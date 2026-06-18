# Fork 精简版规划

> 基于 `architecture.md` 的架构现状，规划精简版本的具体操作
> 最后更新：2026-06-18
> 原则：只做调研和文档，不修改代码

---

## 一、标识变更

### 1.1 需要修改的配置项

| 配置项 | 当前值 | 修改位置 |
|--------|--------|----------|
| `applicationId` | `me.rerere.rikkahub` | `app/build.gradle.kts` |
| `namespace` | `me.rerere.rikkahub` | `app/build.gradle.kts` |
| 包名目录 | `me/rerere/rikkahub/` | `app/src/main/java/` 全部子目录 |
| `app_name` | RikkaHub | `app/src/main/res/values*/strings.xml` 各语言版本 |
| 启动图标 | `ic_launcher` | `app/src/main/res/mipmap-*/ic_launcher*` 及 `ic_launcher_round*` |
| User-Agent | `RikkaHub-Android/${VERSION}` | `di/DataSourceModule.kt` OkHttpClient 拦截器 |

各子模块包名不改（`me.rerere.ai`/`me.rerere.search` 等）。

### 1.2 持久化标识调整

| 标识 | 当前值 | 修改位置 | 说明 |
|------|--------|----------|------|
| 数据库名 | `rikka_hub` | `di/DataSourceModule.kt` 第 53 行 `databaseBuilder` 第三个参数 | 改为新应用标识 |
| DataStore 名称 | `settings` | `data/datastore/PreferencesStore.kt` 第 59 行 `preferencesDataStore(name = ...)` | 可保留 `"settings"` 或改为新名称 |
| SharedPreferences | `rikka_hub_prefs`（若有） | 搜索 `getSharedPreferences` | 需排查 |
| 文件目录 | `workspaces/`、`uploads/`、`tool_outputs/`、`skills/` | `data/files/FileFolders` | 路径基于 `filesDir`，与包名无关，无需改 |

### 1.3 硬编码引用清理

| 引用 | 位置 | 处理 |
|------|------|------|
| `SponsorAPI` baseUrl | `data/api/SponsorAPI.kt` | 随赞助功能删除 |
| `RikkaHubAPI` 空接口 | `data/api/RikkaHubAPI.kt` | 随 Retrofit 删除 |
| `UpdateChecker` API_URL | `utils/UpdateChecker.kt` 第 20 行 | 替换为自建更新接口 |
| `UpdateChecker` User-Agent | `utils/UpdateChecker.kt` 第 36 行 | `RikkaHub` 替换为新应用名 |
| `RemoteConfig` 默认值 | `app/src/main/res/xml/remote_config_defaults.xml` | 随 Firebase 删除 |

---

## 二、Migration 清理与样本保留

### 2.1 Room DB Migration

当前 `AppDatabase.kt` 版本 23，含 17 个 AutoMigration + 5 个手动 Migration。

**删除全部历史 migration**（9 个文件 + 1 个测试）：

| 删除项 | 路径 |
|--------|------|
| 整个 migrations 目录 | `app/src/main/java/.../data/db/migrations/` |
| Migration 测试 | `app/src/androidTest/java/.../data/db/migrations/Migration_11_12_Test.kt` |
| AutoMigrations 注解 | `AppDatabase.kt` 的 `@Database(autoMigrations = [...])` 全部 17 条 |
| 手动 Migration 注册 | `di/DataSourceModule.kt` 的 `.addMigrations(...)` 全部 5 个 |

### 2.2 DataStore Migration

**删除整个 migration 目录**（`app/src/main/java/.../data/datastore/migration/`）。

`PreferencesStore.kt` 第 58-67 行的 `produceMigrations` 中引用的 `PreferenceStoreV1Migration`/`V2`/`V3` 全部删除，改为空列表或直接移除 `produceMigrations` 参数。

### 2.3 保留一次 Migration 作为样本

**目标**：新 app 数据库默认有 v1→v2 一次迁移，充分展示迁移经验，作为后续开发的参考范式。

**设计**：

- **v1（初始版本）**：精简后的最终 schema（删除 lorebook_ids 列后的 7 个 Entity）
- **v2（样本迁移）**：新增一个字段（如 `ConversationEntity.tags` 或其他合理的 schema 变更），演示一次完整的 Migration 编写

**实现方式**：

```kotlin
// AppDatabase.kt
@Database(
    entities = [...],
    version = 2,  // ← 从 2 开始，v1→v2 是样本迁移
)

// DataSourceModule.kt
Room.databaseBuilder(context, AppDatabase::class.java, "新数据库名")
    .addMigrations(Migration_1_2)  // ← 仅保留一个样本迁移
    .build()
```

```kotlin
// migrations/Migration_1_2.kt
val Migration_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 示例：新增列 + 默认值
        database.execSQL("ALTER TABLE conversations ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
    }
}
```

**配套测试**：保留 `Migration_1_2_Test.kt`（基于原 `Migration_11_12_Test.kt` 的模式），验证 schema 正确性和数据完整性。

### 2.4 保留的架构设计经验

以下经验体现在 v1 schema 设计中，不需要 migration 代码：

1. **MessageNode 独立表**：`message_node` 表，外键关联 ConversationEntity（CASCADE 删除），不是 JSON blob
2. **序列化短名**：UIMessagePart 使用 `@SerialName` 短名（`text`/`image`/`tool` 等）
3. **工具消息合并**：Tool part 合并到 ASSISTANT 消息的 parts 中
4. **FTS5 + jieba**：`MessageFtsManager` 使用 FTS5 虚拟表 + jieba 中文分词

---

## 三、功能移除

### 3.1 酒馆角色卡 / 第三方导入

**删除文件**：

| 文件 | 说明 |
|------|------|
| `ui/pages/assistant/detail/AssistantImporter.kt` | 酒馆角色卡导入 |
| `data/sync/importer/ChatboxImporter.kt` | Chatbox 导入 |
| `data/sync/importer/CherryStudioProviderImporter.kt` | Cherry Studio 导入 |

**修改文件**：

| 文件 | 修改 |
|------|------|
| `utils/ImageUtils.kt` | 移除 `getTavernCharacterMeta()` |
| `utils/AIIconMatcher.kt` | 移除 tavern 图标匹配 |
| `data/export/ExportSerializer.kt` | 移除 `tryImportSillyTavern()` 及 `SillyTavernLorebook`/`SillyTavernEntry` |
| `ui/pages/backup/BackupVM.kt` | 移除 `restoreFromChatBox()`/`restoreFromCherryStudio()`/`ChatboxRestoreResult` |
| `ui/pages/backup/tabs/ImportExportTab.kt` | 移除"从其他应用导入"section |

### 3.2 Lorebook / 世界书

保留 ModeInjection，移除 RegexInjection（Lorebook）。

**删除文件**：

| 文件 | 说明 |
|------|------|
| `app/src/test/.../PromptInjectionTransformerTest.kt` | 移除后为 ModeInjection 补新测试 |

**修改文件**：

| 文件 | 修改 |
|------|------|
| `data/model/Assistant.kt` | 删除 `lorebookIds` 字段、`RegexInjection` 类、`Lorebook` 类、`isTriggered()`/`extractContextForMatching()`/`getTriggeredInjections()` |
| `data/model/Conversation.kt` | 删除 `lorebookIds` 字段 |
| `data/db/entity/ConversationEntity.kt` | 删除 `lorebook_ids` 列 |
| `data/repository/ConversationRepository.kt` | 删除 lorebookIds 的序列化/反序列化 |
| `data/datastore/PreferencesStore.kt` | 删除 `LOREBOOKS` key、读写逻辑、`Settings.lorebooks` 字段、`import Lorebook` |
| `data/ai/GenerationHandler.kt` | 删除 `conversationLorebookIds` 参数及透传 |
| `data/ai/transformers/Transformer.kt` | 删除 `TransformerContext.conversationLorebookIds` 及透传 |
| `data/ai/transformers/PromptInjectionTransformer.kt` | 删除 Lorebook 相关 import/参数/匹配逻辑，保留 ModeInjection |
| `data/export/ExportSerializer.kt` | 删除 `LorebookSerializer` |
| `service/ChatService.kt` | 删除 `conversationLorebookIds` 透传 |

**界面影响**（见第五章）：
- `AssistantExtensionsPage.kt`：Tab 从 4 个减为 3 个（删除 Lorebook Tab）
- `PromptPage.kt`：Tab 从 2 个减为 1 个（删除 Lorebook Tab，可简化为无 Tab 结构）

### 3.3 翻译功能

**删除文件**：

| 文件 | 说明 |
|------|------|
| `ui/pages/translator/` 整个目录 | TranslatorPage.kt, TranslatorVM.kt |
| `data/ai/prompts/Translation.kt` | 翻译 prompt 模板 |

**修改文件**：

| 文件 | 修改 |
|------|------|
| `data/ai/GenerationHandler.kt` | 删除 `translateText()` 方法 |
| `data/datastore/PreferencesStore.kt` | 删除 3 个 key + 3 个字段（translateModeId/translatePrompt/translateThinkingBudget） |
| `ui/pages/setting/SettingModelPage.kt` | 删除翻译模型选择项（Tab 0 从 7 项减为 6 项） |
| `ui/pages/setting/SettingModelPromptPage.kt` | 删除翻译 Prompt 设置项（Tab 1 从 5 项减为 4 项） |
| `RouteActivity.kt` | 删除 Translator 路由 + Screen 定义 + import |
| `ai/.../registry/ModelRegistry.kt` | 删除 `QWEN_MT` 匹配规则 |
| `ui/pages/chat/ChatPage.kt` | 删除 `onTranslate`/`onClearTranslation` 回调 |
| `ui/pages/chat/ChatDrawer.kt` | 删除"AI 翻译"菜单项 |

**界面影响**（见第五章）：
- 模型设置页 Tab 0 减少 1 项（翻译模型）
- 模型设置页 Tab 1 减少 1 项（翻译 Prompt）
- 聊天抽屉菜单减少 1 项（AI 翻译）
- 消息长按菜单减少"翻译"选项

### 3.4 赞助 / 捐赠

**删除文件**：

| 文件 | 说明 |
|------|------|
| `ui/pages/setting/SettingDonatePage.kt` | 赞助页 |
| `data/api/SponsorAPI.kt` | Retrofit 接口 |
| `data/model/Sponsor.kt` | Sponsor 数据类 |

**修改文件**：

| 文件 | 修改 |
|------|------|
| `data/datastore/PreferencesStore.kt` | 删除 `SPONSOR_ALERT_DISMISSED_AT` key + `Settings.sponsorAlertDismissedAt` 字段 |
| `ui/pages/setting/SettingPage.kt` | 删除赞助提醒弹窗（第 93-114 行）+ 捐赠入口（第 352-355 行） |
| `ui/pages/debug/DebugPage.kt` | 删除 sponsorAlertDismissedAt 调试项（第 272-293 行） |
| `RouteActivity.kt` | 删除 SettingDonate 路由 + Screen 定义 + import |
| `di/DataSourceModule.kt` | 删除 `SponsorAPI.create(get())` |

**界面影响**（见第五章）：
- 设置页"关于"分组减少 1 项（捐赠）
- 设置页赞助提醒弹窗删除
- DebugPage 减少 1 个调试项

### 3.5 Firebase

**删除文件**：`data/ai/AIRequestInterceptor.kt`（RemoteConfig 逻辑已被注释，无实际作用）

**修改文件**：

| 文件 | 修改 |
|------|------|
| `RikkaHubApp.kt` | 删除 FirebaseRemoteConfig import + 初始化代码 |
| `di/AppModule.kt` | 删除 Firebase.crashlytics/remoteConfig/analytics 注册 + import |
| `di/DataSourceModule.kt` | 删除 `AIRequestInterceptor` 引用 |
| `di/ViewModelModule.kt` | 删除 `analytics = get()` |
| `ui/pages/chat/ChatVM.kt` | 删除 `analytics` 依赖 + 所有 `analytics.logEvent()` 调用 + import |
| `app/build.gradle.kts` | 删除 Firebase 插件 + 依赖 |
| `gradle/libs.versions.toml` | 删除 firebase 相关条目 |

### 3.6 保留的功能

| 功能 | 说明 |
|------|------|
| AI 生图 | 演示功能 |
| ModeInjection | 与 Lorebook 分离后保留 |
| 消息分支 | MessageNode 树形结构 |
| 工作空间 | proot 沙箱 |
| MCP | 核心功能 |
| 记忆系统 | Assistant 记忆 |
| 工具审批 HITL | 核心安全机制 |
| 备份（WebDAV/S3） | 数据安全 |
| FTS 全文搜索 | 消息搜索 |
| 快捷消息 | 完整保留 |
| 版本更新检查 | 替换 `UpdateChecker` 的 API_URL 和 User-Agent |
| 使用统计页 | 完整保留 |
| Skills 系统 | 完整保留 |

---

## 四、Provider 体系精简

### 4.1 预设 Provider

`DefaultProviders.kt` 当前 18 个预设。保留 4 个：

| 名称 | 类型 | 理由 |
|------|------|------|
| OpenAI | OpenAI | 协议标准实现 |
| Gemini | Google | 协议标准实现 |
| Claude | Claude | 协议标准实现（需新增，指向 `https://api.anthropic.com/v1`） |
| DeepSeek | OpenAI | 主流国产模型 |

移除 15 个：RikkaHub, AiHubMix, 硅基流动, OpenRouter, Vercel AI Gateway, 小马算力, 阿里云百炼, 火山引擎, 月之暗面, 智谱AI, 阶跃星辰, 302.AI, 腾讯Hunyuan, xAI, AckAI

### 4.2 Provider 实现精简（可选）

| 组件 | 说明 | 移除影响 |
|------|------|----------|
| `ResponseAPI.kt` | ~34 KB，仅 xAI 使用 | 删除文件 + 删除测试 + 移除 `useResponseApi` 字段 + 简化 `OpenAIProvider` |
| `vertex/` 目录 | ~6 KB，零预设使用 | 删除目录 + 移除 Google 的 6 个 Vertex 字段 + 简化 `GoogleProvider` |
| ChatCompletionsAPI 硬编码适配 | ~80 行 | 保留 DeepSeek 适配，移除其余 host 适配 |

### 4.3 搜索引擎精简

保留 5 个：Bing(BingLocal)、Tavily、SearXNG、Custom JS、RikkaHub
移除 12 个

### 4.4 语音模块精简

TTS 保留 3 个（System、OpenAI、Gemini），移除 5 个
ASR 保留 2 个（OpenAI Realtime、DashScope），移除 1 个

### 4.5 Retrofit 移除

`RikkaHubAPI` 空接口 + `SponsorAPI` 随赞助删除。移除 `retrofit` 和 `retrofit-serialization-json` 依赖，删除 `di/DataSourceModule.kt` 的 Retrofit 单例注册。

### 4.6 Web 模块移除

Web 模块提供"通过浏览器访问手机 AI"功能，含 Ktor Server + React 前端 + mDNS。建议移除。

**删除**：
- `web/` 模块 + `web-ui/` 项目
- `settings.gradle.kts` 删除 `include(":web")`
- `app/build.gradle.kts` 删除 `implementation(project(":web"))`
- app 中 13 个 Web 文件（`web/WebServerManager.kt`、`web/WebApiModule.kt`、`web/Exceptions.kt`、`web/NsdServiceRegistrar.kt`、`web/dto/WebDto.kt`、`web/routes/` 6 个文件、`service/WebServerService.kt`、`ui/pages/setting/SettingWebPage.kt`）

**修改**：

| 文件 | 修改 |
|------|------|
| `di/AppModule.kt` | 删除 WebServerManager 注册 + import |
| `RikkaHubApp.kt` | 删除 `startWebServerIfEnabled()` 方法/调用 + `WEB_SERVER_NOTIFICATION_CHANNEL_ID` 常量 + import WebServerService |
| `AndroidManifest.xml` | 删除 WebServerService 声明 + 检查 FOREGROUND_SERVICE 权限是否其他功能需要 |
| `RouteActivity.kt` | 删除 SettingWeb 路由 + Screen 定义 + import |
| `ui/pages/setting/SettingPage.kt` | 删除 Web 服务器入口 |
| `data/datastore/PreferencesStore.kt` | 删除 5 个 Web Server key + 5 个字段 |

**Ktor 依赖处理**：Ktor Server 依赖在 `web/build.gradle.kts`（随模块删除）；Ktor Client 依赖在 `app/build.gradle.kts`（MCP + WebDAV/S3 用，保留）。

---

## 五、界面布局变化

### 5.1 设置主页（SettingPage）

| 分组 | 变化 |
|------|------|
| 通用设置 | 不变（4 项） |
| 模型与服务 | 6→5 项（删除 Web 服务器入口） |
| 数据设置 | 不变（2 项） |
| 关于 | 5→4 项（删除捐赠入口） |
| 赞助提醒弹窗 | 删除 |

### 5.2 模型设置页（SettingModelPage）

| Tab | 变化 |
|-----|------|
| Tab 0 - 模型 | 7→6 项（删除翻译模型） |
| Tab 1 - Prompt | 5→4 项（删除翻译 Prompt） |

### 5.3 助手扩展配置页（AssistantExtensionsPage）

Tab 从 4 个减为 3 个：

| 原索引 | 原 Tab | 变化 |
|--------|--------|------|
| 0 | 快捷消息 | 保留 |
| 1 | 模式注入 | 保留 |
| 2 | Lorebook | 删除 |
| 3 | 技能 | 保留（索引变为 2） |

### 5.4 扩展功能入口页（ExtensionsPage）→ PromptPage

ExtensionsPage 入口不变（4 项保留）。PromptPage 内部变化：

| 原 Tab | 变化 |
|--------|------|
| Tab 0 - 模式注入 | 保留 |
| Tab 1 - Lorebook | 删除 |

只剩 1 个 Tab，可移除 Pager + TabBar 结构，直接渲染 ModeInjectionTab。

### 5.5 聊天抽屉（ChatDrawer）

| 菜单项 | 变化 |
|--------|------|
| AI 翻译 | 删除 |
| AI 生图 | 保留 |

菜单从 2 项减为 1 项，可考虑改为直接按钮而非弹出菜单。

消息长按菜单中"翻译"选项一并删除。

### 5.6 调试页（DebugPage）

删除 sponsorAlertDismissedAt 调试项，保留 launchCount 调试项。

### 5.7 路由变化（RouteActivity）

**删除的 Screen 定义**：`SettingDonate`、`SettingWeb`、`Translator`

**删除的 entry 注册**：对应 3 个 entry

**删除的 import**：`SettingDonatePage`、`SettingWebPage`、`TranslatorPage`

---

## 六、精简后数据模型

### 6.1 Settings 字段变更

**删除 10 个字段**：

| 字段 | 来源 |
|------|------|
| translateModeId / translatePrompt / translateThinkingBudget | 翻译 |
| lorebooks | Lorebook |
| sponsorAlertDismissedAt | 赞助 |
| webServerEnabled / webServerPort / webServerJwtEnabled / webServerAccessPassword / webServerLocalhostOnly | Web 服务器 |

### 6.2 Assistant 字段变更

**删除 1 个字段**：`lorebookIds`

保留：`quickMessageIds`、`enabledSkills`、`modeInjectionIds`、`mcpServers`、`localTools`、`workspaceId` 等全部。

### 6.3 Conversation 字段变更

**删除 1 个字段**：`lorebookIds`

保留：`modeInjectionIds`、`customSystemPrompt`、`workspaceCwd` 等。

### 6.4 ConversationEntity 列变更

**删除 1 列**：`lorebook_ids`

### 6.5 PreferencesStore key 变更

**删除 10 个 key**：`TRANSLATE_MODEL`、`TRANSLATION_PROMPT`、`TRANSLATE_THINKING_BUDGET`、`LOREBOOKS`、`SPONSOR_ALERT_DISMISSED_AT`、`WEB_SERVER_ENABLED`、`WEB_SERVER_PORT`、`WEB_SERVER_JWT_ENABLED`、`WEB_SERVER_ACCESS_PASSWORD`、`WEB_SERVER_LOCALHOST_ONLY`

---

## 七、精简版稳定化

### 7.1 编译验证检查清单

- [ ] `./gradlew assembleDebug` 编译通过
- [ ] `./gradlew test` 单元测试通过
- [ ] `./gradlew lint` Lint 通过
- [ ] 应用可安装、可启动
- [ ] 基础对话：发送消息 → 收到流式响应
- [ ] 工具调用：LocalTools 能执行
- [ ] MCP 连接：能连接 MCP 服务器、调用工具
- [ ] limitContext 上下文截断正常
- [ ] 消息分支：重新生成、切换分支正常
- [ ] Provider 配置：添加/编辑/删除 Provider 正常
- [ ] 对话保存/加载正常
- [ ] AI 生图正常
- [ ] Migration_1_2 能正确执行

### 7.2 建议执行顺序

```
1. 标识变更（包名/应用名/图标/数据库名/User-Agent）→ 确认编译通过
2. Migration 清理（删除全部历史 migration，保留 Migration_1_2 样本）→ 确认数据库正常创建
3. Firebase 移除 → 确认编译通过
4. Retrofit 移除 → 确认编译通过
5. Web 模块移除（注意 Ktor Client 保留）→ 确认编译通过
6. 功能移除（酒馆/Lorebook/翻译/赞助）→ 每个移除后确认编译通过
7. Provider 精简（预设 4 个/搜索 5 个/TTS 3 个/ASR 2 个）
8. 可选移除（ResponseAPI/Vertex AI）
9. 稳定化验证（对照 7.1 检查清单）
```

---

## 八、测试覆盖策略

### 8.1 当前测试清单

**ai/src/test/（13 个文件）**：MessageTest（26KB）、GoogleProviderMessageTest（17KB）、ChatCompletionsAPIMessageTest（16KB）、ResponseAPIMessageTest（14KB，若移除 ResponseAPI 则删）、ProviderMessageUtilsTest（13KB）、ClaudeProviderMessageTest（14KB）、ClaudeProviderPromptCacheTest（10KB）、MessageMetadataTest（6KB）、JsonTest（4KB）、ModelRegistryTest（4KB）、ToolApprovalStateTest（1KB）、FileEncoderExifTransformTest（3KB）

**app/src/test/（11 个文件）**：PromptInjectionTransformerTest（44KB，移除 Lorebook 后需重写）、ShareSheetTest（6KB）、TextReplacersTest（5KB）、TimeReminderTransformerTest（4KB）、ProviderConfigureConvertToTest（4KB）、DiffUtilsTest（3KB）、VersionTest（3KB）、SkillPathsTest（2KB）、DefaultProvidersTest（1KB，更新为 4 个预设）、ChatServiceTest（1KB，需重写）

**app/src/androidTest/（2 个文件）**：Migration_11_12_Test（删除，替换为 Migration_1_2_Test）、ExampleInstrumentedTest

### 8.2 覆盖差距

| 核心功能 | 当前覆盖 | 差距 |
|----------|----------|------|
| UIMessage 模型 / limitContext / handleMessageChunk | ✅ | 良好 |
| Provider 消息转换（三大 Provider） | ✅ | 良好 |
| ChatService 生成流程 | ❌ ChatServiceTest 仅 1KB | 严重不足 |
| GenerationHandler ReAct 循环 | ❌ 无 | 严重不足 |
| 工具执行循环 | ❌ 无 | 严重不足 |
| Transformer 管道 | ⚠️ TimeReminder 有 | 不足 |
| MCP 工具调用 | ❌ 无 | 不足 |
| 工具审批 HITL | ⚠️ ToolApprovalStateTest 1KB | 不足 |
| compressConversation | ❌ 无 | 不足 |
| Assistant/Conversation 模型 | ❌ 无 | 不足 |
| Settings 持久化 | ❌ 无 | 不足 |
| 消息分支 | ❌ 无 | 不足 |

### 8.3 补充测试优先级

**P0 — 核心生成流程**：

| 测试文件 | 测试内容 |
|----------|----------|
| `GenerationHandlerTest.kt`（新建） | ReAct 循环：tool_call → 执行 → 继续；无 tool_call 结束；maxSteps；工具输出截断 |
| `ToolExecutionTest.kt`（新建） | needsApproval=true 时 break；Approved/Denied/Answered 处理 |
| `ChatServiceTest.kt`（重写） | sendMessage → handleMessageComplete 流程；工具构建；Transformer 管道 |

策略：Mock Provider 返回预设 MessageChunk，不依赖真实 API。

**P1 — 核心数据模型**：Transformer 管道测试、compressConversation 测试、Assistant/Conversation 序列化测试、Settings 持久化测试

**P2 — 功能模块**：McpManager（Mock transport）、LocalTools（每个 execute）、limitContext 边界

### 8.4 精简后需要更新的测试

| 测试文件 | 更新 |
|----------|------|
| `PromptInjectionTransformerTest.kt` | 移除 Lorebook 用例，为 ModeInjection 补测试 |
| `DefaultProvidersTest.kt` | 更新为 4 个预设 |
| `Migration_11_12_Test.kt` | 替换为 `Migration_1_2_Test.kt` |
| `ResponseAPIMessageTest.kt` | 删除（若移除 ResponseAPI） |
| `ProviderConfigureConvertToTest.kt` | 检查是否涉及移除的字段 |
| `ModelRegistryTest.kt` | 删除 QWEN_MT 相关（若移除翻译） |
