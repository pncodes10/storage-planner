package com.storageplanner.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.AlgorithmV3;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Lab;
import com.storageplanner.core.PhotoCategory;
import com.storageplanner.core.Report;
import com.storageplanner.core.RuleSet;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Versus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v2 against v3 (Algorithm Lab): runs the app's current sorting (v2) and the Mirage sorting (v3, AlgorithmV3) with your
 * rules on up to a few thousand of your pictures, then shows one card per picture where they suggest different things:
 * the picture, v2's category and suggestion on the left, v3's on the right. Mark the one that is right (buttons, or swipe
 * the card towards it) and the next card comes; the score is on top. Cards are saved as they are made and marked, so a
 * run can be stopped, continued and marked over several sittings. Read-only: nothing on the phone is changed.
 */
public final class VersusScreen extends Screen {
    private static final String PREFS = "versus";
    private static final int DETAIL_MAX = 150;          // pictures whose full Lab lines go to the log

    // ---- shared by every visit to the page: the cards, the run and the files they are kept in (main thread only)
    private static List<Versus.Card> cards;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(), IO = Executors.newSingleThreadExecutor();
    private static volatile boolean running, stop;
    private static volatile String runStatus = "";
    private static volatile int runDone, runTotal;
    private static VersusScreen shown;                   // the page on screen, told about new cards

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Integer> undo = new ArrayDeque<Integer>();
    private int current = -1;
    private boolean animating;
    private TextView scoreBig, scoreLine, status, hint;
    private ProgressBar bar;
    private EditText countBox;
    private CheckBox newestBox, agreeBox;
    private Button startBtn, stopBtn, v2Btn, v3Btn, skipBtn, undoBtn;
    private FrameLayout cardBox;
    private View front;

    public VersusScreen(MainActivity a) { super(a); }

    public String title() { return "v2 vs v3"; }

    @Override public String descriptor() { return "versus"; }

    private SharedPreferences prefs() { return a.getSharedPreferences(PREFS, 0); }

    private boolean withAgreeing() { return prefs().getBoolean("agreeing", false); }

    // ------------------------------------------------------------------ page
    protected View build() {
        if (cards == null) cards = load(a);
        if (!running && runTotal == 0) { runTotal = readTodo(a).size(); runDone = Math.min(cards.size(), runTotal); }
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);

        // score
        LinearLayout head = Ui.card(a);
        scoreBig = Ui.text(a, "", 26, Ui.text, true);
        scoreBig.setGravity(Gravity.CENTER_HORIZONTAL);
        scoreBig.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        head.addView(scoreBig);
        scoreLine = Ui.text(a, "", 13, Ui.text2, false);
        scoreLine.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(4);
        head.addView(scoreLine, sl);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // run
        LinearLayout runRow = Ui.row(a);
        TextView l = Ui.text(a, "Pictures", 15, Ui.text, false);
        runRow.addView(l, new LinearLayout.LayoutParams(-2, -2));
        countBox = new EditText(a);
        countBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        countBox.setText(String.valueOf(prefs().getInt("count", 1000)));
        countBox.setSelectAllOnFocus(true);
        countBox.setTextColor(Ui.text);
        countBox.setGravity(Gravity.END);
        countBox.setContentDescription("How many pictures to compare");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(90), -2); cl.leftMargin = dp(8);
        runRow.addView(countBox, cl);
        newestBox = new CheckBox(a);
        newestBox.setText("newest");
        newestBox.setTextColor(Ui.text);
        newestBox.setChecked(prefs().getBoolean("newest", true));
        runRow.addView(newestBox, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(10);
        root.addView(runRow, rl);

        LinearLayout runBtns = Ui.row(a);
        startBtn = Ui.button(a, "Start", true);
        startBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startOrContinue(); } });
        stopBtn = Ui.button(a, "Stop", false);
        stopBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stop = true; runStatus = "Stopping after this picture…"; updateRun(); } });
        Button fresh = Ui.button(a, "New run", false);
        fresh.setContentDescription("Forget these cards and marks and start again");
        fresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { newRun(); } });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1); b1.rightMargin = dp(8);
        runBtns.addView(startBtn, b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, -2, 1); b2.rightMargin = dp(8);
        runBtns.addView(stopBtn, b2);
        runBtns.addView(fresh, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rbl = new LinearLayout.LayoutParams(-1, -2); rbl.topMargin = dp(6);
        root.addView(runBtns, rbl);
        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(6);
        root.addView(bar, bl);
        status = Ui.text(a, "", 13, Ui.text2, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(root, status, 2);

        // the card
        hint = Ui.text(a, "← swipe left: v2 is right   ·   swipe right: v3 is right →", 13, Ui.text2, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.add(root, hint, 12);
        cardBox = new FrameLayout(a);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(-1, -2); cbl.topMargin = dp(6);
        root.addView(cardBox, cbl);

        LinearLayout pick = Ui.row(a);
        v2Btn = Ui.button(a, "v2 is right", true);
        v2Btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { fly(-1); } });
        v3Btn = Ui.button(a, "v3 is right", true);
        v3Btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { fly(+1); } });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1); p1.rightMargin = dp(10);
        pick.addView(v2Btn, p1);
        pick.addView(v3Btn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(10);
        root.addView(pick, pl);
        LinearLayout more = Ui.row(a);
        skipBtn = Ui.button(a, "Skip (can't tell)", false);
        skipBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { mark(Versus.SKIP); } });
        undoBtn = Ui.button(a, "Undo", false);
        undoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { undoLast(); } });
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, -2, 1); m1.rightMargin = dp(10);
        more.addView(skipBtn, m1);
        more.addView(undoBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = dp(6);
        root.addView(more, ml);

        agreeBox = new CheckBox(a);
        agreeBox.setText("Also show pictures where v2 and v3 agree (same category and suggestion)");
        agreeBox.setTextColor(Ui.text);
        agreeBox.setChecked(withAgreeing());
        agreeBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { prefs().edit().putBoolean("agreeing", on).apply(); current = -1; showCard(); }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(12);
        root.addView(agreeBox, al);
        Button save = Ui.button(a, "Save the score and marks to the Lab log", false);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (String s : Versus.report(new ArrayList<Versus.Card>(cards), withAgreeing())) LabLog.line(a, s);
                a.toast("Written to the Lab log (Algorithm Lab > Save log or Share log).");
            }
        });
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, -2); svl.topMargin = dp(6);
        root.addView(save, svl);
        MirageCard.addTo(a, root);
        Ui.add(root, Ui.text(a, "v2 is the app's sorting today (its own text reader, Tesseract). v3 sorts with Mirage, Cactus Compute's vision model: "
                + "its text through the same text rules, memes and forwards from what it says the picture looks like, and no accidental shot where it "
                + "clearly sees something. Both use your rules. Each picture is judged on its own, so duplicates, bursts and blur against your other "
                + "photos are not part of it. Needs the Mirage model (download it in the box above). About 2-5 seconds per picture: 1,000 pictures take about an hour. Keep the screen on this page; "
                + "Stop and Start continue where it stopped. Nothing on the phone is changed.", 12, Ui.text2, false), 14);

        shown = this;
        updateScore();
        updateRun();
        showCard();
        return sv;
    }

    @Override public void refresh() { shown = this; if (cardBox != null) { updateScore(); updateRun(); if (front == null) showCard(); } }

    @Override public void onHide() { if (shown == this) shown = null; }

    // ------------------------------------------------------------------ the run
    private void startOrContinue() {
        if (running) return;
        if (!AndroidPlatform.hasFilesAccess(a)) { a.toast("Grant all files access first (on the start screen)."); return; }
        int n;
        try { n = Integer.parseInt(countBox.getText().toString().replace(",", "").trim()); } catch (NumberFormatException e) { n = 1000; }
        n = Math.max(1, Math.min(20000, n));
        countBox.setText(String.valueOf(n));
        final boolean newest = newestBox.isChecked();
        prefs().edit().putInt("count", n).putBoolean("newest", newest).apply();
        final int want = n;
        final Context c = a.getApplicationContext();
        final RuleSet rules = RulesStore.get(a).copy();
        final AndroidPlatform pf = new AndroidPlatform(a);
        running = true; stop = false;
        runStatus = "Listing pictures…";
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateRun();
        WORKER.execute(new Runnable() {
            public void run() {
                try { work(c, pf, rules, want, newest); }
                catch (Throwable t) { LabLog.error(c, "v2 vs v3", t); runStatus = "Failed: " + t; }
                running = false;
                post(new Runnable() { public void run() { if (shown != null) shown.a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } });
                post(null);
            }
        });
    }

    /** Lists the pictures once per run (kept in todo.txt), then sorts the ones without a card yet, saving each card. */
    private static void work(final Context c, AndroidPlatform pf, RuleSet rules, int want, boolean newest) throws Exception {
        List<FileItem> todo = readTodo(c);
        if (todo.isEmpty()) {
            Scanner.Progress pr = new Scanner.Progress() {
                public void update(String stage, int done, int total) { runStatus = stage + (done > 0 ? " (" + done + " found)" : "") + "…"; post(null); }
                public boolean cancelled() { return stop; }
            };
            todo = Versus.choose(pf, want, newest, System.currentTimeMillis(), pr);
            writeTodo(c, todo);
            LabLog.line(c, "\n--- " + LabLog.now() + "  v2 vs v3: " + todo.size() + " pictures (" + (newest ? "newest" : "random") + ")");
        }
        final Set<String> done = new HashSet<String>();
        final Object flushed = new Object();
        final boolean[] ok = {false};
        IO.execute(new Runnable() { public void run() { synchronized (flushed) { ok[0] = true; flushed.notifyAll(); } } });   // earlier saves first
        synchronized (flushed) { while (!ok[0]) flushed.wait(); }
        for (Versus.Card k : load(c)) done.add(k.path);
        runTotal = todo.size();
        runDone = done.size();
        long t0 = System.currentTimeMillis();
        int made = 0;
        for (FileItem f : todo) {
            if (stop) break;
            if (done.contains(f.abs)) continue;
            int k = runDone + 1;
            long el = System.currentTimeMillis() - t0;
            runStatus = "Picture " + k + " of " + runTotal + (made > 2 ? String.format(Locale.US, ", about %d min left", Math.round(el / (double) made * (runTotal - runDone) / 60000.0)) : "");
            post(null);
            final boolean full = k <= DETAIL_MAX;
            Lab.Out out = full ? new Lab.Out() { public void line(String s) { LabLog.line(c, s); } } : new Lab.Out() { public void line(String s) {} };
            if (full) LabLog.line(c, "\n[v2 vs v3 " + k + "/" + runTotal + "] " + f.rel);
            final Versus.Card card = Versus.judge(pf, f, rules, System.currentTimeMillis(), out);
            if (!full) LabLog.line(c, "[v2 vs v3 " + k + "/" + runTotal + "] " + f.rel + ": v2 " + card.v2.action + " | v3 "
                    + (card.v3Note != null ? card.v3Note : card.v3.action) + (card.problem != null ? " (" + card.problem + ")" : ""));
            final String line = Versus.toLine(card);
            IO.execute(new Runnable() { public void run() { append(c, line); } });
            done.add(f.abs);
            runDone++; made++;
            post(new Runnable() { public void run() { cards.add(card); } });
        }
        runStatus = stop ? "Stopped at " + runDone + " of " + runTotal + ". Start continues from there." : "Done: all " + runTotal + " pictures compared.";
    }

    /** Runs r on the main thread (null: just refresh the page on screen). */
    private static void post(final Runnable r) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                if (r != null) r.run();
                VersusScreen s = shown;
                if (s != null && s.cardBox != null) { s.updateScore(); s.updateRun(); if (s.front == null) s.showCard(); }
            }
        });
    }

    private void newRun() {
        if (running) { a.toast("Stop the run first."); return; }
        new android.app.AlertDialog.Builder(a).setTitle("Start a new comparison?")
                .setMessage("This forgets the " + cards.size() + " compared pictures and your marks (the Lab log keeps what was written to it).")
                .setPositiveButton("New run", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        cards.clear(); undo.clear(); current = -1;
                        final Context c = a.getApplicationContext();
                        IO.execute(new Runnable() { public void run() { rewrite(c, new ArrayList<Versus.Card>()); writeTodo(c, new ArrayList<FileItem>()); } });
                        runStatus = ""; runDone = 0; runTotal = 0;
                        updateScore(); updateRun(); showCard();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void updateRun() {
        if (startBtn == null) return;
        boolean todoLeft = runTotal > 0 && runDone < runTotal;
        startBtn.setText(running ? "Running…" : todoLeft ? "Continue" : "Start");
        startBtn.setEnabled(!running && (todoLeft || runTotal == 0));
        stopBtn.setEnabled(running);
        countBox.setEnabled(!running && runTotal == 0);
        newestBox.setEnabled(!running && runTotal == 0);
        bar.setVisibility(running || todoLeft ? View.VISIBLE : View.GONE);
        if (runTotal > 0) bar.setProgress((int) (1000L * runDone / runTotal));
        String s = runStatus;
        if (s.isEmpty()) s = cards.isEmpty() ? "Type how many pictures (from camera, screenshots, downloads, WhatsApp and other pictures) and tap Start."
                : cards.size() + " pictures compared so far.";
        status.setText(s);
    }

    // ------------------------------------------------------------------ score and card
    private void updateScore() {
        Versus.Score s = Versus.score(cards, withAgreeing());
        scoreBig.setText(String.format(Locale.US, "v2  %d   :   %d  v3", s.v2, s.v3));
        scoreBig.setContentDescription(String.format(Locale.US, "Score: v2 %d, v3 %d", s.v2, s.v3));
        String extra = s.problems > 0 ? String.format(Locale.US, " · could not compare %d", s.problems) : "";
        scoreLine.setText(String.format(Locale.US, "%d compared · %d to mark · %d skipped · %d where both agree%s",
                s.total, s.left, s.skipped, s.agree, extra));
    }

    private void showCard() {
        if (cardBox == null) return;
        cardBox.removeAllViews();
        front = null;
        current = Versus.next(cards, current < 0 ? 0 : current, withAgreeing());
        boolean have = current >= 0;
        v2Btn.setEnabled(have); v3Btn.setEnabled(have); skipBtn.setEnabled(have);
        undoBtn.setEnabled(!undo.isEmpty());
        hint.setVisibility(have ? View.VISIBLE : View.GONE);
        if (!have) {
            Versus.Score s = Versus.score(cards, withAgreeing());
            String msg;
            if (cards.isEmpty()) msg = running ? "The first card comes when the first picture is compared…" : "No pictures compared yet.";
            else if (running) msg = "All cards so far are marked. The next one comes when v2 and v3 disagree on a picture (category or suggestion)…";
            else {
                String winner = s.v2 == s.v3 ? "a tie" : s.v2 > s.v3 ? "v2 was right more often" : "v3 was right more often";
                msg = String.format(Locale.US, "All marked. Final score: v2 %d, v3 %d (%s). %d pictures where both agree, %d skipped.",
                        s.v2, s.v3, winner, s.agree, s.skipped);
                if (s.problems > 0) msg += String.format(Locale.US, " %d could not be compared (see the Lab log).", s.problems);
            }
            TextView t = Ui.text(a, msg, 15, Ui.text, false);
            t.setBackground(Ui.round(Ui.surface, dp(12)));
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            cardBox.addView(t, new FrameLayout.LayoutParams(-1, -2));
            return;
        }
        final Versus.Card c = cards.get(current);
        LinearLayout card = Ui.card(a);
        TextView name = Ui.text(a, c.name, 14, Ui.text, true);
        Ui.ellipsizeMiddle(name);
        card.addView(name);
        card.addView(Ui.text(a, place(c.area) + " · " + Ui.date(c.mtime) + String.format(Locale.US, " · %.1f MB", c.size / 1e6), 12, Ui.text2, false));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(Ui.surface2);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, dp(280)); il.topMargin = dp(8);
        card.addView(iv, il);
        FileItem f = new FileItem(c.rel, c.path, c.size, c.mtime);
        f.area = c.area;
        if (Thumbs.canPreview(f)) Thumbs.load(iv, f, Math.min(1080, a.getResources().getDisplayMetrics().widthPixels));
        LinearLayout cols = Ui.row(a);
        cols.setGravity(Gravity.TOP);
        cols.addView(column(AlgorithmV3.V2_NAME, c.v2), new LinearLayout.LayoutParams(0, -2, 1));
        View sep = new View(a); sep.setBackgroundColor(Ui.divider);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(dp(1), -1); spl.leftMargin = dp(8); spl.rightMargin = dp(8);
        cols.addView(sep, spl);
        cols.addView(column(AlgorithmV3.NAME, c.v3), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams col = new LinearLayout.LayoutParams(-1, -2); col.topMargin = dp(10);
        card.addView(cols, col);
        if (c.agree()) Ui.add(card, Ui.text(a, "Both agree: same category and suggestion.", 12, Ui.text2, false), 6);
        card.setContentDescription("Picture " + c.name + ". v2: " + PhotoCategory.label(c.v2.category) + ", " + c.v2.action + ". v3: "
                + PhotoCategory.label(c.v3.category) + ", " + c.v3.action + ". Use the buttons below to mark which is right.");

        FrameLayout holder = new FrameLayout(a);
        holder.addView(card, new FrameLayout.LayoutParams(-1, -2));
        TextView leftTag = tag("v2 ✓", Gravity.START), rightTag = tag("v3 ✓", Gravity.END);
        holder.addView(leftTag, tagParams(Gravity.TOP | Gravity.START));
        holder.addView(rightTag, tagParams(Gravity.TOP | Gravity.END));
        holder.setOnTouchListener(new Swipe(leftTag, rightTag));
        cardBox.addView(holder, new FrameLayout.LayoutParams(-1, -2));
        front = holder;
    }

    private LinearLayout column(String title, Versus.Side s) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(Ui.text(a, title, 12, Ui.text2, true));
        TextView cat = Ui.text(a, PhotoCategory.label(s.category), 15, Ui.text, true);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(2);
        l.addView(cat, cl);
        boolean del = s.action.startsWith("Delete");
        TextView act = Ui.text(a, s.action, 14, del ? 0xFFD84315 : s.action.startsWith("Keep") ? Ui.text2 : Ui.accent, true);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(4);
        l.addView(act, al);
        String why = s.why + (s.date > 0 ? (s.why.isEmpty() ? "" : "; ") + "date " + Report.day(s.date) : "");
        if (!why.isEmpty()) Ui.add(l, Ui.text(a, why, 12, Ui.text2, false), 4);
        if (!s.detail.isEmpty()) Ui.add(l, Ui.text(a, s.detail, 12, Ui.text2, false), 4);
        return l;
    }

    private TextView tag(String s, int gravity) {
        TextView t = Ui.text(a, s, 18, Ui.onAccent, true);
        t.setBackground(Ui.round(Ui.accent, dp(8)));
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setGravity(gravity);
        t.setAlpha(0f);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    private FrameLayout.LayoutParams tagParams(int g) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, g);
        p.setMargins(dp(12), dp(12), dp(12), 0);
        return p;
    }

    static String place(String area) {
        switch (area == null ? "" : area) {
            case "camera": return "Camera";
            case "screenshot": return "Screenshot";
            case "download": return "Download";
            case "wa_image": return "WhatsApp (received)";
            case "wa_sent": return "WhatsApp (sent)";
            case "pictures": return "Pictures folder";
            default: return area;
        }
    }

    // ------------------------------------------------------------------ marking
    private static boolean animOn() { return android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled(); }

    /** Animate the card off towards the side marked right (-1 left: v2, +1 right: v3), then mark it. */
    private void fly(final int dir) {
        if (front == null || animating || current < 0) return;
        if (!animOn()) { mark(dir < 0 ? Versus.V2 : Versus.V3); return; }
        animating = true;
        float w = Math.max(front.getWidth(), dp(320));
        front.animate().translationX(dir * w * 1.5f).rotation(dir * 18f).alpha(0f).setDuration(200).withEndAction(new Runnable() {
            public void run() { animating = false; mark(dir < 0 ? Versus.V2 : Versus.V3); }
        }).start();
    }

    private void mark(int m) {
        if (current < 0 || current >= cards.size()) return;
        cards.get(current).mark = m;
        undo.push(current);
        save();
        updateScore();
        String said = m == Versus.V2 ? "v2 marked right" : m == Versus.V3 ? "v3 marked right" : "Skipped";
        current++;
        showCard();
        if (scoreBig != null) scoreBig.announceForAccessibility(said + ". " + scoreBig.getContentDescription());
    }

    private void undoLast() {
        if (undo.isEmpty()) return;
        int i = undo.pop();
        if (i < cards.size()) cards.get(i).mark = Versus.NONE;
        save();
        current = i;
        updateScore();
        showCard();
    }

    private void save() {
        final List<Versus.Card> copy = new ArrayList<Versus.Card>(cards);
        final Context c = a.getApplicationContext();
        IO.execute(new Runnable() { public void run() { rewrite(c, copy); } });
    }

    private final class Swipe implements View.OnTouchListener {
        private final View leftTag, rightTag;
        private float x0, y0, dx, dy;
        private boolean dragging;
        private VelocityTracker vt;
        private final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        private final float flingV = dp(250);

        Swipe(View l, View r) { leftTag = l; rightTag = r; }

        private float commit(float w) { return Math.max(slop * 2f, Math.min(w * 0.2f, dp(72))); }

        public boolean onTouch(View v, MotionEvent e) {
            if (animating) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX(); y0 = e.getRawY(); dx = dy = 0; dragging = false;
                    vt = VelocityTracker.obtain(); vt.addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    dx = e.getRawX() - x0; dy = e.getRawY() - y0;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!dragging && Math.abs(dy) > slop && v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging) {
                        float w = Math.max(1, v.getWidth());
                        v.setTranslationX(dx);
                        v.setRotation(dx / w * 10f);
                        float c = commit(w);
                        leftTag.setAlpha(Math.max(0f, Math.min(1f, -dx / c)));
                        rightTag.setAlpha(Math.max(0f, Math.min(1f, dx / c)));
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    float vx = 0;
                    if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    if (dragging) {
                        float c = commit(Math.max(1, v.getWidth()));
                        if (dx >= c || (vx > flingV && dx > 0)) fly(+1);
                        else if (dx <= -c || (vx < -flingV && dx < 0)) fly(-1);
                        else snapBack(v);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (vt != null) { vt.recycle(); vt = null; }
                    snapBack(v);
                    return true;
            }
            return false;
        }

        private void snapBack(View v) {
            v.animate().translationX(0).rotation(0).setDuration(animOn() ? 160 : 0).start();
            leftTag.setAlpha(0f); rightTag.setAlpha(0f);
        }
    }

    // ------------------------------------------------------------------ files (the app's own folder; only the IO thread writes)
    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "versus");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    static List<Versus.Card> load(Context c) {
        List<Versus.Card> l = new ArrayList<Versus.Card>();
        File f = new File(dir(c), "cards.jsonl");
        if (!f.exists()) return l;
        Set<String> seen = new HashSet<String>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            try {
                String s;
                while ((s = r.readLine()) != null) {
                    Versus.Card k = Versus.fromLine(s);
                    if (k != null && seen.add(k.path)) l.add(k);
                }
            } finally { r.close(); }
        } catch (Throwable t) { LabLog.error(c, "v2 vs v3: reading the cards", t); }
        return l;
    }

    private static void append(Context c, String line) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir(c), "cards.jsonl"), true), UTF8);
            try { w.write(line); w.write('\n'); } finally { w.close(); }
        } catch (Throwable t) { LabLog.error(c, "v2 vs v3: saving a card", t); }
    }

    private static void rewrite(Context c, List<Versus.Card> l) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir(c), "cards.jsonl"), false), UTF8);
            try { for (Versus.Card k : l) { w.write(Versus.toLine(k)); w.write('\n'); } } finally { w.close(); }
        } catch (Throwable t) { LabLog.error(c, "v2 vs v3: saving the marks", t); }
    }

    /** The pictures of the run: area, size, mtime, rel, abs (tab-separated), one per line. */
    private static void writeTodo(Context c, List<FileItem> l) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir(c), "todo.txt"), false), UTF8);
            try { for (FileItem f : l) w.write(f.area + "\t" + f.size + "\t" + f.mtime + "\t" + f.rel + "\t" + f.abs + "\n"); } finally { w.close(); }
        } catch (Throwable t) { LabLog.error(c, "v2 vs v3: saving the picture list", t); }
    }

    private static List<FileItem> readTodo(Context c) {
        List<FileItem> l = new ArrayList<FileItem>();
        File f = new File(dir(c), "todo.txt");
        if (!f.exists()) return l;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            try {
                String s;
                while ((s = r.readLine()) != null) {
                    String[] p = s.split("\t");
                    if (p.length != 5) continue;
                    FileItem it = new FileItem(p[3], p[4], Long.parseLong(p[1]), Long.parseLong(p[2]));
                    it.area = p[0];
                    l.add(it);
                }
            } finally { r.close(); }
        } catch (Throwable t) { LabLog.error(c, "v2 vs v3: reading the picture list", t); }
        return l;
    }
}
