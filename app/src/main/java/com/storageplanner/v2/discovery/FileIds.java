package com.storageplanner.v2.discovery;

import com.storageplanner.v2.util.Hashing;
import com.storageplanner.v2.util.Hex;

import java.nio.charset.StandardCharsets;

/** Stable file id (ARCHITECTURE 2.3.1). */
public final class FileIds {
    private FileIds() {}
    public static String of(String volumeId, String normalizedPath, String identityToken) {
        String s = "sp-fid-1" + "\u0000" + volumeId + "\u0000" + normalizedPath + "\u0000" + identityToken;
        byte[] d = Hashing.sha256().digest(s.getBytes(StandardCharsets.UTF_8));
        return Hex.of(d, 0, 16);
    }
}
