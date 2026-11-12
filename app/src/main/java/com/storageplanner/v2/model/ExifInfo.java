package com.storageplanner.v2.model;

public final class ExifInfo extends Sub {
    public long dateTimeOriginal = Unknown.TIME, dateTime = Unknown.TIME;
    public String offsetTimeOriginal, make, model, software;
    public Tri tzKnown = Tri.UNKNOWN, hasGps = Tri.UNKNOWN, hasThumbnail = Tri.UNKNOWN;
    public int orientation = -1, pixelX = -1, pixelY = -1;
}
