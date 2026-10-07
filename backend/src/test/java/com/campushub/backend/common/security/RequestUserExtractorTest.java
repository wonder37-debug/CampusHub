package com.campushub.backend.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestUserExtractorTest {

    private static final String SECRET = "ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==";
    private final JwtTokenService tokenService = new JwtTokenService(SECRET);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RequestUserExtractor requestUserExtractor = new RequestUserExtractor(tokenService, userRepository);

    private static User user(Long id, UserRole role, UserStatus status) {
        return new User(id, "user" + id + "@edu.cn", "S" + id, "hash", "nick" + id, null, role, status, 80,
            BigDecimal.ZERO, BigDecimal.ZERO, LocalDateTime.now(), LocalDateTime.now());
    }

    private MockHttpServletRequest bearer(TokenPayload payload) {
        String token = tokenService.generateToken(payload);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void shouldExtractCurrentUserFromGeneratedToken() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, UserRole.USER, UserStatus.ACTIVE)));
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(bearer(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600))));

        assertEquals(42L, currentUser.userId());
        assertEquals(UserRole.USER, currentUser.role());
    }

    @Test
    void shouldUseDatabaseRoleOverTokenRole() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, UserRole.USER, UserStatus.ACTIVE)));
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(bearer(
            new TokenPayload(42L, UserRole.ADMIN, Instant.now().plusSeconds(3600))));

        assertEquals(42L, currentUser.userId());
        assertEquals(UserRole.USER, currentUser.role(),
            "旧 token 中的 ADMIN 角色在数据库角色已降级后不能继续生效");
    }

    @Test
    void shouldRejectExpiredToken() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> requestUserExtractor.requireCurrentUser(bearer(
                new TokenPayload(42L, UserRole.USER, Instant.now().minusSeconds(60))))
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
        assertEquals("token has expired", exception.getMessage());
    }

    @Test
    void shouldRejectBannedUserInRequire() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, UserRole.USER, UserStatus.BANNED)));
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> requestUserExtractor.requireCurrentUser(bearer(
                new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600))))
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectMissingUserInRequire() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> requestUserExtractor.requireCurrentUser(bearer(
                new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600))))
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldReturnNullWhenBearerMissing() {
        assertNull(requestUserExtractor.tryExtract(new MockHttpServletRequest()));
    }

    @Test
    void shouldReturnNullForInvalidTokenInTryExtract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-jwt");
        assertNull(requestUserExtractor.tryExtract(request));
    }

    @Test
    void shouldReturnNullForBannedUserInTryExtract() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, UserRole.USER, UserStatus.BANNED)));
        assertNull(requestUserExtractor.tryExtract(bearer(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)))));
    }

    @Test
    void shouldReturnNullForMissingUserInTryExtract() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        assertNull(requestUserExtractor.tryExtract(bearer(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)))));
    }

    @Test
    void shouldUseDatabaseRoleOverTokenRoleInTryExtract() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, UserRole.USER, UserStatus.ACTIVE)));
        CurrentUser currentUser = requestUserExtractor.tryExtract(bearer(
            new TokenPayload(42L, UserRole.ADMIN, Instant.now().plusSeconds(3600))));

        assertEquals(42L, currentUser.userId());
        assertEquals(UserRole.USER, currentUser.role());
    }
}
