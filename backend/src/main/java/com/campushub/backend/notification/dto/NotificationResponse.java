package com.campushub.backend.notification.dto;

import com.campushub.backend.notification.domain.Notification;
import java.time.LocalDateTime;

public record NotificationResponse(
    Long id,
    String type,
    String title,
    String content,
    boolean read,
    Long relatedId,
    LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getType().name(),
            notification.getTitle(),
            notification.getContent(),
            notification.isRead(),
            notification.getRelatedId(),
            notification.getCreatedAt()
        );
    }
}
