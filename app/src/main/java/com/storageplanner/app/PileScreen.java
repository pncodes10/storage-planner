package com.storageplanner.app;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Piles;
import com.storageplanner.core.Report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pile of the clean-up ("Memes and forwards"): every file in it, pictures as a grid. Each picture says in a word
 * what happens to it (Remove, Keep, Smaller); a tap flips it between removed and kept, and the ⓘ button (or a long
 * press) opens its page with the reason and similar photos. Copies of one photo sit together under one heading, with
 * the copy that stays marked "Best · kept".
 */
public final class PileScreen extends Screen {
    private final String pile;
    private ListView list;
    private TextView sub, reviewText;
    private ProgressBar reviewBar;
    private boolean restored;
    private Button allBtn;
    private final List<Object[]> rows = new ArrayList<Object[]>();    // {"h", title, FileItem focus} {"t", List<Entry>, FileItem best} {"f", Entry}
    private List<FinalPlan.Entry> files = new ArrayList<FinalPlan.Entry>();
    private Adapter adapter;

    public PileScreen(MainActivity a, String pile) { super(a); this.pile = pile; }

    public String title() { return Piles.title(pile); }

    @Override public String descriptor() { return "pile:" + pile; }

    /** What a tap moves a file to in this pile (made smaller, or removed). */
    private String acting() { return Piles.SMALLER.equals(pile) ? FinalPlan.COMPRESS : FinalPlan.DELETE; }

    static boolean picture(FileItem f) { int k = FileKind.of(f); return k == FileKind.IMAGE || k == FileKind.VIDEO; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(8), dp(16), dp(4));
        sub = Ui.text(a, "", 14, Ui.text2, false);
        top.addView(sub);
        TextView banner = Ui.text(a, Piles.banner(pile), 14, Piles.SMALLER.equals(pile) ? Ui.smallerInk : Ui.removeInk, false);
        banner.setBackground(Ui.round(Piles.SMALLER.equals(pile) ? Ui.smallerTint : Ui.removeTint, dp(14)));
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        Ui.add(top, banner, 10);
        // how much of the pile you have looked at: only those files are cleaned up
        reviewBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        reviewBar.setMax(1000);
        reviewBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(-1, -2); rb.topMargin = dp(10);
        top.addView(reviewBar, rb);
        reviewText = Ui.add(top, Ui.text(a, "", 13, Ui.text2, false), 2);
        reviewText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (Piles.COPIES.equals(pile)) waiting(top);
        root.addView(top);

        list = new ListView(a);
        list.setDivider(null);
        list.setPadding(dp(16), dp(4), dp(16), dp(16));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int state) { if (state == SCROLL_STATE_IDLE) savePosition(); }
            public void onScroll(AbsListView v, int first, int visible, int total) { seen(first, visible); }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = Ui.row(a);
        bar.setPadding(dp(16), dp(10), dp(16), dp(14));
        allBtn = Ui.button(a, "", false);
        allBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean anyActing = false;
                for (FinalPlan.Entry e : files) if (!FinalPlan.KEEP.equals(e.action)) anyActing = true;
                if (anyActing) { choose(a, files, FinalPlan.KEEP); ReviewStore.mark(a, files); }   // a decision on every file
                else {
                    List<FinalPlan.Entry> back = new ArrayList<FinalPlan.Entry>();
                    for (FinalPlan.Entry e : files) back.add(e);
                    choose(a, back, acting());
                }
                refresh();
            }
        });
        Button done = Ui.button(a, "Done", true);
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.onBackPressed(); } });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(52), 1); l1.rightMargin = dp(10);
        bar.addView(allBtn, l1);
        bar.addView(done, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(bar);
        refresh();
        return root;
    }

    /**
     * A paused analysis: groups of similar shots with photos of the same moment not looked at yet. The best one could be
     * among those, so nothing in the group is removed until they are. Shows the first group's photos done, then an empty
     * tile for each one still to come.
     */
    private void waiting(LinearLayout top) {
        if (Session.ub == null || Session.ub.det.waiting.isEmpty()) return;
        List<List<String>> groups = Session.ub.det.waiting;
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.outlineDrawable(a, Ui.text2, 1.5f));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(Ui.text(a, "◷  Waiting: not decided yet", 15, Ui.text, true));
        LinearLayout strip = Ui.row(a);
        int cell = dp(64), shown = 0;
        for (String rel : groups.get(0)) {
            FileItem f = Session.fileByRel.get(rel);
            if (f == null || shown == 3) continue;
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.round(Ui.surface2, dp(8)));
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell); lp.rightMargin = dp(6);
            strip.addView(iv, lp);
            Thumbs.load(iv, f, cell);
            shown++;
        }
        int missing = Session.ub.det.waitingMissing.get(0);
        for (int i = 0; i < Math.min(missing, 4 - shown); i++) {
            TextView t = Ui.text(a, "not looked at", 11, Ui.text2, false);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.round(Ui.surface2, dp(8)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell); lp.rightMargin = dp(6);
            strip.addView(t, lp);
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(10);
        c.addView(strip, sl);
        String more = groups.size() > 1 ? " " + Piles.count(groups.size() - 1, "more group") + " of similar shots wait" + (groups.size() == 2 ? "s" : "") + " the same way." : "";
        Ui.add(c, Ui.text(a, Piles.count(missing, "more shot") + " of this moment " + (missing == 1 ? "isn't" : "aren't")
                + " analysed yet. The best one could be among them, so nothing here is removed until carrying on finishes them." + more, 13, Ui.text2, false), 10);
        c.setContentDescription("Waiting: " + groups.size() + " groups of similar shots are not decided yet, because photos of the same moment are not analysed yet.");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(10);
        top.addView(c, cl);
    }

    /**
     * Sets your choice for these files. A finished plan opens again for changes (its finish is only a bookmark: nothing
     * has been deleted yet).
     */
    static void choose(MainActivity a, Collection<FinalPlan.Entry> l, String action) {
        if (PlanStore.finalized(a) != 0) { PlanStore.setFinalized(a, false); a.toast("Your plan is open for changes again."); }
        PlanStore.set(a, l, action);
    }

    @Override public void refresh() {
        if (list == null) return;
        files = new ArrayList<FinalPlan.Entry>();
        for (Piles.Pile p : Piles.build(PlanStore.entries(a))) if (p.id.equals(pile)) files = p.files;
        layout();
        adapter.notifyDataSetChanged();
        updateSummary();
        if (!restored) {                                  // back where you left this pile, even after other pages or a restart
            restored = true;
            int[] pos = ReviewStore.position(a, pile);
            if (pos[0] > 0 && pos[0] < rows.size()) list.setSelectionFromTop(pos[0], pos[1]);
        }
    }

    @Override public void onHide() { savePosition(); }

    private void savePosition() {
        if (list == null || list.getChildCount() == 0) return;
        View c = list.getChildAt(0);
        ReviewStore.setPosition(a, pile, list.getFirstVisiblePosition(), c.getTop() - list.getPaddingTop());
    }

    /** The rows on screen are reviewed: every file in them. */
    @SuppressWarnings("unchecked")
    private void seen(int first, int visible) {
        if (rows.isEmpty()) return;
        List<FinalPlan.Entry> l = new ArrayList<FinalPlan.Entry>();
        for (int i = Math.max(0, first); i < Math.min(rows.size(), first + visible); i++) {
            Object[] r = rows.get(i);
            if ("t".equals(r[0])) l.addAll((List<FinalPlan.Entry>) r[1]);
            else if ("f".equals(r[0])) l.add((FinalPlan.Entry) r[1]);
        }
        if (ReviewStore.mark(a, l)) updateReview();
    }

    private void updateReview() {
        if (reviewBar == null) return;
        int n = 0;
        java.util.Set<String> keys = ReviewStore.keys(a);
        for (FinalPlan.Entry e : files) if (Piles.reviewed(e, keys)) n++;
        int t = files.size();
        reviewBar.setProgress(t == 0 ? 1000 : (int) (1000L * n / t));
        reviewText.setText(n >= t ? "✓ All " + Piles.count(t, "file") + " reviewed: they are cleaned up as shown."
                : "Reviewed " + n + " of " + Piles.count(t, "file") + ". Only files you have scrolled past are cleaned up: keep scrolling to the end.");
        reviewText.setTextColor(n >= t ? Ui.keepInk : Ui.text2);
    }

    private void updateSummary() {
        int n = 0; long b = 0;
        for (FinalPlan.Entry e : files) if (!FinalPlan.KEEP.equals(e.action)) { n++; b += e.saved(); }
        boolean smaller = Piles.SMALLER.equals(pile);
        sub.setText(Piles.count(n, "file") + (smaller ? " made smaller" : " removed") + " · frees " + Report.size(b)
                + (files.size() > n ? " · " + Piles.count(files.size() - n, "file") + " kept" : ""));
        allBtn.setText(n > 0 ? "Keep all" : smaller ? "Make all smaller" : "Remove all");
        allBtn.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
        updateReview();
    }

    /**
     * Biggest saving first: each set of copies (the copy that stays leading) by what the whole set frees, the single
     * pictures and the other files each by their biggest, and the files inside each of these biggest first.
     */
    private void layout() {
        rows.clear();
        Collections.sort(files, Piles.BY_SAVING);
        Map<String, List<FinalPlan.Entry>> sets = new LinkedHashMap<String, List<FinalPlan.Entry>>();
        List<FinalPlan.Entry> single = new ArrayList<FinalPlan.Entry>(), other = new ArrayList<FinalPlan.Entry>();
        for (FinalPlan.Entry e : files) {
            if (!picture(e.file)) { other.add(e); continue; }
            String k = PlanStore.groupKey(e.file);
            if (k == null) { single.add(e); continue; }
            List<FinalPlan.Entry> s = sets.get(k);
            if (s == null) sets.put(k, s = new ArrayList<FinalPlan.Entry>());
            s.add(e);
        }
        // blocks: {worth, kind, data}; shown biggest worth first
        List<Object[]> blocks = new ArrayList<Object[]>();
        for (Map.Entry<String, List<FinalPlan.Entry>> s : sets.entrySet()) {
            List<FinalPlan.Entry> l = new ArrayList<FinalPlan.Entry>();
            FinalPlan.Entry keeper = PlanStore.entry(a, s.getKey());
            if (keeper != null && !s.getValue().contains(keeper)) l.add(keeper);
            l.addAll(s.getValue());
            if (l.size() < 2) { single.addAll(s.getValue()); continue; }
            long w = 0;
            for (FinalPlan.Entry e : s.getValue()) w += Piles.worth(e);
            blocks.add(new Object[]{w, "set", l, keeper});
        }
        Collections.sort(single, Piles.BY_SAVING);
        if (!single.isEmpty()) blocks.add(new Object[]{Piles.worth(single.get(0)), "single", single, null});
        if (!other.isEmpty()) blocks.add(new Object[]{Piles.worth(other.get(0)), "other", other, null});
        Collections.sort(blocks, new java.util.Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) { return Long.compare((Long) y[0], (Long) x[0]); }
        });
        boolean anySet = !sets.isEmpty();
        for (Object[] b : blocks) {
            @SuppressWarnings("unchecked") List<FinalPlan.Entry> l = (List<FinalPlan.Entry>) b[2];
            if ("set".equals(b[1])) {
                FinalPlan.Entry keeper = (FinalPlan.Entry) b[3];
                FileItem focus = l.get(keeper != null && l.get(0) == keeper && l.size() > 1 ? 1 : 0).file;
                Group g = Group.of(focus);
                String head = g != null ? g.relation() : Piles.count(l.size(), "copy").replace("copys", "copies");
                long frees = 0;
                for (FinalPlan.Entry e : l) frees += e.saved();
                rows.add(new Object[]{"h", head + " · " + Report.day(focus.mtime) + (frees > 0 ? " · frees " + Report.size(frees) : ""), g != null ? focus : null});
                tiles(l, keeper != null ? keeper.file : null);
            } else if ("single".equals(b[1])) {
                if (anySet || !rows.isEmpty()) rows.add(new Object[]{"h", Piles.count(l.size(), "other photo"), null});
                tiles(l, null);
            } else {
                if (!rows.isEmpty() || blocks.size() > 1) rows.add(new Object[]{"h", Piles.count(l.size(), "file"), null});
                for (FinalPlan.Entry e : l) rows.add(new Object[]{"f", e});
            }
        }
    }

    private void tiles(List<FinalPlan.Entry> l, FileItem best) {
        for (int i = 0; i < l.size(); i += 3) rows.add(new Object[]{"t", new ArrayList<FinalPlan.Entry>(l.subList(i, Math.min(l.size(), i + 3))), best});
    }

    private void flip(FinalPlan.Entry e) {
        choose(a, Collections.singletonList(e), FinalPlan.KEEP.equals(e.action) ? acting() : FinalPlan.KEEP);
        ReviewStore.mark(a, Collections.singletonList(e));
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    // ---- words and badges shared with the item page

    static String word(String action) {
        return FinalPlan.DELETE.equals(action) ? "Remove" : FinalPlan.COMPRESS.equals(action) ? "Smaller" : "Keep";
    }

    static int solid(String action) {
        return FinalPlan.DELETE.equals(action) ? Ui.REMOVE : FinalPlan.COMPRESS.equals(action) ? Ui.SMALLER : Ui.KEEP;
    }

    /** The small pill on a picture: "Remove", "✓ Keep", "★ Best · kept", "Smaller". */
    static TextView pill(MainActivity a, String action, boolean best) {
        String w = FinalPlan.KEEP.equals(action) ? (best ? "★ Best · kept" : "✓ Keep") : FinalPlan.DELETE.equals(action) ? "✕ Remove" : "↘ Smaller";
        TextView t = Ui.text(a, w, 12, 0xFFFFFFFF, true);
        t.setBackground(Ui.round(solid(action), Ui.dp(a, 12)));
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 3), Ui.dp(a, 9), Ui.dp(a, 3));
        return t;
    }

    static String spoken(String action) {
        return FinalPlan.DELETE.equals(action) ? "will be removed" : FinalPlan.COMPRESS.equals(action) ? "will be kept and made smaller" : "will be kept";
    }

    /**
     * The badges on a picture, stacked: a removed one says "✕ Remove"; a kept one says "✓ Keep" (or "★ Best · kept"),
     * and when it is also made smaller a purple "↘ Smaller" sits under it, so a copy that stays but is compressed shows
     * both.
     */
    static LinearLayout badges(MainActivity a, String action, boolean best) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (FinalPlan.DELETE.equals(action)) { l.addView(pill(a, action, false)); return l; }
        l.addView(pill(a, FinalPlan.KEEP, best));
        if (FinalPlan.COMPRESS.equals(action)) {
            TextView s = pill(a, FinalPlan.COMPRESS, false);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = Ui.dp(a, 3);
            l.addView(s, sl);
        }
        return l;
    }

    // ---- the list

    private int cell() { return (a.getResources().getDisplayMetrics().widthPixels - dp(32) - dp(8)) / 3; }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int i) { Object t = rows.get(i)[0]; return "h".equals(t) ? 0 : "t".equals(t) ? 1 : 2; }
        @Override public boolean isEnabled(int i) { return false; }

        @SuppressWarnings("unchecked")
        public View getView(int i, View v, ViewGroup parent) {
            Object[] r = rows.get(i);
            if ("h".equals(r[0])) return header(r);
            if ("t".equals(r[0])) return tileRow((List<FinalPlan.Entry>) r[1], (FileItem) r[2]);
            return fileRow((FinalPlan.Entry) r[1]);
        }
    }

    private View header(Object[] r) {
        LinearLayout h = Ui.row(a);
        h.setPadding(0, dp(14), 0, dp(4));
        TextView t = Ui.text(a, (String) r[1], 14, Ui.text, true);
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        final FileItem focus = (FileItem) r[2];
        if (focus != null) {
            TextView cmp = Ui.text(a, "Compare", 14, Ui.accent, true);
            cmp.setMinHeight(dp(40));
            cmp.setGravity(Gravity.CENTER_VERTICAL);
            cmp.setPadding(dp(12), 0, 0, 0);
            cmp.setContentDescription("Compare these photos side by side");
            cmp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new SimilarScreen(a, focus)); } });
            h.addView(cmp);
        }
        return h;
    }

    private View tileRow(List<FinalPlan.Entry> l, FileItem best) {
        LinearLayout row = Ui.row(a);
        row.setPadding(0, dp(4), 0, 0);
        int c = cell();
        for (int k = 0; k < l.size(); k++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(c, c);
            if (k < 2) lp.rightMargin = dp(4);
            row.addView(tile(l.get(k), best, c), lp);
        }
        return row;
    }

    private View tile(final FinalPlan.Entry e, FileItem best, int c) {
        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(Ui.surface2, dp(10)));
        box.setClipToOutline(true);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        Thumbs.load(iv, e.file, c);
        boolean kept = FinalPlan.KEEP.equals(e.action);
        if (kept) {
            View ring = new View(a);
            ring.setBackground(Ui.outlineDrawable(a, Ui.KEEP, 3));
            box.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        }
        View p = badges(a, e.action, e.file == best);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); pl.setMargins(dp(6), 0, 0, dp(6));
        box.addView(p, pl);
        TextView info = Ui.text(a, "i", 14, 0xFFFFFFFF, true);
        info.setGravity(Gravity.CENTER);
        info.setBackground(Ui.round(0x99000000, dp(14)));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.END); il.setMargins(0, dp(6), dp(6), 0);
        FrameLayout hit = new FrameLayout(a);                      // a 44dp touch target around the small button
        hit.addView(info, il);
        hit.setContentDescription("Details of " + e.file.name());
        hit.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ItemScreen(a, e.file)); } });
        box.addView(hit, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END));
        box.setContentDescription(e.file.name() + ", " + spoken(e.action) + ". " + Piles.why(e) + " Double tap to "
                + (kept ? (Piles.SMALLER.equals(pile) ? "make it smaller" : "remove it") : "keep it") + ".");
        box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { flip(e); } });
        box.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { a.push(new ItemScreen(a, e.file)); return true; } });
        return box;
    }

    private View fileRow(final FinalPlan.Entry e) {
        LinearLayout row = Ui.row(a);
        row.setBackground(Ui.round(Ui.surface, dp(14)));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        row.setMinimumHeight(dp(64));
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(a, e.file.name(), 15, Ui.text, true);
        Ui.ellipsizeMiddle(name);
        mid.addView(name);
        mid.addView(Ui.text(a, Report.size(e.file.size) + " · " + Report.day(e.file.mtime), 13, Ui.text2, false));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        View p = badges(a, e.action, false);
        row.addView(p);
        TextView info = Ui.text(a, "›", 24, Ui.text2, false);
        info.setGravity(Gravity.CENTER);
        info.setContentDescription("Details of " + e.file.name());
        info.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ItemScreen(a, e.file)); } });
        row.addView(info, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.setContentDescription(e.file.name() + ", " + Report.size(e.file.size) + ", " + spoken(e.action) + ". Double tap to "
                + (FinalPlan.KEEP.equals(e.action) ? "remove it" : "keep it") + ".");
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { flip(e); } });
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(0, dp(6), 0, 0);
        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }
}
