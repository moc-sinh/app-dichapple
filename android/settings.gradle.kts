// MSTrans for Android. `core` is plain Kotlin (typing, prompts, conversation logic) and is tested
// anywhere; `app` is the Android app and needs the Android SDK, so it is only part of the build
// where one is installed (GitHub Actions sets ANDROID_HOME).
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "MSTrans"

include(":core")
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (androidSdk != null && file(androidSdk).isDirectory) include(":app")
