package com.campushub.backend.auth.service;

import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class InMemoryVerificationCodeService implements VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVerificationCodeService.class);
    private static final long EXPIRES_IN_SECONDS = 300L;
    private static final long RESEND_COOLDOWN_SECONDS = 60L;

    private final Random random = new Random();
    private final Map<String, VerificationRecord> records = new ConcurrentHashMap<>();

    @Override
    public EmailVerificationIssue issueCode(String email, String studentId) {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "email must be a valid address");
        }

        Instant now = Instant.now();
        VerificationRecord existing = records.get(normalizedEmail);
        if (existing != null && Duration.between(existing.sentAt(), now).getSeconds() < RESEND_COOLDOWN_SECONDS) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "verification code sent too frequently");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant expiresAt = now.plusSeconds(EXPIRES_IN_SECONDS);
        records.put(normalizedEmail, new VerificationRecord(code, expiresAt, now, studentId));
        log.info("Mock email verification code issued for {}: {}", normalizedEmail, code);
        return new EmailVerificationIssue(normalizedEmail, code, EXPIRES_IN_SECONDS, expiresAt);
    }

    @Override
    public boolean verify(String email, String verificationCode) {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isBlank() || verificationCode == null || verificationCode.isBlank()) {
            return false;
        }

        VerificationRecord record = records.get(normalizedEmail);
        if (record == null || Instant.now().isAfter(record.expiresAt())) {
            records.remove(normalizedEmail);
            return false;
        }
        if (!record.code().equals(verificationCode.trim())) {
            return false;
        }

        records.remove(normalizedEmail);
        return true;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private record VerificationRecord(String code, Instant expiresAt, Instant sentAt, String studentId) {
    }
}
