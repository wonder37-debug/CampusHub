package com.campushub.backend.recommendation.domain;

import com.campushub.backend.demand.domain.DemandCategory;
import java.time.LocalDateTime;

public class UserActionLog {

    private Long id;
    private Long userId;
    private ActionType actionType;
    private Long demandId;
    private DemandCategory category;
    private LocalDateTime createdAt;

    public UserActionLog() {
    }

    public UserActionLog(
        Long id,
        Long userId,
        ActionType actionType,
        Long demandId,
        DemandCategory category,
        LocalDateTime createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.actionType = actionType;
        this.demandId = demandId;
        this.category = category;
        this.createdAt = createdAt;
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
