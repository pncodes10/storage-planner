package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;

/** What the Assessor concluded about one file, apart from its options. Explains why something is protected. */
public final class Assessment {
    /** Value of keeping the file, 0..1 (Policy.KEEP_DEFAULT when nothing is known). */
    public double retention = Policy.KEEP_DEFAULT;
    /** Identity, financial, legal or medical record: only an identical extra copy may ever be suggested. */
    public boolean sensitive;
    /** Another file is only safe to remove because this one stays (it is the kept copy of a group). */
    public int keeperOf;
    /** Other files belong to this one (RAW + JPEG, edit sidecar, subtitle, photo + its video). */
    public boolean hasCompanion;
    /** What was found, in words ("camera photo", "faces", "kept copy of 3 files"). */
    public final List<String> labels = new ArrayList<String>();
    /** Suggestions that were withdrawn, with the reason ("Old payment screenshots: sensitive record"). */
    public final List<String> withdrawn = new ArrayList<String>();

    /** Debug only (Policy.trace): every proposal for this file and what became of it, step by step. */
    public final List<String> trace = new ArrayList<String>();

    /** True when the file must not be suggested for anything but removing an identical extra copy. */
    public boolean isProtected() { return sensitive; }
}
