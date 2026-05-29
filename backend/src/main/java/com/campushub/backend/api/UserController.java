package com.campushub.backend.api;

import com.campushub.backend.api.view.ReviewView;
import com.campushub.backend.api.view.UserSummaryView;
import com.campushub.backend.auth.dto.UpdateProfileCommand;
import com.campushub.backend.auth.service.AuthApplicationService;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.service.ReviewApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AuthApplicationService authApplicationService;
    private final ReviewApplicationService reviewApplicationService;
    private final UserRepository userRepository;
    private final RequestUserExtractor requestUserExtractor;

    public UserController(
        AuthApplicationService authApplicationService,
        ReviewApplicationService reviewApplicationService,
        UserRepository userRepository,
        RequestUserExtractor requestUserExtractor
    ) {
        this.authApplicationService = authApplicationService;
        this.reviewApplicationService = reviewApplicationService;
        this.userRepository = userRepository;
        this.requestUserExtractor = requestUserExtractor;
    }

    @GetMapping("/me")
    public ApiResponse<UserSummaryView> me(HttpServletRequest request) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(UserSummaryView.from(authApplicationService.getProfile(currentUser.userId())));
    }

    @GetMapping("/{userId}/reviews")
    public ApiResponse<PageResponse<ReviewView>> listReviews(
        HttpServletRequest request,
        @PathVariable Long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        if (!currentUser.isAdmin() && !currentUser.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "cannot view another user's reviews");
        }

        PageResponse<ReviewResponse> reviewPage = reviewApplicationService.listUserReviews(userId, new ReviewQuery(new PageQuery(page, size)));
        return ApiResponse.success(
            new PageResponse<>(
                reviewPage.items().stream().map(this::toReviewView).toList(),
                reviewPage.page(),
                reviewPage.size(),
                reviewPage.total()
            )
        );
    }

    @PutMapping("/me")
    public ApiResponse<UserSummaryView> updateProfile(
        HttpServletRequest request,
        @RequestBody UpdateProfileCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(
            UserSummaryView.from(authApplicationService.updateProfile(currentUser.userId(), currentUser.userId(), command))
        );
    }

    private ReviewView toReviewView(ReviewResponse review) {
        var author = userRepository.findById(review.authorId()).orElse(null);
        var target = userRepository.findById(review.targetId()).orElse(null);
        return new ReviewView(
            review.id(),
            review.orderId(),
            review.rating(),
            review.comment(),
            review.targetId(),
            target == null ? null : target.getNickname(),
            author == null ? null : UserSummaryView.from(author),
            review.createdAt()
        );
    }
}
