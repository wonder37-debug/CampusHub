package com.campushub.backend.admin.service;

import com.campushub.backend.admin.dto.AdminCategoryStatResponse;
import com.campushub.backend.admin.dto.AdminDashboardResponse;
import com.campushub.backend.admin.dto.AdminDemandQuery;
import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
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
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AdminApplicationServiceImpl implements AdminApplicationService {

    private static final int MAX_ADMIN_REASON_LENGTH = 500;

    private final UserRepository userRepository;
    private final DemandRepository demandRepository;
    private final OrderRepository orderRepository;

    public AdminApplicationServiceImpl(
        UserRepository userRepository,
        DemandRepository demandRepository,
        OrderRepository orderRepository
    ) {
        this.userRepository = userRepository;
        this.demandRepository = demandRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public PageResponse<UserProfileResponse> listUsers(Long operatorId, AdminUserQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin user query must not be null");
        }

        List<User> filtered = userRepository.findAll().stream()
            .filter(user -> matchesUserKeyword(user, query.q()))
            .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
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
    public PageResponse<DemandSummaryResponse> listPendingDemands(Long operatorId, AdminDemandQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin demand query must not be null");
        }

        List<Demand> filtered = demandRepository.findAll().stream()
            .filter(demand -> demand.getStatus() == DemandStatus.REVIEWING)
            .filter(demand -> matchesDemandKeyword(demand, query.q()))
            .filter(demand -> matchesDemandCategory(demand, query.category()))
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
        if ("approve".equals(action)) {
            demand.setStatus(DemandStatus.PENDING);
        } else if ("reject".equals(action)) {
            demand.setStatus(DemandStatus.CANCELLED);
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported review action: " + command.action());
        }
        demand.setUpdatedAt(LocalDateTime.now());
        return DemandDetailResponse.from(demandRepository.save(demand));
    }

    @Override
    public AdminDashboardResponse getDashboard(Long operatorId) {
        requireAdmin(operatorId);

        List<User> users = userRepository.findAll();
        List<Demand> demands = demandRepository.findAll();
        List<Order> orders = orderRepository.findAll();
        LocalDate today = LocalDate.now();

        long dailyActiveUsers = users.stream()
            .filter(user -> isActiveToday(user, today))
            .count();
        long pendingReviewDemands = demands.stream()
            .filter(demand -> demand.getStatus() == DemandStatus.REVIEWING)
            .count();
        long completedOrders = orders.stream()
            .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
            .count();
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

    private boolean matchesUserKeyword(User user, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(user.getNickname(), normalized)
            || containsIgnoreCase(user.getEmail(), normalized)
            || containsIgnoreCase(user.getStudentId(), normalized);
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

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private boolean isActiveToday(User user, LocalDate today) {
        return isSameDate(user.getCreatedAt(), today) || isSameDate(user.getUpdatedAt(), today);
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
}
