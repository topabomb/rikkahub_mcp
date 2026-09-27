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

## 3. 契约归属与覆盖选择

一项语义先找最近的生产 owner，由它的行为测试锁定成功、拒绝、失败、取消和恢复。跨 owner 的提交与补偿再用集成测试观察真实边界；依赖 Android SQLite、系统组件、Compose 或硬件的行为必须由对应设备测试证明。不要为了“每层都有一个测试”复制同一断言，也不要用静态源码扫描代替运行行为。

| 契约 | 最近的测试 owner | 必要的跨边界证据 |
| --- | --- | --- |
| Conversation 树、Turn/Step/Tool 状态与事务发布 | `ConversationTransitionTest`、`TurnTransitionTest`、`ConversationCommandCoordinatorTest`、`TurnCommitterTest` | 真实 Room/恢复测试核验 schema、事务、分支与失败重试 |
| Provider 请求、流式解析、opaque 回放与 usage | `RequestAssemblerTest`、各 Adapter 的 serializer/parser 测试、`RequestUsageReducerTest` | 本机 HTTP/SSE fixture 验证传输终态；真实服务另行验收 |
| Settings、按域解析与企业 Session | `SettingsStore`/`ConfigurationResolver`/`EnterpriseSessionController` 的行为测试 | DataStore/Applied 重开、Core 合同样例、旧 Session 与撤权竞态 |
| Artifact、GeneratedMedia、备份与 migration | 各 Store、`BackupArchiveService`、显式 migration 测试 | Android SQLite 的 schema/数据保全、文件交换和恢复测试 |
| MCP、Workspace、Speech、子助手 | 各自 runtime/coordinator 的状态与失败测试 | 实际 SDK、进程、系统服务或设备场景按风险补足 |
| Compose 页面与授权后的用户路径 | ViewModel 投影测试 | 正式页面 instrumentation；不能以节点存在代替可见、可点或可完成 |

`app/src/test/.../pilot/architecture/` 的静态契约只锁 owner、依赖方向和退休面。`ArchitectureDependencyTest`、`RetiredSurfaceContractTest`、`TurnStepProtocolContractTest` 是该层的入口；运行语义仍由最近 owner 的行为测试证明。新增测试前先核对现有覆盖，避免把同一规则散落到多个实现细节测试中。

## 4. Provider contract suite（两层）

配置选择分别由 application service、UiModel、Compose 和 Store 测试验证提交、投影、交互与持久化失败；UI 注入的提交失败不能冒充设备磁盘写入失败。

- **Provider-independent**：请求装配、内容顺序、`Tool.stepId`、历史选择与 receipt 归共享模型和 app 测试；媒体输入由对应 transformer 测试。
- **Adapter-specific**：每个 adapter 分开验证自身请求序列化、响应/usage 解析和必要的 replay 往返；endpoint profile 不混入 parser。
- **共享 fixture**：`ProviderRequestContractFixtures.kt` 提供相同的多轮工具输入，各 adapter 分别断言 wire，不建立复杂 abstract base test。
- **传输边界**：跨 chunk 参数和 reasoning 合并归 `StepOutputAccumulator`；adapter parser 验证 wire 投影，本进程 HTTP/SSE 测试验证 listener 的 EOF、终态、重复 ID 与交错调用槽。

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

Portal、企业退出和平台接入按同一证据边界分层：

| 边界 | 应验证的事实 | 不能据此宣称 |
| --- | --- | --- |
| JVM 状态与本机 HTTP | 文档/Session 绑定、CLOSING 屏障、迟到结果、媒体额度与回交、一次性兑换、Snapshot 校验、刷新与退出重试 | Android WebView、硬件或真实 Core 已验收 |
| 真实 Store/Room 与受控竞态 | 企业退出后的主/子运行、lease、终态及清理恢复；到期查询返回前复验原 Session，保留其他主体数据 | 正式页面或远端服务行为已验收 |
| Android instrumentation | WebView detach 与迟到请求、媒体采集和释放、AndroidKeyStore、Applied manifest 重开 | 真实平台、外链、权限弹窗及后台完整用户路径已验收 |
| 显式 live 场景 | 新建的一次性 Enrollment、真实 Core/Provider、已发布模型和媒体的跨端路径 | 未执行的资源、声学输入、Realtime ASR、remote Portal 或 Release 首次接入已验收 |

离线 Core fixture 保护十项可选默认、模型引用闭包、generation 和拒绝语义；真实平台结果要记录所用 Core/Provider 身份与场景。失败注入必须观察已提交事实，不能让返回空集合的替身掩盖退出、清理或恢复错误。

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

`contracts/runtime/managed-snapshot-required.json` 原样来自 platform-core 的 `api/fixtures/problem/managed-snapshot-required.json`，由平台 Runtime/MCP 测试消费。Android 自有工具测试不宣称跨端共享样例或真实服务互操作。

Starter v4/v5 使用 Core 导出的单一 OpenAPI、manifest 和共享 cases，来源见 [配置架构](android-configuration-architecture.md)。`PlatformContractSourceTest` 验证来源摘要，`PlatformCoreStarterContractTest` 消费 Core v5 canonical opening 并核对字面内容/顺序，`PlatformWireTest` 验证各版本严格解析。`withStarterOpeningMock` 仅为独立网络错误/变化场景构造确定性输入与 Mock hash，不承担 wire schema 权威。`EnterpriseStarterAppliedStoreTest` 在真实临时文件上验证 opening 原文/顺序、历史缺失、Session Discovery `[4]` 与 revision 保全；`PlatformSessionNetworkTest` 覆盖 Bootstrap 仍支持的已知 v4/v5 缓存使用 304、未知或已撤销版本不发送 ETag、意外 304 拒绝且保全 Applied，以及 v4→v5 下载后的原子发布和重开。`EnterpriseStarterPersistenceAndroidTest` 在独立 noBackup 目录使用真实 Android Keystore 和 AtomicFile，验证发布后重开及历史文件原文不被改写。测试存在不表示设备或真实 Core 联调已经通过，须分别记录执行结果。

`StarterOpeningSelectionTest` 覆盖轻量导航、原发布来源保留、定义变更与撤销；`ConversationPageAccessTest` 以真实 runtime/command 链验证提交后 UI 拒收的精确补偿，以及旧选择 token 不能覆盖后来绑定。`StarterOpeningDetailsAndroidTest` 验证默认折叠时不读取正文、展开后的字面文本和重复展开的查询次数；不能据此代替三个完整入口、首发失败及 IME/旋转场景的实际设备验证。

`StarterEntryFlowAndroidTest` 共用真实 Applied/Keystore、Session、Application、Runtime 和 command owner，遍历 v4/v5 的空间预览、聊天快捷菜单与空白聊天卡片；验证原输入和附件保留、v4 仅填提示词、v5 绑定原定义/发布 hash，以及 Ready 再选仅追加提示词。该测试的 Repository 提交使用明确 ACK mock，落盘保全由 opening Room 与 Applied 设备测试独立覆盖，不能视作完整平台网络或全页面导航验收。

`StarterV5ChatFlowAndroidTest` 在独立且从未绑定企业的设备上显式运行：`starterV5MockLive=true` 使用本地 Mock；`starterV5CoreLive=true` 和 `coreStarterInput` 由 Core 提供实际 Snapshot、目标 Starter 与临时接入资料，可分别连接 Admin→Publish 的本地确定性 adapter harness 或 `device:real` 实际供应商环境。Core lane 经正式 HTTP 同步，复用首发/Room/详情/重开断言；确定性 adapter 独立捕获并核验请求，实际供应商场景保留真实响应与原始失败诊断，二者分开记录，不以其中一种替代另一种验收。输入文件读取后删除。Android 自有 `androidTest/assets/contracts/starter-v5-e2e.json` 标明来源与 Mock 身份；本机 HTTP 提供 discovery、接入、v5 同步及 OpenAI runtime，实际 Applied/Session、RouteActivity 空态卡片、ChatVM 首发、Room 与 Provider 适配器贯通。选择目标 Starter 时先定位可见且具有横向滚动语义的卡片列表，再滚动到标题并点击；覆盖第三张等初始屏外卡片，不把隐藏导航的 LazyRow 误作目标。验证不自动发送/建库、opening System、有序背景、原发布来源、详情、Repository 直接 Room 读回与 Activity 重开（未关闭重开 AppDatabase，也不代表进程重启验收），结束通过正常会话删除和企业退出清理本 fixture。不得在已绑定生产 Demo 的 AVD 上执行，也不通过覆盖企业文件恢复数据；普通全套设备测试明确跳过，定向运行结果单独记录，文件存在不代表整链验收通过。

`StarterSubmissionOwnershipAndroidTest` 经真实 Room、Artifact 与发送 owner 验证 Starter 更新拒绝后的附件归还、立即 GC 与刷新重试，安装前拒绝只补偿一次，提交前取消和草稿关闭，以及提交中取消/发布失败时的 durable 引用保全。发布失败保留异常类型、message 和 cause；故障注入等待真实 Repository 提交完成，不将挂起 spy 的原方法返回强转为 Boolean。`ArtifactUseCaseTest` 以确定的交错点覆盖关闭发生在归还检查后、重新插入前的 pin 释放。

`StarterOpeningConcurrencyTest` 通过配置读取 barrier 验证并发选择串行、过期 CAS 不追加文字也不覆盖绑定，以及重新同步 v4 后显式选择清除 Draft 的 v5 开场绑定；原输入与原发布定义保全。

`ConversationContextPresentationTest` 以 1000 节点和 500 条接纳的访问计数验证摘要不反复扫描分支且只输出实际变化 marker；覆盖 11 请求一次变化、多次变化按 Step 归属、跨 Turn 沿用无新 marker、混合原因只展示 EXTERNAL 但保留原文，以及 selected variant/streaming 稳定性。恢复与初始不产生通用入口；preset/summary 来源仍沿原投影。`ConversationContextQueryTest` 保留授权目录、按需 Artifact 原文、关闭/分支切换迟到结果拒绝和诊断/取消边界。`ConversationContextAndroidTest` 验证所选请求变化正文、原文/技术来源的独立折叠、关闭取消读取、失败重试与新请求不抢阅读；窄屏、大字体、IME 下核对短标签左对齐、紧凑间距与点击区域。`ChatMessageCotTest` 断言只有通知 Step 分组、标签在完整工具结果后及受影响输出前，普通 Step 连续折叠不变。`SubAssistantDetailPageAndroidTest` 核对子助手在实际消息 Step 展示通知、请求区无聚合入口，以及无正文终态与后续输出的边界稳定。`ChatInputStateAndroidTest` 保留持久提交后清理、迟到回调、同文件重选和编辑输入保全。测试存在不代表已运行，设备结果单独记账。

`ConfigurationFeedbackTest` 区分下一次发送（START）生效的配置与立即呈现的视觉配置，避免背景或视觉正则显示延迟生效。`PromptPageAndroidTest` 通过实际编辑弹层切换位置、修改深度和提交，验证 role 保留、条件显隐与 200dp 正文编辑区不随标签变更而改变；既有 `AssistantPromptPageAndroidTest` 继续验证后台更新后提交使用原编辑基线。

`AdaptiveModalFeedbackAndroidTest` 验证嵌套配置模态只有活动 host 显示一条反馈，父内容不会因暂时隐藏反馈而重建。`ConversationCommandAccessTest` 通过原 worker 清理 barrier 区分接受请求与 Append 已提交：等待期间退出并重新登录不补写旧请求，多次替换必须等待全部原清理且只有最终请求取得 durable receipt。

`TurnWorkloadBenchmarks.renderHundredActiveAssistantUpdatesWithContext` 使用独立 `compose_context_100` 场景：1000 个节点、26 组有效 System/披露与接纳记录，生产 `ConversationPresentationProjector` 和真实 `ChatMessage` 的轻量摘要；仅最后助手出现一条外部更新，100 次流式更新期间正文保持折叠。沿用原 Trace、分配与 frame 指标；原 `compose_100` 场景保留。两者 fixture 的历史角色结构不同，不能把耗时差简单当作上下文功能开销；该场景也不代表详情正文展开、查询或真实模型网络成本。

`ChatContextFlowAndroidTest` 经真实 `RouteActivity`、Settings/Memory/Conversation owner、Room 和 OpenAI 适配器驱动连续三次发送及工具续步；HTTP 仅连接本机 Mock。首请求等待时从原选择器切模型，通过 owner 改 System、提示规则、Memory 和子助手目录；两次记忆写入与一次助手管理沿正式审批执行，验证同 Turn 模型/System/规则固定、完整工具结果先于外部变化、自身操作不额外注入。下一 START 采用新配置，聊天扩展中关闭规则后第三 START 不再含该规则；核对实际 Step 标签、该次变化详情及原文，后续请求不重复展示。测试截图优先保存在 instrumentation `additionalTestOutputDir` 下的 `context-ui-evidence`，未提供参数时才使用应用外部文件目录；fixture 只创建随机身份的助手/Provider/会话，结束后恢复原选择、域与最近会话引用。`SettingsStartupTest` 验证最近会话设值后以 null 清除只影响指定域，不改变其他域或资源选择。

`ChatDocumentContextFlowAndroidTest` 从实际上传菜单导入含占位符和反引号的文档，再经 ChatVM、Room 与 Provider adapter 发送。系统文件选择器的返回 URI 和 HTTP 是 fixture，Artifact 导入与上下文接纳为真实路径。断言用户模板、文档和时间不二次渲染，DOCUMENT_TEXT/REFERENCE_ONLY 保持各自来源与实际位置，授权 query 读取的原文与 wire 一致，Activity 重开保留接纳且无外部更新行；不通过已移除菜单读取附件输入。截图与请求位于 `document-context-ui-evidence`。持久会话基线使用授权 `recentConversations` 查询，不以会先发出 loading 空列表的 Flow 首次发射当作数据库为空。

`ManagedFileCreationIntegrationTest.mountedInputThumbnailRecoversWhenRejectedSubmissionReturnsOwnership` 在缩略图仍挂载时转移并归还输入 ownership。与 Compose 预览协程争用 draft mutex 的操作经 `awaitWithCompose` 后台执行，由有界 `compose.waitUntil` 推进测试调度，结束后 `await` 传播异常；不能嵌套 `runBlocking` 阻断持锁读取协程的恢复。原实际红色像素和输入保全断言继续保留。

`PlatformContextLiveAndroidTest` 仅在显式 `platformContextLive=true` 且专用设备已接入企业时运行；普通设备门禁明确跳过。通过真实 RouteActivity 验证当前发布的 v4 Starter 仅预填、编辑后发送、真实 Provider 完成，以及授权 query 核对本次接纳原文；没有真实变化时 UI 不显示更新入口。可选 `platformContextModelSwitch=true` 通过原模型选择器切换到另一个本域已发布 CHAT 模型并再次发送，断言不额外生成状态披露。测试使用现有发布资源，不修改受管配置，保留 demo 会话并恢复临时助手/模型选择；截图和不含凭据的 release/hash、接纳数量及结果写入 instrumentation 输出目录。接入由 `PlatformEnrollmentLiveAndroidTest.enrollsForSpaceReview` 单独完成，不能把 service 接入测试声称为手工扫码 UI 验收，也不能把真实 v4 结果作为 v5 opening 证据。执行此 opt-in 流程前先完成会卸载 Debug 包的普通 connected 门禁，之后采用保留数据的安装与直接 instrumentation。
`WorkspaceTerminalAndroidTest` 的 native PTY 使用 Android 系统 shell。Linux Rootfs 的实际验收由 `WorkspaceProotAndroidTest` 单独负责，显式传 `-Pandroid.testInstrumentationRunnerArguments.prootRootfsUrl=<匹配 ABI 的已核验 Rootfs URL>` 才下载并执行；未提供 fixture 时明确跳过。该测试使用独立临时 workspace 并在结束后清理，不修改用户已有工作区。x86_64 / 4 KB 场景验证生产 Shell、文件操作、长输出、超时、取消与双 PTY；x86_64 / 16 KB 场景用同一标准 4 KB Ubuntu archive 验证明确拒绝和旧目录保全。两环境的互斥场景跳过必须分别记账，不能汇总成所有 PRoot 场景通过；arm64 仍需对应镜像与设备执行。

`connectedDebugAndroidTest` 使用独立测试 AVD，并以 `ANDROID_SERIAL` 明确目标；AGP 的安装/卸载会清理 Debug 包私有数据，不能对保存日常调试数据的 AVD 直接运行。测试目录的 finally 清理不能保护包级卸载。优先复用匹配 ABI/页大小的测试设备，临时镜像和 AVD 在验收后清理，避免积累快照与重复磁盘。


### HTTP ASR 设备输入前提

`HttpAsrLifecycleInstrumentedTest` 的录音中取消和 revoked admission 用例不依赖有声输入，覆盖不上传、原文件清理与状态复位。两个完整 WAV 上传/上传中取消用例使用 `httpAsrLiveAudio=true` 显式启用，要求测试期间向设备麦克风持续提供有声输入；每次采样保持 1.5 秒，并在等待时立即呈现 ASR Error 原诊断。普通模拟器底噪不满足 `PcmSignalStatistics` 的有效信号要求，未提供音源时这两项跳过，不能记录为成功上传验收。运行参数为 `-Pandroid.testInstrumentationRunnerArguments.httpAsrLiveAudio=true`；仅指定 `HttpAsrLifecycleInstrumentedTest` 可执行该声学场景。
