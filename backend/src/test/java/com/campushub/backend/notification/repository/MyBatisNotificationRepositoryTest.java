package com.campushub.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.domain.NotificationType;
import java.time.LocalDateTime;
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
        });
        notification.setRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }
}
