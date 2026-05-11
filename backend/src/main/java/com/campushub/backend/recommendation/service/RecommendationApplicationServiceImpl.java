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
import java.time.LocalDateTime;
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

        List<RecommendationItem> ranked = new ArrayList<>();
        int rank = 1;
        for (Demand demand : filteredDemands.stream().sorted(resolveComparator(enabled, acceptedCategoryStats)).toList()) {
            double score = enabled ? scoreDemand(demand, acceptedCategoryStats) : 0.0;
            List<String> reasonTags = enabled ? buildReasonTags(demand, acceptedCategoryStats) : List.of("默认排序");
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

    private Comparator<Demand> resolveComparator(boolean enabled, Map<String, Long> acceptedCategoryStats) {
        if (!enabled || acceptedCategoryStats.isEmpty()) {
            return Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
        }
        return Comparator
            .comparingDouble((Demand demand) -> scoreDemand(demand, acceptedCategoryStats))
            .reversed()
            .thenComparing(
                Demand::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
            );
    }

    private double scoreDemand(Demand demand, Map<String, Long> acceptedCategoryStats) {
        if (acceptedCategoryStats.isEmpty()) {
            return 0.1;
        }
        long totalAccepted = acceptedCategoryStats.values().stream().mapToLong(Long::longValue).sum();
        long categoryAccepted = acceptedCategoryStats.getOrDefault(demand.getCategory().name(), 0L);
        double categoryScore = totalAccepted == 0 ? 0.0 : ((double) categoryAccepted / totalAccepted);
        double timeBoost = demand.getCreatedAt() == null ? 0.0 : 0.1;
        return Math.min(1.0, 0.8 * categoryScore + timeBoost);
    }

    private List<String> buildReasonTags(Demand demand, Map<String, Long> acceptedCategoryStats) {
        List<String> tags = new ArrayList<>();
        if (acceptedCategoryStats.containsKey(demand.getCategory().name())) {
            tags.add("同分类");
            tags.add("历史接单偏好");
        } else {
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
