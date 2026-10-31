"""Records what OpenCV's own FaceDetectorYN finds in a folder of pictures, for jvmtest FaceNetTest
(the app's pure-Java YuNet must give the same faces). Writes <dir>/expected.tsv:
picture, number of faces, then per face (';'-separated) OpenCV's 15 numbers (box, 5 landmarks, score).
usage: expected_faces.py <face_detection_yunet_2023mar.onnx> <dir with .png/.jpg pictures>
(score threshold 0.5 and NMS 0.3, as FaceNetTest runs it; needs opencv-python >= 4.8)"""
import sys, os, cv2
model, d = sys.argv[1], sys.argv[2]
rows = []
for name in sorted(os.listdir(d)):
    if not name.lower().endswith((".png", ".jpg", ".jpeg")): continue
    im = cv2.imread(os.path.join(d, name))
    h, w = im.shape[:2]
    det = cv2.FaceDetectorYN.create(model, "", (w, h), 0.5, 0.3, 5000)
    _, faces = det.detect(im)
    faces = [] if faces is None else faces
    rows.append(name + "\t" + str(len(faces)) + "\t" + ";".join(",".join("%.4f" % v for v in f) for f in faces))
open(os.path.join(d, "expected.tsv"), "w").write("\n".join(rows) + "\n")
print(len(rows), "pictures,", sum(int(r.split("\t")[1]) for r in rows), "faces")
