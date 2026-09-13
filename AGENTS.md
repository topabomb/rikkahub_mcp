# Repository Guide

代码与静态契约测试是实现事实，`docs/references/` 只描述当前架构。发生冲突时先核对代码，并在同一变更中
修正文档；不得用兼容层、fallback 或白名单掩盖不一致。

## 工作方式与审查

- 修改领域前先阅读对应 `docs/references/`；行为、owner、协议或边界变化时同步文档和静态契约。
- 重大重构、跨模块/数据契约变更及交付前关键审查，可在独立上下文中使用最多 2 个子代理。
  不委派可直接完成的琐碎检查。审查只返回简洁、准确、可执行的高信号发现，重点关注架构漂移、重复
  事实源、ownership、契约稳定性、非必要复杂度、正确性和测试价值。
- 工作树可能包含用户改动。不得回退、覆盖或顺带提交无关内容；冲突无法安全绕开时再请求用户决策。
- 变更必须完整移除旧路径、无调用协议和过渡命名；禁止以“最小改动”保留架构债。

## 验证

- Windows 使用 `gradlew.bat`，macOS/Linux 使用 `./gradlew`；Gradle 固定串行：
  `--no-parallel --max-workers=1`。
- 先做定向验证，再运行与风险匹配的完整门禁。架构或跨模块变更的基准为：
  `gradlew test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`。
- 设备、数据库 migration、Compose instrumentation 和真实系统集成必须运行 `connectedDebugAndroidTest`
  及对应真机/模拟器场景；JVM 或构建通过不能表述为设备验收通过。
- 仅在关键业务逻辑、数据结构、持久化、并发、取消、事务、恢复或 ownership 变化时增加高价值测试；
  覆盖失败路径与竞态，清理过时或只重复实现的测试。
- 交付前检查 `git diff --check`、最终 diff、测试报告、编码/换行和工作树。

## 模块边界

| Module | Owner scope |
| --- | --- |
| `app` | Compose UI、application/service、Room/DataStore、Android 集成 |
| `ai` | Provider 抽象、线协议、`UIMessage`、流式合并与工具基础模型 |
| `common` | 跨模块 Kotlin/Android 工具 |
| `document` | PDF、DOCX、PPTX、EPUB 解析 |
| `highlight` | Kotlin 原生代码语法高亮 |
| `material3` | Material You 动态配色扩展 |
| `search` | 搜索 SDK |
| `speech` | TTS 与 ASR |
| `workspace` | PRoot 沙箱、文件与代码工具 |

## 架构不变量

### Owner、持久化与恢复

- 每类 durable 事实只有一个 owner 和一个写协议。扩展现有 command、typed use case、projection 或状态机；
  禁止旁路 DAO/Repository、服务定位器、整聚合回写和第二状态源。
- UI/ViewModel 只依赖 application/query ports 与 UiModel，不直连 DAO、Repository、Runtime Registry、
  Artifact/GeneratedMedia store 或 payload 层。UI 使用 `ConversationPresentation`，不持有 Runtime Job。
- Artifact metadata、引用和生命周期只归 `ArtifactStore`；`ArtifactPayloadStore` 只做磁盘 IO。
  未发布资源通过 typed lease/owner 交接，checkpoint 成功后发布，失败或取消精确回滚。
- 生成媒体 canonical row、payload 和删除恢复只归 `GeneratedMediaStore`；文件 application/query service 只做
  跨 owner 编排和只读投影。`attachment:<uuid>` 索引只归 `AttachmentReferenceLookup`，对外只披露真实
  `/upload` 路径；UI 不扫描消息 metadata 或解析子助手 payload。
- 受管路径读取由 `ArtifactStore` 校验，不要求当前分支仍引用该资源，也不依赖 Workspace。
- 启动恢复顺序固定为 pending backup restore → Settings → Artifact → GeneratedMedia → projection →
  turn/assistant cleanup → post-recovery maintenance，并 fail-closed。破坏性操作使用可恢复状态机与幂等/CAS，
  不能用日志、空列表或 best-effort 代表成功。

### Conversation、turn 与工具

- `ConversationCommandCoordinator`、`ConversationRuntimeRegistry`、`ConversationRuntime`、
  `ConversationTransition`、`TurnTransition`、`TurnCommitter` 是唯一会话命令、snapshot 与 turn 链；事务成功后
  才发布 durable 状态，只有 streaming projection 可先发布且不落库。
- 新聊天是非持久化 Draft；首条 `AppendUserMessage` 单事务建库并原位晋升 Ready。空 Draft 不入库、列表或 turn。
- `START` 与 `CONTINUE_USER_INTERACTION` 是不同协议。继续流程保留原 `TurnHandle`，不清树、不回填附件、
  不创建第二 turn。durable 流程只读 `ConversationAggregateSnapshot.nodes`；UI 只消费
  `ConversationPresentationSnapshot.nodes`。
- 工具装配、参数校验、审批、执行和终态使用 typed phase，并共用同一 step 工具索引。
  `Tool.parseArguments` 与工具自身的纯校验必须在审批前完成；资源、权限和远端业务校验归执行 owner。
  `Tool.hasReplayResult/output` 只表示 Provider 可回放结果，不充当 active 状态或详情门禁；未执行的拒绝只提交
  消息失败结果，不创建执行记录。执行 phase 只随已提交 checkpoint 推进，metadata 只细化领域子阶段。
- 取消必须传播。`NonCancellable` 仅用于已取得明确 ownership 的终态提交或补偿收口；不得用
  `runCatching` 或宽泛 catch 吞掉 `CancellationException`。
- 标题只归 `ConversationTitleCoordinator`；模型结果使用 token + expected-title CAS 并与手动写入串行，
  不得覆盖请求发出后的手动标题。

### 配置与 UI

- 用户配置归 Settings owner，企业配置归 Enterprise owner，按域解析归 ConfigurationResolver；UI 只提交 typed
  配置命令并消费投影，不从显示值反推持久化语义。
- 企业定义助手的 `modelId` 是默认模型，不是强制锁定模型。准入允许时，企业助手可在本域选择企业模型或
  用户自有模型；受管定义、system prompt 和固定 MCP 保持只读。助手模型的“助手默认 / 空间默认 / 指定模型”
  三态必须在持久化和 UI 投影中保持可区分，失效显式引用不得静默替换。
- 移动端默认界面保持低密度、清晰动作归属和渐进披露；内部 owner、generation、准入等概念只在用户作决定
  或诊断失败时出现。共享用户定义从企业上下文编辑时明确提示影响，不能复制第二套编辑器。

### Context window 与 prompt cache

- 移动端必须主动控制 Provider context window。历史 tool output 的自动策略只有 rolling compaction：成功请求后、
  checkpoint 前由 `ToolOutputCompactionPlanner.planAfterSuccessfulRequest` 纯计划，阈值唯一来源为
  `ContextBudget`，写入由 `ToolOutputStore.stageCompaction` 完成。
- 仅压缩本次成功请求可见且已消费的历史 inline tool result：`ARCHIVABLE_TEXT` 归档为 artifact marker，
  `REGENERABLE_TEXT` 折叠为固定 marker，`PRESERVE` 原样保留。保护窗口内和净回收不足的批次不改写。
- Prompt cache prefix 稳定性仅次于长度控制；自动链路不得无故改写前缀。会话级 history summarization 尚未实现，
  不得冒充 rolling compaction。完整规则见 `docs/references/request-context.md`。

## 错误与诊断

- 预期的校验、权限或业务拒绝使用稳定 reason/code 与可行动说明；非预期异常不得被“操作失败，请重试”等
  通用文案覆盖。
- 非预期异常的用户可见诊断至少保留异常类型、原始 message/detail 和有意义的 cause；长内容应可展开或复制。
  记录完整堆栈并保留原 cause，不能只写日志后向 UI 发布空值、假成功或通用占位符。
- 只脱敏凭据、token、Cookie、Authorization、密钥和明确的隐私 payload；不得以安全为由删除异常类型、
  stable reason/code 或与定位相关的非敏感细节。取消不作为失败展示，必须继续向上传播。
- 新增错误路径时测试真实诊断仍可见、原异常未被吞掉，并覆盖重试/恢复行为。

## 数据与构建边界

- Room/DataStore/文件结构保持稳定；结构变化必须提供显式 migration、schema 同构和数据保全测试。
- Release 使用 AGP 9 optimization；运行时 keep rules 位于 `app/src/main/keepRules/rikkahub.keep`。
  `android.r8.strictFullModeForKeepRules=false` 是依赖 consumer rules 的现行兼容要求，依赖未确认前不得删除。
- APK ABI 为 `arm64-v8a` 与 `x86_64`，App Bundle 自动禁用 splits，Debug 包名带 `.debug`。
- 默认不改 `versionCode`、`versionName` 或 changelog；只有用户明确要求发布版本时才同步。

## 架构参考

- 总体 owner：`application-architecture.md`；测试：`testing-strategy.md`；数据库：`database-indexing.md`。
- 会话/turn/审批：`turn-step-execution.md`；请求上下文：`request-context.md`；协议：`protocol-reference.md`；
  prompt/tool：`prompts-and-tools.md`；token：`token-usage-accounting.md`。
- 配置：`assistant-configuration.md`、`android-configuration-architecture.md`；MCP：`mcp-architecture.md`。
- Artifact/附件：`multimodal-context-and-turn-durability.md`；子助手：`sub-assistant-architecture.md`、
  `sub-assistant-multimodal.md`；Workspace：`workspace-architecture.md`。
- UI：`ui-architecture.md`；渲染：`message-rendering-pipeline.md`；更新：`update-mechanism.md`。
- `docs/dev/original-architecture.md`、`fork-simplification-plan.md` 是历史归档，不是当前实现依据；
  `docs/dev/upstream-sync.md` 是上游同步总账。

核心实现导航：会话 `ConversationTurnService` / `TurnFinalizer` / `TurnRecovery`；Artifact `ArtifactStore` /
`ArtifactPayloadStore` / `ArtifactSettingsCoordinator` / `ArtifactUseCase`；媒体 `GeneratedMediaStore`；MCP
`McpServerRuntime` / `McpRuntimeCoordinator` / `McpCatalogStore` / `McpOAuthCoordinator`；子助手
`SubAssistantRunCoordinator` / `SubAssistantLifecycle` / `SubAssistantRunGate`；架构契约位于
`app/src/test/java/net/weero/measix/pilot/architecture/`。

## 文档、代码与本地化

- `docs/references/` 只写当前事实，使用类、函数、常量和真实工具名定位，不用易失行号或阶段术语。
  `docs/dev/changelog.md` 只在明确发布时更新；上游同步每批先 fetch 并续写 `upstream-sync.md`。
- 遵循 `.editorconfig`：Kotlin/Gradle 4 空格，XML/JSON/Markdown/YAML 2 空格，UTF-8 without BOM + CRLF；
  Kotlin 类型 PascalCase，测试类以 `*Test` 结尾。
- 注释只解释长期语义、ownership 和非显然原因；删除临时计划、迁移阶段说明和无调用代码。
- 常见用户可见字符串同步 `values`、`values-zh`、`values-ja`、`values-ko-rKR`、`values-ru`；底层英文
  reason/detail 可只放源语言。Compose 使用 `stringResource`，非 Composable 使用 `Context.getString`。
