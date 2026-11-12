package com.storageplanner.v2.store;

import com.storageplanner.v2.model.Recommendation;
import java.util.List;

public interface RecommendationTable extends Table<Recommendation> { List<Recommendation> forFile(String fileId); }
