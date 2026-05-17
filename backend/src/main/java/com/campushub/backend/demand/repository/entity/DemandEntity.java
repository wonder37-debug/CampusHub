package com.campushub.backend.demand.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.repository.handler.CommaSeparatedStringTypeHandler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ord_demand 表的 MyBatis-Plus 持久化实体。
 *
 * <p>字段映射说明：
 * <ul>
 *   <li>{@code tags}：数据库为 {@code varchar(500)} 逗号分隔字符串，
 *       通过 {@link CommaSeparatedStringTypeHandler} 与 {@code List<String>} 自动转换。</li>
 *   <li>{@code category / status / campus_zone}：数据库存储枚举的英文名，
 *       实体中以 {@code String} 持有，转换时与领域枚举互转。</li>
 *   <li>{@code receiverId}：数据库 ord_demand 无此列，标记 {@code exist=false}；
 *       该字段由 Order 模块管理，Demand 侧不持久化。</li>
 * </ul>
 */
@TableName(value = "ord_demand", autoResultMap = true)
public class DemandEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("publisher_id")
    private Long publisherId;

    /** 数据库无此列；receiver_id 由 Order 模块管理，Demand 侧不持久化。 */
    @TableField(value = "receiver_id", exist = false)
    private Long receiverId;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("category")
    private String category;

    @TableField(value = "campus_zone")
    private String campusZone;

    @TableField("location")
    private String location;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("reward")
    private BigDecimal reward;

    @TableField(value = "tags", typeHandler = CommaSeparatedStringTypeHandler.class)
    private List<String> tags;

    @TableField("status")
    private String status;

    @TableField("note")
    private String note;

    @TableField("anonymous")
    private Boolean anonymous;

    @TableField("anonymous_code")
    private String anonymousCode;

    @TableField("publisher_display_name")
    private String publisherDisplayName;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public DemandEntity() {
        this.tags = new ArrayList<>();
    }

    /** 领域模型 -> 持久化实体。 */
    public static DemandEntity fromDomain(Demand demand) {
        if (demand == null) {
            return null;
        }
        DemandEntity entity = new DemandEntity();
        entity.id = demand.getId();
        entity.publisherId = demand.getPublisherId();
        entity.receiverId = null; // receiverId 在 Demand 领域模型中不存在，由 Order 模块管理
        entity.publisherDisplayName = demand.getPublisherDisplayName();
        entity.title = demand.getTitle();
        entity.description = demand.getDescription();
        entity.category = demand.getCategory() == null ? null : demand.getCategory().name();
        entity.campusZone = demand.getCampusZone() == null ? null : demand.getCampusZone().name();
        entity.location = demand.getLocation();
        entity.startTime = demand.getStartTime();
        entity.endTime = demand.getEndTime();
        entity.reward = demand.getReward();
        entity.tags = demand.getTags();
        entity.status = demand.getStatus() == null ? null : demand.getStatus().name();
        entity.anonymous = demand.isAnonymous();
        entity.anonymousCode = demand.getAnonymousCode();
        entity.note = null; // note 属于接单留言，领域模型不含此字段
        entity.createdAt = demand.getCreatedAt();
        entity.updatedAt = demand.getUpdatedAt();
        return entity;
    }

    /** 持久化实体 -> 领域模型。 */
    public Demand toDomain() {
        Demand demand = new Demand();
        demand.setId(this.id);
        demand.setPublisherId(this.publisherId);
        demand.setPublisherDisplayName(this.publisherDisplayName);
        demand.setTitle(this.title);
        demand.setDescription(this.description);
        demand.setCategory(this.category == null ? null : DemandCategory.valueOf(this.category));
        demand.setCampusZone(this.campusZone == null ? null : CampusZone.valueOf(this.campusZone));
        demand.setLocation(this.location);
        demand.setStartTime(this.startTime);
        demand.setEndTime(this.endTime);
        demand.setReward(this.reward);
        demand.setTags(this.tags == null ? new ArrayList<>() : new ArrayList<>(this.tags));
        demand.setStatus(this.status == null ? null : DemandStatus.valueOf(this.status));
        demand.setAnonymous(this.anonymous != null && this.anonymous);
        demand.setAnonymousCode(this.anonymousCode);
        demand.setCreatedAt(this.createdAt);
        demand.setUpdatedAt(this.updatedAt);
        return demand;
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

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCampusZone() {
        return campusZone;
    }

    public void setCampusZone(String campusZone) {
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
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Boolean getAnonymous() {
        return anonymous;
    }

    public void setAnonymous(Boolean anonymous) {
        this.anonymous = anonymous;
    }

    public String getAnonymousCode() {
        return anonymousCode;
    }

    public void setAnonymousCode(String anonymousCode) {
        this.anonymousCode = anonymousCode;
    }

    public String getPublisherDisplayName() {
        return publisherDisplayName;
    }

    public void setPublisherDisplayName(String publisherDisplayName) {
        this.publisherDisplayName = publisherDisplayName;
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
