package com.campushub.backend.order.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Order {

    private Long id;
    private Long demandId;
    private Long publisherId;
    private Long accepterId;
    private OrderStatus status;
    private String acceptNote;
    private boolean proofSubmitted;
    private int proofImageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private List<OrderStatusHistoryEntry> statusHistory;

    public Order() {
        this.statusHistory = new ArrayList<>();
    }

    public Order(
        Long id,
        Long demandId,
        Long publisherId,
        Long accepterId,
        OrderStatus status,
        String acceptNote,
        boolean proofSubmitted,
        int proofImageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        List<OrderStatusHistoryEntry> statusHistory
    ) {
        this.id = id;
        this.demandId = demandId;
        this.publisherId = publisherId;
        this.accepterId = accepterId;
        this.status = status;
        this.acceptNote = acceptNote;
        this.proofSubmitted = proofSubmitted;
        this.proofImageCount = proofImageCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
        this.statusHistory = statusHistory == null ? new ArrayList<>() : new ArrayList<>(statusHistory);
    }

    public boolean isParticipant(Long userId) {
        return userId != null && (userId.equals(publisherId) || userId.equals(accepterId));
    }

    public void addHistory(OrderStatus fromStatus, OrderStatus toStatus, Long operatorId, String note, LocalDateTime changedAt) {
        this.statusHistory.add(new OrderStatusHistoryEntry(fromStatus, toStatus, operatorId, note, changedAt));
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

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getAcceptNote() {
        return acceptNote;
    }

    public void setAcceptNote(String acceptNote) {
        this.acceptNote = acceptNote;
    }

    public boolean isProofSubmitted() {
        return proofSubmitted;
    }

    public void setProofSubmitted(boolean proofSubmitted) {
        this.proofSubmitted = proofSubmitted;
    }

    public int getProofImageCount() {
        return proofImageCount;
    }

    public void setProofImageCount(int proofImageCount) {
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
        return new ArrayList<>(statusHistory);
    }

    public void setStatusHistory(List<OrderStatusHistoryEntry> statusHistory) {
        this.statusHistory = statusHistory == null ? new ArrayList<>() : new ArrayList<>(statusHistory);
    }
}
