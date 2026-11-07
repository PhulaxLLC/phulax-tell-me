package com.phonewatch.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonewatch.scan.Radar
import com.phonewatch.scan.Voice

/** Language, voice, speed/pitch and the owner's own text for every spoken alert. Has its own TextToSpeech for the Test buttons. */
@Composable
fun VoiceSettings() {
    val ctx = LocalContext.current
    Radar.version.collectAsState().value
    var ready by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(ctx.applicationContext) { ready = it == TextToSpeech.SUCCESS } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    val lang = Voice.lang
    var installed by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf(listOf<android.speech.tts.Voice>()) }
    LaunchedEffect(ready, lang, Voice.voiceName, Voice.rate, Voice.pitch) {
        if (ready) { installed = Voice.apply(tts); voices = Voice.voices(tts) }
    }
    fun test(text: String) { if (ready) { Voice.apply(tts); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test") } }
    fun sample(p: Voice.Phrase) = Voice.say(p, name = "Sample", rssi = -60, minutes = 15, tech = "4G (LTE)")

    Text("Language", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Voice.LANGS.forEach { l ->
            FilterChip(selected = l.code == lang, onClick = { Voice.voiceName = null; Voice.lang = l.code }, label = { Text(l.label) })
        }
    }
    if (ready && !installed) {
        Muted("No ${Voice.LANGS.first { it.code == lang }.label} voice is installed on this phone. Install one in the speech settings " +
            "(Samsung or Google text-to-speech > Install voice data). Until then the phone's default voice reads the text." +
            if (lang == "ga") " Google and Samsung don't offer an Irish voice yet, so Irish needs a separate text-to-speech app that has one." else "",
            MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Install voice") }
            TextButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Speech settings") }
        }
    }

    Text("Voice", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (voices.isEmpty()) Muted(if (ready) "Only the default voice is available for this language." else "Loading voices…", MaterialTheme.typography.bodySmall)
    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = Voice.voiceName == null, onClick = { Voice.voiceName = null; test(sample(Voice.Phrase.VOICE_ON)) }, label = { Text("Default") })
        voices.forEachIndexed { i, v ->
            val where = v.locale.displayCountry.ifBlank { v.locale.country }
            val label = "Voice ${i + 1}" + (if (where.isNotBlank()) " · $where" else "") + if (v.isNetworkConnectionRequired) " · online" else ""
            FilterChip(selected = Voice.voiceName == v.name, onClick = { Voice.voiceName = v.name; test(sample(Voice.Phrase.VOICE_ON)) }, label = { Text(label) })
        }
    }
    Text("Speed ${"%.1f".format(Voice.rate)}x", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Slider(value = Voice.rate, onValueChange = { Voice.rate = it }, valueRange = 0.5f..2f, steps = 14)
    Text("Pitch ${"%.1f".format(Voice.pitch)}", style = MaterialTheme.typography.bodyMedium)
    Slider(value = Voice.pitch, onValueChange = { Voice.pitch = it }, valueRange = 0.5f..2f, steps = 14)

    Text("What it says", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Muted("Write your own text for any alert, in the language picked above. Leave a box empty to use the text shown in grey. " +
        "You can use {name} (device or network name), {near} (how close), {minutes} and {tech} (cell type). " +
        "English only: {type} (what it probably is), {named} (\"named …\" or \"with no name\"), {feet} (\"about 12 feet from you\"), " +
        "{moving} (\", moving towards you\") and {risk} (security risk or not).", MaterialTheme.typography.bodySmall)
    Voice.Phrase.values().forEach { p ->
        var text by remember(p, lang) { mutableStateOf(Voice.custom(p) ?: "") }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it; Voice.setCustom(p, it) },
                label = { Text(p.title) }, placeholder = { Text(Voice.default(p)) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { test(sample(p)) }) { Text("Test") }
                if (text.isNotEmpty()) TextButton(onClick = { text = ""; Voice.setCustom(p, null) }) { Text("Reset") }
            }
        }
    }
}
