package com.campushub.backend.notification.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.notification.domain.Notification;
import com.campushub.backend.notification.domain.NotificationType;

import java.time.LocalDateTime;

/**
 * sys_notification 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段映射严格对照 init_schema.sql 中 sys_notification 的真实列：
 * id / user_id / type / title / content / is_read / related_id / created_at。</p>
 *
 * <p>领域模型 {@link Notification} 的全部 8 个字段均对应 sys_notification 的真实列，
 * 无需 {@code @TableField(exist = false)} 虚拟字段。</p>
 *
 * <p>{@code is_read} 在数据库为 {@code tinyint(1) NOT NULL DEFAULT 0}（0 未读 / 1 已读），
 * 实体中以 {@code Boolean} 持有，字段名为 {@code isRead}（Java boolean 命名惯例），
 * 通过 {@code @TableField("is_read")} 显式映射列名。</p>
 *
 * <p>{@code type} 在数据库为 {@code varchar(32) NOT NULL}，
 * 实体中以 {@link NotificationType} 枚举持有，MyBatis-Plus 默认以 {@code enum.name()}
 * 存入 varchar 列。</p>
 */
@TableName("sys_notification")
public class NotificationEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("type")
    private NotificationType type;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    /** 数据库为 tinyint(1) NOT NULL DEFAULT 0，实体以 Boolean 持有，显式映射列名 is_read。 */
    @TableField("is_read")
    private Boolean isRead;

    @TableField("related_id")
    private Long relatedId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public NotificationEntity() {
    }

    /** 领域模型 -> 持久化实体。 */
    public static NotificationEntity fromDomain(Notification notification) {
        if (notification == null) {
            return null;
        }
        NotificationEntity entity = new NotificationEntity();
        entity.id = notification.getId();
        entity.userId = notification.getUserId();
        entity.type = notification.getType();
        entity.title = notification.getTitle();
        entity.content = notification.getContent();
        entity.isRead = notification.isRead();
        entity.relatedId = notification.getRelatedId();
        entity.createdAt = notification.getCreatedAt();
        return entity;
    }

    /** 持久化实体 -> 领域模型。 */
    public Notification toDomain() {
        Notification notification = new Notification();
        notification.setId(this.id);
        notification.setUserId(this.userId);
        notification.setType(this.type);
        notification.setTitle(this.title);
        notification.setContent(this.content);
        notification.setRead(this.isRead == null ? false : this.isRead);
        notification.setRelatedId(this.relatedId);
        notification.setCreatedAt(this.createdAt);
        return notification;
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

    public NotificationType getType() {
        return type;
    }

    public void setType(NotificationType type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    public Long getRelatedId() {
        return relatedId;
    }

    public void setRelatedId(Long relatedId) {
        this.relatedId = relatedId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}