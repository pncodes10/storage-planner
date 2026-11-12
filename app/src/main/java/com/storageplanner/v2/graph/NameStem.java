package com.storageplanner.v2.graph;

import com.storageplanner.v2.util.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Name stems (PHASE_3 5.3): NFC, lower case, no extension, up to 3 trailing edit/copy suffixes stripped. */
public final class NameStem {
    private NameStem() {}

    public static final class Parsed {
        public String stem;
        public boolean copySuffix, editSuffix;
    }

    static final Pattern COPY = Pattern.compile("(~\\d+| ?\\(\\d+\\)| - copy)$");
    static final Pattern EDIT = Pattern.compile("([-_]edited|[-_]edit|[-_](annotated|markup|text_added|filtered|cropped|crop|mirrored|rotated|collage|snapseed|copy))$");
    static final Pattern SEQ = Pattern.compile("(?<=\\d{6})[-_]\\d{1,2}$");
    static final Pattern RESIZE = Pattern.compile("((?<=\\d{6})[-_]\\d{3,4}(px)?|[-_]\\d{3,4}px)$");
    public static final Pattern WA = Pattern.compile("^(img-\\d{8}-wa\\d{4}|whatsapp image .*)$");
    static final Pattern GENERIC = Pattern.compile("^(temp\\d*|tmp\\d*|document\\d*|file\\d*|untitled\\d*|download\\d*|new( document)?\\d*"
            + "|img-\\d{8}-wa\\d{4}|[0-9a-f]{16,}|\\d+)$");

    public static Parsed parse(String name) {
        Parsed p = new Parsed();
        String s = Text.nfc(name == null ? "" : name).toLowerCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        for (int i = 0; i < 3; i++) {
            Matcher m;
            if ((m = COPY.matcher(s)).find()) { s = s.substring(0, m.start()); p.copySuffix = true; continue; }
            if ((m = EDIT.matcher(s)).find()) {
                if (m.group().endsWith("copy")) p.copySuffix = true; else p.editSuffix = true;
                s = s.substring(0, m.start()); continue;
            }
            if ((m = SEQ.matcher(s)).find()) { s = s.substring(0, m.start()); p.editSuffix = true; continue; }
            if ((m = RESIZE.matcher(s)).find()) {
                s = s.substring(0, m.start()); p.editSuffix = true; continue;
            }
            break;
        }
        p.stem = s.trim();
        return p;
    }

    public static String of(String name) { return parse(name).stem; }

    /** Generic name (keeper nameRank 1, PHASE_3 8.1). */
    public static boolean generic(String name) {
        Parsed p = parse(name);
        if (p.copySuffix) return true;
        return GENERIC.matcher(p.stem).matches();
    }
}
