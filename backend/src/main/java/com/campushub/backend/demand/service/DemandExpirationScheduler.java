package com.campushub.backend.demand.service;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.order.service.RewardSettlementService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时任务：每 5 分钟扫描一次所有 PENDING 状态的需求，
 * 若 endTime 已过期则将状态自动更新为 EXPIRED，并释放尚未结算的冻结悬赏。
 *
 * <p>过期与解冻在同一个事务内完成；对每条需求重新加 FOR UPDATE 行锁并校验当前状态仍为 PENDING，
 * 避免与 accept/withdraw/selectResponse/acceptAnswer 等持锁路径并发产生状态覆盖或重复解冻。</p>
 */
@Component
public class DemandExpirationScheduler {

    private final DemandRepository demandRepository;
    private final RewardSettlementService rewardSettlementService;

    public DemandExpirationScheduler(DemandRepository demandRepository, RewardSettlementService rewardSettlementService) {
        this.demandRepository = demandRepository;
        this.rewardSettlementService = rewardSettlementService;
    }

    @Scheduled(fixedRate = 300_000)
    @Transactional(rollbackFor = Exception.class)
    public void expireOverdueDemands() {
        LocalDateTime now = LocalDateTime.now();
        List<Demand> pendingDemands = demandRepository.findByStatus(DemandStatus.PENDING);
        for (Demand snapshot : pendingDemands) {
            if (snapshot.getEndTime() == null || !snapshot.getEndTime().isBefore(now)) {
                continue;
            }
            expireOne(snapshot.getId(), now);
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
