package com.campushub.backend.api;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.dto.CreateDemandResponseCommand;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandResponseDetail;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.SelectResponsesCommand;
import com.campushub.backend.demand.dto.UpdateDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.demand.service.DemandResponseApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import com.campushub.backend.recommendation.repository.UserActionLogRepository;
import com.campushub.backend.recommendation.service.RecommendationApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demands")
public class DemandController {

    private static final Logger log = LoggerFactory.getLogger(DemandController.class);

    private final DemandApplicationService demandApplicationService;
    private final OrderApplicationService orderApplicationService;
    private final DemandResponseApplicationService demandResponseApplicationService;
    private final RecommendationApplicationService recommendationApplicationService;
    private final DemandRepository demandRepository;
    private final DemandResponseRepository demandResponseRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final UserActionLogRepository userActionLogRepository;
    private final RequestUserExtractor requestUserExtractor;
    private final ApiViewMapper apiViewMapper;

    public DemandController(
        DemandApplicationService demandApplicationService,
        OrderApplicationService orderApplicationService,
        DemandResponseApplicationService demandResponseApplicationService,
        RecommendationApplicationService recommendationApplicationService,
        DemandRepository demandRepository,
        DemandResponseRepository demandResponseRepository,
        UserRepository userRepository,
        OrderRepository orderRepository,
        UserActionLogRepository userActionLogRepository,
        RequestUserExtractor requestUserExtractor,
        ApiViewMapper apiViewMapper
    ) {
        this.demandApplicationService = demandApplicationService;
        this.orderApplicationService = orderApplicationService;
        this.demandResponseApplicationService = demandResponseApplicationService;
        this.recommendationApplicationService = recommendationApplicationService;
        this.demandRepository = demandRepository;
        this.demandResponseRepository = demandResponseRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.userActionLogRepository = userActionLogRepository;
        this.requestUserExtractor = requestUserExtractor;
        this.apiViewMapper = apiViewMapper;
    }

    @PostMapping
    public ApiResponse<DemandView> publish(HttpServletRequest request, @RequestBody PublishDemandCommand command) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        Long demandId = demandApplicationService.publish(currentUser.userId(), command).id();
        return ApiResponse.success(
            demandRepository.findById(demandId)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
                .orElseThrow()
        );
    }

    @GetMapping
    public ApiResponse<PageResponse<DemandView>> list(
        HttpServletRequest request,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String campusZone,
        @RequestParam(required = false) String location,
        @RequestParam(required = false) LocalDateTime startTimeFrom,
        @RequestParam(required = false) LocalDateTime startTimeTo,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = "false") boolean includeOwn,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentUser currentUser = requestUserExtractor.tryExtract(request);
        DemandSort resolvedSort = parseSort(sort);

        // sort=RECOMMEND：先获取候选需求、完成推荐打分/排序/diversity rerank，再执行分页，
        // 避免“先分页后推荐”导致高推荐分需求永远无法进入第一页
        if (resolvedSort == DemandSort.RECOMMEND && currentUser != null) {
            PageResponse<DemandSummaryResponse> recPage = recommendationApplicationService.recommendDemandList(
                currentUser.userId(),
                new DemandQuery(q, category, campusZone, location, startTimeFrom, startTimeTo, DemandSort.RECOMMEND, new PageQuery(page, Math.min(size, 50)))
            );
            List<Long> recDemandIds = recPage.items().stream().map(DemandSummaryResponse::id).toList();
            if (recDemandIds.isEmpty()) {
                return ApiResponse.success(new PageResponse<>(List.of(), recPage.page(), recPage.size(), recPage.total()));
            }
            Map<Long, Demand> recDemandMap = demandRepository.findAllById(recDemandIds).stream()
                .collect(Collectors.toMap(Demand::getId, d -> d));
            Set<Long> recPublisherIds = recDemandMap.values().stream().map(Demand::getPublisherId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, User> recUserMap = recPublisherIds.isEmpty() ? Map.of()
                : userRepository.findAllById(recPublisherIds).stream().collect(Collectors.toMap(User::getId, u -> u));
            Map<Long, Order> recOrderMap = orderRepository.findAllByDemandIdIn(recDemandIds).stream()
                .collect(Collectors.toMap(Order::getDemandId, o -> o));
            Map<Long, Long> recSelectedCountMap = demandResponseRepository.countSelectedByDemandIds(recDemandIds);
            List<DemandView> recItems = recDemandIds.stream()
                .map(recDemandMap::get)
                .filter(Objects::nonNull)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser, recUserMap, recOrderMap, recSelectedCountMap))
                .toList();
            return ApiResponse.success(new PageResponse<>(recItems, recPage.page(), recPage.size(), recPage.total()));
        }

        // 普通 TIME / REWARD / DISTANCE 排序保持现有语义
        PageResponse<DemandSummaryResponse> rawPage = demandApplicationService.list(
            new DemandQuery(
                q,
                category,
                campusZone,
                location,
                startTimeFrom,
                startTimeTo,
                resolvedSort,
                new PageQuery(page, size),
                includeOwn && currentUser != null ? currentUser.userId() : null
            )
        );
        List<Long> demandIds = rawPage.items().stream().map(DemandSummaryResponse::id).toList();
        if (demandIds.isEmpty()) {
            return ApiResponse.success(new PageResponse<>(List.of(), rawPage.page(), rawPage.size(), rawPage.total()));
        }
        Map<Long, Demand> demandMap = demandRepository.findAllById(demandIds).stream()
            .collect(Collectors.toMap(Demand::getId, d -> d));
        Set<Long> publisherIds = demandMap.values().stream().map(Demand::getPublisherId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> userMap = publisherIds.isEmpty() ? Map.of()
            : userRepository.findAllById(publisherIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, Order> orderMap = orderRepository.findAllByDemandIdIn(demandIds).stream()
            .collect(Collectors.toMap(Order::getDemandId, o -> o));
        Map<Long, Long> selectedCountMap = demandResponseRepository.countSelectedByDemandIds(demandIds);
        List<DemandView> items = demandIds.stream()
            .map(demandMap::get)
            .filter(Objects::nonNull)
            .map(demand -> apiViewMapper.toDemandView(demand, currentUser, userMap, orderMap, selectedCountMap))
            .toList();
        return ApiResponse.success(new PageResponse<>(items, rawPage.page(), rawPage.size(), rawPage.total()));
    }

    private DemandSort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        try {
            return DemandSort.fromValue(sort);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, exception.getMessage());
        }
    }

    @GetMapping("/{demandId}")
    public ApiResponse<DemandView> detail(HttpServletRequest request, @PathVariable Long demandId) {
        CurrentUser currentUser = requestUserExtractor.tryExtract(request);
        Demand demand = demandRepository.findById(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
        recordView(currentUser, demand);
        return ApiResponse.success(apiViewMapper.toDemandView(demand, currentUser));
    }

    // ponytail: VIEW 去重——同一用户对同一需求 1 小时内的重复浏览只记一次，
    // 避免刷新推荐页面产生大量日志。仅在已登录用户访问真实需求时记录。
    // VIEW 日志是推荐系统副作用，写入失败不得影响详情接口可用性，故吞掉 DataAccessException。
    // 用 existsRecentView bounded 查询替代全量加载用户 VIEW 历史。
    private void recordView(CurrentUser currentUser, Demand demand) {
        if (currentUser == null || demand == null || demand.getId() == null || demand.getCategory() == null) {
            return;
        }
        try {
            Long userId = currentUser.userId();
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            if (userActionLogRepository.existsRecentView(userId, demand.getId(), oneHourAgo)) {
                return;
            }
            userActionLogRepository.save(new UserActionLog(
                null, userId, ActionType.VIEW, demand.getId(), demand.getCategory(), LocalDateTime.now()
            ));
        } catch (DataAccessException e) {
            log.warn("VIEW action log failed for demand {}", demand.getId(), e);
        }
    }

    @PutMapping("/{demandId}")
    public ApiResponse<DemandView> update(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @RequestBody UpdateDemandCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        demandApplicationService.update(currentUser.userId(), demandId, command);
        return ApiResponse.success(
            demandRepository.findById(demandId)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/{demandId}/withdraw")
    public ApiResponse<DemandView> withdraw(HttpServletRequest request, @PathVariable Long demandId) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        demandApplicationService.withdraw(currentUser.userId(), demandId);
        return ApiResponse.success(
            demandRepository.findById(demandId)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/{demandId}/accept")
    public ApiResponse<OrderView> accept(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @RequestBody(required = false) AcceptOrderCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        Long orderId = orderApplicationService.accept(currentUser.userId(), demandId, command).orderId();
        return ApiResponse.success(
            orderRepository.findById(orderId)
                .map(order -> apiViewMapper.toOrderView(order, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/{demandId}/responses")
    public ApiResponse<DemandResponseDetail> createResponse(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @RequestBody CreateDemandResponseCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(demandResponseApplicationService.createResponse(currentUser.userId(), demandId, command));
    }

    @GetMapping("/{demandId}/responses")
    public ApiResponse<List<DemandResponseDetail>> listResponses(
        HttpServletRequest request,
        @PathVariable Long demandId
    ) {
        requestUserExtractor.tryExtract(request);
        return ApiResponse.success(demandResponseApplicationService.listResponses(demandId));
    }

    @PostMapping("/{demandId}/responses/{responseId}/select")
    public ApiResponse<OrderView> selectResponse(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @PathVariable Long responseId
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        Long orderId = demandResponseApplicationService.selectResponse(currentUser.userId(), demandId, responseId).orderId();
        return ApiResponse.success(
            orderRepository.findById(orderId)
                .map(order -> apiViewMapper.toOrderView(order, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/{demandId}/responses/select")
    public ApiResponse<DemandView> selectResponses(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @RequestBody SelectResponsesCommand command
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        demandResponseApplicationService.selectResponses(currentUser.userId(), demandId, command);
        return ApiResponse.success(
            demandRepository.findById(demandId)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/{demandId}/responses/{responseId}/accept-answer")
    public ApiResponse<DemandView> acceptAnswer(
        HttpServletRequest request,
        @PathVariable Long demandId,
        @PathVariable Long responseId
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        demandResponseApplicationService.acceptAnswer(currentUser.userId(), demandId, responseId);
        return ApiResponse.success(
            demandRepository.findById(demandId)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
                .orElseThrow()
        );
    }

    @PostMapping("/responses/{responseId}/withdraw")
    public ApiResponse<DemandResponseDetail> withdrawResponse(
        HttpServletRequest request,
        @PathVariable Long responseId
    ) {
        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);
        return ApiResponse.success(demandResponseApplicationService.withdrawResponse(currentUser.userId(), responseId));
    }
}
