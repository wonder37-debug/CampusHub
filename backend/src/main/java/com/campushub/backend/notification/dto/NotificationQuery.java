package com.campushub.backend.notification.dto;

import com.campushub.backend.common.model.PageQuery;

public record NotificationQuery(boolean unreadOnly, PageQuery pageQuery) {

    public NotificationQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
