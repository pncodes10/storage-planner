package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

import java.util.*;

/** PHASE_4 2 stage 4: deterministic signals per evidence family. */
public final class SignalExtractor {
    private SignalExtractor() {}
    public static void extract(FileContext ctx) {
        List<Signal> s = ctx.signals; s.clear();
        FileRecord f = ctx.f;
        String kw = Keywords.hit(f.path);
        if (Keywords.record(f.path)) s.add(new Signal(EvidenceFamily.PROV, "PATH_KEYWORD_RECORD", +1, 0.9, "path:" + kw));
        else if (Keywords.sentimental(f.path)) s.add(new Signal(EvidenceFamily.PROV, "PATH_KEYWORD_SENTIMENTAL", +1, 0.8, "path:" + kw));
        if (f.name != null && com.storageplanner.v2.graph.NameStem.WA.matcher(com.storageplanner.v2.graph.NameStem.of(f.name)).matches()
                && f.name.matches(".*\\(\\d+\\).*")) s.add(new Signal(EvidenceFamily.PROV, "WA_FORWARD_NAME", -1, 0.5, "name"));
        ExifInfo ex = ctx.feat == null ? null : ctx.feat.exif;
        if (ex != null && (exHas(ex, "gps") || exHas(ex, "make"))) s.add(new Signal(EvidenceFamily.CAPTURE, "EXIF_CAMERA_OR_GPS", +1, 0.5, "exif"));
        if (ctx.usage != null && ctx.usage.junk()) s.add(new Signal(EvidenceFamily.STRUCT, "LOCATION_" + ctx.usage, -1, 1.0, "area:" + f.area));
        ApkInfo apk = ctx.apk();
        if (apk != null && apk.installState == InstallState.SAME_VERSION) s.add(new Signal(EvidenceFamily.APP, "APK_SAME_VERSION_INSTALLED", -1, 0.9, "apk"));
        if (ctx.classification != null) s.add(new Signal(EvidenceFamily.PROV, "RULES_" + ctx.classification.category.replace(' ', '_').toUpperCase(Locale.ROOT), +1, 0.9, "classification"));
    }
    static boolean exHas(ExifInfo ex, String what) {
        return what.equals("make") ? ex.make != null && !ex.make.isEmpty() : ex.hasGps == Tri.TRUE;
    }
    /** Strongest signal of a family in a direction (within-family max, PHASE_4 1.3 a). */
    public static Signal strongest(List<Signal> s, EvidenceFamily fam, int dir) {
        Signal best = null;
        for (Signal x : s) if (x.family == fam && x.direction == dir && (best == null || x.strength > best.strength)) best = x;
        return best;
    }
}
