package com.storageplanner.v2.model;

public final class PdfInfo extends Sub {
    public String version, pagesSource, title, author, producer;
    public Tri encrypted = Tri.UNKNOWN, linearized = Tri.UNKNOWN, objectStreams = Tri.UNKNOWN;
    public int pages = -1, incrementalUpdates = -1;
    public long createdAt = Unknown.TIME;
}
