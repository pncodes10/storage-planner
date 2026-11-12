package com.storageplanner.v2.pipeline;

import com.storageplanner.v2.model.CoverageReport;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.stage.StageOutcome;

public final class PipelineResult {
    public ScanSession session;
    public CoverageReport coverage;
    /** DONE when every stage completed, else the outcome that stopped the run. */
    public StageOutcome outcome;
}
