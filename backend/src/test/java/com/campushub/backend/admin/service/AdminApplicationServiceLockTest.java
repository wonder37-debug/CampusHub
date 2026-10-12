package com.campushub.backend.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.RewardSettlementService;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
import com.campushub.backend.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminApplicationServiceLockTest {

    @Test
    void reviewDemandShouldUseLockingQueryToPreventRaceWithExpirationScheduler() {
        UserRepository userRepository = mock(UserRepository.class);
        DemandRepository demandRepository = mock(DemandRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        NotificationApplicationService notificationApplicationService = mock(NotificationApplicationService.class);
        DemandApplicationService demandApplicationService = mock(DemandApplicationService.class);
        RewardSettlementService rewardSettlementService = mock(RewardSettlementService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UserActionLogRepository userActionLogRepository = mock(UserActionLogRepository.class);

        AdminApplicationServiceImpl service = new AdminApplicationServiceImpl(
            userRepository, demandRepository, orderRepository, notificationApplicationService,
            demandApplicationService, rewardSettlementService, reviewRepository, userActionLogRepository
        );

        Long adminId = 1L;
        Long demandId = 10L;
        Long publisherId = 2L;

        when(userRepository.findById(adminId)).thenReturn(Optional.of(new User(
            adminId, "admin@edu.cn", "ADMIN001", "hash", "管理员", null,
            UserRole.ADMIN, UserStatus.ACTIVE, 100,
            new BigDecimal("100.00"), BigDecimal.ZERO,
            LocalDateTime.now(), LocalDateTime.now()
        )));

        // 关键：endTime 未过期，使 reviewDemand 不被截止时间校验拒绝，从而走到 approve 写入分支。
        // 此分支正是并发竞争窗口：若改回非锁定 findById，会读到过期调度器并发修改前的旧快照并覆盖 EXPIRED；
        // 加锁查询（findByIdForUpdate）保证持锁期间读到最新状态、与调度器串行化。
        Demand reviewingDemand = new Demand(
            demandId, publisherId, "发布者", "审核竞争测试", "描述", null,
            DemandCategory.EXPRESS, CampusZone.XIANLIN, "仙林",
            LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), BigDecimal.ONE,
            InteractionMode.DIRECT_ACCEPT, null, List.of(), List.of(), null,
            DemandStatus.REVIEWING, true, false, null, null, null, null,
            LocalDateTime.now(), LocalDateTime.now()
        );
        when(demandRepository.findByIdForUpdate(demandId)).thenReturn(Optional.of(reviewingDemand));
        when(demandRepository.save(any(Demand.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(notificationApplicationService).notifyDemandApproved(any(), any());

        DemandDetailResponse result = service.reviewDemand(
            adminId, demandId, new AdminDemandReviewCommand("approve", "通过")
        );

        assertEquals(DemandStatus.PENDING.name(), result.status());
        verify(demandRepository).findByIdForUpdate(eq(demandId));
        verify(demandRepository, never()).findById(eq(demandId));
    }
}
