package com.storageplanner.v2test;

import com.storageplanner.v2.content.*;
import com.storageplanner.v2.content.doc.PdfText;
import com.storageplanner.v2.features.Extractors;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.ContentCodec;
import com.storageplanner.v2.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Deflater;

/** Phase 6 S3a (PHASE_6 7.2): PDF text extraction on PDFs built byte by byte, hostile inputs, and the DOC pass of the stage. */
public final class PdfTextTest {
    static byte[] a(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    static byte[] flate(byte[] d) {
        Deflater df = new Deflater();
        df.setInput(d); df.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!df.finished()) { int n = df.deflate(buf); o.write(buf, 0, n); }
        df.end();
        return o.toByteArray();
    }

    /** The 20-line ASCII85 encoder (with the '~>' end marker). */
    static byte[] ascii85(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 4) {
            int n = Math.min(4, d.length - i);
            long v = 0;
            for (int j = 0; j < 4; j++) v = (v << 8) | (j < n ? d[i + j] & 0xFF : 0);
            if (v == 0 && n == 4) { sb.append('z'); continue; }
            char[] c = new char[5];
            for (int j = 4; j >= 0; j--) { c[j] = (char) (v % 85 + '!'); v /= 85; }
            sb.append(c, 0, n + 1);
        }
        return a(sb.append("~>").toString());
    }

    /** Stream object: dictionary extras (e.g. "/Filter /FlateDecode") and data; direct /Length unless length is given as text. */
    static byte[] stream(int num, String extras, byte[] data, String lengthText) {
        String len = lengthText != null ? lengthText : String.valueOf(data.length);
        return FxImg.cat(a(num + " 0 obj\n<< /Length " + len + " " + extras + " >>\nstream\n"), data, a("\nendstream\nendobj\n"));
    }

    static byte[] obj(int num, String body) { return a(num + " 0 obj\n" + body + "\nendobj\n"); }

    static byte[] pdf(byte[]... parts) {
        return FxImg.cat(a("%PDF-1.4\n"), FxImg.cat(parts), a("trailer\n<< /Root 1 0 R >>\n%%EOF\n"));
    }

    static byte[] pageObjs() {
        return FxImg.cat(obj(1, "<< /Type /Catalog /Pages 2 0 R >>"), obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                obj(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>"));
    }

    static byte[] page(String content, String extras, boolean ascii85, boolean fl) {
        byte[] d = a(content);
        String ex = "";
        if (fl) d = flate(d);
        if (ascii85) d = ascii85(d);
        if (ascii85 && fl) ex = "/Filter [/ASCII85Decode /FlateDecode]";
        else if (fl) ex = "/Filter /FlateDecode";
        else if (ascii85) ex = "/Filter /ASCII85Decode";
        return pdf(pageObjs(), stream(4, ex + " " + extras, d, null));
    }

    static final String STMT = "BT /F1 12 Tf 72 700 Td (Account Statement) Tj ET";

    static PdfText.Result x(byte[] b) { return PdfText.extract(b, null); }
    static String text(PdfText.Result r) { return r.text.toString(); }

    public static int run(String[] args) throws Exception {
        T.section("PdfText");
        basics();
        hostile();
        stageTests();
        datasetTest();
        return T.finish("PdfTextTest");
    }

    static void basics() {
        PdfText.Result r = x(page(STMT, "", false, false));
        T.eq("1 uncompressed OK", DocStatus.OK, r.status);
        T.check("1 text contains Account Statement", text(r).contains("Account Statement"));
        T.eq("1 pages 1", 1, r.pages);
        r = x(page(STMT, "", false, true));
        T.check("2 flate", r.status == DocStatus.OK && text(r).contains("Account Statement") && r.pages == 1);
        r = x(page(STMT, "", true, true));
        T.check("3 [ASCII85 Flate]", r.status == DocStatus.OK && text(r).contains("Account Statement") && r.unsupported == 0);
        r = x(page(STMT, "", true, false));
        T.check("3b ASCII85 alone", text(r).contains("Account Statement"));
        r = x(page("BT /F1 12 Tf [(Ac) -250 (count) 10 ( Summary of the year)] TJ ET", "", false, false));
        T.check("4 TJ gap inserts a space, small gap does not", text(r).contains("Ac count Summary of the year"));
        r = x(page("BT (a\\(b\\)c\\\\d\\101 and some more words) Tj ET", "", false, false));
        T.check("5 escapes", text(r).contains("a(b)c\\dA and"));
        r = x(page("BT (line one\\\ncontinued) Tj (\\0501) Tj (x\\n) Tj ET", "", false, false));
        T.check("5b continuation, octal and \\n", text(r).startsWith("line onecontinued(1x"));
        r = x(page("BT (nested (parens (deep)) ok) Tj ET", "", false, false));
        T.check("5c nested parens", text(r).contains("nested (parens (deep)) ok"));
        // 6: ToUnicode CMap + hex string
        String cm = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                + "2 beginbfchar <0048> <0048> <0049> <0049> endbfchar\n1 beginbfrange <0041> <0043> <0061> endbfrange\nendcmap";
        byte[] p6 = pdf(pageObjs(), stream(5, "", a(cm), null), stream(4, "", a("BT <00480049> Tj <004100420043> Tj ET"), null));
        r = x(p6);
        T.eq("6 hex via CMap", "HIabc", text(r).trim());
        T.eq("6 status OK with 5 chars", DocStatus.OK, r.status);
        // CMap inside Flate, bfrange with array
        String cm2 = "begincmap 1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfrange <41> <42> [<0058> <0059>] endbfrange endcmap";
        r = x(pdf(pageObjs(), stream(5, "/Filter /FlateDecode", flate(a(cm2)), null), stream(4, "", a("BT <4142> Tj ET"), null)));
        T.eq("6b 1-byte CMap with bfrange array", "XY", text(r).trim());
        // 7: 2-byte hex without CMap is ignored; other text counts
        r = x(pdf(pageObjs(), stream(4, "", a("BT <00480049> Tj (This is more than twenty chars.) Tj ET"), null)));
        T.check("7 hex ignored, rest OK", r.status == DocStatus.OK && text(r).equals("This is more than twenty chars.") && r.text.length() >= 20);
        r = x(pdf(pageObjs(), stream(4, "", a("BT <00480049> Tj ET"), null)));
        T.check("7b only hex without CMap: not OK text (chars < 20)", r.text.length() == 0 && r.status != DocStatus.NO_TEXT_LAYER);
        // 8: encrypted
        PdfInfo enc = new PdfInfo(); enc.encrypted = Tri.TRUE;
        r = PdfText.extract(page(STMT, "", false, false), enc);
        T.check("8 PdfInfo encrypted -> ENCRYPTED, no text, pages unknown", r.status == DocStatus.ENCRYPTED && r.text.length() == 0 && r.pages == -1);
        byte[] withEnc = FxImg.cat(a("%PDF-1.6\n"), pageObjs(), stream(4, "", a(STMT), null), a("trailer\n<< /Root 1 0 R /Encrypt 9 0 R >>\n%%EOF"));
        T.eq("8b raw /Encrypt 9 0 R with no PdfInfo -> ENCRYPTED", DocStatus.ENCRYPTED, x(withEnc).status);
        byte[] withEnc2 = FxImg.cat(a("%PDF-1.6\n"), stream(4, "", a(STMT), null), a("trailer\n<< /Encrypt<</Filter/Standard>> >>"));
        T.eq("8c inline /Encrypt dictionary -> ENCRYPTED", DocStatus.ENCRYPTED, x(withEnc2).status);
        T.check("8d /EncryptMetadata alone is not encryption", x(FxImg.cat(a("%PDF-1.6\n"), pageObjs(), stream(4, "", a(STMT), null), a("<< /EncryptMetadata false >>"))).status == DocStatus.OK);
        // 9: image only
        byte[] img = pdf(pageObjs(), stream(5, "/Type /XObject /Subtype /Image /Width 2 /Height 2 /Filter /DCTDecode", new byte[]{1, 2, 3, 4}, null),
                stream(4, "", a("q 100 0 0 100 0 0 cm /Im0 Do Q"), null));
        r = x(img);
        T.check("9 image only -> NO_TEXT_LAYER, chars unknown by the caller", r.status == DocStatus.NO_TEXT_LAYER && r.unsupported == 0);
        T.check("9b ProcSet /ImageB with no image is not a scan", x(pdf(pageObjs(), stream(4, "", a("q Q"), null), a("<< /ProcSet [/PDF /Text /ImageB] >>"))).status != DocStatus.NO_TEXT_LAYER);
        // 10: 9 MiB of zeros
        byte[] zeros = new byte[9 << 20];
        long t0 = System.nanoTime();
        r = x(pdf(pageObjs(), stream(4, "/Filter /FlateDecode", flate(zeros), null), stream(6, "", a("BT (Visible text after the bomb stream) Tj ET"), null)));
        long ms = (System.nanoTime() - t0) / 1000000L;
        T.check("10 bomb stream skipped: unsupported 1, later text kept", r.unsupported == 1 && text(r).contains("Visible text"));
        T.check("10 under 1 s", ms < 1000);
        // 11: object stream holding the page object
        byte[] objstm = a("3 0 << /Type /Page /Parent 2 0 R >>");
        r = x(pdf(stream(5, "/Type /ObjStm /N 1 /First 4 /Filter /FlateDecode", flate(objstm), null), stream(4, "", a(STMT), null)));
        T.eq("11 pages from ObjStm", 1, r.pages);
        T.check("11 text found, status OK", r.status == DocStatus.OK && text(r).contains("Account Statement"));
        // 12: indirect /Length
        r = x(pdf(pageObjs(), stream(4, "", a(STMT), "5 0 R"), obj(5, "27")));
        T.check("12 indirect /Length -> uses endstream", text(r).contains("Account Statement"));
        // PdfInfo pages win; unknown when no page object
        PdfInfo pi = new PdfInfo(); pi.pages = 7; pi.encrypted = Tri.FALSE; pi.title = "T"; pi.author = "A";
        r = PdfText.extract(page(STMT, "", false, false), pi);
        T.check("8-metadata PdfInfo pages, title, author", r.pages == 7 && "T".equals(r.title) && "A".equals(r.author));
        String xmp = "<x:xmpmeta><rdf:RDF><rdf:Description><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Quarterly Statement</rdf:li></rdf:Alt></dc:title>"
                + "<dc:creator><rdf:Seq><rdf:li>Some Author</rdf:li></rdf:Seq></dc:creator></rdf:Description></rdf:RDF></x:xmpmeta>";
        r = x(pdf(pageObjs(), stream(5, "/Type /Metadata /Subtype /XML", a(xmp), null), stream(4, "", a(STMT), null)));
        T.check("8-metadata XMP title and creator (uncompressed)", "Quarterly Statement".equals(r.title) && "Some Author".equals(r.author));
        r = x(pdf(pageObjs(), stream(5, "/Type /Metadata /Subtype /XML /Filter /FlateDecode", flate(a(xmp)), null), stream(4, "", a(STMT), null)));
        T.check("8-metadata XMP (Flate)", "Quarterly Statement".equals(r.title));
        T.check("metadata stream is not text", !text(r).contains("rdf"));
        // statuses
        r = x(a(""));
        T.eq("empty input -> CORRUPT", DocStatus.CORRUPT, r.status);
        T.eq("not a pdf -> CORRUPT", DocStatus.CORRUPT, x(a("hello, this is plain text, not a pdf at all")).status);
        r = x(pdf(pageObjs(), stream(4, "/Filter /LZWDecode", new byte[]{1, 2, 3}, null)));
        T.check("LZW only -> UNSUPPORTED, unsupported 1", r.status == DocStatus.UNSUPPORTED && r.unsupported == 1);
        r = x(pdf(pageObjs(), stream(4, "/Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 5 >>", flate(a(STMT)), null)));
        T.check("predictor stream skipped and counted", r.status == DocStatus.UNSUPPORTED && r.unsupported == 1 && r.text.length() == 0);
        r = x(pdf(pageObjs(), stream(4, "", a("BT (Hi) Tj ET"), null)));
        T.check("a few words -> OK with chars as found", r.status == DocStatus.OK && text(r).equals("Hi"));
        r = x(pdf(pageObjs()));
        T.eq("no stream at all -> CORRUPT", DocStatus.CORRUPT, r.status);
        // newlines and operators
        r = x(page("BT 72 700 Td (First line here) Tj 0 -14 Td (Second line here) Tj T* (Third) Tj ET", "", false, false));
        T.eq("Td/T* insert newlines, none leading", "First line here\nSecond line here\nThird", text(r));
        r = x(page("BT (One) ' (Two) ' 1 2 (Three) \" ET", "", false, false));
        T.eq("' and \" operators start a new line", "One\nTwo\nThree", text(r).replace("\n\n", "\n"));
        // control-heavy stream dropped (Identity-H literal strings without CMap)
        StringBuilder ctl = new StringBuilder("BT (");
        for (int i = 0; i < 50; i++) ctl.append("\\000\\001");
        ctl.append(") Tj ET");
        r = x(pdf(pageObjs(), stream(4, "", a(ctl.toString()), null), stream(6, "", a("BT (Readable text on page two) Tj ET"), null)));
        T.eq("control-character stream dropped", "Readable text on page two", text(r));
        // inline image data is skipped
        r = x(page("BT (Before the image) Tj ET q 10 0 0 10 0 0 cm BI /W 2 /H 2 /BPC 8 /CS /G ID (not text) Tj BT EI Q BT (After image) Tj ET", "", false, false));
        T.check("inline image bytes are not text", text(r).contains("Before the image") && text(r).contains("After image") && !text(r).contains("not text"));
        // CMap conflicts > 10 % -> hex strings are not decoded and counted
        StringBuilder bad = new StringBuilder("begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange 20 beginbfchar ");
        for (int i = 1; i <= 10; i++) bad.append(String.format("<%04X> <%04X> ", i, 0x41 + i));
        for (int i = 1; i <= 10; i++) bad.append(String.format("<%04X> <%04X> ", i, 0x61 + i));
        bad.append("endbfchar endcmap");
        r = x(pdf(pageObjs(), stream(5, "", a(bad.toString()), null), stream(4, "", a("BT <0001> Tj (Plain text that is long enough) Tj ET"), null)));
        T.check("conflicting CMap: hex not decoded, counted unsupported, literal text kept",
                r.unsupported == 1 && text(r).equals("Plain text that is long enough"));
        // caps
        StringBuilder big = new StringBuilder("BT ");
        for (int i = 0; i < 30000; i++) big.append("(ABCDEFGHIJ) Tj ");
        r = x(pdf(pageObjs(), stream(4, "/Filter /FlateDecode", flate(a(big.append("ET").toString())), null)));
        T.check("text capped at 200,000 chars, status OK", r.status == DocStatus.OK && r.text.length() == PdfText.MAX_CHARS);
        // 13 truncated
        byte[] full = pdf(pageObjs(), stream(4, "/Filter /FlateDecode", flate(a(STMT + " BT (Some more text so the stream has size) Tj ET")), null), stream(6, "", a("BT (Second page text here) Tj ET"), null));
        boolean ok13 = true; int okCount = 0;
        for (int cut = 0; cut <= full.length; cut += 3) {
            try {
                PdfText.Result t = x(java.util.Arrays.copyOf(full, cut));
                if (t.status != DocStatus.OK && t.status != DocStatus.CORRUPT && t.status != DocStatus.UNSUPPORTED && t.status != DocStatus.NO_TEXT_LAYER) ok13 = false;
                if (t.status == DocStatus.OK) okCount++;
            } catch (RuntimeException e) { ok13 = false; }
        }
        T.check("13 every truncation point: no exception, status OK/CORRUPT/UNSUPPORTED", ok13);
        T.check("13 70 % cut keeps a sensible status", x(java.util.Arrays.copyOf(full, full.length * 7 / 10)).status != null && okCount > 0);
        // determinism
        T.eq("deterministic", text(x(full)), text(x(full)));
    }

    static void hostile() {
        long t0 = System.nanoTime();
        // circular references: object refers to itself via /Length, /Parent, /Kids, /Contents
        byte[] circ = FxImg.cat(a("%PDF-1.4\n"), obj(1, "<< /Type /Catalog /Pages 1 0 R >>"), obj(2, "<< /Type /Pages /Kids [2 0 R] /Parent 2 0 R /Count 1 >>"),
                stream(3, "/Contents 3 0 R", a("BT (Looping object stream text) Tj ET"), "3 0 R"), a("trailer << /Root 1 0 R >>"));
        PdfText.Result r = x(circ);
        T.check("circular references terminate and text is found", r.status == DocStatus.OK && text(r).contains("Looping"));
        // absurd /Length: larger than the file, negative-looking, huge digits
        for (String len : new String[]{"999999999", "2147483647", "99999999999999999999", "-5", "0"}) {
            r = x(pdf(pageObjs(), stream(4, "", a(STMT), len)));
            T.check("absurd /Length " + len + " -> endstream used", text(r).contains("Account Statement"));
        }
        // /Length that points before the real end
        r = x(pdf(pageObjs(), stream(4, "", a("BT (Complete sentence in the stream) Tj ET"), "5")));
        T.check("/Length too short -> endstream used", text(r).contains("Complete sentence in the stream"));
        // more than 10,000 streams: at most the first 10,000 are looked at
        ByteArrayOutputStream many = new ByteArrayOutputStream();
        for (int i = 0; i < 12000; i++) { byte[] s = stream(10 + i, "", a("BT (S" + i + ") Tj ET"), null); many.write(s, 0, s.length); }
        r = x(pdf(pageObjs(), many.toByteArray()));
        T.check("12,000 streams: no exception, text from the first 10,000 only", r.status == DocStatus.OK && text(r).contains("S9990") && !text(r).contains("S11999"));
        // a dictionary that never opens: 100,000 '>>' before stream
        StringBuilder gt = new StringBuilder("%PDF-1.4\n1 0 obj\n");
        for (int i = 0; i < 50000; i++) gt.append(">>");
        gt.append("\nstream\nBT (x) Tj ET\nendstream\n");
        r = x(a(gt.toString()));
        T.check("unbalanced dictionary: bounded, status defined", r.status != null);
        StringBuilder lt = new StringBuilder("%PDF-1.4\n1 0 obj\n");
        for (int i = 0; i < 50000; i++) lt.append("<<");
        lt.append(" >>\nstream\nBT (x) Tj ET\nendstream\n");
        T.check("100,000 nested '<<': bounded, no exception", x(a(lt.toString())).status != null);
        // total decoded > 64 MiB -> LIMIT
        byte[] z8 = flate(new byte[8 << 20]);
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) { byte[] s = stream(10 + i, "/Filter /FlateDecode", z8, null); bomb.write(s, 0, s.length); }
        long tb = System.nanoTime();
        r = x(pdf(pageObjs(), stream(4, "", a(STMT), null), bomb.toByteArray()));
        T.eq("10 x 8 MiB inflated (80 MiB) -> LIMIT", DocStatus.LIMIT, r.status);
        T.check("LIMIT returns no text", r.text.length() == 0);
        T.check("LIMIT in < 5 s", (System.nanoTime() - tb) / 1000000L < 5000);
        // ASCII85 'z' bomb: 3 MiB of z = 12 MiB output
        byte[] zz = new byte[3 << 20]; java.util.Arrays.fill(zz, (byte) 'z');
        r = x(pdf(pageObjs(), stream(4, "/Filter /ASCII85Decode", zz, null), stream(6, "", a("BT (Text after the z bomb stream) Tj ET"), null)));
        T.check("ASCII85 z bomb skipped, counted, later text kept", r.unsupported == 1 && text(r).contains("after the z bomb"));
        // zlib stream that is not zlib; truncated flate; flate with garbage tail
        r = x(pdf(pageObjs(), stream(4, "/Filter /FlateDecode", a("this is not deflate data at all"), null), stream(6, "", a("BT (Fine text on another stream) Tj ET"), null)));
        T.check("invalid flate counted, no exception", r.unsupported == 1 && text(r).contains("Fine text"));
        byte[] fl = flate(a("BT (Truncated flate data still readable maybe) Tj ET"));
        r = x(pdf(pageObjs(), stream(4, "/Filter /FlateDecode", java.util.Arrays.copyOf(fl, fl.length / 2), null)));
        T.check("truncated flate: no exception", r.status != null);
        // unbalanced literal string and a missing endstream
        r = x(pdf(pageObjs(), stream(4, "", a("BT (unbalanced ((( never closed Tj ET " + repeat("x", 100000)), null)));
        T.check("unbalanced '(' consumes to the end, bounded", r.status != null);
        r = x(FxImg.cat(a("%PDF-1.4\n3 0 obj\n<< /Length 9999 >>\nstream\nBT (No end marker but long enough text) Tj ET")));
        T.check("missing endstream: data to EOF", text(r).contains("No end marker"));
        // CMap abuse: 5,000 ranges of 65,536 codes each, huge counts
        StringBuilder cm = new StringBuilder("begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange 5000 beginbfrange ");
        for (int i = 0; i < 5000; i++) cm.append("<0000> <FFFF> <0041> ");
        cm.append("endbfrange endcmap");
        long tc = System.nanoTime();
        r = x(pdf(pageObjs(), stream(5, "", a(cm.toString()), null), stream(4, "", a("BT <0001> Tj (Some literal text long enough) Tj ET"), null)));
        T.check("CMap range abuse: bounded, < 5 s, no exception", r.status != null && (System.nanoTime() - tc) / 1000000L < 5000);
        // garbage CMap tokens
        r = x(pdf(pageObjs(), stream(5, "", a("begincmap beginbfchar <zz> <12 [[[ <<<< endbfchar beginbfrange <00> endbfrange begincodespacerange"), null), stream(4, "", a(STMT), null)));
        T.check("garbage CMap: no exception, text kept", text(r).contains("Account Statement"));
        // random corruption of a valid file: never an exception
        byte[] base = pdf(pageObjs(), stream(5, "/Filter /FlateDecode", flate(a("begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar <0041> <0042> endbfchar endcmap")), null),
                stream(4, "/Filter /FlateDecode", flate(a("BT /F1 12 Tf <0041> Tj [(abc) -300 (def)] TJ 1 0 0 1 0 0 Tm (ghi) ' ET")), null));
        Random rnd = new Random(7);
        boolean noThrow = true;
        for (int it = 0; it < 400; it++) {
            byte[] c = base.clone();
            int flips = 1 + rnd.nextInt(12);
            for (int k = 0; k < flips; k++) c[rnd.nextInt(c.length)] = (byte) rnd.nextInt(256);
            try { if (x(c).status == null) noThrow = false; } catch (RuntimeException e) { noThrow = false; }
        }
        T.check("400 random corruptions: never an exception", noThrow);
        byte[] rb = new byte[200000]; rnd.nextBytes(rb);
        T.check("random bytes: no exception", x(rb).status != null);
        T.check("hostile cases together under 30 s", (System.nanoTime() - t0) / 1000000L < 30000);
    }

    static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    // ---- the DOC pass ----
    static DocInfo doc(Fx fx, String path) { FeatureRecord fr = fx.store.features().get(fx.f(path).fileId); return fr == null ? null : fr.doc; }

    static void stageTests() throws Exception {
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentConfig cfg = new ContentConfig(); cfg.docMaxBytes = 3000;
            ContentStage cs = new ContentStage(new ContentProviders(null, null), cfg);
            fx.extraStages.add(cs);
            String secret = "ZXQVSecretMarkerWord";
            byte[] good = page("BT (" + secret + " alpha beta gamma delta) Tj ET", "", false, false);
            fx.tree.file("Documents/good.pdf", good);
            fx.tree.file("Documents/enc.pdf", FxImg.cat(a("%PDF-1.6\n"), pageObjs(), stream(4, "", a(STMT), null), a("trailer\n<< /Encrypt 9 0 R >>\n%%EOF")));
            fx.tree.file("Documents/scan.pdf", pdf(pageObjs(), stream(5, "/Subtype /Image /Width 1 /Height 1", new byte[]{1, 2}, null), stream(4, "", a("q /Im0 Do Q"), null)));
            fx.tree.file("Documents/trunc.pdf", java.util.Arrays.copyOf(good, good.length * 6 / 10));
            fx.tree.file("Documents/big.pdf", page("BT (" + repeat("x", 4000) + ") Tj ET", "", false, false));
            fx.tree.file("Documents/junk.pdf", FxImg.cat(a("%PDF-1.4\n"), new byte[200]));
            fx.tree.file("Documents/empty.pdf", new byte[0]);
            fx.tree.file("Documents/notes.txt", a("plain text, not handled by S3a"));
            fx.tree.file("Documents/denied.pdf", good);
            fx.faulty.denyOpen("Documents/denied.pdf");
            fx.scan();
            DocInfo g = doc(fx, "Documents/good.pdf");
            T.check("good: OK, format pdf, chars/words/pages", g != null && g.status == DocStatus.OK && "pdf".equals(g.format) && g.chars == (secret + " alpha beta gamma delta").length() && g.words == 5 && g.pages == 1);
            T.eq("good: unsupportedStreams 0", 0, g.unsupportedStreams);
            DocInfo e = doc(fx, "Documents/enc.pdf");
            T.check("encrypted: ENCRYPTED, counts unknown", e != null && e.status == DocStatus.ENCRYPTED && e.chars == -1 && e.words == -1 && e.pages == -1);
            DocInfo sc = doc(fx, "Documents/scan.pdf");
            T.check("scanned: NO_TEXT_LAYER, chars unknown (not 0)", sc != null && sc.status == DocStatus.NO_TEXT_LAYER && sc.chars == -1 && sc.words == -1);
            DocInfo tr = doc(fx, "Documents/trunc.pdf");
            T.check("truncated: a status is stored, no exception", tr != null && tr.status != DocStatus.NOT_RUN);
            DocInfo bg = doc(fx, "Documents/big.pdf");
            T.check("over docMaxBytes: TOO_LARGE, unknown counts", bg != null && bg.status == DocStatus.TOO_LARGE && bg.chars == -1 && bg.words == -1);
            DocInfo jk = doc(fx, "Documents/junk.pdf");
            T.check("junk with %PDF header and no streams: CORRUPT", jk != null && jk.status == DocStatus.CORRUPT);
            T.check("empty file not selected", fx.store.features().get(fx.f("Documents/empty.pdf").fileId) == null || doc(fx, "Documents/empty.pdf") == null);
            T.check("txt is handled by the S3b dispatch (format txt, OK)", doc(fx, "Documents/notes.txt") != null && doc(fx, "Documents/notes.txt").status == DocStatus.OK);
            DocInfo dn = doc(fx, "Documents/denied.pdf");
            T.check("open failure -> CORRUPT (unknown), stamped", dn != null && dn.status == DocStatus.CORRUPT && dn.chars == -1);
            FeatureRecord fg = fx.store.features().get(fx.f("Documents/good.pdf").fileId);
            T.eq("version stamp", Extractors.version(Extractors.DOC_TEXT), fg.versions[Extractors.DOC_TEXT]);
            Long made = fx.session(1).counters.get(ContentStage.COUNT_DOC);
            T.eq("8 documents handled (7 pdf + notes.txt)", 8L, made == null ? 0 : made);
            T.check("no incomplete event", !Fx.hasEvent(fx.session(1), ContentStage.INCOMPLETE));
            // privacy: nothing of the text, title or author in any serialised form of the record or its problems
            java.io.StringWriter sw = new java.io.StringWriter();
            ContentCodec.write(new JsonWriter(sw), g);
            String js = sw.toString();
            T.check("no raw text in the stored DocInfo JSON", !js.contains(secret) && !js.contains("alpha") && !js.contains("\"text\"") && !js.contains("\"title\"") && !js.contains("\"author\""));
            StringBuilder all = new StringBuilder(js);
            for (String p : fg.problems) all.append(p);
            for (Object en : fg.extra.entrySet()) all.append(en);
            T.check("no raw text in problems/extra", !all.toString().contains(secret));
            // carry-over: the second FULL run reads nothing
            fx.scan();
            Long again = fx.session(2).counters.get(ContentStage.COUNT_DOC);
            T.eq("second FULL run reads 0 documents", 0L, again == null ? 0 : again);
            T.eq("record unchanged by the second run", DocStatus.OK, doc(fx, "Documents/good.pdf").status);
            // version bump: all again (7 stamped files: denied included)
            Extractors.overrideVersionForTest(Extractors.DOC_TEXT, 9);
            try { fx.scan(); } finally { Extractors.resetVersionsForTest(); }
            T.eq("version bump: 8 again", 8L, fx.session(3).counters.get(ContentStage.COUNT_DOC));
            // a changed file is re-read
            fx.tree.file("Documents/good.pdf", page("BT (A different document with new content) Tj ET", "", false, false));
            fx.scan();
            T.check("changed file re-read", doc(fx, "Documents/good.pdf").words == 6 || doc(fx, "Documents/good.pdf").chars == "A different document with new content".length());
        }
        // pause/resume: commit every 2, pause after the first commit, resume processes only the rest; result equals one run
        java.util.Map<String, String> whole;
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentConfig cfg = new ContentConfig(); cfg.commitEvery = 2;
            fx.extraStages.add(new ContentStage(new ContentProviders(null, null), cfg));
            for (int i = 0; i < 9; i++) fx.tree.file("Documents/d" + i + ".pdf", page("BT (Document number " + i + " with text) Tj ET", "", i % 2 == 0, true));
            fx.scan();
            whole = docs(fx);
            T.eq("9 documents in the uninterrupted run", 9L, fx.session(1).counters.get(ContentStage.COUNT_DOC));
        }
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentConfig cfg = new ContentConfig(); cfg.commitEvery = 2;
            ContentStage cs = new ContentStage(new ContentProviders(null, null), cfg);
            fx.extraStages.add(cs);
            for (int i = 0; i < 9; i++) fx.tree.file("Documents/d" + i + ".pdf", page("BT (Document number " + i + " with text) Tj ET", "", i % 2 == 0, true));
            fx.control = new ContentStageResumeTest.TestControlAdapter(new com.storageplanner.v2.device.ScanControl() {
                boolean pause;
                public boolean cancelRequested() { return false; }
                public boolean pauseRequested() { return pause; }
                public void progress(String stage, long done, long total) { if (ContentStage.ID.equals(stage) && done >= 2) pause = true; }
            });
            com.storageplanner.v2.pipeline.PipelineResult r1 = fx.scan();
            T.eq("run 1 paused", com.storageplanner.v2.stage.StageOutcome.PAUSED, r1.outcome);
            T.eq("run 1 handled 2", 2L, fx.session(1).counters.get(ContentStage.COUNT_DOC));
            T.eq("pending event doc=7", "ocr=0 visual=0 doc=7 activity=0", ContentStageResumeTest.eventDetail(fx.session(1)));
            fx.control = null;
            fx.scan(ScanKind.FULL, true);
            T.eq("run 2 handled the other 7", 9L, fx.session(1).counters.get(ContentStage.COUNT_DOC));
            T.check("chain equals the uninterrupted run", whole.equals(docs(fx)));
        }
    }

    static java.util.Map<String, String> docs(Fx fx) {
        java.util.Map<String, String> m = new java.util.TreeMap<String, String>();
        for (FileRecord f : fx.files()) {
            FeatureRecord fr = fx.store.features().get(f.fileId);
            if (fr == null || fr.doc == null) continue;
            java.io.StringWriter sw = new java.io.StringWriter();
            ContentCodec.write(new JsonWriter(sw), fr.doc);
            m.put(f.path, sw.toString() + "/v" + fr.versions[Extractors.DOC_TEXT]);
        }
        return m;
    }

    // ---- 14: dataset ----
    static void datasetTest() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("p6.dataset", "/home/user/sp-dataset"));
        java.nio.file.Path f = root.resolve("Documents/Bank/Rohan_Statement_2019_07.pdf");
        if (!java.nio.file.Files.isRegularFile(f)) { System.out.println("  (dataset not present: PDF check skipped)"); return; }
        PdfText.Result r = x(java.nio.file.Files.readAllBytes(f));
        T.check("14 dataset statement OK and contains SPECIMEN BANK", r.status == DocStatus.OK && text(r).contains("SPECIMEN BANK"));
    }
}
