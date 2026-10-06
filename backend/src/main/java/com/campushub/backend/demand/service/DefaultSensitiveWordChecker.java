package com.campushub.backend.demand.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DefaultSensitiveWordChecker implements SensitiveWordChecker {

    private static final Logger log = LoggerFactory.getLogger(DefaultSensitiveWordChecker.class);

    private static final String[] WORD_LIST_LOCATIONS = {
        "sensitive-words/academic.txt",
        "sensitive-words/advertising.txt"
    };

    private final Set<String> forbiddenWords;

    public DefaultSensitiveWordChecker() {
        this(toResources(WORD_LIST_LOCATIONS));
    }

    DefaultSensitiveWordChecker(Resource... resources) {
        this.forbiddenWords = loadWords(resources);
    }

    private static Resource[] toResources(String[] locations) {
        Resource[] resources = new Resource[locations.length];
        for (int i = 0; i < locations.length; i++) {
            resources[i] = new ClassPathResource(locations[i]);
        }
        return resources;
    }

    private static Set<String> loadWords(Resource... resources) {
        Set<String> words = new LinkedHashSet<>();
        for (Resource resource : resources) {
            if (resource == null) {
                continue;
            }
            if (!resource.exists()) {
                log.warn("敏感词词表文件不存在，已跳过: {}", resource.getDescription());
                continue;
            }
            try (InputStream in = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 Stream<String> lines = reader.lines()) {
                lines.forEach(line -> {
                    String trimmed = line == null ? "" : line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        return;
                    }
                    words.add(trimmed);
                });
            } catch (Exception e) {
                log.warn("加载敏感词词表文件失败，已跳过: {}", resource.getDescription(), e);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(words));
    }

    @Override
    public boolean containsForbiddenWords(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String word : forbiddenWords) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!isNoise(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isNoise(char c) {
        return isWhitespace(c) || isSeparator(c) || isZeroWidth(c);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\u000B'
            || c == '\u3000';
    }

    private static boolean isSeparator(char c) {
        return c == '-' || c == '_'
            || c == '\u00B7'
            || c == '\u2022'
            || c == '\u30FB';
    }

    private static boolean isZeroWidth(char c) {
        return c == '\u200B'
            || c == '\u200C'
            || c == '\u200D'
            || c == '\uFEFF'
            || c == '\u2060'
            || c == '\u00AD'
            || c == '\u200E'
            || c == '\u200F'
            || (c >= '\u2061' && c <= '\u2064');
    }
}
