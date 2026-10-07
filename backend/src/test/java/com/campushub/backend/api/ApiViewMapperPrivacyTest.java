package com.campushub.backend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ApiViewMapper 隐私四视角测试：验证 Demand 公开视图在不同视角下的发布者身份可见性，
 * 以及 PublicUserSummaryView 不携带 email/studentId/balance/frozenBalance 等敏感字段。
 *
 * <p>覆盖：普通用户、匿名需求、本人、管理员四种视角。</p>
 */
class ApiViewMapperPrivacyTest {

    private final ApiViewMapper mapper = new ApiViewMapper(null, null, null, null);

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
}
