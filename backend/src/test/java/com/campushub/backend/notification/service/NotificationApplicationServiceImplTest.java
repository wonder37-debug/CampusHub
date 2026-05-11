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
        notificationApplicationService.notifyOrderAccepted(1L, 100L, "接单成功");
        notificationApplicationService.notifyStatusChanged(1L, 100L, "状态变更");

        PageResponse<NotificationResponse> page = notificationApplicationService.list(
            1L,
            new NotificationQuery(true, new PageQuery(1, 20))
        );

        assertEquals(2, page.total());
        assertTrue(page.items().stream().allMatch(item -> !item.read()));
    }

    @Test
    void shouldMarkOwnNotificationAsRead() {
        notificationApplicationService.notifyOrderAccepted(1L, 100L, "接单成功");
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
        notificationApplicationService.notifyOrderAccepted(1L, 100L, "接单成功");
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
}
