package com.campushub.backend.recommendation.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.recommendation.dto.RecommendationItemResponse;

public interface RecommendationApplicationService {

    PageResponse<RecommendationItemResponse> recommend(Long userId, DemandQuery query);

    PageResponse<DemandSummaryResponse> recommendDemandList(Long userId, DemandQuery query);
}
