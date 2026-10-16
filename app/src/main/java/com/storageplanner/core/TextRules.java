package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of picture text is, from the words the text reader found: one-time codes, payment
 * confirmations, tickets, receipts, bills, chats, greetings, ID documents, or just text. Keyword evidence
 * is counted per kind; the strongest kind with enough evidence wins. Written for Indian phones first
 * (UPI apps, IRCTC, GST) but the words are general.
 */
public final class TextRules {
    private TextRules() {}

    /** The reader's words with their confidence (0-100). */
    public static final class Words {
        public final List<String> text = new ArrayList<String>();
        public final List<Integer> conf = new ArrayList<Integer>();
        public void add(String t, int c) { text.add(t); conf.add(c); }
    }

    public static final class Verdict {
        public String kind;             // one of PhotoCategory ids, or null when the text says nothing specific
        public int goodWords;           // words read with confidence
        public long date;               // a date found in the text (ms, local midnight), 0 if none
        public String why = "";         // the evidence, for the UI
        public int score;
    }

    /** Words read confidently: at least 2 characters, mostly letters or digits, confidence >= 70. */
    public static int goodWords(Words w) {
        int n = 0;
        for (int i = 0; i < w.text.size(); i++) {
            String t = w.text.get(i);
            if (w.conf.get(i) < 70 || t.length() < 2) continue;
            int ok = 0;
            for (int k = 0; k < t.length(); k++) if (Character.isLetterOrDigit(t.charAt(k))) ok++;
            if (ok * 3 >= t.length() * 2) n++;
        }
        return n;
    }

    /** Lower-case text of the words read with some confidence, single spaces. */
    public static String clean(Words w) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.text.size(); i++) if (w.conf.get(i) >= 45) b.append(w.text.get(i)).append(' ');
        return b.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ evidence
    static final String[] OTP = {"otp", "one time password", "one-time password", "verification code", "security code", "login code",
            "passcode", "do not share", "don't share", "never share", "valid for", "expires in"};
    static final String[] PAY = {"paid to", "payment successful", "paid successfully", "money sent", "transaction successful", "sent successfully",
            "upi transaction id", "upi ref", "upi id", "utr", "google pay", "gpay", "phonepe", "paytm", "bhim", "debited from", "received from",
            "transaction id", "payment received", "from:", "to:", "powered by", "split bill", "@ok", "@ybl", "@paytm", "@upi", "@axl", "@ibl"};
    static final String[] TICKET = {"pnr", "boarding pass", "e-ticket", "eticket", "date of journey", "train no", "coach", "berth", "gate", "seat",
            "seats", "flight", "booking id", "booking confirmed", "screen", "boarding", "departure", "arrival", "irctc", "show time",
            "sequence", "passenger", "platform", "bus no", "quota", "cnf", "indigo", "air india", "vistara", "akasa", "pvr", "inox", "bookmyshow"};
    static final String[] RECEIPT = {"total", "cash", "change", "subtotal", "sub total", "qty", "receipt", "cashier", "thank you", "come again",
            "rounding", "round off", "item", "price", "amount", "tax", "gst", "cgst", "sgst", "mrp", "discount", "bill no", "table",
            "goods sold", "net amt", "payment mode", "card", "invoice", "counter"};
    static final String[] BILL = {"invoice", "invoice no", "invoice number", "invoice date", "due date", "bill to", "ship to", "billing",
            "gstin", "hsn", "account number", "amount due", "customer id", "order id", "order number", "vat", "iban", "payment terms",
            "billing period", "consumer no", "units consumed", "meter", "statement", "balance due", "description", "unit price", "tax invoice",
            "purchase order", "terms", "bank details", "pan no", "place of supply", "factuur", "facture", "rechnung", "fattura", "factura",
            "invoice date", "date of invoice", "billed to", "sold by", "total due", "net amount", "subscription", "period"};
    static final String[] RECEIPT_ONLY = {"cash", "change", "cashier", "thank you", "come again", "goods sold", "rounding", "round off",
            "table", "counter", "server", "pos", "tender", "visa", "mastercard", "card no", "bill no"};
    static final String[] INVOICE_WORD = {"invoice", "tax invoice", "factuur", "facture", "rechnung", "fattura", "factura", "bill to", "billed to", "due date", "amount due", "total due"};
    static final String[] CHAT = {"type a message", "message", "online", "last seen", "typing", "today", "yesterday", "read", "delivered",
            "voice call", "video call", "whatsapp", "telegram", "you:", "replied"};
    static final String[] GREET = {"good morning", "good night", "good evening", "good afternoon", "happy birthday", "happy diwali", "happy holi",
            "happy new year", "happy sunday", "happy monday", "happy friday", "have a blessed day", "have a nice day", "have a great day",
            "stay blessed", "sweet dreams", "shubh", "suprabhat", "subh", "happy dussehra", "eid mubarak", "merry christmas", "happy pongal",
            "happy onam", "happy independence", "happy republic", "happy raksha", "happy ganesh", "good day", "gm", "happy janmashtami",
            "happy navratri", "happy ugadi", "happy lohri", "happy mother", "happy father", "happy anniversary", "best wishes", "warm wishes"};
    /** Single greeting words, matched at the start of a word ("birthdays", "blessings"). */
    static final String[] GREET_WORDS = {"happy", "morning", "blessed", "blessing", "wishes", "birthday", "diwali", "holi", "shubh",
            "suprabhat", "dreams", "evening", "goodnight", "festival", "greetings", "congratulations", "eid", "christmas", "pongal", "navratri"};

    static int prefixHits(String t, String[] keys, StringBuilder why) {
        int n = 0;
        for (String k : keys) {
            int i = t.indexOf(k);
            while (i >= 0 && i > 0 && Character.isLetterOrDigit(t.charAt(i - 1))) i = t.indexOf(k, i + 1);
            if (i >= 0) { n++; if (why != null && why.length() < 60) why.append(why.length() == 0 ? "" : ", ").append('"').append(k).append('"'); }
        }
        return n;
    }

    static final String[] ID = {"government of india", "govt of india", "aadhaar", "aadhar", "unique identification", "income tax department",
            "permanent account number", "republic of india", "passport", "driving licence", "driving license", "election commission",
            "elector", "voter", "date of birth", "dob", "enrolment no", "vid :", "mera aadhaar", "nationality", "father's name", "uidai"};

    static final Pattern AMOUNT = Pattern.compile("(₹|rs\\.?|inr|rm|\\$|€|£)\\s?\\d[\\d,]*(\\.\\d{1,2})?|\\b\\d{1,3}(,\\d{2,3})+(\\.\\d{2})?\\b|\\b\\d+\\.\\d{2}\\b");
    static final Pattern CODE = Pattern.compile("\\b\\d{4,8}\\b");
    static final Pattern TIME = Pattern.compile("\\b\\d{1,2}[:.]\\d{2}\\s?(am|pm)\\b");
    static final Pattern AADHAAR = Pattern.compile("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b");
    static final Pattern PAN = Pattern.compile("\\b[a-z]{5}\\d{4}[a-z]\\b");

    static int hits(String t, String[] keys, StringBuilder why) {
        int n = 0;
        for (String k : keys) {
            int i = t.indexOf(k);
            if (i < 0) continue;
            boolean startOk = i == 0 || !Character.isLetterOrDigit(t.charAt(i - 1)) || !Character.isLetterOrDigit(k.charAt(0));
            int e = i + k.length();
            boolean endOk = e >= t.length() || !Character.isLetterOrDigit(t.charAt(e)) || !Character.isLetterOrDigit(k.charAt(k.length() - 1));
            if (startOk && endOk) { n++; if (why != null && why.length() < 60) why.append(why.length() == 0 ? "" : ", ").append('"').append(k).append('"'); }
        }
        return n;
    }

    static int count(Pattern p, String t) { int n = 0; Matcher m = p.matcher(t); while (m.find()) n++; return n; }

    /**
     * Decide what the text is. aspect = width / height of the picture; photoLike = the picture is a colourful
     * photo or graphic rather than paper or a plain screen; screenshot = taken with the phone's screenshot key.
     */
    public static Verdict classify(Words w, double aspect, boolean photoLike, boolean screenshot, long now) {
        return classify(w, aspect, photoLike, screenshot, false, now);
    }

    /** chatImage: the picture was received in a chat app (where greeting images live). */
    public static Verdict classify(Words w, double aspect, boolean photoLike, boolean screenshot, boolean chatImage, long now) {
        Verdict v = new Verdict();
        v.goodWords = goodWords(w);
        String t = clean(w);
        // greetings are written in decorative letters on pictures: one readable greeting word is enough there
        StringBuilder wg = new StringBuilder();
        int g0 = hits(t, GREET, wg), g1 = prefixHits(t, GREET_WORDS, wg);
        if ((g0 >= 1 || g1 >= 1 && chatImage) && photoLike && v.goodWords <= 40 && hits(t, PAY, null) == 0 && hits(t, OTP, null) == 0)
            return pick(v, PhotoCategory.GREETING, 6 + 2 * g0 + g1, wg, t, now);
        if (v.goodWords < 3 || t.length() < 8) return v;
        int amounts = count(AMOUNT, t), times = count(TIME, t);
        StringBuilder wOtp = new StringBuilder(), wPay = new StringBuilder(), wTk = new StringBuilder(), wRc = new StringBuilder(),
                wBl = new StringBuilder(), wCh = new StringBuilder(), wGr = new StringBuilder(), wId = new StringBuilder();
        int otp = hits(t, OTP, wOtp), pay = hits(t, PAY, wPay), tk = hits(t, TICKET, wTk), rc = hits(t, RECEIPT, wRc), bl = hits(t, BILL, wBl),
            ch = hits(t, CHAT, wCh), gr = hits(t, GREET, wGr), id = hits(t, ID, wId);
        boolean code = CODE.matcher(t).find();

        // sensitive first: an ID document is never treated as anything else
        int idScore = 3 * id + (AADHAAR.matcher(t).find() ? 3 : 0) + (PAN.matcher(t).find() ? 3 : 0);
        if (idScore >= 6 && pay < 2 && tk < 3) return pick(v, PhotoCategory.ID, idScore, wId, t, now);

        int[] s = new int[8];
        s[0] = otp >= 1 && code ? 4 * otp + 2 : 0;                                   // one-time code
        s[1] = pay >= 3 || pay >= 2 && amounts >= 1 ? 3 * pay + 2 : 0;               // payment confirmation
        s[2] = tk >= 3 ? 2 * tk : 0;                                                   // ticket / pass
        int ro = hits(t, RECEIPT_ONLY, null), inv = hits(t, INVOICE_WORD, null);
        boolean page = aspect >= 0.62 && aspect <= 0.85, narrow = aspect < 0.62;
        s[3] = rc >= 3 && amounts >= 3 && (narrow || ro >= 2) ? 2 * rc + Math.min(6, amounts / 2) + 2 * ro + (narrow ? 4 : 0) : 0;   // receipt
        s[4] = bl >= 2 && (amounts >= 1 || bl >= 4) || inv >= 1 && amounts >= 2 ? 2 * bl + 3 * inv + (page ? 4 : 0) + (v.goodWords >= 80 ? 2 : 0) : 0;  // bill / invoice
        s[5] = screenshot && (ch >= 2 && times >= 3 || times >= 5) ? 2 * ch + times : 0; // chat screenshot
        s[6] = gr >= 1 && photoLike && v.goodWords <= 40 ? 6 + 2 * gr : 0;            // greeting on a picture
        String[] kinds = {PhotoCategory.OTP, PhotoCategory.PAYMENT, PhotoCategory.TICKET, PhotoCategory.RECEIPT, PhotoCategory.BILL,
                PhotoCategory.CHAT, PhotoCategory.GREETING};
        StringBuilder[] whys = {wOtp, wPay, wTk, wRc, wBl, wCh, wGr};
        int best = -1;
        for (int i = 0; i < 7; i++) if (s[i] > 0 && (best < 0 || s[i] > s[best])) best = i;
        if (best >= 0) return pick(v, kinds[best], s[best], whys[best], t, now);
        if (v.goodWords >= 25 && !photoLike && !screenshot) return pick(v, PhotoCategory.DOCUMENT, v.goodWords, new StringBuilder(v.goodWords + " words"), t, now);
        return v;
    }

    private static Verdict pick(Verdict v, String kind, int score, StringBuilder why, String t, long now) {
        v.kind = kind; v.score = score; v.why = why.toString();
        v.date = date(t, now);
        return v;
    }

    // ------------------------------------------------------------------ dates
    static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    static final Pattern D_NUM = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})\\b");
    static final Pattern D_TXT = Pattern.compile("\\b(\\d{1,2})[ -]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[ ,-]*(\\d{2,4})\\b");
    static final Pattern D_ISO = Pattern.compile("\\b(20\\d{2})-(\\d{1,2})-(\\d{1,2})\\b");

    /** The latest plausible date in the text (day first, as written in India), not more than a year ahead; 0 if none. */
    public static long date(String t, long now) {
        long best = 0;
        Matcher m = D_NUM.matcher(t);
        while (m.find()) best = Math.max(best, ok(day(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))), now));
        m = D_TXT.matcher(t);
        while (m.find()) {
            int mon = 0; for (int i = 0; i < 12; i++) if (MON[i].equals(m.group(2))) mon = i + 1;
            best = Math.max(best, ok(day(Integer.parseInt(m.group(3)), mon, Integer.parseInt(m.group(1))), now));
        }
        m = D_ISO.matcher(t);
        while (m.find()) best = Math.max(best, ok(day(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), now));
        return best;
    }

    private static long ok(long d, long now) { return d > 0 && d <= now + 366L * 86400000L && d >= now - 30L * 366 * 86400000L ? d : 0; }

    static long day(int y, int m, int d) {
        if (y < 100) y += 2000;
        if (m < 1 || m > 12 || d < 1 || d > 31 || y < 1990 || y > 2100) return 0;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear(); c.set(y, m - 1, d);
        if (c.get(Calendar.DAY_OF_MONTH) != d) return 0;
        return c.getTimeInMillis();
    }

    /** The reader's words as written by the page: JSON.stringify of [["word", conf], ...]. */
    public static Words parseJson(String json) {
        Words w = new Words();
        int i = 0, n = json.length();
        while (true) {
            int a = json.indexOf("[\"", i);
            if (a < 0) break;
            StringBuilder b = new StringBuilder();
            int k = a + 2;
            for (; k < n; k++) {
                char c = json.charAt(k);
                if (c == '\\' && k + 1 < n) {
                    char d = json.charAt(++k);
                    if (d == 'u' && k + 4 < n) { b.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16)); k += 4; }
                    else if (d == 'n') b.append('\n'); else if (d == 't') b.append('\t'); else b.append(d);
                } else if (c == '"') break;
                else b.append(c);
            }
            int comma = json.indexOf(',', k), end = json.indexOf(']', k);
            if (comma < 0 || end < 0) break;
            int conf;
            try { conf = Integer.parseInt(json.substring(comma + 1, end).trim()); } catch (NumberFormatException e) { conf = 0; }
            w.add(b.toString(), conf);
            i = end + 1;
        }
        return w;
    }
}
