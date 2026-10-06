package com.campushub.backend.api;

import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;
import com.campushub.backend.review.service.ReviewApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处理基于 Response 的评价（TEAM_UP 双向评价）。
 * Order 评价仍在 {@link OrderController}。
 */
@RestController
@RequestMapping("/api/v1/demands/responses")
public class ReviewController {

    private final ReviewApplicationService reviewApplicationService;
    private final RequestUserExtractor requestUserExtractor;

    public ReviewController(
        ReviewApplicationService reviewApplicationService,
        RequestUserExtractor requestUserExtractor
    ) {
        this.reviewApplicationService = reviewApplicationService;
        this.requestUserExtractor = requestUserExtractor;
    }

    @PostMapping("/{responseId}/reviews")
    public ApiResponse<ReviewResponse> submitForResponse(
        HttpServletRequest request,
        @PathVariable Long responseId,
        @RequestBody SubmitReviewCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(reviewApplicationService.submitForResponse(currentUser.userId(), responseId, command));
    }
}
