package com.campushub.backend.recommendation.dto;

import com.campushub.backend.recommendation.domain.RecommendationItem;
import java.util.List;

public record RecommendationItemResponse(
    Long demandId,
    double score,
    int rank,
    List<String> reasonTags
) {

    public static RecommendationItemResponse from(RecommendationItem item) {
        return new RecommendationItemResponse(
            item.demand().getId(),
            item.score(),
            item.rank(),
            item.reasonTags()
        );
    }
}
