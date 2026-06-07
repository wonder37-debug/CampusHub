package com.campushub.backend.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationEmailSender.class);

    @Override
    public void sendRegistrationCode(String email, String verificationCode, long expiresInSeconds) {
        log.info(
            "Verification email fallback for {} with code {} expiring in {} seconds",
            email,
            verificationCode,
            expiresInSeconds
        );
    }
}
