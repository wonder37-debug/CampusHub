package com.campushub.backend.demand.dto;

import java.time.LocalDateTime;

/**
 * 当前用户提交的响应（跨需求），附带关联 Demand 摘要信息，
 * 供"我参与的互助"列表使用。WITHDRAWN 响应的 content 字段已脱敏。
 */
public record MyDemandResponseView(
    Long id,
    Long demandId,
    String demandTitle,
    String demandStatus,
    String demandCategory,
    String demandInteractionMode,
    Long authorId,
    String authorName,
    String content,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
