#!/bin/bash
# Checks the Android version where the Android SDK cannot be downloaded (a cloud session): runs the
# core's tests, then compiles the app's Kotlin against the Android framework taken from Maven Central
# (Robolectric's android-all, 186 MB, downloaded once) with a stand-in R class. Nothing is run on a
# phone or an emulator: CI builds the APK. Needs Gradle 8 and a JDK 17+.
#   bash ci/android-local-check.sh          core tests, then the app's compile check
#   bash ci/android-local-check.sh compile  the compile check only
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
W=${MSTRANS_CHECK_DIR:-${TMPDIR:-/tmp}/mstrans-android-check}
mkdir -p "$W/app/libs" "$W/app/src/stub/vn/mocsinh/mstrans"

# Maven Central answers 429 to some cloud machines: Google's mirror of it is tried first.
cat > "$W/mirror.gradle" <<'EOF'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
settingsEvaluated { settings ->
    [settings.pluginManagement.repositories, settings.dependencyResolutionManagement.repositories].each { repos ->
        repos.withType(MavenArtifactRepository).configureEach { repo ->
            if (repo.url.toString().startsWith('https://repo.maven.apache.org')) repo.url = mirror
        }
        repos.maven { url = mirror }
    }
}
EOF

cd "$R/android"
if [ "${1:-}" != compile ]; then
  env -u ANDROID_HOME -u ANDROID_SDK_ROOT gradle -q -I "$W/mirror.gradle" :core:test
  echo "Core tests passed."
else
  env -u ANDROID_HOME -u ANDROID_SDK_ROOT gradle -q -I "$W/mirror.gradle" :core:compileKotlin
fi

JAR="$W/app/libs/android-all.jar"
[ -s "$JAR" ] || curl -sSf -o "$JAR" \
  https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all/15-robolectric-13954326/android-all-15-robolectric-13954326.jar
{
  echo "package vn.mocsinh.mstrans"
  echo "object R {"
  echo "    object drawable {"
  i=0
  for f in "$R"/android/app/src/main/res/drawable/*.xml; do
    printf "        const val %s = 0x7f08%04x\n" "$(basename "$f" .xml)" $i
    i=$((i + 1))
  done
  echo "    }"
  echo "}"
} > "$W/app/src/stub/vn/mocsinh/mstrans/R.kt"
cat > "$W/app/settings.gradle.kts" <<'EOF'
rootProject.name = "appcheck"
EOF
cat > "$W/app/build.gradle.kts" <<EOF
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm") version "2.2.0" }
repositories { mavenCentral() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
sourceSets["main"].kotlin.srcDirs("$R/android/app/src/main/kotlin", "src/stub")
dependencies {
    compileOnly(files("libs/android-all.jar"))
    implementation(files("$R/android/core/build/classes/kotlin/main"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
EOF
cd "$W/app"
gradle -I "$W/mirror.gradle" compileKotlin --rerun-tasks 2>&1 | grep -E "^(e|w): |BUILD" | sed "s|file://$R/||"
