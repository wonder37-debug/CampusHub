package com.campushub.backend.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    // 测试用密钥（Base64 解码后 ≥ 32 字节，满足 HS256）
    private static final String SECRET = "ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==";
    private final JwtTokenService tokenService = new JwtTokenService(SECRET);

    @Test
    void shouldRoundTripGenerateAndVerify() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String token = tokenService.generateToken(new TokenPayload(42L, UserRole.USER, expiresAt));

        TokenPayload parsed = tokenService.verifyAndParse(token);

        assertEquals(42L, parsed.userId());
        assertEquals(UserRole.USER, parsed.role());
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().minusSeconds(60)));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.verifyAndParse(token));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("token has expired", ex.getMessage());
    }

    @Test
    void shouldRejectTamperedSignature() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)));
        // 破坏最后一个字符制造签名错误
        String tampered = token.substring(0, token.length() - 2) + "AA";

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.verifyAndParse(tampered));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("invalid token", ex.getMessage());
    }

    @Test
    void shouldRejectTokenSignedByDifferentKey() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)));
        byte[] otherKeyBytes = "0123456789012345678901234567890123456789012345678901234567890123".getBytes(StandardCharsets.UTF_8);
        // JwtTokenService(byte[]) 为包级构造，同包测试可直接访问
        JwtTokenService otherService = new JwtTokenService(otherKeyBytes);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> otherService.verifyAndParse(token));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("invalid token", ex.getMessage());
    }

    @Test
    void shouldRejectMalformedToken() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> tokenService.verifyAndParse("not-a-jwt"));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("invalid token", ex.getMessage());
    }
}
