package com.campushub.backend.notification.repository;

import com.campushub.backend.notification.domain.Notification;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
public class InMemoryNotificationRepository implements NotificationRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, Notification> notifications = new ConcurrentHashMap<>();

    @Override
    public Notification save(Notification notification) {
        if (notification.getId() == null) {
            notification.setId(sequence.getAndIncrement());
        }
        notifications.put(notification.getId(), notification);
        return notification;
    }

    @Override
    public Optional<Notification> findById(Long notificationId) {
        return Optional.ofNullable(notifications.get(notificationId));
    }

    @Override
    public List<Notification> findByUserId(Long userId) {
        List<Notification> results = new ArrayList<>();
        for (Notification notification : notifications.values()) {
            if (notification.getUserId().equals(userId)) {
                results.add(notification);
            }
        }
        return results;
    }
}
