package com.storageplanner.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.Report;

import java.util.List;
import java.util.Locale;

/**
 * While the analysis runs: what can be freed so far (it grows as files are done), how much is looked at, the file being
 * looked at, and the piles ready so far. Files are looked at one batch at a time, the biggest first. Pause stops after
 * the files being looked at and opens the plan made from the files done; Play (on the Clean up screen) carries on from
 * there. The ✕ asks whether to keep what is done or throw this run away.
 */
public final class AnalysisScreen extends Screen {
    private TextView sub, freed, lookedAt, filesLine, looking, stage;
    private ProgressBar bar;
    private LinearLayout ready;
    private Button pause, stop;

    public AnalysisScreen(MainActivity a) { super(a); }

    public String title() { return "Analysing your storage"; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        LinearLayout L = new LinearLayout(a);
        L.setOrientation(LinearLayout.VERTICAL);
        L.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(L);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        sub = Ui.add(L, Ui.text(a, "", 14, Ui.text2, false), 0);

        LinearLayout hero = Ui.card(a);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.addView(Ui.text(a, "Can be freed so far", 15, Ui.text2, false));
        freed = Ui.add(hero, Ui.text(a, "", 40, Ui.text, true), 4);
        freed.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(12);
        hero.addView(bar, bl);
        LinearLayout nums = Ui.row(a);
        lookedAt = Ui.text(a, "", 13, Ui.text2, false);
        filesLine = Ui.text(a, "", 13, Ui.text2, false);
        nums.addView(lookedAt, new LinearLayout.LayoutParams(0, -2, 1));
        nums.addView(filesLine);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2); nl.topMargin = dp(6);
        hero.addView(nums, nl);
        card(L, hero, 12);

        LinearLayout now = Ui.card(a);
        now.addView(Ui.text(a, "Now looking at", 13, Ui.text2, false));
        looking = Ui.add(now, Ui.text(a, "", 16, Ui.text, true), 4);
        stage = Ui.add(now, Ui.text(a, "", 13, Ui.text2, false), 4);
        stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card(L, now, 12);

        TextView head = Ui.text(a, "READY SO FAR", 12, Ui.text2, true);
        head.setLetterSpacing(0.08f);
        Ui.add(L, head, 20);
        ready = new LinearLayout(a);
        ready.setOrientation(LinearLayout.VERTICAL);
        card(L, ready, 8);
        Ui.add(L, Ui.text(a, "Biggest files first, every check on each file before the next. Pause any time: the plan is made from "
                + "the files done, and the rest are left alone until you carry on. It runs in the background too.", 13, Ui.text2, false), 14);

        LinearLayout bottom = Ui.row(a);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(14));
        pause = Ui.button(a, "Pause and see plan", true);
        pause.setTextSize(16);
        pause.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pause(); } });
        stop = Ui.button(a, "✕", false);
        stop.setTextSize(18);
        stop.setContentDescription("Stop the analysis");
        stop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { askStop(); } });
        bottom.addView(pause, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(54), dp(54)); sl.leftMargin = dp(10);
        bottom.addView(stop, sl);
        root.addView(bottom);
        refresh();
        return root;
    }

    private void card(LinearLayout L, View c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top);
        L.addView(c, lp);
    }

    private void pause() {
        if (!AnalysisState.running) return;
        AnalysisState.cancelled = true;
        refresh();
        pause.announceForAccessibility("Pausing");
    }

    private void askStop() {
        if (!AnalysisState.running) return;
        String done = AnalysisState.filesTotal > 0 ? String.format(Locale.US, "%,d of %,d files are done", AnalysisState.filesDone, AnalysisState.filesTotal)
                + (AnalysisState.freed > 0 ? ", with " + Report.size(AnalysisState.freed) + " that can be freed." : ".") : "Nothing is analysed yet.";
        new AlertDialog.Builder(a).setTitle("Stop the analysis?").setMessage(done
                        + "\n\nKeep the plan so far: you can carry on from here later.\nThrow it away: the next analysis starts from the beginning.")
                .setPositiveButton("Stop and keep the plan", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { pause(); }
                })
                .setNeutralButton("Throw it away", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { AnalysisState.discard = true; pause(); }
                })
                .setNegativeButton("Keep analysing", null).show();
    }

    @Override public void refresh() {
        if (freed == null) return;
        boolean stopping = AnalysisState.cancelled;
        String note = AnalysisState.resumeNote;
        sub.setText(note != null ? note : "Biggest files first. Pause any time to use what is ready.");
        long f = AnalysisState.freed;
        freed.setText(f >= 0 ? Report.size(f) : "counting…");
        long bd = AnalysisState.bytesDone, bt = AnalysisState.bytesTotal;
        int fd = AnalysisState.filesDone, ft = AnalysisState.filesTotal;
        if (ft > 0) {
            bar.setIndeterminate(false);
            bar.setProgress((int) (1000 * Math.min(1.0, bt > 0 ? bd / (double) bt : fd / (double) ft)));
            lookedAt.setText(Report.gb(bd) + " of " + Report.gb(bt) + " looked at");
            filesLine.setText(String.format(Locale.US, "%,d of %,d files", fd, ft));
        } else {
            bar.setIndeterminate(true);
            lookedAt.setText("Listing your files");
            filesLine.setText("");
        }
        String l = AnalysisState.looking;
        looking.setText(stopping ? (AnalysisState.discard ? "Stopping…" : "Pausing after this file…") : l != null ? l : "Your file list");
        int d = AnalysisState.done, t = AnalysisState.total;
        String s = AnalysisState.stage;
        stage.setText(ft > 0 || t <= 0 ? (s == null ? "" : s) : s + "  " + d + " of " + t);
        ready.removeAllViews();
        List<Object[]> piles = AnalysisState.ready;
        if (piles.isEmpty()) {
            ready.addView(Ui.text(a, f >= 0 ? "Nothing to free in the files done so far." : "The first files are being looked at.", 14, Ui.text2, false));
        } else {
            for (int i = 0; i < piles.size(); i++) {
                Object[] p = piles.get(i);
                LinearLayout row = Ui.row(a);
                row.setMinimumHeight(dp(40));
                row.addView(Ui.text(a, (String) p[0], 15, Ui.text, false), new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(Ui.text(a, Report.size((Long) p[1]), 15, Ui.text, true));
                ready.addView(row);
                if (i < piles.size() - 1) ready.addView(Ui.divider(a));
            }
        }
        pause.setEnabled(AnalysisState.running && !stopping);
        stop.setEnabled(AnalysisState.running && !stopping);
        pause.setText(stopping ? (AnalysisState.discard ? "Stopping…" : "Pausing…") : "Pause and see plan");
    }

    /** "Paused: 1,240 of 8,912 files done" for the plan screens; null after a finished analysis. */
    static String pausedLine(MainActivity a) {
        int[] p = AnalysisRunner.paused(a);
        if (p == null) return null;
        return String.format(Locale.US, "Paused · %,d of %,d files done", p[1] - p[0], p[1]);
    }
}
