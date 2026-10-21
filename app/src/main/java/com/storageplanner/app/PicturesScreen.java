package com.storageplanner.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.algorithms.v2_futureneed.FutureNeed;
import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.core.Report;
import com.storageplanner.core.Unit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One picture category (or the results of a text search) as a grid. Tap a picture to view it; tap "Select"
 * (or long-press a picture) to pick several, then approve them for deletion or keep them. Filters: age.
 * Nothing is deleted here either: approved pictures join the plan as "Approved by you".
 */
public final class PicturesScreen extends Screen {
    private static final int[] AGE_DAYS = {0, 30, 90, 365};
    private static final String[] AGE_LABEL = {"Any age", "Older than 1 month", "Older than 3 months", "Older than 1 year"};
    private static final int ACT_SELECT = 0x7f0f0301;

    private final String category;    // null when searching
    private final String query;
    private List<FileItem> all = new ArrayList<FileItem>(), shown = new ArrayList<FileItem>();
    private int age;
    private boolean selecting;
    private final Set<String> picked = new HashSet<String>();
    private TextView summary, status;
    private Button selectBtn, ageBtn, deleteBtn, keepBtn, undoBtn;
    private LinearLayout actions;
    private Adapter adapter;
    private Map<Unit, String[]> lastUndo;
    private boolean confirmId;

    private PicturesScreen(MainActivity a, String category, String query) { super(a); this.category = category; this.query = query; }

    static PicturesScreen category(MainActivity a, String c) { return new PicturesScreen(a, c, null); }
    static PicturesScreen search(MainActivity a, String q) { return new PicturesScreen(a, null, q); }

    public String title() {
        if (Session.NOT_NEEDED.equals(category)) return "Not needed any more";
        return category != null ? PhotoCategory.label(category) : "Pictures with “" + query + "”";
    }

    @Override public String descriptor() { return category != null ? "pics:" + category : "search:" + query; }

    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        String d = Session.NOT_NEEDED.equals(category)
                ? "Pictures whose content is no longer useful: expired coupons, past trips, old one-time codes, delivered orders, small old payments, forwards. "
                  + "Records, ID documents and photos of people are never here. Open one to see why."
                : category != null ? PhotoCategory.describe(category) : "Pictures whose text, code or file name contains “" + query + "”.";
        root.addView(Ui.text(a, d, 14, Ui.text2, false), new LinearLayout.LayoutParams(-1, -2));
        summary = Ui.text(a, "", 15, Ui.text, true);
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(6);
        root.addView(summary, sl);

        LinearLayout bar = Ui.row(a);
        ageBtn = Ui.button(a, AGE_LABEL[0], false);
        ageBtn.setContentDescription("Filter by age: " + AGE_LABEL[0] + ". Double tap to change.");
        ageBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                age = (age + 1) % AGE_DAYS.length;
                ageBtn.setText(AGE_LABEL[age]);
                ageBtn.setContentDescription("Filter by age: " + AGE_LABEL[age] + ". Double tap to change.");
                filter();
            }
        });
        selectBtn = Ui.button(a, "Select", false);
        selectBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setSelecting(!selecting); } });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1); b1.rightMargin = dp(8);
        bar.addView(ageBtn, b1); bar.addView(selectBtn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(8);
        root.addView(bar, bl);

        GridView grid = new GridView(a);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(4)); grid.setVerticalSpacing(dp(4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1); gl.topMargin = dp(8);
        root.addView(grid, gl);

        status = Ui.text(a, "", 13, Ui.text2, false);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        actions = Ui.row(a);
        deleteBtn = Ui.button(a, "Approve deletion", true);
        deleteBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { approve(); } });
        keepBtn = Ui.button(a, "Keep", false);
        keepBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { keep(); } });
        undoBtn = Ui.button(a, "Undo", false);
        undoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { undo(); } });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1); a1.rightMargin = dp(8);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(-2, -2); a2.rightMargin = dp(8);
        actions.addView(deleteBtn, a1); actions.addView(keepBtn, a2); actions.addView(undoBtn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(6);
        root.addView(actions, al);
        load();
        setSelecting(false);
        return root;
    }

    @Override public void refresh() { if (adapter != null) { load(); } }

    private void load() {
        all = category != null ? (Session.pictures.containsKey(category) ? Session.pictures.get(category) : new ArrayList<FileItem>())
                               : Session.searchPictures(query);
        filter();
    }

    private void filter() {
        shown = new ArrayList<FileItem>();
        long now = Session.result.now;
        for (FileItem f : all) if (AGE_DAYS[age] == 0 || now - f.mtime > AGE_DAYS[age] * 86400000L) shown.add(f);
        picked.retainAll(rels(shown));
        long bytes = 0; for (FileItem f : shown) bytes += f.size;
        summary.setText(shown.size() + (shown.size() == 1 ? " picture" : " pictures") + " · " + Report.size(bytes)
                + (category == null && shown.isEmpty() ? " — nothing found" : ""));
        adapter.notifyDataSetChanged();
        updateActions();
    }

    private static Set<String> rels(List<FileItem> l) { Set<String> s = new HashSet<String>(); for (FileItem f : l) s.add(f.rel); return s; }

    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) picked.clear();
        confirmId = false;
        selectBtn.setText(on ? "Done" : "Select");
        selectBtn.setContentDescription(on ? "Stop selecting" : "Select pictures to approve for deletion or keep");
        adapter.notifyDataSetChanged();
        updateActions();
    }

    private void updateActions() {
        int n = picked.size(); long b = 0;
        for (FileItem f : shown) if (picked.contains(f.rel)) b += f.size;
        actions.setVisibility(selecting || lastUndo != null ? View.VISIBLE : View.GONE);
        deleteBtn.setEnabled(n > 0); keepBtn.setEnabled(n > 0);
        deleteBtn.setVisibility(selecting ? View.VISIBLE : View.GONE); keepBtn.setVisibility(selecting ? View.VISIBLE : View.GONE);
        undoBtn.setVisibility(lastUndo != null ? View.VISIBLE : View.GONE);
        deleteBtn.setText(confirmId ? "Tap again: ID documents" : n == 0 ? "Approve deletion" : "Approve deletion (" + n + ")");
        status.setText(selecting ? (n == 0 ? "Tap pictures to pick them." : n + " picked · " + Report.size(b) + ". Long-press a picture to view it.") : "");
    }

    private List<FileItem> pickedFiles() {
        List<FileItem> l = new ArrayList<FileItem>();
        for (FileItem f : shown) if (picked.contains(f.rel)) l.add(f);
        return l;
    }

    private void approve() {
        List<FileItem> l = pickedFiles();
        if (l.isEmpty()) return;
        boolean ids = false; for (FileItem f : l) if (PhotoCategory.ID.equals(f.category)) ids = true;
        if (ids && !confirmId) {                              // sensitive: ask for a second tap, no dialog
            confirmId = true; updateActions();
            a.toast("Some of these look like ID documents. Tap again to approve them anyway.");
            return;
        }
        long b = 0; for (FileItem f : l) b += f.size;
        lastUndo = Session.approvePictures(a, l);
        a.toast(l.size() + (l.size() == 1 ? " picture" : " pictures") + " (" + Report.size(b) + ") approved for deletion. They are listed under “You approved”.");
        setSelecting(false);
        load();
    }

    private void keep() {
        List<FileItem> l = pickedFiles();
        if (l.isEmpty()) return;
        lastUndo = Session.keepPictures(a, l);
        a.toast(l.size() + (l.size() == 1 ? " picture is" : " pictures are") + " kept: left out of every plan.");
        setSelecting(false);
        load();
    }

    private void undo() {
        if (lastUndo == null) return;
        Session.restore(a, lastUndo);
        lastUndo = null;
        a.toast("Undone.");
        load();
    }

    private void toggle(FileItem f) {
        if (!picked.remove(f.rel)) picked.add(f.rel);
        confirmId = false;
        adapter.notifyDataSetChanged();
        updateActions();
    }

    // ------------------------------------------------------------------ grid
    private final class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            Cell c = v == null ? new Cell() : (Cell) v.getTag();
            final FileItem f = shown.get(i);
            c.bind(f);
            return c.root;
        }
    }

    private final class Cell {
        final FrameLayout root = new FrameLayout(a);
        final ImageView iv = new ImageView(a);
        final TextView caption = Ui.text(a, "", 11, 0xFFFFFFFF, false), state = Ui.text(a, "", 11, 0xFFFFFFFF, true);
        final Tick tick = new Tick(a);
        FileItem f;

        Cell() {
            int size = (a.getResources().getDisplayMetrics().widthPixels - dp(32)) / 3;
            root.setLayoutParams(new AbsListView.LayoutParams(-1, size));
            root.setBackgroundColor(0xFF101014);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            caption.setBackgroundColor(0x99000000);
            caption.setPadding(dp(4), dp(2), dp(4), dp(2));
            Ui.ellipsizeMiddle(caption);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            state.setPadding(dp(6), dp(2), dp(6), dp(2));
            state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); sl.setMargins(0, dp(4), dp(4), 0);
            root.addView(state, sl);
            tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(tick, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));
            root.setTag(this);
            root.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (selecting) toggle(f); else a.push(new ViewerScreen(a, f)); }
            });
            root.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    if (selecting) a.push(new ViewerScreen(a, f));
                    else { setSelecting(true); toggle(f); }
                    return true;
                }
            });
            root.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View h, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(h, info);
                    if (!selecting) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACT_SELECT, "Select"));
                    if (selecting) { info.setCheckable(true); info.setChecked(f != null && picked.contains(f.rel)); }
                }
                @Override public boolean performAccessibilityAction(View h, int action, Bundle args) {
                    if (action == ACT_SELECT) { setSelecting(true); toggle(f); return true; }
                    return super.performAccessibilityAction(h, action, args);
                }
            });
        }

        void bind(FileItem file) {
            f = file;
            Thumbs.load(iv, f, dp(140));
            String date = f.catDate > 0 ? Report.day(f.catDate) : Ui.date(f.mtime);
            FutureNeed.Assessment fn = Session.NOT_NEEDED.equals(category) && Session.ub != null ? Session.ub.need.get(f.rel) : null;
            caption.setText(fn != null ? FutureNeed.label(fn.kind) : f.code != null ? f.code : date + " · " + Report.size(f.size));
            String st = Session.pictureState(f);
            if ("deleted".equals(st)) { state.setText("✕ approved"); state.setBackground(Ui.round(0xE6B3261E, dp(6))); state.setVisibility(View.VISIBLE); }
            else if ("kept".equals(st)) { state.setText("✓ kept"); state.setBackground(Ui.round(0xE61E6B2E, dp(6))); state.setVisibility(View.VISIBLE); }
            else if ("planned".equals(st)) { state.setText("in plan"); state.setBackground(Ui.round(0xCC1A56C4, dp(6))); state.setVisibility(View.VISIBLE); }
            else state.setVisibility(View.GONE);
            tick.setVisibility(selecting ? View.VISIBLE : View.GONE);
            tick.setChecked(picked.contains(f.rel));
            String spoken = PhotoCategory.label(f.category) + ", " + f.name() + ", " + date + ", " + Report.size(f.size)
                    + (f.code != null ? ", " + f.code : "") + (fn != null ? ", not needed: " + fn.why : "")
                    + ("deleted".equals(st) ? ", approved for deletion" : "kept".equals(st) ? ", kept by you" : "planned".equals(st) ? ", in the plan" : "")
                    + (selecting ? (picked.contains(f.rel) ? ", picked" : ", not picked") : "");
            root.setContentDescription(spoken);
        }
    }
}
