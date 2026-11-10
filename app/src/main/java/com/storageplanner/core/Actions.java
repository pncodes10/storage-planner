package com.storageplanner.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The algorithm's output for the engine that will carry actions out: one entry per suggestion, with
 * everything needed to act on it safely and to explain it. Nothing here performs an action.
 *
 * Each action has a state:
 *   approved   the user accepted it. The only state an engine may act on without asking again.
 *   planned    the algorithm put it in the current plan; the user has not looked at it yet.
 *   available  it passed every check but the plan does not need it.
 *   review     shown for the user to decide; the algorithm never plans it.
 *
 * "verify" holds what the file looked like at scan time. Before acting, the engine must find the file
 * unchanged (size and modified time; SHA-256 when given), and for an action with "keeps", find that
 * file present too.
 */
public final class Actions {
    private Actions() {}

    public static final int SCHEMA = 1;
    public static final String APPROVED = "approved", PLANNED = "planned", AVAILABLE = "available", REVIEW = "review";

    public static String state(Unit u, Option o, Planner.Plan plan, Map<String, String> accepted) {
        boolean inPlan = plan.chosen.get(u) == o;
        if (inPlan && accepted.containsKey(u.id)) return APPROVED;
        if (inPlan) return PLANNED;
        return o.review ? REVIEW : AVAILABLE;
    }

    /** kept: unit ids the user chose to keep (left out of "actions", listed under "kept_by_user"). */
    public static String json(List<Unit> units, Planner.Plan plan, Set<String> kept, Map<String, String> accepted,
                              boolean safeMode, long now) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"schema\": ").append(SCHEMA).append(", \"policy\": ").append(Policy.VERSION)
          .append(", \"generated\": ").append(now).append(", \"mode\": ").append(js(safeMode ? "safe" : "target")).append(",\n");
        long planned = 0, approved = 0;
        StringBuilder acts = new StringBuilder(), prot = new StringBuilder(), keptOut = new StringBuilder();
        for (Unit u : units) {
            if (kept.contains(u.id)) { keptOut.append(keptOut.length() == 0 ? "" : ", ").append(js(u.id)); continue; }
            Option userPick = plan.chosen.get(u);
            boolean listed = false;
            for (Option o : u.options) {
                if (o == userPick) listed = true;
                String st = state(u, o, plan, accepted);
                if (st.equals(PLANNED)) planned += o.saved; else if (st.equals(APPROVED)) approved += o.saved;
                entry(acts, u, o, st);
            }
            if (userPick != null && !listed) {                  // a deletion the user picked for a file the algorithm would keep
                approved += userPick.saved;
                entry(acts, u, userPick, APPROVED);
            }
            Assessment a = u.assessment;
            if (a != null && (a.isProtected() || !a.withdrawn.isEmpty())) {
                prot.append(prot.length() == 0 ? "" : ",\n").append("    {\"item\": ").append(js(u.id))
                    .append(", \"protected\": ").append(a.isProtected()).append(", \"labels\": ").append(list(a.labels))
                    .append(", \"withdrawn\": ").append(list(a.withdrawn)).append("}");
            }
        }
        sb.append("  \"planned_bytes\": ").append(planned).append(", \"approved_bytes\": ").append(approved).append(",\n");
        sb.append("  \"actions\": [\n").append(acts).append("\n  ],\n");
        sb.append("  \"protected\": [\n").append(prot).append("\n  ],\n");
        sb.append("  \"kept_by_user\": [").append(keptOut).append("]\n}\n");
        return sb.toString();
    }

    private static void entry(StringBuilder sb, Unit u, Option o, String state) {
        if (sb.length() > 0) sb.append(",\n");
        sb.append("    {\"item\": ").append(js(u.id)).append(", \"kind\": ").append(js(u.kind))
          .append(", \"action\": ").append(js(o.action)).append(", \"state\": ").append(js(state))
          .append(", \"technique\": ").append(js(o.technique))
          .append(", \"tier\": ").append(o.risk).append(", \"tier_name\": ").append(js(Option.TIER_NAME[o.risk]))
          .append(", \"bytes\": ").append(o.saved).append(", \"exact\": ").append(o.exact)
          .append(", \"reason\": ").append(js(o.why));
        if (o.note != null) sb.append(", \"review_because\": ").append(js(o.note));
        if (o.keeps != null) sb.append(", \"keeps\": ").append(js(o.keeps));
        if (u.file != null) {
            sb.append(", \"path\": ").append(js(u.file.abs)).append(", \"verify\": {\"size\": ").append(u.file.size)
              .append(", \"mtime\": ").append(u.file.mtime);
            if (u.file.sha256 != null) sb.append(", \"sha256\": ").append(js(u.file.sha256));
            sb.append("}");
        } else if (u.app != null) sb.append(", \"package\": ").append(js(u.app.pkg));
        if (o.confidence >= 0)
            sb.append(String.format(Locale.US, ", \"retention\": %.2f, \"redundancy\": %.2f, \"regenerable\": %.2f, \"harm\": %.2f, \"confidence\": %.2f",
                    o.retention, o.redundancy, o.regenerable, o.harm, o.confidence));
        sb.append("}");
    }

    private static String list(List<String> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : ", ").append(js(l.get(i)));
        return b.append("]").toString();
    }

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
