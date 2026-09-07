# 测试策略 Testing Strategy

本文描述本仓库**当前**的测试分层、所有权与稳定性约定，是新增/审查测试时的稳定依据。
它不记录迁移过程，只描述长期成立的规则。生产语义与参考文档冲突时以代码为准。

## 1. 分层（按运行位置与真实依赖选择）

| 层 | 运行位置 | 覆盖 | 约束 |
| --- | --- | --- | --- |
| L1 纯协议/纯函数 | JVM | `ConversationTransition`、`TurnStepProtocolContractTest` 锁定的 transcript/step 不变量、`ToolResultStatus`/审批状态、Request Context 选择、`ContextBudget`、compaction plan、sub-assistant policy/lineage/projection、Provider-independent replay projection、字符串/路径/解析算法 | 不需 Android Context；不 mock Room；不启动协程 Job（除非被测即协程协议）；table-driven；无真实时钟与随机 UUID |
| L2 组件/应用服务 | JVM（必要时 Robolectric） | `TurnRunner`、`StepRunner`、`ToolBatchRunner`、`TurnCommitter`、`ConversationTurnService`、`ConversationRuntime`、`SubAssistantRunCoordinator`、SettingsStore commit-then-publish、Artifact/MCP runtime owner | 用确定性 fake，不得把依赖整体设成 `relaxed = true` |
| L3 持久化/协议集成 | Robolectric 或 instrumentation | Room 事务、DAO SQL、FTS、conversation delta、artifact 引用、tool execution facts、backup staging、Provider request JSON 与 parser、transcript 序列化 | 只在真实 DB/平台行为有价值时进入；不 mock SQL 后再断言 SQL 字符串；同一 SQL 契约不同时保留"字符串包含"与"真实执行"两套 |
| L4 Android 平台/Compose | instrumentation | Room Migration、ContentResolver/Uri、Bitmap/Exif、进程生命周期、前台服务、Haptic/Vibrator adapter、System TTS、Compose semantics/焦点/IME/自适应布局、Intent/剪贴板/分享 | 只验证 JVM 无法可靠证明的事实；UI policy 先在 JVM 测，instrumentation 只验 adapter 与真实 Compose 绑定；不重复测纯函数决策 |
| L5 性能/长期稳定性 | controlled benchmark / nightly | stream chunk 吞吐、活跃 Assistant 分配、checkpoint 写次数、tool output read/grep、大会话 request assembly、migration 大数据量、Compose recomposition、长 TTS 队列 | 普通单测只验证**结构性复杂度**（如 `TurnPersistenceDeltaTest` 断言写次数而非耗时）；时间与内存放 benchmark |

## 2. 目录与命名约定

- 不新增 Gradle module；测试 package 与生产 package 对齐。
- `app/src/test/.../pilot/architecture/`：跨切面静态契约测试（见 §5）。
- `app/src/test/.../pilot/service/turn/`、`service/runtime/`、`service/`、`service/subassistant/`：Turn/Step/Runtime/子助手编排。
- `app/src/test/.../pilot/data/ai/request/`、`data/ai/tools/`、`data/ai/subassistant/`：请求装配、工具与 compaction、子助手投影。
- `ai/src/test/.../provider/providers/`：各 adapter 的 wire 契约（见 §4）。
- 测试类以 `*Test` 结尾；名称使用正式 V3 术语（Turn/Step/Tool/Interaction/Execution/Result），不用批次或事故编号。

## 3. Owner 与唯一权威测试

每类 durable 事实只有一个 owner，也只有一个**权威测试**。同一契约最多三层：一个最近 owner 的权威测试、一个必要的跨边界集成测试、一个必要的平台测试；超过必须说明每层观察的不同事实。

当前主要映射（语义 → 权威测试）：

| 语义 | 权威测试 |
| --- | --- |
| Conversation header/tree/variant | `ConversationTransitionTest` |
| Turn/Step/Tool 协议不变量 | `TurnStepProtocolContractTest` |
| commit-then-publish 与命令锁 | `ConversationCommandCoordinatorTest` |
| active Turn session 与 live phase | `ConversationRuntimeTest` |
| streaming overlay | `TurnStreamProjectionTest` |
| Turn 多 Step 循环 | `TurnRunnerTest` |
| 单 Step request + tool batch | `StepRunnerTest` |
| Tool batch gate 与顺序 | `ToolBatchRunnerTest` |
| durable checkpoint | `TurnCommitterTest` |
| terminal finalization（失败/取消/stop 收口） | `TurnFinalizerTest` |
| restart recovery | `TurnRecoveryTest` |
| Turn entry | `ConversationTurnServiceTest` |
| 单 Tool 解析与执行包装 | `ToolCallRuntimeTest` |
| 审批 gate | `ToolCallRuntimeTest`、`ToolApprovalReducerTest` |
| Tool Result envelope | `ToolResultContractTest` |
| 跨 chunk 输出拼装 | `StepOutputAccumulatorTest` |
| request history/disclosure 选择 | `RequestContextPlannerTest` |
| Provider-independent 装配 | `RequestAssemblerTest` |
| rolling compaction 选择与计划 | `ToolOutputCompactionPlannerTest` |
| compaction token 记账 | `ToolOutputCompactionAccountingTest` |
| tool output 协议与 marker | `ToolOutputProtocolTest` |
| request/turn usage | `RequestUsageReducerTest`、`TurnUsageTest` |
| Tool lease 顺序 | `ToolResourceCommitTest` |
| Tool set 冻结 | `TurnToolSetFactoryMcpTest`、`FrozenToolSetTest` |
| 子助手访问/运行策略 | `SubAssistantAccessPolicyTest`、`SubAssistantRunPolicyTest` |
| 子助手 lineage/retention | `SubAssistantLineageTest` |
| 子助手结果投影 | `SubAssistantResultProjectionTest` |
| Child run 编排 | `SubAssistantRunCoordinatorTest` |
| Room V3 migration / backup | `Migration_10_11Test`、backup restore instrumentation |

## 4. Provider contract suite（两层）

- **Provider-independent**：`RequestAssemblerTest` 是唯一 `UIMessage → ModelRequestMessage` 边界的权威测试，守住 Step 丢弃、tool call/result identity、terminal safe prefix、Disclosure 位置、selected branch、request receipt、media capability 投影。
- **Adapter-specific**：每个 adapter 只验自身 wire。请求序列化与响应解析**分开**（§12.3）：
  - `ChatCompletionsAPISerializerTest` / `ChatCompletionsAPIParserTest`
  - `ResponseAPISerializerTest` / `ResponseAPIParserTest`
  - `ClaudeProviderMessageTest`、`GoogleProviderMessageTest`（纯序列化）
  - usage parser 独立（`ChatCompletionsAPIUsageTest` 等），endpoint profile 不混入 parser。
- **共享 fixture**：`ai/.../testsupport/ProviderRequestContractFixtures.kt` 提供 `executedTool()` 与 `canonicalMultiRoundToolTurn()` 等 canonical 输入；各 adapter 显式调用同一输入、断言各自 wire。common case 只维护一次（Exit 9），不建复杂 abstract base test。
- 跨 chunk 参数拼接、reasoning 合并归 app `StepOutputAccumulator`，adapter parser 只锁 index→call-id/name 投影。

## 5. 架构契约测试

`app/src/test/.../pilot/architecture/` 用源码扫描（非运行时）锁定 owner 与退休面，共享扫描器 `ArchitectureSources.kt`
（`architectureSourceRoot`/`architectureSources`/`sourcesUnder`/`hits`/`assertNoHits`）：

- `ArchitectureDependencyTest`：分层与所有权——UI 只经 application/query ports、durable 写入单一 caller graph、runtime 加载受限、标题 CAS、MCP query 边界、`ArtifactDAO` 单 owner。
- `RetiredSurfaceContractTest`：已退休兼容面不得回归（`ToolSetRunMode`、`runCatching` 吞取消、`@Deprecated` 转发、`getKoin` 服务定位器、退休 MCP/master 文件）。
- `TurnStepProtocolContractTest`：loop/compaction/tool-lifecycle/turn-context/live-phase/recovery/fgs-timeout 的 owner 与顺序封印。

架构契约测试只锁定 owner、依赖方向、退休符号与协议顺序，**不**用 exact source snippet 锁运行时/UI 行为（Exit 7）；
具体 Compose 渲染与内联运行时逻辑归各自行为测试。durable 只读 snapshot 与 UI import 边界并入 `ArchitectureDependencyTest`；
checkpoint 写放大是行为事实，归 `service/turn/TurnPersistenceDeltaTest`（非源码扫描）。

这些测试断言的是**架构不变量**，失败信息须定位到 owner 与被破坏的约定。

## 6. 关键不变量的测试锚点

三条不可打折的不变量各有明确锚点：

- **工具输出滚动裁剪（含所有工具与 MCP 工具）**：`ToolOutputCompactionPlannerTest` 锁 eligibility（只压缩本次成功请求确实可见、已消费的历史 inline tool result）、`ToolOutputPolicy` 三形态（archiving/folding/PRESERVE）不混用、保护窗口与净回收阈值；阈值唯一来源 `ContextBudget`。`ToolOutputProtocolTest` 锁 marker 与协议上限。
- **Prompt cache 前缀稳定**：`ClaudeProviderPromptCacheTest` 锁 cache_control 断点；compaction 只在预算触发时改写已消费历史，保护窗口内最近批次/最近 token 不参与——前缀失效是预算触发的预期代价，不得靠"不压缩"换取。
- **工具审批语义与子代理 `ask_user` 透传**：`ToolApprovalReducerTest`/`ToolCallRuntimeTest` 锁审批 gate 与 typed phase；`SubAssistantRunCoordinatorTest` 锁子助手 `ask_user` 经 bridge 透传到父会话、续跑保留原 `TurnHandle`。

## 7. 确定性、竞态、取消与所有权规则

- **禁止 wall-clock 等待**：不用 `Thread.sleep`、固定 `delay` 后猜状态、轮询到 timeout。用 `runTest`、`CompletableDeferred`、`Channel`、`Mutex` barrier、`TestCoroutineScheduler`、`advanceUntilIdle`。
- **竞态测试必须有显式交接点**：说明它控制的 barrier（START commit 前/后、Provider 首输出前、response 后 pending checkpoint 前、Tool STARTED commit 后 side effect 前、result commit 后 Artifact publish 前、terminal commit 中），不依赖调度器"碰巧"切换。
- **取消原因 first-wins**：覆盖 user stop、superseded、parent cancelled、policy revoked、process restart、provider failure 与 cancel 并发；只测一次最终 durable outcome，UI presentation 不重复推导 terminal truth。
- **资源所有权**：Artifact/Tool resource 测试观察 lease 状态机（unpublished→checkpointed→published→discarded/retained），不能只验文件"存在/不存在"。
- **Mock vs Fake**：核心状态机禁止大面积 `relaxed = true`。Mock 适合单次 leaf call、Android framework adapter、无状态查询 port；Fake 适合 Provider stream、Tool execution、commit protocol、Repository transaction、Artifact lease、MCP runtime state。

## 8. 单个测试的取舍标准

**保留**须至少保护一项：durable 数据完整性、Provider wire 正确性、安全/授权/路径边界、用户可见关键行为、并发/取消/恢复/事务/资源所有权、曾真实发生且易回归的输入边界、明确的跨版本兼容；且观察稳定结果而非私有实现、失败能说明破坏了哪条契约、与其他测试无完全相同保护范围、能确定运行。短测试不自动低价值。

**删除**须满足至少一项：对应能力已物理退休；相同语义已被更靠近 owner、诊断更好的测试完整覆盖；类型系统/DB 约束已使该错误无法构造；只检查私有函数/字段名/文件位置/exact source snippet；无可导致业务失败的断言；只是模板；重复验证框架自身；依赖已被正式 V3 路径替代的旧 fixture。
**不得**因文件小/大、测试旧、migration 版本旧、跑得慢但验真实 Android/Room 契约、"看起来不会再改"而单独删除。

## 9. 验证命令

- Windows 用 `gradlew.bat`，macOS/Linux 用 `./gradlew`；本仓库串行运行：`--no-parallel --max-workers=1`。
- 定向验证：`gradlew :app:testDebugUnitTest --tests "<FQCN>"`（或 `:ai:` 等对应 module）。
- 架构或跨模块变更的完整门禁：
  `gradlew test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`。
- 设备/DB migration/Compose instrumentation/真实系统集成用 `connectedDebugAndroidTest` 及对应真机/模拟器场景；构建或 JVM 通过不等于设备验收通过。
- 无 ignored/quarantined critical test，无永久 retry。
