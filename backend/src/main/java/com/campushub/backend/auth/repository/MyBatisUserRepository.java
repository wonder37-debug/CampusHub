package com.campushub.backend.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserQueryCriteria;
import com.campushub.backend.auth.repository.entity.UserEntity;
import com.campushub.backend.auth.repository.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 {@link UserRepository} 实现。
 *
 * <p>默认仓储实现；实现严格遵循 {@code P4-数据库接口调用规范.md} 中对 DAO 层的契约：
 * 不在 DAO 层抛业务异常、查不到返回 {@link Optional#empty()}、
 * 唯一约束冲突由底层异常向上传递。</p>
 */
@Repository
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper userMapper;

    public MyBatisUserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Optional<User> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        UserEntity entity = userMapper.selectById(id);
        return Optional.ofNullable(entity).map(UserEntity::toDomain);
    }

    @Override
    public List<User> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(ids).stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public Optional<User> findByStudentId(String studentId) {
        if (studentId == null || studentId.isEmpty()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getStudentId, studentId)
            .last("LIMIT 1");
        UserEntity entity = userMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(UserEntity::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isEmpty()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getEmail, email)
            .last("LIMIT 1");
        UserEntity entity = userMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(UserEntity::toDomain);
    }

    @Override
    public Optional<User> findByLoginId(String loginId) {
        if (loginId == null || loginId.isEmpty()) {
            return Optional.empty();
        }
        // 同时按 email 或 student_id 匹配，对应规范 §5.1 的"登录标识允许邮箱或学号"语义。
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getEmail, loginId)
            .or()
            .eq(UserEntity::getStudentId, loginId)
            .last("LIMIT 1");
        UserEntity entity = userMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(UserEntity::toDomain);
    }

    @Override
    public boolean existsByNicknameIgnoreCase(String nickname, Long excludeUserId) {
        if (nickname == null || nickname.isBlank()) {
            return false;
        }
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
            .apply("LOWER(nickname) = LOWER({0})", nickname.trim());
        if (excludeUserId != null) {
            wrapper.ne(UserEntity::getId, excludeUserId);
        }
        wrapper.last("LIMIT 1");
        return userMapper.selectCount(wrapper) > 0;
    }

    @Override
    public List<User> findAll() {
        List<UserEntity> entities = userMapper.selectList(null);
        return entities.stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public List<User> findByStatus(UserStatus status) {
        if (status == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getStatus, status.name());
        return userMapper.selectList(wrapper).stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public List<User> findByRole(UserRole role) {
        if (role == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getRole, role.name());
        return userMapper.selectList(wrapper).stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public List<User> findPage(UserQueryCriteria criteria) {
        if (criteria == null) {
            return List.of();
        }
        LambdaQueryWrapper<UserEntity> wrapper = buildWrapper(criteria);
        applyUserSort(wrapper, criteria.sortBy(), criteria.sortDirection());
        int size = criteria.pageQuery().size();
        long offset = (long) (criteria.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return userMapper.selectList(wrapper).stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public long count(UserQueryCriteria criteria) {
        if (criteria == null) {
            return 0L;
        }
        return userMapper.selectCount(buildWrapper(criteria));
    }

    private LambdaQueryWrapper<UserEntity> buildWrapper(UserQueryCriteria criteria) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        String q = criteria.q();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            String field = criteria.searchField();
            if (field == null || field.isBlank()) {
                wrapper.and(w -> w.like(UserEntity::getNickname, keyword)
                    .or().like(UserEntity::getEmail, keyword)
                    .or().like(UserEntity::getStudentId, keyword));
            } else {
                switch (field.toLowerCase(Locale.ROOT)) {
                    case "nickname" -> wrapper.like(UserEntity::getNickname, keyword);
                    case "email" -> wrapper.like(UserEntity::getEmail, keyword);
                    case "studentid", "student_id" -> wrapper.like(UserEntity::getStudentId, keyword);
                    default -> wrapper.and(w -> w.like(UserEntity::getNickname, keyword)
                        .or().like(UserEntity::getEmail, keyword)
                        .or().like(UserEntity::getStudentId, keyword));
                }
            }
        }
        String role = criteria.role();
        if (role != null && !role.isBlank()) {
            wrapper.eq(UserEntity::getRole, role.trim().toUpperCase(Locale.ROOT));
        }
        String status = criteria.status();
        if (status != null && !status.isBlank()) {
            wrapper.eq(UserEntity::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        return wrapper;
    }

    private void applyUserSort(LambdaQueryWrapper<UserEntity> wrapper, String sortBy, String sortDirection) {
        boolean descending = sortDirection == null || sortDirection.isBlank()
            || !"asc".equalsIgnoreCase(sortDirection.trim());
        String resolvedSortBy = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        switch (resolvedSortBy) {
            case "creditscore", "credit_score" -> {
                if (descending) wrapper.orderByDesc(UserEntity::getCreditScore);
                else wrapper.orderByAsc(UserEntity::getCreditScore);
            }
            case "nickname" -> {
                if (descending) wrapper.orderByDesc(UserEntity::getNickname);
                else wrapper.orderByAsc(UserEntity::getNickname);
            }
            case "createdat", "created_at" -> {
                if (descending) wrapper.orderByDesc(UserEntity::getCreatedAt);
                else wrapper.orderByAsc(UserEntity::getCreatedAt);
            }
            default -> {
                if (descending) wrapper.orderByDesc(UserEntity::getCreatedAt);
                else wrapper.orderByAsc(UserEntity::getCreatedAt);
            }
        }
        wrapper.orderByAsc(UserEntity::getId);
    }

    @Override
    public User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        UserEntity entity = UserEntity.fromDomain(user);
        if (user.getId() == null) {
            userMapper.insert(entity);
            user.setId(entity.getId());
        } else {
            userMapper.updateById(entity);
        }
        return user;
    }

    @Override
    public long count() {
        return userMapper.selectCount(null);
    }

    @Override
    public boolean freezeBalance(Long userId, BigDecimal amount) {
        if (userId == null || !isPositiveAmount(amount)) {
            return false;
        }
        return userMapper.freezeBalance(userId, amount) > 0;
    }

    @Override
    public boolean unfreezeBalance(Long userId, BigDecimal amount) {
        if (userId == null || !isPositiveAmount(amount)) {
            return false;
        }
        return userMapper.unfreezeBalance(userId, amount) > 0;
    }

    @Override
    public boolean addBalance(Long userId, BigDecimal amount) {
        if (userId == null || !isPositiveAmount(amount)) {
            return false;
        }
        return userMapper.addBalance(userId, amount) > 0;
    }

    @Override
    public boolean deductBalance(Long userId, BigDecimal amount) {
        if (userId == null || !isPositiveAmount(amount)) {
            return false;
        }
        return userMapper.deductBalance(userId, amount) > 0;
    }

    private static boolean isPositiveAmount(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
}
