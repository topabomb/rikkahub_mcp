# 可持久化记录与云端同步基线

本文是云端同步的设计基线。以当前代码为事实，只回答两件事：应用里有哪些**可独立同步的记录**，以及三类云端需求应怎样落到这些记录上。

当前代码**没有**来源标记、企业只读或市场导入协议。文中「建议」一律是设计约束，不是已实现字段。

---

## 1. 产品需求与抽象

未来要支持三类能力：

1. **企业下发**：提供商、模型、企业付费 TTS/ASR、企业 MCP 等。企业记录在 App 内不可改；用户仍可自建同类型记录。
2. **助手市场**：下载助手定义。同 ID 覆盖，或生成新 ID（重命名/复制）。
3. **MCP 市场**：下载用户可编辑的 MCP 连接。企业 MCP 与用户 MCP 共存。

这三类不是「按类型一刀切」，而是同一类型上的**来源与可变性**不同。落地前用下面的记录模型，不要按「Provider 整表只能企业下发」来设计。

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

## 2. 存储层

| 存储 | 入口 | 存什么 |
|------|------|--------|
| DataStore Preferences `"settings"` | `PreferencesStore` / `Settings` | 全局配置。每个逻辑字段一个 key；复杂对象是独立 JSON 字符串 |
| Room `AppDatabase` | 各 DAO / Repository | 会话、记忆、工作区、文件元数据、收藏、文件夹 |
| 应用 `filesDir` | `FileFolders`、`SkillManager`、`WorkspaceManager`、`FilesManager` | Skill 目录、上传文件、字体、工具输出、Workspace Rootfs |

写入规则（当前实现）：

- `SettingsStore.updateAtomic`：互斥读最新非 dummy Settings → transform → `normalizeForPersistence` → 写入成功后才发布 `settingsFlow`。
- `normalizeForPersistence` **只**规范化 `Assistant.description`、关闭类别时清掉 `isSubAssistantGloballyVisible`、按 `assistantId` 去重 tombstone。它**不**清理失效引用。
- 失效引用、重复 id、内置 Provider/助手/TTS 补齐，发生在 **DataStore 加载后的 `settingsFlow` map**。导入/同步如果只调用 `normalizeForPersistence`，不会得到加载期那套清理。
- `SearchServiceOptions`、`TTSProviderSetting`、`ASRProviderSetting` 用 `decodeListLenient`：未知密封子类会被跳过。
- `ProviderSetting.builtIn` 与描述 lambda 是 `@Transient`。磁盘上没有 `builtIn`；加载时若 id 落在 `DEFAULT_PROVIDERS` 里，再把内置标记和描述补回去。
- `Settings.pendingAssistantDeletions` 在数据类上是 `@Transient`，因此不会进整份 Settings JSON / 备份快照；它另有 DataStore key，供跨进程删除恢复。

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
| `GenMediaEntity` / `ManagedFileEntity` | 自有主键 | 文件路径 | 备份 | 元数据；字节在 `filesDir` |

普通用户列表、搜索、最近对话工具只看 `parent_conversation_id IS NULL` 的会话。Child 行是子助手内部工作区。

### 3.3 文件系统

| 路径 | 内容 | 建议通道 |
|------|------|----------|
| `filesDir/skills/<name>/` | `SKILL.md` 与附属文件 | 市场可按名覆盖或改名；助手只引用名字 |
| `filesDir/upload/` | 用户上传与聊天附件 | 备份 |
| `filesDir/fonts/` | 自定义聊天字体 | 偏好同步需带文件，或只同步主题、不带路径 |
| `filesDir/tool_outputs/` | 工具超长输出落盘 | 不进配置同步 |
| `filesDir/workspaces/<root>/` | Workspace Rootfs 与用户文件 | 备份或企业镜像；体积大 |
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

## 5. 三类需求怎样落到记录上

### 5.1 企业下发（`enterprise-overlay`）

按身份合并到现有列表，不要清空用户自建项。

适合下发：`ProviderSetting`（含模型）、企业 `TTSProviderSetting` / `ASRProviderSetting`、企业 `McpServerConfig`（不要下发用户 OAuth 令牌）、企业 `SearchServiceOptions`、可选的默认 `chatModelId` 与模型级 Prompt。

客户端约束（待实现）：

- 记录带来源；`enterprise` 在 UI 只读。
- 企业更新按身份覆盖企业载荷，不得覆盖 `local` 同类型记录。
- 用户不能改企业 `apiKey`/`baseUrl`，可以换「当前选用哪一条」。
- `ProviderSetting.builtIn` 不能当企业标记用。它只表示预设模板，而且不进磁盘。

### 5.2 助手市场（`marketplace-package`）

包内应是图，而不是裸 `Assistant`：

- 必选：`Assistant` 公开载荷（提示、参数、工具开关、正则、预置消息等）
- 按引用带上：`ModeInjection`、`QuickMessage`、`Tag`、Skill 目录
- 可选：用户 MCP 的**非机密**连接描述（url、name）；密钥让用户填
- 默认不带：Memory、Conversation、Workspace Rootfs、OAuth、企业 Provider

覆盖：目标已有同 `Assistant.id` 则替换定义，引用仍指向用户环境里已有的 MCP/工作区。  
复制：新 `Assistant.id`，包内注入/快捷消息/标签生成新 id，并改写助手上的引用。Skill 按名合并，重名则覆盖或改名后改 `enabledSkills`。

`allowedSubAssistantIds` 指向包外助手时，导入后应视为未解析，不能静默指向用户机器上碰巧同 id 的助手。

### 5.3 MCP 市场

用户 MCP 与企业 MCP 共用 `Settings.mcpServers`。市场项来源为 `marketplace` 或导入后变 `local`。`McpCommonOptions.tools` 以连上服务器后的 `mergeTools` 为准，不要把缓存 schema 当市场真源。OAuth 令牌留在设备。

---

## 6. 实现时容易踩的约束

- **身份不是显示名。** 模型同步用 `Model.id`；Skill 用目录名；搜索当前选中必须先改成 `SearchServiceOptions.id`。
- **列表下标不能同步。** `searchServiceSelected` 在增删排序后会指错引擎。
- **加载清理 ≠ 写入清理。** 市场导入要显式做引用校验或复用 `settingsFlow` 那套过滤，不能只信 `normalizeForPersistence`。
- **`@Transient` 不是「不落盘」。** `pendingAssistantDeletions` 有独立 key；`builtIn` 则完全不落盘。
- **会话行上的 `nodes` 不是消息源。** 备份/迁移必须带 `message_node` 表。
- **Workspace 绑定与 Rootfs 分离。** 助手只引用 `workspaceId`；真正的 Linux 树在 `filesDir/workspaces/<root>/`。
- **备份通道 ≠ 配置同步。** `WebDavConfig` / `S3Config` 是备份自己的密钥，不要和企业下发、市场包混在一个 API。

---

## 7. 代码入口

| 代码 | 职责 |
|------|------|
| `PreferencesStore` / `Settings` | DataStore 读写、加载期清理、`normalizeForPersistence` |
| `DefaultProviders` | `DEFAULT_PROVIDERS`、`DEFAULT_AUTO_MODEL_ID` |
| `Assistant.kt` | 助手、注入、快捷消息、正则 |
| `ProviderSetting` / `Model` | 提供商与模型 |
| `McpConfig.kt` | MCP 服务器、工具缓存、OAuth |
| `AppDatabase` 与各 Entity | Room 运行数据 |
| `FileFolders` / `SkillManager` / `WorkspaceManager` | 文件与 Skill / Rootfs |
