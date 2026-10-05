package com.campushub.backend.demand.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.demand.repository.entity.DemandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * ord_demand 表的 MyBatis-Plus Mapper。
 *
 * <p>仅提供数据访问能力，业务规则与筛选仍在服务层完成。</p>
 */
@Mapper
public interface DemandMapper extends BaseMapper<DemandEntity> {

    /**
     * 按主键查询并加行锁（SELECT ... FOR UPDATE），用于 SELECT_MANY/HELP/SELECT_ONE
     * 等发布者选择操作的并发控制。必须在事务中调用，锁持续到事务结束。
     */
    @Select("SELECT * FROM ord_demand WHERE id = #{demandId} FOR UPDATE")
    DemandEntity selectByIdForUpdate(@Param("demandId") Long demandId);
}
