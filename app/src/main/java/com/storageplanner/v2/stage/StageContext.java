package com.storageplanner.v2.stage;

import com.storageplanner.v2.device.ScanControl;
import com.storageplanner.v2.device.V2Device;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.store.V2Store;

public final class StageContext {
    public V2Store store; public V2Device device; public PipelineConfig config;
    public ScanSession session; public ScanControl control; public Budget budget; public Throttle throttle;
}
