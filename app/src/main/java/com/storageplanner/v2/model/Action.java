package com.storageplanner.v2.model;

/** ARCHITECTURE 2.11. */
public enum Action {
    KEEP, DELETE, COMPRESS, ARCHIVE, QUARANTINE, REVIEW, CLEAR_APP_CACHE, ARCHIVE_APP, UNINSTALL_APP;
    public boolean removal() { return this == DELETE || this == QUARANTINE || this == UNINSTALL_APP; }
    public boolean transform() { return this == COMPRESS || this == ARCHIVE; }
}
