package com.phonewatch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonewatch.data.Prefs
import com.phonewatch.data.Removal
import com.phonewatch.data.Repo
import com.phonewatch.debloat.PackageLister
import com.phonewatch.debloat.Pkg
import com.phonewatch.debloat.Safety
import com.phonewatch.debloat.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private enum class DFilter(val label: String) { SYSTEM("System apps"), RECOMMENDED("Safe to remove"), ALL("All apps"), REMOVED("Removed / disabled") }

fun ratingColor(r: String?): Color = when (r) {
    "Recommended" -> Color(0xFF81C784)
    "Advanced" -> Color(0xFFFFD54F)
    "Expert" -> Color(0xFFFFB74D)
    "Unsafe" -> Color(0xFFE57373)
    else -> Color(0xFF9E9E9E)
}

@Composable
fun DebloatScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Prefs.state.collectAsState().value
    var shell by remember { mutableStateOf(Shell.state(ctx)) }
    var pkgs by remember { mutableStateOf<List<Pkg>?>(null) }
    var companies by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var filter by remember { mutableStateOf(DFilter.SYSTEM) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var detail by remember { mutableStateOf<Pkg?>(null) }
    var review by remember { mutableStateOf<List<Pkg>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expertTaps by remember { mutableIntStateOf(0) }
    var expertAsk by remember { mutableStateOf(false) }
    val history by remember { Repo.db.removals().all() }.collectAsState(emptyList())
    val expert = Prefs.expertDebloat

    fun reload() = scope.launch {
        shell = Shell.state(ctx)
        val c = withContext(Dispatchers.IO) { Repo.db.conn().companiesPerPkg(Repo.startOfToday()).associate { it.pkg to it.n } }
        companies = c
        pkgs = withContext(Dispatchers.IO) { PackageLister.list(ctx, c) }
    }
    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, _ -> shell = Shell.state(ctx) }
        val b = Shizuku.OnBinderReceivedListener { shell = Shell.state(ctx) }
        runCatching { Shizuku.addRequestPermissionResultListener(l); Shizuku.addBinderReceivedListenerSticky(b) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(l); Shizuku.removeBinderReceivedListener(b) } }
    }

    fun act(list: List<Pkg>, action: String) {
        if (shell != Shell.State.READY) { message = "The helper isn't running yet (see the card at the top). Or use the adb commands (tap an app)."; return }
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val file = if (action != "restore" && list.size > 1) PackageLister.exportList(ctx, list, action) else null
                var ok = 0; val fails = mutableListOf<String>()
                for (p in list) {
                    if (action != "restore" && !Safety.allowed(p, Prefs.expertDebloat)) { fails += "${p.label} (protected)"; continue }
                    val r = when (action) { "uninstall" -> Shell.uninstall(p.pkg); "disable" -> Shell.disable(p.pkg); else -> Shell.restore(p.pkg) }
                    val success = r.code == 0 && !r.out.contains("Failure", true) && !r.out.contains("Exception", true)
                    if (success) {
                        ok++
                        if (action == "restore") Repo.db.removals().markRestored(p.pkg)
                        else Repo.db.removals().insert(Removal(pkg = p.pkg, label = p.label, method = action, time = System.currentTimeMillis()))
                    } else fails += "${p.label}: ${r.out.take(80)}"
                }
                val verb = when (action) { "uninstall" -> "Removed"; "disable" -> "Disabled"; else -> "Restored" }
                "$verb $ok of ${list.size}." + (file?.let { " List saved to $it." } ?: "") + (if (fails.isNotEmpty()) "\nProblems: " + fails.joinToString("; ") else "")
            }
            message = res; busy = false; selected.clear(); reload()
        }
    }

    val all = pkgs.orEmpty()
    val shown = all.filter { p ->
        (query.isBlank() || p.label.contains(query, true) || p.pkg.contains(query, true)) && when (filter) {
            DFilter.SYSTEM -> p.installed && (p.system || p.uad != null)
            DFilter.RECOMMENDED -> p.installed && p.uad?.removal == "Recommended" && p.hardBlocked == null
            DFilter.ALL -> p.installed
            DFilter.REMOVED -> !p.installed || !p.enabled
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Debloat", style = MaterialTheme.typography.headlineMedium) }
        item { HelperCard(onReady = { shell = Shell.state(ctx); reload() }) }
        item {
            InfoCard(warn = true) {
                Text("Removes apps for your user only", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable {
                    expertTaps++; if (expertTaps >= 7 && !expert) { expertAsk = true; expertTaps = 0 }
                })
                Text("Nothing is deleted from the phone's system partition, so every removal can be restored here. A Samsung firmware update or a factory reset can bring removed apps back.", style = MaterialTheme.typography.bodyMedium)
                Text("Ratings from Universal Android Debloater: Recommended (safe) · Advanced · Expert · Unsafe (locked).", style = MaterialTheme.typography.bodySmall)
                if (expert) {
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("Expert mode", "Unsafe-rated apps can be removed. Core system apps are always locked.", true) { Prefs.expertDebloat = false }
                }
            }
        }
        if (!PackageLister.usageAccess(ctx)) item {
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Show last-used dates + sizes (grant Usage access)") }
        }
        item {
            Button(onClick = {
                review = all.filter { it.installed && it.uad?.removal == "Recommended" && it.hardBlocked == null &&
                        (it.pkg.startsWith("com.samsung") || it.pkg.startsWith("com.sec.") || it.uad.list == "Oem" && it.pkg.contains("samsung")) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Preset: Samsung safe-to-remove (review first)") }
        }
        message?.let { m -> item { InfoCard { Text(m, style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { message = null }) { Text("OK") } } } }
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text(f.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }) }
            }
        }
        if (selected.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { review = all.filter { it.pkg in selected } }) { Text("Remove ${selected.size} selected…") }
                OutlinedButton(onClick = { selected.clear() }) { Text("Clear") }
            }
        }
        if (pkgs == null) item { Muted("Loading apps…") }
        else item { Muted("${shown.size} apps", MaterialTheme.typography.bodySmall) }
        items(shown, key = { it.pkg }) { p ->
            PkgRow(p, companies[p.pkg] ?: 0, p.pkg in selected, Safety.allowed(p, expert) && p.installed,
                onCheck = { if (it) selected.add(p.pkg) else selected.remove(p.pkg) }, onClick = { detail = p })
        }
        item { Section("Removal history", sub = "Everything removed or disabled here, with one-tap restore") }
        val active = history.filter { !it.restored }
        if (active.isNotEmpty()) item {
            Button(enabled = !busy, onClick = { act(active.map { r -> all.firstOrNull { it.pkg == r.pkg } ?: Pkg(r.pkg, r.label, true, false, false, 0, 0, null, null) }, "restore") }) { Text("Restore all (${active.size})") }
        }
        if (history.isEmpty()) item { Muted("Nothing removed yet.") }
        items(history, key = { "h" + it.id }) { r ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.bodyLarge)
                        Muted("${if (r.method == "uninstall") "Removed" else "Disabled"} ${fmtAgo(r.time)}" + if (r.restored) " · restored" else "", MaterialTheme.typography.bodySmall)
                    }
                    if (!r.restored) Button(enabled = !busy, onClick = { act(listOf(all.firstOrNull { it.pkg == r.pkg } ?: Pkg(r.pkg, r.label, true, false, false, 0, 0, null, null)), "restore") }) { Text("Restore") }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    detail?.let { p ->
        val cmds = listOf("adb shell " + Shell.uninstallCmd(p.pkg), "adb shell " + Shell.disableCmd(p.pkg), "adb shell " + Shell.restoreCmd(p.pkg))
        val allowed = Safety.allowed(p, expert)
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(p.label) },
            text = {
                LazyColumn {
                    item {
                        Text(p.pkg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Text("Rating: ${p.uad?.removal ?: "not rated"}" + (p.uad?.list?.let { " ($it)" } ?: ""), color = ratingColor(p.uad?.removal), style = MaterialTheme.typography.titleSmall)
                        p.hardBlocked?.let { Text("Locked: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        if (p.hardBlocked == null && p.uad?.removal == "Unsafe" && !expert) Text("Locked: rated Unsafe.", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(6.dp))
                        Text(p.uad?.description?.takeIf { it.isNotBlank() } ?: "No description in the Universal Android Debloater list. If unsure, leave it.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Muted("Size ${fmtBytes(p.sizeBytes)} · last used ${fmtAgo(p.lastUsed)} · contacted ${companies[p.pkg] ?: 0} companies today", MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Text("Without the helper, run from a computer:", style = MaterialTheme.typography.titleSmall)
                        cmds.forEach { c -> Text(c, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) }
                        TextButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", cmds.joinToString("\n")))
                            message = "adb commands copied"
                        }) { Text("Copy adb commands") }
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (!p.installed || !p.enabled) TextButton(enabled = !busy, onClick = { act(listOf(p), "restore"); detail = null }) { Text("Restore") }
                    else {
                        TextButton(enabled = allowed && !busy, onClick = { act(listOf(p), "uninstall"); detail = null }) { Text("Remove (restorable)") }
                        TextButton(enabled = allowed && !busy, onClick = { act(listOf(p), "disable"); detail = null }) { Text("Disable only") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } }
        )
    }

    review?.let { list ->
        val ok = list.filter { Safety.allowed(it, expert) }
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text("Review ${ok.size} apps to remove") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { Text("A list with restore commands is saved to Downloads first. You can restore any of them from Removal history.", style = MaterialTheme.typography.bodyMedium) }
                    if (ok.size < list.size) item { Text("${list.size - ok.size} locked apps were left out.", color = MaterialTheme.colorScheme.error) }
                    items(ok, key = { "rv" + it.pkg }) { p ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(p.label, style = MaterialTheme.typography.bodyLarge)
                            Text(p.pkg + " · " + (p.uad?.removal ?: "unrated"), style = MaterialTheme.typography.bodySmall, color = ratingColor(p.uad?.removal))
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = ok.isNotEmpty() && !busy, onClick = { act(ok, "uninstall"); review = null }) { Text("Remove ${ok.size}") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Cancel") } }
        )
    }

    if (expertAsk) AlertDialog(
        onDismissRequest = { expertAsk = false },
        title = { Text("Turn on expert mode?") },
        text = { Text("Expert mode lets you remove apps rated UNSAFE. Removing the wrong one can break calls, the camera, updates, or make the phone restart in a loop until you factory reset it. Core system, phone, Settings, Play services and Knox stay locked no matter what. Only continue if you know exactly what each app does.") },
        confirmButton = { TextButton(onClick = { Prefs.expertDebloat = true; expertAsk = false }) { Text("I understand, turn on") } },
        dismissButton = { TextButton(onClick = { expertAsk = false }) { Text("Cancel") } }
    )
}

@Composable
private fun ShizukuCard(state: Shell.State, onPermission: () -> Unit, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    InfoCard(warn = state != Shell.State.READY) {
        Text(
            when (state) {
                Shell.State.READY -> "Shizuku connected: ready"
                Shell.State.NEED_PERMISSION -> "Shizuku running: allow Phulax Android Tell Me"
                Shell.State.NOT_RUNNING -> "Shizuku installed but not started"
                Shell.State.NOT_INSTALLED -> "Shizuku not installed"
            }, style = MaterialTheme.typography.titleMedium
        )
        Text(
            when (state) {
                Shell.State.READY -> "Phulax Android Tell Me can remove, disable and restore apps."
                Shell.State.NEED_PERMISSION -> "Tap the button and choose Allow."
                Shell.State.NOT_RUNNING -> "Open Shizuku > Start via Wireless debugging (see INSTALL.md). It needs starting again after each reboot."
                Shell.State.NOT_INSTALLED -> "Install Shizuku (free) from the Play Store, or use the adb commands shown for each app."
            }, style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            when (state) {
                Shell.State.NEED_PERMISSION -> Button(onClick = onPermission) { Text("Allow") }
                Shell.State.NOT_INSTALLED -> Button(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))) }
                }) { Text("Get Shizuku") }
                Shell.State.NOT_RUNNING -> Button(onClick = {
                    runCatching { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")) }
                }) { Text("Open Shizuku") }
                else -> {}
            }
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun PkgRow(p: Pkg, companiesToday: Int, checked: Boolean, canSelect: Boolean, onCheck: (Boolean) -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck, enabled = canSelect)
            AppIcon(p.pkg, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val r = if (p.hardBlocked != null) "Locked" else p.uad?.removal ?: "Unrated"
                    Text(r, color = if (p.hardBlocked != null) MaterialTheme.colorScheme.error else ratingColor(p.uad?.removal), style = MaterialTheme.typography.labelMedium)
                    Text(fmtBytes(p.sizeBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.lastUsed > 0) Text("used ${fmtAgo(p.lastUsed)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!p.installed) Text("REMOVED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    else if (!p.enabled) Text("DISABLED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                if (companiesToday > 0) Text("Contacted $companiesToday ${if (companiesToday == 1) "company" else "companies"} today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
