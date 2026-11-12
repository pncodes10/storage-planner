package com.storageplanner.v2.model;

/** PHASE_4 3.1, in rule order. */
public enum UsageClass {
    EMPTY, TRASH, TEMP, THUMBNAIL, CACHE, APP_LOG, STATUS, CHAT_BACKUP, INSTALLER, ARCHIVE, DOCUMENT_RECORD, DOCUMENT,
    CAMERA_PHOTO, CAMERA_VIDEO, SCREENSHOT, CHAT_MEDIA, VOICE, MUSIC, PICTURES, DOWNLOAD, APP_MEDIA, OTHER, UNKNOWN;
    /** Classes whose loss/utility defaults are the "junk" row of PHASE_4 4.1. */
    public boolean junk() { return this == TEMP || this == TRASH || this == THUMBNAIL || this == CACHE || this == APP_LOG || this == STATUS || this == EMPTY; }
}
