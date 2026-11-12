#!/usr/bin/env bash
# v2 test runner: compiles classic core + zxing + v2 + the v2 JVM tests with --release 8 into a temp dir,
# runs every v2 test suite (AllV2Tests) and the end-to-end kill/resume shell test. Exit non-zero on any failure.
# Usage: bash tools/v2eval/run_tests.sh [SuiteName...]     (suite names restrict AllV2Tests; shell test always runs)
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export LC_ALL=C.UTF-8          # non-ASCII file names (ARCHITECTURE 0)
WORK="$(mktemp -d "${TMPDIR:-/tmp}/v2test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
CLS="$WORK/classes"
mkdir -p "$CLS"
cd "$REPO"

SRC=$(find app/src/main/java/com/storageplanner/core app/src/main/java/com/google app/src/main/java/com/storageplanner/v2 \
           jvmtest/com/storageplanner/v2test jvmtest/com/storageplanner/core -name '*.java' | sort)
javac --release 8 -encoding UTF-8 -nowarn -d "$CLS" $SRC

java -Dv2.repo="$REPO" -cp "$CLS" com.storageplanner.v2test.AllV2Tests "$@"

# ---- end-to-end kill test: halt the JVM after N durable snapshots, resume, compare with a clean run ----
T="$WORK/tree"
mkdir -p "$T/DCIM/Camera" "$T/Download/sub" "$T/WhatsApp/Media/WhatsApp Images" "$T/Android/data/com.x"
for i in $(seq 1 40); do head -c $((i * 37)) /dev/zero > "$T/DCIM/Camera/IMG_$i.jpg"; done
for i in $(seq 1 15); do printf 'doc %d' "$i" > "$T/Download/sub/d$i.pdf"; done
printf 'x' > "$T/WhatsApp/Media/WhatsApp Images/IMG-1-WA0001.jpg"
printf 'é' > "$T/Download/é è.txt"
printf 'r' > "$T/Android/data/com.x/r.bin"
CLI="java -cp $CLS com.storageplanner.v2test.V2Cli"
NOW=1792000000000
set +e
$CLI scan "$T" --out "$WORK/killed.json" --state-dir "$WORK/state" --snapshot-every-ms 0 --halt-after-commits 5 --now $NOW >/dev/null
rc=$?
set -e
if [ "$rc" != 137 ]; then echo "kill test: expected exit 137 from --halt-after-commits, got $rc"; exit 1; fi
$CLI scan "$T" --out "$WORK/resumed.json" --state-dir "$WORK/state" --snapshot-every-ms 0 --resume --now $NOW >/dev/null
$CLI scan "$T" --out "$WORK/clean.json" --now $NOW >/dev/null
if python3 -I "$REPO/tools/v2eval/evaluate.py" --compare "$WORK/clean.json" "$WORK/resumed.json" >"$WORK/cmp.txt"; then
  echo "kill test: halted at commit 5 (exit 137), resumed result equals clean run"
else
  cat "$WORK/cmp.txt"; echo "kill test: FAILED"; exit 1
fi
python3 -I - "$WORK/resumed.json" <<'EOF'
import json, sys
s = json.load(open(sys.argv[1], encoding="utf-8"))["scan"]
assert s["resumedCount"] == 1 and s["state"] == "COMPLETED", s
EOF
echo "v2 run_tests: ALL PASSED"
