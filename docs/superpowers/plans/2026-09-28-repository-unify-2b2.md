# 仓储层统一 2B.2（notification list SQL 下推）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `NotificationApplicationServiceImpl.list` 的 `findByUserId` + 内存 `unreadOnly` 过滤 + 内存 `createdAt DESC` 排序 + `subList` 分页下沉为 `NotificationRepository.findPage` / `count` 的 SQL（`LambdaQueryWrapper` 过滤 + `orderByDesc` + `LIMIT/OFFSET` + `selectCount`），行为零回归。

**Architecture:** Repository 接口新增 `findPage(Long userId, NotificationQuery)` + `count(Long userId, NotificationQuery)`（风格 A，与 2B.1 一致，`userId` 作独立参数贴合 `list` 签名）；`MyBatisNotificationRepository` 提取私有 `buildWrapper(userId, query)`（`eq(user_id)` + `unreadOnly`→`eq(is_read,false)`，供 `findPage`/`count` 共用）；Service `list` 改调新方法，`toNotificationResponse` 保留（跨仓储 N+1 留子项目3）。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / MyBatis-Plus 3.5.7（`BaseMapper` + `LambdaQueryWrapper`，无 XML mapper）/ H2 MySQL 兼容模式（测试）/ MySQL（生产）/ JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2b2-design.md`（设计依据，与本 plan 一同阅读）

## Global Constraints

- **JDK 21**：本机默认 JDK 17，跑 Maven 前必须先设 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`（单条命令），再单独跑 `.\mvnw.cmd test -q`（workdir=`D:\workspace\sec-ii-2026\backend`）。
- **PowerShell 7+**：bash 工具禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`；每条命令单独执行。
- **Maven Wrapper**：用 `.\mvnw.cmd`（非 `./mvnw`），workdir=`D:\workspace\sec-ii-2026\backend`。
- **前端契约不变**：`PageResponse(items, page, size, total)` / `NotificationResponse` 字段结构不变；API 行为不变。
- **测试基线 137/137 不回归**（2B.1 后）：每 task 结束跑全量验证。
- **分支 `refactor/optimization`**：仅 SDD 的 commit step 可执行；不 push / 不 merge。
- **无 XML mapper**：全部 `BaseMapper` + `LambdaQueryWrapper`。
- **H2 MySQL 兼容模式**（测试）与 MySQL（生产）行为一致。

## File Structure

| 文件 | 责任 | 本 plan 操作 |
|---|---|---|
| `notification/repository/NotificationRepository.java` | 仓储接口 | 加 `findPage` + `count`（`save`/`findById`/`findByUserId` 保留） |
| `notification/repository/MyBatisNotificationRepository.java` | MyBatis 实现 | 实现 `findPage` + `count` + 私有 `buildWrapper` |
| `notification/service/NotificationApplicationServiceImpl.java` | 应用服务 | `list` 改用新方法；删 `Comparator` import |
| `notification/repository/MyBatisNotificationRepositoryTest.java` | Repository 切片测试 | 新增 6 个 `findPage`/`count` 用例 + 1 个辅助工厂 |
| `notification/service/NotificationApplicationServiceImplTest.java` | Service 集成测试 | **不改**（4 用例作回归护栏） |

---

### Task 1: NotificationRepository 扩展 findPage/count + MyBatisNotificationRepository 实现

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/notification/repository/NotificationRepository.java`
- Modify: `backend/src/main/java/com/campushub/backend/notification/repository/MyBatisNotificationRepository.java`
- Test: `backend/src/test/java/com/campushub/backend/notification/repository/MyBatisNotificationRepositoryTest.java`

**Interfaces:**
- Consumes: `NotificationQuery`（`notification.dto`，record `(boolean unreadOnly, PageQuery pageQuery)`，紧凑构造器保证 `pageQuery` 非 null）、`PageQuery`（`common.model`，record `(int page, int size)`，构造校验 `page>=1`、`1<=size<=100`）、`NotificationEntity`（`@TableName("sys_notification")`，字段 `userId`/`type`/`title`/`content`/`isRead`(Boolean, 列 `is_read`)/`relatedId`/`createdAt`）、`NotificationMapper extends BaseMapper<NotificationEntity>`（`selectList`/`selectCount`）。
- Produces: `NotificationRepository.findPage(Long userId, NotificationQuery) -> List<Notification>`、`NotificationRepository.count(Long userId, NotificationQuery) -> long`（供 Task 2 Service 调用）。

- [ ] **Step 1: 写失败测试（6 个 findPage/count 用例 + 1 个辅助工厂）**

在 `MyBatisNotificationRepositoryTest.java` 末尾（`newNotification` 工厂之前）追加测试方法，并在 `newNotification` 之后追加 `newNotificationWithCreated` 辅助工厂。

测试方法（追加到类体内）：

```java
    @Test
    void findPage_returns_user_notifications_sorted_by_created_desc() {
        Notification older = repository.save(newNotificationWithCreated(10L, NotificationType.ORDER_ACCEPTED, LocalDateTime.now().minusMinutes(10)));
        Notification newer = repository.save(newNotificationWithCreated(10L, NotificationType.REVIEW_RECEIVED, LocalDateTime.now().minusMinutes(1)));

        List<Notification> page = repository.findPage(10L, new NotificationQuery(false, new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(repository.count(10L, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(2L);
    }

    @Test
    void findPage_unread_only_returns_unread_notifications() {
        Notification unread = repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        Notification read = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        read.setRead(true);
        repository.save(read);

        List<Notification> page = repository.findPage(10L, new NotificationQuery(true, new PageQuery(1, 20)));

        assertThat(page).extracting(Notification::getId).containsExactly(unread.getId());
        assertThat(repository.count(10L, new NotificationQuery(true, new PageQuery(1, 20)))).isEqualTo(1L);
    }

    @Test
    void findPage_unread_false_returns_all_including_read() {
        repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        Notification read = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        read.setRead(true);
        repository.save(read);

        List<Notification> page = repository.findPage(10L, new NotificationQuery(false, new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(repository.count(10L, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(2L);
    }

    @Test
    void findPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newNotificationWithCreated(10L, NotificationType.ORDER_ACCEPTED, LocalDateTime.now().minusMinutes(5 - i)));
        }
        NotificationQuery page1 = new NotificationQuery(false, new PageQuery(1, 2));
        NotificationQuery page2 = new NotificationQuery(false, new PageQuery(2, 2));
        NotificationQuery page3 = new NotificationQuery(false, new PageQuery(3, 2));

        assertThat(repository.findPage(10L, page1)).hasSize(2);
        assertThat(repository.findPage(10L, page2)).hasSize(2);
        assertThat(repository.findPage(10L, page3)).hasSize(1);
        assertThat(repository.count(10L, page1)).isEqualTo(5L);
    }

    @Test
    void count_matches_findPage_total_for_unread_and_all() {
        repository.save(newNotification(10L, NotificationType.ORDER_ACCEPTED));
        Notification read = repository.save(newNotification(10L, NotificationType.STATUS_CHANGED));
        read.setRead(true);
        repository.save(read);

        assertThat(repository.count(10L, new NotificationQuery(true, new PageQuery(1, 20)))).isEqualTo(1L);
        assertThat(repository.findPage(10L, new NotificationQuery(true, new PageQuery(1, 20)))).hasSize(1);
        assertThat(repository.count(10L, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(2L);
        assertThat(repository.findPage(10L, new NotificationQuery(false, new PageQuery(1, 20)))).hasSize(2);
    }

    @Test
    void findPage_and_count_return_empty_when_userId_or_query_null() {
        assertThat(repository.findPage(null, new NotificationQuery(false, new PageQuery(1, 20)))).isEmpty();
        assertThat(repository.findPage(10L, null)).isEmpty();
        assertThat(repository.count(null, new NotificationQuery(false, new PageQuery(1, 20)))).isEqualTo(0L);
        assertThat(repository.count(10L, null)).isEqualTo(0L);
    }
```

辅助工厂（追加到 `newNotification` 之后）：

```java
    private static Notification newNotificationWithCreated(Long userId, NotificationType type, LocalDateTime createdAt) {
        Notification notification = newNotification(userId, type);
        notification.setCreatedAt(createdAt);
        return notification;
    }
```

补充 import（在现有 import 区追加）：

```java
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.notification.dto.NotificationQuery;
```

- [ ] **Step 2: 跑测试确认失败（编译错）**

设置 JDK 21（单独一条命令）：

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

跑 Repository 测试（workdir=`D:\workspace\sec-ii-2026\backend`，单独一条）：

```
.\mvnw.cmd test -q -Dtest=MyBatisNotificationRepositoryTest
```

Expected: 编译失败，`NotificationRepository` 无 `findPage`/`count` 方法。

- [ ] **Step 3: 加 NotificationRepository 接口方法**

`NotificationRepository.java` 在 `findByUserId` 之后追加（`save`/`findById`/`findByUserId` 保留不动）：

```java
    /**
     * 按用户与查询条件分页查询通知（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param userId 用户 ID，为 null 时返回空列表
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Notification> findPage(Long userId, NotificationQuery query);

    /**
     * 按用户与查询条件统计匹配的通知总数（过滤下推 SQL，用于分页 total）。
     *
     * @param userId 用户 ID，为 null 时返回 0
     * @param query 查询条件，为 null 时返回 0
     */
    long count(Long userId, NotificationQuery query);
```

并在 import 区追加：

```java
import com.campushub.backend.notification.dto.NotificationQuery;
```

- [ ] **Step 4: 实现 MyBatisNotificationRepository 的 findPage/count/buildWrapper**

在 `MyBatisNotificationRepository.java` 的 `findByUserId` 方法之后追加实现：

```java
    @Override
    public List<Notification> findPage(Long userId, NotificationQuery query) {
        if (userId == null || query == null) {
            return List.of();
        }
        LambdaQueryWrapper<NotificationEntity> wrapper = buildWrapper(userId, query);
        wrapper.orderByDesc(NotificationEntity::getCreatedAt);
        int size = query.pageQuery().size();
        int offset = (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return notificationMapper.selectList(wrapper).stream().map(NotificationEntity::toDomain).toList();
    }

    @Override
    public long count(Long userId, NotificationQuery query) {
        if (userId == null || query == null) {
            return 0L;
        }
        return notificationMapper.selectCount(buildWrapper(userId, query));
    }

    private LambdaQueryWrapper<NotificationEntity> buildWrapper(Long userId, NotificationQuery query) {
        LambdaQueryWrapper<NotificationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NotificationEntity::getUserId, userId);
        if (query.unreadOnly()) {
            wrapper.eq(NotificationEntity::getIsRead, false);
        }
        return wrapper;
    }
```

在 import 区追加（保持字母序，与现有风格一致）：

```java
import com.campushub.backend.notification.dto.NotificationQuery;
```

- [ ] **Step 5: 跑 Repository 测试确认通过**

确保 `$env:JAVA_HOME` 已设（若新 session 先执行 Step 2 的设置命令）。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q -Dtest=MyBatisNotificationRepositoryTest
```

Expected: PASS，`MyBatisNotificationRepositoryTest` 全部用例（含原有 7 + 新增 6 = 13 个）绿。

- [ ] **Step 6: 跑全量测试确认无回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q
```

Expected: 全绿，测试数 ≥ 137 + 6（新增）= 143。`NotificationApplicationServiceImplTest` 此刻仍用旧 `list`（调 `findByUserId`），`findByUserId` 保留故仍绿。

- [ ] **Step 7: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/notification/repository/NotificationRepository.java backend/src/main/java/com/campushub/backend/notification/repository/MyBatisNotificationRepository.java backend/src/test/java/com/campushub/backend/notification/repository/MyBatisNotificationRepositoryTest.java
```

```
git commit -m "refactor(notification): push list query down to SQL via findPage/count in NotificationRepository"
```

---

### Task 2: NotificationApplicationServiceImpl.list 改用 findPage/count + 清理 import

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/notification/service/NotificationApplicationServiceImpl.java:99-120`（`list` 方法体）+ import 区
- Test: `backend/src/test/java/com/campushub/backend/notification/service/NotificationApplicationServiceImplTest.java`（不改，作回归护栏）

**Interfaces:**
- Consumes: Task 1 的 `NotificationRepository.findPage(Long userId, NotificationQuery) -> List<Notification>` + `NotificationRepository.count(Long userId, NotificationQuery) -> long`。
- Produces: `NotificationApplicationService.list(Long userId, NotificationQuery) -> PageResponse<NotificationResponse>`（签名不变，行为等价但走 SQL 下推）。

- [ ] **Step 1: 改 list 方法体**

替换 `NotificationApplicationServiceImpl.java:99-120` 的 `list` 方法为：

```java
    @Override
    public PageResponse<NotificationResponse> list(Long userId, NotificationQuery query) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "notification query must not be null");
        }
        List<Notification> notifications = notificationRepository.findPage(userId, query);
        List<NotificationResponse> items = notifications.stream()
            .map(this::toNotificationResponse)
            .toList();
        long total = notificationRepository.count(userId, query);
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        return new PageResponse<>(items, page, size, total);
    }
```

- [ ] **Step 2: 清理失效 import**

删除前用 Grep 工具确认 `Comparator` 在 `NotificationApplicationServiceImpl.java` 无其他引用（应仅旧 `list` 的 `sorted(Comparator.comparing(...))`）。确认后从 import 区删除：

```java
import java.util.Comparator;
```

保留：`java.time.LocalDateTime`（`createNotification` 用）、`java.util.List`（`list` 用）、`java.util.Optional`（`resolveTargetTitle` 用）。

- [ ] **Step 3: 跑 Service 测试确认 list 回归绿**

确保 `$env:JAVA_HOME` 已设。workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q -Dtest=NotificationApplicationServiceImplTest
```

Expected: PASS，含 `shouldListUnreadNotifications`、`shouldMarkOwnNotificationAsRead`、`shouldRejectMarkingOthersNotificationAsRead`、`shouldBuildStructuredContentWithFallbackWhenRelatedResourceMissing` 全绿。

- [ ] **Step 4: 跑全量测试确认不回归**

workdir=`D:\workspace\sec-ii-2026\backend`：

```
.\mvnw.cmd test -q
```

Expected: 全绿，测试数 = Task 1 Step 6 的数量（本 task 不新增测试，仅重构）。

- [ ] **Step 5: Commit**

workdir=`D:\workspace\sec-ii-2026`：

```
git add backend/src/main/java/com/campushub/backend/notification/service/NotificationApplicationServiceImpl.java
```

```
git commit -m "refactor(notification): switch NotificationApplicationServiceImpl.list to SQL-pushed findPage/count and drop in-memory filter/sort"
```

---

## Self-Review

**1. Spec 覆盖**
- §4.1 接口扩展（findPage/count 风格 A，userId+query）→ Task 1 Step 3。✓
- §4.2 MyBatisNotificationRepository 实现（buildWrapper/findPage/count，固定 orderByDesc）→ Task 1 Step 4。✓
- §4.3 过滤下沉映射（user_id eq + unreadOnly→is_read=false）→ Task 1 Step 4 `buildWrapper`。✓
- §4.4 排序下沉（createdAt DESC）→ Task 1 Step 4 `findPage` 内 `orderByDesc`。✓
- §4.5 分页下沉（LIMIT/OFFSET + selectCount）→ Task 1 Step 4。✓
- §4.6 Service 改造 → Task 2 Step 1。✓
- §4.7 import 清理（Comparator）→ Task 2 Step 2。✓
- §4.8 now 一致性 → 无需代码动作（notification 无 now 依赖）。✓
- §5 影响面 → File Structure 表。✓
- §6 测试策略（回归护栏 4 + 新增 6）→ Task 1 Step 1 + Task 2 Step 3。✓
- §7 验收（mvn 全绿 / list 不再 findByUserId / findPage+count 暴露 / 契约不变 / toNotificationResponse 不变）→ Task 1 Step 5-6 + Task 2 Step 3-4。✓

**2. 占位符扫描**：无 TBD/TODO；所有代码块为可直接落地实现。✓

**3. 类型一致性**：`findPage(Long userId, NotificationQuery) -> List<Notification>` / `count(Long userId, NotificationQuery) -> long` 在 Task 1（定义）与 Task 2（`list` 调用 `notificationRepository.findPage(userId, query)` + `count(userId, query)`）签名一致；`NotificationQuery(boolean unreadOnly, PageQuery)`、`NotificationEntity` 字段（`userId`/`isRead`/`createdAt`）与 spec 及源码一致。✓
