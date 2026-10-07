package com.campushub.backend.demand.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.repository.DemandRepository;
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
class DemandExpirationSchedulerTest {

    @Autowired
    private DemandRepository demandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DemandExpirationScheduler scheduler;

    private Long publisherId;

    @BeforeEach
    void setUp() {
        publisherId = createUser("pub@example.edu.cn", "20260001", "发布者");
    }

    private Long createUser(String email, String studentId, String nickname) {
        return userRepository.save(new User(
            null, email, studentId, "hash", nickname, null,
            UserRole.USER, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO,
            LocalDateTime.now(), LocalDateTime.now()
        )).getId();
    }

    private Long createPendingDemand(LocalDateTime endTime, BigDecimal reward, BigDecimal frozenBalance) {
        return createPendingDemandFor(publisherId, endTime, reward, frozenBalance);
    }

    private Long createPendingDemandFor(Long pubId, LocalDateTime endTime, BigDecimal reward, BigDecimal frozenBalance) {
        User user = userRepository.findById(pubId).orElseThrow();
        user.setFrozenBalance(frozenBalance);
        userRepository.save(user);
        Demand demand = new Demand(
            null, pubId, "发布者", "过期测试需求", "描述", null,
            DemandCategory.EXPRESS, CampusZone.XIANLIN, "仙林",
            LocalDateTime.now().minusHours(2), endTime, reward,
            InteractionMode.DIRECT_ACCEPT, null, List.of(), List.of(), null,
            DemandStatus.PENDING, true, false, null, null, null, null,
            LocalDateTime.now(), LocalDateTime.now()
        );
        return demandRepository.save(demand).getId();
    }

    private Demand reload(Long demandId) {
        return demandRepository.findById(demandId).orElseThrow();
    }

    private BigDecimal frozenBalance() {
        return frozenBalanceOf(publisherId);
    }

    private BigDecimal frozenBalanceOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getFrozenBalance();
    }

    private BigDecimal balance() {
        return userRepository.findById(publisherId).orElseThrow().getBalance();
    }

    @Test
    void shouldExpirePendingDemandAndRefundFrozenReward() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void shouldNotDoubleRefundWhenExpiredDemandProcessedTwice() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();
        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO));

        scheduler.expireOverdueDemands();
        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO), "重复执行不能重复解冻");
    }

    @Test
    void shouldSkipDemandAlreadyAcceptedByOther() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));
        Demand demand = reload(demandId);
        demand.setStatus(DemandStatus.IN_PROGRESS);
        demandRepository.save(demand);

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.IN_PROGRESS, reload(demandId).getStatus(), "已接单的需求不能被过期覆盖");
        assertEquals(0, frozenBalance().compareTo(new BigDecimal("10.00")), "已接单的需求冻结金不应被解冻");
    }

    @Test
    void shouldSkipDemandNotYetOverdue() {
        Long demandId = createPendingDemand(LocalDateTime.now().plusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.PENDING, reload(demandId).getStatus());
        assertEquals(0, frozenBalance().compareTo(new BigDecimal("10.00")));
    }

    @Test
    void shouldExpireZeroRewardDemandWithoutRefund() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), BigDecimal.ZERO, BigDecimal.ZERO);

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void shouldRollbackSingleDemandWhenRefundFailsWithoutThrowing() {
        // reward=10 但 frozenBalance=0，refundToPublisher 抛 BusinessException，当前 Demand 独立事务回滚，不抛异常
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), BigDecimal.ZERO);

        scheduler.expireOverdueDemands(); // 不抛异常，记录错误后继续

        assertEquals(DemandStatus.PENDING, reload(demandId).getStatus(), "解冻失败时状态必须回滚为 PENDING");
        assertEquals(0, frozenBalance().compareTo(BigDecimal.ZERO), "未错误扣款");
    }

    @Test
    void shouldNotBlockOtherDemandsWhenOneFails() {
        Long pubA = createUser("a@edu.cn", "20260010", "A");
        Long pubB = createUser("b@edu.cn", "20260011", "B");
        Long pubC = createUser("c@edu.cn", "20260012", "C");

        Long demandA = createPendingDemandFor(pubA, LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));
        Long demandB = createPendingDemandFor(pubB, LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), BigDecimal.ZERO);
        Long demandC = createPendingDemandFor(pubC, LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.EXPIRED, reload(demandA).getStatus(), "A 正常过期");
        assertEquals(DemandStatus.PENDING, reload(demandB).getStatus(), "B 解冻失败回滚为 PENDING");
        assertEquals(DemandStatus.EXPIRED, reload(demandC).getStatus(), "C 不被 B 阻塞，正常过期");
        assertEquals(0, frozenBalanceOf(pubA).compareTo(BigDecimal.ZERO), "A 已解冻");
        assertEquals(0, frozenBalanceOf(pubB).compareTo(BigDecimal.ZERO), "B 未错误扣款");
        assertEquals(0, frozenBalanceOf(pubC).compareTo(BigDecimal.ZERO), "C 已解冻");
    }
}
