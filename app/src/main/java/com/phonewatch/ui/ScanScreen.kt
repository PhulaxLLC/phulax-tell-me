package com.phonewatch.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.phonewatch.scan.Kind
import com.phonewatch.scan.Radar
import com.phonewatch.scan.ScanService
import com.phonewatch.scan.Seen

@Composable
fun ScanScreen() {
    val ctx = LocalContext.current
    val running by Radar.running.collectAsState()
    Radar.version.collectAsState().value                      // redraw on every update
    var missing by remember { mutableStateOf(listOf<String>()) }
    var showAllBt by remember { mutableStateOf(false) }
    var showAllWifi by remember { mutableStateOf(false) }
    var showCells by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Seen?>(null) }
    var map by remember { mutableStateOf(false) }
    var mapFocus by remember { mutableStateOf<Seen?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        missing = res.filterValues { !it }.keys.map { it.substringAfterLast('.') }
        ScanService.start(ctx)
    }
    val mine = Radar.ignored()
    val all = Radar.seen.values.toList()
    val flagged = all.filter { (it.kind.alert || it.following) && it.addr.uppercase() !in mine }.sortedByDescending { it.last }
    val trackersNear = all.filter { it.kind.tracker && !it.kind.alert && it.addr.uppercase() !in mine }
    val bt = all.filter { !it.wifi }.sortedByDescending { it.rssi }
    val wifi = all.filter { it.wifi }.sortedByDescending { it.rssi }
    val cells = synchronized(Radar.cells) { Radar.cells.toList() }.asReversed()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Section("Scan", sub = "Trackers, cameras, Bluetooth, WiFi and cell towers around you. Listen-only: nothing is sent to these devices.") }
        item {
            InfoCard {
                SwitchRow("Scan around me", if (running) "Running in the background. Stop it from here or the notification." else
                    "Uses Bluetooth, WiFi and location. Keep Bluetooth and location turned on.", running) { on ->
                    if (!on) ScanService.stop(ctx)
                    else {
                        val need = ScanService.needed().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                        if (need.isEmpty()) ScanService.start(ctx) else ask.launch(need.toTypedArray())
                    }
                }
                SwitchRow("Speak out loud", "Says what each new thing is, how far away, if it's coming closer, and if it's a security risk.",
                    Radar.voice) { Radar.voice = it }
                SwitchRow("Battery saver", "Gentler scans and location while the screen is off. Trackers are still found, a little slower.",
                    Radar.saver) { Radar.saver = it }
                if (missing.isNotEmpty()) Muted("Not allowed: ${missing.joinToString()}. Some scans will be missing until you allow them in Settings > Apps > Phulax Android Tell Me > Permissions.")
                if (running) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Bluetooth", "${bt.size}", Modifier.weight(1f))
                    Stat("WiFi", "${wifi.size}", Modifier.weight(1f))
                    Stat("Flagged", "${flagged.size}", Modifier.weight(1f))
                }
                if (running) TextButton(onClick = { mapFocus = null; map = true }) { Text("Show map") }
            }
        }
        item { RadioOffWarning() }
        item { Section("Flagged", sub = "Trackers away from their owner, Flock and Axon gear, and anything moving with you for ${Radar.FOLLOW_MIN_MS / 60_000}+ min and ${Radar.FOLLOW_MIN_M.toInt()}+ m") }
        if (flagged.isEmpty()) item { Muted(if (running) "Nothing flagged so far." else "Turn on scanning to start.") }
        items(flagged, key = { "f" + it.key }) { SeenRow(it, warn = true) { picked = it } }
        if (trackersNear.isNotEmpty()) {
            item { Section("Trackers with their owner nearby", sub = "Usually someone else's AirTag or tag in their own bag. It is flagged if it keeps moving with you.") }
            items(trackersNear, key = { "t" + it.key }) { SeenRow(it) { picked = it } }
        }
        item { AlertSettings() }
        item { Section("Cell tower", sub = "The tower your phone is using. A drop to 2G is the main warning sign of a fake tower (stingray).") }
        if (cells.isEmpty()) item { Muted("No tower info yet.") }
        items(cells.take(if (showCells) 8 else 1), key = { "c${it.t}" }) { c ->
            InfoCard(warn = c.tech.startsWith("2G")) {
                Text("${c.tech}${c.dbm?.let { ", $it dBm" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
                Text(c.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Muted("since ${fmtTime(c.t)}", MaterialTheme.typography.bodySmall)
            }
        }
        if (cells.size > 1) item { TextButton(onClick = { showCells = !showCells }) { Text(if (showCells) "Hide earlier towers" else "Earlier towers (${cells.size - 1})") } }
        listOf(Triple("Bluetooth", bt, showAllBt), Triple("WiFi", wifi, showAllWifi)).forEach { (title, list, showAll) ->
            item { Section("All $title (${list.size})", sub = "Strongest (closest) first") }
            items((if (showAll) list else list.take(25)), key = { title + it.key }) { SeenRow(it) { picked = it } }
            if (list.size > 25 && !showAll) item {
                TextButton(onClick = { if (title == "WiFi") showAllWifi = true else showAllBt = true }) { Text("Show all ${list.size}") }
            }
        }
        item { ContactsSection() }
        item { CantSee() }
        item { Spacer(Modifier.height(24.dp)) }
    }

    picked?.let { s ->
        val isMine = s.addr.uppercase() in mine
        AlertDialog(onDismissRequest = { picked = null },
            title = { Text(s.name?.takeIf { it.isNotBlank() } ?: s.kind.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.kind.label + (s.guess?.label()?.takeIf { it.isNotBlank() && s.kind in listOf(Kind.BT, Kind.WIFI, Kind.APPLE) }?.let { " · probably $it" } ?: ""))
                    val risk = com.phonewatch.scan.Voice.risk(s)
                    Text(risk, color = if (risk.startsWith("Possible")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.addr, fontFamily = FontFamily.Monospace)
                    s.detail?.let { Text(it) }
                    Text("Signal ${s.rssi} dBm: ${distance(s)} (rough guess, walls and pockets change it)")
                    s.trend().label.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text(s.motion().label.ifEmpty { "Not enough readings yet to tell if it's moving. Keep scanning, and walk around if you can." },
                        style = if (s.motion().label.isEmpty()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall)
                    s.estimate()?.let { (la, lo) -> Text("Best guess of where it is: ${"%.5f".format(la)}, ${"%.5f".format(lo)}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { mapFocus = s; map = true; picked = null }) { Text("Show on map") }
                    Text("First seen ${fmtTime(s.first)}, last seen ${fmtAgo(s.last)}, ${s.hits} times")
                    if (s.movedM > 0) Text("Seen across ${"%.2f".format(s.movedM / 1000)} km while you moved")
                    if (!s.wifi) Muted("Many phones and tags change their Bluetooth address every 15 minutes to a day, so one device can show up more than once.", MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { Radar.setMine(s.addr.uppercase(), !isMine); picked = null }) { Text(if (isMine) "Not mine" else "This is mine (don't flag)") } },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Close") } })
    }
    if (map) ScanMapDialog(mapFocus, (flagged + trackersNear + bt.take(40) + wifi.take(40)).distinctBy { it.key }) { map = false }
}

/** Android returns no WiFi or Bluetooth scan results at all while Location is off, so say so loudly. */
@Composable
private fun RadioOffWarning() {
    val ctx = LocalContext.current
    Radar.version.collectAsState().value
    val locOff = !(ctx.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled ?: true)
    val btOff = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == false
    if (!locOff && !btOff) return
    InfoCard(warn = true) {
        if (locOff) {
            Text("Location is off", style = MaterialTheme.typography.titleMedium)
            Muted("Android hides every WiFi and Bluetooth scan result from apps while Location is off, so the scan finds nothing. Turn it on while scanning.")
            TextButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Turn on Location") }
        }
        if (btOff) {
            Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
            Muted("Trackers, AirTags and Bluetooth devices can't be found until Bluetooth is on.")
            TextButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Turn on Bluetooth") }
        }
    }
}

private fun fmtDay(t: Long): String = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))

/** Devices that actually connected to this phone, from Android's own history (needs the helper). */
@Composable
private fun ContactsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val report by Radar.contacts.collectAsState()
    var state by remember { mutableStateOf(com.phonewatch.debloat.Shell.state(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var showPaired by remember { mutableStateOf(false) }
    var pickedBt by remember { mutableStateOf<com.phonewatch.scan.Contacts.Bt?>(null) }
    fun check() {
        state = com.phonewatch.debloat.Shell.state(ctx)
        if (state != com.phonewatch.debloat.Shell.State.READY) return
        busy = true
        scope.launch {
            Radar.contacts.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.phonewatch.scan.Contacts.read() }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (report == null) check() }

    Section("Devices that connected to your phone", sub = "From Android's own Bluetooth and WiFi history. Unpaired devices and forced WiFi disconnects are flagged.")
    when (state) {
        com.phonewatch.debloat.Shell.State.READY -> {}
        else -> HelperCard(onReady = { check() })
    }
    val r = report
    if (state == com.phonewatch.debloat.Shell.State.READY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { check() }, enabled = !busy) { Text(if (busy) "Checking…" else "Check now") }
            if (r?.since != null) Muted("History since ${fmtDay(r.since)}. Checked again every 5 min while scanning.", MaterialTheme.typography.bodySmall)
        }
    }
    if (r == null) return
    r.error?.let { Muted("Couldn't read the history: $it") }
    val unknown = r.bt.filter { !it.paired }
    val paired = r.bt.filter { it.paired }
    if (r.deauthBurst) InfoCard(warn = true) {
        Text("WiFi kicked you off 3+ times within 10 minutes", style = MaterialTheme.typography.bodyLarge)
        Muted("With a good signal. That's the pattern of a deauth attack (someone forcing you off WiFi), though a bad router can do it too.")
    }
    if (unknown.isEmpty()) Muted("No unpaired Bluetooth device has connected to your phone.")
    unknown.forEach { b ->
        InfoCard(Modifier.clickable { pickedBt = b }, warn = true) {
            Text("Unknown: ${b.name ?: "device"} ending ${b.addr.takeLast(5)}", style = MaterialTheme.typography.bodyLarge)
            b.type?.let { Muted(it, MaterialTheme.typography.bodySmall) }
            Muted("${if (b.classic) "Classic Bluetooth" else "Bluetooth LE"} · connected ${b.times.size} time(s) · last ${fmtDay(b.times.last())}. " +
                "Not one of your paired devices. Phones, watches and cars sometimes connect briefly to share or check; repeated connections are worth a look.",
                MaterialTheme.typography.bodySmall)
        }
    }
    TextButton(onClick = { showPaired = !showPaired }) { Text(if (showPaired) "Hide paired devices" else "Your paired devices (${paired.size})") }
    if (showPaired) paired.forEach { b ->
        InfoCard(Modifier.clickable { pickedBt = b }) {
            Text(b.name ?: "Paired device ${b.addr.takeLast(5)}", style = MaterialTheme.typography.bodyLarge)
            Muted("Connected ${b.times.size} time(s) · last ${fmtDay(b.times.last())}", MaterialTheme.typography.bodySmall)
        }
    }
    pickedBt?.let { b ->
        AlertDialog(onDismissRequest = { pickedBt = null },
            title = { Text(b.name ?: "Device ending ${b.addr.takeLast(5)}") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { Text(if (b.paired) "Paired with your phone" else "NOT paired with your phone") }
                    item { Text("Address ${b.fullAddr ?: b.addr}", fontFamily = FontFamily.Monospace) }
                    b.type?.let { item { Text("Type: $it") } }
                    item { Text(if (b.classic) "Classic Bluetooth" else "Bluetooth LE") }
                    b.profiles?.takeIf { it.isNotBlank() }?.let { item { Text("Can do: $it") } }
                    item { Text("Connected ${b.times.size} time(s), first ${fmtDay(b.times.first())}, last ${fmtDay(b.times.last())}") }
                    item { Text("All connections", style = MaterialTheme.typography.titleSmall) }
                    items(b.times.asReversed().take(100)) { Text(fmtDay(it), style = MaterialTheme.typography.bodySmall) }
                    if (b.raw.isNotEmpty()) {
                        item { Text("Raw records from Android", style = MaterialTheme.typography.titleSmall) }
                        items(b.raw) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickedBt = null }) { Text("Close") } })
    }
    val bad = r.drops.filter { it.suspicious }
    Muted("WiFi: ${r.drops.size} disconnects caused by the network side" +
        if (bad.isEmpty()) ", none with the codes deauth attacks use." else ", ${bad.size} with deauth-attack codes:")
    bad.take(10).forEach { d ->
        InfoCard(warn = true) {
            Text("Forced off WiFi · ${fmtDay(d.t)}", style = MaterialTheme.typography.bodyLarge)
            Muted("Reason ${d.reason} (${d.reasonName})${d.rssi?.let { ", signal $it dBm" } ?: ""}", MaterialTheme.typography.bodySmall)
        }
    }
}

private val ALERT_GROUPS = listOf(
    "Trackers away from their owner" to listOf(Kind.FIND_MY, Kind.TILE, Kind.SMARTTAG, Kind.GOOGLE_TAG),
    "Flock cameras" to listOf(Kind.FLOCK),
    "Axon police gear" to listOf(Kind.AXON),
    "Smart glasses with cameras" to listOf(Kind.GLASSES),
    "Trackers with their owner nearby" to listOf(Kind.FIND_MY_NEAR),
    "Apple devices" to listOf(Kind.APPLE),
    "Other Bluetooth devices" to listOf(Kind.BT),
    "WiFi networks" to listOf(Kind.WIFI),
)

@Composable
private fun AlertSettings() {
    Radar.version.collectAsState().value                      // redraw when a setting changes
    var open by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    InfoCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("What to alert on", style = MaterialTheme.typography.titleMedium)
                Muted(if (Radar.allAlertsOn()) "Everything" else "Trackers, cameras, smart glasses and police gear (default)", MaterialTheme.typography.bodySmall)
            }
            Text(if (open) "Hide" else "Change", color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            SwitchRow("Everything", "Every new tracker, camera, Bluetooth device, WiFi network and cell tower change. " +
                "Busy places can mean hundreds: the voice reads the 2 closest of each kind and counts the rest.", Radar.allAlertsOn()) { Radar.setAllAlerts(it) }
            val on = Radar.alertKinds()
            ALERT_GROUPS.forEach { (title, kinds) ->
                SwitchRow(title, checked = kinds.all { it.name in on }) { Radar.setAlert(kinds, it) }
            }
            SwitchRow("Cell tower changes", "Drops to 2G always alert", Radar.cellAlerts) { Radar.cellAlerts = it }
            Muted("Anything moving with you always alerts.", MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().clickable { voiceOpen = !voiceOpen }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                Muted("Language, voice, speed and the words it says", MaterialTheme.typography.bodySmall)
            }
            Text(if (voiceOpen) "Hide" else "Change", color = MaterialTheme.colorScheme.primary)
        }
        if (voiceOpen) VoiceSettings()
    }
}

private fun distance(s: Seen) = com.phonewatch.scan.Guesser.feetText(s.feet())

@Composable
private fun SeenRow(s: Seen, warn: Boolean = false, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val named = !s.name.isNullOrBlank()
                Text(if (named) s.name!! else s.kind.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val what = s.guess?.label()?.takeIf { it.isNotBlank() && s.kind in listOf(Kind.BT, Kind.WIFI, Kind.APPLE) }
                if (named && (s.kind != Kind.BT && s.kind != Kind.WIFI || what != null)) Text(what ?: s.kind.label, style = MaterialTheme.typography.bodySmall)
                else if (!named && what != null) Text(what, style = MaterialTheme.typography.bodySmall)
                val sub = listOfNotNull(distance(s), s.trend().label.takeIf { it.isNotEmpty() }, s.motion().label.takeIf { it.isNotEmpty() }, s.addr, s.detail,
                    if (s.following) "moving with you ${(s.last - s.first) / 60_000} min" else null)
                Text(sub.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${s.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                Muted(fmtAgo(s.last), MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CantSee() {
    Section("What a phone can't see")
    Inferred("Devices that connect to your phone are shown above. Android never shows apps the radio traffic itself: WiFi probe requests, " +
        "devices that only scan without connecting, or raw RF signals. Seeing those needs a small add-on radio such as an ESP32 board in monitor mode. " +
        "Apps on this phone talking to the internet are shown in the Apps tab. " +
        "Flock cameras are only found when they broadcast their usual WiFi/Bluetooth names, so a quiet one can be missed.")
}
