package com.campushub.backend.recommendation.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.recommendation.repository.entity.UserActionLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * rec_user_action_log 表的 MyBatis-Plus Mapper。
 */
@Mapper
public interface UserActionLogMapper extends BaseMapper<UserActionLogEntity> {
}
