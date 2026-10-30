package com.campushub.backend.demand.repository;

import com.campushub.backend.demand.domain.DemandResponse;
import com.campushub.backend.demand.domain.ResponseStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface DemandResponseRepository {

    DemandResponse save(DemandResponse response);

    Optional<DemandResponse> findById(Long id);

    List<DemandResponse> findByDemandId(Long demandId);

    List<DemandResponse> findByDemandIdAndStatusIn(Long demandId, Collection<ResponseStatus> statuses);

    Optional<DemandResponse> findActiveByDemandIdAndAuthorId(Long demandId, Long authorId);

    List<DemandResponse> findSelectedByDemandId(Long demandId);

    /**
     * 判断某用户是否为该需求的已选中参与者（SELECTED 响应）。
     * 覆盖 SELECT_MANY 被选中的组队成员与 HELP 被采纳的回答者——
     * 这两类流程不创建正式 Order，需要独立的授权依据。
     */
    boolean existsSelectedByDemandIdAndAuthorId(Long demandId, Long authorId);

    /**
     * 批量查询某用户在这些 Demand 中作为已选中参与者（SELECTED 响应）的 demandId 集合，
     * 用于列表场景的联系方式授权判定，避免逐条 existsSelectedByDemandIdAndAuthorId 产生 N+1。
     *
     * @param authorId   当前用户 ID
     * @param demandIds  需求 ID 集合，为空时返回空 Set
     * @return 该用户已选中的 demandId 集合；无匹配时返回空 Set
     */
    Set<Long> findSelectedDemandIdsByAuthor(Long authorId, Collection<Long> demandIds);

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

    /**
     * 查询某用户提交的全部响应（跨需求），用于"我参与的互助"列表。
     * 按 createdAt 降序返回。
     */
    List<DemandResponse> findByAuthorId(Long authorId);
}
