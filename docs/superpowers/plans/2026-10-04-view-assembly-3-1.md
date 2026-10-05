# 3.1 设计 + 实施（DemandController list toDemandView batch 预加载）

- 日期：2026-10-04
- 分支：`refactor/3-view-assembly`（HEAD `d01dcba`）
- 上游 spec：`docs/superpowers/specs/2026-10-04-view-assembly-3-design.md`（§2/§3 3.1）
- 范围：`UserRepository.findAllById` + `OrderRepository.findAllByDemandIdIn` + `ApiViewMapper.toDemandView` batch 重载 + `DemandController.list` batch 预加载

## 1. 现状

`DemandController.list`（:109-112）：
```java
List<DemandView> items = rawPage.items().stream()
    .map(item -> demandRepository.findById(item.id()).orElseThrow())  // ← 逐 item N+1
    .map(demand -> apiViewMapper.toDemandView(demand, currentUser))   // ← toDemandView 内 findById publisher + findByDemandId order（N+1）
    .toList();
```
列表场景逐 demand 3 次 SQL（findById demand + findById publisher + findByDemandId order）。

## 2. 设计

### 2.1 UserRepository.findAllById（新）
```java
List<User> findAllById(Collection<Long> ids);
```
`MyBatisUserRepository`：`userMapper.selectBatchIds(ids).stream().map(UserEntity::toDomain).toList()`。import `java.util.Collection`。

### 2.2 OrderRepository.findAllByDemandIdIn（新）
```java
List<Order> findAllByDemandIdIn(Collection<Long> demandIds);
```
`MyBatisOrderRepository`：
```java
@Override
public List<Order> findAllByDemandIdIn(Collection<Long> demandIds) {
    if (demandIds == null || demandIds.isEmpty()) { return List.of(); }
    return assembleWithBatchHistory(orderMapper.selectList(
        new LambdaQueryWrapper<OrderEntity>().in(OrderEntity::getDemandId, demandIds)));
}
```
> 用 `assembleWithBatchHistory`（2C.1）保证 history 加载（toDemandView 不读 history，但 toOrderView 读——3.1 仅 toDemandView，但保持一致用 batch loadHistory）。import `java.util.Collection`。

### 2.3 ApiViewMapper.toDemandView batch 重载

```java
public DemandView toDemandView(Demand demand, CurrentUser currentUser) {
    return toDemandView(demand, currentUser, null, null);  // 单 detail 走 findById
}

public DemandView toDemandView(Demand demand, CurrentUser currentUser, Map<Long, User> userMap, Map<Long, Order> orderMap) {
    // 原 toDemandView 方法体，但 :47 findById → resolveUser(demand.getPublisherId(), userMap)
    // :74 findByDemandId → resolveOrder(demand.getId(), orderMap)
    ...
}

private User resolveUser(Long id, Map<Long, User> map) {
    if (id == null) return null;
    if (map != null) return map.get(id);
    return userRepository.findById(id).orElse(null);
}

private Order resolveOrder(Long demandId, Map<Long, Order> map) {
    if (demandId == null) return null;
    if (map != null) return map.get(demandId);
    return orderRepository.findByDemandId(demandId).orElse(null);
}
```

> 原 `toDemandView(Demand, CurrentUser)` 委托 batch 版（map=null 走 findById）。单 detail 方法（publish/detail/update/withdraw）不变。import `java.util.Map`。

### 2.4 DemandController.list batch 预加载

```java
List<Long> demandIds = rawPage.items().stream().map(DemandSummaryResponse::id).toList();
if (demandIds.isEmpty()) {
    return ApiResponse.success(new PageResponse<>(List.of(), rawPage.page(), rawPage.size(), rawPage.total()));
}
List<Demand> demands = demandRepository.findAllById(demandIds);  // 2C.4 已有
Set<Long> publisherIds = demands.stream().map(Demand::getPublisherId).filter(Objects::nonNull).collect(Collectors.toSet());
Map<Long, User> userMap = publisherIds.isEmpty() ? Map.of()
    : userRepository.findAllById(publisherIds).stream().collect(Collectors.toMap(User::getId, u -> u));
Map<Long, Order> orderMap = orderRepository.findAllByDemandIdIn(demandIds).stream()
    .collect(Collectors.toMap(Order::getDemandId, o -> o));
List<DemandView> items = demands.stream()
    .map(demand -> apiViewMapper.toDemandView(demand, currentUser, userMap, orderMap))
    .toList();
```

3 次 batch SQL（findAllById demands + findAllById users + findAllByDemandIdIn orders），替 N*3 次。import `java.util.Map`/`Set`/`stream.Collectors`/`Objects`。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `auth/repository/UserRepository.java` + `MyBatisUserRepository.java` | 加 `findAllById` + import |
| `order/repository/OrderRepository.java` + `MyBatisOrderRepository.java` | 加 `findAllByDemandIdIn` + import |
| `api/ApiViewMapper.java` | `toDemandView` batch 重载 + `resolveUser`/`resolveOrder` + import |
| `api/DemandController.java` | `list` batch 预加载 + import |
| `auth/repository/MyBatisUserRepositoryTest.java` | 新增 `findAllById` 用例 |
| `order/repository/MyBatisOrderRepositoryTest.java` | 新增 `findAllByDemandIdIn` 用例 |

## 4. 测试策略

### 4.1 回归
`DemandControllerTest`（若有 list 用例）/ `ApiViewMapperTest`（若有）/ 全量测试。

### 4.2 新增
1. `MyBatisUserRepositoryTest.findAllById`：返回匹配 users + null/空防御。
2. `MyBatisOrderRepositoryTest.findAllByDemandIdIn`：返回匹配 orders（含 history）+ null/空防御。

## 5. 验收
1. mvn test 全绿 ≥226 + 2 新增 = 228。
2. `DemandController.list` 不再逐 item `findById`（grep 确认 batch 预加载）。
3. `UserRepository.findAllById` + `OrderRepository.findAllByDemandIdIn` 暴露。
4. `ApiViewMapper.toDemandView` batch 版存在（单 detail 版不变）。

---

# 实施计划

## Global Constraints
- JDK 21 / PowerShell 7+ / mvnw.cmd / workdir=backend / 基线 226 / 分支 refactor/3-view-assembly HEAD d01dcba / push 新 PR。

## Task 1（单 Task）

- [ ] **Step 1: 写失败测试**

`MyBatisUserRepositoryTest` 末尾追加：
```java
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
```
import `import java.util.List;`（若未 import——已 import List）。

`MyBatisOrderRepositoryTest` 末尾追加：
```java
    @Test
    void findAllByDemandIdIn_returns_matching_orders_with_history() {
        Order o1 = repository.save(newOrder(9401L, 10L, 20L));
        o1.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", LocalDateTime.now());
        repository.save(o1);
        Order o2 = repository.save(newOrder(9402L, 11L, 21L));
        repository.save(newOrder(9403L, 12L, 22L)); // 不在查询集

        List<Order> result = repository.findAllByDemandIdIn(List.of(9401L, 9402L, 9404L));

        assertThat(result).extracting(Order::getDemandId).containsExactlyInAnyOrder(9401L, 9402L);
        Order loaded1 = result.stream().filter(o -> o.getDemandId().equals(9401L)).findFirst().orElseThrow();
        assertThat(loaded1.getStatusHistory()).hasSize(1);  // assembleWithBatchHistory 加载 history
    }

    @Test
    void findAllByDemandIdIn_handles_null_and_empty() {
        assertThat(repository.findAllByDemandIdIn(null)).isEmpty();
        assertThat(repository.findAllByDemandIdIn(List.of())).isEmpty();
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=MyBatisUserRepositoryTest,MyBatisOrderRepositoryTest
```
Expected: 编译失败，`findAllById`/`findAllByDemandIdIn` 不存在。

- [ ] **Step 3: 加 UserRepository + OrderRepository 接口与实现**

参照 §2.1/§2.2。import `java.util.Collection`。

- [ ] **Step 4: ApiViewMapper.toDemandView batch 重载**

参照 §2.3。原 `toDemandView(Demand, CurrentUser)` 方法体改为委托 batch 版（map=null）。新增 `toDemandView(Demand, CurrentUser, Map, Map)` + `resolveUser`/`resolveOrder`。import `java.util.Map`。

**重要**：原 toDemandView 方法体的 :47 `userRepository.findById(demand.getPublisherId()).orElse(null)` → `resolveUser(demand.getPublisherId(), userMap)`；:74 `orderRepository.findByDemandId(demand.getId()).orElse(null)` → `resolveOrder(demand.getId(), orderMap)`。其余逻辑不变。

- [ ] **Step 5: DemandController.list batch 预加载**

参照 §2.4。替换 :109-112。import `java.util.Map`/`Set`/`stream.Collectors`/`Objects` + `User`/`Order`（若未 import——需确认 DemandController 已 import）。

**重要**：DemandController 需注入 `userRepository`（若未注入）。读 DemandController 构造函数确认——若未注入 UserRepository，需加注入。

- [ ] **Step 6: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 ≥228。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/com/campushub/backend/auth/repository/UserRepository.java backend/src/main/java/com/campushub/backend/auth/repository/MyBatisUserRepository.java backend/src/main/java/com/campushub/backend/order/repository/OrderRepository.java backend/src/main/java/com/campushub/backend/order/repository/MyBatisOrderRepository.java backend/src/main/java/com/campushub/backend/api/ApiViewMapper.java backend/src/main/java/com/campushub/backend/api/DemandController.java backend/src/test/java/com/campushub/backend/auth/repository/MyBatisUserRepositoryTest.java backend/src/test/java/com/campushub/backend/order/repository/MyBatisOrderRepositoryTest.java
```
```
git commit -m "perf(api): batch preload in DemandController.list to eliminate toDemandView N+1 (findAllById + findAllByDemandIdIn)"
```

## Self-Review
- §2.1 findAllById → Step 3 ✓；§2.2 findAllByDemandIdIn → Step 3 ✓
- §2.3 toDemandView batch 重载 → Step 4 ✓
- §2.4 DemandController.list batch → Step 5 ✓
- §4.2 新增 4 用例 → Step 1 ✓
- §5 验收 → Step 6 ✓
- 单 detail 方法（publish/detail/update/withdraw）不变（toDemandView 委托 map=null）✓
