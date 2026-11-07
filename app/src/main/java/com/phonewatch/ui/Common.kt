package com.phonewatch.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonewatch.data.Apps
import com.phonewatch.data.Cat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ---------- Theme: large, high-contrast text for easy reading ----------
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FE0D3), onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F4D47), onPrimaryContainer = Color(0xFFB9F5EC),
    secondary = Color(0xFFFFB59D), onSecondary = Color(0xFF3A0B00),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5C1513), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101418), onBackground = Color(0xFFF1F3F4),
    surface = Color(0xFF101418), onSurface = Color(0xFFF1F3F4),
    surfaceVariant = Color(0xFF242B31), onSurfaceVariant = Color(0xFFD3DCE2),
    surfaceContainer = Color(0xFF1A2025), surfaceContainerHigh = Color(0xFF232A30),
    outline = Color(0xFF8B979F),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF00695F), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F5EC), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF9A3A1A),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFA), surface = Color(0xFFF8FAFA),
    surfaceVariant = Color(0xFFE1E8EB), onSurfaceVariant = Color(0xFF2F3A40),
)

private val BigType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 20.sp, lineHeight = 27.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        bodySmall = t.bodySmall.copy(fontSize = 16.sp, lineHeight = 21.sp),
        labelLarge = t.labelLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontSize = 16.sp),
        labelSmall = t.labelSmall.copy(fontSize = 15.sp),
    )
}

@Composable
fun PwTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = BigType, content = content)
}

// ---------- formatting ----------
fun fmtBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun fmtAgo(t: Long): String {
    if (t <= 0) return "never"
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} days ago"
    }
}

fun fmtTime(t: Long): String = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date(t))

fun catColor(c: Cat): Color = when (c) {
    Cat.ADS -> Color(0xFFE57373)
    Cat.ANALYTICS -> Color(0xFFFFB74D)
    Cat.CRASH -> Color(0xFFBA9BE0)
    Cat.SOCIAL -> Color(0xFF64B5F6)
    Cat.FINGERPRINT -> Color(0xFFF06292)
    Cat.LOCATION -> Color(0xFFFFD54F)
    Cat.TRACKING -> Color(0xFFFF8A65)
    Cat.CONTENT -> Color(0xFF90A4AE)
    Cat.OWN -> Color(0xFF81C784)
    Cat.UNKNOWN -> Color(0xFF9E9E9E)
}

// ---------- small building blocks ----------
@Composable
fun CatChip(c: Cat) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(catColor(c).copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(c.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatChips(mask: Int, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Cat.of(mask).forEach { CatChip(it) }
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, warn: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(18.dp)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun AppIcon(pkg: String, size: Dp = 44.dp) {
    var img by remember(pkg) { mutableStateOf(iconCache[pkg]) }
    LaunchedEffect(pkg) {
        if (img == null) img = withContext(Dispatchers.IO) {
            Apps.icon(pkg)?.let { d ->
                val px = 96
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, px, px); d.draw(Canvas(bmp))
                bmp.asImageBitmap().also { iconCache[pkg] = it }
            }
        }
    }
    val i = img
    if (i != null) Image(i, null, Modifier.size(size).clip(RoundedCornerShape(12.dp)))
    else Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Muted(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium) =
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Inferred(text: String = "Likely / inferred. Traffic is encrypted, so Phulax Android Tell Me cannot read what is inside. This is what the app's permissions and the tracker's published data collection allow.") {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)).padding(12.dp)
    ) { Text("ⓘ  $text", style = MaterialTheme.typography.bodySmall) }
}


/** "Tell me" button: speaks what this app sent, received, read and copied, for the range picked on screen. Tap again to stop. */
@Composable
fun TellMeButton(uid: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val speaking by com.phonewatch.data.Speaker.speakingUid.collectAsState()
    val range by UiState.range.collectAsState()
    val on = speaking == uid
    FilledTonalButton(onClick = {
        if (on) com.phonewatch.data.Speaker.stop()
        else scope.launch {
            val words = when (range) { Range.TODAY -> "today"; Range.WEEK -> "in the last 7 days"; Range.MONTH -> "in the last 30 days" }
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.phonewatch.data.AppTell.report(ctx, uid, range.since(), words) }
            com.phonewatch.data.Speaker.say(ctx, uid, text)
        }
    }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (on) "Stop" else "Tell me") }
}
