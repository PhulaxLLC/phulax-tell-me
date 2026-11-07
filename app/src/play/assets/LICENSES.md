# Phulax Tell Me licenses and credits

Phulax Tell Me (Google Play edition) by Phulax LLC. Source code: https://github.com/PhulaxLLC/phulax-tell-me
Because it includes GPL-3.0 code, Phulax Tell Me as a whole is GPL-3.0-or-later (see LICENSE).

## Code

| Component | Where | License | Notes |
|---|---|---|---|
| NetGuard packet engine (native C) by Marcel Bokhorst (M66B), https://github.com/M66B/NetGuard | app/src/main/cpp/netguard | GPL-3.0-or-later | Copied with original headers. Changes: JNI names renamed to Phulax Tell Me classes; domain blocking now gets the app uid (per-app rules); SNI uid via getConnectionOwnerUid on Android 10+; recheck packets marked "chk". |
| NetGuard JNI bridge classes (Packet, Allowed, ResourceRecord, Usage) | app/src/main/java/com/phonewatch/engine | GPL-3.0-or-later | Derived from NetGuard. |
| Shizuku API by RikkaApps, https://github.com/RikkaApps/Shizuku-API | Gradle dependency | Apache-2.0 (API library) | Used only to run pm/cmd for Debloat. |
| Shizuku ADB client and pairing code by RikkaApps, https://github.com/RikkaApps/Shizuku | app/src/main/java/com/phonewatch/adb, app/src/main/cpp/adb | Apache-2.0 | Adapted for the built-in helper (package renamed, Shizuku helpers replaced). Original license in app/src/main/cpp/adb/LICENSE-Shizuku-Apache-2.0. |
| BoringSSL prefab (io.github.vvb2060.ndk:boringssl) | Gradle dependency | ISC / OpenSSL / Apache-2.0 | SPAKE2 for Wireless debugging pairing. |
| HiddenApiBypass by LSPosed, https://github.com/LSPosed/AndroidHiddenApiBypass | Gradle dependency | Apache-2.0 | Reaches the system TLS keying-material call used during pairing. |
| AndroidX, Jetpack Compose, Room, Kotlin coroutines | Gradle dependencies | Apache-2.0 | |
| Bouncy Castle (bcpkix/bcprov/bcutil jdk18on 1.79), https://www.bouncycastle.org | Gradle dependency | MIT (Bouncy Castle licence) | Deep Inspect: builds the on-phone CA and per-site certificates. |
| SQLCipher for Android 4.6.1 by Zetetic, https://github.com/sqlcipher/sqlcipher-android | Gradle dependency | BSD-3-Clause | Deep Inspect: encrypted capture database. |
| Twitter HPACK 1.0.2, https://github.com/twitter/hpack | Gradle dependency | Apache-2.0 | Deep Inspect: reads HTTP/2 headers. |
| Google Brotli decoder (org.brotli:dec 0.1.2), https://github.com/google/brotli | Gradle dependency | MIT | Deep Inspect: unpacks br-compressed bodies. |
| NetGuard engine change for Deep Inspect | app/src/main/cpp/netguard (tcp.c, netguard.c/.h) | GPL-3.0-or-later | Per-connection SOCKS5 redirect of chosen apps' port 443/80 to Phulax Tell Me's on-phone proxy. |

PC tool (not in the APK): `tools/patch_app.py` uses Apktool 3.0.3 by Connor Tumbleson and contributors
(https://github.com/iBotPeaches/Apktool, Apache-2.0), downloaded unmodified from the official GitHub release
into tools/bin/apktool_3.0.3.jar, plus zipalign/apksigner from the Android SDK build-tools.

Design was also informed by TrackerControl (GPL-3.0, https://github.com/TrackerControl/tracker-control-android) and Rethink DNS (Apache-2.0). No code was copied from them.

## Data bundled in the APK (app/src/main/assets/data)

| Dataset | File | License | Use allowed? |
|---|---|---|---|
| Exodus Privacy tracker database (ETIP), https://reports.exodus-privacy.eu.org/api/trackers | exodus-trackers.json | ODbL 1.0 (database), DbCL 1.0 (contents) | Yes, with attribution; a derived database would be shared under ODbL. |
| Universal Android Debloater Next Generation package list, https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation | uad.json (trimmed from uad_lists.json: list, removal rating, description) | GPL-3.0 | Yes; GPL-compatible with this app. |
| Phulax Tell Me CDN list (hand made from public knowledge) | cdn.json | GPL-3.0 (this project) | |
| DB-IP IP to City Lite + IP to ASN Lite (2026-10), https://db-ip.com | ipdb.bin (converted to a compact lookup file by tools/build_ipdb.py) | CC BY 4.0 | Yes, with attribution: "IP Geolocation by DB-IP" (shown on the Recipients screen and in exports). Lookups are offline, on the phone. |

Considered but NOT bundled: the full Tracker Radar per-domain files (too large; only the owner map is used), X-Ray 2020 list (license unclear), Peter Lowe's list (prohibits commercial use), MaxMind GeoLite2 (needs an account/EULA), iptoasn.com (its robots.txt disallows the data downloads).

The original source files and their LICENSE files are kept in data-src/.

## Optional updates

The "Update tracker lists" button (off by default) downloads the Exodus list directly from
reports.exodus-privacy.eu.org.
No data about the phone is sent.
