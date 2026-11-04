#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT="$ROOT/build"
APK_NAME="TraeGateway-v1.1.3-arm64.apk"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 24 \
  --target-sdk-version 28 \
  --version-code 10013 \
  --version-name 1.1.3 \
  --java "$OUT/gen" \
  "$OUT/res.zip"

echo "==> javac"
find "$ROOT/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -cp "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" $(cat "$OUT/classes.txt")

echo "==> package apk"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" \
  "$ROOT/jniLibs/arm64-v8a/libtraegateway.so" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile
base, dex, so, out = sys.argv[1:5]
with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    dst.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    dst.write(so, "lib/arm64-v8a/libtraegateway.so", compress_type=zipfile.ZIP_DEFLATED)
print("apk entries: %d" % len(zipfile.ZipFile(out).namelist()))
PY

echo "==> zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$ROOT/debug.keystore" ]; then
  echo "==> generate debug keystore"
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -storetype PKCS12 \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "==> apksigner sign"
"$BT/apksigner" sign \
  --ks "$ROOT/debug.keystore" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$ROOT/$APK_NAME" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --verbose "$ROOT/$APK_NAME" >/dev/null
echo "==> done: $ROOT/$APK_NAME"
ls -lh "$ROOT/$APK_NAME"
