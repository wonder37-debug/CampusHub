package com.campushub.backend.auth.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CampusEmailPolicy {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
        Pattern.CASE_INSENSITIVE
    );

    private final Set<String> allowedDomains;

    public CampusEmailPolicy(
        @Value("${app.auth.allowed-email-domains:edu.cn,example.edu.cn,campus.edu,test.edu.cn}") String allowedDomains
    ) {
        this.allowedDomains = Set.of(allowedDomains.split(","))
            .stream()
            .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
            .filter(domain -> !domain.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isValidCampusEmail(String email) {
        String normalizedEmail = normalize(email);
        if (normalizedEmail.isBlank() || !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            return false;
        }

        int atIndex = normalizedEmail.lastIndexOf('@');
        if (atIndex < 0 || atIndex == normalizedEmail.length() - 1) {
            return false;
        }

        String domain = normalizedEmail.substring(atIndex + 1);
        return allowedDomains.contains(domain);
    }
}
