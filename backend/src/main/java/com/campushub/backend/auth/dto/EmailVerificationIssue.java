package com.campushub.backend.auth.dto;

import java.time.Instant;

public record EmailVerificationIssue(
    String email,
    String verificationCode,
    long expiresInSeconds,
    Instant expiresAt
) {
}
