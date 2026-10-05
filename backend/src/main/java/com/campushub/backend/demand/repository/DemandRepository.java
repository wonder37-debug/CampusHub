package com.campushub.backend.demand.repository;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandReviewQuery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface DemandRepository {

    /**
     * 保存需求。id 为空时视为新增，否则视为更新。
     */
    Demand save(Demand demand);

    /**
     * 按主键查询需求，不存在时返回空。
     */
    Optional<Demand> findById(Long demandId);

    /**
     * 按主键查询需求并加行锁（FOR UPDATE），用于发布者选择/采纳操作的并发控制。
     * 必须在事务中调用，锁持续到事务结束（含状态更新与 reward 结算）。
     */
    Optional<Demand> findByIdForUpdate(Long demandId);

    /**
     * 查询全量需求。当前由服务层完成筛选、排序与推荐逻辑。
     */
    List<Demand> findAll();

    /**
     * 按需求状态查询。主要供后台管理审核列表使用，Service 层负责权限校验。
     */
    List<Demand> findByStatus(DemandStatus status);

    /**
     * 按查询条件分页查询需求（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findPage(DemandQuery query);

    /**
     * 按查询条件统计匹配的需求总数（过滤下推 SQL，用于分页 total）。
     *
     * @param query 查询条件，为 null 时返回 0
     */
    long count(DemandQuery query);

    /**
     * 按审核查询条件分页查询审核中需求（status=REVIEWING + 过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findReviewPage(DemandReviewQuery query);

    /**
     * 按审核查询条件统计匹配的审核中需求总数（过滤下推 SQL，用于分页 total）。
     *
     * @param query 查询条件，为 null 时返回 0
     */
    long countReview(DemandReviewQuery query);

    /**
     * 统计需求总数（下推 SQL selectCount，无过滤，用于 dashboard stats）。
     */
    long countAll();

    /**
     * 按状态统计需求数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long countByStatus(DemandStatus status);

    /**
     * 按分类统计需求数（下推 SQL groupBy，用于 dashboard categoryDistribution）。
     */
    Map<String, Long> countByCategory();

    /**
     * 按推荐候选条件查询需求（status=PENDING + 排除自己 + keyword/category/campusZone/location/startTime 过滤下推 SQL）。
     *
     * <p>不分页（返回全部候选），不排序（Service 层做 score 排序）；不加载跨仓储的 order 存在性（留 Service N+1，2C 修）。</p>
     *
     * @param userId 推荐目标用户 ID（排除自己发的 demand），为 null 时不加 publisher_id 条件
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findCandidatePage(Long userId, DemandQuery query);

    Set<Long> findActivePublisherIdsByDate(LocalDate today);

    /**
     * 按主键集合批量查询需求（用于 Service 层避免逐条 findById N+1）。
     */
    List<Demand> findAllById(Collection<Long> ids);
}
