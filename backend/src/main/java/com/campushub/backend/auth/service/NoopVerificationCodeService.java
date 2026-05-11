package com.campushub.backend.auth.service;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class NoopVerificationCodeService implements VerificationCodeService {

    @Override
    public boolean verify(String email, String verificationCode) {
        return verificationCode != null && !verificationCode.isBlank();
    }
}
