package com.campushub.backend.demand.service;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.repository.DemandRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：每 5 分钟扫描一次所有 PENDING 状态的需求，
 * 若 endTime 已过期则将状态自动更新为 EXPIRED。
 */
@Component
public class DemandExpirationScheduler {

    private final DemandRepository demandRepository;

    public DemandExpirationScheduler(DemandRepository demandRepository) {
        this.demandRepository = demandRepository;
    }

    @Scheduled(fixedRate = 300_000)
    public void expireOverdueDemands() {
        LocalDateTime now = LocalDateTime.now();
        List<Demand> pendingDemands = demandRepository.findByStatus(DemandStatus.PENDING);
        for (Demand demand : pendingDemands) {
            if (demand.getEndTime() != null && demand.getEndTime().isBefore(now)) {
                demand.setStatus(DemandStatus.EXPIRED);
                demand.setUpdatedAt(now);
                demandRepository.save(demand);
            }
        }
    }
}
