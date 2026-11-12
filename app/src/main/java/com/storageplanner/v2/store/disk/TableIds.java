package com.storageplanner.v2.store.disk;

import com.storageplanner.v2.store.Codecs;

/**
 * Frame table ids (PHASE_S_STORE 3.2): 1..17 in the {@code V2Store} accessor order (= ARCHITECTURE 4.1 / snapshot order).
 * The names are the snapshot table names ({@code Codec.table()}).
 */
public final class TableIds {
    private TableIds() {}
    public static final int FILES = 1, FEATURES = 2, HASHES = 3, EMBEDDINGS = 4, RELATIONSHIPS = 5, DUPLICATE_GROUPS = 6,
            BACKUPS = 7, PAIR_CHECKS = 8, RECOMMENDATIONS = 9, ACTION_PLANS = 10, OPERATIONS = 11, FEEDBACK = 12,
            PREFERENCES = 13, MODELS = 14, SESSIONS = 15, VOLUMES = 16, DIRECTORIES = 17;
    public static final int COUNT = 17;

    private static final Codecs.Codec<?>[] CODECS = {null, Codecs.FILE, Codecs.FEATURE, Codecs.HASH, Codecs.EMBEDDING,
            Codecs.RELATIONSHIP, Codecs.DUPLICATE_GROUP, Codecs.BACKUP, Codecs.PAIR_CHECK, Codecs.RECOMMENDATION,
            Codecs.ACTION_PLAN, Codecs.OPERATION, Codecs.FEEDBACK, Codecs.PREFERENCE, Codecs.MODEL, Codecs.SESSION,
            Codecs.VOLUME, Codecs.DIR};

    public static Codecs.Codec<?> codec(int id) {
        if (id < 1 || id > COUNT) throw new IllegalArgumentException("table id " + id);
        return CODECS[id];
    }

    public static String name(int id) { return codec(id).table(); }

    /** Id of a snapshot table name; -1 if unknown. */
    public static int id(String name) {
        for (int i = 1; i <= COUNT; i++) if (CODECS[i].table().equals(name)) return i;
        return -1;
    }

    /**
     * Derived per-file tables (3.11): a damaged record reads as absent, so the stage re-extracts it. Every other table
     * fails closed.
     */
    public static boolean derived(int id) { return id == FEATURES || id == HASHES || id == EMBEDDINGS || id == PAIR_CHECKS; }
}
