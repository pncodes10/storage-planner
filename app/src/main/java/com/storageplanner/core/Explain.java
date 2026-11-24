package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Why a file of the plan gets its action, for the card's analysis panel: the verdict, every option the rules
 * offered, the rules that match (and the keep / never-delete rules that protect it), and every tag the app knows
 * about it. Pure Java; the screens show the sections as they come.
 */
public final class Explain {
    private Explain() {}

    /** One titled list of "label: value" lines. */
    public static final class Section {
        public final String title;
        public final List<String[]> lines = new ArrayList<String[]>();
        Section(String t) { title = t; }
        void add(String label, String value) { lines.add(new String[]{label, value}); }
    }

    static final String[] RISK = {"Safe", "No loss", "Looks identical", "Small quality loss", "Your call"};

    /** The analysis of one entry of the final plan. ctx: the scan's tag context; rules: the rules in use. */
    public static List<Section> of(FinalPlan.Entry e, Tags.Context ctx, RuleSet rules, long now) {
        List<Section> out = new ArrayList<Section>();
        FileItem f = e.file;

        Section v = new Section("Verdict");
        v.add("Final action", FinalPlan.actionLabel(e.action) + (e.chosen ? " (your choice)" : " (suggested)"));
        if (e.suggested != null) {
            v.add("Suggested", FinalPlan.actionLabel(e.suggestedAction) + ": " + e.suggested.technique);
            if (e.suggested.why != null && !e.suggested.why.isEmpty()) v.add("Why", e.suggested.why);
            v.add("Risk", e.suggested.risk >= 0 && e.suggested.risk < RISK.length ? RISK[e.suggested.risk] : "?");
            v.add(FinalPlan.DELETE.equals(e.suggestedAction) ? "Frees" : "Frees about", Report.size(e.suggested.saved));
        } else v.add("Suggested", "Keep: no rule has anything for this file");
        if (e.chosen && !e.action.equals(e.suggestedAction)) v.add("Note", "You changed the suggestion");
        out.add(v);

        if (e.unit != null && e.unit.options.size() > 0) {
            Section o = new Section("Options the rules offered");
            for (Option op : e.unit.options)
                o.add(actionWord(op.action) + " · " + op.technique, (op.exact ? "" : "about ") + Report.size(op.saved) + " · " + (op.risk >= 0 && op.risk < RISK.length ? RISK[op.risk] : "")
                        + (op.why == null || op.why.isEmpty() ? "" : " · " + op.why));
            out.add(o);
        }

        Tags.T t = Tags.of(f, ctx, now);
        if (rules != null) {
            Section r = new Section("Rules that match");
            for (Rule x : rules.rules) {
                if (!x.enabled || x.problem() != null || !x.matches(t)) continue;
                r.add((x.builtIn ? "" : "Your rule: ") + x.name, RuleText.action(x) + " — " + RuleText.conditions(x));
            }
            if (r.lines.isEmpty()) r.add("None", "No rule matches this file, so it is kept");
            out.add(r);
        }

        Section tg = new Section("What the app found");
        for (Tags.Entry x : t.shown()) tg.add(x.label, x.value);
        out.add(tg);

        Section fl = new Section("File");
        fl.add("Name", f.name());
        fl.add("Folder", f.rel.lastIndexOf('/') < 0 ? "Internal storage" : f.rel.substring(0, f.rel.lastIndexOf('/')));
        fl.add("Size", Report.size(f.size));
        fl.add("Modified", Report.day(f.mtime));
        if (f.width > 0 && f.height > 0) fl.add("Dimensions", f.width + " × " + f.height);
        out.add(fl);
        return out;
    }

    static String actionWord(String action) {
        if ("delete".equals(action)) return "Delete";
        if (action != null && (action.startsWith("reencode") || "lossless_image".equals(action))) return "Compress";
        return action == null ? "" : action.toLowerCase(Locale.ROOT);
    }
}
