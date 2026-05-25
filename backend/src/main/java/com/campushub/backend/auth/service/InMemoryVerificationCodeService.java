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
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class InMemoryVerificationCodeService implements VerificationCodeService {

    private static final long EXPIRES_IN_SECONDS = 300L;
    private static final long RESEND_COOLDOWN_SECONDS = 60L;

    private final CampusEmailPolicy campusEmailPolicy;
    private final VerificationEmailSender verificationEmailSender;
    private final Random random = new Random();
    private final Map<String, VerificationRecord> records = new ConcurrentHashMap<>();

    public InMemoryVerificationCodeService(
        CampusEmailPolicy campusEmailPolicy,
        VerificationEmailSender verificationEmailSender
    ) {
        this.campusEmailPolicy = campusEmailPolicy;
        this.verificationEmailSender = verificationEmailSender;
    }

    @Override
    public EmailVerificationIssue issueCode(String email, String studentId) {
        String normalizedEmail = campusEmailPolicy.normalize(email);
        if (!campusEmailPolicy.isValidCampusEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "email must be a valid campus email");
        }

        Instant now = Instant.now();
        VerificationRecord existing = records.get(normalizedEmail);
        if (existing != null && Duration.between(existing.sentAt(), now).getSeconds() < RESEND_COOLDOWN_SECONDS) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "verification code sent too frequently");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant expiresAt = now.plusSeconds(EXPIRES_IN_SECONDS);
        String normalizedStudentId = normalizeStudentId(studentId);
        verificationEmailSender.sendRegistrationCode(normalizedEmail, code, EXPIRES_IN_SECONDS);
        records.put(normalizedEmail, new VerificationRecord(code, expiresAt, now, normalizedStudentId));
        return new EmailVerificationIssue(normalizedEmail, code, EXPIRES_IN_SECONDS, expiresAt);
    }

    @Override
    public boolean verify(String email, String verificationCode) {
        String normalizedEmail = campusEmailPolicy.normalize(email);
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

    public boolean matchesStudentId(String email, String studentId) {
        String normalizedEmail = campusEmailPolicy.normalize(email);
        String normalizedStudentId = normalizeStudentId(studentId);
        VerificationRecord record = records.get(normalizedEmail);
        if (record == null || Instant.now().isAfter(record.expiresAt())) {
            records.remove(normalizedEmail);
            return false;
        }
        return normalizedStudentId.equals(record.studentId());
    }

    private String normalizeStudentId(String studentId) {
        return studentId == null ? null : studentId.trim();
    }

    private record VerificationRecord(String code, Instant expiresAt, Instant sentAt, String studentId) {
    }
}
