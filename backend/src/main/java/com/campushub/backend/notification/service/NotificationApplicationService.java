package com.campushub.backend.notification.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;

public interface NotificationApplicationService {

    void notifyOrderAccepted(Long receiverId, Long orderId, String content);

    void notifyStatusChanged(Long receiverId, Long orderId, String content);

    void notifyReviewReceived(Long receiverId, Long orderId, String content);

    PageResponse<NotificationResponse> list(Long userId, NotificationQuery query);

    void markAsRead(Long userId, Long notificationId);
}
