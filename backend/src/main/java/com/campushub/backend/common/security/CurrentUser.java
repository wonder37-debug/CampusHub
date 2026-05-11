package com.campushub.backend.common.security;

import com.campushub.backend.auth.domain.UserRole;

public record CurrentUser(Long userId, UserRole role) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
