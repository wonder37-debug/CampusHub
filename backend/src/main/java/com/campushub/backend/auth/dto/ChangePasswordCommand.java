package com.campushub.backend.auth.dto;

public record ChangePasswordCommand(
    String oldPassword,
    String newPassword
) {
}
