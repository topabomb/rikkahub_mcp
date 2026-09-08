# 测试策略 Testing Strategy

本文描述本仓库**当前**的测试分层、所有权与稳定性约定，是新增/审查测试时的稳定依据。
它不记录迁移过程，只描述长期成立的规则。生产语义与参考文档冲突时以代码为准。

## 1. 分层（按运行位置与真实依赖选择）

| 层 | 运行位置 | 覆盖 | 约束 |
| --- | --- | --- | --- |
| L1 纯协议/纯函数 | JVM | `ConversationTransition`、`TurnTransition` 的 transcript/step 不变量、`ToolResultStatus`/交互状态、Request Context 选择、`ContextBudget`、compaction plan、sub-assistant policy/lineage/projection、Provider-independent replay projection、字符串/路径/解析算法 | 不需 Android Context；不 mock Room；不启动协程 Job（除非被测即协程协议）；table-driven；关键状态使用确定性时间与 ID |
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
| Turn/Step/Tool 状态转换与 transcript 不变量 | `TurnTransitionTest` |
| 执行链 owner、依赖与退休面 | `ArchitectureDependencyTest`、`RetiredSurfaceContractTest`、`TurnStepProtocolContractTest`（静态检查） |
| commit-then-publish 与命令锁 | `ConversationCommandCoordinatorTest` |
| active Turn session 与 live phase | `ConversationRuntimeTest` |
| 辅助生成任务、原 Session 与摘要取消 | `AuxiliaryGenerationOwnershipTest` 使用实际生成与应用服务、延迟 Provider 验证取消等待、旧 Session、切域、手动标题、摘要原树校验和提交后取消；`ConversationRepositoryTreeIntegrationTest` 在真实 Room 注入 Child 删除失败，验证整个摘要树事务回滚及重试 |
| 原生切域与 Portal 清理 | `EnterpriseApplicationServiceTest` 用真实 Session/store 和可控宿主回执验证发布屏障、进度与原选择/目标 Session；`PortalDocumentTest` 验证原请求、宿主超时及重开准入；`PortalWebViewAndroidTest` 验证系统 Cookie/站点存储清理和真实网页消息，`EnterprisePageAndroidTest` 验证原生页面接线与后台交接取消 |
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
| V3 payload 结构与旧 payload 转换 | `V3TranscriptValidatorTest`、`LegacyTurnTranscriptMigratorTest` |
| Room V3 migration / backup | `Migration_10_11Test`、backup restore instrumentation |
| 域身份持久化 / 主子一致性 | `Migration_11_12Test`、`ConfigurationScopePersistenceTest`、`ConversationRepositoryTreeIntegrationTest`、`ConversationTransitionTest`；不替代查询授权、文件隔离与企业 UI 验收 |
| 运行记忆域与 Session | `RealmAccessTest`、`MemoryServiceTest` 与真实 Room 的 `ScopedMemoryRepositoryAndroidTest`；验证旧 Session、模式变化、工具结果归属、事务取消及个人助手清理范围 |
| 会话目录、FTS 与统计域过滤 | `ScopedConversationQueryTest`、`SelectedRealmPagingSourceTest` 验证原 Session 工具、列表恢复及实际 Pager 失效；`ConversationDAOIntegrationTest` 和使用生产数据库工厂的 `ScopedMessageSearchAndroidTest` 验证真实 Room/Requery/Jieba 的完整主体过滤与限额前过滤，不替代按 ID 页面/命令授权验收 |
| 聊天页面打开与生命周期 | `ConversationPageAccessTest` 验证显式 Draft/Existing、原 Session、header 前置检查与投影撤销；`ChatPageLifecycleTest` 验证实际 ViewModel 的授权先行、取消/回收、分享输入消费；`UserSettingsMigrationAndroidTest` 验证实际 DataStore/SharedPreferences 的最近聊天迁移、失败重试和保全；不替代普通命令/Turn 或正式企业 UI 验收 |
| Portal 消息与文档授权 | `PortalProtocolTest` 核验完整共享输入摘要，执行全部 BridgeRequest 案例和原始重复键；`PortalDocumentTest` 使用真实本地 Session/Feed owner 验证原文档、ETag、配置同步、切域往返、文档替换及迟到同步结果；`PortalWebViewAndroidTest` 覆盖随包网页首次读取、实际 bootstrap、页内导航、重载后新页面、快速切域和旧同步错误隔离。设备验证结果见实施方案；请求解析不代表共享响应反例全部消费，页面测试不替代媒体、扫码或真实平台验收 |

## 4. Provider contract suite（两层）

- **Provider-independent**：`RequestAssemblerTest` 保护 `UIMessage → ModelRequestMessage` 转换、Step 丢弃和 tool call/result identity；`MessageTest` 保护 terminal safe prefix 与空结果/失败媒体的投影区别，`ProviderMessageUtilsTest` 用矩阵保护内容顺序与 `Tool.stepId` 批次边界。`RequestContextPlannerTest` 保护 history/Disclosure 选择与 receipt，媒体输入投影由相应 Input Transformer 测试负责。
- **Adapter-specific**：每个 adapter 只验自身 wire。请求序列化与响应解析**分开**：
  - `ChatCompletionsAPISerializerTest` / `ChatCompletionsAPIParserTest`
  - `ResponseAPISerializerTest` / `ResponseAPIParserTest`
  - `ClaudeProviderMessageTest`、`GoogleProviderMessageTest`（wire 构建与必要的 parse/replay 往返）
  - usage parser 独立（`ChatCompletionsAPIUsageTest` 等），endpoint profile 不混入 parser。
- **共享 fixture**：`ai/.../testsupport/ProviderRequestContractFixtures.kt` 提供 `executedTool()` 与 `canonicalMultiRoundToolTurn()` 等 canonical 输入；各 adapter 显式调用同一输入、断言各自 wire。common case 只维护一次，不建复杂 abstract base test。
- 跨 chunk 参数拼接、reasoning 合并归 app `StepOutputAccumulator`，adapter parser 锁 transport slot 与 wire 字段投影；`ProviderStreamContractTest` 使用本进程 SSE response body 验证真实 listener 的终态证据、EOF、重复 ID 与交错调用槽。

## 5. 架构契约测试

`app/src/test/.../pilot/architecture/` 用源码扫描（非运行时）锁定 owner 与退休面，共享扫描器 `ArchitectureSources.kt`
（`architectureSourceRoot`/`architectureSources`/`sourcesUnder`/`hits`/`assertNoHits`）：

- `ArchitectureDependencyTest`：分层与所有权——UI 只经 application/query ports（包含 ArtifactStore/ToolOutputStore 禁令）、durable 写入单一 caller graph、runtime 加载受限、标题 CAS、MCP query 边界、`ArtifactDAO` 单 owner。
- `RetiredSurfaceContractTest`：已退休兼容面不得回归（`ToolSetRunMode`、`runCatching` 吞取消、`@Deprecated` 转发、`getKoin` 服务定位器、退休 MCP/master 文件）。
- `TurnStepProtocolContractTest`：只锁 turn owner 不得回引 Workspace output 路径、恢复与生命周期不得消费 render overlay。UI 依赖归 ArchitectureDependencyTest；旧 generation Job 符号归 RetiredSurfaceContractTest，不在两处重复扫描；不以符号或代码行顺序冒充运行行为验证。

架构契约测试只锁定 owner、依赖方向与退休符号，**不**用 exact source snippet 代替运行时/UI 行为测试；
具体 Compose 渲染与内联运行时逻辑归各自行为测试。durable 只读 snapshot 与 UI import 边界并入 `ArchitectureDependencyTest`；
checkpoint 写放大是行为事实，归 `service/turn/TurnPersistenceDeltaTest`（非源码扫描）。

这些测试断言的是**架构不变量**，失败信息须定位到 owner 与被破坏的约定。

## 6. 关键不变量的测试锚点

三条不可打折的不变量各有明确锚点：

- **工具输出滚动裁剪（含所有工具与 MCP 工具）**：`ToolOutputCompactionPlannerTest` 锁 eligibility（只压缩本次成功请求确实可见、已消费的历史 inline tool result）、`ToolOutputPolicy` 三形态（archiving/folding/PRESERVE）不混用、保护窗口与净回收阈值；阈值唯一来源 `ContextBudget`。`ToolOutputProtocolTest` 锁 marker 与协议上限。
- **Prompt cache 前缀稳定**：`ClaudeProviderPromptCacheTest` 锁 cache_control 断点；compaction 只在预算触发时改写已消费历史，保护窗口内最近批次/最近 token 不参与——前缀失效是预算触发的预期代价，不得靠"不压缩"换取。
- **工具审批语义与子助手 `ask_user`**：`ToolApprovalReducerTest`/`ToolCallRuntimeTest` 锁交互 gate，`TurnInteractionContinuationIntegrationTest` 验证真实 Room 的 continuation；`SubAssistantRunCoordinatorTest` 保护 Child 编排与中断收口。mock Runner 的委托测试只证明入口调用，不代表 Parent/Child 完整执行链或界面绑定已经验收。

## 7. 确定性、竞态、取消与所有权规则

`PortalDocumentTest` 使用真实 Session、Registry 和退出 service 验证退出等待原网页请求收尾、CLOSING 拒绝新文档、旧 Session 不关闭新登录文档；两份文档的关闭测试以显式门阻挡第二份请求，验证首份清理失败不能提前结束全体等待，失败可重试且 UI 通知异常不阻塞退出。`PortalWebViewAndroidTest` 消费实际 Portal 包和 Android WebView，核实导航关闭通知携带原文档原因且发生在 detach 后，并通过 `awaitClosed` 等待迟到请求。

网页原生操作同样由 `PortalDocumentTest` 验证：真实退出 owner 在取消发起请求后继续完成，拒绝/超时/旧提示不能执行外部操作，固定时钟推进超过文档期限时即使关闭定时任务未执行也拒绝确认。`PortalWebViewAndroidTest` 的网页退出用随包页面触发真实 Bridge、Compose 原生确认、Session/store/Exit owner；只替换会话运行停止 port，分别核实取消保留登录及确认后关闭并退出，不以这一用例替代真实会话运行停止的独立集成测试。

`PortalMediaStoreTest` 使用真实临时文件和注入时钟验证文档归属、预留额度、格式与分块、五分钟期限、仍在写入的文件保留、取消回交、删除失败后的额度/所有权和重试。大 MP4 brand 表只验证容器扫描的有界实现与发布，不替代音频解码或硬件采集测试。该组件尚未接入 Portal 的媒体请求，设备权限、拍摄、录音和生命周期清理仍须由实际消费者验收。

企业退出由 `EnterpriseExitServiceTest` 验证调用者取消、重复请求、到期准入写盘失败、清理失败及重试；坏企业 manifest 使用真实恢复编排验证个人启动不被阻断。`ConversationCommandAccessTest` 使用真实会话 owner 验证按域停止、辅助任务等待、终态提交失败与空闲 Runtime 淘汰竞态。`SubAssistantTurnIntegrationTest` 在真实主/子 Runner 链的 Child 创建、父 link 提交及 Child START 提交窗口触发真实退出，验证原 Session 拒绝迟到 START、lease 释放、存留 Child 的完整 link 及子运行终态失败重试；IO double 从成功提交记录归并最新事实，不能以默认空集合绕过退出核验。`EnterpriseSessionControllerTest` 与设备上的 `EnterpriseAppliedStateAndroidTest` 验证退出原因、CLOSING 重开及当前磁盘 manifest 重开保全及旧原型版本拒绝；`ConversationRepositoryTreeIntegrationTest` 验证真实 Room 的未完成主/子运行按完整主体计数。这些测试不代表正式页面、媒体或真实平台退出已验收。

- **禁止 wall-clock 等待**：不用 `Thread.sleep`、固定 `delay` 后猜状态、轮询到 timeout。用 `runTest`、`CompletableDeferred`、`Channel`、`Mutex` barrier、`TestCoroutineScheduler`、`advanceUntilIdle`。
- 真实平台的负行为测试可保留明确的观察窗口（如暂停期间不得开始播放）；窗口只观察该时间段的禁止行为，不能用它猜测合成或 collector 已完成。完成与顺序断言等待真实可观测状态，跨线程记录用 StateFlow/Channel 交接。
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

## 10. CI 与性能证据

`.github/workflows/verify.yml` 在 PR、主分支 push 和手动运行时执行全模块 JVM、Debug/Release 构建、lint、diff 检查和 Room schema 生成一致性检查，再在 Android 模拟器运行完整 `connectedDebugAndroidTest`。设备门禁适用于所有 PR，包含数据库、Runtime、Artifact、子助手和 UI 变更；失败保留测试报告，不自动 retry。`release.yml` 复用该门禁，成功后才进入签名与发布。

性能入口复用 `:app:baselineprofile:connectedBenchmarkReleaseAndroidTest`：`StartupBenchmarks` 测量冷启动；`TurnWorkloadBenchmarks` 使用 AndroidX Macrobenchmark 的 `TurnWorkloadMetric`（`TraceMetric`），通过真实生产 owner 执行以下场景：

- 10,000 次活跃 Assistant chunk 合并。
- 1,000 条历史的请求规划与装配。
- 100 个大型 Tool Result 的 rolling compaction 规划。
- 50 个 Tool schema 冻结与请求预算计算。
- 1,000 个含大型 legacy transcript 的真实 Room 迁移，使用导出的 v10 schema 建立隔离数据库。
- 100 MB 工具输出经真实 ArtifactStore/ToolOutputStore 的 read 与 grep。
- 1,000 条历史下的 100 次活跃 Assistant 更新，通过真实 presentation 与 ChatMessage 渲染，并采集 `FrameTimingMetric`。

输入构造在 Trace 测量段外，使用完整编译并重复采样。`measureWorkload` 是同步 workload 唯一测量边界：Perfetto slice 记录耗时，边界前后的 ART `art.gc.bytes-allocated` 差值记录近似 Java/Kotlin 分配字节；Compose 从首帧完成后到第 100 次更新完成记录同口径分配。该平台统计是进程范围、近似且可能延迟，包含区间内其他线程与测量开销，不是精确对象计数、native allocation、存活堆或峰值内存。缺少平台统计直接失败，不记为零。驱动 Activity、数据库与 payload 隔离 fixture 仅在 `app/src/benchmarkRelease`，不会进入正式 Debug/Release APK；不用 synthetic legacy implementation 作为对照。

在已连接设备上定向执行：

```text
gradlew :app:baselineprofile:connectedBenchmarkReleaseAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.TurnWorkloadBenchmarks' --no-parallel --max-workers=1
```

`TurnWorkloadMetric` 用当前迭代的目标进程查询 Perfetto：同步 workload 必须恰好有一个 slice 和一个 allocation counter；Compose 汇总所有活跃消息 slice 并读取最终 composition counter，分配 counter 仍必须恰好一个。每次冷启动采集独立 trace，不跨迭代保存计数。设备要求 API 29+（平台 `Trace.setCounter`）。

AndroidX JSON 的 `sampledMetrics` 保留每轮耗时与 `JavaAllocatedBytesApprox` 的 `runs` 原始样本，并由库写出 `P50/P90/P95/P99`；Compose 同时保留 `FrameTimingMetric` 的逐帧分布。`migratedRows` 是 SQL 验证出的迁移行数，`toolOutputInputBytes` 是扫描输入文件大小，`activeAssistantCompositions` 是活跃消息已提交的 composition 次数（含初始 composition）。文件大小不是操作系统实际 IO 字节，composition 次数不是 Compose 内部所有函数的重组次数；这些口径不能互相代替。各 measurement 可回查同次 `.perfetto-trace` 的 slice/counter。

`.github/workflows/benchmark.yml` 提供手动模拟器诊断，保留 AndroidX JSON 与 Perfetto trace。它不设性能 pass/fail 阈值。固定实机与固定系统上采集的结果才能与同环境 baseline 比较；模拟器或开发机结果不能冒充受控门禁，执行入口存在也不代表已经采集基线。

指标 API 依据：[Android Debug runtime statistics](https://developer.android.com/reference/android/os/Debug#getRuntimeStat(java.lang.String))、[AndroidX TraceMetric](https://developer.android.com/reference/androidx/benchmark/macro/TraceMetric)、[Metric.Measurement](https://developer.android.com/reference/androidx/benchmark/macro/Metric.Measurement)。

真实 IME 布局验证要求非零 inset 的停靠软键盘，CI 关闭硬键盘。`TtsControllerLayoutTest` 使用 edge-to-edge/adjustResize 窗口，并等待实际 IME 高度；测试需要临时调整系统手写设置时，只在最小授权作用域写入，任何失败均在 finally 恢复原值。键盘未出现不能转换为跳过，也不能改变生产输入配置来满足测试。
