# 消息生成链路

> 本文档以当前实现为准，说明用户命令、生成流、持久化与恢复的唯一链路。总体 owner 与分层边界见
> [`application-architecture.md`](application-architecture.md)，附件与执行事实细节见
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
| `RequestUsageReducer` / `TurnUsageAccumulator` | 单 Provider 请求 presence overlay 与请求关闭、turn 内恰好一次累计；只产出 owning Assistant 消息的 durable `TokenUsage` |
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
  ├─ 从一次 EffectiveSettingsSnapshot 捕获 prompt/disclosure/MCP/tools 与不可变 TurnRequestContext
  └─ TurnEngine.start
       └─ StartTurn 单事务：assistant 槽 + RUNNING turn fact + 可选 model-context entry
            │
            ▼
       GenerationLoop.run
         ├─ ConversationContextPlanner.planRequest：replay-safe 后按完整 USER 轮次裁剪，只改变本次请求
         ├─ 稳定 System / frozen Tool prompt 与 context-first USER projection
         ├─ Input Transformers
         ├─ Provider stream
         ├─ Output Transformers
         └─ 工具循环与 awaited checkpoint
            │
            ▼
       TurnEngine.bind
         ├─ onMessagesObserved：Producer 同步更新 TurnEngine 唯一最新消息槽，不依赖 UI 交付
         ├─ Messages：TurnHandle/epoch 校验后更新纯内存 activeTurn
         ├─ Checkpoint：CommitCheckpoint durable command
         └─ Finished/异常/取消：FinalizeTurn durable command + sealed TurnOutcome
              （AwaitingApproval 携带 pending locator 列表，消费者不再回消息里扫描）
```

`MasterTurnCoordinator.sendMessage` 在安装唯一 generation Job 时返回 `SendMessageReceipt`。receipt 中的
`userMessageId` 是本次 `AppendUserMessage` 的稳定身份，不代表 durable 提交已经成功；UI 只可用它观察
`ConversationSnapshot.nodes` 中的目标消息并协调滚动等临时表现，不能据此提前发布消息或持有 Runtime Job。
编辑已有 USER 并发送走 `editAndResend`：截断到该 USER node、提交新 USER variant，再按变换后的目标分支
`START`；receipt 的 `userMessageId` 是这个新 variant。纯编辑（长按发送）与编辑 Assistant 只走
`editMessage`，不创建 model-context entry、不启动 turn。

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
`GenerationRequest.onMessagesObserved` 在每次发布前、特别是请求关闭 usage 后的可取消变换之前同步更新
`TurnEngine` 的唯一最新消息槽；`bind` 只消费展示和终态事件，不把迟到展示消息回写该槽。
外部取消仍从这一槽准备终态，因此未返回 usage 的已发起请求也计入请求数和不完整性。
失败准备使用既有 `withoutUnpersistableBase64()` 把未落盘媒体转为 typed 失败占位，保留已落盘文件 part；
未发布资源仍由原 lease 作用域精确补偿。
`STEP_COMPLETED` 与带新输出的 `TOOL_RESULT_COMPLETED` 在显示 local payload 前，先提交 checkpoint、
更新同一最新消息槽，再发布 lease。交接开始前检查取消；已经取得结果/资源所有权后的这一小段提交不可取消，
完成后继续传播取消。提交前取消或提交失败不会把随后被回滚的 local URI 交给终态消息；
checkpoint 成功后即使 lease 发布失败，终态仍使用已有 durable root 的消息，不退回提交前的内容。
Tool Result 的 output transformers 若生成受管文件引用，`TOOL_RESULT_COMPLETED` 必须提交变换后的消息本身，再发布 lease；禁止用 raw result 建 durable checkpoint 却发布只被 presentation 引用的资源。
`TurnOutcome.fromFailure` 是 Master/Target 共用的失败分类点：Provider 失败使用 `ProviderFailureKind.reason` 作为细分稳定码，
并把 `classifyProviderFailure().detail` 的脱敏诊断随 `FinalizeTurn.terminalDetail` 写入 owning Assistant 消息 JSON；
`INCOMPLETE` 保留独立状态与 `provider_incomplete` / limit reason。Turn execution 与消息仍共享同一终态提交，不增加
UI 状态表或第二写入口。没有终态事件便关闭的流同样收口为 `INCOMPLETE`，不能遗留 RUNNING execution。

## 请求构建与 Transformer

每个新 START 只读取并沿整条装配链传递一个 `EffectiveSettingsSnapshot`；新 USER 的输入预处理与该 START 的 Assistant/Model/wire 解析使用同一份 Settings，不能在 `launchRun` 再取第二份。
`ConversationCommandCoordinator.startTurn` 是 START 的唯一 durable 入口：assistant 槽、`turn_execution` 与 model-context entry 由同一个
Room 事务提交，只有 `repository.commit` 成功后才 `publishCommitted` 发布 Runtime snapshot；提交抛错时不发布任何投影，
已提交的 USER 与既有分支保持不变（`ConversationStartAtomicityTest` 与 `ConversationCommandCoordinatorTest` 分别锁定事务回滚与不发布语义）。
START 只建立一个进程内 `TurnRequestContext`：它保存 resolved Assistant/Model、不可变 Provider wire shape、media capability、
`FrozenTurnPromptInputs`、有序 `FrozenToolDefinition` 与同名 execution bindings。`GenerationRequest` 只携带该 context、消息、固定 pipeline
和 checkpoint callback；Provider step、审批/`ask_user` continuation 与重试不得重读 Settings、Workspace、模板或时钟来改变模型可见请求。
Provider API key、Google service-account key/email 等不进入 context。START 以 `ProviderCredentialOwnerLocator` 精确区分顶层 catalog provider 与 model `providerOverwrite` owner；`ProviderTransportLease` 每次请求只从该 exact owner 刷新凭据，并与冻结 endpoint、协议选择、cache 选项和 selected model 组合为临时 transport setting。移动/复制 model id、替换 overwrite owner、owner 重复或 provider identity/type 漂移均 fail-closed，不能重新执行 live model/provider 选择。

`GenerationLoop.generateInternal()` 固定执行：

1. 对非成功历史建立 replay-safe projection，再按配置执行请求级裁剪；完整历史不变。
2. 从同一份上下文构建 Tool system prompt。
3. 组装 Assistant/会话 System 与冻结的 Tool prompt；动态 Memory / 子助手 Catalog 不进入 System，只作为 START 提交的 Disclosure Snapshot 出现在因果 USER 的第一个 part。
4. 运行 `TurnPipelineFactory` 提供的 Input Transformer。
5. 构建模型、采样、输出上限、自定义 Header/Body 与工具参数。
6. 每次真实 Provider 调用建立一个 request usage reducer，合并 chunk，并按字段 presence 覆盖该请求的 usage snapshot。
7. 正常、失败或取消关闭请求后，将它恰好一次加入 turn usage；流式投影可预览，checkpoint/终态仍只持久化 owning Assistant 消息。

Master 的主要输入顺序是 `TimeReminderTransformer`、`PromptInjectionTransformer`、
`PlaceholderTransformer`、`DocumentAsPromptTransformer`、`TemplateTransformer`、
`WorkspaceReminderTransformer`、`ToolArtifactReplayTransformer`、`AttachmentProjectionTransformer`。
Target 由同一工厂装配，共享附件投影语义且同样把 `AttachmentProjectionTransformer` 放在
Template / Workspace 之后；明确差异只有不装配 `ToolArtifactReplayTransformer`。
本次请求由管线合成的内容（System、时间提醒、模式注入、Workspace 提醒及被其改写的 System）由
`RequestMessageOriginTracker` 标记为 synthetic，`TemplateTransformer` 跳过它们，不应用用户的
`messageTemplate`。tracker 是 request-scoped capability，不进入 durable `UIMessage`、不序列化、不跨请求缓存。

Streaming Transformer 只改变显示投影；需要持久化的变化必须进入 checkpoint 的
`OutputMessageTransformer.transform()` 或终态落盘 Transformer。

usage 只归产生请求的 owning Assistant 消息。Master 与每个 Child 各自建立 request reducer 和 turn accumulator；工具前后
属于 Master 的多次请求在 Master turn 内累计，Target usage 只写 Child。完整算法、失败/取消、审批继续和消费者口径见
[`token-usage-accounting.md`](token-usage-accounting.md)。

## 工具装配与执行

`MasterTurnCoordinator` 通过 `GenerationToolSetFactory` 在 START 前装配 Search、Local、Conversation、Workspace、Skill、
Assistant、MCP 与按需附件识别工具；Memory Tool 同期按固定 owner namespace 加入。全部 definitions/bindings 物化一次并在 Turn 内复用，
实际执行仍重验权限、资源、Memory namespace 与远端状态。MCP 连接生命周期由
`McpRuntimeCoordinator` 编排的 per-server `McpServerRuntime` 持有，已验证目录由 `McpCatalogStore` 持久化。Master 与每个 Target
在 run 开始时只为所选 server 做有界并行 preflight；匹配的 durable LKG 可立即捕获，只有缺少目录的 server 才等待
连接。随后固定一次 `TurnMcpCapabilitySnapshot`，后续 step 复用同一 catalog revision。用户手工刷新与远端
`notifications/tools/list_changed` 成功提交后更新后续 turn，当前 run 不漂移；断网、timeout、后台和重试等健康变化
只在调用结果中体现，不撤下 LKG schema。用户禁用/删除/definition 或 policy 明确撤销则在调用发送前 fail-closed。
完整协议见 `mcp-architecture.md`。

工具能力判定必须显式传入本次 run 的 `capabilityModel`；主/子生成传入实际 resolved model，
`assistant_inspect` 这类非运行时检查显式解析目标助手的配置模型，不存在“未传模型则启用兼容集”。
按需识图只取决于有效的 `attachmentInspectionModelId`，不由当前模型容器覆盖或 Workspace 就绪状态否决。
文件清单不是图片内容：`inspect_attachments` 通过 Runtime 注入的只读能力按安全 `/upload` 路径读取内存图片，
不扫描当前会话获取授权，也不创建持久化附件副本。
`AssistantToolFactory` 构造时必须同时获得 `DelegationCoordinator` 与 `GenerationToolSetFactory`，
不把缺失装配降级为工具运行时错误。

`recent_chats` 与 `conversation_search` 只依赖 `ConversationQueryService`。其中最近会话读取
`ConversationListRecord` 列表形状，不装载 `MessageNode` 树；全文检索仍由同一查询端口限定助手与顶层会话范围。

同一 Assistant message 的 ToolCall 以 `messageId + toolOrdinal` 定位。审批是一批工具的屏障：Pending 全部解决后，
按 ordinal 串行执行。`ResolveToolInteraction` 在同一次 reducer 变换中更新 durable node 与 active-turn projection，
Runtime 仅在事务提交成功后发布该投影；因此 UI、Master resume 与 Child `ask_user` 只会观察到同一个终态决定。
工具输出内联在 `UIMessagePart.Tool.output`；Provider 序列化时再展开为各自协议。

审批前通过同一个 step 工具索引确认可用性，再由 `Tool.parseArguments` 严格解析 JSON object 并调用工具自身的纯
`validateArguments`。Provider 的空参数缓冲按无参 `{}` 解释；非空坏 JSON、非 object 或工具参数错误直接返回失败，
不进入审批或执行。`ask_user`、生图等领域校验属于工具定义，循环不维护工具名特判。
无效或已撤销的 Pending 调用清除等待态，并通过既有 `TOOL_RESULT_COMPLETED` 提交 FAILED 结果；同批其余合法 Pending
仍形成审批屏障。用户已 Denied/Answered 的决定不被参数错误覆盖；Approved 不重复审批。执行仍使用同一解析入口，
实际文件、权限、配置与远端状态只在原执行 owner 内复核。未执行的拒绝不创建 `tool_execution` 行。
纯校验返回结构化领域错误，由 `ToolArgumentsException` 保留原字段并补齐标准 `error` / `type: error`；
通用 JSON 错误为 `invalid_arguments`。撤销或审批不可用的 Runtime 拒绝同样携带标准错误标记，重读历史仍显示 FAILED；
不将正常执行返回的领域业务失败改成执行失败。

取消收口对尚无 Child link 的 `assistant_call` 使用通用中断结果：`TurnFinalization` 根据已校验的 turn 与
`tool_execution` ordinal 确认不存在执行事实，或仅有尚未建立 Child link 的 `STARTED` 事实，且 run metadata key 不存在。
已有 Child link 必须与 run metadata 一致；存在但损坏的 metadata、终态执行缺失结果仍 fail-closed，不能降级成未执行调用。

`ToolExecutionContext.reportMetadata` 使用 `ToolMetadataDelivery` 明确交付边界：`STREAMING` 只发布进度投影，
`CHECKPOINT` 提交后再展示，`DEFERRED` 只合并进生成器现有 messages，随接下来的既有 checkpoint 交接。
生图与子助手的最终 metadata 使用 `DEFERRED`，与结果一起提交；新 Child 的初始 metadata 同样随 child link
checkpoint 交接。未提交的文件引用不会提前进入显示或取消终态，不新增 metadata 缓冲或持久化字段。

Master 生成入口显式区分 `MasterTurnEntry.START` 与 `CONTINUE_USER_INTERACTION`。START 在尚无 active turn 时完成建议清理、
无效消息清理和附件引用精确回填，再由 `TurnEngine.start()` 建立 owner；批准、拒绝和回答都由
`applyToolUserDecision` 提交唯一 `ResolveToolInteraction` 后进入 CONTINUE_USER_INTERACTION，只复用既有 `TurnHandle` 并调用
`TurnEngine.continueActive()`，不得再次执行结构预检或提交树命令。
结构预检只读取 durable `ConversationSnapshot.nodes`；`renderNodes` 是每次读取都可能新建的显示投影，不能作为持久化输入，
也不能用列表引用身份判断是否需要写入。

`ActiveTurnState.toolCallPhases` 是运行中工具卡片的唯一阶段投影，仍由同一 Runtime snapshot 发布。Provider 首次流出
Tool part 时为 `CALL_STREAMING`；`STEP_COMPLETED` 提交后转为 `READY` 或 `AWAITING_APPROVAL`；`STARTED` execution fact
与结果 checkpoint 提交后依次转为 `EXECUTING` 和终态，同一生成流在这两类影响通知的 checkpoint 后补发 presentation tick，使通知投影即使没有后续 Provider chunk 也只观察已提交阶段。Live Update 对文本增量保留节流，但执行 ordinal 从空到有、从有到空或在工具间切换时立即发布，不能被文本节流窗口吞掉。工具 output 只表示 Provider 可回放结果，不表示执行中状态。
因此卡片从首次 Tool delta 起即可打开：参数 JSON 尚未闭合时显示原始片段，文生图执行期间显示其 metadata 提供的
queued/generating/persisting/setting-background 子阶段。崩溃恢复、停止和抛异常/超时的标准结果封套分别投影为
`INTERRUPTED`、`CANCELLED`、`FAILED`，不会因 output 非空被误报为完成。工具正常返回的领域失败仍是调用
`COMPLETED`，其业务结果由对应 renderer（例如图片生成失败原因）独立呈现。

会话页和 Drawer 不读取 Runtime 的 coroutine Job。`ConversationPresentation` 从私有 active request 与 durable
snapshot 派生 `IDLE`、`PREPARING`、`GENERATING`、`AWAITING_APPROVAL`、`STOPPING`；审批暂停仍属于同一 turn owner，底部使用不同颜色和问询图标保持明确的
用户注意力提示，且文件夹/消息树结构操作继续受 active owner 保护。`STOPPING` 期间不再提供工具审批、问题回答或 Target 交互入口。后台通知将 `AWAITING_APPROVAL` 保留为独立可操作状态，只有 `Completed` 终态才发送“已完成”通知，失败、取消和 incomplete 只清理 live update。流式增量可用有界 `tryEmit` 降压，但待审批与终态使用可挂起 `emit`，不得因 buffer 满遗留 ongoing 通知；应用进入前台时撤销已跟踪的 live/待审批通知。完成工具卡片保留 `COMPLETED` 事实，但隐藏常驻
状态文字；失败、拒绝、回答、取消和中断仍显示简短终态。

`ConversationUiModel.turnFeedback` 由 `projectConversationTurnFeedback` 只读派生，不建立新的运行事实。
准备阶段允许尚无 Assistant 的有效 request；生成阶段必须有与 active turn 匹配的 request，残留 durable turn 不产生工作心跳。
输出量 `outputCharacters` 只取 owning Assistant 的正文、思考文字和工具输入的 UTF-16 长度之和，不计工具 replay output、metadata 或媒体 URL；该只读数值仅近似触觉所需的输出量，不是 token 用量。首次 streaming projection 尚未发布时，从同一 snapshot 末节点当前选中的 owning Assistant 槽位取得基线，且严格匹配 `assistantMessageId`，不扫描历史节点；尚无槽位时为未知长度，不伪装为空输出。
待审批可由 durable 状态投影；主 turn 仍在生成阶段时，
只有当前 `EXECUTING` 的 `assistant_call` 且 metadata 为 `RUNNING` / `AWAITING_USER` 并携带有效 `ask_user` 交互才视为待回答。
查询投影负责识别这些领域状态，前台页面只负责触觉节拍、效果与取消：工作使用轻振，新的待审批或待回答使用间隔 200ms 的两次重击，等待期间不继续工作心跳或周期催促；声音和后台通知协议不变。

Master 的 `FAILED` / `INCOMPLETE` 由 `ChatErrorStore` 投影为当前会话底部诊断卡，卡片只允许用户关闭，不自动超时；
卡片标题由 durable reason 映射为限流、额度、鉴权、权限、政策、无效请求、服务不可用、Provider、本地运行或未完成，
正文读取消息上的脱敏 `terminalDetail`。关闭卡片后，消息终态短标签仍可点击并从同一消息事实重新打开；Store 只按
`conversationId` 展示当前会话，不能把别的会话错误串入。标题、建议、压缩和发送/重生成/审批等边缘或命令失败仍是
5 秒短暂卡片。取消与 supersede 不进入错误卡片；无可见内容的 CANCELLED Assistant 槽在 UI 投影中隐藏，部分输出则保留
并显示“已停止”或“已被新回复替换”。工具和子助手的领域失败继续由各自卡片展示，不重复抬成 Master 错误卡。

首条用户消息的纯文本会规范化空白并按 Unicode code point 截断为确定性本地标题，作为 `AppendUserMessage.initialTitle`
与消息在同一事务提交，因此长 turn 不会让会话一直停留在“New Chat”。`ConversationTitlePhase` 明确区分 `EMPTY`、
`LOCAL_FALLBACK`、`MODEL_GENERATING`、`RESOLVED`；`ConversationTitleCoordinator` 是阶段、去重和重试的唯一 owner。
模型请求取得包含 expected title 的 generation token，提交时使用 `UpdateTitleIfCurrent` CAS；Coordinator 直接返回 CAS
结果，非 resident 会话不会为确认标题加载消息树。手动标题与模型标题提交
共享 Coordinator mutex；CAS 返回 expected title 是否匹配，匹配但新旧文本相同也视为成功并收口到 `RESOLVED`，只跳过
不必要的数据库写入。手动提交成功后失效活动 token 与排队重试，因此包括 force 在内的异步结果都不能覆盖请求发出后
产生的手动标题。进程重启后缺少候选 provenance 的非空持久化标题按 `RESOLVED` 保护。标题文本仍使用现有 header 字段，
数据库 schema 不变。

`ConversationContextPlanner` 同时负责请求窗口和成功 Provider step 后的 Tool Result 压缩候选。完整工具结果先经
`TOOL_RESULT_COMPLETED` durable checkpoint 保存，并且只有被成功 `ModelStepReceipt` 保守确认进入最终 Provider 请求投影后，
才允许压缩。inline Tool 文本达到 48K estimated tokens 才触发，按 16K 低水位、24K 整批最小净回收量、最近两个 typed
批次和最近 8K estimated tokens 保护规则选择；不额外冻结整个已完成 USER turn。候选必须有显式 `completed` / `failed`
终态，且 `originalEstimatedTokens - markerEstimatedTokens >= 128`。估算规则按每个 Tool Result 独立计算：ASCII 字母与空白约每 4 个
code point 1 token，连续 ASCII 数字段约每 3 位 1 token，连续 ASCII 符号段约每 2 个 1 token，其他 Unicode code point 各计 1；
全部生产阈值只来自 `ContextTrimmingPolicy.kt`，Provider input token 与 cache 命中百分比
都不参与决策。`PRESERVE`、混合媒体、Provider opaque replay、已归档结果与 `denied` / `answered` 不参与。
回查工具结果标为 `REGENERABLE_TEXT`，满足同一滚动规则时只折叠固定 marker，不创建新的 Artifact。
一次执行只要登记过 unpublished Artifact，Runtime 对成功和失败结果都强制 `PRESERVE`，不把产物交付引用交给 planner 猜测。

`ToolOutputStore` 将可归档文本规范化后暂存为 `ArtifactStore` 管理的 `tool_outputs` Artifact；可再生文本不复制 payload。
marker replacement 只进入局部 `checkpointMessages`；其中旧 Tool Result 通过 locator + marker + 可空 archive 的 typed
`toolOutputCompactionPatches` delta 与本 step 的
`STEP_COMPLETED` 共用事务，不能只提交最后一条 Assistant。checkpoint 成功后才发布 lease 和 marker projection，失败则
保留原完整 inline output 并精确回滚暂存 Artifact。模型只通过稳定注册的 `read_tool_output` / `grep_tool_output` 按当前
conversation 的 `TOOL_OUTPUT` reference 回查；UI 只显示消息中的 durable 归档摘要，不提供正文回读，也不触发文件 IO。
任何路径都不披露私有文件路径或依赖 Workspace shell。

## Artifact 生命周期

托管文件只有 `ArtifactStore` 持有 metadata 与生命周期能力；`ArtifactPayloadStore` 只做 staging、rename、stat 和
物理删除。创建协议为 staging → CREATING row → 原子 rename → ACTIVE，启动可幂等完成或回滚。

- 草稿发布：消息或 Settings 提交成功后才经 `publishUnpublished` / `publishAllUnpublished` 交接所有权。
- 创建补偿：仅 `OwnedArtifact` 可调用 `discardUnpublished`。
- 用户删除：`deleteUserRequested` 先 CAS DELETING，再在统一 Settings roots 锁下 detach，最后删除 payload 与 row。
- GC：只读取索引候选，并在生命周期锁内重验 message refs 与 Settings roots；DELETING artifact 不能建立新引用。
- 引用投影：message delta 与 `artifact_reference` 同事务；损坏节点不会把 backfill 标为完成。
- Settings 完整性：写入要求 ACTIVE artifact。启动时发现背景/头像缺少 metadata 或 payload，先持久化默认显示偏好；不扫描认领遗留文件。仍被消息引用的 ACTIVE artifact 缺少 payload 则 fail-closed，详见多模态参考的文件可用性与恢复约定。

显式删除后的历史引用保留，Replay 投影为 unavailable；系统不会从其他化身自动“复活”用户已删除的文件。

## 上下文与压缩

`Assistant.contextMessageLimit` 只控制本次 Provider 请求。裁剪从完整 USER 轮次边界开始，不改 durable history。

Provider 只返回开头 `<think>` 文本时，`ThinkTagTransformer` 在 Master/Target 共用 output pipeline 以最后一个已执行 Tool 为边界，只把当前 assistant→tool step 的首个非空 `Text` 拆成 `Reasoning` 与回答正文。只有当前 step 已有 Provider 原生 Reasoning 时才禁用该 fallback；已完成 step 的 Reasoning 不得抑制后续 step。闭合标签到达时立即冻结当前 step reasoning 的 `finishedAt`，后续累计正文 chunk 只从上一投影的同一 step 复用首次闭合时间；流结束只为当前 step 未闭合标签补时间。`GenerationLoop` 的 phase 判定同样基于累计 raw message 的当前 step，同一标签内文本只发布 `reasoning_streaming`，标签后的正文出现后才发布 `answer_streaming`；终态提交保留各 step 闭合标签首次投影的完成时间。
消息条数不等于 token 数，长文档、图片、工具 schema 与 System prompt 仍可能占据大量上下文。`ChatSizeChecker` 的
上下文预警阈值只使用最终请求投影在发送前按稳定规则估算出的 `latestRequestEstimatedContextTokens`；缺失时不以 turn
累计 input 猜测。它与前述 Tool Output 滚动压缩共用估算口径，但预警只读取最近一次实际发送请求的快照，不参与压缩决策。

显式压缩由 `ConversationApplicationService.compress()` 进入 `GenerationSideEffects.compressConversation()`：生成摘要，
保留指定的最近完整轮次，再以 durable tree command 替换历史。它是用户触发、持久化且不可撤销的操作，不自动挂到
发送链路。

## Runtime 与恢复

`ConversationRuntime` 的 `snapshot` 只包含已经提交的事实；Header command 不清除 active turn，冲突树命令显式结束或
拒绝当前 owner。Runtime 无页面引用且无活跃 Job 后可由 Registry 清理，不存在 `pendingPersist` 或下一命令重试协议。

启动顺序由 `ApplicationRecoveryCoordinator` 固定为：Settings ready → artifact reconcile → generated media reconcile →
reference projection → FTS projection → Child run recovery → Master turn recovery → pending assistant deletion。任一步失败进入 `Failed(error)`，所有
durable command 继续被 `ApplicationRecoveryGate` 阻断；`retry()` 重新执行同一幂等顺序。

用户可见发起/继续 Master turn 后，`GenerationForegroundLifetime.ensureStarted` 请求 Android 保活；
`ChatGenerationForegroundService` 只消费 `ConversationQueryService.conversationActivities()`，只要存在
`RESPONSE_GENERATION` 就保持前台，全部结束或只剩 `APPROVAL_REQUIRED`/`TITLE_GENERATION` 即停止。service
不保存 generation id、turn map、Job 或消息，不调用 `KeepScreenOn`，也不操作 Window flag；生成、取消、终态
与恢复仍由 Runtime/TurnEngine 唯一拥有。Android 15+ dataSync 的 `onTimeout(startId, fgsType)` 会先立即
`stopSelf(startId)` 释放平台配额，再由 AppScope 经 application command 请求停止所有 projected active Master turn。

恢复查询只读取非终态 execution 索引。命中候选后，`TurnRecovery` 与 SubAssistant retention 只能通过 `getConversationSnapshotById` / `getChildConversationSnapshots` 装载 validated aggregate，不能从公共 `Conversation` 重建一个丢失 model context 的 snapshot。Child/tool 先收口，再提交 owning turn 终态；健康数据库不加载 Conversation 树。
非终态 execution 若已失去 owning Assistant 消息，或 context 的 owner/anchor/message role/canonical envelope 损坏，恢复进入 `Failed`，不发布会话、也不补偿写入 turn/tool 事实；消息载荷损坏同样保持 fail-closed。
正常 supersede/cancel 属于 `TurnFinalization`，Child lineage/retention/delete 属于 `SubAssistantLifecycle`，
`TurnRecovery` 只负责进程恢复。

## 子助手扩展

子助手不是第二套生成引擎。`DelegationCoordinator` 只增加 preflight → materialize Child → run → terminal 四阶段：
Child、tool STARTED 与 childConversationId 关系在 Target 启动前强制提交；失败会补偿 Child 与未发布附件。
`SubAssistantRunGate.withLease` 编码并发所有权，`SubAssistantResultProjection` 负责纯结果形状，Target 仍使用相同的
`TurnEngine`、`GenerationLoop` 与 checkpoint 协议。共享生成实现不共享 turn usage 状态，详见
[`token-usage-accounting.md`](token-usage-accounting.md)。

## 关键架构文件

| 边界 | 文件 |
| --- | --- |
| UI command / query port | `app/src/main/java/net/weero/measix/pilot/service/ConversationApplicationService.kt`、`ConversationQueryService.kt` |
| Master turn 编排 | `app/src/main/java/net/weero/measix/pilot/service/MasterTurnCoordinator.kt`、`GenerationSideEffects.kt`、`ConversationTitleCoordinator.kt` |
| 命令与 Runtime 主链 | `app/src/main/java/net/weero/measix/pilot/service/runtime/ConversationCommands.kt`、`ConversationTransition.kt`、`ConversationRuntime.kt`、`ConversationRuntimeRegistry.kt`、`ConversationCommandCoordinator.kt`、`TurnEngine.kt` |
| UI 只读投影 | `app/src/main/java/net/weero/measix/pilot/service/runtime/ConversationPresentation.kt` |
| 终态与恢复 | `app/src/main/java/net/weero/measix/pilot/service/TurnFinalization.kt`、`TurnRecovery.kt`、`ApplicationRecoveryCoordinator.kt` |
| Provider 循环 | `app/src/main/java/net/weero/measix/pilot/data/ai/GenerationLoop.kt` |
| 工具装配 | `app/src/main/java/net/weero/measix/pilot/data/ai/tools/GenerationToolSetFactory.kt` |
| Durable conversation / artifact | `app/src/main/java/net/weero/measix/pilot/data/repository/ConversationRepository.kt`、`data/files/ArtifactStore.kt`、`ArtifactPayloadStore.kt` |
| 子助手扩展 | `app/src/main/java/net/weero/measix/pilot/service/runtime/DelegationCoordinator.kt`、`service/SubAssistantLifecycle.kt` |
