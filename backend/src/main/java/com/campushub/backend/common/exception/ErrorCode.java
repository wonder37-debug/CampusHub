package com.campushub.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    AUTH_FAILED(1001, HttpStatus.UNAUTHORIZED),
    VALIDATION_FAILED(1002, HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(1003, HttpStatus.NOT_FOUND),
    PERMISSION_DENIED(1004, HttpStatus.FORBIDDEN),
    BUSINESS_CONFLICT(1005, HttpStatus.CONFLICT);

    private final int code;
    private final HttpStatus httpStatus;

    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
