package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.StageContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** T2 Part A: ZIP listing, subtype refinement, APK features, archive integrity (PHASE_2 3.4). TAR is deferred to Phase 6 (A11). */
final class Archives {
    private Archives() {}

    static void structure(StageContext ctx, FileRecord f, FeatureRecord fr, ReadableFile rf, Limits lim) throws IOException {
        if (f.type.equals("tar")) {
            ArchiveInfo a = new ArchiveInfo(); a.format = "tar"; a.listingStatus = "UNSUPPORTED"; a.problem = "tar:DEFERRED_PHASE6";
            fr.archive = a;
            return;
        }
        long size = f.sizeBytes;
        ZipProbe.Listing l = ZipProbe.list(rf, size, lim);
        ArchiveInfo a = l.info;
        String mimetype = null;
        if (!l.entries.isEmpty() && l.entries.get(0).path.equals("mimetype") && l.entries.get(0).method == 0 && l.entries.get(0).size <= 200) {
            byte[] d = ZipProbe.read(rf, size, l.entries.get(0), 200, 1);
            if (d != null) mimetype = new String(d, StandardCharsets.US_ASCII).trim();
        }
        a.subtype = ArchiveSubtype.of(l.entries, mimetype, null);
        String newType = a.subtype == null ? "zip" : a.subtype;
        if (!newType.equals(f.type)) {
            f.type = newType;
            f.mimeType = MimeMap.of(newType);
            f.extMismatch = ExtensionMap.mismatch(f.extension, newType, f.typeSource);
        }
        a.entries = a.subtype == null ? l.entries : null;   // REVIEW_1 F-M12: stored only for plain archives
        if (a.problem != null) fr.problem(a.problem);
        fr.archive = a;
        // integrity (PHASE_2 3.3)
        if ("OK".equals(a.listingStatus) || "TRUNCATED".equals(a.listingStatus) && l.cdOffset >= 0) {
            boolean allEnc = true, anyFile = false;
            for (ArchiveEntry e : l.entries) if (e.depth == 0 && !e.dir) { anyFile = true; if (e.encrypted != Tri.TRUE) allEnc = false; }
            f.integrity = anyFile && allEnc ? Integrity.ENCRYPTED : "OK".equals(a.listingStatus) ? Integrity.OK : Integrity.UNKNOWN;
        } else if ("TRUNCATED".equals(a.listingStatus)) f.integrity = Integrity.TRUNCATED;
        else if ("CORRUPT".equals(a.listingStatus)) f.integrity = Integrity.CORRUPT;
        if (newType.equals("apk") || newType.equals("apks") || newType.equals("xapk")) {
            fr.apk = ApkProbe.probe(rf, size, l, newType, ctx.device.apps(), ctx.device.storage().localPath(f.volumeId, f.accessPath()), fr, lim);
        } else fr.apk = null;
    }
}
