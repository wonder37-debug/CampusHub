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
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class OrderApplicationServiceImpl implements OrderApplicationService {

    private static final String PROVIDER_CONFIRMED_NOTE = "接单方确认完成，等待需求方确认";
    private static final String REQUESTER_CONFIRMED_NOTE = "需求方确认完成，等待接单方确认";
    private static final String COMPLETION_FINAL_NOTE = "双方已确认完成";

    private final OrderRepository orderRepository;
    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final NotificationApplicationService notificationApplicationService;

    public OrderApplicationServiceImpl(
        OrderRepository orderRepository,
        DemandRepository demandRepository,
        UserRepository userRepository,
        NotificationApplicationService notificationApplicationService
    ) {
        this.orderRepository = orderRepository;
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.notificationApplicationService = notificationApplicationService;
    }

    @Override
    public OrderDetailResponse accept(Long operatorId, Long demandId, AcceptOrderCommand command) {
        User accepter = findActiveUser(operatorId);
        if (accepter.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "admin cannot accept demands");
        }

        Demand demand = findDemand(demandId);
        if (isDemandExpired(demand, LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has expired");
        }
        if (demand.getStatus() == DemandStatus.EXPIRED) {
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
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand has already been accepted");
        }

        demand.setStatus(DemandStatus.IN_PROGRESS);
        demand.setUpdatedAt(now);
        demandRepository.save(demand);

        notificationApplicationService.notifyOrderAcceptedForPublisher(demand.getPublisherId(), order.getId());
        notificationApplicationService.notifyOrderAcceptedForAccepter(accepter.getId(), order.getId());

        return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
    }

    @Override
    public OrderDetailResponse updateStatus(Long operatorId, Long orderId, UpdateOrderStatusCommand command) {
        if (command == null || command.targetStatus() == null || command.targetStatus().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetStatus must not be blank");
        }
        Order order = findOrder(orderId);
        Demand demand = findDemand(order.getDemandId());
        if (!order.isParticipant(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only order participants can update order status");
        }

        OrderStatus targetStatus = parseStatus(command.targetStatus());
        if (targetStatus == OrderStatus.COMPLETED) {
            return confirmCompletion(operatorId, order, demand, command);
        }
        validateTransition(order, operatorId, targetStatus, command.proofImageCount());

        OrderStatus fromStatus = order.getStatus();
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(targetStatus);
        order.setUpdatedAt(now);

        if (targetStatus == OrderStatus.IN_PROGRESS) {
            demand.setStatus(DemandStatus.IN_PROGRESS);
        }
        if (targetStatus == OrderStatus.CANCELLED) {
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

    private OrderDetailResponse confirmCompletion(Long operatorId, Order order, Demand demand, UpdateOrderStatusCommand command) {
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only in progress orders can be completed");
        }

        Long counterpartId = getCounterpartId(order, operatorId);
        if (counterpartId == null) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only participants can complete order");
        }

        if (hasCompletionConfirmation(order, operatorId)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "completion already confirmed by this user");
        }

        // 接单方必须首先确认完成
        boolean isOperatorProvider = operatorId.equals(order.getAccepterId());
        boolean isOperatorRequester = operatorId.equals(order.getPublisherId());
        boolean providerConfirmed = hasCompletionConfirmation(order, order.getAccepterId());

        if (isOperatorRequester && !providerConfirmed) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "接单方需要先确认完成");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!hasCompletionConfirmation(order, counterpartId)) {
            if (command != null && command.proofImageCount() != null) {
                order.setProofSubmitted(true);
                order.setProofImageCount(command.proofImageCount());
            }
            String pendingNote = isOperatorProvider ? PROVIDER_CONFIRMED_NOTE : REQUESTER_CONFIRMED_NOTE;
            order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS, operatorId, pendingNote, now);
            order.setUpdatedAt(now);
            orderRepository.save(order);
            notificationApplicationService.notifyOrderCompletionPending(counterpartId, order.getId());
            return OrderDetailResponse.from(order, DemandDetailResponse.from(demand));
        }

        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (command != null && command.proofImageCount() != null && !order.isProofSubmitted()) {
            order.setProofSubmitted(true);
            order.setProofImageCount(command.proofImageCount());
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
        List<Order> sorted = orderRepository.findByParticipant(operatorId).stream()
            .sorted(Comparator.comparing(Order::getCreatedAt).reversed())
            .toList();

        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(sorted.size(), fromIndex + size);
        List<OrderSummaryResponse> items = fromIndex >= sorted.size()
            ? List.of()
            : sorted.subList(fromIndex, toIndex).stream().map(OrderSummaryResponse::from).toList();
        return new PageResponse<>(items, page, size, sorted.size());
    }

    private void validateTransition(Order order, Long operatorId, OrderStatus targetStatus, Integer proofImageCount) {
        OrderStatus currentStatus = order.getStatus();
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
                if (currentStatus != OrderStatus.IN_PROGRESS) {
                    throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only in progress orders can be completed");
                }
                if (!operatorId.equals(order.getAccepterId())) {
                    throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only accepter can complete the order");
                }
                if (proofImageCount == null || proofImageCount < 1 || proofImageCount > 3) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "proofImageCount must be between 1 and 3");
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
            case ACCEPTED -> throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "cannot transition back to accepted");
        }
    }

    private boolean hasCompletionConfirmation(Order order, Long userId) {
        return order.getStatusHistory().stream()
            .anyMatch(entry -> entry.operatorId() != null
                && entry.operatorId().equals(userId)
                && entry.fromStatus() == OrderStatus.IN_PROGRESS
                && entry.toStatus() == OrderStatus.IN_PROGRESS
                && (PROVIDER_CONFIRMED_NOTE.equals(entry.note())
                    || REQUESTER_CONFIRMED_NOTE.equals(entry.note())));
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

    private Order findOrder(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "orderId must not be null");
        }
        return orderRepository.findById(orderId)
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

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private void unfreezePublisherBalance(Demand demand) {
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        if (reward.compareTo(BigDecimal.ZERO) <= 0 || demand.getPublisherId() == null) {
            return;
        }
        User publisher = userRepository.findById(demand.getPublisherId()).orElse(null);
        if (publisher != null) {
            BigDecimal frozen = publisher.getFrozenBalance() == null ? BigDecimal.ZERO : publisher.getFrozenBalance();
            publisher.setFrozenBalance(frozen.subtract(reward).max(BigDecimal.ZERO));
            userRepository.save(publisher);
        }
    }

    private boolean isDemandExpired(Demand demand, LocalDateTime now) {
        return demand.getEndTime() != null && demand.getEndTime().isBefore(now);
    }

    /**
     * 自动完成接单方已确认超过 48 小时但需求方未确认的订单。
     */
    public void autoCompleteOverdueOrders(Long userId) {
        List<Order> orders = orderRepository.findByParticipant(userId);
        LocalDateTime now = LocalDateTime.now();
        for (Order order : orders) {
            if (order.getStatus() != OrderStatus.IN_PROGRESS) {
                continue;
            }
            if (!userId.equals(order.getPublisherId())) {
                continue;
            }
            boolean providerConfirmed = hasCompletionConfirmation(order, order.getAccepterId());
            if (!providerConfirmed) {
                continue;
            }
            // 查找接单方确认时间
            LocalDateTime providerConfirmTime = order.getStatusHistory().stream()
                .filter(e -> e.operatorId() != null && e.operatorId().equals(order.getAccepterId())
                    && e.fromStatus() == OrderStatus.IN_PROGRESS
                    && e.toStatus() == OrderStatus.IN_PROGRESS
                    && PROVIDER_CONFIRMED_NOTE.equals(e.note()))
                .map(OrderStatusHistoryEntry::changedAt)
                .findFirst()
                .orElse(null);
            if (providerConfirmTime == null) {
                continue;
            }
            if (providerConfirmTime.plusHours(48).isAfter(now)) {
                continue;
            }
            // 超时，自动确认完成
            Demand demand = findDemand(order.getDemandId());
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(now);
            order.setUpdatedAt(now);
            order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, userId, "需求方超时未确认，系统自动完成", now);
            demand.setStatus(DemandStatus.COMPLETED);
            demand.setUpdatedAt(now);
            orderRepository.save(order);
            demandRepository.save(demand);
            transferReward(demand, order);
            notificationApplicationService.notifyOrderStatusChanged(order.getPublisherId(), order.getId(), OrderStatus.COMPLETED, true);
            notificationApplicationService.notifyOrderStatusChanged(order.getAccepterId(), order.getId(), OrderStatus.COMPLETED, false);
        }
    }
}
