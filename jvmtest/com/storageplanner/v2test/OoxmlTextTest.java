package com.storageplanner.v2test;

import com.storageplanner.v2.content.*;
import com.storageplanner.v2.content.doc.*;
import com.storageplanner.v2.features.Extractors;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.ContentCodec;
import com.storageplanner.v2.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Phase 6 S3b (PHASE_6 7.3): OOXML, ODF, OLE2, plain text and XmlText on zips built in the test, hostile inputs, and the DOC pass dispatch. */
public final class OoxmlTextTest {
    static byte[] u(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** Zip builder; entries deflated unless addStored. */
    static final class Z {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final ZipOutputStream z = new ZipOutputStream(bo);
        Z add(String name, byte[] data) throws IOException { z.putNextEntry(new ZipEntry(name)); z.write(data); z.closeEntry(); return this; }
        Z stored(String name, byte[] data) throws IOException {
            ZipEntry e = new ZipEntry(name); e.setMethod(ZipEntry.STORED); e.setSize(data.length); e.setCompressedSize(data.length);
            CRC32 c = new CRC32(); c.update(data); e.setCrc(c.getValue());
            z.putNextEntry(e); z.write(data); z.closeEntry(); return this;
        }
        byte[] done() throws IOException { z.close(); return bo.toByteArray(); }
    }

    static final String CT = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>";
    static String body(String s) { return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"w\"><w:body>" + s + "</w:body></w:document>"; }
    static String core(String title, String creator, String created, String modified) {
        return "<?xml version=\"1.0\"?><cp:coreProperties xmlns:cp=\"c\" xmlns:dc=\"d\" xmlns:dcterms=\"t\"><dc:title>" + title + "</dc:title><dc:creator>" + creator
                + "</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">" + created + "</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + modified + "</dcterms:modified></cp:coreProperties>";
    }

    static OoxmlText.Result ox(byte[] b, String fmt) { return OoxmlText.extract(new ActivityProfileTest.MemFile(b), b.length, fmt); }
    static OoxmlText.Result od(byte[] b, String fmt) { return OdfText.extract(new ActivityProfileTest.MemFile(b), b.length, fmt); }
    static String t(OoxmlText.Result r) { return r.text.toString(); }

    static byte[] docx(String bodyXml) throws IOException {
        return new Z().add("[Content_Types].xml", u(CT)).add("word/document.xml", u(body(bodyXml))).done();
    }

    public static int run(String[] args) throws Exception {
        T.section("OoxmlText");
        xmlText();
        office();
        odf();
        ole2AndPlain();
        hostile();
        stageTests();
        datasetTest();
        return T.finish("OoxmlTextTest");
    }

    static final Set<String> TXT = new HashSet<String>(Arrays.asList("w:t", "t")), BRK = new HashSet<String>(Arrays.asList("w:p"));

    static void xmlText() {
        XmlText.Out o = XmlText.text(u("<?xml version=\"1.0\"?><!-- c <w:t>no</w:t> --><w:p><w:t>a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos; &#65;&#x42; &bogus; &#xZZ;</w:t></w:p>"), TXT, BRK, 1000);
        T.eq("entities decoded, unknown kept literally", "a & b <c> \"d\" 'e' AB &bogus; &#xZZ;\n", o.text.toString());
        T.check("not malformed", !o.malformed);
        o = XmlText.text(u("<w:p><w:t><![CDATA[x < y & z]]></w:t><w:t xml:space=\"preserve\" a=\"1>2\"> tail</w:t></w:p><w:p/>"), TXT, BRK, 1000);
        T.eq("CDATA inside a text element, '>' inside an attribute, self-closing break", "x < y & z tail\n\n", o.text.toString());
        o = XmlText.text(u("<w:p><w:r>outside <w:t>in</w:t> outside</w:r></w:p>"), TXT, BRK, 1000);
        T.eq("character data outside text elements ignored", "in\n", o.text.toString());
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 300; i++) deep.append("<w:x>");
        deep.append("<w:t>deep</w:t>");
        o = XmlText.text(u("<w:p><w:t>kept text</w:t></w:p>" + deep), TXT, BRK, 1000);
        T.check("depth > 256: stops, keeps what was collected", o.malformed && o.text.toString().startsWith("kept text"));
        o = XmlText.text(u("<w:p><w:t>one</w:t></w:q><w:t>two</w:t>"), TXT, BRK, 1000);
        T.check("mismatched end tag: malformed, collected text kept", o.malformed && o.text.toString().startsWith("one"));
        o = XmlText.text(u("<w:p><w:t>abc</w:t></w:p><w:p><w:t unterminated"), TXT, BRK, 1000);
        T.check("unterminated tag: malformed", o.malformed && o.text.toString().startsWith("abc"));
        o = XmlText.text(u("<!DOCTYPE x [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">]><w:p><w:t>&b;&a;</w:t></w:p>"), TXT, BRK, 1000);
        T.check("DOCTYPE entities are not expanded", o.text.toString().startsWith("&b;&a;"));
        StringBuilder many = new StringBuilder("<w:p>");
        for (int i = 0; i < 100000; i++) many.append("<w:t>abcdefghij</w:t>");
        o = XmlText.text(u(many.toString()), TXT, BRK, 5000);
        T.check("output capped at maxChars", o.text.length() == 5000 && o.full);
        T.eq("BOM skipped, empty input", "", XmlText.text(new byte[0], TXT, BRK, 10).text.toString());
        T.eq("firstText skips nested markup", "Hello World", XmlText.firstText(u("<a><dc:title>Hello <b>World</b></dc:title></a>"), "dc:title"));
        T.check("firstText absent / self-closing", XmlText.firstText(u("<a/>"), "dc:title") == null && XmlText.firstText(u("<dc:title/>"), "dc:title") == null);
        T.eq("attribute", "7", XmlText.attribute(u("<m><meta:document-statistic meta:table-count=\"1\" meta:page-count=\"7\"/></m>"), "meta:document-statistic", "meta:page-count"));
    }

    static void office() throws Exception {
        // docx: two w:p -> two lines; title/creator/dates from core.xml; pages from app.xml
        byte[] d = new Z().add("[Content_Types].xml", u(CT))
                .add("word/document.xml", u(body("<w:p><w:r><w:t>First paragraph</w:t></w:r></w:p><w:p><w:r><w:t>Second </w:t></w:r><w:r><w:t>paragraph &amp; more</w:t></w:r></w:p>")))
                .add("word/footnotes.xml", u(body("<w:p><w:r><w:t>A footnote</w:t></w:r></w:p>")))
                .add("docProps/core.xml", u(core("My Title", "Jane Doe", "2023-05-01T10:15:30Z", "2023-06-02T08:00:00+05:30")))
                .add("docProps/app.xml", u("<Properties><Pages>3</Pages><Words>5</Words></Properties>")).done();
        OoxmlText.Result r = ox(d, "docx");
        T.eq("docx OK", DocStatus.OK, r.status);
        T.eq("docx two lines + footnote", "First paragraph\nSecond paragraph & more\nA footnote\n", t(r));
        T.check("docx title and creator", "My Title".equals(r.title) && "Jane Doe".equals(r.author));
        T.eq("docx created (UTC ms)", 1682936130000L, r.createdAt);
        T.eq("docx modified (+05:30 offset applied)", 1685673000000L, r.modifiedAt);
        T.eq("docx pages from app.xml", 3, r.pages);
        // xlsx: sharedStrings only
        byte[] x = new Z().add("[Content_Types].xml", u(CT)).add("xl/workbook.xml", u("<workbook/>"))
                .add("xl/sharedStrings.xml", u("<sst><si><t>Rent &amp; Utilities</t></si><si><r><t>Part </t></r><r><t>two</t></r></si></sst>"))
                .add("xl/worksheets/sheet1.xml", u("<worksheet><sheetData><row><c><v>12345678901234567890</v></c></row></sheetData></worksheet>")).done();
        r = ox(x, "xlsx");
        T.check("xlsx sharedStrings with &amp; -> &, sheets not read", r.status == DocStatus.OK && t(r).equals("Rent & Utilities\nPart two\n"));
        T.eq("xlsx pages unknown", -1, r.pages);
        byte[] xn = new Z().add("[Content_Types].xml", u(CT)).add("xl/workbook.xml", u("<workbook/>")).add("xl/worksheets/sheet1.xml", u("<worksheet/>")).done();
        r = ox(xn, "xlsx");
        T.check("xlsx without sharedStrings: OK, 0 chars", r.status == DocStatus.OK && r.text.length() == 0);
        // pptx: slides 10 and 2 -> slide 2 text first
        byte[] p = new Z().add("[Content_Types].xml", u(CT))
                .add("ppt/slides/slide10.xml", u("<p:sld><a:p><a:r><a:t>Slide ten text</a:t></a:r></a:p></p:sld>"))
                .add("ppt/slides/slide2.xml", u("<p:sld><a:p><a:r><a:t>Slide two text</a:t></a:r></a:p></p:sld>"))
                .add("ppt/slides/slide1.xml.rels", u("<a:t>not a slide</a:t>"))
                .add("ppt/slides/slideX.xml", u("<a:p><a:t>not numbered</a:t></a:p>"))
                .add("ppt/slides/slide201.xml", u("<a:p><a:t>beyond 200</a:t></a:p>")).done();
        r = ox(p, "pptx");
        T.eq("pptx: slide 2 before slide 10, others ignored", "Slide two text\nSlide ten text\n", t(r));
        T.eq("pptx pages = slide count when app.xml absent", 2, r.pages);
        T.eq("empty pptx (no slides) -> UNSUPPORTED", DocStatus.UNSUPPORTED, ox(new Z().add("[Content_Types].xml", u(CT)).done(), "pptx").status);
        T.eq("docx without word/document.xml -> UNSUPPORTED (unknown)", DocStatus.UNSUPPORTED, ox(new Z().add("[Content_Types].xml", u(CT)).add("word/styles.xml", u("<s/>")).done(), "docx").status);
        // an entry with the encrypted flag
        byte[] enc = setFlag(docx("<w:p><w:r><w:t>Secret text that is encrypted</w:t></w:r></w:p>"));
        r = ox(enc, "docx");
        T.check("encrypted entry -> ENCRYPTED, no text", r.status == DocStatus.ENCRYPTED && r.text.length() == 0);
        // small malformed content
        r = ox(docx("<w:p><w:r><w:t>short</w:t></w:r></w:q>"), "docx");
        T.eq("malformed with < 20 chars -> CORRUPT", DocStatus.CORRUPT, r.status);
        r = ox(docx("<w:p><w:r><w:t>this paragraph has more than twenty characters</w:t></w:r></w:q>"), "docx");
        T.eq("malformed with >= 20 chars -> OK", DocStatus.OK, r.status);
        T.eq("garbage is CORRUPT", DocStatus.CORRUPT, ox(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23}, "docx").status);
        T.eq("empty is CORRUPT", DocStatus.CORRUPT, ox(new byte[0], "docx").status);
        T.eq("unknown format -> UNSUPPORTED", DocStatus.UNSUPPORTED, ox(d, "docm2").status);
        // 200 slides, text cap 200,000 chars
        Z z = new Z().add("[Content_Types].xml", u(CT));
        StringBuilder slide = new StringBuilder("<p:sld>");
        for (int i = 0; i < 400; i++) slide.append("<a:p><a:t>0123456789012345678901234567890123456789</a:t></a:p>");
        for (int n = 1; n <= 200; n++) z.add("ppt/slides/slide" + n + ".xml", u(slide + "</p:sld>"));
        r = ox(z.done(), "pptx");
        T.check("200 slides of text: capped at 200,000 chars, OK", r.status == DocStatus.OK && r.text.length() == OoxmlText.MAX_CHARS);
        T.eq("deterministic", t(ox(d, "docx")), t(ox(d, "docx")));
    }

    /** Sets bit 0 (encrypted) of the general purpose flags in every central directory header. */
    static byte[] setFlag(byte[] z) {
        byte[] c = z.clone();
        for (int i = 0; i + 10 < c.length; i++) if (c[i] == 'P' && c[i + 1] == 'K' && c[i + 2] == 1 && c[i + 3] == 2) c[i + 8] |= 1;
        return c;
    }

    static void odf() throws Exception {
        String content = "<office:document-content><office:body><office:text><text:h>Heading line</text:h><text:p>Hello <text:span>bold</text:span> world &amp; co</text:p><text:p>Second</text:p></office:text></office:body></office:document-content>";
        String meta = "<office:document-meta><office:meta><dc:title>ODF Title</dc:title><meta:initial-creator>Pat</meta:initial-creator><meta:creation-date>2022-03-04T05:06:07</meta:creation-date>"
                + "<meta:document-statistic meta:table-count=\"0\" meta:page-count=\"4\"/></office:meta></office:document-meta>";
        byte[] b = new Z().stored("mimetype", u("application/vnd.oasis.opendocument.text")).add("content.xml", u(content)).add("meta.xml", u(meta))
                .add("META-INF/manifest.xml", u("<manifest:manifest><manifest:file-entry manifest:full-path=\"content.xml\"/></manifest:manifest>")).done();
        OoxmlText.Result r = od(b, "odt");
        T.eq("odt content.xml text", "Heading line\nHello bold world & co\nSecond\n", t(r));
        T.check("odt status, title, author, pages", r.status == DocStatus.OK && "ODF Title".equals(r.title) && "Pat".equals(r.author) && r.pages == 4);
        T.eq("odt creation date", 1646370367000L, r.createdAt);
        byte[] e = new Z().stored("mimetype", u("application/vnd.oasis.opendocument.text")).add("content.xml", u(content))
                .add("META-INF/manifest.xml", u("<manifest:manifest><manifest:file-entry manifest:full-path=\"content.xml\"><manifest:encryption-data manifest:checksum-type=\"x\"/></manifest:file-entry></manifest:manifest>")).done();
        r = od(e, "odt");
        T.check("password-protected odt (manifest encryption-data) -> ENCRYPTED, no text", r.status == DocStatus.ENCRYPTED && r.text.length() == 0);
        T.eq("odt without content.xml -> UNSUPPORTED", DocStatus.UNSUPPORTED, od(new Z().stored("mimetype", u("application/vnd.oasis.opendocument.text")).done(), "odt").status);
        T.eq("encrypted-flag entry -> ENCRYPTED", DocStatus.ENCRYPTED, od(setFlag(b), "odt").status);
        T.eq("garbage -> CORRUPT", DocStatus.CORRUPT, od(new byte[100], "ods").status);
    }

    static void ole2AndPlain() throws Exception {
        byte[] ole = new byte[8192];
        int[] magic = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};
        for (int i = 0; i < 8; i++) ole[i] = (byte) magic[i];
        byte[] name = "EncryptedPackage".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(name, 0, ole, 4096, name.length);
        OoxmlText.Result r = Ole2.check(ole, ole.length);
        T.check("OLE2 with EncryptedPackage directory entry -> ENCRYPTED (format ole2)", r.status == DocStatus.ENCRYPTED && "ole2".equals(r.format) && r.text.length() == 0);
        byte[] ole2 = Arrays.copyOf(ole, ole.length); Arrays.fill(ole2, 4096, 4096 + name.length, (byte) 0);
        byte[] info = "EncryptionInfo".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(info, 0, ole2, 200, info.length);
        T.eq("OLE2 with EncryptionInfo -> ENCRYPTED", DocStatus.ENCRYPTED, Ole2.check(ole2, ole2.length).status);
        byte[] plain = Arrays.copyOf(ole, 8192); Arrays.fill(plain, 4096, 4096 + name.length, (byte) 0);
        r = Ole2.check(plain, plain.length);
        T.check("legacy OLE2 -> UNSUPPORTED (unknown), format ole2", r.status == DocStatus.UNSUPPORTED && "ole2".equals(r.format));
        byte[] ascii = Arrays.copyOf(plain, 8192); System.arraycopy("EncryptedPackage".getBytes(StandardCharsets.US_ASCII), 0, ascii, 4096, 16);
        T.eq("ASCII (not UTF-16LE) spelling is not a hit", DocStatus.UNSUPPORTED, Ole2.check(ascii, ascii.length).status);
        T.eq("not OLE2 magic -> UNSUPPORTED", DocStatus.UNSUPPORTED, Ole2.check(new byte[100], 100).status);
        byte[] late = new byte[Ole2.MAX_BYTES + 100]; System.arraycopy(plain, 0, late, 0, 8);
        System.arraycopy(name, 0, late, Ole2.MAX_BYTES + 10, name.length);
        T.eq("only the first 4 MiB are searched", DocStatus.UNSUPPORTED, Ole2.check(late, late.length).status);
        // plain text
        byte[] u16 = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "Grüße, 日本 text".getBytes(StandardCharsets.UTF_16LE));
        r = PlainText.decode(u16, u16.length, false, "txt");
        T.check("UTF-16LE with BOM decoded", r.status == DocStatus.OK && t(r).equals("Grüße, 日本 text"));
        byte[] u16b = concat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "Big endian".getBytes(StandardCharsets.UTF_16BE));
        T.eq("UTF-16BE with BOM", "Big endian", t(PlainText.decode(u16b, u16b.length, false, "txt")));
        byte[] u8bom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, u("Café"));
        T.eq("UTF-8 BOM removed", "Café", t(PlainText.decode(u8bom, u8bom.length, false, "txt")));
        T.eq("UTF-8 without BOM", "naïve café", t(PlainText.decode(u("naïve café"), u("naïve café").length, false, "txt")));
        byte[] w = {'c', 'a', 'f', (byte) 0xE9, ' ', (byte) 0x93, 'q', (byte) 0x94};
        T.eq("invalid UTF-8 -> Windows-1252", "café “q”", t(PlainText.decode(w, w.length, false, "csv")));
        // cut inside a multi-byte character at the 1 MiB boundary
        byte[] cut = new byte[PlainText.MAX_BYTES]; Arrays.fill(cut, (byte) 'a');
        cut[cut.length - 2] = (byte) 0xC3; cut[cut.length - 1] = (byte) 0xA9;
        byte[] cut2 = cut.clone(); cut2[cut2.length - 1] = (byte) 0xA9; cut2[cut2.length - 2] = (byte) 'b';
        byte[] half = Arrays.copyOf(cut, cut.length - 1);
        r = PlainText.decode(half, half.length, true, "txt");
        T.check("a character cut by the 1 MiB limit does not turn the text into Windows-1252", r.status == DocStatus.OK && t(r).length() == half.length - 1 && !t(r).contains("Ã"));
        r = PlainText.decode(new byte[PlainText.MAX_BYTES + 5000], PlainText.MAX_BYTES + 5000, true, "txt");
        T.check("longer buffers are cut at 1 MiB", r.status == DocStatus.UNSUPPORTED || r.text.length() <= PlainText.MAX_BYTES);
        byte[] bin = new byte[500]; new Random(3).nextBytes(bin); for (int i = 0; i < bin.length; i += 2) bin[i] = 1;
        T.eq("binary data in a .txt -> UNSUPPORTED", DocStatus.UNSUPPORTED, PlainText.decode(bin, bin.length, false, "txt").status);
        r = PlainText.decode(new byte[0], 0, false, "txt");
        T.check("empty buffer: OK with 0 chars (the stage never selects empty files)", r.status == DocStatus.OK && r.text.length() == 0);
    }

    static byte[] concat(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    static void hostile() throws Exception {
        // zip bomb: one entry that inflates to 40 MiB (central directory tells the truth)
        Z bz = new Z().add("[Content_Types].xml", u(CT));
        bz.z.putNextEntry(new ZipEntry("word/document.xml"));
        byte[] chunk = new byte[1 << 20]; Arrays.fill(chunk, (byte) ' ');
        for (int i = 0; i < 40; i++) bz.z.write(chunk);
        bz.z.closeEntry();
        byte[] bomb = bz.done();
        long t0 = System.nanoTime();
        OoxmlText.Result r = ox(bomb, "docx");
        long ms = (System.nanoTime() - t0) / 1000000L;
        T.check("zip bomb entry (" + bomb.length + " B -> 40 MiB) -> LIMIT, no text", r.status == DocStatus.LIMIT && r.text.length() == 0);
        T.check("bomb handled in < 1 s (" + ms + " ms)", ms < 1000);
        // a central directory that lies about the size: declares 1000 bytes, inflates to 40 MiB
        byte[] lie = bomb.clone();
        int lied = 0;
        for (int i = 0; i + 30 < lie.length; i++) if (lie[i] == 'P' && lie[i + 1] == 'K' && lie[i + 2] == 1 && lie[i + 3] == 2 && new String(lie, i + 46, 17, StandardCharsets.US_ASCII).equals("word/document.xml")) {
            lie[i + 24] = (byte) 0xE8; lie[i + 25] = 0x03; lie[i + 26] = 0; lie[i + 27] = 0; lied++;
        }
        t0 = System.nanoTime();
        r = ox(lie, "docx");
        T.check("lying central directory: no OK, no text, no exception", lied == 1 && r.status != DocStatus.OK && r.text.length() == 0);
        T.check("lying bomb in < 1 s", (System.nanoTime() - t0) / 1000000L < 1000);
        // ODF bomb through content.xml
        Z ob = new Z().stored("mimetype", u("application/vnd.oasis.opendocument.text"));
        ob.z.putNextEntry(new ZipEntry("content.xml"));
        for (int i = 0; i < 40; i++) ob.z.write(chunk);
        ob.z.closeEntry();
        T.eq("odt bomb -> LIMIT", DocStatus.LIMIT, od(ob.done(), "odt").status);
        // total 32 MiB: nine 3.9 MiB slides without any text
        Random rnd = new Random(5);
        StringBuilder sb = new StringBuilder("<p:sld>");
        char[] hex = "0123456789abcdef".toCharArray();
        while (sb.length() < (4 << 20) - 200000) { sb.append("<p:a v=\""); for (int k = 0; k < 40; k++) sb.append(hex[rnd.nextInt(16)]); sb.append("\"/>"); }
        sb.append("</p:sld>");
        byte[] slideBytes = u(sb.toString());
        Z many = new Z().add("[Content_Types].xml", u(CT));
        for (int n = 1; n <= 9; n++) many.add("ppt/slides/slide" + n + ".xml", slideBytes);
        t0 = System.nanoTime();
        r = ox(many.done(), "pptx");
        T.check("9 x ~3.9 MiB of slides (> 32 MiB read in total) -> LIMIT", r.status == DocStatus.LIMIT && r.text.length() == 0);
        T.check("total cap in < 10 s", (System.nanoTime() - t0) / 1000000L < 10000);
        // truncated and corrupted zips never throw
        byte[] base = new Z().add("[Content_Types].xml", u(CT)).add("word/document.xml", u(body("<w:p><w:r><w:t>Some paragraph text for fuzzing</w:t></w:r></w:p>")))
                .add("docProps/core.xml", u(core("T", "A", "2023-01-01T00:00:00Z", "2023-01-02T00:00:00Z"))).done();
        boolean ok = true;
        for (int cut = 0; cut < base.length; cut += 5) {
            try { if (ox(Arrays.copyOf(base, cut), "docx").status == null) ok = false; } catch (RuntimeException e) { ok = false; }
        }
        T.check("every 5th truncation point of a docx: no exception", ok);
        Random rr = new Random(11);
        for (int it = 0; it < 500; it++) {
            byte[] c = base.clone();
            for (int k = 0, n = 1 + rr.nextInt(10); k < n; k++) c[rr.nextInt(c.length)] = (byte) rr.nextInt(256);
            try { if (ox(c, "docx").status == null || od(c, "odt").status == null) ok = false; } catch (RuntimeException e) { ok = false; }
        }
        T.check("500 random corruptions as docx and odt: no exception", ok);
        // absurd directory: EOCD claims 65535 entries over a tiny file
        byte[] eocd = new byte[22]; eocd[0] = 'P'; eocd[1] = 'K'; eocd[2] = 5; eocd[3] = 6; eocd[10] = (byte) 0xFF; eocd[11] = (byte) 0xFF; eocd[12] = (byte) 0xFF; eocd[13] = (byte) 0xFF;
        T.check("EOCD with absurd counts/sizes: CORRUPT, no exception", ox(eocd, "docx").status == DocStatus.CORRUPT);
        // XML bombs inside an entry
        StringBuilder nest = new StringBuilder();
        for (int i = 0; i < 100000; i++) nest.append("<w:p>");
        r = ox(docx(nest.toString()), "docx");
        T.check("100,000 nested elements: stops, status defined (" + r.status + ")", r.status == DocStatus.CORRUPT || r.status == DocStatus.UNSUPPORTED);
        r = ox(docx("<w:p><w:r><w:t>" + repeatStr("&amp;", 500000) + "</w:t></w:r></w:p>"), "docx");
        T.check("500,000 repeated entities: ratio guard or cap, never more than 200,000 chars", (r.status == DocStatus.OK || r.status == DocStatus.UNSUPPORTED) && r.text.length() <= OoxmlText.MAX_CHARS);
        XmlText.Out eo = XmlText.text(u("<w:p><w:t>" + repeatStr("&amp;", 500000) + "</w:t></w:p>"), TXT, BRK, 200000);
        T.check("XmlText alone: 500,000 entities capped at 200,000 chars", eo.text.length() == 200000 && eo.full);
        // duplicate names and odd paths do not matter
        T.check("duplicate entry names: first wins, no exception", ox(new Z().add("[Content_Types].xml", u(CT)).add("word/document.xml", u(body("<w:p><w:t>first version of text</w:t></w:p>")))
                .add("../word/document.xml", u(body("<w:p><w:t>traversal</w:t></w:p>"))).done(), "docx").status == DocStatus.OK);
    }

    static String repeatStr(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    // ---- the DOC pass dispatch ----
    static DocInfo doc(Fx fx, String path) { FeatureRecord fr = fx.store.features().get(fx.f(path).fileId); return fr == null ? null : fr.doc; }

    static void stageTests() throws Exception {
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2;                                           // zip subtypes (docx, odt...) are decided by T2
            ContentConfig cfg = new ContentConfig(); cfg.docMaxBytes = 5000;
            fx.extraStages.add(new ContentStage(new ContentProviders(null, null), cfg));
            String secret = "ZXQVSecretMarkerWord";
            fx.tree.file("Documents/good.docx", docx("<w:p><w:r><w:t>" + secret + " alpha beta gamma delta</w:t></w:r></w:p>"));
            fx.tree.file("Documents/sheet.xlsx", new Z().add("[Content_Types].xml", u(CT)).add("xl/workbook.xml", u("<workbook/>"))
                    .add("xl/sharedStrings.xml", u("<sst><si><t>Rent and utilities for the flat</t></si></sst>")).done());
            fx.tree.file("Documents/deck.pptx", new Z().add("[Content_Types].xml", u(CT)).add("ppt/slides/slide1.xml", u("<p:sld><a:p><a:t>Quarterly review of everything</a:t></a:p></p:sld>")).done());
            fx.tree.file("Documents/letter.odt", new Z().stored("mimetype", u("application/vnd.oasis.opendocument.text"))
                    .add("content.xml", u("<office:document-content><text:p>Dear society secretary please note</text:p></office:document-content>")).done());
            fx.tree.file("Documents/book.ods", new Z().stored("mimetype", u("application/vnd.oasis.opendocument.spreadsheet"))
                    .add("content.xml", u("<office:document-content><text:p>Cell one</text:p><text:p>Cell two is longer</text:p></office:document-content>")).done());
            fx.tree.file("Documents/drawing.odg", new Z().stored("mimetype", u("application/vnd.oasis.opendocument.graphics")).add("content.xml", u("<a/>")).done());
            byte[] ole = new byte[2048];
            int[] magic = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};
            for (int i = 0; i < 8; i++) ole[i] = (byte) magic[i];
            byte[] encOle = ole.clone();
            byte[] nm = "EncryptedPackage".getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(nm, 0, encOle, 512, nm.length);
            fx.tree.file("Documents/protected.docx", encOle);
            fx.tree.file("Documents/old.doc", ole);
            fx.tree.file("Documents/notes.txt", u("Plain notes with a few words " + secret));
            fx.tree.file("Documents/data.csv", u("a,b,c\n1,2,3\n4,5,6\n"));
            fx.tree.file("Documents/contacts.csv", u("Name Phone\nAsha 98200 00000\nRavi 98200 11111\n"));     // not csv-like: the detector says text
            fx.tree.file("Documents/server.log", u("not a document class: log"));
            fx.tree.file("Documents/big.txt", u(repeatStr("x ", 4000)));
            fx.tree.file("Documents/bad.docx", concat(new byte[]{'P', 'K', 3, 4}, new byte[300]));
            fx.tree.file("Documents/enc-entry.docx", setFlag(docx("<w:p><w:r><w:t>Secret text that is encrypted</w:t></w:r></w:p>")));
            fx.tree.file("Documents/empty.txt", new byte[0]);
            fx.scan();
            DocInfo g = doc(fx, "Documents/good.docx");
            T.check("docx: OK, format, chars, words", g != null && g.status == DocStatus.OK && "docx".equals(g.format) && g.chars == (secret + " alpha beta gamma delta\n").length() && g.words == 5);
            T.eq("docx: unsupportedStreams stays unknown (PDF only)", -1, g.unsupportedStreams);
            T.check("xlsx OK", doc(fx, "Documents/sheet.xlsx").status == DocStatus.OK && "xlsx".equals(doc(fx, "Documents/sheet.xlsx").format) && doc(fx, "Documents/sheet.xlsx").words == 6);
            T.check("pptx OK with pages 1", doc(fx, "Documents/deck.pptx").status == DocStatus.OK && doc(fx, "Documents/deck.pptx").pages == 1);
            T.check("odt OK", doc(fx, "Documents/letter.odt").status == DocStatus.OK && "odt".equals(doc(fx, "Documents/letter.odt").format));
            T.check("ods OK", doc(fx, "Documents/book.ods").status == DocStatus.OK && "ods".equals(doc(fx, "Documents/book.ods").format) && doc(fx, "Documents/book.ods").words == 6);
            T.check("odg not selected", doc(fx, "Documents/drawing.odg") == null);
            DocInfo pe = doc(fx, "Documents/protected.docx");
            T.check("password-protected docx (OLE2 EncryptedPackage): ENCRYPTED, counts unknown", pe != null && pe.status == DocStatus.ENCRYPTED && pe.chars == -1 && pe.words == -1 && "ole2".equals(pe.format));
            DocInfo od = doc(fx, "Documents/old.doc");
            T.check("legacy .doc: UNSUPPORTED, unknown counts, format ole2", od != null && od.status == DocStatus.UNSUPPORTED && od.chars == -1 && "ole2".equals(od.format));
            T.check("txt OK with words", doc(fx, "Documents/notes.txt").status == DocStatus.OK && doc(fx, "Documents/notes.txt").words == 7 && "txt".equals(doc(fx, "Documents/notes.txt").format));
            T.check("csv OK", doc(fx, "Documents/data.csv").status == DocStatus.OK && "csv".equals(doc(fx, "Documents/data.csv").format));
            T.check("a .csv the detector calls text is still csv", doc(fx, "Documents/contacts.csv") != null && "csv".equals(doc(fx, "Documents/contacts.csv").format) && doc(fx, "Documents/contacts.csv").status == DocStatus.OK);
            T.check(".log is not a document class", doc(fx, "Documents/server.log") == null);
            T.check("txt over docMaxBytes: TOO_LARGE, unknown counts", doc(fx, "Documents/big.txt").status == DocStatus.TOO_LARGE && doc(fx, "Documents/big.txt").chars == -1);
            DocInfo bd = doc(fx, "Documents/bad.docx");
            T.check("junk with a zip signature: not selected as docx or CORRUPT, never OK", bd == null || bd.status != DocStatus.OK);
            DocInfo ee = doc(fx, "Documents/enc-entry.docx");
            T.check("zip entry flagged encrypted: ENCRYPTED, chars unknown", ee != null && ee.status == DocStatus.ENCRYPTED && ee.chars == -1);
            T.check("empty txt not selected", doc(fx, "Documents/empty.txt") == null);
            // privacy: no marker word anywhere in the stored record
            FeatureRecord fg = fx.store.features().get(fx.f("Documents/good.docx").fileId);
            FeatureRecord fn = fx.store.features().get(fx.f("Documents/notes.txt").fileId);
            StringBuilder all = new StringBuilder();
            for (FeatureRecord fr : new FeatureRecord[]{fg, fn}) {
                java.io.StringWriter sw = new java.io.StringWriter();
                ContentCodec.write(new JsonWriter(sw), fr.doc);
                all.append(sw);
                for (String p : fr.problems) all.append(p);
                for (Object en : fr.extra.entrySet()) all.append(en);
            }
            T.check("no raw text in any stored form", !all.toString().contains(secret) && !all.toString().contains("alpha"));
            T.eq("version stamped", Extractors.version(Extractors.DOC_TEXT), fg.versions[Extractors.DOC_TEXT]);
            long first = fx.session(1).counters.get(ContentStage.COUNT_DOC);
            fx.scan();
            Long again = fx.session(2).counters.get(ContentStage.COUNT_DOC);
            T.check("second FULL run reads 0 of " + first, again == null || again == 0);
            T.check("no incomplete event", !Fx.hasEvent(fx.session(1), ContentStage.INCOMPLETE));
        }
        // open failure on an Office file -> CORRUPT, stamped
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2;
            fx.extraStages.add(new ContentStage(new ContentProviders(null, null), new ContentConfig()));
            fx.tree.file("Documents/a.docx", docx("<w:p><w:r><w:t>Some document text here to read</w:t></w:r></w:p>"));
            fx.faulty.denyOpen("Documents/a.docx");
            fx.scan();
            DocInfo a = doc(fx, "Documents/a.docx");
            T.check("open failure -> CORRUPT (unknown), stamped", a != null && a.status == DocStatus.CORRUPT && a.chars == -1);
        }
    }

    // ---- dataset ----
    static void datasetTest() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("p6.dataset", "/home/user/sp-dataset"));
        java.nio.file.Path f = root.resolve("Documents/Letter_to_Society_Secretary.docx");
        if (!java.nio.file.Files.isRegularFile(f)) { System.out.println("  (dataset not present: docx check skipped)"); return; }
        byte[] b = java.nio.file.Files.readAllBytes(f);
        OoxmlText.Result r = ox(b, "docx");
        T.check("dataset Letter_to_Society_Secretary.docx: OK, chars > 100", r.status == DocStatus.OK && r.text.length() > 100);
    }
}
