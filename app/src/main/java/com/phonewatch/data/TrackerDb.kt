package com.phonewatch.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** What a server is used for. Bit flags so one domain can have several. */
enum class Cat(val bit: Int, val label: String, val blurb: String, val blockable: Boolean = true) {
    ADS(1, "Advertising", "Ad networks. Typically receive device/ad IDs, app name, rough location and what you look at, to target ads."),
    ANALYTICS(2, "Analytics", "Usage tracking. Typically receive which screens you open, taps, session length, device model and an install ID."),
    CRASH(4, "Crash reporting", "Error reports. Typically receive device model, OS version, app state and logs when something breaks."),
    SOCIAL(8, "Social", "Social networks (Facebook, X, TikTok...). Login, share and tracking SDKs that tie activity to a social profile."),
    FINGERPRINT(16, "Fingerprinting", "Device fingerprinting / identification. Combines device details to recognise you without cookies."),
    LOCATION(32, "Location", "Location data companies. Collect GPS or Wi-Fi location, often resold."),
    TRACKING(256, "Other tracking", "Known tracker with no more specific category."),
    CONTENT(64, "Content / CDN", "Delivers images, video, fonts and code. Usually needed for apps to work. Blocking often breaks things."),
    OWN(128, "App's own servers", "Servers run by the company that made the app.", blockable = false),
    UNKNOWN(512, "Unclassified", "Not in any bundled list. Could be anything; often the app's own back end.", blockable = false);

    companion object {
        fun of(mask: Int): List<Cat> = entries.filter { mask and it.bit != 0 }
        fun byName(n: String): Cat? = entries.firstOrNull { it.name == n }
        val TRACKER_MASK = ADS.bit or ANALYTICS.bit or CRASH.bit or SOCIAL.bit or FINGERPRINT.bit or LOCATION.bit or TRACKING.bit
    }
}

class Company(val name: String) {
    var legal: String? = null
    var cats: Int = 0
    val signals = sortedSetOf<String>()
    val services = sortedSetOf<String>()
    var about: String? = null
    var aboutSource: String? = null
    var website: String? = null
    var domainCount = 0
}

class DomainEntry(val company: Company, var cats: Int, val service: String?)

data class Hit(val domain: String, val company: Company, val cats: Int, val service: String?)

/**
 * Domain -> company/category database, built at start-up from the open datasets in
 * assets/data (or the user's manually downloaded copies in filesDir/lists):
 *  - DuckDuckGo App Tracker blocklist (android-tds.json)  CC BY-NC-SA 4.0
 *  - Disconnect tracking protection services.json          CC BY-NC-SA 4.0
 *  - Exodus Privacy tracker list (exodus-trackers.json)    ODbL 1.0 / DbCL 1.0
 *  - AdGuard companiesdb (adguard-trackers/companies.json) CC BY-SA 4.0
 *  - EasyPrivacy (easyprivacy.txt)                         GPL-3.0 / CC BY-SA 3.0
 *  - HaGeZi Pro DNS blocklist (hagezi-pro.txt)             GPL-3.0
 *  - PhoneWatch CDN list (cdn.json, hand made)
 */
object TrackerDb {
    private const val TAG = "PW.TrackerDb"
    @Volatile var domains: Map<String, DomainEntry> = emptyMap(); private set
    @Volatile var companies: Map<String, Company> = emptyMap(); private set
    /** package name -> company group (from DuckDuckGo's packageNames list). */
    @Volatile var appOwners: Map<String, String> = emptyMap(); private set
    @Volatile var loaded = false; private set
    @Volatile var sourceInfo = ""; private set
    /** Sorted tracker domains from EasyPrivacy + HaGeZi that have no entry of their own in [domains]. */
    @Volatile private var blocked: Array<String> = emptyArray()
    private val rootCompanies = HashMap<String, Company>()

    /** Company "group" so that e.g. DoubleClick, AdMob and Firebase all show as Google. */
    private val GROUPS = listOf(
        Regex("google|doubleclick|admob|firebase|crashlytics|fabric|youtube|gstatic|alphabet|urchin|invite media|adometry|teracent|apigee|recaptcha") to "Google",
        Regex("facebook|instagram|whatsapp|meta platforms|^meta$|oculus|atlas solutions") to "Meta (Facebook)",
        Regex("twitter|^x corp|mopub-twitter|tweetdeck|vine") to "X (Twitter)",
        Regex("linkedin|microsoft|bing|xandr|appnexus|hockeyapp|github|skype|visual studio app center|msn") to "Microsoft",
        Regex("amazon|aws|twitch|alexa|sizmek|audible") to "Amazon",
        Regex("yahoo|oath|verizon media|flurry|aol|yieldmo|brightroll|one by aol") to "Yahoo",
        Regex("mopub|applovin|adcolony|max ad") to "AppLovin",
        Regex("unity|ironsource|supersonic|tapjoy") to "Unity Software",
        Regex("bytedance|tiktok|pangle|musical\\.ly") to "ByteDance (TikTok)",
        Regex("oracle|bluekai|moat|addthis|datalogix|grapeshot") to "Oracle",
        Regex("salesforce|krux|exacttarget|pardot|evergage") to "Salesforce",
        Regex("adobe|omniture|demdex|tubemogul|marketo") to "Adobe",
        Regex("comscore|scorecard research|scorecardresearch") to "Comscore",
        Regex("^snap|snapchat") to "Snap",
        Regex("tencent|wechat|qq ") to "Tencent",
        Regex("alibaba|umeng|alipay|taobao") to "Alibaba",
        Regex("baidu") to "Baidu",
        Regex("yandex|appmetrica") to "Yandex",
        Regex("samsung") to "Samsung",
        Regex("^apple|itunes") to "Apple",
        Regex("criteo") to "Criteo",
        Regex("appsflyer") to "AppsFlyer",
        Regex("adjust") to "Adjust",
        Regex("branch metrics|^branch$") to "Branch",
        Regex("braze|appboy") to "Braze",
        Regex("new relic") to "New Relic",
        Regex("sentry") to "Sentry",
        Regex("taboola") to "Taboola",
        Regex("outbrain") to "Outbrain",
        Regex("the trade desk|adsrvr") to "The Trade Desk",
        Regex("pubmatic") to "PubMatic",
        Regex("magnite|rubicon") to "Magnite",
        Regex("openx") to "OpenX",
        Regex("inmobi") to "InMobi",
        Regex("vungle|liftoff") to "Liftoff (Vungle)",
        Regex("chartboost") to "Chartboost",
        Regex("digital turbine|fyber|inneractive") to "Digital Turbine",
        Regex("mixpanel") to "Mixpanel",
        Regex("amplitude") to "Amplitude",
        Regex("segment") to "Segment (Twilio)",
        Regex("onesignal") to "OneSignal",
        Regex("cloudflare") to "Cloudflare",
        Regex("akamai") to "Akamai",
        Regex("fastly") to "Fastly",
    )

    private val groupCache = HashMap<String, String>()
    private val anyGroup by lazy { Regex(GROUPS.joinToString("|") { "\\b(?:" + it.first.pattern + ")\\b" }) }
    private val groupRes by lazy { GROUPS.map { (re, g) -> Regex("\\b(?:" + re.pattern + ")\\b") to g } }

    fun groupOf(raw: String): String = synchronized(groupCache) { groupCache.getOrPut(raw) { groupOfRaw(raw) } }

    private fun groupOfRaw(raw: String): String {
        val clean = raw.trim()
            .replace(Regex(",?\\s+(inc\\.?|llc|ltd\\.?|limited|corporation|corp\\.?|gmbh|s\\.?a\\.?s?|b\\.?v\\.?|plc|co\\.?|ag|pte\\.? ltd\\.?|sa)$", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd(',')
        val low = clean.lowercase()
        if (!anyGroup.containsMatchIn(low)) return clean
        for ((re, g) in groupRes) if (re.containsMatchIn(low)) return g
        return clean
    }

    @Synchronized
    fun load(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val doms = HashMap<String, DomainEntry>(40000)
        val comps = HashMap<String, Company>()
        val owners = HashMap<String, String>()
        fun company(raw: String): Company { val g = groupOf(raw); return comps.getOrPut(g) { Company(g) } }
        fun suffixHit(d: String): DomainEntry? {
            var h = d
            while (true) {
                doms[h]?.let { return it }
                val i = h.indexOf('.')
                if (i < 0 || h.indexOf('.', i + 1) < 0) return null
                h = h.substring(i + 1)
            }
        }
        fun clean(domain: String) = domain.trim().lowercase().trimStart('.')
        fun valid(d: String) = d.isNotEmpty() && d.contains('.') && !d.contains('/') && !d.contains('*')
        fun put(domain: String, c: Company, cats: Int, service: String?) {
            val d = clean(domain); if (!valid(d)) return
            val e = doms[d]
            if (e == null) { doms[d] = DomainEntry(c, cats, service); c.domainCount++ } else e.cats = e.cats or cats
            c.cats = c.cats or cats
            if (service != null && service != c.name) c.services.add(service)
        }
        val used = mutableListOf<String>()

        // 0) EasyPrivacy + HaGeZi Pro: plain tracker domain lists (no owner, no category)
        val block = HashSet<String>(300000)
        if (readLines(ctx, "easyprivacy.txt") { l ->
                if (l.startsWith("||")) {
                    val end = l.indexOf('^')
                    if (end > 2) {
                        val opt = l.substring(end + 1)
                        if (opt.isEmpty() || opt == "\$third-party") clean(l.substring(2, end)).takeIf { valid(it) }?.let { block += it }
                    }
                }
            }) used += "EasyPrivacy"
        if (readLines(ctx, "hagezi-pro.txt") { l -> if (!l.startsWith("#")) clean(l).takeIf { valid(it) }?.let { block += it } }) used += "HaGeZi"
        fun listed(d: String): Boolean {
            var h = d
            while (true) {
                if (h in block) return true
                val i = h.indexOf('.')
                if (i < 0 || h.indexOf('.', i + 1) < 0) return false
                h = h.substring(i + 1)
            }
        }

        // 1) Disconnect services.json: categories + company names
        read(ctx, "services.json")?.let { txt ->
            runCatching {
                val cats = JSONObject(txt).getJSONObject("categories")
                for (catName in cats.keys()) {
                    val bit = when (catName) {
                        "Advertising" -> Cat.ADS.bit
                        "Analytics" -> Cat.ANALYTICS.bit
                        "Social" -> Cat.SOCIAL.bit
                        "FingerprintingInvasive", "FingerprintingGeneral", "Anti-fraud" -> Cat.FINGERPRINT.bit
                        "Content" -> Cat.CONTENT.bit
                        "Cryptomining" -> Cat.TRACKING.bit
                        else -> 0 // Email / ConsentManagers: not relevant to phone apps
                    }
                    if (bit == 0) continue
                    val arr = cats.getJSONArray(catName)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        for (name in o.keys()) {
                            val c = company(name)
                            val inner = o.getJSONObject(name)
                            for (k in inner.keys()) {
                                val v = inner.opt(k)
                                if (v is org.json.JSONArray) {
                                    if (c.website == null && k.startsWith("http")) c.website = k
                                    for (j in 0 until v.length()) put(v.getString(j), c, bit, name)
                                }
                            }
                        }
                    }
                }
                used += "Disconnect"
            }.onFailure { Log.w(TAG, "disconnect", it) }
        }

        // 2) DuckDuckGo app tracker list: owners, data "signals", app package owners
        read(ctx, "android-tds.json")?.let { txt ->
            runCatching {
                val root = JSONObject(txt)
                val trackers = root.getJSONObject("trackers")
                for (d in trackers.keys()) {
                    val t = trackers.getJSONObject(d)
                    val owner = t.optJSONObject("owner") ?: continue
                    val legal = owner.optString("name")
                    val c = company(owner.optString("displayName", legal))
                    if (c.legal == null) c.legal = legal
                    val dd = clean(d); if (!valid(dd)) continue
                    val parent = suffixHit(dd)
                    val block = t.optString("default") == "block"
                    if (block) {
                        // A tracker host DuckDuckGo blocks by default. Keep any category we already know.
                        val known = parent?.cats ?: 0
                        val bits = if (known and Cat.TRACKER_MASK != 0) known and Cat.CONTENT.bit.inv() else (known and Cat.CONTENT.bit.inv()) or Cat.TRACKING.bit
                        put(dd, parent?.company ?: c, bits, null)
                    } else if (parent == null) {
                        // DuckDuckGo says "don't block" (needed for apps to work): record the owner only.
                        put(dd, c, Cat.CONTENT.bit, null)
                    }
                }
                root.optJSONObject("entities")?.let { ents ->
                    for (legal in ents.keys()) {
                        val c = comps[groupOf(legal)] ?: continue
                        if (c.legal == null) c.legal = legal
                        val sig = ents.getJSONObject(legal).optJSONArray("signals") ?: continue
                        for (i in 0 until sig.length()) c.signals.add(sig.getString(i))
                    }
                }
                root.optJSONObject("packageNames")?.let { p -> for (k in p.keys()) owners[k] = groupOf(p.getString(k)) }
                used += "DuckDuckGo"
            }.onFailure { Log.w(TAG, "ddg", it) }
        }

        // 2b) AdGuard companiesdb: company names + descriptions for ~5,000 domains. Ad/analytics
        //     categories count only when a blocklist agrees, because AdGuard also files whole
        //     platforms (google.com, facebook.com) under "advertising".
        val agCompanies = read(ctx, "adguard-companies.json")?.let { runCatching { JSONObject(it).getJSONObject("companies") }.getOrNull() }
        read(ctx, "adguard-trackers.json")?.let { txt ->
            runCatching {
                val root = JSONObject(txt)
                val trackers = root.getJSONObject("trackers")
                val tds = root.getJSONObject("trackerDomains")
                for (d in tds.keys()) {
                    val t = trackers.optJSONObject(tds.getString(d)) ?: continue
                    val dd = clean(d); if (!valid(dd) || doms.containsKey(dd)) continue
                    val co = agCompanies?.optJSONObject(t.optString("companyId"))
                    val c = company(co?.optString("name")?.takeIf { it.isNotBlank() } ?: t.optString("name"))
                    if (co != null) {
                        if (c.website == null) co.optString("websiteUrl").takeIf { it.startsWith("http") }?.let { c.website = it }
                        if (c.about == null) co.optString("description").trim('"', ' ').takeIf { it.length > 20 }?.let { c.about = stripMd(it).take(600); c.aboutSource = "AdGuard" }
                    }
                    val sure = listed(dd)
                    val bits = when (t.optInt("categoryId", 11)) {
                        4, 3 -> if (sure) Cat.ADS.bit else 0
                        6, 101, 15 -> if (sure) Cat.ANALYTICS.bit else 0
                        7 -> Cat.SOCIAL.bit
                        0, 5, 9, 10 -> Cat.CONTENT.bit
                        else -> if (sure) Cat.TRACKING.bit else 0
                    }
                    put(dd, c, bits, t.optString("name").takeIf { it.isNotBlank() })
                }
                used += "AdGuard"
            }.onFailure { Log.w(TAG, "adguard", it) }
        }

        // 3) Exodus Privacy: crash reporting / location / analytics SDKs + descriptions
        read(ctx, "exodus-trackers.json")?.let { txt ->
            runCatching {
                val trackers = JSONObject(txt).getJSONObject("trackers")
                for (id in trackers.keys()) {
                    val t = trackers.getJSONObject(id)
                    val name = t.optString("name")
                    val desc = t.optString("description")
                    val ownership = section(desc, "Ownership")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                    val c = company(if (ownership != null && ownership.length < 60) ownership else name)
                    var bits = 0
                    val catArr = t.optJSONArray("categories")
                    if (catArr != null) for (i in 0 until catArr.length()) bits = bits or when (catArr.getString(i)) {
                        "Advertisement" -> Cat.ADS.bit
                        "Analytics", "Profiling" -> Cat.ANALYTICS.bit
                        "Crash reporting" -> Cat.CRASH.bit
                        "Location" -> Cat.LOCATION.bit
                        "Identification" -> Cat.FINGERPRINT.bit
                        else -> Cat.TRACKING.bit
                    }
                    if (bits == 0) bits = Cat.TRACKING.bit
                    if (c.about == null) section(desc, "About")?.let { c.about = stripMd(it).take(600); c.aboutSource = "Exodus Privacy" }
                    if (c.website == null) t.optString("website").takeIf { it.startsWith("http") }?.let { c.website = it }
                    c.services.add(name)
                    val sig = t.optString("network_signature")
                    if (sig.isBlank()) continue
                    for (part in sig.split('|')) {
                        val d = part.replace("\\.", ".").replace("\\-", "-").trim().trimStart('^').trimEnd('$')
                        if (d.matches(Regex("[a-z0-9.-]+\\.[a-z]{2,}"))) {
                            val dd = clean(d)
                            val e = doms[dd]
                            if (e != null) {
                                // Never turn a plain content domain (google.com, amazonaws.com...) into a tracker
                                if (e.cats and Cat.TRACKER_MASK != 0) { e.cats = e.cats or bits; e.company.cats = e.company.cats or bits }
                            } else if (dd.count { it == '.' } >= 2 || suffixHit(dd) == null) {
                                val p = suffixHit(dd)
                                if (p == null || p.cats and Cat.TRACKER_MASK != 0 || dd.count { it == '.' } >= 2) put(dd, p?.company ?: c, bits, name)
                            }
                        }
                    }
                }
                used += "Exodus"
            }.onFailure { Log.w(TAG, "exodus", it) }
        }

        // 4) Content / CDN (hand made, public knowledge)
        read(ctx, "cdn.json")?.let { txt ->
            runCatching {
                val o = JSONObject(txt)
                for (name in o.keys()) {
                    val arr = o.getJSONArray(name); val c = company(name)
                    for (i in 0 until arr.length()) {
                        val d = arr.getString(i)
                        if (doms[d] == null) put(d, c, Cat.CONTENT.bit, null)
                    }
                }
            }
        }

        // 5) DuckDuckGo Tracker Radar owner map: names the company behind ~38k more domains
        //    (e.g. cnn.com -> Warner Bros. Discovery). No category: shown as Unclassified/own servers.
        runCatching {
            val f = File(ctx.filesDir, "lists/tr-owners.tsv")
            val stream = if (f.exists()) f.inputStream() else ctx.assets.open("data/tr-owners.tsv")
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val d = parts[0]
                    if (doms.containsKey(d)) continue
                    val c = company(parts[1])
                    if (c.legal == null && parts.size > 2) c.legal = parts[2]
                    put(d, c, 0, null)
                }
            }
            used += "Tracker Radar"
        }.onFailure { Log.w(TAG, "tracker radar", it) }

        // Blocklist domains already in the map gain the tracker flag; the rest stay in a sorted array.
        val rest = ArrayList<String>(block.size)
        for (d in block) {
            val e = doms[d]
            if (e == null) rest += d
            else if (e.cats and Cat.TRACKER_MASK == 0 && e.cats != Cat.CONTENT.bit) { e.cats = e.cats or Cat.TRACKING.bit; e.company.cats = e.company.cats or Cat.TRACKING.bit }
        }
        rest.sort()
        domains = doms; companies = comps; appOwners = owners; blocked = rest.toTypedArray(); loaded = true
        synchronized(rootCompanies) { rootCompanies.clear() }
        sourceInfo = "${doms.size + rest.size} domains, ${comps.size} companies from ${used.joinToString()}"
        Log.i(TAG, "loaded $sourceInfo in ${System.currentTimeMillis() - t0} ms")
    }

    fun lookup(host: String?): Hit? {
        if (host.isNullOrEmpty()) return null
        var h = host.lowercase().trimEnd('.')
        val map = domains
        val bl = blocked
        while (true) {
            map[h]?.let { return Hit(h, it.company, it.cats, it.service) }
            if (bl.isNotEmpty() && java.util.Arrays.binarySearch(bl, h) >= 0) {
                // Listed tracker: name the owner from a parent domain we know, else the domain itself
                val owner = parentEntry(h, map)
                return Hit(h, owner?.company ?: rootCompany(h), ((owner?.cats ?: 0) and Cat.CONTENT.bit.inv()) or Cat.TRACKING.bit, owner?.service)
            }
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return null
            h = h.substring(i + 1)
        }
    }

    private fun parentEntry(d: String, map: Map<String, DomainEntry>): DomainEntry? {
        var h = d
        while (true) {
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return null
            h = h.substring(i + 1)
            map[h]?.let { return it }
        }
    }

    /** "metrics.example.co.uk" -> company "example.co.uk", for listed trackers with no known owner. */
    private fun rootCompany(d: String): Company {
        val p = d.split('.')
        val n = if (p.size >= 3 && p[p.size - 2].length <= 3 && p.last().length == 2) 3 else 2
        val root = p.takeLast(n).joinToString(".")
        return synchronized(rootCompanies) { rootCompanies.getOrPut(root) { Company(root).also { it.cats = Cat.TRACKING.bit } } }
    }

    private fun readLines(ctx: Context, name: String, each: (String) -> Unit): Boolean {
        val f = File(ctx.filesDir, "lists/$name")
        return try {
            (if (f.exists() && f.length() > 100) f.inputStream() else ctx.assets.open("data/$name"))
                .bufferedReader().useLines { it.forEach { l -> val t = l.trim(); if (t.isNotEmpty()) each(t) } }
            true
        } catch (e: Exception) { Log.w(TAG, "read $name", e); false }
    }

    private fun read(ctx: Context, name: String): String? {
        val f = File(ctx.filesDir, "lists/$name")
        return try {
            if (f.exists() && f.length() > 100) f.readText()
            else ctx.assets.open("data/$name").bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "read $name", e); null }
    }

    private fun section(md: String, title: String): String? {
        val m = Regex("##\\s*$title\\s*\\r?\\n([\\s\\S]*?)(\\r?\\n##\\s|$)").find(md) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun stripMd(s: String) = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_#>`]"), "").replace(Regex("\\s+"), " ").trim()

    /** Human wording for DuckDuckGo "signals" (documented data an SDK collects). */
    fun signalLabel(s: String): String = when (s) {
        "AAID" -> "Advertising ID"
        "unique_identifier" -> "Unique device/install ID"
        "gps_coordinates" -> "GPS coordinates"
        "email_address" -> "Email address"
        "phone_number" -> "Phone number"
        "first_name", "last_name" -> "Name"
        "network_carrier" -> "Mobile carrier"
        "device_connectivity" -> "Wi-Fi / mobile status"
        else -> s.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
