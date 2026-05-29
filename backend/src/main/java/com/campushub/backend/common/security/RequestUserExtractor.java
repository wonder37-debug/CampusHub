package com.campushub.backend.common.security;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class RequestUserExtractor {

    public CurrentUser requireCurrentUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "missing bearer token");
        }
        return parseToken(authorization.substring("Bearer ".length()).trim());
    }

    public CurrentUser tryExtract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return parseToken(authorization.substring("Bearer ".length()).trim());
    }

    private CurrentUser parseToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int firstSeparator = decoded.indexOf(':');
            int secondSeparator = firstSeparator < 0 ? -1 : decoded.indexOf(':', firstSeparator + 1);
            int lastSeparator = decoded.lastIndexOf(':');
            if (firstSeparator < 0 || secondSeparator < 0 || lastSeparator <= secondSeparator) {
                throw new BusinessException(ErrorCode.AUTH_FAILED, "invalid token format");
            }

            Long userId = Long.valueOf(decoded.substring(0, firstSeparator));
            UserRole role = UserRole.valueOf(decoded.substring(firstSeparator + 1, secondSeparator));
            Instant expiresAt = Instant.parse(decoded.substring(secondSeparator + 1, lastSeparator));
            if (expiresAt.isBefore(Instant.now())) {
                throw new BusinessException(ErrorCode.AUTH_FAILED, "token has expired");
            }

            return new CurrentUser(userId, role);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "invalid token");
        }
    }
}
