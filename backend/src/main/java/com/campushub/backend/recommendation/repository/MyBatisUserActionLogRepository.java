package com.campushub.backend.recommendation.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.repository.entity.UserActionLogEntity;
import com.campushub.backend.recommendation.repository.mapper.UserActionLogMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis-Plus 的 {@link UserActionLogRepository} 实现。
 *
 * <p>仅在 {@code local} profile 下激活，避免与默认内存仓储冲突。</p>
 *
 * <p>rec_user_action_log 是推荐系统行为日志表，业务上以追加写入为主；
 * 本实现仍保留 id 存在时 updateById 的通用仓储契约，便于测试与一致性维护。</p>
 */
@Repository
@Profile("local")
public class MyBatisUserActionLogRepository implements UserActionLogRepository {

    private final UserActionLogMapper userActionLogMapper;

    public MyBatisUserActionLogRepository(UserActionLogMapper userActionLogMapper) {
        this.userActionLogMapper = userActionLogMapper;
    }

    @Override
    public UserActionLog save(UserActionLog log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        UserActionLogEntity entity = UserActionLogEntity.fromDomain(log);
        if (log.getId() == null) {
            userActionLogMapper.insert(entity);
            log.setId(entity.getId());
        } else {
            userActionLogMapper.updateById(entity);
        }
        return log;
    }

    @Override
    public List<UserActionLog> findByUserId(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        List<UserActionLogEntity> entities = userActionLogMapper.selectList(
            new LambdaQueryWrapper<UserActionLogEntity>()
                .eq(UserActionLogEntity::getUserId, userId)
        );
        return entities.stream().map(UserActionLogEntity::toDomain).toList();
    }

    @Override
    public List<UserActionLog> findByUserIdAndActionType(Long userId, ActionType actionType) {
        if (userId == null || actionType == null) {
            return new ArrayList<>();
        }
        List<UserActionLogEntity> entities = userActionLogMapper.selectList(
            new LambdaQueryWrapper<UserActionLogEntity>()
                .eq(UserActionLogEntity::getUserId, userId)
                .eq(UserActionLogEntity::getActionType, actionType)
        );
        return entities.stream().map(UserActionLogEntity::toDomain).toList();
    }
}
