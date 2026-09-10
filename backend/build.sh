#!/usr/bin/env bash
# Builds DeskCam straight from the SDK build-tools. No Gradle, no AGP, no network.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT_VER="${BT_VER:-37.0.0}"
PLATFORM="${PLATFORM:-android-37.0}"
MIN_SDK=33
TARGET_SDK=37

BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
KEYSTORE="$ROOT/deskcam.keystore"
APK="$OUT/deskcam.apk"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo ">> clean"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo ">> aapt2 compile"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"

echo ">> aapt2 link"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code 1 --version-name 0.1 \
    "$OUT/res.zip"

# The pure logic runs on the workstation, before anything is packaged. The ROI maths,
# the parsers, the clamp and the tar writer need no device, and every one of them has
# been wrong at some point in a way a compiler cannot see.
echo ">> tests"
mkdir -p "$OUT/testclasses"
javac -Xlint:all -Werror -encoding UTF-8 --release 17 \
    -d "$OUT/testclasses" \
    "$ROOT/app/src/dev/deskcam/Geom.java" \
    "$ROOT/app/src/dev/deskcam/Parse.java" \
    "$ROOT/app/src/dev/deskcam/Tar.java" \
    "$ROOT/app/src/dev/deskcam/Access.java" \
    "$ROOT/app/src/dev/deskcam/Sharp.java" \
    "$ROOT/app/src/dev/deskcam/Hunt.java" \
    "$ROOT/app/src/dev/deskcam/Tape.java" \
    "$ROOT/app/src/dev/deskcam/Thermal.java" \
    "$ROOT/test/dev/deskcam/Tests.java"
java -cp "$OUT/testclasses" dev.deskcam.Tests "$OUT/testwork"

echo ">> javac"
# Warnings are on and fatal. They were off, with -nowarn, for the whole life of the
# project.
find "$ROOT/app/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -Xlint:all -Werror -encoding UTF-8 --release 17 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"; then
    echo "javac failed" >&2
    exit 1
fi

echo ">> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$ANDROID_JAR" --min-api "$MIN_SDK" --output "$OUT/dex" @"$OUT/classes.txt"

echo ">> package"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unaligned.apk" classes*.dex )

echo ">> zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo ">> generating signing key"
    keytool -genkeypair -v -keystore "$KEYSTORE" \
        -alias deskcam -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass deskcam -keypass deskcam \
        -dname "CN=DeskCam, OU=Bench, O=DeskCam, L=., S=., C=AU" >/dev/null 2>&1
fi

echo ">> sign"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:deskcam --key-pass pass:deskcam \
    --min-sdk-version "$MIN_SDK" \
    --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --min-sdk-version "$MIN_SDK" "$APK" >/dev/null

rm -rf "$OUT/unaligned.apk" "$OUT/aligned.apk" "$OUT/base.apk" "$OUT/res.zip" \
       "$OUT/sources.txt" "$OUT/classes.txt" "$OUT/testclasses" "$OUT/testwork"

echo
echo "built: $APK  ($(du -h "$APK" | cut -f1))"
