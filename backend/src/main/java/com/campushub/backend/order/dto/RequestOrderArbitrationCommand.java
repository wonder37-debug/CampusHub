package com.campushub.backend.order.dto;

import java.util.List;

public record RequestOrderArbitrationCommand(
    String reason,
    List<String> imageUrls
) {
    public RequestOrderArbitrationCommand(String reason) {
        this(reason, null);
    }
}
