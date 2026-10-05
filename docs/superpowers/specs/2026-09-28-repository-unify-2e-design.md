# 仓储层统一阶段 2E 设计（配置收尾）

- 日期：2026-09-28
- 状态：待评审
- 分支：`refactor/optimization`（2D 已完成，HEAD `394c91f`）
- 范围：main `application.properties` + 1 个测试文件

## 1. 背景
- `application.properties:13` `mybatis.type-aliases-package=com.campushub.backend.entity` 指向**不存在的包**（实体实际分散在 `*.repository.entity`）。因项目无 XML mapper（全 BaseMapper + @TableName 显式映射），此配置**实际未生效也不影响运行**，属配置遗留。
- 2A review 遗留 deferred minor：`AdminApplicationServiceImplTest` 的 `@Autowired notificationApplicationService` 字段未被任何测试方法/helper 引用（旧 setUp 局部变量被提升为字段后失语义，死代码）。

## 2. 目标 / 非目标
### 目标
1. 删除 main `application.properties` 的无效 `mybatis.type-aliases-package` 行。
2. 删除 `AdminApplicationServiceImplTest` 未使用的 `notificationApplicationService` 字段（含 import）。
3. `mvn test` 全绿不回归。

### 非目标
- `UserEntity` 补 `email_verified_at` 列映射（涉及 User domain/Service 层，留给子项目4 服务层清理时一并处理）
- 2B SQL 下推 / 2C N+1（独立阶段）
- 不改 Repository/Service/Controller 业务逻辑

## 3. 约束
- 前端兼容契约：API 行为不变
- 125/125 测试不回归
- 工作分支 `refactor/optimization`
- 不改 Java 业务逻辑（仅删测试死字段 + 删配置行）

## 4. 设计
### 4.1 删 `application.properties` 第 13 行
```properties
mybatis.type-aliases-package=com.campushub.backend.entity   # 删除此行
```
（保留 `mybatis.mapper-locations=classpath:mapper/*.xml`，虽无 XML 但无害且为未来预留）

### 4.2 删 `AdminApplicationServiceImplTest` 死字段
- 删除字段声明 `@Autowired private NotificationApplicationService notificationApplicationService;`
- 删除对应 import
- 不动其他字段/测试方法

## 5. 影响面
| 文件 | 操作 |
|---|---|
| `backend/src/main/resources/application.properties` | 删 1 行 |
| `backend/src/test/java/com/campushub/backend/admin/service/AdminApplicationServiceImplTest.java` | 删 1 字段 + import |

## 6. 验收
1. `mvn test` 全绿 125/125
2. `application.properties` 无 `mybatis.type-aliases-package`
3. `AdminApplicationServiceImplTest` 无 `notificationApplicationService` 字段
4. 无其他改动
