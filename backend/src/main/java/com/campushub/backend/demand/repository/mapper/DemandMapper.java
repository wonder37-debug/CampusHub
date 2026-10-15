package com.campushub.backend.demand.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.demand.repository.entity.DemandEntity;
import com.campushub.backend.demand.repository.handler.CommaSeparatedStringTypeHandler;
import com.campushub.backend.demand.repository.handler.JsonStringArrayTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
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
     *
     * <p>自定义 {@code @Select} 不会复用 MyBatis-Plus 的 autoResultMap，
     * 必须显式声明 {@code tags}/{@code images} 的 typeHandler，
     * 否则 JSON/逗号分隔列会被静默映射为空集合，后续 save 会把数据写空。</p>
     */
    @Select("SELECT * FROM ord_demand WHERE id = #{demandId} FOR UPDATE")
    @Results(id = "demandEntityForUpdateMap", value = {
        @Result(column = "tags", property = "tags", typeHandler = CommaSeparatedStringTypeHandler.class),
        @Result(column = "images", property = "images", typeHandler = JsonStringArrayTypeHandler.class)
    })
    DemandEntity selectByIdForUpdate(@Param("demandId") Long demandId);
}
