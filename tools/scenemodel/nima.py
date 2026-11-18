"""NIMA aesthetic score (idealo/image-quality-assessment, MobileNet, Apache 2.0) for each picture in a list.
Usage: python3 nima.py list.txt nima.tsv weights_mobilenet_aesthetic_0.07.hdf5 (one line per picture: mean score, then the 10 probabilities)"""
import sys, numpy as np, tensorflow as tf
from PIL import Image
base = tf.keras.applications.MobileNet(input_shape=(224, 224, 3), include_top=False, pooling='avg', weights=None)
x = tf.keras.layers.Dropout(0.75)(base.output)
x = tf.keras.layers.Dense(10, activation='softmax')(x)
m = tf.keras.Model(base.input, x)
m.load_weights(sys.argv[3])
paths = [l.strip() for l in open(sys.argv[1])]
out = open(sys.argv[2], 'w')
B = 64
for i in range(0, len(paths), B):
    xs = []
    for p in paths[i:i + B]:
        try: im = Image.open(p).convert('RGB').resize((224, 224), Image.NEAREST)
        except Exception: im = Image.new('RGB', (224, 224))
        xs.append(np.asarray(im, np.float32) / 127.5 - 1)
    pr = m.predict(np.stack(xs), verbose=0)
    for p, d in zip(paths[i:i + B], pr):
        out.write('%.4f\t%s\n' % ((d * np.arange(1, 11)).sum(), ' '.join('%.4f' % v for v in d)))
    if i % 2048 == 0: print(i, flush=True)
out.close()
