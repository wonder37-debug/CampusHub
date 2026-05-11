package com.campushub.backend.notification.domain;

import java.time.LocalDateTime;

public class Notification {

    private Long id;
    private Long userId;
    private NotificationType type;
    private String title;
    private String content;
    private boolean read;
    private Long relatedId;
    private LocalDateTime createdAt;

    public Notification() {
    }

    public Notification(
        Long id,
        Long userId,
        NotificationType type,
        String title,
        String content,
        boolean read,
        Long relatedId,
        LocalDateTime createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.read = read;
        this.relatedId = relatedId;
        this.createdAt = createdAt;
    }

    public void markAsRead() {
        this.read = true;
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

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
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
