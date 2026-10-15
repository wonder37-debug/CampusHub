package com.campushub.backend.order.service;

import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 定时任务：每 5 分钟扫描所有 IN_PROGRESS 状态的订单，
 * 若接单方已提交完成确认、发布者超过 48 小时仍未确认，则自动完成订单并结算悬赏。
 *
 * <p>此前的自动完成只在发布者再次发布需求时被动触发（checkPendingReviewsAndAutoComplete），
 * 发布者 48 小时后不再操作时订单会无限期停留在 IN_PROGRESS、悬赏无法结算。
 * 本调度器将资金结算从用户偶然行为中解耦，独立周期执行。</p>
 *
 * <p>每笔订单的"加行锁 → 状态/时间窗口重新校验 → 完成 → 结算 → 通知"在独立事务
 * （REQUIRES_NEW）内完成，单笔失败只回滚当前订单并记录错误，不影响其他订单；
 * 幂等由 {@code OrderApplicationServiceImpl.autoCompleteOverdueOrder} 锁内校验保证。</p>
 */
@Component
public class OrderAutoCompletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoCompletionScheduler.class);

    private final OrderRepository orderRepository;
    private final OrderApplicationService orderApplicationService;
    private final TransactionTemplate transactionTemplate;

    public OrderAutoCompletionScheduler(
        OrderRepository orderRepository,
        OrderApplicationService orderApplicationService,
        PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.orderApplicationService = orderApplicationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedRate = 300_000)
    public void autoCompleteOverdueOrders() {
        List<Order> inProgressOrders = orderRepository.findAllByStatus(OrderStatus.IN_PROGRESS);
        for (Order snapshot : inProgressOrders) {
            if (snapshot == null || snapshot.getId() == null) {
                continue;
            }
            try {
                transactionTemplate.execute(status -> {
                    // operatorUserId 传 null：状态历史 operator 为空表示系统定时触发
                    orderApplicationService.autoCompleteOverdueOrder(snapshot.getId(), null);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to auto complete overdue order {}: {}", snapshot.getId(), e.getMessage(), e);
            }
        }
    }
}
