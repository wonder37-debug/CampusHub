package com.campushub.backend.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.admin.dto.AdminDashboardResponse;
import com.campushub.backend.admin.dto.AdminDemandQuery;
import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
import com.campushub.backend.admin.dto.AdminUserQuery;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.auth.repository.InMemoryUserRepository;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
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
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.InMemoryOrderRepository;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.order.service.OrderApplicationServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminApplicationServiceImplTest {

    private UserRepository userRepository;
    private DemandRepository demandRepository;
    private OrderRepository orderRepository;
    private DemandApplicationService demandApplicationService;
    private OrderApplicationService orderApplicationService;
    private AdminApplicationService adminApplicationService;
    private Long adminId;
    private Long publisherId;
    private Long accepterId;
    private NotificationApplicationService notificationApplicationService;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        demandRepository = new InMemoryDemandRepository();
        orderRepository = new InMemoryOrderRepository();
        notificationApplicationService = new NotificationApplicationServiceImpl(new InMemoryNotificationRepository());
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
        adminApplicationService = new AdminApplicationServiceImpl(
            userRepository,
            demandRepository,
            orderRepository,
            notificationApplicationService
        );

        adminId = userRepository.save(new User(
            null,
            "admin@example.edu.cn",
            "20260000",
            "hash",
            "管理员",
            null,
            UserRole.ADMIN,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        )).getId();
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
    void shouldRejectNonAdminOperation() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
            adminApplicationService.listUsers(
                publisherId,
                new AdminUserQuery(null, null, null, null, null, null, new PageQuery(1, 20))
            )
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldListUsersByKeyword() {
        userRepository.save(new User(
            null,
            "extra@example.edu.cn",
            "20260003",
            "hash",
            "测试用户",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        ));

        PageResponse<UserProfileResponse> page = adminApplicationService.listUsers(
            adminId,
            new AdminUserQuery("20260003", "studentId", null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(1, page.total());
        assertEquals("20260003", page.items().get(0).studentId());
    }

    @Test
    void shouldFilterAndSortUsersByRoleStatusAndCreditScore() {
        userRepository.save(new User(
            null,
            "lower-score@example.edu.cn",
            "20260111",
            "hash",
            "低分用户",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            60,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        ));
        userRepository.save(new User(
            null,
            "higher-score@example.edu.cn",
            "20260112",
            "hash",
            "高分用户",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            95,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        ));

        PageResponse<UserProfileResponse> page = adminApplicationService.listUsers(
            adminId,
            new AdminUserQuery(null, null, "USER", "ACTIVE", "creditScore", "desc", new PageQuery(1, 20))
        );

        assertTrue(page.items().size() >= 2);
        assertEquals(100, page.items().get(0).creditScore());
        assertTrue(page.items().stream().allMatch(item -> item.role() == UserRole.USER));
        assertTrue(page.items().stream().allMatch(item -> item.status() == UserStatus.ACTIVE));
    }

    @Test
    void shouldBanAndUnbanUser() {
        UserProfileResponse banned = adminApplicationService.banUser(adminId, publisherId, "违规内容");
        assertEquals(UserStatus.BANNED, banned.status());

        UserProfileResponse active = adminApplicationService.unbanUser(adminId, publisherId);
        assertEquals(UserStatus.ACTIVE, active.status());
    }

    @Test
    void shouldListAndApproveReviewingDemand() {
        DemandDetailResponse reviewingDemand = createDemand("待审核跑腿", "EXPRESS");

        PageResponse<DemandSummaryResponse> pendingPage = adminApplicationService.listPendingDemands(
            adminId,
            new AdminDemandQuery("待审核", "EXPRESS", new PageQuery(1, 20))
        );
        assertEquals(1, pendingPage.total());
        assertEquals(reviewingDemand.id(), pendingPage.items().get(0).id());

        DemandDetailResponse approved = adminApplicationService.reviewDemand(
            adminId,
            reviewingDemand.id(),
            new AdminDemandReviewCommand("approve", "通过审核")
        );
        assertEquals(DemandStatus.PENDING.name(), approved.status());
        assertTrue(demandRepository.findById(reviewingDemand.id()).orElseThrow().getIsApproved());
    }

    @Test
    void shouldBuildDashboardStats() {
        userRepository.save(new User(
            null,
            "inactive-today@example.edu.cn",
            "20269999",
            "hash",
            "今日未活跃",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        ));

        DemandDetailResponse reviewingDemand = createDemand("待审核咨询", "STUDY_TUTORING");

        DemandDetailResponse completedDemand = createDemand("已完成快递", "EXPRESS");
        demandRepository.findById(completedDemand.id()).ifPresent(demand -> {
            demand.setStatus(DemandStatus.PENDING);
            demandRepository.save(demand);
        });
        OrderDetailResponse order = orderApplicationService.accept(
            accepterId,
            completedDemand.id(),
            new AcceptOrderCommand("我来处理")
        );
        orderApplicationService.updateStatus(
            accepterId,
            order.orderId(),
            new UpdateOrderStatusCommand("IN_PROGRESS", "开始处理", null)
        );
        orderApplicationService.updateStatus(
            accepterId,
            order.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "已完成", 1)
        );
        orderApplicationService.updateStatus(
            publisherId,
            order.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "确认完成", null)
        );

        AdminDashboardResponse dashboard = adminApplicationService.getDashboard(adminId);

        assertEquals(4, dashboard.totalUsers());
        assertEquals(2, dashboard.totalDemands());
        assertEquals(1, dashboard.pendingReviewDemands());
        assertEquals(1, dashboard.totalOrders());
        assertEquals(1, dashboard.completedOrders());
        assertEquals(2, dashboard.dailyActiveUsers());
        assertTrue(dashboard.categoryDistribution().stream()
            .anyMatch(item -> item.category().equals("EXPRESS") && item.count() == 1));
        assertTrue(dashboard.categoryDistribution().stream()
            .anyMatch(item -> item.category().equals("STUDY_TUTORING") && item.count() == 1));
    }

    @Test
    void shouldRejectReviewForNonReviewingDemand() {
        DemandDetailResponse demand = createDemand("普通需求", "OTHER");
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });

        BusinessException exception = assertThrows(BusinessException.class, () ->
            adminApplicationService.reviewDemand(
                adminId,
                demand.id(),
                new AdminDemandReviewCommand("reject", "驳回")
            )
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldPersistRejectReasonAndNotifyPublisher() {
        DemandDetailResponse reviewingDemand = createDemand("待驳回需求", "OTHER");

        DemandDetailResponse rejected = adminApplicationService.reviewDemand(
            adminId,
            reviewingDemand.id(),
            new AdminDemandReviewCommand("reject", "信息不完整")
        );

        assertEquals(DemandStatus.CANCELLED.name(), rejected.status());
        assertEquals("信息不完整", rejected.reviewReason());
        assertEquals(adminId, rejected.reviewedBy());

        var notifications = notificationApplicationService.list(
            publisherId,
            new com.campushub.backend.notification.dto.NotificationQuery(false, new PageQuery(1, 20))
        );
        assertTrue(notifications.items().stream()
            .anyMatch(item -> "DEMAND_REJECTED".equals(item.type())
                && reviewingDemand.id().equals(item.targetId())
                && item.content().contains("信息不完整")));
    }

    private DemandDetailResponse createDemand(String title, String category) {
        return demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                title,
                title + " 描述",
                null,
                category,
                "XIANLIN",
                "仙林",
                null,
                null,
                BigDecimal.ONE,
                List.of("tag"),
                false
            )
        );
    }
}
