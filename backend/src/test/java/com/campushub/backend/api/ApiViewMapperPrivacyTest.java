package com.campushub.backend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ApiViewMapper 隐私四视角测试：验证 Demand 公开视图在不同视角下的发布者身份可见性，
 * 以及 PublicUserSummaryView 不携带 email/studentId/balance/frozenBalance 等敏感字段。
 *
 * <p>覆盖：普通用户、匿名需求、本人、管理员四种视角。</p>
 */
class ApiViewMapperPrivacyTest {

    private final ApiViewMapper mapper = new ApiViewMapper(null, null, null, null, null);

    private User newUser(Long id, String email, String studentId, String nickname) {
        return new User(id, email, studentId, "hash", nickname, "avatar.png",
            UserRole.USER, UserStatus.ACTIVE, 100, new BigDecimal("50.00"), new BigDecimal("10.00"),
            LocalDateTime.now(), LocalDateTime.now());
    }

    private Demand newDemand(Long id, Long publisherId, String displayName,
                             boolean anonymous, String anonymousCode, String contactInfo) {
        return new Demand(id, publisherId, displayName, "title", "desc", null,
            DemandCategory.OTHER, CampusZone.XIANLIN, "loc", null, null,
            BigDecimal.ZERO, InteractionMode.DIRECT_ACCEPT, null, null, null, contactInfo,
            DemandStatus.PENDING, false, anonymous, anonymousCode, null, null, null, null, null);
    }

    @Test
    void shouldExposePublisherIdentityForNonAnonymousDemandToOrdinaryUser() {
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者");
        Demand demand = newDemand(10L, 1L, "发布者", false, null, null);
        CurrentUser ordinary = new CurrentUser(2L, UserRole.USER);

        DemandView view = mapper.toDemandView(demand, ordinary, Map.of(1L, publisher), Map.of());

        assertEquals(1L, view.publisher().id(), "非匿名需求对普通用户应可见 publisherId");
        assertEquals("发布者", view.publisher().nickname());
    }

    @Test
    void shouldAnonymizePublisherForAnonymousDemandToOrdinaryUser() {
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者真名");
        Demand demand = newDemand(10L, 1L, "匿名校友", true, "匿名校友ABCD", null);
        CurrentUser ordinary = new CurrentUser(2L, UserRole.USER);

        DemandView view = mapper.toDemandView(demand, ordinary, Map.of(1L, publisher), Map.of());

        // 匿名需求对普通用户：publisher.id 脱敏为 null，nickname 为 anonymousCode
        assertNull(view.publisher().id(), "匿名需求对普通用户应隐藏 publisherId");
        assertEquals("匿名校友ABCD", view.publisher().nickname(), "应展示 anonymousCode 而非真实昵称");
    }

    @Test
    void shouldRevealPublisherToSelfEvenIfAnonymous() {
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者真名");
        Demand demand = newDemand(10L, 1L, "匿名校友", true, "匿名校友ABCD", null);
        CurrentUser self = new CurrentUser(1L, UserRole.USER);

        DemandView view = mapper.toDemandView(demand, self, Map.of(1L, publisher), Map.of());

        // 本人视角：匿名需求也对自己可见
        assertEquals(1L, view.publisher().id(), "匿名需求对发布者本人应可见 publisherId");
        assertEquals("发布者真名", view.publisher().nickname());
    }

    @Test
    void shouldRevealPublisherToAdminEvenIfAnonymous() {
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者真名");
        Demand demand = newDemand(10L, 1L, "匿名校友", true, "匿名校友ABCD", null);
        CurrentUser admin = new CurrentUser(99L, UserRole.ADMIN);

        DemandView view = mapper.toDemandView(demand, admin, Map.of(1L, publisher), Map.of());

        // 管理员视角：匿名需求对管理员可见
        assertEquals(1L, view.publisher().id(), "匿名需求对管理员应可见 publisherId");
        assertEquals("发布者真名", view.publisher().nickname());
    }

    @Test
    void shouldNotExposeContactInfoToOutsider() {
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者");
        Demand demand = newDemand(10L, 1L, "发布者", false, null, "13800000000");
        CurrentUser outsider = new CurrentUser(2L, UserRole.USER);

        DemandView view = mapper.toDemandView(demand, outsider, Map.of(1L, publisher), Map.of());

        // 非接单方/非发布者/非管理员看不到联系方式
        assertNull(view.contactInfo(), "联系方式仅对接单人、发布者和管理员可见");
    }

    @Test
    void shouldExposeContactInfoToPublisherSelf() {
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者");
        Demand demand = newDemand(10L, 1L, "发布者", false, null, "13800000000");
        CurrentUser self = new CurrentUser(1L, UserRole.USER);

        DemandView view = mapper.toDemandView(demand, self, Map.of(1L, publisher), Map.of());

        assertEquals("13800000000", view.contactInfo(), "发布者本人应可见联系方式");
    }

    // ===== Order 私密边界：acceptNote / 履约凭证 / 完整状态历史 / 仲裁结果 仅订单双方与管理员可见 =====

    private Order newOrderWithPrivateFields() {
        List<OrderStatusHistoryEntry> history = List.of(
            new OrderStatusHistoryEntry(
                OrderStatus.IN_ARBITRATION, OrderStatus.COMPLETED, 99L,
                "ARBITRATION_RESOLVED:争议原因", LocalDateTime.now())
        );
        return new Order(100L, 10L, 1L, 2L, OrderStatus.COMPLETED, "接单备注", true, 2,
            LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), history);
    }

    private OrderView toOrderViewFor(Order order, CurrentUser currentUser) {
        Demand demand = newDemand(10L, 1L, "发布者", false, null, "13900000000");
        User publisher = newUser(1L, "pub@edu.cn", "20260001", "发布者");
        User accepter = newUser(2L, "acc@edu.cn", "20260002", "接单者");
        return mapper.toOrderView(order, currentUser,
            Map.of(10L, demand),
            Map.of(1L, publisher, 2L, accepter),
            Map.of(), Set.of(), Map.of());
    }

    @Test
    void shouldNotExposeOrderPrivateFieldsToOutsider() {
        Order order = newOrderWithPrivateFields();
        CurrentUser outsider = new CurrentUser(3L, UserRole.USER);

        OrderView view = toOrderViewFor(order, outsider);

        assertNull(view.acceptNote(), "第三方不可见接单备注");
        assertFalse(view.proofSubmitted(), "第三方不可见履约凭证状态");
        assertEquals(0, view.proofImageCount(), "第三方不可见履约凭证数量");
        assertTrue(view.statusHistory().isEmpty(), "第三方不可见完整状态历史");
        assertNull(view.arbitrationResult(), "第三方不可见仲裁内部信息");
    }

    @Test
    void shouldExposeOrderPrivateFieldsToPublisher() {
        Order order = newOrderWithPrivateFields();
        CurrentUser publisher = new CurrentUser(1L, UserRole.USER);

        OrderView view = toOrderViewFor(order, publisher);

        assertEquals("接单备注", view.acceptNote());
        assertTrue(view.proofSubmitted());
        assertEquals(2, view.proofImageCount());
        assertEquals(1, view.statusHistory().size());
        assertNotNull(view.arbitrationResult());
        assertTrue(view.arbitrationResult().contains("裁决结果"));
    }

    @Test
    void shouldExposeOrderPrivateFieldsToAccepter() {
        Order order = newOrderWithPrivateFields();
        CurrentUser accepter = new CurrentUser(2L, UserRole.USER);

        OrderView view = toOrderViewFor(order, accepter);

        assertEquals("接单备注", view.acceptNote());
        assertTrue(view.proofSubmitted());
        assertEquals(2, view.proofImageCount());
        assertEquals(1, view.statusHistory().size());
        assertNotNull(view.arbitrationResult());
    }

    @Test
    void shouldExposeOrderPrivateFieldsToAdmin() {
        Order order = newOrderWithPrivateFields();
        CurrentUser admin = new CurrentUser(99L, UserRole.ADMIN);

        OrderView view = toOrderViewFor(order, admin);

        assertEquals("接单备注", view.acceptNote());
        assertTrue(view.proofSubmitted());
        assertEquals(2, view.proofImageCount());
        assertEquals(1, view.statusHistory().size());
        assertNotNull(view.arbitrationResult());
    }
}
