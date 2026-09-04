# V3：测试架构

系列：

| 文档 | 职责 |
| --- | --- |
| [`v3-turn-step-execution-refactor-plan.md`](v3-turn-step-execution-refactor-plan.md) | 生产语义、owner、类型、Room/Backup 迁移、阶段。**术语与阶段以它为准。** |
| 本文 | 测试分层、权威测试、现有用例处置、CI、迁移样本分层 |

完成后稳定规则进入 `docs/references/testing-strategy.md`，与执行计划同时退休。不在本文重写生产状态机。

---

## 0. 结论

V3 不能只重写生产架构，再把现有测试机械改名到新类上。当前测试已经积累了四类明显债务：

1. **核心语义被重复测试。** Turn、工具审批、暂停、恢复、Sub-assistant、Provider replay 等同一行为，分别在 reducer、协调器、UI helper、集成测试中重复断言。
2. **大量测试锁定实现，而不是锁定契约。** 典型表现是扫描源码字符串、要求某一行代码存在、要求某个私有字段名不存在、要求文件必须放在某个具体类中。
3. **大测试类成为新的“测试泥球”。** `GenerationLoopFlowTest`、`ChatCompletionsAPIMessageTest`、`ResponseAPIMessageTest`、`McpRuntimeCoordinatorTest`、`ArtifactStoreLifecycleTest` 等同时验证多个 owner，修改任一边界都会造成大面积无关改动。
4. **测试分层失真。** 有些 JVM 测试只检查 SQL 字符串，而已有 instrumentation 测试真正执行了 SQL；有些普通单测用 `System.nanoTime()` 打印性能数字，却没有可比较、可重复的 benchmark 环境；同时各模块还残留真正无意义的模板测试。

因此，本方案的核心不是“提高覆盖率”，而是：

> **让每个稳定语义只有一个最接近 owner 的权威测试，再用少量跨边界集成测试验证组合风险。**

V3 测试改造完成后，测试体系应直接对应正式语义链：

```text
Conversation
  └─ Turn
      └─ Step
          └─ Tool Call
              ├─ Tool Interaction
              ├─ Tool Execution
              └─ Tool Result
```

并形成以下测试主链：

```text
纯 Transition / Planner
  → TurnRunner / StepRunner / ToolBatchRunner
  → TurnCommitter / Repository transaction
  → Runtime / Presentation
  → Provider wire
  → Android platform integration
```

本轮不设置“单测覆盖率必须达到多少”的指标，也不追求测试数量。V3 Exit 看的是：关键语义是否有唯一 owner、失败与竞态是否被覆盖、过时机制是否已经物理删除，以及整套测试是否确定、快速、可维护。

---

# 1. 目标与非目标

## 1.1 目标

本方案覆盖仓库中全部测试源集：

```text
app/src/test
app/src/androidTest
ai/src/test
ai/src/androidTest
common/src/test
common/src/androidTest
document/src/test
document/src/androidTest
highlight/src/test
highlight/src/androidTest
material3/src/test
material3/src/androidTest
search/src/test
search/src/androidTest
speech/src/test
speech/src/androidTest
workspace/src/test
workspace/src/androidTest
```

以及未来 V3 新增的：

- Turn / Step / Tool typed protocol 测试；
- Room v10 → v11 migration 与 backup v4 → v5 restore（样本见 §10，合同见执行方案 §11）；
- Provider `ModelRequestMessage` wire contract（V3-F）；
- 用户会话与 Child 共用 TurnRunner 的集成测试。

V3 **不**新增无消费者的 Enterprise stamp 测试。

具体目标：

- 对现有每个测试文件和测试用例给出处置结论；
- 删除真实无意义、重复、过时、只锁实现细节的测试；
- 把大而全测试按稳定 owner 重组；
- 将 JVM、Robolectric、Room instrumentation、Compose instrumentation 和 benchmark 重新分层；
- 建立统一而克制的 V3 TestKit；
- 让测试名称、package、fixture 与生产 V3 术语一致；
- 控制测试运行时间、写放大和长期维护成本；
- V3 完成后不保留旧类测试、兼容 facade 测试或双路径测试。

## 1.2 非目标

本方案不做：

- 不为了数字好看追求 100% coverage；
- 不引入大型测试框架、通用场景 DSL 或新的测试 Gradle module；
- 不将每个 production class 强制对应一个 test class；
- 不为每个私有方法建立单测；
- 不使用真实 Provider 网络作为合并门禁；
- 不使用永久 retry 掩盖 flaky test；
- 不删除旧 migration test，仅因为版本较老；
- 不把所有短测试一律删除；
- 不把所有大测试一律按文件大小拆碎；
- 不用 snapshot/golden 覆盖所有 JSON，从而把无关字段顺序也锁死；
- 不让测试成为第二份架构文档。

---

# 2. 当前测试盘点与主要问题

## 2.1 当前测试结构

### `:app`

当前 `app/src/test` 已覆盖：

- 架构约束与性能“证据”；
- Conversation、Runtime、Transition、TurnEngine、GenerationLoop；
- Context window、Disclosure、Tool Output rolling compaction；
- Tool gate、审批、ask_user、执行和结果；
- MCP Catalog、Runtime、OAuth、授权；
- Sub-Assistant policy、lineage、metadata、result、run gate；
- Artifact、图片、附件和文件生命周期；
- Settings、Managed overlay、Room mapper；
- Backup、FTS、Repository；
- Application Service 与 UI policy；
- 工具、Transformer、TTS、Workspace 和通用工具。

`app/src/androidTest` 已覆盖：

- Room DAO；
- Migration 1→2、3→4、4→5、5→6、6→7、7→8、8→9、9→10；
- Artifact 和图片真实 Android IO；
- Conversation Repository 树；
- Backup restore migration；
- Turn cancellation 与 approval continuation；
- Compose、生命周期、Intent、剪贴板、字体、haptic 等平台行为。

### `:ai`

当前 `ai/src/test` 已覆盖：

- Model Registry、TokenUsage、Tool arguments；
- Chat Completions、Responses、Claude、Google 等 Provider wire；
- reasoning、usage、prompt cache、endpoint profile；
- UIMessage 流式合并、Tool 操作和 metadata；
- JSON、错误解析、文件编码等基础能力。

### 其他模块

- `highlight` 有真实的高亮引擎和语言 fixture 测试，同时残留模板测试；
- `workspace` 的 JVM `ExampleUnitTest` 实际包含文件系统、路径逃逸、rootfs 和 patcher 行为，不能按名称删除；
- `speech` 有真实 `SystemTtsSequentialPlaybackInstrumentedTest`，同时残留模板 instrumentation；
- `common`、`document`、`material3` 等模块主要残留 Android Studio 模板测试；
- `app:baselineprofile` 当前没有独立测试源集。

## 2.2 当前问题分类

### A. 事故或批次命名进入永久测试

例如：

```text
UpstreamBatch13BoundaryTest
ApprovalContinuationMissingToolIntegrationTest
```

问题不是测试内容一定无价值，而是名称围绕一次历史事故或同步批次，无法表达长期契约。事故修复完成后应将可复用语义迁入正式 owner 测试，删除事故名测试。

### B. 源码字符串测试过度

当前部分 architecture test 会：

- 搜索某个私有字段名；
- 断言某个 exact source snippet；
- 断言某个 UI 调用必须位于某文件；
- 禁止全部 `TODO` / `FIXME`；
- 检查具体字符串拼接；
- 检查已移除函数名是否出现在任意源码正文。

这类测试容易误伤重命名和合理重构，却不一定能捕获实际语义错误。

V3 只保留三类静态约束：

1. package/import 依赖方向；
2. 退休生产符号不得重新声明或导入；
3. 关键公共协议类型的结构性不变量。

### C. 同一行为跨层重复

例如 Tool interaction 目前分散在：

```text
ToolApprovalStateTest
ToolApprovalReducerTest
ToolCallRuntimeGateTest
AskUserToolTest
MasterTurnCoordinatorTest
GenerationLoopFlowTest
ApprovalContinuationMissingToolIntegrationTest
ToolCallPhaseTest
SubAssistantAskUserBridgeTest
```

V3 后应由：

```text
ToolInteractionProtocolTest
ToolCallRuntimeTest
ToolBatchRunnerTest
TurnInteractionContinuationIntegrationTest
```

分别承担 typed state、单 Tool、Tool batch 和真正跨层 continuation；UI 只测试展示映射，不再重复执行语义。

### D. 大测试类跨越多个 owner

重点对象：

```text
GenerationLoopFlowTest
ChatCompletionsAPIMessageTest
ResponseAPIMessageTest
GoogleProviderMessageTest
McpRuntimeCoordinatorTest
ArtifactStoreLifecycleTest
ConversationRuntimeTest
ConversationTransitionTest
```

拆分原则不是“按方法数拆”，而是按变化原因：

- state transition；
- request assembly；
- execution；
- persistence；
- presentation；
- adapter-specific wire。

### E. 普通单测冒充 benchmark

普通 JVM 测试中的：

```text
System.nanoTime()
打印 p50 / p95 / p99
人工构造 legacy 与 current 实现比较
```

不具备稳定 runner、warm-up、measurement iteration、噪声控制和历史基线，不能作为性能门禁。

V3 将性能验证分为：

- 普通测试：验证复杂度和写入数量；
- controlled benchmark：验证时间和内存；
- instrumentation：验证真实 Room/Android 行为。

### F. Boilerplate 与空辅助文件

明确无价值的例子：

- `2 + 2 == 4`；
- 只验证 application package name；
- Android Studio 默认 `ExampleUnitTest` / `ExampleInstrumentedTest`；
- 没有声明任何类型、只保留注释的 `MemoryDAOTestRef.kt`。

这些文件直接删除，不迁移。

---

# 3. 单个测试的审计与处置规则

每个现有测试用例必须在 V3 实施时标记为以下一种状态：

```text
KEEP
REWRITE
MERGE_INTO
MOVE
DELETE_REDUNDANT
DELETE_RETIRED
DELETE_IMPLEMENTATION_DETAIL
DELETE_BOILERPLATE
```

## 3.1 保留标准

测试至少保护下列一项：

- durable 数据完整性；
- Provider wire 正确性；
- 安全、授权、路径边界；
- 用户可见关键行为；
- 并发、取消、恢复、事务或资源所有权；
- 曾经真实发生且容易回归的输入边界；
- 明确的跨版本兼容性。

同时必须满足：

- 观察的是稳定结果，而非私有实现；
- 失败时能清楚说明哪条契约破坏；
- 与其他测试不存在完全相同的保护范围；
- 能稳定、确定地运行。

短测试并不自动低价值。例如：

- 模型名精确匹配不能误命中子串；
- `.agc` 文件允许但未知二进制拒绝；
- unknown persisted enum 必须 fail-closed；
- stable ID reorder 必须基于最新列表；

即使只有两个断言，也可能是有效回归测试。

## 3.2 删除标准

只有满足以下至少一项才删除：

1. 对应生产能力已在 V3 物理退休；
2. 相同语义已被更靠近 owner、失败诊断更好的测试完整覆盖；
3. 新类型系统或数据库约束已经使该错误无法构造，旧运行时断言不再提供价值；
4. 测试只检查私有函数、字段名、文件位置或 exact source snippet；
5. 测试没有可导致业务失败的断言；
6. 测试只是 Android Studio 模板；
7. 测试重复验证框架自身，例如 `2 + 2`；
8. 测试依赖旧 V2 fixture 或旧兼容路径，而正式 V3 路径已经替代它。

不得因为以下理由单独删除：

- 文件很小；
- 文件很大；
- 测试很旧；
- migration 版本很旧；
- 测试运行较慢但验证真实 Android/Room 契约；
- 当前代码“看起来不会再改”。

## 3.3 删除前置条件

每个删除项必须记录：

```text
旧测试路径
原本保护的契约
处置原因
V3 权威测试或类型约束
删除生效阶段
```

`DELETE_REDUNDANT` 必须指向一个已经通过的新测试；不能先删旧测试，再承诺以后补。

---

# 4. V3 目标测试分层

## 4.1 L1：纯协议与纯函数测试

运行位置：JVM。

范围：

- `ConversationTransition`；
- `TurnTransition`；
- `UIMessagePart.Step` transcript invariants；
- `ToolInteractionState` / `ToolResultStatus`；
- Request Context 选择；
- Context Budget；
- Tool Output compaction plan；
- Sub-assistant policy、lineage、result projection；
- Provider-independent replay projection；
- 字符串、路径和解析算法。

要求：

- 不需要 Android Context；
- 不 mock Room；
- 不启动协程 Job，除非被测对象本身是 coroutine protocol；
- 使用 table-driven cases 覆盖状态转换；
- 无真实时钟和随机 UUID。

## 4.2 L2：组件与应用服务测试

运行位置：JVM，必要时 Robolectric。

范围：

- `TurnRunner`；
- `StepRunner`；
- `ToolBatchRunner`；
- `TurnCommitter`；
- `ConversationTurnService`；
- `ConversationRuntime`；
- `TurnFinalizer`；
- `SubAssistantRunCoordinator`；
- SettingsStore commit-then-publish；
- Artifact lifecycle owner；
- MCP Runtime owner。

使用 deterministic fakes，不把所有依赖都设成 `relaxed = true`。

## 4.3 L3：持久化与协议集成测试

运行位置：Robolectric 或 Android instrumentation，按真实依赖选择。

范围：

- Room transaction；
- DAO SQL；
- FTS；
- Conversation delta；
- Artifact references；
- Tool execution facts；
- Backup staging；
- Provider request JSON 与 parser；
- V3 transcript serialize/deserialize。

原则：

- 只在真实数据库/平台行为有价值时进入此层；
- 不用 mock SQL 后再检查 SQL 字符串；
- 同一 SQL 契约不同时保留“字符串包含”与“真实执行”两套测试。

## 4.4 L4：Android 平台与 Compose instrumentation

范围：

- Room Migration；
- ContentResolver / Uri；
- Android Bitmap/Exif；
- Process lifecycle；
- Foreground service；
- Haptic/Vibrator adapter；
- System TTS；
- Compose semantics、焦点、IME、自适应布局；
- Intent、剪贴板和文件分享。

原则：

- 只验证 Android 环境无法在 JVM 可靠证明的事实；
- UI policy 先在 JVM 测，instrumentation 只验证 adapter 与真实 Compose 绑定；
- 不重复测试纯函数决策。

## 4.5 L5：性能与长期稳定性

范围：

- stream chunk throughput；
- active Assistant allocation；
- Room checkpoint write count；
- Tool Output read/grep；
- 大 Conversation request assembly；
- migration 大数据量；
- Compose recomposition；
- 长时间 TTS queue。

普通单测只验证结构性复杂度；时间和内存放在 controlled benchmark/nightly。

---

# 5. 测试 owner 与唯一权威测试

| 生产语义 | 权威测试 |
|---|---|
| Conversation header/tree/variant | `ConversationTransitionTest` |
| Turn/Step/Tool transcript transition | `TurnTransitionTest` |
| Turn/Step protocol invariants | `TurnStepProtocolContractTest` |
| commit-then-publish 与锁 | `ConversationCommandCoordinatorTest` |
| active Turn session | `ConversationRuntimeTest` |
| streaming overlay | `TurnStreamProjectionTest` |
| Turn 多 Step 循环 | `TurnRunnerTest` |
| 单 Step request + tool batch | `StepRunnerTest` |
| Tool batch gate 与顺序 | `ToolBatchRunnerTest` |
| durable checkpoint | `TurnCommitterTest` |
| stop/failure/cancel | `TurnFinalizerTest` |
| restart recovery | `TurnRecoveryTest` |
| Turn entry | `ConversationTurnServiceTest` |
| 单 Tool 解析与执行包装 | `ToolCallRuntimeTest` |
| interaction typed protocol | `ToolInteractionProtocolTest` |
| Tool Result envelope | `ToolResultContractTest` |
| request history/disclosure | `RequestContextPlannerTest` |
| Provider-independent request assembly | `RequestAssemblerTest` |
| budget | `ContextBudgetTest` |
| rolling compaction selection | `ToolOutputCompactionPlannerTest` |
| archive/read/grep | `ToolOutputStoreTest` |
| Provider wire | 各 adapter contract test |
| Sub-assistant policy | `SubAssistantPolicyTest` |
| Sub-assistant lineage | `SubAssistantLineageTest` |
| Sub-assistant output | `SubAssistantResultProjectionTest` |
| Child run 编排 | `SubAssistantRunCoordinatorTest` |
| Room V3 migration | `Migration_10_11Test` |
| backup v4→v5 | `BackupV4ToV5RestoreTest` |
| checkpoint 写放大 / node delta | `TurnPersistenceDeltaTest` |
| Tool set 冻结 | `TurnToolSetFactoryTest` |
| request/step/turn usage | `RequestUsageReducerTest` / `StepUsageTest` / `TurnUsageTest` |
| Tool lease 顺序 | `ToolResourceCommitTest` |
| Provider 失败分类 | `ProviderFailureClassificationTest` |

同一契约最多允许：

- 一个最近 owner 的权威测试；
- 一个必要的跨边界集成测试；
- 一个必要的平台测试。

超过这三层必须明确说明每层观察的不同事实。

---

# 6. 目标测试目录

不新增 Gradle module。测试 package 与生产 package 对齐。

```text
app/src/test/java/net/weero/measix/pilot/
├─ architecture/
│  ├─ ArchitectureDependencyTest.kt
│  ├─ RetiredSurfaceContractTest.kt
│  └─ TurnStepProtocolContractTest.kt
│
├─ service/turn/
│  ├─ ConversationTurnServiceTest.kt
│  ├─ TurnRunnerTest.kt
│  ├─ StepRunnerTest.kt
│  ├─ ToolBatchRunnerTest.kt
│  ├─ TurnCommitterTest.kt
│  ├─ TurnFinalizerTest.kt
│  ├─ TurnRecoveryTest.kt
│  ├─ TurnPersistenceDeltaTest.kt
│  ├─ RequestUsageReducerTest.kt
│  ├─ StepUsageTest.kt
│  └─ TurnUsageTest.kt
│
├─ service/runtime/
│  ├─ ConversationTransitionTest.kt
│  ├─ TurnTransitionTest.kt
│  ├─ ConversationCommandCoordinatorTest.kt
│  ├─ ConversationRuntimeTest.kt
│  └─ TurnStreamProjectionTest.kt
│
├─ service/subassistant/
│  ├─ SubAssistantPolicyTest.kt
│  ├─ SubAssistantLineageTest.kt
│  ├─ SubAssistantResultProjectionTest.kt
│  ├─ SubAssistantRunCoordinatorTest.kt
│  └─ SubAssistantRunGateTest.kt
│
├─ data/ai/request/
│  ├─ RequestAssemblerTest.kt
│  ├─ RequestContextPlannerTest.kt
│  └─ ContextBudgetTest.kt
│
├─ data/ai/tools/
│  ├─ ToolInteractionProtocolTest.kt
│  ├─ ToolCallRuntimeTest.kt
│  ├─ ToolResultContractTest.kt
│  ├─ ToolOutputCompactionPlannerTest.kt
│  ├─ ToolOutputStoreTest.kt
│  ├─ TurnToolSetFactoryTest.kt
│  └─ ToolResourceCommitTest.kt
│
└─ testkit/
   ├─ TurnFixtures.kt
   ├─ ScriptedProvider.kt
   ├─ RecordingTool.kt
   ├─ RecordingCommitPort.kt
   ├─ ArtifactLeaseProbe.kt
   └─ TestClockAndIds.kt
```

`testkit` 最多保持这类少量基础对象；不建立通用 DSL、反射注入容器或第二套 runtime。

---

# 7. TestKit 设计

## 7.1 固定时钟与 ID

新增：

```kotlin
class TestClock(...)
class TestIdGenerator(...)
```

V3 production seam 接受时钟/ID provider 的位置，测试不得依赖：

```text
Clock.System.now()
System.currentTimeMillis()
Uuid.random()
```

断言应使用稳定的：

```text
turn-1
step-1
tool-1
execution-1
```

## 7.2 `ScriptedProvider`

替代核心测试中大量 MockK stream stub：

```kotlin
ScriptedProvider(
    attempts = listOf(
        ProviderAttempt.RetryableFailure(...),
        ProviderAttempt.Stream(
            chunks = ...,
            finish = ...
        )
    )
)
```

能力：

- non-streaming；
- streaming；
- delayed first output；
- retry before output；
- partial output then failure；
- tool call batch；
- missing terminal；
- usage presence/absence；
- cancellation barrier。

它不实现真实 Provider wire；wire 仍由 `:ai` adapter tests 验证。

## 7.3 `RecordingTool`

支持：

```text
interaction requirement
argument validation result
execution result
execution barrier
resource lease
execution invocation count
```

用来验证：

- Approval 前不得执行；
- batch 未完全解决不得执行；
- denied/answered 不创建 execution；
- STARTED commit 必须先于副作用；
- duplicate continuation 不重复执行。

## 7.4 `RecordingCommitPort`

记录 typed checkpoint：

```text
ModelResponseCheckpoint
ToolExecutionStartedCheckpoint
ToolResultCheckpoint
FinalizeTurn
```

测试 `TurnRunner` / `StepRunner` 时，不需要构造完整 Room repository。

`TurnCommitterTest` 再用真实 `ConversationCommandCoordinator` 或 transaction fake 验证落盘协议。

## 7.5 `ArtifactLeaseProbe`

显式记录：

```text
created
checkpointed
published
discarded
retained
```

禁止只靠 `verify(exactly=1)` 猜资源所有权顺序。

## 7.6 Fixture 克制规则

Fixture 只提供合法默认对象和显式变体：

```kotlin
conversationFixture(...)
assistantTurnFixture(...)
stepFixture(...)
toolCallFixture(...)
turnContextFixture(...)
```

禁止：

- builder 中自动提交命令；
- fixture 隐式创建 Tool output；
- fixture 根据测试名称决定行为；
- 一套 DSL 模拟整个 App。

测试正文必须仍能直接读出 Given / When / Then。

---

# 8. 现有测试的具体处置

## 8.1 Architecture 测试

### `SingleWriterContractTest.kt`

**处置：拆解后删除原文件。**

迁移：

- package/import 依赖规则 → `ArchitectureDependencyTest`；
- 已退休 V2 symbol → `RetiredSurfaceContractTest`；
- Turn/Step typed invariant → `TurnStepProtocolContractTest`。

删除：

- `TODO` / `FIXME` 全局禁令；
- exact source statement；
- UI 具体调用位置；
- 私有字段名；
- 与 Kotlin formatter 或文件布局绑定的断言；
- 与资源编译器、类型系统重复的检查。

### `SnapshotOnlyContractTest.kt`

**处置：合并后删除。**

保留：

- UI 不得导入 aggregate、Repository、Runtime Registry；
- presentation 不暴露 model context。

删除：

- 某个 getter 文本不得存在；
- 某个私有 StateFlow 字段名不得存在；
- `Conversation.kt` exact source substring。

### `UpstreamBatch13BoundaryTest.kt`

**处置：删除。**

迁移真正稳定的边界：

- UI 不持有底层 owner；
- workspace query/application 分离；
- Provider setting conversion 不泄漏敏感字段。

删除：

- Batch13 命名；
- exact 文件位置；
- exact UI 代码；
- 翻译字符串存在性；
- 与一次上游同步记录绑定的断言。

### `ArchitecturePerformanceEvidenceTest.kt`

**处置：删除原测试。**

迁移：

- changed row count；
- structural sharing；
- FTS invalidation count；

到 `TurnPersistenceDeltaTest` / `TurnStreamProjectionTest`。

删除：

- `System.nanoTime()`；
- println percentile；
- synthetic legacy comparison。

### `CheckpointWriteAmplificationTest.kt`

**处置：重写为 `TurnPersistenceDeltaTest.kt`。**

保留：

- 单节点 checkpoint 只 upsert changed node；
- 历史 compaction 只触碰命中节点；
- terminal commit 不回写完整树；
- Artifact reference delta 与 node delta 对齐。

其中 START rollback 与 `ConversationStartAtomicityTest` 重复的部分合并到一个 transaction suite。

### `StreamingTransformScopeTest.kt`

**处置：重写为 `TurnStreamProjectionTest.kt`。**

保留：

- chunk 只更新 active Assistant；
- 历史 nodes 不遍历；
- 历史实例引用保持；
- committed projection 与 stream projection 分离。

普通行为测试无需固定 5000 chunk；使用小而确定的 chunk 序列。5000+ chunk throughput 进入 benchmark。

### `FtsDeltaScopeTest.kt`

**处置：移动并重命名为 `MessageFtsDeltaIntegrationTest.kt`。**

它验证的是真实 Room/FTS delta，不是 architecture source rule，应放入：

```text
app/src/test/.../data/db/fts/
```

---

## 8.2 Turn / Step / Runtime 测试

### `GenerationLoopFlowTest.kt`

**处置：完全拆解，原文件删除。**

迁移目标：

| 原测试内容 | V3 目标 |
|---|---|
| provider stream 与 terminal | `StepRunnerTest` |
| 多 Step tool loop | `TurnRunnerTest` |
| pending batch gate | `ToolBatchRunnerTest` |
| approval/user input | `ToolInteractionProtocolTest` + integration |
| stream projection | `TurnStreamProjectionTest` |
| checkpoint sequence | `TurnCommitterTest` |
| Tool Output rolling compaction | `ToolOutputCompactionPlannerTest` |
| usage accumulation | `RequestUsageReducerTest` / `StepUsageTest` / `TurnUsageTest` |
| resource lease | `ToolResourceCommitTest` |
| memory owner policy | `TurnToolSetFactoryTest` 或 Memory owner policy test |
| helper envelope | `ToolResultContractTest` |

禁止把原 90 KB 文件按原顺序复制成 `TurnRunnerTest`。

### `GenerationLoopLogicTest.kt`

**处置：按 owner 合并。**

- step limit → `TurnRunnerTest`；
- finish reason mapping → `StepRunnerTest`；
- provider failure classification → `ProviderFailureClassificationTest`；
- pure Tool helper → Tool owner test。

### `GenerationStreamingProjectionTest.kt`

**处置：合并到 `TurnStreamProjectionTest`。**

### `GenerationTimingTest.kt`

**处置：合并到 `ProviderAttemptTimingTest` 或 `StepRunnerTest`。**

保留：

- empty protocol event 不构成 first model output；
- text/reasoning/tool payload 构成 first output。

删除独立文件，避免一个两分支 helper 一个测试类。

### `ProviderNonStreamingTerminalTest.kt`

**处置：合并到 `StepRunnerTest` 与 adapter terminal parser tests。**

### `TurnEngineTest.kt`

**处置：重写为 `TurnCommitterTest.kt`。**

只测试：

- typed checkpoint → command；
- commit failure 不发布；
- terminal idempotency；
- stale handle；
- latest Assistant draft；
- pending durable boundary。

不再测试 Generation Flow。

### `MasterTurnCoordinatorTest.kt`

**处置：重写为 `ConversationTurnServiceTest.kt`。**

只测试：

- send/edit-resend/regenerate command ordering；
- START 与 continuation 入口；
- Settings snapshot 只捕获一次；
- worker ownership；
- failure before START 不伪造 Turn；
- side effect scheduling 与 durable success 解耦。

### `ConversationTransitionTest.kt`

**处置：拆成两个 owner。**

`ConversationTransitionTest` 只保留：

- append/edit/delete/select/truncate；
- header；
- structural sharing；
- model-context entry 随树删除。

Turn、Step、Tool interaction、checkpoint、terminal 全部迁到 `TurnTransitionTest`。

### `ConversationModelContextTransitionTest.kt`

**处置：收缩。**

保留：

- owner/anchor variant 的因果存在性；
- fork/clone remap；
- tree mutation prune。

请求前投影与 Disclosure placement 迁到 `RequestContextPlannerTest`。

### `ConversationRuntimeTest.kt`

**处置：拆分但不碎片化。**

保留两个文件：

```text
ConversationRuntimeTest
TurnStreamProjectionTest
```

前者负责：

- ActiveTurnSession owner；
- worker install/release；
- stop/cancel；
- continuation resume；
- stale identity。

后者负责：

- committed + stream + presentation 合成；
- structural sharing；
- UI live phases。

### `ConversationRuntimePersistenceTest.kt`

**处置：合并到 `TurnCommitterTest` 或 Repository integration。**

Runtime 本身不应拥有 persistence；V3 后若仍需要此测试，说明边界尚未收干净。

### `ConversationCommandCoordinatorTest.kt`

**处置：保留并收缩。**

只测试：

- per-conversation serialization；
- recovery gate；
- transaction commit 后 publish；
- non-resident load；
- conflict/failure classification；
- create/delete ownership。

具体 command reducer 语义不重复。

### `TurnPipelineFactoryTest.kt`

**处置：合并到 `RequestAssemblerTest`。**

固定 transformer 顺序是 RequestAssembler contract，不再保留独立 pipeline factory 测试。

### `TurnRequestContextFactoryTest.kt`

**处置：重写为 `TurnContextFactoryTest.kt`。**

覆盖：

- snapshot capture；
- Assistant/Model/Provider；
- tool definitions/bindings 同源；
- credential lease 与 wire shape 分离；
- pre-START revalidation；
- 用户会话与 Child 复用同一 `TurnContextFactory`。

V3 不测无消费者的 configuration stamp。

### `TurnUsageResetTest.kt`

**处置：合并到 `StepUsageTest` / `TurnUsageTest`。**

---

## 8.3 Context、Usage 与 rolling compaction

### `ConversationContextPlannerTest.kt`

**处置：拆成三个稳定 owner。**

```text
RequestContextPlannerTest
ContextBudgetTest
ToolOutputCompactionPlannerTest
```

映射：

- USER turn window、replay-safe history、Disclosure locator → RequestContextPlanner；
- token estimator、水位、request budget → ContextBudget；
- protected batches、net reclaim、archive/fold eligibility → ToolOutputCompactionPlanner。

### `ToolOutputCompactionAccountingTest.kt`

**处置：合并到 `ToolOutputCompactionPlannerTest`。**

### `TokenUsageAccountingTest.kt`

**处置：按语义拆分为：**

```text
RequestUsageReducerTest
StepUsageTest
TurnUsageTest
```

Provider response JSON 的 usage 字段解析继续留在 adapter-specific usage test；不能在应用层重复模拟 parser。

### `MessageNodeTokenStatsTest.kt`

**处置：删除。**

它只检查 SQL 字符串是否包含 JSON path；已有 `MessageNodeTokenStatsIntegrationTest` 真实执行 legacy/complete/partial/missing usage 语义。V3 更新 instrumentation 测试到新 payload 后，源码字符串测试不再保留。

### `MessageNodeTokenStatsIntegrationTest.kt`

**处置：保留并更新 V3 payload。**

增加：

- 无 `transcript_schema` 的旧 payload（当前生产）；
- V3 Turn aggregate usage；
- Step usage 不被重复累计；
- Child 是否计入统计的明确口径。

---

## 8.4 Tool 测试

### 合并后的目标

```text
ToolInteractionProtocolTest
ToolCallRuntimeTest
ToolBatchRunnerTest
ToolResultContractTest
TurnInteractionContinuationIntegrationTest
```

### 原文件映射

| 原文件 | 处置 |
|---|---|
| `ToolCallRuntimeGateTest` | 并入 `ToolCallRuntimeTest` 与 `ToolBatchRunnerTest` |
| `ToolCallRuntimeExecutionTest` | 并入 `ToolCallRuntimeTest` |
| `ToolApprovalReducerTest` | V3 typed transition 迁入 `TurnTransitionTest` |
| `ToolApprovalStateTest` | ToolApprovalState 退休后删除 |
| `AskUserToolTest` | Tool schema/result 保留；interaction lifecycle 移出 |
| `ApprovalContinuationMissingToolIntegrationTest` | 重写为 `TurnInteractionContinuationIntegrationTest` |
| `ToolCallPhaseTest` | 改为 `ToolLivePhaseTest`，只测展示映射 |
| `ToolResultProtocolTest` | 保留并重命名 `ToolResultContractTest` |
| `ToolOutputProtocolTest` | 保留，聚焦 marker/ref/read/grep wire |
| `ToolDefinitionCacheContractTest` | 并入 `TurnContextFactoryTest` / `TurnToolSetFactoryTest` |
| `FrozenToolSetTest` | 并入 `TurnToolSetFactoryTest` |
| `GenerationToolSetFactoryMcpTest` | 重写为 `TurnToolSetFactoryMcpTest` |
| `ShouldUseExternalWebSearchTest` | 合并到 `TurnToolSetFactoryTest` 的 capability matrix |
| `ShouldInjectAttachmentInspectionTest` | 同上 |
| `MemoryToolsTest` | 保留真实 namespace/revalidation，合并到 Memory Tool contract |
| `WorkspaceToolArgumentsTest` | 保留，路径与参数安全属于稳定 wire |
| `WorkspaceToolsTest` | 删除；stable attachment ref 已由 `AttachmentRefsTest` 更完整覆盖 |
| `TtsToolPlaybackPolicyTest` | 合并到 `TtsPlaybackQueuePolicyTest` |
| `JavascriptToolTest` | 审查；若只检查一个常量/字段则合并，若保护 sandbox/result shape 则保留 |

### interaction matrix

`ToolInteractionProtocolTest` 必须覆盖：

```text
NotRequired
AwaitingApproval → Approved
AwaitingApproval → Denied
AwaitingInput → Answered
wrong decision kind
duplicate decision
stale localCallId
wrong stepId
terminal Tool cannot resolve again
```

`ToolBatchRunnerTest` 必须覆盖：

```text
all automatic
all approval
mixed invalid + approval + automatic
multiple pending
one resolved but another pending
denied + automatic
answered + automatic
execution order
STARTED before side effect
resource result commit
partial execution failure
```

---

## 8.5 Provider 与 `:ai` 测试

### 当前问题

`ChatCompletionsAPIMessageTest`、`ResponseAPIMessageTest` 和 `GoogleProviderMessageTest` 都包含：

- plain text；
- sequential Tool；
- same-step Tool batch；
- reasoning；
- terminal replay；
- endpoint profile；
- media；
- parser；
- request body options。

大量基础场景在不同 adapter 中重复，同时一个文件中又混入多个变化原因。

### V3 目标

新增公共 fixture，不建立继承层级很深的 abstract test：

```text
ai/src/test/.../provider/ProviderRequestContractFixtures.kt
```

定义 canonical cases：

```text
plain user/assistant
one Step final
two Step tool continuation
same-Step Tool batch
Tool call/result pairing
partial terminal Step
reasoning with Provider metadata
tool media supported/unsupported
adjacent same-role normalization
```

各 adapter 文件：

```text
ChatCompletionsRequestContractTest.kt
ResponsesRequestContractTest.kt
ClaudeRequestContractTest.kt
GeminiRequestContractTest.kt
DeepSeekReplayContractTest.kt
```

adapter-specific 文件继续独立：

```text
ChatCompletionsRequestOptionsTest
ResponsesEndpointProfileTest
ProviderUsageParserTest
ProviderResponseParserTest
PromptCachePolicyTest
ToolSchemaSerializationTest
```

### 具体处置

- `ChatCompletionsAPIMessageTest`：拆分后删除原文件；
- `ResponseAPIMessageTest`：拆分后删除原文件；
- `GoogleProviderMessageTest`：拆分为 Gemini request + parser；
- `ClaudeProviderMessageTest`：收缩为 Claude-specific contract；
- `DisclosureContextWireTest`：V3 后改为 `ModelRequestDisclosureWireTest`，只验证各协议投影；
- `ProviderMessageUtilsTest`：只保留真正跨 adapter 的纯规范化；
- `MessageTest`：
  - chunk merge 行为迁到 `StepOutputAccumulatorTest`；
  -普通 UIMessage helper 只保留稳定 transcript 行为；
- `MessageToolOperationsTest`：迁到 `TurnTranscriptTest` / `TurnTransitionTest`；
- `MessageMetadataTest`：
  -保留 Provider opaque metadata codec；
  -删除 Tool runtime JSON 相关断言；
- `ToolApprovalStateTest`：删除，由 V3 typed interaction tests 替代；
- `ExampleUnitTest`：直接删除。

### exact JSON 的使用规则

只有以下内容使用完整 golden：

- Provider 协议必须 exact 的 envelope；
- Tool schema；
- reason/signature/opaque replay；
- migration canonical payload。

其余使用语义断言：

```text
role 顺序
call/result identity
content 顺序
必要字段存在/禁止字段缺失
```

避免因为 JSON object key 顺序或无关默认字段变化导致无意义失败。

---

## 8.6 Sub-assistant 测试

生产不引入 `SubAgent` 词根（执行方案 §1 / §10.2）。测试 package 为 `service/subassistant/`，类名保持 `SubAssistant*`。

### 合并目标

#### `SubAssistantPolicyTest`

合并：

```text
SubAssistantAccessPolicyTest
SubAssistantRunPolicyTest
SubAssistantRuntimeErrorTest 中的纯分类部分
```

#### `SubAssistantLineageTest`

合并：

```text
SubAssistantLineageResolverTest
SubAssistantForkTest
SubAssistantRetentionTest
```

真实 Child clone 与 Artifact copy 保留一个 integration test。

#### `SubAssistantResultProjectionTest`

合并：

```text
SubAssistantArtifactProjectionTest
SubAssistantResultProjectionTest
SubAssistantFinalAnswerTest
SubAssistantChildPartsTest 中的纯 projection
```

#### `SubAssistantRunStateTest`

V3 typed state 后合并：

```text
SubAssistantCallMetadataTest
SubAssistantPreviewReducerTest
SubAssistantRunStateReducerTest
SubAssistantCallCardLogicTest 中的 domain mapping
```

旧 JSON metadata 的兼容解析只留在 migration test，不能继续作为 runtime 权威测试。

#### `SubAssistantRunCoordinatorTest`

合并：

```text
DelegationCoordinatorMaterializationTest
SubAssistantAskUserBridgeTest
SubAssistantChildPartsTest 中的 materialization
```

#### `SubAssistantFinalizationIntegrationTest`

合并：

```text
SubAssistantFinalizationTest
SubAssistantInterruptionProjectionTest
TurnFinalizationAssistantCallTest
TurnRecovery 中 Child 相关场景
```

### 防重复规则

同一个 Child cancelled outcome 不应同时在：

```text
metadata reducer
result helper
coordinator
TurnFinalizer
UI card
```

五层重复断言。

各层只测自己的输出：

- reducer：合法状态转换；
- coordinator：Child 与 Parent owner 关系；
- finalizer：durable terminal；
- UI：typed state 到文本/操作可见性。

---

## 8.7 MCP 测试

### `McpRuntimeCoordinatorTest.kt`

**处置：按三个稳定生命周期拆分：**

```text
McpCatalogLifecycleTest
McpConnectionLifecycleTest
McpTurnCapabilitySnapshotTest
```

不要按每个 public method 建一个文件。

### 保留的高价值测试

- OAuth callback server；
- OAuth trust validation；
- server authorization；
- durable LKG catalog；
- list_changed 后续 Turn 生效、当前 Turn 不漂移；
- disabled/deleted/policy revoked 调用前 fail-closed；
- timeout/health 不删除 LKG schema；
- definition digest mismatch；
- Tool name collision。

### 合并或删除

- `TargetMcpPreparationTest` 并入 `SubAssistantRunCoordinatorTest` 或 `McpTurnCapabilitySnapshotTest`；
- `McpToolFailureTest` 并入 Tool result/error contract；
- `McpOAuthCallbackKeepAliveTest` 先逐断言审查：若只是直接映射一个布尔值，合并到 callback server lifecycle；不保留独立微型文件。

Enterprise Gateway 测试不得混入 Local MCP Runtime。V3 不写 stamp，因此不新增 `TurnContextEnterpriseStampTest`。未来真正接入后再单独立项。

---

## 8.8 Artifact、附件与图片测试

这些测试中很多文件较大，但保护的是资源所有权、删除、恢复和安全边界，不能为了减少数量大面积删除。

### `ArtifactStoreLifecycleTest.kt`

按状态机拆为最多四个文件：

```text
ArtifactCreationTest
ArtifactReferenceProjectionTest
ArtifactDeletionTest
ArtifactRecoveryTest
```

不进一步按每个方法拆碎。

### 合并原则

- `ArtifactBatchDiscardTest` 可并入 `ArtifactCreationTest`；
- `LocalArtifactRefTest` 保留版本/path validation，或并入 reference test；
- `ArtifactUploadImageReadTest` 与 instrumentation test 分工：
  - JVM：authorization/result mapping；
  - Android：真实 bytes、MIME sniff、retention；
- `ConversationArtifactReferencesTest` 与 `ConversationFileReferencesTest` 合并为一个 canonical reference projection test；
- `WorkspaceToolsTest` 删除，不再重复 attachment ref；
- 图片失败 UI 测试只保留 typed failure → UI projection；不重复生成 coordinator failure；
- `AttachmentInspectionFailureUiTest`、`ImageGenerationFailureUiTest` 若仅验证同一个通用 failure card，应合并为 `ToolFailurePresentationTest`；
- `AttachmentRefsTest` 保留，它覆盖幂等、metadata merge、malformed ref、Windows URL 和 nested Tool backfill；
- `SafeRemoteMediaFetcherTest` 保留 SSRF、size、redirect 和 MIME 安全边界。

### Image Generation

将：

```text
ImageGenerationCoordinatorTest
ImageGenerationToolCompensationTest
GeneratedMediaStoreTest
GeneratedMediaScopedCleanupTest
```

按 owner 去重：

- coordinator 只测选择与 orchestration；
- Tool 只测 Tool execution/result/lease；
- store 只测 payload lifecycle；
- UI 只测 typed result presentation。

---

## 8.9 Settings、Managed overlay、DB 与 Backup

### Settings

保留：

- commit 后才 publish；
- normalization；
- legacy OCR migration；
- Provider owner resolution；
- local + managed effective resolution；
- managed expiry；
- sensitive credential ownership。

合并：

```text
DisplaySettingTtsTest
DisplaySettingMutationTest
TtsControllerPolicyTest
```

到对应 Settings/UI policy owner，不重复默认值。

当前 Signed Overlay prototype 的测试收缩为：

```text
SignedOverlayPrototypeVerificationTest
SignedOverlayPrototypeStorageTest
ManagedSettingsContributionTest
```

正式 Enterprise Snapshot 到来后，不继续扩展 prototype wire 测试。

### DB

- `MemoryDAOTestRef.kt`：直接删除，它没有任何测试类型或断言；
- `WorkspaceEntityTest`：保留 unknown persisted value → BROKEN 的 fail-closed 行为，重命名或并入 `WorkspacePersistenceMappingTest`；
- `MessageNodeTokenStatsTest`：删除源码 SQL 检查，保留 instrumentation；
- DAO mapper 纯转换测试保留；
- 真实 SQL 进入 Room test。

### Backup

保留：

- zip-slip；
- duplicate entry；
- size limit；
- manifest hash；
- DB/files aggregate；
- staged restore；
- restart publish；
- legacy migration；
- cancellation cleanup。

V3 新增：

```text
BackupV4ToV5RestoreTest
V3BackupRoundTripTest
```

`BackupArchivePathTest` 若只是重复 zip-slip helper，合并进 `BackupArchiveServiceTest`，不独立保留。

---

## 8.10 UI 与 Android adapter 测试

### UI 纯 policy 测试

当前存在大量一个 helper 一个文件的短测试。不能全部删除，但应按组件 aggregate 合并：

```text
ChatInputPolicyTest
ChatFeedbackPolicyTest
ImagePreviewPolicyTest
SettingsUiPolicyTest
MessagePresentationPolicyTest
AdaptiveLayoutPolicyTest
```

例如：

- `ChatInputActionVisibilityTest`、`CropResultDispositionTest` → `ChatInputPolicyTest`；
- `ImagePreviewActionsTest`、`ImagePreviewDialogProgressTest`、`ClassifyImageSourceTest` → `ImagePreviewPolicyTest`；
- `SettingMcpHeaderVisibilityTest`、`DisplaySettingMutationTest` → `SettingsUiPolicyTest`；
- `ChainOfThoughtSelectionTest`、`ChatMessageCotTest` → `MessagePresentationPolicyTest`，前提是各自仍有不同语义；
- `ImeAutoScrollerTest` 逐断言审查，若仅映射一个常量则合并到 chat scroll policy；若保护 IME race 则保留。

### Haptic

当前多层测试重叠，目标收敛为：

```text
TurnFeedbackPolicyTest             // JVM，何时反馈
AndroidTurnFeedbackAdapterTest     // Robolectric，如何调用 Android
TurnFeedbackLifecycleTest          // instrumentation，仅真实 lifecycle 必要行为
```

删除 service、UI helper、Android adapter 对同一枚举 mapping 的重复断言。

### TTS

目标：

```text
TtsPlaybackQueuePolicyTest
TtsControllerTest
SystemTtsSequentialPlaybackInstrumentedTest
```

保留真实 System TTS instrumentation；删除模块模板 instrumentation。

### Compose instrumentation

保留：

- semantics 可访问性；
- adaptive/foldable layout；
- dialog container；
- scroll/IME；
- activity lifecycle；
- clipboard/intent；
-真实 font/image Android integration。

删除：

- 只重复纯函数 policy；
- 只验证 composable 存在；
- 依赖像素坐标但没有稳定语义的脆弱测试。

---

## 8.11 其他模块

### 全仓 true boilerplate

删除模式：

```text
*/src/test/**/ExampleUnitTest.kt
*/src/androidTest/**/ExampleInstrumentedTest.kt
```

但必须先检查内容。

**明确例外：**

```text
workspace/src/test/.../ExampleUnitTest.kt
```

它不是模板测试，必须重命名并拆分为：

```text
WorkspaceFileSystemTest.kt
WorkspaceManagerTest.kt
RootfsPatcherTest.kt
```

### `highlight`

保留：

```text
CodeHighlighterTest
HighlightEngineTest
RegexesTest
LanguageFixtureTest
HljsFixtures
```

删除真正的 `ExampleUnitTest` / `ExampleInstrumentedTest`。

### `speech`

保留：

```text
SystemTtsSequentialPlaybackInstrumentedTest
```

删除模板 instrumentation。

### `material3`

当前只有模板测试时全部删除；不要为了 source set 不为空保留 `2 + 2`。

### `common` / `document` / `search`

删除模板测试；真实 parser/network mapping 测试按模块 owner 保留。

---

# 9. 立即执行的删除、重命名与合并清单

## 9.1 可直接确认删除

```text
ai/src/test/.../ExampleUnitTest.kt
所有确认仍为 Android Studio 默认内容的 ExampleUnitTest.kt
所有确认仍为 Android Studio 默认内容的 ExampleInstrumentedTest.kt
app/src/test/.../data/db/dao/MemoryDAOTestRef.kt
app/src/test/.../data/ai/tools/WorkspaceToolsTest.kt
app/src/test/.../architecture/ArchitecturePerformanceEvidenceTest.kt
  （先迁移 changed-row / identity invariant）
app/src/test/.../architecture/UpstreamBatch13BoundaryTest.kt
  （先迁移稳定依赖规则）
app/src/test/.../data/db/dao/MessageNodeTokenStatsTest.kt
  （V3 instrumentation 测试接管）
```

V3 类型退休后删除：

```text
ToolApprovalStateTest.kt
ToolCallPhaseTest.kt
GenerationRequestFixtures.kt
TurnRequestContextFixtures.kt
ModelContextTestSupport.kt 中已无消费者的部分
所有 GenerationLoop / TurnEngine 旧 fixture
```

拆解完成后删除原文件：

```text
SingleWriterContractTest.kt
SnapshotOnlyContractTest.kt
GenerationLoopFlowTest.kt
ChatCompletionsAPIMessageTest.kt
ResponseAPIMessageTest.kt
```

## 9.2 明确重命名

```text
CheckpointWriteAmplificationTest
→ TurnPersistenceDeltaTest

StreamingTransformScopeTest
→ TurnStreamProjectionTest

FtsDeltaScopeTest
→ MessageFtsDeltaIntegrationTest

MasterTurnCoordinatorTest
→ ConversationTurnServiceTest

TurnEngineTest
→ TurnCommitterTest

TurnRequestContextFactoryTest
→ TurnContextFactoryTest

ApprovalContinuationMissingToolIntegrationTest
→ TurnInteractionContinuationIntegrationTest

DelegationCoordinatorMaterializationTest
→ SubAssistantRunCoordinatorTest
```

`SubAssistantRunGateTest` 只搬家，不改名。其余 `SubAssistant*Test` 按 §8.6 合并，**不**改成 `SubAgent*`。

## 9.3 不能自动删除、必须逐断言审查的候选

```text
JavascriptToolTest
McpOAuthCallbackKeepAliveTest
ImageGenerationFailureUiTest
AttachmentInspectionFailureUiTest
SettingMcpHeaderVisibilityTest
ChainOfThoughtSelectionTest
ImeAutoScrollerTest
UpdateCheckerTest
CoroutineResultTest
WorkspaceEntityTest
BackupArchivePathTest
```

其中短小不构成删除理由。审查重点是：

- 是否保护真实历史回归；
- 是否已被更强测试覆盖；
- 是否只检查常量直通；
- 是否应合并到同 owner 文件。

---

# 10. Migration 与兼容测试

## 10.1 不按年龄删除 migration

现有 migration test 继续保留，直到项目明确提高“最早支持恢复/升级的数据库版本”。

每个 migration 不必都重复完整 schema 检查。建立共享：

```text
RoomMigrationTestHarness
```

负责：

- 创建 old schema；
- 执行 migration；
- Room schema validation；
- common data preservation；
- FK/indices 检查。

每个 migration 文件只保留自己的特殊语义。

## 10.2 V3 `Migration_10_11Test`

生产合同是执行方案 §11（含 `transcript_schema` 同构、CREATED→INTERRUPTED、终态 Pending 收口、`tool_execution` 重建幂等）。本测试只验证那些合同，不另写一套映射。

必须覆盖：

1. Legacy DTO → `UIMessagePart.Step` + typed Tool JSON 键（`providerCallId` / `interactionState`）；不得用 V3 `Tool` 直接解旧行。
2. 确定性 `stepId` / `localCallId`（namespace 见执行方案 §11.4）。
3. `tool_runtime` 删除；Provider opaque metadata 保留；`sub_assistant_call.schema_version = 2`。
4. Artifact 引用集合相等（改读 `runtimeState.archive`）。
5. `AWAITING_APPROVAL → AWAITING_USER`；`CREATED → INTERRUPTED`。
6. `tool_execution` 状态矩阵（执行方案 §11.6）：半截临时表丢弃重填；DROP 后仅临时表则 RENAME；两表皆无则失败。
7. DDL 中断重入：建临时表后、INSERT 中、INSERT 完未 DROP、DROP 后未 RENAME、RENAME 后缺索引。
8. 终态 / 无 turn 行不得留下 open Step；非终态 Pending 保持 open。
9. STARTED 找不到 Tool、终态 ordinal 越界、非法 `tool_runtime` → 失败。
10. 分批游标，不一次装入全库。

## 10.3 Legacy transcript migrator

纯 JVM：

```text
LegacyTurnTranscriptMigratorTest
```

真实 Room：

```text
Migration_10_11Test
```

职责分离：

- pure test 覆盖 payload mapping matrix；
- instrumentation 覆盖 transaction、schema、真实 JSON/SQL。

不在两层复制全部 case。

## 10.4 Backup

```text
BackupRestoreMigrationIntegrationTest   // 现有；V3 增加 v4→v5 样本，可改名为 BackupV4ToV5RestoreTest
V3BackupRoundTripTest                   // 仅 v5 导出再读入，不得再跑 transcript 转换
```

必须验证 restore 使用同一个 `LegacyTurnTranscriptMigrator`。v3/v4：**staging 内升到 v11 并验证后**才进入 pending；升级失败不得 swap live。v5 库不得再跑转换。不允许 DB migration 与 backup 各写一套改写。

---

# 11. 并发、时间、取消与资源测试规范

## 11.1 禁止 wall-clock 等待

禁止：

```text
Thread.sleep
delay(固定毫秒后猜状态)
轮询直到 timeout
```

使用：

```text
runTest
CompletableDeferred
Channel
Mutex barrier
TestCoroutineScheduler
advanceUntilIdle
```

## 11.2 竞态测试必须有显式交接点

典型 barrier：

```text
before START commit
after START commit, before publish
before Provider first output
after model response, before pending checkpoint
after Tool STARTED commit, before side effect
after side effect, before result commit
after result commit, before Artifact publish
during terminal commit
```

每个竞态测试必须说明它控制的交接点，不允许依赖调度器“碰巧”切换。

## 11.3 取消原因 first-wins

覆盖：

- user stop；
- superseded；
- parent Sub-assistant cancelled；
- policy revoked；
- process restart；
- provider failure concurrent with cancel。

只测试一次最终 durable outcome；UI presentation 不重复推导 terminal truth。

## 11.4 资源所有权

Artifact/Tool resource tests必须观察 lease 状态机：

```text
unpublished
checkpointed
published
discarded
retained
```

不能只验证文件最后“存在/不存在”，否则无法定位提交与补偿顺序错误。

## 11.5 Mock 使用

核心状态机禁止大面积 `relaxed = true`。

Mock 适合：

- 单次 leaf call；
- Android framework adapter；
- 无状态查询 port。

Fake 适合：

- Provider stream；
- Tool execution；
- commit protocol；
- Repository transaction；
- Artifact lease；
- MCP runtime state。

---

# 12. Provider contract suite

## 12.1 两层协议测试

### Provider-independent

`RequestAssemblerTest` 验证：

- V3 `UIMessagePart.Step` 不进入 Provider（V3-B～E 经 `transcriptPartsForProvider`；V3-F 经 `ModelRequestMessage`）；
- Tool call/result identity；
- terminal safe prefix；
- Disclosure 位置；
- selected branch；
- request receipt；
- media capability projection。

### Adapter-specific

每个 Provider 只验证自身 wire：

- Chat Completions role/tool ordering；
- Responses item ordering；
- Claude content blocks/cache controls；
- Gemini same-role merge/function response；
- DeepSeek reasoning replay；
- usage parser；
- endpoint-specific request options。

## 12.2 Contract case 复用

使用普通 fixture function：

```kotlin
fun canonicalTwoStepToolTurn(): List<ModelRequestMessage>
```

不建立复杂 abstract base test。每个 adapter 显式调用 fixture，断言自己的 wire，阅读测试时仍能看出 Provider 规则。

## 12.3 Parser 与 serializer 分开

- request serializer test 不混 response parser；
- usage parser 不混 endpoint profile；
- reasoning policy 不混 media fallback；
- Tool schema 不混 chat transcript。

这样 V3 修改 `ModelRequestMessage` 时，只影响 request contract，不会把整个 Provider 文件打红。

---

# 13. 性能测试重构

## 13.1 普通测试只测复杂度事实

保留：

- 1 changed node → 1 node upsert；
- stream chunk → 0 history transforms；
- Tool schema 每 Turn freeze 一次；
- request assembly 不重读 Settings；
- recovery query 与 nonterminal execution 数量相关；
- read range 不扫描目标范围之后的 payload；
- checkpoint 不携带完整 currentMessages。

使用计数器、recording fake、对象 identity，不使用 wall-clock。

## 13.2 Controlled benchmark

新增或整理为独立 benchmark source set/任务，优先复用现有 Android benchmark 能力，不建立自制 JUnit benchmark。

场景：

```text
10k stream chunks
1k message selected branch
100 large historical Tool Results
50 Tool schema
large Room migration
Tool Output 100 MB range read/grep
Compose active Assistant updates
```

指标：

- allocation；
- wall time；
- p50/p95；
- DB rows written；
- bytes read；
- recomposition count。

门禁阈值只能在受控 runner 上比较相对 baseline；普通开发机只输出报告，不作为 pass/fail。

## 13.3 删除伪性能测试

删除所有：

- 只打印数字；
- 没有断言；
- 使用单次 `nanoTime`；
- 同一个 JVM 中比较两段未经 warm-up 的实现；
- 将 synthetic legacy implementation 当真实基线。

---

# 14. CI 分层

## 14.1 Tier 0：结构与编译

每个 PR：

- Kotlin compile；
- `ArchitectureDependencyTest`；
- `RetiredSurfaceContractTest`；
- `TurnStepProtocolContractTest`；
- `git diff --check`；
- schema/generated file consistency。

目标：快速发现错误依赖、旧符号回流和协议结构破坏。

## 14.2 Tier 1：全 JVM

每个 PR：

- 所有 module unit tests；
- pure transition/planner；
- TurnRunner/StepRunner fakes；
- Provider wire；
- deterministic filesystem tests；
- Robolectric 小型 adapter test。

目标：不依赖 emulator，稳定、快速。

## 14.3 Tier 2：持久化与重型 Robolectric

每个 PR 或 core 路径变更：

- Room in-memory；
- FTS；
- Backup service；
- Artifact lifecycle；
- MCP lifecycle；
- application service integration。

## 14.4 Tier 3：Android instrumentation

主分支合并和 release 必跑：

- 全 migration；
- Room DAO；
- backup restore；
- ContentResolver/Bitmap；
- Turn cancellation/interaction；
- Compose semantics；
- System TTS；
- lifecycle/foreground service；
-真实 Android 文件和权限 adapter。

V3 修改 DB、Turn Runtime、Artifact、Sub-assistant 或 UI Runtime 时，PR 也必须跑对应 instrumentation，不得只等 nightly。

## 14.5 Tier 4：Nightly / controlled runner

- benchmark；
- 长时间 queue；
- 大 migration；
- flaky detector，多次重复但不自动吞失败；
- release build；
-必要的多 API level / ABI matrix。

## 14.6 不允许永久 retry

测试失败 retry 只能用于诊断，不得把第二次通过视为合并通过。

临时 quarantine 必须同时记录：

```text
owner
issue
原因
风险
到期日期
替代门禁
```

到期仍未修复则恢复阻断，不允许永久跳过。

---

# 15. 实施顺序

## T0 — 测试基线与审计台账

1. 冻结本方案对应 commit；
2. 枚举全部 `src/test` / `src/androidTest`；
3. 记录每个 test method 的处置状态；
4. 运行现有完整测试并记录：
   - pass/fail；
   - 耗时；
   - flaky；
   - 重复 fixture；
   - Android-only 原因；
5. 不删除行为测试。

唯一台账：`docs/dev/v3-test-audit.md`（T0 创建，V3 完成后随本计划删除）。格式见附录 A。**该文件未覆盖全部现有 test method 之前，不得进入 T1。** 不把台账散落到 PR 评论。

## T1 — 无争议清理

- 删除 true boilerplate；
- 删除 `MemoryDAOTestRef`；
- 删除/迁移伪 benchmark；
- 将 workspace `ExampleUnitTest` 正确重命名拆分；
- 建立 `TestClockAndIds`、`ScriptedProvider`、`RecordingTool` 等基础 fixture；
- 不改变生产行为。

## T2 — 与 V3-A 对齐的测试合同

不改生产行为。建立测试台账与 TestKit 骨架。协议测试 **随 V3-B 类型落地才可编译运行**，不在生产类型之前提交会红的空壳。

## T3 — 随 V3-B：类型、写路径、Migration_10_11 同交付

生产已只写 V3 JSON。测试同步：

- `UIMessagePart.Step` 不变量；
- typed Tool / `transcriptPartsForProvider`；
- `LegacyTurnTranscriptMigratorTest` + `Migration_10_11Test` + v4 restore；
- 删除 `ToolApprovalStateTest`。

禁止测试仍写入 `tool_runtime` 却断言 `transcript_schema=3`。

## T4 — Runtime / Command

随 V3-C：

- split ConversationTransition / TurnTransition；
- ActiveTurnSession；
- stream projection；
- typed checkpoint。

迁移：

```text
ConversationRuntime*
TurnEngine*
CheckpointWriteAmplification*
SnapshotOnly*
```

旧测试只在同一阶段新测试通过后删除。

## T5 — TurnRunner / StepRunner

随 V3-D：

- 拆解 `GenerationLoopFlowTest`；
-建立 TurnRunner、StepRunner、ToolBatchRunner、TurnCommitter；
-删除 GenerationRequest/Chunk/FinishedReason fixtures；
-建立竞态 barrier tests。

## T6 — 随 V3-E：Conversation entry 与 Sub-assistant

- `ConversationTurnServiceTest`；
- `SubAssistantRunCoordinatorTest`；
- Child 复用同一 TurnRunner；
- 合并 Sub-assistant 测试族（不改名 SubAgent）；
- 一条完整 Parent Tool → Child Turn → Parent Result integration。

## T7 — Request / Provider / Compaction

随 V3-F：

- `ModelRequestMessage`；
- `RequestAssemblerTest`；
- Provider contract suite；
- Context planner；
- compaction receipt；
-拆解两个 OpenAI 巨型 message test；
-删除 provider 直接消费 UIMessage 的旧测试。

## T8 — Android、CI 与最终清理

- Migration/backup/device gate；
- UI policy 合并；
- haptic/TTS 去重；
- architecture test 清理；
-所有旧生产符号和旧测试 fixture 物理删除；
-更新 stable testing reference；
-退休本计划文档。

---

# 16. 每阶段验收规则

每个 V3 阶段必须同时满足：

1. 新测试保护的是正式语义，不是临时代码形状；
2. 旧测试删除有明确替代项；
3. 没有 production 兼容 facade 仅用于让旧测试通过；
4. 没有 ignored test；
5. 没有永久 retry；
6. critical state test 使用确定性时钟、ID 和 barrier；
7. JVM 与 instrumentation 的职责没有重复；
8. 测试名称使用正式 V3 术语；
9. 失败消息可以定位到 owner 与不变量；
10. 该阶段相关文档同步更新。

---

# 17. V3 测试 Exit 标准

只有全部满足，V3 测试重构才完成：

1. 全仓每个旧测试用例已有 KEEP/REWRITE/MERGE/MOVE/DELETE 记录；
2. 所有 true boilerplate 测试删除；
3. `MemoryDAOTestRef` 等空辅助文件删除；
4. `SingleWriterContractTest` 不再是政策垃圾桶；
5. 不再存在 Batch/事故编号命名的永久测试；
6. 不再存在普通 JUnit 伪 benchmark；
7. 不再通过 exact source snippet 保护运行时语义；
8. `GenerationLoopFlowTest` 等跨 owner 巨型测试已拆解；
9. Provider common case 不在每个 adapter 重复维护；
10. Turn/Step/Tool 状态转换由 typed protocol tests 覆盖；
11. START、checkpoint、continuation、terminal、recovery 有完整失败和竞态测试；
12. 用户会话与 Child 使用同一 Runner 的事实有集成证明；
13. v10→v11 与 backup v4→v5 的数据保全测试通过（含幂等、fail-closed、v5 不二次转换）；
14. 旧 migration coverage 未被无依据删除；
15. Tool Output rolling compaction 的 eligibility、receipt、archive 和 retrieval 分层清楚；
16. Runtime streaming test 不遍历或复制历史；
17. critical tests 无真实 sleep、真实网络和随机时间；
18. UI policy 与 Android adapter 不重复；
19. 所有 module JVM tests 通过；
20. 所有相关 Android instrumentation 通过；
21. `test assembleDebug lintDebug assembleRelease` 通过；
22. release 前 `connectedDebugAndroidTest` 通过；
23. 无 ignored/quarantined critical test；
24. 旧 V2 测试类型、fixture 和兼容路径物理删除；
25. 稳定测试规则进入 `docs/references/testing-strategy.md`，本开发计划退休。

---

# 附录 A：审计台账格式

落点：`docs/dev/v3-test-audit.md`。每一行对应一个旧 test method，而不只是 test file：

| Old test | Protected contract | Risk | Disposition | V3 owner | Destination | Delete condition |
|---|---|---:|---|---|---|---|
| `GenerationLoopFlowTest.invalid pending...` | mixed batch gate | Critical | REWRITE | `ToolBatchRunner` | `ToolBatchRunnerTest` | 新 case 通过 |
| `WorkspaceToolsTest.workspace image...` | attachment ref | Medium | DELETE_REDUNDANT | `AttachmentRefs` | `AttachmentRefsTest` | 已有 nested producer case |
| `MemoryDAOTestRef` | 无 | None | DELETE_BOILERPLATE | — | — | 立即 |
| `MessageNodeTokenStatsTest...` | legacy token SQL | High | DELETE_REDUNDANT | Room DAO | instrumentation test | V3 payload case 通过 |

Risk 只允许：

```text
Critical
High
Medium
Low
None
```

Critical 包括：

- durable transaction；
- side effect exactly-once/unknown；
- authorization；
- process recovery；
- Provider replay；
- migration；
- Artifact ownership。

---

# 附录 B：V3 核心场景矩阵

| 场景 | Transition | Runner | Commit | Integration |
|---|---:|---:|---:|---:|
| 单 Step 正常回答 | ✓ | ✓ | ✓ | — |
| 多 Step Tool loop | ✓ | ✓ | ✓ | ✓ |
| Provider retry before output | — | ✓ | — | — |
| partial stream failure | ✓ | ✓ | ✓ | ✓ |
| missing terminal event | ✓ | ✓ | ✓ | — |
| invalid Tool arguments | ✓ | ✓ | ✓ | — |
| mixed invalid/pending/auto | ✓ | ✓ | ✓ | ✓ |
| multiple approvals | ✓ | ✓ | ✓ | ✓ |
| ask_user answer | ✓ | ✓ | ✓ | ✓ |
| stale/duplicate decision | ✓ | — | ✓ | ✓ |
| cancel before START | — | ✓ | — | ✓ |
| cancel during model stream | ✓ | ✓ | ✓ | ✓ |
| cancel after Tool STARTED | ✓ | ✓ | ✓ | ✓ |
| result commit failure | ✓ | ✓ | ✓ | ✓ |
| Artifact publish failure | — | ✓ | ✓ | ✓ |
| process restart | ✓ | — | ✓ | ✓ |
| regenerate/fork/variant | ✓ | — | ✓ | ✓ |
| Child create/reuse/clone | ✓ | ✓ | ✓ | ✓ |
| Child ask_user（父保持 RUNNING，决定打 Child） | ✓ | ✓ | ✓ | ✓ |
| 无 Tool 单 Step：仅 START + FinalizeTurn | — | ✓ | ✓ | — |
| Tool 领域失败后续 Call 仍执行 | — | ✓ | ✓ | — |
| materialize 失败 → FinalizeTurn | — | — | ✓ | ✓ |
| policy revoked mid Child | ✓ | ✓ | ✓ | ✓ |
| rolling archive/fold/preserve | ✓ | ✓ | ✓ | ✓ |
| Provider terminal replay | — | — | — | adapter |
| V10→V11 migration | — | — | — | instrumentation |
| backup v4→v5 | — | — | — | instrumentation |

“✓”不表示每格都复制同一断言，而是各层只验证自己的输出：

- Transition：合法状态；
- Runner：调用顺序和决策；
- Commit：原子落盘；
- Integration：边界连接。

---

# 最终决策

V3 测试体系不应是生产类数量的镜像，也不应保留历史事故的博物馆。它应当成为一组围绕稳定语义 owner 的高信号证据：

```text
少量协议不变量
+ 明确的状态机测试
+ 确定性的 Runner 场景
+ 必要的事务与平台集成
+ 独立的性能 benchmark
```

测试清理的衡量标准不是“删了多少”，而是：

- 删除一条测试后是否仍能明确证明相同风险；
- 某条测试失败时是否能立刻定位到唯一 owner；
- V3 重命名或内部重排时，未改变契约的测试是否保持稳定；
- 真正破坏 Turn / Step / Tool / durability 的变更是否必然被阻断。
