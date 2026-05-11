package com.campushub.backend.admin.dto;

import com.campushub.backend.common.model.PageQuery;

public record AdminUserQuery(String q, PageQuery pageQuery) {

    public AdminUserQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
