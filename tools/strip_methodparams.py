"""Remove MethodParameters attributes from .class files (in place).

JDK 21's javac emits MethodParameters with unnamed 'mandated' entries for inner-class
constructors; the D8 1.5.x available offline crashes on them. The attribute only carries
parameter names for reflection, so removing it does not change behaviour.
usage: strip_methodparams.py <classes_dir>
"""
import os, sys, struct

def strip(data):
    p = 10; n = struct.unpack(">H", data[8:10])[0]; utf8 = {}; i = 1
    while i < n:
        tag = data[p]
        if tag == 1:
            ln = struct.unpack(">H", data[p + 1:p + 3])[0]; utf8[i] = data[p + 3:p + 3 + ln]; p += 3 + ln
        elif tag in (3, 4): p += 5
        elif tag in (5, 6): p += 9; i += 1
        elif tag in (7, 8, 16, 19, 20): p += 3
        elif tag in (9, 10, 11, 12, 17, 18): p += 5
        elif tag == 15: p += 4
        else: raise ValueError(f"unknown constant tag {tag}")
        i += 1
    out = bytearray(data[:p]); p += 6                       # access, this, super
    out += data[p - 6:p]
    ni = struct.unpack(">H", data[p:p + 2])[0]; out += data[p:p + 2 + 2 * ni]; p += 2 + 2 * ni
    removed = 0
    def members(drop):
        nonlocal p, removed
        cnt = struct.unpack(">H", data[p:p + 2])[0]; out.extend(data[p:p + 2]); p += 2
        for _ in range(cnt):
            out.extend(data[p:p + 6]); p += 6
            attrs(drop)
    def attrs(drop):
        nonlocal p, removed
        cnt = struct.unpack(">H", data[p:p + 2])[0]; p += 2; kept = []
        for _ in range(cnt):
            name_i, ln = struct.unpack(">HI", data[p:p + 6]); blob = data[p:p + 6 + ln]; p += 6 + ln
            if drop and utf8.get(name_i) == b"MethodParameters": removed += 1; continue
            kept.append(blob)
        out.extend(struct.pack(">H", len(kept))); [out.extend(b) for b in kept]
    members(False)          # fields
    members(True)           # methods
    attrs(False)            # class attributes
    assert p == len(data), "trailing bytes"
    return bytes(out), removed

if __name__ == "__main__":
    total = 0
    for root, _, fs in os.walk(sys.argv[1]):
        for f in fs:
            if f.endswith(".class"):
                fp = os.path.join(root, f); new, r = strip(open(fp, "rb").read())
                if r: open(fp, "wb").write(new); total += r
    print(f"stripped {total} MethodParameters attributes")
