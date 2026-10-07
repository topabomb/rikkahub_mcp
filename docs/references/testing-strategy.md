# 测试策略与验证边界

本文说明如何选择测试、定位现有覆盖和执行验证。领域行为由对应参考文档维护；发现文档与实现不一致时，
沿生产入口和测试查明原因，再同步修正。测试存在、测试通过和真实服务验收是不同证据，不能互相替代。

## 1. 选择测试边界

先找负责该语义的生产组件，用最近的行为测试观察成功、拒绝、失败、取消和恢复；跨组件提交与补偿再补集成测试。
不为凑齐层级复制断言，不用源码扫描证明运行行为。

| 层次 | 运行位置 | 适合证明的事实 |
| --- | --- | --- |
| 纯协议与算法 | JVM | 状态迁移、请求选择与预算、压缩计划、解析与路径规则；不引入无关 Android 或数据库依赖 |
| 组件与应用服务 | JVM，必要时 Robolectric | Turn/Step/工具编排、commit-then-publish、Session 与资源生命周期；用可控 fake 驱动依赖 |
| 持久化与线协议集成 | JVM、Robolectric 或 instrumentation，按真实依赖选择 | Provider JSON/SSE、序列化、真实 Room 事务/DAO/FTS、备份与引用恢复；不 mock SQL 后断言字符串 |
| Android 与 Compose | instrumentation | SQLite migration、ContentResolver、Bitmap/Exif、Keystore、系统服务、WebView、焦点/IME、分享及真实页面绑定 |
| 性能 | 受控 benchmark | 耗时、分配和帧分布；普通测试只锁写入次数等结构性复杂度，不用耗时阈值猜测性能 |

JVM 适合先验证 UI 决策和投影，设备测试验证平台适配与真实页面交互。节点存在不等于可见、可点击或流程可完成；
Activity 重开不等于数据库关闭重开或进程重启，Repository ACK 不等于已落盘，确定性 HTTP adapter 不等于真实 Provider。

测试放在对应模块和生产 package 下，类名用 `*Test` 与领域术语，不新增仅为分层服务的 Gradle module。
`app/src/test/.../pilot/architecture/` 保存静态边界测试，`ai/src/test/.../provider/providers/` 保存 adapter 线协议测试；
其余行为测试随 service、runtime、data 等现有目录归属。

## 2. 契约归属与主要入口

以下入口用于定位覆盖，不代表已执行结果，也不要求每次修改运行全部用例。

| 契约 | 主要入口 | 需要区分的证据 |
| --- | --- | --- |
| 会话与事务发布 | `ConversationTransitionTest`、`TurnTransitionTest`、`ConversationCommandCoordinatorTest`、`TurnCommitterTest`、`TurnPersistenceDeltaTest` | 状态与写入次数由行为测试证明；schema、事务、分支与恢复另用真实 Room |
| 请求冻结与上下文 | `TurnRequestAdmissionTest`、`ConversationDisclosureReconciliationTest`、`TimeReminderTransformerTest`；`ConversationContextPayloadTest`、`ConversationContextPruneTest`、`ConversationContextQueryTest`、`ConversationContextPresentationTest` | 重试输入不变、来源保全、variant 适用性、无 admission 历史、按需读取与迟到结果 |
| 工具压缩与缓存 | `ToolOutputCompactionPlannerTest`、`ToolOutputProtocolTest`、`ClaudeProviderPromptCacheTest` | 只处理本次成功请求可见且已消费的历史结果；三种保留策略、保护窗口和净回收阈值由 `ContextBudget` 约束，缓存稳定不能阻止必要压缩 |
| 工具交互与子助手 | `ToolApprovalReducerTest`、`ToolCallRuntimeTest`、`TurnInteractionContinuationIntegrationTest`、`SubAssistantRunCoordinatorTest` | Mock Runner 委托只证明入口；continuation、Parent/Child 终态与正式页面需观察真实边界 |
| Settings 与启动恢复 | `SettingsStartupTest`、`ApplicationRecoveryCoordinatorTest` | 真实 DataStore 文件/migration 的保全重试、observer 归属，以及恢复顺序、门禁、取消与 retry |
| 企业 Session、退出与页面恢复 | `RealmAccessTest`、`EnterpriseExitServiceTest`、`PlatformSessionNetworkTest`、`RemoteWorkspaceServiceTest`、`ConversationQueryServiceTest`、`ChatPageLifecycleTest` | 旧到期事件、撤权、迟到结果和重试不误用新 Session；读失败保留原异常与输入，真实撤权关闭原 lease |
| Starter 与平台合同 | `StarterOpeningSelectionTest`、`StarterOpeningConcurrencyTest`、`ConversationPageAccessTest`；`PlatformContractSourceTest`、`PlatformCoreStarterContractTest`、`PlatformWireTest`、`EnterpriseStarterAppliedStoreTest` | 完整定义比较、原发布身份、选择 CAS、精确补偿、v4/v5 消费、ETag/304 与 Applied 重开 |
| Android 上下文与首发 | `EnterpriseStarterPersistenceAndroidTest`、`StarterSubmissionOwnershipAndroidTest`；`ChatContextFlowAndroidTest`、`ChatDocumentContextFlowAndroidTest`、`ConversationContextAndroidTest`、`StarterEntryFlowAndroidTest`、`StarterV5ChatFlowAndroidTest` | Keystore/AtomicFile、Room、附件归还、取消和页面重开；分别标明 Mock 的配置、网络与 Provider 边界 |
| JavaScript 工具 | `JavascriptToolTest`、`JavascriptRuntimeAndroidTest` | 前者使用桌面 QuickJS 验证截止、取消、格式化陷阱及限额；Android JNI、双 ABI 和 Release R8 需独立证据 |

Artifact、GeneratedMedia、备份、MCP、Workspace 与 Speech 的行为测试随各自 Store、service 或 runtime 定位。
Portal 的 JVM/本机 HTTP 测试观察文档及 Session 绑定、迟到请求、一次性兑换和媒体额度；WebView detach、
媒体采集释放、AndroidKeyStore 与正式权限/后台路径仍需 Android 或显式 live 场景。
配置提交失败注入不能冒充设备磁盘故障，返回空集合的替身不能掩盖退出、清理或恢复错误。

### Provider 线协议

- 共享请求装配、历史选择、内容顺序、`Tool.stepId` 与 receipt 由 app/共享模型测试负责，媒体由 transformer 测试负责。
- 各 adapter 分别验证序列化、响应/usage 解析及 opaque replay；`ProviderRequestContractFixtures.kt` 共享输入，不用复杂基类合并不同 wire。
- `StepOutputAccumulator` 验证跨 chunk 参数与 reasoning 合并，本机 HTTP/SSE fixture 验证 listener 的 EOF、终态、重复 ID 与交错调用槽。

Core Snapshot、manifest 和共享 cases 的来源见 [配置架构](android-configuration-architecture.md)。
`contracts/runtime/managed-snapshot-required.json` 来自 platform-core 同名 Problem fixture；Android 自有 Mock 不是跨端合同的权威来源。

### 静态架构边界

`ArchitectureDependencyTest` 锁定 UI/application/query 依赖方向和唯一写入职责；`RetiredSurfaceContractTest` 防止已移除接口回归；
`TurnStepProtocolContractTest` 限制 Turn 对 Workspace 输出路径和恢复对 render overlay 的依赖。
三者复用 `ArchitectureSources.kt`，只约束 owner、依赖方向和退休接口；不复制源码片段或代码行顺序来证明 CAS、取消或 UI 行为。
失败信息应定位被破坏的约定，避免同一禁令散落到多份扫描测试。

## 3. 确定性与测试取舍

JVM 竞态测试用 `runTest`、`CompletableDeferred`、`Channel`、`Mutex` barrier 和测试调度器控制交接，
不靠固定 sleep/delay 猜测完成。注明控制的生产边界，例如 START 提交、Provider 首输出、工具 STARTED 提交、
结果提交与资源发布；跨线程状态通过 StateFlow/Channel 等可观察机制交接。

取消测试覆盖 first-wins 的停止原因及失败并发，观察最终持久结果和资源实际退出，不从 UI 再推导一次终态。
资源测试观察资源取得、提交、发布和回滚的所有权，不只检查文件是否存在。
核心状态机使用确定性 fake；Mock 用于叶子调用、平台适配和无状态查询，避免依赖整体 `relaxed = true`。

设备或外部进程可用有界条件等待真实状态。负行为允许明确的观察窗口，如暂停期间不得播放；
窗口不能证明合成或 collector 已结束。Compose 持锁 IO 通过后台交接，不嵌套 `runBlocking` 阻断持锁协程。
失败后重跑通过只证明该次重跑，不得用自动 retry、扩大等待或放宽断言代替根因分析。

保留能保护数据完整性、wire、授权/路径边界、关键用户行为、并发恢复或真实兼容性的测试，观察稳定结果并给出可定位失败。
删除或合并前核对实际生产入口、输入边界和独有断言；仅当能力已移除、保护范围完全重叠或断言没有业务价值时删除。
文件大小、年代、migration 版本或运行速度本身不是删除理由。

- 有限状态矩阵可在同一用例逐项断言并标明失败输入；不同 adapter 的 wire 仍分开保护。
- `MigrationTestHelper.runMigrationsAndValidate` 已覆盖的重复 schema 断言可合并，数据保全、独有约束与失败路径仍保留。
- Room 默认 `index_` 名称不必逐字比较；旧索引清理等真实兼容约束需明确断言。
- DAO 测试调用实际 DAO，不能以手写同形 SQL 替代；Compose 保存恢复让真实保存机制持有状态，不能靠测试外部变量存活。

## 4. 验证命令与 CI

先运行最近的定向测试，再按风险扩大。纯文档只需事实、链接、编码与最终 diff 检查；
架构或跨模块/数据契约变更需完整门禁，Android 平台、数据库迁移或 Compose 变更还需设备门禁。
Windows PowerShell 使用以下命令，macOS/Linux 将入口换为 `./gradlew`；所有 Gradle 验证串行执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "<完整测试类名>" --no-parallel --max-workers=1
.\gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
.\gradlew.bat connectedDebugAndroidTest --no-parallel --max-workers=1
```

定向任务按实际模块替换。关键测试不能永久忽略、隔离或自动重试；需要外部资源的 opt-in 场景按下一节单独记录。

`.github/workflows/verify.yml` 在 PR、主分支 push、手动或复用调用时运行全模块 JVM、Debug/Release 构建、lint、diff
和 Room schema 生成一致性检查，再运行模拟器 `connectedDebugAndroidTest`，失败仍上传报告。
`release.yml` 复用该门禁，通过后才签名与发布。CI 关闭模拟器硬键盘；普通 CI 不提供下述 opt-in live 环境。

## 5. 设备与显式集成场景

`connectedDebugAndroidTest` 使用专用测试 AVD，并用 `ANDROID_SERIAL` 明确目标。AGP 安装/卸载可能清理 Debug
私有数据，测试内 finally 无法保护包级卸载，不能直接用于日常或演示设备。先运行普通门禁；需要已接入状态的 live
用例采用保留数据的安装与直接 instrumentation。检查设备 loopback Mock 所需的网络条件和系统 HTTP 代理，
临时设置验证后还原。IME 场景要求实际非零 inset 的停靠软键盘，键盘未出现不能改为跳过或修改生产输入配置。

| 场景 | 启用参数与前提 | 证据边界 |
| --- | --- | --- |
| Starter v5 隔离 Mock | `starterV5MockLive=true`；专用且从未绑定企业的设备 | 实际 UI、Applied、Session、Room 与 adapter，HTTP 为 Mock |
| Starter v5 Core | `starterV5CoreLive=true` 与 `coreStarterInput` | 真实 Snapshot/Starter/临时接入资料，输入读取后删除；区分确定性 adapter 和实际 Provider |
| 已接入平台上下文 | `platformContextLive=true`；可选 `platformContextModelSwitch=true` | 记录发布身份、实际版本与 Provider 结果；v4 不证明 v5 opening |
| 远程文件 | `remoteWorkspaceInput`；未接入专用设备和独立 Core/Agent Space | 配置文件与主机配合步骤见下文，普通门禁无输入时跳过 |
| Linux Rootfs | `prootRootfsUrl`；匹配 ABI 的已核验镜像 | `WorkspaceProotAndroidTest` 使用临时 Workspace；native PTY 不证明 PRoot，页大小和 ABI 分别记录 |
| HTTP ASR 控制器录音与交接 | `httpAsrLiveAudio=true`；持续有效麦克风输入 | `HttpAsrLifecycleInstrumentedTest` 使用转写替身，证明 WAV、有效信号、取消与清理，不证明真实 HTTP 上传或服务器识别 |
| MCP 目录冷启动 | `McpLifecycleUiAndroidTest#confirmedDirectoryRestoresWithoutFailureAfterProcessRestart`，先 `mcpColdSeed=true`，停止目标进程后再 `mcpColdVerify=true` | 同一专用设备保留安装和数据；真实 Catalog 持久化与正式设置页，HTTP 为本地 fixture |

Gradle 参数用 `'-Pandroid.testInstrumentationRunnerArguments.<name>=<value>'`，避免 PowerShell 拆分。
缺 Rootfs fixture 明确跳过；ASR 录音取消与准入撤销可独立验证，真实上传和识别另需服务端证据。
`McpLifecycleUiAndroidTest` 的普通用例通过真实 HTTP/SSE、Koin owners 和正式设置页验证连接、刷新失败保留目录、
通知退化仍能调用及显式恢复，并保存截图。冷启动两阶段用直接 instrumentation 运行，阶段之间不能卸载包；
验证阶段删除自己创建的配置、目录及 marker。`SettingMcpPageAndroidTest` 的企业暗色大字体截图使用受控 query 投影，
只证明界面呈现，不代替真实 Core 接入或企业执行授权测试。
旧/当前客户端的 Core 兼容探针由 [兼容性验证工具](../../tools/compatibility/README.md)维护，入口是
`PlatformSnapshotCompatibilityLiveAndroidTest`。旧 APK 使用其固定提交和独立探针，不能换用当前 DTO 声称覆盖旧客户端；
版本故障注入不等于 Core 正式发布。

### 远程文件 live 的准备与运行

`RemoteWorkspaceLiveAndroidTest` 从目标应用 cache 内读取 `remoteWorkspaceInput` 指定的 JSON，随即删除输入文件。
设备必须没有 Session、待接入资料或旧身份。通过真实 enrollment owner 接入；主机只操作专属测试用户，不改变全局发布。

| JSON 字段 | 含义 |
| --- | --- |
| `enrollment` | 必填，真实临时接入字符串 |
| `configurationPublished` | 默认 `false`；未发布环境要求 `activeManagedGeneration=0` 并断言 Pending/无 Applied；已发布环境设 `true`，显式同步后断言 READY/Applied |
| `expectedMcpAvailable` | 默认 `false`，须与隔离用户的真实 MCP 投影一致；文件资格与 MCP 分别断言 |
| `lifecycle` | 默认 `false`；设 `true` 时需要主机配合断开、恢复及空间替换 |

用例覆盖 64 MiB 摘要往返、BOM/正文标记/CRLF、创建/复制/移动条件写入、覆盖确认竞态、双编辑者冲突与另存、
慢下载取消和切域关闭，以及原生预览、编码 Markdown 相对图片和真实目录选择对账。

生命周期模式在 cache 写出 `remote-workspace-step.txt`，主机依次响应 `disconnect`、`restore`、`files`、`replace`：
完成对应专属用户管理动作后，在同目录写临时回执，再原子重命名为 `remote-workspace-<step>.done`，内容必须为 `ok`。
设备先检查旧回执不存在，再等待并消费新回执；避免预写或让它看到未完成内容。
用例观察恢复时文件资格和替换空间后的旧句柄读写拒绝，最后同步并按 `configurationPublished` 断言最终配置状态。

cache 中的 `remote-workspace-live-evidence.json` 在 finally 写出，失败时可能只有部分结果；保留它和测试报告、截图一起判断，
文件存在不能单独证明通过。测试创建的远端验证目录与企业绑定不会自动清理，运行后由专用环境管理流程处理。

每次交付记录源码/构建身份、设备和 ABI、报告、失败与跳过原因，以及实际 Core/Provider 身份。
设备、真实服务、声学输入与生产环境分别说明；参考文档维护验证方法，不累计历史通过数量。

### 文件预览设备场景

`MediaPreviewAndroidTest` 使用项目内的短 MP4 验证解码、原生控制条、拖动、全屏返回、后台停止及撤权；
4 GiB 稀疏 MP4 将真实媒体数据放到超过 2 GiB 的偏移，验证 Long 寻址与有界读取，不代表 4 GiB 网络吞吐或持续播放测试。
图片查看器、静态 SVG 验证和 `EditorInputConnectionAndroidTest` 分别覆盖图片操作、解析边界与原生惯性/查找/跳行。
编辑器的键盘缩放断言显式使用停靠键盘，并在 finally 恢复原手写模式；浮动手写 IME 可见但底部 inset 为零，不代表正文应缩短。
这些设备证据不能代替真实 DAV 服务的 HEAD/206/If-Match、代理转发和中断响应验证；真实服务必须单独记录部署身份。

`RemoteMediaPreviewLiveAndroidTest` 使用已接入的专用设备，以 `remotePreviewLive=true` 显式启用；采用保留数据安装和直接
instrumentation，不用会卸载应用的 connected 任务。测试通过原远程服务上传短 MP4，在真实文件页面检查播放、暂停拖动后的
新画面、横竖屏、关闭释放和旧 ETag 拒绝。不得为了测试修改企业发布、助手绑定或工作卷容量。

4 GiB 样本采用宿主协作：设备在 cache 写出 `remote-preview-sparse-request.json`，含本次 UUID 目录与原 `agentSpaceId`。
宿主核对它与真实服务/运行环境身份一致后，通过官方 guest 执行入口运行测试资源 `media/sparse-preview.py`，参数是该目录的
`short.mp4` 和 `four-gib.mp4`。脚本独占创建稀疏文件，实际媒体数据位于 3 GiB，不能从 Host 挂载运行中工作卷或覆盖原文件。
成功后将目录名写入同 cache 的临时文件，再原子改名为 `remote-preview-sparse.done`；禁止预写回执。
设备验证 4 GiB HEAD、跨 2 GiB 与末尾 Range 及真实解码，finally 删除本次目录与握手文件，将结果写入
`remote-preview-live-evidence.json`。截图位于应用 external files。测试仍不代表完整 4 GiB 网络传输或高码率持续播放。

## 6. 性能证据

性能入口为 `:app:baselineprofile:connectedBenchmarkReleaseAndroidTest`：`StartupBenchmarks` 测冷启动，
`TurnWorkloadBenchmarks` 以完整编译、冷启动和重复采样调用真实生产 owner：

- 10,000 次活跃 Assistant chunk 合并、1,000 条历史请求装配、100 个大型工具结果压缩计划、50 个工具 schema 冻结与预算。
- 从导出 v10 schema 建库，迁移 1,000 个含大型 legacy transcript 的节点；通过实际迁移链、schema 校验和 SQL 检查结果。
- 100 MB 工具输出经 ArtifactStore/ToolOutputStore 执行 read 与 grep。
- 1,000 条历史下进行 100 次活跃 Assistant 更新，分别测有/无上下文投影，并采集 `FrameTimingMetric`。

驱动 Activity、数据库与 payload fixture 位于 `app/src/benchmarkRelease`，不进入正式 Debug/Release APK。
输入构造在测量段外，设备要求 API 29+。在已连接专用设备上执行：

```powershell
.\gradlew.bat :app:baselineprofile:connectedBenchmarkReleaseAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.TurnWorkloadBenchmarks' --no-parallel --max-workers=1
```

`measureWorkload` 用 Perfetto slice 记录同步耗时，边界前后 ART `art.gc.bytes-allocated` 差值记录近似 Java/Kotlin 分配。
Compose 从首帧完成至 100 次更新完成记录同口径分配。统计是进程级近似值，包含其他线程与测量开销，
不是精确对象计数、native 分配、存活堆或峰值内存；缺少统计直接失败，不记零。

`TurnWorkloadMetric` 只查询当前迭代的目标进程：每个同步测量段恰有一个 slice 和一个分配 counter，
Compose 汇总活跃消息 slice，读取最终 composition counter 和一个分配 counter，不跨迭代保存计数。
AndroidX JSON 的 `sampledMetrics` 保留耗时与分配的原始 `runs` 及分位数，Compose 另保留逐帧分布；同次 Perfetto trace 可核对口径。
`migratedRows` 是 SQL 验证行数，`toolOutputInputBytes` 是输入文件大小，`activeAssistantCompositions` 是含初次渲染的活跃消息 composition 次数，
后两者分别不代表操作系统 IO 字节或 Compose 内全部函数重组次数。

`.github/workflows/benchmark.yml` 仅供手动模拟器诊断，保留 JSON 与 trace，不设性能通过阈值。
只有固定实机和系统下的结果适合与同环境 baseline 比较；有执行入口不代表已经采集基线。
