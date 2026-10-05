# 3.2 设计 + 实施（toOrderView batch + OrderController/AdminController 列表 + :145 bug）

- 日期：2026-10-04
- 分支：`refactor/3-view-assembly`（HEAD `6ca7184`，3.1 已完成）
- 上游 spec：`docs/superpowers/specs/2026-10-04-view-assembly-3-design.md`（§3 3.2）
- 范围：`OrderRepository.findAllById` + `ReviewRepository.findAllByOrderIdIn` + `ApiViewMapper.toOrderView` batch 重载 + `OrderController.list`/`AdminController.listArbitrationOrders` batch 预加载 + `:145` 重复 findByDemandId bug 修复

## 1. 现状

`ApiViewMapper.toOrderView`（:109-149）6+ 跨仓储查询：
- :110 `demandRepository.findById(order.getDemandId())`
- :111 `userRepository.findById(order.getPublisherId())`
- :112 `userRepository.findById(order.getAccepterId())`
- :116 `reviewRepository.findByOrderId(order.getId())`
- :120 `reviewRepository.findByOrderIdAndAuthorId(order.getId(), currentUser.userId())`
- :135 嵌套 `toDemandView(demand, currentUser)`（又触发 findById publisher + findByDemandId order）
- :145 `orderRepository.findByDemandId(order.getDemandId())` —— **bug（order 查自己 by demandId）**

**列表 N+1**：`OrderController.list`（:69-72）+ `AdminController.listArbitrationOrders`（:134-138）逐 order `findById` + `toOrderView` 触发 8+ 次查询。

## 2. 设计

### 2.1 OrderRepository.findAllById（新）
```java
List<Order> findAllById(Collection<Long> ids);
```
`MyBatisOrderRepository`：`assembleWithBatchHistory(orderMapper.selectBatchIds(ids))`。import `java.util.Collection`。

### 2.2 ReviewRepository.findAllByOrderIdIn（新）
```java
List<Review> findAllByOrderIdIn(Collection<Long> orderIds);
```
`MyBatisReviewRepository`：`selectList(in(order_id, orderIds)).map(toDomain)`。import `java.util.Collection`。

### 2.3 ApiViewMapper.toOrderView batch 重载

```java
public OrderView toOrderView(Order order, CurrentUser currentUser) {
    return toOrderView(order, currentUser, null, null, null, null, null);
}

public OrderView toOrderView(Order order, CurrentUser currentUser,
        Map<Long, Demand> demandMap, Map<Long, User> userMap,
        Map<Long, List<Review>> reviewByOrderIdMap, Set<Long> reviewedOrderIdsByCurrentUser,
        Map<Long, Order> orderByDemandMap) {
    // :110 demand = resolveDemand(order.getDemandId(), demandMap)
    // :111 requester = resolveUser(order.getPublisherId(), userMap)
    // :112 provider = resolveUser(order.getAccepterId(), userMap)
    // :116 reviews = resolveReviews(order.getId(), reviewByOrderIdMap)
    // :120 currentUserReviewed = reviewedOrderIdsByCurrentUser != null ? reviewedOrderIdsByCurrentUser.contains(order.getId()) : reviewRepository.findByOrderIdAndAuthorId(order.getId(), currentUser.userId()).isPresent()
    // :135 toDemandView(demand, currentUser, userMap, orderByDemandMap)  // 3.1 batch 版
    // :145 resolveContactInfo(demand, order, currentUser)  // bug 修复：用 order 本身（原 findByDemandId 查自己）
    ...
}

private Demand resolveDemand(Long id, Map<Long, Demand> map) {
    if (id == null) return null;
    if (map != null) return map.get(id);
    return demandRepository.findById(id).orElse(null);
}

private List<Review> resolveReviews(Long orderId, Map<Long, List<Review>> map) {
    if (orderId == null) return List.of();
    if (map != null) return map.getOrDefault(orderId, List.of());
    return reviewRepository.findByOrderId(orderId);
}
```

> `resolveUser`（3.1 已有）。`:145` bug 修复：`orderRepository.findByDemandId(order.getDemandId())` 返回 order 自己（uk_order_demand 唯一），改用 `order` 本身传给 `resolveContactInfo`。import `java.util.Set`/`Map`。

### 2.4 OrderController.list + AdminController.listArbitrationOrders batch 预加载

```java
// OrderController.list（:69-72）
List<Long> orderIds = rawPage.items().stream().map(OrderSummaryResponse::orderId).toList();
if (orderIds.isEmpty()) { return ApiResponse.success(new PageResponse<>(List.of(), rawPage.page(), rawPage.size(), rawPage.total())); }
List<Order> orders = orderRepository.findAllById(orderIds);
Set<Long> demandIds = orders.stream().map(Order::getDemandId).filter(Objects::nonNull).collect(toSet());
Map<Long, Demand> demandMap = demandRepository.findAllById(demandIds).stream().collect(toMap(Demand::getId, d -> d));
Set<Long> userIds = new HashSet<>();
orders.forEach(o -> { userIds.add(o.getPublisherId()); userIds.add(o.getAccepterId()); });
demands.forEach(d -> userIds.add(d.getPublisherId()));  // demand publisher（匿名判断需）
Map<Long, User> userMap = userRepository.findAllById(userIds).stream().collect(toMap(User::getId, u -> u));
Map<Long, List<Review>> reviewByOrderIdMap = reviewRepository.findAllByOrderIdIn(orderIds).stream().collect(groupingBy(Review::getOrderId));
Set<Long> reviewedOrderIds = reviewRepository.findReviewedOrderIdsByAuthor(currentUser.userId(), orderIds);  // 2C.5
Map<Long, Order> orderByDemandMap = orders.stream().collect(toMap(Order::getDemandId, o -> o));
List<OrderView> items = orders.stream()
    .map(order -> apiViewMapper.toOrderView(order, currentUser, demandMap, userMap, reviewByOrderIdMap, reviewedOrderIds, orderByDemandMap))
    .toList();
```

> AdminController.listArbitrationOrders 同模式。需注入 `demandRepository`/`userRepository`/`reviewRepository`（OrderController/AdminController 需确认注入——若未注入需加）。import `java.util.Map`/`Set`/`HashSet`/`stream.Collectors`/`Objects`/`Demand`/`User`/`Review`。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `order/repository/OrderRepository.java` + `MyBatisOrderRepository.java` | 加 `findAllById` + import |
| `review/repository/ReviewRepository.java` + `MyBatisReviewRepository.java` | 加 `findAllByOrderIdIn` + import |
| `api/ApiViewMapper.java` | `toOrderView` batch 重载 + `resolveDemand`/`resolveReviews` + `:145` bug 修复 + import |
| `api/OrderController.java` | `list` batch + import + 注入确认 |
| `api/AdminController.java` | `listArbitrationOrders` batch + import + 注入确认 |
| `order/repository/MyBatisOrderRepositoryTest.java` | 新增 `findAllById` 用例 |
| `review/repository/MyBatisReviewRepositoryTest.java` | 新增 `findAllByOrderIdIn` 用例 |

## 4. 测试策略

### 4.1 回归
OrderControllerTest/AdminControllerTest（若有 list 用例）+ 全量。

### 4.2 新增
1. `MyBatisOrderRepositoryTest.findAllById`：返回匹配 orders（含 history）+ null/空。
2. `MyBatisReviewRepositoryTest.findAllByOrderIdIn`：返回匹配 reviews + null/空。

## 5. 验收
1. mvn test 全绿 ≥230 + 4 新增 = 234。
2. `OrderController.list`/`AdminController.listArbitrationOrders` 不再逐 order `findById`。
3. `toOrderView:145` 重复 `findByDemandId` bug 修复（用 order 本身）。
4. `OrderRepository.findAllById`/`ReviewRepository.findAllByOrderIdIn` 暴露。
5. `toOrderView` batch 版存在（单 detail 版不变）。

---

# 实施计划

## Global Constraints
- JDK 21 / PowerShell 7+ / mvnw.cmd / workdir=backend / 基线 230 / 分支 refactor/3-view-assembly HEAD 6ca7184 / push PR #13。

## Task 1（单 Task）

- [ ] **Step 1: 写失败测试**

`MyBatisOrderRepositoryTest` 末尾加：
```java
    @Test
    void findAllById_returns_matching_orders_with_history() {
        Order o1 = repository.save(newOrder(9501L, 10L, 20L));
        o1.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", LocalDateTime.now());
        repository.save(o1);
        Order o2 = repository.save(newOrder(9502L, 11L, 21L));
        repository.save(newOrder(9503L, 12L, 22L));

        List<Order> result = repository.findAllById(List.of(o1.getId(), o2.getId(), 9999L));

        assertThat(result).extracting(Order::getId).containsExactlyInAnyOrder(o1.getId(), o2.getId());
        Order loaded1 = result.stream().filter(o -> o.getId().equals(o1.getId())).findFirst().orElseThrow();
        assertThat(loaded1.getStatusHistory()).hasSize(1);
    }

    @Test
    void findAllById_handles_null_and_empty() {
        assertThat(repository.findAllById(null)).isEmpty();
        assertThat(repository.findAllById(List.of())).isEmpty();
    }
```

`MyBatisReviewRepositoryTest` 末尾加：
```java
    @Test
    void findAllByOrderIdIn_returns_matching_reviews() {
        repository.save(newReview(9601L, 10L, 20L, 5));
        repository.save(newReview(9602L, 11L, 20L, 4));
        repository.save(newReview(9603L, 12L, 21L, 3));

        List<Review> result = repository.findAllByOrderIdIn(List.of(9601L, 9602L, 9604L));

        assertThat(result).extracting(Review::getOrderId).containsExactlyInAnyOrder(9601L, 9602L);
    }

    @Test
    void findAllByOrderIdIn_handles_null_and_empty() {
        assertThat(repository.findAllByOrderIdIn(null)).isEmpty();
        assertThat(repository.findAllByOrderIdIn(List.of())).isEmpty();
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisOrderRepositoryTest,MyBatisReviewRepositoryTest
```
Expected: 编译失败。

- [ ] **Step 3: 加 OrderRepository.findAllById + ReviewRepository.findAllByOrderIdIn 接口与实现**

参照 §2.1/§2.2。import `java.util.Collection`。

- [ ] **Step 4: ApiViewMapper.toOrderView batch 重载 + :145 bug 修复**

参照 §2.3。原 `toOrderView(Order, CurrentUser)` 委托 batch 版（map=null）。:145 `orderRepository.findByDemandId(order.getDemandId())` → 用 `order` 本身（`resolveContactInfo(demand, order, currentUser)`）。import `java.util.Set`/`Map`。

- [ ] **Step 5: OrderController.list + AdminController.listArbitrationOrders batch**

参照 §2.4。**先读 OrderController/AdminController 构造函数确认注入**（demandRepository/userRepository/reviewRepository）——若未注入需加。import `java.util.Map`/`Set`/`HashSet`/`stream.Collectors`/`Objects`/`Demand`/`User`/`Review`/`DemandSummaryResponse`（OrderController 已有）/`OrderSummaryResponse`。

- [ ] **Step 6: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥234。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/main/java/com/campushub/backend/review/repository/ReviewRepository.java backend/src/main/java/com/campushub/backend/review/repository/MyBatisReviewRepository.java backend/src/main/java/com/campushub/backend/api/ApiViewMapper.java backend/src/main/java/com/campushub/backend/api/OrderController.java backend/src/main/java/com/campushub/backend/api/AdminController.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java backend/src/test/java/com/campushub/backend/review/repository/MyBatisReviewRepositoryTest.java
```
```
git commit -m "perf(api): batch preload in OrderController.list/AdminController.listArbitrationOrders to eliminate toOrderView N+1 + fix :145 duplicate findByDemandId bug [3.2]"
```

## Self-Review
- §2.1 findAllById → Step 3 ✓；§2.2 findAllByOrderIdIn → Step 3 ✓
- §2.3 toOrderView batch 重载 + :145 bug → Step 4 ✓
- §2.4 Controller batch → Step 5 ✓
- §4.2 新增 4 用例 → Step 1 ✓
- §5 验收 → Step 6 ✓
- `:145` bug：order 查自己 by demandId（uk 唯一），改用 order 本身 ✓
