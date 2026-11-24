#!/usr/bin/env bash
# Makes a release: bumps the version in app/build.gradle, commits, tags vX.Y.Z and pushes the tag.
# The Release workflow (.github/workflows/release.yml) then builds, signs, audits and publishes the APK;
# installed copies of the app find it with their update check.
#
# usage: tools/release.sh 1.9.1        (run on the main branch, clean working tree)
#        tools/release.sh 1.9.1 --no-push   (commit and tag only; push with: git push origin main vX.Y.Z)
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:?usage: tools/release.sh X.Y.Z [--no-push]}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 1.9.1"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "working tree is not clean"; exit 1; }
git rev-parse "v$V" >/dev/null 2>&1 && { echo "tag v$V already exists"; exit 1; }
CUR=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)
CODE=$(sed -n "s/.*versionCode \([0-9]*\).*/\1/p" app/build.gradle)
[ "$(printf '%s\n%s\n' "$CUR" "$V" | sort -V | tail -1)" = "$V" ] && [ "$CUR" != "$V" ] || { echo "$V is not newer than $CUR"; exit 1; }
sed -i "s/versionCode $CODE/versionCode $((CODE + 1))/; s/versionName '$CUR'/versionName '$V'/" app/build.gradle
sed -i "s/VERSION_CODE:-$CODE/VERSION_CODE:-$((CODE + 1))/; s/VERSION_NAME:-$CUR/VERSION_NAME:-$V/" tools/build_offline.sh
git commit -am "Release $V"
git tag -a "v$V" -m "Storage Planner $V"
if [ "${2:-}" = "--no-push" ]; then echo "Tagged v$V. Push with: git push origin HEAD v$V"; else git push origin HEAD "v$V"; fi
