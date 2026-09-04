# V3：Conversation / Turn / Step 执行架构

系列：

| 文档 | 职责 |
| --- | --- |
| 本文 | 生产语义、owner、类型、Room/Backup 迁移、阶段与 Exit |
| [`v3-test-architecture-refactor-plan.md`](v3-test-architecture-refactor-plan.md) | 测试分层、权威测试、处置清单、CI、迁移样本 |

术语与阶段以本文为准。测试文件名、owner 矩阵、样本与删除规则只在测试方案维护。完成后，稳定内容进入 `docs/references/turn-step-execution.md`，测试规则进入 `docs/references/testing-strategy.md`；两份 dev 计划同时退休。

V3 不推翻 Conversation、Artifact、MCP、Settings。它把已经存在、但名称与所有权错位的执行语义对齐为一条主链：

```text
Conversation
  └─ Turn                          一次 Assistant 响应（一个 Assistant message variant）
      └─ Step                      一次逻辑模型采样 + 该响应对应的完整 Tool batch
          └─ Tool Call             模型提出的执行意图（transcript 事实）
              ├─ Tool Interaction  执行前的用户门禁（审批 / 提问）
              ├─ Tool Execution    副作用开始后才存在的执行事实（Room 行）
              └─ Tool Result       下一 Step 可回放的结果
```

约束：每个 durable 事实一个 owner、一个写协议；名称必须等于职责；旧数据必须经同一迁移器无损升级；禁止兼容双路径。

---

## 1. 唯一术语

下列词在代码、数据库、文档中只允许这一种含义。禁止用旧名或近义名并行。

| 术语 | 唯一含义 | 不是 |
| --- | --- | --- |
| Conversation | 用户可见持久会话与分支树 | Job、流式草稿、当前 Tool 是否在跑 |
| Turn | 针对 selected branch 与一条因果 USER，由一个 Assistant 完成的一次完整响应 | 批准/拒绝/回答、Tool 完成、rolling compaction、Provider 透明重试 |
| Step | 一次逻辑模型采样，以及该响应所请求的全部 Tool Call，直到每条 Call 都有可回放结果，或因等待用户而暂停 | 一次 HTTP；一次 checkpoint；一次 UI 刷新 |
| Provider request attempt | Step 内一次真实网络/SDK dispatch | 新的 durable 实体；新的 Step |
| Tool Call | transcript 中的执行意图（`localCallId` + `providerCallId`） | 执行行；审批状态 |
| Tool Interaction | Call 的用户门禁 | 执行状态；结果状态 |
| Tool Execution | 副作用开始后的 `tool_execution` 行 | 参数错误、不可用、拒绝、`ask_user` 已回答 |
| Tool Result | 提供给下一模型 Step 的可回放结果 | `Tool.output` 是否为空的猜测 |
| Checkpoint | awaited、原子、commit-then-publish 的持久化边界 | Step、phase、Turn outcome |
| TurnExecutionStatus | `turn_execution.status` 的 durable 状态（Room 枚举名，不另造 `TurnStatus` 类型） | UI loading |
| TurnLivePhase | 进程内展示/控制阶段 | 落库状态 |
| TurnOutcome | Turn 终态（不可回退） | `AWAITING_USER` 暂停 |
| TurnPause | Runner 因等待用户而返回的非终态结果 | TurnOutcome |
| Sub-assistant | 可被 `assistant_call` 调用的 Target 配置身份 | 一次运行 |
| Sub-assistant run | 一次 `assistant_call` 执行（`run_id`） | 父 Turn 的新 Step；父 Conversation 的新 Turn |

产品层可以说 Work = Conversation。代码与持久化只用 `Conversation`。

Master / Target 只描述会话角色：用户会话与 Child 会话。二者走同一条 Turn 链，不存在第二套生成引擎。

---

## 2. 外部实现取舍

| 来源 | 采用 | 不采用 |
| --- | --- | --- |
| DSH | Turn ≠ Step；Step = 模型调用 + 其 Tool batch；durable transcript ≠ live runtime；steering / 用户交互不是新 Turn | Event Sourcing、append-only session log、插件化 waterfall |
| Codex | 用户可见 Turn ≠ 内部采样 Step；Turn 级冻结上下文 + 每 Step 请求快照；强类型执行项 | Turn 中途重读配置以改变模型可见能力 |
| OpenCode | 显式 Step 边界；强类型 Tool 状态；per-step usage 与 Turn 累计分离 | 每个 Step 一条顶层 Assistant `MessageNode`；Session processor 兼任 Store / Tool Runtime / Compactor |

RikkaHub 特有、必须保持：

- 一个 Assistant message variant = 一个完整 Turn；多个 Step 存在于该 variant 内部。这是 regenerate、fork、分支选择、因果 USER、Disclosure owner 的稳定结构。
- Assistant / Model / Provider wire / Prompt inputs / Tool definitions / MCP catalog revision 在 Turn START 冻结。每个 Step 只从冻结 `TurnContext` + 已提交 transcript 派生 `StepRequestPlan`。
- 现有 Conversation 单写、树分支、Room delta、Artifact 引用继续作为持久化基础。

---

## 3. 当前错位（实施必须消灭）

1. **名称反了。** `GenerationLoop` 才是多 Step Turn 循环；`TurnEngine` 主要是 checkpoint / 流式发布 / 终态适配。
2. **Step 是推断的。** `UIMessage.appendChunk()` 用“最后一个尚无结果的 Tool”当隐式边界；usage、recovery、compaction receipt 都在猜。
3. **Checkpoint 冒充业务阶段。** `CheckpointKind.STEP_COMPLETED` 实际发生在模型请求成功、Tool batch 未完成时。
4. **Durable / runtime / streaming 混在 `activeTurn`。** `ActiveTurnState.messages` 复制整条当前分支；每个 chunk 可能带动完整 list。
5. **Tool 生命周期藏在 `metadata.tool_runtime` JSON。** interaction、terminalStatus、resultBatchOrdinal、archive 是影子 schema。
6. **Tool 身份依赖 `messageId + toolOrdinal`。** Provider `toolCallId` 可为 `""`、可重复、跨 Step 复用。
7. **Master 与 Child 重复装配。** `MasterTurnCoordinator` 与 `DelegationCoordinator` 各自走 Settings → tools → pipeline → GenerationLoop → TurnEngine。
8. **`ConversationTransition` 理解过多运行策略。** compaction、tool_runtime、checkpoint kind 进入了树 reducer。
9. **`TurnOutcome.AwaitingApproval` 不是终态。** 暂停被建模成 outcome，与 COMPLETED/FAILED 混在同一密封类型。
10. **`turn_execution.CREATED` 与 `AWAITING_APPROVAL` 名实不符。** 现行 `StartTurn` 直接写入 `RUNNING`；`CREATED` 是死状态。`AWAITING_APPROVAL` 已同时覆盖 `ask_user`，但库里的名字只有审批语义。现行 presentation 已用 `AWAITING_USER`，Room 仍写旧名。

---

## 4. 目标主链

```text
Compose / ViewModel
        │
        ▼
ConversationApplicationService     结构命令：创建/删除/fork/编辑树
ConversationTurnService            Turn 命令：send / regenerate / edit-resend / resolve interaction
        │
        ├─ 结构命令 ─► ConversationCommandCoordinator ─► ConversationTransition
        │                                              ─► TurnTransition
        │                                              ─► ConversationRepository（唯一事务）
        │
        └─ TurnContextFactory.prepareLaunch / materialize
                ▼
             TurnRunner
                ▼
             StepRunner
             ├─ RequestAssembler
             │    ├─ RequestContextPlanner
             │    └─ ContextBudget
             ├─ Provider（消费 ModelRequestMessage，不消费 UIMessage）
             ├─ ToolBatchRunner → ToolCallRuntime
             ├─ ToolOutputCompactionPlanner → ToolOutputStore
             └─ TurnCommitter → ConversationCommandCoordinator
```

| 事实或流程 | 唯一 owner |
| --- | --- |
| Conversation header / tree / variant | `ConversationTransition` |
| Turn / Step / Tool transcript | `TurnTransition` |
| 锁、事务、commit-then-publish | `ConversationCommandCoordinator` |
| Turn 上下文冻结 | `TurnContextFactory` |
| 多 Step 循环 | `TurnRunner` |
| 单个 Step | `StepRunner` |
| Tool batch 门禁与串行执行 | `ToolBatchRunner` |
| 单 Call 解析、gate、执行包装 | `ToolCallRuntime` |
| durable checkpoint 适配 | `TurnCommitter` |
| 进程内 Turn session | `ConversationRuntime`（私有 `ActiveTurnSession`） |
| 请求投影 | `RequestAssembler` |
| rolling compaction 选择 | `ToolOutputCompactionPlanner` |
| archive / read / grep | `ToolOutputStore` |
| Sub-assistant run：preflight、lineage、Child、父结果 | `SubAssistantRunCoordinator` |
| Child lineage / retention / delete | `SubAssistantLifecycle`（不改名） |
| stop / failure / cancel 终态准备 | `TurnFinalizer` |
| 进程重启收口 | `TurnRecovery` |

`ConversationCommandCoordinator` 仍是唯一写入入口，同时接受结构命令与 Turn 命令。不增加第二把锁、第二个 Repository。

---

## 5. 语义合同

### 5.1 Conversation

继续负责：历史、regenerate variant、branch selection、fork、标题、文件夹、会话级 Prompt、Disclosure 在历史上的因果位置。

不负责：当前 Job、模型请求阶段、Tool 是否执行中、UI loading、Provider transport、流式草稿。

`ConversationAggregateSnapshot` 只含 `header`、`nodes`、`modelContextEntries`。删除 `activeTurn`。

### 5.2 Turn

触发（新 `turnId`、新 Assistant variant、冻结 `TurnContext`）：

- 发送 USER 并请求回答
- regenerate Assistant
- 编辑 USER 并重新发送
- Sub-assistant child task
- 后续受管交互

不是新 Turn：批准、拒绝、`ask_user` 回答、Tool 执行完成、Result 回模型、rolling compaction、Provider 透明重试。

START 与首个 `UIMessagePart.Step` 同事务提交。准备失败发生在 START 前：USER 可以已落库，Assistant Turn 不得被伪造成已开始。已提交 Turn 至少有一个 Step。删除 durable `CREATED`。

`AWAITING_USER` 时仍是同一 Turn、同一 `stepId`。`TurnRunner` 此时返回 `TurnPause`，不是 `TurnOutcome`。

### 5.3 Step

```text
Step
  ├─ one logical model request
  │    └─ one or more transparent Provider attempts
  ├─ model output
  ├─ zero or more Tool Calls
  ├─ zero or more Tool Executions（仅副作用开始后）
  ├─ exactly one Result for every closed Tool Call
  └─ StepOutcome?
```

- 未接受任何模型输出前的网络重试 = 同一 Step。
- 一旦形成 durable model output，下一次模型调用 = 下一 Step。
- 无 Tool Call 时可直接成为 Turn 的最终 Step。
- 有 Tool Call 时，全部有 Result 后才能 close。
- 等待 Approval / UserInput 时 Step 保持打开，Turn = `AWAITING_USER`；用户决策后不得新建 Step。
- Tool Results 齐备并需要再次采样：close 当前 Step，open 下一 Step。

不新增 `step_execution` 表。Step 没有独立于 Turn 的生命周期，不需要全局分页或单独 GC；fork/clone 必须随 Assistant transcript 复制。Process recovery 经 `turn_execution` 定点到非终态 Turn，加载 owning Assistant message 即可找到尾部 open Step。

### 5.4 Tool 四态分离

**Tool Call**（transcript）：`localCallId`、`stepId`、`providerCallId`、`toolName`、`input`（即模型给出的参数字符串；不另设 `rawArguments` 第二名字）。

**Tool Interaction**：

```text
NotRequired
AwaitingApproval
AwaitingInput
Approved
Denied(reason)
Answered(answer)
```

禁止 `Pending + metadata.interaction=user_input` 这种影子区分。删除 `ToolApprovalState`。不要用 `None`：它无法表达“无需用户、自动执行”。

**Tool Execution** 仅在副作用开始后创建。Room 枚举 `ToolExecutionStatus`：

```text
STARTED → COMPLETED | FAILED | CANCELLED | UNKNOWN
```

终态不可回退。不创建行：参数无效、Tool 不可用、审批不可用、用户拒绝、`ask_user` 的答案本身就是 Result。

**Tool Result**：`Completed` / `Failed` / `Denied` / `Answered` / `Cancelled` / `Interrupted` / `Unknown`。

对应关系（有 execution 行时）：

| Execution | Result |
| --- | --- |
| COMPLETED | Completed |
| FAILED | Failed |
| CANCELLED | Cancelled |
| UNKNOWN | Unknown |
| （无行） | Denied / Answered / Failed（执行前） / Interrupted |

`Unknown` 仅当：副作用已 STARTED，结果提交前进程中断，无法确认远端是否已发生，且不允许自动重试。

Locator：

```kotlin
data class ToolCallLocator(
    val assistantMessageId: Uuid,
    val stepId: Uuid,
    val localCallId: Uuid,
)
```

ordinal 只用于 UI 顺序，不再承担身份。

### 5.5 Sub-assistant run

```text
Parent Turn
  └─ Parent Step
      └─ assistant_call Tool Execution
          └─ Sub-assistant run
              ├─ Child Conversation
              └─ Child Turn → Child Steps
```

父 Execution 等待 Child Turn 终态，再投影为父 Tool Result。同步、单层委托、Child Conversation、run lineage 不变。不实现跨进程续跑、异步 mailbox、多层递归。

Child `ask_user`（不得由实现者另猜）：

| 项 | 规定 |
| --- | --- |
| Child Turn | `AWAITING_USER`，同一 `stepId` 打开 |
| 父 Turn | 保持 `RUNNING`；live phase = `TOOL_EXECUTING`。**父不进入 `AWAITING_USER`**（那只表示父自己的 Tool batch 门禁） |
| 父 Execution | 保持 `STARTED`；`child_conversation_id` / `child_turn_id` / `sub_assistant_run_id` 已写入 |
| UI | 只在父 `assistant_call` 卡片上作答。身份 = Child `ToolCallLocator` + `run_id` + `interaction_id`（`sub_assistant_call.user_interaction`） |
| 提交 | `resolveToolInteraction` 打在 **Child** conversation。禁止把 Child 决定写成父 Tool Interaction |
| 继续 | Child 同一 Turn/Step 的 `ToolBatchRunner` resume；父 Execution 继续等 Child 终态 |
| 进程重启 | 先收口 Child（open Step → Interrupted，STARTED → Unknown）；再收口父 `STARTED` `assistant_call` → Unknown Result。父 Turn 按 §7 中断 |

配置/策略/UI/metadata 继续使用 Sub-assistant 词根（`allowAsSubAssistant`、`sub_assistant_call`、`SubAssistantLifecycle`）。执行入口只把 `DelegationCoordinator` 改成 `SubAssistantRunCoordinator`：它拥有一次 run，不是通用 delegation 框架。禁止再引入 SubAgent 第二词根。`TurnKind` 取 `USER` / `SUB_ASSISTANT`，不用 `SUB_AGENT`。

`TurnPause(pendingInteractions)` 是 Runner 的非终态返回值，不放进 `TurnOutcome`。

### 5.6 Checkpoint

删除 `CheckpointKind` 与 `STEP_COMPLETED`。改成具名 payload：

```text
ModelResponseCheckpoint
ToolExecutionStartedCheckpoint
ToolExecutionUpdatedCheckpoint
ToolResultCheckpoint
FinalizeTurn
```

`ToolResultCheckpoint` 可原子携带当前 Step close + 下一 Step open，避免空事务。

---

## 6. Assistant transcript 与类型

### 6.1 保留：一个 Assistant variant = 一个 Turn

不采用“一个 Step 一条顶层 Assistant message”。

### 6.2 `UIMessagePart.Step`

```kotlin
@Serializable
@SerialName("step")
data class Step(
    val stepId: Uuid,
    val ordinal: Int,
    val startedAt: Instant,
    val modelResult: StepModelResult? = null,
    val outcome: StepOutcome? = null,
    val finishedAt: Instant? = null,
    override val metadata: JsonObject? = null,
) : UIMessagePart
```

只用这一种 part，不用 StepStart/StepFinish 双 marker。不可变 copy 更新：

| modelResult | outcome | 含义 |
| --- | --- | --- |
| null | null | 采样未完成 |
| 非 null | null | 模型已提交，Tool 准备/执行/等待用户 |
| 任意 | 非 null | Step 已结束 |

形态：

```text
ASSISTANT MESSAGE
  Step(ordinal=0)
  Reasoning / Text / Media
  Tool Call A
  Tool Call B
  Step(ordinal=1)
  Reasoning / Text
```

从一个 `Step` 到下一个 `Step` 之前的 part 属于该 Step。

不变量：

- 已提交 Assistant Turn 的第一个执行 part 必须是 `Step(ordinal=0)`。
- ordinal 严格递增、从 0 起。
- 新 Step 出现前，上一 Step 必须已有 outcome。
- 最多一个 open Step，且必须在末尾。
- Tool 的 `stepId` 等于其前方最近的 `Step`。
- `Step` 不得出现在 Tool output 内、不进入 Provider、不渲染、不进 FTS、不进摘要。
- 只有 Turn 命令 reducer 可以更新 `Step`：V3-B 仍在 `ConversationTransition` 的 Turn 分支；V3-C 起仅为 `TurnTransition`。

`Step` 不是 `Text`，现有 `extractFtsText()` 已只索引 Text，迁移不必重建 FTS。

### 6.3 StepModelResult / StepOutcome

```kotlin
data class StepModelResult(
    val finishReason: String?,
    val usage: StepUsage,
    val providerRequestCount: Int,
    val timeToFirstOutputMillis: Long?,
    val requestDurationMillis: Long?,
    val usageCompleteness: UsageCompleteness,
    val providerMetadata: JsonObject?,
)
```

Turn 总 usage 仍在 Assistant message 的累计 `TokenUsage`。迁移数据的 per-step `usageCompleteness = UsageCompleteness.LEGACY`，`providerRequestCount = 0`，时长字段为 null，禁止写 0 冒充实测。

```text
StepOutcome:
  Continue          Tool Results 齐备，需要下一模型 Step
  Final             本 Step 给出 Turn 最终结果
  Failed / Cancelled / Incomplete / Interrupted
                    同时推动 Turn 进入对应终态
```

### 6.4 Tool part

```kotlin
UIMessagePart.Tool(
    localCallId: Uuid,
    stepId: Uuid,
    providerCallId: String,
    toolName: String,
    input: String,
    output: List<UIMessagePart>,
    interactionState: ToolInteractionState,
    resultStatus: ToolResultStatus?,
    runtimeState: ToolRuntimeState,
    metadata: JsonObject?,
)
```

`ToolRuntimeState` 只保留 `outputPolicy` 与可选 `archive`。下列 JSON 字段退出 runtime：

- `interaction` → `ToolInteractionState`
- `terminalStatus` → `ToolResultStatus`
- `resultBatchOrdinal` → 显式 Step
- `archive` → `ToolRuntimeState.archive`

`metadata["tool_runtime"]` 只允许 `LegacyTurnTranscriptMigrator` 读取。V3 runtime 禁止读写。`metadata` 全部改为 `val`；patch 必须 `copy()`。

`sub_assistant_call` 顶层键名不变。`SubAssistantUserInteraction` JSON：删除 `tool_ordinal`，新增 `local_call_id`（字符串 UUID）；`schema_version` 升到 2。卡片定位用 `local_call_id`，不再用 ordinal。`tool_execution.sub_assistant_run_id` 是恢复用执行事实；投影仍读 metadata；同事务写入必须一致。

V3 起 `collectArtifactReferences` 从 `ToolRuntimeState.archive` 收集 TOOL_OUTPUT，不再读 `tool_runtime`。Migrator 写完 typed archive 后，引用集合必须与转换前相等。

### 6.5 UIMessage

不重命名 `UIMessage` / `UIMessagePart`（上游 `:ai`、fork 冲突成本高，改名不修复语义）。必须：

1. 全部 `metadata` 改为 `val`
2. 删除 `appendChunk()`、`operator plus` 等运行行为；流式合并移到 `StepOutputAccumulator`（V3-B 即可把该 accumulator 暂放在 `GenerationLoop` 内，V3-D 随 `StepRunner` 搬家）
3. V3-F 起 Provider 不再直接读取 `UIMessage`；V3-B～E 仍读 `UIMessage`，但必须经唯一过滤点丢掉 `Step`（见下）

Room 使用 `JsonInstant`（`ignoreUnknownKeys = true`，缺字段仍失败）。V3 `Tool` 不得直接解码旧 JSON。Migrator 用 **Legacy DTO / JsonElement 手术** 写出 V3 键名后再交给运行时解码。运行时字段与 JSON 键：

| 运行时 | JSON 键 |
| --- | --- |
| `localCallId` | `localCallId` |
| `stepId` | `stepId` |
| `providerCallId` | `providerCallId`（由旧 `toolCallId` 改写，不保留旧键） |
| `interactionState` | `interactionState`（由旧 `approvalState` 改写） |
| `resultStatus` | `resultStatus` |
| `runtimeState` | `runtimeState` |

禁止给 V3 `Tool` 加旧键别名或默认空 UUID。未迁移 payload 必须解失败。

---

## 7. Durable / Runtime / Streaming

```text
ConversationAggregateSnapshot     已提交树，无运行态
CommittedTurnProjection           最近一次成功 checkpoint 的 Turn 身份与 pending interactions，无消息副本
TurnStreamProjection              唯一高频流式态：当前 Assistant message + live phase + tool phases
ActiveTurnSession                 ConversationRuntime 私有：handle、TurnContext、current Step、worker
ConversationPresentationSnapshot  aggregate + committed + stream
```

`TurnStreamProjection` 只含当前 Assistant message，不复制整条 branch。流式只替换最后一个 Assistant variant，历史节点保持引用共享。UI 不持有 Session 或 Job。

用户交互暂停：Session 与 `TurnContext` 保留，worker 可结束，resume 安装新 worker，Turn/Step 身份不变。

进程重启后 `TurnContext` 不可恢复，fail-closed：

```text
nonterminal Turn        → Interrupted
STARTED Tool Execution  → Unknown
open/pending Tool Call  → Interrupted result
open Step               → Interrupted
```

不实现跨进程继续执行。升级后仍为 `RUNNING` / `AWAITING_USER` 的行走 `TurnRecovery`，按**上表**收口——这与现行 recovery（只改 STARTED 的 output、不关 Pending）不同，是 V3 行为。SQL 迁移不得把这些行标成终态。`CREATED` 在 SQL 中直接 `INTERRUPTED`（§11.5），不交给 recovery 再猜。

---

## 8. 状态机

### 8.1 TurnExecutionStatus（Room `turn_execution.status`）

```text
RUNNING ⇄ AWAITING_USER → COMPLETED | CANCELLED | FAILED | INCOMPLETE | INTERRUPTED
```

终态不可回退；重复相同终态幂等；不同终态冲突。

删除 `CREATED`。将库值 `AWAITING_APPROVAL` 重命名为 `AWAITING_USER`。枚举仍叫 `TurnExecutionStatus`，定义在 `data/db/entity`，供 Room 与 CAS 使用。`TurnProtocol.kt` 不复制一份。

`TurnOutcome` 只表示终态。暂停由 `TurnExecutionStatus.AWAITING_USER` + `CommittedTurnProjection.pendingInteractions` + `TurnPause` 表达。

### 8.2 TurnLivePhase

```text
PREPARING
MODEL_WAITING
MODEL_STREAMING
TOOL_PREPARING
AWAITING_USER
TOOL_EXECUTING
STOPPING
```

没有 `IDLE`：没有 active presentation 即 idle，避免“无 Turn 但 phase=IDLE”。

现行 `ConversationTurnPhase.GENERATING` 过粗，由上表替换。UI 字符串可映射，但代码枚举不得再合并回 GENERATING。

### 8.3 Tool

**Pending** 只表示 `AwaitingApproval | AwaitingInput`，不是独立枚举。同一 batch 中任一 Pending：全部自动 Tool 暂不执行；完整 batch 原子进入 `AWAITING_USER`；全部决策完成后按模型顺序串行执行。不引入通用并行 scheduler。

未执行的拒绝只提交消息内 Result，不创建 `tool_execution`。

**Batch 失败策略（串行已开始执行之后）：**

| 失败种类 | 本 Call | 同 batch 尚未执行的 Call | Turn |
| --- | --- | --- | --- |
| 执行前：参数/不可用/拒绝/Answered | Result only，无 execution | 按原顺序继续（Pending 仍先形成屏障） | 不因此终结 |
| Tool 领域或执行失败（已 STARTED） | execution FAILED + Result Failed | **继续**执行剩余 Call，把失败交给下一模型 Step | 不因此终结 |
| Checkpoint / 事务 / Artifact 发布协议失败 | 本事务回滚或 fail-closed | 停止 | Turn Failed / Incomplete |
| 用户取消 / supersede | STARTED → Unknown 或 Cancelled（已确认无副作用则 Cancelled） | Interrupted Result，无 execution | Turn Cancelled |
| 进程重启 | STARTED → Unknown | Interrupted Result | Turn Interrupted |

「partial execution failure」= 上表第二行：一个 Call 失败不短路整个 batch，也不直接失败 Turn。安全、持久化、所有权错误不是领域失败，不得装成 Tool Result 后继续。

---

## 9. 运行流程（合同级）

### 9.1 新 USER Turn

```text
ConversationTurnService.send
  → 一次 EffectiveSettingsSnapshot
  → 预处理 USER
  → AppendUserMessage
  → TurnContextFactory.prepareLaunch        // 短生命周期 TurnLaunchPlan；可失败，此时无 Turn
  → pre-START live revalidation
  → StartTurn 事务：Assistant variant + Step(0) + turn_execution RUNNING + 可选 model-context
  → TurnContextFactory.materialize          // 见下
  → ConversationRuntime.openTurnSession
  → TurnRunner.run
```

`materialize` 只绑定 `prepareLaunch` 已捕获的快照，**禁止 IO、禁止重读 Settings**。正常路径不可失败。若仍抛错（编程错误）：立即 `FinalizeTurn`（Failed，reason=`turn_context_materialize`），关闭 Session，不得留下无 `TurnContext` 的 RUNNING。

### 9.2 单个 Step

```text
StepRunner
  → RequestAssembler（replay-safe、window、disclosure、transformers、ModelRequestMessage、receipt）
  → live Provider credential
  → stream（只更新 active Assistant message）
  → close request usage
  → ToolCallRuntime.prepareBatch
  → ToolOutputCompactionPlanner.planAfterSuccessfulRequest
  → 分支见下
```

采样成功后的持久化边界：

| 本 Step | 事务 |
| --- | --- |
| 无 Tool Call，outcome = Final | **一次** `FinalizeTurn`：写入 `modelResult` + Step Final + Turn COMPLETED。不发单独的 `ModelResponseCheckpoint`。无 Tool 单 Step Turn 的核心事务 = START + terminal（§13） |
| 有 Tool Call（含全部是执行前失败） | 必须先 `ModelResponseCheckpoint`（modelResult、calls、immediate results、pending、compaction）。有 Pending → pause；否则 `ToolBatchRunner`。末个 Result 可与 Step close / next Step / `FinalizeTurn` 同事务 |

### 9.3 用户交互继续

```text
resolveToolInteraction(locator, decision)
  → 校验 Turn=AWAITING_USER、current stepId、localCallId、decision kind
  → durable commit
  → 仍有 Pending? 保持 AWAITING_USER
  → 否则 Turn=RUNNING，resume 同一 Session / 同一 Step 的 ToolBatchRunner
```

禁止：新 Turn、新 Step、重读 Settings、重发现 MCP、换 Tool schema、重跑 Input Transformer、重注入 Disclosure、第二 Assistant message。

### 9.4 取消 / 失败 / 中断

`TurnFinalizer` 只读取 `ActiveTurnSession` 的最新 Assistant draft，不得改读 durable node 覆盖已流出内容。输入从完整 `messages` 缩小为单个 Assistant message。STARTED 副作用在必要时成为 Unknown。同一事务关闭 Step、Tool、Turn。

---

## 10. 文件与类重命名

同一交付内物理删除旧符号，禁止 deprecated 转发。下表是完整清单。

### 10.1 生产代码

| 当前 | V3 | 说明 |
| --- | --- | --- |
| `service/MasterTurnCoordinator.kt` `MasterTurnCoordinator` | `service/ConversationTurnService.kt` `ConversationTurnService` | 用户会话 Turn 入口；去掉相对 Child 的 Master 命名 |
| `data/ai/GenerationLoop.kt` `GenerationLoop` | `service/turn/TurnRunner.kt` `TurnRunner` | 真正的多 Step 循环 |
| 同上文件中的 Step 循环体 | `service/turn/StepRunner.kt` `StepRunner` | 一次采样 + 完整 Tool batch |
| 同上文件中的 batch 执行 | `service/turn/ToolBatchRunner.kt` `ToolBatchRunner` | batch gate、暂停、串行执行 |
| `service/runtime/TurnEngine.kt` `TurnEngine` | `service/turn/TurnCommitter.kt` `TurnCommitter` | 只适配 checkpoint / terminal command |
| `service/runtime/TurnRequestContext.kt` `TurnRequestContext` | `service/turn/TurnContext.kt` `TurnContext` | START 冻结上下文 |
| `ResolvedAssistantRequest` | `TurnAssistantSnapshot` | |
| `ResolvedModelRequest` | `TurnModelSnapshot` | |
| `FrozenTurnPromptInputs` | `TurnPromptSnapshot` | |
| `service/runtime/TurnRequestContextFactory.kt` | `service/turn/TurnContextFactory.kt` | `prepareLaunch` + `materialize` |
| `data/ai/tools/GenerationToolSetFactory.kt` | `data/ai/tools/TurnToolSetFactory.kt` | Turn 级捕获 |
| `ToolSetRunMode` | 删除；由 `TurnKind.USER / SUB_ASSISTANT` 决定 | 禁止第二套运行分类；不要用 `SUB_AGENT` |
| `service/TurnFinalization.kt` `TurnFinalization` | `service/turn/TurnFinalizer.kt` `TurnFinalizer` | |
| `service/TurnRecovery.kt` | `service/turn/TurnRecovery.kt` | 只搬家 |
| `service/runtime/DelegationCoordinator.kt` `DelegationCoordinator` | `service/subassistant/SubAssistantRunCoordinator.kt` `SubAssistantRunCoordinator` | 拥有一次 run |
| `service/SubAssistantRunGate.kt` | `service/subassistant/SubAssistantRunGate.kt` | 只搬家，不改名 |
| `service/SubAssistantLifecycle.kt` | `service/subassistant/SubAssistantLifecycle.kt` | 只搬家，不改名 |
| `data/ai/ConversationContextPlanner.kt` | `data/ai/request/RequestContextPlanner.kt` | 仅请求前 |
| 新增 | `data/ai/tools/ToolOutputCompactionPlanner.kt` | 仅请求成功后 |
| `data/ai/ContextTrimmingPolicy.kt` | `data/ai/request/ContextBudget.kt` | 请求预算 |
| `ToolOutputProtocolLimits` | `data/ai/tools/ToolOutputProtocol.kt` | read/grep/marker 上限 |
| 新增 | `data/ai/request/RequestAssembler.kt` | UIMessage → ModelRequestMessage |
| 新增 | `ai/.../core/ModelRequestMessage.kt` | Provider 唯一输入 |
| `GenerationChunk` / `GenerationRequest` / `TurnEvent` / `FinishedReason` | 删除 | Runtime StateFlow + `TurnOutcome` / `TurnPause` |
| `GenerationCheckpoint` / `CheckpointKind` | `TurnCheckpoint` 密封类型 | |
| `CommitCheckpoint` | `CommitTurnCheckpoint` | 载荷改为 owning Assistant + facts，禁止完整 messages list |
| `ToolExecutionEvent` | `ToolExecutionFact` | 删除 `ToolExecutionEventStatus`，直接用 `ToolExecutionStatus` |
| `ToolResultEvent` | `ToolResultFact` | 删除 `ToolResultEventStatus`，直接用 `ToolResultStatus` |
| `ToolInteractionKind` | 删除 | 门禁只由 `ToolInteractionState` 表达 |
| `ActiveTurnState` | 删除，拆为 `CommittedTurnProjection` + `TurnStreamProjection` | |
| `ConversationRuntime.ActiveTurnRuntime` | `ActiveTurnSession` | |
| `InstalledActiveRequest` / `CapturedActiveRequest` | `InstalledTurnWorker` / `CapturedTurnWorker` | |
| `ConversationTurnPhase` | `TurnLivePhase` | |
| `ToolCallPhase` | `ToolLivePhase` | |
| `ToolApprovalState` | `ToolInteractionState` | `:ai` 类型，无兼容别名 |
| `ToolInteractionAvailability` | `TurnInteractionCapability` | |
| `PendingInteraction` | `PendingToolInteraction` | |
| `ToolUserDecision` | `ToolInteractionDecision` | |
| `ToolRuntimeMetadata` | 删除；运行时用 `ToolRuntimeState`；历史用 migrator 私有 decoder | |
| `ModelStepReceipt` | `ModelRequestReceipt` | |
| `planPostStepCompaction` | `planAfterSuccessfulRequest` | |
| `ConversationCommands.kt` 中的 Turn 命令 | `service/runtime/TurnCommands.kt` | 见下 |
| 新增 | `service/runtime/TurnTransition.kt` | |
| 新增 | `service/turn/TurnProtocol.kt` | 仅运行时：`TurnKind`、`TurnHandle`、`StepHandle`、`TurnCheckpoint`、`TurnPause`、`PendingToolInteraction`。`TurnExecutionStatus` 留在 entity |
| 新增 | `data/db/migrations/Migration_10_11.kt` | |
| 新增 | `data/db/transcript/LegacyTurnTranscriptMigrator.kt` | 唯一旧 payload 转换器 |
| `ConversationRuntime.retainAwaitingApproval` 等 | `retainAwaitingUser` / `continueAwaitingUser` / `isAwaitingUser` / `markAwaitingUser` | Turn 级暂停，不再叫 Approval |
| `AppEvent.ChatGenerationAwaitingApproval` | `AppEvent.ChatGenerationAwaitingUser` | 架构测试同步改事件名 |
| `ChatNotificationManager.handleAwaitingApproval` | `handleAwaitingUser` | |
| `FinishedReason.AwaitingApproval` | 随 `FinishedReason` 删除 | 由 `TurnPause` 替代 |

Call 级 `ToolLivePhase.AWAITING_APPROVAL` 与 `AWAITING_INPUT` 保留：那是单 Call 的门禁种类，不是 Turn 状态。

结构命令留在 `ConversationCommands.kt`：`AppendUserMessage`、`EditMessageVariant`、`DeleteMessage`、`SelectNodeVariant`、`TruncateToNodeIndex`、`ReplaceMessageTree`、`BackfillAttachmentRefs`、`HeaderConversationCommand`。

Turn 命令在 `TurnCommands.kt`：`StartTurn`、`CommitTurnCheckpoint`、`ResolveToolInteraction`、`FinalizeTurn`、`RecoverInterruptedTurn`。根类型仍是 `ConversationCommand`（coordinator 已按此分发）。不另造 `DurableConversationCommand`。

`ResolveToolInteraction` / `ToolCallLocator` 的身份字段改为 `assistantMessageId + stepId + localCallId`，删除 ordinal。

### 10.2 明确不改名

```text
Conversation, MessageNode, UIMessage, UIMessagePart
ConversationCommandCoordinator, ConversationRuntime, ConversationRuntimeRegistry
ConversationRepository, ConversationApplicationService, ConversationQueryService
ToolCallRuntime, ToolExecutionBinding, ToolOutputStore
ArtifactStore, ArtifactPayloadStore
McpRuntimeCoordinator, McpServerRuntime, McpCatalogStore
SettingsStore
SubAssistantLifecycle, SubAssistantRunGate, SubAssistantCallMetadata
assistant_call, assistant_inspect, assistant_manage
read_tool_output, grep_tool_output
sub_assistant_call          // metadata 键
```

### 10.3 目标目录

不新增 Gradle module。

```text
app/src/main/java/net/weero/measix/pilot/
├─ service/
│  ├─ ConversationApplicationService.kt
│  ├─ ConversationQueryService.kt
│  ├─ ConversationTurnService.kt
│  ├─ turn/          TurnProtocol, TurnContext, TurnContextFactory,
│  │                 TurnRunner, StepRunner, ToolBatchRunner,
│  │                 TurnCommitter, TurnFinalizer, TurnRecovery
│  └─ subassistant/  SubAssistantRunCoordinator, SubAssistantRunGate,
│                    SubAssistantLifecycle, SubAssistantFork,
│                    SubAssistantInterruptionProjection,
│                    SubAssistantRetention 及同域其余文件
├─ service/runtime/  ConversationRuntime, Registry, CommandCoordinator,
│                    ConversationCommands, TurnCommands,
│                    ConversationTransition, TurnTransition,
│                    ConversationPresentation
├─ data/ai/request/  RequestAssembler, RequestContextPlanner, ContextBudget
├─ data/ai/tools/    TurnToolSetFactory, ToolCallRuntime, ToolRuntimeState,
│                    ToolOutputCompactionPlanner, ToolOutputProtocol, ToolOutputStore
└─ data/db/transcript/  LegacyTurnTranscriptMigrator
```

### 10.4 测试

生产类重命名后，测试 package 与类名跟生产走。权威测试、巨型测试拆解、删除条件见测试方案 §5–§9。架构测试只保留三类静态约束（依赖方向、退休符号、协议不变量），细则同样在测试方案。

---

## 11. Room 与 Backup 迁移

当前：`APP_DATABASE_VERSION = 10`，备份 `rikkahub-durable-v4`。`message_node` **没有** payload 版本列。现行 START 写入 `RUNNING`，`CREATED` 只存在于枚举与恢复白名单。

V3：`Migration_10_11`，`APP_DATABASE_VERSION = 11`，备份 `rikkahub-durable-v5`。

### 11.1 设计原则

1. **一次转换、运行时单读。** 正常 runtime 只接受 V3 transcript。旧 decoder 只存在于 `LegacyTurnTranscriptMigrator`，只被 Room migration 与 backup restore 的同一升级链调用。
2. **幂等。** Room 不为普通 `Migration` 包事务（见 `Migration_6_7`）。崩溃后 version 仍为 10，升级会重跑。`migrate()` 必须可重复执行：已转换行跳过，DDL 用存在性检查。
3. **失败即失败。** 单行 JSON 损坏、非终态 `tool_execution` 找不到 owning Tool、转换后不变量不成立 → 整个升级失败，不得跳过、删除或“尽量恢复”。不得用 `decodeListLenient` 跳过未知 part。
4. **不伪造精确历史。** Step 边界在旧数据里本就是推断的。迁移保证顺序、身份、Artifact、Provider replay 字符串；不保证 per-step usage 或精确采样次数。
5. **不改用户可见正文。** Text/Reasoning/Media/Tool input/output/archive ref 字节级保持。不重建 FTS（`Step` 不是 Text）。
6. **新安装走实体默认 schema**，不跑 10→11 的 JSON 扫描。

### 11.2 不采用的错误做法

- 给 `message_node` 增加 `payload_version DEFAULT 2` 再写成 3：当前根本没有 v2 列；DEFAULT 2 会让新行/未转换行带上假版本。
- 先 `ADD COLUMN ... DEFAULT 3` 再转换：未转换行会被标成已是 V3。
- 把 `CREATED` 改成 `RUNNING` 再等 recovery：CREATED 从未开始执行，应在迁移中直接 `INTERRUPTED`。
- 为 Step 建表、为 Provider attempt 建表。
- 运行时双读旧 `tool_runtime` / `ToolApprovalState`。
- 静默丢弃找不到 Tool 的非终态 execution。

### 11.3 `message_node.transcript_schema`

新增列，**仅作 fail-closed 哨兵**，不是第二份 transcript。DDL 必须与 fresh schema **逐字同构**（`Migration_9_10` 已锁定此规则）。SQLite 禁止无 DEFAULT 的 `ADD COLUMN NOT NULL`，因此实体必须声明 Room default：

```kotlin
@ColumnInfo(name = "transcript_schema", defaultValue = "3")
val transcriptSchema: Int = 3
```

fresh `CREATE TABLE` 与 `ALTER TABLE ... ADD COLUMN ... INTEGER NOT NULL DEFAULT 3` 都带 `DEFAULT 3`。Kotlin 属性默认值单独存在不能生成该子句。

顺序（幂等）：

1. 用 `PRAGMA table_info(message_node)` 判断列是否已存在。不存在则先不要加列。列已存在时仍必须扫描 JSON（幂等），不得把“有列”当成“已转换完毕”。
2. 游标逐行读取 `message_node(id, messages)`，不一次装入全库。
3. `LegacyTurnTranscriptMigrator.convertNode(id, messagesJson, turnByAssistantMessageId)`：已是 V3 且不变量成立则原样返回。
4. `UPDATE message_node SET messages=? WHERE id=?`（仅当 JSON 变化）。
5. 全部 JSON 成功后：若列不存在，执行 `ALTER TABLE message_node ADD COLUMN transcript_schema INTEGER NOT NULL DEFAULT 3`。
6. 断言每一行 `transcript_schema = 3`；抽查转换不变量。

Runtime 装载 `!= 3` fail-closed。新插入只写 3。当前生产 = 无此列，不要用 1/2 表示旧数据。

### 11.4 Transcript 转换算法

输入：`message_node.messages` = `List<UIMessage>` JSON。USER/SYSTEM 消息结构不变。只改写 ASSISTANT variant。`var metadata` → `val` 不改变序列化。

Migrator 需要一份 `assistantMessageId → TurnExecutionStatus?` 查找表（无 turn 行视为历史已完成消息，v5 之前大量存在）。解码用 **Legacy DTO**（仍含 `toolCallId` / `approvalState` / 无 `Step`），不得用 V3 `UIMessagePart.Tool` 去解旧行。写出时只含 V3 键。

同一节点内多个 Assistant variant 各自独立转换；`toolOrdinal` 按 **该 variant 自己的 Tool 顺序**，与现行 `getTools()` 一致。

对每个 Assistant message：

1. **已是 V3：** 存在 `@SerialName("step")` part → 校验不变量，通过则跳过。
2. **空 parts：** 插入 `Step(ordinal=0)`。Turn 非终态（RUNNING / AWAITING_USER）→ `modelResult=null, outcome=null`。Turn 终态或无 turn 行 → `outcome` 对齐：COMPLETED→Final，CANCELLED→Cancelled，FAILED→Failed，INCOMPLETE→Incomplete，INTERRUPTED→Interrupted。不得留下无 Step 的已提交 Turn。
3. **有 parts：**
   - 按现有 Tool 在该 message 中的顺序赋予 **message-level toolOrdinal**（与当前 `tool_execution.tool_ordinal` 一致）。
   - `localCallId = uuid5(NAMESPACE, "$messageId/tool/$toolOrdinal")`。
   - `providerCallId = 旧 toolCallId`（类型是非空 `String`；空串保持空串）。
   - **Step 切分唯一判据：** 仅在有证据处切开。证据是 (a) `resultBatchOrdinal` 不同，或 (b) 已有 replay result 的 Tool 之后出现新的 Reasoning/Text，再出现 Tool。无证据不得切分，也不得跨证据合并。不得把尚未有 Result 的尾部 Tool 并进已经有 Result 的上一 batch。
   - `stepId = uuid5(NAMESPACE, "$messageId/step/$ordinal")`。
   - 在每段头部插入 `Step`。
   - `ToolApprovalState` + `tool_runtime` → typed fields，然后删除 `metadata.tool_runtime`。其它 metadata 保留。`sub_assistant_call.user_interaction`：删除 `tool_ordinal`，写入 `local_call_id`（该 Tool 的 uuid5）；`schema_version = 2`。无 `user_interaction` 的 run 只升版本。
   - Turn 累计 `TokenUsage` 不变。各 Step：`usageCompleteness = LEGACY`，`providerRequestCount = 0`，时长 null。
   - 已关闭 Step 的 outcome：若其后还有 Step → `Continue`，否则按 Turn 终态或 `Final`。
   - **Turn 终态或无 turn 行时，禁止留下 open Step / Pending Call。** trailing Pending：`interactionState` 保持 Awaiting* 的终态对应物为 `Denied("schema_upgrade")`，`resultStatus = Interrupted`（Interrupted 表示进程/升级打断，Denied 记录用户从未作答）。无 output 的 Auto/Approved → `resultStatus = Interrupted`。Step outcome：有 turn 终态则对齐该终态；无 turn 行则有完整可见文本且无 pending → `Final`，否则 `Interrupted`。
   - **Turn 仍为 RUNNING / AWAITING_USER：** 保持 Pending 为 Awaiting*、无 output 的 Auto 为 open，交给启动 `TurnRecovery`。不得在 SQL 里把等待用户的 Turn 改成终态。
4. **确定性 UUID namespace（合同值，代码必须逐字使用）：**

```text
6b1c0e2a-7f3d-5a14-9c8e-2d4f1a0b7e65
```

RFC 4122 UUID v5（SHA-1）。测试锁定 `uuid5(NAMESPACE, "<known messageId>/step/0")` 的字节。新运行时 Step/Call 使用随机 UUID，不得再用该公式。

Approval 映射（先按表，再按上面的终态收口覆盖 Pending/open）：

| 旧 | 新 interactionState | 非终态 Turn 的 resultStatus | 终态 / 无 turn 行 |
| --- | --- | --- | --- |
| Auto，有 output | NotRequired | terminalStatus 或 Completed | 同左 |
| Auto，无 output | NotRequired | null（open） | Interrupted |
| Pending + approval / 缺省 | AwaitingApproval | null | Denied("schema_upgrade") + Interrupted |
| Pending + user_input | AwaitingInput | null | Denied("schema_upgrade") + Interrupted |
| Approved，有 output | Approved | terminalStatus 或 Completed/Failed | 同左 |
| Approved，无 output | Approved | null（待执行） | Interrupted |
| Denied(reason) | Denied(reason) | Denied | Denied |
| Answered(answer) | Answered(answer) | Answered | Answered |

`tool_runtime.terminalStatus` 优先于“有 output 即 Completed”。archive / outputPolicy 进入 `ToolRuntimeState`。非法 `tool_runtime`（现行 `isInvalid`）→ 迁移失败。

转换后不变量（每条 Assistant）：§6.2 全满足；终态 Turn 无 open Step；Tool 个数与顺序不变；每个旧 toolOrdinal 有稳定 `localCallId`；Artifact 引用集合不变。

### 11.5 `turn_execution`

列结构不变。仅改 status 文本：

```text
AWAITING_APPROVAL → AWAITING_USER
CREATED           → INTERRUPTED（reason = schema_upgrade；该状态从未对应已开始执行）
```

`RUNNING` / 已改名的 `AWAITING_USER` 保持。启动 `TurnRecovery` 按 §7 收口（Pending → Interrupted result，STARTED → Unknown），**不是**现行“只改 STARTED output、Pending 原样留下”。迁移不得把等待用户的 Turn 标成 Completed。

枚举删除 `CREATED`、`AWAITING_APPROVAL`。CAS 合法边：`RUNNING ⇄ AWAITING_USER`，二者均可进入终态。`ConversationRepository.persistTurnExecution` 与 `ToolExecutionDAO` 里所有 `IN ('RUNNING', 'AWAITING_APPROVAL')` 改为 `IN ('RUNNING', 'AWAITING_USER')`。`TurnExecutionOperation.RECOVER` 的源状态不再包含 `CREATED`。

### 11.6 `tool_execution` 重建

现行：

```text
execution_id PK
turn_id FK → turn_execution CASCADE
tool_ordinal
status, reason, child_conversation_id, created_at, updated_at
INDEX(turn_id), INDEX(status), INDEX(child_conversation_id)
```

目标（实体注解、fresh schema、迁移 DDL **逐字同构**）：

```text
execution_id PK
turn_id            NOT NULL  FK turn_execution CASCADE
step_id            NOT NULL
local_call_id      NOT NULL
status, reason
child_conversation_id     NULL
child_turn_id             NULL
sub_assistant_run_id      NULL
created_at, updated_at
UNIQUE(turn_id, local_call_id)     -- Room: Index(unique=true)
INDEX(turn_id)
INDEX(child_conversation_id)
INDEX(child_turn_id)
INDEX(sub_assistant_run_id)
```

`child_turn_id` / `sub_assistant_run_id` 的索引服务恢复定点，不是装饰。不建 `INDEX(step_id)`。不建 `INDEX(status)`：DAO 没有按 status 的全局查询。

V3 `ToolExecutionDAO` 的 INSERT/UPDATE 用 `local_call_id` 替代 `tool_ordinal`；`getByTurnId` 按 `created_at, local_call_id` 排序。active turn 子查询改为 `IN ('RUNNING', 'AWAITING_USER')`。

`sub_assistant_run_id` 是执行事实；卡片仍读 metadata；同事务必须一致。

重建必须可重入。临时表名固定 `tool_execution_v3`。探测用 `PRAGMA table_info` + `sqlite_master`，不靠“上次走到哪”的内存状态。

**状态矩阵（`tool_execution` / `tool_execution_v3`）：**

| 当前 `tool_execution` | `tool_execution_v3` | 动作 |
| --- | --- | --- |
| 有 `local_call_id`（已是目标） | 不存在 | 只补 `CREATE INDEX IF NOT EXISTS`，结束 tool 段 |
| 有 `local_call_id` | 存在 | `DROP TABLE tool_execution_v3`，再补索引。残留临时表 |
| 有 `tool_ordinal`（旧表） | 不存在 | 建空临时表 → INSERT 全量映射 → 校验行数 = 旧表保留行数 → DROP 旧表 → RENAME |
| 有 `tool_ordinal` | 存在且空 | 同上一行，从 INSERT 继续 |
| 有 `tool_ordinal` | 存在且非空 | `DROP TABLE tool_execution_v3`（半截），再建空表重填。旧表仍是权威，禁止 RENAME 半截表 |
| **不存在** | 存在且完整（列 = 目标，行可映射回 transcript） | `ALTER TABLE tool_execution_v3 RENAME TO tool_execution`，再补索引。崩溃在 DROP 旧表之后、RENAME 之前 |
| 不存在 | 不存在 / 临时表残缺 | **迁移失败**。无法从空库猜回 execution 行 |

「完整」= 列含 `local_call_id`+`step_id`，且每行能对上已转换 Assistant 的 Tool；对不上则失败，不得 RENAME。

DDL 边界（必须有 instrumentation 中断重入样本）：建临时表后、INSERT 中、INSERT 完未 DROP、DROP 旧表后未 RENAME、RENAME 后未建齐索引。

全程 `PRAGMA foreign_keys=OFF`，结束时 `ON`。

映射：用 `turn_execution.assistant_message_id` 找到已转换 Assistant JSON，按 **旧 tool_ordinal** 定位 Tool，写入 `step_id` + `local_call_id`。`assistant_call` 抄 `run_id` → `sub_assistant_run_id`。`child_turn_id` 旧行保持 NULL。

找不到 owning Tool：

- STARTED，或 Turn 非终态：迁移失败。
- 终态行且 conversation/message 已不存在：删除悬挂行，测试锁定计数。
- 终态行且 message 仍在、但 ordinal 越界：迁移失败。

`child_turn_id` 不从旧数据猜测（Child 可能 reuse/clone 多 Turn）。V3 `assistant_call` 在 STARTED/child-link checkpoint 写入。

### 11.7 其它表

`conversation_model_context`、`artifact` / `artifact_reference`、FTS、Settings、MCP catalog **不改结构**。`collectArtifactReferences` 忽略 `Step`，并从 `runtimeState.archive` 读取 TOOL_OUTPUT。迁移后引用集合必须相等。`database-indexing.md` 在 V3-G 改为描述新索引，不再写 `tool_ordinal` / `index_tool_execution_status`。

### 11.8 Backup

| 方向 | 规则 |
| --- | --- |
| 导出 | 只写 `rikkahub-durable-v5` + Room v11。禁止再写 v4。 |
| 读入 v5 | 校验后 staging；数据库已是 v11，**不得再跑 transcript 转换**。 |
| 读入 v4 / v3 | staging 内走同一 Room 链升到 v11 并跑 `Migration_10_11`；**验证成功后**才把 pending 标为可发布。禁止先把未升级 DB swap 进 live 再开 Room。升级失败：删除该 staging/pending，保留原 live，不得留下半升级 live |
| decoder 位置 | 只在 DB migration；restore 不单独实现第二套 JSON 改写。 |
| 版本门 | `validateDatabase` 的上限随 `APP_DATABASE_VERSION`；modern aggregate 仍要求 v8+。 |
| 发布 | 仅 v11 且 transcript 已转换的 aggregate 可进入 `PendingBackupRestore`。swap 失败仍走现有 rollback 目录 |

`SUPPORTED_MANIFEST_VERSIONS = v3, v4, v5`。v3/v4 只作为输入；输出只有 v5。

### 11.9 迁移验证职责

| 层 | 测试 | 观察 |
| --- | --- | --- |
| JVM | `LegacyTurnTranscriptMigratorTest` | payload 映射矩阵、确定性 uuid5、失败关闭 |
| instrumentation | `Migration_10_11Test` | 事务、schema 同构、真实 SQL/JSON、幂等重入 |
| instrumentation | `BackupRestoreMigrationIntegrationTest`（v4→v5） | restore 走同一 migrator，不第二套转换 |

样本清单、分层不复制规则见测试方案 §10。合同不变量仍是 §11.1–§11.8：终态无 open Step、引用集合相等、v5 不二次转换。

---

## 12. Checkpoint 合同

```kotlin
sealed interface TurnCheckpoint {
    val turn: TurnHandle
    val step: StepHandle
    val assistantMessage: UIMessage
}
```

禁止携带完整 currentMessages / 整条 branch。

| Variant | 何时 | 事务内必须成立 |
| --- | --- | --- |
| `ModelResponseCheckpoint` | 采样成功并形成 durable output | Step.modelResult；模型输出与 Tool Calls；immediate failures；pending interactions；compaction patches；Turn RUNNING 或 AWAITING_USER。UI 不得先于 DB 看到新 Pending。 |
| `ToolExecutionStartedCheckpoint` | 不可逆副作用前 | `tool_execution STARTED` + turn/step/localCallId。Assistant transcript 可以不变。成功返回后才执行 Tool。 |
| `ToolExecutionUpdatedCheckpoint` | 仅 durable 中间事实 | Child link、新 Artifact root、unknown-sensitive 远端边界。进度/百分比只进 `TurnStreamProjection`。 |
| `ToolResultCheckpoint` | 单或批 Result | output、resultStatus、execution 终态、Tool metadata、Artifact roots；可选 StepCompletion + next Step。 |
| `FinalizeTurn` | 终态 | 单个最新 Assistant message；open Step outcome；Turn outcome；execution 收口。删除 `messages: List<UIMessage>` 与 `closeInterruptedTools: Boolean`。`TurnFinalizer` 在提交前产出完整合法 terminal message。 |
| `RecoverInterruptedTurn` | 进程重启 | 与 `FinalizeTurn` 同一载荷形状：owning Assistant + Step/Tool/Turn 终态 facts。禁止 `currentMessages()` 整树、禁止 `closeInterruptedTools` 布尔。 |

---

## 13. 性能（结构性门禁，不编造毫秒）

1. 每个 chunk 不遍历历史 nodes，不重跑历史 Input Transformer；只复制 active Assistant 与当前 Step parts。
2. Checkpoint 只带 owning Assistant + 精确 compaction patches + execution facts。
3. 首个 Step 与 START 同事务；后续 Step 与上一 Step close 同事务。
4. 副作用最低写入：STARTED → result。可合并：immediate failures 一批；Denied/Answered 一批；末个 result + Step close/open；Child link + execution update。
5. Tool schema 每个 Turn freeze 一次；Step 不重跑 `parameters()`。
6. Recovery 查询量 ∝ 非终态 turn/tool 行。
7. Compaction 只扫描本次 `ModelRequestReceipt` 可见的 inline outputs。
8. 无 Tool 单 Step Turn：核心事务仍是 START + terminal。
9. `read`/`grep` 上限迁到 `ToolOutputProtocol`；不引入全文索引。

Tool 执行默认串行。不提前引入 `PARALLEL_SAFE`。

---

## 14. Request 与 Compaction

请求前：`RequestContextPlanner`（window、origin、disclosure、estimate）→ `RequestAssembler` → `ModelRequestMessage` + receipt。

请求后：receipt → `ToolOutputCompactionPlanner.planAfterSuccessfulRequest` → `ToolOutputStore.stageCompaction` → 随 `ModelResponseCheckpoint` 提交。Compaction 不叫 Step completion。archiving / folding / preserve 三者不得混用。

`ContextBudget` 管请求水位；`ToolOutputProtocol` 管 read/grep/marker。禁止再塞进一个 `ContextTrimmingPolicy`。

会话级 history summarization 仍未实现，不得与 rolling compaction 冒充。

---

## 15. Enterprise 接缝

V3 **不写入**无消费者的 stamp / source 字段（无调用协议即死 schema）。执行主链的稳定接缝是：`TurnContextFactory` 与 `TurnToolSetFactory` 是未来唯一允许增加 Realm / Gateway 来源的入口。禁止改 Conversation、Step、Tool execution、checkpoint 协议。

后续（非 V3 交付）若接入：

- `TurnConfigurationStamp(effectiveRevision, realm, managedGeneration, gatewaySurfaceHash)` 必须同时有读取方
- Tool binding source：`LOCAL | DIRECT_MCP | ENTERPRISE_GATEWAY` 必须同时被装配与诊断使用

禁止：Gateway 伪装 `McpServerConfig`、写入 `Assistant.mcpServers`、本地下载完整 Catalog、本地签发 `toolRef`、Gateway 失败 fallback Direct MCP、Step 中途刷新 Gateway definitions。

---

## 16. 实施阶段

阶段边界 = 可门禁的语义切片。每一阶段结束必须删除该阶段替换掉的旧符号，禁止“先双路径再清”。

**V3-A 合同与基线。** 本文与测试方案定稿。不改行为。`docs/references/` 与 `AGENTS.md` 仍描述现状。测试侧对应 T0/T1（测试方案 §15）。

**V3-B 类型、写路径与 Migration_10_11 同交付。** 同一变更必须同时完成：

- `:ai` 增加 `UIMessagePart.Step`、typed Tool 字段、`metadata` 改为 `val`；删除 `ToolApprovalState`
- `StartTurn` 与 checkpoint reducer **只写 V3 JSON 键**
- `GenerationLoop` 经 `transcriptPartsForProvider` 再交给现有 adapter；golden 仍过
- `ToolCallRuntime` 读写 typed 字段；`collectArtifactReferences` 改读 `runtimeState.archive`
- `Migration_10_11` + backup v5；DAO 列与 CAS 字符串同步
- Legacy DTO 只留在 migrator

禁止“先加列、后改写路径”。生产门禁：升级后新 Turn 与旧行可被同一 V3 decoder 装载；引用集合相等。测试门禁见测试方案 T3。

**V3-C Runtime 与 command。** 去掉 `activeTurn`；`TurnCommands` / `TurnTransition`；stream 只含当前 Assistant。Gate：commit-then-publish、streaming 不落库、stale 拒绝、pending 可见性、取消竞态、结构共享。

**V3-D TurnRunner / StepRunner / TurnCommitter。** 删除 Generation*、CheckpointKind、TurnEvent。Gate：单 Step、多 Step、混合 batch、continuation、step limit、Provider 失败、取消、incomplete、lease。

**V3-E ConversationTurnService 与 Sub-assistant 统一。** 删除 Child 私有装配/loop/continuation 与 `ToolSetRunMode`。Gate：同一 TurnRunner、Child `ask_user`、取消、recovery、lineage、父 Result、Artifact、access revocation。

**V3-F Request / Provider / Compaction。** `ModelRequestMessage`；删除 `transcriptPartsForProvider`、planner 旧类与 replay 启发式。Gate：Chat Completions / Responses / Claude / Gemini / DeepSeek、receipt、rolling compaction、read/grep bounds。

**V3-G 文档与清包。** `AGENTS.md` 核心导航改为 V3 类名；`chat-generation-pipeline.md` → `turn-step-execution.md`；相关 references 只描述 V3。测试规则进入 `docs/references/testing-strategy.md`。不交付无消费者的 Enterprise stamp。两份 V3 计划同时退休。测试侧对应 T8。

---

## 17. 明确不做

- Event Sourcing；新 Gradle domain module；Workflow/Saga；内部 Event Bus
- 一 command 一 handler 碎片化
- Tool 通用并行调度
- 自动语义摘要；跨进程 Turn resume
- 异步 Sub-assistant mailbox；多层递归
- Gateway Catalog 本地镜像与 Direct MCP fallback
- `UIMessage` 全仓库改名
- `step_execution` 表；Provider attempt 表
- 运行时双读旧 payload；V3 `Tool` 上的旧 JSON 键别名
- 把 Sub-assistant 再命名成 SubAgent 第二词根
- 用 `decodeListLenient` 吞掉未知 Step/Tool 字段

---

## 18. Exit

全部成立才算完成：

1. `Conversation → Turn → Step → Tool Call / Interaction / Execution / Result` 在类型与持久化中可定位，一词一义。
2. 每个已提交 Assistant Turn 有显式 `UIMessagePart.Step`。
3. 不存在 `STEP_COMPLETED` 表示“模型刚结束”。
4. 用户交互继续同一 Turn、同一 Step。
5. 用户会话与 Child 共用 TurnRunner / StepRunner / TurnCommitter。
6. aggregate 无 streaming messages；chunk 不携带完整 branch。
7. Tool 生命周期不依赖 `tool_runtime`；locator 不依赖 ordinal。
8. ConversationTransition 与 TurnTransition 分开，仍一个写入口。
9. Provider 不直接消费 `UIMessage`；compaction 使用 `ModelRequestReceipt`。
10. v10→v11 幂等、可失败；v4→v5 restore 保全；v5 不重复转换。
11. 进程重启精确关闭 open Step 与 STARTED Tool。
12. 未来 Realm/Gateway 只允许进入 `TurnContextFactory` / `TurnToolSetFactory`，V3 不写入无消费者字段。
13. 旧类、旧文件、facade、双路径物理删除。
14. Gradle 门禁与 instrumentation 以测试方案 Exit 为准；生产 Exit 不重复罗列测试文件。
15. `AGENTS.md` 与 `docs/references/` 只描述 V3；本文与测试方案同时退休。

最终主链：

```text
ConversationTurnService
  → TurnContextFactory
  → StartTurn
  → TurnRunner
      → StepRunner
          → RequestAssembler
          → Provider
          → ToolBatchRunner
          → TurnCommitter
  → TurnFinalizer
```
