package com.campushub.backend.ai.service;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;

/**
 * AI 需求草稿生成应用服务。只负责把自然语言转换为 DemandDraft，
 * 不访问数据库、不创建 Demand、不执行工具调用。
 */
public interface AiDemandApplicationService {

    DemandDraft generateDraft(GenerateDemandDraftCommand command);
}
