package com.campushub.backend.demand.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.demand.domain.DemandResponse;
import com.campushub.backend.demand.domain.ResponseStatus;

import java.time.LocalDateTime;

/**
 * ord_demand_response 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段映射：id / demand_id / author_id / content / status / created_at / updated_at。
 * status 存储枚举英文名，与 {@link ResponseStatus} 互转。
 */
@TableName("ord_demand_response")
public class DemandResponseEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("demand_id")
    private Long demandId;

    @TableField("author_id")
    private Long authorId;

    @TableField("content")
    private String content;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public DemandResponseEntity() {
    }

    /** 领域模型 -> 持久化实体。 */
    public static DemandResponseEntity fromDomain(DemandResponse response) {
        if (response == null) {
            return null;
        }
        DemandResponseEntity entity = new DemandResponseEntity();
        entity.id = response.getId();
        entity.demandId = response.getDemandId();
        entity.authorId = response.getAuthorId();
        entity.content = response.getContent();
        entity.status = response.getStatus() == null ? null : response.getStatus().name();
        entity.createdAt = response.getCreatedAt();
        entity.updatedAt = response.getUpdatedAt();
        return entity;
    }

    /** 持久化实体 -> 领域模型。 */
    public DemandResponse toDomain() {
        DemandResponse response = new DemandResponse();
        response.setId(this.id);
        response.setDemandId(this.demandId);
        response.setAuthorId(this.authorId);
        response.setContent(this.content);
        response.setStatus(this.status == null ? ResponseStatus.PENDING : ResponseStatus.valueOf(this.status));
        response.setCreatedAt(this.createdAt);
        response.setUpdatedAt(this.updatedAt);
        return response;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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
