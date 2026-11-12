#!/usr/bin/env bash
# v2 scale harness (PHASE_S_STORE slice S0): generate a synthetic tree of N files (cached under $SCALE_DIR), then run
# ScaleRun on it under a hard heap cap and print the per-phase heap figures.
# Usage: bash tools/v2eval/run_scale.sh N [--xmx 2g] [--images 0.3] [--seed 7] [ScaleRun options: --store memory --profile --tables ...]
# Env: SCALE_DIR (default ${TMPDIR:-/tmp}/v2scale) keeps generated trees between runs (generation of 100k takes about 40 s).
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export LC_ALL=C.UTF-8
N="${1:?usage: run_scale.sh N [--xmx SIZE] [--images SHARE] [--seed S] [ScaleRun options]}"; shift
XMX=2g; IMAGES=0.3; SEED=7; PASS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --xmx) XMX="$2"; shift 2;;
    --images) IMAGES="$2"; shift 2;;
    --seed) SEED="$2"; shift 2;;
    *) PASS+=("$1"); shift;;
  esac
done
SCALE_DIR="${SCALE_DIR:-${TMPDIR:-/tmp}/v2scale}"
TREE="$SCALE_DIR/tree-$N-$IMAGES-$SEED"
if [ ! -f "$TREE.done" ]; then
  rm -rf "$TREE"; mkdir -p "$TREE"
  python3 -I "$REPO/tools/v2eval/gen_synth.py" "$TREE" "$N" --images "$IMAGES" --seed "$SEED"
  touch "$TREE.done"
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/v2scale-cls.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
cd "$REPO"
SRC=$(find app/src/main/java/com/storageplanner/core app/src/main/java/com/google app/src/main/java/com/storageplanner/v2 app/src/main/java/com/storageplanner/v2adapter \
           jvmtest/com/storageplanner/v2test jvmtest/com/storageplanner/core -name '*.java' | sort)
javac --release 8 -encoding UTF-8 -nowarn -d "$WORK" $SRC
echo "tree $TREE  -Xmx$XMX"
java -Xmx"$XMX" -cp "$WORK" com.storageplanner.v2test.ScaleRun "$TREE" ${PASS[@]+"${PASS[@]}"}
