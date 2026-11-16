package com.storageplanner.v2.content.doc;

import com.storageplanner.v2.model.DocStatus;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * txt / csv (PHASE_6 7.3): the first 1 MiB; a BOM selects UTF-8 / UTF-16, else valid UTF-8 is UTF-8 and anything else is
 * Windows-1252. A buffer that is mostly control characters is binary data: UNSUPPORTED (unknown), not "text".
 */
public final class PlainText {
    private PlainText() {}

    public static final int MAX_BYTES = 1 << 20;

    /** @param b the first bytes of the file; len valid bytes; more = the file continues after them (a cut multi-byte character is tolerated) */
    public static OoxmlText.Result decode(byte[] b, int len, boolean more, String format) {
        OoxmlText.Result r = new OoxmlText.Result();
        r.format = format;
        try {
            len = Math.min(len, Math.min(b.length, MAX_BYTES));
            String s;
            if (len >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) s = utf8(b, 3, len - 3, more);
            else if (len >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) s = new String(b, 2, (len - 2) & ~1, StandardCharsets.UTF_16LE);
            else if (len >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) s = new String(b, 2, (len - 2) & ~1, StandardCharsets.UTF_16BE);
            else {
                s = utf8(b, 0, len, more);
                if (s == null) {
                    StringBuilder sb = new StringBuilder(len);
                    for (int i = 0; i < len; i++) sb.append(PdfText.cp1252(b[i] & 0xFF));
                    s = sb.toString();
                }
            }
            if (s == null) s = "";
            int ctl = 0;
            for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') ctl++; }
            if (ctl * 10L > s.length() * 3L) { r.status = DocStatus.UNSUPPORTED; return r; }
            r.text.append(s);
            r.status = DocStatus.OK;
        } catch (RuntimeException e) {
            r.status = DocStatus.CORRUPT; r.text.setLength(0);
        }
        return r;
    }

    /** Strict UTF-8 decode of b[off, off+len); null when invalid (an incomplete last character is dropped when more data follows). */
    private static String utf8(byte[] b, int off, int len, boolean more) {
        int end = off + len;
        if (more) {                                      // cut inside a multi-byte sequence: drop it
            int k = end - 1, back = 0;
            while (k > off && back < 3 && (b[k] & 0xC0) == 0x80) { k--; back++; }
            int lead = b[k] & 0xFF, need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
            if (need > 1 && end - k < need) end = k;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, end - off)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
