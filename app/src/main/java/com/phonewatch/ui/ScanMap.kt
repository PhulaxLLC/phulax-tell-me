package com.phonewatch.ui

import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phonewatch.scan.Radar
import com.phonewatch.scan.Seen
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/** OpenStreetMap view: your path while scanning, each device's estimated spot, and (for [focus]) every place it was heard,
 *  colored by signal (red = strong / close, yellow = medium, blue = weak). */
@Composable
fun ScanMapDialog(focus: Seen?, devices: List<Seen>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    remember {
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", 0))
            userAgentValue = "PhoneWatch/${ctx.packageName}"
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(focus?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.kind.label } ?: "Map of what was found", style = MaterialTheme.typography.titleMedium)
                        Muted(if (focus != null) "Dots: where you were when it was heard (red = strong, blue = weak). Pin: best guess of where it is."
                            else "Blue line: your path. Pins: best guess of where each device is (red pin = flagged).", MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(), factory = { c ->
                    MapView(c).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(17.0)
                        val track = synchronized(Radar.track) { Radar.track.map { GeoPoint(it.lat, it.lon) } }
                        if (track.size > 1) overlays.add(Polyline(this).apply { setPoints(track); outlinePaint.color = Color.rgb(30, 120, 255); outlinePaint.strokeWidth = 8f })
                        val show = if (focus != null) listOf(focus) else devices
                        if (focus != null) synchronized(focus.spots) { focus.spots.toList() }.forEach { sp ->
                            overlays.add(Polygon(this).apply {
                                points = Polygon.pointsAsCircle(GeoPoint(sp.lat, sp.lon), 6.0)
                                val col = when { sp.rssi >= -60 -> Color.RED; sp.rssi >= -78 -> Color.rgb(255, 190, 0); else -> Color.rgb(60, 120, 255) }
                                fillPaint.color = Color.argb(160, Color.red(col), Color.green(col), Color.blue(col)); outlinePaint.color = col; outlinePaint.strokeWidth = 2f
                                title = "${sp.rssi} dBm at ${fmtTime(sp.t)}"
                            })
                        }
                        var center: GeoPoint? = null
                        show.forEach { s ->
                            val e = s.estimate() ?: return@forEach
                            val gp = GeoPoint(e.first, e.second)
                            if (center == null) center = gp
                            overlays.add(Marker(this).apply {
                                position = gp; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = s.name?.takeIf { it.isNotBlank() } ?: s.kind.label
                                snippet = listOf(s.kind.label, s.motion().label, s.trend().label, "${s.rssi} dBm", "last ${fmtAgo(s.last)}").filter { it.isNotBlank() }.joinToString(" · ")
                                if (s.kind.alert || s.following) icon?.setTint(Color.RED)
                            })
                        }
                        val here = Radar.place?.let { GeoPoint(it.latitude, it.longitude) }
                        if (here != null) overlays.add(Polygon(this).apply {
                            points = Polygon.pointsAsCircle(here, 8.0); fillPaint.color = Color.rgb(30, 120, 255); outlinePaint.color = Color.WHITE; title = "You"
                        })
                        (if (focus != null) center ?: here else here ?: center)?.let { controller.setCenter(it) }
                        onResume()
                    }
                }, onRelease = { it.onDetach() })
                Text("Map © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
            }
        }
    }
}
