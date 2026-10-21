"""Converts OpenCV Zoo's YuNet face detector (face_detection_yunet_2023mar.onnx, MIT licence) into the small
binary the app's pure-Java runner (core/FaceNet.java) reads: the layers in order, with their weights.
Source: github.com/opencv/opencv_zoo models/face_detection_yunet/face_detection_yunet_2023mar.onnx
(sha256 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4). The output is pinned in scripts/face_assets.sha256.
usage: export_yunet.py <yunet.onnx> <out.bin>"""
import sys, struct, onnx, numpy as np
from onnx import numpy_helper
m = onnx.load(sys.argv[1]); g = m.graph
W = {t.name: numpy_helper.to_array(t) for t in g.initializer}
for n in g.node:
    if n.op_type == 'Constant':
        W[n.output[0]] = numpy_helper.to_array(n.attribute[0].t)
HEADS = {'cls': ['258', '260', '262'], 'obj': ['270', '272', '274'], 'bbox': ['264', '266', '268'], 'kps': ['276', '278', '280']}
ids = {'input': 0}
def tid(name):
    if name not in ids: ids[name] = len(ids)
    return ids[name]
ops = []    # (type, inputs, output, payload)
for n in g.node:
    a = {x.name: onnx.helper.get_attribute_value(x) for x in n.attribute}
    if n.op_type == 'Conv':
        w = W[n.input[1]].astype(np.float32); b = W[n.input[2]].astype(np.float32)
        assert a['dilations'] == [1, 1] and a['kernel_shape'][0] == a['kernel_shape'][1] and len(set(a['pads'])) == 1
        ops.append((1, [tid(n.input[0])], tid(n.output[0]), (w.shape[0], w.shape[1] * a['group'], a['kernel_shape'][0], a['strides'][0], a['pads'][0], a['group'], w, b)))
    elif n.op_type == 'Relu': ops.append((2, [tid(n.input[0])], tid(n.output[0]), None))
    elif n.op_type == 'MaxPool':
        assert a['kernel_shape'] == [2, 2] and a['strides'] == [2, 2] and a.get('ceil_mode', 0) == 0
        ops.append((3, [tid(n.input[0])], tid(n.output[0]), None))
    elif n.op_type == 'Resize':
        sc = W[n.input[2]]; assert list(sc) == [1, 1, 2, 2] and a['mode'] == b'nearest', sc
        ops.append((4, [tid(n.input[0])], tid(n.output[0]), None))
    elif n.op_type == 'Add': ops.append((5, [tid(n.input[0]), tid(n.input[1])], tid(n.output[0]), None))
    elif n.op_type in ('Transpose', 'Reshape', 'Sigmoid'): continue      # output formatting: done in Java
    else: raise SystemExit('unsupported op ' + n.op_type)
last = {}
for i, (t, ins, out, p) in enumerate(ops):
    for x in ins: last[x] = i
heads = [ids[h] for k in ('cls', 'obj', 'bbox', 'kps') for h in HEADS[k]]
with open(sys.argv[2], 'wb') as f:
    f.write(b'YUNT'); f.write(struct.pack('<iii', len(ids), len(ops), len(heads)))
    f.write(struct.pack('<%di' % len(heads), *heads))
    for i, (t, ins, out, p) in enumerate(ops):
        free = [x for x in ins if last[x] == i and x not in heads]
        f.write(struct.pack('<iii', t, len(ins), out)); f.write(struct.pack('<%di' % len(ins), *ins))
        f.write(struct.pack('<i', len(free))); f.write(struct.pack('<%di' % len(free), *free))
        if t == 1:
            cout, cin, k, s, pad, grp, w, b = p
            f.write(struct.pack('<6i', cout, cin, k, s, pad, grp)); f.write(w.astype('<f4').tobytes()); f.write(b.astype('<f4').tobytes())
print(len(ops), 'layers,', len(ids), 'tensors,', sum(p[6].size + p[7].size for t, _, _, p in ops if t == 1), 'weights')
