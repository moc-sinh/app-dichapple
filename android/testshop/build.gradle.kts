import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A made-up shop with Chinese text, to try MSTrans › Dịch màn hình on a device. Part of the build
// only with MSTRANS_TESTSHOP set; never released.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.mocsinh.testshop"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.mocsinh.testshop"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
