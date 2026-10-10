package com.campushub.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.domain.NotificationType;
import com.campushub.backend.notification.dto.NotificationQuery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@MybatisPlusTest
@ActiveProfiles("local")
@Import(MyBatisNotificationRepository.class)
@Sql(scripts = "classpath:schema-notification.sql")
class MyBatisNotificationRepositoryTest {

    @Autowired
    private MyBatisNotificationRepository repository;

    @Test
    void save_insert_assigns_id_and_findById_returns_persisted_notification() {
        Notification notification = newNotification(10L, NotificationType.ORDER_ACCEPTED);

        Notification saved = repository.save(notification);

        assertThat(saved.getId()).isNotNull();
        Optional<Notification> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getUserId()).isEqualTo(10L);
        assertThat(loaded.get().getType()).isEqualTo(NotificationType.ORDER_ACCEPTED);
        assertThat(loaded.get().getTitle()).isEqualTo("订单已接单");
        assertThat(loaded.get().isRead()).isFalse();
    }

    @Test
    void save_update_when_id_present_marks_as_read() {
        Notification notification = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        notification.setRead(true);
        repository.save(notification);

        Notification reloaded = repository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.isRead()).isTrue();
        assertThat(repository.findByUserId(10L)).hasSize(1);
    }

    @Test
    void findById_returns_empty_when_missing_or_null() {
        assertThat(repository.findById(9999L)).isEmpty();
        assertThat(repository.findById(null)).isEmpty();
    }

    @Test
    void findByUserId_returns_all_notifications_for_a_user() {
        repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        repository.save(newNotification(10L, NotificationType.REVIEW_RECEIVED));
        repository.save(newNotification(20L, NotificationType.STATUS_CHANGED));

        List<Notification> forUser10 = repository.findByUserId(10L);
        assertThat(forUser10).hasSize(2);
        assertThat(forUser10).extracting(Notification::getType)
            .containsExactlyInAnyOrder(NotificationType.ORDER_ACCEPTED, NotificationType.REVIEW_RECEIVED);

        assertThat(repository.findByUserId(null)).isNotNull().isEmpty();
    }

    @Test
    void findByUserId_returns_empty_list_not_null_when_no_notifications() {
        List<Notification> results = repository.findByUserId(999L);
        assertThat(results).isNotNull().isEmpty();
    }

    @Test
    void related_id_is_persisted_and_loaded_correctly() {
        Notification notification = newNotification(10L, NotificationType.ORDER_ACCEPTED);
        notification.setRelatedId(1001L);

        repository.save(notification);

        Notification reloaded = repository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getRelatedId()).isEqualTo(1001L);
    }

    @Test
    void content_is_persisted_and_loaded_correctly() {
        Notification notification = newNotification(10L, NotificationType.STATUS_CHANGED);

        repository.save(notification);

        Notification reloaded = repository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo("状态已变更");
    }

    @Test
    void findPage_returns_user_notifications_sorted_by_created_desc() {
        Notification older = repository.save(newNotificationWithCreated(10L, NotificationType.ORDER_ACCEPTED, LocalDateTime.now().minusMinutes(10)));
        Notification newer = repository.save(newNotificationWithCreated(10L, NotificationType.REVIEW_RECEIVED, LocalDateTime.now().minusMinutes(1)));

        List<Notification> page = repository.findPage(10L, new NotificationQuery(false, new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(repository.count(10L, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(2L);
    }

    @Test
    void findPage_unread_only_returns_unread_notifications() {
        Notification unread = repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        Notification read = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        read.setRead(true);
        repository.save(read);

        List<Notification> page = repository.findPage(10L, new NotificationQuery(true, new PageQuery(1, 20)));

        assertThat(page).extracting(Notification::getId).containsExactly(unread.getId());
        assertThat(repository.count(10L, new NotificationQuery(true, new PageQuery(1, 20)))).isEqualTo(1L);
    }

    @Test
    void findPage_unread_false_returns_all_including_read() {
        repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        Notification read = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        read.setRead(true);
        repository.save(read);

        List<Notification> page = repository.findPage(10L, new NotificationQuery(false, new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(repository.count(10L, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(2L);
    }

    @Test
    void findPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newNotificationWithCreated(10L, NotificationType.ORDER_ACCEPTED, LocalDateTime.now().minusMinutes(5 - i)));
        }
        NotificationQuery page1 = new NotificationQuery(false, new PageQuery(1, 2));
        NotificationQuery page2 = new NotificationQuery(false, new PageQuery(2, 2));
        NotificationQuery page3 = new NotificationQuery(false, new PageQuery(3, 2));

        assertThat(repository.findPage(10L, page1)).hasSize(2);
        assertThat(repository.findPage(10L, page2)).hasSize(2);
        assertThat(repository.findPage(10L, page3)).hasSize(1);
        assertThat(repository.count(10L, page1)).isEqualTo(5L);
    }

    @Test
    void count_matches_findPage_total_for_unread_and_all() {
        repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        Notification read = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        read.setRead(true);
        repository.save(read);

        assertThat(repository.count(10L, new NotificationQuery(true, new PageQuery(1, 20)))).isEqualTo(1L);
        assertThat(repository.findPage(10L, new NotificationQuery(true, new PageQuery(1, 20)))).hasSize(1);
        assertThat(repository.count(10L, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(2L);
        assertThat(repository.findPage(10L, new NotificationQuery(false, new PageQuery(1, 20)))).hasSize(2);
    }

    @Test
    void findPage_and_count_return_empty_when_userId_or_query_null() {
        assertThat(repository.findPage(null, new NotificationQuery(false, new PageQuery(1, 20)))).isEmpty();
        assertThat(repository.findPage(10L, null)).isEmpty();
        assertThat(repository.count(null, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(0L);
        assertThat(repository.count(10L, null)).isEqualTo(0L);
    }

    @Test
    void findPage_with_same_created_at_is_deterministic_across_pages() {
        LocalDateTime sameTime = LocalDateTime.now();
        Notification n1 = repository.save(newNotificationWithCreated(10L, NotificationType.ORDER_ACCEPTED, sameTime));
        Notification n2 = repository.save(newNotificationWithCreated(10L, NotificationType.REVIEW_RECEIVED, sameTime));
        Notification n3 = repository.save(newNotificationWithCreated(10L, NotificationType.STATUS_CHANGED, sameTime));
        Notification n4 = repository.save(newNotificationWithCreated(10L, NotificationType.ORDER_ACCEPTED, sameTime));

        NotificationQuery page1 = new NotificationQuery(false, new PageQuery(1, 2));
        NotificationQuery page2 = new NotificationQuery(false, new PageQuery(2, 2));

        List<Notification> first = repository.findPage(10L, page1);
        List<Notification> second = repository.findPage(10L, page2);

        List<Long> allIds = new ArrayList<>(first.stream().map(Notification::getId).toList());
        allIds.addAll(second.stream().map(Notification::getId).toList());
        assertThat(allIds).containsExactlyInAnyOrder(n1.getId(), n2.getId(), n3.getId(), n4.getId());
    }

    private static Notification newNotification(Long userId, NotificationType type) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(switch (type) {
            case ORDER_ACCEPTED -> "订单已接单";
            case STATUS_CHANGED -> "状态已变更";
            case REVIEW_RECEIVED -> "收到新评价";
            case REVIEW_REQUEST -> "需求待审核";
            case DEMAND_REJECTED -> "需求审核未通过";
            case DEMAND_APPROVED -> "需求审核已通过";
            case PENDING_REVIEW -> "待评价提醒";
            case ORDER_ARBITRATION_REQUESTED -> "订单申请仲裁";
            case ORDER_ARBITRATION_RESOLVED -> "订单仲裁已处理";
            case RESPONSE_REVIEW_RECEIVED -> "收到组队评价";
            case DEMAND_RESPONSE_RECEIVED -> "收到新留言";
        });
        notification.setContent(switch (type) {
            case ORDER_ACCEPTED -> "接单成功";
            case STATUS_CHANGED -> "状态已变更";
            case REVIEW_RECEIVED -> "有人评价了你";
            case REVIEW_REQUEST -> "有新的需求等待审核";
            case DEMAND_REJECTED -> "需求审核未通过";
            case DEMAND_APPROVED -> "需求审核已通过";
            case PENDING_REVIEW -> "您有未评价的订单";
            case ORDER_ARBITRATION_REQUESTED -> "有订单发起了仲裁";
            case ORDER_ARBITRATION_RESOLVED -> "订单仲裁结果已发布";
            case RESPONSE_REVIEW_RECEIVED -> "您的组队收到新评价";
            case DEMAND_RESPONSE_RECEIVED -> "有人在您的需求下提交了留言";
        });
        notification.setRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }

    private static Notification newNotificationWithCreated(Long userId, NotificationType type, LocalDateTime createdAt) {
        Notification notification = newNotification(userId, type);
        notification.setCreatedAt(createdAt);
        return notification;
    }
}
