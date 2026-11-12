#!/usr/bin/env bash
# v2 scale tests (PHASE_S_STORE S4…S6): minutes each, so not part of run_tests.sh. Uses run_scale.sh (tree cached under
# $SCALE_DIR). Exit non-zero if any test fails.
# Usage: bash tools/v2eval/run_scale_tests.sh [test...]     tests: s4-disk (default: all)
#   s4-disk   synth 100,000 files (30 % images), DISCOVER … adapter on a DiskStore (-Xmx2g): completes, and at the end every
#             store file together (segments, index, manifest) is ≤ 2 × live + 64 MiB (S4 exit, design 3.10).
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TESTS=("$@"); [ ${#TESTS[@]} -eq 0 ] && TESTS=(s4-disk)
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
    *) echo "unknown scale test $t"; FAIL=1;;
  esac
done
exit $FAIL
