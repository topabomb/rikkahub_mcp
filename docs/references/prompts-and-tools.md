# 提示词、上下文注入与工具描述

> 本文档以当前代码为准，记录模型在一次生成请求里实际看到的系统提示、动态注入、工具
> `description`、参数说明和 Tool Result 形状。文案以英文源串为准。
> 工具名使用 `Tool(name = "...")` 的注册名。

相关实现：`GenerationHandler.generateInternal()`、`PlaceholderTransformer`、
`TemplateTransformer`、`AssistantCatalogBuilder`、`AssistantToolFactory`、
`WorkspaceReminderTransformer`、`TimeReminderTransformer`、`buildMemoryPrompt()`。

---

## 1. 一次请求里的上下文顺序

`generateInternal()` 先拼 System，再跑 Input Transformer：

```text
System
  1. Assistant.systemPrompt
     （allowConversationSystemPrompt 且会话 customSystemPrompt 非空时，用会话提示覆盖）
  2. **Memories** + JSON     ← assistant.enableMemory
  3. 各 Tool.systemPrompt()  ← 按当前 step 的工具列表，空串不产生有效段落

随后 Input Transformer（固定顺序）：
  TimeReminderTransformer
  PromptInjectionTransformer
  PlaceholderTransformer      ← 替换 {{char}} / {{description}} 等
  DocumentAsPromptTransformer
  OcrTransformer
  TemplateTransformer         ← 渲染 messageTemplate
  WorkspaceReminderTransformer
```

工具 schema（name、description、parameters）随 `TextGenerationParams.tools` 另发，与 System 同屏。
同一事实只应出现在一个落点：正在做那个动作的工具句或字段上，不在 Catalog 与工具句之间复读。

---

## 2. 助手系统提示与占位符

新建助手默认模板是 `DEFAULT_SYSTEM_PROMPT`（`Assistant.kt`）：称呼 `{{char}}`、模型
`{{model_name}}`、日期/语言/时区/设备/系统版本/`{{user}}`，以及 Markdown、LaTeX、
`text_to_speech`、记忆的用法提示。默认模板不插入 `{{description}}`，避免空描述留下空行。

### 2.1 `PlaceholderTransformer`

不区分大小写，支持 `{{key}}` 与 `{key}`：

| 占位符 | 值 |
|--------|-----|
| `{{char}}` | `assistant.name`，空则为 `assistant` |
| `{{description}}` | `assistant.description`，空则为空串 |
| `{{user}}` / `{{nickname}}` | 用户昵称，空则为 `user` |
| `{{model_name}}` | `model.displayName` |
| `{{model_id}}` | `model.modelId` |
| `{{cur_date}}` | 本地中等格式日期 |
| `{{locale}}` | 系统语言显示名 |
| `{{timezone}}` | 系统时区显示名 |
| `{{system_version}}` | Android SDK 与发行版 |
| `{{device_info}}` | 品牌与型号 |

已移除的 `{{cur_time}}` / `{{cur_datetime}}` 降级为 `{{cur_date}}` 的值。
提示词页变量芯片来自 `DefaultPlaceholderProvider`。

### 2.2 `TemplateTransformer`（Pebble `messageTemplate`）

| 变量 | 含义 |
|------|------|
| `message` | 当前文本 part |
| `role` | `user` / `assistant` / `system` |
| `time` / `date` | 该条消息的创建时间/日期，不是“现在” |
| `description` | `assistant.description` |

默认模板 `"{{ message }}"` 原样输出文本。

---

## 3. 动态注入

### 3.1 记忆 `buildMemoryPrompt()`

`assistant.enableMemory` 时追加。生成开始时读取的记忆用于本轮提示词，后续 step 不刷新；Memory Tool 执行仍按 owner 约束写库，Target 还会即时重验记忆开关与 namespace。

```text
**Memories**
[{"id":1,"content":"..."}]
```

JSON 由 `JsonInstantPretty` 编码。

### 3.2 子助手 Catalog `buildCatalogPrompt()`

`LocalToolOption.AssistantManagement` 或 `AssistantDelegation` 开启时注入一次：

- 两者都开：由 `assistant_manage.systemPrompt()` 注入，`assistant_call` 返回空串
- 仅管理：`assistant_manage`
- 仅委托：`assistant_call`

三种 `CatalogMode` 前缀相同。列表来自 `SubAssistantAccessPolicy.accessibleSubAssistants()`，
排除 caller，保持 `Settings.assistants` 顺序。`name` / `description` 中的 `<` `>` `&` 编成
JSON Unicode escape。

```text
<sub_assistant_catalog>
Sub-assistants (sub-agents).
{"header":["id","name","description"],"rows":[["uuid","名称","路由描述"]]}
</sub_assistant_catalog>
```

空列表仍是同一 `header` 与 `"rows":[]`。执行期不信任 Catalog，三个 Assistant 工具都会从最新
Settings 重算访问范围。

### 3.3 技能 `use_skill.systemPrompt()`

`enabledSkills` 非空时：

```text
**Skills**
<available_skills>
  <skill>
    <name>...</name>
    <description>...</description>
  </skill>
</available_skills>
```

### 3.4 TTS `text_to_speech.systemPrompt()`

当前选中 TTS Provider 的 `TTSManager.getPromptGuidance()`；无指导时为空串。

### 3.5 工作区 `WorkspaceReminderTransformer`

`workspaceId` 已绑定且 `WorkspaceShellStatus.READY` 时追加到第一条 System。不注入 cwd。

内容由 `buildWorkspacePrompt()` 生成：`<workspace>` 内说明 `/workspace`、路径必须在 Rootfs
内、四个 `workspace_*` 工具的分工、`/skills`、`/upload` 只读。

### 3.6 时间间隔 `TimeReminderTransformer`

`enableTimeReminder` 时，在首条 USER 前以及间隔超过一小时的 USER 前插入：

```text
<time_reminder>Current time: <weekday>, <local datetime></time_reminder>
```

有间隔时附加 `(<gap> since last message)`。

---

## 4. 职责落点（模型同屏时不复读）

| 事实 | 只出现在 |
|------|----------|
| 有哪些子助手 | Catalog JSON |
| 委托、不要指定做法 | `assistant_call` description |
| 对方看不见本对话，简报要写全 | `assistant_call.request` |
| 路由短句不是系统提示 | `assistant_manage.description` 字段 |
| 创建时不要编造工具 | `assistant_manage.instructions` 字段 |
| 只读局部记忆 | `assistant_memory_list` description |
| 工作区路径与挂载 | `<workspace>`；工具侧只在 `path` 参数重复绝对路径规则 |
| 技能清单 | `<available_skills>` |

---

## 5. 工具描述与参数

主会话的基础工具装配顺序见 `ChatService`：搜索、Local Tools、最近会话、Workspace、技能、
Assistant Tools、MCP；记忆工具由 `GenerationHandler` 在每个 step 按当前记忆状态加入。
Target Run 的动态集合由 `GenerationToolSetFactory` 重建，并永久过滤 `assistant_manage`、
`assistant_memory_list`、`assistant_call`，保留并桥接 `ask_user`；其余工具按运行开始快照与当前配置的交集在 step 边界重建。

下列 description 为源码中的英文原文（动态日期/时区用占位标明）。

### `search_web`

启用：`enableWebSearch`。

> Search the web for current or specific facts. Use focused keywords; run multiple searches if needed.
> Today is \<local date\>.
> Cite with `[citation,domain](id)` after the sentence.
> If images help, embed 2–4 from `images[]` at the start of the reply; never invent urls.

参数由当前 SearchService 提供：`query`（Search keywords）；Tavily 另有 `topic`
（general, news, or finance）。

结果：`items[].id`（6 字符）、`index`、`title`、`url`、`text`，以及可选 `answer`、`images[]`。

### `scrape_web`

启用：搜索已开且当前 provider 提供 scraping。

> Scrape a URL when the user wants that page, or when search snippets are not enough.
> Do not use it for common questions unless asked.

参数：`url`（Page URL）。

### `get_time_info`

启用：`LocalToolOption.TimeInfo`。无参数。

> Get the current local date and time from the device.

结果为固定字段 JSON：year/month/day、weekday、weekday_en、weekday_index、date、time、
datetime、timezone、utc_offset、timestamp_ms。

### `text_to_speech`

启用：`LocalToolOption.Tts`。

> Speak text aloud when the user asks you to read something, or when audio is appropriate.
> Returns immediately; playback continues in the background.
> Provide natural speech text without markdown.

参数：`text`（Plain text to speak）。结果：`{"success":true}`。

### `clipboard_tool`

启用：`LocalToolOption.Clipboard`。

> Read or write the device clipboard. Do not write unless the user explicitly asks.

| 参数 | description |
|------|-------------|
| `action` | read or write |
| `text` | Text to write (required for write) |

read：`{"text":"..."}`。write：`{"success":true}`。

### `ask_user`

启用：`LocalToolOption.AskUser`。`needsApproval` 恒为 true；合法调用由 HITL 收答案，`execute` 不直接跑。
参数不合法时在审批门口直接失败：写入
`{"error":"invalid_arguments","field":"...","expected":"..."}`，不 Pending、不自动执行。
`options` 格式错误时额外带 `hint`。Target 上由 Coordinator 桥到主聊天子助手卡片。

> Ask the user one or more questions when you need clarification or confirmation.

| 参数 | description |
|------|-------------|
| `questions` | List of questions to ask the user |
| `questions[].id` | Unique identifier for this question |
| `questions[].question` | The question text to display to the user |
| `questions[].options` | Suggested string choices, not objects. |
| `questions[].selection_type` | Answer type: text (free text input, default), single (...), multi (...) |

### `get_screen_time`

启用：`LocalToolOption.ScreenTime`。

> Get app screen usage over a time range (`begin`/`end`, or `range`: today/week).
> Device timezone: '\<zone\>' (UTC \<offset\>); naive times use this zone.
> Requires Usage access; if missing, settings open and an error is returned.

`begin` / `end` 接受 ISO 日期、本地日期时间、带偏移日期时间或 epoch 毫秒；提供 `begin` 时忽略
`range`。`top` 默认 10。

### `calendar_query`

启用：`LocalToolOption.Calendar`。

> Query device calendar events (`begin`/`end`, or `range`: today/week/month).
> Device timezone: '\<zone\>' (UTC \<offset\>); naive times use this zone.
> Requires Calendar permission; if missing, a request is triggered and an error is returned.

另有 `query`（标题关键字）与 `limit`（默认 20）。

### `calendar_create`

启用：同上。`needsApproval` 恒为 true。

> Create a calendar event (title and start required). End defaults to 1 hour after start, or the next day if all-day.
> Device timezone: '\<zone\>' (UTC \<offset\>).
> Requires Calendar permission; if missing, a request is triggered and an error is returned.

成功结果：`{"success":true,"event_id":N,"start":"...","end":"..."}`。`start`/`end` 是解析后的规范时间。

### `eval_javascript`

启用：`LocalToolOption.JavascriptEngine`。

> Execute JavaScript (QuickJS, ES2020). Result is the last expression.
> Use toFixed() for decimal precision. No DOM or Node.js APIs. Console output is in logs.

参数：`code`。结果：`result`，若有控制台输出则带 `logs`。

### `memory_tool`

启用：`assistant.enableMemory`。由 `GenerationHandler` 按 owner namespace 构建。

> Store long-term notes across conversations (create/edit/delete).
> Merge similar records; prefer edit over create.
> Do not store sensitive personal attributes.
> Do not show memory content unless the user asks.
> Today is \<local date\>.

| 参数 | description |
|------|-------------|
| `action` | create, edit, or delete |
| `id` | Record id (required for edit/delete) |
| `content` | Note text (required for create/edit) |

create：`{"id":N}`。edit / delete：`{"success":true,"id":N}`。
聊天卡片摘要读的是 tool **入参**的 `content`，不是结果。

### `recent_chats`

启用：`enableRecentChatsReference`。

> List recent conversations with this assistant (titles and last-activity dates, pinned first).
> Use `conversation_search` for message content.

`limit` 默认 10、最大 30。结果为 id / title / last_chat 数组。

### `conversation_search`

启用：同上。

> Full-text search in past conversations. Use focused keywords; try several queries if needed.
> Snippets wrap matches in [brackets].

`query` 必填。`limit` 默认 15、最大 50。检索范围与 `recent_chats` 一致，只包含当前 Assistant 的顶层会话，不包含其他 Assistant 或 Child。

### `workspace_read_file`

启用：助手绑定 Workspace 且 Rootfs READY。

> Read a UTF-8 text or image file from the bound workspace Rootfs.

`path`：Absolute path inside Rootfs. Use /workspace for the workspace files area.

文本结果：`{path, text}`。图片：Image part + 路径说明。

### `workspace_write_file`

> Write a UTF-8 text file in the bound workspace Rootfs.

`text`：UTF-8 text content to write。`overwrite` 默认 true。
路径落在可写根之外时强制审批。结果为文件元数据（path / name / sizeBytes / updatedAt），不含正文。

### `workspace_edit_file`

> Edit a UTF-8 text file in the bound workspace Rootfs.
> old_text must occur once unless replace_all=true. If no exact match, whitespace-tolerant matching is tried.

结果：`path`、`replacements`、可选 `matchStrategy`、`sizeBytes`、`updatedAt`。
unified diff 只进 part metadata，不进发给模型的文本。

### `workspace_shell`

> Run a shell command in the bound workspace Rootfs. cwd is relative to the workspace files root.
> Defaults to '\<cwd\>'.   ← 仅当存在默认 cwd 时追加后半句

`timeout` 默认 30 秒。结果：`exitCode`、`stdout`、`stderr`、`timedOut`，截断时带 `truncated`。
默认需要审批。

### `use_skill`

启用：`enabledSkills` 非空。

> Load a skill's instructions when the user's request matches an available skill.

`name`：Skill name from the available list。
`path`：只允许使用 SKILL.md 里 Markdown 链接抽出的相对路径；省略则读默认 SKILL.md。
结果为文件原文，不包 JSON。

### `assistant_manage`

启用：`LocalToolOption.AssistantManagement`。`needsApproval` 恒为 true。

> Create, update, or delete a sub-assistant (sub-agent). New ones join your allowed list.

| 参数 | description |
|------|-------------|
| `action` | CREATE, UPDATE, or DELETE. |
| `assistant_id` | Required for UPDATE and DELETE. |
| `name` | Display name. |
| `description` | Specialty and when to call it. Not a system prompt. |
| `instructions` | System prompt for the sub-assistant: role, method, output style. Do not invent tools or skills. |

成功不回显 `instructions`，只回变更后的 id / name / description。DELETE 可带 `cleanup_pending`。

### `assistant_memory_list`

启用：同上。只读。

> List the local memories of a sub-assistant in the catalog. This is read-only; only the target can change them through its own memory tools. Global memory is never returned.

`assistant_id`：Catalog id。

结果：`assistant`、`active_memory`（`local` / `global` / `disabled`）、`header+rows`。
仅 `local` 时 rows 有内容；caller 自身返回 `target_is_caller`。

### `assistant_call`

启用：`LocalToolOption.AssistantDelegation`。同步：返回前主助手当前 Tool Loop 不继续。

> Delegate a self-contained request to a catalog sub-assistant (sub-agent). Do not prescribe how it must work.

| 参数 | description |
|------|-------------|
| `assistant_id` | Catalog id. |
| `request` | It cannot see this chat. Include facts, constraints, and the expected deliverable. |

完成：`{"status":"completed","assistant_name":"...","content":"..."}`，必要时
`has_non_text_output`。其他终态带稳定 `reason`。

`content` 取本次 run 范围内：优先最后一条 ASSISTANT 在最后一个工作工具之后的顶层 Text；
`text_to_speech` 不算工作工具；最后一步为空则回退更早 step，再回退最后一段 Text island。

### `mcp__<server>__<tool>`

启用：MCP 服务器已连接。名称、description、参数来自 `tools/list`。输入参数以完整 JSON Schema 文档保存，`$schema`、`$defs`、`$ref` 和未知扩展不会在缓存或通用工具层丢失。
超时 120 秒。`TextContent` 进文本；`ImageContent` 先存上传文件再转 Image part。

---

## 6. 工具输出截断

`GenerationHandler.maybeTruncateToolOutput()`：采用 `TRUNCATABLE_TEXT` 的工具在文本总长超过 `MAX_TOOL_OUTPUT_CHARS` 且助手有 Shell 时，全文写入 `/tool_outputs/<executionId>.txt`，只把 `TOOL_OUTPUT_PREVIEW_CHARS` 控制的预览与读取指引回给模型。无 Shell 时不截断。`assistant_call` 使用 `PRESERVE`，其带 `status`、`assistant_name`、`content` 和 `reason` 的结构化 JSON 不会被通用文本截断器破坏。

---

## 7. 结果里不回写入参原文

入参已留在同一条消息的 tool call 中。结果只保留模型单看 output 无法知道的信息：

| 工具 | 结果保留 |
|------|----------|
| `memory_tool` create | `id` |
| `memory_tool` edit / delete | `success` + `id` |
| `clipboard_tool` write | `success` |
| `calendar_create` | `event_id` + 规范化 `start` / `end` |
| `text_to_speech` | `success` |
| `workspace_write_file` / `workspace_edit_file` | 文件元数据，不含正文 |
| `assistant_manage` | 不含 `instructions` |

`clipboard_tool` read 的 `text`、`assistant_call` 的 `content` 是新数据，不是回显。
