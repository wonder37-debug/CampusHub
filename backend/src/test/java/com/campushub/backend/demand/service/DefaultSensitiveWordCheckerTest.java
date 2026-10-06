package com.campushub.backend.demand.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSensitiveWordCheckerTest {

    private DefaultSensitiveWordChecker checker;

    @BeforeEach
    void setUp() {
        checker = new DefaultSensitiveWordChecker();
    }

    @Test
    void normalCampusTextShouldNotBeFlagged() {
        assertFalse(checker.containsForbiddenWords("求一名高数家教"));
        assertFalse(checker.containsForbiddenWords("转让闲置教材"));
        assertFalse(checker.containsForbiddenWords("寻找羽毛球搭子"));
        assertFalse(checker.containsForbiddenWords("校园跑腿帮忙取快递"));
        assertFalse(checker.containsForbiddenWords("寻找自习室"));
        assertFalse(checker.containsForbiddenWords("社团活动报名"));
        assertFalse(checker.containsForbiddenWords("二手自行车转让"));
    }

    @Test
    void academicWordsShouldBeDetected() {
        assertTrue(checker.containsForbiddenWords("我需要代考"));
        assertTrue(checker.containsForbiddenWords("提供论文代写服务"));
        assertTrue(checker.containsForbiddenWords("出售考试答案"));
        assertTrue(checker.containsForbiddenWords("代刷网课"));
        assertTrue(checker.containsForbiddenWords("考试作弊服务"));
    }

    @Test
    void advertisingWordsShouldBeDetected() {
        assertTrue(checker.containsForbiddenWords("加微信领红包"));
        assertTrue(checker.containsForbiddenWords("高薪日结兼职"));
        assertTrue(checker.containsForbiddenWords("刷单返利"));
        assertTrue(checker.containsForbiddenWords("月入过万兼职"));
        assertTrue(checker.containsForbiddenWords("校园兼职刷单"));
    }

    @Test
    void shouldDetectBypassWithSeparators() {
        assertTrue(checker.containsForbiddenWords("代考"));
        assertTrue(checker.containsForbiddenWords("代 考"));
        assertTrue(checker.containsForbiddenWords("代　考"));
        assertTrue(checker.containsForbiddenWords("代-考"));
        assertTrue(checker.containsForbiddenWords("代_考"));
        assertTrue(checker.containsForbiddenWords("代·考"));
        assertTrue(checker.containsForbiddenWords("代•考"));
    }

    @Test
    void shouldDetectUnicodeZeroWidthBypass() {
        assertTrue(checker.containsForbiddenWords("代\u200B考"));
        assertTrue(checker.containsForbiddenWords("代\u200C考"));
        assertTrue(checker.containsForbiddenWords("代\u200D考"));
        assertTrue(checker.containsForbiddenWords("代\uFEFF考"));
        assertTrue(checker.containsForbiddenWords("代\u2060考"));
        assertTrue(checker.containsForbiddenWords("代\u00AD考"));
    }

    @Test
    void boundaryCasesShouldNotCrashOrMisflag() {
        assertFalse(checker.containsForbiddenWords(null));
        assertFalse(checker.containsForbiddenWords(""));
        assertFalse(checker.containsForbiddenWords("   "));
        assertFalse(checker.containsForbiddenWords("   \t\r\n　"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("普通校园生活分享");
        }
        assertFalse(checker.containsForbiddenWords(sb.toString()));
        assertFalse(checker.containsForbiddenWords("今天天气不错，适合打羽毛球"));
        assertFalse(checker.containsForbiddenWords("hello world 大家好"));
        assertFalse(checker.containsForbiddenWords("Java Spring Boot 学习笔记"));
    }

    @Test
    void normalizedTextOnlyUsedForDetection() {
        assertTrue(checker.containsForbiddenWords("代 - _ · • 考"));
        assertTrue(checker.containsForbiddenWords("论 文 代 写"));
        assertTrue(checker.containsForbiddenWords("刷\u200B单\u200C返\u200D利"));
    }
}
