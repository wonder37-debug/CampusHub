package com.campushub.backend.auth.service;

public interface VerificationEmailSender {

    void sendRegistrationCode(String email, String verificationCode, long expiresInSeconds);
}
