# Scene network and aesthetic score (SceneNet, Scene, Aesthetic)

How `app/src/main/assets/scene/scene.bin` and the weights in `core/Aesthetic.java` were made. Nothing here is needed
to build the app.

## The network
MobileNetV3-Small, 1.0, 224 px, ImageNet weights from Keras Applications (Apache 2.0, licence next to the model).
`export_mnv3.py` folds batch normalisation into the 54 convolutions and writes them as 16-bit floats (5 MB).
`core/SceneNet.java` runs it in plain Java; the block layout is fixed in the code.

`parity.py` puts the 16-bit weights back into Keras and prints its answer for a made-up input; `SceneTest` checks
the Java network gives the same top-5 classes and the same numbers (within 0.002).

## Scene
`core/Scene.java` adds up ImageNet classes per group. Checked on labelled pictures with `SceneDump.java` (the Java
network) and a script that applies the same lists:

| Pictures | Right |
|---|---|
| Oxford-IIIT Pets (7,390) + Imagenette springer spaniels | 95% pet |
| Caltech-101 planes, cars, motorbikes, helicopters, boats + Imagenette garbage trucks | 94% vehicle |
| Food-101, 9 dishes (917) + Caltech pizza and strawberries | 93% food |
| TensorFlow flower photos (3,670) + Caltech flowers, wild animals, Imagenette tench | 80% nature |
| Everything else in Caltech-101 and Imagenette (10,543) | 97% other |

A head learned on these pictures was right more often on the kinds it was trained on (99%) but called half of the
unseen kinds of "other" (gas pumps, churches, chairs...) food, nature or pets, so the fixed class lists were kept.
Adding vase, greenhouse, picket fence and cardoon to nature raised flowers from 75% to 80% and changed nothing else.

## Aesthetic score
`nima.py` scores every picture with the teacher, `SceneDump.java` writes the Java network's features, and
`train_aesthetic.py` fits a ridge regression from the network's 1024 features to NIMA's mean score.
- Teacher: NIMA, MobileNet aesthetic model by idealo (github.com/idealo/image-quality-assessment, Apache 2.0), trained on
  AVA; its own agreement with AVA's photographers is Spearman 0.61.
- Pictures: 33,598 from Imagenette, the flower photos, Caltech-101 and Oxford-IIIT Pets, plus 8,399 spoiled copies
  (`degrade.py`: blur, shake, dark, washed out, JPEG quality 3–8, pixelated, tilted, hazy, pocket shots).
- Every 5th picture with its spoiled copy held out (8,419): Pearson 0.81, Spearman 0.81 with NIMA, mean difference 0.35.
  A 16-unit network was no better (Spearman 0.81), so the linear layer (1,025 weights) is used.

| Held-out pictures | NIMA | This score |
|---|---|---|
| Originals | 4.62 | 4.57 |
| Blurred | 3.64 | 3.69 |
| Dark | 3.90 | 3.88 |
| Crushed JPEG | 3.34 | 3.56 |
| Pixelated | 3.17 | 3.34 |
| Pocket shots | 3.74 | 3.87 |
| Tilted | 4.66 | 4.50 |
