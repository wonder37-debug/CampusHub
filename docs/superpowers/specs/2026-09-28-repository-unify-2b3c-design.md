# 仓储层统一阶段 2B.3c 设计（admin listArbitrationOrders SQL 下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/optimization`（HEAD `801a446`，2B.3b 已完成，测试 174/174）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 3 子阶段 c）
- 范围：`OrderRepository` 扩 `findArbitrationPage`/`countArbitration` + `MyBatisOrderRepository` 实现 + `AdminApplicationServiceImpl.listArbitrationOrders` 下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #6）

`AdminApplicationServiceImpl.listArbitrationOrders`（`backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:165-181`）当前：

```java
int resolvedPage = Math.max(page, 1);
int resolvedSize = Math.max(size, 1);

List<Order> filtered = orderRepository.findAll().stream()
    .filter(order -> order.getStatus() == OrderStatus.IN_ARBITRATION)
    .sorted(Comparator.comparing(Order::getUpdatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
    .toList();

int fromIndex = Math.max(0, (resolvedPage - 1) * resolvedSize);
int toIndex = Math.min(filtered.size(), fromIndex + resolvedSize);
List<OrderSummaryResponse> items = fromIndex >= filtered.size()
    ? List.of()
    : filtered.subList(fromIndex, toIndex).stream().map(OrderSummaryResponse::from).toList();
return new PageResponse<>(items, resolvedPage, resolvedSize, filtered.size());
```

即 `findAll()` 全量加载（含逐条 `loadHistory` N+1）→ 内存过滤（`status==IN_ARBITRATION`）+ 内存排序（`updatedAt DESC nullsLast`，**无 id tie-breaker**）+ `subList` 内存分页 → `filtered.size()` 作 total。无 SQL 级 `orderBy`/`LIMIT`/`count`。签名用 `int page, int size`（非 `PageQuery`），`Math.max(page,1)`/`Math.max(size,1)` 兜底。

### 1.2 与 2B.3a/2B.3b 的差异（更简单）

- **无层次反转**：`listArbitrationOrders` 签名 `(Long operatorId, int page, int size)`，无 query DTO，`OrderRepository` 直接接 `int page, int size`，不依赖 admin 模块类型。无需新建 criteria record。
- **无 keyword/category/campusZone 过滤**：仅 `status==IN_ARBITRATION` 单条件。
- **固定排序**：`updatedAt DESC nullsLast`（无 sort 枚举、无 asc/desc 双轴）。
- **`int page/size` + `Math.max` 兜底**（非 `PageQuery` 构造校验）：保持现状语义（`page<1`→1、`size<1`→1，不抛异常），避免引入 `PageQuery` 改变行为（`PageQuery` 对 `page<1` 抛 `BusinessException`，会破坏前端传 `page=0` 返回第 1 页的现有契约）。
- **不需 `loadHistory`**：`listArbitrationOrders` 用 `OrderSummaryResponse::from`，仅取 `id`/`demandId`/`publisherId`/`accepterId`/`status`/`createdAt`/`completedAt`（`OrderSummaryResponse.java:16-26`），**不依赖 `statusHistory`/`updatedAt`**。故 `findArbitrationPage` 直接 `selectList(wrapper).map(e -> e.toDomain(Collections.emptyList()))`，**不调 `loadHistory`**，避免 `findAll` 的 N+1（N+1 的彻底批量化留 2C）。

### 1.3 `OrderEntity.toDomain` 签名

`OrderEntity.toDomain(List<OrderStatusHistoryEntry> history)`（`OrderEntity.java:96`）**需传 history 参数**（无无参版本），但 `history==null` 时内部兜底为 `new ArrayList<>()`（:109）。`findArbitrationPage` 传 `Collections.emptyList()`（`MyBatisOrderRepository` 已 import `Collections`，:11），返回 `Order` 含空 `statusHistory`——`OrderSummaryResponse.from` 不读 `statusHistory`，行为等价。

### 1.4 私有方法与 import 引用范围（grep 已核实）

- `Comparator` import（:38）：2B.3b 删 `listPendingDemands` 的 `Comparator.comparing(...)` 后，仅 `listArbitrationOrders`（:174 `Comparator.comparing(Order::getUpdatedAt...)`）用。2B.3c 删后者后，`Comparator` **无其他引用**，可连带删 import。
- `orderRepository.findAll()`（grep 已确认）：
  - `AdminApplicationServiceImpl:172`（`listArbitrationOrders`）—— **2B.3c 下推**（findArbitrationPage）
  - `AdminApplicationServiceImpl:291`（`getDashboard` 聚合）—— 2B.3d 下推（改 `count()`）

故 2B.3c 仅下推 :172，`OrderRepository.findAll` 接口与实现**保留**（2B.3d 处理 :291；`findByParticipant` 2B.5 处理）。

### 1.5 调用方契约

`listArbitrationOrders` 调用方：`AdminController.listArbitrationOrders`（拿 `PageResponse<OrderSummaryResponse>` 返回前端）。2B.3c 仅改 Service 内部实现，`PageResponse<OrderSummaryResponse>` 结构不变，Controller 与前端契约不动。签名 `(Long operatorId, int page, int size)` 不变。

## 2. 目标 / 非目标

### 目标
1. `OrderRepository` 新增 `findArbitrationPage(int page, int size)` + `countArbitration()`（风格 A，与 2B.1/2B.3a/2B.3b 一致，但 `page`/`size` 用 `int` 贴合现有签名），`findAll`/`findByParticipant`/`findById`/`findByDemandId`/`save`/`deleteById` 保留。
2. `MyBatisOrderRepository` 实现：`findArbitrationPage` 用 `eq(status, IN_ARBITRATION)` + `orderByDesc(updatedAt).orderByDesc(id)` + `LIMIT/OFFSET`，**不调 `loadHistory`**（`selectList(wrapper).map(e -> e.toDomain(Collections.emptyList()))`）；`countArbitration` 用 `selectCount`。
3. `AdminApplicationServiceImpl.listArbitrationOrders` 改用 `findArbitrationPage` + `countArbitration`，删除 `findAll` + 内存过滤 + 内存排序 + `subList` 分页。保持 `Math.max(page,1)`/`Math.max(size,1)` 兜底与 `int page/size` 签名。
4. 删除连带失效的 `Comparator` import（2B.3b 后仅 `listArbitrationOrders` 用）。
5. 行为零回归：`PageResponse` / `OrderSummaryResponse` 字段与语义不变；`AdminController` 与前端契约不变；现有 `shouldListArbitrationOrdersByActualStatus` 用例断言不变且全绿。

### 非目标
- 不删 `OrderRepository.findAll`/`findByParticipant`（1.4，2B.3d/2B.5 用）。
- 不改 `Order` / `OrderEntity` / `OrderSummaryResponse` / `OrderMapper` / schema。
- 不改 `listArbitrationOrders` 签名（`int page, int size` 保持，不引入 `PageQuery`）。
- 不动 `getDashboard`（:286-315）（2B.3d）。
- 不动 `AdminApplicationServiceImpl` 双构造函数与 `@Autowired(required=false)`（子项目4）。
- 不修 N+1 的彻底批量化（`findArbitrationPage` 避免逐条 `loadHistory` 是附带优化，`findByParticipant` 的 N+1 留 2B.5/2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)` / `OrderSummaryResponse` 字段结构不变；`AdminController` 转换不变；API 行为不变；`listArbitrationOrders` 签名不变。
- 测试全绿：基线 174/174（2B.3b 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisOrderRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致。

## 4. 设计

### 4.1 Repository 接口扩展（风格 A，int page/size）

`OrderRepository.java` 新增两方法（`findAll`/`findByParticipant`/`findById`/`findByDemandId`/`save`/`deleteById` 保留）：

```java
    /**
     * 分页查询仲裁中订单（status=IN_ARBITRATION + updatedAt DESC + LIMIT/OFFSET 下推 SQL）。
     *
     * <p>不加载 statusHistory（调用方用 OrderSummaryResponse 不依赖历史）；避免 findAll 的逐条 loadHistory N+1。</p>
     *
     * @param page 页码（>=1，由 Service 层 Math.max 兜底）
     * @param size 每页大小（>=1，由 Service 层 Math.max 兜底）
     */
    List<Order> findArbitrationPage(int page, int size);

    /**
     * 统计仲裁中订单总数（status=IN_ARBITRATION 下推 SQL，用于分页 total）。
     */
    long countArbitration();
```

> `page`/`size` 用 `int`（非 `PageQuery`）：贴合 `listArbitrationOrders(Long, int, int)` 现有签名与 `Math.max` 兜底语义，避免引入 `PageQuery` 的 `page<1` 抛异常改变行为。

### 4.2 MyBatisOrderRepository 实现

```java
@Override
public List<Order> findArbitrationPage(int page, int size) {
    LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getStatus, OrderStatus.IN_ARBITRATION.name())
        .orderByDesc(OrderEntity::getUpdatedAt)
        .orderByDesc(OrderEntity::getId);
    long offset = (long) (page - 1) * size;
    wrapper.last("LIMIT " + size + " OFFSET " + offset);
    return orderMapper.selectList(wrapper).stream()
        .map(e -> e.toDomain(Collections.emptyList()))
        .toList();
}

@Override
public long countArbitration() {
    LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getStatus, OrderStatus.IN_ARBITRATION.name());
    return orderMapper.selectCount(wrapper);
}
```

在 import 区追加：

```java
import com.campushub.backend.order.domain.OrderStatus;
```

> `Collections` 已 import（:11）。`LambdaQueryWrapper`/`OrderEntity`/`OrderMapper` 已 import。`OrderStatus` 需确认是否已 import（`save`/`findById` 等未直接用 `OrderStatus`，可能未 import——plan 实现时以编译通过为准）。

### 4.3 过滤下沉映射

| # | 内存逻辑（现状） | SQL 下推 | 边界对齐 |
|---|---|---|---|
| 1 | `order.getStatus() == OrderStatus.IN_ARBITRATION` | `eq(status, 'IN_ARBITRATION')` | `OrderStatus.IN_ARBITRATION.name()` = `"IN_ARBITRATION"`，enum name 全大写存库，精确匹配 |

### 4.4 排序下沉映射

| 内存逻辑（现状） | SQL 下推 | NULL 处理 |
|---|---|---|
| `Comparator.comparing(Order::getUpdatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed()` | `ORDER BY updated_at DESC, id DESC` | `updated_at` 为 `DATETIME`（可 null，`schema-order.sql:18` 无 NOT NULL/DEFAULT）。MySQL/H2 中 `DESC` 天然 NULL 末尾（NULL 视为最小，DESC 从大到小 NULL 在末尾）= 内存 `nullsLast` ✓ |

**id tie-breaker**：内存原逻辑 `reversed()` 只反转 `updatedAt`，**无 `thenComparing(id)`**，同 `updatedAt`（含同为 null）的行顺序未定义。2B.3c 加 `orderByDesc(id)` tie-breaker 保证分页确定性（CodeRabbit fix 原则）。加 id DESC 不改变可观察行为（原无定义，现确定）。

> 仅 DESC 分支（无 asc），无 2B.3a nickname 的 ASC NULL 位置矛盾。`updated_at` 可 null 但 DESC 天然 nullsLast，与内存一致。

### 4.5 不调 `loadHistory`（附带 N+1 优化）

`findAll`/`findByParticipant` 经 `assembleAll` 逐条 `loadHistory`（N+1）。`findArbitrationPage` 直接 `selectList(wrapper).map(e -> e.toDomain(Collections.emptyList()))`，返回 `Order` 含空 `statusHistory`。

`OrderSummaryResponse.from(Order)`（`OrderSummaryResponse.java:16-26`）仅取 `id`/`demandId`/`publisherId`/`accepterId`/`status`/`createdAt`/`completedAt`，**不读 `statusHistory`/`updatedAt`**。故空 `statusHistory` 不影响 `OrderSummaryResponse` 输出，行为等价。

> `findArbitrationPage` 不需 `updatedAt` 字段在 `Order` domain——`OrderSummaryResponse.from` 不读 `updatedAt`。但 `updatedAt` 仍由 `toDomain` 填入 `Order`（:107 `order.setUpdatedAt(this.updatedAt)`），仅 `OrderSummaryResponse` 不序列化它。无影响。

### 4.6 Service 改造

`AdminApplicationServiceImpl.listArbitrationOrders` 改为：

```java
@Override
public PageResponse<OrderSummaryResponse> listArbitrationOrders(Long operatorId, int page, int size) {
    requireAdmin(operatorId);
    int resolvedPage = Math.max(page, 1);
    int resolvedSize = Math.max(size, 1);

    List<Order> orders = orderRepository.findArbitrationPage(resolvedPage, resolvedSize);
    List<OrderSummaryResponse> items = orders.stream().map(OrderSummaryResponse::from).toList();
    long total = orderRepository.countArbitration();
    return new PageResponse<>(items, resolvedPage, resolvedSize, total);
}
```

`Math.max` 兜底保持（与现状一致）。`requireAdmin` 在前（权限校验先于查询）。

### 4.7 import 清理

删除（2B.3b 后仅 `listArbitrationOrders` 用，grep 确认）：

```java
import java.util.Comparator;
```

> 删前 grep 确认 `Comparator` 在 `AdminApplicationServiceImpl.java` 无其他引用（2B.3b 删 `listPendingDemands` 的 `Comparator.comparing` 后，仅 :174 `listArbitrationOrders` 用；2B.3c 删后者后无引用）。

保留：`java.util.Locale`（`updateUserRole`/`reviewDemand`/`resolveOrderArbitration` 用）；`Order`/`OrderStatus`/`OrderSummaryResponse`（新 `listArbitrationOrders`/`resolveOrderArbitration`/`deleteOrder` 用）；`orderRepository` 字段。

新增：无（`OrderRepository` 已有 `orderRepository` 字段）。

### 4.8 `now` 一致性

仲裁查询无 `now`/时间边界依赖，`findArbitrationPage`/`countArbitration` 无 `now` 取值，无毫秒窗口问题。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `order/repository/OrderRepository.java` | 加 `findArbitrationPage(int, int)` + `countArbitration()`（其余方法保留） |
| `order/repository/MyBatisOrderRepository.java` | 实现 `findArbitrationPage` + `countArbitration`（不调 loadHistory）+ import（OrderStatus 若需） |
| `admin/service/AdminApplicationServiceImpl.java` | `listArbitrationOrders` 改用 `findArbitrationPage`+`countArbitration`；删 `Comparator` import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `order/repository/MyBatisOrderRepositoryTest.java` | **新增** `findArbitrationPage`/`countArbitration` 用例 |
| `admin/service/AdminApplicationServiceImplTest.java` | **不改**（`shouldListArbitrationOrdersByActualStatus` 作回归护栏） |

### 不受影响
- `Order` / `OrderEntity` / `OrderSummaryResponse` / `OrderMapper` / `OrderStatusLogMapper` / schema
- `AdminController` 及前端契约
- `getDashboard`（2B.3d）/ `OrderApplicationServiceImpl.listHistory`（2B.5）
- `findAll`/`findByParticipant`/`findById`/`findByDemandId`（保留）
- 索引（`idx_order_publisher`/`idx_order_accepter` 已有；`status` 列无索引——2D 已评估，选择性低不下推，2B.3c 不动 schema）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `AdminApplicationServiceImplTest.shouldListArbitrationOrdersByActualStatus`：`createDemand` → `accept` → `requestArbitration` → `listArbitrationOrders(adminId, 1, 20)` 断言 `total=1`、`items[0].orderId`、`items[0].status="IN_ARBITRATION"`。下推后 `status='IN_ARBITRATION' ORDER BY updated_at DESC, id DESC LIMIT 20 OFFSET 0` 等价。

### 6.2 新增 `MyBatisOrderRepositoryTest` 用例（@MybatisPlusTest + @Sql schema-order.sql）

> 需读现有 `MyBatisOrderRepositoryTest` 确认切片配置与工厂方法（`newOrder` 等）。造 `IN_ARBITRATION` 状态的 order 需 `setStatus(OrderStatus.IN_ARBITRATION)` + `save`。

1. `findArbitrationPage` 仅返回 `IN_ARBITRATION`（插入 IN_ARBITRATION + PENDING + COMPLETED，仅 IN_ARBITRATION 命中）。
2. `findArbitrationPage` 排序 `updated_at DESC + id DESC`（插入不同 updatedAt + 同 updatedAt 验证 id 降序 tie-breaker）。
3. `findArbitrationPage` `updated_at` NULL 排末尾（插入有 updatedAt + null updatedAt，null 在后，验证 nullsLast）。
4. `findArbitrationPage` 分页 `LIMIT/OFFSET`：插入 5 条 IN_ARBITRATION，`page=1,size=2`/`page=2,size=2`/`page=3,size=2` 返回 2/2/1。
5. `findArbitrationPage` 返回 `Order` 含空 `statusHistory`（验证不调 loadHistory：`statusHistory` 为空 list）。
6. `countArbitration` 仅计 `IN_ARBITRATION`（插入混合 status，仅 IN_ARBITRATION 数）。
7. `countArbitration` 与 `findArbitrationPage`（同条件、不限分页）总数一致。

> 现有 `MyBatisOrderRepositoryTest` 的 `save`/`findById`/`findByDemandId`/`findByParticipant`/`findAll`/`deleteById` 用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 174 + 新增（约 7 个 findArbitrationPage/countArbitration 用例）。
2. `AdminApplicationServiceImpl.listArbitrationOrders` 不再出现 `orderRepository.findAll()`、`Comparator`、`subList`。
3. `OrderRepository` 暴露 `findArbitrationPage(int, int)` + `countArbitration()`；`MyBatisOrderRepository` 实现不调 `loadHistory`（用 `toDomain(Collections.emptyList())`）。
4. `PageResponse` / `OrderSummaryResponse` 字段不变；`AdminController` 转换不变；`listArbitrationOrders` 签名不变；`shouldListArbitrationOrdersByActualStatus` 断言不变且全绿。
5. `getDashboard` 行为不变（未触动）。
6. `findAll`/`findByParticipant` 保留（2B.3d/2B.5 用）。
7. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `updated_at` NULL 行在 DESC 排序时位置与内存 `nullsLast` 不一致 | MySQL/H2 中 `DESC` 天然 NULL 末尾（NULL 视为最小）= `nullsLast` ✓；测试用例 3 显式覆盖（4.4） |
| 加 `id DESC` tie-breaker 改变行为 | 内存原无 `thenComparing(id)`，同 updatedAt 行顺序未定义；加 id DESC 仅消除不确定性（4.4） |
| 不调 `loadHistory` 致 `Order.statusHistory` 为空影响 `OrderSummaryResponse` | `OrderSummaryResponse.from` 不读 `statusHistory`（4.5）；测试用例 5 显式验证空 history |
| `int page/size` 不走 `PageQuery` 校验致 offset 越界 | `Math.max(page,1)`/`Math.max(size,1)` 兜底（Service 层），`offset=(page-1)*size` 必非负；`offset` 用 `long` 防 overflow |
| 删 `Comparator` import 误伤其他调用方 | grep 已确认 2B.3b 后仅 `listArbitrationOrders` 用（1.4）；2B.3c 删后者后无引用 |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`/`long`（`Math.max` 兜底后 >=1），无注入风险 |
| `status` 列无索引致全表扫描 | `idx_order_status` 未建（2D 评估选择性低）；2B.3c 不动 schema，性能留后续评估 |
