package com.campushub.backend.auth.dto;

public record LoginResult(String token, long expiresIn, UserProfileResponse user) {
}
