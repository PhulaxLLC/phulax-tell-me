package com.phonewatch.inspect

import android.util.Log
import com.phonewatch.data.Apps
import com.phonewatch.data.Cat
import com.phonewatch.data.TrackerDb
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * On-phone interception proxy for Deep Inspect (same idea as mitmproxy / PCAPdroid's decryption mode).
 *
 * The packet engine hands each chosen TCP 443/80 connection to 127.0.0.1:[port] using SOCKS5, with the
 * app's uid in the SOCKS user name and the real destination in the CONNECT request. For TLS we:
 *  1. read the app's ClientHello (server name + ALPN),
 *  2. connect to the real server and validate its certificate normally against the system CA store
 *     (invalid -> blocked, never downgraded),
 *  3. answer the app with a certificate for that name signed by PhoneWatch's CA,
 *  4. relay the decrypted bytes unchanged in both directions while a passive parser logs them.
 * If the app rejects our certificate it is remembered and later connections pass through untouched.
 * The proxy listens on loopback only, and PhoneWatch's own sockets bypass the VPN, so nothing loops.
 */
object MitmProxy {
    private const val TAG = "PW.Mitm"
    @Volatile var running = false; private set
    @Volatile var port = 0; private set
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "pw-mitm").apply { isDaemon = true } }
    /** Set by the VPN service: server name learned from DNS for (uid, ip). */
    @Volatile var dnsName: (Int, String) -> String? = { _, _ -> null }

    @Synchronized fun start(): Int {
        if (running) return port
        val s = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        server = s; port = s.localPort; running = true
        Thread({
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { handle(c) }
            }
        }, "pw-mitm-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on $port")
        return port
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        server = null; port = 0
    }

    private fun readFully(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var o = 0
        while (o < n) { val r = i.read(b, o, n - o); if (r < 0) throw EOFException(); o += r }
        return b
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 20_000
            val ci = client.getInputStream(); val co = client.getOutputStream()
            // ---- SOCKS5 from the packet engine ----
            val g = readFully(ci, 2); readFully(ci, g[1].toInt() and 0xFF)
            co.write(byteArrayOf(5, 2)); co.flush()                       // choose user/password auth
            val a = readFully(ci, 2); val user = String(readFully(ci, a[1].toInt() and 0xFF))
            val pl = readFully(ci, 1)[0].toInt() and 0xFF; readFully(ci, pl)
            co.write(byteArrayOf(1, 0)); co.flush()
            val req = readFully(ci, 4)
            val atyp = req[3].toInt()
            val addr = readFully(ci, if (atyp == 1) 4 else 16)
            val pb = readFully(ci, 2)
            val dport = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)
            co.write(ByteArray(if (atyp == 1) 10 else 22).also { it[0] = 5; it[1] = 0; it[2] = 0; it[3] = atyp.toByte() }); co.flush()
            val uid = user.substringBefore(':').toIntOrNull() ?: -1
            val ip = InetAddress.getByAddress(addr).hostAddress ?: return
            val pkg = Apps.meta(uid).pkg

            if (dport == 80) { plainHttp(client, ci, co, uid, pkg, ip, dport); return }

            // ---- peek at the TLS ClientHello ----
            val rh = readFully(ci, 5)
            if (rh[0].toInt() != 0x16) { passThrough(client, ci, co, ip, dport, rh); return }
            val rlen = ((rh[3].toInt() and 0xFF) shl 8) or (rh[4].toInt() and 0xFF)
            val body = readFully(ci, rlen)
            val hello = rh + body
            val (sni, alpn) = parseHello(body)
            val host = (sni ?: dnsName(uid, ip) ?: ip).lowercase()
            if (sni == null || DeepInspect.cantInspect(uid, host)) {
                if (sni == null) DeepInspect.status(uid, host, "error", "No server name in the TLS hello; passed through")
                passThrough(client, ci, co, ip, dport, hello); return
            }

            // ---- real server first: normal certificate validation against the system store ----
            val raw = Socket()
            upstream = raw
            raw.connect(InetSocketAddress(InetAddress.getByAddress(addr), dport), 15_000)
            raw.tcpNoDelay = true
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, dport, true) as SSLSocket
            upstream = ssl
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(sni))
                val want = alpn.filter { it == "h2" || it == "http/1.1" }
                if (want.isNotEmpty()) applicationProtocols = want.toTypedArray()
            }
            ssl.soTimeout = 20_000
            try {
                ssl.startHandshake()
            } catch (e: SSLHandshakeException) {
                val certProblem = generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException } ||
                    e.message?.contains("cert", true) == true || e.message?.contains("hostname", true) == true
                DeepInspect.status(uid, host, if (certProblem) "bad_cert" else "error",
                    if (certProblem) "BLOCKED: the real server's certificate is not valid (${e.message?.take(160)})" else "Server TLS error: ${e.message?.take(160)}")
                return
            }
            val chosen = ssl.applicationProtocol ?: ""

            // ---- now the app: present our certificate for the same name ----
            val engine = CaManager.serverContext(sni).createSSLEngine()
            engine.useClientMode = false
            if (chosen.isNotEmpty()) engine.sslParameters = engine.sslParameters.apply { applicationProtocols = arrayOf(chosen) }
            val tls = TlsIo(engine, ci, co, hello)
            try {
                tls.handshake()
            } catch (e: Exception) {
                val why = "App rejected Phulax Android Tell Me's certificate (doesn't trust user CAs or pins certificates)."
                // A TLS alert from the app is a clear "no"; a silent hang-up counts as a strike (twice = no)
                val alert = e.message?.contains("alert", true) == true
                if (alert || DeepInspect.strike(uid, host)) DeepInspect.markCantInspect(uid, host, why, hardFail = alert)
                return
            }
            val hs = System.currentTimeMillis()
            DeepInspect.status(uid, host, "inspected", null)
            client.soTimeout = 0; ssl.soTimeout = 0

            val hit = TrackerDb.lookup(host)
            val c = ConnInfo(uid, pkg, host, ip, dport, "https", hit?.company?.name, hit?.cats ?: Cat.UNKNOWN.bit)
            val cap = DeepInspect.bodyCap()
            val (reqTap, respTap) = if (chosen == "h2") Http2Conn(c, cap).let { it.reqTap() to it.respTap() } else Http1Conn(c, cap).let { it.reqTap() to it.respTap() }

            val upIn = ssl.inputStream; val upOut = ssl.outputStream
            pool.execute {
                val buf = ByteArray(32 * 1024)
                try {
                    while (true) {
                        val n = upIn.read(buf); if (n < 0) break
                        respTap.feed(buf, 0, n)
                        tls.write(buf, 0, n)
                    }
                } catch (_: Exception) {
                } finally { respTap.closed(); tls.close(); runCatching { client.close() }; runCatching { ssl.close() } }
            }
            val buf = ByteArray(32 * 1024)
            var sent = 0L
            try {
                while (true) {
                    val n = tls.read(buf); if (n < 0) break
                    if (sent == 0L) DeepInspect.sawRequest(uid, host)
                    sent += n
                    reqTap.feed(buf, 0, n)
                    upOut.write(buf, 0, n); upOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                reqTap.closed()
                runCatching { ssl.close() }; runCatching { client.close() }
            }
            // Handshake OK but the app hung up at once without a request, twice: it checks pins after the handshake
            if (sent == 0L && System.currentTimeMillis() - hs < 3000 && DeepInspect.strike(uid, host))
                DeepInspect.markCantInspect(uid, host, "App closed the connection right after the handshake, 3 times (likely certificate pinning)", hardFail = false)
        } catch (e: Exception) {
            Log.d(TAG, "conn", e)
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** Plain HTTP (port 80): relay unchanged, log with the HTTP/1 parser. */
    private fun plainHttp(client: Socket, ci: InputStream, co: OutputStream, uid: Int, pkg: String, ip: String, dport: Int) {
        val up = Socket()
        try {
            up.connect(InetSocketAddress(ip, dport), 15_000); up.tcpNoDelay = true
            client.soTimeout = 0
            val host = dnsName(uid, ip) ?: ip
            val hit = TrackerDb.lookup(host)
            val conn = Http1Conn(ConnInfo(uid, pkg, host, ip, dport, "http", hit?.company?.name, hit?.cats ?: Cat.UNKNOWN.bit), DeepInspect.bodyCap())
            DeepInspect.status(uid, host, "inspected", "Plain HTTP (not encrypted at all)")
            relay(client, ci, co, up, ByteArray(0), conn.reqTap(), conn.respTap())
        } finally { runCatching { up.close() } }
    }

    /** Untouched pass-through (the app keeps working, PhoneWatch can't read it). */
    private fun passThrough(client: Socket, ci: InputStream, co: OutputStream, ip: String, dport: Int, prefix: ByteArray) {
        val up = Socket()
        try {
            up.connect(InetSocketAddress(ip, dport), 15_000); up.tcpNoDelay = true
            client.soTimeout = 0
            relay(client, ci, co, up, prefix, null, null)
        } finally { runCatching { up.close() } }
    }

    private fun relay(client: Socket, ci: InputStream, co: OutputStream, up: Socket, prefix: ByteArray, reqTap: Tap?, respTap: Tap?) {
        val ui = up.getInputStream(); val uo = up.getOutputStream()
        if (prefix.isNotEmpty()) { reqTap?.feed(prefix, 0, prefix.size); uo.write(prefix); uo.flush() }
        val done = java.util.concurrent.CountDownLatch(1)
        pool.execute {
            val b = ByteArray(32 * 1024)
            try { while (true) { val n = ui.read(b); if (n < 0) break; respTap?.feed(b, 0, n); co.write(b, 0, n); co.flush() } }
            catch (_: Exception) {} finally { respTap?.closed(); runCatching { client.shutdownOutput() }; done.countDown() }
        }
        val b = ByteArray(32 * 1024)
        try { while (true) { val n = ci.read(b); if (n < 0) break; reqTap?.feed(b, 0, n); uo.write(b, 0, n); uo.flush() } }
        catch (_: Exception) {} finally { reqTap?.closed(); runCatching { up.shutdownOutput() } }
        // the app half-closed: give the server up to 30 s to finish its answer, then the callers close both
        runCatching { done.await(30, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** Server name (SNI) and ALPN list from a ClientHello handshake message. */
    fun parseHello(b: ByteArray): Pair<String?, List<String>> {
        var sni: String? = null
        val alpn = ArrayList<String>()
        runCatching {
            if (b[0].toInt() != 1) return null to alpn
            var p = 4 + 2 + 32
            p += 1 + (b[p].toInt() and 0xFF)
            p += 2 + (((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF))
            p += 1 + (b[p].toInt() and 0xFF)
            val extEnd = p + 2 + (((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF))
            p += 2
            while (p + 4 <= minOf(extEnd, b.size)) {
                val type = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
                val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
                val d = p + 4
                if (type == 0 && len > 5) {
                    val nl = ((b[d + 3].toInt() and 0xFF) shl 8) or (b[d + 4].toInt() and 0xFF)
                    sni = String(b, d + 5, nl, Charsets.US_ASCII)
                } else if (type == 16 && len > 2) {
                    var q = d + 2
                    while (q < d + len) { val l = b[q].toInt() and 0xFF; alpn += String(b, q + 1, l, Charsets.US_ASCII); q += 1 + l }
                }
                p = d + len
            }
        }
        return sni to alpn
    }
}
