package com.campushub.backend.order.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.campushub.backend.order.domain.ArbitrationSubmission;

import java.time.LocalDateTime;
import java.util.List;

@TableName(value = "ord_arbitration_submission", autoResultMap = true)
public class ArbitrationSubmissionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("submitter_id")
    private Long submitterId;

    @TableField("role")
    private String role;

    @TableField("reason")
    private String reason;

    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)
    private List<String> imageUrls;

    @TableField("image_count")
    private Integer imageCount;

    @TableField("submitted_at")
    private LocalDateTime submittedAt;

    public ArbitrationSubmissionEntity() {
    }

    public static ArbitrationSubmissionEntity fromDomain(ArbitrationSubmission submission) {
        ArbitrationSubmissionEntity entity = new ArbitrationSubmissionEntity();
        entity.id = submission.id();
        entity.orderId = submission.orderId();
        entity.submitterId = submission.submitterId();
        entity.role = submission.role();
        entity.reason = submission.reason();
        entity.imageUrls = submission.imageUrls();
        entity.imageCount = submission.imageCount();
        entity.submittedAt = submission.submittedAt();
        return entity;
    }

    public ArbitrationSubmission toDomain() {
        return new ArbitrationSubmission(
            this.id,
            this.orderId,
            this.submitterId,
            this.role,
            this.reason,
            this.imageUrls,
            this.imageCount == null ? 0 : this.imageCount,
            this.submittedAt
        );
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getSubmitterId() { return submitterId; }
    public void setSubmitterId(Long submitterId) { this.submitterId = submitterId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
    public Integer getImageCount() { return imageCount; }
    public void setImageCount(Integer imageCount) { this.imageCount = imageCount; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
}
