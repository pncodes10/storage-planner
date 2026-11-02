package com.storageplanner.app;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Originals whose compressed copy is written and checked, waiting for the user to move them to the
 * trash. Remembered across restarts, so the app never loses track of what it can offer to remove.
 */
final class Originals {
    private Originals() {}

    /** Original path and the path of its compressed copy. */
    static final class Pair {
        final String original, copy;
        Pair(String original, String copy) { this.original = original; this.copy = copy; }
        long savedBytes() { return Math.max(0, new File(original).length() - new File(copy).length()); }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("compress", Context.MODE_PRIVATE); }

    static synchronized void add(Context c, String original, String copy) {
        Set<String> s = new HashSet<String>(prefs(c).getStringSet("pending", new HashSet<String>()));
        s.add(original + "\t" + copy);
        prefs(c).edit().putStringSet("pending", s).commit();      // commit: the worker may be killed right after
    }

    /**
     * Originals still waiting. Entries whose original is gone (trashed or deleted elsewhere) or whose copy
     * is missing (the user deleted it) are dropped: nothing is offered unless a good copy exists.
     */
    static synchronized List<Pair> pending(Context c) {
        Set<String> s = prefs(c).getStringSet("pending", new HashSet<String>());
        List<Pair> out = new ArrayList<Pair>();
        Set<String> keep = new HashSet<String>();
        for (String e : s) {
            int i = e.indexOf('\t');
            if (i <= 0) continue;
            Pair p = new Pair(e.substring(0, i), e.substring(i + 1));
            if (!new File(p.original).isFile() || !new File(p.copy).isFile() || new File(p.copy).length() == 0) continue;
            keep.add(e); out.add(p);
        }
        if (keep.size() != s.size()) prefs(c).edit().putStringSet("pending", keep).apply();
        java.util.Collections.sort(out, new java.util.Comparator<Pair>() {
            public int compare(Pair x, Pair y) { return x.original.compareTo(y.original); }
        });
        return out;
    }

    /** Forget these originals (moved to the trash, or the user chose to keep them). */
    static synchronized void forget(Context c, Set<String> originals) {
        Set<String> s = new HashSet<String>(prefs(c).getStringSet("pending", new HashSet<String>()));
        for (java.util.Iterator<String> it = s.iterator(); it.hasNext(); ) {
            String e = it.next();
            int i = e.indexOf('\t');
            if (i > 0 && originals.contains(e.substring(0, i))) it.remove();
        }
        prefs(c).edit().putStringSet("pending", s).apply();
    }

    static long savedBytes(List<Pair> ps) { long n = 0; for (Pair p : ps) n += p.savedBytes(); return n; }

    /** MediaStore item URIs for these files (Android 11+ trash requests need them). Files Android has not indexed are left out. */
    static Map<String, Uri> mediaUris(Context c, List<String> paths) {
        Map<String, Uri> out = new HashMap<String, Uri>();
        Uri files = MediaStore.Files.getContentUri("external");
        String[] proj = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE};
        for (int from = 0; from < paths.size(); from += 200) {
            List<String> chunk = paths.subList(from, Math.min(paths.size(), from + 200));
            StringBuilder sel = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
            for (int i = 0; i < chunk.size(); i++) sel.append(i == 0 ? "?" : ",?");
            sel.append(")");
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(files, proj, sel.toString(), chunk.toArray(new String[0]), null);
                if (cur == null) continue;
                while (cur.moveToNext()) {
                    long id = cur.getLong(0);
                    String path = cur.getString(1);
                    boolean image = cur.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
                    out.put(path, ContentUris.withAppendedId(image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : files, id));
                }
            } catch (Throwable ignored) {
            } finally {
                if (cur != null) cur.close();
            }
        }
        return out;
    }
}
