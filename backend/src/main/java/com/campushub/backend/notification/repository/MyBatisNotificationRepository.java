package com.campushub.backend.notification.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.repository.entity.NotificationEntity;
import com.campushub.backend.notification.repository.mapper.NotificationMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 {@link NotificationRepository} 实现。
 *
 * <p>仅在 {@code local} profile 下激活，避免与默认内存仓储冲突。</p>
 *
 * <p>本仓储仅保证基本的查询与持久化正确。排序与分页由 Service 层
 * （{@link com.campushub.backend.notification.service.NotificationApplicationServiceImpl}）
 * 在内存中完成，DAO 层不做排序。</p>
 */
@Repository
@Profile("local")
public class MyBatisNotificationRepository implements NotificationRepository {

    private final NotificationMapper notificationMapper;

    public MyBatisNotificationRepository(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public Notification save(Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification must not be null");
        }
        NotificationEntity entity = NotificationEntity.fromDomain(notification);
        if (notification.getId() == null) {
            notificationMapper.insert(entity);
            notification.setId(entity.getId());
        } else {
            notificationMapper.updateById(entity);
        }
        return notification;
    }

    @Override
    public Optional<Notification> findById(Long notificationId) {
        if (notificationId == null) {
            return Optional.empty();
        }
        NotificationEntity entity = notificationMapper.selectById(notificationId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public List<Notification> findByUserId(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        List<NotificationEntity> entities = notificationMapper.selectList(
            new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getUserId, userId)
        );
        return entities.stream().map(NotificationEntity::toDomain).toList();
    }
}