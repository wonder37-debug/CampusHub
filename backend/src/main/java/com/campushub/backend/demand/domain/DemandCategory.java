package com.campushub.backend.demand.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public enum DemandCategory {
    EXPRESS("跑腿代取"),
    ERRAND("委托代办"),
    STUDY_TUTORING("学习辅导"),
    SECOND_HAND("二手交易"),
    TEAM_UP("活动组队"),
    OTHER("其他");

    private final String label;

    DemandCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static boolean supports(String value) {
        return fromValue(value) != null;
    }

    public static DemandCategory fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(category -> category.name().equals(normalized)
                || category.getLabel().equals(value.trim())
                || legacyAliases(category).contains(normalized))
            .findFirst()
            .orElse(null);
    }

    private static Set<String> legacyAliases(DemandCategory category) {
        return switch (category) {
            case EXPRESS -> Set.of("TAKE_EXPRESS", "PICKUP_EXPRESS");
            case ERRAND -> Set.of("RUN_ERRAND", "COMMISSION", "COMMISSION_TASK");
            default -> Set.of();
        };
    }
}
