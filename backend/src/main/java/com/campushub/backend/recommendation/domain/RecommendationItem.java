package com.campushub.backend.recommendation.domain;

import com.campushub.backend.demand.domain.Demand;
import java.util.List;

public record RecommendationItem(
    Demand demand,
    double score,
    int rank,
    List<String> reasonTags
) {
}
