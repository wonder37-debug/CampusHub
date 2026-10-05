# 3.3 设计 + 实施（NotificationApplicationServiceImpl.list toNotificationResponse batch）

- 日期：2026-10-04
- 分支：`refactor/3-view-assembly`（HEAD `7172402`，3.2 已完成）
- 范围：`NotificationApplicationServiceImpl.list` batch 预加载 + `toNotificationResponse` batch 重载（复用 `DemandRepository.findAllById` + `OrderRepository.findAllById`）

## 1. 现状

`list`（:97-113）：`:105-108 notifications.stream().map(this::toNotificationResponse)` —— 逐 notification 调 `toNotificationResponse`（:282-288）→ `resolveTargetTitle`（:301-317）→ `:308 demandRepository.findById(relatedId)`（DEMAND type）或 `:313/314 orderRepository.findById(relatedId) + demandRepository.findById(order.getDemandId())`（ORDER type）—— N+1。

## 2. 设计

### 2.1 toNotificationResponse batch 重载

```java
private NotificationResponse toNotificationResponse(Notification notification) {
    return toNotificationResponse(notification, null, null);
}

private NotificationResponse toNotificationResponse(Notification notification, Map<Long, Demand> demandMap, Map<Long, Order> orderMap) {
    String targetType = resolveTargetType(notification);
    Long targetId = notification.getRelatedId();
    String targetTitle = resolveTargetTitle(notification, targetType, demandMap, orderMap);
    String actionHint = resolveActionHint(notification.getType(), targetType);
    return NotificationResponse.from(notification, targetType, targetId, targetTitle, actionHint);
}

private String resolveTargetTitle(Notification notification, String targetType, Map<Long, Demand> demandMap, Map<Long, Order> orderMap) {
    if (notification == null || notification.getRelatedId() == null || targetType == null) {
        return null;
    }
    if ("DEMAND".equals(targetType)) {
        if (demandRepository == null) return null;
        if (demandMap != null) {
            Demand demand = demandMap.get(notification.getRelatedId());
            return demand == null ? null : demand.getTitle();
        }
        return demandRepository.findById(notification.getRelatedId()).map(Demand::getTitle).orElse(null);
    }
    if (!"ORDER".equals(targetType) || orderRepository == null) return null;
    Order order = orderMap != null ? orderMap.get(notification.getRelatedId())
        : orderRepository.findById(notification.getRelatedId()).orElse(null);
    if (order == null || demandRepository == null) return null;
    Long demandId = order.getDemandId();
    if (demandMap != null) {
        Demand demand = demandMap.get(demandId);
        return demand == null ? null : demand.getTitle();
    }
    return demandRepository.findById(demandId).map(Demand::getTitle).orElse(null);
}
```

> 单 detail（toNotificationResponse 委托 map=null 走 findById）。import `java.util.Map`/`Demand`/`Order`。

### 2.2 list batch 预加载

```java
List<Notification> notifications = notificationRepository.findPage(userId, query);
Map<Long, Demand> demandMap = null;
Map<Long, Order> orderMap = null;
if (!notifications.isEmpty()) {
    Set<Long> demandRelatedIds = new HashSet<>();
    Set<Long> orderRelatedIds = new HashSet<>();
    for (Notification n : notifications) {
        String type = resolveTargetType(n);
        if ("DEMAND".equals(type) && n.getRelatedId() != null) {
            demandRelatedIds.add(n.getRelatedId());
        } else if ("ORDER".equals(type) && n.getRelatedId() != null) {
            orderRelatedIds.add(n.getRelatedId());
        }
    }
    List<Order> orders = orderRelatedIds.isEmpty() ? List.of()
        : orderRepository.findAllById(orderRelatedIds);
    for (Order o : orders) {
        if (o.getDemandId() != null) demandRelatedIds.add(o.getDemandId());
    }
    demandMap = (demandRepository == null || demandRelatedIds.isEmpty()) ? Map.of()
        : demandRepository.findAllById(demandRelatedIds).stream().collect(Collectors.toMap(Demand::getId, d -> d));
    orderMap = orders.isEmpty() ? Map.of()
        : orders.stream().collect(Collectors.toMap(Order::getId, o -> o));
}
List<NotificationResponse> items = notifications.stream()
    .map(n -> toNotificationResponse(n, demandMap, orderMap))
    .toList();
```

> 2 次 batch SQL（findAllById orders + findAllById demands，若无 order/demand type 则跳过）。import `java.util.Map`/`Set`/`HashSet`/`stream.Collectors`/`Demand`/`Order`。

## 3. 影响面

| 文件 | 操作 |
|---|---|
| `notification/service/NotificationApplicationServiceImpl.java` | `toNotificationResponse`/`resolveTargetTitle` batch 重载 + `list` batch 预加载 + import |

测试：`NotificationApplicationServiceImplTest` list 用例回归（无新增——复用 findAllById）。

## 4. 验收
1. mvn test 全绿 234（无新增）。
2. `list` 不再逐 notification `findById`（grep 确认 batch 预加载）。
3. `toNotificationResponse` batch 版存在（单 detail 委托 map=null）。
4. list 行为不变（NotificationApplicationServiceImplTest 回归）。

---

# 实施计划

## Global Constraints
- JDK 21 / PowerShell 7+ / mvnw.cmd / workdir=backend / 基线 234 / 分支 refactor/3-view-assembly HEAD 7172402 / push PR #13。

## Task 1（单 Task）

- [ ] **Step 1: 改 toNotificationResponse/resolveTargetTitle batch 重载 + list batch**

参照 §2.1/§2.2。import `java.util.Map`/`Set`/`HashSet`/`stream.Collectors`/`Demand`/`Order`。

- [ ] **Step 2: 跑 Service 测试**

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```
```
.\mvnw.cmd test -Dtest=NotificationApplicationServiceImplTest
```
Expected: PASS（list 用例回归）。

- [ ] **Step 3: 跑全量测试**

```
.\mvnw.cmd test
```
Expected: 全绿 234。若截断写 tool-output，Grep 搜无 BUILD FAILURE/<<< FAILURE!/<<< ERROR!。

- [ ] **Step 4: Commit**

```
git add backend/src/main/java/com/campushub/backend/notification/service/NotificationApplicationServiceImpl.java
```
```
git commit -m "perf(notification): batch preload in list to eliminate toNotificationResponse N+1 findById [3.3]"
```

## Self-Review
- §2.1 toNotificationResponse batch → Step 1 ✓；§2.2 list batch → Step 1 ✓
- §4 验收 → Step 2-3 ✓
- 复用 DemandRepository.findAllById（2C.4）+ OrderRepository.findAllById（3.2）✓
