# Eye open / closed model (EyeState)

How the network in `core/EyeState.java` was made. The model ships inside the app; nothing here is
needed to build it.

**Data** (not included here):
- **Closed eyes:** 1,192 face crops from Closed Eyes In The Wild (CEW), as mirrored in
  github.com/jesusmartinoza/Open-eyes-using-Pix2Pix (`closed_eyes_dataset/`).
- **Open eyes:** 2,400 of the 6,724 aligned faces in the same repository (`regular_faces/`).

**Steps:**
1. `detect.py`: YuNet (OpenCV Zoo) finds both eye centres in each face. The pipeline only uses the
   midpoint and the eye distance, which is what Android's `FaceDetector` reports.
2. `feat.py`: the patch and feature code. `EyeState.java` is a line-by-line port of it.
3. `build_ds.py`: builds the training set with augmentations so the two sources look alike:
   - random down-scaling to 16–110 px eye distance;
   - roll of the open faces;
   - JPEG quality 40–95 and noise;
   - eye-position error of 6%.
4. `train2.py` compares models. `export.py` trains the shipped one (304 → 16 → 1 MLP, 80% of faces)
   and writes the weights.
5. `parity.py` writes the 716 held-out faces for `jvmtest/.../EyeStateTest`, which checks:
   - **Parity:** Java and Python agree to 1e-14.
   - **Accuracy:** with exact eye positions, 97% (AUC 0.996); with 6% position error, 98%; with 12%
     error, 91%.

**Check that the results are not a dataset artefact.** The closed faces vary in size and the open
faces don't, so accuracy was checked separately for small, medium and large closed faces. AUC was
0.985, 0.989 and 0.986, so the model reads the eyes, not the image size.
