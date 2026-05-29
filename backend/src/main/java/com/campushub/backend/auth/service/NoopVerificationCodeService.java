package com.campushub.backend.auth.service;

import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;

/**
 * 空实现，仅在没有其他 VerificationCodeService 实现时使用。
 * 生产环境应使用 InMemoryVerificationCodeService（已通过 @Primary 注册）。
 */
public class NoopVerificationCodeService implements VerificationCodeService {

    @Override
    public EmailVerificationIssue issueCode(String email, String studentId) {
        throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "verification code service is not available");
    }

    @Override
    public boolean verify(String email, String verificationCode) {
        return verificationCode != null && !verificationCode.isBlank();
    }

    @Override
    public boolean matchesStudentId(String email, String studentId) {
        return studentId != null && !studentId.isBlank();
    }
}
