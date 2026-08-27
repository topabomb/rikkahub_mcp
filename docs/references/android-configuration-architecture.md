# Android 配置架构与企业下发清单

> 状态：当前实现事实登记 + S0.2 企业下发规划基线
> 审查日期：2026-08-27
> Android 实现仓库：`topabomb/rikkahub_mcp`
> 平台架构权威：`topabomb/measix-architecture` 的 S0.2 Android Integration Contract 与 S0 Control Protocol
> Executable wire：`topabomb/measix-platform-core/api/client/client-control.openapi.yaml`

本次登记基线：

| 仓库/Artifact | 调研版本 |
|---|---|
| `measix-architecture` | `dbb56952ab1cf60fa55e4cbb8d14ee70eda43a48` |
| `measix-platform-core` | `11e2b2efffac3ecb5690f08e3a03303d09d240eb` |
| `rikkahub_mcp` | `e80eafbe954690f9cd8c7c2252bbd8c888b8c8cc` |
| 当前 generated Android OpenAPI source hash | `sha256:67dd2bec0debbaf99d19543d96b3b39534a324536eaa347c657c96f49c807d11` |

这些是调研可复现基线，不是有效 C7 Freeze pin。

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

当前 S0.2 企业下发范围已经由架构收敛为：

```text
ManagedSnapshot
  ├─ providers[]
  ├─ models[]
  ├─ tts[]
  ├─ asr[]
  ├─ mcp[]
  └─ policy
```

因此：

- **Snapshot 必须携带**：Managed Provider/Model/TTS/HTTP-ASR/MCP typed 数组字段和 Managed Policy；五个数组均可为空，
  实际资源由 Release 决定；
- **必须独立保存但不是能力配置**：Enterprise Binding、Refresh Credential、Applied Snapshot payload/元数据；
- **S0.2 保持本地**：Assistant、Prompt、Memory、Search、Skill、Workspace、主题/显示、备份、快捷消息、图片生成、实时 ASR 等；
- **禁止下发到 Android**：`UpstreamDefinition`、`upstreamId`、base/internal URL、企业 API Key/Secret、RuntimeBinding、
  `runtimeRouteId`、Pricing；这不包含客户端必须接收的 `upstreamModelKey`；
- **禁止实现方式**：把服务端 payload 直接反序列化为 `Settings`，或把 Enterprise Token 写入本地 Provider/MCP credential 字段。

当前 `measix-platform-core` 明确仍处于 S0.1 pre-freeze，尚无有效 C7 Freeze。因此本文可以完成盘点和映射设计，
但 Android S0.2 wire/code 实现必须等 Freeze 后以固定的 commit、OpenAPI hash、fixture hash 和 schema version 开始。

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
| Enterprise Binding | S0.2 待实现的独立持久化 | Deployment/User/Device/Session binding | 否 |
| Enterprise credential | S0.2 待实现的 Keystore-backed store | Refresh Credential；Access Token 仅内存 | 否 |
| Applied Managed State | S0.2 待实现的 crash-safe whole state | Snapshot payload + generation/hash/schema/release | 否；与 credential 分离 |
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

主要实现：

- `data/datastore/SettingsStore.kt`
- `data/datastore/SettingsCommit.kt`
- `data/datastore/SettingsNormalization.kt`
- `data/datastore/SettingsWriteRules.kt`
- `data/datastore/EffectiveSettings.kt`
- `data/datastore/ManagedConfiguration.kt`

## 3. Local Settings 完整顶层结构（38 个持久 key）

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
| `mcpServers` | `List<McpServerConfig>` | `mcp_servers` | `[]` | Local MCP server、headers、OAuth、工具快照 |
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
  tools[]                       # discovery 后的可用工具和审批覆盖
  oauth?                        # Local OAuth state

McpOAuthState
  enabled, clientId, clientSecret,
  authorizationEndpoint, tokenEndpoint, registrationEndpoint, scope,
  accessToken, refreshToken, expiresAt

McpTool
  enable, name, description?, inputSchema?, needsApproval
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

当前手工备份格式是 `rikkahub-durable-v3`：包含 `settings.json`、`VACUUM INTO` 得到的 Room snapshot、manifest，以及
`upload/images/skills/fonts` 四个 durable 目录；不包含 `workspaces/tool_outputs/managed_configuration`。ZIP 未加密、未签名，
manifest 的 SHA-256 只提供完整性。Manifest 仍声明 `allowBackup=true`，所以企业 credential 设计还必须单独审计 Android
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

## 7. S0.2 企业下发的精确结构

以下结构来自当前 pre-freeze Client OpenAPI。Freeze 前仍允许按架构流程修正；Android 实现必须 pin 最终 frozen artifact。

### 7.1 Snapshot wrapper

```text
ManagedSnapshot
  deploymentId: dep_*
  schemaVersion: 1
  managedGeneration: integer >= 1
  releaseId: rel_*
  snapshotHash: sha256:<hex>
  providers: ProviderDefinition[]
  models: ModelDefinition[]
  tts: TtsDefinition[]
  asr: AsrDefinition[]
  mcp: McpDefinition[]
  policy: ManagedPolicy
  metadata:
    publishedAt
    publishedByUserId?
```

数组按稳定平台 ID 排序参与服务端 canonical hash；Android 只校验 HTTP ETag 与 `body.snapshotHash` 一致，并校验
TLS、deployment identity、schema 和引用后原子替换 Applied state。Android 不自行重算或发明 JSON canonicalization。

### 7.2 Provider / Model

```text
ProviderDefinition
  providerId: prv_*
  displayName: non-empty String
  clientProtocol: OPENAI_CHAT_COMPLETIONS
  enabled: Boolean

ModelDefinition
  modelId: mdl_*
  providerId: prv_*
  displayName: String
  upstreamModelKey: String
  runtimePath: path-only String
  inputModalities: [TEXT | IMAGE]
  outputModalities: [TEXT]
  capabilities: [TOOL | REASONING]
  enabled: Boolean
```

Managed Provider 只是客户端分组/协议身份，不是 Local `ProviderSetting`。它没有 base URL、API key、balance、Responses、
provider overwrite 或 custom header/body。

### 7.3 TTS / ASR / MCP

```text
TtsDefinition
  ttsId: tts_*
  displayName: String
  clientProtocol: OPENAI_AUDIO_SPEECH
  upstreamModelKey: non-empty String
  voice: non-empty String
  runtimePath: path-only String
  enabled: Boolean

AsrDefinition
  asrId: asr_*
  displayName: String
  clientProtocol: OPENAI_AUDIO_TRANSCRIPTIONS
  upstreamModelKey: non-empty String
  language?: String
  runtimePath: path-only String
  enabled: Boolean

McpDefinition
  mcpServerId: mcp_*
  displayName: String
  clientProtocol: MCP_STREAMABLE_HTTP
  runtimePath: path-only String
  authOwnership: ENTERPRISE_MANAGED | NONE
  enabled: Boolean
```

### 7.4 Policy

```text
ManagedPolicy
  policyId: pol_*
  allowLocalProviders: Boolean
  allowLocalTts: Boolean
  allowLocalAsr: Boolean
  allowLocalMcp: Boolean
  defaultModelId?: mdl_*
  defaultTtsId?: tts_*
  defaultAsrId?: asr_*
```

Policy default 引用 Managed 平台 ID，不能写入 Local `Settings.*Id: Uuid`。Effective runtime 必须保留 origin-aware identity，
由同一 picker/catalog 展示 Local + Managed，但使用各自原始稳定 ID。

### 7.5 绝不进入 Snapshot 的服务端字段

```text
RuntimeBindingDefinition
  runtimeRouteId, resourceId, upstreamId,
  allowedMethods, allowedPathPrefixes,
  transportPolicy, timeoutPolicy

Upstream base/internal URL
Enterprise Secret / API key / resolved credential
PricingRule
```

Android 只使用：

```text
{platform origin}/runtime/v1/resources/{resourceId}{runtimePath}
Authorization: Bearer <enterprise access token>
X-Measix-Managed-Generation: <captured generation>
X-Measix-Interaction-Id: int_*
```

## 8. 企业控制状态：部分必须持久化，但不是配置下发项

### 8.1 Discovery、Enrollment 与 Bootstrap wire

Android 从用户输入的 secure platform origin 开始，只把服务端返回的 path-only base 拼到该 origin；不能接受 Snapshot
下发新的 host。当前 pre-freeze Discovery 完整结构为：

```text
Discovery
  product: MEASIX_AGENT_PLATFORM
  protocolVersion: "1"
  deploymentId: dep_*
  deploymentName: String
  clientApiBase: path-only String
  runtimeApiBase: path-only String
  supportedSnapshotSchemaVersions: Integer[]
```

Enrollment exchange 结构为：

```text
request
  code
  installationId: ins_*
  deviceName
  appVersion
  platform: ANDROID

response
  deploymentId: dep_*
  userId: usr_*
  deviceId: dev_*
  sessionId: ses_*
  accessToken
  accessTokenExpiresAt
  refreshToken
  refreshExpiresAt
```

Refresh request 只携带 `refreshToken`，response 只返回新 `accessToken/accessTokenExpiresAt`；Logout 同样以
refresh token 撤销 session。Bootstrap 返回：

```text
deployment { deploymentId, name }
user       { userId, displayName }
device     { deviceId, status: ACTIVE | REVOKED }
session    { sessionId, expiresAt }
supportedSnapshotSchemaVersions[]
managedState
```

### 8.2 Binding 与 credential partition

```text
BindingState = UNBOUND | ENROLLING | BOUND | REVOKED

durable binding identity
  platform origin
  deploymentId
  userId
  deviceId
  sessionId
  refresh/session expiry metadata
```

`installationId` 由 Android 生成；User/Device/Session ID 必须由 Hub 返回。Refresh Credential 使用 Keystore-backed
保护，Access Token 仅内存。Disconnect 只清 Enterprise Binding/Credential/Managed state，不删除 Local Settings/history。

### 8.3 Managed readiness 与 Applied state

Control plane 的 `ManagedState` wire 与 Android 派生状态不是同一个 enum：

```text
ManagedState
  runtimeStatus: READY | ACTIVATING | DEGRADED
  activeManagedGeneration: Integer >= 0
  managedStateRevision: Integer >= 0
  syncRequired: Boolean
  targetManagedGeneration?: Integer >= 1
  runtimeBlocked: Boolean

ManagedRuntimeState
  UNKNOWN | READY | SYNC_REQUIRED | SYNCING |
  SYNC_FAILED | CONTROL_UNAVAILABLE

AppliedManagedState                  # crash-safe whole-state unit
  snapshot payload
  appliedManagedGeneration
  snapshotHash
  schemaVersion
  releaseId
  validated recovery metadata
```

每个新的顶层 Managed interaction 必须先获取 authoritative Managed State；并发可以共享 single-flight preflight/sync，
但每个 interaction 捕获独立 immutable `{interactionId, generation, effectiveRuntime, startedAt}`。不能用 TTL、本地 LKG 或
全局 mutable current interaction 绕过 guard。

### 8.4 Generation deny 与禁止 replay

只有确有 Applied Snapshot 时，preflight 才发送：

```http
X-Measix-Applied-Managed-Generation: N
```

Runtime Relay 的 generation mismatch 必须在 forward 前返回：

```text
HTTP 428
code: managed_snapshot_required
targetManagedGeneration
requestId
forwarded: false
```

Android 终止当前 interaction/request 并进入 sync；同步完成后，只有下一次新的 user/top-level action 才能用新的
interaction identity 与 generation。不得在同一 interaction 自动 replay 原 user/tool/MCP side effect，也不存在 generation
grace window。

## 9. Android Local → Managed 映射与缺口

| 领域 | 可复用的现有边界 | 不能直接复用的 Local 结构 | S0.2 所需结果 |
|---|---|---|---|
| Provider/Model | OpenAI Chat Completions 请求、stream/tool/render pipeline | `ProviderSetting` 含 baseUrl/apiKey；Local ID 是 UUID；Managed ID 是 `prv_*/mdl_*` | 独立 Managed projection + 平台 endpoint/auth adapter，进入同一 Effective runtime/picker |
| TTS | OpenAI request、binary audio playback/save/cancel | Local TTS 把 baseUrl/apiKey/model/voice 放在一个对象 | 使用 Snapshot model+voice，平台路由和 auth；禁止 Local voice fallback |
| ASR | 音频采集和文本消费 UI | 现有 `ASRProviderSetting` 全是 WebSocket realtime | 增加 HTTP multipart Managed execution adapter，不制造 VAD/sample-rate |
| MCP | Streamable HTTP client、工具 discovery/call | Local URL、headers、OAuth 和连接恢复可绕过企业 guard | 平台 runtime URL + authOwnership；connect/call/reconnect 全部受 interaction/generation guard |
| Policy/default | 现有 origin/lock UI 和 Store 写门禁可提供经验 | 当前 lock 是任意 record path；默认 ID 是 UUID | 按 frozen policy 决定 availability/default；Managed mutation 在 command/commit boundary 拒绝 |
| Persistence | `SettingsStore` 的落盘后发布、单一 effective read model | 当前 managed 文件不是 binding/credential/applied-state whole unit | Binding、credential、Applied state 分 owner，原子发布统一 Effective runtime revision |

Managed runtime identity 不能通过“把 `mdl_*` hash 成 UUID”伪装成本地 ID，也不能把 Managed Policy default 写进 Local Settings。
实现需要一个 origin-aware key，例如概念上的 `{origin, stableId}`，但不能因此复制第二套用户 picker 或第二套 Chat runtime。

## 10. 企业下发分级清单

| Android 配置域 | S0.2 结论 | 原因/后续条件 |
|---|---|---|
| Managed Provider grouping | **下发** | required Snapshot profile |
| Managed Chat Model | **下发** | OpenAI Chat Completions required profile |
| Managed TTS | **下发** | OpenAI Audio Speech + required voice |
| Managed HTTP ASR | **下发** | OpenAI Audio Transcriptions multipart |
| Managed MCP | **下发** | Streamable HTTP + auth ownership |
| Managed Policy/default | **下发** | Local capability availability + Managed defaults |
| Binding/Managed State | **控制面同步** | 身份/正确性状态，不是 Snapshot resource |
| Refresh/Access Token | **安全会话状态** | 不得进入 Settings/Snapshot/backup |
| Local Provider/Model/API key | **保持本地** | Local Runtime 完整；enterprise secret server-side |
| Assistant/Tag/Prompt/Quick Message | **S0.2 不下发** | 当前 Snapshot 无定义；属于 Agent/体验定义扩展 |
| Memory/Recent chats/Conversation override | **S0.2 不下发** | User Data Sync 与 Managed Delivery 永久分离 |
| Search | **S0.2 不下发** | required Managed profile 未定义 Search |
| Skill | **S0.2 不下发** | 需要签名 bundle、ID、版本、引用和生命周期协议 |
| Workspace/local tool approval | **S0.2 不下发** | Agent Space/Remote Runtime 在后续 Stage |
| Theme/Display/Language/UI preference | **保持本地** | 个人设备体验，不是执行能力定义 |
| WebDAV/S3/Backup reminder | **保持本地** | 用户备份域，不能承载 enterprise sync |
| Title/Suggestion/Compression prompt/model | **保持本地** | S0 Snapshot 没有相应资源/任务语义 |
| Image Generation/Embedding | **S0.2 不下发** | 明确非 required profile |
| Local realtime ASR | **保持本地** | Managed v1 仅 HTTP transcription |
| RuntimeBinding/Upstream/Secret/Pricing | **禁止下发** | server-only topology/security/operations |

以后要下发 Assistant、Prompt、Search、Skill 或 Workspace 时，应各自成为 typed definition/bundle，并先更新 architecture、
Control Protocol、OpenAPI、fixtures、Android compatibility 和测试。不要把完整 `Settings` 或任意 `JsonObject` 当扩展协议。

## 11. 现有 ManagedConfiguration 原型的定位

当前 Android 已有一套内部原型：

```text
ManagedConfigurationEnvelope
  schemaVersion, keyId, tenantId, generation, expiresAt, signature
  payload
    records[]       # Provider/Assistant/Tag/MCP/TTS/ASR/Search/Injection/QuickMessage
    defaults        # 多个 Local UUID selector
    locks
    assets / assistant asset bindings
    revoked
```

原型的 `records.kind` 完整集合是 `PROVIDER/ASSISTANT/ASSISTANT_TAG/MCP_SERVER/TTS_PROVIDER/ASR_PROVIDER/
SEARCH_SERVICE/MODE_INJECTION/QUICK_MESSAGE`；`defaults` 完整集合是 chat、fast、title、image-generation、
attachment-inspection、compress model，以及 Assistant、Search、TTS、ASR selector。它不能表达 suggestion model/prompt、
Display、Search common option、播放速度或集合级 allow/deny policy。

原型文件是 `filesDir/managed_configuration/active.envelope` 和 `generation-<n>/assets/`，envelope 上限 2 MiB；当前
trust anchor 固定为 `keyId=rikkahub-managed-v1`、`tenantId=rikkahub`。运行状态为
`ABSENT/ACTIVE/DEGRADED/BLOCKED`：过期包进入 `DEGRADED` 但继续提供已验证 overlay/lock，损坏的已存包进入
`BLOCKED`，更高 generation 的签名 `revoked` 包回到 `ABSENT`。

它具备签名校验、generation 单调、asset staging、原子 envelope 替换、Local shadow merge 和 write lock 等实现经验，
但**不是当前 S0.1/S0.2 Client Snapshot contract**：

- transport shape 与 OpenAPI `ManagedSnapshot` 不同；
- 使用硬编码 tenant/key trust anchor，而 S0 使用 Enrollment/Deployment/Session/TLS/ETag contract；
- record/default identity 是 Android UUID，平台资源是 typed `prv_*/mdl_*/tts_*/asr_*/mcp_*`；
- 下发 Assistant/Search/Prompt/assets，而 S0.2 required Snapshot 不包含这些域；
- 缺少 `ManagedPolicy`、Binding、secure credential、Managed State preflight、interaction context 和 Relay 428 语义；
- `SettingsStore.applyManagedSnapshot()` 当前只有测试调用，没有生产 Enrollment/Sync 接入者。
- `SettingsStore.restoreLocal()` 当前只替换 Local shadow，允许其在 overlay 遮挡期间继续变化、并在 Managed remove/disconnect
  后重新显现；这符合 S0 Local-shadow 原则。它绕过当前旧原型的自定义 `locks`，是否要约束整份备份恢复只是旧原型
  语义，不能外推为 S0.2 Managed mutation 规则。
- `McpManager.syncTools()` 和 OAuth 刷新会写 `updateLocal()`；Managed MCP 的 discovery cache、用户审批偏好和
  credential 不能继续共用整条 Managed Definition 的写协议。

S0.2 实施时不应给该原型再套一层兼容 adapter 或保留双 transport/source of truth。应在有效 Freeze 后以 frozen generated types
和 canonical fixtures 重建唯一 Managed ingestion path；能复用的仅是已验证的原子持久化、Local shadow、effective projection、
source/lock UI 和提交门禁原则。旧 envelope/record path 在切换完成时应物理删除或完全收口为新的唯一协议实现。

## 12. Freeze 前必须由权威契约解决的问题

下面不是 Android 可自行选择的实现细节。它们在 C7 Freeze 前必须由 architecture/OpenAPI/fixtures 或 S0.2 Android
implementation decision 给出唯一答案：

1. **双域 ID 与 shadow 规则**：Local 资源使用 `Uuid`，平台使用 `prv_*/mdl_*/tts_*/asr_*/mcp_*`；当前没有可执行的
   “同稳定 ID 覆盖”关系。必须明确 origin-aware identity 与冲突语义，不能 strip prefix、hash 成 UUID 或碰撞后取首项。
2. **一个 `defaultModelId` 对 Android 多模型角色的作用范围**：必须明确它只影响 Chat effective default，还是会影响
   Assistant、fast/title/compress/attachment inspection；它绝不能误覆盖 Image Generation。
3. **Local 被禁止后的引用/fallback**：当 Assistant 显式引用 Local model、`allowLocalProviders=false` 且 Managed default
   缺失时，是 unavailable 还是选择某个 Managed model，必须有确定规则；不得回写或清空 Local shadow。
4. **disabled 资源的 default 与 Relay enforcement**：当前 Core validation 只检查 policy default 引用“存在”，不检查
   `enabled`；Runtime Control 也按全部 bindings 编译 route。Freeze 前必须保证 disabled 资源不能成为有效 default，且不能
   被客户端绕过 catalog 直接调用。
5. **Model 字段约束**：当前 OpenAPI/后端没有像 TTS/ASR 一样要求 Model `upstreamModelKey` 非空，也没有规定
   modalities/capabilities 的非空、去重组合；Android 不能发明隐式默认。
6. **Managed MCP tool policy**：Snapshot 没有 tool enable/approval 字段。必须明确 discovery cache 的 owner、用户能否本地
   disable/require approval、这些偏好如何跨 generation 继承，以及它们是否算 Managed mutation。
7. **Snapshot hash fixture 完整性**：当前 Go `snapshotDescriptor + json.Marshal` 是服务端 canonical authority，只有
   `snapshot/generation-42.json` 参与 golden hash；`full-required-profile.json` 的 hash 只是格式占位。Freeze 必须提供
   可执行的 full-profile hash fixture 与 artifact manifest。Android 只验证 HTTP ETag 等于 `body.snapshotHash`，不能重写一份
   可能漂移的 canonicalization。
8. **response forward compatibility**：OpenAPI 对对象使用 `additionalProperties:false`，协议同时要求 client 忽略新增的
   optional response 字段。Android DTO/decoder 必须登记 `ignoreUnknownKeys` 等等价规则，同时保持 request strict。
9. **Binding 与 Keystore 的 crash recovery**：Binding metadata 与 refresh credential 分属不同存储；必须定义
   staging/commit/recovery 顺序，保证 Enrollment 失败或进程终止不会留下 half binding。
10. **Android Freeze pin 落点**：当前 Core 的 `api/generated/android/` 只有 OpenAPI copy 与 manifest；Android 仓库尚无
    Kotlin DTO、已消费的 manifest pin、hash drift gate 或 fixture consumer。这些必须在开始 S0.2 code 前登记。

在以上问题解决且 C7 Freeze 生效前，本文状态只能是“pre-freeze architecture registry”，不能当成最终 v1 wire 或
Android 已具备企业下发能力的声明。

## 13. 文档与实现同步规则

以下变化必须同步本文：

- `Settings` 顶层字段、默认读取值或 DataStore key；
- Provider/Model/Assistant/TTS/ASR/MCP/Search 的持久字段；
- SharedPreferences 产品 key；
- Workspace/Skill/会话覆盖的配置边界；
- Local backup 包含/排除范围；
- Effective settings owner、merge、origin 或 write-lock 语义；
- frozen Client Snapshot schema、required profile 或 Managed Policy；
- 某 Local 配置域被提升为 typed Managed Definition。

对应验证至少包括：

1. Settings key/serialization/default compatibility；
2. Local backup 不包含 Enterprise Binding/credential/Applied state；
3. frozen OpenAPI/fixture/generated type drift gate；
4. Local + Managed same-identity/不同-origin 的 deterministic merge；
5. Managed mutation UI disabled + command/commit deny；
6. Model/TTS/HTTP-ASR/MCP 全入口 guard 与 endpoint/auth 映射；
7. invalid/corrupt Snapshot 保持 LKG 和 Local，不出现 half commit；
8. 428/revoke/auth/network/cancel 不自动重放可能有副作用的请求；
9. real emulator/device 从 Enrollment 开始的完整 T4 路径；
10. Local runtime 全量回归。

## 14. 权威来源与更新顺序

需求与语义按以下顺序裁决，后层不能反向改写前层：

1. `measix-architecture/README.md`、`docs/measix-stage-document-index.md`；
2. `docs/10-runtime-foundation/s0/measix-s0-foundation-contract-spec.md`；
3. `measix-s0-capability-delivery-contract-spec.md`、`measix-s0-control-protocol.md`、
   `measix-s0-android-integration-contract-spec.md`、`measix-s0-android-client-testing-spec.md`；
4. `measix-platform-core/api/client/client-control.openapi.yaml` 与 `api/fixtures/`；
5. 有效 C7 Freeze manifest；当前进度以 `measix-platform-core/docs/s0-execution-progress.md` 为准；
6. Android 的 `SettingsStore.kt`、`ManagedConfiguration.kt`、`EffectiveSettings.kt`、各领域 data model 与 application service；
7. 本文和各领域 reference 文档。

Architecture 负责产品/跨组件契约，Core 负责 executable wire/fixtures，Android 仓库负责本地配置、映射、持久化与执行细节。
如果三者冲突，先在 owner 层修正并重新 Freeze，不能只在本文或 Android adapter 中加兼容旁路。
