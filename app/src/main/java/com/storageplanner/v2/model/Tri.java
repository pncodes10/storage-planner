package com.storageplanner.v2.model;

public enum Tri { TRUE, FALSE, UNKNOWN; public static Tri of(boolean b) { return b ? TRUE : FALSE; } }
