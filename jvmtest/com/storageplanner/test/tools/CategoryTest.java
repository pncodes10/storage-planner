package com.storageplanner.test.tools;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Scanner;
import com.storageplanner.test.JvmPlatform;
import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.tools.ocr.TextRules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorting pictures into categories on a labelled corpus laid out like a phone (DCIM/Camera, Pictures/Screenshots,
 * Download, WhatsApp Images): real receipts (SROIE), invoices (invoice2data), document pages (Tesseract and
 * OCRmyPDF tests), phone photos and faces; synthetic screenshots, greetings, QR scenes and accidental shots.
 * The whole scan runs as on the phone (Scanner + Sorter); the text reader's words come from the same Tesseract
 * build run in Chromium ("<picture>.ocr.json"), faces from "<picture>.faces".
 * usage: CategoryTest <device dir whose sdcard is the corpus> <labels.json> [--list] [--check]
 */
public final class CategoryTest {
    public static void main(String[] a) throws Exception {
        boolean list = false, check = false;
        for (String x : a) { if (x.equals("--list")) list = true; if (x.equals("--check")) check = true; }
        String js = new String(Files.readAllBytes(new File(a[1]).toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"([^\"]+)\": \\{\\s*\"label\": \"([^\"]+)\",\\s*\"source\": \"([^\"]+)\"").matcher(js);
        Map<String, String[]> labels = new TreeMap<String, String[]>();
        while (m.find()) labels.put(m.group(1), new String[]{m.group(2), m.group(3)});

        JvmPlatform pf = new JvmPlatform(a[0]);
        long t0 = System.nanoTime();
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        double secs = (System.nanoTime() - t0) / 1e9;
        Map<String, FileItem> byRel = new TreeMap<String, FileItem>();
        for (FileItem f : r.files) byRel.put(f.rel, f);

        Map<String, int[]> per = new LinkedHashMap<String, int[]>();
        Map<String, String> src = new LinkedHashMap<String, String>();
        Map<String, Integer> confusion = new TreeMap<String, Integer>();
        int right = 0, n = 0, read = 0, readEveryday = 0, everyday = 0;
        for (Map.Entry<String, String[]> e : labels.entrySet()) {
            FileItem f = byRel.get(e.getKey());
            String want = e.getValue()[0];
            String got = f == null ? "missing" : f.category;
            boolean ok = false;
            for (String w : want.split("\\|")) if (w.equals(got)) ok = true;
            int[] p = per.get(want); if (p == null) { p = new int[2]; per.put(want, p); src.put(want, e.getValue()[1]); }
            p[1]++; n++;
            if (ok) { p[0]++; right++; } else confusion.merge(want + "->" + got, 1, Integer::sum);
            boolean everydayPhoto = want.equals("photo") || want.equals("people");
            if (everydayPhoto) everyday++;
            if (f != null && (f.text != null || f.category != null && !f.textPending && wasRead(f))) { read++; if (everydayPhoto) readEveryday++; }
            if (list && !ok) System.out.printf("  MISS %-11s got %-11s %s %s%n", want, got, e.getKey(), f != null && f.text != null ? "[" + f.text.substring(0, Math.min(70, f.text.length())) + "]" : "");
        }
        System.out.println("per category (right / total):");
        for (Map.Entry<String, int[]> e : per.entrySet())
            System.out.printf(Locale.ROOT, "  %-18s %3d / %3d  %5.1f%%   %s%n", e.getKey(), e.getValue()[0], e.getValue()[1], 100.0 * e.getValue()[0] / e.getValue()[1], src.get(e.getKey()));
        System.out.println("mistakes: " + confusion);
        System.out.printf(Locale.ROOT, "overall %d / %d (%.1f%%); everyday photos sent to the text reader: %d of %d; scan %.1f s for %d files%n",
                right, n, 100.0 * right / n, readEveryday, everyday, secs, r.files.size());
        if (check) {
            int fails = 0;
            // a second scan reuses everything for unchanged pictures: same categories, far less work
            Scanner.Options again = new Scanner.Options();
            again.previous = byRel;
            long t1 = System.nanoTime();
            Scanner.Result r2 = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } }, again);
            double secs2 = (System.nanoTime() - t1) / 1e9;
            int same = 0, total = 0;
            for (FileItem f : r2.files) if (byRel.containsKey(f.rel) && byRel.get(f.rel).category != null) {
                total++;
                FileItem o = byRel.get(f.rel);
                if (f.category.equals(o.category) && java.util.Objects.equals(f.text, o.text) && java.util.Objects.equals(f.code, o.code)) same++;
            }
            System.out.printf(Locale.ROOT, "rescan: %d / %d pictures identical, %.1f s instead of %.1f s%n", same, total, secs2, secs);
            if (same != total || secs2 > secs / 2) { System.out.println("FAIL rescan"); fails++; }
            // with no time to read text, pictures stay "not read yet"; the next scan reads them
            Scanner.Options none = new Scanner.Options();
            none.textBudgetMs = -1;
            Scanner.Result r3 = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } }, none);
            Map<String, FileItem> m3 = new TreeMap<String, FileItem>();
            int pending = 0;
            for (FileItem f : r3.files) { m3.put(f.rel, f); if (f.textPending) pending++; }
            Scanner.Options later = new Scanner.Options();
            later.previous = m3;
            Scanner.Result r4 = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } }, later);
            int pending4 = 0, match = 0, cnt = 0;
            for (FileItem f : r4.files) {
                if (f.textPending) { pending4++; if (pending4 <= 3) System.out.println("  still unread: " + f.rel + " area " + f.area); }
                if (byRel.containsKey(f.rel) && byRel.get(f.rel).category != null) { cnt++; if (f.category.equals(byRel.get(f.rel).category)) match++; }
            }
            System.out.printf(Locale.ROOT, "time limit: %d pictures left unread, all %s read in the next scan (%d / %d categories as in one full scan)%n",
                    pending, pending4 == 0 ? "" : "NOT", match, cnt);
            if (pending == 0 || pending4 != 0 || match != cnt) { System.out.println("FAIL unread pictures not completed by the next scan"); fails++; }
            for (Map.Entry<String, int[]> e : per.entrySet()) {
                double min = MIN.containsKey(e.getKey()) ? MIN.get(e.getKey()) : 0.8;
                if (e.getValue()[0] < min * e.getValue()[1]) { System.out.println("FAIL " + e.getKey() + " below " + Math.round(min * 100) + "%"); fails++; }
            }
            if (readEveryday > 0.25 * everyday) { System.out.println("FAIL too many everyday photos sent to the text reader"); fails++; }
            System.out.println(fails == 0 ? "category checks pass" : fails + " category checks FAILED");
            if (fails > 0) System.exit(1);
        }
    }

    /** Lowest acceptable share right per label (set from what the methods can do; see README). */
    static final Map<String, Double> MIN = new TreeMap<String, Double>();
    static {
        // regression floors, a little under what this version reaches (see README for the numbers)
        MIN.put("greeting", 0.30); MIN.put("accidental", 0.70); MIN.put("bill", 0.55); MIN.put("document", 0.65);
        MIN.put("receipt", 0.90); MIN.put("qr", 0.95); MIN.put("photo", 0.95); MIN.put("people", 0.95);
        MIN.put("payment", 0.95); MIN.put("otp", 0.95); MIN.put("ticket", 0.95); MIN.put("chat", 0.95); MIN.put("screenshot", 0.95);
    }

    static boolean wasRead(FileItem f) { return f.text != null; }
}
