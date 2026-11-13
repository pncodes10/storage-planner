package com.storageplanner.v2adapter;

import com.storageplanner.core.AppItem;
import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PlanUnit;
import com.storageplanner.v2.plan.ReasonCatalog;
import com.storageplanner.v2.plan.UnitBuilder2;
import com.storageplanner.v2.util.Text;

import java.util.*;

/**
 * Phase A (ARCHITECTURE 13, PHASE_5 11, REVIEW_2 F-13): v2 plan units and recommendations -> classic Unit/Option so the existing
 * screens work unchanged. Android-free; compiled into the JVM tests.
 *
 * <ul>
 * <li>Only {@code UnitBuilder2.Built.units} become plannable options (keepers, hash-pending deletes and unknown-byte options are
 *     already dropped there). A unit that touches a keeper is skipped entirely (asserted by tests).</li>
 * <li>Conflict-free: an app/cache option with non-empty {@code conflicts} is not emitted (its files at a better tier are), so the
 *     classic planner, which knows no conflicts, never counts a byte twice. {@code displayOnly} (included-in) units are dropped.</li>
 * <li>YOUR_CALL without the opt-in (A29), REVIEW and PROTECTED become display-only units: an option with 0 bytes added directly
 *     (Unit.add drops 0-byte options) and the unit id in {@link Adapted#notPlannable}; {@link V2Plans} never plans them.</li>
 * </ul>
 */
public final class V2Adapter {
    private V2Adapter() {}

    public static final String SAFE = "Safe · ", NO_LOSS = "No loss · ", YOUR_CALL = "Your call · ", REVIEW = "Review · ", PROTECTED = "Protected · ";

    public static final class Adapted {
        public Scanner.Result result;
        public UnitBuilder ub;
        /** Unit ids shown but never planned (YOUR_CALL without opt-in, REVIEW, PROTECTED). */
        public final Set<String> notPlannable = new HashSet<String>();
        /** Sum of the plannable options' bytes (SAFE + NO_LOSS, conflict-free). */
        public long plannableBytes;
        public int keeperUnitsSkipped, conflictOptionsDropped, includedInDropped, displayOnly;
    }

    /** Prefix of the classic technique for a v2 tier (the five tiers stay distinguishable). */
    public static String prefix(Tier t) {
        switch (t) {
            case SAFE: return SAFE;
            case NO_LOSS: return NO_LOSS;
            case YOUR_CALL: return YOUR_CALL;
            case REVIEW: return REVIEW;
            default: return PROTECTED;
        }
    }
    /** Classic risk for a plannable v2 tier: SAFE R0, NO_LOSS R1, YOUR_CALL R4 (needs confirmation), lossy compress R3. */
    public static int risk(Tier t, Action a) {
        if (t == Tier.SAFE) return Option.R0;
        if (t == Tier.NO_LOSS) return Option.R1;
        if (t == Tier.YOUR_CALL && a != null && a.transform()) return Option.R3;
        return Option.R4;
    }
    public static String action(Tier t, Action a) {
        if (t == Tier.REVIEW) return "v2_review";
        if (t == Tier.PROTECTED) return "v2_protected";
        switch (a) {
            case CLEAR_APP_CACHE: return "clear_cache";
            case ARCHIVE_APP: return "archive_app";
            case UNINSTALL_APP: return "uninstall_app";
            case COMPRESS: return "v2_compress";
            case ARCHIVE: return "v2_archive";
            default: return "delete";
        }
    }
    /** Short group label for a reason code / action (the part after the tier prefix). */
    public static String label(Action a, String code) {
        if (a == Action.CLEAR_APP_CACHE) return "App caches";
        if (a == Action.ARCHIVE_APP) return "Archive unused apps";
        if (a == Action.UNINSTALL_APP) return "Unused apps";
        if (a == Action.COMPRESS || a == Action.ARCHIVE) return "Smaller without loss";
        String l = code == null ? null : LABELS.get(code);
        return l != null ? l : "Other";
    }
    private static final Map<String, String> LABELS = new HashMap<String, String>();
    static {
        String[] m = {
            "S_TEMP_FILE", "Temporary files", "S_TRASHED", "Trash", "S_THUMBNAIL", "Thumbnails", "S_APP_CACHE_FILE", "App cache files",
            "S_APP_LOG", "App logs", "S_EXPIRED_STATUS", "Expired statuses", "S_EMPTY_FILE", "Empty files", "S_APP_CACHE", "App caches",
            "S_UNINSTALLED_LEFTOVER", "Leftovers of removed apps", "N_APP_ARCHIVE", "Archive unused apps",
            "N_EXACT_DUPLICATE", "Identical copies", "N_SAME_CONTENT_DIFFERENT_NAME", "Identical copies",
            "N_INSTALLER_INSTALLED", "Installers already installed", "N_LOSSLESS_SMALLER", "Smaller without loss",
            "Y_IDLE_APP", "Unused apps", "Y_CHAT_COPY", "Chat copies", "Y_SMALLER_COPY", "Smaller copies", "Y_CONVERTED_COPY", "Converted copies",
            "Y_SIMILAR_SHOT", "Similar shots", "Y_OLDER_INSTALLER", "Older installers", "Y_INSTALLER_UNVERIFIED", "Installers",
            "Y_OLD_CHAT_BACKUP", "Old chat backups", "Y_LEFTOVER_MEDIA", "Leftover media", "Y_LOW_QUALITY", "Low-quality pictures",
            "R_SIMILAR_SHOT", "Similar shots", "R_INSTALLER_NOT_INSTALLED", "Installers", "R_INSTALLER_SIGNER_MISMATCH", "Installers",
            "R_INSTALLER_NEWER", "Installers", "R_OLD_DOWNLOAD", "Old downloads", "R_UNREADABLE", "Unreadable files",
            "R_CONTRADICTORY", "Unclear evidence", "R_UNCERTAIN_COPY", "Possible copies", "R_UNKNOWN_DEPENDENCY", "Possibly needed",
            "R_NAME_CONTENT_MISMATCH", "Name does not match content", "R_ENCRYPTED", "Encrypted files", "R_ARCHIVE_UNSAFE", "Archives",
            "P_USER_PROTECTED", "Kept by you", "P_UNIQUE_RECORD", "Unique records", "P_CRITICAL_DEPENDENCY", "Needed by other files",
            "P_ARCHIVE_UNIQUE_CONTENT", "Archives with unique content",
            "P_KEEPER", "Kept copies",
        };
        for (int i = 0; i < m.length; i += 2) LABELS.put(m[i], m[i + 1]);
    }
    private static final String[] TIER_SENTENCE = {
        "v2 tier: Safe (rebuildable or junk).", "v2 tier: No loss (nothing unique is lost).", "v2 tier: Your call (you decide; not planned automatically).",
        "v2 tier: Review (needs a closer look; never planned).", "v2 tier: Protected (never removed)."
    };

    /** Tier name + the rendered reason sentences (unknown codes are skipped, never invented). */
    public static String why(Tier t, Recommendation r) {
        StringBuilder sb = new StringBuilder(TIER_SENTENCE[t.ordinal()]);
        if (r != null) for (String c : r.reasonCodes) {
            if (!ReasonCatalog.exists(c)) continue;
            try { sb.append(' ').append(ReasonCatalog.render(c, r.reasonParams)); } catch (RuntimeException ignored) { /* skip */ }
        }
        return sb.toString();
    }

    /**
     * @param in        the PLAN input (files, recs incl. app subjects, keepers)
     * @param built     {@code Plans.make(in, ...).built} (or {@code UnitBuilder2.build(in)})
     * @param duplicateOf copy fileId -> keeper fileId (exact groups), for the classic "copies of" links
     * @param root      absolute shared-storage root ("" if paths are absolute already)
     * @param allowYourCall the user's opt-in to YOUR_CALL (A29); false = YOUR_CALL is display-only
     */
    /**
     * The files in ascending id. A {@code SortedMap} (natural order) or a {@code StoreFileMap} is iterated directly (PHASE_S_STORE
     * 4 item 5: no copy of every id); any other map is copied and sorted as before.
     */
    public static Iterable<Map.Entry<String, FileRecord>> sortedFiles(Map<String, FileRecord> files) {
        if (files instanceof com.storageplanner.v2.plan.StoreFileMap
                || (files instanceof SortedMap && ((SortedMap<String, FileRecord>) files).comparator() == null)) return files.entrySet();
        List<String> ids = new ArrayList<String>(files.keySet());
        Collections.sort(ids);
        List<Map.Entry<String, FileRecord>> out = new ArrayList<Map.Entry<String, FileRecord>>(ids.size());
        for (String id : ids) out.add(new AbstractMap.SimpleImmutableEntry<String, FileRecord>(id, files.get(id)));
        return out;
    }

    public static Adapted adapt(UnitBuilder2.Input in, UnitBuilder2.Built built, List<InstalledApp> apps, Map<String, String> duplicateOf,
                                String root, int sdk, long now, long total, long free, boolean allowYourCall) {
        final Iterable<Map.Entry<String, FileRecord>> es = sortedFiles(in.files);
        return adapt(in, new Iterable<FileRecord>() {
            public Iterator<FileRecord> iterator() {
                final Iterator<Map.Entry<String, FileRecord>> it = es.iterator();
                return new Iterator<FileRecord>() {
                    public boolean hasNext() { return it.hasNext(); }
                    public FileRecord next() { return it.next().getValue(); }
                    public void remove() { throw new UnsupportedOperationException(); }
                };
            }
        }, built, apps, duplicateOf, root, sdk, now, total, free, allowYourCall);
    }

    /**
     * REVIEW_S F-09: the same over the files in ascending fileId given directly (e.g. {@code DiskStore.releaseIndexes()}'s
     * projection, decoded one at a time); {@code in.files} is not read.
     */
    public static Adapted adapt(UnitBuilder2.Input in, Iterable<FileRecord> filesById, UnitBuilder2.Built built, List<InstalledApp> apps,
                                Map<String, String> duplicateOf, String root, int sdk, long now, long total, long free, boolean allowYourCall) {
        Adapted a = new Adapted();
        Scanner.Result r = new Scanner.Result();
        r.now = now; r.total = total; r.free = free;
        ById byId = new ById();
        HashMap<String, String> words = new HashMap<String, String>();
        String base = root == null || root.isEmpty() ? "" : (root.endsWith("/") ? root : root + "/");
        for (FileRecord f : filesById) {
            if (f == null) continue;
            String id = f.fileId;
            if (f == null || f.presence != Presence.PRESENT || f.path == null) continue;
            FileItem fi = new FileItem(f.path, base + f.path, Math.max(0, f.sizeBytes), Unknown.knownTime(f.modifiedAt) ? f.modifiedAt : 0);
            // the few distinct area/package/type values are shared instead of one decoded String per file (slimmer result)
            fi.area = shared(words, f.area == null ? "other" : f.area);
            fi.pkg = shared(words, f.ownerPackage);
            fi.type = shared(words, f.type);
            fi.sha256 = f.sha256;
            fi.hasPhash = f.hasPhash; fi.phash = f.phash;
            r.files.add(fi);
            byId.put(id, fi);
        }
        Map<String, AppItem> appByPkg = new LinkedHashMap<String, AppItem>();
        if (apps != null) for (InstalledApp x : apps) {
            AppItem ai = appItem(x);
            appByPkg.put(ai.pkg, ai);
        }
        r.apps = new ArrayList<AppItem>(appByPkg.values());
        UnitBuilder ub = new UnitBuilder(new Scanner.Result(), new Estimates(), sdk);   // empty input yields no units (C10)
        a.result = r; a.ub = ub;
        a.includedInDropped = built.displayOnly.size();

        Set<String> used = new HashSet<String>();
        for (PlanUnit pu : built.units) {
            boolean keeper = false;
            for (String fid : pu.fileIds) if (in.keepers.contains(fid)) keeper = true;
            if (keeper) { a.keeperUnitsSkipped++; continue; }
            Unit u = unitFor(pu, byId, appByPkg, r);
            if (u == null || used.contains(u.id)) continue;
            List<PlanUnit.Option2> plannable = new ArrayList<PlanUnit.Option2>(), yourCall = new ArrayList<PlanUnit.Option2>();
            for (PlanUnit.Option2 o : pu.options) {
                if (!o.conflicts.isEmpty() && (pu.id.startsWith("app:") || pu.id.startsWith("cache:"))) { a.conflictOptionsDropped++; continue; }
                if (o.tier == Tier.SAFE || o.tier == Tier.NO_LOSS || (o.tier == Tier.YOUR_CALL && allowYourCall)) plannable.add(o);
                else if (o.tier == Tier.YOUR_CALL) yourCall.add(o);
            }
            if (!plannable.isEmpty()) {
                for (PlanUnit.Option2 o : plannable)
                    u.add(new Option(action(o.tier, o.action), prefix(o.tier) + label(o.action, o.code), o.bytes, risk(o.tier, o.action), o.exact,
                            why(o.tier, built.recById.get(o.recId))));
                if (u.options.isEmpty()) continue;
                long best = 0;
                for (Option o : u.options) best = Math.max(best, o.saved);
                a.plannableBytes += best;
                ub.units.add(u); used.add(u.id);
            } else if (!yourCall.isEmpty()) {
                PlanUnit.Option2 o = yourCall.get(0);
                display(a, ub, used, u, Tier.YOUR_CALL, o.action, o.code, built.recById.get(o.recId), o.bytes);
            }
        }
        // REVIEW and PROTECTED: visible, never selectable. Keepers and files that already have a unit are skipped.
        for (Recommendation rec : in.recs) {
            if (rec.tier != Tier.REVIEW && rec.tier != Tier.PROTECTED) continue;
            if (rec.fileId == null || in.keepers.contains(rec.fileId)) continue;
            FileItem fi = byId.get(rec.fileId);
            if (fi == null || used.contains(fi.rel)) continue;
            display(a, ub, used, Unit.forFile(fi), rec.tier, rec.proposedAction, rec.reasonCodes.isEmpty() ? null : rec.reasonCodes.get(0), rec, -1);
        }
        if (duplicateOf != null) for (Map.Entry<String, String> e : duplicateOf.entrySet()) {
            FileItem c = byId.get(e.getKey()), k = byId.get(e.getValue());
            if (c != null && k != null && !c.rel.equals(k.rel)) ub.det.duplicateOf.put(c.rel, k.rel);
        }
        return a;
    }

    private static String shared(HashMap<String, String> words, String w) {
        if (w == null) return null;
        String c = words.get(w);
        if (c == null) { words.put(w, w); c = w; }
        return c;
    }

    /**
     * fileId → FileItem of the adapted files (PHASE_S F-09 follow-up, slimmer result): the files arrive in ascending id, so the
     * ids are kept as a {@link com.storageplanner.v2.util.FileIdList} (16 B per hex id) next to the items and found by binary
     * search, instead of a HashMap holding every id String (≈ 140 B per file). Falls back to a HashMap if the order is broken.
     */
    static final class ById {
        private final com.storageplanner.v2.util.FileIdList ids = new com.storageplanner.v2.util.FileIdList();
        private final ArrayList<FileItem> items = new ArrayList<FileItem>();
        private String last;
        private HashMap<String, FileItem> map;
        void put(String id, FileItem fi) {
            if (map == null && last != null && id.compareTo(last) <= 0) {
                map = new HashMap<String, FileItem>();
                for (int i = 0; i < ids.size(); i++) map.put(ids.get(i), items.get(i));
            }
            if (map != null) { map.put(id, fi); return; }
            ids.add(id); items.add(fi); last = id;
        }
        FileItem get(String id) {
            if (id == null) return null;
            if (map != null) return map.get(id);
            int lo = 0, hi = ids.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = ids.get(mid).compareTo(id);
                if (c == 0) return items.get(mid);
                if (c < 0) lo = mid + 1; else hi = mid - 1;
            }
            return null;
        }
    }

    private static void display(Adapted a, UnitBuilder ub, Set<String> used, Unit u, Tier t, Action act, String code, Recommendation rec, long bytes) {
        String why = why(t, rec);
        if (bytes > 0) why += " It would free " + com.storageplanner.v2.plan.Fmt.bytes(bytes) + " if you chose it.";
        // added directly: Unit.add drops 0-byte options; 0 bytes and notPlannable keep it out of every plan
        u.options.add(new Option(action(t, act == null ? Action.DELETE : act), prefix(t) + label(act, code), 0, Option.R4, true, why));
        ub.units.add(u); used.add(u.id); a.notPlannable.add(u.id); a.displayOnly++;
    }

    private static Unit unitFor(PlanUnit pu, ById byId, Map<String, AppItem> apps, Scanner.Result r) {
        if (pu.id.startsWith("cache:") || pu.id.startsWith("app:")) {
            String pkg = pu.id.substring(pu.id.indexOf(':') + 1);
            AppItem ai = apps.get(pkg);
            if (ai == null) { ai = new AppItem(pkg, pkg); apps.put(pkg, ai); r.apps.add(ai); }
            return pu.id.startsWith("cache:") ? Unit.forCache(ai) : Unit.forApp(ai);
        }
        // a file or a bundle (shown on its primary; the option's bytes cover every member)
        String fid = pu.id.startsWith("bundle:") ? pu.id.substring("bundle:".length()) : pu.id;
        FileItem fi = byId.get(fid);
        return fi == null ? null : Unit.forFile(fi);
    }

    /** Display copy of an installed app; unknown sizes show as 0 (classic has no unknown), never used for planning. */
    public static AppItem appItem(InstalledApp x) {
        AppItem ai = new AppItem(x.packageName, x.label == null ? x.packageName : Text.truncate(x.label, 200));
        ai.codeBytes = Math.max(0, x.codeBytes); ai.dataBytes = Math.max(0, x.dataBytes); ai.cacheBytes = Math.max(0, x.cacheBytes);
        ai.lastUsed = x.lastUsed == Long.MIN_VALUE ? -1 : x.lastUsed;
        ai.firstInstall = x.firstInstallTime == Long.MIN_VALUE ? 0 : x.firstInstallTime;
        ai.versionCode = Math.max(0, x.versionCode);
        ai.isGame = x.game == Tri.TRUE; ai.isSystem = x.system == Tri.TRUE; ai.hasLauncher = x.hasLauncher == Tri.TRUE; ai.isEssential = x.essential == Tri.TRUE;
        return ai;
    }

    /** copy fileId -> keeper fileId for every exact group. */
    public static Map<String, String> duplicateOf(Iterable<DuplicateGroup> groups) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (DuplicateGroup g : groups) {
            if (!g.exact || g.keeperId == null) continue;
            for (String x : g.members) if (!x.equals(g.keeperId)) m.put(x, g.keeperId);
        }
        return m;
    }
}
