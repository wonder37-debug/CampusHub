package com.campushub.backend.review.dto;

import com.campushub.backend.common.model.PageQuery;

public record ReviewQuery(PageQuery pageQuery) {

    public ReviewQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
