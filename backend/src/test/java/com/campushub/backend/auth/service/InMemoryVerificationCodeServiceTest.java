package com.campushub.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 验证码服务安全策略单元测试：错误次数锁定、一次性消费、重发 cooldown、不存在邮箱不泄露存在性。
 */
class InMemoryVerificationCodeServiceTest {

    private static final CampusEmailPolicy POLICY = new CampusEmailPolicy("edu.cn,example.edu.cn");

    private static VerificationEmailSender noopSender() {
        return (email, code, expires) -> { };
    }

    private static InMemoryVerificationCodeService newService() {
        return new InMemoryVerificationCodeService(POLICY, noopSender());
    }

    @Test
    void shouldInvalidateCodeAfterSuccessfulVerify() {
        InMemoryVerificationCodeService svc = newService();
        EmailVerificationIssue issue = svc.issueCode("user@example.edu.cn", "20260001");
        assertTrue(svc.verify("user@example.edu.cn", issue.verificationCode()));
        // 成功后重复使用应失败（一次性消费）
        assertFalse(svc.verify("user@example.edu.cn", issue.verificationCode()));
    }

    @Test
    void shouldLockCodeAfterMaxWrongAttempts() {
        InMemoryVerificationCodeService svc = newService();
        EmailVerificationIssue issue = svc.issueCode("user@example.edu.cn", "20260001");
        String correct = issue.verificationCode();

        // 5 次错误后锁定
        for (int i = 1; i <= 5; i++) {
            assertFalse(svc.verify("user@example.edu.cn", "000000"), "第 " + i + " 次错误应返回 false");
        }
        // 锁定后即使输入正确码也失败
        assertFalse(svc.verify("user@example.edu.cn", correct), "锁定后正确码也应失败");
    }

    @Test
    void shouldKeepCodeValidBeforeMaxWrongAttempts() {
        InMemoryVerificationCodeService svc = newService();
        EmailVerificationIssue issue = svc.issueCode("user@example.edu.cn", "20260001");
        String correct = issue.verificationCode();

        // 4 次错误未达阈值，码仍可用
        for (int i = 1; i <= 4; i++) {
            assertFalse(svc.verify("user@example.edu.cn", "000000"));
        }
        assertTrue(svc.verify("user@example.edu.cn", correct), "未达阈值前正确码仍应可用");
    }

    @Test
    void shouldEnforceResendCooldown() {
        InMemoryVerificationCodeService svc = newService();
        svc.issueCode("user@example.edu.cn", "20260001");
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> svc.issueCode("user@example.edu.cn", "20260002")
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, ex.getErrorCode());
    }

    @Test
    void shouldFailVerifyForNonexistentEmailWithoutLeakingExistence() {
        InMemoryVerificationCodeService svc = newService();
        // 不存在的邮箱：verify 直接返回 false，不抛错、不泄露存在性
        assertFalse(svc.verify("nobody@example.edu.cn", "123456"));
    }

    @Test
    void shouldMatchStudentIdWhileCodeAlive() {
        InMemoryVerificationCodeService svc = newService();
        svc.issueCode("user@example.edu.cn", "20260001");
        assertTrue(svc.matchesStudentId("user@example.edu.cn", "20260001"));
        assertFalse(svc.matchesStudentId("user@example.edu.cn", "20269999"));
    }
}
