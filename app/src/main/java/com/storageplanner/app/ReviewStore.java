package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Piles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which files you have reviewed, and where you were on each pile's page. A file counts as reviewed once its row has been
 * on screen on its pile's page (scrolled past) or you tapped it; only reviewed files are removed or made smaller. Marks
 * are kept by pile and path (Piles.reviewKey), so they survive a new analysis for files that stay in the same pile.
 * Kept in a file (there can be thousands); written a moment after the last change, off the main thread.
 */
final class ReviewStore {
    private ReviewStore() {}

    private static final String FILE = "reviewed.txt";
    private static Set<String> keys;
    private static boolean dirty, writing;

    /** The reviewed keys (main thread; read once, then kept). */
    static Set<String> keys(Context c) {
        if (keys == null) keys = read(new File(c.getFilesDir(), FILE));
        return keys;
    }

    static boolean reviewed(Context c, FinalPlan.Entry e) { return Piles.reviewed(e, keys(c)); }

    /** Marks these files reviewed; true when any of them was not yet. */
    static boolean mark(Context c, Collection<FinalPlan.Entry> l) {
        Set<String> k = keys(c);
        boolean added = false;
        for (FinalPlan.Entry e : l) {
            String key = Piles.reviewKey(e);
            if (key != null && k.add(key)) added = true;
        }
        if (added) scheduleSave(c);
        return added;
    }

    /** The files of the plan a clean-up acts on now: removed or made smaller, and reviewed. */
    static List<FinalPlan.Entry> ready(Context c) { return Piles.ready(PlanStore.entries(c), keys(c)); }

    // ------------------------------------------------------------------ where you were on a pile's page
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("review_pos", Context.MODE_PRIVATE); }

    /** {row, pixels from the top} of the row at the top of the pile's list when you left it; {0, 0} the first time. */
    static int[] position(Context c, String pile) {
        SharedPreferences p = prefs(c);
        return new int[]{p.getInt(pile + ".row", 0), p.getInt(pile + ".top", 0)};
    }

    static void setPosition(Context c, String pile, int row, int top) {
        prefs(c).edit().putInt(pile + ".row", row).putInt(pile + ".top", top).apply();
    }

    // ------------------------------------------------------------------ file
    private static void scheduleSave(Context c) {
        dirty = true;
        if (writing) return;
        writing = true;
        final Context app = c.getApplicationContext();
        AnalysisState.UI.postDelayed(new Runnable() {
            public void run() {
                dirty = false;
                final List<String> copy = new ArrayList<String>(keys);
                new Thread(new Runnable() {
                    public void run() {
                        write(new File(app.getFilesDir(), FILE), copy);
                        AnalysisState.UI.post(new Runnable() {
                            public void run() { writing = false; if (dirty) scheduleSave(app); }
                        });
                    }
                }, "save-reviewed").start();
            }
        }, 800);
    }

    private static Set<String> read(File f) {
        Set<String> s = new HashSet<String>();
        AtomicFile af = new AtomicFile(f);
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(af.openRead(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) if (!line.isEmpty()) s.add(line);
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        return s;
    }

    private static void write(File f, List<String> lines) {
        AtomicFile af = new AtomicFile(f);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            StringBuilder b = new StringBuilder();
            for (String l : lines) b.append(l).append('\n');
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
        } catch (Throwable t) {
            if (out != null) af.failWrite(out);
        }
    }
}
