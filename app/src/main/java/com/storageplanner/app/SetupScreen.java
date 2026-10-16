package com.storageplanner.app;

import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.Report;

import java.util.Locale;

/** Start page: permissions, target, analyse, progress. */
public final class SetupScreen extends Screen {
    private TextView storageLine, filesStatus, usageStatus, progressText;
    private Button filesBtn, usageBtn, analyzeBtn, cancelBtn, resultsBtn;
    private EditText targetInput;
    private CheckBox measureBox;
    private ProgressBar progressBar;

    public SetupScreen(MainActivity a) { super(a); }

    public String title() { return "Storage Planner"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout L = new LinearLayout(a);
        L.setOrientation(LinearLayout.VERTICAL);
        L.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(L);

        Ui.add(L, Ui.text(a, "Finds what is using your storage and builds a plan to reach a target, lowest-risk steps first. "
                + "It only reads: nothing is deleted or changed, and it has no internet access.", 15, Ui.text2, false), 0);
        storageLine = Ui.add(L, Ui.text(a, "", 18, Ui.text, true), 16);

        LinearLayout perm = Ui.card(a);
        Ui.add(perm, Ui.heading(a, "Permissions", 17), 0);
        filesStatus = Ui.add(perm, Ui.text(a, "", 15, Ui.text, false), 8);
        filesBtn = Ui.button(a, "Grant all files access", true);
        filesBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.requestFilesAccess(); } });
        addButton(perm, filesBtn, 8);
        usageStatus = Ui.add(perm, Ui.text(a, "", 15, Ui.text, false), 14);
        usageBtn = Ui.button(a, "Grant usage access", false);
        usageBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { a.openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, false); }
        });
        addButton(perm, usageBtn, 8);
        addCard(L, perm, 16);

        LinearLayout tgt = Ui.card(a);
        Ui.add(tgt, Ui.heading(a, "Target", 17), 0);
        TextView lbl = Ui.add(tgt, Ui.text(a, "Used space to reach, in GB. The default is the 70-of-128 GB ratio applied to your phone.", 14, Ui.text2, false), 6);
        targetInput = new EditText(a);
        targetInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        targetInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        targetInput.setTextColor(Ui.text);
        targetInput.setContentDescription("Target used space in gigabytes");
        targetInput.setId(View.generateViewId());
        lbl.setLabelFor(targetInput.getId());              // TalkBack reads the label with the field
        tgt.addView(targetInput, new LinearLayout.LayoutParams(-1, -2));
        long total = new AndroidPlatform(a).totalBytes();
        targetInput.setText(String.format(Locale.US, "%.0f", Math.max(1, total / 1e9 * 70.0 / 128.0)));
        measureBox = new CheckBox(a);
        measureBox.setText("Measure compression on a few of my photos (about 1 extra minute, more accurate)");
        measureBox.setTextColor(Ui.text);
        measureBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        measureBox.setChecked(true);
        measureBox.setMinHeight(dp(48));
        tgt.addView(measureBox, new LinearLayout.LayoutParams(-1, -2));
        final CheckBox sortBox = new CheckBox(a);
        sortBox.setText("Sort pictures into categories and read the text in them, on the phone (the first time takes longer)");
        sortBox.setTextColor(Ui.text);
        sortBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sortBox.setChecked(AnalysisRunner.sortPictures(a));
        sortBox.setMinHeight(dp(48));
        sortBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { AnalysisRunner.setSortPictures(a, on); }
        });
        tgt.addView(sortBox, new LinearLayout.LayoutParams(-1, -2));
        addCard(L, tgt, 16);

        LinearLayout row = Ui.row(a);
        analyzeBtn = Ui.button(a, "Analyse my storage", true);
        analyzeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startAnalysis(); } });
        cancelBtn = Ui.button(a, "Cancel", false);
        cancelBtn.setEnabled(false);
        cancelBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            AnalysisState.cancelled = true;
            refresh();
            v.announceForAccessibility("Cancelling");
        } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(12);
        row.addView(analyzeBtn, lp); row.addView(cancelBtn);
        Button lab = Ui.button(a, "Algorithm Lab (debug)", false);
        lab.setContentDescription("Algorithm Lab: try the picture algorithms on pictures you pick, with a log for debugging");
        lab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new LabScreen(a)); } });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(20);
        L.addView(row, rl);
        LinearLayout.LayoutParams labl = new LinearLayout.LayoutParams(-2, -2); labl.topMargin = dp(10);
        L.addView(lab, labl);
        Ui.add(L, Ui.text(a, "Runs in the background. You can switch apps; you'll get a notification when your plan is ready.", 14, Ui.text2, false), 8);

        progressBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(16);
        L.addView(progressBar, pl);
        progressText = Ui.add(L, Ui.text(a, "", 14, Ui.text2, false), 6);
        progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        resultsBtn = Ui.button(a, "Open last results", false);
        resultsBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new OverviewScreen(a)); } });
        addButton(L, resultsBtn, 16);
        refresh();
        return sv;
    }

    private void addButton(LinearLayout p, Button b, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(top);
        p.addView(b, lp);
    }

    private void addCard(LinearLayout p, View c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top);
        p.addView(c, lp);
    }

    @Override public void refresh() {
        if (storageLine == null) return;
        AndroidPlatform pf = new AndroidPlatform(a);
        long total = pf.totalBytes(), free = pf.freeBytes();
        storageLine.setText("Used " + Report.gb(total - free) + " of " + Report.gb(total));
        boolean files = AndroidPlatform.hasFilesAccess(a), usage = AndroidPlatform.hasUsageAccess(a);
        filesStatus.setText((files ? "✓ Granted: " : "✗ Needed: ") + "all files access, to read photos, videos, downloads and WhatsApp media.");
        filesBtn.setVisibility(files ? View.GONE : View.VISIBLE);
        usageStatus.setText((usage ? "✓ Granted: " : "Optional: ") + "usage access, for app sizes, caches and apps you have not opened in months.");
        usageBtn.setVisibility(usage ? View.GONE : View.VISIBLE);
        boolean running = AnalysisState.running, loading = ResultFiles.loading;
        analyzeBtn.setEnabled(!running && !loading); cancelBtn.setEnabled(running && !AnalysisState.cancelled);
        resultsBtn.setVisibility(Session.hasResults() && !running && !loading ? View.VISIBLE : View.GONE);
        if (running) {
            if (AnalysisState.cancelled) { progressBar.setVisibility(View.VISIBLE); progressBar.setIndeterminate(true); progressText.setText("Cancelling…"); }
            else progress(AnalysisState.stage, AnalysisState.done, AnalysisState.total);
        } else if (loading) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            progressText.setText("Loading your last results...");
        } else {
            progressBar.setVisibility(View.GONE);
            progressText.setText(AnalysisState.message != null ? AnalysisState.message : "");
        }
    }

    private void startAnalysis() {
        if (!AndroidPlatform.hasFilesAccess(a)) { a.toast("Grant all files access first."); return; }
        double gb;
        try { gb = Double.parseDouble(targetInput.getText().toString().trim()); }
        catch (NumberFormatException e) { a.toast("Enter a target in GB."); return; }
        a.startAnalysis(gb, measureBox.isChecked());
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
