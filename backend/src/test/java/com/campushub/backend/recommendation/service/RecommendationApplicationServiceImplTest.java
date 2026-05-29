package com.campushub.backend.recommendation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.InMemoryUserRepository;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandQuery;
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
import com.campushub.backend.order.repository.InMemoryOrderRepository;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.order.service.OrderApplicationServiceImpl;
import com.campushub.backend.recommendation.dto.RecommendationItemResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationApplicationServiceImplTest {

    private UserRepository userRepository;
    private DemandRepository demandRepository;
    private OrderRepository orderRepository;
    private DemandApplicationService demandApplicationService;
    private OrderApplicationService orderApplicationService;
    private RecommendationApplicationService recommendationApplicationService;
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
        recommendationApplicationService = new RecommendationApplicationServiceImpl(
            demandRepository,
            orderRepository,
            userRepository,
            () -> true
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
