package com.campushub.backend.order.dto;

import java.util.List;

public record ArbitrationSubmissionCommand(
    String reason,
    List<String> imageUrls
) {
}
