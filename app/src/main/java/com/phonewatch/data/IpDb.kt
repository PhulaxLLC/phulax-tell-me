package com.phonewatch.data

import android.content.Context
import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer

/** Where an IP address is and who runs its network. */
data class IpInfo(
    val country: String?, val region: String?, val city: String?,
    val asn: Int?, val asOrg: String?, val hosting: String?,
) {
    fun place(): String = listOfNotNull(city?.takeIf { it.isNotEmpty() }, region?.takeIf { it.isNotEmpty() && it != city }, country).joinToString(", ").ifEmpty { "unknown" }
    fun network(): String = if (asn != null) "AS$asn ${asOrg ?: ""}".trim() else "unknown network"
}

/**
 * Offline IP -> country/city + ASN lookups, from the bundled DB-IP Lite data (CC BY 4.0,
 * "IP Geolocation by DB-IP", https://db-ip.com). No online lookups. Built by tools/build_ipdb.py.
 * IPv6 city is by /32 network (coarse); IPv4 city and all ASN data are per published range.
 */
object IpDb {
    private const val TAG = "PW.IpDb"
    @Volatile private var buf: ByteBuffer? = null
    private lateinit var strOff: IntArray
    private var strBase = 0
    private lateinit var locs: IntArray        // 3 string ids per loc
    private lateinit var ases: IntArray        // asn, org string id
    private val tables = arrayOfNulls<Table>(4) // city4, city6, asn4, asn6
    private val cache = object : LinkedHashMap<String, IpInfo>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IpInfo>?) = size > 5000
    }

    private class Table(val keyBits: Int, val first: LongArray, val off: IntArray, val dataStart: Int, val dataEnd: Int)

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (buf != null) return true
        return runCatching {
            val bytes = ctx.assets.open("data/ipdb.bin").use { it.readBytes() }
            val b = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4); b.get(magic)
            require(String(magic) == "PWIP" && b.int == 2)
            val ns = b.int
            strOff = IntArray(ns + 1) { b.int }
            strBase = b.position(); b.position(strBase + strOff[ns])
            val nl = b.int; locs = IntArray(nl * 3) { b.int }
            val na = b.int; ases = IntArray(na * 2) { b.int }
            for (t in 0 until 4) {
                val bits = b.get().toInt(); b.int /* entries */; val nb = b.int
                val first = LongArray(nb); val off = IntArray(nb)
                for (i in 0 until nb) { first[i] = b.long; off[i] = b.int }
                val dl = b.int; val start = b.position(); b.position(start + dl)
                tables[t] = Table(bits, first, off, start, start + dl)
            }
            buf = b
            true
        }.onFailure { Log.w(TAG, "load", it) }.getOrDefault(false)
    }

    private fun str(i: Int): String {
        val b = buf!!
        val s = strOff[i]; val e = strOff[i + 1]
        return String(b.array(), strBase + s, e - s, Charsets.UTF_8)
    }

    private fun varint(b: ByteBuffer): Long {
        var shift = 0; var r = 0L
        while (true) {
            val x = b.get().toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }

    /** Returns value+1 (0 = no data) for [key] in table [t]. Keys are compared unsigned. */
    private fun find(t: Table, key: Long): Int {
        val first = t.first
        var lo = 0; var hi = first.size - 1; var blk = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (java.lang.Long.compareUnsigned(first[mid], key) <= 0) { blk = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (blk < 0) return 0
        val b = buf!!.duplicate()
        b.position(t.dataStart + t.off[blk])
        val end = if (blk + 1 < t.off.size) t.dataStart + t.off[blk + 1] else t.dataEnd
        var k = first[blk]
        var v = varint(b).toInt()
        while (b.position() < end) {
            val save = b.position()
            val nk = k + varint(b)
            if (java.lang.Long.compareUnsigned(nk, key) > 0) { b.position(save); break }
            k = nk; v = varint(b).toInt()
        }
        return v
    }

    fun lookup(ctx: Context, ip: String?): IpInfo? {
        if (ip.isNullOrEmpty()) return null
        synchronized(cache) { cache[ip]?.let { return it } }
        if (!load(ctx)) return null
        val info = runCatching {
            val a = InetAddress.getByName(ip).address
            val v4 = a.size == 4
            val city = tables[if (v4) 0 else 1]!!
            val asn = tables[if (v4) 2 else 3]!!
            fun key(bits: Int): Long {
                var k = 0L
                for (i in 0 until bits / 8) k = (k shl 8) or (a[i].toLong() and 0xFF)
                return k
            }
            val lv = find(city, key(city.keyBits))
            val av = find(asn, key(asn.keyBits))
            val (cc, rg, ct) = if (lv > 0) Triple(str(locs[(lv - 1) * 3]), str(locs[(lv - 1) * 3 + 1]), str(locs[(lv - 1) * 3 + 2])) else Triple(null, null, null)
            val asnNum = if (av > 0) ases[(av - 1) * 2] else null
            val org = if (av > 0) str(ases[(av - 1) * 2 + 1]) else null
            IpInfo(cc, rg, ct, asnNum, org, hostingOf(org))
        }.getOrNull() ?: return null
        synchronized(cache) { cache[ip] = info }
        return info
    }

    private val HOSTS = listOf(
        "amazon" to "Amazon Web Services", "google" to "Google Cloud", "microsoft" to "Microsoft Azure",
        "cloudflare" to "Cloudflare", "akamai" to "Akamai", "fastly" to "Fastly", "digitalocean" to "DigitalOcean",
        "ovh" to "OVHcloud", "hetzner" to "Hetzner", "oracle" to "Oracle Cloud", "alibaba" to "Alibaba Cloud",
        "tencent" to "Tencent Cloud", "linode" to "Akamai (Linode)", "facebook" to "Meta", "meta platforms" to "Meta",
        "edgecast" to "Edgio", "edgio" to "Edgio", "limelight" to "Edgio", "stackpath" to "StackPath", "vultr" to "Vultr",
        "leaseweb" to "Leaseweb", "bunny" to "BunnyCDN", "cdn77" to "CDN77", "incapsula" to "Imperva", "imperva" to "Imperva",
        "zscaler" to "Zscaler", "apple" to "Apple", "bytedance" to "ByteDance", "byteplus" to "ByteDance", "huawei" to "Huawei Cloud",
        "ibm" to "IBM Cloud", "salesforce" to "Salesforce", "twitter" to "X (Twitter)", "netflix" to "Netflix", "samsung" to "Samsung",
    )

    /** Hosting / cloud provider guessed from the network owner's name. */
    fun hostingOf(org: String?): String? {
        val o = org?.lowercase() ?: return null
        return HOSTS.firstOrNull { o.contains(it.first) }?.second
    }
}
