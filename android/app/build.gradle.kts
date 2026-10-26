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
        versionName = "1.1.0"
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
}
