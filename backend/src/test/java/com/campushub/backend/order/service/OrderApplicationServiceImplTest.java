package com.campushub.backend.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
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

    @Autowired
    private com.campushub.backend.upload.repository.UploadedAssetRepository uploadedAssetRepository;

    @Autowired
    private com.campushub.backend.order.repository.ArbitrationSubmissionRepository arbitrationSubmissionRepository;

    @Autowired
    private UserActionLogRepository userActionLogRepository;

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

        for (String fn : java.util.List.of("proof1.png", "proof2.png", "proof-a.png", "proof-b.png",
                "p1.png", "p2.png", "p3.png", "single.png", "a.png")) {
            uploadedAssetRepository.insert(
                new com.campushub.backend.upload.repository.entity.UploadedAssetEntity(fn, "/api/v1/uploads/2026/10/" + fn, accepterId, true, null));
        }
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
    void onlyAccepterCanCancelAcceptedOrder() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来处理"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                publisherId, accepted.orderId(), new UpdateOrderStatusCommand("CANCELLED", "发布者尝试取消", null)));
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());

        OrderDetailResponse cancelled = orderApplicationService.updateStatus(
            accepterId, accepted.orderId(), new UpdateOrderStatusCommand("CANCELLED", "接单方取消", null));
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", demandApplicationService.getDetail(demand.id()).status());
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
            new UpdateOrderStatusCommand("COMPLETED", "已完成并上传凭证", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png"))
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
    void shouldRejectPublisherConfirmingCompletionBeforeProvider() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                publisherId,
                accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "抢先确认完成", null)
            )
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());

        OrderDetailResponse orderAfter = orderApplicationService.getDetail(publisherId, accepted.orderId());
        assertEquals("IN_PROGRESS", orderAfter.status(), "订单应仍处于进行中，发布者抢先确认不应改变状态");
        assertFalse(orderAfter.proofSubmitted(), "接单方未提交凭证，proofSubmitted 应为 false");
    }

    @Test
    void shouldAllowPublisherToConfirmAfterProviderWithoutProof() {
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
            new UpdateOrderStatusCommand("COMPLETED", "接单方提交凭证", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png"))
        );

        OrderDetailResponse finalCompleted = orderApplicationService.updateStatus(
            publisherId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null)
        );

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
            new UpdateOrderStatusCommand("COMPLETED", "第一次确认完成", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png"))
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                accepterId,
                accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "重复确认", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png"))
            )
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRecoverAfterPublisherPrematureConfirmRejection() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );

        BusinessException premature = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(
                publisherId,
                accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "抢先确认", null)
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, premature.getErrorCode());

        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "提交凭证", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png"))
        );

        OrderDetailResponse finalCompleted = orderApplicationService.updateStatus(
            publisherId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null)
        );
        assertEquals("COMPLETED", finalCompleted.status());
        assertTrue(finalCompleted.proofSubmitted());
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

    @Test
    void shouldSaveArbitrationSubmissionToNewTable() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.requestArbitration(
            publisherId,
            accepted.orderId(),
            new RequestOrderArbitrationCommand("对履约细节有争议", null)
        );

        List<com.campushub.backend.order.domain.ArbitrationSubmission> submissions =
            arbitrationSubmissionRepository.findByOrderId(accepted.orderId());
        assertEquals(1, submissions.size());
        assertEquals("INITIATOR", submissions.get(0).role());
        assertEquals("对履约细节有争议", submissions.get(0).reason());
        assertEquals(publisherId, submissions.get(0).submitterId());
    }

    @Test
    void shouldAllowCounterpartyToSubmitArbitrationResponse() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.requestArbitration(
            publisherId,
            accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null)
        );

        orderApplicationService.submitArbitrationResponse(
            accepterId,
            accepted.orderId(),
            new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("另一方补充说明", null)
        );

        List<com.campushub.backend.order.domain.ArbitrationSubmission> submissions =
            arbitrationSubmissionRepository.findByOrderId(accepted.orderId());
        assertEquals(2, submissions.size());
        assertTrue(submissions.stream().anyMatch(s -> "RESPONDER".equals(s.role()) && "另一方补充说明".equals(s.reason())));
    }

    @Test
    void shouldRejectDuplicateArbitrationResponseFromCounterparty() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.requestArbitration(publisherId, accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null));
        orderApplicationService.submitArbitrationResponse(accepterId, accepted.orderId(),
            new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("第一次说明", null));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderApplicationService.submitArbitrationResponse(accepterId, accepted.orderId(),
                new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("重复说明", null)));

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        assertEquals(2, arbitrationSubmissionRepository.findByOrderId(accepted.orderId()).size());
    }

    @Test
    void shouldRejectArbitrationResponseFromInitiator() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.requestArbitration(
            publisherId,
            accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null)
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.submitArbitrationResponse(
                publisherId,
                accepted.orderId(),
                new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("发起方试图补充", null)
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Autowired
    private com.campushub.backend.admin.service.AdminApplicationService adminApplicationService;

    @Test
    void shouldRejectArbitrationSubmissionAfterResolved() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        orderApplicationService.requestArbitration(
            publisherId,
            accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null)
        );

        Long adminId = userRepository.save(new User(
            null, "admin@example.edu.cn", "20260099", "hash", "管理员", null,
            UserRole.ADMIN, UserStatus.ACTIVE, 100, BigDecimal.ZERO, BigDecimal.ZERO,
            LocalDateTime.now(), LocalDateTime.now()
        )).getId();

        adminApplicationService.resolveOrderArbitration(
            adminId,
            accepted.orderId(),
            new com.campushub.backend.admin.dto.AdminOrderArbitrationCommand("complete", "裁决完成")
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.submitArbitrationResponse(
                accepterId,
                accepted.orderId(),
                new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("裁决后试图补充", null)
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
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
            new UpdateOrderStatusCommand("COMPLETED", "已完成并上传凭证", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png")));

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

    // ==================== F1: proofImageUrls 校验与持久化 ====================

    @Test
    void shouldRejectProviderCompletionWithoutProofImageUrls() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "未上传凭证", 1, null))
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithEmptyProofImageUrls() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "空凭证", 0, List.of()))
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithTooManyProofImages() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "超限凭证", 4, List.of("p1", "p2", "p3", "p4")))
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldReconcileProofImageCountWithUrlsWhenInconsistent() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));
        // 前端传 count=1 但 urls 含 2 张，以 urls 为准修正 count=2
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "凭证与数量不一致", 1, List.of("/api/v1/uploads/2026/10/proof-a.png", "/api/v1/uploads/2026/10/proof-b.png")));
        orderApplicationService.updateStatus(publisherId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null));

        com.campushub.backend.order.domain.Order saved = orderRepository.findById(accepted.orderId()).orElseThrow();
        assertEquals(2, saved.getProofImageCount(), "count 应以 urls 为准修正为 2");
        assertEquals(List.of("/api/v1/uploads/2026/10/proof-a.png", "/api/v1/uploads/2026/10/proof-b.png"), saved.getProofImageUrls());
    }

    @Test
    void shouldPersistAndReadBackProofImageUrls() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "凭证提交", 3, List.of("/api/v1/uploads/2026/10/p1.png", "/api/v1/uploads/2026/10/p2.png", "/api/v1/uploads/2026/10/p3.png")));
        orderApplicationService.updateStatus(publisherId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null));

        com.campushub.backend.order.domain.Order saved = orderRepository.findById(accepted.orderId()).orElseThrow();
        assertEquals(3, saved.getProofImageCount());
        assertEquals(List.of("/api/v1/uploads/2026/10/p1.png", "/api/v1/uploads/2026/10/p2.png", "/api/v1/uploads/2026/10/p3.png"), saved.getProofImageUrls());
    }

    @Test
    void shouldRejectProviderCompletionWithNullElementInProofImageUrls() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "含null元素", 2,
                    java.util.Arrays.asList("/api/v1/uploads/2026/10/a.png", null)))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithBlankElementInProofImageUrls() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "含空白元素", 2,
                    java.util.Arrays.asList("/api/v1/uploads/2026/10/a.png", "   ")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithExternalHttpUrl() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "外部HTTP", 1,
                    List.of("http://evil.com/x.png")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithExternalHttpsUrl() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "外部HTTPS", 1,
                    List.of("https://evil.com/x.png")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithIllegalPath() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "非法路径", 1,
                    List.of("/etc/passwd")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldAcceptProviderCompletionWithOneValidProofUrl() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "单张凭证", 1,
                List.of("/api/v1/uploads/2026/10/single.png")));
        orderApplicationService.updateStatus(publisherId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null));

        com.campushub.backend.order.domain.Order saved = orderRepository.findById(accepted.orderId()).orElseThrow();
        assertEquals(1, saved.getProofImageCount());
        assertEquals(List.of("/api/v1/uploads/2026/10/single.png"), saved.getProofImageUrls());
    }

    @Test
    void shouldRejectProviderCompletionWithProofUrlFromDifferentUser() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        uploadedAssetRepository.insert(
            new com.campushub.backend.upload.repository.entity.UploadedAssetEntity("other-user-asset.png", "/api/v1/uploads/2026/10/other-user-asset.png", publisherId, true, null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "他人凭证", 1,
                    List.of("/api/v1/uploads/2026/10/other-user-asset.png")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithNonExistentProofUrl() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "伪造路径", 1,
                    List.of("/api/v1/uploads/2026/10/nonexistent.png")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRejectProviderCompletionWithMismatchedDatePath() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, accepted.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "日期不匹配", 1,
                    List.of("/api/v1/uploads/2025/01/proof1.png")))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void shouldRecordProofSubmitWhenAcceptorBelatedlySubmitsProofInFinalCompletion() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));

        // 模拟历史订单：发布者已确认完成（REQUESTER_CONFIRMED），但接单方尚未提交凭证
        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        order.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS, publisherId,
            "REQUESTER_CONFIRMED_COMPLETION", LocalDateTime.now());
        orderRepository.save(order);

        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "补交凭证", 1, List.of("/api/v1/uploads/2026/10/proof1.png")));

        List<UserActionLog> proofLogs = userActionLogRepository.findByUserIdAndActionType(accepterId, ActionType.PROOF_SUBMIT);
        assertEquals(1, proofLogs.size(), "历史订单补交凭证应记录一次 PROOF_SUBMIT");
        assertEquals(demand.id(), proofLogs.get(0).getDemandId());
    }

    @Test
    void shouldNotDuplicateProofSubmitLogInNormalDoubleConfirmFlow() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null));
        orderApplicationService.updateStatus(accepterId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "提交凭证", 1, List.of("/api/v1/uploads/2026/10/proof1.png")));
        orderApplicationService.updateStatus(publisherId, accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null));

        List<UserActionLog> proofLogs = userActionLogRepository.findByUserIdAndActionType(accepterId, ActionType.PROOF_SUBMIT);
        assertEquals(1, proofLogs.size(), "正常双确认流程只应记录一次 PROOF_SUBMIT");
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

    @Test
    void shouldRejectCrossOrderBindingOfSameProofImage() {
        DemandDetailResponse demand1 = createDemand();
        DemandDetailResponse demand2 = createDemand();
        OrderDetailResponse order1 = orderApplicationService.accept(accepterId, demand1.id(), new AcceptOrderCommand("处理1"));
        OrderDetailResponse order2 = orderApplicationService.accept(accepterId, demand2.id(), new AcceptOrderCommand("处理2"));

        orderApplicationService.updateStatus(accepterId, order1.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始1", null));
        orderApplicationService.updateStatus(accepterId, order2.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始2", null));

        // 第一个订单提交完成凭证 → 绑定成功
        orderApplicationService.updateStatus(accepterId, order1.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "完成1", 1, List.of("/api/v1/uploads/2026/10/proof1.png")));

        var asset = uploadedAssetRepository.findByUrlPath("/api/v1/uploads/2026/10/proof1.png");
        assertEquals(order1.orderId(), asset.getBoundOrderId());

        // 第二个订单尝试使用同一张图片 → 绑定失败，抛异常
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.updateStatus(accepterId, order2.orderId(),
                new UpdateOrderStatusCommand("COMPLETED", "完成2", 1, List.of("/api/v1/uploads/2026/10/proof1.png")))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());

        // 事务回滚：order2 状态仍为 IN_PROGRESS，proofSubmitted 仍为 false
        OrderDetailResponse order2After = orderApplicationService.getDetail(accepterId, order2.orderId());
        assertEquals("IN_PROGRESS", order2After.status());

        // asset 仍只绑定到 order1
        var assetAfter = uploadedAssetRepository.findByUrlPath("/api/v1/uploads/2026/10/proof1.png");
        assertEquals(order1.orderId(), assetAfter.getBoundOrderId());
    }

    @Test
    void shouldAllowIdempotentRebindToSameOrder() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse order = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(accepterId, order.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始", null));

        // 接单方提交完成凭证（第一方确认）
        OrderDetailResponse firstConfirm = orderApplicationService.updateStatus(accepterId, order.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "完成", 1, List.of("/api/v1/uploads/2026/10/proof2.png")));
        assertEquals("IN_PROGRESS", firstConfirm.status());
        assertTrue(firstConfirm.proofSubmitted());

        // asset 已绑定
        var asset = uploadedAssetRepository.findByUrlPath("/api/v1/uploads/2026/10/proof2.png");
        assertEquals(order.orderId(), asset.getBoundOrderId());

        // 发布方确认完成（第二方确认）→ bindProofImagesAsPrivate 再次调用同一 orderId，幂等
        OrderDetailResponse secondConfirm = orderApplicationService.updateStatus(publisherId, order.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认", null));
        assertEquals("COMPLETED", secondConfirm.status());

        // asset 仍绑定到同一订单
        var assetAfter = uploadedAssetRepository.findByUrlPath("/api/v1/uploads/2026/10/proof2.png");
        assertEquals(order.orderId(), assetAfter.getBoundOrderId());
    }

    // ==================== 仲裁通知与权限回归 ====================

    @Autowired
    private com.campushub.backend.notification.service.NotificationApplicationService notificationApplicationService;

    private List<com.campushub.backend.notification.domain.Notification> getNotifications(Long userId) {
        return notificationRepository.findByUserId(userId);
    }

    @Test
    void shouldNotifyInitiatorWithArbitrationResponseNotOrderArbitration() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.requestArbitration(
            publisherId, accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null)
        );
        // 清除发起仲裁时产生的通知，只观察响应方提交后产生的新通知
        getNotifications(publisherId).forEach(n -> notificationRepository.save(
            new com.campushub.backend.notification.domain.Notification(
                n.getId(), n.getUserId(), n.getType(), n.getTitle(), n.getContent(), true, n.getRelatedId(), n.getCreatedAt()
            )
        ));

        orderApplicationService.submitArbitrationResponse(
            accepterId, accepted.orderId(),
            new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("另一方补充说明", null)
        );

        List<com.campushub.backend.notification.domain.Notification> initiatorNotifications =
            getNotifications(publisherId).stream().filter(n -> !n.isRead()).toList();
        assertFalse(initiatorNotifications.isEmpty(), "发起方应收到通知");
        com.campushub.backend.notification.domain.Notification arbNotification = initiatorNotifications.stream()
            .filter(n -> n.getTitle().contains("仲裁补充说明") || n.getContent().contains("补充说明"))
            .findFirst().orElse(null);
        assertNotNull(arbNotification, "发起方应收到'仲裁补充说明'通知，而非重复'订单进入仲裁'");
        assertFalse(arbNotification.getContent().contains("进入管理员仲裁"),
            "通知内容不应是'订单进入仲裁'的重复消息");
    }

    @Test
    void shouldNotifyAdminWithArbitrationResponseSubmitted() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.requestArbitration(
            publisherId, accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null)
        );
        Long adminId = userRepository.save(new User(
            null, "admin-arb@example.edu.cn", "20260088", "hash", "管理员", null,
            UserRole.ADMIN, UserStatus.ACTIVE, 100, BigDecimal.ZERO, BigDecimal.ZERO,
            LocalDateTime.now(), LocalDateTime.now()
        )).getId();

        orderApplicationService.submitArbitrationResponse(
            accepterId, accepted.orderId(),
            new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("另一方补充说明", null)
        );

        List<com.campushub.backend.notification.domain.Notification> adminNotifications =
            getNotifications(adminId).stream()
                .filter(n -> n.getContent().contains("补充说明"))
                .toList();
        assertFalse(adminNotifications.isEmpty(), "管理员应收到'仲裁补充说明'通知");
    }

    @Test
    void shouldRejectArbitrationResponseWithoutInitiatorSubmission() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        // 直接修改订单状态为 IN_ARBITRATION，但不创建 INITIATOR 记录（模拟数据不一致）
        com.campushub.backend.order.domain.Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        order.setStatus(com.campushub.backend.order.domain.OrderStatus.IN_ARBITRATION);
        orderRepository.save(order);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.submitArbitrationResponse(
                accepterId, accepted.orderId(),
                new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("无发起方记录", null)
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldExposeBothPartiesArbitrationSubmissions() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.requestArbitration(
            publisherId, accepted.orderId(),
            new RequestOrderArbitrationCommand("发起方理由", null)
        );
        orderApplicationService.submitArbitrationResponse(
            accepterId, accepted.orderId(),
            new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("另一方补充说明", null)
        );

        List<com.campushub.backend.order.domain.ArbitrationSubmission> submissions =
            arbitrationSubmissionRepository.findByOrderId(accepted.orderId());
        assertEquals(2, submissions.size(), "应同时存在 INITIATOR 和 RESPONDER 两条记录");
        assertTrue(submissions.stream().anyMatch(s -> "INITIATOR".equals(s.role()) && "发起方理由".equals(s.reason())),
            "应包含发起方理由");
        assertTrue(submissions.stream().anyMatch(s -> "RESPONDER".equals(s.role()) && "另一方补充说明".equals(s.reason())),
            "应包含响应方补充说明");
    }

    @Test
    void shouldNotAllowInitiatorToSubmitAsResponder() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        // accepter 发起仲裁
        orderApplicationService.requestArbitration(
            accepterId, accepted.orderId(),
            new RequestOrderArbitrationCommand("接单方发起仲裁", null)
        );

        // 接单方作为 INITIATOR 不能再提交响应
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> orderApplicationService.submitArbitrationResponse(
                accepterId, accepted.orderId(),
                new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("发起方试图伪装响应方", null)
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());

        // publisher 作为 RESPONDER 可以提交
        orderApplicationService.submitArbitrationResponse(
            publisherId, accepted.orderId(),
            new com.campushub.backend.order.dto.ArbitrationSubmissionCommand("真正的响应方补充说明", null)
        );
        List<com.campushub.backend.order.domain.ArbitrationSubmission> submissions =
            arbitrationSubmissionRepository.findByOrderId(accepted.orderId());
        assertEquals(2, submissions.size());
        assertTrue(submissions.stream().anyMatch(s -> "RESPONDER".equals(s.role()) && publisherId.equals(s.submitterId())));
    }
}
