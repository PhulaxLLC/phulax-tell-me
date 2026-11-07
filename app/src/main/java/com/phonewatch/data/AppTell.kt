package com.phonewatch.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.phonewatch.debloat.Shell
import com.phonewatch.inspect.CaptureDb
import com.phonewatch.inspect.SensType
import com.phonewatch.scan.Voice
import kotlinx.coroutines.flow.MutableStateFlow

/** "Tell me": one app's data in plain spoken words. What it sent and received and to whom (PhoneWatch's own connection log),
 *  what it read or copied on the phone (Android's app-ops record, through Shizuku), and personal data Deep Inspect saw in its traffic. */
object AppTell {
    // Android app-op -> what it means, in words that follow "It ..."
    private val OPS = linkedMapOf(
        "READ_CLIPBOARD" to "read what you copied", "WRITE_CLIPBOARD" to "copied something to your clipboard",
        "CAMERA" to "used your camera", "RECORD_AUDIO" to "used your microphone",
        "FINE_LOCATION" to "got your exact location", "COARSE_LOCATION" to "got your rough location",
        "READ_CONTACTS" to "read your contacts", "READ_SMS" to "read your text messages", "READ_CALL_LOG" to "read your call history",
        "READ_CALENDAR" to "read your calendar", "READ_MEDIA_IMAGES" to "read your photos", "READ_MEDIA_VIDEO" to "read your videos",
        "READ_EXTERNAL_STORAGE" to "read your files", "GET_ACCOUNTS" to "looked up the accounts on your phone",
        "READ_PHONE_STATE" to "read your phone details", "BLUETOOTH_SCAN" to "scanned for nearby Bluetooth devices",
        "ACTIVITY_RECOGNITION" to "read your physical activity", "BODY_SENSORS" to "read your body sensors",
        "GET_USAGE_STATS" to "looked at which apps you use",
    )
    private val LINE = Regex("""^\s*(\w+): [^;]*;(?:.*?\btime=\+([\dhdms]+) ago)?(?:.*?\brejectTime=\+([\dhdms]+) ago)?""")
    private val PART = Regex("""(\d+)(ms|d|h|m|s)""")

    private fun agoMs(s: String) = PART.findAll(s).sumOf { m ->
        val n = m.groupValues[1].toLong()
        when (m.groupValues[2]) { "d" -> n * 86_400_000; "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; else -> n }
    }
    private fun agoWords(ms: Long): String = when {
        ms < 120_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} minutes ago"
        ms < 2 * 3_600_000 -> "an hour ago"
        ms < 86_400_000 -> "${ms / 3_600_000} hours ago"
        ms < 2 * 86_400_000 -> "yesterday"
        else -> "${ms / 86_400_000} days ago"
    }
    private fun size(b: Long) = when {
        b < 1_000 -> "$b bytes"; b < 1_000_000 -> "${b / 1000} kilobytes"
        b < 1_000_000_000 -> "%.1f megabytes".format(b / 1e6); else -> "%.1f gigabytes".format(b / 1e9)
    }
    private fun list(xs: List<String>) = when (xs.size) { 0 -> ""; 1 -> xs[0]; else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last() }

    /** Builds the spoken report. Runs database and shell calls: call off the main thread. */
    fun report(ctx: Context, uid: Int, since: Long, rangeWords: String): String {
        val meta = Apps.meta(uid)
        val out = mutableListOf<String>()
        val hosts = Repo.db.conn().hostsForAppNow(uid, since)
        if (hosts.isEmpty()) out += "${meta.label}, $rangeWords: Phulax Android Tell Me saw no internet data from it."
        else {
            val up = hosts.sumOf { it.up }; val down = hosts.sumOf { it.down }
            val byCo = hosts.groupBy { it.company ?: "unknown" }.mapValues { e -> e.value.sumOf { it.n } }.toList().sortedByDescending { it.second }
            val known = byCo.map { it.first }.filter { it != "unknown" }
            out += "${meta.label}, $rangeWords: sent ${size(up)} and received ${size(down)}, in ${hosts.sumOf { it.n }} connections."
            if (known.isNotEmpty()) out += "It sent data to ${known.size} compan${if (known.size == 1) "y" else "ies"}: ${list(known.take(5))}" +
                (if (known.size > 5) ", and ${known.size - 5} more." else ".")
            val trackers = hosts.filter { it.cats and Cat.TRACKER_MASK != 0 }.mapNotNull { it.company }.distinct()
            if (trackers.isNotEmpty()) {
                val kinds = Cat.of(hosts.filter { it.cats and Cat.TRACKER_MASK != 0 }.fold(0) { a, h -> a or h.cats }).filter { it.bit and Cat.TRACKER_MASK != 0 }
                out += "Tracking: ${list(trackers.take(4))} ${if (trackers.size == 1) "is" else "are"} ${list(kinds.map { it.label.lowercase() })} companies."
            }
            hosts.sumOf { it.blocked }.takeIf { it > 0 }?.let { out += "Phulax Android Tell Me blocked $it of its connections." }
        }

        // what it actually read or copied on the phone, from Android's own record
        if (Shell.state(ctx) == Shell.State.READY && Regex("[A-Za-z0-9._]+").matches(meta.pkg)) {
            val win = System.currentTimeMillis() - since
            val did = mutableListOf<String>(); val tried = mutableListOf<String>()
            Shell.run("appops", "get", meta.pkg, log = false).out.lineSequence().forEach { l ->
                val m = LINE.find(l) ?: return@forEach
                val what = OPS[m.groupValues[1]] ?: return@forEach
                m.groupValues[2].takeIf { it.isNotEmpty() }?.let { agoMs(it) }?.takeIf { it <= win }?.let { did += "$what ${agoWords(it)}" }
                m.groupValues[3].takeIf { it.isNotEmpty() }?.let { agoMs(it) }?.takeIf { it <= win }?.let { tried += m.groupValues[1].lowercase().replace('_', ' ') }
            }
            out += if (did.isEmpty()) "On your phone, it didn't read or copy anything Android keeps a record of, $rangeWords."
                else "On your phone, it ${list(did)}."
            if (tried.isNotEmpty()) out += "Android blocked it when it tried: ${list(tried)}."
        } else {
            val allowed = Apps.grantedData(meta.pkg).map { it.label.lowercase() }
            if (allowed.isNotEmpty()) out += "It is allowed to use: ${list(allowed.take(6))}. Start Shizuku to hear what it actually read or copied."
        }

        // personal data Deep Inspect found inside its requests
        if (CaptureDb.exists(ctx)) runCatching {
            val mask = CaptureDb.get(ctx).dao().sensSinceNow(since).filter { it.uid == uid }.fold(0) { a, r -> a or r.sens }
            if (mask != 0) out += "Found inside what it sent: your ${list(SensType.of(mask).map { it.label.lowercase() })}."
        }
        return out.joinToString(" ")
    }
}

/** One shared voice for "Tell me" buttons, with the voice settings from the Scan tab. */
object Speaker {
    val speakingUid = MutableStateFlow<Int?>(null)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(ctx: Context, uid: Int, text: String) {
        speakingUid.value = uid
        val t = tts
        if (t != null && ready) { Voice.apply(t); t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tell$uid"); return }
        pending = text
        if (t == null) tts = TextToSpeech(ctx.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            val tt = tts ?: return@TextToSpeech
            if (!ready) { speakingUid.value = null; return@TextToSpeech }
            tt.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (tt.isSpeaking != true) speakingUid.value = null }
                @Deprecated("old API") override fun onError(id: String?) { speakingUid.value = null }
                override fun onStop(id: String?, interrupted: Boolean) { if (!tt.isSpeaking) speakingUid.value = null }
            })
            Voice.apply(tt)
            pending?.let { tt.speak(it, TextToSpeech.QUEUE_FLUSH, null, "tell$uid") }; pending = null
        }
    }

    fun stop() { tts?.stop(); speakingUid.value = null }
}
