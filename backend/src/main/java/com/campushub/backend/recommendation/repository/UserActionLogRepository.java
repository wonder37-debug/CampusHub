package com.campushub.backend.recommendation.repository;

import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface UserActionLogRepository {

    /**
     * 保存用户行为日志。id 为空时视为新增，否则视为更新。
     */
    UserActionLog save(UserActionLog log);

    /**
     * 查询某个用户的全部行为日志。
     */
    List<UserActionLog> findByUserId(Long userId);

    /**
     * 查询某个用户指定动作类型的行为日志。
     */
    List<UserActionLog> findByUserIdAndActionType(Long userId, ActionType actionType);

    Set<Long> findActiveUserIdsByDate(LocalDate today);
}
