package com.campushub.backend.demand.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.demand.repository.entity.DemandEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ord_demand 表的 MyBatis-Plus Mapper。
 *
 * <p>仅提供数据访问能力，业务规则与筛选仍在服务层完成。</p>
 */
@Mapper
public interface DemandMapper extends BaseMapper<DemandEntity> {
}
