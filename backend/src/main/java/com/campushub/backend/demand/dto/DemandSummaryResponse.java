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
    List<String> tags,
    List<String> images
) {

    public static DemandSummaryResponse from(Demand demand) {
        return from(demand, demand.isAnonymous() ? demand.getAnonymousCode() : demand.getPublisherDisplayName());
    }

    /**
     * 使用调用方提供的实时发布者显示名构造响应；匿名需求仍使用匿名代号，避免泄露身份。
     */
    public static DemandSummaryResponse from(Demand demand, String liveDisplayName) {
        String visibleDisplayName = demand.isAnonymous()
            ? demand.getAnonymousCode()
            : (liveDisplayName == null || liveDisplayName.isBlank() ? demand.getPublisherDisplayName() : liveDisplayName);
        return new DemandSummaryResponse(
            demand.getId(),
            demand.getTitle(),
            demand.getCategory().name(),
            demand.getCampusZone().name(),
            demand.getLocation(),
            demand.getReward(),
            visibleDisplayName,
            demand.getAnonymousCode(),
            demand.isAnonymous(),
            demand.getStatus().name(),
            demand.getStartTime(),
            demand.getEndTime(),
            demand.getCreatedAt(),
            demand.getTags(),
            demand.getImages()
        );
    }
}
