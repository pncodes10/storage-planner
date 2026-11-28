package com.storageplanner.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Report;

import java.util.List;
import java.util.Locale;

/**
 * Your plan: what the analysis suggests for every file, by file type, with how much space it frees. Tap a type to see
 * its files by action (delete, compress, keep) and change any of them. Finalize the plan, then move the files to delete
 * to the phone's Recycle bin (Android 11+), after your confirmation and Android's.
 */
public final class PlanScreen extends Screen {
    private LinearLayout content;

    public PlanScreen(MainActivity a) { super(a); }

    public String title() { return "Your plan"; }

    @Override public String descriptor() { return "plan"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        refresh();
        return sv;
    }

    // ---- colours and words shared by the plan screens
    static final int DELETE_COLOR = 0xFFE5484D, KEEP_COLOR = 0xFF2E9E5B;

    static int color(String action) {
        return FinalPlan.DELETE.equals(action) ? DELETE_COLOR : FinalPlan.COMPRESS.equals(action) ? Ui.accent : KEEP_COLOR;
    }

    static String icon(String type) {
        switch (type) {
            case "image": return "🖼";
            case "video": return "🎬";
            case "audio": return "🎵";
            case "document": return "📄";
            case "apk": return "📦";
            default: return "🗂";
        }
    }

    static String count(int n, String one) { return String.format(Locale.US, "%,d", n) + " " + (n == 1 ? one : one + "s"); }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        if (!Session.hasResults()) {
            Ui.add(content, Ui.text(a, "No analysis yet. Go back and tap Analyse my storage.", 15, Ui.text2, false), 8);
            return;
        }
        List<FinalPlan.Entry> all = PlanStore.entries(a);
        FinalPlan.Totals t = FinalPlan.totals(all, null);
        long fin = PlanStore.finalized(a);
        hero(t, fin);

        Ui.add(content, Ui.heading(a, "By file type", 19), 26);
        Ui.add(content, Ui.text(a, "Tap a type to see its files by action. Untick anything you want to keep.", 14, Ui.text2, false), 4);
        for (final String type : FinalPlan.TYPES) {
            FinalPlan.Totals tt = FinalPlan.totals(all, type);
            if (tt.files == 0) continue;
            typeRow(type, tt);
        }
        if (Session.appStats && !Session.result.apps.isEmpty()) {
            LinearLayout c = row("📱", "Installed apps", Session.result.apps.size() + " apps · unused apps and caches", null);
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(ListScreen.apps(a)); } });
        }

        // the finish line
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(28);
        content.addView(box, bl);
        final int del = t.count(FinalPlan.DELETE);
        if (fin == 0) {
            Button finalize = Ui.button(a, "Finalize plan", true);
            finalize.setContentDescription("Finalize the plan: " + count(del, "file") + " to delete, " + count(t.count(FinalPlan.COMPRESS), "file") + " to compress");
            finalize.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmFinalize(); } });
            box.addView(finalize, new LinearLayout.LayoutParams(-1, dp(54)));
            Ui.add(box, Ui.text(a, "Finalizing locks your choices. Nothing is deleted until you confirm the next step.", 13, Ui.text2, false), 8);
        } else {
            Button trash = Ui.button(a, del == 0 ? "Nothing to delete" : "Move " + count(del, "file") + " to Recycle bin", true);
            trash.setBackground(Ui.round(del == 0 ? Ui.surface2 : DELETE_COLOR, dp(24)));
            trash.setTextColor(0xFFFFFFFF);
            trash.setEnabled(del > 0);
            trash.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmTrash(); } });
            box.addView(trash, new LinearLayout.LayoutParams(-1, dp(54)));
            Button edit = Ui.button(a, "Edit plan", false);
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { PlanStore.setFinalized(a, false); refresh(); a.toast("The plan is a draft again: change anything you like."); }
            });
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2); el.topMargin = dp(10);
            box.addView(edit, el);
            Ui.add(box, Ui.text(a, TrashAction.supported()
                    ? "Files go to your phone's Recycle bin and stay there for 30 days, so you can restore them. Android asks you to confirm first."
                    : TrashAction.why(), 13, Ui.text2, false), 8);
        }
        TextView reset = Ui.text(a, "Start over (back to the suggestions)", 14, Ui.accent, true);
        reset.setMinHeight(dp(48));
        reset.setGravity(Gravity.CENTER);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("Start over?").setMessage("Every file goes back to what the analysis suggests, and the plan becomes a draft.")
                        .setPositiveButton("Start over", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { PlanStore.reset(a); refresh(); }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(12);
        content.addView(reset, rl);
    }

    /** The big card: what the plan frees, the three actions as a bar, and its state. */
    private void hero(FinalPlan.Totals t, long fin) {
        LinearLayout c = Ui.card(a);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        TextView state = Ui.text(a, fin == 0 ? "DRAFT · review and finalize" : "✓ FINALIZED · " + Ui.date(fin), 12, fin == 0 ? Ui.text2 : KEEP_COLOR, true);
        state.setLetterSpacing(0.06f);
        c.addView(state);
        Ui.add(c, Ui.text(a, "You can free up", 15, Ui.text2, false), 10);
        TextView big = Ui.text(a, Report.size(t.freed()), 40, Ui.text, true);
        c.addView(big);
        Ui.add(c, Ui.text(a, "of " + Report.size(t.size) + " in " + count(t.files, "file") + ". Compressing is an estimate.", 13, Ui.text2, false), 2);

        LinearLayout bar = Ui.row(a);
        long sum = Math.max(1, t.bytes(FinalPlan.DELETE) + t.bytes(FinalPlan.COMPRESS) + t.bytes(FinalPlan.KEEP));
        for (String act : FinalPlan.ACTIONS) {
            long b = t.bytes(act);
            if (b <= 0) continue;
            View seg = new View(a);
            seg.setBackground(Ui.round(color(act), dp(4)));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(10), Math.max(0.02f, b / (float) sum)); sl.rightMargin = dp(3);
            bar.addView(seg, sl);
        }
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-1, -2); brl.topMargin = dp(16);
        c.addView(bar, brl);
        LinearLayout legend = Ui.row(a);
        for (String act : FinalPlan.ACTIONS) legend.addView(chip(act, t.count(act), act.equals(FinalPlan.KEEP) ? t.bytes(act) : t.saved(act)), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = dp(12);
        c.addView(legend, ll);
        c.setContentDescription("You can free up " + Report.size(t.freed()) + ". " + count(t.count(FinalPlan.DELETE), "file") + " to delete, "
                + count(t.count(FinalPlan.COMPRESS), "file") + " to compress, " + count(t.count(FinalPlan.KEEP), "file") + " to keep. "
                + (fin == 0 ? "The plan is a draft." : "The plan is finalized."));
        content.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    /** "● Delete / 1,203 · 4.1 GB". */
    private LinearLayout chip(String action, int n, long bytes) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(a, FinalPlan.actionLabel(action), 13, Ui.text2, false);
        GradientDrawable dot = Ui.round(color(action), dp(5));
        dot.setSize(dp(10), dp(10));
        head.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        head.setCompoundDrawablePadding(dp(6));
        l.addView(head);
        l.addView(Ui.text(a, String.format(Locale.US, "%,d", n), 17, Ui.text, true));
        l.addView(Ui.text(a, Report.size(bytes), 12, Ui.text2, false));
        return l;
    }

    private void typeRow(final String type, FinalPlan.Totals tt) {
        String sub = count(tt.files, "file") + " · " + Report.size(tt.size);
        LinearLayout c = row(icon(type), FinalPlan.typeLabel(type), sub, tt);
        c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new PlanTypeScreen(a, type, null)); } });
    }

    /** A tappable card: icon, title, subtitle, and (for a type) the three action counts. */
    private LinearLayout row(String icon, String title, String sub, FinalPlan.Totals tt) {
        LinearLayout c = Ui.card(a);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout top = Ui.row(a);
        TextView ic = Ui.text(a, icon, 22, Ui.text, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(Ui.surface2, dp(12)));
        top.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.text(a, title, 16, Ui.text, true));
        mid.addView(Ui.text(a, sub, 13, Ui.text2, false));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1); ml.leftMargin = dp(14);
        top.addView(mid, ml);
        top.addView(Ui.text(a, "›", 24, Ui.text2, false));
        c.addView(top);
        StringBuilder spoken = new StringBuilder(title + ", " + sub);
        if (tt != null) {
            LinearLayout chips = Ui.row(a);
            for (String act : FinalPlan.ACTIONS) {
                int n = tt.count(act);
                if (n == 0) continue;
                TextView p = Ui.text(a, FinalPlan.actionLabel(act) + " " + String.format(Locale.US, "%,d", n), 12, color(act), true);
                GradientDrawable bg = Ui.round(0x00000000, dp(12));
                bg.setStroke(dp(1), color(act));
                p.setBackground(bg);
                p.setPadding(dp(10), dp(3), dp(10), dp(3));
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2); pl.rightMargin = dp(8);
                chips.addView(p, pl);
                spoken.append(", ").append(n).append(" to ").append(FinalPlan.actionLabel(act).toLowerCase(Locale.ROOT));
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(10); cl.leftMargin = dp(60);
            c.addView(chips, cl);
        }
        c.setContentDescription(spoken + ". Double tap to open.");
        c.setClickable(true); c.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        content.addView(c, lp);
        return c;
    }

    private void confirmFinalize() {
        FinalPlan.Totals t = FinalPlan.totals(PlanStore.entries(a), null);
        String msg = count(t.count(FinalPlan.DELETE), "file") + " to delete (" + Report.size(t.saved(FinalPlan.DELETE)) + ")\n"
                + count(t.count(FinalPlan.COMPRESS), "file") + " to compress (about " + Report.size(t.saved(FinalPlan.COMPRESS)) + ")\n"
                + count(t.count(FinalPlan.KEEP), "file") + " to keep\n\nYou can still edit the plan afterwards.";
        new AlertDialog.Builder(a).setTitle("Finalize your plan?").setMessage(msg)
                .setPositiveButton("Finalize", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { PlanStore.setFinalized(a, true); refresh(); a.toast("Plan finalized."); }
                }).setNegativeButton("Not yet", null).show();
    }

    private void confirmTrash() {
        // only reviewed files (scrolled past on their pile's page in Clean up) are moved to the bin
        final List<FileItem> files = new java.util.ArrayList<FileItem>();
        for (FinalPlan.Entry e : ReviewStore.ready(a)) if (FinalPlan.DELETE.equals(e.action)) files.add(e.file);
        int waiting = PlanStore.files(a, FinalPlan.DELETE).size() - files.size();
        if (files.isEmpty()) { a.toast("Review the piles in Clean up first: only files you have scrolled past are removed."); return; }
        if (!TrashAction.supported()) { a.toast(TrashAction.why()); return; }
        long b = 0; for (FileItem f : files) b += f.size;
        new AlertDialog.Builder(a).setTitle("Move to Recycle bin?")
                .setMessage("Move " + count(files.size(), "file") + " (" + Report.size(b) + ") to your phone's Recycle bin?\n\n"
                        + "They stay in the bin for 30 days and can be restored from there (Files, Google Photos or your gallery's bin). "
                        + "After 30 days Android removes them for good."
                        + (waiting > 0 ? "\n\n" + count(waiting, "file") + " not reviewed yet stay: scroll through their pile in Clean up first." : "")
                        + "\n\nAndroid will ask you to confirm next.")
                .setPositiveButton("Move to bin", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { TrashAction.start(a, files); }
                }).setNegativeButton("Cancel", null).show();
    }
}
