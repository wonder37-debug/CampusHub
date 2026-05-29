package com.campushub.backend.demand.dto;

import com.campushub.backend.demand.domain.Demand;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DemandDetailResponse(
    Long id,
    Long publisherId,
    String publisherDisplayName,
    String title,
    String description,
    String note,
    String category,
    String campusZone,
    String location,
    LocalDateTime startTime,
    LocalDateTime endTime,
    BigDecimal reward,
    List<String> tags,
    String status,
    boolean anonymous,
    String anonymousCode,
    Long reviewedBy,
    LocalDateTime reviewedAt,
    String reviewReason,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public static DemandDetailResponse from(Demand demand) {
        Long visiblePublisherId = demand.isAnonymous() ? null : demand.getPublisherId();
        String visibleName = demand.isAnonymous() ? demand.getAnonymousCode() : demand.getPublisherDisplayName();
        return new DemandDetailResponse(
            demand.getId(),
            visiblePublisherId,
            visibleName,
            demand.getTitle(),
            demand.getDescription(),
            demand.getNote(),
            demand.getCategory().name(),
            demand.getCampusZone().name(),
            demand.getLocation(),
            demand.getStartTime(),
            demand.getEndTime(),
            demand.getReward(),
            demand.getTags(),
            demand.getStatus().name(),
            demand.isAnonymous(),
            demand.getAnonymousCode(),
            demand.getReviewedBy(),
            demand.getReviewedAt(),
            demand.getReviewReason(),
            demand.getCreatedAt(),
            demand.getUpdatedAt()
        );
    }
}
