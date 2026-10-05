# 2C.3（dailyActiveUsers 拆按日查询 + getDashboard 去冗余 findAll）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 把 `countDailyActiveUsers` 的 4 个 collect*（含 2 处 N+1：listAllReviews 逐 user findByTargetId + collectRecommendationActivity 逐 user findByUserId）+ `getDashboard` 的 3 `findAll` 冗余，改为 4 个 `findActiveUserIdsByDate(LocalDate)` SQL 按日查询 + `getDashboard` 去 users/orders `findAll`（保留 demands for categoryDistribution）。

**Architecture:** 4 Repository 各加 `findActiveUserIdsByDate`（范围查询 `[today, today+1)` + `select` 列选择 + `Set` 返回）；`countDailyActiveUsers` 改调 4 方法合并 set；`getDashboard` 去 users/orders findAll；删 collect*/listAllReviews/isSameDate。

**Spec:** `docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c3-design.md`

## Global Constraints
- JDK 21：先 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`，再单独 `.\mvnw.cmd test`（workdir=backend）。
- PowerShell 7+：禁管道/分号/&&/&/重定向/反引号/$()；每条单独。
- 基线 212/212（2C.2 后）；2C.3 新增约 8 用例后应 220。
- 分支 refactor/2c，HEAD abb2ae2。仅 commit，push PR #11。

## File Structure
| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java` | 加 `findActivePublisherIdsByDate(LocalDate)` |
| `order/repository/OrderRepository.java` + `MyBatisOrderRepository.java` | 加 `findActiveParticipantIdsByDate(LocalDate)` |
| `review/repository/ReviewRepository.java` + `MyBatisReviewRepository.java` | 加 `findActiveAuthorIdsByDate(LocalDate)` |
| `recommendation/repository/UserActionLogRepository.java` + `MyBatisUserActionLogRepository.java` | 加 `findActiveUserIdsByDate(LocalDate)` |
| `admin/service/AdminApplicationServiceImpl.java` | `countDailyActiveUsers` 改调 + `getDashboard` 去 users/orders findAll + 删 collect*/listAllReviews/isSameDate + import |
| 4 Repository Test | 各新增 2 用例 |

---

### Task 1: 4 Repository findActiveUserIdsByDate + 测试

- [ ] **Step 1: 写失败测试（各 Repository 2 用例）**

**`MyBatisDemandRepositoryTest`**：
```java
    @Test
    void findActivePublisherIdsByDate_returns_today_active() {
        Demand todayDemand = repository.save(newDemandWithCreated("今日", DemandCategory.OTHER, LocalDateTime.now()));
        Demand oldDemand = repository.save(newDemandWithCreated("旧", DemandCategory.OTHER, LocalDateTime.now().minusDays(2)));

        Set<Long> result = repository.findActivePublisherIdsByDate(LocalDate.now());

        assertThat(result).contains(todayDemand.getPublisherId());
        assertThat(result).doesNotContain(oldDemand.getPublisherId());
    }

    @Test
    void findActivePublisherIdsByDate_null_returns_empty() {
        assertThat(repository.findActivePublisherIdsByDate(null)).isEmpty();
    }
```
import：`import java.time.LocalDate;` + `import java.util.Set;`。

**`MyBatisOrderRepositoryTest`**：
```java
    @Test
    void findActiveParticipantIdsByDate_returns_today_active_publisher_and_accepter() {
        Order today = repository.save(newOrder(9101L, 10L, 20L));
        today.setCreatedAt(LocalDateTime.now());
        repository.save(today);
        Order old = repository.save(newOrder(9102L, 11L, 21L));
        old.setCreatedAt(LocalDateTime.now().minusDays(2));
        repository.save(old);

        Set<Long> result = repository.findActiveParticipantIdsByDate(LocalDate.now());

        assertThat(result).contains(10L, 20L);
        assertThat(result).doesNotContain(11L, 21L);
    }

    @Test
    void findActiveParticipantIdsByDate_null_returns_empty() {
        assertThat(repository.findActiveParticipantIdsByDate(null)).isEmpty();
    }
```
import：`import java.time.LocalDate;` + `import java.util.Set;`。

**`MyBatisReviewRepositoryTest`**：
```java
    @Test
    void findActiveAuthorIdsByDate_returns_today_active() {
        Review today = repository.save(newReview(9201L, 10L, 20L, 5));
        today.setCreatedAt(LocalDateTime.now());
        repository.save(today);
        Review old = repository.save(newReview(9202L, 11L, 21L, 4));
        old.setCreatedAt(LocalDateTime.now().minusDays(2));
        repository.save(old);

        Set<Long> result = repository.findActiveAuthorIdsByDate(LocalDate.now());

        assertThat(result).contains(10L);
        assertThat(result).doesNotContain(11L);
    }

    @Test
    void findActiveAuthorIdsByDate_null_returns_empty() {
        assertThat(repository.findActiveAuthorIdsByDate(null)).isEmpty();
    }
```
import：`import java.time.LocalDate;` + `import java.util.Set;`。

**`MyBatisUserActionLogRepositoryTest`**（需读现有测试确认工厂）：
```java
    @Test
    void findActiveUserIdsByDate_returns_today_active() {
        // 用现有工厂创建今日 + 旧日志，验证仅今日命中
        // 具体代码依现有 MyBatisUserActionLogRepositoryTest 工厂
    }

    @Test
    void findActiveUserIdsByDate_null_returns_empty() {
        assertThat(repository.findActiveUserIdsByDate(null)).isEmpty();
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest,MyBatisOrderRepositoryTest,MyBatisReviewRepositoryTest,MyBatisUserActionLogRepositoryTest
```
Expected: 编译失败，4 方法不存在。

- [ ] **Step 3: 加 4 Repository 接口方法**

各 Repository 接口追加（import `java.time.LocalDate` + `java.util.Set`）：

```java
// DemandRepository
Set<Long> findActivePublisherIdsByDate(LocalDate today);
// OrderRepository
Set<Long> findActiveParticipantIdsByDate(LocalDate today);
// ReviewRepository
Set<Long> findActiveAuthorIdsByDate(LocalDate today);
// UserActionLogRepository
Set<Long> findActiveUserIdsByDate(LocalDate today);
```

- [ ] **Step 4: 实现 4 MyBatisRepository**

参照 spec §2.1 实现。关键模式：
- `today == null` → `Set.of()`
- `start = today.atStartOfDay()` / `end = today.plusDays(1).atStartOfDay()`
- `selectList(select(字段).and(范围 OR 范围))` → `map(字段).filter(nonNull).collect(toSet())`
- OrderRepository 提取 publisherId + accepterId 两列

import：`java.time.LocalDate` / `java.time.LocalDateTime` / `java.util.Set` / `java.util.HashSet` / `java.util.Objects` / `java.util.stream.Collectors`（按需）。

- [ ] **Step 5: 跑 4 Repository 测试**

```
.\mvnw.cmd test -Dtest=MyBatisDemandRepositoryTest,MyBatisOrderRepositoryTest,MyBatisReviewRepositoryTest,MyBatisUserActionLogRepositoryTest
```
Expected: PASS（原有 + 新增 8）。

- [ ] **Step 6: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥220（AdminApplicationServiceImplTest 仍用旧 getDashboard，findAll 保留故绿）。

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/com/campushub/backend/demand/repository/DemandRepository.java backend/src/main/java/com/campushub/backend/demand/repository/MyBatisDemandRepository.java backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/main/java/com/campushub/backend/review/repository/ReviewRepository.java backend/src/main/java/com/campushub/backend/review/repository/MyBatisReviewRepository.java backend/src/main/java/com/campushub/backend/recommendation/repository/UserActionLogRepository.java backend/src/main/java/com/campushub/backend/recommendation/repository/MyBatisUserActionLogRepository.java backend/src/test/java/com/campushub/backend/demand/repository/MyBatisDemandRepositoryTest.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java backend/src/test/java/com/campushub/backend/review/repository/MyBatisReviewRepositoryTest.java backend/src/test/java/com/campushub/backend/recommendation/repository/MyBatisUserActionLogRepositoryTest.java
```
```
git commit -m "perf(repository): add findActiveUserIdsByDate to 4 repositories for dailyActiveUsers SQL pushdown"
```

---

### Task 2: AdminApplicationServiceImpl countDailyActiveUsers/getDashboard 改造 + 删 collect*

- [ ] **Step 1: 改 countDailyActiveUsers + getDashboard**

参照 spec §2.2/§2.3。`countDailyActiveUsers(LocalDate today)` 调 4 findActiveUserIdsByDate；`getDashboard` 去 users/orders findAll，保留 demands findAll（categoryDistribution）。

- [ ] **Step 2: 删 collect*/listAllReviews/isSameDate**

删 `collectDemandActivity`/`collectOrderActivity`/`collectReviewActivity`/`collectRecommendationActivity`/`listAllReviews`/`isSameDate`（grep 确认仅 countDailyActiveUsers 用）。

- [ ] **Step 3: import 清理**

grep 确认失效 import（`Review`/`UserActionLog`/`HashSet` 等是否还有其他引用）。保留 `reviewRepository`/`userActionLogRepository` 字段（required=false）。

- [ ] **Step 4: 跑 Service 测试**

```
.\mvnw.cmd test -Dtest=AdminApplicationServiceImplTest
```
Expected: PASS（shouldBuildDashboardStats dailyActiveUsers 断言不变）。

- [ ] **Step 5: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿（=Task 1 Step 6 数量）。

- [ ] **Step 6: Commit**

```
git add backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java
```
```
git commit -m "perf(admin): switch countDailyActiveUsers to 4 SQL findActiveUserIdsByDate and drop N+1 collect*/listAllReviews + redundant users/orders findAll"
```

---

## Self-Review
- §2.1 4 findActiveUserIdsByDate → Task 1 Step 3-4 ✓
- §2.2 countDailyActiveUsers 改调 → Task 2 Step 1 ✓
- §2.3 getDashboard 去 users/orders findAll → Task 2 Step 1 ✓
- §2.4 删 collect*/listAllReviews/isSameDate → Task 2 Step 2 ✓
- §4 测试（回归 + 各 Repository 2 用例）→ Task 1 Step 1 + Task 2 Step 4 ✓
- §5 验收（mvn 全绿 / 不再 collect*/listAllReviews/users.findAll/orders.findAll / dailyActiveUsers 不变 / 4 方法暴露 / reviewRepository/userActionLogRepository 字段保留）→ Task 1 Step 5-6 + Task 2 Step 4-5 ✓
- 范围查询 `[start, end)` 与 `isSameDate(toLocalDate().isEqual)` 一致 ✓；NULL 时间不匹配与内存一致 ✓
