package com.campushub.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.dto.LoginCommand;
import com.campushub.backend.auth.dto.LoginResult;
import com.campushub.backend.auth.dto.PasswordResetCommand;
import com.campushub.backend.auth.dto.RegisterCommand;
import com.campushub.backend.auth.dto.UpdateProfileCommand;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(classes = {BackendApplication.class, AuthApplicationServiceImplTest.TestConfig.class}, properties = {
    "app.demo-data.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthApplicationServiceImplTest {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final CampusEmailPolicy CAMPUS_EMAIL_POLICY = new CampusEmailPolicy(
        "example.edu.cn,campus.edu,test.edu.cn,edu.cn"
    );

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthApplicationService authApplicationService;

    @Autowired
    private RecordingVerificationEmailSender verificationEmailSender;

    @BeforeEach
    void setUp() {
        verificationEmailSender.reset();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        RecordingVerificationEmailSender recordingVerificationEmailSender() {
            return new RecordingVerificationEmailSender();
        }
    }

    @Test
    void shouldRegisterUserSuccessfully() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");
        UserProfileResponse response = authApplicationService.register(
            new RegisterCommand(
                "zheng@example.edu.cn",
                issue.verificationCode(),
                "20260001",
                "Password1",
                "tester",
                null
            )
        );

        assertNotNull(response.id());
        assertEquals("20260001", response.studentId());
        assertEquals(UserRole.USER, response.role());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertEquals(100, response.creditScore());
    }

    @Test
    void shouldRejectDuplicateStudentId() {
        EmailVerificationIssue firstIssue = authApplicationService.sendRegistrationCode("one@example.edu.cn", "20260001");
        authApplicationService.register(
            new RegisterCommand(
                "one@example.edu.cn",
                firstIssue.verificationCode(),
                "20260001",
                "Password1",
                "one",
                null
            )
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.sendRegistrationCode("two@example.edu.cn", "20260001")
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldLoginSuccessfully() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");
        UserProfileResponse registered = authApplicationService.register(
            new RegisterCommand(
                "zheng@example.edu.cn",
                issue.verificationCode(),
                "20260001",
                "Password1",
                "tester",
                null
            )
        );

        LoginResult result = authApplicationService.login(new LoginCommand("20260001", "Password1"));

        assertNotNull(result.token());
        assertTrue(result.expiresIn() > 0);
        assertEquals(registered.id(), result.user().id());
    }

    @Test
    void shouldRejectWrongPassword() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");
        authApplicationService.register(
            new RegisterCommand(
                "zheng@example.edu.cn",
                issue.verificationCode(),
                "20260001",
                "Password1",
                "tester",
                null
            )
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.login(new LoginCommand("20260001", "WrongPass1"))
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectBannedUserLogin() {
        User bannedUser = new User(
            null,
            "ban@example.edu.cn",
            "20260002",
            PASSWORD_ENCODER.encode("Password1"),
            "banned",
            null,
            UserRole.USER,
            UserStatus.BANNED,
            100,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        userRepository.save(bannedUser);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.login(new LoginCommand("20260002", "Password1"))
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldRejectUpdatingAnotherUsersProfile() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");
        UserProfileResponse user = authApplicationService.register(
            new RegisterCommand(
                "zheng@example.edu.cn",
                issue.verificationCode(),
                "20260001",
                "Password1",
                "tester",
                null
            )
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.updateProfile(999L, user.id(), new UpdateProfileCommand("new", null))
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldUpdateOwnProfile() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");
        UserProfileResponse user = authApplicationService.register(
            new RegisterCommand(
                "zheng@example.edu.cn",
                issue.verificationCode(),
                "20260001",
                "Password1",
                "tester",
                null
            )
        );

        UserProfileResponse updated = authApplicationService.updateProfile(
            user.id(),
            user.id(),
            new UpdateProfileCommand("new-name", "https://example.com/avatar.png")
        );

        assertEquals("new-name", updated.nickname());
        assertEquals("https://example.com/avatar.png", updated.avatarUrl());
    }

    @Test
    void shouldRejectNonCampusEmailWhenSendingCode() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.sendRegistrationCode("user@gmail.com", "20260001")
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals(0, verificationEmailSender.sendCount);
    }

    @Test
    void shouldSendVerificationEmailWhenIssuingCode() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");

        assertEquals("zheng@example.edu.cn", verificationEmailSender.lastEmail);
        assertEquals(issue.verificationCode(), verificationEmailSender.lastCode);
        assertTrue(verificationEmailSender.lastExpiresInSeconds > 0);
    }

    @Test
    void shouldNotEnterCooldownWhenEmailSendingFails() {
        VerificationCodeService failingService = new InMemoryVerificationCodeService(
            CAMPUS_EMAIL_POLICY,
            (email, verificationCode, expiresInSeconds) -> {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "failed to send verification email");
            }
        );

        BusinessException firstException = assertThrows(
            BusinessException.class,
            () -> failingService.issueCode("zheng@example.edu.cn", "20260001")
        );
        BusinessException secondException = assertThrows(
            BusinessException.class,
            () -> failingService.issueCode("zheng@example.edu.cn", "20260001")
        );

        assertEquals("failed to send verification email", firstException.getMessage());
        assertEquals("failed to send verification email", secondException.getMessage());
    }

    @Test
    void shouldRejectRegisterWhenStudentIdDoesNotMatchIssuedCode() {
        EmailVerificationIssue issue = authApplicationService.sendRegistrationCode("zheng@example.edu.cn", "20260001");

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.register(
                new RegisterCommand(
                    "zheng@example.edu.cn",
                    issue.verificationCode(),
                    "20269999",
                    "Password1",
                    "tester",
                    null
                )
            )
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldResetPasswordWithEmailCode() {
        EmailVerificationIssue registerIssue = authApplicationService.sendRegistrationCode("reset@example.edu.cn", "20260009");
        authApplicationService.register(
            new RegisterCommand(
                "reset@example.edu.cn",
                registerIssue.verificationCode(),
                "20260009",
                "Password1",
                "reset-user",
                null
            )
        );

        EmailVerificationIssue resetIssue = authApplicationService.sendPasswordResetCode("reset@example.edu.cn");
        authApplicationService.resetPassword(
            new PasswordResetCommand("reset@example.edu.cn", resetIssue.verificationCode(), "NewPassword1")
        );

        LoginResult result = authApplicationService.login(new LoginCommand("20260009", "NewPassword1"));
        assertNotNull(result.token());
    }

    @Test
    void shouldNotRevealEmailExistenceWhenSendingPasswordResetCode() {
        // 账号枚举防护：不存在的邮箱不应抛 RESOURCE_NOT_FOUND，返回与正常流程一致的伪 issue
        EmailVerificationIssue issue = authApplicationService.sendPasswordResetCode("nobody@example.edu.cn");
        assertEquals(300L, issue.expiresInSeconds());
        assertNull(issue.verificationCode(), "伪 issue 不应携带真实验证码");
    }

    @Test
    void shouldRejectPasswordResetWithInvalidCodeWithoutLeakingUserExistence() {
        // 无论邮箱是否注册，错误验证码统一返回 AUTH_FAILED，不暴露账号是否存在
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> authApplicationService.resetPassword(
                new PasswordResetCommand("nobody@example.edu.cn", "000000", "NewPassword1")
            )
        );
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
    }

    @Test
    void shouldRejectPasswordResetWithInvalidCodeForExistingEmail() {
        EmailVerificationIssue registerIssue = authApplicationService.sendRegistrationCode("reset2@example.edu.cn", "20260010");
        authApplicationService.register(
            new RegisterCommand(
                "reset2@example.edu.cn",
                registerIssue.verificationCode(),
                "20260010",
                "Password1",
                "reset2-user",
                null
            )
        );
        // 已注册邮箱 + 错误验证码：统一返回 AUTH_FAILED（不区分用户存在性）
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> authApplicationService.resetPassword(
                new PasswordResetCommand("reset2@example.edu.cn", "999999", "NewPassword1")
            )
        );
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
    }

    static class RecordingVerificationEmailSender implements VerificationEmailSender {
        private String lastEmail;
        private String lastCode;
        private long lastExpiresInSeconds;
        private int sendCount;

        @Override
        public void sendRegistrationCode(String email, String verificationCode, long expiresInSeconds) {
            this.lastEmail = email;
            this.lastCode = verificationCode;
            this.lastExpiresInSeconds = expiresInSeconds;
            this.sendCount++;
        }

        void reset() {
            this.lastEmail = null;
            this.lastCode = null;
            this.lastExpiresInSeconds = 0L;
            this.sendCount = 0;
        }
    }
}
