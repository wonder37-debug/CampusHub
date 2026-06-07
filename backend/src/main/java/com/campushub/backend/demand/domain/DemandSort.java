package com.campushub.backend.demand.domain;

import java.util.Locale;

public enum DemandSort {
    TIME,
    DISTANCE,
    REWARD,
    RECOMMEND;

    public static DemandSort fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TIME", "TIME_RECENT", "RECENT", "LATEST" -> TIME;
            case "DISTANCE", "NEAREST" -> DISTANCE;
            case "REWARD", "REWARD_DESC", "HIGHEST_REWARD" -> REWARD;
            case "RECOMMEND", "RECOMMENDED" -> RECOMMEND;
            default -> throw new IllegalArgumentException("unsupported demand sort: " + value);
        };
    }
}
