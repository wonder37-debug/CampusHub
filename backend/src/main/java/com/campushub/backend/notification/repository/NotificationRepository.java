package com.campushub.backend.notification.repository;

import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.dto.NotificationQuery;
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

    /**
     * 按用户与查询条件分页查询通知（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param userId 用户 ID，为 null 时返回空列表
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Notification> findPage(Long userId, NotificationQuery query);

    /**
     * 按用户与查询条件统计匹配的通知总数（过滤下推 SQL，用于分页 total）。
     *
     * @param userId 用户 ID，为 null 时返回 0
     * @param query 查询条件，为 null 时返回 0
     */
    long count(Long userId, NotificationQuery query);
}
