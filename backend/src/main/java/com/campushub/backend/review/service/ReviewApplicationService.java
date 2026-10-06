package com.campushub.backend.review.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;

public interface ReviewApplicationService {

    ReviewResponse submit(Long operatorId, Long orderId, SubmitReviewCommand command);

    /**
     * 基于 Response 提交评价（TEAM_UP 双向评价）。
     * 只有 SELECTED 状态的 Response 且 Demand 已 COMPLETED 才能评价。
     * 同一参与关系中同一用户只能评价一次。
     */
    ReviewResponse submitForResponse(Long operatorId, Long responseId, SubmitReviewCommand command);

    PageResponse<ReviewResponse> listUserReviews(Long targetUserId, ReviewQuery query);

    int recalculateCreditScore(Long userId);
}
