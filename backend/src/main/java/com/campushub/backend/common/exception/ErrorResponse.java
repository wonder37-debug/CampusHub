package com.campushub.backend.common.exception;

import java.util.Map;

public record ErrorResponse(int code, String message, Map<String, Object> errors) {

    public static ErrorResponse from(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message, Map.of());
    }
}
