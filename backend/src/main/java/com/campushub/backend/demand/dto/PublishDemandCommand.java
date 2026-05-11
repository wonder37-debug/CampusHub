package com.campushub.backend.demand.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PublishDemandCommand(
    String title,
    String description,
    String category,
    String campusZone,
    String location,
    LocalDateTime startTime,
    LocalDateTime endTime,
    BigDecimal reward,
    List<String> tags,
    boolean anonymous
) {
}
