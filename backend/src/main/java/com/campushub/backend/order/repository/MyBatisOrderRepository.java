package com.campushub.backend.order.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.repository.entity.OrderEntity;
import com.campushub.backend.order.repository.entity.OrderStatusLogEntity;
import com.campushub.backend.order.repository.mapper.OrderMapper;
import com.campushub.backend.order.repository.mapper.OrderStatusLogMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
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
    public Optional<Order> findByIdForUpdate(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        OrderEntity entity = orderMapper.selectByIdForUpdate(orderId);
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
    public List<Order> findAllByDemandIdIn(Collection<Long> demandIds) {
        if (demandIds == null || demandIds.isEmpty()) { return List.of(); }
        return assembleWithBatchHistory(orderMapper.selectList(
            new LambdaQueryWrapper<OrderEntity>().in(OrderEntity::getDemandId, demandIds)));
    }

    @Override
    public List<Order> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return assembleWithBatchHistory(orderMapper.selectBatchIds(ids));
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
        return assembleWithBatchHistory(orderMapper.selectList(wrapper));
    }

    @Override
    public List<Order> findAll() {
        return assembleWithBatchHistory(orderMapper.selectList(null));
    }

    @Override
    public List<Order> findArbitrationPage(int page, int size) {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getStatus, OrderStatus.IN_ARBITRATION.name())
            .orderByDesc(OrderEntity::getUpdatedAt)
            .orderByDesc(OrderEntity::getId);
        long offset = (long) (page - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return orderMapper.selectList(wrapper).stream()
            .map(e -> e.toDomain(Collections.emptyList()))
            .toList();
    }

    @Override
    public long countArbitration() {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getStatus, OrderStatus.IN_ARBITRATION.name());
        return orderMapper.selectCount(wrapper);
    }

    @Override
    public long count() {
        return orderMapper.selectCount(null);
    }

    @Override
    public long countByStatus(OrderStatus status) {
        if (status == null) {
            return 0L;
        }
        return orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
            .eq(OrderEntity::getStatus, status.name()));
    }

    @Override
    public List<Order> findHistoryPage(Long userId, OrderHistoryQuery query) {
        if (userId == null || query == null) {
            return List.of();
        }
        LambdaQueryWrapper<OrderEntity> wrapper = buildHistoryWrapper(userId);
        wrapper.orderByDesc(OrderEntity::getCreatedAt)
               .orderByDesc(OrderEntity::getId);
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return orderMapper.selectList(wrapper).stream()
            .map(e -> e.toDomain(Collections.emptyList()))
            .toList();
    }

    @Override
    public long countHistory(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return orderMapper.selectCount(buildHistoryWrapper(userId));
    }

    private LambdaQueryWrapper<OrderEntity> buildHistoryWrapper(Long userId) {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(OrderEntity::getPublisherId, userId)
            .or().eq(OrderEntity::getAccepterId, userId));
        return wrapper;
    }

    @Override
    public Set<Long> findDemandIdsWithOrder(Collection<Long> demandIds) {
        if (demandIds == null || demandIds.isEmpty()) {
            return Set.of();
        }
        List<OrderEntity> entities = orderMapper.selectList(
            new LambdaQueryWrapper<OrderEntity>()
                .select(OrderEntity::getDemandId)
                .in(OrderEntity::getDemandId, demandIds));
        return entities.stream().map(OrderEntity::getDemandId).collect(Collectors.toSet());
    }

    @Override
    public Set<Long> findActiveParticipantIdsByDate(LocalDate today) {
        if (today == null) {
            return Set.of();
        }
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        List<OrderEntity> entities = orderMapper.selectList(
            new LambdaQueryWrapper<OrderEntity>()
                .select(OrderEntity::getPublisherId, OrderEntity::getAccepterId)
                .and(w -> w.ge(OrderEntity::getCreatedAt, start).lt(OrderEntity::getCreatedAt, end)
                    .or().ge(OrderEntity::getUpdatedAt, start).lt(OrderEntity::getUpdatedAt, end)
                    .or().ge(OrderEntity::getCompletedAt, start).lt(OrderEntity::getCompletedAt, end)));
        Set<Long> result = new HashSet<>();
        for (OrderEntity e : entities) {
            result.add(e.getPublisherId());
            result.add(e.getAccepterId());
        }
        result.remove(null);
        return result;
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

    private List<Order> assembleWithBatchHistory(List<OrderEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> orderIds = entities.stream().map(OrderEntity::getId).filter(Objects::nonNull).toList();
        Map<Long, List<OrderStatusHistoryEntry>> historyByOrderId = loadHistories(orderIds);
        List<Order> orders = new ArrayList<>(entities.size());
        for (OrderEntity entity : entities) {
            orders.add(entity.toDomain(historyByOrderId.getOrDefault(entity.getId(), Collections.emptyList())));
        }
        return orders;
    }

    private Map<Long, List<OrderStatusHistoryEntry>> loadHistories(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<OrderStatusLogEntity> logs = statusLogMapper.selectList(
            new LambdaQueryWrapper<OrderStatusLogEntity>()
                .in(OrderStatusLogEntity::getOrderId, orderIds)
                .orderByAsc(OrderStatusLogEntity::getChangedAt)
                .orderByAsc(OrderStatusLogEntity::getId)
        );
        Map<Long, List<OrderStatusHistoryEntry>> result = new HashMap<>();
        for (OrderStatusLogEntity log : logs) {
            result.computeIfAbsent(log.getOrderId(), k -> new ArrayList<>()).add(log.toDomain());
        }
        return result;
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
