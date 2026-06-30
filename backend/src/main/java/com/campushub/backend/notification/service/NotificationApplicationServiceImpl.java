package com.campushub.backend.notification.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.domain.NotificationType;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.notification.repository.NotificationRepository;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationApplicationServiceImpl implements NotificationApplicationService {

    private final NotificationRepository notificationRepository;
    private final OrderRepository orderRepository;
    private final DemandRepository demandRepository;

    public NotificationApplicationServiceImpl(NotificationRepository notificationRepository) {
        this(notificationRepository, null, null);
    }

    @Autowired
    public NotificationApplicationServiceImpl(
        NotificationRepository notificationRepository,
        OrderRepository orderRepository,
        DemandRepository demandRepository
    ) {
        this.notificationRepository = notificationRepository;
        this.orderRepository = orderRepository;
        this.demandRepository = demandRepository;
    }

    @Override
    public void notifyOrderAcceptedForPublisher(Long receiverId, Long orderId) {
        createNotification(receiverId, buildOrderAcceptedForPublisher(orderId));
    }

    @Override
    public void notifyOrderAcceptedForAccepter(Long receiverId, Long orderId) {
        createNotification(receiverId, buildOrderAcceptedForAccepter(orderId));
    }

    @Override
    public void notifyOrderStatusChanged(Long receiverId, Long orderId, OrderStatus status, boolean isPublisher) {
        createNotification(receiverId, buildOrderStatusChanged(orderId, status, isPublisher));
    }

    @Override
    public void notifyOrderCompletionPending(Long receiverId, Long orderId) {
        createNotification(receiverId, buildOrderCompletionPending(orderId));
    }

    @Override
    public void notifyReviewReceived(Long receiverId, Long orderId) {
        createNotification(receiverId, buildReviewReceived(orderId));
    }

    @Override
    public void notifyDemandReviewRequested(Long receiverId, Long demandId) {
        createNotification(receiverId, buildDemandReviewRequested(demandId));
    }

    @Override
    public void notifyDemandRejected(Long receiverId, Long demandId, String reviewReason) {
        createNotification(receiverId, buildDemandRejected(demandId, reviewReason));
    }

    @Override
    public void notifyDemandApproved(Long receiverId, Long demandId) {
        createNotification(receiverId, buildDemandApproved(demandId));
    }

    @Override
    public void notifyPendingReviewReminder(Long receiverId, Long orderId) {
        createNotification(receiverId, buildPendingReviewReminder(orderId));
    }

    @Override
    public void notifyOrderArbitrationRequested(Long receiverId, Long orderId, Long requesterId, String reason) {
        createNotification(receiverId, buildOrderArbitrationRequested(orderId, requesterId, reason));
    }

    @Override
    public void notifyOrderArbitrationResolved(Long receiverId, Long orderId, String outcome, String reason) {
        createNotification(receiverId, buildOrderArbitrationResolved(orderId, outcome, reason));
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
            : filtered.subList(fromIndex, toIndex).stream().map(this::toNotificationResponse).toList();
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

    private void createNotification(Long receiverId, NotificationDraft draft) {
        if (receiverId == null || draft == null) {
            return;
        }
        Notification notification = new Notification(
            null,
            receiverId,
            draft.type(),
            draft.title(),
            draft.content(),
            false,
            draft.relatedId(),
            LocalDateTime.now()
        );
        notificationRepository.save(notification);
    }

    private NotificationDraft buildOrderAcceptedForPublisher(Long orderId) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        return new NotificationDraft(
            NotificationType.ORDER_ACCEPTED,
            "订单已被接单",
            "您的需求《" + demandTitle + "》已有同学接单，请尽快沟通细节。",
            orderId
        );
    }

    private NotificationDraft buildOrderAcceptedForAccepter(Long orderId) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        return new NotificationDraft(
            NotificationType.ORDER_ACCEPTED,
            "接单成功",
            "您已成功接下《" + demandTitle + "》，请按约定开始处理。",
            orderId
        );
    }

    private NotificationDraft buildOrderStatusChanged(Long orderId, OrderStatus status, boolean isPublisher) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        String title;
        String content;
        switch (status) {
            case IN_PROGRESS -> {
                title = isPublisher ? "订单已开始处理" : "您已开始处理订单";
                content = isPublisher
                    ? "接单方已开始处理您的需求《" + demandTitle + "》。"
                    : "您已开始处理《" + demandTitle + "》，请按约定完成。";
            }
            case IN_ARBITRATION -> {
                title = "订单进入仲裁";
                content = "订单《" + demandTitle + "》已进入管理员仲裁，请等待处理结果。";
            }
            case COMPLETED -> {
                title = "订单已完成";
                content = isPublisher
                    ? "您的需求《" + demandTitle + "》已完成，请对接单方进行评价。"
                    : "订单《" + demandTitle + "》已完成，请对发布者进行评价。";
            }
            case CANCELLED -> {
                title = isPublisher ? "您已取消订单" : "订单已被取消";
                content = isPublisher
                    ? "您已取消订单《" + demandTitle + "》。"
                    : "订单《" + demandTitle + "》已被取消。";
            }
            default -> {
                title = "订单状态已更新";
                content = "订单《" + demandTitle + "》当前状态已更新为 " + formatOrderStatus(status) + "。";
            }
        }
        return new NotificationDraft(NotificationType.STATUS_CHANGED, title, content, orderId);
    }

    private NotificationDraft buildOrderCompletionPending(Long orderId) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        return new NotificationDraft(
            NotificationType.STATUS_CHANGED,
            "订单待确认完成",
            "订单《" + demandTitle + "》已由对方提交完成，请您尽快确认。",
            orderId
        );
    }

    private NotificationDraft buildReviewReceived(Long orderId) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        return new NotificationDraft(
            NotificationType.REVIEW_RECEIVED,
            "收到新的评价",
            "订单《" + demandTitle + "》有一条新的评价，请及时查看。",
            orderId
        );
    }

    private NotificationDraft buildDemandReviewRequested(Long demandId) {
        String demandTitle = resolveDemandTitle(demandId);
        return new NotificationDraft(
            NotificationType.REVIEW_REQUEST,
            "需求待审核",
            "有新的需求《" + demandTitle + "》等待审核。",
            demandId
        );
    }

    private NotificationDraft buildDemandRejected(Long demandId, String reviewReason) {
        String demandTitle = resolveDemandTitle(demandId);
        return new NotificationDraft(
            NotificationType.DEMAND_REJECTED,
            "需求审核未通过",
            "您的需求《" + demandTitle + "》审核未通过，原因：" + normalizeReviewReason(reviewReason),
            demandId
        );
    }

    private NotificationDraft buildDemandApproved(Long demandId) {
        String demandTitle = resolveDemandTitle(demandId);
        return new NotificationDraft(
            NotificationType.DEMAND_APPROVED,
            "需求审核通过",
            "您的需求《" + demandTitle + "》已通过审核，现已开放接单。",
            demandId
        );
    }

    private NotificationDraft buildPendingReviewReminder(Long orderId) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        return new NotificationDraft(
            NotificationType.PENDING_REVIEW,
            "请您及时完成评价",
            "您有已完成订单《" + demandTitle + "》尚未评价，请尽快评价以维护您的信用记录。",
            orderId
        );
    }

    private NotificationDraft buildOrderArbitrationRequested(Long orderId, Long requesterId, String reason) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        String roleLabel = resolveArbitrationRequesterRole(orderId, requesterId);
        return new NotificationDraft(
            NotificationType.ORDER_ARBITRATION_REQUESTED,
            "有订单申请仲裁",
            roleLabel + "针对订单《" + demandTitle + "》发起了仲裁。原因：" + normalizeReviewReason(reason),
            orderId
        );
    }

    private NotificationDraft buildOrderArbitrationResolved(Long orderId, String outcome, String reason) {
        String demandTitle = resolveOrderDemandTitle(orderId);
        return new NotificationDraft(
            NotificationType.ORDER_ARBITRATION_RESOLVED,
            "订单仲裁已处理",
            "订单《" + demandTitle + "》仲裁结果为 " + formatArbitrationOutcome(outcome) + "。说明：" + normalizeReviewReason(reason),
            orderId
        );
    }

    private NotificationResponse toNotificationResponse(Notification notification) {
        String targetType = resolveTargetType(notification);
        Long targetId = notification.getRelatedId();
        String targetTitle = resolveTargetTitle(notification, targetType);
        String actionHint = resolveActionHint(notification.getType(), targetType);
        return NotificationResponse.from(notification, targetType, targetId, targetTitle, actionHint);
    }

    private String resolveTargetType(Notification notification) {
        if (notification == null || notification.getType() == null) {
            return null;
        }
        return switch (notification.getType()) {
            case ORDER_ACCEPTED, STATUS_CHANGED, REVIEW_RECEIVED, PENDING_REVIEW,
                ORDER_ARBITRATION_REQUESTED, ORDER_ARBITRATION_RESOLVED -> "ORDER";
            case REVIEW_REQUEST, DEMAND_REJECTED, DEMAND_APPROVED -> "DEMAND";
        };
    }

    private String resolveTargetTitle(Notification notification, String targetType) {
        if (notification == null || notification.getRelatedId() == null || targetType == null) {
            return null;
        }
        if ("DEMAND".equals(targetType)) {
            return demandRepository == null
                ? null
                : demandRepository.findById(notification.getRelatedId()).map(demand -> demand.getTitle()).orElse(null);
        }
        if (!"ORDER".equals(targetType) || orderRepository == null) {
            return null;
        }
        return orderRepository.findById(notification.getRelatedId())
            .flatMap(order -> demandRepository == null ? Optional.empty() : demandRepository.findById(order.getDemandId()))
            .map(demand -> demand.getTitle())
            .orElse(null);
    }

    private String resolveActionHint(NotificationType type, String targetType) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case REVIEW_REQUEST -> "REVIEW_DEMAND";
            case DEMAND_REJECTED, DEMAND_APPROVED -> "VIEW_DEMAND";
            case REVIEW_RECEIVED, PENDING_REVIEW, ORDER_ACCEPTED, STATUS_CHANGED,
                ORDER_ARBITRATION_REQUESTED, ORDER_ARBITRATION_RESOLVED ->
                "ORDER".equals(targetType) ? "VIEW_ORDER" : "VIEW_DEMAND";
        };
    }

    private String resolveOrderDemandTitle(Long orderId) {
        if (orderId == null || orderRepository == null) {
            return "相关订单";
        }
        return orderRepository.findById(orderId)
            .flatMap(order -> demandRepository == null ? Optional.empty() : demandRepository.findById(order.getDemandId()))
            .map(demand -> demand.getTitle())
            .filter(title -> !title.isBlank())
            .orElse("相关订单");
    }

    /**
     * 根据订单信息判断提起仲裁的人是发单方还是接单方。
     */
    private String resolveArbitrationRequesterRole(Long orderId, Long requesterId) {
        if (orderId == null || requesterId == null || orderRepository == null) {
            return "有用户";
        }
        try {
            return orderRepository.findById(orderId)
                .map(order -> {
                    if (requesterId.equals(order.getPublisherId())) {
                        return "发单方";
                    }
                    if (requesterId.equals(order.getAccepterId())) {
                        return "接单方";
                    }
                    return "用户";
                })
                .orElse("用户");
        } catch (Exception e) {
            return "用户";
        }
    }

    private String resolveDemandTitle(Long demandId) {
        if (demandId == null || demandRepository == null) {
            return "相关需求";
        }
        return demandRepository.findById(demandId)
            .map(demand -> demand.getTitle())
            .filter(title -> !title.isBlank())
            .orElse("相关需求");
    }

    private String normalizeReviewReason(String reviewReason) {
        if (reviewReason == null || reviewReason.isBlank()) {
            return "请查看详情";
        }
        String normalized = reviewReason.trim();
        int fullWidthMarker = normalized.lastIndexOf("原因：");
        if (fullWidthMarker >= 0) {
            return normalized.substring(fullWidthMarker + 3).trim();
        }
        int halfWidthMarker = normalized.lastIndexOf("原因:");
        if (halfWidthMarker >= 0) {
            return normalized.substring(halfWidthMarker + 3).trim();
        }
        return normalized;
    }

    private String formatOrderStatus(OrderStatus status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case ACCEPTED -> "已接单";
            case IN_PROGRESS -> "进行中";
            case IN_ARBITRATION -> "仲裁中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    private String formatArbitrationOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return "未知结果";
        }
        String normalized = outcome.trim().toLowerCase();
        if ("complete".equals(normalized) || "completed".equals(normalized)) {
            return "完成订单";
        }
        if ("cancel".equals(normalized) || "cancelled".equals(normalized)) {
            return "取消订单";
        }
        return outcome;
    }

    private record NotificationDraft(NotificationType type, String title, String content, Long relatedId) {
    }
}
