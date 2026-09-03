# Repository Guide

代码与静态契约测试是实现事实，`docs/references/` 是当前架构参考。
发生冲突时先核对代码，并在同一变更中修正文档；禁止以兼容层掩盖不一致。

## Subagent Collaboration

Seek a review in a separate context using at most 2 subagents only at key leverage points (e.g., substantial refactors, cross-module/data-contract changes, and prior to completion). Do not delegate trivial checks or tasks you can investigate directly; handle straightforward verifications independently. The review should focus on architecture drift, duplicate mechanisms or sources of truth, unclear ownership, unstable contracts, unnecessary complexity, material correctness, test gaps, and redundant or low-value tests. Always explicitly instruct subagents to return concise, accurate, and high-signal feedback—delivering actionable findings with zero fluff.

## Commands and Verification

- Windows 使用 `gradlew.bat`，macOS/Linux 使用 `./gradlew`。
- 本仓库串行运行 Gradle：`--no-parallel --max-workers=1`。
- 定向验证后必须运行与风险匹配的完整门禁。架构或跨模块变更的基准命令：
  `gradlew test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`。
- 设备、数据库 migration、Compose instrumentation 或真实系统集成行为使用
  `connectedDebugAndroidTest` 及对应真机/模拟器场景验证。构建或 JVM 通过不能表述为设备验收通过。
- 只有在关键业务逻辑变动或数据结构变动或持久化变动时新增或更新测试；并发、取消、事务、恢复和所有权变更需验证覆盖失败路径与竞态；不追求测试的覆盖面，清理过时或低价值的测试用例。
- 提交前检查 `git diff --check`、最终 diff、测试报告和工作树；不得把无关用户改动纳入或回退。

## Project Map

| Module | Owner scope |
| --- | --- |
| `app` | Jetpack Compose UI、application/service、Room/DataStore、Android 集成 |
| `ai` | Provider 抽象、线协议、`UIMessage`、流式合并与工具基础模型 |
| `common` | 跨模块 Kotlin/Android 工具 |
| `document` | PDF、DOCX、PPTX、EPUB 解析 |
| `highlight` | Kotlin 原生代码语法高亮 |
| `material3` | Material You 动态配色扩展 |
| `search` | Bing、Tavily、SearXNG 搜索 SDK |
| `speech` | System/OpenAI/Gemini/MiMo TTS 与 ASR |
| `workspace` | PRoot 沙箱、文件与代码工具 |

核心实现导航：

> 增加或调整核心语义或概念需同步下列内容

- 会话命令与 turn：`MasterTurnCoordinator`、
  `TurnFinalization`、`TurnRecovery`。
- Artifact：`ArtifactStore`、`ArtifactPayloadStore`、`ArtifactSettingsCoordinator`、`ArtifactUseCase`。
- 生成媒体与文件管理：`GeneratedMediaStore`、`FileManagementApplicationService`、`FileManagementQueryService`。
- MCP：`McpServerRuntime`、`McpRuntimeCoordinator`、`McpCatalogStore`、`McpOAuthCoordinator`。
- 子助手：`DelegationCoordinator`、`SubAssistantLifecycle`、`SubAssistantRunGate`。
- UI 边界：`ConversationApplicationService`、`ConversationQueryService` 及专用 reader/query port。
- 架构契约：`app/src/test/java/net/weero/measix/pilot/architecture/SingleWriterContractTest.kt` 与性能证据测试。

## 核心约定 Core Rules

### 1. Context window budget —— 第一优先级

- 移动端必须主动控制每请求发送给 Provider 的 context window 长度。针对历史 tool output 的自动手段是
  **rolling compaction**：step 提交后由 `ConversationContextPlanner.planPostStepCompaction` 产出计划
  （阈值唯一来源是 `ContextTrimmingPolicy`），落盘由 `ToolOutputStore.stageCompaction` 执行；计划阶段不做任何文件或消息写入。
- 压缩对象只限于"本次成功请求确实可见、且模型已消费的历史 inline tool result"。降级形态由 `ToolOutputPolicy` 决定，三者不得混用：
  - `ARCHIVABLE_TEXT` → **archiving**：原文写入 artifact，消息内替换为 `[Archived tool result: ref=...]` marker，
    模型可用 `read_tool_output` / `grep_tool_output` 回查。
  - `REGENERABLE_TEXT` → **folding**：替换为固定 `REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER`，内容可用原参数重新生成，不复制 payload。
  - `PRESERVE` → 始终保留完整原始 output，不参与压缩。
- 保护窗口内的最近批次与最近 token 不参与压缩；整批净回收不足时不得改写历史，避免为微小收益付出前缀代价。

### 2. Prompt cache prefix —— 第二优先级

- Provider 侧 prompt cache 命中依赖 request prefix 的稳定性（prefix stability）。改写历史 tool result 必然改写前缀，
  因此前缀稳定性是仅次于长度控制的第二优先级；它不是长度控制的免死金牌。
- 冲突裁决：
  - 用户显式修改配置或消息、且明确知晓会破坏前缀 → 接受，属于用户意图。
  - rolling compaction 自身改写历史 → 仅在预算触发、且只作用于已消费的历史 tool result 时允许，由此产生的前缀失效是预期代价。
  - agent / assistant / sub-assistant 在自动生成链路中因工具调用破坏前缀 → 不可接受，不得以"影响面小"绕过。
- 前缀稳定性由保护窗口与阈值调节，不得靠"不压缩"换取。
- 条数窗口、滚动压缩、Disclosure 与手动摘要如何叠加见 `docs/references/request-context.md`。

### 3. 未实现边界

- 会话级自动压缩（conversation-level compaction / history summarization）仍在 roadmap，当前未实现。它作用于历史消息层，
  与已实现的 tool output rolling compaction 是两件事，不得混为一谈或互相冒充。


## Architecture Rules

> 架构变动或约束调整时需同步以下内容

- 每类 durable 事实只有一个 owner 和一个写协议。扩展既有 command、typed use case、projection 或状态机；
  禁止旁路 DAO/Repository 写入、服务定位器、整聚合回写和第二状态源。
- `ConversationCommandCoordinator`、`ConversationRuntimeRegistry`、`ConversationRuntime`、
  `ConversationTransition` 与 `TurnEngine` 构成唯一会话命令、snapshot 与 turn 链。事务成功后才发布 durable 状态；
  streaming projection 是唯一允许先发布且不落库的会话态。
- 取消必须传播。`NonCancellable` 只用于已经取得明确所有权的终态提交或补偿收口；不得用 `runCatching`
  吞掉 `CancellationException`。
- 新聊天是非持久化 Draft；首条 `AppendUserMessage` 单事务建库并原位晋升 Ready。空 Draft 不得进入数据库、
  会话列表或启动 turn。
- 新 turn 的 `START` 与用户交互后的 `CONTINUE_USER_INTERACTION` 是不同协议。继续流程保留原 `TurnHandle`，不得清理树、
  回填附件或创建第二 turn。durable 流程只读取 `ConversationAggregateSnapshot.nodes`；UI 只消费
  `ConversationPresentationSnapshot.nodes`，禁止以显示列表对象身份推断写入。
- 工具装配、调用拼接、就绪、审批、执行和终态使用 typed phase。`UIMessagePart.Tool.hasReplayResult` / `Tool.output`
  只表示 Provider 可回放结果，不能充当 active 运行状态或详情门禁；执行阶段只随已提交 checkpoint 推进，metadata 只细化领域子阶段。
- 工具参数由 `Tool.parseArguments` 与工具自身纯校验在审批前检查；可用性、审批与执行共用同一 step 工具索引。
  未执行的拒绝只提交消息失败结果，不创建执行记录；资源、权限与远端业务校验仍归实际执行 owner。
- UI/ViewModel 只依赖 application/query ports 与 UiModel，不得直连 DAO、ConversationRepository、Runtime
  Registry、ArtifactStore、GeneratedMediaStore 或 payload 层。UI 使用 `ConversationPresentation`，不持有 Runtime Job。
- 标题由 `ConversationTitleCoordinator` 管理。模型结果使用 token + expected-title CAS，并与手动标题写入串行；
  异步或 force 结果不得覆盖请求发出后产生的手动标题。
- Artifact metadata、引用和生命周期只归 `ArtifactStore`；`ArtifactPayloadStore` 只做磁盘 IO 且无 DAO。
  未发布资源必须用 typed lease/owner 交接，checkpoint 成功后发布，失败或取消精确回滚。
- 图库生成媒体的 canonical row、payload 与删除恢复只归 `GeneratedMediaStore`。`FileManagementApplicationService`
  与 `FileManagementQueryService` 只做跨 owner 命令编排和只读投影，不成为第三个文件 owner。
- 内部 `attachment:<uuid>` 的索引只归 `AttachmentReferenceLookup`；对外只披露真实 `/upload` 路径。
  路径读取由 `ArtifactStore` 校验受管文件，不要求当前分支引用，也不依赖 Workspace。UI 不扫描消息 metadata、
  不解析子助手 payload，也不直连 Artifact。
- 启动恢复按 pending backup restore → Settings → Artifact → GeneratedMedia → projection → turn/assistant cleanup → post-recovery maintenance 固定顺序 fail-closed；
  损坏或不完整聚合不得伪装 Ready。破坏性操作使用可恢复状态机与幂等/CAS，不以日志、空列表或 best-effort 代表成功。
- 架构迁移必须在同一交付中物理删除旧 facade、fallback、deprecated 转发、兼容白名单、过渡命名和无调用协议；
  同步更新静态契约测试与参考文档，不保留双路径。

## Persistent Data and Build Boundaries

- 保持既有 Room/DataStore/文件数据结构稳定；结构变化必须提供显式 migration、schema 同构与数据保全测试，每次考虑更改数据结构时必须从长期稳定、符合项目演进方向的角度来规划。
- Release 使用 AGP 9 optimization；运行时 keep rules 位于 `app/src/main/keepRules/rikkahub.keep`。
- `android.r8.strictFullModeForKeepRules=false` 是当前依赖 consumer rules 的兼容要求，依赖确认兼容前不得删除。
- APK ABI 为 `arm64-v8a` 与 `x86_64`，App Bundle 构建自动禁用 splits；Debug 包名带 `.debug`。
- 默认保持 `versionCode`/`versionName` 与 `changelog` 不变。只有用户明确要求发布版本时才同步更新。

## Current Architecture References

修改对应领域前先阅读相关文档，并在行为或边界变化时同步维护：

| Topic | Reference |
| --- | --- |
| 应用总体架构、所有者与边界 | `docs/references/application-architecture.md` |
| Room 索引、查询覆盖与迁移边界 | `docs/references/database-indexing.md` |
| 会话、Runtime、turn、审批与标题 | `docs/references/chat-generation-pipeline.md` |
| 请求上下文：条数窗口、滚动压缩、披露与摘要 | `docs/references/request-context.md` |
| 多模态、附件、Artifact、Turn/Tool durability | `docs/references/multimodal-context-and-turn-durability.md` |
| 子助手 owner、lineage、retention、恢复 | `docs/references/sub-assistant-architecture.md` |
| 子助手多模态输入输出 | `docs/references/sub-assistant-multimodal.md` |
| Assistant 配置模型 | `docs/references/assistant-configuration.md` |
| Android 配置目录与企业下发边界 | `docs/references/android-configuration-architecture.md` |
| MCP definition、catalog、runtime、OAuth 与 UI 投影 | `docs/references/mcp-architecture.md` |
| Provider 线协议 | `docs/references/protocol-reference.md` |
| Token usage、缓存命中、累计与统计口径 | `docs/references/token-usage-accounting.md` |
| 模型可见 prompts 与工具结果 | `docs/references/prompts-and-tools.md` |
| Compose 导航、布局、主题与图片查看器 | `docs/references/ui-architecture.md` |
| Markdown/代码/Mermaid/LaTeX 渲染 | `docs/references/message-rendering-pipeline.md` |
| Workspace/PRoot | `docs/references/workspace-architecture.md` |
| 更新与发布机制 | `docs/references/update-mechanism.md` |
| Fork 前冻结架构 | `docs/dev/original-architecture.md`（仅历史，不作当前实现依据） |
| 上游同步总账 | `docs/dev/upstream-sync.md` |

## Documentation Maintenance

需主动维护：

- `docs/references/` 只描述当前实现。行为、owner、协议或边界变化时同步修改对应参考；不用行号、行数或易失计数定位，
  使用类、函数、常量和真实工具注册名，代码片段必须忠实反映实际声明。
- `docs/dev/changelog.md` 记录版本内容。只有用户明确要求发布版本时，才在顶部新增版本条目并同步修改。维护内容是用精简的人类友好的语言介绍变更，不要过多技术细节和冗长内容。
  `app/build.gradle.kts` 的 `versionCode`/`versionName`；默认保持不变。
- `docs/dev/upstream-sync.md` 是上游同步总账，其中包括了工作流说明和方法论，执行同步时注意架构的变迁，引入上游变更要合理整合到本项目架构。每次同步都先 fetch，按上一批冻结点续查，并追加本批摘要。

`docs/dev/` 为开发过程文档：`original-architecture.md`、`fork-simplification-plan.md` 是 Fork 时的冻结归档；不作为当前架构规则来源。Gemini 后续范围见 `google-gemini-protocol-correction-plan.md`。

## Code and Localization Conventions

- 遵循 `.editorconfig`：Kotlin/Gradle 4 空格，XML/JSON/Markdown/YAML 2 空格，文本统一 UTF-8 without BOM + CRLF，
  Kotlin 类型使用 PascalCase，测试类以 `*Test` 结尾。
- 注释解释长期成立的语义、所有权与非显然原因；删除临时计划、迁移阶段和已经失效的说明。
- 用户交互流程常见可见字符串同步 `values`、`values-zh`、`values-ja`、`values-ko-rKR`、`values-ru`，适度扩展语言文件，避免逐渐膨胀。
  底层英文诊断 reason/detail 可只放源语言。Compose 使用 `stringResource`，非 Composable 使用 `Context.getString`。
- 变更必须完整、清晰、可验证。文件或概念命名不再符合唯一语义时直接改名；删除无调用代码、重复 owner、
- 禁止“最小改动”或“影响面小”的理由以打补丁或者会造成歧义的方式实现，禁止保留架构债。
- 不要把阶段计划、临时结论或迁移过程术语写进代码注释。
