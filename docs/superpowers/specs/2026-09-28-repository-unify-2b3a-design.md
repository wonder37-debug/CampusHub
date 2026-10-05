# 仓储层统一阶段 2B.3a 设计（admin listUsers SQL 下推）

- 日期：2026-09-28
- 状态：待评审
- 分支：`refactor/optimization`（HEAD `563a0f4`，2B.1/2B.2 + CodeRabbit fix 已完成，测试 151/151）
- 上游 spec：`docs/superpowers/specs/2026-09-28-repository-unify-2b-design.md`（2B 总体，本文件为其第 3 子阶段 a）
- 范围：新建 `auth.dto.UserQueryCriteria` + `UserRepository` 扩 `findPage`/`count` + `MyBatisUserRepository` 实现 + `AdminApplicationServiceImpl.listUsers` 下沉 SQL

## 1. 背景与现状（探索已核实）

### 1.1 现状定位（2B 总体 spec #4）

`AdminApplicationServiceImpl.listUsers`（`backend/src/main/java/com/campushub/backend/admin/service/AdminApplicationServiceImpl.java:78-99`）当前：

```java
List<User> filtered = userRepository.findAll().stream()
    .filter(user -> matchesUserKeyword(user, query.q(), query.searchField()))
    .filter(user -> matchesUserRole(user, query.role()))
    .filter(user -> matchesUserStatus(user, query.status()))
    .sorted(resolveUserComparator(query.sortBy(), query.sortDirection()))
    .toList();

int page = query.pageQuery().page();
int size = query.pageQuery().size();
int fromIndex = Math.max(0, (page - 1) * size);
int toIndex = Math.min(filtered.size(), fromIndex + size);
List<UserProfileResponse> items = fromIndex >= filtered.size()
    ? List.of()
    : filtered.subList(fromIndex, toIndex).stream().map(UserProfileResponse::from).toList();
return new PageResponse<>(items, page, size, filtered.size());
```

即 `findAll()` 全表加载 → 3 道内存过滤（keyword+searchField / role / status）+ 内存排序（creditScore/nickname/createdAt + asc/desc + id tie-breaker）+ `subList` 内存分页 → `filtered.size()` 作 total。无任何 SQL 级 `orderBy`/`LIMIT`/`like`/`count`。

### 1.2 层次反转问题（关键，本子阶段独有）

2B.1（demand）/2B.2（notification）的查询 DTO（`DemandQuery`/`NotificationQuery`）与 Repository 同模块，Repository 直接接收 DTO 无层次问题。

`AdminUserQuery` 位于 `admin.dto`，而 `UserRepository` 位于 `auth.repository`。若 `UserRepository.findPage(AdminUserQuery)` 直接接收 admin 模块 DTO，则**底层 auth 依赖上层 admin**，违反分层（auth 不应感知 admin 的查询语义）。

故 2B.3a **新建 `auth.dto.UserQueryCriteria`**（字段与 `AdminUserQuery` 一致），`UserRepository.findPage`/`count` 接收 `UserQueryCriteria`，`AdminApplicationServiceImpl.listUsers` 内将 `AdminUserQuery` 字段映射为 `UserQueryCriteria`（admin → auth 是合法的上层→下层依赖方向）。

### 1.3 私有方法引用范围（grep 已核实）

- `matchesUserKeyword`（:351）/ `matchesUserRole`（:393）/ `matchesUserStatus`（:400）/ `resolveUserComparator`（:407）：4 方法仅 `listUsers`（:85-88）引用，下推后可删。
- `containsIgnoreCase`（:389）：被 `matchesUserKeyword`（:357-367）与 `matchesDemandKeyword`（:376-378）共用。`matchesDemandKeyword` 属 `listPendingDemands`（2B.3b 才删），故 **2B.3a 保留 `containsIgnoreCase`**，待 2B.3b 删 `matchesDemandKeyword` 后再评估。
- `Comparator` import：被 `resolveUserComparator`（:407-420）、`listPendingDemands`（:164 `Comparator.comparing(Demand::getCreatedAt...)`）、`listArbitrationOrders`（:185 `Comparator.comparing(Order::getUpdatedAt...)`）共用。2B.3a 删 `resolveUserComparator` 后 `Comparator` 仍被 :164/:185 引用，**保留 import**。

### 1.4 `userRepository.findAll()` 调用方（grep 已核实）

| 位置 | 用途 | 本子阶段处理 |
|---|---|---|
| `AdminApplicationServiceImpl:84` | `listUsers` | **2B.3a 下推**（findPage/count） |
| `AdminApplicationServiceImpl:300` | `getDashboard` 聚合 | 2B.3d 下推（保留 `count()`） |
| `AdminApplicationServiceImpl:466` | `collectRecommendationActivity`（getDashboard 子方法，N+1） | 2B.3d/2C 处理 |
| `AdminApplicationServiceImpl:476` | `listAllReviews`（getDashboard 子方法） | 2B.3d 处理 |
| `AuthApplicationServiceImpl:263` | 其他模块统计 | 不在 2B 范围 |

故 2B.3a 仅下推 :84，`UserRepository.findAll()` 接口与实现**保留**（2B.3d/2C/其他模块仍用）。

### 1.5 调用方契约

`listUsers` 调用方（grep 已核实）：
- `AdminController.listUsers`（`backend/src/main/java/com/campushub/backend/api/AdminController.java:64`）：拿 `PageResponse<UserProfileResponse> rawPage` → 转 `PageResponse<UserSummaryView>` 返回前端。
- `AdminApplicationServiceImplTest` 3 个 `listUsers` 用例（回归护栏）。

`AdminController` 的 `UserSummaryView` 转换与前端契约**不动**（2B.3a 仅改 Service 内部实现，`PageResponse<UserProfileResponse>` 结构不变）。

## 2. 目标 / 非目标

### 目标
1. 新建 `auth.dto.UserQueryCriteria` record（字段与 `AdminUserQuery` 对齐，紧凑构造器做相同规范化），解决层次反转。
2. `UserRepository` 新增 `findPage(UserQueryCriteria)` + `count(UserQueryCriteria)`（风格 A，与 2B.1/2B.2 一致），`findAll`/`findByStatus`/`findByRole` 等保留。
3. `MyBatisUserRepository` 实现：私有 `buildWrapper(criteria)`（keyword+searchField 分支 + role/status `eq`）+ `applyUserSort(wrapper, sortBy, sortDirection)`（主列 asc/desc + id ASC tie-breaker）；`findPage` 加 `orderBy` + `LIMIT/OFFSET`；`count` 用 `selectCount`。
4. `AdminApplicationServiceImpl.listUsers` 改用 `findPage` + `count`，`AdminUserQuery` → `UserQueryCriteria` 映射，删除 `findAll` + 3 道内存过滤 + `resolveUserComparator` + `subList` 分页。
5. 删除仅服务于旧 `listUsers` 的 4 个私有方法：`matchesUserKeyword` / `matchesUserRole` / `matchesUserStatus` / `resolveUserComparator`。
6. 行为零回归：`PageResponse` / `UserProfileResponse` 字段与语义不变；`AdminController` 与前端契约不变；现有 3 个 `listUsers` 用例断言不变且全绿。

### 非目标
- 不删 `UserRepository.findAll`/`findByStatus`/`findByRole`（1.4/1.3，2B.3d/2C/其他模块仍用）。
- 不删 `containsIgnoreCase`（`matchesDemandKeyword` 仍用，2B.3b 处理）。
- 不删 `Comparator` import（`listPendingDemands`/`listArbitrationOrders` 仍用，2B.3b/2B.3c 处理）。
- 不动 `AdminUserQuery` / `User` / `UserEntity` / `UserProfileResponse` / `UserMapper` / `AdminController` / schema。
- 不动 `listPendingDemands`（:154）/ `listArbitrationOrders`（:178）/ `getDashboard`（:297）（2B.3b/2B.3c/2B.3d）。
- 不动 `AdminApplicationServiceImpl` 双构造函数与 `@Autowired(required=false)`（子项目4）。
- 不修 N+1（2C）。

## 3. 约束（继承 2B 总体 + 主 spec）
- 前端兼容契约：`PageResponse(items, page, size, total)` / `UserProfileResponse` 字段结构不变；`AdminController` 转换与 `UserSummaryView` 不变；API 行为不变。
- 测试全绿：基线 151/151，每子阶段不回归。
- 工作分支 `refactor/optimization`。
- Repository 接口扩方法，`MyBatisUserRepository` 实现；无 XML mapper，全 `BaseMapper` + `LambdaQueryWrapper`。
- H2 MySQL 兼容模式（测试）与 MySQL（生产）行为一致；`LIKE` 大小写依赖默认 ci collation。

## 4. 设计

### 4.1 新建 `UserQueryCriteria`（层次反转修复）

`backend/src/main/java/com/campushub/backend/auth/dto/UserQueryCriteria.java`：

```java
package com.campushub.backend.auth.dto;

import com.campushub.backend.common.model.PageQuery;

public record UserQueryCriteria(
    String q,
    String searchField,
    String role,
    String status,
    String sortBy,
    String sortDirection,
    PageQuery pageQuery
) {

    public UserQueryCriteria {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
        searchField = searchField == null ? null : searchField.trim();
        role = role == null ? null : role.trim();
        status = status == null ? null : status.trim();
        sortBy = sortBy == null ? null : sortBy.trim();
        sortDirection = sortDirection == null ? null : sortDirection.trim();
    }
}
```

字段集合与紧凑构造器与 `AdminUserQuery` 逐字对齐（`AdminUserQuery` 已规范化，传入再 trim 幂等无害），保证 Service 层 `AdminUserQuery → UserQueryCriteria` 映射零语义损耗。

> 命名取 `Criteria`（查询条件）而非 `Query`，区别于 `AdminUserQuery`（admin 模块的入参 record）与 `DemandQuery`/`NotificationQuery`（同模块 DTO 直接复用），表达"auth 仓储层的查询条件"语义。

### 4.2 Repository 接口扩展（风格 A，与 2B.1/2B.2 一致）

`UserRepository.java` 新增两方法（`findAll`/`findById`/`findByStudentId`/`findByEmail`/`findByLoginId`/`findByStatus`/`findByRole`/`save` 保留）：

```java
/**
 * 按查询条件分页查询用户（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
 */
List<User> findPage(UserQueryCriteria criteria);

/**
 * 按查询条件统计匹配的用户总数（过滤下推 SQL，用于分页 total）。
 */
long count(UserQueryCriteria criteria);
```

### 4.3 MyBatisUserRepository 实现

新增私有 `buildWrapper(criteria)`（纯过滤，供 `findPage`/`count` 共用）+ `applyUserSort(wrapper, sortBy, sortDirection)`（仅 `findPage` 调）。

```java
@Override
public List<User> findPage(UserQueryCriteria criteria) {
    if (criteria == null) {
        return List.of();
    }
    LambdaQueryWrapper<UserEntity> wrapper = buildWrapper(criteria);
    applyUserSort(wrapper, criteria.sortBy(), criteria.sortDirection());
    int size = criteria.pageQuery().size();
    long offset = (long) (criteria.pageQuery().page() - 1) * size;
    wrapper.last("LIMIT " + size + " OFFSET " + offset);
    return userMapper.selectList(wrapper).stream().map(UserEntity::toDomain).toList();
}

@Override
public long count(UserQueryCriteria criteria) {
    if (criteria == null) {
        return 0L;
    }
    return userMapper.selectCount(buildWrapper(criteria));
}
```

> `offset` 用 `long`（与 2B.1/2B.2 一致，CodeRabbit fix 后统一）；`PageQuery` 构造已校验 `page>=1`、`1<=size<=100`、`page<=1000`，`offset` 必非负且不溢出 `int`（`MAX_PAGE * MAX_SIZE = 100000`）。`last("LIMIT ... OFFSET ...")` 入参为 `int`/`long` 字面量拼接，无注入风险。

### 4.4 过滤下沉映射（内存逻辑 → SQL）

| # | 内存逻辑（现状） | SQL 下推（LambdaQueryWrapper） | 边界对齐说明 |
|---|---|---|---|
| 1 | `matchesUserKeyword`：`searchField` null/blank → nickname OR email OR studentId（`containsIgnoreCase`）；`"nickname"`/`"email"`/`"studentid"`/`"student_id"` → 单字段；default → 三字段 OR | `searchField` null/blank/default → `and(w -> w.like(nickname, q).or().like(email, q).or().like(student_id, q))`；`"nickname"` → `like(nickname, q)`；`"email"` → `like(email, q)`；`"studentid"`/`"student_id"` → `like(student_id, q)` | `q` 为 null/blank 跳过；`q = q.trim()`（**不 toLowerCase**，依赖 ci collation，与 2B.1/2B.2 keyword like 一致）；`searchField` 用 `toLowerCase(Locale.ROOT)` 归一化分支匹配（与内存 `searchField.trim().toLowerCase()` 一致）；`nickname`/`email`/`student_id` 为 NULL 的行 `NULL LIKE` → false，与内存 `value != null && value.contains` 一致 |
| 2 | `matchesUserRole`：`user.getRole().name().equalsIgnoreCase(role.trim())` | `role = role.trim().toUpperCase(Locale.ROOT)` | `UserRole` enum name 全大写存库（`USER`/`ADMIN`），`toUpperCase` 后精确匹配，不依赖 collation；`role` null/blank 跳过 |
| 3 | `matchesUserStatus`：`user.getStatus().name().equalsIgnoreCase(status.trim())` | `status = status.trim().toUpperCase(Locale.ROOT)` | `UserStatus` enum name 全大写存库（`ACTIVE`/`BANNED`），同 #2 |

`buildWrapper` 实现：

```java
private LambdaQueryWrapper<UserEntity> buildWrapper(UserQueryCriteria criteria) {
    LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
    String q = criteria.q();
    if (q != null && !q.isBlank()) {
        String keyword = q.trim();
        String field = criteria.searchField();
        if (field == null || field.isBlank()) {
            wrapper.and(w -> w.like(UserEntity::getNickname, keyword)
                .or().like(UserEntity::getEmail, keyword)
                .or().like(UserEntity::getStudentId, keyword));
        } else {
            switch (field.toLowerCase(Locale.ROOT)) {
                case "nickname" -> wrapper.like(UserEntity::getNickname, keyword);
                case "email" -> wrapper.like(UserEntity::getEmail, keyword);
                case "studentid", "student_id" -> wrapper.like(UserEntity::getStudentId, keyword);
                default -> wrapper.and(w -> w.like(UserEntity::getNickname, keyword)
                    .or().like(UserEntity::getEmail, keyword)
                    .or().like(UserEntity::getStudentId, keyword));
            }
        }
    }
    String role = criteria.role();
    if (role != null && !role.isBlank()) {
        wrapper.eq(UserEntity::getRole, role.trim().toUpperCase(Locale.ROOT));
    }
    String status = criteria.status();
    if (status != null && !status.isBlank()) {
        wrapper.eq(UserEntity::getStatus, status.trim().toUpperCase(Locale.ROOT));
    }
    return wrapper;
}
```

### 4.5 排序下沉映射（关键，与 demand 的差异）

#### 4.5.1 内存 `resolveUserComparator` 行为还原

```java
boolean descending = sortDirection == null || sortDirection.isBlank()
    || !"asc".equalsIgnoreCase(sortDirection.trim());  // 默认 desc
Comparator<User> comparator = switch (sortBy.toLowerCase()) {
    case "creditscore", "credit_score" -> comparing(User::getCreditScore, nullsLast);
    case "nickname" -> comparing(User::getNickname, nullsLast(compareToIgnoreCase));
    case "createdat", "created_at" -> comparing(User::getCreatedAt, nullsLast);
    default -> comparing(User::getCreatedAt, nullsLast);
};
comparator = descending ? comparator.reversed() : comparator;  // 先反转主列
return comparator.thenComparing(User::getId, nullsLast);  // 后加 id 升序 tie-breaker
```

**关键**：`reversed()` 在 `thenComparing(id)` **之前**调用，故 `reversed()` 只反转主列，**id tie-breaker 永远升序**（无论主列 asc/desc）。这与 2B.1 demand 的 `resolveComparator` 不同——demand 的 `resolveComparator` 对整个 comparator（含 id）一起 `reversed()`，故 demand id 也是降序；user id 是升序。

#### 4.5.2 SQL 下推

| `sortBy` | `direction` | 内存主列 | SQL `ORDER BY` | id tie-breaker |
|---|---|---|---|---|
| `creditscore`/`credit_score` | desc | `creditScore DESC nullsLast` | `ORDER BY credit_score DESC` | `, id ASC` |
| `creditscore`/`credit_score` | asc | `creditScore ASC nullsLast` | `ORDER BY credit_score ASC` | `, id ASC` |
| `nickname` | desc | `nickname DESC nullsLast` | `ORDER BY nickname DESC` | `, id ASC` |
| `nickname` | asc | `nickname ASC nullsLast` | `ORDER BY nickname ASC` | `, id ASC` |
| `createdat`/`created_at`/default | desc | `createdAt DESC nullsLast` | `ORDER BY created_at DESC` | `, id ASC` |
| `createdat`/`created_at`/default | asc | `createdAt ASC nullsLast` | `ORDER BY created_at ASC` | `, id ASC` |

> id tie-breaker 用 `orderByAsc(id)`（**升序**，与内存 `thenComparing(id)` 在 `reversed()` 之后语义一致）。

#### 4.5.3 NULL 排序边界（schema 已核实）

| 列 | schema 约束 | NULL 风险 | 对策 |
|---|---|---|---|
| `credit_score` | `INT NOT NULL DEFAULT 100`（`init_schema.sql:41`/`schema.sql:13`） | 无 | 无需处理 |
| `created_at` | `datetime NOT NULL DEFAULT CURRENT_TIMESTAMP`（`init_schema.sql:47`/`schema.sql:16`） | 无 | 无需处理 |
| `nickname` | `varchar(64) DEFAULT '匿名校友'`（`init_schema.sql:33`/`schema.sql:9`） | 有 DEFAULT 但可显式 INSERT NULL | 见下 |

`nickname` NULL 边界：
- MySQL/H2 中 `ORDER BY nickname DESC` → NULL 末尾（NULL 视为最小，DESC 从大到小）= 内存 `nullsLast` ✓
- MySQL/H2 中 `ORDER BY nickname ASC` → NULL 最前（ASC 从小到大，NULL 最小）≠ 内存 `nullsLast` ✗

**对策**（YAGNI，与 2B.1 reward 处理一致风格）：2B.3a **不引入 `ISNULL()` 复杂化**。理由：
1. `nickname` 有 `DEFAULT '匿名校友'`，注册流程（`RegisterCommand`）与测试数据均显式 setNickname，实际无 NULL 行。
2. 2B.1 的 `reward`（BigDecimal 可 NULL，无 DEFAULT）也仅用 `ORDER BY reward DESC` 不加 `ISNULL`，因 demand 排序只有 desc 分支（DESC 天然 nullsLast）。
3. user `nickname` 有 asc 分支是真差异，但实际无 NULL 行触发。

spec 记录此边界，若生产出现 `nickname NULL` 行导致 asc 排序异常，2C 或后续补 `ORDER BY nickname IS NULL, nickname ASC`。新增仓储测试用例不显式构造 nickname NULL 行（与现有 `MyBatisUserRepositoryTest` 风格一致）。

#### 4.5.4 `nickname` 排序大小写

内存用 `Comparator.nullsLast(String::compareToIgnoreCase)`（大小写不敏感）。SQL `ORDER BY nickname` 在 ci collation 下大小写不敏感，与 `compareToIgnoreCase` 一致（与 2B.1 keyword like 同依赖）。

#### 4.5.5 `applyUserSort` 实现

```java
private void applyUserSort(LambdaQueryWrapper<UserEntity> wrapper, String sortBy, String sortDirection) {
    boolean descending = sortDirection == null || sortDirection.isBlank()
        || !"asc".equalsIgnoreCase(sortDirection.trim());
    String resolvedSortBy = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
    switch (resolvedSortBy) {
        case "creditscore", "credit_score" -> {
            if (descending) wrapper.orderByDesc(UserEntity::getCreditScore);
            else wrapper.orderByAsc(UserEntity::getCreditScore);
        }
        case "nickname" -> {
            if (descending) wrapper.orderByDesc(UserEntity::getNickname);
            else wrapper.orderByAsc(UserEntity::getNickname);
        }
        case "createdat", "created_at" -> {
            if (descending) wrapper.orderByDesc(UserEntity::getCreatedAt);
            else wrapper.orderByAsc(UserEntity::getCreatedAt);
        }
        default -> {
            if (descending) wrapper.orderByDesc(UserEntity::getCreatedAt);
            else wrapper.orderByAsc(UserEntity::getCreatedAt);
        }
    }
    wrapper.orderByAsc(UserEntity::getId);
}
```

### 4.6 Service 改造

`AdminApplicationServiceImpl.listUsers` 改为：

```java
@Override
public PageResponse<UserProfileResponse> listUsers(Long operatorId, AdminUserQuery query) {
    requireAdmin(operatorId);
    if (query == null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "admin user query must not be null");
    }

    UserQueryCriteria criteria = new UserQueryCriteria(
        query.q(), query.searchField(), query.role(), query.status(),
        query.sortBy(), query.sortDirection(), query.pageQuery());
    List<User> users = userRepository.findPage(criteria);
    List<UserProfileResponse> items = users.stream().map(UserProfileResponse::from).toList();
    long total = userRepository.count(criteria);
    int page = query.pageQuery().page();
    int size = query.pageQuery().size();
    return new PageResponse<>(items, page, size, total);
}
```

`AdminUserQuery → UserQueryCriteria` 直接字段映射（两个 record 字段逐一对齐，紧凑构造器做相同规范化）。`requireAdmin` / null 校验顺序不变（先权限后入参，与现状一致）。

### 4.7 私有方法与 import 清理

删除（仅旧 `listUsers` 使用，已下沉，grep 确认无其他引用）：
- `matchesUserKeyword`（:351-369）
- `matchesUserRole`（:393-398）
- `matchesUserStatus`（:400-405）
- `resolveUserComparator`（:407-421）

随之失效的 import（删除前需确认无其他方法引用）：
- **无**。`Comparator` 仍被 `listPendingDemands`（:164）/ `listArbitrationOrders`（:185）用，**保留**；`Locale` 仍被 `updateUserRole`（:137 `toUpperCase(Locale.ROOT)`）/ `reviewDemand`（:210 `toLowerCase(Locale.ROOT)`）/ `resolveOrderArbitration`（:262 `toLowerCase(Locale.ROOT)`）用，**保留**；`User` 仍被 `findUser`/`requireAdmin`/`banUser`/`transferReward` 等用，**保留**。

新增 import：
- `com.campushub.backend.auth.dto.UserQueryCriteria`

> import 清理在 plan 的实现 task 中以编译通过为准绳逐项确认（与 2B.1/2B.2 一致）。

### 4.8 `now` 一致性

user 查询无 `now`/时间边界依赖（不像 demand 的 `end_time >= now`），`findPage`/`count` 无 `now` 取值，无 2B.1 的毫秒窗口问题。

## 5. 影响面

### 改动文件
| 文件 | 操作 |
|---|---|
| `auth/dto/UserQueryCriteria.java` | **新建** record（字段同 `AdminUserQuery`，紧凑构造器同） |
| `auth/repository/UserRepository.java` | 加 `findPage(UserQueryCriteria)` + `count(UserQueryCriteria)`（其余方法保留） |
| `auth/repository/MyBatisUserRepository.java` | 实现 `findPage` + `count` + 私有 `buildWrapper` + `applyUserSort` |
| `admin/service/AdminApplicationServiceImpl.java` | `listUsers` 改用 `findPage`+`count` + `AdminUserQuery→UserQueryCriteria` 映射；删 4 私有方法；加 `UserQueryCriteria` import |

### 测试文件
| 文件 | 操作 |
|---|---|
| `auth/repository/MyBatisUserRepositoryTest.java` | **新增** `findPage`/`count` 用例（覆盖 keyword+searchField 分支 / role / status / sortBy+direction / 分页 / null 防御） |
| `admin/service/AdminApplicationServiceImplTest.java` | **不改**（3 个 `listUsers` 用例作回归护栏，断言不变） |

### 不受影响
- `AdminUserQuery` / `User` / `UserEntity` / `UserProfileResponse` / `UserMapper` / schema
- `AdminController` 及 `UserSummaryView` 转换、前端契约
- `listPendingDemands` / `listArbitrationOrders` / `getDashboard`（2B.3b/2B.3c/2B.3d）
- `containsIgnoreCase` / `matchesDemandKeyword` 等（2B.3b）
- `AuthApplicationServiceImpl`（不在 2B 范围）
- 索引（sys_user 已有 `uk_user_email`/`uk_user_student_id`；`role`/`status` 列选择性低不下推索引；`created_at`/`credit_score` 排序可后续 2D 评估，2B.3a 不动 schema）

## 6. 测试策略

### 6.1 回归护栏（不改，须全绿）
- `shouldRejectNonAdminOperation`：`listUsers(publisherId, ...)` 抛 `PERMISSION_DENIED`。`requireAdmin` 在 `findPage` 前调用，下推不影响权限路径。
- `shouldListUsersByKeyword`：插入 1 user（studentId=20260003）→ `listUsers(adminId, q="20260003", searchField="studentId", page1 size20)` 断言 `total=1`、`items[0].studentId="20260003"`。下推后 `student_id LIKE '%20260003%'` 等价（`searchField="studentId"` → `toLowerCase="studentid"` → 单字段 `like(student_id, "20260003")`）。
- `shouldFilterAndSortUsersByRoleStatusAndCreditScore`：插入 2 user（creditScore 60/95）+ setUp 3 user（admin/publisher/accepter）→ `listUsers(adminId, null, null, "USER", "ACTIVE", "creditScore", "desc", page1 size20)` 断言 `items.size()>=2`、`items[0].creditScore=100`、全 `role=USER`、全 `status=ACTIVE`。下推后 `role='USER' AND status='ACTIVE' ORDER BY credit_score DESC, id ASC LIMIT 20 OFFSET 0` 等价（admin role=ADMIN 被过滤；4 条 USER+ACTIVE 中 creditScore 100/100/95/60，id ASC tie-breaker 保证 publisher 在 accepter 前）。

### 6.2 新增 `MyBatisUserRepositoryTest` 用例（@MybatisPlusTest + @Sql schema.sql）

> 现有 `MyBatisUserRepositoryTest` 用 `@MybatisPlusTest` + `@ActiveProfiles("local")`（注释提及 `@Profile("local")` 已在 2A 移除，注释属遗留说明，不影响功能）+ `@Import(MyBatisUserRepository.class)`，走 H2 + `classpath:schema.sql`。新增用例沿用同一切片配置，沿用 `newUser(...)` 辅助工厂（需补 `setCreatedAt`/`setUpdatedAt` 以验证排序）。

1. `findPage` 无过滤 + 默认 createdAt desc：插入 3 条（不同 `createdAt`）→ 断言按 `created_at DESC` 返回，`id ASC` tie-breaker（同 createdAt 时 id 升序）。
2. `findPage` keyword（searchField=null）三字段 OR：分别命中 nickname / email / studentId 三档 + 都不命中空集。
3. `findPage` searchField="nickname" 单字段：命中 nickname 含，不命中 email/studentId 含。
4. `findPage` searchField="email" 单字段。
5. `findPage` searchField="studentId" 单字段（验证 `"studentId"` → `"studentid"` 分支匹配 `student_id` 列）。
6. `findPage` searchField="student_id" 单字段（验证下划线变体）。
7. `findPage` searchField 未知值（如 "foo"）→ 退化为三字段 OR（与 default 一致）。
8. `findPage` role eq（传 `"user"` 小写命中 `USER`）+ status eq（传 `"active"` 小写命中 `ACTIVE`）。
9. `findPage` sortBy=creditScore desc：插入不同 creditScore → 断言 `credit_score DESC`，同分 id ASC。
10. `findPage` sortBy=creditScore asc：断言 `credit_score ASC` + id ASC。
11. `findPage` sortBy=nickname desc / asc（验证 asc/desc 双向）。
12. `findPage` sortBy 未知值 → 退化 createdAt desc（default 分支）。
13. `findPage` sortDirection=null/blank → 默认 desc。
14. `findPage` 分页 `LIMIT/OFFSET`：插入 5 条，`PageQuery(1,2)`/`(2,2)`/`(3,2)` 返回 2/2/1。
15. `count` 与 `findPage`（同条件、不限分页）总数一致（覆盖 role/status/keyword 各档）。
16. `findPage(null)` / `count(null)` 返回空/0（防御）。

> 现有 `MyBatisUserRepositoryTest` 的 `save`/`findById`/`findByStudentId`/`findByEmail`/`findByLoginId`/`findAll`/`findByStatus`/`findByRole`/`duplicate` 用例保留不动。

## 7. 验收标准
1. `.\mvnw.cmd test`（`JAVA_HOME` 指向 JDK 21，workdir=backend）全绿，测试数 ≥ 151 + 新增（约 16 个 findPage/count 用例）。
2. `AdminApplicationServiceImpl.listUsers` 不再出现 `userRepository.findAll()`、`matchesUserKeyword`、`matchesUserRole`、`matchesUserStatus`、`resolveUserComparator`、`subList`。
3. `UserRepository` 暴露 `findPage(UserQueryCriteria)` + `count(UserQueryCriteria)`；`MyBatisUserRepository` 实现含 `buildWrapper` + `applyUserSort`。
4. `auth.dto.UserQueryCriteria` record 存在，字段与 `AdminUserQuery` 对齐。
5. `PageResponse` / `UserProfileResponse` / `UserSummaryView` 字段不变；`AdminController` 转换不变；现有 3 个 `listUsers` 用例断言不变且全绿。
6. `listPendingDemands` / `listArbitrationOrders` / `getDashboard` 行为不变（未触动）。
7. `containsIgnoreCase` / `matchesDemandKeyword` 等 2B.3b 目标方法保留（未误删）。
8. Controller 及前端契约未触动。

## 8. 风险与对策
| 风险 | 对策 |
|---|---|
| `nickname` NULL 行在 asc 排序时位置与内存 `nullsLast` 不一致 | `nickname` 有 `DEFAULT '匿名校友'`，实际无 NULL 行（4.5.3）；测试不构造 NULL 行；若生产出现，2C 补 `ISNULL()` |
| `like` 大小写在 H2 MySQL 模式与生产 MySQL 不一致 | 2B 总体风险表已背书一致（均 ci collation）；`role`/`status` 用 `toUpperCase` 精确匹配规避 collation 依赖；`like` 用例在 H2 验证 |
| `nickname` 排序大小写与内存 `compareToIgnoreCase` 不一致 | ci collation 下 `ORDER BY nickname` 大小写不敏感，与 `compareToIgnoreCase` 一致（4.5.4） |
| id tie-breaker 方向（ASC vs DESC）与 demand 不一致致混淆 | spec 4.5.1 已还原内存 `reversed()` 在 `thenComparing(id)` 之前的事实，user id ASC 是内存行为逐字对齐（demand id DESC 是 demand 内存不同所致，非矛盾） |
| `AdminUserQuery → UserQueryCriteria` 映射遗漏字段 | 两 record 字段逐一对齐 + 紧凑构造器同规范化，Service 映射零损耗（4.6）；回归用例 `shouldListUsersByKeyword`/`shouldFilterAndSortUsersByRoleStatusAndCreditScore` 覆盖 keyword/role/status/sortBy/sortDirection 全字段 |
| 删除 4 私有方法误伤其他调用方 | grep 已确认 4 方法仅 `listUsers` 引用（1.3）；`containsIgnoreCase`/`Comparator` 保留（2B.3b/2B.3c 才处理） |
| `last("LIMIT ... OFFSET ...")` 拼接 | 入参 `int`/`long`（`PageQuery` 已校验范围），无注入风险 |
