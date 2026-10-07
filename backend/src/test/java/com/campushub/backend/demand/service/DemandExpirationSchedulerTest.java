package com.campushub.backend.demand.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
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
        User user = new User(
            null,
            "pub@example.edu.cn",
            "20260001",
            "hash",
            "发布者",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            new BigDecimal("0.00"),
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        publisherId = userRepository.save(user).getId();
    }

    private Long createPendingDemand(LocalDateTime endTime, BigDecimal reward, BigDecimal frozenBalance) {
        User user = userRepository.findById(publisherId).orElseThrow();
        user.setFrozenBalance(frozenBalance);
        userRepository.save(user);
        Demand demand = new Demand(
            null, publisherId, "发布者", "过期测试需求", "描述", null,
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
        return userRepository.findById(publisherId).orElseThrow().getFrozenBalance();
    }

    private BigDecimal balance() {
        return userRepository.findById(publisherId).orElseThrow().getBalance();
    }

    @Test
    void shouldExpirePendingDemandAndRefundFrozenReward() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(new BigDecimal("0.00"), frozenBalance());
        assertEquals(new BigDecimal("100.00"), balance());
    }

    @Test
    void shouldNotDoubleRefundWhenExpiredDemandProcessedTwice() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();
        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(new BigDecimal("0.00"), frozenBalance());

        scheduler.expireOverdueDemands();
        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(new BigDecimal("0.00"), frozenBalance(), "重复执行不能重复解冻");
    }

    @Test
    void shouldSkipDemandAlreadyAcceptedByOther() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));
        // 模拟 accept 已把状态改为 IN_PROGRESS（持锁路径已提交）
        Demand demand = reload(demandId);
        demand.setStatus(DemandStatus.IN_PROGRESS);
        demandRepository.save(demand);

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.IN_PROGRESS, reload(demandId).getStatus(), "已接单的需求不能被过期覆盖");
        assertEquals(new BigDecimal("10.00"), frozenBalance(), "已接单的需求冻结金不应被解冻");
    }

    @Test
    void shouldSkipDemandNotYetOverdue() {
        Long demandId = createPendingDemand(LocalDateTime.now().plusHours(1), new BigDecimal("10.00"), new BigDecimal("10.00"));

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.PENDING, reload(demandId).getStatus());
        assertEquals(new BigDecimal("10.00"), frozenBalance());
    }

    @Test
    void shouldExpireZeroRewardDemandWithoutRefund() {
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), BigDecimal.ZERO, BigDecimal.ZERO);

        scheduler.expireOverdueDemands();

        assertEquals(DemandStatus.EXPIRED, reload(demandId).getStatus());
        assertEquals(new BigDecimal("0.00"), frozenBalance());
    }

    @Test
    void shouldRollbackWhenRefundFails() {
        // reward=10 但 frozenBalance=0，refundToPublisher 会抛 BusinessException，整个事务回滚
        Long demandId = createPendingDemand(LocalDateTime.now().minusHours(1), new BigDecimal("10.00"), BigDecimal.ZERO);

        assertThrows(BusinessException.class, scheduler::expireOverdueDemands);

        assertEquals(DemandStatus.PENDING, reload(demandId).getStatus(), "解冻失败时状态必须回滚为 PENDING");
    }
}
