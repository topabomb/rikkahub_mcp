# Android 配置架构与资源边界

本文定义 Android 当前配置的 owner、持久化载体、字段目录、引用关系及个人/企业域边界。Assistant 逐字段运行语义见 [助手配置](assistant-configuration.md)，总体依赖与启动恢复见 [应用架构](application-architecture.md)。

## 1. 配置组成

```text
UserSettingsDocument（用户定义 + 公用/按域偏好）+ Applied Enterprise State
  → ConfigurationResolver（按原域/主体解析）
      → ResolvedConfiguration → application / query ports → 执行与 UI
```

完整配置分属 DataStore、轻量 UI preferences、Room、资源文件和可重建缓存，不能整体替换为远端 Settings JSON。企业平台接入复用现有 Session、配置发布与按域解析，平台令牌不进入用户配置。企业配置使用独立身份与定义，不通过全局 Settings overlay 覆盖用户配置。

## 2. 配置 owner 与读写架构

### 2.1 Owner 表

| Owner | 当前载体 | 负责的事实 | 是否进入普通本地备份 |
|---|---|---|---|
| Built-in | `DefaultProviders.kt`、默认 Assistant/TTS/Prompt/Theme 常量 | 安装包内默认资源和反序列化默认值 | 不单独备份；读取时补齐 |
| 用户配置与偏好 | Preferences DataStore `settings` 的 `user_settings` JSON | UserSettingsDocument 内分开保存用户定义、公用显示偏好、按主体的选择、内部状态 | 以个人 Settings 投影导出 `settings.json` |
| Local UI preference | SharedPreferences `MeasixPilot.preferences` | 语言、明暗、启动/布局/搜索排序等轻量偏好 | 否 |
| Local durable resource | Room + `filesDir` | Workspace、Skill、会话级覆盖、资源文件 | 仅备份协议明确包含的域 |
| Local runtime cache | `cacheDir/lru_key_roulette.json` 等 | 可重建的 key 轮换/发现缓存；不是配置真源 | 否 |
| 企业状态 | `noBackupFilesDir/enterprise`；EnterpriseAppliedStore / EnterpriseSessionController | Session、完整配置、binding、独立 Feed 与当前空间；按 Deployment/User | 否 |

最近聊天 ID 位于 `ScopedUserPreferences.lastConversationId`，按个人域或完整 Deployment/User 保存。
`SettingsStore.rememberConversation(scope, id)` 沿原 writer 更新该引用；`id = null` 明确清除指定域的最近聊天，不改变其资源选择或其他域。
`ConversationHistoryPreferenceMigration` 在旧 Settings 键迁移之后，把已发行的 SharedPreferences `lastConversationId`
一次性归入个人域；已有域内值优先，DataStore 提交成功后才删除旧键，失败可重试。运行时不再读取全局旧键。
普通个人配置更新保留各域最近聊天与企业使用偏好；新聊天 Draft 不写入最近聊天，删除后恢复缺失会话由页面明确处理。

`lru_key_roulette.json` 当前以原始 API key 作为 map key 保存轮换时间。它虽然不是配置真源也不进入手工备份，仍是
未加密 secret 副本；企业 credential 不能复用这条缓存协议。

### 2.2 单一读写路径

`SettingsStore` 是用户配置与偏好的唯一写入 owner。企业配置归独立的 EnterpriseAppliedStore / EnterpriseSessionController。

```text
updateLocal(latest personalSettings transform)
→ normalizeForPersistence / canonicalizeForDataStore
→ UserSettingsDocument.withPersonalSettings
→ one dataStore.edit（user_settings）
→ durable success
→ userSettings（materializeForRead，只读个人投影）
```

- `userSettings` 用于共享用户定义编辑、公用外观与个人配置读取；不代表企业可用资源或执行授权。
- 域内目录、使用选择和执行通过 configuration application/query ports 消费 `ConfigurationResolver` 的 `ResolvedConfiguration`，不另落盘镜像。
- `ConfigurationQueryService` 将原 selection 的读取失败发布为带诊断的不可用状态，保留外层空间订阅；查询等待期间授权到期后，切换空间仍可重新建立目录读取。取消继续传播，失败不发布旧目录或空目录冒充成功。
- `SettingsStore.observeConfiguration` 的去重同时比较完整投影内容与目录 `catalog.keys` 的迭代顺序：Map 值相同但顺序变化仍发布。目录顺序只从原配置 List 经 Resolver 投影，不增加 order 字段或 UI 排序副本；无关配置变化仍去重。
- 所有配置修改持有同一 Settings writer lock。首次写入直接读 DataStore，不等待异步 UI 投影；观察器取得写锁后重新读取最新文档，不能用迟到值回退投影。
- 启动恢复显式等待 `SettingsStore.initializeForRecovery()` 的真实读取、migration 和解码；异常直接进入原应用恢复门禁。同一实例只保留一个发布 `userSettings` 的长期观察 job，重试先在 writer 锁外等待旧观察结束，再在原写锁内读取最新值并发布；合法的配置冷 Flow 仍可独立订阅。失败不重置已有投影、不清空文件或自动写默认配置。
- DataStore 拒绝提交时不发布；已取得写入所有权的提交在取消后仍等待回执并发布，再传播取消。按域偏好提交还保持原 Session 授权边界。
- 企业规则在 application/Settings 命令与执行边界复验，UI disabled 只是展示；企业定义不会覆盖或删除同名用户定义。
- `restoreLocal()` 和 `snapshotLocal()` 只操作个人 Settings 投影；恢复经 `ArtifactStore.restoreSettingsReferences` 校验配置文件引用。
- `pendingAssistantDeletions` 在 Settings 投影中为 `@Transient`，在 UserSettingsDocument.internalState 中持久化，恢复普通 Settings 时不得清空。

新增文件引用必须通过 `ArtifactStore.updateSettingsReferences`：Settings 写锁先于 Artifact 生命周期锁，校验后保持文件锁直到 DataStore 回执与创建所有权交接完成。普通配置提交拒绝绕过该入口新增引用。完整引用覆盖用户定义和所有主体保留的使用偏好，不从当前显示投影推断；具体文件保留、删除与恢复协议见 [多模态与持久化](multimodal-context-and-turn-durability.md)。

### 2.3 用户配置文档与迁移

`UserSettingsDocument` 的 `schemaVersion`、`configuration`、`preferences`、`internalState` 为必需字段，缺失或版本不支持时拒绝读取；其 schema 与 Room、应用和备份版本独立。

- `UserConfiguration` 保存现有 Provider/Model、TTS/ASR、MCP、Search、Assistant、注入/QuickMessage/标签、辅助提示词、备份连接及 UserProfile。用户昵称/头像不重复放入显示偏好。
- `UserPreferences.common` 保存 DataStore 原有的外观、DisplayPreferences、播放速度和提醒；`scopes` 以完整 ConfigurationScope 保存 ResourceSelections、企业域的 AssistantUsagePreferences 和 GatewayPreference。Gateway 偏好随 Deployment、User 与资源 ID 隔离，未交付的顶层 gateways 字段已删除，不将无用户归属的原型偏好猜测归入某个主体。SharedPreferences 表中的偏好仍由原 owner 管理。
- `common.configuration.ConfigurationReference` 保留原用户 UUID 或企业 deploymentId/原始字符串 ID，不生成替代 UUID。个人引用的 JSON 仍是原 UUID 字符串；企业引用序列化为 `managed~deploymentId~资源ID`。ConfigurationScope 企业身份只包括 deploymentId/userId，URL、域名、端口、服务器位置、企业名称和用户名等可变属性都不参与身份。Settings 投影只提供个人配置；企业聊天、子助手、辅助模型、MCP、语音与配置界面通过原域的 `ResolvedConfiguration` 读取资源和选择。
- `EnterprisePrincipalPreferencesMigration` 在 DataStore 打开前一次性去除旧 authority 中的 `sourceNamespace` 并将旧企业引用改写为 deploymentId 格式；同一主体因多个旧 URL 来源产生的 Settings scope 按序列化顺序以后出现的标量为准、逐资源合并偏好，MCP Catalog 保留最高 managed generation/revision 的可重建缓存。Room 的同类持久字段由 `Migration_12_13` 在数据库事务内改写；迁移先验证所有非 Personal scope 与 managed reference，任一异常即整体失败。正常解析器只接受新格式，不保留旧格式 fallback。
- Settings、MCP Catalog、Conversation、Memory、Artifact 与媒体缓存/查询均以稳定的 `deploymentId + userId` scope 归属；修改企业地址不会复制或清空缓存。`Migration_12_13` 同时把高频域内查询索引改为 scope 前缀，保留 Child 外键、全库恢复和生命周期查询所需的非 scope 索引，不增加第二套缓存或地址到身份的映射。
- `UserSettingsMigration` 在旧 OCR/Search/MCP 迁移之后，将旧键转换并在同一次 DataStore 迁移提交中移除；MCP Catalog 的 pending staging 保留给 Catalog owner。旧资源或 tombstone 解码失败会中止迁移，原输入不变。正常读写只访问新文档，没有旧键 fallback。
- 用户定义及其配置绑定只接受 User 引用；按企业保存的选择允许 User 或同 authority 的 Enterprise 引用，拒绝外域引用。会话、Message、Turn、文件与 Workspace 的自身 ID 继续使用 UUID。
- 已删除无功能消费者的 developerMode 字段；迁移清除旧 developer_mode 键，旧备份中的字段由 JSON codec 忽略。构建类型标记不使用此配置。
- 写个人配置会保留其他主体的偏好；ResolvedConfiguration 仍为只读内存投影，不另落盘。

背景写入由 `AssistantBackgroundService` 接受明确的目的：页面持有原 `RealmSelection`，生成工具持有原 `RealmAccess`，共享定义编辑器显式指定 User 助手。个人域写助手定义，企业域写完整主体下的 `AssistantUsagePreferences.background` 并关闭渐变，不复制整份个人配置，也不修改企业下发定义。生成背景按原域和图库 ID 读取；查看器读取 `ImageSource` 后复验目的域。`AssistantPreferenceChange.Background` 复用 Settings 唯一 typed 写协议，经 `ArtifactSettingsCoordinator` 与 `ArtifactStore.commitSettingsRoots` 在 Session → Settings → Artifact 顺序中验证引用、提交并移交创建 pin。失败精确回收未发布副本；旧图片由 Artifact 按所有域的引用统一回收。

企业聊天的 `AssistantUsageEditor` 借用原 `ConversationAssistantTarget` 与 `ConversationViewLease`，复用普通助手配置的分组和内容组件，不增加可持久化编辑器或第二配置快照。当前会话助手才能进入此写页面；抽屉和选择器中尚未选定的候选只把目录快照显示在同一分组表面，不构造写 target，企业候选也不进入个人 Settings 编辑器。`EditUsage` 比较页面基线与编辑结果，只将实际修改字段应用到锁内最新偏好；未修改字段保持继承，定义字段、企业固定提示词/MCP 与继承子助手引用不能借此覆盖。额外子助手引用和标签选择使用 typed 字段命令；标签目录仍共享，清理统计所有主体使用偏好。`ResetUsage` 只在企业域展示，只删除当前主体对该助手的使用覆盖。

头像和背景导入经 `ConfigurationApplicationService.importAssistantImage` 创建原域配置资产，再通过既有 Settings → Artifact 提交引用并移交创建所有权。读取用 `RealmConfiguration` 保留原页面和 Session，允许 Personal 已提交配置根或本主体已提交 usage 根，拒绝其他企业及没有配置根的聊天资产。个人共享图片失去个人根后，只要本企业仍保留已提交引用，本企业可继续读取；个人配置读取不能借用企业根。预设消息文本编辑保留非文本 parts 和 metadata。企业 Prompt 预览不提供重新捕获全局目标的“设为背景”操作，背景在原助手使用设置中编辑。

### 2.4 企业来源与 Session

平台 Discovery、Enrollment、Bootstrap、Snapshot 和运行请求共用原生 Session，不存在手机端模拟企业来源或第二套企业配置。`EnterpriseSessionController` 串行发布身份、连接和 Applied 版本；`EnterpriseAppliedStore` 在 `noBackupFilesDir/enterprise` 用单 manifest 发布经校验的不可变配置与执行描述。用户定义和偏好仍只由 `SettingsStore` 写入。启动恢复在 Settings 和文件 owner 完成恢复后继续本机企业重置及配置恢复；企业校验失败保留企业不可用诊断，不伪装成空配置。

正式接入入口为扫码、相册识码和粘贴，统一经 `EnrollmentMaterialParser` 解析和用户确认。`PlatformSnapshotMapper` 当前只映射平台 MCP 定义，`gateways` 固定为空；保留的 Gateway 数据类型与使用偏好不代表平台已下发手机端 Gateway 资源。

- Applied manifest 与平台 Snapshot 各有独立版本。Applied 的旧 schema 只经一次性持久迁移进入当前格式；正常读写不双读。manifest 提交需同步文件并核验实际落盘，损坏态不能通过回读旧 manifest 实施重置。
- 同步由 `EnterpriseSynchronizationService` 合并同一主体/Session 的请求；Session owner 再核验 Bootstrap 身份与候选 Snapshot 后提交。成功保存最近同步时间；已提交配置的 applied 回报失败仍保留配置并暴露诊断，下一次同步可重报。
- `EnterpriseExecutionLease` 捕获原 Session、Applied binding 与连接；退出等待其真实清理，清理失败保留 owner 供重试。lease 本身不等于远端执行准入。

平台地址是 Session 的可变连接参数，不是 deployment 身份。`PlatformEnterpriseService.changeAddress` 先验证同一 deployment、原用户/设备/Session，再由 Session owner 一次提交新地址及必要的轮换凭据；失败保留已确认连接。退出、撤销与身份删除先持久发布 CLOSING，停止新操作并等待已有 operation/execution lease；`EnterpriseExitService` 只完成原 Session 的退出。永久身份删除由 `EnterpriseIdentityDataDisposer` 编排各 owner 精确清除该 principal，个人数据不进入范围。全设备本机重置由 `EnterpriseDataResetService` 持久化 reset intent，复用同一关闭屏障并在重启后继续，不能用清日志或空列表表示完成。

- 地址切换期间，已捕获的请求继续使用原连接；新连接只供提交后的请求。候选地址验证失败不关闭原 Portal。成功后旧 Portal 的关闭和站点清理由同一屏障确认。候选地址若已完成凭据轮换，失败处理不能回退到已失效凭据。
- 主动退出、到期、撤销和身份删除都绑定原 Session；重复请求合并。`IDENTITY_DELETED` 是更强的持久终态，不能被重启或较弱的退出原因覆盖。远端注销失败仍须完成可恢复的本机退出与清理并保留原诊断。
- 本地到期计时只唤醒检查，`EnterpriseSessionController.expireIfCurrent` 在 Session 锁内复核原身份与最新截止时间；已经续期的会话不接受旧到期事件。到期提交失败的显式重试仍保留本地到期来源并再次复核，不能变成无条件撤权。服务端明确的 `session_expired`、撤销或身份删除继续沿原失效协议处理，不受本地旧截止限制。
- Core 的 `401 invalid_credential` 可以表示短期 access token 被拒绝，并不证明 Session 到期。平台服务按原 Session/连接串行恢复凭据，只有 refresh credential 的明确终态拒绝或服务端会话/主体终态才退出企业连接。访问接口再次拒绝新 token 时保留原诊断，不将其伪装为登录到期。
- 本机重置区分“仅删除接入”与“接入和全部企业历史”，范围先写入 intent，再由原数据 owner 清理；强删除终态到达时扩大清理范围，重启继续。两个分支都不删除个人域，也不依赖 Core logout 成功。

`PlatformSnapshotMapper` 将受支持的 Snapshot v4/v5 映射为候选；`EnterpriseConfigurationCodec` 在读取 canonical Applied 时再次校验同一领域约束。`ManagedPolicy` 的助手、对话、快速、标题、附件检查、建议、压缩、图片、TTS、ASR 十项默认引用均可省略；缺失表示未设置，不取资源首项，也不复制对话默认。非空模型引用必须指向同一快照内已启用模型，附件检查模型还需 IMAGE 输入。可选 `imageGenerators` 缺失表示空集合；显式 null、未知字段和未知枚举失败关闭。平台 Snapshot 版本与本地 Applied manifest 版本是不同契约，不能混用。

Android v4/v5 的唯一 wire 来源为 Core 导出的 `contracts/platform/client-control.openapi.yaml` 与 `manifest.json`。`tools/generate-enterprise-wire.py` 校验 LF 规范化来源摘要，从 Core 的 ManagedSnapshotV4 与 ManagedSnapshot 合成客户端版本化类型并生成唯一 `PlatformWire.kt`，`--check` 验证漂移。Core 共享 cases 同时覆盖 v4/v5，旧 v4 golden 保留；Android 不再维护目标版本增量 schema。仓库内固定导出使普通构建不依赖 sibling checkout，实际部署能力与本地生成契约仍分别验证。`PlatformSnapshotCompatibility.supportedSchemas` 是客户端实际支持的格式集合，目前为 4、5，不按 App 版本、最高版本或连续范围推断。Discovery 与首次/刷新/地址变更 Bootstrap 校验控制协议与身份，不用 Snapshot 能力拒绝有效身份；非零目标配置同步才检查能力交集，实际下载版本还必须出现在本次 Bootstrap 的能力列表中。`PlatformWireCodec` 对 Snapshot 在有界严格 JSON 解析后、完整 DTO 解码前检查外层正整数 `schemaVersion`，再按受支持格式严格校验字段；不将非法 v5 当作 v4 继续解析。

`PlatformConnection` 的持久构造只校验 origin/身份/路径，不用当前在线能力门禁拒绝旧 Session 中的 Discovery `[4]`。`EnterpriseAppliedStore` 仍使用 manifest 6，先验证原 revision hash 再解码；旧 `EnterpriseStarter.openingSnapshot` 缺失保留为 null，不补造 System、不改原 release/hash，也不因字段增加重写 revision。v4 网络 Starter 不含 openingSnapshot，mapper 保留 null；v5 必须提供 `format=1` 的 openingSnapshot，System 与背景正文允许显式空串，背景数组允许空且保留顺序，块 ID/标题非空白、ID 唯一。v4 携带该字段、v5 缺失或 null 均拒绝；完整响应和 Applied 文件仍共享既有 4 MiB 上限。版本不支持、合法 v4 无 opening 与内容损坏是不同结果；保全旧状态不绕过原远端执行准入。

`EnterpriseExecution.Platform.snapshotSchemaVersion` 保存下载时验证的网络版本，沿原 `StoredEnterpriseExecution` 私有 payload 持久化；历史文件缺失时为 null，不能根据当前 Discovery、字段形状或客户端版本补造。`PlatformEnterpriseService.synchronize` 仅在缓存原版本同时受客户端和最新 Bootstrap 支持时携带 ETag，并允许 304 复用及上报 Applied；已知 v4/v5 均适用。未知版本或服务端不再支持的缓存发起原 generation 的无条件完整下载；异常 304 由 `PlatformControlClient.snapshot` 以 `snapshot_304_without_cache` 拒绝，保全原 Applied，不循环重试或伪造版本。完整响应校验后沿原发布事务保存已验证版本；v4 升级为含开场的 v5 由服务端发布新 generation/release，原发布 hash 不改写。

`EnterpriseSynchronizationService.prepareExecution` 查询 Core Managed State；仅在非零 generation、READY、版本一致且未阻断时，Session owner 才签发执行 lease。缺少配置、版本不同或 Core 要求同步时拒绝本次执行，保留原 Applied，提示用户在空间页手动同步；执行准入不下载或发布 Snapshot。本机缓存足以呈现目录和历史，不代替权威执行检查。请求若收到 `ManagedSnapshotRequired`，原 lease 永久关闭，等待原任务停止和资源释放；更新配置由用户显式触发，旧请求不重放，也不因新配置到达而复活。

#### 配置兼容性与空间导航

长期协议演进与消费者义务集中定义于 [Control Protocol §10.10.3](../../../measix/measix-architecture/docs/10-runtime-foundation/s0/measix-s0-control-protocol.md#10103-snapshot-兼容用户提示与后续演进)；此处只记录 Android 实现入口。

- `EnterpriseSessionController.readPresentation` 的 `canEnterEnterprise` 与 `switchRealm` 共用数据访问规则。有效身份在 CONFIGURATION_PENDING 也可选择企业空间；`EnterpriseAppliedStore` 保存并恢复此选择，无需改变 manifest 格式。个人空间不依赖企业网络配置；过期、撤销、关闭和跨主体限制继续生效。
- `EnterpriseSnapshotCompatibilityException` 携带服务端版本与客户端支持集合；空集合或混合不匹配不误报客户端过旧。Snapshot 成功响应超过 4 MiB 上限，以及 `validateSnapshotContent` 内的解码、身份/ETag、映射校验失败，均标记为 `EnterpriseSnapshotContentException` 并保留 cause。真实读流失败仍为网络错误；HTTP、持久化错误与取消不冒充版本不兼容，其他接口不沿用 Snapshot 的内容错误分类。
- `EnterpriseSynchronizationService` 是共享同步及其瞬态结果的唯一来源，结果绑定 `RealmAccess.Enterprise`。同一 Session 去重，取消等待者不撤销共享工作；主动取消传播并保留此前失败。发布结果在 StateFlow CAS 中复验 Session，旧任务不得覆盖新主体状态。此结果不是第二份配置或执行许可，不另落盘。
- Native 同步与接入后的配置同步消费同次 `EnterpriseSynchronizationCommandResult`：成功、失败已呈现或已被替代；失败不再重复发布通用错误或误报接入资料无效。主动同步保留原异常；执行准入遇到本 Session 已观察到的同步失败时，以 `platform_runtime_synchronization_required` 和原完整诊断拒绝，等待用户手动恢复，取消不转成命令失败。
- 配置同步触发点只有原生空间页的显式同步、Portal 的显式 `refresh` 命令、用户确认首次接入或重新登录后的初始化，以及启动时刚完成同一 Session 未完成 Enrollment Bootstrap 后的一次初始化。初始化失败或配置尚未发布时不自动重试；用户可在空间页手动同步。
- Pending Enrollment 的 Bootstrap 恢复诊断绑定原 Session，只在同一 pending enrollment 或已完成的同一 Session 下呈现。首次初始化的同步失败仍由共享同步投影呈现；Session 被替换后的准入拒绝只记录原异常，不能写入新 Session 的接入恢复错误。
- 已有会话启动恢复、空间切换、页面进入、前后台切换、Portal 打开或关闭、执行准入及模型/MCP/语音的代际 barrier 均不自动同步 Snapshot。启动恢复从 Applied manifest 保留 Session phase、绑定和配置；有效 READY 会话在 Core state 一致时可正常签发 lease。按需刷新凭据、Bootstrap 身份校验、工作区状态和预算查询仍由各自 owner 执行，不属于配置同步。已观察到的同步失败只由成功的显式同步清除，READY 或缓存代际不能绕过本 Session 的失败。
- `EnterpriseApplicationService` 投影同步状态；企业页在原连接区提供简短原因，技术诊断默认折叠且可选择复制，抽屉当前企业入口和企业聊天顶部只增加必要的短提示，点击进入同一企业页面；个人会话不显示该企业异常。较新格式提示更新应用，过旧格式提示管理员处理，非法配置与网络错误分别提示；不捏造最低应用版本或下载地址。成功同步清除对应 Session 的失败，不清库或重新接入。

### 企业接入资料与身份建立

当前接入只有 Core 平台来源。实现入口为 `EnrollmentMaterialParser`、`EnterpriseApplicationService`、`PlatformEnterpriseService` 与 `EnterpriseSessionController`；协议权威是平台架构仓库的 [Control Protocol §8](../../../measix/measix-architecture/docs/10-runtime-foundation/s0/measix-s0-control-protocol.md)。接入成功只建立身份，不替代后续配置同步和执行准入。

#### 格式与信任

Enrollment material 使用 `formatVersion=1`、`kind=PLATFORM_ENROLLMENT`，必填 `platformUrl`、`code`、`expiresAt`，拒绝未知字段、重复解码键、null、未知版本/kind 和非法时间。原始 UTF-8 输入最多 2048 字节；code 为 1–128 个 Unicode 字符。`expiresAt` 接受当前 RFC3339 UTC 子集并按 `Instant` 判断到期。

`platformUrl` 必须是规范化的 HTTP/HTTPS origin，可使用域名、局域网 IP、IPv6 和显式端口；拒绝 userinfo、query、fragment、非根 path 与非法端口。扫码、相册和粘贴都进入同一解析器。解析成功只产生带规范化 origin 的 `EnterpriseJoinConfirmation`，用户确认后才执行 Discovery、Enrollment exchange、Bootstrap 与 Snapshot 同步；取消或被替换的确认不发网络请求。

Core 使用 `enrollment_expired` 区分过期资料，并区分两个 409：一次性码已使用为 `enrollment_already_used`；本机 installation 已绑定另一用户为 `installation_user_conflict`，且不消费新码。Android 的本地到期预检与 Core 响应使用同一过期 reason，并为过期、已使用、格式无效和 installation 冲突显示各自稳定、可操作的主文案；历史手机端来源不再进入提示。普通换用户需要用户在“重置与数据处置”中重置企业连接，使 `EnterpriseAppliedStore.resetLocalState()` 删除本机 installation ID；仅撤销 Core 设备不会改变手机 installation 身份。管理员 hard delete COMPLETED 后是明确例外：旧 Device 已由 Core 删除，同一 installation 可用新 Enrollment 绑定新 principal，不要求 Android 为绕过校验而轮换 installation ID。

#### Session 与恢复

`PlatformEnterpriseService` 编排网络 I/O，`EnterpriseSessionController` 是身份、pending enrollment 和 Applied 状态的串行写 owner。兑换成功后先保存 pending；Bootstrap 核对 Session/User/Device/Deployment 才发布企业身份。临时网络失败保留 pending 并在应用恢复后继续 Bootstrap，不重复兑换一次性 code。若前一 principal 已被管理员删除，pending 阶段继续保留 `IDENTITY_DELETED` 终态；只有 Bootstrap 确认 Core 签发的新 principal 后，Session owner 才在同一 manifest 提交中发布新 Session 并清除旧 reason。首次兑换、手动重试与启动恢复共用同一 Bootstrap 终态处理。若 pending 所属 principal 在 Bootstrap 前也被删除，删除响应会原子移除 pending credential 并保留 `SIGNED_OUT + IDENTITY_DELETED`，下一份新接入资料可重新兑换。相同 username 不代表相同 principal，旧 Access/Refresh credential 和旧域数据不会因此恢复。

installation ID 在一次本机企业连接生命周期内稳定，不能为绕过跨用户限制而自动更换。refresh credential 与 pending idempotency key 由 `EnterpriseAppliedStore` 加密、原子持久化；access token 只在内存。退出与本机重置使用本节前述关闭屏障。

#### 契约同步与验证

Core `api/generated/android/portal/` 是唯一消费输入，Android 在 `app/src/test/resources/contracts/portal/` 固定其 manifest 和当前六份 artifact：Portal contract、Client Feed schema、native/feed vectors、platform enrollment fixture 与 enrollment cases。普通 Android 构建不依赖 sibling checkout；消费副本不得独立演进。

`EnrollmentMaterialParserTest` 验证严格字段、重复键、UTF-8/字符限制、UTC 与 origin；`EnrollmentSharedCasesTest` 直接消费 Core 共享 raw cases；`PlatformSessionNetworkTest` 使用真实本机 HTTP 验证兑换、Bootstrap 恢复、刷新与撤销。二维码库 round-trip、JVM 和本机 HTTP 都不能替代真实 Core + Android 相机/相册/发行版设备验收。

### 2.5 按主体解析与使用偏好

`ConfigurationQueryService` 组合 `UserSettingsDocument`、原 Session 和 Applied；`ConfigurationResolver` 按完整 Deployment/User 产生 `ResolvedConfiguration`，只做纯派生，不落盘镜像。用户定义保持一份；企业助手定义、system prompt 与固定 MCP 只读，本域允许的模型和使用偏好通过 typed 命令写回用户文档。助手模型的默认、空间默认、指定引用必须保持可区分，失效的显式引用不能静默替换。所有列表、查询和命令携带原 `RealmSelection` 或 `RealmAccess`；等待 Settings、数据库、文件或网络后须重新验证原主体，切域返回也不恢复旧授权。

资源选择和修改在 Settings 写锁内依据最新文档、原域与准入规则完成；UI 只消费有效目录和不可用原因，不从显示名称推断持久化引用。企业 `img_*` 是独立图片定义，仅由 resolver 投影到统一图片目录；模型、图片、云端语音和 Direct MCP 执行都须冻结原 Session、generation 与具体 route，并在 Provider I/O 前执行前述受管版本准入。准入成功不代表远端请求成功。

`ModelExecutionService` 在用户配置事务中捕获同一文档的目录与选择，主聊天、子助手和辅助生成沿原域复验。内建搜索选择必须匹配 Provider wire 能力；外挂搜索以同次 `ResolvedConfiguration` 的 SEARCH 选择查用户目录，企业域未选或失效不回退首项。企业固定 Memory Seed 由 resolver 派生，START 捕获后经请求接纳进入 disclosure，不进入可变记忆表。

### 2.6 预算、Portal 与数据边界

生产用量、预算和阻断事实归 Core。`PlatformControlClient.budgets` 经原 Session 查询，Android 不持久化预算；`EnterpriseVM` 的投影绑定原 selection 与 origin，刷新中保留最近成功值，只有请求实际失败才标记 stale 并保留原诊断。MODEL、IMAGE_GENERATION、TTS、ASR、MCP 均按平台 Problem 精确分类；个人请求不能被普通远端 429 冒充企业额度失败。

`runtimeCompleted` 仅由实际使用平台路由的模型、MCP、云端 TTS 和 ASR 调用触发，并保留原企业 access。
企业空间中的个人 MCP、个人模型/语音和设备 System TTS 不触发企业额度刷新；该条件不改变新企业操作的配置及权限检查，
也不取消企业页面打开或用户手动发起的预算查询。刷新信号和查询投影均归 Android，Core 用量统计与协议没有变化。

平台 Problem 仅在 routed 请求、稳定 code、HTTP status 和 `forwarded=false` 匹配时进入企业失败链；保留 blocker、resetAt、requestId、resourceId 和原 detail。失败不回退个人资源或自动重放业务请求。陈旧预算只供显示，执行仍由 Core 准入。

Portal 文档和消息由 `PortalDocument`、`PortalDocumentRegistry` 与原 `RealmSelection`/Session 共同授权；Bridge v3 严格解码，重复键、未知操作或不匹配的文档身份失败关闭。Android 始终打开 Core `/portal/`；标准或企业自定义页面由 Core 选择。旧文档关闭、站点数据清理和地址切换在同一发布屏障内等待确认，迟到回复不能进入新页面。原生媒体和外链动作均由当前文档 owner 再次授权，Portal 不成为配置或会话 writer。

Portal 原生媒体的暂存、额度、取消交接与删除恢复归 `PortalMediaStore`，文档关闭等待网页和硬件清理全部完成；失败保留原 owner 供重试。站点清理只针对本 Session 的 origin 和 Portal Cookie，不全局清除浏览数据；不具备完整站点清理能力的 WebView 明确拒绝打开。真正的 Core grant、系统浏览器、相机/录音和关闭路径需单独设备验收。

Conversation、Memory、Artifact、GeneratedMedia、收藏、分页与统计中的 durable 行均带完整 scope。个人备份只构建个人数据及其共享资源闭合图；恢复时保留最新企业图，文件由各自 owner 交接。系统备份和设备迁移不直接复制混合域存储。具体备份格式、旧数据迁移与字段目录见下文对应载体；文件与会话的写协议仍归各自专题。

## 3. 用户配置结构

`UserSettingsDocument.configuration` 是用户可编辑配置的持久事实；`Settings` 还包含运行消费所需的投影。
字段定义位于 `SettingsStore.kt`，迁移与读取规范化分别由 `UserSettingsMigration`、`SettingsNormalization` 维护。
不在这里复制完整属性清单，但必须区分四种默认语义：Kotlin 构造默认、旧 DataStore key 缺失默认、
`materializeForRead()` 后的有效默认、企业字段未提供。不能用其中一种推断另一种。
例如 `Settings()` 的 `modeInjections` 构造默认包含 `DEFAULT_MODE_INJECTIONS`，空存储经过迁移后却为
空列表，读取规范化不会补回 Learning Mode。正常持久化写入 `user_settings` 的类型化结构；
JSON codec 使用 `ignoreUnknownKeys=true` 和 `encodeDefaults=true`。

| 配置类别 | 语义与边界 |
| --- | --- |
| 显示与主题 | `DisplaySetting`、主题及自定义主题是用户偏好；系统栏和布局由 [UI 策略](ui-architecture.md)解析 |
| 模型与派生任务 | Chat、标题、摘要、建议、生图、附件识别等选择分别解析，不能用当前聊天模型替代失效的显式引用 |
| 资源目录 | Provider、Assistant、提示规则、MCP、语音和搜索定义保留各自 ID；目录存在不表示当前域获准使用 |
| 本域使用选择 | `ResourceSelections` 与助手使用偏好按主体保存，不能反写企业受管定义或全局用户选择 |
| 备份与内部状态 | 用户备份配置与提醒独立于同步、清理和恢复状态；内部状态不成为 UI 可任意回写的聚合 |

`null`、空集合、未设置和显式失效引用必须按各字段协议区分；不得根据显示文本反推配置语义。
助手模型的三态、工具和提示字段见 [助手配置](assistant-configuration.md)。

## 4. 资源定义与专题边界

### 4.1 显示偏好

`DisplaySetting` 保存消息外观、自动滚动、触觉、更新提示等显示偏好。页面消费 query/UiModel，
外观选择由 `AppearancePolicy` 统一解释；颜色模式和 AMOLED 的 SharedPreferences 归属见下文。
显示正则、字体或气泡设置不修改 durable 消息或 Provider 请求；请求侧正则另见 [助手配置](assistant-configuration.md)。

### 4.2 Provider 与 Model

Local `ProviderSetting` 是三种密封类型：

| 类型 | 公共字段 | 类型特有字段 |
|---|---|---|
| `OpenAI` | `id/enabled/name/models/balanceOption` | `apiKey/baseUrl/chatCompletionsPath/useResponseApi/includeHistoryReasoning` |
| `Google` | 同上 | `apiKey/baseUrl/vertexAI/useServiceAccount/privateKey/serviceAccountEmail/location/projectId` |
| `Claude` | 同上 | `apiKey/baseUrl/promptCaching/promptCacheTtl` |

`balanceOption` 为 `{enabled=false, apiPath="/credits", resultPath="data.total_usage"}`。`builtIn`、描述 Composable
是读取时恢复的 transient 元数据，不属于持久 wire。

每个 Local `Model` 的完整结构：

```text
Model
  modelId: String                    # provider upstream selector
  displayName: String
  id: ConfigurationReference         # stable definition reference
  type: CHAT | IMAGE | EMBEDDING
  customHeaders: CustomHeader[]
  customBodies: CustomBody[]
  inputModalities: TEXT | IMAGE []
  outputModalities: TEXT | IMAGE []
  abilities: TOOL | REASONING []
  tools: search | url_context | image_generation []
  providerOverwrite: ProviderSetting?
```

`providerOverwrite`、custom headers/body、base URL 和 API key 都是 Local 高级覆盖；它们不能进入 Managed Model。
`CustomHeader.value` 以及嵌套 `providerOverwrite` 也可能直接包含 secret，不能当成公开企业 definition。
辅助结构为 `CustomHeader {name, value}`、`CustomBody {key, value: JsonElement}`。

### 4.3 Assistant、Prompt、工具与引用

`Assistant` 的字段、默认值、运行语义及本域使用编辑只在 [助手配置参考](assistant-configuration.md)维护。此处只记录它引用的其他配置事实：`QuickMessage`、`AssistantRegex`、`ModeInjection`、MCP server、Skill、Workspace 与本地工具开关仍属于用户文档或各自 owner，不因 Assistant 引用而转移写入所有权。

`presetMessages` 保存完整 `UIMessage` 图，可能包含 Provider metadata、usage、terminal state 和多模态/工具 parts；它不是可直接下发的轻量示例文本。企业 Starter 使用独立受限定义，不把本地运行历史或 URI 当作受管配置。其 `EnterpriseStarterOpeningSnapshot` 保存完整领域 System 与有序 `EnterpriseStarterInitialContext(id,title,content)`，mapper 保留原文；`EnterpriseStarterUiModel.openingAvailable` 只投影可用性，不向目录热路径复制长正文。

`ConversationApplicationService` 是 Draft 开场选择、刷新、清除的 application 入口；`BindDraftOpening` 在原 Conversation owner 中保存唯一绑定，并以非持久 selection token 做 CAS。选择提交后的输入接纳失败只补偿本 token，不能清掉后续选择；`ChatVM` 使用同一输入 Mutex 串行选择、刷新、清除、切助手和首次发送。跨页 `StarterOpeningReference` 只含定义摘要和原发布来源，初始化复验完整定义摘要后绑定，不传 System/背景正文。`requireCurrentStarterOpening` 首发比较仍获准的完整定义，独立 generation 变化不阻断；内容变化或撤销保留草稿并给出原因。目录与选择摘要均不复制长正文，详情由原授权查询按需投影。

### 4.4 企业 Starter 与会话开场

Starter 是企业发布的任务入口；`prompt` 是供用户编辑的起始输入，Opening 是会话绑定的开场定义副本。
二者都不是运行记忆。Snapshot v4 的 Starter 只预填提示词；v5 要求 `openingSnapshot`，包含完整
`systemPrompt` 和有序 `initialContexts(id,title,content)`。严格解析拒绝版本与字段不匹配、重复背景 ID；
Android 保留发布值的空串、空白、顺序和字面内容，不把已发布的空 System 重新解释成“继承助手”。
发布端如何编制默认值归 Core 合同；Android 消费 Core 导出的样例，不在客户端重新编译发布定义。

| 阶段 | 当前行为与负责入口 |
| --- | --- |
| 浏览与导航 | 目录包含标题、描述、完整起始 prompt 与 opening 可用性；`StarterOpeningReference` 仅携带定义摘要与原发布身份，opening 的 System/背景正文经授权 query 按需读取 |
| Draft 选择 | `ConversationApplicationService` 经 `BindDraftOpening` 在内存绑定；selection token 比较并交换（CAS）防止迟到选择覆盖新选择，输入拒收只补偿本次 token |
| 显式刷新 | 复验最新完整定义并更新绑定，不再次追加起始提示词；选择 v4 入口会清除已有 Draft opening |
| 首次发送 | `requireCurrentStarterOpening` 比较助手、当前准入和完整 Starter 定义；单独 generation 改变不拒绝，定义变更/撤销则保留草稿与附件并报告原因 |
| 持久化 | 首次 `AppendUserMessage` 在同一事务保存会话根、opening 和用户实际输入；START 是之后的独立命令，其失败不撤销已提交输入 |
| 已有会话 | 再选 Starter 只追加提示词，不替换根 opening；删除首条消息不删除根 opening，Fork 复制该事实而不复制执行任务 |

`ConversationOpening` 保存原助手、releaseId、generation、snapshotHash 和完整 Starter 定义，
不保存凭据、Session 或执行租约。原始 prompt 与用户编辑后发送的 USER 是不同事实；未发送 Draft 不落库。
三个界面入口（空间预览、聊天快捷菜单、空白聊天卡片）使用同一 application 协议，不自动发送。

Opening 的 System 只在当前会话助手等于原助手时参与选择，优先级低于合法非空会话覆盖、高于助手定义；
切走后不应用，切回且获准时可再次应用。模板每个 START 按当次变量渲染，同一 Turn 固定。
背景仍是会话输入，按请求窗口投影，不因换助手变成新用户指令。其模型格式和位置见
[提示词与工具](prompts-and-tools.md)，接纳与历史来源见 [请求上下文](request-context.md)。

### 4.5 搜索配置

```text
SearchCommonOptions
  resultSize = 10

SearchServiceOptions
  BingLocalOptions { id }
  TavilyOptions   { id, apiKey, depth="advanced" }
  SearXNGOptions  { id, url, engines, language, username, password }
```

Search 包含本地用户 API key/URL/账号，不作为 Model/MCP 路由或企业凭据载体。
当前 `SearchServiceOptions.DEFAULT` 在类加载时由 `BingLocalOptions()` 产生随机 UUID，不具备跨安装/跨设备稳定性。

### 4.6 语音配置

用户语音定义保存在 Settings，企业 TTS/ASR 定义保存在 Applied 配置，平台执行绑定单独管理。
`selectedTTSProviderId` 与 ASR 选择由本域解析；公共播放倍速属于播放偏好，不是服务端 voice。
类型、协议条件、交互冻结、取消与硬件清理统一见 [语音架构](speech-architecture.md)。

### 4.7 MCP

```text
McpServerConfig
  SseTransportServer            { id, commonOptions, url }
  StreamableHTTPServer          { id, commonOptions, url }

McpCommonOptions
  enable
  name
  headers[]                     # Local credential/header
  toolPolicies[]                # name/enable/needsApproval；不含远端 schema
  oauth?                        # Local OAuth state

McpOAuthState
  revision, enabled, clientId, clientSecret,
  authorizationEndpoint, tokenEndpoint, registrationEndpoint, scope,
  accessToken, refreshToken, expiresAt

McpToolPolicy
  enable, name, needsApproval

McpCatalogStore                 # 独立 mcp_catalog DataStore
  McpCatalogSnapshot
    serverId, revision, definitionDigest, catalogDigest,
    tools[]
  McpCatalogTool
    name, description?, inputSchema
```

Local OAuth/headers 保持 Local。Managed MCP 只能携带平台 `runtimePath` 和 `authOwnership`，不能把 enterprise access token
写回 `headers`/`oauth`。

MCP 配置写入口由 `McpApplicationService` 统一调用 `validateMcpHeaders`；批量导入全部校验后才原子写入，合法 header value 不 trim。仅编辑器本次新增的完全空行可作为未保存草稿忽略；历史非法行不会被静默删除。稳定 reason 与行号不包含凭据值，保存/导入失败保留编辑输入。连接前与企业每请求复验边界见 [MCP 架构](mcp-architecture.md)。

### 4.8 备份配置

WebDAV、S3 和提醒配置属于用户 Settings。个人备份包含用户自己的服务凭据，不能纳入企业
Snapshot、执行绑定或 Session。归档格式、混合域隔离、冷恢复和系统备份排除规则统一见
[备份与恢复](data-persistence.md#个人备份与恢复)。

## 5. Settings 之外的 Android 配置

### 5.1 SharedPreferences

主题、语言、启动行为、侧栏和搜索排序属于本机显示偏好，由各自的偏好入口读写，不进入企业发布配置。
CrashHandler 的独立 `crash_handler` SharedPreferences 保存 `crashed` 和截断后的 `stacktrace`，属于崩溃恢复状态。
字段名与默认值以相应声明为准，不能将崩溃标记当成用户配置迁移。

### 5.2 Room 中的配置性事实

- `WorkspaceEntity`：`id/name/root/shellStatus/toolApprovals/createdAt/updatedAt/lastAccessAt`；
- `ConversationEntity`：以 `assistantId` 固定会话归属，并可保存 `customSystemPrompt`、`modeInjectionIds`、`workspaceCwd`；
- `conversation_model_context`：类型化的模型上下文条目；请求贡献由 Assistant variant 拥有并保存因果定位，预置/摘要来源指向自身消息；不属于 Settings、UI 投影或独立导出域；
- `FolderEntity`：`id/assistantId/name/sortIndex/createAt`，作为某个 Assistant 下的 Local 会话分组；
- Workspace shell 状态和时间戳是生命周期状态，`toolApprovals` 是 Local 用户覆盖；
- 会话覆盖只有在 Assistant 对应 allow 字段开启时才生效。

`WorkspaceConfig` 的文件读写、列表与搜索上限是代码内置运行限制，不是持久化 Settings，也没有企业下发入口。具体执行边界由 [Workspace](workspace-architecture.md)维护。

这些事实归原配置或用户数据 owner，企业域按完整主体保存会话与文件夹；Workspace 注册及目录为显式共享配置，不属于企业 Snapshot 或远端会话同步。

### 5.3 Skills 与文件资源

- Skill 由 `filesDir/skills/<name>/SKILL.md` 及同目录资源构成；
- frontmatter 至少提供 `name/description`，可带 `compatibility`；
- `Assistant.enabledSkills` 只保存 Skill 名称引用；
- 自定义字体、Assistant 头像/背景、上传和生成图片分别由自己的文件 owner 管理。

`SkillManager` 是 Skill 目录身份、文件树、读取、校验和发布的唯一 owner，UI 与工具只持有 typed metadata/file/result，不取得宿主路径。`SkillFrontmatterParser` 使用 SafeConstructor 与 loader limits，拒绝重复键并限制 alias、嵌套和文档大小。

Skill 文本在 owner 边界先做 4 MiB bounded byte read，再 strict UTF-8 解码；超限、非法编码和 IO 分别返回 typed failure。写主文档/支持文件及删除支持文件时，先复制完整已发布目录到 staging，拒绝 symlink/path escape，校验 frontmatter 与预期 name 后 rename 发布。更新 SKILL.md 保留支持文件；中断 backup 由下一次 owner 访问恢复或清理，歧义时拒绝继续。

ZIP bundle 完整解析并拒绝重复 Skill name 后，复制整个 Skill root、一次 root swap 提交，失败或取消不留下部分更新；root backup 同样可恢复。导入上限为 16 MiB 输入、512 entries、单文件 4 MiB、累计解压 32 MiB。GitHub 导入保留支持文件原字节，仅主文档 strict UTF-8 解码，因此二进制资源不被文本化。SKILL.md 不能作为普通支持文件删除，整项删除经 deleteSkill 与 enabledSkills 引用清理协议。

SkillManager 的 `saveSkill`、`saveSkillFile`、`saveSkillFileBytesAtomically` 与 `deleteSkillFile` 使用单一 suspend 写入口，在 staging 校验完成、发布前检查取消。预期拒绝返回稳定结果码，非预期 IO 抛出原异常，补偿失败保留 suppressed；不再通过 nullable metadata、Boolean 或无 cause 的 IO_FAILURE 丢失原因。GitHub HTTP 429 或带明确限流信号的 403 归为 RATE_LIMITED，普通 403 保留 HTTP 拒绝；status、有限长度响应 detail 与网络 cause 进入同一可复制诊断。搜索只按 name/description 过滤当前 metadata 展示，不更改 enabledSkills 或文件身份。

## 6. 用户配置引用图与运行依赖

```text
Settings.providers[].id
  └─ providers[].models[].id
       ├─ Settings.{chat,fast,title,imageGeneration,suggestion,
       │            attachmentInspection,compress}ModelId
       └─ Assistant.chatModelId

Settings.assistants[].id
  ├─ Settings.assistantId                    # 个人新会话选择
  ├─ Conversation.assistantId                # 已有会话权威归属
  ├─ Folder.assistantId                      # 同一 scope 内会话分组
  └─ Assistant.allowedSubAssistantIds

Assistant
  ├─ tags[]              → Settings.assistantTags[].id
  ├─ mcpServers[]        → Settings.mcpServers[].id
  ├─ modeInjectionIds[]  → Settings.modeInjections[].id
  ├─ quickMessageIds[]   → Settings.quickMessages[].id
  ├─ workspaceId         → Room WorkspaceEntity.id
  └─ enabledSkills[]     → filesDir/skills/<name>

Settings.selectedSearchServiceId → searchServices[].id
Settings.selectedTTSProviderId   → ttsProviders[].id
Settings.selectedASRProviderId   → asrProviders[].id
Conversation.folderId            → Folder.id
```

已发布并参与本地引用兼容的 Built-in stable ID：

| 事实 | ID |
|---|---|
| Auto Model sentinel | `b7055fb4-39f9-4042-a88a-0d80ed76cf08` |
| 默认 Assistant | `0950e2dc-9bd5-4801-afa3-aa887aa36b4e` |
| System TTS | `026a01a2-c3a0-4fd5-8075-80e03bdef200` |
| Learning Mode injection | `b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74` |
| OpenAI / Gemini / Claude / DeepSeek Built-in Provider | `1eeea727-9ee5-4cae-93e6-6fb01a4d051e` / `6ab18148-c138-4394-a46f-1cd8c8ceaa6d` / `3a7c8e2f-1b4d-4e5f-9a6b-7c8d9e0f1a2b` / `f099ad5b-ef03-446d-8e78-7e36787f780b` |

预设主题使用 `sakura/ocean/spring/autumn/black/minimal/claude` 稳定字符串 ID。相反，默认 Bing Search 当前不是
稳定跨设备 ID，不能被企业引用。

读取物化会补齐 Built-in Provider/Assistant/System TTS、按 ID 去重，并清理部分失效引用；它不会把清理结果静默写回磁盘。
跨记录删除、授权清理和默认选择修正仍需由对应 application service 在同一次 `updateLocal` transform 中完成。

## 7. 关键架构文件

| 边界 | 文件 |
| --- | --- |
| 用户配置、偏好与提交发布 owner | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsStore.kt` |
| 读取物化与持久化归一化 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsNormalization.kt` |
| 个人持久化规范化与配置拒绝类型 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsWriteRules.kt` |
| 按域有效读模型与纯解析器 | `app/src/main/java/net/weero/measix/pilot/data/configuration/ResolvedConfiguration.kt` |
| Assistant 配置模型 | `app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt` |

## 8. 维护与验证

以下变化必须同步本文：Settings 字段与默认读取语义、Local/Managed owner、有效读模型、持久化与备份边界、稳定引用
规则，以及已经实际接入 Android 的平台 typed definition。

修改当前 Android 配置链至少验证：

- Settings serialization、缺失 key 默认、读取物化和旧备份兼容；
- 用户文档、按域解析、write lock 与“落盘后发布”顺序；
- Local 备份不包含 Enterprise Binding、credential 或 Applied Snapshot；
- 无效、过期、撤销或损坏 Managed payload 的 fail-closed/LKG 行为；
- 删除与恢复在 Provider、Assistant、MCP、TTS、ASR、Search 和文件引用之间保持原子；
- UI 与运行时只消费同一个 effective read model，不建立第二 owner。

构建/JVM 验证不替代真实平台存储、签名资源和恢复场景的设备验收。新增生产同步路径时，应补充对应的服务端互操作验证。

企业远程文件能力独立于 Applied 配置及 MCP 发布。CONFIGURATION_PENDING 中的有效 Session 可查询工作区并管理文件，
不得为此放宽模型执行准入或向 Settings 写入另一份工作区状态。客户端请求随原 RealmSelection、Session 和连接变化撤销；
退出与地址变更通过 `RemoteWorkspaceService` 排空请求及未交付资源，详见 [Workspace](workspace-architecture.md#11-企业远程文件)。
