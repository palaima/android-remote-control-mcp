#!/usr/bin/env bash
# Build the smoke-test helper app (package dev.test.nosettext) without Gradle.
#
# The app shows two EditText fields. The first one refuses ACTION_SET_TEXT, so typing tools must use
# their fallback path. The second one is a plain field.
#
# Usage: build.sh [output-dir]   (default: <repo>/build/emulator-smoke/test-app)
# Needs: ANDROID_HOME (or ANDROID_SDK_ROOT) with build-tools and a platform installed, and JDK 11 or newer
# (d8 does not run on Java 8). The JDK is taken from JAVA_HOME when set, otherwise from PATH.
# Optional: BUILD_TOOLS_VERSION and PLATFORM (for example 35.0.0 and android-35); the newest installed are used.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
OUT="${1:-$REPO/build/emulator-smoke/test-app}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK" >&2; exit 1; }

BT_VER="${BUILD_TOOLS_VERSION:-$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -exec basename {} \; | sort -V | tail -1)}"
PLAT="${PLATFORM:-$(find "$SDK/platforms" -mindepth 1 -maxdepth 1 -name 'android-[0-9]*' -exec basename {} \; | sort -V | tail -1)}"
BT="$SDK/build-tools/$BT_VER"
JAR="$SDK/platforms/$PLAT/android.jar"
[ -x "$BT/d8" ] && [ -f "$JAR" ] || { echo "Missing build-tools ($BT) or platform ($JAR)" >&2; exit 1; }

[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
javac -source 8 -target 8 -nowarn -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
  "$HERE/src/dev/test/nosettext/MainActivity.java"
find "$OUT/classes" -name '*.class' -print0 | xargs -0 "$BT/d8" --min-api 30 --lib "$JAR" --output "$OUT"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml"
(cd "$OUT" && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

# Throwaway signing key, created once per output directory.
if [ ! -f "$OUT/test-app.jks" ]; then
  keytool -genkeypair -keystore "$OUT/test-app.jks" -storepass android -keypass android -alias test \
    -keyalg RSA -dname CN=test -validity 3650 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$OUT/test-app.jks" --ks-pass pass:android --out "$OUT/nosettext.apk" "$OUT/aligned.apk"
echo "$OUT/nosettext.apk"
