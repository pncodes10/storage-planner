# Mirage (Algorithm Lab)

[Mirage](https://cactuscompute.com/blog/mirage) (Cactus Compute, 6 Nov 2026): 51.3 M parameters, mostly 2-bit,
21.6 MB. One pass gives the scene, named objects (boxes, masks, descriptions), text lines (Latin script) and relations.
Weights: [Cactus-Compute/mirage](https://huggingface.co/Cactus-Compute/mirage) (Apache 2.0). Engine:
[Cactus-Compute/cactus](https://huggingface.co/Cactus-Compute/cactus) `releases/4.1.4` (Apache 2.0, prebuilt binaries
included). The revisions and hashes are the ones the `cactus-compute` 4.2.6 Python package pins.

| File | Where | |
|---|---|---|
| `fetch.sh` | `app/src/main/assets/mirage/mirage.cact`, `build/mirage/libcactus.a`, `build/mirage/cactus.h` | downloads at pinned revisions, checks `files.sha256` |
| `build_native.sh` | `build/mirage/lib/arm64-v8a/libmirage.so` | links `app/src/main/cpp/mirage_jni.cpp` with the engine (NDK r28+: the engine uses LLVM 19's libc++) |
| `files.sha256` | | the model, the engine, its header, the licence |

The bridge calls only `cactus_load` (the engine copies the model, so Java's copy is freed) and `cactus_describe` (RGB
in, JSON out; a bigger buffer is tried when the engine says the answer needs more room). The engine's one call that can
remove a file sits in a tool-index cache of the Needle text model that Mirage never reaches; the bridge defines its own
`remove()` that always refuses, so the library cannot delete a file, and `tools/audit_apk.py` checks the built library
imports no `remove`, `unlink`, `rename`, network, process or library-loading functions.

## How it compared (desktop test, 4-core x86 processor, Python package 4.2.6)

Not on a phone: Cactus quotes 107 ms on a Galaxy S26's processor; here it took 1.4–2.2 s per picture, whichever
sections were asked for.

**Scene**, from the object names (simple word lists: dog/cat -> pet, plate/tomato -> food...), 60 labelled pictures each:

| Pictures | Scene network (the app's) | Mirage |
|---|---|---|
| Oxford pets | 93% | 97% |
| Food-101 (different pictures) | 93% | 97% |
| Flowers, wild animals | 78% | 90% |
| Cars, planes, motorbikes, boats | 97% | 100% |
| Other things (chairs, lamps, churches...) | 97% | 75% |
| The same 240 pictures | 91.2% | 90.4% |

Mirage names trees and grass in many pictures where they barely show (23 of 60 pet pictures), and its form answers
(pick one of our labels) were poor: pets and cars came out as "person" or as no answer.

**Text**, 5 invented documents (receipt, ticket, bill, shopping list, chat screenshot): every line exact on the clean
images; photographed on a table (tilted, slightly blurred) a few letters wrong ("B4.10" for 84.10, "mufin",
"Coppuccino", "Thank youl"), no line missed. The scene "style" said "document" / "screenshot" on the clean images but
"graphic design" / "minimalist" on the photos.
