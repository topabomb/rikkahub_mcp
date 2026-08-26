# 可持久化记录与云端同步基线

本文是配置治理、可持久化记录与云端同步的设计基线。它以当前代码为事实，回答以下问题：

1. 应用有哪些持久化介质、记录、引用和文件载荷；
2. 当前读写链路的职责、并发、一致性和性能边界；
3. 企业下发、助手市场、MCP 市场应怎样落到这些记录上；
4. 在不改变现有落盘结构的前提下，准备阶段必须先收口哪些架构问题。

当前落盘模型**没有**来源标记、企业只读或市场导入协议。准备阶段只增加了非持久化的写入来源与
策略插槽；文中企业覆盖、锁索引和市场包仍是设计约束，不是已实现业务能力或序列化字段。

> 准备阶段约束：不增加或修改 DataStore key、JSON 字段、Room Entity/表/索引/版本、SharedPreferences
> key、文件目录和备份 ZIP entry；不实现企业关联、登录、下发或市场协议。先建立单一写入口、读取物化、
> 写入授权和依赖边界，再进入数据结构迁移阶段。

---

## 1. 产品需求与抽象

未来要支持以下能力：

1. **企业下发**：提供商、模型、企业付费 TTS/ASR、企业 MCP 等。企业记录在 App 内不可改；用户仍可自建同类型记录。
2. **助手市场**：下载助手定义。同 ID 覆盖，或生成新 ID（重命名/复制）。
3. **MCP 市场**：下载用户可编辑的 MCP 连接。企业 MCP 与用户 MCP 共存。

这些能力不是「按类型一刀切」，而是同一类型上的**来源与可变性**不同。落地前用下面的记录模型，不要按「Provider 整表只能企业下发」来设计。

| 概念 | 含义 |
|------|------|
| **记录** | 有稳定身份、可单独增删改的一条配置或数据 |
| **身份** | 覆盖/引用用的键。多数是 `Uuid`；Skill 是目录名 |
| **载荷** | 身份之外的可序列化内容 |
| **机密** | 载荷里的密钥、令牌、密码。与公开定义分开传输和权限 |
| **引用** | 指向另一条记录身份的字段。导入必须重映射或校验 |
| **来源** | 建议值：`local` / `marketplace` / `enterprise`。现网不存在 |
| **可变性** | `enterprise` 在客户端只读；`local`/`marketplace` 默认可改 |
| **通道** | `enterprise-overlay`（按身份合并） / `marketplace-package`（图 + 重映射） / `preference-sync`（用户偏好） / `backup`（整库与文件） |

细化后的产品规则：

- 企业下发是**叠加**，不是独占某个 Kotlin 类型。用户 Provider 与企业 Provider 同表共存。
- 市场包是**引用图**，不是单条 `Assistant` JSON。助手会引用 MCP、注入、快捷消息、标签、Skill、工作区。
- 覆盖 = 同身份替换公开载荷；复制 = 新身份 + 重写包内引用。
- 记忆默认**不进**助手市场包。它按 `assistantId` 隔离，但是用户私有运行数据。
- 机密不进市场包。企业机密只走企业通道，客户端不可编辑。
- `searchServiceSelected` 是列表下标，不能作为跨设备身份。选中项必须改成记录 `id` 后再做同步。
- Child Conversation（`parentConversationId != null`）不是用户配置，不进市场，也不进企业下发。

---

## 2. 存储拓扑

| 存储 | 入口 | 持久化内容 | 同步定位 |
|------|------|------------|----------|
| DataStore Preferences `"settings"` | `SettingsStore` / `Settings` | 全局配置；每个逻辑字段一个 key，复杂对象为独立 JSON 字符串 | 企业/市场/偏好的主要本地底座，但不是云端真源 |
| SharedPreferences `"MeasixPilot.preferences"` | `ui/hooks/SharedPreferences.kt` | 颜色模式、AMOLED、宽屏侧栏、启动时新建会话、最近会话、搜索排序等设备 UI/导航状态 | 默认不上云；不能混入企业配置 |
| SharedPreferences `"crash_handler"` | `CrashHandler` | 崩溃标记与截断堆栈 | 仅设备诊断，不备份、不同步 |
| Room `AppDatabase` | DAO / Repository | 会话、消息节点、记忆、工作区、文件元数据、生成媒体、收藏、文件夹 | 运行数据与备份；不是企业配置表 |
| 应用 `filesDir` | `FileFolders`、`SkillManager`、`WorkspaceManager`、`FilesManager`、`GeneratedMediaStore` | Skill、上传、字体、工具输出、生成媒体、Workspace Rootfs、FTS 词典 | 按目录分别进入市场、备份或不上传 |
| 应用 `cacheDir` | 备份、OCR、导出、相机等入口 | 可重建的下载包、OCR cache、导出和拍摄临时文件 | 不同步，不得作为持久化真源 |
| 备份 ZIP | `WebDavSync` / `S3Sync` | `settings.json`、数据库/WAL/SHM、部分 `filesDir` 目录 | 灾备格式；与记录级配置同步严格分离 |

生产数据库名是 `measix_pilot`，Room 当前使用 WAL 与显式历史迁移链。`message_fts` 是在数据库
打开回调里建立的运行时 FTS5 虚表，不属于 Room schema JSON；数据库备份和恢复仍必须考虑它与
Jieba 扩展的重建行为。

写入规则（当前实现）：

- `SettingsStore.updateAtomic`：互斥读最新非 dummy Settings → transform → 写策略 →
  `normalizeForPersistence` → DataStore 标量规范化 → DataStore 写入成功 → `materializeForRead` →
  发布 `settingsFlow`。
- `normalizeForPersistence` **只**规范化 `Assistant.description`、关闭类别时清掉 `isSubAssistantGloballyVisible`、按 `assistantId` 去重 tombstone。它**不**清理失效引用。
- DataStore 标量规范化只复用原有落盘约束：空搜索服务列表补默认项、搜索下标限制在有效范围、
  TTS 默认速度限制在有效范围。约束在提交准备阶段完成，避免磁盘值与提交返回值不一致。
- 失效引用、重复 id、内置 Provider/助手/TTS 补齐由纯 `materializeForRead` 负责；DataStore 回读与
  提交后发布共用它。导入/同步如果只调用 `normalizeForPersistence`，仍不会得到读取物化那套清理。
- `SearchServiceOptions`、`TTSProviderSetting`、`ASRProviderSetting` 用 `decodeListLenient`：未知密封子类会被跳过。
- `ProviderSetting.builtIn` 与描述 lambda 是 `@Transient`。磁盘上没有 `builtIn`；加载时若 id 落在 `DEFAULT_PROVIDERS` 里，再把内置标记和描述补回去。
- `Settings.pendingAssistantDeletions` 在数据类上是 `@Transient`，因此不会进整份 Settings JSON / 备份快照；它另有 DataStore key，供跨进程删除恢复。

### 2.1 当前 Settings 读取链路

```text
DataStore Preferences
  -> 逐 key 解码为持久化 Settings 快照
  -> 补齐内置 Provider / Assistant / TTS
  -> 恢复 builtIn、description 等运行时属性
  -> 按 id 去重并清理部分失效引用
  -> settingsFlow（应用消费的有效快照）
```

“持久化快照”和“应用有效快照”不是同一个概念。补齐内置项与清理引用只用于读取，不应无提示地
反写 DataStore。写成功后的主动内存发布必须复用同一读取物化函数，否则可能先发布含重复项或失效
引用的值，再被 DataStore 回读改成另一份值。

### 2.2 当前 Settings 写入链路

```text
调用方 transform
  -> SettingsStore Mutex 内读取最新非 dummy 快照
  -> 写入来源/策略边界
  -> normalizeForPersistence
  -> DataStore 标量规范化
  -> 单次 DataStore edit 写完全部 key
  -> edit 成功
  -> 用读取物化后的同构快照发布 settingsFlow
```

写失败时不得发布内存值。`pendingAssistantDeletions` 只有明确的删除恢复流程可以增删；备份 JSON、
UI 快照和市场包都不能因 `@Transient` 默认值而清空它。

### 2.3 Room 与文件双写

Room 事务只能保证数据库内部一致性，不能覆盖 `filesDir`。当前项目按不同业务采用补偿式协议：

- `FilesManager` 以 Mutex 串行文件/`artifact` 变更，写 DB 失败时删除已创建文件（`registerTrackedFile`
  同步登记失败回滚，"文件 + 记录"要么都在要么都不在）；启动 `ArtifactStore.reconcileStartup()`
  单向收敛磁盘侧外部不一致（缺失行清理、untracked 仅日志，无补录）；
- `GeneratedMediaStore` 使用 pending 文件、rename、Room 元数据与 `reconcile`；
- `SkillManager` 用 staging/backup 目录原子替换 Skill，但删除 Skill 后再清理 Assistant 引用，
  失败时需要后续 orphan prune；
- Assistant 删除先持久化 tombstone，再停止运行、清理 Room/文件，最后移除 tombstone。

未来同步不能假设“一个 Room transaction 可以同时提交文件”。每个跨介质操作都必须明确提交顺序、
幂等键、补偿动作和启动恢复入口。

### 2.4 持久化与同步依赖边界

| 依赖/机制 | 当前用途 | 不能承担的职责 |
|-----------|----------|----------------|
| AndroidX DataStore Preferences | `settings` 的原子 `edit`、Flow 回读与 I/O 错误传播 | 不是记录级数据库；标准 `preferencesDataStore` 委托也不是未来多进程企业同步协议 |
| kotlinx.serialization | `Settings` 各复杂 key、Room TypeConverter、备份 JSON；`JsonInstant` 忽略未知字段并编码默认值 | 不能验证来源、签名、引用完整性或机密权限；宽松解码不能等同导入成功 |
| Kotlin Coroutines / Flow / Mutex | 串行 Settings transform、发布有效快照、观察缓存依赖 | 进程内 Mutex 不能替代跨进程锁、远端 generation 或跨介质事务 |
| Room + KSP | 结构化运行数据、DAO/Repository、migration 与 schema 导出 | Room transaction 不能覆盖 `filesDir`，也不能让打开中的数据库文件恢复天然安全 |
| requery SQLite + simple/Jieba 扩展 | 实际 SQLite 驱动、运行时 FTS5 与分词能力 | 运行时创建的 FTS 对象不由 Room schema JSON 完整描述，备份恢复必须另行验证重建 |
| `java.util.zip` | 当前 WebDAV/S3 备份 archive | 只提供流式压缩/解压；不提供 manifest、签名、路径授权、原子恢复或回滚 |
| Ktor `HttpClient` | 自研 WebDAV client、S3 client 与 SigV4 传输 | transport 成功不代表包可信、完整或可提交；企业验签必须位于独立通道 |
| Koin | 组装 Store、Repository、Manager、缓存观察者 | 业务对象不应通过 `KoinComponent` 反向查找上层依赖或隐藏写副作用 |
| Pebble | Assistant message template 编译缓存 | 缓存失效不是持久化职责，缓存键本身必须包含决定编译结果的模板内容 |

依赖方向固定为：UI → ViewModel/记录命令 → Store/Repository/领域 Manager → DataStore、Room 或文件；
同步入口只能调用记录命令与领域 Manager，不能直接从 UI 写 Store，也不能绕过补偿协议直写 DAO + File。
未来 `EffectiveSettingsResolver` 与 `RecordLockIndex` 位于消费模型和写策略层，不反向塞进 DataStore
序列化模型。

---

## 3. 记录目录

只列**同步时要当一条记录对待**的东西。内嵌值对象（`AssistantRegex`、`CustomHeader`、`McpTool`、`BalanceOption`）随父记录走，不单独建通道。

### 3.1 DataStore 记录

| 记录 | 身份 | 机密 | 主要引用 | 建议通道 | 说明 |
|------|------|------|----------|----------|------|
| `ProviderSetting` | `id` | `apiKey`、Google `privateKey` 等 | 内嵌 `Model.id` | 企业叠加，或用户本地 | `OpenAI` / `Google` / `Claude`。内置预设靠固定 id 在加载时补齐 |
| `Model` | `id`（不是 `modelId`） | 可经 `providerOverwrite` 再带一份密钥 | 可嵌套另一份 `ProviderSetting` | 随所属 Provider | `modelId` 是线协议名；同步身份必须用内部 `id` |
| 全局模型选择 | 各 `Settings.*ModelId` | 无 | → `Model.id` | 企业可覆盖默认聊天模型；其余多为偏好 | 未写入时聊天/快速/压缩回退 `DEFAULT_AUTO_MODEL_ID` |
| `Settings.assistantId` | 无（单值） | 无 | → `Assistant.id` | 偏好或不上云 | 只表示新建会话等入口的当前助手，不是已有会话归属 |
| `McpServerConfig` | `id` | `headers`、OAuth 令牌 | 无出站引用 | 企业只读 **或** MCP 市场 | `SseTransportServer` / `StreamableHTTPServer`。`tools` 是上次同步缓存，不是用户编辑源 |
| `TTSProviderSetting` | `id` | 云端 TTS 的 `apiKey` | 无 | 企业或本地 | `SystemTTS` 无密钥。`selectedTTSProviderId` 是选择，不是定义 |
| `ASRProviderSetting` | `id` | `apiKey` | 无 | 企业或本地 | `selectedASRProviderId` 加载时若失效会改选列表第一项 |
| `SearchServiceOptions` | `id` | Tavily `apiKey`、SearXNG 密码 | 无 | 企业或本地 | **当前选中是 `searchServiceSelected` 下标**，同步前必须改为按 `id` |
| `Assistant` | `id` | 无直接密钥 | 见 §4 | 助手市场 | 定义见 `Assistant.kt` |
| `PromptInjection.ModeInjection` | `id` | 无 | 被助手/会话引用 | 可随助手打包 | |
| `QuickMessage` | `id` | 无 | 被助手引用 | 可随助手打包 | |
| `Tag` | `id` | 无 | 被 `Assistant.tags` 引用 | 可随助手打包 | |
| 模型级 Prompt 字符串 | 无（单值） | 无 | 无 | 企业可选覆盖 | `titlePrompt` / `suggestionPrompt` / `ocrPrompt` / `compressPrompt` |
| `DisplaySetting` 与主题 | 无或 `CustomTheme.id` | 自定义字体是本地路径 | `chatCustomFontPath` → `filesDir/fonts` | 偏好同步 | 不要逐字段同步；整包或白名单 |
| 备份端点 | 无 | WebDAV/S3 密码与密钥 | 无 | 默认不同步到企业/市场 | 这是备份通道自己的凭据 |
| `developerMode` / `launchCount` / `ignoredUpdateVersion` | 无 | 无 | 无 | 不同步 | 设备或进程本地 |

`Settings` 不是天然的一条同步记录；它只是当前 DataStore 的内存聚合。企业/市场同步必须按上表的
记录边界产生操作，不能把远端整份 `Settings` 覆盖到本地。备份恢复可以替换用户快照，但仍要经过
独立来源与内部状态保护。

`Assistant` 上同步真正危险的是引用，不是把整份数据类再抄一遍：

| 引用字段 | 指向 |
|----------|------|
| `chatModelId` | `Model.id` |
| `tags` | `Tag.id` |
| `modeInjectionIds` | `ModeInjection.id` |
| `quickMessageIds` | `QuickMessage.id` |
| `mcpServers` | `McpServerConfig.id` |
| `workspaceId` | `WorkspaceEntity.id` |
| `enabledSkills` | Skill 目录名 |
| `allowedSubAssistantIds` | 其他 `Assistant.id` |

`localTools`、`regexes`、`presetMessages`、`customHeaders`/`customBodies` 是内嵌值，随助手复制。`LocalToolOption` 的序列化名是 `javascript_engine`、`assistant_delegation` 等，以代码为准。

### 3.2 Room 记录

| 记录 | 身份 | 引用 | 建议通道 | 说明 |
|------|------|------|----------|------|
| `ConversationEntity` | `id` | `assistantId`、`folderId`、`parentConversationId`、`modeInjectionIds` | 仅备份 | `nodes` 列已空置；消息在 `MessageNodeEntity`。`tags` 仍在会话行上 |
| `MessageNodeEntity` | `id` | `conversation_id` | 随会话备份 | `messages` 为 `List<UIMessage>` JSON |
| `MemoryEntity` | 自增 `id` | `assistantId`（或 `GLOBAL_MEMORY_ID`） | 默认不上市场；可另做用户备份 | 关记忆开关不删数据 |
| `WorkspaceEntity` | `id` | 被 `Assistant.workspaceId` 引用 | 定义可打包；Rootfs 走文件/备份 | `root` 唯一。`toolApprovals` 是用户覆盖 |
| `FolderEntity` | `id` | `assistantId` | 备份 | 助手内分组 |
| `FavoriteEntity` | 自有主键 | 指向消息/会话 | 备份 | |
| `GenMediaEntity` / `ArtifactEntity` | 自有主键 | 文件路径 | 备份 | 元数据；字节在 `filesDir` |

普通用户列表、搜索、最近对话工具只看 `parent_conversation_id IS NULL` 的会话。Child 行是子助手内部工作区。

### 3.3 文件系统

| 路径 | 内容 | 建议通道 |
|------|------|----------|
| `filesDir/skills/<name>/` | `SKILL.md` 与附属文件 | 市场可按名覆盖或改名；助手只引用名字 |
| `filesDir/upload/` | 用户上传与聊天附件 | 备份 |
| `filesDir/fonts/` | 自定义聊天字体 | 偏好同步需带文件，或只同步主题、不带路径 |
| `filesDir/tool_outputs/` | 工具超长输出落盘 | 不进配置同步 |
| `filesDir/workspaces/<root>/` | Workspace Rootfs 与用户文件 | 备份或企业镜像；体积大 |
| `filesDir/images/` | 文生图/编辑图的规范产物；元数据在 `GenMediaEntity` | 用户备份；不进配置下发或市场 |
| `filesDir/jieba/` | FTS simple tokenizer 运行词典 | 可由应用资源重建，不同步 |
| 助手 `avatar` / `background` 的本地 URI | 图片文件 | 市场包要内嵌或改成远端 URL |

---

## 4. 引用图

```text
Settings
├─ providers[]
│  └─ models[]
│     └─ providerOverwrite?          → 另一份 Provider 载荷
├─ *ModelId / favoriteModels         → Model.id
├─ assistantId                       → Assistant.id
├─ assistants[]
│  ├─ chatModelId                    → Model.id
│  ├─ tags                           → Tag.id
│  ├─ modeInjectionIds               → ModeInjection.id
│  ├─ quickMessageIds                → QuickMessage.id
│  ├─ mcpServers                     → McpServerConfig.id
│  ├─ workspaceId                    → WorkspaceEntity.id
│  ├─ enabledSkills                  → skills/<name>
│  └─ allowedSubAssistantIds         → Assistant.id
├─ mcpServers[] / ttsProviders[] / asrProviders[] / searchServices[]
├─ modeInjections[] / quickMessages[] / assistantTags[]
└─ displaySetting.chatCustomFontPath → filesDir/fonts

Room
├─ Conversation.assistantId          → Assistant.id
├─ Conversation.modeInjectionIds     → ModeInjection.id
├─ Conversation.folderId             → Folder.id
├─ Conversation.parentConversationId → Conversation.id
├─ Memory.assistantId                → Assistant.id 或 GLOBAL_MEMORY_ID
└─ Folder.assistantId                → Assistant.id
```

导入助手包时，至少处理上表所有从 `Assistant` 出发的边。现有加载期清理会丢掉指向不存在 MCP/注入/快捷消息的 id；**不会**自动下载缺失依赖。

---

## 5. 企业配置目标架构

企业“只读”不能等价于把编辑控件设为 disabled。Compose、ViewModel、备份恢复、市场导入、工具调用
和将来的后台同步都可能写配置；真正的强制边界必须位于持久化提交前。

### 5.1 本地快照、企业覆盖和有效读模型分离

企业数据不直接混写进现有 `Settings` JSON。后续阶段增加独立的 `EnterprisePolicyStore`，保存由企业
通道验证过的版本化快照、租户/设备绑定、签名摘要、过期策略和密钥引用；现有 DataStore 继续只保存
本地用户配置。两者通过纯函数生成运行时有效读模型：

```mermaid
flowchart LR
    L["Local Settings DataStore"] --> R["EffectiveSettingsResolver"]
    B["Built-in defaults"] --> R
    E["EnterprisePolicyStore"] --> R
    R --> S["Effective Settings"]
    R --> K["RecordLockIndex"]
    S --> UI["UI / runtime consumers"]
    K --> UI
    UI --> G["Settings mutation gateway"]
    G --> P["Write policy"]
    P --> L
    ES["Verified enterprise sync"] --> E
```

这样设计会得到以下结果：

- 企业记录按稳定 id 覆盖有效读模型，同类型本地记录继续共存；
- 同 id 的本地记录可作为 shadow 保留，解除企业关联后恢复，而不是被企业载荷永久破坏；
- 企业来源和锁定元数据不依赖客户端可伪造的 `builtIn` 或普通导入 JSON；
- 现有 `Settings`、Provider、Assistant、MCP 等序列化结构不需要为准备阶段增加 `source` 字段。

若产品最终选择“解除关联后删除 shadow”而不是恢复，必须在企业协议阶段明确，不能由 merge 顺序偶然决定。

### 5.2 合并与冲突规则

有效配置按记录 id 合并，不按显示名或 Kotlin 类型整表覆盖：

1. 内置默认项提供最低优先级；
2. 本地记录覆盖同 id 内置模板的可持久化字段；
3. 企业记录覆盖同 id 的有效公开载荷与企业机密引用；
4. 企业记录不存在的本地 id 正常显示和可编辑；
5. 企业默认选择可以覆盖运行时默认值，但是否允许用户在可用企业记录之间选择，应由策略显式声明；
6. 引用解析只接受本次有效图中的目标；未知或撤回的企业依赖不能落到“本机碰巧同 id”的市场记录。

企业快照应用必须是 generation/version 单调的整包事务。下载未完成、验签失败、租户不匹配或依赖图
不完整时继续使用上一份已验证快照，不能发布半包。

### 5.3 写入来源与授权

所有配置变更必须携带来源：`LOCAL`、`BACKUP_RESTORE`、`MARKETPLACE_IMPORT`、
`ENTERPRISE_DELIVERY`。来源不是持久化业务字段，而是写入上下文。

- 本地、备份和市场来源不得修改 `RecordLockIndex` 中的记录或锁定字段；
- 企业来源只能由验签后的企业同步服务调用，不能暴露给 UI 或通用 JSON 导入；
- 策略在 Mutex 内对最新本地/企业 generation 重验，不能只在页面打开时检查；
- UI 使用锁索引展示原因、来源和禁用状态，但 UI 判断不是授权依据；
- 运行时始终消费有效读模型，避免用户通过旧会话或后台任务继续使用已撤回企业密钥。

准备阶段只建立来源、策略和读取物化的插槽，不创建企业存储，也不伪造“已锁定”的 UI。当前普通
写入口固定为 `LOCAL`，备份只能调用专用恢复入口；`ENTERPRISE_DELIVERY` 没有暴露给 UI 或通用导入的
提交入口。

### 5.4 机密边界

企业 Provider/TTS/ASR/MCP 的密钥不进入市场包、普通 Settings 导出或用户可编辑表单。后续应让企业
公开定义只持有 secret reference，密钥材料进入 Android Keystore 支持的独立机密存储；日志、异常、
调试导出和 `toString` 都必须脱敏。是否允许企业备份由服务端策略决定，不能沿用当前 `settings.json`
整包备份。

---

## 6. 各类需求怎样落到记录上

### 6.1 企业下发（`enterprise-overlay`）

按身份合并到现有列表，不要清空用户自建项。

适合下发：`ProviderSetting`（含模型）、企业 `TTSProviderSetting` / `ASRProviderSetting`、企业 `McpServerConfig`（不要下发用户 OAuth 令牌）、企业 `SearchServiceOptions`、可选的默认 `chatModelId` 与模型级 Prompt。

客户端约束（待实现）：

- 有效记录带独立来源/锁索引；`enterprise` 在 UI 只读，并由写策略强制只读。
- 企业更新按身份覆盖企业载荷，不得覆盖 `local` 同类型记录。
- 用户不能改企业 `apiKey`/`baseUrl`，可以换「当前选用哪一条」。
- `ProviderSetting.builtIn` 不能当企业标记用。它只表示预设模板，而且不进磁盘。

### 6.2 助手市场（`marketplace-package`）

包内应是图，而不是裸 `Assistant`：

- 必选：`Assistant` 公开载荷（提示、参数、工具开关、正则、预置消息等）
- 按引用带上：`ModeInjection`、`QuickMessage`、`Tag`、Skill 目录
- 可选：用户 MCP 的**非机密**连接描述（url、name）；密钥让用户填
- 默认不带：Memory、Conversation、Workspace Rootfs、OAuth、企业 Provider

覆盖：目标已有同 `Assistant.id` 则替换定义，引用仍指向用户环境里已有的 MCP/工作区。  
复制：新 `Assistant.id`，包内注入/快捷消息/标签生成新 id，并改写助手上的引用。Skill 按名合并，重名则覆盖或改名后改 `enabledSkills`。

`allowedSubAssistantIds` 指向包外助手时，导入后应视为未解析，不能静默指向用户机器上碰巧同 id 的助手。

### 6.3 MCP 市场

用户 MCP 与企业 MCP 共用 `Settings.mcpServers`。市场项来源为 `marketplace` 或导入后变 `local`。`McpCommonOptions.tools` 以连上服务器后的 `mergeTools` 为准，不要把缓存 schema 当市场真源。OAuth 令牌留在设备。

---

## 7. 当前架构审查与准备阶段决策

| 现状/风险 | 影响 | 准备阶段决策 |
|-----------|------|----------------|
| `SettingsStore` 同时负责 key、解码、默认补齐、引用清理、写入、内存发布和 Pebble 缓存副作用 | 难测试；持久化层通过 Koin 反向查找模板引擎，依赖方向隐式成环 | 把读取物化、写策略和模板缓存观察者拆开；Store 只编排 DataStore 与发布 |
| 写成功后手工发布值与 DataStore 回读还会经过不同清理步骤 | 同一次提交可能短暂或最终暴露不同有效状态 | 读回与提交后发布共用纯 `materializeForRead` |
| 多个页面/VM 把采集到的整份旧 `Settings` 复制后回写 | 与工具、后台恢复、其他页面并发时可覆盖无关新值；企业锁无法稳定重验 | 本地编辑统一改为 Mutex 内基于最新值的 transform/记录命令；整份替换仅保留给显式备份恢复 |
| Composable/通用 UI 组件直接注入 `SettingsStore` 并写收藏等配置 | UI 与持久化实现耦合，锁定策略容易漏入口 | 读写接口分离；写操作经 VM/command gateway，组件只接收状态与 callback |
| 外层使用最新 `Settings`，但内层 WebDAV/S3/提醒/注入列表仍由旧页面快照整对象替换 | 快速输入或后台写入时仍会丢掉同记录的并发字段 | 内层编辑也改成对 Mutex 内最新记录执行字段 transform 或稳定 id 命令 |
| MCP 工具同步先在 Mutex 外复制完整连接记录 | OAuth/连接参数并发刷新时可能被旧记录覆盖 | 只把服务器工具列表带入 transform，在最新 MCP 记录上合并 `tools` |
| 用户头像在 Settings 写入前删除旧文件 | DataStore 写失败会留下仍指向已删除文件的旧配置 | 提交接口返回策略处理后的实际值；只依据已提交值执行旧头像/背景清理，写失败或策略拒绝不触碰旧文件 |
| DataStore 每次变更都编码并设置完整 Settings key 集 | 大列表与 JSON 在高频输入时产生额外 CPU/分配；DataStore 最终仍是整文件提交 | 本轮优先消除无意义/陈旧快照写与无关缓存失效；字段 diff 编码需单独兼容测试后再做 |
| Pebble 缓存在任意 Settings 变化时全量失效 | 主题、计数、备份配置变化也触发无关缓存清理 | 独立观察 Assistant `id + messageTemplate` 指纹后再失效 |
| `searchServiceSelected` 使用列表下标 | 排序、删除、企业叠加和跨设备同步会选错记录 | 企业/偏好同步前必须迁移为稳定 id；本轮冻结现有 key 和行为 |
| `WebDavSync` 与 `S3Sync` 重复实现 ZIP 组装/恢复 | 修复容易只落一条通道；当前 transport 与 archive 职责混合 | 后续抽取共同 archive service；传输层只上传/下载。准备阶段先记录格式与恢复风险 |
| 备份直接复制 WAL 数据库文件，并在 Room 仍打开时覆盖 DB/WAL/SHM | 并发写时快照与恢复边界不清，不能作为记录同步实现基础 | 配置同步禁止复用备份实现；数据库灾备另行引入一致快照/受控重启协议和设备测试 |
| ZIP 恢复同时写 Settings、DB 与文件，没有统一 staging/commit | 中途失败可形成跨介质部分恢复 | 后续采用校验清单、staging、数据库版本检查、提交顺序和恢复日志；本轮不改变现有格式 |
| 上传目录 ZIP entry 原先直接拼接目标路径 | 非可信备份可用相对路径逃逸目标目录 | WebDAV/S3 共用规范路径解析并拒绝逃逸；Skill 继续使用 `SkillPaths`，字体继续限定单文件名 |
| SharedPreferences 状态未进入原记录目录 | 容易被误纳入 Settings 同步或在备份语义中遗漏 | 明确归为设备 UI/诊断状态，默认不上云 |
| Room 与文件的业务各自实现补偿 | 新增市场/企业包时容易绕开现有 Manager 直接写磁盘 | 同步层只能调用领域 Store/Manager；禁止直接写 Room DAO + File |
| `StatsVM` 等少数 UI VM 直接依赖 DAO | UI 知道数据库查询细节 | 不影响企业配置，本轮不扩大重构；新代码必须走 Repository/query service |

“不在本轮处理”不代表问题已接受。尤其是数据库备份一致性、稳定搜索服务 id 和企业机密存储，必须
在相应数据迁移/协议阶段作为 release blocker，而不是通过当前架构调整顺带改变落盘格式。

---

## 8. 实现时容易踩的约束

- **身份不是显示名。** 模型同步用 `Model.id`；Skill 用目录名；搜索当前选中必须先改成 `SearchServiceOptions.id`。
- **列表下标不能同步。** `searchServiceSelected` 在增删排序后会指错引擎。
- **加载清理 ≠ 写入清理。** 市场导入要显式做引用校验或复用 `settingsFlow` 那套过滤，不能只信 `normalizeForPersistence`。
- **`@Transient` 不是「不落盘」。** `pendingAssistantDeletions` 有独立 key；`builtIn` 则完全不落盘。
- **会话行上的 `nodes` 不是消息源。** 备份/迁移必须带 `message_node` 表。
- **Workspace 绑定与 Rootfs 分离。** 助手只引用 `workspaceId`；真正的 Linux 树在 `filesDir/workspaces/<root>/`。
- **备份通道 ≠ 配置同步。** `WebDavConfig` / `S3Config` 是备份自己的密钥，不要和企业下发、市场包混在一个 API。
- **有效读模型 ≠ 本地持久化快照。** 企业覆盖只进入 resolver；普通写入不能把有效快照整份反写本地。
- **UI 只读 ≠ 数据只读。** 锁定必须由写策略在最新 generation 上重验。
- **来源不能由导入包自报。** `ENTERPRISE_DELIVERY` 只来自验签通道；普通 JSON 带 `enterprise` 字样仍是外部导入。
- **Room 事务 ≠ 跨文件事务。** 市场包和备份恢复必须使用 staging、补偿与恢复日志。
- **SharedPreferences 不是 Settings 扩展区。** 新的重要配置不能为了省事写入 UI hook 的偏好文件。

---

## 9. 准备阶段实施清单

### 9.1 本轮允许的代码调整

- 提取 Settings 读取物化纯函数，供 DataStore 回读和提交后发布复用；
- 建立 `SettingsWriteSource` / `SettingsWritePolicy`，默认策略保持当前行为；
- 为备份恢复提供显式入口并保护内部 tombstone；
- 把 Pebble cache invalidation 从持久化层移到独立观察者，只观察模板指纹，并让编译缓存键包含
  助手 id 与模板内容，使正确性不依赖异步失效时序；
- 把本地 UI 的整份 Settings 回写改成最新值上的 transform 或明确的记录级命令；
- 增加读取物化、授权策略、并发 delta、序列化兼容和写失败顺序测试；
- 同步修正引用文档中与真实 `normalizeForPersistence` 职责不一致的描述。

### 9.2 本轮禁止变化的落盘契约

- DataStore 名称和全部 Preferences key 字符串不变；
- `Settings`、Provider、Model、Assistant、MCP、TTS、ASR、Search、主题等序列化字段与 discriminator 不变；
- `pendingAssistantDeletions` 继续只写独立 key，不进入 `Settings` JSON；
- Room 数据库名、版本、Entity、列、索引、外键、migration 和 schema JSON 不变；
- SharedPreferences 文件名/key/默认行为不变；
- `filesDir` 目录、文件命名、URI/相对路径契约不变；
- WebDAV/S3 备份 entry 名称和兼容读取行为不变；
- 不新增 versionCode/versionName，不更新 changelog。

### 9.3 验证门槛

- JVM：Settings 读取物化、写策略来源、tombstone、Assistant 兼容、相关 UI delta 与现有全量单测；
- Room：确认 schema/migration 文件无 diff；已有迁移链测试保持可运行，本轮无 schema 变更；
- 静态：`git diff --check`、UTF-8 without BOM、文档链接与禁止落盘契约审查；
- 构建：Android Lint 与 Debug APK；
- 最终人工 diff：逐个写入口确认没有旁路策略、没有先发布后写盘、没有把企业能力表述成已实现。

设备级备份恢复、真实企业服务、跨设备同步和数据库一致快照不属于本轮可伪造的验证证据；若未运行，
最终结论必须明确标注。

---

## 10. 代码入口与职责目标

| 代码 | 职责 |
|------|------|
| `SettingsStore` / `Settings` | DataStore 编排、公开有效快照、序列化模型 |
| `commitSettings` | 固化并测试“策略与规范化 → 落盘 → 发布”的提交顺序；失败不发布 |
| `prepareSettingsForWrite` | 应用来源策略、持久化归一化和既有 DataStore 标量约束，形成唯一待提交值 |
| `materializeForRead` | 内置项物化、运行时属性恢复、去重和读取期引用清理 |
| `normalizeForPersistence` | 写入前最小规范化；不得偷偷承担导入图校验 |
| `SettingsWritePolicy` | 按最新状态与写入来源执行授权；未来企业锁的强制边界 |
| `AssistantTemplateCacheInvalidator` | 观察模板指纹并失效 Pebble cache，不反向污染持久化层 |
| `FavoriteModelService` | 收藏模型的记录级增删/排序命令；通用模型组件不直接写 Store |
| `applySearchMode` / `ProviderSettingsApplicationService.saveConfiguration` / `applyMcpEditorSave` | 聊天搜索、Provider 详情与 MCP 编辑表单在最新记录上合并，避免整份陈旧快照覆盖 |
| `SettingVM` / `ChatVM` / `BackupVM` 等 | 接收字段或记录 transform，不接收整份 UI Settings 快照 |
| `DefaultProviders` | `DEFAULT_PROVIDERS`、`DEFAULT_AUTO_MODEL_ID` |
| `Assistant.kt` | 助手、注入、快捷消息、正则 |
| `ProviderSetting` / `Model` | 提供商与模型 |
| `McpConfig.kt` | MCP 服务器、工具缓存、OAuth |
| `AppDatabase` 与各 Entity | Room 运行数据 |
| `FileFolders` / `SkillManager` / `WorkspaceManager` | 文件与 Skill / Rootfs |
| `FilesManager` / `GeneratedMediaStore` | Room + 文件双写、补偿与对账 |
| `WebDavSync` / `S3Sync` | 当前备份 transport + archive；后续拆出共同 archive service |
| `resolveBackupEntry` / `SkillPaths` | 将恢复目标限制在指定目录内，不允许 ZIP 路径逃逸 |

---

## 11. 准备阶段实施记录

本节只记录已经通过代码与测试验证的结果。设计完成但尚未落地的内容保留在 §9，不提前标记完成。

### 11.1 已完成的架构准备

- `SettingsStore` 不再实现 `KoinComponent` 或反向获取 Pebble 引擎；读取物化、写策略、提交顺序和
  模板缓存观察已拆成独立职责。模板编译缓存键同时包含助手 id 与模板内容，异步观察者只负责回收，
  不再承担避免旧模板命中的正确性职责；
- `SettingsWriteSource` 与 `SettingsWritePolicy` 已进入唯一提交链。默认策略是兼容性的 allow-all，
  尚未伪造企业锁；
- DataStore 写成功后发布的值与 DataStore 回读共用 `materializeForRead`；落盘异常或取消发生在
  `persist` 阶段时不会发布内存快照；搜索下标和 TTS 速度等既有落盘裁剪已前移到提交准备阶段，
  提交返回值不再与磁盘值分叉；
- 备份恢复使用显式 `restoreFromBackup` 来源，并合并保留不进入 `settings.json` 的删除 tombstone；
- 设置、备份、聊天、调试、提示词、图片生成等写入口已从整份旧快照改成最新值上的 transform；
  Provider、MCP、TTS、ASR、Search、Theme、Assistant 等列表操作以稳定 id 解析最新记录；
- DisplaySetting 页面使用字段 delta；WebDAV、S3、备份提醒与注入列表直接对最新内层记录执行
  transform，避免“外层原子、内层陈旧”；
- 聊天页切换搜索模式只改最新 Model 的 `tools`，不再用页面快照整份覆盖模型其它字段；
- Provider 详情页配置保存保留最新 `models` 与运行时内置元数据；模型增删改排序在最新
  Provider 上执行。MCP 编辑表单保存保留最新 OAuth 与工具 schema，只覆盖用户改过的
  `enable` / `needsApproval`；
- 模型收藏收口到 `FavoriteModelService`，`ModelList` 不再依赖 `SettingsStore`；`AssistantPicker`
  只回传助手 id；`ImgGenPage` 只调用 ViewModel 命令；
- MCP 工具缓存合并移入最新记录 transform；用户头像和助手背景副作用以策略处理后的实际提交值为准，
  不再以 transform 中的拟议值冒充提交结果；
- WebDAV/S3 恢复共用 `resolveBackupEntry` 拒绝上传目录路径逃逸，未改变 ZIP entry 或目录结构。

### 11.2 落盘兼容性结论

本轮没有修改 DataStore 名称、Preferences key 字符串、`Settings`/Provider/Assistant 等序列化字段、
Room Entity/数据库版本/migration/schema JSON、SharedPreferences、`filesDir` 契约或备份 entry。
新增的写入来源、策略、指纹与协调器全部是运行时结构。`settings.json` 仍不包含
`pendingAssistantDeletions`，恢复后继续保留设备上未完成的内部清理状态。

### 11.3 当前验证记录

定向 JVM 测试已覆盖读取物化、策略来源、标量落盘规范化、提交失败不发布、备份恢复保留
tombstone、策略拒绝后的文件补偿、Assistant/tombstone 兼容、DisplaySetting 并发 delta、
聊天搜索模式按最新 Model 改 `tools`、Provider 表单保留最新模型列表、MCP 编辑表单保留
OAuth/新同步工具、收藏模型稳定 id 排序、模板指纹/内容缓存键和备份路径约束，并已通过。
随后已在当前工作树串行完成：

- `gradlew.bat test --no-parallel --max-workers=1`；
- `gradlew.bat lint --no-parallel --max-workers=1`；
- `gradlew.bat assembleDebug --no-parallel --max-workers=1`。

最终审查确认 DataStore Preferences key 集合与基线一致，Room schema/migration、数据库版本、
`app/build.gradle.kts`、SharedPreferences 和序列化模型没有结构 diff；Debug APK 已生成。
本轮未运行设备仪器测试、真实 WebDAV/S3 恢复或企业服务验证，因此这些不属于本轮已证明范围。
