package com.phonewatch.scan

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import java.util.Locale

/** What the voice says for each alert, in the chosen language, with the owner's own text when they wrote one.
 *  Placeholders: {name} device or network name, {near} how close, {minutes}, {tech} cell type, {count}.
 *  English-only extras: {type} what it probably is, {named} "named X" / "with no name", {feet} "about 12 feet from you",
 *  {moving} ", moving towards you" / ", moving away from you", {risk} "This device is (not) a security risk." */
object Voice {
    class Lang(val code: String, val label: String, val locale: Locale)
    val LANGS = listOf(
        Lang("en", "English", Locale.US), Lang("pl", "Polski", Locale("pl", "PL")), Lang("es", "Español", Locale("es", "ES")),
        Lang("ru", "Русский", Locale("ru", "RU")), Lang("de", "Deutsch", Locale.GERMANY), Lang("ga", "Gaeilge (Irish)", Locale("ga", "IE")),
    )

    enum class Phrase(val title: String, val kind: Kind? = null) {
        FIND_MY("AirTag / Find My tracker away from owner", Kind.FIND_MY), TILE("Tile tracker", Kind.TILE),
        SMARTTAG("Samsung SmartTag", Kind.SMARTTAG), GOOGLE_TAG("Google tracker tag", Kind.GOOGLE_TAG),
        FLOCK("Flock camera", Kind.FLOCK), AXON("Axon police device", Kind.AXON),
        FIND_MY_NEAR("Find My tracker with owner nearby", Kind.FIND_MY_NEAR), APPLE("Apple device", Kind.APPLE),
        BT("Bluetooth device", Kind.BT), WIFI("WiFi network", Kind.WIFI),
        FOLLOWING("Something moving with you"), UNKNOWN_BT("Unknown device connected to your phone"),
        DEAUTH("Forced off WiFi (deauth)"), TWO_G("Phone dropped to 2G"), CELL("Cell tower changed"), VOICE_ON("Voice turned on"),
        GLASSES("Smart glasses with a camera", Kind.GLASSES),
    }

    // Default text per language. Order matches Phrase.
    private val DEFAULTS: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "Alert. AirTag or Find My tracker away from its owner, {feet}{moving}. {risk}", "Alert. Tile tracker, {feet}{moving}. {risk}",
            "Alert. Samsung SmartTag, {feet}{moving}. {risk}", "Alert. Google tracker tag, {feet}{moving}. {risk}",
            "Alert. Possible Flock camera, {feet}{moving}. {risk}", "Alert. Axon police device, {feet}{moving}. {risk}",
            "Find My tracker with its owner nearby, {feet}{moving}. {risk}", "New {type} {named}, {feet}{moving}. {risk}",
            "New Bluetooth {type} {named}, {feet}{moving}. {risk}", "New WiFi {type} {named}, {feet}{moving}. {risk}",
            "Warning. {name} has been moving with you for {minutes} minutes. Possible security risk. It can track your location.", "Alert. Unknown Bluetooth device connected to your phone. {name}.",
            "Alert. You were forced off WiFi. Possible deauth attack.", "Warning. Your phone dropped to 2G. This can be a fake cell tower.",
            "Cell tower changed to {tech}.", "Voice alerts on.", "Alert. Smart glasses with a camera {named}, {feet}{moving}. {risk}"),
        "pl" to listOf(
            "Uwaga. AirTag lub lokalizator Find My z dala od właściciela, {near}.", "Uwaga. Lokalizator Tile, {near}.",
            "Uwaga. Samsung SmartTag, {near}.", "Uwaga. Lokalizator Google, {near}.",
            "Uwaga. Możliwa kamera Flock, {near}.", "Uwaga. Urządzenie policyjne Axon, {near}.",
            "Lokalizator Find My z właścicielem w pobliżu, {near}.", "Urządzenie Apple {name}, {near}.",
            "Urządzenie Bluetooth {name}, {near}.", "Sieć WiFi {name}, {near}.",
            "Ostrzeżenie. {name} porusza się z tobą od {minutes} minut.", "Uwaga. Nieznane urządzenie Bluetooth połączyło się z telefonem. {name}.",
            "Uwaga. Rozłączono cię z WiFi. Możliwy atak deauth.", "Ostrzeżenie. Telefon przełączył się na 2G. To może być fałszywa stacja bazowa.",
            "Zmiana stacji bazowej na {tech}.", "Alerty głosowe włączone.", "Uwaga. Inteligentne okulary z kamerą, {near}."),
        "es" to listOf(
            "Alerta. AirTag o rastreador Find My lejos de su dueño, {near}.", "Alerta. Rastreador Tile, {near}.",
            "Alerta. Samsung SmartTag, {near}.", "Alerta. Rastreador de Google, {near}.",
            "Alerta. Posible cámara Flock, {near}.", "Alerta. Dispositivo policial Axon, {near}.",
            "Rastreador Find My con su dueño cerca, {near}.", "Dispositivo Apple {name}, {near}.",
            "Dispositivo Bluetooth {name}, {near}.", "Red WiFi {name}, {near}.",
            "Advertencia. {name} se mueve contigo desde hace {minutes} minutos.", "Alerta. Un dispositivo Bluetooth desconocido se conectó a tu teléfono. {name}.",
            "Alerta. Te desconectaron del WiFi. Posible ataque de desautenticación.", "Advertencia. Tu teléfono bajó a 2G. Puede ser una antena falsa.",
            "La antena cambió a {tech}.", "Alertas de voz activadas.", "Alerta. Gafas inteligentes con cámara, {near}."),
        "ru" to listOf(
            "Внимание. AirTag или метка Find My вдали от владельца, {near}.", "Внимание. Метка Tile, {near}.",
            "Внимание. Samsung SmartTag, {near}.", "Внимание. Метка Google, {near}.",
            "Внимание. Возможно, камера Flock, {near}.", "Внимание. Полицейское устройство Axon, {near}.",
            "Метка Find My рядом с владельцем, {near}.", "Устройство Apple {name}, {near}.",
            "Устройство Bluetooth {name}, {near}.", "Сеть WiFi {name}, {near}.",
            "Предупреждение. {name} перемещается вместе с вами уже {minutes} минут.", "Внимание. Неизвестное устройство Bluetooth подключилось к телефону. {name}.",
            "Внимание. Вас отключили от WiFi. Возможна атака деаутентификации.", "Предупреждение. Телефон переключился на 2G. Это может быть поддельная вышка.",
            "Вышка сменилась на {tech}.", "Голосовые оповещения включены.", "Внимание. Умные очки с камерой, {near}."),
        "de" to listOf(
            "Achtung. AirTag oder Find-My-Tracker ohne Besitzer, {near}.", "Achtung. Tile-Tracker, {near}.",
            "Achtung. Samsung SmartTag, {near}.", "Achtung. Google-Tracker, {near}.",
            "Achtung. Mögliche Flock-Kamera, {near}.", "Achtung. Axon-Polizeigerät, {near}.",
            "Find-My-Tracker mit Besitzer in der Nähe, {near}.", "Apple-Gerät {name}, {near}.",
            "Bluetooth-Gerät {name}, {near}.", "WLAN {name}, {near}.",
            "Warnung. {name} bewegt sich seit {minutes} Minuten mit dir.", "Achtung. Ein unbekanntes Bluetooth-Gerät hat sich mit deinem Telefon verbunden. {name}.",
            "Achtung. Du wurdest aus dem WLAN geworfen. Möglicher Deauth-Angriff.", "Warnung. Dein Telefon ist auf 2G gefallen. Das kann ein falscher Funkmast sein.",
            "Funkmast gewechselt zu {tech}.", "Sprachwarnungen an.", "Achtung. Smarte Brille mit Kamera, {near}."),
        "ga" to listOf(
            "Aire. AirTag nó rianaire Find My i bhfad óna úinéir, {near}.", "Aire. Rianaire Tile, {near}.",
            "Aire. Samsung SmartTag, {near}.", "Aire. Rianaire Google, {near}.",
            "Aire. Ceamara Flock, b'fhéidir, {near}.", "Aire. Gléas póilíní Axon, {near}.",
            "Rianaire Find My lena úinéir in aice láimhe, {near}.", "Gléas Apple {name}, {near}.",
            "Gléas Bluetooth {name}, {near}.", "Líonra WiFi {name}, {near}.",
            "Rabhadh. Tá {name} ag bogadh leat le {minutes} nóiméad.", "Aire. Cheangail gléas Bluetooth anaithnid le do ghuthán. {name}.",
            "Aire. Baineadh den WiFi thú. Ionsaí deauth, b'fhéidir.", "Rabhadh. Thit do ghuthán go 2G. D'fhéadfadh gur crann bréige é.",
            "Athraíodh an crann go {tech}.", "Foláirimh ghutha ar siúl.", "Aire. Spéaclaí cliste le ceamara, {near}."),
    )
    private val NEAR = mapOf(
        "en" to listOf("very close", "close", "nearby", "far away"), "pl" to listOf("bardzo blisko", "blisko", "w pobliżu", "daleko"),
        "es" to listOf("muy cerca", "cerca", "en las cercanías", "lejos"), "ru" to listOf("очень близко", "близко", "поблизости", "далеко"),
        "de" to listOf("sehr nah", "nah", "in der Nähe", "weit weg"), "ga" to listOf("an-ghar", "gar", "in aice láimhe", "i bhfad uait"),
    )
    // Grouped speech when many of one kind show up at once: "<label>: <count>"
    private val GROUP = mapOf(
        "en" to mapOf(Kind.WIFI to "New WiFi networks", Kind.BT to "New Bluetooth devices", Kind.APPLE to "New Apple devices", null to "New devices"),
        "pl" to mapOf(Kind.WIFI to "Nowe sieci WiFi", Kind.BT to "Nowe urządzenia Bluetooth", Kind.APPLE to "Nowe urządzenia Apple", null to "Nowe urządzenia"),
        "es" to mapOf(Kind.WIFI to "Redes WiFi nuevas", Kind.BT to "Dispositivos Bluetooth nuevos", Kind.APPLE to "Dispositivos Apple nuevos", null to "Dispositivos nuevos"),
        "ru" to mapOf(Kind.WIFI to "Новые сети WiFi", Kind.BT to "Новые устройства Bluetooth", Kind.APPLE to "Новые устройства Apple", null to "Новые устройства"),
        "de" to mapOf(Kind.WIFI to "Neue WLANs", Kind.BT to "Neue Bluetooth-Geräte", Kind.APPLE to "Neue Apple-Geräte", null to "Neue Geräte"),
        "ga" to mapOf(Kind.WIFI to "Líonraí WiFi nua", Kind.BT to "Gléasanna Bluetooth nua", Kind.APPLE to "Gléasanna Apple nua", null to "Gléasanna nua"),
    )
    private val MORE = mapOf("en" to "More alerts", "pl" to "Więcej alertów", "es" to "Más alertas", "ru" to "Ещё оповещения", "de" to "Weitere Warnungen", "ga" to "Tuilleadh foláireamh")

    private lateinit var prefs: SharedPreferences
    fun init(c: Context) { prefs = c.getSharedPreferences("scan", Context.MODE_PRIVATE) }

    var lang: String
        get() = prefs.getString("lang", "en") ?: "en"
        set(v) { prefs.edit().putString("lang", v).apply(); Radar.bump() }
    var voiceName: String?                    // null = the language's default voice
        get() = prefs.getString("voiceName", null)
        set(v) { prefs.edit().putString("voiceName", v).apply(); Radar.bump() }
    var rate: Float
        get() = prefs.getFloat("rate", 1f)
        set(v) { prefs.edit().putFloat("rate", v).apply(); Radar.bump() }
    var pitch: Float
        get() = prefs.getFloat("pitch", 1f)
        set(v) { prefs.edit().putFloat("pitch", v).apply(); Radar.bump() }
    val KEYS = setOf("lang", "voiceName", "rate", "pitch")

    fun locale() = LANGS.first { it.code == lang }.locale
    fun default(p: Phrase, l: String = lang) = (DEFAULTS[l] ?: DEFAULTS.getValue("en"))[p.ordinal]
    fun custom(p: Phrase): String? = prefs.getString("txt_${lang}_${p.name}", null)?.takeIf { it.isNotBlank() }
    fun setCustom(p: Phrase, text: String?) {
        prefs.edit().apply { if (text.isNullOrBlank()) remove("txt_${lang}_${p.name}") else putString("txt_${lang}_${p.name}", text) }.apply()
        Radar.bump()
    }

    fun nearWord(rssi: Int) = (NEAR[lang] ?: NEAR.getValue("en"))[when { rssi >= -55 -> 0; rssi >= -70 -> 1; rssi >= -85 -> 2; else -> 3 }]

    /** The text to speak, with placeholders filled. Empty placeholders are dropped along with a doubled space or comma. */
    fun say(p: Phrase, name: String? = null, rssi: Int? = null, minutes: Long? = null, tech: String? = null, s: Seen? = null): String {
        var t = custom(p) ?: default(p)
        val ft = s?.feet() ?: rssi?.let { Guesser.feet(it, p == Phrase.WIFI) }
        t = t.replace("{type}", s?.let { typeWord(it) } ?: if (p == Phrase.WIFI) "network" else "device")
            .replace("{named}", s?.let { named(it) } ?: name?.let { "named ${it.take(30)}" } ?: "")
            .replace("{feet}", ft?.let { Guesser.feetText(it) + if (it > 500) "" else " from you" } ?: "")
            .replace("{moving}", s?.let { moving(it.trend()) } ?: "").replace("{risk}", s?.let { risk(it) } ?: "")
        t = t.replace("{name}", name?.take(30) ?: "").replace("{near}", rssi?.let { nearWord(it) } ?: "")
            .replace("{minutes}", minutes?.toString() ?: "").replace("{tech}", tech ?: "").replace("{count}", "")
        return t.replace(Regex("\\s+,"), ",").replace(Regex(",\\s*\\."), ".").replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun cleanName(s: Seen) = s.name?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("(") }?.take(30)
    fun typeWord(s: Seen): String = when (s.kind) {
        Kind.APPLE -> s.guess?.takeIf { it.type != DevType.UNKNOWN }?.say(false) ?: "Apple device"
        Kind.BT, Kind.WIFI -> s.guess?.say(s.wifi) ?: if (s.wifi) "network" else "device"
        else -> s.kind.say
    }
    fun named(s: Seen): String = cleanName(s)?.let { "named $it" } ?: if (s.wifi) "with a hidden name" else "with no name"
    fun moving(t: Trend) = when (t) { Trend.CLOSER -> ", moving towards you"; Trend.FARTHER -> ", moving away from you"; else -> "" }
    /** Every device: is any data going in or out between it and this phone, and what. "Possible security risk" when there is,
     *  or when the device is built to collect information about you (trackers, cameras, glasses, plate readers). */
    fun risk(s: Seen): String {
        val parts = listOfNotNull(Radar.link(s), about(s))
        if (parts.isNotEmpty()) return "Possible security risk. " + parts.joinToString(" ")
        return "No connection to your device, so no data in or out." + when {
            s.open -> " It has no password, so joining it would be risky."
            s.kind == Kind.FIND_MY_NEAR -> " It is flagged if it keeps following you."
            else -> ""
        }
    }

    /** What the device itself collects about you, whether or not it ever connects to your phone. */
    private fun about(s: Seen): String? = when {
        s.following -> "It has been moving with you, so it can track your location."
        s.kind == Kind.FIND_MY || s.kind == Kind.TILE || s.kind == Kind.SMARTTAG || s.kind == Kind.GOOGLE_TAG -> "It can report your location to its owner."
        s.kind == Kind.FLOCK -> "It records license plates and photos of cars."
        s.kind == Kind.AXON -> "It can record video and audio of you."
        s.kind == Kind.GLASSES -> "It can take photos, video and audio of you."
        s.guess?.type == DevType.CAMERA -> "It can record video of you."
        else -> null
    }

    /** What a device that connected to this phone can get, from the Bluetooth profiles Android lists for it. */
    fun gathers(b: Contacts.Bt): String {
        if (!b.paired) return "It is not paired, so it can only get your phone's Bluetooth name and basic device details."
        val p = (b.profiles ?: "").uppercase()
        val what = listOfNotNull(
            "your contacts and call history".takeIf { "PBAP" in p }, "your text messages".takeIf { "MAP" in p },
            "your calls and microphone".takeIf { Regex("HFP|HSP|HEADSET").containsMatchIn(p) }, "audio playing on your phone".takeIf { "A2DP" in p },
            "your internet connection".takeIf { Regex("PAN|NAP").containsMatchIn(p) }, "keyboard and mouse input".takeIf { "HID" in p },
            "files you accept".takeIf { "OPP" in p }, "your SIM card".takeIf { "SAP" in p })
        return if (what.isEmpty()) "It is paired, so it can exchange data with your phone." else "It can get " + what.joinToString(", ").replace(Regex(", ([^,]*)$"), " and $1") + "."
    }

    /** Said when a device already named comes clearly closer. */
    fun closer(s: Seen, ft: Int): String {
        val n = cleanName(s)?.let { "${typeWord(s)} $it" } ?: typeWord(s)
        return "${n.replaceFirstChar { it.uppercase() }} is getting closer, now ${Guesser.feetText(ft)} from you."
    }

    fun phraseFor(k: Kind) = Phrase.values().first { it.kind == k }
    fun group(k: Kind, count: Int): String {
        val g = GROUP[lang] ?: GROUP.getValue("en")
        return "${g[k] ?: g.getValue(null)}: $count."
    }
    fun more(count: Int) = "${MORE[lang] ?: MORE.getValue("en")}: $count."

    /** Puts the chosen language, voice, speed and pitch on a TextToSpeech. Returns false when the language isn't installed. */
    fun apply(t: TextToSpeech): Boolean {
        val ok = t.setLanguage(locale()) >= TextToSpeech.LANG_AVAILABLE
        voiceName?.let { n -> runCatching { t.voices?.firstOrNull { it.name == n } }.getOrNull()?.let { t.voice = it } }
        t.setSpeechRate(rate); t.setPitch(pitch)
        return ok
    }

    /** Voices installed for the chosen language, best first. */
    fun voices(t: TextToSpeech): List<android.speech.tts.Voice> = runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
        .filter { it.locale.language == locale().language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != locale().country }, { it.name }))
}
