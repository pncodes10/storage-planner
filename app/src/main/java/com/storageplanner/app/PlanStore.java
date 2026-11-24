package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.FileItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Your final plan on this phone: the action you chose for each file (files/finalplan.tsv, one "action<TAB>path" a line)
 * and whether the plan is finalized (preferences). The entries are built from the last analysis in Session and rebuilt
 * when it changes. Main thread only; files are written on a background thread.
 */
final class PlanStore {
    private PlanStore() {}

    private static final String FILE = "finalplan.tsv", PREFS = "finalplan";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static Map<String, String> choices;
    private static List<FinalPlan.Entry> entries;
    private static Object builtFor;                       // the UnitBuilder the entries were built from

    static Map<String, String> choices(Context c) {
        if (choices == null) choices = FinalPlan.fromText(read(new File(c.getFilesDir(), FILE)));
        return choices;
    }

    private static Map<String, FinalPlan.Entry> byRel;

    /** The plan of the last analysis (empty without one). */
    static List<FinalPlan.Entry> entries(Context c) {
        if (!Session.hasResults()) return new ArrayList<FinalPlan.Entry>();
        if (entries == null || builtFor != Session.ub) {
            entries = FinalPlan.build(Session.result.files, Session.unitById, choices(c), Session.kept, Session.accepted);
            builtFor = Session.ub;
            byRel = null;
        }
        return entries;
    }

    /** The plan entry of a file (null if it has none). */
    static FinalPlan.Entry entry(Context c, String rel) {
        List<FinalPlan.Entry> l = entries(c);
        if (byRel == null) { byRel = new java.util.HashMap<String, FinalPlan.Entry>(); for (FinalPlan.Entry e : l) byRel.put(e.file.rel, e); }
        return byRel.get(rel);
    }

    /**
     * The key of the copies a file belongs to (exact duplicates, shots of the same moment, WhatsApp copies): the path of
     * the copy the suggestion keeps; null when the file has no copies.
     */
    static String groupKey(FileItem f) {
        if (Session.ub == null) return null;
        String k = Session.ub.det.duplicateOf.get(f.rel);
        if (k == null) k = Session.ub.det.burstExtraOf.get(f.rel);
        if (k == null) k = Session.ub.det.sentCopyOf.get(f.rel);
        if (k == null && Session.copiesOf.containsKey(f.rel)) k = f.rel;
        return k;
    }

    /** Sets the final action of these entries (your choice) and saves. */
    static void set(Context c, Collection<FinalPlan.Entry> l, String action) {
        Map<String, String> m = choices(c);
        for (FinalPlan.Entry e : l) {
            if (!FinalPlan.possible(e, action)) continue;
            FinalPlan.set(e, action);
            e.chosen = true;
            m.put(e.file.rel, action);
        }
        save(c);
    }

    /** Forgets files that are gone (moved to the Recycle bin). */
    static void forget(Context c, Collection<String> rels) {
        Map<String, String> m = choices(c);
        for (String r : rels) m.remove(r);
        entries = null; byRel = null;
        save(c);
    }

    /** Starts over: every file back to what the rules suggest. */
    static void reset(Context c) {
        choices(c).clear();
        entries = null;
        prefs(c).edit().remove("finalized").apply();
        save(c);
    }

    static void invalidate() { entries = null; byRel = null; }

    // ---- finalized
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }

    /** When the plan was finalized (ms), or 0 while it is a draft. */
    static long finalized(Context c) { return prefs(c).getLong("finalized", 0); }

    static void setFinalized(Context c, boolean on) {
        if (on) prefs(c).edit().putLong("finalized", System.currentTimeMillis()).apply();
        else prefs(c).edit().remove("finalized").apply();
    }

    // ---- developer mode and analysis settings (shown on the Settings page)
    static boolean developer(Context c) { return prefs(c).getBoolean("developer", false); }

    static void setDeveloper(Context c, boolean on) { prefs(c).edit().putBoolean("developer", on).apply(); }

    /** Used space to reach, in GB; default the 70-of-128 GB ratio of the phone. */
    static double targetGb(Context c) {
        float v = prefs(c).getFloat("target_gb", -1);
        if (v > 0) return v;
        long total = new AndroidPlatform(c).totalBytes();
        return Math.max(1, Math.round(total / 1e9 * 70.0 / 128.0));
    }

    static void setTargetGb(Context c, double gb) { prefs(c).edit().putFloat("target_gb", (float) gb).apply(); }

    static boolean measure(Context c) { return prefs(c).getBoolean("measure", true); }

    static void setMeasure(Context c, boolean on) { prefs(c).edit().putBoolean("measure", on).apply(); }

    // ---- files
    private static void save(Context c) {
        final File f = new File(c.getFilesDir(), FILE);
        final String text = FinalPlan.toText(new LinkedHashMap<String, String>(choices(c)));
        new Thread(new Runnable() {
            public void run() {
                synchronized (PlanStore.class) {
                    try {
                        FileOutputStream o = new FileOutputStream(f, false);
                        try { o.write(text.getBytes(UTF8)); } finally { o.close(); }
                    } catch (Throwable ignored) {}
                }
            }
        }, "save-plan").start();
    }

    private static String read(File f) {
        if (!f.exists()) return "";
        try {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[1 << 16]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return new String(bo.toByteArray(), UTF8);
            } finally { in.close(); }
        } catch (Throwable t) { return ""; }
    }

    /** Files with this final action, for the Recycle bin. */
    static List<FileItem> files(Context c, String action) {
        List<FileItem> l = new ArrayList<FileItem>();
        for (FinalPlan.Entry e : entries(c)) if (action.equals(e.action)) l.add(e.file);
        return l;
    }
}
