package com.campushub.backend.review.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.InMemoryUserRepository;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.InMemoryDemandRepository;
import com.campushub.backend.demand.service.DefaultSensitiveWordChecker;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.demand.service.DemandApplicationServiceImpl;
import com.campushub.backend.notification.dto.NotificationQuery;
import com.campushub.backend.notification.dto.NotificationResponse;
import com.campushub.backend.notification.repository.InMemoryNotificationRepository;
import com.campushub.backend.notification.repository.NotificationRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.notification.service.NotificationApplicationServiceImpl;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.InMemoryOrderRepository;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.order.service.OrderApplicationServiceImpl;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;
import com.campushub.backend.review.repository.InMemoryReviewRepository;
import com.campushub.backend.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewApplicationServiceImplTest {

    private UserRepository userRepository;
    private DemandRepository demandRepository;
    private OrderRepository orderRepository;
    private ReviewRepository reviewRepository;
    private NotificationRepository notificationRepository;
    private DemandApplicationService demandApplicationService;
    private OrderApplicationService orderApplicationService;
    private ReviewApplicationService reviewApplicationService;
    private NotificationApplicationService notificationApplicationService;
    private Long publisherId;
    private Long accepterId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        demandRepository = new InMemoryDemandRepository();
        orderRepository = new InMemoryOrderRepository();
        reviewRepository = new InMemoryReviewRepository();
        notificationRepository = new InMemoryNotificationRepository();
        notificationApplicationService = new NotificationApplicationServiceImpl(notificationRepository);
        demandApplicationService = new DemandApplicationServiceImpl(
            demandRepository,
            userRepository,
            new DefaultSensitiveWordChecker()
        );
        orderApplicationService = new OrderApplicationServiceImpl(
            orderRepository,
            demandRepository,
            userRepository,
            notificationApplicationService
        );
        reviewApplicationService = new ReviewApplicationServiceImpl(
            reviewRepository,
            orderRepository,
            userRepository,
            notificationApplicationService
        );

        publisherId = userRepository.save(new User(
            null, "publisher@example.edu.cn", "20260001", "hash", "发布者", null,
            UserRole.USER, UserStatus.ACTIVE, 100, LocalDateTime.now(), LocalDateTime.now()
        )).getId();
        accepterId = userRepository.save(new User(
            null, "accepter@example.edu.cn", "20260002", "hash", "接单者", null,
            UserRole.USER, UserStatus.ACTIVE, 100, LocalDateTime.now(), LocalDateTime.now()
        )).getId();
        outsiderId = userRepository.save(new User(
            null, "outsider@example.edu.cn", "20260003", "hash", "旁观者", null,
            UserRole.USER, UserStatus.ACTIVE, 100, LocalDateTime.now(), LocalDateTime.now()
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
        assertEquals(80, userRepository.findById(accepterId).orElseThrow().getCreditScore());
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

    private DemandDetailResponse createDemand() {
        DemandDetailResponse demand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮拿快递",
                "下午帮忙拿快递",
                "EXPRESS",
                "XIANLIN",
                "菜鸟驿站",
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(2),
                new BigDecimal("2.00"),
                List.of("快递"),
                false
            )
        );
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(com.campushub.backend.demand.domain.DemandStatus.PENDING);
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
        return orderApplicationService.updateStatus(
            accepterId,
            accepted.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "完成", 2)
        );
    }
}
