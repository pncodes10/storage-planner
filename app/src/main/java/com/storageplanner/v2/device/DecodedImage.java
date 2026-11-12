package com.storageplanner.v2.device;

import com.storageplanner.v2.model.DecodeStatus;

public final class DecodedImage {
    public DecodeStatus status;
    /** luma 0..255 row-major w*h; argb null unless wanted. */
    public int[] luma, argb;
    public int w, h, origW = -1, origH = -1, orientation = -1;
    public String error;
    public static DecodedImage failed(DecodeStatus s, String error) { DecodedImage d = new DecodedImage(); d.status = s; d.error = error; return d; }
}
