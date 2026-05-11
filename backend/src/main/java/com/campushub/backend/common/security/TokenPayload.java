package com.campushub.backend.common.security;

import com.campushub.backend.auth.domain.UserRole;
import java.time.Instant;

public record TokenPayload(Long userId, UserRole role, Instant expiresAt) {
}
