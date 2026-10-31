"""Minimal APK packager + APK Signature Scheme v2 signer (no Android SDK needed).

usage: apkbuild.py <base.apk from aapt2 link> <classes.dex> <out.apk> <keystore_dir>

- Copies entries from the aapt2 output (AndroidManifest.xml, resources.arsc, res/...),
  adds classes.dex, stores resources.arsc uncompressed and 4-byte aligned (required for
  targetSdk >= 30), then signs with APK Signature Scheme v2 (RSA-2048, SHA-256).
- Key + self-signed certificate are created once in <keystore_dir> and reused, so later
  builds install as updates.
Spec: https://source.android.com/docs/security/features/apksigning/v2
"""
import os, sys, struct, zipfile, hashlib, datetime
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding

V2_BLOCK_ID = 0x7109871A
ALG_RSA_PKCS1_SHA256 = 0x0103

def lp(b): return struct.pack("<I", len(b)) + b                     # uint32 length-prefixed

def load_or_make_key(d):
    os.makedirs(d, exist_ok=True)
    kp, cp = os.path.join(d, "key.pem"), os.path.join(d, "cert.der")
    if os.path.exists(kp) and os.path.exists(cp):
        key = serialization.load_pem_private_key(open(kp, "rb").read(), None)
        return key, open(cp, "rb").read()
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Storage Planner"),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Storage Planner (self-signed)")])
    now = datetime.datetime(2026, 1, 1)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now)
            .not_valid_after(now + datetime.timedelta(days=365 * 30)).sign(key, hashes.SHA256()))
    open(kp, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    der = cert.public_bytes(serialization.Encoding.DER); open(cp, "wb").write(der)
    return key, der

def package(base_apk, dex, out_unsigned):
    """Write a zip with 4-byte-aligned STORED entries (resources.arsc must be uncompressed)."""
    with zipfile.ZipFile(base_apk) as src, open(out_unsigned, "wb") as f:
        z = zipfile.ZipFile(f, "w")
        entries = [(i.filename, src.read(i.filename)) for i in src.infolist()]
        entries = [e for e in entries if e[0] != "classes.dex"] + [("classes.dex", open(dex, "rb").read())]
        entries.sort(key=lambda e: (e[0] != "AndroidManifest.xml", e[0]))
        for name, data in entries:
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            stored = name == "resources.arsc" or name.endswith((".png", ".arsc"))
            zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
            if stored:
                hdr_end = f.tell() + 30 + len(name.encode())
                zi.extra = b"\0" * ((4 - hdr_end % 4) % 4)
            z.writestr(zi, data)
        z.close()

def chunk_digest(parts):
    """v2 content digest: 1 MiB chunks over the three sections, SHA-256."""
    chunks = []
    for p in parts:
        for i in range(0, len(p), 1 << 20):
            c = p[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(c)) + c).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()

def sign_v2(unsigned, out, key, cert_der):
    data = open(unsigned, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    cd_size, cd_off = struct.unpack("<II", data[eocd + 12:eocd + 20])
    entries, cd, eo = data[:cd_off], data[cd_off:cd_off + cd_size], bytearray(data[eocd:])
    # digest is computed with the EOCD's CD-offset pointing at the start of the signing block
    eo_for_digest = bytearray(eo); struct.pack_into("<I", eo_for_digest, 16, cd_off)
    digest = chunk_digest([entries, cd, bytes(eo_for_digest)])
    digests = lp(lp(struct.pack("<I", ALG_RSA_PKCS1_SHA256) + lp(digest)))
    certs = lp(lp(cert_der))
    signed_data = digests + certs + lp(b"")                        # no additional attributes
    sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed_data) + lp(lp(struct.pack("<I", ALG_RSA_PKCS1_SHA256) + lp(sig))) + lp(pub)
    value = lp(lp(signer))
    pair = struct.pack("<Q", 4 + len(value)) + struct.pack("<I", V2_BLOCK_ID) + value
    block_size = len(pair) + 8 + 16                                # pairs + trailing size + magic
    block = struct.pack("<Q", block_size) + pair + struct.pack("<Q", block_size) + b"APK Sig Block 42"
    struct.pack_into("<I", eo, 16, cd_off + len(block))            # CD now starts after the block
    open(out, "wb").write(entries + block + cd + bytes(eo))

if __name__ == "__main__":
    base, dex, out, kdir = sys.argv[1:5]
    key, cert = load_or_make_key(kdir)
    tmp = out + ".unsigned"
    package(base, dex, tmp); sign_v2(tmp, out, key, cert); os.remove(tmp)
    print(f"signed {out} ({os.path.getsize(out)} bytes), cert sha256 {hashlib.sha256(cert).hexdigest()[:16]}…")
