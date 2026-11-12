package com.storageplanner.v2.features;

import com.storageplanner.v2.model.ArchiveEntry;

import java.util.List;

/** Refines a zip by its entry names (PHASE_2 3.4 Part A). */
public final class ArchiveSubtype {
    private ArchiveSubtype() {}

    /** @param mimetype content of a first STORED "mimetype" entry, or null */
    public static String of(List<ArchiveEntry> entries, String mimetype, String manifestJsonOrNull) {
        boolean manifest = false, dex = false, arsc = false, ct = false, word = false, xl = false, ppt = false, mf = false, toc = false, mjson = false, info = false;
        int apks = 0;
        for (ArchiveEntry e : entries) {
            if (e.depth != 0) continue;
            String p = e.path;
            if (p.equals("AndroidManifest.xml")) manifest = true;
            else if (p.matches("classes\\d*\\.dex")) dex = true;
            else if (p.equals("resources.arsc")) arsc = true;
            else if (p.equals("[Content_Types].xml")) ct = true;
            else if (p.equals("META-INF/MANIFEST.MF")) mf = true;
            else if (p.equals("toc.pb")) toc = true;
            else if (p.equals("manifest.json")) mjson = true;
            else if (p.equals("info.json")) info = true;
            if (p.startsWith("word/")) word = true; if (p.startsWith("xl/")) xl = true; if (p.startsWith("ppt/")) ppt = true;
            if (!p.contains("/") && p.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) apks++;
        }
        if (manifest && (dex || arsc)) return "apk";
        if (apks >= 1 && (toc || mjson || info)) return toc ? "apks" : "xapk";
        if (ct && word) return "ooxml-docx";
        if (ct && xl) return "ooxml-xlsx";
        if (ct && ppt) return "ooxml-pptx";
        if (mimetype != null) {
            if (mimetype.startsWith("application/vnd.oasis.opendocument.")) return "odf";
            if (mimetype.startsWith("application/epub+zip")) return "epub";
        }
        if (mf && !manifest && !dex) return "jar";
        return null;
    }
}
