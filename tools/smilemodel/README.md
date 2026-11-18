# Smile model (core/Smile.java, core/SmileModel.java)

**Data** (not included): the SMILEs set (github.com/hromi/SMILEsmileD): 3,690 smiling and 9,475 not-smiling faces
from Labeled Faces in the Wild, 64 x 64 grey crops.

**Steps:**
1. `SmileFeatures.java` shows each face 3x bigger on a plain background, finds it with the app's face detector (YuNet:
   eyes, nose, mouth corners; 13,156 of 13,165 found) and writes `Smile.features()` for it: the same code the phone runs.
   Each face is also used mirrored and at 2 smaller sizes (eyes 14–60 px apart, found again at that size, half with
   noise).
2. `train.py` trains a 363 → 16 → 1 network (scikit-learn), every 5th face held out with all its copies, and writes
   `SmileModel.java`.

**Held-out faces** (2,630 faces, 10,239 rows): AUC 0.957, 90.7% right.

| Eyes apart | Right |
|---|---|
| under 20 px | 86% |
| 20–35 px | 89% |
| 35–60 px | 91% |
| 60 px and more | 91% |

`SceneTest smile_heldout.csv` checks that Java gives Python's probabilities (within 1e-4; 5e-7 in practice).

**Looking at the camera** is not trained: `Smile.facing()` uses where the nose sits between the eyes and the mouth.
