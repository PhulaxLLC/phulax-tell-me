package com.phonewatch.scan

import com.phonewatch.debloat.Shell
import java.util.Calendar

/** Who has actually touched this phone, read from Android's own Bluetooth and WiFi history through Shizuku (ADB-level, no root).
 *  Bluetooth: every connection (ACL link) to the phone, matched against the paired-device list, so an unpaired device stands out.
 *  WiFi: every disconnect the router side caused, with its reason code. Reason 6/7 or a burst of them is what deauth attacks look like.
 *  Android masks the first 4 bytes of Bluetooth addresses in these dumps, so devices are matched on the last 2. */
object Contacts {
    data class Bt(val addr: String, val name: String?, val paired: Boolean, val classic: Boolean, val times: List<Long>,
                  val fullAddr: String? = null, val type: String? = null, val profiles: String? = null, val raw: List<String> = emptyList())
    data class Drop(val t: Long, val reason: Int, val reasonName: String, val rssi: Int?, val suspicious: Boolean)
    data class Report(val bt: List<Bt>, val drops: List<Drop>, val deauthBurst: Boolean, val since: Long?, val error: String? = null)

    private val ACL = Regex("""(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.\d+--BluetoothRemoteDevices -- ACTION_ACL_CONNECTED, device is ([0-9A-Fa-fX:]{17}), transport is (\d)""")
    private val BONDED = Regex("""^\s+([0-9A-Fa-fX:]{17}) \[[^\]]*]\[[^\]]*] (.+?) \(([^)]*)""", RegexOption.MULTILINE)
    private val NAME = Regex("""name:"([^"]*)"""")
    private val COD = Regex("""cod:([0-9a-fA-F]+)-([0-9a-fA-F]+)-([0-9a-fA-F]+)""")
    private val FULL = Regex("""\[((?:[0-9a-f]{2}:){5}[0-9a-f]{2})]""")

    // Bluetooth Class of Device major classes (Bluetooth SIG Assigned Numbers 2.8)
    private fun major(m: Int) = when (m) {
        1 -> "Computer"; 2 -> "Phone"; 3 -> "Network access point"; 4 -> "Audio / video (speaker, headset, car, radio)"
        5 -> "Peripheral (keyboard, mouse, remote)"; 6 -> "Imaging (camera, printer, scanner)"; 7 -> "Wearable (watch, glasses)"
        8 -> "Toy"; 9 -> "Health device"; else -> "Unclassified"
    }

    /** Everything the Bluetooth dump says about one device (lines that mention its address). */
    private fun details(dump: String, addrTail: String): Triple<String?, String?, List<String>> {
        val t = addrTail.lowercase()
        val lines = dump.lineSequence().map { it.trim() }.filter { it.lowercase().contains(t) && !it.contains("ACTION_ACL_") && !it.contains("Acl is not connected") }
            .distinct().sortedByDescending { l -> listOf("name:", "cod:", " | ", "[BR/EDR]", "[ DUAL ]", "[ LE ]").count { l.contains(it) } }.take(25).toList()
        val full = lines.firstNotNullOfOrNull { l -> FULL.find(l)?.groupValues?.get(1)?.takeIf { it.endsWith(t) } }
        val type = lines.firstNotNullOfOrNull { COD.find(it) }?.let { major(it.groupValues[2].toInt(16)) }
        return Triple(full?.uppercase(), type, lines)
    }
    private fun nameIn(lines: List<String>) = lines.firstNotNullOfOrNull { NAME.find(it)?.groupValues?.get(1)?.takeIf { n -> n.isNotBlank() } }
    private val DROP = Regex("""(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.\d+ NETWORK_DISCONNECTION_EVENT local_gen=false reason=(-?\d+):(\w+)(?: lastRssi=(-?\d+))?""")

    // 802.11 reason codes that a forged deauth/disassoc frame normally carries
    private val DEAUTH_REASONS = setOf(6, 7)

    private fun time(mo: String, d: String, h: String, mi: String, s: String): Long {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { set(now.get(Calendar.YEAR), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt()); set(Calendar.MILLISECOND, 0) }
        if (c.after(now)) c.add(Calendar.YEAR, -1)    // dumps have no year
        return c.timeInMillis
    }

    private fun tail(a: String) = a.uppercase().takeLast(5)

    fun read(): Report {
        val btOut = Shell.run("dumpsys", "bluetooth_manager", log = false)
        val wifiOut = Shell.run("dumpsys", "wifi", log = false)
        if (btOut.code != 0 && wifiOut.code != 0) return Report(emptyList(), emptyList(), false, null, btOut.out.take(200))

        val bondLines = BONDED.findAll(btOut.out).toList()
        val bonded = bondLines.associate { tail(it.groupValues[1]) to it.groupValues[2].trim() }
        val profiles = bondLines.associate { tail(it.groupValues[1]) to it.groupValues[3].trim() }
        val bt = ACL.findAll(btOut.out).map { m ->
            val g = m.groupValues
            Triple(g[6].uppercase(), g[7] == "1", time(g[1], g[2], g[3], g[4], g[5]))
        }.groupBy { it.first }.map { (addr, list) ->
            val (full, type, raw) = details(btOut.out, tail(addr))
            Bt(addr, bonded[tail(addr)] ?: nameIn(raw), tail(addr) in bonded, list.first().second, list.map { it.third }.sorted(),
                full, type, profiles[tail(addr)], raw)
        }.sortedWith(compareBy<Bt> { it.paired }.thenByDescending { it.times.last() })

        val drops = DROP.findAll(wifiOut.out).map { m ->
            val g = m.groupValues
            val r = g[6].toInt()
            Drop(time(g[1], g[2], g[3], g[4], g[5]), r, g[7], g[8].toIntOrNull(), r in DEAUTH_REASONS)
        }.toList().sortedByDescending { it.t }
        // 3+ router-side drops inside 10 minutes = burst (weak signal drops are excluded)
        val strong = drops.filter { (it.rssi ?: -100) > -75 }.map { it.t }.sorted()
        val burst = strong.indices.any { i -> i + 2 < strong.size && strong[i + 2] - strong[i] <= 10 * 60_000L }

        val since = (bt.flatMap { it.times } + drops.map { it.t }).minOrNull()
        return Report(bt, drops, burst, since)
    }

    /** Things that happened after [after], worth an alert: an unpaired device connecting, or a deauth-looking drop. */
    fun newAlerts(r: Report, after: Long): List<Triple<String, String, String>> = buildList {
        r.bt.filter { !it.paired }.forEach { b ->
            val n = b.times.count { it > after }
            if (n > 0 && Radar.firstKnown("conn:${b.fullAddr ?: b.addr}")) add(Triple("Unknown Bluetooth device connected to your phone",
                "${b.name ?: "Device"} ending ${b.addr.takeLast(5)} (${if (b.classic) "classic Bluetooth" else "Bluetooth LE"}) connected $n time(s) and is not one of your paired devices.",
                Voice.say(Voice.Phrase.UNKNOWN_BT, name = b.name) + if (Voice.lang == "en") " Possible security risk. " + Voice.gathers(b) else ""))
        }
        r.drops.filter { it.suspicious && it.t > after }.forEach { d ->
            add(Triple("WiFi kicked off (possible deauth attack)",
                "The network side disconnected you with reason ${d.reason} (${d.reasonName}). Forged disconnect frames usually carry this code. It can also be a router glitch.",
                Voice.say(Voice.Phrase.DEAUTH)))
        }
    }
}
