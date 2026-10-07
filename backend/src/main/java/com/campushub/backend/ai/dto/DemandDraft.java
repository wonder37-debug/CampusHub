package com.campushub.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * AI 生成的需求草稿。仅作为前端回填的中间结构，不参与数据库写入。
 *
 * <p>字段类型尽量与现有 Demand / PublishDemandCommand 保持一致：
 * <ul>
 *   <li>{@code category} 仅允许 EXPRESS/ERRAND/STUDY_TUTORING/SECOND_HAND/TEAM_UP/HELP/OTHER</li>
 *   <li>{@code interactionMode} 仅允许 DIRECT_ACCEPT/SELECT_ONE/SELECT_MANY/HELP</li>
 *   <li>{@code campusZone} 仅允许 GULOU/XIANLIN/SUZHOU</li>
 *   <li>{@code startTime}/{@code endTime} 使用 ISO-8601 字符串，服务端解析为 LocalDateTime 后校验</li>
 *   <li>{@code missingFields} 标识 AI 未能从自然语言中提取的必填字段，前端据此提示用户补充</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DemandDraft(
    String title,
    String description,
    String category,
    String campusZone,
    String location,
    String startTime,
    String endTime,
    BigDecimal reward,
    List<String> tags,
    String interactionMode,
    Integer targetParticipantCount,
    List<String> missingFields
) {
}
