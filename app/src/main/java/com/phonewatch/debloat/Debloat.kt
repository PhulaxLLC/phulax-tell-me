package com.phonewatch.debloat

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/** Universal Android Debloater (UAD-ng) rating for a package. GPL-3.0, see LICENSES.md. */
data class UadInfo(val list: String, val removal: String, val description: String)

data class Pkg(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val installed: Boolean,     // false = removed for this user (restorable)
    val sizeBytes: Long,
    val lastUsed: Long,
    val uad: UadInfo?,
    val hardBlocked: String?,   // reason it can never be removed here, or null
)

object Uad {
    @Volatile private var map: Map<String, UadInfo>? = null
    fun get(ctx: Context): Map<String, UadInfo> = map ?: synchronized(this) {
        map ?: run {
            val o = JSONObject(ctx.assets.open("data/uad.json").bufferedReader().use { it.readText() })
            val m = HashMap<String, UadInfo>(o.length())
            for (k in o.keys()) { val a = o.getJSONArray(k); m[k] = UadInfo(a.getString(0), a.getString(1), a.getString(2)) }
            m.also { map = it }
        }
    }
}

object Safety {
    /** Never removable, whatever the expert setting: removing these can stop the phone booting or calling. */
    private val CORE = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.providers.telephony", "com.android.server.telecom", "com.android.providers.settings",
        "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.webview", "com.android.webview", "com.android.networkstack",
        "com.google.android.networkstack", "com.android.bluetooth", "com.android.nfc",
        "com.android.se", "com.android.keychain", "com.android.location.fused",
        "com.android.inputdevices", "com.android.externalstorage", "com.android.providers.media",
        "com.android.providers.media.module", "com.google.android.providers.media.module",
        "com.android.providers.downloads", "com.android.providers.contacts", "com.android.providers.calendar",
        "com.android.emergency", "com.samsung.android.emergency", "com.android.cellbroadcastreceiver",
        "com.sec.android.app.launcher", "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.samsung.android.messaging", "com.sec.imsservice", "com.samsung.android.providers.context",
        "com.sec.android.provider.badge", "com.samsung.android.lool", "com.android.wallpaper.livepicker",
        "com.sec.android.app.SecSetupWizard", "com.google.android.setupwizard",
        "com.phonewatch.app", "moe.shizuku.privileged.api",
    )

    fun hardBlock(pkg: String, uad: UadInfo?, launchers: Set<String>, keyboards: Set<String>): String? {
        if (pkg in CORE) return "Core system / phone / Play services: never removed"
        if (pkg.contains("knox", ignoreCase = true)) return "Samsung Knox: removal can cause boot loops"
        if (pkg in launchers && launchers.size <= 1) return "Your only home-screen app"
        if (pkg in keyboards && keyboards.size <= 1) return "Your only keyboard"
        val d = uad?.description?.lowercase() ?: ""
        if (d.contains("bootloop") || d.contains("boot loop") || d.contains("soft-brick") || d.contains("brick"))
            return "UAD warns: bootloop risk"
        return null
    }

    /** May be removed with the current settings? */
    fun allowed(p: Pkg, expert: Boolean): Boolean {
        if (p.hardBlocked != null) return false
        if (p.uad?.removal == "Unsafe" && !expert) return false
        return true
    }
}

/** Runs pm/cmd/dumpsys/appops as the ADB shell user: through the built-in helper (com.phonewatch.adb.AdbShell) when it is
 *  connected, otherwise through the Shizuku app if that is running. */
object Shell {
    private const val TAG = "PW.Shell"
    enum class State { NOT_INSTALLED, NOT_RUNNING, NEED_PERMISSION, READY }

    fun state(ctx: Context): State {
        if (com.phonewatch.adb.AdbShell.ready()) return State.READY
        val installed = runCatching { ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        if (Shizuku.isPreV11()) return State.NOT_RUNNING
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.READY else State.NEED_PERMISSION
    }

    fun requestPermission() = runCatching { Shizuku.requestPermission(42) }

    data class Result(val code: Int, val out: String)

    /** Shizuku.newProcess is hidden in API 13; call it reflectively (same as other debloaters). */
    fun run(vararg cmd: String, log: Boolean = true): Result = com.phonewatch.adb.AdbShell.takeIf { it.ready() }?.exec(cmd.toList())?.let { (code, out) ->
        Log.i(TAG, "${cmd.joinToString(" ")} -> $code ${if (log) out else "(${out.length} chars)"} [helper]"); Result(code, out)
    } ?: runShizuku(*cmd, log = log)

    private fun runShizuku(vararg cmd: String, log: Boolean): Result = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf(*cmd), null, null) as java.lang.Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        Log.i(TAG, "${cmd.joinToString(" ")} -> $code ${if (log) out.trim() else "(${out.length} chars)"}")
        Result(code, out.trim())
    } catch (e: Throwable) {
        Log.e(TAG, "run", e); Result(-1, e.cause?.message ?: e.message ?: "error")
    }

    private val PKG_RE = Regex("^[A-Za-z0-9_.]+$")
    private fun safe(pkg: String) = require(PKG_RE.matches(pkg)) { "bad package name" }

    fun uninstallCmd(pkg: String) = "pm uninstall -k --user 0 $pkg"
    fun disableCmd(pkg: String) = "pm disable-user --user 0 $pkg"
    fun restoreCmd(pkg: String) = "cmd package install-existing --user 0 $pkg"
    fun enableCmd(pkg: String) = "pm enable --user 0 $pkg"

    fun uninstall(pkg: String): Result { safe(pkg); return run("pm", "uninstall", "-k", "--user", "0", pkg) }
    fun disable(pkg: String): Result { safe(pkg); return run("pm", "disable-user", "--user", "0", pkg) }
    fun restore(pkg: String): Result {
        safe(pkg)
        val r = run("cmd", "package", "install-existing", "--user", "0", pkg)
        val e = run("pm", "enable", "--user", "0", pkg) // also undo a disable
        return if (r.code == 0 || e.code == 0) Result(0, r.out + "\n" + e.out) else r
    }
}

object PackageLister {
    fun list(ctx: Context, companiesToday: Map<String, Int>): List<Pkg> {
        val pm = ctx.packageManager
        val uad = Uad.get(ctx)
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val apps = pm.getInstalledApplications(flags)
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.filter { it != "com.android.settings" }.toSet()
        val keyboards = runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        val usage = lastUsedMap(ctx)
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val canStats = usage.isNotEmpty()
        return apps.map { ai ->
            val installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            val size = if (canStats && installed) runCatching {
                ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle()).let { it.appBytes + it.dataBytes + it.cacheBytes }
            }.getOrDefault(apkSize(ai)) else apkSize(ai)
            val info = uad[ai.packageName]
            Pkg(
                pkg = ai.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                enabled = ai.enabled,
                installed = installed,
                sizeBytes = size,
                lastUsed = usage[ai.packageName] ?: 0,
                uad = info,
                hardBlocked = Safety.hardBlock(ai.packageName, info, launchers, keyboards),
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun apkSize(ai: ApplicationInfo) = runCatching { File(ai.sourceDir).length() }.getOrDefault(0L)

    fun usageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun lastUsedMap(ctx: Context): Map<String, Long> {
        if (!usageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        return runCatching {
            usm.queryAndAggregateUsageStats(now - 365L * 24 * 3600 * 1000, now).mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap())
    }

    /** Before a batch removal, save the list + restore commands to Downloads. Returns the file name. */
    fun exportList(ctx: Context, pkgs: List<Pkg>, action: String): String? {
        val name = "Phulax Android Tell Me-debloat-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
        val text = buildString {
            appendLine("Phulax Android Tell Me debloat list ($action) - ${java.util.Date()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine()
            appendLine("# Packages")
            pkgs.forEach { appendLine("${it.pkg}\t${it.label}\t${it.uad?.removal ?: "unrated"}") }
            appendLine()
            appendLine("# To restore from a computer (adb):")
            pkgs.forEach { appendLine("adb shell ${Shell.restoreCmd(it.pkg)}") }
        }
        return runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            File(ctx.filesDir, "exports").apply { mkdirs() }.resolve(name).writeText(text)
            "Downloads/$name"
        }.getOrNull()
    }
}
