package com.storageplanner.algorithms;

import com.storageplanner.algorithms.v1_rules.RulesV1;
import com.storageplanner.algorithms.v2_futureneed.FutureNeed;

/** Every deletion algorithm version, oldest first, and the one the app uses. */
public final class Registry {
    private Registry() {}

    public static final DeletionAlgorithm[] ALL = {new RulesV1(), new FutureNeed()};

    /** The version the app uses. */
    public static DeletionAlgorithm current() { return byId("v2_futureneed"); }

    public static DeletionAlgorithm byId(String id) {
        for (DeletionAlgorithm a : ALL) if (a.id().equals(id)) return a;
        throw new IllegalArgumentException("no deletion algorithm " + id);
    }
}
