﻿# 上游同步检查记录

> 本文档记录对原项目 [RikkaHub](https://github.com/rikkahub/rikkahub) 提交的检查历史，避免重复检查。
> 每批同步的完整分析见 `docs/dev/upstream-sync/` 子目录下的详细文档。

---

## 工作方法与同步原则

### Git 远程仓库配置

本地仓库配置了两个 remote：

| remote | URL | 用途 |
|--------|-----|------|
| `origin` | `https://github.com/topabomb/rikkahub_mcp` | 我们的 fork，日常开发推送 |
| `upstream` | `https://github.com/rikkahub/rikkahub.git` | 原项目，只读拉取用于同步检查 |

### 工作方法

1. **拉取上游提交**：`git fetch upstream` 获取上游最新提交到本地 `upstream/master` 分支（不 merge、不 rebase，仅 fetch）
2. **确定检查范围**：从上次检查的最后一个 commit hash 到 `upstream/master`，用 `git log --oneline <last_hash>..upstream/master` 列出待检查的提交；完成后把范围终点冻结为本次实际检查的 commit hash，不在完成记录中保留漂移的 `upstream/master`
3. **逐提交精确分析**：`git show <hash> --stat` 查看影响范围，`git show <hash> -- <path>` 逐文件查看完整 diff
4. **本地核对**：打开本地对应文件逐行对比，确认是否存在同样问题或是否已有对应实现
5. **确认最终态**：对涉及多次修复的文件（如后续提交修正前序提交），以最后一次提交的最终代码为准合并改动
6. **包名映射**：上游 `me.rerere.rikkahub` → 本地 `net.weero.measix.pilot`，DeepLink scheme `rikkahub://` → `measix://`，核对时忽略此差异
7. **连续性校验**：用 `git rev-list --count <start>..<end>` 校验提交数，并确认本批起点等于上批终点；若上游重写历史，记录旧/新 hash 映射后再继续

### 同步判断原则

| 判定 | 条件 |
|------|------|
| **引入** | bug 修复（本地确认存在同样问题）/ 本地工具新增或优化 / 架构改进无破坏性 / 针对性能的优化 / 新的模型的适配 |
| **必要同步** | **本地工具（local tools）的优化和新增**——作为移动端 Pilot 能力增强的必要项 / 界面或用户体验优化 / MCP相关功能或优化 / 新的模型适配 |
| **引入** | **安全修复**：涉及权限提升、敏感数据处理、注入防护等安全相关的修复优先引入 |
| **按需引入** | **测试用例**：与未引入功能无关的上游测试可跳过；被同步的 bug 修复、新行为和本地适配必须配套引入或补写回归测试 |
| **单独评估** | **依赖更新**：上游的依赖版本升级需评估是否与本地依赖冲突、是否引入 ABI 变化，不盲目跟从 |
| **单独评估** | **新增依赖/API 级别**：上游引入新依赖或提高 minSdk 需确认本地兼容性，不降低适配范围 |
| **单独评估** | **持久化变更**：涉及 Room 数据库 schema 变更（entity 字段增删改、新增表）、DataStore/SharedPreferences 配置结构变更、`@Serializable` data class 结构变更等落盘数据格式的改动，需评估迁移影响并手写 Migration，不盲目引入 |
| **跳过** | 与 Fork 精简方向直接冲突（新 Provider / 赞助商 / 新搜索引擎 / 新 TTS / web 端的变更） |
| **跳过** | 版本号升级（我们版本线独立）/ 对历史版本的兼容（fork的起点之前）  |

### 同步操作约定

- **重复提交合并**：一个功能分多次提交时，合并最终态一次引入，不逐个引入
- **本地偏离保留**：本地有合理偏离上游的改动，需要审查合理性，确定最终的的正确、最佳实现版本
- **多文件改动核对**：每次同步后逐文件与上游最终态核对，确认逻辑一致（仅包名差异）
- **完成门槛**：同步项、许可证项和跳过项之和必须等于范围内提交数；P0/P1 不得以“待后续”状态计入已完成

### 文档结构约定

- **详细分析文档**：每批同步的逐提交 diff 分析、本地代码核对、适配要点等完整记录，存放在 `docs/dev/upstream-sync/` 子目录，命名为 `batch-N-YYYY-MM-DD.md`（N 为批次序号，YYYY-MM-DD 为检查日期）
- **进度摘要**：本文件（`upstream-sync.md`）仅保留每批同步的进度摘要和指向详细文档的链接
- **摘要更新时机**：在整批同步工作**全部完成**后（编译通过 + 逐文件核对一致）才更新摘要状态为 ✅；分析阶段仅记录检查范围和链接，状态标记为"分析完成，待同步"
- **阶段复核文档**：若干批次形成稳定检查点后创建
  `phase-N-review-YYYY-MM-DD.md`，复核累计范围连续性、产品边界、最终代码职责、持久化兼容和全量验证；
  它不替代逐批记录，也不覆盖先前阶段。

### 阶段复核机制

阶段复核用于回答“每批都完成以后，整个 Fork 是否仍然正确”，而不是再次罗列提交。创建时必须：

1. 冻结准确的上游终点和本地版本，校验各批起止点、提交数和分类总数；
2. 从当前完整文件而非单次 diff 复核产品目标、模块职责、数据兼容和实现最终态；
3. 明确已修问题、剩余非阻断风险、测试及构建证据；
4. 建立与架构基线、精简计划、专题设计文档和最后一批详细记录的链接；
5. 后续阶段新增文件，命名序号递增，不改写已冻结阶段的结论。

当前阶段检查点：

- [阶段 1：第 1～9 批，冻结于 `2d61ba95`](upstream-sync/phase-1-review-2026-07-28.md)

### 持久化与迁移影响检查

每次同步前需确认改动是否影响持久化配置或数据：

- **`@Serializable` data class 新增字段**：检查是否有 `@SerialName` 和默认值，确保旧配置可正常反序列化
- **密封类/枚举新增项**：检查 `@SerialName` 确保序列化稳定，旧 `settings.json` 无此字段时默认不启用，无迁移风险
- **Room 数据库 schema 变更**：确认是否涉及 Room entity 变更，如有需新增 Migration
- **`AndroidManifest.xml` 权限/intent 新增**：运行时权限需确认申请时机（如首次开启工具开关时），不影响已安装用户；`<queries>` intent 声明仅影响可见性，无运行时行为变化

---

## 检查点格式

每批检查在 `upstream-sync/` 下创建 `batch-N-YYYY-MM-DD.md` 详细文档，并在本文件「检查记录」中添加摘要条目：

```
### YYYY-MM-DD - 检查 XXX+ 更新（第N批）

> **同步状态：✅ 全部完成 / ⏳ 分析完成，待同步**
>
> 详细分析文档：[batch-N-YYYY-MM-DD.md](upstream-sync/batch-N-YYYY-MM-DD.md)

**检查范围**：`commit1..commit2`（日期范围，共 N 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`hash`（日期）
- 上次检查时间：YYYY-MM-DD（第N-1批，`hash`）

**已同步 / 有价值提交（N 个）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `hash` | 简要描述 | bug 修复 / UI 修复 / 新功能 / … |

**跳过（N 个）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `hash` | 描述 | 跳过原因 |

> 关键改动详述及持久化影响评估见详细文档。
```

- 已完成批次：表格列用 `类别`（bug 修复 / UI 修复 / 新功能 / …）
- 待同步批次：表格列增加 `判定`（✅ 同步 / ⚠️ 按需引入）和 `优先级`（P0 / P1 / P2 / 评估）
- 摘要中不列改动量、文件数等细节，这些属于详细文档

---

## 检查记录

### 2026-08-02 - 检查 3a18dae8+ 更新（第十批，2026-08-04 扩展）

> **同步状态：✅ 全部完成（11 个同步 + 1 个按需引入 + 4 个跳过；单元测试、Lint、Debug/Release 编译及签名校验通过）**
>
> 详细分析文档：[batch-10-2026-08-02.md](upstream-sync/batch-10-2026-08-02.md)

**检查范围**：`3a18dae8..b106e8bb`（2026-07-30 ~ 2026-08-03，共 16 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`b106e8bb`（2026-08-03）
- 上次检查时间：2026-07-30（第九批扩容，`3a18dae8`）

**版本号对应关系**：

| 维度 | 上游 | 本地 | 说明 |
|------|------|------|------|
| 版本名 | 2.4.5 | 0.0.12 | 版本线独立，不跟随上游版本号 |
| versionCode | 172 | 12 | 独立递增 |
| DB 版本 | v24 | v3 | 本地从 v1 重建，无历史 migration 包袱 |

> 本轮同步涉及持久化变更（新增 `defaultTTSPlaybackSpeed` Settings 字段）和 highlight 模块
> 大规模重写（QuickJS + prism.js → 纯 Kotlin，30+ 语言）。已递增本地版本号至 `0.0.12`（versionCode 12）。

**已同步（11 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `d7897022` | Kotlin 原生实现语法高亮（含 WIP `16be94e8`） | 架构改进 |
| 2 | `952fa019` | 声明 legacy storage 权限 | bug 修复 |
| 3 | `12368ace` | TTS 默认播放速度 | 功能增强 |
| 4 | `3b577b6e` | SkillsPage LazyColumn key 修复 | bug 修复（UI） |
| 5 | `dc6e077b` | workspace 支持 HEIC/HEIF/AVIF/ICO 图片 | bug 修复 |
| 6 | `262e8ab0` | Moonshot K2.5/K3 temperature 过滤 | bug 修复 |
| 7 | `e7971589` | 依赖升级（material3 alpha25 / nav3 1.1.5 / baselineprofile beta01） | 依赖升级 |
| 8 | `ba85b8f0` | MCP 请求头值默认遮罩与显示/隐藏 | 隐私保护 / UI |
| 9 | `4268f899` | convention plugin 共享 Android library 构建约束 | 构建系统 |
| 10 | `de3399ba` | 精简 keep rules 并采用 AGP 9 optimization DSL | Release / R8 |
| 11 | `b106e8bb` | 拆分 `UIMessagePart` / `UIMessageAnnotation` 源码文件 | 源码结构整理 |

**按需引入（1 个提交）**：

| 提交 | 描述 | 判定 | 原因 |
|------|------|------|------|
| `26613c10` | 更新 baseline profile | ⚠️ 按需引入 | 需在 highlight 重写同步完成后在目标设备上重新生成 |

**跳过（4 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `16be94e8` | kotlin 原生实现语法高亮(wip) | WIP 提交，被 `d7897022` 最终态完全覆盖 |
| `d2af7b8a` | 修复最近改动挂掉的 JVM 测试 | 本地测试已在 batch 7/8/9 中按本地实现对齐 |
| `5f39f1c1` | bump to 2.4.5 (172) | 版本线独立 |
| `8349ef25` | 上下文限制警告文案改用"无限制"表述 | 本地文案已在 batch 9 按 Fork 语义重写，不引用"0"值，上游修复的问题不存在 |

> 完整分析（逐文件 diff、本地代码核对、**合理性/完整性/价值三维复核**、适配要点、持久化影响评估、highlight 模块重写策略）见详细文档。
>
> **同步执行要点**（2026-08-02）：
> - highlight 模块完全重写为纯 Kotlin 实现，保留本地 `HighlightTextColorPalette` + `buildHighlightText` + `getStyleForTokenType` 于 `HighlightStyle.kt`，兼容自定义 Atom One Dark/Light 配色
> - `CodeColor.kt` 无需修改（`HighlightTextColorPalette` 仍保留）；`HighlightCodeBlock.kt` 移除 `runBlocking`、类型重命名、`HighlightCodeVisualTransformation` 重写（移除死代码 `regex()` companion）；`CodeBlockPreview` 硬编码 URL 改用 `WEB_VIEW_BASE_URL` 常量
> - `RouteActivity.kt` / `Export.kt` 移除 `Highlighter` DI 注入和 `LocalHighlighter` provides（`LocalCodeHighlighter` 有默认实例）
> - `WebViewPage.kt` 使用 `WEB_VIEW_BASE_URL` 常量已在本轮分析期间同步落地
> - golden fixture 测试因 Windows CRLF 行尾失败，修复 `HljsFixtures.kt` 规范化行尾 + 添加 `.gitattributes` 规则
> - `ChatCompletionsAPI.kt` 修正：K2.6 不在 temperature 限制范围内（支持 temperature）
> - `ba85b8f0` 按本地职责适配：请求头值默认遮罩，删除请求头时重映射可见状态，并补齐 5 locale 无障碍文案与 JVM 回归测试
> - `4268f899` 引入薄层 convention plugin，集中 8 个 library 的共同配置；`search` 因 Fork 已无 Compose 代码而使用基础插件
> - `de3399ba` 删除空/模板规则并采用 AGP 9 optimization DSL；仅保留有真机崩溃证据的 R8 strict-full-mode 兼容开关
> - `b106e8bb` 作为第十批直接扩展引入：只拆分 Fork 当前保留的消息类型，不恢复已删除的 `Search`、`ToolCall`、`ToolResult`；类型序列化结构不变

---

### 2026-07-28 - 检查 8eebe950+ 更新（第九批）

> **同步状态：✅ 全部完成（24/24 已同步，3 个按 Fork 边界跳过；范围扩展至 `3a18dae8`）**
>
> 详细分析文档：[batch-9-2026-07-28.md](upstream-sync/batch-9-2026-07-28.md)
>
> 九批阶段复核：[phase-1-review-2026-07-28.md](upstream-sync/phase-1-review-2026-07-28.md)

**检查范围**：`8eebe950..3a18dae8`（2026-07-18 ~ 2026-07-30，共 27 个提交；阶段复核冻结于 `2d61ba95`，`669cc460` 为独立依赖升级，`3a18dae8` 为 4 个扩容提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`3a18dae8`（2026-07-30）
- 上次检查时间：2026-07-17（第八批，`8eebe950`）

**版本号对应关系**：

| 维度 | 上游 | 本地 | 说明 |
|------|------|------|------|
| 版本名 | 2.4.3 | 0.0.11 | 版本线独立，不跟随上游版本号 |
| versionCode | 171 | 11 | 独立递增 |
| DB 版本 | v24 | v3 | 本地从 v1 重建，无历史 migration 包袱 |

> 本轮同步涉及持久化变更（移除旧 `contextMessageSize`、新增默认关闭的
> `contextMessageLimit`）和许可证变更（纯 AGPL-3.0）。第九批目标版本保持
> `0.0.11`（versionCode 11），纳入 `2d61ba95` 后不再次递增。

**许可证变更（历史时刻）**：

上游 `65a63e4e` 将许可证从"分段双重许可（Segmented Dual Licensing）"改为纯 AGPL-3.0。移除了商业用途限制和用户数量门槛，使项目回归纯粹的 copyleft 自由软件精神。本地 LICENSE 文件已同步更新为纯 AGPL-3.0 全文。

**已同步（20 个提交，含 1 个许可证变更）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `fe3037c5` | 图片裁剪失败提示错误而非静默忽略 | bug 修复（UI） |
| 2 | `845a649f` | 升级 material3 至 1.5.0-alpha24 | 依赖升级 |
| 3 | `65a63e4e` | 改为纯 AGPL-3.0 许可证 | 许可证同步 |
| 4 | `fe2e7cdc` | SAF 支持复制/移动文件到 workspace | 功能增强 |
| 5 | `c2e2abda` | 适配 Kimi K3 裸 id "k3" | 模型适配 |
| 6 | `8154e8d1` | 消息模板使用消息发送时间而非当前时间 | bug 修复（cache） |
| 7 | `b0698524` | 移除上下文消息数量限制 | 重构 |
| 8 | `dda1ea42` | 新增极简白和 Claude 主题 + 网格布局 | 新功能 |
| 9 | `1428a4b3` | resolve skills from metadata directories | bug 修复 |
| 10 | `acc7b7c6` | SkillFrontmatterParser 抽离为独立文件 | 重构 |
| 11 | `af53971a` | 修复 Response API 内置工具覆盖自定义函数工具 | bug 修复 |
| 12 | `4801b448` | 月之暗面 K2.6 thinking.keep=all | 模型适配 |
| 13 | `9f7a7854` | cached tokens 按 provider 方言兜底解析 | bug 修复 |
| 14 | `19e74b9a` | 内置 mermaid.min.js 修复首次渲染耗时 | bug 修复（性能） |
| 15 | `1cf6f510` | 图标支持匹配到 k3 模型 id | bug 修复 |
| 16 | `dee7d214` | 解耦本地备份与 WebDAV items | bug 修复 |
| 17 | `353c29e2` | 修复语音服务下拉菜单展开崩溃 | bug 修复（UI 崩溃） |
| 18 | `c3d7dc63` | 文件工具支持读取 bind mount 路径 | bug 修复（workspace） |
| 19 | `2d61ba95` | 上下文限制改为阶梯式裁剪；按本地完整轮次、缓存估算和 UI 语义适配 | 功能增强（cache/context） |
| 20 | `669cc460` | 更新 AGP 和 kotlin 版本 | 依赖升级 |
| 21 | `0a86bf32` | 修复 Grok 生图 size 参数 400 报错 | bug 修复 |
| 22 | `bc4591f0` | 等待会话删除完成后再跳转 | bug 修复 |
| 23 | `786ed33f` | 避免切换助手后新对话归属旧助手 | bug 修复 |
| 24 | `3a18dae8` | 移除 cur_time/cur_datetime 占位符，降级兼容旧配置 | bug 修复（cache） |

**跳过（3 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `82e0ca49` | docs: add gemini and anthropic api skills | AI 编程助手开发文档，非应用功能 |
| `5f3d89a9` | chore: bump to 2.4.3 | 版本线独立 |
| `d16be4a1` | chore: 月之暗面提供商默认开启 | Fork 精简移除了月之暗面默认提供商 |

> 完整分析（逐文件 diff、本地代码核对、适配要点、持久化影响评估、许可证变更详述）见详细文档。

---

### 2026-07-17 - 检查 449ce1e6+ 更新（第八批）

> **同步状态：✅ 全部完成**
>
> 详细分析文档：[batch-8-2026-07-17.md](upstream-sync/batch-8-2026-07-17.md)

**检查范围**：`449ce1e6..8eebe950`（2026-07-10 ~ 2026-07-16，共 24 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`8eebe950`（2026-07-16）
- 上次检查时间：2026-07-09（第七批，`449ce1e6`）

**版本号对应关系**：

| 维度 | 上游 | 本地 | 说明 |
|------|------|------|------|
| 版本名 | 2.4.2 | 0.0.10 | 版本线独立，不跟随上游版本号 |
| versionCode | 170 | 10 | 独立递增 |
| DB 版本 | v24 | v3 | 本地从 v1 重建，无历史 migration 包袱 |

> 本轮同步涉及持久化变更（`enableWebSearch` 从 Settings 迁移到 Assistant）和多个功能增强，已递增本地版本号至 `0.0.10`（versionCode 10）。

**需同步（11 个提交）**：

| # | 提交 | 描述 | 判定 | 优先级 |
|---|------|------|------|--------|
| 1 | `d910c4a1` | WebView 预览缓存到文件避免 TransactionTooLargeException 崩溃 | ✅ 同步 | P0 |
| 2 | `1ff465d1` | CustomJs 搜索 scrape 参数补全 array items 声明 | ✅ 同步 | P0 |
| 3 | `40592c1b` | 工作区恢复备份后目录缺失标记 BROKEN 而非删除记录 | ✅ 同步 | P1 |
| 4 | `01830332` | 网络搜索开关改为每个助手独立存储 | ✅ 同步 | P1 |
| 5 | `027f33bf` | 工作区文件管理支持文本编辑与图片/视频预览 | ✅ 同步 | P1 |
| 6 | `599d4adf` | folder sync 双向同步补录与孤儿清理 | ✅ 同步 | P1 |
| 7 | `e3bd7119` | 支持一键清理聊天文件 | ✅ 同步 | P1 |
| 8 | `dab4e448` | TTS 本地工具描述改为所选 TTS 服务 | ✅ 同步 | P2 |
| 9 | `d37d4ed6` | 优化建议回复默认提示词 | ✅ 同步 | P2 |
| 10 | `0dfb598c` | 补全 TTS 引号描述优化显示 | ✅ 同步 | P2 |
| 11 | `720c9e8e` | 新增 Kimi K3 模型定义 | ✅ 同步 | P2 |

**选择性引入（2 个提交）**：

| 提交 | 描述 | 判定 | 原因 |
|------|------|------|------|
| `0f5b3f3e` | 拆分 McpManager 并暴露完整错误详情 | ✅ 选择性引入 | 不引入架构拆分（保留 Dormant/网络感知/前台恢复）；引入 Error.detail + Error.from() + SettingMcpPage 错误详情展示 + McpConnectionKey + 连接参数变化检测（connectedConfigs + hasSameConnectionParameters）+ 3 个单元测试 |
| `f5f398ef` | 升级依赖版本 | ✅ 引入（跳过 firebase） | okhttp 5.4.0 / coil 3.5.0 / koin 4.2.2 / ktor 3.5.1 / slf4j 2.0.18 / uiautomator 2.4.0 / baselineprofile alpha07；firebase-bom 跳过（本地无）；slf4j-android 保持 2.0.17-0 |

**跳过（11 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `9d337938` | 测试修复 | 已同步或不适用（ElevenLabs/FishAudio 不存在） |
| `3c1a2de6` | FGS 权限闪退修复 | 本地无 WebServerService |
| `cf55cbbb` | CI Issue 自动关闭 | CI 配置 |
| `9de57dc8` | 赞助商链接 | 赞助商 README |
| `7c3b15d9` | 赞助商 | 赞助商 README |
| `acb3b883` | 赞助商 LOGO | 赞助商 README |
| `8bde99a8` | README fork 警告 | README 文档 |
| `b9285fbb` | star history | README 文档 |
| `de4f1579` | 移除 Firebase Remote Config | 本地无 Firebase |
| `729e990b` | Exa 网页内容抓取 | 本地无 Exa 搜索引擎 |
| `8eebe950` | 发布 2.4.2 | 版本线独立 |

> 完整分析（逐文件 diff、本地代码核对、适配要点、持久化影响评估）见详细文档。

---

### 2026-07-08 - 检查 d7f0ef26+ 更新（第七批）

> **同步状态：✅ 全部完成（编译通过 + 单元测试通过 + 逐文件核对一致）**
>
> 详细分析文档：[batch-7-2026-07-08.md](upstream-sync/batch-7-2026-07-08.md)

**检查范围**：`d7f0ef26..449ce1e6`（2026-07-08 ~ 2026-07-09，共 12 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`449ce1e6`（2026-07-09）
- 上次检查时间：2026-07-08（第六批最终检查点，`d7f0ef26`）

**版本号对应关系**：

| 维度 | 上游 | 本地 | 说明 |
|------|------|------|------|
| 版本名 | 2.4.1 | 0.0.9 | 版本线独立，不跟随上游版本号 |
| versionCode | 169 | 9 | 独立递增 |
| DB 版本 | v24 | v3 | 本地从 v1 重建，无历史 migration 包袱 |

> 本批同步不递增本地版本号（`versionCode`/`versionName` 保持 9/0.0.9），改动以 bug 修复和功能增强为主。

**已同步（7 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `7d88bbc3` | 流式丢字修复 + replaceRegexes 缓存 + 通知拆分到 ChatNotificationManager | bug 修复 + 重构 |
| 2 | `8477334d` | MCP SSE 通知流重试耗尽不再触发整体重连 | bug 修复 |
| 3 | `55c2cf71` | 聊天头部模型名支持两行显示 | UI 修复 |
| 4 | `8e872e5f` | 聊天输入框键盘收起时增加底部呼吸间距 | UI 修复 |
| 5 | `6a35f89e` | TTS 语气标记引导 + MiMo 接入 | 功能增强 |
| 6 | `b6b16842` | TTS 不朗读括号内的内容，与引号过滤叠加生效 | 功能增强 |
| 7 | `449ce1e6` | removeBracketedContent 单元测试 + TTS 括号设置本地化 | 测试 + 本地化 |

**跳过（5 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `45191d27` | MiniMax 移除 emotion 参数 | 本地无 MiniMax TTS |
| `c9394d94` | CI 每日构建替换手动 Release | CI 配置，不适用 |
| `6ce74f71` | CI 每日构建拉取 submodule | CI 配置，不适用 |
| `feeed5dc` | CI 每日构建 Prerelease | CI 配置，不适用 |
| `baf72a21` | 版本升级 2.4.1 | 版本线独立，不适用 |

> 完整分析（逐文件 diff、本地代码核对、适配要点、持久化影响评估）见详细文档。

---

### 2026-07-08 - 检查 ef564dca+ 更新（第六批）

> **同步状态：✅ 全部完成（编译通过 + 单元测试通过 + 逐文件核对一致）**
>
> 详细分析文档：[batch-6-2026-07-08.md](upstream-sync/batch-6-2026-07-08.md)

**检查范围**：`ef564dca..d7f0ef26`（2026-07-06 ~ 2026-07-08，共 23 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`d7f0ef26`（2026-07-08）
- 上次检查时间：2026-07-06（第五批，`ef564dca`）

**版本号对应关系**：

| 维度 | 上游 | 本地 | 说明 |
|------|------|------|------|
| 版本名 | 2.4.0 | 0.0.9 | 版本线独立，不跟随上游版本号 |
| versionCode | 168 | 9 | 独立递增 |
| DB 版本 | v24 | v3 | 本地从 v1 重建，无历史 migration 包袱 |

> 本批同步不递增本地版本号（`versionCode`/`versionName` 保持 9/0.0.9），改动以补丁级修复和重构为主。

**已同步（17 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `4d2276d0` | mhchem.mjs 导入方式修复 | bug 修复 |
| 2 | `0df1939c` | mhchem \ce 化学公式渲染修复 | bug 修复 |
| 3 | `89f8c4b1` | 工具结果图片按模型输入模态回传 | bug 修复 |
| 4 | `8eeaa801` | 中文弯引号匹配修复 | bug 修复 |
| 5 | `152e8614` | extractQuotedContent 单元测试 | 测试 |
| 6 | `b7eb6d77` | fileSizeToString 统一重构 | 重构 |
| 7 | `f4be676b` | GLM-5.2/HY3/LongCat-2.0 模型注册 | 功能增强 |
| 8 | `21d04284` | Qwen MAX/Doubao 2.x 模型检测 | 功能增强 |
| 9 | `1f937b8d` | 移除 Google Imagen 图像生成 | 架构改进 |
| 10 | `0217ca82` | imggen ImageGenSize 重构 | 功能增强 |
| 11 | `c66935f2` | 移除重复模型选择器 | 重构 |
| 12 | `e1ef629b` | TTS 引号扩展（直角引号） | 功能增强 |
| 13 | `57e6dbc1` | 直角引号提取测试 | 测试 |
| 14 | `5f5f9654` | 状态栏沉浸修复（助手详情/设置页） | UI 修复 |
| 15 | `5ea0e35f` | 扩展管理按钮关闭面板修复 | UI 修复 |
| 16 | `624ab635` | 工作区导入按钮移至顶栏 | UI 修复 |
| 17 | `d7f0ef26` | HEIF/HEIC 图片上传与裁剪支持 | 功能增强 |

**跳过（6 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `d1aa55e5` | Ollama fetch 搜索 | Ollama 搜索引擎未在 Fork 中引入 |
| `0aa8073b` | Fish Audio TTS | 已精简 TTS Provider |
| `5856325d` | web 跨平台构建 | Fork 未引入 web 模块 |
| `70aa7a0c` | web 文件夹 + SSE 事件流 | Fork 未引入 web 模块 |
| `d07b54ca` | web-ui 推理选择器 | Fork 未引入 web-ui 模块 |
| `a2a71da2` | MiniMax/MIMO 内置提供商 | 新 Provider，与 Fork 精简方向冲突 |

> 本批包含对 batch-4（多模态工具调用）和 batch-5（mhchem 化学公式）的后续修复，以及 imggen 重构、TTS 引号扩展和状态栏沉浸修复。完整分析（逐文件 diff、本地代码核对、适配要点、持久化影响评估）见详细文档。

---

### 2026-07-06 - 检查 5b39e05d+ 更新（第五批）

> **同步状态：✅ 全部完成（编译通过 + 逐文件核对一致）**
>
> 详细分析文档：[batch-5-2026-07-06.md](upstream-sync/batch-5-2026-07-06.md)

**检查范围**：`5b39e05d..ef564dca`（2026-07-05，共 8 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`ef564dca`（2026-07-05）
- 上次检查时间：2026-07-04（第四批，`5b39e05d`）

**版本号对应关系**：

| 维度 | 上游 | 本地 | 说明 |
|------|------|------|------|
| 版本名 | 2.4.0 | 0.0.9 | 版本线独立，不跟随上游版本号 |
| versionCode | 168 | 9 | 独立递增 |
| 发布提交 | `ef564dca` | — | 上游版本升级提交，本地跳过 |
| DB 版本 | v24 | v3 | 本地从 v1 重建，无历史 migration 包袱 |

> 本批同步未递增本地版本号（`versionCode`/`versionName` 保持 9/0.0.9），因 batch-4 已递增至 0.0.9 且本批改动均为补丁级修复和依赖升级。

**已同步（6 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `b1a9f0ba` | 化学公式渲染支持（mhchem + trust） | 功能增强 |
| 2 | `195287fa` | 预览搜索跳转 requestScrollToItem | bug 修复 |
| 3 | `57651267` | 搜索选择面板残留状态修复 | bug 修复 |
| 4 | `fb1da5d5` | jlatexmath 1.4→1.5（\ce 支持） | 功能增强 |
| 5 | `c0c7f70e` | Compose BOM/Material3/Nav3 升级 + 预存偏离对齐 | 依赖升级 |
| 6 | `0edcd81b` | 压缩上下文对话框手动输入 | UI 改进 |

**跳过（2 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `b3f298f1` | 赞助商名称文档 | 已移除赞助体系 |
| `ef564dca` | 版本升级 2.4.0 (168) | 版本线独立，不适用 |

> 完整分析（逐文件 diff、本地代码核对、依赖升级评估、预存偏离对齐说明、持久化影响评估）见详细文档。

---

### 2026-07-04 - 检查 4b2fd4b9+ 更新（第四批）

> **同步状态：✅ 全部完成（编译通过 + 逐文件核对一致）**
>
> 详细分析文档：[batch-4-2026-07-04.md](upstream-sync/batch-4-2026-07-04.md)

**检查范围**：`4b2fd4b9..5b39e05d`（2026-07-01 ~ 2026-07-03，共 9 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`5b39e05d`（2026-07-03）
- 上次检查时间：2026-06-30（第三批，`4b2fd4b9`）

**已同步（9 个提交，无跳过）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `5b39e05d` | 附件菜单按钮居中 | UI 修复 |
| 2 | `44f9ccb5` | 粗体字重 Bold | bug 修复 |
| 3 | `efe37f23` | 归一化工具调用参数 | bug 修复 |
| 4 | `eb361197` | Google API 多媒体工具响应 | bug 修复 |
| 5 | `2232c65d` | OpenAI 多模态工具调用 | bug 修复 |
| 6 | `1f04d2db` | workspace `/tmp` 免审批 | 功能增强 |
| 7 | `c49be6fe` | OAuth 令牌刷新 | 功能增强（MCP） |
| 8 | `00013e19` | MCP OAuth 2.1 授权 | 新功能（MCP） |
| 9 | `26e31c57` | 会话文件夹分组 | 新功能 |

> 完整分析（逐文件 diff、本地代码核对、适配要点、持久化影响评估）见详细文档。

---

### 2026-06-30 - 检查 a6e7a305+ 更新（第三批）

> **同步状态：✅ 全部完成（编译通过 + 单元测试通过 + 逐文件核对一致）**
>
> 详细分析文档：[batch-3-2026-06-30.md](upstream-sync/batch-3-2026-06-30.md)

**检查范围**：`a6e7a305..4b2fd4b9`（2026-06-27 ~ 2026-06-30，共 12 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`4b2fd4b9`（2026-06-30）
- 上次检查时间：2026-06-27（第二批，`a6e7a305`）

**已同步（9 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `5b46c8de` | screen_time 改用事件配对计算 | bug 修复 |
| 2 | `40b613eb` | screen_time 排除桌面 launcher | bug 修复（配套 #1） |
| 3 | `4b2fd4b9` | S3/COS 下载丢数据 + COS endpoint | bug 修复 |
| 4 | `18addd23` | Skills 扩展面板清理已删除技能残留 | bug 修复 |
| 5 | `4559397b` | 后台文本生成默认 AUTO 推理级别 | 改进 |
| 6 | `3341dfd0` | 渐变背景动画循环跳变 | UI 修复 |
| 7 | `cad7029e` | IME 展开时隐藏输入栏底部圆角 | UI 修复 |
| 8 | `f502bcbf` | 助手头像支持图片裁剪 | 功能增强 |
| 9 | `d677707d` | 新增日历查询与创建工具 | 新功能（必要同步） |

**跳过（3 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `a383c209` | 版本升级 2.3.3 (166) | 版本线独立，不适用 |
| `7b64059e` | 版本升级 2.3.4 (167) | 同上 |
| `f7566e1` | docs: add chat generation pipeline doc | 当时因已有等价架构文档跳过；第九批评估上下文策略时已按本地实现重写补入 |

> 关键改动详述（screen_time 事件配对、S3/COS 修复、日历工具集成）及持久化影响评估见详细文档。

---

### 2026-06-27 - 检查 2.3.2+ 更新（第二批）

> **同步状态：✅ 全部完成（已编译验证 + 单元测试通过）**
>
> 详细分析文档：[batch-2-2026-06-27.md](upstream-sync/batch-2-2026-06-27.md)

**检查范围**：`7f709b23..a6e7a305`（2026-06-21 ~ 2026-06-26，共 13 条提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 本次检查最新上游提交：`a6e7a305`（2026-06-26）
- 上次检查时间：2026-06-20（第一批）

**已同步（7 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `85bb7364` | 快捷消息按钮菜单宽度约束 | UI 修复 |
| 2 | `ded4a5b8` | LaTeX 字体跟随聊天字体大小 | UI 改进 |
| 3 | `31c0f000` | 上下文截断警告 | UX 改进 |
| 4 | `244ce35b` | 最近聊天引用 → 按需工具（⭐ 架构优化） | 架构优化 |
| 5 | `a6e7a305` | 搜索结果返回图片 | 功能增强 |
| 6 | `a8619508` | 屏幕使用时间工具 + local tools 拆分 | 新功能（必要同步） |
| 7 | `aef1bc40` | workspace 挂载 upload 目录 | 功能增强 |

**永久跳过（6 个提交 — 与 Fork 精简方向冲突）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `9d046020` `b78c86d7` `98c7aaf6` | 赞助商 / 推荐提供商 | 已移除赞助体系 |
| `e631a0c6` | Serper 搜索引擎 | 已精简搜索引擎 |
| `26e1e4ae` | StepFun TTS | 已精简 TTS |
| `4c8dab68` | ElevenLabs TTS | 已精简 TTS |

> 逐提交变更内容、本地同步步骤、本地偏离记录（screen_time 工具改进）及持久化影响评估见详细文档。

---

### 2026-06-20 - 检查 2.3.2 更新（第一批）

> **同步状态：✅ 全部完成**
>
> 详细分析文档：[batch-1-2026-06-20.md](upstream-sync/batch-1-2026-06-20.md)

**检查范围**：`5b9be301..7f709b23`（共 8 个提交）

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 当前上游：2.3.2（versionCode 165）- 2026-06-19

**已同步（3 个提交）**：

| # | 提交 | 描述 | 类别 |
|---|------|------|------|
| 1 | `7f709b23` | OCR 请求附带 Provider 高级自定义 body/headers | bug 修复 |
| 2 | `fca33136` | 平板横竖屏旋转后模态抽屉残留打开无法关闭 | bug 修复 |
| 3 | `8e6da720` | 更新依赖和 baseline prof | 依赖更新 |

**跳过（5 个提交）**：

| 提交 | 描述 | 原因 |
|------|------|------|
| `cb3dbf7a` | 支持 Firecrawl 无 API Key 模式 | 已在清理中移除 Firecrawl |
| `eafd5f92` | 适配小米 MiMo ASR + 阶跃星辰 Step ASR | 已在清理中移除 ASR 相关代码 |
| `a92b3194` | 适配 aiping.cn 思考参数 | 未使用的 Provider |
| `f09c3211` | 更新上游 Claude/Agents 文档 | Fork 使用自己的开发文档体系 |
| `a799ce40` | 发布 2.3.2 | 版本线独立 |

> 本地偏离记录（修复废弃 API `currentWindowDpSize`）见详细文档。

---

## 永久跳过汇总

以下提交与 Fork 精简方向直接冲突，所有批次中永久跳过：

| 类别 | 提交 | 描述 | 原因 |
|------|------|------|------|
| 赞助商 | `9d046020` `b78c86d7` `98c7aaf6` | 赞助商 / 推荐提供商 | 已移除赞助体系 |
| 搜索引擎 | `e631a0c6` | Serper 搜索引擎 | 已精简搜索引擎 |
| TTS | `26e1e4ae` | StepFun TTS | 已精简 TTS |
| TTS | `4c8dab68` | ElevenLabs TTS | 已精简 TTS |
| TTS | `0aa8073b` | Fish Audio TTS | 已精简 TTS |
| 搜索引擎 | `d1aa55e5` | Ollama fetch 搜索 | Ollama 搜索引擎未在 Fork 中引入 |
| 新 Provider | `a2a71da2` | MiniMax/MIMO 内置提供商 | 与 Fork 精简方向冲突（新 Provider） |
| 已移除功能 | `cb3dbf7a` | Firecrawl 无 API Key 模式 | 已在清理中移除 Firecrawl |
| 已移除功能 | `eafd5f92` | MiMo ASR + Step ASR | 已在清理中移除 ASR 相关代码 |
| 未使用 | `a92b3194` | aiping.cn 思考参数 | 未使用的 Provider |

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

*最后更新：2026-08-04（第十批扩展同步完成，`3a18dae8..b106e8bb` 共 16 个提交，11 同步 + 1 按需 + 4 跳过；单元测试、Lint、Debug/Release 编译及签名校验通过）*
