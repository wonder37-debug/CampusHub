package com.campushub.backend.recommendation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.BackendApplication;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.notification.repository.NotificationRepository;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.dto.RecommendationItemResponse;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
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
class RecommendationApplicationServiceImplTest {

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
    private RecommendationApplicationService recommendationApplicationService;

    @Autowired
    private UserActionLogRepository userActionLogRepository;

    private Long publisherId;
    private Long accepterId;

    @BeforeEach
    void setUp() {
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
    void shouldRankPreferredCategoryFirst() {
        DemandDetailResponse historyDemand = createDemand("历史快递", "EXPRESS");
        orderApplicationService.accept(accepterId, historyDemand.id(), new AcceptOrderCommand("接过快递"));

        DemandDetailResponse expressCandidate = createDemand("推荐快递", "EXPRESS");
        DemandDetailResponse studyCandidate = createDemand("学习辅导", "STUDY_TUTORING");

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
        assertFalse(page.items().stream().anyMatch(item -> historyDemand.id().equals(item.demandId())));
        assertEquals(expressCandidate.id(), page.items().get(0).demandId());
        assertTrue(page.items().get(0).reasonTags().contains("同分类"));
        assertEquals(studyCandidate.id(), page.items().get(1).demandId());
    }

    @Test
    void shouldFallbackToTimeOrderWhenNoHistory() {
        DemandDetailResponse older = createDemand("旧需求", "OTHER");
        DemandDetailResponse newer = createDemand("新需求", "SECOND_HAND");

        demandRepository.findById(older.id()).ifPresent(demand -> {
            demand.setCreatedAt(LocalDateTime.now().minusDays(1));
            demandRepository.save(demand);
        });
        demandRepository.findById(newer.id()).ifPresent(demand -> {
            demand.setCreatedAt(LocalDateTime.now());
            demandRepository.save(demand);
        });

        PageResponse<DemandSummaryResponse> page = recommendationApplicationService.recommendDemandList(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(newer.id(), page.items().get(0).id());
        assertEquals(older.id(), page.items().get(1).id());
    }

    @Test
    void shouldRespectRecommendationSwitchOff() {
        recommendationApplicationService = new RecommendationApplicationServiceImpl(
            demandRepository,
            orderRepository,
            userRepository,
            userActionLogRepository,
            () -> false
        );
        DemandDetailResponse first = createDemand("第一条", "EXPRESS");
        DemandDetailResponse second = createDemand("第二条", "SECOND_HAND");

        demandRepository.findById(first.id()).ifPresent(demand -> {
            demand.setCreatedAt(LocalDateTime.now().minusHours(2));
            demandRepository.save(demand);
        });
        demandRepository.findById(second.id()).ifPresent(demand -> {
            demand.setCreatedAt(LocalDateTime.now());
            demandRepository.save(demand);
        });

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(second.id(), page.items().get(0).demandId());
        assertEquals(0.0, page.items().get(0).score());
        assertTrue(page.items().get(0).reasonTags().contains("默认排序"));
    }

    @Test
    void shouldRankByViewPreference() {
        DemandDetailResponse express = createDemand("推荐快递", "EXPRESS");
        DemandDetailResponse study = createDemand("学习辅导", "STUDY_TUTORING");
        // 8 天前 VIEW：decay=0.2 仍计入偏好，且 >7d 不触发浏览抑制
        saveActionLog(accepterId, express.id(), DemandCategory.EXPRESS, ActionType.VIEW, LocalDateTime.now().minusDays(8));

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(express.id(), page.items().get(0).demandId());
        assertTrue(page.items().get(0).reasonTags().contains("同分类"));
    }

    @Test
    void shouldWeighAcceptHigherThanView() {
        DemandDetailResponse express = createDemand("快递需求", "EXPRESS");
        DemandDetailResponse study = createDemand("学习需求", "STUDY_TUTORING");
        saveActionLog(accepterId, express.id(), DemandCategory.EXPRESS, ActionType.VIEW, LocalDateTime.now().minusDays(1));
        saveActionLog(accepterId, study.id(), DemandCategory.STUDY_TUTORING, ActionType.ACCEPT, LocalDateTime.now().minusDays(1));

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(study.id(), page.items().get(0).demandId());
    }

    @Test
    void shouldWeighRecentActionHigherThanOld() {
        DemandDetailResponse express = createDemand("旧快递", "EXPRESS");
        DemandDetailResponse study = createDemand("新学习", "STUDY_TUTORING");
        saveActionLog(accepterId, express.id(), DemandCategory.EXPRESS, ActionType.VIEW, LocalDateTime.now().minusDays(8));
        saveActionLog(accepterId, study.id(), DemandCategory.STUDY_TUTORING, ActionType.VIEW, LocalDateTime.now().minusDays(1));

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(study.id(), page.items().get(0).demandId());
    }

    @Test
    void shouldNotPreferCategoryFromActionOlderThan14Days() {
        DemandDetailResponse express = createDemand("快递需求", "EXPRESS");
        DemandDetailResponse study = createDemand("学习需求", "STUDY_TUTORING");
        // express 故意创建更早，使 cold-start 下 study 的 freshness 更高
        setCreatedAt(express.id(), LocalDateTime.now().minusDays(2));
        // 15 天前 VIEW：decay=0，偏好为空，走 cold-start
        saveActionLog(accepterId, express.id(), DemandCategory.EXPRESS, ActionType.VIEW, LocalDateTime.now().minusDays(15));

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        // preference 空走 cold-start → study freshness 更高排第一
        assertEquals(study.id(), page.items().get(0).demandId());
    }

    @Test
    void shouldPenalizeRecentlyViewedDemand() {
        DemandDetailResponse viewed = createDemand("看过的需求", "EXPRESS");
        DemandDetailResponse fresh = createDemand("新鲜的需求", "EXPRESS");
        // 12 小时前 VIEW：24h 内，惩罚 ×0.80
        saveActionLog(accepterId, viewed.id(), DemandCategory.EXPRESS, ActionType.VIEW, LocalDateTime.now().minusHours(12));

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 20))
        );

        assertEquals(fresh.id(), page.items().get(0).demandId());
        assertEquals(viewed.id(), page.items().get(1).demandId());
        assertTrue(page.items().get(1).reasonTags().contains("近期已浏览"));
    }

    @Test
    void shouldLimitSameCategoryInTop5ViaDiversityRerank() {
        DemandDetailResponse e1 = createDemandWithReward("快递1", "EXPRESS", new BigDecimal("10"));
        DemandDetailResponse e2 = createDemandWithReward("快递2", "EXPRESS", new BigDecimal("10"));
        DemandDetailResponse e3 = createDemandWithReward("快递3", "EXPRESS", new BigDecimal("10"));
        DemandDetailResponse study = createDemandWithReward("学习任务", "STUDY_TUTORING", BigDecimal.ONE);
        DemandDetailResponse second = createDemandWithReward("二手物品", "SECOND_HAND", BigDecimal.ONE);
        DemandDetailResponse other = createDemandWithReward("其他任务", "OTHER", BigDecimal.ONE);

        PageResponse<RecommendationItemResponse> page = recommendationApplicationService.recommend(
            accepterId,
            new DemandQuery(null, null, null, null, null, null, null, new PageQuery(1, 5))
        );

        assertEquals(5, page.items().size());
        long expressCount = page.items().stream()
            .filter(item -> demandRepository.findById(item.demandId())
                .map(d -> DemandCategory.EXPRESS == d.getCategory())
                .orElse(false))
            .count();
        assertTrue(expressCount <= 2, "Top 5 中 EXPRESS 不应超过 2 个，实际: " + expressCount);
    }

    private void saveActionLog(Long userId, Long demandId, DemandCategory category, ActionType actionType, LocalDateTime createdAt) {
        UserActionLog log = new UserActionLog();
        log.setUserId(userId);
        log.setDemandId(demandId);
        log.setCategory(category);
        log.setActionType(actionType);
        log.setCreatedAt(createdAt);
        userActionLogRepository.save(log);
    }

    private void setCreatedAt(Long demandId, LocalDateTime createdAt) {
        demandRepository.findById(demandId).ifPresent(demand -> {
            demand.setCreatedAt(createdAt);
            demandRepository.save(demand);
        });
    }

    private DemandDetailResponse createDemandWithReward(String title, String category, BigDecimal reward) {
        DemandDetailResponse demand = demandApplicationService.publish(
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
                reward,
                List.of("tag"),
                null,
                null,
                false,
                null,
                null
            )
        );
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        return demandApplicationService.getDetail(demand.id());
    }

    private DemandDetailResponse createDemand(String title, String category) {
        DemandDetailResponse demand = demandApplicationService.publish(
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
        demandRepository.findById(demand.id()).ifPresent(saved -> {
            saved.setStatus(DemandStatus.PENDING);
            demandRepository.save(saved);
        });
        return demandApplicationService.getDetail(demand.id());
    }
}
