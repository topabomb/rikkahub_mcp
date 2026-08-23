# 消息生成链路

> 本文档以 Measix Pilot 当前代码为准，说明从用户发送消息到模型回复落盘的完整数据流。
> 附件事实、请求投影与 Turn/Tool 持久化不变量见 [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)。
> 上下文裁剪与压缩的设计取舍见 [`docs/dev/context-management.md`](../dev/context-management.md)。
> 模型可见的提示词、注入与工具文案见 [`prompts-and-tools.md`](prompts-and-tools.md)。

## 核心职责

| 类型 | 职责 |
|------|------|
| `ChatService` | 会话入口与编排：装配 Turn 输入、提交域命令、消费 TurnEvent 副作用（TTS/通知）；不实现第二套 chunk 落库协议 |
| `ConversationRuntime` | 单会话事实源：`commandMutex` 单写、`snapshot` 唯一状态流（`ConversationSnapshot`，无兼容投影）、`applyStreamingDelta` 内存态、`submit` 差异落库 |
| `TurnEngine` / `TurnPipelineFactory` | 生成期 chunk→持久化的统一提交协议（Master/Target 共享，turn 骨架唯一实现 `TurnEngine.start`）；Transformer 装配 |
| `GenerationSideEffects` | 生成副作用域：音效反馈 + 标题/建议/压缩等会话衍生数据生成（共用后台生成骨架） |
| `TurnRecovery` | 中断/崩溃恢复语义唯一所有者：master turn 启动恢复、子助手 run 定点收口、取消收口原语、retention |
| `ConversationRepository` | 单事务 `applyMutation`（message tree + 执行事实）；投影（FTS / artifact_reference）事务后维护 |
| `InputMessageTransformer` | 请求发送前，以固定顺序变换消息 |
| `OutputMessageTransformer` | 流式显示、持久化变换和生成结束后的收口处理 |
| Provider 实现 | 把统一的 `UIMessage` / `TextGenerationParams` 转为 OpenAI、Claude、Google 等协议 |

## 主链路

```text
用户发送
  │
  ▼
ChatService.sendMessage()
  ├─ 以旧 turnId 记录取消原因，取消并等待上一生成 Job
  ├─ finishInterruptedPendingTools()
  ├─ preprocessUserInputParts()：执行 USER 范围正则
  ├─ 追加 UIMessage(USER) 到 Conversation.messageNodes
  └─ launchRun()
       │
       ├─ 解析当前 Assistant 与可用 CHAT Model
       ├─ 分配稳定 turnId / assistantMessageId，原子提交 RUNNING 响应槽
       ├─ 校验消息与工具能力
       ├─ 装配 Input/Output Transformers
       ├─ 装配 Search / Local / Conversation / Workspace / Skill / MCP Tools
       └─ GenerationHandler.generateText()
            │
            ├─ 每个 step 构建 Memory Tools
            ├─ 若没有待恢复的工具，进入 generateInternal()
            │    ├─ limitContext()：可选的请求级阶梯裁剪
            │    ├─ 构建 System：助手提示 + Memory + Tool systemPrompt
            │    ├─ 顺序执行 Input Transformers
            │    ├─ 构建 TextGenerationParams
            │    └─ Provider streamText() / generateText()
            │
            ├─ 先对 terminal 历史执行 replaySafeProjection，再构建请求
            ├─ 合并 chunk，执行 Output Transformers 并持续 emit
            ├─ 结束当前 step，写入 finishedAt
            ├─ 无工具调用：结束
            ├─ 工具待审批：写入 Pending 后暂停
            └─ 工具可执行：提交 STARTED 后执行；逐工具提交结果，再进入下一 step
                 │
                 ▼
TurnEngine.bind()（唯一提交协议，Master 与 Target 共用；ChatService / DelegationCoordinator 只消费副作用）
  ├─ chunk → Streaming：ConversationRuntime.applyStreamingDelta 流式投影（纯内存，不落库）
  ├─ 调用方经 AppEventBus / TurnEvent 做进度、声音、通知、卡片 Phase 等副作用
  ├─ awaited onCheckpoint → CommitCheckpoint 命令：delta 节点写入、turn 与可选工具 execution
  │   事实在一个 Room transaction 提交（ConversationRepository.applyMutation）
  └─ bind 在 Finished / 异常 / 取消时提交 FinalizeTurn（不再由 ChatService 第二套 collect 终态落库）：
     - COMPLETED：收口 turn，再异步生成标题和建议回复
     - STEP/INTERACTION_LIMIT：保存 INCOMPLETE，不启动完成副作用
     - CANCELLED / FAILED / Provider INCOMPLETE：保留部分内容和明确终态
     - AWAITING_APPROVAL：保存可恢复挂起状态，不启动完成副作用
     自动标题只在标题仍空白时发起；同一会话进程内同时只跑一次，
     被挡住的触发在当前请求结束后另起会话引用任务补一次。
     实际 LLM 请求最多 MAX_AUTO_TITLE_GENERATION_ATTEMPTS 次。
     缺模型不计入次数。空白结果不写回。抽屉里的手动重新生成不受该上限。
     标题和建议只经 UpdateHeader 命令 patch 对应列，禁止用旧 Conversation 整对象覆盖消息树。
```

工具结果在统一模型中内联于 `UIMessagePart.Tool.output`，不会作为持久化的
`MessageRole.TOOL` 消息单独插入；各 Provider 在序列化请求时再展开为自己的 tool call/result
协议。

## 请求构建顺序

`GenerationHandler.generateInternal()` 的顺序是有意固定的：

1. 排除仅用于持久化的空 in-flight assistant，对非成功 assistant 建立 `replaySafeProjection()`，再计算
   `contextMessages`。未启用自动裁剪时保持投影列表；启用后只改变本次请求，不改写 `Conversation`。
2. 使用同一份 `contextMessages` 构建 Tool system prompt，避免工具提示和实际发送历史看到不同上下文。
3. 组装 System message：

   - 对话允许自定义 System 时使用会话级提示，否则使用 Assistant 提示；
   - 按 Assistant 配置追加隔离记忆或全局记忆；
   - 追加启用工具的 system prompt。

4. 把 System 与 `contextMessages` 合并后执行 Input Transformer 管道。
5. 构建模型、采样、输出上限、自定义 Header/Body、工具等请求参数。
6. 调用 Provider；返回的 usage 合并到最后一条助手消息。

`Assistant.maxTokens` 是模型的输出上限，不是输入上下文窗口，也不参与历史裁剪阈值计算。

## Input Transformer 管道

Master 路径按 `ChatService` 当前装配顺序执行，后一个接收前一个的输出：

| 顺序 | Transformer | 作用 |
|------|-------------|------|
| 1 | `TimeReminderTransformer` | 启用时按消息自身时间戳注入跨时段提醒 |
| 2 | `PromptInjectionTransformer` | 注入 Assistant 或会话绑定的 Mode Injection |
| 3 | `PlaceholderTransformer` | 替换内置占位符 |
| 4 | `DocumentAsPromptTransformer` | 将文档附件内容注入提示 |
| 5 | `TemplateTransformer` | 按消息自己的 `createdAt` 渲染模板，避免历史文本每轮变化 |
| 6 | `WorkspaceReminderTransformer` | Workspace Shell 就绪时向 System 追加环境和路径约束 |
| 7 | `ToolArtifactReplayTransformer` | 按 artifact metadata 物化历史 Tool Result 的路径和 Image URL |
| 8 | `AttachmentProjectionTransformer` | 递归顶层与 `Tool.output`：当前模型接收 IMAGE 时保留原图并附稳定引用行，否则只保留引用行并追加一次 capability hint |

Target 复用相同附件投影语义，Transformer 装配统一走 `TurnPipelineFactory.targetInput()/targetOutput()`（与 Master 共用管道工厂，仅 Target 侧差异化选择项）；它不是 Master 顺序的机械复制。子助手差异见 [`sub-assistant-multimodal.md`](sub-assistant-multimodal.md)。

`PromptInjectionTransformer` 支持 `BEFORE_SYSTEM_PROMPT`、`AFTER_SYSTEM_PROMPT`、
`TOP_OF_CHAT`、`BOTTOM_OF_CHAT` 和 `AT_DEPTH`。插入点会避开用户消息与其工具回复之间的边界。

## Output Transformer 管道

| 时机 | 接口 / 方法 | 当前职责 |
|------|------|----------|
| 流式 chunk 到达 | `StreamingMessageTransformer.transformStreaming()` | 只变换最后一条消息（单消息通道），`ThinkTagTransformer` 等展示变换在此生效，历史消息 immutable |
| 流式结束 | `StreamingMessageTransformer.onStreamingFinish()` | 最后一条消息的收尾变换 |
| checkpoint 提交前 | `OutputMessageTransformer.transform()` | `RegexOutputTransformer` 等真实变换，结果进入持久化事实 |
| 单步生成完成 | `Base64ImageToLocalFileTransformer` 等 | 提取最终 reasoning、将 base64 图片落盘 |

`transformStreaming()` 不应修改持久化事实（流式投影永不落库）；最终需要保存的变化必须进入
`transform()`（checkpoint 路径）或由落盘 Transformer 在终态完成。

## 工具装配与执行

`ChatService.launchRun()` 按以下顺序装配工具：

1. Search Tools：`shouldUseExternalWebSearch(assistant, model)` 为真时（助手开启外挂搜索，且模型未带 `BuiltInTools.Search`）；
2. Local Tools：JavaScript、时间、剪贴板、语音播报、向用户提问、屏幕使用时间、日历、条件注册的 `generate_image` 等；
3. Conversation Tools：允许引用近期对话时；
4. Workspace Tools：绑定 Workspace 且 Shell 状态为 `READY` 时；
5. Skill Tools：Assistant 启用了 Skill 时；
6. Assistant Tools：按 Caller 权限装配 `assistant_manage`、`assistant_inspect`、`assistant_call`；
7. MCP Tools：仅取 Assistant 选中且当前已连接、名称合法的服务；
8. Memory Tools：不属于上述静态列表，由 `GenerationHandler` 在每个工具循环 step 按当前记忆状态加入。

运行时附件识别工具由 `GenerationToolSetFactory` 根据本次 resolved model 与附件识别模型配置加入；它不依赖当前消息是否含图片，因此同一 run 的 schema 不随消息内容改变。

工具审批状态：

```text
Auto
  ├─ 无需审批 ───────────────► 执行
  └─ 需要审批 ─► Pending
                    ├─ Approved ─► 执行
                    ├─ Denied ───► 写入拒绝结果
                    └─ Answered ─► 写入用户答案
```

同一 Assistant message 含多个 ToolCall 时，审批是整批屏障：只要任一调用仍为 Pending，本批自动工具不
执行；全部 Approved/Denied/Answered 后，按 Tool part ordinal 串行处理。审批更新、执行结果合并和
截断文件定位均使用 `messageId + toolOrdinal`，Provider `toolCallId` 只用于线协议。

文本工具输出超过 32 KiB 且当前具备 Workspace Shell 时，只在消息中保留 4 KiB 预览，
完整内容保存到 `/tool_outputs/{executionId}.txt`，其中 `executionId` 由 `messageId + toolOrdinal`
构成，不使用 Provider `toolCallId`。没有 Shell 访问能力时不生成模型无法读取的
文件引用。

MCP 连接、休眠与恢复不由生成链路持有；`McpManager` 负责连接状态和工具快照，
`ChatService` 只在每次请求装配当前可用工具。生命周期细节见
[`docs/dev/mcp-lifecycle-analysis.md`](../dev/mcp-lifecycle-analysis.md)。

独立文生图页面与 `generate_image` 共用 `ImageGenerationSelectionResolver`、
`ImageGenerationCoordinator` 和 `GeneratedMediaStore`。队列是进程内 FIFO 单工，不持久化、
不在重启后重放。Gallery 原图、聊天 Tool Result 与 Assistant 背景必须是不同所有权的文件。
下一轮请求会通过 `ToolArtifactReplayTransformer` 按 artifact metadata 重写历史 Tool Result
的 `/upload/<file>` 与 Image URL；找不到文件时不得伪造可读路径，也不得把历史执行
`status=completed` 改写成 `failed`。可用性写在 `file.available=false` 与 `file.reason=artifact_missing`。
UI 层用 `resolveImageGenerationUiState()` 把执行结果与 artifact 可用性投影为独立维度：
`completed + 文件缺失` 解析为 `CompletedArtifactUnavailable`，显示「图片已生成 · 文件不可用」，
不进入失败分支；`artifact_missing` 不在 `imageGenerationFailureStringRes()` 的 failure 映射内。

Conversation 状态更新不等于文件 GC。物理删除分两套语义：

- 自动清理（删除消息/会话/分支、压缩历史、孤儿回收）必须经过
  `ArtifactStore.collectUnreferencedArtifacts()` 按 `collectFileReferenceTokens()`
  做全局引用检查（含 `artifact_reference` 投影与 Assistant metadata 引用），有引用绝不删除；
  反序列化失败时保留文件。
- 用户在文件管理页明确确认的永久删除属于 destructive operation，由
  `ArtifactStore.deletePermanently()` 协调（CAS 幂等，IN_PROGRESS 并发拒绝）：先原子解除
  Assistant background/avatar 等可变当前引用（Settings 写入成功才继续，失败则保留文件），
  再执行物理删除；历史 Conversation 引用保持不动，Replay 投影为不可用。
  显式删除造成的 `artifact_missing` 是正常状态，不得从 Gallery canonical 图
  自动复制回来「复活」文件，否则违反用户删除意图。

`FilesManager.deleteManagedFilePermanently()` / `deleteManagedFolderPermanently()` 是
unchecked destructive 原语，禁止当作自动 GC 使用。

`OpenAIProvider.generateImage` / `editImage` 走 `/images/generations` 与 `/images/edits`，
失败时抛带 HTTP 状态和 `error.code` / `error.type` 的 `HttpException`，不再把原始响应体塞进
`error()`。`ImageGenerationCoordinator` 用 `classifyProviderFailure()` 把异常分成政策拒绝、
限流、额度、鉴权、权限、非法请求、服务不可用、其余 Provider 错误和运行时错误。
OpenAI Images 的 `moderation_blocked` / `content_policy_violation`，以及 xAI Imagine 的
`respect_moderation=false`，都记为 `content_blocked`。HTTP 成功但带 `error` 信封且没有图片时，
按信封分类（政策拒绝仍是 `content_blocked`，限流、额度等保持对应 kind）。
`data` 为空或 200 没有图片、也没有审核标记时记为 `invalid_result`。
工具只把稳定 `reason` 和裁剪后的 `detail` 回给模型；政策类 `detail` 不回传检查类型。

## 上下文、缓存与压缩

### 请求级阶梯裁剪

`Assistant.contextMessageLimit` 默认是 `0`，表示不自动裁剪。用户显式开启后：

- UI 默认阈值为 80，允许范围为 40～512；
- 消息数首次越过阈值时，保留量回落到约一半；
- 后续在同一台阶内只追加尾部消息，不移动历史起点；
- 起点回退到最近的 USER 消息，确保保留完整对话轮次和其中的工具调用；
- 只影响发给 Provider 的 `internalMessages`，完整历史仍保存在会话中。

消息条数不等于 token 数。单条长文档、图片、工具 schema、Tool Result 或 System prompt 都可能
占用大量上下文，因此该阈值是可选的成本/历史长度控制，不是模型窗口溢出的可靠防线。

### 显式语义压缩

用户可通过“压缩上下文”调用 `GenerationSideEffects.compressConversation()`（生成副作用域，`ChatService` 暴露 `sideEffects` 入口）：

- 较早历史交给压缩模型生成摘要；
- 至少保留用户指定数量的最近消息，并把切点回退到完整 USER 轮次；
- 大批历史分块时同样优先在 USER 轮次边界拆分；
- 摘要和最近历史会替换当前会话的旧消息，这是持久化且不可撤销的操作。

摘要的“目标 token”是给模型的输出指令，不是本地 tokenizer 的硬校验。当前没有把语义压缩
自动挂到发送链路，以免在缺少准确模型窗口与 token 计量时静默丢失制造分析中的约束、结论或证据。

完整决策、已知边界和后续 token 预算方案见
[`docs/dev/context-management.md`](../dev/context-management.md)。

## 会话与通知生命周期

`ConversationRuntime` 负责单会话状态（`service/runtime/`）：

- 页面通过 `acquire()` / `release()` 维护引用；
- 生成 Job 活跃时即使页面离开也保留 Runtime；
- 持久化失败标记 `pendingPersist` 未清除时禁止回收；落库成功后重新安排空闲检查；
- 无引用、未生成且无 `pendingPersist` 时，空闲超时后由 `ConversationRuntimeRegistry` 清理；
- 取消原因与 active turnId 绑定，旧 Job 的结束回调不能清除新 turn 状态；
- 生成结束、失败或取消都通过 `AppEventBus` 发布事件；
- `ChatNotificationManager` 独立消费事件，生成主链路不直接持有通知状态。

应用启动后、接受新的聊天操作前，`TurnRecovery`（恢复语义唯一所有者）会恢复遗留 `CREATED/RUNNING`：assistant 消息标记
`INTERRUPTED`，仍为 `STARTED` 的工具标记 `UNKNOWN` 并写入不可自动重试的结果。恢复按会话分组定点加载（`turn_execution` 状态索引查询，DAO JOIN 过滤 Child），不重复反序列化。UI 保留原始部分内容；
下一次 Provider 请求只读取安全投影。

## 子助手生成管线扩展

子助手不是另一套生成引擎。它由 `DelegationCoordinator` 在通用 Generation Pipeline 外增加四阶段编排（preflight → materialize Child → run → terminal）；并发门禁（lease + pending ask_user）归 `SubAssistantRunGate`，恢复语义归 `TurnRecovery`，结果形状纯函数归 `SubAssistantResultProjection`。Target 内部仍使用同一 `GenerationHandler`、附件投影、Provider、工具循环和 checkpoint。

通用管线为此提供：

- `ToolExecutionContext(messageId, toolOrdinal, resolveAttachments)`，精确定位本地 ToolCall，并只暴露最小附件解析能力，不依赖 Provider `toolCallId` 或完整 Conversation
- `GenerationChunk.Messages/Phase/Checkpoint/Finished`，区分消息更新、阶段、持久化边界和结束原因
- 每个模型 step 重建工具的 `toolProvider`
- Target 非交互审批策略，以及可由宿主承接的 `ask_user` 例外
- Master/Child 共用的 `ConversationRuntimeRegistry`

`assistant_call` 同步完成以下流程：校验 Caller、Target、访问与模型；解析当前 Master 分支的 lineage；经 `SubAssistantRunGate` 获取 Master/Target lease；用最新 Settings 重验；新建、复用或克隆 Child；运行 Target；持续保存 Child 与 Master metadata；最后返回成功内容或稳定失败原因。lease 与交互等待器由 executeCall 的唯一 `finally` 释放。内容政策拒绝、Provider HTTP 失败和未分类异常分别记为 `content_blocked`、`provider_error` 与 `runtime_error`，并带回裁剪后的 `detail`；默认带 `tts_stats` 与轻量 `artifacts[]`，完整 `tts` / `tool_calls` / 交付物内容由 `extras` 按需返回。普通工具 Pending 与子助手桥接的 `ask_user` 共用前台审批音效 `loop_approval`。

Target 永久过滤 Assistant 管理与再次委托。`generate_image` 按 Target 快照 ∩ 当前配置允许，不再永久过滤。附件识别模型选择在 Target run 开始时冻结，Target 删除、撤权等运行安全条件仍在 step 边界重验。其他工具继续按子助手运行策略动态装配；Memory Tool 在执行前独立重验。需审批工具默认拒绝，只有 `ask_user` 会按 Child locator 桥接到主聊天。

每个 Master turn 创建唯一的 TTS queue session，Master 与该 turn 内的 Target 只向这条共享队列提交音频；工具审批暂停与恢复复用原 session，新消息和重新生成创建新 session。播放器是队列边界的唯一仲裁者：新 session 替换旧队列；同 session 在顺序开关开启时追加、关闭时替换。Tool 实例和 UI `activeSource` 均不保存或推断队列生命周期；每个 chunk 直接绑定来源，`activeSource` 只随实际播放更新。自动朗读忽略其他会话和待审批暂停事件，并按同一 session 的策略提交，不能旁路打断工具音频。旧 worker 和旧播放器回调通过所有权 token 隔离，不能清空或停止新队列。System TTS 并发预取使用系统创建的唯一临时文件，禁止以时间戳共享输出路径。

完整的持久化结构、执行流程、撤权、恢复、分支与 UI 边界见 [sub-assistant-architecture.md](sub-assistant-architecture.md)。

## 关键文件

```text
ai/src/main/java/me/rerere/ai/
├─ core/Tool.kt                          # ToolExecutionContext、contextualExecute
├─ provider/
└─ ui/Message.kt                         # UIMessage、工具状态、上下文边界与阶梯裁剪

app/src/main/java/net/weero/measix/pilot/
├─ service/
│  ├─ ChatService.kt                     # 会话编排、工具装配
│  ├─ GenerationSideEffects.kt           # 生成副作用域（音效 + 标题/建议/压缩衍生生成）
│  ├─ TurnRecovery.kt                    # 恢复语义唯一所有者（master turn + 子助手 run + 取消收口）
│  ├─ SubAssistantRunGate.kt             # run 门禁（lease + pending ask_user 并发原语）
│  ├─ runtime/                           # Runtime Core（v1 重构）
│  │  ├─ ConversationCommands.kt         # 密封命令 + ConversationSnapshot（唯一状态形状）
│  │  ├─ ConversationReducer.kt          # 纯函数 reducer（快照态，structural sharing）
│  │  ├─ ConversationRuntime.kt          # 单写 submit 命令通道 + applyStreamingDelta 流式投影（snapshot 唯一事实流）
│  │  ├─ ConversationRuntimeRegistry.kt  # Master/Child 共用 Runtime 生命周期
│  │  ├─ TurnEngine.kt                   # turn 骨架唯一实现（start）+ chunk→投影 / checkpoint→CommitCheckpoint / 终态→FinalizeTurn
│  │  └─ DelegationCoordinator.kt        # 子助手四阶段编排（preflight/materialize/run/terminal）
│  ├─ AssistantManagementService.kt      # Assistant CRUD、原子授权、删除编排
│  └─ ChatNotificationManager.kt         # 通知事件消费者
└─ data/ai/
   ├─ GenerationHandler.kt               # Provider 请求与工具循环（含 Phase/Checkpoint/Finished）
   ├─ attachments/                       # stable ref、Resolver、安全取图
   ├─ transformers/                      # request/output projection
   ├─ subassistant/                      # 访问策略、lineage、reducer、metadata、preview、catalog、结果投影纯函数
   └─ tools/
      ├─ AssistantToolFactory.kt         # assistant_manage/inspect/call 工具构建
      ├─ AttachmentInspectionTool.kt      # 按需附件识别
      ├─ GenerationToolSetFactory.kt     # 按 Assistant/资源/RunMode 装配工具集
      └─ local/
```