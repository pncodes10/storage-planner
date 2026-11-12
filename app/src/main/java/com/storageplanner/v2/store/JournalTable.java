package com.storageplanner.v2.store;

import com.storageplanner.v2.model.OperationEntry;
import java.util.List;

/** Append-only: put of an existing key throws IllegalStateException. */
public interface JournalTable extends Table<OperationEntry> {
    List<OperationEntry> forOp(String opId);
    /** opIds whose last entry is not in a terminal phase. */
    List<String> openOps();
}
