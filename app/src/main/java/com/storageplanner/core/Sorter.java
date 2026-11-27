package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scan stage: sorts pictures into categories. Pass 1 measures every picture (text rows, paper, screens,
 * darkness, codes, faces). Pass 2 reads the text of the pictures worth reading, screenshots and newest first,
 * within a time limit; whatever is left is read in the next scan (unchanged pictures are never redone).
 */
final class Sorter {
    private Sorter() {}

    static final int EDGE = 1024, BIG = 2048, TEXT_EDGE = 1600, FACE_EDGE = 512, GREETING_EDGE = 360;
    static final int MAX_TEXT = 4000;

    /**
     * Version of the sorting rules. Pictures sorted under an older version are measured and sorted once more at the
     * next scan (the file is unchanged, but the rules or the measurements are new). Raise it whenever a change
     * should reach pictures that are already sorted. 1: page-of-paper check. 2: eyes open on every photo with a face,
     * cards and papers held up are read (ID cards). 3: focus ignores sensor grain (night skies), glare share.
     * 4: a small sharp subject on a blurred background is not an accidental (shaken) shot.
     * 5: smiles, faces looking at the camera, and how close the biggest face is (selfies).
     * 6: faces are looked for on received, downloaded and saved photos too (they were all "Photos without faces").
     */
    static final int VERSION = 6;

    /** A picture this flat (share of flat screen-like areas) is a drawn or designed graphic, not looked at for faces. */
    static final double FLAT_GRAPHIC = 0.35;

    /**
     * Version of the scene network and the aesthetic score (SceneNet, Scene, Aesthetic). Pictures measured with an
     * older one are measured again. 1: first.
     */
    static final int SCENE_VERSION = 1;
    /** The scene network looks at a copy this big (then squeezes it to 224 x 224). */
    static final int SCENE_EDGE = 448;

    /** True when this earlier result for an unchanged picture can be kept as it is. */
    static boolean current(FileItem p) { return p != null && p.category != null && !p.textPending && p.sortVersion >= VERSION; }

    /** Pass-1 measurements of one picture, kept until it is classified. */
    static final class Prep { ImageSignals.S s; double aspect; }

    /**
     * Measures one picture from ARGB pixels at ~EDGE px (dims = {origW, origH, w, h}): signals, codes and faces.
     * Called by the scanner while it has the pixels anyway (camera photos), or by sort() for the rest.
     */
    static Prep measure(Platform pf, FileItem f, int[] px, int[] dims) {
        int w = dims[2], h = dims[3];
        if (f.width == 0) { f.width = dims[0]; f.height = dims[1]; }
        ImageSignals.S s = ImageSignals.measure(px, w, h);
        int[] l = luma(px);
        f.signals = s;                       // kept on the file (saved with the results) for tags; moreText below refines the same object
        if (s.paper >= 0.2 && s.textBlobs < 150 && Math.max(dims[0], dims[1]) > 1100) {     // small print: look again bigger
            int[] d2 = new int[4];
            int[] big;
            try { big = pf.argb(f.abs, BIG, d2); } catch (Throwable t) { big = null; }
            if (big != null) ImageSignals.moreText(luma(big), d2[2], d2[3], s);
        }
        String[] code = Codes.read(l, w, h, false);
        if (code == null && (s.flat >= 0.1 || s.paper >= 0.2)) code = Codes.read(l, w, h, true);
        f.code = code == null ? null : Codes.describe(code[0], code[1]);
        f.faces = -1;
        f.eyeFaces = -1; f.eyesOpen = -1; f.smiling = -1; f.looking = -1; f.faceSize = -1;
        // faces: on every picture that may be a photo. A page or a card too (someone holding it up is a person, an ID card
        // has a face on it); and photos whose text is read anyway (received in a chat, downloads, Pictures), which before
        // version 6 were never looked at for faces and all came out as "Photos without faces". Not on screenshots,
        // accidental shots, or flat drawn or designed graphics (mostly memes and posters; their faces are drawings)
        if ((!PhotoCategory.worthReading(f, s) || s.page() || PhotoCategory.cardCandidate(s) || s.flat < FLAT_GRAPHIC)
                && !PhotoCategory.isScreenshot(f) && !PhotoCategory.accidental(s))
            faces(pf, f, px, w, h, l);
        Prep p = new Prep();
        p.s = s; p.aspect = dims[0] / (double) Math.max(1, dims[1]);
        return p;
    }

    static void sort(Platform pf, Scanner.Progress pr, Scanner.Result r, Scanner.Options opt, Map<FileItem, Prep> measured) {
        List<FileItem> pics = new ArrayList<FileItem>();
        for (FileItem f : r.files) if (PhotoCategory.sortable(f)) pics.add(f);
        r.mirageNote = sort(pf, pr, pics, r.now, opt, measured);
    }

    /**
     * Sorts these pictures (all of them, or one batch of the analysis): measures, reads text, looks at what photos show,
     * then Mirage. Returns why Mirage could not look at them (see mirage), or null.
     */
    static String sort(final Platform pf, Scanner.Progress pr, List<FileItem> pics, long now, final Scanner.Options opt, Map<FileItem, Prep> measured) {
        final Map<FileItem, ImageSignals.S> sig = java.util.Collections.synchronizedMap(new HashMap<FileItem, ImageSignals.S>());
        final Map<FileItem, double[]> aspect = java.util.Collections.synchronizedMap(new HashMap<FileItem, double[]>());
        final List<FileItem> toRead = java.util.Collections.synchronizedList(new ArrayList<FileItem>());
        final List<FileItem> picList = pics;
        if (!Par.forEach(picList.size(), opt.workers, opt.highPriority, pr, "Sorting pictures", new Par.Body() { public void run(int k, int[] dims) {
            FileItem f = picList.get(k);
            FileItem p = Scanner.same(f, opt.previous);
            if (p != null && p.sceneVersion == SCENE_VERSION) { f.scene = p.scene; f.aesthetic = p.aesthetic; f.sceneVersion = p.sceneVersion; }
            if (p != null && p.mirageVersion == AlgorithmV3.VERSION && p.sortVersion >= VERSION) copyMirage(p, f);   // v3 builds on this sorting
            if (current(p)) {                                                  // unchanged and fully sorted last time, by the current rules
                copyOver(p, f);
                return;
            }
            Prep m = measured == null ? null : measured.get(f);
            if (m == null) {
                int[] px;
                try { px = pf.argb(f.abs, EDGE, dims); } catch (Throwable t) { px = null; }
                if (px == null) {                                          // cannot be decoded now: keep what the older sort found
                    if (p != null && p.category != null) copyOver(p, f);
                    return;
                }
                m = measure(pf, f, px, dims);
            }
            ImageSignals.S s = m.s;
            if (f.quality != null) f.faces = f.quality.faces;          // measured more carefully for near-identical shots
            // sorted before by older rules: measured again, but a verdict that came from the text or a code stays,
            // and the text already read is not read again
            boolean again = p != null && p.category != null && !p.textPending;
            if (again && !PhotoCategory.PHOTO.equals(p.category) && !PhotoCategory.PEOPLE.equals(p.category) && !PhotoCategory.ACCIDENTAL.equals(p.category)) {
                int ef = f.eyeFaces, eo = f.eyesOpen, sm = f.smiling, lk = f.looking;   // just measured: newer than what the older sort kept
                float fs = f.faceSize;
                copyOver(p, f);
                f.signals = s; f.sortVersion = VERSION; f.eyeFaces = ef; f.eyesOpen = eo; f.smiling = sm; f.looking = lk; f.faceSize = fs;
                return;
            }
            boolean read = PhotoCategory.worthReading(f, s);           // a receipt or ticket can carry a code too
            if (again && !((s.page() || PhotoCategory.cardLike(f, s)) && p.text == null)) read = false;   // read only what the newer checks newly send
            PhotoCategory.Result c = PhotoCategory.classify(f, s, f.code, null, f.faces);
            f.category = c.category; f.catDate = again && !read ? p.catDate : 0; f.text = again && !read ? p.text : null;
            f.textPending = read; f.sortVersion = VERSION;
            if (read) { toRead.add(f); sig.put(f, s); aspect.put(f, new double[]{m.aspect}); }
        } })) return null;
        int[] dims = new int[4];
        // ---- pass 2: read text, screenshots first, then newest first, within the time limit
        Collections.sort(toRead, new Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) {
                boolean sa = PhotoCategory.isScreenshot(a), sb = PhotoCategory.isScreenshot(b);
                if (sa != sb) return sa ? -1 : 1;
                return Long.compare(b.mtime, a.mtime);
            }
        });
        long t0 = System.currentTimeMillis();
        int fails = 0;
        // with opt.textThreads > 1 the next few pictures are decoded and sent to the reader ahead of time, so its workers never
        // wait for this loop (and a slow main thread in the background no longer stalls it); the results are used in order
        final java.util.concurrent.ExecutorService ex = opt.textThreads > 1 ? textPool(opt) : null;
        final List<java.util.concurrent.Future<TextRules.Words>> ahead = new ArrayList<java.util.concurrent.Future<TextRules.Words>>();
        try {
        for (int k = 0; k < toRead.size(); k++) {
            if (pr.cancelled()) return null;
            if (System.currentTimeMillis() - t0 > opt.textBudgetMs) break;     // the rest stays "not read yet"
            if (!opt.readerWorked && opt.readerFails >= 3) break;              // no reader on this phone (earlier batches): next scan
            pr.update("Reading text in pictures", k + 1, toRead.size());
            FileItem f = toRead.get(k);
            TextRules.Words words = null;
            if (ex != null) {
                while (ahead.size() < toRead.size() && ahead.size() < k + opt.textThreads) {
                    final FileItem g = toRead.get(ahead.size());
                    ahead.add(ex.submit(new java.util.concurrent.Callable<TextRules.Words>() {
                        public TextRules.Words call() { return readFirst(pf, g); }
                    }));
                }
                try { words = ahead.get(k).get(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                catch (Exception e) { words = null; }
            } else {
                words = readFirst(pf, f);
            }
            if (words == null) {
                opt.readerFails++;
                if (++fails >= 3 && k + 1 == fails && !opt.readerWorked) break;    // no reader on this phone: try again next scan
                if (k + 1 > fails || opt.readerWorked) words = new TextRules.Words();   // the reader works, this picture just gave nothing
                else continue;
            } else { opt.readerWorked = true; opt.readerFails = 0; }
            fails = 0;
            ImageSignals.S s = sig.get(f);
            TextRules.Verdict v = TextRules.classify(words, aspect.get(f)[0], s.colorful >= 15 && s.flat < 0.5, PhotoCategory.isScreenshot(f),
                    f.area.startsWith("wa_") || "telegram_media".equals(f.area), now);
            boolean chat = f.area.startsWith("wa_") || "telegram_media".equals(f.area);
            boolean photoLike = s.colorful >= 15 && s.flat < 0.5;
            if (v.kind == null && chat && photoLike) {
                // greeting images use big decorative letters; the reader often sees them better on a small copy
                try {
                    int[] small = pf.argb(f.abs, GREETING_EDGE, dims);
                    TextRules.Words w2 = small == null ? null : pf.readText(f.abs, small, dims[2], dims[3]);
                    if (w2 != null) {
                        TextRules.Verdict v2 = TextRules.classify(w2, aspect.get(f)[0], true, false, true, now);
                        if (PhotoCategory.GREETING.equals(v2.kind)) { v = v2; words = w2; }
                    }
                } catch (Throwable ignored) {}
            }
            PhotoCategory.Result c = PhotoCategory.classify(f, s, f.code, v, f.faces);
            f.category = c.category; f.catDate = c.date; f.textPending = false;
            String t = TextRules.clean(words);
            f.text = PhotoCategory.ID.equals(c.category) || t.isEmpty() ? null : t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
        }
        } finally { if (ex != null) ex.shutdownNow(); }
        scenes(pf, pr, pics, opt);
        return mirage(pf, pr, pics, opt, now);
    }

    /** Decodes one picture and reads its text; null when it cannot be decoded or there is no reader. */
    private static TextRules.Words readFirst(Platform pf, FileItem f) {
        try {
            int[] d = new int[4];
            int[] px = pf.argb(f.abs, TEXT_EDGE, d);
            return px == null ? null : pf.readText(f.abs, px, d[2], d[3]);
        } catch (Throwable t) { return null; }
    }

    private static java.util.concurrent.ExecutorService textPool(final Scanner.Options opt) {
        return java.util.concurrent.Executors.newFixedThreadPool(opt.textThreads, new java.util.concurrent.ThreadFactory() {
            int n;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "text-" + (n++));
                if (opt.highPriority) t.setPriority(Thread.MAX_PRIORITY);
                return t;
            }
        });
    }

    /** Whether Mirage looks at this picture in a scan: every sorted picture (photos, screenshots, chats, downloads...). */
    static boolean mirageWorthy(FileItem f) { return f.category != null && f.mirageVersion != AlgorithmV3.VERSION; }

    /**
     * Pass 4: Mirage (AlgorithmV3) on each sorted picture, for the mirage_* tags and v3's category: screenshots, chat
     * images, downloads and other pictures first, then camera photos, newest first within each, within opt.mirageBudgetMs;
     * the rest is looked at in the next scan. One picture at a time (the engine is not shared between threads). First
     * gets Mirage ready (Platform.prepareMirage: the phone downloads the model the first time). Stops at once when Mirage
     * cannot run (no model and no connection, not 64-bit ARM): those tags then stay unknown, and the rules that need
     * Mirage hold back. Returns why Mirage did not look at the pictures, for the user; null when it ran or had nothing to do.
     */
    static String mirage(final Platform pf, Scanner.Progress pr, List<FileItem> pics, final Scanner.Options opt, long now) {
        if (opt.mirageBudgetMs <= 0) return null;
        List<FileItem> todo = new ArrayList<FileItem>();
        for (FileItem f : pics) if (mirageWorthy(f)) todo.add(f);
        if (todo.isEmpty()) return null;
        String notReady;
        try { notReady = pf.prepareMirage(pr); } catch (Throwable t) { notReady = "Mirage could not start: " + t; }
        if (notReady != null) return notReady;
        Collections.sort(todo, new Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) {
                boolean ca = PhotoCategory.isCamera(a), cb = PhotoCategory.isCamera(b);
                if (ca != cb) return ca ? 1 : -1;
                return Long.compare(b.mtime, a.mtime);
            }
        });
        long end = System.currentTimeMillis() + opt.mirageBudgetMs;
        boolean worked = false;
        // the engine takes one picture at a time, so with workers the next pictures are decoded ahead of it on other threads
        final java.util.concurrent.ExecutorService ex = opt.workers > 1 ? java.util.concurrent.Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
            int n;
            public Thread newThread(Runnable r) { Thread t = new Thread(r, "mirage-decode-" + (n++)); if (opt.highPriority) t.setPriority(Thread.MAX_PRIORITY); return t; }
        }) : null;
        final List<java.util.concurrent.Future<int[][]>> ahead = new ArrayList<java.util.concurrent.Future<int[][]>>();
        try {
        for (int k = 0; k < todo.size(); k++) {
            if (pr.cancelled() || System.currentTimeMillis() > end) return null;
            pr.update("Looking at pictures with Mirage", k + 1, todo.size());
            FileItem f = todo.get(k);
            int[] dims = new int[4];
            int[] px;
            if (ex != null) {
                while (ahead.size() < todo.size() && ahead.size() < k + 3) {
                    final FileItem g = todo.get(ahead.size());
                    ahead.add(ex.submit(new java.util.concurrent.Callable<int[][]>() {
                        public int[][] call() {
                            int[] d = new int[4];
                            try { int[] p = pf.argb(g.abs, TEXT_EDGE, d); return p == null ? null : new int[][]{p, d}; } catch (Throwable t) { return null; }
                        }
                    }));
                }
                int[][] got;
                try { got = ahead.get(k).get(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; } catch (Exception e) { got = null; }
                if (got == null) continue;
                px = got[0]; dims = got[1];
                ahead.set(k, null);
            } else {
                try { px = pf.argb(f.abs, TEXT_EDGE, dims); } catch (Throwable t) { px = null; }
            }
            if (px == null) continue;
            String json;
            try { json = pf.mirage(px, dims[2], dims[3], Mirage.OPTIONS); } catch (Throwable t) { json = null; }
            if (json == null) {                                       // not on this phone: stop; one picture failing: go on
                if (!worked) { String why = pf.mirageNote(); return why != null ? why : "Mirage could not run"; }
                continue;
            }
            worked = true;
            try {
                Mirage.Answer a = Mirage.read(json);
                ImageSignals.S s = f.signals;
                double aspect = f.width > 0 && f.height > 0 ? f.width / (double) f.height : dims[2] / (double) Math.max(1, dims[3]);
                AlgorithmV3.Result v3 = AlgorithmV3.sort(f, s, aspect, s != null && PhotoCategory.worthReading(f, s), a, now);
                AlgorithmV3.record(f, v3, a);
            } catch (RuntimeException ignored) {}
        }
        } finally { if (ex != null) ex.shutdownNow(); }
        return null;
    }

    private static void copyMirage(FileItem p, FileItem f) {
        f.mirageVersion = p.mirageVersion; f.mirageStyle = p.mirageStyle; f.miragePeople = p.miragePeople; f.mirageObjects = p.mirageObjects;
        f.mirageScene = p.mirageScene; f.mirageReference = p.mirageReference; f.mirageSees = p.mirageSees; f.v3Category = p.v3Category; f.v3Date = p.v3Date;
    }

    /** Whether the scene network looks at this picture: photos (not screenshots, papers, chats or graphics). */
    static boolean sceneWorthy(FileItem f) {
        return PhotoCategory.PHOTO.equals(f.category) || PhotoCategory.PEOPLE.equals(f.category) || PhotoCategory.ACCIDENTAL.equals(f.category);
    }

    /**
     * Pass 3: what each photo shows (SceneNet, Scene) and its aesthetic score (Aesthetic), newest first, within
     * opt.sceneBudgetMs; the rest is measured in the next scan. The network runs on several threads (one per core,
     * up to 4); pictures are decoded one at a time.
     */
    static void scenes(final Platform pf, final Scanner.Progress pr, List<FileItem> pics, Scanner.Options opt) {
        if (opt.sceneBudgetMs <= 0) return;
        final List<FileItem> todo = new ArrayList<FileItem>();
        for (FileItem f : pics) if (sceneWorthy(f) && f.sceneVersion != SCENE_VERSION) todo.add(f);
        if (todo.isEmpty()) return;
        final SceneNet net;
        try { net = pf.sceneNet(); } catch (Throwable t) { return; }
        if (net == null) return;
        Collections.sort(todo, new Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) { return Long.compare(b.mtime, a.mtime); }
        });
        final long end = System.currentTimeMillis() + opt.sceneBudgetMs;
        final int[] next = {0}, done = {0};
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        Thread[] ts = new Thread[threads];
        for (int k = 0; k < threads; k++) {
            ts[k] = new Thread(new Runnable() {
                public void run() {
                    int[] dims = new int[4];
                    while (true) {
                        FileItem f;
                        int[] px;
                        synchronized (next) {
                            if (pr.cancelled() || System.currentTimeMillis() > end || next[0] >= todo.size()) return;
                            f = todo.get(next[0]++);
                            try { px = pf.argb(f.abs, SCENE_EDGE, dims); } catch (Throwable t) { px = null; }
                        }
                        if (px != null) {
                            try { measureScene(net, f, px, dims[2], dims[3]); } catch (Throwable ignored) {}
                        }
                        synchronized (next) { done[0]++; pr.update("Looking at what photos show", done[0], todo.size()); }
                    }
                }
            }, "scene-" + k);
            if (opt.highPriority) ts[k].setPriority(Thread.MAX_PRIORITY);
            ts[k].start();
        }
        for (Thread t : ts) try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
    }

    /** Scene shares, sky share and aesthetic score of one picture (ARGB, w x h). */
    static void measureScene(SceneNet net, FileItem f, int[] px, int w, int h) {
        SceneNet.Out o = net.run(px, w, h);
        float[] g = Scene.shares(SceneNet.probabilities(o.logits));
        float[] sc = new float[g.length + 1];
        System.arraycopy(g, 0, sc, 0, g.length);
        double k = Math.min(1.0, 96.0 / Math.max(w, h));
        int sw = Math.max(1, (int) Math.round(w * k)), sh = Math.max(1, (int) Math.round(h * k));
        sc[g.length] = (float) Scene.sky(k >= 1 ? px : shrink(px, w, h, sw, sh), sw, sh);
        f.scene = sc;
        f.aesthetic = (float) Aesthetic.score(o.features);
        f.sceneVersion = SCENE_VERSION;
    }

    private static void copyOver(FileItem p, FileItem f) {
        f.category = p.category; f.code = p.code; f.catDate = p.catDate; f.text = p.text; f.faces = p.faces; f.signals = p.signals;
        f.sortVersion = p.sortVersion; f.textPending = p.textPending; f.eyeFaces = p.eyeFaces; f.eyesOpen = p.eyesOpen;
        f.smiling = p.smiling; f.looking = p.looking; f.faceSize = p.faceSize;
    }

    /** Faces, looked for on a small copy (People vs. other photos only needs to know there is a face). */
    static int faces(Platform pf, String path, int[] px, int w, int h) {
        FileItem f = new FileItem("", path, 0, 0);
        faces(pf, f, px, w, h, null);
        return f.faces;
    }

    /**
     * Sets f.faces from a small copy, and, when there are faces and luma (w x h) is given, f.eyeFaces and f.eyesOpen,
     * judged on the full-size pixels (the eye check needs more detail than finding the face does).
     */
    static void faces(Platform pf, FileItem item, int[] px, int w, int h, int[] luma) {
        double s = Math.min(1.0, FACE_EDGE / (double) Math.max(w, h));
        int sw = Math.max(1, (int) (w * s)), sh = Math.max(1, (int) (h * s));
        int[] small = s >= 1.0 ? px : shrink(px, w, h, sw, sh);
        float[] f;
        try { f = pf.faces(item.abs, small, sw, sh); } catch (Throwable t) { f = null; }
        item.faces = f == null ? -1 : Faces.count(f);
        if (item.faces <= 0 || luma == null) return;
        try {
            double kx = w / (double) sw, ky = h / (double) sh;
            float[] big = f.clone();
            for (int i = 0; i + Faces.F - 1 < big.length; i += Faces.F) {
                big[i] *= kx; big[i + 1] *= ky; big[i + 2] *= kx;
                for (int k = 4; k < Faces.F; k += 2) { big[i + k] *= kx; big[i + k + 1] *= ky; }
            }
            int[] e = PhotoQuality.eyes(luma, w, h, big);
            item.eyeFaces = e[0]; item.eyesOpen = e[1]; item.smiling = e[2]; item.looking = e[3];
            item.faceSize = e[0] > 0 ? e[4] / 10000f : -1;
        } catch (Throwable t) { item.eyeFaces = -1; item.eyesOpen = -1; item.smiling = -1; item.looking = -1; item.faceSize = -1; }
    }

    /** Box-filter down-scaling of ARGB pixels. */
    static int[] shrink(int[] px, int w, int h, int sw, int sh) {
        int[] o = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            int y0 = y * h / sh, y1 = Math.max(y0 + 1, (y + 1) * h / sh);
            for (int x = 0; x < sw; x++) {
                int x0 = x * w / sw, x1 = Math.max(x0 + 1, (x + 1) * w / sw);
                long rr = 0, gg = 0, bb = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                    int c = px[yy * w + xx]; rr += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255; n++;
                }
                o[y * sw + x] = 0xFF000000 | (int) (rr / n) << 16 | (int) (gg / n) << 8 | (int) (bb / n);
            }
        }
        return o;
    }

    static int[] luma(int[] px) {
        int[] l = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
        }
        return l;
    }
}
