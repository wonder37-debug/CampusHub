package com.campushub.backend.demand.domain;

import java.util.Locale;

/**
 * 需求互动模式枚举。
 *
 * <p>DIRECT_ACCEPT：一人一单，沿用现有 Order 流程（EXPRESS/ERRAND）。
 * <p>SELECT_ONE：多人留言/报名，发布者选择一人后进入 Order（SECOND_HAND/STUDY_TUTORING）。
 * <p>SELECT_MANY：多人报名，发布者选择 N 人后立即完成（TEAM_UP）。
 * <p>HELP：多人回答，发布者采纳一个回答后完成，不创建 Order（HELP）。
 */
public enum InteractionMode {
    DIRECT_ACCEPT,
    SELECT_ONE,
    SELECT_MANY,
    HELP;

    public static InteractionMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return InteractionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 根据需求分类推导默认互动模式。OTHER 返回 null，表示需由用户在 publish 时传入 interactionMode。
     */
    public static InteractionMode resolve(DemandCategory category) {
        if (category == null) {
            return DIRECT_ACCEPT;
        }
        return switch (category) {
            case EXPRESS, ERRAND -> DIRECT_ACCEPT;
            case SECOND_HAND, STUDY_TUTORING -> SELECT_ONE;
            case TEAM_UP -> SELECT_MANY;
            case HELP -> HELP;
            case OTHER -> null;
        };
    }
}
