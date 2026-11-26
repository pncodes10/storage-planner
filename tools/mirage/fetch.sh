#!/usr/bin/env bash
# Downloads Mirage and the Cactus engine for Android (64-bit ARM) from Hugging Face, at pinned revisions, and checks
# every file against tools/mirage/files.sha256 (the same hashes the cactus-compute 4.2.6 Python package pins).
#   build/mirage/mirage.cact                  the model, 21.6 MB (Cactus-Compute/mirage, Apache 2.0): only with WITH_MODEL=1, for
#                                             local testing. It is NOT packed in the APK: the app downloads it itself once
#                                             (MirageModel.java, same revision and hash) and keeps it across updates.
#   build/mirage/libcactus.a, cactus.h        the engine (Cactus-Compute/cactus releases/4.1.4/android-arm64, Apache 2.0)
# Without the engine the app still builds; the Lab then says Mirage is not available.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
MODEL_REV=76c1e9e53381aca8fc3843c636216c96ff220122
ENGINE_REV=f4ab2741203a282c6e7b89983de2b5e5b2e805a8
HF=https://huggingface.co
mkdir -p "$HERE/build/mirage"
get() {   # url, destination, name in files.sha256
  local want; want=$(awk -v n="$3" '$2 == n {print $1}' "$HERE/tools/mirage/files.sha256")
  if [ -f "$2" ] && [ "$(sha256sum "$2" | cut -d' ' -f1)" = "$want" ]; then echo "have $3"; return; fi
  for i in 1 2 3 4; do curl -fsSL --retry 3 -o "$2.part" "$1" && break || sleep $((i * 5)); done
  local got; got=$(sha256sum "$2.part" | cut -d' ' -f1)
  if [ "$got" != "$want" ]; then rm -f "$2.part"; echo "$3: sha256 $got, expected $want" >&2; exit 1; fi
  mv "$2.part" "$2"; echo "got $3"
}
if [ "${WITH_MODEL:-0}" = 1 ]; then get "$HF/Cactus-Compute/mirage/resolve/$MODEL_REV/mirage.cact" "$HERE/build/mirage/mirage.cact" mirage.cact; fi
get "$HF/Cactus-Compute/cactus/resolve/$ENGINE_REV/releases/4.1.4/android-arm64/libcactus.a" "$HERE/build/mirage/libcactus.a" libcactus.a
get "$HF/Cactus-Compute/cactus/resolve/$ENGINE_REV/releases/4.1.4/android-arm64/cactus.h" "$HERE/build/mirage/cactus.h" cactus.h
