package com.storageplanner.v2.sample;

import com.storageplanner.v2.model.ChangeState;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stage SAMPLE, run right after DISCOVER in a sample run (the "Quick v2 test" button). Keeps only the selected files in the
 * store and drops the other file records, so extraction, graph, assessment and plan behave as if only the sample existed.
 *
 * It must only ever run on a store that belongs to the sample run (its own blob location, emptied before the run): the dropped
 * records are removed outright, not marked REMOVED, so no "removed" bookkeeping exists for them and no later stage can see them.
 * Nothing on the phone's storage is touched (the app is read-only); only the sample store's own records change.
 */
public final class SampleStage implements Stage {
    public static final String ID = "SAMPLE";
    private final SampleSpec spec;

    public SampleStage(SampleSpec spec) { this.spec = spec == null ? SampleSpec.DEFAULT : spec; }

    public String id() { return ID; }

    /** Inserts this stage right after DISCOVER (before every extraction stage). */
    public static void insertAfterDiscovery(List<Stage> stages, SampleSpec spec) {
        int at = 0;
        for (int i = 0; i < stages.size(); i++) if ("DISCOVER".equals(stages.get(i).id())) { at = i + 1; break; }
        stages.add(at, new SampleStage(spec));
    }

    public StageOutcome run(StageContext ctx) throws StoreException {
        int[] r = prune(ctx.store, spec);
        ScanSession s = ctx.session;
        s.counters.put("sampleImages", (long) r[0]);
        s.counters.put("sampleVideos", (long) r[1]);
        s.counters.put("sampleDropped", (long) r[2]);
        // discovery counted every file it found as added; only the sample is left
        long added = 0;
        for (FileRecord f : ctx.store.files().all()) if (f.change == ChangeState.ADDED && f.changeScanId == s.scanId) added++;
        s.counters.put("added", added);
        if (!s.stagesDone.contains(ID)) s.stagesDone.add(ID);
        ctx.store.scanSessions().put(s);
        ctx.store.flush();
        return StageOutcome.DONE;
    }

    /**
     * Removes every file record that is not in the sample. Idempotent, and safe to call several times while discovery is still
     * walking (a record dropped now is not among the newest N of the files seen so far, so it cannot be among the newest N of
     * all). Returns {selected images, selected videos, records dropped}.
     */
    public static int[] prune(V2Store store, SampleSpec spec) throws StoreException {
        SampleSelector sel = new SampleSelector(spec);
        for (FileRecord f : store.files().all()) sel.offer(f);
        Set<String> keep = sel.selected();
        List<String> drop = new ArrayList<String>();
        for (String k : store.files().keys()) if (!keep.contains(k)) drop.add(k);
        for (String k : drop) store.files().remove(k);
        return new int[]{sel.selectedImages(), sel.selectedVideos(), drop.size()};
    }
}
