package com.storageplanner.v2.store;

/**
 * The device's free space fell below the store's reserve (PHASE_S_STORE 3.12, {@code D_LOW_STORAGE}). Unchecked like
 * {@link StoreCorruption}, because it is thrown from {@code put}; it is not corruption, so it never triggers a reset. After
 * it the store accepts only a final allowance (1 MiB) of frames, enough for {@code Pipeline.fail} to write the FAILED
 * session and checkpoint, then refuses every append. The store never writes the device below the reserve.
 */
public final class StoreLowSpace extends RuntimeException {
    public static final String CODE = "D_LOW_STORAGE";
    public StoreLowSpace(String message) { super(CODE + ": " + message); }

    /** The StoreLowSpace in a cause chain, or null. */
    public static StoreLowSpace in(Throwable t) {
        for (int i = 0; t != null && i < 16; i++, t = t.getCause()) if (t instanceof StoreLowSpace) return (StoreLowSpace) t;
        return null;
    }
}
