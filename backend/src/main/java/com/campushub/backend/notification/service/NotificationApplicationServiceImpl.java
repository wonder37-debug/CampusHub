package com.campushub.backend.notification.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.domain.NotificationType;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationApplicationServiceImpl implements NotificationApplicationService {

    private final NotificationRepository notificationRepository;

    public NotificationApplicationServiceImpl(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void notifyOrderAccepted(Long receiverId, Long orderId, String content) {
        createNotification(receiverId, NotificationType.ORDER_ACCEPTED, "订单已接单", content, orderId);
    }

    @Override
    public void notifyStatusChanged(Long receiverId, Long orderId, String content) {
        createNotification(receiverId, NotificationType.STATUS_CHANGED, "订单状态更新", content, orderId);
    }

    @Override
    public void notifyReviewReceived(Long receiverId, Long orderId, String content) {
        createNotification(receiverId, NotificationType.REVIEW_RECEIVED, "收到新的评价", content, orderId);
    }

    @Override
    public PageResponse<NotificationResponse> list(Long userId, NotificationQuery query) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "notification query must not be null");
        }

        List<Notification> filtered = notificationRepository.findByUserId(userId).stream()
            .filter(notification -> !query.unreadOnly() || !notification.isRead())
            .sorted(Comparator.comparing(Notification::getCreatedAt).reversed())
            .toList();

        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(filtered.size(), fromIndex + size);
        List<NotificationResponse> items = fromIndex >= filtered.size()
            ? List.of()
            : filtered.subList(fromIndex, toIndex).stream().map(NotificationResponse::from).toList();
        return new PageResponse<>(items, page, size, filtered.size());
    }

    @Override
    public void markAsRead(Long userId, Long notificationId) {
        if (userId == null || notificationId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId and notificationId must not be null");
        }
        Notification notification = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "notification not found"));
        if (!notification.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "cannot mark another user's notification");
        }
        notification.markAsRead();
        notificationRepository.save(notification);
    }

    private void createNotification(Long receiverId, NotificationType type, String title, String content, Long relatedId) {
        if (receiverId == null) {
            return;
        }
        Notification notification = new Notification(
            null,
            receiverId,
            type,
            title,
            content,
            false,
            relatedId,
            LocalDateTime.now()
        );
        notificationRepository.save(notification);
    }
}
