package com.campushub.backend.demand.service;

public interface SensitiveWordChecker {

    boolean containsForbiddenWords(String text);
}
