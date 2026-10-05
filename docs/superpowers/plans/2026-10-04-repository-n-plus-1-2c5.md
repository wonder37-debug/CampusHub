# 2C.5 设计 + 实施（checkPendingReviewsAndAutoComplete batch 修复）

- 日期：2026-10-04
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/2c`（HEAD `aefebfc`，2C.4 已完成，2C 最后一个）
- 上游 spec：`docs/superpowers/specs/2026-10-04-repository-n-plus-1-2c-design.md`（2C 总体 §3.5）
- 范围：`ReviewRepository` 加 `findReviewedOrderIdsByAuthor` + `DemandApplicationServiceImpl.checkPendingReviewsAndAutoComplete` 改 batch

## 1. 现状

`DemandApplicationServiceImpl.checkPendingReviewsAndAutoComplete`（:401-420）：
```java
List<Order> allOrders = orderRepository.findByParticipant(publisherId);  // 已 2C.1 批量化 loadHistory
for (Order order : allOrders) {
    if (order.getStatus() != OrderStatus.COMPLETED) { continue; }
    if (reviewRepository.findByOrderIdAndAuthorId(order.getId(), publisherId).isPresent()) { continue; }  // ← N+1
    notificationApplicationService.notifyPendingReviewReminder(publisherId, order.getId());
}
```
逐 COMPLETED order 调 `findByOrderIdAndAuthorId`（N+1）。

## 2. 设计

### 2.1 ReviewRepository 加 `findReviewedOrderIdsByAuthor`

```java
/**
 * 查询给定 order 集合中已被指定作者评价的 order_id（用于批量排除已评价订单）。
 */
Set<Long> findReviewedOrderIdsByAuthor(Long authorId, Collection<Long> orderIds);
```

### 2.2 MyBatisReviewRepository 实现

```java
@Override
public Set<Long> findReviewedOrderIdsByAuthor(Long authorId, Collection<Long> orderIds) {
    if (authorId == null || orderIds == null || orderIds.isEmpty()) {
        return Set.of();
    }
    List<ReviewEntity> entities = reviewMapper.selectList(
        new LambdaQueryWrapper<ReviewEntity>()
            .select(ReviewEntity::getOrderId)
            .eq(ReviewEntity::getAuthorId, authorId)
            .in(ReviewEntity::getOrderId, orderIds));
    return entities.stream().map(ReviewEntity::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
}
```

> `select(orderId).eq(authorId).in(orderId, ids)` 一次查询替 N 次 `findByOrderIdAndAuthorId`。import `java.util.Collection`/`Set`/`Objects`/`Collectors`。

### 2.3 Service 改造

```java
private void checkPendingReviewsAndAutoComplete(Long publisherId) {
    if (orderApplicationService != null) {
        orderApplicationService.autoCompleteOverdueOrders(publisherId);
    }
    if (notificationApplicationService != null && reviewRepository != null && orderRepository != null) {
        List<Long> completedOrderIds = orderRepository.findByParticipant(publisherId).stream()
            .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
            .map(Order::getId)
            .toList();
        if (completedOrderIds.isEmpty()) {
            return;
        }
        Set<Long> reviewedOrderIds = reviewRepository.findReviewedOrderIdsByAuthor(publisherId, completedOrderIds);
        for (Long orderId : completedOrderIds) {
            if (!reviewedOrderIds.contains(orderId)) {
                notificationApplicationService.notifyPendingReviewReminder(publisherId, orderId);
            }
        }
    }
}
```

2 次 SQL（findByParticipant + findReviewedOrderIdsByAuthor），无 N+1。import `java.util.Set`（若未 import）。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `review/repository/ReviewRepository.java` + `MyBatisReviewRepository.java` | 加 `findReviewedOrderIdsByAuthor` + import |
| `demand/service/DemandApplicationServiceImpl.java` | `checkPendingReviewsAndAutoComplete` 改 batch + import |

测试：`MyBatisReviewRepositoryTest` 新增用例；`DemandApplicationServiceImplTest` 回归（若有 checkPendingReviewsAndAutoComplete 用例）。

## 4. 测试策略

### 4.1 回归
`DemandApplicationServiceImplTest` 现有用例（若有涉及 checkPendingReviewsAndAutoComplete / publish 后的通知）。若无直接用例，依赖全量测试不回归。

### 4.2 新增
1. `findReviewedOrderIdsByAuthor` 返回 author 已评价的 order_id（插入 author 的 review + 其他 author 的 review，验证仅前者命中）。
2. `findReviewedOrderIdsByAuthor` null/空防御。

## 5. 验收
1. mvn test 全绿 ≥222 + 2 新增 = 224。
2. `checkPendingReviewsAndAutoComplete` 不再逐 order `findByOrderIdAndAuthorId`。
3. `ReviewRepository.findReviewedOrderIdsByAuthor` 暴露。
4. checkPendingReviewsAndAutoComplete 行为不变。

## 6. 风险
- `in(order_id, ids)` 大列表：COMPLETED orders 有限，可接受。
- `findByParticipant` 返回 Order 含 history（2C.1 批量化），但 checkPendingReviewsAndAutoComplete 仅用 status/id，不读 history——可优化但不改（YAGNI）。

---

# 实施计划

## Global Constraints
- JDK 21 / PowerShell 7+ / mvnw.cmd / workdir=backend / 基线 222 / 分支 refactor/2c HEAD aefebfc / push PR #11。

## File Structure
| 文件 | 操作 |
|---|---|
| `review/repository/ReviewRepository.java` + `MyBatisReviewRepository.java` | 加 `findReviewedOrderIdsByAuthor` + import |
| `demand/service/DemandApplicationServiceImpl.java` | `checkPendingReviewsAndAutoComplete` 改 batch + import |
| `review/repository/MyBatisReviewRepositoryTest.java` | 新增 2 用例 |

## Task 1（单 Task）

- [ ] **Step 1: 写失败测试**

`MyBatisReviewRepositoryTest` 末尾追加：
```java
    @Test
    void findReviewedOrderIdsByAuthor_returns_reviewed_order_ids() {
        repository.save(newReview(9301L, 10L, 20L, 5)); // author=10 reviewed order 9301
        repository.save(newReview(9302L, 10L, 21L, 4)); // author=10 reviewed order 9302
        repository.save(newReview(9303L, 11L, 20L, 3)); // author=11 reviewed order 9303

        Set<Long> result = repository.findReviewedOrderIdsByAuthor(10L, List.of(9301L, 9302L, 9303L, 9304L));

        assertThat(result).containsExactlyInAnyOrder(9301L, 9302L);
    }

    @Test
    void findReviewedOrderIdsByAuthor_handles_null_and_empty() {
        assertThat(repository.findReviewedOrderIdsByAuthor(null, List.of(9301L))).isEmpty();
        assertThat(repository.findReviewedOrderIdsByAuthor(10L, null)).isEmpty();
        assertThat(repository.findReviewedOrderIdsByAuthor(10L, List.of())).isEmpty();
    }
```
import：`import java.util.Set;`（若未 import）。

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisReviewRepositoryTest
```
Expected: 编译失败，`findReviewedOrderIdsByAuthor` 不存在。

- [ ] **Step 3: 加 ReviewRepository 接口 + MyBatis 实现**

参照 §2.1/§2.2。import `java.util.Collection`/`Set`/`Objects`/`Collectors`。

- [ ] **Step 4: 改 checkPendingReviewsAndAutoComplete**

参照 §2.3。import `java.util.Set`（若未 import）。

- [ ] **Step 5: 跑 Repository + Service 测试**

```
.\mvnw.cmd test -Dtest=MyBatisReviewRepositoryTest,DemandApplicationServiceImplTest
```
Expected: PASS。

- [ ] **Step 6: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥224。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/com/campushub/backend/review/repository/ReviewRepository.java backend/src/main/java/com/campushub/backend/review/repository/MyBatisReviewRepository.java backend/src/main/java/com/campushub/backend/demand/service/DemandApplicationServiceImpl.java backend/src/test/java/com/campushub/backend/review/repository/MyBatisReviewRepositoryTest.java
```
```
git commit -m "perf(demand): batch findReviewedOrderIdsByAuthor to eliminate checkPendingReviewsAndAutoComplete N+1 findByOrderIdAndAuthorId"
```

## Self-Review
- §2.1 接口 → Step 3 ✓；§2.2 实现 → Step 3 ✓；§2.3 Service 改造 → Step 4 ✓
- §4.2 新增 2 用例 → Step 1 ✓；回归 → Step 5 ✓
- §5 验收 → Step 5-6 ✓
