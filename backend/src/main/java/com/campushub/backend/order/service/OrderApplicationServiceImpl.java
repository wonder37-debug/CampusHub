package com.campushub.backend.order.service;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.upload.repository.UploadedAssetRepository;
import com.campushub.backend.upload.repository.entity.UploadedAssetEntity;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.RequestOrderArbitrationCommand;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
public class OrderApplicationServiceImpl implements OrderApplicationService {

    private static final String PROVIDER_CONFIRMED_NOTE = "PROVIDER_CONFIRMED_COMPLETION";
    private static final String REQUESTER_CONFIRMED_NOTE = "REQUESTER_CONFIRMED_COMPLETION";
    private static final String COMPLETION_FINAL_NOTE = "ORDER_COMPLETED";
    private static final int MAX_ARBITRATION_REASON_LENGTH = 500;
    private static final int MAX_PROOF_URL_LENGTH = 512;
    private static final java.util.regex.Pattern PROOF_URL_PATTERN =
        java.util.regex.Pattern.compile("/api/v1/uploads/\\d{4}/\\d{2}/[a-zA-Z0-9][a-zA-Z0-9._-]*");

    private final OrderRepository orderRepository;
    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final NotificationApplicationService notificationApplicationService;
    private final RewardSettlementService rewardSettlementService;
    private final UserActionLogRepository userActionLogRepository;
    private final UploadedAssetRepository uploadedAssetRepository;

    public OrderApplicationServiceImpl(
        OrderRepository orderRepository,
        DemandRepository demandRepository,
        UserRepository userRepository,
        NotificationApplicationService notificationApplicationService,
        RewardSettlementService rewardSettlementService,
        UserActionLogRepository userActionLogRepository,
        UploadedAssetRepository uploadedAssetRepository
    ) {
        this.orderRepository = orderRepository;
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.notificationApplicationService = notificationApplicationService;
        this.rewardSettlementService = rewardSettlementService;
        this.userActionLogRepository = userActionLogRepository;
        this.uploadedAssetRepository = uploadedAssetRepository;
    }

    @Override
    public OrderDetailResponse accept(Long operatorId, Long demandId, AcceptOrderCommand command) {
        User accepter = findActiveUser(operatorId);
        if (accepter.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "admin cannot accept demands");
        }

        // 加 Demand 行锁，避免 accept 与 update 并发：update 把 DIRECT_ACCEPT 改成 SELECT_ONE 后 accept 仍按旧 mode 创建 Order
        Demand demand = findDemandForUpdate(demandId);
        if (demand.getInteractionMode() != InteractionMode.DIRECT_ACCEPT) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                "only DIRECT_ACCEPT demand can be accepted via /accept, current mode: " + demand.getInteractionMode());
        }
        if (isDemandExpired(demand, LocalDateTime.now()) || demand.getStatus() == DemandStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has expired");
        }
        if (demand.getPublisherId().equals(accepter.getId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "publisher cannot accept own demand");
        }
        if (demand.getStatus() != DemandStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand is not available for acceptance");
        }
        if (orderRepository.findByDemandId(demandId).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has already been accepted");
        }

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setDemandId(demand.getId());
        order.setPublisherId(demand.getPublisherId());
        order.setAccepterId(accepter.getId());
        order.setStatus(OrderStatus.ACCEPTED);
        order.setAcceptNote(trimToNull(command == null ? null : command.note()));
        order.setProofSubmitted(false);
        order.setProofImageCount(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.addHistory(null, OrderStatus.ACCEPTED, accepter.getId(), order.getAcceptNote(), now);

        try {
            order = orderRepository.save(order);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has already been accepted");
        }

        demand.setStatus(DemandStatus.IN_PROGRESS);
        demand.setUpdatedAt(now);
        demandRepository.save(demand);

        recordAccept(accepter.getId(), demand);

        notificationApplicationService.notifyOrderAcceptedForPublisher(demand.getPublisherId(), order.getId());
        notificationApplicationService.notifyOrderAcceptedForAccepter(accepter.getId(), order.getId());
        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    @Override
    public OrderDetailResponse createOrderForSelectedResponse(Long operatorId, Long demandId, Long accepterId, String note) {
        if (accepterId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "accepterId must not be null");
        }
        Demand demand = findDemand(demandId);
        if (demand.getInteractionMode() != InteractionMode.SELECT_ONE) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only SELECT_ONE demand can create order from response");
        }
        if (demand.getPublisherId() == null || !demand.getPublisherId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only publisher can select response to create order");
        }
        if (demand.getPublisherId().equals(accepterId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "publisher cannot select own response");
        }
        if (isDemandExpired(demand, LocalDateTime.now()) || demand.getStatus() == DemandStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has expired");
        }
        if (demand.getStatus() != DemandStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand is not available for selection");
        }
        if (orderRepository.findByDemandId(demandId).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has already been accepted");
        }

        User accepter = findActiveUser(accepterId);
        if (accepter.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "admin cannot be selected as accepter");
        }

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setDemandId(demand.getId());
        order.setPublisherId(demand.getPublisherId());
        order.setAccepterId(accepter.getId());
        order.setStatus(OrderStatus.ACCEPTED);
        order.setAcceptNote(trimToNull(note));
        order.setProofSubmitted(false);
        order.setProofImageCount(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.addHistory(null, OrderStatus.ACCEPTED, operatorId, order.getAcceptNote(), now);

        try {
            order = orderRepository.save(order);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has already been accepted");
        }

        demand.setStatus(DemandStatus.IN_PROGRESS);
        demand.setUpdatedAt(now);
        demandRepository.save(demand);

        recordAccept(accepter.getId(), demand);

        notificationApplicationService.notifyOrderAcceptedForPublisher(demand.getPublisherId(), order.getId());
        notificationApplicationService.notifyOrderAcceptedForAccepter(accepter.getId(), order.getId());
        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    @Override
    public OrderDetailResponse updateStatus(Long operatorId, Long orderId, UpdateOrderStatusCommand command) {
        if (command == null || command.targetStatus() == null || command.targetStatus().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetStatus must not be blank");
        }
        Order order = findOrderForUpdate(orderId);
        Demand demand = findDemand(order.getDemandId());
        if (!order.isParticipant(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only order participants can update order status");
        }

        OrderStatus targetStatus = parseStatus(command.targetStatus());
        if (targetStatus == OrderStatus.COMPLETED) {
            return confirmCompletion(operatorId, order, demand, command);
        }

        validateTransition(order, operatorId, targetStatus);
        OrderStatus fromStatus = order.getStatus();
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(targetStatus);
        order.setUpdatedAt(now);

        if (targetStatus == OrderStatus.IN_PROGRESS) {
            demand.setStatus(DemandStatus.IN_PROGRESS);
        } else if (targetStatus == OrderStatus.CANCELLED) {
            demand.setStatus(DemandStatus.CANCELLED);
            unfreezePublisherBalance(demand);
        }

        order.addHistory(fromStatus, targetStatus, operatorId, trimToNull(command.note()), now);
        demand.setUpdatedAt(now);
        orderRepository.save(order);
        demandRepository.save(demand);

        notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), targetStatus, true);
        notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), targetStatus, false);
        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    @Override
    public OrderDetailResponse requestArbitration(Long operatorId, Long orderId, RequestOrderArbitrationCommand command) {
        Order order = findOrderForUpdate(orderId);
        if (!order.isParticipant(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only order participants can request arbitration");
        }
        if (order.getStatus() != OrderStatus.ACCEPTED && order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only active orders can request arbitration");
        }

        String reason = normalizeArbitrationReason(command == null ? null : command.reason());
        Demand demand = findDemand(order.getDemandId());
        LocalDateTime now = LocalDateTime.now();
        OrderStatus fromStatus = order.getStatus();
        order.setStatus(OrderStatus.IN_ARBITRATION);
        order.setUpdatedAt(now);
        order.addHistory(fromStatus, OrderStatus.IN_ARBITRATION, operatorId, "ARBITRATION_REQUESTED: " + reason, now);
        orderRepository.save(order);

        notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), OrderStatus.IN_ARBITRATION, true);
        notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), OrderStatus.IN_ARBITRATION, false);
        for (User admin : userRepository.findByRole(UserRole.ADMIN)) {
            notificationApplicationService.notifyOrderArbitrationRequested(admin.getId(), order.getId(), operatorId, reason);
        }
        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    @Override
    public OrderDetailResponse getDetail(Long operatorId, Long orderId) {
        Order order = findOrder(orderId);
        User operator = findActiveUser(operatorId);
        if (!order.isParticipant(operatorId) && operator.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only participants or admins can view order");
        }
        return OrderDetailResponse.from(order, DemandDetailResponse.from(findDemand(order.getDemandId())));
    }

    @Override
    public PageResponse<OrderSummaryResponse> listHistory(Long operatorId, OrderHistoryQuery query) {
        findActiveUser(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "order history query must not be null");
        }
        List<Order> orders = orderRepository.findHistoryPage(operatorId, query);
        List<OrderSummaryResponse> items = orders.stream().map(OrderSummaryResponse::from).toList();
        long total = orderRepository.countHistory(operatorId);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }

    @Override
    public void autoCompleteOverdueOrders(Long userId) {
        List<Order> orders = orderRepository.findByParticipant(userId);
        LocalDateTime now = LocalDateTime.now();
        for (Order summary : orders) {
            if (summary.getStatus() != OrderStatus.IN_PROGRESS || !userId.equals(summary.getPublisherId())) {
                continue;
            }
            if (!hasCompletionConfirmation(summary, summary.getAccepterId())) {
                continue;
            }
            LocalDateTime providerConfirmTime = summary.getStatusHistory().stream()
                .filter(entry -> entry.operatorId() != null
                    && entry.operatorId().equals(summary.getAccepterId())
                    && entry.fromStatus() == OrderStatus.IN_PROGRESS
                    && entry.toStatus() == OrderStatus.IN_PROGRESS
                    && PROVIDER_CONFIRMED_NOTE.equals(entry.note()))
                .map(OrderStatusHistoryEntry::changedAt)
                .findFirst()
                .orElse(null);
            if (providerConfirmTime == null || providerConfirmTime.plusHours(48).isAfter(now)) {
                continue;
            }

            // 加 Order 行锁，确保并发自动完成不会重复结算
            Order order = orderRepository.findByIdForUpdate(summary.getId()).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.IN_PROGRESS) {
                continue;
            }
            Demand demand = findDemand(order.getDemandId());
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(now);
            order.setUpdatedAt(now);
            order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, userId, "SYSTEM_AUTO_COMPLETED", now);
            demand.setStatus(DemandStatus.COMPLETED);
            demand.setUpdatedAt(now);
            orderRepository.save(order);
            demandRepository.save(demand);
            transferReward(demand, order);
            notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), OrderStatus.COMPLETED, true);
            notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), OrderStatus.COMPLETED, false);
        }
    }

    private OrderDetailResponse confirmCompletion(Long operatorId, Order order, Demand demand, UpdateOrderStatusCommand command) {
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only in progress orders can be completed");
        }
        boolean operatorIsPublisher = operatorId.equals(order.getPublisherId());
        boolean operatorIsAccepter = operatorId.equals(order.getAccepterId());
        Long counterpartId = getCounterpartId(order, operatorId);
        if (counterpartId == null) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only participants can complete order");
        }
        if (hasCompletionConfirmation(order, operatorId)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "completion already confirmed by this user");
        }
        if (operatorIsAccepter) {
            validateProviderProof(command.proofImageUrls(), order.getAccepterId());
        }

        LocalDateTime now = LocalDateTime.now();
        if (!hasCompletionConfirmation(order, counterpartId)) {
            if (operatorIsAccepter) {
                List<String> proofImageUrls = command.proofImageUrls();
                order.setProofSubmitted(true);
                order.setProofImageUrls(proofImageUrls);
                order.setProofImageCount(proofImageUrls.size());
            }
            String pendingNote = operatorIsAccepter ? PROVIDER_CONFIRMED_NOTE : REQUESTER_CONFIRMED_NOTE;
            order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS, operatorId, pendingNote, now);
            order.setUpdatedAt(now);
            orderRepository.save(order);
            notificationApplicationService.notifyOrderCompletionPending(counterpartId, order.getId());
            return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
        }

        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (operatorIsAccepter && !order.isProofSubmitted()) {
            List<String> proofImageUrls = command.proofImageUrls();
            order.setProofSubmitted(true);
            order.setProofImageUrls(proofImageUrls);
            order.setProofImageCount(proofImageUrls.size());
        }
        order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, operatorId, COMPLETION_FINAL_NOTE, now);
        demand.setStatus(DemandStatus.COMPLETED);
        demand.setUpdatedAt(now);
        orderRepository.save(order);
        demandRepository.save(demand);
        transferReward(demand, order);

        notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), OrderStatus.COMPLETED, true);
        notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), OrderStatus.COMPLETED, false);
        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    private void validateProviderProof(List<String> proofImageUrls, Long accepterId) {
        if (proofImageUrls == null || proofImageUrls.isEmpty() || proofImageUrls.size() > 3) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls must contain 1 to 3 images");
        }
        for (int i = 0; i < proofImageUrls.size(); i++) {
            String url = proofImageUrls.get(i);
            if (url == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] must not be null");
            }
            String trimmed = url.trim();
            if (trimmed.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] must not be blank");
            }
            if (trimmed.length() > MAX_PROOF_URL_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] length must not exceed " + MAX_PROOF_URL_LENGTH);
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] must be an internal upload URL, external URLs are not allowed");
            }
            if (!PROOF_URL_PATTERN.matcher(trimmed).matches()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] must match /api/v1/uploads/YYYY/MM/filename format");
            }
            String filename = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            UploadedAssetEntity asset = uploadedAssetRepository.findByFilename(filename);
            if (asset == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] is not from a valid upload");
            }
            if (!asset.getUploaderId().equals(accepterId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageUrls[" + i + "] does not belong to the current user");
            }
        }
    }

    private void validateTransition(Order order, Long operatorId, OrderStatus targetStatus) {
        OrderStatus currentStatus = order.getStatus();
        if (currentStatus == OrderStatus.IN_ARBITRATION) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "order is waiting for admin arbitration");
        }
        if (currentStatus == targetStatus) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "order is already in target status");
        }

        switch (targetStatus) {
            case IN_PROGRESS -> {
                if (currentStatus != OrderStatus.ACCEPTED) {
                    throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only accepted orders can move to in progress");
                }
                if (!operatorId.equals(order.getAccepterId())) {
                    throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only accepter can start the order");
                }
            }
            case COMPLETED -> {
                // COMPLETED 实际由 confirmCompletion 处理（updateStatus 中提前分支），此处不会执行；
                // 保留状态校验以防未来重构遗漏，但不再校验 proofImageCount（proofImageUrls 是唯一 source of truth）
                if (currentStatus != OrderStatus.IN_PROGRESS) {
                    throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only in progress orders can be completed");
                }
            }
            case CANCELLED -> {
                if (currentStatus != OrderStatus.ACCEPTED) {
                    throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only accepted orders can be cancelled");
                }
                if (!order.isParticipant(operatorId)) {
                    throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only participants can cancel order");
                }
            }
            case ACCEPTED, IN_ARBITRATION ->
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "unsupported order status transition");
        }
    }

    private boolean hasCompletionConfirmation(Order order, Long userId) {
        return userId != null && order.getStatusHistory().stream()
            .anyMatch(entry -> entry.operatorId() != null
                && entry.operatorId().equals(userId)
                && entry.fromStatus() == OrderStatus.IN_PROGRESS
                && entry.toStatus() == OrderStatus.IN_PROGRESS
                && (PROVIDER_CONFIRMED_NOTE.equals(entry.note()) || REQUESTER_CONFIRMED_NOTE.equals(entry.note())));
    }

    private Long getCounterpartId(Order order, Long operatorId) {
        if (operatorId.equals(order.getPublisherId())) {
            return order.getAccepterId();
        }
        if (operatorId.equals(order.getAccepterId())) {
            return order.getPublisherId();
        }
        return null;
    }

    private OrderStatus parseStatus(String raw) {
        try {
            return OrderStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported order status: " + raw);
        }
    }

    private Demand findDemand(Long demandId) {
        return demandRepository.findById(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
    }

    private Demand findDemandForUpdate(Long demandId) {
        return demandRepository.findByIdForUpdate(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
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

    private User findActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));
        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "banned user cannot operate orders");
        }
        return user;
    }

    private String normalizeArbitrationReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "arbitration reason must not be blank");
        }
        String normalized = reason.trim();
        if (normalized.length() > MAX_ARBITRATION_REASON_LENGTH) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "arbitration reason length must not exceed " + MAX_ARBITRATION_REASON_LENGTH
            );
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ponytail: ACCEPT 行为日志在订单创建成功路径追加写入，与 Order 同事务；
    // 不改事务边界、不改状态机，仅在 demandRepository.save(demand) 成功后记录。
    private void recordAccept(Long accepterId, Demand demand) {
        if (accepterId == null || demand == null || demand.getId() == null || demand.getCategory() == null) {
            return;
        }
        userActionLogRepository.save(new UserActionLog(
            null, accepterId, ActionType.ACCEPT, demand.getId(), demand.getCategory(), LocalDateTime.now()
        ));
    }

    private void transferReward(Demand demand, Order order) {
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        rewardSettlementService.settleToAccepter(order.getPublisherId(), order.getAccepterId(), reward);
    }

    private void unfreezePublisherBalance(Demand demand) {
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        rewardSettlementService.refundToPublisher(demand.getPublisherId(), reward);
    }

    private boolean isDemandExpired(Demand demand, LocalDateTime now) {
        return demand.getEndTime() != null && demand.getEndTime().isBefore(now);
    }
}
