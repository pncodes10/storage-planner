#!/usr/bin/env bash
# Phase 6 dataset gate (PHASE_6 13, S1 step 0): compile like run_tests.sh, scan the labelled dataset in standard and aggressive
# mode, run evaluate.py --gate phase4 and phase5. Exit 1 if any step fails.
# Usage: bash tools/v2eval/p6_gate.sh OUT [extra V2Cli args]      (e.g. --max-tier 4)
set -uo pipefail
if [ $# -lt 1 ]; then echo "usage: p6_gate.sh OUT [extra V2Cli args]"; exit 2; fi
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export LC_ALL=C.UTF-8
O="$1"; shift
DATASET="${P6_DATASET:-/home/user/sp-dataset}"
mkdir -p "$O/classes"; : > "$O/rc.txt"
cd "$REPO"
SRC=$(find app/src/main/java/com/storageplanner/core app/src/main/java/com/google app/src/main/java/com/storageplanner/v2 app/src/main/java/com/storageplanner/v2adapter \
           jvmtest/com/storageplanner/v2test jvmtest/com/storageplanner/core -name '*.java' | sort)
fail=0
javac --release 8 -encoding UTF-8 -nowarn -d "$O/classes" $SRC; rc=$?
echo "javac rc=$rc" >> "$O/rc.txt"; [ $rc -ne 0 ] && { echo "p6_gate: compile failed"; exit 1; }
for m in standard aggressive; do
  java -cp "$O/classes" com.storageplanner.v2test.V2Cli scan "$DATASET" --store memory --mode $m --target-bytes max --now 1792000000000 --out "$O/$m.json" "$@" > "$O/$m.log" 2>&1
  rc=$?; echo "cli $m rc=$rc" >> "$O/rc.txt"; [ $rc -ne 0 ] && fail=1
  python3 -I tools/v2eval/evaluate.py --result "$O/$m.json" --dataset "$DATASET" --gate phase4 > "$O/$m.phase4.txt" 2>&1
  rc=$?; echo "p4 $m rc=$rc" >> "$O/rc.txt"; [ $rc -ne 0 ] && fail=1
  python3 -I tools/v2eval/evaluate.py --result "$O/$m.json" --dataset "$DATASET" --gate phase5 --plans max="$O/$m.json" > "$O/$m.phase5.txt" 2>&1
  rc=$?; echo "p5 $m rc=$rc" >> "$O/rc.txt"; [ $rc -ne 0 ] && fail=1
done
cat "$O/rc.txt"
if [ $fail -ne 0 ]; then echo "p6_gate: FAILED (see $O)"; exit 1; fi
echo "p6_gate: OK ($O)"
