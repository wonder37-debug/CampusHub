# 仓储层统一阶段 2A 设计（删 InMemory + MyBatis 默认 + 测试适配）

- 日期：2026-09-28
- 状态：待评审
- 分支：`refactor/optimization`（子项目1 已完成，HEAD `7f15c00`）
- 范围：后端 `*/repository/` 仓储实现层 + 测试适配
- 上游 spec：`docs/superpowers/specs/2026-09-27-backend-common-infra-design.md`（子项目分解第 2 项的阶段一）

## 1. 背景与现状（探索已核实）

### 1.1 双实现 + Profile 切换
每个业务模块（auth/demand/order/review/notification/recommendation）的仓储接口有两个实现：
- `InMemory*Repository`：`@Repository @Profile("!local")` — 默认 profile 激活
- `MyBatis*Repository`：`@Repository @Profile("local")` — 仅 local profile 激活

`application.properties:22` 设 `spring.profiles.active=${SPRING_PROFILES_ACTIVE:local}`，但 **test classpath 的 `application.properties` 同名文件覆盖 main 且未设 `spring.profiles.active`**，故 `@SpringBootTest` 测试实际运行在**默认 profile**（无 active）→ InMemory 激活、MyBatis 不激活。

### 1.2 影响删 InMemory 的关键点
- 删 InMemory 后，默认 profile 无任何 `*Repository` bean → `@SpringBootTest`（`BackendApplicationTests`、`FrontendIntegrationFlowTest`）与 `DemoDataInitializer`（`api/DemoDataInitializer.java` 注入 `UserRepository`）启动即崩。
- 7 个 Service 单测在 `@BeforeEach` 直接 `new InMemory*Repository(...)`（共 21 处），删 InMemory 后编译失败：
  - `AuthApplicationServiceImplTest`、`DemandApplicationServiceImplTest`、`OrderApplicationServiceImplTest`、`ReviewApplicationServiceImplTest`、`NotificationApplicationServiceImplTest`、`RecommendationApplicationServiceImplTest`、`AdminApplicationServiceImplTest`
- 6 个 `MyBatisXxxRepositoryTest` 切片测试用 `@MybatisPlusTest + @ActiveProfiles("local") + @Sql("classpath:schema-*.sql")`，移除 `@Profile("local")` 后仍正常（`@MybatisPlusTest` 自动配置 MyBatis，`@Import` 显式导入仓储）。
- `FrontendIntegrationFlowTest` 的 `properties` 显式覆盖 `spring.sql.init.schema-locations=classpath:schema.sql`（仅 `sys_user` 表），删 InMemory 改用 MyBatis 后，demand/order/review/notification/recommendation 表不存在 → 必须加载全部 `schema-*.sql`。

## 2. 目标 / 非目标

### 目标
1. 删除全部 6 个 `InMemory*Repository`，移除 6 个 `MyBatis*Repository` 的 `@Profile("local")`，让 MyBatis 实现成为**所有 profile 下的唯一默认 bean**。
2. 适配 7 个 Service 单测 + 2 个 `@SpringBootTest`，使全量 `mvn test` 在 H2 + MyBatis 下全绿。
3. 保持前端兼容契约（本阶段不动 API/Controller/Service 业务逻辑，仅换仓储实现来源）。

### 非目标
- SQL 下沉（分页/排序/LIKE）→ 子项目 2B
- N+1 修复 → 子项目 2C
- 索引补齐 → 子项目 2D
- 配置修正（`type-aliases-package`）→ 子项目 2E
- 不改 Repository 接口签名、不改 Service 业务逻辑、不改 Controller

## 3. 约束（继承自主 spec + 用户确认）
- 前端兼容契约：`ApiResponse`/`ErrorResponse` 字段结构不变；API 行为不变
- 删除全部 `InMemory*Repository`，测试统一用 H2 + 真实 MyBatis Mapper
- 现有测试全绿 + 不回归（子项目1 后基线 125/125）
- 工作分支 `refactor/optimization`
- 测试验证：`cd backend && ./mvnw test`（本机 `JAVA_HOME` 需指向 JDK 21）

## 4. 详细设计

### 4.1 删除 InMemory 实现（6 文件）
- `auth/repository/InMemoryUserRepository.java`
- `demand/repository/InMemoryDemandRepository.java`
- `order/repository/InMemoryOrderRepository.java`
- `review/repository/InMemoryReviewRepository.java`
- `notification/repository/InMemoryNotificationRepository.java`
- `recommendation/repository/InMemoryUserActionLogRepository.java`

### 4.2 移除 MyBatis 实现的 `@Profile("local")`（6 文件）
对每个 `MyBatis*Repository.java`：
- 删除 `import org.springframework.context.annotation.Profile;`
- 删除 `@Profile("local")` 注解
- 保留 `@Repository`
- 更新类 Javadoc（移除"仅在 local profile 下激活"措辞）

文件：`MyBatisUserRepository`、`MyBatisDemandRepository`、`MyBatisOrderRepository`、`MyBatisReviewRepository`、`MyBatisNotificationRepository`、`MyBatisUserActionLogRepository`。

### 4.3 test `application.properties` 调整
当前：
```properties
spring.sql.init.schema-locations=classpath:schema.sql
```
改为加载全部模块 schema（按依赖顺序）：
```properties
spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql
app.demo-data.enabled=false
```
- 加载全部表，让 `@SpringBootTest` 的 MyBatis 仓储可用
- `app.demo-data.enabled=false` 避免演示数据干扰测试断言（Service 测试自行 `@BeforeEach` save 数据）

### 4.4 `FrontendIntegrationFlowTest` 调整
`properties` 中：
```properties
"spring.sql.init.schema-locations=classpath:schema.sql"
```
改为（与 test `application.properties` 一致的全 schema）：
```properties
"spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-demand.sql,classpath:schema-order.sql,classpath:schema-review.sql,classpath:schema-notification.sql,classpath:schema-recommendation.sql"
```
其余 properties（H2 url、demo-data=false、mail exclude、jwt secret）不变。

### 4.5 Service 单测改造（7 文件）→ `@SpringBootTest`
**策略**：Service 单测当前是"真实 InMemory 仓储 + 真实 Service"的端到端风格。删 InMemory 后，改用 `@SpringBootTest`（H2 + 真实 MyBatis 仓储 + 真实 Service），保留端到端验证语义，**测试方法逻辑基本不变**，仅改类注解与 `setUp` 数据准备方式。

每个 Service 测试类改造模式：
- 类注解加：
  ```java
  @SpringBootTest(classes = BackendApplication.class, properties = {
      "app.demo-data.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
  })
  @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
  ```
  （H2 + 全 schema + jwt secret 由 test `application.properties` 提供，无需重复；`AFTER_EACH_TEST_METHOD` 保证每个测试方法独立数据隔离。）
- `setUp` 改为 `@Autowired` 注入被测 Service 及所需 Repository，用 Repository `save` 准备数据（替代 `new InMemory*` + 手工构造 Service）。
- 测试方法体（断言业务行为）保持不变。
- 删除 `new InMemory*Repository(...)` 与手工 `new XxxApplicationServiceImpl(...)` 构造。

涉及文件：`AuthApplicationServiceImplTest`、`DemandApplicationServiceImplTest`、`OrderApplicationServiceImplTest`、`ReviewApplicationServiceImplTest`、`NotificationApplicationServiceImplTest`、`RecommendationApplicationServiceImplTest`、`AdminApplicationServiceImplTest`。

> **权衡说明**：`@SpringBootTest` 启动 Spring context 比纯 InMemory 慢，但：① 满足"测试走 H2 + 真实 MyBatis"的约束；② 改动量最小（测试断言逻辑不动）；③ Spring test context caching 让相同配置的类复用 context。Mockito mock 方案虽快但需为每个 Repository 方法重写 stub（21 处 × 多方法），改动量更大且丢失端到端语义。后续 2B 改 Repository 接口时再评估是否拆 mock。

### 4.6 `BackendApplicationTests`
`@SpringBootTest` 默认 contextLoads，随 4.3 test `application.properties` 改全 schema + MyBatis 默认激活后自动通过，无需改代码。

### 4.7 `MyBatisXxxRepositoryTest`（6 切片）不动
继续 `@MybatisPlusTest + @ActiveProfiles("local") + @Sql("classpath:schema-*.sql")`。移除 `@Profile("local")` 后，`@ActiveProfiles("local")` 变为无具体意义但无害（保留不影响）。可选在 2E 顺手移除，本阶段不强求。

## 5. 影响面

### main 改动
| 文件 | 操作 |
|---|---|
| 6 个 `InMemory*Repository.java` | 删除 |
| 6 个 `MyBatis*Repository.java` | 移除 `@Profile("local")` + import + Javadoc |

### test 改动
| 文件 | 操作 |
|---|---|
| `src/test/resources/application.properties` | schema-locations 加全 schema + demo-data=false |
| `FrontendIntegrationFlowTest.java` | properties schema-locations 改全 schema |
| 7 个 `*ApplicationServiceImplTest.java` | 改 `@SpringBootTest` + `@Autowired setUp` |
| 6 个 `MyBatisXxxRepositoryTest.java` | 不动 |
| `BackendApplicationTests.java` | 不动（自动适配） |

### 不受影响
- Repository 接口、Entity、Mapper、Domain
- Service 业务逻辑、Controller、API
- 前端

## 6. 验收标准
1. `cd backend && ./mvnw clean package` 构建成功
2. `cd backend && ./mvnw test` 全绿，测试数 ≥ 125（不回归）
3. `grep -r InMemory backend/src` 无 `InMemory*Repository` 命中（类全删）
4. `grep -r '@Profile("local")' backend/src/main` 无命中（MyBatis 实现不再带 profile）
5. 默认 profile（无 `SPRING_PROFILES_ACTIVE`）下 Spring context 能启动（`BackendApplicationTests` 证明）
6. 前端兼容：API 行为不变（`FrontendIntegrationFlowTest` 全绿证明）

## 7. 风险与对策
| 风险 | 对策 |
|---|---|
| `@SpringBootTest` Service 测试变慢 | 接受为 2A 代价；context caching 缓解；后续 2B 评估 mock |
| `DirtiesContext AFTER_EACH_TEST_METHOD` 进一步拖慢 | 保证数据隔离的稳妥选择；若过慢可改 `@Sql`+`@DirtiesContext AFTER_CLASS` |
| Service 测试 `@Autowired` 注入的 Service 含可选依赖（`@Autowired(required=false)`）在 `@SpringBootTest` 下行为变化 | 实际 `@SpringBootTest` 加载全 context，可选依赖会被注入真实 bean（不再 null），可能暴露此前被 null 检查掩盖的路径；若测试失败，需在测试 properties 中 exclude 相关自动配置或补 stub |
| H2 与 MySQL 方言差异致测试行为偏差 | 现有 `MyBatisXxxRepositoryTest` 已在 H2 验证仓储，风险已知可控 |
| `DemoDataInitializer` 在 `@SpringBootTest` 下执行 | `app.demo-data.enabled=false` 关闭 |

## 8. 后续
2A 完成后进入：
- 2D 索引补齐（生产 `init_schema.sql` + H2 `schema-*.sql` 同步）
- 2B Repository 接口扩展 + SQL 下推
- 2C N+1 修复
- 2E 配置修正
