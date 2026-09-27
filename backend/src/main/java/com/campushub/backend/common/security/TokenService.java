package com.campushub.backend.common.security;

public interface TokenService {

    String generateToken(TokenPayload payload);

    /**
     * 验签并解析 token，校验签名与过期时间。
     *
     * @param token 紧凑序列化的 JWT
     * @return 解析出的载荷
     * @throws com.campushub.backend.common.exception.BusinessException
     *     {@link com.campushub.backend.common.exception.ErrorCode#AUTH_FAILED}
     *     当签名无效、token 过期或格式错误时抛出
     */
    TokenPayload verifyAndParse(String token);
}
