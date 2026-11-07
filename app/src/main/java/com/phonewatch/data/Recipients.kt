package com.phonewatch.data

import android.content.Context

/** A server (domain, or bare IP) that received data from one or more apps, fully identified. */
data class Recipient(
    val key: String,
    val host: String?,
    val ip: String,
    val company: String?,
    val parent: String?,
    val cats: Int,
    val rows: List<RecipRow>,       // one per app
    val n: Int,
    val up: Long,
    val down: Long,
    val blocked: Int,
    val firstTime: Long,
    val lastTime: Long,
    val cname: String?,
    val info: IpInfo?,
) {
    val apps get() = rows.size
    val bytes get() = up + down
}

object Recipients {
    fun parentOf(company: String?): String? = company?.let { TrackerDb.companies[it]?.legal?.takeIf { l -> l.isNotBlank() && l != it } }

    /** CNAME-cloaked tracker: the visible name looks first-party but the chain ends at a tracker company. */
    fun cloakedBy(cname: String?): String? {
        val chain = cname?.split(" > ") ?: return null
        if (chain.size < 2) return null
        val first = TrackerDb.lookup(chain[0])?.company?.name
        // only a tracker at the end of the chain counts; a CDN (Fastly, Akamai, CloudFront...) is ordinary hosting
        return chain.drop(1).firstNotNullOfOrNull { TrackerDb.lookup(it)?.takeIf { h -> h.cats and Cat.TRACKER_MASK != 0 } }?.company?.name?.takeIf { it != first }
    }

    fun build(ctx: Context, rows: List<RecipRow>): List<Recipient> =
        rows.groupBy { it.host ?: it.ip }.map { (key, l) ->
            val ip = l.maxByOrNull { it.lastTime }?.ip?.takeIf { it.isNotEmpty() } ?: l.firstOrNull { it.ip.isNotEmpty() }?.ip ?: ""
            val company = l.firstNotNullOfOrNull { it.company }
            Recipient(
                key = key, host = l.first().host, ip = ip, company = company, parent = parentOf(company),
                cats = l.fold(0) { a, r -> a or r.cats }, rows = l.sortedByDescending { it.up + it.down },
                n = l.sumOf { it.n }, up = l.sumOf { it.up }, down = l.sumOf { it.down }, blocked = l.sumOf { it.blocked },
                firstTime = l.minOf { it.firstTime }, lastTime = l.maxOf { it.lastTime },
                cname = l.firstNotNullOfOrNull { it.cname }, info = IpDb.lookup(ctx, ip),
            )
        }
}
