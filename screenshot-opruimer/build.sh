#!/usr/bin/env bash
# Builds Screenshot Opruimer without Gradle, using Ubuntu's Android tools:
#   apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
# plus a newer framework jar to compile against (Robolectric android-all, API 34, from Maven Central).
#
# Usage: ./build.sh [android-all.jar] [keystore]
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_ALL="${1:-build/android-all-34.jar}"
KEYSTORE="${2:-build/release.keystore}"
PLATFORM_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
OUT=build

mkdir -p "$OUT"
rm -rf "$OUT/gen" "$OUT/classes" "$OUT"/*.apk "$OUT/classes.dex"
mkdir -p "$OUT/gen" "$OUT/classes"

if [ ! -f "$ANDROID_ALL" ]; then
  curl -fsSL -o "$ANDROID_ALL" \
    https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
fi

# 1. Resources -> R.java
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$PLATFORM_JAR"

# 2. Java -> .class (Java 8 bytecode for dx)
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_ALL:$PLATFORM_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

# 3. .class -> classes.dex
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"

# 4. Package, align, sign
aapt package -f -M AndroidManifest.xml -S res -I "$PLATFORM_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias opruimer -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Screenshot Opruimer" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias opruimer --out "$OUT/ScreenshotOpruimer.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/ScreenshotOpruimer.apk"
echo "Klaar: $OUT/ScreenshotOpruimer.apk"
