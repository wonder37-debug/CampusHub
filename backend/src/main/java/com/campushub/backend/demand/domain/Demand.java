package com.campushub.backend.demand.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Demand {

    private Long id;
    private Long publisherId;
    private String publisherDisplayName;
    private String title;
    private String description;
    private String note;
    private DemandCategory category;
    private CampusZone campusZone;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal reward;
    private List<String> tags;
    private List<String> images;
    private String contactInfo;
    private DemandStatus status;
    private boolean isApproved;
    private boolean anonymous;
    private String anonymousCode;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Demand() {
        this.tags = new ArrayList<>();
        this.images = new ArrayList<>();
    }

    public Demand(
        Long id,
        Long publisherId,
        String publisherDisplayName,
        String title,
        String description,
        String note,
        DemandCategory category,
        CampusZone campusZone,
        String location,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal reward,
        List<String> tags,
        List<String> images,
        String contactInfo,
        DemandStatus status,
        boolean isApproved,
        boolean anonymous,
        String anonymousCode,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String reviewReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.id = id;
        this.publisherId = publisherId;
        this.publisherDisplayName = publisherDisplayName;
        this.title = title;
        this.description = description;
        this.note = note;
        this.category = category;
        this.campusZone = campusZone;
        this.location = location;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reward = reward;
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
        this.contactInfo = contactInfo;
        this.status = status;
        this.isApproved = isApproved;
        this.anonymous = anonymous;
        this.anonymousCode = anonymousCode;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.reviewReason = reviewReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public boolean isEditableBy(Long operatorId) {
        return publisherId != null && publisherId.equals(operatorId)
            && status != DemandStatus.COMPLETED
            && status != DemandStatus.CANCELLED
            && status != DemandStatus.EXPIRED;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(Long publisherId) {
        this.publisherId = publisherId;
    }

    public String getPublisherDisplayName() {
        return publisherDisplayName;
    }

    public void setPublisherDisplayName(String publisherDisplayName) {
        this.publisherDisplayName = publisherDisplayName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public DemandCategory getCategory() {
        return category;
    }

    public void setCategory(DemandCategory category) {
        this.category = category;
    }

    public CampusZone getCampusZone() {
        return campusZone;
    }

    public void setCampusZone(CampusZone campusZone) {
        this.campusZone = campusZone;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public BigDecimal getReward() {
        return reward;
    }

    public void setReward(BigDecimal reward) {
        this.reward = reward;
    }

    public List<String> getTags() {
        return new ArrayList<>(tags);
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public List<String> getImages() {
        return new ArrayList<>(images);
    }

    public void setImages(List<String> images) {
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
    }

    public String getContactInfo() {
        return contactInfo;
    }

    public void setContactInfo(String contactInfo) {
        this.contactInfo = contactInfo;
    }

    public DemandStatus getStatus() {
        return status;
    }

    public void setStatus(DemandStatus status) {
        this.status = status;
    }

    public boolean getIsApproved() {
        return isApproved;
    }

    public void setIsApproved(boolean isApproved) {
        this.isApproved = isApproved;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public void setAnonymous(boolean anonymous) {
        this.anonymous = anonymous;
    }

    public String getAnonymousCode() {
        return anonymousCode;
    }

    public void setAnonymousCode(String anonymousCode) {
        this.anonymousCode = anonymousCode;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
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
