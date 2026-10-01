# 测试策略与验证边界

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
- 测试类以 `*Test` 结尾；名称使用领域术语（Turn/Step/Tool/Interaction/Execution/Result），不用批次或事故编号。

## 3. 契约归属与覆盖选择

一项语义先找最近的生产 owner，由它的行为测试锁定成功、拒绝、失败、取消和恢复。跨 owner 的提交与补偿再用集成测试观察真实边界；依赖 Android SQLite、系统组件、Compose 或硬件的行为必须由对应设备测试证明。不要为了“每层都有一个测试”复制同一断言，也不要用静态源码扫描代替运行行为。

`JavascriptToolTest` 通过真实桌面 QuickJS native runtime 验证执行截止时间、父取消、格式化陷阱及输出限额，`JavascriptRuntimeAndroidTest` 验证 Android JNI 的中断与关闭后再次执行；前者不能代替双 ABI、Release R8 或设备证据。`SettingsStartupTest` 使用真实 DataStore 文件和 migration 验证损坏/失败后保全与重试，并用可控数据源验证同一 SettingsStore 的 observer 归属；`ApplicationRecoveryCoordinatorTest` 验证恢复门禁顺序、取消和 retry。

| 契约 | 最近的测试 owner | 必要的跨边界证据 |
| --- | --- | --- |
| Conversation 树、Turn/Step/Tool 状态与事务发布 | `ConversationTransitionTest`、`TurnTransitionTest`、`ConversationCommandCoordinatorTest`、`TurnCommitterTest` | 真实 Room/恢复测试核验 schema、事务、分支与失败重试 |
| Provider 请求、流式解析、opaque 回放与 usage | `RequestAssemblerTest`、各 Adapter 的 serializer/parser 测试、`RequestUsageReducerTest` | 本机 HTTP/SSE fixture 验证传输终态；真实服务另行验收 |
| Settings、按域解析与企业 Session | `SettingsStore`/`ConfigurationResolver`/`EnterpriseSessionController` 的行为测试 | DataStore/Applied 重开、Core 合同样例、旧 Session 与撤权竞态 |
| Artifact、GeneratedMedia、备份与 migration | 各 Store、`BackupArchiveService`、显式 migration 测试 | Android SQLite 的 schema/数据保全、文件交换和恢复测试 |
| MCP、Workspace、Speech、子助手 | 各自 runtime/coordinator 的状态与失败测试 | 实际 SDK、进程、系统服务或设备场景按风险补足 |
| Compose 页面与授权后的用户路径 | ViewModel 投影测试 | 正式页面 instrumentation；不能以节点存在代替可见、可点或可完成 |

`app/src/test/.../pilot/architecture/` 的静态契约只锁 owner、依赖方向和已移除的接口。`ArchitectureDependencyTest`、`RetiredSurfaceContractTest`、`TurnStepProtocolContractTest` 是该层的入口；运行语义仍由最近 owner 的行为测试证明。新增测试前先核对现有覆盖，避免把同一规则散落到多个实现细节测试中。

## 4. Provider contract suite（两层）

配置选择分别由 application service、UiModel、Compose 和 Store 测试验证提交、投影、交互与持久化失败；UI 注入的提交失败不能冒充设备磁盘写入失败。

- **Provider-independent**：请求装配、内容顺序、`Tool.stepId`、历史选择与 receipt 归共享模型和 app 测试；媒体输入由对应 transformer 测试。
- **Adapter-specific**：每个 adapter 分开验证自身请求序列化、响应/usage 解析和必要的 replay 往返；endpoint profile 不混入 parser。
- **共享 fixture**：`ProviderRequestContractFixtures.kt` 提供相同的多轮工具输入，各 adapter 分别断言 wire，不建立复杂 abstract base test。
- **传输边界**：跨 chunk 参数和 reasoning 合并归 `StepOutputAccumulator`；adapter parser 验证 wire 投影，本进程 HTTP/SSE 测试验证 listener 的 EOF、终态、重复 ID 与交错调用槽。

## 5. 架构契约测试

`app/src/test/.../pilot/architecture/` 用源码扫描（非运行时）锁定 owner 与已移除的接口，共享扫描器 `ArchitectureSources.kt`
（`architectureSourceRoot`/`architectureSources`/`sourcesUnder`/`hits`/`assertNoHits`）：

- `ArchitectureDependencyTest`：分层与所有权——UI 只经 application/query ports（包含 ArtifactStore/ToolOutputStore 禁令）、durable 写入单一 caller graph、runtime 加载受限、标题 CAS、MCP query 边界、`ArtifactDAO` 单 owner。
- `RetiredSurfaceContractTest`：已移除的兼容接口不得回归（`ToolSetRunMode`、`runCatching` 吞取消、`@Deprecated` 转发、`getKoin` 服务定位器、已移除的 MCP/master 文件）。
- `TurnStepProtocolContractTest`：只锁 turn owner 不得回引 Workspace output 路径、恢复与生命周期不得消费 render overlay。UI 依赖归 ArchitectureDependencyTest；旧 generation Job 符号归 RetiredSurfaceContractTest，不在两处重复扫描；不以符号或代码行顺序冒充运行行为验证。

架构契约测试只锁定 owner、依赖方向与已移除的符号，**不**用 exact source snippet 代替运行时/UI 行为测试；
具体 Compose 渲染与内联运行时逻辑归各自行为测试。durable 只读 snapshot 与 UI import 边界并入 `ArchitectureDependencyTest`；
checkpoint 写放大是行为事实，归 `service/turn/TurnPersistenceDeltaTest`（非源码扫描）。

这些测试断言的是**架构不变量**，失败信息须定位到 owner 与被破坏的约定。

## 6. 关键不变量的测试锚点

三条不可打折的不变量各有明确锚点：

- **工具输出滚动裁剪（含所有工具与 MCP 工具）**：`ToolOutputCompactionPlannerTest` 锁 eligibility（只压缩本次成功请求确实可见、已消费的历史 inline tool result）、`ToolOutputPolicy` 三形态（archiving/folding/PRESERVE）不混用、保护窗口与净回收阈值；阈值唯一来源 `ContextBudget`。`ToolOutputProtocolTest` 锁 marker 与协议上限。
- **Prompt cache 前缀稳定**：`ClaudeProviderPromptCacheTest` 锁 cache_control 断点；compaction 只在预算触发时改写已消费历史，保护窗口内最近批次/最近 token 不参与——前缀失效是预算触发的预期代价，不得靠"不压缩"换取。
- **工具审批语义与子助手 `ask_user`**：`ToolApprovalReducerTest`/`ToolCallRuntimeTest` 锁交互 gate，`TurnInteractionContinuationIntegrationTest` 验证真实 Room 的 continuation；`SubAssistantRunCoordinatorTest` 保护 Child 编排与中断终结。mock Runner 的委托测试只证明入口调用，不代表 Parent/Child 完整执行链或界面绑定已经验收。

## 7. 确定性、竞态、取消与所有权规则

Portal、企业退出和平台接入按同一证据边界分层：

| 边界 | 应验证的事实 | 不能据此宣称 |
| --- | --- | --- |
| JVM 状态与本机 HTTP | 文档/Session 绑定、CLOSING 屏障、迟到结果、媒体额度与回交、一次性兑换、Snapshot 校验、刷新与退出重试 | Android WebView、硬件或真实 Core 已验收 |
| 真实 Store/Room 与受控竞态 | 企业退出后的主/子运行、lease、终态及清理恢复；到期查询返回前复验原 Session，保留其他主体数据 | 正式页面或远端服务行为已验收 |
| Android instrumentation | WebView detach 与迟到请求、媒体采集和释放、AndroidKeyStore、Applied manifest 重开 | 真实平台、外链、权限弹窗及后台完整用户路径已验收 |
| 显式 live 场景 | 新建的一次性 Enrollment、真实 Core/Provider、已发布模型和媒体的跨端路径 | 未执行的资源、声学输入、Realtime ASR、remote Portal 或 Release 首次接入已验收 |

离线 Core fixture 保护十项可选默认、模型引用闭包、generation 和拒绝语义；真实平台结果要记录所用 Core/Provider 身份与场景。失败注入必须观察已提交事实，不能让返回空集合的替身掩盖退出、清理或恢复错误。

企业和聊天恢复的行为入口为 `RealmAccessTest`、`EnterpriseExitServiceTest`、`PlatformSessionNetworkTest`、
`RemoteWorkspaceServiceTest`、`ConversationQueryServiceTest` 与 `ChatPageLifecycleTest`。应分别验证旧到期事件及失败重试
不会关闭已续期 Session、时钟回拨后仍最终到期、access token 拒绝与 refresh credential 终态的区别、不可重放输出和写入、
仍有权限的读失败保留原异常与输入并只重订阅读取，以及最近聊天记录失败不撤销页面或丢失导入。真实撤权仍必须关闭原 lease，
取消不能显示为失败；Mock HTTP 和 JVM 错误注入不代替真实 Core/Agent Space 或页面交互验收。

- **禁止 wall-clock 等待**：不用 `Thread.sleep`、固定 `delay` 后猜状态、轮询到 timeout。用 `runTest`、`CompletableDeferred`、`Channel`、`Mutex` barrier、`TestCoroutineScheduler`、`advanceUntilIdle`。
- 真实平台的负行为测试可保留明确的观察窗口（如暂停期间不得开始播放）；窗口只观察该时间段的禁止行为，不能用它猜测合成或 collector 已完成。完成与顺序断言等待真实可观测状态，跨线程记录用 StateFlow/Channel 交接。
- **竞态测试必须有显式交接点**：说明它控制的 barrier（START commit 前/后、Provider 首输出前、response 后 pending checkpoint 前、Tool STARTED commit 后 side effect 前、result commit 后 Artifact publish 前、terminal commit 中），不依赖调度器"碰巧"切换。
- **取消原因 first-wins**：覆盖 user stop、superseded、parent cancelled、policy revoked、process restart、provider failure 与 cancel 并发；只测一次最终 durable outcome，UI presentation 不重复推导 terminal truth。
- **资源所有权**：Artifact/Tool resource 测试观察 lease 状态机（unpublished→checkpointed→published→discarded/retained），不能只验文件"存在/不存在"。
- **Mock vs Fake**：核心状态机禁止大面积 `relaxed = true`。Mock 适合单次 leaf call、Android framework adapter、无状态查询 port；Fake 适合 Provider stream、Tool execution、commit protocol、Repository transaction、Artifact lease、MCP runtime state。

## 8. 单个测试的取舍标准

**保留**须至少保护一项：durable 数据完整性、Provider wire 正确性、安全/授权/路径边界、用户可见关键行为、并发/取消/恢复/事务/资源所有权、曾真实发生且易回归的输入边界、明确的跨版本兼容；且观察稳定结果而非私有实现、失败能说明破坏了哪条契约、与其他测试无完全相同保护范围、能确定运行。短测试不自动低价值。

**删除**须满足至少一项：对应能力已从实现中删除；相同语义已被更靠近 owner、诊断更好的测试完整覆盖；类型系统/DB 约束已使该错误无法构造；只检查私有函数/字段名/文件位置/exact source snippet；无可导致业务失败的断言；只是模板；重复验证框架自身；依赖已被当前执行路径替代的旧 fixture。
**不得**因文件小/大、测试旧、migration 版本旧、跑得慢但验真实 Android/Room 契约、"看起来不会再改"而单独删除。

## 9. 验证命令

- Windows 用 `gradlew.bat`，macOS/Linux 用 `./gradlew`；本仓库串行运行：`--no-parallel --max-workers=1`。
- 定向验证：`gradlew.bat :app:testDebugUnitTest --tests "<FQCN>" --no-parallel --max-workers=1`（或 `:ai:` 等对应 module）。
- 架构或跨模块变更的完整门禁：
  `gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`。
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

## 11. 上下文与 Starter 的验证入口

以下是测试定位表，不是执行结果。测试实现存在、JVM 通过、设备通过和真实服务通过必须分别记录。

| 契约 | 主要测试入口 | 必须观察的边界 |
| --- | --- | --- |
| 请求冻结、状态对账与重试 | `TurnRequestAdmissionTest`、`ConversationDisclosureReconciliationTest`、`TimeReminderTransformerTest` | 自身成功工具效果、外部变化、缺失状态恢复、完整状态大小、时间来源与重试输入不变 |
| 上下文存储与历史查看 | `ConversationContextPayloadTest`、`ConversationContextPruneTest`、`ConversationContextQueryTest`、`ConversationContextPresentationTest` | 原文保全、分支/variant 适用性、无 admission 的历史、迟到结果拒绝和按需读取 |
| 开场选择与首发 | `StarterOpeningSelectionTest`、`StarterOpeningConcurrencyTest`、`ConversationPageAccessTest` | 完整定义比较、原发布身份、选择 CAS、精确补偿、v4 清除旧绑定 |
| 平台合同与 Applied | `PlatformContractSourceTest`、`PlatformCoreStarterContractTest`、`PlatformWireTest`、`PlatformSessionNetworkTest`、`EnterpriseStarterAppliedStoreTest` | Core 导出来源、v4/v5 严格消费、ETag/304、拒绝后保全及文件重开 |
| Android 持久化与输入所有权 | `EnterpriseStarterPersistenceAndroidTest`、`StarterSubmissionOwnershipAndroidTest` | 真实 Keystore/AtomicFile、Room、附件归还、提交中取消与恢复 |
| 实际聊天与详情 | `ChatContextFlowAndroidTest`、`ChatDocumentContextFlowAndroidTest`、`ConversationContextAndroidTest`、`StarterEntryFlowAndroidTest`、`StarterV5ChatFlowAndroidTest` | 实际发送输入、更新位置、原文按需查询、三个入口、首发和 Activity 重开；明确各用例的 Mock 边界 |

Core Snapshot、manifest 与共享 cases 的来源由 [配置架构](android-configuration-architecture.md)维护。
`contracts/runtime/managed-snapshot-required.json` 来自 platform-core 同名 Problem fixture，
供平台 Runtime/MCP 测试消费；Android 自有 Mock 不成为跨端合同的权威来源。

Activity 重开不等于数据库关闭后重开或进程重启。fixture 的 Repository ACK 不能代替落盘证据，
确定性 HTTP adapter 也不能代替真实 Provider 成功响应。设备测试等待可观测条件，不能用固定延迟、
自动重试或放宽断言隐藏失败；一次失败后重跑通过只证明该次重跑通过。

## 12. 设备与显式集成场景

旧客户端与当前客户端的 Core 兼容探针由 [兼容性验证工具](../../tools/compatibility/README.md)维护。
`PlatformSnapshotCompatibilityLiveAndroidTest` 验证实际发布身份、支持版本、拒绝后的 Applied/历史保全与同 Session 恢复；
历史 APK 使用固定提交和独立探针，不能换用当前 DTO 后声称验证旧客户端。版本故障注入不冒充 Core 正式发布。

`connectedDebugAndroidTest` 使用独立测试 AVD，并以 `ANDROID_SERIAL` 明确目标。AGP 安装/卸载可能清理
Debug 包私有数据，测试的 finally 无法保护包级卸载；不得对保存日常或生产演示数据的设备直接运行。
先完成普通设备门禁，再为需要已接入状态的 live 用例采用保留数据的安装与直接 instrumentation。

| 场景 | 启用参数与前提 | 证据限制 |
| --- | --- | --- |
| Starter v5 隔离 Mock | `starterV5MockLive=true`；专用且从未绑定企业的设备 | 实际 UI、Applied、Session、Room 与 adapter；HTTP 为 Mock |
| Starter v5 Core 联调 | `starterV5CoreLive=true` 与 `coreStarterInput` | Core 提供真实 Snapshot、目标 Starter、临时接入资料；分别记录确定性 adapter 或实际供应商环境；输入文件读取后删除 |
| 已接入平台上下文 | `platformContextLive=true`；可选 `platformContextModelSwitch=true` | 使用当前发布资源；记录 release/hash、实际版本及 Provider 结果，v4 不能证明 v5 opening |
| 远程文件真实链路 | `remoteWorkspaceInput` 指向专用设备应用 cache 内 JSON，包含 `enrollment` 字符串；可选 `lifecycle: true`、`expectedMcpAvailable`（默认 false，必须与隔离用户实际投影一致）、`configurationPublished`（默认 false） | `RemoteWorkspaceLiveAndroidTest` 要求未接入测试设备和独立 Core/Agent Space；输入读取后删除。通过真实 enrollment owner 接入；未发布配置环境要求 `activeManagedGeneration=0`，断言 Pending 且无 Applied 并验证文件读写，已发布配置环境使用 `configurationPublished: true` 显式同步后断言 READY 和 Applied，不能把初始化或显式同步后的 READY 记作无 Applied。单独断言实际 MCP 投影，不更改 Core 的全局发布。验证 64 MiB 摘要往返、BOM/正文标记/CRLF、创建及复制移动条件写入、覆盖确认竞态、双编辑者冲突与另存、慢下载取消和切域关闭、原生预览、编码 Markdown 相对图片、真实目录选择对账。生命周期模式在 cache 写出 `remote-workspace-step.txt`，主机按 disconnect、restore、files、replace 对专属测试用户执行管理动作，完成后先写同目录临时文件，再原子重命名为对应 `remote-workspace-<step>.done`（内容 `ok`），避免设备看见尚未写完的回执；验证恢复时文件资格和替换空间后的旧句柄读写拒绝；最后按输入断言已发布配置保持 READY 或未发布配置仍 Pending。普通设备门禁仍条件跳过，不能替代显式真实服务联调 |
| Linux Rootfs | `prootRootfsUrl` 指向匹配 ABI 的已核验镜像 | `WorkspaceProotAndroidTest`；系统 shell 的 native PTY 测试不证明 PRoot；4 KB/16 KB 与 arm64/x86_64 分别记账 |
| HTTP ASR 完整上传 | `httpAsrLiveAudio=true`；持续有效麦克风输入 | `HttpAsrLifecycleInstrumentedTest` 的上传/上传中取消；模拟器底噪不满足有效信号检查，跳过不能记为上传成功 |

参数通过带引号的 `'-Pandroid.testInstrumentationRunnerArguments.<name>=<value>'` 传入 Gradle，避免 PowerShell
拆分。Rootfs 场景使用独立临时 Workspace，缺 fixture 明确跳过；ASR 的录音中取消与准入撤销仍可独立验证。

IME 用例要求实际非零 inset 的停靠软键盘，CI 关闭硬键盘；未出现键盘不能转为跳过或修改生产输入配置。
临时系统设置必须在 finally 恢复。Compose 测试中的持锁 IO 使用后台交接和有界条件等待，不嵌套
`runBlocking` 阻断持锁协程；UI 操作与等待应遵循当前 Compose 测试调度器。

每次交付保留对应源码/构建身份、测试报告、失败记录及跳过原因，设备、声学输入、真实服务和生产环境
分别说明。参考文档只维护如何验证，不累计历史通过数量或把旧 APK 的结果写成当前验收。
