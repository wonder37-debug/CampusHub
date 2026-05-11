package com.campushub.backend.order.dto;

import com.campushub.backend.common.model.PageQuery;

public record OrderHistoryQuery(PageQuery pageQuery) {

    public OrderHistoryQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
