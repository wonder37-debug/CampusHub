package com.campushub.backend.recommendation.service;

import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.RecommendationItem;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.dto.RecommendationItemResponse;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RecommendationApplicationServiceImpl implements RecommendationApplicationService {

    private static final int MAX_RECOMMEND_SIZE = 50;
    private static final int DIVERSITY_WINDOW = 5;
    private static final int MAX_SAME_CATEGORY_IN_TOP = 2;

    private final DemandRepository demandRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final UserActionLogRepository userActionLogRepository;
    private final RecommendationSwitch recommendationSwitch;

    public RecommendationApplicationServiceImpl(
        DemandRepository demandRepository,
        OrderRepository orderRepository,
        UserRepository userRepository,
        UserActionLogRepository userActionLogRepository,
        RecommendationSwitch recommendationSwitch
    ) {
        this.demandRepository = demandRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.userActionLogRepository = userActionLogRepository;
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

        Map<String, Double> preference = buildUserPreference(userId);
        boolean hasPreference = !preference.isEmpty();
        boolean enabled = recommendationSwitch.enabled();
        BigDecimal maxReward = filteredDemands.stream()
            .map(Demand::getReward)
            .filter(r -> r != null)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ONE);
        RecentViews recentViews = buildRecentViews(userId);

        Comparator<Demand> comparator = resolveComparator(enabled, hasPreference, preference, recentViews, maxReward);
        List<RecommendationItem> ranked = new ArrayList<>();
        for (Demand demand : filteredDemands.stream().sorted(comparator).toList()) {
            double baseScore = enabled ? scoreDemand(demand, preference, maxReward) : 0.0;
            double score = enabled ? applyViewPenalty(baseScore, demand.getId(), recentViews) : 0.0;
            List<String> reasonTags = enabled ? buildReasonTags(demand, preference, maxReward, recentViews) : List.of("默认排序");
            ranked.add(new RecommendationItem(demand, score, ranked.size() + 1, reasonTags));
        }

        // ponytail: RecommendationSwitch 关闭时保持 createdAt DESC 默认排序语义，
        // 不执行 diversity rerank、不应用 view penalty、不生成偏好排序（见上方 enabled 三元）。
        if (enabled) {
            ranked = applyDiversityRerank(ranked);
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
        List<Demand> candidates = demandRepository.findCandidatePage(userId, query);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> demandIdsWithOrder = orderRepository.findDemandIdsWithOrder(
            candidates.stream().map(Demand::getId).toList());
        return candidates.stream()
            .filter(demand -> !demandIdsWithOrder.contains(demand.getId()))
            .toList();
    }

    // ponytail: 用户偏好直接基于 rec_user_action_log 聚合，不引入额外存储。
    // preference(category) = Σ(actionWeight × timeDecay)，归一化到 0~1。
    // VIEW=1.0, ACCEPT=3.0；>14 天的行为 decay=0 不再影响偏好。
    private Map<String, Double> buildUserPreference(Long userId) {
        List<UserActionLog> logs = userActionLogRepository.findByUserId(userId);
        if (logs.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Double> raw = new HashMap<>();
        for (UserActionLog log : logs) {
            if (log.getCategory() == null || log.getActionType() == null || log.getCreatedAt() == null) {
                continue;
            }
            double weight = log.getActionType() == ActionType.ACCEPT ? 3.0 : 1.0;
            double decay = computeTimeDecay(log.getCreatedAt(), now);
            if (decay <= 0.0) {
                continue;
            }
            raw.merge(log.getCategory().name(), weight * decay, Double::sum);
        }
        if (raw.isEmpty()) {
            return Map.of();
        }
        double max = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (max <= 0.0) {
            return Map.of();
        }
        Map<String, Double> normalized = new HashMap<>();
        for (var entry : raw.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / max);
        }
        return normalized;
    }

    private double computeTimeDecay(LocalDateTime actionTime, LocalDateTime now) {
        if (actionTime == null) {
            return 0.0;
        }
        long days = ChronoUnit.DAYS.between(actionTime, now);
        if (days <= 0) {
            return 1.0;
        }
        if (days < 3) {
            return 0.8;
        }
        if (days < 7) {
            return 0.5;
        }
        if (days < 14) {
            return 0.2;
        }
        return 0.0;
    }

    private record RecentViews(Set<Long> within24h, Set<Long> within7d) {
    }

    private RecentViews buildRecentViews(Long userId) {
        List<UserActionLog> views = userActionLogRepository.findByUserIdAndActionType(userId, ActionType.VIEW);
        if (views.isEmpty()) {
            return new RecentViews(Set.of(), Set.of());
        }
        LocalDateTime now = LocalDateTime.now();
        Set<Long> within24h = new HashSet<>();
        Set<Long> within7d = new HashSet<>();
        for (UserActionLog view : views) {
            if (view.getCreatedAt() == null || view.getDemandId() == null) {
                continue;
            }
            long hours = ChronoUnit.HOURS.between(view.getCreatedAt(), now);
            if (hours >= 0 && hours < 24) {
                within24h.add(view.getDemandId());
            }
            long days = ChronoUnit.DAYS.between(view.getCreatedAt(), now);
            if (days >= 0 && days < 7) {
                within7d.add(view.getDemandId());
            }
        }
        return new RecentViews(within24h, within7d);
    }

    private double applyViewPenalty(double score, Long demandId, RecentViews recentViews) {
        if (recentViews == null || demandId == null) {
            return score;
        }
        if (recentViews.within24h().contains(demandId)) {
            return score * 0.80;
        }
        if (recentViews.within7d().contains(demandId)) {
            return score * 0.90;
        }
        return score;
    }

    // ponytail: 轻量 diversity rerank——Top 5 同 category 最多 2 个，
    // 超出者按原顺序推迟到第 5 位之后，不做 MMR/学习重排。
    private List<RecommendationItem> applyDiversityRerank(List<RecommendationItem> ranked) {
        if (ranked.size() <= 1) {
            return ranked;
        }
        List<RecommendationItem> result = new ArrayList<>(ranked.size());
        List<RecommendationItem> deferred = new ArrayList<>();
        Map<String, Integer> categoryCount = new HashMap<>();
        for (RecommendationItem item : ranked) {
            String category = item.demand().getCategory() == null ? "UNKNOWN" : item.demand().getCategory().name();
            if (result.size() < DIVERSITY_WINDOW) {
                int count = categoryCount.getOrDefault(category, 0);
                if (count < MAX_SAME_CATEGORY_IN_TOP) {
                    result.add(item);
                    categoryCount.merge(category, 1, Integer::sum);
                } else {
                    deferred.add(item);
                }
            } else {
                deferred.add(item);
            }
        }
        result.addAll(deferred);
        if (result.size() == ranked.size()) {
            boolean orderChanged = false;
            for (int i = 0; i < ranked.size(); i++) {
                if (!ranked.get(i).demand().getId().equals(result.get(i).demand().getId())) {
                    orderChanged = true;
                    break;
                }
            }
            if (!orderChanged) {
                return ranked;
            }
        }
        List<RecommendationItem> finalList = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            RecommendationItem item = result.get(i);
            finalList.add(new RecommendationItem(item.demand(), item.score(), i + 1, item.reasonTags()));
        }
        return finalList;
    }

    private Comparator<Demand> resolveComparator(
        boolean enabled, boolean hasPreference, Map<String, Double> preference,
        RecentViews recentViews, BigDecimal maxReward
    ) {
        if (!enabled) {
            return Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
        }
        if (!hasPreference) {
            return Comparator
                .comparingDouble((Demand demand) -> applyViewPenalty(scoreColdStart(demand, maxReward), demand.getId(), recentViews))
                .reversed()
                .thenComparing(Demand::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return Comparator
            .comparingDouble((Demand demand) -> applyViewPenalty(scoreDemand(demand, preference, maxReward), demand.getId(), recentViews))
            .reversed()
            .thenComparing(Demand::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private double scoreDemand(Demand demand, Map<String, Double> preference, BigDecimal maxReward) {
        double preferenceScore = preference.getOrDefault(
            demand.getCategory() == null ? null : demand.getCategory().name(), 0.0);
        double rewardScore = computeRewardScore(demand.getReward(), maxReward);
        double urgencyScore = computeUrgencyScore(demand.getEndTime());
        double freshnessScore = computeFreshnessScore(demand.getCreatedAt());
        return clamp01(0.50 * preferenceScore + 0.20 * rewardScore + 0.15 * urgencyScore + 0.15 * freshnessScore);
    }

    private double scoreColdStart(Demand demand, BigDecimal maxReward) {
        double rewardScore = computeRewardScore(demand.getReward(), maxReward);
        double urgencyScore = computeUrgencyScore(demand.getEndTime());
        double freshnessScore = computeFreshnessScore(demand.getCreatedAt());
        return clamp01(0.40 * rewardScore + 0.30 * urgencyScore + 0.30 * freshnessScore);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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

    private List<String> buildReasonTags(
        Demand demand, Map<String, Double> preference, BigDecimal maxReward, RecentViews recentViews
    ) {
        List<String> tags = new ArrayList<>();
        double pref = preference.getOrDefault(
            demand.getCategory() == null ? null : demand.getCategory().name(), 0.0);
        if (pref > 0.0) {
            tags.add("同分类");
            tags.add("历史行为偏好");
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
        if (recentViews != null && demand.getId() != null) {
            if (recentViews.within24h().contains(demand.getId()) || recentViews.within7d().contains(demand.getId())) {
                tags.add("近期已浏览");
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
}
