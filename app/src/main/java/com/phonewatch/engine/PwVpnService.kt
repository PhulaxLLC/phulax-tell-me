package com.phonewatch.engine

/*
 * PhoneWatch local VPN service. The packet engine (libnetguard, app/src/main/cpp/netguard) is
 * NetGuard's native code by Marcel Bokhorst (M66B), GPL-3.0-or-later. This class re-implements the
 * Java side of NetGuard's ServiceSinkhole callbacks for PhoneWatch. Nothing is sent to any outside
 * server: traffic is terminated and re-sent from the phone itself.
 */

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.phonewatch.MainActivity
import com.phonewatch.R
import com.phonewatch.data.Apps
import com.phonewatch.data.Cat
import com.phonewatch.data.Conn
import com.phonewatch.data.Prefs
import com.phonewatch.data.Repo
import com.phonewatch.data.RuleEngine
import com.phonewatch.data.TrackerDb
import com.phonewatch.inspect.DeepInspect
import com.phonewatch.inspect.MitmProxy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class PwVpnService : VpnService() {

    companion object {
        private const val TAG = "PW.Vpn"
        const val ACTION_START = "com.phonewatch.START"
        const val ACTION_STOP = "com.phonewatch.STOP"
        const val ACTION_PAUSE = "com.phonewatch.PAUSE"
        const val ACTION_RESUME = "com.phonewatch.RESUME"
        const val ACTION_DEEP_OFF = "com.phonewatch.DEEP_OFF"
        private const val DEEP_NOTIF_ID = 8
        private const val DEEP_CHANNEL = "deep"
        const val VDNS = "10.1.10.53"           // virtual DNS server inside the tunnel
        private const val NOTIF_ID = 7
        private const val CHANNEL = "watch"

        val running = MutableStateFlow(false)
        @Volatile private var instance: PwVpnService? = null

        init { System.loadLibrary("netguard") }

        fun start(c: Context) {
            Prefs.vpnWanted = true
            c.startForegroundService(Intent(c, PwVpnService::class.java).setAction(ACTION_START))
        }
        fun stop(c: Context) {
            Prefs.vpnWanted = false
            c.startService(Intent(c, PwVpnService::class.java).setAction(ACTION_STOP))
        }
        /** Rules/settings changed: new decisions apply to every new connection straight away. */
        fun reload(@Suppress("UNUSED_PARAMETER") c: Context) {
            RuleEngine.clearCache()
            instance?.updateNotification()
        }
        /** Deep Inspect switched on/off. */
        fun deepChanged() { instance?.let { if (DeepInspect.enabled) it.startDeep() else it.stopDeep() } }
        fun dnsHost(uid: Int, ip: String): String? = instance?.hostFor(uid, ip)
        fun cnameChain(host: String?): String? = instance?.chainOf(host)
    }

    // ---- native engine (NetGuard) ----
    external fun jni_init(sdk: Int): Long
    external fun jni_start(context: Long, loglevel: Int)
    external fun jni_run(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    external fun jni_stop(context: Long)
    external fun jni_clear(context: Long)
    external fun jni_get_mtu(): Int
    external fun jni_done(context: Long)
    external fun jni_mitm(port: Int)

    private var ctx = 0L
    private var tun: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private var notifJob: Job? = null
    private lateinit var cm: ConnectivityManager
    @Volatile private var upstreamDns: String = "9.9.9.9"
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // ---- DNS knowledge used to name IP addresses ----
    private val ipHost = object : LinkedHashMap<String, String>(4096, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 8000
    }
    private val cnames = object : LinkedHashMap<String, MutableSet<String>>(1024, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, MutableSet<String>>?) = size > 4000
    }
    /** "uid|ip|port|proto" -> conn id, and "uid|ip" -> most recent conn id (for SNI + byte counts). */
    private val open = ConcurrentHashMap<String, Long>()
    private val lastByIp = ConcurrentHashMap<String, Long>()
    private var sniName: String? = null
    private var sniConn: Long = 0
    private var sniUid: Int = -1

    override fun onCreate() {
        super.onCreate()
        instance = this
        cm = getSystemService(ConnectivityManager::class.java)
        ctx = jni_init(Build.VERSION.SDK_INT)
        ConnLogger.start()
        watchUnderlyingDns()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_PAUSE -> { Prefs.pausedUntil = System.currentTimeMillis() + 15 * 60_000; reload(this) }
            ACTION_RESUME -> { Prefs.pausedUntil = 0; Prefs.blocking = true; reload(this) }
            ACTION_DEEP_OFF -> { DeepInspect.enabled = false; stopDeep() }
            else -> {} // START, always-on (null intent / android.net.VpnService)
        }
        startForegroundNow()
        if (tun == null) startVpn()
        return START_STICKY
    }

    private fun startForegroundNow() {
        val n = buildNotification("Starting…")
        ServiceCompat.startForeground(this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun startVpn() {
        try {
            if (VpnService.prepare(this) != null) { Log.w(TAG, "VPN permission missing"); shutdown(); return }
            val b = Builder()
                .setSession("Phulax Android Tell Me")
                .addAddress("10.1.10.1", 32)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("2000::", 3)
                .addDnsServer(VDNS)
                .setMtu(jni_get_mtu())
                .addDisallowedApplication(packageName) // PhoneWatch's own sockets go straight out
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            b.setMetered(false)
            tun = b.establish()
            val fd = tun ?: run { Log.e(TAG, "establish failed (another VPN active?)"); shutdown(); return }
            jni_start(ctx, Log.ERROR)
            thread = Thread({
                Log.i(TAG, "tunnel running")
                jni_run(ctx, fd.fd, true, 3 /* NXDOMAIN */)
                Log.i(TAG, "tunnel exited")
            }, "pw-tunnel").also { it.start() }
            running.value = true
            startNotificationUpdates()
            if (DeepInspect.enabled) startDeep()
        } catch (e: Throwable) {
            Log.e(TAG, "start", e); shutdown()
        }
    }

    private fun stopNative() {
        val t = thread ?: return
        jni_stop(ctx)
        runCatching { t.join(5000) }
        thread = null
        jni_clear(ctx)
    }

    private fun shutdown() {
        notifJob?.cancel()
        stopDeep()
        stopNative()
        runCatching { tun?.close() }
        tun = null
        running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took over, or the user turned us off in system settings
        Prefs.vpnWanted = false
        shutdown()
    }

    override fun onDestroy() {
        stopDeep()
        stopNative()
        runCatching { tun?.close() }
        tun = null
        running.value = false
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        if (ctx != 0L) { jni_done(ctx); ctx = 0 }
        instance = null
        super.onDestroy()
    }

    // ---- underlying (real) network DNS, used as upstream for the virtual DNS server ----
    private fun watchUnderlyingDns() {
        cm.activeNetwork?.let { n -> cm.getLinkProperties(n)?.let { pickDns(it) } }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { pickDns(lp) }
        }
        netCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun pickDns(lp: LinkProperties) {
        val list = lp.dnsServers
        val v4 = list.firstOrNull { it is Inet4Address } ?: list.firstOrNull()
        v4?.hostAddress?.let { if (it != VDNS) { upstreamDns = it; Log.i(TAG, "upstream DNS $it") } }
    }

    // =================== callbacks from native code (packet thread) ===================

    @Suppress("unused")
    fun nativeExit(reason: String?) { Log.w(TAG, "native exit $reason") }

    @Suppress("unused")
    fun nativeError(error: Int, message: String?) { Log.w(TAG, "native error $error $message") }

    @Suppress("unused")
    fun logPacket(packet: Packet) { /* blocked DNS answers are logged in isDomainBlocked */ }

    @Suppress("unused")
    fun getUidQ(version: Int, protocol: Int, saddr: String, sport: Int, daddr: String, dport: Int): Int {
        if (protocol != 6 && protocol != 17) return Process.INVALID_UID
        return try {
            cm.getConnectionOwnerUid(protocol, InetSocketAddress(InetAddress.getByName(saddr), sport),
                InetSocketAddress(InetAddress.getByName(daddr), dport))
        } catch (e: Exception) { Process.INVALID_UID }
    }

    @Suppress("unused")
    fun dnsResolved(rr: ResourceRecord) {
        val q = rr.QName?.lowercase() ?: return
        val ip = rr.Resource ?: return
        if (rr.TTL == -1) {
            // TLS ClientHello server name (SNI) for an outgoing connection
            sniName = q; sniUid = rr.uid
            sniConn = lastByIp["${rr.uid}|$ip"] ?: 0
            synchronized(ipHost) { ipHost["${rr.uid}|$ip"] = q }
            return
        }
        val a = rr.AName?.lowercase()
        synchronized(cnames) { if (a != null && a != q) cnames.getOrPut(q) { HashSet() }.add(a) }
        if (ip.isEmpty()) return
        // Don't remember names whose answer we are about to sinkhole (avoids blocking shared IPs)
        if (RuleEngine.decide(rr.uid, q, cnamesOf(q)).blocked) return
        synchronized(ipHost) { ipHost[ip] = q; if (rr.uid >= 0) ipHost["${rr.uid}|$ip"] = q }
    }

    private val lastBlockLog = HashMap<String, Long>()
    /** Apps retry blocked lookups many times a second; log each (app, server) at most every 10 s. */
    private fun recentBlock(key: String): Boolean {
        val now = System.currentTimeMillis()
        val t = lastBlockLog[key]
        if (t != null && now - t < 10_000) return true
        if (lastBlockLog.size > 2000) lastBlockLog.clear()
        lastBlockLog[key] = now
        return false
    }

    // ---- Deep Inspect ----
    fun startDeep() {
        if (thread == null) return
        MitmProxy.dnsName = { uid, ip -> hostFor(uid, ip) }
        val port = runCatching { MitmProxy.start() }.getOrElse { Log.e(TAG, "proxy", it); return }
        jni_mitm(port)
        Repo.io.launch { DeepInspect.loadStatus() }
        showDeepNotification()
    }

    fun stopDeep() {
        runCatching { jni_mitm(0) }
        MitmProxy.stop()
        getSystemService(NotificationManager::class.java).cancel(DEEP_NOTIF_ID)
    }

    private fun showDeepNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(DEEP_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(DEEP_CHANNEL, "Deep Inspect is on", NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(true); setSound(null, null) })
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 4, Intent(this, PwVpnService::class.java).setAction(ACTION_DEEP_OFF), PendingIntent.FLAG_IMMUTABLE)
        val text = "Phulax Android Tell Me is decrypting web traffic of the apps you chose, on this phone only. Anyone holding the CA key could read traffic: turn it off when you don't need it."
        nm.notify(DEEP_NOTIF_ID, NotificationCompat.Builder(this, DEEP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Deep Inspect is ON")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Turn off Deep Inspect", off).build())
    }

    /** "a.com > b.net > c.cdn.com": follows the CNAME records seen in DNS answers. */
    fun chainOf(host: String?): String? {
        if (host == null) return null
        val out = mutableListOf(host)
        var cur: String = host
        synchronized(cnames) {
            for (i in 0 until 8) {
                val next = cnames[cur]?.firstOrNull { it !in out } ?: break
                out += next; cur = next
            }
        }
        return if (out.size > 1) out.joinToString(" > ") else null
    }

    private fun cnamesOf(q: String): Collection<String> = synchronized(cnames) { cnames[q]?.toList() } ?: emptyList()

    fun hostFor(uid: Int, ip: String): String? = synchronized(ipHost) { ipHost["$uid|$ip"] ?: ipHost[ip] }

    @Suppress("unused")
    fun isDomainBlocked(name: String?, uid: Int): Boolean {
        val host = name?.lowercase() ?: return false
        if (host == sniName && uid == sniUid) {
            // SNI check for a TLS connection: refine the logged connection with the real server name
            sniName = null
            val d = RuleEngine.decide(uid, host)
            if (sniConn != 0L) {
                val hit = TrackerDb.lookup(host)
                ConnLogger.send(ConnLogger.Op.Host(sniConn, host, hit?.company?.name, catsFor(uid, host, hit), "sni", d.blocked, chainOf(host)))
            }
            return d.blocked
        }
        val d = RuleEngine.decide(uid, host, cnamesOf(host))
        if (d.blocked && recentBlock("$uid|$host")) return true
        if (d.blocked) {
            val hit = TrackerDb.lookup(host) ?: cnamesOf(host).firstNotNullOfOrNull { TrackerDb.lookup(it) }
            log(uid, host, "", 53, 17, true, hit, "dns")
        }
        return d.blocked
    }

    @Suppress("unused")
    fun isAddressAllowed(p: Packet): Allowed? {
        val allow = Allowed()
        // DNS: forward lookups made to the virtual DNS server to the real network's DNS server
        if (p.protocol == 17 && p.dport == 53) return if (p.daddr == VDNS) Allowed(upstreamDns, 53) else allow
        // Stop "Private DNS" (DNS-over-TLS) probes to our virtual server so lookups stay visible
        if (p.protocol == 6 && p.dport == 853 && p.daddr == VDNS) return null
        // QUIC (HTTP/3 over UDP 443) hides the server name; refusing it makes apps fall back to TLS/TCP
        if (p.protocol == 17 && p.dport == 443) return null
        if (p.daddr.startsWith("ff0") || p.daddr.startsWith("fe80") || p.daddr.startsWith("224.") || p.daddr == "255.255.255.255")
            return allow // local multicast / link-local housekeeping, not app data
        val host = hostFor(p.uid, p.daddr)
        val d = RuleEngine.decide(p.uid, host, if (host != null) cnamesOf(host) else emptyList())
        when (p.data) {
            "chk", "sni" -> return if (d.blocked) null else allow
        }
        if (p.protocol == 6 && p.flags?.contains('S') != true) return if (d.blocked) null else allow
        val hit = TrackerDb.lookup(host)
        val id = log(p.uid, host, p.daddr, p.dport, p.protocol, d.blocked, hit, if (host != null) "dns" else "ip")
        open["${p.uid}|${p.daddr}|${p.dport}|${p.protocol}"] = id
        lastByIp["${p.uid}|${p.daddr}"] = id
        if (open.size > 6000) open.clear()
        if (lastByIp.size > 6000) lastByIp.clear()
        if (d.blocked) return null
        // Deep Inspect: hand this app's web connection to the on-phone proxy (skip known "can't inspect" ones)
        if (p.protocol == 6 && DeepInspect.intercept(p.uid, p.dport) && !DeepInspect.cantInspect(p.uid, host))
            return Allowed("127.0.0.1", MitmProxy.port)
        return allow
    }

    @Suppress("unused")
    fun accountUsage(u: Usage) {
        val id = open["${u.Uid}|${u.DAddr}|${u.DPort}|${u.Protocol}"] ?: return
        ConnLogger.send(ConnLogger.Op.Bytes(id, u.Sent, u.Received))
    }

    private fun catsFor(uid: Int, host: String?, hit: com.phonewatch.data.Hit?): Int {
        val pkg = Apps.meta(uid).pkg
        if (Apps.isOwnServer(pkg, host, hit?.company?.name, hit?.cats ?: 0)) return Cat.OWN.bit
        return hit?.cats?.takeIf { it != 0 } ?: Cat.UNKNOWN.bit
    }

    private fun log(uid: Int, host: String?, ip: String, port: Int, proto: Int, blocked: Boolean,
                    hit: com.phonewatch.data.Hit?, via: String): Long {
        val id = ConnLogger.nextId()
        val meta = Apps.meta(uid)
        ConnLogger.send(ConnLogger.Op.Insert(Conn(
            id = id, time = System.currentTimeMillis(), uid = uid, pkg = meta.pkg, host = host, ip = ip,
            port = port, proto = proto, blocked = blocked, company = hit?.company?.name,
            cats = catsFor(uid, host, hit), via = via, cname = chainOf(host))))
        return id
    }

    // ---- notification ----
    private fun startNotificationUpdates() {
        notifJob?.cancel()
        notifJob = Repo.io.launch {
            var n = 0
            while (true) {
                updateNotification()
                if (n % 360 == 0) Repo.db.conn().deleteBefore(System.currentTimeMillis() - Repo.RETENTION_MS) // every ~6 h
                if (n++ % 60 == 0) DeepInspect.purgeOld() // captured content: every hour, older than the chosen days
                delay(60_000)
            }
        }
    }

    fun updateNotification() {
        Repo.io.launch {
            val since = Repo.startOfToday()
            val t = runCatching {
                Repo.db.query("SELECT COUNT(*), IFNULL(SUM(blocked),0) FROM conn WHERE time >= ?", arrayOf<Any>(since)).use {
                    if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else 0 to 0
                }
            }.getOrDefault(0 to 0)
            val state = when {
                !Prefs.blocking -> "Watching only (blocking off)"
                !Prefs.blockingActive() -> "Blocking paused"
                else -> "Watching + blocking"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification("$state · today ${t.first} connections, ${t.second} blocked"))
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phulax Android Tell Me running", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, PwVpnService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Phulax Android Tell Me is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (Prefs.blockingActive()) b.addAction(0, "Pause blocking 15 min", action(ACTION_PAUSE, 1))
        else b.addAction(0, "Resume blocking", action(ACTION_RESUME, 2))
        b.addAction(0, "Turn off", action(ACTION_STOP, 3))
        return b.build()
    }
}
