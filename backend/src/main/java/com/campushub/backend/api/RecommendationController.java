package com.campushub.backend.api;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.api.view.RecommendedDemandView;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.recommendation.dto.RecommendationItemResponse;
import com.campushub.backend.recommendation.service.RecommendationApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationApplicationService recommendationApplicationService;
    private final DemandRepository demandRepository;
    private final DemandResponseRepository demandResponseRepository;
    private final RequestUserExtractor requestUserExtractor;
    private final ApiViewMapper apiViewMapper;

    public RecommendationController(
        RecommendationApplicationService recommendationApplicationService,
        DemandRepository demandRepository,
        DemandResponseRepository demandResponseRepository,
        RequestUserExtractor requestUserExtractor,
        ApiViewMapper apiViewMapper
    ) {
        this.recommendationApplicationService = recommendationApplicationService;
        this.demandRepository = demandRepository;
        this.demandResponseRepository = demandResponseRepository;
        this.requestUserExtractor = requestUserExtractor;
        this.apiViewMapper = apiViewMapper;
    }

    @GetMapping
    public ApiResponse<PageResponse<RecommendedDemandView>> recommend(
        HttpServletRequest request,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String campusZone,
        @RequestParam(required = false) String location,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        PageResponse<RecommendationItemResponse> rawPage = recommendationApplicationService.recommend(
            currentUser.userId(),
            new DemandQuery(q, category, campusZone, location, null, null, DemandSort.RECOMMEND, new PageQuery(page, size))
        );
        if (rawPage.items().isEmpty()) {
            return ApiResponse.success(new PageResponse<>(List.of(), rawPage.page(), rawPage.size(), rawPage.total()));
        }
        // batch 加载 Demand + selectedCount，避免逐项 findById 和 selectedParticipantCount N+1
        List<Long> demandIds = rawPage.items().stream().map(RecommendationItemResponse::demandId).toList();
        Map<Long, Demand> demandMap = demandRepository.findAllById(demandIds).stream()
            .collect(Collectors.toMap(Demand::getId, d -> d));
        Map<Long, Long> selectedCountMap = demandResponseRepository.countSelectedByDemandIds(demandIds);
        List<RecommendedDemandView> items = rawPage.items().stream()
            .map(item -> {
                Demand demand = demandMap.get(item.demandId());
                if (demand == null) {
                    return null;
                }
                DemandView view = apiViewMapper.toDemandView(demand, currentUser, null, null, selectedCountMap);
                return new RecommendedDemandView(item.rank(), item.score(), item.reasonTags(), view);
            })
            .filter(Objects::nonNull)
            .toList();
        return ApiResponse.success(new PageResponse<>(items, rawPage.page(), rawPage.size(), rawPage.total()));
    }
}
