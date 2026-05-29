package com.campushub.backend.auth.dto;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record UserProfileResponse(
    Long id,
    String email,
    String studentId,
    String nickname,
    String avatarUrl,
    UserRole role,
    UserStatus status,
    int creditScore,
    BigDecimal balance,
    BigDecimal frozenBalance
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
            user.getCreditScore(),
            user.getBalance(),
            user.getFrozenBalance()
        );
    }

    @JsonProperty("credit_score")
    public int creditScoreSnakeCase() {
        return creditScore;
    }
}
