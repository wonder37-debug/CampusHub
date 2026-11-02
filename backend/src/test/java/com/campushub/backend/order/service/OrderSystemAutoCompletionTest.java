package com.campushub.backend.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.upload.repository.UploadedAssetRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 覆盖定时任务触发的系统自动完成路径：{@code autoCompleteOverdueOrder(orderId, null)}。
 *
 * <p>与 {@link OrderAutoCompletionTransactionIsolationTest} 的区别在于操作人来源：
 * 后者走"发布者再次发布需求"的被动触发，传入真实 publisherId；
 * 本用例走调度器路径，operatorUserId 为 null，用于验证系统流水能以 operator_id = NULL 落库。</p>
 *
 * <p>该路径此前没有测试覆盖：代码注释与前端文案都按"operator 为空表示系统触发"设计，
 * 但 ord_order_status_log.operator_id 声明为 NOT NULL，导致自动完成在写流水时抛
 * DataIntegrityViolationException、整笔订单回滚，悬赏永不结算。</p>
 */
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderSystemAutoCompletionTest {

    private static final BigDecimal REWARD = new BigDecimal("3.00");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DemandRepository demandRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DemandApplicationService demandApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private UploadedAssetRepository uploadedAssetRepository;

    @MockBean
    private NotificationApplicationService notificationApplicationService;

    private Long publisherId;
    private Long accepterId;

    @BeforeEach
    void setUp() {
        publisherId = userRepository.save(new User(
            null, "sys-publisher@example.edu.cn", "20260101", "hash", "发布者", null,
            UserRole.USER, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO, LocalDateTime.now(), LocalDateTime.now()
        )).getId();
        accepterId = userRepository.save(new User(
            null, "sys-accepter@example.edu.cn", "20260102", "hash", "接单者", null,
            UserRole.USER, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO, LocalDateTime.now(), LocalDateTime.now()
        )).getId();
        uploadedAssetRepository.insert(
            new com.campushub.backend.upload.repository.entity.UploadedAssetEntity(
                "sys-proof.png", "/api/v1/uploads/2026/10/sys-proof.png", accepterId, true, null));
    }

    @Test
    void systemTriggeredAutoCompletionWritesNullOperatorAndSettlesReward() {
        OrderDetailResponse accepted = createOverdueOrder();
        Long orderId = accepted.orderId();

        BigDecimal accepterBalanceBefore = userRepository.findById(accepterId).orElseThrow().getBalance();
        BigDecimal publisherBalanceBefore = userRepository.findById(publisherId).orElseThrow().getBalance();
        BigDecimal publisherFrozenBefore = userRepository.findById(publisherId).orElseThrow().getFrozenBalance();

        orderApplicationService.autoCompleteOverdueOrder(orderId, null);

        Order completed = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, completed.getStatus(), "系统触发后订单应自动完成");

        OrderStatusHistoryEntry systemEntry = completed.getStatusHistory().stream()
            .filter(entry -> "SYSTEM_AUTO_COMPLETED".equals(entry.note()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("自动完成应写入 SYSTEM_AUTO_COMPLETED 流水"));
        assertNull(systemEntry.operatorId(), "系统触发的流水不应绑定任何用户");
        assertEquals(OrderStatus.IN_PROGRESS, systemEntry.fromStatus());
        assertEquals(OrderStatus.COMPLETED, systemEntry.toStatus());

        User accepter = userRepository.findById(accepterId).orElseThrow();
        assertEquals(0, accepterBalanceBefore.add(REWARD).compareTo(accepter.getBalance()),
            "接单方余额应增加悬赏金额");

        User publisher = userRepository.findById(publisherId).orElseThrow();
        assertEquals(0, publisherBalanceBefore.subtract(REWARD).compareTo(publisher.getBalance()),
            "发布者余额应扣减悬赏金额");
        assertEquals(0, publisherFrozenBefore.subtract(REWARD).compareTo(publisher.getFrozenBalance()),
            "发布者冻结金额应解冻");
        assertTrue(publisher.getFrozenBalance().compareTo(BigDecimal.ZERO) >= 0, "冻结金额不应为负");
    }

    /**
     * 创建一笔满足自动完成条件的订单：
     * IN_PROGRESS + 接单方已确认完成 + 距确认超过 48 小时。
     */
    private OrderDetailResponse createOverdueOrder() {
        DemandDetailResponse demand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "系统自动完成用例", "desc", null, "EXPRESS", "XIANLIN", "菜鸟驿站",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3),
                REWARD, List.of(), null, null, false, null, null
            )
        );
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        OrderDetailResponse accepted = orderApplicationService.accept(
            accepterId, demand.id(), new AcceptOrderCommand("我来处理"));
        orderApplicationService.updateStatus(
            accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        // save 只插入新增流水，故直接追加一条 49 小时前的接单方确认记录
        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS,
            accepterId, "PROVIDER_CONFIRMED_COMPLETION", LocalDateTime.now().minusHours(49));
        orderRepository.save(order);
        return accepted;
    }
}
