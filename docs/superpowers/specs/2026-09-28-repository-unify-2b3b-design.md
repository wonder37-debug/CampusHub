# 仓储层统一阶段 2B.3b 设计（admin listPendingDemands SQL 下推）

- 日期：2026-09-28
- 状态：待评审（用户已授权直接执行）
- 分支：`refactor/optimization`（HEAD `e85180f`，2B.3a 已完成，测试 165/165）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 3 子阶段 b）
- 范围：新建 `demand.dto.DemandReviewQuery` + `DemandRepository` 扩 `findReviewPage`/`countReview` + `MyBatisDemandRepository` 实现 + `AdminApplicationServiceImpl.listPendingDemands` 下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #5）

`AdminApplicationServiceImpl.listPendingDemands`（`backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:154-175`）当前：

```java
List<Demand> filtered = demandRepository.findByStatus(DemandStatus.REVIEWING).stream()
    .filter(demand -> matchesDemandKeyword(demand, query.q()))
    .filter(demand -> matchesDemandCategory(demand, query.category()))
    .filter(demand -> matchesDemandCampusZone(demand, query.campusZone()))
    .sorted(Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
    .toList();

int page = query.pageQuery().page();
int size = query.pageQuery().size();
int fromIndex = Math.max(0, (page - 1) * size);
int toIndex = Math.min(filtered.size(), fromIndex + size);
List<DemandSummaryResponse> items = fromIndex >= filtered.size()
    ? List.of()
    : filtered.subList(fromIndex, toIndex).stream().map(DemandSummaryResponse::from).toList();
return new PageResponse<>(items, page, size, filtered.size());
```

即 `findByStatus(REVIEWING)` 全量加载审核中需求 → 3 道内存过滤（keyword title/description/location / category / campusZone）+ 内存排序（`createdAt DESC nullsLast`，**无 id tie-breaker**）+ `subList` 内存分页 → `filtered.size()` 作 total。无 SQL 级 `orderBy`/`LIMIT`/`like`/`count`。

### 1.2 与 2B.1 demand findPage 的差异

2B.1 的 `findPage(DemandQuery)` 用于公开列表，可见性 `OR`（`status IN (PENDING,IN_PROGRESS,COMPLETED) OR publisher_id=currentUserId`）+ 6 过滤（keyword title/description / category / campusZone / location / startTime）+ 4 排序（REWARD/TIME/DISTANCE/RECOMMEND）。

2B.3b 是 admin 审核列表，需求不同：
- `status` 固定 `REVIEWING`（不在 2B.1 公开可见性集合），用 `eq` 而非 `IN + OR`。
- `keyword` 匹配 `title`/`description`/**`location`** 三字段（2B.1 的 keyword 只匹配 title/description，location 是独立过滤；2B.3b 的 keyword 含 location，因 `matchesDemandKeyword` 内存逻辑如此）。
- 无 `location` 独立过滤（`AdminDemandQuery` 无 location 字段）。
- 无 `startTime` 过滤。
- 无 `currentUserId`/可见性 OR。
- 排序固定 `createdAt DESC`（无 sort 枚举）。

故 2B.3b **不复用** 2B.1 的 `buildWrapper`/`findPage`，新增独立的 `buildReviewWrapper` + `findReviewPage`/`countReview`。

### 1.3 层次反转问题（同 2B.3a，沿用先例）

`AdminDemandQuery` 位于 `admin.dto`，`DemandRepository` 位于 `demand.repository`。若 `DemandRepository.findReviewPage(AdminDemandQuery)` 直接接收 admin 模块 DTO，则**底层 demand 依赖上层 admin**，违反分层。

2B.3a 已建立先例：新建 `auth.dto.UserQueryCriteria` 避免 auth 仓储依赖 admin。2B.3b 沿用：新建 `demand.dto.DemandReviewQuery`（字段与 `AdminDemandQuery` 一致），`DemandRepository.findReviewPage` 接收 `DemandReviewQuery`，Service 层 `AdminDemandQuery → DemandReviewQuery` 映射。

### 1.4 私有方法引用范围（grep 已核实）

- `matchesDemandKeyword`（:351-358）/ `matchesDemandCategory`（:360-363）/ `matchesDemandCampusZone`（:365-368）：3 方法仅 `listPendingDemands`（:161-163）引用，下推后可删。
- `containsIgnoreCase`（:364）：2B.3a 删 `matchesUserKeyword` 后，仅被 `matchesDemandKeyword`（:351-353）用。2B.3b 删 `matchesDemandKeyword` 后，`containsIgnoreCase` **无其他引用**，可连带删。
- `Comparator` import：仍被 `listArbitrationOrders`（:185 `Comparator.comparing(Order::getUpdatedAt...)`）用（2B.3c 才删），**保留**。
- `Locale` import：仍被 `updateUserRole`（:137）/ `reviewDemand`（:210）/ `resolveOrderArbitration`（:262）用，**保留**。

### 1.5 `findByStatus` 保留

`DemandRepository.findByStatus(DemandStatus)` 调用方（grep 已确认）：
- `AdminApplicationServiceImpl:160`（`listPendingDemands`）—— **2B.3b 下推**（findReviewPage）
- `AdminApplicationServiceImpl:306`（`getDashboard` 聚合 `findByStatus(REVIEWING).size()`）—— 2B.3d 下推（改 `countByStatus`）

故 2B.3b 仅下推 :160，`findByStatus` 接口与实现**保留**（2B.3d 处理 :306）。

### 1.6 调用方契约

`listPendingDemands` 调用方：`AdminController.listPendingDemands`（拿 `PageResponse<DemandSummaryResponse>` 返回前端）。2B.3b 仅改 Service 内部实现，`PageResponse<DemandSummaryResponse>` 结构不变，Controller 与前端契约不动。

## 2. 目标 / 非目标

### 目标
1. 新建 `demand.dto.DemandReviewQuery` record（字段与 `AdminDemandQuery` 对齐：`q`/`category`/`campusZone`/`pageQuery`），解决层次反转。
2. `DemandRepository` 新增 `findReviewPage(DemandReviewQuery)` + `countReview(DemandReviewQuery)`（风格 A，与 2B.1/2B.3a 一致），`findAll`/`findByStatus`/`findPage`/`count` 保留。
3. `MyBatisDemandRepository` 实现：私有 `buildReviewWrapper(query)`（`eq(status, REVIEWING)` + keyword like(title/description/location OR) + category `eq`+upper + campusZone `eq`+upper，供 `findReviewPage`/`countReview` 共用）；`findReviewPage` 加 `orderByDesc(createdAt).orderByDesc(id)` + `LIMIT/OFFSET`；`countReview` 用 `selectCount`。
4. `AdminApplicationServiceImpl.listPendingDemands` 改用 `findReviewPage` + `countReview`，`AdminDemandQuery → DemandReviewQuery` 映射，删除 `findByStatus` + 3 道内存过滤 + 内存排序 + `subList` 分页。
5. 删除仅服务于旧 `listPendingDemands` 的 4 个私有方法：`matchesDemandKeyword` / `matchesDemandCategory` / `matchesDemandCampusZone` / `containsIgnoreCase`（后者连带删，无其他引用）。
6. 行为零回归：`PageResponse` / `DemandSummaryResponse` 字段与语义不变；`AdminController` 与前端契约不变；现有 `shouldListAndApproveReviewingDemand` 用例断言不变且全绿。

### 非目标
- 不删 `DemandRepository.findByStatus`（1.5，2B.3d 用）。
- 不删 `Comparator` import（`listArbitrationOrders` 仍用，2B.3c 处理）。
- 不改 `AdminDemandQuery` / `Demand` / `DemandEntity` / `DemandSummaryResponse` / `DemandMapper` / schema。
- 不动 `listArbitrationOrders`（:178）/ `getDashboard`（:297）（2B.3c/2B.3d）。
- 不动 `AdminApplicationServiceImpl` 双构造函数与 `@Autowired(required=false)`（子项目4）。
- 不修 N+1（2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)` / `DemandSummaryResponse` 字段结构不变；`AdminController` 转换不变；API 行为不变。
- 测试全绿：基线 165/165（2B.3a 后），每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisDemandRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致；LIKE 依赖默认 ci collation。

## 4. 设计

### 4.1 新建 `DemandReviewQuery`（层次反转修复，沿用 2B.3a 先例）

`backend/src/main/java/com/campushub/backend/demand/dto/DemandReviewQuery.java`：

```java
package com.campushub.backend.demand.dto;

import com.campushub.backend.common.model.PageQuery;

public record DemandReviewQuery(
    String q,
    String category,
    String campusZone,
    PageQuery pageQuery
) {

    public DemandReviewQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
    }
}
```

字段与 `AdminDemandQuery` 逐字对齐（`AdminDemandQuery` 紧凑构造器仅 `pageQuery` null 兜底，无 trim——2B.3b 沿用相同规范化）。Service 层 `AdminDemandQuery → DemandReviewQuery` 映射零语义损耗。

> 命名 `DemandReviewQuery`（而非 `DemandAdminQueryCriteria`）：表达"审核中需求查询"语义（`status=REVIEWING` 固定），与 2B.1 的 `DemandQuery`（公开可见性查询）区分；`Query` 后缀与 `DemandQuery`/`NotificationQuery` 一致。方法名 `findReviewPage`/`countReview` 与 `findPage`/`count` 区分。

### 4.2 Repository 接口扩展（风格 A，与 2B.1/2B.3a 一致）

`DemandRepository.java` 新增两方法（`findAll`/`findByStatus`/`findPage`/`count` 保留）：

```java
    /**
     * 按审核查询条件分页查询审核中需求（status=REVIEWING + 过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Demand> findReviewPage(DemandReviewQuery query);

    /**
     * 按审核查询条件统计匹配的审核中需求总数（过滤下推 SQL，用于分页 total）。
     *
     * @param query 查询条件，为 null 时返回 0
     */
    long countReview(DemandReviewQuery query);
```

并在 import 区追加：

```java
import com.campushub.backend.demand.dto.DemandReviewQuery;
```

### 4.3 MyBatisDemandRepository 实现

新增私有 `buildReviewWrapper(query)`（纯过滤，供 `findReviewPage`/`countReview` 共用）。`findReviewPage` 内直接 `orderByDesc(createdAt).orderByDesc(id)`（固定排序，无 `applySort`）。

```java
@Override
public List<Demand> findReviewPage(DemandReviewQuery query) {
    if (query == null) {
        return List.of();
    }
    LambdaQueryWrapper<DemandEntity> wrapper = buildReviewWrapper(query);
    wrapper.orderByDesc(DemandEntity::getCreatedAt)
           .orderByDesc(DemandEntity::getId);
    int size = query.pageQuery().size();
    long offset = (long) (query.pageQuery().page() - 1) * size;
    wrapper.last("LIMIT " + size + " OFFSET " + offset);
    return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
}

@Override
public long countReview(DemandReviewQuery query) {
    if (query == null) {
        return 0L;
    }
    return demandMapper.selectCount(buildReviewWrapper(query));
}

private LambdaQueryWrapper<DemandEntity> buildReviewWrapper(DemandReviewQuery query) {
    LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(DemandEntity::getStatus, DemandStatus.REVIEWING.name());
    String q = query.q();
    if (q != null && !q.isBlank()) {
        String keyword = q.trim();
        wrapper.and(w -> w.like(DemandEntity::getTitle, keyword)
            .or().like(DemandEntity::getDescription, keyword)
            .or().like(DemandEntity::getLocation, keyword));
    }
    String category = query.category();
    if (category != null && !category.isBlank()) {
        wrapper.eq(DemandEntity::getCategory, category.trim().toUpperCase(Locale.ROOT));
    }
    String zone = query.campusZone();
    if (zone != null && !zone.isBlank()) {
        wrapper.eq(DemandEntity::getCampusZone, zone.trim().toUpperCase(Locale.ROOT));
    }
    return wrapper;
}
```

在 import 区追加：

```java
import com.campushub.backend.demand.dto.DemandReviewQuery;
```

> `Locale` 已在 2B.1 时 import（`buildWrapper` 用 `toUpperCase(Locale.ROOT)`），无需重复。`DemandStatus` 已 import。

### 4.4 过滤下沉映射（内存逻辑 → SQL）

| # | 内存逻辑（现状） | SQL 下推（LambdaQueryWrapper） | 边界对齐说明 |
|---|---|---|---|
| 1 | `findByStatus(REVIEWING)` | `eq(status, 'REVIEWING')` | `DemandStatus.REVIEWING.name()` = `"REVIEWING"`，enum name 全大写存库，精确匹配 |
| 2 | `matchesDemandKeyword`：`title.toLowerCase().contains(q.trim().toLowerCase()) \|\| description \|\| location`（`containsIgnoreCase`） | `and(w -> w.like(title, q).or().like(description, q).or().like(location, q))`，`q = q.trim()`（**不 toLowerCase**，依赖 ci collation，与 2B.1/2B.3a keyword like 一致） | `q` 为 null/blank 跳过；`description`/`location` 为 NULL 的行 `NULL LIKE` → false，与内存 `value != null && value.contains` 一致 |
| 3 | `matchesDemandCategory`：`demand.getCategory().name().equalsIgnoreCase(category.trim())` | `eq(category, category.trim().toUpperCase(Locale.ROOT))` | `DemandCategory` enum name 全大写存库（`EXPRESS`/`STUDY_TUTORING`/`OTHER` 等），`toUpperCase` 后精确匹配，不依赖 collation；`category` null/blank 跳过 |
| 4 | `matchesDemandCampusZone`：`demand.getCampusZone().name().equalsIgnoreCase(campusZone.trim())` | `eq(campus_zone, campusZone.trim().toUpperCase(Locale.ROOT))` | `CampusZone` enum name 全大写存库（`XIANLIN`/`GULOU` 等），同 #3 |

### 4.5 排序下沉映射

| 内存逻辑（现状） | SQL 下推 | NULL 处理 |
|---|---|---|
| `Comparator.comparing(Demand::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed()` | `ORDER BY created_at DESC, id DESC` | `created_at` 为 `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（`schema-demand.sql:26`），无 NULL 位置问题 |

**id tie-breaker**：内存原逻辑 `reversed()` 只反转 `createdAt`，**无 `thenComparing(id)`**，故同 `createdAt` 的行顺序未定义（依赖 DB 返回顺序）。2B.3b 加 `orderByDesc(id)` tie-breaker 保证分页确定性（CodeRabbit fix 原则：所有 findPage 的 orderByDesc 链式追加 id tie-breaker）。加 id DESC 不改变现有行为（原无定义，现确定），仅消除分页不确定性。

> 与 2B.1 `applySort` 的 `TIME/DISTANCE/RECOMMEND` 分支一致（`orderByDesc(createdAt).orderByDesc(id)`）。与 2B.3a `applyUserSort` 的 id ASC 不同——user 内存 `thenComparing(id)` 在 `reversed()` 之后故 id 升序；demand 内存无 `thenComparing(id)`，2B.3b 按 CodeRabbit fix 惯例用 id DESC（与 2B.1 demand 一致）。

### 4.6 Service 改造

`AdminApplicationServiceImpl.listPendingDemands` 改为：

```java
@Override
public PageResponse<DemandSummaryResponse> listPendingDemands(Long operatorId, AdminDemandQuery query) {
    requireAdmin(operatorId);
    if (query == null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin demand query must not be null");
    }

    DemandReviewQuery reviewQuery = new DemandReviewQuery(
        query.q(), query.category(), query.campusZone(), query.pageQuery());
    List<Demand> demands = demandRepository.findReviewPage(reviewQuery);
    List<DemandSummaryResponse> items = demands.stream().map(DemandSummaryResponse::from).toList();
    long total = demandRepository.countReview(reviewQuery);
    int page = query.pageQuery().page();
    int size = query.pageQuery().size();
    return new PageResponse<>(items, page, size, total);
}
```

`AdminDemandQuery → DemandReviewQuery` 直接字段映射（两 record 字段逐一对齐）。`requireAdmin` / null 校验顺序不变。

### 4.7 私有方法与 import 清理

删除（仅旧 `listPendingDemands` 使用，已下沉，grep 确认无其他引用）：
- `matchesDemandKeyword`（:351-358）
- `matchesDemandCategory`（:360-363）
- `matchesDemandCampusZone`（:365-368）
- `containsIgnoreCase`（:364）—— **连带删**，2B.3a 删 `matchesUserKeyword` 后仅 `matchesDemandKeyword` 用，2B.3b 删后者后无引用

随之失效的 import（删除前需确认无其他方法引用）：
- **无**。`Comparator` 仍被 `listArbitrationOrders`（:185）用，**保留**；`Locale` 仍被 `updateUserRole`/`reviewDemand`/`resolveOrderArbitration` 用，**保留**；`Demand`/`DemandStatus`/`DemandSummaryResponse` 仍被新 `listPendingDemands`/`reviewDemand`/`getDashboard` 用，**保留**。

新增 import：
- `com.campushub.backend.demand.dto.DemandReviewQuery`

> `DemandStatus` import 保留——`reviewDemand`（:206 `demand.getStatus() != DemandStatus.REVIEWING`）/`getDashboard`（:306 `DemandStatus.REVIEWING`）仍用。`Demand` import 保留——新 `listPendingDemands`（`List<Demand> demands`）/`reviewDemand`/`getDashboard` 用。

### 4.8 `now` 一致性

审核查询无 `now`/时间边界依赖（不像 2B.1 的 `end_time >= now`），`findReviewPage`/`countReview` 无 `now` 取值，无毫秒窗口问题。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `demand/dto/DemandReviewQuery.java` | **新建** record（字段同 `AdminDemandQuery`） |
| `demand/repository/DemandRepository.java` | 加 `findReviewPage(DemandReviewQuery)` + `countReview(DemandReviewQuery)`（其余方法保留）+ import |
| `demand/repository/MyBatisDemandRepository.java` | 实现 `findReviewPage` + `countReview` + 私有 `buildReviewWrapper` + import |
| `admin/service/AdminApplicationServiceImpl.java` | `listPendingDemands` 改用 `findReviewPage`+`countReview` + `AdminDemandQuery→DemandReviewQuery` 映射；删 4 私有方法；加 `DemandReviewQuery` import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `demand/repository/MyBatisDemandRepositoryTest.java` | **新增** `findReviewPage`/`countReview` 用例 |
| `admin/service/AdminApplicationServiceImplTest.java` | **不改**（`shouldListAndApproveReviewingDemand` 作回归护栏） |

### 不受影响
- `AdminDemandQuery` / `Demand` / `DemandEntity` / `DemandSummaryResponse` / `DemandMapper` / schema
- `AdminController` 及前端契约
- `listArbitrationOrders` / `getDashboard`（2B.3c/2B.3d）
- `Comparator` import / `findByStatus`（2B.3c/2B.3d）
- 索引（`idx_demand_status`/`idx_demand_category`/`idx_demand_campus_zone`/`idx_demand_created_at` 已覆盖下推查询）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `AdminApplicationServiceImplTest.shouldListAndApproveReviewingDemand`：`createDemand("待审核跑腿", "EXPRESS")` → `listPendingDemands(adminId, AdminDemandQuery("待审核", "EXPRESS", null, page1 size20))` 断言 `total=1`、`items[0].id=reviewingDemand.id`。下推后 `status='REVIEWING' AND title LIKE '%待审核%' AND category='EXPRESS' ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0` 等价。

### 6.2 新增 `MyBatisDemandRepositoryTest` 用例（@MybatisPlusTest + @Sql schema-demand.sql）

> 现有 `MyBatisDemandRepositoryTest` 已有 2B.1 的 11 个 `findPage`/`count` 用例 + 原有用例。新增 `findReviewPage`/`countReview` 用例沿用同一切片配置与 `newDemand`/`newDemandWithCreated` 工厂。需造 `status=REVIEWING` 的 demand（`newDemand` 默认 `REVIEWING`，见 2B.1 测试工厂）。

1. `findReviewPage` 仅返回 `REVIEWING` 状态（插入 REVIEWING + PENDING + COMPLETED，仅 REVIEWING 命中）。
2. `findReviewPage` keyword（title/description/location 三字段 OR）：title 含 / description 含 / location 含 / 都不命中空集。
3. `findReviewPage` category eq（传 `"express"` 小写命中 `EXPRESS`）。
4. `findReviewPage` campusZone eq（传 `"xianlin"` 小写命中 `XIANLIN`）。
5. `findReviewPage` 排序 `created_at DESC + id DESC`（插入不同 createdAt + 同 createdAt 验证 id 降序 tie-breaker）。
6. `findReviewPage` 分页 `LIMIT/OFFSET`：插入 5 条 REVIEWING，`PageQuery(1,2)`/`(2,2)`/`(3,2)` 返回 2/2/1。
7. `findReviewPage` 组合过滤（keyword + category + campusZone 同时）。
8. `countReview` 与 `findReviewPage`（同条件、不限分页）总数一致。
9. `findReviewPage(null)` / `countReview(null)` 返回空/0（防御）。

> 现有 `MyBatisDemandRepositoryTest` 的 `save`/`findById`/`findAll`/`findByStatus`/`findPage`/`count` 用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 165 + 新增（约 9 个 findReviewPage/countReview 用例）。
2. `AdminApplicationServiceImpl.listPendingDemands` 不再出现 `demandRepository.findByStatus`、`matchesDemandKeyword`、`matchesDemandCategory`、`matchesDemandCampusZone`、`containsIgnoreCase`、`subList`。
3. `DemandRepository` 暴露 `findReviewPage(DemandReviewQuery)` + `countReview(DemandReviewQuery)`；`MyBatisDemandRepository` 实现含 `buildReviewWrapper`。
4. `demand.dto.DemandReviewQuery` record 存在，字段与 `AdminDemandQuery` 对齐。
5. `PageResponse` / `DemandSummaryResponse` 字段不变；`AdminController` 转换不变；`shouldListAndApproveReviewingDemand` 断言不变且全绿。
6. `listArbitrationOrders` / `getDashboard` 行为不变（未触动）。
7. `Comparator` import / `findByStatus` 保留（2B.3c/2B.3d 用）。
8. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `like` 大小写在 H2 MySQL 模式与生产 MySQL 不一致 | 2B 总体风险表已背书一致（均 ci collation）；`category`/`campusZone` 用 `toUpperCase` 精确匹配规避 collation 依赖；`like` 用例在 H2 验证 |
| `description`/`location` NULL 行在 keyword like 时不匹配与内存不一致 | `NULL LIKE` → false，与内存 `value != null && value.contains` 一致（4.4 #2） |
| 加 `id DESC` tie-breaker 改变行为 | 内存原无 `thenComparing(id)`，同 createdAt 行顺序未定义；加 id DESC 仅消除不确定性，不改变可观察行为（4.5） |
| `AdminDemandQuery → DemandReviewQuery` 映射遗漏字段 | 两 record 字段逐一对齐（q/category/campusZone/pageQuery），`AdminDemandQuery` 紧凑构造器仅 pageQuery 兜底，`DemandReviewQuery` 同；映射零损耗（4.6） |
| 删 `containsIgnoreCase` 误伤其他调用方 | grep 已确认 2B.3a 后仅 `matchesDemandKeyword` 用（1.4）；2B.3b 删后者后无引用 |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`/`long`（`PageQuery` 已校验范围），无注入风险 |
