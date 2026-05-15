package com.campushub.backend.api;

import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.review.dto.SubmitReviewCommand;
import com.campushub.backend.review.service.ReviewApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;
    private final OrderRepository orderRepository;
    private final ReviewApplicationService reviewApplicationService;
    private final RequestUserExtractor requestUserExtractor;
    private final ApiViewMapper apiViewMapper;

    public OrderController(
        OrderApplicationService orderApplicationService,
        OrderRepository orderRepository,
        ReviewApplicationService reviewApplicationService,
        RequestUserExtractor requestUserExtractor,
        ApiViewMapper apiViewMapper
    ) {
        this.orderApplicationService = orderApplicationService;
        this.orderRepository = orderRepository;
        this.reviewApplicationService = reviewApplicationService;
        this.requestUserExtractor = requestUserExtractor;
        this.apiViewMapper = apiViewMapper;
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderView>> list(
        HttpServletRequest request,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        PageResponse<OrderSummaryResponse> rawPage = orderApplicationService.listHistory(
            currentUser.userId(),
            new OrderHistoryQuery(new PageQuery(page, size))
        );
        List<OrderView> items = rawPage.items().stream()
            .map(item -> orderRepository.findById(item.orderId()).orElseThrow())
            .map(order -> apiViewMapper.toOrderView(order, currentUser))
            .toList();
        return ApiResponse.success(new PageResponse<>(items, rawPage.page(), rawPage.size(), rawPage.total()));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderView> detail(HttpServletRequest request, @PathVariable Long orderId) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        orderApplicationService.getDetail(currentUser.userId(), orderId);
        return ApiResponse.success(
            orderRepository.findById(orderId)
                .map(order -> apiViewMapper.toOrderView(order, currentUser))
                .orElseThrow()
        );
    }

    @PutMapping("/{orderId}")
    public ApiResponse<OrderView> updateStatus(
        HttpServletRequest request,
        @PathVariable Long orderId,
        @RequestBody UpdateOrderStatusCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        orderApplicationService.updateStatus(currentUser.userId(), orderId, command);
        return ApiResponse.success(
            orderRepository.findById(orderId)
                .map(order -> apiViewMapper.toOrderView(order, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/{orderId}/reviews")
    public ApiResponse<?> submitReview(
        HttpServletRequest request,
        @PathVariable Long orderId,
        @RequestBody SubmitReviewCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(reviewApplicationService.submit(currentUser.userId(), orderId, command));
    }
}
