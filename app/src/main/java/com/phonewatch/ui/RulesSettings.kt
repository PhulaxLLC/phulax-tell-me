package com.phonewatch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phonewatch.data.*
import com.phonewatch.engine.PwVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RulesScreen(open: (Detail) -> Unit) {
    val rules by remember { Repo.db.rules().all() }.collectAsState(emptyList())
    Prefs.state.collectAsState().value
    RuleEngine.version.collectAsState().value
    var newDomain by remember { mutableStateOf("") }
    var askContent by remember { mutableStateOf(false) }
    val globalCats = RuleEngine.globalCats()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Block rules", style = MaterialTheme.typography.headlineMedium) }
        item {
            InfoCard(warn = true) {
                Text("Blocking can break some apps", style = MaterialTheme.typography.titleMedium)
                Text("If an app stops loading, logging in, or showing pictures, open it in the Today tab and turn on \"Never block this app\", or pause blocking from the notification.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            InfoCard {
                SwitchRow("Blocking on", "Off = watch only, nothing is blocked", Prefs.blocking) { Prefs.blocking = it; if (it) Prefs.pausedUntil = 0; Repo.settingsChanged() }
                SwitchRow("Safe mode (recommended)", "Never block Android itself or phone, SMS, emergency and Google Play services apps", Prefs.safeMode) { Prefs.safeMode = it; Repo.settingsChanged() }
            }
        }
        item {
            Section("Block everywhere, by category", sub = "Applies to every app")
        }
        item {
            InfoCard {
                Button(onClick = { Repo.applyRecommended() }, modifier = Modifier.fillMaxWidth()) { Text("Block Ads + Analytics + Fingerprinting") }
                Spacer(Modifier.height(4.dp))
                Cat.entries.filter { it.blockable }.forEach { c ->
                    SwitchRow(c.label, c.blurb, globalCats and c.bit != 0) { on ->
                        if (on && c == Cat.CONTENT) askContent = true else Repo.setRule("category", c.name, null, on)
                    }
                }
            }
        }
        item { Section("Block a website / server everywhere") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newDomain, { newDomain = it.trim().lowercase() }, label = { Text("e.g. doubleclick.net") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (newDomain.contains('.')) { Repo.addRule("domain", newDomain.removePrefix("https://").removePrefix("http://").substringBefore('/')); newDomain = "" } }) { Text("Block") }
            }
        }
        val list = rules.filter { !(it.kind == "category" && it.pkg == null) }
        item { Section("Your other rules", sub = if (list.isEmpty()) "None yet. Add them from an app or company page." else "Tap ✕ to remove") }
        items(list, key = { it.id }) { r ->
            val who = r.pkg?.let { p -> Repo.app.packageManager.runCatching { getApplicationLabel(getApplicationInfo(p, 0)).toString() }.getOrDefault(p) } ?: "all apps"
            val text = when (r.kind) {
                "category" -> "Block ${Cat.byName(r.target)?.label ?: r.target} in $who"
                "company" -> "Block ${r.target} in $who"
                "domain" -> "Block ${r.target} in $who"
                "allowdomain" -> "Always allow ${r.target}"
                "exempt" -> "Never block $who"
                "appblockall" -> "Block all internet for $who"
                "apponlyown" -> "Only own servers for $who"
                else -> r.kind + " " + r.target
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(start = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable(enabled = r.kind == "company") { open(Detail.Company(r.target)) })
                    IconButton(onClick = { Repo.removeRuleId(r.id) }) { Icon(Icons.Default.Close, "Remove rule") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (askContent) AlertDialog(
        onDismissRequest = { askContent = false },
        title = { Text("Block all Content / CDN?") },
        text = { Text("This blocks the servers that deliver pictures, video and app code. Most apps and websites will break. Only do this to test.") },
        confirmButton = { TextButton(onClick = { Repo.addRule("category", Cat.CONTENT.name); askContent = false }) { Text("Block anyway") } },
        dismissButton = { TextButton(onClick = { askContent = false }) { Text("Cancel") } }
    )
}

@Composable
fun SettingsScreen(onTurnOn: () -> Unit) {
    val ctx = LocalContext.current
    val running by PwVpnService.running.collectAsState()
    Prefs.state.collectAsState().value
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium) }
        item {
            InfoCard {
                SwitchRow("Phulax Android Tell Me on", "Turn off to stop all watching and blocking", running) { if (it) onTurnOn() else PwVpnService.stop(ctx) }
                Muted("Phulax Android Tell Me is a local VPN: it cannot run at the same time as another VPN app.", MaterialTheme.typography.bodySmall)
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Android VPN settings (Always-on)") }
            }
        }
        item { Section("Privacy of this app") }
        item {
            InfoCard {
                Text("• Logs stay on this phone and are deleted after 30 days.\n• No analytics, no ads, no account.\n• Phulax Android Tell Me reads only connection details" + (if (com.phonewatch.BuildConfig.PLAY) ". It never reads what's inside your traffic." else ", unless YOU turn on Deep Inspect (Inspect tab) for chosen apps."), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all logs now") }
            }
        }
        item { Section("Tracker lists", sub = TrackerDb.sourceInfo) }
        item {
            InfoCard {
                SwitchRow("Allow updating tracker lists", if (com.phonewatch.BuildConfig.PLAY) "Off by default. When on, the button below downloads a fresh list from Exodus Privacy. Nothing about you is sent." else "Off by default. When on, the button below downloads fresh lists from DuckDuckGo, Disconnect and Exodus Privacy. Nothing about you is sent.", Prefs.allowListUpdates) { Prefs.allowListUpdates = it }
                Button(enabled = Prefs.allowListUpdates && !updating, onClick = {
                    updating = true
                    scope.launch { updateMsg = withContext(Dispatchers.IO) { ListUpdater.update(ctx) }; updating = false }
                }) { Text(if (updating) "Updating…" else "Update tracker lists now") }
                if (Prefs.lastListUpdate > 0) Muted("Last update: ${fmtAgo(Prefs.lastListUpdate)}", MaterialTheme.typography.bodySmall)
                updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { scope.launch(Dispatchers.IO) { ListUpdater.resetToBundled(ctx) }; updateMsg = "Back to the lists built into the app." }) { Text("Use built-in lists") }
            }
        }
        item { Section("About") }
        item {
            InfoCard {
                Text("Phulax Android Tell Me ${com.phonewatch.BuildConfig.VERSION_NAME} by Phulax LLC", style = MaterialTheme.typography.titleMedium)
                Text(if (com.phonewatch.BuildConfig.PLAY) "Free software under GPL-3.0 (source: see the store listing). Packet engine from NetGuard by Marcel Bokhorst (GPL-3.0). Tracker data: Exodus Privacy (ODbL). Debloat ratings: Universal Android Debloater Next Generation (GPL-3.0). IP Geolocation by DB-IP (db-ip.com, CC BY 4.0). Helper adapted from Shizuku (Apache-2.0)." else "Packet engine from NetGuard by Marcel Bokhorst (GPL-3.0). Tracker data: DuckDuckGo, Disconnect (CC BY-NC-SA 4.0), Exodus Privacy (ODbL). Debloat ratings: Universal Android Debloater Next Generation (GPL-3.0). IP Geolocation by DB-IP (db-ip.com, CC BY 4.0). Deep Inspect uses Bouncy Castle (MIT), SQLCipher (BSD), Twitter HPACK (Apache-2.0), Brotli (MIT).", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showLicenses = true }) { Text("Licenses and credits") }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Delete all logs?") },
        confirmButton = { TextButton(onClick = { Repo.clearLogs(); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    if (showLicenses) {
        val text = remember { runCatching { ctx.assets.open("LICENSES.md").bufferedReader().readText() }.getOrDefault("See LICENSES.md") }
        AlertDialog(onDismissRequest = { showLicenses = false }, confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
            title = { Text("Licenses") },
            text = { LazyColumn { item { Text(text, style = MaterialTheme.typography.bodySmall) } } })
    }
}
