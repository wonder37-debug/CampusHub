package com.campushub.backend.order.repository;

import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long orderId);

    /**
     * 按主键查询订单并加行锁（FOR UPDATE），用于订单状态机核心写路径的并发控制。
     *
     * <p>覆盖普通状态更新、双方确认完成、发起仲裁、管理员仲裁裁决、自动完成等场景，
     * 确保并发请求不能产生重复完成、重复结算、非法状态迁移。必须在事务中调用，
     * 锁持续到事务结束（含状态更新与 reward 结算）。与 Demand 的 {@code findByIdForUpdate} 设计保持一致。</p>
     */
    Optional<Order> findByIdForUpdate(Long orderId);

    Optional<Order> findByDemandId(Long demandId);

    List<Order> findAllByDemandIdIn(Collection<Long> demandIds);

    List<Order> findAllById(Collection<Long> ids);

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

    /**
     * 统计订单总数（下推 SQL selectCount，无过滤，用于 dashboard stats）。
     */
    long count();

    /**
     * 按状态统计订单数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long countByStatus(OrderStatus status);

    /**
     * 按用户与查询条件分页查询历史订单（publisher_id 或 accepter_id 命中 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * <p>不加载 statusHistory（调用方用 OrderSummaryResponse 不依赖历史）；避免 findByParticipant 的逐条 loadHistory N+1。</p>
     *
     * @param userId 用户 ID，为 null 时返回空列表
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Order> findHistoryPage(Long userId, OrderHistoryQuery query);

    /**
     * 按用户统计历史订单总数（publisher_id 或 accepter_id 命中，下推 SQL，用于分页 total）。
     *
     * @param userId 用户 ID，为 null 时返回 0
     */
    long countHistory(Long userId);

    /**
     * 查询给定 demand 集合中已有订单的 demand_id（用于推荐候选排除已被接单的 demand）。
     *
     * @param demandIds demand ID 集合，为 null/空时返回空集
     */
    Set<Long> findDemandIdsWithOrder(Collection<Long> demandIds);

    Set<Long> findActiveParticipantIdsByDate(LocalDate today);

    void deleteById(Long orderId);
}
