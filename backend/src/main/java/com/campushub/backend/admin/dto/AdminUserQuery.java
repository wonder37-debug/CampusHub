package com.campushub.backend.admin.dto;

import com.campushub.backend.common.model.PageQuery;

public record AdminUserQuery(
    String q,
    String searchField,
    String role,
    String status,
    String sortBy,
    String sortDirection,
    PageQuery pageQuery
) {

    public AdminUserQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
        searchField = searchField == null ? null : searchField.trim();
        role = role == null ? null : role.trim();
        status = status == null ? null : status.trim();
        sortBy = sortBy == null ? null : sortBy.trim();
        sortDirection = sortDirection == null ? null : sortDirection.trim();
    }
}
