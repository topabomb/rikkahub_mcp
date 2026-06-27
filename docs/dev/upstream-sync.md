# 上游同步检查记录

> 本文档记录对原项目 [RikkaHub](https://github.com/rikkahub/rikkahub) 提交的检查历史，避免重复检查。

---

## 检查点格式

```
### YYYY-MM-DD - 版本号

**检查范围**：commit_hash1..commit_hash2

**有价值提交**：
- [ ] 提交描述（影响范围）

**已同步**：
- 提交描述 → 对应的本地修改

**跳过**：
- 提交描述（原因）
```

---

## 检查记录

### 2026-06-27 - 检查 2.3.2+ 更新（第二批）

> **同步状态：✅ 全部完成（已编译验证 + 单元测试通过）**
>
> 本次检查的 7 个有价值提交均已同步落地，并于 2026-06-27 经完整审查确认实现正确。
> 审查方法：逐一获取上游 commit 完整 diff（`.patch` 原始 URL），与本地实现逐行比对。

**检查范围**：`5b9be301..a6e7a305`（2026-06-18 ~ 2026-06-26，共 21 条提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`a6e7a305`（2026-06-26）
- 上次检查时间：2026-06-20（已同步 2.3.2 的 OCR 修复 / 平板 UI 修复 / 依赖更新）

---

#### 🔴 跳过（与 Fork 精简方向冲突，不建议同步）

| 提交 | 描述 | 跳过原因 |
|------|------|----------|
| `9d046020` | 新增随想AI网关赞助商及提供商推荐 | 赞助商 / 推荐提供商功能，我们已移除赞助体系 |
| `b78c86d7` | 新增推荐提供商 Sheet 入口 | 同上，推荐提供商基础设施的一部分 |
| `98c7aaf6` | 修复推荐 Sheet 缺少展开动画 | 同上，推荐 Sheet 的 UI 修复 |
| `e631a0c6` | add Serper search provider (+168 行) | 新搜索引擎，我们已精简至 4 个，不符合精简方向 |
| `26e1e4ae` | add StepFun TTS provider (+519 行) | 新 TTS Provider，我们已精简至 3 个 |
| `4c8dab68` | 增加 ElevenLabs TTS provider (+303 行) | 同上，新 TTS Provider |

> **结论**：以上 6 条提交全部为新增 Provider / 赞助商功能，与我们"精简 Provider 体系"的 Fork 核心目标直接冲突，**永久跳过**。

---

#### 🟢 建议同步（P0 — 低成本、高价值）

##### 1. `85bb7364` — Constrain quick message button menu width

| 项目 | 内容 |
|------|------|
| 改动量 | 1 文件，+1 / -3 行 |
| 涉及文件 | `ui/components/ai/ChatInput.kt` |
| Fork 影响 | 无 |
| 合并难度 | 极低 |

**变更内容**：`QuickMessageButton` 的 DropdownMenu 宽度约束从 `.widthIn(min = 200.dp).width(IntrinsicSize.Min)` 改为 `.widthIn(min = 200.dp, max = 360.dp)`，避免菜单过宽。

**本地状态**：✅ 已同步。`ChatInput.kt` 已更新为 `widthIn(min = 200.dp, max = 360.dp)`，移除了 `IntrinsicSize` 导入。

---

##### 2. `ded4a5b8` — LaTeX 公式渲染跟随聊天字体大小

| 项目 | 内容 |
|------|------|
| 改动量 | 3 文件，+6 / -4 行 |
| 涉及文件 | `ui/components/richtext/Markdown.kt`、`MarkdownNew.kt`、`MathBlock.kt` |
| Fork 影响 | 无 |
| 合并难度 | 低 |

**变更内容**：`MathBlock` 的字号回退值从硬编码的 `MaterialTheme.typography.bodyLarge.fontSize` 改为 `LocalTextStyle.current.fontSize`，使 LaTeX 公式大小跟随用户设置的聊天字体大小。

**本地状态**：✅ 已同步。`MathBlock.kt` 已改为 `LocalTextStyle.current.fontSize`，`Markdown.kt` 和 `MarkdownNew.kt` 调用处已显式传入 `fontSize`。

---

##### 3. `31c0f000` — 助手上下文消息数限制时显示截断警告

| 项目 | 内容 |
|------|------|
| 改动量 | 7 文件，+15 行 |
| 涉及文件 | `AssistantBasicPage.kt` + 6 个 `strings.xml` |
| Fork 影响 | 无（纯增量） |
| 合并难度 | 低 |

**变更内容**：当 `assistant.contextMessageSize > 0` 时，在 Slider 下方显示警告文本，提示限制上下文可能导致频繁截断并影响提示词缓存。

**本地状态**：✅ 已同步。`AssistantBasicPage.kt` 已添加条件警告 Text，5 个 `strings.xml` 已新增 `assistant_page_context_message_truncation_warning` 字符串。

---

#### 🟡 建议同步（P1 — 中成本、高价值）

##### 4. `244ce35b` — 将最近聊天引用改为按需调用的对话工具

| 项目 | 内容 |
|------|------|
| 改动量 | 13 文件（1 新增 + 12 修改），+201 / -48 行 |
| 涉及文件 | `GenerationHandler.kt`、`GenerationPrompts.kt`、**`tools/ConversationTools.kt`（新增）**、`DataSourceModule.kt`、`ChatService.kt`、`BuiltinToolUIs.kt`、`ToolUI.kt` + 6 个 `strings.xml` |
| Fork 影响 | 低（涉及 DI 注册和工具 UI，需适配包路径） |
| 合并难度 | 中 |

**变更内容**：将 `enableRecentChatsReference` 从**静态注入 system prompt** 改为向 AI 注册 `recent_chats` 和 `conversation_search` 两个工具。核心动机：动态内容注入 system prompt 会破坏 prompt cache，改为工具后 AI 按需调用，缓存命中率大幅提升。

**本地状态**：✅ 已同步。已完成：
1. 新建 `ConversationTools.kt`（包含 `recent_chats` + `conversation_search` 两个 Tool 定义）
2. 移除 `GenerationHandler.kt` 中的静态注入逻辑和 `conversationRepo` 依赖
3. 删除 `GenerationPrompts.kt` 中的 `buildRecentChatsPrompt` 函数
4. 在 `ChatService.kt` 中按 `enableRecentChatsReference` 注册对话工具
5. 在 `BuiltinToolUIs.kt` 添加 `RecentChatsToolUI` 和 `ConversationSearchToolUI`
6. 在 `ToolUI.kt` 注册新工具 UI
7. 更新 `DataSourceModule.kt` 移除 `conversationRepo` 注入
8. 5 个 `strings.xml` 已新增相关字符串并更新描述

---

##### 5. `a6e7a305` — 搜索结果返回图片并在 AI 与展开 Sheet 中展示

| 项目 | 内容 |
|------|------|
| 改动量 | 5 文件，+49 / -2 行 |
| 涉及文件 | `SearchTools.kt`、`BuiltinToolUIs.kt`、`ExaSearchService.kt`、`SearchService.kt`、`TavilySearchService.kt` |
| Fork 影响 | 中（`ExaSearchService.kt` 已在精简中移除） |
| 合并难度 | 中 |

**变更内容**：`SearchResult` 新增 `images` 字段，Tavily（`include_images`）和 Exa 适配返回图片；搜索结果展开 Sheet 新增横向滚动图片缩略图行。

**本地状态**：✅ 已同步。已完成：
1. `SearchService.kt` 新增 `images` 字段到 `SearchResult`
2. `TavilySearchService.kt` 添加 `include_images=true` 参数和图片解析
3. `SearchTools.kt` 更新工具描述引导 AI 嵌入图片
4. `BuiltinToolUIs.kt` 添加横向滚动图片缩略图行
5. **跳过** `ExaSearchService.kt` 的改动（已移除）

---

#### 🟠 已同步（P2 — 高成本，已完成评估并同步）

##### 6. `a8619508` — 新增屏幕使用时间工具并拆分 local tools

| 项目 | 内容 |
|------|------|
| 改动量 | 20+ 文件，+884 / -338 行 |
| 涉及文件 | 删除 `LocalTools.kt`，新增 `tools/local/` 目录 8 个文件 + `ScreenTimeTool.kt`；修改 AndroidManifest、RouteActivity、Assistant.kt、ChatService、BuiltinToolUIs、AssistantLocalToolPage、ContextUtil + 6 个 strings.xml |
| Fork 影响 | 高（大范围重构 + 新权限） |
| 合并难度 | 高 |

**变更内容**：
- **拆分**：将单体 `LocalTools.kt`（326 行）拆分为 `tools/local/` 下 8 个独立文件（每个工具一个文件）
- **新增工具**：`get_screen_time` 查询应用前台使用时长，支持 `begin/end` 自定义区间与 `today/week` 预设，处理 `PACKAGE_USAGE_STATS` 权限引导

**本地状态**：✅ 已同步。已完成：
1. 拆分 `LocalTools.kt` 到 `tools/local/` 目录（`LocalToolOption.kt`、`LocalTools.kt`、`JavascriptTool.kt`、`TimeInfoTool.kt`、`ClipboardTool.kt`、`TextToSpeechTool.kt`、`AskUserTool.kt`）
2. 新建 `ScreenTimeTool.kt` 实现 `get_screen_time` 工具
3. 创建 `UsageStatsUtil.kt` 提供权限检查和设置页跳转
4. 更新 `AppEvent.kt` 添加 `OpenUsageAccessSettings` 事件
5. 更新 `AndroidManifest.xml` 添加 `PACKAGE_USAGE_STATS` 权限
6. 更新 `RouteActivity.kt` 处理事件
7. 在 `BuiltinToolUIs.kt` 添加 `GetScreenTimeToolUI`
8. 在 `ToolUI.kt` 注册新工具 UI
9. 更新 `AssistantLocalToolPage.kt` 添加 ScreenTime 开关（含权限引导）
10. 5 个 `strings.xml` 已新增相关字符串（6 条 × 5 语言 = 30 条）

---

##### 7. `aef1bc40` — 挂载 upload 目录并向 AI 暴露上传文件路径

| 项目 | 内容 |
|------|------|
| 改动量 | 3 文件，+21 / -7 行 |
| 涉及文件 | `DocumentAsPromptTransformer.kt`、`WorkspaceReminderTransformer.kt`、`RepositoryModule.kt` |
| Fork 影响 | 中（涉及 workspace bind mount 机制） |
| 合并难度 | 中 |

**变更内容**：将用户上传目录（`filesDir/upload`）通过 proot 绑定到 workspace 的 `/upload`，便于 AI 在沙箱内直接读取原始上传文件。`DocumentAsPromptTransformer` 改用 `<UploadFile>` 标签并附带 `path` 属性。

**本地状态**：✅ 已同步。已完成：
1. `RepositoryModule.kt` 添加 `WorkspaceBindMount` 将 `upload` 目录挂载到 `/upload`
2. `DocumentAsPromptTransformer.kt` 改用 `<UploadFile>` 标签并附带 `path` 属性，新增 `resolveWorkspacePath` 函数
3. `WorkspaceReminderTransformer.kt` 添加 `/upload` 只读提示

---

## 同步完成状态汇总

> 本次检查（2026-06-27）涉及的所有有价值提交均已同步完成，编译通过、单元测试通过。

### 优先级 P0（已完成同步）

| # | 提交 | 描述 | 改动量 | 状态 |
|---|------|------|--------|------|
| 1 | `85bb7364` | 快捷消息按钮菜单宽度约束 | 1 行 | ✅ |
| 2 | `ded4a5b8` | LaTeX 字体跟随聊天字体大小 | 10 行 / 3 文件 | ✅ |
| 3 | `31c0f000` | 上下文截断警告 | 15 行 + 5 条字符串 | ✅ |

### 优先级 P1（已完成同步）

| # | 提交 | 描述 | 改动量 | 状态 |
|---|------|------|--------|------|
| 4 | `244ce35b` | 最近聊天引用 → 按需工具（⭐ 架构优化） | ~200 行 / 13 文件 | ✅ |
| 5 | `a6e7a305` | 搜索结果返回图片 | ~47 行 / 4 文件 | ✅ |

### 优先级 P2（已完成同步）

| # | 提交 | 描述 | 改动量 | 状态 |
|---|------|------|--------|------|
| 6 | `a8619508` | 屏幕使用时间工具 + local tools 拆分 | ~1200 行 / 20+ 文件 | ✅ |
| 7 | `aef1bc40` | workspace 挂载 upload 目录 | ~28 行 / 3 文件 | ✅ |

### 永久跳过（与 Fork 精简方向冲突）

| 提交 | 描述 | 原因 |
|------|------|------|
| `9d046020` `b78c86d7` `98c7aaf6` | 赞助商 / 推荐提供商 | 已移除赞助体系 |
| `e631a0c6` | Serper 搜索引擎 | 已精简搜索引擎 |
| `26e1e4ae` | StepFun TTS | 已精简 TTS |
| `4c8dab68` | ElevenLabs TTS | 已精简 TTS |

---

## 历史检查记录

### 2026-06-20 - 检查 2.3.2 更新

**检查范围**：`5b9be301..2026-06-19 最新`

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 当前上游：2.3.2（versionCode 165）- 2026-06-19

**已同步**：

- [x] `fix: OCR model requests now include provider advanced custom body/headers` → 已同步（ca7c956e）
- [x] `fix: 平板横竖屏旋转后模态抽屉残留打开无法关闭` → 已同步（1837b9d5）
- [x] `chore: 更新依赖和 baseline prof` → 已同步（fd5b8ab3）

**已跳过**：

- `feat: 支持 Firecrawl 无 API Key 模式` — 已在清理中移除 Firecrawl
- `适配小米 MiMo ASR + 阶跃星辰 Step ASR` — 已在清理中移除 ASR 相关代码
- `适配 aiping.cn 思考参数` — 未使用的 Provider
- `移除开发者页面和AI日志追踪` — 我们保留调试功能

---

## 本地改进（偏离上游）

### 2026-06-27 - screen_time 工具的本地改进

审查 `a8619508` 同步实现时，本地有两处合理偏离上游：

**改进 1：`UsageStatsUtil.kt` 独立文件**
- 上游将 `hasUsageStatsPermission()` 和 `openUsageAccessSettings()` 放在 `ContextUtil.kt`
- 本地拆分到独立的 `utils/UsageStatsUtil.kt`，职责更清晰（使用统计相关功能内聚）

**改进 2：ScreenTime 开关权限引导逻辑**
- 上游 `AssistantLocalToolPage.kt`：无权限时先开启开关再引导授权（开关已开但工具无法使用）
- 本地实现：无权限时不开启开关、引导用户先授权，授权后用户再手动开启（符合用户预期）

**验证**：编译通过 + 单元测试通过（2026-06-27）

---

### 2026-06-20 - 修复废弃 API

**问题**：上游使用了已废弃的 `currentWindowDpSize()` API，编译时会产生警告。

**修复**：
- 移除：`import androidx.compose.material3.adaptive.currentWindowDpSize`
- 添加：`import androidx.compose.ui.platform.LocalDensity` + `import androidx.compose.ui.platform.LocalWindowInfo`
- 替换：使用 `LocalWindowInfo.current.containerSize` + `LocalDensity` 计算窗口尺寸

**文件**：`app/src/main/java/net/weero/measix/pilot/ui/pages/chat/ChatPage.kt`

**影响**：功能逻辑不变，仅 API 调用方式更新

---

## 同步检查频率

建议每 **2 周** 检查一次上游提交，或在以下情况时检查：
- 原项目发布新版本
- 我们遇到已知问题需要上游修复
- 计划大版本更新前

---

*最后更新：2026-06-27*
