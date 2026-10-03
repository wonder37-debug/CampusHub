package com.campushub.backend.demand.dto;

import com.campushub.backend.common.model.PageQuery;

public record DemandReviewQuery(
    String q,
    String category,
    String campusZone,
    PageQuery pageQuery
) {

    public DemandReviewQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
