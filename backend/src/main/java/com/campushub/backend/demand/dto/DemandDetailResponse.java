package com.campushub.backend.demand.dto;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.InteractionMode;
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
    String interactionMode,
    Integer targetParticipantCount,
    List<String> tags,
    List<String> images,
    String contactInfo,
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
        InteractionMode mode = demand.getInteractionMode() == null ? InteractionMode.DIRECT_ACCEPT : demand.getInteractionMode();
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
            mode.name(),
            demand.getTargetParticipantCount(),
            demand.getTags(),
            demand.getImages(),
            demand.getContactInfo(),
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
