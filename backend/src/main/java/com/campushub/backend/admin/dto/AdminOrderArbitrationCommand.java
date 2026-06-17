package com.campushub.backend.admin.dto;

public record AdminOrderArbitrationCommand(
    String outcome,
    String reason
) {
}
