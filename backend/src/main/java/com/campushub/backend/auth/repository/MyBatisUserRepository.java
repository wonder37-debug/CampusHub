package com.campushub.backend.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.entity.UserEntity;
import com.campushub.backend.auth.repository.mapper.UserMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 {@link UserRepository} 实现。
 *
 * <p>仅在 {@code local} profile 下激活，避免与默认的内存仓储冲突；
 * 实现严格遵循 {@code P4-数据库接口调用规范.md} 中对 DAO 层的契约：
 * 不在 DAO 层抛业务异常、查不到返回 {@link Optional#empty()}、
 * 唯一约束冲突由底层异常向上传递。</p>
 */
@Repository
@Profile("local")
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
}
