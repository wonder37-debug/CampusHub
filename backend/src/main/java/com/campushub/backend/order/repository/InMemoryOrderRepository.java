package com.campushub.backend.order.repository;

import com.campushub.backend.order.domain.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!local")
public class InMemoryOrderRepository implements OrderRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final Map<Long, Long> demandToOrder = new ConcurrentHashMap<>();

    @Override
    public synchronized Order save(Order order) {
        if (order.getId() == null) {
            if (demandToOrder.containsKey(order.getDemandId())) {
                throw new IllegalStateException("order for demand already exists");
            }
            order.setId(sequence.getAndIncrement());
            demandToOrder.put(order.getDemandId(), order.getId());
        }
        orders.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public Optional<Order> findByDemandId(Long demandId) {
        Long orderId = demandToOrder.get(demandId);
        return orderId == null ? Optional.empty() : Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public List<Order> findByParticipant(Long userId) {
        List<Order> results = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.isParticipant(userId)) {
                results.add(order);
            }
        }
        return results;
    }

    @Override
    public List<Order> findAll() {
        return new ArrayList<>(orders.values());
    }
}
