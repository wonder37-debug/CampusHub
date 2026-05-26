package com.campushub.backend.auth.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class User {

    private Long id;
    private String email;
    private String studentId;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private UserRole role;
    private UserStatus status;
    private int creditScore;
    private BigDecimal balance;
    private BigDecimal frozenBalance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public User() {
    }

    public User(
        Long id,
        String email,
        String studentId,
        String passwordHash,
        String nickname,
        String avatarUrl,
        UserRole role,
        UserStatus status,
        int creditScore,
        BigDecimal balance,
        BigDecimal frozenBalance,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.id = id;
        this.email = email;
        this.studentId = studentId;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.role = role;
        this.status = status;
        this.creditScore = creditScore;
        this.balance = balance;
        this.frozenBalance = frozenBalance;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public User(
        Long id,
        String email,
        String studentId,
        String passwordHash,
        String nickname,
        String avatarUrl,
        UserRole role,
        UserStatus status,
        int creditScore,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this(
            id,
            email,
            studentId,
            passwordHash,
            nickname,
            avatarUrl,
            role,
            status,
            creditScore,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            createdAt,
            updatedAt
        );
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

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public int getCreditScore() {
        return creditScore;
    }

    public void setCreditScore(int creditScore) {
        this.creditScore = creditScore;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getFrozenBalance() {
        return frozenBalance;
    }

    public void setFrozenBalance(BigDecimal frozenBalance) {
        this.frozenBalance = frozenBalance;
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
