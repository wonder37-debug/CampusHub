package com.campushub.backend.demand.dto;

import com.campushub.backend.demand.domain.DemandResponse;

import java.time.LocalDateTime;

public record DemandResponseDetail(
    Long id,
    Long demandId,
    Long authorId,
    String authorName,
    String content,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public static DemandResponseDetail from(DemandResponse response, String authorName) {
        return new DemandResponseDetail(
            response.getId(),
            response.getDemandId(),
            response.getAuthorId(),
            authorName,
            response.getContent(),
            response.getStatus() == null ? "PENDING" : response.getStatus().name(),
            response.getCreatedAt(),
            response.getUpdatedAt()
        );
    }
}
