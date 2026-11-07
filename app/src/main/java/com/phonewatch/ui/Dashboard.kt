package com.phonewatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonewatch.data.*
import com.phonewatch.engine.PwVpnService

data class CompanyAgg(val name: String, val cats: Int, val n: Int, val blocked: Int, val apps: Int, val bytes: Long)

fun mergeCompanies(rows: List<CompanyRow>): List<CompanyAgg> =
    rows.groupBy { it.company }.map { (name, l) ->
        CompanyAgg(name, l.fold(0) { a, r -> a or r.cats }, l.sumOf { it.n }, l.sumOf { it.blocked }, l.maxOf { it.apps }, l.sumOf { it.bytes })
    }.sortedByDescending { it.n }

@Composable
fun StatusCard(onTurnOn: () -> Unit) {
    val ctx = LocalContext.current
    val running by PwVpnService.running.collectAsState()
    Prefs.state.collectAsState().value // recompose on pref changes
    val blocking = Prefs.blockingActive()
    InfoCard(warn = !running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when { !running -> "Phulax Android Tell Me is OFF"; !Prefs.blocking -> "Watching (blocking off)"; !blocking -> "Watching (blocking paused)"; else -> "Watching + blocking" },
                    style = MaterialTheme.typography.titleLarge
                )
                Muted(if (running) "All app traffic is checked on this phone. Nothing is sent to us." else "Turn on to see which apps send data and where.")
            }
            Switch(checked = running, onCheckedChange = { if (it) onTurnOn() else PwVpnService.stop(ctx) })
        }
        if (running) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (blocking) OutlinedButton(onClick = { Prefs.pausedUntil = System.currentTimeMillis() + 15 * 60_000; Repo.settingsChanged() }) { Text("Pause blocking 15 min") }
            else Button(onClick = { Prefs.pausedUntil = 0; Prefs.blocking = true; Repo.settingsChanged() }) { Text("Resume blocking") }
        }
    }
}

@Composable
fun RangeChips() {
    val range by UiState.range.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Range.entries.forEach { r ->
            FilterChip(selected = r == range, onClick = { UiState.range.value = r }, label = { Text(r.label, style = MaterialTheme.typography.labelLarge) })
        }
    }
}

@Composable
fun DashboardScreen(open: (Detail) -> Unit, onTurnOn: () -> Unit) {
    val range by UiState.range.collectAsState()
    val since = remember(range) { range.since() }
    val dao = Repo.db.conn()
    val totals by remember(since) { dao.totals(since) }.collectAsState(Totals(0, 0, 0, 0, 0, 0))
    val apps by remember(since) { dao.apps(since) }.collectAsState(emptyList())
    val compRows by remember(since) { dao.companies(since) }.collectAsState(emptyList())
    val companies = remember(compRows) { mergeCompanies(compRows) }
    val recent by remember { dao.recent(5) }.collectAsState(emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Phulax Android Tell Me", style = MaterialTheme.typography.headlineMedium) }
        item { StatusCard(onTurnOn) }
        item { RangeChips() }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("apps", totals.apps.toString(), Modifier.weight(1f))
                Stat("companies", totals.companies.toString(), Modifier.weight(1f))
                Stat("blocked", totals.blocked.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("sent ↑", fmtBytes(totals.up), Modifier.weight(1f))
                Stat("received ↓", fmtBytes(totals.down), Modifier.weight(1f))
            }
        }
        item {
            OutlinedButton(onClick = { open(Detail.Recipients) }, modifier = Modifier.fillMaxWidth()) { Text("Recipients: every server your apps send to →") }
        }
        if (recent.isNotEmpty()) {
            item { Section("Live feed", sub = "Most recent connections (full list in the Apps tab)") }
            items(recent, key = { "r" + it.id }) { FeedRow(it, open) }
        }
        item { Section("Apps sending data", sub = "Tap an app to see where its data goes") }
        if (apps.isEmpty()) item { Muted("Nothing yet. Turn Phulax Android Tell Me on and use your phone for a minute.") }
        items(apps, key = { "a" + it.uid }) { a -> AppRow(a) { open(Detail.App(a.uid)) } }
        item { Section("Top companies", sub = "Who receives data from your apps") }
        items(companies.take(40), key = { "c" + it.name }) { c -> CompanyRowView(c) { open(Detail.Company(c.name)) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AppRow(a: AppSummary, onClick: () -> Unit) {
    val meta = remember(a.uid) { Apps.meta(a.uid) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(meta.pkg, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${a.companies} companies · ${a.hosts} servers · ${a.n} connections", MaterialTheme.typography.bodySmall)
                Muted("↑ ${fmtBytes(a.up)}  ↓ ${fmtBytes(a.down)}" + if (a.blocked > 0) "  ·  ${a.blocked} blocked" else "", MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            TellMeButton(a.uid)
        }
    }
}

@Composable
fun CompanyRowView(c: CompanyAgg, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (RuleEngine.companyBlocked(c.name)) Text("BLOCKED", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
            Muted("${c.apps} apps · ${c.n} connections" + if (c.blocked > 0) " · ${c.blocked} blocked" else "", MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            CatChips(c.cats)
        }
    }
}

@Composable
fun FeedRow(c: Conn, open: (Detail) -> Unit, onInfo: ((Conn) -> Unit)? = null) {
    val meta = remember(c.uid) { Apps.meta(c.uid) }
    val ctx = LocalContext.current
    val place = remember(c.ip) { IpDb.lookup(ctx, c.ip)?.place() }
    Row(
        Modifier.fillMaxWidth().clickable { if (onInfo != null) onInfo(c) else open(Detail.App(c.uid)) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(meta.pkg, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (c.blocked) "⛔ " else "") + (c.host ?: c.ip),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = if (c.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Muted(
                "${meta.label} → ${c.company ?: "unknown company"} · ${Cat.of(c.cats).firstOrNull()?.label ?: ""}" +
                    (place?.takeIf { it != "unknown" }?.let { " · $it" } ?: "") + " · ${fmtTime(c.time)}",
                MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        TellMeButton(c.uid)
    }
}

@Composable
fun LiveScreen(open: (Detail) -> Unit) {
    val ctx = LocalContext.current
    val rows by remember { Repo.db.conn().recent(150) }.collectAsState(emptyList())
    var onlyBlocked by remember { mutableStateOf(false) }
    var onlyTrackers by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<Conn?>(null) }
    // search covers the whole history (up to the last 20,000 connections), not just the live 150
    val found by produceState<List<Conn>?>(null, query) {
        value = if (query.trim().length < 2) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(250)
            Repo.db.conn().recentList(20_000).filter { connMatches(ctx, it, query) }.take(500)
        }
    }
    val shown = (found ?: rows).filter { (!onlyBlocked || it.blocked) && (!onlyTrackers || it.cats and Cat.TRACKER_MASK != 0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text("Live feed", style = MaterialTheme.typography.headlineMedium) }
        item { Muted("Every connection as it happens. Server names come from DNS lookups and TLS server names (SNI); content stays encrypted and unread.") }
        item {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Search: server, IP, app, company, country, city, network") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
            if (found != null) Muted("${found!!.size}${if (found!!.size == 500) "+" else ""} matches in your history. Tap one for where it goes and what it is.",
                MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(onlyTrackers, { onlyTrackers = !onlyTrackers }, label = { Text("Trackers only") })
                FilterChip(onlyBlocked, { onlyBlocked = !onlyBlocked }, label = { Text("Blocked only") })
            }
        }
        if (shown.isEmpty()) item { Muted(if (found != null) "Nothing matches." else "No connections yet.") }
        items(shown, key = { it.id }) { c ->
            FeedRow(c, open, onInfo = { info = it })
            HorizontalDivider()
        }
    }
    info?.let { c -> ConnInfoDialog(c, onDismiss = { info = null }, onSearch = { query = it; info = null }, open = open) }
}

