package com.storageplanner.v2.content.doc;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.ArchiveEntry;
import com.storageplanner.v2.model.DocStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Text of odt / ods / odp (PHASE_6 7.3): {@code content.xml} through {@link XmlText}, metadata from {@code meta.xml}; same caps as {@link OoxmlText}. */
public final class OdfText {
    private OdfText() {}

    static final Set<String> TEXT = OoxmlText.set("text:p", "text:span", "text:h");
    static final Set<String> BREAK = OoxmlText.set("text:p", "text:h");

    /** @param format odt, ods or odp */
    public static OoxmlText.Result extract(ReadableFile rf, long size, String format) {
        OoxmlText.Result r = new OoxmlText.Result();
        r.format = format;
        try {
            run(rf, size, r);
        } catch (IOException e) {
            r.status = DocStatus.CORRUPT; r.text.setLength(0);
        } catch (RuntimeException e) {
            r.status = DocStatus.CORRUPT; r.text.setLength(0);
        }
        return r;
    }

    private static void run(ReadableFile rf, long size, OoxmlText.Result r) throws IOException {
        Map<String, ArchiveEntry> m = OoxmlText.list(rf, size);
        if (m == null) { r.status = DocStatus.CORRUPT; return; }
        OoxmlText.Reader rd = new OoxmlText.Reader(rf, size, r);
        // password-protected ODF: the manifest says so (the entries themselves look like ordinary deflate data)
        byte[] man = rd.read(m.get("META-INF/manifest.xml"));
        if (man != null && new String(man, StandardCharsets.UTF_8).contains("encryption-data")) { r.status = DocStatus.ENCRYPTED; return; }
        boolean malformed = false;
        boolean main = m.containsKey("content.xml");
        if (main && !rd.limit && !rd.encrypted) {
            byte[] b = rd.read(m.get("content.xml"));
            if (b != null) {
                XmlText.Out o = XmlText.text(b, TEXT, BREAK, OoxmlText.MAX_CHARS);
                malformed = o.malformed;
                r.text.append(o.text);
            }
        }
        byte[] meta = rd.limit || rd.encrypted ? null : rd.read(m.get("meta.xml"));
        if (meta != null) {
            r.title = XmlText.firstText(meta, "dc:title");
            r.author = XmlText.firstText(meta, "meta:initial-creator");
            r.createdAt = OoxmlText.isoDate(XmlText.firstText(meta, "meta:creation-date"));
            int p = OoxmlText.intOf(XmlText.attribute(meta, "meta:document-statistic", "meta:page-count"));
            if (p >= 0) r.pages = p;
        }
        if (rd.encrypted) { r.status = DocStatus.ENCRYPTED; r.text.setLength(0); return; }
        if (rd.limit) { r.status = DocStatus.LIMIT; r.text.setLength(0); return; }
        if (!main) { r.status = DocStatus.UNSUPPORTED; return; }
        int chars = r.text.length();
        if (malformed && chars < 20) { r.status = DocStatus.CORRUPT; return; }
        if (r.unsupported > 0 && chars < 20) { r.status = DocStatus.UNSUPPORTED; return; }
        r.status = DocStatus.OK;
    }
}
