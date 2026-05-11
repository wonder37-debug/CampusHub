package com.campushub.backend.order.dto;

import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import java.time.LocalDateTime;

public record OrderStatusHistoryResponse(
    String fromStatus,
    String toStatus,
    Long operatorId,
    String note,
    LocalDateTime changedAt
) {

    public static OrderStatusHistoryResponse from(OrderStatusHistoryEntry entry) {
        return new OrderStatusHistoryResponse(
            entry.fromStatus() == null ? null : entry.fromStatus().name(),
            entry.toStatus().name(),
            entry.operatorId(),
            entry.note(),
            entry.changedAt()
        );
    }
}
