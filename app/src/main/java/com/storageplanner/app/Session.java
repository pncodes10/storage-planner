package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.storageplanner.algorithms.Decision;
import com.storageplanner.core.AppItem;
import com.storageplanner.core.Categories;
import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.Report;
import com.storageplanner.core.Review;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory results of the last analysis + the user's review decisions (persisted). */
public final class Session {
    private Session() {}

    public static final int MODE_TARGET = 0, MODE_SAFE = 1;

    public static Scanner.Result result;
    public static UnitBuilder ub;
    public static Estimates est;
    public static long targetUsed;
    public static boolean appStats;
    public static int sdk;
    public static int mode = MODE_TARGET;
    public static final Set<String> kept = new HashSet<String>();
    /** unit id -> technique the user accepted (swiped right); locked into every plan */
    public static final Map<String, String> accepted = new HashMap<String, String>();

    public static Planner.Plan plan, safe;
    public static Map<String, List<Unit>> candidates = new HashMap<String, List<Unit>>();
    public static Map<String, Long> breakdown = new HashMap<String, Long>();
    public static final Map<String, Unit> unitById = new HashMap<String, Unit>();
    public static final Map<String, FileItem> fileByRel = new HashMap<String, FileItem>();
    /** keeper rel -> files flagged as its copies (duplicates, burst extras, Sent copies) */
    public static final Map<String, List<String>> copiesOf = new HashMap<String, List<String>>();

    /** Key in pictures of those whose content is no longer needed (FutureNeed), next to the category ids. */
    public static final String NOT_NEEDED = "not_needed";
    /** Sorted pictures by category, newest first. */
    public static final Map<String, List<FileItem>> pictures = new HashMap<String, List<FileItem>>();
    /** Pictures that are worth reading but were not read yet (time limit), and whether sorting ran at all. */
    public static int picturesPending, picturesSorted;

    public static boolean hasResults() { return result != null && ub != null && plan != null; }
    public static long used() { return result.total - result.free; }

    public static void set(Scanner.Result r, UnitBuilder b, Estimates e, long target, boolean stats, int sdkInt) {
        result = r; ub = b; est = e; targetUsed = target; appStats = stats; sdk = sdkInt;
        unitById.clear(); fileByRel.clear(); copiesOf.clear();
        for (Unit u : b.units) unitById.put(u.id, u);
        for (FileItem f : r.files) fileByRel.put(f.rel, f);
        link(b.det.duplicateOf); link(b.det.burstExtraOf); link(b.det.sentCopyOf);
        candidates = Review.candidates(b.units);
        pictures.clear(); picturesPending = 0; picturesSorted = 0;
        for (FileItem f : r.files) {
            if (f.category == null) continue;
            picturesSorted++;
            if (f.textPending) picturesPending++;
            List<FileItem> l = pictures.get(f.category);
            if (l == null) { l = new ArrayList<FileItem>(); pictures.put(f.category, l); }
            l.add(f);
            Decision fn = b.need.get(f.rel);
            if (fn != null && fn.verdict == Decision.NOT_NEEDED) {
                List<FileItem> n = pictures.get(NOT_NEEDED);
                if (n == null) { n = new ArrayList<FileItem>(); pictures.put(NOT_NEEDED, n); }
                n.add(f);
            }
        }
        for (List<FileItem> l : pictures.values())
            java.util.Collections.sort(l, new java.util.Comparator<FileItem>() {
                public int compare(FileItem x, FileItem y) { return Long.compare(y.mtime, x.mtime); }
            });
        breakdown = Categories.breakdown(r);
        replan();
    }

    private static void link(Map<String, String> m) {
        for (Map.Entry<String, String> e : m.entrySet()) {
            List<String> l = copiesOf.get(e.getValue());
            if (l == null) { l = new ArrayList<String>(); copiesOf.put(e.getValue(), l); }
            l.add(e.getKey());
        }
    }

    public static void replan() {
        if (ub == null) return;
        long need = Math.max(0, used() - targetUsed);
        safe = Review.planWithDecisions(ub.units, kept, accepted, need, MODE_SAFE);
        plan = mode == MODE_SAFE ? safe : Review.planWithDecisions(ub.units, kept, accepted, need, MODE_TARGET);
    }

    // ---------------- review decisions, remembered across scans (by path / package)
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("review", Context.MODE_PRIVATE); }

    public static void loadKept(Context c) {
        kept.clear();
        kept.addAll(prefs(c).getStringSet("kept", new HashSet<String>()));
        accepted.clear();
        for (String e : prefs(c).getStringSet("accepted", new HashSet<String>())) {
            int i = e.indexOf('\t');
            if (i > 0) accepted.put(e.substring(0, i), e.substring(i + 1));
        }
    }

    private static void saveDecisions(Context c) {
        Set<String> acc = new HashSet<String>();
        for (Map.Entry<String, String> e : accepted.entrySet()) acc.add(e.getKey() + "\t" + e.getValue());
        prefs(c).edit().putStringSet("kept", new HashSet<String>(kept)).putStringSet("accepted", acc).apply();
    }

    /** Keep = leave out of every plan (also cancels an earlier accept). */
    public static void setKept(Context c, List<Unit> units, boolean keep) {
        for (Unit u : units) { if (keep) { kept.add(u.id); accepted.remove(u.id); } else kept.remove(u.id); }
        saveDecisions(c);
        replan();
    }

    /** Accept = lock this item's planned action into the plan (also cancels an earlier keep). */
    public static void setAccepted(Context c, Unit u, String technique) {
        if (technique == null) accepted.remove(u.id);
        else { accepted.put(u.id, technique); kept.remove(u.id); }
        saveDecisions(c);
        replan();
    }

    /** Clears any decision on these units (used by Undo). */
    public static void clearDecision(Context c, List<Unit> units) {
        for (Unit u : units) { kept.remove(u.id); accepted.remove(u.id); }
        saveDecisions(c);
        replan();
    }

    /** Several decisions at once, with one save and one replan (a group of photos decided together). */
    public static void decide(Context c, java.util.Collection<Unit> keep, Map<Unit, String> accept, java.util.Collection<Unit> clear) {
        for (Unit u : clear) { kept.remove(u.id); accepted.remove(u.id); }
        for (Unit u : keep) { kept.add(u.id); accepted.remove(u.id); }
        for (Map.Entry<Unit, String> e : accept.entrySet()) { accepted.put(e.getKey().id, e.getValue()); kept.remove(e.getKey().id); }
        saveDecisions(c);
        replan();
    }

    /** {kept ? "kept" : null, accepted technique or null}: what Undo restores. */
    public static String[] state(Unit u) { return new String[]{kept.contains(u.id) ? "kept" : null, accepted.get(u.id)}; }

    /** Puts units back to earlier states (from state()), with one save and one replan. */
    public static void restore(Context c, Map<Unit, String[]> before) {
        for (Map.Entry<Unit, String[]> e : before.entrySet()) {
            String id = e.getKey().id;
            kept.remove(id); accepted.remove(id);
            if (e.getValue()[0] != null) kept.add(id);
            if (e.getValue()[1] != null) accepted.put(id, e.getValue()[1]);
        }
        saveDecisions(c);
        replan();
    }

    /** Pictures whose words or code contain the query (case-insensitive), newest first. */
    public static List<FileItem> searchPictures(String q) {
        String k = q.toLowerCase(java.util.Locale.ROOT).trim();
        List<FileItem> out = new ArrayList<FileItem>();
        if (k.isEmpty()) return out;
        for (List<FileItem> l : pictures.values())
            for (FileItem f : l)
                if (f.text != null && f.text.contains(k) || f.code != null && f.code.toLowerCase(java.util.Locale.ROOT).contains(k)
                        || f.name().toLowerCase(java.util.Locale.ROOT).contains(k)) out.add(f);
        java.util.Collections.sort(out, new java.util.Comparator<FileItem>() {
            public int compare(FileItem x, FileItem y) { return Long.compare(y.mtime, x.mtime); }
        });
        return out;
    }

    /** Approves deletion of pictures picked while browsing (their own delete option, else a picked deletion). */
    public static Map<Unit, String[]> approvePictures(Context c, List<FileItem> files) {
        Map<Unit, String[]> before = new java.util.LinkedHashMap<Unit, String[]>();
        Map<Unit, String> acc = new java.util.LinkedHashMap<Unit, String>();
        for (FileItem f : files) {
            Unit u = unitById.get(f.rel);
            if (u == null) continue;
            before.put(u, state(u));
            Option d = Review.deleteOption(u);
            acc.put(u, d != null ? d.technique : Review.CHOSEN + com.storageplanner.core.Techniques.PICKED);
        }
        decide(c, new ArrayList<Unit>(), acc, new ArrayList<Unit>());
        return before;
    }

    /** Keeps pictures picked while browsing (out of every plan). */
    public static Map<Unit, String[]> keepPictures(Context c, List<FileItem> files) {
        Map<Unit, String[]> before = new java.util.LinkedHashMap<Unit, String[]>();
        List<Unit> keep = new ArrayList<Unit>();
        for (FileItem f : files) {
            Unit u = unitById.get(f.rel);
            if (u == null) continue;
            before.put(u, state(u)); keep.add(u);
        }
        decide(c, keep, new HashMap<Unit, String>(), new ArrayList<Unit>());
        return before;
    }

    /** "deleted" (approved for deletion), "kept", "planned" (in the plan, not decided) or null. */
    public static String pictureState(FileItem f) {
        Unit u = unitById.get(f.rel);
        if (u == null) return null;
        if (kept.contains(u.id)) return "kept";
        String t = accepted.get(u.id);
        if (t != null) { Option o = Review.optionFor(u, t); if (o != null && o.action.equals("delete")) return "deleted"; return "approved"; }
        Option p = plan == null ? null : plan.chosen.get(u);
        return p != null ? "planned" : null;
    }

    public static boolean isAccepted(Unit u) { return accepted.containsKey(u.id) && !kept.contains(u.id); }

    public static int acceptedInResults() {
        int n = 0;
        for (String id : accepted.keySet()) if (unitById.containsKey(id) && !kept.contains(id)) n++;
        return n;
    }

    /** Planned items with no decision yet, in review order: needs-confirmation first, then larger first. */
    public static List<Unit> undecided(String technique) {
        List<Unit> out = new ArrayList<Unit>();
        for (Map.Entry<Unit, Option> e : plan.chosen.entrySet()) {
            Unit u = e.getKey();
            if (kept.contains(u.id) || accepted.containsKey(u.id)) continue;
            if (technique != null && !e.getValue().technique.equals(technique)) continue;
            out.add(u);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Unit>() {
            public int compare(Unit x, Unit y) {
                Option a = plan.chosen.get(x), b = plan.chosen.get(y);
                if (a.risk != b.risk) return b.risk - a.risk;
                return Long.compare(b.saved, a.saved);
            }
        });
        return out;
    }

    public static int keptInResults() {
        int n = 0;
        for (String id : kept) if (unitById.containsKey(id)) n++;
        return n;
    }

    public static Unit appUnit(AppItem a) { return unitById.get("app:" + a.pkg); }
    public static Unit cacheUnit(AppItem a) { return unitById.get("cache:" + a.pkg); }

    public static Report.Context reportContext() {
        Report.Context c = new Report.Context();
        c.total = result.total; c.free = result.free; c.used = used(); c.targetUsed = targetUsed; c.est = est; c.det = ub.det;
        c.files = result.files.size(); c.unreadableDirs = result.unreadableDirs; c.sdk = sdk; c.now = result.now;
        return c;
    }
}
