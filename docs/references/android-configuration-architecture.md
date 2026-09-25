# Android 配置架构与资源边界

本文定义 Android 当前配置的 owner、持久化载体、字段目录、引用关系及个人/企业域边界。Assistant 逐字段运行语义见 [助手配置](assistant-configuration.md)，总体依赖与启动恢复见 [应用架构](application-architecture.md)，平台接入见 [Enrollment 合同](enrollment-material-contract.md)。

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
- 所有配置修改持有同一 Settings writer lock。首次写入直接读 DataStore，不等待异步 UI 投影；观察器取得写锁后重新读取最新文档，不能用迟到值回退投影。
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

平台 Discovery、Enrollment、Bootstrap、Snapshot 和运行请求共用原生 Session，不存在手机端模拟企业来源或第二套企业配置。`EnterpriseSessionController` 串行发布身份、连接和 Applied 版本；`EnterpriseAppliedStore` 在 `noBackupFilesDir/enterprise` 用单 manifest 发布经校验的不可变配置与执行描述。用户定义和偏好仍只由 `SettingsStore` 写入。启动恢复在 Settings 和文件 owner 收口后继续本机企业重置及配置恢复；企业校验失败保留企业不可用诊断，不伪装成空配置。

- Applied manifest 与平台 Snapshot 各有独立版本。Applied 的旧 schema 只经一次性持久迁移进入当前格式；正常读写不双读。manifest 提交需同步文件并核验实际落盘，损坏态不能通过回读旧 manifest 实施重置。
- 同步由 `EnterpriseSynchronizationService` 合并同一主体/Session 的请求；Session owner 再核验 Bootstrap 身份与候选 Snapshot 后提交。成功保存最近同步时间；已提交配置的 applied 回报失败仍保留配置并暴露诊断，下一次同步可重报。
- `EnterpriseExecutionLease` 捕获原 Session、Applied binding 与连接；退出等待其真实清理，清理失败保留 owner 供重试。lease 本身不等于远端执行准入。

平台地址是 Session 的可变连接参数，不是 deployment 身份。`PlatformEnterpriseService.changeAddress` 先验证同一 deployment、原用户/设备/Session，再由 Session owner 一次提交新地址及必要的轮换凭据；失败保留已确认连接。退出、撤销与身份删除先持久发布 CLOSING，停止新操作并等待已有 operation/execution lease；`EnterpriseExitService` 只完成原 Session 的退出。永久身份删除由 `EnterpriseIdentityDataDisposer` 编排各 owner 精确清除该 principal，个人数据不进入范围。全设备本机重置由 `EnterpriseDataResetService` 持久化 reset intent，复用同一关闭屏障并在重启后继续，不能用清日志或空列表表示完成。

- 地址切换期间，已捕获的请求继续使用原连接；新连接只供提交后的请求。候选地址验证失败不关闭原 Portal。成功后旧 Portal 的关闭和站点清理由同一屏障确认。候选地址若已完成凭据轮换，失败收口不能回退到已失效凭据。
- 主动退出、到期、撤销和身份删除都绑定原 Session；重复请求合并。`IDENTITY_DELETED` 是更强的持久终态，不能被重启或较弱的退出原因覆盖。远端注销失败仍须完成可恢复的本机收口并保留原诊断。
- 本机重置区分“仅删除接入”与“接入和全部企业历史”，范围先写入 intent，再由原数据 owner 清理；强删除终态到达时扩大清理范围，重启继续。两个分支都不删除个人域，也不依赖 Core logout 成功。

`PlatformSnapshotMapper` 将当前 Snapshot v4 映射为候选；`EnterpriseConfigurationCodec` 在读取 canonical Applied 时再次校验同一领域约束。`ManagedPolicy` 的助手、对话、快速、标题、附件检查、建议、压缩、图片、TTS、ASR 十项默认引用均可省略；缺失表示未设置，不取资源首项，也不复制对话默认。非空模型引用必须指向同一快照内已启用模型，附件检查模型还需 IMAGE 输入。可选 `imageGenerators` 缺失表示空集合；显式 null、未知字段和未知枚举失败关闭。平台 Snapshot 版本与本地 Applied manifest 版本是不同契约，不能混用。

`EnterpriseSynchronizationService.prepareExecution` 查询 Core Managed State；仅在非零 generation、READY、版本一致且未阻断时，Session owner 才签发执行 lease。版本缺失或不同可通过同一同步入口更新后再查，不能用本机缓存替代权威检查。请求若收到 `ManagedSnapshotRequired`，原 lease 永久关闭，等待原任务停止和释放后再同步；旧请求不重放，也不因新配置到达而复活。

### 2.5 按主体解析与使用偏好

`ConfigurationQueryService` 组合 `UserSettingsDocument`、原 Session 和 Applied；`ConfigurationResolver` 按完整 Deployment/User 产生 `ResolvedConfiguration`，只做纯派生，不落盘镜像。用户定义保持一份；企业助手定义、system prompt 与固定 MCP 只读，本域允许的模型和使用偏好通过 typed 命令写回用户文档。助手模型的默认、空间默认、指定引用必须保持可区分，失效的显式引用不能静默替换。所有列表、查询和命令携带原 `RealmSelection` 或 `RealmAccess`；等待 Settings、数据库、文件或网络后须重新验证原主体，切域返回也不恢复旧授权。

资源选择和修改在 Settings 写锁内依据最新文档、原域与准入规则完成；UI 只消费有效目录和不可用原因，不从显示名称推断持久化引用。企业 `img_*` 是独立图片定义，仅由 resolver 投影到统一图片目录；模型、图片、云端语音和 Direct MCP 执行都须冻结原 Session、generation 与具体 route，并在 Provider I/O 前执行前述受管版本准入。准入成功不代表远端请求成功。

`ModelExecutionService` 在用户配置事务中捕获同一文档的目录与选择，主聊天、子助手和辅助生成沿原域复验。内建搜索选择必须匹配 Provider wire 能力；外挂搜索以同次 `ResolvedConfiguration` 的 SEARCH 选择查用户目录，企业域未选或失效不回退首项。企业固定 Memory Seed 由 resolver 派生，START 时进入 disclosure，不进入可变记忆表。

### 2.6 预算、Portal 与数据边界

生产用量、预算和阻断事实归 Core。`PlatformControlClient.budgets` 经原 Session 查询，Android 不持久化预算；`EnterpriseVM` 的投影绑定原 selection 与 origin，刷新中保留最近成功值，只有请求实际失败才标记 stale 并保留原诊断。MODEL、IMAGE_GENERATION、TTS、ASR、MCP 均按平台 Problem 精确分类；个人请求不能被普通远端 429 冒充企业额度失败。

平台 Problem 仅在 routed 请求、稳定 code、HTTP status 和 `forwarded=false` 匹配时进入企业失败链；保留 blocker、resetAt、requestId、resourceId 和原 detail。失败不回退个人资源或自动重放业务请求。陈旧预算只供显示，执行仍由 Core 准入。

Portal 文档和消息由 `PortalDocument`、`PortalDocumentRegistry` 与原 `RealmSelection`/Session 共同授权；Bridge v3 严格解码，重复键、未知操作或不匹配的文档身份失败关闭。Android 始终打开 Core `/portal/`；标准或企业自定义页面由 Core 选择。旧文档关闭、站点数据清理和地址切换在同一发布屏障内等待确认，迟到回复不能进入新页面。原生媒体和外链动作均由当前文档 owner 再次授权，Portal 不成为配置或会话 writer。

Portal 原生媒体的暂存、额度、取消交接与删除恢复归 `PortalMediaStore`，文档关闭等待网页和硬件清理全部完成；失败保留原 owner 供重试。站点清理只针对本 Session 的 origin 和 Portal Cookie，不全局清除浏览数据；不具备完整站点清理能力的 WebView 明确拒绝打开。真正的 Core grant、系统浏览器、相机/录音和关闭路径需单独设备验收。

Conversation、Memory、Artifact、GeneratedMedia、收藏、分页与统计中的 durable 行均带完整 scope。个人备份只构建个人数据及其共享资源闭合图；恢复时保留最新企业图，文件由各自 owner 交接。系统备份和设备迁移不直接复制混合域存储。具体备份格式、旧数据迁移与字段目录见下文对应载体；文件与会话的写协议仍归各自专题。

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
| `selectedSearchServiceId` | `ConfigurationReference?` | `selected_search_service_id` | 个人未指定由 Resolver 派生首个已配置服务；企业未指定不回退 | 按域稳定 ID 选择；旧 index key 已迁移 |
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

`Assistant` 的字段、默认值、运行语义及本域使用编辑只在 [助手配置参考](assistant-configuration.md)维护。此处只记录它引用的其他配置事实：`QuickMessage`、`AssistantRegex`、`ModeInjection`、MCP server、Skill、Workspace 与本地工具开关仍属于用户文档或各自 owner，不因 Assistant 引用而转移写入所有权。

`presetMessages` 保存完整 `UIMessage` 图，可能包含 Provider metadata、usage、terminal state 和多模态/工具 parts；它不是可直接下发的轻量示例文本。企业 Starter 使用独立受限定义，不把本地运行历史或 URI 当作受管配置。

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

公共倍速位于一般偏好的 TTS 组，保持既有字段和播放层设置协议。`TtsController` 仅预取当前位置之后两段；同 turn 的追加只补足该窗口，不能按上次预取位置继续前推。没有自动合成重试或跳过队列项的旁路。远端 TTS 可并发预取；`SystemTTSProvider` 通过单一 `SystemTtsSynthesisCoordinator` 串行访问设备引擎，避免厂商实现同时绑定和合成多个分片。

企业公开定义使用 `EnterpriseTtsResource`，共同字段为 `id/name/enabled/protocol`；云端协议携带 `modelId/voice` 等条件字段，System TTS 只携带 `speechRate/pitch`。云端 `voice` 必须按协议显式提供，不补 Android 默认音色。企业定义与用户 `TTSProviderSetting`、私有 `EnterpriseRuntimeBinding` 分别保存。

`TtsController` 统一管理分片、预取与播放；每个 `TtsPlaybackSession` 提供合成和播放准入回调。停止取消并返回同一组任务的清理回执，恢复播放复验原 worker，销毁等待整个 controller 协程作用域，包含旧队列尚未退出的合成。`SpeechApplicationService` 为唯一应用语音 owner，提供 `SpeechPlayback` / `SpeechRecognition` UI 端口；页面不创建 controller 或通过 AppEvent 发出播放请求。系统 TTS 在主线程创建和调用引擎，初始化、参数、语言、启动、带错误码终态、主动停止、空输出、超时和关闭都产生明确诊断；回调只接受第一个匹配 utterance 的终态，取消仍向上传播。System TTS 的 speech rate 固定为 `0.1..3.0`，pitch 固定为 `0.1..2.0`，个人与企业定义共用该校验。OpenAI/Gemini HTTP 合成使用 `Call.readResponse`，取消实际网络 Call，并等待响应正文读取退出后关闭响应。

### 4.6 ASR

所有 Local ASR 类型都有 `id/name`，类型特有字段如下：

| Local 类型 | 字段 |
|---|---|
| `OpenAIRealtime` | `id, name, apiKey, websocketUrl, model, language, prompt, sampleRate, vadThreshold, prefixPaddingMs, silenceDurationMs` |
| `DashScope` | `id, name, apiKey, websocketUrl, model, language, sampleRate, vadThreshold, silenceDurationMs` |

当前 Local ASR 使用 WebSocket/realtime controller 配置，HTTP transcription 不由这些 realtime 类型承载。

`RealtimeAsrController` 统一管理两种个人实时协议的连接、消息投影和停止流程，各自的 endpoint/session 编码仍取对应配置类型。`PcmAudioCapture` 独占一只麦克风及阻塞读循环。用户停止、服务端结束和关闭帧共用一次读循环等待与关闭握手；销毁等待所有已取消录音及原连接的 WebSocket 终态回调。`SpeechApplicationService` 保留原 Recognition、转写交付任务和第一次销毁回执；正常结束先完成最终交付，再等待 controller/录音/网络退出并释放 binding。取消或替换后，旧回调不得更新新输入或错误投影。`HttpAsrController` 使用同一 PcmAudioCapture 写入临时 WAV；停止后在写 WAV 头和上传前累计检查 PCM 的 RMS、峰值和非零样本数。无有效信号抛出 `NoSpeechDetectedException`，应用显示“未检测到语音，请重试。”且不会调用 transport；所有路径最终回收原文件，清理失败保留原文件与 owner 供重试。

企业公开定义独立使用 `EnterpriseAsrResource`，共同字段为 `id/name/enabled/modelId/language/protocol`，其中 `language` 可省略，提供时必须非空。`EnterpriseSpeechDefinition.validate` 按协议校验条件字段：OpenAI/DashScope HTTP 禁止实时字段，OpenAI realtime 与 DashScope realtime 分别要求各自采样率、VAD 等参数，不跨协议补值。

平台云端 TTS 通过 `SpeechHttpTransport` 把完整资源地址、平台头和请求 client 传给现有 OpenAI/Gemini/MiMo 编解码器，`TTSRequest.transport` 只在内存使用，不进入序列化配置。`TtsSynthesizer` 和 `TtsController` 共用个人空间的音频合成、播放、暂停、停止流程。系统朗读只使用现有 SystemTTS 引擎的 speechRate/pitch，不取得远端 binding；它仍属于企业定义，禁止个人 TTS 不会禁用它。

平台文件识别通过原 `HttpAsrController` 录制 WAV：`FileTranscription` 对 OpenAI 构造 multipart，对 DashScope 构造 Data URI JSON 并读取 output.text。`maxFileTranscriptionAudioBytes` 按实际协议开销从请求上限反算录音文件上限，录音达到上限明确失败并回收原文件，不静默截断或自动重新上传。

平台实时识别复用 `RealtimeAsrController` 的协议编码、PCM 采集和停止流程，`RealtimeAsrTransport` 只在内存传递完整平台握手请求。`SingleAttemptWebSocketFactory` 用公开 HTTP upgrade socket API 保留原 WebSocket 编解码器，在握手 follow-up 前拒绝失败响应；取消同时关闭原握手 Call 与 WebSocket。升级连接的 sink 累计实际写入字节，自动 pong 和关闭帧也计入平台上限。发送拥堵、超限及上游失败明确结束识别，不静默丢弃录音，不自动重连或重放。

独立语音交互先经权威 Managed State 检查再冻结配置；工具朗读沿用父 turn 的上下文。平台租约以原 AppliedVersion 获取，请求前取得当前 Session 令牌并复验原语音 owner。模型与语音共用 `common.http.withExplicitRoute` 的完整地址、请求体上限、禁止自动重放和真实 HTTP 诊断；有效 428 仍由原语音 owner 终止与同步。播放器和文件识别的失败由应用提供 `userVisibleDiagnostic`，保留异常类型与 cause，不以通用语音失败替换。

`EnterpriseSpeechTransport` 按平台 Snapshot 的协议编码 TTS 与 ASR 请求，注入原 generation/interaction；Runtime endpoint 与认证只归 platform execution transport。HTTP client 不重定向或自动重试。共享 `ManagedSnapshotRequired` 解析 428 barrier，严格 JSON 解码归 `StrictJsonValue`。应用 owner 在原 `RealmSelection` 下捕获资源和完整 AppliedVersion，队列/录音自行持有 execution lease 至实际清理完成。独立播放/录音创建交互，工具和主/子助手共用原 turn 的冻结语音上下文。完成事件携带原回复和该上下文，自动朗读不查询新 turn 的全局选择。428 永久终止原语音交互，分别收口父 turn、语音资源与同步，不重放；文件清理失败不能跳过父 turn 停止或同步。空间切换在 Session 锁内只撤销和停止硬件，锁外等待清理。


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

普通备份的 `settings.json` 是用户文档的个人配置投影，当前会序列化 Local Provider/Search/TTS/ASR/MCP/WebDAV/S3 中的本地凭据。
这再次说明 Enterprise credential 不能进入 `Settings`。Managed Snapshot/Binding/credential 也不属于普通备份域。

当前手工完整备份格式为 `rikkahub-personal-v1`，包含个人 `settings.json`、个人 `mcp_catalogs.json`、个人数据图的 `measix_pilot.db`、按图收集的 payload 和完整性 manifest。设置投影仍含用户自己的 Provider/Search/TTS/ASR/MCP/WebDAV/S3 凭据；企业配置、binding、Session、Feed、企业偏好和企业数据均不进入个人包。ZIP 未加密、未签名，SHA-256 仅校验完整性。

`BackupArchiveService` 在既有文件锁与 snapshot barrier 内取得 SQLite 一致快照，`BackupDataGraph` 将个人根及其 Message/Turn/Tool/Context 复制到新建数据库，按显式列名复制并保留自增 ID 高水位；不携带源库空闲页、未知表或旧全文索引。Artifact 引用和 FTS 从保留的消息重建。个人 Artifact 包含未挂接聊天的用户文件；payload 清单只来自保留的 metadata、生命周期 receipt，以及共享 Skill/字体配置，不扫描整份 upload/images。Workspace 注册信息属于共享配置，Workspace 目录内容不进入该包。

个人格式最低要求带 durable scope 的 Room schema 12，不绑定后续 App 或当前 Room 版本；升级继续使用同一迁移链。恢复仍接受已发布的 durable-v3/v4/v5 和原有无 manifest 个人备份，使用同一 Room migration/物理 schema 校验；旧格式缺失的头像、背景和预设资产仅在显式恢复入口按 `ArtifactReferencePolicy.detach` 回退默认。新格式要求配置根完整，已持久化的 DELETING 根保留给 Artifact 恢复 owner。正常用户删除附件留下的历史消息仍有效，不重建已经失效的活引用。

`PendingBackupRestore` 保留原始个人输入。冷启动在应用 Room 和运行写入开放前读取最新数据库与同一 Settings DataStore，将“备份个人图 + 最新企业图 + 企业偏好仍引用的共享个人资产”构建为独立 publication。共享 Workspace 注册使用最新本地值。相同主键但不同内容、不同文件 owner 的路径冲突、非规范路径或跨域引用均拒绝，不覆盖企业行；同一共享 Artifact 仅在 metadata 与 payload 均相同时合并。物理发布仍使用既有 swap/rollback；重试先恢复原图，再重新读取最新企业状态。升级时遇到已发布个人版本留下的 prepared 输入，在 publication 副本上走生产 Room migration，保留原输入字节；若旧个人恢复已开始直接交换 pending 文件，先按原交换来源回滚，再进入合并。CREATING/DELETING（包含 payload 已清除但 metadata 补偿未完成的状态）和图库 `.pending`/`.deleting` receipt 交回原 owner 恢复，不在备份层执行生命周期动作。Settings 恢复只替换个人投影，保留企业使用偏好与内部清理状态。旧个人 prepared 输入的失效配置资产在合并企业图前归一化；派生结果保存在 pending 的独立文件，实际 Settings owner 消费同一结果，原 settings.json 与数据库字节不变。恢复 owner 完成后先原子退休 pending，再删除 rollback、publication 与退休目录；清理中断不重放恢复。

Settings-only 包只有个人 Settings 与 MCP Catalog，不携带会话数据或本地 payload 引用。系统备份与设备迁移已显式排除混合域存储，见本文“系统备份边界”。

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

这些事实归原配置或用户数据 owner，企业域按完整主体保存会话与文件夹；Workspace 注册及目录为显式共享配置，不属于企业 Snapshot 或远端会话同步。

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
