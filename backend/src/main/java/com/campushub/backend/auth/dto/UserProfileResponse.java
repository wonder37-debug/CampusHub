package com.campushub.backend.auth.dto;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;

public record UserProfileResponse(
    Long id,
    String email,
    String studentId,
    String nickname,
    String avatarUrl,
    UserRole role,
    UserStatus status,
    int creditScore
) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
            user.getId(),
            user.getEmail(),
            user.getStudentId(),
            user.getNickname(),
            user.getAvatarUrl(),
            user.getRole(),
            user.getStatus(),
            user.getCreditScore()
        );
    }
}
