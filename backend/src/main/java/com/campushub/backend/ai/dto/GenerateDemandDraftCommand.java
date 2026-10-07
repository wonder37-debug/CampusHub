package com.campushub.backend.ai.dto;

/**
 * AI 需求草稿生成请求。仅包含用户输入的自然语言 prompt，不携带任何用户身份敏感字段。
 */
public record GenerateDemandDraftCommand(String prompt) {
}
