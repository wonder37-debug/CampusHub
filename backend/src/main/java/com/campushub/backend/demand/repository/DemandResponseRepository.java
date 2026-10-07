package com.campushub.backend.demand.repository;

import com.campushub.backend.demand.domain.DemandResponse;
import com.campushub.backend.demand.domain.ResponseStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DemandResponseRepository {

    DemandResponse save(DemandResponse response);

    Optional<DemandResponse> findById(Long id);

    List<DemandResponse> findByDemandId(Long demandId);

    List<DemandResponse> findByDemandIdAndStatusIn(Long demandId, Collection<ResponseStatus> statuses);

    Optional<DemandResponse> findActiveByDemandIdAndAuthorId(Long demandId, Long authorId);

    List<DemandResponse> findSelectedByDemandId(Long demandId);

    long countSelectedByDemandId(Long demandId);

    /**
     * 批量统计多个 Demand 的已选中 Response 数量（下推 SQL GROUP BY，避免列表场景 N+1）。
     *
     * @param demandIds 需求 ID 集合，为空时返回空 Map
     * @return demandId → selectedCount 映射，未出现在结果中的 demandId 视为 0
     */
    Map<Long, Long> countSelectedByDemandIds(Collection<Long> demandIds);

    /**
     * 统计 Demand 下的全部 Response 数量（含所有状态），用于 update 时禁止修改互动模式字段。
     */
    long countByDemandId(Long demandId);

    List<DemandResponse> findByIdIn(Collection<Long> ids);
}
