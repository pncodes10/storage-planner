package com.storageplanner.v2.model;

/** Every store record: a stable key and a deep copy. */
public interface Record { String key(); Record copy(); }
