# Android 配置架构与企业下发边界

> 当前实现事实以 Android 代码为准；尚未落地的 S0.2 企业边界以外部平台契约为准，不在本文中冒充已实现能力。
> 平台架构权威：`topabomb/measix-architecture` 的 S0.2 Enterprise Realm & Experience Contract、S0.4 Android Managed Runtime Integration Contract 与 S0 Control Protocol
> Executable wire：`topabomb/measix-platform-core/api/client/client-control.openapi.yaml`

本文回答两个不同问题：

1. Android 当前有哪些配置、默认值、引用和持久化边界；
2. 其中哪些属于企业下发，哪些必须保持本地，哪些只是状态或服务端内部事实。

本文是 Android 实现结构的总目录。Assistant 的逐字段运行语义仍以
[助手配置参考](assistant-configuration.md) 为权威；跨组件 Managed Snapshot 字段仍以 MEASIX Control Protocol 和冻结后的
Client OpenAPI 为权威，本文不反向发明 wire 字段。

## 1. 先给结论

Android 的“完整配置”不是一份可整体下发的 `Settings JSON`，而是五类不同 owner 的事实：

```text
Built-in Defaults
       +
Local Settings / Local Resources
       +
Applied Managed Snapshot
       ↓
Effective Configuration / Runtime View
       ↓
Assistant / Model / TTS / ASR / MCP / Search / UI consumers

Enterprise Binding + Credential + Managed State
       └── 只负责身份、同步和 Managed interaction correctness
```

外部平台契约的分阶段边界是：

```text
S0.1 Snapshot v1
  └─ Provider / Model / TTS / ASR / Direct MCP / Policy
S0.2 Snapshot v2
  └─ v1 + Managed Assistant / Memory Seed / Assistant Starter
S0.3 Snapshot v3
  └─ v2 + Enterprise Tool Gateway
S0.4 Android integration
  └─ 消费冻结后的完整 profile
```

因此：

- **Snapshot v2**：保留 v1 的 Managed Provider/Model/TTS/HTTP-ASR/Direct MCP/Policy，并增加
  `ManagedAssistantDefinition`、其中的 Managed Memory Seed 和 `AssistantStarterDefinition`；
- **必须独立保存但不是能力配置**：Enterprise Binding、Refresh Credential、Applied Snapshot payload/元数据；
- **仍保持本地或属于后续阶段**：Personal Assistant/Prompt/Memory、Search、Skill、Workspace、主题/显示、备份、
  快捷消息、图片生成和实时 ASR；Enterprise Local Assistant Memory 是 User Data，不进入 Snapshot；
- **禁止下发到 Android**：`UpstreamDefinition`、`upstreamId`、base/internal URL、企业 API Key/Secret、RuntimeBinding、
  `runtimeRouteId`、Pricing；这不包含客户端必须接收的 `upstreamModelKey`；
- **禁止实现方式**：把服务端 payload 直接反序列化为 `Settings`，或把 Enterprise Token 写入本地 Provider/MCP credential 字段。

当前 Android 只有内部签名 overlay 原型，没有 ClientRealm、Enrollment/Session、Snapshot v2/v3 generated DTO、
Enterprise Update/Portal 或生产同步入口；不能把该原型表述为已完成 S0.2/S0.4 集成。

## 2. 配置 owner 与读写架构

### 2.1 Owner 表

| Owner | 当前载体 | 负责的事实 | 是否进入普通本地备份 |
|---|---|---|---|
| Built-in | `DefaultProviders.kt`、默认 Assistant/TTS/Prompt/Theme 常量 | 安装包内默认资源和反序列化默认值 | 不单独备份；读取时补齐 |
| Local Settings | Preferences DataStore `settings` | 全局 UI、资源目录、选择项、Assistant、Prompt、备份参数 | 是，导出为 `settings.json` |
| Local UI preference | SharedPreferences `MeasixPilot.preferences` | 语言、明暗、启动/布局/搜索排序等轻量偏好 | 否 |
| Local durable resource | Room + `filesDir` | Workspace、Skill、会话级覆盖、资源文件 | 仅备份协议明确包含的域 |
| Local runtime cache | `cacheDir/lru_key_roulette.json` 等 | 可重建的 key 轮换/发现缓存；不是配置真源 | 否 |
| Managed prototype | `filesDir/managed_configuration/` | 当前代码中的签名通用 overlay 原型 | 否 |
| Enterprise Binding | 当前 Android 未实现 | Deployment/User/Device/Session binding | 否 |
| Enterprise credential | 当前 Android 未实现 | Refresh Credential；Access Token 仅内存 | 否 |
| Applied Managed State | 当前 Android 未实现 | Snapshot payload + generation/hash/schema/release | 否；与 credential 分离 |
| Server runtime owner | Control Hub / Runtime Relay | Upstream、Secret、RuntimeBinding、route、pricing | 永不进入 Android 本地配置 |

`lru_key_roulette.json` 当前以原始 API key 作为 map key 保存轮换时间。它虽然不是配置真源也不进入手工备份，仍是
未加密 secret 副本；企业 credential 不能复用这条缓存协议。

### 2.2 单一读写路径

`SettingsStore` 是当前应用级配置聚合的唯一 owner：

```text
updateLocal(latest Local shadow transform)
→ managed lock/write rule
→ normalizeForPersistence
→ canonicalizeForDataStore
→ one dataStore.edit
→ durable success
→ materializeForRead
→ EffectiveSettingsResolver
→ effectiveSettings
```

关键不变量：

- 外部消费者只读 `effectiveSettings`，不同时订阅 Local 与 Managed 两个 Flow；
- 写入失败或取消时不得发布领先于磁盘的内存状态；
- managed mutation 必须在 Store/commit boundary 拒绝，UI disabled 只是展示；
- Local shadow 不因 Managed 同 ID 覆盖而删除，Managed remove/disconnect 后应恢复；
- `restoreLocal()` 和 `snapshotLocal()` 只操作 Local shadow；
- `pendingAssistantDeletions` 虽为 `@Transient`，仍由独立 DataStore key 持久化，恢复普通 Settings 时不得清空。

## 3. Local Settings 顶层结构

下表中的“读取默认”以空 DataStore 的真实读取/物化结果为准，不以 `Settings()` 中为序列化兼容而存在的随机 UUID
占位值为准。配置演进时必须分别检查四种语义：Kotlin 构造默认、DataStore key 缺失默认、`materializeForRead()`
后的有效默认、Managed 字段未提供；它们不能相互代替。当前 JSON codec 使用 `ignoreUnknownKeys=true` 和
`encodeDefaults=true`。

### 3.1 外观、显示与开发选项

| `Settings` 字段 | 类型 | DataStore key | 读取默认 | 说明 |
|---|---|---|---|---|
| `dynamicColor` | `Boolean` | `dynamic_color` | `true` | Android 动态色 |
| `themeId` | `String` | `theme_id` | 首个预设主题 ID | 非动态色时的主题 |
| `customThemes` | `List<CustomTheme>` | `custom_themes` | `[]` | 用户自定义主题 |
| `developerMode` | `Boolean` | `developer_mode` | `false` | 开发者入口开关 |
| `displaySetting` | `DisplaySetting` | `display_setting` | `DisplaySetting()` | 聊天显示、通知、TTS 播放和输入偏好 |

### 3.2 模型选择、提示与派生任务

| `Settings` 字段 | 类型 | DataStore key | 读取默认 | 引用/用途 |
|---|---|---|---|---|
| `favoriteModels` | `List<Uuid>` | `favorite_models` | `[]` | 引用 `providers[].models[].id`；失效 ID 在读取模型过滤 |
| `chatModelId` | `Uuid` | `chat_model` | `DEFAULT_AUTO_MODEL_ID` | 全局 Chat 默认；Assistant 可覆盖 |
| `fastModelId` | `Uuid` | `fast_model` | `DEFAULT_AUTO_MODEL_ID` | 快速任务默认 |
| `titleModelId` | `Uuid?` | `title_model` | `null` | 标题生成显式选择 |
| `imageGenerationModelId` | `Uuid` | `image_generation_model` | `DEFAULT_AUTO_MODEL_ID` | Local standalone image generation |
| `titlePrompt` | `String` | `title_prompt` | `DEFAULT_TITLE_PROMPT` | 标题生成 prompt |
| `enableSuggestion` | `Boolean` | `enable_suggestion` | `true` | 是否生成后续建议 |
| `suggestionModelId` | `Uuid?` | `suggestion_model` | `null` | 建议生成显式模型 |
| `suggestionPrompt` | `String` | `suggestion_prompt` | `DEFAULT_SUGGESTION_PROMPT` | 建议生成 prompt |
| `attachmentInspectionModelId` | `Uuid?` | `attachment_inspection_model` | `null` | 文本模型无法原生看图时的配置化视觉模型 |
| `compressModelId` | `Uuid` | `compress_model` | `DEFAULT_AUTO_MODEL_ID` | 历史压缩模型 |
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
| `assistantId` | `Uuid` | `select_assistant` | `DEFAULT_ASSISTANT_ID` | 新会话/全局入口当前选择，不覆盖已有会话归属 |
| `assistantTags` | `List<Tag>` | `assistant_tags` | `[]` | Assistant 分组标签 |
| `searchServices` | `List<SearchServiceOptions>` | `search_services` | 至少物化 `SearchServiceOptions.DEFAULT` | Local Search provider 目录 |
| `searchCommonOptions` | `SearchCommonOptions` | `search_common` | `resultSize=10` | 公共搜索参数 |
| `selectedSearchServiceId` | `Uuid?` | `selected_search_service_id` | 缺失时由选择规范化/消费者回退 | 稳定 ID 选择；旧 index key 已迁移 |
| `mcpServers` | `List<McpServerConfig>` | `mcp_servers` | `[]` | Local MCP definition、headers、OAuth、工具策略；远端目录独立持久化 |
| `ttsProviders` | `List<TTSProviderSetting>` | `tts_providers` | 读取后补齐 System TTS | Local TTS 目录 |
| `selectedTTSProviderId` | `Uuid` | `selected_tts_provider` | `DEFAULT_SYSTEM_TTS_ID` | 当前 TTS 选择 |
| `defaultTTSPlaybackSpeed` | `Float` | `default_tts_playback_speed` | `1.0`，持久化限制 `0.5..2.0` | 公共播放速度 |
| `asrProviders` | `List<ASRProviderSetting>` | `asr_providers` | `[]` | 当前只有 Local realtime ASR 类型 |
| `selectedASRProviderId` | `Uuid?` | `selected_asr_provider` | 首个有效项或 `null` | 当前 ASR 选择 |
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
  id: Uuid                           # Local stable reference
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

Search 包含用户 API key/URL/账号，S0.2 不下发，也不能复用为 Managed Model/MCP 路由。
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

当前 Local ASR 全是 WebSocket/realtime controller 配置；S0.2 Managed ASR 是 HTTP multipart transcription，不能把
Managed `runtimePath/model/language` 强塞进这些 realtime 类型，也不能伪造 VAD/sample-rate 字段。

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

这些都是 Local durable/user data。S0.2 明确不包含 Agent Space、server-side conversation 或 User Sync。

### 5.3 Skills 与文件资源

- Skill 由 `filesDir/skills/<name>/SKILL.md` 及同目录资源构成；
- frontmatter 至少提供 `name/description`，可带 `compatibility`；
- `Assistant.enabledSkills` 只保存 Skill 名称引用；
- 自定义字体、Assistant 头像/背景、上传和生成图片分别由自己的文件 owner 管理。

Skill/Workspace/Assistant asset 不属于 S0.2 Snapshot。以后若要企业下发，必须先定义签名内容、稳定 ID、引用完整性、
版本、删除/回滚和本地同名冲突协议，不能继续扩展自由形态 `JsonObject records`。

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

## 7. 企业集成边界

现行平台契约以 S0.2 Snapshot v2 增加 Managed Assistant、Managed Memory Seed 与 Assistant Starter；S0.3 再增加
Enterprise Tool Gateway，S0.4 才完成 Android 对冻结 profile 的完整集成。Enterprise Update 使用独立 Feed，不进入
Snapshot；Enterprise Local Assistant Memory 属于 User Data，也不进入 Snapshot。

当前 Android 的 `ManagedConfigurationEnvelope` 是 `schemaVersion=1` 的内部签名 overlay 原型。它具备签名校验、
generation 单调、asset staging、Local shadow、effective projection 和写门禁，但没有 ClientRealm、Enrollment/Session、
Snapshot v2/v3 generated DTO、Enterprise Update/Portal 或生产同步入口。因此：

- 它不能冒充 S0.2/S0.4 实现，也不能通过 adapter 与未来平台协议并存为双 source of truth；
- Enterprise Binding、credential 与 Applied Snapshot 必须独立于 Local Settings、普通备份和 Provider/MCP credential；
- Local shadow 不因 Managed overlay 删除；Managed remove/disconnect 后恢复；
- Managed Assistant 与 Personal Assistant 必须保留 origin/provenance，不能把平台 ID hash 或转写为 Local UUID；
- 服务端 upstream、secret、runtime route 和 pricing 永不进入 Android Snapshot；
- UI disabled 只是投影，Managed mutation 必须在 command/commit boundary 拒绝；
- 新协议落地时应替换旧原型的传输与 owner 路径，物理删除无调用的旧协议，不保留兼容旁路。

Assistant 的当前 Local 字段和 S0.2 映射见
[助手配置参考](assistant-configuration.md)。平台 Snapshot、Realm、Gateway 和 Android full-profile 的精确 wire/阶段边界
以 MEASIX Architecture、冻结后的 Control Protocol 与 generated OpenAPI 为准；本仓库参考只登记已经实现的 Android
owner、持久化和消费边界，不复制阶段性 fixture、commit/hash 或 Freeze 待办。

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

构建/JVM 验证不能表述为真实 Enrollment、Keystore、后台同步、网络失败、428/revoke 或设备集成验收。相关生产路径
落地后，必须按冻结平台契约补齐真实 emulator/device 与服务端互操作验证。
