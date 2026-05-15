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

    private final SimpleTokenService tokenService = new SimpleTokenService();
    private final RequestUserExtractor requestUserExtractor = new RequestUserExtractor();

    @Test
    void shouldExtractCurrentUserFromGeneratedToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600))
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);

        assertEquals(42L, currentUser.userId());
        assertEquals(UserRole.USER, currentUser.role());
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().minusSeconds(60))
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> requestUserExtractor.requireCurrentUser(request)
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
        assertEquals("token has expired", exception.getMessage());
    }
}
