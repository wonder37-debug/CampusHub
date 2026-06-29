# P4 集成测试与 CI/CD 规范

## 1. 文档目的

本文档定义 CampusHub 项目的**集成测试策略**和 **CI/CD 流水线规范**，涵盖以下内容：

1. 集成测试的设计思路、环境配置、场景覆盖标准和编写约定
2. GitLab CI 流水线的阶段划分、各 Job 职责、环境变量和资源约束
3. CI 环境搭建过程中遇到的关键问题及其解决方案
4. 本地运行测试的操作指南和问题排查方法

本文档与 P4 其他规范的关系：

- 本规范中 CI 流水线的 `unit-test` 阶段运行的是服务层同学编写的单元测试（`*ServiceImplTest`），其编写规范不在本文档范围内
- 集成测试 `FrontendIntegrationFlowTest` 由本规范的负责同学编写和维护
- CI 环境中的 H2 数据库 Schema 需与数据库同学的 `init_schema.sql` 保持同步，对应关系见 [P4-数据库接口调用规范](./P4-数据库接口调用规范.md)

---

## 2. 适用范围

本规范覆盖以下配置和代码：

| 文件/目录 | 说明 |
|---|---|
| `.gitlab-ci.yml` | CI 主流水线定义（阶段划分、触发规则） |
| `.gitlab/backend.yml` | 后端各 Job 模板与具体配置 |
| `.gitlab/frontend.yml` | 前端各 Job 模板与具体配置 |
| `.gitlab/maven-settings.xml` | Maven 阿里云镜像加速 |
| `backend/pom.xml` | 后端构建配置、依赖、Surefire 插件配置 |
| `backend/src/test/java/.../api/FrontendIntegrationFlowTest.java` | 集成测试入口类 |
| `backend/src/test/resources/` | 测试环境数据源配置与 H2 Schema 文件 |

---

## 3. 项目测试分层

### 3.1 四层结构

本项目采用四层测试体系，从下到上成本递增、覆盖范围递增：

```
┌──────────────────────────────────────────────┐
│              集成测试 (Integration)            │
│   FrontendIntegrationFlowTest                 │
│   全链路 API 流程验证，MockMvc + H2           │
├──────────────────────────────────────────────┤
│              单元测试 (Unit)                   │
│   *ServiceImplTest / RequestUserExtractorTest │
│   服务层 + 工具类，内存仓储，不依赖 Spring 容器  │
├──────────────────────────────────────────────┤
│              持久层测试 (Repository)            │
│   MyBatis*RepositoryTest                      │
│   仓储实现验证，H2 数据库，MyBatis 映射正确性    │
├──────────────────────────────────────────────┤
│              静态检查 (Static Check)            │
│   后端: mvn compile / 前端: vue-tsc type-check │
│   编译期类型与语法校验                          │
└──────────────────────────────────────────────┘
```

### 3.2 各层职责

- **静态检查**：编译期拦截语法错误、类型不匹配、import 路径错误。成本最低，几秒到几十秒即可完成
- **持久层测试**：验证 MyBatis SQL 映射正确性和数据库唯一约束。使用 H2 内存数据库的 MySQL 兼容模式
- **服务层单元测试**：覆盖业务逻辑正确性、状态流转、参数校验、权限判断、异常分支。采用纯 JUnit 5 + 内存仓储（`InMemory*Repository`）模式，不启动 Spring 容器
- **集成测试**：验证核心业务流程的端到端正确性和跨模块数据一致性。使用 SpringBootTest + MockMvc + H2 内存数据库

### 3.3 集成测试与单元测试的边界

集成测试不重复验证单元测试已覆盖的逻辑。两者分工如下：

| 关注点 | 单元测试负责 | 集成测试负责 |
|---|---|---|
| 单模块业务逻辑 | ✓ | — |
| 参数校验规则 | ✓ | — |
| 状态流转正确性 | ✓ | — |
| 异常分支覆盖 | ✓ | — |
| 认证拦截器链路 | — | ✓ |
| 跨模块数据联动 | — | ✓ |
| 完整业务流程串联 | — | ✓ |
| 错误码 HTTP 返回 | — | ✓ |
| 权限不足的 HTTP 拒绝 | — | ✓ |

---

## 4. 集成测试规范

### 4.1 设计思路

集成测试的核心目标是回答以下问题：

- 认证拦截器是否正确拦截了未登录请求？
- 用户注册获取的 Token 能否在后续接口中正常鉴权？
- 发布需求后，管理员能否在审核列表中看到它？
- 审核通过后，需求是否出现在公开大厅列表中？
- 接单后，需求状态和订单状态是否联动变化？
- 订单完成后，评价和信用分流程是否串联触发？
- 非参与方是否被正确拒绝访问订单详情？

这些场景涉及多模块协作，单独测试某一层无法验证。因此集成测试不追求覆盖所有业务分支（那是单元测试的职责），而是确保核心主链路和关键异常分支能端到端正确运行。

### 4.2 测试入口

集成测试统一入口为 `FrontendIntegrationFlowTest`，位于：

```
backend/src/test/java/com/campushub/backend/api/FrontendIntegrationFlowTest.java
```

该测试类使用 MockMvc 模拟前端 HTTP 请求，通过 H2 内存数据库运行完整的 Spring 应用上下文。

### 4.3 环境配置

```java
@SpringBootTest(classes = BackendApplication.class, properties = {
    "app.demo-data.enabled=false",                          // 禁用 Demo 数据
    "spring.autoconfigure.exclude=...MailSenderAutoConfiguration", // 排除邮件自动配置
    "spring.datasource.url=jdbc:h2:mem:campushub_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
```

环境配置的四个关键决策：

1. **独立 H2 数据库**：使用 `campushub_integration` 库名，与持久层测试的 `campushub_test` 隔离。`MODE=MySQL` 开启 MySQL 语法兼容，`DATABASE_TO_LOWER=TRUE` 消除大小写差异，`DB_CLOSE_DELAY=-1` 保持数据库在多次连接间存活

2. **排除邮件发送**：CI 环境无 SMTP 服务器，通过 `spring.autoconfigure.exclude` 排除 `MailSenderAutoConfiguration`。测试中注册流程改为直接调用 `authApplicationService.sendRegistrationCode(email, studentId)` 获取验证码，绕过邮件发送步骤

3. **隔离测试上下文**：`@DirtiesContext(classMode = AFTER_CLASS)` 确保测试类结束后 Spring 上下文被清理，不会影响后续测试

4. **全局超时保护**：`@Timeout(3min)` 防止 CI 环境下因资源竞争导致测试挂死

### 4.4 场景覆盖标准

集成测试的覆盖范围遵循以下标准：

- **必须覆盖**：完整主链路（Happy Path），从注册到订单完成的全部核心业务环节
- **必须覆盖**：认证与鉴权的异常拒绝场景（未登录、权限不足）
- **必须覆盖**：业务冲突的拒绝场景（重复接单、自接自单）
- **必须覆盖**：数据可见性的边界场景（审核中需求不可公开见、订单仅参与方可见）
- **不需覆盖**：服务层已通过单元测试覆盖的业务逻辑分支和参数边界

### 4.5 已覆盖场景

`FrontendIntegrationFlowTest` 包含 8 个测试方法：

| 测试方法 | 场景类型 | 验证的业务规则 |
|---|---|---|
| `shouldCompleteHappyPathAcrossFrontendApis` | 主链路 | 注册→登录→发需求→管理员审核→大厅浏览→推荐→接单→订单流转→双确认完成→评价→通知 |
| `shouldRejectUnauthenticatedProtectedApi` | 认证拦截 | 无 Token 访问受保护接口返回 401，`errorCode = AUTH_FAILED` |
| `shouldRejectDuplicateAcceptingDemand` | 业务冲突 | 第二人接同一需求返回 409，`errorCode = BUSINESS_CONFLICT` |
| `shouldRejectNonCampusEmailVerificationRequest` | 参数校验 | 非校园邮箱请求验证码返回 400 |
| `shouldRejectPublishingDemandWithInvalidParameters` | 参数校验 | 标题过短、报酬为负返回 400 |
| `shouldListOwnReviewingDemandOnlyWhenRequested` | 数据可见性 | 大厅默认不显示审核中需求，`includeOwn=true` 可显示 |
| `shouldRejectPublisherAcceptingOwnDemand` | 权限判断 | 发布者接自己需求返回 403，`errorCode = PERMISSION_DENIED` |
| `shouldRejectOutsiderViewingOrderDetail` | 权限判断 | 非参与方查看订单详情返回 403，`errorCode = PERMISSION_DENIED` |

其中主链路测试在一个方法内串联执行全部步骤，每步完成后立即断言关键字段，任一步骤失败即可快速定位问题环节。

### 4.6 编写约定

集成测试代码遵循以下约定：

1. **用户隔离**：每个测试方法通过 `registerAndLogin(prefix)` 创建独立用户。用户标识（学号、邮箱）使用 `System.nanoTime()` 后缀确保全局唯一，防止测试间数据串扰

2. **验证码绕过**：注册流程通过 `authApplicationService.sendRegistrationCode(email, studentId)` 直接获取验证码，不依赖 HTTP 验证码接口（该接口依赖邮件发送，在测试环境不可用）

3. **预置管理员**：在 `@BeforeEach` 中预置管理员账号 `admin / Admin1234`，角色为 `ADMIN`，供审核流程使用

4. **双确认流程**：订单完成测试必须覆盖"接单方提交 → 发布方确认"完整两步。接单方提交后断言 `status = IN_PROGRESS` 且 `proofSubmitted = true`，发布方确认后断言 `status = COMPLETED`

5. **断言策略**：优先断言业务字段（`status`、`errorCode`、`code`）而非仅断言 HTTP 状态码。对于成功响应，验证 `data` 中的业务字段值；对于失败响应，验证 `errorCode` 的语义是否与业务规则一致

---

## 5. GitLab CI/CD 流水线规范

### 5.1 设计原则

流水线设计遵循以下原则：

1. **尽早失败（Fail-Fast）**：阶段之间严格串行，前一阶段失败则后续阶段不再执行。静态检查不通过就不跑测试，单元测试不通过就不跑集成测试，从而最快速度反馈问题

2. **按需触发（Change-Driven）**：每个 Job 配置 `rules:changes`，只在相关文件变更时运行。仅修改前端代码不触发后端测试，仅修改后端代码不触发前端构建

3. **职责分离（Separation of Concerns）**：前后端 CI 配置拆分到独立文件，主文件只定义阶段顺序和触发规则

4. **资源可控（Resource-Bounded）**：严格限制 JVM 堆内存和 CPU 核数，确保在共享 Runner 的有限资源下稳定运行

### 5.2 流水线架构

```
触发条件: MR 事件 或 分支推送（无已开启 MR 时）

┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌──────────────────┐    ┌──────────┐
│  deps    │───▶│ static-check │───▶│ unit-test   │───▶│ integration-test │───▶│  build   │
│ 安装依赖  │    │ 编译/类型检查  │    │ 单元测试     │    │ 集成测试          │    │ 构建产物  │
└──────────┘    └──────────────┘    └────────────┘    └──────────────────┘    └──────────┘
    并行              并行               并行                串行                  并行
 后端: mvn         后端: mvn          后端: mvn           后端: mvn             后端: mvn
 test-compile      compile            test                 test                  package
 前端: npm ci      前端: npm ci        (ServiceImplTest)    (FrontendInt...Test)  前端: npm run
                   前端: type-check                                              build
```

配置文件结构：

```
.gitlab-ci.yml          # 主入口：阶段定义、触发规则、include 子文件
.gitlab/
├── backend.yml         # 后端 5 个 Job 的完整定义
├── frontend.yml        # 前端 3 个 Job 的完整定义
└── maven-settings.xml  # Maven 阿里云镜像加速
```

### 5.3 触发规则

```yaml
workflow:
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'   # MR 触发
    - if: '$CI_COMMIT_BRANCH && $CI_OPEN_MERGE_REQUESTS'   # 已有开启 MR 的分支不重复触发
      when: never
    - if: '$CI_COMMIT_BRANCH'                               # 普通分支推送
```

每个 Job 额外配置 `rules:changes`，按文件路径匹配：

- 后端 Job 触发条件：`backend/**/*`、`.gitlab/backend.yml`、`.gitlab/maven-settings.xml`、`.gitlab-ci.yml`
- 前端 Job 触发条件：`frontend/**/*`、`.gitlab/frontend.yml`、`.gitlab-ci.yml`

### 5.4 后端 Job 规范

镜像：`maven:3.9.9-eclipse-temurin-21`（Java 21）

| Job | 阶段 | 超时 | 说明 |
|---|---|---|---|
| `backend:install-deps` | deps | 20min | `mvn -DskipTests test-compile`，预下载依赖并缓存到 `.m2/repository/`，后续 Job 复用 |
| `backend:static-check` | static-check | 10min | `mvn -DskipTests compile`，编译期拦截语法和类型错误 |
| `backend:unit-test` | unit-test | 15min | `mvn test -Dtest="*ServiceImplTest,RequestUserExtractorTest"`，运行服务层单元测试。配置 `surefire.timeout=240` 单测超时，`timeout` 命令兜底 |
| `backend:integration-test` | integration-test | 25min | `mvn test -Dtest="FrontendIntegrationFlowTest"`，运行集成测试。测试前后采集系统资源快照写入 `target/diagnostics/` |
| `backend:build` | build | 15min | `mvn -DskipTests package`，产出 `target/*.jar` |

产物保留策略：

- JUnit XML 报告（`target/surefire-reports/TEST-*.xml`）：保留 7 天，用于 GitLab MR 测试报告展示
- 诊断日志（`backend/target/diagnostics/`）：保留 7 天，用于 CI 失败回溯
- 构建产物（`backend/target/*.jar`）：保留 7 天

`integration-test` Job 的诊断能力设计：

- 编译前采集一次系统状态快照（时间、内存、磁盘、cgroup 限制）
- 编译失败时打印编译日志尾部 200 行和 cgroup 状态，避免静默失败
- 测试前后各采集一次进程列表和 cgroup 内存使用情况
- 测试完成时记录退出码，区分正常结束、超时（124）和被 SIGKILL（137）

### 5.5 前端 Job 规范

镜像：`node:20-bookworm`

| Job | 阶段 | 超时 | 说明 |
|---|---|---|---|
| `frontend:install-deps` | deps | 10min | `npm ci --prefer-online --no-audit --no-fund` |
| `frontend:static-check` | static-check | 10min | `npm ci && npm run type-check`（vue-tsc TypeScript 类型检查） |
| `frontend:build` | build | 15min | `npm ci && npm run build`，产出 `frontend/dist/` |

当前前端未配置 Vitest 等单元测试框架，质量保障依赖 TypeScript 类型检查和 Vite 构建验证。

### 5.6 CI 环境变量规范

后端所有 Job 继承 `.backend-template` 中定义的环境变量：

| 变量 | 值 | 规范说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `default` | CI 环境不加载 `local` profile，避免连接本地 MySQL |
| `SPRING_AUTOCONFIGURE_EXCLUDE` | `MailSenderAutoConfiguration` | CI 环境无 SMTP 服务器，必须排除邮件自动配置 |
| `SPRING_MAIN_LAZY_INITIALIZATION` | `true` | 延迟初始化，减少 Spring 启动时内存峰值 |
| `APP_DEMO_DATA_ENABLED` | `false` | 禁用 Demo 数据初始化，避免干扰测试数据 |
| `ARTIFACT_COMPRESSION_LEVEL` | `fastest` | 产物压缩优先速度而非体积 |

### 5.7 Maven 加速与网络配置

使用阿里云 Maven 公共镜像（`.gitlab/maven-settings.xml`）代理 Central 仓库：

```xml
<mirror>
  <id>aliyun-public</id>
  <name>Aliyun Maven Public Mirror</name>
  <url>https://maven.aliyun.com/repository/public</url>
  <mirrorOf>central</mirrorOf>
</mirror>
```

Maven 命令行超时与重试参数：

```
MAVEN_CLI_OPTS: "-B -ntp
  -Daether.connector.connectTimeout=30000
  -Daether.connector.requestTimeout=120000
  -Dmaven.wagon.rto=120000
  -Dmaven.wagon.http.retryHandler.count=1"
```

参数说明：
- `-B -ntp`：非交互模式，不输出下载进度（减少 CI 日志量）
- `connectTimeout=30000`：连接超时 30 秒
- `requestTimeout=120000`：请求超时 120 秒，适配较大依赖的下载
- `retryHandler.count=1`：失败重试 1 次

### 5.8 CI 资源约束规范

GitLab 共享 Runner 通常仅提供 1-2GB 容器内存。为防止 OOM Kill，CI 环境中的 JVM 进程必须遵守以下约束：

**Maven 自身 JVM（`MAVEN_OPTS`）：**

CI 脚本中通过 `export` 设置以下环境变量：`JAVA_TOOL_OPTIONS` 设为 `-XX:ActiveProcessorCount=1 -XX:+UseSerialGC`（限制 CPU 可见核数和 GC 线程），`MAVEN_OPTS` 设为 `-Xmx256m -XX:+ExitOnOutOfMemoryError`（限制堆内存并在 OOM 时立即退出）。

**Surefire 测试 Fork JVM（`pom.xml`）：**

```xml
<argLine>-Xmx256m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC
  -XX:+ExitOnOutOfMemoryError
  -Djava.security.egd=file:/dev/./urandom</argLine>
<forkCount>1</forkCount>
<reuseForks>false</reuseForks>
```

约束要点：
- `-Xmx256m`：堆内存上限 256MB，防止单个 JVM 进程占用过多内存
- `-XX:ActiveProcessorCount=1`：限制 JVM 可见 CPU 为 1 核，避免过多并行 GC 线程
- `-XX:+UseSerialGC`：使用单线程串行 GC，降低内存和线程开销
- `-XX:+ExitOnOutOfMemoryError`：OOM 时立即退出，而非挂起等待
- `forkCount=1`：Surefire 仅 fork 1 个进程，防止并行测试导致内存叠加

---

## 6. CI 环境关键问题与解决方案

### 6.1 问题一：无外部数据库

**问题**：CI Runner 容器内无 MySQL 服务，应用启动和测试无法连接数据库。

**方案**：测试环境使用 H2 内存数据库，开启 MySQL 兼容模式。

```
jdbc:h2:mem:campushub_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
```

三个参数的作用：
- `MODE=MySQL`：模拟 MySQL SQL 语法和函数
- `DATABASE_TO_LOWER=TRUE`：MySQL 默认不区分表名/列名大小写，H2 默认区分，此参数消除差异
- `DB_CLOSE_DELAY=-1`：保持数据库在 JVM 生命周期内存活，不被连接关闭时销毁

**局限**：H2 不完全兼容所有 MySQL 特性。项目的 MyBatis SQL 使用标准 SQL 语法，未触发兼容性问题。若未来引入 MySQL 特有语法（如 `GROUP_CONCAT`、`FIND_IN_SET`），需改用 Testcontainers 启动真实 MySQL 容器。

### 6.2 问题二：无 SMTP 邮件服务

**问题**：项目注册流程需要发送邮箱验证码，CI 环境无法连接 SMTP 服务器。

**方案**：两层处理。

第一层：环境变量排除 Spring Mail 自动配置：
```yaml
SPRING_AUTOCONFIGURE_EXCLUDE: "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
```

第二层：集成测试中不调用 HTTP 验证码接口，改为直接调用服务层方法获取验证码：
```java
EmailVerificationIssue issue = authApplicationService.sendRegistrationCode(email, studentId);
// 使用 issue.verificationCode() 完成注册
```

`sendRegistrationCode()` 方法生成验证码后将其返回（生产环境会额外发送邮件），测试代码拿到验证码即可走完后续注册流程。这样既覆盖了注册接口的参数校验和业务逻辑，又绕过了对 SMTP 的外部依赖。

### 6.3 问题三：CI Runner OOM

**问题**：流水线调试阶段，Maven 进程偶发退出码 137（被 SIGKILL）。经诊断确认为容器内存不足触发 OOM Kill。

**排查方法**：在 `integration-test` Job 的脚本中，测试前后遍历 `/sys/fs/cgroup/memory.max`、`memory.current`、`memory.events`、`memory.limit_in_bytes`、`memory.usage_in_bytes`、`memory.failcnt` 等 cgroup 文件，将值打印到诊断日志。

通过 `memory.failcnt` 大于 0 且 `memory.usage_in_bytes` 接近 `memory.limit_in_bytes` 可确认 OOM。

**方案**：实施 5.8 节所述的资源约束——限制堆内存 256MB、CPU 核数 1、使用 SerialGC、延迟初始化 Spring。实施后单次 CI 运行内存峰值控制在 300MB 以内。

### 6.4 问题四：集成测试超时

**问题**：`FrontendIntegrationFlowTest` 本地约 30 秒完成，但在共享 CI Runner 上因 CPU 竞争可能显著变慢。

**方案**：设置三道超时防线：

1. 框架级：`@Timeout(value = 3, unit = TimeUnit.MINUTES)`
2. Surefire 级：`-Dsurefire.timeout=360`（单测试方法 6 分钟上限）
3. CI 脚本级：`timeout -k 30s 10m mvn ... test`（整体 10 分钟上限，超时后 30 秒宽限期发送 SIGKILL）

三道防线各司其职：框架级保护单个测试类、Surefire 级保护单个测试方法、脚本级保护整个 Job。

---

## 7. 测试数据管理规范

### 7.1 测试账号

- **管理员**：`admin / Admin1234`，角色 `ADMIN`，由集成测试 `@BeforeEach` 预置
- **普通用户**：各测试方法独立注册，命名规则 `{prefix}-{nanoTime}@smail.nju.edu.cn`，学号以 `S` 前缀加 nanoTime 后缀

`nanoTime` 后缀确保每次测试运行的账号全局唯一，避免并行或重跑时的数据冲突。

### 7.2 数据库隔离

| 测试层级 | 数据库 | 说明 |
|---|---|---|
| 服务层单元测试 | 无（内存仓储） | `InMemory*Repository` 基于 ConcurrentHashMap |
| 持久层测试 | H2 `campushub_test` | 独立内存库 |
| 集成测试 | H2 `campushub_integration` | 独立内存库，与持久层测试隔离 |

所有测试数据库均在内存中运行，测试进程结束后自动销毁，不产生持久化副作用。

### 7.3 邮件处理

- 所有测试环境均排除 `MailSenderAutoConfiguration`
- 集成测试通过 `authApplicationService.sendRegistrationCode()` 直接获取验证码
- 不得在任何测试中连接真实 SMTP 服务器

---

## 8. 本地运行与调试

### 8.1 前置条件

运行测试所需：

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 后端编译与测试运行 |
| Node.js | 18+ | 前端构建与类型检查 |

> **注意**：后端测试使用 H2 内存数据库，**完全不需要安装 MySQL 或 Maven**。项目内置了 Maven Wrapper（`mvnw`），首次运行时自动下载 Maven。

本地运行完整应用额外需要：

| 工具 | 版本 | 说明 |
|---|---|---|
| MySQL | 8.x | 默认连接 `localhost:3306`，数据库名 `campushub` |

### 8.2 通用约定

**所有操作均在项目根目录 `sec-ii-2026` 下执行。** 如果终端当前在其他位置，需要先切换过去——Windows PowerShell 切换到 `D:\24761\sec-ii-2026`，Git Bash 或 Linux 切换到 `/d/24761/sec-ii-2026`。

**Maven Wrapper 命令前缀**因操作系统而异：

| 操作系统 | 命令前缀 | 示例 |
|---|---|---|
| Windows（PowerShell / CMD） | `.\mvnw.cmd` | `.\mvnw.cmd test` |
| Linux / macOS / Git Bash | `./mvnw` | `./mvnw test` |

本节后续描述用 `{mvnw}` 表示命令前缀，实际使用时替换为上表中对应形式。

### 8.3 运行后端测试

后端测试无需 MySQL，只需 JDK 21。从项目根目录进入 `backend` 子目录后执行。

各类测试的运行命令如下：

| 测试类型 | 命令 | 说明 |
|---|---|---|
| 服务层单元测试 | `{mvnw} test -Dtest="*ServiceImplTest,RequestUserExtractorTest"` | 使用内存仓储，毫秒级完成 |
| 持久层测试 | `{mvnw} test -Dtest="MyBatis*RepositoryTest"` | 使用 H2 内存数据库 |
| 集成测试 | `{mvnw} test -Dtest="FrontendIntegrationFlowTest"` | 全链路，使用 H2 内存数据库 |
| 全部测试 | `{mvnw} test` | 运行项目中所有测试类 |
| 构建产物（跳过测试） | `{mvnw} -DskipTests package` | 产出 `target/*.jar` |

以 Windows PowerShell 为例，进入 `D:\24761\sec-ii-2026\backend` 后，运行服务层单元测试的具体命令是 `.\mvnw.cmd test -Dtest="*ServiceImplTest,RequestUserExtractorTest"`，运行集成测试是 `.\mvnw.cmd test -Dtest="FrontendIntegrationFlowTest"`，运行全部测试是 `.\mvnw.cmd test`。Git Bash 或 Linux 下将 `.\mvnw.cmd` 改为 `./mvnw` 即可。

### 8.4 运行前端检查

从项目根目录进入 `frontend` 子目录后执行：

| 步骤 | 命令 | 说明 |
|---|---|---|
| 安装依赖 | `npm install` | 首次或 `node_modules` 不存在时需要 |
| 类型检查 | `npm run type-check` | 执行 `vue-tsc -b`，TypeScript 编译期校验 |
| 生产构建 | `npm run build` | 执行 `vue-tsc -b && vite build`，产物在 `frontend/dist/` |

### 8.5 运行完整应用

运行完整的前后端应用需要本地 MySQL 8.x。详细步骤见 `backend/数据库配置快速指南（团队成员必读）.md`，核心流程如下：

**第一步：配置 MySQL 连接。** 确保本地 MySQL 服务已启动，在 `backend/src/main/resources/` 下的 `application-local.properties`，内容为 `spring.datasource.password=你的MySQL密码`。该文件已被 `.gitignore` 忽略，Spring Boot 启动时会自动建表（执行 `init_schema.sql`）。

**第二步：启动后端。** 从项目根目录进入 `backend` 子目录，执行 `{mvnw} spring-boot:run`。启动成功后无红色报错，后端运行在 `http://localhost:8080`。

**第三步：启动前端。** 另开一个终端，从项目根目录进入 `frontend` 子目录，先执行 `npm install`（首次），再执行 `npm run dev`。前端运行在 `http://localhost:5173`，API 请求自动代理到后端。

**或者使用一键启动脚本**（需要 Git Bash 或 WSL）：在项目根目录执行 `bash start.sh`。

启动后默认测试账号：学号 `admin`，密码 `Admin1234`。

### 8.6 本地模拟 CI 完整流程

在不启动 MySQL 的情况下，按 CI 流水线的阶段顺序依次执行以下 5 个步骤来验证全部检查。

所有步骤均从项目根目录开始。下表以 Windows PowerShell 为例：

| 步骤 | 对应 CI 阶段 | 操作 |
|---|---|---|
| 1 | static-check | 进入 `backend` 目录，执行 `.\mvnw.cmd -DskipTests compile`（编译检查） |
| 2 | unit-test | 仍在 `backend` 目录，执行 `.\mvnw.cmd test -Dtest="*ServiceImplTest,RequestUserExtractorTest"`（服务层单元测试） |
| 3 | integration-test | 仍在 `backend` 目录，执行 `.\mvnw.cmd test -Dtest="FrontendIntegrationFlowTest"`（集成测试） |
| 4 | build | 仍在 `backend` 目录，执行 `.\mvnw.cmd -DskipTests package`（后端构建） |
| 5 | 前端 static-check + build | 切换到 `frontend` 目录，依次执行 `npm install`、`npm run type-check`、`npm run build` |

Git Bash 或 Linux 下将 `.\mvnw.cmd` 改为 `./mvnw`，`..\frontend` 改为 `../frontend` 即可。

> 步骤 1-4 全部使用 H2 内存数据库，无需 MySQL。步骤 5 需要 Node.js，不需要 MySQL。

---

## 9. 常见 CI 失败排查

### 9.1 集成测试本地通过但 CI 失败

常见原因和排查顺序：

1. **Schema 不同步**：检查 `src/test/resources/schema*.sql` 是否与 `init_schema.sql` 存在字段差异。CI 中每次从 schema.sql 初始化，本地可能复用了旧表结构
2. **H2 兼容性**：某些 MyBatis SQL 在 H2 和 MySQL 中行为不同。先确认持久层测试 (`MyBatis*RepositoryTest`) 是否也失败
3. **环境差异**：CI 镜像（`maven:3.9.9-eclipse-temurin-21`）与本地 JDK/Maven 版本不一致。对齐版本后复现

### 9.2 CI Runner OOM

- 现象：Maven 退出码 137（SIGKILL），诊断日志中 `memory.failcnt > 0`
- 排查：检查 `MAVEN_OPTS` 和 Surefire `argLine` 中的内存限制是否被覆盖
- 确认 5.8 节所有约束均已生效

### 9.3 Maven 依赖下载失败

- 现象：`Could not resolve dependencies` 或连接超时
- 排查：确认阿里云镜像可用、超时参数足够、CI 缓存未损坏
- 如需清缓存重试：在 GitLab CI 界面手动清除 Pipeline cache

---

## 10. 规范验收标准

集成测试和 CI/CD 部分满足以下标准视为合格：

1. **CI 流水线全绿**：所有阶段（deps → static-check → unit-test → integration-test → build）在 MR 和分支推送时均通过
2. **集成测试覆盖主链路**：注册→登录→发需求→审核→接单→双确认完成→评价→通知 全链路可自动验证
3. **集成测试覆盖关键异常**：未认证拦截、重复接单拦截、自接自单拦截、订单隐私保护等异常场景均有断言
4. **前端类型检查零错误**：`npm run type-check` 在 CI 中通过
5. **构建产物可用**：`backend/target/*.jar` 和 `frontend/dist/` 可在 CI artifacts 中获取
6. **测试 Schema 与生产脚本一致**：`init_schema.sql` 的字段和约束变更已同步到 `src/test/resources/schema*.sql`
7. **CI 环境无 OOM**：流水线运行日志中无 SIGKILL 或 memory.failcnt 增长

---

## 11. 备注

本规范覆盖集成测试和 CI/CD 两部分的设计决策、配置标准和问题解决方案。规范中的约定和配置均以当前代码中的实际落地形态为准。

其中集成测试由负责集成测试和 CI/CD 的同学编写，服务层单元测试和持久层测试由服务层和数据库同学编写，CI 流水线统一调度执行。
