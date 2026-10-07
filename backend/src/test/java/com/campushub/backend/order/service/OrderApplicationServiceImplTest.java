package com.campushub.backend.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.notification.repository.NotificationRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.RequestOrderArbitrationCommand;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderApplicationServiceImplTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DemandRepository demandRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DemandApplicationService demandApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    private Long publisherId;
    private Long accepterId;

    @BeforeEach
    void setUp() {
        publisherId = userRepository.save(new User(
            null,
            "publisher@example.edu.cn",
            "20260001",
            "hash",
            "发布者",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        )).getId();
        accepterId = userRepository.save(new User(
            null,
            "accepter@example.edu.cn",
            "20260002",
            "hash",
            "接单者",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        )).getId();
    }

    @Test
    void shouldAcceptDemandSuccessfully() {
        DemandDetailResponse demand = createDemand();

        OrderDetailResponse order = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来处理"));

        assertEquals("ACCEPTED", order.status());
        assertEquals(demand.id(), order.demandId());
        assertEquals(1, order.statusHistory().size());
        assertEquals("IN_PROGRESS", demandApplicationService.getDetail(demand.id()).status());
    }

    @Test
    void shouldRejectAcceptingOwnDemand() {
        DemandDetailResponse demand = createDemand();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.accept(publisherId, demand.id(), new AcceptOrderCommand("自己接"))
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldRejectDuplicateAccept() {
        DemandDetailResponse demand = createDemand();
        orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("第一次"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("第二次"))
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldAllowValidStatusTransitions() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        OrderDetailResponse inProgress = orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );
        OrderDetailResponse waitingConfirm = orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "已完成并上传凭证", 2)
        );
        OrderDetailResponse finalCompleted = orderApplicationService.updateStatus(
            publisherId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null)
        );

        assertEquals("IN_PROGRESS", inProgress.status());
        assertEquals("IN_PROGRESS", waitingConfirm.status());
        assertEquals("COMPLETED", finalCompleted.status());
        assertTrue(waitingConfirm.proofSubmitted());
        assertEquals(2, waitingConfirm.proofImageCount());
        assertEquals("COMPLETED", demandApplicationService.getDetail(demand.id()).status());
    }

    @Test
    void shouldRejectInvalidStatusTransition() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                accepterId,
                accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "跳过处理中", 2)
            )
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldAllowRequesterToConfirmCompletionBeforeProvider() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );

        OrderDetailResponse requesterPending = orderApplicationService.updateStatus(
            publisherId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "先确认完成", null)
        );
        OrderDetailResponse finalCompleted = orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "补交凭证并完成", 2)
        );

        assertEquals("IN_PROGRESS", requesterPending.status());
        assertEquals("COMPLETED", finalCompleted.status());
        assertTrue(finalCompleted.proofSubmitted());
        assertEquals(2, finalCompleted.proofImageCount());
    }

    @Test
    void shouldRejectRepeatedCompletionConfirmationFromSameUser() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );
        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "第一次确认完成", 2)
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                accepterId,
                accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "重复确认", 2)
            )
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRequireProofWhenProviderConfirmsCompletionSecond() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );
        orderApplicationService.updateStatus(
            publisherId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "先确认完成", null)
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                accepterId,
                accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "未上传凭证", null)
            )
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectNonParticipantViewingOrder() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        Long outsiderId = userRepository.save(new User(
            null,
            "outsider@example.edu.cn",
            "20260003",
            "hash",
            "路人",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        )).getId();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.getDetail(outsiderId, accepted.orderId())
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldReturnOrderHistoryForParticipant() {
        DemandDetailResponse firstDemand = createDemand();
        orderApplicationService.accept(accepterId, firstDemand.id(), new AcceptOrderCommand("第一单"));
        DemandDetailResponse secondDemand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "第二个需求",
                "第二个描述",
                null,
                "OTHER",
                "GULOU",
                "鼓楼",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                null,
                null,
                false,
                null,
                null
            )
        );
        demandRepository.findById(secondDemand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        orderApplicationService.accept(accepterId, secondDemand.id(), new AcceptOrderCommand("第二单"));

        PageResponse<OrderSummaryResponse> history = orderApplicationService.listHistory(
            accepterId,
            new OrderHistoryQuery(new PageQuery(1, 20))
        );

        assertEquals(2, history.total());
        assertEquals(2, history.items().size());
    }

    @Test
    void shouldRequestArbitrationForActiveOrder() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        OrderDetailResponse arbitration = orderApplicationService.requestArbitration(
            publisherId,
            accepted.orderId(),
            new RequestOrderArbitrationCommand("对履约细节有争议")
        );

        assertEquals("IN_ARBITRATION", arbitration.status());
        assertTrue(arbitration.statusHistory().stream().anyMatch(item -> "IN_ARBITRATION".equals(item.toStatus())));
    }

    // ==================== accept ↔ update 并发边界 ====================

    @Test
    void shouldRejectAcceptWhenModeChangedToSelectOne() {
        // 序列化验证锁定后重新读取：update 把 DIRECT_ACCEPT 改成 SELECT_ONE 后，accept 必须拒绝，不会绕过新 mode
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "OTHER accept mode", "desc", null, "OTHER", "XIANLIN", "图书馆",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2),
                new BigDecimal("3.00"), List.of(), null, null, false, null, "DIRECT_ACCEPT")
        ).id();
        demandRepository.findById(demandId).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            saved.setIsApproved(true);
            demandRepository.save(saved);
        });

        demandApplicationService.update(
            publisherId, demandId,
            new com.campushub.backend.demand.dto.UpdateDemandCommand(
                null, null, null, null, null, null, null, null, null, null, null, null, null, "SELECT_ONE"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.accept(accepterId, demandId, new AcceptOrderCommand("我来"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        assertEquals(com.campushub.backend.demand.domain.InteractionMode.SELECT_ONE,
            demandRepository.findById(demandId).orElseThrow().getInteractionMode());
        assertTrue(orderRepository.findByDemandId(demandId).isEmpty(),
            "mode=SELECT_ONE 时 /accept 不应创建 Order");
    }

    @Test
    void shouldNotRaceAcceptAndUpdateOnDemandLock() throws Exception {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "OTHER accept race", "desc", null, "OTHER", "XIANLIN", "图书馆",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2),
                new BigDecimal("3.00"), List.of(), null, null, false, null, "DIRECT_ACCEPT")
        ).id();
        demandRepository.findById(demandId).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            saved.setIsApproved(true);
            demandRepository.save(saved);
        });

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger acceptSuccess = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger updateSuccess = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        executor.submit(() -> {
            try {
                start.await();
                orderApplicationService.accept(accepterId, demandId, new AcceptOrderCommand("我来"));
                acceptSuccess.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                demandApplicationService.update(
                    publisherId, demandId,
                    new com.campushub.backend.demand.dto.UpdateDemandCommand(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, "SELECT_ONE"));
                updateSuccess.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // 不变量：有 Order ⇔ Demand=IN_PROGRESS（accept 成功推进），不会出现 Demand=PENDING + 已创建 Order
        boolean hasOrder = orderRepository.findByDemandId(demandId).isPresent();
        com.campushub.backend.demand.domain.Demand finalDemand = demandRepository.findById(demandId).orElseThrow();
        if (hasOrder) {
            assertEquals(com.campushub.backend.demand.domain.DemandStatus.IN_PROGRESS, finalDemand.getStatus(),
                "accept 成功创建 Order 时 Demand 必为 IN_PROGRESS");
            // accept 当时 mode 必为 DIRECT_ACCEPT（锁内校验）；update 若成功则在 accept 之后改 mode
            assertTrue(acceptSuccess.get() == 1, "有 Order 说明 accept 成功");
        } else {
            assertEquals(com.campushub.backend.demand.domain.DemandStatus.PENDING, finalDemand.getStatus(),
                "无 Order 时 Demand 应仍 PENDING");
            assertEquals(com.campushub.backend.demand.domain.InteractionMode.SELECT_ONE, finalDemand.getInteractionMode(),
                "无 Order 说明 update 先改 mode=SELECT_ONE，accept 拒绝");
        }
        // 至少一个操作成功
        assertTrue(acceptSuccess.get() + updateSuccess.get() >= 1);
    }

    @Test
    void shouldNotDoubleCompleteOrderUnderConcurrentConfirmation() throws Exception {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));
        // accepter 先确认完成（提交凭证），等待 publisher 确认
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "已完成并上传凭证", 2));

        // publisher 并发两次确认完成：Order 行锁应保证只有一个完成并结算，另一个被拒绝
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    orderApplicationService.updateStatus(publisherId, accepted.orderId(),
                        new UpdateOrderStatusCommand("COMPLETED", "确认完成", null));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    // ignore framework errors
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // 不变量：并发确认完成只能一个成功结算，另一个被并发保护拒绝（重复完成/订单已 COMPLETED）
        assertEquals(1, success.get(), "并发确认完成只能一个成功结算");
        assertTrue(rejected.get() >= 1, "另一个应被并发保护拒绝");
        com.campushub.backend.order.domain.Order finalOrder = orderRepository.findById(accepted.orderId()).orElseThrow();
        assertEquals(com.campushub.backend.order.domain.OrderStatus.COMPLETED, finalOrder.getStatus(),
            "最终订单状态应为 COMPLETED");
    }

    private DemandDetailResponse createDemand() {
        DemandDetailResponse demand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮拿快递",
                "下午帮忙拿个快递",
                null,
                "EXPRESS",
                "XIANLIN",
                "菜鸟驿站",
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(3),
                new BigDecimal("3.00"),
                List.of("快递"),
                null,
                null,
                false,
                null,
                null
            )
        );
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        return demandApplicationService.getDetail(demand.id());
    }
}
