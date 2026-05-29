package com.campushub.backend.order.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.repository.entity.OrderEntity;
import com.campushub.backend.order.repository.entity.OrderStatusLogEntity;
import com.campushub.backend.order.repository.mapper.OrderMapper;
import com.campushub.backend.order.repository.mapper.OrderStatusLogMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 {@link OrderRepository} 实现（一主多从结构）。
 *
 * <p>主表 {@code ord_order} 与流水表 {@code ord_order_status_log} 在同一事务中维护，
 * 保证订单与流水的原子性。仅在 {@code local} profile 下激活，避免与默认内存仓储冲突。</p>
 *
 * <p>并发防重底线：依赖 ord_order 上的唯一索引 {@code uk_order_demand(demand_id)}，
 * 重复抢单将由数据库抛出 SQLException，Spring 体系会转换为 {@link
 * org.springframework.dao.DuplicateKeyException}。本类不做任何捕获，原样向上传播。</p>
 *
 * <p>流水写入策略：基于"流水仅追加"的领域约束，UPDATE 路径下统计已持久化的流水条数 N，
 * 仅把领域列表中索引 ≥ N 的尾部条目插入到流水表，从而避免重复写入历史记录。</p>
 */
@Repository
@Profile("local")
public class MyBatisOrderRepository implements OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;

    public MyBatisOrderRepository(OrderMapper orderMapper, OrderStatusLogMapper statusLogMapper) {
        this.orderMapper = orderMapper;
        this.statusLogMapper = statusLogMapper;
    }

    @Override
    @Transactional
    public Order save(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        OrderEntity entity = OrderEntity.fromDomain(order);

        int existingLogCount;
        if (order.getId() == null) {
            // 唯一索引 uk_order_demand 触发的重复抢单异常会在此抛出
            orderMapper.insert(entity);
            order.setId(entity.getId());
            existingLogCount = 0;
        } else {
            orderMapper.updateById(entity);
            existingLogCount = countLogs(order.getId());
        }

        // 仅追加新增流水：跳过 [0, existingLogCount) 的已持久化条目
        List<OrderStatusHistoryEntry> history = order.getStatusHistory();
        if (history != null) {
            for (int i = existingLogCount; i < history.size(); i++) {
                OrderStatusLogEntity logEntity =
                    OrderStatusLogEntity.fromDomain(order.getId(), history.get(i));
                statusLogMapper.insert(logEntity);
            }
        }
        return order;
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        OrderEntity entity = orderMapper.selectById(orderId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain(loadHistory(entity.getId())));
    }

    @Override
    public Optional<Order> findByDemandId(Long demandId) {
        if (demandId == null) {
            return Optional.empty();
        }
        OrderEntity entity = orderMapper.selectOne(
            new LambdaQueryWrapper<OrderEntity>().eq(OrderEntity::getDemandId, demandId)
        );
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain(loadHistory(entity.getId())));
    }

    @Override
    public List<Order> findByParticipant(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        // publisher_id == userId OR accepter_id == userId
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getPublisherId, userId)
            .or()
            .eq(OrderEntity::getAccepterId, userId);
        List<OrderEntity> entities = orderMapper.selectList(wrapper);
        return assembleAll(entities);
    }

    @Override
    public List<Order> findAll() {
        List<OrderEntity> entities = orderMapper.selectList(null);
        return assembleAll(entities);
    }

    /** 把实体列表组装为携带流水的领域订单列表，绝不返回 null。 */
    private List<Order> assembleAll(List<OrderEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        List<Order> orders = new ArrayList<>(entities.size());
        for (OrderEntity entity : entities) {
            orders.add(entity.toDomain(loadHistory(entity.getId())));
        }
        return orders;
    }

    /** 读取指定订单的全部流水，按 changed_at 升序、id 升序稳定排序。 */
    private List<OrderStatusHistoryEntry> loadHistory(Long orderId) {
        if (orderId == null) {
            return Collections.emptyList();
        }
        List<OrderStatusLogEntity> logs = statusLogMapper.selectList(
            new LambdaQueryWrapper<OrderStatusLogEntity>()
                .eq(OrderStatusLogEntity::getOrderId, orderId)
                .orderByAsc(OrderStatusLogEntity::getChangedAt)
                .orderByAsc(OrderStatusLogEntity::getId)
        );
        List<OrderStatusHistoryEntry> result = new ArrayList<>(logs.size());
        for (OrderStatusLogEntity log : logs) {
            result.add(log.toDomain());
        }
        return result;
    }

    /** 统计某订单当前已持久化的流水条数，用于实现"仅追加"的写入策略。 */
    private int countLogs(Long orderId) {
        Long c = statusLogMapper.selectCount(
            new LambdaQueryWrapper<OrderStatusLogEntity>()
                .eq(OrderStatusLogEntity::getOrderId, orderId)
        );
        return c == null ? 0 : c.intValue();
    }
}
