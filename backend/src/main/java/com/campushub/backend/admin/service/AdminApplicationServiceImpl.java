package com.campushub.backend.admin.service;

import com.campushub.backend.admin.dto.AdminCategoryStatResponse;
import com.campushub.backend.admin.dto.AdminDashboardResponse;
import com.campushub.backend.admin.dto.AdminDemandQuery;
import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
import com.campushub.backend.admin.dto.AdminOrderArbitrationCommand;
import com.campushub.backend.admin.dto.AdminUserQuery;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.auth.dto.UserQueryCriteria;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandReviewQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.RewardSettlementService;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
import com.campushub.backend.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
public class AdminApplicationServiceImpl implements AdminApplicationService {

    private static final int MAX_ADMIN_REASON_LENGTH = 500;

    private final UserRepository userRepository;
    private final DemandRepository demandRepository;
    private final OrderRepository orderRepository;
    private final NotificationApplicationService notificationApplicationService;
    private final DemandApplicationService demandApplicationService;
    private final RewardSettlementService rewardSettlementService;
    private final ReviewRepository reviewRepository;
    private final UserActionLogRepository userActionLogRepository;

    public AdminApplicationServiceImpl(
        UserRepository userRepository,
        DemandRepository demandRepository,
        OrderRepository orderRepository,
        NotificationApplicationService notificationApplicationService,
        DemandApplicationService demandApplicationService,
        RewardSettlementService rewardSettlementService,
        ReviewRepository reviewRepository,
        UserActionLogRepository userActionLogRepository
    ) {
        this.userRepository = userRepository;
        this.demandRepository = demandRepository;
        this.orderRepository = orderRepository;
        this.notificationApplicationService = notificationApplicationService;
        this.demandApplicationService = demandApplicationService;
        this.rewardSettlementService = rewardSettlementService;
        this.reviewRepository = reviewRepository;
        this.userActionLogRepository = userActionLogRepository;
    }

    @Override
    public PageResponse<UserProfileResponse> listUsers(Long operatorId, AdminUserQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin user query must not be null");
        }

        UserQueryCriteria criteria = new UserQueryCriteria(
            query.q(), query.searchField(), query.role(), query.status(),
            query.sortBy(), query.sortDirection(), query.pageQuery());
        List<User> users = userRepository.findPage(criteria);
        List<UserProfileResponse> items = users.stream().map(UserProfileResponse::from).toList();
        long total = userRepository.count(criteria);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }

    @Override
    public UserProfileResponse banUser(Long operatorId, Long userId, String reason) {
        User operator = requireAdmin(operatorId);
        validateReason(reason);
        if (operator.getId().equals(userId)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "admin cannot ban self");
        }
        User user = findUser(userId);
        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "user is already banned");
        }
        user.setStatus(UserStatus.BANNED);
        user.setUpdatedAt(LocalDateTime.now());
        return UserProfileResponse.from(userRepository.save(user));
    }

    @Override
    public UserProfileResponse unbanUser(Long operatorId, Long userId) {
        requireAdmin(operatorId);
        User user = findUser(userId);
        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "user is already active");
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(LocalDateTime.now());
        return UserProfileResponse.from(userRepository.save(user));
    }

    @Override
    public UserProfileResponse updateUserRole(Long operatorId, Long userId, String role) {
        requireAdmin(operatorId);
        if (role == null || role.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "role must not be blank");
        }
        UserRole targetRole;
        try {
            targetRole = UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported role: " + role);
        }
        if (operatorId.equals(userId)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "admin cannot change own role");
        }
        User user = findUser(userId);
        if (user.getRole() == targetRole) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "user is already in role " + role);
        }
        user.setRole(targetRole);
        user.setUpdatedAt(LocalDateTime.now());
        return UserProfileResponse.from(userRepository.save(user));
    }

    @Override
    public PageResponse<DemandSummaryResponse> listPendingDemands(Long operatorId, AdminDemandQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin demand query must not be null");
        }

        DemandReviewQuery reviewQuery = new DemandReviewQuery(
            query.q(), query.category(), query.campusZone(), query.pageQuery());
        List<Demand> demands = demandRepository.findReviewPage(reviewQuery);
        List<DemandSummaryResponse> items = demands.stream().map(DemandSummaryResponse::from).toList();
        long total = demandRepository.countReview(reviewQuery);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }

    @Override
    public PageResponse<OrderSummaryResponse> listArbitrationOrders(Long operatorId, int page, int size) {
        requireAdmin(operatorId);
        int resolvedPage = Math.max(page, 1);
        int resolvedSize = Math.max(size, 1);

        List<Order> orders = orderRepository.findArbitrationPage(resolvedPage, resolvedSize);
        List<OrderSummaryResponse> items = orders.stream().map(OrderSummaryResponse::from).toList();
        long total = orderRepository.countArbitration();
        return new PageResponse<>(items, resolvedPage, resolvedSize, total);
    }

    @Override
    public DemandDetailResponse reviewDemand(Long operatorId, Long demandId, AdminDemandReviewCommand command) {
        requireAdmin(operatorId);
        if (command == null || command.action() == null || command.action().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "review action must not be blank");
        }
        validateReason(command.reason());

        Demand demand = demandRepository.findById(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
        if (demand.getStatus() != DemandStatus.REVIEWING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only reviewing demands can be reviewed");
        }

        String action = command.action().trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        if ("approve".equals(action)) {
            demand.setStatus(DemandStatus.PENDING);
            demand.setIsApproved(true);
            demand.setReviewReason(null);
            notificationApplicationService.notifyDemandApproved(demand.getPublisherId(), demand.getId());
        } else if ("reject".equals(action)) {
            String reviewReason = normalizeRejectReason(command.reason());
            demand.setStatus(DemandStatus.CANCELLED);
            demand.setIsApproved(false);
            demand.setReviewReason(reviewReason);
            demandApplicationService.unfreezePublisherBalance(demandId);
            notificationApplicationService.notifyDemandRejected(demand.getPublisherId(), demand.getId(), reviewReason);
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported review action: " + command.action());
        }
        demand.setReviewedBy(operatorId);
        demand.setReviewedAt(now);
        demand.setUpdatedAt(now);
        return DemandDetailResponse.from(demandRepository.save(demand));
    }

    @Override
    public void deleteOrder(Long operatorId, Long orderId, String reason) {
        requireAdmin(operatorId);
        validateReason(reason);
        Order order = findOrder(orderId);
        Demand demand = demandRepository.findById(order.getDemandId()).orElse(null);
        if (demand != null && order.getStatus() != OrderStatus.COMPLETED) {
            demand.setStatus(DemandStatus.CANCELLED);
            demand.setUpdatedAt(LocalDateTime.now());
            demandRepository.save(demand);
            demandApplicationService.unfreezePublisherBalance(demand.getId());
        }
        orderRepository.deleteById(orderId);
    }

    @Override
    public OrderDetailResponse resolveOrderArbitration(Long operatorId, Long orderId, AdminOrderArbitrationCommand command) {
        requireAdmin(operatorId);
        if (command == null || command.outcome() == null || command.outcome().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "arbitration outcome must not be blank");
        }
        String reason = normalizeRejectReason(command.reason());
        Order order = findOrderForUpdate(orderId);
        if (order.getStatus() != OrderStatus.IN_ARBITRATION) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "order is not in arbitration");
        }
        Demand demand = demandRepository.findById(order.getDemandId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));

        String outcome = command.outcome().trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        if ("complete".equals(outcome) || "completed".equals(outcome)) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(now);
            demand.setStatus(DemandStatus.COMPLETED);
            transferReward(demand, order);
        } else if ("cancel".equals(outcome) || "cancelled".equals(outcome)) {
            order.setStatus(OrderStatus.CANCELLED);
            demand.setStatus(DemandStatus.CANCELLED);
            demandApplicationService.unfreezePublisherBalance(demand.getId());
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported arbitration outcome: " + command.outcome());
        }

        order.setUpdatedAt(now);
        order.addHistory(OrderStatus.IN_ARBITRATION, order.getStatus(), operatorId, "ARBITRATION_RESOLVED: " + reason, now);
        demand.setUpdatedAt(now);
        orderRepository.save(order);
        demandRepository.save(demand);

        if (order.getStatus() == OrderStatus.COMPLETED) {
            notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), OrderStatus.COMPLETED, true);
            notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), OrderStatus.COMPLETED, false);
        } else {
            notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), OrderStatus.CANCELLED, true);
            notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), OrderStatus.CANCELLED, false);
        }
        notificationApplicationService.notifyOrderArbitrationResolved(order.getPublisherId(), order.getId(), command.outcome(), reason);
        notificationApplicationService.notifyOrderArbitrationResolved(order.getAccepterId(), order.getId(), command.outcome(), reason);

        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    @Override
    public AdminDashboardResponse getDashboard(Long operatorId) {
        requireAdmin(operatorId);

        LocalDate today = LocalDate.now();

        long dailyActiveUsers = countDailyActiveUsers(today);
        long totalUsers = userRepository.count();
        long totalDemands = demandRepository.countAll();
        long totalOrders = orderRepository.count();
        long pendingReviewDemands = demandRepository.countByStatus(DemandStatus.REVIEWING);
        long completedOrders = orderRepository.countByStatus(OrderStatus.COMPLETED);
        Map<String, Long> categoryDistribution = demandRepository.countByCategory();

        List<AdminCategoryStatResponse> categoryStats = categoryDistribution.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .map(entry -> new AdminCategoryStatResponse(entry.getKey(), entry.getValue()))
            .toList();

        return new AdminDashboardResponse(
            dailyActiveUsers, totalUsers, totalDemands, pendingReviewDemands,
            totalOrders, completedOrders, categoryStats);
    }

    private User requireAdmin(Long operatorId) {
        User operator = findUser(operatorId);
        if (operator.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "admin role is required");
        }
        return operator;
    }

    private User findUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));
    }

    private Order findOrder(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "orderId must not be null");
        }
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "order not found"));
    }

    private Order findOrderForUpdate(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "orderId must not be null");
        }
        return orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "order not found"));
    }

    private long countDailyActiveUsers(LocalDate today) {
        Set<Long> activeUserIds = new HashSet<>();
        activeUserIds.addAll(demandRepository.findActivePublisherIdsByDate(today));
        activeUserIds.addAll(orderRepository.findActiveParticipantIdsByDate(today));
        if (reviewRepository != null) {
            activeUserIds.addAll(reviewRepository.findActiveAuthorIdsByDate(today));
        }
        if (userActionLogRepository != null) {
            activeUserIds.addAll(userActionLogRepository.findActiveUserIdsByDate(today));
        }
        activeUserIds.remove(null);
        return activeUserIds.size();
    }

    private void validateReason(String reason) {
        if (reason != null && reason.length() > MAX_ADMIN_REASON_LENGTH) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "admin reason length must not exceed " + MAX_ADMIN_REASON_LENGTH
            );
        }
    }

    private String normalizeRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reason must not be blank");
        }
        validateReason(reason);
        return reason.trim();
    }

    private void transferReward(Demand demand, Order order) {
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        rewardSettlementService.settleToAccepter(order.getPublisherId(), order.getAccepterId(), reward);
    }
}
