package com.campushub.backend.common.security;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestUserExtractor {

    private final TokenService tokenService;

    public RequestUserExtractor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public CurrentUser requireCurrentUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "missing bearer token");
        }
        TokenPayload payload = tokenService.verifyAndParse(authorization.substring("Bearer ".length()).trim());
        return new CurrentUser(payload.userId(), payload.role());
    }

    public CurrentUser tryExtract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            TokenPayload payload = tokenService.verifyAndParse(authorization.substring("Bearer ".length()).trim());
            return new CurrentUser(payload.userId(), payload.role());
        } catch (BusinessException exception) {
            return null;
        }
    }
}
