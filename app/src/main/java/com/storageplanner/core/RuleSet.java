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
            else if (r.action == Action.DELETE) c.deletes.add(r);
            else c.compress.add(r);
        }
        return c;
    }

    public static final class Compiled {
        final List<Rule> keep = new ArrayList<Rule>(), deletes = new ArrayList<Rule>(), compress = new ArrayList<Rule>();

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
            if (t.idDocument) {                                  // ID documents: only an identical extra copy may go
                for (Iterator<Option> it = u.options.iterator(); it.hasNext(); ) {
                    Option o = it.next();
                    if (o.action.equals("delete") && !o.technique.equals(Techniques.DUPLICATES)) it.remove();
                }
            }
        }

        private static void emit(Unit u, Rule r, FileItem f, Tags.T t, Tags.Context ctx, Estimates est, long now) {
            boolean del = r.action == Action.DELETE;
            Saving sv = r.saving == null ? Saving.defaultFor(r.action) : r.saving;
            int risk = r.risk;
            String tech = r.technique;
            if (!r.builtIn) {                                    // limits for the user's own rules
                sv = del ? Saving.FILE_SIZE : (sv == Saving.FILE_SIZE ? Saving.defaultFor(r.action) : sv);
                risk = del ? Option.R4 : Option.R2;
                tech = Rule.USER_PREFIX + r.name;
            }
            long saved = sv.of(f, est);
            if (saved <= 0) return;
            String why;
            if ("burst".equals(r.whyHook)) why = UnitBuilder.burstWhy(f, ctx.byRel.get(t.burstKeptShot));
            else if (r.why == null || r.why.length() == 0) why = r.builtIn ? "" : "Matches your rule \"" + r.name + "\"";
            else why = fill(r.why, f, t, ctx, est, now);
            u.add(new Option(r.action.option, tech, saved, risk, sv.exact(), why));
        }
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
