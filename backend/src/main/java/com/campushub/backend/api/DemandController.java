package com.campushub.backend.api;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.common.api.ApiResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.common.security.RequestUserExtractor;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.UpdateDemandCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.service.DemandApplicationService;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.order.service.OrderApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
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

    private final DemandApplicationService demandApplicationService;
    private final OrderApplicationService orderApplicationService;
    private final DemandRepository demandRepository;
    private final OrderRepository orderRepository;
    private final RequestUserExtractor requestUserExtractor;
    private final ApiViewMapper apiViewMapper;

    public DemandController(
        DemandApplicationService demandApplicationService,
        OrderApplicationService orderApplicationService,
        DemandRepository demandRepository,
        OrderRepository orderRepository,
        RequestUserExtractor requestUserExtractor,
        ApiViewMapper apiViewMapper
    ) {
        this.demandApplicationService = demandApplicationService;
        this.orderApplicationService = orderApplicationService;
        this.demandRepository = demandRepository;
        this.orderRepository = orderRepository;
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
        List<DemandView> items = rawPage.items().stream()
            .map(item -> demandRepository.findById(item.id()).orElseThrow())
            .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
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
        return ApiResponse.success(
            demandRepository.findById(demandId)
                .map(demand -> apiViewMapper.toDemandView(demand, currentUser))
                .orElseThrow()
        );
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
}
