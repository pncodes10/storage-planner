package com.storageplanner.v2.stage;

import com.storageplanner.v2.store.StoreException;

public interface Stage {
    String id();
    /** Runs until done, budget exhausted, pause or cancel. Idempotent and resumable from store state alone. */
    StageOutcome run(StageContext ctx) throws StoreException;
}
