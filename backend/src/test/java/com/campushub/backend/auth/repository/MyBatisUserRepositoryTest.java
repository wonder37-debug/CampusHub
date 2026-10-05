package com.campushub.backend.auth.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserQueryCriteria;
import com.campushub.backend.auth.repository.mapper.UserMapper;
import com.campushub.backend.common.model.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
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
        LocalDateTime updatedAt = LocalDateTime.of(2026, 5, 18, 21, 30);
        user.setUpdatedAt(updatedAt);
        repository.save(user);

        User reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("Bob-Updated");
        assertThat(reloaded.getCreditScore()).isEqualTo(88);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
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

    @Test
    void findPage_returns_all_users_sorted_by_created_desc_with_id_asc_tiebreaker() {
        User older = repository.save(newUserWithCreated("a1@campus.edu", "2026001", LocalDateTime.now().minusMinutes(10)));
        User newer = repository.save(newUserWithCreated("a2@campus.edu", "2026002", LocalDateTime.now().minusMinutes(1)));
        User sameInstant = repository.save(newUserWithCreated("a3@campus.edu", "2026003", newer.getCreatedAt()));

        List<User> page = repository.findPage(new UserQueryCriteria(null, null, null, null, null, null, new PageQuery(1, 20)));

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.get(1).getId()).isEqualTo(sameInstant.getId());
        assertThat(repository.count(new UserQueryCriteria(null, null, null, null, null, null, new PageQuery(1, 20)))).isEqualTo(3L);
    }

    @Test
    void findPage_keyword_without_search_field_matches_nickname_or_email_or_student_id() {
        User byNickname = repository.save(newUser("nick-test@campus.edu", "2026101"));
        byNickname.setNickname("张三丰");
        repository.save(byNickname);
        User byEmail = repository.save(newUser("alice-test@campus.edu", "2026102"));
        User byStudentId = repository.save(newUser("bob@campus.edu", "2026103-special"));
        User noMatch = repository.save(newUser("carol@campus.edu", "2026104"));
        noMatch.setNickname("carol");
        repository.save(noMatch);

        List<User> page = repository.findPage(new UserQueryCriteria("test", null, null, null, null, null, new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactlyInAnyOrder(byNickname.getId(), byEmail.getId(), byStudentId.getId());
        assertThat(repository.count(new UserQueryCriteria("test", null, null, null, null, null, new PageQuery(1, 20)))).isEqualTo(3L);
    }

    @Test
    void findPage_keyword_with_search_field_nickname_only() {
        User byNickname = repository.save(newUser("a1@campus.edu", "2026201"));
        byNickname.setNickname("张三丰-feng");
        repository.save(byNickname);
        User byEmail = repository.save(newUser("feng-test@campus.edu", "2026202"));

        List<User> page = repository.findPage(new UserQueryCriteria("feng", "nickname", null, null, null, null, new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactly(byNickname.getId());
    }

    @Test
    void findPage_keyword_with_search_field_email_or_student_id_variants() {
        User byEmail = repository.save(newUser("alice@campus.edu", "2026301"));
        User byStudentId = repository.save(newUser("bob@campus.edu", "2026302-studentid"));

        List<User> byEmailField = repository.findPage(new UserQueryCriteria("alice", "email", null, null, null, null, new PageQuery(1, 20)));
        assertThat(byEmailField).extracting(User::getId).containsExactly(byEmail.getId());

        List<User> byStudentIdCamel = repository.findPage(new UserQueryCriteria("studentid", "studentId", null, null, null, null, new PageQuery(1, 20)));
        assertThat(byStudentIdCamel).extracting(User::getId).containsExactly(byStudentId.getId());

        List<User> byStudentIdSnake = repository.findPage(new UserQueryCriteria("studentid", "student_id", null, null, null, null, new PageQuery(1, 20)));
        assertThat(byStudentIdSnake).extracting(User::getId).containsExactly(byStudentId.getId());
    }

    @Test
    void findPage_keyword_with_unknown_search_field_degrades_to_three_field_or() {
        User byNickname = repository.save(newUser("a1@campus.edu", "2026401"));
        byNickname.setNickname("张三丰-feng");
        repository.save(byNickname);
        User byEmail = repository.save(newUser("feng-test@campus.edu", "2026402"));

        List<User> page = repository.findPage(new UserQueryCriteria("feng", "unknownfield", null, null, null, null, new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactlyInAnyOrder(byNickname.getId(), byEmail.getId());
    }

    @Test
    void findPage_filters_by_role_case_insensitive() {
        repository.save(newUser("admin@campus.edu", "2026501", UserRole.ADMIN, UserStatus.ACTIVE));
        User userRole = repository.save(newUser("user@campus.edu", "2026502", UserRole.USER, UserStatus.ACTIVE));

        List<User> page = repository.findPage(new UserQueryCriteria(null, null, "user", null, null, null, new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactly(userRole.getId());
        assertThat(repository.count(new UserQueryCriteria(null, null, "user", null, null, null, new PageQuery(1, 20)))).isEqualTo(1L);
    }

    @Test
    void findPage_filters_by_status_case_insensitive() {
        User active = repository.save(newUser("active@campus.edu", "2026601", UserRole.USER, UserStatus.ACTIVE));
        repository.save(newUser("banned@campus.edu", "2026602", UserRole.USER, UserStatus.BANNED));

        List<User> page = repository.findPage(new UserQueryCriteria(null, null, null, "active", null, null, new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactly(active.getId());
        assertThat(repository.count(new UserQueryCriteria(null, null, null, "active", null, null, new PageQuery(1, 20)))).isEqualTo(1L);
    }

    @Test
    void findPage_sorts_by_credit_score_desc_with_id_asc_tiebreaker() {
        User low = repository.save(newUserWithCredit("low@campus.edu", "2026701", 60));
        User high = repository.save(newUserWithCredit("high@campus.edu", "2026702", 95));
        User mid = repository.save(newUserWithCredit("mid@campus.edu", "2026703", 95));
        repository.save(newUserWithCredit("zero@campus.edu", "2026704", 0));

        List<User> page = repository.findPage(new UserQueryCriteria(null, null, null, null, "creditScore", "desc", new PageQuery(1, 20)));

        assertThat(page).extracting(User::getCreditScore).containsExactly(95, 95, 60, 0);
        assertThat(page.get(0).getId()).isEqualTo(high.getId());
        assertThat(page.get(1).getId()).isEqualTo(mid.getId());
    }

    @Test
    void findPage_sorts_by_credit_score_asc() {
        repository.save(newUserWithCredit("low@campus.edu", "2026801", 60));
        repository.save(newUserWithCredit("high@campus.edu", "2026802", 95));
        repository.save(newUserWithCredit("mid@campus.edu", "2026803", 0));

        List<User> page = repository.findPage(new UserQueryCriteria(null, null, null, null, "creditScore", "asc", new PageQuery(1, 20)));

        assertThat(page).extracting(User::getCreditScore).containsExactly(0, 60, 95);
    }

    @Test
    void findPage_sorts_by_nickname_desc_and_asc() {
        User a = repository.save(newUser("a@campus.edu", "2026901"));
        a.setNickname("alpha");
        repository.save(a);
        User b = repository.save(newUser("b@campus.edu", "2026902"));
        b.setNickname("beta");
        repository.save(b);
        User c = repository.save(newUser("c@campus.edu", "2026903"));
        c.setNickname("gamma");
        repository.save(c);

        List<User> desc = repository.findPage(new UserQueryCriteria(null, null, null, null, "nickname", "desc", new PageQuery(1, 20)));
        assertThat(desc).extracting(User::getNickname).containsExactly("gamma", "beta", "alpha");

        List<User> asc = repository.findPage(new UserQueryCriteria(null, null, null, null, "nickname", "asc", new PageQuery(1, 20)));
        assertThat(asc).extracting(User::getNickname).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void findPage_unknown_sort_by_defaults_to_created_desc() {
        User older = repository.save(newUserWithCreated("a@campus.edu", "20261001", LocalDateTime.now().minusMinutes(10)));
        User newer = repository.save(newUserWithCreated("b@campus.edu", "20261002", LocalDateTime.now().minusMinutes(1)));

        List<User> page = repository.findPage(new UserQueryCriteria(null, null, null, null, "unknownfield", "desc", new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void findPage_null_or_blank_sort_direction_defaults_to_desc() {
        User older = repository.save(newUserWithCreated("a@campus.edu", "20261101", LocalDateTime.now().minusMinutes(10)));
        User newer = repository.save(newUserWithCreated("b@campus.edu", "20261102", LocalDateTime.now().minusMinutes(1)));

        List<User> nullDir = repository.findPage(new UserQueryCriteria(null, null, null, null, "createdAt", null, new PageQuery(1, 20)));
        assertThat(nullDir).extracting(User::getId).containsExactly(newer.getId(), older.getId());

        List<User> blankDir = repository.findPage(new UserQueryCriteria(null, null, null, null, "createdAt", "  ", new PageQuery(1, 20)));
        assertThat(blankDir).extracting(User::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void findPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newUserWithCreated("u" + i + "@campus.edu", "2026" + (1200 + i), LocalDateTime.now().minusMinutes(5 - i)));
        }
        UserQueryCriteria page1 = new UserQueryCriteria(null, null, null, null, null, null, new PageQuery(1, 2));
        UserQueryCriteria page2 = new UserQueryCriteria(null, null, null, null, null, null, new PageQuery(2, 2));
        UserQueryCriteria page3 = new UserQueryCriteria(null, null, null, null, null, null, new PageQuery(3, 2));

        assertThat(repository.findPage(page1)).hasSize(2);
        assertThat(repository.findPage(page2)).hasSize(2);
        assertThat(repository.findPage(page3)).hasSize(1);
        assertThat(repository.count(page1)).isEqualTo(5L);
    }

    @Test
    void findPage_and_count_return_empty_or_zero_when_criteria_null() {
        assertThat(repository.findPage(null)).isEmpty();
        assertThat(repository.count(null)).isEqualTo(0L);
    }

    @Test
    void count_returns_zero_when_empty() {
        assertThat(repository.count()).isZero();
    }

    @Test
    void count_returns_total_after_inserts() {
        repository.save(newUser("a@campus.edu", "2026001"));
        repository.save(newUser("b@campus.edu", "2026002"));
        repository.save(newUser("c@campus.edu", "2026003"));

        assertThat(repository.count()).isEqualTo(3L);
    }

    @Test
    void findAllById_returns_matching_users() {
        User u1 = repository.save(newUser("a@campus.edu", "2026001"));
        User u2 = repository.save(newUser("b@campus.edu", "2026002"));
        repository.save(newUser("c@campus.edu", "2026003"));

        List<User> result = repository.findAllById(List.of(u1.getId(), u2.getId()));

        assertThat(result).extracting(User::getId).containsExactlyInAnyOrder(u1.getId(), u2.getId());
    }

    @Test
    void findAllById_handles_null_and_empty() {
        assertThat(repository.findAllById(null)).isEmpty();
        assertThat(repository.findAllById(List.of())).isEmpty();
    }

    @Test
    void freezeBalance_increments_frozen_when_available_balance_sufficient() {
        User user = repository.save(newUserWithBalance("freeze@campus.edu", "20268001", "100.00", "10.00"));

        boolean result = repository.freezeBalance(user.getId(), new BigDecimal("30.00"));

        assertThat(result).isTrue();
        User reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getFrozenBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    void freezeBalance_returns_false_and_keeps_state_when_balance_insufficient() {
        User user = repository.save(newUserWithBalance("insuff@campus.edu", "20268002", "50.00", "40.00"));

        boolean result = repository.freezeBalance(user.getId(), new BigDecimal("20.00"));

        assertThat(result).isFalse();
        User reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("50.00");
        assertThat(reloaded.getFrozenBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    void freezeBalance_returns_false_for_non_positive_amount_or_null_user() {
        User user = repository.save(newUserWithBalance("zero@campus.edu", "20268003", "100.00", "0.00"));

        assertThat(repository.freezeBalance(user.getId(), BigDecimal.ZERO)).isFalse();
        assertThat(repository.freezeBalance(user.getId(), new BigDecimal("-5.00"))).isFalse();
        assertThat(repository.freezeBalance(user.getId(), null)).isFalse();
        assertThat(repository.freezeBalance(null, new BigDecimal("5.00"))).isFalse();
        assertThat(repository.freezeBalance(9999L, new BigDecimal("5.00"))).isFalse();

        assertThat(repository.findById(user.getId()).orElseThrow().getFrozenBalance())
            .isEqualByComparingTo("0.00");
    }

    @Test
    void unfreezeBalance_decrements_frozen_when_sufficient() {
        User user = repository.save(newUserWithBalance("unfreeze@campus.edu", "20268004", "100.00", "40.00"));

        boolean result = repository.unfreezeBalance(user.getId(), new BigDecimal("15.00"));

        assertThat(result).isTrue();
        User reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getFrozenBalance()).isEqualByComparingTo("25.00");
    }

    @Test
    void unfreezeBalance_returns_false_when_frozen_insufficient() {
        User user = repository.save(newUserWithBalance("unf-insuff@campus.edu", "20268005", "100.00", "10.00"));

        boolean result = repository.unfreezeBalance(user.getId(), new BigDecimal("30.00"));

        assertThat(result).isFalse();
        assertThat(repository.findById(user.getId()).orElseThrow().getFrozenBalance())
            .isEqualByComparingTo("10.00");
    }

    @Test
    void addBalance_increments_balance_unconditionally() {
        User user = repository.save(newUserWithBalance("add@campus.edu", "20268006", "100.00", "0.00"));

        boolean result = repository.addBalance(user.getId(), new BigDecimal("33.50"));

        assertThat(result).isTrue();
        User reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("133.50");
        assertThat(reloaded.getFrozenBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void deductBalance_decrements_balance_when_sufficient() {
        User user = repository.save(newUserWithBalance("deduct@campus.edu", "20268007", "100.00", "0.00"));

        boolean result = repository.deductBalance(user.getId(), new BigDecimal("40.00"));

        assertThat(result).isTrue();
        assertThat(repository.findById(user.getId()).orElseThrow().getBalance())
            .isEqualByComparingTo("60.00");
    }

    @Test
    void deductBalance_returns_false_when_balance_insufficient() {
        User user = repository.save(newUserWithBalance("deduct-insuff@campus.edu", "20268008", "30.00", "0.00"));

        boolean result = repository.deductBalance(user.getId(), new BigDecimal("40.00"));

        assertThat(result).isFalse();
        assertThat(repository.findById(user.getId()).orElseThrow().getBalance())
            .isEqualByComparingTo("30.00");
    }

    @Test
    void atomic_operations_are_independent_per_call_and_do_not_leak_state() {
        User publisher = repository.save(newUserWithBalance("atom-pub@campus.edu", "20268009", "100.00", "0.00"));
        User accepter = repository.save(newUserWithBalance("atom-acc@campus.edu", "20268010", "50.00", "0.00"));

        assertThat(repository.freezeBalance(publisher.getId(), new BigDecimal("30.00"))).isTrue();
        assertThat(repository.deductBalance(publisher.getId(), new BigDecimal("30.00"))).isTrue();
        assertThat(repository.unfreezeBalance(publisher.getId(), new BigDecimal("30.00"))).isTrue();
        assertThat(repository.addBalance(accepter.getId(), new BigDecimal("30.00"))).isTrue();

        User pubReloaded = repository.findById(publisher.getId()).orElseThrow();
        User accReloaded = repository.findById(accepter.getId()).orElseThrow();
        assertThat(pubReloaded.getBalance()).isEqualByComparingTo("70.00");
        assertThat(pubReloaded.getFrozenBalance()).isEqualByComparingTo("0.00");
        assertThat(accReloaded.getBalance()).isEqualByComparingTo("80.00");
        assertThat(accReloaded.getFrozenBalance()).isEqualByComparingTo("0.00");
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

    private static User newUserWithCreated(String email, String studentId, LocalDateTime createdAt) {
        User user = newUser(email, studentId);
        user.setCreatedAt(createdAt);
        return user;
    }

    private static User newUserWithCredit(String email, String studentId, int creditScore) {
        User user = newUser(email, studentId);
        user.setCreditScore(creditScore);
        return user;
    }

    private static User newUserWithBalance(String email, String studentId, String balance, String frozen) {
        User user = newUser(email, studentId);
        user.setBalance(new BigDecimal(balance));
        user.setFrozenBalance(new BigDecimal(frozen));
        return user;
    }
}
