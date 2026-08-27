# 消息生成链路

> 本文档以当前实现为准，说明用户命令、生成流、持久化与恢复的唯一链路。总体 owner 与分层边界见
> [`application-architecture-v1.md`](application-architecture-v1.md)，附件与执行事实细节见
> [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)，子助手扩展见
> [`sub-assistant-architecture.md`](sub-assistant-architecture.md)。

## 职责边界

| 类型 | 职责 |
| --- | --- |
| `ConversationApplicationService` | 用户会话命令、创建、删除、fork、压缩入口；不直接实现 Runtime 或 Room 写协议 |
| `ConversationQueryService` | UI 读端口；活动会话返回显式 Loading/Ready/Missing/Failed 的 `ConversationReadState`，Ready 携带 `ConversationSnapshot`；列表返回不含消息树的 `ConversationSummary` |
| `MasterTurnCoordinator` | Master turn 输入、工具与副作用编排；不实现第二套持久化协议 |
| `ConversationCommandCoordinator` | resident/non-resident durable command 唯一入口；按 conversationId 串行化，并受恢复门禁保护。`execute()` 把身份/缺失冲突映射为 `Conflict`，事务失败映射为 `Failure` |
| `ConversationRuntime` | 已提交 `ConversationSnapshot` 的唯一事实流、私有 `ActiveTurnRuntime` 请求状态机与纯内存 streaming projection。durable CAS 仍只用 `TurnHandle` |
| `ConversationRuntimeRegistry` | `Loading/Draft/Ready/Missing/Failed` 生命周期、引用与生成 Job；Draft 只服务未发送首条消息的新聊天，首消息事务后晋升 Ready；禁止伪造 durable 会话 |
| `ConversationTransition` | 唯一 command planner：同时产生 snapshot、delta mutation 与 execution facts；未变化节点保持引用 |
| `TurnEngine` / `TurnPipelineFactory` | Master/Target 共用的 start、checkpoint、stream 与非空 `TurnOutcome` 协议；固定 Transformer 顺序 |
| `ConversationTitleCoordinator` | 确定性本地标题、模型标题阶段、token/CAS、手动与异步提交串行化、进程内去重与有限重试；不增加持久化字段 |
| `ConversationRepository` | CommandCoordinator 内部使用的 Room 持久化原语 |
| `ApplicationRecoveryCoordinator` | 唯一启动恢复入口；失败时 durable write 保持关闭 |

UI/ViewModel 不持有会话 Repository、Runtime Registry、底层文件/DAO 实现或原始 Conversation 聚合。列表操作只携带
`ConversationSummary`/ID；撤销删除使用 Application 层的 opaque restore token。

## 主链路

```text
用户发送
  │
  ▼
ConversationApplicationService / MasterTurnCoordinator
  ├─ 等待 ApplicationRecoveryGate.Ready
  ├─ 结束上一 turn，并收口需要保留的工具/Child 结果
  ├─ 预处理用户消息与稳定附件引用
  ├─ AppendUserMessage durable command：用户消息与首轮本地标题同事务
  └─ TurnEngine.start
       └─ StartTurn 单事务：assistant 槽 + RUNNING turn fact
            │
            ▼
       GenerationLoop.run
         ├─ limitContext：只改变本次请求
         ├─ System / Memory / Tool prompt
         ├─ Input Transformers
         ├─ Provider stream
         ├─ Output Transformers
         └─ 工具循环与 awaited checkpoint
            │
            ▼
       TurnEngine.bind
         ├─ Messages：TurnHandle/epoch 校验后更新纯内存 activeTurn
         ├─ Checkpoint：CommitCheckpoint durable command
         └─ Finished/异常/取消：FinalizeTurn durable command + sealed TurnOutcome
```

`MasterTurnCoordinator.sendMessage` 在安装唯一 generation Job 时返回 `SendMessageReceipt`。receipt 中的
`userMessageId` 是本次 `AppendUserMessage` 的稳定身份，不代表 durable 提交已经成功；UI 只可用它观察
`ConversationSnapshot.nodes` 中的目标消息并协调滚动等临时表现，不能据此提前发布消息或持有 Runtime Job。

`StartTurn`、`CommitCheckpoint` 与 `FinalizeTurn` 均执行：

```text
reduce → 构造 changed-node delta → Room transaction → publish committed Snapshot
```

Room 事务同时覆盖 header/message node、turn/tool execution、`artifact_reference` 和 FTS。持久化失败会向调用方传播，
Runtime 不发布未提交状态。Streaming 是唯一先发布且不落库的状态；旧 epoch 或不匹配的 turn/message delta返回
`STALE_TURN`，不能覆盖新 turn。

终态提交在同一事务中先把该 turn 遗留的 STARTED tool fact 收为 `CANCELLED` 或 `UNKNOWN`，再 CAS turn 终态；
任一步失败则整体回滚。Turn 状态使用 insert-once + 合法 CAS，终态不可回退，重复同终态幂等。
失败或取消时，Application 终态准备显式接收 TurnEngine 累积的最新 messages，校验同一 `TurnHandle` 后在该投影上
关闭未完成工具；它不能改读 durable nodes，否则会丢失最后一次 checkpoint 之后已经流出的文本或工具 delta。

## 请求构建与 Transformer

`GenerationLoop.generateInternal()` 固定执行：

1. 对非成功历史建立 replay-safe projection，再按配置执行请求级裁剪；完整历史不变。
2. 从同一份上下文构建 Tool system prompt。
3. 组装 Assistant/会话 System、Memory 与 Tool prompt。
4. 运行 `TurnPipelineFactory` 提供的 Input Transformer。
5. 构建模型、采样、输出上限、自定义 Header/Body 与工具参数。
6. 调用 Provider，合并 chunk 和 usage。

Master 的主要输入顺序是 `TimeReminderTransformer`、`PromptInjectionTransformer`、
`PlaceholderTransformer`、`DocumentAsPromptTransformer`、`TemplateTransformer`、
`WorkspaceReminderTransformer`、`ToolArtifactReplayTransformer`、`AttachmentProjectionTransformer`。
Target 由同一工厂装配，共享附件投影语义且同样把 `AttachmentProjectionTransformer` 放在
Template / Workspace 之后；明确差异只有不装配 `ToolArtifactReplayTransformer`。

Streaming Transformer 只改变显示投影；需要持久化的变化必须进入 checkpoint 的
`OutputMessageTransformer.transform()` 或终态落盘 Transformer。

## 工具装配与执行

`MasterTurnCoordinator` 通过 `GenerationToolSetFactory` 装配 Search、Local、Conversation、Workspace、Skill、
Assistant、MCP 与按需附件识别工具；Memory Tool 在每个工具循环 step 按最新状态加入。MCP 连接生命周期仍由
`McpManager` 独立持有。

工具能力判定必须显式传入本次 run 的 `capabilityModel`；主/子生成传入实际 resolved model，
`assistant_inspect` 这类非运行时检查显式解析目标助手的配置模型，不存在“未传模型则启用兼容集”。
`AssistantToolFactory` 构造时必须同时获得 `DelegationCoordinator` 与 `GenerationToolSetFactory`，
不把缺失装配降级为工具运行时错误。

`recent_chats` 与 `conversation_search` 只依赖 `ConversationQueryService`。其中最近会话读取
`ConversationListRecord` 列表形状，不装载 `MessageNode` 树；全文检索仍由同一查询端口限定助手与顶层会话范围。

同一 Assistant message 的 ToolCall 以 `messageId + toolOrdinal` 定位。审批是一批工具的屏障：Pending 全部解决后，
按 ordinal 串行执行。`UpdateToolApproval` 在同一次 reducer 变换中更新 durable node 与 active-turn projection，
Runtime 仅在事务提交成功后发布该投影；因此 UI、Master resume 与 Child `ask_user` 只会观察到同一个终态决定。
工具输出内联在 `UIMessagePart.Tool.output`；Provider 序列化时再展开为各自协议。

Master 生成入口显式区分 `MasterTurnEntry.START` 与 `CONTINUE_APPROVAL`。START 在尚无 active turn 时完成建议清理、
无效消息清理和附件引用精确回填，再由 `TurnEngine.start()` 建立 owner；批准、拒绝和回答都由
`applyToolApprovalDecision` 提交唯一 `UpdateToolApproval` 后进入 CONTINUE_APPROVAL，只复用既有 `TurnHandle` 并调用
`TurnEngine.continueActive()`，不得再次执行结构预检或提交树命令。
结构预检只读取 durable `ConversationSnapshot.nodes`；`renderNodes` 是每次读取都可能新建的显示投影，不能作为持久化输入，
也不能用列表引用身份判断是否需要写入。

`ActiveTurnState.toolCallPhases` 是运行中工具卡片的唯一阶段投影，仍由同一 Runtime snapshot 发布。Provider 首次流出
Tool part 时为 `CALL_STREAMING`；`STEP_COMPLETED` 提交后转为 `READY` 或 `AWAITING_APPROVAL`；`STARTED` execution fact
与结果 checkpoint 提交后依次转为 `EXECUTING` 和终态。工具 output 只表示 Provider 可回放结果，不表示执行中状态。
因此卡片从首次 Tool delta 起即可打开：参数 JSON 尚未闭合时显示原始片段，文生图执行期间显示其 metadata 提供的
queued/generating/persisting/setting-background 子阶段。崩溃恢复、停止和抛异常/超时的标准结果封套分别投影为
`INTERRUPTED`、`CANCELLED`、`FAILED`，不会因 output 非空被误报为完成。工具正常返回的领域失败仍是调用
`COMPLETED`，其业务结果由对应 renderer（例如图片生成失败原因）独立呈现。

会话页和 Drawer 不读取 Runtime 的 coroutine Job。`ConversationPresentation` 从私有 active request 与 durable
snapshot 派生 `IDLE`、`PREPARING`、`GENERATING`、`AWAITING_APPROVAL`、`STOPPING`；审批暂停仍属于同一 turn owner，底部使用不同颜色和问询图标保持明确的
用户注意力提示，且文件夹/消息树结构操作继续受 active owner 保护。完成工具卡片保留 `COMPLETED` 事实，但隐藏常驻
状态文字；失败、拒绝、回答、取消和中断仍显示简短终态。

首条用户消息的纯文本会规范化空白并按 Unicode code point 截断为确定性本地标题，作为 `AppendUserMessage.initialTitle`
与消息在同一事务提交，因此长 turn 不会让会话一直停留在“New Chat”。`ConversationTitlePhase` 明确区分 `EMPTY`、
`LOCAL_FALLBACK`、`MODEL_GENERATING`、`RESOLVED`；`ConversationTitleCoordinator` 是阶段、去重和重试的唯一 owner。
模型请求取得包含 expected title 的 generation token，提交时使用 `UpdateTitleIfCurrent` CAS；Coordinator 直接返回 CAS
结果，非 resident 会话不会为确认标题加载消息树。手动标题与模型标题提交
共享 Coordinator mutex；CAS 返回 expected title 是否匹配，匹配但新旧文本相同也视为成功并收口到 `RESOLVED`，只跳过
不必要的数据库写入。手动提交成功后失效活动 token 与排队重试，因此包括 force 在内的异步结果都不能覆盖请求发出后
产生的手动标题。进程重启后缺少候选 provenance 的非空持久化标题按 `RESOLVED` 保护。标题文本仍使用现有 header 字段，
数据库 schema 不变。

文本工具输出过大且 Workspace 可读时，消息只保留预览，完整内容写入 `/tool_outputs/{executionId}.txt`。
模型不可读取 Workspace 时不生成虚假文件引用。

## Artifact 生命周期

托管文件只有 `ArtifactStore` 持有 metadata 与生命周期能力；`ArtifactPayloadStore` 只做 staging、rename、stat 和
物理删除。创建协议为 staging → CREATING row → 原子 rename → ACTIVE，启动可幂等完成或回滚。

- 草稿发布：消息或 Settings 提交成功后才 `markPublished`。
- 创建补偿：仅 `OwnedArtifact` 可调用 `discardUnpublished`。
- 用户删除：`deleteUserRequested` 先 CAS DELETING，再在统一 Settings roots 锁下 detach，最后删除 payload 与 row。
- GC：只读取索引候选，并在生命周期锁内重验 message refs 与 Settings roots；DELETING artifact 不能建立新引用。
- 引用投影：message delta 与 `artifact_reference` 同事务；损坏节点不会把 backfill 标为完成。
- Settings 完整性：当前写协议不会提交缺少 ACTIVE artifact metadata 的本地 root；启动发现这种根时 fail-closed，不补录、不清除 root 或 payload。

显式删除后的历史引用保留，Replay 投影为 unavailable；系统不会从其他化身自动“复活”用户已删除的文件。

## 上下文与压缩

`Assistant.contextMessageLimit` 只控制本次 Provider 请求。裁剪从完整 USER 轮次边界开始，不改 durable history。

Provider 只返回开头 `<think>` 文本时，`ThinkTagTransformer` 在 Master/Target 共用 output pipeline 以最后一个已执行 Tool 为边界，只把当前 assistant→tool step 的首个非空 `Text` 拆成 `Reasoning` 与回答正文。只有当前 step 已有 Provider 原生 Reasoning 时才禁用该 fallback；已完成 step 的 Reasoning 不得抑制后续 step。闭合标签到达时立即冻结当前 step reasoning 的 `finishedAt`，后续累计正文 chunk 只从上一投影的同一 step 复用首次闭合时间；流结束只为当前 step 未闭合标签补时间。`GenerationLoop` 的 phase 判定同样基于累计 raw message 的当前 step，同一标签内文本只发布 `reasoning_streaming`，标签后的正文出现后才发布 `answer_streaming`；终态提交保留各 step 闭合标签首次投影的完成时间。
消息条数不等于 token 数，长文档、图片、工具 schema 与 System prompt 仍可能占据大量上下文。

显式压缩由 `ConversationApplicationService.compress()` 进入 `GenerationSideEffects.compressConversation()`：生成摘要，
保留指定的最近完整轮次，再以 durable tree command 替换历史。它是用户触发、持久化且不可撤销的操作，不自动挂到
发送链路。

## Runtime 与恢复

`ConversationRuntime` 的 `snapshot` 只包含已经提交的事实；Header command 不清除 active turn，冲突树命令显式结束或
拒绝当前 owner。Runtime 无页面引用且无活跃 Job 后可由 Registry 清理，不存在 `pendingPersist` 或下一命令重试协议。

启动顺序由 `ApplicationRecoveryCoordinator` 固定为：Settings ready → artifact reconcile → reference projection → FTS
projection → Child run recovery → Master turn recovery → pending assistant deletion。任一步失败进入 `Failed(error)`，所有
durable command 继续被 `ApplicationRecoveryGate` 阻断；`retry()` 重新执行同一幂等顺序。

恢复查询只读取非终态 execution 索引。Child/tool 先收口，再提交 owning turn 终态；健康数据库不加载 Conversation 树。
非终态 execution 若已失去 owning Assistant 消息，恢复进入 `Failed`，不发布会话、也不补偿写入 turn/tool 事实；消息载荷损坏同样保持 fail-closed。
正常 supersede/cancel 属于 `TurnFinalization`，Child lineage/retention/delete 属于 `SubAssistantLifecycle`，
`TurnRecovery` 只负责进程恢复。

## 子助手扩展

子助手不是第二套生成引擎。`DelegationCoordinator` 只增加 preflight → materialize Child → run → terminal 四阶段：
Child、tool STARTED 与 childConversationId 关系在 Target 启动前强制提交；失败会补偿 Child 与未发布附件。
`SubAssistantRunGate.withLease` 编码并发所有权，`SubAssistantResultProjection` 负责纯结果形状，Target 仍使用相同的
`TurnEngine`、`GenerationLoop` 与 checkpoint 协议。

## 关键文件

```text
app/src/main/java/net/weero/measix/pilot/
├─ service/
│  ├─ ConversationApplicationService.kt
│  ├─ ConversationQueryService.kt
│  ├─ MasterTurnCoordinator.kt
│  ├─ GenerationSideEffects.kt
│  ├─ ConversationTitleCoordinator.kt
│  ├─ TurnFinalization.kt
│  ├─ TurnRecovery.kt
│  ├─ SubAssistantLifecycle.kt
│  ├─ ApplicationRecoveryCoordinator.kt
│  └─ runtime/
│     ├─ ConversationCommands.kt
│     ├─ ConversationTransition.kt
│     ├─ ConversationRuntime.kt
│     ├─ ConversationRuntimeRegistry.kt
│     ├─ ConversationPresentation.kt
│     ├─ ConversationCommandCoordinator.kt
│     ├─ TurnEngine.kt
│     └─ DelegationCoordinator.kt
└─ data/
   ├─ repository/ConversationRepository.kt
   ├─ files/ArtifactStore.kt
   ├─ files/ArtifactPayloadStore.kt
   └─ ai/GenerationLoop.kt
```
