# 仓储层统一阶段 2A 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除全部 6 个 `InMemory*Repository`、移除 6 个 `MyBatis*Repository` 的 `@Profile("local")`，让 MyBatis 成为所有 profile 下唯一默认仓储 bean，7 个 Service 单测改 `@SpringBootTest`(H2) 适配，全量测试全绿。

**Architecture:** 三步递进——先就绪测试 schema 配置（Task 1，仍 InMemory 模式），再把 7 个 Service 单测改为 `@SpringBootTest` 注入（Task 2，仍 InMemory bean，验证 @SpringBootTest 模式与测试逻辑），最后原子删 InMemory + 移除 @Profile（Task 3，Service 测试自动切到 MyBatis bean + H2）。每步独立可测全绿，Task 3 的原子切换风险被前两步隔离到最小。

**Tech Stack:** Spring Boot 3.5.0 / Java 21 / MyBatis-Plus 3.5.7 / H2(MySQL 兼容模式) / JUnit 5 + Spring Test。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2a-design.md`

## Global Constraints
- Java 21，Spring Boot 3.5.0，MyBatis-Plus 3.5.7
- 前端兼容契约：`ApiResponse`/`ErrorResponse` 字段结构不变；API 行为不变（本阶段不动 Controller/Service 业务逻辑）
- 删除全部 `InMemory*Repository`，测试统一用 H2 + 真实 MyBatis Mapper
- 现有测试全绿 + 不回归（基线 125/125，子项目1 后）
- 工作分支 `refactor/optimization`（HEAD `7f15c00`）
- 本机 `JAVA_HOME` 需指向 JDK 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`），CI 用 temurin-21 不受影响
- bash 工具为 PowerShell 7+，禁止管道 `|`、分号 `;`、`&&`、`&`、重定向 `>`、反引号、`$()`，用单条命令；Maven 用 `.\mvnw.cmd` + workdir=backend
- 构建验证：`cd backend && ./mvnw clean package`；测试：`cd backend && ./mvnw test`

---

## File Structure

| 文件 | 操作 | 责任 |
|---|---|---|
| `backend/src/test/resources/application.properties` | 修改 | schema-locations 加全 schema + demo-data=false |
| `backend/src/test/java/com/campushub/backend/api/FrontendIntegrationFlowTest.java` | 修改 | properties schema-locations 改全 schema |
| 7 个 `*ApplicationServiceImplTest.java` | 修改 | 改 `@SpringBootTest` + `@Autowired setUp` |
| 6 个 `MyBatis*Repository.java` | 修改 | 移除 `@Profile("local")` + import + Javadoc |
| 6 个 `InMemory*Repository.java` | 删除 | 被 MyBatis 取代 |

---

## Task 1: 测试 schema 配置就绪（仍 InMemory 模式）

**Files:**
- Modify: `backend/src/test/resources/application.properties`
- Modify: `backend/src/test/java/com/campushub/backend/api/FrontendIntegrationFlowTest.java`

**Interfaces:**
- Consumes: 无
- Produces: test `application.properties` + `FrontendIntegrationFlowTest` 加载全部 `schema-*.sql`，为 Task 3 的 MyBatis + H2 提供表结构

- [ ] **Step 1: 修改 `backend/src/test/resources/application.properties`，把 `spring.sql.init.schema-locations` 改为全部 schema，并加 `app.demo-data.enabled=false`**

把
```properties
spring.sql.init.schema-locations=classpath:schema.sql
```
改为
```properties
spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql
app.demo-data.enabled=false
```
（其余行不动，包括 `app.security.jwt.secret`）

- [ ] **Step 2: 修改 `FrontendIntegrationFlowTest.java` 的 `properties`，把 schema-locations 改为全部 schema**

把
```java
"spring.sql.init.schema-locations=classpath:schema.sql",
```
改为
```java
"spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql",
```
（其余 properties 不动）

- [ ] **Step 3: 跑全量测试验证不回归（仍 InMemory 模式，全 schema 加载对 InMemory 无害）**

Run（bash workdir="D:\workspace\sec-ii-2026\backend"，先 `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"` 再 `.\mvnw.cmd test -q`，两条独立单命令）
Expected: BUILD SUCCESS，125/125 全绿（InMemory 不依赖表，加全 schema 无影响）。

- [ ] **Step 4: 提交（两条独立单命令，workdir 仓库根，不用 && 或 ;）**
- `git add backend/src/test/resources/application.properties backend/src/test/java/com/campushub/backend/api/FrontendIntegrationFlowTest.java`
- `git commit -m "test(repository): 测试加载全部 schema-*.sql + 关闭演示数据"`

---

## Task 2: 7 个 Service 单测改 @SpringBootTest（仍 InMemory bean）

**Files:**
- Modify: 7 个 `backend/src/test/java/com/campushub/backend/{module}/service/*ApplicationServiceImplTest.java`
  - `auth/service/AuthApplicationServiceImplTest.java`
  - `demand/service/DemandApplicationServiceImplTest.java`
  - `order/service/OrderApplicationServiceImplTest.java`
  - `review/service/ReviewApplicationServiceImplTest.java`
  - `notification/service/NotificationApplicationServiceImplTest.java`
  - `recommendation/service/RecommendationApplicationServiceImplTest.java`
  - `admin/service/AdminApplicationServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 的全 schema 配置
- Produces: 7 个 Service 单测改为 `@SpringBootTest` 注入模式（仍用 InMemory bean，因 InMemory 未删），验证模式与测试逻辑

**通用改造模式（以 `DemandApplicationServiceImplTest` 为模板，其余 6 个同模式套用）：**

类注解（加在 `class XxxApplicationServiceImplTest` 上方）：
```java
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
```
需 import：
```java
import com.campushub.backend.BackendApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
```

字段：把原来手工 `new` 的字段改为 `@Autowired`：
```java
@Autowired private UserRepository userRepository;
@Autowired private DemandRepository demandRepository;
@Autowired private DemandApplicationService demandApplicationService;
```
（具体每个测试注入哪些 Repository/Service，见下方清单）

`setUp`：删除 `new InMemory*Repository(...)` 与 `new XxxApplicationServiceImpl(...)` 手工构造，改为 Spring 注入后的 `@BeforeEach` 用 Repository `save` 准备数据（保留原有 save 逻辑，仅去掉手工 new）。

**测试方法体（断言业务行为）保持不变。**

**各测试注入清单（需注入的 Repository + 被测 Service）：**
| 测试类 | 注入 Repository | 被测 Service |
|---|---|---|
| AuthApplicationServiceImplTest | UserRepository | AuthApplicationService |
| DemandApplicationServiceImplTest | UserRepository, DemandRepository | DemandApplicationService |
| OrderApplicationServiceImplTest | UserRepository, DemandRepository, OrderRepository, NotificationRepository | OrderApplicationService |
| ReviewApplicationServiceImplTest | UserRepository, DemandRepository, OrderRepository, ReviewRepository, NotificationRepository | ReviewApplicationService |
| NotificationApplicationServiceImplTest | NotificationRepository | NotificationApplicationService |
| RecommendationApplicationServiceImplTest | UserRepository, DemandRepository, OrderRepository, NotificationRepository | RecommendationApplicationService |
| AdminApplicationServiceImplTest | UserRepository, DemandRepository, OrderRepository, NotificationRepository | AdminApplicationService |

> implementer 须先读每个测试现有 `setUp`，确认其准备的数据与构造的依赖，再按上表 `@Autowired` 注入；若某测试还 `new` 了其他协作对象（如 `SensitiveWordChecker`、`VerificationCodeService`），这些是值对象/无 Spring 依赖，可保留手工 `new`（不注入）。

- [ ] **Step 1: 逐个改造 7 个 Service 测试**（按上表 + 模板），用 Edit/Write。先读每个测试现有结构，再改注解 + 字段 + setUp。

- [ ] **Step 2: 跑全量测试验证（@SpringBootTest 模式 + InMemory bean）**

Run（bash workdir=backend，JAVA_HOME 设 JDK 21，`.\mvnw.cmd test -q`）
Expected: BUILD SUCCESS，全绿。若某 Service 测试因 `@SpringBootTest` 全 context 下可选依赖（原 InMemory 测试为 null）注入真实 bean 导致行为变化失败：
- 先读失败用例的断言与 Service 路径
- 若是可选依赖触发了额外副作用（如 `publish` 触发 `autoCompleteOverdueOrders`），在测试 properties 加 `"app.demo-data.enabled=false"` 已设，或在该测试用 `@MockBean` 指定协作 Service 为空实现，或调整 setUp 数据避免触发
- 不改 Service 业务逻辑，只调测试 setUp/properties
- 把每处调整与原因记入报告

- [ ] **Step 3: 提交**
- `git add backend/src/test/java`（7 个测试文件）
- `git commit -m "test(service): 7 个 Service 单测改 @SpringBootTest 注入模式"`

---

## Task 3: 原子删 6 InMemory + 移除 6 @Profile（切 MyBatis 默认）

**Files:**
- Delete: 6 个 `backend/src/main/java/com/campushub/backend/{module}/repository/InMemory*Repository.java`
- Modify: 6 个 `backend/src/main/java/com/campushub/backend/{module}/repository/MyBatis*Repository.java`

**Interfaces:**
- Consumes: Task 1 全 schema + Task 2 Service 测试 @SpringBootTest
- Produces: MyBatis 成所有 profile 唯一 Repository bean；Service 测试 @SpringBootTest 自动切到 MyBatis + H2

**原子性**：删 InMemory 与移除 @Profile 必须同步——删 InMemory 后若不移除 @Profile，默认 profile 无 bean；移除 @Profile 后若不删 InMemory，默认 profile 两个 bean 冲突。中间态不可跑集成测试。

- [ ] **Step 1: 移除 6 个 `MyBatis*Repository.java` 的 `@Profile("local")`**

对每个文件：
- 删除 `import org.springframework.context.annotation.Profile;`
- 删除 `@Profile("local")` 注解行
- 保留 `@Repository`
- 类 Javadoc 中"仅在 local profile 下激活"措辞删除/改写为"默认仓储实现"

文件：`MyBatisUserRepository`、`MyBatisDemandRepository`、`MyBatisOrderRepository`、`MyBatisReviewRepository`、`MyBatisNotificationRepository`、`MyBatisUserActionLogRepository`

- [ ] **Step 2: 删除 6 个 `InMemory*Repository.java`**

用 `git rm`（单条命令，workdir 仓库根）：
- `git rm backend/src/main/java/com/campushub/backend/auth/repository/InMemoryUserRepository.java`
- `git rm backend/src/main/java/com/campushub/backend/demand/repository/InMemoryDemandRepository.java`
- `git rm backend/src/main/java/com/campushub/backend/order/repository/InMemoryOrderRepository.java`
- `git rm backend/src/main/java/com/campushub/backend/review/repository/InMemoryReviewRepository.java`
- `git rm backend/src/main/java/com/campushub/backend/notification/repository/InMemoryNotificationRepository.java`
- `git rm backend/src/main/java/com/campushub/backend/recommendation/repository/InMemoryUserActionLogRepository.java`

- [ ] **Step 3: 清理 7 个 Service 测试中残留的 `InMemory*` import**

Task 2 改造后理论上已无 `new InMemory*`，但 import 可能残留。grep 检查（单条命令，workdir 仓库根）：
- `git grep "InMemory.*Repository" backend/src/test`
若有命中，删除对应 import 行（用 Edit）。

- [ ] **Step 4: 跑全量测试验证（MyBatis 默认 + H2 + 全 schema）**

Run（bash workdir=backend，JAVA_HOME 设 JDK 21，`.\mvnw.cmd test -q`）
Expected: BUILD SUCCESS，全绿。此时：
- `@SpringBootTest`（BackendApplicationTests、FrontendIntegrationFlowTest、7 个 Service 测试）用 MyBatis bean + H2 + 全 schema
- `MyBatisXxxRepositoryTest` 切片仍 `@MybatisPlusTest + @Sql`（@Profile 移除不影响）
- 若失败：检查是否某测试依赖 InMemory 特有行为（如内存自增 ID 顺序），改为 H2 友好写法；记入报告

- [ ] **Step 5: 残余检查**
- `git grep "InMemory" backend/src`（应无 main 命中；test 仅可能的注释）
- `git grep '@Profile("local")' backend/src/main`（应无命中）

- [ ] **Step 6: 提交（git rm 已 stage 删除；其余 add；commit）**
- `git add backend/src/main/java/com/campushub/backend`（6 个 MyBatis*Repository 改动 + 6 个 InMemory 删除已由 git rm stage）
- `git commit -m "refactor(repository): 删除 InMemory 仓储，MyBatis 成唯一默认实现"`

---

## 验收清单（全部 task 完成后核对）
- [ ] `cd backend && ./mvnw clean package` 构建成功
- [ ] `cd backend && ./mvnw test` 全绿，测试数 ≥ 125
- [ ] `git grep "InMemory.*Repository" backend/src/main` 无命中
- [ ] `git grep '@Profile("local")' backend/src/main` 无命中
- [ ] 默认 profile（无 `SPRING_PROFILES_ACTIVE`）下 `BackendApplicationTests` contextLoads 通过
- [ ] `FrontendIntegrationFlowTest` 全绿（MyBatis + H2 + 全 schema）
- [ ] API 行为不变（前端兼容）
