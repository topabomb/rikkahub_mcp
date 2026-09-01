# Application Architecture

本文档是 Pilot 当前应用架构的总览与边界参考。它定义稳定语义、唯一所有者、写入协议和跨领域依赖方向；具体实现细节由文末专题文档补充。代码与静态架构契约必须符合本文，行为或边界变化必须在同一变更中同步更新参考文档。

## 产品语义

Pilot 是 Android 本地 Agent 工作台。核心对象使用以下唯一语义：

```text
Work（一个 Conversation）
└─ Turn（一轮交互）
   ├─ Step（模型运行步进）
   ├─ Tool Execution（工具执行与审批）
   ├─ Artifact（附件、生成媒体和工具产物）
   └─ Sub-Agent Run（由 Child Conversation 承载）
```

| 语义 | 运行时对象 | Durable fact |
| --- | --- | --- |
| Conversation | `ConversationRuntime`、`ConversationSnapshot`、`ConversationCommand` | conversation header 与 message tree |
| Turn | `TurnEngine`、`TurnHandle`、`TurnOutcome` | `turn_execution` |
| Step | `GenerationChunk.Phase`、checkpoint | 只在 checkpoint 后形成执行事实 |
| Tool Execution | `ToolExecutionContext`、`ToolCallPhase` | Tool message part 与 `tool_execution` |
| Tool User Interaction | `ResolveToolInteraction` | Tool message part 与 execution status |
| Artifact | `ArtifactStore`、`OwnedArtifact`、`ToolResourceLease` | artifact metadata、reference 与 payload |
| Sub-Agent Run | `DelegationCoordinator`、`SubAssistantLifecycle` | Child Conversation 与 `sub_assistant_call` metadata |

`Runtime`、`Turn`、`Artifact` 是当前会话架构命名。会话运行时领域不得再以 Session/SessionRegistry 指代 `ConversationRuntime`/`ConversationRuntimeRegistry`；除读取历史持久化结构的 migration/backup 边界外，Artifact 领域不得重新引入 ManagedFile 旧语义或兼容门面。TTS queue session、Terminal session 等其他领域的独立 session 语义不受此约束。

## 分层与依赖方向

```text
Compose UI / ViewModel
        │ commands + UiModel/query state
        ▼
Application services / coordinators / query ports / typed use cases
        │ typed command, use case, projection
        ▼
Runtime and domain state machines
        │ mutation + execution facts
        ▼
Repository / ArtifactStore / Settings coordinators
        │ transaction or owned payload operation
        ▼
Room / DataStore / filesystem / Provider SDK
```

依赖只向下。UI 和 ViewModel 不得直接持有 DAO、`ConversationRepository`、Runtime Registry、`ArtifactStore`、payload store 或 Provider 容器。Android framework 入口通过 typed host contract 接收依赖，不使用全局服务定位器。

Application 层负责编排，不建立第二套数据协议。Repository 只执行事务化持久化，不能成为 UI API。Projection 可重建，不能作为 durable fact 的替代来源。

### 边界选择规则

边界按事实所有权和操作语义选择，不按页面数量或包名机械增加层级：

- 单一 owner 内的 typed operation 直接扩展该 owner/use case；只有当流程跨多个 owner、需要串行化、补偿或隔离外部 SDK 时，才增加 application service/coordinator。
- Command 负责校验、串行化和提交；Query 只组合 durable/runtime projection，不能反向执行 mutation。两者生命周期或依赖不同就物理分开，不把多个 service、UiModel 和 host adapter 堆进同一文件。
- 边界类型必须提供投影、身份、能力或状态约束。禁止只包一层同名字段的透传 UiModel/facade；稳定的可序列化配置对象可以直接作为页面编辑草稿，但 Provider 容器、Repository entity、Runtime handle 和线协议对象不能越过边界。
- 页面局部输入、弹窗和 stale-run token 属于 ViewModel/presentation；进程级资源、并发 owner 和可供多个页面恢复的运行态属于 application runtime。不得为了配置变化把页面草稿提升为第二 durable source，也不得把进程资源降到 Composable remember 状态。

`SettingsStore` 是配置 durable owner，`SkillManager` 是 Skill 文件与 typed parse owner；单 owner 的配置或文件命令可以由 ViewModel 通过其 typed contract 调用。Provider 探测、完整恢复、Workspace Rootfs/PTY 等会访问外部 SDK 或跨 owner 的流程必须经过本文列出的 application service。是否增加 service 取决于职责，不以“所有调用都再包一层”为目标。

## 唯一所有者

| 事实或流程 | 唯一 owner | 对外入口 |
| --- | --- | --- |
| Conversation durable command | `ConversationCommandCoordinator` | `ConversationApplicationService` 与领域 coordinator |
| Resident snapshot 与私有 active request | `ConversationRuntime` | `ConversationRuntimeRegistry`；UI 只读 `ConversationPresentation` |
| Conversation 事务持久化 | `ConversationRepository` | Coordinator 产生的 mutation 与 execution fact |
| Turn start、checkpoint、terminal | `TurnEngine` | Master 与 Target coordinator |
| 单请求 usage 归一化 | 各线协议 Adapter | `ProviderUsageSnapshot`；不累计历史或 turn |
| Turn token 累计与完整性 | `RequestUsageReducer` / `TurnUsageAccumulator` | `GenerationLoop`；唯一 durable 结果是 owning Assistant 消息的 `TokenUsage` |
| 正常 stop、supersede、finalize | `TurnFinalization` | turn 编排层 |
| 进程中断恢复 | `TurnRecovery` | `ApplicationRecoveryCoordinator` |
| 子助手 lineage、retention、delete | `SubAssistantLifecycle` | `DelegationCoordinator` 与 application 层 |
| Artifact metadata、引用、状态机 | `ArtifactStore` | `ArtifactUseCase` 与领域服务 |
| Artifact payload IO | `ArtifactPayloadStore` | 仅由 `ArtifactStore` 调用 |
| Settings 图片 roots | `ArtifactSettingsCoordinator` | 头像与背景 typed operation |
| 生成媒体 canonical row、payload 与删除恢复 | `GeneratedMediaStore` | `ImageGenerationCoordinator`、文件管理 application port |
| 应用配置 durable state、有效读模型与提交顺序 | `SettingsStore` | `updateLocal` → `SettingsWriteRules` → `commitSettings` → `effectiveSettings` |
| Skill 文件树、frontmatter 解释、bundle 事务与中断恢复 | `SkillManager` | typed DTO/result、cancellable import、root-swap 与删除暂存恢复 |
| 会话读模型 | `ConversationQueryService` | UI、会话工具与只读详情 |
| 内部稳定附件 handle 索引 | `AttachmentReferenceLookup` | 查询 projector；不作为工具文件读取授权 |
| 启动恢复顺序与写门禁 | `ApplicationRecoveryCoordinator` | Android 启动入口与 retry |
| 标题阶段、异步 token 与提交仲裁 | `ConversationTitleCoordinator` | application 与生成副作用 |
| Provider 设置写入、模型目录、余额与连接探测 | `ProviderSettingsApplicationService` | `ProviderSettingsVM` typed command/query |
| MCP server definition 与用户工具策略 | `SettingsStore` | `McpApplicationService` typed command |
| MCP 已验证工具目录 | `McpCatalogStore` | `McpServerRuntime` candidate commit、`McpQueryService` query |
| MCP 单 server 连接、通知与重连状态 | `McpServerRuntime` | `McpRuntimeCoordinator`、turn capability snapshot |
| MCP OAuth 凭据刷新与授权流程 | `McpOAuthCoordinator` | `McpApplicationService`、`McpServerRuntime` |
| 托管文件跨 owner 清理命令编排 | `FileManagementApplicationService` | 设置文件页 typed command；分别委托 Artifact / GeneratedMedia owner |
| 托管文件列表、候选数与存储统计投影 | `FileManagementQueryService` | `ManagedFileKey` / `ManagedFileUiModel` / `ManagedStorageUiModel`；不暴露领域实体或字符串拼接身份；Compose 列表只从 typed key 提取 Bundle-safe 数值 id，不把应用层 identity 改造成 Android 可序列化类型 |
| 生成期后台保活（平台消费者） | `ChatGenerationForegroundService` / `GenerationForegroundLifetime` | 只消费 `conversationActivities()`，不持有运行事实，不操作 Window flag |
| Workspace 持久化命令、模型 Rootfs 操作与终端互斥 | `WorkspaceApplicationService` | Workspace ViewModel command 与 `executeTool` capability |
| Workspace 管理读模型 | `WorkspaceQueryService` | Workspace 列表、详情、文件预览与 `observeTerminal` |
| Workspace 交互 PTY 与 Tab 生命周期 | `WorkspaceTerminalRuntime` | `WorkspaceApplicationService` / `WorkspaceQueryService` |
| 恢复请求串行化与本地 archive 临时所有权 | `BackupRestoreApplicationService` | `BackupVM` confirmed restore command |
| archive 校验、staging 与 pending 发布 | `BackupArchiveService` | WebDAV/S3 sync service |

同一事实不得增加旁路 DAO/Repository 写入、整聚合回写、fallback、兼容白名单或第二状态源。新增能力应扩展现有 command、typed use case、projection 或状态机。

`SettingsStore` 对外只发布 `effectiveSettings`，并提供本地更新和恢复命令。`ManagedConfigurationStorage`、
`EffectiveSettingsResolver`、`SettingsWriteRules` 与 `commitSettings` 都是同包 internal 协作者：前者只验证并原子保存受管
document/资源，resolver 只合成 Built-in、Local shadow 与受管覆盖，写规则只依据当前 managed generation 拒绝锁定路径。
受管包或本地提交都不能自行发布第二个 Flow；持久化成功后才由 `SettingsStore` 发布同一 revision 的有效快照。UI 只从该
快照显示来源和锁定理由；实际写入仍在 Store 内复核，拒绝时 Local shadow 不变并沿原页面错误反馈通道返回。

删除仍需跨 Settings 与文件 owner 时，文件 owner 先把树移到其可恢复暂存位置，再提交同一个 `updateLocal` transform；锁定或
提交失败必须恢复原树，提交成功后才物理删除暂存树。`SkillManager` 和 `WorkspaceManager` 各自恢复自己的未完成暂存，不以
旁路 Settings 写入或隐藏引用解决跨 owner 失败。

Provider 设置页只通过 `ProviderSettingsApplicationService` 访问 Provider SDK 与 `SettingsStore`。连接测试使用用户当前编辑中的完整 Provider 草稿；保存成功、删除成功等 UI 事件只能在 `SettingsStore.updateLocal` 返回后发布。所有 Workspace UI（含聊天补全、cwd 选择和文件导出）只依赖 `WorkspaceApplicationService`、`WorkspaceQueryService` 及其 UiModel；交互终端页面不持有 `TerminalSession`、创建 Job 或 Repository。模型的 `workspace_*` 工具同样不得直连 Repository；每次工具执行通过 `WorkspaceApplicationService.executeTool` 取得受限 `WorkspaceToolSession`，与安装、删除、UI 文件命令和终端 mutation 共享 per-workspace command gate。

## Conversation 与 Runtime 协议

`ConversationRuntimeRegistry` 显式表示 `Loading`、`Draft`、`Ready`、`Missing` 和 `Failed`。它只能安装从数据库完整装载的 snapshot，或持有尚未持久化的新聊天 Draft；不得为加载期伪造默认 Assistant、空树或 Ready Conversation。

空 Draft 不进入数据库、会话列表或 turn。首条 `AppendUserMessage` 在一个事务中创建 Conversation、写入用户消息与确定性本地标题，并把同一个 Runtime 原位晋升为 Ready。

Durable command 的固定协议是：

```text
validate owner/epoch
  → ConversationTransition.plan 产生 next snapshot 与 mutation
  → Room transaction 提交 mutation、execution fact 和相关 projection
  → 发布 committed snapshot
```

持久化失败必须向调用方传播，并且不能发布 next snapshot。Streaming projection 是唯一允许先发布且不落库的会话状态；它必须携带 `TurnHandle` 并校验 epoch、turnId 与 assistantMessageId。旧 turn 的迟到 delta 必须被拒绝。

Header command 不清除 active turn。与 active owner 冲突的结构命令必须显式拒绝或先由正常终态协议收口。Durable 领域逻辑只读取 `ConversationSnapshot.nodes`；`renderNodes` 只用于 UI 显示投影，其对象身份没有持久化语义。

Non-resident command 仍经过同一个 `ConversationCommandCoordinator` 锁与语义，不得退回 Repository fallback。轻量 header command 不为写入装载完整 message tree。

## Turn、工具与审批

Master 与 Target 共用 `TurnEngine` 和同一套 chunk-to-checkpoint 协议。`StartTurn` 在一个事务中创建 assistant 槽和 RUNNING turn fact；checkpoint 只写 changed nodes；`FinalizeTurn` 同事务关闭遗留的 STARTED tools 并提交不可逆的 turn 终态。

Token usage 沿用同一 message/turn 写协议，唯一 durable 结果是 owning Assistant 消息的 `TokenUsage`。Adapter、request
reducer、turn accumulator、Master/Child 隔离、历史兼容和消费者边界见
[`token-usage-accounting.md`](token-usage-accounting.md)。

Turn 和 Tool execution 使用 insert-once 与合法状态 CAS。终态不可回退，重复同终态幂等，非法转换返回明确冲突。失败或取消的终态准备必须消费 TurnEngine 累积的最新 turn-owned messages；该投影可能包含最后一次 checkpoint 后的 delta，不能用 durable nodes 覆盖。准备阶段校验完整 `TurnHandle`，关闭未完成工具后由同一个 `FinalizeTurn` 原子提交。取消必须传播；`NonCancellable` 只用于已经取得所有权的终态提交或补偿收口，完成后仍重新抛出原始 `CancellationException`。

工具调用按 typed phase 区分调用流、就绪、等待审批、执行和终态。`UIMessagePart.Tool.hasReplayResult` / `Tool.output` 只表示 Provider 可回放结果，不能作为 active 执行中状态、active 完成状态或详情点击门禁。active phase 只能随已提交 checkpoint 的 `ToolExecutionEntity` 或 typed `ToolResultEvent` 推进；影响通知的工具执行与结果 checkpoint 成功后，同一生成流发布 presentation tick，通知等边缘投影只消费提交后 phase。没有 active turn 的历史消息可从 durable replay result 形成静态终态展示，但该静态投影不反向驱动运行态。metadata 只细化领域子阶段。

新 turn 的 `START` 与用户交互后的 `CONTINUE_USER_INTERACTION` 是不同入口：

- `START` 只在没有 active owner 时做结构预检，并由 `TurnEngine.start` 建立新的 `TurnHandle`。
- Approve、deny 与 answer 只提交一个 `ResolveToolInteraction`，然后以 `CONTINUE_USER_INTERACTION` 继续原 owner。
- Continue 不得再次清理树、回填附件、建立第二 turn 或轮换该 turn 的 TTS session。

审批屏障以 `messageId + toolOrdinal` 定位 ToolCall；Pending 全部解决后再按 ordinal 串行执行。Provider 的 toolCallId 只保留为协议数据，不作为本地唯一键。

工具参数契约归各 `Tool.validateArguments`，只做纯校验，不读取 Settings、数据库、文件或网络。
`Tool.parseArguments` 是审批与执行共用的 JSON object 解析入口；空参数缓冲表示无参 object，非空损坏 JSON 不得替换为空 object。
`GenerationToolSetFactory` 是每个 step 工具集合的装配 owner，并稳定注册 `read_tool_output` / `grep_tool_output`、拒绝保留名冲突。
`GenerationLoop` 只编排 Provider step、整批交互屏障、执行顺序、streaming 和 checkpoint；同一个 step 工具索引交给
`ToolCallRuntime` 完成 definition lookup、一次参数解析/纯校验、typed interaction gate、执行包装和结果规范化，不按工具名维护
审批或校验特例。审批与 `ask_user` 分别使用 `Approval` / `UserInput` requirement 和 typed decision，但都只通过 Conversation
command 写回原 ToolCall 并复用原 TurnHandle。

`ConversationContextPlanner` 是请求窗口和成功 Provider step 后 Tool Result 压缩候选的唯一纯规划边界。普通历史裁剪只影响
request projection；完整纯文本 Tool Result 只有被成功 `ModelStepReceipt` 保守确认已进入最终请求投影后才可压缩。
压缩由 48K inline estimated-token 高水位触发，按 16K 低水位和 24K 整批最小净回收量选择；单结果必须净回收至少
512 estimated tokens。最近两个 typed 批次和最近 4K estimated tokens 受保护，不额外冻结整个已完成 USER turn。
稳定估算按每个 Tool Result 独立计算：ASCII code point 总数除以 4 并向上取整、其他 code point 各计 1；生产阈值统一定义在
`ContextTrimmingPolicy.kt`，不由 Provider input token 或 cache 命中率驱动。`ToolOutputStore` 对 `ARCHIVABLE_TEXT` 复用
`ArtifactStore`、`ArtifactReferenceType.TOOL_OUTPUT` 和 unpublished lease，在 `STEP_COMPLETED` checkpoint 建立 root 后发布；
对 `REGENERABLE_TEXT` 只折叠固定 marker，不创建 Artifact。模型只能通过 conversation-scoped read/grep query capability
读取归档正文，UI 不接触 DAO、relative path 或私有文件。
参数失败或工具撤销直接提交 FAILED 结果，不创建 `tool_execution` 记录或制造 STARTED 执行事实；旧 Pending 同时退出等待。
纯参数校验返回领域 `JsonObject`；`ToolArgumentsException` 保留领域字段并统一补齐 `error` / `type: error`，
确保无 active turn 的历史投影仍是失败。该标记不添加到工具正常执行返回的业务失败中。
Denied/Answered 保留已有决定；Approved 仍检查当前参数但不重复询问。实际资源与权限由原执行 owner 复核，
`ToolExecutionContext.approvedByUser` 只从该调用已有审批事实派生，不接受模型提供的授权值，也不新增持久化状态。

## Artifact 与附件

`ArtifactStore` 是 metadata、reference 和生命周期的唯一 owner；`ArtifactPayloadStore` 只执行 staging、rename、stat 和物理删除，不持有 DAO。创建协议是 staging → CREATING row → 原子 rename → ACTIVE，启动恢复可幂等完成或回滚。

未发布资源必须以 `OwnedArtifact` 或 `ToolResourceLease` 显式交接：durable checkpoint 成功后发布，失败或取消精确回滚。用户删除先 CAS 到 DELETING，再在 Settings roots 锁内 detach，最后删除 payload 与 row。GC 只从索引候选出发，并在同一生命周期锁内重验 message refs 与 Settings roots。

上传 Artifact 与图库生成媒体是两个独立领域：前者继续由 `ArtifactStore` 管理引用和生命周期，后者由 `GeneratedMediaStore` 管理 canonical row、payload 与删除恢复。`FileManagementApplicationService` 在 application 边界路由范围删除、单项删除和临时预览写入，预览写入失败时必须删除不完整临时文件；`FileManagementQueryService` 组合列表、图库分页、统计和路径分类等只读投影。Gallery UI 只使用 `GeneratedMediaKind` 和 `GeneratedMediaUiModel`，不接触 `GenMediaEntity`、Repository 或媒体根目录常量，并将 Paging 的首次加载、刷新和错误/重试与真实空状态分开。两个 port 都不成为第三个持久化 owner，也不把两套删除状态合并成新的持久化事实。

Settings 图片导入先完成有界复制、结构魔数与实际 MIME 校验，再提交 root 并发布 artifact。当前写协议不会提交缺少
ACTIVE metadata 的本地 Settings root。启动发现头像/背景 metadata 或 payload 缺失时，经既有 Settings root 协议持久化
回到默认显示偏好，不扫描目录认领文件；仍被消息引用的 ACTIVE artifact 缺少 payload 则保持 fail-closed。

内部稳定附件 handle 使用 `attachment:<uuid>`，由 `AttachmentReferenceLookup` 统一索引直接 message part 与
`assistant_call.artifacts`，只用于内部 metadata 与查询投影。模型可见位置统一为真实 `/upload/<file>` 的 `path`，
识图与委托不接受 UUID、远程 URL 或任意 file URI，也不依赖 Workspace、当前分支是否引用该文件。
`ArtifactStore.withUploadImages` 在 lifecycle lock 内校验 ACTIVE/已发布文件并取得既有 retention pin，锁外有界读取和
校验实际图片；finally 释放，删除/GC 尊重同一保留规则。识图内存快照经共用 FileEncoder 规范化后使用 data URI，
Child 在保留作用域内提交原文件引用。读取本身不创建磁盘副本、持久化记录、别名或第二套 owner。

查询侧从 durable nodes 与 owning active assistant message 重建内部索引，同时为已知附件工具的顶层 `attachments`
路径生成预览。所有本地预览经 ArtifactStore 校验，UI 只做 map lookup，不扫描 metadata 或直连文件 owner；
预览 map 和工具输入路径不成为 durable root。`AssetFileNames` 只生成无来源前缀候选名，占位与碰撞裁决留在各文件 owner；
旧文件与内部 UUID 不改写，图库索引通过显式 Room migration 创建。细节见 [多模态参考](multimodal-context-and-turn-durability.md)。

## 子助手与恢复

子助手不是第二套生成引擎。`DelegationCoordinator` 只编排 preflight → materialize Child → run → terminal；Child、Tool STARTED 与 childConversationId 关系必须在 Target 启动前强制提交，失败时补偿 Child 与未发布 Artifact。并发所有权使用 `SubAssistantRunGate.withLease` 的结构化作用域。

`SubAssistantLifecycle` 拥有 lineage、retention、fork 与删除；`TurnFinalization` 拥有正常运行中的 stop 和 supersede；`TurnRecovery` 只处理进程恢复。三者不得互相吸收职责或通过整树兼容写入收口。

启动恢复由 `ApplicationRecoveryCoordinator` 以固定顺序执行：Settings → Artifact → GeneratedMedia → reference projection → FTS projection → Child/Master turn → Assistant cleanup。任一步失败进入 `Failed`，durable command 保持关闭；retry 重跑同一幂等顺序。文件 command/query port 同样先等待这一全局门禁，页面不能在 tombstone 与孤儿 payload 收口前读取或删除托管文件。

恢复只查询非终态 execution 索引，健康会话不加载 message tree。非终态 execution 缺少 owning Assistant message 是持久化完整性错误；恢复进入 `Failed`，不发布会话、也不补偿写入 turn/tool facts。消息 payload 损坏同样保持 fail-closed。

## Query、UI 与标题

会话 UI 只消费 `ConversationReadState`、`ConversationSnapshot`、`ConversationSummary`、`ConversationPresentation` 或专用 UiModel。页面对同一 snapshot 建立一个权威订阅；UI 不从 Runtime Job、布尔值或 Tool output 推断活动状态。

`ConversationPresentation` 区分 idle、generating、awaiting approval 与 stopping。审批暂停仍属于 active turn，通知协议将其表达为可回到会话处理的待审批态，不得误报为“生成完成”。`STOPPING` 期间隐藏审批、回答与子助手交互入口，避免向已在收口的 owner 提交竞态命令。Tool 卡片从首个调用 delta 起可查看；不完整 JSON 显示原始片段，不能等 output 出现才开放详情。

`ConversationTitleCoordinator` 统一确定性本地标题、模型标题 phase、token、重试与手动写入。模型请求携带 expected title，并以 `UpdateTitleIfCurrent` CAS 提交；CAS 的成功语义是 expected title 匹配，与新旧文本是否相同无关，相同值无需写库但仍收口为 resolved。手动标题和模型提交共享串行边界，手动提交会使活动和排队 token 失效。异步结果与 force 请求都不能覆盖请求发出后产生的手动标题。

## 持久化与兼容边界

当前架构不要求回滚已有数据库或 Settings 数据结构。Conversation、Message、Turn、Tool 与 Artifact 的 schema 变化只能通过显式 Room migration 演进，并以 fresh schema 同构、历史 migration、数据保全和外键检查验证。

`AppDatabase` 是业务 Room 数据库。索引随实体 schema 和显式 migration 一起维护，不由启动恢复或业务请求临时创建；查询和写入继续通过既有 owner。当前查询覆盖与索引边界见[数据库索引参考](database-indexing.md)。

兼容只允许存在于稳定的持久化数据和外部协议解析边界，且必须被类型化、测试化。内部旧 facade、deprecated 转发、fallback、服务定位器、过渡命名、无调用协议和静态白名单必须物理删除。应用版本与 changelog 只有在发布需求明确要求时才修改。

## 架构验证

架构或跨模块变更至少验证：

- Runtime/Coordinator 的并发、stale turn、持久化失败不发布与 active owner 冲突。
- Start/checkpoint/finalize 的事务性、CAS、取消传播与 Master/Target 等价。
- Approval approve/deny/answer 的实际编排入口、同一 owner 与无结构预检 continuation。
- Artifact 创建、发布、删除、GC、Settings replacement、崩溃恢复与补偿。
- Draft 晋升、non-resident command、恢复门禁和损坏数据 fail-closed。
- UI/query 静态依赖、projection 一致性、标题竞态与附件索引唯一规则。
- Room 历史 migration、fresh schema 和持久化数据保全。

完整 JVM、Debug、Lint 与 Release 门禁不能代替真机或模拟器验收。涉及升级、系统授权、数据库 migration、Compose 交互或 Android 生命周期时，必须记录实际设备环境与结果；未执行必须明确标为未验证。

## 专题参考

| 领域 | 文档 |
| --- | --- |
| 会话、Runtime、生成、审批、工具与标题 | [`chat-generation-pipeline.md`](chat-generation-pipeline.md) |
| 多模态、附件、Artifact 与 Turn durability | [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md) |
| 子助手 owner、lineage、retention 与恢复 | [`sub-assistant-architecture.md`](sub-assistant-architecture.md) |
| 子助手多模态输入输出 | [`sub-assistant-multimodal.md`](sub-assistant-multimodal.md) |
| Assistant 配置 | [`assistant-configuration.md`](assistant-configuration.md) |
| MCP 生命周期、目录与 UI 投影 | [`mcp-architecture.md`](mcp-architecture.md) |
| Provider 线协议 | [`protocol-reference.md`](protocol-reference.md) |
| 模型可见 prompts 与工具结果 | [`prompts-and-tools.md`](prompts-and-tools.md) |
| Compose 导航、布局与主题 | [`ui-architecture.md`](ui-architecture.md) |
| 消息渲染 | [`message-rendering-pipeline.md`](message-rendering-pipeline.md) |
| 数据库查询索引与迁移边界 | [`database-indexing.md`](database-indexing.md) |
| Workspace/PRoot | [`workspace-architecture.md`](workspace-architecture.md) |
| 更新与发布 | [`update-mechanism.md`](update-mechanism.md) |
