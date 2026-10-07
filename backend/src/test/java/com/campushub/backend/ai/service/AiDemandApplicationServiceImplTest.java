package com.campushub.backend.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * AI 需求草稿生成服务单元测试。通过 Mockito mock ChatClient 链式调用，
 * 不依赖真实 TokenHub / DeepSeek provider，可离线运行。
 */
class AiDemandApplicationServiceImplTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;
    private AiDemandApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        service = new AiDemandApplicationServiceImpl(chatClient, 1000);
    }

    @Test
    void shouldGenerateValidExpressDemand() {
        DemandDraft raw = new DemandDraft(
            "代取快递并送到南区宿舍",
            "明天下午三点帮我从菜鸟驿站取快递送到南区宿舍",
            "EXPRESS",
            "XIANLIN",
            "南区宿舍",
            "2026-10-08T15:00:00",
            "2026-10-08T17:00:00",
            new BigDecimal("10"),
            List.of("快递", "跑腿"),
            "DIRECT_ACCEPT",
            null,
            "轻拿轻放",
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand(
            "明天下午三点帮我从菜鸟驿站取快递送到南区宿舍，给10元"));

        assertEquals("EXPRESS", result.category());
        assertEquals("DIRECT_ACCEPT", result.interactionMode());
        assertEquals(new BigDecimal("10"), result.reward());
        assertEquals("代取快递并送到南区宿舍", result.title());
        assertTrue(result.missingFields().isEmpty());
    }

    @Test
    void shouldNotFabricateMissingInfo() {
        DemandDraft raw = new DemandDraft(
            "帮我找人买咖啡",
            "帮我找人买咖啡",
            "ERRAND",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of("campusZone", "location", "startTime", "endTime", "reward")
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("帮我找人买咖啡"));

        assertEquals("ERRAND", result.category());
        assertEquals("DIRECT_ACCEPT", result.interactionMode());
        assertNull(result.campusZone());
        assertNull(result.location());
        assertNull(result.startTime());
        assertNull(result.endTime());
        assertNull(result.reward());
        assertTrue(result.missingFields().contains("campusZone"));
        assertTrue(result.missingFields().contains("reward"));
    }

    @Test
    void shouldResolveTeamUpDemand() {
        DemandDraft raw = new DemandDraft(
            "周六下午找3个人一起打羽毛球",
            "周六下午找3个人一起打羽毛球",
            "TEAM_UP",
            null,
            null,
            "2026-10-10T14:00:00",
            "2026-10-10T17:00:00",
            BigDecimal.ZERO,
            List.of("羽毛球"),
            "SELECT_MANY",
            3,
            null,
            List.of("campusZone", "location")
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("周六下午找3个人一起打羽毛球"));

        assertEquals("TEAM_UP", result.category());
        assertEquals("SELECT_MANY", result.interactionMode());
        assertEquals(3, result.targetParticipantCount());
    }

    @Test
    void shouldForceSelectManyForTeamUpWhenInteractionModeMismatched() {
        DemandDraft raw = new DemandDraft(
            "组队打球",
            "组队打球",
            "TEAM_UP",
            "XIANLIN",
            "操场",
            "2026-10-10T14:00:00",
            "2026-10-10T17:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            3,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("组队打球"));

        assertEquals("TEAM_UP", result.category());
        // TEAM_UP 强制修正为 SELECT_MANY
        assertEquals("SELECT_MANY", result.interactionMode());
    }

    @Test
    void shouldAddTargetParticipantCountToMissingFieldsWhenTeamUpWithoutCount() {
        DemandDraft raw = new DemandDraft(
            "找几个人一起打羽毛球",
            "找几个人一起打羽毛球",
            "TEAM_UP",
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            List.of(),
            "SELECT_MANY",
            null,
            null,
            List.of("campusZone", "location", "startTime", "endTime")
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("找几个人一起打羽毛球"));

        assertEquals("TEAM_UP", result.category());
        assertEquals("SELECT_MANY", result.interactionMode());
        assertNull(result.targetParticipantCount());
        assertTrue(result.missingFields().contains("targetParticipantCount"));
    }

    @Test
    void shouldRejectInvalidCategory() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "INVALID",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("随便说点什么")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        // 不泄露内部细节
        assertTrue(exception.getMessage().contains("无法识别"));
    }

    @Test
    void shouldRejectInvalidInteractionModeForOther() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "OTHER",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "INVALID_MODE",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("随便说点什么")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectHelpModeForOtherCategory() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "OTHER",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "HELP",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("其他需求")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldAcceptOtherCategoryWithSelectOneMode() {
        DemandDraft raw = new DemandDraft(
            "其他需求",
            "描述",
            "OTHER",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "SELECT_ONE",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("其他需求"));

        assertEquals("OTHER", result.category());
        assertEquals("SELECT_ONE", result.interactionMode());
    }

    @Test
    void shouldForceSelectManyForTeamUpWithNullInteractionMode() {
        DemandDraft raw = new DemandDraft(
            "组队打球",
            "组队打球",
            "TEAM_UP",
            "XIANLIN",
            "操场",
            "2026-10-10T14:00:00",
            "2026-10-10T17:00:00",
            BigDecimal.ZERO,
            List.of(),
            null,
            3,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("组队打球"));

        assertEquals("TEAM_UP", result.category());
        // TEAM_UP + null interactionMode 仍强制 SELECT_MANY
        assertEquals("SELECT_MANY", result.interactionMode());
        assertEquals(3, result.targetParticipantCount());
    }

    @Test
    void shouldForceHelpInteractionModeForHelpCategory() {
        DemandDraft raw = new DemandDraft(
            "求助问题",
            "求助问题",
            "HELP",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            null,
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("求助"));

        assertEquals("HELP", result.category());
        // HELP category 即使 interactionMode=null 也强制 HELP
        assertEquals("HELP", result.interactionMode());
    }

    @Test
    void shouldRejectInvalidCampusZone() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "EXPRESS",
            "INVALID_ZONE",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldAcceptNullCampusZone() {
        DemandDraft raw = new DemandDraft(
            "取快递",
            "描述",
            "EXPRESS",
            null,
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of("campusZone")
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertNull(result.campusZone());
    }

    @Test
    void shouldNormalizeCampusZoneCaseInsensitive() {
        DemandDraft raw = new DemandDraft(
            "取快递",
            "描述",
            "EXPRESS",
            "xianlin",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertEquals("XIANLIN", result.campusZone());
    }

    @Test
    void shouldDeduplicateAndWhitelistMissingFields() {
        DemandDraft raw = new DemandDraft(
            "买咖啡",
            "描述",
            "ERRAND",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of("campusZone", "campusZone", "location", "reward", "invalidField", "anotherJunk", "  startTime  ", "")
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("帮我找人买咖啡"));

        // 白名单过滤掉 invalidField/anotherJunk，去重 campusZone，trim startTime
        assertTrue(result.missingFields().contains("campusZone"));
        assertTrue(result.missingFields().contains("location"));
        assertTrue(result.missingFields().contains("reward"));
        assertTrue(result.missingFields().contains("startTime"));
        assertEquals(4, result.missingFields().size());
        assertTrue(!result.missingFields().contains("invalidField"));
        assertTrue(!result.missingFields().contains("anotherJunk"));
    }

    @Test
    void shouldRejectNegativeReward() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "EXPRESS",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            new BigDecimal("-5"),
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("随便说点什么")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectInvalidTargetParticipantCount() {
        DemandDraft raw = new DemandDraft(
            "组队",
            "组队",
            "TEAM_UP",
            "XIANLIN",
            "操场",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "SELECT_MANY",
            0,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("组队")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectTargetParticipantCountExceeding100() {
        DemandDraft raw = new DemandDraft(
            "组队",
            "组队",
            "TEAM_UP",
            "XIANLIN",
            "操场",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "SELECT_MANY",
            200,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("组队")));
    }

    @Test
    void shouldRejectEndTimeBeforeStartTime() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "EXPRESS",
            "XIANLIN",
            "图书馆",
            "2026-10-08T12:00:00",
            "2026-10-08T10:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectInvalidTimeFormat() {
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "EXPRESS",
            "XIANLIN",
            "图书馆",
            "not-a-date",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldTranslateProviderRateLimitError() {
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new NonTransientAiException("429 Too Many Requests"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("AI 服务繁忙，请稍后重试", exception.getMessage());
    }

    @Test
    void shouldTranslateNonTransientAiExceptionAsInternalErrorWithoutLeakingSecrets() {
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new NonTransientAiException("401 Unauthorized: invalid api key"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("AI 服务暂时不可用，请稍后重试", exception.getMessage());
        // 不泄露 API Key / Authorization / 内部堆栈
        assertFalseContains(exception.getMessage(), "api");
        assertFalseContains(exception.getMessage(), "key");
        assertFalseContains(exception.getMessage(), "Authorization");
        assertFalseContains(exception.getMessage(), "401");
    }

    @Test
    void shouldTranslateTransientAiExceptionAsInternalErrorWithoutLeakingInternals() {
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new TransientAiException("500 Internal Server Error: upstream timeout"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("AI 服务暂时不可用，请稍后重试", exception.getMessage());
        assertFalseContains(exception.getMessage(), "upstream");
        assertFalseContains(exception.getMessage(), "internal");
        assertFalseContains(exception.getMessage(), "500");
    }

    @Test
    void shouldTranslateStructuredOutputParseFailureAsValidationFailed() {
        // 结构化输出解析失败：模型返回非 JSON，BeanOutputConverter 抛 RuntimeException
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new RuntimeException("Failed to convert response to DemandDraft: not a JSON"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("AI 返回内容无法识别，请重新描述需求", exception.getMessage());
        // 不泄露 provider 内部细节
        assertFalseContains(exception.getMessage(), "convert");
        assertFalseContains(exception.getMessage(), "JSON");
    }

    @Test
    void shouldRejectWhenChatClientNotConfigured() {
        AiDemandApplicationServiceImpl unconfiguredService =
            new AiDemandApplicationServiceImpl(null, 1000);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> unconfiguredService.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("AI 服务尚未配置，请联系管理员", exception.getMessage());
    }

    @Test
    void shouldRejectBlankPrompt() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("   ")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectNullPrompt() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand(null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectTooLongPrompt() {
        String longPrompt = "a".repeat(1001);
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand(longPrompt)));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectNullAiOutput() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldTruncateOverlongTitleAndDescription() {
        String longTitle = "标题".repeat(150);
        String longDescription = "描述".repeat(1500);
        DemandDraft raw = new DemandDraft(
            longTitle,
            longDescription,
            "EXPRESS",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            List.of(),
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertNotNull(result.title());
        assertTrue(result.title().length() <= 200);
        assertTrue(result.description().length() <= 2000);
    }

    @Test
    void shouldTruncateTagsExceedingLimit() {
        List<String> tooManyTags = java.util.stream.Stream.generate(() -> "tag").limit(30).toList();
        DemandDraft raw = new DemandDraft(
            "标题",
            "描述",
            "EXPRESS",
            "XIANLIN",
            "图书馆",
            "2026-10-08T10:00:00",
            "2026-10-08T12:00:00",
            BigDecimal.ZERO,
            tooManyTags,
            "DIRECT_ACCEPT",
            null,
            null,
            List.of()
        );
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(raw);

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertTrue(result.tags().size() <= 20);
    }

    private void assertFalseContains(String actual, String fragment) {
        String lower = actual == null ? "" : actual.toLowerCase();
        assertTrue(!lower.contains(fragment.toLowerCase()),
            "unexpected leak: message=" + actual + " contains=" + fragment);
    }
}
