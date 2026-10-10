package com.campushub.backend.ai.service;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.InteractionMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

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

    /** 统一输出格式：yyyy-MM-dd'T'HH:mm:ss，截断至秒，避免小数秒泄漏给 API 调用方 */
    private static final DateTimeFormatter ISO_SECONDS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String SYSTEM_PROMPT = """
        你是 CampusHub 的"校园需求草稿生成器"。

        任务：将用户的自然语言需求转换为结构化 DemandDraft JSON。

        严格规则：
        1. 只返回结构化 DemandDraft 的 JSON，不要输出任何解释、markdown、代码块或多余文本。
        2. 不直接发布 Demand，不访问数据库，不执行工具调用。
        3. 不编造用户没有提供的信息。无法确定的信息一律返回 null。
        4. 重要缺失信息加入 missingFields 数组（字段名使用英文驼峰：title/category/campusZone/location/startTime/endTime/reward/interactionMode/targetParticipantCount）。
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
        10. startTime/endTime 使用 ISO-8601 格式 yyyy-MM-dd'T'HH:mm:ss。
            - 用户未指定开始时间时，startTime 填入当前时间（下方提供的"当前时间"），不要返回 null，也不要加入 missingFields。
            - 用户未指定结束时间时，endTime 返回 null 并加入 missingFields。
            - 解析"5天后""五天后"等相对时间时，基于当前时间计算。例如当前时间加5天。
            - 解析"明天""下周一"等模糊日期时，基于当前时间推算。
        11. reward 为数字（单位：校邻币，不是人民币）。不确定时返回 null 并加入 missingFields；明确说"免费/无报酬"时填 0。
            - 用户明确写出"50校邻币"时，reward 填 50。
            - 用户明确使用其他货币（如"50元""50块钱""50 RMB"等人民币或其他货币）时，不得直接当作校邻币，也不得擅自换算；reward 返回 null 并把 "reward" 加入 missingFields，让用户确认。
        12. tags 为字符串数组，没有则返回空数组 []。
        13. title 长度 3-200 字符；description 不超过 2000 字符。
        14. 不要替代后端 SensitiveWordChecker，不要自行审核内容。
        15. AI 产生的是草稿，不是最终发布结果。所有最终发布都必须通过 CampusHub 现有 Demand 发布流程。

        联系方式（contactInfo）：
        16. 从用户自然语言中识别 QQ 号、微信号、手机号、邮箱等联系方式，填入 contactInfo 字段。
            - 格式示例："QQ: 123456789" 或 "微信: abcdef" 或 "手机: 13800138000" 或 "邮箱: test@example.com"
            - 多个联系方式用分号分隔，如 "QQ: 123456789; 手机: 13800138000"
            - 不要把联系方式写进 title 或 description 中。
            - 没有识别到联系方式时，contactInfo 返回 null（不加入 missingFields，联系方式为选填）。

        匿名发布（anonymous）：
        17. 用户明确要求匿名发布（如"匿名发布""匿名""不要透露身份"等）时，anonymous 填 true。
            - 用户未提及匿名要求时，anonymous 返回 null（不覆盖用户已有的手动选择）。
            - 不要在 title 或 description 中写"匿名发布"等字样。

        描述去重（description）：
        18. description 中不要机械重复已有独立字段的信息（地点、截止时间、联系方式、报酬、匿名标记等）。
            - 但应保留实际任务内容和必要要求。例如"还需要5名队员，其中需要一位守门员"是任务内容，应保留。
            - 如果用户原始输入中的联系方式、地点、时间等信息已提取到对应字段，description 中不应重复这些信息。

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
          "contactInfo": "...",
          "anonymous": ...,
          "missingFields": [...]
        }
        """;

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;
    private static final int LOCATION_MAX_LENGTH = 256;
    private static final int TAGS_MAX_SIZE = 20;
    private static final int TARGET_PARTICIPANT_COUNT_MAX = 100;
    private static final int CONTACT_INFO_MAX_LENGTH = 200;

    /**
     * missingFields 白名单：只允许 DemandDraft 已知字段名，避免 AI 注入任意字符串到前端提示。
     */
    private static final Set<String> MISSING_FIELD_WHITELIST = Set.of(
        "title", "category", "campusZone", "location",
        "startTime", "endTime", "reward", "interactionMode", "targetParticipantCount"
    );

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
     *
     * <p>interactionMode 处理顺序（关键）：
     * <ol>
     *   <li>先校验 category 合法（null/非法直接拒绝，不进入 missingFields）。</li>
     *   <li>TEAM_UP 先强制 {@link InteractionMode#SELECT_MANY}，不管原 interactionMode（即使 null）。</li>
     *   <li>HELP 先强制 {@link InteractionMode#HELP}，不管原 interactionMode。</li>
     *   <li>OTHER 禁止 HELP；null 则加入 missingFields 让用户选择；非空必须合法且非 HELP。</li>
     *   <li>固定分类（EXPRESS/ERRAND/STUDY_TUTORING/SECOND_HAND）用 {@link InteractionMode#resolve} 推导，忽略 AI 返回值。</li>
     * </ol>
     *
     * <p>missingFields canonicalization：服务端根据字段最终值重新计算缺失字段，
     * 不盲目信任 LLM 声明的 missingFields（LLM 漏报的补充，LLM 误报的移除）。
     */
    DemandDraft validate(DemandDraft raw) {
        String category = normalizeEnum(raw.category(), DemandCategory::fromValue, "category", raw.category());
        DemandCategory categoryEnum = DemandCategory.fromValue(category);

        LinkedHashSet<String> missingFields = new LinkedHashSet<>(
            raw.missingFields() == null ? List.of() : raw.missingFields());

        // interactionMode 按 category 优先级处理
        String interactionMode = resolveInteractionMode(categoryEnum, raw.interactionMode(), missingFields);

        // campusZone 校验：非空时必须合法枚举
        String campusZone = normalizeCampusZone(raw.campusZone());

        Integer targetParticipantCount = raw.targetParticipantCount();
        BigDecimal reward = raw.reward();

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

        // reward 校验
        if (reward != null && reward.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }

        // 时间校验
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = parseTime(raw.startTime(), "startTime");
        // startTime 默认为当前时间（Asia/Shanghai），不要求用户手动补填
        String startTimeStr;
        if (start != null) {
            // 统一格式化 AI 返回的时间，避免小数秒格式泄漏
            start = start.truncatedTo(ChronoUnit.SECONDS);
            // 不允许过去开始时间，与正式发布 validateTimeWindow 一致
            if (start.isBefore(now)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "AI 返回内容无法识别，请重新描述需求");
            }
            startTimeStr = start.format(ISO_SECONDS_FORMATTER);
        } else {
            start = now;
            startTimeStr = now.format(ISO_SECONDS_FORMATTER);
        }
        LocalDateTime end = parseTime(raw.endTime(), "endTime");
        String endTimeStr = null;
        if (end != null) {
            end = end.truncatedTo(ChronoUnit.SECONDS);
            endTimeStr = end.format(ISO_SECONDS_FORMATTER);
        }
        if (start != null && end != null && !end.isAfter(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }

        // 字段长度截断（保留草稿可用部分，避免直接拒绝可恢复的输出）
        String title = truncate(raw.title(), TITLE_MAX_LENGTH);
        String description = truncate(raw.description(), DESCRIPTION_MAX_LENGTH);
        String location = truncate(raw.location(), LOCATION_MAX_LENGTH);

        // contactInfo 校验：截断 + 去除前后空白
        String contactInfo = truncate(raw.contactInfo() == null ? null : raw.contactInfo().trim(), CONTACT_INFO_MAX_LENGTH);

        // anonymous 校验：只允许 true 或 null（null 表示未提及，不覆盖用户已有选择）
        Boolean anonymous = null;
        if (raw.anonymous() != null) {
            if (raw.anonymous()) {
                anonymous = Boolean.TRUE;
            } else {
                // AI 返回 false 也当作 null（不主动取消用户已有的匿名选择）
                anonymous = null;
            }
        }

        // tags 校验
        List<String> tags = raw.tags() == null ? List.of() : raw.tags();
        if (tags.size() > TAGS_MAX_SIZE) {
            tags = tags.subList(0, TAGS_MAX_SIZE);
        }
        List<String> normalizedTags = tags.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(String::trim)
            .toList();

        // canonicalize missingFields：服务端根据字段最终值重算（不信任 LLM 声明）
        // 先 trim 所有 missingFields 中的元素，确保后续 remove 能精确匹配
        LinkedHashSet<String> trimmedMissingFields = new LinkedHashSet<>();
        for (String field : missingFields) {
            if (field != null && !field.isBlank()) {
                trimmedMissingFields.add(field.trim());
            }
        }
        missingFields.clear();
        missingFields.addAll(trimmedMissingFields);
        canonicalizeMissingFields(missingFields, category, title, campusZone, location,
            startTimeStr, endTimeStr, reward, interactionMode, targetParticipantCount);

        // 白名单 + 去重
        List<String> normalizedMissingFields = normalizeMissingFields(missingFields);

        return new DemandDraft(
            title,
            description,
            category,
            campusZone,
            location,
            startTimeStr,
            endTimeStr,
            reward,
            normalizedTags,
            interactionMode,
            targetParticipantCount,
            contactInfo,
            anonymous,
            normalizedMissingFields
        );
    }

    /**
     * 按 category 优先级解析 interactionMode。
     *
     * <p>TEAM_UP / HELP 强制覆盖原值；OTHER 禁止 HELP 且非空必须合法；固定分类用 resolve 推导。
     */
    private String resolveInteractionMode(DemandCategory category, String rawMode, LinkedHashSet<String> missingFields) {
        if (category == DemandCategory.TEAM_UP) {
            // TEAM_UP 先强制 SELECT_MANY，不管原 interactionMode（即使 null 或非法）
            if (rawMode != null && !rawMode.isBlank()) {
                InteractionMode parsed = InteractionMode.fromValue(rawMode);
                if (parsed != null && parsed != InteractionMode.SELECT_MANY) {
                    log.warn("AI 返回 TEAM_UP 但 interactionMode={}，强制修正为 SELECT_MANY", rawMode);
                }
            }
            return InteractionMode.SELECT_MANY.name();
        }
        if (category == DemandCategory.HELP) {
            // HELP 先强制 HELP，不管原 interactionMode（即使 null 或非法）
            if (rawMode != null && !rawMode.isBlank()) {
                InteractionMode parsed = InteractionMode.fromValue(rawMode);
                if (parsed != null && parsed != InteractionMode.HELP) {
                    log.warn("AI 返回 HELP 但 interactionMode={}，强制修正为 HELP", rawMode);
                }
            }
            return InteractionMode.HELP.name();
        }
        if (category == DemandCategory.OTHER) {
            // OTHER 由用户最终确认；AI 返回的值必须合法且不能是 HELP
            if (rawMode == null || rawMode.isBlank()) {
                missingFields.add("interactionMode");
                return null;
            }
            InteractionMode parsed = InteractionMode.fromValue(rawMode);
            if (parsed == null) {
                log.warn("AI 返回非法 interactionMode={}", rawMode);
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "AI 返回内容无法识别，请重新描述需求");
            }
            if (parsed == InteractionMode.HELP) {
                // OTHER 禁止 HELP
                log.warn("AI 返回 OTHER 但 interactionMode=HELP，禁止");
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "AI 返回内容无法识别，请重新描述需求");
            }
            return parsed.name();
        }
        // 固定分类：用 resolve 推导（忽略 AI 返回的 interactionMode）
        InteractionMode resolved = InteractionMode.resolve(category);
        return resolved == null ? InteractionMode.DIRECT_ACCEPT.name() : resolved.name();
    }

    /**
     * 服务端根据字段最终值重新计算 missingFields。
     *
     * <p>结合现有 Demand 发布规则（前端 runValidations / 后端 validatePublishCommand）：
     * <ul>
     *   <li>必填字段（title/campusZone/location/startTime/endTime/reward）：null/空 → 加入 missingFields；
     *       有值（含 reward=0）→ 移除（不信任 LLM 漏报或误报）。</li>
     *   <li>targetParticipantCount：仅 SELECT_MANY 必填；非 SELECT_MANY 时不应出现在 missingFields。</li>
     *   <li>interactionMode：仅 OTHER 必填；非 OTHER 时不应出现在 missingFields。</li>
     *   <li>category：null/非法直接拒绝（不进入 missingFields）。</li>
     *   <li>description/tags：可空，不进入 missingFields。</li>
     * </ul>
     */
    private void canonicalizeMissingFields(LinkedHashSet<String> missingFields, String category,
        String title, String campusZone, String location,
        String startTime, String endTime, BigDecimal reward,
        String interactionMode, Integer targetParticipantCount) {
        updateMissing(missingFields, "title", isBlank(title));
        updateMissing(missingFields, "campusZone", isBlank(campusZone));
        updateMissing(missingFields, "location", isBlank(location));
        // startTime 已默认为当前时间，不再算作缺失
        missingFields.remove("startTime");
        updateMissing(missingFields, "endTime", isBlank(endTime));
        // reward：null → 缺失；0 → 有效值（不缺失）
        updateMissing(missingFields, "reward", reward == null);
        // targetParticipantCount：仅 SELECT_MANY 必填
        if ("SELECT_MANY".equals(interactionMode)) {
            updateMissing(missingFields, "targetParticipantCount", targetParticipantCount == null);
        } else {
            missingFields.remove("targetParticipantCount");
        }
        // interactionMode：仅 OTHER 必填（OTHER+null 已在 resolveInteractionMode 加入）
        if (!"OTHER".equals(category)) {
            missingFields.remove("interactionMode");
        }
    }

    private void updateMissing(LinkedHashSet<String> missingFields, String field, boolean isMissing) {
        if (isMissing) {
            missingFields.add(field);
        } else {
            missingFields.remove(field);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private String normalizeCampusZone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CampusZone.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            log.warn("AI 返回非法 campusZone={}", value);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "AI 返回内容无法识别，请重新描述需求");
        }
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

    /**
     * missingFields 白名单 + 去重：只保留 DemandDraft 已知字段名，避免 AI 注入任意字符串到前端提示。
     */
    private List<String> normalizeMissingFields(LinkedHashSet<String> missingFields) {
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String field : missingFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String trimmed = field.trim();
            if (MISSING_FIELD_WHITELIST.contains(trimmed)) {
                deduped.add(trimmed);
            }
        }
        return List.copyOf(deduped);
    }

    /**
     * Provider 异常分类（关键：不把网络故障误报成"AI 返回内容无法识别"）。
     *
     * <ol>
     *   <li>429 限流（message contains 429/too many requests/rate limit/quota）→ VALIDATION_FAILED "AI 服务繁忙"。
     *       Spring AI 可能把 429 包装成 NonTransientAiException，但 message 仍含 429，故优先识别。</li>
     *   <li>Spring AI NonTransientAiException / TransientAiException（401/403/5xx）→ INTERNAL_ERROR "AI 服务暂时不可用"。</li>
     *   <li>网络/HTTP 故障（ResourceAccessException / ConnectException / SocketTimeoutException，含 cause-chain）→ INTERNAL_ERROR "AI 服务暂时不可用"。</li>
     *   <li>结构化输出解析失败（cause-chain 含 JsonProcessingException）→ VALIDATION_FAILED "AI 返回内容无法识别"。
     *       Spring AI BeanOutputConverter 用 ObjectMapper.readValue，失败时抛 RuntimeException(cause=JsonProcessingException)。</li>
     *   <li>未知异常 → INTERNAL_ERROR "AI 服务暂时不可用"（不误判为 JSON 解析失败）。</li>
     * </ol>
     */
    private BusinessException translateProviderException(Exception exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        String lower = message.toLowerCase();
        // 429 限流：优先处理，提示用户稍后重试
        if (lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit")
            || lower.contains("quota")) {
            log.warn("AI provider 返回限流");
            return new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 服务繁忙，请稍后重试");
        }
        // Spring AI provider 异常（401/403/5xx）→ INTERNAL_ERROR，不泄露内部细节
        if (exception instanceof NonTransientAiException || exception instanceof TransientAiException) {
            log.warn("AI provider 异常：{}", exception.getClass().getSimpleName());
            return new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务暂时不可用，请稍后重试");
        }
        // 网络/HTTP 故障（含 cause-chain）→ INTERNAL_ERROR，不误判为结构化输出失败
        if (hasCauseInChain(exception, ResourceAccessException.class, ConnectException.class, SocketTimeoutException.class)) {
            log.warn("AI 网络异常：{}", exception.getClass().getSimpleName());
            return new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务暂时不可用，请稍后重试");
        }
        // 结构化输出解析失败（cause-chain 含 JsonProcessingException）→ VALIDATION_FAILED
        if (hasCauseInChain(exception, JsonProcessingException.class)) {
            log.warn("AI 结构化输出解析失败：{}", exception.getClass().getSimpleName());
            return new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 返回内容无法识别，请重新描述需求");
        }
        // 未知异常：优先 INTERNAL_ERROR，不误判为 JSON 解析失败
        log.warn("AI 未知异常：{}", exception.getClass().getSimpleName());
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务暂时不可用，请稍后重试");
    }

    /**
     * 沿 cause-chain 检查异常或其 cause 是否为指定类型。
     */
    private boolean hasCauseInChain(Throwable throwable, Class<?>... targetTypes) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            for (Class<?> targetType : targetTypes) {
                if (targetType.isInstance(current)) {
                    return true;
                }
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
