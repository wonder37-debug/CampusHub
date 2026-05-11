package com.campushub.backend.demand.dto;

import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.DemandSort;
import java.time.LocalDateTime;

public record DemandQuery(
    String q,
    String category,
    String campusZone,
    String location,
    LocalDateTime startTimeFrom,
    LocalDateTime startTimeTo,
    DemandSort sort,
    PageQuery pageQuery
) {

    public DemandQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
        sort = sort == null ? DemandSort.TIME : sort;
    }
}
