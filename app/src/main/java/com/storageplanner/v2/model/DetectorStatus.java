package com.storageplanner.v2.model;

/** Result of an optional detector (QR, faces). NOT_RUN = unknown; FAILED = unknown, reason in FeatureRecord.problems. */
public enum DetectorStatus { NOT_RUN, OK, FAILED }
