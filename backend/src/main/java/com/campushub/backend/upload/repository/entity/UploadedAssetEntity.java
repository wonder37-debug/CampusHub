package com.campushub.backend.upload.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("uploaded_asset")
public class UploadedAssetEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String filename;
    private String urlPath;
    private Long uploaderId;

    @TableField("is_private")
    private Boolean isPrivate;

    @TableField("bound_order_id")
    private Long boundOrderId;

    private LocalDateTime uploadedAt;

    public UploadedAssetEntity() {
    }

    public UploadedAssetEntity(String filename, String urlPath, Long uploaderId) {
        this(filename, urlPath, uploaderId, false, null);
    }

    public UploadedAssetEntity(String filename, String urlPath, Long uploaderId, boolean isPrivate, Long boundOrderId) {
        this.filename = filename;
        this.urlPath = urlPath;
        this.uploaderId = uploaderId;
        this.isPrivate = isPrivate;
        this.boundOrderId = boundOrderId;
        this.uploadedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getUrlPath() {
        return urlPath;
    }

    public void setUrlPath(String urlPath) {
        this.urlPath = urlPath;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
    }

    public Boolean getIsPrivate() {
        return isPrivate;
    }

    public void setIsPrivate(Boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    public Long getBoundOrderId() {
        return boundOrderId;
    }

    public void setBoundOrderId(Long boundOrderId) {
        this.boundOrderId = boundOrderId;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
