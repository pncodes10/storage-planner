package com.storageplanner.v2.features;

import com.storageplanner.v2.model.NameDate;
import com.storageplanner.v2.model.Unknown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dates in file names (PHASE_2 4). First valid match wins. */
public final class NameDates {
    private NameDates() {}
    static final Pattern CAM = Pattern.compile("^(?:IMG|VID|PXL|MVIMG|PANO|BURST\\d*|DSC|MOV)?[_-]?(\\d{8})[_-]?(\\d{6})(\\d{0,3})(?:\\D.*)?$");
    static final Pattern SHOT1 = Pattern.compile("^Screenshot[_ -](\\d{8})[-_](\\d{6}).*$");
    static final Pattern SHOT2 = Pattern.compile("^Screenshot[_ ](\\d{4})-(\\d{2})-(\\d{2})[-_ ](\\d{2})[-.](\\d{2})[-.](\\d{2}).*$");
    static final Pattern WA = Pattern.compile("^(?:IMG|VID|AUD|PTT|DOC|STK)-(\\d{8})-WA\\d{4}.*$");
    static final Pattern WA_DESK = Pattern.compile("^WhatsApp (?:Image|Video|Audio) (\\d{4})-(\\d{2})-(\\d{2}) at (\\d{1,2})\\.(\\d{2})\\.(\\d{2})(?: ?([AP]M))?(?: \\(\\d+\\))?$");
    static final Pattern SAMSUNG = Pattern.compile("^(\\d{8})_(\\d{6})(?:\\D.*)?$");
    static final Pattern GENERIC = Pattern.compile("(?<!\\d)(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})(?!\\d)");

    static int n(String s) { return Integer.parseInt(s); }

    static NameDate mk(long at, String precision, String pattern, String source, long now) {
        if (!Dates.plausible(at, now)) return null;
        NameDate d = new NameDate(); d.at = at; d.precision = precision; d.pattern = pattern; d.source = source; return d;
    }
    static long ymdHms(String ymd, String hms, int ms) {
        return Dates.utc(n(ymd.substring(0, 4)), n(ymd.substring(4, 6)), n(ymd.substring(6, 8)),
                n(hms.substring(0, 2)), n(hms.substring(2, 4)), n(hms.substring(4, 6)), ms);
    }

    /** @param name file name with or without extension */
    public static NameDate parse(String name, long now) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name;
        Matcher m;
        NameDate r;
        if ((m = CAM.matcher(b)).matches()) {
            String extra = m.group(3);
            int ms = extra.length() == 3 ? n(extra) : 0;
            if ((r = mk(ymdHms(m.group(1), m.group(2), ms), extra.length() == 3 ? "MILLI" : "SECOND", "CAM_YMD_HMS", "camera", now)) != null) return r;
        }
        if ((m = SHOT1.matcher(b)).matches() && (r = mk(ymdHms(m.group(1), m.group(2), 0), "SECOND", "SCREENSHOT", "screenshot", now)) != null) return r;
        if ((m = SHOT2.matcher(b)).matches()
                && (r = mk(Dates.utc(n(m.group(1)), n(m.group(2)), n(m.group(3)), n(m.group(4)), n(m.group(5)), n(m.group(6)), 0), "SECOND", "SCREENSHOT", "screenshot", now)) != null) return r;
        if ((m = WA.matcher(b)).matches()) {
            String d = m.group(1);
            if ((r = mk(Dates.utc(n(d.substring(0, 4)), n(d.substring(4, 6)), n(d.substring(6, 8)), 0, 0, 0, 0), "DAY", "WA_DAY", "whatsapp", now)) != null) return r;
        }
        if ((m = WA_DESK.matcher(b)).matches() || (m = WA_DESK.matcher(name)).matches()) {
            int h = n(m.group(4));
            String ap = m.group(7);
            boolean ok = true;
            if (ap != null) { if (h < 1 || h > 12) ok = false; else if (ap.equals("PM")) h = h == 12 ? 12 : h + 12; else h = h == 12 ? 0 : h; }
            long t = ok ? Dates.utc(n(m.group(1)), n(m.group(2)), n(m.group(3)), h, n(m.group(5)), n(m.group(6)), 0) : Unknown.TIME;
            if ((r = mk(t, "SECOND", "WA_DESKTOP", "whatsapp", now)) != null) return r;
        }
        if ((m = SAMSUNG.matcher(b)).matches() && (r = mk(ymdHms(m.group(1), m.group(2), 0), "SECOND", "SAMSUNG", "camera", now)) != null) return r;
        m = GENERIC.matcher(b);
        while (m.find()) {
            if ((r = mk(Dates.utc(n(m.group(1)), n(m.group(2)), n(m.group(3)), 0, 0, 0, 0), "DAY", "GENERIC_DAY", "generic", now)) != null) return r;
        }
        return null;
    }
}
