package com.storageplanner.core;

import java.util.List;

/**
 * Sorting algorithm v3: the picture's category from Mirage (Cactus Compute's vision model, see Mirage) instead of the
 * app's own text reader. v2, the app's algorithm today, is PhotoCategory.classify with Tesseract's text (Lab.readAndJudge);
 * both then go through the same rules. v3 is a separate mechanism: it never changes v2 or the scan.
 *
 * Mirage has no fixed list of labels: its decoder writes object names, scene words and a style freely (an 8,192-token
 * vocabulary). v3 maps what it says onto the app's own categories (PhotoCategory.ALL):
 *  - its text, cleaned up (Mirage.clean), goes through the same text rules as Tesseract's (TextRules): one-time codes,
 *    payments, tickets with their date, receipts, bills, chats, greetings, ID documents, documents;
 *  - a picture the pixel checks call accidental is not, when Mirage reads its text or clearly names something in it;
 *  - a picture received in a chat or saved (Download, Pictures) that Mirage calls drawn, or a collage with captions, and
 *    whose text is none of the kinds above, is Graphics & images (a meme or forward); a drawn picture or comic with many
 *    words is Graphics & images, not Documents & text;
 *  - everything else is sorted as v2 sorts it (codes, screenshots, faces, pages of paper).
 */
public final class AlgorithmV3 {
    private AlgorithmV3() {}

    public static final String NAME = "v3 (Mirage)", V2_NAME = "v2 (current)";

    /** A collage is a comic strip or meme, not a photo collage, from this many words read confidently. */
    public static final int MEME_WORDS = 8;

    /** v3's answer for one picture, with what it was decided from. */
    public static final class Result {
        public PhotoCategory.Result category;
        public Mirage.Clean clean;
        public TextRules.Verdict text;
        public String style;                 // Mirage.style: photograph, screenshot, graphic, collage or null
        public int people;
        public boolean textUsed;             // the text decided (a scan reads pictures like this, or Mirage proved it is no accident)
        public String recognised;            // what Mirage clearly recognised, or null
    }

    /**
     * Sorts one picture with Mirage's answer. f: the picture as a scan lists it, after Sorter.measure (faces, code);
     * s, aspect: its measurements; read: whether a scan reads the text of a picture like this (PhotoCategory.worthReading).
     */
    public static Result sort(FileItem f, ImageSignals.S s, double aspect, boolean read, Mirage.Answer a, long now) {
        Result r = new Result();
        boolean chat = f.area != null && (f.area.startsWith("wa_") || "telegram_media".equals(f.area));
        boolean photoLike = s != null && s.colorful >= 15 && s.flat < 0.5;
        r.clean = Mirage.clean(a.text);
        r.style = Mirage.style(a);
        r.people = Mirage.people(a);
        boolean graphic = "graphic".equals(r.style);
        TextRules.Verdict vm = TextRules.classify(Mirage.words(r.clean.kept), aspect, photoLike || graphic, PhotoCategory.isScreenshot(f), chat, now);
        r.text = vm;
        // the pixel checks can call a real picture "accidental" (smooth, little contrast); what Mirage recognised overrules that
        r.recognised = recognised(a, vm);
        boolean accidental = s != null && PhotoCategory.accidental(s);
        r.textUsed = read || accidental && r.recognised != null && !PhotoCategory.isCamera(f);
        PhotoCategory.Result c = PhotoCategory.classify(f, s, f.code, r.textUsed ? vm : null, f.faces);
        if (PhotoCategory.ACCIDENTAL.equals(c.category) && r.recognised != null) {
            c = PhotoCategory.classify(f, null, f.code, r.textUsed ? vm : null, f.faces);
            if (PhotoCategory.PHOTO.equals(c.category) && r.people > 0) c.category = PhotoCategory.PEOPLE;
            if (c.why == null || c.why.isEmpty()) c.why = "Mirage recognised " + r.recognised + ": not an accidental shot";
        }
        String cat = c.category;
        // a meme or forward: received in a chat or saved, drawn (people in it are drawings), or a collage with captions (a
        // comic strip; a collage of photos has little text), and its text is not a ticket, bill, ID... (those keep their kind)
        boolean captioned = "collage".equals(r.style) && vm.goodWords >= MEME_WORDS;
        boolean saved = chat || "download".equals(f.area) || "pictures".equals(f.area);     // received or saved, not your own photos
        if (saved && vm.kind == null && (graphic || captioned) && !PhotoCategory.ID.equals(cat) && !PhotoCategory.QR.equals(cat)) {
            c.category = PhotoCategory.GRAPHIC;
            c.why = "Mirage: " + (graphic ? "drawn as \"" + styleWords(a) + "\", not a photograph" : "a collage with captions (" + vm.goodWords + " words)")
                    + (chat ? ", received in a chat" : ", saved") + ": a meme or forward";
        } else if (vm.kind == null && (graphic || captioned) && PhotoCategory.DOCUMENT.equals(cat) && c.why != null && c.why.endsWith(" words")) {
            c.category = PhotoCategory.GRAPHIC;               // lots of words, but in a drawn picture or comic, not a page of text
            c.why = "Mirage: " + (graphic ? "drawn as \"" + styleWords(a) + "\"" : "a collage with captions") + ", not a page of text";
        }
        r.category = c;
        return r;
    }

    /** What Mirage clearly recognised (readable text, or an object named with a good score), or null. */
    static String recognised(Mirage.Answer a, TextRules.Verdict text) {
        if (text.goodWords >= 3) return text.goodWords + " words";
        for (Mirage.Thing t : a.objects) if (!"text".equals(t.name) && t.score >= 0.5) return "a " + t.name;
        return null;
    }

    static String styleWords(Mirage.Answer a) {
        for (String x : a.scene) if (x.startsWith("style ")) return x.substring(6);
        return "a graphic";
    }

    /** "Delete — Memes and forwards", "Compress — Re-encode chat images" or "Keep": the plan's first choice for a picture. */
    public static String action(List<Option> ops, String category) {
        for (Option op : ops) if ("delete".equals(op.action)) return "Delete — " + op.technique;
        if (!ops.isEmpty()) return Sample.actionWord(ops.get(0).action) + " — " + ops.get(0).technique;
        return PhotoCategory.ID.equals(category) ? "Keep (ID document, never deleted)" : "Keep";
    }
}
