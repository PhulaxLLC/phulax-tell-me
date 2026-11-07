package com.phonewatch.inspect

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.phonewatch.data.Apps
import com.phonewatch.data.Cat
import com.phonewatch.data.Recipients
import com.phonewatch.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owner-initiated exports to Downloads/PhoneWatch. Nothing is ever sent anywhere: these are files on
 * the phone that the owner can open or copy to a computer.
 */
object Exporter {
    private val stamp get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    private fun open(ctx: Context, dir: String, name: String, mime: String): Pair<OutputStream, String> {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$dir")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("cannot create $name")
        return ctx.contentResolver.openOutputStream(uri)!! to "Download/$dir/$name"
    }

    private fun headersOut(h: String?, secrets: Boolean): String =
        (h ?: "").lines().filter { it.isNotBlank() }.joinToString("\n") { l ->
            val name = l.substringBefore(':')
            if (!secrets && Sensitive.isMaskedHeader(name)) "$name: •••••• (hidden; export with \"include secrets\" to show)" else l
        }

    private fun bodyOut(b: ByteArray?, type: String?, headers: String?, secrets: Boolean): String {
        if (b == null) return ""
        val enc = headers?.lines()?.firstOrNull { it.startsWith("content-encoding:", true) }?.substringAfter(':')?.trim()
        val t = Sensitive.bodyText(b, type, enc)
        return if (secrets) t else Sensitive.maskBodyText(t)
    }

    /** One request, as a readable text file. */
    fun exportRequest(ctx: Context, f: CapFlow, secrets: Boolean): String {
        val text = buildString {
            appendLine("Phulax Android Tell Me Deep Inspect: one captured request")
            appendLine("App: ${Apps.meta(f.uid).label} (${f.pkg})")
            appendLine("Time: ${iso(f.time)}")
            appendLine("Server: ${f.host} (${f.ip}:${f.port}) · company: ${f.company ?: "unknown"} · ${f.proto}")
            appendLine("Personal data found: ${SensType.of(f.sens).joinToString { it.label }.ifEmpty { "none detected" }}")
            appendLine()
            appendLine("${f.method} ${f.url}")
            appendLine(headersOut(f.reqHeaders, secrets))
            appendLine()
            appendLine("---- request body (${f.reqSize} bytes${if (f.reqTrunc) ", stored part only" else ""}) ----")
            appendLine(bodyOut(f.reqBody, f.reqType, f.reqHeaders, secrets))
            appendLine()
            appendLine("---- response: ${f.status} ----")
            appendLine(headersOut(f.respHeaders, secrets))
            appendLine()
            appendLine("---- response body (${f.respSize} bytes${if (f.respTrunc) ", stored part only" else ""}) ${f.note ?: ""} ----")
            appendLine(bodyOut(f.respBody, f.respType, f.respHeaders, secrets))
        }
        val (o, path) = open(ctx, "Phulax Android Tell Me", "request-${f.id}-$stamp.txt", "text/plain")
        o.use { it.write(text.toByteArray()) }
        return path
    }

    /**
     * "Export for review": every captured request (JSONL), every connection of every app (CSV, including
     * the ones that could not be decrypted), and a plain-language summary.md. Returns the folder path.
     */
    fun exportReview(ctx: Context, secrets: Boolean, days: Int = 30): String {
        val dir = "PhoneWatch/review-$stamp"
        val since = System.currentTimeMillis() - days * 86_400_000L
        val status = runCatching { CaptureDb.get(ctx).dao().statusesNow() }.getOrDefault(emptyList())
        val statusByUid = status.groupBy { it.uid }

        // ---- 1. requests.jsonl ----
        data class SensCount(var n: Int = 0, val companies: MutableMap<String, Int> = HashMap())
        val perApp = HashMap<String, HashMap<SensType, SensCount>>()
        var nReq = 0
        if (CaptureDb.exists(ctx)) {
            val dao = CaptureDb.get(ctx).dao()
            val (o, _) = open(ctx, dir, "requests.jsonl", "application/x-ndjson")
            o.bufferedWriter().use { w ->
                var after = 0L
                while (true) {
                    val page = dao.page(after, 200); if (page.isEmpty()) break
                    for (f in page) {
                        after = f.id
                        val app = Apps.meta(f.uid).label
                        val enc = f.reqHeaders.lines().firstOrNull { it.startsWith("content-encoding:", true) }?.substringAfter(':')?.trim()
                        val text = f.reqBody?.let { Sensitive.bodyText(it, f.reqType, enc) }
                        val j = JSONObject()
                            .put("time", iso(f.time)).put("app", app).put("package", f.pkg)
                            .put("host", f.host).put("ip", f.ip).put("company", f.company ?: JSONObject.NULL)
                            .put("parent_company", Recipients.parentOf(f.company) ?: JSONObject.NULL)
                            .put("category", Cat.of(f.cats).joinToString { it.label })
                            .put("protocol", f.proto).put("method", f.method).put("url", f.url)
                            .put("request_headers", headersOut(f.reqHeaders, secrets))
                            .put("request_body_bytes", f.reqSize).put("request_body_truncated", f.reqTrunc)
                            .put("status", f.status)
                            .put("response_headers", headersOut(f.respHeaders, secrets))
                            .put("response_body_bytes", f.respSize)
                            .put("sensitive_types", JSONArray(SensType.of(f.sens).map { it.label }))
                        if (text != null) {
                            val shown = if (secrets) text else Sensitive.maskBodyText(text)
                            val js = runCatching { if (shown.trim().startsWith("{")) JSONObject(shown) else if (shown.trim().startsWith("[")) JSONArray(shown) else null }.getOrNull()
                            j.put("request_body", js ?: shown.take(256 * 1024))
                        }
                        if (f.respBody != null && Sensitive.looksText(Sensitive.decompress(f.respBody, null)))
                            j.put("response_body_text", bodyOut(f.respBody, f.respType, f.respHeaders, secrets).take(16 * 1024))
                        w.write(j.toString()); w.write("\n")
                        nReq++
                        for (t in SensType.of(f.sens)) {
                            val sc = perApp.getOrPut(app) { HashMap() }.getOrPut(t) { SensCount() }
                            sc.n++; val co = f.company ?: f.host; sc.companies[co] = (sc.companies[co] ?: 0) + 1
                        }
                    }
                }
            }
        }

        // ---- 2. connections.csv: ALL apps, decrypted or not ----
        val rows = Repo.db.conn().recipientsNow(since)
        val recips = Recipients.build(ctx, rows)
        val byKey = recips.associateBy { it.key }
        fun q(s: Any?) = "\"" + (s?.toString() ?: "").replace("\"", "\"\"") + "\""
        run {
            val (o, _) = open(ctx, dir, "connections.csv", "text/csv")
            o.bufferedWriter().use { w ->
                w.write("app,package,domain,ip,cname_chain,company,parent_company,category,asn,network_owner,hosting,country,region,city,connections,bytes_sent,bytes_received,blocked,first_seen,last_seen,inspectable,reason\n")
                for (r in rows.sortedWith(compareBy({ it.pkg }, { -(it.up + it.down) }))) {
                    val rc = byKey[r.host ?: r.ip]; val info = rc?.info
                    val st = statusByUid[r.uid]
                    val s1 = st?.firstOrNull { it.host == r.host } ?: st?.firstOrNull { it.host == "*" }
                    val (insp, why) = inspectable(r.pkg, r.host, s1)
                    w.write(listOf(q(Apps.meta(r.uid).label), q(r.pkg), q(r.host), q(r.ip), q(r.cname), q(r.company), q(Recipients.parentOf(r.company)),
                        q(Cat.of(r.cats).joinToString { it.label }), q(info?.asn), q(info?.asOrg), q(info?.hosting), q(info?.country), q(info?.region), q(info?.city),
                        r.n, r.up, r.down, r.blocked, q(iso(r.firstTime)), q(iso(r.lastTime)), q(insp), q(why)).joinToString(",") + "\n")
                }
            }
        }

        // ---- 3. summary.md ----
        val md = buildString {
            appendLine("# Phulax Android Tell Me review export")
            appendLine()
            appendLine("Made ${iso(System.currentTimeMillis())} on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}). Covers the last $days days of connections and the captured requests still stored (kept ${DeepInspect.retentionDays} days).")
            appendLine("Secrets (Authorization/Cookie headers, password fields): ${if (secrets) "INCLUDED, keep this folder private" else "masked"}.")
            appendLine("Files: requests.jsonl ($nReq decrypted requests), connections.csv (${rows.size} app-to-server rows, all apps), this summary.")
            appendLine()
            appendLine("> Phulax Android Tell Me only sees the FIRST recipient of each connection. What a company does with the data afterwards (sharing or selling it to others) is not visible from the phone.")
            appendLine()
            appendLine("## Personal data seen in decrypted requests, per app")
            if (perApp.isEmpty()) appendLine("\nNo decrypted requests with personal data (Deep Inspect off, no app inspected yet, or nothing detected).")
            for ((app, m) in perApp.toSortedMap()) {
                appendLine()
                appendLine("**$app** sent:")
                for ((t, sc) in m.entries.sortedByDescending { it.value.n }) {
                    val to = sc.companies.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} (${it.value})" }
                    appendLine("- ${t.label} ${sc.n} times, to $to")
                }
            }
            appendLine()
            appendLine("## Every app: who it talked to, and whether Phulax Android Tell Me could read it")
            for ((uid, l) in rows.groupBy { it.uid }.toList().sortedByDescending { p -> p.second.sumOf { it.up } }) {
                val meta = Apps.meta(uid)
                val st = statusByUid[uid]
                val whole = st?.firstOrNull { it.host == "*" }
                val (insp, why) = inspectable(meta.pkg, null, whole ?: st?.firstOrNull())
                val companies = l.groupBy { it.company ?: "unknown company" }.map { (c, x) -> c to x.sumOf { it.up } }.sortedByDescending { it.second }
                appendLine()
                appendLine("### ${meta.label} (${meta.pkg})")
                appendLine("- Sent ${fmt(l.sumOf { it.up })}, received ${fmt(l.sumOf { it.down })}, ${l.size} servers, ${l.sumOf { it.n }} connections, ${l.sumOf { it.blocked }} blocked")
                appendLine("- Inspectable: $insp${if (why.isNotEmpty()) " ($why)" else ""}")
                appendLine("- Companies: " + companies.take(12).joinToString(", ") { "${it.first} (${fmt(it.second)} sent)" })
            }
            appendLine()
            appendLine("## Recipients")
            appendLine()
            appendLine("Every server any app sent data to, largest first. Location and network come from the offline DB-IP Lite database (IP Geolocation by DB-IP, https://db-ip.com, CC BY 4.0); IPv6 city is approximate.")
            appendLine()
            appendLine("| Domain | Company (parent) | Category | IP / network | Hosting | Location | Apps | Sent | Received | First seen | Last seen | CNAME chain |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (r in recips.sortedByDescending { it.up }) {
                val co = (r.company ?: "unknown") + (r.parent?.let { " ($it)" } ?: "") + (Recipients.cloakedBy(r.cname)?.let { " [CNAME-cloaked: $it]" } ?: "")
                appendLine("| ${r.key} | $co | ${Cat.of(r.cats).joinToString { it.label }} | ${r.ip} ${r.info?.network() ?: ""} | ${r.info?.hosting ?: ""} | ${r.info?.place() ?: ""} | " +
                    "${r.rows.joinToString(", ") { Apps.meta(it.uid).label }} | ${fmt(r.up)} | ${fmt(r.down)} | ${iso(r.firstTime)} | ${iso(r.lastTime)} | ${r.cname ?: ""} |")
            }
        }
        val (o, _) = open(ctx, dir, "summary.md", "text/markdown")
        o.use { it.write(md.toByteArray()) }
        return "Download/$dir"
    }

    private fun inspectable(pkg: String, host: String?, s: HostStatus?): Pair<String, String> = when {
        s?.status == "cant_inspect" -> "no" to (s.detail ?: "app doesn't trust user certificates / pins")
        s?.status == "bad_cert" -> "blocked" to (s.detail ?: "server certificate invalid")
        s?.status == "inspected" -> "yes" to (s.detail ?: "decrypted")
        !DeepInspect.enabled -> "no" to "Deep Inspect off (metadata only)"
        Exclusions.reason(pkg) != null && !DeepInspect.appSelected(pkg) -> "no" to "excluded: ${Exclusions.reason(pkg)}"
        !DeepInspect.appSelected(pkg) -> "no" to "app not selected for Deep Inspect"
        else -> "not yet" to "no web traffic seen since Deep Inspect was turned on"
    }

    private fun fmt(b: Long) = when {
        b < 1024 -> "$b B"; b < 1048576 -> "%.0f KB".format(b / 1024.0); else -> "%.1f MB".format(b / 1048576.0)
    }

}
