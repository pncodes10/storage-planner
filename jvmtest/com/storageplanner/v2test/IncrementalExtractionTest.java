package com.storageplanner.v2test;

import com.storageplanner.v2.features.Extractors;
import com.storageplanner.v2.features.T1MetadataStage;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Budget;
import com.storageplanner.v2.stage.StageOutcome;

/** Incremental re-extraction, version bumps, unreadable files, change during read, budgets (PHASE_2 3.1, 8, 9). */
public final class IncrementalExtractionTest {
    public static int run(String[] args) throws Exception {
        T.section("IncrementalExtraction");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 3;
            TempTree t = fx.tree;
            t.file("DCIM/a.jpg", FxImg.jpeg(FxImg.pattern(100, 80, 1), 0.8f));
            t.file("Docs/p.pdf", "%PDF-1.4\ntrailer << >>\n%%EOF\n".getBytes("ISO-8859-1"));
            t.file("Docs/q.pdf", "%PDF-1.4\ntrailer << /Root 1 0 R >>\n%%EOF\n".getBytes("ISO-8859-1"));
            t.file("x.txt", "hello".getBytes("UTF-8"));
            fx.scan();
            long p0 = T1MetadataStage.probesRun;
            fx.faulty.bytesRead.clear();
            com.storageplanner.v2.pipeline.PipelineResult r = fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("second run: 0 probes", 0L, T1MetadataStage.probesRun - p0);
            T.eq("second run: 0 bytes", 0L, fx.faulty.totalBytesRead());
            T.eq("extractedThisScan 0", 0L, ((java.util.Map<?, ?>) r.coverage.extraction).get("extractedThisScan"));
            t.file("x.txt", "hello world".getBytes("UTF-8"));
            p0 = T1MetadataStage.probesRun;
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("modified file: 1 re-extracted", 1L, T1MetadataStage.probesRun - p0);
            Extractors.overrideVersionForTest(Extractors.PDF, 2);
            p0 = T1MetadataStage.probesRun;
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("PDF version bump: only the 2 PDFs", 2L, T1MetadataStage.probesRun - p0);
            Extractors.resetVersionsForTest();
            p0 = T1MetadataStage.probesRun;
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("version reset: PDFs again", 2L, T1MetadataStage.probesRun - p0);
            p0 = T1MetadataStage.probesRun;
            fx.scan(ScanKind.FULL, false);
            T.eq("FULL: all re-run", 4L, T1MetadataStage.probesRun - p0);
            String id = fx.f("x.txt").fileId;
            t.delete("x.txt");
            fx.scan(ScanKind.INCREMENTAL, false);
            T.check("deleted: FeatureRecord kept", fx.store.features().get(id) != null);

            t.file("Docs/locked.pdf", "%PDF-1.4\n%%EOF\n".getBytes("ISO-8859-1"));
            fx.faulty.denyOpen("Docs/locked.pdf");
            fx.scan(ScanKind.INCREMENTAL, false);
            FileRecord u = fx.f("Docs/locked.pdf");
            T.eq("denied → UNREADABLE", ExtractionStatus.UNREADABLE, u.extractionStatus);
            T.eq("confidence 0.1", 0.1, u.extractionConfidence); T.eq("type by extension", TypeSource.EXTENSION, u.typeSource);
            T.eq("readable FALSE", Tri.FALSE, u.readable);

            t.file("grow.txt", "aaaa".getBytes("UTF-8"));
            final TempTree tt = t;
            fx.faulty.mutateAfterOpen("grow.txt", new Runnable() { public void run() {
                try { tt.append("grow.txt", "bbbbbbbb".getBytes("UTF-8")); tt.mtime("grow.txt", tt.mtime("grow.txt") + 3000); } catch (Exception e) { throw new RuntimeException(e); }
            } });
            fx.scan(ScanKind.INCREMENTAL, false);
            FileRecord g = fx.f("grow.txt");
            T.check("changed during read → T1 pending", (g.tiersPending & 2) != 0);
            fx.scan(ScanKind.INCREMENTAL, false);
            T.check("next run extracts it", (fx.f("grow.txt").tiersDone & 2) != 0);
        }
        try (Fx fx = Fx.create()) {
            fx.maxTier = 3;
            for (int i = 0; i < 5; i++) fx.tree.file("DCIM/i" + i + ".png", FxImg.encode(FxImg.pattern(80, 60, i), "png"));
            Budget b = new Budget(); b.maxDecodes = 2; fx.budget = b;
            com.storageplanner.v2.pipeline.PipelineResult r = fx.scan();
            T.eq("decode budget → BUDGET_EXHAUSTED", StageOutcome.BUDGET_EXHAUSTED, r.outcome);
            int pend = 0; for (FileRecord f : fx.files()) if ((f.tiersPending & 8) != 0) pend++;
            T.eq("3 images pending tier 3", 3, pend);
            T.eq("stoppedBy DECODES", "DECODES", ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) r.coverage.extraction).get("budget")).get("stoppedBy"));
            fx.budget = new Budget();
            r = fx.scan(ScanKind.FULL, true);
            int ph = 0; for (FileRecord f : fx.files()) if (f.hasPhash) ph++;
            T.eq("resume: all 5 decoded", 5, ph);
        }
        return T.finish("IncrementalExtractionTest");
    }
}
