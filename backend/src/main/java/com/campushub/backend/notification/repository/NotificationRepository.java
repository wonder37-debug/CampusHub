package com.campushub.backend.notification.repository;

import com.campushub.backend.notification.domain.Notification;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository {

    /**
     * 保存通知。id 为空时视为新增，否则视为更新。
     */
    Notification save(Notification notification);

    /**
     * 按主键查询通知，不存在时返回空。
     */
    Optional<Notification> findById(Long notificationId);

    /**
     * 查询某个用户收到的全部通知，排序由服务层控制。
     */
    List<Notification> findByUserId(Long userId);
}
