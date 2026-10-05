# 后端公共基础设施重塑设计（子项目1）

- 日期：2026-09-27
- 状态：待评审
- 分支：`refactor/optimization`
- 范围：后端 `common/` 模块（security / exception / api）+ 相关配置与依赖

## 1. 背景与动机

CampusHub 后端 `common/` 基础设施层存在以下真实痛点（已逐一核实源码）：

| 痛点 | 定位 | 影响 |
|---|---|---|
| Token 无签名 | `common/security/SimpleTokenService.java`：`userId:role:expiry:uuid` 仅 Base64 编码 | 任何人可篡改 `userId`/`role` 伪造管理员 token，严重安全隐患 |
| 验签逻辑散落 | `common/security/RequestUserExtractor.java:31-54` 自写 Base64 解码 + split 解析 | 与签发耦合，难以独立测试，扩展算法需改两处 |
| 兜底异常泄露 | `common/exception/GlobalExceptionHandler.java:20` 直接返回 `exception.getMessage()` | 可能向前端泄露栈信息/内部细节 |
| 兜底错误码游离 | `GlobalExceptionHandler:20` 硬编码 `new ErrorResponse(5000, "INTERNAL_SERVER_ERROR", ...)` | 未走 `ErrorCode` 枚举体系，与业务错误码管理不一致 |
| ErrorCode 不完整 | `common/exception/ErrorCode.java` 仅 5 枚举，无兜底项 | 兜底错误无标准枚举可引用 |

本 spec 为"后端全面架构重塑"的**第一个子项目**，聚焦公共基础设施层，是后续所有模块重塑的地基。

## 2. 目标 / 非目标

### 目标
1. Token 升级为 JWT（HS256 签名 + 验签 + 过期校验），杜绝伪造
2. Token 签发与验签收敛到 `TokenService` 单一抽象，`RequestUserExtractor` 仅负责 HTTP 头解析
3. 异常兜底不再泄露内部细节，统一走 `ErrorCode` 体系
4. 保持前端兼容契约（API 响应字段与语义不变）
5. 现有测试全绿，补齐 JWT 与异常兜底测试

### 非目标
- 不引入 Spring Security 完整鉴权框架（仅升级 Token 实现）
- 不改 `ApiResponse` / `PageResponse` / `ErrorResponse` 字段结构（前端兼容）
- 不改业务模块逻辑（auth/demand/order 等的业务行为不动）
- 不动前端代码
- 不做 refresh token / 双 token 机制（YAGNI）

## 3. 约束（用户已确认）

| 维度 | 约束 |
|---|---|
| 激进度 | 全面架构重塑（architectural 路径） |
| 前端契约 | 后端保持前端兼容契约，前端不动，后端独立交付 |
| 仓储 | 统一为 MyBatis 单实现（删 InMemory），测试走 H2（本子项目不直接涉及，但 Token 测试不依赖 InMemory） |
| 依赖 | 允许引入 jjwt 等必要库 |
| 数据库 schema | 允许加索引与必要微调（本子项目不涉及 schema 改动） |
| 测试 | 现有测试全绿 + 补齐重塑相关测试 |

## 4. 总体架构分解（上下文）

后端全面架构重塑分解为 6 个有序子项目，本 spec 仅覆盖**子项目1**：

| 序 | 子项目 | 依赖 | 状态 |
|---|---|---|---|
| **1** | 公共基础设施重塑（本 spec） | 无 | 设计中 |
| 2 | 仓储层统一与查询下沉 | 1 | 待设计 |
| 3 | 视图组装层独立 | 2 | 待设计 |
| 4 | 服务层清理 | 1,3 | 待设计 |
| 5 | Controller 层收敛 | 3,4 | 待设计 |
| 6 | 文档对齐与测试补齐 | 随各阶段 | 待设计 |

## 5. 详细设计

### 5.1 Token 升级 JWT

#### 接口扩展
```java
public interface TokenService {
    String generateToken(TokenPayload payload);
    TokenPayload verifyAndParse(String token);
}
```
`verifyAndParse` 承担验签 + 过期校验，失败统一抛 `BusinessException(ErrorCode.AUTH_FAILED, 具体消息)`。

#### 实现 `JwtTokenService`（替换 `SimpleTokenService`）
- 依赖：jjwt-api 0.12.6 + jjwt-impl(runtime) + jjwt-jackson(runtime)
- 签名算法：HS256
- 密钥：从配置 `app.security.jwt.secret` 注入（Base64 编码的密钥串），构造时解码为 `SecretKey`
- 载荷：
  - `sub` = userId（字符串）
  - `role` = role.name()
  - `exp` = expiresAt 的 epoch 秒（由 `TokenPayload.expiresAt` 提供）
- `generateToken`：用 `Jwts.builder()` 签发，返回紧凑序列化字符串
- `verifyAndParse`：用 `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` 解析；捕获 `JwtException`（签名错误/过期/格式错）转 `BusinessException(AUTH_FAILED)`：
  - `ExpiredJwtException` → "token has expired"（保持与现有测试期望一致）
  - 其他 `JwtException` → "invalid token"

#### `RequestUserExtractor` 重构
- 构造注入 `TokenService`
- `requireCurrentUser(request)` / `tryExtract(request)`：取 `Authorization: Bearer <token>` 头 → 调 `tokenService.verifyAndParse(token)` → 转 `CurrentUser(userId, role)`
- 删除自写 Base64 解码 + split 逻辑（现有 `parseToken` 方法整体移除）
- token 缺失：`requireCurrentUser` 抛 `AUTH_FAILED("missing bearer token")`；`tryExtract` 返回 `null`（保持现有行为）

#### `TokenPayload`
保持 record `(Long userId, UserRole role, Instant expiresAt)` 不变。

#### 删除
- `SimpleTokenService.java` 整体删除
- 现有 `@Component` 注册由 `JwtTokenService` 取代

### 5.2 异常体系

#### `ErrorCode` 扩充
新增一枚兜底错误码：
```java
INTERNAL_ERROR(5000, HttpStatus.INTERNAL_SERVER_ERROR);
```
其余 5 枚举（`AUTH_FAILED` 1001 / `VALIDATION_FAILED` 1002 / `RESOURCE_NOT_FOUND` 1003 / `PERMISSION_DENIED` 1004 / `BUSINESS_CONFLICT` 1005）不变。

#### `GlobalExceptionHandler` 重写兜底
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
    log.error("unexpected exception", exception);
    return ResponseEntity.internalServerError()
        .body(ErrorResponse.from(ErrorCode.INTERNAL_ERROR, "服务异常，请稍后重试", Map.of()));
}
```
- 引入 `org.slf4j.Logger` / `LoggerFactory`
- 不再返回 `exception.getMessage()`
- 不再硬编码 5000，走 `ErrorCode.INTERNAL_ERROR`

#### `ErrorResponse` / `BusinessException`
字段与构造保持不变（前端兼容）：`ErrorResponse(int code, String errorCode, String message, Map details, Map errors)`、`BusinessException(ErrorCode, String message, Map details)`。

### 5.3 common/api 规范
- `ApiResponse<T>(int code, String message, T data)`：不变，`success` → `(0, "OK", data)`
- `PageResponse<T>(List<T> items, int page, int size, long total)`：不变
- 本子项目对 `common/api` 无代码改动，仅确认契约稳定

### 5.4 依赖与配置

#### `backend/pom.xml` 新增
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

#### `backend/src/main/resources/application.properties` 新增
```properties
app.security.jwt.secret=${APP_JWT_SECRET:ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==}
app.security.jwt.expiry-seconds=${APP_JWT_EXPIRY_SECONDS:3600}
```
默认密钥明确标注 dev-only（注释说明），生产环境通过 `APP_JWT_SECRET` 环境变量覆盖。

#### `application-local.properties`（gitignore）
可选写入 `APP_JWT_SECRET=<自定义密钥>`。

### 5.5 测试策略

#### 改造
- `RequestUserExtractorTest`：
  - 将 `new SimpleTokenService()` 改为 `new JwtTokenService(密钥)`
  - 保留"生成→解析往返""过期拒绝"两个用例的断言语义
  - 过期用例的期望消息保持 `"token has expired"`

#### 新增
- `JwtTokenServiceTest`：
  - 正常签发 → 验签往返：userId/role 一致
  - 过期 token：抛 `AUTH_FAILED("token has expired")`
  - 篡改签名的 token：抛 `AUTH_FAILED("invalid token")`
  - 密钥不匹配：抛 `AUTH_FAILED("invalid token")`
  - 格式错误的 token（非 JWT）：抛 `AUTH_FAILED("invalid token")`

#### 适配（已核实源码引用）
- `AuthApplicationServiceImplTest`：已确认 line 46 直接 `new SimpleTokenService()`，需改为 `new JwtTokenService(密钥)`
- `FrontendIntegrationFlowTest`：经核实不直接引用 `SimpleTokenService`，通过 `AuthApplicationService.login` 获取 token（login 内部依赖 `TokenService` 接口，Spring 自动注入 `JwtTokenService` bean），无需改
- `AuthApplicationServiceImpl`（main）：依赖 `TokenService` 接口，不直接引用 `SimpleTokenService`，无需改

#### 验收
- `./mvnw test` 全绿，测试数量 ≥ 现有 112（新增 `JwtTokenServiceTest` 后增加）

## 6. 影响面与迁移

### 改动文件
| 文件 | 操作 |
|---|---|
| `common/security/TokenService.java` | 扩展接口（加 `verifyAndParse`） |
| `common/security/SimpleTokenService.java` | **删除** |
| `common/security/JwtTokenService.java` | **新增** |
| `common/security/RequestUserExtractor.java` | 重构（注入 TokenService，删自写解析） |
| `common/security/TokenPayload.java` | 不变 |
| `common/security/CurrentUser.java` | 不变 |
| `common/exception/ErrorCode.java` | 加 `INTERNAL_ERROR` |
| `common/exception/GlobalExceptionHandler.java` | 重写兜底 |
| `common/exception/ErrorResponse.java` | 不变 |
| `common/exception/BusinessException.java` | 不变 |
| `common/api/ApiResponse.java` | 不变 |
| `common/api/PageResponse.java` | 不变 |
| `backend/pom.xml` | 加 jjwt 依赖 |
| `backend/src/main/resources/application.properties` | 加 JWT 配置 |

### 测试文件
| 文件 | 操作 |
|---|---|
| `RequestUserExtractorTest.java` | 改造（用 JwtTokenService） |
| `JwtTokenServiceTest.java` | **新增** |
| `AuthApplicationServiceImplTest.java` | 改造（已确认直接 new SimpleTokenService，改用 JwtTokenService） |
| `FrontendIntegrationFlowTest.java` | 无需改（经核实不直接引用 SimpleTokenService，走 login） |

### 不受影响
- 所有业务模块（auth/demand/order/review/notification/recommendation/admin）的领域、DTO、仓储、服务逻辑
- 前端（Bearer 传输方式不变，token 格式变化对前端透明）
- 数据库 schema

## 7. 验收标准

1. `./mvnw clean package` 构建成功
2. `./mvnw test` 全绿，测试数 ≥ 112
3. Token 无法通过篡改 userId/role 伪造（验签拦截）
4. 过期 token 被拒绝，错误码 `AUTH_FAILED`、消息 `"token has expired"`
5. 未知异常返回 `code=5000`、`errorCode=INTERNAL_ERROR`、`message="服务异常，请稍后重试"`，不包含原始异常 message
6. 前端现有登录、鉴权流程不受影响（集成测试验证）
7. `SimpleTokenService` 已删除，无残留引用

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 默认 dev 密钥被误用于生产 | 默认值注释标注 dev-only；README 增加生产配置说明（随子项目6） |
| jjwt 版本与 Java 21 兼容性 | 选用 0.12.6（已验证支持 JDK 17+，Java 21 兼容） |
| 集成测试因 token 格式变化失败 | 集成测试优先通过 `AuthApplicationService.login` 获取真实 token；直接签发处统一改用 `JwtTokenService` |
| 前端缓存的旧 Base64 token 失效 | 用户需重新登录；属预期行为，非缺陷 |

## 9. 后续

本子项目完成后，按分解顺序进入：
- 子项目2：仓储层统一与查询下沉
- 子项目3：视图组装层独立
- 子项目4：服务层清理
- 子项目5：Controller 层收敛
- 子项目6：文档对齐与测试补齐

每个子项目独立设计、独立 spec、独立验收。
