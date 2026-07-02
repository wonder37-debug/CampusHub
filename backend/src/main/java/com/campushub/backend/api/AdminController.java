package com.campushub.backend.api;

import com.campushub.backend.admin.dto.AdminDashboardResponse;
import com.campushub.backend.admin.dto.AdminDemandQuery;
import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
import com.campushub.backend.admin.dto.AdminOrderArbitrationCommand;
import com.campushub.backend.admin.dto.AdminUserQuery;
import com.campushub.backend.admin.service.AdminApplicationService;
import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.api.view.UserSummaryView;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.repository.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminApplicationService adminApplicationService;
    private final RequestUserExtractor requestUserExtractor;
    private final OrderRepository orderRepository;
    private final ApiViewMapper apiViewMapper;

    public AdminController(
        AdminApplicationService adminApplicationService,
        RequestUserExtractor requestUserExtractor,
        OrderRepository orderRepository,
        ApiViewMapper apiViewMapper
    ) {
        this.adminApplicationService = adminApplicationService;
        this.requestUserExtractor = requestUserExtractor;
        this.orderRepository = orderRepository;
        this.apiViewMapper = apiViewMapper;
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<UserSummaryView>> listUsers(
        HttpServletRequest request,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String searchField,
        @RequestParam(required = false) String role,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDirection,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        PageResponse<com.campushub.backend.auth.dto.UserProfileResponse> rawPage = adminApplicationService.listUsers(
            currentUser.userId(),
            new AdminUserQuery(q, searchField, role, status, sortBy, sortDirection, new PageQuery(page, size))
        );
        return ApiResponse.success(new PageResponse<>(
            rawPage.items().stream().map(UserSummaryView::from).toList(),
            rawPage.page(),
            rawPage.size(),
            rawPage.total()
        ));
    }

    @PostMapping("/users/{userId}/ban")
    public ApiResponse<UserSummaryView> banUser(
        HttpServletRequest request,
        @PathVariable Long userId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.success(UserSummaryView.from(
            adminApplicationService.banUser(currentUser.userId(), userId, reason)
        ));
    }

    @PostMapping("/users/{userId}/unban")
    public ApiResponse<UserSummaryView> unbanUser(HttpServletRequest request, @PathVariable Long userId) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(UserSummaryView.from(adminApplicationService.unbanUser(currentUser.userId(), userId)));
    }

    @PostMapping("/users/{userId}/role")
    public ApiResponse<UserSummaryView> updateUserRole(
        HttpServletRequest request,
        @PathVariable Long userId,
        @RequestBody Map<String, String> body
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        String role = body == null ? null : body.get("role");
        return ApiResponse.success(UserSummaryView.from(
            adminApplicationService.updateUserRole(currentUser.userId(), userId, role)
        ));
    }

    @GetMapping("/demands/pending")
    public ApiResponse<PageResponse<DemandSummaryResponse>> listPendingDemands(
        HttpServletRequest request,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String campusZone,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(
            adminApplicationService.listPendingDemands(
                currentUser.userId(),
                new AdminDemandQuery(q, category, campusZone, new PageQuery(page, size))
            )
        );
    }

    @GetMapping("/orders/arbitration")
    public ApiResponse<PageResponse<OrderView>> listArbitrationOrders(
        HttpServletRequest request,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        PageResponse<OrderSummaryResponse> rawPage = adminApplicationService.listArbitrationOrders(currentUser.userId(), page, size);
        return ApiResponse.success(new PageResponse<>(
            rawPage.items().stream()
                .map(item -> orderRepository.findById(item.orderId()).orElseThrow())
                .map(order -> apiViewMapper.toOrderView(order, currentUser))
                .toList(),
            rawPage.page(),
            rawPage.size(),
            rawPage.total()
        ));
    }

    @PostMapping("/demands/{demandId}/review")
    public ApiResponse<?> reviewDemand(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @RequestBody AdminDemandReviewCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(adminApplicationService.reviewDemand(currentUser.userId(), demandId, command));
    }

    @DeleteMapping("/orders/{orderId}")
    public ApiResponse<Void> deleteOrder(
        HttpServletRequest request,
        @PathVariable Long orderId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        adminApplicationService.deleteOrder(currentUser.userId(), orderId, body == null ? null : body.get("reason"));
        return ApiResponse.success(null);
    }

    @PostMapping("/orders/{orderId}/arbitration/resolve")
    public ApiResponse<OrderView> resolveOrderArbitration(
        HttpServletRequest request,
        @PathVariable Long orderId,
        @RequestBody AdminOrderArbitrationCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        adminApplicationService.resolveOrderArbitration(currentUser.userId(), orderId, command);
        return ApiResponse.success(
            orderRepository.findById(orderId)
                .map(order -> apiViewMapper.toOrderView(order, currentUser))
                .orElse(null)
        );
    }

    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardResponse> dashboard(HttpServletRequest request) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(adminApplicationService.getDashboard(currentUser.userId()));
    }
}
