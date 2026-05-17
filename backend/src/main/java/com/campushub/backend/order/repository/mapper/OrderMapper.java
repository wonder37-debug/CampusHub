package com.campushub.backend.order.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.order.repository.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ord_order 表的 MyBatis-Plus Mapper。
 *
 * <p>仅承担数据访问能力；状态流水的写入由 {@link OrderStatusLogMapper} 负责。</p>
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {
}
