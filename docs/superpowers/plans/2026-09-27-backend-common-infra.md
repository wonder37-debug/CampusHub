# 后端公共基础设施重塑实现计划（子项目1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将后端 `common/` 基础设施层 Token 从无签名 Base64 升级为 JWT（HS256 签名验签），异常兜底不再泄露内部细节并统一走 ErrorCode 体系，保持前端兼容契约。

**Architecture:** 扩展现有 `TokenService` 接口为「签发 + 验签」一体，新增 `JwtTokenService`（jjwt 0.12.6 HS256）替换 `SimpleTokenService`；`RequestUserExtractor` 重构为依赖 `TokenService` 仅做 HTTP 头解析；`ErrorCode` 加 `INTERNAL_ERROR`，`GlobalExceptionHandler` 兜底改用 slf4j 记录 + 通用文案。响应字段结构（`ApiResponse`/`ErrorResponse`）保持不变以维持前端兼容。

**Tech Stack:** Java 21、Spring Boot 3.5.0、jjwt 0.12.6、slf4j（Spring Boot 自带）、JUnit 5 + Spring Mock 测试。

**Spec:** `docs/superpowers/specs/2026-09-27-backend-common-infra-design.md`

## Global Constraints

- Java 21，Spring Boot 3.5.0，MyBatis-Plus 3.5.7（本计划不改 ORM）
- 测试数据库 H2（MySQL 兼容模式），生产 MySQL——本计划不涉及 schema 改动
- 前端兼容契约：`ApiResponse{code,message,data}` 与 `ErrorResponse{code,errorCode,message,details,errors}` 字段不变
- jjwt 版本固定 0.12.6
- 现有测试全绿 + 补齐 JWT/异常兜底测试
- 工作分支 `refactor/optimization`
- 构建验证命令：`cd backend && ./mvnw clean package`；测试命令：`cd backend && ./mvnw test`

---

## File Structure

| 文件 | 操作 | 责任 |
|---|---|---|
| `backend/pom.xml` | 修改 | 加 jjwt 三依赖 |
| `backend/src/main/resources/application.properties` | 修改 | 加 JWT 密钥与过期配置 |
| `backend/src/main/java/com/campushub/backend/common/exception/ErrorCode.java` | 修改 | 加 `INTERNAL_ERROR` 枚举 |
| `backend/src/main/java/com/campushub/backend/common/exception/GlobalExceptionHandler.java` | 修改 | 引入 slf4j，兜底不泄露 |
| `backend/src/main/java/com/campushub/backend/common/security/TokenService.java` | 修改 | 接口加 `verifyAndParse` |
| `backend/src/main/java/com/campushub/backend/common/security/JwtTokenService.java` | 新建 | JWT 签发 + 验签实现 |
| `backend/src/main/java/com/campushub/backend/common/security/SimpleTokenService.java` | 删除 | 被 JwtTokenService 取代 |
| `backend/src/main/java/com/campushub/backend/common/security/RequestUserExtractor.java` | 修改 | 注入 TokenService，删自写解析 |
| `backend/src/test/java/com/campushub/backend/common/security/JwtTokenServiceTest.java` | 新建 | TDD 驱动 JWT 实现 |
| `backend/src/test/java/com/campushub/backend/common/security/RequestUserExtractorTest.java` | 修改 | 改用 JwtTokenService |
| `backend/src/test/java/com/campushub/backend/auth/service/AuthApplicationServiceImplTest.java` | 修改 | `new SimpleTokenService()` → `new JwtTokenService(...)` |
| `backend/src/test/java/com/campushub/backend/common/exception/GlobalExceptionHandlerTest.java` | 新建 | TDD 驱动兜底不泄露 |

---

## Task 1: 引入 jjwt 依赖与 JWT 配置

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.properties`

**Interfaces:**
- Consumes: 无
- Produces: jjwt 依赖可用于后续 task；配置项 `app.security.jwt.secret` / `app.security.jwt.expiry-seconds`

- [ ] **Step 1: 在 `backend/pom.xml` 的 `<dependencies>` 末尾（`h2` 依赖之后、`</dependencies>` 之前）追加 jjwt 三依赖**

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

- [ ] **Step 2: 在 `backend/src/main/resources/application.properties` 末尾追加 JWT 配置**

```properties

# JWT 认证配置（默认密钥仅用于本地开发，生产须通过 APP_JWT_SECRET 覆盖）
app.security.jwt.secret=${APP_JWT_SECRET:ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==}
app.security.jwt.expiry-seconds=${APP_JWT_EXPIRY_SECONDS:3600}
```

- [ ] **Step 3: 验证依赖可解析、工程可编译**

Run: `cd backend && ./mvnw clean compile -q`
Expected: BUILD SUCCESS，无依赖解析错误。

- [ ] **Step 4: 提交**

```bash
git add backend/pom.xml backend/src/main/resources/application.properties
git commit -m "chore(backend): 引入 jjwt 0.12.6 依赖与 JWT 配置占位"
```

---

## Task 2: 异常体系 — ErrorCode 加 INTERNAL_ERROR 与兜底不泄露

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/common/exception/ErrorCode.java`
- Modify: `backend/src/main/java/com/campushub/backend/common/exception/GlobalExceptionHandler.java`
- Test (create): `backend/src/test/java/com/campushub/backend/common/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Task 1 无关，独立
- Produces: `ErrorCode.INTERNAL_ERROR` 枚举常量；`GlobalExceptionHandler` 兜底返回 `ErrorResponse.from(ErrorCode.INTERNAL_ERROR, "服务异常，请稍后重试", Map.of())` 且不包含原始异常 message

- [ ] **Step 1: 写失败测试 `GlobalExceptionHandlerTest`（验证兜底不泄露原始 message）**

```java
package com.campushub.backend.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnInternalErrorForUnexpectedExceptionWithoutLeakingMessage() {
        String secretDetail = "java.lang.NullPointerException at com.example.Secret.internal(Secret.java:42)";
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(new RuntimeException(secretDetail));

        assertEquals(500, response.getStatusCode().value());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(5000, body.code());
        assertEquals("INTERNAL_ERROR", body.errorCode());
        assertEquals("服务异常，请稍后重试", body.message());
        // 关键：原始异常细节不得出现在响应里
        assertNotEquals(secretDetail, body.message());
    }

    @Test
    void shouldMapBusinessExceptionToItsErrorCode() {
        BusinessException exception = new BusinessException(ErrorCode.VALIDATION_FAILED, "title length must be between 3 and 200");
        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(1002, body.code());
        assertEquals("VALIDATION_FAILED", body.errorCode());
        assertEquals("title length must be between 3 and 200", body.message());
    }
}
```

- [ ] **Step 2: 跑测试验证失败（编译失败：ErrorCode.INTERNAL_ERROR 不存在）**

Run: `cd backend && ./mvnw test -Dtest=GlobalExceptionHandlerTest -q`
Expected: 编译失败，`cannot find symbol: variable INTERNAL_ERROR`。

- [ ] **Step 3: 在 `ErrorCode.java` 枚举里追加 `INTERNAL_ERROR`**

在 `BUSINESS_CONFLICT(1005, HttpStatus.CONFLICT);` 之后改为：

```java
    BUSINESS_CONFLICT(1005, HttpStatus.CONFLICT),
    INTERNAL_ERROR(5000, HttpStatus.INTERNAL_SERVER_ERROR);
```

- [ ] **Step 4: 重写 `GlobalExceptionHandler.java`（引入 slf4j，兜底不泄露）**

```java
package com.campushub.backend.common.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
            .body(ErrorResponse.from(errorCode, exception.getMessage(), exception.getDetails()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("unexpected exception", exception);
        return ResponseEntity.internalServerError()
            .body(ErrorResponse.from(ErrorCode.INTERNAL_ERROR, "服务异常，请稍后重试", Map.of()));
    }
}
```

- [ ] **Step 5: 跑测试验证通过**

Run: `cd backend && ./mvnw test -Dtest=GlobalExceptionHandlerTest -q`
Expected: PASS，2 个用例全绿。

- [ ] **Step 6: 跑全量测试确认无回归**

Run: `cd backend && ./mvnw test -q`
Expected: BUILD SUCCESS，所有测试通过（含原有 112 + 新增 2）。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/campushub/backend/common/exception/ErrorCode.java backend/src/main/java/com/campushub/backend/common/exception/GlobalExceptionHandler.java backend/src/test/java/com/campushub/backend/common/exception/GlobalExceptionHandlerTest.java
git commit -m "refactor(exception): ErrorCode 加 INTERNAL_ERROR，兜底异常不泄露内部细节"
```

---

## Task 3: Token 体系替换 — JWT 签发与验签

**Files:**
- Modify: `backend/src/main/java/com/campushub/backend/common/security/TokenService.java`
- Create: `backend/src/main/java/com/campushub/backend/common/security/JwtTokenService.java`
- Modify: `backend/src/main/java/com/campushub/backend/common/security/RequestUserExtractor.java`
- Delete: `backend/src/main/java/com/campushub/backend/common/security/SimpleTokenService.java`
- Test (create): `backend/src/test/java/com/campushub/backend/common/security/JwtTokenServiceTest.java`
- Modify: `backend/src/test/java/com/campushub/backend/common/security/RequestUserExtractorTest.java`
- Modify: `backend/src/test/java/com/campushub/backend/auth/service/AuthApplicationServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 的 jjwt 依赖与 `app.security.jwt.secret` 配置；Task 2 的 `ErrorCode.AUTH_FAILED`
- Produces: `TokenService.verifyAndParse(String)` 方法；`JwtTokenService` 作为唯一 `TokenService` `@Component` bean

- [ ] **Step 1: 写失败测试 `JwtTokenServiceTest`（驱动 JWT 实现核心行为）**

```java
package com.campushub.backend.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    // 测试用密钥（Base64 解码后 ≥ 32 字节，满足 HS256）
    private static final String SECRET = "ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==";
    private final JwtTokenService tokenService = new JwtTokenService(SECRET);

    @Test
    void shouldRoundTripGenerateAndVerify() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String token = tokenService.generateToken(new TokenPayload(42L, UserRole.USER, expiresAt));

        TokenPayload parsed = tokenService.verifyAndParse(token);

        assertEquals(42L, parsed.userId());
        assertEquals(UserRole.USER, parsed.role());
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().minusSeconds(60)));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.verifyAndParse(token));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("token has expired", ex.getMessage());
    }

    @Test
    void shouldRejectTamperedSignature() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)));
        // 破坏最后一个字符制造签名错误
        String tampered = token.substring(0, token.length() - 2) + "AA";

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.verifyAndParse(tampered));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("invalid token", ex.getMessage());
    }

    @Test
    void shouldRejectTokenSignedByDifferentKey() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)));
        byte[] otherKeyBytes = "0123456789012345678901234567890123456789012345678901234567890123".getBytes(StandardCharsets.UTF_8);
        // JwtTokenService(byte[]) 为包级构造，同包测试可直接访问
        JwtTokenService otherService = new JwtTokenService(otherKeyBytes);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> otherService.verifyAndParse(token));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("invalid token", ex.getMessage());
    }

    @Test
    void shouldRejectMalformedToken() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> tokenService.verifyAndParse("not-a-jwt"));
        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
        assertEquals("invalid token", ex.getMessage());
    }
}
```

> 说明：为支持「密钥不匹配」测试，`JwtTokenService` 需提供一个接收原始 `byte[]` 的包级构造函数（见 Step 5）。测试类与 `JwtTokenService` 同包，可直接访问该包级构造，无需额外内部类。

- [ ] **Step 2: 跑测试验证失败（编译失败：JwtTokenService 不存在）**

Run: `cd backend && ./mvnw test -Dtest=JwtTokenServiceTest -q`
Expected: 编译失败，`cannot find symbol: class JwtTokenService`。

- [ ] **Step 3: 扩展 `TokenService` 接口，加 `verifyAndParse` 方法**

```java
package com.campushub.backend.common.security;

public interface TokenService {

    String generateToken(TokenPayload payload);

    /**
     * 验签并解析 token，校验签名与过期时间。
     *
     * @param token 紧凑序列化的 JWT
     * @return 解析出的载荷
     * @throws com.campushub.backend.common.exception.BusinessException
     *     {@link com.campushub.backend.common.exception.ErrorCode#AUTH_FAILED}
     *     当签名无效、token 过期或格式错误时抛出
     */
    TokenPayload verifyAndParse(String token);
}
```

- [ ] **Step 4: 给 `SimpleTokenService` 加过渡实现，保证 main 编译通过（此步后 SimpleTokenService 仍唯一 bean，集成测试不回归）**

在 `SimpleTokenService.java` 内补一个 `verifyAndParse` 方法：

```java
    @Override
    public TokenPayload verifyAndParse(String token) {
        throw new UnsupportedOperationException("pending migration to JwtTokenService");
    }
```

- [ ] **Step 5: 实现 `JwtTokenService`（新建文件，完整 JWT 签发与验签）**

```java
package com.campushub.backend.common.security;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService implements TokenService {

    private final SecretKey key;

    /** Spring 注入构造：从配置读取 Base64 编码的密钥串。 */
    public JwtTokenService(@Value("${app.security.jwt.secret}") String base64Secret) {
        this(io.jsonwebtoken.io.Decoders.BASE64.decode(base64Secret));
    }

    /** 测试用构造：直接传原始密钥字节（用于密钥不匹配等场景）。 */
    JwtTokenService(byte[] rawKey) {
        this.key = Keys.hmacShaKeyFor(rawKey);
    }

    @Override
    public String generateToken(TokenPayload payload) {
        return Jwts.builder()
            .subject(String.valueOf(payload.userId()))
            .claim("role", payload.role().name())
            .expiration(Date.from(payload.expiresAt()))
            .signWith(key)
            .compact();
    }

    @Override
    public TokenPayload verifyAndParse(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            UserRole role = UserRole.valueOf(claims.get("role", String.class));
            Instant expiresAt = claims.getExpiration().toInstant();
            return new TokenPayload(userId, role, expiresAt);
        } catch (ExpiredJwtException exception) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "token has expired");
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "invalid token");
        }
    }
}
```

> 注意：`JwtTokenService` 带 `@Component` 后，与仍带 `@Component` 的 `SimpleTokenService` 会产生两个 `TokenService` bean，导致 Spring 启动冲突。因此必须在 Step 6 的原子切换中删除 `SimpleTokenService`，不可在 Step 5 与 Step 6 之间运行任何集成测试（`@SpringBootTest`）。

- [ ] **Step 6: 跑 `JwtTokenServiceTest` 验证通过**

Run: `cd backend && ./mvnw test -Dtest=JwtTokenServiceTest -q`
Expected: PASS，5 个用例全绿。

- [ ] **Step 7: 原子切换 — 删除 SimpleTokenService + 重构 RequestUserExtractor + 适配两个测试**

此 step 一次性完成 `TokenService` bean 从 `SimpleTokenService` 到 `JwtTokenService` 的切换，避免中间态出现「两个 `@Component` 实现冲突」或「缺 bean」。

7a. **删除** `backend/src/main/java/com/campushub/backend/common/security/SimpleTokenService.java`（整个文件）。

7b. **重写** `RequestUserExtractor.java`：

```java
package com.campushub.backend.common.security;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestUserExtractor {

    private final TokenService tokenService;

    public RequestUserExtractor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public CurrentUser requireCurrentUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_FAILED, "missing bearer token");
        }
        TokenPayload payload = tokenService.verifyAndParse(authorization.substring("Bearer ".length()).trim());
        return new CurrentUser(payload.userId(), payload.role());
    }

    public CurrentUser tryExtract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            TokenPayload payload = tokenService.verifyAndParse(authorization.substring("Bearer ".length()).trim());
            return new CurrentUser(payload.userId(), payload.role());
        } catch (BusinessException exception) {
            return null;
        }
    }
}
```

> 注意 `tryExtract` 行为变化：原实现对无效 token 抛异常（在 list 等公开接口会 500）。新实现 `tryExtract` 对无效 token 返回 `null`（视为未登录），更符合「尝试提取」语义且让公开列表接口对无效 token 容错。`requireCurrentUser` 仍对无效 token 抛 `AUTH_FAILED`。此行为变化需在 Step 8 的集成测试验证不破坏前端。

7c. **重写** `RequestUserExtractorTest.java`（改用 `JwtTokenService`）：

```java
package com.campushub.backend.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestUserExtractorTest {

    private static final String SECRET = "ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw==";
    private final JwtTokenService tokenService = new JwtTokenService(SECRET);
    private final RequestUserExtractor requestUserExtractor = new RequestUserExtractor(tokenService);

    @Test
    void shouldExtractCurrentUserFromGeneratedToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().plusSeconds(3600)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        CurrentUser currentUser = requestUserExtractor.requireCurrentUser(request);

        assertEquals(42L, currentUser.userId());
        assertEquals(UserRole.USER, currentUser.role());
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = tokenService.generateToken(
            new TokenPayload(42L, UserRole.USER, Instant.now().minusSeconds(60)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> requestUserExtractor.requireCurrentUser(request)
        );

        assertEquals(ErrorCode.AUTH_FAILED, exception.getErrorCode());
        assertEquals("token has expired", exception.getMessage());
    }

    @Test
    void shouldReturnNullWhenBearerMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(null, requestUserExtractor.tryExtract(request));
    }

    @Test
    void shouldReturnNullForInvalidTokenInTryExtract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-jwt");
        assertEquals(null, requestUserExtractor.tryExtract(request));
    }
}
```

7d. **修改** `AuthApplicationServiceImplTest.java` 第 22 行 import 与第 46 行构造：

将
```java
import com.campushub.backend.common.security.SimpleTokenService;
```
改为
```java
import com.campushub.backend.common.security.JwtTokenService;
```

将第 46 行
```java
            new SimpleTokenService(),
```
改为
```java
            new JwtTokenService("ZGV2LWNhbXB1c2h1Yi1qd3Qtc2VjcmV0LWtleS1mb3ItZGV2ZWxvcG1lbnQtb25seS0yNTYtYml0cw=="),
```

- [ ] **Step 8: 跑全量测试验证全绿（单元 + Spring 集成）**

Run: `cd backend && ./mvnw test -q`
Expected: BUILD SUCCESS，所有测试通过。重点关注：
- `JwtTokenServiceTest` 5 个用例
- `RequestUserExtractorTest` 4 个用例
- `AuthApplicationServiceImplTest` 全部用例
- `FrontendIntegrationFlowTest`（验证 Spring 单 `TokenService` bean 注入正常、登录拿 token 流程不受影响）

若 `FrontendIntegrationFlowTest` 因 token 行为变化失败，检查是否依赖了 `tryExtract` 对无效 token 抛异常的旧行为（新行为返回 null）。

- [ ] **Step 9: 提交**

```bash
git add backend/src/main/java/com/campushub/backend/common/security/TokenService.java backend/src/main/java/com/campushub/backend/common/security/JwtTokenService.java backend/src/main/java/com/campushub/backend/common/security/RequestUserExtractor.java backend/src/test/java/com/campushub/backend/common/security/JwtTokenServiceTest.java backend/src/test/java/com/campushub/backend/common/security/RequestUserExtractorTest.java backend/src/test/java/com/campushub/backend/auth/service/AuthApplicationServiceImplTest.java
git rm backend/src/main/java/com/campushub/backend/common/security/SimpleTokenService.java
git commit -m "refactor(security): Token 升级 JWT(HS256 签名验签)，删除 SimpleTokenService"
```

---

## 验收清单（全部 task 完成后核对）

- [ ] `cd backend && ./mvnw clean package` 构建成功
- [ ] `cd backend && ./mvnw test` 全绿，测试数 ≥ 112（新增 `JwtTokenServiceTest` 5 + `GlobalExceptionHandlerTest` 2 + `RequestUserExtractorTest` 新增 2 = ≥ 121）
- [ ] `SimpleTokenService.java` 已删除，`grep -r SimpleTokenService backend/src` 无结果
- [ ] 篡改 userId/role 的 token 被 `verifyAndParse` 拒绝（`AUTH_FAILED`）
- [ ] 过期 token 返回 `code=1001`、`errorCode=AUTH_FAILED`、`message="token has expired"`
- [ ] 未知异常返回 `code=5000`、`errorCode=INTERNAL_ERROR`、`message="服务异常，请稍后重试"`，不含原始异常 message
- [ ] `ApiResponse` / `ErrorResponse` 字段结构未变（前端兼容）
