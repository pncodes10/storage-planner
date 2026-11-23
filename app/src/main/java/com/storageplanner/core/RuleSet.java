package com.storageplanner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.storageplanner.core.Rule.Action;
import com.storageplanner.core.Rule.Condition;
import com.storageplanner.core.Rule.Saving;

/**
 * An ordered list of rules: the built-in rules (each can be switched off or have its numbers changed) followed
 * by the user's own rules. It decides, for one file, which options the file gets.
 *
 * Order of work for one file:
 *  1. Delete rules, top to bottom. A file keeps only its lowest-risk delete (Unit.add; the first one on a tie).
 *     A rule marked onlyIfNoOtherOption fires only while the file has no option yet.
 *  2. Re-encode rules, top to bottom. The first rule whose conditions match ends the search, even if its saving
 *     turns out to be 0 (that is how the old if / else-if chain worked).
 *  3. Safety rails that no rule can override: a matching KEEP rule removes every option of the file; an ID
 *     document keeps no delete option except "exact duplicate".
 *
 * Limits on rules written by the user, enforced here and not only on screen: a delete is always R4 (needs the
 * user's confirmation), a re-encode is always R2, a delete always saves the whole file, the technique is
 * "My rule: name", KEEP makes no option.
 */
public final class RuleSet {
    public static final int VERSION = 1;
    private static final String HEADER = "storage-planner-rules";

    public final List<Rule> rules = new ArrayList<Rule>();
    /** Things found while loading from text (unknown tag, newer format, ...). Empty for defaults. */
    public final List<String> warnings = new ArrayList<String>();

    public static RuleSet defaults() {
        RuleSet s = new RuleSet();
        s.rules.addAll(BuiltInRules.all());
        return s;
    }

    public Rule find(String id) {
        for (Rule r : rules) if (r.id.equals(id)) return r;
        return null;
    }

    /** Adds a new user rule at the end with an id that is not used yet; fill in its conditions afterwards. */
    public Rule addUser(String name, Action action) {
        Rule r = Rule.user(name, action);
        r.id = freeId();
        rules.add(r);
        return r;
    }

    private String freeId() {
        for (int i = 1; ; i++) if (find("user_" + i) == null) return "user_" + i;
    }

    // ------------------------------------------------------------------ running
    /** Gives one file its options (and removes them again where a KEEP rule or the ID document rail says so). */
    public void apply(Unit u, FileItem f, Tags.T t, Tags.Context ctx, Estimates est, long now) {
        compile().apply(u, f, t, ctx, est, now);
    }

    /** A fixed copy of what is switched on, quick to run for many files. Do not change the rules while it is in use. */
    public Compiled compile() {
        Compiled c = new Compiled();
        for (Rule r : rules) {
            if (!r.enabled || r.problem() != null) continue;
            if (r.action == Action.KEEP) c.keep.add(r);
            else if (r.action == Action.PROTECT) c.protect.add(r);
            else if (r.action == Action.DELETE) c.deletes.add(r);
            else c.compress.add(r);
        }
        return c;
    }

    public static final class Compiled {
        final List<Rule> keep = new ArrayList<Rule>(), deletes = new ArrayList<Rule>(), compress = new ArrayList<Rule>(), protect = new ArrayList<Rule>();

        public void apply(Unit u, FileItem f, Tags.T t, Tags.Context ctx, Estimates est, long now) {
            for (int i = 0; i < keep.size(); i++) if (keep.get(i).matches(t)) { u.options.clear(); return; }
            for (int i = 0; i < deletes.size(); i++) {
                Rule r = deletes.get(i);
                if (r.onlyIfNoOtherOption && !u.options.isEmpty()) continue;
                if (r.matches(t)) emit(u, r, f, t, ctx, est, now);
            }
            for (int i = 0; i < compress.size(); i++) {
                Rule r = compress.get(i);
                if (!r.matches(t)) continue;
                emit(u, r, f, t, ctx, est, now);
                break;
            }
            for (int i = 0; i < protect.size(); i++) {
                if (!protect.get(i).matches(t)) continue;
                for (Iterator<Option> it = u.options.iterator(); it.hasNext(); ) if (protectedFrom(it.next())) it.remove();
                break;
            }
            if (t.idDocument) {                                  // ID documents: only an identical extra copy may go
                for (Iterator<Option> it = u.options.iterator(); it.hasNext(); ) {
                    Option o = it.next();
                    if (o.action.equals("delete") && !o.technique.equals(Techniques.DUPLICATES)) it.remove();
                }
            }
        }

        /** A delete a protect rule takes away: all but an identical copy, a sent copy (the original is kept) or a worse shot of a burst. */
        static boolean protectedFrom(Option o) {
            return o.action.equals("delete") && !o.technique.equals(Techniques.DUPLICATES) && !o.technique.equals(Techniques.SENT)
                    && !o.technique.equals(Techniques.BURSTS);
        }

        private static void emit(Unit u, Rule r, FileItem f, Tags.T t, Tags.Context ctx, Estimates est, long now) {
            Saving sv = effectiveSaving(r);
            int risk = r.effectiveRisk();
            String tech = r.builtIn ? r.technique : Rule.USER_PREFIX + r.name;
            long saved = sv.of(f, est);
            if (saved <= 0) return;
            String why;
            if ("burst".equals(r.whyHook)) why = UnitBuilder.burstWhy(f, ctx.byRel.get(t.burstKeptShot));
            else if (r.why == null || r.why.length() == 0) why = r.builtIn ? "" : "Matches your rule \"" + r.name + "\"";
            else why = fill(r.why, f, t, ctx, est, now);
            u.add(new Option(r.action.option, tech, saved, risk, sv.exact(), why));
        }
    }

    /** How the saving of a rule is counted. Limits for the user's own rules: a delete saves the whole file; a re-encode never counts whole files. */
    static Saving effectiveSaving(Rule r) {
        Saving sv = r.saving == null ? Saving.defaultFor(r.action) : r.saving;
        if (r.builtIn) return sv;
        return r.action == Action.DELETE ? Saving.FILE_SIZE : (sv == Saving.FILE_SIZE ? Saving.defaultFor(r.action) : sv);
    }

    /**
     * Fills the {placeholders} of a why text: age (days, rounded), name, size_mb, dup_of, sent_of, burst_of, pkg, app_label,
     * date (the date read in the picture), kbps, mbps, photo_source, chat_source, screenshot_source. Unknown ones stay as written.
     */
    public static String fill(String tpl, FileItem f, Tags.T t, Tags.Context ctx, Estimates est, long now) {
        if (tpl.indexOf('{') < 0) return tpl;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < tpl.length()) {
            int o = tpl.indexOf('{', i);
            int c = o < 0 ? -1 : tpl.indexOf('}', o);
            if (c < 0) { b.append(tpl, i, tpl.length()); break; }
            b.append(tpl, i, o);
            String key = tpl.substring(o + 1, c), v = placeholder(key, f, t, ctx, est, now);
            b.append(v == null ? tpl.substring(o, c + 1) : v);
            i = c + 1;
        }
        return b.toString();
    }

    private static String placeholder(String key, FileItem f, Tags.T t, Tags.Context ctx, Estimates est, long now) {
        switch (key) {
            case "age": return String.valueOf(Math.round(f.ageDays(now)));
            case "name": return f.name();
            case "size_mb": return String.format(Locale.US, "%.1f", f.size / 1e6);
            case "dup_of": return t.duplicateOf;
            case "sent_of": return t.sentCopyOf;
            case "burst_of": return t.burstKeptShot;
            case "pkg": return String.valueOf(f.pkg);
            case "app_label": {
                AppItem a = f.apkPackage == null ? null : ctx.apps.get(f.apkPackage);
                return a == null ? String.valueOf(f.apkPackage) : a.label;
            }
            case "date": return f.catDate > 0 ? Report.day(f.catDate) : "";
            case "kbps": return String.valueOf(f.bitrate / 1000);
            case "mbps": return String.format("%.1f", f.bitrate / 1e6);
            case "photo_source": return est.photoSource;
            case "chat_source": return est.chatSource;
            case "screenshot_source": return est.screenshotSource;
            default: return null;
        }
    }

    // ------------------------------------------------------------------ editing
    /** A rule set that can be changed without touching this one (the rules screen edits a copy and saves it when the user is done). */
    public RuleSet copy() {
        RuleSet s = new RuleSet();
        for (Rule r : rules) s.rules.add(r.copy());
        s.warnings.addAll(warnings);
        return s;
    }

    /** Puts an edited rule back: replaces the rule with the same id, or adds it at the end (a user rule without an id gets a new one). */
    public void put(Rule r) {
        if (r.id == null || r.id.length() == 0) r.id = freeId();
        for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(r.id)) { rules.set(i, r); return; }
        rules.add(r);
    }

    /** Removes a rule of the user; a built-in rule can only be switched off. */
    public boolean remove(String id) {
        for (int i = 0; i < rules.size(); i++)
            if (rules.get(i).id.equals(id)) {
                if (rules.get(i).builtIn) return false;
                rules.remove(i);
                return true;
            }
        return false;
    }

    /** Puts a built-in rule back to what the app ships (conditions and on). */
    public boolean reset(String id) {
        for (Rule d : BuiltInRules.all())
            if (d.id.equals(id))
                for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(id)) { rules.set(i, d); return true; }
        return false;
    }

    /** True for a built-in rule that is switched off or has other numbers than the app ships. */
    public static boolean changed(Rule r) {
        if (!r.builtIn) return false;
        for (Rule d : BuiltInRules.all())
            if (d.id.equals(r.id)) {
                if (d.enabled != r.enabled || d.when.size() != r.when.size()) return true;
                for (int i = 0; i < d.when.size(); i++) if (!d.when.get(i).toText().equals(r.when.get(i).toText())) return true;
                return false;
            }
        return false;
    }

    public static final int MAX_NAME = 60;
    /** Tags that only say what format a file has: a delete rule with nothing else would hit every file of that format. */
    private static boolean formatOnly(Condition c) { return c.tag.equals(Tags.KIND) || c.tag.equals(Tags.FILE_TYPE); }

    /**
     * Why a rule cannot be saved, in words for the user; empty if it can. The rule is one of this set (same id) or a new one.
     * A user rule needs a name that no other rule of the user has (the name is how its suggestions are grouped), at least one
     * condition that makes sense, and, if it deletes, a condition that says more than the format of the file.
     */
    public List<String> problems(Rule r) {
        List<String> out = new ArrayList<String>();
        if (r.action == null) { out.add("Choose what the rule does."); return out; }
        if (!r.builtIn) {
            String n = r.name == null ? "" : r.name.trim();
            if (n.length() == 0) out.add("Give the rule a name.");
            else if (n.length() > MAX_NAME) out.add("The name is too long (at most " + MAX_NAME + " letters).");
            else for (Rule o : rules)
                if (!o.builtIn && !o.id.equals(r.id) && o.name != null && o.name.trim().equalsIgnoreCase(n)) { out.add("You already have a rule with this name. Choose another."); break; }
        }
        if (r.when.isEmpty()) out.add("Add at least one condition.");
        if (r.fileType != null && !Rule.knownFileType(r.fileType)) out.add("Choose which files the rule is for.");
        else {
            String need = Rule.kindFor(r.action), have = Rule.kindOf(r.fileType);
            if (need != null && !Rule.ANY_FILE.equals(r.fileType) && !need.equals(have))
                out.add("“" + RuleText.action(r.action) + "” only works on " + RuleText.kindWords(need)
                        + ", but this rule is for " + RuleText.fileType(r.fileType).toLowerCase(Locale.ROOT) + ". Choose another file type or action.");
        }
        for (Condition c : r.when) {
            String p = c.problem();
            if (p != null) out.add("\"" + RuleText.condition(c) + "\": " + p + ".");
        }
        if (!r.builtIn && r.action == Action.DELETE && !r.when.isEmpty()) {
            boolean more = false;
            for (Condition c : r.when) if (!formatOnly(c)) more = true;
            if (!more) out.add("A delete rule needs a condition that says more than the kind of file, such as its age, where it comes from or its category.");
        }
        return out;
    }

    // ------------------------------------------------------------------ matches (the rules screen and the live preview)
    /** A delete rule that matches more than this share of all files is called broad: the editor warns in words. */
    public static final double BROAD_SHARE = 0.25;

    /** What one rule does to this scan. */
    public static final class Matches {
        public int total;           // files in the scan
        public int count;           // files the rule would act on
        public long bytes;          // their size
        public long saved;          // what it would free (delete: the size; compress: an estimate; keep: the size it protects)
        public int idProtected;     // ID documents a delete rule matches but is never allowed to touch
        public int alsoSuggested;   // of the files above, those another rule already gives an option (keep rule: suggestions it would remove)
        public int overridden;      // compress rule: files an earlier re-encode rule already claims (only the first match counts)
        public final List<FileItem> files = new ArrayList<FileItem>();    // the largest first, at most the limit asked for

        public boolean broad(Rule r) {
            return r != null && r.action == Action.DELETE && !r.builtIn && total > 0 && count > BROAD_SHARE * total;
        }
    }

    /** Applies the rails every rule runs under, and counts the file if the rule would act on it. */
    private static boolean hit(Matches m, Rule r, FileItem f, Tags.T t, Estimates est) {
        if (!r.matches(t)) return false;
        if (r.action == Action.DELETE && t.idDocument) { m.idProtected++; return false; }     // the ID document rail
        long saved = r.action == Action.KEEP || r.action == Action.PROTECT ? f.size : effectiveSaving(r).of(f, est);
        if (saved <= 0) return false;                                                           // no option would be made
        m.count++; m.bytes += f.size; m.saved += saved;
        return true;
    }

    /**
     * What the rule {@code r} would do to this scan, with the same engine and rails as the planner (a delete never touches an
     * ID document; a re-encode that saves nothing makes no option). {@code r} may be one of this set (its own entry is then
     * left out when counting "already suggested") or a rule being written; it counts whether or not it is switched on.
     * limit = how many matching files to list, largest first.
     */
    public Matches matches(Rule r, Scanner.Result res, Tags.Context ctx, Estimates est, long now, int limit) {
        Matches m = new Matches();
        m.total = res.files.size();
        if (r.action == null || r.problem() != null) return m;
        RuleSet rest = new RuleSet();
        List<Rule> earlier = new ArrayList<Rule>();           // enabled re-encode rules above r: they win a file first
        boolean seen = false;
        for (Rule o : rules) {
            if (r.id != null && o.id.equals(r.id)) { seen = true; continue; }
            rest.rules.add(o);
            if (!seen && r.action.compress() && o.enabled && o.problem() == null && o.action.compress()) earlier.add(o);
        }
        Compiled others = rest.compile();
        List<FileItem> hits = new ArrayList<FileItem>();
        for (FileItem f : res.files) {
            Tags.T t = Tags.of(f, ctx, now);
            if (!hit(m, r, f, t, est)) continue;
            hits.add(f);
            for (Rule e : earlier) if (e.matches(t)) { m.overridden++; break; }
            Unit u = Unit.forFile(f);
            others.apply(u, f, t, ctx, est, now);
            if (!u.options.isEmpty()) m.alsoSuggested++;
        }
        java.util.Collections.sort(hits, new java.util.Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) { return a.size != b.size ? (a.size > b.size ? -1 : 1) : a.rel.compareTo(b.rel); }
        });
        for (int i = 0; i < hits.size() && i < limit; i++) m.files.add(hits.get(i));
        return m;
    }

    /** One pass over the scan for every rule that can run (also those switched off): rule id -> count, size and saving (no file list). */
    public Map<String, Matches> tally(Scanner.Result res, Tags.Context ctx, Estimates est, long now) {
        Map<String, Matches> out = new HashMap<String, Matches>();
        List<Rule> live = new ArrayList<Rule>();
        for (Rule r : rules) {
            Matches m = new Matches();
            m.total = res.files.size();
            out.put(r.id, m);
            if (r.action != null && r.problem() == null) live.add(r);
        }
        for (FileItem f : res.files) {
            Tags.T t = Tags.of(f, ctx, now);
            for (int i = 0; i < live.size(); i++) hit(out.get(live.get(i).id), live.get(i), f, t, est);
        }
        return out;
    }

    // ------------------------------------------------------------------ text
    /*
     * Format (one block per rule; lines "key value"; blank lines and lines starting with # are ignored):
     *   storage-planner-rules 1
     *   rule old_greeting          <- a built-in: only enabled and when are saved; the rest comes from the app
     *   builtin yes
     *   enabled yes
     *   when category = greeting
     *   when age_days > 7
     *   end
     *   rule user_1                <- the user's own rule
     *   name Old chat graphics
     *   enabled yes
     *   action delete              <- delete, reencode_photo, lossless_image, reencode_video, reencode_audio, keep
     *   saving file_size           <- how the saving is counted (re-encode rules)
     *   filetype jpeg              <- which files: any, a kind (image, video, audio, document, apk, other) or a format
     *                                 (Rule.FILE_TYPES); missing = any (rules saved before file types existed)
     *   when chat_media is yes
     *   when NOT faces > 0
     *   why Chat graphic from {age} days ago
     *   end
     * name and why may contain \n and \\ for a new line and a backslash.
     */
    public String toText() {
        StringBuilder b = new StringBuilder();
        b.append(HEADER).append(' ').append(VERSION).append('\n');
        for (Rule r : rules) {
            b.append("\nrule ").append(r.id).append('\n');
            if (r.builtIn) b.append("builtin yes\n");
            else b.append("name ").append(esc(r.name)).append('\n');
            b.append("enabled ").append(r.enabled ? "yes" : "no").append('\n');
            if (!r.builtIn) {
                b.append("action ").append(r.action != null ? r.action.text : r.unknownAction).append('\n');
                b.append("saving ").append(r.saving == null ? "" : r.saving.name().toLowerCase(Locale.ROOT)).append('\n');
            }
            if (!r.builtIn) b.append("filetype ").append(r.fileType == null ? Rule.ANY_FILE : r.fileType).append('\n');
            for (Condition c : r.when) b.append("when ").append(c.toText()).append('\n');
            if (!r.builtIn) b.append("why ").append(esc(r.why)).append('\n');
            b.append("end\n");
        }
        return b.toString();
    }

    /** Reads toText(). Never throws: a rule it cannot run is loaded switched off, and the reason is added to warnings. */
    public static RuleSet fromText(String text) {
        RuleSet out = new RuleSet();
        Map<String, Rule> defaults = new HashMap<String, Rule>();
        List<Rule> defList = BuiltInRules.all();
        for (Rule r : defList) defaults.put(r.id, r);
        List<Rule> user = new ArrayList<Rule>();
        Set<String> seen = new HashSet<String>();
        String[] lines = text == null ? new String[0] : text.split("\r?\n", -1);
        int i = 0;
        while (i < lines.length && (lines[i].trim().isEmpty() || lines[i].startsWith("#"))) i++;
        boolean header = i < lines.length && lines[i].startsWith(HEADER + " ");
        int version = 0;
        if (header) try { version = Integer.parseInt(lines[i].substring(HEADER.length() + 1).trim()); } catch (NumberFormatException e) { header = false; }
        if (!header) { out.warnings.add("not a rules text; using the built-in rules"); out.rules.addAll(defList); return out; }
        if (version > VERSION) { out.warnings.add("rules saved by a newer version; using the built-in rules"); out.rules.addAll(defList); return out; }
        Rule cur = null;
        boolean curBuiltIn = false, curHasWhen = false;
        for (i++; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.trim().isEmpty() || ln.startsWith("#")) continue;
            int sp = ln.indexOf(' ');
            String key = sp < 0 ? ln : ln.substring(0, sp), val = sp < 0 ? "" : ln.substring(sp + 1);
            if (key.equals("rule")) {
                cur = new Rule(); cur.id = val.trim(); cur.name = cur.id; cur.technique = Rule.USER_PREFIX + cur.id;
                cur.saving = null; cur.why = ""; curBuiltIn = false; curHasWhen = false;
                continue;
            }
            if (cur == null) continue;
            if (key.equals("end")) {
                finish(out, cur, curBuiltIn, curHasWhen, defaults, user, seen);
                cur = null;
            } else if (key.equals("builtin")) curBuiltIn = val.trim().equals("yes");
            else if (key.equals("name")) { cur.name = unesc(val); cur.technique = Rule.USER_PREFIX + cur.name; }
            else if (key.equals("enabled")) cur.enabled = !val.trim().equals("no");
            else if (key.equals("action")) { cur.action = Action.of(val.trim()); if (cur.action == null) cur.unknownAction = val.trim(); }
            else if (key.equals("saving")) cur.saving = Saving.of(val.trim());
            else if (key.equals("why")) cur.why = unesc(val);
            else if (key.equals("filetype")) cur.fileType = val.trim().toLowerCase(Locale.ROOT);
            else if (key.equals("when")) {
                Condition c = Condition.parse(val);
                cur.when.add(c != null ? c : new Condition(val.trim(), null, "", false));
                curHasWhen = true;
            }                                                   // anything else: a key from a newer version, ignored
        }
        if (cur != null) out.warnings.add("rule " + cur.id + ": the text ends inside it; rule skipped");
        out.rules.clear();                                      // built-ins first in the app's order (missing ones come with their defaults), then the user's rules in the order saved
        out.rules.addAll(defList);
        out.rules.addAll(user);
        return out;
    }

    private static void finish(RuleSet out, Rule r, boolean builtIn, boolean hasWhen, Map<String, Rule> defaults, List<Rule> user, Set<String> seen) {
        if (builtIn) {
            Rule d = defaults.get(r.id);
            if (d == null) { out.warnings.add("rule " + r.id + ": no such built-in rule in this version; skipped"); return; }
            if (!seen.add(r.id)) return;
            d.enabled = r.enabled;
            if (hasWhen) { d.when.clear(); d.when.addAll(r.when); }
            String p = d.problem();
            if (p != null) { d.enabled = false; out.warnings.add("rule " + d.id + ": " + p + "; switched off"); }
            return;
        }
        r.builtIn = false;
        if (defaults.containsKey(r.id) || !seen.add(r.id)) {     // an id that is taken: give the user's rule a new one
            int n = 1;
            while (defaults.containsKey("user_" + n) || seen.contains("user_" + n)) n++;
            r.id = "user_" + n; seen.add(r.id);
        }
        String p = r.problem();
        if (p != null) { r.enabled = false; out.warnings.add("rule " + r.id + " (" + r.name + "): " + p + "; switched off"); }
        user.add(r);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else b.append(c);
        }
        return b.toString();
    }
}
