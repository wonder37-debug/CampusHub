package com.campushub.backend.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.model.PageQuery;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 验证 {@link OrderApplicationServiceImpl#autoCompleteOverdueOrders(Long)} 的逐单事务隔离：
 * 单笔订单自动完成失败（通知异常）只回滚该订单，不影响其他订单的自动完成。
 */
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderAutoCompletionTransactionIsolationTest {

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
    private com.campushub.backend.upload.repository.UploadedAssetRepository uploadedAssetRepository;

    @MockBean
    private NotificationApplicationService notificationApplicationService;

    private Long publisherId;
    private Long accepterId;

    @BeforeEach
    void setUp() {
        publisherId = userRepository.save(new User(
            null, "publisher@example.edu.cn", "20260001", "hash", "发布者", null,
            UserRole.USER, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO, LocalDateTime.now(), LocalDateTime.now()
        )).getId();
        accepterId = userRepository.save(new User(
            null, "accepter@example.edu.cn", "20260002", "hash", "接单者", null,
            UserRole.USER, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO, LocalDateTime.now(), LocalDateTime.now()
        )).getId();
        for (String fn : List.of("proof1.png", "proof2.png")) {
            uploadedAssetRepository.insert(
                new com.campushub.backend.upload.repository.entity.UploadedAssetEntity(
                    fn, "/api/v1/uploads/2026/10/" + fn, accepterId, true, null));
        }
    }

    @Test
    void singleOrderFailureDoesNotAffectOtherOrders() {
        Long order1Id = createOverdueOrder("需求A", "/api/v1/uploads/2026/10/proof1.png");
        Long order2Id = createOverdueOrder("需求B", "/api/v1/uploads/2026/10/proof2.png");

        // 让 order1 的通知抛异常，模拟单笔自动完成失败
        doThrow(new RuntimeException("simulated notification failure"))
            .when(notificationApplicationService)
            .notifyOrderStatusChanged(anyLong(), eq(order1Id), any(), anyBoolean());

        orderApplicationService.autoCompleteOverdueOrders(publisherId);

        Order order1 = orderRepository.findById(order1Id).orElseThrow();
        assertEquals(OrderStatus.IN_PROGRESS, order1.getStatus(),
            "通知失败的订单应回滚，仍为 IN_PROGRESS");

        Order order2 = orderRepository.findById(order2Id).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, order2.getStatus(),
            "未失败的订单应正常完成，为 COMPLETED");
    }

    @Test
    void allOrdersCompleteWhenNoFailures() {
        Long order1Id = createOverdueOrder("需求A", "/api/v1/uploads/2026/10/proof1.png");
        Long order2Id = createOverdueOrder("需求B", "/api/v1/uploads/2026/10/proof2.png");

        orderApplicationService.autoCompleteOverdueOrders(publisherId);

        assertEquals(OrderStatus.COMPLETED, orderRepository.findById(order1Id).orElseThrow().getStatus());
        assertEquals(OrderStatus.COMPLETED, orderRepository.findById(order2Id).orElseThrow().getStatus());
    }

    /**
     * 创建一笔满足自动完成条件的订单：
     * IN_PROGRESS + 接单方已确认完成（PROVIDER_CONFIRMED）+ 距确认超过 48 小时。
     */
    private Long createOverdueOrder(String title, String proofUrl) {
        DemandDetailResponse demand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                title, "desc", null, "EXPRESS", "XIANLIN", "菜鸟驿站",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3),
                new BigDecimal("3.00"), List.of(), null, null, false, null, null
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

        // 直接在 statusHistory 中添加 49 小时前的 PROVIDER_CONFIRMED_COMPLETION 条目
        // save 只插入新增条目，不修改已有条目，故直接追加而非改时间
        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS,
            accepterId, "PROVIDER_CONFIRMED_COMPLETION", LocalDateTime.now().minusHours(49));
        orderRepository.save(order);
        return accepted.orderId();
    }
}
