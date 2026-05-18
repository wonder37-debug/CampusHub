package com.campushub.backend.auth.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MyBatisUserRepository} 的切片测试。
 *
 * <p>使用 MyBatis-Plus 官方提供的 {@code @MybatisPlusTest}（功能等价于 Spring Boot 的
 * {@code @MybatisTest}，但能正确装配 MyBatis-Plus 的 SqlSessionFactory），
 * 走 H2 内存库 + classpath:schema.sql。</p>
 */
@MybatisPlusTest
@ActiveProfiles("local") // 激活 local 环境，使得带有 @Profile("local") 的 Repository 能被扫描到
@Import(MyBatisUserRepository.class) // 强制导入我们自己写的 Repository 实现类
class MyBatisUserRepositoryTest {

    @Autowired
    private MyBatisUserRepository repository;

    @Autowired
    private UserMapper userMapper;

    @Test
    void save_insert_assigns_id_and_findById_returns_persisted_user() {
        User user = newUser("alice@campus.edu", "2026001");

        User saved = repository.save(user);

        assertThat(saved.getId()).isNotNull();
        Optional<User> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getEmail()).isEqualTo("alice@campus.edu");
        assertThat(loaded.get().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void save_update_when_id_present_changes_fields_in_place() {
        User user = repository.save(newUser("bob@campus.edu", "2026002"));

        user.setNickname("Bob-Updated");
        user.setCreditScore(88);
        repository.save(user);

        User reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("Bob-Updated");
        assertThat(reloaded.getCreditScore()).isEqualTo(88);
        // 仅一条记录，更新而非插入。
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void findByStudentId_and_findByEmail_return_empty_when_missing() {
        assertThat(repository.findByStudentId("not-exist")).isEmpty();
        assertThat(repository.findByEmail("nobody@campus.edu")).isEmpty();
        assertThat(repository.findById(9999L)).isEmpty();
    }

    @Test
    void findByLoginId_matches_either_email_or_student_id() {
        repository.save(newUser("carol@campus.edu", "2026003"));

        Optional<User> byEmail = repository.findByLoginId("carol@campus.edu");
        Optional<User> byStudentId = repository.findByLoginId("2026003");
        Optional<User> miss = repository.findByLoginId("ghost");

        assertThat(byEmail).isPresent();
        assertThat(byStudentId).isPresent();
        assertThat(byEmail.get().getId()).isEqualTo(byStudentId.get().getId());
        assertThat(miss).isEmpty();
    }

    @Test
    void findAll_returns_empty_list_not_null() {
        List<User> all = repository.findAll();
        assertThat(all).isNotNull().isEmpty();
    }

    @Test
    void findByStatus_returns_users_with_matching_status() {
        repository.save(newUser("active@test.edu.cn", "20261001", UserRole.USER, UserStatus.ACTIVE));
        repository.save(newUser("banned@test.edu.cn", "20261002", UserRole.USER, UserStatus.BANNED));

        List<User> activeUsers = repository.findByStatus(UserStatus.ACTIVE);

        assertThat(activeUsers).hasSize(1);
        assertThat(activeUsers.get(0).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(repository.findByStatus(null)).isEmpty();
    }

    @Test
    void findByRole_returns_users_with_matching_role() {
        repository.save(newUser("admin@test.edu.cn", "ADM001", UserRole.ADMIN, UserStatus.ACTIVE));
        repository.save(newUser("user@test.edu.cn", "20261003", UserRole.USER, UserStatus.ACTIVE));

        List<User> admins = repository.findByRole(UserRole.ADMIN);

        assertThat(admins).hasSize(1);
        assertThat(admins.get(0).getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(repository.findByRole(null)).isEmpty();
    }

    @Test
    void duplicate_email_triggers_DataIntegrityViolationException() {
        repository.save(newUser("dup@campus.edu", "2026010"));

        assertThatThrownBy(() -> repository.save(newUser("dup@campus.edu", "2026011")))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicate_student_id_triggers_DataIntegrityViolationException() {
        repository.save(newUser("a@campus.edu", "2026020"));

        assertThatThrownBy(() -> repository.save(newUser("b@campus.edu", "2026020")))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void mapper_is_wired_and_isolated_per_test() {
        // 切片测试每个用例独立事务回滚，count 应为 0。
        assertThat(userMapper.selectCount(null)).isZero();
    }

    private static User newUser(String email, String studentId) {
        return newUser(email, studentId, UserRole.USER, UserStatus.ACTIVE);
    }

    private static User newUser(String email, String studentId, UserRole role, UserStatus status) {
        User user = new User();
        user.setEmail(email);
        user.setStudentId(studentId);
        user.setPasswordHash("$2a$10$dummyBcryptHashForTestUseOnly.................");
        user.setNickname("tester");
        user.setRole(role);
        user.setStatus(status);
        user.setCreditScore(100);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
