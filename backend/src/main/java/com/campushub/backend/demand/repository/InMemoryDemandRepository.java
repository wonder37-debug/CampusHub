package com.campushub.backend.demand.repository;

import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
public class InMemoryDemandRepository implements DemandRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, Demand> demands = new ConcurrentHashMap<>();

    @Override
    public Demand save(Demand demand) {
        if (demand.getId() == null) {
            demand.setId(sequence.getAndIncrement());
        }
        demands.put(demand.getId(), demand);
        return demand;
    }

    @Override
    public Optional<Demand> findById(Long demandId) {
        return Optional.ofNullable(demands.get(demandId));
    }

    @Override
    public List<Demand> findAll() {
        return new ArrayList<>(demands.values());
    }

    @Override
    public List<Demand> findByStatus(DemandStatus status) {
        if (status == null) {
            return new ArrayList<>();
        }
        return demands.values().stream()
            .filter(demand -> demand.getStatus() == status)
            .collect(Collectors.toList());
    }
}
