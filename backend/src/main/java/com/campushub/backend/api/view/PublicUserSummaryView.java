package com.campushub.backend.api.view;

import com.campushub.backend.auth.domain.User;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 公开用户摘要视图：仅包含业务真正需要向普通用户公开的字段。
 *
 * <p>email / studentId / balance / frozenBalance 等敏感信息只允许在本人或管理员接口
 * （{@link com.campushub.backend.auth.dto.UserProfileResponse} / {@link UserSummaryView}）中出现，
 * 不进入 Demand / Order / Review 等公开接口，从后端 API 数据源头避免隐私泄露。</p>
 */
public record PublicUserSummaryView(
    Long id,
    String nickname,
    String avatarUrl,
    String role,
    String status,
    int creditScore
) {

    public static PublicUserSummaryView from(User user) {
        if (user == null) {
            return null;
        }
        return new PublicUserSummaryView(
            user.getId(),
            user.getNickname(),
            user.getAvatarUrl(),
            user.getRole().name(),
            user.getStatus().name(),
            user.getCreditScore()
        );
    }

    @JsonProperty("credit_score")
    public int creditScoreSnakeCase() {
        return creditScore;
    }
}
