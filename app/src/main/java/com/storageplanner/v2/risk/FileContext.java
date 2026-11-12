package com.storageplanner.v2.risk;

import com.storageplanner.v2.content.Classification;
import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.model.*;

import java.util.*;

/** PHASE_4 1.1: everything known about one file for one assessment; built once by ContextBuilder, in memory only. */
public final class FileContext {
    public FileRecord f; public FeatureRecord feat; public HashRecord hash;
    public InstalledApp ownerApp; public Availability appsAvailability = Availability.UNAVAILABLE;
    /** Installed package names when availability == FULL, else null. */
    public Set<String> installedPackages;
    public GraphView g;
    public UsageClass usage;
    public boolean nameContentMismatch;        // F-05: temp name over complete content
    public long now;
    public long ageMs = com.storageplanner.v2.model.Unknown.LONG; public String ageSource;
    public long mtimeAgeMs = com.storageplanner.v2.model.Unknown.LONG;
    public List<Signal> signals = new ArrayList<Signal>();
    public Classification classification;
    // group roles (stage 3), filled by RecoverabilityEstimator.roles
    public DuplicateGroup copyGroup;          // the group in which this file is a redundant (non-keeper) member, if any
    public String copyKeeper;                 // that group's keeper
    public Relationship copyEdge;             // current edge between this file and copyKeeper (null if none)
    public List<DuplicateGroup> keeperOf = new ArrayList<DuplicateGroup>();
    public List<DuplicateGroup> memberOf = new ArrayList<DuplicateGroup>();
    public String perceptualStatus() { return feat == null ? null : feat.extra.get("graph.perceptual"); }
    public ImageInfo image() { return feat == null ? null : feat.image; }
    public ApkInfo apk() { return feat == null ? null : feat.apk; }
    public ArchiveInfo archive() { return feat == null ? null : feat.archive; }
    public String lowerPath() { return f.path == null ? "" : f.path.toLowerCase(Locale.ROOT); }
}
