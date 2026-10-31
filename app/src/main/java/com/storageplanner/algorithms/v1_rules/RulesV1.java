package com.storageplanner.algorithms.v1_rules;

import com.storageplanner.algorithms.Decision;
import com.storageplanner.algorithms.DeletionAlgorithm;
import com.storageplanner.core.Report;
import com.storageplanner.core.Techniques;
import com.storageplanner.facts.PictureFacts;
import com.storageplanner.tools.category.PhotoCategory;

/**
 * Version 1.6's fixed rules, one per category, kept as the baseline: one-time codes older than a day, payments
 * older than 90 days, tickets a week after their date (180 days without one), chat greetings older than a week,
 * accidental shots. Everything else is kept.
 */
public final class RulesV1 implements DeletionAlgorithm {
    static final long DAY = 86400000L;

    public String id() { return "v1_rules"; }

    public String name() { return "1.6 fixed rules per category"; }

    public Decision decide(PictureFacts p, long now) {
        Decision d = new Decision();
        d.algorithm = id();
        String c = p.category == null ? "" : p.category;
        double age = p.ageDays(now);
        d.kind = c; d.kindLabel = PhotoCategory.label(p.category);
        if (c.equals(PhotoCategory.OTP) && age > 1)
            notNeeded(d, Techniques.OTP_SHOTS, "Screenshot of a one-time code, " + Math.round(age) + " days old");
        else if (c.equals(PhotoCategory.PAYMENT) && age > 90)
            notNeeded(d, Techniques.OLD_PAYMENTS, "Payment confirmation from " + Math.round(age) + " days ago");
        else if (c.equals(PhotoCategory.TICKET) && (p.dateInPicture > 0 ? now - p.dateInPicture > 7 * DAY : age > 180))
            notNeeded(d, Techniques.EXPIRED_TICKETS, p.dateInPicture > 0 ? "Ticket for " + Report.day(p.dateInPicture) + ", which has passed"
                    : "Ticket saved " + Math.round(age) + " days ago");
        else if (c.equals(PhotoCategory.GREETING) && age > 7 && p.chatImage)
            notNeeded(d, Techniques.GREETINGS, "Greeting image received " + Math.round(age) + " days ago");
        else if (c.equals(PhotoCategory.ACCIDENTAL))
            notNeeded(d, Techniques.ACCIDENTAL, "Almost nothing visible in this shot");
        else { d.verdict = Decision.KEEP; d.need = 1; d.why = "No rule for this picture"; }
        return d;
    }

    private static void notNeeded(Decision d, String technique, String why) {
        d.verdict = Decision.NOT_NEEDED; d.need = 0; d.technique = technique; d.why = why; d.reasons.add(why);
    }
}
