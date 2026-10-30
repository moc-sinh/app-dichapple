// The Android Gradle plugin comes from Google's repository, which only the CI build can reach;
// it goes on the root classpath (with Kotlin's) so the Kotlin Android plugin can see it.
buildscript {
    val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (androidSdk != null && file(androidSdk).isDirectory) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    kotlin("jvm") version "2.2.0" apply false
}
