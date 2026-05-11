package com.campushub.backend.common.security;

public interface TokenService {

    String generateToken(TokenPayload payload);
}
