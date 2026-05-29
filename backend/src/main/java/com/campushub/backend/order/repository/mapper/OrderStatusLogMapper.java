package com.campushub.backend.order.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.order.repository.entity.OrderStatusLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ord_order_status_log 表的 MyBatis-Plus Mapper。
 *
 * <p>提供按 order_id 查询、按 changed_at 升序排序等操作通过 LambdaQueryWrapper 完成。</p>
 */
@Mapper
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLogEntity> {
}
