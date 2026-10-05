# 仓储层统一 2B.3a（admin listUsers SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AdminApplicationServiceImpl.listUsers` 的 `findAll` + 3 道内存过滤（keyword+searchField / role / status）+ 内存排序（creditScore/nickname/createdAt + asc/desc + id tie-breaker）+ `subList` 分页下沉为 `UserRepository.findPage` / `count` 的 SQL（`LambdaQueryWrapper` 过滤 + `orderBy` + `LIMIT/OFFSET` + `selectCount`），并新建 `auth.dto.UserQueryCriteria` 解决 auth 仓储依赖 admin DTO 的层次反转，行为零回归。

**Architecture:** 新建 `auth.dto.UserQueryCriteria` record（字段与 `AdminUserQuery` 逐字对齐）；`UserRepository` 新增 `findPage(UserQueryCriteria)` + `count(UserQueryCriteria)`（风格 A，与 2B.1/2B.2 一致）；`MyBatisUserRepository` 提取私有 `buildWrapper(criteria)`（keyword+searchField 分支 + role/status `eq`，供 `findPage`/`count` 共用）+ `applyUserSort(wrapper, sortBy, sortDirection)`（主列 asc/desc + id ASC tie-breaker）；Service `listUsers` 内 `AdminUserQuery → UserQueryCriteria` 映射后调新方法，删除 4 个仅服务于旧实现的私有方法（`matchesUserKeyword`/`matchesUserRole`/`matchesUserStatus`/`resolveUserComparator`）。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b3a-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `UserProfileResponse` / `UserSummaryView` 字段结构不变；`AdminController` 转换不变；API 行为不变。
- **测试基线 151/151 不回归**（2B.1/2B.2 + CodeRabbit fix 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；push 到 PR #10 延续授权模式；不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致；LIKE 依赖默认 ci collation。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `auth/dto/UserQueryCriteria.java` | 查询条件 record（auth 层） | **新建**（字段同 `AdminUserQuery`，紧凑构造器同） |
| `auth/repository/UserRepository.java` | 仓储接口 | 加 `findPage(UserQueryCriteria)` + `count(UserQueryCriteria)`（其余方法保留） |
| `auth/repository/MyBatisUserRepository.java` | MyBatis 实现 | 实现 `findPage` + `count` + 私有 `buildWrapper` + `applyUserSort` |
| `admin/service/AdminApplicationServiceImpl.java` | 应用服务 | `listUsers` 改用新方法 + `AdminUserQuery→UserQueryCriteria` 映射；删 4 私有方法；加 `UserQueryCriteria` import |
| `auth/repository/MyBatisUserRepositoryTest.java` | Repository 切片测试 | 新增 14 个 `findPage`/`count` 用例 + 2 个辅助工厂 |

---

### Task 1: 新建 UserQueryCriteria + UserRepository 扩展 findPage/count + MyBatisUserRepository 实现

**Files:**
- Create: `backend/src/main/java/com/campushub/backend/auth/dto/UserQueryCriteria.java`
- Modify: `backend/src/main/java/com/campushub/backend/auth/repository/UserRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/auth/repository/MyBatisUserRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/auth/repository/MyBatisUserRepositoryTest.java`

**Interfaces:**
- Consumes: `AdminUserQuery`（`admin.dto`，record `(String q, String searchField, String role, String status, String sortBy, String sortDirection, PageQuery pageQuery)`，紧凑构造器规范化各字段）、`PageQuery`（`common.model`，record `(int page, int size)`，构造校验 `page>=1`、`1<=size<=100`、`page<=1000`，常量 `DEFAULT_PAGE=1`/`DEFAULT_SIZE=20`）、`UserEntity`（`@TableName("sys_user")`，字段 `id`(Long)/`email`(String)/`studentId`(String, 列 `student_id`)/`nickname`(String)/`role`(String)/`status`(String)/`creditScore`(Integer, 列 `credit_score`)/`createdAt`(LocalDateTime, 列 `created_at`)）、`UserMapper extends BaseMapper<UserEntity>`（`selectList`/`selectCount`）。
- Produces: `UserQueryCriteria` record（auth 层查询条件）、`UserRepository.findPage(UserQueryCriteria) -> List<User>`、`UserRepository.count(UserQueryCriteria) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（14 个 findPage/count 用例 + 2 个辅助工厂）**

在 `MyBatisUserRepositoryTest.java` 类体内末尾（`newUser` 工厂方法之前）追加测试方法，并在 `newUser(email, studentId, role, status)` 工厂之后追加 2 个辅助工厂。

测试方法（追加到类体内）：

```java
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

        List<User> page = repository.findPage(new UserQueryCriteria("test", null, null, null, null, null, new PageQuery(1, 20)));

        assertThat(page).extracting(User::getId).containsExactlyInAnyOrder(byNickname.getId(), byEmail.getId(), byStudentId.getId());
        assertThat(repository.count(new UserQueryCriteria("test", null, null, null, null, null, new PageQuery(1, 20)))).isEqualTo(3L);
    }

    @Test
    void findPage_keyword_with_search_field_nickname_only() {
        User byNickname = repository.save(newUser("a1@campus.edu", "2026201"));
        byNickname.setNickname("张三丰");
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
        byNickname.setNickname("张三丰");
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
```

辅助工厂（追加到 `newUser(email, studentId, role, status)` 之后）：

```java
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
```

补充 import（在现有 import 区追加，保持顺序）：

```java
import com.campushub.backend.auth.dto.UserQueryCriteria;
import com.campushub.backend.common.model.PageQuery;
```

> 现有 `newUser(email, studentId)` 默认 `creditScore=100`/`nickname="tester"`/`createdAt=LocalDateTime.now()`。`newUserWithCreated`/`newUserWithCredit` 在其基础上覆写单字段。测试中需不同 `nickname` 的用例在 `save` 后 `setNickname` 再 `save`（updateById 路径）。

- [ ] **Step 2: 跑测试确认失败（编译错）**

设置 JDK 21（单独一条命令）：

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

跑 Repository 测试（workdir=`D:\workspace\sec-ii-2026\backend`，单独一条）：

```
.\mvnw.cmd test -Dtest=MyBatisUserRepositoryTest
```

Expected: 编译失败，`UserQueryCriteria` 不存在，`UserRepository` 无 `findPage`/`count` 方法。

- [ ] **Step 3: 新建 UserQueryCriteria record**

创建 `backend/src/main/java/com/campushub/backend/auth/dto/UserQueryCriteria.java`：

```java
package com.campushub.backend.auth.dto;

import com.campushub.backend.common.model.PageQuery;

public record UserQueryCriteria(
    String q,
    String searchField,
    String role,
    String status,
    String sortBy,
    String sortDirection,
    PageQuery pageQuery
) {

    public UserQueryCriteria {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
        searchField = searchField == null ? null : searchField.trim();
        role = role == null ? null : role.trim();
        status = status == null ? null : status.trim();
        sortBy = sortBy == null ? null : sortBy.trim();
        sortDirection = sortDirection == null ? null : sortDirection.trim();
    }
}
```

- [ ] **Step 4: 加 UserRepository 接口方法**

`UserRepository.java` 在 `findByRole` 之后、`save` 之前追加（其余方法保留不动）：

```java
    /**
     * 按查询条件分页查询用户（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param criteria 查询条件，为 null 时返回空列表
     */
    List<User> findPage(UserQueryCriteria criteria);

    /**
     * 按查询条件统计匹配的用户总数（过滤下推 SQL，用于分页 total）。
     *
     * @param criteria 查询条件，为 null 时返回 0
     */
    long count(UserQueryCriteria criteria);
```

并在 import 区追加（保持字母序）：

```java
import com.campushub.backend.auth.dto.UserQueryCriteria;
```

- [ ] **Step 5: 实现 MyBatisUserRepository 的 findPage/count/buildWrapper/applyUserSort**

在 `MyBatisUserRepository.java` 的 `findByRole` 方法之后追加实现：

```java
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
```

在 import 区追加（保持字母序，与现有风格一致）：

```java
import com.campushub.backend.auth.dto.UserQueryCriteria;
import java.util.Locale;
```

- [ ] **Step 6: 跑 Repository 测试确认通过**

确保 `$env:JAVA_HOME` 已设（若新 session 先执行 Step 2 的设置命令）。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -Dtest=MyBatisUserRepositoryTest
```

Expected: PASS，`MyBatisUserRepositoryTest` 全部用例（含原有 9 + 新增 14 = 23 个）绿。

- [ ] **Step 7: 跑全量测试确认无回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 ≥ 151 + 14（新增）= 165。`AdminApplicationServiceImplTest` 此刻仍用旧 `listUsers`（调 `findAll`），`findAll` 保留故仍绿。

> 若输出超 2000 行被截断写到 tool-output 文件，用 Grep 工具搜该文件确认无 `BUILD FAILURE` / `<<< FAILURE!` / `<<< ERROR!`，并搜 `Tests run:` 确认总数。

- [ ] **Step 8: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/auth/dto/UserQueryCriteria.java backend/src/main/java/com/campushub/backend/auth/repository/UserRepository.java backend/src/main/java/com/campushub/backend/auth/repository/MyBatisUserRepository.java backend/src/test/java/com/campushub/backend/auth/repository/MyBatisUserRepositoryTest.java
```

```
git commit -m "refactor(auth): push user list query down to SQL via UserQueryCriteria/findPage/count in UserRepository"
```

---

### Task 2: AdminApplicationServiceImpl.listUsers 改用 findPage/count + 清理私有方法

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:78-99`（`listUsers` 方法体）+ `:351-421`（删 4 私有方法）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `UserRepository.findPage(UserQueryCriteria) -> List<User>` + `UserRepository.count(UserQueryCriteria) -> long` + `UserQueryCriteria` record。
- Produces: `AdminApplicationService.listUsers(Long operatorId, AdminUserQuery query) -> PageResponse<UserProfileResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 listUsers 方法体**

替换 `AdminApplicationServiceImpl.java:78-99` 的 `listUsers` 方法为：

```java
    @Override
    public PageResponse<UserProfileResponse> listUsers(Long operatorId, AdminUserQuery query) {
        requireAdmin(operatorId);
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin user query must not be null");
        }

        UserQueryCriteria criteria = new UserQueryCriteria(
            query.q(), query.searchField(), query.role(), query.status(),
            query.sortBy(), query.sortDirection(), query.pageQuery());
        List<User> users = userRepository.findPage(criteria);
        List<UserProfileResponse> items = users.stream().map(UserProfileResponse::from).toList();
        long total = userRepository.count(criteria);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }
```

- [ ] **Step 2: 删除 4 个仅服务于旧 listUsers 的私有方法**

删除 `AdminApplicationServiceImpl.java` 中的以下私有方法（位于 `:351-421`，整体删除）：

- `matchesUserKeyword(User user, String keyword, String searchField)`（:351-369）
- `matchesUserRole(User user, String role)`（:393-398）
- `matchesUserStatus(User user, String status)`（:400-405）
- `resolveUserComparator(String sortBy, String sortDirection)`（:407-421）

> 删除前用 Grep 工具确认这 4 方法仅被旧 `listUsers` 调用：搜 `matchesUserKeyword|matchesUserRole|matchesUserStatus|resolveUserComparator` 在 `AdminApplicationServiceImpl.java`，命中应仅旧 `listUsers` 体内（:85-88）与各方法定义（:351/393/400/407）。`containsIgnoreCase`（:389）保留——仍被 `matchesDemandKeyword`（:376-378）用，属 2B.3b 范围。

- [ ] **Step 3: 清理/补充 import**

从 `AdminApplicationServiceImpl.java` import 区**删除前确认**（编译通过为准绳，逐项 grep 确认无其他引用）：

- `java.util.Comparator`：仍被 `listPendingDemands`（:164 `Comparator.comparing(...)`）与 `listArbitrationOrders`（:185 `Comparator.comparing(...)`）用，**保留**。
- `java.util.Locale`：仍被 `updateUserRole`（:137）/ `reviewDemand`（:210）/ `resolveOrderArbitration`（:262）用，**保留**。
- `com.campushub.backend.auth.domain.User`：仍被 `findUser`/`requireAdmin`/`banUser`/`transferReward` 与新 `listUsers`（`List<User> users`）用，**保留**。

故 import 区**仅新增**（不删）：

```java
import com.campushub.backend.auth.dto.UserQueryCriteria;
```

> 删除 4 私有方法后，若编译报某 import 失效再删；预期无失效（`Comparator`/`Locale`/`User` 均有其他引用）。

- [ ] **Step 4: 跑 Service 测试确认 listUsers 回归绿**

确保 `$env:JAVA_HOME` 已设。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -Dtest=AdminApplicationServiceImplTest
```

Expected: PASS，含 `shouldRejectNonAdminOperation`、`shouldListUsersByKeyword`、`shouldFilterAndSortUsersByRoleStatusAndCreditScore` 三个 `listUsers` 用例及 `banUser`/`reviewDemand`/`getDashboard`/`listPendingDemands`/`listArbitrationOrders` 等全绿。

- [ ] **Step 5: 跑全量测试确认不回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test
```

Expected: 全绿，测试数 = Task 1 Step 7 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 6: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java
```

```
git commit -m "refactor(admin): switch AdminApplicationServiceImpl.listUsers to SQL-pushed findPage/count and drop in-memory filters"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 新建 `UserQueryCriteria`（字段同 `AdminUserQuery`）→ Task 1 Step 3。✓
- §4.2 接口扩展（findPage/count 风格 A）→ Task 1 Step 4。✓
- §4.3 MyBatisUserRepository 实现（buildWrapper/applyUserSort/findPage/count）→ Task 1 Step 5。✓
- §4.4 过滤下沉映射（keyword+searchField 分支 / role eq+upper / status eq+upper）→ Task 1 Step 5 `buildWrapper`。✓
- §4.5 排序下沉（creditScore/nickname/createdAt + asc/desc + id ASC tie-breaker + NULL 边界）→ Task 1 Step 5 `applyUserSort`（id 用 `orderByAsc`）。✓
- §4.6 Service 改造（AdminUserQuery→UserQueryCriteria 映射 + findPage/count）→ Task 2 Step 1。✓
- §4.7 私有方法 + import 清理（删 4 方法；Comparator/Locale/User 保留；新增 UserQueryCriteria import）→ Task 2 Step 2-3。✓
- §4.8 now 一致性 → 无需代码动作（user 无 now 依赖）。✓
- §5 影响面文件清单 → File Structure 表。✓
- §6 测试策略（回归护栏 3 + 新增 14）→ Task 1 Step 1（14 新增）+ Task 2 Step 4（3 护栏）。✓
- §7 验收（mvn 全绿 / listUsers 不再 findAll / findPage+count 暴露 / UserQueryCriteria 存在 / 契约不变 / containsIgnoreCase 保留）→ Task 1 Step 6-7 + Task 2 Step 4-5。✓
- §8 风险（nickname NULL asc 边界 / like 大小写 / id tie-breaker 方向 / 映射零损耗）→ spec 已记，测试不构造 nickname NULL 行，`role`/`status` 用 `toUpperCase` 规避 collation，id ASC 与内存 `thenComparing(id)` 在 `reversed()` 之后一致。✓

**2. 占位符扫描**：无 TBD/TODO/"add appropriate"等；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`UserQueryCriteria(String q, String searchField, String role, String status, String sortBy, String sortDirection, PageQuery pageQuery)` 在 Task 1（定义）与 Task 2（`new UserQueryCriteria(query.q(), query.searchField(), ...)`）字段顺序一致；`findPage(UserQueryCriteria) -> List<User>` / `count(UserQueryCriteria) -> long` 在 Task 1（接口+实现）与 Task 2（`userRepository.findPage(criteria)` + `userRepository.count(criteria)`）签名一致；`UserEntity` 字段（`nickname`/`email`/`studentId`/`role`/`status`/`creditScore`/`createdAt`/`id`）与 spec 及源码 `@TableField` 一致；`AdminUserQuery` 字段（`q`/`searchField`/`role`/`status`/`sortBy`/`sortDirection`/`pageQuery`）与 `UserQueryCriteria` 逐字对齐。✓
