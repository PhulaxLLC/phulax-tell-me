package com.phonewatch.inspect

import android.util.Log
import com.twitter.hpack.Decoder
import com.twitter.hpack.HeaderListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** Who/where a proxied connection belongs to. */
class ConnInfo(
    val uid: Int, val pkg: String, val host: String, val ip: String, val port: Int,
    val scheme: String, val company: String?, val cats: Int,
)

/** Body bytes kept up to the cap; size always counted. Media bodies keep metadata only. */
class Body(private val cap: Int) {
    private val buf = ByteArrayOutputStream()
    var size = 0L; private set
    var media = false
    fun add(b: ByteArray, off: Int, len: Int) {
        size += len
        if (media) return
        val room = cap - buf.size()
        if (room > 0) buf.write(b, off, minOf(room, len))
    }
    val truncated get() = !media && size > buf.size()
    fun bytes(): ByteArray? = if (media || buf.size() == 0) null else buf.toByteArray()
}

/** One request/response pair being assembled from the decrypted streams. */
class FlowBuilder(val c: ConnInfo, val proto: String, cap: Int) {
    val id = ids.incrementAndGet()
    val time = System.currentTimeMillis()
    var method = ""
    var url = ""
    val reqHeaders = ArrayList<Pair<String, String>>()
    val reqBody = Body(cap)
    var status = 0
    val respHeaders = ArrayList<Pair<String, String>>()
    val respBody = Body(cap)
    var sens = 0
    var note: String? = null
    @Volatile var reqDone = false

    fun header(list: List<Pair<String, String>>, name: String) = list.firstOrNull { it.first.equals(name, true) }?.second

    fun requestComplete() {
        if (reqDone) return
        reqDone = true
        val ct = header(reqHeaders, "content-type")
        sens = runCatching {
            Sensitive.mask(Sensitive.scan(Sensitive.requestText(url, headersText(reqHeaders), reqBody.bytes(), ct, header(reqHeaders, "content-encoding"))))
        }.getOrDefault(0)
        save()
    }

    fun responseHeadersDone() {
        val ct = header(respHeaders, "content-type")?.lowercase() ?: ""
        val len = header(respHeaders, "content-length")?.toLongOrNull() ?: -1
        if (isMedia(ct, len, respBody)) { respBody.media = true; note = "Media response: metadata only (type, size)" }
    }

    fun save() = DeepInspect.save(toFlow())

    private fun toFlow() = CapFlow(
        id = id, time = time, uid = c.uid, pkg = c.pkg, host = c.host, ip = c.ip, port = c.port, company = c.company, cats = c.cats,
        scheme = c.scheme, proto = proto, method = method, url = url,
        reqHeaders = headersText(reqHeaders), reqBody = reqBody.bytes(), reqSize = reqBody.size,
        reqType = header(reqHeaders, "content-type"), reqTrunc = reqBody.truncated,
        status = status, respHeaders = if (respHeaders.isEmpty()) null else headersText(respHeaders),
        respBody = respBody.bytes(), respSize = respBody.size, respType = header(respHeaders, "content-type"),
        respTrunc = respBody.truncated, sens = sens, note = note,
    )

    companion object {
        private val ids = AtomicLong(System.currentTimeMillis() * 1000)
        fun headersText(h: List<Pair<String, String>>) = h.joinToString("\n") { "${it.first}: ${it.second}" }
        fun isMedia(ct: String, len: Long, b: Body): Boolean =
            ct.startsWith("video/") || ct.startsWith("audio/") || ct.startsWith("image/") || ct.startsWith("font/") ||
                ct.contains("mp2t") || ct.contains("dash") && !ct.contains("xml") ||
                (ct.contains("octet-stream") && len > 1_000_000)
    }
}

/** Passive parser: watches one direction of a decrypted connection. Never changes the bytes. */
interface Tap { fun feed(b: ByteArray, off: Int, len: Int); fun closed() }

/** HTTP/1.x request (client->server) and response (server->client) parser sharing one queue. */
class Http1Conn(val c: ConnInfo, private val cap: Int) {
    val pending = ArrayDeque<FlowBuilder>()
    @Volatile var dead = false
    fun reqTap(): Tap = H1Tap(true)
    fun respTap(): Tap = H1Tap(false)

    private inner class H1Tap(val isReq: Boolean) : Tap {
        private val head = ByteArrayOutputStream()
        private var state = 0           // 0 head, 1 body by length, 2 chunk size line, 3 chunk data, 4 chunk CRLF, 5 trailers, 6 until close
        private var remaining = 0L
        private var tail = 0
        private val line = StringBuilder()
        private var cur: FlowBuilder? = null

        override fun feed(b: ByteArray, off: Int, len: Int) {
            if (dead) return
            try { parse(b, off, len) } catch (e: Exception) { Log.d("PW.Tap", "h1 parse", e); dead = true }
        }

        private fun body(): Body? = cur?.let { if (isReq) it.reqBody else it.respBody }

        private fun parse(b: ByteArray, off0: Int, len0: Int) {
            var off = off0; val end = off0 + len0
            while (off < end && !dead) {
                when (state) {
                    0 -> {
                        val c0 = b[off].toInt() and 0xFF; head.write(c0); off++
                        tail = (tail shl 8) or c0
                        if (head.size() > 128 * 1024) { dead = true; return }
                        if (tail == 0x0D0A0D0A) { headDone(String(head.toByteArray(), Charsets.ISO_8859_1)); head.reset(); tail = 0 }
                    }
                    1 -> {
                        val n = minOf(remaining, (end - off).toLong()).toInt()
                        body()?.add(b, off, n); off += n; remaining -= n
                        if (remaining == 0L) messageDone()
                    }
                    2, 5 -> {
                        val ch = (b[off].toInt() and 0xFF).toChar(); off++
                        if (ch == '\n') {
                            val l = line.toString().trim(); line.setLength(0)
                            if (state == 2) {
                                val size = l.substringBefore(';').trim().toLongOrNull(16) ?: run { dead = true; return }
                                if (size == 0L) state = 5 else { remaining = size; state = 3 }
                            } else if (l.isEmpty()) messageDone()
                        } else line.append(ch)
                    }
                    3 -> {
                        val n = minOf(remaining, (end - off).toLong()).toInt()
                        body()?.add(b, off, n); off += n; remaining -= n
                        if (remaining == 0L) { state = 4; remaining = 2 }
                    }
                    4 -> { off++; remaining--; if (remaining == 0L) state = 2 }
                    6 -> { val n = end - off; body()?.add(b, off, n); off = end }
                }
            }
        }

        private fun headDone(text: String) {
            val lines = text.split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return
            val first = lines[0]
            val headers = lines.drop(1).mapNotNull { l -> val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim() }
            fun h(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second
            val chunked = h("transfer-encoding")?.contains("chunked", true) == true
            val clen = h("content-length")?.toLongOrNull()
            if (isReq) {
                val parts = first.split(' ')
                if (parts.size < 3 || !parts[2].startsWith("HTTP/")) { dead = true; return }
                val f = FlowBuilder(c, "http/1.1", cap)
                f.method = parts[0]
                val target = parts[1]
                f.url = if (target.startsWith("http://") || target.startsWith("https://")) target else "${c.scheme}://${h("host") ?: c.host}$target"
                f.reqHeaders += headers
                synchronized(pending) { pending.addLast(f) }
                cur = f
                if (f.method == "CONNECT") { f.requestComplete(); dead = true; return }
                state = when { chunked -> 2; clen != null && clen > 0 -> { remaining = clen; 1 }; else -> { messageDone(); return } }
            } else {
                if (!first.startsWith("HTTP/")) { dead = true; return }
                val code = first.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (code in 100..199) {
                    if (code == 101) { synchronized(pending) { pending.firstOrNull() }?.let { it.status = 101; it.respHeaders += headers; it.note = "Switched protocol (WebSocket): later messages not parsed"; it.save() }; dead = true }
                    return // interim response, wait for the real one
                }
                val f = synchronized(pending) { pending.firstOrNull() } ?: run { dead = true; return }
                cur = f
                f.status = code; f.respHeaders += headers
                f.responseHeadersDone()
                val noBody = f.method == "HEAD" || code == 204 || code == 304
                state = when { noBody -> { messageDone(); return }; chunked -> 2; clen != null -> if (clen == 0L) { messageDone(); return } else { remaining = clen; 1 }; else -> 6 }
            }
        }

        private fun messageDone() {
            val f = cur ?: return
            if (isReq) f.requestComplete()
            else { synchronized(pending) { pending.removeFirstOrNull() }; if (!f.reqDone) f.requestComplete() else f.save() }
            cur = null; state = 0
        }

        override fun closed() {
            if (state == 6 && !isReq) messageDone()
            else if (isReq) cur?.let { if (!it.reqDone) it.requestComplete() }
        }
    }
}

/** HTTP/2 passive parser for both directions (frames + HPACK). */
class Http2Conn(val c: ConnInfo, private val cap: Int) {
    private val streams = HashMap<Int, FlowBuilder>()
    private val reqDecoder = Decoder(256 * 1024, 4096)   // decodes what the app sends
    private val respDecoder = Decoder(256 * 1024, 4096)  // decodes what the server sends
    @Volatile var dead = false

    fun reqTap(): Tap = H2Dir(true)
    fun respTap(): Tap = H2Dir(false)

    private inner class H2Dir(val isReq: Boolean) : Tap {
        private var preface = if (isReq) 24 else 0
        private val hdr = ByteArray(9); private var hdrN = 0
        private var payload = ByteArray(0); private var payN = 0; private var payLen = 0
        private var type = 0; private var flags = 0; private var sid = 0
        private var block: ByteArrayOutputStream? = null
        private var blockSid = 0; private var blockEnd = false; private var blockPush = false

        override fun feed(b: ByteArray, off: Int, len: Int) {
            if (dead) return
            try { parse(b, off, len) } catch (e: Exception) { Log.d("PW.Tap", "h2 parse", e); dead = true }
        }

        private fun parse(b: ByteArray, off0: Int, len0: Int) {
            var off = off0; val end = off0 + len0
            while (off < end) {
                if (preface > 0) { val n = minOf(preface, end - off); preface -= n; off += n; continue }
                if (hdrN < 9) {
                    val n = minOf(9 - hdrN, end - off); System.arraycopy(b, off, hdr, hdrN, n); hdrN += n; off += n
                    if (hdrN == 9) {
                        payLen = ((hdr[0].toInt() and 0xFF) shl 16) or ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                        type = hdr[3].toInt() and 0xFF; flags = hdr[4].toInt() and 0xFF
                        sid = (((hdr[5].toInt() and 0x7F) shl 24) or ((hdr[6].toInt() and 0xFF) shl 16) or ((hdr[7].toInt() and 0xFF) shl 8) or (hdr[8].toInt() and 0xFF))
                        if (payLen > 16 * 1024 * 1024) { dead = true; return }
                        if (payload.size < payLen) payload = ByteArray(payLen)
                        payN = 0
                        if (payLen == 0) { frame(); hdrN = 0 }
                    }
                    continue
                }
                val n = minOf(payLen - payN, end - off); System.arraycopy(b, off, payload, payN, n); payN += n; off += n
                if (payN == payLen) { frame(); hdrN = 0 }
            }
        }

        private fun frame() {
            val p = payload; val len = payLen
            when (type) {
                0x0 -> { // DATA
                    var s = 0; var e = len
                    if (flags and 0x8 != 0) { e -= p[0].toInt() and 0xFF; s = 1 }
                    val f = synchronized(streams) { streams[sid] } ?: return
                    if (e > s) (if (isReq) f.reqBody else f.respBody).add(p, s, e - s)
                    if (flags and 0x1 != 0) endStream(f)
                }
                0x1, 0x5 -> { // HEADERS, PUSH_PROMISE
                    var s = 0; var e = len
                    if (flags and 0x8 != 0) { e -= p[0].toInt() and 0xFF; s = 1 }
                    if (type == 0x1 && flags and 0x20 != 0) s += 5
                    if (type == 0x5) s += 4
                    block = ByteArrayOutputStream().apply { if (e > s) write(p, s, e - s) }
                    blockSid = sid; blockEnd = flags and 0x1 != 0; blockPush = type == 0x5
                    if (flags and 0x4 != 0) headerBlockDone()
                }
                0x9 -> { // CONTINUATION
                    block?.write(p, 0, len)
                    if (flags and 0x4 != 0) headerBlockDone()
                }
                0x3 -> synchronized(streams) { streams.remove(sid) }?.let { f -> if (!f.reqDone) f.requestComplete(); f.note = (f.note ?: "") + " (stream reset)"; f.save() }
                0x4 -> if (flags and 0x1 == 0) { // SETTINGS
                    var i = 0
                    while (i + 6 <= len) {
                        val id = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        val v = ((p[i + 2].toInt() and 0xFF) shl 24) or ((p[i + 3].toInt() and 0xFF) shl 16) or ((p[i + 4].toInt() and 0xFF) shl 8) or (p[i + 5].toInt() and 0xFF)
                        // The peer's HEADER_TABLE_SIZE limits the OTHER side's encoder
                        if (id == 1) (if (isReq) respDecoder else reqDecoder).let { d -> synchronized(d) { d.setMaxHeaderTableSize(v) } }
                        i += 6
                    }
                }
            }
        }

        private fun headerBlockDone() {
            val raw = block?.toByteArray() ?: return
            block = null
            val list = ArrayList<Pair<String, String>>()
            val dec = if (isReq) reqDecoder else respDecoder
            synchronized(dec) {
                dec.decode(ByteArrayInputStream(raw), HeaderListener { name, value, _ -> list += String(name, Charsets.UTF_8) to String(value, Charsets.UTF_8) })
                dec.endHeaderBlock()
            }
            if (blockPush) return // server push: decoded only to keep HPACK state in step
            val sidNow = blockSid
            if (isReq) {
                val existing = synchronized(streams) { streams[sidNow] }
                if (existing == null) {
                    val f = FlowBuilder(c, "h2", cap)
                    fun h(n: String) = list.firstOrNull { it.first == n }?.second
                    f.method = h(":method") ?: "?"
                    f.url = "${h(":scheme") ?: c.scheme}://${h(":authority") ?: c.host}${h(":path") ?: ""}"
                    f.reqHeaders += list.filter { !it.first.startsWith(":") || it.first == ":authority" }
                    synchronized(streams) { streams[sidNow] = f }
                    if (blockEnd) endStream(f)
                } else if (blockEnd) endStream(existing) // trailers
            } else {
                val f = synchronized(streams) { streams[sidNow] } ?: return
                val st = list.firstOrNull { it.first == ":status" }?.second?.toIntOrNull()
                if (st != null && st in 100..199) return
                if (f.status == 0 && st != null) { f.status = st; f.respHeaders += list.filter { !it.first.startsWith(":") }; f.responseHeadersDone() }
                if (blockEnd) endStream(f)
            }
        }

        private fun endStream(f: FlowBuilder) {
            if (isReq) f.requestComplete()
            else {
                synchronized(streams) { streams.values.remove(f) }
                if (!f.reqDone) f.requestComplete() else f.save()
            }
        }

        override fun closed() {
            val left = synchronized(streams) { streams.values.toList() }
            left.forEach { if (!it.reqDone) it.requestComplete() else if (!isReq) it.save() }
        }
    }
}
