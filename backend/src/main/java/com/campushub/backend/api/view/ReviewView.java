package com.campushub.backend.api.view;

import java.time.LocalDateTime;

public record ReviewView(
    Long id,
    Long orderId,
    int rating,
    String comment,
    Long targetId,
    String targetName,
    UserSummaryView author,
    LocalDateTime createdAt
) {
}
