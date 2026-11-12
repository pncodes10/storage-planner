package com.storageplanner.v2.features;

import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;

public final class T3ImageStage implements Stage {
    public static final String ID = "T3_IMAGE";
    public String id() { return ID; }
    public StageOutcome run(StageContext ctx) { return StageOutcome.DONE; }
}
