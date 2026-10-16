package com.storageplanner.app;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.Categories;
import com.storageplanner.core.Option;
import com.storageplanner.core.Report;
import com.storageplanner.core.Review;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Results home: storage meter, recommendations (lowest risk first), what is using space. */
public final class OverviewScreen extends Screen {
    private LinearLayout content;

    public OverviewScreen(MainActivity a) { super(a); }

    public String title() { return "Your storage plan"; }

    @Override public String descriptor() { return "overview"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        refresh();
        return sv;
    }

    /** One recommendation's totals under the current plan. */
    private static final class Rec {
        String tech; int tier; int inPlan, total; long planBytes, possibleBytes; boolean exact = true;
    }

    @Override public void refresh() {
        if (content == null || !Session.hasResults()) return;
        content.removeAllViews();
        modeToggle();
        summaryCard();

        Ui.add(content, Ui.heading(a, "Recommendations", 20), 24);
        Ui.add(content, Ui.text(a, "Lowest risk first. Tap one to review every file it flags and keep anything you want.", 14, Ui.text2, false), 4);
        final int undecided = Session.undecided(null).size();
        if (undecided > 0) {
            android.widget.Button cards = Ui.button(a, "Review " + undecided + " planned items with swipe cards", true);
            cards.setContentDescription("Review the " + undecided + " planned items one by one. Swipe left or tap Keep to keep a file, "
                    + "swipe right or tap Accept to accept the decision. Items that need your call come first.");
            cards.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new DeckScreen(a, null)); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(12);
            content.addView(cards, cl);
        }
        List<Rec> recs = recs();
        boolean shownOther = false;
        for (Rec r : recs) {
            if (r.inPlan == 0 && !shownOther) {
                shownOther = true;
                Ui.add(content, Ui.heading(a, Session.mode == Session.MODE_SAFE ? "Not included at this risk level" : "Also possible (not needed for your target)", 16), 20);
            }
            recCard(r);
        }

        pictures();
        Ui.add(content, Ui.heading(a, "What's using space", 20), 28);
        Ui.add(content, Ui.text(a, "Tap a category to browse its files, largest first, with any flags.", 14, Ui.text2, false), 4);
        breakdown();

        Ui.add(content, Ui.heading(a, "How savings were estimated", 16), 28);
        Ui.add(content, Ui.text(a, "Deletions, caches and app sizes are exact. Re-encoding is estimated:\n• Photos: " + Session.est.photoSource
                + "\n• Chat images: " + Session.est.chatSource + "\n• Screenshots: " + Session.est.screenshotSource
                + "\n• Videos: high-bitrate H.264 assumed to shrink 45% as HEVC (conservative).", 14, Ui.text2, false), 6);

        LinearLayout row = Ui.row(a);
        Button labBtn = Ui.button(a, "Algorithm Lab (debug)", false);
        labBtn.setContentDescription("Algorithm Lab: try the picture algorithms on pictures you pick, with a log for debugging");
        labBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new LabScreen(a)); } });
        LinearLayout.LayoutParams lab0 = new LinearLayout.LayoutParams(-1, -2); lab0.topMargin = dp(16);
        content.addView(labBtn, lab0);
        Button save = Ui.button(a, "Save report", true);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.saveReport(); } });
        Button share = Ui.button(a, "Share", false);
        share.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.shareSummary(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(12);
        row.addView(save, lp); row.addView(share);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(24);
        content.addView(row, rl);
    }

    /** "Your pictures": every category with its count and size, and a search over the text read in pictures. */
    private void pictures() {
        Ui.add(content, Ui.heading(a, "Your pictures", 20), 28);
        if (Session.picturesSorted == 0) {
            Ui.add(content, Ui.text(a, "Pictures were not sorted in this analysis. Turn on “Sort pictures into categories” on the start screen and tap Analyse.",
                    14, Ui.text2, false), 4);
            return;
        }
        String sub = "Sorted on your phone: QR codes, receipts, bills, tickets, screenshots, people and more. Tap one to browse, pick and approve.";
        if (Session.picturesPending > 0)
            sub += " Text in " + Session.picturesPending + (Session.picturesPending == 1 ? " picture is" : " pictures is") + " not read yet; tap Analyse again to read the rest.";
        Ui.add(content, Ui.text(a, sub, 14, Ui.text2, false), 4);

        LinearLayout search = Ui.row(a);
        final android.widget.EditText q = new android.widget.EditText(a);
        q.setHint("Search text in pictures (e.g. swiggy, pnr, invoice)");
        q.setSingleLine(true);
        q.setTextColor(Ui.text);
        q.setHintTextColor(Ui.text2);
        q.setMinHeight(dp(48));
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setContentDescription("Search the text read in your pictures");
        final Runnable go = new Runnable() {
            public void run() { String t = q.getText().toString().trim(); if (!t.isEmpty()) a.push(PicturesScreen.search(a, t)); }
        };
        q.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            public boolean onEditorAction(android.widget.TextView v, int id, android.view.KeyEvent e) { go.run(); return true; }
        });
        Button sb = Ui.button(a, "Search", false);
        sb.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go.run(); } });
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, -2, 1); ql.rightMargin = dp(8);
        search.addView(q, ql); search.addView(sb, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(8);
        content.addView(search, sl);

        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null; int col = 0;
        for (final String c : com.storageplanner.core.PhotoCategory.ALL) {
            java.util.List<com.storageplanner.core.FileItem> l = Session.pictures.get(c);
            if (l == null || l.isEmpty()) continue;
            long b = 0; for (com.storageplanner.core.FileItem f : l) b += f.size;
            if (row == null || col == 2) {
                row = Ui.row(a); col = 0;
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(8);
                grid.addView(row, rl);
            }
            LinearLayout t = new LinearLayout(a);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setBackground(Ui.round(c.equals(com.storageplanner.core.PhotoCategory.ID) ? Ui.tierBg[4] : Ui.surface, dp(12)));
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
            t.setMinimumHeight(dp(64));
            TextView name = Ui.text(a, com.storageplanner.core.PhotoCategory.label(c), 15, Ui.text, true);
            t.addView(name);
            t.addView(Ui.text(a, l.size() + " · " + com.storageplanner.core.Report.size(b), 13, Ui.text2, false));
            t.setContentDescription(com.storageplanner.core.PhotoCategory.label(c) + ", " + l.size() + " pictures, " + com.storageplanner.core.Report.size(b) + ". Double tap to browse.");
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(PicturesScreen.category(a, c)); } });
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1);
            if (col == 0) tl.rightMargin = dp(8);
            row.addView(t, tl);
            col++;
        }
        if (row != null && col == 1) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
    }

    private void modeToggle() {
        LinearLayout seg = Ui.row(a);
        final String[] labels = {"Reach my target", "All safe steps"};
        final String[] spoken = {"Plan mode: minimum steps to reach the target", "Plan mode: every step that loses nothing visible"};
        for (int i = 0; i < 2; i++) {
            final int m = i;
            boolean sel = Session.mode == m;
            Button b = Ui.button(a, labels[i], sel);
            b.setContentDescription(spoken[i] + (sel ? ", selected" : ""));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (Session.mode != m) { Session.mode = m; Session.replan(); refresh(); ResultFiles.saveSessionAsync(a); } }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (i == 0) lp.rightMargin = dp(8);
            seg.addView(b, lp);
        }
        content.addView(seg, new LinearLayout.LayoutParams(-1, -2));
    }

    private void summaryCard() {
        long used = Session.used(), saved = Session.plan.saved(), after = used - saved, target = Session.targetUsed;
        LinearLayout c = Ui.card(a);
        String head = Session.plan.reachesTarget ? "After this plan: " + Report.gb(after) : "Target not reachable: best is " + Report.gb(after);
        Ui.add(c, Ui.heading(a, head, 22), 0);
        Ui.add(c, Ui.text(a, "Now " + Report.gb(used) + " used · target " + Report.gb(target) + " · capacity " + Report.gb(Session.result.total), 14, Ui.text2, false), 4);
        MeterView m = new MeterView(a);
        m.set(Session.result.total, used, saved, target);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = dp(12);
        c.addView(m, ml);
        LinearLayout legend = Ui.row(a);
        legend.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);  // the bar already speaks
        legend.addView(swatch(Ui.meterUsed, "Still used"));
        legend.addView(swatch(Ui.accent, "Freed"));
        legend.addView(swatch(Ui.surface2, "Free"));
        legend.addView(swatch(Ui.text, "Target"));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = dp(6);
        c.addView(legend, ll);

        Ui.add(c, Ui.text(a, "Frees " + Report.gb(saved) + ": " + Report.gb(Session.plan.savedExact()) + " exact (deletions, caches, apps) and "
                + Report.gb(saved - Session.plan.savedExact()) + " estimated (re-encoding).", 15, Ui.text, false), 12);
        int conf = Session.plan.confirmations();
        if (conf > 0) {
            TextView t = Ui.add(c, Ui.text(a, conf + " items would delete something unique. They are marked “" + Ui.TIER_WORD[4]
                    + "”: review them before acting.", 15, Ui.tier[4], true), 8);
            t.setContentDescription("Warning. " + t.getText());
        } else {
            Ui.add(c, Ui.text(a, "Nothing unique needs to be deleted.", 15, Ui.tier[0], true), 8);
        }
        int acc = Session.acceptedInResults();
        if (acc > 0) {
            TextView ap = Ui.add(c, Ui.text(a, "You approved " + acc + (acc == 1 ? " item" : " items") + ". See them \u203A", 15, Ui.tier[0], true), 8);
            ap.setMinHeight(dp(48)); ap.setGravity(Gravity.CENTER_VERTICAL);
            ap.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(ListScreen.accepted(a)); } });
        }
        int kept = Session.keptInResults();
        if (kept > 0) {
            TextView k = Ui.add(c, Ui.text(a, "You chose to keep " + kept + (kept == 1 ? " item" : " items") + ", which the plan leaves alone. Review ›", 15, Ui.accent, true), 8);
            k.setMinHeight(dp(48)); k.setGravity(Gravity.CENTER_VERTICAL);
            k.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(ListScreen.kept(a)); } });
        }
        if (!Session.appStats)
            Ui.add(c, Ui.text(a, "Usage access is off, so app caches and unused apps are not included.", 14, Ui.text2, false), 8);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(12);
        content.addView(c, cl);
    }

    private TextView swatch(int color, String label) {
        TextView t = Ui.text(a, label, 13, Ui.text2, false);
        GradientDrawable g = Ui.round(color, dp(2));
        g.setSize(dp(12), dp(12));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(g, null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        t.setPadding(0, 0, dp(14), 0);
        return t;
    }

    private List<Rec> recs() {
        List<Rec> out = new ArrayList<Rec>();
        for (Map.Entry<String, List<Unit>> e : Session.candidates.entrySet()) {
            Rec r = new Rec(); r.tech = e.getKey(); r.tier = Review.tier(r.tech, e.getValue());
            for (Unit u : e.getValue()) {
                Option o = Review.optionFor(u, r.tech);
                if (!o.exact) r.exact = false;
                if (Session.kept.contains(u.id)) continue;
                r.total++; r.possibleBytes += o.saved;
                Option ch = Session.plan.chosen.get(u);
                if (ch != null && ch.technique.equals(r.tech)) { r.inPlan++; r.planBytes += o.saved; }
            }
            if (r.total > 0 || r.inPlan > 0) out.add(r);
        }
        Collections.sort(out, new Comparator<Rec>() {
            public int compare(Rec x, Rec y) {
                boolean px = x.inPlan > 0, py = y.inPlan > 0;
                if (px != py) return px ? -1 : 1;
                if (x.tier != y.tier) return x.tier - y.tier;
                return Long.compare(px ? y.planBytes : y.possibleBytes, px ? x.planBytes : x.possibleBytes);
            }
        });
        return out;
    }

    private void recCard(final Rec r) {
        LinearLayout c = Ui.card(a);
        c.setMinimumHeight(dp(48));
        LinearLayout top = Ui.row(a);
        top.addView(Ui.badge(a, r.tier));
        View spacer = new View(a);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        long bytes = r.inPlan > 0 ? r.planBytes : r.possibleBytes;
        top.addView(Ui.text(a, Report.size(bytes) + (r.exact ? "" : " est."), 17, r.inPlan > 0 ? Ui.text : Ui.text2, true));
        c.addView(top, new LinearLayout.LayoutParams(-1, -2));
        Ui.add(c, Ui.text(a, r.tech, 17, Ui.text, true), 8);
        String count = r.inPlan > 0 ? r.inPlan + " of " + r.total + (r.total == 1 ? " item" : " items") + " in the plan"
                : r.total + (r.total == 1 ? " item" : " items") + " possible";
        Ui.add(c, Ui.text(a, count, 14, Ui.text, false), 2);
        TextView what = Ui.add(c, Ui.text(a, Techniques.info(r.tech).what, 14, Ui.text2, false), 4);
        what.setMaxLines(3);
        c.setContentDescription(r.tech + ". Risk: " + Ui.TIER_SPOKEN[r.tier] + ". " + (r.inPlan > 0 ? "Frees " : "Could free ")
                + Report.size(bytes) + (r.exact ? "" : ", estimated") + ". " + count + ". " + what.getText() + " Double tap to review.");
        c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(ListScreen.technique(a, r.tech)); } });
        c.setClickable(true); c.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        content.addView(c, lp);
    }

    private void breakdown() {
        final Map<String, Long> bd = Session.breakdown;
        List<String> cats = new ArrayList<String>(bd.keySet());
        Collections.sort(cats, new Comparator<String>() { public int compare(String x, String y) { return Long.compare(bd.get(y), bd.get(x)); } });
        long max = 1; for (Long v : bd.values()) max = Math.max(max, v);
        LinearLayout c = Ui.card(a);
        c.setPadding(dp(16), dp(6), dp(16), dp(10));
        for (final String cat : cats) {
            long v = bd.get(cat);
            if (v <= 0) continue;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(10), 0, dp(6));
            item.setMinimumHeight(dp(48));
            LinearLayout line = Ui.row(a);
            boolean browsable = !cat.equals(Categories.SYSTEM);
            line.addView(Ui.text(a, cat + (browsable ? "  ›" : ""), 15, Ui.text, false), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(Ui.text(a, Report.size(v), 15, Ui.text, true));
            item.addView(line);
            LinearLayout bar = Ui.row(a);
            View fill = new View(a); fill.setBackground(Ui.round(Ui.accent, dp(3)));
            View rest = new View(a);
            float f = Math.max(0.01f, (float) v / max);
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(6), f));
            bar.addView(rest, new LinearLayout.LayoutParams(0, dp(6), 1 - f));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(6);
            item.addView(bar, bl);
            item.setContentDescription(cat + ", " + Report.size(v) + (browsable ? ". Double tap to browse." : ". Not browsable: system files."));
            if (browsable) {
                item.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View vv) {
                        if (cat.equals(Categories.APPS)) a.push(ListScreen.apps(a));
                        else if (cat.equals(Categories.CACHES)) a.push(ListScreen.technique(a, Techniques.CACHE));
                        else a.push(ListScreen.category(a, cat));
                    }
                });
            }
            c.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        content.addView(c, lp);
    }
}
