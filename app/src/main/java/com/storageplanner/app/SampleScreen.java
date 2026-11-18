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
 * Try on my pictures (debug): a number for each place on the phone (camera, screenshots, downloads, WhatsApp, ...);
 * that many pictures are picked at random from each and run through the sorting and the rules, as in the Algorithm Lab.
 * Shows a summary per place and the pictures that would get a suggestion; every picture's full details go to the Lab log.
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
    private CheckBox sameBox;
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
        Ui.add(root, Ui.text(a, "Debug tool. Type how many pictures to try from each place. That many are picked at random from your phone, "
                + "sorted into categories and run through your rules, exactly as the Algorithm Lab does for one picture. "
                + "Nothing is changed or deleted.", 14, Ui.text2, false), 0);
        Ui.add(root, Ui.text(a, "Each picture is judged on its own: duplicates, bursts, blur compared with your other photos and sent copies need "
                + "a full Analyse, so rules that test them do not match here. Reading text takes a few seconds per picture, so 1,000 pictures "
                + "can take over half an hour. Keep this screen open; it stays awake while it runs.", 13, Ui.text2, false), 6);

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

        LinearLayout buttons = Ui.row(a);
        runBtn = Ui.button(a, "Run", true);
        runBtn.setContentDescription("Run the sorting and the rules on the pictures picked from each place");
        runBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(); } });
        stopBtn = Ui.button(a, "Stop", false);
        stopBtn.setEnabled(false);
        stopBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stop = true; status.setText("Stopping after this picture…"); } });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1); b1.rightMargin = dp(8);
        buttons.addView(runBtn, b1); buttons.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(12);
        root.addView(buttons, bl);

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

    // ------------------------------------------------------------------ running
    private void start() {
        if (busy) return;
        final Map<String, Integer> want = new LinkedHashMap<String, Integer>();
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
        if (total == 0) { a.toast("Type a number for at least one place."); return; }
        final long seed = sameBox.isChecked() ? prefs().getLong("seed", System.currentTimeMillis()) : System.currentTimeMillis();
        ed.putLong("seed", seed).apply();
        busy = true; stop = false;
        runBtn.setEnabled(false); stopBtn.setEnabled(true);
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
                        runBtn.setEnabled(true); stopBtn.setEnabled(false); sameBox.setEnabled(true);
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
