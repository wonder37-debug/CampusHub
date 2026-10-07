package com.campushub.backend.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

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

    /** 构造 DemandDraft（note 字段已删除，12 参数）。 */
    private DemandDraft buildRaw(String title, String description, String category, String campusZone,
        String location, String startTime, String endTime, BigDecimal reward,
        List<String> tags, String interactionMode, Integer targetParticipantCount,
        List<String> missingFields) {
        return new DemandDraft(title, description, category, campusZone, location,
            startTime, endTime, reward, tags, interactionMode, targetParticipantCount, missingFields);
    }

    @Test
    void shouldGenerateValidExpressDemand() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "代取快递并送到南区宿舍", "明天下午三点帮我从菜鸟驿站取快递送到南区宿舍",
            "EXPRESS", "XIANLIN", "南区宿舍", "2026-10-08T15:00:00", "2026-10-08T17:00:00",
            new BigDecimal("10"), List.of("快递", "跑腿"), "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand(
            "明天下午三点帮我从菜鸟驿站取快递送到南区宿舍，给10元"));

        assertEquals("EXPRESS", result.category());
        assertEquals("DIRECT_ACCEPT", result.interactionMode());
        assertEquals(new BigDecimal("10"), result.reward());
        assertEquals("代取快递并送到南区宿舍", result.title());
        // 所有必填字段都有值，canonicalize 后 missingFields 为空
        assertTrue(result.missingFields().isEmpty());
    }

    @Test
    void shouldNotFabricateMissingInfo() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "帮我找人买咖啡", "帮我找人买咖啡", "ERRAND", null, null, null, null,
            null, List.of(), "DIRECT_ACCEPT", null,
            List.of("campusZone", "location", "startTime", "endTime", "reward")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("帮我找人买咖啡"));

        assertEquals("ERRAND", result.category());
        assertEquals("DIRECT_ACCEPT", result.interactionMode());
        assertNull(result.campusZone());
        assertNull(result.location());
        assertNull(result.startTime());
        assertNull(result.endTime());
        assertNull(result.reward());
        // canonicalize 根据实际 null 字段补充 missingFields
        assertTrue(result.missingFields().contains("campusZone"));
        assertTrue(result.missingFields().contains("reward"));
        assertTrue(result.missingFields().contains("startTime"));
        assertTrue(result.missingFields().contains("endTime"));
    }

    @Test
    void shouldResolveTeamUpDemand() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "周六下午找3个人一起打羽毛球", "周六下午找3个人一起打羽毛球",
            "TEAM_UP", null, null, "2026-10-10T14:00:00", "2026-10-10T17:00:00",
            BigDecimal.ZERO, List.of("羽毛球"), "SELECT_MANY", 3,
            List.of("campusZone", "location")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("周六下午找3个人一起打羽毛球"));

        assertEquals("TEAM_UP", result.category());
        assertEquals("SELECT_MANY", result.interactionMode());
        assertEquals(3, result.targetParticipantCount());
        // reward=0 是有效值，不应进入 missingFields
        assertFalse(result.missingFields().contains("reward"));
        // targetParticipantCount=3 有值，不应进入 missingFields
        assertFalse(result.missingFields().contains("targetParticipantCount"));
    }

    @Test
    void shouldForceSelectManyForTeamUpWhenInteractionModeMismatched() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "组队打球", "组队打球", "TEAM_UP", "XIANLIN", "操场",
            "2026-10-10T14:00:00", "2026-10-10T17:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", 3, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("组队打球"));

        assertEquals("TEAM_UP", result.category());
        assertEquals("SELECT_MANY", result.interactionMode());
    }

    @Test
    void shouldAddTargetParticipantCountToMissingFieldsWhenTeamUpWithoutCount() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "找几个人一起打羽毛球", "找几个人一起打羽毛球", "TEAM_UP", null, null,
            null, null, BigDecimal.ZERO, List.of(), "SELECT_MANY", null,
            List.of("campusZone", "location", "startTime", "endTime")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("找几个人一起打羽毛球"));

        assertEquals("TEAM_UP", result.category());
        assertEquals("SELECT_MANY", result.interactionMode());
        assertNull(result.targetParticipantCount());
        // canonicalize 补充 targetParticipantCount（SELECT_MANY + null）
        assertTrue(result.missingFields().contains("targetParticipantCount"));
    }

    @Test
    void shouldRejectInvalidCategory() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "INVALID", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("随便说点什么")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("无法识别"));
    }

    @Test
    void shouldRejectInvalidInteractionModeForOther() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "OTHER", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "INVALID_MODE", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("随便说点什么")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectHelpModeForOtherCategory() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "OTHER", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "HELP", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("其他需求")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldAcceptOtherCategoryWithSelectOneMode() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "其他需求", "描述", "OTHER", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "SELECT_ONE", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("其他需求"));

        assertEquals("OTHER", result.category());
        assertEquals("SELECT_ONE", result.interactionMode());
    }

    @Test
    void shouldForceSelectManyForTeamUpWithNullInteractionMode() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "组队打球", "组队打球", "TEAM_UP", "XIANLIN", "操场",
            "2026-10-10T14:00:00", "2026-10-10T17:00:00", BigDecimal.ZERO,
            List.of(), null, 3, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("组队打球"));

        assertEquals("TEAM_UP", result.category());
        // TEAM_UP + null interactionMode 仍强制 SELECT_MANY
        assertEquals("SELECT_MANY", result.interactionMode());
        assertEquals(3, result.targetParticipantCount());
    }

    @Test
    void shouldForceHelpInteractionModeForHelpCategory() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "求助问题", "求助问题", "HELP", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), null, null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("求助"));

        assertEquals("HELP", result.category());
        // HELP category 即使 interactionMode=null 也强制 HELP
        assertEquals("HELP", result.interactionMode());
    }

    @Test
    void shouldRejectInvalidCampusZone() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "EXPRESS", "INVALID_ZONE", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldAcceptNullCampusZone() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "取快递", "描述", "EXPRESS", null, "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of("campusZone")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertNull(result.campusZone());
    }

    @Test
    void shouldNormalizeCampusZoneCaseInsensitive() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "取快递", "描述", "EXPRESS", "xianlin", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertEquals("XIANLIN", result.campusZone());
    }

    @Test
    void shouldRejectNegativeReward() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", new BigDecimal("-5"),
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("随便说点什么")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectInvalidTargetParticipantCount() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "组队", "组队", "TEAM_UP", "XIANLIN", "操场",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "SELECT_MANY", 0, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("组队")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectTargetParticipantCountExceeding100() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "组队", "组队", "TEAM_UP", "XIANLIN", "操场",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "SELECT_MANY", 200, List.of()));

        assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("组队")));
    }

    @Test
    void shouldRejectEndTimeBeforeStartTime() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T12:00:00", "2026-10-08T10:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectInvalidTimeFormat() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "not-a-date", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    // ========== reward / missingFields 边界测试（P1-5）==========

    @Test
    void shouldCanonicalizeRewardNullToMissingFields() {
        // Case 1: reward=null, LLM 声明 missingFields=[]，服务端必须补充 reward
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "取快递", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", null,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertNull(result.reward());
        assertTrue(result.missingFields().contains("reward"),
            "reward=null 必须进入 missingFields，不能因 LLM 漏报而认为已存在");
    }

    @Test
    void shouldNotAddRewardToMissingFieldsWhenZero() {
        // Case 2: reward=0 是有效值，不应进入 missingFields
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "取快递", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertEquals(BigDecimal.ZERO, result.reward());
        assertFalse(result.missingFields().contains("reward"),
            "reward=0 是有效值，不应进入 missingFields");
    }

    @Test
    void shouldRemoveRewardFromMissingFieldsWhenPresent() {
        // Case 3: reward=positive，LLM 误报 missingFields=["reward"]，服务端应以实际值为准移除
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "取快递", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", new BigDecimal("10"),
            List.of(), "DIRECT_ACCEPT", null, List.of("reward")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertEquals(new BigDecimal("10"), result.reward());
        assertFalse(result.missingFields().contains("reward"),
            "reward 已有值，服务端 canonicalize 后不应再当作缺失字段");
    }

    @Test
    void shouldCanonicalizeNullRequiredFieldsToMissingFields() {
        // 多个必填字段为 null，服务端必须全部补充到 missingFields
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            null, null, "EXPRESS", null, null, null, null, null,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertTrue(result.missingFields().contains("title"));
        assertTrue(result.missingFields().contains("campusZone"));
        assertTrue(result.missingFields().contains("location"));
        assertTrue(result.missingFields().contains("startTime"));
        assertTrue(result.missingFields().contains("endTime"));
        assertTrue(result.missingFields().contains("reward"));
    }

    @Test
    void shouldNotIncludeOptionalFieldsInMissingFields() {
        // description/tags 可空，不应进入 missingFields
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", null, "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of("description", "tags")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        // description/tags 不在白名单，被过滤；其他必填字段有值不在 missingFields
        assertFalse(result.missingFields().contains("description"));
        assertFalse(result.missingFields().contains("tags"));
        assertTrue(result.missingFields().isEmpty());
    }

    @Test
    void shouldDeduplicateAndWhitelistMissingFields() {
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "买咖啡", "描述", "ERRAND", null, null, null, null, null,
            List.of(), "DIRECT_ACCEPT", null,
            List.of("campusZone", "campusZone", "location", "reward", "invalidField", "anotherJunk", "  startTime  ", "")));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("帮我找人买咖啡"));

        // 白名单过滤掉 invalidField/anotherJunk，去重 campusZone，trim startTime
        assertTrue(result.missingFields().contains("campusZone"));
        assertTrue(result.missingFields().contains("location"));
        assertTrue(result.missingFields().contains("reward"));
        assertTrue(result.missingFields().contains("startTime"));
        // canonicalize 补充 endTime（raw 的 endTime=null，原 missingFields 没声明）
        assertTrue(result.missingFields().contains("endTime"));
        assertEquals(5, result.missingFields().size());
        assertFalse(result.missingFields().contains("invalidField"));
        assertFalse(result.missingFields().contains("anotherJunk"));
    }

    // ========== 异常分类测试（P1-3/4）==========

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
    void shouldTranslateConnectExceptionAsInternalError() {
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new RuntimeException(new ConnectException("Connection refused")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("AI 服务暂时不可用，请稍后重试", exception.getMessage());
        // 不能误判为"AI 返回内容无法识别"
        assertFalse(exception.getMessage().contains("无法识别"));
        assertFalseContains(exception.getMessage(), "Connection");
    }

    @Test
    void shouldTranslateSocketTimeoutExceptionAsInternalError() {
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("AI 服务暂时不可用，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("无法识别"));
    }

    @Test
    void shouldTranslateResourceAccessExceptionWithSocketTimeoutCauseAsInternalError() {
        ResourceAccessException ex = new ResourceAccessException(
            "I/O error on POST request for \"https://tokenhub.tencentmaas.com/v1/chat/completions\": Read timed out",
            new SocketTimeoutException("Read timed out"));
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenThrow(ex);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("AI 服务暂时不可用，请稍后重试", exception.getMessage());
        // 不泄露 provider URL / 内部堆栈
        assertFalse(exception.getMessage().contains("无法识别"));
        assertFalseContains(exception.getMessage(), "tokenhub");
        assertFalseContains(exception.getMessage(), "chat/completions");
    }

    @Test
    void shouldTranslateStructuredOutputFailureWithJsonProcessingCause() {
        // 结构化输出解析失败：BeanOutputConverter 用 ObjectMapper.readValue 失败时抛
        // RuntimeException(cause=JsonProcessingException)。JsonParseException extends JsonProcessingException。
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new RuntimeException("Failed to convert response to DemandDraft",
                new JsonParseException("invalid JSON", JsonLocation.NA)));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("AI 返回内容无法识别，请重新描述需求", exception.getMessage());
        // 不泄露 provider 内部细节
        assertFalseContains(exception.getMessage(), "convert");
        assertFalseContains(exception.getMessage(), "JSON");
    }

    @Test
    void shouldTranslateUnknownExceptionAsInternalErrorNotJsonFailure() {
        // 未知异常：优先 INTERNAL_ERROR，不误判为 JSON 解析失败
        when(callResponseSpec.entity(eq(DemandDraft.class)))
            .thenThrow(new IllegalStateException("unexpected runtime state"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.generateDraft(new GenerateDemandDraftCommand("取快递")));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("AI 服务暂时不可用，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("无法识别"));
        assertFalseContains(exception.getMessage(), "unexpected");
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
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            longTitle, longDescription, "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            List.of(), "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertNotNull(result.title());
        assertTrue(result.title().length() <= 200);
        assertTrue(result.description().length() <= 2000);
    }

    @Test
    void shouldTruncateTagsExceedingLimit() {
        List<String> tooManyTags = java.util.stream.Stream.generate(() -> "tag").limit(30).toList();
        when(callResponseSpec.entity(eq(DemandDraft.class))).thenReturn(buildRaw(
            "标题", "描述", "EXPRESS", "XIANLIN", "图书馆",
            "2026-10-08T10:00:00", "2026-10-08T12:00:00", BigDecimal.ZERO,
            tooManyTags, "DIRECT_ACCEPT", null, List.of()));

        DemandDraft result = service.generateDraft(new GenerateDemandDraftCommand("取快递"));

        assertTrue(result.tags().size() <= 20);
    }

    private void assertFalseContains(String actual, String fragment) {
        String lower = actual == null ? "" : actual.toLowerCase();
        assertTrue(!lower.contains(fragment.toLowerCase()),
            "unexpected leak: message=" + actual + " contains=" + fragment);
    }
}
