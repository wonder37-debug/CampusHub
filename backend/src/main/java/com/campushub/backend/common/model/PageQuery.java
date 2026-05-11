package com.campushub.backend.common.model;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;

public record PageQuery(int page, int size) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 1");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "size must be between 1 and " + MAX_SIZE
            );
        }
    }

    public static PageQuery defaultPage() {
        return new PageQuery(DEFAULT_PAGE, DEFAULT_SIZE);
    }
}
