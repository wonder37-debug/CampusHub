package com.campushub.backend.api.view;

import java.time.LocalDateTime;
import java.util.List;

public record ArbitrationSubmissionView(
    Long id,
    Long submitterId,
    String submitterName,
    String role,
    String reason,
    List<String> imageUrls,
    int imageCount,
    LocalDateTime submittedAt
) {
}
