package com.campushub.backend.order.dto;

public record UpdateOrderStatusCommand(String targetStatus, String note, Integer proofImageCount) {
}
