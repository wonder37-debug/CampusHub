package com.campushub.backend.api.view;

import java.util.List;

public record RecommendedDemandView(
    int rank,
    double score,
    List<String> reasonTags,
    DemandView demand
) {
}
