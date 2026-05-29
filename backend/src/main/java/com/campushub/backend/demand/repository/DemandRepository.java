package com.campushub.backend.demand.repository;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import java.util.List;
import java.util.Optional;

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
     * 查询全量需求。当前由服务层完成筛选、排序与推荐逻辑。
     */
    List<Demand> findAll();

    /**
     * 按需求状态查询。主要供后台管理审核列表使用，Service 层负责权限校验。
     */
    List<Demand> findByStatus(DemandStatus status);
}
