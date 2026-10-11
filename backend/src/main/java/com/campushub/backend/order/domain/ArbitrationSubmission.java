package com.campushub.backend.order.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ArbitrationSubmission(
    Long id,
    Long orderId,
    Long submitterId,
    String role,
    String reason,
    List<String> imageUrls,
    int imageCount,
    LocalDateTime submittedAt
) {
}
