package com.storageplanner.app;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The copies of one photo or one moment (a burst, exact duplicates, WhatsApp copies), side by side in a grid: the ★ best
 * shot first, what the photo check found under each, and a tick to delete each one in your plan. Keep best only, keep
 * all, or compare two side by side.
 */
public final class SimilarScreen extends Screen {
    private final FileItem focus;
    private Group g;
    private LinearLayout content;

    public SimilarScreen(MainActivity a, FileItem focus) { super(a); this.focus = focus; }

    public String title() { return "Similar photos"; }

    @Override public String descriptor() { return "similar:" + focus.rel; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(24));
        sv.addView(content);
        refresh();
        return sv;
    }

    private boolean locked() { return PlanStore.finalized(a) != 0; }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        g = Group.of(focus);
        if (g == null) { Ui.add(content, Ui.text(a, "This photo has no similar copies.", 15, Ui.text2, false), 8); return; }
        Ui.add(content, Ui.text(a, g.relation(), 20, Ui.text, true), 0);
        int del = 0; long bytes = 0;
        for (FileItem m : g.members) { FinalPlan.Entry e = PlanStore.entry(a, m.rel); if (e != null && FinalPlan.DELETE.equals(e.action)) { del++; bytes += m.size; } }
        Ui.add(content, Ui.text(a, "★ marks the best shot, which the suggestion keeps. " + del + " of " + g.members.size() + " ticked to delete · frees "
                + Report.size(bytes) + ". Tap a photo to tick or untick it, long-press to look at it.", 13, Ui.text2, false), 4);

        LinearLayout actions = Ui.row(a);
        Button best = Ui.button(a, "Keep best only", true);
        best.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { keepOnly(Collections.singleton(g.best.rel)); } });
        Button all = Ui.button(a, "Keep all", false);
        all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { Set<String> s = new HashSet<String>(); for (FileItem m : g.members) s.add(m.rel); keepOnly(s); }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1); b1.rightMargin = dp(8);
        actions.addView(best, b1);
        actions.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(10);
        content.addView(actions, al);
        Button cmp = Ui.button(a, "Compare two side by side", false);
        cmp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { compare(a, g, focus); } });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(8);
        content.addView(cmp, cl);
        if (locked()) Ui.add(content, Ui.text(a, "✓ The plan is final. Tap Edit plan on Your plan to make changes.", 13, Ui.text, false), 8);

        // two columns of large photos
        int w = (a.getResources().getDisplayMetrics().widthPixels - dp(24) - dp(8)) / 2;
        LinearLayout row = null;
        for (int i = 0; i < g.members.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(a);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(10);
                content.addView(row, rl);
            }
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(w, -2);
            if (i % 2 == 0) tl.rightMargin = dp(8);
            row.addView(tile(g.members.get(i), w), tl);
        }
    }

    private View tile(final FileItem m, int w) {
        final FinalPlan.Entry e = PlanStore.entry(a, m.rel);
        boolean del = e != null && FinalPlan.DELETE.equals(e.action);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(0xFF1C1C22, dp(10)));
        box.setClipToOutline(true);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        Thumbs.load(iv, m, w);
        Tick t = new Tick(a);
        t.style(PlanScreen.DELETE_COLOR, "ticked, will be deleted", "not ticked, will be kept");
        t.setChecked(del);
        box.addView(t, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END));
        TextView badge = Ui.text(a, (m == g.best ? "★ " : "") + (del ? "DELETE" : "KEEP"), 12, 0xFFFFFFFF, true);
        badge.setBackground(Ui.round(del ? PlanScreen.DELETE_COLOR : PlanScreen.KEEP_COLOR, dp(6)));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); bl.setMargins(dp(6), 0, 0, dp(6));
        box.addView(badge, bl);
        col.addView(box, new LinearLayout.LayoutParams(w, w * 4 / 3));
        TextView note = Ui.text(a, g.note(m), 13, Ui.text, true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2); nl.topMargin = dp(4);
        col.addView(note, nl);
        col.addView(Ui.text(a, Group.facts(m), 12, Ui.text2, false));
        if (m != g.best) {
            List<String> why = g.whyBest(m);
            if (!why.isEmpty()) {
                StringBuilder b = new StringBuilder("★ is better: ");
                for (int k = 0; k < why.size() && k < 3; k++) b.append(k == 0 ? "" : ", ").append(why.get(k));
                TextView wb = Ui.text(a, b.toString(), 12, Ui.text2, false);
                col.addView(wb);
            }
        }
        box.setContentDescription((m == g.best ? "Best shot. " : "") + g.note(m) + ", " + Group.facts(m) + (del ? ", will be deleted" : ", will be kept")
                + ". Double tap to " + (del ? "keep" : "delete") + " it, long press to look at it.");
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (locked()) { a.toast("The plan is final. Tap Edit plan on Your plan to change it."); return; }
                if (e == null) return;
                PlanStore.set(a, Collections.singletonList(e), FinalPlan.DELETE.equals(e.action) ? FinalPlan.KEEP : FinalPlan.DELETE);
                refresh();
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { a.push(new ViewerScreen(a, m)); return true; } });
        return col;
    }

    /** Keeps these members and deletes the others, in your plan. */
    private void keepOnly(Set<String> keep) {
        if (locked()) { a.toast("The plan is final. Tap Edit plan on Your plan to change it."); return; }
        List<FinalPlan.Entry> k = new ArrayList<FinalPlan.Entry>(), d = new ArrayList<FinalPlan.Entry>();
        for (FileItem m : g.members) {
            FinalPlan.Entry e = PlanStore.entry(a, m.rel);
            if (e == null) continue;
            (keep.contains(m.rel) ? k : d).add(e);
        }
        PlanStore.set(a, k, FinalPlan.KEEP);
        PlanStore.set(a, d, FinalPlan.DELETE);
        refresh();
    }

    /**
     * Opens the side-by-side comparison of a group on the final plan: the ticks start from your plan, and applying writes
     * them back (ticked: delete, the rest: keep).
     */
    static void compare(final MainActivity a, final Group g, FileItem focus) {
        final Set<String> sel = new HashSet<String>();
        for (FileItem m : g.members) { FinalPlan.Entry e = PlanStore.entry(a, m.rel); if (e != null && FinalPlan.DELETE.equals(e.action)) sel.add(m.rel); }
        Runnable apply = new Runnable() {
            public void run() {
                if (PlanStore.finalized(a) != 0) { a.toast("The plan is final. Tap Edit plan on Your plan to change it."); return; }
                List<FinalPlan.Entry> k = new ArrayList<FinalPlan.Entry>(), d = new ArrayList<FinalPlan.Entry>();
                for (FileItem m : g.members) {
                    FinalPlan.Entry e = PlanStore.entry(a, m.rel);
                    if (e != null) (sel.contains(m.rel) ? d : k).add(e);
                }
                PlanStore.set(a, k, FinalPlan.KEEP);
                PlanStore.set(a, d, FinalPlan.DELETE);
                a.toast(g.summary(sel) + ", in your plan.");
                a.onBackPressed();
            }
        };
        a.push(new CompareScreen(a, g, sel, g.best, focus == g.best ? null : focus, apply));
    }
}
