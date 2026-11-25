package com.storageplanner.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.Explain;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Option;
import com.storageplanner.core.Piles;
import com.storageplanner.core.Report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Why this decision?": the one place for the reasoning. The short answer first, then your choices with what each
 * frees, what the app found in the file, and (folded away) the rules that matched and the raw details.
 */
public final class WhyScreen extends Screen {
    private final FileItem f;
    private LinearLayout content;
    private boolean advanced;

    public WhyScreen(MainActivity a, FileItem f) { super(a); this.f = f; }

    public String title() { return "Why this decision?"; }

    @Override public String descriptor() { return "why:" + f.rel; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(28));
        sv.addView(content);
        refresh();
        return sv;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        FinalPlan.Entry e = PlanStore.entry(a, f.rel);
        if (e == null) { Ui.add(content, Ui.text(a, "This file isn't part of your plan.", 15, Ui.text2, false), 0); return; }
        List<Explain.Section> ex;
        try { ex = Explain.of(e, Session.tags(), RulesStore.get(a), Session.result.now); }
        catch (Throwable t) { ex = new ArrayList<Explain.Section>(); }

        // in short
        LinearLayout s = card("In short");
        Ui.add(s, Ui.text(a, Piles.why(e), 16, Ui.text, false), 6);
        Group g = Group.of(f);
        if (g != null && g.best != f) {
            List<String> wb = g.whyBest(f);
            if (!wb.isEmpty()) {
                StringBuilder b = new StringBuilder("The photo that stays is better: ");
                for (int i = 0; i < wb.size() && i < 4; i++) b.append(i == 0 ? "" : ", ").append(wb.get(i).toLowerCase(Locale.ROOT));
                Ui.add(s, Ui.text(a, b + ".", 14, Ui.text2, false), 8);
            }
        }

        // your choices
        LinearLayout c = card("Your choices");
        choiceLine(c, FinalPlan.KEEP, "frees nothing", e);
        if (e.unit != null)
            for (Option o : e.unit.options) {
                String act = "delete".equals(o.action) ? FinalPlan.DELETE : FinalPlan.COMPRESS;
                choiceLine(c, act, (o.exact ? "frees " : "frees about ") + Report.size(o.saved), e);
            }
        if (!FinalPlan.KEEP.equals(e.suggestedAction))
            Ui.add(c, Ui.text(a, "Suggested: " + PileScreen.word(e.suggestedAction) + (e.chosen && !e.action.equals(e.suggestedAction) ? ". You changed it." : "."), 13, Ui.text2, false), 8);

        // what the app found
        for (Explain.Section x : ex)
            if (x.title.equals("What the app found") && !x.lines.isEmpty()) {
                LinearLayout w = card("What the app found");
                for (String[] ln : x.lines) line(w, ln[0], ln[1]);
            }

        // the rules, folded away
        final TextView toggle = Ui.text(a, (advanced ? "▾ " : "▸ ") + "Rules and details (advanced)", 15, Ui.accent, true);
        toggle.setMinHeight(dp(48));
        toggle.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toggle.setContentDescription(advanced ? "Hide the rules and details" : "Show the rules and details");
        toggle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { advanced = !advanced; refresh(); } });
        Ui.add(content, toggle, 14);
        if (advanced)
            for (Explain.Section x : ex) {
                if (x.title.equals("What the app found")) continue;
                LinearLayout w = card(x.title);
                for (String[] ln : x.lines) line(w, ln[0], ln[1]);
            }
        Ui.add(content, Ui.text(a, "You can change what the app suggests in Settings › Cleaning rules.", 13, Ui.text2, false), 12);
    }

    private LinearLayout card(String title) {
        LinearLayout c = Ui.card(a);
        c.setBackground(Ui.round(Ui.surface, dp(16)));
        TextView h = Ui.heading(a, title.toUpperCase(Locale.ROOT), 13);
        h.setTextColor(Ui.text2);
        h.setLetterSpacing(0.06f);
        c.addView(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        content.addView(c, lp);
        return c;
    }

    private void choiceLine(LinearLayout c, String act, String frees, FinalPlan.Entry e) {
        LinearLayout r = Ui.row(a);
        r.setPadding(0, dp(6), 0, dp(6));
        boolean now = act.equals(e.action);
        String w = PileScreen.word(act) + (now ? " · your plan" : act.equals(e.suggestedAction) ? " · suggested" : "");
        TextView l = Ui.text(a, w, 15, now ? (FinalPlan.DELETE.equals(act) ? Ui.removeInk : FinalPlan.COMPRESS.equals(act) ? Ui.smallerInk : Ui.keepInk) : Ui.text, now);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(Ui.text(a, frees, 14, Ui.text2, false));
        r.setContentDescription(w + ", " + frees);
        c.addView(r);
    }

    private void line(LinearLayout c, String k, String v) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        r.addView(Ui.text(a, k, 13, Ui.text2, false));
        r.addView(Ui.text(a, v, 15, Ui.text, false));
        r.setContentDescription(k + ": " + v);
        c.addView(r);
    }
}
