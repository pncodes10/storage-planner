#!/usr/bin/env bash
# PHASE_S_STORE 5.2: runs the v2 CLI twice on TREE, with --store memory and --store disk (fresh state dirs, fixed --now,
# --assess --target-bytes max), and requires the two result files to be byte-identical after removing the "timings" object.
# Usage: bash tools/v2eval/store_equal.sh TREE [extra V2Cli options...]
#   CLASSES=dir   use already compiled classes (run_tests.sh does); otherwise the tree is compiled into a temp dir.
#   NOW=ms        the fixed clock (default 1792000000000).   KEEP=dir   keep both outputs (memory.json, disk.json) there.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export LC_ALL=C.UTF-8
if [ $# -lt 1 ] || [ ! -d "$1" ]; then echo "usage: store_equal.sh TREE [V2Cli options...]" >&2; exit 2; fi
TREE="$1"; shift
WORK="$(mktemp -d "${TMPDIR:-/tmp}/v2eq.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
CLS="${CLASSES:-}"
if [ -z "$CLS" ]; then
  CLS="$WORK/classes"; mkdir -p "$CLS"
  (cd "$REPO" && javac --release 8 -encoding UTF-8 -nowarn -d "$CLS" $(find app/src/main/java/com/storageplanner/core app/src/main/java/com/google \
      app/src/main/java/com/storageplanner/v2 app/src/main/java/com/storageplanner/v2adapter jvmtest/com/storageplanner/v2test \
      jvmtest/com/storageplanner/core -name '*.java' | sort))
fi
NOW="${NOW:-1792000000000}"
for S in memory disk; do
  java -cp "$CLS" com.storageplanner.v2test.V2Cli scan "$TREE" --out "$WORK/$S.json" --state-dir "$WORK/state-$S" --store $S \
       --now "$NOW" --assess --target-bytes max "$@" >/dev/null
done
if [ -n "${KEEP:-}" ]; then mkdir -p "$KEEP"; cp "$WORK/memory.json" "$WORK/disk.json" "$KEEP/"; fi
python3 -I - "$WORK/memory.json" "$WORK/disk.json" <<'EOF'
import json, sys
def strip(x):
    if isinstance(x, dict): return {k: strip(v) for k, v in x.items() if k != "timings"}
    if isinstance(x, list): return [strip(v) for v in x]
    return x
a, b = [json.dumps(strip(json.load(open(p, encoding="utf-8"))), ensure_ascii=False).encode("utf-8") for p in sys.argv[1:3]]
if a != b:
    i = next((k for k in range(min(len(a), len(b))) if a[k] != b[k]), min(len(a), len(b)))
    print("store equality: FAILED (memory and disk results differ at byte %d: ...%s... vs ...%s...)"
          % (i, a[max(0, i - 60):i + 60].decode("utf-8", "replace"), b[max(0, i - 60):i + 60].decode("utf-8", "replace")))
    sys.exit(1)
print("store equality: memory and disk results identical (timings removed, %d bytes)" % len(a))
EOF
