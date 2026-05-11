package com.campushub.backend.admin.dto;

import com.campushub.backend.common.model.PageQuery;

public record AdminDemandQuery(String q, String category, PageQuery pageQuery) {

    public AdminDemandQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
