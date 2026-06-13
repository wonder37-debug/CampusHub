package com.campushub.backend.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.notification.repository.InMemoryNotificationRepository;
import com.campushub.backend.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationApplicationServiceImplTest {

    private NotificationApplicationService notificationApplicationService;

    @BeforeEach
    void setUp() {
        notificationApplicationService = new NotificationApplicationServiceImpl(new InMemoryNotificationRepository());
    }

    @Test
    void shouldListUnreadNotifications() {
        notificationApplicationService.notifyOrderAcceptedForPublisher(1L, 100L);
        notificationApplicationService.notifyOrderStatusChanged(1L, 100L, OrderStatus.IN_PROGRESS, true);

        PageResponse<NotificationResponse> page = notificationApplicationService.list(
            1L,
            new NotificationQuery(true, new PageQuery(1, 20))
        );

        assertEquals(2, page.total());
        assertTrue(page.items().stream().allMatch(item -> !item.read()));
        assertTrue(page.items().stream().allMatch(item -> "ORDER".equals(item.targetType())));
        assertTrue(page.items().stream().allMatch(item -> "VIEW_ORDER".equals(item.actionHint())));
    }

    @Test
    void shouldMarkOwnNotificationAsRead() {
        notificationApplicationService.notifyOrderAcceptedForPublisher(1L, 100L);
        NotificationResponse notification = notificationApplicationService.list(
            1L,
            new NotificationQuery(false, new PageQuery(1, 20))
        ).items().get(0);

        notificationApplicationService.markAsRead(1L, notification.id());

        NotificationResponse updated = notificationApplicationService.list(
            1L,
            new NotificationQuery(false, new PageQuery(1, 20))
        ).items().get(0);
        assertTrue(updated.read());
    }

    @Test
    void shouldRejectMarkingOthersNotificationAsRead() {
        notificationApplicationService.notifyOrderAcceptedForPublisher(1L, 100L);
        NotificationResponse notification = notificationApplicationService.list(
            1L,
            new NotificationQuery(false, new PageQuery(1, 20))
        ).items().get(0);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> notificationApplicationService.markAsRead(2L, notification.id())
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
        NotificationResponse unchanged = notificationApplicationService.list(
            1L,
            new NotificationQuery(false, new PageQuery(1, 20))
        ).items().get(0);
        assertFalse(unchanged.read());
    }

    @Test
    void shouldBuildStructuredContentWithFallbackWhenRelatedResourceMissing() {
        notificationApplicationService.notifyDemandRejected(1L, 999L, "信息不完整");
        NotificationResponse notification = notificationApplicationService.list(
            1L,
            new NotificationQuery(false, new PageQuery(1, 20))
        ).items().get(0);

        assertEquals("需求审核未通过", notification.title());
        assertTrue(notification.content().contains("相关需求"));
        assertTrue(notification.content().contains("信息不完整"));
    }
}
