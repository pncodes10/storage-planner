package com.storageplanner.algorithms.v2_futureneed;

import com.storageplanner.algorithms.Decision;
import com.storageplanner.algorithms.DeletionAlgorithm;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;
import com.storageplanner.core.Techniques;
import com.storageplanner.facts.PictureFacts;
import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.tools.ocr.TextRules;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Will this picture be needed again? Works out what the picture is for, from its category, the text and code
 * read in it, faces and where it was saved, and how long that stays useful: a one-time code for minutes, a
 * coupon until it expires, a ticket until the trip, a delivery update until the parcel is in, a rent receipt,
 * prescription or warranty card for years, a family photo for good.
 *
 * need = how likely the picture is wanted again (0-1). It is the content's base value while the content is
 * useful, then halves every halfDays down to a floor. Kinds whose floor is at or above DELETE_BELOW can never
 * be suggested for deletion, however old: only content with a clear end of usefulness is.
 */
public final class FutureNeed implements DeletionAlgorithm {
    public String id() { return "v2_futureneed"; }

    public String name() { return "Useful life of what the picture shows (1.9)"; }

    public Decision decide(PictureFacts p, long now) { return assess(p, now); }

    public static final double KEEP_AT = 0.5, DELETE_BELOW = 0.2;
    static final long DAY = 86400000L;


    /** Useful life of a kind: base value, floor, days at full value (from the picture's date or usefulUntil), half-life after. */
    static final class Life {
        final String label; final double base, floor, lifeDays, halfDays; final String technique;
        Life(String label, double base, double floor, double lifeDays, double halfDays, String technique) {
            this.label = label; this.base = base; this.floor = floor; this.lifeDays = lifeDays; this.halfDays = halfDays; this.technique = technique;
        }
    }

    static final java.util.Map<String, Life> LIFE = new java.util.LinkedHashMap<String, Life>();
    static void life(String k, String label, double base, double floor, double life, double half, String t) { LIFE.put(k, new Life(label, base, floor, life, half, t)); }
    static {
        double F = Double.POSITIVE_INFINITY;
        // memories and records: never suggested
        life("people", "Photo of people", 1.0, 1.0, F, F, null);
        life("photo", "Photo", 0.9, 0.9, F, F, null);
        life("id", "ID document", 1.0, 1.0, F, F, null);
        life("warranty", "Warranty or purchase proof", 1.0, 1.0, F, F, null);
        life("medical", "Medical record", 1.0, 1.0, F, F, null);
        life("insurance", "Insurance", 1.0, 1.0, F, F, null);
        life("record", "Certificate, contract or financial record", 1.0, 1.0, F, F, null);
        life("credential", "Wi-Fi or bank details", 1.0, 1.0, F, F, null);
        life("payment_proof", "Proof of a large or important payment", 1.0, 1.0, F, F, null);
        life("bill", "Bill or invoice", 0.85, 0.85, F, F, null);
        life("receipt", "Receipt", 0.8, 0.8, F, F, null);
        life("document", "Document", 0.8, 0.8, F, F, null);
        life("chat", "Chat screenshot", 0.7, 0.7, F, F, null);
        life("qr", "QR code or barcode", 0.7, 0.7, F, F, null);
        life("graphic", "Image", 0.6, 0.6, F, F, null);
        life("invitation", "Invitation", 0.8, 0.35, 7, 30, null);
        life("screenshot", "Screenshot", 0.6, 0.35, 365, 180, null);
        life("greeting_photo", "Greeting picture", 0.6, 0.35, 30, 60, null);
        life("unread", "Picture whose text is not read yet", 0.6, 0.6, F, F, null);
        // content with an end of usefulness
        life("otp", "One-time code", 0.9, 0.02, 1, 0.25, Techniques.OTP_SHOTS);
        life("coupon", "Coupon or offer", 0.8, 0.05, 0, 2, Techniques.EXPIRED_OFFERS);
        life("coupon_nodate", "Coupon or offer", 0.8, 0.05, 45, 15, Techniques.EXPIRED_OFFERS);
        life("delivery", "Delivery or order update", 0.8, 0.05, 30, 7, Techniques.ORDER_UPDATES);
        life("ride", "Ride or food order status", 0.7, 0.05, 14, 7, Techniques.ORDER_UPDATES);
        life("navigation", "Map or directions", 0.6, 0.05, 7, 5, Techniques.SHORT_LIVED);
        life("weather", "Weather", 0.5, 0.02, 2, 2, Techniques.SHORT_LIVED);
        life("token", "Parking, queue or token slip", 0.7, 0.05, 2, 2, Techniques.SHORT_LIVED);
        life("appointment", "Appointment or booking", 0.9, 0.05, 3, 3, Techniques.SHORT_LIVED);
        life("shopping", "Shopping page", 0.6, 0.05, 45, 30, Techniques.SHOPPING);
        life("payment", "Payment confirmation", 0.8, 0.05, 75, 20, Techniques.OLD_PAYMENTS);
        life("payment_unknown", "Payment confirmation, amount not readable", 0.8, 0.35, 90, 60, null);   // could be a large one
        life("payment_big", "Payment confirmation", 0.9, 0.05, 365, 120, Techniques.OLD_PAYMENTS);
        life("payment_failed", "Failed payment", 0.7, 0.03, 10, 5, Techniques.OLD_PAYMENTS);
        life("payment_pending", "Pending payment or refund", 0.9, 0.05, 60, 20, Techniques.OLD_PAYMENTS);
        life("ticket", "Ticket or pass", 0.8, 0.05, 7, 3, Techniques.EXPIRED_TICKETS);
        life("ticket_nodate", "Ticket or pass", 0.8, 0.05, 150, 30, Techniques.EXPIRED_TICKETS);
        life("ticket_refund", "Cancelled ticket awaiting refund", 0.9, 0.05, 45, 15, Techniques.EXPIRED_TICKETS);
        life("receipt_small", "Small receipt", 0.7, 0.05, 180, 60, Techniques.OLD_RECEIPTS);
        life("greeting", "Greeting forward", 0.5, 0.03, 7, 3, Techniques.GREETINGS);
        life("forward", "Meme or forward", 0.45, 0.03, 30, 15, Techniques.FORWARDS);
        life("accidental", "Accidental shot", 0.05, 0.05, 0, 1, Techniques.ACCIDENTAL);
    }

    public static String label(String kind) { Life l = LIFE.get(kind); return l == null ? kind : l.label; }


    // ------------------------------------------------------------------ what the text is about
    /** A purpose read from words: strong phrases count 2, weak ones 1; it applies at `need` points or more. */
    static final class Topic {
        final String kind; final String[] strong, weak; final int need;
        Topic(String kind, int need, String[] strong, String[] weak) { this.kind = kind; this.need = need; this.strong = strong; this.weak = weak; }
    }

    // records first: anything that looks like one is kept, whatever else it mentions
    static final Topic[] RECORDS = {
        new Topic("warranty", 3, new String[]{"warranty", "guarantee", "serial no", "serial number", "s/n", "imei", "extended warranty", "warranty card"},
                new String[]{"model no", "model number", "model", "date of purchase", "purchase date", "service centre", "service center", "product", "dealer", "registration"}),
        new Topic("medical", 4, new String[]{"prescription", "diagnosis", "patient", "lab report", "test report", "pathology", "vaccination",
                "vaccine", "haemoglobin", "hemoglobin", "blood sugar", "cholesterol", "radiology", "x-ray", "discharge summary", "medical report", "rx"},
                new String[]{"hospital", "clinic", "dr.", "doctor", "mg", "tablet", "tab.", "capsule", "syrup", "dose", "twice daily", "once daily", "after food", "before food",
                        "reference range", "result", "medicine", "age/sex", "specimen", "sample", "physician", "consultation"}),
        new Topic("insurance", 3, new String[]{"policy number", "policy no", "insurance", "insured", "sum assured", "sum insured", "nominee", "policyholder",
                "policy holder", "premium due", "mediclaim"},
                new String[]{"premium", "cover", "renewal", "policy", "claim", "tenure", "plan"}),
        new Topic("record", 3, new String[]{"form 16", "income tax return", "itr-v", "acknowledgement number", "rental agreement", "rent agreement", "lease",
                "sale deed", "agreement", "certificate", "marksheet", "mark sheet", "grade card", "transcript", "degree", "salary slip", "payslip",
                "pay slip", "offer letter", "appointment letter", "fixed deposit", "loan account", "sanction letter", "registration certificate",
                "chassis", "80g", "fee receipt", "provident fund", "uan"},
                new String[]{"university", "board", "semester", "employee", "employer", "salary", "gross", "net pay", "tenant", "landlord", "witness",
                        "signature", "roll no", "loan", "interest rate", "maturity", "deductions", "earnings", "hereby", "certify", "awarded"}),
        new Topic("credential", 2, new String[]{"wifi", "wi-fi", "ssid", "ifsc", "account number", "a/c no", "account no", "swift code", "network name",
                "wifi password", "password:", "pwd"},
                new String[]{"password", "bank", "branch", "beneficiary", "username", "user id", "account holder", "micr"}),
    };

    static final Topic[] SHORT_LIVED = {
        new Topic("coupon", 3, new String[]{"coupon", "promo code", "promocode", "coupon code", "use code", "voucher", "scratch card", "cashback",
                "redeem", "offer valid", "valid till", "valid until", "valid upto", "valid up to", "expires on", "flat off", "% off", "apply code"},
                new String[]{"offer", "off", "discount", "save", "deal", "code", "t&c", "terms", "min order", "minimum order", "applicable", "reward",
                        "won", "unlock", "claim"}),
        new Topic("delivery", 3, new String[]{"out for delivery", "order placed", "order confirmed", "arriving", "delivered on", "delivered", "track order",
                "track package", "tracking id", "tracking number", "awb", "shipped", "dispatched", "expected delivery", "estimated delivery",
                "delivery by", "your order", "order #", "package", "parcel"},
                new String[]{"delivery", "courier", "shipment", "seller", "items", "item", "return window", "return", "order id", "order"}),
        new Topic("ride", 3, new String[]{"your ride", "ride otp", "driver", "cab", "uber", "ola", "rapido", "pickup", "pick-up", "drop off", "drop-off",
                "on the way", "delivery partner", "being prepared", "arriving in", "mins away", "min away", "rate your", "trip details", "your trip", "swiggy", "zomato"},
                new String[]{"eta", "km", "fare", "toll", "auto", "bike", "share ride", "drop", "trip", "restaurant", "food"}),
        new Topic("navigation", 3, new String[]{"directions", "fastest route", "google maps", "start navigation", "your location", "choose destination",
                "best route", "usual traffic", "via "},
                new String[]{"km", "min", "hr", "exit", "turn", "head", "toward", "towards", "arrive", "traffic", "route", "start", "steps"}),
        new Topic("weather", 3, new String[]{"humidity", "feels like", "forecast", "precipitation", "uv index", "chance of rain", "air quality", "aqi", "hourly forecast"},
                new String[]{"wind", "sunny", "cloudy", "rain", "thunderstorm", "partly", "sunrise", "sunset", "high", "low", "°c", "°"}),
        new Topic("token", 3, new String[]{"parking", "token no", "token number", "your token", "queue", "valet", "cloakroom", "parking ticket"},
                new String[]{"vehicle", "entry", "exit", "slot", "level", "counter", "please wait", "in time", "vehicle no"}),
        new Topic("shopping", 3, new String[]{"add to cart", "buy now", "add to bag", "wishlist", "in stock", "out of stock", "free delivery", "ratings",
                "no cost emi", "bank offer", "size chart", "select size", "inclusive of all taxes", "add to basket"},
                new String[]{"mrp", "off", "price", "deal", "seller", "brand", "colour", "color", "size", "reviews", "emi", "offers"}),
        new Topic("appointment", 3, new String[]{"appointment", "reservation", "table for", "booking confirmed", "your booking", "slot booked",
                "consultation at", "meeting at", "scheduled for", "reminder"},
                new String[]{"date", "time", "guests", "venue", "location", "confirm", "reschedule", "cancel"}),
        new Topic("forward", 3, new String[]{"forwarded", "forwarded many times", "share this", "share with", "send this to", "share it", "joke", "meme",
                "lol", "lmao", "funny", "quote of the day", "thought of the day", "motivation", "motivational", "when you", "me:", "nobody:"},
                new String[]{"life", "success", "smile", "happiness", "love", "friends", "wife", "husband", "monday", "boss", "always", "never", "people", "dream"}),
        new Topic("invitation", 3, new String[]{"cordially invite", "invite you", "wedding", "marriage", "reception", "save the date", "muhurtham",
                "you are invited", "join us", "celebrate with us"},
                new String[]{"venue", "family", "blessings", "ceremony", "rsvp", "auspicious", "occasion"}),
    };

    /** Evidence points; 0 when no strong phrase is present and strong is required (records). */
    static int points(String t, Topic tp, StringBuilder why, boolean strongRequired) {
        int p = 0;
        for (String k : tp.strong) if (has(t, k)) { p += 2; note(why, k); }
        if (strongRequired && p == 0) return 0;
        for (String k : tp.weak) if (has(t, k)) { p += 1; note(why, k); }
        return p;
    }

    static void note(StringBuilder why, String k) { if (why != null && why.length() < 50) why.append(why.length() == 0 ? "" : ", ").append('"').append(k.trim()).append('"'); }

    /** The phrase as a whole word or words (punctuation at its ends may touch anything). */
    static boolean has(String t, String k) {
        int i = t.indexOf(k);
        while (i >= 0) {
            boolean a = i == 0 || !Character.isLetterOrDigit(t.charAt(i - 1)) || !Character.isLetterOrDigit(k.charAt(0));
            int e = i + k.length();
            boolean b = e >= t.length() || !Character.isLetterOrDigit(t.charAt(e)) || !Character.isLetterOrDigit(k.charAt(k.length() - 1));
            if (a && b) return true;
            i = t.indexOf(k, i + 1);
        }
        return false;
    }

    static boolean any(String t, String... keys) { for (String k : keys) if (has(t, k)) return true; return false; }

    // ------------------------------------------------------------------ amounts and dates
    // OCR often reads the rupee sign as %, z, = or ?; amounts are only taken with a currency mark, grouping or paise
    static final Pattern MONEY = Pattern.compile("(?:₹|rs\\.?|inr|%|z|=|\\?|\\$|£|€)\\s?(\\d[\\d,]*(?:\\.\\d{1,2})?)|\\b(\\d{1,3}(?:(?:,\\d{2})*,\\d{3}|(?:,\\d{3})+)(?:\\.\\d{1,2})?)\\b|\\b(\\d{1,6}\\.\\d{2})\\b");

    static final Pattern TOTAL = Pattern.compile("(?:grand total|net total|total amount|net amt|total)[ :]*(?:₹|rs\\.?|inr)?\\s?(\\d[\\d,]*\\.\\d{2})");

    /** A receipt's total (the last "total" with paise), else the largest amount; 0 if none. */
    public static double total(String t) {
        Matcher m = TOTAL.matcher(t);
        double v = 0;
        while (m.find()) { double x = amount(m.group(1)); if (x > 0) v = x; }
        return v > 0 ? v : amount(t);
    }

    /** The largest amount in the text (rupees), 0 if none. */
    public static double amount(String t) {
        double best = 0;
        Matcher m = MONEY.matcher(t);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String digits = g.replace(",", "");
            if (digits.indexOf('.') < 0 && digits.length() > 7) continue;          // a long reference number, not money
            try { double v = Double.parseDouble(digits); if (v < 1e7) best = Math.max(best, v); } catch (NumberFormatException ignored) {}
        }
        return best;
    }

    static final String MONTHS = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*";
    static final String DATE = "(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?|(\\d{1,2})(?:st|nd|rd|th)?[ -]?" + MONTHS + "[ ,.-]*(\\d{4}|\\d{2}(?!\\d|:))?|" + MONTHS + "[ -]?(\\d{1,2})(?:st|nd|rd|th)?,?[ ]*(\\d{4})?";
    static final Pattern EXPIRY = Pattern.compile("(?:valid till|valid until|valid upto|valid up to|valid thru|valid through|valid to|expires on|expires|expiry date|expiry|expiring|exp\\.?|use by|use before|offer ends|ends on|last date|till|until)[ :-]*(?:on )?(?:" + DATE + ")");
    static final Pattern DELIVERED = Pattern.compile("(?:delivered on|delivered|arriving on|arriving|delivery by|expected by|expected on)[ :,-]*(?:on |by )?(?:[a-z]{3,9},? )?(?:" + DATE + ")");
    static final Pattern ANY_DATE = Pattern.compile("\\b(?:" + DATE + ")");

    /** Date of a match of DATE (groups from `g`), or 0. Missing years are the year around ref (the picture's date). */
    static long dateAt(Matcher m, int g, long ref) {
        int y, mo, d;
        if (m.group(g) != null) { d = num(m.group(g)); mo = num(m.group(g + 1)); y = m.group(g + 2) == null ? -1 : num(m.group(g + 2)); }
        else if (m.group(g + 3) != null) { d = num(m.group(g + 3)); mo = mon(m.group(g + 4)); y = m.group(g + 5) == null ? -1 : num(m.group(g + 5)); }
        else if (m.group(g + 6) != null) { mo = mon(m.group(g + 6)); d = num(m.group(g + 7)); y = m.group(g + 8) == null ? -1 : num(m.group(g + 8)); }
        else return 0;
        if (y >= 0) return TextRules.day(y, mo, d);
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(ref);
        long best = TextRules.day(c.get(java.util.Calendar.YEAR), mo, d);
        if (best > 0 && best < ref - 60 * DAY) best = TextRules.day(c.get(java.util.Calendar.YEAR) + 1, mo, d);   // "valid till 5 Jan" seen in December
        return best;
    }

    static int num(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } }
    static int mon(String s) { for (int i = 0; i < 12; i++) if (s.startsWith(TextRules.MON[i])) return i + 1; return -1; }

    /** The latest date the pattern finds after its keyword, within ~3 years of ref; 0 if none. */
    static long find(Pattern p, String t, long ref) {
        long best = 0;
        Matcher m = p.matcher(t);
        while (m.find()) {
            long d = dateAt(m, 1, ref);
            if (d > 0 && Math.abs(d - ref) < 3 * 366 * DAY) best = Math.max(best, d);
        }
        return best;
    }

    // ------------------------------------------------------------------ the decision
    static final String[] FAILED = {"payment failed", "transaction failed", "failed", "declined", "unsuccessful", "could not be completed"};
    static final String[] DEBITED = {"money debited", "amount debited", "has been debited", "was debited", "is debited", "will be refunded", "refund initiated", "refund will"};
    static final String[] NOT_DEBITED = {"no amount", "not debited", "not deducted", "was not", "wasn't", "has not been"};
    static final String[] PENDING = {"pending", "processing", "in progress", "refund initiated", "will be refunded", "refund", "under review", "awaiting", "confirming"};
    static final String[] PURPOSE = {"rent", "house rent", "school fee", "school fees", "tuition", "college fee", "fees", "emi", "loan", "insurance",
            "premium", "tax", "deposit", "advance", "security deposit", "salary", "maintenance", "society", "hospital", "donation", "token amount"};
    static final String[] FOLDER_KEEP = {"important", "keep", "docs", "documents", "document", "tax", "taxes", "medical", "health", "warranty",
            "warranties", "bills", "bill", "receipts", "id", "ids", "kyc", "insurance", "certificates", "office", "work", "family", "wedding", "kids", "baby"};
    static final String[] STANDARD_FOLDERS = {"camera", "screenshots", "screenshot", "whatsapp", "whatsapp images", "whatsapp business", "media",
            "images", "sent", "download", "downloads", "pictures", "dcim", "telegram", "telegram images", "instagram", "snapchat", "facebook",
            "messenger", "twitter", "restored", "edited", ".private", "100andro", "100media", "gallery", "raw", "portrait", "selfies", "documents"};

    /** Decision for one file (wraps it in PictureFacts). */
    public static Decision assess(FileItem f, long now) { return assess(PictureFacts.of(f), now); }

    /** Decision for one sorted picture at time now. */
    public static Decision assess(PictureFacts f, long now) {
        Decision a = new Decision();
        a.algorithm = "v2_futureneed";
        String t = f.text == null ? "" : f.text;
        boolean chatImage = f.chatImage;
        String c = f.category == null ? PhotoCategory.PHOTO : f.category;
        long anchor = f.savedAt;
        StringBuilder ev = new StringBuilder();
        String kind = null;

        // 1. what the picture is, from its category, refined by the text
        if (c.equals(PhotoCategory.ID)) { kind = "id"; a.reasons.add("ID documents are always kept"); }
        else if (c.equals(PhotoCategory.ACCIDENTAL)) { kind = "accidental"; a.reasons.add("Almost nothing visible in this shot"); }
        else if (c.equals(PhotoCategory.PEOPLE)) { kind = "people"; a.reasons.add("Has " + (f.faces > 1 ? f.faces + " faces" : "a face") + " in it"); }
        if (kind == null && !t.isEmpty()) {
            for (Topic tp : RECORDS) {
                StringBuilder w = new StringBuilder();
                if (tp.kind.equals("credential") && (c.equals(PhotoCategory.OTP) || c.equals(PhotoCategory.PAYMENT))) continue;  // "never share your password"
                if (points(t, tp, w, true) >= tp.need) { kind = tp.kind; a.reasons.add(label(kind) + ": " + w); break; }
            }
        }
        if (kind == null) {
            if (c.equals(PhotoCategory.OTP)) { kind = "otp"; a.reasons.add("A one-time code stops working within minutes"); }
            else if (c.equals(PhotoCategory.PAYMENT)) kind = payment(t, a);
            else if (c.equals(PhotoCategory.TICKET)) {
                if (any(t, "cancelled", "canceled", "refund")) { kind = "ticket_refund"; a.reasons.add("Cancelled or refund mentioned: kept until the refund is likely done"); }
                else if (f.dateInPicture > 0) { kind = "ticket"; a.usefulUntil = f.dateInPicture; anchor = f.dateInPicture; }
                else kind = "ticket_nodate";
            } else if (c.equals(PhotoCategory.RECEIPT)) {
                double amt = total(t);
                if (any(t, RECORDS[0].strong)) { kind = "warranty"; a.reasons.add("Receipt with a warranty, serial number or IMEI: proof of purchase"); }
                else if (amt > 0 && amt < 1000) { kind = "receipt_small"; a.reasons.add("Small purchase (about ₹" + Math.round(amt) + ")"); }
                else { kind = "receipt"; if (amt >= 1000) a.reasons.add("Purchase of about ₹" + Math.round(amt) + ": may be needed for returns or warranty"); }
            } else if (c.equals(PhotoCategory.BILL)) kind = "bill";
            else if (c.equals(PhotoCategory.CHAT)) kind = "chat";
            else if (c.equals(PhotoCategory.QR)) kind = f.code != null && f.code.toLowerCase(Locale.ROOT).startsWith("wi-fi") ? "credential" : "qr";
            else if (c.equals(PhotoCategory.GREETING)) {
                kind = chatImage ? "greeting" : "greeting_photo";
                a.reasons.add(chatImage ? "Greeting forwarded in a chat" : "Greeting picture not from a chat");
            }
        }
        if (kind == null && chatImage && greeting(t)) { kind = "greeting"; a.reasons.add("Greeting forwarded in a chat"); }
        // short-lived content, read from the text of screenshots, downloads, chat images and documents
        if (kind == null && !t.isEmpty()) {
            int best = 0; String bestKind = null; String bestWhy = null;
            for (Topic tp : SHORT_LIVED) {
                if (tp.kind.equals("forward") && !(chatImage || f.screenshot)) continue;
                StringBuilder w = new StringBuilder();
                int p = points(t, tp, w, false);
                int needP = tp.kind.equals("forward") && chatImage && t.length() < 80 ? 2 : tp.need;   // a caption on a picture says little
                if (p >= needP && p > best) { best = p; bestKind = tp.kind; bestWhy = w.toString(); }
            }
            if (bestKind != null) { kind = bestKind; a.reasons.add(label(kind) + ": " + bestWhy); }
            if ("coupon".equals(kind)) {
                long exp = find(EXPIRY, t, f.savedAt);
                if (exp > 0) { a.usefulUntil = exp; anchor = exp; } else kind = "coupon_nodate";
            } else if ("delivery".equals(kind)) {
                long d = find(DELIVERED, t, f.savedAt);
                if (d > 0 && d <= now) anchor = Math.max(anchor, d);
                if (d > now) { a.usefulUntil = d; anchor = d; }
            } else if ("appointment".equals(kind) || "invitation".equals(kind)) {
                long d = TextRules.date(t, now);
                if (d == 0) d = find(ANY_DATE, t, f.savedAt);
                if (d > 0) { a.usefulUntil = d; anchor = d; }
                else if ("appointment".equals(kind)) anchor = f.savedAt + 14 * DAY;   // no date read: assume it is within two weeks
            }
        }
        if (kind == null) {
            if (f.textPending) kind = "unread";
            else if (c.equals(PhotoCategory.DOCUMENT)) kind = "document";
            else if (c.equals(PhotoCategory.GRAPHIC)) kind = "graphic";
            else if (c.equals(PhotoCategory.SCREENSHOT)) kind = "screenshot";
            else if (c.equals(PhotoCategory.PHOTO)) kind = "photo";
            else kind = "screenshot";
        }
        a.kind = kind; a.kindLabel = label(kind);
        Life l = LIFE.get(kind);

        // 2. how useful it still is
        double days = (now - anchor) / (double) DAY;
        double need;
        if (a.usefulUntil > now) { need = Math.max(l.base, 0.9); a.reasons.add("Still valid until " + Report.day(a.usefulUntil)); }
        else if (days <= l.lifeDays) need = l.base;
        else need = l.floor + (l.base - l.floor) * Math.pow(0.5, (days - l.lifeDays) / l.halfDays);
        if (a.usefulUntil > 0 && a.usefulUntil <= now) a.reasons.add(dateWhy(kind) + " " + Report.day(a.usefulUntil) + ", " + ago(now - a.usefulUntil));

        // 3. what the picture's surroundings say
        String folder = folder(f.path);
        if (folder != null && need < 0.75) { need = 0.75; a.reasons.add("You saved it in the folder \"" + folder + "\""); }
        if (f.faces > 0 && !kind.equals("people") && need < 0.35 && !kind.equals("otp") && !kind.equals("accidental")) {
            need = 0.35; a.reasons.add("Has " + (f.faces > 1 ? f.faces + " faces" : "a face") + " in it");
        }
        if (f.textPending && need < 0.35) { need = 0.35; a.reasons.add("Its text is not read yet"); }

        a.need = Math.max(0, Math.min(1, need));
        a.verdict = a.need >= KEEP_AT ? Decision.KEEP : a.need >= DELETE_BELOW ? Decision.UNSURE : Decision.NOT_NEEDED;
        if (a.verdict == Decision.NOT_NEEDED) a.technique = l.technique;
        // when it will stop being needed, if nothing else changes
        if (a.verdict != Decision.NOT_NEEDED && l.floor < DELETE_BELOW && folder == null && f.faces <= 0 && !f.textPending) {
            double after = l.halfDays * Math.log((l.base - l.floor) / (DELETE_BELOW - l.floor)) / Math.log(2);
            a.deletableFrom = anchor + (long) ((l.lifeDays + after) * DAY);
            if (a.usefulUntil > now) a.deletableFrom = Math.max(a.deletableFrom, a.usefulUntil + DAY);
        }
        a.why = summary(a, l, f, now);
        return a;
    }

    /** A few words with a greeting in them (the sorter may have missed it on a plain card, which is not "photo-like"). */
    static boolean greeting(String t) {
        if (t.length() > 120) return false;
        return TextRules.hits(t, TextRules.GREET, null) >= 1 || TextRules.prefixHits(t, TextRules.GREET_WORDS, null) >= 1;
    }

    static String payment(String t, Decision a) {
        if (any(t, FAILED) && !any(t, "successful", "successfully")) {
            if (any(t, DEBITED) && !any(t, NOT_DEBITED)) { a.reasons.add("Failed payment with money debited: kept until the refund is likely done"); return "payment_pending"; }
            a.reasons.add("The payment did not go through"); return "payment_failed";
        }
        if (any(t, PENDING)) { a.reasons.add("Payment still pending or being refunded"); return "payment_pending"; }
        double amt = amount(t);
        StringBuilder w = new StringBuilder();
        for (String k : PURPOSE) if (has(t, k)) { note(w, k); }
        if (w.length() > 0 || amt >= 10000) {
            a.reasons.add(amt >= 10000 ? "Large payment (about ₹" + String.format(Locale.ROOT, "%,d", Math.round(amt)) + "): proof of payment"
                    : "Payment for " + w + ": proof of payment");
            return "payment_proof";
        }
        if (amt >= 2000) { a.reasons.add("Payment of about ₹" + String.format(Locale.ROOT, "%,d", Math.round(amt)) + ": kept for a year"); return "payment_big"; }
        if (amt > 0) { a.reasons.add("Small payment (about ₹" + Math.round(amt) + "); your bank and payment app keep the record"); return "payment"; }
        a.reasons.add("Payment confirmation whose amount could not be read: it may be a large one");
        return "payment_unknown";
    }

    static String dateWhy(String kind) {
        if (kind.startsWith("coupon")) return "Expired on";
        if (kind.startsWith("ticket")) return "Trip or show was on";
        if (kind.equals("delivery")) return "Delivery was due";
        return "The date was";
    }

    /** A folder the user named themselves (not Camera, Screenshots, WhatsApp ...), when it says the picture matters. */
    static String folder(String rel) {
        String[] p = rel.split("/");
        for (int i = 0; i < p.length - 1; i++) {
            String s = p[i].toLowerCase(Locale.ROOT).trim();
            for (String k : FOLDER_KEEP)
                if (s.equals(k) || s.startsWith(k + " ") || s.endsWith(" " + k) || s.contains(" " + k + " ")) {
                    boolean standard = false;
                    for (String st : STANDARD_FOLDERS) if (s.equals(st)) standard = true;
                    if (!standard) return p[i];
                }
        }
        return null;
    }

    static String ago(long ms) {
        long d = ms / DAY;
        if (d < 1) return "today";
        if (d < 60) return d + (d == 1 ? " day ago" : " days ago");
        if (d < 730) return (d / 30) + " months ago";
        return (d / 365) + " years ago";
    }

    static String summary(Decision a, Life l, PictureFacts f, long now) {
        String main = a.reasons.isEmpty() ? l.label : a.reasons.get(0);
        if (a.verdict == Decision.NOT_NEEDED) {
            String last = a.reasons.size() > 1 ? a.reasons.get(a.reasons.size() - 1) : null;
            String age = "saved " + ago(now - f.savedAt);
            return main + (last != null && !last.equals(main) ? "; " + last : "") + "; " + age;
        }
        if (a.deletableFrom > now) return main + "; not needed after about " + Report.day(a.deletableFrom);
        return main;
    }
}
