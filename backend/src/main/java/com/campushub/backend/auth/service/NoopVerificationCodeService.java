package com.campushub.backend.auth.service;

import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class NoopVerificationCodeService implements VerificationCodeService {

    @Override
    public EmailVerificationIssue issueCode(String email, String studentId) {
        throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "verification code service is not available");
    }

    @Override
    public boolean verify(String email, String verificationCode) {
        return verificationCode != null && !verificationCode.isBlank();
    }
}
