package com.campushub.backend.recommendation.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import java.time.LocalDateTime;

/**
 * rec_user_action_log 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段映射严格对照 init_schema.sql 中 rec_user_action_log 的真实列：
 * id / user_id / action_type / demand_id / category / created_at。</p>
 *
 * <p>{@code action_type / category} 在数据库为 varchar 列，实体中以枚举持有，
 * MyBatis-Plus 默认以 {@code enum.name()} 存入 varchar 列。</p>
 */
@TableName("rec_user_action_log")
public class UserActionLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("action_type")
    private ActionType actionType;

    @TableField("demand_id")
    private Long demandId;

    @TableField("category")
    private DemandCategory category;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public UserActionLogEntity() {
    }

    /** 领域模型 -> 持久化实体。 */
    public static UserActionLogEntity fromDomain(UserActionLog log) {
        if (log == null) {
            return null;
        }
        UserActionLogEntity entity = new UserActionLogEntity();
        entity.id = log.getId();
        entity.userId = log.getUserId();
        entity.actionType = log.getActionType();
        entity.demandId = log.getDemandId();
        entity.category = log.getCategory();
        entity.createdAt = log.getCreatedAt();
        return entity;
    }

    /** 持久化实体 -> 领域模型。 */
    public UserActionLog toDomain() {
        UserActionLog log = new UserActionLog();
        log.setId(this.id);
        log.setUserId(this.userId);
        log.setActionType(this.actionType);
        log.setDemandId(this.demandId);
        log.setCategory(this.category);
        log.setCreatedAt(this.createdAt);
        return log;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
    }

    public Long getDemandId() {
        return demandId;
    }

    public void setDemandId(Long demandId) {
        this.demandId = demandId;
    }

    public DemandCategory getCategory() {
        return category;
    }

    public void setCategory(DemandCategory category) {
        this.category = category;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
