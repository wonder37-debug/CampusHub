package com.campushub.backend.order.repository;

import com.campushub.backend.order.domain.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long orderId);

    Optional<Order> findByDemandId(Long demandId);

    List<Order> findByParticipant(Long userId);

    List<Order> findAll();

    /**
     * 分页查询仲裁中订单（status=IN_ARBITRATION + updatedAt DESC + LIMIT/OFFSET 下推 SQL）。
     *
     * <p>不加载 statusHistory（调用方用 OrderSummaryResponse 不依赖历史）；避免 findAll 的逐条 loadHistory N+1。</p>
     *
     * @param page 页码（>=1，由 Service 层 Math.max 兜底）
     * @param size 每页大小（>=1，由 Service 层 Math.max 兜底）
     */
    List<Order> findArbitrationPage(int page, int size);

    /**
     * 统计仲裁中订单总数（status=IN_ARBITRATION 下推 SQL，用于分页 total）。
     */
    long countArbitration();

    void deleteById(Long orderId);
}
