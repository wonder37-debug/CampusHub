package com.campushub.backend.common.security;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService implements TokenService {

    private final SecretKey key;

    /** Spring 注入构造：从配置读取 Base64 编码的密钥串。 */
    @Autowired
    public JwtTokenService(@Value("${app.security.jwt.secret}") String base64Secret) {
        this(io.jsonwebtoken.io.Decoders.BASE64.decode(base64Secret));
    }

    /** 测试用构造：直接传原始密钥字节（用于密钥不匹配等场景）。 */
    JwtTokenService(byte[] rawKey) {
        this.key = Keys.hmacShaKeyFor(rawKey);
    }

    @Override
    public String generateToken(TokenPayload payload) {
        return Jwts.builder()
            .subject(String.valueOf(payload.userId()))
            .claim("role", payload.role().name())
            .expiration(Date.from(payload.expiresAt()))
            .signWith(key)
            .compact();
    }

    @Override
    public TokenPayload verifyAndParse(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            UserRole role = UserRole.valueOf(claims.get("role", String.class));
            Instant expiresAt = claims.getExpiration().toInstant();
            return new TokenPayload(userId, role, expiresAt);
        } catch (ExpiredJwtException exception) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "token has expired");
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "invalid token");
        }
    }
}
