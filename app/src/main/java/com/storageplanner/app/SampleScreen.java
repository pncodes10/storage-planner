package com.storageplanner.app;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Lab;
import com.storageplanner.core.Option;
import com.storageplanner.core.PhotoCategory;
import com.storageplanner.core.Sample;
import com.storageplanner.core.Scanner;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Try on my pictures (debug): a number for each place on the phone (camera, screenshots, downloads, WhatsApp, ...).
 * Run starts the real Analyse limited to that many pictures from each place (at random or the newest): the same
 * pipeline, plan and screens as a full analysis, so every picture gets the options it would get there. "Lab check
 * only" runs each picked picture through the sorting and the rules one by one, as in the Algorithm Lab, with no plan.
 * Read-only: nothing on the phone is changed.
 */
public final class SampleScreen extends Screen {
    private static final String PREFS = "sample";
    private static final int SHOW_MAX = 400;           // pictures listed on screen; the log has all of them

    private final EditText[] boxes = new EditText[Sample.SOURCES.length];
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy, stop;
    private Button runBtn, stopBtn;
    private CheckBox sameBox, newestBox;
    private Button labBtn;
    private ProgressBar bar;
    private TextView status, summary, list;

    public SampleScreen(MainActivity a) { super(a); }

    public String title() { return "Try on my pictures"; }

    @Override public String descriptor() { return "sample"; }

    private SharedPreferences prefs() { return a.getSharedPreferences(PREFS, 0); }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(root);
        Ui.add(root, Ui.text(a, "Type how many pictures to analyse from each place, then tap Analyse these pictures. It runs the same "
                + "analysis as Analyse my storage, on those pictures only, and opens the plan: every picture gets the options it would get "
                + "in a full analysis. Nothing is changed or deleted.", 14, Ui.text2, false), 0);
        Ui.add(root, Ui.text(a, "The plan then covers only these pictures (no other files, no apps) and replaces the last results until you "
                + "analyse your whole storage again. Duplicates, bursts and blur are found among the picked pictures only: pick the newest "
                + "to keep bursts together. Reading text takes a few seconds per picture, so 1,000 pictures can take over half an hour.", 13, Ui.text2, false), 6);

        SharedPreferences p = prefs();
        for (int i = 0; i < Sample.SOURCES.length; i++) {
            String area = Sample.SOURCES[i];
            LinearLayout row = Ui.row(a);
            TextView l = Ui.text(a, Sample.label(area), 15, Ui.text, false);
            row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            EditText e = new EditText(a);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setText(String.valueOf(p.getInt(area, 0)));
            e.setSelectAllOnFocus(true);
            e.setTextColor(Ui.text);
            e.setMinWidth(dp(96));
            e.setGravity(android.view.Gravity.END);
            e.setContentDescription("Number of " + Sample.label(area) + " to try");
            row.addView(e, new LinearLayout.LayoutParams(dp(110), -2));
            boxes[i] = e;
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(4);
            root.addView(row, rl);
        }
        sameBox = new CheckBox(a);
        sameBox.setText("Pick the same pictures as last time (to compare after changing a rule)");
        sameBox.setTextColor(Ui.text);
        sameBox.setEnabled(p.contains("seed"));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(8);
        root.addView(sameBox, cl);
        newestBox = new CheckBox(a);
        newestBox.setText("Take the newest pictures instead of random ones (keeps bursts and near-identical shots together)");
        newestBox.setTextColor(Ui.text);
        newestBox.setChecked(p.getBoolean("newest", false));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2); nl.topMargin = dp(4);
        root.addView(newestBox, nl);

        LinearLayout buttons = Ui.row(a);
        runBtn = Ui.button(a, "Analyse these pictures", true);
        runBtn.setContentDescription("Run the full analysis on the pictures picked from each place and open the plan");
        runBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { analyse(); } });
        stopBtn = Ui.button(a, "Stop", false);
        stopBtn.setEnabled(false);
        stopBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stop = true; status.setText("Stopping after this picture…"); } });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1); b1.rightMargin = dp(8);
        buttons.addView(runBtn, b1); buttons.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(12);
        root.addView(buttons, bl);

        Ui.add(root, Ui.text(a, "Lab check only: runs each picked picture through the sorting and your rules one by one, as the Algorithm Lab does, "
                + "and lists the results here, with every detail in the Lab log. No plan is made and your last results stay. Each picture is "
                + "judged on its own, so duplicates, bursts, blur and sent copies are not checked. Stop ends it after the current picture.", 13, Ui.text2, false), 16);
        labBtn = Ui.button(a, "Lab check only (no plan)", false);
        labBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(); } });
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, -2); lb.topMargin = dp(6);
        root.addView(labBtn, lb);

        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(10);
        root.addView(bar, pl);
        status = Ui.text(a, "", 14, Ui.text, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(root, status, 4);

        Ui.add(root, Ui.heading(a, "Summary", 17), 16);
        summary = mono("Type the numbers and tap Run.");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(6);
        root.addView(summary, sl);
        Ui.add(root, Ui.heading(a, "Pictures", 17), 16);
        list = mono("");
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = dp(6);
        root.addView(list, ll);

        Ui.add(root, Ui.text(a, "Every picture's measurements, the text read and each rule's verdict are in the Algorithm Lab log.", 13, Ui.text2, false), 12);
        LinearLayout logRow = Ui.row(a);
        Button save = Ui.button(a, "Save log", false);
        save.setContentDescription("Save the log to Download/StoragePlanner");
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveLog(false); } });
        Button share = Ui.button(a, "Share log", false);
        share.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveLog(true); } });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1); l1.rightMargin = dp(8);
        logRow.addView(save, l1); logRow.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lrl = new LinearLayout.LayoutParams(-1, -2); lrl.topMargin = dp(6);
        root.addView(logRow, lrl);
        return sv;
    }

    private TextView mono(String s) {
        TextView t = Ui.text(a, s, 12, Ui.text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setBackground(Ui.round(Ui.surface, dp(8)));
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        return t;
    }

    // ------------------------------------------------------------------ the limit
    /** Reads the fields, saves them (numbers, seed, newest); null and a message when no place has a number. */
    private Map<String, Integer> readCounts() {
        Map<String, Integer> want = new LinkedHashMap<String, Integer>();
        int total = 0;
        SharedPreferences.Editor ed = prefs().edit();
        for (int i = 0; i < boxes.length; i++) {
            int n;
            try { n = Integer.parseInt(boxes[i].getText().toString().replace(",", "").trim()); } catch (NumberFormatException e) { n = 0; }
            n = Math.max(0, Math.min(Sample.MAX_EACH, n));
            boxes[i].setText(String.valueOf(n));
            want.put(Sample.SOURCES[i], n);
            ed.putInt(Sample.SOURCES[i], n);
            total += n;
        }
        if (total == 0) { a.toast("Type a number for at least one place."); return null; }
        long seed = sameBox.isChecked() ? prefs().getLong("seed", System.currentTimeMillis()) : System.currentTimeMillis();
        ed.putLong("seed", seed).putBoolean("newest", newestBox.isChecked()).commit();       // the analysis reads them right away
        sameBox.setEnabled(true);
        return want;
    }

    /** The limit as saved on this screen, for the analysis (AnalysisRunner). */
    static Sample.Limit limit(android.content.Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        Sample.Limit l = new Sample.Limit();
        for (String area : Sample.SOURCES) l.counts.put(area, p.getInt(area, 0));
        l.seed = p.getLong("seed", 1);
        l.newest = p.getBoolean("newest", false);
        return l;
    }

    /** Starts the real analysis on the picked pictures; the start screen shows the progress and the plan opens when it is done. */
    private void analyse() {
        if (busy) { a.toast("Wait for the Lab check to finish, or stop it."); return; }
        if (AnalysisState.running) { a.toast("An analysis is already running."); return; }
        if (!AndroidPlatform.hasFilesAccess(a)) { a.toast("Grant all files access first (on the start screen)."); return; }
        if (readCounts() == null) return;
        long total = new AndroidPlatform(a).totalBytes();
        double gb = Math.max(1, total / 1e9 * 70.0 / 128.0);          // the start screen's default target
        a.startAnalysis(gb, false, true);
        a.showStart();
    }

    // ------------------------------------------------------------------ Lab check only
    private void start() {
        if (busy) return;
        final Map<String, Integer> want = readCounts();
        if (want == null) return;
        final long seed = prefs().getLong("seed", System.currentTimeMillis());
        busy = true; stop = false;
        runBtn.setEnabled(false); labBtn.setEnabled(false); stopBtn.setEnabled(true);
        bar.setProgress(0); bar.setVisibility(View.VISIBLE);
        status.setText("Listing pictures…");
        summary.setText(""); list.setText("");
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final AndroidPlatform pf = new AndroidPlatform(a);
        final StringBuilder shown = new StringBuilder();
        final int[] listed = {0};
        worker.execute(new Runnable() {
            public void run() {
                LabLog.header(a);
                LabLog.line(a, "\n--- " + LabLog.now() + "  Try on my pictures");
                final long t0 = System.currentTimeMillis();
                Scanner.Progress pr = new Scanner.Progress() {
                    public void update(final String stage, final int done, final int total) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (total > 0) {
                                    bar.setProgress((int) (1000L * done / total));
                                    long el = System.currentTimeMillis() - t0;
                                    String left = done > 1 ? String.format(Locale.US, ", about %d min left", Math.round(el / (double) done * (total - done) / 60000.0)) : "";
                                    status.setText(stop ? "Stopping after this picture…" : "Picture " + done + " of " + total + left);
                                } else status.setText(stage + (done > 0 ? " (" + done + " found)" : "") + "…");
                            }
                        });
                    }
                    public boolean cancelled() { return stop; }
                };
                Lab.Out log = new Lab.Out() { public void line(String s) { LabLog.line(a, s); } };
                Sample.Listener lst = new Sample.Listener() {
                    public void picture(Sample.Item it, int done, int total) {
                        boolean show = it.problem != null || !it.options.isEmpty();
                        if (!show || listed[0] >= SHOW_MAX) return;
                        listed[0]++;
                        final String line = line(it);
                        ui.post(new Runnable() { public void run() { shown.append(line).append('\n'); list.setText(shown); } });
                    }
                };
                Sample.Result res = null;
                Throwable err = null;
                try { res = Sample.run(pf, want, RulesStore.get(a).copy(), System.currentTimeMillis(), seed, pr, log, lst); }
                catch (Throwable t) { err = t; LabLog.error(a, "Try on my pictures", t); }
                final Sample.Result r = res;
                final Throwable e = err;
                if (r != null) { LabLog.line(a, ""); for (String s : Sample.summary(r)) LabLog.line(a, s); }
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        runBtn.setEnabled(true); labBtn.setEnabled(true); stopBtn.setEnabled(false); sameBox.setEnabled(true);
                        bar.setVisibility(View.GONE);
                        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (r == null) { status.setText("Failed: " + e); return; }
                        StringBuilder b = new StringBuilder();
                        for (String s : Sample.summary(r)) b.append(s).append('\n');
                        summary.setText(b);
                        int kept = r.items.size() - listed[0];
                        if (shown.length() == 0) list.setText("No picture would get a suggestion.");
                        else if (listed[0] >= SHOW_MAX) list.append("… the first " + SHOW_MAX + " are shown; the log has every picture.\n");
                        status.setText((r.cancelled ? "Stopped. " : "Done. ") + r.done() + " pictures sorted. Pictures listed below get a suggestion"
                                + (kept > 0 ? "; " + kept + " others are kept." : "."));
                        status.announceForAccessibility(status.getText());
                    }
                });
            }
        });
    }

    /** "Camera  IMG_1.jpg (2.1 MB): People → Delete by My rule: Old (2.1 MB)". */
    static String line(Sample.Item it) {
        FileItem f = it.file;
        StringBuilder b = new StringBuilder();
        b.append(shortLabel(it.source)).append("  ").append(f.name()).append(String.format(Locale.US, " (%.1f MB)", f.size / 1e6));
        if (it.problem != null) return b.append(": skipped, ").append(it.problem).toString();
        b.append(": ").append(PhotoCategory.label(it.category));
        for (Option o : it.options)
            b.append(String.format(Locale.US, "\n    → %s by %s, frees %.1f MB", Sample.actionWord(o.action), o.technique, o.saved / 1e6));
        return b.toString();
    }

    private static String shortLabel(String area) {
        switch (area) {
            case "camera": return "Camera";
            case "screenshot": return "Screenshot";
            case "download": return "Download";
            case "wa_image": return "WhatsApp";
            case "wa_sent": return "WA sent";
            default: return "Pictures";
        }
    }

    private void saveLog(boolean share) {
        try {
            File f = LabLog.export(a);
            if (!share) { a.toast("Saved to Download/StoragePlanner/" + f.getName()); return; }
            FileItem item = new FileItem("Download/StoragePlanner/" + f.getName(), f.getAbsolutePath(), f.length(), f.lastModified());
            FileActions.share(a, item);
        } catch (Throwable t) {
            a.toast("Could not save the log: " + t.getMessage());
        }
    }
}
