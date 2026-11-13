package com.storageplanner.v2.features;

import com.storageplanner.v2.device.DecodedImage;
import com.storageplanner.v2.device.MediaDecoder;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.util.FileIdList;

import java.util.*;

/** Tier 3: decode + ImageBasics for every image, in area priority order, under the budget (PHASE_2 3.5). */
public final class T3ImageStage implements Stage {
    public static final String ID = "T3_IMAGE";
    static final List<String> AREAS = Arrays.asList("camera", "screenshot", "pictures", "wa_sent", "wa_image", "download", "document",
            "telegram_media", "app_media", "user_other");
    private final Limits lim;
    public T3ImageStage() { this(Limits.DEFAULT); }
    public T3ImageStage(Limits lim) { this.lim = lim; }
    public String id() { return ID; }

    private static final class Sel {
        final int rank; final String path, id;
        Sel(int rank, String path, String id) { this.rank = rank; this.path = path; this.id = id; }
    }

    static int rank(String area) { int i = area == null ? -1 : AREAS.indexOf(area); return i < 0 ? AREAS.size() : i; }

    public StageOutcome run(final StageContext ctx) throws StoreException {
        final long scanId = ctx.session.scanId;
        final boolean full = ctx.session.kind == ScanKind.FULL;
        // Only (rank, path, id) of the selected images are held while sorting; the loop gets just the ids (PHASE_S 4 item 1).
        List<Sel> sel = new ArrayList<Sel>();
        for (FileRecord f : ExtractionLoop.present(ctx)) {
            if (!FileType.isImage(f.type) || (f.tiersDone & Tiers.T1) == 0) continue;
            FeatureRecord fr = ctx.store.features().get(f.fileId);
            if (fr == null || fr.fp == null || !fr.fp.sameAs(f.fingerprint())) continue;
            boolean cur = fr.versions[Extractors.IMAGE_BASICS] == Extractors.version(Extractors.IMAGE_BASICS)
                    && !(full && !Long.toString(scanId).equals(fr.extra.get("t3scan")));
            if (!cur) sel.add(new Sel(rank(f.area), f.path, f.fileId));
        }
        Collections.sort(sel, new Comparator<Sel>() {   // stable: ties keep ascending fileId, as before
            public int compare(Sel a, Sel b) {
                int c = Integer.compare(a.rank, b.rank);
                return c != 0 ? c : a.path.compareTo(b.path);
            }
        });
        FileIdList jobs = new FileIdList();
        for (Sel s : sel) jobs.add(s.id);
        sel = null;
        return ExtractionLoop.run(ctx, ID, jobs, Tiers.T3, new ExtractionLoop.Job() {
            public void run(FileRecord f) throws StoreException { image(ctx, f, scanId); }
        });
    }

    void image(StageContext ctx, FileRecord job, long scanId) throws StoreException {
        FileRecord f = ctx.store.files().get(job.fileId);
        FeatureRecord fr = ctx.store.features().get(f.fileId);
        MediaDecoder media = ctx.device.media();
        ImageInfo img = fr.image == null ? new ImageInfo() : fr.image.<ImageInfo>copy();
        if (img.format == null) img.format = f.type;
        clearPixels(img);
        f.hasPhash = false; f.phash = 0;
        DecodeStatus st;
        ctx.session.count("T3.extracted", 1);
        if (media == null || !media.decodableTypes().contains(f.type)) st = DecodeStatus.NO_DECODER;
        else if (img.width > 0 && img.height > 0 && (long) img.width * img.height > lim.maxPixels) st = DecodeStatus.TOO_LARGE;
        else if ((img.width <= 0 || img.height <= 0) && f.sizeBytes > lim.undecodedMaxBytes) st = DecodeStatus.TOO_LARGE;
        else {
            long t0 = ctx.device.clock().monotonicNanos();
            DecodedImage d = media.decode(f.volumeId, f.accessPath(), lim.decodeLongEdge, lim.maxPixels, true);
            if (ctx.budget != null) ctx.budget.chargeDecode();
            ctx.session.count("T3.decodeMs", (ctx.device.clock().monotonicNanos() - t0) / 1000000L);
            st = d == null || d.status == null ? DecodeStatus.CORRUPT : d.status;
            if (st == DecodeStatus.OK && (d.w <= 0 || d.h <= 0 || (d.luma == null && d.argb == null))) st = DecodeStatus.CORRUPT;
            if (st == DecodeStatus.OK) {
                try {
                    f.phash = ImageBasics.measure(d, img); f.hasPhash = true;
                } catch (RuntimeException e) {
                    clearPixels(img); st = DecodeStatus.CORRUPT; fr.problem("image:EXCEPTION:" + e.getClass().getSimpleName());
                }
            }
        }
        img.decodeStatus = st;
        if (f.type.equals("jpeg") && f.integrity == Integrity.UNKNOWN && fr.problems.contains("jpeg:NO_EOI_IN_TAIL") && st == DecodeStatus.CORRUPT)
            f.integrity = Integrity.TRUNCATED;
        fr.image = img;
        fr.versions[Extractors.IMAGE_BASICS] = Extractors.version(Extractors.IMAGE_BASICS);
        fr.extra.put("t3scan", Long.toString(scanId));
        if (st == DecodeStatus.OK) Tiers.done(f, Tiers.T3); else Tiers.failed(f, Tiers.T3);
        Confidence.apply(f, fr);
        ctx.store.features().put(fr);
        ctx.store.files().put(f);
    }

    static void clearPixels(ImageInfo i) {
        i.analysedWidth = -1; i.analysedHeight = -1;
        i.sharpness = i.brightness = i.contrast = i.clipLow = i.clipHigh = i.exposure = i.colorfulness = i.saturation = Double.NaN;
        i.lumaHist16 = null;
    }
}
