#!/usr/bin/env bash
# Local stand-in for step [1/6] of tools/build_offline.sh (javac of ALL of app/src/main/java against android-34.jar),
# for machines without an Android SDK. Exit non-zero on any compile error.
#
# Bootclasspath: Robolectric's android-all API 34 jar (repo1.maven.org) supplies android.*, but it does NOT ship
# libcore's java.* (Robolectric runs on the host JDK). Android 14's libcore is ~OpenJDK 17, so java.* is taken
# from the JDK at two API levels:
#   pass 1  --release 8  : catches JDK APIs newer than Java 8 (closest to what minSdk 26 guarantees)
#   pass 2  --release 17 : catches clashes with java.lang types that android-34.jar has but Java 8 lacks
#                          (e.g. java.lang.Record vs a wildcard-imported com.storageplanner.v2.model.Record)
# Neither pass proves a Java 8 API exists on API 26 (e.g. java.nio.file is API 26+, but some of it is not);
# ForbiddenApiTest and the real CI build remain authoritative.
# Usage: bash tools/v2eval/check_android_compile.sh [path/to/android-all.jar]
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VER=14-robolectric-10818077
AJ="${1:-${ANDROID_ALL_JAR:-/home/user/android-all/android-all-$VER.jar}}"
if [ ! -f "$AJ" ]; then
  mkdir -p "$(dirname "$AJ")"
  echo "downloading android-all $VER -> $AJ"
  curl -fsS -o "$AJ" "https://repo1.maven.org/maven2/org/robolectric/android-all/$VER/android-all-$VER.jar"
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/v2acheck.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
cd "$REPO"
find app/src/main/java -name '*.java' | sort > "$WORK/srcs.txt"     # same source set as build_offline.sh
rc=0
for REL in 8 17; do
  mkdir -p "$WORK/c$REL"
  if javac --release "$REL" -classpath "$AJ" -encoding UTF-8 -Xlint:-options -nowarn -d "$WORK/c$REL" \
        @"$WORK/srcs.txt" > "$WORK/javac$REL.log" 2>&1; then
    echo "pass --release $REL: OK ($(wc -l < "$WORK/srcs.txt") files)"
  else
    grep -v "JAVA_TOOL_OPTIONS" "$WORK/javac$REL.log"; echo "pass --release $REL: FAILED"; rc=1
  fi
done
[ "$rc" = 0 ] && echo "check_android_compile: OK" || echo "check_android_compile: FAILED"
exit "$rc"
