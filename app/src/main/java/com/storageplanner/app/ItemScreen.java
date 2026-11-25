package com.storageplanner.app;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Piles;
import com.storageplanner.core.Report;

import java.util.Collections;

/**
 * One file of the clean-up: the picture, what happens to it and why in one sentence, buttons to keep or remove it,
 * and its similar photos right underneath. The full decision (rules, tags, options) is behind "Why this decision?".
 */
public final class ItemScreen extends Screen {
    private final FileItem f;
    private LinearLayout content;

    public ItemScreen(MainActivity a, FileItem f) { super(a); this.f = f; }

    public String title() { return f.name(); }

    @Override public String descriptor() { return "item:" + f.rel; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        sv.addView(content);
        refresh();
        return sv;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        final FinalPlan.Entry e = PlanStore.entry(a, f.rel);

        // the picture, tap to look closer
        FrameLayout pic = new FrameLayout(a);
        pic.setBackgroundColor(0xFF000000);
        int h = Math.min(dp(380), a.getResources().getDisplayMetrics().heightPixels / 2);
        if (PileScreen.picture(f) || Thumbs.canPreview(f)) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pic.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            Thumbs.load(iv, f, a.getResources().getDisplayMetrics().widthPixels);
        } else {
            TextView t = Ui.text(a, f.name(), 18, 0xFFFFFFFF, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), 0, dp(24), 0);
            pic.addView(t, new FrameLayout.LayoutParams(-1, -1));
            h = dp(160);
        }
        pic.setContentDescription("Open " + f.name() + " full screen");
        pic.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ViewerScreen(a, f)); } });
        content.addView(pic, new LinearLayout.LayoutParams(-1, h));

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), 0);
        content.addView(body);
        if (e == null) {
            Ui.add(body, Ui.text(a, "This file isn't part of your plan.", 15, Ui.text2, false), 0);
            return;
        }

        // what happens, and why in one sentence
        LinearLayout state = Ui.row(a);
        TextView p = PileScreen.pill(a, FinalPlan.DELETE.equals(e.action) ? FinalPlan.DELETE : FinalPlan.KEEP, false);
        p.setTextSize(14);
        p.setText(FinalPlan.DELETE.equals(e.action) ? "✕ Will be removed" : "✓ Stays");
        state.addView(p);
        if (FinalPlan.COMPRESS.equals(e.action)) {                  // kept, and made smaller too
            TextView sm = PileScreen.pill(a, FinalPlan.COMPRESS, false);
            sm.setTextSize(14);
            sm.setText("↘ Made smaller");
            LinearLayout.LayoutParams sml = new LinearLayout.LayoutParams(-2, -2); sml.leftMargin = dp(6);
            state.addView(sm, sml);
        }
        TextView size = Ui.text(a, "  " + Report.size(f.size) + " · " + Report.day(f.mtime), 13, Ui.text2, false);
        state.addView(size);
        body.addView(state);
        TextView why = Ui.text(a, Piles.why(e), 18, Ui.text, true);
        Ui.add(body, why, 12);
        if (e.saved() > 0) Ui.add(body, Ui.text(a, "Frees " + Report.size(e.saved()) + ".", 14, Ui.text2, false), 4);

        // the choice
        LinearLayout choice = Ui.row(a);
        choice.setContentDescription("Choose what happens to this file");
        String[] acts = FinalPlan.possible(e, FinalPlan.COMPRESS)
                ? new String[]{FinalPlan.KEEP, FinalPlan.COMPRESS, FinalPlan.DELETE} : new String[]{FinalPlan.KEEP, FinalPlan.DELETE};
        for (int i = 0; i < acts.length; i++) {
            final String act = acts[i];
            boolean on = act.equals(e.action);
            Button b = Ui.button(a, (on ? "✓ " : "") + PileScreen.word(act), false);
            b.setTextColor(on ? 0xFFFFFFFF : Ui.text);
            b.setBackground(on ? Ui.round(PileScreen.solid(act), dp(14)) : Ui.outlineDrawable(a, Ui.text2, 1.5f));
            b.setContentDescription(PileScreen.word(act) + (on ? ", selected" : ""));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (act.equals(e.action)) return;
                    PileScreen.choose(a, Collections.singletonList(e), act);
                    refresh();
                }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(52), 1);
            if (i < acts.length - 1) bl.rightMargin = dp(8);
            choice.addView(b, bl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(16);
        body.addView(choice, cl);

        // similar photos
        final Group g = Group.of(f);
        if (g != null) {
            LinearLayout head = Ui.row(a);
            head.addView(Ui.heading(a, "Similar photos · " + g.members.size(), 16), new LinearLayout.LayoutParams(0, -2, 1));
            TextView all = Ui.text(a, "Compare all", 14, Ui.accent, true);
            all.setMinHeight(dp(44));
            all.setGravity(Gravity.CENTER_VERTICAL);
            all.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new SimilarScreen(a, f)); } });
            head.addView(all);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2); hl.topMargin = dp(22);
            body.addView(head, hl);
            Ui.add(body, Ui.text(a, g.relation(), 13, Ui.text2, false), 0);
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = Ui.row(a);
            hs.addView(strip);
            int c = dp(108);
            for (final FileItem m : g.members) {
                FinalPlan.Entry me = PlanStore.entry(a, m.rel);
                String act = me != null ? me.action : FinalPlan.KEEP;
                FrameLayout box = new FrameLayout(a);
                box.setBackground(Ui.round(Ui.surface2, dp(12)));
                box.setClipToOutline(true);
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                Thumbs.load(iv, m, c);
                if (m == f) {
                    View ring = new View(a);
                    ring.setBackground(Ui.outlineDrawable(a, Ui.text, 3));
                    box.addView(ring, new FrameLayout.LayoutParams(-1, -1));
                }
                View mp = PileScreen.badges(a, act, m == g.best);
                if (m == f) {
                    TextView me1 = Ui.text(a, "This one", 11, 0xFFFFFFFF, true);
                    me1.setBackground(Ui.round(0xCC000000, dp(10)));
                    me1.setPadding(dp(7), dp(2), dp(7), dp(2));
                    FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); tl.setMargins(dp(6), dp(6), 0, 0);
                    box.addView(me1, tl);
                }
                FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); pl.setMargins(dp(6), 0, dp(6), dp(6));
                box.addView(mp, pl);
                box.setContentDescription((m == f ? "This photo" : m == g.best ? "Best photo" : "Similar photo") + ", " + PileScreen.spoken(act)
                        + (m == f ? "" : ". Double tap to open it."));
                if (m != f) box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ItemScreen(a, m)); } });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(c, c); bl.rightMargin = dp(8);
                strip.addView(box, bl);
            }
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(8);
            body.addView(hs, sl);
            if (g.members.size() > 1) {
                Button best = Ui.button(a, "Keep only the best one", false);
                best.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        java.util.List<FinalPlan.Entry> keep = new java.util.ArrayList<FinalPlan.Entry>(), del = new java.util.ArrayList<FinalPlan.Entry>();
                        for (FileItem m : g.members) {
                            FinalPlan.Entry me = PlanStore.entry(a, m.rel);
                            if (me != null) (m == g.best ? keep : del).add(me);
                        }
                        PileScreen.choose(a, keep, FinalPlan.KEEP);
                        PileScreen.choose(a, del, FinalPlan.DELETE);
                        refresh();
                    }
                });
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, dp(48)); kl.topMargin = dp(10);
                body.addView(best, kl);
            }
        }

        // the details, one tap away
        LinearLayout why2 = Ui.row(a);
        why2.setBackground(Ui.round(Ui.surface, dp(14)));
        why2.setPadding(dp(14), 0, dp(14), 0);
        why2.setMinimumHeight(dp(56));
        TextView ic = Ui.text(a, "ⓘ", 18, Ui.text2, false);
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        why2.addView(ic);
        TextView wt = Ui.text(a, "Why this decision?", 15, Ui.text, true);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1); wl.leftMargin = dp(10);
        why2.addView(wt, wl);
        why2.addView(Ui.text(a, "›", 24, Ui.text2, false));
        why2.setClickable(true); why2.setFocusable(true);
        why2.setContentDescription("Why this decision? Shows what the app checked and which rules matched.");
        why2.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new WhyScreen(a, f)); } });
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(-1, -2); w2.topMargin = dp(24);
        body.addView(why2, w2);
    }
}
