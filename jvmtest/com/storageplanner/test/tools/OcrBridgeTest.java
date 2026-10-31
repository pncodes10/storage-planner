package com.storageplanner.test.tools;

import com.storageplanner.tools.ocr.TextRules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The words the app's text-reader page hands to Java (recorded by scripts/test_ocr_page.py from the shipped
 * assets) are parsed by the same code the app uses and classified as expected.
 * usage: OcrBridgeTest <dir with 1.json, 2.json, ...> <expected kind for 1> <expected kind for 2> ...
 */
public final class OcrBridgeTest {
    public static void main(String[] a) throws Exception {
        int fails = 0;
        for (int i = 1; i < a.length; i++) {
            String json = new String(Files.readAllBytes(new File(a[0], i + ".json").toPath()), StandardCharsets.UTF_8);
            TextRules.Words w = TextRules.parseJson(json);
            boolean shot = a[i].equals("otp") || a[i].equals("payment") || a[i].equals("chat");
            TextRules.Verdict v = TextRules.classify(w, shot ? 0.45 : 0.4, false, shot, 1792000000000L);
            boolean ok = a[i].equals(String.valueOf(v.kind)) && w.text.size() > 5;
            System.out.println((ok ? "ok   " : "FAIL ") + i + ": " + w.text.size() + " words, " + v.goodWords + " confident, kind " + v.kind + " (want " + a[i] + ") " + v.why);
            if (!ok) fails++;
        }
        if (fails > 0) System.exit(1);
        System.out.println("bridge checks pass");
    }
}
