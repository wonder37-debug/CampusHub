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

    void deleteById(Long orderId);
}
