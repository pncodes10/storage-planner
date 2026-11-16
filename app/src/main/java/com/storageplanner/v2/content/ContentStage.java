package com.storageplanner.v2.content;

import com.storageplanner.v2.content.doc.OdfText;
import com.storageplanner.v2.content.doc.Ole2;
import com.storageplanner.v2.content.doc.OoxmlText;
import com.storageplanner.v2.content.doc.PdfText;
import com.storageplanner.v2.content.doc.PlainText;
import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.features.ExtractionLoop;
import com.storageplanner.v2.features.Extractors;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.risk.FileContext;
import com.storageplanner.v2.risk.UsageClassifier;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.stage.Throttle;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.util.FileIdList;
import com.storageplanner.v2.util.Io;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Stage {@code T4_TEXT} (PHASE_6 5.1): content passes after GRAPH, in the order DOC, ACTIVITY, VISUAL, OCR. S2 implements ACTIVITY only.
 * Content facts are stored in the FeatureRecord sub-records and stamped with the extractor version in the same commit, so a kill
 * re-selects exactly the unfinished files; a file the stage did not reach keeps NOT_RUN = unknown (PHASE_6 5.3).
 */
public final class ContentStage implements Stage {
    public static final String ID = "T4_TEXT";
    /** Session event: content passes are not finished; detail "ocr=N visual=M doc=D activity=A" (files still pending). */
    public static final String INCOMPLETE = "C_CONTENT_INCOMPLETE";
    /** Session counters (files handled by the pass in this session). */
    public static final String COUNT_ACTIVITY = "content.activity";
    public static final String COUNT_DOC = "content.doc";

    private final ContentProviders providers;
    private final ContentConfig cfg;
    /** Files left unprocessed by the last run (0 = none): {doc, activity, visual, ocr}. */
    public final long[] pending = new long[4];

    public ContentStage(ContentProviders p, ContentConfig cfg) {
        this.providers = p == null ? new ContentProviders(null, null) : p;
        this.cfg = cfg == null ? new ContentConfig() : cfg;
    }

    public String id() { return ID; }

    public StageOutcome run(StageContext ctx) throws StoreException {
        java.util.Arrays.fill(pending, 0);
        StageOutcome o = docPass(ctx);
        if (o == StageOutcome.DONE) o = activityPass(ctx);
        else pending[1] = activityJobs(ctx).size();             // a stopped DOC pass leaves the later passes unrun: count them
        noteIncomplete(ctx, pending);
        return o;
    }

    // ---- ACTIVITY pass (PHASE_6 8.1) ----

    /** Selection of §8.1: PRESENT mp4/mov/3gp with usage CAMERA_VIDEO, CHAT_MEDIA or DOWNLOAD whose ACTIVITY version is not current. */
    static boolean activityCandidate(FileRecord f) {
        String t = f.type;
        if (!("mp4".equals(t) || "mov".equals(t) || "3gp".equals(t))) return false;
        FileContext fc = new FileContext(); fc.f = f;
        UsageClass u = UsageClassifier.of(fc);
        return u == UsageClass.CAMERA_VIDEO || u == UsageClass.CHAT_MEDIA || u == UsageClass.DOWNLOAD;
    }

    private FileIdList activityJobs(StageContext ctx) throws StoreException {
        FileIdList jobs = new FileIdList();
        for (FileRecord f : ExtractionLoop.present(ctx)) {              // ascending fileId
            if (!activityCandidate(f)) continue;
            FeatureRecord fr = ctx.store.features().get(f.fileId);
            if (fr == null || fr.fp == null || !fr.fp.sameAs(f.fingerprint())) continue;
            if (fr.versions[Extractors.ACTIVITY] == Extractors.version(Extractors.ACTIVITY)) continue;
            jobs.add(f.fileId);
        }
        return jobs;
    }

    private StageOutcome activityPass(StageContext ctx) throws StoreException {
        FileIdList jobs = activityJobs(ctx);
        Clock clock = ctx.device.clock();
        long lastCommit = clock.monotonicNanos();
        int since = 0;
        for (int i = 0; i < jobs.size(); i++) {
            StageOutcome stop = stopReason(ctx);
            if (stop != null) {
                pending[1] = jobs.size() - i;
                commit(ctx);
                return stop;
            }
            FileRecord f = ctx.store.files().get(jobs.get(i));
            FeatureRecord old = f == null ? null : ctx.store.features().get(f.fileId);
            if (old != null) activity(ctx, f, old);
            since++;
            if (since >= cfg.commitEvery || (clock.monotonicNanos() - lastCommit) / 1000000L >= cfg.commitEveryMs) {
                commit(ctx);
                since = 0; lastCommit = clock.monotonicNanos();
                if (ctx.control != null) ctx.control.progress(ID, i + 1, jobs.size());
            } else if (ctx.control != null && (i & 63) == 0) ctx.control.progress(ID, i, jobs.size());
        }
        commit(ctx);
        return StageOutcome.DONE;
    }

    private void activity(StageContext ctx, FileRecord f, FeatureRecord old) throws StoreException {
        FeatureRecord fr = old.copy();
        ActivityInfo ai;
        ReadableFile rf = null;
        try {
            rf = new Io.CountingReadableFile(ctx.device.storage().open(f.volumeId, f.accessPath()));
            Mp4Tables.Tables t = Mp4Tables.read(rf, rf.size(), cfg.mp4MaxMoovBytes);
            ai = ActivityProfile.video(t);
        } catch (IOException e) {
            ai = failed(fr, "IOException");
        } catch (RuntimeException e) {
            ai = failed(fr, e.getClass().getSimpleName());
        } finally {
            if (rf != null) {
                if (ctx.budget != null) ctx.budget.chargeBytes(((Io.CountingReadableFile) rf).bytes);
                try { rf.close(); } catch (IOException ignored) { }
            }
        }
        fr.activity = ai;
        fr.versions[Extractors.ACTIVITY] = Extractors.version(Extractors.ACTIVITY);
        ctx.session.count(COUNT_ACTIVITY, 1);
        ctx.store.features().put(fr);
    }

    /** A file the pass could not read at all: status CORRUPT (unknown), problem content:ACTIVITY:<Class> (ActivityStatus has no FAILED). */
    private static ActivityInfo failed(FeatureRecord fr, String cls) {
        fr.problem("content:ACTIVITY:" + cls);
        ActivityInfo a = new ActivityInfo(); a.videoStatus = ActivityStatus.CORRUPT;
        return a;
    }

    // ---- DOC pass (PHASE_6 7.1; S3a: PDF only) ----

    /**
     * Selection of §7.1: PRESENT, size >= 1 and a document format. The detected type names are those of TypeDetector
     * (pdf, ooxml-docx/xlsx/pptx, odf with extension odt/ods/odp, ole2, text with extension txt or csv, csv); returns the DocInfo format or null.
     */
    static String docFormat(FileRecord f) {
        if (f.sizeBytes < 1 || f.type == null) return null;
        String t = f.type, ext = f.extension == null ? "" : f.extension.toLowerCase(Locale.ROOT);
        if (t.equals("pdf")) return "pdf";
        if (t.equals("ooxml-docx")) return "docx";
        if (t.equals("ooxml-xlsx")) return "xlsx";
        if (t.equals("ooxml-pptx")) return "pptx";
        if (t.equals("odf")) return ext.equals("odt") || ext.equals("ods") || ext.equals("odp") ? ext : null;
        if (t.equals("ole2")) return "ole2";
        if (t.equals("text")) return ext.equals("txt") ? "txt" : ext.equals("csv") ? "csv" : null;      // a .csv the detector did not recognise as csv is still csv text
        if (t.equals("csv")) return "csv";
        return null;
    }

    static boolean docCandidate(FileRecord f) { return docFormat(f) != null; }

    private FileIdList docJobs(StageContext ctx) throws StoreException {
        FileIdList jobs = new FileIdList();
        for (FileRecord f : ExtractionLoop.present(ctx)) {              // ascending fileId
            if (!docCandidate(f)) continue;
            FeatureRecord fr = ctx.store.features().get(f.fileId);
            if (fr == null || fr.fp == null || !fr.fp.sameAs(f.fingerprint())) continue;
            if (fr.versions[Extractors.DOC_TEXT] == Extractors.version(Extractors.DOC_TEXT)) continue;
            jobs.add(f.fileId);
        }
        return jobs;
    }

    private StageOutcome docPass(StageContext ctx) throws StoreException {
        FileIdList jobs = docJobs(ctx);
        Clock clock = ctx.device.clock();
        long lastCommit = clock.monotonicNanos();
        int since = 0;
        for (int i = 0; i < jobs.size(); i++) {
            StageOutcome stop = stopReason(ctx);
            if (stop != null) {
                pending[0] = jobs.size() - i;
                commit(ctx);
                return stop;
            }
            FileRecord f = ctx.store.files().get(jobs.get(i));
            FeatureRecord old = f == null ? null : ctx.store.features().get(f.fileId);
            if (old != null) doc(ctx, f, old);
            since++;
            if (since >= cfg.commitEvery || (clock.monotonicNanos() - lastCommit) / 1000000L >= cfg.commitEveryMs) {
                commit(ctx);
                since = 0; lastCommit = clock.monotonicNanos();
                if (ctx.control != null) ctx.control.progress(ID, i + 1, jobs.size());
            } else if (ctx.control != null && (i & 63) == 0) ctx.control.progress(ID, i, jobs.size());
        }
        commit(ctx);
        return StageOutcome.DONE;
    }

    /**
     * One document. The extracted text lives only in this method (PdfText.Result) and is reduced to counts; nothing else is kept
     * (PHASE_6 4.1: no raw text, title or author is stored).
     */
    private void doc(StageContext ctx, FileRecord f, FeatureRecord old) throws StoreException {
        FeatureRecord fr = old.copy();
        DocInfo di = new DocInfo();
        String fmt = docFormat(f);
        di.format = fmt;
        ReadableFile rf = null;
        try {
            rf = new Io.CountingReadableFile(ctx.device.storage().open(f.volumeId, f.accessPath()));
            long size = rf.size();
            boolean whole = "pdf".equals(fmt) || "txt".equals(fmt) || "csv".equals(fmt);
            if (whole && (size > cfg.docMaxBytes || size > (1 << 30))) di.status = DocStatus.TOO_LARGE;
            else if (size < 1) di.status = DocStatus.CORRUPT;
            else {
                PdfText.Result pr = null;
                OoxmlText.Result or = null;
                if ("pdf".equals(fmt)) {
                    byte[] b = Io.readAt(rf, 0, (int) size);
                    if (b.length != size) throw new IOException("short read");
                    pr = PdfText.extract(b, fr.pdf);
                } else if ("docx".equals(fmt) || "xlsx".equals(fmt) || "pptx".equals(fmt)) {
                    or = OoxmlText.extract(rf, size, fmt);
                } else if (fmt.startsWith("od")) {
                    or = OdfText.extract(rf, size, fmt);
                } else if ("ole2".equals(fmt)) {
                    int n = (int) Math.min(size, Ole2.MAX_BYTES);
                    byte[] b = Io.readAt(rf, 0, n);
                    or = Ole2.check(b, b.length);
                } else {                                                  // txt, csv
                    int n = (int) Math.min(size, PlainText.MAX_BYTES);
                    byte[] b = Io.readAt(rf, 0, n);
                    or = PlainText.decode(b, b.length, size > n, fmt);
                }
                // the extracted text (and title, author) goes out of scope here: only the counts below are kept
                if (pr != null) {
                    di.status = pr.status; di.pages = pr.pages;
                    if (pr.status != DocStatus.ENCRYPTED) di.unsupportedStreams = pr.unsupported;
                    if (pr.status == DocStatus.OK) { di.chars = pr.text.length(); di.words = words(pr.text); }
                } else {
                    di.status = or.status; di.pages = or.pages; di.createdAt = or.createdAt; di.modifiedAt = or.modifiedAt;
                    if (or.status == DocStatus.OK) { di.chars = or.text.length(); di.words = words(or.text); }
                }
            }
        } catch (IOException e) {
            di.status = DocStatus.CORRUPT;
        } catch (RuntimeException e) {
            di.status = DocStatus.CORRUPT;
            fr.problem("content:DOC:" + e.getClass().getSimpleName());
        } finally {
            if (rf != null) {
                if (ctx.budget != null) ctx.budget.chargeBytes(((Io.CountingReadableFile) rf).bytes);
                try { rf.close(); } catch (IOException ignored) { }
            }
        }
        fr.doc = di;
        fr.versions[Extractors.DOC_TEXT] = Extractors.version(Extractors.DOC_TEXT);
        ctx.session.count(COUNT_DOC, 1);
        ctx.store.features().put(fr);
    }

    /** Number of whitespace-separated words. */
    static int words(CharSequence t) {
        int n = 0; boolean in = false;
        for (int i = 0; i < t.length(); i++) {
            boolean ws = Character.isWhitespace(t.charAt(i));
            if (!ws && !in) n++;
            in = !ws;
        }
        return n;
    }

    // ---- shared ----

    private static void commit(StageContext ctx) throws StoreException {
        ctx.store.scanSessions().put(ctx.session);
        ctx.store.commit();
    }

    /** Cancel, pause, throttle, budget: the order and effects of ExtractionLoop (checked before every file). */
    static StageOutcome stopReason(StageContext ctx) {
        if (ctx.control != null && ctx.control.cancelRequested()) return StageOutcome.CANCELLED;
        if (ctx.control != null && ctx.control.pauseRequested()) return StageOutcome.PAUSED;
        if (ctx.throttle != null && ctx.throttle.shouldPause()) { ctx.session.pauseReason = Throttle.PAUSE_REASON; return StageOutcome.PAUSED; }
        if (ctx.budget != null && ctx.budget.exhausted(ctx.device.clock())) {
            ctx.session.pauseReason = "PL_BUDGET";
            ctx.session.counters.put("budget.stoppedBy." + ctx.budget.stoppedBy(ctx.device.clock()), 1L);
            return StageOutcome.BUDGET_EXHAUSTED;
        }
        return null;
    }

    /** One {@link #INCOMPLETE} event per session, replaced by each run of this stage; none when nothing is pending. */
    static void noteIncomplete(StageContext ctx, long[] p) {
        if (ctx.session == null) return;
        List<ScanEvent> ev = ctx.session.events;
        for (int i = ev.size() - 1; i >= 0; i--) if (INCOMPLETE.equals(ev.get(i).code)) ev.remove(i);
        if (p[0] + p[1] + p[2] + p[3] > 0) {
            long now = ctx.device == null ? Unknown.TIME : ctx.device.clock().nowMillis();
            ctx.session.event(now, INCOMPLETE, null, null, "ocr=" + p[3] + " visual=" + p[2] + " doc=" + p[0] + " activity=" + p[1]);
        }
    }
}
