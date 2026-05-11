package com.campushub.backend.order.domain;

import java.time.LocalDateTime;

public record OrderStatusHistoryEntry(
    OrderStatus fromStatus,
    OrderStatus toStatus,
    Long operatorId,
    String note,
    LocalDateTime changedAt
) {
}
