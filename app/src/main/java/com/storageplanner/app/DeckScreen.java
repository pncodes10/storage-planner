package com.storageplanner.app;

import android.animation.ValueAnimator;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Report;
import com.storageplanner.core.Unit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swipe review: one card per planned item. Swipe left (or Keep) = keep the file, out of every plan.
 * Swipe right (or Accept) = accept the algorithm's decision; it is locked into the plan as "Approved by you".
 * Nothing is deleted. Undo reverts the last decision. Buttons and TalkBack actions do everything swipes do.
 */
public final class DeckScreen extends Screen {
    private static final int ACT_KEEP = 0x7f0f0201, ACT_ACCEPT = 0x7f0f0202, ACT_VIEW = 0x7f0f0203, ACT_TICK = 0x7f0f0210;

    private final String technique;                    // null = whole plan
    private final List<Unit> queue = new ArrayList<Unit>();
    private final Set<Unit> queued = new HashSet<Unit>();
    private final Map<Unit, String> techOf = new HashMap<Unit, String>();
    private final ArrayDeque<Decision> history = new ArrayDeque<Decision>();
    private int index, nKept, nAccepted;
    private long bytesAccepted, bytesKept;

    private TextView progress;
    private FrameLayout host;
    private Button keepBtn, undoBtn, acceptBtn;
    private View front;
    private boolean animating;
    /** ticked ("delete") files of each group card, by group key; shared with the compare screen */
    private final Map<String, Set<String>> selections = new HashMap<String, Set<String>>();
    private GroupCard frontGroup;                       // the front card's group, if it is one

    /** One swipe or button press: the earlier state of every unit it touched, for Undo. */
    private static final class Decision {
        Map<Unit, String[]> before; int index, kept, accepted; long bytesKept, bytesAccepted;
        String groupKey; Set<String> groupSel;           // a group card's ticks, so Undo shows them again
    }

    /** A group card on screen: its group, the ticked files, and the views that show the ticks. */
    private final class GroupCard {
        final Group g; final Set<String> sel; final Unit unit;
        final List<Tile> tiles = new ArrayList<Tile>();
        TextView frees, warn, accTag; View card;
        GroupCard(Group g, Set<String> sel, Unit unit) { this.g = g; this.sel = sel; this.unit = unit; }

        void toggle(FileItem m) {
            if (!sel.remove(m.rel)) sel.add(m.rel);
            update();
            boolean del = sel.contains(m.rel);
            host.announceForAccessibility(m.name() + (del ? " will be deleted. " : " will be kept. ") + g.summary(sel) + ".");
        }

        void update() {
            for (Tile t : tiles) {
                boolean del = sel.contains(t.m.rel);
                t.tick.setChecked(del);
                Group.setBadge(a, t.badge, del);
                t.tick.setContentDescription("Delete " + t.m.name());
            }
            int n = sel.size();
            frees.setText(n == 0 ? "frees nothing" : "frees " + Report.size(g.bytes(sel)));
            if (n == g.members.size()) {
                warn.setText("All " + n + " will be deleted. Nothing of this " + (g.isBurst() ? "moment" : "photo") + " stays on the phone.");
                warn.setVisibility(View.VISIBLE);
            } else if (sel.contains(g.best.rel)) {
                warn.setText("You're keeping a different one than the suggested ★ best shot.");
                warn.setVisibility(View.VISIBLE);
            } else warn.setVisibility(View.GONE);
            if (accTag != null) accTag.setText(n == 0 ? "KEEP ALL" : n == g.members.size() ? "DELETE ALL" : "DELETE " + n);
            if (this == frontGroup) {
                acceptBtn.setText(n == 0 ? "Keep all →" : n == g.members.size() ? "Delete all →" : "Delete " + n + " →");
                acceptBtn.setContentDescription(g.summary(sel) + ". Apply the ticks.");
                card.setContentDescription(spoken());
            }
        }

        String spoken() {
            StringBuilder b = new StringBuilder(g.relation()).append(". ");
            for (FileItem m : g.members) b.append(g.spoken(m, sel.contains(m.rel))).append(' ');
            if (!g.recommended().equals(sel)) b.append("You changed the suggestion. ");
            b.append(g.summary(sel)).append(". Swipe right or use Apply; swipe left or use Keep all. Actions available to tick each photo.");
            return b.toString();
        }
    }

    private static final class Tile { FileItem m; Tick tick; TextView badge; View box; }

    public DeckScreen(MainActivity a, String technique) {
        super(a);
        this.technique = technique;
        for (Unit u : Session.undecided(technique)) enqueue(u);
    }

    private void enqueue(Unit u) {
        Option o = Session.plan.chosen.get(u);
        if (o == null || queued.contains(u)) return;
        queue.add(u); queued.add(u); techOf.put(u, o.technique);
    }

    public String title() { return technique == null ? "Review your plan" : "Review: " + technique; }

    @Override public String descriptor() { return "deck:" + (technique == null ? "*" : technique); }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        progress = Ui.text(a, "", 15, Ui.text, true);
        progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(progress);
        TextView hint = Ui.text(a, "← Swipe left to keep the file  ·  swipe right to accept →", 13, Ui.text2, false);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(hint);

        host = new FrameLayout(a);
        host.setClipChildren(false); host.setClipToPadding(false);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, 0, 1); hl.topMargin = dp(12); hl.bottomMargin = dp(12);
        root.addView(host, hl);

        LinearLayout row = Ui.row(a);
        keepBtn = Ui.button(a, "← Keep", false);
        keepBtn.setContentDescription("Keep this file. Leave it out of the plan.");
        keepBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { fly(-1); } });
        undoBtn = Ui.button(a, "Undo", false);
        undoBtn.setContentDescription("Undo the last decision");
        undoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { undo(); } });
        acceptBtn = Ui.button(a, "Accept →", true);
        acceptBtn.setContentDescription("Accept the planned action for this item");
        acceptBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { fly(+1); } });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1); l1.rightMargin = dp(8);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2); l2.rightMargin = dp(8);
        row.addView(keepBtn, l1); row.addView(undoBtn, l2); row.addView(acceptBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        showCard();
        return root;
    }

    @Override public void refresh() { if (host != null && !animating) showCard(); }

    // ------------------------------------------------------------------ deck
    /** An item is still reviewable if it is in the plan and nobody decided on it meanwhile. */
    private boolean valid(Unit u) {
        return Session.plan.chosen.containsKey(u) && !Session.kept.contains(u.id) && !Session.accepted.containsKey(u.id);
    }

    private void showCard() {
        host.removeAllViews();
        front = null;
        frontGroup = null;
        while (index < queue.size() && !valid(queue.get(index))) index++;
        int left = 0;
        for (int i = index; i < queue.size(); i++) if (valid(queue.get(i))) left++;
        int done = nKept + nAccepted;
        progress.setText(index >= queue.size() ? "All reviewed" :
                (done + 1) + " of " + (done + left) + "   ·   accepted " + nAccepted + "   ·   kept " + nKept);
        undoBtn.setEnabled(!history.isEmpty());
        boolean any = index < queue.size();
        keepBtn.setEnabled(any); acceptBtn.setEnabled(any);
        if (!any) { doneView(); return; }

        int next = index + 1;
        while (next < queue.size() && !valid(queue.get(next))) next++;
        if (next < queue.size()) {
            View back = card(queue.get(next), false, 0, 0);
            back.setScaleX(0.94f); back.setScaleY(0.94f); back.setTranslationY(dp(14)); back.setAlpha(0.7f);
            host.addView(back, new FrameLayout.LayoutParams(-1, -1));
        }
        front = card(queue.get(index), true, done + 1, done + left);
        host.addView(front, new FrameLayout.LayoutParams(-1, -1));
        final View f = front;
        f.post(new Runnable() { public void run() { f.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); } });
    }

    private View card(final Unit u, boolean isFront, int pos, int total) {
        final Option o = Session.plan.chosen.get(u);
        final FileItem file = u.file;
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.surface, dp(18)));
        card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setElevation(dp(isFront ? 6 : 2));

        // ---- preview: one file, or every copy of the same photo / moment with a "delete" tick on each
        final Group grp = Group.forOption(file, o);
        GroupCard gc = null;
        if (grp != null) {
            Set<String> sel = selections.get(grp.key());
            if (sel == null) { sel = grp.current(); selections.put(grp.key(), sel); }
            gc = new GroupCard(grp, sel, u);
            gc.card = card;
        }
        FrameLayout pv = new FrameLayout(a);
        pv.setBackgroundColor(0xFF101014);
        if (gc != null) pv.addView(groupPreview(gc), new FrameLayout.LayoutParams(-1, -1));
        else singlePreview(pv, u, file);
        TextView keepTag = stamp(gc != null ? "KEEP ALL" : "KEEP", Ui.night ? 0xFF8AB4F8 : 0xFF1A56C4, -12);
        TextView accTag = gc != null ? stamp("DELETE", Ui.night ? 0xFFF2B8B5 : 0xFFB3261E, 12)
                : stamp("ACCEPT", Ui.night ? 0xFF7DD88F : 0xFF1E6B2E, 12);
        int vg = gc != null ? Gravity.CENTER_VERTICAL : Gravity.TOP;
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(-2, -2, vg | Gravity.START); kl.setMargins(dp(16), dp(20), 0, 0);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-2, -2, vg | Gravity.END); al.setMargins(0, dp(20), dp(16), 0);
        pv.addView(keepTag, kl); pv.addView(accTag, al);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0, 1);
        card.addView(pv, pl);

        // ---- the decision and the item
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout top = Ui.row(a);
        top.addView(Ui.badge(a, o.risk));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        TextView frees = Ui.text(a, "frees " + Report.size(o.saved) + (o.exact ? "" : " (est.)"), 15, Ui.text, true);
        top.addView(frees);
        info.addView(top, new LinearLayout.LayoutParams(-1, -2));
        String name, sub;
        if (gc != null) {
            Ui.add(info, Ui.text(a, grp.relation(), 17, Ui.text, true), 8);
            FileItem other = grp.members.get(1);
            StringBuilder why = new StringBuilder("★ Suggested to keep: " + grp.best.name());
            List<String> r = grp.whyBest(other);
            if (!r.isEmpty()) why.append(" — ").append(join(r));
            if (grp.isBurst() && grp.best.quality == null) why.append(". Run Analyse again to also check eyes, faces and light.");
            TextView w = Ui.add(info, Ui.text(a, why.toString(), 14, Ui.text2, false), 2);
            w.setMaxLines(3);
            TextView hint = Ui.add(info, Ui.text(a, "Tick the photos to delete. Swipe right to apply, left to keep all.", 13, Ui.text, true), 6);
            hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            gc.warn = Ui.add(info, Ui.text(a, "", 13, Ui.night ? 0xFFF2B8B5 : 0xFFB3261E, true), 4);
            gc.frees = frees;
            gc.accTag = accTag;
            name = file.name(); sub = Group.facts(file);
        } else {
            Ui.add(info, Ui.text(a, o.technique, 17, Ui.text, true), 8);
            TextView why = Ui.add(info, Ui.text(a, o.why, 14, Ui.text2, false), 2);
            why.setMaxLines(3);
            if (file != null) {
                name = file.name();
                int s = file.rel.lastIndexOf('/');
                sub = (s < 0 ? "Internal storage" : file.rel.substring(0, s)) + " · " + Report.size(file.size) + " · " + Ui.date(file.mtime);
            } else {
                name = u.title;
                sub = "App · " + Report.size(u.app.codeBytes + u.app.dataBytes + u.app.cacheBytes) + " · opened " + Ui.ago(u.app.lastUsed, Session.result.now);
            }
            TextView nm = Ui.add(info, Ui.text(a, name, 15, Ui.text, true), 10); Ui.ellipsizeMiddle(nm);
            TextView sb = Ui.add(info, Ui.text(a, sub, 13, Ui.text2, false), 0); Ui.ellipsizeMiddle(sb);
        }
        card.addView(info, new LinearLayout.LayoutParams(-1, -2));

        if (!isFront) {
            if (gc != null) gc.update();
            card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            return card;
        }
        // ---- front card: tap, swipe, accessibility actions
        // taps are detected by the swipe handler (clickable children would swallow the drag)
        pv.setClickable(false);
        pv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);     // the card's actions cover it
        final GroupCard fg = gc;
        frontGroup = gc;
        if (gc != null) { gc.update(); acceptBtn.setEnabled(true); keepBtn.setText("← Keep all"); }
        else {
            acceptBtn.setText("Accept →"); acceptBtn.setContentDescription("Accept the planned action for this item");
            keepBtn.setText("← Keep");
            card.setContentDescription("Item " + pos + " of " + total + ". " + o.technique + ". Risk: " + Ui.TIER_SPOKEN[o.risk]
                    + ". Frees " + Report.size(o.saved) + (o.exact ? "" : ", estimated") + ". " + o.why + ". " + name + ", " + sub.replace(" · ", ", ")
                    + ". Swipe left or use Keep to keep it; swipe right or use Accept to accept. Actions available.");
        }
        card.setFocusable(true);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View h, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(h, info);
                if (fg != null) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_ACCEPT, "Apply: " + fg.g.summary(fg.sel)));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_KEEP, "Keep all"));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_VIEW, "Compare side by side"));
                    for (int i = 0; i < fg.g.members.size() && i < 8; i++) {
                        FileItem m = fg.g.members.get(i);
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_TICK + i,
                                (fg.sel.contains(m.rel) ? "Keep " : "Delete ") + m.name()));
                    }
                } else {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_ACCEPT, "Accept"));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_KEEP, "Keep"));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_VIEW, "View"));
                }
            }
            @Override public boolean performAccessibilityAction(View h, int action, Bundle args) {
                if (action == ACT_ACCEPT) { decide(+1); return true; }
                if (action == ACT_KEEP) { decide(-1); return true; }
                if (action == ACT_VIEW) { openItem(u, null); return true; }
                if (fg != null && action >= ACT_TICK && action < ACT_TICK + Math.min(8, fg.g.members.size())) {
                    fg.toggle(fg.g.members.get(action - ACT_TICK)); return true;
                }
                return super.performAccessibilityAction(h, action, args);
            }
        });
        card.setOnTouchListener(new Swipe(keepTag, accTag, new TapRouter() {
            public void onTap(float rx, float ry) {
                if (fg == null) { openItem(u, null); return; }
                for (Tile t : fg.tiles) if (hit(t.tick, rx, ry, dp(8))) { fg.toggle(t.m); return; }
                for (Tile t : fg.tiles) if (hit(t.box, rx, ry, 0)) { openItem(u, t.m); return; }
                openItem(u, null);
            }
        }, pv));
        return card;
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) b.append(b.length() == 0 ? "" : ", ").append(b.length() == 0 ? x : x.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + x.substring(1));
        return b.toString();
    }

    /** Whether a raw screen point is on view v (grown by `slack` px on each side). */
    private static boolean hit(View v, float rx, float ry, int slack) {
        if (v == null || !v.isShown()) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        if (!v.getGlobalVisibleRect(r)) return false;
        r.inset(-slack, -slack);
        return r.contains((int) rx, (int) ry);
    }

    private void singlePreview(FrameLayout pv, Unit u, FileItem file) {
        TextView ext = Ui.text(a, file != null ? ListScreen.ext(file) : "APP", 30, 0xFF8E8A98, true);
        ext.setGravity(Gravity.CENTER);
        pv.addView(ext, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(a);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (file != null) {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pv.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            if (Thumbs.canPreview(file)) Thumbs.load(iv, file, Math.min(1080, a.getResources().getDisplayMetrics().widthPixels));
            playIcon(pv, file);
        } else if (u.app != null) {
            try { iv.setImageDrawable(a.getPackageManager().getApplicationIcon(u.app.pkg)); } catch (PackageManager.NameNotFoundException ignored) {}
            ext.setVisibility(View.GONE);
            pv.addView(iv, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
        }
        TextView tap = Ui.text(a, file != null && FileKind.of(file) != FileKind.NONE ? FileKind.label(FileKind.of(file)) + " ›" : "Details ›", 12, 0xFFFFFFFF, true);
        tap.setBackground(Ui.round(0x99000000, dp(12)));
        tap.setPadding(dp(10), dp(4), dp(10), dp(4));
        tap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); tl.setMargins(0, 0, dp(10), dp(10));
        pv.addView(tap, tl);
    }

    private void playIcon(FrameLayout box, FileItem file) {
        int k = FileKind.of(file);
        if (k != FileKind.VIDEO && k != FileKind.AUDIO) return;
        TextView play = Ui.text(a, k == FileKind.VIDEO ? "▶" : "♪", 22, 0xFFFFFFFF, true);
        play.setGravity(Gravity.CENTER);
        play.setBackground(Ui.round(0x99000000, dp(24)));
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(play, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
    }

    /**
     * Every copy with a delete tick (top left) and a KEEP / DELETE badge (top right) that follow the ticks.
     * Two copies sit side by side, the suggested keeper on the left; three or more fill a 2 x 2 grid
     * (the fourth cell says how many more there are; the compare screen shows them all).
     */
    private View groupPreview(final GroupCard gc) {
        Group g = gc.g;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView rel = Ui.text(a, (g.members.size() > 2 ? g.members.size() + " copies" : g.relation()) + "  ·  tap a photo to compare", 12, 0xFFFFFFFF, true);
        rel.setBackgroundColor(0xFF24252C);
        rel.setPadding(dp(12), dp(6), dp(12), dp(6));
        rel.setGravity(Gravity.CENTER);
        Ui.ellipsizeMiddle(rel);
        rel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        col.addView(rel, new LinearLayout.LayoutParams(-1, -2));
        int n = g.members.size(), shown = Math.min(n, 4);
        boolean grid = n > 2;
        int px = Math.min(720, a.getResources().getDisplayMetrics().widthPixels / 2 + 1);
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < (grid ? 2 : 1); r++) {
            LinearLayout row = Ui.row(a);
            row.setGravity(Gravity.FILL_VERTICAL);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -1, 1);
                l.setMargins(c == 0 ? 0 : dp(1), r == 0 ? 0 : dp(1), c == 0 ? dp(1) : 0, 0);
                if (i < shown) {
                    int more = (i == 3 && n > 4) ? n - 4 : 0;
                    row.addView(tile(gc, g.members.get(i), px / (grid ? 2 : 1), grid, more), l);
                } else row.addView(new View(a), l);
            }
            rows.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        col.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        return col;
    }

    private View tile(GroupCard gc, FileItem f, int px, boolean small, int more) {
        Group g = gc.g;
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        FrameLayout box = new FrameLayout(a);
        box.setBackgroundColor(0xFF18181D);
        TextView ext = Ui.text(a, ListScreen.ext(f), 20, 0xFF8E8A98, true);
        ext.setGravity(Gravity.CENTER);
        box.addView(ext, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (Thumbs.canPreview(f)) Thumbs.load(iv, f, px);
        playIcon(box, f);
        if (more > 0) {
            TextView m = Ui.text(a, "+" + more + " more\ntap to see all", 15, 0xFFFFFFFF, true);
            m.setGravity(Gravity.CENTER);
            m.setBackgroundColor(0x99000000);
            box.addView(m, new FrameLayout.LayoutParams(-1, -1));
        }
        Tile t = new Tile();
        t.m = f; t.box = box;
        t.tick = new Tick(a);
        t.tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);    // the card's actions tick for TalkBack
        box.addView(t.tick, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));
        t.badge = Group.badge(a, small ? 11 : 13);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); bl.setMargins(0, dp(10), dp(6), 0);
        box.addView(t.badge, bl);
        h.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout cap = new LinearLayout(a);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setBackgroundColor(f == g.best ? 0xFF15261A : 0xFF1E1F24);
        cap.setPadding(dp(8), dp(4), dp(8), dp(5));
        TextView nt = Ui.text(a, g.note(f), small ? 11 : 13, f == g.best ? 0xFF9BE3AA : 0xFFE6E1E5, true);
        Ui.ellipsizeMiddle(nt);
        cap.addView(nt);
        TextView fa = Ui.text(a, Group.facts(f), 11, 0xFFC9C5D0, false);
        Ui.ellipsizeMiddle(fa);
        cap.addView(fa);
        if (!small) {
            TextView fo = Ui.text(a, Group.folder(f), 11, 0xFFB4B0BC, false);
            Ui.ellipsizeMiddle(fo);
            cap.addView(fo);
        }
        h.addView(cap, new LinearLayout.LayoutParams(-1, -2));
        gc.tiles.add(t);
        return h;
    }

    private TextView stamp(String s, int color, float rot) {
        TextView t = Ui.text(a, s, 22, color, true);
        android.graphics.drawable.GradientDrawable g = Ui.round(Ui.night ? 0xE6121316 : 0xE6FFFFFF, dp(8));
        g.setStroke(dp(3), color);
        t.setBackground(g);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setRotation(rot);
        t.setAlpha(0f);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** Opens the viewer, or for a group card the compare screen (showing `other` next to the suggested keeper). */
    private void openItem(Unit u, FileItem other) {
        final Group g = Group.forOption(u.file, Session.plan.chosen.get(u));
        if (g != null) {
            Set<String> sel = selections.get(g.key());
            if (sel == null) { sel = g.current(); selections.put(g.key(), sel); }
            FileItem right = other != null && other != g.best ? other : g.members.get(1);
            a.push(new CompareScreen(a, g, sel, g.best, right, new Runnable() {
                public void run() { a.onBackPressed(); fly(+1); }               // "Apply" on the compare screen
            }));
            return;
        }
        if (u.file != null && FileKind.of(u.file) != FileKind.NONE) a.push(new ViewerScreen(a, u.file));
        else a.push(new DetailScreen(a, Row.forUnit(u)));
    }

    interface TapRouter { void onTap(float rawX, float rawY); }

    // ------------------------------------------------------------------ gestures
    private final class Swipe implements View.OnTouchListener {
        private final View keepTag, accTag, preview;
        private final TapRouter router;
        private float x0, y0, dx, dy;
        private boolean dragging;
        private VelocityTracker vt;
        private final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        private final float flingV = dp(250);              // a quick flick is enough
        private boolean armed;                              // past the commit distance (for the haptic tick)

        /** Distance that commits a swipe: about a thumb's width, never more than 15% of the card. */
        private float commit(float w) { return Math.max(slop * 2f, Math.min(w * 0.15f, dp(56))); }

        Swipe(View k, View ac, TapRouter r, View pv) { keepTag = k; accTag = ac; router = r; preview = pv; }

        public boolean onTouch(View v, MotionEvent e) {
            if (animating) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX(); y0 = e.getRawY(); dx = dy = 0; dragging = false; armed = false;
                    vt = VelocityTracker.obtain(); vt.addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    dx = e.getRawX() - x0; dy = e.getRawY() - y0;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        float w = Math.max(1, v.getWidth());
                        v.setTranslationX(dx);
                        v.setRotation(dx / w * 12f);
                        float c = commit(w);
                        keepTag.setAlpha(Math.max(0f, Math.min(1f, -dx / c)));
                        accTag.setAlpha(Math.max(0f, Math.min(1f, dx / c)));
                        boolean past = Math.abs(dx) >= c;
                        if (past != armed) {
                            armed = past;
                            if (past) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        }
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
                    } else if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                        // a tap on the preview: tick / compare / view (decided by what is under the finger)
                        if (e.getY() <= preview.getBottom()) router.onTap(e.getRawX(), e.getRawY());
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
            v.animate().translationX(0).rotation(0).setDuration(animOn() ? 180 : 0).start();
            keepTag.setAlpha(0f); accTag.setAlpha(0f);
        }
    }

    private static boolean animOn() { return android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled(); }

    /** Animate the front card off screen (-1 left = keep, +1 right = accept), then record the decision. */
    private void fly(final int dir) {
        if (front == null || animating) return;
        final View v = front;
        if (!animOn()) { decide(dir); return; }
        animating = true;
        float w = Math.max(v.getWidth(), dp(320));
        v.animate().translationX(dir * w * 1.5f).rotation(dir * 22f).alpha(0f).setDuration(220).withEndAction(new Runnable() {
            public void run() { animating = false; decide(dir); }
        }).start();
    }

    private void decide(int dir) {
        if (index >= queue.size()) return;
        Unit u = queue.get(index);
        Option o = Session.plan.chosen.get(u);
        Decision d = new Decision();
        d.index = index;
        String name = u.file != null ? u.file.name() : u.title;
        Group g = Group.forOption(u.file, o);
        String said;
        if (g != null) {
            Set<String> sel = selections.get(g.key());
            if (sel == null) sel = g.current();
            Set<String> del = dir > 0 ? new HashSet<String>(sel) : new HashSet<String>();
            d.before = g.apply(a, del);
            d.accepted = del.size(); d.kept = g.members.size() - del.size();
            d.bytesAccepted = g.bytes(del);
            for (FileItem m : g.members) if (!del.contains(m.rel)) d.bytesKept += m.size;
            d.groupKey = g.key(); d.groupSel = new HashSet<String>(sel);
            selections.remove(g.key());
            said = del.isEmpty() ? "Kept all " + g.members.size() + "." : g.summary(del) + ".";
        } else {
            d.before = new HashMap<Unit, String[]>();
            d.before.put(u, Session.state(u));
            long b = o != null ? o.saved : 0;
            if (dir < 0) { Session.setKept(a, Collections.singletonList(u), true); d.kept = 1; d.bytesKept = b; said = "Kept " + name + "."; }
            else { Session.setAccepted(a, u, o != null ? o.technique : techOf.get(u)); d.accepted = 1; d.bytesAccepted = b; said = "Accepted " + name + "."; }
        }
        nKept += d.kept; nAccepted += d.accepted; bytesKept += d.bytesKept; bytesAccepted += d.bytesAccepted;
        int added = 0;
        if (d.kept > 0) for (Unit x : Session.undecided(technique)) if (!queued.contains(x)) { enqueue(x); added++; }
        host.announceForAccessibility(said + (added > 0 ? " " + added + " more items were added to make up the space." : ""));
        if (added > 0) a.toast(added + (added == 1 ? " item was" : " items were") + " added to the end of the deck to make up the space you kept.");
        history.push(d);
        index++;
        showCard();
    }

    private void undo() {
        if (history.isEmpty() || animating) return;
        Decision d = history.pop();
        Session.restore(a, d.before);
        nKept -= d.kept; nAccepted -= d.accepted; bytesKept -= d.bytesKept; bytesAccepted -= d.bytesAccepted;
        index = d.index;
        if (d.groupKey != null) selections.put(d.groupKey, new HashSet<String>(d.groupSel));
        host.announceForAccessibility("Undone.");
        showCard();
    }

    private void doneView() {
        LinearLayout c = Ui.card(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView h = Ui.heading(a, nKept + nAccepted == 0 ? "Nothing left to review" : "✓ All reviewed", 22);
        h.setGravity(Gravity.CENTER);
        c.addView(h, new LinearLayout.LayoutParams(-1, -2));
        String s = nKept + nAccepted == 0
                ? "Every item in this part of the plan already has your decision."
                : "Accepted " + nAccepted + (nAccepted == 1 ? " item" : " items") + " (" + Report.size(bytesAccepted) + ")  ·  kept "
                  + nKept + (nKept == 1 ? " item" : " items") + " (" + Report.size(bytesKept) + ").";
        TextView t = Ui.add(c, Ui.text(a, s, 16, Ui.text, false), 10); t.setGravity(Gravity.CENTER);
        TextView n = Ui.add(c, Ui.text(a, "Accepted items are marked “Approved by you” in the plan. Storage Planner never deletes anything: "
                + "use Open with or Show in folder on each item, or the app's settings for apps.", 14, Ui.text2, false), 10);
        n.setGravity(Gravity.CENTER);
        Button back = Ui.button(a, "Back to the plan", true);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.onBackPressed(); } });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(16);
        c.addView(back, bl);
        if (Session.acceptedInResults() > 0) {
            Button see = Ui.button(a, "See approved items", false);
            see.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(ListScreen.accepted(a)); } });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(10);
            c.addView(see, sl);
        }
        host.addView(c, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        c.post(new Runnable() { public void run() { progress.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); } });
    }
}
