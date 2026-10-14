package com.campushub.backend.api;

import com.campushub.backend.api.view.PublicUserSummaryView;
import com.campushub.backend.api.view.ReviewView;
import com.campushub.backend.api.view.UserStatsView;
import com.campushub.backend.api.view.UserSummaryView;
import com.campushub.backend.auth.domain.User;
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
import com.campushub.backend.order.repository.OrderRepository;
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
    private final OrderRepository orderRepository;
    private final RequestUserExtractor requestUserExtractor;
    private final ApiViewMapper apiViewMapper;

    public UserController(
        AuthApplicationService authApplicationService,
        ReviewApplicationService reviewApplicationService,
        UserRepository userRepository,
        OrderRepository orderRepository,
        RequestUserExtractor requestUserExtractor,
        ApiViewMapper apiViewMapper
    ) {
        this.authApplicationService = authApplicationService;
        this.reviewApplicationService = reviewApplicationService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.requestUserExtractor = requestUserExtractor;
        this.apiViewMapper = apiViewMapper;
    }

    @GetMapping("/me")
    public ApiResponse<UserSummaryView> me(HttpServletRequest request) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(UserSummaryView.from(authApplicationService.getProfile(currentUser.userId())));
    }

    @GetMapping("/{userId}")
    public ApiResponse<PublicUserSummaryView> getPublicProfile(
        HttpServletRequest request,
        @PathVariable Long userId
    ) {
        requestUserExtractor.requireCurrentUser(request);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));
        return ApiResponse.success(PublicUserSummaryView.from(user));
    }

    @GetMapping("/{userId}/reviews")
    public ApiResponse<PageResponse<ReviewView>> listReviews(
        HttpServletRequest request,
        @PathVariable Long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        // 公开信任档案传 received 仅看收到的评价；本人“我的评价”不传（默认 all）保持双向
        @RequestParam(required = false) String direction
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        PageResponse<ReviewResponse> reviewPage = reviewApplicationService.listUserReviews(
            userId, new ReviewQuery(new PageQuery(page, size), direction));
        return ApiResponse.success(
            new PageResponse<>(
                reviewPage.items().stream().map(r -> apiViewMapper.toAnonymizedReviewView(r, currentUser)).toList(),
                reviewPage.page(),
                reviewPage.size(),
                reviewPage.total()
            )
        );
    }

    @GetMapping("/{userId}/stats")
    public ApiResponse<UserStatsView> getStats(
        HttpServletRequest request,
        @PathVariable Long userId
    ) {
        requestUserExtractor.requireCurrentUser(request);
        long completedOrders = orderRepository.countCompletedByUserId(userId);
        return ApiResponse.success(new UserStatsView((int) completedOrders));
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
}
