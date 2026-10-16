#!/usr/bin/env bash
# Offline APK build without Gradle / Android Studio (used where Google's Maven is unreachable).
# Normal route: open this folder in Android Studio and Build > Build APK.
#
# Needs: JDK (javac), a Java 17 runtime for D8, android.jar (API 34 to compile, API 29 for D8),
#        r8.jar (contains D8), aapt2, Python 3 with 'cryptography'.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TC="${TOOLCHAIN:?set TOOLCHAIN to the folder with android-34.jar android-29.jar r8.jar aapt2 java17}"
AJ="$TC/android-34.jar"; AJ29="$TC/android-29.jar"; R8="$TC/r8.jar"; AAPT2="$TC/aapt2"; J17="$TC/java17/bin/java"
OUT="$HERE/build/offline"; KEYS="${KEYSTORE_DIR:-$HERE/../keystore}"
VERSION_CODE="${VERSION_CODE:-10}"; VERSION_NAME="${VERSION_NAME:-1.8.1}"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "[1/6] javac"
if ! javac -source 8 -target 8 -bootclasspath "$AJ" -encoding UTF-8 -Xlint:-options -nowarn \
      -d "$OUT/classes" $(find "$HERE/app/src/main/java" -name "*.java") > "$OUT/javac.log" 2>&1; then
  grep -v "JAVA_TOOL_OPTIONS" "$OUT/javac.log"; echo "javac failed: no APK built"; exit 1
fi
test -f "$OUT/classes/com/storageplanner/app/MainActivity.class"

echo "[2/6] strip MethodParameters (JDK 21 javac vs D8 1.5)"
python3 "$HERE/tools/strip_methodparams.py" "$OUT/classes"

echo "[3/6] d8"
"$J17" -cp "$R8" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJ29" --output "$OUT/dex" \
      $(find "$OUT/classes" -name "*.class") 2>&1 | grep -v "JAVA_TOOL_OPTIONS" || true
test -f "$OUT/dex/classes.dex"

echo "[4/6] manifest + resources (aapt2)"
python3 - "$HERE/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import re, sys
src, dst, vc, vn = sys.argv[1:5]
m = open(src).read()
m = re.sub(r'\s+tools:\w+="[^"]*"', '', m)                     # tools:* attributes are build-time only
m = m.replace(' xmlns:tools="http://schemas.android.com/tools"', '')
m = m.replace('<manifest ', f'<manifest package="com.storageplanner" android:versionCode="{vc}" android:versionName="{vn}" ', 1)
m = re.sub(r'(<manifest[^>]*>)', r'\1\n    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />', m, count=1)
open(dst, "w").write(m)
PY
"$AAPT2" compile --dir "$HERE/app/src/main/res" -o "$OUT/res/compiled.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJ" --manifest "$OUT/AndroidManifest.xml" "$OUT/res/compiled.zip" --auto-add-overlay -A "$HERE/app/src/main/assets"

echo "[5/6] package + sign (APK Signature Scheme v2)"
python3 "$HERE/tools/apkbuild.py" "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/StoragePlanner.apk" "$KEYS"

echo "[6/6] done: $OUT/StoragePlanner.apk"
