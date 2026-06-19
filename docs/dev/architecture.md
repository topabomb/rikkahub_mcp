# RikkaHub 架构文档

> 面向 Fork 维护者的完整架构指南
> 最后更新：2026-06-19

---

## 源头追踪（Provenance）

本文档描述的是 Fork 前的原项目（RikkaHub）架构，供 Mersix Pilot 维护者追踪源头、对比差异。

| 项目 | 值 |
|------|------|
| 原项目名称 | RikkaHub |
| 原项目版本 | 2.3.1（versionCode 164） |
| 上游源头提交 | `5b9be3017fed06e77f79236a1d88be9b36a52454` — "Add toggle for request logging"（2026-06-18 19:27） |
| Fork 起点提交 | `22aa916bed06de81580cf34967f0a3c76fc4bd1d` — "Fork 调研和初始化"（2026-06-19 07:54） |
| Fork 后包名 | `net.weero.mersix.pilot` |
| Fork 后应用名 | Mersix Pilot（小麦助手） |
| Fork 规划文档 | `docs/dev/fork-simplification-plan.md` |

> 追溯上游代码：`git show 5b9be301:<path>` 或 `git diff 5b9be301..HEAD -- <path>`
> 查看上游完整历史：`git log --oneline 5b9be301`

---

## 一、项目概览

RikkaHub 是一个原生 Android LLM 聊天客户端，支持多 Provider 对话、工具调用、MCP 协议、语音合成、文档解析、工作空间沙箱等功能。

| 指标 | 数值 |
|------|------|
| Kotlin 文件 | ~510 个 |
| 总代码行 | ~100,000 行 |
| Gradle 模块 | 10 个 |
| Room DB 版本 | 23 |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 37 |

### 技术栈

| 类别 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 + Navigation3 |
| DI | Koin |
| 网络 | OkHttp（主） + Ktor（MCP/SSE） + Retrofit（部分） |
| 序列化 | kotlinx.serialization |
| 数据库 | Room |
| 异步 | Coroutines + Flow |
| 图片 | Coil |
| MCP | modelcontextprotocol-kotlin-sdk |
| Markdown | JetBrains Markdown |
| JS 引擎 | QuickJS |

---

## 二、模块结构

```
rikkahub/
├── app/          主应用（376 文件，79K 行）— UI + 业务逻辑 + 数据层
├── ai/           AI SDK 抽象层（42 文件，10K 行）— Provider + 消息模型 + 工具定义
├── search/       搜索引擎 SDK（20 文件，3K 行）— 17 个搜索引擎
├── speech/       语音 SDK（29 文件，3.1K 行）— 8 TTS + 3 ASR Provider
├── workspace/    工作空间（10 文件，1.7K 行）— proot Linux 沙箱
├── document/     文档处理（6 文件，1.1K 行）— PDF/DOCX/PPTX/EPUB
├── highlight/    代码高亮（4 文件，400 行）
├── material3/    Material3 扩展（3 文件，155 行）
├── common/       通用工具（17 文件，1.3K 行）
└── web/          Web 服务器（3 文件，84 行）— Ktor 入口
```

### 模块依赖方向

```
                    app
                   /  |  \  \  \  \  \
                  ai  search speech workspace document web
                   |    |      |        |        |
                  common (所有模块间接依赖 common)
```

- `app` 依赖所有其他模块
- `ai` 是核心抽象层，被 `app` 和 `search` 直接依赖
- `search` 依赖 `ai` 和 `common`（用于搜索引擎的 AI 工具集成）
- `speech`/`workspace`/`document` 是独立功能模块
- 模块间存在横向依赖：`search` → `ai`

### 各模块职责

| 模块 | 职责 | 关键类 |
|------|------|--------|
| `ai` | Provider 抽象、UIMessage 模型、Tool 定义、Token 使用统计 | `Provider`, `ProviderManager`, `UIMessage`, `Tool` |
| `search` | 17 个搜索引擎的统一接口 | `SearchService`, `SearchServiceOptions` |
| `speech` | TTS/ASR Provider 抽象 | `TTSProvider`, `ASRProvider` |
| `workspace` | proot 沙箱 shell 执行 | `WorkspaceManager`, `ProotShellRunner` |
| `document` | 文档解析为文本 | `PdfParser`, `DocxParser`, `EpubParser`, `PptxParser` |
| `web` | Ktor 服务器，暴露 REST API | `WebServerManager` |

---

## 三、ai 模块 — AI SDK 抽象层

`ai` 模块是整个项目的精华，提供与 AI Provider 交互的统一抽象。

### 3.1 Provider 体系

#### Provider 接口

```kotlin
// ai/src/main/java/me/rerere/ai/provider/Provider.kt
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>
    suspend fun getBalance(providerSetting: T): String  // 默认返回 "TODO"
    suspend fun generateText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk
    suspend fun streamText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk>
    suspend fun generateEmbedding(providerSetting: T, params: EmbeddingGenerationParams): EmbeddingGenerationResult  // 默认 error()
    suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem>
    suspend fun editImage(providerSetting: ProviderSetting, params: ImageEditParams): Flow<ImageGenerationItem>  // 默认 error()
}
```

**设计要点**：
- **无状态**：Provider 实现不持有状态，每次调用传入 `providerSetting`
- **泛型约束**：`T : ProviderSetting` 绑定到特定配置子类，编译期类型安全
- **可选能力**：`getBalance` 有默认实现（返回 `"TODO"`），`generateEmbedding`/`editImage` 有默认 `error()` 实现

#### ProviderSetting 配置类

```kotlin
// ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption
    abstract val builtIn: Boolean
    abstract val description: @Composable () -> Unit
    abstract val shortDescription: @Composable () -> Unit

    data class OpenAI(...)   // OpenAI 兼容协议
    data class Google(...)   // Gemini / Vertex AI
    data class Claude(...)   // Anthropic Claude

    companion object {
        val Types = listOf(OpenAI::class, Google::class, Claude::class)
    }
}
```

| 子类 | 关键字段 | 说明 |
|------|----------|------|
| `OpenAI` | `apiKey`, `baseUrl`, `chatCompletionsPath`, `useResponseApi`, `includeHistoryReasoning` | 支持双路径：ChatCompletions API / Response API |
| `Google` | `apiKey`, `baseUrl`, `vertexAI`, `useServiceAccount`, `privateKey`, `serviceAccountEmail`, `location`, `projectId` | 支持 Gemini API 和 Vertex AI（服务账号认证） |
| `Claude` | `apiKey`, `baseUrl`, `promptCaching`, `promptCacheTtl` | 支持 Prompt Cache |

#### ProviderManager — Provider 注册与分发

```kotlin
// ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt
class ProviderManager(client: OkHttpClient, context: Context) {
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
    }

    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude  -> getProvider("claude")
        } as Provider<T>
    }
}
```

#### Provider 实现细节

| 实现 | 文件 | 说明 |
|------|------|------|
| `OpenAIProvider` | `providers/OpenAIProvider.kt` | 根据 `useResponseApi` 分流到 `ChatCompletionsAPI` 或 `ResponseAPI` |
| `GoogleProvider` | `providers/GoogleProvider.kt` | 支持 Gemini API 和 Vertex AI（服务账号 JWT 认证） |
| `ClaudeProvider` | `providers/ClaudeProvider.kt` | 支持 Prompt Cache（`cache_control` 标记） |

**OpenAI 双路径**：
- `ChatCompletionsAPI`（`providers/openai/ChatCompletionsAPI.kt`）：标准 `/chat/completions` 接口
- `ResponseAPI`（`providers/openai/ResponseAPI.kt`）：OpenAI 新版 Response API，支持更丰富的工具调用

#### KeyRoulette — API Key 轮换

```kotlin
// ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt
class KeyRoulette(private val rawKey: String) {
    // 支持空格/逗号/换行分隔多 key，轮换使用避免单 key 限流
}
```

### 3.2 消息模型

#### UIMessage

```kotlin
// ai/src/main/java/me/rerere/ai/ui/Message.kt
data class UIMessage(
    val role: MessageRole,          // USER, ASSISTANT, SYSTEM, TOOL
    val parts: List<UIMessagePart>, // 消息内容部分（多模态）
    val createdAt: LocalDateTime,
    val modelId: Uuid?,
    val usage: TokenUsage?,         // token 使用统计
    val annotations: List<...>?,    // 标注
)
```

#### UIMessagePart — 多模态内容

`UIMessagePart` 是密封类，支持多种内容类型：

| 子类 | 说明 |
|------|------|
| `Text` | 文本内容 |
| `Image` | 图片（URL 或 base64） |
| `Reasoning` | 推理过程（think tag 提取） |
| `Tool` | 工具调用（含 toolCallId, toolName, input, output, approvalState） |
| `Document` | 文档附件 |
| `Audio` | 音频 |
| `Video` | 视频 |

#### MessageChunk — 流式更新块

`MessageChunk` 是 Provider 流式返回的增量块，通过 `handleMessageChunk()` 合并到 UIMessage。

#### TokenUsage

```kotlin
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedTokens: Int = 0,  // 缓存命中的 token
)
```

### 3.3 Tool 定义

```kotlin
// ai/src/main/java/me/rerere/ai/core/Tool.kt
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (Model, List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)
```

**设计要点**：
- `execute` 是挂起函数，支持异步工具
- `needsApproval` 动态判断是否需要用户审批（HITL）
- `systemPrompt` 可注入工具相关的系统提示

### 3.4 registry — 模型能力注册表

`ai/src/main/java/me/rerere/ai/registry/` 下的 `ModelRegistry` 根据 modelId 匹配模型特殊能力（如 QWEN_MT 翻译模型），供 GenerationHandler 等组件查询模型是否需要特殊处理。

---

## 四、app 模块 — 主应用

### 4.1 分层架构

```
app/src/main/java/me/rerere/rikkahub/
├── data/          数据层
│   ├── db/        Room 数据库（Entity, DAO, migrations, FTS）
│   ├── repository/  Repository 层
│   ├── datastore/   DataStore 偏好存储（Settings, PreferencesStore）
│   ├── model/       数据模型（Assistant, Conversation, PromptInjection）
│   ├── ai/          AI 业务逻辑
│   │   ├── transformers/   消息转换管道
│   │   ├── mcp/            MCP 管理器
│   │   ├── tools/          工具实现
│   │   ├── prompts/        Prompt 模板
│   │   ├── GenerationHandler.kt  生成处理器
│   │   └── AIRequestInterceptor.kt
│   ├── files/       文件管理
│   ├── sync/        数据同步（导入导出、备份）
│   └── export/      导出功能
├── service/       核心服务（ChatService）
├── ui/            UI 层
│   ├── pages/     页面（chat, setting, assistant, history...）
│   ├── components/  可复用组件
│   ├── hooks/     Compose Hooks（TTS, ASR）
│   ├── theme/     主题
│   └── activity/  Activity
├── di/            Koin 依赖注入模块
├── web/           Web 服务器路由
└── utils/         工具函数
```

### 4.2 依赖注入（Koin）

```
AppModule → DataSourceModule → RepositoryModule → ViewModelModule
```

| 模块 | 文件 | 注册内容 |
|------|------|----------|
| `AppModule` | `di/AppModule.kt` | Json, Highlighter, AppEventBus, LocalTools, UpdateChecker, AppScope, EmojiData, TTSManager, Firebase (crashlytics/remoteConfig/analytics), SoundEffectPlayer, ChatService, WebServerManager |
| `DataSourceModule` | `di/DataSourceModule.kt` | SettingsStore, Room Database, DAO, MessageFtsManager, McpManager, GenerationHandler, OkHttpClient, ProviderManager, TemplateTransformer, SearchService 初始化, WebDavSync, S3Sync, Retrofit, RikkaHubAPI, SponsorAPI |
| `RepositoryModule` | `di/RepositoryModule.kt` | ConversationRepository, MemoryRepository, GenMediaRepository, FilesRepository, FavoriteRepository, WorkspaceManager, WorkspaceRepository, FilesManager, SkillManager |
| `ViewModelModule` | `di/ViewModelModule.kt` | 所有 ViewModel（参数化注入 conversationId） |

---

## 五、核心数据流 — 消息生成全流程

### 5.1 整体流程

```
用户输入
  │
  ▼
ChatVM.handleMessageSend()
  │
  ▼
ChatService.sendMessage()
  ├── 1. 取消前序生成任务
  ├── 2. finishInterruptedPendingTools() — 清理中断的工具
  ├── 3. preprocessUserInputParts() — 正则替换
  ├── 4. 构建用户 UIMessage，追加到 messageNodes
  ├── 5. saveConversation() — 持久化
  └── 6. handleMessageComplete() — 启动生成
        │
        ▼
      GenerationHandler.generateText()  ← Flow<GenerationChunk>
        │
        ├── 构建工具列表（Search + Local + Workspace + Skill + MCP）
        │
        └── ReAct 循环（最多 256 步）
              │
              ├─── 步骤 N:
              │    │
              │    ├── generateInternal()
              │    │    ├── 构建 system prompt（assistant.prompt + memory + tools prompt）
              │    │    ├── limitContext() — 上下文截断
              │    │    ├── 输入 Transformer 管道处理
              │    │    ├── Provider.streamText() / generateText()
              │    │    ├── 流式收集 → handleMessageChunk() 合并
              │    │    ├── 输出 Transformer: visualTransform() — UI 实时更新
              │    │    └── 输出 Transformer: onGenerationFinish() — 生成完成后处理
              │    │
              │    ├── 检查是否有 tool_call
              │    │    ├── 无 → break（生成结束）
              │    │    └── 有 → 检查 needsApproval
              │    │          ├── 需要审批 → 设为 Pending，break 等待用户
              │    │          └── 无需审批 → 执行工具
              │    │
              │    ├── 执行工具（顺序，forEach）
              │    │    ├── LocalTools.execute()
              │    │    ├── McpManager.callTool()
              │    │    ├── SearchTools.search/scrape
              │    │    ├── WorkspaceTools.shell
              │    │    └── SkillTools.execute
              │    │
              │    ├── 工具输出截断（>32KB 保存到文件）
              │    │
              │    └── 将 tool_result 追加到消息 → 继续下一步
              │
              ▼
      ChatService.collect { chunk → }
        ├── updateConversation() — 更新内存状态
        └── sendLiveUpdateNotification() — 后台通知（如不在前台）
        │
        ▼
      ChatVM ← conversation StateFlow 自动更新
        │
        ▼
      ChatPage UI 重组渲染
```

### 5.2 ChatService — 生成编排中心

**文件**：`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（~1353 行）

**13 个依赖**：`context`, `appScope`, `settingsStore`, `conversationRepo`, `memoryRepository`, `generationHandler`, `templateTransformer`, `providerManager`, `localTools`, `mcpManager`, `filesManager`, `skillManager`, `workspaceRepository`

**核心方法**：

| 方法 | 职责 |
|------|------|
| `sendMessage()` | 用户发送消息，预处理 + 启动生成 |
| `handleMessageComplete()` | 生成编排核心：构建工具、调用 GenerationHandler、收集结果 |
| `regenerateAtMessage()` | 从指定消息重新生成（消息分支） |
| `editMessage()` | 编辑历史消息并重新生成 |
| `compressConversation()` | 手动上下文压缩 |
| `handleToolApproval()` | 处理工具审批（批准/拒绝/回答） |
| `forkConversationAtMessage()` | 从指定消息分叉对话 |
| `generateTitle()` | 自动生成对话标题 |
| `generateSuggestion()` | 生成对话建议 |

**Transformer 管道定义**（ChatService 内）：

```kotlin
// 输入管道（发送给 Provider 前）
private val inputTransformers = listOf(
    TimeReminderTransformer(),      // 时间提醒注入
    PromptInjectionTransformer(),   // 模式注入（ModeInjection）+ Lorebook（RegexInjection）
    PlaceholderTransformer(),       // {{ }} 占位符替换
    DocumentAsPromptTransformer(),  // 文档转文本 prompt
    OcrTransformer(),               // 图片 OCR
)
// 运行时追加: TemplateTransformer + WorkspaceReminderTransformer

// 输出管道（Provider 返回后）
private val outputTransformers = listOf(
    ThinkTagTransformer(),              // <think> → Reasoning part
    Base64ImageToLocalFileTransformer(),// base64 图片 → 本地文件
    RegexOutputTransformer(),           // 正则替换输出
)
```

### 5.3 GenerationHandler — 生成与工具循环

**文件**：`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`

**ReAct 循环**（最多 `maxSteps=256` 步）：

```
for (stepIndex in 0 until maxSteps) {
    1. 构建 toolsInternal（用户工具 + 记忆工具）
    2. 检查 pendingTools（待审批/待回答的工具）
    3. 若无 pending → generateInternal() 调用 Provider
       ├── 构建 system prompt
       ├── limitContext() 截断
       ├── 输入 Transformer 管道
       ├── Provider.streamText() / generateText()
       ├── 输出 Transformer: visualTransform() + onGenerationFinish()
       └── 检查 tool_call
    4. 若有 tool_call 且无需审批 → 执行工具
    5. 将 tool_result 更新到消息 → 进入下一步
}
```

工具输出截断机制见 [7.10 工具输出截断](#710-工具输出截断)。

---

## 六、Transformer 管道

### 6.1 接口定义

```kotlin
// app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt

interface MessageTransformer {
    suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage>
}

interface InputMessageTransformer : MessageTransformer  // 输入管道（发送前）

interface OutputMessageTransformer : MessageTransformer {  // 输出管道（返回后）
    suspend fun visualTransform(ctx, messages): List<UIMessage>      // 流式 UI 实时转换
    suspend fun onGenerationFinish(ctx, messages): List<UIMessage>   // 生成完成后最终处理
}
```

### 6.2 完整 Transformer 列表

| Transformer | 类型 | 职责 |
|-------------|------|------|
| `TimeReminderTransformer` | Input | 注入当前时间信息到 system prompt |
| `PromptInjectionTransformer` | Input | 模式注入（ModeInjection）+ Lorebook（RegexInjection） |
| `PlaceholderTransformer` | Input | `{{ variable }}` 占位符替换 |
| `DocumentAsPromptTransformer` | Input | 文档附件转为文本 prompt |
| `OcrTransformer` | Input | 图片 OCR 提取文本 |
| `TemplateTransformer` | Input | Pebble 模板处理用户消息 |
| `WorkspaceReminderTransformer` | Input | 注入工作空间 cwd 提醒 |
| `ThinkTagTransformer` | Output | `<think>` 标签 → `UIMessagePart.Reasoning` |
| `Base64ImageToLocalFileTransformer` | Output | base64 图片 → 本地文件引用 |
| `RegexOutputTransformer` | Output | 对助手输出应用正则替换 |

### 6.3 执行时机

```
输入管道（generateInternal 中，调用 Provider 前）：
  messages.transforms(inputTransformers)
    → TimeReminder → PromptInjection → Placeholder → Document → OCR → Template → WorkspaceReminder

输出管道 — 流式实时（每次 chunk 更新时）：
  messages.visualTransforms(outputTransformers)
    → ThinkTag → Base64Image → Regex

输出管道 — 生成完成（单次生成结束后）：
  messages.onGenerationFinish(outputTransformers)
    → ThinkTag → Base64Image → Regex
```

---

## 七、工具调用机制

### 7.1 Tool 数据类

```kotlin
// ai/src/main/java/me/rerere/ai/core/Tool.kt
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (Model, List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)
```

| 字段 | 用途 |
|------|------|
| `name` | 发送给 LLM 的 function name |
| `description` | LLM 据此决定是否调用 |
| `parameters` | 延迟计算的 JSON Schema 参数描述 |
| `systemPrompt` | 该工具向系统提示词注入的额外文本 |
| `needsApproval` | 根据输入参数动态判断是否需要用户审批（HITL） |
| `execute` | 挂起函数，接收 JSON 参数，返回 UIMessagePart 列表 |

### 7.2 工具来源与组装

工具在 `ChatService.handleMessageComplete()` 中组装，传给 `GenerationHandler`：

| 来源 | 注入条件 | 文件 |
|------|----------|------|
| **LocalTools** | Assistant 配置 `localTools` 字段 | `data/ai/tools/LocalTools.kt` |
| **MemoryTools** | Assistant 启用 `enableMemory` | `data/ai/tools/MemoryTools.kt` |
| **SearchTools** | Settings `enableWebSearch` 启用 | `data/ai/tools/SearchTools.kt` |
| **WorkspaceTools** | Assistant 配置 `workspaceId` 且 shell READY | `data/ai/tools/WorkspaceTools.kt` |
| **SkillTools** | Assistant 配置 `enabledSkills` | `data/ai/tools/SkillsTools.kt` |
| **MCP Tools** | Assistant 关联的 MCP 服务器的启用工具 | `data/ai/mcp/McpManager.kt` |

GenerationHandler 在 ReAct 循环每一步还会追加 MemoryTools（若 `enableMemory`）。

### 7.3 LocalTools — 5 个本地工具

通过 `LocalTools.getTools(options)` 按 Assistant 配置返回。每个工具对应一个 `LocalToolOption`：

| LocalToolOption | 工具 name | 说明 | needsApproval |
|-----------------|-----------|------|---------------|
| `JavascriptEngine` | `eval_javascript` | QuickJS 引擎执行 JS（ES2020，无 DOM/Node），捕获 console 输出 | 否 |
| `TimeInfo` | `get_time_info` | 获取设备时间（年月日/星期/时区/时间戳） | 否 |
| `Clipboard` | `clipboard_tool` | 读写剪贴板（action: read/write） | 否 |
| `Tts` | `text_to_speech` | TTS 朗读，通过 AppEventBus 触发 | 否 |
| `AskUser` | `ask_user` | 向用户提问（支持 text/single/multi 选择），HITL 流程 | **是**（始终） |

`ask_user` 的 `execute` 抛出 error，实际由 GenerationHandler 的 HITL 流程处理（设为 Pending → 等待用户 Answered）。

### 7.4 MemoryTools — 记忆管理工具

`buildMemoryTools()` 在 GenerationHandler 中按 `enableMemory` 注入：

| 工具 name | 说明 | needsApproval |
|-----------|------|---------------|
| `save_memory` | 创建记忆条目 | 否 |
| `update_memory` | 更新记忆内容 | 否 |
| `delete_memory` | 删除记忆 | 否 |

记忆 ID 来源：`useGlobalMemory` 时用 `GLOBAL_MEMORY_ID`，否则用 `assistant.id`。

### 7.5 SearchTools — 搜索工具

`createSearchTools(settings)` 在 `enableWebSearch` 时注入：

| 工具 name | 说明 | needsApproval |
|-----------|------|---------------|
| `search_web` | 调用当前选中的搜索引擎搜索 | 否 |
| `scrape_web` | 抓取指定 URL 页面内容（仅当引擎支持 scraping 时注册） | 否 |

### 7.6 WorkspaceTools — 工作空间工具

`createWorkspaceTools()` 在 Assistant 配置 `workspaceId` 且工作空间状态为 `READY` 时注入：

| 工具 name | 说明 | needsApproval |
|-----------|------|---------------|
| `workspace_shell` | 在 proot 沙箱中执行 shell 命令 | 否 |
| `workspace_read_file` | 读取工作空间文件 | 否 |
| `workspace_write_file` | 写入工作空间文件 | 否 |
| `workspace_list_files` | 列出目录内容 | 否 |
| `workspace_delete_file` | 删除文件 | 否 |
| `workspace_move_file` | 移动/重命名文件 | 否 |
| `workspace_search_files` | glob 模式搜索文件 | 否 |
| `workspace_grep` | grep 搜索文件内容 | 否 |

所有工具通过 `WorkspaceManager` 执行，操作范围限制在 rootfs 内（路径越界防护）。

### 7.7 SkillTools — 技能工具

`createSkillTools()` 在 Assistant 配置 `enabledSkills` 时注入：

| 工具 name | 说明 | needsApproval |
|-----------|------|---------------|
| `read_skill` | 读取技能文件内容 | 否 |
| `resolve_skill_file` | 解析技能中的文件引用路径 | 否 |

技能从 `SkillManager` 加载，存储在 `{filesDir}/skills/` 目录，通过 bind mount 映射到 rootfs 的 `/skills/`。

### 7.8 MCP 工具

MCP 工具由 `McpManager.getAllAvailableTools()` 动态发现：

```
McpManager.getAllAvailableTools()
  → 遍历 settings.mcpServers 中 enable 且 id 在 assistant.mcpServers 中的服务器
  → 遍历每个服务器的 commonOptions.tools 中 enable 的工具
  → 返回 List<Triple<serverId, serverName, McpTool>>
```

每个 MCP 工具注册为 `Tool` 对象：
- **命名规则**：`mcp__{serverName}__{toolName}`
- **execute 回调**：调用 `mcpManager.callTool(serverId, toolName, args)`
- **返回值转换**：`TextContent` → `UIMessagePart.Text`，`ImageContent` → 保存为文件 → `UIMessagePart.Image`
- **needsApproval**：由 `McpTool.needsApproval` 字段控制（但该字段当前未实际使用，审批在 `Tool.needsApproval` 控制）

#### MCP 绑定层级

```
全局 Settings.mcpServers: List<McpServerConfig>  ← 全局 MCP 服务器配置
Assistant.mcpServers: Set<Uuid>                  ← 助手关联的 MCP ID
McpManager                                       ← 全局单例，管理所有连接
```

MCP 绑定在 Assistant 级别。Conversation 没有自己的 MCP 配置。

#### 传输层

| 传输 | 实现 | 说明 |
|------|------|------|
| SSE | `SseClientTransport` | Server-Sent Events，旧版 MCP 传输 |
| Streamable HTTP | `StreamableHttpClientTransport` | 新版 MCP 传输，推荐 |

两个传输都支持自定义 headers。

#### McpManager 核心职责

1. **连接管理**：监听 `settings.mcpServers` 变化，自动增删 MCP 客户端
2. **工具同步**：`sync()` 从 MCP 服务器拉取工具列表，更新到 Settings
3. **工具调用**：`callTool(serverId, toolName, args)` 执行 MCP 工具
4. **自动重连**：指数退避重连（最多 5 次，1s → 30s）
5. **状态暴露**：`syncingStatus: StateFlow<Map<Uuid, McpStatus>>`

### 7.9 工具审批（HITL）

```kotlin
sealed class ToolApprovalState {
    data object Auto : ToolApprovalState()       // 自动执行
    data object Pending : ToolApprovalState()    // 等待用户审批
    data class Approved(...) : ToolApprovalState()
    data class Denied(val reason: String) : ToolApprovalState()
    data class Answered(val answer: String) : ToolApprovalState()  // ask_user 回答
}
```

**流程**：
1. LLM 返回 tool_call → 检查 `Tool.needsApproval(input)`
2. 需要审批且状态为 `Auto` → 改为 `Pending`，break 循环等待用户
3. 用户操作 → `ChatService.handleToolApproval()`
4. `Approved` → 执行工具；`Denied` → 返回拒绝信息；`Answered` → 返回用户回答

### 7.10 工具输出截断

当存在 `workspace_shell` 工具时，超过阈值的工具输出会被截断：

- 阈值：`MAX_TOOL_OUTPUT_CHARS = 32 * 1024`
- 预览：`TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024`
- 截断后完整输出保存到 `/tool_outputs/{toolCallId}.txt`
- 返回给 LLM 的是：截断标记 + 预览 + 文件路径提示（`cat /tool_outputs/{file}`）

---

## 八、上下文管理

### 8.1 limitContext — 硬截断（自动）

**文件**：`ai/src/main/java/me/rerere/ai/ui/Message.kt`

```kotlin
fun List<UIMessage>.limitContext(contextMessageSize: Int): List<UIMessage>
```

- `contextMessageSize = 0` → 不截断（保留全部）
- `contextMessageSize > 0` → 只保留最近 N 条消息
- **智能边界**：截断点若落在 tool_call/tool_result 中间，自动向前扩展，保证工具调用对完整

**调用位置**：`GenerationHandler.generateInternal()` 中，构建 `internalMessages` 时调用 `messages.limitContext(assistant.contextMessageSize)`，随后整体经输入 Transformer 管道 `transforms()` 处理。

> **本质**：这是丢弃，不是压缩。被截断的消息永久从本次请求中移除（但仍在数据库中）。

### 8.2 compressConversation — LLM 摘要压缩（手动）

**文件**：`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:848`

**触发方式**：纯手动，用户点击"压缩上下文"按钮。**无自动触发。**

**流程**：
1. 获取压缩模型（`settings.compressModelId`）
2. 分割：前 N 条压缩 + 后 `keepRecentMessages` 条保留
3. 分块：超过 256 条的消息用二分递归分块
4. 并行压缩：每个 chunk 异步调用 LLM 生成摘要
5. 替换：摘要作为 user 消息 + 保留的最近消息 = 新对话

**可配置参数**（Settings）：
- `compressModelId`：压缩用的模型
- `compressPrompt`：压缩指令模板
- 用户可配：`targetTokens`（500/1000/2000/4000）、`keepRecentMessages`（0/16/32/64）

### 8.3 Token 计数

- **数据来源**：Provider API 返回的 `usage` 字段（`TokenUsage`），存入 UIMessage
- **无独立 Token 估算器**：没有字符数估算逻辑
- **无自动溢出检测**：不会根据 token 数自动触发压缩

---

## 九、数据层

### 9.1 Room 数据库

**文件**：`app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`

- **版本**：23
- **7 个 Entity**：ConversationEntity, MemoryEntity, GenMediaEntity, MessageNodeEntity, ManagedFileEntity, FavoriteEntity, WorkspaceEntity
- **7 个 DAO**：各自对应 Entity 的 CRUD
- **FTS**：`MessageFtsManager` — SQLite FTS 虚拟表，支持 jieba 中文分词全文搜索
- **Migrations**：5 个手动 Migration + 17 个 AutoMigration（版本 1→23）

### 9.2 Repository 层

| Repository | 职责 |
|------------|------|
| `ConversationRepository` | 会话全生命周期：CRUD、分页、FTS 索引同步、消息节点管理、收藏关联 |
| `MemoryRepository` | 助手记忆（全局/隔离），支持 `GLOBAL_MEMORY_ID` |
| `FavoriteRepository` | 收藏管理 |
| `FilesRepository` | 受管理文件 |
| `GenMediaRepository` | AI 生成媒体记录 |
| `WorkspaceRepository` | 工作空间 |

### 9.3 DataStore — Settings

**文件**：`app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`

`Settings` 数据类是全局配置中心，关键字段：

```kotlin
data class Settings(
    // AI / Provider
    val providers: List<ProviderSetting>,           // Provider 配置列表
    val assistants: List<Assistant>,                // 助手列表
    val assistantId: Uuid,                          // 当前助手
    val chatModelId: Uuid,                          // 默认聊天模型
    val fastModelId: Uuid,                          // 快速模型
    val titleModelId: Uuid?,                        // 标题生成模型
    val imageGenerationModelId: Uuid,               // 图片生成模型
    val translateModeId: Uuid,                      // 翻译模型
    val compressModelId: Uuid,                      // 压缩模型
    val suggestionModelId: Uuid?,                   // 建议生成模型
    val ocrModelId: Uuid,                           // OCR 模型

    // 搜索
    val enableWebSearch: Boolean,
    val searchServices: List<SearchServiceOptions>,
    val searchServiceSelected: Int,

    // MCP
    val mcpServers: List<McpServerConfig>,

    // 语音
    val ttsProviders: List<TTSProviderSetting>,
    val asrProviders: List<ASRProviderSetting>,

    // 注入
    val modeInjections: List<PromptInjection.ModeInjection>,
    val lorebooks: List<Lorebook>,

    // Web 服务器
    val webServerEnabled: Boolean,
    val webServerPort: Int,
    val webServerJwtEnabled: Boolean,
    // ... 其他 UI/主题/备份配置
)
```

**DefaultProviders**：18 个预设 Provider 配置（RikkaHub, OpenAI, Gemini, AiHubMix, 硅基流动, DeepSeek, OpenRouter 等），大部分使用 `ProviderSetting.OpenAI` 类型。

### 9.4 核心数据模型

#### Assistant

```kotlin
data class Assistant(
    val id: Uuid,
    val chatModelId: Uuid?,                  // null = 用全局默认
    val systemPrompt: String,
    val temperature: Float?,
    val topP: Float?,
    val contextMessageSize: Int,             // 0 = 不截断
    val streamOutput: Boolean,
    val enableMemory: Boolean,
    val useGlobalMemory: Boolean,
    val enableRecentChatsReference: Boolean,
    val messageTemplate: String,             // Pebble 模板
    val presetMessages: List<UIMessage>,     // 预设消息
    val regexes: List<AssistantRegex>,       // 输入/输出正则替换
    val reasoningLevel: ReasoningLevel,
    val maxTokens: Int?,
    val customHeaders: List<CustomHeader>,
    val customBodies: List<CustomBody>,
    val mcpServers: Set<Uuid>,               // 关联的 MCP 服务器
    val localTools: List<LocalToolOption>,   // 启用的本地工具
    val workspaceId: Uuid?,                  // 关联的工作空间
    val modeInjectionIds: Set<Uuid>,         // 模式注入
    val lorebookIds: Set<Uuid>,              // Lorebook
    val enabledSkills: Set<String>,          // 技能
    val enableTimeReminder: Boolean,
    val allowConversationSystemPrompt: Boolean,
    val allowConversationPromptInjection: Boolean,
)
```

#### Conversation

```kotlin
data class Conversation(
    val id: Uuid,
    val title: String,
    val messageNodes: List<MessageNode>,     // 树形消息节点
    val assistantId: Uuid,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isPinned: Boolean,
    val suggestions: List<String>,
    val customSystemPrompt: String?,         // 对话级 system prompt 覆盖
    val modeInjectionIds: Set<Uuid>,         // 对话级模式注入
    val lorebookIds: Set<Uuid>,              // 对话级 Lorebook
    val workspaceCwd: String?,               // 工作空间目录
)
```

#### MessageNode — 消息分支

```kotlin
data class MessageNode(
    val id: Uuid,
    val messages: List<UIMessage>,   // 同一节点的多个消息（分支）
    val selectIndex: Int,            // 当前选中的消息索引
)
```

支持对话分支：用户可以从任意消息重新生成，产生新的分支。

#### PromptInjection — 提示词注入

```kotlin
sealed class PromptInjection {
    data class ModeInjection(...)    // 基于开关的注入（如学习模式）
    data class RegexInjection(...)   // 基于关键词/正则匹配的注入（Lorebook/世界书）
}

data class Lorebook(
    val entries: List<PromptInjection.RegexInjection>,
)
```

注入位置：`BEFORE_SYSTEM_PROMPT`, `AFTER_SYSTEM_PROMPT`, `TOP_OF_CHAT`, `BOTTOM_OF_CHAT`, `AT_DEPTH`

---

## 十、UI 层

### 10.1 页面结构

| 目录 | 核心页面 | 职责 |
|------|----------|------|
| `chat/` | ChatPage, ChatList, ChatDrawer | 主聊天界面 |
| `setting/` | SettingPage + 15 个子页 | 设置总入口 |
| `assistant/` | AssistantPage, AssistantDetailPage | 助手管理 |
| `history/` | HistoryPage | 历史对话 |
| `search/` | SearchPage | 全局消息搜索（FTS） |
| `extensions/` | PromptPage, SkillsPage, WorkspacePage | 扩展功能 |
| `imggen/` | ImgGenPage | AI 图片生成 |
| `translator/` | TranslatorPage | 翻译工具 |
| `backup/` | BackupPage | 数据备份（WebDAV/S3） |
| `debug/` | DebugPage | 调试 |

### 10.2 ChatVM ↔ ChatService 交互

```
ChatVM (chat/ChatVM.kt)
  ├── conversation: StateFlow<Conversation>  ← chatService.getConversationFlow(id)
  ├── handleMessageSend()    → chatService.sendMessage()
  ├── handleMessageEdit()    → chatService.editMessage()
  ├── regenerateAtMessage()  → chatService.regenerateAtMessage()
  ├── stopGeneration()       → chatService.stopGeneration()
  ├── handleToolApproval()   → chatService.handleToolApproval()
  ├── handleCompressContext()→ chatService.compressConversation()
  ├── forkMessage()          → chatService.forkConversationAtMessage()
  └── deleteMessage()        → chatService.deleteMessage()
```

### 10.3 导航

**文件**：`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`

使用 Navigation3，核心路由：
- `chat/{conversationId}` — 聊天页
- `setting` → 子路由：provider, model, speech, search, mcp, theme, about...
- `assistant` / `assistant/{id}` — 助手列表/详情
- `history` — 历史对话
- `search` — 全局搜索

### 10.4 版本更新检查

**文件**：`app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt`

```kotlin
class UpdateChecker(private val client: OkHttpClient) {
    fun checkUpdate(): Flow<UiState<UpdateInfo>>   // 检查更新
    fun downloadUpdate(context: Context, download: UpdateDownload)  // 下载更新包
}
```

**机制**：

1. `checkUpdate()` 向更新接口发送 GET 请求，User-Agent 携带当前版本名和版本号
2. 响应为 `UpdateInfo` JSON：`version`（最新版本号）、`publishedAt`（发布时间）、`changelog`（更新日志）、`downloads`（下载列表）
3. `UpdateCard`（`ui/components/ui/UpdateCard.kt`）在聊天抽屉（ChatDrawer）中展示新版本提示：比较 `Version(info.version) > Version(BuildConfig.VERSION_NAME)`，若新版本更高则显示卡片
4. 用户点击卡片查看详情，选择下载项后 `downloadUpdate()` 通过系统 `DownloadManager` 下载 APK 到 `Download/` 目录

**数据模型**：

```kotlin
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

data class UpdateDownload(val name: String, val url: String, val size: String)
```

**版本比较**：`Version` 是基于 SemVer 规范的 value class，支持 `MAJOR.MINOR.PATCH[-prerelease][+build]` 格式比较，预发布版本优先级低于正式版。

**集成位置**：`UpdateChecker` 在 `AppModule` 中注册为单例，注入到 `ChatVM`，`ChatVM` 在初始化时调用 `checkUpdate()` 暴露 `updateState: StateFlow`，`UpdateCard` 在 ChatDrawer 中收集该状态。

---

## 十一、功能模块

### 11.1 search 模块

**17 个搜索引擎**，统一接口：

```kotlin
interface SearchService<T : SearchServiceOptions> {
    suspend fun search(params, commonOptions, serviceOptions): Result<SearchResult>
    suspend fun scrape(params, commonOptions, serviceOptions): Result<ScrapedResult>
}
```

| 引擎 | 需 API Key | 说明 |
|------|-----------|------|
| Tavily, Exa, Brave, Zhipu, Perplexity, Jina, Bocha, Metaso, Firecrawl, Grok, LinkUp, Ollama, RikkaHub | 是 | 商业/自托管 API |
| Bing | 否 | 本地抓取 |
| SearXNG | 否 | 自托管实例 |
| Custom JS | 否 | QuickJS 执行用户脚本 |
| Tinyfish | 是 | - |

**被 app 调用**：`SearchTools.kt` 创建 `search_web` 和 `scrape_web` 两个 AI Tool。

### 11.2 speech 模块

**8 个 TTS Provider**：System, Edge, Azure, CosyVoice, FishAudio, Volcano, Doubao, SiliconFlow

**3 个 ASR Provider**：System, Azure, Volcano

统一接口 `TTSProvider` / `ASRProvider`，被 app 的 `ui/hooks/TTS.kt` 和 `ui/hooks/ASR.kt` 调用。

### 11.3 workspace 模块

**proot 用户态沙箱**，在无 root 权限下通过 ptrace 拦截系统调用实现 chroot + bind mount + uid/gid 映射，提供完整的 Linux rootfs 环境。

#### 模块结构

`workspace/` 模块 7 个 Kotlin 文件 + CMake 原生构建：

| 文件 | 核心类 | 职责 |
|------|--------|------|
| `Workspace.kt` | `Workspace`, `WorkspaceShellStatus`, `RootfsInstallStage` 等数据模型 | 全模块数据模型与枚举 |
| `WorkspaceManager.kt` | `WorkspaceManager` | 文件系统 + Shell 执行的统一入口，编排 FileSystem 和 ShellRunner |
| `WorkspaceShellRunner.kt` | `WorkspaceShellRunner`(接口), `HostShellRunner` | Shell 执行抽象、主机 Shell 实现 |
| `ProotShellRunner.kt` | `ProotShellRunner`, `WorkspaceBindMount` | proot 沙箱 Shell 执行器 |
| `WorkspaceFileSystem.kt` | `WorkspaceFileSystem` | 文件 CRUD（list/read/write/delete/move/glob/grep），含路径越界防护 |
| `RootfsInstaller.kt` | `RootfsInstaller` | 从 URL 下载 rootfs tar 压缩包，解压到工作区 |
| `RootfsPatcher.kt` | `RootfsPatcher` | rootfs 解压后配置修补（DNS、hosts、hostname、locale） |

CMake 构建两个原生库：
- `libworkspace.so` — 空壳库
- `libtermux.so` — PTY 终端 JNI 支持（createSubprocess/setPtyWindowSize/waitFor/close）

proot 本身以预编译 `.so` 形式打包（`libproot_exec.so` + `libproot_loader.so`），通过 `nativeLibraryDir` 加载。

#### Rootfs 目录结构

每个工作区在磁盘上的布局：
```
{filesDir}/workspaces/{root}/
├── rootfs/              ← 解压的 Linux 根文件系统
│   ├── bin/  usr/  etc/  ...
│   └── skills/          ← bind mount → {filesDir}/skills/
│   └── tool_outputs/    ← bind mount → {filesDir}/tool_outputs/
├── .installed           ← 安装标记
└── proot_tmp/           ← proot 临时文件
```

#### Shell 执行流程

```
ProotShellRunner.execute(command, cwd, env)
  │
  ├── 构建 proot 命令：
  │     proot --rootfs={rootfs} --cwd={cwd}
  │           --bind=/dev:/dev --bind=/proc:/proc --bind=/sys:/sys
  │           --bind={skills_dir}:/skills
  │           --bind={tool_outputs_dir}:/tool_outputs
  │           --kill-on-exit --root-id
  │           /bin/sh -c "{command}"
  │
  ├── 启动子进程（ProcessBuilder）
  ├── 通过 StreamWriter 写入 stdin
  ├── 通过 StreamCollector 采集 stdout/stderr
  └── 返回 WorkspaceCommandResult（exitCode, stdout, stderr）
```

#### WorkspaceShellStatus 状态机

```
INITIALIZING ──→ READY ──→ RUNNING ──→ READY
     │                │
     └──→ ERROR ←─────┘
```

- `INITIALIZING`：rootfs 安装中
- `READY`：可用，无命令执行
- `RUNNING`：有命令正在执行
- `ERROR`：rootfs 安装失败或其他错误

#### app 集成

| 组件 | 文件 | 职责 |
|------|------|------|
| `WorkspaceRepository` | `data/repository/WorkspaceRepository.kt` | 工作区 CRUD + Shell 状态管理 |
| `WorkspaceEntity` | `data/db/entity/WorkspaceEntity.kt` | 持久化（id, name, root, shellStatus, toolApprovals） |
| `WorkspaceTools` | `data/ai/tools/WorkspaceTools.kt` | 注册为 AI Tool |
| `WorkspaceReminderTransformer` | `data/ai/transformers/WorkspaceReminderTransformer.kt` | 注入工作空间 cwd 提醒到 system prompt |

工作区与 Assistant 的关联：`Assistant.workspaceId: Uuid?`，仅当工作区状态为 `READY` 时工具才注入。

### 11.4 document 模块

解析 PDF/DOCX/PPTX/EPUB 为文本，被 `DocumentAsPromptTransformer` 调用。

### 11.5 web 模块

**Ktor Server**，提供嵌入式 Web 服务器，允许通过浏览器访问手机 AI 功能。

- `web/Entry.kt` — `startWebServer()` 入口，Ktor CIO 引擎
- `app/.../web/WebServerManager.kt` — 服务器生命周期管理 + mDNS/NSD 局域网发现
- `app/.../service/WebServerService.kt` — Android 前台服务，保持服务器运行
- 路由：`ConversationRoutes`（对话 CRUD + SSE）、`SettingsRoutes`、`FilesRoutes`、`AIIconRoutes`
- 支持 JWT 认证、localhost-only 模式、密码访问
- 前端静态资源来自 `web-ui/` React 项目，`web/build.gradle.kts` 的 `preBuild` 阶段执行 `pnpm run build`

> **注意**：Ktor 在项目中分两套。Ktor **Server** 依赖在 `web/` 模块（仅 Web 服务器用）；Ktor **Client** 依赖在 `app/` 模块（MCP transport 通信 + WebDAV/S3 备份用）。两者独立。

---

## 附录：关键文件索引

| 文件 | 职责 |
|------|------|
| `service/ChatService.kt` | 生成编排中心，协调 Provider/Transformer/Tools/MCP |
| `data/ai/GenerationHandler.kt` | ReAct 循环 + 工具执行 + 工具输出截断 |
| `data/ai/transformers/Transformer.kt` | Transformer 接口定义 + 管道执行扩展函数 |
| `data/ai/mcp/McpManager.kt` | MCP 连接管理/工具同步/工具调用/自动重连 |
| `data/ai/mcp/McpConfig.kt` | MCP 配置模型（SseTransportServer/StreamableHTTPServer） |
| `data/ai/tools/LocalTools.kt` | 5 个本地工具定义 |
| `data/model/Assistant.kt` | 助手模型 + PromptInjection + Lorebook |
| `data/datastore/PreferencesStore.kt` | Settings 全局配置 + DataStore 读写 |
| `data/datastore/DefaultProviders.kt` | 18 个预设 Provider |
| `ai/.../provider/Provider.kt` | Provider 接口定义 |
| `ai/.../provider/ProviderSetting.kt` | Provider 配置密封类（OpenAI/Google/Claude） |
| `ai/.../provider/ProviderManager.kt` | Provider 注册与分发 |
| `ai/.../ui/Message.kt` | UIMessage + UIMessagePart + limitContext + handleMessageChunk |
