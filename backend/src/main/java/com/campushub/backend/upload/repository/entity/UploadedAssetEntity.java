package com.campushub.backend.upload.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("uploaded_asset")
public class UploadedAssetEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String filename;
    private Long uploaderId;
    private LocalDateTime uploadedAt;

    public UploadedAssetEntity() {
    }

    public UploadedAssetEntity(String filename, Long uploaderId) {
        this.filename = filename;
        this.uploaderId = uploaderId;
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

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
