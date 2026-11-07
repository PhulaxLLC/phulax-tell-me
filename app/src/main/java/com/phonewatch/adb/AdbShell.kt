package com.phonewatch.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The built-in helper: the same ADB-level shell Shizuku gives, without the Shizuku app. Pairs once with Android's
 *  Wireless debugging (AdbPairingService), then connects to it over localhost and runs commands as the shell user.
 *  ADB code adapted from Shizuku (RikkaApps, Apache-2.0). After a restart it turns Wireless debugging back on by itself
 *  (WRITE_SECURE_SETTINGS, granted through the helper on first connect), so it only needs WiFi to come back. */
object AdbShell {
    private const val TAG = "PW.AdbShell"
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    private lateinit var ctx: Context
    private val prefs by lazy { ctx.getSharedPreferences("adb", Context.MODE_PRIVATE) }
    @Volatile private var client: AdbClient? = null
    private val lock = Any()

    fun init(c: Context) { ctx = c.applicationContext }

    val paired: Boolean get() = prefs.getBoolean("paired", false)
    fun markPaired() = prefs.edit().putBoolean("paired", true).apply()
    fun ready() = client != null
    val wirelessDebuggingOn: Boolean get() = Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
    private fun canWriteSecure() = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun key() = AdbKey(PreferenceAdbKeyStore(prefs), "phulax")

    /** Finds the Wireless debugging port (mDNS) and connects. Blocking, a few seconds at most: call off the main thread. */
    fun connect(): Boolean { synchronized(lock) {
        if (client != null) return true
        if (!wirelessDebuggingOn && canWriteSecure()) {      // turn it back on after a restart
            runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1) }
            Thread.sleep(2500)
        }
        val ports = LinkedHashSet<Int>()
        val found = CountDownLatch(1)
        val mdns = AdbMdns(ctx, AdbMdns.TLS_CONNECT) { p -> if (p > 0) { ports += p; found.countDown() } }
        runCatching { mdns.start(); found.await(6, TimeUnit.SECONDS) }
        runCatching { mdns.stop() }
        prefs.getInt("port", -1).takeIf { it > 0 }?.let { ports += it }
        for (p in ports) {
            val c = AdbClient("127.0.0.1", p, key())
            if (runCatching { c.connect() }.onFailure { Log.w(TAG, "connect $p: $it") }.isSuccess) {
                client = c; prefs.edit().putInt("port", p).putBoolean("paired", true).apply()
                if (!canWriteSecure()) runRaw("pm grant ${ctx.packageName} $PERM")
                Log.i(TAG, "connected on $p")
                return true
            }
            runCatching { c.close() }
        }
        return false
    } }

    /** Runs one shell command; returns exit code and output. Reconnects once if the link dropped. */
    fun exec(cmd: List<String>): Pair<Int, String>? {
        val line = cmd.joinToString(" ") { if (Regex("^[A-Za-z0-9_./:=@%+,-]+$").matches(it)) it else "'" + it.replace("'", "'\\''") + "'" }
        return runRaw(line) ?: run { synchronized(lock) { runCatching { client?.close() }; client = null }; if (connect()) runRaw(line) else null }
    }

    private fun runRaw(line: String): Pair<Int, String>? { synchronized(lock) {
        val c = client ?: return null
        val out = java.io.ByteArrayOutputStream()
        return runCatching {
            c.shellCommand("$line 2>&1; echo \"__rc=$?\"") { out.write(it) }
            val s = out.toString(Charsets.UTF_8.name())
            val rc = Regex("__rc=(-?\\d+)\\s*$").find(s)
            (rc?.groupValues?.get(1)?.toInt() ?: -1) to s.substring(0, rc?.range?.first ?: s.length).trim()
        }.onFailure { Log.w(TAG, "run: $it") }.getOrNull()
    } }

    fun disconnect() = synchronized(lock) { runCatching { client?.close() }; client = null }
}
