#!/usr/bin/env bash
# v2 scale tests (PHASE_S_STORE S4…S6): minutes each, so not part of run_tests.sh. Uses run_scale.sh (tree cached under
# $SCALE_DIR). Exit non-zero if any test fails.
# Usage: bash tools/v2eval/run_scale_tests.sh [test...]     tests: s4-disk s5-a s5-b s5-c s5-d (default: all)
#   s4-disk   synth 100,000 files (30 % images), DISCOVER … adapter on a DiskStore (-Xmx2g): completes, and at the end every
#             store file together (segments, index, manifest) is ≤ 2 × live + 64 MiB (S4 exit, design 3.10).
#   s5-a      design 6 (a): synth 100,000 files (20 % images), DISCOVER … PLAN (no adapter), DiskStore, -Xmx128m: completes.
#   s5-b      design 6 (b): synth 150,000 files (20 % images), full V2Analysis incl. V2Adapter, DiskStore, -Xmx148m
#             ((0.8 × 256 − 20) / 1.25, REVIEW_S F-08): completes.
#   s5-c      design 6 (c): s5-a with MemoryStore at -Xmx128m must fail with OutOfMemoryError (the test bites).
#   s5-d      design 6 (d): the s5-a tree through the CLI, memory vs disk (store_equal.sh): byte-identical without timings.
#   s6-cap    V2Capacity's claim at 256 MB (99,999 files, slice S6): synth 100,000 files (20 % images), full V2Analysis incl.
#             V2Adapter, DiskStore, -Xmx148m (the F-08 cap): completes.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TESTS=("$@"); [ ${#TESTS[@]} -eq 0 ] && TESTS=(s4-disk s5-a s5-b s5-c s5-d s6-cap)
SCALE_DIR="${SCALE_DIR:-${TMPDIR:-/tmp}/v2scale}"; export SCALE_DIR
FAIL=0
for t in "${TESTS[@]}"; do
  case "$t" in
    s4-disk)
      OUT="$(bash "$REPO/tools/v2eval/run_scale.sh" 100000 --xmx 2g --store disk 2>&1)"; rc=$?
      echo "$OUT" | grep -E 'SCALE_RUN|disk store|OOM' || true
      LIVE=$(echo "$OUT" | sed -n 's/.*liveBytes=\([0-9]*\).*/\1/p'); FILES=$(echo "$OUT" | sed -n 's/.*fileBytes=\([0-9]*\).*/\1/p')
      if [ $rc -eq 0 ] && [ -n "$LIVE" ] && [ -n "$FILES" ] && [ "$FILES" -le $((2 * LIVE + 64 * 1024 * 1024)) ]; then
        echo "scale test s4-disk: PASSED (store files $FILES B <= 2 x live $LIVE B + 64 MiB)"
      else echo "scale test s4-disk: FAILED (rc=$rc files=$FILES live=$LIVE)"; FAIL=1; fi;;
    s5-a|s5-b|s5-c|s6-cap)
      case "$t" in
        s6-cap) ARGS=(100000 --images 0.2 --xmx 148m --store disk);;
        s5-a) ARGS=(100000 --images 0.2 --xmx 128m --store disk --no-adapter);;
        s5-b) ARGS=(150000 --images 0.2 --xmx 148m --store disk);;
        s5-c) ARGS=(100000 --images 0.2 --xmx 128m --store memory --no-adapter);;
      esac
      T0=$(date +%s)
      OUT="$(bash "$REPO/tools/v2eval/run_scale.sh" "${ARGS[@]}" 2>&1)"; rc=$?
      echo "$OUT" | grep -E 'SCALE_RUN|OOM|session|analyse' || true
      if [ "$t" = s5-c ]; then
        if [ $rc -ne 0 ] && echo "$OUT" | grep -q 'OOM'; then echo "scale test $t: PASSED (MemoryStore ran out of memory at ${ARGS[4]}, $(( $(date +%s) - T0 )) s)"
        else echo "scale test $t: FAILED (rc=$rc; MemoryStore was expected to run out of memory)"; FAIL=1; fi
      elif [ $rc -eq 0 ] && echo "$OUT" | grep -q 'SCALE_RUN OK'; then echo "scale test $t: PASSED (-Xmx${ARGS[4]}, $(( $(date +%s) - T0 )) s)"
      else echo "scale test $t: FAILED (rc=$rc)"; FAIL=1; fi;;
    s5-d)
      bash "$REPO/tools/v2eval/run_scale.sh" 100000 --images 0.2 --xmx 64m --help >/dev/null 2>&1 || true   # generates the tree if needed
      TREE="$SCALE_DIR/tree-100000-0.2-7"
      if [ -f "$TREE.done" ] && OUT="$(bash "$REPO/tools/v2eval/store_equal.sh" "$TREE" 2>&1)"; then echo "$OUT" | tail -1; echo "scale test s5-d: PASSED"
      else echo "$OUT" | tail -3; echo "scale test s5-d: FAILED"; FAIL=1; fi;;
    *) echo "unknown scale test $t"; FAIL=1;;
  esac
done
exit $FAIL
