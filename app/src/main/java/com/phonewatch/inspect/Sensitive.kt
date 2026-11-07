package com.phonewatch.inspect

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Kinds of personal data PhoneWatch looks for in what apps send. */
enum class SensType(val bit: Int, val label: String) {
    AD_ID(1, "Advertising ID"),
    DEVICE_ID(2, "Device ID"),
    HW_ID(4, "IMEI / serial number"),
    LOCATION(8, "GPS location"),
    EMAIL(16, "Email address"),
    PHONE(32, "Phone number"),
    CONTACTS(64, "Contacts-like list"),
    AUTH(128, "Login / auth token"),
    ;
    companion object { fun of(mask: Int) = entries.filter { mask and it.bit != 0 } }
}

data class Hit(val type: SensType, val start: Int, val end: Int)

object Sensitive {
    private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private const val SEP = "[\"']?\\s*[:=]\\s*[\"']?"
    private val rules: List<Pair<SensType, Regex>> = listOf(
        SensType.AD_ID to Regex("(?i)\\b(adid|ad_id|advertising_?id|advertisingid|gaid|google_?ad_?id|rdid|aaid|idfa|ifa|maid)$SEP($UUID)"),
        SensType.AD_ID to Regex("(?i)\\b(ad[-_ ]?tracking|lat|limit_ad_tracking)$SEP(true|false|0|1)\\b.{0,200}?($UUID)"),
        SensType.DEVICE_ID to Regex("(?i)\\b(android_?id|androidid|device_?id|deviceid|ssaid|installation_?id|install_?id|instance_?id|app_instance_id|fid|firebase_?installation_?id|udid|uuid|vendor_?id|anon_?id|anonymous_?id|user_pseudo_id|cid|client_?id)$SEP([A-Za-z0-9_\\-:.]{8,80})"),
        SensType.HW_ID to Regex("(?i)\\b(imei|meid|imsi|iccid|serial|serialno|serial_?number|ro\\.serialno|wifi_?mac|mac_?address|bt_?mac)$SEP([A-Za-z0-9:\\-]{6,40})"),
        SensType.LOCATION to Regex("(?i)\\b(lat|latitude|lng|lon|long|longitude)$SEP(-?\\d{1,3}\\.\\d{3,})"),
        SensType.LOCATION to Regex("(?i)\\b(ll|latlng|latlon|loc|location|geo|coordinates)$SEP\\[?(-?\\d{1,2}\\.\\d{3,})\\s*[,;%2C]+\\s*(-?\\d{1,3}\\.\\d{3,})"),
        SensType.EMAIL to Regex("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]{1,63}(\\.[A-Za-z0-9\\-]{1,63})*\\.[A-Za-z]{2,24}"),
        SensType.PHONE to Regex("(?i)\\b(phone|phone_?number|tel|telephone|msisdn|mobile|cell)$SEP(\\+?[0-9][0-9 ()\\-.]{6,18}[0-9])"),
        SensType.PHONE to Regex("(?<![\\w+])\\+[1-9]\\d{9,13}\\b"),
        SensType.AUTH to Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=\\-]{12,}"),
        SensType.AUTH to Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"),
        SensType.AUTH to Regex("(?i)\\b(access_?token|id_?token|refresh_?token|auth_?token|session_?id|sessionid|api_?key|apikey|x-api-key|authtoken|oauth_token|client_secret|password|passwd|pwd)$SEP([^\\s\"'&,;}]{6,})"),
    )
    /** 15-digit IMEI-looking numbers that pass the Luhn check (TAC prefixes of real phones). */
    private val imeiLike = Regex("(?<!\\d)(35|86|01|99|45|53)\\d{13}(?!\\d)")

    private val maskHeaders = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-goog-api-key")
    private val maskBody = Regex("(?i)([\"']?(password|passwd|pwd|pass|passcode|pin|secret|client_secret|otp)[\"']?\\s*[:=]\\s*[\"']?)([^\"'&,;}\\s]+)")

    fun isMaskedHeader(name: String) = name.lowercase() in maskHeaders
    fun maskBodyText(t: String): String = maskBody.replace(t) { it.groupValues[1] + "••••••" }

    fun scan(text: String): List<Hit> {
        val out = ArrayList<Hit>()
        for ((t, re) in rules) for (m in re.findAll(text)) {
            if (t == SensType.LOCATION && !plausibleLatLon(m)) continue
            out += Hit(t, m.range.first, m.range.last + 1)
        }
        for (m in imeiLike.findAll(text)) if (luhn(m.value)) out += Hit(SensType.HW_ID, m.range.first, m.range.last + 1)
        // Contacts-like: many emails / phone numbers in one payload, or contact-ish keys
        val emails = out.count { it.type == SensType.EMAIL }
        val phones = Regex("\\+?\\d[\\d ()\\-]{8,16}\\d").findAll(text).count()
        if (emails >= 3 || (phones >= 5 && Regex("(?i)(contact|display_?name|given_?name|address_?book)").containsMatchIn(text)))
            out += Hit(SensType.CONTACTS, 0, 0)
        return out
    }

    private fun plausibleLatLon(m: MatchResult): Boolean {
        val nums = m.groupValues.drop(1).mapNotNull { it.toDoubleOrNull() }
        return nums.isNotEmpty() && nums.all { it in -180.0..180.0 } && nums.any { it != 0.0 }
    }

    private fun luhn(s: String): Boolean {
        var sum = 0
        for ((i, ch) in s.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    fun mask(hits: List<Hit>) = hits.fold(0) { a, h -> a or h.type.bit }

    /** Everything the app sent in one request, as text for scanning (URL, headers, decoded body). */
    fun requestText(url: String, headers: String, body: ByteArray?, contentType: String?, encoding: String?): String =
        buildString {
            append(runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)).append('\n').append(headers).append('\n')
            if (body != null) append(bodyText(body, contentType, encoding, forScan = true))
        }

    // ---------- body decoding / pretty printing ----------

    fun decompress(body: ByteArray, encoding: String?): ByteArray {
        val enc = encoding?.lowercase()?.trim() ?: return body
        val s: InputStream = when {
            enc.contains("gzip") -> GZIPInputStream(ByteArrayInputStream(body))
            enc.contains("deflate") -> InflaterInputStream(ByteArrayInputStream(body))
            enc.contains("br") -> org.brotli.dec.BrotliInputStream(ByteArrayInputStream(body))
            else -> return body
        }
        val out = ByteArrayOutputStream()
        runCatching { s.use { i -> val b = ByteArray(8192); while (true) { val n = i.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > 4 shl 20) break } } }
        return if (out.size() > 0) out.toByteArray() else body
    }

    fun looksText(b: ByteArray): Boolean {
        if (b.isEmpty()) return true
        val n = minOf(b.size, 2048)
        var bad = 0
        for (i in 0 until n) { val c = b[i].toInt() and 0xFF; if (c < 9 || (c in 14..31)) bad++ }
        return bad * 20 < n
    }

    /** Human readable body: pretty JSON, decoded form fields, or hex + printable strings for binary (protobuf etc). */
    fun bodyText(raw: ByteArray, contentType: String?, encoding: String?, forScan: Boolean = false): String {
        val b = decompress(raw, encoding)
        val ct = contentType?.lowercase() ?: ""
        if (looksText(b)) {
            val s = String(b, Charsets.UTF_8)
            val t = s.trim()
            if (ct.contains("json") || t.startsWith("{") || t.startsWith("[")) runCatching {
                return if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
            }
            if (ct.contains("x-www-form-urlencoded") || (!t.contains(' ') && t.contains('=') && t.contains('&'))) runCatching {
                return t.split('&').joinToString("\n") { kv ->
                    val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
                    URLDecoder.decode(k, "UTF-8") + " = " + URLDecoder.decode(v, "UTF-8")
                }
            }
            return s
        }
        return if (forScan) strings(b).joinToString("\n") else hexDump(b)
    }

    /** Printable runs of 4+ characters (like the `strings` tool): shows readable parts of protobuf. */
    fun strings(b: ByteArray, min: Int = 4): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) cur.append(c.toChar()) else { if (cur.length >= min) out += cur.toString(); cur.setLength(0) }
        }
        if (cur.length >= min) out += cur.toString()
        return out
    }

    fun hexDump(b: ByteArray, max: Int = 16 * 1024): String = buildString {
        append("Binary data (${b.size} bytes, e.g. protobuf). Hex + readable text:\n\n")
        val n = minOf(b.size, max)
        var i = 0
        while (i < n) {
            append("%06x  ".format(i))
            for (j in 0 until 16) if (i + j < n) append("%02x ".format(b[i + j])) else append("   ")
            append(' ')
            for (j in 0 until 16) if (i + j < n) { val c = b[i + j].toInt() and 0xFF; append(if (c in 32..126) c.toChar() else '.') }
            append('\n'); i += 16
        }
        if (b.size > n) append("… ${b.size - n} more bytes\n")
        val s = strings(b)
        if (s.isNotEmpty()) { append("\nReadable strings:\n"); s.take(300).forEach { append("• ").append(it).append('\n') } }
    }
}
