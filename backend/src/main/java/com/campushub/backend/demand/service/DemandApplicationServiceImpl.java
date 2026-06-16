package com.campushub.backend.demand.service;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.UpdateDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DemandApplicationServiceImpl implements DemandApplicationService {

    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final SensitiveWordChecker sensitiveWordChecker;
    private final NotificationApplicationService notificationApplicationService;
    private final OrderApplicationService orderApplicationService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;

    public DemandApplicationServiceImpl(
        DemandRepository demandRepository,
        UserRepository userRepository,
        SensitiveWordChecker sensitiveWordChecker
    ) {
        this(demandRepository, userRepository, sensitiveWordChecker, null, null, null, null);
    }

    @Autowired
    public DemandApplicationServiceImpl(
        DemandRepository demandRepository,
        UserRepository userRepository,
        SensitiveWordChecker sensitiveWordChecker,
        @Autowired(required = false) NotificationApplicationService notificationApplicationService,
        @Autowired(required = false) OrderApplicationService orderApplicationService,
        @Autowired(required = false) ReviewRepository reviewRepository,
        @Autowired(required = false) OrderRepository orderRepository
    ) {
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.sensitiveWordChecker = sensitiveWordChecker;
        this.notificationApplicationService = notificationApplicationService;
        this.orderApplicationService = orderApplicationService;
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public DemandDetailResponse publish(Long publisherId, PublishDemandCommand command) {
        validatePublishCommand(command);

        User publisher = findActivePublisher(publisherId);
        guardForbiddenWords(command.title(), command.description());
        validateRewardAgainstBalance(publisher, command.reward());

        BigDecimal reward = normalizeReward(command.reward());
        freezeBalance(publisher, reward);

        // 自动完成超时订单 + 检查未评价订单并提醒
        checkPendingReviewsAndAutoComplete(publisherId);

        LocalDateTime now = LocalDateTime.now();
        Demand demand = new Demand(
            null,
            publisher.getId(),
            publisher.getNickname(),
            command.title().trim(),
            trimToNull(command.description()),
            trimToNull(command.note()),
            parseCategory(command.category()),
            parseCampusZone(command.campusZone()),
            trimToNull(command.location()),
            command.startTime(),
            command.endTime(),
            normalizeReward(command.reward()),
            command.tags(),
            DemandStatus.REVIEWING,
            false,
            command.anonymous(),
            command.anonymous() ? generateAnonymousCode() : null,
            null,
            null,
            null,
            now,
            now
        );

        Demand saved = demandRepository.save(demand);
        notifyAdminsForReview(saved);
        return DemandDetailResponse.from(saved);
    }

    @Override
    public PageResponse<DemandSummaryResponse> list(DemandQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "demand query must not be null");
        }

        LocalDateTime now = LocalDateTime.now();
        Stream<Demand> filtered = demandRepository.findAll().stream()
            .filter(demand -> isPubliclyVisible(demand, now)
                || (query.currentUserId() != null && demand.getPublisherId() != null
                    && demand.getPublisherId().equals(query.currentUserId())))
            .filter(demand -> matchesKeyword(demand, query.q()))
            .filter(demand -> matchesCategory(demand, query.category()))
            .filter(demand -> matchesCampusZone(demand, query.campusZone()))
            .filter(demand -> matchesLocation(demand, query.location()))
            .filter(demand -> matchesStartTimeRange(demand, query.startTimeFrom(), query.startTimeTo()));

        List<Demand> sorted = filtered.sorted(resolveComparator(query.sort())).toList();
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(sorted.size(), fromIndex + size);
        List<DemandSummaryResponse> items = fromIndex >= sorted.size()
            ? List.of()
            : sorted.subList(fromIndex, toIndex).stream().map(DemandSummaryResponse::from).toList();

        return new PageResponse<>(items, page, size, sorted.size());
    }

    @Override
    public DemandDetailResponse getDetail(Long demandId) {
        return DemandDetailResponse.from(findDemandById(demandId));
    }

    @Override
    public DemandDetailResponse update(Long operatorId, Long demandId, UpdateDemandCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "update demand command must not be null");
        }

        Demand demand = findDemandById(demandId);
        if (!demand.isEditableBy(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only publisher can edit this demand");
        }

        guardForbiddenWords(command.title(), command.description());
        if (command.title() != null) {
            validateTitle(command.title());
            demand.setTitle(command.title().trim());
        }
        if (command.description() != null) {
            validateDescription(command.description());
            demand.setDescription(trimToNull(command.description()));
        }
        if (command.note() != null) {
            demand.setNote(trimToNull(command.note()));
        }
        if (command.category() != null) {
            demand.setCategory(parseCategory(command.category()));
        }
        if (command.campusZone() != null) {
            demand.setCampusZone(parseCampusZone(command.campusZone()));
        }
        if (command.location() != null) {
            validateLocation(command.location());
            demand.setLocation(trimToNull(command.location()));
        }
        if (command.startTime() != null || command.endTime() != null) {
            LocalDateTime startTime = command.startTime() != null ? command.startTime() : demand.getStartTime();
            LocalDateTime endTime = command.endTime() != null ? command.endTime() : demand.getEndTime();
            validateTimeWindow(startTime, endTime);
            demand.setStartTime(startTime);
            demand.setEndTime(endTime);
        }
        if (command.reward() != null) {
            User publisher = userRepository.findById(demand.getPublisherId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "publisher not found"));
            validateRewardAgainstBalance(publisher, command.reward());
            BigDecimal newReward = normalizeReward(command.reward());
            BigDecimal oldReward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
            BigDecimal diff = newReward.subtract(oldReward);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                freezeBalance(publisher, diff);
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                unfreezeBalance(publisher, diff.negate());
            }
            demand.setReward(newReward);
        }
        if (command.tags() != null) {
            validateTags(command.tags());
            demand.setTags(command.tags());
        }
        if (command.anonymous() != null) {
            demand.setAnonymous(command.anonymous());
            demand.setAnonymousCode(Boolean.TRUE.equals(command.anonymous()) ? generateAnonymousCode() : null);
        }
        demand.setUpdatedAt(LocalDateTime.now());
        return DemandDetailResponse.from(demandRepository.save(demand));
    }

    @Override
    public void unfreezePublisherBalance(Long demandId) {
        Demand demand = findDemandById(demandId);
        if (demand.getPublisherId() == null) {
            return;
        }
        User publisher = userRepository.findById(demand.getPublisherId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "publisher not found"));
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        unfreezeBalance(publisher, reward);
    }

    @Override
    public DemandDetailResponse withdraw(Long operatorId, Long demandId) {
        Demand demand = demandRepository.findById(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
        if (!demand.getPublisherId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only the publisher can withdraw this demand");
        }
        if (demand.getStatus() != DemandStatus.REVIEWING && demand.getStatus() != DemandStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only reviewing or open demands can be withdrawn");
        }
        demand.setStatus(DemandStatus.CANCELLED);
        demand.setUpdatedAt(LocalDateTime.now());
        unfreezeBalanceForDemand(demand);
        demandRepository.save(demand);
        return DemandDetailResponse.from(demand);
    }

    private void unfreezeBalanceForDemand(Demand demand) {
        if (demand.getReward() == null || demand.getReward().compareTo(BigDecimal.ZERO) <= 0 || demand.getPublisherId() == null) {
            return;
        }
        User publisher = userRepository.findById(demand.getPublisherId()).orElse(null);
        if (publisher != null) {
            BigDecimal frozen = publisher.getFrozenBalance() == null ? BigDecimal.ZERO : publisher.getFrozenBalance();
            publisher.setFrozenBalance(frozen.subtract(demand.getReward()).max(BigDecimal.ZERO));
            userRepository.save(publisher);
        }
    }

    private User findActivePublisher(Long publisherId) {
        if (publisherId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "publisherId must not be null");
        }
        User user = userRepository.findById(publisherId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "publisher not found"));
        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "banned user cannot publish demands");
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "admin cannot publish demands");
        }
        return user;
    }

    private Demand findDemandById(Long demandId) {
        if (demandId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "demandId must not be null");
        }
        return demandRepository.findById(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
    }

    private void validatePublishCommand(PublishDemandCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "publish demand command must not be null");
        }
        validateTitle(command.title());
        validateDescription(command.description());
        parseCategory(command.category());
        parseCampusZone(command.campusZone());
        validateLocation(command.location());
        validateTimeWindow(command.startTime(), command.endTime());
        normalizeReward(command.reward());
        validateTags(command.tags());
    }

    private void validateTitle(String title) {
        if (title == null || title.isBlank() || title.trim().length() < 3 || title.trim().length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "title length must be between 3 and 200");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "description length must not exceed 2000");
        }
    }

    private void validateLocation(String location) {
        if (location != null && location.length() > 256) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "location length must not exceed 256");
        }
    }

    private void validateTimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && endTime.isBefore(startTime)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "endTime must not be before startTime");
        }
    }

    private void validateTags(List<String> tags) {
        if (tags != null && tags.size() > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "tags size must not exceed 20");
        }
    }

    private void validateRewardAgainstBalance(User publisher, BigDecimal reward) {
        BigDecimal normalizedReward = normalizeReward(reward);
        if (normalizedReward.compareTo(resolveAvailableBalance(publisher)) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reward must not exceed available balance");
        }
    }

    private BigDecimal resolveAvailableBalance(User user) {
        BigDecimal balance = user.getBalance() == null ? BigDecimal.ZERO : user.getBalance();
        BigDecimal frozenBalance = user.getFrozenBalance() == null ? BigDecimal.ZERO : user.getFrozenBalance();
        BigDecimal available = balance.subtract(frozenBalance);
        return available.max(BigDecimal.ZERO);
    }

    private void freezeBalance(User user, BigDecimal amount) {
        BigDecimal currentFrozen = user.getFrozenBalance() == null ? BigDecimal.ZERO : user.getFrozenBalance();
        user.setFrozenBalance(currentFrozen.add(amount));
        userRepository.save(user);
    }

    void unfreezeBalance(User user, BigDecimal amount) {
        BigDecimal currentFrozen = user.getFrozenBalance() == null ? BigDecimal.ZERO : user.getFrozenBalance();
        BigDecimal newFrozen = currentFrozen.subtract(amount).max(BigDecimal.ZERO);
        user.setFrozenBalance(newFrozen);
        userRepository.save(user);
    }

    private void guardForbiddenWords(String title, String description) {
        String merged = (title == null ? "" : title) + "\n" + (description == null ? "" : description);
        if (sensitiveWordChecker.containsForbiddenWords(merged)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand contains forbidden words");
        }
    }

    private DemandCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "category must not be blank");
        }
        DemandCategory resolved = DemandCategory.fromValue(category);
        if (resolved == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported category: " + category);
        }
        return resolved;
    }

    private CampusZone parseCampusZone(String campusZone) {
        if (campusZone == null || campusZone.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "campusZone must not be blank");
        }
        try {
            return CampusZone.valueOf(campusZone.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported campusZone: " + campusZone);
        }
    }

    private BigDecimal normalizeReward(BigDecimal reward) {
        if (reward == null) {
            return BigDecimal.ZERO;
        }
        if (reward.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reward must be greater than or equal to 0");
        }
        return reward;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isPubliclyVisible(Demand demand, LocalDateTime now) {
        if (isExpired(demand, now)) {
            return false;
        }
        return demand.getStatus() == DemandStatus.PENDING
            || demand.getStatus() == DemandStatus.IN_PROGRESS
            || demand.getStatus() == DemandStatus.COMPLETED;
    }

    private boolean isExpired(Demand demand, LocalDateTime now) {
        return demand.getEndTime() != null && demand.getEndTime().isBefore(now);
    }

    private boolean matchesKeyword(Demand demand, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return demand.getTitle().toLowerCase(Locale.ROOT).contains(normalized)
            || (demand.getDescription() != null
            && demand.getDescription().toLowerCase(Locale.ROOT).contains(normalized));
    }

    private boolean matchesCategory(Demand demand, String category) {
        return category == null || category.isBlank() || demand.getCategory().name().equalsIgnoreCase(category);
    }

    private boolean matchesCampusZone(Demand demand, String campusZone) {
        return campusZone == null || campusZone.isBlank() || demand.getCampusZone().name().equalsIgnoreCase(campusZone);
    }

    private boolean matchesLocation(Demand demand, String location) {
        return location == null || location.isBlank()
            || (demand.getLocation() != null
            && demand.getLocation().toLowerCase(Locale.ROOT).contains(location.trim().toLowerCase(Locale.ROOT)));
    }

    private boolean matchesStartTimeRange(Demand demand, LocalDateTime from, LocalDateTime to) {
        if (demand.getStartTime() == null) {
            return from == null && to == null;
        }
        boolean afterFrom = from == null || !demand.getStartTime().isBefore(from);
        boolean beforeTo = to == null || !demand.getStartTime().isAfter(to);
        return afterFrom && beforeTo;
    }

    private Comparator<Demand> resolveComparator(DemandSort sort) {
        return switch (sort) {
            case REWARD -> Comparator.comparing(Demand::getReward, Comparator.nullsLast(BigDecimal::compareTo))
                .reversed()
                .thenComparing(Demand::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case DISTANCE, TIME, RECOMMEND ->
                Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
        };
    }

    private String generateAnonymousCode() {
        return "匿名校友" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT);
    }

    private void notifyAdminsForReview(Demand demand) {
        if (notificationApplicationService == null || demand == null || demand.getId() == null) {
            return;
        }
        for (User admin : userRepository.findByRole(UserRole.ADMIN)) {
            if (admin.getId() == null || admin.getStatus() == UserStatus.BANNED) {
                continue;
            }
            notificationApplicationService.notifyDemandReviewRequested(admin.getId(), demand.getId());
        }
    }

    private void checkPendingReviewsAndAutoComplete(Long publisherId) {
        // 先执行超时订单自动完成
        if (orderApplicationService != null) {
            orderApplicationService.autoCompleteOverdueOrders(publisherId);
        }

        // 检查该用户是否有已完成但未评价的订单，如果有则发送提醒
        if (notificationApplicationService != null && reviewRepository != null && orderRepository != null) {
            java.util.List<com.campushub.backend.order.domain.Order> allOrders = orderRepository.findByParticipant(publisherId);
            for (com.campushub.backend.order.domain.Order order : allOrders) {
                if (order.getStatus() != OrderStatus.COMPLETED) {
                    continue;
                }
                if (reviewRepository.findByOrderIdAndAuthorId(order.getId(), publisherId).isPresent()) {
                    continue;
                }
                notificationApplicationService.notifyPendingReviewReminder(publisherId, order.getId());
            }
        }
    }
}
