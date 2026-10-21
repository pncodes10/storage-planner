package com.storageplanner.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.tools.quality.PhotoQuality;
import com.storageplanner.core.Report;
import com.storageplanner.core.Review;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies of one photo or one moment: the file the algorithm keeps (best) and every file flagged because of it
 * (exact duplicates, near-identical burst shots, WhatsApp Sent copies). The user ticks which ones to delete:
 * any of them, all of them, or none.
 */
final class Group {
    static final int DUPLICATE = 1, BURST = 2, SENT = 3;

    final FileItem best;
    final List<FileItem> members = new ArrayList<FileItem>();      // best first, then the one asked about, then the rest
    private final Map<String, Integer> kind = new HashMap<String, Integer>();

    private Group(FileItem best, FileItem focus) {
        this.best = best;
        members.add(best);
        List<String> copies = Session.copiesOf.get(best.rel);
        List<FileItem> rest = new ArrayList<FileItem>();
        if (copies != null)
            for (String r : copies) {
                FileItem f = Session.fileByRel.get(r);
                if (f == null || kind.containsKey(r)) continue;
                kind.put(r, relationOf(f));
                if (f != focus) rest.add(f);
            }
        if (focus != null && focus != best && kind.containsKey(focus.rel)) members.add(focus);
        Collections.sort(rest, new Comparator<FileItem>() {                    // better shots first, then by name
            public int compare(FileItem x, FileItem y) {
                double sx = x.quality != null ? x.quality.sharp : x.sharp, sy = y.quality != null ? y.quality.sharp : y.sharp;
                if (sx != sy) return Double.compare(sy, sx);
                return x.rel.compareTo(y.rel);
            }
        });
        members.addAll(rest);
        kind.put(best.rel, dominant());
    }

    /** The group of a file, whether it is the kept one or a flagged copy; null if it has no copies. */
    static Group of(FileItem f) {
        if (f == null || Session.ub == null) return null;
        String k = keeperRel(f);
        FileItem best = k != null ? Session.fileByRel.get(k) : Session.copiesOf.containsKey(f.rel) ? f : null;
        if (best == null) return null;
        Group g = new Group(best, f);
        return g.members.size() >= 2 ? g : null;
    }

    /** The group only when this item's planned action is "delete, because another copy is kept". */
    static Group forOption(FileItem f, Option o) {
        if (f == null || o == null || !"delete".equals(o.action)) return null;
        if (!Techniques.DUPLICATES.equals(o.technique) && !Techniques.BURSTS.equals(o.technique) && !Techniques.SENT.equals(o.technique)) return null;
        return of(f);
    }

    private static String keeperRel(FileItem f) {
        String k = Session.ub.det.duplicateOf.get(f.rel);
        if (k == null) k = Session.ub.det.burstExtraOf.get(f.rel);
        if (k == null) k = Session.ub.det.sentCopyOf.get(f.rel);
        return k;
    }

    private static int relationOf(FileItem f) {
        if (Session.ub.det.duplicateOf.containsKey(f.rel)) return DUPLICATE;
        if (Session.ub.det.burstExtraOf.containsKey(f.rel)) return BURST;
        return SENT;
    }

    private int dominant() {
        boolean b = false, d = false;
        for (Map.Entry<String, Integer> e : kind.entrySet()) { if (e.getValue() == BURST) b = true; if (e.getValue() == DUPLICATE) d = true; }
        return b ? BURST : d ? DUPLICATE : SENT;
    }

    int kindOf(FileItem m) { Integer k = kind.get(m.rel); return k == null ? dominant() : k; }
    String key() { return best.rel; }
    boolean isBurst() { return dominant() == BURST; }

    /** One line for the whole group. */
    String relation() {
        boolean same = true; int k0 = -1;
        for (FileItem m : members) if (m != best) { int k = kindOf(m); if (k0 < 0) k0 = k; else if (k != k0) same = false; }
        if (!same) return "Copies of the same photo";
        switch (k0) {
            case DUPLICATE: return "Identical copies (byte for byte)";
            case BURST: return members.size() == 2 ? "Two shots of the same moment" : members.size() + " shots of the same moment";
            default: return "WhatsApp copy of a camera photo";
        }
    }

    /** Short facts under one photo: how it relates, then what the photo-quality check found. */
    String note(FileItem m) {
        int k = kindOf(m);
        if (k == DUPLICATE) return m == best ? "Original place" : "Identical copy";
        if (k == SENT) return m == best ? "Full-quality original" : "WhatsApp's compressed copy";
        List<String> bits = new ArrayList<String>();
        if (m == best) bits.add("★ Best shot");
        String eyes = PhotoQuality.eyesLabel(m.quality);
        if (eyes != null && m.quality.faces > 0) bits.add(eyes);
        if (isTop(m, 0)) bits.add("Sharpest");
        if (isTop(m, 1)) bits.add("Best light");
        StringBuilder b = new StringBuilder();
        for (String s : bits) b.append(b.length() == 0 ? "" : " · ").append(s);
        return b.length() == 0 ? "Similar shot" : b.toString();
    }

    /** Whether m is clearly the best of the group on focus (what 0) or exposure (what 1). */
    private boolean isTop(FileItem m, int what) {
        if (m.quality == null || members.size() < 2) return false;
        double mine = what == 0 ? m.quality.sharp : m.quality.exposure(), second = -1;
        for (FileItem o : members) {
            if (o == m) continue;
            if (o.quality == null) return false;
            double v = what == 0 ? o.quality.sharp : o.quality.exposure();
            if (v >= mine) return false;
            second = Math.max(second, v);
        }
        return what == 0 ? mine > 1.15 * second : mine - second > 0.05;
    }

    /** Why the algorithm keeps `best` rather than `other`. */
    List<String> whyBest(FileItem other) {
        List<String> r = new ArrayList<String>();
        int k = kindOf(other);
        if (k == DUPLICATE) { r.add("Same file; this copy is in " + folder(best)); return r; }
        if (k == SENT) { r.add("Full-quality original; the other is WhatsApp's smaller copy"); return r; }
        if (best.quality != null && other.quality != null) r.addAll(PhotoQuality.reasons(best.quality, other.quality));
        else if (best.sharp > other.sharp) r.add("Sharper");
        if (r.isEmpty()) r.add("Practically the same shot");
        return r;
    }

    /** The algorithm's suggestion: delete everything except the best one. */
    Set<String> recommended() {
        Set<String> s = new HashSet<String>();
        for (FileItem m : members) if (m != best) s.add(m.rel);
        return s;
    }

    /** What is ticked now: the user's earlier decisions where there are any, else the suggestion. */
    Set<String> current() {
        Set<String> s = new HashSet<String>();
        boolean any = false;
        for (FileItem m : members) {
            Unit u = Session.unitById.get(m.rel);
            if (u == null) continue;
            if (Session.kept.contains(u.id)) any = true;
            else if (Session.accepted.containsKey(u.id)) {
                any = true;
                Option o = Review.optionFor(u, Session.accepted.get(u.id));
                if (o != null && o.action.equals("delete")) s.add(m.rel);
            }
        }
        return any ? s : recommended();
    }

    /**
     * Records the user's choice: ticked files are approved for deletion, the others are kept.
     * Returns the earlier state of every member, for Undo.
     */
    Map<Unit, String[]> apply(Context c, Set<String> delete) {
        Map<Unit, String[]> before = new LinkedHashMap<Unit, String[]>();
        List<Unit> units = new ArrayList<Unit>();
        Set<String> ids = new HashSet<String>();
        Unit bestUnit = null;
        for (FileItem m : members) {
            Unit u = Session.unitById.get(m.rel);
            if (u == null) continue;
            units.add(u);
            before.put(u, Session.state(u));
            if (delete.contains(m.rel)) ids.add(u.id);
            if (m == best) bestUnit = u;
        }
        Review.GroupDecision d = Review.groupDecision(units, bestUnit, ids, technique(), Session.accepted);
        Session.decide(c, d.keep, d.accept, d.clear);
        return before;
    }

    long bytes(Set<String> delete) {
        long b = 0;
        for (FileItem m : members) if (delete.contains(m.rel)) b += m.size;
        return b;
    }

    private String technique() {
        switch (dominant()) {
            case BURST: return Techniques.BURSTS;
            case DUPLICATE: return Techniques.DUPLICATES;
            default: return Techniques.SENT;
        }
    }

    /** Summary of a selection, for buttons and TalkBack. */
    String summary(Set<String> delete) {
        int n = 0; for (FileItem m : members) if (delete.contains(m.rel)) n++;
        if (n == 0) return "Keep all " + members.size();
        if (n == members.size()) return "Delete all " + n;
        return "Delete " + n + ", keep " + (members.size() - n);
    }

    static String facts(FileItem f) {
        StringBuilder b = new StringBuilder();
        if (f.width > 0 && f.height > 0) b.append(f.width).append('×').append(f.height).append(" · ");
        b.append(Report.size(f.size)).append(" · ").append(Ui.date(f.mtime));
        return b.toString();
    }

    static String folder(FileItem f) {
        int s = f.rel.lastIndexOf('/');
        return s < 0 ? "Internal storage" : f.rel.substring(0, s);
    }

    /** "✕ DELETE" / "✓ KEEP" badge. Fixed colours: it always sits on the dark photo background. */
    static TextView badge(Context c, float sp) {
        TextView t = Ui.text(c, "", sp, 0xFFFFFFFF, true);
        t.setPadding(Ui.dp(c, 9), Ui.dp(c, 3), Ui.dp(c, 9), Ui.dp(c, 3));
        t.setGravity(Gravity.CENTER);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    static void setBadge(Context c, TextView t, boolean delete) {
        t.setText(delete ? "✕ DELETE" : "✓ KEEP");
        t.setBackground(Ui.round(delete ? 0xFFB3261E : 0xFF1E6B2E, Ui.dp(c, 8)));
    }

    /** Spoken description of one member and its current state. */
    String spoken(FileItem m, boolean delete) {
        return (delete ? "Marked for deletion: " : "Kept: ") + m.name() + ". " + note(m).replace("★ ", "") + ". "
                + facts(m).replace(" · ", ", ") + ".";
    }
}
