package com.phonewatch.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.le.ScanResult
import kotlin.math.pow
import kotlin.math.roundToInt

/** What kind of thing a Bluetooth device or WiFi network probably is. say = how the voice names it. risky = flagged as a security risk. */
enum class DevType(val say: String, val label: String, val risky: Boolean = false) {
    PHONE_ANDROID("Android phone", "Android phone"), PHONE_APPLE("iPhone or other Apple device", "iPhone / Apple device"),
    EARBUDS("earbuds or headphones", "Earbuds / headphones"), AIRPODS("AirPods or Beats earbuds", "AirPods / Beats"),
    SPEAKER("speaker", "Speaker"), TV("TV", "TV"), WATCH_ANDROID("Android watch", "Android watch"), WATCH_APPLE("Apple Watch", "Apple Watch"),
    WATCH("smartwatch or fitness band", "Watch / fitness band"), LAPTOP("laptop or computer", "Laptop / computer"),
    CAMERA("camera", "Camera", risky = true), SECURITY("home security device", "Security device (alarm, lock)"),
    VEHICLE("vehicle", "Vehicle"), PRINTER("printer", "Printer"), PHONE_HOTSPOT("phone hotspot", "Phone hotspot"),
    UNKNOWN("device", ""),
}

/** A guess, with make and model for vehicles when the name gives them away. */
data class Guess(val type: DevType, val make: String? = null, val model: String? = null) {
    /** "Ford F-150 vehicle", "Tesla vehicle", or the type's plain word. */
    fun say(wifi: Boolean): String {
        if (type == DevType.VEHICLE) return listOfNotNull(make, model, if (wifi) "vehicle hotspot" else "vehicle").joinToString(" ")
        if (wifi && type == DevType.UNKNOWN) return "network"
        if (wifi && type == DevType.CAMERA) return "camera network"
        return type.say
    }
    fun label(): String = if (type == DevType.VEHICLE) listOfNotNull("Vehicle", listOfNotNull(make, model).joinToString(" ").ifBlank { null }).joinToString(": ") else type.label
}

object Guesser {
    // Car makers, in the spelling that shows up in car Bluetooth and hotspot names. Infotainment brand names map to their maker.
    private val MAKES = listOf("Tesla", "Ford", "Lincoln", "Chevrolet", "Chevy", "GMC", "Buick", "Cadillac", "Toyota", "Lexus", "Honda", "Acura",
        "Nissan", "Infiniti", "Hyundai", "Kia", "Genesis", "Subaru", "Mazda", "Mitsubishi", "BMW", "Mini", "Mercedes", "Audi", "Volkswagen", "VW",
        "Porsche", "Volvo", "Polestar", "Jeep", "Ram", "Dodge", "Chrysler", "Rivian", "Lucid", "Jaguar", "Land Rover", "Range Rover", "Fiat")
    private val MAKE_RX = Regex("(?i)\\b(" + MAKES.joinToString("|") { Regex.escape(it).replace(" ", "\\s?") } + ")\\b\\s*(.*)")
    private val INFOTAINMENT = listOf(Regex("(?i)uconnect") to "Jeep, Dodge, Chrysler or Ram", Regex("(?i)\\bsync\\b") to "Ford",
        Regex("(?i)mylink|onstar|mychevrolet") to "Chevrolet or GM", Regex("(?i)entune|toyota connect") to "Toyota",
        Regex("(?i)hondalink") to "Honda", Regex("(?i)\\bmbux\\b|mercedes me") to "Mercedes", Regex("(?i)\\bidrive\\b") to "BMW",
        Regex("(?i)car ?multimedia|carplay|android auto|\\bcar\\b|my car|\\bhum\\b|dashlink") to null)
    private val NAMES = listOf(
        Regex("(?i)airpods|powerbeats|beats (fit|studio|solo|flex)") to DevType.AIRPODS,
        Regex("(?i)buds|earbud|headphone|headset|earphone|jabra|bose|\\bwh-|\\bwf-|soundcore|skullcandy|sennheiser|raycon|tozo|nothing ear|shokz|aftershokz|\\bqc\\d") to DevType.EARBUDS,
        Regex("(?i)apple watch") to DevType.WATCH_APPLE,
        Regex("(?i)galaxy watch|pixel watch|wear ?os|ticwatch|fossil gen") to DevType.WATCH_ANDROID,
        Regex("(?i)watch|fitbit|garmin|amazfit|mi band|smart band|whoop|forerunner|fenix|venu|\\bversa\\b|\\binspire\\b|oura") to DevType.WATCH,
        Regex("(?i)gopro|insta360|dash ?cam|\\bcam\\b|camera|\\bring\\b|arlo|wyze|blink|eufy|nest cam|\\bdji\\b|osmo|reolink|vantrue|viofo|hikvision|dahua|ezviz|\\bipc\\b") to DevType.CAMERA,
        Regex("(?i)simplisafe|\\badt\\b|alarm|smart ?lock|\\block\\b|august|yale|schlage|kwikset|ultraloq|abode|vivint|keypad|level bolt") to DevType.SECURITY,
        Regex("(?i)\\bjbl\\b|speaker|soundlink|sonos|ue boom|wonderboom|echo|homepod") to DevType.SPEAKER,
        Regex("(?i)\\[tv]|\\btv\\b|bravia|roku|fire ?tv|chromecast|vizio|hisense") to DevType.TV,
        Regex("(?i)macbook|imac|mac ?mini|mac ?pro|laptop|notebook|thinkpad|surface|chromebook|desktop-|laptop-|\\bdell\\b|lenovo|\\bhp\\b|asus|acer|\\bxps\\b|\\bpc\\b|windows") to DevType.LAPTOP,
        Regex("(?i)iphone|ipad") to DevType.PHONE_APPLE,
        Regex("(?i)galaxy|pixel|\\bsm-[a-z]\\d|\\bmoto|oneplus|android|redmi|xiaomi|\\boppo\\b|\\bvivo\\b|realme|nokia|\\blg-|\\btcl\\b") to DevType.PHONE_ANDROID,
        Regex("(?i)printer|direct-..-(hp|epson|canon|brother)|officejet|deskjet|laserjet|envy|epson|canon|brother") to DevType.PRINTER,
    )
    private val HOTSPOT = Regex("(?i)iphone|galaxy|androidap|android ?ap|pixel|moto|oneplus|hotspot|mobile ?hot")

    /** From a device or network name alone. Vehicles first (a car named "Ford" shouldn't read as anything else). */
    fun byName(name: String?, wifi: Boolean): Guess? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("(") } ?: return null
        MAKE_RX.find(n)?.let { m ->
            val make = MAKES.first { it.replace(" ", "").equals(m.groupValues[1].replace(" ", ""), true) }.let { if (it == "Chevy") "Chevrolet" else if (it == "VW") "Volkswagen" else it }
            val model = m.groupValues[2].replace(Regex("(?i)\\b(wifi|wi-fi|hotspot|bluetooth|bt|car|\\d{3,})\\b"), "").trim().split(Regex("\\s+"))
                .take(2).joinToString(" ").trim().takeIf { it.length in 1..20 }
            // "Ram" and "Mini" also show up in ordinary names: only trust them with a car-like word around
            if (make !in listOf("Ram", "Mini") || Regex("(?i)car|truck|uconnect|\\d{4}").containsMatchIn(n)) return Guess(DevType.VEHICLE, make, model)
        }
        INFOTAINMENT.firstOrNull { it.first.containsMatchIn(n) }?.let { return Guess(DevType.VEHICLE, it.second) }
        if (wifi && HOTSPOT.containsMatchIn(n)) return Guess(DevType.PHONE_HOTSPOT)
        return NAMES.firstOrNull { it.first.containsMatchIn(n) }?.let { Guess(it.second) }
    }

    /** From everything a BLE advertisement carries: name, Bluetooth class, appearance, Apple / Microsoft / Google signatures. */
    @SuppressLint("MissingPermission")
    fun ble(r: ScanResult): Guess {
        val rec = r.scanRecord
        byName(rec?.deviceName ?: runCatching { r.device.name }.getOrNull(), wifi = false)?.let { return it }
        // Bluetooth Core spec "Appearance" (AD type 0x19), category = value >> 6
        when (appearance(rec?.bytes)?.shr(6)) {
            0x01 -> return Guess(DevType.PHONE_ANDROID); 0x02 -> return Guess(DevType.LAPTOP); 0x03 -> return Guess(DevType.WATCH)
            0x21 -> return Guess(DevType.SPEAKER); 0x25 -> return Guess(DevType.EARBUDS)
        }
        runCatching { r.device.bluetoothClass }.getOrNull()?.let { c ->
            when (c.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> return Guess(DevType.VEHICLE)
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> return Guess(DevType.EARBUDS)
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> return Guess(DevType.SPEAKER)
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA, BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER -> return Guess(DevType.CAMERA)
                BluetoothClass.Device.WEARABLE_WRIST_WATCH -> return Guess(DevType.WATCH)
            }
            when (c.majorDeviceClass) {
                BluetoothClass.Device.Major.PHONE -> return Guess(DevType.PHONE_ANDROID)
                BluetoothClass.Device.Major.COMPUTER -> return Guess(DevType.LAPTOP)
                BluetoothClass.Device.Major.WEARABLE -> return Guess(DevType.WATCH)
            }
        }
        // Apple Continuity message type: 0x07 proximity pairing (AirPods / Beats), 0x10 Nearby Info (iPhone, iPad, Mac, Watch)
        rec?.getManufacturerSpecificData(0x004C)?.takeIf { it.isNotEmpty() }?.let { m ->
            return when (m[0].toInt()) { 0x07 -> Guess(DevType.AIRPODS); 0x10, 0x0C, 0x0F -> Guess(DevType.PHONE_APPLE); else -> Guess(DevType.UNKNOWN) }
        }
        if (rec?.getManufacturerSpecificData(0x0006) != null) return Guess(DevType.LAPTOP)            // Microsoft Swift Pair: Windows PCs
        if (rec?.serviceUuids.orEmpty().any { it.uuid.toString().startsWith("0000fe2c") } ||
            rec?.serviceData?.keys.orEmpty().any { it.uuid.toString().startsWith("0000fe2c") }) return Guess(DevType.EARBUDS)  // Google Fast Pair accessories
        return Guess(DevType.UNKNOWN)
    }

    private fun appearance(b: ByteArray?): Int? {
        b ?: return null
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0 || i + len >= b.size) break
            if ((b[i + 1].toInt() and 0xFF) == 0x19 && len >= 3) return (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            i += len + 1
        }
        return null
    }

    /** Rough distance from signal strength (log-distance model). Walls, bodies and pockets change it a lot: a guess, not a measurement.
     *  tx1m = the device's own signal at 1 m when it says so; otherwise typical values for phones/tags (BLE) and routers (WiFi). */
    fun feet(rssi: Int, wifi: Boolean, tx1m: Int? = null): Int {
        val ref = tx1m ?: if (wifi) -40 else -59
        val n = if (wifi) 3.0 else 2.5
        val m = 10.0.pow((ref - rssi) / (10 * n)).coerceIn(0.3, 300.0)
        return (m * 3.281).roundToInt().coerceAtLeast(1)
    }

    fun feetText(ft: Int): String = when {
        ft <= 1 -> "about 1 foot"
        ft < 10 -> "about $ft feet"
        ft < 50 -> "about ${(ft + 2) / 5 * 5} feet"
        ft < 200 -> "about ${(ft + 5) / 10 * 10} feet"
        ft < 500 -> "about ${(ft + 25) / 50 * 50} feet"
        else -> "more than 500 feet"
    }
}
