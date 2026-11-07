# Phulax Tell Me

An Android privacy app by Phulax LLC that says out loud what is around you and what your apps are doing.

- **Nearby:** scans Bluetooth and WiFi and says each device in a full sentence: what it is, its name, about how many
  feet away, whether it is moving toward you, and whether any data is going in or out of your phone.
- **Tell me:** one tap next to any app speaks what it sent, received, read or copied, and which companies got it.
- **Firewall:** a local VPN (NetGuard engine) shows every connection per app and blocks trackers. Nothing leaves the phone.
- **Built-in helper:** pairs once with Android's Wireless debugging (code adapted from Shizuku) for Debloat,
  the Bluetooth/WiFi connection history and the app-ops record. No separate app needed.

## Build

Android Studio's JDK 17 and the Android SDK/NDK (27.1.12297006, CMake 3.22.1).

```
./gradlew assemblePlayRelease
```

The `play` flavor is the Google Play edition (package `com.phulax.tellme`). The `personal` flavor also exists in
`app/build.gradle.kts`, but its extra non-commercial tracker lists are not in this repository, so it builds with
the Exodus list only. Release signing is read from a local keystore that is not included; without it the release
APK is unsigned.

## License

GPL-3.0-or-later (see `LICENSE`). Third-party code and data, with their licenses, are listed in `LICENSES.md`.

Support: Phulax LLC, https://phulaxllc.com
