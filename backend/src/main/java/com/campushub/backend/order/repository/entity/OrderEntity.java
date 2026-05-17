package com.campushub.backend.order.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ord_order 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段映射严格对照 init_schema.sql 中 ord_order 的真实列：
 * id / demand_id / publisher_id / accepter_id / status / accept_note /
 * proof_submitted / proof_image_count / created_at / updated_at / completed_at。</p>
 *
 * <p>领域模型 {@link Order} 中的 {@code statusHistory} 列表不是 ord_order 的列，
 * 由 ord_order_status_log 表独立持久化，因此本实体保留同名字段但标记
 * {@code @TableField(exist = false)}，仅作为内存中拼装的中转容器。</p>
 */
@TableName("ord_order")
public class OrderEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("demand_id")
    private Long demandId;

    @TableField("publisher_id")
    private Long publisherId;

    @TableField("accepter_id")
    private Long accepterId;

    /** 数据库存储 OrderStatus 枚举的英文名。 */
    @TableField("status")
    private String status;

    @TableField("accept_note")
    private String acceptNote;

    @TableField("proof_submitted")
    private Boolean proofSubmitted;

    @TableField("proof_image_count")
    private Integer proofImageCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 状态流水：数据库无该列，由 ord_order_status_log 单独管理，仅供拼装回传。 */
    @TableField(exist = false)
    private List<OrderStatusHistoryEntry> statusHistory = new ArrayList<>();

    public OrderEntity() {
    }

    /** 领域模型 -> 持久化实体（不含流水，流水由仓储层另行写入流水表）。 */
    public static OrderEntity fromDomain(Order order) {
        if (order == null) {
            return null;
        }
        OrderEntity entity = new OrderEntity();
        entity.id = order.getId();
        entity.demandId = order.getDemandId();
        entity.publisherId = order.getPublisherId();
        entity.accepterId = order.getAccepterId();
        entity.status = order.getStatus() == null ? null : order.getStatus().name();
        entity.acceptNote = order.getAcceptNote();
        entity.proofSubmitted = order.isProofSubmitted();
        entity.proofImageCount = order.getProofImageCount();
        entity.createdAt = order.getCreatedAt();
        entity.updatedAt = order.getUpdatedAt();
        entity.completedAt = order.getCompletedAt();
        entity.statusHistory = order.getStatusHistory();
        return entity;
    }

    /**
     * 持久化实体 -> 领域模型；流水列表需要由调用方在外部填充后传入。
     *
     * @param history 已经从 ord_order_status_log 查出的有序流水（按 changed_at 升序）
     */
    public Order toDomain(List<OrderStatusHistoryEntry> history) {
        Order order = new Order();
        order.setId(this.id);
        order.setDemandId(this.demandId);
        order.setPublisherId(this.publisherId);
        order.setAccepterId(this.accepterId);
        order.setStatus(this.status == null ? null : OrderStatus.valueOf(this.status));
        order.setAcceptNote(this.acceptNote);
        order.setProofSubmitted(this.proofSubmitted != null && this.proofSubmitted);
        order.setProofImageCount(this.proofImageCount == null ? 0 : this.proofImageCount);
        order.setCreatedAt(this.createdAt);
        order.setUpdatedAt(this.updatedAt);
        order.setCompletedAt(this.completedAt);
        order.setStatusHistory(history == null ? new ArrayList<>() : history);
        return order;
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

    public Long getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(Long publisherId) {
        this.publisherId = publisherId;
    }

    public Long getAccepterId() {
        return accepterId;
    }

    public void setAccepterId(Long accepterId) {
        this.accepterId = accepterId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAcceptNote() {
        return acceptNote;
    }

    public void setAcceptNote(String acceptNote) {
        this.acceptNote = acceptNote;
    }

    public Boolean getProofSubmitted() {
        return proofSubmitted;
    }

    public void setProofSubmitted(Boolean proofSubmitted) {
        this.proofSubmitted = proofSubmitted;
    }

    public Integer getProofImageCount() {
        return proofImageCount;
    }

    public void setProofImageCount(Integer proofImageCount) {
        this.proofImageCount = proofImageCount;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public List<OrderStatusHistoryEntry> getStatusHistory() {
        return statusHistory;
    }

    public void setStatusHistory(List<OrderStatusHistoryEntry> statusHistory) {
        this.statusHistory = statusHistory == null ? new ArrayList<>() : statusHistory;
    }
}
