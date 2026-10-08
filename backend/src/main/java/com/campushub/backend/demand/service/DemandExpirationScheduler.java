package com.campushub.backend.demand.service;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.order.service.RewardSettlementService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 定时任务：每 5 分钟扫描一次所有 PENDING 状态的需求，
 * 若 endTime 已过期则将状态自动更新为 EXPIRED，并释放尚未结算的冻结悬赏。
 *
 * <p>每条需求的"加锁 → 状态确认 → EXPIRED → reward refund"在独立事务（REQUIRES_NEW）内完成，
 * 单条失败只回滚当前需求并记录错误，不影响其他需求的过期处理。</p>
 */
@Component
public class DemandExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemandExpirationScheduler.class);

    private final DemandRepository demandRepository;
    private final RewardSettlementService rewardSettlementService;
    private final TransactionTemplate transactionTemplate;

    public DemandExpirationScheduler(
        DemandRepository demandRepository,
        RewardSettlementService rewardSettlementService,
        PlatformTransactionManager transactionManager
    ) {
        this.demandRepository = demandRepository;
        this.rewardSettlementService = rewardSettlementService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedRate = 300_000)
    public void expireOverdueDemands() {
        LocalDateTime now = LocalDateTime.now();
        // 直接查询已过期且仍为 PENDING 的需求（endTime < now 下推 SQL），避免加载全部 PENDING 后再 Java 筛选
        List<Demand> expiredDemands = demandRepository.findExpiredPending(now);
        for (Demand snapshot : expiredDemands) {
            try {
                transactionTemplate.execute(status -> {
                    expireOne(snapshot.getId(), now);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to expire demand {}: {}", snapshot.getId(), e.getMessage(), e);
            }
        }
    }

    private void expireOne(Long demandId, LocalDateTime now) {
        Demand demand = demandRepository.findByIdForUpdate(demandId).orElse(null);
        if (demand == null) {
            return;
        }
        // 状态前置校验：仅当数据库当前状态仍为 PENDING 才执行过期，避免重复解冻
        if (demand.getStatus() != DemandStatus.PENDING) {
            return;
        }
        if (demand.getEndTime() == null || !demand.getEndTime().isBefore(now)) {
            return;
        }
        demand.setStatus(DemandStatus.EXPIRED);
        demand.setUpdatedAt(now);
        // 释放尚未结算的冻结悬赏，与 withdraw/reject/deleteOrder 路径对齐
        rewardSettlementService.refundToPublisher(demand.getPublisherId(), demand.getReward());
        demandRepository.save(demand);
    }
}
