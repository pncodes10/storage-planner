package com.storageplanner.app;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.algorithms.v2_futureneed.FutureNeed;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Report;
import com.storageplanner.core.Unit;
import com.storageplanner.tools.category.PhotoCategory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything about one file or app: preview, facts, every flag and its status, the related kept copy, actions. */
public final class DetailScreen extends Screen {
    private final Row row;
    private LinearLayout content;

    private static final Map<String, String> AREA = new HashMap<String, String>();
    static {
        String[][] a = {{"camera", "Camera"}, {"pictures", "Pictures"}, {"screenshot", "Screenshot"}, {"movies", "Movies"},
            {"download", "Download"}, {"music", "Music"}, {"document", "Documents"}, {"trash", "Trash (already deleted)"},
            {"thumbnails", "Thumbnail cache"}, {"temp", "Temporary file"}, {"residue", "Left by an uninstalled app"},
            {"wa_image", "WhatsApp image"}, {"wa_sent", "WhatsApp image you sent"}, {"wa_video", "WhatsApp video"},
            {"wa_sent_video", "WhatsApp video you sent"}, {"wa_voice", "WhatsApp voice note / audio"}, {"wa_doc", "WhatsApp document"},
            {"wa_status", "WhatsApp status"}, {"wa_sticker", "WhatsApp sticker"}, {"wa_gif", "WhatsApp GIF"},
            {"wa_old_backup", "Old WhatsApp chat backup"}, {"wa_other", "WhatsApp file"}, {"telegram_media", "Telegram media"},
            {"app_obb", "Game / app asset file"}, {"app_media", "App media"}, {"user_other", "Other"}};
        for (String[] x : a) AREA.put(x[0], x[1]);
    }

    public DetailScreen(MainActivity a, Row row) { super(a); this.row = row; }

    public String title() { return row.file != null ? "File details" : "App details"; }

    @Override public String descriptor() { return "detail:" + row.ref; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        refresh();
        return sv;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        if (row.file != null) fileHeader(row.file); else appHeader();
        flags();
        if (row.file != null) related(row.file);
        actions();
    }

    // ------------------------------------------------------------------ header + facts
    private void fileHeader(final FileItem f) {
        final int k = FileKind.of(f);
        if (k == FileKind.IMAGE || k == FileKind.VIDEO || k == FileKind.PDF) {
            FrameLayout box = new FrameLayout(a);
            box.setBackground(Ui.round(Ui.surface2, dp(12)));
            box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND); box.setClipToOutline(true);
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            Thumbs.load(iv, f, Math.min(1080, a.getResources().getDisplayMetrics().widthPixels));
            if (k == FileKind.VIDEO) {
                TextView play = Ui.text(a, "\u25B6", 28, 0xFFFFFFFF, true);
                play.setGravity(android.view.Gravity.CENTER);
                play.setBackground(Ui.round(0x99000000, dp(32)));
                play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                box.addView(play, new FrameLayout.LayoutParams(dp(64), dp(64), android.view.Gravity.CENTER));
            }
            box.setContentDescription("Preview of " + f.name() + ". Double tap to " + FileKind.label(k).toLowerCase(Locale.US) + " in the app.");
            box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ViewerScreen(a, f)); } });
            content.addView(box, new LinearLayout.LayoutParams(-1, dp(280)));
        }
        TextView name = Ui.add(content, Ui.heading(a, f.name(), 20), 12);
        name.setTextIsSelectable(true);
        LinearLayout facts = Ui.card(a);
        fact(facts, "Location", row.folder());
        fact(facts, "Size", Report.size(f.size));
        fact(facts, "Modified", Ui.date(f.mtime));
        String area = AREA.containsKey(f.area) ? AREA.get(f.area) : f.area;
        fact(facts, "Kind", area + (f.type != null && !f.type.equals("other") ? " · " + f.type.toUpperCase(Locale.US) : ""));
        if (f.width > 0 && f.height > 0) fact(facts, "Dimensions", f.width + " × " + f.height
                + (Thumbs.canPreview(f) && !"mp4".equals(f.type) && !"mkv".equals(f.type) ? String.format(Locale.US, " (%.1f MP)", f.width * (double) f.height / 1e6) : ""));
        if (f.vcodec != null) fact(facts, "Video", f.vcodec.replace("video/", "").toUpperCase(Locale.US)
                + String.format(Locale.US, " · %.1f Mbps · %s", f.bitrate / 1e6, duration(f.durationMs)));
        else if (f.bitrate > 0) fact(facts, "Audio", (f.bitrate / 1000) + " kbps");
        if (f.apkPackage != null) fact(facts, "Installer for", f.apkPackage);
        if (f.category != null) {
            fact(facts, "Picture category", com.storageplanner.tools.category.PhotoCategory.label(f.category) + (f.textPending ? " (text not read yet)" : ""));
            if (f.code != null) fact(facts, "Code", f.code);
            if (f.catDate > 0) fact(facts, "Date in the picture", Report.day(f.catDate));
            if (f.faces > 0) fact(facts, "Faces", String.valueOf(f.faces));
            com.storageplanner.algorithms.Decision fn = Session.ub == null ? null : Session.ub.need.get(f.rel);
            if (fn != null) {
                StringBuilder w = new StringBuilder(com.storageplanner.algorithms.Decision.verdictLabel(fn.verdict));
                if (!fn.kindLabel.isEmpty()) w.append(" (").append(fn.kindLabel).append(")");
                for (String r : fn.reasons) w.append("\n• ").append(r);
                if (fn.deletableFrom > Session.result.now) w.append("\n• Not needed after about ").append(Report.day(fn.deletableFrom));
                w.append("\n• Decided by ").append(Session.ub.algorithm.name());
                fact(facts, "Needed later?", w.toString());
            }
            if (f.text != null) fact(facts, "Text read", f.text.length() > 300 ? f.text.substring(0, 300) + "…" : f.text);
        }
        fact(facts, "Full path", f.abs);
        addCard(facts, 12);
    }

    private void appHeader() {
        LinearLayout top = Ui.row(a);
        ImageView iv = new ImageView(a);
        try { Drawable d = a.getPackageManager().getApplicationIcon(row.app.pkg); iv.setImageDrawable(d); }
        catch (PackageManager.NameNotFoundException ignored) {}
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView name = Ui.heading(a, row.app.label, 20);
        name.setPadding(dp(12), 0, 0, 0);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout facts = Ui.card(a);
        fact(facts, "Package", row.app.pkg);
        fact(facts, "App", Report.size(row.app.codeBytes));
        fact(facts, "Data", Report.size(row.app.dataBytes));
        fact(facts, "Cache", Report.size(row.app.cacheBytes));
        fact(facts, "Last opened", Ui.ago(row.app.lastUsed, Session.result.now) + (row.app.lastUsed > 0 ? " (" + Ui.date(row.app.lastUsed) + ")" : ""));
        fact(facts, "Installed", Ui.date(row.app.firstInstall));
        if (row.app.isGame) fact(facts, "Type", "Game");
        if (row.app.isSystem) fact(facts, "Type", "Pre-installed system app");
        addCard(facts, 12);
    }

    private void fact(LinearLayout card, String label, String value) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        r.addView(Ui.text(a, label, 13, Ui.text2, false));
        TextView v = Ui.text(a, value, 15, Ui.text, false);
        v.setTextIsSelectable(true);
        r.addView(v);
        r.setContentDescription(label + ": " + value);
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ flags
    private void flags() {
        Ui.add(content, Ui.heading(a, "Why it's flagged", 18), 22);
        if (!row.flagged()) {
            Ui.add(content, Ui.text(a, "Not flagged. Nothing is recommended for this item.", 15, Ui.text2, false), 6);
            return;
        }
        boolean kept = row.anyKept();
        for (Unit u : row.units) {
            Option chosen = Session.plan.chosen.get(u);
            for (Option o : u.options) {
                LinearLayout c = Ui.card(a);
                LinearLayout top = Ui.row(a);
                top.addView(Ui.badge(a, o.risk));
                View sp = new View(a); top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
                top.addView(Ui.text(a, "frees " + Report.size(o.saved) + (o.exact ? "" : " (est.)"), 15, Ui.text, true));
                c.addView(top, new LinearLayout.LayoutParams(-1, -2));
                Ui.add(c, Ui.text(a, o.technique, 16, Ui.text, true), 8);
                Ui.add(c, Ui.text(a, o.why, 15, Ui.text2, false), 4);
                String st; int col;
                if (kept) { st = "You chose to keep this item. It is not in any plan."; col = Ui.text2; }
                else if (o == chosen && Session.isAccepted(u)) { st = "\u2713 Approved by you: locked into your plan."; col = Ui.tier[0]; }
                else if (o == chosen) { st = "✓ This is in your plan."; col = Ui.accent; }
                else if (chosen != null) { st = "Not chosen: “" + chosen.technique + "” is planned for this item instead."; col = Ui.text2; }
                else { st = Session.mode == Session.MODE_SAFE && o.risk > Option.R2 ? "Not included at this risk level." : "Not needed to reach your target."; col = Ui.text2; }
                Ui.add(c, Ui.text(a, st, 15, col, true), 8);
                c.setContentDescription(o.technique + ". Risk: " + Ui.TIER_SPOKEN[o.risk] + ". Frees " + Report.size(o.saved)
                        + (o.exact ? "" : ", estimated") + ". " + o.why + ". " + st);
                addCard(c, 10);
            }
        }
    }

    // ------------------------------------------------------------------ the copy that stays / copies elsewhere
    private void related(FileItem f) {
        String keeper = null, label = null;
        if (Session.ub.det.duplicateOf.containsKey(f.rel)) { keeper = Session.ub.det.duplicateOf.get(f.rel); label = "Identical copy that is kept"; }
        else if (Session.ub.det.burstExtraOf.containsKey(f.rel)) { keeper = Session.ub.det.burstExtraOf.get(f.rel); label = "Better shot that is kept"; }
        else if (Session.ub.det.sentCopyOf.containsKey(f.rel)) { keeper = Session.ub.det.sentCopyOf.get(f.rel); label = "Original in your camera roll (kept)"; }
        if (keeper != null && Session.fileByRel.containsKey(keeper)) {
            Ui.add(content, Ui.heading(a, label, 18), 22);
            mini(Session.fileByRel.get(keeper));
        }
        List<String> copies = Session.copiesOf.get(f.rel);
        if (copies != null && !copies.isEmpty()) {
            Ui.add(content, Ui.heading(a, "This is the copy that is kept", 18), 22);
            Ui.add(content, Ui.text(a, copies.size() + (copies.size() == 1 ? " other file is" : " other files are")
                    + " flagged because of it:", 15, Ui.text2, false), 4);
            int n = 0;
            for (String c : copies) {
                if (n++ >= 20) break;
                FileItem cf = Session.fileByRel.get(c);
                if (cf != null) mini(cf);
            }
        }
        final Group g = Group.of(f);
        if (g != null) {
            Button cmp = Ui.button(a, "Compare side by side and choose", true);
            cmp.setContentDescription("Compare the " + g.members.size() + " copies side by side and tick which ones to delete");
            cmp.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { a.push(new CompareScreen(a, g, g.current(), g.best, f == g.best ? null : f, null)); }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(10);
            content.addView(cmp, cl);
        }
    }

    private void mini(final FileItem f) {
        LinearLayout r = Ui.row(a);
        r.setBackground(Ui.round(Ui.surface, dp(10)));
        r.setPadding(dp(10), dp(8), dp(10), dp(8));
        r.setMinimumHeight(dp(64));
        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(Ui.surface2, dp(8)));
        box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND); box.setClipToOutline(true);
        TextView ext = Ui.text(a, ListScreen.ext(f), 11, Ui.text2, true); ext.setGravity(Gravity.CENTER);
        box.addView(ext, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(a); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (Thumbs.canPreview(f)) Thumbs.load(iv, f, dp(48));
        r.addView(box, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, 0, 0);
        TextView t = Ui.text(a, f.name(), 15, Ui.text, true); Ui.ellipsizeMiddle(t);
        int i = f.rel.lastIndexOf('/');
        TextView s = Ui.text(a, (i < 0 ? "" : f.rel.substring(0, i)) + " · " + Report.size(f.size), 13, Ui.text2, false); Ui.ellipsizeMiddle(s);
        col.addView(t); col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(Ui.text(a, "›", 22, Ui.text2, false));
        r.setContentDescription(f.name() + " in " + (i < 0 ? "internal storage" : f.rel.substring(0, i)) + ", " + Report.size(f.size) + ". Double tap for details.");
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new DetailScreen(a, Row.forFile(f))); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        content.addView(r, lp);
    }

    // ------------------------------------------------------------------ actions
    private void actions() {
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.VERTICAL);
        if (row.file != null) {
            final FileItem f = row.file;
            int k = FileKind.of(f);
            if (k != FileKind.NONE) {
                Button view = Ui.button(a, FileKind.label(k), true);
                view.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ViewerScreen(a, f)); } });
                add(btns, view);
            }
            LinearLayout r1 = Ui.row(a);
            Button with = Ui.button(a, "Open with\u2026", k == FileKind.NONE);
            with.setContentDescription("Open " + f.name() + " with another app");
            with.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { FileActions.openWith(a, f); } });
            Button share = Ui.button(a, "Share", false);
            share.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { FileActions.share(a, f); } });
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1); wl.rightMargin = dp(10);
            r1.addView(with, wl); r1.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(10);
            btns.addView(r1, rl);
            Button folder = Ui.button(a, "Show in folder", false);
            folder.setContentDescription("Show the folder that contains " + f.name() + " in your file manager");
            folder.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { FileActions.showInFolder(a, f); } });
            add(btns, folder);
        } else {
            Button open = Ui.button(a, "Open app settings", true);
            open.setContentDescription("Open Android settings for " + row.app.label + " to clear cache, archive or uninstall");
            open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { MediaOpen.appSettings(a, row.app.pkg); } });
            add(btns, open);
        }
        if (row.flagged() && !row.anyKept()) {
            final boolean acc = row.anyAccepted();
            final Unit planned = plannedUnit();
            if (acc || planned != null) {
                Button ab = Ui.button(a, acc ? "Undo approval" : "Accept this decision", false);
                ab.setContentDescription(acc ? "Remove your approval of the planned action" : "Accept the planned action: " + Session.plan.chosen.get(planned).technique);
                ab.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (acc) { for (Unit u : row.units) Session.setAccepted(a, u, null); }
                        else Session.setAccepted(a, planned, Session.plan.chosen.get(planned).technique);
                        refresh();
                        content.announceForAccessibility(acc ? "Approval removed. Plan updated." : "Accepted. Plan updated.");
                    }
                });
                add(btns, ab);
            }
        }
        if (row.flagged()) {
            final boolean kept = row.anyKept();
            Button k = Ui.button(a, kept ? "Include in planning again" : "Keep this — leave it out of the plan", false);
            k.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Session.setKept(a, row.units, !kept);
                    refresh();
                    content.announceForAccessibility(kept ? "Included. Plan updated." : "Kept. Plan updated.");
                }
            });
            add(btns, k);
        }
        Ui.add(btns, Ui.text(a, "Storage Planner never deletes or changes files. Use the viewer, your gallery or file manager to act.", 13, Ui.text2, false), 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(24);
        content.addView(btns, lp);
    }

    /** The unit of this row that currently has a planned action, if any. */
    private Unit plannedUnit() {
        for (Unit u : row.units) if (Session.plan.chosen.containsKey(u)) return u;
        return null;
    }

    private void add(LinearLayout p, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        p.addView(b, lp);
    }

    private void addCard(View c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top);
        content.addView(c, lp);
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
