package com.campushub.backend.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.admin.dto.AdminDashboardResponse;
import com.campushub.backend.admin.dto.AdminDemandQuery;
import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
import com.campushub.backend.admin.dto.AdminOrderArbitrationCommand;
import com.campushub.backend.admin.dto.AdminUserQuery;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.demand.service.DemandExpirationScheduler;
import com.campushub.backend.notification.repository.NotificationRepository;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.RequestOrderArbitrationCommand;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
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
class AdminApplicationServiceImplTest {

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
    private AdminApplicationService adminApplicationService;

    @Autowired
    private com.campushub.backend.upload.repository.UploadedAssetRepository uploadedAssetRepository;

    @Autowired
    private DemandExpirationScheduler scheduler;

    private Long adminId;
    private Long publisherId;
    private Long accepterId;

    @BeforeEach
    void setUp() {
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
            new AdminDemandQuery("待审核", "EXPRESS", null, new PageQuery(1, 20))
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
        uploadedAssetRepository.insert(new com.campushub.backend.upload.repository.entity.UploadedAssetEntity("proof1.png", "/api/v1/uploads/2026/10/proof1.png", accepterId, true, null));
        orderApplicationService.updateStatus(
            accepterId,
            order.orderId(),
            new UpdateOrderStatusCommand("COMPLETED", "已完成", 1, List.of("/api/v1/uploads/2026/10/proof1.png"))
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
    void shouldResolveArbitrationAndDeleteOrder() {
        DemandDetailResponse demand = createDemand("仲裁需求", "OTHER");
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        OrderDetailResponse order = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("接单"));
        orderApplicationService.requestArbitration(
            publisherId,
            order.orderId(),
            new RequestOrderArbitrationCommand("存在争议")
        );

        // 仲裁中的订单禁止直接删除：不能绕过正式裁决并销毁争议证据
        BusinessException inArbitrationBlocked = assertThrows(BusinessException.class, () ->
            adminApplicationService.deleteOrder(adminId, order.orderId(), "尝试绕过仲裁删除"));
        assertEquals(ErrorCode.BUSINESS_CONFLICT, inArbitrationBlocked.getErrorCode());
        assertTrue(orderRepository.findById(order.orderId()).isPresent(), "仲裁中订单不应被删除");

        OrderDetailResponse resolved = adminApplicationService.resolveOrderArbitration(
            adminId,
            order.orderId(),
            new AdminOrderArbitrationCommand("complete", "核实后完成")
        );
        assertEquals("COMPLETED", resolved.status());

        // 已完成订单包含结算与评价记录，不允许物理删除
        BusinessException completedBlocked = assertThrows(BusinessException.class, () ->
            adminApplicationService.deleteOrder(adminId, order.orderId(), "尝试删除已完成订单"));
        assertEquals(ErrorCode.BUSINESS_CONFLICT, completedBlocked.getErrorCode());
        assertTrue(orderRepository.findById(order.orderId()).isPresent(), "已完成订单不应被删除");
    }

    @Test
    void shouldRejectDeleteWithoutReasonAndDeleteCancelledOrderWithNotifications() {
        DemandDetailResponse demand = createDemand("仲裁取消需求", "OTHER");
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        OrderDetailResponse order = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("接单"));
        orderApplicationService.requestArbitration(
            publisherId,
            order.orderId(),
            new RequestOrderArbitrationCommand("存在争议")
        );
        adminApplicationService.resolveOrderArbitration(
            adminId,
            order.orderId(),
            new AdminOrderArbitrationCommand("cancel", "核实后取消")
        );

        // 删除原因必填（可追溯，用于通知双方）
        assertThrows(BusinessException.class, () ->
            adminApplicationService.deleteOrder(adminId, order.orderId(), null));

        // 已取消订单允许处置式删除（解冻幂等，不会重复退款）
        adminApplicationService.deleteOrder(adminId, order.orderId(), "违规订单清理");
        assertTrue(orderRepository.findById(order.orderId()).isEmpty());

        // 双方均收到 ORDER_DELETED 通知，relatedId 指向关联需求
        List<String> publisherTypes = notificationRepository.findByUserId(publisherId).stream()
            .map(notification -> notification.getType().name()).toList();
        List<String> accepterTypes = notificationRepository.findByUserId(accepterId).stream()
            .map(notification -> notification.getType().name()).toList();
        assertTrue(publisherTypes.contains("ORDER_DELETED"), "发布者应收到订单删除通知");
        assertTrue(accepterTypes.contains("ORDER_DELETED"), "接单方应收到订单删除通知");
    }

    @Test
    void shouldListArbitrationOrdersByActualStatus() {
        DemandDetailResponse demand = createDemand("仲裁列表需求", "OTHER");
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });

        OrderDetailResponse order = orderApplicationService.accept(accepterId, demand.id(), new AcceptOrderCommand("接单"));
        orderApplicationService.requestArbitration(
            publisherId,
            order.orderId(),
            new RequestOrderArbitrationCommand("存在争议")
        );

        PageResponse<OrderSummaryResponse> page = adminApplicationService.listArbitrationOrders(adminId, 1, 20);

        assertEquals(1, page.total());
        assertEquals(order.orderId(), page.items().get(0).orderId());
        assertEquals("IN_ARBITRATION", page.items().get(0).status());
    }

    @Test
    void shouldRejectReviewAndNotDoubleRefundWhenDemandAlreadyExpired() {
        Long demandId = createReviewingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));
        scheduler.expireOverdueDemands();
        assertEquals(DemandStatus.EXPIRED, demandRepository.findById(demandId).orElseThrow().getStatus());
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO));

        BusinessException exception = assertThrows(BusinessException.class, () ->
            adminApplicationService.reviewDemand(adminId, demandId, new AdminDemandReviewCommand("approve", "通过"))
        );
        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());

        assertEquals(DemandStatus.EXPIRED, demandRepository.findById(demandId).orElseThrow().getStatus(),
            "审核不能把 EXPIRED 覆盖回 PENDING");
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO), "冻结报酬不应被重复解冻");
        assertEquals(0, balance().compareTo(new BigDecimal("100.00")), "不应重复退款");
    }

    @Test
    void shouldNotCorruptStateWhenReviewRacesWithExpiration() throws Exception {
        // endTime 略未来：reviewDemand 读取时尚未过期，会走到 approve 写入分支（PENDING）；
        // 随后 endTime 过期，调度器将 PENDING 流转为 EXPIRED 并解冻一次。
        // 覆盖审核与过期调度竞争的"审核先成功"分支，验证最终状态不回退为 PENDING、报酬只退还一次。
        Long demandId = createReviewingDemand(LocalDateTime.now().plusSeconds(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        executor.submit(() -> {
            try {
                start.await();
                adminApplicationService.reviewDemand(adminId, demandId, new AdminDemandReviewCommand("approve", "通过"));
            } catch (BusinessException ignored) {
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                // 等待 endTime 过期后再触发调度器，确保读到审核写入后的 PENDING 并流转为 EXPIRED
                Thread.sleep(1500);
                scheduler.expireOverdueDemands();
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        Demand finalDemand = demandRepository.findById(demandId).orElseThrow();
        assertEquals(DemandStatus.EXPIRED, finalDemand.getStatus(),
            "审核通过（PENDING）后 endTime 过期，调度器应流转为 EXPIRED，不回退为 PENDING");
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO), "冻结报酬应只解冻一次");
        assertEquals(0, balance().compareTo(new BigDecimal("100.00")), "退款应只发生一次");
        assertEquals(0, failures.get(), "并发执行不应产生未捕获异常");
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
                null,
                null,
                false,
                null,
                null
            )
        );
    }

    private Long createReviewingDemand(LocalDateTime endTime, BigDecimal reward, BigDecimal frozenBalance) {
        User user = userRepository.findById(publisherId).orElseThrow();
        user.setFrozenBalance(frozenBalance);
        userRepository.save(user);
        Demand demand = new Demand(
            null, publisherId, "发布者", "审核竞争测试", "描述", null,
            DemandCategory.EXPRESS, CampusZone.XIANLIN, "仙林",
            LocalDateTime.now().minusHours(2), endTime, reward,
            InteractionMode.DIRECT_ACCEPT, null, List.of(), List.of(), null,
            DemandStatus.REVIEWING, true, false, null, null, null, null,
            LocalDateTime.now(), LocalDateTime.now()
        );
        return demandRepository.save(demand).getId();
    }

    private BigDecimal frozenBalance() {
        return userRepository.findById(publisherId).orElseThrow().getFrozenBalance();
    }

    private BigDecimal balance() {
        return userRepository.findById(publisherId).orElseThrow().getBalance();
    }
}
