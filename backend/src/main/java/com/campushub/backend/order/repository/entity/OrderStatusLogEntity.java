package com.campushub.backend.order.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;

import java.time.LocalDateTime;

/**
 * ord_order_status_log 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段严格对照 init_schema.sql 中 ord_order_status_log 的真实列：
 * id / order_id / from_status / to_status / operator_id / note / changed_at。</p>
 *
 * <p>{@code from_status} 在领域中允许为空（首条流水代表订单初始状态），
 * 数据库定义同样允许为 NULL。</p>
 */
@TableName("ord_order_status_log")
public class OrderStatusLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("from_status")
    private String fromStatus;

    @TableField("to_status")
    private String toStatus;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("note")
    private String note;

    @TableField("changed_at")
    private LocalDateTime changedAt;

    public OrderStatusLogEntity() {
    }

    /** 领域流水条目 -> 持久化实体。 */
    public static OrderStatusLogEntity fromDomain(Long orderId, OrderStatusHistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        OrderStatusLogEntity entity = new OrderStatusLogEntity();
        entity.orderId = orderId;
        entity.fromStatus = entry.fromStatus() == null ? null : entry.fromStatus().name();
        entity.toStatus = entry.toStatus() == null ? null : entry.toStatus().name();
        entity.operatorId = entry.operatorId();
        entity.note = entry.note();
        entity.changedAt = entry.changedAt();
        return entity;
    }

    /** 持久化实体 -> 领域流水条目（record 不可变）。 */
    public OrderStatusHistoryEntry toDomain() {
        return new OrderStatusHistoryEntry(
            this.fromStatus == null ? null : OrderStatus.valueOf(this.fromStatus),
            this.toStatus == null ? null : OrderStatus.valueOf(this.toStatus),
            this.operatorId,
            this.note,
            this.changedAt
        );
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
