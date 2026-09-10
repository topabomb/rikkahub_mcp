# 助手配置参考

本文说明 Assistant 的持久化字段、默认值、解析规则和配置消费边界。内部受管 overlay 与本地 shadow 的关系统一见 [Android 配置架构](android-configuration-architecture.md)；生成协议见 [Turn/Step 执行](turn-step-execution.md)，子助手运行见 [子助手架构](sub-assistant-architecture.md)。

## 1. 数据归属与解析

`Assistant` 定义在
`app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt`，作为
`Settings.assistants` 的本地 shadow 由 `SettingsStore` 写入既有 DataStore，并经同一 Store 的有效读模型提供给应用。

- Assistant 身份及模型、MCP、子助手等配置引用使用 `ConfigurationReference`；个人资源序列化保持原 UUID，企业资源保留 authority 与原始资源 ID。用户定义中的引用只能指向 User，企业域使用选择由独立偏好表达。
- `Settings.assistantId` 只是全局当前选择。
- 已创建会话以 `Conversation.assistantId` 为助手归属权威来源。
- 聊天由 `ConversationQueryService` 按原域与 `header.assistantId` 解析。定义删除或撤权保留历史与不可用原因，不回退到当前全局助手；聊天使用选择通过 `AssistantPreferenceChange` 修改指定字段。
- `Settings.getChatModel(assistant)` 优先使用 `assistant.chatModelId`，为空时回退到全局 `Settings.chatModelId`；只在已启用 Provider 的 Chat 模型中解析。
- 会话迁移到另一个助手必须显式更新 `Conversation.assistantId`，不能仅切换全局助手。

模型缺失或 Provider 未启用会使生成前的 readiness 检查失败；工具、记忆、工作区或 MCP 未配置通常只会使对应能力不进入本次请求。

## 2. 字段语义

### 身份与显示

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `id` | `ConfigurationReference.User`（随机 UUID） | 持久化身份；引用、权限和数据隔离都依赖它 |
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
| `contextMessageLimit` | `0` | 消息数阶梯裁剪阈值；`0` 禁用，正值归一化到 `MIN_CONTEXT_MESSAGE_LIMIT..MAX_CONTEXT_MESSAGE_LIMIT`。不是 token/window 上限。台阶算法与请求级叠加见 [请求上下文](request-context.md) |

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

当前 `memory_tool` 对 `MemoryEntity` 执行 create/edit/delete；`MemoryEntity` 只有自增 `id`、String `assistantId` 和 `content`，
没有 realm、deployment、managedGeneration 或 provenance；它是可变本地记忆，不承担受管 Memory Seed。

### 工具与扩展

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `localTools` | `DEFAULT_ASSISTANT_LOCAL_TOOLS` | 内置本地工具选项；当前默认是 `TimeInfo`、`Tts`、`AskUser`。`TextToImage` 不在默认集中 |
| `enableWebSearch` | `false` | 装配外挂搜索工具；若当前模型已带 `BuiltInTools.Search` 则不再装配 |
| `builtInSearch` | `null` | `null` 继承模型定义的 Search；`true`/`false` 仅覆盖本助手请求的内建搜索，不改共享模型 |
| `mcpServers` | 空集合 | 允许该助手使用的 MCP Server ID；选择不等于运行时已发现工具 |
| `workspaceId` | `null` | 绑定工作区；仅工作区 shell ready 时装配工具并注入提醒 |
| `enabledSkills` | 空集合 | 允许 `use_skill` 访问的技能名 |
| `quickMessageIds` | 空集合 | UI 快捷消息引用 |

主会话的工具装配顺序是：搜索、本地工具、历史引用、Workspace、Skill、Assistant Tools、MCP。
`Model.withAssistantSearch` 统一派生搜索工具；其他模型工具保持原值。聊天搜索模式同时更新助手的
`enableWebSearch` 和 `builtInSearch`：关闭为 false/false、外挂为 true/false、内建为 false/true。
子助手借用 Caller 模型时继承模型身份及模型执行参数，搜索仍按原始模型和 Target 自己的偏好解析。
内建搜索仅支持 Google 与 OpenAI Responses 传输；界面按实际模型覆盖连接判断，不支持时显示不可用提示，
`ModelExecutionService` 在发请求前拒绝，保留原选择供用户调整。

旧 Assistant JSON 缺少 `builtInSearch` 时读取为 null，原 `Model.tools` 原样保留；备份恢复采用相同规则。
企业使用偏好的同名字段缺失表示继承用户助手定义，`UsageValue(null)` 表示改为继承本域所选模型。
显式 true/false 仅影响该主体的助手使用设置。该字段为可选扩展，不改变 Room 或接入资料格式版本。

MCP 还必须与 definition 匹配的完整非空 LKG Catalog 和用户工具策略求交，连接健康不参与 schema 注入；Master/Target 在 run 开始时冻结
`TurnMcpCapabilitySnapshot`，同一 run 不跟随远端目录通知漂移。
Memory Tools 与其他工具一样在新 Turn START 前按固定 namespace 装配并冻结 definitions/bindings；同一 Turn 不按 step 重建。Target Run 进一步过滤子助手管理/委托工具；执行时仍重验权限、资源与 Memory namespace，撤销后 live fail-closed。
`generate_image` 在 Assistant 已开启 `TextToImage`、且默认文生图模型当前有效时注册；Master 与 Target Run 同一规则。

当前 Quick Message 点击调用 `ChatInputState.appendText(quickMessage.content)`，只向输入草稿追加内容，不触发发送或工具执行。

### 文本变换与请求覆盖

| 字段 | 默认值 | 语义 |
|------|--------|------|
| `regexes` | 空列表 | 用户/助手文本正则替换规则，按列表顺序应用，可在提示词页拖动排序 |
| `customHeaders` | 空列表 | 合并到 Provider HTTP 请求头 |
| `customBodies` | 空列表 | 合并到 Provider 请求体（非结构高级参数覆盖入口） |

`AssistantRegex` 的 `affectingScope` 选择 USER 或 ASSISTANT，`visualOnly` 区分只用于 UI 的
`visualTransform` 与真正进入消息/持久化流程的替换。非法正则或非法替换引用会保留原文本，避免破坏生成。

自定义 Header/Body 是高级覆盖入口。`customBody` 只允许扩展或覆盖非结构字段；Provider builder 拥有的身份、
消息、工具、续轮和传输生命周期字段是保留字段，发生冲突时在 HTTP 前抛出 typed 本地错误
（`CustomBodyReservedKeyException`，stable reason `custom_body_reserved_key`）。已有冲突配置仍可加载和编辑，
但请求会 fail-fast。由于 Assistant 级配置会随默认模型或 Provider override 切换协议，配置 UI 不使用跨协议
保留键并集；当前协议 request builder 是唯一权威校验 owner。不自动删除、改名或迁移用户的 custom body。
详见 [协议层参考](protocol-reference.md)。

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
从所有 Caller 的 `allowedSubAssistantIds` 移除该 ID 是编辑/管理服务在同一次 `updateLocal` transform
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
| `AssistantManagement` | `assistant_manage`、`assistant_inspect` |
| `AssistantDelegation` | `assistant_call` |
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

用户 Assistant 定义保存在 UserSettingsDocument.configuration；企业定义归 Applied Enterprise State。企业域的用户使用选择保存在同一用户文档的 scoped preferences，不复制整份 Assistant，也不修改企业固定定义。

`SettingsStore.updateLocal()` 持写锁读取最新个人投影，执行 transform、持久化规范化与 DataStore 提交，回执成功后发布 `userSettings`。企业目录和执行使用 `ConfigurationResolver` 的按域结果；共享定义编辑器明确编辑用户定义，不将个人读取投影视作企业授权。跨助手权限清理、选择修正和删除 tombstone 必须在同一事务完成。

`Settings.normalizeForPersistence()` 在每次写入前运行，只负责规范化
`Assistant.description`、在未开启子助手类别时强制关闭全局可见、按 `assistantId` 去重
`pendingAssistantDeletions`。失效的 MCP / 注入 / 快捷消息引用、重复 id、内置 Provider 补齐由
`materializeForRead()` 负责，不在 `normalizeForPersistence` 里。

管理服务的 CRUD（`assistant_manage` 与 UI 删除）通过 `SettingsStore.manageAssistant` 的 typed mutation 一次提交用户定义、按主体使用授权与删除 tombstone；`ArtifactSettingsCoordinator` 接入既有 `ArtifactStore.commitSettingsRoots`，保持 Settings → Artifact 锁序。用户助手集合与读取共用内置定义补齐规则，避免目录可见但命令无法找到。回执只在 DataStore 确认后返回，取消前未接收的写入拒绝，已接收写入等待确认后传播取消。市场导入若只调用 `normalizeForPersistence`，不会自动丢掉失效引用。

`assistant_manage` 捕获原 `AssistantManagementCaller`（助手引用与 RealmAccess），Factory 不读取个人 Settings 做第二次准入。管理服务在原 Session 内用最新配置解析调用者、管理工具开关及主从授权；企业域还要求 allowLocalAssistants。CREATE 保存一份共享用户子助手定义，并在同次提交给调用者的当前企业主体追加 additionalSubAssistantIds；个人域仍更新用户定义的允许列表。UPDATE/DELETE 只接受获准用户定义，企业定义只读；工具说明明确共享影响。切换到个人不改变在途调用的原域，退出重入不能给旧 Session 补发权限，提交前复验期限。

删除同次移除各域该助手 usage 及额外授权，保留企业已选引用的失效状态，不静默替换；个人当前选择按原规则修正。该配置提交不删除企业聊天或运行记忆。

删除 Assistant 由 `AssistantManagementService` 协调：先写 tombstone，再取消相关生成和子助手运行，
清理记忆与会话，最后提交 Settings 清理。中断后由 tombstone 恢复流程继续完成，不能把列表移除视为删除完成。

## 6. 配置消费边界

`Assistant` 数据模型定义持久字段与默认语义；用户配置的读取物化和提交归 Settings owner；企业状态归 Enterprise owner；按域生效解析归 ConfigurationResolver。会话助手归属只经 `ConversationApplicationService` 迁移；模型 readiness 与主生成工具装配归
`ConversationTurnService` 和 `TurnToolSetFactory`；请求映射、工具循环与 Transformer 归 `TurnRunner` / `StepRunner` / `ToolBatchRunner`；助手的
创建、修改和删除归 `AssistantManagementService`。子助手的运行过滤、执行与恢复分别归 `SubAssistantRunPolicy`、
`SubAssistantRunCoordinator`、`TurnRecovery` 和 `ApplicationRecoveryCoordinator`。

UI 和 Provider adapter 只消费 typed 配置与有效读模型，不成为配置写入 owner。

维护配置时应从“持久化默认值 → UI/工具创建入口 → 解析与归一化 → 请求消费 → 测试/文档”完整检查，
避免只修改数据类或单一页面。
`contextMessageLimit` 编辑器使用独立草稿状态：合法值为 `0` 或 `40..512`，仅在 IME Done 或失焦时提交；关闭写 `0`，重新启用写默认值 `80`。开关提交会抑制随后一次失焦对旧草稿的重复写入，Done 与失焦也不会重复提交同一值。编辑中收到外部设置更新时保留当前输入缓冲，但更新去重基线；未聚焦时同步 durable 值。最终写入仍走 Assistant 配置既有 typed 更新链。

## 7. 本域使用编辑

企业聊天的助手管理与记忆入口打开 `AssistantUsageEditor`，复用基本参数、提示词使用、请求、记忆、本地工具、MCP、QuickMessage、Injection 与 Skill 内容。名称、描述、系统提示词、子助手身份与企业固定模型只读；用户助手可明确进入共享定义编辑，并确认对其他空间的影响。固定子助手引用不可移除，本域额外引用按目录准入选择。重置使用设置恢复定义及默认值，只影响原主体。

页面向 `ConfigurationApplicationService` 提交 `AssistantPreferenceChange`。页面回调通过基线差量保留并发未编辑字段，资产仍经 Artifact 引用事务；不存在整份解析助手回写。搜索模式和资源选择继续使用聊天的现有选择器。预设文本编辑保留其他消息部分，媒体预览按原配置来源读取，不读取个人历史。

## 8. 关键架构文件

| 边界 | 文件 |
| --- | --- |
| Assistant 数据模型 | `app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt` |
| 用户配置写入与域内解析 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsStore.kt`、`data/configuration/ConfigurationResolver.kt` |
| 读取物化、写规则与提交 | `app/src/main/java/net/weero/measix/pilot/data/datastore/SettingsNormalization.kt`、`SettingsWriteRules.kt` |
| 会话归属与生成装配 | `app/src/main/java/net/weero/measix/pilot/service/ConversationApplicationService.kt`、`ConversationTurnService.kt`、`service/turn/TurnRunner.kt`、`service/turn/StepRunner.kt`、`service/turn/ToolBatchRunner.kt`、`service/turn/TurnRunState.kt`、`data/ai/tools/TurnToolSetFactory.kt` |
| 助手管理工具 | `app/src/main/java/net/weero/measix/pilot/service/AssistantManagementService.kt`、`data/ai/tools/AssistantToolFactory.kt` |
| 子助手策略与执行 | `app/src/main/java/net/weero/measix/pilot/data/ai/subassistant/SubAssistantRunPolicy.kt`、`service/subassistant/SubAssistantRunCoordinator.kt` |
