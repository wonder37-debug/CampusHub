package com.campushub.backend.api.view;

import java.time.LocalDateTime;
import java.util.List;

public record OrderView(
    Long id,
    Long orderId,
    String status,
    Long demandId,
    Long publisherId,
    Long accepterId,
    String acceptNote,
    boolean proofSubmitted,
    int proofImageCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt,
    DemandView demand,
    UserSummaryView requester,
    UserSummaryView provider,
    List<OrderTimelineView> statusHistory
) {
}
