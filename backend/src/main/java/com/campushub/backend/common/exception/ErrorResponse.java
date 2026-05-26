package com.campushub.backend.common.exception;

import java.util.Map;

public record ErrorResponse(
    int code,
    String errorCode,
    String message,
    Map<String, Object> details,
    Map<String, Object> errors
) {

    public static ErrorResponse from(ErrorCode errorCode, String message, Map<String, Object> details) {
        Map<String, Object> normalizedDetails = details == null ? Map.of() : details;
        return new ErrorResponse(
            errorCode.getCode(),
            errorCode.name(),
            message,
            normalizedDetails,
            normalizedDetails
        );
    }
}
