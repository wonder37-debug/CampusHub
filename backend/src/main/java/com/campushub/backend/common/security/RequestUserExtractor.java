package com.campushub.backend.common.security;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 从请求头解析 Bearer JWT 并还原当前登录用户。
 *
 * <p>token 仅承担身份认证（userId）与过期校验；用户角色与状态以数据库当前值为准，
 * 这样角色降级或封禁后旧 token 立即失效，避免依赖 token version/blacklist 等额外基础设施。</p>
 */
@Component
public class RequestUserExtractor {

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public RequestUserExtractor(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    public CurrentUser requireCurrentUser(HttpServletRequest request) {
        TokenPayload payload = parsePayload(request);
        User user = userRepository.findById(payload.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_FAILED, "user no longer exists"));
        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "user has been banned");
        }
        return new CurrentUser(user.getId(), user.getRole());
    }

    public CurrentUser tryExtract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            TokenPayload payload = tokenService.verifyAndParse(authorization.substring("Bearer ".length()).trim());
            return userRepository.findById(payload.userId())
                .filter(user -> user.getStatus() != UserStatus.BANNED)
                .map(user -> new CurrentUser(user.getId(), user.getRole()))
                .orElse(null);
        } catch (BusinessException exception) {
            return null;
        }
    }

    private TokenPayload parsePayload(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "missing bearer token");
        }
        return tokenService.verifyAndParse(authorization.substring("Bearer ".length()).trim());
    }
}
