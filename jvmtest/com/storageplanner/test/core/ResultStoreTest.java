package com.storageplanner.test.core;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.ResultStore;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;
import com.storageplanner.test.JvmPlatform;
import com.storageplanner.tools.quality.PhotoQuality;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Round-trip of the saved results file on the virtual phone: every field of every FileItem / AppItem /
 * Estimates / Scanner.Result survives, the rebuilt plan is identical, and damaged or foreign files are
 * rejected cleanly. Fields are compared by reflection, so a field added later without being stored fails here.
 * usage: ResultStoreTest <vp2/device>
 */
public final class ResultStoreTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; if (fails <= 20) System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        JvmPlatform pf = new JvmPlatform(args[0]);
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() {
            public void update(String stage, int done, int total) {}
            public boolean cancelled() { return false; }
        });
        Estimates est = new Estimates();
        est.cameraPhoto = 0.3712345678901; est.photoSource = "measured on 12 of your photos"; est.chatSource = null;
        est.screenshotLossless = 0.27; est.screenshotSource = "measured on 5 screenshots ✓ é";
        r.unreadableDirs = 3;

        ResultStore.Saved s = new ResultStore.Saved();
        s.result = r; s.est = est; s.targetUsed = (long) (r.total * 70.0 / 128.0); s.appStats = true; s.sdk = 34; s.mode = 1;

        byte[] bytes = save(s);
        long t0 = System.nanoTime();
        ResultStore.Saved l = ResultStore.read(new ByteArrayInputStream(bytes));
        double ms = (System.nanoTime() - t0) / 1e6;

        // ---- settings + estimates + result scalars
        check("targetUsed", l.targetUsed == s.targetUsed);
        check("appStats", l.appStats == s.appStats);
        check("sdk", l.sdk == s.sdk);
        check("mode", l.mode == s.mode);
        int estFields = compareFields("Estimates", s.est, l.est);
        int resFields = compareFields("Result", r, l.result);   // scalars; lists compared below

        // ---- every file and app, every field
        check("file count", r.files.size() == l.result.files.size());
        check("app count", r.apps.size() == l.result.apps.size());
        int fileFields = 0, appFields = 0;
        for (int i = 0; i < Math.min(r.files.size(), l.result.files.size()); i++)
            fileFields += compareFields("file " + r.files.get(i).rel, r.files.get(i), l.result.files.get(i));
        for (int i = 0; i < Math.min(r.apps.size(), l.result.apps.size()); i++)
            appFields += compareFields("app " + r.apps.get(i).pkg, r.apps.get(i), l.result.apps.get(i));

        // ---- non-default values are really exercised (otherwise the test proves little)
        int sha = 0, ph = 0, vid = 0, apk = 0, aud = 0, dims = 0, pk = 0;
        for (FileItem f : r.files) {
            if (f.sha256 != null) sha++; if (f.hasPhash) ph++; if (f.vcodec != null) vid++;
            if (f.apkPackage != null) apk++; if (f.vcodec == null && f.bitrate > 0) aud++; if (f.width > 0) dims++; if (f.pkg != null) pk++;
        }
        check("fixture has hashes, phashes, videos, dims", sha > 0 && ph > 0 && vid > 0 && dims > 0);

        // ---- rebuilt units + plans are identical
        UnitBuilder a = new UnitBuilder(r, s.est, s.sdk), b = new UnitBuilder(l.result, l.est, l.sdk);
        check("unit count", a.units.size() == b.units.size());
        for (int i = 0; i < Math.min(a.units.size(), b.units.size()); i++) {
            Unit x = a.units.get(i), y = b.units.get(i);
            boolean same = x.id.equals(y.id) && x.options.size() == y.options.size();
            for (int k = 0; same && k < x.options.size(); k++) {
                Option o = x.options.get(k), p = y.options.get(k);
                same = o.technique.equals(p.technique) && o.saved == p.saved && o.risk == p.risk && o.exact == p.exact && o.why.equals(p.why);
            }
            check("unit " + x.id, same);
        }
        check("detections", a.det.duplicateOf.equals(b.det.duplicateOf) && a.det.burstExtraOf.equals(b.det.burstExtraOf)
                && a.det.sentCopyOf.equals(b.det.sentCopyOf) && a.det.blurry.equals(b.det.blurry));
        long used = r.total - r.free;
        Planner.Plan pa = Planner.tiered(a.units, used - s.targetUsed), pb = Planner.tiered(b.units, used - l.targetUsed);
        check("plan saved", pa.saved() == pb.saved());
        check("plan tier", pa.tier == pb.tier);
        check("plan confirmations", pa.confirmations() == pb.confirmations());
        check("plan reaches", pa.reachesTarget == pb.reachesTarget);
        check("plan choices", ids(pa).equals(ids(pb)));
        Planner.Plan sa = Planner.bestWithin(a.units, Option.R2), sb = Planner.bestWithin(b.units, Option.R2);
        check("safe plan", sa.saved() == sb.saved() && ids(sa).equals(ids(sb)));

        // ---- photo quality of near-identical shots is saved (format 2) and restored exactly
        int withQ = 0, withFaces = 0;
        for (FileItem f : r.files) if (f.quality != null) { withQ++; if (f.quality.faces > 0) withFaces++; }
        check("fixture has measured near-identical shots", withQ > 0);
        System.out.printf("  photo quality saved for %d near-identical shots (%d with faces)%n", withQ, withFaces);

        // ---- a file saved by app 1.2-1.4 (format 1) still loads; it simply has no photo quality
        ByteArrayOutputStream b1 = new ByteArrayOutputStream();
        ResultStore.write(b1, s, 1);
        ResultStore.Saved old = ResultStore.read(new ByteArrayInputStream(b1.toByteArray()));
        boolean oldOk = old.result.files.size() == r.files.size();
        for (int i = 0; oldOk && i < r.files.size(); i++) {
            FileItem x = r.files.get(i), y = old.result.files.get(i);
            oldOk = y.quality == null && x.rel.equals(y.rel) && x.size == y.size && x.phash == y.phash && x.sharp == y.sharp;
        }
        check("format 1 file loads (no quality)", oldOk);
        check("format 1 rebuilds units", new UnitBuilder(old.result, old.est, old.sdk).units.size() == a.units.size());

        // ---- determinism: saving the loaded copy gives the same bytes
        check("re-save identical bytes", Arrays.equals(bytes, save(l)));

        System.out.printf("round trip: %d files x %d fields, %d apps x %d fields, %d estimate fields, %d result fields; %d bytes (%.1f per file), load %.0f ms%n",
                r.files.size(), r.files.isEmpty() ? 0 : fileFields / r.files.size(), r.apps.size(), r.apps.isEmpty() ? 0 : appFields / r.apps.size(),
                estFields, resFields, bytes.length, bytes.length / (double) Math.max(1, r.files.size()), ms);
        System.out.printf("  exercised: %d sha256, %d phash, %d video, %d audio, %d apk, %d with dims, %d with owner pkg%n", sha, ph, vid, aud, apk, dims, pk);
        System.out.printf("  plan original vs loaded: saved %d / %d bytes, tier R%d / R%d, confirmations %d / %d, %d units%n",
                pa.saved(), pb.saved(), pa.tier, pb.tier, pa.confirmations(), pb.confirmations(), a.units.size());

        // ---- damaged / foreign input is rejected cleanly
        int truncs = 0;
        Random rnd = new Random(42);
        List<Integer> cuts = new ArrayList<Integer>();
        for (int n = 0; n < 64 && n < bytes.length; n++) cuts.add(n);
        for (int n = 0; n < 300; n++) cuts.add(rnd.nextInt(bytes.length));
        for (int n = 1; n <= 16; n++) cuts.add(bytes.length - n);
        for (int cut : cuts) { rejected("truncated at " + cut, Arrays.copyOf(bytes, cut)); truncs++; }
        int flips = 0;
        for (int n = 0; n < 300; n++) {
            byte[] c = bytes.clone();
            int pos = n < 8 ? n : rnd.nextInt(c.length);
            c[pos] ^= (byte) (1 << rnd.nextInt(8));
            rejected("bit flip at " + pos, c); flips++;
        }
        byte[] v = bytes.clone(); v[7]++;
        rejected("wrong version", v);
        byte[] v0 = bytes.clone(); v0[4] = 0; v0[5] = 0; v0[6] = 0; v0[7] = 0;
        rejected("version 0", v0);
        byte[] g = new byte[4096]; rnd.nextBytes(g);
        rejected("random garbage", g);
        rejected("empty", new byte[0]);
        byte[] extra = Arrays.copyOf(bytes, bytes.length + 8);   // trailing bytes are ignored, still valid
        check("trailing bytes tolerated", ResultStore.tryRead(new ByteArrayInputStream(extra)) != null);
        System.out.printf("  rejected cleanly: %d truncations, %d bit flips, wrong version, version 0, garbage, empty%n", truncs, flips);

        System.out.println((checks - fails) + "/" + checks + " result store checks pass");
        if (fails > 0) System.exit(1);
    }

    static byte[] save(ResultStore.Saved s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ResultStore.write(bo, s);
        return bo.toByteArray();
    }

    static List<String> ids(Planner.Plan p) {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) l.add(e.getKey().id + "=" + e.getValue().technique + "/" + e.getValue().saved);
        return l;
    }

    /** read must throw IOException (never anything else), tryRead must return null. */
    static void rejected(String what, byte[] data) {
        boolean ok;
        try { ResultStore.read(new ByteArrayInputStream(data)); ok = false; }
        catch (IOException e) { ok = true; }
        catch (Throwable t) { ok = false; System.out.println("  unexpected " + t + " for " + what); }
        check(what + " -> IOException", ok);
        check(what + " -> tryRead null", ResultStore.tryRead(new ByteArrayInputStream(data)) == null);
    }

    /** Compares every non-static, non-collection field; returns how many were compared. */
    static int compareFields(String what, Object x, Object y) throws IllegalAccessException {
        int n = 0;
        for (Field f : x.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || java.util.Collection.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object u = f.get(x), w = f.get(y);
            if (u instanceof com.storageplanner.tools.quality.PhotoQuality.Q || w instanceof com.storageplanner.tools.quality.PhotoQuality.Q) {
                check(what + "." + f.getName() + " presence", (u == null) == (w == null));
                if (u != null && w != null) n += compareFields(what + "." + f.getName(), u, w);
                else n++;
                continue;
            }
            if (u instanceof float[] && w instanceof float[]) { check(what + "." + f.getName(), Arrays.equals((float[]) u, (float[]) w)); n++; continue; }
            check(what + "." + f.getName() + " " + u + " vs " + w, Objects.equals(u, w));
            n++;
        }
        return n;
    }
}
