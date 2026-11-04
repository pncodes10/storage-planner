package com.storageplanner.app;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.storageplanner.core.AppItem;
import com.storageplanner.core.Categories;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Report;
import com.storageplanner.core.Review;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reviewable list of files/apps. Four kinds: one recommendation, one storage category, all apps,
 * items kept by the user. Every row shows why it is flagged, its plan status and a Keep/Include toggle.
 */
public final class ListScreen extends Screen {
    private static final int TECH = 0, CAT = 1, APPS = 2, KEPT = 3, ACCEPTED = 4;
    private final int kind;
    private final String key;               // technique or category
    private final List<Row> rows = new ArrayList<Row>();
    private LinearLayout header;
    private BaseAdapter adapter;
    private static final Map<String, Drawable> ICONS = new HashMap<String, Drawable>();

    private ListScreen(MainActivity a, int kind, String key) { super(a); this.kind = kind; this.key = key; loadRows(); }

    public static ListScreen technique(MainActivity a, String t) { return new ListScreen(a, TECH, t); }
    public static ListScreen category(MainActivity a, String c) { return new ListScreen(a, CAT, c); }
    public static ListScreen apps(MainActivity a) { return new ListScreen(a, APPS, Categories.APPS); }
    public static ListScreen kept(MainActivity a) { return new ListScreen(a, KEPT, "Kept by you"); }
    public static ListScreen accepted(MainActivity a) { return new ListScreen(a, ACCEPTED, "Approved by you"); }

    public String title() { return key; }

    @Override public String descriptor() {
        return kind == TECH ? "list:TECH:" + key : kind == CAT ? "list:CAT:" + key : kind == APPS ? "list:APPS"
                : kind == ACCEPTED ? "list:ACCEPTED" : "list:KEPT";
    }

    private void loadRows() {
        rows.clear();
        if (kind == TECH) {
            List<Unit> us = Session.candidates.get(key);
            if (us != null) for (Unit u : us) rows.add(Row.forUnit(u));
        } else if (kind == CAT) {
            List<FileItem> fs = Categories.files(Session.result, key);
            Collections.sort(fs, new Comparator<FileItem>() { public int compare(FileItem x, FileItem y) { return Long.compare(y.size, x.size); } });
            for (FileItem f : fs) rows.add(Row.forFile(f));
        } else if (kind == APPS) {
            List<AppItem> as = new ArrayList<AppItem>(Session.result.apps);
            Collections.sort(as, new Comparator<AppItem>() {
                public int compare(AppItem x, AppItem y) { return Long.compare(y.codeBytes + y.dataBytes + y.cacheBytes, x.codeBytes + x.dataBytes + x.cacheBytes); }
            });
            for (AppItem ap : as) if (ap.codeBytes + ap.dataBytes + ap.cacheBytes > 0 || !ap.isSystem) rows.add(Row.forApp(ap));
        } else if (kind == ACCEPTED) {
            List<String> ids = new ArrayList<String>(Session.accepted.keySet());
            Collections.sort(ids);
            for (String id : ids) { Unit u = Session.unitById.get(id); if (u != null && !Session.kept.contains(id)) rows.add(Row.forUnit(u)); }
        } else {
            List<String> ids = new ArrayList<String>(Session.kept);
            Collections.sort(ids);
            for (String id : ids) { Unit u = Session.unitById.get(id); if (u != null) rows.add(Row.forUnit(u)); }
        }
    }

    protected View build() {
        ListView lv = new ListView(a);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(Ui.divider));
        lv.setDividerHeight(Math.max(1, dp(1) / 2));
        lv.setPadding(0, 0, 0, dp(24)); lv.setClipToPadding(false);
        header = new LinearLayout(a);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        lv.addHeaderView(header, null, false);
        adapter = new Adapter();
        lv.setAdapter(adapter);
        fillHeader();
        return lv;
    }

    @Override public void refresh() {
        if (kind == KEPT || kind == ACCEPTED) loadRows();
        if (header != null) fillHeader();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ header
    private void fillHeader() {
        header.removeAllViews();
        if (kind == TECH) {
            int tier = Review.tier(key, Session.candidates.containsKey(key) ? Session.candidates.get(key) : new ArrayList<Unit>());
            LinearLayout b = Ui.row(a); b.addView(Ui.badge(a, tier));
            header.addView(b);
            Ui.add(header, Ui.heading(a, key, 21), 8);
            Techniques.Info info = Techniques.info(key);
            Ui.add(header, Ui.text(a, info.what, 15, Ui.text, false), 6);
            Ui.add(header, Ui.text(a, "How to do it", 15, Ui.text, true), 12);
            Ui.add(header, Ui.text(a, info.how, 15, Ui.text2, false), 2);
            int inPlan = 0, total = 0; long planB = 0, totB = 0;
            for (Row r : rows) {
                Option o = Review.optionFor(r.units.get(0), key);
                if (r.anyKept()) continue;
                total++; totB += o.saved;
                Option ch = Session.plan.chosen.get(r.units.get(0));
                if (ch != null && ch.technique.equals(key)) { inPlan++; planB += o.saved; }
            }
            Ui.add(header, Ui.text(a, "In the plan: " + inPlan + " of " + total + " items, " + Report.size(planB)
                    + (inPlan < total ? "  ·  all " + total + ": " + Report.size(totB) : ""), 15, Ui.text, true), 12);
            if (tier >= Option.R4)
                Ui.add(header, Ui.text(a, "Each of these deletes something unique. Open an item to look at it before you decide.", 14, Ui.tier[4], true), 6);
            final int undecided = Session.undecided(key).size();
            if (undecided > 0) {
                Button cards = Ui.button(a, "Review " + undecided + " with swipe cards", true);
                cards.setContentDescription("Review the " + undecided + " planned items one by one: keep or accept each");
                cards.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new DeckScreen(a, key)); } });
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(12);
                header.addView(cards, cl);
            }
            LinearLayout btns = Ui.row(a);
            Button keepAll = Ui.button(a, "Keep all", false);
            keepAll.setContentDescription("Keep all items in " + key + ", removing them from the plan");
            keepAll.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setAll(true); } });
            Button incAll = Ui.button(a, "Include all", false);
            incAll.setContentDescription("Include all items in " + key + " in planning again");
            incAll.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setAll(false); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(12);
            btns.addView(keepAll, lp); btns.addView(incAll);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(12);
            header.addView(btns, bl);
        } else if (kind == CAT) {
            long size = 0; int flagged = 0, inPlan = 0;
            for (Row r : rows) { size += r.size(); if (r.flagged()) flagged++; if (r.chosen() != null) inPlan++; }
            Ui.add(header, Ui.heading(a, key, 21), 0);
            Ui.add(header, Ui.text(a, rows.size() + " files · " + Report.size(size) + " · " + flagged + " flagged · " + inPlan + " in the plan", 15, Ui.text, false), 6);
            Ui.add(header, Ui.text(a, "Largest first. Flagged files show why; open any file for details.", 14, Ui.text2, false), 4);
        } else if (kind == APPS) {
            Ui.add(header, Ui.heading(a, key, 21), 0);
            Ui.add(header, Ui.text(a, Session.appStats ? "App size, data and cache from Android, largest first, with when you last opened each app."
                    : "Usage access is off, so sizes and last-opened dates are not available.", 15, Ui.text2, false), 6);
        } else if (kind == ACCEPTED) {
            long total = 0;
            for (Row r : rows) { Option o = r.chosen(); if (o != null) total += o.saved; }
            Ui.add(header, Ui.heading(a, key, 21), 0);
            Ui.add(header, Ui.text(a, rows.size() + (rows.size() == 1 ? " item, " : " items, ") + Report.size(total) + ". You accepted the planned action for these.", 15, Ui.text, true), 6);
            Ui.add(header, Ui.text(a, "Storage Planner never deletes anything. Open an item to act on it yourself: view it, open it with another app, "
                    + "show it in your file manager, or open the app's settings. Undo removes the approval.", 14, Ui.text2, false), 6);
        } else {
            Ui.add(header, Ui.heading(a, key, 21), 0);
            Ui.add(header, Ui.text(a, rows.isEmpty() ? "Nothing kept. Use Keep on any item to protect it from every plan."
                    : "These items are left out of every plan, also in future scans. Tap Include to allow them again.", 15, Ui.text2, false), 6);
        }
    }

    private void setAll(boolean keep) {
        List<Unit> us = new ArrayList<Unit>();
        for (Row r : rows) us.addAll(r.units);
        Session.setKept(a, us, keep);
        refresh();
        header.announceForAccessibility(keep ? "All kept. Plan updated." : "All included. Plan updated.");
    }

    // ------------------------------------------------------------------ rows
    private static final class Holder {
        ImageView thumb; TextView ext, title, sub, flag, status; Button keep;
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            Holder h;
            if (v == null) { v = rowView(); }
            h = (Holder) v.getTag();
            bind(h, v, rows.get(i));
            return v;
        }
    }

    private View rowView() {
        Holder h = new Holder();
        LinearLayout row = Ui.row(a);
        row.setPadding(dp(16), dp(10), dp(8), dp(10));
        row.setMinimumHeight(dp(76));
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(Ui.surface2, dp(8)));
        h.thumb = new ImageView(a);
        h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.thumb.setClipToOutline(true);
        h.thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        h.ext = Ui.text(a, "", 11, Ui.text2, true);
        h.ext.setGravity(Gravity.CENTER);
        h.ext.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(h.ext, new FrameLayout.LayoutParams(-1, -1));
        box.addView(h.thumb, new FrameLayout.LayoutParams(-1, -1));
        box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND); box.setClipToOutline(true);
        row.addView(box, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        h.title = Ui.text(a, "", 15, Ui.text, true); Ui.ellipsizeMiddle(h.title);
        h.sub = Ui.text(a, "", 13, Ui.text2, false); Ui.ellipsizeMiddle(h.sub);
        h.flag = Ui.text(a, "", 13, Ui.text, false); h.flag.setMaxLines(2);
        h.status = Ui.text(a, "", 13, Ui.text2, true);
        col.addView(h.title); col.addView(h.sub); col.addView(h.flag); col.addView(h.status);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        h.keep = Ui.button(a, "Keep", false);
        h.keep.setTextSize(14);
        h.keep.setPadding(dp(14), 0, dp(14), 0);
        row.addView(h.keep, new LinearLayout.LayoutParams(-2, -2));
        row.setTag(h);
        return row;
    }

    private void bind(Holder h, View v, final Row r) {
        h.title.setText(r.title());
        String sub;
        if (r.file != null) {
            sub = r.folder() + " · " + Report.size(r.file.size) + " · " + Ui.date(r.file.mtime);
            h.thumb.setImageDrawable(null);
            h.ext.setText(ext(r.file));
            if (Thumbs.canPreview(r.file)) Thumbs.load(h.thumb, r.file, dp(56));
            else { h.thumb.setTag(null); }
        } else {
            sub = "App · " + Report.size(r.size()) + " · opened " + Ui.ago(r.app.lastUsed, Session.result.now);
            h.thumb.setTag(null);
            h.ext.setText("APP");
            h.thumb.setImageDrawable(icon(r.app.pkg));
        }
        h.sub.setText(sub);

        Option shown = kind == TECH ? Review.optionFor(r.units.get(0), key) : r.headline();
        String flagText, statusText; int flagColor = Ui.text2, statusColor = Ui.text2;
        if (shown == null) {
            flagText = "Not flagged"; statusText = "";
        } else {
            int more = r.optionCount() - 1;
            flagText = "⚑ " + (kind == TECH ? shown.why : shown.technique + ": " + shown.why) + (kind != TECH && more > 0 ? "  (+" + more + " more)" : "");
            flagColor = Ui.tier[shown.risk];
            Option ch = r.chosen();
            if (r.anyKept()) { statusText = "Kept by you · not in plan"; }
            else if (ch != null && r.anyAccepted()) {
                statusText = "✓ Approved by you · frees " + Report.size(ch.saved) + (ch.exact ? "" : " (estimate)");
                statusColor = Ui.tier[0];
            }
            else if (ch == null) {
                statusText = Session.mode == Session.MODE_SAFE && shown.risk > Option.R2 ? "Not included at this risk level" : "Not needed for your target";
            } else if (kind == TECH && !ch.technique.equals(key)) {
                statusText = "In plan as: " + ch.technique;
                statusColor = Ui.accent;
            } else {
                statusText = "✓ In plan · frees " + Report.size(ch.saved) + (ch.exact ? "" : " (estimate)");
                statusColor = Ui.accent;
            }
        }
        h.flag.setText(flagText); h.flag.setTextColor(flagColor);
        h.status.setText(statusText); h.status.setTextColor(statusColor);
        h.status.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);

        if (kind == ACCEPTED) {
            h.keep.setVisibility(View.VISIBLE);
            h.keep.setText("Undo");
            h.keep.setContentDescription("Undo approval of " + r.title());
            h.keep.setOnClickListener(new View.OnClickListener() {
                public void onClick(View b) {
                    for (Unit u : r.units) Session.setAccepted(a, u, null);
                    refresh();
                    b.announceForAccessibility("Approval removed. Plan updated.");
                }
            });
            v.setContentDescription(r.title() + ". " + sub.replace(" · ", ", ") + ". " + statusText + ". Double tap for details.");
            v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { a.push(new DetailScreen(a, r)); } });
            return;
        }
        final boolean kept = r.anyKept();
        h.keep.setVisibility(r.flagged() ? View.VISIBLE : View.GONE);
        h.keep.setText(kept ? "Include" : "Keep");
        h.keep.setContentDescription((kept ? "Include " : "Keep ") + r.title() + (kept ? " in planning again" : ", remove it from the plan"));
        h.keep.setOnClickListener(new View.OnClickListener() {
            public void onClick(View b) {
                Session.setKept(a, r.units, !kept);
                refresh();
                b.announceForAccessibility(kept ? r.title() + " included. Plan updated." : r.title() + " kept. Plan updated.");
            }
        });
        v.setContentDescription(r.title() + ". " + sub.replace(" · ", ", ") + ". "
                + (shown == null ? "Not flagged." : (kind == TECH ? "" : shown.technique + ". ") + shown.why + ". Risk: " + Ui.TIER_SPOKEN[shown.risk] + ". " + statusText + ".")
                + " Double tap for details.");
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { a.push(new DetailScreen(a, r)); } });
    }

    private Drawable icon(String pkg) {
        Drawable d = ICONS.get(pkg);
        if (d == null) {
            try { d = a.getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException e) { d = null; }
            if (d != null) ICONS.put(pkg, d);
        }
        return d;
    }

    static String ext(FileItem f) {
        if (f.type != null && !f.type.equals("other") && !f.type.equals("bin")) return f.type.toUpperCase(Locale.US);
        String n = f.name(); int i = n.lastIndexOf('.');
        return i > 0 && n.length() - i <= 6 ? n.substring(i + 1).toUpperCase(Locale.US) : "FILE";
    }
}
