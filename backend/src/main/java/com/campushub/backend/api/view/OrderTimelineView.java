package com.campushub.backend.api.view;

import java.time.LocalDateTime;

public record OrderTimelineView(
    LocalDateTime changedAt,
    String fromStatus,
    String toStatus,
    Long operatorId,
    String note
) {
}
