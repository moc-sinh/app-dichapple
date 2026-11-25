import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.mocsinh.mstrans"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.mocsinh.mstrans"
        minSdk = 26
        targetSdk = 34
        // CI numbers each build (the run number), so a new APK installs over the one before.
        versionCode = (System.getenv("MSTRANS_BUILD") ?: "1").toInt()
        versionName = "1.6.0"
        // ML Kit ships native code (about 45 MB) for every CPU. Phones are ARM; a build for the x86_64
        // emulator sets MSTRANS_ABIS=arm64-v8a,armeabi-v7a,x86_64.
        ndk { abiFilters += (System.getenv("MSTRANS_ABIS") ?: "arm64-v8a,armeabi-v7a").split(",").map { it.trim() } }
    }

    // One key for every build: Android installs an update only when it is signed like the app on the
    // phone. CI may hand in its own key (a secret); otherwise the key kept in the repository signs it.
    signingConfigs {
        create("sideload") {
            val secret = System.getenv("MSTRANS_KEYSTORE")
            storeFile = if (secret != null) file(secret) else file("mstrans-sideload.p12")
            storePassword = System.getenv("MSTRANS_KEYSTORE_PASSWORD") ?: "mstrans-sideload"
            keyAlias = System.getenv("MSTRANS_KEY_ALIAS") ?: "mstrans"
            keyPassword = System.getenv("MSTRANS_KEY_PASSWORD") ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    lint {
        // The build is checked by compiling and by the core's tests; lint's release check only slows CI.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        // Compressed in the APK (unpacked once at install): ML Kit's native code would otherwise make
        // the download about three times larger.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Dịch màn hình: chữ Trung trong ảnh (mô hình đi kèm APK) và dịch Trung → Việt ngoại tuyến.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
