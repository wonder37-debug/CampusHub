package com.campushub.backend.demand.domain;

import java.util.Arrays;

public enum DemandCategory {
    EXPRESS("取快递"),
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
        return Arrays.stream(values()).anyMatch(category -> category.name().equals(value));
    }
}
