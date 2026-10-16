package com.storageplanner.core;

import java.util.Locale;

/**
 * Sorting pictures into categories for browsing and for deletion suggestions. Uses, in this order:
 * file details (screenshot? chat app? download?), codes found in the picture, the text in it (when read),
 * and picture measurements (text rows, paper, flat screen areas, darkness, blur, faces).
 */
public final class PhotoCategory {
    private PhotoCategory() {}

    public static final String QR = "qr", RECEIPT = "receipt", BILL = "bill", DOCUMENT = "document", PAYMENT = "payment", OTP = "otp",
            TICKET = "ticket", CHAT = "chat", GREETING = "greeting", ID = "id", SCREENSHOT = "screenshot", ACCIDENTAL = "accidental",
            PEOPLE = "people", PHOTO = "photo", GRAPHIC = "graphic";

    /** Every category, in the order shown when browsing. */
    public static final String[] ALL = {PEOPLE, PHOTO, SCREENSHOT, CHAT, PAYMENT, OTP, TICKET, RECEIPT, BILL, DOCUMENT, QR, GREETING, ACCIDENTAL, GRAPHIC, ID};

    public static String label(String c) {
        if (c == null) return "Not sorted yet";
        switch (c) {
            case QR: return "QR codes & barcodes";
            case RECEIPT: return "Receipts";
            case BILL: return "Bills & invoices";
            case DOCUMENT: return "Documents & text";
            case PAYMENT: return "Payment screenshots";
            case OTP: return "One-time codes";
            case TICKET: return "Tickets & passes";
            case CHAT: return "Chat screenshots";
            case GREETING: return "Greetings & forwards";
            case ID: return "ID documents (sensitive)";
            case SCREENSHOT: return "Other screenshots";
            case ACCIDENTAL: return "Accidental shots";
            case PEOPLE: return "People";
            case PHOTO: return "Photos without faces";
            case GRAPHIC: return "Graphics & images";
            default: return c;
        }
    }

    public static String describe(String c) {
        if (c == null) return "";
        switch (c) {
            case QR: return "Pictures of QR codes and barcodes; the code is read, so you can see what it was for.";
            case RECEIPT: return "Shop and restaurant receipts.";
            case BILL: return "Invoices and bills (utility, phone, online orders).";
            case DOCUMENT: return "Pages, notes and other pictures that are mostly text.";
            case PAYMENT: return "UPI and bank payment confirmations. Your bank statement keeps these too.";
            case OTP: return "Screenshots of one-time passwords and login codes; useless after a few minutes.";
            case TICKET: return "Train, flight, bus and movie tickets and boarding passes.";
            case CHAT: return "Screenshots of chat conversations.";
            case GREETING: return "Good-morning, festival and other greeting images, mostly forwarded in chats.";
            case ID: return "Aadhaar, PAN, passport, licence and similar. Never suggested for deletion; keep them somewhere safe.";
            case SCREENSHOT: return "Screenshots that are none of the above.";
            case ACCIDENTAL: return "Very dark, covered or badly shaken shots.";
            case PEOPLE: return "Photos with faces.";
            case PHOTO: return "Places, things, food, pets, nature: photos with no faces and no text.";
            case GRAPHIC: return "Images that are not camera photos: stickers, wallpapers, designs.";
            default: return "";
        }
    }

    /** What sorting found for one picture. */
    public static final class Result {
        public String category;
        public String code;            // text of a QR code or barcode, if any
        public long date;              // date found in the text (tickets, receipts), 0 if none
        public String why = "";
    }

    /**
     * Picks the category. area: storage area from the Classifier; sig: picture measurements; code: decoded
     * QR/barcode text or null; text: the reader's verdict or null when the text was not read; faces: faces found
     * (-1 = not checked).
     */
    public static Result classify(FileItem f, ImageSignals.S sig, String code, TextRules.Verdict text, int faces) {
        Result r = new Result();
        r.code = code;
        boolean shot = isScreenshot(f);
        if (text != null && text.kind != null) {
            r.category = text.kind; r.date = text.date;
            r.why = text.why.isEmpty() ? "" : "Text: " + text.why;
            boolean twoD = code != null && !code.startsWith("Barcode");      // 1-D "barcodes" are easily seen in lines of text
            if (twoD && (text.kind.equals(DOCUMENT) || text.kind.equals(GREETING))) { r.category = QR; r.why = "Contains a code"; }
            if (shot && text.kind.equals(DOCUMENT)) { r.category = SCREENSHOT; r.why = ""; }
            return r;
        }
        if (code != null) { r.category = QR; r.why = "Contains a code"; return r; }
        if (sig == null) { r.category = shot ? SCREENSHOT : PHOTO; return r; }
        if (shot) { r.category = SCREENSHOT; return r; }
        if (accidental(sig)) { r.category = ACCIDENTAL; r.why = sig.darkFrac > 0.6 ? "Almost completely dark" : "Very blurred, no detail"; return r; }
        if (text != null && text.goodWords >= 25) { r.category = DOCUMENT; r.why = text.goodWords + " words"; return r; }
        if (sig.flat >= 0.35 && !isCamera(f) && !sig.wordy() && (text == null || text.goodWords < 5)) { r.category = GRAPHIC; return r; }
        if (sig.flat >= 0.35 && !isCamera(f)) { r.category = DOCUMENT; r.why = "Mostly text"; return r; }
        if (sig.wordy() && sig.paper >= 0.3 && sig.flat >= 0.12) { r.category = DOCUMENT; r.why = "Looks like a page of text"; return r; }
        r.category = faces > 0 ? PEOPLE : PHOTO;
        return r;
    }

    /** Dark (lens covered, pocket), almost featureless, or so shaken that nothing is sharp anywhere; never with text. */
    public static boolean accidental(ImageSignals.S s) {
        if (s.textBlobs >= 20 || s.flat >= 0.3) return false;
        if (s.contrast < 16) return true;                                  // nothing in the picture
        if (s.meanLuma < 25 && s.contrast < 30) return true;               // dark
        return s.sharp >= 0 && s.sharp < 0.12 && s.meanLuma > 25;          // shaken: no sharp detail anywhere
    }

    public static boolean isScreenshot(FileItem f) {
        String n = f.rel.toLowerCase(Locale.ROOT);
        return "screenshot".equals(f.area) || n.contains("screenshot");
    }

    public static boolean isCamera(FileItem f) { return "camera".equals(f.area); }

    /** Pictures worth reading the text of: screenshots, downloads, chat images, and photos that show text or paper. */
    public static boolean worthReading(FileItem f, ImageSignals.S s) {
        if (s == null || accidental(s)) return false;
        if (isScreenshot(f) || "download".equals(f.area) || f.area.startsWith("wa_") || "pictures".equals(f.area) || "document".equals(f.area)) return true;
        if (s.flat >= 0.15) return true;                        // paper, screens, printed pages photographed straight
        return s.textBlobs >= 60 && s.textLines >= 4;
    }

    /** Pictures that are sorted at all (photos and images people look at; not app caches or thumbnails). */
    public static boolean sortable(FileItem f) {
        if (f.type == null) return false;
        boolean img = f.type.equals("jpeg") || f.type.equals("png") || f.type.equals("webp") || f.type.equals("heic");
        if (!img) return false;
        String a = f.area;
        return a.equals("camera") || a.equals("screenshot") || a.equals("pictures") || a.equals("download") || a.equals("document")
                || a.equals("wa_image") || a.equals("wa_sent") || a.equals("telegram_media") || a.equals("user_other") || a.equals("wa_status");
    }
}
