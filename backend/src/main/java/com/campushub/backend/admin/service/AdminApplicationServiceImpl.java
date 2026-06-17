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
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminApplicationServiceImpl implements AdminApplicationService {

    private static final int MAX_ADMIN_REASON_LENGTH = 500;

    private final UserRepository userRepository;
    private final DemandRepository demandRepository;
    private final OrderRepository orderRepository;
    private final NotificationApplicationService notificationApplicationService;
    private final DemandApplicationService demandApplicationService;

    @Autowired(required = false)
    private ReviewRepository reviewRepository;

    @Autowired(required = false)
    private UserActionLogRepository userActionLogRepository;

    public AdminApplicationServiceImpl(
        UserRepository userRepository,
        DemandRepository demandRepository,
        OrderRepository orderRepository,
        NotificationApplicationService notificationApplicationService,
        DemandApplicationService demandApplicationService
    ) {
        this.userRepository = userRepository;
        this.demandRepository = demandRepository;
        this.orderRepository = orderRepository;
        this.notificationApplicationService = notificationApplicationService;
        this.demandApplicationService = demandApplicationService;
    }

    @Override
    public PageResponse<UserProfileResponse> listUsers(Long operatorId, AdminUserQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin user query must not be null");
        }

        List<User> filtered = userRepository.findAll().stream()
            .filter(user -> matchesUserKeyword(user, query.q(), query.searchField()))
            .filter(user -> matchesUserRole(user, query.role()))
            .filter(user -> matchesUserStatus(user, query.status()))
            .sorted(resolveUserComparator(query.sortBy(), query.sortDirection()))
            .toList();

        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(filtered.size(), fromIndex + size);
        List<UserProfileResponse> items = fromIndex >= filtered.size()
            ? List.of()
            : filtered.subList(fromIndex, toIndex).stream().map(UserProfileResponse::from).toList();
        return new PageResponse<>(items, page, size, filtered.size());
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

        List<Demand> filtered = demandRepository.findByStatus(DemandStatus.REVIEWING).stream()
            .filter(demand -> matchesDemandKeyword(demand, query.q()))
            .filter(demand -> matchesDemandCategory(demand, query.category()))
            .filter(demand -> matchesDemandCampusZone(demand, query.campusZone()))
            .sorted(Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
            .toList();

        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(filtered.size(), fromIndex + size);
        List<DemandSummaryResponse> items = fromIndex >= filtered.size()
            ? List.of()
            : filtered.subList(fromIndex, toIndex).stream().map(DemandSummaryResponse::from).toList();
        return new PageResponse<>(items, page, size, filtered.size());
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
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.IN_ARBITRATION) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "order is not in arbitration");
        }
        Demand demand = demandRepository.findById(order.getDemandId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));

        String outcome = command.outcome().trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        if ("complete".equals(outcome)) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(now);
            demand.setStatus(DemandStatus.COMPLETED);
            transferReward(demand, order);
        } else if ("cancel".equals(outcome)) {
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

        List<User> users = userRepository.findAll();
        List<Demand> demands = demandRepository.findAll();
        List<Order> orders = orderRepository.findAll();
        LocalDate today = LocalDate.now();

        long dailyActiveUsers = countDailyActiveUsers(demands, orders, today);
        long pendingReviewDemands = demandRepository.findByStatus(DemandStatus.REVIEWING).size();
        long completedOrders = orders.stream().filter(order -> order.getStatus() == OrderStatus.COMPLETED).count();
        Map<String, Long> categoryDistribution = demands.stream()
            .collect(Collectors.groupingBy(demand -> demand.getCategory().name(), Collectors.counting()));

        List<AdminCategoryStatResponse> categoryStats = categoryDistribution.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .map(entry -> new AdminCategoryStatResponse(entry.getKey(), entry.getValue()))
            .toList();

        return new AdminDashboardResponse(
            dailyActiveUsers,
            users.size(),
            demands.size(),
            pendingReviewDemands,
            orders.size(),
            completedOrders,
            categoryStats
        );
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

    private boolean matchesUserKeyword(User user, String keyword, String searchField) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        if (searchField == null || searchField.isBlank()) {
            return containsIgnoreCase(user.getNickname(), normalized)
                || containsIgnoreCase(user.getEmail(), normalized)
                || containsIgnoreCase(user.getStudentId(), normalized);
        }
        return switch (searchField.trim().toLowerCase(Locale.ROOT)) {
            case "nickname" -> containsIgnoreCase(user.getNickname(), normalized);
            case "email" -> containsIgnoreCase(user.getEmail(), normalized);
            case "studentid", "student_id" -> containsIgnoreCase(user.getStudentId(), normalized);
            default -> containsIgnoreCase(user.getNickname(), normalized)
                || containsIgnoreCase(user.getEmail(), normalized)
                || containsIgnoreCase(user.getStudentId(), normalized);
        };
    }

    private boolean matchesDemandKeyword(Demand demand, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(demand.getTitle(), normalized)
            || containsIgnoreCase(demand.getDescription(), normalized)
            || containsIgnoreCase(demand.getLocation(), normalized);
    }

    private boolean matchesDemandCategory(Demand demand, String category) {
        return category == null || category.isBlank() || demand.getCategory().name().equalsIgnoreCase(category);
    }

    private boolean matchesDemandCampusZone(Demand demand, String campusZone) {
        return campusZone == null || campusZone.isBlank() || demand.getCampusZone().name().equalsIgnoreCase(campusZone);
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private boolean matchesUserRole(User user, String role) {
        if (role == null || role.isBlank()) {
            return true;
        }
        return user.getRole().name().equalsIgnoreCase(role.trim());
    }

    private boolean matchesUserStatus(User user, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return user.getStatus().name().equalsIgnoreCase(status.trim());
    }

    private Comparator<User> resolveUserComparator(String sortBy, String sortDirection) {
        boolean descending = sortDirection == null || sortDirection.isBlank() || !"asc".equalsIgnoreCase(sortDirection.trim());
        Comparator<User> comparator = switch (sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT)) {
            case "creditscore", "credit_score" ->
                Comparator.comparing(User::getCreditScore, Comparator.nullsLast(Integer::compareTo));
            case "nickname" ->
                Comparator.comparing(User::getNickname, Comparator.nullsLast(String::compareToIgnoreCase));
            case "createdat", "created_at" ->
                Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo));
            default ->
                Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo));
        };
        comparator = descending ? comparator.reversed() : comparator;
        return comparator.thenComparing(User::getId, Comparator.nullsLast(Long::compareTo));
    }

    private long countDailyActiveUsers(List<Demand> demands, List<Order> orders, LocalDate today) {
        Set<Long> activeUserIds = new HashSet<>();
        collectDemandActivity(activeUserIds, demands, today);
        collectOrderActivity(activeUserIds, orders, today);
        collectReviewActivity(activeUserIds, today);
        collectRecommendationActivity(activeUserIds, today);
        activeUserIds.remove(null);
        return activeUserIds.size();
    }

    private void collectDemandActivity(Set<Long> activeUserIds, List<Demand> demands, LocalDate today) {
        demands.stream()
            .filter(demand -> isSameDate(demand.getCreatedAt(), today) || isSameDate(demand.getUpdatedAt(), today))
            .map(Demand::getPublisherId)
            .forEach(activeUserIds::add);
    }

    private void collectOrderActivity(Set<Long> activeUserIds, List<Order> orders, LocalDate today) {
        orders.stream()
            .filter(order -> isSameDate(order.getCreatedAt(), today)
                || isSameDate(order.getUpdatedAt(), today)
                || isSameDate(order.getCompletedAt(), today))
            .forEach(order -> {
                activeUserIds.add(order.getPublisherId());
                activeUserIds.add(order.getAccepterId());
            });
    }

    private void collectReviewActivity(Set<Long> activeUserIds, LocalDate today) {
        if (reviewRepository == null) {
            return;
        }
        for (Review review : listAllReviews()) {
            if (isSameDate(review.getCreatedAt(), today)) {
                activeUserIds.add(review.getAuthorId());
            }
        }
    }

    private void collectRecommendationActivity(Set<Long> activeUserIds, LocalDate today) {
        if (userActionLogRepository == null) {
            return;
        }
        for (User user : userRepository.findAll()) {
            List<UserActionLog> logs = userActionLogRepository.findByUserId(user.getId());
            logs.stream()
                .filter(log -> isSameDate(log.getCreatedAt(), today))
                .map(UserActionLog::getUserId)
                .forEach(activeUserIds::add);
        }
    }

    private List<Review> listAllReviews() {
        return userRepository.findAll().stream()
            .flatMap(user -> reviewRepository.findByTargetId(user.getId()).stream())
            .distinct()
            .toList();
    }

    private boolean isSameDate(LocalDateTime time, LocalDate date) {
        return time != null && time.toLocalDate().isEqual(date);
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
        if (reward.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        User publisher = userRepository.findById(order.getPublisherId()).orElse(null);
        User accepter = userRepository.findById(order.getAccepterId()).orElse(null);
        if (publisher != null) {
            BigDecimal publisherBalance = publisher.getBalance() == null ? BigDecimal.ZERO : publisher.getBalance();
            BigDecimal publisherFrozen = publisher.getFrozenBalance() == null ? BigDecimal.ZERO : publisher.getFrozenBalance();
            publisher.setBalance(publisherBalance.subtract(reward).max(BigDecimal.ZERO));
            publisher.setFrozenBalance(publisherFrozen.subtract(reward).max(BigDecimal.ZERO));
            userRepository.save(publisher);
        }
        if (accepter != null) {
            BigDecimal accepterBalance = accepter.getBalance() == null ? BigDecimal.ZERO : accepter.getBalance();
            accepter.setBalance(accepterBalance.add(reward));
            userRepository.save(accepter);
        }
    }
}
