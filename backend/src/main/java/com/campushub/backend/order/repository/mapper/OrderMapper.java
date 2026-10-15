package com.campushub.backend.order.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.campushub.backend.order.repository.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * ord_order 表的 MyBatis-Plus Mapper。
 *
 * <p>仅承担数据访问能力；状态流水的写入由 {@link OrderStatusLogMapper} 负责。</p>
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 按主键查询并加行锁（SELECT ... FOR UPDATE），用于订单状态机核心写路径的并发控制。
     * 必须在事务中调用，锁持续到事务结束（含状态更新与 reward 结算）。
     *
     * <p>自定义 {@code @Select} 不会复用 MyBatis-Plus 的 autoResultMap，
     * 必须显式声明 {@code proof_image_urls} 的 typeHandler，
     * 否则已提交的凭证图片 URL 会被静默映射为空列表，后续 save 会把凭证数据写空。</p>
     */
    @Select("SELECT * FROM ord_order WHERE id = #{orderId} FOR UPDATE")
    @Results(id = "orderEntityForUpdateMap", value = {
        @Result(column = "proof_image_urls", property = "proofImageUrls", typeHandler = JacksonTypeHandler.class)
    })
    OrderEntity selectByIdForUpdate(@Param("orderId") Long orderId);
}
