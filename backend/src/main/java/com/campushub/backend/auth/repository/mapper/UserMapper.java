package com.campushub.backend.auth.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.auth.repository.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user 表的 MyBatis-Plus Mapper。
 *
 * <p>仅提供数据访问能力，业务规则与权限判断仍在服务层完成。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
