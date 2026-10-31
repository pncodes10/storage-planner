package com.storageplanner.test.algorithms;

import com.storageplanner.algorithms.Decision;
import com.storageplanner.algorithms.v1_rules.RulesV1;
import com.storageplanner.facts.PictureFacts;
import com.storageplanner.tools.category.PhotoCategory;

/** The 1.6 rules, kept as the baseline, decide exactly as 1.6 did. */
public final class RulesV1Test {
    static int checks, fails;
    static final long NOW = 1792000000000L, DAY = 86400000L;

    static void expect(String what, String category, String area, double ageDays, long dateInPicture, int verdict) {
        PictureFacts p = new PictureFacts();
        p.path = "x.png"; p.area = area; p.category = category; p.savedAt = NOW - (long) (ageDays * DAY); p.dateInPicture = dateInPicture;
        p.chatImage = area.startsWith("wa_");
        Decision d = new RulesV1().decide(p, NOW);
        checks++;
        if (d.verdict != verdict) { fails++; System.out.println("FAIL " + what + ": got " + d.verdict + " (" + d.why + ")"); }
    }

    public static void main(String[] a) {
        int K = Decision.KEEP, D = Decision.NOT_NEEDED;
        expect("OTP after a day", PhotoCategory.OTP, "screenshot", 2, 0, D);
        expect("fresh OTP", PhotoCategory.OTP, "screenshot", 0.5, 0, K);
        expect("payment after 90 days, whatever the amount", PhotoCategory.PAYMENT, "screenshot", 91, 0, D);
        expect("payment at 80 days", PhotoCategory.PAYMENT, "screenshot", 80, 0, K);
        expect("ticket a week after its date", PhotoCategory.TICKET, "screenshot", 30, NOW - 8 * DAY, D);
        expect("ticket before its date", PhotoCategory.TICKET, "screenshot", 30, NOW + 8 * DAY, K);
        expect("ticket with no date after 180 days", PhotoCategory.TICKET, "screenshot", 181, 0, D);
        expect("greeting in a chat after a week", PhotoCategory.GREETING, "wa_image", 8, 0, D);
        expect("greeting outside a chat", PhotoCategory.GREETING, "camera", 80, 0, K);
        expect("accidental", PhotoCategory.ACCIDENTAL, "camera", 0, 0, D);
        expect("receipt: no rule", PhotoCategory.RECEIPT, "camera", 2000, 0, K);
        System.out.println((checks - fails) + "/" + checks + " v1 rule checks pass");
        if (fails > 0) System.exit(1);
    }
}
