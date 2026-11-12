package com.storageplanner.v2.model;

public final class ScanEvent {
    public long at = Unknown.TIME;
    public String code, volumeId, path, detail;
    public ScanEvent() {}
    public ScanEvent(long at, String code, String volumeId, String path, String detail) {
        this.at = at; this.code = code; this.volumeId = volumeId; this.path = path; this.detail = detail;
    }
    public ScanEvent copy() { return new ScanEvent(at, code, volumeId, path, detail); }
}
