package com.campushub.backend.api.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DemandView(
    Long id,
    Long publisherId,
    String publisherDisplayName,
    UserSummaryView publisher,
    String title,
    String description,
    String category,
    String campusZone,
    String location,
    LocalDateTime startTime,
    LocalDateTime endTime,
    BigDecimal reward,
    List<String> tags,
    String status,
    boolean anonymous,
    String anonymousCode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
