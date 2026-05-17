package com.campushub.backend.review.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.review.domain.Review;

import java.time.LocalDateTime;

/**
 * ord_review 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段映射严格对照 init_schema.sql 中 ord_review 的真实列：
 * id / order_id / author_id / target_id / rating / comment / created_at。</p>
 *
 * <p>领域模型 {@link Review} 的全部 7 个字段均对应 ord_review 的真实列，
 * 无需 {@code @TableField(exist = false)} 虚拟字段。</p>
 *
 * <p>{@code rating} 在数据库为 {@code tinyint NOT NULL}（1-5 星打分），
 * 实体中以 {@code Integer} 持有，与 MyBatis-Plus 默认类型映射一致。</p>
 */
@TableName("ord_review")
public class ReviewEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("author_id")
    private Long authorId;

    @TableField("target_id")
    private Long targetId;

    /** 数据库为 tinyint NOT NULL（1-5 星），实体以 Integer 持有。 */
    @TableField("rating")
    private Integer rating;

    @TableField("comment")
    private String comment;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public ReviewEntity() {
    }

    /** 领域模型 -> 持久化实体。 */
    public static ReviewEntity fromDomain(Review review) {
        if (review == null) {
            return null;
        }
        ReviewEntity entity = new ReviewEntity();
        entity.id = review.getId();
        entity.orderId = review.getOrderId();
        entity.authorId = review.getAuthorId();
        entity.targetId = review.getTargetId();
        entity.rating = review.getRating();
        entity.comment = review.getComment();
        entity.createdAt = review.getCreatedAt();
        return entity;
    }

    /** 持久化实体 -> 领域模型。 */
    public Review toDomain() {
        Review review = new Review();
        review.setId(this.id);
        review.setOrderId(this.orderId);
        review.setAuthorId(this.authorId);
        review.setTargetId(this.targetId);
        review.setRating(this.rating == null ? 0 : this.rating);
        review.setComment(this.comment);
        review.setCreatedAt(this.createdAt);
        return review;
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

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}