package com.campushub.backend.ai.service;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.InteractionMode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 需求草稿生成服务实现。
 *
 * <p>核心边界：
 * <ul>
 *   <li>只生成 DemandDraft，不调用 repository.save、不创建 Demand。</li>
 *   <li>不复制 SensitiveWordChecker，最终发布时由现有 DemandApplicationService 自然经过敏感词检查。</li>
 *   <li>所有 provider 原始异常都被转换为业务错误，不泄露 API Key / Authorization / 内部堆栈。</li>
 * </ul>
 */
@Service
public class AiDemandApplicationServiceImpl implements AiDemandApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AiDemandApplicationServiceImpl.class);

    private static final DateTimeFormatter CURRENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SYSTEM_PROMPT = """
        你是 CampusHub 的"校园需求草稿生成器"。

        任务：将用户的自然语言需求转换为结构化 DemandDraft JSON。

        严格规则：
        1. 只返回结构化 DemandDraft 的 JSON，不要输出任何解释、markdown、代码块或多余文本。
        2. 不直接发布 Demand，不访问数据库，不执行工具调用。
        3. 不编造用户没有提供的信息。无法确定的信息一律返回 null。
        4. 重要缺失信息加入 missingFields 数组（字段名使用英文驼峰：title/description/category/campusZone/location/startTime/endTime/reward/tags/interactionMode/targetParticipantCount/note）。
        5. category 只能是以下值之一：EXPRESS、ERRAND、STUDY_TUTORING、SECOND_HAND、TEAM_UP、HELP、OTHER。
        6. interactionMode 只能是以下值之一：DIRECT_ACCEPT、SELECT_ONE、SELECT_MANY、HELP。
        7. category 与 interactionMode 对应关系必须遵循：
           - EXPRESS -> DIRECT_ACCEPT
           - ERRAND -> DIRECT_ACCEPT
           - STUDY_TUTORING -> SELECT_ONE
           - SECOND_HAND -> SELECT_ONE
           - TEAM_UP -> SELECT_MANY（强制）
           - HELP -> HELP
           - OTHER -> 由用户最终确认，可填 DIRECT_ACCEPT/SELECT_ONE/SELECT_MANY，禁止 HELP
        8. TEAM_UP 必须对应 SELECT_MANY；如果用户没有明确人数，targetParticipantCount 返回 null 并把 "targetParticipantCount" 加入 missingFields。
        9. campusZone 只能是 GULOU、XIANLIN、SUZHOU；不确定时返回 null 并加入 missingFields。
        10. startTime/endTime 使用 ISO-8601 格式 yyyy-MM-dd'T'HH:mm:ss；不确定时返回 null 并加入 missingFields。
        11. reward 为数字（单位元），不确定时返回 null 并加入 missingFields；明确说"免费/无报酬"时填 0。
        12. tags 为字符串数组，没有则返回空数组 []。
        13. title 长度 3-200 字符；description 不超过 2000 字符；note 不超过 500 字符。
        14. 不要替代后端 SensitiveWordChecker，不要自行审核内容。
        15. AI 产生的是草稿，不是最终发布结果。所有最终发布都必须通过 CampusHub 现有 Demand 发布流程。

        输出 JSON 字段（按此顺序）：
        {
          "title": "...",
          "description": "...",
          "category": "...",
          "campusZone": "...",
          "location": "...",
          "startTime": "...",
          "endTime": "...",
          "reward": ...,
          "tags": [...],
          "interactionMode": "...",
          "targetParticipantCount": ...,
          "note": "...",
          "missingFields": [...]
        }
        """;

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int TITLE_MIN_LENGTH = 3;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;
    private static final int LOCATION_MAX_LENGTH = 256;
    private static final int NOTE_MAX_LENGTH = 500;
    private static final int TAGS_MAX_SIZE = 20;
    private static final int TARGET_PARTICIPANT_COUNT_MAX = 100;

    private final ChatClient chatClient;
    private final int promptMaxLength;

    @Autowired
    public AiDemandApplicationServiceImpl(
        @Autowired(required = false) ChatClient chatClient,
        @Value("${app.ai.demand-draft.prompt-max-length:1000}") int promptMaxLength
    ) {
        this.chatClient = chatClient;
        this.promptMaxLength = promptMaxLength;
    }

    @Override
    public DemandDraft generateDraft(GenerateDemandDraftCommand command) {
        if (command == null || command.prompt() == null || command.prompt().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先描述你想发布的需求");
        }
        String prompt = command.prompt().trim();
        if (prompt.length() > promptMaxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "需求描述过长，请控制在 " + promptMaxLength + " 个字符以内");
        }

        if (chatClient == null) {
            log.warn("AI 草稿生成被拒绝：ChatClient 未配置");
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 服务尚未配置，请联系管理员");
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String userPrompt = buildUserPrompt(prompt, now);

        DemandDraft raw;
        try {
            raw = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .entity(DemandDraft.class);
        } catch (Exception exception) {
            throw translateProviderException(exception);
        }

        if (raw == null) {
            log.warn("AI 返回空结果");
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 返回内容无法识别，请重新描述需求");
        }

        return validate(raw);
    }

    private String buildUserPrompt(String userPrompt, LocalDateTime now) {
        return "当前时间：" + now.format(CURRENT_TIME_FORMATTER) + "\n"
            + "时区：Asia/Shanghai\n"
            + "\n"
            + "用户输入：\n"
            + userPrompt;
    }

    /**
     * 服务端二次校验：保证非法 AI 输出不会直接到前端。
     * <ul>
     *   <li>category / interactionMode 非法 → 拒绝</li>
     *   <li>TEAM_UP 与 SELECT_MANY 一致性 → 强制修正</li>
     *   <li>targetParticipantCount / reward / 时间窗口非法 → 拒绝</li>
     *   <li>字段长度超限 → 截断并保留可用部分</li>
     *   <li>tags 数量超限 → 截断</li>
     * </ul>
     */
    DemandDraft validate(DemandDraft raw) {
        String category = normalizeEnum(raw.category(), DemandCategory::fromValue,
            "category", raw.category());
        String interactionMode = normalizeInteractionMode(raw.interactionMode());
        Integer targetParticipantCount = raw.targetParticipantCount();
        BigDecimal reward = raw.reward();
        List<String> missingFields = new ArrayList<>(
            raw.missingFields() == null ? List.of() : raw.missingFields());

        // TEAM_UP 强制 SELECT_MANY
        if ("TEAM_UP".equals(category)) {
            if (!"SELECT_MANY".equals(interactionMode)) {
                log.warn("AI 返回 TEAM_UP 但 interactionMode={}，强制修正为 SELECT_MANY", interactionMode);
                interactionMode = "SELECT_MANY";
            }
        }
        // HELP category 强制 HELP interactionMode
        if ("HELP".equals(category) && !"HELP".equals(interactionMode)) {
            log.warn("AI 返回 HELP 但 interactionMode={}，强制修正为 HELP", interactionMode);
            interactionMode = "HELP";
        }

        // targetParticipantCount 校验：仅 SELECT_MANY 允许非空
        if (targetParticipantCount != null) {
            if (targetParticipantCount < 1 || targetParticipantCount > TARGET_PARTICIPANT_COUNT_MAX) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "AI 返回内容无法识别，请重新描述需求");
            }
            if (!"SELECT_MANY".equals(interactionMode)) {
                targetParticipantCount = null;
            }
        }
        // SELECT_MANY 模式但 targetParticipantCount 为空，加入 missingFields
        if ("SELECT_MANY".equals(interactionMode) && targetParticipantCount == null
            && !missingFields.contains("targetParticipantCount")) {
            missingFields.add("targetParticipantCount");
        }

        // reward 校验
        if (reward != null && reward.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }

        // 时间校验
        LocalDateTime start = parseTime(raw.startTime(), "startTime");
        LocalDateTime end = parseTime(raw.endTime(), "endTime");
        if (start != null && end != null && !end.isAfter(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }

        // 字段长度截断（保留草稿可用部分，避免直接拒绝可恢复的输出）
        String title = truncate(raw.title(), TITLE_MAX_LENGTH);
        String description = truncate(raw.description(), DESCRIPTION_MAX_LENGTH);
        String location = truncate(raw.location(), LOCATION_MAX_LENGTH);
        String note = truncate(raw.note(), NOTE_MAX_LENGTH);

        // tags 校验
        List<String> tags = raw.tags() == null ? List.of() : raw.tags();
        if (tags.size() > TAGS_MAX_SIZE) {
            tags = tags.subList(0, TAGS_MAX_SIZE);
        }
        List<String> normalizedTags = tags.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(String::trim)
            .toList();

        return new DemandDraft(
            title,
            description,
            category,
            raw.campusZone(),
            location,
            raw.startTime(),
            raw.endTime(),
            reward,
            normalizedTags,
            interactionMode,
            targetParticipantCount,
            note,
            List.copyOf(missingFields)
        );
    }

    private String normalizeEnum(String value, java.util.function.Function<String, DemandCategory> resolver,
                                 String fieldName, String raw) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }
        DemandCategory resolved = resolver.apply(value);
        if (resolved == null) {
            log.warn("AI 返回非法 {}={}", fieldName, raw);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }
        return resolved.name();
    }

    private String normalizeInteractionMode(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }
        InteractionMode resolved = InteractionMode.fromValue(value);
        if (resolved == null) {
            log.warn("AI 返回非法 interactionMode={}", value);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }
        return resolved.name();
    }

    private LocalDateTime parseTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace(' ', 'T');
        try {
            if (normalized.length() == 16) {
                normalized = normalized + ":00";
            }
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException exception) {
            log.warn("AI 返回非法 {}={}", fieldName, value);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private BusinessException translateProviderException(Exception exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        String lower = message.toLowerCase();
        // 429 限流：提示用户稍后重试
        if (lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit")
            || lower.contains("quota")) {
            log.warn("AI provider 返回限流");
            return new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 服务繁忙，请稍后重试");
        }
        // 结构化输出解析失败：通常是模型返回非 JSON
        if (exception.getClass().getName().startsWith("org.springframework.ai")) {
            log.warn("AI 调用失败：{}", exception.getClass().getSimpleName());
            return new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 返回内容无法识别，请重新描述需求");
        }
        // 其他 provider 异常（401/403/5xx/超时）：统一兜底，不泄露内部细节
        log.warn("AI provider 异常：{}", exception.getClass().getSimpleName());
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务暂时不可用，请稍后重试");
    }
}
