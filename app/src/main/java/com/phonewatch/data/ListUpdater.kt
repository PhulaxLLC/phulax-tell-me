package com.phonewatch.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional, manual, OFF by default. Downloads fresh copies of the open tracker lists straight from
 * their publishers (no PhoneWatch server). PhoneWatch is excluded from its own VPN, so this request
 * goes out directly. Nothing about the phone is sent; it is a plain file download.
 */
object ListUpdater {
    private val SOURCES = mapOf(
        "android-tds.json" to "https://raw.githubusercontent.com/duckduckgo/tracker-blocklists/main/app/android-tds.json",
        "services.json" to "https://raw.githubusercontent.com/disconnectme/disconnect-tracking-protection/master/services.json",
        "exodus-trackers.json" to "https://reports.exodus-privacy.eu.org/api/trackers",
        "adguard-trackers.json" to "https://raw.githubusercontent.com/AdguardTeam/companiesdb/main/dist/trackers.json",
        "adguard-companies.json" to "https://raw.githubusercontent.com/AdguardTeam/companiesdb/main/dist/companies.json",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "hagezi-pro.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
    ).filterKeys { !com.phonewatch.BuildConfig.PLAY || it !in setOf("android-tds.json", "services.json") }   // Play edition: commercial-use lists only

    fun update(ctx: Context): String {
        if (!Prefs.allowListUpdates) return "Updates are switched off"
        val dir = File(ctx.filesDir, "lists").apply { mkdirs() }
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((name, url) in SOURCES) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Phulax Android Tell Me-personal/1.0")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                if (c.responseCode != 200 || body.length < 1000) throw Exception("bad response")
                if (name.endsWith(".json")) org.json.JSONObject(body) // validate
                else if (body.lineSequence().count { it.contains('.') && !it.startsWith("#") && !it.startsWith("!") } < 1000) throw Exception("bad list")
                File(dir, "$name.tmp").writeText(body)
                File(dir, "$name.tmp").renameTo(File(dir, name))
                done += name
            } catch (e: Exception) { failed += name }
        }
        TrackerDb.load(ctx)
        RuleEngine.clearCache()
        Prefs.lastListUpdate = System.currentTimeMillis()
        return "Updated: ${done.size}/${SOURCES.size}" + (if (failed.isNotEmpty()) " (failed: ${failed.joinToString()})" else "") + ". ${TrackerDb.sourceInfo}"
    }

    fun resetToBundled(ctx: Context) {
        File(ctx.filesDir, "lists").deleteRecursively()
        TrackerDb.load(ctx); RuleEngine.clearCache()
    }
}
