package com.campushub.backend.auth.service;

public interface VerificationCodeService {

    boolean verify(String email, String verificationCode);
}
