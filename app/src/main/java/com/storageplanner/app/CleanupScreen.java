package com.storageplanner.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Option;
import com.storageplanner.core.Piles;
import com.storageplanner.core.Report;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;

import java.util.List;

/**
 * "Clean up": the plan at a glance. How much it frees, then plain piles of what is removed (copies, memes, blurry
 * shots, apps you don't use ...) with a strip of their pictures, and what is made smaller. No rules, tags or risk tiers
 * here: each file's reasons are one tap away on its own page. One button at the bottom goes on to the clean-up.
 */
public final class CleanupScreen extends Screen {
    private LinearLayout content;
    private Button go, play;

    public CleanupScreen(MainActivity a) { super(a); }

    public String title() { return "Clean up"; }

    @Override public String descriptor() { return "cleanup"; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(14));
        bar.addView(Ui.divider(a));
        go = Ui.button(a, "", true);
        go.setTextSize(17);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ConfirmScreen(a)); } });
        play = Ui.button(a, "▶  Carry on", false);
        play.setTextSize(16);
        play.setContentDescription("Carry on analysing the files not done yet");
        play.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (AnalysisState.running) a.showProgress(); else a.carryOn();
        } });
        LinearLayout buttons = Ui.row(a);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, dp(56)); pl.rightMargin = dp(10);
        buttons.addView(play, pl);
        buttons.addView(go, new LinearLayout.LayoutParams(0, dp(56), 1));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2); gl.topMargin = dp(10);
        bar.addView(buttons, gl);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        refresh();
        return root;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        if (!Session.hasResults()) {
            Ui.add(content, Ui.text(a, "No analysis yet. Go back and tap Analyse my storage.", 15, Ui.text2, false), 8);
            go.setVisibility(View.GONE);
            play.setVisibility(View.GONE);
            return;
        }
        List<FinalPlan.Entry> all = PlanStore.entries(a);
        List<Piles.Pile> piles = Piles.build(all);
        FinalPlan.Totals t = FinalPlan.totals(all, null);
        long fin = PlanStore.finalized(a);
        long removed = t.saved(FinalPlan.DELETE), smaller = t.saved(FinalPlan.COMPRESS);
        java.util.Set<String> keys = ReviewStore.keys(a);
        final FinalPlan.Totals ready = FinalPlan.totals(Piles.ready(all, keys), null);
        hero(t.freed(), removed, smaller, Session.used(), fin);
        reviewCard(ready.freed(), t.freed());
        String paused = AnalysisScreen.pausedLine(a);
        if (paused != null || AnalysisState.running) {
            int waiting = Session.ub == null ? 0 : Session.ub.det.waiting.size();
            LinearLayout band = new LinearLayout(a);
            band.setOrientation(LinearLayout.VERTICAL);
            band.setBackground(Ui.round(Ui.removeTint, dp(14)));
            band.setPadding(dp(14), dp(12), dp(14), dp(12));
            band.addView(Ui.text(a, AnalysisState.running ? "Analysing in the background" : paused, 15, Ui.removeInk, true));
            band.addView(Ui.text(a, AnalysisState.running ? (paused != null ? "This is the plan from when you paused (" + paused.substring(paused.indexOf('·') + 2)
                    + ")." : "This is the plan from your last analysis.") + " It is brought up to date when the analysis pauses or finishes." : "This plan covers only those. The rest are left alone until you carry on."
                    + (waiting > 0 ? " " + Piles.count(waiting, "group") + " of similar shots wait for photos not looked at yet." : ""), 13, Ui.removeInk, false));
            band.setContentDescription(AnalysisState.running ? "Analysing in the background." : paused + ". This plan covers only those.");
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(10);
            content.addView(band, bl);
        }
        String part = AnalysisRunner.lastSample(a);
        if (part != null) {
            TextView n = Ui.text(a, "This plan covers only part of your pictures (" + part + "). Change it in Settings › Analysis.", 13, Ui.keepInk, false);
            n.setBackground(Ui.round(Ui.keepTint, dp(12)));
            n.setPadding(dp(12), dp(10), dp(12), dp(10));
            Ui.add(content, n, 10);
        }

        boolean anyRemove = false;
        for (Piles.Pile p : piles) if (!Piles.SMALLER.equals(p.id)) anyRemove = true;
        long[] apps = unusedApps();
        if (anyRemove || apps[0] > 0) section("Will be removed");
        for (final Piles.Pile p : piles) {
            if (Piles.SMALLER.equals(p.id)) continue;
            pileRow(p.title(), p.subtitle() + reviewed(p, keys), p.freed(), p.files, null, new View.OnClickListener() {
                public void onClick(View v) { a.push(new PileScreen(a, p.id)); }
            });
        }
        if (apps[0] > 0)
            pileRow("Apps you don't use", Piles.count((int) apps[0], "app") + " · not opened in months", apps[1], null, "▦", new View.OnClickListener() {
                public void onClick(View v) { a.push(ListScreen.apps(a)); }
            });
        for (final Piles.Pile p : piles) {
            if (!Piles.SMALLER.equals(p.id)) continue;
            section("Made smaller, look the same");
            pileRow("Videos, photos and music", p.subtitle() + reviewed(p, keys), p.freed(), p.files, null, new View.OnClickListener() {
                public void onClick(View v) { a.push(new PileScreen(a, p.id)); }
            });
        }
        if (piles.isEmpty() && apps[0] == 0)
            Ui.add(content, Ui.text(a, "Nothing to clean up: your phone looks tidy.", 16, Ui.text, false), 16);
        Ui.add(content, Ui.text(a, "Photos with people in them, your favourites and anything you tap to keep are never removed.", 13, Ui.text2, false), 14);

        // the rest, out of the way
        section("More");
        link("See everything by file type", new View.OnClickListener() { public void onClick(View v) { a.push(new PlanScreen(a)); } });
        link("Start over (back to the suggestions)", new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("Start over?").setMessage("Every file goes back to what the app suggests.")
                        .setPositiveButton("Start over", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { PlanStore.reset(a); refresh(); }
                        }).setNegativeButton("Cancel", null).show();
            }
        });

        go.setVisibility(t.freed() > 0 ? View.VISIBLE : View.INVISIBLE);
        go.setEnabled(ready.freed() > 0);
        go.setAlpha(ready.freed() > 0 ? 1f : 0.5f);
        play.setText(AnalysisState.running ? "See progress" : "▶  Carry on");
        play.setContentDescription(AnalysisState.running ? "See the analysis progress" : "Carry on analysing the files not done yet");
        play.setVisibility(paused != null || AnalysisState.running ? View.VISIBLE : View.GONE);
        go.setText(ready.freed() == 0 ? "Review a pile to clean up" : fin == 0 ? "Clean up " + Report.size(ready.freed()) + " reviewed"
                : "Continue clean-up · " + Report.size(ready.freed()));
    }

    /** {count, bytes} of the installed apps the rules would uninstall or archive. */
    static long[] unusedApps() {
        long n = 0, b = 0;
        if (Session.ub == null || !Session.appStats) return new long[]{0, 0};
        for (Unit u : Session.ub.units) {
            if (!"app".equals(u.kind)) continue;
            long best = 0;
            for (Option o : u.options)
                if (Techniques.UNINSTALL_APPS.equals(o.technique) || Techniques.UNINSTALL_GAMES.equals(o.technique) || Techniques.ARCHIVE.equals(o.technique))
                    best = Math.max(best, o.saved);
            if (best > 0) { n++; b += best; }
        }
        return new long[]{n, b};
    }

    private void hero(long freed, long removed, long smaller, long used, long fin) {
        LinearLayout c = Ui.card(a);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        c.setBackground(Ui.round(Ui.surface, dp(20)));
        if (fin != 0) {
            TextView st = Ui.text(a, "✓ Plan finished on " + Ui.date(fin), 13, Ui.keepInk, true);
            c.addView(st);
        }
        Ui.add(c, Ui.text(a, "You can free up", 15, Ui.text2, false), fin != 0 ? 8 : 0);
        TextView big = Ui.text(a, Report.size(freed), 42, Ui.text, true);
        c.addView(big);

        LinearLayout bar = Ui.row(a);
        bar.setBackground(Ui.round(Ui.surface2, dp(6)));
        bar.setClipToOutline(true);
        long whole = Math.max(1, used);
        addSeg(bar, Ui.REMOVE, removed, whole);
        addSeg(bar, Ui.SMALLER, smaller, whole);
        View rest = new View(a);
        bar.addView(rest, new LinearLayout.LayoutParams(0, dp(12), Math.max(0.0001f, 1f - (removed + smaller) / (float) whole)));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(12)); bl.topMargin = dp(14);
        c.addView(bar, bl);

        LinearLayout legend = Ui.row(a);
        legend.addView(legendItem("■ " + Report.size(removed) + " removed", Ui.removeInk));
        if (smaller > 0) legend.addView(legendItem("■ " + Report.size(smaller) + " smaller", Ui.smallerInk));
        legend.addView(legendItem("of " + Report.size(used) + " used", Ui.text2));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = dp(10);
        c.addView(legend, ll);
        c.setContentDescription("You can free up " + Report.size(freed) + ": " + Report.size(removed) + " removed"
                + (smaller > 0 ? ", " + Report.size(smaller) + " made smaller" : "") + ", out of " + Report.size(used) + " used."
                + (fin != 0 ? " The plan is finished." : ""));
        c.setFocusable(true);
        content.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    /** " · 40% reviewed" / " · ✓ reviewed" for a pile row. */
    private static String reviewed(Piles.Pile p, java.util.Set<String> keys) {
        int n = p.reviewed(keys), t = p.files.size();
        return n >= t ? " · ✓ reviewed" : " · " + (t == 0 ? 0 : 100L * n / t) + "% reviewed";
    }

    /**
     * How much of the plan you have reviewed: only files you have looked at on their pile's page are cleaned up, so the
     * clean-up frees this much now, the rest once you have scrolled through the other piles.
     */
    private void reviewCard(long ready, long total) {
        LinearLayout c = Ui.card(a);
        c.setBackground(Ui.round(Ui.surface, dp(20)));
        c.setPadding(dp(20), dp(14), dp(20), dp(14));
        int pct = total <= 0 ? 100 : (int) Math.min(100, 100L * ready / total);
        c.addView(Ui.text(a, pct >= 100 ? "✓ Everything reviewed" : "Reviewed " + pct + "%: " + Report.size(ready) + " of " + Report.size(total)
                + " ready to clean up", 15, pct >= 100 ? Ui.keepInk : Ui.text, true));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(pct);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(6);
        c.addView(bar, bl);
        if (pct < 100)
            Ui.add(c, Ui.text(a, "Only the files you have scrolled past in a pile are cleaned up. Open each pile and scroll to its end to add the rest.",
                    13, Ui.text2, false), 6);
        c.setContentDescription("Reviewed " + pct + " percent: " + Report.size(ready) + " of " + Report.size(total) + " ready to clean up.");
        c.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        content.addView(c, lp);
    }

    private void addSeg(LinearLayout bar, int color, long b, long whole) {
        if (b <= 0) return;
        View s = new View(a);
        s.setBackgroundColor(color);
        bar.addView(s, new LinearLayout.LayoutParams(0, dp(12), Math.max(0.01f, b / (float) whole)));
    }

    private TextView legendItem(String s, int color) {
        TextView t = Ui.text(a, s, 13, color, false);
        t.setPadding(0, 0, dp(14), 0);
        return t;
    }

    private void section(String title) {
        TextView h = Ui.heading(a, title.toUpperCase(java.util.Locale.ROOT), 13);
        h.setTextColor(Ui.text2);
        h.setLetterSpacing(0.06f);
        Ui.add(content, h, 22);
    }

    /** A pile: a 2×2 strip of its pictures (or a symbol), its name and count, what it frees. */
    private void pileRow(String title, String sub, long bytes, List<FinalPlan.Entry> files, String symbol, View.OnClickListener open) {
        LinearLayout row = Ui.row(a);
        row.setBackground(Ui.round(Ui.surface, dp(16)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(72));
        row.addView(mosaic(files, symbol), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.text(a, title, 16, Ui.text, true));
        mid.addView(Ui.text(a, sub, 13, Ui.text2, false));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1); ml.leftMargin = dp(12); ml.rightMargin = dp(8);
        row.addView(mid, ml);
        row.addView(Ui.text(a, Report.size(bytes), 15, Ui.text, true));
        TextView chev = Ui.text(a, "›", 24, Ui.text2, false);
        chev.setPadding(dp(8), 0, 0, 0);
        row.addView(chev);
        row.setContentDescription(title + ", " + sub + ", frees " + Report.size(bytes) + ". Double tap to see them.");
        row.setClickable(true); row.setFocusable(true);
        row.setOnClickListener(open);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        content.addView(row, lp);
    }

    private View mosaic(List<FinalPlan.Entry> files, String symbol) {
        GridLayout g = new GridLayout(a);
        g.setColumnCount(2);
        g.setBackground(Ui.round(Ui.surface2, dp(10)));
        g.setClipToOutline(true);
        int shown = 0, cell = dp(25);
        if (files != null)
            for (FinalPlan.Entry e : files) {
                if (shown == 4) break;
                if (!PileScreen.picture(e.file)) continue;
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = cell; lp.height = cell;
                lp.setMargins(shown % 2 == 1 ? dp(2) : 0, shown >= 2 ? dp(2) : 0, 0, 0);
                g.addView(iv, lp);
                Thumbs.load(iv, e.file, cell);
                shown++;
            }
        if (shown > 0) return g;
        TextView t = Ui.text(a, symbol != null ? symbol : symbolFor(files), 22, Ui.text2, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.round(Ui.surface2, dp(10));
        t.setBackground(bg);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    static String symbolFor(List<FinalPlan.Entry> files) {
        if (files == null || files.isEmpty()) return "▤";
        FileItem f = files.get(0).file;
        String n = f.name().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk")) return "⬇";
        return "▤";
    }

    private void link(String s, View.OnClickListener l) {
        TextView t = Ui.text(a, s, 15, Ui.accent, true);
        t.setMinHeight(dp(48));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setOnClickListener(l);
        Ui.add(content, t, 4);
    }
}
