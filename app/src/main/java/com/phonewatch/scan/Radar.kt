package com.phonewatch.scan

import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import android.net.wifi.ScanResult as WifiResult
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** What a nearby signal looks like. alert = suspicious on its own (flagged, and alerted by default). say = what the voice reads out. */
enum class Kind(val label: String, val alert: Boolean, val tracker: Boolean = false, val say: String) {
    FIND_MY("Apple Find My tracker away from its owner (AirTag or Find My accessory)", true, true, "AirTag or Find My tracker away from its owner"),
    FIND_MY_NEAR("Apple Find My tracker with its owner nearby", false, true, "Find My tracker with its owner nearby"),
    TILE("Tile tracker", true, true, "Tile tracker"),
    SMARTTAG("Samsung SmartTag", true, true, "Samsung SmartTag"),
    GOOGLE_TAG("Google Find Hub tracker", true, true, "Google tracker tag"),
    FLOCK("Possible Flock Safety camera or sensor", true, say = "Possible Flock camera"),
    AXON("Axon device (body camera, Taser or other police gear)", true, say = "Axon police device"),
    GLASSES("Smart glasses or headset with a camera (Meta Ray-Ban, Quest, Spectacles)", true, say = "Smart glasses with a camera"),
    APPLE("Apple device (iPhone, AirPods, Watch…)", false, say = "Apple device"),
    BT("Bluetooth device", false, say = "Bluetooth device"),
    WIFI("WiFi network", false, say = "WiFi network"),
}

class Seen(
    val key: String, var kind: Kind, var name: String?, val addr: String, val wifi: Boolean,
    var rssi: Int, val first: Long, var last: Long,
) {
    var hits = 1
    var firstPlace: Location? = null
    var movedM = 0f              // farthest distance from where it was first seen while still being seen
    var following = false
    val alertedKinds = HashSet<Kind>()   // kinds already alerted for this entry (a BT device can later turn out to be a tracker)
    val rssiLog = ArrayDeque<Pair<Long, Int>>()            // recent signal readings, for closer / farther
    val spots = ArrayList<Spot>()                          // where you were each time it was heard, for the map and motion

    /** Signal trend over the last minute vs the minute before: + = getting closer. */
    fun trend(): Trend {
        val now = System.currentTimeMillis()
        val recent = synchronized(rssiLog) { rssiLog.toList() }
        val a = recent.filter { now - it.first <= 45_000 }.map { it.second }
        val b = recent.filter { now - it.first in 45_001..150_000 }.map { it.second }
        if (a.size < 2 || b.size < 2) return Trend.UNKNOWN
        val d = a.average() - b.average()
        return when { d >= 5 -> Trend.CLOSER; d <= -5 -> Trend.FARTHER; else -> Trend.STEADY }
    }

    /** Stationary, moving, or moving with you, from how its signal behaves against your own movement. */
    fun motion(): Motion {
        val sp = synchronized(spots) { spots.toList() }
        if (sp.size < 3) return Motion.UNKNOWN
        val far = sp.maxOf { s -> sp.maxOf { distM(s, it) } }       // how far apart your positions were while hearing it
        if (far >= Radar.FOLLOW_MIN_M && sp.takeLast(3).all { it.rssi > -80 }) return Motion.WITH_YOU
        if (far < 40) {                                            // you stood still: a changing signal means it moved
            val r = sp.map { it.rssi.toDouble() }; val m = r.average()
            val sd = kotlin.math.sqrt(r.sumOf { (it - m) * (it - m) } / r.size)
            return if (sd >= 7) Motion.MOVING else Motion.STATIONARY_MAYBE
        }
        // you moved: a fixed device is loud near one spot and fades with distance from it
        val best = sp.maxBy { it.rssi }
        val fades = sp.filter { distM(it, best) > 60 }.let { f -> f.isNotEmpty() && f.map { it.rssi }.average() < best.rssi - 8 }
        return if (fades) Motion.STATIONARY else Motion.MOVING
    }

    /** Best guess of where it is: signal-weighted middle of the places you heard it. Good to tens of meters, not exact. */
    fun estimate(): Pair<Double, Double>? {
        val sp = synchronized(spots) { spots.toList() }.ifEmpty { return null }
        val w = sp.map { Math.pow(10.0, it.rssi / 10.0) }; val tw = w.sum()
        return sp.indices.sumOf { sp[it].lat * w[it] } / tw to sp.indices.sumOf { sp[it].lon * w[it] } / tw
    }
    var detail: String? = null   // SSID security, channel etc.
    var guess: Guess? = null      // what kind of device / network it probably is
    var tx1m: Int? = null         // its own advertised signal at 1 m, when it gives one
    var infoAt = 0L               // when detail / guess were last worked out (cheap path skips them in between)
    var saidTrend = Trend.UNKNOWN // last closer / farther the voice announced, and when
    var saidAt = 0L

    fun feet() = Guesser.feet(rssi, wifi, tx1m)
    var saidFeet = 0              // distance when the voice last named it (0 = never named)
    /** Distance from the average of the last 20 s of readings, so one loud reading doesn't count as "closer". */
    fun feetSmooth(): Int {
        val now = System.currentTimeMillis()
        val r = synchronized(rssiLog) { rssiLog.filter { now - it.first <= 20_000 }.map { it.second } }
        return Guesser.feet(if (r.isEmpty()) rssi else r.average().toInt(), wifi, tx1m)
    }
    /** Trackers, cameras, smart glasses, police gear, anything moving with you. */
    fun risky() = kind.alert || following || guess?.type?.risky == true
    val open get() = wifi && detail?.contains("open") == true
}

data class Spot(val t: Long, val lat: Double, val lon: Double, val rssi: Int)
enum class Trend(val label: String) { CLOSER("Getting closer"), FARTHER("Moving away"), STEADY("Same distance"), UNKNOWN("") }
enum class Motion(val label: String) {
    STATIONARY("Stationary (fixed in place)"), STATIONARY_MAYBE("Not moving (you're standing still)"),
    MOVING("Moving"), WITH_YOU("Moving with you"), UNKNOWN("")
}
fun distM(a: Spot, b: Spot): Float { val r = FloatArray(1); Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r); return r[0] }

data class Cell(val t: Long, val tech: String, val id: String, val dbm: Int?)

object Radar {
    // Sources for the signatures, so they can be checked:
    //  Apple Find My offline finding: manufacturer 0x004C, type 0x12; length 0x19 = separated from owner (AirGuard, OpenHaystack)
    //  Tile: service UUIDs 0xFEED / 0xFEEC (Tile Inc. Bluetooth SIG assignments)
    //  Samsung SmartTag: service data 0xFD5A;  Google Find Hub (FMDN): Eddystone 0xFEAA frame type 0x40/0x41
    //  Axon Enterprise (formerly TASER International) IEEE OUI 00:25:DF
    //  Flock Safety: device / SSID names used by its cameras and batteries (Flock*, Penguin*, Pigvision*, FS Ext Battery)
    private val TILE = listOf(ParcelUuid.fromString("0000feed-0000-1000-8000-00805f9b34fb"), ParcelUuid.fromString("0000feec-0000-1000-8000-00805f9b34fb"))
    private val SMARTTAG = ParcelUuid.fromString("0000fd5a-0000-1000-8000-00805f9b34fb")
    private val EDDYSTONE = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
    private val FLOCK_NAME = Regex("(?i)^(flock|penguin|pigvision|fs ext battery)")
    private val AXON_OUI = listOf("00:25:DF")
    //  Meta smart glasses / Quest: Bluetooth SIG company ids 0x01AB (Meta Platforms) and 0x058E (Meta Platforms Technologies),
    //  service UUID 0xFD5F (Meta Platforms Technologies); Snap Spectacles by name
    private val META_IDS = listOf(0x01AB, 0x058E)
    private val META_UUID = ParcelUuid.fromString("0000fd5f-0000-1000-8000-00805f9b34fb")
    private val GLASSES_NAME = Regex("(?i)ray-?ban|meta (glasses|wayfarer)|oakley meta|spectacles|\\bquest\\b")

    const val FOLLOW_MIN_MS = 15 * 60_000L   // seen this long…
    const val FOLLOW_MIN_M = 500f            // …and this far from where it was first seen = moving with you

    val seen = ConcurrentHashMap<String, Seen>()
    val cells = ArrayList<Cell>()
    val version = MutableStateFlow(0L)       // bumps when anything changes, so the UI redraws
    val running = MutableStateFlow(false)
    @Volatile var place: Location? = null
    val track = ArrayList<Spot>()                          // your own path while scanning (rssi unused)
    fun moved(l: Location) {
        place = l
        synchronized(track) {
            val p = Spot(System.currentTimeMillis(), l.latitude, l.longitude, 0)
            if (track.isEmpty() || distM(track.last(), p) > 10) { track.add(p); if (track.size > 3000) track.removeAt(0) }
        }
    }
    private lateinit var prefs: android.content.SharedPreferences

    fun init(c: Context) { prefs = c.getSharedPreferences("scan", Context.MODE_PRIVATE) }
    fun ignored(): Set<String> = prefs.getStringSet("mine", emptySet()) ?: emptySet()
    fun setMine(addr: String, mine: Boolean) {
        prefs.edit().putStringSet("mine", ignored().toMutableSet().apply { if (mine) add(addr) else remove(addr) }).apply()
        bump()
    }
    fun bump() { version.value = System.currentTimeMillis() }

    // ---- alert settings: which kinds alert, cell tower changes, and voice
    private val DEFAULT_ALERTS = Kind.values().filter { it.alert }.map { it.name }.toSet()
    // GLASSES came later: switch it on for people who had already picked their categories, until they touch it themselves
    fun alertKinds(): Set<String> = prefs.getStringSet("alertKinds", null)?.let { if (prefs.getBoolean("glassesSet", false)) it else it + Kind.GLASSES.name } ?: DEFAULT_ALERTS
    fun alertOn(k: Kind) = k.name in alertKinds()
    fun setAlert(kinds: Collection<Kind>, on: Boolean) {
        prefs.edit().putStringSet("alertKinds", alertKinds().toMutableSet().apply { kinds.forEach { if (on) add(it.name) else remove(it.name) } })
            .putBoolean("glassesSet", true).apply()
        bump()
    }
    var cellAlerts: Boolean                  // every tower change, not just drops to 2G
        get() = prefs.getBoolean("cellAlerts", false)
        set(v) { prefs.edit().putBoolean("cellAlerts", v).apply(); bump() }
    var voice: Boolean
        get() = prefs.getBoolean("voice", false)
        set(v) { prefs.edit().putBoolean("voice", v).apply(); bump() }
    var saver: Boolean                       // battery saver: gentler scans and location while the screen is off
        get() = prefs.getBoolean("saver", true)
        set(v) { prefs.edit().putBoolean("saver", v).apply(); bump() }
    fun allAlertsOn() = alertKinds().size == Kind.values().size && cellAlerts
    fun setAllAlerts(on: Boolean) {
        prefs.edit().putStringSet("alertKinds", if (on) Kind.values().map { it.name }.toSet() else DEFAULT_ALERTS).putBoolean("cellAlerts", on).apply()
        bump()
    }
    // ---- devices that have connected to this phone: said once, ever (kept across restarts), never repeated
    private fun saidKnown(): Set<String> = prefs.getStringSet("saidKnown", emptySet()) ?: emptySet()
    /** True the first time a connected device (or the WiFi you're on) is announced; false every time after. */
    fun firstKnown(id: String): Boolean {
        val now = saidKnown()
        if (id in now) return false
        prefs.edit().putStringSet("saidKnown", now + id).apply(); return true
    }
    @Volatile var bonded: Set<String> = emptySet()   // paired Bluetooth devices: addresses and names, upper-case
    /** Has connected to this phone before: paired, in Android's connection history, connected now, or the WiFi you're on. */
    fun knownToPhone(s: Seen): Boolean {
        if (s.wifi) return isMyWifi(s)
        val a = s.addr.uppercase()
        return a in bonded || s.name?.uppercase()?.let { it in bonded } == true || btNow.containsKey(a) || connectedToMe(s) != null
    }
    var contactsAfter: Long
        get() = prefs.getLong("contactsAfter", 0L)
        set(v) { prefs.edit().putLong("contactsAfter", v).apply() }
    val contacts = MutableStateFlow<Contacts.Report?>(null)
    fun onPrefs(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun offPrefs(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    fun classifyBle(r: ScanResult): Kind {
        val rec = r.scanRecord
        val addr = r.device.address.uppercase()
        val name = rec?.deviceName
        if (AXON_OUI.any { addr.startsWith(it) }) return Kind.AXON
        if (name != null && FLOCK_NAME.containsMatchIn(name)) return Kind.FLOCK
        if ((name != null && GLASSES_NAME.containsMatchIn(name)) || META_IDS.any { rec?.getManufacturerSpecificData(it) != null } ||
            META_UUID in rec?.serviceUuids.orEmpty() || rec?.getServiceData(META_UUID) != null) return Kind.GLASSES
        rec?.getManufacturerSpecificData(0x004C)?.let { m ->
            if (m.size >= 2 && m[0] == 0x12.toByte()) return if (m[1] == 0x19.toByte()) Kind.FIND_MY else Kind.FIND_MY_NEAR
            return Kind.APPLE
        }
        val uuids = rec?.serviceUuids.orEmpty()
        if (uuids.any { it in TILE } || TILE.any { rec?.getServiceData(it) != null }) return Kind.TILE
        if (rec?.getServiceData(SMARTTAG) != null || SMARTTAG in uuids) return Kind.SMARTTAG
        rec?.getServiceData(EDDYSTONE)?.let { d -> if (d.isNotEmpty() && (d[0] == 0x40.toByte() || d[0] == 0x41.toByte())) return Kind.GOOGLE_TAG }
        return Kind.BT
    }

    // Bluetooth SIG company identifiers for the makers seen most often
    private val COMPANY = mapOf(0x004C to "Apple", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0006 to "Microsoft", 0x0087 to "Garmin",
        0x0157 to "Huami/Amazfit", 0x038F to "Xiaomi", 0x0171 to "Amazon", 0x0059 to "Nordic Semiconductor", 0x000F to "Broadcom",
        0x0002 to "Intel", 0x02E5 to "Espressif")

    /** Everything a BLE advertisement says about its sender, as one readable line. */
    @android.annotation.SuppressLint("MissingPermission")
    fun bleDetail(r: ScanResult): String {
        val rec = r.scanRecord
        val parts = mutableListOf<String>()
        val m = rec?.manufacturerSpecificData
        if (m != null) for (i in 0 until m.size()) m.keyAt(i).let { parts += "maker ${COMPANY[it] ?: "id 0x%04X".format(it)}" }
        rec?.serviceUuids?.takeIf { it.isNotEmpty() }?.let { u -> parts += "services " + u.joinToString { it.uuid.toString().substring(4, 8).uppercase() } }
        rec?.serviceData?.keys?.takeIf { it.isNotEmpty() }?.let { u -> parts += "data for " + u.joinToString { it.uuid.toString().substring(4, 8).uppercase() } }
        runCatching { r.device.bluetoothClass?.majorDeviceClass }.getOrNull()?.takeIf { it != 0x1F00 }?.let { parts += "class 0x%04X".format(it) }
        parts += when (runCatching { r.device.type }.getOrDefault(0)) { 1 -> "classic"; 2 -> "LE"; 3 -> "dual mode"; else -> "type unknown" }
        if (r.isConnectable) parts += "accepts connections"
        rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let { parts += "tx power $it dBm" }
        if (r.txPower != ScanResult.TX_POWER_NOT_PRESENT) parts += "tx ${r.txPower} dBm"
        if ((r.device.address.substring(0, 2).toInt(16) and 0xC0) == 0x40) parts += "random rotating address"
        return parts.joinToString(", ")
    }

    fun classifyWifi(w: WifiResult, ssid: String): Kind {
        val b = w.BSSID.uppercase()
        if (AXON_OUI.any { b.startsWith(it) }) return Kind.AXON
        if (FLOCK_NAME.containsMatchIn(ssid)) return Kind.FLOCK
        return Kind.WIFI
    }

    /** Records a sighting; returns the entry when it newly needs an alert. */
    fun saw(key: String, kind: Kind, name: String?, addr: String, wifi: Boolean, rssi: Int, detail: String? = null, guess: Guess? = null, tx1m: Int? = null): Seen? {
        val now = System.currentTimeMillis()
        val s = seen.getOrPut(key) { Seen(key, kind, name, addr, wifi, rssi, now, now) }
        s.rssi = rssi; s.last = now; s.hits++
        synchronized(s.rssiLog) { s.rssiLog.addLast(now to rssi); while (s.rssiLog.isNotEmpty() && (s.rssiLog.size > 60 || now - s.rssiLog.first().first > 300_000)) s.rssiLog.removeFirst() }
        place?.let { p ->
            synchronized(s.spots) {
                val l = s.spots.lastOrNull()
                val sp = Spot(now, p.latitude, p.longitude, rssi)
                if (l == null || now - l.t > 20_000 || distM(l, sp) > 15) { s.spots.add(sp); if (s.spots.size > 300) s.spots.removeAt(0) }
                else if (rssi > l.rssi) s.spots[s.spots.lastIndex] = sp      // same spot: keep the strongest reading
            }
        }
        if (name != null) s.name = name
        if (kind != Kind.BT && kind != Kind.WIFI) s.kind = kind
        if (detail != null) { s.detail = detail; s.infoAt = now }
        if (guess != null && (guess.type != DevType.UNKNOWN || s.guess == null)) s.guess = guess
        if (tx1m != null) s.tx1m = tx1m
        val here = place
        if (here != null) {
            val f = s.firstPlace
            if (f == null) s.firstPlace = here else s.movedM = maxOf(s.movedM, f.distanceTo(here))
        }
        val wasFollowing = s.following
        s.following = now - s.first >= FOLLOW_MIN_MS && s.movedM >= FOLLOW_MIN_M
        if (addr.uppercase() in ignored()) return null
        val kindNew = alertOn(s.kind) && s.alertedKinds.add(s.kind)
        val followNew = s.following && !wasFollowing && (s.kind.tracker || !wifi)   // always on
        return if (kindNew || followNew) s else null
    }

    /** Drops things not seen for 20 minutes (trackers and following ones are kept for the session). */
    fun prune() {
        val cut = System.currentTimeMillis() - 20 * 60_000L
        seen.values.removeAll { it.last < cut && !it.kind.alert && !it.following && !it.kind.tracker }
    }

    fun addCell(c: Cell): Cell? {
        synchronized(cells) {
            val prev = cells.lastOrNull()
            if (prev != null && prev.id == c.id && prev.tech == c.tech) { cells[cells.lastIndex] = c; return null }
            cells.add(c); if (cells.size > 200) cells.removeAt(0)
            return prev
        }
    }

    fun clear() { seen.clear(); synchronized(announced) { announced.clear() }; synchronized(cells) { cells.clear() }; bump() }

    // ---- what the voice already said: a device or network name is said once per session, even when it shows up again
    //      under a new address (phones and tags rotate theirs) or on another access point of the same network
    private val announced = HashSet<String>()
    fun firstTime(s: Seen): Boolean {
        val n = s.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val id = when { n == null -> s.key; n.startsWith("(") -> "${s.wifi}:hidden"; else -> "${s.wifi}:${s.kind}:$n" }
        return synchronized(announced) { announced.add(id) }
    }

    // the WiFi network this phone is connected to right now (set from each WiFi read)
    @Volatile var myBssid: String? = null
    @Volatile var mySsid: String? = null
    fun isMyWifi(s: Seen) = s.wifi && (s.addr.equals(myBssid, true) || (mySsid != null && s.name == mySsid))
    @Volatile var wifiBase: LongArray? = null       // WiFi bytes sent / received when scanning noticed the connection
    /** Bluetooth devices connected to this phone right now: address -> (name, what the link carries). Refreshed by the scan service. */
    @Volatile var btNow: Map<String, Pair<String?, Set<String>>> = emptyMap()

    private fun mb(b: Long) = if (b < 1_000_000) "${b / 1000} kilobytes" else "%.1f megabytes".format(b / 1e6)

    /** What goes between this phone and the device, in plain words, or null when nothing does. */
    fun link(s: Seen): String? {
        if (s.wifi) {
            if (!isMyWifi(s)) return null
            val amounts = wifiBase?.let { b ->
                val tx = android.net.TrafficStats.getTotalTxBytes() - android.net.TrafficStats.getMobileTxBytes() - b[0]
                val rx = android.net.TrafficStats.getTotalRxBytes() - android.net.TrafficStats.getMobileRxBytes() - b[1]
                if (tx >= 0 && rx >= 0 && tx + rx >= 100_000) " Since scanning started: ${mb(tx)} sent and ${mb(rx)} received through it." else null
            } ?: ""
            return "Your device is connected to it, so all your internet data goes in and out through it.$amounts " +
                "It can see your phone's name and address, every website and app server you connect to, and any unencrypted data."
        }
        val a = s.addr.uppercase()
        val now = btNow[a] ?: s.name?.let { n -> btNow.values.firstOrNull { it.first == n } }
        val past = connectedToMe(s)
        if (now == null && past == null) return null
        val parts = mutableListOf<String>()
        if (now != null) {
            val p = now.second
            parts += "It is connected to your device now."
            if ("A2DP" in p) parts += "Sound from your phone goes out to it."
            if ("HEADSET" in p) parts += "Your calls go through it, and its microphone sends audio in."
            if ("GATT" in p) parts += "It is exchanging data with your phone. Android doesn't show what or how much."
        }
        if (past != null) {
            if (now == null) parts += "It connected to your device ${past.times.size} time${if (past.times.size == 1) "" else "s"}, last at " +
                java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(past.times.last())) + "."
            parts += Voice.gathers(past)
        } else if (now != null && "GATT" !in now.second && now.second.isEmpty()) parts += "Android doesn't show what data it exchanges."
        return parts.joinToString(" ")
    }

    /** The device's entry in Android's own connection history when it actually connected to this phone (and isn't yours). */
    fun connectedToMe(s: Seen): Contacts.Bt? {
        if (s.wifi) return null
        val a = s.addr.uppercase()
        return contacts.value?.bt?.firstOrNull { b ->
            b.times.isNotEmpty() && (b.fullAddr == a || (b.addr.takeLast(5) == a.takeLast(5) && b.name != null && b.name == s.name))
        }?.takeIf { !it.paired || a !in ignored() }
    }
}
