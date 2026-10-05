# 仓储层统一阶段 2E 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 删除无效 `mybatis.type-aliases-package` 配置 + 清理 Admin 测试死字段，闭合子项目2 的配置收尾。

**Architecture:** 单 task：删 1 配置行 + 删 1 测试字段，mvn test 验证。

**Spec:** `docs/superpowers/specs/2026-09-28-repository-unify-2e-design.md`

## Global Constraints
- Java 21，Spring Boot 3.5.0，MyBatis-Plus 3.5.7
- 前端兼容契约：API 行为不变
- 125/125 测试不回归
- 工作分支 `refactor/optimization`（HEAD `394c91f`）
- JAVA_HOME 需 JDK 21；bash 禁管道/分号/&&/重定向，Maven 用 `.\mvnw.cmd` + workdir=backend

---

## Task 1: 删无效配置 + Admin 死字段

**Files:**
- Modify: `backend/src/main/resources/application.properties`
- Modify: `backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java`

- [ ] **Step 1: 读 `application.properties`，删除 `mybatis.type-aliases-package=com.campushub.backend.entity` 行**（用 Edit，保留其余行含 `mybatis.mapper-locations`）

- [ ] **Step 2: 读 `AdminApplicationServiceImplTest.java`，删除未使用的 `@Autowired private NotificationApplicationService notificationApplicationService;` 字段 + 其 import**（用 Edit，不动其他字段/方法）

- [ ] **Step 3: 跑全量测试验证**（bash workdir=backend，JAVA_HOME 设 JDK 21，`.\mvnw.cmd test -q`）
Expected: BUILD SUCCESS，125/125 全绿。

- [ ] **Step 4: 提交（两条独立单命令，workdir 仓库根）**
- `git add backend/src/main/resources/application.properties backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java`
- `git commit -m "chore(config): 删除无效 type-aliases-package 配置与 Admin 测试死字段"`

---

## 验收
- [ ] `mvn test` 全绿 125/125
- [ ] `application.properties` 无 `mybatis.type-aliases-package`
- [ ] `AdminApplicationServiceImplTest` 无 `notificationApplicationService` 字段
