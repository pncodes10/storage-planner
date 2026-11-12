package com.storageplanner.v2.store;

public final class StoreException extends Exception {
    public StoreException(String message) { super(message); }
    public StoreException(String message, Throwable cause) { super(message, cause); }
}
