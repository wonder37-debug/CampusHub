package com.campushub.backend.api.view;

import java.time.LocalDateTime;

public record ReviewView(
    Long id,
    Long orderId,
    Long responseId,
    Long demandId,
    String demandTitle,
    int rating,
    String comment,
    Long targetId,
    String targetName,
    PublicUserSummaryView author,
    LocalDateTime createdAt
) {
}
