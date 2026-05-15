package com.campushub.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.EmailVerificationIssue;
import com.campushub.backend.auth.dto.LoginCommand;
import com.campushub.backend.auth.dto.LoginResult;
import com.campushub.backend.auth.dto.RegisterCommand;
import com.campushub.backend.auth.dto.UpdateProfileCommand;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.auth.repository.InMemoryUserRepository;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.security.SimpleTokenService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthApplicationServiceImplTest {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private UserRepository userRepository;
    private AuthApplicationService authApplicationService;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        authApplicationService = new AuthApplicationServiceImpl(
            userRepository,
            new InMemoryVerificationCodeService(),
            new SimpleTokenService()
        );
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
                "嘉鸿",
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

        EmailVerificationIssue secondIssue = authApplicationService.sendRegistrationCode("two@example.edu.cn", null);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authApplicationService.register(
                new RegisterCommand(
                    "two@example.edu.cn",
                    secondIssue.verificationCode(),
                    "20260001",
                    "Password2",
                    "two",
                    null
                )
            )
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
                "嘉鸿",
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
                "嘉鸿",
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
                "嘉鸿",
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
                "嘉鸿",
                null
            )
        );

        UserProfileResponse updated = authApplicationService.updateProfile(
            user.id(),
            user.id(),
            new UpdateProfileCommand("新昵称", "https://example.com/avatar.png")
        );

        assertEquals("新昵称", updated.nickname());
        assertEquals("https://example.com/avatar.png", updated.avatarUrl());
    }
}
