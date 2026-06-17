package com.campushub.backend.order.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.repository.entity.OrderEntity;
import com.campushub.backend.order.repository.entity.OrderStatusLogEntity;
import com.campushub.backend.order.repository.mapper.OrderMapper;
import com.campushub.backend.order.repository.mapper.OrderStatusLogMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
            orderMapper.insert(entity);
            order.setId(entity.getId());
            existingLogCount = 0;
        } else {
            orderMapper.updateById(entity);
            existingLogCount = countLogs(order.getId());
        }

        List<OrderStatusHistoryEntry> history = order.getStatusHistory();
        if (history != null) {
            for (int i = existingLogCount; i < history.size(); i++) {
                statusLogMapper.insert(OrderStatusLogEntity.fromDomain(order.getId(), history.get(i)));
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
        OrderEntity entity = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>().eq(OrderEntity::getDemandId, demandId));
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
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getPublisherId, userId)
            .or()
            .eq(OrderEntity::getAccepterId, userId);
        return assembleAll(orderMapper.selectList(wrapper));
    }

    @Override
    public List<Order> findAll() {
        return assembleAll(orderMapper.selectList(null));
    }

    @Override
    @Transactional
    public void deleteById(Long orderId) {
        if (orderId == null) {
            return;
        }
        statusLogMapper.delete(new LambdaQueryWrapper<OrderStatusLogEntity>().eq(OrderStatusLogEntity::getOrderId, orderId));
        orderMapper.deleteById(orderId);
    }

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

    private int countLogs(Long orderId) {
        Long count = statusLogMapper.selectCount(new LambdaQueryWrapper<OrderStatusLogEntity>()
            .eq(OrderStatusLogEntity::getOrderId, orderId));
        return count == null ? 0 : count.intValue();
    }
}
