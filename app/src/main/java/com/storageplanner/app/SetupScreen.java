package com.storageplanner.app;

import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Report;

/**
 * Home: your storage at a glance, your plan (once there is one), and one button to analyse. Permissions show only
 * while missing. Settings hold the target and the other options; developer mode adds the Algorithm Lab here.
 */
public final class SetupScreen extends Screen {
    private LinearLayout L, permCard, planCard, devBox;
    private TextView usedLine, freeLine, filesStatus, usageStatus, progressText;
    private Button filesBtn, usageBtn, analyzeBtn, cancelBtn;
    private MeterView meter;
    private ProgressBar progressBar;

    public SetupScreen(MainActivity a) { super(a); }

    public String title() { return "Storage Planner"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        L = new LinearLayout(a);
        L.setOrientation(LinearLayout.VERTICAL);
        L.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(L);

        Ui.add(L, Ui.text(a, "Free up space safely. Everything stays on your phone, and nothing is deleted unless you say so.", 15, Ui.text2, false), 0);

        // storage
        LinearLayout st = Ui.card(a);
        st.setPadding(dp(20), dp(18), dp(20), dp(18));
        TextView lab = Ui.text(a, "STORAGE", 12, Ui.text2, true);
        lab.setLetterSpacing(0.08f);
        st.addView(lab);
        usedLine = Ui.add(st, Ui.text(a, "", 30, Ui.text, true), 6);
        freeLine = Ui.add(st, Ui.text(a, "", 14, Ui.text2, false), 2);
        meter = new MeterView(a);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = dp(14);
        st.addView(meter, ml);
        card(st, 16);

        // permissions (only while missing)
        permCard = Ui.card(a);
        Ui.add(permCard, Ui.heading(a, "Before you start", 17), 0);
        filesStatus = Ui.add(permCard, Ui.text(a, "", 14, Ui.text, false), 6);
        filesBtn = Ui.button(a, "Allow access to files", true);
        filesBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.requestFilesAccess(); } });
        add(permCard, filesBtn, 8);
        usageStatus = Ui.add(permCard, Ui.text(a, "", 14, Ui.text, false), 14);
        usageBtn = Ui.button(a, "Allow usage access", false);
        usageBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { a.openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, false); }
        });
        add(permCard, usageBtn, 8);
        card(permCard, 14);

        // your plan
        planCard = Ui.card(a);
        planCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        card(planCard, 14);

        // analyse
        LinearLayout row = Ui.row(a);
        analyzeBtn = Ui.button(a, "Analyse my storage", true);
        analyzeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startAnalysis(); } });
        cancelBtn = Ui.button(a, "Cancel", false);
        cancelBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            AnalysisState.cancelled = true;
            refresh();
            v.announceForAccessibility("Cancelling");
        } });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, dp(54), 1);
        row.addView(analyzeBtn, al);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, dp(54)); cl.leftMargin = dp(10);
        row.addView(cancelBtn, cl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(22);
        L.addView(row, rl);
        progressBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(12);
        L.addView(progressBar, pl);
        progressText = Ui.add(L, Ui.text(a, "", 14, Ui.text2, false), 6);
        progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(L, Ui.text(a, "Runs in the background: you can use other apps and you'll get a notification when your plan is ready.", 13, Ui.text2, false), 4);

        // settings and, in developer mode, the Algorithm Lab
        devBox = new LinearLayout(a);
        devBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2); dl.topMargin = dp(18);
        L.addView(devBox, dl);
        refresh();
        return sv;
    }

    private void add(LinearLayout p, Button b, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(top);
        p.addView(b, lp);
    }

    private void card(View c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top);
        L.addView(c, lp);
    }

    @Override public void refresh() {
        if (L == null) return;
        AndroidPlatform pf = new AndroidPlatform(a);
        long total = pf.totalBytes(), free = pf.freeBytes(), used = total - free;
        usedLine.setText(Report.gb(used) + " used");
        freeLine.setText("of " + Report.gb(total) + " · " + Report.gb(free) + " free");
        boolean running = AnalysisState.running, loading = ResultFiles.loading;
        boolean results = Session.hasResults() && !running && !loading;
        FinalPlan.Totals t = results ? FinalPlan.totals(PlanStore.entries(a), null) : null;
        meter.set(total, used, t == null ? 0 : t.freed(), (long) (PlanStore.targetGb(a) * 1e9));
        meter.setContentDescription(Report.gb(used) + " used of " + Report.gb(total) + (t == null ? "" : ", your plan frees " + Report.size(t.freed())));

        boolean files = AndroidPlatform.hasFilesAccess(a), usage = AndroidPlatform.hasUsageAccess(a);
        permCard.setVisibility(files && usage ? View.GONE : View.VISIBLE);
        filesStatus.setText(files ? "✓ Access to files" : "Storage Planner needs access to your files to find photos, videos, downloads and WhatsApp media. It only reads them.");
        filesBtn.setVisibility(files ? View.GONE : View.VISIBLE);
        usageStatus.setText(usage ? "✓ Usage access" : "Optional: usage access shows app sizes, caches and apps you haven't opened in months.");
        usageBtn.setVisibility(usage ? View.GONE : View.VISIBLE);

        planCard.removeAllViews();
        planCard.setVisibility(results ? View.VISIBLE : View.GONE);
        if (results) {
            long fin = PlanStore.finalized(a);
            TextView head = Ui.text(a, fin == 0 ? "YOUR PLAN · DRAFT" : "YOUR PLAN · ✓ FINALIZED", 12, fin == 0 ? Ui.text2 : PlanScreen.KEEP_COLOR, true);
            head.setLetterSpacing(0.08f);
            planCard.addView(head);
            Ui.add(planCard, Ui.text(a, "Free up " + Report.size(t.freed()), 26, Ui.accent, true), 6);
            Ui.add(planCard, Ui.text(a, PlanScreen.count(t.count(FinalPlan.DELETE), "file") + " to delete · " + PlanScreen.count(t.count(FinalPlan.COMPRESS), "file")
                    + " to compress", 14, Ui.text2, false), 2);
            Button open = Ui.button(a, fin == 0 ? "Review your plan" : "Open your plan", true);
            open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new PlanScreen(a)); } });
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, dp(50)); ol.topMargin = dp(14);
            planCard.addView(open, ol);
            planCard.setContentDescription("Your plan: free up " + Report.size(t.freed()));
        }

        analyzeBtn.setText(Session.hasResults() ? "Analyse again" : "Analyse my storage");
        analyzeBtn.setEnabled(!running && !loading);
        cancelBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        cancelBtn.setEnabled(running && !AnalysisState.cancelled);
        if (running) {
            if (AnalysisState.cancelled) { progressBar.setVisibility(View.VISIBLE); progressBar.setIndeterminate(true); progressText.setText("Cancelling…"); }
            else progress(AnalysisState.stage, AnalysisState.done, AnalysisState.total);
        } else if (loading) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            progressText.setText("Loading your last plan…");
        } else {
            progressBar.setVisibility(View.GONE);
            progressText.setText(AnalysisState.message != null ? AnalysisState.message : "");
        }

        devBox.removeAllViews();
        TextView settings = Ui.text(a, "⚙  Settings", 15, Ui.accent, true);
        settings.setMinHeight(dp(48));
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new SettingsScreen(a)); } });
        devBox.addView(settings);
        if (PlanStore.developer(a)) {
            Button lab = Ui.button(a, "Algorithm Lab (developer)", false);
            lab.setContentDescription("Algorithm Lab: debug tools for developers");
            lab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new LabScreen(a)); } });
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2); ll.topMargin = dp(6);
            devBox.addView(lab, ll);
        }
    }

    private void startAnalysis() {
        if (!AndroidPlatform.hasFilesAccess(a)) { a.toast("Allow access to files first."); return; }
        a.startAnalysis(PlanStore.targetGb(a), PlanStore.measure(a));
        refresh();
    }

    public void progress(String stage, int done, int total) {
        if (progressText == null || AnalysisState.cancelled) return;
        progressBar.setVisibility(View.VISIBLE);
        progressText.setText(total > 0 ? stage + "  " + done + " of " + total : (done > 0 ? stage + "  " + done : stage + "…"));
        if (total > 0) { progressBar.setIndeterminate(false); progressBar.setMax(total); progressBar.setProgress(done); }
        else progressBar.setIndeterminate(true);
    }

    public void finished(String msg) {
        if (progressText == null) return;
        progressText.setText(msg);
        refresh();
    }
}
