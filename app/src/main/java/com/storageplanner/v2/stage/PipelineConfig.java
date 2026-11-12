package com.storageplanner.v2.stage;

import com.storageplanner.v2.model.ScanKind;
import java.util.Set;

/**
 * Pipeline options (ARCHITECTURE 5.1). Phase 1 subset: walk limits are passed to DiscoveryEngine directly
 * (WalkLimits lives in v2.discovery, which depends on this package).
 */
public final class PipelineConfig {
    public ScanKind kind = ScanKind.FULL;
    public boolean resume;
    public int maxTier = 0;
    public int workers = 0;
    public Budget budget = new Budget();
    /** null = all volumes. */
    public Set<String> volumeIds;
    /** The caller had to discard an unreadable store: the new session gets event D_STORE_RESET. */
    public boolean storeReset;
}
