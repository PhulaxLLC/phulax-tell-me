package com.phonewatch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonewatch.adb.AdbPairingService
import com.phonewatch.adb.AdbShell
import com.phonewatch.debloat.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Setting up the built-in helper (ADB-level access through Wireless debugging). Shown wherever a feature needs it.
 *  Pair once; after that it starts by itself whenever the phone is on WiFi. */
@Composable
fun HelperCard(onReady: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Shell.state(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun start() {
        busy = true; msg = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AdbShell.connect() }
            busy = false; state = Shell.state(ctx)
            msg = if (ok) null else if (!AdbShell.wirelessDebuggingOn) "Wireless debugging is off. Turn it on (step 2), stay on WiFi, then tap Start again."
                else "Couldn't connect. If you haven't paired yet, do step 3 first."
            if (ok) onReady()
        }
    }
    LaunchedEffect(Unit) { if (state != Shell.State.READY && AdbShell.paired) start() }

    if (state == Shell.State.READY) {
        InfoCard {
            Text("Helper is running", style = MaterialTheme.typography.titleMedium)
            Muted("Phone-level access is on. It turns itself back on after a restart whenever you're on WiFi.", MaterialTheme.typography.bodySmall)
        }
        return
    }
    InfoCard(warn = true) {
        Text("Turn on the helper", style = MaterialTheme.typography.titleMedium)
        Muted("Needed for Debloat, the connection history, and what apps read or copied. It's Android's own Wireless debugging: " +
            "nothing leaves the phone. One-time setup, about a minute.", MaterialTheme.typography.bodySmall)
        if (!AdbShell.paired) {
            Text("1. Developer options: Settings > About phone > Software information > tap Build number 7 times.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open About phone") }
            Text("2. Connect to WiFi, then in Developer options turn on Wireless debugging.", style = MaterialTheme.typography.bodySmall)
            Text("3. Tap Pair below, then in Wireless debugging tap \"Pair device with pairing code\". " +
                "Type the 6-digit code into the notification from this app. Tip: open Settings in split screen.", style = MaterialTheme.typography.bodySmall)
            Row {
                Button(onClick = {
                    runCatching { ctx.startForegroundService(AdbPairingService.startIntent(ctx)) }
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Pair") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { start() }, enabled = !busy) { Text(if (busy) "Starting…" else "Start") }
            }
        } else {
            Muted("Paired. Make sure you're on WiFi, then tap Start.", MaterialTheme.typography.bodySmall)
            Row {
                Button(onClick = { start() }, enabled = !busy) { Text(if (busy) "Starting…" else "Start helper") }
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Developer options") }
            }
        }
        msg?.let { Muted(it, MaterialTheme.typography.bodySmall) }
        if (state == Shell.State.NEED_PERMISSION) TextButton(onClick = { Shell.requestPermission() }) { Text("Or allow the Shizuku app instead") }
    }
}
