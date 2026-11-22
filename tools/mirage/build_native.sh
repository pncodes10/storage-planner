#!/usr/bin/env bash
# Builds lib/arm64-v8a/libmirage.so: the Java bridge (app/src/main/cpp/mirage_jni.cpp) linked with the Cactus engine
# (build/mirage/libcactus.a from fetch.sh). The engine is built with LLVM 19's libc++, so this needs NDK r28 or newer.
#   ANDROID_NDK=<ndk folder> bash tools/mirage/build_native.sh   ->  build/mirage/lib/arm64-v8a/libmirage.so
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
NDK="${ANDROID_NDK:?set ANDROID_NDK to an Android NDK r28+ folder}"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
OUT="$HERE/build/mirage/lib/arm64-v8a"; mkdir -p "$OUT"
cat > "$HERE/build/mirage/exports.map" <<'MAP'
{ global: Java_com_storageplanner_app_MirageEngine_*; local: *; };
MAP
"$CXX" -shared -fPIC -O2 -std=c++17 -fvisibility=hidden -I "$HERE/build/mirage" \
  "$HERE/app/src/main/cpp/mirage_jni.cpp" "$HERE/build/mirage/libcactus.a" \
  -static-libstdc++ -lm -Wl,--no-undefined -Wl,--gc-sections -Wl,--exclude-libs,ALL \
  -Wl,--version-script="$HERE/build/mirage/exports.map" -Wl,-z,max-page-size=16384 -Wl,--build-id=none -s \
  -o "$OUT/libmirage.so"
ls -l "$OUT/libmirage.so"
