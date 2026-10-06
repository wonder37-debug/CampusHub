package com.campushub.backend.recommendation.repository;

import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * 判断指定用户在某时间点之后是否已对某需求产生过 VIEW 行为。
     *
     * <p>用于 recordView 去重的 bounded/exists 查询，避免加载用户全部 VIEW 历史。
     * 查询条件：user_id + action_type=VIEW + demand_id + created_at >= since。</p>
     */
    boolean existsRecentView(Long userId, Long demandId, LocalDateTime since);

    Set<Long> findActiveUserIdsByDate(LocalDate today);
}
