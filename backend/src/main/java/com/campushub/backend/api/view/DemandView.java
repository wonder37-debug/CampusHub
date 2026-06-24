package com.campushub.backend.api.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DemandView(
    Long id,
    Long publisherId,
    String publisherDisplayName,
    UserSummaryView publisher,
    String publisherStudentIdMasked,
    boolean publisherIdentityVisible,
    String title,
    String description,
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
    boolean canAccept,
    String acceptDisabledReason,
    String acceptStatusHint,
    boolean canStartExecution,
    boolean canViewAcceptNote,
    boolean canSubmitAcceptNote,
    List<String> images,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
