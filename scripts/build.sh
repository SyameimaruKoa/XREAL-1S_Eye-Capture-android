#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME is not set" >&2
  exit 1
fi
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
BUILD="$ROOT/build"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

"$BT/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  --min-sdk-version 31 \
  --target-sdk-version 35

find "$ROOT/app/src" -name '*.java' -print > "$BUILD/sources.txt"
javac --release 8 -cp "$PLATFORM" -d "$BUILD/classes" @"$BUILD/sources.txt"

find "$BUILD/classes" -name '*.class' -print > "$BUILD/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api 31 --output "$BUILD/dex" @"$BUILD/classes.txt"

jar uf "$BUILD/base.apk" -C "$BUILD/dex" classes.dex
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

KEYSTORE="${KEYSTORE:-}"
if [[ -z "$KEYSTORE" ]]; then
  KEYSTORE="$BUILD/debug.keystore"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
  KEYSTORE_PASSWORD=android
  KEY_PASSWORD=android
  KEY_ALIAS=androiddebugkey
fi

"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass "pass:${KEYSTORE_PASSWORD}" \
  --key-pass "pass:${KEY_PASSWORD}" \
  --ks-key-alias "${KEY_ALIAS}" \
  --out "$BUILD/xreal-recorder.apk" \
  "$BUILD/aligned.apk"

echo "APK: $BUILD/xreal-recorder.apk"
