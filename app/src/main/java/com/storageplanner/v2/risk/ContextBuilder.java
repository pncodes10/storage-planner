package com.storageplanner.v2.risk;

import com.storageplanner.v2.content.Classification;
import com.storageplanner.v2.content.ContentClassifier;
import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.device.InstalledApps;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/** Builds one FileContext per file (stages 1, 3, 4). Stale features/hashes are treated as absent. */
public final class ContextBuilder {
    final V2Store store; final GraphView g; final Availability avail; final Set<String> installed; final InstalledApps apps; final long now;
    final RiskConfig cfg;

    public ContextBuilder(V2Store store, InstalledApps apps, long now, RiskConfig cfg) {
        this.store = store; this.apps = apps; this.now = now; this.cfg = cfg;
        this.g = GraphView.of(store);
        this.avail = apps == null ? Availability.UNAVAILABLE : apps.availability();
        Set<String> s = null;
        if (avail == Availability.FULL) { s = new TreeSet<String>(); for (InstalledApp x : apps.list()) s.add(x.packageName); }
        installed = s;
        for (FeatureRecord fr : store.features().all()) {
            if (fr.apk == null || fr.fp == null) continue;            // only APK features load their file (PHASE_S 4 item 4)
            FileRecord f = g.file(fr.fileId);
            if (f != null && fr.fp.sameAs(f.fingerprint())) g.apkStates.put(f.fileId, fr.apk.installState);
        }
    }
    public GraphView graph() { return g; }

    public FileContext build(FileRecord f) {
        FileContext c = new FileContext();
        c.f = f; c.g = g; c.now = now; c.appsAvailability = avail; c.installedPackages = installed;
        FeatureRecord fr = store.features().get(f.fileId);
        c.feat = fr != null && fr.fp != null && fr.fp.sameAs(f.fingerprint()) ? fr : null;
        HashRecord hr = store.hashes().get(f.fileId);
        c.hash = hr != null && hr.fp != null && hr.fp.sameAs(f.fingerprint()) ? hr : null;
        if (f.ownerPackage != null && apps != null) c.ownerApp = apps.get(f.ownerPackage);
        return finish(c, cfg);
    }
    /** Shared by tests that construct contexts directly. */
    public static FileContext finish(FileContext c, RiskConfig cfg) {
        FileRecord f = c.f;
        if (Unknown.knownTime(f.createdAt)) { c.ageMs = c.now - f.createdAt; c.ageSource = "CREATED"; }
        else if (Unknown.knownTime(f.modifiedAt)) { c.ageMs = c.now - f.modifiedAt; c.ageSource = "MTIME"; }
        c.mtimeAgeMs = Unknown.knownTime(f.modifiedAt) ? c.now - f.modifiedAt : Unknown.LONG;
        c.usage = UsageClassifier.of(c);
        RecoverabilityEstimator.roles(c);
        c.classification = null;
        if (cfg != null) for (ContentClassifier k : cfg.classifiers) { Classification x = k.classify(c); if (x != null) { c.classification = x; break; } }
        SignalExtractor.extract(c);
        return c;
    }
}
