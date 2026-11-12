package com.storageplanner.v2test;

import com.storageplanner.v2.device.DirEntry;
import com.storageplanner.v2.device.EntryKind;
import com.storageplanner.v2.device.FileStat;
import com.storageplanner.v2.device.IoStatus;
import com.storageplanner.v2.discovery.FileIds;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.PipelineResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WalkerTest {
    static boolean hasInacc(CoverageReport c, String path, InaccessReason r) {
        for (InaccessibleLocation l : c.inaccessible) if (l.path.equals(path) && l.reason == r) return true;
        return false;
    }

    public static int run(String[] args) throws Exception {
        T.section("1 basic tree");
        try (Fx fx = Fx.create()) {
            fx.tree.file("a.txt", 10, 1); fx.tree.file("d1/b.bin", 2000, 2); fx.tree.file("d1/d2/c.bin", 33, 3);
            fx.scan();
            T.eq("every file once", 3, fx.files().size());
            T.eq("all present", 3, fx.count(Presence.PRESENT));
            boolean attrs = true, ids = true;
            for (String p : new String[]{"a.txt", "d1/b.bin", "d1/d2/c.bin"}) {
                FileRecord r = fx.f(p);
                Path real = fx.tree.path(p);
                attrs &= r != null && r.sizeBytes == Files.size(real) && r.modifiedAt == Files.getLastModifiedTime(real, LinkOption.NOFOLLOW_LINKS).toMillis();
                ids &= r != null && r.fileId.equals(FileIds.of("primary", p, "ino:" + Fx.inode(real)));
            }
            T.check("sizes and mtimes equal Files values", attrs);
            T.check("ids = FileIds.of(..., ino:<inode>)", ids);
            List<String> order1 = new ArrayList<String>(); for (FileRecord r : fx.store.files().all()) order1.add(r.fileId);
            List<String> sorted = new ArrayList<String>(order1); java.util.Collections.sort(sorted);
            T.eq("files.all() in key order", sorted, order1);
            String dump1 = Fx.dumpFilesDirs(fx.store);
            Fx fx2 = new Fx(fx.tree, Tri.TRUE);
            fx2.scan();
            T.eq("deterministic", dump1, Fx.dumpFilesDirs(fx2.store));
        }

        T.section("2 zero-byte");
        try (Fx fx = Fx.create()) {
            fx.tree.file("z.txt", new byte[0]);
            fx.scan();
            T.eq("size 0", 0L, fx.f("z.txt").sizeBytes);
            T.eq("present", Presence.PRESENT, fx.f("z.txt").presence);
        }

        T.section("3 unicode");
        try (Fx fx = Fx.create()) {
            String nfd = "été.txt".replace("é", "é");
            fx.tree.file("u/" + nfd, 5, 1);
            fx.tree.file("u/😀 smile.jpg", 5, 2);
            fx.tree.file("u/हिन्दी.txt", 5, 3);
            StringBuilder b = new StringBuilder(); for (int i = 0; i < 127; i++) b.append('é');
            String longName = b.toString();
            fx.tree.file("u/" + longName, 5, 4);
            fx.scan();
            String nfc = java.text.Normalizer.normalize(nfd, java.text.Normalizer.Form.NFC);
            FileRecord r = fx.f("u/" + nfc);
            T.check("NFD file found under NFC path", r != null);
            T.eq("rawPath NFD", "u/" + nfd, r == null ? null : r.rawPath);
            FileStat st = fx.sa.stat("primary", r == null ? "" : r.rawPath);
            T.eq("stat(rawPath) OK", IoStatus.OK, st.status);
            T.check("emoji name", fx.f("u/😀 smile.jpg") != null);
            T.check("Devanagari name", fx.f("u/हिन्दी.txt") != null);
            T.check("254-byte name", fx.f("u/" + longName) != null && longName.getBytes(StandardCharsets.UTF_8).length == 254);
            T.eq("4 files", 4, fx.files().size());
        }

        T.section("4 path too long");
        try (Fx fx = Fx.create()) {
            fx.tree.file("aaaaaaaaaaaaaaaaaaaa/bbbbbbbbbbbbbbbbbbbb/cccccccccccccccccccccccc/dddd/f.txt", 5, 1);
            fx.tree.file("aaaaaaaaaaaaaaaaaaaa/ok.txt", 5, 2);
            fx.lim.maxPathChars = 64;
            PipelineResult res = fx.scan();
            DirRecord d = fx.d("aaaaaaaaaaaaaaaaaaaa/bbbbbbbbbbbbbbbbbbbb/cccccccccccccccccccccccc");
            T.eq("PATH_TOO_LONG dir record", DirStatus.PATH_TOO_LONG, d == null ? null : d.status);
            T.eq("counter", 1L, res.session.counter("pathTooLong"));
            T.check("short file found", fx.f("aaaaaaaaaaaaaaaaaaaa/ok.txt") != null);
            T.check("coverage lists it", hasInacc(res.coverage, "aaaaaaaaaaaaaaaaaaaa/bbbbbbbbbbbbbbbbbbbb/cccccccccccccccccccccccc", InaccessReason.PATH_TOO_LONG));
        }

        T.section("5 symlinks");
        try (Fx fx = Fx.create()) {
            TempTree outside = TempTree.create();
            try {
                outside.file("secret/s.txt", 5, 9);
                fx.tree.file("a/f1.txt", 5, 1); fx.tree.file("a/b/f2.txt", 5, 2); fx.tree.file("top.txt", 5, 3);
                fx.tree.symlink("a/loop", "..");
                fx.tree.symlink("out", outside.path("secret").toString());
                fx.tree.symlink("a/flink", "f1.txt");
                fx.scan();
                T.eq("default: loop SYMLINK_SKIPPED", DirStatus.SYMLINK_SKIPPED, fx.d("a/loop").status);
                T.eq("default: each real file once", 3, fx.files().size());
                T.eq("link to file not followed", DirStatus.SYMLINK_SKIPPED, fx.d("a/flink").status);
                T.eq("symlinksSkipped", 3L, fx.session(1).counter("symlinksSkipped"));
                Fx f2 = new Fx(fx.tree, Tri.TRUE);
                f2.lim.followSymlinks = true;
                PipelineResult res = f2.scan();
                T.eq("follow: completes", ScanState.COMPLETED, res.session.state);
                T.eq("follow: LOOP recorded", DirStatus.LOOP, f2.d("a/loop").status);
                T.eq("follow: each real file once", 3, f2.files().size());
                T.eq("follow: outside → OUTSIDE_ROOT", DirStatus.OUTSIDE_ROOT, f2.d("out").status);
                boolean outsideListed = false;
                for (String p : f2.faulty.listedPaths()) if (p.startsWith("out")) outsideListed = true;
                T.check("nothing outside listed", !outsideListed);
                T.check("coverage OUTSIDE_ROOT", hasInacc(res.coverage, "out", InaccessReason.OUTSIDE_ROOT));
            } finally { outside.close(); }
        }

        T.section("6 bind-mount alias");
        try (Fx fx = Fx.create()) {
            fx.tree.file("x/a.jpg", 5, 1); fx.tree.file("y/b.jpg", 5, 2);
            fx.scan();
            String xid = fx.posix.dirIdentity("primary", "x");
            fx.faulty.aliasIdentity("y", xid);
            PipelineResult r = fx.scan();
            T.eq("alias → LOOP", DirStatus.LOOP, fx.d("y").status);
            T.eq("alias records UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("y/b.jpg").presence);
            T.eq("reason D_LOOP", "D_LOOP", fx.f("y/b.jpg").presenceReason);
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
            T.check("D_LOOP event", Fx.hasEvent(r.session, "D_LOOP"));
            T.check("x still present", fx.f("x/a.jpg").presence == Presence.PRESENT);
        }

        T.section("6b hard link");
        try (Fx fx = Fx.create()) {
            Path h1 = fx.tree.file("h/one.jpg", 50, 1);
            Files.createLink(fx.tree.path("h/two.jpg"), h1);
            fx.scan();
            FileRecord a = fx.f("h/one.jpg"), b = fx.f("h/two.jpg");
            T.check("two records", a != null && b != null && !a.fileId.equals(b.fileId));
            T.check("equal physicalKey", a != null && b != null && a.physicalKey != null && a.physicalKey.equals(b.physicalKey));
        }

        T.section("7 unreadable dir");
        try (Fx fx = Fx.create()) {
            fx.tree.file("d1/a.txt", 5, 1); fx.tree.file("d1/sub/b.txt", 5, 2); fx.tree.file("ok.txt", 5, 3);
            fx.scan();
            fx.faulty.denyList("d1");
            PipelineResult r = fx.scan();
            T.eq("PERMISSION_DENIED", DirStatus.PERMISSION_DENIED, fx.d("d1").status);
            T.check("coverage lists it", hasInacc(r.coverage, "d1", InaccessReason.PERMISSION_DENIED));
            T.eq("known files UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("d1/sub/b.txt").presence);
            T.eq("reason", "D_PARENT_UNREADABLE", fx.f("d1/a.txt").presenceReason);
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
        }

        T.section("8 vanishing file");
        try (Fx fx = Fx.create()) {
            fx.tree.file("v.txt", 5, 1); fx.tree.file("w.txt", 5, 2);
            fx.faulty.vanishOnStat("v.txt");
            PipelineResult r = fx.scan();
            T.check("not recorded on first scan", fx.f("v.txt") == null);
            T.eq("vanishedDuringScan", 1L, r.session.counter("vanishedDuringScan"));
            fx.rebuild();
            fx.scan();
            T.eq("known now", Presence.PRESENT, fx.f("v.txt").presence);
            fx.faulty.vanishOnStat("v.txt");
            r = fx.scan();
            T.eq("REMOVED when known", Presence.REMOVED, fx.f("v.txt").presence);
            T.eq("reason D_VANISHED", "D_VANISHED", fx.f("v.txt").presenceReason);
            T.eq("counter", 1L, r.session.counter("vanishedDuringScan"));
        }

        T.section("9 depth limit");
        try (Fx fx = Fx.create()) {
            fx.tree.file("l1/f1.txt", 5, 1); fx.tree.file("l1/l2/f2.txt", 5, 2); fx.tree.file("l1/l2/l3/f3.txt", 5, 3);
            fx.tree.file("l1/l2/l3/l4/f4.txt", 5, 4); fx.tree.file("l1/l2/l3/l4/l5/f5.txt", 5, 5);
            fx.scan();
            fx.lim.maxDepth = 3;
            PipelineResult r = fx.scan();
            T.eq("DEPTH_LIMIT", DirStatus.DEPTH_LIMIT, fx.d("l1/l2/l3/l4").status);
            T.eq("deeper known UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("l1/l2/l3/l4/l5/f5.txt").presence);
            T.eq("depth-4 file UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("l1/l2/l3/l4/f4.txt").presence);
            T.eq("depth-3 dir files present", Presence.PRESENT, fx.f("l1/l2/l3/f3.txt").presence);
            T.check("coverage DEPTH_LIMIT", hasInacc(r.coverage, "l1/l2/l3/l4", InaccessReason.DEPTH_LIMIT));
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
        }

        T.section("10 entries per dir");
        try (Fx fx = Fx.create()) {
            for (int i = 0; i < 25; i++) fx.tree.file(String.format("many/f%02d.txt", i), 5, i);
            fx.lim.maxEntriesPerDir = 10;
            fx.scan();
            T.eq("10 records", 10, fx.files().size());
            T.eq("entriesSkipped", 15, fx.d("many").entriesSkipped);
            T.eq("TRUNCATED_ENTRY_LIMIT", DirStatus.TRUNCATED_ENTRY_LIMIT, fx.d("many").status);
        }
        try (Fx fx = Fx.create()) {
            for (int i = 0; i < 25; i++) fx.tree.file(String.format("many/f%02d.txt", i), 5, i);
            fx.scan();
            fx.lim.maxEntriesPerDir = 10;
            fx.scan();
            boolean untouched = true;
            for (int i = 10; i < 25; i++) { FileRecord r = fx.f(String.format("many/f%02d.txt", i)); untouched &= r.presence == Presence.PRESENT && r.changeScanId == 1; }
            T.check("known unprocessed files untouched", untouched);
        }

        T.section("11 special file");
        try (Fx fx = Fx.create()) {
            fx.tree.file("s/a.txt", 5, 1);
            DirEntry fifo = new DirEntry(); fifo.name = "pipe"; fifo.kind = EntryKind.OTHER;
            fx.faulty.injectEntry("s", fifo);
            PipelineResult r = fx.scan();
            T.eq("specialFilesSkipped", 1L, r.session.counter("specialFilesSkipped"));
            T.eq("only the real file", 1, fx.files().size());
        }

        T.section("12 partial listing");
        try (Fx fx = Fx.create()) {
            for (int i = 0; i < 6; i++) fx.tree.file("p/f" + i + ".txt", 5, i);
            fx.scan();
            fx.faulty.partialList("p", 3);
            PipelineResult r = fx.scan();
            T.eq("PARTIAL_IO_ERROR", DirStatus.PARTIAL_IO_ERROR, fx.d("p").status);
            T.eq("missing → UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("p/f5.txt").presence);
            T.eq("reason", "D_PARTIAL_LISTING", fx.f("p/f5.txt").presenceReason);
            T.eq("listed stay present", Presence.PRESENT, fx.f("p/f0.txt").presence);
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
            T.check("coverage PARTIAL_READ_ERROR", hasInacc(r.coverage, "p", InaccessReason.PARTIAL_READ_ERROR));
        }

        T.section("13 restricted");
        try (Fx fx = Fx.create()) {
            fx.tree.file("Android/data/com.x/files/a.bin", 5, 1); fx.tree.file("Android/media/b.jpg", 5, 2);
            PipelineResult r = fx.scan();
            T.eq("RESTRICTED", DirStatus.RESTRICTED, fx.d("Android/data").status);
            T.check("not listed", !fx.faulty.listedPaths().contains("Android/data"));
            T.check("nothing recorded under it", fx.f("Android/data/com.x/files/a.bin") == null);
            T.check("coverage OS_RESTRICTED", hasInacc(r.coverage, "Android/data", InaccessReason.OS_RESTRICTED));
            T.check("coverage lists obb too", hasInacc(r.coverage, "Android/obb", InaccessReason.OS_RESTRICTED));
        }

        T.section("14 excluded");
        try (Fx fx = Fx.create()) {
            fx.tree.file("ex/a.txt", 5, 1); fx.tree.file("ex/b/c.txt", 5, 2); fx.tree.file("keep.txt", 5, 3); fx.tree.file("manifest.json", 5, 4);
            fx.lim.excluded.add("ex"); fx.lim.excluded.add("manifest.json");
            PipelineResult r = fx.scan();
            T.eq("EXCLUDED", DirStatus.EXCLUDED, fx.d("ex").status);
            T.eq("nothing below recorded", 1, fx.files().size());
            T.eq("excludedFiles", 1L, r.session.counter("excludedFiles"));
            T.check("coverage EXCLUDED_BY_USER", hasInacc(r.coverage, "ex", InaccessReason.EXCLUDED_BY_USER));
        }

        T.section("15 undecodable name");
        try (Fx fx = Fx.create()) {
            fx.tree.file("n/ok.txt", 5, 1);
            DirEntry bad = new DirEntry(); bad.name = "bad�name.jpg"; bad.kind = EntryKind.UNKNOWN;
            fx.faulty.injectEntry("n", bad);
            PipelineResult r = fx.scan();
            T.eq("undecodableNames", 1L, r.session.counter("undecodableNames"));
            T.check("coverage UNDECODABLE_NAMES", hasInacc(r.coverage, "n", InaccessReason.UNDECODABLE_NAMES));
            T.eq("vanishedDuringScan not counted", 0L, r.session.counter("vanishedDuringScan"));
        }

        T.section("16 NFC/NFD twins");
        try (Fx fx = Fx.create()) {
            String nfc = "café.txt", nfd = "café.txt";
            fx.tree.file("t/" + nfc, 5, 1); fx.tree.file("t/" + nfd, 6, 2);
            PipelineResult r = fx.scan();
            T.eq("two records", 2, fx.files().size());
            T.check("NFC record", fx.f("t/" + nfc) != null && fx.f("t/" + nfc).sizeBytes == 5);
            T.check("second keeps raw path", fx.f("t/" + nfd) != null && fx.f("t/" + nfd).sizeBytes == 6);
            T.check("D_NAME_COLLISION", Fx.hasEvent(r.session, "D_NAME_COLLISION"));
            fx.scan();
            T.eq("rescan stable", 0, fx.changed(2, ChangeState.ADDED) + fx.changed(2, ChangeState.REMOVED));
        }

        T.section("17 total limit");
        try (Fx fx = Fx.create()) {
            for (int i = 0; i < 10; i++) { fx.tree.file("a/f" + i, 5, i); fx.tree.file("b/f" + i, 5, i); fx.tree.file("c/f" + i, 5, i); }
            fx.lim.maxTotalEntries = 20;
            PipelineResult r = fx.scan();
            int total = 0;
            for (DirRecord d : fx.store.directories().all()) if (d.status == DirStatus.TOTAL_LIMIT) total++;
            T.check("TOTAL_LIMIT recorded", total > 0);
            T.check("complete == false", !r.coverage.complete);
            T.eq("scan still completes", ScanState.COMPLETED, r.session.state);
            T.check("lastFullScanId not set", fx.store.volumes().get("primary").lastFullScanId < 0);
        }

        T.section("18 listing NOT_FOUND (F-B2)");
        try (Fx fx = Fx.create()) {
            fx.tree.file("a/x.txt", 5, 1); fx.tree.file("b.txt", 5, 2);
            fx.scan();
            fx.faulty.listNotFound("");
            fx.scan();
            T.eq("root NOT_FOUND: zero REMOVED", 0, fx.count(Presence.REMOVED));
            T.eq("all UNVERIFIABLE/VOLUME_ABSENT", 2, fx.count(Presence.UNVERIFIABLE) + fx.count(Presence.VOLUME_ABSENT));
        }
        try (Fx fx = Fx.create()) {
            fx.tree.file("a/x.txt", 5, 1); fx.tree.file("a/y.txt", 5, 1); fx.tree.file("b.txt", 5, 2);
            fx.scan();
            fx.faulty.listNotFound("a");
            fx.scan();
            T.eq("subdir NOT_FOUND: UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("a/x.txt").presence);
            T.eq("zero REMOVED", 0, fx.count(Presence.REMOVED));
            fx.tree.delete("a");
            fx.rebuild();
            fx.scan();
            T.eq("next scan, parent lacks a → REMOVED", Presence.REMOVED, fx.f("a/x.txt").presence);
            T.eq("reason D_DIR_REMOVED", "D_DIR_REMOVED", fx.f("a/y.txt").presenceReason);
        }

        T.section("19 stat failure (F-M1)");
        try (Fx fx = Fx.create()) {
            fx.tree.file("s/k.txt", 5, 1);
            fx.scan();
            String id = fx.f("s/k.txt").fileId;
            fx.faulty.statFails("s/k.txt");
            PipelineResult r = fx.scan();
            FileRecord k = fx.f("s/k.txt");
            T.eq("UNVERIFIABLE", Presence.UNVERIFIABLE, k.presence);
            T.eq("D_STAT_FAILED", "D_STAT_FAILED", k.presenceReason);
            T.eq("same fileId", id, k.fileId);
            T.eq("nothing REPLACED", 0, fx.changed(2, ChangeState.REPLACED));
            T.eq("one record", 1, fx.files().size());
            T.eq("statFailed counter", 1L, r.session.counter("statFailed"));
            fx.scan();
            T.eq("still failing: still one record", 1, fx.files().size());
            fx.rebuild();
            fx.scan();
            T.eq("recovers to PRESENT", Presence.PRESENT, fx.f("s/k.txt").presence);
            T.eq("same id after recovery", id, fx.f("s/k.txt").fileId);
        }

        T.section("20 dir <-> file replacement");
        try (Fx fx = Fx.create()) {
            fx.tree.file("x/in1.txt", 5, 1); fx.tree.file("x/sub/in2.txt", 5, 2); fx.tree.file("y", 7, 3);
            fx.scan();
            fx.tree.delete("x"); fx.tree.file("x", 9, 4);
            fx.tree.delete("y"); fx.tree.file("y/new.txt", 3, 5);
            fx.scan();
            T.eq("old subtree REMOVED", Presence.REMOVED, fx.f("x/sub/in2.txt").presence);
            T.eq("reason D_DIR_REMOVED", "D_DIR_REMOVED", fx.f("x/in1.txt").presenceReason);
            T.eq("dir record NOT_FOUND", DirStatus.NOT_FOUND, fx.d("x").status);
            T.eq("file x ADDED", ChangeState.ADDED, fx.f("x").change);
            T.eq("file x present", Presence.PRESENT, fx.f("x").presence);
            T.check("old file y REMOVED", fx.store.files().byPath("primary", "y").presence == Presence.REMOVED);
            T.eq("y/new.txt ADDED", ChangeState.ADDED, fx.f("y/new.txt").change);
        }
        return T.finish("WalkerTest");
    }
}
