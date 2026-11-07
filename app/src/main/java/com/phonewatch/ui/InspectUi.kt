package com.phonewatch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonewatch.data.*
import com.phonewatch.engine.PwVpnService
import com.phonewatch.inspect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the encrypted capture database off the main thread (it unwraps its key from the Keystore). */
@Composable
fun rememberCapDao(): CapDao? {
    val ctx = LocalContext.current
    val gen = CaptureDb.generation.intValue
    val dao by produceState<CapDao?>(null, gen) { value = null; value = withContext(Dispatchers.IO) { runCatching { CaptureDb.get(ctx).dao() }.getOrNull() } }
    return dao
}

private fun sensColor(t: SensType) = when (t) {
    SensType.AD_ID -> Color(0xFFE57373); SensType.DEVICE_ID -> Color(0xFFFFB74D); SensType.HW_ID -> Color(0xFFF06292)
    SensType.LOCATION -> Color(0xFFFFD54F); SensType.EMAIL -> Color(0xFF64B5F6); SensType.PHONE -> Color(0xFF4DD0E1)
    SensType.CONTACTS -> Color(0xFFBA9BE0); SensType.AUTH -> Color(0xFFFF8A65)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensChips(mask: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SensType.of(mask).forEach { t ->
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(sensColor(t).copy(alpha = 0.3f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(t.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun DeepWarning() {
    InfoCard(warn = true) {
        Text("Read this first", style = MaterialTheme.typography.titleMedium)
        Text("Deep Inspect decrypts the web traffic of the apps you choose, on this phone, so you can read what they send. " +
            "To do that Phulax Android Tell Me makes its own certificate authority (CA). While its certificate is installed, anyone holding the CA key could read traffic. " +
            "The key is locked in this phone's Keystore and never leaves it, but keep Deep Inspect OFF when you don't need it, and delete the CA when you're done.",
            style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectScreen(open: (Detail) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by PwVpnService.running.collectAsState()
    val dao = rememberCapDao()
    var hasCa by remember { mutableStateOf(CaManager.exists()) }
    var installed by remember { mutableStateOf(false) }
    LaunchedEffect(hasCa) { installed = withContext(Dispatchers.IO) { CaManager.installedInAndroid() } }
    var guide by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var afterDelete by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var secrets by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showApps by remember { mutableStateOf(false) }

    val today = remember { Repo.startOfToday() }
    val capApps by remember(dao) { dao?.apps(0) ?: emptyFlow() }.collectAsState(emptyList())
    val sens by remember(dao) { dao?.sensSince(today) ?: emptyFlow() }.collectAsState(emptyList())
    val statuses by remember(dao) { dao?.statuses() ?: emptyFlow() }.collectAsState(emptyList())
    val results by remember(dao, query) { if (dao == null || query.length < 2) emptyFlow() else dao.search(query) }.collectAsState(emptyList())
    val seenApps by remember { Repo.db.conn().apps(System.currentTimeMillis() - 30 * 86_400_000L) }.collectAsState(emptyList())
    val enabled = DeepInspect.enabled

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Deep Inspect", style = MaterialTheme.typography.headlineMedium) }
        item { Muted("See the actual content apps send: decrypted on this phone, never sent anywhere.") }
        item { DeepWarning() }
        item {
            InfoCard(warn = enabled) {
                SwitchRow("Deep Inspect", when {
                    !hasCa -> "Create the Phulax Android Tell Me certificate first (below)"
                    !running -> "Phulax Android Tell Me itself is off. Turn it on in the Today tab."
                    enabled -> "ON: decrypting the chosen apps. A notification shows while it's on."
                    else -> "Off"
                }, enabled, enabled = hasCa || enabled) {
                    DeepInspect.enabled = it; PwVpnService.deepChanged()
                }
                if (hasCa && !installed) Muted("The certificate is not installed in Android yet, so apps will reject it and be passed through. Use \"Install certificate\" below.", MaterialTheme.typography.bodySmall)
            }
        }

        item { Section("Certificate") }
        item {
            InfoCard {
                if (!hasCa) {
                    Text("No Phulax Android Tell Me certificate yet.", style = MaterialTheme.typography.bodyLarge)
                    Muted("It is made on this phone. Its private key stays in the Android Keystore and can't be exported.", MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = { busy = true; scope.launch { withContext(Dispatchers.IO) { runCatching { CaManager.create() } }; hasCa = CaManager.exists(); busy = false } }) { Text("Create Phulax Android Tell Me certificate") }
                } else {
                    Text(CaManager.CA_NAME, style = MaterialTheme.typography.titleMedium)
                    Muted("Fingerprint (SHA-256): ${CaManager.fingerprint()}", MaterialTheme.typography.bodySmall)
                    Text(if (installed) "✔ Installed in Android (user certificate)" else "✖ Not installed in Android yet", style = MaterialTheme.typography.bodyMedium,
                        color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { guide = true }) { Text("Install certificate") }
                        OutlinedButton(onClick = { scope.launch { installed = withContext(Dispatchers.IO) { CaManager.installedInAndroid() } } }) { Text("Check again") }
                    }
                    OutlinedButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete CA & turn off") }
                }
            }
        }

        item { Section("Data sent today", sub = "Personal data found in decrypted requests, by type and company") }
        item {
            InfoCard {
                if (sens.isEmpty()) Muted("Nothing detected yet today.")
                val byType = SensType.entries.mapNotNull { t -> val l = sens.filter { it.sens and t.bit != 0 }; if (l.isEmpty()) null else t to l }
                byType.forEach { (t, l) ->
                    val cos = l.groupBy { it.company ?: it.host }.map { it.key to it.value.size }.sortedByDescending { it.second }
                    Row(verticalAlignment = Alignment.CenterVertically) { SensChips(t.bit); Spacer(Modifier.width(8.dp)); Text("${l.size}×", style = MaterialTheme.typography.titleMedium) }
                    Muted("to " + cos.take(6).joinToString(", ") { "${it.first} (${it.second})" } + if (cos.size > 6) " +${cos.size - 6} more" else "", MaterialTheme.typography.bodySmall)
                    Muted("from " + l.map { it.uid }.distinct().joinToString(", ") { Apps.meta(it).label }, MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = { open(Detail.Recipients) }) { Text("All recipients (every app) →") }
            }
        }

        item { Section("Captured apps", sub = "Tap an app to see each request") }
        if (capApps.isEmpty()) item { Muted(if (enabled) "No decrypted traffic yet. Use an app you selected." else "Nothing captured.") }
        items(capApps, key = { "ca" + it.uid }) { a ->
            val meta = remember(a.uid) { Apps.meta(a.uid) }
            Card(Modifier.fillMaxWidth().clickable { open(Detail.InspectApp(a.uid)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(meta.pkg, 40.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta.label, style = MaterialTheme.typography.titleMedium)
                        Muted("${a.n} requests · ${fmtBytes(a.bytes)} of request bodies · ${fmtAgo(a.lastTime)}", MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            OutlinedTextField(query, { query = it }, label = { Text("Search all requests (URL, header, body)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        items(results, key = { "s" + it.id }) { FlowRowView(it, showApp = true) { open(Detail.Request(it.id)) } }

        val cant = statuses.filter { it.status == "cant_inspect" || it.status == "bad_cert" }
        item { Section("Can't inspect", sub = "Passed through untouched (the app keeps working) or blocked") }
        if (cant.isEmpty()) item { Muted("None so far.") }
        items(cant.take(60), key = { "x" + it.key }) { s ->
            val meta = remember(s.uid) { Apps.meta(s.uid) }
            InfoCard(warn = s.status == "bad_cert") {
                Text("${meta.label} · ${if (s.host == "*") "whole app" else s.host}", style = MaterialTheme.typography.titleSmall)
                Text(if (s.status == "bad_cert") "Blocked: real server's certificate is invalid" else "Can't inspect (app doesn't trust user certificates / pins)", style = MaterialTheme.typography.bodyMedium)
                s.detail?.let { Muted(it, MaterialTheme.typography.bodySmall) }
                if (s.status == "cant_inspect") TextButton(onClick = { DeepInspect.forgetCantInspect(s.uid) }) { Text("Try again for this app") }
            }
        }

        item { Section("Apps to inspect", sub = "Off by default. Money, password, 2FA apps and Google Play services stay excluded unless you turn them on one by one.") }
        item {
            InfoCard {
                SwitchRow("All apps (except the exclusion list)", null, DeepInspect.allApps) { DeepInspect.allApps = it }
                TextButton(onClick = { showApps = !showApps }) { Text(if (showApps) "Hide app list" else "Choose apps one by one (${DeepInspect.chosenApps().size} chosen)") }
                if (showApps) seenApps.filter { it.uid >= 10000 && it.pkg != ctx.packageName }.forEach { a ->
                    val meta = remember(a.uid) { Apps.meta(a.uid) }
                    val why = remember(meta.pkg) { Exclusions.reason(meta.pkg) }
                    SwitchRow(meta.label, why?.let { "Excluded by default: $it" } ?: meta.pkg, DeepInspect.appSelected(meta.pkg)) { DeepInspect.setApp(meta.pkg, it) }
                }
            }
        }

        item { Section("Storage", sub = "Encrypted on this phone with a Keystore key. Nothing ever leaves the phone.") }
        item {
            InfoCard {
                Text("Keep captured data for", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 3, 7, 14, 30).forEach { d -> FilterChip(DeepInspect.retentionDays == d, { DeepInspect.retentionDays = d; scope.launch(Dispatchers.IO) { DeepInspect.purgeOld() } }, { Text(if (d == 1) "1 day" else "$d days") }) }
                }
                Text("Store up to this much of each body", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(64, 256, 1024).forEach { k -> FilterChip(DeepInspect.bodyCapKb == k, { DeepInspect.bodyCapKb = k }, { Text(if (k >= 1024) "${k / 1024} MB" else "$k KB") }) }
                }
                Muted("Video, audio and images are logged as type + size only.", MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { confirmWipe = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Wipe all captured data") }
            }
        }
        item { Section("Export for review", sub = "Writes a readable folder to Downloads/Phulax Android Tell Me: every captured request, every connection of every app, and a plain summary") }
        item {
            InfoCard {
                SwitchRow("Include secrets", "Off = Authorization/Cookie headers and passwords are masked in the files", secrets) { secrets = it }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { msg = withContext(Dispatchers.IO) { runCatching { "Saved to " + Exporter.exportReview(ctx, secrets) }.getOrElse { "Export failed: ${it.message}" } }; busy = false }
                }) { Text(if (busy) "Working…" else "Export for review") }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (guide) AlertDialog(
        onDismissRequest = { guide = false },
        title = { Text("Install the certificate") },
        text = {
            LazyColumn { item {
                var path by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { path = withContext(Dispatchers.IO) { CaManager.exportPublicCert() } }
                Text("1. Phulax Android Tell Me saved the PUBLIC certificate to ${path ?: "Downloads"} (Phulax Android Tell Me-CA.crt). The private key is never exported.\n\n" +
                    "2. Open Settings > Security and privacy > More security settings > Install from device storage > CA certificate.\n\n" +
                    "3. Tap \"Install anyway\", confirm with your PIN/fingerprint, and pick Phulax Android Tell Me-CA.crt in Downloads.\n\n" +
                    "4. Come back and tap \"Check again\".\n\n" +
                    "Only apps that trust user certificates can be read (Chrome and many browsers do; most Play Store apps on Android 7+ don't, and they are passed through untouched).",
                    style = MaterialTheme.typography.bodyMedium)
            } }
        },
        confirmButton = { TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }; guide = false }) { Text("Open Security settings") } },
        dismissButton = { TextButton(onClick = { guide = false }) { Text("Close") } })

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the Phulax Android Tell Me CA?") },
        text = { Text("Deep Inspect turns off and the CA key is wiped from the Keystore, so nothing can be decrypted with it again. Captured data stays until you wipe it.") },
        confirmButton = { TextButton(onClick = {
            DeepInspect.enabled = false; PwVpnService.deepChanged(); CaManager.delete(); hasCa = false; confirmDelete = false; afterDelete = true
        }) { Text("Delete CA") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })

    if (afterDelete) AlertDialog(
        onDismissRequest = { afterDelete = false },
        title = { Text("CA key deleted") },
        text = { Text("Now remove the certificate from Android too:\nSettings > Security and privacy > More security settings > View security certificates > User > \"${CaManager.CA_NAME}\" > Remove.\n\n(Or: More security settings > Remove all user certificates.)") },
        confirmButton = { TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }; afterDelete = false }) { Text("Open Security settings") } },
        dismissButton = { TextButton(onClick = { afterDelete = false }) { Text("Done") } })

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Wipe all captured data?") },
        text = { Text("Deletes every captured request and the database key. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmWipe = false; scope.launch(Dispatchers.IO) { DeepInspect.wipeAll() }; msg = "All captured data wiped." }) { Text("Wipe") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } })
}

@Composable
fun FlowRowView(f: FlowHead, showApp: Boolean = false, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.method, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(f.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (f.status > 0) f.status.toString() else "…", style = MaterialTheme.typography.labelLarge)
            }
            Text(f.url.substringAfter(f.host).ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            Muted((if (showApp) Apps.meta(f.uid).label + " · " else "") + "${fmtTime(f.time)} · ↑ ${fmtBytes(f.reqSize)} ↓ ${fmtBytes(f.respSize)} · ${f.proto}" + (f.company?.let { " · $it" } ?: ""), MaterialTheme.typography.bodySmall)
            if (f.sens != 0) { Spacer(Modifier.height(4.dp)); SensChips(f.sens) }
        }
    }
}

@Composable
fun InspectAppScreen(uid: Int, open: (Detail) -> Unit) {
    val dao = rememberCapDao()
    val meta = remember(uid) { Apps.meta(uid) }
    var q by remember { mutableStateOf("") }
    val flows by remember(dao, q) { dao?.flows(uid, q) ?: emptyFlow() }.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(meta.pkg, 56.dp); Spacer(Modifier.width(12.dp))
                Column { Text(meta.label, style = MaterialTheme.typography.headlineSmall); Muted("Decrypted requests, newest first", MaterialTheme.typography.bodySmall) }
            }
        }
        item { OutlinedTextField(q, { q = it }, label = { Text("Search this app's requests") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (flows.isEmpty()) item { Muted("No requests.") }
        items(flows, key = { it.id }) { f -> FlowRowView(f) { open(Detail.Request(f.id)) } }
    }
}

private fun highlight(text: String, maxLen: Int = 60_000): AnnotatedString {
    val t = if (text.length > maxLen) text.take(maxLen) + "\n… (${text.length - maxLen} more characters, use Export to see all)" else text
    val hits = runCatching { Sensitive.scan(t) }.getOrDefault(emptyList()).filter { it.end > it.start }
    return buildAnnotatedString {
        append(t)
        hits.forEach { addStyle(SpanStyle(background = sensColor(it.type).copy(alpha = 0.45f), fontWeight = FontWeight.Bold), it.start, minOf(it.end, t.length)) }
    }
}

@Composable
private fun HeaderList(headers: String?, reveal: Boolean, onReveal: () -> Unit) {
    val lines = headers?.lines()?.filter { it.isNotBlank() }.orEmpty()
    if (lines.isEmpty()) { Muted("(none)", MaterialTheme.typography.bodySmall); return }
    lines.forEach { l ->
        val name = l.substringBefore(':'); val value = l.substringAfter(':').trim()
        val masked = Sensitive.isMaskedHeader(name) && !reveal
        Row(Modifier.fillMaxWidth().clickable(enabled = masked, onClick = onReveal).padding(vertical = 2.dp)) {
            Text(buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(name); append(": "); pop()
                if (masked) { pushStyle(SpanStyle(color = Color(0xFFFF8A65))); append("•••••• (tap to show)"); pop() } else append(highlight(value))
            }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
        }
    }
}

@Composable
fun RequestScreen(id: Long) {
    val ctx = LocalContext.current
    val dao = rememberCapDao()
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf<CapFlow?>(null) }
    LaunchedEffect(dao, id) { if (dao != null) f = withContext(Dispatchers.IO) { dao.get(id) } }
    var reveal by remember { mutableStateOf(false) }
    var showResp by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val flow = f ?: run { Box(Modifier.fillMaxSize().padding(24.dp)) { Muted("Loading…") }; return }
    val enc = remember(flow) { flow.reqHeaders.lines().firstOrNull { it.startsWith("content-encoding:", true) }?.substringAfter(':')?.trim() }
    val reqText = remember(flow, reveal) { flow.reqBody?.let { Sensitive.bodyText(it, flow.reqType, enc) }?.let { if (reveal) it else Sensitive.maskBodyText(it) } }
    val respEnc = remember(flow) { flow.respHeaders?.lines()?.firstOrNull { it.startsWith("content-encoding:", true) }?.substringAfter(':')?.trim() }
    val respText = remember(flow, showResp) { if (showResp) flow.respBody?.let { Sensitive.bodyText(it, flow.respType, respEnc) } else null }
    val meta = remember(flow.uid) { Apps.meta(flow.uid) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(meta.pkg, 40.dp); Spacer(Modifier.width(10.dp))
                Column { Text(meta.label, style = MaterialTheme.typography.titleMedium); Muted("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(flow.time))} · ${flow.proto}", MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SelectionContainer { Text("${flow.method} ${flow.url}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.SemiBold) }
        }
        item { Muted("Server ${flow.host} · ${flow.ip}:${flow.port} · ${flow.company ?: "unknown company"} · status ${if (flow.status > 0) flow.status else "no response"}", MaterialTheme.typography.bodySmall) }
        if (flow.sens != 0) item { InfoCard { Text("Personal data found in this request", style = MaterialTheme.typography.titleSmall); SensChips(flow.sens); Muted("Highlighted below.", MaterialTheme.typography.bodySmall) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide secrets" else "Show hidden values") }
                Button(onClick = { scope.launch { msg = withContext(Dispatchers.IO) { runCatching { "Saved to " + Exporter.exportRequest(ctx, flow, reveal) }.getOrElse { "Export failed: ${it.message}" } } } }) { Text("Export to Downloads") }
            }
        }
        msg?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item { Section("Request headers") }
        item { InfoCard { HeaderList(flow.reqHeaders, reveal) { reveal = true } } }
        item { Section("Request body", sub = "${fmtBytes(flow.reqSize)}${if (flow.reqTrunc) " (only the first ${fmtBytes(flow.reqBody?.size?.toLong() ?: 0)} stored)" else ""}${flow.reqType?.let { " · $it" } ?: ""}") }
        item {
            InfoCard {
                if (reqText == null) Muted("(no body)") else
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer { Text(highlight(reqText), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) }
                    }
            }
        }
        item { Section("Response", sub = "Status ${flow.status} · ${fmtBytes(flow.respSize)}${flow.respType?.let { " · $it" } ?: ""}${flow.note?.let { " · $it" } ?: ""}") }
        item { InfoCard { HeaderList(flow.respHeaders, reveal) { reveal = true } } }
        item {
            if (!showResp) TextButton(onClick = { showResp = true }) { Text("Show response body") }
            else InfoCard {
                if (respText == null) Muted("(not stored)") else
                    Box(Modifier.horizontalScroll(rememberScrollState())) { SelectionContainer { Text(respText.take(40_000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

enum class RecipSort(val label: String) { BYTES("Bytes"), APPS("Apps"), COMPANY("Company"), COUNTRY("Country") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientsScreen(open: (Detail) -> Unit) {
    val ctx = LocalContext.current
    val range by UiState.range.collectAsState()
    val since = remember(range) { range.since() }
    val rows by remember(since) { Repo.db.conn().recipients(since) }.collectAsState(emptyList())
    var list by remember { mutableStateOf<List<Recipient>>(emptyList()) }
    LaunchedEffect(rows) { list = withContext(Dispatchers.Default) { Recipients.build(ctx, rows) } }
    var sort by remember { mutableStateOf(RecipSort.BYTES) }
    var q by remember { mutableStateOf("") }
    val shown = remember(list, sort, q) {
        list.filter { q.isEmpty() || it.key.contains(q, true) || (it.company ?: "").contains(q, true) || (it.info?.country ?: "").equals(q, true) }
            .let { l -> when (sort) {
                RecipSort.BYTES -> l.sortedByDescending { it.up }
                RecipSort.APPS -> l.sortedByDescending { it.apps }
                RecipSort.COMPANY -> l.sortedBy { it.company ?: "~" }
                RecipSort.COUNTRY -> l.sortedBy { it.info?.country ?: "~" }
            } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Recipients", style = MaterialTheme.typography.headlineMedium) }
        item { Muted("Every server any app sent data to (${list.size}). Shown for all traffic, decrypted or not.") }
        item { Inferred("Phulax Android Tell Me only sees the first recipient. What the receiving company later shares with others isn't visible from the phone.") }
        item { RangeChips() }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sort:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                RecipSort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) }
            }
        }
        item { OutlinedTextField(q, { q = it }, label = { Text("Filter: domain, company or country code") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        items(shown.take(400), key = { it.key }) { r ->
            Card(Modifier.fillMaxWidth().clickable { open(Detail.Recipient(r.key)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text((if (r.blocked > 0) "⛔ " else "") + r.key, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("${r.company ?: "unknown company"}${r.parent?.let { " ($it)" } ?: ""} · ${r.apps} app${if (r.apps == 1) "" else "s"} · ↑ ${fmtBytes(r.up)} ↓ ${fmtBytes(r.down)}", MaterialTheme.typography.bodySmall)
                    Muted("${r.info?.place() ?: "location unknown"} · ${r.info?.hosting ?: r.info?.asOrg ?: ""}", MaterialTheme.typography.bodySmall)
                    Recipients.cloakedBy(r.cname)?.let { Text("CNAME-cloaked: really ${it}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Muted("IP Geolocation by DB-IP (db-ip.com), CC BY 4.0. Offline database bundled in the app; no online lookups.", MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun RecipientDetailScreen(key: String, open: (Detail) -> Unit) {
    val ctx = LocalContext.current
    val range by UiState.range.collectAsState()
    val since = remember(range) { range.since() }
    val rows by remember(since) { Repo.db.conn().recipients(since) }.collectAsState(emptyList())
    var r by remember { mutableStateOf<Recipient?>(null) }
    LaunchedEffect(rows, key) { r = withContext(Dispatchers.Default) { Recipients.build(ctx, rows.filter { (it.host ?: it.ip) == key }).firstOrNull() } }
    val dao = rememberCapDao()
    val sens by remember(dao) { dao?.sensSince(0) ?: emptyFlow() }.collectAsState(emptyList())
    val sensHere = remember(sens, key) { sens.filter { it.host == key } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SelectionContainer { Text(key, style = MaterialTheme.typography.headlineSmall) } }
        val r = r ?: run { item { Muted("Loading… (no data in this time range?)") }; return@LazyColumn }
        item {
            InfoCard {
                Text("Who receives it", style = MaterialTheme.typography.titleMedium)
                Text("Company: ${r.company ?: "unknown"}" + (r.parent?.let { "\nParent company: $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                if (r.cats != 0) CatChips(r.cats)
                r.cname?.let { Text("DNS CNAME chain: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Recipients.cloakedBy(r.cname)?.let { Text("CNAME cloaking: this name points to $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (r.company != null) TextButton(onClick = { open(Detail.Company(r.company)) }) { Text("Company details →") }
            }
        }
        item {
            InfoCard {
                Text("Where it is", style = MaterialTheme.typography.titleMedium)
                Text("IP: ${r.ip.ifEmpty { "not connected (blocked at DNS)" }}", style = MaterialTheme.typography.bodyMedium)
                Text("Network: ${r.info?.network() ?: "unknown"}", style = MaterialTheme.typography.bodyMedium)
                r.info?.hosting?.let { Text("Hosting / cloud: $it", style = MaterialTheme.typography.bodyMedium) }
                Text("Location: ${r.info?.place() ?: "unknown"}", style = MaterialTheme.typography.bodyMedium)
                Muted("Offline DB-IP Lite data (CC BY 4.0). The server's location, not the company's.", MaterialTheme.typography.bodySmall)
            }
        }
        if (sensHere.isNotEmpty()) item {
            InfoCard { Text("Personal data seen going here (Deep Inspect)", style = MaterialTheme.typography.titleMedium); SensChips(sensHere.fold(0) { a, s -> a or s.sens }) }
        }
        item { Section("Apps that sent data here", sub = "First seen, last seen, bytes") }
        r.rows.forEach { row ->
            item(key = "ra" + row.uid) {
                val meta = remember(row.uid) { Apps.meta(row.uid) }
                val types = sensHere.filter { it.uid == row.uid }.fold(0) { a, s -> a or s.sens }
                Card(Modifier.fillMaxWidth().clickable { open(Detail.App(row.uid)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(meta.pkg, 40.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(meta.label, style = MaterialTheme.typography.titleSmall)
                            Muted("↑ ${fmtBytes(row.up)} ↓ ${fmtBytes(row.down)} · ${row.n}× · first ${fmtAgo(row.firstTime)} · last ${fmtAgo(row.lastTime)}", MaterialTheme.typography.bodySmall)
                            if (types != 0) SensChips(types)
                        }
                    }
                }
            }
        }
    }
}

/** "Actually seen" card for the app / company screens. */
@Composable
fun DeepSeenCard(uid: Int? = null, company: String? = null, open: (Detail) -> Unit) {
    val ctx = LocalContext.current
    if (!CaptureDb.exists(ctx)) return
    val dao = rememberCapDao() ?: return
    val sens by remember(dao) { dao.sensSince(System.currentTimeMillis() - 30 * 86_400_000L) }.collectAsState(emptyList())
    val mine = sens.filter { (uid == null || it.uid == uid) && (company == null || it.company == company) }
    if (mine.isEmpty()) return
    InfoCard {
        Text("Actually seen in decrypted traffic", style = MaterialTheme.typography.titleMedium)
        Muted("Read from this app's real requests by Deep Inspect, not inferred.", MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        SensType.entries.forEach { t ->
            val l = mine.filter { it.sens and t.bit != 0 }
            if (l.isNotEmpty()) Text("• ${t.label}: ${l.size}× to " + l.groupBy { if (company != null) Apps.meta(it.uid).label else it.company ?: it.host }.keys.take(5).joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        }
        if (uid != null) TextButton(onClick = { open(Detail.InspectApp(uid)) }) { Text("See the requests →") }
    }
}
