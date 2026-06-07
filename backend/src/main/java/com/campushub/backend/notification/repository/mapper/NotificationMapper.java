package com.campushub.backend.notification.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.notification.repository.entity.NotificationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_notification 表的 MyBatis-Plus Mapper 接口。
 *
 * <p>继承 {@link BaseMapper} 即可获得 insert / updateById / selectById / selectList
 * 等标准 CRUD 方法，无需手写 XML 或注解 SQL。</p>
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {
}