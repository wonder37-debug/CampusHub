# 子项目3 设计（视图组装层 N+1 修复）

- 日期：2026-10-04
- 分支：`refactor/3-view-assembly`（基于 `github/main` `d01dcba`）
- 范围：`ApiViewMapper`（toDemandView/toOrderView/toReviewView）+ `NotificationApplicationServiceImpl.toNotificationResponse` 的列表场景 N+1 batch 预加载修复

## 1. N+1 清单（列表场景，grep + 代码核实）

### 1.1 ApiViewMapper.toDemandView（:45-107）
- :47 `userRepository.findById(demand.getPublisherId())` —— 单 demand 单次
- :74 `orderRepository.findByDemandId(demand.getId())` —— 单 demand 单次
- **列表 N+1**：`DemandController`（:75/111/171/186/197）`demands.stream().map(toDemandView)` 逐 demand 触发 2 次 findById/findByDemandId

### 1.2 ApiViewMapper.toOrderView（:109-149）
- :110 `demandRepository.findById(order.getDemandId())`
- :111 `userRepository.findById(order.getPublisherId())`
- :112 `userRepository.findById(order.getAccepterId())`
- :116 `reviewRepository.findByOrderId(order.getId())`
- :120 `reviewRepository.findByOrderIdAndAuthorId(order.getId(), currentUser.userId())`
- :135 嵌套 `toDemandView(demand, currentUser)`（又触发 :47/:74）
- :145 `orderRepository.findByDemandId(order.getDemandId())` —— **重复查（order 查自己 by demandId），bug**
- :116-118 嵌套 `toReviewView`（:189/190 findById author/target）
- **列表 N+1**：`OrderController`（:71/80/91/106/121）+ `AdminController`（:137/176）+ `DemandController`（:212）逐 order 触发 6+ 次 findById/findByOrderId + 嵌套

### 1.3 NotificationApplicationServiceImpl.toNotificationResponse（:282）
- :308 `demandRepository.findById(notification.getRelatedId())`
- :313-314 `orderRepository.findById + demandRepository.findById`（链式）
- :336/337/351/371 其他 findById
- **列表 N+1**：:107 `notificationRepository.findPage...map(toNotificationResponse)` 逐 notification findById demand/order

### 1.4 附带 bug
- `toOrderView:145` `orderRepository.findByDemandId(order.getDemandId())` —— order 查自己的 demandId，返回自己（应复用 order 本身，:110 已查 demand）
- `hasCompletionConfirmation:436-437` note 比对乱码（GBK 被当 UTF-8 解码），永不匹配——编码 bug

## 2. 修复方案（batch 预加载）

**核心**：列表场景 Controller 先 batch 查所有需要的 Entity（`findAllById`/`findAllByXxxIn`），传 `Map<Long, Entity>` 给 toView 方法；toView 用 map 查（map != null），单 detail 走 findById（map == null，原行为）。

### 2.1 Repository batch 方法（新增）
- `OrderRepository.findAllByDemandIdIn(Collection<Long> demandIds) -> List<Order>`（`selectList(in(demand_id))`，供 toDemandView batch 查 relatedOrder）
- `UserRepository.findAllById(Collection<Long> ids) -> List<User>`（`selectBatchIds`，供 toDemandView/toOrderView/toReviewView batch 查 publisher/accepter/author/target）—— **已 2C.4 加？确认**（2C.4 加的是 DemandRepository.findAllById，非 UserRepository）
- `DemandRepository.findAllById`（2C.4 已加）
- `ReviewRepository.findAllByOrderIdIn(Collection<Long> orderIds) -> List<Review>`（`selectList(in(order_id))`，供 toOrderView batch 查 reviews）

### 2.2 ApiViewMapper 重载 batch 版
- `toDemandView(Demand, CurrentUser, Map<Long,User> userMap, Map<Long,Order> orderMap)` —— map != null 时用 map，null 时走 findById（原 toDemandView 委托）
- `toOrderView(Order, CurrentUser, Map<Long,Demand> demandMap, Map<Long,User> userMap, Map<Long, List<Review>> reviewMap, Map<Long,Order> orderByDemandMap)` —— batch 版
- `toReviewView(Review, ..., Map<Long,User> userMap)` —— batch 版

### 2.3 Controller 列表 batch 预加载
- DemandController 列表：collect publisherIds + demandIds → `findAllById` + `findAllByDemandIdIn` → map → `toDemandView(d, currentUser, userMap, orderMap)`
- OrderController/AdminController 列表：collect demandIds + publisherIds + accepterIds + orderIds → batch → `toOrderView(o, currentUser, demandMap, userMap, reviewMap, orderByDemandMap)`
- NotificationApplicationServiceImpl.list：collect relatedIds → batch → `toNotificationResponse(n, demandMap, orderMap)`

### 2.4 附带 bug 修复
- toOrderView:145 删重复 `findByDemandId`（复用 :110 的 demand + order 本身）
- hasCompletionConfirmation:436-437 乱码 note 修复（编码）

## 3. 子阶段分解

| 子阶段 | 范围 | N+1 |
|---|---|---|
| 3.1 | OrderRepository.findAllByDemandIdIn + UserRepository.findAllById + DemandController 列表 toDemandView batch | #1.1 |
| 3.2 | ReviewRepository.findAllByOrderIdIn + toOrderView batch（OrderController/AdminController/DemandController 列表 + 嵌套 toDemandView/toReviewView batch）+ :145 bug 修复 | #1.2 + #1.4 |
| 3.3 | NotificationApplicationServiceImpl.list toNotificationResponse batch | #1.3 |
| 3.4 | hasCompletionConfirmation 编码 bug 修复（:436-437） | #1.4 |

## 4. 验收（全部完成后）
1. mvn test 全绿（基线 226 + 新增 batch 用例）。
2. ApiViewMapper 列表场景无逐条 findById/findByDemandId/findByOrderId（grep 确认 Controller 列表调 batch 版 toView）。
3. NotificationApplicationServiceImpl.list 无逐条 findById。
4. toOrderView:145 重复查 bug 修复。
5. hasCompletionConfirmation 编码 bug 修复。

## 5. 风险
- batch 版 toView 签名复杂（多 map 参数）——保持单 detail 版（原签名）兼容。
- Map 查询 vs findById 语义一致（entity 不存在时 map.get 返回 null，findById 返回 Optional.empty → null）✓
- batch `in(ids)` 大列表——列表分页（PageQuery size <=100）限制，可接受。
