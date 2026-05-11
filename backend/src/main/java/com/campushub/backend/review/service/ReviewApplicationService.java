package com.campushub.backend.review.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;

public interface ReviewApplicationService {

    ReviewResponse submit(Long operatorId, Long orderId, SubmitReviewCommand command);

    PageResponse<ReviewResponse> listUserReviews(Long targetUserId, ReviewQuery query);

    int recalculateCreditScore(Long userId);
}
