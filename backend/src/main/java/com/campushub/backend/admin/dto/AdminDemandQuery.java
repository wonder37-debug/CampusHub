package com.campushub.backend.admin.dto;

import com.campushub.backend.common.model.PageQuery;

public record AdminDemandQuery(String q, String category, String campusZone, PageQuery pageQuery) {

    public AdminDemandQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }

    public AdminDemandQuery(String q, String category, PageQuery pageQuery) {
        this(q, category, null, pageQuery);
    }
}
