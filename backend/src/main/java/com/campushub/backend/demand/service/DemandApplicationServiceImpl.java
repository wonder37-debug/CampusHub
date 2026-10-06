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
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.UpdateDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
public class DemandApplicationServiceImpl implements DemandApplicationService {

    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final SensitiveWordChecker sensitiveWordChecker;
    private final NotificationApplicationService notificationApplicationService;
    private final OrderApplicationService orderApplicationService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final DemandResponseRepository demandResponseRepository;

    @Autowired
    public DemandApplicationServiceImpl(
        DemandRepository demandRepository,
        UserRepository userRepository,
        SensitiveWordChecker sensitiveWordChecker,
        NotificationApplicationService notificationApplicationService,
        OrderApplicationService orderApplicationService,
        ReviewRepository reviewRepository,
        OrderRepository orderRepository,
        DemandResponseRepository demandResponseRepository
    ) {
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.sensitiveWordChecker = sensitiveWordChecker;
        this.notificationApplicationService = notificationApplicationService;
        this.orderApplicationService = orderApplicationService;
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.demandResponseRepository = demandResponseRepository;
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

        DemandCategory category = parseCategory(command.category());
        InteractionMode interactionMode = resolveInteractionMode(category, command.interactionMode());
        validateTargetParticipantCount(interactionMode, command.targetParticipantCount());
        // 不变量：仅 SELECT_MANY 保存 targetParticipantCount，其余强制 null
        Integer targetParticipantCount = interactionMode == InteractionMode.SELECT_MANY
            ? command.targetParticipantCount() : null;

        LocalDateTime now = LocalDateTime.now();
        Demand demand = new Demand(
            null,
            publisher.getId(),
            publisher.getNickname(),
            command.title().trim(),
            trimToNull(command.description()),
            trimToNull(command.note()),
            category,
            parseCampusZone(command.campusZone()),
            trimToNull(command.location()),
            command.startTime(),
            command.endTime(),
            reward,
            interactionMode,
            targetParticipantCount,
            command.tags(),
            command.images(),
            trimToNull(command.contactInfo()),
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
        List<Demand> demands = demandRepository.findPage(query);
        List<DemandSummaryResponse> items = demands.stream()
            .map(DemandSummaryResponse::from)
            .toList();
        long total = demandRepository.count(query);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
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

        // 一旦已有 Response（报名/留言/回答），禁止修改互动模式相关字段，避免破坏选择/结算流程
        boolean hasResponses = demandResponseRepository.countByDemandId(demandId) > 0;

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
            if (hasResponses) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "cannot change category/interactionMode after responses exist");
            }
            DemandCategory newCategory = parseCategory(command.category());
            demand.setCategory(newCategory);
            InteractionMode newMode = resolveInteractionMode(newCategory, command.interactionMode());
            demand.setInteractionMode(newMode);
            if (newMode == InteractionMode.SELECT_MANY) {
                Integer effectiveTarget = command.targetParticipantCount() != null
                    ? command.targetParticipantCount()
                    : demand.getTargetParticipantCount();
                validateTargetParticipantCount(newMode, effectiveTarget);
            } else {
                demand.setTargetParticipantCount(null);
            }
        } else if (command.interactionMode() != null) {
            // 单独修改 interactionMode（仅对 OTHER 有意义；固定分类 resolveInteractionMode 会忽略 userInput）
            if (hasResponses) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "cannot change interactionMode after responses exist");
            }
            InteractionMode newMode = resolveInteractionMode(demand.getCategory(), command.interactionMode());
            demand.setInteractionMode(newMode);
            if (newMode == InteractionMode.SELECT_MANY) {
                Integer effectiveTarget = command.targetParticipantCount() != null
                    ? command.targetParticipantCount()
                    : demand.getTargetParticipantCount();
                validateTargetParticipantCount(newMode, effectiveTarget);
            } else {
                demand.setTargetParticipantCount(null);
            }
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
        if (command.contactInfo() != null) {
            demand.setContactInfo(trimToNull(command.contactInfo()));
        }
        if (command.anonymous() != null) {
            demand.setAnonymous(command.anonymous());
            demand.setAnonymousCode(Boolean.TRUE.equals(command.anonymous()) ? generateAnonymousCode() : null);
        }
        if (command.targetParticipantCount() != null) {
            if (hasResponses) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "cannot change targetParticipantCount after responses exist");
            }
            if (demand.getInteractionMode() != InteractionMode.SELECT_MANY) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "targetParticipantCount only allowed for SELECT_MANY, current mode: " + demand.getInteractionMode());
            }
            validateTargetParticipantCount(demand.getInteractionMode(), command.targetParticipantCount());
            demand.setTargetParticipantCount(command.targetParticipantCount());
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
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        if (reward.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!userRepository.unfreezeBalance(demand.getPublisherId(), reward)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "冻结金额不足，无法解冻悬赏金额");
        }
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
        if (!userRepository.unfreezeBalance(demand.getPublisherId(), demand.getReward())) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "冻结金额不足，无法解冻悬赏金额");
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

    private void validateTargetParticipantCount(InteractionMode interactionMode, Integer targetParticipantCount) {
        if (interactionMode == InteractionMode.SELECT_MANY) {
            if (targetParticipantCount == null || targetParticipantCount < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "SELECT_MANY demand requires targetParticipantCount >= 1");
            }
            if (targetParticipantCount > 100) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetParticipantCount must not exceed 100");
            }
        }
    }

    /**
     * 解析互动模式：固定分类按 category 推导；OTHER 由用户传入，未传默认 DIRECT_ACCEPT，且禁止 HELP。
     */
    private InteractionMode resolveInteractionMode(DemandCategory category, String userInput) {
        if (category == DemandCategory.OTHER) {
            if (userInput == null || userInput.isBlank()) {
                return InteractionMode.DIRECT_ACCEPT;
            }
            InteractionMode mode = InteractionMode.fromValue(userInput);
            if (mode == null || mode == InteractionMode.HELP) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "OTHER category interactionMode must be one of DIRECT_ACCEPT/SELECT_ONE/SELECT_MANY");
            }
            return mode;
        }
        InteractionMode resolved = InteractionMode.resolve(category);
        return resolved == null ? InteractionMode.DIRECT_ACCEPT : resolved;
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
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!userRepository.freezeBalance(user.getId(), amount)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "余额不足，无法冻结悬赏金额");
        }
    }

    void unfreezeBalance(User user, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!userRepository.unfreezeBalance(user.getId(), amount)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "冻结金额不足，无法解冻");
        }
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
            List<Long> completedOrderIds = orderRepository.findByParticipant(publisherId).stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                .map(Order::getId)
                .toList();
            if (completedOrderIds.isEmpty()) {
                return;
            }
            Set<Long> reviewedOrderIds = reviewRepository.findReviewedOrderIdsByAuthor(publisherId, completedOrderIds);
            for (Long orderId : completedOrderIds) {
                if (!reviewedOrderIds.contains(orderId)) {
                    notificationApplicationService.notifyPendingReviewReminder(publisherId, orderId);
                }
            }
        }
    }
}
