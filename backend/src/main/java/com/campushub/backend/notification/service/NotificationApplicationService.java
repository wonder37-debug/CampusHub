package com.campushub.backend.notification.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.order.domain.OrderStatus;

public interface NotificationApplicationService {

    void notifyOrderAcceptedForPublisher(Long receiverId, Long orderId);

    void notifyOrderAcceptedForAccepter(Long receiverId, Long orderId);

    void notifyOrderStatusChanged(Long receiverId, Long orderId, OrderStatus status, boolean isPublisher);

    void notifyOrderCompletionPending(Long receiverId, Long orderId);

    void notifyReviewReceived(Long receiverId, Long orderId);

    void notifyDemandReviewRequested(Long receiverId, Long demandId);

    void notifyDemandRejected(Long receiverId, Long demandId, String reviewReason);

    void notifyDemandApproved(Long receiverId, Long demandId);

    PageResponse<NotificationResponse> list(Long userId, NotificationQuery query);

    void markAsRead(Long userId, Long notificationId);
}
