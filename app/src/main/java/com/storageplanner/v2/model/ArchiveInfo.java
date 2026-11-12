package com.storageplanner.v2.model;

import java.util.List;

/** Archive listing (PHASE_2 2, 7). entries are stored only for plain archives (subtype == null; REVIEW_1 F-M12). */
public final class ArchiveInfo extends Sub {
    public String format, subtype, listingStatus, problem;
    public long declaredEntries = -1, totalUncompressed = -1, totalCompressed = -1;
    public int listedEntries, nestedArchives = -1, nestedListed;
    public double maxRatio = Double.NaN;
    public Tri bombSuspect = Tri.UNKNOWN, pathTraversal = Tri.UNKNOWN, absolutePaths = Tri.UNKNOWN, encryptedEntries = Tri.UNKNOWN,
            nameEncodingUncertain = Tri.UNKNOWN;
    public List<ArchiveEntry> entries;
}
