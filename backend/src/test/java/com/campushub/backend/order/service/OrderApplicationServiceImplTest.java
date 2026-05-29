package com.campushub.backend.order.service;

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
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.InMemoryDemandRepository;
import com.campushub.backend.demand.service.DefaultSensitiveWordChecker;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.demand.service.DemandApplicationServiceImpl;
import com.campushub.backend.notification.repository.InMemoryNotificationRepository;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.notification.service.NotificationApplicationServiceImpl;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.InMemoryOrderRepository;
import com.campushub.backend.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderApplicationServiceImplTest {

    private UserRepository userRepository;
    private DemandRepository demandRepository;
    private OrderRepository orderRepository;
    private DemandApplicationService demandApplicationService;
    private OrderApplicationService orderApplicationService;
    private Long publisherId;
    private Long accepterId;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        demandRepository = new InMemoryDemandRepository();
        orderRepository = new InMemoryOrderRepository();
        NotificationApplicationService notificationApplicationService =
            new NotificationApplicationServiceImpl(new InMemoryNotificationRepository());
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
                false
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
                false
            )
        );
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        return demandApplicationService.getDetail(demand.id());
    }
}
