## AI 代码信任度实验报告

**实验名称：** AI 协助实现前端登录/注册错误展示（字段级与全局）

**时间：** 2026-06-01

**作者：** 团队 + AI 辅助（本次由助理生成并由人工审查合并）

---

**一、选择的功能点**
- 登录 / 注册流程的错误显示：按后端返回的错误信息在前端显示字段级（如学号/验证码/密码）和全局错误文案；严格遵守“不修改后端”的约束。

**二、实验 Prompt（用于让 AI 实现前端改动）**

```
请在前端 `AuthView.vue` 中实现：当后端返回登录或注册失败时，前端应
- 从请求抛出的错误中读取原始后端 payload（如 payload.errors / payload.fieldErrors / message）
- 使用现有的 `translateApiError` 将后端消息翻译为中文
- 支持字段级错误展示（在对应输入下显示），并在表单顶部展示全局错误
- 不修改后端逻辑，仅修改前端代码
请生成所需的改动列表和示例代码片段。
```

**三、AI 直出内容摘要（改动概览）**
- 在 `frontend/src/stores/campusHub.ts` 的 `requestJson` 中，抛出的 `Error` 附带原始 `payload`（`err.payload`），以便视图层读取字段级错误。
- 在 `frontend/src/views/AuthView.vue` 中：
  - 新增 `loginFieldErrors` / `registerFieldErrors` 用于字段级错误存储。
  - 在 `submitLogin` / `submitRegister` 的 catch 分支中，优先从 `err.payload` 提取 `errors`（数组）或 `fieldErrors`（对象），用 `translateApiError` 翻译后赋值到字段错误对象；若无字段错误则显示全局错误。
  - 在模板中每个输入下方渲染对应字段错误文本。

修改过的文件（主要）：
- `frontend/src/stores/campusHub.ts`
- `frontend/src/views/AuthView.vue`

**四、编译与运行验证**
- 操作：在 `frontend` 运行 `npm run build`（包含 `vue-tsc -b`）
- 结果：初次构建出现 TypeScript 报错（因动态字段索引导致 TS7053），人工在 `AuthView.vue` 中对动态赋值使用 `as any` 断言并重新构建；最终成功。

构建日志要点：
- 初次错误：Element implicitly has an 'any' type because expression of type 'any' can't be used to index type
- 修复后：构建通过（`✓ built in 439ms`）

**五、人工审查（发现的问题）**
1. 动态字段赋值的 TypeScript 类型错误（TS7053）：AI 代码直接使用 `obj[field] = ...`，在严格类型下报错。
2. 初版未把后端原始 payload 附加到抛出的 Error 中，视图层无法读取字段级细节（已由 AI 建议并实现）。
3. 需要约束字段名映射（前后端可能使用不同字段名），人工需确认后端返回的 `field` 名称与前端 `registerFieldErrors` 中的 key 是否一致，必要时添加映射表。
4. AI返回前端的错误映射顺序有误，导致最后出来的报错信息并不具体，而是呈现一个模糊的兜底报错。

**六、修复与改进（人工修改）**
1. 对动态字段赋值使用断言 `(obj as any)[field] = ...` 以解决 TypeScript 报错（短期修复）。
2. 在 `requestJson` 中把 `payload` 附加到抛出的 `Error`（`err.payload = payload`），便于视图层读取后端返回结构并做更细粒度展示。
3. 在 `AuthView.vue` 中清理旧的 field 错误后再赋值，避免展示遗留信息。
4. 调整了`errorHandler.ts`中的代码块顺序，优先匹配具体消息翻译，再回退到通用错误码。

主要补丁位置：
- [frontend/src/stores/campusHub.ts](frontend/src/stores/campusHub.ts)
- [frontend/src/views/AuthView.vue](frontend/src/views/AuthView.vue)
- [frontend/src/utils/errorHandler.ts](frontend/src/utils/errorHandler.ts)

**七、指标表（实验记录）**

| 指标 | AI 直出 | 人工审查修复后 |
|---|---:|---:|
| 编译是否通过 | 否（首次有 TS 错误） | 是（构建通过） |
| 功能是否可运行 | 部分（逻辑存在，但字段错误无法展示或会触发类型问题） | 是（可以在前端展示字段级与全局错误） |
| 自动化测试是否通过 | N/A（未增加自动化前端测试） | N/A |
| 主要问题 / 修复说明 | TS 类型索引问题；未附带 payload | 使用 `as any` 断言修复索引；给 Error 附加 payload；模板增加字段错误显示 |

**八、结论与建议**
- 结论：AI 在完成高层逻辑实现方面效率较高（快速生成改动清单与核心代码），但在静态类型强约束的代码库中会引入类型错误以及一些错误的映射改动；这些通常可以由人工快速修复（如断言或精确类型定义或目测）。
- 建议：
  1. 在引入 AI 代码后，必须运行静态类型检查（`vue-tsc` / `tsc`）并手动修复类型问题。
  2. 对于字段级错误显示，需和后端约定统一的字段名或在前端维护映射表以避免命名不一致造成无法定位错误。把常见后端错误码在 `translateApiError` 中补全可提高 UX 覆盖率。
  3. 若希望降低人工接入成本，可把字段错误的类型声明拓展为通用索引签名（短期）或在 long-term 定义更精确的 error payload 类型并为 `requestJson` 返回特定 Error 子类（更安全）。

**九、实验附件（已应用的补丁/提交）**
- 已修改：
  - `frontend/src/stores/campusHub.ts`（抛出错误附带 `payload`）
  - `frontend/src/views/AuthView.vue`（字段级错误对象、模板显示、catch 分支解析 payload）
  - `frontend/src/utils/errorHandler.ts`（修改了代码块顺序）
