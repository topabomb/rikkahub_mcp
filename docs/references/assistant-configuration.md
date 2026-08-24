# 助手配置参考

本文说明 `Assistant` 的持久化字段、默认值、解析规则和配置消费边界。完整生成过程见
[消息生成链路](chat-generation-pipeline.md)，模型可见提示与工具契约见
[提示词、上下文注入与工具描述](prompts-and-tools.md)，子助手运行语义见
[子助手架构与执行流程参考](sub-assistant-architecture.md)。

## 1. 数据归属与解析

`Assistant` 定义在
`app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt`，作为
`Settings.assistants` 的元素由 `PreferencesStore` 写入 DataStore。

- `Settings.assistantId` 只是全局当前选择。
- 已创建会话以 `Conversation.assistantId` 为助手归属权威来源。
- `Settings.getConversationAssistant()` 优先解析会话引用；只有引用的助手已被删除时才回退到当前助手。
- `Settings.getChatModel(assistant)` 优先使用 `assistant.chatModelId`，为空时回退到全局 `Settings.chatModelId`；只在已启用 Provider 的 Chat 模型中解析。
- 会话迁移到另一个助手必须显式更新 `Conversation.assistantId`，不能仅切换全局助手。

模型缺失或 Provider 未启用会使生成前的 readiness 检查失败；工具、记忆、工作区或 MCP 未配置通常只会使对应能力不进入本次请求。

## 2. 字段语义

### 身份与显示

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `id` | 随机 `Uuid` | 持久化身份；引用、权限和数据隔离都依赖它 |
| `name` | `""` | UI 名称和 `{{char}}` 占位符来源 |
| `description` | `""` | 子助手 Catalog 的路由描述和 `{{description}}` 来源，不是 System Prompt |
| `avatar` | `Avatar.Dummy` | 助手头像配置 |
| `useAssistantAvatar` | `false` | 聊天消息是否优先显示助手头像 |
| `tags` | 空列表 | 助手分组标签 |
| `background` | `null` | 聊天背景 URI 或 URL |
| `backgroundOpacity` | `1.0f` | 背景不透明度 |
| `useGradientBackground` | `false` | 是否使用动态渐变背景 |

开启背景图或渐变后，聊天页 chrome 卡片（思考过程、气泡、子助手卡等）会按 `ChatSurfacePolicy` 略微透明，让背景透出；代码、表格、公式和媒体产物保持不透明。细节见 [界面架构](ui-architecture.md)。

### 模型与生成参数

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `chatModelId` | `null` | 显式模型；为空时继承全局 Chat 模型 |
| `temperature` | `null` | 为空时不覆盖 Provider 默认值；部分推理模式会主动省略 |
| `topP` | `null` | 为空时不覆盖 Provider 默认值 |
| `maxTokens` | `null` | 输出 token 上限，不是输入上下文窗口 |
| `reasoningLevel` | `AUTO` | `OFF`、`AUTO`、`LOW`、`MEDIUM`、`HIGH`、`XHIGH`、`MAX`；实际线协议映射由 Provider 决定 |
| `streamOutput` | `true` | 选择流式或一次性生成入口 |
| `contextMessageLimit` | `0` | 消息数阶梯裁剪阈值；`0` 禁用，正值归一化到 `MIN_CONTEXT_MESSAGE_LIMIT..MAX_CONTEXT_MESSAGE_LIMIT` |

`ReasoningLevel` 同时携带通用 `budgetTokens` 和 `effort`，但这不是所有 Provider 的直接线格式。
协议实现必须按模型能力和端点转换，详见 [协议层参考](protocol-reference.md)。

### 提示词与上下文

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `systemPrompt` | `""` | 助手系统提示；UI 新建助手和内置默认助手会显式使用 `DEFAULT_SYSTEM_PROMPT` |
| `messageTemplate` | `"{{ message }}"` | Pebble 消息模板 |
| `presetMessages` | 空列表 | 新会话的预置消息 |
| `modeInjectionIds` | 空集合 | 关联的 `PromptInjection` |
| `enableTimeReminder` | `false` | 是否注入跨时段提醒 |
| `allowConversationSystemPrompt` | `false` | 是否允许会话覆盖 System Prompt |
| `allowConversationPromptInjection` | `false` | 是否允许会话追加注入项 |

`systemPrompt` 的占位符、`messageTemplate` 变量、注入位置及最终请求顺序统一由
[提示词、上下文注入与工具描述](prompts-and-tools.md) 维护。

### 记忆与历史引用

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `enableMemory` | `true` | 注入当前记忆并装配 `memory_tool` |
| `useGlobalMemory` | `false` | 使用全局记忆；否则按助手 ID 隔离 |
| `enableRecentChatsReference` | `false` | 装配 `recent_chats` 和 `conversation_search` |

关闭记忆不会删除已有数据。`useGlobalMemory` 只改变读取和写入的命名空间。

### 工具与扩展

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `localTools` | `DEFAULT_ASSISTANT_LOCAL_TOOLS` | 内置本地工具选项；当前默认是 `TimeInfo`、`Tts`、`AskUser`。`TextToImage` 不在默认集中 |
| `enableWebSearch` | `false` | 装配外挂搜索工具；若当前模型已带 `BuiltInTools.Search` 则不再装配 |
| `mcpServers` | 空集合 | 允许该助手使用的 MCP Server ID |
| `workspaceId` | `null` | 绑定工作区；仅工作区 shell ready 时装配工具并注入提醒 |
| `enabledSkills` | 空集合 | 允许 `use_skill` 访问的技能名 |
| `quickMessageIds` | 空集合 | UI 快捷消息引用 |

主会话的工具装配顺序是：搜索、本地工具、历史引用、Workspace、Skill、Assistant Tools、MCP。
`GenerationHandler` 在每个工具循环 step 再按当前记忆状态加入记忆工具。Target Run 会进一步过滤子助手管理/委托工具，并在 step 边界重验权限。
`generate_image` 在 Assistant 已开启 `TextToImage`、且默认文生图模型当前有效时注册；Master 与 Target Run 同一规则。

### 文本变换与请求覆盖

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `regexes` | 空列表 | 用户/助手文本正则替换规则，按列表顺序应用，可在提示词页拖动排序 |
| `customHeaders` | 空列表 | 合并到 Provider HTTP 请求头 |
| `customBodies` | 空列表 | 合并到 Provider 请求体 |

`AssistantRegex` 的 `affectingScope` 选择 USER 或 ASSISTANT，`visualOnly` 区分只用于 UI 的
`visualTransform` 与真正进入消息/持久化流程的替换。非法正则或非法替换引用会保留原文本，避免破坏生成。

自定义 Header/Body 是高级覆盖入口。它们可以改变 Provider 请求语义，配置 UI 和导入逻辑不应把它们误当成普通提示字段。

### 子助手访问字段

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `allowAsSubAssistant` | `false` | 是否属于可被调用的 Target 类别 |
| `isSubAssistantGloballyVisible` | `false` | 是否对所有启用 Assistant Tools 的 Caller 可见 |
| `allowedSubAssistantIds` | 空集合 | Caller 显式允许访问的 Target ID |

有效访问必须同时满足：

```text
target.allowAsSubAssistant
&& target.id != caller.id
&& (target.id in caller.allowedSubAssistantIds
    || target.isSubAssistantGloballyVisible)
```

`description` 在开启 `allowAsSubAssistant` 时必须非空。持久化归一化会折叠空白并按 Unicode
code point 限制长度。关闭 `allowAsSubAssistant` 时，`normalizeForPersistence()` 只强制关闭全局可见；
从所有 Caller 的 `allowedSubAssistantIds` 移除该 ID 是编辑/管理服务在同一次 `updateAtomic` transform
中显式完成的跨记录操作，不能误认为归一化会自动清理授权。

## 3. 关联类型

### `LocalToolOption`

| 选项 | 实际注册工具 |
|------|--------------|
| `TimeInfo` | `get_time_info` |
| `Tts` | `text_to_speech` |
| `AskUser` | `ask_user` |
| `JavascriptEngine` | `eval_javascript` |
| `Clipboard` | `clipboard_tool` |
| `ScreenTime` | `get_screen_time` |
| `Calendar` | `calendar_query`、`calendar_create` |
| `TextToImage` | `generate_image`（默认图片模型有效时；Master 与 Target 均可） |

工具是否需要审批由具体 `Tool.needsApproval` 决定，而不是由枚举统一决定。`generate_image` 仅在 `set_as_background=true` 时审批。Target 非交互下该审批仍返回 `tool_not_permitted`。

### `PromptInjection`

`PromptInjection` 是密封类，当前持久化实现为嵌套的
`PromptInjection.ModeInjection`。公共字段包括 `id`、`name`、`enabled`、`priority`、
`position`、`content`、`injectDepth` 和 `role`。

`InjectionPosition` 的语义：

| 值 | 插入位置 |
|----|----------|
| `BEFORE_SYSTEM_PROMPT` | System Prompt 之前 |
| `AFTER_SYSTEM_PROMPT` | System Prompt 之后 |
| `TOP_OF_CHAT` | 聊天消息顶部 |
| `BOTTOM_OF_CHAT` | 最新输入之前 |
| `AT_DEPTH` | 从最新消息向前按 `injectDepth` 定位 |

同位置注入按 `priority` 排序；已禁用或未被助手/会话选中的项不进入请求。

## 4. 默认助手与工具创建助手

`Assistant()` 的数据类默认值用于反序列化兼容和临时占位，不等同于完整的新建 UI 模板。

- 内置默认助手和 UI 新建助手显式填入 `DEFAULT_SYSTEM_PROMPT`。
- 普通新建助手与 `assistant_manage(CREATE)` 创建的 Target 共用
  `DEFAULT_ASSISTANT_LOCAL_TOOLS`，避免两条创建路径能力不一致。
- 工具创建的 Target 显式开启 `allowAsSubAssistant`，但保持全局可见、搜索、历史引用、MCP、
  Workspace、Skill、模式注入和会话覆盖关闭。
- 工具创建的 Target 默认不绑定模型。执行时可继承 Caller 的有效模型参数，但不把继承值写回配置。
- Target 的身份、System Prompt、工具、记忆和权限始终独立于 Caller。

默认值修改必须同步检查 `DEFAULT_ASSISTANTS`、`AssistantPage` 的 UI 新建入口、
`buildToolCreatedAssistant()`、序列化兼容测试和本文档。

## 5. 更新与持久化边界

`SettingsStore.updateAtomic()` 在互斥区内读取最新非 dummy Settings、执行 transform、写策略、
持久化归一化与 DataStore 标量规范化，再写入 DataStore；写入成功后经 `materializeForRead()` 发布
`settingsFlow`。跨助手的权限清理、选择项修正和删除 tombstone 必须在同一个 transform 中完成，
不能先发布内存状态再补写磁盘。

`Settings.normalizeForPersistence()` 在每次写入前运行，只负责规范化
`Assistant.description`、在未开启子助手类别时强制关闭全局可见、按 `assistantId` 去重
`pendingAssistantDeletions`。失效的 MCP / 注入 / 快捷消息引用、重复 id、内置 Provider 补齐由
`materializeForRead()` 负责，不在 `normalizeForPersistence` 里。

跨助手的权限清理和删除 tombstone 仍必须放在同一次 `updateAtomic` transform 中，不能先发布内存状态再补写磁盘。市场导入若只调用 `normalizeForPersistence`，不会自动丢掉失效引用。

删除 Assistant 由 `AssistantManagementService` 协调：先写 tombstone，再取消相关生成和子助手运行，
清理记忆与会话，最后提交 Settings 清理。中断后由 tombstone 恢复流程继续完成，不能把列表移除视为删除完成。

## 6. 配置消费位置

| 责任 | 主要实现 |
|------|----------|
| 数据模型、正则、上下文阈值、默认提示 | `data/model/Assistant.kt` |
| Settings 持久化与解析 | `data/datastore/PreferencesStore.kt` |
| 读取物化、写策略与提交顺序 | `SettingsNormalization.kt`、`SettingsWritePolicy.kt`、`SettingsCommitCoordinator.kt` |
| UI 新建与编辑 | `ui/pages/assistant/` |
| 会话助手归属与迁移 | `ConversationApplicationService.moveToAssistant()`、`UpdateHeader` |
| 模型 readiness 与主生成工具装配 | `MasterTurnCoordinator`、`GenerationToolSetFactory` |
| 请求构建、工具循环与 Transformer | `GenerationHandler` |
| 工具创建/修改/删除助手 | `AssistantManagementService`、`AssistantToolFactory` |
| Target 默认模板和运行过滤 | `SubAssistantRunPolicy` |
| 子助手执行与恢复 | `DelegationCoordinator`、`TurnRecovery`、`ApplicationRecoveryCoordinator` |
| Provider 参数映射 | `ai/.../provider/providers/` |

维护配置时应从“持久化默认值 → UI/工具创建入口 → 解析与归一化 → 请求消费 → 测试/文档”完整检查，
避免只修改数据类或单一页面。
