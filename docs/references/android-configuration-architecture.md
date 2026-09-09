# Android 配置架构与资源边界

本文定义 Android 当前配置的 owner、持久化载体、字段目录、引用关系和内部签名 overlay 边界。Assistant 逐字段运行语义见 [助手配置](assistant-configuration.md)，总体依赖与启动恢复见 [应用架构](application-architecture.md)。待实现的企业阶段、字段映射与验证要求见 [企业集成计划](../dev/android-enterprise-integration-plan.md)。

## 1. 配置组成

```text
Built-in defaults + Local shadow + 已验证的 Managed overlay
  → SettingsStore.effectiveSettings
      → 配置、生成与 UI consumers
```

完整配置分属 DataStore、轻量 UI preferences、Room、资源文件和可重建缓存，不能整体替换为远端 Settings JSON。当前 ManagedConfigurationEnvelope 是内部签名 overlay；生产 Realm、Enrollment/Session、平台 Snapshot 和同步入口尚未实现，不能复用本地 Provider/MCP 凭据冒充企业身份。

## 2. 配置 owner 与读写架构

### 2.1 Owner 表

| Owner | 当前载体 | 负责的事实 | 是否进入普通本地备份 |
|---|---|---|---|
| Built-in | `DefaultProviders.kt`、默认 Assistant/TTS/Prompt/Theme 常量 | 安装包内默认资源和反序列化默认值 | 不单独备份；读取时补齐 |
| 用户配置与偏好 | Preferences DataStore `settings` 的 `user_settings` JSON | UserSettingsDocument 内分开保存用户定义、公用显示偏好、按主体的选择、内部状态 | 当前仍以个人 Settings 投影导出 `settings.json` |
| Local UI preference | SharedPreferences `MeasixPilot.preferences` | 语言、明暗、启动/布局/搜索排序等轻量偏好 | 否 |
| Local durable resource | Room + `filesDir` | Workspace、Skill、会话级覆盖、资源文件 | 仅备份协议明确包含的域 |
| Local runtime cache | `cacheDir/lru_key_roulette.json` 等 | 可重建的 key 轮换/发现缓存；不是配置真源 | 否 |
| Managed prototype | `filesDir/managed_configuration/` | 当前代码中的签名通用 overlay 原型 | 否 |

最近聊天 ID 位于 `ScopedUserPreferences.lastConversationId`，按个人域或完整来源/Deployment/User 保存。
`ConversationHistoryPreferenceMigration` 在旧 Settings 键迁移之后，把已发行的 SharedPreferences `lastConversationId`
一次性归入个人域；已有域内值优先，DataStore 提交成功后才删除旧键，失败可重试。运行时不再读取全局旧键。
普通个人配置更新保留各域最近聊天与企业使用偏好；新聊天 Draft 不写入最近聊天，删除后恢复缺失会话由页面明确处理。

`lru_key_roulette.json` 当前以原始 API key 作为 map key 保存轮换时间。它虽然不是配置真源也不进入手工备份，仍是
未加密 secret 副本；企业 credential 不能复用这条缓存协议。

### 2.2 单一读写路径

`SettingsStore` 是当前应用级配置聚合的唯一 owner：

```text
updateLocal(latest Local shadow transform)
→ managed lock/write rule
→ normalizeForPersistence
→ canonicalizeForDataStore
→ UserSettingsDocument.withPersonalSettings
→ one dataStore.edit（user_settings）
→ durable success
→ materializeForRead
→ EffectiveSettingsResolver
→ effectiveSettings
```

关键不变量：

- 既有页面和执行消费者只读 `effectiveSettings`；企业配置查询端口的派生解析见下节，不由消费者自行合并 Local 与 Managed；
- 写入失败或取消时不得发布领先于磁盘的内存状态；
- managed mutation 必须在 Store/commit boundary 拒绝，UI disabled 只是展示；
- Local shadow 不因 Managed 同 ID 覆盖而删除，Managed remove/disconnect 后应恢复；
- `restoreLocal()` 和 `snapshotLocal()` 只操作个人 Settings 投影；恢复经 `ArtifactStore.restoreSettingsReferences` 校验配置文件引用；
- `pendingAssistantDeletions` 在 Settings 投影中为 `@Transient`，在 UserSettingsDocument.internalState 中持久化，恢复普通 Settings 时不得清空。

新增文件引用必须通过 `ArtifactStore.updateSettingsReferences`：Settings 写锁先于 Artifact 生命周期锁，校验后保持文件锁直到 DataStore 回执与创建所有权交接完成。普通配置提交拒绝绕过该入口新增引用。完整引用覆盖用户定义和所有主体保留的使用覆盖，不从 `effectiveSettings` 的当前显示投影推断；具体文件保留、删除与恢复协议见 [多模态与持久化](multimodal-context-and-turn-durability.md)。

### 2.3 用户配置文档与迁移

`UserSettingsDocument` 的 `schemaVersion`、`configuration`、`preferences`、`internalState` 为必需字段，缺失或版本不支持时拒绝读取；其 schema 与 Room、应用和备份版本独立。

- `UserConfiguration` 保存现有 Provider/Model、TTS/ASR、MCP、Search、Assistant、注入/QuickMessage/标签、辅助提示词、备份连接及 UserProfile。用户昵称/头像不重复放入显示偏好。
- `UserPreferences.common` 保存 DataStore 原有的外观、DisplayPreferences、播放速度和提醒；`scopes` 以完整 ConfigurationScope 保存 ResourceSelections、企业域的 AssistantUsagePreferences 和 GatewayPreference。Gateway 偏好随来源、Deployment、User 与资源 ID 隔离，未交付的顶层 gateways 字段已删除，不将无用户归属的原型偏好猜测归入某个主体。SharedPreferences 表中的偏好仍由原 owner 管理。
- `common.configuration.ConfigurationReference` 保留原用户 UUID 或企业来源/原始字符串 ID，不生成替代 UUID。个人引用的 JSON 仍是原 UUID 字符串；企业引用序列化为 `managed~来源类型~来源标识~deploymentId~资源ID`，完整保留来源和原始资源 ID。ConfigurationScope 企业身份包括 sourceNamespace/deploymentId/userId，本地与平台来源不相交。当前实际 Settings 消费链只投影个人选择；正式本地企业接入、状态和 Portal 已实现，企业资源消费者继续按实施方案接线。
- `UserSettingsMigration` 在旧 OCR/Search/MCP 迁移之后，将旧键转换并在同一次 DataStore 迁移提交中移除；MCP Catalog 的 pending staging 保留给 Catalog owner。旧资源或 tombstone 解码失败会中止迁移，原输入不变。正常读写只访问新文档，没有旧键 fallback。
- 用户定义及其配置绑定只接受 User 引用；按企业保存的选择允许 User 或同 authority 的 Enterprise 引用，拒绝外域引用。会话、Message、Turn、文件与 Workspace 的自身 ID 继续使用 UUID。
- 已删除无功能消费者的 developerMode 字段；迁移清除旧 developer_mode 键，旧备份中的字段由 JSON codec 忽略。构建类型标记不使用此配置。
- 写个人配置会保留其他主体的偏好；EffectiveSettingsSnapshot 仍为只读内存投影，不另落盘。

背景写入由 `AssistantBackgroundService` 接受明确的目的：页面持有原 `RealmSelection`，生成工具持有原 `RealmAccess`，共享定义编辑器显式指定 User 助手。个人域写助手定义，企业域写完整主体下的 `AssistantUsagePreferences.background` 并关闭渐变，不复制整份个人配置，也不修改企业下发定义。生成背景按原域和图库 ID 读取；查看器读取 `ImageSource` 后复验目的域。`AssistantPreferenceChange.Background` 复用 Settings 唯一 typed 写协议，经 `ArtifactSettingsCoordinator` 与 `ArtifactStore.commitSettingsRoots` 在 Session → Settings → Artifact 顺序中验证引用、提交并移交创建 pin。失败精确回收未发布副本；旧图片由 Artifact 按所有域的引用统一回收。

### 2.4 本地企业接入基础

`data/enterprise` 提供独立的企业配置、接入资料及持久状态组件。DataSourceModule 注册其单例，私有存储位于 noBackupFilesDir/enterprise；ApplicationRecoveryCoordinator 在 Settings 就绪之后恢复企业状态。企业校验错误由企业 owner 发布，不阻塞个人数据恢复。正式入口、Portal、会话与模型执行已接入企业域；MCP、Speech 等消费者仍在迁移，旧 Managed 原型尚未整体退休。

- `EnterprisePackageCodec` 校验 formatVersion=2 的完整本地资料：显式五项准入、资源/助手引用、默认选择与完整运行绑定，顶层可携带 feedSeed 初值。该格式独立于接入资料和平台 Snapshot；旧企业原型格式拒绝，不保留双格式兼容。定义与运行连接分开；异常不带可能含凭据的原始反序列化错误。
- `EnterpriseAppliedStore` 在调用者指定的私有目录暂存不可变配置、绑定及独立 Feed 文件，以 schemaVersion=3 的单个 manifest 原子发布身份、版本、当前空间及退出原因。只接受当前企业格式，未交付原型的旧版本明确拒绝，不自动改写身份或推断退出原因；接入资料和完整配置使用各自独立版本。Feed 指针按来源/Deployment/User 保存，退出保留且不可跨主体读取。提交显式同步文件并核验实际 manifest，不能把 AtomicFile 仅记录日志的失败当作成功。
- `EnterpriseSessionController` 是上述存储的串行写 owner。enrollLocal 处理已验证接入（配置不可用时待配置），importLocal 处理显式原生安装后的应用，synchronize 只接受原 Session 的候选。无 registerIdentity/applyPackage 通用旁路。主动退出复验原企业 Session 与确认时的 RealmSelection；到期/撤销绑定原 Session，不依赖当前选中空间。beginExit/beginInvalidation 先发布 CLOSING 并撤销新动作及 binding lease；finishExit 只接受原 token，在 lease 全部释放后发布 SIGNED_OUT 或 REAUTH_REQUIRED。配置损坏时仍能依靠已验证身份退出；重启保留 CLOSING，不能在运行恢复前假报已退出。
- `EnterpriseExitService` 在应用作用域持有已接受的退出任务并合并重复请求；页面取消不取消退出。释放 Session 锁后并发取消并等待原 Session 的 Portal、同步、主/子 Runtime 与辅助生成，通过既有 TurnFinalizer 提交终态，并核验该域没有未完成运行事实后才完成 Session 退出。任一非取消失败不跳过其他 owner 的收口，失败保留原 CLOSING 和可显式重试的投影；到期准入写盘失败单独记录原授权与原因，不假报已接受。自动观察器与重试共享同一任务准入锁。退出成功后的版本文件清理失败单独返回维护结果。此 owner 已接入启动恢复与授权到期观察；正式入口与 Portal logout 已接线，媒体清理纳入原文档关闭屏障。
- LocalEnterpriseSource 通过 LocalEnterpriseConfigurationStore 唯一管理 noBackupFilesDir/local_enterprise_service 中的安装目录和来源配置。这是模拟服务的来源事实，不是第四个客户端配置区或 enterprise_local。目录按完整主体保存身份、generation 与内容摘要引用，配置使用独立不可变文件；身份读取不依赖配置可读。场景修改按来源 revision 做 CAS 并推进 generation，尚未同步时客户端 Applied 保持原值。损坏来源不能静默重置为随包示例；显式完整文件导入可以在 CAS 校验后以更高 generation 修复。
- EnterpriseSynchronizationService 在 Session 准入锁内登记并合并同一主体/Session 的同步请求，读取来源候选后交给 Session owner 复核原授权并原子应用，不创建、续期或切换 Session。成功提交同时保存 lastConfigurationSyncMillis；同版本检查复用 Applied revision，失败保留原配置和成功时间。取消等待者不会回滚或重放同步；退出通过 cancelAndAwait 取消原 Session 的在途同步，在 Session 锁外等待。同步同会话更新保留离线状态。
- EnterpriseBindingLease 由 EnterpriseSessionController.captureBindings 签发；捕获时校验发起动作时的完整 RealmAccess，读取绑定后复验 Session 有效期；相同主体重新接入不能授权旧请求。在途 lease 保留捕获的旧绑定直至最后一个引用释放。释放立即禁止继续取绑定；并发调用均等待真实清理，清理失败保留 owner 并允许重试，finishExit 不能越过失败的清理。lease 不充当执行授权，运行链接入时还需统一准入门禁。
- `LocalEnterpriseSource` 统一验证一键、粘贴和扫码解析后的公开示例接入资料，并支持私有整包导入。`docs/examples/enterprise.local.example.json` 是唯一公开示例输入，通过构建任务进入 assets；根目录 `enterprise.local.json` 被 Git 忽略且不参与打包。配置内 HTML 与 EnterprisePortal 原型已删除。Portal local 消费包固定在 app/src/main/enterprisePortal，保留上游 build-identity.json；PrepareEnterprisePortalAssets 检查来源、版本、完整文件集合和 SHA256 后，为全部构建生成 enterprise_portal assets。普通构建不依赖 sibling checkout；当前随包协议为 Bridge v3 / 本地读取 v2，原生宿主见下节。

`EnterpriseFeed` 集中管理草稿创建/编辑、发布、撤回与日期查询，EnterpriseSessionController 使用原 Session 写锁和同一 manifest 发布其结果。动态不再属于 EnterpriseConfiguration；仅改变动态不推进配置 generation 或替换 Applied。Seed 仅在该主体没有 Feed 时初始化，重复导入、同步和重入不恢复撤回内容。公开 revision 只随发布/撤回变化；草稿编辑只改变存储 revision。查询遵守 Client Feed 字段、枚举和 eup_UUIDv4 标识，按企业时区日历日界线过滤，ETag 包含主体、公开 revision、规范化日期/limit 与返回表示。查询接受原 RealmSelection，在同一次 Session 锁内验证完整主体、母 Session、选中空间与 selectionRevision；当前本地 Portal 使用 modified/notModified 消息结果，不构造 HTTP 304。

原生资料使用独立 EnrollmentMaterialParser 对齐 formatVersion=1 的 PLATFORM_ENROLLMENT / LOCAL_EXAMPLE_ENROLLMENT；原文上限 2048 UTF-8 字节、严格字段与重复键验证。本地资料不包含 userId，运行时由 LocalEnrollmentAuthority 领取一次性 code；该模拟服务账本位于 noBackupFilesDir/local_enterprise_service，独立拥有消费事实，Session 仍只归 EnterpriseSessionController。详见 [接入资料契约](enrollment-material-contract.md)。真实平台资料当前只解析并返回明确不支持，不进入本地接入；完整私有配置使用自己的版本。

PrepareEnterpriseExampleAssets 从公开完整模板派生 enterprise.local.identity.json，只用于安装目录的首次初始化。原生完整文件导入可安装其他本地来源/Deployment/User；换主体必须先退出，扫码和粘贴无安装权限。票据固定登录身份，同企业的不同用户分别存储。重新接入读取来源当前发布版本，没有“旧安装包时保留较新 Applied”的特殊分支。配置文件缺失/损坏时，已兑换身份可发布 CONFIGURATION_PENDING，不能因读不到配置而猜测用户。有效导入的来源发布成功但客户端应用失败时，LocalEnterpriseImportResult 明确返回来源 revision 与失败原因，后续可重试同步；不会谎报已应用。

### 模型执行中的配置与私有连接

助手的 `builtInSearch` 可选偏好由 `Model.withAssistantSearch` 派生为请求工具，个人定义与企业使用偏好保持原有存储归属。
缺字段继承原模型 Search，不迁移或重写旧 `Model.tools`；显式覆盖不影响共用模型的其他助手。
子助手从 Caller 借用模型时，按 Target 偏好重新解析原模型。`supportsBuiltInSearch` 判断实际传输能力，
模型执行捕获拒绝无法兑现的内建搜索选择；配置意图保留，不能把 wire 忽略当成功。

`SettingsStore.withExecutionConfiguration` 在用户配置事务锁内读取同一 `UserSettingsDocument`，派生原 scope 的目录、用户配置和内容摘要 revision；该捕获不增加持久化配置区。`ModelExecutionService` 是主聊天、子助手、附件识别、图片生成及标题/建议/手动摘要模型捕获与逐请求准入入口，企业请求固定原 Session 与 Applied binding revision。子助手准备完成时统一使用新捕获的配置构建 prompt、披露与工具；preflight RunSpec 只用于复验，不混入另一份 Settings。

企业本地示例模型使用既有 RequestAssembler、StepRunner、流式合并和 Turn 提交链，返回明确的模拟文本；图片只确认接收，不声称完成真实视觉推理。私有模型 binding 复用 OpenAI Chat/Responses、Claude、Google 的既有 wire builder；IMAGE 模型使用独立 `OPENAI_IMAGES` binding，复用生成与编辑接口。`RequestCredentials.Fixed` 只存在于请求参数，不轮换、不写用户 key cache，也不序列化到 Settings 或普通备份。自动认证与同名私有 header 不能同时配置；用户 header 不得改写认证、Host 或企业自有 header，用户 body 不得指定模型回退或路由。

辅助模型选择从原域的角色配置解析：标题/建议未配置时尝试 fast，再使用原助手的聊天模型；摘要未配置时使用原助手聊天模型。只有 Personal 的历史 `DEFAULT_AUTO_MODEL_ID` 等同未配置，显式缺失、被撤权或类型不符的引用不回退。辅助角色不受企业助手固定聊天模型的绑定限制，但每个请求仍复验原助手和所选资源准入，保持原 wire shape/企业 binding revision。`ModelExecutionSnapshot` 是进程内执行快照，不持久化私有传输数据。聊天、附件识别与已启用的图片工具在同次配置读取中捕获，工具借用只含 `execute` 的 `ModelRequests`；Runtime 保留唯一模型 lease 和企业 binding，暂停继续只移交这一个资源 owner。模型 lease 在取得 binding 前登记到原 Runtime；辅助任务清理失败不能丢失重试 owner。

私有请求带无身份、无凭据的 `PrivateRequest` 标记，现有 HTTP 日志入口跳过该请求。共享网络边界在 OkHttp 跟随跨 origin 重定向前拒绝请求，避免 Google/Claude 与自定义私有 header 被转发；个人请求保持原日志和重定向行为。图片 URL 结果的后续下载继承私有日志标记，但不转发原认证和企业 header。企业 MCP 已接入原 Session/binding/interaction 的执行准入与版本屏障，管理页按域投影仍在实施；Speech 尚未全部迁入相应执行链。

图片页面从原域模型目录选择明确的 IMAGE 引用；`ImageGenerationCoordinator` 的现有请求节点负责页面模型 lease，出队才捕获连接，后续默认模型变化不改选原任务。页面捕获、逐请求准入和结果提交复验原 `RealmSelection`，切域往返不会恢复旧请求；工具借用原 Turn 的模型请求视图，只校验原任务域及资源权限。企业退出等待原队列工作停止和资源释放；失败节点保留给原退出流程重试。默认本地来源生成标明模拟性质的 PNG，编辑输入会校验文件并显示模拟编辑标识，不声称完成真实图像编辑。

企业 Applied 提交/恢复从同一已验证 package 派生模型传输能力，随 configuration 和 revision 一起发布；失败不提前替换能力。用户模型能力来自实际 providerOverwrite 或其 Provider。聊天只取得音频、视频、内建搜索等无秘密能力信息，不制造带假凭据的 Provider。

### 2.5 按主体解析与使用偏好

`ConfigurationQueryService` 通过 SettingsStore 组合唯一用户文档与 EnterpriseSessionController 的已发布状态；ConfigurationResolver 纯派生当前空间或明确指定主体的 ResolvedConfiguration，不持久化第三份镜像。目录携带资源来源、显示名称、编辑权限、准入与不可用原因；企业连接和凭据不进入该目录。

- 内置定义通过 withBuiltInDefinitions 补齐，显式失效的模型、MCP、注入和快捷消息引用保留。模型选择同时校验用途类型；找不到或被策略排除时返回原因，不按名称或首项替换。企业选择为空时只继承企业默认，不继承个人选择。
- 重复导入 Provider 可能保留相同模型 ID；新目录将该模型标为引用歧义，不任意选择凭据 owner，也不使其他资源目录整体失败。原用户定义保持不变。
- AssistantUsagePreferences 只保存企业主体内的显式覆盖。字段缺失继承原定义，UsageValue 中显式 null 清除可空字段；个人助手仍只保存一份共享定义。企业助手使用自身固定核心与普通字段默认值，本域偏好不能改写固定模型、系统提示词或移除固定 MCP/子助手引用。
- ConfigurationApplicationService 的资源选择、收藏和建议开关接收页面捕获的 RealmSelection；Gateway 接收原 RealmAccess.Enterprise。聊天字段命令使用 ConversationAssistantTarget，包含原页面命令目标和助手 ID。切出再切回不会恢复旧页面资格。锁序为 Session → Settings → 根会话；只写最新文档中的目标字段，DataStore actor 确认后才释放已取得的提交所有权并传播取消。通用助手 usage transform 写入口已删除。
- Settings 更新以 cold DataStore 文档为基线，不能把异步显示 StateFlow 当作最新值。commitUserDocument 统一等待实际写入 ack，个人 aggregate 编辑保留企业偏好。Workspace 使用选择先通过原会话命令清空 cwd，再提交偏好；第二步失败明确报告目录已重置，保留原选择供重试，不声称两个存储具有联合事务。目录选择另核对发起时的 Workspace，切助手也在原 Room patch 中清 cwd。
- ResourceSelectionSlot 对应模型角色、助手、Search、TTS、ASR 选择；收藏及建议开关使用同一偏好写协议。新增选择校验身份、类别准入、启用状态和模型用途；清除覆盖始终允许，且不会隐式修复仍失效的其他选择。
- 助手 MCP 修改只校验新增引用，允许逐项移除已有失效引用。写失败/取消不发布提前生效的内存值；个人定义编辑保留企业主体偏好，同企业不同用户不继承对方的使用选择。
- 每个企业配置最多一个 Gateway，必须携带 `surfaceVersion=1` 与 `surfaceHash`；不接受缺失字段的未发布原型。企业 Direct MCP 与 Gateway 的私有连接只接受 `MCP_STREAMABLE_HTTP`，个人 MCP 的 SSE 仍保留。Gateway 定义存在即已发布，不含第二个 enabled 位；撤销由来源候选删除定义表达。Resolver 为目录项派生完整 discover/invoke 工具对的 gatewayEnablement，统一决定生效开关与可切换性。REQUIRED 强制开启、拒绝开关写入，但保留原 false；恢复 USER_CONTROLLABLE_DEFAULT_ON 后原 false 再生效，无偏好则默认开启。setGatewayEnabled 使用同一授权与偏好提交协议，拒绝缺失或异主体资源，不更改配置 generation。该目录决策不代表 Session、连接或工具 surface 已通过执行校验；Gateway 执行已独立装配完整工具对并校验 surface；正式管理 UI 尚未接通。

`observeModelCatalog` 在 Session → Settings 锁序下捕获同一原选择的目录，Settings 流只作为失效通知；返回 Loading、Available 或 Unavailable。`ModelCatalogUiModel` 分开保留原覆盖、有效选择和用途不可用原因，企业目录不携带私有 binding。模型默认设置页与聊天模型选择器消费该投影，完整助手使用页面与其余资源执行链仍在接线。

Provider 余额仍在共享用户定义编辑目录沿用既有读取路径；按域模型目录暂未展示余额，需继续通过真实用户 Provider ID 接入现有余额 owner，不向目录加入连接凭据。

用户图片模型的目录、原子选择和执行解析共用 `supportsImageGeneration`，以模型覆盖连接或实际 Provider 协议判断，不以分组 Provider 替代真实传输。附件识别选择要求 CHAT 类型及 IMAGE 输入。企业图片定义的可选择性表示配置声明，实际图片执行适配尚未完成。收藏移动使用原引用对作用于最新完整列表；缺失或歧义收藏仍可从 UI 移除，不凭空构造模型定义。原 FavoriteModelService 已删除。

### 2.6 数据根记录的域身份

Room 的 Conversation、Memory、Artifact、生成媒体、会话文件夹和收藏记录持有 ConfigurationScope。Migration_11_12 将既有数据归为 Personal，保留原 ID 和内容。企业 scope 编码包含 sourceNamespace、deploymentId、userId；显示名称和当前选中空间不参与持久身份，非法或非规范编码拒绝读取。

Conversation、ConversationHeader、aggregate snapshot 与列表记录之间的映射保留 scope，Draft 首消息物化也沿用原 header。ConversationHeaderPatch 不提供改域操作。子会话创建继承父会话域，分支克隆校验源子会话与父主体一致；Repository 在普通创建、snapshot 创建和树导入时拒绝父子跨域。Folder 模型与 Entity 双向保留 scope。

Memory 已通过 MemoryAddress/MemoryService 按原域、主体与 Session 进行查询和写入；共享记忆仅在本域主体内共享，详情编辑和工具卡删除保存原授权上下文。完整 owner、取消与订阅协议见 [运行记忆](memory-architecture.md)。

会话/文件夹列表、分页、最近聊天、FTS 与统计按完整 scope 查询。UI 跟随选中域及原 Session，切域清空旧投影并失效分页源；助手的 recent_chats/conversation_search 工具保持创建时的原 RealmAccess，切回个人不改写在途企业工具的归属，退出重登也不能恢复旧工具授权。抽屉持续跟踪文件夹并在域变化时清除文件夹筛选。文件夹目录和会话分页行携带同一 Session owner 签发的 RealmSelection（原 RealmAccess 与进程内选择版本）；快速切域再返回也失效旧目录和惰性分页，新的目录不能给旧行重新授权。文件夹创建显式保存 scope 并验证当前助手准入，重命名、删除和移动由 ConversationApplicationService 在原选择锁内验证完整主体与助手。移动还需验证根会话和目录的选择版本一致；关闭或重登后的旧弹窗不能操作新空间。普通会话操作通过页面 lease 或目录行的 ConversationCommandTarget 保留原选择，最终会话锁内校验主体、根会话和页面生命周期；停止后树操作重新授权，撤销 token 不可跨选择复用。具体协议见 [会话操作](turn-step-execution.md)。子助手回答也使用原页面命令目标，并在 pending owner 内匹配原 Master 与执行 Session；UI 等待原回答被接受后才禁用提交，拒绝可重试，页面回收取消尚未被接受的提交。此接收结果不等同于 Child 后续持久化或模型执行成功。其余企业资源执行、文件访问和恢复/备份尚未全面接入，不能据此宣称全部企业数据已经隔离；Workspace 仍是用户可选择的共享资源。

发送、编辑重发、重生成和主助手审批接收原页面 ConversationCommandTarget。请求安装前验证选中域与 Session，接受后仍以原 RealmAccess 执行 USER/结构修改和 START；切域不改变后台请求归属，退出重登不能恢复旧请求。TurnContext 冻结 RealmAccess，审批继续复用它并校验当前页面的原 Session。输入附件创建 pin 从编辑器交给已接受请求，每个请求另持有独立的 Artifact 保留 lease，页面关闭或前驱取消不能提前释放后继正在引用的附件；前驱终态失败保留原 worker，可通过精确 stop 重试。该准入链不代表企业资源执行 adapters、主动退出收口和所有文件读写授权已全部接通。

### 2.7 本地 Portal 文档与消息

`PortalProtocol` 严格解析 Bridge v3 请求：固定版本、原文档/请求关联、准确字段与参数类型，原始 JSON 中的重复键不会先折叠为 Map。三个本地读方法复用现有 Feed DTO 和规则；不提供本地 GET、旧 CustomEvent 或通用 URL 代理。

`PortalDocument` 在 Main dispatcher 管理单个文档、在途请求与原回复通道，冻结原 RealmSelection、母 Session 和最多十分钟期限。快速切出再切回、重登、到期或关闭均不能恢复旧文档。每次读取和回复重新经过 Session owner 授权；普通请求的十秒期限涵盖授权、执行及回复等待，超时后只能以非等待授权检查返回错误。请求 ID 在文档内不复用，迟到结果不转投新页面。配置刷新复用 EnterpriseSynchronizationService；列表的 notModified 仅在授权成功后比较 ETag，且不携带正文。

`PortalWebView` 为每个批准文档新建实例，在首次加载前注册原生消息监听和 document-start bootstrap。固定 origin 只读取经过版本与摘要校验的 PortalAssets，入口为 text/html；其他地址本地拒绝，网络、文件、content URI、网页直接媒体权限均关闭。静态主文档只交付一次，重载和跨文档导航撤销旧实例，页内导航保留当前文档。响应仅经原 JavaScriptReplyProxy；关闭时撤销请求、销毁 WebView 并清理该 origin 的浏览状态。

文档创建在原 Session 的选中授权锁内登记到 `PortalDocumentRegistry`；它只索引活动 owner，不另存会话状态。`close` 立即撤权并取消请求，`awaitClosed` 供外部 owner 等待原请求收尾及宿主清理，不能由文档自身请求等待。关闭原因保留首次值；WebView 清理全部步骤成功后才发送带原 documentId 的界面通知，通知异常不占据退出屏障。宿主清理逐项尝试，失败项保留重试；依赖 WebView 的前置清理及 detach 全部成功后才 destroy，销毁后只等待或重试独立浏览状态清理。Registry 按完整原 RealmAccess 关闭所有已捕获文档，全部等待后再汇总失败；成功完成才移除登记，旧 Session 清理不关闭新登录文档。创建交接被取消时仍收口已取得的文档，补偿失败附加到原异常，不覆盖取消原因。

`EnterpriseExitService` 在 CLOSING 提交且释放 Session 锁后，并发调用 Portal、同步和会话的既有清理入口；非取消失败不跳过其他 owner，所有收口成功后才完成持久退出。启动恢复复用该流程。正式原生切域复用下述发布屏障。

`PortalNativeActions` 持有单个文档的原生确认。`PortalDocumentContext` 冻结该文档的身份和期限，供消息与原生操作共同复验。网页 logout 先冻结原退出请求和企业名称，原生确认后检查请求仍活动且文档未过期，再交给既有退出 owner。已接受的原 Session 退出不会因网页随后关闭而撤销；它仍受原 Session/selection CAS 约束。退出关闭本页只取消调用方等待，应用作用域中的退出继续，不依赖 JS 成功回调。交给退出 owner 前发生拒绝、十秒超时、关闭或原确认对象失效时，不再发起退出。

`openExternal` 展示完整 HTTPS 地址并要求原生确认，在原 Session 选中授权锁内再次检查文档期限后发出外部 Intent。它不在 WebView 内导航。原生确认界面只消费当前文档的提示投影，并回传原提示对象；旧界面决策不能接受新提示。正式宿主接通 logout/openExternal、capturePhoto/recordAudio、readMedia/releaseMedia，并声明相应能力。

宿主基础方法为 getStatus、refresh、close、cancel 和三个本地读取方法；原生交互能力见上文。状态中的动态刷新时间只表示当前文档已完成的读取，未知时为 null。以上实现不代表设备媒体验收或真实平台接入。

`PortalMediaStore` 独占 noBackupFilesDir 下的 portal_media 目录，复用应用既有企业恢复步骤清除进程遗留文件。Store 独占传入目录，恢复清理后才能按 documentId 打开 Session；各 Session 预留、发布、读取及释放自身句柄，不作全局媒体查找。预留占用两项/20 MiB 额度，单项上限 10 MiB，期限从预留起最多五分钟；发布冻结文件并检查 JPEG/MP4 容器，分块返回最多 65536 字节。容器扫描使用有限缓冲，不解码音视频。删除失败立即撤销读取并保留清理责任和额度，未交接资源的补偿失败可由原 owner 重试；取消异常保留原因为主，清理错误附加。过期扫描只触及已发布结果；调用方必须先停止写入者，再发布、丢弃或关闭 Session。硬件由 `AndroidPortalCaptureFactory` 创建：Camera2 与 TextureView 提供应用内预览，ImageReader 获取 JPEG 后由原适配器写入并设置 Exif；MediaRecorder 生成 MPEG-4/AAC，原生开始/停止、时长和大小限制共用同一停止与释放路径。

采集请求由 `PortalNativeActions` 持有独立子任务与原操作身份。原生取消既停止硬件，也取消尚未完成的发布与回交；未交给消息层的已发布句柄仍由原请求补偿。消息层只在原回复代理实际接收成功后交出句柄，回复失败或取消释放未交付结果；取消读取不释放之前已交付的句柄。普通请求期限为十秒，采集请求为 120 秒。

`PortalDocument` 同时启动网页清理与原生媒体清理，全部成功后才确认 hostClosed。媒体清理先等待原相机设备、录音器和文件写入结束，再删除媒体 Session 文件；CameraDevice 的关闭回调同时确认其 CaptureSession 失效，不再等待可能被设备关闭截断的采集序列回调；它不进入 Session 授权锁或等待桥接请求任务。迟到硬件回调只关闭原实例，超时不遗弃实际清理回执，失败保留 owner 供重试。新文档和切域均受同一关闭屏障约束。

浏览状态清理由 WebStorageCompat.deleteBrowsingDataForSite 的系统完成回调确认，包含 Cookie、缓存和站点存储。API 的边界是本地保留站点 measix.invalid（包含子域），不删除无关站点；不支持该 API 的 WebView 明确拒绝打开 Portal。系统删除不可取消，十秒限制只结束等待者，重试继续等待原操作。Registry 在创建新宿主前等待旧宿主完成，并在 Session 授权锁内再次检查准入；同一站点不同时打开两个活动文档。原请求尚在取消收尾但宿主清理已完成时，可以批准新文档，原回复仍只归原文档。

`EnterpriseApplicationService` 是正式空间 UI 的命令与查询入口，复用本地来源、同步、退出和 Portal owner。`RealmSwitchRequest` 冻结原 RealmSelection 与目标 RealmAccess，包含目标 Session 身份；Session 在锁内核验请求并等待宿主清理完成后才发布新选中空间。应用作用域持有已接受的切域任务，页面取消不取消该任务；原请求收尾在 Session 锁外等待。关闭或写盘失败保持原空间，已撤销文档不会复活。普通切域保留登录和原域生成，不走退出 CLOSING。进度投影不重新获取正在等待宿主的 Session 锁。

聊天顶部、抽屉和设置页均有正式空间入口。`EnterprisePage` 展示空间、身份、阶段、已生效 generation 与最近配置同步，并提供一键示例、扫码、粘贴、示例二维码、同步、Portal、切域与原生退出确认。`EnterpriseVM` 只依赖 application service；接入文本不写 Activity saved state。退出确认冻结原请求，Portal 页面每次打开持有独立 UI 身份，旧关闭回调不能关闭新页面。返回聊天重新经过 Startup 获取本域 lease。Portal 页面退出前台或离开组合时关闭原宿主；网页 logout 经原生确认复用退出服务，媒体请求使用同一文档和原生交互 owner。

## 3. Local Settings 顶层结构

下表的 DataStore key 列记录旧迁移输入键，正常落盘已统一为 `user_settings` 的类型化结构。下表中的“读取默认”以空 DataStore 的真实迁移/读取/物化结果为准，不以 `Settings()` 中为序列化兼容而存在的随机 UUID
占位值为准。配置演进时必须分别检查四种语义：Kotlin 构造默认、DataStore key 缺失默认、`materializeForRead()`
后的有效默认、Managed 字段未提供；它们不能相互代替。当前 JSON codec 使用 `ignoreUnknownKeys=true` 和
`encodeDefaults=true`。

### 3.1 外观与显示

| `Settings` 字段 | 类型 | DataStore key | 读取默认 | 说明 |
|---|---|---|---|---|
| `dynamicColor` | `Boolean` | `dynamic_color` | `true` | Android 动态色 |
| `themeId` | `String` | `theme_id` | 首个预设主题 ID | 非动态色时的主题 |
| `customThemes` | `List<CustomTheme>` | `custom_themes` | `[]` | 用户自定义主题 |
| `displaySetting` | `DisplaySetting` | `display_setting` | `DisplaySetting()` | 聊天显示、通知、TTS 播放和输入偏好 |

### 3.2 模型选择、提示与派生任务

| `Settings` 字段 | 类型 | DataStore key | 读取默认 | 引用/用途 |
|---|---|---|---|---|
| `favoriteModels` | `List<ConfigurationReference>` | `favorite_models` | `[]` | 按域保存引用；失效引用保留并可取消收藏 |
| `chatModelId` | `ConfigurationReference` | `chat_model` | `DEFAULT_AUTO_MODEL_ID` | 全局 Chat 默认；Assistant 可覆盖 |
| `fastModelId` | `ConfigurationReference` | `fast_model` | `DEFAULT_AUTO_MODEL_ID` | 快速任务默认 |
| `titleModelId` | `ConfigurationReference?` | `title_model` | `null` | 标题生成显式选择 |
| `imageGenerationModelId` | `ConfigurationReference` | `image_generation_model` | `DEFAULT_AUTO_MODEL_ID` | Local standalone image generation |
| `titlePrompt` | `String` | `title_prompt` | `DEFAULT_TITLE_PROMPT` | 标题生成 prompt |
| `enableSuggestion` | `Boolean` | `enable_suggestion` | `true` | 是否生成后续建议 |
| `suggestionModelId` | `ConfigurationReference?` | `suggestion_model` | `null` | 建议生成显式模型 |
| `suggestionPrompt` | `String` | `suggestion_prompt` | `DEFAULT_SUGGESTION_PROMPT` | 建议生成 prompt |
| `attachmentInspectionModelId` | `ConfigurationReference?` | `attachment_inspection_model` | `null` | 文本模型无法原生看图时的配置化视觉模型 |
| `compressModelId` | `ConfigurationReference` | `compress_model` | `DEFAULT_AUTO_MODEL_ID` | 历史压缩模型 |
| `compressPrompt` | `String` | `compress_prompt` | `DEFAULT_COMPRESS_PROMPT` | 历史压缩 prompt |

旧 `ocr_model` / `ocr_prompt` 只存在于一次性迁移：合法的 image-input 模型迁移到
`attachmentInspectionModelId`，旧 OCR prompt 不迁移；旧 key 随迁移删除。

另一个必须保留的兼容差异：`Settings()` 构造器默认带 `DEFAULT_MODE_INJECTIONS`，但空 DataStore 的
`mode_injections` 实际读取为 `[]`，读取物化也不会自动补 Learning Mode。不能用构造默认推断新安装持久状态。

### 3.3 可引用资源目录与选择项

| `Settings` 字段 | 类型 | DataStore key | 读取默认 | 说明 |
|---|---|---|---|---|
| `providers` | `List<ProviderSetting>` | `providers` | 读取后补齐 `DEFAULT_PROVIDERS` | Local Provider + Model 目录 |
| `assistants` | `List<Assistant>` | `assistants` | 读取后补齐 `DEFAULT_ASSISTANTS` | Assistant 定义目录 |
| `assistantId` | `ConfigurationReference` | `select_assistant` | `DEFAULT_ASSISTANT_ID` | 新会话/全局入口当前选择，不覆盖已有会话归属 |
| `assistantTags` | `List<Tag>` | `assistant_tags` | `[]` | Assistant 分组标签 |
| `searchServices` | `List<SearchServiceOptions>` | `search_services` | 至少物化 `SearchServiceOptions.DEFAULT` | Local Search provider 目录 |
| `searchCommonOptions` | `SearchCommonOptions` | `search_common` | `resultSize=10` | 公共搜索参数 |
| `selectedSearchServiceId` | `ConfigurationReference?` | `selected_search_service_id` | 缺失时由选择规范化/消费者回退 | 稳定 ID 选择；旧 index key 已迁移 |
| `mcpServers` | `List<McpServerConfig>` | `mcp_servers` | `[]` | Local MCP definition、headers、OAuth、工具策略；远端目录独立持久化 |
| `ttsProviders` | `List<TTSProviderSetting>` | `tts_providers` | 读取后补齐 System TTS | Local TTS 目录 |
| `selectedTTSProviderId` | `ConfigurationReference` | `selected_tts_provider` | `DEFAULT_SYSTEM_TTS_ID` | 当前 TTS 选择 |
| `defaultTTSPlaybackSpeed` | `Float` | `default_tts_playback_speed` | `1.0`，持久化限制 `0.5..2.0` | 公共播放速度 |
| `asrProviders` | `List<ASRProviderSetting>` | `asr_providers` | `[]` | 当前只有 Local realtime ASR 类型 |
| `selectedASRProviderId` | `ConfigurationReference?` | `selected_asr_provider` | 首个有效项或 `null` | 当前 ASR 选择 |
| `modeInjections` | `List<ModeInjection>` | `mode_injections` | `[]` | Prompt Injection 目录 |
| `quickMessages` | `List<QuickMessage>` | `quick_messages` | `[]` | 快捷消息目录 |

### 3.4 备份、统计和内部状态

| `Settings` 字段 | 类型 | DataStore key | 读取默认 | 分类 |
|---|---|---|---|---|
| `webDavConfig` | `WebDavConfig` | `webdav_config` | `WebDavConfig()` | Local backup credential/config，不得企业下发 |
| `s3Config` | `S3Config` | `s3_config` | `S3Config()` | Local backup credential/config，不得企业下发 |
| `backupReminderConfig` | `BackupReminderConfig` | `backup_reminder_config` | disabled / 7 days / never | 用户提醒偏好 + 上次备份状态 |
| `launchCount` | `Int` | `launch_count` | `0` | 运行统计，不是策略配置 |
| `ignoredUpdateVersion` | `String` | `ignored_update_version` | `""` | 用户更新提示状态 |
| `pendingAssistantDeletions` | `List<PendingAssistantDeletion>` | `pending_assistant_deletions` | `[]` | 内部恢复 tombstone；不进入 `Settings` JSON 序列化 |

`Settings.init` 也是 `@Transient`，只标记不可保存的初始化 dummy，不是配置字段。

## 4. Local 嵌套配置结构

### 4.1 `DisplaySetting`

| 字段 | 默认值 | 语义 |
|---|---|---|
| `userAvatar` | `Avatar.Dummy` | 用户头像 |
| `userNickname` | `""` | 用户昵称及 prompt 占位符来源 |
| `useAppIconStyleLoadingIndicator` | `true` | App 图标风格加载动画 |
| `showUserAvatar` | `true` | 显示用户头像 |
| `showAssistantBubble` | `false` | 显示 Assistant 气泡 |
| `bubbleOpacity` | `1.0` | 气泡不透明度 |
| `showModelIcon` | `true` | 显示模型图标 |
| `showModelName` | `true` | 显示模型名 |
| `showDateTimeInMessage` | `false` | 显示消息时间 |
| `showTokenUsage` | `true` | 显示 token usage |
| `showThinkingContent` | `true` | 显示推理内容 |
| `autoCloseThinking` | `true` | 自动折叠已完成推理 |
| `showUpdates` | `true` | 允许更新检查/提示 |
| `updateCheckDisabledUntilEpochMillis` | `0` | 更新检查临时暂停截止时间 |
| `showMessageJumper` | `true` | 显示消息跳转器 |
| `messageJumperOnLeft` | `false` | 跳转器位置 |
| `fontSizeRatio` | `1.0` | 聊天字号比例 |
| `enableMessageGenerationHapticEffect` | `true` | 生成触觉反馈 |
| `enableMessageGenerationSoundEffect` | `true` | 生成音效 |
| `skipCropImage` | `true` | 选择图片时跳过裁剪 |
| `enableNotificationOnMessageGeneration` | `true` | 后台生成完成通知 |
| `enableLiveUpdateNotification` | `true` | 生成过程实时通知 |
| `codeBlockAutoWrap` | `true` | 代码块自动换行 |
| `codeBlockAutoCollapse` | `true` | 代码块自动折叠 |
| `showLineNumbers` | `false` | 代码行号 |
| `ttsOnlyReadQuoted` | `false` | TTS 只读引用部分 |
| `ttsOnlyReadOutsideBrackets` | `false` | TTS 跳过括号内容 |
| `autoPlayTTSAfterGeneration` | `false` | 生成完成自动播放 TTS |
| `ttsToolSequentialPlayback` | `true` | 工具 TTS 按 turn 顺序播放 |
| `pasteLongTextAsFile` | `false` | 长文本粘贴转文件 |
| `pasteLongTextThreshold` | `1000` | 转文件字符阈值 |
| `sendOnEnter` | `false` | Enter 直接发送 |
| `enableAutoScroll` | `true` | 生成时自动滚动 |
| `enableLatexRendering` | `true` | LaTeX 渲染 |
| `enableBlurEffect` | `false` | 模糊效果 |
| `chatFontFamily` | `DEFAULT` | `DEFAULT/SERIF/MONOSPACE/CUSTOM` |
| `chatCustomFontPath` | `""` | 应用内部字体文件域中的本地路径 |
| `chatCustomFontName` | `""` | 自定义字体显示名 |
| `enableVolumeKeyScroll` | `false` | 音量键滚动 |
| `volumeKeyScrollRatio` | `1.0` | 音量键滚动倍率 |

`CustomTheme` 为 `{id, name, primaryColorArgb, secondaryColorArgb?, tertiaryColorArgb?}`。

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

Assistant 的完整字段和运行语义见 [助手配置参考](assistant-configuration.md)。结构分组如下：

```text
Assistant
  identity/display
    id, name, description, avatar, useAssistantAvatar, tags,
    background, backgroundOpacity, useGradientBackground
  model/generation
    chatModelId?, temperature?, topP?, maxTokens?, reasoningLevel,
    streamOutput, contextMessageLimit
  prompt/context
    systemPrompt, messageTemplate, presetMessages, regexes,
    modeInjectionIds, enableTimeReminder,
    allowConversationSystemPrompt, allowConversationPromptInjection
  memory/history
    enableMemory, useGlobalMemory, enableRecentChatsReference
  capability references
    quickMessageIds, mcpServers, localTools, enableWebSearch,
    workspaceId?, enabledSkills
  sub-assistant access
    allowAsSubAssistant, isSubAssistantGloballyVisible, allowedSubAssistantIds
  request overrides
    customHeaders, customBodies
```

关联类型：

- `Tag = {id, name}`；
- `QuickMessage = {id, title, content}`；
- `AssistantRegex = {id, name, enabled, findRegex, replaceString, affectingScope, visualOnly}`；
- `ModeInjection = {id, name, enabled, priority, position, content, injectDepth, role}`；
- `LocalToolOption`：JavaScript、Time、Clipboard、TTS、AskUser、ScreenTime、Calendar、AssistantManagement、
  AssistantDelegation、TextToImage。

`presetMessages` 保存的是完整 `UIMessage` 图，而不是轻量示例文本：它可能包含模型/Provider metadata、usage、terminal state
以及 Text/Image/Video/Audio/Document/Reasoning/Tool parts。未来若要企业预置示例对话，应另建受限 typed schema，不能
把运行历史和本地 URI 直接当配置下发。

### 4.4 Search

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

### 4.5 TTS

所有 Local TTS 类型都有 `id/name`，类型特有字段如下：

| Local 类型 | 字段 |
|---|---|
| `OpenAI` | `id, name, apiKey, baseUrl, model, voice` |
| `Gemini` | `id, name, apiKey, baseUrl, model, voiceName` |
| `SystemTTS` | `id, name, speechRate, pitch` |
| `MiMo` | `id, name, apiKey, baseUrl, model, voice, voiceDesignPrompt` |

`selectedTTSProviderId` 选中一个 provider；`defaultTTSPlaybackSpeed` 是播放层公共速度，不是服务端 TTS voice。

### 4.6 ASR

所有 Local ASR 类型都有 `id/name`，类型特有字段如下：

| Local 类型 | 字段 |
|---|---|
| `OpenAIRealtime` | `id, name, apiKey, websocketUrl, model, language, prompt, sampleRate, vadThreshold, prefixPaddingMs, silenceDurationMs` |
| `DashScope` | `id, name, apiKey, websocketUrl, model, language, sampleRate, vadThreshold, silenceDurationMs` |

当前 Local ASR 使用 WebSocket/realtime controller 配置，HTTP transcription 不由这些 realtime 类型承载。

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

### 4.8 Backup

```text
WebDavConfig { url, username, password, path="measix_pilot_backups", items=[DATABASE,FILES] }
S3Config     { endpoint, accessKeyId, secretAccessKey, bucket, region="auto",
               pathStyle=true, items=[DATABASE,FILES] }
BackupReminderConfig { enabled=false, intervalDays=7, lastBackupTime=0 }
```

普通备份的 `settings.json` 是 Local shadow，当前会序列化 Local Provider/Search/TTS/ASR/MCP/WebDAV/S3 中的本地凭据。
这再次说明 Enterprise credential 不能进入 `Settings`。Managed Snapshot/Binding/credential 也不属于普通备份域。

当前手工备份格式是 `rikkahub-durable-v4`：包含 `settings.json`、独立的 `mcp_catalogs.json`、`VACUUM INTO` 得到的 Room
snapshot、manifest，以及 `upload/images/skills/fonts` 四个 durable 目录；不包含
`workspaces/tool_outputs/managed_configuration`。Room v10 的 `conversation_model_context` 随 `measix_pilot.db` 整体备份和恢复，
不增加 disclosure sidecar 或新 manifest 版本；settings-only 备份不携带会话 context。恢复仍接受 v3，并从旧 Settings 中一次性提取完整 MCP schema 后写入独立
Catalog；空或不完整目录不迁移。`SettingsPersistenceContractTest` 用 `settings-golden.json` 锁定 `settings.json` 的逐字载荷（含 Provider/Model 覆盖、Assistant 枚举、显示设置、S3/WebDAV、TTS/ASR、模式注入与备份配置的默认值与嵌套表示），并另锁顶层字段顺序、Preferences key 名称/Boolean-String-Int-Float 类型清单，明确排除 disclosure/model-context 字段；`BackupArchiveServiceTest` 通过生产 `prepare` 验证 settings-only ZIP 只有 settings 与独立 Catalog。`BackupRestoreMigrationIntegrationTest` 在设备上覆盖两条链路：真实 v9 snapshot 封装为 durable-v4，经 `stageRestore`、冷启动文件交换和生产 migration 链打开为 v10 并装载历史 aggregate；以及已含 canonical context 行的 v10 库整体备份后换回，仍由生产 migration 链打开并经 Repository 逐字读回该 entry。ZIP 未加密、未签名，manifest 的 SHA-256 只提供完整性。Manifest 仍声明
`allowBackup=true`，所以企业 credential 设计还必须单独审计 Android
Auto Backup/设备迁移规则，不能只验证手工 ZIP。

## 5. Settings 之外的 Android 配置

### 5.1 SharedPreferences

| Key | 默认 | 分类 |
|---|---|---|
| `colorMode` | `SYSTEM` | Local 外观偏好 |
| `amoledDark` | `false` | Local 外观偏好 |
| `appLanguage` | `SYSTEM` | Local 语言偏好 |
| `create_new_conversation_on_start` | `true` | Local 启动行为 |
| `chat_sidebar_expanded` | `true` | Local 布局偏好 |
| `search_page_sort_order` | `RELEVANCE` | Local 查询显示偏好 |
| `lastConversationId` | `null` | 导航恢复状态，不是配置下发项 |

CrashHandler 的独立 `crash_handler` SharedPreferences 保存 `crashed` 和截断后的 `stacktrace`，属于崩溃恢复状态，
不是产品配置。

### 5.2 Room 中的配置性事实

- `WorkspaceEntity`：`id/name/root/shellStatus/toolApprovals/createdAt/updatedAt/lastAccessAt`；
- `ConversationEntity`：以 `assistantId` 固定会话归属，并可保存 `customSystemPrompt`、`modeInjectionIds`、`workspaceCwd`；
- `conversation_model_context`：由 Assistant request variant 拥有、锚定因果 USER 的 canonical 会话披露事实；不属于 Settings、UI 投影或独立导出域；
- `FolderEntity`：`id/assistantId/name/sortIndex/createAt`，作为某个 Assistant 下的 Local 会话分组；
- Workspace shell 状态和时间戳是生命周期状态，`toolApprovals` 是 Local 用户覆盖；
- 会话覆盖只有在 Assistant 对应 allow 字段开启时才生效。

Workspace 文件系统另有代码内置的 `WorkspaceConfig` 运行限制：`maxReadBytes=512 KiB`、
`maxWriteBytes=2 MiB`、`maxListEntries=500`、`maxSearchResults=100`。它们当前不是持久化字段，也没有
企业下发入口；如果以后改成策略，必须先明确由 Local、Managed Policy 还是 Runtime owner 持有。

这些事实归本地配置或用户数据 owner，不属于当前内部 overlay 的远端会话同步协议。

### 5.3 Skills 与文件资源

- Skill 由 `filesDir/skills/<name>/SKILL.md` 及同目录资源构成；
- frontmatter 至少提供 `name/description`，可带 `compatibility`；
- `Assistant.enabledSkills` 只保存 Skill 名称引用；
- 自定义字体、Assistant 头像/背景、上传和生成图片分别由自己的文件 owner 管理。

`SkillManager` 是 Skill 目录身份、文件树、读取、校验和发布的唯一 owner，UI 与工具只持有 typed metadata/file/result，不取得宿主路径。`SkillFrontmatterParser` 使用 SafeConstructor 与 loader limits，拒绝重复键并限制 alias、嵌套和文档大小。

Skill 文本在 owner 边界先做 4 MiB bounded byte read，再 strict UTF-8 解码；超限、非法编码和 IO 分别返回 typed failure。写主文档/支持文件及删除支持文件时，先复制完整已发布目录到 staging，拒绝 symlink/path escape，校验 frontmatter 与预期 name 后 rename 发布。更新 SKILL.md 保留支持文件；中断 backup 由下一次 owner 访问恢复或清理，歧义时拒绝继续。

ZIP bundle 完整解析并拒绝重复 Skill name 后，复制整个 Skill root、一次 root swap 提交，失败或取消不留下部分更新；root backup 同样可恢复。导入上限为 16 MiB 输入、512 entries、单文件 4 MiB、累计解压 32 MiB。GitHub 导入保留支持文件原字节，仅主文档 strict UTF-8 解码，因此二进制资源不被文本化。SKILL.md 不能作为普通支持文件删除，整项删除经 deleteSkill 与 enabledSkills 引用清理协议。

## 6. 当前引用图与运行依赖

```text
Settings.providers[].id
  └─ providers[].models[].id
       ├─ Settings.{chat,fast,title,imageGeneration,suggestion,
       │            attachmentInspection,compress}ModelId
       └─ Assistant.chatModelId

Settings.assistants[].id
  ├─ Settings.assistantId                    # 新会话/全局选择
  ├─ Conversation.assistantId                # 已有会话权威归属
  ├─ Folder.assistantId                      # Local 会话分组
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

## 7. 内部受管配置边界

`ManagedConfigurationEnvelope` 使用 `schemaVersion=1`，具备签名校验、generation 单调、asset staging、Local shadow、effective projection 和写门禁。其保存与校验归 ManagedConfigurationStorage，唯一有效状态仍由 SettingsStore 发布。

Local shadow 不因同 ID overlay 覆盖而删除；overlay 移除后恢复本地值。UI disabled 仅表达投影，受管 mutation 必须在提交边界拒绝。受管文件不进入普通本地备份，不能成为绕过 SettingsStore 的第二写入口。

当前尚未接入生产 Realm/Enrollment、企业 Binding/Session wire、平台 Snapshot DTO、Managed Memory Seed store 或生产下发入口。前文的本地企业会话及整包格式独立于生产协议。真实接入必须明确身份、凭据、generation、资源引用与撤销合同，并替换内部原型的传输路径，不能混用平台 ID 与 Local UUID，不能把服务端 upstream/secret/route 写入本地能力配置。

## 8. 关键架构文件

| 边界 | 文件 |
| --- | --- |
| Settings owner 与 Local shadow | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsStore.kt` |
| 提交与发布顺序 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsCommit.kt` |
| 读取物化与持久化归一化 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsNormalization.kt` |
| Managed 写门禁 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsWriteRules.kt` |
| 唯一有效读模型 | `app/src/main/java/net/weero/measix/pilot/data/datastore/EffectiveSettings.kt` |
| 当前签名 overlay 原型 | `app/src/main/java/net/weero/measix/pilot/data/datastore/ManagedConfiguration.kt` |
| Assistant 配置模型 | `app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt` |

## 9. 维护与验证

以下变化必须同步本文：Settings 字段与默认读取语义、Local/Managed owner、有效读模型、持久化与备份边界、稳定引用
规则，以及已经实际接入 Android 的平台 typed definition。

修改当前 Android 配置链至少验证：

- Settings serialization、缺失 key 默认、读取物化和旧备份兼容；
- Local shadow、Managed overlay、write lock 与“落盘后发布”顺序；
- Local 备份不包含 Managed envelope、Enterprise Binding、credential 或 Applied Snapshot；
- 无效、过期、撤销或损坏 Managed payload 的 fail-closed/LKG 行为；
- 删除与恢复在 Provider、Assistant、MCP、TTS、ASR、Search 和文件引用之间保持原子；
- UI 与运行时只消费同一个 effective read model，不建立第二 owner。

构建/JVM 验证不替代真实平台存储、签名资源和恢复场景的设备验收。新增生产同步路径时，应补充对应的服务端互操作验证。

### 文件目录与选择生命周期

FileManagementQueryService 合成当前选择的上传与图库目录，条目保留原 RealmSelection；列表、候选数量和清理 SQL 都显式限定 scope。FileManagementApplicationService 在 Session 准入内编排原 ArtifactStore/GeneratedMediaStore，单项文件归属仍由原 owner 复验。页面切换会清除旧确认和预览；返回相同主体不会恢复旧选择的写权限。统计只消费本域已登记条目，查询失败可以在同域重试。输入框名称只查询原 Draft 当前附件。共享配置预设消息经 ArtifactStore 验证持久配置根后复制为目标域附件；主 Draft 由原 Runtime 持有创建令牌，子助手沿既有 Child 创建与链接提交交接。预览、导出和工具读取使用原页面/执行主体；Workspace 是显式共享空间，其文件与终端边界见 Workspace 参考。设备与整体验收状态以本期实施方案为准。
