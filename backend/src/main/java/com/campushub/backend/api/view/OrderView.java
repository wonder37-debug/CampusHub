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
    PublicUserSummaryView requester,
    PublicUserSummaryView provider,
    List<OrderTimelineView> statusHistory,
    List<ReviewView> reviews,
    boolean currentUserReviewed,
    Long pendingReviewTarget,
    String completionHint,
    List<String> demandImages,
    String demandContactInfo,
    String arbitrationResult
) {
}
