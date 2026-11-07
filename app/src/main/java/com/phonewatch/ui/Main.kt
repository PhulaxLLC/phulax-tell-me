package com.phonewatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phonewatch.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow

/** inBar = shown in the bottom bar on the narrow (cover) screen; the others open from More there. The inner screen's rail shows all. */
/** The Play edition has no Deep Inspect. */
val shownTabs get() = Tab.entries.filter { it != Tab.INSPECT || !com.phonewatch.BuildConfig.PLAY }

enum class Tab(val label: String, val icon: ImageVector, val about: String, val inBar: Boolean = true) {
    HOME("Home", Icons.Default.Dashboard, "Today's summary"),
    LIVE("Apps", Icons.Default.Sensors, "What your apps are sending right now"),
    SCAN("Nearby", Icons.Default.Radar, "Trackers, cameras, Bluetooth and WiFi around you"),
    RULES("Block", Icons.Default.Block, "Block apps and companies"),
    DEBLOAT("Debloat", Icons.Default.DeleteSweep, "Turn off or remove built-in apps", inBar = false),
    INSPECT("Inspect", Icons.Default.Policy, "Read what chosen apps send (advanced)", inBar = false),
    MORE("More", Icons.Default.Settings, "Settings"),
}

sealed class Detail {
    data class App(val uid: Int) : Detail()
    data class Company(val name: String) : Detail()
    data object Recipients : Detail()
    data class Recipient(val key: String) : Detail()
    data class InspectApp(val uid: Int) : Detail()
    data class Request(val id: Long) : Detail()
}

enum class Range(val label: String, val days: Int) { TODAY("Today", 0), WEEK("7 days", 7), MONTH("30 days", 30);
    fun since(): Long = if (days == 0) Repo.startOfToday() else System.currentTimeMillis() - days * 86_400_000L
}

object UiState { val range = MutableStateFlow(Range.TODAY) }

/** Fold aware: two panes (list + detail) on the inner screen, one pane on the cover screen. */
@Composable
fun MainUi(onTurnOn: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val stack = remember { mutableStateListOf<Detail>() }
    val open: (Detail) -> Unit = { stack.add(it) }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val twoPane = wide && (tab == Tab.HOME || tab == Tab.LIVE || tab == Tab.INSPECT)
        if (wide) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    shownTabs.forEach { t ->
                        NavigationRailItem(selected = tab == t, onClick = { tab = t; stack.clear() },
                            icon = { Icon(t.icon, t.label) }, label = { Text(t.label, style = MaterialTheme.typography.labelMedium) })
                    }
                }
                if (twoPane) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(tab, open, onTurnOn) }
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val d = stack.lastOrNull()
                        if (d == null) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Muted("Tap an app or a company to see what it sends and where.")
                        } else DetailPane(d, open, back = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null)
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val d = stack.lastOrNull()
                        if (d != null) DetailPane(d, open, back = { stack.removeAt(stack.lastIndex) }) else TabContent(tab, open, onTurnOn)
                    }
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        shownTabs.filter { it.inBar }.forEach { t ->
                            NavigationBarItem(selected = tab == t || (t == Tab.MORE && !tab.inBar), onClick = { tab = t; stack.clear() },
                                icon = { Icon(t.icon, t.label) }, label = { Text(t.label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).consumeWindowInsets(pad).statusBarsPadding()) {
                    val d = stack.lastOrNull()
                    if (d != null) DetailPane(d, open, back = { stack.removeAt(stack.lastIndex) })
                    else if (tab == Tab.MORE) Column {
                        // narrow screen: the tools that don't fit in the bottom bar, at the top of More
                        shownTabs.filter { !it.inBar }.forEach { t ->
                            ListItem(headlineContent = { Text(t.label) }, supportingContent = { Text(t.about) },
                                leadingContent = { Icon(t.icon, null) }, modifier = Modifier.clickable { tab = t })
                        }
                        HorizontalDivider()
                        Box(Modifier.weight(1f)) { TabContent(tab, open, onTurnOn) }
                    } else Column {
                        if (!tab.inBar) TextButton(onClick = { tab = Tab.MORE }, modifier = Modifier.padding(start = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("More")
                        }
                        Box(Modifier.weight(1f)) { TabContent(tab, open, onTurnOn) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TabContent(tab: Tab, open: (Detail) -> Unit, onTurnOn: () -> Unit) {
    when (tab) {
        Tab.HOME -> DashboardScreen(open, onTurnOn)
        Tab.LIVE -> LiveScreen(open)
        Tab.SCAN -> ScanScreen()
        Tab.RULES -> RulesScreen(open)
        Tab.DEBLOAT -> DebloatScreen()
        Tab.INSPECT -> InspectScreen(open)
        Tab.MORE -> SettingsScreen(onTurnOn)
    }
}

@Composable
private fun DetailPane(d: Detail, open: (Detail) -> Unit, back: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        if (back != null) TextButton(onClick = back, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Back")
        }
        when (d) {
            is Detail.App -> AppDetailScreen(d.uid, open)
            is Detail.Company -> CompanyDetailScreen(d.name, open)
            is Detail.Recipients -> RecipientsScreen(open)
            is Detail.Recipient -> RecipientDetailScreen(d.key, open)
            is Detail.InspectApp -> InspectAppScreen(d.uid, open)
            is Detail.Request -> RequestScreen(d.id)
        }
    }
}
