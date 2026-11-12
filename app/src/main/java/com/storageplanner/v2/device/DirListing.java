package com.storageplanner.v2.device;

import java.util.ArrayList;
import java.util.List;

public final class DirListing {
    public IoStatus status; public List<DirEntry> entries = new ArrayList<DirEntry>(); public String error;
    public static DirListing failed(IoStatus st, String error) { DirListing l = new DirListing(); l.status = st; l.error = error; return l; }
}
