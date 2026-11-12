package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.model.DecodeStatus;

import java.util.Collections;
import java.util.Set;

public final class JvmMediaDecoder implements MediaDecoder {
    private final StorageAccess storage;
    public JvmMediaDecoder(StorageAccess storage) { this.storage = storage; }
    public Set<String> decodableTypes() { return Collections.emptySet(); }
    public DecodedImage decode(String v, String p, int e, long m, boolean a) { return DecodedImage.failed(DecodeStatus.NO_DECODER, null); }
    public DecodedImage videoFrame(String v, String p, long t, int e) { return DecodedImage.failed(DecodeStatus.UNSUPPORTED_FORMAT, null); }
}
