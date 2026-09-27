package com.campushub.backend.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestUserExtractorTest {

    private static final String SECRET = "ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==";
    private final JwtTokenService tokenService = new JwtTokenService(SECRET);
    private final RequestUserExtractor requestUserExtractor = new RequestUserExtractor(tokenService);

    @Test
    void shouldExtractCurrentUserFromGeneratedToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);

        assertEquals(42L, currentUser.userId());
        assertEquals(UserRole.USER, currentUser.role());
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().minusSeconds(60)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> requestUserExtractor.requireCurrentUser(request)
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
        assertEquals("token has expired", exception.getMessage());
    }

    @Test
    void shouldReturnNullWhenBearerMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(null, requestUserExtractor.tryExtract(request));
    }

    @Test
    void shouldReturnNullForInvalidTokenInTryExtract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-jwt");
        assertEquals(null, requestUserExtractor.tryExtract(request));
    }
}
