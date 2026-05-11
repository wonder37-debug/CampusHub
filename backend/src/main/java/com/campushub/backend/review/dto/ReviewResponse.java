package com.campushub.backend.review.dto;

import com.campushub.backend.review.domain.Review;
import java.time.LocalDateTime;

public record ReviewResponse(
    Long id,
    Long orderId,
    Long authorId,
    Long targetId,
    int rating,
    String comment,
    LocalDateTime createdAt
) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
            review.getId(),
            review.getOrderId(),
            review.getAuthorId(),
            review.getTargetId(),
            review.getRating(),
            review.getComment(),
            review.getCreatedAt()
        );
    }
}
