# 助手配置与对话交互参考

> 本文档以 Measix Pilot 当前代码为准，完整描述 Assistant（助手）概念的数据模型、全部配置项及其定义位置，
> 分析配置中的阻断性条件，并详细梳理助手在一个对话 session 中的完整交互流程和对 LLM 的干预/微调机制。
>
> **相关文档**：[消息生成链路](chat-generation-pipeline.md) | [界面架构参考](ui-architecture.md)

---

## 目录

1. [Assistant 数据模型](#1-assistant-数据模型)
2. [完整配置项清单](#2-完整配置项清单)
3. [关联数据类型](#3-关联数据类型)
4. [阻断性配置分析](#4-阻断性配置分析)
5. [对话 Session 交互流程](#5-对话-session-交互流程)
6. [LLM 干预与微调机制](#6-llm-干预与微调机制)
7. [配置项与代码文件映射](#7-配置项与代码文件映射)

---

## 1. Assistant 数据模型

### 定义文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt` | `Assistant` data class 及关联类型（`AssistantRegex`、`PromptInjection`、`InjectionPosition` 等） |
| `ai/src/main/java/me/rerere/ai/core/Reasoning.kt` | `ReasoningLevel` 枚举 |
| `ai/src/main/java/me/rerere/ai/provider/Provider.kt` | `CustomHeader`、`CustomBody` 数据类 |
| `app/src/main/java/net/weero/measix/pilot/data/ai/tools/local/LocalToolOption.kt` | `LocalToolOption` 密封类 |
| `app/src/main/java/net/weero/measix/pilot/data/datastore/PreferencesStore.kt` | `Settings` 中的助手解析函数（`getChatModel`、`getAssistantById` 等） |

### 数据模型概览

```kotlin
@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null,
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false,
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = true,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo, LocalToolOption.Tts, LocalToolOption.AskUser),
    val enableWebSearch: Boolean = false,
    val workspaceId: Uuid? = null,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val useGradientBackground: Boolean = false,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val enableTimeReminder: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val allowConversationPromptInjection: Boolean = false,
    // 子助手配置
    val description: String = "",
    val allowAsSubAssistant: Boolean = false,
    val isSubAssistantGloballyVisible: Boolean = false,
    val allowedSubAssistantIds: Set<Uuid> = emptySet(),
)
```

`Assistant` 存储在 `Settings.assistants: List<Assistant>` 中，通过 DataStore 持久化。全局当前选中的助手由 `Settings.assistantId` 标识。会话通过 `Conversation.assistantId` 引用助手，是已创建会话的助手权威来源。

### 助手解析规则

```kotlin
// 会话级：解析会话引用的助手，已被删除时回退到当前全局助手
fun Settings.getConversationAssistant(assistantId: Uuid): Assistant =
    getAssistantById(assistantId) ?: getCurrentAssistant()

// 模型解析：优先助手配置的 chatModelId，null 时回退到全局 chatModelId
fun Settings.getChatModel(assistant: Assistant): Model? =
    providers.filter { it.enabled }
        .flatMap { it.models }
        .firstOrNull { it.id == (assistant.chatModelId ?: chatModelId) && it.type == ModelType.CHAT }
```

---

## 2. 完整配置项清单

按功能维度分组。

### 2.1 身份与展示

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `id` | `Uuid` | 随机生成 | 助手唯一标识符 |
| `name` | `String` | `""` | 助手名称，用于 UI 显示和 `{{char}}` 占位符 |
| `avatar` | `Avatar` | `Avatar.Dummy` | 头像类型（文字/图片/渐变/emoji） |
| `useAssistantAvatar` | `Boolean` | `false` | 使用助手头像替代模型头像显示在聊天消息中 |
| `background` | `String?` | `null` | 聊天页背景图地址（本地 URI 或网络 URL） |
| `backgroundOpacity` | `Float` | `1.0f` | 背景图不透明度（0~1） |
| `useGradientBackground` | `Boolean` | `false` | 聊天页使用动态渐变背景 |
| `tags` | `List<Uuid>` | `emptyList()` | 助手标签列表（用于分组管理） |

### 2.2 模型与采样参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `chatModelId` | `Uuid?` | `null` | 绑定的聊天模型 ID；null 时使用全局默认模型 |
| `temperature` | `Float?` | `null` | 采样温度；null 时使用 Provider 默认值 |
| `topP` | `Float?` | `null` | Top-P 核采样；null 时使用 Provider 默认值 |
| `maxTokens` | `Int?` | `null` | 模型输出上限 token 数（非输入上下文窗口） |
| `reasoningLevel` | `ReasoningLevel` | `AUTO` | 推理等级：OFF / AUTO / LOW / MEDIUM / HIGH / XHIGH |
| `streamOutput` | `Boolean` | `true` | 是否流式输出；false 时使用一次性生成 |
| `contextMessageLimit` | `Int` | `0` | 消息条数阈值，0 禁用自动裁剪；有效范围 40~512 |

### 2.3 系统提示词与模板

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `systemPrompt` | `String` | `""` | 系统提示词，支持占位符 `{{char}}`、`{{model_name}}` 等 |
| `messageTemplate` | `String` | `"{{ message }}"` | Pebble 模板，包裹每条消息文本 |
| `presetMessages` | `List<UIMessage>` | `emptyList()` | 预设消息，新建对话时自动添加 |
| `enableTimeReminder` | `Boolean` | `false` | 在跨时段消息前注入 `<time_reminder>` 标签 |

### 2.4 记忆系统

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableMemory` | `Boolean` | `true` | 启用记忆功能；注入 `memory_tool` 和记忆列表到 System Prompt |
| `useGlobalMemory` | `Boolean` | `false` | true 使用全局共享记忆；false 使用助手隔离记忆 |

### 2.5 工具配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `localTools` | `List<LocalToolOption>` | `[TimeInfo, Tts, AskUser]` | 启用的本地工具列表 |
| `enableWebSearch` | `Boolean` | `false` | 启用网络搜索工具（每个助手独立） |
| `mcpServers` | `Set<Uuid>` | `emptySet()` | 关联的 MCP 服务器 ID 列表 |
| `workspaceId` | `Uuid?` | `null` | 绑定的工作空间 ID |
| `enableRecentChatsReference` | `Boolean` | `false` | 启用近期对话引用工具（`recent_chats` / `conversation_search`） |

### 2.6 正则变换

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `regexes` | `List<AssistantRegex>` | `emptyList()` | 正则替换规则列表 |

`AssistantRegex` 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Uuid` | 规则 ID |
| `name` | `String` | 规则名称 |
| `enabled` | `Boolean` | 是否启用 |
| `findRegex` | `String` | 正则表达式 |
| `replaceString` | `String` | 替换字符串 |
| `affectingScope` | `Set<AssistantAffectScope>` | 影响范围：USER / ASSISTANT |
| `visualOnly` | `Boolean` | 是否仅影响视觉显示（不修改持久化内容） |

### 2.7 提示词注入

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `modeInjectionIds` | `Set<Uuid>` | `emptySet()` | 关联的模式注入 ID |
| `allowConversationSystemPrompt` | `Boolean` | `false` | 允许对话单独重写 System Prompt |
| `allowConversationPromptInjection` | `Boolean` | `false` | 允许对话单独绑定提示词注入 |

### 2.8 自定义请求

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `customHeaders` | `List<CustomHeader>` | `emptyList()` | 自定义 HTTP 请求头，合并到 Provider 请求 |
| `customBodies` | `List<CustomBody>` | `emptyList()` | 自定义请求体字段，合并到 Provider 请求 |

### 2.9 快捷消息与技能

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `quickMessageIds` | `Set<Uuid>` | `emptySet()` | 关联的快捷消息 ID 列表 |
| `enabledSkills` | `Set<String>` | `emptySet()` | 启用的 Skill 名称列表 |

### 2.10 子助手配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `description` | `String` | `""` | 路由描述，简短说明角色或擅长领域；开启子助手类别时必须非空；规范化时折叠空白并限 240 code point |
| `allowAsSubAssistant` | `Boolean` | `false` | 是否属于子助手类别且可被 `assistant_call` 调用；关闭时通过 `updateAtomic` 同一次原子更新中关闭全局可见并从所有 Assistant 的 `allowedSubAssistantIds` 移除其 ID，避免重新开启时静默恢复旧授权 |
| `isSubAssistantGloballyVisible` | `Boolean` | `false` | 全局可见权限开关；开启后所有启用子助手工具的 Assistant 可发现、调用和管理该 Target，但修改和删除仍需确认，启用管理工具的助手也可查看其局部记忆 |
| `allowedSubAssistantIds` | `Set<Uuid>` | `emptySet()` | 管理与调用共用的显式允许列表；`assistant_manage(CREATE)` 创建时原子加入 caller |

有效访问公式：`Target.allowAsSubAssistant && Target.id != Caller.id && (Target.id in Caller.allowedSubAssistantIds || Target.isSubAssistantGloballyVisible)`

工具创建的子助手由 `buildToolCreatedAssistant` 显式构造，`chatModelId = null` 表示调用时继承 caller 的有效 Chat Model 与模型执行参数，不写回 Target。Target 明确绑定模型时严格使用自身模型和参数；绑定失效返回 `target_model_unavailable`，不会静默 fallback。Target 未绑定且 caller 也没有有效模型时返回 `caller_model_unavailable`。继承范围仅含 model、temperature/topP/maxTokens/reasoning、stream/context limit 和 Assistant 级 custom headers/bodies；Target 的身份、System Prompt、工具、记忆与权限保持独立。配置 UI 对未绑定模型显示继承说明，只对显式绑定但失效显示错误。其 Local Tools 与普通 Assistant 共用 `DEFAULT_ASSISTANT_LOCAL_TOOLS`，默认包含 `TimeInfo`、`Tts`、`AskUser`，Target Run 中实际注册为 `get_time_info`、`text_to_speech`、`ask_user`。`ask_user` 的 Pending 由 Coordinator 按 Child `messageId + toolOrdinal` 桥接到主聊天子助手卡片；其余需审批 Tool 在 Target 模式返回 `tool_not_permitted`。Assistant 管理与再次委托能力仍被过滤。Web Search、Recent Chats、MCP、Workspace、Skills 等扩展能力默认关闭。

---

## 3. 关联数据类型

### 3.1 ReasoningLevel

```kotlin
enum class ReasoningLevel(val budgetTokens: Int, val effort: String) {
    OFF(0, "none"),         // 关闭推理
    AUTO(-1, "auto"),       // 自动（由模型决定）
    LOW(1_000, "low"),      // 低预算推理
    MEDIUM(2_000, "medium"),// 中等预算
    HIGH(8_000, "high"),    // 高预算
    XHIGH(16_000, "xhigh"); // 超高预算
}
```

### 3.2 LocalToolOption

| 选项 | 工具名 | 说明 |
|------|--------|------|
| `TimeInfo` | `get_time_info` | 时间信息工具 |
| `Tts` | `text_to_speech` | 语音合成工具 |
| `AskUser` | `ask_user` | 向用户提问（需 HITL 审批） |
| `JavascriptEngine` | `eval_javascript` | JavaScript 执行引擎 |
| `Clipboard` | `clipboard_tool` | 剪贴板读取工具 |
| `ScreenTime` | `get_screen_time` | 屏幕使用时间工具 |
| `Calendar` | `calendar_query` / `calendar_create` | 日历查询和创建工具 |

### 3.3 PromptInjection (ModeInjection)

`PromptInjection` 是密封类，当前唯一实现是 `ModeInjection`：

```kotlin
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int
    abstract val role: MessageRole

    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()
}
```

### 3.4 InjectionPosition

| 位置 | 说明 |
|------|------|
| `BEFORE_SYSTEM_PROMPT` | 系统提示词之前 |
| `AFTER_SYSTEM_PROMPT` | 系统提示词之后（最常用） |
| `TOP_OF_CHAT` | 对话最开头（第一条用户消息之前） |
| `BOTTOM_OF_CHAT` | 最新消息之前（当前用户输入之前） |
| `AT_DEPTH` | 在指定深度位置插入（从最新消息往前数） |

### 3.5 CustomHeader / CustomBody

```kotlin
data class CustomHeader(val name: String, val value: String)
data class CustomBody(val key: String, val value: JsonElement)
```

---

## 4. 阻断性配置分析

阻断分为两种类型：

- **硬阻断（Type A）**：缺少配置就无法使用该助手进行对话
- **流程阻断（Type B）**：在对话过程中，用户不干预的情况下会阻断 turn 流程

### 4.1 硬阻断（Type A）：缺少配置无法对话

#### 4.1.1 模型不可用 — 阻断

**判定逻辑**（`ConversationReadiness.kt` + `ChatService.handleMessageComplete`）：

```kotlin
// ConversationReadiness.kt
val hasAvailableChatModel = providers.any {
    it.enabled && it.models.any { it.type == ModelType.CHAT }
}
val selectedModel = getChatModel(assistant)
val modelState = when {
    !hasAvailableChatModel -> ModelReadiness.NOT_CONFIGURED  // 无可用 Provider/模型
    selectedModel == null -> ModelReadiness.NOT_SELECTED      // 模型未选中
    else -> ModelReadiness.READY
}

// canSend 仅在 READY 时为 true
val canSend: Boolean get() = modelState == ModelReadiness.READY
```

**在 `ChatService.handleMessageComplete` 中的表现**：

```kotlin
val model = settings.getChatModel(assistant) ?: return  // 静默返回，不生成
```

| 场景 | 原因 | 影响 |
|------|------|------|
| 无启用 Provider | 所有 Provider 均未启用或未配置 API Key | 无法解析模型，对话不启动 |
| 无 CHAT 类型模型 | 启用的 Provider 下没有 CHAT 类型模型 | 同上 |
| 模型 ID 无效 | `assistant.chatModelId` 指向的模型已被删除，且全局 `chatModelId` 也无效 | 同上 |

**注意**：`chatModelId` 为 `null` 不是阻断条件，会回退到全局默认模型。只有当回退后的模型 ID 仍无法在启用的 Provider 中找到匹配的 CHAT 类型模型时才阻断。

#### 4.1.2 非阻断配置

以下配置缺失时**不影响对话能力**，仅影响功能丰富度：

| 配置 | 缺失时的行为 |
|------|-------------|
| `systemPrompt` 为空 | 不发送 System 消息（Provider 使用默认行为） |
| `name` 为空 | UI 显示"默认助手"，`{{char}}` 占位符降级为 "assistant" |
| `enableMemory` 为 false | 不注入记忆工具和记忆列表 |
| `enableWebSearch` 为 false | 不装配搜索工具 |
| `mcpServers` 为空 | 不装配 MCP 工具 |
| `workspaceId` 为 null | 不装配工作空间工具 |
| `localTools` 为空 | 不装配本地工具 |
| `enabledSkills` 为空 | 不装配技能工具 |
| `presetMessages` 为空 | 新建对话时不添加预设消息 |
| `regexes` 为空 | 不执行正则替换 |
| `modeInjectionIds` 为空 | 不注入提示词 |
| `customHeaders` / `customBodies` 为空 | 不添加自定义请求参数 |

### 4.2 流程阻断（Type B）：对话中阻断 turn 流程

这些情况不阻止对话开始，但在生成过程中会暂停 turn 循环，**必须用户干预才能继续**。

#### 4.2.1 工具审批（HITL）— 阻断

**机制**（`GenerationHandler.generateText`）：

```kotlin
// 工具的 needsApproval 返回 true 且状态为 Auto → 设为 Pending
toolDef?.needsApproval(tool.inputAsJson()) == true &&
    tool.approvalState is ToolApprovalState.Auto -> {
    hasPendingApproval = true
    tool.copy(approvalState = ToolApprovalState.Pending)
}

// 有待审批工具时，暂停生成循环
if (hasPendingApproval) {
    Log.i(TAG, "generateText: waiting for tool approval")
    break  // 退出生成循环，等待用户操作
}
```

**触发审批的工具**：

| 工具 | 触发条件 | 用户操作 |
|------|----------|----------|
| `ask_user` | `needsApproval = { true }`，**总是触发** | 用户回答问题后继续 |
| MCP 工具 | `tool.needsApproval` 由 MCP 服务器定义 | 用户批准或拒绝 |
| Workspace Shell | 部分配置可能需要审批 | 用户批准或拒绝 |

**恢复机制**：用户通过 `ChatVM.handleToolApproval()` / `ChatVM.handleToolAnswer()` 处理后，`ChatService.handleToolApproval` 更新审批状态，并在所有待处理工具处理完毕后调用 `handleMessageComplete()` 恢复生成。

```kotlin
// 只有所有 Pending 工具都处理完毕才继续
if (!hasPendingTools) {
    handleMessageComplete(conversationId)
}
```

#### 4.2.2 模型不支持工具但配置了工具 — 非阻断但降级

```kotlin
if (!model.abilities.contains(ModelAbility.TOOL)) {
    if (assistant.enableWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty()) {
        addError(
            IllegalStateException(context.getString(R.string.tools_warning)),
            conversationId,
            title = context.getString(R.string.error_title_tool_unavailable)
        )
    }
}
```

此场景**不阻断**生成（会继续不带工具发送请求），但会报错提醒用户。模型可能因缺少工具能力而无法正确处理工具相关指令。

#### 4.2.3 工具执行失败 — 非阻断

工具执行过程中的异常（包括超时）被捕获并转为错误 JSON 返回给模型，**不中断**生成循环：

```kotlin
// 超时 → 降级为错误 JSON
if (it is TimeoutCancellationException) {
    executedTools += tool.copy(output = /* timeout error JSON */)
    return@onFailure
}
// 取消 → 向上传播（用户主动停止）
if (it is CancellationException) throw it
// 其他异常 → 包装为错误 JSON
executedTools += tool.copy(output = /* error JSON */)
```

#### 4.2.4 OCR 处理 — 非阻断但可能延迟

`OcrTransformer` 对不支持图片输入的模型执行 OCR，设置 `processingStatus` 为"正在识别图片..."。OCR 失败时降级为 `[Image]` 文本，不阻断流程。

#### 4.2.5 文档解析 — 非阻断但可能失败

`DocumentAsPromptTransformer` 解析 PDF/DOCX/PPTX/EPUB 文件。解析失败时注入 `[ERROR, failed to read file: ...]` 文本，不阻断流程。

### 4.3 阻断条件矩阵

| 条件 | 类型 | 阻断点 | 恢复方式 |
|------|------|--------|----------|
| 无可用 CHAT 模型 | 硬阻断 (A) | 发送前静默返回 | 配置 Provider 和模型 |
| 模型 ID 无效 | 硬阻断 (A) | 发送前静默返回 | 修正模型选择 |
| `ask_user` 工具触发 | 流程阻断 (B) | 生成循环中 break | 用户回答问题 |
| MCP 工具需审批 | 流程阻断 (B) | 生成循环中 break | 用户批准/拒绝 |
| 工具执行超时/异常 | 非阻断 | — | 自动降级为错误 JSON |
| OCR 失败 | 非阻断 | — | 自动降级为 `[Image]` |
| 文档解析失败 | 非阻断 | — | 自动降级为错误文本 |
| 模型不支持工具 | 非阻断（降级） | — | 报错但继续生成 |

---

## 5. 对话 Session 交互流程

### 5.1 整体流程图

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                           用户发送消息                                     │
│  ChatVM.handleMessageSend(content, answer)                                │
│    └─ ChatService.sendMessage(conversationId, content, answer)            │
│         ├─ 取消上一个生成 Job（如果有）                                     │
│         ├─ finishInterruptedPendingTools()  ← 清理上次中断的工具            │
│         ├─ preprocessUserInputParts()       ← 执行 USER 范围正则替换         │
│         ├─ 追加 UIMessage(USER) 到 Conversation.messageNodes              │
│         ├─ saveConversation()               ← 内存 + DB 持久化              │
│         └─ if (answer) handleMessageComplete()                            │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       消息补全与生成编排                                    │
│  ChatService.handleMessageComplete()                                      │
│    ├─ 解析 Assistant: settings.getAssistantById(conv.assistantId)         │
│    │    └─ 回退: settings.getCurrentAssistant()                           │
│    ├─ 解析 Model: settings.getChatModel(assistant)                        │
│    │    └─ 硬阻断: null → return（静默退出）                                 │
│    ├─ 校验模型工具能力 (ModelAbility.TOOL)                                  │
│    ├─ checkInvalidMessages()  ← 移除未执行工具的无效消息                     │
│    ├─ 装配 Input/Output Transformers                                       │
│    ├─ 装配 Tools (Search → Local → Conversation → Workspace → Skill → MCP)│
│    └─ GenerationHandler.generateText()                                    │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     生成循环（最多 256 步）                                 │
│  GenerationHandler.generateText()                                         │
│    for (stepIndex in 0 until maxSteps) {                                  │
│      ├─ 构建 Memory Tools（如果 enableMemory）                              │
│      ├─ 检查是否有待恢复的工具（pendingTools）                                │
│      │                                                                     │
│      ├─ [无待恢复工具] generateInternal()                                  │
│      │    ├─ limitContext()               ← 阶梯裁剪（如果启用）             │
│      │    ├─ 构建 System Prompt                                           │
│      │    │    ├─ effectiveSystemPrompt（会话级 or 助手级）                  │
│      │    │    ├─ Memory Prompt（如果 enableMemory）                        │
│      │    │    └─ Tool systemPrompt()                                     │
│      │    ├─ 执行 Input Transformer 管道（7 个顺序执行）                     │
│      │    ├─ 构建 TextGenerationParams                                    │
│      │    │    ├─ temperature / topP / maxTokens                          │
│      │    │    ├─ reasoningLevel                                          │
│      │    │    ├─ tools                                                   │
│      │    │    ├─ customHeaders（助手 + 模型合并）                           │
│      │    │    └─ customBodies（助手 + 模型合并）                            │
│      │    └─ Provider.streamText() / generateText()                      │
│      │         └─ chunk 合并 → onUpdateMessages()                        │
│      │              ├─ transforms()         ← Output Transformer 真实变换  │
│      │              └─ emit(GenerationChunk.Messages)                    │
│      │                   └─ visualTransforms() ← Output Transformer 展示变换│
│      │                                                                     │
│      │    [生成完成]                                                       │
│      │    ├─ onGenerationFinish()         ← Output Transformer 收口处理    │
│      │    ├─ 写入 finishedAt                                              │
│      │    └─ emit(最终消息)                                                │
│      │                                                                     │
│      │    [检查工具调用]                                                    │
│      │    ├─ 无工具 → break（结束生成）                                     │
│      │    ├─ 工具待审批 → 设为 Pending → break（流程阻断，等待用户）          │
│      │    └─ 工具可执行 → 继续执行                                          │
│      │                                                                     │
│      ├─ [有待恢复工具] 直接处理已审批/已回答/已拒绝的工具                      │
│      │                                                                     │
│      └─ 执行工具                                                           │
│           ├─ Approved/Auto → toolDef.execute(args)                       │
│           ├─ Denied → 写入拒绝结果                                          │
│           ├─ Answered → 写入用户答案                                        │
│           ├─ 超时 → 写入 timeout 错误 JSON                                  │
│           └─ 异常 → 写入 error JSON                                        │
│           [工具结果写回同一 ASSISTANT UIMessage，进入下一 step]               │
│    }                                                                       │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       结果收集与后处理                                      │
│  ChatService.collect { chunk ->                                          │
│    ├─ 更新 ConversationSession.state                                     │
│    ├─ AppEventBus.emit(ChatGenerationUpdate)  ← 通知/声音/进度             │
│    └─ 声音反馈（step 完成 / 待审批 / 失败 / 完成）                           │
│  }                                                                        │
│  onCompletion:                                                            │
│    ├─ 兜底更新（finishReasoning / finishedAt）                              │
│    └─ AppEventBus.emit(ChatGenerationEnded)                              │
│  onSuccess:                                                               │
│    ├─ 保存会话                                                             │
│    ├─ 异步生成标题（generateTitle）                                         │
│    └─ 异步生成建议回复（generateSuggestion）                                 │
└──────────────────────────────────────────────────────────────────────────┘
```

### 5.2 关键交互节点

#### 5.2.1 会话初始化

```kotlin
// ChatVM.init
chatService.addConversationReference(conversationId)  // 引用计数 +1
viewModelScope.launch {
    chatService.initializeConversation(conversationId)
}
```

`initializeConversation` 从 DB 加载已有会话或创建新会话（含预设消息）。

#### 5.2.2 用户输入预处理

用户发送的消息在加入会话前，先经过 `preprocessUserInputParts`：

```kotlin
fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
    return parts.map { part ->
        when (part) {
            is UIMessagePart.Text -> part.copy(
                text = part.text.replaceRegexes(assistant, scope = USER, visual = false)
            )
            else -> part
        }
    }
}
```

此处执行 `affectingScope` 包含 `USER` 且 `visualOnly == false` 的正则规则，结果直接持久化。

#### 5.2.3 无效消息清理

每次 `handleMessageComplete` 前执行 `checkInvalidMessages`：

- 移除所有未执行且不可恢复的工具消息
- 保留已批准/已拒绝/已回答的待恢复工具
- 修正 `selectIndex` 越界
- 移除空消息节点

#### 5.2.4 工具审批恢复

用户处理待审批工具后，`ChatService.handleToolApproval` 更新工具状态，并仅在**所有** Pending 工具处理完毕后才恢复生成：

```kotlin
val hasPendingTools = updatedNodes.any { node ->
    node.currentMessage.parts.any { it is UIMessagePart.Tool && it.isPending }
}
if (!hasPendingTools) {
    handleMessageComplete(conversationId)  // 恢复生成
}
```

#### 5.2.5 生成停止

```kotlin
fun stopGeneration(conversationId) {
    job.cancel()
    job.join()
    finishInterruptedPendingTools(conversationId)  // 清理中断的工具
}
```

停止时：
- Pending 工具标记为 `Denied("Generation cancelled by user")`
- 执行中断的工具（output 为空但非 Pending）标记为 `interrupted`

### 5.3 后台任务

生成完成后，异步执行两个后台任务（不阻塞 UI）：

| 任务 | 模型 | 触发条件 |
|------|------|----------|
| 生成标题 | `settings.titleModelId`（回退到 `fastModelId`） | 标题为空或强制生成 |
| 生成建议 | `settings.suggestionModelId`（回退到 `fastModelId`） | `settings.enableSuggestion` 为 true |

---

## 6. LLM 干预与微调机制

助手在一个对话 session 中对 LLM 的干预分为以下维度：

### 6.1 请求构建阶段（发送前）

#### 6.1.1 System Prompt 构建

```kotlin
// GenerationHandler.generateInternal
val system = buildString {
    // 1. 有效系统提示词
    val effectiveSystemPrompt =
        if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
            conversationSystemPrompt  // 对话级覆盖
        } else {
            assistant.systemPrompt   // 助手级
        }
    if (effectiveSystemPrompt.isNotBlank()) append(effectiveSystemPrompt)

    // 2. 记忆列表（如果 enableMemory）
    if (assistant.enableMemory) {
        appendLine()
        append(buildMemoryPrompt(memories))  // JSON 格式的记忆列表
    }

    // 3. 工具 system prompt
    tools.forEach { tool ->
        appendLine()
        append(tool.systemPrompt(model, contextMessages))
    }
}
```

#### 6.1.2 Input Transformer 管道

按固定顺序执行，后一个接收前一个的输出：

| 顺序 | Transformer | 触发条件 | 干预内容 |
|------|-------------|----------|----------|
| 1 | `TimeReminderTransformer` | `assistant.enableTimeReminder == true` | 在跨时段（>1h）的用户消息前注入 `<time_reminder>` |
| 2 | `PromptInjectionTransformer` | `modeInjectionIds` 非空或会话绑定了注入 | 按 5 种位置注入提示词到消息列表 |
| 3 | `PlaceholderTransformer` | 始终执行 | 替换 `{{char}}`、`{{model_name}}`、`{{cur_date}}` 等占位符 |
| 4 | `DocumentAsPromptTransformer` | 消息含 `UIMessagePart.Document` | 将文档内容解析为文本并注入 `<UploadFile>` 标签 |
| 5 | `OcrTransformer` | 模型不支持图片输入 且 消息含本地图片 | 对图片执行 OCR 并替换为 `<image_file_ocr>` 文本 |
| 6 | `TemplateTransformer` | 始终执行 | 用 Pebble 引擎渲染 `messageTemplate`，注入 `{{message}}`、`{{time}}`、`{{date}}` 等 |
| 7 | `WorkspaceReminderTransformer` | `workspaceId` 绑定且 Shell 状态为 READY | 向 System 追加 `<workspace>` 环境提示 |

#### 6.1.3 上下文裁剪

```kotlin
// 消息条数阈值裁剪（仅影响本次请求，不改写会话）
val contextMessages = messages.limitContext(assistant.effectiveContextMessageLimit())
```

- `contextMessageLimit == 0`：不裁剪，发送全部消息
- `contextMessageLimit > 0`：按阶梯策略裁剪，保留约 50% 的阈值量，起点回退到最近的 USER 消息

#### 6.1.4 请求参数构建

```kotlin
val params = TextGenerationParams(
    model = model,
    temperature = assistant.temperature,      // null → Provider 默认
    topP = assistant.topP,                   // null → Provider 默认
    maxTokens = assistant.maxTokens,         // null → Provider 默认
    tools = tools,                           // 装配的所有工具
    reasoningLevel = assistant.reasoningLevel,
    customHeaders = buildList {
        addAll(assistant.customHeaders)      // 助手级 Headers
        addAll(model.customHeaders)          // 模型级 Headers（后加，优先级更高）
    },
    customBody = buildList {
        addAll(assistant.customBodies)       // 助手级 Body
        addAll(model.customBodies)           // 模型级 Body
    }
)
```

#### 6.1.5 工具装配顺序

```kotlin
tools = buildList {
    // 1. 搜索工具
    if (assistant.enableWebSearch) addAll(createSearchTools(settings))

    // 2. 本地工具
    addAll(localTools.getTools(assistant.localTools))

    // 3. 对话引用工具
    if (assistant.enableRecentChatsReference) {
        addAll(createConversationTools(conversationRepo, assistant.id))
    }

    // 4. 工作空间工具
    addAll(createWorkspaceToolsIfReady(assistant.workspaceId, conversation.workspaceCwd))

    // 5. 技能工具
    if (assistant.enabledSkills.isNotEmpty()) {
        addAll(createSkillTools(assistant.enabledSkills, skillManager.listSkills()))
    }

    // 6. MCP 工具
    mcpManager.getAllAvailableTools(assistant).forEach { (serverId, serverName, tool) ->
        add(Tool(name = "mcp__${serverName}__${tool.name}", ...))
    }

    // 7. 记忆工具（在 GenerationHandler 内部添加）
    // if (assistant.enableMemory) addAll(buildMemoryTools(...))
}
```

### 6.2 响应处理阶段（接收后）

#### 6.2.1 Output Transformer 管道

| 时机 | 方法 | Transformer | 干预内容 |
|------|------|-------------|----------|
| 流式 chunk 到达 | `transforms()` | `RegexOutputTransformer` | 对 ASSISTANT 消息执行非 `visualOnly` 的正则替换（作用于 Text 和 Reasoning） |
| 流式 UI 展示 | `visualTransforms()` | `ThinkTagTransformer` | 提取 ` stuff` 转为 `UIMessagePart.Reasoning`，从展示文本中移除 |
| 流式 UI 展示 | `visualTransforms()` | `RegexOutputTransformer` | 同上（复用，不修改持久化） |
| 单步生成完成 | `onGenerationFinish()` | `ThinkTagTransformer` | 最终提取 reasoning，设置 `finishedAt` |
| 单步生成完成 | `onGenerationFinish()` | `Base64ImageToLocalFileTransformer` | 将 base64 图片转为本地文件引用 |
| 单步生成完成 | `onGenerationFinish()` | `RegexOutputTransformer` | 不执行（无 `onGenerationFinish` 覆盖） |

#### 6.2.2 工具输出截断

当工具输出超过 32 KiB 且当前具备 Workspace Shell 时：

```kotlin
// 只保留 4 KiB 预览，完整内容保存到文件
// 结果包含文件引用：/tool_outputs/{executionId}.txt
// executionId 由 messageId + toolOrdinal 构成，不使用 Provider toolCallId
// 指引模型使用 shell 工具读取完整内容
```

无 Shell 访问能力时不截断（避免生成模型无法读取的文件引用）。

### 6.3 用户输入正则替换

在消息发送前（`preprocessUserInputParts`），对 `UIMessagePart.Text` 执行正则替换：

- 作用范围：`affectingScope` 包含 `USER`
- 仅非 `visualOnly` 规则（修改持久化内容）
- 结果直接写入会话历史

### 6.4 助手输出正则替换

在输出 Transformer 管道中（`RegexOutputTransformer`），对 ASSISTANT 消息执行正则替换：

- 作用范围：`affectingScope` 包含 `ASSISTANT`
- `transforms()`：执行非 `visualOnly` 规则（修改持久化内容）
- `visualTransforms()`：同样执行非 `visualOnly` 规则（流式展示期间）
- 作用于 `UIMessagePart.Text` 和 `UIMessagePart.Reasoning`

### 6.5 记忆系统干预

#### 注入点

**System Prompt 中**（构建阶段）：

```text
**Memories**
These are memories stored via the memory_tool that you can reference in future conversations.
[{"id":1,"content":"User prefers Chinese replies"},{"id":2,"content":"..."}]
```

**工具**（`memory_tool`）：

- `create`：创建新记忆
- `edit`：更新已有记忆
- `delete`：删除记忆

记忆隔离：
- `useGlobalMemory == true`：使用 `MemoryRepository.GLOBAL_MEMORY_ID`
- `useGlobalMemory == false`：使用 `assistant.id.toString()` 作为隔离键

### 6.6 提示词注入机制

`PromptInjectionTransformer` 根据助手配置或会话配置注入提示词：

```kotlin
// 有效注入 ID 的判定
val effectiveModeInjectionIds = if (assistant.allowConversationPromptInjection) {
    conversationModeInjectionIds  // 会话级
} else {
    assistant.modeInjectionIds    // 助手级
}
```

注入位置处理：

| 位置 | 处理方式 |
|------|----------|
| `BEFORE_SYSTEM_PROMPT` | 插入到 System 消息文本前面 |
| `AFTER_SYSTEM_PROMPT` | 追加到 System 消息文本后面 |
| `TOP_OF_CHAT` | 在第一条 USER 消息前插入新消息 |
| `BOTTOM_OF_CHAT` | 在最后一条消息前插入新消息 |
| `AT_DEPTH` | 在指定深度位置插入（从最新往前数） |

插入安全机制：`findSafeInsertIndex` 避免在 USER 消息和其 ASSISTANT+Tool 回复之间插入。

### 6.7 流式 vs 非流式

```kotlin
if (assistant.streamOutput) {
    providerImpl.streamText(providerSetting, messages, params).collect { chunk ->
        messages = messages.handleMessageChunk(chunk, model)
        onUpdateMessages(messages)  // 实时更新
    }
} else {
    val chunk = providerImpl.generateText(providerSetting, messages, params)
    messages = messages.handleMessageChunk(chunk, model)
    onUpdateMessages(messages)  // 一次性更新
}
```

### 6.8 对话级覆盖

助手可以允许对话级别的配置覆盖：

| 助手配置 | 对话级字段 | 覆盖条件 |
|----------|-----------|----------|
| `allowConversationSystemPrompt` | `Conversation.customSystemPrompt` | 为 true 且 customSystemPrompt 非空时覆盖 systemPrompt |
| `allowConversationPromptInjection` | `Conversation.modeInjectionIds` | 为 true 时使用会话级注入 ID 替代助手级 |

---

## 7. 配置项与代码文件映射

### 7.1 配置定义位置

| 配置分组 | 定义文件 |
|----------|----------|
| Assistant 主体 | `app/.../data/model/Assistant.kt` |
| AssistantRegex | `app/.../data/model/Assistant.kt` |
| PromptInjection | `app/.../data/model/Assistant.kt` |
| InjectionPosition | `app/.../data/model/Assistant.kt` |
| ReasoningLevel | `ai/.../core/Reasoning.kt` |
| CustomHeader / CustomBody | `ai/.../provider/Provider.kt` |
| LocalToolOption | `app/.../data/ai/tools/local/LocalToolOption.kt` |
| DEFAULT_SYSTEM_PROMPT | `app/.../data/model/Assistant.kt` |
| 上下文限制常量 | `app/.../data/model/Assistant.kt` |

### 7.2 配置消费位置

| 配置 | 消费文件 | 消费方法/位置 |
|------|----------|---------------|
| `chatModelId` | `PreferencesStore.kt` | `getChatModel()` |
| `systemPrompt` | `GenerationHandler.kt` | `generateInternal()` 构建 System |
| `temperature` / `topP` / `maxTokens` | `GenerationHandler.kt` | `TextGenerationParams` 构建 |
| `reasoningLevel` | `GenerationHandler.kt` | `TextGenerationParams` 构建 |
| `streamOutput` | `GenerationHandler.kt` | `streamText()` vs `generateText()` |
| `contextMessageLimit` | `GenerationHandler.kt` | `limitContext()` |
| `enableMemory` / `useGlobalMemory` | `GenerationHandler.kt` + `ChatService.kt` | Memory Tools 构建 + System 注入 |
| `enableWebSearch` | `ChatService.kt` | `createSearchTools()` |
| `localTools` | `ChatService.kt` | `localTools.getTools()` |
| `mcpServers` | `ChatService.kt` | `mcpManager.getAllAvailableTools()` |
| `workspaceId` | `ChatService.kt` + `WorkspaceReminderTransformer.kt` | 工具装配 + System 注入 |
| `enableRecentChatsReference` | `ChatService.kt` | `createConversationTools()` |
| `enabledSkills` | `ChatService.kt` | `createSkillTools()` |
| `regexes` | `ChatService.kt` + `RegexOutputTransformer.kt` | `preprocessUserInputParts()` + `visualTransform()` |
| `messageTemplate` | `TemplateTransformer.kt` | Pebble 模板渲染 |
| `presetMessages` | `ChatService.kt` | `initializeConversation()` |
| `modeInjectionIds` | `PromptInjectionTransformer.kt` | `collectInjections()` |
| `enableTimeReminder` | `TimeReminderTransformer.kt` | `applyTimeReminder()` |
| `customHeaders` / `customBodies` | `GenerationHandler.kt` | `TextGenerationParams` 构建 |
| `allowConversationSystemPrompt` | `GenerationHandler.kt` | effectiveSystemPrompt 判定 |
| `allowConversationPromptInjection` | `PromptInjectionTransformer.kt` | effectiveModeInjectionIds 判定 |
| `quickMessageIds` | `PreferencesStore.kt` | `getQuickMessagesOfAssistant()` |
| `name` / `avatar` / `useAssistantAvatar` | UI 层各组件 | 展示层 |
| `background` / `backgroundOpacity` / `useGradientBackground` | `Background.kt` | 聊天页背景渲染 |

### 7.3 Transformer 管道定义

| 管道 | 定义文件 | 顺序 |
|------|----------|------|
| Input Transformers | `ChatService.kt` | `TimeReminderTransformer` → `PromptInjectionTransformer` → `PlaceholderTransformer` → `DocumentAsPromptTransformer` → `OcrTransformer` → `TemplateTransformer` → `WorkspaceReminderTransformer` |
| Output Transformers | `ChatService.kt` | `ThinkTagTransformer` → `Base64ImageToLocalFileTransformer` → `RegexOutputTransformer` |
| Transformer 接口 | `transformers/Transformer.kt` | `MessageTransformer` / `InputMessageTransformer` / `OutputMessageTransformer` |

### 7.4 工具构建函数

| 工具集 | 构建函数 | 文件 |
|--------|----------|------|
| 搜索工具 | `createSearchTools(settings)` | `tools/SearchTools.kt` |
| 本地工具 | `LocalTools.getTools(options)` | `tools/local/LocalTools.kt` |
| 记忆工具 | `buildMemoryTools(json, ...)` | `tools/MemoryTools.kt` |
| 对话引用工具 | `createConversationTools(repo, assistantId)` | `tools/ConversationTools.kt` |
| 工作空间工具 | `createWorkspaceTools(workspaceId, repo, cwd)` | `tools/WorkspaceTools.kt` |
| 技能工具 | `createSkillTools(enabledSkills, allSkills)` | `tools/SkillsTools.kt` |
| MCP 工具 | `mcpManager.getAllAvailableTools(assistant)` | `mcp/McpManager.kt` |
| 时间信息工具 | `buildTimeInfoTool()` | `tools/local/TimeInfoTool.kt` |
| TTS 工具 | `buildTextToSpeechTool(...)` | `tools/local/TextToSpeechTool.kt` |
| AskUser 工具 | `buildAskUserTool()` | `tools/local/AskUserTool.kt` |
| 剪贴板工具 | `buildClipboardTool(context)` | `tools/local/ClipboardTool.kt` |
| JavaScript 工具 | `buildJavascriptTool()` | `tools/local/JavascriptTool.kt` |
| 屏幕时间工具 | `buildScreenTimeTool(...)` | `tools/local/ScreenTimeTool.kt` |
| 日历工具 | `buildCalendarQueryTool()` / `buildCalendarCreateTool()` | `tools/local/CalendarTool.kt` |

---

## 附录 A：内置占位符

`PlaceholderTransformer` 替换以下占位符（不区分大小写，支持 `{{key}}` 和 `{key}` 两种语法）：

| 占位符 | 值 | 来源 |
|--------|-----|------|
| `{{char}}` | 助手名称（空时降级为 "assistant"） | `assistant.name` |
| `{{user}}` / `{{nickname}}` | 用户昵称（空时降级为 "user"） | `settings.displaySetting.userNickname` |
| `{{model_name}}` | 模型显示名 | `model.displayName` |
| `{{model_id}}` | 模型 ID | `model.modelId` |
| `{{cur_date}}` | 当前日期（中等格式） | `LocalDate.now()` |
| `{{locale}}` | 系统语言显示名 | `Locale.getDefault().displayName` |
| `{{timezone}}` | 系统时区显示名 | `TimeZone.getDefault().displayName` |
| `{{system_version}}` | Android 版本 | `"Android SDK v${SDK_INT} (${RELEASE})"` |
| `{{device_info}}` | 设备信息 | `"${Build.BRAND} ${Build.MODEL}"` |

向后兼容降级：`{{cur_time}}` 和 `{{cur_datetime}}` 已移除，降级为 `{{cur_date}}` 的值。

## 附录 B：Pebble 模板变量

`TemplateTransformer` 在 `messageTemplate` 中可用以下变量：

| 变量 | 类型 | 说明 |
|------|------|------|
| `message` | String | 消息文本内容 |
| `role` | String | 消息角色（"user" / "assistant" / "system"） |
| `time` | LocalTime | 消息创建时间（非当前时间，保证多次请求稳定） |
| `date` | LocalDate | 消息创建日期 |

默认模板 `"{{ message }}"` 直接输出原始消息文本。

## 附录 C：上下文限制常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `MIN_CONTEXT_MESSAGE_LIMIT` | 40 | 最小消息条数阈值 |
| `DEFAULT_CONTEXT_MESSAGE_LIMIT` | 80 | UI 默认值 |
| `MAX_CONTEXT_MESSAGE_LIMIT` | 512 | 最大消息条数阈值 |
| `CONTEXT_KEEP_RATIO` | 0.5 | 裁剪后保留比例（在 `Message.kt` 中定义） |
| `MAX_TOOL_OUTPUT_CHARS` | 32 KiB | 工具输出截断阈值 |
| `TOOL_OUTPUT_PREVIEW_CHARS` | 4 KiB | 截断后保留的预览长度 |
| `MESSAGE_NODE_WARNING_THRESHOLD` | 768 | 消息节点数量警告阈值 |
| `LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD` | 300,000 | 上次输入 token 警告阈值 |
