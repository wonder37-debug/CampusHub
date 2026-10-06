package com.campushub.backend.demand.service;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandResponse;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.domain.ResponseStatus;
import com.campushub.backend.demand.dto.CreateDemandResponseCommand;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandResponseDetail;
import com.campushub.backend.demand.dto.SelectResponsesCommand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.service.OrderApplicationService;
import com.campushub.backend.order.service.RewardSettlementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(rollbackFor = Exception.class)
public class DemandResponseApplicationServiceImpl implements DemandResponseApplicationService {

    private static final int MAX_CONTENT_LENGTH = 1000;

    private final DemandResponseRepository demandResponseRepository;
    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final OrderApplicationService orderApplicationService;
    private final RewardSettlementService rewardSettlementService;

    public DemandResponseApplicationServiceImpl(
        DemandResponseRepository demandResponseRepository,
        DemandRepository demandRepository,
        UserRepository userRepository,
        OrderApplicationService orderApplicationService,
        RewardSettlementService rewardSettlementService
    ) {
        this.demandResponseRepository = demandResponseRepository;
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.orderApplicationService = orderApplicationService;
        this.rewardSettlementService = rewardSettlementService;
    }

    @Override
    public DemandResponseDetail createResponse(Long operatorId, Long demandId, CreateDemandResponseCommand command) {
        if (command == null || command.content() == null || command.content().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "response content must not be blank");
        }
        String content = command.content().trim();
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "response content length must not exceed " + MAX_CONTENT_LENGTH);
        }

        User author = findActiveUser(operatorId);
        Demand demand = findDemandForUpdate(demandId);

        if (demand.getInteractionMode() == InteractionMode.DIRECT_ACCEPT) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "DIRECT_ACCEPT demand does not accept responses, use accept endpoint");
        }
        if (!demand.getIsApproved() || demand.getStatus() != DemandStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "demand is not open for responses");
        }
        if (demand.getPublisherId() != null && demand.getPublisherId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "publisher cannot respond to own demand");
        }

        // 同一用户对同一 Demand 不能重复创建有效 Response
        if (demandResponseRepository.findActiveByDemandIdAndAuthorId(demandId, operatorId).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "active response already exists for this demand");
        }

        LocalDateTime now = LocalDateTime.now();
        DemandResponse response = new DemandResponse(
            null,
            demandId,
            author.getId(),
            content,
            ResponseStatus.PENDING,
            now,
            now
        );
        response = demandResponseRepository.save(response);
        return DemandResponseDetail.from(response, author.getNickname());
    }

    @Override
    public List<DemandResponseDetail> listResponses(Long demandId) {
        Demand demand = findDemand(demandId);
        List<DemandResponse> responses = demandResponseRepository.findByDemandId(demandId);
        if (responses.isEmpty()) {
            return List.of();
        }
        Map<Long, String> authorNames = loadAuthorNames(responses);
        return responses.stream()
            .map(r -> DemandResponseDetail.from(r, authorNames.getOrDefault(r.getAuthorId(), "未知用户")))
            .toList();
    }

    @Override
    public OrderDetailResponse selectResponse(Long operatorId, Long demandId, Long responseId) {
        Demand demand = findDemandForUpdate(demandId);
        requireInteractionMode(demand, InteractionMode.SELECT_ONE);
        requirePublisher(demand, operatorId);
        requireDemandPending(demand);

        DemandResponse response = findResponse(responseId);
        requireResponseBelongsToDemand(response, demandId);
        if (response.getStatus() != ResponseStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only pending response can be selected");
        }

        // 创建 Order 进入履约（复用现有 Order 状态机、评价、仲裁体系）
        OrderDetailResponse orderDetail = orderApplicationService.createOrderForSelectedResponse(
            operatorId, demandId, response.getAuthorId(), response.getContent()
        );

        // 标记选中
        response.setStatus(ResponseStatus.SELECTED);
        response.setUpdatedAt(LocalDateTime.now());
        demandResponseRepository.save(response);

        // 拒绝其他 PENDING Response
        rejectOtherPendingResponses(demandId, List.of(responseId));

        return orderDetail;
    }

    @Override
    public DemandDetailResponse selectResponses(Long operatorId, Long demandId, SelectResponsesCommand command) {
        if (command == null || command.responseIds() == null || command.responseIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "responseIds must not be empty");
        }
        Demand demand = findDemandForUpdate(demandId);
        requireInteractionMode(demand, InteractionMode.SELECT_MANY);
        requirePublisher(demand, operatorId);
        requireDemandPending(demand);
        if (demand.getTargetParticipantCount() == null || demand.getTargetParticipantCount() < 1) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "TEAM_UP demand missing targetParticipantCount");
        }

        List<Long> responseIds = command.responseIds().stream().distinct().toList();
        long alreadySelected = demandResponseRepository.countSelectedByDemandId(demandId);
        if (alreadySelected + responseIds.size() > demand.getTargetParticipantCount()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                "selection exceeds targetParticipantCount (selected=" + alreadySelected
                    + ", new=" + responseIds.size()
                    + ", target=" + demand.getTargetParticipantCount() + ")");
        }

        // 校验所有 responseId 属于该 Demand 且状态 PENDING
        LocalDateTime now = LocalDateTime.now();
        for (Long responseId : responseIds) {
            DemandResponse response = findResponse(responseId);
            requireResponseBelongsToDemand(response, demandId);
            if (response.getStatus() != ResponseStatus.PENDING) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "response " + responseId + " is not pending");
            }
            response.setStatus(ResponseStatus.SELECTED);
            response.setUpdatedAt(now);
            demandResponseRepository.save(response);
        }

        long totalSelected = alreadySelected + responseIds.size();
        if (totalSelected >= demand.getTargetParticipantCount()) {
            // 达到目标人数，立即完成并平分 reward
            return completeTeamUpDemand(demand, now);
        }

        // 未达目标人数，保留其他 PENDING Response 供后续分批选择
        return DemandDetailResponse.from(demand);
    }

    @Override
    public DemandDetailResponse acceptAnswer(Long operatorId, Long demandId, Long responseId) {
        Demand demand = findDemandForUpdate(demandId);
        requireInteractionMode(demand, InteractionMode.HELP);
        requirePublisher(demand, operatorId);
        requireDemandPending(demand);

        DemandResponse response = findResponse(responseId);
        requireResponseBelongsToDemand(response, demandId);
        if (response.getStatus() != ResponseStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only pending response can be accepted");
        }

        LocalDateTime now = LocalDateTime.now();
        response.setStatus(ResponseStatus.SELECTED);
        response.setUpdatedAt(now);
        demandResponseRepository.save(response);

        rejectOtherPendingResponses(demandId, List.of(responseId));

        // Demand 完成，reward 结算给被采纳者
        demand.setStatus(DemandStatus.COMPLETED);
        demand.setUpdatedAt(now);
        demandRepository.save(demand);

        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        rewardSettlementService.settleToAccepter(demand.getPublisherId(), response.getAuthorId(), reward);

        return DemandDetailResponse.from(demand);
    }

    @Override
    public DemandResponseDetail withdrawResponse(Long operatorId, Long responseId) {
        DemandResponse response = findResponse(responseId);
        if (!response.getAuthorId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only author can withdraw response");
        }
        if (response.getStatus() != ResponseStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only pending response can be withdrawn");
        }
        response.setStatus(ResponseStatus.WITHDRAWN);
        response.setUpdatedAt(LocalDateTime.now());
        demandResponseRepository.save(response);
        String authorName = userRepository.findById(operatorId).map(User::getNickname).orElse("未知用户");
        return DemandResponseDetail.from(response, authorName);
    }

    private DemandDetailResponse completeTeamUpDemand(Demand demand, LocalDateTime now) {
        List<DemandResponse> selected = demandResponseRepository.findSelectedByDemandId(demand.getId());
        if (selected.isEmpty()) {
            return DemandDetailResponse.from(demand);
        }
        demand.setStatus(DemandStatus.COMPLETED);
        demand.setUpdatedAt(now);
        demandRepository.save(demand);

        List<Long> accepterIds = selected.stream().map(DemandResponse::getAuthorId).toList();
        BigDecimal reward = demand.getReward() == null ? BigDecimal.ZERO : demand.getReward();
        rewardSettlementService.settleToMultipleAccepters(demand.getPublisherId(), accepterIds, reward);

        // 拒绝剩余 PENDING Response
        rejectOtherPendingResponses(demand.getId(), selected.stream().map(DemandResponse::getId).toList());
        return DemandDetailResponse.from(demand);
    }

    private void rejectOtherPendingResponses(Long demandId, List<Long> excludedResponseIds) {
        List<DemandResponse> pending = demandResponseRepository.findByDemandIdAndStatusIn(demandId, List.of(ResponseStatus.PENDING));
        LocalDateTime now = LocalDateTime.now();
        for (DemandResponse r : pending) {
            if (!excludedResponseIds.contains(r.getId())) {
                r.setStatus(ResponseStatus.REJECTED);
                r.setUpdatedAt(now);
                demandResponseRepository.save(r);
            }
        }
    }

    private Map<Long, String> loadAuthorNames(List<DemandResponse> responses) {
        List<Long> authorIds = responses.stream()
            .map(DemandResponse::getAuthorId)
            .distinct()
            .toList();
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(authorIds)) {
            names.put(user.getId(), user.getNickname());
        }
        return names;
    }

    private Demand findDemand(Long demandId) {
        if (demandId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "demandId must not be null");
        }
        return demandRepository.findById(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
    }

    private Demand findDemandForUpdate(Long demandId) {
        if (demandId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "demandId must not be null");
        }
        return demandRepository.findByIdForUpdate(demandId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "demand not found"));
    }

    private void requireDemandPending(Demand demand) {
        if (demand.getStatus() != DemandStatus.PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                "only PENDING demand can be selected, current status: " + demand.getStatus());
        }
    }

    private DemandResponse findResponse(Long responseId) {
        if (responseId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "responseId must not be null");
        }
        return demandResponseRepository.findById(responseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "response not found"));
    }

    private User findActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));
        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "banned user cannot respond");
        }
        return user;
    }

    private void requirePublisher(Demand demand, Long operatorId) {
        if (demand.getPublisherId() == null || !demand.getPublisherId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only publisher can perform this action");
        }
    }

    private void requireInteractionMode(Demand demand, InteractionMode expected) {
        if (demand.getInteractionMode() != expected) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                "demand interactionMode is " + demand.getInteractionMode() + ", expected " + expected);
        }
    }

    private void requireResponseBelongsToDemand(DemandResponse response, Long demandId) {
        if (!response.getDemandId().equals(demandId)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "response does not belong to this demand");
        }
    }
}
