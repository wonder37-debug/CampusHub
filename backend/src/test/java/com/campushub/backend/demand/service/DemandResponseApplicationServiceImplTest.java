package com.campushub.backend.demand.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
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

    // ==================== helpers ====================

    private Long createExpressDemand() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮拿快递", "下午帮忙拿快递", null, "EXPRESS", "XIANLIN", "菜鸟驿站",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2),
                new BigDecimal("2.00"), List.of("快递"), null, null, false, null
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
                new BigDecimal("5.00"), List.of("教材"), null, null, false, null
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
                new BigDecimal("10.00"), List.of("篮球"), null, null, false, targetCount
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
                reward, List.of("高数"), null, null, false, null
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
