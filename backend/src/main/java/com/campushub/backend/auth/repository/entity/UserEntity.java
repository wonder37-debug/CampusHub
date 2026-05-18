package com.campushub.backend.auth.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;

import java.time.LocalDateTime;

/**
 * sys_user 表的 MyBatis-Plus 持久化实体。
 *
 * <p>仅承载与领域模型 {@link User} 对应的字段；数据库中存在但当前领域模型尚未使用的列
 * （balance / frozen_balance / email_verified_at）此处不声明，由数据库默认值兜底。</p>
 */
@TableName("sys_user")
public class UserEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("email")
    private String email;

    @TableField("student_id")
    private String studentId;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("nickname")
    private String nickname;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("role")
    private String role;

    @TableField("status")
    private String status;

    @TableField("credit_score")
    private Integer creditScore;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public UserEntity() {
    }

    /** 领域模型 -> 持久化实体。 */
    public static UserEntity fromDomain(User user) {
        if (user == null) {
            return null;
        }
        UserEntity entity = new UserEntity();
        entity.id = user.getId();
        entity.email = user.getEmail();
        entity.studentId = user.getStudentId();
        entity.passwordHash = user.getPasswordHash();
        entity.nickname = user.getNickname();
        entity.avatarUrl = user.getAvatarUrl();
        entity.role = user.getRole() == null ? null : user.getRole().name();
        entity.status = user.getStatus() == null ? null : user.getStatus().name();
        entity.creditScore = user.getCreditScore();
        entity.createdAt = user.getCreatedAt();
        entity.updatedAt = user.getUpdatedAt();
        return entity;
    }

    /** 持久化实体 -> 领域模型。 */
    public User toDomain() {
        User user = new User();
        user.setId(this.id);
        user.setEmail(this.email);
        user.setStudentId(this.studentId);
        user.setPasswordHash(this.passwordHash);
        user.setNickname(this.nickname);
        user.setAvatarUrl(this.avatarUrl);
        user.setRole(this.role == null ? null : UserRole.valueOf(this.role));
        user.setStatus(this.status == null ? null : UserStatus.valueOf(this.status));
        user.setCreditScore(this.creditScore == null ? 0 : this.creditScore);
        user.setCreatedAt(this.createdAt);
        user.setUpdatedAt(this.updatedAt);
        return user;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCreditScore() {
        return creditScore;
    }

    public void setCreditScore(Integer creditScore) {
        this.creditScore = creditScore;
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
