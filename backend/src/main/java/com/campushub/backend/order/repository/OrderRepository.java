package com.campushub.backend.order.repository;

import com.campushub.backend.order.domain.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    /**
     * 保存订单。id 为空时视为新增，否则视为更新。
     */
    Order save(Order order);

    /**
     * 按主键查询订单，不存在时返回空。
     */
    Optional<Order> findById(Long orderId);

    /**
     * 按需求 id 查询订单。数据库实现需保证同一需求最多存在一条有效订单。
     */
    Optional<Order> findByDemandId(Long demandId);

    /**
     * 查询用户参与的订单，包含发布方与接单方两种角色。
     */
    List<Order> findByParticipant(Long userId);

    /**
     * 查询全量订单。当前主要供后台统计使用。
     */
    List<Order> findAll();
}
