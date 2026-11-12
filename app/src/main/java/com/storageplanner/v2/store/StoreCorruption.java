package com.storageplanner.v2.store;

/**
 * Saved store data cannot be trusted (PHASE_S_STORE 3.6 F-04, 3.11): a damaged record of a table that fails closed, a
 * locator that reached another record's frame, or an I/O failure on the store's files. Unchecked: it is thrown from
 * reads ({@code get}, iterators) and writes. It is never turned into "absent". The caller fails the run; the next open
 * of a store marked damaged refuses, so the caller starts a fresh store.
 */
public final class StoreCorruption extends RuntimeException {
    public StoreCorruption(String message) { super(message); }
    public StoreCorruption(String message, Throwable cause) { super(message, cause); }
}
