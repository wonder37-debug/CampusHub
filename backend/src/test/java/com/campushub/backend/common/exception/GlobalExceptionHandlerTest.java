package com.campushub.backend.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnInternalErrorForUnexpectedExceptionWithoutLeakingMessage() {
        String secretDetail = "java.lang.NullPointerException at com.example.Secret.internal(Secret.java:42)";
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(new RuntimeException(secretDetail));

        assertEquals(500, response.getStatusCode().value());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(5000, body.code());
        assertEquals("INTERNAL_ERROR", body.errorCode());
        assertEquals("服务异常，请稍后重试", body.message());
        // 关键：原始异常细节不得出现在响应里
        assertNotEquals(secretDetail, body.message());
    }

    @Test
    void shouldMapBusinessExceptionToItsErrorCode() {
        BusinessException exception = new BusinessException(ErrorCode.VALIDATION_FAILED, "title length must be between 3 and 200");
        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(1002, body.code());
        assertEquals("VALIDATION_FAILED", body.errorCode());
        assertEquals("title length must be between 3 and 200", body.message());
    }
}
