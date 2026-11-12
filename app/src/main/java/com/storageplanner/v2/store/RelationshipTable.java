package com.storageplanner.v2.store;

import com.storageplanner.v2.model.Relationship;
import java.util.List;

public interface RelationshipTable extends Table<Relationship> { List<Relationship> of(String nodeRef); }
