package com.storageplanner.tools.codes;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** QR codes and barcodes in a picture, read with ZXing (Apache 2.0, bundled source, no network). */
public final class Codes {
    private Codes() {}

    /** {format, text} of the first code found, or null. */
    public static String[] read(int[] luma, int w, int h, boolean thorough) {
        byte[] y = new byte[w * h];
        for (int i = 0; i < y.length; i++) y[i] = (byte) luma[i];
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417,
                BarcodeFormat.AZTEC, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF));
        if (thorough) hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result r = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false))), hints);
            return new String[]{r.getBarcodeFormat().name(), r.getText()};
        } catch (Exception e) {
            return null;
        } catch (StackOverflowError e) {
            return null;
        }
    }

    /** What a code is for, in a few words ("UPI payment to shop@okaxis", "Wi-Fi network Home", "Link example.com"). */
    public static String describe(String format, String text) {
        if (text == null) return null;
        String t = text.trim(), l = t.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("upi://")) {
            String pa = param(t, "pa"), pn = param(t, "pn");
            return "UPI payment code" + (pn != null ? " for " + pn : pa != null ? " for " + pa : "");
        }
        if (l.startsWith("wifi:")) { String s = between(t, "S:", ";"); return "Wi-Fi network" + (s != null ? " " + s : ""); }
        if (l.startsWith("http://") || l.startsWith("https://")) {
            String host = t.replaceFirst("(?i)^https?://", ""); int k = host.indexOf('/'); if (k > 0) host = host.substring(0, k);
            return "Link to " + host;
        }
        if (l.startsWith("begin:vcard") || l.startsWith("mecard:")) return "Contact card";
        if (l.startsWith("mailto:")) return "E-mail address";
        if (l.startsWith("tel:")) return "Phone number";
        if (format != null && !format.equals("QR_CODE")) return "Barcode " + (t.length() > 24 ? t.substring(0, 24) + "…" : t);
        return "Text: " + (t.length() > 40 ? t.substring(0, 40) + "…" : t);
    }

    private static String param(String uri, String k) {
        int q = uri.indexOf('?'); if (q < 0) return null;
        for (String p : uri.substring(q + 1).split("&")) {
            int e = p.indexOf('=');
            if (e > 0 && p.substring(0, e).equalsIgnoreCase(k)) {
                return unescape(p.substring(e + 1));
            }
        }
        return null;
    }

    /** %XX and + decoding (kept here so the app has no networking classes at all). */
    static String unescape(String s) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') o.write(' ');
            else if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                o.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16)); i += 2;
            } else { byte[] b = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8); o.write(b, 0, b.length); }
        }
        return new String(o.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a); if (i < 0) return null; int j = s.indexOf(b, i + a.length()); return j < 0 ? s.substring(i + a.length()) : s.substring(i + a.length(), j);
    }
}
