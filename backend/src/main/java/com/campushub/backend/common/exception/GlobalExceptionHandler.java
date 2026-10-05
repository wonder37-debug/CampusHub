package com.campushub.backend.common.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
            .body(ErrorResponse.from(errorCode, exception.getMessage(), exception.getDetails()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.from(ErrorCode.VALIDATION_FAILED, "缺少必填参数: " + exception.getParameterName(), Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.from(ErrorCode.VALIDATION_FAILED, "请求体格式错误", Map.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.from(ErrorCode.VALIDATION_FAILED, "上传文件超过大小限制", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("unexpected exception", exception);
        return ResponseEntity.internalServerError()
            .body(ErrorResponse.from(ErrorCode.INTERNAL_ERROR, "服务异常，请稍后重试", Map.of()));
    }
}
