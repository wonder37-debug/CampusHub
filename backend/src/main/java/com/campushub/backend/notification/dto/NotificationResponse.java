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
    String targetType,
    Long targetId,
    String targetTitle,
    String actionHint,
    LocalDateTime createdAt
) {

    public static NotificationResponse from(
        Notification notification,
        String targetType,
        Long targetId,
        String targetTitle,
        String actionHint
    ) {
        return new NotificationResponse(
            notification.getId(),
            notification.getType().name(),
            notification.getTitle(),
            notification.getContent(),
            notification.isRead(),
            notification.getRelatedId(),
            targetType,
            targetId,
            targetTitle,
            actionHint,
            notification.getCreatedAt()
        );
    }
}
