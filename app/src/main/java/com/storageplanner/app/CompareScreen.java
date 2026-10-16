package com.storageplanner.app;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.PhotoQuality;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Full-screen comparison of copies of one photo or moment. Two photos at a time, side by side or stacked, each
 * with its "delete" tick (top left), a KEEP / DELETE badge that follows the tick, what the photo check found
 * (eyes, sharpness, light) and face markers. With "Zoom together", zooming one moves the other to the same spot.
 * Groups of three or more get a strip of all copies below: tap one to compare it, tick it to delete it.
 */
public final class CompareScreen extends Screen {
    private final Group g;
    private final Set<String> sel;                // shared with the swipe card that opened this screen
    private final Runnable onApply;               // null: apply records the decision here
    private FileItem left, right;
    private boolean together = true, stacked;
    private LinearLayout panes;
    private final Pane[] pane = new Pane[2];
    private final List<Thumb> strip = new ArrayList<Thumb>();
    private TextView why, warn;
    private Button apply;
    private int generation;
    private boolean loaded;

    private final class Pane { FileItem f; FrameLayout box; ZoomImageView iv; Tick tick; TextView badge, note, facts; ProgressBar pb; }
    private static final class Thumb { FileItem f; Tick tick; TextView badge; View frame; }

    public CompareScreen(MainActivity a, Group g, Set<String> sel, FileItem left, FileItem right, Runnable onApply) {
        super(a);
        this.g = g; this.sel = sel; this.onApply = onApply;
        this.left = left != null ? left : g.best;
        this.right = right != null && right != this.left ? right : g.members.get(this.left == g.members.get(0) ? 1 : 0);
        stacked = this.left.width > this.left.height && this.right.width > this.right.height;   // landscape: one above the other
    }

    public String title() { return "Compare"; }

    @Override public String descriptor() { return "compare:" + right.rel; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(10));
        root.addView(Ui.heading(a, g.relation(), 17), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout opts = Ui.row(a);
        CheckBox sync = new CheckBox(a);
        sync.setText("Zoom together");
        sync.setTextColor(Ui.text);
        sync.setTextSize(15);
        sync.setMinHeight(dp(48));
        sync.setChecked(together);
        sync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                together = on;
                if (on && pane[0] != null) pane[1].iv.applyState(pane[0].iv.state());
            }
        });
        opts.addView(sync, new LinearLayout.LayoutParams(0, -2, 1));
        final Button layout = Ui.button(a, stacked ? "Side by side" : "Stacked", false);
        layout.setContentDescription(stacked ? "Show the photos side by side" : "Show the photos one above the other");
        layout.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stacked = !stacked;
                layout.setText(stacked ? "Side by side" : "Stacked");
                layout.setContentDescription(stacked ? "Show the photos side by side" : "Show the photos one above the other");
                arrange();
            }
        });
        opts.addView(layout, new LinearLayout.LayoutParams(-2, -2));
        root.addView(opts, new LinearLayout.LayoutParams(-1, -2));

        panes = new LinearLayout(a);
        for (int i = 0; i < 2; i++) { pane[i] = pane(i); panes.addView(pane[i].box); }
        arrange();
        ZoomImageView.Listener follow = new ZoomImageView.Listener() {
            public void onViewChanged(ZoomImageView v) {
                if (!together) return;
                ZoomImageView other = v == pane[0].iv ? pane[1].iv : pane[0].iv;
                other.applyState(v.state());
            }
        };
        pane[0].iv.setListener(follow); pane[1].iv.setListener(follow);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0, 1); pl.topMargin = dp(4);
        root.addView(panes, pl);

        if (g.members.size() > 2) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = Ui.row(a);
            for (FileItem f : g.members) row.addView(thumb(f));
            hs.addView(row);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(6);
            root.addView(hs, sl);
            TextView sh = Ui.text(a, "Tap a copy to compare it on the right; long-press to put it on the left.", 12, Ui.text2, false);
            root.addView(sh, new LinearLayout.LayoutParams(-1, -2));
        }

        why = Ui.text(a, "", 14, Ui.text, false);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2); wl.topMargin = dp(6);
        root.addView(why, wl);
        warn = Ui.text(a, "", 13, Ui.night ? 0xFFF2B8B5 : 0xFFB3261E, true);
        root.addView(warn, new LinearLayout.LayoutParams(-1, -2));

        Button debug = Ui.button(a, "Debug this choice in the Algorithm Lab", false);
        debug.setContentDescription("Open all " + g.members.size() + " copies in the Algorithm Lab to see every number behind the suggestion");
        debug.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(LabScreen.with(a, g.members)); } });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2); dl.topMargin = dp(6);
        root.addView(debug, dl);
        apply = Ui.button(a, "", true);
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (onApply != null) { onApply.run(); return; }
                g.apply(a, new java.util.HashSet<String>(sel));
                a.toast(g.summary(sel) + ". Nothing is deleted from here: approved files are listed under “You approved”.");
                a.onBackPressed();
            }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(6);
        root.addView(apply, al);
        show();
        load();
        return root;
    }

    // ------------------------------------------------------------------ panes
    private Pane pane(final int side) {
        final Pane p = new Pane();
        p.box = new FrameLayout(a);
        p.box.setBackground(Ui.round(0xFF101014, dp(12)));
        p.box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        p.box.setClipToOutline(true);
        p.iv = new ZoomImageView(a);
        p.box.addView(p.iv, new FrameLayout.LayoutParams(-1, -1));
        p.pb = new ProgressBar(a);
        p.pb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        p.box.addView(p.pb, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        p.tick = new Tick(a);
        p.tick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggle(p.f); } });
        p.box.addView(p.tick, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));
        p.badge = Group.badge(a, 13);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); bl.setMargins(0, dp(10), dp(8), 0);
        p.box.addView(p.badge, bl);
        LinearLayout cap = new LinearLayout(a);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setBackgroundColor(0xCC000000);
        cap.setPadding(dp(10), dp(4), dp(10), dp(6));
        cap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        p.note = Ui.text(a, "", 13, 0xFFE6E1E5, true);
        Ui.ellipsizeMiddle(p.note);
        cap.addView(p.note);
        p.facts = Ui.text(a, "", 12, 0xFFC9C5D0, false);
        Ui.ellipsizeMiddle(p.facts);
        cap.addView(p.facts);
        p.box.addView(cap, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return p;
    }

    private void arrange() {
        panes.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            LinearLayout.LayoutParams l = stacked ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1);
            if (i == 0) { if (stacked) l.bottomMargin = dp(3); else l.rightMargin = dp(3); }
            else { if (stacked) l.topMargin = dp(3); else l.leftMargin = dp(3); }
            pane[i].box.setLayoutParams(l);
        }
    }

    private View thumb(final FileItem f) {
        final Thumb t = new Thumb();
        t.f = f;
        FrameLayout fr = new FrameLayout(a);
        fr.setBackground(Ui.round(0xFF101014, dp(8)));
        fr.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        fr.setClipToOutline(true);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        fr.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (Thumbs.canPreview(f)) Thumbs.load(iv, f, dp(96));
        t.tick = new Tick(a);
        t.tick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggle(f); } });
        fr.addView(t.tick, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));
        t.badge = Group.badge(a, 10);
        fr.addView(t.badge, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        fr.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showOn(1, f); } });
        fr.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { showOn(0, f); return true; } });
        fr.setContentDescription("Compare " + f.name());
        t.frame = fr;
        strip.add(t);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(88), dp(88)); l.rightMargin = dp(6);
        fr.setLayoutParams(l);
        return fr;
    }

    /** Puts f on one side (0 = left, 1 = right); if it is already on the other side, the two swap. */
    private void showOn(int side, FileItem f) {
        if ((side == 0 ? left : right) == f) return;
        if ((side == 0 ? right : left) == f) { FileItem t = left; left = right; right = t; }
        else if (side == 0) left = f; else right = f;
        generation++; loaded = false;
        show();
        load();
    }

    private void toggle(FileItem f) {
        if (!sel.remove(f.rel)) sel.add(f.rel);
        show();
        a.toast(f.name() + (sel.contains(f.rel) ? " will be deleted" : " will be kept"));
    }

    /** Refreshes everything that depends on which photos are shown and which are ticked. */
    private void show() {
        FileItem[] fs = {left, right};
        for (int i = 0; i < 2; i++) {
            Pane p = pane[i];
            boolean newFile = p.f != fs[i];
            p.f = fs[i];
            boolean del = sel.contains(p.f.rel);
            p.tick.setChecked(del);
            p.tick.setContentDescription("Delete " + p.f.name());
            Group.setBadge(a, p.badge, del);
            p.note.setText(g.note(p.f) + "  ·  " + p.f.name());
            p.note.setTextColor(p.f == g.best ? 0xFF9BE3AA : 0xFFE6E1E5);
            p.facts.setText(Group.facts(p.f) + "  ·  " + Group.folder(p.f));
            p.iv.setContentDescription((i == 0 ? "Left: " : "Right: ") + g.spoken(p.f, del) + " Pinch or double tap to zoom; zoom actions are available.");
            p.iv.setFaces(p.f.quality != null && p.f.quality.faces > 0 ? p.f.quality.face : null, 5);
            if (newFile) { p.iv.setImageDrawable(null); p.pb.setVisibility(View.VISIBLE); }
        }
        for (Thumb t : strip) {
            boolean del = sel.contains(t.f.rel);
            t.tick.setChecked(del);
            t.tick.setContentDescription("Delete " + t.f.name());
            Group.setBadge(a, t.badge, del);
            boolean on = t.f == left || t.f == right;
            t.frame.setForeground(on ? Ui.outlineDrawable(a, 0xFFFFFFFF, 3) : null);
        }
        FileItem other = left == g.best ? right : right == g.best ? left : right;
        StringBuilder b = new StringBuilder("★ Suggested to keep: ").append(g.best.name());
        if (other != g.best) {
            List<String> r = g.whyBest(other);
            b.append(". Compared with ").append(other.name()).append(": ");
            for (int i = 0; i < r.size(); i++) b.append(i == 0 ? "" : ", ").append(r.get(i));
        }
        why.setText(b.toString());
        int n = sel.size();
        if (n == g.members.size()) { warn.setText("All " + n + " are ticked: nothing of this photo would stay."); warn.setVisibility(View.VISIBLE); }
        else if (sel.contains(g.best.rel)) { warn.setText("You're keeping a different one than the suggested ★ best shot."); warn.setVisibility(View.VISIBLE); }
        else warn.setVisibility(View.GONE);
        apply.setText(onApply != null ? "Apply and next: " + g.summary(sel).toLowerCase(java.util.Locale.ROOT) : "Apply: " + g.summary(sel).toLowerCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------------ images
    private void load() {
        if (loaded || pane[0] == null) return;
        loaded = true;
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int px = Math.max(dm.widthPixels, dm.heightPixels);       // two decodes at once: screen size each
        for (Pane p : pane) if (p.iv.getDrawable() == null) decode(p, px, generation);
    }

    private void decode(final Pane p, final int px, final int gen) {
        final FileItem f = p.f;
        new Thread(new Runnable() {
            public void run() {
                Bitmap b = null;
                try { if (Thumbs.canPreview(f)) b = Thumbs.make(f, px); } catch (Throwable ignored) {}
                final Bitmap fb = b;
                p.box.post(new Runnable() {
                    public void run() {
                        if (gen != generation || p.f != f) return;
                        p.pb.setVisibility(View.GONE);
                        if (fb == null) return;
                        p.iv.setImageBitmap(fb);
                        p.iv.post(new Runnable() {                  // once fitted, catch up with a zoomed partner
                            public void run() {
                                if (!together || gen != generation) return;
                                ZoomImageView other = p == pane[0] ? pane[1].iv : pane[0].iv;
                                if (other.getDrawable() != null) p.iv.applyState(other.state());
                            }
                        });
                    }
                });
            }
        }, "compare-image").start();
    }

    @Override public void refresh() { if (pane[0] != null) show(); load(); }

    /** Frees the two full-size bitmaps while hidden; they are decoded again when the screen comes back. */
    @Override public void onHide() {
        generation++;
        loaded = false;
        for (Pane p : pane) if (p != null) { p.iv.setImageDrawable(null); p.pb.setVisibility(View.VISIBLE); }
    }
}
