package com.storageplanner.app;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;
import com.storageplanner.core.ResultStore;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.UnitBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hosts a simple back stack of screens. The analysis runs in AnalysisService; results are saved to
 * app-private storage and reloaded after process death, and the back stack is restored from
 * onSaveInstanceState descriptors.
 */
public final class MainActivity extends Activity {
    private static final String KEY_STACK = "stack";
    private static MainActivity current;

    private final List<Screen> stack = new ArrayList<Screen>();
    private SetupScreen setup;
    private boolean night;
    /** Screen descriptors waiting for the results to finish loading from disk (null = fresh start). */
    private ArrayList<String> pendingStack;
    private boolean awaitingLoad;

    private final AnalysisState.Listener listener = new AnalysisState.Listener() {
        public void onProgress(String stage, int done, int total) { setup.progress(stage, done, total); }
        public void onFinished(String msg, boolean ok) {
            setup.finished(msg);
            if (ok && Session.hasResults()) showFreshOverview();
            else if (!stack.isEmpty()) top().refresh();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        Ui.init(this); night = Ui.night;
        Session.loadKept(this);
        styleBars();
        setup = new SetupScreen(this);
        push(setup);
        ArrayList<String> saved = b != null ? b.getStringArrayList(KEY_STACK) : null;
        if (ResultFiles.consumeInterrupted(this)) {
            AnalysisState.message = AnalysisState.INTERRUPTED;
            saved = new ArrayList<String>();             // stay on the setup screen; older results stay one tap away
        }
        if (Session.hasResults()) {
            if (!AnalysisState.running) restoreStack(saved);
        } else if (ResultFiles.loading) {                 // started by a previous instance; it calls back current
            pendingStack = saved; awaitingLoad = true;
        } else if (!AnalysisState.running && ResultFiles.exists(this)) {
            pendingStack = saved; awaitingLoad = true;
            loadSavedResults();
        }
        setup.refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        AnalysisState.listener = listener;
        if (AnalysisState.pendingOverview && !AnalysisState.running && Session.hasResults()) {
            AnalysisState.pendingOverview = false;       // finished while we were away
            setup.refresh();
            showFreshOverview();
        } else if (!stack.isEmpty()) top().refresh();
        if (!AnalysisState.running) AnalysisService.clearResultNotification(this);
    }

    @Override protected void onPause() {
        if (AnalysisState.listener == listener) AnalysisState.listener = null;
        if (!stack.isEmpty()) top().onHide();             // stop audio/video when the app goes to the background
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (awaitingLoad) {
            if (pendingStack != null) out.putStringArrayList(KEY_STACK, pendingStack);
            return;
        }
        ArrayList<String> d = new ArrayList<String>();
        for (int i = 1; i < stack.size(); i++) {
            String x = stack.get(i).descriptor();
            if (x != null) d.add(x);
        }
        out.putStringArrayList(KEY_STACK, d);
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        if (AnalysisState.listener == listener) AnalysisState.listener = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ saved results + back stack restore
    private void loadSavedResults() {
        ResultFiles.loading = true;
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                ResultStore.Saved s = ResultFiles.load(app);
                UnitBuilder ub = null;
                if (s != null) {
                    try { ub = new UnitBuilder(s.result, s.est, s.sdk); } catch (Throwable t) { ub = null; }
                }
                final ResultStore.Saved fs = s; final UnitBuilder fub = ub;
                AnalysisState.UI.post(new Runnable() {
                    public void run() {
                        ResultFiles.loading = false;
                        if (fs != null && fub != null && !Session.hasResults()) {
                            try {
                                Session.mode = fs.mode == Session.MODE_SAFE ? Session.MODE_SAFE : Session.MODE_TARGET;
                                Session.set(fs.result, fub, fs.est, fs.targetUsed, fs.appStats, fs.sdk);
                            } catch (Throwable t) { Session.result = null; Session.ub = null; Session.plan = null; }
                        }
                        MainActivity m = current;
                        if (m != null) m.onResultsLoaded();
                    }
                });
            }
        }, "load-results").start();
    }

    private void onResultsLoaded() {
        ArrayList<String> p = pendingStack;
        boolean waited = awaitingLoad;
        pendingStack = null; awaitingLoad = false;
        setup.refresh();
        if (waited && Session.hasResults() && !AnalysisState.running && stack.size() == 1) restoreStack(p);
        else if (!stack.isEmpty()) top().refresh();
    }

    /** Rebuilds the stack above the setup screen; null = fresh start (open the Overview). Stale entries are skipped. */
    private void restoreStack(List<String> descriptors) {
        if (descriptors == null) { push(new OverviewScreen(this)); return; }
        for (String d : descriptors) {
            Screen s = null;
            try { s = fromDescriptor(d); } catch (Throwable ignored) {}
            if (s != null) stack.add(s);
        }
        if (stack.size() > 1) show(top());
    }

    private Screen fromDescriptor(String d) {
        if (d == null) return null;
        if (d.equals("overview")) return new OverviewScreen(this);
        if (d.equals("list:APPS")) return ListScreen.apps(this);
        if (d.equals("list:KEPT")) return ListScreen.kept(this);
        if (d.equals("list:ACCEPTED")) return ListScreen.accepted(this);
        if (d.startsWith("viewer:")) {
            com.storageplanner.core.FileItem f = Session.fileByRel.get(d.substring(7));
            return f != null ? new ViewerScreen(this, f) : null;
        }
        if (d.equals("lab")) return new LabScreen(this);
        if (d.startsWith("pics:")) return Session.pictures.containsKey(d.substring(5)) ? PicturesScreen.category(this, d.substring(5)) : null;
        if (d.startsWith("search:")) return PicturesScreen.search(this, d.substring(7));
        if (d.startsWith("compare:")) {
            com.storageplanner.core.FileItem f = Session.fileByRel.get(d.substring(8));
            Group g = Group.of(f);
            return g != null ? new CompareScreen(this, g, g.current(), g.best, f == g.best ? null : f, null) : null;
        }
        if (d.startsWith("deck:")) {
            String t = d.substring(5);
            return new DeckScreen(this, t.equals("*") ? null : t);
        }
        if (d.startsWith("list:TECH:")) {
            String t = d.substring(10);
            return Session.candidates.containsKey(t) || t.equals(Techniques.CACHE) ? ListScreen.technique(this, t) : null;
        }
        if (d.startsWith("list:CAT:")) {
            String c = d.substring(9);
            return Session.breakdown.containsKey(c) ? ListScreen.category(this, c) : null;
        }
        if (d.startsWith("detail:")) {
            Row r = Row.fromRef(d.substring(7));
            return r != null ? new DetailScreen(this, r) : null;
        }
        return null;
    }

    private void showFreshOverview() {
        if (!stack.isEmpty()) top().onHide();
        while (stack.size() > 1) stack.remove(stack.size() - 1);
        push(new OverviewScreen(this));
    }

    private Screen top() { return stack.get(stack.size() - 1); }

    public void push(Screen s) {
        if (!stack.isEmpty()) top().onHide();
        stack.add(s);
        show(s);
    }

    private void show(Screen s) {
        View v = s.view();
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        setContentView(v);
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setTitle(s.title());
            ab.setDisplayHomeAsUpEnabled(stack.size() > 1);
            ab.setHomeActionContentDescription("Back");
        }
        setTitle(s.title());                                   // announced by TalkBack on screen change
        v.post(new Runnable() { public void run() { getWindow().getDecorView().sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); } });
    }

    @Override public void onBackPressed() {
        if (stack.size() > 1) {
            top().onHide();
            stack.remove(stack.size() - 1);
            Screen t = top();
            t.refresh();
            show(t);
        } else super.onBackPressed();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** Dark/light switch without restarting (configChanges=uiMode): rebuild every screen with the new palette. */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Ui.init(this);
        if (Ui.night != night) {
            night = Ui.night;
            styleBars();
            for (Screen s : stack) s.invalidate();
            show(top());
        }
    }

    private void styleBars() {
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.bg));
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().setNavigationBarColor(Ui.bg);
            View d = getWindow().getDecorView();
            int flags = d.getSystemUiVisibility();
            flags = Ui.night ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(flags);
        }
    }

    // ------------------------------------------------------------------ permissions
    void requestFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true);
        else requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (!stack.isEmpty()) top().refresh();
    }

    void openSettings(String action, boolean withPackage) {
        try {
            Intent i = new Intent(action);
            if (withPackage) i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            catch (Throwable t2) { toast("Open Settings and grant the permission to Storage Planner manually."); }
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ analysis (runs in AnalysisService)
    void startAnalysis(final double targetGb, final boolean measure) {
        if (!AnalysisState.begin()) return;
        // the scan runs whether or not this is granted; it only decides whether the notification shows
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        Intent i = new Intent(this, AnalysisService.class)
                .setAction(AnalysisService.ACTION_START)
                .putExtra(AnalysisService.EXTRA_TARGET_GB, targetGb)
                .putExtra(AnalysisService.EXTRA_MEASURE, measure);
        try {
            startForegroundService(i);
        } catch (Throwable t) {
            // the system refused the service: run in this process as before (stops if Android kills the app)
            final android.content.Context app = getApplicationContext();
            new Thread(new Runnable() {
                public void run() { AnalysisRunner.run(app, targetGb, measure, AnalysisRunner.progress(null)); }
            }, "analysis").start();
        }
    }

    // ------------------------------------------------------------------ Algorithm Lab: picture picker
    private static final int REQ_PICK = 4711;
    private LabScreen picking;

    void pickImages(LabScreen s) {
        picking = s;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); }
        catch (Throwable t) {
            try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PICK); }
            catch (Throwable t2) { toast("No file picker on this phone."); }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        java.util.List<android.net.Uri> uris = new ArrayList<android.net.Uri>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        LabScreen s = picking;
        if (s == null && !stack.isEmpty() && top() instanceof LabScreen) s = (LabScreen) top();   // the app was restarted meanwhile
        picking = null;
        if (s != null) s.picked(uris);
    }

    // ------------------------------------------------------------------ report
    void saveReport() {
        if (!Session.hasResults()) return;
        try {
            Report.Context c = Session.reportContext();
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "StoragePlanner");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
            write(new File(dir, "plan-" + stamp + ".txt"), Report.text(c, Session.plan, Session.safe));
            write(new File(dir, "plan-" + stamp + ".csv"), Report.csv(Session.plan));
            write(new File(dir, "plan-" + stamp + ".json"), Report.json(c, Session.plan, Session.safe));
            toast("Saved to Download/StoragePlanner/plan-" + stamp + " (.txt, .csv, .json)");
        } catch (Throwable t) {
            toast("Could not save: " + t.getMessage());
        }
    }

    private static void write(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(s.getBytes(StandardCharsets.UTF_8)); } finally { out.close(); }
    }

    void shareSummary() {
        if (!Session.hasResults()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Storage plan");
        i.putExtra(Intent.EXTRA_TEXT, Report.text(Session.reportContext(), Session.plan, Session.safe));
        try { startActivity(Intent.createChooser(i, "Share plan")); } catch (Throwable ignored) {}
    }
}
