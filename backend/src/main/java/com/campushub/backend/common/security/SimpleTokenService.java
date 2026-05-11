package com.campushub.backend.common.security;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SimpleTokenService implements TokenService {

    @Override
    public String generateToken(TokenPayload payload) {
        String raw = payload.userId()
            + ":"
            + payload.role().name()
            + ":"
            + DateTimeFormatter.ISO_INSTANT.format(payload.expiresAt())
            + ":"
            + UUID.randomUUID();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
