package com.phonewatch.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

object Prefs {
    private lateinit var sp: SharedPreferences
    val state = MutableStateFlow(0) // bumps when anything changes, so the UI can recompose
    /** Compose snapshot state read by every getter, so any screen that shows a pref redraws when it changes
     *  (a plain StateFlow read at the top of a screen does not redraw skipped LazyColumn items). */
    private val ver = mutableIntStateOf(0)
    fun init(c: Context) { sp = c.getSharedPreferences("pw", Context.MODE_PRIVATE) }
    private fun bump() { state.value++; ver.intValue++ }
    private fun <T> r(v: T): T { ver.intValue; return v }

    var vpnWanted: Boolean get() = r(sp.getBoolean("vpn", false)); set(v) { sp.edit().putBoolean("vpn", v).apply(); bump() }
    /** Blocking on/off. Monitoring keeps running when this is off ("paused"). */
    var blocking: Boolean get() = r(sp.getBoolean("blocking", true)); set(v) { sp.edit().putBoolean("blocking", v).apply(); bump() }
    var pausedUntil: Long get() = r(sp.getLong("pausedUntil", 0)); set(v) { sp.edit().putLong("pausedUntil", v).apply(); bump() }
    var safeMode: Boolean get() = r(sp.getBoolean("safe", true)); set(v) { sp.edit().putBoolean("safe", v).apply(); bump() }
    var allowListUpdates: Boolean get() = r(sp.getBoolean("updates", false)); set(v) { sp.edit().putBoolean("updates", v).apply(); bump() }
    var expertDebloat: Boolean get() = r(sp.getBoolean("expert", false)); set(v) { sp.edit().putBoolean("expert", v).apply(); bump() }
    var lastListUpdate: Long get() = r(sp.getLong("listUpdate", 0)); set(v) { sp.edit().putLong("listUpdate", v).apply(); bump() }

    fun blockingActive(): Boolean = blocking && System.currentTimeMillis() >= pausedUntil
}

data class Decision(val blocked: Boolean, val reason: String?)

/** In-memory snapshot of the rules table, rebuilt whenever rules change. Thread safe, lock free reads. */
object RuleEngine {
    private class Snapshot(
        val globalCats: Int,
        val appCats: Map<String, Int>,
        val globalCompanies: Set<String>,
        val appCompanies: Map<String, Set<String>>,
        val globalDomains: Set<String>,
        val appDomains: Map<String, Set<String>>,
        val allowDomains: Set<String>,
        val exempt: Set<String>,
        val blockAll: Set<String> = emptySet(),      // apps with all internet blocked
        val onlyOwn: Set<String> = emptySet(),       // apps limited to their own company's servers
    )

    @Volatile private var snap = Snapshot(0, emptyMap(), emptySet(), emptyMap(), emptySet(), emptyMap(), emptySet(), emptySet())
    private val cache = ConcurrentHashMap<String, Decision>()
    val version = MutableStateFlow(0)
    /** Snapshot state read by the UI helpers below so switches redraw as soon as a rule changes. */
    private val uiVer = mutableIntStateOf(0)
    private fun ui(): Snapshot { uiVer.intValue; return snap }

    @Volatile private var current: List<Rule> = emptyList()

    /** Optimistic update: the switch and the engine change at once, the database write follows. */
    @Synchronized fun applyLocal(kind: String, target: String, pkg: String?, on: Boolean) {
        val rest = current.filterNot { it.kind == kind && it.target == target && it.pkg == pkg }
        load(if (on) rest + Rule(kind = kind, target = target, pkg = pkg) else rest)
    }

    @Synchronized fun load(rules: List<Rule>) {
        current = rules
        var gc = 0
        val ac = HashMap<String, Int>()
        val gco = HashSet<String>(); val aco = HashMap<String, MutableSet<String>>()
        val gd = HashSet<String>(); val ad = HashMap<String, MutableSet<String>>()
        val allow = HashSet<String>(); val ex = HashSet<String>()
        val all = HashSet<String>(); val own = HashSet<String>()
        for (r in rules) when (r.kind) {
            "category" -> { val bit = Cat.byName(r.target)?.bit ?: 0; if (r.pkg == null) gc = gc or bit else ac[r.pkg] = (ac[r.pkg] ?: 0) or bit }
            "company" -> if (r.pkg == null) gco += r.target else aco.getOrPut(r.pkg) { HashSet() } += r.target
            "domain" -> if (r.pkg == null) gd += r.target.lowercase() else ad.getOrPut(r.pkg) { HashSet() } += r.target.lowercase()
            "allowdomain" -> allow += r.target.lowercase()
            "exempt" -> r.pkg?.let { ex += it }
            "appblockall" -> r.pkg?.let { all += it }
            "apponlyown" -> r.pkg?.let { own += it }
        }
        snap = Snapshot(gc, ac, gco, aco, gd, ad, allow, ex, all, own)
        cache.clear()
        version.value++
        uiVer.intValue++
    }

    fun clearCache() = cache.clear()

    private fun suffixIn(host: String, set: Set<String>): String? {
        if (set.isEmpty()) return null
        var h = host
        while (true) {
            if (h in set) return h
            val i = h.indexOf('.'); if (i < 0) return null
            h = h.substring(i + 1)
        }
    }

    /**
     * Should a connection from [uid] to [host] be blocked? [alsoNames] are CNAME targets seen in DNS
     * (catches trackers hidden behind a first-party name, "CNAME cloaking").
     */
    fun decide(uid: Int, host: String?, alsoNames: Collection<String> = emptyList()): Decision {
        if (!Prefs.blockingActive()) return Decision(false, null)
        if (host.isNullOrEmpty()) {
            // Unknown server name (IP only): only "block all internet for this app" applies
            val s = snap
            if (s.blockAll.isEmpty()) return Decision(false, null)
            val meta = Apps.meta(uid)
            if (Prefs.safeMode && Apps.isProtected(uid)) return Decision(false, null)
            if (meta.packages.any { it in s.exempt }) return Decision(false, null)
            return if (meta.packages.any { it in s.blockAll }) Decision(true, "All internet blocked for this app") else Decision(false, null)
        }
        val key = "$uid|$host"
        cache[key]?.let { return it }
        val d = compute(uid, host.lowercase().trimEnd('.'), alsoNames)
        if (cache.size > 20000) cache.clear()
        cache[key] = d
        return d
    }

    private fun compute(uid: Int, host: String, alsoNames: Collection<String>): Decision {
        val s = snap
        val meta = Apps.meta(uid)
        if (Prefs.safeMode && Apps.isProtected(uid)) return Decision(false, null)
        if (meta.packages.any { it in s.exempt }) return Decision(false, null)
        if (meta.packages.any { it in s.blockAll }) return Decision(true, "All internet blocked for this app")
        if (suffixIn(host, s.allowDomains) != null) return Decision(false, null)
        suffixIn(host, s.globalDomains)?.let { return Decision(true, "Domain $it blocked") }
        for (p in meta.packages) s.appDomains[p]?.let { set -> suffixIn(host, set)?.let { return Decision(true, "Domain $it blocked for this app") } }

        for (name in listOf(host) + alsoNames) {
            val hit = TrackerDb.lookup(name) ?: continue
            val company = hit.company.name
            if (company in s.globalCompanies) return Decision(true, "$company blocked everywhere")
            for (p in meta.packages) if (s.appCompanies[p]?.contains(company) == true) return Decision(true, "$company blocked for this app")
            if (Apps.isOwnServer(meta.pkg, name, company, hit.cats)) continue // never category-block an app's own servers
            val cats = hit.cats
            val g = cats and s.globalCats
            if (g != 0) return Decision(true, Cat.of(g).first().label + " blocked")
            for (p in meta.packages) {
                val a = cats and (s.appCats[p] ?: 0)
                if (a != 0) return Decision(true, Cat.of(a).first().label + " blocked for this app")
            }
        }
        if (meta.packages.any { it in s.onlyOwn }) {
            val names = listOf(host) + alsoNames
            val own = names.any { n -> val h = TrackerDb.lookup(n); Apps.isOwnServer(meta.pkg, n, h?.company?.name, h?.cats ?: 0) }
            if (!own) return Decision(true, "Only its own servers allowed for this app")
        }
        return Decision(false, null)
    }

    // ---- helpers used by the UI ----
    fun globalCats(): Int = ui().globalCats
    fun appCats(pkg: String): Int = ui().appCats[pkg] ?: 0
    fun companyBlocked(company: String) = company in ui().globalCompanies
    fun companyBlockedFor(pkg: String, company: String) = ui().appCompanies[pkg]?.contains(company) == true
    fun exempt(pkg: String) = pkg in ui().exempt
    fun domainBlocked(domain: String) = domain.lowercase() in ui().globalDomains
    fun domainBlockedFor(pkg: String, domain: String) = ui().appDomains[pkg]?.contains(domain.lowercase()) == true
    fun blockAll(pkg: String) = pkg in ui().blockAll
    fun onlyOwn(pkg: String) = pkg in ui().onlyOwn
}
