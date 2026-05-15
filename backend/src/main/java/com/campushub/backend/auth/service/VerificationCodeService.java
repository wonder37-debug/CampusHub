package com.campushub.backend.auth.service;

import com.campushub.backend.auth.dto.EmailVerificationIssue;

public interface VerificationCodeService {

    EmailVerificationIssue issueCode(String email, String studentId);

    boolean verify(String email, String verificationCode);
}
