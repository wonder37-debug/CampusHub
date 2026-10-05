# 2C.3 设计（dailyActiveUsers 拆按日查询 + getDashboard 去冗余 findAll）

- 日期：2026-10-04
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/2c`（HEAD `abb2ae2`，2C.2 已完成）
- 上游 spec：`docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c-design.md`（2C 总体 §3.3）
- 范围：4 Repository 加 `findActiveUserIdsByDate` + `AdminApplicationServiceImpl.countDailyActiveUsers` 改调 + `getDashboard` 去 users/orders `findAll`

## 1. 现状

`AdminApplicationServiceImpl.getDashboard`（:277-303）+ `countDailyActiveUsers`（:329-337）+ `collect*`（:339-379）+ `listAllReviews`（:381-386）：

- `countDailyActiveUsers(demands, orders, today)` 调 4 个 collect*，内存聚合今日活跃用户 set
- `collectDemandActivity`/`collectOrderActivity`：用已加载的 demands/orders（非 N+1，但依赖 findAll）
- `collectReviewActivity`（:357-366）：调 `listAllReviews`（:381-386）逐 user `reviewRepository.findByTargetId` —— **N+1**
- `collectRecommendationActivity`（:368-379）：逐 user `userActionLogRepository.findByUserId` —— **N+1**
- `getDashboard` 3 `findAll`（users/demands/orders）+ 5 `selectCount` 并存 —— 冗余（2B.3d 妥协）

## 2. 设计

### 2.1 4 个 `findActiveUserIdsByDate(LocalDate)` 方法

**`DemandRepository.findActivePublisherIdsByDate(LocalDate today)`**：今日 `created_at` 或 `updated_at` 的 demand 的 `publisher_id` set。

```java
Set<Long> findActivePublisherIdsByDate(LocalDate today);
```

实现（`MyBatisDemandRepository`）：
```java
@Override
public Set<Long> findActivePublisherIdsByDate(LocalDate today) {
    if (today == null) {
        return Set.of();
    }
    LocalDateTime start = today.atStartOfDay();
    LocalDateTime end = today.plusDays(1).atStartOfDay();
    List<DemandEntity> entities = demandMapper.selectList(
        new LambdaQueryWrapper<DemandEntity>()
            .select(DemandEntity::getPublisherId)
            .and(w -> w.ge(DemandEntity::getCreatedAt, start).lt(DemandEntity::getCreatedAt, end)
                .or().ge(DemandEntity::getUpdatedAt, start).lt(DemandEntity::getUpdatedAt, end)));
    return entities.stream().map(DemandEntity::getPublisherId).filter(Objects::nonNull).collect(Collectors.toSet());
}
```

**`OrderRepository.findActiveParticipantIdsByDate(LocalDate today)`**：今日 `created_at`/`updated_at`/`completed_at` 的 order 的 `publisher_id` + `accepter_id` set。

```java
Set<Long> findActiveParticipantIdsByDate(LocalDate today);
```

实现（`MyBatisOrderRepository`）：
```java
@Override
public Set<Long> findActiveParticipantIdsByDate(LocalDate today) {
    if (today == null) {
        return Set.of();
    }
    LocalDateTime start = today.atStartOfDay();
    LocalDateTime end = today.plusDays(1).atStartOfDay();
    List<OrderEntity> entities = orderMapper.selectList(
        new LambdaQueryWrapper<OrderEntity>()
            .select(OrderEntity::getPublisherId, OrderEntity::getAccepterId)
            .and(w -> w.ge(OrderEntity::getCreatedAt, start).lt(OrderEntity::getCreatedAt, end)
                .or().ge(OrderEntity::getUpdatedAt, start).lt(OrderEntity::getUpdatedAt, end)
                .or().ge(OrderEntity::getCompletedAt, start).lt(OrderEntity::getCompletedAt, end)));
    Set<Long> result = new HashSet<>();
    for (OrderEntity e : entities) {
        result.add(e.getPublisherId());
        result.add(e.getAccepterId());
    }
    result.remove(null);
    return result;
}
```

**`ReviewRepository.findActiveAuthorIdsByDate(LocalDate today)`**：今日 `created_at` 的 review 的 `author_id` set。

```java
Set<Long> findActiveAuthorIdsByDate(LocalDate today);
```

实现（`MyBatisReviewRepository`）：
```java
@Override
public Set<Long> findActiveAuthorIdsByDate(LocalDate today) {
    if (today == null) {
        return Set.of();
    }
    LocalDateTime start = today.atStartOfDay();
    LocalDateTime end = today.plusDays(1).atStartOfDay();
    List<ReviewEntity> entities = reviewMapper.selectList(
        new LambdaQueryWrapper<ReviewEntity>()
            .select(ReviewEntity::getAuthorId)
            .ge(ReviewEntity::getCreatedAt, start).lt(ReviewEntity::getCreatedAt, end));
    return entities.stream().map(ReviewEntity::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
}
```

**`UserActionLogRepository.findActiveUserIdsByDate(LocalDate today)`**：今日 `created_at` 的 userActionLog 的 `user_id` set。

```java
Set<Long> findActiveUserIdsByDate(LocalDate today);
```

实现（`MyBatisUserActionLogRepository`）：需读现有实现确认字段名。

### 2.2 `countDailyActiveUsers` 改调

```java
private long countDailyActiveUsers(LocalDate today) {
    Set<Long> activeUserIds = new HashSet<>();
    activeUserIds.addAll(demandRepository.findActivePublisherIdsByDate(today));
    activeUserIds.addAll(orderRepository.findActiveParticipantIdsByDate(today));
    if (reviewRepository != null) {
        activeUserIds.addAll(reviewRepository.findActiveAuthorIdsByDate(today));
    }
    if (userActionLogRepository != null) {
        activeUserIds.addAll(userActionLogRepository.findActiveUserIdsByDate(today));
    }
    activeUserIds.remove(null);
    return activeUserIds.size();
}
```

> 4 次 SQL（替原 N+1 循环）。`reviewRepository`/`userActionLogRepository` 仍是 `@Autowired(required=false)`，null 时跳过（与原 collect* 一致）。

### 2.3 `getDashboard` 去 users/orders `findAll`

```java
@Override
public AdminDashboardResponse getDashboard(Long operatorId) {
    requireAdmin(operatorId);

    List<Demand> demands = demandRepository.findAll();  // 保留（categoryDistribution 用）
    LocalDate today = LocalDate.now();

    long dailyActiveUsers = countDailyActiveUsers(today);
    long totalUsers = userRepository.count();
    long totalDemands = demandRepository.countAll();
    long totalOrders = orderRepository.count();
    long pendingReviewDemands = demandRepository.countByStatus(DemandStatus.REVIEWING);
    long completedOrders = orderRepository.countByStatus(OrderStatus.COMPLETED);
    Map<String, Long> categoryDistribution = demands.stream()
        .collect(Collectors.groupingBy(demand -> demand.getCategory().name(), Collectors.counting()));

    List<AdminCategoryStatResponse> categoryStats = categoryDistribution.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
        .map(entry -> new AdminCategoryStatResponse(entry.getKey(), entry.getValue()))
        .toList();

    return new AdminDashboardResponse(
        dailyActiveUsers, totalUsers, totalDemands, pendingReviewDemands,
        totalOrders, completedOrders, categoryStats);
}
```

- 删 `List<User> users = userRepository.findAll()` + `List<Order> orders = orderRepository.findAll()`（dailyActiveUsers 不再依赖）
- 保留 `List<Demand> demands = demandRepository.findAll()`（categoryDistribution 用，2C.3 不下推 SQL groupBy，YAGNI——数据量小，内存 groupBy 无额外查询）
- `countDailyActiveUsers(today)` 签名简化（去掉 demands/orders 参数）

### 2.4 删 `collect*` + `listAllReviews` + `isSameDate`

- `collectDemandActivity`/`collectOrderActivity`/`collectReviewActivity`/`collectRecommendationActivity`：下推 SQL，删
- `listAllReviews`：仅 `collectReviewActivity` 用，删
- `isSameDate`：仅 collect* 用，删

> `@Autowired(required=false) reviewRepository`/`userActionLogRepository` 字段保留（其他方法不用，但字段不删——子项目4 双构造函数范围）。grep 确认 reviewRepository/userActionLogRepository 字段是否有其他引用。

### 2.5 import

- 删 `import java.time.LocalDateTime;`？需 grep 确认（getDashboard/transferReward 等可能用）。保留。
- 删 `import com.campushub.backend.review.domain.Review;`（listAllReviews 用，删后失效）？grep 确认。
- 删 `import com.campushub.backend.recommendation.domain.UserActionLog;`（collectRecommendationActivity 用）？grep 确认。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` + `MyBatisDemandRepository.java` | 加 `findActivePublisherIdsByDate` + import |
| `order/repository/OrderRepository.java` + `MyBatisOrderRepository.java` | 加 `findActiveParticipantIdsByDate` + import |
| `review/repository/ReviewRepository.java` + `MyBatisReviewRepository.java` | 加 `findActiveAuthorIdsByDate` + import |
| `recommendation/repository/UserActionLogRepository.java` + `MyBatisUserActionLogRepository.java` | 加 `findActiveUserIdsByDate` + import |
| `admin/service/AdminApplicationServiceImpl.java` | `countDailyActiveUsers` 改调 + `getDashboard` 去 users/orders findAll + 删 collect*/listAllReviews/isSameDate + import 清理 |

测试：各 Repository 新增 findActiveUserIdsByDate 用例 + AdminApplicationServiceImplTest.shouldBuildDashboardStats 回归。

## 4. 测试策略

### 4.1 回归护栏
`AdminApplicationServiceImplTest.shouldBuildDashboardStats`（dailyActiveUsers=2 断言，验证今日活跃用户）。

### 4.2 新增（各 Repository）
1. `DemandRepository.findActivePublisherIdsByDate`：今日 created/updated 的 demand publisherId 命中；非今日不命中。
2. `OrderRepository.findActiveParticipantIdsByDate`：今日 created/updated/completed 的 order publisherId+accepterId 命中。
3. `ReviewRepository.findActiveAuthorIdsByDate`：今日 created 的 review authorId 命中。
4. `UserActionLogRepository.findActiveUserIdsByDate`：今日 created 的 userActionLog userId 命中。
5. 各 null 防御。

## 5. 验收
1. mvn test 全绿 ≥212 + 新增（约 8 个，各 Repository 2 个）。
2. `countDailyActiveUsers` 不再调 `collect*`/`listAllReviews`；`getDashboard` 不再 `userRepository.findAll()`/`orderRepository.findAll()`。
3. `shouldBuildDashboardStats` dailyActiveUsers 断言不变。
4. `reviewRepository`/`userActionLogRepository` 字段保留（required=false）。
5. 4 个 `findActiveUserIdsByDate` 暴露。

## 6. 风险
| 风险 | 对策 |
|---|---|
| 范围查询 `created_at >= start AND created_at < end` 与内存 `isSameDate(time.toLocalDate().isEqual(today))` 边界差异 | 内存 `isEqual` 比较日期（不含时间）；范围查询 `[start, end)` 等价今日 0:00-次日 0:00，与 `toLocalDate().isEqual` 一致 ✓ |
| `updated_at`/`completed_at` NULL 与内存 `isSameDate(null→false)` | NULL `>= start` → NULL → false，与内存一致 ✓ |
| `.select(publisherId)` 列选择 | MyBatis-Plus 3.5.7 支持；OrderRepository 的 `.select(publisherId, accepterId)` 多列选择 ✓ |
| H2 `LocalDateTime` 范围查询时区 | H2 MySQL 模式 `DATETIME` 无时区，`LocalDate.atStartOfDay` 本地时区，与生产 MySQL 一致 ✓ |
| `reviewRepository`/`userActionLogRepository` null（required=false） | null 时跳过（与原 collect* 一致） |
