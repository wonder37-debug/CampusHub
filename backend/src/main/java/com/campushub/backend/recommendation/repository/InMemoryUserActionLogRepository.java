package com.campushub.backend.recommendation.repository;

import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
public class InMemoryUserActionLogRepository implements UserActionLogRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, UserActionLog> logs = new ConcurrentHashMap<>();

    @Override
    public UserActionLog save(UserActionLog log) {
        if (log.getId() == null) {
            log.setId(sequence.getAndIncrement());
        }
        logs.put(log.getId(), log);
        return log;
    }

    @Override
    public List<UserActionLog> findByUserId(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        return logs.values().stream()
            .filter(log -> userId.equals(log.getUserId()))
            .toList();
    }

    @Override
    public List<UserActionLog> findByUserIdAndActionType(Long userId, ActionType actionType) {
        if (userId == null || actionType == null) {
            return new ArrayList<>();
        }
        return logs.values().stream()
            .filter(log -> userId.equals(log.getUserId()) && actionType == log.getActionType())
            .toList();
    }
}
