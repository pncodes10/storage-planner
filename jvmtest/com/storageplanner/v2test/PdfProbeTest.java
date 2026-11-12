package com.storageplanner.v2test;

import com.storageplanner.v2.features.Dates;
import com.storageplanner.v2.model.*;

import java.nio.charset.StandardCharsets;

/** PdfProbe (PHASE_2 6) on hand-written text PDFs (FxPdf). */
public final class PdfProbeTest {
    static byte[] a(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    static String plain(int pages, String info, String trailerExtra) {
        StringBuilder b = new StringBuilder("%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        b.append("2 0 obj << /Type /Pages /Kids [] /Count ").append(pages).append(" >> endobj\n");
        for (int i = 0; i < pages; i++) b.append(10 + i).append(" 0 obj << /Type /Page /Parent 2 0 R >> endobj\n");
        b.append("3 0 obj << ").append(info).append(" >> endobj\n");
        b.append("xref\n0 1\ntrailer << /Root 1 0 R /Info 3 0 R ").append(trailerExtra).append(" >>\nstartxref\n0\n%%EOF\n");
        return b.toString();
    }
    static PdfInfo pdf(Fx fx, String p) { return fx.store.features().get(fx.f(p).fileId).pdf; }

    public static int run(String[] args) throws Exception {
        T.section("PdfProbe");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2;
            TempTree t = fx.tree;
            t.file("p.pdf", a(plain(3, "/Title (Annual \\(draft\\) \\101\\nX) /Author <FEFF00C40062> /CreationDate (D:20230405060708+05'30')", "")));
            t.file("enc.pdf", a(plain(2, "/Title (Secret)", "/Encrypt 5 0 R")));
            t.file("xrefenc.pdf", a("%PDF-1.7\n9 0 obj << /Type /XRef /Size 3 /Encrypt 4 0 R >> stream\nendstream endobj\nstartxref\n0\n%%EOF\n"));
            t.file("lin.pdf", a("%PDF-1.5\n1 0 obj << /Linearized 1 /L 1000 /N 12 /T 900 >> endobj\ntrailer << /Root 2 0 R >>\n%%EOF\n"));
            String inc = plain(4, "/Title (v1)", "") + "2 0 obj << /Type /Pages /Kids [] /Count 5 >> endobj\ntrailer << /Prev 0 >>\n%%EOF\n";
            t.file("inc.pdf", a(inc));
            t.file("objstm.pdf", a("%PDF-1.5\n5 0 obj << /Type /ObjStm /N 3 /First 10 >> stream\nxx\nendstream endobj\n7 0 obj << /Type /XRef >> endobj\n%%EOF\n"));
            t.file("noeof.pdf", a("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n"));
            t.file("cut.pdf", a("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n"));
            byte[] big = new byte[12 << 20];
            byte[] h = a("%PDF-1.4\n"), tl = a("trailer << /Root 1 0 R >>\n%%EOF\n");
            System.arraycopy(h, 0, big, 0, h.length); System.arraycopy(tl, 0, big, big.length - tl.length, tl.length);
            t.file("big.pdf", big);
            fx.scan();
            PdfInfo p = pdf(fx, "p.pdf");
            T.eq("pages 3", 3, p.pages); T.eq("PAGES_ROOT", "PAGES_ROOT", p.pagesSource); T.eq("encrypted FALSE", Tri.FALSE, p.encrypted);
            T.eq("title escapes + octal", "Annual (draft) A\nX", p.title); T.eq("UTF-16BE author", "Äb", p.author);
            T.eq("version", "1.7", p.version);
            T.eq("CreationDate with offset → UTC", Dates.utc(2023, 4, 5, 6, 7, 8, 0) - 330 * 60000L, p.createdAt);
            T.eq("createdAt CONTAINER", TimeSource.CONTAINER, fx.f("p.pdf").createdAtSource);
            T.eq("integrity OK", Integrity.OK, fx.f("p.pdf").integrity);
            PdfInfo e = pdf(fx, "enc.pdf");
            T.eq("/Encrypt in trailer → TRUE", Tri.TRUE, e.encrypted); T.eq("encrypted: title null", null, e.title);
            T.eq("ENCRYPTED integrity", Integrity.ENCRYPTED, fx.f("enc.pdf").integrity);
            T.eq("encrypted: status COMPLETE (tiers 1-2)", ExtractionStatus.COMPLETE, fx.f("enc.pdf").extractionStatus);
            T.eq("/Encrypt in xref stream → TRUE", Tri.TRUE, pdf(fx, "xrefenc.pdf").encrypted);
            T.eq("linearized /N 12", 12, pdf(fx, "lin.pdf").pages); T.eq("LINEARIZED", "LINEARIZED", pdf(fx, "lin.pdf").pagesSource);
            T.eq("incremental: latest root 5", 5, pdf(fx, "inc.pdf").pages); T.eq("incremental updates 1", 1, pdf(fx, "inc.pdf").incrementalUpdates);
            T.eq("object streams → pages unknown", -1, pdf(fx, "objstm.pdf").pages);
            T.check("OBJECT_STREAMS problem", fx.store.features().get(fx.f("objstm.pdf").fileId).problems.contains("pdf:OBJECT_STREAMS"));
            T.eq("no %%EOF → TRUNCATED", Integrity.TRUNCATED, fx.f("noeof.pdf").integrity);
            T.eq("cut head-only → encrypted UNKNOWN", Tri.UNKNOWN, pdf(fx, "cut.pdf").encrypted);
            long read = fx.faulty.bytesRead.get("big.pdf");
            T.check("12 MiB: only head/tail read by T1 (" + read + ")", read <= (8L << 20) + 2048 + (128 << 10));   // regions + the shared 64 KiB head and tail reads
        }
        return T.finish("PdfProbeTest");
    }
}
