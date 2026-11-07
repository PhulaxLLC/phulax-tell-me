import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing: keystore lives in PhoneWatch/keystore, password in ~/.secrets/phonewatch_keystore.txt
val ksFile = rootProject.file("keystore/phonewatch.jks")
val ksPassFile = File(System.getProperty("user.home"), ".secrets/phonewatch_keystore.txt")

android {
    namespace = "com.phonewatch"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.phonewatch.app"
        minSdk = 29          // getConnectionOwnerUid (per-app attribution) needs Android 10+
        targetSdk = 34       // Galaxy Z Fold 4 runs Android 14 (API 34)
        versionCode = 23
        versionName = "2.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_PLATFORM=android-29" } }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    signingConfigs {
        create("release") {
            if (ksFile.exists() && ksPassFile.exists()) {
                val pw = ksPassFile.readText().trim()
                storeFile = ksFile
                storePassword = pw
                keyAlias = "phonewatch"
                keyPassword = pw
            }
        }
    }

    // personal = the owner's own build (non-commercial tracker lists, Deep Inspect).
    // play = the Google Play edition: only lists that allow commercial use, no Deep Inspect, its own package.
    flavorDimensions += "edition"
    productFlavors {
        create("personal") { dimension = "edition"; buildConfigField("boolean", "PLAY", "false") }
        create("play") { dimension = "edition"; applicationId = "com.phulax.tellme"; buildConfigField("boolean", "PLAY", "true") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile.exists() && ksPassFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true; aidl = false; prefab = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/BCKEY.*", "META-INF/BC*.SF", "META-INF/BC*.DSA", "org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties") }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("io.github.vvb2060.ndk:boringssl:20250114")      // Apache-2.0/ISC: SPAKE2 for Wireless debugging pairing (built-in helper)
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.9.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")     // Apache-2.0: reach platform Conscrypt for pairing
    // Deep Inspect
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")          // MIT-style Bouncy Castle licence: CA + leaf certificates
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")       // BSD-style: encrypted capture database
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("com.twitter:hpack:1.0.2")                       // Apache-2.0: HTTP/2 header decoding
    implementation("org.brotli:dec:0.1.2")                          // MIT: decode "br" compressed bodies
    implementation("org.osmdroid:osmdroid-android:6.1.20")          // Apache-2.0: OpenStreetMap map view (tiles Â© OpenStreetMap contributors)
}
