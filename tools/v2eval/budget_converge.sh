#!/usr/bin/env bash
# PHASE_S_STORE S7 on a real tree (default /home/user/sp-dataset): consecutive runs with a GRAPH decode budget smaller than
# the work must converge to the unbudgeted result (PerceptualBudgetTest main), every budget-stopped run must be safe, and the
# first budgeted run must pass the phase-4/phase-5 gates as the unbudgeted run does (DDRR 0).
# Usage: bash tools/v2eval/budget_converge.sh OUTDIR [TREE] [BUDGET] [STORE] [MODE]     (not in run_tests.sh: minutes)
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export LC_ALL=C.UTF-8
OUT=${1:?outdir}; TREE=${2:-/home/user/sp-dataset}; BUDGET=${3:-200}; STORE=${4:-disk}; MODE=${5:-standard}
mkdir -p "$OUT"; CLS="$OUT/classes"; mkdir -p "$CLS"; cd "$REPO"
javac --release 8 -encoding UTF-8 -nowarn -d "$CLS" $(find app/src/main/java/com/storageplanner/core app/src/main/java/com/google app/src/main/java/com/storageplanner/v2 \
  app/src/main/java/com/storageplanner/v2adapter jvmtest/com/storageplanner/v2test jvmtest/com/storageplanner/core -name '*.java' | sort) || exit 2
java -cp "$CLS" com.storageplanner.v2test.PerceptualBudgetTest --tree "$TREE" --budget "$BUDGET" --store "$STORE" --mode "$MODE" --out "$OUT/runs"
rc=$?
L="ds-$STORE-$MODE"
for f in "$L-b0" "$L-ref0"; do
  python3 -I tools/v2eval/evaluate.py --result "$OUT/runs/$f.json" --dataset "$TREE" --gate phase4 > "$OUT/$f.phase4.txt" 2>&1; echo "$f phase4 rc=$?"
  python3 -I tools/v2eval/evaluate.py --result "$OUT/runs/$f.json" --dataset "$TREE" --gate phase5 --plans max="$OUT/runs/$f.json" > "$OUT/$f.phase5.txt" 2>&1; echo "$f phase5 rc=$?"
done
exit $rc
