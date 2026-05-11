package com.campushub.backend.order.dto;

import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.order.domain.Order;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
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
    DemandDetailResponse demand,
    List<OrderStatusHistoryResponse> statusHistory
) {

    public static OrderDetailResponse from(Order order, DemandDetailResponse demand) {
        return new OrderDetailResponse(
            order.getId(),
            order.getStatus().name(),
            order.getDemandId(),
            order.getPublisherId(),
            order.getAccepterId(),
            order.getAcceptNote(),
            order.isProofSubmitted(),
            order.getProofImageCount(),
            order.getCreatedAt(),
            order.getUpdatedAt(),
            order.getCompletedAt(),
            demand,
            order.getStatusHistory().stream().map(OrderStatusHistoryResponse::from).toList()
        );
    }
}
