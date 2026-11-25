package com.storageplanner.app;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Piles;
import com.storageplanner.core.Report;

import java.util.List;

/**
 * The last step: what the clean-up does, in three lines, that removed files go to the Recycle bin for 30 days, and one
 * button. Nothing is removed before this screen.
 */
public final class ConfirmScreen extends Screen {
    private LinearLayout content;

    public ConfirmScreen(MainActivity a) { super(a); }

    public String title() { return "Clean up"; }

    @Override public String descriptor() { return "confirm"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(28));
        sv.addView(content);
        refresh();
        return sv;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        List<FinalPlan.Entry> all = PlanStore.entries(a);
        FinalPlan.Totals t = FinalPlan.totals(all, null);
        final List<FileItem> remove = PlanStore.files(a, FinalPlan.DELETE);
        long[] apps = CleanupScreen.unusedApps();
        long total = t.freed() + apps[1];
        Ui.add(content, Ui.heading(a, "Ready to free up " + Report.size(total), 26), 4);

        LinearLayout box = Ui.card(a);
        box.setBackground(Ui.round(Ui.surface, dp(18)));
        int lines = 0;
        if (t.count(FinalPlan.DELETE) > 0) {
            line(box, "✕", Ui.REMOVE, Piles.count(t.count(FinalPlan.DELETE), "file"),
                    "go to the Recycle bin", Report.size(t.saved(FinalPlan.DELETE)), lines++ > 0);
        }
        if (apps[0] > 0) {
            View r = line(box, "▦", Ui.REMOVE, Piles.count((int) apps[0], "app"), "you uninstall them one by one from the Apps list", Report.size(apps[1]), lines++ > 0);
            r.setClickable(true);
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(ListScreen.apps(a)); } });
        }
        if (t.count(FinalPlan.COMPRESS) > 0)
            line(box, "↘", Ui.SMALLER, Piles.count(t.count(FinalPlan.COMPRESS), "file") + " to make smaller",
                    "not available yet: they stay as they are for now", "about " + Report.size(t.saved(FinalPlan.COMPRESS)), lines++ > 0);
        if (lines == 0) Ui.add(box, Ui.text(a, "Nothing is set to be removed.", 15, Ui.text, false), 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(18);
        content.addView(box, bl);

        boolean bin = TrashAction.supported();
        TextView note = Ui.text(a, bin ? "↺  Photos and files go to the Recycle bin first. You can bring them back for 30 days. Android asks you to confirm."
                : TrashAction.why(), 14, Ui.keepInk, false);
        note.setBackground(Ui.round(Ui.keepTint, dp(16)));
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        Ui.add(content, note, 16);

        Button go = Ui.button(a, bin ? "Clean up now" : "Finish my plan", true);
        go.setTextSize(17);
        go.setTextColor(0xFFFFFFFF);
        go.setBackground(Ui.round(remove.isEmpty() ? Ui.text2 : Ui.REMOVE, dp(28)));
        go.setEnabled(!remove.isEmpty() || !bin);
        final boolean canBin = bin;
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PlanStore.setFinalized(a, true);
                if (canBin && !remove.isEmpty()) TrashAction.start(a, remove);
                else { a.toast("Your plan is saved. Nothing has been removed."); a.onBackPressed(); }
            }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(56)); gl.topMargin = dp(24);
        content.addView(go, gl);
        TextView back = Ui.text(a, "Look again first", 15, Ui.accent, true);
        back.setGravity(Gravity.CENTER);
        back.setMinHeight(dp(48));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.onBackPressed(); } });
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, -2); kl.topMargin = dp(6);
        content.addView(back, kl);
    }

    private View line(LinearLayout box, String sym, int color, String what, String how, String size, boolean divider) {
        if (divider) box.addView(Ui.divider(a));
        LinearLayout r = Ui.row(a);
        r.setPadding(0, dp(10), 0, dp(10));
        r.setMinimumHeight(dp(60));
        TextView ic = Ui.text(a, sym, 16, 0xFFFFFFFF, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(color, dp(18)));
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.text(a, what, 15, Ui.text, true));
        mid.addView(Ui.text(a, how, 13, Ui.text2, false));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1); ml.leftMargin = dp(12); ml.rightMargin = dp(8);
        r.addView(mid, ml);
        r.addView(Ui.text(a, size, 15, Ui.text, true));
        r.setContentDescription(what + ", " + how + ", " + size);
        box.addView(r);
        return r;
    }
}
