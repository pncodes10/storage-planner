package com.storageplanner.v2test;

import com.storageplanner.v2.features.Extractors;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;

import java.util.ArrayList;
import java.util.List;

/** Phase 6 S1 (REVIEW_6 F-B1): content sub-records survive T1's fresh FeatureRecord on FULL scans when the file is unchanged. */
public final class ContentCarryOverTest {
    /** Stub content pass: fills TextInfo for every present file whose OCR extractor version is not current. */
    static final class StubContent implements Stage {
        int calls;
        public String id() { return "STUB_CONTENT"; }
        public StageOutcome run(StageContext ctx) throws StoreException {
            List<FeatureRecord> todo = new ArrayList<FeatureRecord>();
            for (FileRecord f : ctx.store.files().all()) {
                if (f.presence != Presence.PRESENT) continue;
                FeatureRecord fr = ctx.store.features().get(f.fileId);
                if (fr != null && fr.versions[Extractors.OCR] != Extractors.version(Extractors.OCR)) todo.add(fr.copy());
            }
            for (FeatureRecord fr : todo) {
                calls++;
                TextInfo t = new TextInfo(); t.status = TextStatus.READ; t.engine = "stub"; t.engineVersion = 1; t.words = 10 + calls;
                fr.text = t; fr.versions[Extractors.OCR] = Extractors.version(Extractors.OCR);
                DocInfo d = new DocInfo(); d.status = DocStatus.OK; d.pages = 2; fr.doc = d;
                ctx.store.features().put(fr);
            }
            return StageOutcome.DONE;
        }
    }

    public static int run(String[] args) throws Exception {
        T.section("ContentCarryOver");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            StubContent stub = new StubContent();
            fx.extraStages.add(stub);
            TempTreeFiles.make(fx.tree);
            fx.scan(ScanKind.FULL, false);
            T.eq("first run: 3 calls", 3, stub.calls);
            FeatureRecord a1 = fx.store.features().get(fx.f("a.txt").fileId);
            T.eq("text stored", TextStatus.READ, a1.text.status);
            int wordsA = a1.text.words, wordsB = fx.store.features().get(fx.f("b.txt").fileId).text.words;
            T.eq("OCR version stamped", Extractors.version(Extractors.OCR), a1.versions[Extractors.OCR]);

            fx.scan(ScanKind.FULL, false);
            T.eq("second FULL run: 0 new calls", 3, stub.calls);
            FeatureRecord a2 = fx.store.features().get(fx.f("a.txt").fileId);
            T.check("T1 really re-extracted (extractedScanId advanced)", a2.extractedScanId > a1.extractedScanId);
            T.eq("a.txt text equal", wordsA, a2.text.words); T.eq("a.txt status equal", TextStatus.READ, a2.text.status);
            T.eq("b.txt text equal", wordsB, fx.store.features().get(fx.f("b.txt").fileId).text.words);
            T.eq("a.txt doc kept", DocStatus.OK, a2.doc.status);
            T.eq("a.txt versions kept", Extractors.version(Extractors.OCR), a2.versions[Extractors.OCR]);

            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("INCREMENTAL run: still 3 calls", 3, stub.calls);

            // modify one file: new size + mtime -> its content facts are dropped, exactly one call
            fx.tree.file("b.txt", "changed content, longer".getBytes("UTF-8"));
            fx.tree.mtime("b.txt", fx.tree.mtime("b.txt") + 5000);
            fx.scan(ScanKind.FULL, false);
            T.eq("touched file: exactly 1 more call", 4, stub.calls);
            T.check("touched file got fresh text", fx.store.features().get(fx.f("b.txt").fileId).text.words != wordsB);
            T.eq("untouched file kept", wordsA, fx.store.features().get(fx.f("a.txt").fileId).text.words);
            T.eq("untouched file kept (c)", 3, countWith(fx, TextStatus.READ));

            fx.scan(ScanKind.FULL, false);
            T.eq("next FULL run: no calls", 4, stub.calls);

            // version bump of the content extractor makes every file selectable again
            Extractors.overrideVersionForTest(Extractors.OCR, 2);
            try { fx.scan(ScanKind.FULL, false); } finally { Extractors.resetVersionsForTest(); }
            T.eq("OCR version bump: all 3 again", 7, stub.calls);
        }
        return T.finish("ContentCarryOverTest");
    }

    static int countWith(Fx fx, TextStatus s) {
        int n = 0;
        for (FeatureRecord r : fx.store.features().all()) if (r.text != null && r.text.status == s) n++;
        return n;
    }

    static final class TempTreeFiles {
        static void make(TempTree t) throws Exception {
            t.file("a.txt", "alpha".getBytes("UTF-8")); t.file("b.txt", "beta".getBytes("UTF-8")); t.file("sub/c.txt", "gamma!".getBytes("UTF-8"));
        }
    }
}
