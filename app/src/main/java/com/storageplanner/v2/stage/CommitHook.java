package com.storageplanner.v2.stage;

import com.storageplanner.v2.store.StoreException;

/** Called by a stage at each consistency point (DiscoveryEngine: put(session); store.commit(); test hooks). */
public interface CommitHook { void commit() throws StoreException; }
