package com.campushub.backend.review.service;

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
import com.campushub.backend.demand.dto.DemandResponseDetail;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.SelectResponsesCommand;
import com.campushub.backend.demand.dto.CreateDemandResponseCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.demand.service.DemandResponseApplicationService;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.notification.repository.NotificationRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;
import com.campushub.backend.review.repository.ReviewRepository;
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
class ReviewApplicationServiceImplTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DemandRepository demandRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DemandApplicationService demandApplicationService;

    @Autowired
    private DemandResponseApplicationService demandResponseApplicationService;

    @Autowired
    private DemandResponseRepository demandResponseRepository;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private ReviewApplicationService reviewApplicationService;

    @Autowired
    private NotificationApplicationService notificationApplicationService;

    private Long publisherId;
    private Long accepterId;
    private Long outsiderId;
    private Long responder1Id;
    private Long responder2Id;

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
        outsiderId = userRepository.save(new User(
            null,
            "outsider@example.edu.cn",
            "20260003",
            "hash",
            "旁观者",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        )).getId();
        responder1Id = userRepository.save(new User(
            null,
            "responder1@example.edu.cn",
            "20260004",
            "hash",
            "报名者1",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        )).getId();
        responder2Id = userRepository.save(new User(
            null,
            "responder2@example.edu.cn",
            "20260005",
            "hash",
            "报名者2",
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
    void shouldSubmitReviewAndRecalculateCreditScore() {
        OrderDetailResponse order = createCompletedOrder();

        ReviewResponse response = reviewApplicationService.submit(
            publisherId,
            order.orderId(),
            new SubmitReviewCommand(4, "完成得不错")
        );

        assertEquals(order.orderId(), response.orderId());
        assertEquals(accepterId, response.targetId());
        assertEquals(98, userRepository.findById(accepterId).orElseThrow().getCreditScore());
    }

    @Test
    void shouldRejectDuplicateReviewFromSameAuthor() {
        OrderDetailResponse order = createCompletedOrder();
        reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(5, "第一次"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(3, "第二次"))
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
        assertEquals(1, reviewRepository.findByOrderId(order.orderId()).size());
    }

    @Test
    void shouldRejectReviewBeforeOrderCompleted() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submit(publisherId, accepted.orderId(), new SubmitReviewCommand(5, "还没完成"))
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldRejectReviewFromNonParticipant() {
        OrderDetailResponse order = createCompletedOrder();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> reviewApplicationService.submit(outsiderId, order.orderId(), new SubmitReviewCommand(5, "无权评价"))
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldListUserReviews() {
        OrderDetailResponse order = createCompletedOrder();
        reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(5, "很好"));

        PageResponse<ReviewResponse> page = reviewApplicationService.listUserReviews(
            accepterId,
            new ReviewQuery(new PageQuery(1, 20))
        );

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
    }

    @Test
    void shouldGenerateNotificationAfterReviewSubmitted() {
        OrderDetailResponse order = createCompletedOrder();
        reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(5, "很好"));

        PageResponse<NotificationResponse> notifications = notificationApplicationService.list(
            accepterId,
            new NotificationQuery(false, new PageQuery(1, 20))
        );

        assertTrue(notifications.items().stream().anyMatch(item -> "REVIEW_RECEIVED".equals(item.type())));
    }

    @Test
    void shouldNotifyParticipantWhenPublisherReviewsResponse() {
        // SELECT_MANY 完成后 publisher 评价 participant（response.author），应通知 participant
        Long demandId = createTeamUpDemandCompleted();
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id())));

        reviewApplicationService.submitForResponse(
            publisherId, r1.id(), new SubmitReviewCommand(5, "靠谱"));

        PageResponse<NotificationResponse> notifications = notificationApplicationService.list(
            responder1Id,
            new NotificationQuery(false, new PageQuery(1, 20))
        );
        NotificationResponse reviewNotification = notifications.items().stream()
            .filter(item -> "RESPONSE_REVIEW_RECEIVED".equals(item.type()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("participant 未收到 RESPONSE_REVIEW_RECEIVED 通知"));
        assertEquals("DEMAND", reviewNotification.targetType(),
            "Response Review 通知 targetType 必须为 DEMAND，避免前端误判为 Order Review");
        assertEquals(demandId, reviewNotification.targetId(),
            "Response Review 通知 targetId 应为 demandId，前端跳 demand 详情");
    }

    @Test
    void shouldNotifyPublisherWhenParticipantReviewsResponse() {
        // SELECT_MANY 完成后 participant 评价 publisher，应通知 publisher
        Long demandId = createTeamUpDemandCompleted();
        DemandResponseDetail r1 = demandResponseApplicationService.createResponse(
            responder1Id, demandId, new CreateDemandResponseCommand("报名1"));
        DemandResponseDetail r2 = demandResponseApplicationService.createResponse(
            responder2Id, demandId, new CreateDemandResponseCommand("报名2"));
        demandResponseApplicationService.selectResponses(
            publisherId, demandId, new SelectResponsesCommand(List.of(r1.id(), r2.id())));

        reviewApplicationService.submitForResponse(
            responder1Id, r1.id(), new SubmitReviewCommand(4, "感谢组队"));

        PageResponse<NotificationResponse> notifications = notificationApplicationService.list(
            publisherId,
            new NotificationQuery(false, new PageQuery(1, 20))
        );
        assertTrue(notifications.items().stream().anyMatch(item -> "RESPONSE_REVIEW_RECEIVED".equals(item.type())),
            "publisher 未收到 RESPONSE_REVIEW_RECEIVED 通知");
    }

    @Test
    void shouldNotBreakOrderReviewNotificationAfterResponseReviewAdded() {
        // 验证新增 Response Review 通知不破坏既有 Order Review 通知
        OrderDetailResponse order = createCompletedOrder();
        reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(5, "很好"));

        PageResponse<NotificationResponse> notifications = notificationApplicationService.list(
            accepterId,
            new NotificationQuery(false, new PageQuery(1, 20))
        );
        assertTrue(notifications.items().stream().anyMatch(item -> "REVIEW_RECEIVED".equals(item.type())),
            "Order Review 通知仍应为 REVIEW_RECEIVED");
    }

    // ==================== F2: listUserReviews 双向查询 ====================

    @Test
    void shouldListReviewsGivenByUserWhenOnlyAuthor() {
        // publisher 评价 accepter，publisher 作为 author 查询应返回自己发出的评价
        OrderDetailResponse order = createCompletedOrder();
        reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(5, "很好"));

        PageResponse<ReviewResponse> page = reviewApplicationService.listUserReviews(
            publisherId, new ReviewQuery(new PageQuery(1, 20)));

        assertEquals(1, page.total(), "publisher 作为 author 应查到发出的评价");
        assertEquals(publisherId, page.items().get(0).authorId());
    }

    @Test
    void shouldListBothGivenAndReceivedReviews() {
        // 双方互评后，任一方查询应同时返回发出与收到的评价
        OrderDetailResponse order = createCompletedOrder();
        reviewApplicationService.submit(publisherId, order.orderId(), new SubmitReviewCommand(5, "接单方不错"));
        reviewApplicationService.submit(accepterId, order.orderId(), new SubmitReviewCommand(4, "发布方靠谱"));

        PageResponse<ReviewResponse> publisherPage = reviewApplicationService.listUserReviews(
            publisherId, new ReviewQuery(new PageQuery(1, 20)));
        assertEquals(2, publisherPage.total(), "publisher 应同时看到发出与收到的评价");

        PageResponse<ReviewResponse> accepterPage = reviewApplicationService.listUserReviews(
            accepterId, new ReviewQuery(new PageQuery(1, 20)));
        assertEquals(2, accepterPage.total(), "accepter 应同时看到发出与收到的评价");
    }

    @Test
    void shouldReturnEmptyWhenUserHasNoReviews() {
        PageResponse<ReviewResponse> page = reviewApplicationService.listUserReviews(
            outsiderId, new ReviewQuery(new PageQuery(1, 20)));
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    private Long createTeamUpDemandCompleted() {
        Long demandId = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "组队打球", "找队友", null, "TEAM_UP", "XIANLIN", "操场",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3),
                new BigDecimal("10.00"), List.of("篮球"), null, null, false, 2, null)
        ).id();
        demandRepository.findById(demandId).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            saved.setIsApproved(true);
            demandRepository.save(saved);
        });
        return demandId;
    }

    private DemandDetailResponse createDemand() {
        DemandDetailResponse demand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮拿快递",
                "下午帮忙拿快递",
                null,
                "EXPRESS",
                "XIANLIN",
                "菜鸟驿站",
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(2),
                new BigDecimal("2.00"),
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

    private OrderDetailResponse createCompletedOrder() {
        DemandDetailResponse demand = createDemand();
        OrderDetailResponse accepted = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("我来"));
        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始", null)
        );
        orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "完成", 2, List.of("/api/v1/uploads/2026/10/proof1.png", "/api/v1/uploads/2026/10/proof2.png"))
        );
        return orderApplicationService.updateStatus(
            publisherId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null)
        );
    }
}
