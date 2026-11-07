# JNI bridge: the native engine looks these up by name
-keep class com.phonewatch.engine.Packet { *; }
-keep class com.phonewatch.engine.Allowed { *; }
-keep class com.phonewatch.engine.ResourceRecord { *; }
-keep class com.phonewatch.engine.Usage { *; }
-keep class com.phonewatch.engine.PwVpnService { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Shizuku
-keep class rikka.shizuku.** { *; }
# Deep Inspect
-keep class net.zetetic.database.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn javax.naming.**
-keep class com.twitter.hpack.** { *; }
-dontwarn org.brotli.**

# built-in helper: adb_pairing.cpp registers natives on this class by name
-keep class com.phonewatch.adb.PairingContext { *; }
