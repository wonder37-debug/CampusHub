package com.campushub.backend.demand.domain;

import java.time.LocalDateTime;

/**
 * 需求响应领域模型。
 *
 * <p>统一承载留言/报名/回答，避免拆成 Comment/Application/Answer 三套实体。
 * 同一用户对同一 Demand 不能重复创建有效（PENDING/SELECTED）Response，
 * 由 Service 层校验 + DB 唯一索引 {@code uk_response_demand_author_active} 双重保证。
 */
public class DemandResponse {

    private Long id;
    private Long demandId;
    private Long authorId;
    private String content;
    private ResponseStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public DemandResponse() {
    }

    public DemandResponse(
        Long id,
        Long demandId,
        Long authorId,
        String content,
        ResponseStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.id = id;
        this.demandId = demandId;
        this.authorId = authorId;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public boolean isActive() {
        return status == ResponseStatus.PENDING || status == ResponseStatus.SELECTED;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDemandId() {
        return demandId;
    }

    public void setDemandId(Long demandId) {
        this.demandId = demandId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public ResponseStatus getStatus() {
        return status;
    }

    public void setStatus(ResponseStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
