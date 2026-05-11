package com.campushub.backend.demand.dto;

import com.campushub.backend.demand.domain.Demand;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DemandSummaryResponse(
    Long id,
    String title,
    String category,
    String campusZone,
    String location,
    BigDecimal reward,
    String publisherDisplayName,
    String anonymousCode,
    boolean anonymous,
    String status,
    LocalDateTime startTime,
    LocalDateTime endTime,
    LocalDateTime createdAt,
    List<String> tags
) {

    public static DemandSummaryResponse from(Demand demand) {
        return new DemandSummaryResponse(
            demand.getId(),
            demand.getTitle(),
            demand.getCategory().name(),
            demand.getCampusZone().name(),
            demand.getLocation(),
            demand.getReward(),
            demand.isAnonymous() ? demand.getAnonymousCode() : demand.getPublisherDisplayName(),
            demand.getAnonymousCode(),
            demand.isAnonymous(),
            demand.getStatus().name(),
            demand.getStartTime(),
            demand.getEndTime(),
            demand.getCreatedAt(),
            demand.getTags()
        );
    }
}
