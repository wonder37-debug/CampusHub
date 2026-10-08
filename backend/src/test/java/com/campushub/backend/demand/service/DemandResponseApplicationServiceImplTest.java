package com.campushub.backend.demand.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandResponse;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.ResponseStatus;
import com.campushub.backend.demand.dto.CreateDemandResponseCommand;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandResponseDetail;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.SelectResponsesCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;
import com.campushub.backend.review.service.ReviewApplicationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
class DemandResponseApplicationServiceImplTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DemandRepository demandRepository;
    @Autowired
    private DemandResponseRepository demandResponseRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DemandApplicationService demandApplicationService;
    @Autowired
    private DemandResponseApplicationService demandResponseApplicationService;
    @Autowired
    private OrderApplicationService orderApplicationService;
    @Autowired
    private ReviewApplicationService reviewApplicationService;
    @Autowired
    private com.campushub.backend.api.ApiViewMapper apiViewMapper;

    private Long publisherId;
    private Long responder1Id;
    private Long responder2Id;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        publisherId = createUser("publisher@example.edu.cn", "20260001", "发布者");
        responder1Id = createUser("responder1@example.edu.cn", "20260002", "报名者1");
        responder2Id = createUser("responder2@example.edu.cn", "20260003", "报名者2");
        outsiderId = createUser("outsider@example.edu.cn", "20260004", "旁观者");
    }

    private Long createUser(String email, String studentId, String nickname) {
        return userRepository.save(new User(
            null, email, studentId, "hash", nickname, null,
            UserRole.USER, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO,
            LocalDateTime.now(), LocalDateTime.now()
        )).getId();
    }

    // ==================== createResponse ====================

    @Test
    void shouldCreateResponseForSelectOneDemand() {
        Long demandId = createSecondHandDemand();

        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要这个")
        );

        assertEquals(demandId, response.demandId());
        assertEquals(responder1Id, response.authorId());
        assertEquals("PENDING", response.status());
    }

    @Test
    void shouldRejectResponseForDirectAcceptDemand() {
        Long demandId = createExpressDemand();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.createResponse(
                responder1Id, demandId, new CreateDemandResponseCommand("我来")
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectDuplicateActiveResponse() {
        Long demandId = createSecondHandDemand();
        demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("第一次")
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.createResponse(
                responder1Id, demandId, new CreateDemandResponseCommand("第二次")
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectResponseFromPublisher() {
        Long demandId = createSecondHandDemand();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.createResponse(
                publisherId, demandId, new CreateDemandResponseCommand("自报名")
            )
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldWithdrawAndRecreateResponse() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail first = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名")
        );
        demandResponseApplicationService.withdrawResponse(responder1Id, first.id());

        DemandResponseDetail second = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("重新报名")
        );
        assertEquals("PENDING", second.status());
    }

    // ==================== selectResponse (SELECT_ONE) ====================

    @Test
    void shouldSelectResponseAndCreateOrder() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要")
        );

        OrderDetailResponse order = demandResponseApplicationService.selectResponse(
            publisherId, demandId, response.id()
        );

        assertEquals(responder1Id, order.accepterId());
        assertEquals("ACCEPTED", order.status());
        assertEquals("SELECTED", demandResponseRepository.findById(response.id()).orElseThrow().getStatus().name());
        assertEquals(DemandStatus.IN_PROGRESS, demandRepository.findById(demandId).orElseThrow().getStatus());
    }

    @Test
    void shouldRejectSelectByNonPublisher() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要")
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.selectResponse(responder2Id, demandId, response.id())
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    // ==================== selectResponses (SELECT_MANY) ====================

    @Test
    void shouldCompleteTeamUpWhenTargetReached() {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")
        );
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")
        );

        DemandDetailResponse result = demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id()))
        );

        assertEquals("COMPLETED", result.status());
        BigDecimal publisherBalance = userRepository.findById(publisherId).orElseThrow().getBalance();
        assertEquals(new BigDecimal("90.00"), publisherBalance);
        BigDecimal r1Balance = userRepository.findById(responder1Id).orElseThrow().getBalance();
        assertEquals(new BigDecimal("105.00"), r1Balance);
    }

    @Test
    void shouldRejectExceedingTargetParticipantCount() {
        Long demandId = createTeamUpDemand(1);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")
        );
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.selectResponses(
                publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id()))
            )
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    // ==================== acceptAnswer (HELP) ====================

    @Test
    void shouldAcceptAnswerAndSettleReward() {
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("这是答案")
        );

        DemandDetailResponse result = demandResponseApplicationService.acceptAnswer(
            publisherId, demandId, response.id()
        );

        assertEquals("COMPLETED", result.status());
        assertEquals("SELECTED", demandResponseRepository.findById(response.id()).orElseThrow().getStatus().name());
        BigDecimal responderBalance = userRepository.findById(responder1Id).orElseThrow().getBalance();
        assertEquals(new BigDecimal("110.00"), responderBalance);
        BigDecimal publisherBalance = userRepository.findById(publisherId).orElseThrow().getBalance();
        assertEquals(new BigDecimal("90.00"), publisherBalance);
    }

    @Test
    void shouldRejectAcceptAnswerForNonHelpDemand() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要")
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.acceptAnswer(publisherId, demandId, response.id())
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void helpDemandShouldNotCreateOrder() {
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("回答")
        );
        demandResponseApplicationService.acceptAnswer(publisherId, demandId, response.id());

        assertTrue(orderRepository.findByDemandId(demandId).isEmpty());
    }

    // ==================== withdrawResponse ====================

    @Test
    void shouldRejectWithdrawByNonAuthor() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名")
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.withdrawResponse(responder2Id, response.id())
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    // ==================== Review for Response (TEAM_UP) ====================

    @Test
    void shouldSubmitReviewForResponseAfterTeamUpCompleted() {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")
        );
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")
        );
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id()))
        );

        ReviewResponse review = reviewApplicationService.submitForResponse(
            publisherId, r1.id(), new SubmitReviewCommand(5, "靠谱")
        );

        assertEquals(r1.id(), review.responseId());
        assertEquals(responder1Id, review.targetId());
    }

    @Test
    void shouldRejectDuplicateReviewForResponse() {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")
        );
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")
        );
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id()))
        );
        reviewApplicationService.submitForResponse(publisherId, r1.id(), new SubmitReviewCommand(5, "第一次"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submitForResponse(publisherId, r1.id(), new SubmitReviewCommand(3, "第二次"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectReviewFromNonParticipantForResponse() {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")
        );
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")
        );
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id()))
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submitForResponse(outsiderId, r1.id(), new SubmitReviewCommand(5, "旁观"))
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    // ==================== Arbitration regression (DIRECT_ACCEPT) ====================

    @Test
    void directAcceptArbitrationFlowStillWorks() {
        Long demandId = createExpressDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(
            responder1Id, demandId, new com.campushub.backend.order.dto.AcceptOrderCommand("我来")
        );
        orderApplicationService.updateStatus(
            responder1Id, accepted.orderId(),
            new com.campushub.backend.order.dto.UpdateOrderStatusCommand("IN_PROGRESS", "开始", null)
        );
        orderApplicationService.requestArbitration(
            publisherId, accepted.orderId(),
            new com.campushub.backend.order.dto.RequestOrderArbitrationCommand("有争议")
        );

        assertEquals("IN_ARBITRATION", orderRepository.findById(accepted.orderId()).orElseThrow().getStatus().name());
    }

    // ==================== 并发回归 ====================

    @Test
    void shouldNotExceedTargetUnderConcurrentSelection() throws Exception {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        Runnable task1 = () -> {
            try {
                start.await();
                demandResponseApplicationService.selectResponses(
                    publisherId, demandId, new SelectResponsesCommand(List.of(r1.id())));
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        };
        Runnable task2 = () -> {
            try {
                start.await();
                demandResponseApplicationService.selectResponses(
                    publisherId, demandId, new SelectResponsesCommand(List.of(r2.id())));
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        };
        executor.submit(task1);
        executor.submit(task2);
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // target=2，两个请求各选 1 人，FOR UPDATE 锁串行化，最终 selected=2 不超额
        assertEquals(2, successes.get());
        assertEquals(0, failures.get());
        assertEquals(2, demandResponseRepository.countSelectedByDemandId(demandId));
        assertEquals(com.campushub.backend.demand.domain.DemandStatus.COMPLETED,
            demandRepository.findById(demandId).orElseThrow().getStatus());
    }

    @Test
    void shouldNotDoubleSettleRewardForHelpUnderConcurrentAccept() throws Exception {
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("回答1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("回答2"));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        executor.submit(() -> {
            try {
                start.await();
                demandResponseApplicationService.acceptAnswer(publisherId, demandId, r1.id());
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                demandResponseApplicationService.acceptAnswer(publisherId, demandId, r2.id());
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // 只有一个采纳成功，另一个因 Demand 已 COMPLETED 失败
        assertEquals(1, successes.get());
        assertEquals(1, failures.get());
        assertEquals(com.campushub.backend.demand.domain.DemandStatus.COMPLETED,
            demandRepository.findById(demandId).orElseThrow().getStatus());
        // 只结算一次：publisher 余额减少 10，只有一个 responder 余额增加 10
        BigDecimal publisherBalance = userRepository.findById(publisherId).orElseThrow().getBalance();
        assertEquals(new BigDecimal("90.00"), publisherBalance);
        long settledCount = java.util.stream.Stream.of(responder1Id, responder2Id)
            .filter(id -> userRepository.findById(id).orElseThrow().getBalance().compareTo(new BigDecimal("110.00")) == 0)
            .count();
        assertEquals(1, settledCount);
    }

    // ==================== update ↔ createResponse 并发回归 ====================

    @Test
    void shouldNotLeaveResponseAndChangedModeUnderConcurrentUpdateAndCreateResponse() throws Exception {
        // OTHER + SELECT_ONE：update 可把 mode 改成 DIRECT_ACCEPT；createResponse 在 SELECT_ONE 下可创建
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "OTHER 并发更新", "描述", null, "OTHER", "XIANLIN", "线上",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(2),
                new BigDecimal("5.00"), List.of(), null, null, false, null, "SELECT_ONE")
        ).id();
        approveDemand(demandId);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger updateSuccess = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger responseSuccess = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        executor.submit(() -> {
            try {
                start.await();
                demandApplicationService.update(
                    publisherId, demandId,
                    new com.campushub.backend.demand.dto.UpdateDemandCommand(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, "DIRECT_ACCEPT"));
                updateSuccess.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                demandResponseApplicationService.createResponse(
                    responder1Id, demandId, new CreateDemandResponseCommand("报名"));
                responseSuccess.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // FOR UPDATE 串行化：恰好一个成功，另一个因互斥条件失败
        assertEquals(1, updateSuccess.get() + responseSuccess.get());
        assertEquals(1, failures.get());

        com.campushub.backend.demand.domain.Demand d = demandRepository.findById(demandId).orElseThrow();
        long responseCount = demandResponseRepository.findByDemandId(demandId).size();
        // 不变量：不会出现“已存在 Response + interactionMode 被改成 DIRECT_ACCEPT”
        if (responseCount > 0) {
            assertEquals(com.campushub.backend.demand.domain.InteractionMode.SELECT_ONE, d.getInteractionMode(),
                "存在 Response 时 interactionMode 不应被修改");
        } else {
            assertEquals(com.campushub.backend.demand.domain.InteractionMode.DIRECT_ACCEPT, d.getInteractionMode(),
                "无 Response 时 update 应成功改为 DIRECT_ACCEPT");
        }
    }

    // ==================== withdrawResponse ↔ selectResponse 并发回归 ====================
    //
    // 说明：H2（MULTI_THREADED=FALSE）下 SELECT ... FOR UPDATE 不提供跨操作行锁等待，
    // withdrawResponse 不修改 Demand 行，仅靠 Demand 行锁无法在 H2 下稳定串行化 Response 读写，
    // 因此真正的并发竞态在 H2 下不可稳定复现（实测两操作均可成功并出现
    // Response=WITHDRAWN + Demand=IN_PROGRESS + Order 的非法终态）。
    // 生产 MySQL 下 FOR UPDATE 会串行化 withdrawResponse 与 selectResponse，修复生效。
    // 按 P1 的 H2 豁免逻辑，改用“锁定后重新读取状态”的序列化集成验证，检查最终数据库状态：
    // selectResponse / selectResponses 把 Response 改成 SELECTED 后，withdrawResponse 必须拒绝。

    @Test
    void shouldRejectWithdrawAfterResponseSelectedForSelectOne() {
        // SELECT_ONE：selectResponse 把 Response 改成 SELECTED、Demand 进入 IN_PROGRESS、创建 Order
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要"));
        demandResponseApplicationService.selectResponse(publisherId, demandId, response.id());

        // withdrawResponse 锁 Demand 后重新查询 Response，发现 SELECTED 必须拒绝
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.withdrawResponse(responder1Id, response.id())
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        assertEquals(com.campushub.backend.demand.domain.ResponseStatus.SELECTED,
            demandResponseRepository.findById(response.id()).orElseThrow().getStatus());
        // Order 已创建且 Reward 结算未被撤回
        assertTrue(orderRepository.findByDemandId(demandId).isPresent());
    }

    @Test
    void shouldRejectWithdrawAfterResponsesSelectedForSelectMany() {
        // SELECT_MANY：selectResponses 达到 target 后 Demand COMPLETED、Reward 平分结算
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id())));

        // 已结算的 SELECTED Response 不能被撤回，避免出现“Reward 已结算 + Response=WITHDRAWN”
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.withdrawResponse(responder1Id, r1.id())
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        assertEquals(com.campushub.backend.demand.domain.ResponseStatus.SELECTED,
            demandResponseRepository.findById(r1.id()).orElseThrow().getStatus());
        assertEquals(com.campushub.backend.demand.domain.DemandStatus.COMPLETED,
            demandRepository.findById(demandId).orElseThrow().getStatus());
    }

    // ==================== Demand.withdraw ↔ 接单/选择流程 回归 ====================

    @Test
    void shouldRejectWithdrawAfterSelectResponseForSelectOne() {
        // SELECT_ONE：selectResponse 后 Demand=IN_PROGRESS + Order 已创建，withdraw 必须拒绝
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要"));
        demandResponseApplicationService.selectResponse(publisherId, demandId, response.id());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.withdraw(publisherId, demandId)
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        // 最终数据库状态：Demand 未被 CANCELLED，Order 仍存在，Response 仍 SELECTED
        assertEquals(com.campushub.backend.demand.domain.DemandStatus.IN_PROGRESS,
            demandRepository.findById(demandId).orElseThrow().getStatus());
        assertTrue(orderRepository.findByDemandId(demandId).isPresent(),
            "select 创建的 Order 不应被 withdraw 撤回");
    }

    @Test
    void shouldRejectWithdrawAfterAcceptAnswerForHelp() {
        // HELP：acceptAnswer 后 Demand=COMPLETED + reward 已结算，withdraw 必须拒绝
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("回答"));
        demandResponseApplicationService.acceptAnswer(publisherId, demandId, response.id());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.withdraw(publisherId, demandId)
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        assertEquals(com.campushub.backend.demand.domain.DemandStatus.COMPLETED,
            demandRepository.findById(demandId).orElseThrow().getStatus());
        // reward 已结算：publisher 余额减少 10，responder 余额增加 10
        assertEquals(new BigDecimal("90.00"), userRepository.findById(publisherId).orElseThrow().getBalance());
        assertEquals(new BigDecimal("110.00"), userRepository.findById(responder1Id).orElseThrow().getBalance());
    }

    @Test
    void shouldRejectWithdrawAfterSelectResponsesForSelectMany() {
        // SELECT_MANY：selectResponses 达 target 后 Demand=COMPLETED + reward 平分结算，withdraw 必须拒绝
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id())));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.withdraw(publisherId, demandId)
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        // 最终数据库状态：Demand 未被 CANCELLED，reward 已结算
        assertEquals(com.campushub.backend.demand.domain.DemandStatus.COMPLETED,
            demandRepository.findById(demandId).orElseThrow().getStatus());
        assertEquals(new BigDecimal("90.00"), userRepository.findById(publisherId).orElseThrow().getBalance());
        assertEquals(new BigDecimal("105.00"), userRepository.findById(responder1Id).orElseThrow().getBalance());
    }

    @Test
    void shouldRejectSelectOnCompletedDemand() {
        Long demandId = createTeamUpDemand(1);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id())));

        // Demand 已 COMPLETED，再次选择必须因 requireDemandPending 失败
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.selectResponses(
                publisherId, demandId, new SelectResponsesCommand(List.of(r1.id())))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectAcceptAnswerOnCompletedDemand() {
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("回答1"));
        demandResponseApplicationService.acceptAnswer(publisherId, demandId, r1.id());

        // Demand 已 COMPLETED，再次采纳必须因 requireDemandPending 失败
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.acceptAnswer(publisherId, demandId, r1.id())
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectSelectOneOnCompletedDemand() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要"));
        demandResponseApplicationService.selectResponse(publisherId, demandId, r1.id());

        // Demand 已 IN_PROGRESS（非 PENDING），再次选择必须因 requireDemandPending 失败
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.selectResponse(publisherId, demandId, r1.id())
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    // ==================== Review 范围回归 ====================

    @Test
    void shouldRejectResponseReviewForHelp() {
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("回答"));
        demandResponseApplicationService.acceptAnswer(publisherId, demandId, response.id());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submitForResponse(
                publisherId, response.id(), new com.campushub.backend.review.dto.SubmitReviewCommand(5, "好"))
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldRejectResponseReviewForSelectOne() {
        Long demandId = createSecondHandDemand();
        DemandResponseDetail response = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要"));
        OrderDetailResponse order = demandResponseApplicationService.selectResponse(
            publisherId, demandId, response.id());
        // 推进 Order 到 COMPLETED
        orderApplicationService.updateStatus(
            responder1Id, order.orderId(),
            new com.campushub.backend.order.dto.UpdateOrderStatusCommand("IN_PROGRESS", "开始", null));
        orderApplicationService.updateStatus(
            responder1Id, order.orderId(),
            new com.campushub.backend.order.dto.UpdateOrderStatusCommand("COMPLETED", "完成", 2, java.util.List.of("/api/v1/uploads/2026/10/proof1.jpg", "/api/v1/uploads/2026/10/proof2.jpg")));
        orderApplicationService.updateStatus(
            publisherId, order.orderId(),
            new com.campushub.backend.order.dto.UpdateOrderStatusCommand("COMPLETED", "确认", null));

        // SELECT_ONE 走 Order Review，Response Review 必须被拒绝
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submitForResponse(
                publisherId, response.id(), new com.campushub.backend.review.dto.SubmitReviewCommand(5, "好"))
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());

        // Order Review 正常
        com.campushub.backend.review.dto.ReviewResponse review = reviewApplicationService.submit(
            publisherId, order.orderId(), new com.campushub.backend.review.dto.SubmitReviewCommand(5, "好评"));
        assertEquals(order.orderId(), review.orderId());
    }

    // ==================== Response 唯一性回归 ====================

    @Test
    void shouldAllowRepeatedWithdrawnThenCreatePending() {
        Long demandId = createSecondHandDemand();
        // 第一轮：create PENDING -> withdraw
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("第一次"));
        demandResponseApplicationService.withdrawResponse(responder1Id, r1.id());
        // 第二轮：create PENDING -> withdraw
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("第二次"));
        demandResponseApplicationService.withdrawResponse(responder1Id, r2.id());
        // 第三轮：create PENDING
        DemandResponseDetail r3 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("第三次"));
        assertEquals("PENDING", r3.status());
    }

    @Test
    void shouldRejectDuplicatePendingResponse() {
        Long demandId = createSecondHandDemand();
        demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("第一次"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.createResponse(
                responder1Id, demandId, new CreateDemandResponseCommand("第二次"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    // ==================== Update 回归 ====================

    @Test
    void shouldRejectUpdateCategoryAfterResponsesExist() {
        Long demandId = createSecondHandDemand();
        demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.update(
                publisherId, demandId,
                new com.campushub.backend.demand.dto.UpdateDemandCommand(
                    null, null, null, "TEAM_UP", null, null, null, null, null, null, null, null, null, null))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectUpdateTargetParticipantCountAfterResponsesExist() {
        Long demandId = createTeamUpDemand(3);
        demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.update(
                publisherId, demandId,
                new com.campushub.backend.demand.dto.UpdateDemandCommand(
                    null, null, null, null, null, null, null, null, null, null, null, null, 1, null))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldAllowUpdateCategoryWhenNoResponses() {
        Long demandId = createSecondHandDemand();
        demandApplicationService.update(
            publisherId, demandId,
            new com.campushub.backend.demand.dto.UpdateDemandCommand(
                null, null, null, "EXPRESS", null, null, null, null, null, null, null, null, null, null));
        assertEquals(com.campushub.backend.demand.domain.DemandCategory.EXPRESS,
            demandRepository.findById(demandId).orElseThrow().getCategory());
    }

    // ==================== OTHER 分类组合测试 ====================

    @Test
    void shouldPublishOtherWithDirectAcceptAndAcceptOrder() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new com.campushub.backend.demand.dto.PublishDemandCommand(
                "OTHER 直接接单", "描述", null, "OTHER", "XIANLIN", "图书馆",
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusHours(2),
                new java.math.BigDecimal("3.00"), java.util.List.of(), null, null, false, null, "DIRECT_ACCEPT")
        ).id();
        approveDemand(demandId);
        com.campushub.backend.demand.domain.Demand d = demandRepository.findById(demandId).orElseThrow();
        assertEquals(com.campushub.backend.demand.domain.InteractionMode.DIRECT_ACCEPT, d.getInteractionMode());
        // 接单流程正常
        orderApplicationService.accept(responder1Id, demandId,
            new com.campushub.backend.order.dto.AcceptOrderCommand("我来"));
        assertEquals("ACCEPTED",
            orderApplicationService.getDetail(responder1Id,
                orderRepository.findAll().stream().filter(o -> o.getDemandId().equals(demandId)).findFirst().orElseThrow().getId()).status());
    }

    @Test
    void shouldPublishOtherWithSelectOneAndCreateOrder() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new com.campushub.backend.demand.dto.PublishDemandCommand(
                "OTHER 选择一人", "描述", null, "OTHER", "XIANLIN", "线上",
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusDays(2),
                new java.math.BigDecimal("5.00"), java.util.List.of(), null, null, false, null, "SELECT_ONE")
        ).id();
        approveDemand(demandId);
        DemandResponseDetail r = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("我要"));
        com.campushub.backend.order.dto.OrderDetailResponse order = demandResponseApplicationService.selectResponse(
            publisherId, demandId, r.id());
        assertEquals("ACCEPTED", order.status());
    }

    @Test
    void shouldPublishOtherWithSelectManyAndComplete() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new com.campushub.backend.demand.dto.PublishDemandCommand(
                "OTHER 组队", "描述", null, "OTHER", "XIANLIN", "操场",
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusHours(3),
                new java.math.BigDecimal("10.00"), java.util.List.of(), null, null, false, 2, "SELECT_MANY")
        ).id();
        approveDemand(demandId);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));
        DemandDetailResponse result = demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(java.util.List.of(r1.id(), r2.id())));
        assertEquals("COMPLETED", result.status());
        // reward 平分：publisher 余额 90，每个 responder 105
        assertEquals(new java.math.BigDecimal("90.00"), userRepository.findById(publisherId).orElseThrow().getBalance());
        assertEquals(new java.math.BigDecimal("105.00"), userRepository.findById(responder1Id).orElseThrow().getBalance());
    }

    @Test
    void shouldRejectOtherSelectManyWithoutTarget() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            demandApplicationService.publish(
                publisherId,
                new com.campushub.backend.demand.dto.PublishDemandCommand(
                    "OTHER 组队无 target", "描述", null, "OTHER", "XIANLIN", "操场",
                    java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusHours(3),
                    new java.math.BigDecimal("10.00"), java.util.List.of(), null, null, false, null, "SELECT_MANY"))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
    }

    @Test
    void shouldRejectOtherSelectManyWithTargetOver100() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            demandApplicationService.publish(
                publisherId,
                new com.campushub.backend.demand.dto.PublishDemandCommand(
                    "OTHER 组队 target 超限", "描述", null, "OTHER", "XIANLIN", "操场",
                    java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusHours(3),
                    new java.math.BigDecimal("10.00"), java.util.List.of(), null, null, false, 101, "SELECT_MANY"))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
    }

    @Test
    void shouldRejectOtherWithHelpInteractionMode() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            demandApplicationService.publish(
                publisherId,
                new com.campushub.backend.demand.dto.PublishDemandCommand(
                    "OTHER HELP", "描述", null, "OTHER", "XIANLIN", "线上",
                    java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusDays(1),
                    new java.math.BigDecimal("5.00"), java.util.List.of(), null, null, false, null, "HELP"))
        );
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getErrorCode());
    }

    // ==================== SELECT_MANY Review 回归 ====================

    @Test
    void shouldAllowPublisherReviewForSelectManyCompleted() {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(java.util.List.of(r1.id(), r2.id())));

        com.campushub.backend.review.dto.ReviewResponse review = reviewApplicationService.submitForResponse(
            publisherId, r1.id(), new com.campushub.backend.review.dto.SubmitReviewCommand(5, "靠谱"));
        assertEquals(r1.id(), review.responseId());
        assertEquals(responder1Id, review.targetId());
    }

    @Test
    void shouldAllowResponseAuthorReviewForSelectManyCompleted() {
        Long demandId = createTeamUpDemand(1);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(java.util.List.of(r1.id())));

        // 被选中者评价发布者
        com.campushub.backend.review.dto.ReviewResponse review = reviewApplicationService.submitForResponse(
            responder1Id, r1.id(), new com.campushub.backend.review.dto.SubmitReviewCommand(4, "感谢组队"));
        assertEquals(publisherId, review.targetId());
    }

    @Test
    void shouldRejectReviewForPendingResponseInSelectMany() {
        Long demandId = createTeamUpDemand(2);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
            reviewApplicationService.submitForResponse(
                publisherId, r1.id(), new com.campushub.backend.review.dto.SubmitReviewCommand(5, "好"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, ex.getErrorCode());
    }

    @Test
    void shouldRejectReviewFromOutsiderForSelectMany() {
        Long demandId = createTeamUpDemand(1);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(java.util.List.of(r1.id())));

        BusinessException ex = assertThrows(BusinessException.class, () ->
            reviewApplicationService.submitForResponse(
                outsiderId, r1.id(), new com.campushub.backend.review.dto.SubmitReviewCommand(5, "旁观"))
        );
        assertEquals(ErrorCode.PERMISSION_DENIED, ex.getErrorCode());
    }

    // ==================== accept 模式限制 ====================

    @Test
    void shouldRejectAcceptForSelectOne() {
        Long demandId = createSecondHandDemand();
        BusinessException ex = assertThrows(BusinessException.class, () ->
            orderApplicationService.accept(responder1Id, demandId,
                new com.campushub.backend.order.dto.AcceptOrderCommand("我来"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, ex.getErrorCode());
    }

    @Test
    void shouldRejectAcceptForSelectMany() {
        Long demandId = createTeamUpDemand(2);
        BusinessException ex = assertThrows(BusinessException.class, () ->
            orderApplicationService.accept(responder1Id, demandId,
                new com.campushub.backend.order.dto.AcceptOrderCommand("我来"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, ex.getErrorCode());
    }

    @Test
    void shouldRejectAcceptForHelp() {
        Long demandId = createHelpDemand(new java.math.BigDecimal("5.00"));
        BusinessException ex = assertThrows(BusinessException.class, () ->
            orderApplicationService.accept(responder1Id, demandId,
                new com.campushub.backend.order.dto.AcceptOrderCommand("我来"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, ex.getErrorCode());
    }

    // ==================== targetParticipantCount 不变量 ====================

    @Test
    void shouldForceNullTargetForOtherDirectAccept() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new com.campushub.backend.demand.dto.PublishDemandCommand(
                "OTHER DA target", "desc", null, "OTHER", "XIANLIN", "图书馆",
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusHours(2),
                new java.math.BigDecimal("3.00"), java.util.List.of(), null, null, false, 3, "DIRECT_ACCEPT")
        ).id();
        approveDemand(demandId);
        com.campushub.backend.demand.domain.Demand d = demandRepository.findById(demandId).orElseThrow();
        assertNull(d.getTargetParticipantCount(),
            "OTHER+DIRECT_ACCEPT must not save targetParticipantCount even if provided");
    }

    @Test
    void shouldForceNullTargetForOtherSelectOne() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new com.campushub.backend.demand.dto.PublishDemandCommand(
                "OTHER S1 target", "desc", null, "OTHER", "XIANLIN", "线上",
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusDays(2),
                new java.math.BigDecimal("5.00"), java.util.List.of(), null, null, false, 3, "SELECT_ONE")
        ).id();
        approveDemand(demandId);
        com.campushub.backend.demand.domain.Demand d = demandRepository.findById(demandId).orElseThrow();
        assertNull(d.getTargetParticipantCount(),
            "OTHER+SELECT_ONE must not save targetParticipantCount even if provided");
    }

    @Test
    void shouldForceNullTargetForHelp() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new com.campushub.backend.demand.dto.PublishDemandCommand(
                "HELP target", "desc", null, "HELP", "XIANLIN", "线上",
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusDays(1),
                new java.math.BigDecimal("5.00"), java.util.List.of(), null, null, false, 3, null)
        ).id();
        approveDemand(demandId);
        com.campushub.backend.demand.domain.Demand d = demandRepository.findById(demandId).orElseThrow();
        assertNull(d.getTargetParticipantCount(),
            "HELP must not save targetParticipantCount even if provided");
    }

    // ==================== Demand 完成后不能创建 Response ====================

    @Test
    void shouldRejectCreateResponseAfterDemandCompleted() {
        Long demandId = createTeamUpDemand(1);
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(java.util.List.of(r1.id())));

        BusinessException ex = assertThrows(BusinessException.class, () ->
            demandResponseApplicationService.createResponse(
                responder2Id, demandId, new CreateDemandResponseCommand("迟到报名"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, ex.getErrorCode());
    }

    // ==================== HELP 多回复 & SELECT_MANY 进度回归 ====================

    @Test
    void shouldAllowMultipleActiveResponsesForHelpMode() {
        Long demandId = createHelpDemand(new BigDecimal("10.00"));

        DemandResponseDetail first = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("第一个回答"));
        DemandResponseDetail second = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("补充回答"));

        assertEquals("PENDING", first.status());
        assertEquals("PENDING", second.status());
        assertNotEquals(first.id(), second.id(), "HELP 模式应允许同一用户提交多条 active Response");
    }

    @Test
    void shouldStillRejectDuplicateActiveResponseForSelectMany() {
        Long demandId = createTeamUpDemand(2);

        demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名"));
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandResponseApplicationService.createResponse(
                responder1Id, demandId, new CreateDemandResponseCommand("再次报名"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldExposeSelectedParticipantCountForSelectMany() {
        Long demandId = createTeamUpDemand(3);
        Long r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")).id();
        Long r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")).id();

        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1)));

        assertEquals(1L, demandResponseRepository.countSelectedByDemandId(demandId),
            "SELECT_MANY 进度应反映已选中人数");
    }

    // ==================== schema 验证：HELP 多 active Response（无唯一约束） ====================

    @Test
    void schemaAllowsMultipleActiveResponsesForSameDemandAuthor() {
        // 直接在 DB 层验证：ord_demand_response 无唯一约束，HELP 场景同一 demand+author 可多条 PENDING
        Long demandId = createHelpDemand(new BigDecimal("10.00"));
        DemandResponse r1 = demandResponseRepository.save(new DemandResponse(
            null, demandId, responder1Id, "第一条", ResponseStatus.PENDING,
            LocalDateTime.now(), LocalDateTime.now()));
        DemandResponse r2 = demandResponseRepository.save(new DemandResponse(
            null, demandId, responder1Id, "第二条", ResponseStatus.PENDING,
            LocalDateTime.now(), LocalDateTime.now()));

        assertThat(r1.getId()).isNotNull();
        assertThat(r2.getId()).isNotNull();
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }

    // ==================== DemandView.selectedParticipantCount 返回值验证 ====================

    @Test
    void shouldReturnCorrectSelectedParticipantCountInDemandView() {
        Long demandId = createTeamUpDemand(3);
        Long r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1")).id();
        Long r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2")).id();
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1)));

        Demand demand = demandRepository.findById(demandId).orElseThrow();
        DemandView view = apiViewMapper.toDemandView(demand, new CurrentUser(outsiderId, UserRole.USER));

        assertEquals(1, view.selectedParticipantCount(), "DemandView.selectedParticipantCount 应反映已选中人数");
    }

    // ==================== batch selectedParticipantCount（避免列表 N+1） ====================

    @Test
    void shouldBatchCountSelectedByDemandIds() {
        Long demand1 = createTeamUpDemand(3);
        Long demand2 = createTeamUpDemand(2);
        Long r1 = demandResponseApplicationService.createResponse(
            responder1Id, demand1, new CreateDemandResponseCommand("报名1")).id();
        Long r2 = demandResponseApplicationService.createResponse(
            responder2Id, demand1, new CreateDemandResponseCommand("报名2")).id();
        Long r3 = demandResponseApplicationService.createResponse(
            responder1Id, demand2, new CreateDemandResponseCommand("报名3")).id();

        demandResponseApplicationService.selectResponses(
            publisherId, demand1, new SelectResponsesCommand(List.of(r1)));

        Map<Long, Long> counts = demandResponseRepository.countSelectedByDemandIds(List.of(demand1, demand2));
        assertEquals(1L, counts.get(demand1), "demand1 已选 1 人");
        assertEquals(0L, counts.getOrDefault(demand2, 0L), "demand2 未选人，batch 查询应返回 0");
    }

    // ==================== helpers ====================

    private Long createExpressDemand() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮拿快递", "下午帮忙拿快递", null, "EXPRESS", "XIANLIN", "菜鸟驿站",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2),
                new BigDecimal("2.00"), List.of("快递"), null, null, false, null, null
            )
        ).id();
        approveDemand(demandId);
        return demandId;
    }

    private Long createSecondHandDemand() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "二手书", "转让二手教材", null, "SECOND_HAND", "XIANLIN", "宿舍",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(3),
                new BigDecimal("5.00"), List.of("教材"), null, null, false, null, null
            )
        ).id();
        approveDemand(demandId);
        return demandId;
    }

    private Long createTeamUpDemand(int targetCount) {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "组队打球", "找队友", null, "TEAM_UP", "XIANLIN", "操场",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3),
                new BigDecimal("10.00"), List.of("篮球"), null, null, false, targetCount, null
            )
        ).id();
        approveDemand(demandId);
        return demandId;
    }

    private Long createHelpDemand(BigDecimal reward) {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "求解答", "高数题不会", null, "HELP", "XIANLIN", "线上",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(1),
                reward, List.of("高数"), null, null, false, null, null
            )
        ).id();
        approveDemand(demandId);
        return demandId;
    }

    private void approveDemand(Long demandId) {
        demandRepository.findById(demandId).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            saved.setIsApproved(true);
            demandRepository.save(saved);
        });
    }
}
