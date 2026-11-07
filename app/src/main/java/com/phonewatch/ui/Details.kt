package com.phonewatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonewatch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(uid: Int, open: (Detail) -> Unit) {
    val meta = remember(uid) { Apps.meta(uid) }
    val range by UiState.range.collectAsState()
    val since = remember(range) { range.since() }
    val hosts by remember(uid, since) { Repo.db.conn().hostsForApp(uid, since) }.collectAsState(emptyList())
    RuleEngine.version.collectAsState().value
    var perms by remember(meta.pkg) { mutableStateOf<List<Apps.PermData>>(emptyList()) }
    LaunchedEffect(meta.pkg) { perms = withContext(Dispatchers.IO) { Apps.grantedData(meta.pkg) } }

    val byCompany = remember(hosts) { hosts.groupBy { it.company ?: "Unknown company" }.toList().sortedByDescending { p -> p.second.sumOf { it.n } } }
    val catsSeen = remember(hosts) { hosts.fold(0) { a, h -> a or h.cats } }
    val signals = remember(hosts) {
        hosts.mapNotNull { it.company }.distinct().mapNotNull { TrackerDb.companies[it] }.flatMap { it.signals }.toSortedSet()
    }
    val protected = remember(uid) { Apps.isProtected(uid) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(meta.pkg, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(meta.label, style = MaterialTheme.typography.headlineSmall)
                    Muted(meta.pkg + if (meta.system) " · system app" else "", MaterialTheme.typography.bodySmall)
                }
                TellMeButton(uid)
            }
        }
        item { RangeChips() }
        item {
            val up = hosts.sumOf { it.up }; val down = hosts.sumOf { it.down }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("companies", byCompany.size.toString(), Modifier.weight(1f))
                    Stat("blocked", hosts.sumOf { it.blocked }.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("sent ↑", fmtBytes(up), Modifier.weight(1f))
                    Stat("received ↓", fmtBytes(down), Modifier.weight(1f))
                }
            }
        }

        item { DeepSeenCard(uid = uid, open = open) }
        item { Section("What data it may be sending") }
        item { Inferred() }
        item {
            InfoCard {
                Text("Allowed by its permissions", style = MaterialTheme.typography.titleMedium)
                if (perms.isEmpty()) Muted("No sensitive permissions granted.")
                perms.forEach { Text("• ${it.label}: ${it.detail}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (catsSeen != 0) item {
            InfoCard {
                Text("Kinds of servers it talked to", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp)); CatChips(catsSeen)
                Cat.of(catsSeen).filter { it.bit and Cat.TRACKER_MASK != 0 }.forEach {
                    Text("• ${it.label}: ${it.blurb}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (signals.isNotEmpty()) item {
            InfoCard {
                Text("Documented by DuckDuckGo research for these trackers", style = MaterialTheme.typography.titleMedium)
                Muted("Data these companies' SDKs have been seen collecting in apps:", MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    signals.forEach { AssistChip(onClick = {}, label = { Text(TrackerDb.signalLabel(it)) }) }
                }
            }
        }

        item { Section("Block for this app only", sub = if (protected && Prefs.safeMode) "Safe mode: this is a phone/SMS/emergency/system app and is never blocked (turn Safe mode off in the Block tab to override)" else "These switches only affect ${meta.label}") }
        item {
            val exempt = RuleEngine.exempt(meta.pkg)
            InfoCard(warn = RuleEngine.blockAll(meta.pkg) && !exempt) {
                SwitchRow("Never block this app", if (exempt) "ON: every rule below is ignored for this app. Turn this off to use them." else "Exempt it from every rule (use if it breaks)", exempt) {
                    Repo.setRule("exempt", meta.pkg, meta.pkg, it)
                }
                HorizontalDivider()
                SwitchRow("Block all internet for this app", "Nothing this app sends gets out", RuleEngine.blockAll(meta.pkg), enabled = !exempt) {
                    Repo.setRule("appblockall", meta.pkg, meta.pkg, it)
                }
                SwitchRow("Allow only its own servers", "Blocks every other company this app talks to (ads, analytics, CDNs...). Can break pictures or logins.", RuleEngine.onlyOwn(meta.pkg), enabled = !exempt) {
                    Repo.setRule("apponlyown", meta.pkg, meta.pkg, it)
                }
                HorizontalDivider()
                val appCats = RuleEngine.appCats(meta.pkg); val global = RuleEngine.globalCats()
                Cat.entries.filter { it.blockable }.forEach { c ->
                    val on = appCats and c.bit != 0
                    SwitchRow("Block ${c.label}", if (global and c.bit != 0) "Already blocked for all apps" else null, on || global and c.bit != 0, enabled = !exempt && global and c.bit == 0) {
                        Repo.setRule("category", c.name, meta.pkg, it)
                    }
                }
            }
        }

        item { Section("Companies this app talks to", sub = "Switch on to block that company for this app only") }
        if (byCompany.isEmpty()) item { Muted("No connections recorded in this time range.") }
        byCompany.forEach { (company, rows) ->
            item(key = "co-$company") {
                val known = company != "Unknown company"
                val forApp = known && RuleEngine.companyBlockedFor(meta.pkg, company)
                val everywhere = known && RuleEngine.companyBlocked(company)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = known) { open(Detail.Company(company)) }) {
                            Text(company, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("${rows.size} servers · ${rows.sumOf { it.n }}×" + if (everywhere) " · blocked in every app" else "", MaterialTheme.typography.bodySmall)
                            CatChips(rows.fold(0) { a, r -> a or r.cats }, Modifier.padding(top = 4.dp))
                        }
                        if (known) Switch(checked = forApp || everywhere, enabled = !everywhere && !RuleEngine.exempt(meta.pkg), onCheckedChange = { Repo.setRule("company", company, meta.pkg, it) })
                    }
                }
            }
        }

        item { Section("Recipients (domains)", sub = "Every server it sent data to. Switch on to block that domain for this app only; tap a row for more options.") }
        if (hosts.isEmpty()) item { Muted("No connections recorded in this time range.") }
        hosts.forEach { h -> item(key = "h-" + (h.host ?: h.ip)) { HostLine(h, showCompany = true) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun HostLine(h: HostRow, showCompany: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val d = h.host ?: h.ip
    val global = h.host != null && RuleEngine.domainBlocked(d)
    val forApp = h.host != null && RuleEngine.domainBlockedFor(h.pkg, d)
    Row(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (h.blocked > 0) "⛔ " else "") + d, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted((if (showCompany) (h.company ?: "unknown company") + " · " else "") +
                "${h.n}× · ↑ ${fmtBytes(h.up)} ↓ ${fmtBytes(h.down)} · ${fmtAgo(h.lastTime)}" + (if (h.ip.isNotEmpty() && h.host != null) " · ${h.ip}" else ""), MaterialTheme.typography.bodySmall)
        }
        if (h.host != null) Switch(checked = forApp || global, enabled = !global && !RuleEngine.exempt(h.pkg), onCheckedChange = { Repo.setRule("domain", d, h.pkg, it) })
        DropdownMenu(menu, { menu = false }) {
            if (h.host != null) {
                DropdownMenuItem(text = { Text(if (forApp) "Unblock $d for this app" else "Block $d for this app") }, onClick = { Repo.setRule("domain", d, h.pkg, !forApp); menu = false })
                DropdownMenuItem(text = { Text(if (global) "Unblock $d everywhere" else "Block $d in every app") }, onClick = { Repo.setRule("domain", d, null, !global); menu = false })
                DropdownMenuItem(text = { Text("Always allow $d") }, onClick = { Repo.addRule("allowdomain", d); menu = false })
            } else DropdownMenuItem(text = { Text("No server name known for this IP, so it can only be blocked with \"Block all internet\"") }, onClick = { menu = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanyDetailScreen(name: String, open: (Detail) -> Unit) {
    val range by UiState.range.collectAsState()
    val since = remember(range) { range.since() }
    val rows by remember(name, since) { Repo.db.conn().hostsForCompany(name, since) }.collectAsState(emptyList())
    RuleEngine.version.collectAsState().value
    val co = TrackerDb.companies[name]
    val byApp = remember(rows) { rows.groupBy { it.uid }.toList().sortedByDescending { p -> p.second.sumOf { it.n } } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text(name, style = MaterialTheme.typography.headlineSmall) }
        co?.legal?.let { if (it != name) item { Muted("Owner: $it") } }
        item { CatChips((co?.cats ?: 0) or rows.fold(0) { a, r -> a or r.cats }) }
        item { DeepSeenCard(company = name, open = open) }
        item {
            InfoCard(warn = RuleEngine.companyBlocked(name)) {
                SwitchRow("Block $name in every app", "Stops all apps reaching this company's servers (DNS + connection blocking)", RuleEngine.companyBlocked(name)) {
                    Repo.setRule("company", name, null, it)
                }
                Muted("Blocking can break apps that need this company (for example logins or maps).", MaterialTheme.typography.bodySmall)
            }
        }
        co?.about?.let { item { InfoCard { Text("About", style = MaterialTheme.typography.titleMedium); Text(it, style = MaterialTheme.typography.bodyMedium); Muted("Source: ${co.aboutSource ?: "Exodus Privacy"}", MaterialTheme.typography.bodySmall) } } }
        if (co != null && co.signals.isNotEmpty()) item {
            InfoCard {
                Text("Data its SDKs are documented to collect", style = MaterialTheme.typography.titleMedium)
                Muted("Likely / inferred: from DuckDuckGo's App Tracking Protection research, not read from your traffic.", MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    co.signals.forEach { AssistChip(onClick = {}, label = { Text(TrackerDb.signalLabel(it)) }) }
                }
            }
        }
        if (co != null && co.services.isNotEmpty()) item {
            InfoCard {
                Text("Known services / SDKs", style = MaterialTheme.typography.titleMedium)
                Text(co.services.take(25).joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Section("Apps that sent data here", sub = range.label) }
        if (byApp.isEmpty()) item { Muted("No app contacted $name in this time range.") }
        byApp.forEach { (uid, list) ->
            item(key = "app-$uid") {
                val meta = remember(uid) { Apps.meta(uid) }
                InfoCard {
                    Row(Modifier.clickable { open(Detail.App(uid)) }, verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(meta.pkg, 40.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(meta.label, style = MaterialTheme.typography.titleMedium)
                            Muted("${list.sumOf { it.n }} connections", MaterialTheme.typography.bodySmall)
                        }
                    }
                    list.forEach { HostLine(it) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
