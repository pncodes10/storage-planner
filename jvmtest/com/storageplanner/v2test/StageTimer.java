package com.storageplanner.v2test;

import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wraps a stage and records its wall time (V2Cli timings.stages). */
final class StageTimer implements Stage {
    static final Map<String, Long> ms = new LinkedHashMap<String, Long>();
    private final Stage s;
    private StageTimer(Stage s) { this.s = s; }
    static Stage wrap(Stage s) { return new StageTimer(s); }
    public String id() { return s.id(); }
    public StageOutcome run(StageContext ctx) throws StoreException {
        long t0 = System.nanoTime();
        try { return s.run(ctx); }
        finally { Long v = ms.get(s.id()); ms.put(s.id(), (v == null ? 0 : v) + (System.nanoTime() - t0) / 1000000L); }
    }
}
