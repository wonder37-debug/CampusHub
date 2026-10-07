package com.campushub.backend.ai.controller;

import com.campushub.backend.ai.dto.DemandDraft;
import com.campushub.backend.ai.dto.GenerateDemandDraftCommand;
import com.campushub.backend.ai.service.AiDemandApplicationService;
import com.campushub.backend.ai.service.AiDemandRateLimiter;
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
 *
 * <p>限流：per-user rate limit（1 分钟 5 次）+ 全局并发限制（3），避免 AI 调用成本失控。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiDemandController {

    private final AiDemandApplicationService aiDemandApplicationService;
    private final AiDemandRateLimiter rateLimiter;
    private final RequestUserExtractor requestUserExtractor;

    public AiDemandController(
        AiDemandApplicationService aiDemandApplicationService,
        AiDemandRateLimiter rateLimiter,
        RequestUserExtractor requestUserExtractor
    ) {
        this.aiDemandApplicationService = aiDemandApplicationService;
        this.rateLimiter = rateLimiter;
        this.requestUserExtractor = requestUserExtractor;
    }

    @PostMapping("/demand-draft")
    public ApiResponse<DemandDraft> generateDemandDraft(
        HttpServletRequest request,
        @RequestBody GenerateDemandDraftCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        // 限流：用 userId 计数，不记录 JWT / Authorization header，不传用户身份给 LLM
        rateLimiter.acquire(currentUser.userId());
        try {
            DemandDraft draft = aiDemandApplicationService.generateDraft(command);
            return ApiResponse.success(draft);
        } finally {
            rateLimiter.release();
        }
    }
}
