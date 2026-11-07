package com.phonewatch.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Telephony
import android.telecom.TelecomManager
import java.util.concurrent.ConcurrentHashMap

data class AppMeta(val uid: Int, val pkg: String, val label: String, val system: Boolean, val packages: List<String>)

/** Looks up which app owns a Linux uid, plus what its granted permissions allow it to send. */
object Apps {
    private val cache = ConcurrentHashMap<Int, AppMeta>()
    private lateinit var ctx: Context
    @Volatile private var protectedPkgs: Set<String> = emptySet()

    fun init(c: Context) { ctx = c.applicationContext; refreshProtected() }

    /** Phone, SMS and emergency apps that safe mode never blocks. */
    private val SAFE_BASE = setOf(
        "com.android.phone", "com.android.server.telecom", "com.android.providers.telephony",
        "com.android.mms", "com.android.mms.service", "com.android.dialer", "com.android.emergency",
        "com.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver", "com.google.android.dialer",
        "com.google.android.apps.messaging", "com.google.android.apps.safetyhub",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.samsung.android.dialer", "com.samsung.android.messaging", "com.samsung.android.incallui",
        "com.samsung.android.emergency", "com.sec.android.app.safetyassurance", "com.sec.imsservice",
        "com.samsung.android.app.telephonyui", "com.android.stk", "com.sec.android.app.servicemodeapp",
        "com.samsung.android.vvm", "com.samsung.android.networkstack", "com.android.networkstack",
        "com.google.android.networkstack", "com.android.captiveportallogin",
        "com.google.android.captiveportallogin", "com.android.ons", "com.samsung.android.ims",
    )

    fun refreshProtected() {
        val s = SAFE_BASE.toMutableSet()
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx)?.let { s += it } }
        runCatching { (ctx.getSystemService(TelecomManager::class.java))?.defaultDialerPackage?.let { s += it } }
        protectedPkgs = s
    }

    fun isProtected(uid: Int): Boolean {
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return true // Android core (system, DNS, radio...)
        val m = meta(uid)
        return m.packages.any { it in protectedPkgs }
    }

    fun meta(uid: Int): AppMeta = cache.getOrPut(uid) { resolve(uid) }

    private fun resolve(uid: Int): AppMeta {
        val special = when (uid) {
            -1 -> "Unknown app"
            0 -> "Android (root/kernel)"
            1000 -> "Android system"
            1001 -> "Phone radio"
            1013 -> "Media server"
            1021 -> "GPS"
            1051 -> "DNS resolver (netd)"
            1052 -> "Network stack"
            1073 -> "Network stack"
            else -> null
        }
        val pm = ctx.packageManager
        val pkgs = if (uid >= 0) runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty() else emptyList()
        if (special != null || pkgs.isEmpty())
            return AppMeta(uid, pkgs.firstOrNull() ?: "uid:$uid", special ?: "uid $uid", true, pkgs)
        // Prefer the package with a launcher icon when several share a uid
        val main = pkgs.firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: pkgs.first()
        val ai = runCatching { pm.getApplicationInfo(main, 0) }.getOrNull()
        var label = ai?.loadLabel(pm)?.toString() ?: main
        if (pkgs.size > 1 && uid < Process.FIRST_APPLICATION_UID + 100) label += " (+${pkgs.size - 1} shared)"
        val sys = ai != null && (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        return AppMeta(uid, main, label, sys, pkgs)
    }

    fun uidOf(pkg: String): Int? = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()

    fun icon(pkg: String): Drawable? = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** "Likely data" an app *could* send, based on permissions it has actually been granted. */
    data class PermData(val label: String, val detail: String)

    fun grantedData(pkg: String): List<PermData> {
        val pi: PackageInfo = runCatching {
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return emptyList()
        val req = pi.requestedPermissions ?: return emptyList()
        val flags = pi.requestedPermissionsFlags ?: IntArray(req.size)
        val granted = req.filterIndexed { i, _ -> flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.toSet()
        val requested = req.toSet()
        val out = mutableListOf<PermData>()
        fun has(vararg p: String) = p.any { it in granted }
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) out += PermData("Precise location", "GPS-level location is allowed")
        else if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) out += PermData("Approximate location", "City-level location is allowed")
        if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) out += PermData("Location in background", "Even when the app is closed")
        if (has(Manifest.permission.READ_CONTACTS)) out += PermData("Contacts", "Can read your address book")
        if (has(Manifest.permission.CAMERA)) out += PermData("Camera", "Can take photos/video while open")
        if (has(Manifest.permission.RECORD_AUDIO)) out += PermData("Microphone", "Can record audio while open")
        if (has(Manifest.permission.READ_PHONE_STATE, "android.permission.READ_PHONE_NUMBERS")) out += PermData("Phone state", "Carrier, phone number / call state")
        if (has(Manifest.permission.READ_CALL_LOG)) out += PermData("Call log", "Who you called")
        if (has(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) out += PermData("Text messages", "Can read SMS")
        if (has(Manifest.permission.READ_CALENDAR)) out += PermData("Calendar", "Can read your events")
        if (has("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", Manifest.permission.READ_EXTERNAL_STORAGE)) out += PermData("Photos & files", "Can read photos/videos")
        if (has(Manifest.permission.BODY_SENSORS)) out += PermData("Body sensors", "Heart rate etc.")
        if (has(Manifest.permission.ACTIVITY_RECOGNITION)) out += PermData("Physical activity", "Steps, walking/driving")
        if (has(Manifest.permission.BLUETOOTH_SCAN, "android.permission.NEARBY_WIFI_DEVICES")) out += PermData("Nearby devices", "Bluetooth / Wi-Fi scans (can reveal location)")
        if ("com.google.android.gms.permission.AD_ID" in requested) out += PermData("Advertising ID", "Asks for the ad ID used to track you across apps")
        if ("android.permission.PACKAGE_USAGE_STATS" in requested && usageGranted(pkg)) out += PermData("App usage", "Which apps you use and when")
        if ("android.permission.QUERY_ALL_PACKAGES" in requested) out += PermData("Installed apps list", "Can see every app you have")
        if (has(Manifest.permission.GET_ACCOUNTS)) out += PermData("Accounts", "Accounts on the phone")
        return out
    }

    private fun usageGranted(pkg: String): Boolean = runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, ai.uid, pkg) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Domain belongs to the company that makes this app? (e.g. nytimes.com for com.nytimes.android) */
    fun isOwnServer(pkg: String, host: String?, company: String?, cats: Int = 0): Boolean {
        if (host == null) return false
        val parts = pkg.split('.')
        // Same parent company but an ad/analytics host (e.g. Chrome -> doubleclick.net) is NOT "own servers"
        val adLike = cats and (Cat.ADS.bit or Cat.ANALYTICS.bit or Cat.TRACKING.bit or Cat.LOCATION.bit) != 0
        if (parts.size >= 2) {
            val brand = parts[1].lowercase()
            if (brand.length >= 3 && brand !in setOf("android", "google", "app", "apps", "mobile", "samsung", "sec")) {
                val labels = host.lowercase().split('.')
                if (labels.size >= 2 && labels.dropLast(1).any { it == brand }) return true
            }
        }
        if (adLike) return false
        val owner = TrackerDb.appOwners[pkg]
        if (owner != null && owner == company) return true
        if (parts.size >= 2) {
            if (parts[1] == "google" && company == "Google") return true
            if (parts[1] in setOf("samsung", "sec") && company == "Samsung") return true
            if (parts[1] == "facebook" && company == "Meta (Facebook)") return true
        }
        return false
    }
}
