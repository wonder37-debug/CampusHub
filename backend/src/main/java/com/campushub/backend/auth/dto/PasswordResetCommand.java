package com.campushub.backend.auth.dto;

public record PasswordResetCommand(
    String email,
    String verificationCode,
    String newPassword
) {
}
