package com.campushub.backend.recommendation.service;

import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.recommendation.domain.RecommendationItem;
import com.campushub.backend.recommendation.dto.RecommendationItemResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class RecommendationApplicationServiceImpl implements RecommendationApplicationService {

    private static final int MAX_RECOMMEND_SIZE = 50;

    private final DemandRepository demandRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final RecommendationSwitch recommendationSwitch;

    public RecommendationApplicationServiceImpl(
        DemandRepository demandRepository,
        OrderRepository orderRepository,
        UserRepository userRepository,
        RecommendationSwitch recommendationSwitch
    ) {
        this.demandRepository = demandRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.recommendationSwitch = recommendationSwitch;
    }

    @Override
    public PageResponse<RecommendationItemResponse> recommend(Long userId, DemandQuery query) {
        validateUser(userId);
        DemandQuery normalizedQuery = normalizeQuery(query);
        RankedRecommendationPage rankedPage = buildRankedPage(userId, normalizedQuery);
        List<RecommendationItemResponse> items = rankedPage.items().stream()
            .map(RecommendationItemResponse::from)
            .toList();
        return new PageResponse<>(
            items,
            normalizedQuery.pageQuery().page(),
            normalizedQuery.pageQuery().size(),
            rankedPage.total()
        );
    }

    @Override
    public PageResponse<DemandSummaryResponse> recommendDemandList(Long userId, DemandQuery query) {
        validateUser(userId);
        DemandQuery normalizedQuery = normalizeQuery(query);
        RankedRecommendationPage rankedPage = buildRankedPage(userId, normalizedQuery);
        List<DemandSummaryResponse> items = rankedPage.items().stream()
            .map(item -> DemandSummaryResponse.from(item.demand()))
            .toList();
        return new PageResponse<>(
            items,
            normalizedQuery.pageQuery().page(),
            normalizedQuery.pageQuery().size(),
            rankedPage.total()
        );
    }

    private DemandQuery normalizeQuery(DemandQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "recommendation query must not be null");
        }
        if (query.pageQuery().size() > MAX_RECOMMEND_SIZE) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "recommendation size must be between 1 and " + MAX_RECOMMEND_SIZE
            );
        }
        return new DemandQuery(
            query.q(),
            query.category(),
            query.campusZone(),
            query.location(),
            query.startTimeFrom(),
            query.startTimeTo(),
            query.sort() == null ? DemandSort.RECOMMEND : query.sort(),
            query.pageQuery()
        );
    }

    private RankedRecommendationPage buildRankedPage(Long userId, DemandQuery query) {
        List<Demand> filteredDemands = filterCandidateDemands(userId, query);
        if (filteredDemands.isEmpty()) {
            return new RankedRecommendationPage(List.of(), 0);
        }

        Map<String, Long> acceptedCategoryStats = buildAcceptedCategoryStats(userId);
        boolean enabled = recommendationSwitch.enabled();
        BigDecimal maxReward = filteredDemands.stream()
            .map(Demand::getReward)
            .filter(r -> r != null)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ONE);

        List<RecommendationItem> ranked = new ArrayList<>();
        int rank = 1;
        for (Demand demand : filteredDemands.stream().sorted(resolveComparator(enabled, acceptedCategoryStats, maxReward)).toList()) {
            double score = enabled ? scoreDemand(demand, acceptedCategoryStats, maxReward) : 0.0;
            List<String> reasonTags = enabled ? buildReasonTags(demand, acceptedCategoryStats, maxReward) : List.of("默认排序");
            ranked.add(new RecommendationItem(demand, score, rank++, reasonTags));
        }

        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(ranked.size(), fromIndex + size);
        List<RecommendationItem> items = fromIndex >= ranked.size()
            ? List.of()
            : ranked.subList(fromIndex, toIndex);
        return new RankedRecommendationPage(items, ranked.size());
    }

    private record RankedRecommendationPage(List<RecommendationItem> items, long total) {
    }

    private List<Demand> filterCandidateDemands(Long userId, DemandQuery query) {
        return demandRepository.findAll().stream()
            .filter(demand -> demand.getStatus() == DemandStatus.PENDING)
            .filter(demand -> orderRepository.findByDemandId(demand.getId()).isEmpty())
            .filter(demand -> !demand.getPublisherId().equals(userId))
            .filter(demand -> matchesKeyword(demand, query.q()))
            .filter(demand -> matchesCategory(demand, query.category()))
            .filter(demand -> matchesCampusZone(demand, query.campusZone()))
            .filter(demand -> matchesLocation(demand, query.location()))
            .filter(demand -> matchesStartTimeRange(demand, query.startTimeFrom(), query.startTimeTo()))
            .toList();
    }

    private Map<String, Long> buildAcceptedCategoryStats(Long userId) {
        Map<String, Long> stats = new HashMap<>();
        for (Order order : orderRepository.findByParticipant(userId)) {
            if (!userId.equals(order.getAccepterId())) {
                continue;
            }
            demandRepository.findById(order.getDemandId()).ifPresent(demand ->
                stats.merge(demand.getCategory().name(), 1L, Long::sum)
            );
        }
        return stats;
    }

    private Comparator<Demand> resolveComparator(boolean enabled, Map<String, Long> acceptedCategoryStats, BigDecimal maxReward) {
        if (!enabled) {
            return Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
        }
        if (acceptedCategoryStats.isEmpty()) {
            return Comparator
                .comparingDouble((Demand demand) -> scoreColdStart(demand, maxReward))
                .reversed()
                .thenComparing(Demand::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return Comparator
            .comparingDouble((Demand demand) -> scoreDemand(demand, acceptedCategoryStats, maxReward))
            .reversed()
            .thenComparing(Demand::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private double scoreDemand(Demand demand, Map<String, Long> acceptedCategoryStats, BigDecimal maxReward) {
        long totalAccepted = acceptedCategoryStats.values().stream().mapToLong(Long::longValue).sum();
        long categoryAccepted = acceptedCategoryStats.getOrDefault(demand.getCategory().name(), 0L);
        double categoryScore = totalAccepted == 0 ? 0.0 : ((double) categoryAccepted / totalAccepted);
        double rewardScore = computeRewardScore(demand.getReward(), maxReward);
        double urgencyScore = computeUrgencyScore(demand.getEndTime());
        double freshnessScore = computeFreshnessScore(demand.getCreatedAt());
        return Math.min(1.0, 0.5 * categoryScore + 0.2 * rewardScore + 0.15 * urgencyScore + 0.15 * freshnessScore);
    }

    private double scoreColdStart(Demand demand, BigDecimal maxReward) {
        double rewardScore = computeRewardScore(demand.getReward(), maxReward);
        double urgencyScore = computeUrgencyScore(demand.getEndTime());
        double freshnessScore = computeFreshnessScore(demand.getCreatedAt());
        return Math.min(1.0, 0.4 * rewardScore + 0.3 * urgencyScore + 0.3 * freshnessScore);
    }

    private double computeRewardScore(BigDecimal reward, BigDecimal maxReward) {
        if (reward == null || maxReward == null || maxReward.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }
        return Math.min(1.0, reward.doubleValue() / maxReward.doubleValue());
    }

    private double computeUrgencyScore(LocalDateTime endTime) {
        if (endTime == null) {
            return 0.3;
        }
        long daysUntilExpiry = ChronoUnit.DAYS.between(LocalDateTime.now(), endTime);
        if (daysUntilExpiry < 0) {
            return 0.0;
        }
        if (daysUntilExpiry <= 1) {
            return 1.0;
        }
        if (daysUntilExpiry >= 7) {
            return 0.1;
        }
        return 1.0 - (daysUntilExpiry - 1.0) / 6.0;
    }

    private double computeFreshnessScore(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0.0;
        }
        long daysSinceCreated = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now());
        if (daysSinceCreated < 0) {
            daysSinceCreated = 0;
        }
        if (daysSinceCreated >= 14) {
            return 0.0;
        }
        return 1.0 - daysSinceCreated / 14.0;
    }

    private List<String> buildReasonTags(Demand demand, Map<String, Long> acceptedCategoryStats, BigDecimal maxReward) {
        List<String> tags = new ArrayList<>();
        if (acceptedCategoryStats.containsKey(demand.getCategory().name())) {
            tags.add("同分类");
            tags.add("历史接单偏好");
        }
        if (demand.getReward() != null && maxReward != null
            && maxReward.compareTo(BigDecimal.ZERO) > 0
            && demand.getReward().doubleValue() / maxReward.doubleValue() > 0.7) {
            tags.add("高报酬");
        }
        if (demand.getEndTime() != null) {
            long daysUntilExpiry = ChronoUnit.DAYS.between(LocalDateTime.now(), demand.getEndTime());
            if (daysUntilExpiry >= 0 && daysUntilExpiry <= 3) {
                tags.add("即将截止");
            }
        }
        if (demand.getCreatedAt() != null) {
            long daysSinceCreated = ChronoUnit.DAYS.between(demand.getCreatedAt(), LocalDateTime.now());
            if (daysSinceCreated <= 1) {
                tags.add("最新需求");
            }
        }
        if (tags.isEmpty()) {
            tags.add("默认排序");
        }
        return tags;
    }

    private void validateUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));
    }

    private boolean matchesKeyword(Demand demand, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return demand.getTitle().toLowerCase(Locale.ROOT).contains(normalized)
            || (demand.getDescription() != null
            && demand.getDescription().toLowerCase(Locale.ROOT).contains(normalized));
    }

    private boolean matchesCategory(Demand demand, String category) {
        return category == null || category.isBlank() || demand.getCategory().name().equalsIgnoreCase(category);
    }

    private boolean matchesCampusZone(Demand demand, String campusZone) {
        return campusZone == null || campusZone.isBlank() || demand.getCampusZone().name().equalsIgnoreCase(campusZone);
    }

    private boolean matchesLocation(Demand demand, String location) {
        return location == null || location.isBlank()
            || (demand.getLocation() != null
            && demand.getLocation().toLowerCase(Locale.ROOT).contains(location.trim().toLowerCase(Locale.ROOT)));
    }

    private boolean matchesStartTimeRange(Demand demand, LocalDateTime from, LocalDateTime to) {
        if (demand.getStartTime() == null) {
            return from == null && to == null;
        }
        boolean afterFrom = from == null || !demand.getStartTime().isBefore(from);
        boolean beforeTo = to == null || !demand.getStartTime().isAfter(to);
        return afterFrom && beforeTo;
    }
}
