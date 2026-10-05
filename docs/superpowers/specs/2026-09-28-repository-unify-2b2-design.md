# 仓储层统一阶段 2B.2 设计（notification list SQL 下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行，不停等审）
- 分支：`refactor/optimization`（HEAD `3dd8e71`，2B.1 已完成）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 2 子阶段）
- 范围：`NotificationRepository` 接口扩展 + `MyBatisNotificationRepository` 实现 + `NotificationApplicationServiceImpl.list` 查询下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #2）

`NotificationApplicationServiceImpl.list`（`backend/src/main/java/com/campushub/backend/notification/service/NotificationApplicationServiceImpl.java:99-120`）当前：

```java
List<Notification> filtered = notificationRepository.findByUserId(userId).stream()
    .filter(notification -> !query.unreadOnly() || !notification.isRead())
    .sorted(Comparator.comparing(Notification::getCreatedAt).reversed())
    .toList();

int page = query.pageQuery().page();
int size = query.pageQuery().size();
int fromIndex = Math.max(0, (page - 1) * size);
int toIndex = Math.min(filtered.size(), fromIndex + size);
List<NotificationResponse> items = fromIndex >= filtered.size()
    ? List.of()
    : filtered.subList(fromIndex, toIndex).stream().map(this::toNotificationResponse).toList();
return new PageResponse<>(items, page, size, filtered.size());
```

即 `findByUserId(userId)` 全量加载 → 内存 `unreadOnly` 过滤 → 内存 `createdAt DESC` 排序 → `subList` 内存分页 → `toNotificationResponse` 转换（含跨仓储查 demand/order title）。无 SQL 级 `orderBy`/`LIMIT`/`count`。

### 1.2 与 demand 2B.1 的差异（更简单）

- `NotificationQuery` 仅 `boolean unreadOnly` + `PageQuery`，无 keyword/category/campusZone/location/startTime/currentUserId。
- `list` 签名 `list(Long userId, NotificationQuery query)` —— `userId` 是独立参数（不入 query），故 `findPage`/`count` 接 `(Long userId, NotificationQuery query)`。
- 排序固定 `createdAt DESC`（无 sort 枚举，无 `applySort`）。
- 无 `now` 依赖（不像 demand 的 `end_time` 过期可见性）。
- 无可见性 `OR` 组合（仅 `user_id` eq + `unreadOnly` eq）。

### 1.3 `toNotificationResponse` 不在下推范围

`list` 内 `.map(this::toNotificationResponse)` 调 `resolveTargetTitle`（跨仓储查 `demandRepository`/`orderRepository` 取 title，N+1）属**子项目3 视图组装层**，2B.2 不动。Service 拿到 `findPage` 结果后仍逐条 `toNotificationResponse`（N+1 由子项目3 修）。

### 1.4 `findByUserId` 保留

`NotificationRepository.findByUserId` 在 main 仅 `list:107` 调用（grep 已确认），`MyBatisNotificationRepositoryTest` 有 4 处测其本身。2B.2 保留 `findByUserId`（与 demand 保留 `findAll` 一致，2B 总体非目标"不改 Repository 结构"；main 改造后 `findByUserId` 成 test-only 方法，后续清理阶段评估是否删）。

## 2. 目标 / 非目标

### 目标
1. `NotificationRepository` 新增 `findPage(Long userId, NotificationQuery query)` + `count(Long userId, NotificationQuery query)`（风格 A，与 2B.1 一致）。
2. `MyBatisNotificationRepository` 实现：私有 `buildWrapper(userId, query)`（`eq(user_id)` + `unreadOnly`→`eq(is_read, false)`）供 `findPage`/`count` 共用；`findPage` 加 `orderByDesc(created_at)` + `LIMIT/OFFSET`；`count` 用 `selectCount`。
3. `NotificationApplicationServiceImpl.list` 改用 `findPage` + `count`，删除 `findByUserId` + 内存 `unreadOnly` 过滤 + 内存 `createdAt DESC` 排序 + `subList` 分页。
4. 删除仅服务于旧 `list` 的失效 import（`java.util.Comparator`）。
5. 行为零回归：`PageResponse` / `NotificationResponse` 字段与语义不变；现有 `NotificationApplicationServiceImplTest` 4 个用例断言不变且全绿。

### 非目标
- 不删 `findByUserId`（保留，1.4）。
- 不改 `NotificationQuery` / `Notification` / `NotificationEntity` / `NotificationResponse` / `NotificationMapper`。
- 不动 `toNotificationResponse` 及其跨仓储 N+1（子项目3）。
- 不动 `NotificationApplicationServiceImpl` 双构造函数（子项目4）。
- 不动 Controller / 前端契约。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)` / `NotificationResponse` 字段结构不变；API 行为不变。
- 测试全绿：基线 137/137（2B.1 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisNotificationRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致。

## 4. 设计

### 4.1 Repository 接口扩展（风格 A，与 2B.1 一致）

`NotificationRepository.java` 新增两方法（`save`/`findById`/`findByUserId` 保留）：

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

`NotificationQuery` 已含 `unreadOnly` + `pageQuery`，`userId` 作独立参数（贴合 `list` 签名）。

### 4.2 MyBatisNotificationRepository 实现

新增私有 `buildWrapper(userId, query)`（纯过滤，供 `findPage`/`count` 共用）。`findPage` 内直接 `orderByDesc(created_at)`（无 `applySort`，因排序固定）。

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

> `last("LIMIT " + size + " OFFSET " + offset)` 入参 `int`，无注入风险；`PageQuery` 构造已校验 `page>=1`、`1<=size<=100`，`offset` 必非负。

### 4.3 过滤下沉映射

| # | 内存逻辑（现状） | SQL 下推 | 边界对齐 |
|---|---|---|---|
| 1 | `findByUserId(userId)` | `user_id = :userId` | `userId==null` 已由 `findPage` 入口守卫返回空 |
| 2 | `!query.unreadOnly() \|\| !notification.isRead()` | `unreadOnly==true` → `is_read = false`；`unreadOnly==false` → 不加条件 | `unreadOnly` 为 `boolean` primitive（非 null），`if (query.unreadOnly())` 控制；`is_read` 为 `tinyint`/`BOOLEAN`，`eq(getIsRead, false)` 生成 `is_read = false`，与领域 `!isRead()` 一致 |

### 4.4 排序下沉

| 内存逻辑 | SQL |
|---|---|
| `Comparator.comparing(Notification::getCreatedAt).reversed()` | `ORDER BY created_at DESC` |

`created_at` 为 `DATETIME NOT NULL`（schema-notification.sql:13），无 null 位置问题。

### 4.5 分页下沉

`LIMIT :size OFFSET :offset`（`offset = (page-1)*size`）；`total` = `selectCount(buildWrapper(userId, query))`。与 2B.1 一致。

### 4.6 Service 改造

`NotificationApplicationServiceImpl.list` 改为：

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

`toNotificationResponse` 保持逐条调用（N+1 留子项目3）。

### 4.7 import 清理

删除（仅旧 `list` 的 `sorted(Comparator.comparing(...))` 用，grep 确认 `Comparator` 在该文件无其他引用）：

```java
import java.util.Comparator;
```

保留：`java.time.LocalDateTime`（`createNotification` 用）、`java.util.List`（`list` 用）、`java.util.Optional`（`resolveTargetTitle` 用）、其余跨仓储 import。

### 4.8 `now` 一致性

notification 无 `now`/时间边界依赖（不像 demand 的 `end_time >= now`），`findPage`/`count` 无 `now` 取值，无 2B.1 的毫秒窗口问题。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `notification/repository/NotificationRepository.java` | 加 `findPage` + `count`（`save`/`findById`/`findByUserId` 保留） |
| `notification/repository/MyBatisNotificationRepository.java` | 实现 `findPage` + `count` + 私有 `buildWrapper` |
| `notification/service/NotificationApplicationServiceImpl.java` | `list` 改用 `findPage`+`count`；删 `Comparator` import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `notification/repository/MyBatisNotificationRepositoryTest.java` | **新增** `findPage`/`count` 用例 |
| `notification/service/NotificationApplicationServiceImplTest.java` | **不改**（4 用例作回归护栏） |

### 不受影响
- `NotificationQuery` / `Notification` / `NotificationEntity` / `NotificationResponse` / `NotificationMapper` / `NotificationType`
- `toNotificationResponse` 及跨仓储 title 解析（子项目3）
- Controller / 前端
- schema（`idx_notify_user_read(user_id, is_read)` 已覆盖下推查询）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `shouldListUnreadNotifications`：2 条通知 user 1 全未读 → `list(1, unreadOnly=true, page1 size20)` 断言 `total=2`、全 `!read`、`targetType=ORDER`、`actionHint=VIEW_ORDER`。下推后 `user_id=1 AND is_read=false` 等价。
- `shouldMarkOwnNotificationAsRead` / `shouldRejectMarkingOthersNotificationAsRead`：`markAsRead` 后 `list(unreadOnly=false)` 反映已读状态。
- `shouldBuildStructuredContentWithFallbackWhenRelatedResourceMissing`：`notifyDemandRejected(1, 999, ...)` → `list` 首条 `title="需求审核未通过"`、content 含"相关需求"。

### 6.2 新增 `MyBatisNotificationRepositoryTest` 用例（@MybatisPlusTest + @Sql schema-notification.sql）
1. `findPage` 返回 user 通知按 `created_at DESC` + 默认分页。
2. `findPage` `unreadOnly=true` 仅返回未读（`is_read=false`）。
3. `findPage` `unreadOnly=false` 返回全部（含已读）。
4. `findPage` 分页 `LIMIT/OFFSET`：插入 5 条，`PageQuery(1,2)`/`(2,2)`/`(3,2)` 返回 2/2/1。
5. `count` 与 `findPage`（同条件、不限分页）总数一致（`unreadOnly` true/false 两档）。
6. `findPage`/`count` null 防御：`userId==null` 或 `query==null` 返回空/0。

> 现有 `MyBatisNotificationRepositoryTest` 的 `save`/`findById`/`findByUserId`/`related_id`/`content` 用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test -q`（`JAVA_HOME` 指向 JDK 21）全绿，测试数 ≥ 137 + 新增（6 个 findPage/count 用例）= 143。
2. `NotificationApplicationServiceImpl.list` 不再出现 `notificationRepository.findByUserId`、`Comparator`、`subList`。
3. `NotificationRepository` 暴露 `findPage` + `count`；`MyBatisNotificationRepository` 实现含 `buildWrapper`。
4. `PageResponse` / `NotificationResponse` 字段不变；现有 4 个 `list` 用例断言不变且全绿。
5. `toNotificationResponse` 行为不变（跨仓储 N+1 留子项目3）。
6. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `is_read` Boolean 下推与内存 `isRead()` 不一致 | `is_read` 为 `tinyint NOT NULL DEFAULT 0`，`eq(getIsRead, false)` 生成 `is_read = false`；`shouldListUnreadNotifications` 回归用例覆盖 |
| `created_at` 排序 null 位置 | `created_at` 为 `NOT NULL`，无 null 位置问题 |
| `findByUserId` 改造后 main 无调用成死代码 | 保留（test 仍测，2B 总体非目标不改结构）；后续清理阶段评估 |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`（`PageQuery` 已校验范围），无注入风险 |
