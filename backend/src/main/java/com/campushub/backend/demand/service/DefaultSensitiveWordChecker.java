package com.campushub.backend.demand.service;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DefaultSensitiveWordChecker implements SensitiveWordChecker {

    private static final List<String> FORBIDDEN_WORDS = List.of("代课", "代考", "代刷网课");

    @Override
    public boolean containsForbiddenWords(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return FORBIDDEN_WORDS.stream().anyMatch(text::contains);
    }
}
