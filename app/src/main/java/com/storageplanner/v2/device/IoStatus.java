package com.storageplanner.v2.device;

import java.io.IOException;

public enum IoStatus {
    OK, NOT_FOUND, PERMISSION_DENIED, NOT_A_DIRECTORY, IO_ERROR, PARTIAL_IO_ERROR, UNSUPPORTED;

    /** NOT_FOUND only on positive evidence (NoSuchFileException); java.io.FileNotFoundException is IO_ERROR (REVIEW_1 F-B2). */
    public static IoStatus of(IOException e) {
        if (e instanceof java.nio.file.AccessDeniedException) return PERMISSION_DENIED;
        if (e instanceof java.nio.file.NoSuchFileException) return NOT_FOUND;
        if (e instanceof java.nio.file.NotDirectoryException) return NOT_A_DIRECTORY;
        return IO_ERROR;
    }
}
