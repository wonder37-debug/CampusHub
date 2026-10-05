# 仓储层统一阶段 2B.1 设计（demand list SQL 下推）

- 日期：2026-09-28
- 状态：待评审
- 分支：`refactor/optimization`（HEAD `a7f166b`，2A/2D/2E 已完成）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 1 子阶段）
- 范围：`DemandRepository` 接口扩展 + `MyBatisDemandRepository` 实现 + `DemandApplicationServiceImpl.list` 查询下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #1）

`DemandApplicationServiceImpl.list`（`backend/src/main/java/com/campushub/backend/demand/service/DemandApplicationServiceImpl.java:122-148`）当前：

```java
LocalDateTime now = LocalDateTime.now();
Stream<Demand> filtered = demandRepository.findAll().stream()
    .filter(demand -> isPubliclyVisible(demand, now)
        || (query.currentUserId() != null && demand.getPublisherId() != null
            && demand.getPublisherId().equals(query.currentUserId())))
    .filter(demand -> matchesKeyword(demand, query.q()))
    .filter(demand -> matchesCategory(demand, query.category()))
    .filter(demand -> matchesCampusZone(demand, query.campusZone()))
    .filter(demand -> matchesLocation(demand, query.location()))
    .filter(demand -> matchesStartTimeRange(demand, query.startTimeFrom(), query.startTimeTo()));

List<Demand> sorted = filtered.sorted(resolveComparator(query.sort())).toList();
int page = query.pageQuery().page();
int size = query.pageQuery().size();
int fromIndex = Math.max(0, (page - 1) * size);
int toIndex = Math.min(sorted.size(), fromIndex + size);
List<DemandSummaryResponse> items = fromIndex >= sorted.size()
    ? List.of()
    : sorted.subList(fromIndex, toIndex).stream().map(DemandSummaryResponse::from).toList();

return new PageResponse<>(items, page, size, sorted.size());
```

即 `findAll()` 全表加载 → 6 道内存过滤 → 内存排序 → `subList` 内存分页 → `sorted.size()` 作 total。无任何 SQL 级 `orderBy`/`LIMIT`/`like`/`count`（仅 `findByStatus` 用了 `eq`）。

### 1.2 RECOMMEND 排序的真实分流路径（关键边界）

经核实 `DemandController.list`（`backend/src/main/java/com/campushub/backend/api/DemandController.java:90-117`）：

1. 无条件调 `demandApplicationService.list(DemandQuery(...))` 拿到 `rawPage`；
2. 仅当 `resolvedSort == DemandSort.RECOMMEND && currentUser != null` 时，再调 `reorderWithRecommendations` → `RecommendationApplicationService.recommend` 对**当前页 items** 重排。

而 `DemandApplicationServiceImpl.resolveComparator`（第 448-456 行）对 `RECOMMEND` 走 `created_at DESC`（与 `TIME`/`DISTANCE` 同分支）。因此：

- `DemandApplicationServiceImpl.list` 的 `RECOMMEND` 分支仅承担"未登录用户请求 RECOMMEND"或"登录但后续被 Controller 重排覆盖"的初始排序，当前语义 = `created_at DESC`；
- 真正推荐算法在 `RecommendationApplicationService`（其 `filterCandidateDemands` 仍用 `findAll`，属 2B.6）。

故 2B.1 将 `RECOMMEND` 下推为 `ORDER BY created_at DESC`（与现状逐字等价），**不**在 `list` 内引入对 `RecommendationApplicationService` 的依赖，**不**改变 Controller 分流逻辑。

### 1.3 保留项

- `DemandRepository.findAll()`：仍被 `RecommendationApplicationServiceImpl.filterCandidateDemands:141` 使用，2B.6 才下推，2B.1 保留接口与实现。
- `DemandRepository.findByStatus()`：供 2B.3 admin `listPendingDemands` 使用，保留。

## 2. 目标 / 非目标

### 目标
1. `DemandRepository` 新增 `findPage(DemandQuery)` + `count(DemandQuery)`（风格 A，分页与计数分离，Repository 返回 domain）。
2. `MyBatisDemandRepository` 用 `LambdaQueryWrapper` 实现：过滤（`eq`/`like`/`in`/`isNotNull`/`ge`/`le`）+ 可见性 `OR` 组合 + 排序（`orderByDesc`）+ 分页（`last("LIMIT ... OFFSET ...")`）+ 计数（`selectCount`），提取 `buildWrapper(query)` 供 `findPage`/`count` 共用。
3. `DemandApplicationServiceImpl.list` 改用 `findPage` + `count`，删除 `findAll` + 6 道内存过滤 + `resolveComparator` + `subList` 分页。
4. 删除仅服务于旧 `list` 的私有方法：`isPubliclyVisible` / `isExpired` / `matchesKeyword` / `matchesCategory` / `matchesCampusZone` / `matchesLocation` / `matchesStartTimeRange` / `resolveComparator`，并清理随之失效的 import。
5. 行为零回归：`PageResponse` / `DemandSummaryResponse` 字段与语义不变；现有 `DemandApplicationServiceImplTest` 两个 `list` 用例断言不变且全绿。

### 非目标
- 不删 `findAll` / `findByStatus`（2B.3 / 2B.6 处理）。
- 不改 `DemandQuery` / `DemandSort` / `Demand` / `DemandEntity` / `DemandSummaryResponse` / `DemandMapper`。
- 不改 `DemandController`（含其 `reorderWithRecommendations` 与 N+1 `findById` 重查，留待子项目3 / 子项目5）。
- 不在 `list` 内引入 `RecommendationApplicationService` 依赖。
- 不动 `DemandApplicationServiceImpl` 双构造函数与 `@Autowired(required=false)`（子项目4）。
- 不修 N+1（2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)` 结构不变；`DemandSummaryResponse` 字段不变；API 行为不变。
- 测试全绿：基线 125/125，每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisDemandRepository` 实现；InMemory 已删（2A），MyBatis 为唯一 bean。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致；LIKE 大小写依赖默认 ci collation。

## 4. 设计

### 4.1 Repository 接口扩展（风格 A）

`DemandRepository.java` 新增两方法（`findAll` / `findByStatus` 保留）：

```java
/**
 * 按查询条件分页查询需求（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
 */
List<Demand> findPage(DemandQuery query);

/**
 * 按查询条件统计匹配的需求总数（过滤下推 SQL，用于分页 total）。
 */
long count(DemandQuery query);
```

`DemandQuery` 已含全部所需输入（`q` / `category` / `campusZone` / `location` / `startTimeFrom` / `startTimeTo` / `sort` / `pageQuery` / `currentUserId`），同模块 DTO 直接作为查询参数，不另建 criteria 对象（YAGNI）。

### 4.2 MyBatisDemandRepository 实现

新增私有 `buildWrapper(query)`（纯过滤，供 `findPage` / `count` 共用）+ `applySort(wrapper, sort)`（仅 `findPage` 调）。

```java
@Override
public List<Demand> findPage(DemandQuery query) {
    if (query == null) {
        return List.of();
    }
    LambdaQueryWrapper<DemandEntity> wrapper = buildWrapper(query);
    applySort(wrapper, query.sort());
    int size = query.pageQuery().size();
    int offset = (query.pageQuery().page() - 1) * size;
    wrapper.last("LIMIT " + size + " OFFSET " + offset);
    return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
}

@Override
public long count(DemandQuery query) {
    if (query == null) {
        return 0L;
    }
    return demandMapper.selectCount(buildWrapper(query));
}
```

> `last("LIMIT " + size + " OFFSET " + offset)` 入参为 `int`，无注入风险；`PageQuery` 构造时已校验 `page>=1`、`1<=size<=100`，`offset` 必非负。

### 4.3 过滤下沉映射（内存逻辑 → SQL）

| # | 内存逻辑（现状） | SQL 下推（LambdaQueryWrapper） | 边界对齐说明 |
|---|---|---|---|
| 1 | `isPubliclyVisible`：未过期且 `status ∈ {PENDING, IN_PROGRESS, COMPLETED}`；OR 自己的（`publisherId == currentUserId`） | `((end_time IS NULL OR end_time >= :now) AND status IN ('PENDING','IN_PROGRESS','COMPLETED')) OR (publisher_id = :uid)` | `currentUserId == null` 时不加 `OR publisher_id` 分支（未登录仅公开可见） |
| 2 | `matchesKeyword(q)`：`title.toLowerCase().contains(q.trim().toLowerCase())` 或 `description` 同 | `(title LIKE :q OR description LIKE :q)`，`q` 两端加 `%` | `q` 为 null/blank 跳过；`description` 为 NULL 的行 `NULL LIKE` → false，与内存 `description != null &&` 一致 |
| 3 | `matchesCategory`：`category.name().equalsIgnoreCase(cat)` | `category = :cat`，入参 `cat.trim().toUpperCase(Locale.ROOT)` | enum name 全大写存库，`toUpperCase` 后精确匹配，不依赖 collation |
| 4 | `matchesCampusZone`：同上 | `campus_zone = :zone`，入参 `zone.trim().toUpperCase(Locale.ROOT)` | 同 #3 |
| 5 | `matchesLocation`：`location.toLowerCase().contains(loc.trim().toLowerCase())` | `location LIKE :loc`（两端 `%`） | 依赖 ci collation 不区分大小写（H2 MySQL 模式与生产 MySQL 一致，2B 总体风险表已背书）；`location` 为 NULL 的行不匹配 |
| 6 | `matchesStartTimeRange`：`startTime==null` 时仅 `from==null && to==null` 返回 true；否则 `startTime>=from && startTime<=to` | `from`/`to` 皆 null：不加条件（含 `start_time IS NULL`）；任一非 null：`start_time IS NOT NULL AND (from!=null → start_time>=:from) AND (to!=null → start_time<=:to)` | `isNotNull` 排除 `startTime==null` 行，与内存分支一致 |

### 4.4 可见性 OR 条件构造

`buildWrapper` 内可见性片段（`now` 取 `LocalDateTime.now()`）：

```java
LocalDateTime now = LocalDateTime.now();
Long currentUserId = query.currentUserId();
List<String> publicStatuses = List.of(
    DemandStatus.PENDING.name(),
    DemandStatus.IN_PROGRESS.name(),
    DemandStatus.COMPLETED.name());
wrapper.and(w -> {
    w.and(v -> v.isNull(DemandEntity::getEndTime).or().ge(DemandEntity::getEndTime, now))
        .in(DemandEntity::getStatus, publicStatuses);
    if (currentUserId != null) {
        w.or(o -> o.eq(DemandEntity::getPublisherId, currentUserId));
    }
});
```

生成 SQL 片段：`((end_time IS NULL OR end_time >= ?) AND status IN (?, ?, ?) OR (publisher_id = ?))`（`currentUserId == null` 时无末尾 `OR (...)`）。因 `AND` 优先级高于 `OR`，等价 `((未过期 AND 公开状态) OR (自己的))`，与内存 `isPubliclyVisible(...) || (uid != null && publisher==uid)` 逐字对齐。

### 4.5 排序下沉映射

| `DemandSort` | 内存 `resolveComparator` | SQL `ORDER BY` | NULL 处理 |
|---|---|---|---|
| `REWARD` | `reward DESC nullsLast` then `created_at DESC nullsLast` | `ORDER BY reward DESC, created_at DESC` | MySQL/H2 中 `DESC` 天然 NULL 末尾 = `nullsLast` |
| `TIME` / `DISTANCE` / `RECOMMEND` | `created_at DESC nullsLast` | `ORDER BY created_at DESC` | 同上 |

```java
private void applySort(LambdaQueryWrapper<DemandEntity> wrapper, DemandSort sort) {
    DemandSort resolved = sort == null ? DemandSort.TIME : sort;
    switch (resolved) {
        case REWARD -> wrapper.orderByDesc(DemandEntity::getReward)
                              .orderByDesc(DemandEntity::getCreatedAt);
        case TIME, DISTANCE, RECOMMEND -> wrapper.orderByDesc(DemandEntity::getCreatedAt);
    }
}
```

> `DemandQuery` 紧凑构造器已保证 `sort == null → DemandSort.TIME`，`applySort` 内再兜底一次以容直接构造。

### 4.6 Service 改造

`DemandApplicationServiceImpl.list` 改为：

```java
@Override
public PageResponse<DemandSummaryResponse> list(DemandQuery query) {
    if (query == null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "demand query must not be null");
    }
    List<Demand> demands = demandRepository.findPage(query);
    List<DemandSummaryResponse> items = demands.stream()
        .map(DemandSummaryResponse::from)
        .toList();
    long total = demandRepository.count(query);
    int page = query.pageQuery().page();
    int size = query.pageQuery().size();
    return new PageResponse<>(items, page, size, total);
}
```

`now` 不再在 Service 取（下沉到 `buildWrapper`）。

### 4.7 私有方法与 import 清理

删除（仅旧 `list` 使用，已下沉）：`isPubliclyVisible`、`isExpired`、`matchesKeyword`、`matchesCategory`、`matchesCampusZone`、`matchesLocation`、`matchesStartTimeRange`、`resolveComparator`。

随之失效的 import（删除前需确认无其他方法引用）：
- `java.util.Comparator`（仅 `resolveComparator` 用）
- `com.campushub.backend.demand.domain.DemandSort`（仅 `resolveComparator` 用；`publish` 等不直接引用）
- `java.util.stream.Stream`（旧 `list` 用 `Stream<Demand>`；新 `list` 用 `demands.stream()` 链式，无需 `Stream` 类型显式引用，移除）

保留：
- `java.util.Locale`（`parseCampusZone` 仍用 `toUpperCase(Locale.ROOT)`）
- `java.util.UUID`（`generateAnonymousCode` 用）
- `DemandStatus` / `DemandCategory` / `CampusZone`（`publish` / `withdraw` / `parseCategory` / `parseCampusZone` 用）

> import 清理在 plan 的实现 task 中以编译通过为准绳逐项确认。

### 4.8 `now` 一致性说明

`findPage` 与 `count` 各自调 `buildWrapper` 各取一次 `now`，两次相差毫秒。对 `end_time >= now` 边界仅在某 demand 的 `end_time` 恰在两次查询之间跨过 `now` 时出现 `items` 与 `total` 差 1 的理论可能；`end_time` 精度到秒，且现有数据多为 null 或远期，实际无影响。2C 若需严格一致再统一注入时钟，2B.1 接受此妥协。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `demand/repository/DemandRepository.java` | 加 `findPage` + `count` 接口方法（`findAll`/`findByStatus` 保留） |
| `demand/repository/MyBatisDemandRepository.java` | 实现 `findPage` + `count` + 私有 `buildWrapper` + `applySort` |
| `demand/service/DemandApplicationServiceImpl.java` | `list` 改用 `findPage`+`count`；删 8 私有方法 + 失效 import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `demand/repository/MyBatisDemandRepositoryTest.java` | **新增** `findPage`/`count` 用例（覆盖过滤/排序/分页/可见性边界） |
| `demand/service/DemandApplicationServiceImplTest.java` | **不改**（现有 `shouldFilterDemandListByCategoryAndCampusZone`、`shouldIncludeOwnReviewingAndCancelledDemandsInList` 作为行为回归护栏，断言不变） |

### 不受影响
- `DemandQuery` / `DemandSort` / `Demand` / `DemandEntity` / `DemandSummaryResponse` / `DemandMapper`
- `DemandController` 及其 `reorderWithRecommendations`
- `RecommendationApplicationServiceImpl`（2B.6 处理）
- schema（2D 已补 `ord_demand` 5 索引：`publisher`/`status`/`category`/`campus_zone`/`created_at`，均被下推查询命中）
- 前端契约

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `DemandApplicationServiceImplTest.shouldFilterDemandListByCategoryAndCampusZone`：2 条 publish（REVIEWING）→ `makePending` → `list(STUDY_TUTORING, XIANLIN, TIME, page1 size20)` 断言 `total=1` 且首条 `category=STUDY_TUTORING`。下推后 `status IN(...PENDING...) AND category='STUDY_TUTORING' AND campus_zone='XIANLIN'` 等价。
- `DemandApplicationServiceImplTest.shouldIncludeOwnReviewingAndCancelledDemandsInList`：`publicPage`（`currentUserId=null`）排除 REVIEWING/CANCELLED；`ownerPage`（`currentUserId=publisherId`）含二者。下推后可见性 `OR (publisher_id=uid)` 等价。

### 6.2 新增 `MyBatisDemandRepositoryTest` 用例（@MybatisPlusTest + @Sql schema-demand.sql）
1. `findPage` 无过滤 + 默认 `TIME` 排序：插入 N 条 → 断言按 `created_at DESC` 返回，`LIMIT/OFFSET` 生效。
2. `findPage` 分页：插入 5 条，`PageQuery(1,2)` 返回 2 条、`PageQuery(3,2)` 返回 1 条。
3. `findPage` keyword `like`：title 含 / description 含 / 都不含 三档。
4. `findPage` category `eq`：含小写入参规范化（传 `"express"` 命中 `EXPRESS`）。
5. `findPage` campusZone `eq`：含小写规范化。
6. `findPage` location `like`。
7. `findPage` startTime range：`from`/`to`/`both`/`start_time IS NULL` 在 `from!=null` 时被排除。
8. `findPage` 可见性：`PENDING` 含 / `REVIEWING` 排除 / `end_time` 过期排除 / `currentUserId` 命中自己的 `REVIEWING`+`CANCELLED`。
9. `findPage` 排序：`REWARD`（`reward DESC, created_at DESC`，含 reward null 末尾）/ `RECOMMEND`（= `created_at DESC`）。
10. `count` 与 `findPage`（同条件、不限分页）总数一致。
11. `findPage(null)` / `count(null)` 返回空/0（防御）。

> 现有 `MyBatisDemandRepositoryTest` 的 `save`/`findById`/`findAll`/`findByStatus`/`tags` 用例保留不动（`findAll` 2B.6 才删）。

## 7. 验收标准
1. `.\mvnw.cmd test -q`（`JAVA_HOME` 指向 JDK 21）全绿，测试数 ≥ 基线 125（新增 `findPage`/`count` 用例后增加）。
2. `DemandApplicationServiceImpl.list` 不再出现 `demandRepository.findAll()`、`isPubliclyVisible`、`matchesKeyword/Category/CampusZone/Location`、`matchesStartTimeRange`、`resolveComparator`、`subList`。
3. `DemandRepository` 暴露 `findPage` + `count`；`MyBatisDemandRepository` 实现含 `buildWrapper` + `applySort`。
4. `PageResponse` / `DemandSummaryResponse` 字段不变；现有 2 个 `list` 用例断言不变且全绿。
5. `RECOMMEND` 分支行为不变（= `ORDER BY created_at DESC`）。
6. `DemandController` 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `findPage`/`count` 两次 `now` 毫秒差异致 `total` 与 `items` 边界差 1 | `end_time` 精度到秒且多为 null/远期，实际无影响；2C 若需严格再统一时钟（4.8） |
| `like` 大小写在 H2 MySQL 模式与生产 MySQL 不一致 | 2B 总体风险表已背书一致（均 ci collation）；`category`/`campusZone` 用 `toUpperCase` 精确匹配规避 collation 依赖；`like` 用例在 H2 验证 |
| `DESC` 下 NULL 排序位置与内存 `nullsLast` 不一致 | MySQL/H2 中 `DESC` 天然 NULL 末尾，等价 `nullsLast`；`REWARD` 用例显式覆盖 reward null 行 |
| `start_time IS NULL` 在 `from`/`to` 任一非 null 时被排除与内存不一致 | `isNotNull` + `ge`/`le` 组合显式覆盖（用例 6.2 #7） |
| 删除私有方法误伤其他调用方 | 实现前 grep 确认 8 方法仅 `list` 引用；import 清理以编译通过为准绳 |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`（`PageQuery` 已校验范围），无注入风险 |
