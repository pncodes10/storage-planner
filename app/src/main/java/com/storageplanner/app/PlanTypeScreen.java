package com.storageplanner.app;

import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Report;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One file type of your plan (photos, videos, installers...): tabs for Delete, Compress and Keep. Gallery view: the
 * files in a grid, grouped by why they are suggested, ticked when the action stays; tap to tick or untick, long-press
 * to look. Card view: one file at a time; swipe right (or tap the action) to confirm, left to keep. On Keep, pick files
 * to delete or compress instead. A finalized plan is read-only until you tap Edit plan.
 */
public final class PlanTypeScreen extends Screen {
    private final String type;
    private String action;
    private boolean cards;
    private List<FinalPlan.Entry> tab = new ArrayList<FinalPlan.Entry>();
    private final Set<FinalPlan.Entry> picked = new HashSet<FinalPlan.Entry>();      // Keep tab: files picked to act on
    private boolean picking;
    private int cardAt;
    private final ArrayDeque<Object[]> undo = new ArrayDeque<Object[]>();               // {entries, earlier actions}

    private LinearLayout tabs, tools, banner, bottom;
    private TextView summary;
    private FrameLayout body;
    private ListView grid;
    private Adapter adapter;
    private final List<Object[]> rows = new ArrayList<Object[]>();                       // {"h", group, entries} or {"c", entries}

    public PlanTypeScreen(MainActivity a, String type, String action) {
        super(a);
        this.type = type;
        this.action = action;
        this.cards = a.getSharedPreferences("finalplan", 0).getBoolean("cards", false);
    }

    public String title() { return FinalPlan.typeLabel(type); }

    @Override public String descriptor() { return "plantype:" + type + ":" + (action == null ? "" : action); }

    private boolean locked() { return PlanStore.finalized(a) != 0; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        tabs = Ui.row(a);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        tools = Ui.row(a);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = dp(8);
        root.addView(tools, tl);
        summary = Ui.text(a, "", 13, Ui.text2, false);
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(6);
        root.addView(summary, sl);
        banner = new LinearLayout(a);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bnl = new LinearLayout.LayoutParams(-1, -2); bnl.topMargin = dp(6);
        root.addView(banner, bnl);
        body = new FrameLayout(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1); bl.topMargin = dp(8);
        root.addView(body, bl);
        bottom = Ui.row(a);
        LinearLayout.LayoutParams bol = new LinearLayout.LayoutParams(-1, -2); bol.topMargin = dp(6);
        root.addView(bottom, bol);

        grid = new ListView(a);
        grid.setDivider(null);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        if (action == null) {                                     // open on the first tab with files, delete first
            FinalPlan.Totals t = FinalPlan.totals(PlanStore.entries(a), type);
            action = t.count(FinalPlan.DELETE) > 0 || hasSuggested(FinalPlan.DELETE) ? FinalPlan.DELETE
                    : t.count(FinalPlan.COMPRESS) > 0 || hasSuggested(FinalPlan.COMPRESS) ? FinalPlan.COMPRESS : FinalPlan.KEEP;
        }
        load();
        return root;
    }

    private boolean hasSuggested(String act) {
        for (FinalPlan.Entry e : PlanStore.entries(a)) if (type.equals(e.type) && act.equals(e.suggestedAction)) return true;
        return false;
    }

    @Override public void refresh() { if (body != null) load(); }

    /** Rebuilds the tab's list (after switching tabs or moving files out of Keep). */
    private void load() {
        tab = FinalPlan.tab(PlanStore.entries(a), type, action);
        picked.retainAll(tab);
        if (cardAt >= tab.size()) cardAt = 0;
        rows.clear();
        String g = null;
        List<FinalPlan.Entry> cur = null, group = null;
        for (FinalPlan.Entry e : tab) {
            String eg = FinalPlan.group(e, action);
            if (!eg.equals(g)) {
                g = eg; group = new ArrayList<FinalPlan.Entry>();
                rows.add(new Object[]{"h", eg, group});
                cur = null;
            }
            group.add(e);
            if (cur == null || cur.size() == 3) { cur = new ArrayList<FinalPlan.Entry>(); rows.add(new Object[]{"c", cur}); }
            cur.add(e);
        }
        drawTabs();
        drawTools();
        drawBanner();
        show();
        updateSummary();
    }

    // ------------------------------------------------------------------ header
    private void drawTabs() {
        tabs.removeAllViews();
        FinalPlan.Totals t = FinalPlan.totals(PlanStore.entries(a), type);
        for (final String act : FinalPlan.ACTIONS) {
            boolean sel = act.equals(action);
            int n = FinalPlan.KEEP.equals(act) ? t.count(act) : t.count(act);
            Button b = Ui.button(a, FinalPlan.actionLabel(act) + "  " + String.format(Locale.US, "%,d", n), sel);
            if (sel) { b.setBackground(Ui.round(PlanScreen.color(act), dp(24))); b.setTextColor(0xFFFFFFFF); }
            else b.setTextColor(PlanScreen.color(act));
            b.setContentDescription(FinalPlan.actionLabel(act) + ", " + n + " files" + (sel ? ", selected" : ""));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (!act.equals(action)) { action = act; picking = false; picked.clear(); cardAt = 0; undo.clear(); load(); } }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (!act.equals(FinalPlan.KEEP)) lp.rightMargin = dp(6);
            tabs.addView(b, lp);
        }
    }

    private void drawTools() {
        tools.removeAllViews();
        final Button view = Ui.button(a, cards ? "▦  Gallery" : "▭  Cards", false);
        view.setContentDescription(cards ? "Switch to the gallery view" : "Switch to the card view, one file at a time");
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cards = !cards; a.getSharedPreferences("finalplan", 0).edit().putBoolean("cards", cards).apply(); drawTools(); show(); updateSummary(); }
        });
        tools.addView(view, new LinearLayout.LayoutParams(-2, -2));
        tools.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        if (cards) return;
        if (FinalPlan.KEEP.equals(action)) {
            Button sel = Ui.button(a, picking ? "Done" : "Select", false);
            sel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (locked()) { lockedToast(); return; } picking = !picking; if (!picking) picked.clear(); drawTools(); adapter.notifyDataSetChanged(); updateSummary(); }
            });
            tools.addView(sel, new LinearLayout.LayoutParams(-2, -2));
        } else {
            Button all = Ui.button(a, "All", false), none = Ui.button(a, "None", false);
            all.setContentDescription("Tick every file in this tab");
            none.setContentDescription("Untick every file in this tab: they are kept");
            all.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setAll(tab, true); } });
            none.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setAll(tab, false); } });
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-2, -2); l1.rightMargin = dp(6);
            tools.addView(all, l1);
            tools.addView(none, new LinearLayout.LayoutParams(-2, -2));
        }
    }

    private void drawBanner() {
        banner.removeAllViews();
        if (!locked()) { banner.setVisibility(View.GONE); return; }
        banner.setVisibility(View.VISIBLE);
        banner.setBackground(Ui.round(Ui.surface, dp(10)));
        banner.setPadding(dp(12), dp(6), dp(6), dp(6));
        banner.addView(Ui.text(a, "✓ The plan is final. Edit it to make changes.", 13, Ui.text, false), new LinearLayout.LayoutParams(0, -2, 1));
        Button edit = Ui.button(a, "Edit plan", false);
        edit.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { PlanStore.setFinalized(a, false); load(); } });
        banner.addView(edit, new LinearLayout.LayoutParams(-2, -2));
    }

    private void updateSummary() {
        int ticked = 0; long bytes = 0, saved = 0;
        for (FinalPlan.Entry e : tab) if (action.equals(e.action)) { ticked++; bytes += e.file.size; saved += e.saved(); }
        String s;
        if (tab.isEmpty()) s = FinalPlan.KEEP.equals(action) ? "No files kept here." : "Nothing to " + FinalPlan.actionLabel(action).toLowerCase(Locale.ROOT) + " here.";
        else if (FinalPlan.DELETE.equals(action)) s = ticked + " of " + tab.size() + " ticked · frees " + Report.size(saved) + (cards ? "" : " · tap to untick, long-press to look");
        else if (FinalPlan.COMPRESS.equals(action)) s = ticked + " of " + tab.size() + " ticked · frees about " + Report.size(saved) + (cards ? "" : " · tap to untick, long-press to look");
        else s = String.format(Locale.US, "%,d", tab.size()) + " files kept · " + Report.size(bytes)
                + (picking ? " · " + picked.size() + " picked" : cards ? "" : " · tap to look, Select to delete or compress some");
        summary.setText(s);
        bottom.removeAllViews();
        if (FinalPlan.KEEP.equals(action) && picking && !picked.isEmpty()) {
            Button del = Ui.button(a, "Delete " + picked.size(), true);
            del.setBackground(Ui.round(PlanScreen.DELETE_COLOR, dp(24))); del.setTextColor(0xFFFFFFFF);
            del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { movePicked(FinalPlan.DELETE); } });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1); dl.rightMargin = dp(8);
            bottom.addView(del, dl);
            boolean any = false; for (FinalPlan.Entry e : picked) if (FinalPlan.possible(e, FinalPlan.COMPRESS)) any = true;
            if (any) {
                Button com = Ui.button(a, "Compress", false);
                com.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { movePicked(FinalPlan.COMPRESS); } });
                bottom.addView(com, new LinearLayout.LayoutParams(0, -2, 1));
            }
        }
        if (!undo.isEmpty() && !cards) {
            Button u = Ui.button(a, "Undo", false);
            u.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { undoLast(); } });
            bottom.addView(u, new LinearLayout.LayoutParams(-2, -2));
        }
    }

    private void lockedToast() { a.toast("The plan is final. Tap Edit plan to change it."); }

    // ------------------------------------------------------------------ changes
    /** Ticks (keeps this tab's action) or unticks (keeps the file) these entries, with undo. */
    private void setAll(List<FinalPlan.Entry> l, boolean on) {
        if (locked()) { lockedToast(); return; }
        List<FinalPlan.Entry> ch = new ArrayList<FinalPlan.Entry>();
        String to = on ? action : FinalPlan.KEEP;
        for (FinalPlan.Entry e : l) if (!to.equals(e.action) && FinalPlan.possible(e, to)) ch.add(e);
        apply(ch, to);
    }

    private void apply(List<FinalPlan.Entry> ch, String to) {
        if (ch.isEmpty()) return;
        String[] before = new String[ch.size()];
        for (int i = 0; i < ch.size(); i++) before[i] = ch.get(i).action;
        undo.push(new Object[]{new ArrayList<FinalPlan.Entry>(ch), before});
        while (undo.size() > 50) undo.removeLast();
        PlanStore.set(a, ch, to);
        drawTabs();
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    @SuppressWarnings("unchecked")
    private void undoLast() {
        if (undo.isEmpty()) return;
        Object[] u = undo.pop();
        List<FinalPlan.Entry> l = (List<FinalPlan.Entry>) u[0];
        String[] before = (String[]) u[1];
        for (int i = 0; i < l.size(); i++) PlanStore.set(a, Collections.singletonList(l.get(i)), before[i]);
        if (cards && cardAt > 0) cardAt--;
        drawTabs();
        if (FinalPlan.KEEP.equals(action)) load(); else { adapter.notifyDataSetChanged(); show(); updateSummary(); }
    }

    private void movePicked(String to) {
        if (locked()) { lockedToast(); return; }
        List<FinalPlan.Entry> l = new ArrayList<FinalPlan.Entry>(picked);
        int n = 0; for (FinalPlan.Entry e : l) if (FinalPlan.possible(e, to)) n++;
        apply(l, to);
        picked.clear(); picking = false;
        a.toast(n + (n == 1 ? " file" : " files") + " moved to " + FinalPlan.actionLabel(to) + ".");
        load();
    }

    private void toggle(FinalPlan.Entry e) {
        if (locked()) { lockedToast(); return; }
        if (FinalPlan.KEEP.equals(action)) {
            if (!picking) { a.push(new ViewerScreen(a, e.file)); return; }
            if (!picked.remove(e)) picked.add(e);
            adapter.notifyDataSetChanged(); updateSummary(); drawTools();
            return;
        }
        apply(Collections.singletonList(e), action.equals(e.action) ? FinalPlan.KEEP : action);
    }

    // ------------------------------------------------------------------ body
    private void show() {
        body.removeAllViews();
        if (tab.isEmpty()) {
            TextView t = Ui.text(a, FinalPlan.KEEP.equals(action) ? "No files are kept here." : "The analysis suggests nothing to "
                    + FinalPlan.actionLabel(action).toLowerCase(Locale.ROOT) + " for " + FinalPlan.typeLabel(type).toLowerCase(Locale.ROOT) + ".", 15, Ui.text2, false);
            t.setGravity(Gravity.CENTER);
            body.addView(t, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        if (!cards) { body.addView(grid, new FrameLayout.LayoutParams(-1, -1)); adapter.notifyDataSetChanged(); return; }
        showCard();
    }

    // ---- gallery
    private int cell() { return (a.getResources().getDisplayMetrics().widthPixels - dp(24) - dp(8)) / 3; }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return "h".equals(rows.get(i)[0]) ? 0 : 1; }

        @SuppressWarnings("unchecked")
        public View getView(int i, View v, ViewGroup parent) {
            Object[] r = rows.get(i);
            if ("h".equals(r[0])) {
                Header h = v == null ? new Header() : (Header) v.getTag();
                h.bind((String) r[1], (List<FinalPlan.Entry>) r[2]);
                return h.root;
            }
            Line ln = v == null ? new Line() : (Line) v.getTag();
            ln.bind((List<FinalPlan.Entry>) r[1]);
            return ln.root;
        }
    }

    private final class Header {
        final LinearLayout root = Ui.row(a);
        final TextView title = Ui.text(a, "", 15, Ui.text, true), sub = Ui.text(a, "", 12, Ui.text2, false);
        final Button flip = Ui.button(a, "", false);
        List<FinalPlan.Entry> group;

        Header() {
            root.setPadding(dp(2), dp(14), 0, dp(6));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(title); col.addView(sub);
            root.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            flip.setMinHeight(dp(36)); flip.setMinimumHeight(dp(36));
            flip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean anyOff = false; for (FinalPlan.Entry e : group) if (!action.equals(e.action)) anyOff = true;
                    setAll(group, anyOff);
                }
            });
            root.addView(flip, new LinearLayout.LayoutParams(-2, -2));
            root.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            root.setTag(this);
        }

        void bind(String g, List<FinalPlan.Entry> l) {
            group = l;
            long b = 0; int on = 0;
            for (FinalPlan.Entry e : l) { b += e.file.size; if (action.equals(e.action)) on++; }
            title.setText(g);
            boolean keepTab = FinalPlan.KEEP.equals(action);
            sub.setText(keepTab ? PlanScreen.count(l.size(), "file") + " · " + Report.size(b) : on + " of " + l.size() + " ticked · " + Report.size(b));
            flip.setVisibility(keepTab ? View.GONE : View.VISIBLE);
            flip.setText(on < l.size() ? "Tick all" : "Untick all");
            flip.setContentDescription((on < l.size() ? "Tick all in " : "Untick all in ") + g);
        }
    }

    private final class Line {
        final LinearLayout root = Ui.row(a);
        final Cell[] cells = new Cell[3];

        Line() {
            for (int k = 0; k < 3; k++) {
                cells[k] = new Cell();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell(), cell());
                if (k < 2) lp.rightMargin = dp(4);
                root.addView(cells[k].root, lp);
            }
            root.setPadding(0, 0, 0, dp(4));
            root.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            root.setTag(this);
        }

        void bind(List<FinalPlan.Entry> l) {
            for (int k = 0; k < 3; k++) {
                if (k < l.size()) { cells[k].root.setVisibility(View.VISIBLE); cells[k].bind(l.get(k)); }
                else cells[k].root.setVisibility(View.INVISIBLE);
            }
        }
    }

    private final class Cell {
        final FrameLayout root = new FrameLayout(a);
        final ImageView iv = new ImageView(a);
        final TextView ext = Ui.text(a, "", 13, 0xFFFFFFFF, true), caption = Ui.text(a, "", 11, 0xFFFFFFFF, false);
        final View dim = new View(a);
        final Tick tick = new Tick(a);
        FinalPlan.Entry e;

        Cell() {
            root.setBackground(Ui.round(0xFF1C1C22, dp(8)));
            root.setClipToOutline(true);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            ext.setGravity(Gravity.CENTER);
            root.addView(ext, new FrameLayout.LayoutParams(-1, -1));
            dim.setBackgroundColor(0x99000000);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            caption.setBackgroundColor(0x88000000);
            caption.setPadding(dp(5), dp(2), dp(5), dp(2));
            Ui.ellipsizeMiddle(caption);
            root.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(tick, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END));
            root.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (e != null) toggle(e); } });
            root.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { if (e != null) a.push(new ViewerScreen(a, e.file)); return true; }
            });
        }

        void bind(FinalPlan.Entry en) {
            e = en;
            FileItem f = en.file;
            boolean preview = Thumbs.canPreview(f) && FileKind.of(f) == FileKind.IMAGE || FileKind.of(f) == FileKind.VIDEO;
            if (preview) { ext.setText(""); Thumbs.load(iv, f, cell()); }
            else { iv.setImageDrawable(null); iv.setTag(null); String n = f.name(); int dot = n.lastIndexOf('.'); ext.setText(dot > 0 ? n.substring(dot + 1).toUpperCase(Locale.ROOT) : "FILE"); }
            caption.setText(preview ? Report.size(f.size) : f.name() + " · " + Report.size(f.size));
            boolean keepTab = FinalPlan.KEEP.equals(action);
            boolean on = keepTab ? picked.contains(en) : action.equals(en.action);
            tick.setVisibility(keepTab && !picking ? View.GONE : View.VISIBLE);
            if (keepTab) tick.style(PlanScreen.DELETE_COLOR, "picked", "not picked");
            else if (FinalPlan.DELETE.equals(action)) tick.style(PlanScreen.DELETE_COLOR, "ticked, will be deleted", "not ticked, will be kept");
            else tick.style(Ui.accent, "ticked, will be compressed", "not ticked, will be kept as it is");
            tick.setChecked(on);
            dim.setVisibility(!keepTab && !on ? View.VISIBLE : View.GONE);
            root.setContentDescription(f.name() + ", " + Report.size(f.size) + ", " + Ui.date(f.mtime)
                    + (keepTab ? (picking ? (on ? ", picked" : ", not picked") : ", kept") : on ? ", will be " + (FinalPlan.DELETE.equals(action) ? "deleted" : "compressed") : ", kept")
                    + ". Double tap to " + (keepTab && !picking ? "look at it" : "change") + ", long press to look at it.");
        }
    }

    // ---- cards
    private TextView cardHint;

    private void showCard() {
        body.removeAllViews();
        if (cardAt >= tab.size()) {
            LinearLayout done = new LinearLayout(a);
            done.setOrientation(LinearLayout.VERTICAL);
            done.setGravity(Gravity.CENTER);
            done.addView(Ui.text(a, "✓", 44, PlanScreen.KEEP_COLOR, true));
            TextView t = Ui.text(a, "You've gone through all " + PlanScreen.count(tab.size(), "file") + " here.", 16, Ui.text, true);
            t.setGravity(Gravity.CENTER);
            done.addView(t);
            Button again = Ui.button(a, "Start again", false);
            again.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cardAt = 0; showCard(); } });
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2); al.topMargin = dp(12);
            done.addView(again, al);
            body.addView(done, new FrameLayout.LayoutParams(-1, -1));
            updateCardBottom();
            return;
        }
        final FinalPlan.Entry e = tab.get(cardAt);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.surface, dp(16)));
        card.setPadding(dp(12), dp(12), dp(12), dp(14));
        FrameLayout pv = new FrameLayout(a);
        pv.setBackground(Ui.round(0xFF1C1C22, dp(12)));
        pv.setClipToOutline(true);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pv.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        int kind = FileKind.of(e.file);
        if (kind == FileKind.IMAGE || kind == FileKind.VIDEO) Thumbs.load(iv, e.file, Math.min(1080, a.getResources().getDisplayMetrics().widthPixels));
        else {
            String n = e.file.name(); int dot = n.lastIndexOf('.');
            TextView x = Ui.text(a, PlanScreen.icon(type) + "\n" + (dot > 0 ? n.substring(dot + 1).toUpperCase(Locale.ROOT) : "FILE"), 30, 0xFFFFFFFF, true);
            x.setGravity(Gravity.CENTER);
            pv.addView(x, new FrameLayout.LayoutParams(-1, -1));
        }
        pv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ViewerScreen(a, e.file)); } });
        card.addView(pv, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView name = Ui.text(a, e.file.name(), 15, Ui.text, true);
        Ui.ellipsizeMiddle(name);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2); nl.topMargin = dp(10);
        card.addView(name, nl);
        card.addView(Ui.text(a, Report.size(e.file.size) + " · " + Ui.date(e.file.mtime) + " · " + Tags.sourceOf(e.file), 13, Ui.text2, false));
        String why = e.suggested == null ? "Nothing suggested for this file" : e.suggested.technique + (e.suggested.why == null || e.suggested.why.isEmpty() ? "" : ": " + e.suggested.why);
        TextView w = Ui.text(a, why, 13, Ui.text, false);
        w.setMaxLines(3);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2); wl.topMargin = dp(6);
        card.addView(w, wl);
        TextView now = Ui.text(a, "Now: " + FinalPlan.actionLabel(e.action), 13, PlanScreen.color(e.action), true);
        card.addView(now);
        card.setContentDescription(e.file.name() + ", " + Report.size(e.file.size) + ". " + why + ". Now: " + FinalPlan.actionLabel(e.action)
                + ". Use the buttons below; swipe right to confirm, left to keep.");
        FrameLayout holder = new FrameLayout(a);
        holder.addView(card, new FrameLayout.LayoutParams(-1, -1));
        holder.setOnTouchListener(new Swipe());
        body.addView(holder, new FrameLayout.LayoutParams(-1, -1));
        front = holder;
        updateCardBottom();
    }

    private View front;

    private void updateCardBottom() {
        updateSummary();
        bottom.removeAllViews();
        if (!cards) return;
        if (cardAt < tab.size()) {
            final FinalPlan.Entry e = tab.get(cardAt);
            boolean keepTab = FinalPlan.KEEP.equals(action);
            Button left = Ui.button(a, keepTab ? "Delete" : "Keep", false);
            final String leftTo = keepTab ? FinalPlan.DELETE : FinalPlan.KEEP;
            left.setTextColor(PlanScreen.color(leftTo));
            left.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { decide(e, leftTo, -1); } });
            Button right = Ui.button(a, keepTab ? "Keep" : FinalPlan.actionLabel(action), true);
            right.setBackground(Ui.round(PlanScreen.color(keepTab ? FinalPlan.KEEP : action), dp(24))); right.setTextColor(0xFFFFFFFF);
            final String rightTo = keepTab ? FinalPlan.KEEP : action;
            right.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { decide(e, rightTo, +1); } });
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(52), 1); l1.rightMargin = dp(8);
            bottom.addView(left, l1);
            bottom.addView(right, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        if (!undo.isEmpty()) {
            Button u = Ui.button(a, "↶", false);
            u.setContentDescription("Undo");
            u.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { undoLast(); } });
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(56), dp(52)); ul.leftMargin = dp(8);
            bottom.addView(u, ul);
        }
        summary.setText(cardAt < tab.size() ? (cardAt + 1) + " of " + tab.size() + " · swipe right to " + (FinalPlan.KEEP.equals(action) ? "keep" : FinalPlan.actionLabel(action).toLowerCase(Locale.ROOT))
                + ", left to " + (FinalPlan.KEEP.equals(action) ? "delete" : "keep") : summary.getText());
    }

    private boolean animating;

    private void decide(final FinalPlan.Entry e, final String to, int dir) {
        if (locked()) { lockedToast(); return; }
        if (animating) return;
        Runnable done = new Runnable() {
            public void run() {
                animating = false;
                if (!to.equals(e.action) && FinalPlan.possible(e, to)) {
                    String[] before = {e.action};
                    undo.push(new Object[]{new ArrayList<FinalPlan.Entry>(Collections.singletonList(e)), before});
                    PlanStore.set(a, Collections.singletonList(e), to);
                } else undo.push(new Object[]{new ArrayList<FinalPlan.Entry>(), new String[0]});
                cardAt++;
                drawTabs();
                showCard();
            }
        };
        boolean anim = android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
        if (front == null || !anim) { done.run(); return; }
        animating = true;
        float w = Math.max(front.getWidth(), dp(320));
        front.animate().translationX(dir * w * 1.3f).rotation(dir * 14f).alpha(0f).setDuration(200).withEndAction(done).start();
    }

    private final class Swipe implements View.OnTouchListener {
        private float x0, y0, dx;
        private boolean dragging;
        private VelocityTracker vt;
        private final int slop = ViewConfiguration.get(a).getScaledTouchSlop();

        public boolean onTouch(View v, MotionEvent ev) {
            if (animating || cardAt >= tab.size()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = ev.getRawX(); y0 = ev.getRawY(); dx = 0; dragging = false;
                    vt = VelocityTracker.obtain(); vt.addMovement(ev);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(ev);
                    dx = ev.getRawX() - x0;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(ev.getRawY() - y0)) dragging = true;
                    if (dragging) { v.setTranslationX(dx); v.setRotation(dx / Math.max(1, v.getWidth()) * 10f); }
                    return true;
                case MotionEvent.ACTION_UP: {
                    float vx = 0;
                    if (vt != null) { vt.addMovement(ev); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    float commit = Math.min(v.getWidth() * 0.25f, dp(90));
                    FinalPlan.Entry e = tab.get(cardAt);
                    boolean keepTab = FinalPlan.KEEP.equals(action);
                    if (dragging && (dx > commit || vx > dp(300) && dx > 0)) decide(e, keepTab ? FinalPlan.KEEP : action, +1);
                    else if (dragging && (dx < -commit || vx < -dp(300) && dx < 0)) decide(e, keepTab ? FinalPlan.DELETE : FinalPlan.KEEP, -1);
                    else {
                        v.animate().translationX(0).rotation(0).setDuration(150).start();
                        if (!dragging && Math.abs(ev.getRawY() - y0) < slop) a.push(new ViewerScreen(a, e.file));
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (vt != null) { vt.recycle(); vt = null; }
                    v.animate().translationX(0).rotation(0).setDuration(150).start();
                    return true;
            }
            return false;
        }
    }

    /** Where a file comes from, for the card ("camera photo", "chat image (received)"). */
    private static final class Tags {
        static String sourceOf(FileItem f) { return com.storageplanner.core.Tags.source(f); }
    }
}
