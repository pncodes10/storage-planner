package com.storageplanner.v2.features;

import com.storageplanner.v2.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android binary XML manifest parser (PHASE_2 5 step 2), plus the text-XML stand-in used by the dataset's APKs. */
public final class AxmlParser {
    private AxmlParser() {}
    static final int VERSION_CODE = 0x0101021b, VERSION_NAME = 0x0101021c, MIN_SDK = 0x0101020c, TARGET_SDK = 0x01010270,
            VERSION_CODE_MAJOR = 0x01010576, IS_SPLIT_REQUIRED = 0x01010591;

    public static final class ManifestInfo {
        public String packageName, versionName, split, problem, source;
        public long versionCode = -1, versionCodeMajor = 0;
        public int minSdk = -1, targetSdk = -1;
        public boolean splitRequired;
    }

    public static ManifestInfo parse(byte[] b) {
        ManifestInfo m = new ManifestInfo();
        if (b.length >= 8 && Bytes.u16le(b, 0) == 0x0003) { m.source = "ZIP_AXML"; binary(b, m); return m; }
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.contains("<manifest")) { m.source = "ZIP_TEXT"; text(s, m); return m; }
        m.problem = "AXML_NOT_XML";
        return m;
    }

    static void binary(byte[] b, ManifestInfo m) {
        try {
            long total = Bytes.u32le(b, 4);
            int end = (int) Math.min(total, b.length);
            int p = Bytes.u16le(b, 2);
            List<String> pool = new ArrayList<String>();
            int[] resMap = new int[0];
            int depth = 0;
            for (int k = 0; k < 100000 && p + 8 <= end; k++) {
                int type = Bytes.u16le(b, p), hs = Bytes.u16le(b, p + 2);
                long size = Bytes.u32le(b, p + 4);
                if (size < 8 || p + size > end || hs > size) { m.problem = "AXML_CHUNK_SIZE"; return; }
                if (type == 0x0001) {
                    long n = Bytes.u32le(b, p + 8);
                    if (n > 100000) { m.problem = "AXML_STRING_LIMIT"; return; }
                    int flags = (int) Bytes.u32le(b, p + 16);
                    long strStart = Bytes.u32le(b, p + 20);
                    boolean utf8 = (flags & 0x100) != 0;
                    for (int i = 0; i < n; i++) {
                        int off = (int) (p + strStart + Bytes.u32le(b, p + hs + 4 * i));
                        pool.add(str(b, off, utf8, (int) (p + size)));
                    }
                } else if (type == 0x0180) {
                    int n = (int) ((size - hs) / 4);
                    resMap = new int[n];
                    for (int i = 0; i < n; i++) resMap[i] = (int) Bytes.u32le(b, p + hs + 4 * i);
                } else if (type == 0x0102) {
                    if (++depth > 64) { m.problem = "AXML_DEPTH"; return; }
                    int ext = p + hs;
                    long nameIdx = Bytes.u32le(b, ext + 4);
                    int attrStart = Bytes.u16le(b, ext + 8), attrSize = Bytes.u16le(b, ext + 10), attrCount = Bytes.u16le(b, ext + 12);
                    if (attrCount > 1000) { m.problem = "AXML_ATTR_LIMIT"; return; }
                    String el = nameIdx < pool.size() ? pool.get((int) nameIdx) : "";
                    for (int i = 0; i < attrCount; i++) {
                        int a = ext + attrStart + i * attrSize;
                        if (a + 20 > p + size) { m.problem = "AXML_ATTR_PAST_END"; break; }
                        long ns = Bytes.u32le(b, a), name = Bytes.u32le(b, a + 4), raw = Bytes.u32le(b, a + 8);
                        int dt = b[a + 15] & 0xff; long data = Bytes.u32le(b, a + 16);
                        int rid = name < resMap.length ? resMap[(int) name] : 0;
                        String an = name < pool.size() ? pool.get((int) name) : "";
                        String sv = dt == 0x03 && data < pool.size() ? pool.get((int) data) : raw != 0xFFFFFFFFL && raw < pool.size() ? pool.get((int) raw) : null;
                        Long nv = dt == 0x10 || dt == 0x11 || dt == 0x12 ? data : null;
                        if (dt == 0x01) { sv = null; nv = null; }   // REFERENCE: resource tables are not parsed
                        if (el.equals("manifest") && ns == 0xFFFFFFFFL && an.equals("package")) m.packageName = sv;
                        else if (el.equals("manifest") && ns == 0xFFFFFFFFL && an.equals("split")) m.split = sv;
                        else if (rid == VERSION_CODE || (rid == 0 && an.equals("versionCode"))) m.versionCode = nv != null ? nv : parseLong(sv);
                        else if (rid == VERSION_NAME || (rid == 0 && an.equals("versionName"))) m.versionName = sv;
                        else if (rid == MIN_SDK || (rid == 0 && an.equals("minSdkVersion"))) m.minSdk = (int) (nv != null ? nv : parseLong(sv));
                        else if (rid == TARGET_SDK || (rid == 0 && an.equals("targetSdkVersion"))) m.targetSdk = (int) (nv != null ? nv : parseLong(sv));
                        else if (rid == VERSION_CODE_MAJOR || (rid == 0 && an.equals("versionCodeMajor"))) m.versionCodeMajor = nv != null ? nv : Math.max(0, parseLong(sv));
                        else if (rid == IS_SPLIT_REQUIRED) m.splitRequired = nv != null && nv != 0;
                    }
                } else if (type == 0x0103) depth--;
                p += size;
            }
        } catch (RuntimeException e) {
            m.problem = "AXML_EXCEPTION";
        }
        if (m.versionCode >= 0) m.versionCode = (m.versionCodeMajor << 32) | (m.versionCode & 0xFFFFFFFFL);
    }

    static long parseLong(String s) { try { return s == null ? -1 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; } }

    static String str(byte[] b, int off, boolean utf8, int end) {
        if (utf8) {
            int p = off;
            int u16len = b[p] & 0xff; p += (u16len & 0x80) != 0 ? 2 : 1;
            int len = b[p] & 0xff; if ((len & 0x80) != 0) { len = ((len & 0x7F) << 8) | (b[p + 1] & 0xff); p += 2; } else p++;
            if (p + len > end) throw new IndexOutOfBoundsException("string past chunk");
            return new String(b, p, len, StandardCharsets.UTF_8);
        }
        int len = Bytes.u16le(b, off), p = off + 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7FFF) << 16) | Bytes.u16le(b, off + 2); p += 2; }
        if (p + 2L * len > end) throw new IndexOutOfBoundsException("string past chunk");
        return new String(b, p, 2 * len, StandardCharsets.UTF_16LE);
    }

    static String attr(String s, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Text stand-in (dataset APKs carry a plain-text AndroidManifest.xml, not binary XML). */
    static void text(String s, ManifestInfo m) {
        Matcher mm = Pattern.compile("<manifest\\b([^>]*)>", Pattern.DOTALL).matcher(s);
        if (!mm.find()) { m.problem = "AXML_TEXT_NO_MANIFEST"; return; }
        String a = mm.group(1);
        m.packageName = attr(a, "package"); m.split = attr(a, "split");
        m.versionCode = parseLong(attr(a, "android:versionCode")); m.versionName = attr(a, "android:versionName");
        long major = parseLong(attr(a, "android:versionCodeMajor"));
        if (major > 0 && m.versionCode >= 0) m.versionCode = (major << 32) | m.versionCode;
        Matcher u = Pattern.compile("<uses-sdk\\b([^>]*)>", Pattern.DOTALL).matcher(s);
        if (u.find()) { m.minSdk = (int) parseLong(attr(u.group(1), "android:minSdkVersion")); m.targetSdk = (int) parseLong(attr(u.group(1), "android:targetSdkVersion")); }
    }
}
