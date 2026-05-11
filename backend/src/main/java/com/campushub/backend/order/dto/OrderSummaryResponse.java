package com.campushub.backend.order.dto;

import com.campushub.backend.order.domain.Order;
import java.time.LocalDateTime;

public record OrderSummaryResponse(
    Long orderId,
    Long demandId,
    Long publisherId,
    Long accepterId,
    String status,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {

    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
            order.getId(),
            order.getDemandId(),
            order.getPublisherId(),
            order.getAccepterId(),
            order.getStatus().name(),
            order.getCreatedAt(),
            order.getCompletedAt()
        );
    }
}
