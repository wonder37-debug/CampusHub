package com.campushub.backend.ai.controller;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;
import com.campushub.backend.ai.service.AiDemandApplicationService;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 需求草稿生成 API。
 *
 * <p>POST /api/v1/ai/demand-draft 需要登录，仅返回 DemandDraft 草稿，
 * 不创建 Demand、不写入数据库。最终发布仍走 POST /api/v1/demands。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiDemandController {

    private final AiDemandApplicationService aiDemandApplicationService;
    private final RequestUserExtractor requestUserExtractor;

    public AiDemandController(
        AiDemandApplicationService aiDemandApplicationService,
        RequestUserExtractor requestUserExtractor
    ) {
        this.aiDemandApplicationService = aiDemandApplicationService;
        this.requestUserExtractor = requestUserExtractor;
    }

    @PostMapping("/demand-draft")
    public ApiResponse<DemandDraft> generateDemandDraft(
        HttpServletRequest request,
        @RequestBody GenerateDemandDraftCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        // 当前用户身份仅用于鉴权，不传入 AI 模型，避免泄露用户信息
        DemandDraft draft = aiDemandApplicationService.generateDraft(command);
        return ApiResponse.success(draft);
    }
}
