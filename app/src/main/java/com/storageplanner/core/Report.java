package com.storageplanner.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stage 4: human-readable and machine-readable output of a plan. Sizes in decimal GB, like Android Settings. */
public final class Report {
    private Report() {}

    /** "12 Nov 2026" for a date read in a picture (stored as UTC midnight). */
    public static String day(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM yyyy", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    public static String gb(long b) { return String.format(Locale.US, "%.1f GB", b / 1e9); }
    public static String size(long b) {
        if (b >= 1e9) return String.format(Locale.US, "%.2f GB", b / 1e9);
        if (b >= 1e6) return String.format(Locale.US, "%.1f MB", b / 1e6);
        return String.format(Locale.US, "%.0f KB", b / 1e3);
    }

    public static final class Context {
        public long total, free, used, targetUsed;
        public Estimates est;
        public UnitBuilder.Detections det;
        public int files, unreadableDirs, sdk;
        public long now;
    }

    public static String text(Context c, Planner.Plan plan, Planner.Plan safe) {
        StringBuilder sb = new StringBuilder();
        sb.append("STORAGE PLAN  ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(c.now))).append("\n\n");
        sb.append("Used ").append(gb(c.used)).append(" of ").append(gb(c.total)).append(";  target ").append(gb(c.targetUsed)).append("\n");
        long after = c.used - plan.saved();
        if (plan.reachesTarget)
            sb.append("Plan reaches ").append(gb(after)).append(" (frees ").append(gb(plan.saved())).append(") using risk tier up to R")
              .append(plan.tier).append(", ").append(plan.confirmations()).append(" items need your confirmation.\n");
        else
            sb.append("Target NOT reachable. Doing everything listed reaches ").append(gb(after)).append(".\n");
        sb.append("Of the planned saving, ").append(gb(plan.savedExact())).append(" is exact (deletions, caches, apps) and ")
          .append(gb(plan.saved() - plan.savedExact())).append(" is estimated (re-encoding).\n");
        sb.append("Everything safe (R0-R2, no unique file deleted) would free ").append(gb(safe.saved()))
          .append(" -> ").append(gb(c.used - safe.saved())).append(".\n\n");
        int tier = -1;
        for (Object[] row : Planner.byTechnique(plan)) {
            int r = (Integer) row[3];
            if (r != tier) { tier = r; sb.append("R").append(r).append("  ").append(Option.TIER_NAME[r]).append("\n"); }
            sb.append(String.format(Locale.US, "   %-38s %10s  %5d items%s\n", row[0], size((Long) row[1]), (Integer) row[2],
                    ((Boolean) row[4]) ? "" : "  (estimate)"));
        }
        sb.append("\nPhoto savings: ").append(c.est.photoSource).append("\nChat images: ").append(c.est.chatSource)
          .append("\nScreenshots: ").append(c.est.screenshotSource).append("\n");
        if (c.unreadableDirs > 0)
            sb.append("\n").append(c.unreadableDirs).append(" folders could not be read (Android/data is private to each app; app sizes come from system stats instead).\n");
        sb.append("\nThis app only plans. It has not deleted or changed anything.\n");
        return sb.toString();
    }

    public static String csv(Planner.Plan plan) {
        StringBuilder sb = new StringBuilder("risk_tier,technique,action,bytes,exact,item,reason\n");
        for (Object[] row : Planner.byTechnique(plan)) {
            @SuppressWarnings("unchecked") List<Unit> us = (List<Unit>) row[5];
            for (Unit u : us) {
                Option o = plan.chosen.get(u);
                sb.append("R").append(o.risk).append(',').append(q(o.technique)).append(',').append(o.action).append(',')
                  .append(o.saved).append(',').append(o.exact ? "yes" : "estimate").append(',').append(q(u.title)).append(',')
                  .append(q(o.why)).append('\n');
            }
        }
        return sb.toString();
    }

    public static String json(Context c, Planner.Plan plan, Planner.Plan safe) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"generated\": ").append(c.now).append(",\n");
        sb.append("  \"total_bytes\": ").append(c.total).append(", \"used_bytes\": ").append(c.used)
          .append(", \"target_used_bytes\": ").append(c.targetUsed).append(",\n");
        sb.append("  \"plan\": {\"reaches_target\": ").append(plan.reachesTarget).append(", \"max_tier\": ").append(plan.tier)
          .append(", \"saved_bytes\": ").append(plan.saved()).append(", \"exact_bytes\": ").append(plan.savedExact())
          .append(", \"confirmations\": ").append(plan.confirmations()).append("},\n");
        sb.append("  \"safe_set_saved_bytes\": ").append(safe.saved()).append(",\n");
        sb.append("  \"detections\": {\"duplicate_groups\": ").append(c.det.duplicateGroups).append(", \"burst_groups\": ")
          .append(c.det.burstGroups).append(", \"blurry\": ").append(c.det.blurry.size()).append(", \"sent_copies\": ")
          .append(c.det.sentCopyOf.size()).append("},\n");
        sb.append("  \"actions\": [\n");
        boolean first = true;
        for (Map.Entry<Unit, Option> e : plan.chosen.entrySet()) {
            Option o = e.getValue();
            if (!first) sb.append(",\n"); first = false;
            sb.append("    {\"item\": ").append(js(e.getKey().id)).append(", \"action\": ").append(js(o.action))
              .append(", \"technique\": ").append(js(o.technique)).append(", \"bytes\": ").append(o.saved)
              .append(", \"risk\": ").append(o.risk).append(", \"exact\": ").append(o.exact).append(", \"reason\": ").append(js(o.why)).append("}");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    private static String q(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
