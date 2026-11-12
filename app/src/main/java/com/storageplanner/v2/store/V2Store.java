package com.storageplanner.v2.store;

import com.storageplanner.v2.model.*;

/** The v2 store (ARCHITECTURE 4.1). */
public interface V2Store extends java.io.Closeable {
    int schemaVersion();
    FileTable files();
    Table<FeatureRecord> features();
    Table<HashRecord> hashes();
    Table<EmbeddingRecord> embeddings();
    RelationshipTable relationships();
    Table<DuplicateGroup> duplicateGroups();
    Table<BackupRecord> backups();
    /** PHASE_3 2.1 (A-1). */
    Table<PairCheck> pairChecks();
    RecommendationTable recommendations();
    Table<ActionPlan> actionPlans();
    JournalTable operations();
    Table<Feedback> feedback();
    Table<Preference> preferences();
    Table<ModelRecord> models();
    Table<ScanSession> scanSessions();
    Table<Volume> volumes();
    DirTable directories();
    /** Consistency point. */
    void commit() throws StoreException;
    /** Drops uncommitted changes. */
    void rollback();
    /** commit() and make it durable now. */
    void flush() throws StoreException;
}
