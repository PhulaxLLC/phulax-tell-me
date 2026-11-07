package com.phonewatch.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.phonewatch.MainActivity
import com.phonewatch.R
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Passive radio watch: Bluetooth LE advertisements, WiFi scan results and the serving cell tower. Listens only;
 *  never connects to, probes or jams anything. Runs as a location foreground service so it keeps going with the screen off. */
@SuppressLint("MissingPermission")
class ScanService : Service() {
    companion object {
        private const val TAG = "PwScan"
        private const val CHANNEL = "scan_running"
        private const val ALERTS = "scan_alerts"
        private const val INFO = "scan_info"
        private const val NOTIF_ID = 31
        private const val INFO_ID = 32
        private const val ACTION_STOP = "stop"

        fun needed(): Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.NEARBY_WIFI_DEVICES); add(Manifest.permission.POST_NOTIFICATIONS) }
        }.toTypedArray()

        fun start(c: Context) {
            if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            c.startForegroundService(Intent(c, ScanService::class.java))
        }
        fun stop(c: Context) { c.startService(Intent(c, ScanService::class.java).setAction(ACTION_STOP)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var alertId = 100
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val wm by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val tm by lazy { getSystemService(TelephonyManager::class.java) }
    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private val bt by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val pm by lazy { getSystemService(android.os.PowerManager::class.java) }
    /** Battery saver is on and nobody is looking at the screen. */
    private fun quiet() = Radar.saver && !pm.isInteractive

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing("Starting…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        if (!Radar.running.value) {
            Radar.running.value = true
            startLocation(); startBle(); startWifi(); startCells(); startStatus(); startVoice(); startContacts()
        }
        return START_STICKY
    }

    private fun shutdown() {
        Radar.running.value = false
        scope.cancel()
        runCatching { bt?.bluetoothLeScanner?.stopScan(bleAll) }
        runCatching { bt?.bluetoothLeScanner?.stopScan(bleTrackers) }
        runCatching { unregisterReceiver(wifiRx) }
        runCatching { lm.removeUpdates(locListener) }
        proxies.forEach { (p, x) -> runCatching { bt?.closeProfileProxy(p, x) } }; proxies.clear()
        Radar.offPrefs(prefsRx); tts?.shutdown(); tts = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (Radar.running.value) shutdown(); super.onDestroy() }

    // ---------------------------------------------------------------- location (for "moving with you")
    private val locListener = LocationListener { l: Location -> Radar.moved(l) }

    private fun startLocation() {
        val providers = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) listOf(LocationManager.FUSED_PROVIDER)
            else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
        for (p in providers) runCatching {
            lm.getLastKnownLocation(p)?.let { if (Radar.place == null) Radar.place = it }
            if (Build.VERSION.SDK_INT >= 31 && Radar.saver)   // WiFi / cell positioning instead of GPS: plenty for "moving with you" (500 m)
                lm.requestLocationUpdates(p, android.location.LocationRequest.Builder(30_000L).setMinUpdateDistanceMeters(20f)
                    .setQuality(android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).build(), mainExecutor, locListener)
            else lm.requestLocationUpdates(p, 10_000L, 10f, locListener, Looper.getMainLooper())
        }
    }

    // ---------------------------------------------------------------- Bluetooth LE
    private val bleAll = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) = onBle(r)
        override fun onBatchScanResults(rs: MutableList<ScanResult>) = rs.forEach(::onBle)
        override fun onScanFailed(code: Int) { Log.w(TAG, "BLE scan failed $code") }
    }
    private val bleTrackers = object : ScanCallback() {   // filtered, so Android keeps it running with the screen off
        override fun onScanResult(type: Int, r: ScanResult) = onBle(r)
        override fun onBatchScanResults(rs: MutableList<ScanResult>) = rs.forEach(::onBle)
    }

    private fun onBle(r: ScanResult) {
        val addr = r.device.address
        val key = "bt:$addr"
        val old = Radar.seen[key]
        // a busy street sends hundreds of adverts a second: the full read (kind, details, device type) runs once a minute per device
        val full = old == null || System.currentTimeMillis() - old.infoAt > 60_000L || (r.scanRecord?.deviceName != null && old.name == null)
        val kind = if (full || old == null) Radar.classifyBle(r) else old.kind
        val tx = r.scanRecord?.txPowerLevel?.takeIf { it in -40..20 }?.let { it - 41 }    // advertised 0 m power, minus 41 dB = at 1 m
        val s = Radar.saw(key, kind, r.scanRecord?.deviceName, addr, wifi = false, rssi = r.rssi,
            detail = if (full) Radar.bleDetail(r) else null, guess = if (full) Guesser.ble(r) else null, tx1m = tx)
        if (s != null) alert(s) else old?.let { closerCheck(it) }
    }

    /** Name a device again when it comes clearly closer (30% and 5+ ft nearer than when last named, at most once a minute). */
    private fun closerCheck(s: Seen) {
        if (!Radar.voice || s.saidFeet == 0) return
        val now = System.currentTimeMillis()
        if (now - s.saidAt < 60_000L) return
        val f = s.feetSmooth()
        if (f > s.saidFeet * 1.5) { s.saidFeet = f; return }           // moved away: reset quietly so the next approach counts
        if (f > s.saidFeet * 0.7 || s.saidFeet - f < 5) return
        if (s.addr.uppercase() in Radar.ignored() || Radar.knownToPhone(s)) return
        s.saidFeet = f; s.saidAt = now
        speak(Voice.closer(s, f), urgent = true)
    }

    private fun trackerFilters(): List<ScanFilter> {
        fun uuid(s: String) = ParcelUuid.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        return listOf(
            ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x12), byteArrayOf(0xFF.toByte())).build(),
            ScanFilter.Builder().setServiceUuid(uuid("feed")).build(),
            ScanFilter.Builder().setServiceUuid(uuid("feec")).build(),
            ScanFilter.Builder().setServiceData(uuid("fd5a"), ByteArray(0)).build(),
            ScanFilter.Builder().setServiceData(uuid("feaa"), byteArrayOf(0x40), byteArrayOf(0xFE.toByte())).build(),
        )
    }

    private fun startBle() = scope.launch {
        var started = 0L; var wasQuiet: Boolean? = null
        while (isActive) {
            val sc = bt?.takeIf { it.isEnabled }?.bluetoothLeScanner
            val q = quiet()
            // restart every 10 min (Android quietly downgrades scans that run longer than 30 min) or when the screen / saver changes
            if (sc != null && (q != wasQuiet || System.currentTimeMillis() - started > 600_000L)) runCatching {
                // screen off + saver: low-power scan, results batched by the Bluetooth chip so the phone wakes less often
                val settings = ScanSettings.Builder().setScanMode(if (q) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_BALANCED)
                    .apply { if (q && bt?.isOffloadedScanBatchingSupported == true) setReportDelay(10_000L) }.build()
                sc.stopScan(bleAll); sc.stopScan(bleTrackers)
                sc.startScan(trackerFilters(), settings, bleTrackers)
                sc.startScan(null, settings, bleAll)
                started = System.currentTimeMillis(); wasQuiet = q
            }.onFailure { Log.w(TAG, "BLE start: $it") }
            if (sc == null) wasQuiet = null
            delay(30_000L)
        }
    }

    // ---------------------------------------------------------------- WiFi
    private val wifiRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { scope.launch { readWifi() } }
    }

    private fun startWifi() {
        registerReceiver(wifiRx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        scope.launch {
            while (isActive) {
                @Suppress("DEPRECATION") runCatching { wm.startScan() }   // Android throttles this; results from other scans arrive too
                readWifi()
                delay(if (!Radar.saver) 60_000L else if (pm.isInteractive) 120_000L else 300_000L)
            }
        }
    }

    private val SEC_RX = Regex("\\[([A-Z0-9]+)")
    private fun readWifi() {
        @Suppress("DEPRECATION") runCatching { wm.connectionInfo }.getOrNull()?.let { ci ->
            val b = ci.bssid?.takeIf { it != "02:00:00:00:00:00" }
            if (b != Radar.myBssid) Radar.wifiBase = b?.let { longArrayOf(android.net.TrafficStats.getTotalTxBytes() - android.net.TrafficStats.getMobileTxBytes(),
                android.net.TrafficStats.getTotalRxBytes() - android.net.TrafficStats.getMobileRxBytes()) }
            Radar.myBssid = b
            Radar.mySsid = ci.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }
        val results = runCatching { wm.scanResults }.getOrNull() ?: return
        for (w in results) {
            @Suppress("DEPRECATION") val ssid = (if (Build.VERSION.SDK_INT >= 33) w.wifiSsid?.toString()?.trim('"') else w.SSID).orEmpty()
            val kind = Radar.classifyWifi(w, ssid)
            val band = if (w.frequency >= 5925) "6 GHz" else if (w.frequency >= 4900) "5 GHz" else "2.4 GHz"
            val sec = SEC_RX.findAll(w.capabilities).map { it.groupValues[1] }.filter { it != "ESS" }.toList()
            val detail = "$band, ${if (sec.isEmpty()) "open (no password)" else sec.joinToString("/")}"
            val old = Radar.seen["wifi:${w.BSSID}"]
            val s = Radar.saw("wifi:${w.BSSID}", kind, ssid.ifBlank { "(hidden network)" }, w.BSSID, wifi = true, rssi = w.level, detail = detail,
                guess = if (old == null) Guesser.byName(ssid, wifi = true) ?: Guess(DevType.UNKNOWN) else null)
            if (s != null) alert(s) else old?.let { closerCheck(it) }
        }
        Radar.bump()
    }

    // ---------------------------------------------------------------- cell tower
    private fun startCells() = scope.launch {
        while (isActive) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) tm.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(info: MutableList<CellInfo>) { onCells(info) }
                }) else onCells(tm.allCellInfo.orEmpty())
            }.onFailure { Log.w(TAG, "cell: $it") }
            delay(30_000L)
        }
    }

    private fun onCells(info: List<CellInfo>) {
        val c = info.firstOrNull { it.isRegistered } ?: return
        val (tech, id) = when (c) {
            is CellInfoGsm -> "2G (GSM)" to c.cellIdentity.let { "${it.mccString}-${it.mncString} LAC ${it.lac} CID ${it.cid}" }
            is CellInfoWcdma -> "3G (UMTS)" to c.cellIdentity.let { "${it.mccString}-${it.mncString} LAC ${it.lac} CID ${it.cid}" }
            is CellInfoLte -> "4G (LTE)" to c.cellIdentity.let { "${it.mccString}-${it.mncString} TAC ${it.tac} CI ${it.ci} PCI ${it.pci}" }
            else -> if (Build.VERSION.SDK_INT >= 29 && c is CellInfoNr) "5G (NR)" to (c.cellIdentity as CellIdentityNr).let { "${it.mccString}-${it.mncString} TAC ${it.tac} NCI ${it.nci} PCI ${it.pci}" }
                    else "Other" to c.toString().take(60)
        }
        val dbm = when (c) {
            is CellInfoGsm -> c.cellSignalStrength.dbm; is CellInfoWcdma -> c.cellSignalStrength.dbm
            is CellInfoLte -> c.cellSignalStrength.dbm
            else -> if (Build.VERSION.SDK_INT >= 29 && c is CellInfoNr) c.cellSignalStrength.dbm else null
        }?.takeIf { it in -150..-20 }
        val prev = Radar.addCell(Cell(System.currentTimeMillis(), tech, id, dbm))
        if (prev != null && !tech.startsWith("2G") && Radar.cellAlerts)
            info(null, Voice.say(Voice.Phrase.CELL, tech = tech), "Cell tower: $tech ($id)")
        if (prev != null && tech.startsWith("2G") && !prev.tech.startsWith("2G"))
            notifyAlert("Phone dropped to 2G", say = Voice.say(Voice.Phrase.TWO_G), body = "Your phone moved from ${prev.tech} to 2G (GSM) at $id. Fake cell towers (\"stingrays\") often force phones " +
                "down to 2G, where calls and texts are weakly encrypted. It can also just be poor coverage. Turning 2G off stops it: Settings > " +
                "Connections > Mobile networks > Network mode (choose a mode without 2G).")
        Radar.bump()
    }

    // ---------------------------------------------------------------- notifications
    private fun alert(s: Seen) {
        val named = s.name?.takeIf { it.isNotBlank() && !it.startsWith("(") }
        val spoken = Voice.say(Voice.phraseFor(s.kind), name = named, rssi = s.rssi, s = s)
        s.saidAt = System.currentTimeMillis(); s.saidTrend = s.trend()
        // already on this scan's list under the same name, or a device that has connected to this phone and was already said once: quiet
        val fresh = s.following || (Radar.firstTime(s) && (!Radar.knownToPhone(s) || Radar.firstKnown(if (s.wifi) "wifi:${s.name}" else "bt:${s.name ?: s.addr}")))
        if (fresh) { s.saidFeet = s.feet(); s.saidAt = System.currentTimeMillis() }
        if (!s.following && !s.kind.alert) {   // everyday things (WiFi, Bluetooth, Apple devices): batched, not one notification each
            if (fresh) info(s.kind, spoken, named ?: s.guess?.label()?.ifBlank { null } ?: s.kind.label, s.rssi)
            return
        }
        if (!fresh) return
        val where = listOfNotNull(s.name?.takeIf { it.isNotBlank() }, s.addr, "${s.rssi} dBm, ${Guesser.feetText(s.feet())} away").joinToString(", ")
        val title = if (s.following) "Moving with you: ${s.kind.label}" else s.kind.label
        val body = if (s.following) "Seen for ${(s.last - s.first) / 60_000} min and over ${"%.1f".format(s.movedM / 1000)} km as you moved ($where). " +
            "If it isn't yours, check your bag, car and clothes." else "Nearby now ($where)."
        notifyAlert(title, body, say = if (s.following) Voice.say(Voice.Phrase.FOLLOWING, name = named ?: s.kind.say, minutes = (s.last - s.first) / 60_000) else spoken)
    }

    // ---------------------------------------------------------------- voice + batched everyday alerts
    private class Say(val text: String, val urgent: Boolean, val kind: Kind?, val rssi: Int = 0)
    private val sayQ = ConcurrentLinkedQueue<Say>()
    private val infoQ = ConcurrentLinkedQueue<Pair<Kind?, String>>()
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val am by lazy { getSystemService(AudioManager::class.java) }
    private val ttsAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus by lazy { AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ttsAttrs).build() }
    private val prefsRx = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "voice") { if (Radar.voice) { ttsOn(); sayQ.add(Say(Voice.say(Voice.Phrase.VOICE_ON), true, null)) } else { tts?.stop(); sayQ.clear() } }
        if (key in Voice.KEYS) tts?.takeIf { ttsReady }?.let { Voice.apply(it) }
        if (key == "saver" && Radar.running.value) { runCatching { lm.removeUpdates(locListener) }; startLocation() }
    }

    private fun ttsOn() {
        if (tts != null) return
        tts = TextToSpeech(applicationContext) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (!ttsReady) { Log.w(TAG, "TTS init failed $st"); return@TextToSpeech }
            tts?.setAudioAttributes(ttsAttrs)
            tts?.let { if (!Voice.apply(it)) Log.w(TAG, "TTS language ${Voice.lang} not installed") }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (tts?.isSpeaking != true) am.abandonAudioFocusRequest(focus) }
                @Deprecated("old API") override fun onError(id: String?) { am.abandonAudioFocusRequest(focus) }
            })
        }
    }

    private fun speak(text: String, urgent: Boolean, kind: Kind? = null, rssi: Int = 0) { if (Radar.voice) sayQ.add(Say(text, urgent, kind, rssi)) }

    private fun info(kind: Kind?, say: String, line: String, rssi: Int = 0) {
        infoQ.add(kind to line)
        speak(say, urgent = false, kind = kind, rssi = rssi)
    }

    private fun startVoice() {
        Radar.onPrefs(prefsRx)
        if (Radar.voice) ttsOn()
        scope.launch {
            var lastInfo = 0L
            while (isActive) {
                delay(3_000L)
                // everyday alerts: one rolling notification, refreshed at most every 30 s
                if (infoQ.isNotEmpty() && System.currentTimeMillis() - lastInfo > 30_000L) {
                    lastInfo = System.currentTimeMillis()
                    val batch = generateSequence { infoQ.poll() }.toList()
                    val counts = batch.groupingBy { it.first?.label ?: "cell tower change" }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
                    notifyInfo("New nearby: $counts", "Latest: " + batch.takeLast(6).asReversed().joinToString("; ") { it.second })
                }
                val t = tts
                if (!Radar.voice || t == null || !ttsReady) { if (!Radar.voice) sayQ.clear(); continue }
                if (t.isSpeaking) continue           // let it finish; new ones keep piling up and get grouped
                val all = generateSequence { sayQ.poll() }.toList()
                if (all.isEmpty()) continue
                val (urgent, rest) = all.partition { it.urgent || it.kind == null }
                val lines = urgent.take(3).map { it.text }.toMutableList()
                if (urgent.size > 3) lines += Voice.more(urgent.size - 3)
                // everyday ones: the 2 closest of each kind in full, the rest as a count
                rest.groupBy { it.kind!! }.forEach { (k, list) ->
                    val near = list.sortedByDescending { it.rssi }
                    val full = if (near.size == 3) 3 else 2              // never "1 more"
                    lines += near.take(full).map { it.text }
                    if (near.size > full) lines += Voice.group(k, near.size - full)
                }
                if (am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) Log.w(TAG, "no audio focus")
                lines.forEachIndexed { i, l -> Log.i(TAG, "say: $l"); t.speak(l, TextToSpeech.QUEUE_ADD, null, "pw${System.nanoTime()}$i") }
            }
        }
    }

    private fun notifyInfo(title: String, body: String) {
        if (nm.getNotificationChannel(INFO) == null)
            nm.createNotificationChannel(NotificationChannel(INFO, "Scan: everyday devices (WiFi, Bluetooth, towers)", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(INFO_ID, NotificationCompat.Builder(this, INFO).setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun notifyAlert(title: String, body: String, say: String? = null) {
        speak(say ?: title, urgent = true)
        if (nm.getNotificationChannel(ALERTS) == null)
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Scan alerts (trackers, cameras, 2G)", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(alertId++, NotificationCompat.Builder(this, ALERTS).setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    // ---------------------------------------------------------------- contacts with this phone (needs Shizuku)
    private fun startContacts() = scope.launch {
        while (isActive) {
            if (com.phonewatch.debloat.Shell.state(this@ScanService) == com.phonewatch.debloat.Shell.State.READY) runCatching {
                val r = Contacts.read()
                if (r.error == null) {
                    val after = Radar.contactsAfter
                    if (after == 0L) Radar.contactsAfter = System.currentTimeMillis()   // first run: history only, no alerts
                    else {
                        Contacts.newAlerts(r, after).forEach { (t, b, sp) -> notifyAlert(t, b, say = sp) }
                        Radar.contactsAfter = System.currentTimeMillis()
                    }
                    Radar.contacts.value = r
                }
            }.onFailure { Log.w(TAG, "contacts: $it") }
            delay(if (Radar.saver) 15 * 60_000L else 5 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- Bluetooth devices connected to this phone right now
    private val proxies = java.util.concurrent.ConcurrentHashMap<Int, android.bluetooth.BluetoothProfile>()
    private fun openProxies() {
        val l = object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) { proxies[p] = proxy; refreshLinks() }
            override fun onServiceDisconnected(p: Int) { proxies.remove(p) }
        }
        listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET).forEach { runCatching { bt?.getProfileProxy(this, l, it) } }
    }
    private fun refreshLinks() {
        val m = HashMap<String, Pair<String?, MutableSet<String>>>()
        fun add(d: android.bluetooth.BluetoothDevice, what: String) =
            m.getOrPut(d.address.uppercase()) { runCatching { d.name }.getOrNull() to HashSet() }.second.add(what)
        proxies[android.bluetooth.BluetoothProfile.A2DP]?.let { p -> runCatching { p.connectedDevices }.getOrNull()?.forEach { add(it, "A2DP") } }
        proxies[android.bluetooth.BluetoothProfile.HEADSET]?.let { p -> runCatching { p.connectedDevices }.getOrNull()?.forEach { add(it, "HEADSET") } }
        runCatching { getSystemService(BluetoothManager::class.java).getConnectedDevices(android.bluetooth.BluetoothProfile.GATT) }.getOrNull()?.forEach { add(it, "GATT") }
        Radar.btNow = m
        runCatching { bt?.bondedDevices }.getOrNull()?.let { b -> Radar.bonded = b.flatMap { listOfNotNull(it.address.uppercase(), runCatching { it.name }.getOrNull()?.uppercase()) }.toSet() }
    }

    private fun startStatus() = scope.launch {
        openProxies()
        var last = ""
        fun show(t: String) { if (t != last) { last = t; nm.notify(NOTIF_ID, ongoing(t)) } }   // only redraw the notification when it changed
        while (isActive) {
            Radar.prune()
            refreshLinks()
            if (!lm.isLocationEnabled) show("Location is OFF: Android hides all WiFi and Bluetooth results. Turn Location on.")
            else {
                val all = Radar.seen.values
                val n = "${all.count { !it.wifi }} Bluetooth, ${all.count { it.wifi }} WiFi, " +
                    "${all.count { it.kind.tracker }} trackers, ${all.count { it.following }} moving with you"
                show(n)
            }
            if (pm.isInteractive) Radar.bump()
            delay(60_000L)
        }
    }

    private fun ongoing(text: String): Notification {
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scan running", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Phulax Android Tell Me is scanning").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Stop scanning", stop).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
}
