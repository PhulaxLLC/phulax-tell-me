package com.phonewatch.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phonewatch.data.*

/** True when a connection matches the search text on any field a person would look for: server, IP, app, company,
 *  country / region / city, network owner (AS number or name) or cloud provider. */
fun connMatches(ctx: Context, c: Conn, q: String): Boolean {
    val t = q.trim().lowercase()
    if (t.isEmpty()) return true
    if (listOfNotNull(c.host, c.ip, c.pkg, c.company, c.cname, Apps.meta(c.uid).label).any { it.lowercase().contains(t) }) return true
    if (Cat.of(c.cats).any { it.label.lowercase().contains(t) }) return true
    val ip = IpDb.lookup(ctx, c.ip) ?: return false
    val countryName = ip.country?.let { java.util.Locale("", it).getDisplayCountry(java.util.Locale.US) }
    return listOfNotNull(ip.country?.takeIf { t.length == 2 }, countryName, ip.region, ip.city, ip.asOrg, ip.hosting, ip.asn?.let { "as$it" })
        .any { it.lowercase().contains(t) }
}

private fun proto(p: Int) = when (p) { 6 -> "TCP"; 17 -> "UDP"; 1, 58 -> "ICMP"; else -> "protocol $p" }
private fun portName(p: Int) = when (p) {
    443 -> "HTTPS (encrypted web)"; 80 -> "HTTP (unencrypted web)"; 53 -> "DNS (name lookup)"; 853 -> "DNS over TLS"
    5228, 5229, 5230 -> "Google push messages"; 123 -> "time sync (NTP)"; 3478, 3479 -> "voice/video call relay (STUN/TURN)"
    993 -> "email (IMAP)"; 587, 465 -> "email sending (SMTP)"; 5222, 5223 -> "chat / push (XMPP, Apple push)"; 8883, 1883 -> "smart-device messages (MQTT)"
    else -> null
}

/** Everything known about one connection: where the server is, who runs it, what it's for. */
@Composable
fun ConnInfoDialog(c: Conn, onDismiss: () -> Unit, onSearch: (String) -> Unit, open: (Detail) -> Unit) {
    val ctx = LocalContext.current
    val meta = remember(c.uid) { Apps.meta(c.uid) }
    val ip = remember(c.ip) { IpDb.lookup(ctx, c.ip) }
    val co = c.company?.let { TrackerDb.companies[it] }
    val cats = Cat.of(c.cats)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(c.host ?: c.ip) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text("Where it goes", style = MaterialTheme.typography.titleSmall) }
                item { Text("Location: ${ip?.place() ?: "unknown"}" + (ip?.country?.let { " (" + java.util.Locale("", it).getDisplayCountry(java.util.Locale.US) + ")" } ?: "")) }
                item { Text("Network: ${ip?.network() ?: "unknown"}") }
                ip?.hosting?.let { item { Text("Hosted on: $it") } }
                item { Text("Address ${c.ip}, port ${c.port} ${proto(c.proto)}" + (portName(c.port)?.let { " · $it" } ?: ""), fontFamily = FontFamily.Monospace) }
                item {
                    Muted(when (c.via) {
                        "dns" -> "The name came from the app's DNS lookup."
                        "sni" -> "The name came from the encrypted connection's server name (SNI)."
                        else -> "Only the IP address is known (no name was looked up)."
                    } + " Location is where the network owner registered the address. Big clouds and CDNs serve from many places, so it can be off.",
                        MaterialTheme.typography.bodySmall)
                }

                item { Text("What it is", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                item { Text("Company: ${c.company ?: "not in the bundled lists"}" + (Recipients.parentOf(c.company)?.let { " (owned by $it)" } ?: "")) }
                co?.about?.let { item { Muted(it, MaterialTheme.typography.bodySmall) } }
                co?.website?.let { item { Muted(it, MaterialTheme.typography.bodySmall) } }
                cats.forEach { k -> item { Text("${k.label}: ${k.blurb}", style = MaterialTheme.typography.bodySmall) } }
                c.cname?.let { item { Text("Name chain: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
                Recipients.cloakedBy(c.cname)?.let { item { Text("Disguised: this name really points to $it", color = MaterialTheme.colorScheme.error) } }

                item { Text("From", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                item { Text("${meta.label} (${meta.pkg})") }
                item { Text("${fmtWhen(c.time)} · sent ${fmtBytes(c.up)}, received ${fmtBytes(c.down)}" + if (c.blocked) " · BLOCKED" else "") }

                item { Text("Search for more like this", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                item {
                    Column {
                        c.company?.let { TextButton(onClick = { onSearch(it) }) { Text("Everything to $it") } }
                        ip?.asOrg?.let { TextButton(onClick = { onSearch(it) }) { Text("Everything on $it's network") } }
                        ip?.country?.let { java.util.Locale("", it).getDisplayCountry(java.util.Locale.US) }?.let { TextButton(onClick = { onSearch(it) }) { Text("Everything going to $it") } }
                        TextButton(onClick = { onSearch(meta.label) }) { Text("Everything from ${meta.label}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { onDismiss(); open(Detail.App(c.uid)) }) { Text("App details") } })
}

fun fmtWhen(t: Long): String = java.text.SimpleDateFormat("MMM d, h:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date(t))
