package com.campushub.backend.auth.repository;

import com.campushub.backend.auth.domain.User;
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
public class InMemoryUserRepository implements UserRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, User> users = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByStudentId(String studentId) {
        return users.values().stream()
            .filter(user -> user.getStudentId().equals(studentId))
            .findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return users.values().stream()
            .filter(user -> user.getEmail().equals(email))
            .findFirst();
    }

    @Override
    public Optional<User> findByLoginId(String loginId) {
        return users.values().stream()
            .filter(user -> user.getEmail().equals(loginId) || user.getStudentId().equals(loginId))
            .findFirst();
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(users.values());
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            user.setId(sequence.getAndIncrement());
        }
        users.put(user.getId(), user);
        return user;
    }
}
