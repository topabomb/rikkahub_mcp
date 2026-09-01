# Tool Runtime、用户交互与上下文滚动收敛方案

> 状态：待实施方案
>
> 基线：以当前 `GenerationLoop`、`GenerationToolSetFactory`、Conversation/Turn command、
> `ArtifactStore` 与请求级 `limitContext()` 实现为准。
>
> 本文是开发方案，不是当前实现参考。实施完成后必须同步更新
> `docs/references/application-architecture.md`、`chat-generation-pipeline.md`、
> `multimodal-context-and-turn-durability.md`、`prompts-and-tools.md`，并按实际代码删除本文中的迁移描述。

## 1. 背景与问题定义

当前工具体系已经具备若干正确且必须保留的边界：

- `GenerationToolSetFactory` 统一决定当前 step 可见的 Tool definitions；
- `Tool.parseArguments()` 是合法 JSON object 与工具纯参数校验的共用入口；
- Master 与 Target 共用 `GenerationLoop`、`TurnEngine` 和 checkpoint 协议；
- ToolCall 以 `messageId + toolOrdinal` 定位，不依赖 Provider `toolCallId`；
- `ConversationCommandCoordinator` 是用户决定的 durable 写入口；
- `ArtifactStore` 是 Artifact metadata、reference 与 payload 生命周期的唯一 owner；
- MCP 动态工具被适配为同一个 `Tool` 类型，不旁路公共审批和执行 checkpoint。

问题不在于完全没有管道，而在于工具调用缺少一个明确、可测试的应用级运行时边界。当前
`GenerationLoop` 同时负责：

```text
Provider step 编排
+ Tool definition 索引
+ 参数解析与校验
+ 审批判定
+ ask_user 特殊交互
+ Denied / Answered / invalid 结果投影
+ ToolExecutionContext 构造
+ 工具调用
+ timeout / exception / empty output 规范化
+ Tool execution checkpoint 编排
+ 单结果即时裁剪
+ tool_outputs 文件写入
+ Provider 请求上下文裁剪
+ streaming transformer
```

由此产生四类架构问题：

1. **工具运行时职责过重**：审批预检和执行阶段会重复解释输入，通用异常与结果规范散落在循环中；
2. **审批语义混合**：`needsApproval: Boolean` 同时承载授权审批，`ask_user` 又借同一个
   `ToolApprovalState.Pending` 表示等待回答，并依赖工具名白名单区分；
3. **输出裁剪越界**：超过阈值的工具文本依赖 `workspace_shell`，直接写 App 私有
   `tool_outputs` 目录，绕过 Artifact 引用、fork、GC、备份和恢复协议；
4. **上下文管理分散**：普通消息窗口、Tool Result 大文本、Provider 最终可见投影分别被不同逻辑处理，
   缺少一个独立的请求上下文规划点和“模型已经实际读取”的可靠凭证。

## 2. 需求

本次工作必须完整满足以下三项需求。

### 2.1 重构工具整个运行时架构

- 所有本地、Workspace、Search、Skill、Memory、Assistant、附件识别和 MCP 工具继续使用同一 `Tool` 协议；
- 参数准备、交互门控、通用执行包装和结果规范化收敛到单一 `ToolCallRuntime`；
- `GenerationLoop` 只保留 Agent step、批次屏障、顺序、streaming 和 checkpoint 编排；
- Tool 领域校验、MCP 线协议、Workspace 权限、Artifact 生命周期继续归原 owner，不建立 God Object。

### 2.2 重构并集中审批与用户输入流程

- 明确区分“执行授权审批”和“向用户获取输入”；
- 用 typed requirement、typed availability 和 typed decision 取代 Boolean 与工具名白名单；
- 用户决定仍经 Conversation command 写入原 ToolCall，并复用原 `TurnHandle` 继续；
- 不新增审批数据库、Approval Store 或第二运行时状态源。

### 2.3 引入独立统一的对话上下文滚动规划和回查工具

- 普通历史窗口和大型 Tool Result 都从同一个 `ConversationContextPlanner` 规划；
- 普通消息裁剪仍是请求级、非破坏、对齐完整 USER 轮次；
- Tool Result 只有在被一次成功 Provider 请求实际读取后，才能从 inline 变为可逆归档 marker；
- 归档内容由 `ToolOutputStore` 通过 `ArtifactStore` 管理；
- 提供稳定注册的 `read_tool_output` 和 `grep_tool_output` 两个回查工具；
- 不自动摘要普通历史，不把消息条数、字符数伪装成精确 token，不静默丢失 durable Conversation 内容。

## 3. 目标与非目标

### 3.1 目标

1. 一次连续工具处理只解析参数一次，审批与执行使用同一个不可变 `JsonObject`；
2. 所有 ToolCall 经过同一个 availability、validation、interaction 和 execution runtime；
3. 审批、回答、拒绝、工具不可用和参数失败具有明确 typed 结果，并且未执行调用不创建 STARTED fact；
4. 工具完整结果先 durable、先被模型读取，再按统一策略滚动归档；
5. 上下文规划独立于 Provider adapter、UI、Workspace 和物理文件路径；
6. fork、删除、GC、备份、恢复和 payload 缺失场景下，归档结果遵守 Artifact 现有生命周期；
7. Master 与 Target 使用同一运行时，差异只由显式 run policy 表达。

### 3.2 非目标

本次不引入：

- 通用 middleware/interceptor 链、Event Bus、Command Bus 或 ToolManager God Object；
- 新 `tool_output` Room 表、`consumedByModel` 字段或额外归档 checkpoint；
- 自动语义摘要、自动重写普通 durable 历史或新的摘要状态机；
- 每种 Tool、失败或输出类型一个 handler/projector 类；
- 模型可见的 App 私有路径、`file://`、Artifact relative path 或 shell 回查协议；
- 依赖当前 Tool definition 反向解释历史 Tool Result；
- 新旧运行时、旧即时裁剪与新滚动归档长期双路径并存。

工具自身的结果数量限制、搜索分页、文件读取范围和 MCP 图片字节限制继续归具体 Tool 或协议 adapter，
不纳入通用上下文裁剪。

## 4. 核心架构裁决

### 4.1 最终组件与唯一职责

| 组件 | 唯一职责 |
| --- | --- |
| `GenerationToolSetFactory` | 决定当前 step 有哪些 Tool definitions，并拒绝保留名冲突 |
| `GenerationLoop` | Provider step、工具批次顺序、暂停、streaming、checkpoint 编排 |
| `ToolCallRuntime` | 一次 ToolCall 的输入准备、用户门控、通用执行包装与结果规范化 |
| `ConversationContextPlanner` | 纯函数规划本次请求消息窗口和成功 step 后的历史 Tool Result 归档候选 |
| `ToolOutputStore` | Tool Result 文本规范化、Artifact staging、source 授权、bounded read/grep |
| `MasterTurnCoordinator` | 接收用户决定、提交 command、在全部交互完成后继续原 Turn |
| `ConversationCommandCoordinator` / `ConversationTransition` | 用户决定的唯一 durable 写入和合法状态迁移 |
| `TurnEngine` | checkpoint、Turn 状态和 Tool execution fact 的 durable owner |
| `ArtifactStore` | 归档 payload 的 metadata、reference、发布、fork retention、GC 和恢复 |
| `ConversationPresentation` | 将 durable/active facts 投影为 UI 可消费的交互和执行阶段 |

### 4.2 总体链路

```text
GenerationToolSetFactory
    │ current step definitions
    ▼
GenerationLoop
    ├─ ConversationContextPlanner.planRequest(...)
    │      └─ 请求级历史窗口，不改 durable Conversation
    │
    ├─ Provider request / stream
    │      └─ ModelStepReceipt：最终实际可见的 inline Tool Result locators
    │
    ├─ ToolCallRuntime.prepareBatch(...)
    │      ├─ definition lookup
    │      ├─ parse + validate once
    │      ├─ interaction gate
    │      └─ PreparedToolCall
    │
    ├─ ToolCallRuntime.execute(...)
    │      └─ normalized ToolCallOutcome
    │
    ├─ ConversationContextPlanner.planPostStepCompaction(...)
    │      └─ 仅选择已成功消费的历史纯文本 Tool Result
    │
    ├─ ToolOutputStore.stageArchive(...)
    │      └─ ArtifactStore unpublished leases
    │
    └─ TurnEngine checkpoint
           ├─ messages + execution facts + artifact refs 同事务
           └─ checkpoint 成功后 publish leases
```

UI 和模型回查工具共享同一个读取边界：

```text
read_tool_output ─┐
grep_tool_output ─┼─→ ToolOutputStore → ArtifactStore retained read
Tool detail UI ───┘
```

## 5. Tool 定义协议

### 5.1 交互 requirement

用显式 requirement 替换 `needsApproval: (JsonElement) -> Boolean`：

```kotlin
sealed interface ToolInteractionRequirement {
    data object None : ToolInteractionRequirement
    data object Approval : ToolInteractionRequirement
    data object UserInput : ToolInteractionRequirement
}
```

核心传输类型固定为：

```kotlin
data class ToolOutputArchiveCandidate(
    val locator: ToolCallLocator,
    val canonicalText: String,
    val terminalStatus: String,
    val characters: Long,
    val lines: Int,
    val failedTail: String?,
)

data class ToolOutputArchivePlan(
    val candidates: List<ToolOutputArchiveCandidate>,
    val reclaimedCharacters: Long,
)

data class StagedToolOutputArchive(
    val replacements: Map<ToolCallLocator, UIMessagePart.Tool>,
    val leases: List<ToolResourceLease>,
)

sealed interface ToolOutputReadResult
sealed interface ToolOutputGrepResult
```

`ToolOutputReadResult` / `ToolOutputGrepResult` 的 Success 必须携带有界文本和分页/截断元数据，Failure 只使用稳定
reason；它们不返回 `File`、Artifact entity 或真实路径。

`Tool` 的目标形状：

```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> JsonObject? = { null },
    val systemPrompt: (Model, List<UIMessage>) -> String = { _, _ -> "" },
    val interactionRequirement: (JsonObject) -> ToolInteractionRequirement = {
        ToolInteractionRequirement.None
    },
    val validateArguments: (JsonElement) -> JsonObject? = { null },
    val outputPolicy: ToolOutputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    val contextualExecute:
        (suspend ToolExecutionContext.(JsonElement) -> List<UIMessagePart>)? = null,
)
```

其中：

```kotlin
enum class ToolInteractionKind {
    NONE,
    APPROVAL,
    USER_INPUT,
}
```

`ToolInteractionKind` 是 Runtime/Presentation 对当前交互种类的稳定投影，不是 durable 状态；它由
`ToolInteractionRequirement` 与已经提交的 Tool state 共同派生。

迁移规则：

| 当前工具语义 | 新 requirement |
| --- | --- |
| 普通只读或无副作用工具 | `None` |
| `calendar_create`、默认 shell、受策略保护的 Workspace 写、需要设置背景的生图、MCP policy approval | `Approval` |
| `ask_user` | `UserInput` |
| `read_tool_output`、`grep_tool_output` | `None` |

`interactionRequirement` 必须是纯函数，只能依据已校验的 `JsonObject` 和 Tool 构造时捕获的不可变策略；
不能读取数据库、文件、网络或在判断阶段请求 Android 权限。

### 5.2 输出策略

```kotlin
enum class ToolOutputPolicy {
    /** 成功被模型读取后，历史纯文本结果允许归档。 */
    ARCHIVABLE_TEXT,

    /** Provider replay 始终保留完整原始 parts。 */
    PRESERVE,
}
```

`ARCHIVABLE_TEXT` 不表示“工具返回时立即裁剪”。第一版只有纯 Text parts 才可归档；Image、Audio、Video、
Document、混合媒体和 Provider opaque replay 一律 `PRESERVE`。

## 6. ToolCallRuntime

### 6.1 定位与职责

新增：

```text
app/src/main/java/net/weero/measix/pilot/data/ai/tools/ToolCallRuntime.kt
```

建议接口：

```kotlin
internal class ToolCallRuntime(
    private val json: Json,
) {
    fun prepareBatch(
        calls: List<LocatedToolCall>,
        definitions: List<Tool>,
        availability: ToolInteractionAvailability,
    ): ToolBatchPreparation

    suspend fun execute(
        call: PreparedToolCall,
        hooks: ToolExecutionHooks,
    ): ToolCallOutcome
}
```

它负责：

- 构建并验证本 step 不可变 `toolsByName`；
- 按名称定位 definition；
- 调用 `Tool.parseArguments()`；
- 使用同一个参数对象计算 interaction requirement；
- 解释 durable interaction state；
- 形成 Pending、Denied、Answered、invalid、unavailable 等立即结果；
- 创建 `PreparedToolCall`；
- 构造 `ToolExecutionContext`；
- 统一 timeout、普通异常、空结果和 `ToolExecutionFailure`；
- 继续传播 `CancellationException`；
- 写入 Runtime 保留 metadata，但不直接提交 Conversation。

它不负责：

- Provider step、Turn 状态、Room transaction 或 UI 发布；
- Tool 领域参数规则；
- MCP 线协议转换、OAuth 和 definition admission；
- Workspace command gate；
- Artifact 创建、发布、删除或 GC；
- 历史上下文窗口和物理归档 IO。

### 6.2 PreparedToolCall

```kotlin
data class PreparedToolCall(
    val locator: ToolCallLocator,
    val executionId: String,
    val source: UIMessagePart.Tool,
    val definition: Tool,
    val arguments: JsonObject,
    val interaction: ToolInteractionRequirement,
    val approvedByUser: Boolean,
)
```

不变量：

> 一次连续运行中，interaction 判断和执行使用同一个不可变 `JsonObject`。

审批暂停后发生进程重启或 durable 恢复时，可以从 `Tool.input` 重新 prepare；但同一次恢复尝试仍只解析一次。
原始输入只持久化在 `UIMessagePart.Tool.input`，不复制到 `tool_execution`，也不持久化解析后的 JSON。

### 6.3 批次准备

```kotlin
data class ToolBatchPreparation(
    val replacements: Map<Int, UIMessagePart.Tool>,
    val immediateResults: List<ToolResultEvent>,
    val executableCalls: List<PreparedToolCall>,
    val pendingInteractions: Set<ToolInteractionKind>,
)
```

只要 `pendingInteractions` 非空，本轮不得执行任何 `executableCalls`。例如同一 Provider step 返回 A 自动、
B 审批、C 自动时，A/B/C 都先不执行；B 决策完成后重新准备整个未完成批次，再按 ordinal 执行 A → B → C。

### 6.4 通用执行顺序

`GenerationLoop` 和 `ToolCallRuntime` 的边界固定为：

```text
GenerationLoop：提交 TOOL_EXECUTION_STARTED
GenerationLoop：发布 EXECUTING presentation
ToolCallRuntime：构造 ToolExecutionContext
ToolCallRuntime：definition.executeWithContext(arguments)
ToolCallRuntime：规范化 output / error
GenerationLoop：写回 Tool.output
GenerationLoop：提交 TOOL_RESULT_COMPLETED
GenerationLoop：checkpoint 成功后发布 ToolResourceLease
GenerationLoop：发布终态 presentation
```

`ToolExecutionHooks` 显式承载当前由 `GenerationLoop` 提供的最小能力：

```kotlin
data class ToolExecutionHooks(
    val resolveAttachments:
        suspend (List<String>) -> ToolAttachmentResolution,
    val reportMetadata:
        suspend (JsonObject, ToolMetadataDelivery) -> Unit,
    val reportChildConversation:
        suspend (String) -> Unit,
    val registerUnpublishedResource:
        (ToolResourceLease) -> Unit,
)
```

`ToolCallRuntime` 只把这些 hooks 装配进 `ToolExecutionContext`。`CHECKPOINT` metadata 与 Child link 的实际
checkpoint 仍由 `GenerationLoop` 提供的 hook 完成，因此 Runtime 不获得 Conversation command 或 Room 写能力。

通用结果规范：

| 情况 | 结果 |
| --- | --- |
| 正常非空 | 保留 Tool owner 返回 parts |
| 正常空结果 | `{"status":"completed","result":null}` |
| `ToolExecutionFailure` | 原样保留其 typed output，execution 为 FAILED |
| timeout | 稳定 `tool_timeout` 信封，execution 为 FAILED |
| 普通异常 | 稳定 `tool_failed` 信封，细节只保留安全短消息，完整异常写 Logcat |
| `CancellationException` | 向上传播，由 Turn finalization 收口 |

参数无效、工具撤销、交互不可用、用户拒绝和用户回答都没有真正执行，因此不创建
`TOOL_EXECUTION_STARTED` 或 `tool_execution` row，只提交 Tool result fact。

## 7. 审批与用户输入统一流程

### 7.1 运行交互能力

替换：

```text
nonInteractive: Boolean
interactiveToolNames: Set<String>
```

改为：

```kotlin
enum class ToolInteractionAvailability {
    /** Master：允许授权审批和用户输入。 */
    FULL,

    /** Target：允许 ask_user 类输入，不允许授权审批。 */
    USER_INPUT_ONLY,

    /** 完全无人值守：不允许暂停等待用户。 */
    NONE,
}
```

运行模式不再通过 `toolName == "ask_user"` 白名单解释交互能力。

### 7.2 用户决定

```kotlin
sealed interface ToolUserDecision {
    data object Approve : ToolUserDecision
    data class Deny(val reason: String) : ToolUserDecision
    data class Answer(val answer: String) : ToolUserDecision
}
```

Application/UI 唯一入口：

```kotlin
suspend fun submitToolDecision(
    locator: ToolCallLocator,
    decision: ToolUserDecision,
)
```

合法组合：

| requirement | 接受的决定 |
| --- | --- |
| `Approval` | `Approve` / `Deny` |
| `UserInput` | `Answer` |
| `None` | 不接受用户决定 |

类型不匹配 fail-closed，禁止把 Answer 当授权或把 Approve 当问答结果。

### 7.3 Durable 编码与命名

为了保持已有消息 JSON、Room 数据和备份可读，第一阶段保留 `ToolApprovalState`：

```text
Auto / Pending / Approved / Denied / Answered
```

它仅作为 durable 编码。除 `ToolCallRuntime`、Conversation command/reducer 和 presentation projector 外，
其他代码不得直接解释它。Tool requirement、交互类型、执行 phase 和 UI 操作必须来自 typed runtime/presentation。

内部命名同步调整：

```text
UpdateToolApproval          → ResolveToolInteraction
CONTINUE_APPROVAL           → CONTINUE_USER_INTERACTION
applyToolApprovalDecision   → applyToolUserDecision
```

不新增 `ToolApprovalCoordinator`。用户决定本来就是 Master application orchestration，继续由
`MasterTurnCoordinator` 跨 Runtime snapshot、Conversation command 和 Turn continuation 编排。

### 7.4 Gate 状态机

```kotlin
sealed interface ToolGateDisposition {
    data object Executable : ToolGateDisposition
    data object AwaitingApproval : ToolGateDisposition
    data object AwaitingInput : ToolGateDisposition
    data class Denied(val reason: String) : ToolGateDisposition
    data class Answered(val answer: String) : ToolGateDisposition
    data class Rejected(val reason: ToolGateRejection) : ToolGateDisposition
}
```

核心矩阵：

| Requirement | Durable state | Availability | 结果 |
| --- | --- | --- | --- |
| `None` | `Auto` | 任意 | `Executable` |
| `Approval` | `Auto` | `FULL` | 写 Pending，`AwaitingApproval` |
| `Approval` | `Auto` | 其他 | `Rejected(approval_unavailable)` |
| `Approval` | `Approved` | 任意 | `Executable` |
| `Approval` | `Denied` | 任意 | `Denied` |
| `UserInput` | `Auto` | `FULL/USER_INPUT_ONLY` | 写 Pending，`AwaitingInput` |
| `UserInput` | `Auto` | `NONE` | `Rejected(input_unavailable)` |
| `UserInput` | `Answered` | 任意 | `Answered` |
| requirement/state 不匹配 | 任意 | 任意 | `Rejected(interaction_state_invalid)` |

### 7.5 用户决定提交顺序

固定流程：

```text
1. 等待当前 worker 到达安全边界
2. 读取 committed ConversationSnapshot
3. 校验 active TurnHandle
4. 以 messageId + toolOrdinal 定位 ToolCall
5. 校验仍在等待用户
6. 校验 requirement 与 decision 匹配
7. 提交 ResolveToolInteraction command
8. 复读 committed snapshot
9. 若仍有 Pending，继续保持暂停
10. 否则复用原 TurnHandle 进入 CONTINUE_USER_INTERACTION
```

Continue 不得创建新 turn、第二条 `turn_execution`、新 assistant message、第二个 TTS session，也不得执行 START
结构预检、附件回填或树清理。

### 7.6 审批不是资源授权

Approve 只表示用户同意尝试该次 ToolCall。执行 owner 仍须重新校验当前 Tool definition、MCP digest、OAuth、
Workspace 路径、Android 权限、文件存在性和远端业务规则。`ToolExecutionContext.approvedByUser` 只能从 durable
`Approved` 派生，不能接受模型参数。

## 8. Runtime 保留 metadata

为了让 UI、恢复和 command 不依赖当前 Tool definition 或工具名，在 Tool metadata 中增加唯一 Runtime 命名空间：

```json
{
  "tool_runtime": {
    "version": 1,
    "interaction": "approval",
    "output_policy": "archivable_text",
    "terminal_status": "completed",
    "archive": null
  }
}
```

归档后：

```json
{
  "tool_runtime": {
    "version": 1,
    "interaction": "approval",
    "output_policy": "archivable_text",
    "terminal_status": "completed",
    "archive": {
      "source": "tool-output://v1/4821",
      "artifact": {
        "relativePath": "tool_outputs/...",
        "mimeType": "text/plain"
      },
      "characters": 86742,
      "lines": 3198
    }
  }
}
```

约束：

- `tool_runtime` 由统一 typed codec 读写；
- Tool 自身 `reportMetadata` 不得写入、替换或删除该 key；
- metadata merge 遇到保留 key 必须拒绝，不允许静默覆盖；
- 老消息没有 metadata 时按安全默认值解释：交互从 durable 事实/当前 definition 严格重建，输出默认 `PRESERVE`；
- 不持久化近似 token 数，只保存稳定字符数与规范化行数。

## 9. ConversationContextPlanner

### 9.1 定位

新增一个无 IO、无 durable 写能力的独立规划边界：

```text
app/src/main/java/net/weero/measix/pilot/data/ai/ConversationContextPlanner.kt
```

```kotlin
internal class ConversationContextPlanner {
    fun planRequest(
        durableMessages: List<UIMessage>,
        messageLimit: Int,
    ): RequestContextPlan

    fun planPostStepCompaction(
        committedMessages: List<UIMessage>,
        receipt: ModelStepReceipt,
        budget: ToolOutputBudget,
    ): ToolOutputArchivePlan
}
```

它统一“上下文选择决策”，但不统一不同事实的写协议：

- 普通消息窗口是 request-only projection，不修改 durable Conversation；
- Tool Result 归档是可逆 durable rewrite，必须进入现有 STEP_COMPLETED checkpoint，并由 Artifact lease 保障；
- 用户触发语义压缩继续走现有 Conversation command，不进入自动 planner。

### 9.2 三层上下文策略

| 层 | 目的 | 是否改写 durable history | Owner |
| --- | --- | --- | --- |
| 请求级滚动窗口 | 控制发送的普通历史范围 | 否 | `ConversationContextPlanner.planRequest` |
| 已消费 Tool Result 归档 | 降低旧大文本长期占用 | 是，但可通过 source 回查 | Planner + `ToolOutputStore` + checkpoint |
| 用户触发语义压缩 | 用摘要替换早期历史 | 是，不可逆 | 现有 `ConversationApplicationService.compress` |

自动 planner 不生成摘要，不把旧对话变成不可审计的模型概括。

### 9.3 请求级滚动窗口

迁移现有 `limitContext()` 语义到 planner 内部纯函数，并保留当前产品约束：

- `contextMessageLimit == 0` 时关闭；
- 非零配置按既有 40..512 归一化；
- 阶梯式移动锚点，保留比例 50%；
- 裁剪起点回退到完整 USER 轮次；
- 不改 durable messages；
- Tool system prompt 与 Provider 请求必须使用同一份 `RequestContextPlan.messages`；
- replay-safe projection、请求裁剪和 transformer 的顺序必须固定并由测试覆盖。

本次可以把逻辑从 `List<UIMessage>.limitContext()` 迁移到 planner，但不能同时保留两个生产入口。

### 9.4 ModelStepReceipt

`generateInternal()` 从返回 `Unit` 改为返回：

```kotlin
data class ModelStepReceipt(
    val visibleInlineToolOutputs: Set<ToolCallLocator>,
)
```

receipt 必须从最终发送给 Provider 的消息投影中产生，已经经过：

```text
replaySafeProjection
→ request context plan
→ input transformers
→ attachment projection
→ Provider 最终消息容器选择
```

只有 Provider 请求成功返回后 receipt 才有效。请求失败、取消、序列化失败或未建立有效响应时，不允许据此归档。
不新增 `consumedAt`、`consumedStep` 或 `consumedByModel` durable 字段；失败恢复最多让模型再次读取完整结果，不会丢失内容。

请求函数必须在内部完成最终请求投影后再生成 receipt，目标签名表达为：

```kotlin
suspend fun requestModel(
    requestPlan: RequestContextPlan,
    definitions: List<Tool>,
    transformers: List<MessageTransformer>,
    mediaCapabilities: RequestMediaCapabilities,
): ModelStepReceipt
```

receipt 从该函数已经完成 transformer、附件投影和 Provider serializer 容器选择的最终消息中提取，不能从
`RequestContextPlan.messages` 或 durable messages 预先猜测。具体 Provider adapter 不返回 conversation locator；
`generateInternal` 在交给 adapter 的同一最终 `UIMessage` 投影上记录 locator，再在调用成功后构造 receipt。

## 10. Tool Result 独立滚动归档

### 10.1 归档时序

工具刚执行完成时始终保留完整结果：

```text
Tool execution
→ 完整 Tool.output
→ TOOL_RESULT_COMPLETED checkpoint
→ 下一次 Provider 请求实际读取完整 output
→ Provider step 成功
→ planner 选择历史候选
→ ToolOutputStore 暂存 Artifact
→ Tool.output 替换 marker，metadata 写 archive ref
→ 与本 step 新输出一起提交 STEP_COMPLETED
→ checkpoint 成功后 publish Artifact lease
→ 下一 step 才看到 marker
```

marker replacement 只能存在于待提交的局部 `checkpointMessages`，不得先覆盖 `GenerationLoop.messages`：

```text
base messages
→ stage artifacts
→ 构造 checkpointMessages（含 marker）
→ CommitCheckpoint(checkpointMessages)
→ 成功：messages = checkpointMessages，再 publish leases/presentation
→ 失败：messages 保持 base，discard staged leases，并传播失败
```

这保证 checkpoint 失败时内存与 durable snapshot 都仍保存完整 inline output。禁止依赖“稍后从数据库重载”修复
已经发布或继续使用的 marker 投影。

因此必须删除旧的：

```text
MAX_TOOL_OUTPUT_CHARS
TOOL_OUTPUT_PREVIEW_CHARS
maybeTruncateToolOutput(...)
hasShellAccess 归档门禁
GenerationLoop 直接 File.writeText(...)
模型通过 cat/grep 读取 App 私有目录的提示
MeasixPilotApp 对 ephemeral tool_outputs 的启动清理
```

### 10.2 候选条件

一个 Tool Result 必须同时满足：

- locator 出现在成功 `ModelStepReceipt.visibleInlineToolOutputs`；
- runtime metadata policy 为 `ARCHIVABLE_TEXT`；
- output 全部为 Text parts；
- 当前仍是 INLINE，没有 archive metadata；
- 不含 Provider opaque replay；
- 不是 `read_tool_output` 或 `grep_tool_output` 的结果。

### 10.3 第一版预算

当前 `Model` 没有可信的统一 context-window 元数据，字符数也不是 token。因此第一版使用明确的**字符预算**，
不采用 `effectiveContextWindow * 20%` 这类看似精确但没有可靠输入的公式：

```text
highWatermarkChars = 64 KiB
lowWatermarkChars = 32 KiB
minimumReclaimChars = 24 KiB
protectedRecentBatches = 2
protectedRecentChars = 12 KiB
```

对应 typed 输入：

```kotlin
data class ToolOutputBudget(
    val highWatermarkChars: Long = 64 * 1024L,
    val lowWatermarkChars: Long = 32 * 1024L,
    val minimumReclaimChars: Long = 24 * 1024L,
    val protectedRecentBatches: Int = 2,
    val protectedRecentChars: Long = 12 * 1024L,
)
```

触发后从最老 eligible output 开始，一次归档到低水位；若可回收量低于 `minimumReclaimChars` 则不执行。
`latestRequestContextTokens` 只用于观测和后续策略评估，不反推当前请求中某一 Tool Result 的精确 token。

未来只有在模型窗口元数据具有“注册表默认值 + 用户覆盖 + 来源”、Provider 能估算最终序列化请求且覆盖
System/Tools/多模态后，才允许增加 typed token budget；届时必须保留字符预算作为未知模型的明确降级策略。

### 10.4 状态单向与 marker

```text
INLINE → ARCHIVED
```

不自动展开、不重新生成 marker、不因模型变化修改预览、不重复归档。稳定 marker 有利于 Provider 提示缓存前缀保持稳定。

成功 marker：

```text
[archived tool output: source=tool-output://v1/4821; lines=1248; characters=37642]
```

失败 marker：

```text
[archived tool output: failed; source=tool-output://v1/4821; lines=3198; characters=86742; tail="BUILD FAILED in 28s"]
```

规则：marker 单行；不保留 4 KiB preview；failed 可保留最后一个非空逻辑行，tail 最多 160 字符；
回查说明只写在工具 description，不在每个 marker 中重复。

## 11. ToolOutputStore 与 Artifact 生命周期

### 11.1 接口

新增：

```text
app/src/main/java/net/weero/measix/pilot/data/ai/tools/ToolOutputStore.kt
```

```kotlin
internal class ToolOutputStore(
    private val artifactStore: ArtifactStore,
) {
    suspend fun stageArchive(
        conversationId: Uuid,
        plan: ToolOutputArchivePlan,
    ): StagedToolOutputArchive

    suspend fun read(
        conversationId: Uuid,
        source: String,
        startLine: Int,
        lineCount: Int,
    ): ToolOutputReadResult

    suspend fun grep(
        conversationId: Uuid,
        source: String,
        pattern: String,
        ignoreCase: Boolean,
        contextLines: Int,
        maxMatches: Int,
    ): ToolOutputGrepResult
}
```

它负责文本规范化、source codec、marker、Artifact staging、conversation-scoped 授权、bounded read/grep。
它不直接访问 DAO，不管理 GC，不提交 Conversation，不解释 Tool execution。

### 11.2 复用 ArtifactStore

不新增 `tool_output` 表。归档使用现有 Artifact 生命周期和 `ArtifactReferenceType.TOOL_OUTPUT`：

```kotlin
artifactStore.createText(
    text = canonicalText,
    folder = FileFolders.TOOL_OUTPUTS,
    origin = ArtifactOrigin.SYSTEM,
)
```

`FileFolders.TOOL_OUTPUTS` 从“ephemeral model context”改为受管 Artifact 目录：

- 不出现在上传文件列表、图库或普通文件管理页面；
- 不通过 `/upload` 或 `/workspace` 暴露；
- 不允许用户按普通附件路径删除；
- 只由 Tool runtime metadata 和 `TOOL_OUTPUT` reference 保留；
- 纳入 Artifact startup reconcile、backup、restore、fork retention 和 GC。

### 11.3 Typed Artifact reference collector

把 `collectFileReferenceTokens(): Set<String>` 改为 typed collector：

```kotlin
data class MessageArtifactReference(
    val token: String,
    val type: ArtifactReferenceType,
)
```

规则：

| 来源 | reference type |
| --- | --- |
| 普通 Image/Document/Audio/Video part | `ATTACHMENT` |
| Tool.output 中的媒体 Artifact | 按当前产品语义明确分类，不能统一猜测 |
| `tool_runtime.archive.artifact` | `TOOL_OUTPUT` |
| `generate_image` / `assistant_call` 交付物 | 保持其现有 owner 与引用语义 |

`ArtifactStore.resolveNodeReferenceEntities()` 使用 typed type 落 `artifact_reference`，不再把所有 token 固定登记成
`ATTACHMENT`。提升 reference projection version，使启动阶段按新类型重建投影；若表结构不变，无需 Room migration。

`ToolOutputStore` 不访问 DAO。`ArtifactStore` 需要增加一个窄的 conversation-scoped retained read capability：按
`conversationId + artifactId + ArtifactReferenceType.TOOL_OUTPUT` 校验 reference、状态、folder 和 MIME，在 lifecycle lock
内取得 retention pin，再在锁外向调用方提供只读 stream，finally 释放。该能力不得返回 Artifact entity 或裸路径。

fork 后原 node 与 fork node 可以引用同一个 Tool Output Artifact。删除任一会话只删除自己的 reference，最后一个引用
消失后才允许 GC，不建立 `executionId → file` 第二生命周期。

## 12. tool-output source 与回查工具

### 12.1 Source 授权

逻辑地址：

```text
tool-output://v1/<artifact-id>
```

模型知道 source 不等于获得读取授权。每次 read/grep 必须同时验证：

- Artifact 状态为 ACTIVE；
- folder 为 `tool_outputs`；
- MIME 为 `text/plain`；
- 当前 conversation 的 message node 确实存在 `TOOL_OUTPUT` reference；
- retained payload 在读取期间受现有 retention pin 保护。

不存在、payload 缺失和越权统一 fail-closed：

```json
{
  "status": "failed",
  "error": "tool_output_unavailable",
  "message": "The requested tool output is not available in this conversation."
}
```

不披露 Artifact id 之外的数据库字段、relative path、App 私有路径或 `file://`。

### 12.2 `read_tool_output`

参数：

```json
{
  "source": "tool-output://v1/4821",
  "start_line": 3150,
  "line_count": 100
}
```

约束：`start_line` 从 1 开始；默认 200 行；最多 500 行；同时受 16 KiB 返回上限约束；不提供 `read_all`。
结果包含 source、当前范围、总行数、带稳定行号的内容和 `next_start_line`。

### 12.3 `grep_tool_output`

参数：

```json
{
  "source": "tool-output://v1/4821",
  "pattern": "error|failed|Caused by",
  "ignore_case": true,
  "context_lines": 2,
  "max_matches": 20
}
```

约束：`context_lines` 为 0..5；`max_matches` 默认 20、最多 100；pattern 长度受限；返回总量受 16 KiB 限制。
使用 RE2/J 或等价无灾难性回溯的实现，不能直接接受模型可控的无界 Java regex。

两个工具始终随 Master 和 Target 稳定注册，`interactionRequirement=None`、`outputPolicy=PRESERVE`，输出严格有界，
因此不会形成“回查结果再次归档”的引用链。它们的名称是内置保留名，动态/MCP 工具冲突时在装配阶段明确拒绝。

### 12.4 文本规范化与行号

第一版不建立 sidecar 行索引或 offset 表。归档时确定性执行：

```text
CRLF / CR → LF
移除 ANSI 控制序列
不做 JSON pretty-print
不生成摘要
超长物理行每 4096 字符切为稳定虚拟行
```

read/grep 使用 buffered streaming scan。只有真实性能证据表明远距离读取成为瓶颈后，才考虑每 128 行一个稀疏索引。

## 13. Presentation 与 UI

### 13.1 ToolCall presentation

```kotlin
data class ToolCallPresentation(
    val phase: ToolCallPhase,
    val interaction: ToolInteractionKind,
)
```

目标 phase：

```text
CALL_STREAMING
READY
AWAITING_APPROVAL
AWAITING_INPUT
EXECUTING
COMPLETED
FAILED
CANCELLED
INTERRUPTED
DENIED
ANSWERED
```

UI 只根据 presentation 决定 Approve/Deny、回答表单、loading 和终态，不直接从 `approvalState` 或
`toolName == "ask_user"` 推断。`ask_user` 可以保留专用表单 renderer，但它只是 UI 特化，不是 Runtime 特例。

Room 中 `TurnExecutionStatus.AWAITING_APPROVAL` 第一阶段可以保留作为兼容 durable 编码，应用层统一解释为
“等待用户交互”；只有确有数据查询或约束需要时才做显式 migration，不能仅为命名美观迁移数据库。

具体映射是：durable `AWAITING_APPROVAL` + Runtime interaction `APPROVAL` 投影为 `ToolCallPhase.AWAITING_APPROVAL`；
同一个 durable turn 状态 + Runtime interaction `USER_INPUT` 投影为 `ToolCallPhase.AWAITING_INPUT`。UI 不直接解释
Room 枚举名称。

### 13.2 Inline/Archived 输出投影

```kotlin
sealed interface ToolOutputProjection {
    data class Inline(val parts: List<UIMessagePart>) : ToolOutputProjection
    data class Archived(
        val source: String,
        val characters: Long,
        val lines: Int,
        val terminalStatus: String,
    ) : ToolOutputProjection
}
```

`ToolUIContext` 使用统一 codec：Inline 保持现有 renderer；Archived 显示摘要和按页加载入口。详情通过
`ConversationQueryService` 的窄 query capability 调用同一个 `ToolOutputStore.read`；UI 不直连 ArtifactStore、DAO 或 File，
加载 Conversation 时也不把全部归档文本恢复进内存。

依赖完整 JSON 绘制摘要的特殊 Tool 必须使用 `PRESERVE`，不能让 renderer 自动读取几十万字符来维持旧 UI。

## 14. GenerationLoop 目标形状

```kotlin
for (step in 0 until maxSteps) {
    val definitions = toolProvider()
    val unresolved = currentUnresolvedToolCalls(messages)

    if (unresolved.isEmpty()) {
        val requestPlan = contextPlanner.planRequest(
            durableMessages = messages,
            messageLimit = assistant.effectiveContextMessageLimit(),
        )
        val receipt = requestModel(requestPlan.messages, definitions)
        messages = finishStreamingLast(messages)

        val archivePlan = contextPlanner.planPostStepCompaction(
            committedMessages = messages,
            receipt = receipt,
            budget = toolOutputBudget,
        )
        val staged = toolOutputStore.stageArchive(conversationId, archivePlan)
        val checkpointMessages = applyArchiveReplacements(messages, staged.replacements)
        staged.leases.forEach(unpublishedResources::register)

        commitCheckpoint(
            kind = CheckpointKind.STEP_COMPLETED,
            checkpointMessages = checkpointMessages,
            publishResources = true,
        )
        messages = checkpointMessages

        if (currentUnresolvedToolCalls(messages).isEmpty()) break
    }

    val batch = toolCallRuntime.prepareBatch(
        calls = currentUnresolvedToolCalls(messages),
        definitions = definitions,
        availability = interactionAvailability,
    )

    applyReplacements(batch.replacements)
    commitImmediateResults(batch.immediateResults)

    if (batch.pendingInteractions.isNotEmpty()) {
        finishAwaitingUser()
        break
    }

    batch.executableCalls.forEach { call ->
        commitToolStarted(call)
        val outcome = toolCallRuntime.execute(call, executionHooks(call))
        applyToolOutcome(outcome)
        commitToolOutcome(outcome)
    }
}
```

最终 `GenerationLoop` 不再拥有参数错误 JSON、审批矩阵、ask_user 名称白名单、ToolExecutionContext 具体构造、
timeout/empty 通用规范、输出路径、即时裁剪阈值和 shell 回查说明。

## 15. MCP、Workspace、Memory 和子助手边界

### 15.1 MCP

MCP 工具仍由 `GenerationToolSetFactory` 从冻结 `TurnMcpCapabilitySnapshot` 适配为 `Tool`。策略中的
`needsApproval` 映射为 `ToolInteractionRequirement.Approval`；`McpRuntimeCoordinator.callTool` 继续持有 admission、OAuth、
definition digest、远端 schema/业务校验和 Text/Image 协议投影。MCP 不新增专用审批或结果归档路径。

### 15.2 Workspace

Workspace 工具 requirement 仍由构造时捕获的 approval override 与规范化路径纯规则确定。Approve 后，
`WorkspaceApplicationService.executeTool` 继续重新校验当前 workspace、路径、权限与 command gate。
归档回查不依赖 `workspace_shell`，也不把 Tool Output 挂载到 Rootfs。

### 15.3 Memory

`memory_tool` 可以继续由 `GenerationLoop` 在每个 step 按当前 owner/namespace 加入 definitions，因为这是动态能力边界；
加入后仍经过同一个 `ToolCallRuntime`。不得为 Memory 保留第二套 parse/execute 分支。

### 15.4 子助手

Target 使用 `ToolInteractionAvailability.USER_INPUT_ONLY`。`ask_user` requirement 为 `UserInput`，可通过现有 Child interaction
桥接提交 typed `Answer`；Approval 工具自动得到 `approval_unavailable`，不再靠 `interactiveToolNames=setOf("ask_user")`。
Child 继续复用同一 `TurnHandle`，interaction 次数限制和 run gate 仍归 `DelegationCoordinator` / `SubAssistantRunGate`。

## 16. 文件级调整

### 16.1 新增

```text
app/src/main/java/net/weero/measix/pilot/data/ai/
└─ ConversationContextPlanner.kt

app/src/main/java/net/weero/measix/pilot/data/ai/tools/
├─ ToolCallRuntime.kt
├─ ToolOutputStore.kt
└─ ToolOutputTools.kt
```

不新增 module，也不再拆 `processor/pipeline/handler/strategy` 多层目录。

### 16.2 主要修改

| 文件/领域 | 调整 |
| --- | --- |
| `ai/.../core/Tool.kt` | `needsApproval` → typed requirement；将即时裁剪策略语义重定义为消费后可归档；保留纯 `parseArguments` |
| `GenerationLoop.kt` | 移出输入、interaction、执行包装和旧裁剪；加入 request receipt 与 planner 调用 |
| `GenerationToolSetFactory.kt` | 稳定注册两个回查工具；保留名冲突；传入 conversation-scoped capability |
| `MasterTurnCoordinator.kt` | typed `submitToolDecision` 与 `CONTINUE_USER_INTERACTION` |
| `ConversationCommands.kt` | `ResolveToolInteraction` command |
| `ConversationTransition.kt` | owner、locator、requirement/decision 和 durable state transition 校验 |
| `ConversationPresentation.kt` | `AWAITING_APPROVAL` / `AWAITING_INPUT` typed projection |
| `DelegationCoordinator.kt` | `USER_INPUT_ONLY` 与 typed Answer bridge |
| `Conversation.kt` | typed Artifact reference collector 与 Runtime metadata codec 使用 |
| `ArtifactStore.kt` | TOOL_OUTPUT text staging、retained scoped read、typed reference projection |
| `FileFolders.kt` | TOOL_OUTPUTS 改为受管 Artifact 目录 |
| `MeasixPilotApp.kt` | 删除 ephemeral tool_outputs 启动清理 |
| `workspace/.../ProotLaunchSpec.kt` | 删除 `/tool_outputs` bind mount 和常量；同步修改 `ProotLaunchSpecTest` |
| `ToolUI.kt` / `ChatMessageTools.kt` | typed decision、interaction presentation、Inline/Archived projection |
| Backup/Recovery | tool_outputs payload 纳入现有 Artifact 协议和一致性检查 |

## 17. 实施切片

每个切片完成时必须迁移其全部消费者并物理删除旧入口；不得保留 deprecated facade、双 API 或 fallback。

### 17.1 切片 A：ToolCallRuntime 与 typed interaction

- 引入 typed requirement、availability、decision；
- 新增 `ToolCallRuntime`，迁入一次 parse、gate、Denied/Answered、执行上下文和结果规范化；
- Master/Target、UI 和 command 迁移到 typed interaction；
- 删除 `resolveToolApprovals`、执行前第二次 parse、`nonInteractive`、`interactiveToolNames` 和 ask_user Runtime 名称特判；
- 此切片暂时保持旧输出行为，但旧即时裁剪只能有一个调用点，不得复制实现。

### 17.2 切片 B：受管 Tool Output 与回查能力

- 新增 `ToolOutputStore`、typed runtime metadata 和 source codec；
- `TOOL_OUTPUTS` 纳入 Artifact lifecycle；
- 修正 typed reference projection 并提升 projection version；
- 增加 read/grep 工具和 UI Archived query port；
- 补齐 fork、delete、GC、backup、restore、startup recovery；
- 删除 PRoot `/tool_outputs` mount，模型回查只能走 scoped tools；
- 可在测试/诊断中运行纯 planner 观察候选，但不得写 durable shadow state。

### 17.3 切片 C：切换统一上下文 planner 与滚动归档

同一变更中完成：

- 迁移请求级 `limitContext` 到 `ConversationContextPlanner` 并删除旧入口；
- `generateInternal` 返回 `ModelStepReceipt`；
- 启用成功 step 后滚动归档；
- 删除 32 KiB 即时裁剪、4 KiB preview、shell/path 提示、直接文件 IO 和 ephemeral 清理；
- 更新全部参考文档和静态架构契约。

切换后不能继续保留“单结果即时裁剪 + 历史滚动归档”两套生产策略。

## 18. 测试与验收矩阵

### 18.1 Tool 输入与执行

- 同一连续调用只 parse 一次；
- interaction 判断和 execute 取得同一 `JsonObject`；
- 空 buffer 按 `{}`，坏 JSON/non-object 保持 invalid；
- 工具动态撤销与 MCP definition digest 变化 fail-closed；
- Tool name 空值、重名和保留名冲突在请求前拒绝；
- timeout、普通异常、`ToolExecutionFailure` 和空结果具有稳定 replay；
- `CancellationException` 传播；
- Tool metadata 不能覆盖 `tool_runtime`；
- STARTED 一定先于真实副作用，资源只在结果 checkpoint 后 publish。

### 18.2 审批与用户输入

| 场景 | 必须满足 |
| --- | --- |
| 无交互工具 | 直接执行 |
| 单审批工具 | Pending，Turn 暂停 |
| 一批混有自动/审批工具 | 全部决定前任何工具都不执行 |
| Approve | 原 Turn 继续并执行 |
| Deny | 无 STARTED，形成 DENIED result |
| ask_user Answer | 无 STARTED，形成 ANSWERED result |
| Target USER_INPUT_ONLY | UserInput 可暂停，Approval 自动拒绝 |
| stale locator/turn | command 明确拒绝 |
| 重复同一决定 | 幂等 |
| 决定类型与 requirement 不匹配 | fail-closed |
| 重启后 Pending | 从 durable state 恢复 |
| 批准后工具/权限失效 | 由当前 definition/执行 owner 返回真实失败 |

### 18.3 请求上下文

- message limit 关闭、阈值内、跨台阶和同台阶锚点稳定；
- 起点始终对齐完整 USER 轮次；
- request plan 不修改 durable Conversation；
- Tool system prompt 与 Provider 使用同一 request messages；
- replay-safe projection、裁剪、transformer 和 Provider receipt 顺序一致；
- 普通历史不会被自动摘要或 durable 删除。
- 将 `ai/src/test/.../MessageTest.kt` 中的 `limitContext` 用例迁移到 app 层 planner 测试，并删除 ai 层旧生产函数与旧测试入口。

### 18.4 滚动归档

- 新 Tool Result 第一次被模型读取前绝不归档；
- Provider 失败、取消和序列化失败不归档；
- receipt 只记录最终实际发送的 inline output；
- 高水位触发后一次降到低水位，低于 minimum reclaim 不抖动；
- 最近两个批次与最近 12 KiB Tool 文本受保护；
- `PRESERVE`、混合媒体和回查工具结果不归档；
- marker 一旦提交保持稳定；
- 归档与本 step 新输出共用 `STEP_COMPLETED` durability boundary；
- checkpoint 失败时旧完整 output 仍在，暂存 Artifact 被精确回滚。

### 18.5 Artifact 生命周期与权限

- 多个候选 staging 全有或全无；
- 进程在 Artifact 创建后/checkpoint 前中断可恢复或回滚；
- checkpoint 后/lease publish 前中断由现有 startup reconcile 收口；
- fork 后 source 可读取；删除原会话不破坏 fork；最后引用消失后可 GC；
- 其他 conversation 猜测 artifact id 不能读取；
- payload 缺失 fail-closed；
- backup/restore 后 source 和 reference type 保持有效；
- reference projection 重建不会把 TOOL_OUTPUT 降级成 ATTACHMENT。

### 18.6 UI 与设备

- AWAITING_APPROVAL 只显示 Approve/Deny；
- AWAITING_INPUT 只显示回答表单；
- STOPPING 隐藏所有交互入口；
- Archived 详情分页读取，不在 Conversation 加载时读取全文；
- 特殊 renderer 的 PRESERVE 策略保持原 UI；
- Compose interaction、进程重启 Pending、backup/restore 和真实文件生命周期需要
  `connectedDebugAndroidTest` 及真机/模拟器场景，JVM 测试不能表述为设备验收通过。

### 18.7 性能证据

记录长 Agent 任务中的：

```text
每个 Provider 请求的 inline Tool Result characters / observed context tokens
归档触发次数与每次回收字符数
首次 marker 所在的 Provider 缓存前缀位置
归档前后 TTFT 与完整任务累计等待时间
read/grep 回查率
模型因找不到历史内容而重复执行原工具的比例
任务最终成功率
```

不能只用 cache hit 百分比证明方案有效。

## 19. 完成门禁

实现完成必须同时满足：

1. 生产代码中只有一个 ToolCall preparation/execution runtime；
2. 所有本地和 MCP ToolCall 都经过同一 typed interaction gate；
3. UI、Child bridge 和 command 不再用 ask_user 名称解释审批语义；
4. `GenerationLoop` 不再直接写 Tool Output 文件或构造通用审批/异常分支；
5. 请求级普通历史窗口只有 `ConversationContextPlanner` 一个入口；
6. Tool Result 不在第一次成功模型消费前归档；
7. Tool Output 只通过 ArtifactStore + TOOL_OUTPUT reference 持久化；
8. 模型和 UI 只通过 `ToolOutputStore` scoped read/grep 读取归档；
9. 旧即时裁剪、shell 回查、ephemeral cleanup、旧命名和无调用协议全部物理删除；
10. 对应参考文档、架构契约、测试和备份/恢复说明在同一交付更新。

架构或跨模块变更完成前运行与风险匹配的完整门禁：

```text
gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
git diff --check
```

涉及 Compose interaction、Artifact 文件恢复、数据库 migration 或真实系统行为时，另运行对应
`connectedDebugAndroidTest` 和真机/模拟器场景。

## 20. 最终不变量

1. **Tool definition 只定义 schema、纯校验、交互 requirement、输出策略和领域执行。**
2. **ToolCallRuntime 统一解释一次调用，但不拥有 Conversation、Turn、MCP、Workspace 或 Artifact durable state。**
3. **审批与 ask_user 共用暂停/继续基础设施，但不是同一种用户语义。**
4. **用户决定只通过 Conversation command 写入，并始终复用原 TurnHandle。**
5. **任何 Pending 都是整个 ToolCall 批次的执行屏障。**
6. **普通对话滚动窗口只改变请求投影，不改 durable history。**
7. **完整 Tool Result 先 durable、先被成功 Provider 请求读取，之后才允许可逆归档。**
8. **归档与成功 step 共用现有 checkpoint，不增加第二状态机。**
9. **Tool Output payload 复用 ArtifactStore 和 TOOL_OUTPUT reference，不新增表或文件 owner。**
10. **归档内容只能通过 conversation-scoped read/grep capability 回查，不暴露真实文件路径。**
