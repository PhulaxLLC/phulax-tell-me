package com.phonewatch.inspect

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.phonewatch.data.Apps
import com.phonewatch.data.Repo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Deep Inspect settings and state. OFF by default. When on, TLS (443) and plain HTTP (80) connections of
 * the chosen apps are sent through PhoneWatch's on-phone proxy (MitmProxy), decrypted with PhoneWatch's
 * own CA, logged to the encrypted capture database and re-encrypted to the real server.
 */
object DeepInspect {
    private const val TAG = "PW.Deep"
    private lateinit var sp: SharedPreferences
    lateinit var app: Context; private set
    private val ver = mutableIntStateOf(0)
    private fun bump() { ver.intValue++ }
    private fun <T> r(v: T): T { ver.intValue; return v }

    fun init(c: Context) {
        app = c.applicationContext
        sp = app.getSharedPreferences("deep", Context.MODE_PRIVATE)
        CaManager.init(app)
        if (enabled && !CaManager.exists()) enabled = false
    }

    var enabled: Boolean get() = !com.phonewatch.BuildConfig.PLAY && r(sp.getBoolean("on", false)); set(v) { sp.edit().putBoolean("on", v).apply(); bump() }
    var allApps: Boolean get() = r(sp.getBoolean("all", false)); set(v) { sp.edit().putBoolean("all", v).apply(); bump() }
    var retentionDays: Int get() = r(sp.getInt("days", 7)); set(v) { sp.edit().putInt("days", v.coerceIn(1, 90)).apply(); bump() }
    var bodyCapKb: Int get() = r(sp.getInt("capKb", 256)); set(v) { sp.edit().putInt("capKb", v.coerceIn(16, 4096)).apply(); bump() }
    /** pkg -> true (inspect) / false (don't). Absent = default (all-apps switch minus the exclusion list). */
    private fun choice(pkg: String): Boolean? = r(if (sp.contains("app:$pkg")) sp.getBoolean("app:$pkg", false) else null)
    fun setApp(pkg: String, on: Boolean) { sp.edit().putBoolean("app:$pkg", on).apply(); bump() }
    fun clearApp(pkg: String) { sp.edit().remove("app:$pkg").apply(); bump() }
    fun chosenApps(): Set<String> = r(sp.all.keys.filter { it.startsWith("app:") && sp.getBoolean(it, false) }.map { it.removePrefix("app:") }.toSet())

    fun bodyCap(): Int = bodyCapKb * 1024

    /** Should this app's web traffic go through the proxy? */
    fun appSelected(pkg: String): Boolean {
        choice(pkg)?.let { return it }
        return allApps && Exclusions.reason(pkg) == null
    }

    // ---- per app+server "can't inspect" memory (apps that don't trust user CAs or pin certificates) ----
    private val cant = ConcurrentHashMap<String, Boolean>()   // "uid|host" or "uid|*"
    private val strikes = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    fun loadStatus() {
        if (loaded || !CaptureDb.exists(app) && !enabled) return
        runCatching { CaptureDb.get(app).dao().cantInspect().forEach { cant[it.key] = true } }
        loaded = true
    }

    fun cantInspect(uid: Int, host: String?): Boolean = cant["$uid|*"] == true || (host != null && cant["$uid|$host"] == true)

    /** Called from the packet thread on a new TCP connection. */
    fun intercept(uid: Int, dport: Int): Boolean {
        if (!enabled || !MitmProxy.running || uid < Process.FIRST_APPLICATION_UID) return false
        if (dport != 443 && dport != 80) return false
        if (cant["$uid|*"] == true) return false
        val meta = Apps.meta(uid)
        if (meta.pkg == app.packageName) return false
        return meta.packages.any { appSelected(it) }
    }

    private val hard = ConcurrentHashMap<String, Boolean>()   // hosts where the app actively rejected our certificate
    private val good = ConcurrentHashMap<String, Boolean>()   // hosts where the app sent a request through us

    fun sawRequest(uid: Int, host: String) { good["$uid|$host"] = true; good["$uid|*"] = true }

    /** [hardFail] = the app answered our certificate with a TLS alert (clear rejection). */
    fun markCantInspect(uid: Int, host: String, why: String, hardFail: Boolean = true) {
        cant["$uid|$host"] = true
        if (hardFail) hard["$uid|$host"] = true
        status(uid, host, "cant_inspect", why)
        // Several different servers clearly rejected us and nothing ever worked: the whole app distrusts
        // user certificates. Stop proxying it.
        if (good["$uid|*"] == true) return
        val n = hard.keys.count { it.startsWith("$uid|") }
        if (n >= 3 && cant["$uid|*"] != true) { cant["$uid|*"] = true; status(uid, "*", "cant_inspect", "App doesn't trust user certificates (or pins its own). Its traffic is passed through untouched.") }
    }

    /** A connection that completed its handshake but closed without sending anything: possible pinning. */
    fun strike(uid: Int, host: String): Boolean {
        val k = "$uid|$host"
        if (good[k] == true) return false   // the app did use this server through us (e.g. a browser closing a spare connection)
        val n = (strikes[k] ?: 0) + 1
        strikes[k] = n
        return n >= 3
    }

    fun forgetCantInspect(uid: Int) {
        cant.keys.filter { it.startsWith("$uid|") }.forEach { cant.remove(it) }
        hard.keys.filter { it.startsWith("$uid|") }.forEach { hard.remove(it) }
        strikes.keys.filter { it.startsWith("$uid|") }.forEach { strikes.remove(it) }
        Repo.io.launch { runCatching { val d = CaptureDb.get(app).dao(); d.statusesNow().filter { it.uid == uid && it.status == "cant_inspect" }.forEach { d.deleteStatus(it.key) } } }
    }

    // ---- writer: batches DB writes off the network threads ----
    private sealed class Op { class F(val f: CapFlow) : Op(); class S(val s: HostStatus) : Op() }
    private val ch = Channel<Op>(Channel.UNLIMITED)
    private val statusCache = ConcurrentHashMap<String, HostStatus>()
    @Volatile private var writer = false

    fun save(f: CapFlow) { ensureWriter(); ch.trySend(Op.F(f)) }

    fun status(uid: Int, host: String, status: String, detail: String?) {
        ensureWriter()
        val key = "$uid|$host"
        val now = System.currentTimeMillis()
        val old = statusCache[key]
        val s = HostStatus(key, uid, Apps.meta(uid).pkg, host, status, detail, (if (old?.status == status) old.count else 0) + 1, old?.firstTime ?: now, now)
        // don't let a later plain "inspected" hide the reason an app/host could not be inspected
        if (old != null && old.status == "cant_inspect" && status == "inspected" && cant[key] == true) return
        statusCache[key] = s
        ch.trySend(Op.S(s))
    }

    private fun ensureWriter() {
        if (writer) return
        synchronized(this) {
            if (writer) return
            writer = true
            Repo.io.launch {
                val flows = LinkedHashMap<Long, CapFlow>(); val sts = LinkedHashMap<String, HostStatus>()
                while (true) {
                    when (val o = ch.receive()) { is Op.F -> flows[o.f.id] = o.f; is Op.S -> sts[o.s.key] = o.s }
                    delay(500)
                    while (true) { when (val o = ch.tryReceive().getOrNull() ?: break) { is Op.F -> flows[o.f.id] = o.f; is Op.S -> sts[o.s.key] = o.s } }
                    runCatching {
                        val d = CaptureDb.get(app).dao()
                        if (flows.isNotEmpty()) d.upsert(flows.values.toList())
                        if (sts.isNotEmpty()) d.upsertStatus(sts.values.toList())
                    }.onFailure { Log.w(TAG, "write", it) }
                    flows.clear(); sts.clear()
                }
            }
        }
    }

    fun purgeOld() = runCatching {
        if (!CaptureDb.exists(app)) return@runCatching
        val before = System.currentTimeMillis() - retentionDays * 86_400_000L
        val d = CaptureDb.get(app).dao()
        d.deleteBefore(before); d.deleteStatusBefore(before)
    }

    fun wipeAll() {
        cant.clear(); strikes.clear(); statusCache.clear()
        CaptureDb.wipe(app)
    }
}

/** Apps never inspected unless the owner turns them on one by one: money, passwords, 2FA, Google Play services. */
object Exclusions {
    private val exact = mapOf(
        "com.google.android.gms" to "Google Play services", "com.google.android.gsf" to "Google Play services",
        "com.android.vending" to "Play Store", "com.google.android.apps.walletnfcrel" to "Google Wallet",
        "com.samsung.android.spay" to "Samsung Wallet / Pay", "com.samsung.android.spaymini" to "Samsung Pay",
        "com.samsung.android.samsungpay.gear" to "Samsung Pay", "com.samsung.android.wallet" to "Samsung Wallet",
        "com.samsung.android.samsungpass" to "Samsung Pass (passwords)",
        "com.google.android.apps.authenticator2" to "Authenticator", "com.azure.authenticator" to "Authenticator",
        "com.authy.authy" to "Authenticator", "com.duosecurity.duomobile" to "Authenticator", "com.beemdevelopment.aegis" to "Authenticator",
        "org.fedorahosted.freeotp" to "Authenticator", "com.twofasapp" to "Authenticator", "com.okta.android.auth" to "Authenticator",
        "com.x8bit.bitwarden" to "Password manager", "com.onepassword.android" to "Password manager", "com.agilebits.onepassword" to "Password manager",
        "com.lastpass.lpandroid" to "Password manager", "com.dashlane" to "Password manager", "com.callpod.android_apps.keeper" to "Password manager",
        "com.nordpass.android.app.password.manager" to "Password manager", "proton.android.pass" to "Password manager", "keepass2android.keepass2android" to "Password manager",
        "com.kunzisoft.keepass.free" to "Password manager", "in.sinew.enpass" to "Password manager",
        "com.paypal.android.p2pmobile" to "Payments", "com.venmo" to "Payments", "com.squareup.cash" to "Payments", "com.zellepay.zelle" to "Payments",
        "com.chase.sig.android" to "Bank", "com.wf.wellsfargomobile" to "Bank", "com.infonow.bofa" to "Bank", "com.konylabs.capitalone" to "Bank",
        "com.citi.citimobile" to "Bank", "com.usaa.mobile.android.usaa" to "Bank", "com.discoverfinancial.mobile" to "Bank", "com.americanexpress.android.acctsvcs.us" to "Bank",
        "com.schwab.mobile" to "Investing", "com.fidelity.android" to "Investing", "com.robinhood.android" to "Investing", "com.etrade.mobilepro.activity" to "Investing",
        "com.vanguard" to "Investing", "com.ally.MobileBanking" to "Bank", "com.coinbase.android" to "Crypto", "com.binance.dev" to "Crypto",
    )
    private val words = listOf("bank", "banking", "finance", "financial", "creditunion", "credit_union", "wallet", "invest", "trading", "brokerage",
        "crypto", "bitcoin", "password", "passwordmanager", "authenticator", "2fa", "otp", "mfa")

    /** Why this app is on the default exclusion list, or null. */
    fun reason(pkg: String): String? {
        exact[pkg]?.let { return it }
        val p = pkg.lowercase()
        if (p.startsWith("com.google.android.gms")) return "Google Play services"
        val parts = p.split('.', '_')
        words.firstOrNull { w -> parts.any { it == w || it.startsWith(w) } || p.contains(".$w") }?.let { return "Money / passwords / 2FA ($it)" }
        val label = runCatching { Apps.meta(Apps.uidOf(pkg) ?: -1).label.lowercase() }.getOrDefault("")
        if (Regex("\\b(bank|credit union|wallet|pay|authenticator|password)\\b").containsMatchIn(label)) return "Money / passwords / 2FA"
        return null
    }
}
