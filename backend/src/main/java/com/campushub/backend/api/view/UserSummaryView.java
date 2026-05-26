package com.campushub.backend.api.view;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.dto.UserProfileResponse;

import java.math.BigDecimal;

public record UserSummaryView(
    Long id,
    String email,
    String studentId,
    String nickname,
    String avatarUrl,
    String role,
    String status,
    int creditScore,
    BigDecimal balance,
    BigDecimal frozenBalance
) {

    public static UserSummaryView from(UserProfileResponse profile) {
        return new UserSummaryView(
            profile.id(),
            profile.email(),
            profile.studentId(),
            profile.nickname(),
            profile.avatarUrl(),
            profile.role().name(),
            profile.status().name(),
            profile.creditScore(),
            profile.balance(),
            profile.frozenBalance()
        );
    }

    public static UserSummaryView from(User user) {
        return new UserSummaryView(
            user.getId(),
            user.getEmail(),
            user.getStudentId(),
            user.getNickname(),
            user.getAvatarUrl(),
            user.getRole().name(),
            user.getStatus().name(),
            user.getCreditScore(),
            user.getBalance(),
            user.getFrozenBalance()
        );
    }
}
