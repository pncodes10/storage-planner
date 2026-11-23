package com.storageplanner.app;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves the files of the finalized plan to the phone's Recycle bin (Android's trash, Android 11+): the only place in
 * the app meant to ask for files to be removed. It never deletes a file itself: it hands Android a trash request and
 * Android shows its own confirmation; trashed files stay in the bin for 30 days and can be restored from there.
 *
 * NOT SWITCHED ON YET: the APK audit (tools/audit_apk.py) forbids every MediaStore trash request, by the owner's
 * standing rule that the file-deletion guardrail is not loosened. Until the owner approves changing the audit, this
 * build contains no trash request at all (the audit checks the compiled code, so even an unused call would fail it):
 * enabled() is false and the plan screen says the step is waiting. To switch it on: allow
 * MediaStore.createTrashRequest from this class only in the audit, then call it in request() (see the work log).
 */
final class TrashAction {
    private TrashAction() {}

    static final int REQ = 4720;
    /** Files per request (one system confirmation each); keeps the request well inside Android's limits. */
    static final int BATCH = 1000;

    private static final List<List<String>> queue = new ArrayList<List<String>>();     // paths per batch still to ask
    private static List<String> asking;                                                // the batch Android is asking about
    private static final List<String> moved = new ArrayList<String>();
    private static long movedBytes;
    private static int notIndexed;

    /** Whether this build can move files to the Recycle bin (see the class comment). */
    static boolean enabled() { return false; }

    static boolean supported() { return enabled() && Build.VERSION.SDK_INT >= 30; }

    /** Why the Recycle bin step is not available here, for the screens. */
    static String why() {
        if (!enabled()) return "Moving files to the Recycle bin is built but not switched on in this version: it needs a change to the app's "
                + "safety check, which is waiting for approval. Your plan is saved; until then, delete the files in your gallery or file manager.";
        return "Moving files to the Recycle bin needs Android 11 or newer. Your plan is saved; delete the files in your gallery or file manager.";
    }

    /** Looks the files up in Android's media index and asks Android to move them to the trash, a batch at a time. */
    static void start(MainActivity a, List<FileItem> files) {
        if (!supported()) { a.toast(why()); return; }
        Map<String, FileItem> byAbs = new LinkedHashMap<String, FileItem>();
        for (FileItem f : files) byAbs.put(f.abs, f);
        Map<String, Uri> uris = uris(a.getContentResolver(), byAbs.keySet());
        queue.clear(); moved.clear(); movedBytes = 0;
        notIndexed = byAbs.size() - uris.size();
        List<String> batch = new ArrayList<String>();
        for (String p : uris.keySet()) {
            batch.add(p);
            if (batch.size() == BATCH) { queue.add(batch); batch = new ArrayList<String>(); }
        }
        if (!batch.isEmpty()) queue.add(batch);
        pending = uris;
        if (queue.isEmpty()) { a.toast("None of these files can be moved to the Recycle bin from here (Android does not list them)."); return; }
        next(a);
    }

    private static Map<String, Uri> pending = new HashMap<String, Uri>();

    private static void next(MainActivity a) {
        if (queue.isEmpty()) { finish(a); return; }
        asking = queue.remove(0);
        List<Uri> l = new ArrayList<Uri>();
        for (String p : asking) l.add(pending.get(p));
        try {
            PendingIntent pi = request(a.getContentResolver(), l);
            if (pi == null) throw new IllegalStateException("the Recycle bin step is not switched on in this version");
            a.startIntentSenderForResult(pi.getIntentSender(), REQ, null, 0, 0, 0);
        } catch (Throwable t) {
            asking = null; queue.clear();
            a.toast("Android did not accept the request: " + t.getMessage());
            finish(a);
        }
    }

    /** From MainActivity.onActivityResult: Android's answer for the batch it asked about. */
    static void onResult(MainActivity a, boolean ok) {
        if (asking == null) return;
        if (ok) {
            for (String p : asking) {
                if (new File(p).exists()) continue;                 // still there: not moved
                moved.add(p);
            }
            asking = null;
            next(a);
        } else {
            asking = null; queue.clear();
            finish(a);
        }
    }

    /** Removes the moved files from the results and the plan, and says what happened. */
    private static void finish(final MainActivity a) {
        if (moved.isEmpty()) {
            a.toast("Nothing was moved to the Recycle bin.");
            return;
        }
        Set<String> gone = new HashSet<String>(moved);
        List<String> rels = new ArrayList<String>();
        if (Session.result != null) {
            for (java.util.Iterator<FileItem> it = Session.result.files.iterator(); it.hasNext(); ) {
                FileItem f = it.next();
                if (gone.contains(f.abs)) { rels.add(f.rel); movedBytes += f.size; it.remove(); }
            }
        }
        PlanStore.forget(a, rels);
        final int n = moved.size();
        final long bytes = movedBytes;
        final int skipped = notIndexed;
        moved.clear();
        Session.rebuild(a, new Runnable() {
            public void run() {
                ResultFiles.saveSessionAsync(a);
                a.toast("Moved " + n + (n == 1 ? " file" : " files") + " (" + Report.size(bytes) + ") to the Recycle bin. They stay there for 30 days."
                        + (skipped > 0 ? " " + skipped + " could not be moved (Android does not list them)." : ""));
            }
        });
    }

    /** Android's trash request for these files (shows Android's own confirmation); null while not switched on. */
    private static PendingIntent request(ContentResolver cr, List<Uri> uris) { return null; }

    /** MediaStore content URIs of the files at these paths (paths Android does not index are left out). */
    static Map<String, Uri> uris(ContentResolver cr, java.util.Collection<String> paths) {
        Map<String, Uri> out = new LinkedHashMap<String, Uri>();
        Uri files = MediaStore.Files.getContentUri("external");
        List<String> l = new ArrayList<String>(paths);
        for (int i = 0; i < l.size(); i += 300) {
            List<String> part = l.subList(i, Math.min(l.size(), i + 300));
            StringBuilder q = new StringBuilder(MediaStore.MediaColumns.DATA + " IN (");
            for (int k = 0; k < part.size(); k++) q.append(k == 0 ? "?" : ",?");
            q.append(')');
            Cursor c = null;
            try {
                c = cr.query(files, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA}, q.toString(), part.toArray(new String[0]), null);
                if (c == null) continue;
                while (c.moveToNext()) out.put(c.getString(1), ContentUris.withAppendedId(files, c.getLong(0)));
            } catch (Throwable ignored) {
            } finally { if (c != null) c.close(); }
        }
        return out;
    }
}
