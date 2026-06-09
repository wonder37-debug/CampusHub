package com.campushub.backend.demand.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.UpdateDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.InMemoryDemandRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemandApplicationServiceImplTest {

    private UserRepository userRepository;
    private DemandRepository demandRepository;
    private DemandApplicationService demandApplicationService;
    private Long publisherId;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        demandRepository = new InMemoryDemandRepository();
        demandApplicationService = new DemandApplicationServiceImpl(
            demandRepository,
            userRepository,
            new DefaultSensitiveWordChecker()
        );

        User user = new User(
            null,
            "zheng@example.edu.cn",
            "20260001",
            "hash",
            "郑嘉鸿",
            null,
            UserRole.USER,
            UserStatus.ACTIVE,
            100,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        publisherId = userRepository.save(user).getId();
    }

    @Test
    void shouldPublishDemandWithDefaultReviewingStatus() {
        DemandDetailResponse response = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮忙取快递",
                "今天下午帮我去菜鸟拿快递",
                "轻拿轻放",
                "EXPRESS",
                "XIANLIN",
                "仙林菜鸟驿站",
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(3),
                new BigDecimal("2.50"),
                List.of("快递", "跑腿"),
                true
            )
        );

        assertEquals("REVIEWING", response.status());
        assertEquals("轻拿轻放", response.note());
        assertTrue(response.anonymous());
        assertTrue(response.publisherDisplayName().startsWith("匿名校友"));
    }

    @Test
    void shouldRejectForbiddenWords() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.publish(
                publisherId,
                new PublishDemandCommand(
                    "求代课",
                    "帮我代课一节",
                    null,
                    "STUDY_TUTORING",
                    "GULOU",
                    "鼓楼教学楼",
                    null,
                    null,
                    BigDecimal.ONE,
                    List.of(),
                    false
                )
            )
        );

        assertEquals(ErrorCode.BUSINESS_CONFLICT, exception.getErrorCode());
    }

    @Test
    void shouldFilterDemandListByCategoryAndCampusZone() {
        DemandDetailResponse first = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "学习辅导",
                "线代答疑",
                null,
                "STUDY_TUTORING",
                "XIANLIN",
                "教学楼",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                false
            )
        );
        DemandDetailResponse second = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "二手书出售",
                "出售教材",
                null,
                "SECOND_HAND",
                "GULOU",
                "宿舍区",
                null,
                null,
                BigDecimal.TEN,
                List.of(),
                false
            )
        );
        makePending(first.id());
        makePending(second.id());

        PageResponse<DemandSummaryResponse> response = demandApplicationService.list(
            new DemandQuery(
                null,
                "STUDY_TUTORING",
                "XIANLIN",
                null,
                null,
                null,
                DemandSort.TIME,
                new PageQuery(1, 20)
            )
        );

        assertEquals(1, response.total());
        assertEquals("STUDY_TUTORING", response.items().get(0).category());
    }

    @Test
    void shouldIncludeOwnReviewingAndCancelledDemandsInList() {
        DemandDetailResponse reviewing = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "待审核跑腿",
                "等待管理员审核",
                null,
                "EXPRESS",
                "XIANLIN",
                "仙林校区",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                false
            )
        );
        DemandDetailResponse cancelled = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "审核未通过需求",
                "模拟审核驳回后的发布单",
                null,
                "OTHER",
                "GULOU",
                "鼓楼校区",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                false
            )
        );
        demandRepository.findById(cancelled.id()).ifPresent(demand -> {
            demand.setStatus(DemandStatus.CANCELLED);
            demandRepository.save(demand);
        });

        PageResponse<DemandSummaryResponse> publicPage = demandApplicationService.list(
            new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20))
        );
        PageResponse<DemandSummaryResponse> ownerPage = demandApplicationService.list(
            new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20), publisherId)
        );

        assertFalse(publicPage.items().stream().anyMatch(item -> item.id().equals(reviewing.id())));
        assertFalse(publicPage.items().stream().anyMatch(item -> item.id().equals(cancelled.id())));
        assertTrue(ownerPage.items().stream().anyMatch(item -> item.id().equals(reviewing.id()) && item.status().equals("REVIEWING")));
        assertTrue(ownerPage.items().stream().anyMatch(item -> item.id().equals(cancelled.id()) && item.status().equals("CANCELLED")));
    }

    @Test
    void shouldHidePublisherIdWhenAnonymous() {
        DemandDetailResponse published = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "匿名求助",
                "匿名发布一条需求",
                null,
                "OTHER",
                "SUZHOU",
                "苏州校区",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                true
            )
        );

        DemandDetailResponse detail = demandApplicationService.getDetail(published.id());

        assertNull(detail.publisherId());
        assertTrue(detail.publisherDisplayName().startsWith("匿名校友"));
    }

    @Test
    void shouldSupportErrandCategoryAndLegacyAlias() {
        DemandDetailResponse errandDemand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "代办校园卡",
                "帮忙代办一项业务",
                null,
                "ERRAND",
                "XIANLIN",
                "行政楼",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                false
            )
        );
        DemandDetailResponse legacyAliasDemand = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "顺路代办",
                "走流程代办",
                null,
                "RUN_ERRAND",
                "GULOU",
                "行政楼",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                false
            )
        );

        assertEquals("ERRAND", errandDemand.category());
        assertEquals("ERRAND", legacyAliasDemand.category());
    }

    @Test
    void shouldRejectUpdateByNonPublisher() {
        DemandDetailResponse published = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "帮忙组队",
                "找队友",
                null,
                "TEAM_UP",
                "XIANLIN",
                "操场",
                null,
                null,
                BigDecimal.ZERO,
                List.of(),
                false
            )
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.update(
                999L,
                published.id(),
                new UpdateDemandCommand("改标题", null, null, null, null, null, null, null, null, null, null)
            )
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    @Test
    void shouldUpdateDemandByPublisher() {
        DemandDetailResponse published = demandApplicationService.publish(
            publisherId,
            new PublishDemandCommand(
                "旧标题",
                "旧描述",
                "旧备注",
                "OTHER",
                "GULOU",
                "原地点",
                null,
                null,
                BigDecimal.ONE,
                List.of("a"),
                false
            )
        );

        DemandDetailResponse updated = demandApplicationService.update(
            publisherId,
            published.id(),
            new UpdateDemandCommand(
                "新标题",
                "新描述",
                "新备注",
                "SECOND_HAND",
                "XIANLIN",
                "新地点",
                null,
                null,
                new BigDecimal("9.99"),
                List.of("新标签"),
                true
            )
        );

        assertEquals("新标题", updated.title());
        assertEquals("新备注", updated.note());
        assertEquals("SECOND_HAND", updated.category());
        assertEquals("XIANLIN", updated.campusZone());
        assertTrue(updated.anonymous());
    }

    @Test
    void shouldRejectUpdateForCompletedDemand() {
        Demand demand = new Demand(
            null,
            publisherId,
            "郑嘉鸿",
            "已完成任务",
            "不可编辑",
            null,
            DemandCategory.OTHER,
            CampusZone.XIANLIN,
            "仙林",
            null,
            null,
            BigDecimal.ZERO,
            List.of(),
            DemandStatus.COMPLETED,
            true,
            false,
            null,
            null,
            null,
            null,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        Demand saved = demandRepository.save(demand);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> demandApplicationService.update(
                publisherId,
                saved.getId(),
                new UpdateDemandCommand("改不了", null, null, null, null, null, null, null, null, null, null)
            )
        );

        assertEquals(ErrorCode.PERMISSION_DENIED, exception.getErrorCode());
    }

    private void makePending(Long demandId) {
        demandRepository.findById(demandId).ifPresent(demand -> {
            demand.setStatus(DemandStatus.PENDING);
            demandRepository.save(demand);
        });
    }
}
