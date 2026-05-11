package com.campushub.backend.auth.dto;

public record RegisterCommand(
    String email,
    String verificationCode,
    String studentId,
    String password,
    String nickname,
    String avatarUrl
) {
}
