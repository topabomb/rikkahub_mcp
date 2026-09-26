# 提示词、上下文注入与工具描述

> 本文档以当前代码为准，记录模型在一次生成请求里实际看到的系统提示、动态注入、工具
> `description`、参数说明和 Tool Result 形状。文案以英文源串为准。
> 工具名使用 `Tool(name = "...")` 的注册名。
> 附件身份、请求投影与 Turn/Tool 持久化不变量见
> [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)。
> 条数窗口、滚动压缩与 Snapshot 如何叠加见 [`request-context.md`](request-context.md)。

相关实现：`StepRunner.generateInternal()`、`PlaceholderTransformer`、
`TemplateTransformer`、`AssistantToolFactory`、
`freezeTurnSystem`、`ToolArtifactReplayTransformer`、
`TimeReminderTransformer`、`AttachmentProjectionTransformer`、
`AttachmentInspectionTool`、`ConversationDisclosureSnapshotService`、
`TurnContextFactory`。

---

## 1. 一次请求里的上下文顺序

`TurnContextFactory.materialize()` 在冻结工具定义后用 `freezeTurnSystem` 生成完整 System 与组成来源。
`generateInternal()` 直接使用 `TurnContext.system.text`；同一 Turn 的 Step、审批与重试复用这些冻结输入，历史消息和工具结果随 checkpoint 推进。Master 装配顺序为：

```text
System（SyntheticMessageKind.SYSTEM_PROMPT；Text 标记 RequestPartSource.RenderedSystem）
  1. BEFORE_SYSTEM_PROMPT 规则（priority 降序，同优先级保留目录顺序）
  2. 合法非空会话覆盖 → 同来源助手适用 opening → Assistant.systemPrompt
  3. 固定 APPLICATION_CONTEXT_RULES 与独立 MODEL_RULES（不随本 Step 是否投影 Disclosure 变化）
  4. 各 FrozenToolDefinition.systemPromptContribution
  5. 已捕获 Workspace 固定说明
  6. AFTER_SYSTEM_PROMPT 规则（同上述排序）

Durable 消息（selected branch → replay-safe projection → messageLimit 窗口）
随后 Input Transformer（不在 transform 时重读 Settings / 时钟 / Locale / Workspace）：
  TimeReminderTransformer
  PromptInjectionTransformer
  PlaceholderTransformer      ← 普通文本单次替换；已渲染来源跳过
  DocumentAsPromptTransformer ← 每个 Document 前插派生正文并登记 part 来源
  TemplateTransformer         ← 仅对普通文本渲染 messageTemplate
  ToolArtifactReplayTransformer ← 先按 artifact metadata 重写历史 Tool Result 路径与 Image URL
  AttachmentProjectionTransformer ← 最后按本次模型能力投影附件：可读 IMAGE 时保留图片并前插
                                  input=native 事实；不可读时只保留 input=reference_only 事实
Transformer 全部完成后：
  TurnRequestAdmission 依据已提交历史对账并接纳本次实际贡献；
  RequestContextPlanner.applyContextProjections 按 typed placement 投影历史和新增正文。
  首个 Step 的新 Disclosure 是 anchor USER 的首个 Text；后续 Step 的变化是完整工具结果批次
  之后的独立应用 USER。接纳正文不再经过模板、占位符、提醒或附件投影改写。
```

Target 与 Master 共用 `TurnPipelineFactory`，输入顺序仅少 `ToolArtifactReplayTransformer`，其余一致。
Child 附件投影见 [子助手多模态](sub-assistant-multimodal.md)，执行 owner 见 [Turn / Step 执行](turn-step-execution.md)。

工具 schema（name、description、parameters）是 START 时 `Tool.freeze()` 物化的
`FrozenToolDefinition.parameters`，随 `TextGenerationParams.tools` 另发，与 System 同屏。
同一事实只应出现在一个落点：正在做那个动作的工具句或字段上，不在 Snapshot 与工具句之间复读。

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
请求管线的占位符值由 `TurnContextFactory` 在 START 时求值并冻结（`placeholderValues`），
跨日 / 切换 Locale / 时区不改变本 Turn 的替换结果；`DefaultPlaceholderProvider` 只服务
提示词页的变量芯片展示。

`renderPromptPlaceholders` 单次匹配并替换：值中的 `{key}` / `{{key}}` 是字面数据，不递归执行。
`freezeTurnPromptSnapshot` 在捕获时渲染选中的规则，并保留 `ResolvedPromptInjection.template/name`；
规则 part 的 `RequestPartSource.PromptRule` 使后续 Placeholder 不再重写它。
`freezeTurnSystem` 只对选定领域模板执行一次占位符替换，System 位置规则使用已捕获的渲染值；
工具与 Workspace 文本按字面拼接。`SystemContextContribution` 保存原模板、实际使用变量、定义引用，
System 位置规则另保留完整 `ModeInjection`；整个最终 System 不再经过后续模板或占位符变换。
已登记的其他应用 part 和 application history 同样跳过；普通消息维持显式配置的变换。

### 2.2 `TemplateTransformer`（Pebble `messageTemplate`）

| 变量 | 含义 |
|--------|-----|
| `message` | 当前文本 part |
| `role` | `user` / `assistant` / `system` |
| `time` / `date` | 该条消息的创建时间/日期，不是“现在” |
| `description` | `assistant.description` |

默认模板 `"{{ message }}"` 原样输出文本。
管线为本次请求合成的内容（System、时间提醒、模式注入、Disclosure）由 request-scoped
`RequestMessageOriginTracker` 标记，不应用该模板；普通 durable 消息仍按用户配置渲染。
同一 USER 内的文档派生 Text 使用 `RequestPartSource.DocumentInput`，只跳过该 part，保留用户正文
的模板处理。Tool Call / Tool Result 不递归进入 Placeholder 或 Pebble。来源表仅属于本次请求；
part 被复制替换时通过 `transferPartSource` 明确转交，不能将对象身份当作持久标识。

---

## 3. 静态注入与 Disclosure Snapshot

`ConversationDisclosureSnapshotService` 生成固定键序的紧凑 JSON。以下展示关闭状态的字段形状；
实际发送不带排版空白：

```json
{
  "type": "conversation_disclosure_snapshot",
  "format": 3,
  "memory": { "enabled": false, "scope": "disabled", "header": ["id", "content"], "rows": [] },
  "sub_assistants": { "mode": "disabled", "header": ["id", "name", "description"], "rows": [] },
  "enterprise_memory_seeds": { "header": ["id", "content"], "rows": [] }
}
```

memory scope 为 local / global / disabled，子助手 mode 为 management_only / delegation_only / both / disabled。
内容不包含捕获时间、Locale 或 revision。format 3 只携带需要披露的完整分区；省略表示本包未涉及，
不能解释为关闭或清空。关闭的分区明确保留固定形状与空 rows。完整性、大小上限、baseline
与窗口适用规则见 [请求上下文](request-context.md)。


### 3.1 记忆：Disclosure Snapshot 的 memory section

`START` 捕获 `TurnDisclosureSource` 的 namespace、能力和企业 Seed；System 与工具定义在本 Turn 内固定。
每个尚未接纳的 Step 由该 source 按原域与权限读取当前 Memory 和可见目录，
经 `ConversationDisclosureSnapshotService.render()` 生成完整当前状态。
`TurnRequestAdmission` 将实际可见的历史分区与已提交内置工具 input/output 按因果顺序交给
`ConversationDisclosureReconciliation`，只接纳首次披露、外部变化或缺失恢复的完整分区。
本会话成功工具已表达的变化不追加 USER；下一 `START` 也沿用这段历史证据，不能仅因 owner 改变就重复披露。
已接纳 Step 重试复用原正文与位置，不重采样当前状态（见 [请求上下文](request-context.md)）。memory section
形状（`enabled` / `scope` / `header` / `rows`）由该 service 的 canonical renderer 唯一定义；关闭时仍输出
固定形状，不写日期、Locale 或 revision，相同业务数据必须逐字相同。

`memory_tool` 的执行仍是 live owner 语义：写入前按最新有效配置重验 owner 与写权限；
Snapshot 中是否存在某条 Memory 不代表它仍可写，也不妨碍按真实 ID 操作新 Memory。

企业 Memory Seed 使用独立 `enterprise_memory_seeds` section，行 ID 为完整企业资源引用，内容只读；
它不是 `memory_tool` 可更新的整数记忆 ID。按当前企业助手的固定绑定顺序捕获，不因关闭可变 Memory
而丢失。历史 format 1 / 2 继续原样读取，不升级正文；format 1 缺少 Seed 表示未披露，不能当作已披露空集合。

### 3.2 子助手：Disclosure Snapshot 的 sub_assistants section

可见子助手集合
（`id` / `name` / `description`）作为 canonical Snapshot 的 `sub_assistants` section 披露；
`mode` 由 caller 的 `AssistantManagement` / `AssistantDelegation` 开关决定，关闭时是固定
`disabled` 形状。列表来自 `SubAssistantAccessPolicy.accessibleSubAssistants()`，排除 caller，
按完整配置引用排序。详细配置继续由 `assistant_inspect` 按需读取。

执行期不信任 Snapshot：三个 Assistant 工具都会从最新 Settings 与 `SubAssistantAccessPolicy`
重算访问范围；本 Turn 内经 `assistant_manage` 成功创建的 Target 按 live policy 即可调用。

`ConversationDisclosureReconciliation.reconcile` 是工具效果与完整当前分区的纯对账入口：调用方提供
实际请求里的因果有序 `DisclosureFact`，并核定内置工具身份、适用域/Memory owner/Caller 和执行终态。
它从适用 Snapshot 建立已知状态，顺序归并成功 input/output；未执行失败不改状态，已执行但未确认
或必要历史缺失使相关分区不完整。完整已知差异为 EXTERNAL_STATE，缺失基线为 BASELINE_RESTORE，
从未披露为 INITIAL；输出仅含需追加的完整分区及各分区原因，不读配置、数据库或时钟。
Memory/目录按 ID 比较，Seed 保持绑定顺序；format 1 缺少 Seed 不代表空 Seed。当前完整候选先验证
形状与总大小上限，再选出变化分区，不能以小更新绕过完整状态上限。

### 3.3 技能 `use_skill` contribution

`SkillFrontmatterParser` 解析 `name`、`description`、`compatibility` 与正文；模型可见列表只使用
name / description。`SkillManager` 是 Skill 文件树和读取 owner，`use_skill` 只通过 typed result
获得文本。文本读取先限制为 4 MiB，再 strict UTF-8 解码；超限、非法编码和 IO 错误明确返回。
文件导入、原子发布与中断恢复见 [Android 配置架构](android-configuration-architecture.md)。

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

### 3.4 TTS `text_to_speech` contribution

当前选中 TTS Provider 的 `TTSManager.getPromptGuidance()`；无指导时为空串。该值在 START 装配时
求值一次并冻结为 `FrozenToolDefinition.systemPromptContribution`。

### 3.5 工作区 `buildWorkspacePrompt`

`workspaceId` 已绑定且 `WorkspaceShellStatus.READY` 时，捕获说明并纳入冻结 System。不注入 cwd，也无逐 Step Workspace transformer。

内容由 `buildWorkspacePrompt()` 生成：`<workspace>` 内说明 `/workspace`、路径必须在 Rootfs
内、四个 `workspace_*` 工具的分工、共享 `/skills` 与 `/workspace`，以及 `/upload` 原生只读和 Shell 显式输入副本规则。

Workspace 固定说明提示使用 `workspace_read_file` 阅读 `/root/.agents/AGENTS.md`、`/workspace/AGENTS.md` 及适用项目指引；缺失为可选，指引不能覆盖用户意图与工具权限。不会自动读取文件写入 system；正文经工具结果进入既有 Step 历史和 rolling compaction。

### 3.6 时间背景 `TimeReminderTransformer`

`enableTimeReminder` 开启时，`applyTimeReminder` 使用真实 USER 判定和前驱时间映射，从消息的
`createdAt` 与捕获时区计算时间，不使用模型响应时钟或 Locale。首条真实 USER 前插入：

```text
<time_reminder>Message time: 2026-09-26T10:00:00+08:00</time_reminder>
```

与前一真实 USER 相隔超过一小时才附加 gap，例如：

```text
<time_reminder>Message time: 2026-09-26T12:00:00+08:00; gap: 2 h</time_reminder>
```

小于一天取整数 h，达到一天取整数 d。助手/工具完成时间以及登记为 synthetic 或 application history
的 USER 不重置间隔。前驱时间通过 `RequestMessageOriginTracker.markPreviousRealUserTime` 提供；
来源登记和该映射属于请求投影，不是独立持久状态。

### 3.7 位置规则 `PromptInjectionTransformer`

所有位置先在同一份未加入请求合成内容的历史上解析，不让先插入的提醒或规则影响后续 depth。
历史排除 System 和 request synthetic，保留预置/摘要这样的持久消息。depth 至少为 1，表示末条
历史消息之前；超范围取历史起点，工具批次的安全边界仍由 `findSafeInsertIndex` 保证。
TOP_OF_CHAT 在首条历史 USER 前，BOTTOM_OF_CHAT 在末条历史消息前。

同一实际位置按 priority 降序，同优先级保持目录顺序；仅相邻同 role 合并成消息，每条规则仍是
独立 Text part 并登记原规则来源。System 前/后规则归 `freezeTurnSystem`，不进入此 transformer；那里不使用配置的 role，原 role 仍保存在定义来源中。

### 3.8 托管文档 `DocumentAsPromptTransformer`

只通过 `ArtifactReadLease` 解析文件；未授权 URI、MIME 不一致或文件不可用明确失败，不读取
任意本地路径，也不把失败包装为成功的文件背景。文件/解析异常保留原类型、message 与 cause，
取消向上继续传播。

每个原 Document 之前放一段 `<UploadFile name="..." path="...">` 派生正文，保持原附件顺序；
仅存在实际工具路径时才提供 path。name/path 转义引号、尖括号、& 和控制空白；正文使用不少于
三个、且长于正文最长连续反引号的围栏。正文不执行占位符或消息模板，不改写原文件内容。

---

## 4. 职责落点（模型同屏时不复读）

| 事实 | 只出现在 |
|------|----------|
| 有哪些子助手 | Catalog JSON |
| 委托、不要指定做法 | `assistant_call` description |
| 对方看不见本对话，简报与交付偏好 | `assistant_call.request` |
| 本次任务相关附件 | `assistant_call.attachments` |
| 额外结果段 | `assistant_call.extras` |
| 路由短句不是系统提示 | `assistant_manage.description` 字段 |
| 创建时不要编造工具 | `assistant_manage.instructions` 字段 |
| 子助手人设、工具名、技能、局部记忆 | `assistant_inspect`；Catalog 只保留路由三列 |
| 工作区路径与挂载 | `<workspace>`；工具侧只在 `path` 参数重复绝对路径规则 |
| 技能清单 | `<available_skills>` |

---

## 5. 工具错误返回协议

工具调用的模型可见错误以一个 Text part 中的 JSON object 开头。公共字段是 `status` 与 `reason`；只有原因代码不足以说明具体对象、修正动作或异常诊断时才加 `detail`。成功结果仍保留各工具的领域形状，durable 终态仍由 `ToolResultStatus` 和执行记录决定。

```json
{"status":"failed","reason":"memory_not_found_in_namespace","detail":"Memory 111 does not exist in the current namespace. Do not retry this ID unchanged."}
```

- `status`：执行或业务失败为 `failed`；已确认当前不能调用为 `unavailable`；调用已承诺但无法确认结果为 `unknown`。`assistant_call` 等委派工具与 MCP 工具可返回后两者；`unknown` 不能盲目重试。
- `reason`：必有、稳定的小写下划线代码，只表达已确认的失败类别。保留已有明确领域代码，不用异常 message、动态资源名或仅表示“工具失败”的泛码代替事实。
- `detail`：可选。若存在，App 生成的最终值必须非空、单行，最多 128 个 Unicode code point（含尾部省略号）；先脱敏、压平空白，再裁剪。省略它表示 `reason` 已足以决策，不能用空字符串或重复解释来占位。远端 MCP 正文仍按原协议保留，不受本地 `detail` 上限约束。

原有业务字段可与公共字段并存，例如 Shell 退出码与 stderr、子助手交付摘要；它们不能覆盖公共语义。App 生成错误不另用 `error`、`type`、`message` 表达平行代码或说明。参数纯校验在审批前返回相同信封，不创建执行行。用户拒绝与 `ask_user` 回答沿用 typed interaction 终态。取消向上传播，不生成失败结果。

### 明确错误与意外异常

参数缺失或类型不符返回 `invalid_arguments`，`detail` 指出字段及期望；当前 owner/namespace 中资源确实不存在时返回领域 not found 代码，并在需要时指出 ID 与边界。读取快照曾有 ID 不是当前可写证明；未知读取异常也不能解释成权限拒绝。Shell 非零退出与超时分别返回 `shell_exit_nonzero`、`shell_timeout`，保留命令输出。

搜索和网页抓取在请求前校验必需字符串及 Tavily `topic` 枚举；搜索服务的明确 HTTP 拒绝按状态返回 `invalid_request`、`auth_failed`、`rate_limited` 或 `search_provider_error`，`detail` 保留服务名和 HTTP 状态。JavaScript 在创建 QuickJS 上下文前校验 `code`；日历查询和屏幕使用时间在权限动作前校验时间字段类型、格式、预设范围和数量字段。Workspace 工具在同一工作区门内复核存在性及 Shell ready 状态，分别返回 `workspace_unavailable`、`workspace_not_ready`；企业访问撤销返回 `tool_not_permitted`。这些都是明确拒绝；其他外部服务、文件 IO 等未知异常由 Runtime 保留诊断。

无法预期的工具实现异常返回 `failed/runtime_error`，其 `detail` 必有异常类型与 cause 链中最深的非空 message；message 为空至少保留异常类型。完整异常、cause 与堆栈写入诊断日志。Runtime 持有的 checkpoint、资源登记等基础设施异常继续向 Turn owner 传播。只脱敏凭据、token、Cookie、Authorization、密钥和明确的隐私 payload；不删除定位相关的非敏感细节。

本地分类失败不得用 `tool_failed`、`operation_failed` 或固定“稍后重试”掩盖已知原因。历史工具输出中的图片字节若未能持久化，回放投影使用 `unavailable/media_persistence_failed` 说明媒体不可读取，不倒改原工具执行终态。

### MCP 阶段与结果

| 已确认事实 | `status` / `reason` | `detail` 的条件 |
| --- | --- | --- |
| 本地定义或工具已撤销 | `unavailable/tool_unavailable` | 通常省略 |
| 调用前无可用 session | `unavailable/server_unavailable` | 恢复动作或底层原因有用时附上 |
| 调用前需要用户授权 | `unavailable/authorization_required` | 通常省略 |
| 服务端明确返回 MCP error 或 `isError` | `failed/remote_error` | 无远端正文时可附有界远端 message；否则保留原始文本及 structuredContent。错误中的图片等非文本内容只标明已省略，不经本地 Artifact 写入，不能覆盖远端错误性质 |
| 结果不符合 MCP 内容协议 | `failed/protocol_incompatible` | 有具体违规约束时附上 |
| 已收到结果，本地投影或保存失败 | `failed/result_processing_failed` | 附本地异常，并提示核实远端副作用 |
| 调用承诺后无可确认结果 | `unknown/outcome_unknown` | 附失败原因和先核实、勿盲重试的提示 |

SDK `McpException` 同时可表达本地连接关闭、超时和明确 RPC 错误，必须按 code 与已完成阶段分类，不能仅凭类型声称来自服务端。客户端的 commitment 不等于网络字节已发送；不能自动重放 unknown 调用。server/tool、transport、generation、`retryable` 和 `request_sent` 不进入模型结果；本地异常可在脱敏后进入有界 `detail`。

新工具、渐进披露工具和代理调用工具先定义稳定的明确失败原因，再把意外异常交给统一 Runtime。回查工具把 ref 格式错误归为 `invalid_arguments`，把合法但不可用的归档引用归为 `archive_unavailable`；意外读取异常交给 Runtime。委派工具保留子任务失败、不可用或未知事实及交付状态，父工具不能把子任务失败伪装为完成。可归档的错误信封仍须由原回查工具读取；有不可恢复交付引用时沿用 `PRESERVE`。

工具卡片以 typed 终态决定成功或失败标题。已知用户操作类别可显示简短本地化提示；技术性 `reason` 保留代码，`detail` 在详情中可展开，不用泛化文案遮盖。验证覆盖参数拒绝时序、领域失败、意外异常和 cause、脱敏及 128 字符边界、取消、MCP 承诺前后状态、明确远端错误与本地结果处理错误，并核对模型 Text part 与 durable 终态。

---

## 6. 工具描述与参数

`search_web` 的描述要求时效问题核验发布日期和事件发生日期；检索顺序、抓取时间不能证明新鲜度，缺日期或一手证据时应继续检索/读取来源。此为固定工具指引，不伪造搜索 SDK 的日期字段。

`ask_user` 的 text、single、multi 均允许自由文本。single/text 的选项填入文本框，multi 按选项顺序组合已选项并追加去重后的自由回答；空白不是有效答案。UI 使用同一规则判断可提交性并编码既有字符串答案，等待原提交 owner 接受后禁用，拒绝可以重试。

`TurnToolSetFactory` 依次装配回查、搜索、附件识别、Local、Conversation、Workspace、Skill、调用方
追加工具与 MCP；Memory 和 Assistant 工具在 START 装配链中加入。同名 definitions / execution bindings
在 `freezeToolSet` 物化，空名或重名失败关闭。schema 与 System contribution 脱离可变 backing map，
同一 Turn 不因 live 配置变化改写工具名、描述或顺序；执行时仍复核实际权限与资源。

Target 禁止 Assistant 管理/委托工具以保持单层调用，保留 `ask_user` 并由父调用卡片桥接。Target 启用
TextToImage 且模型有效时可使用 `generate_image`。运行和检查均显式传入 resolved model；参数校验、
审批及 execution / Result 的通用协议见 [Turn / Step 执行](turn-step-execution.md)。

下列 description 为源码中的英文原文（动态日期/时区用占位标明）。

### `search_web`

启用：`shouldUseExternalWebSearch(assistant, model)`。助手打开外挂搜索，且当前模型未带 `BuiltInTools.Search`。

描述使用常量文本，不内嵌日期。

> Search the web for current or specific facts. Use focused keywords; run multiple searches if needed.
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

### `generate_image`

启用：`LocalToolOption.TextToImage` 已开，并且
`Settings.imageGenerationModelId` 能解析到启用 Provider 上、客户端声明支持文生图的
`ModelType.IMAGE` 模型。默认不加入 `DEFAULT_ASSISTANT_LOCAL_TOOLS`。Master 与 Target Run
（含 `assistant_inspect` 的注入按 `TurnKind.SUB_ASSISTANT` 判定）在配置满足时都会注册。
`set_as_background=true` 仍须审批；Target 非交互下自动拒绝，返回
`tool_not_permitted` + `approval_unavailable`，语义为“需要审批但当前运行环境无法
提供审批，不要原样重试”。该错误只拒绝当前 ToolCall，Target 可调整参数后继续运行。

> Generate one image from a text prompt, show it to the user, and return a local path that follow-up tools can use.
> Failures return a stable reason and, when needed, a short detail.

`text_to_image` 的 `systemPromptContribution` 只在 START 注册时注入当前非敏感配置，字段由
`ImageGenerationModelDescriptor` 统一生成：`provider_type`、`provider_name`、`model_id`、
`model_name`。不含 API key、base URL、custom headers/body，也不声明 Chat 模型是否具有视觉能力。
图片是否回传给下一 step 由请求级附件投影和 Provider 适配共同决定。

| 参数 | description |
|------|-------------|
| `prompt` | A complete, model-ready prompt for the image. Preserve the user's intent and relevant context, and refine it using effective prompting techniques suited to the target image model. |
| `set_as_background` | Whether to use the generated image as the current assistant's chat background. Set to true when the user asks to apply the image as the background. |

`set_as_background` 默认 false，纯生成不审批；`set_as_background=true` 必须审批。通用入口先验证合法 JSON object；
prompt 为空或 boolean 类型不合法时，审批前的最终结果为
`{"status":"failed","reason":"invalid_arguments","detail":"..."}`。

成功结果是 bounded JSON + `UIMessagePart.Image`，使用 `ToolOutputPolicy.PRESERVE`，不回显 prompt：

```json
{
  "status": "completed",
  "file": { "path": "/upload/7ka2b9.png", "mime_type": "image/png" },
  "background": { "requested": false, "updated": false }
}
```

`file.path` 是 Tool Result 内唯一模型可见的文件访问标识，语义为本地工具可消费的
`/upload/<safe-file-name>`。逻辑身份 `attachment:<uuid>` 仍锚定在 Image part metadata 上；
`AttachmentProjectionTransformer` 对托管 upload 披露同一个实际路径与文件名，形成带
`input=native` 或 `input=reference_only` 的 `[Attachment path=/upload/... ]` 事实行。
Android host path、file URI 和内部 relative path 不进入 Tool Result。
会话 fork / 恢复按 metadata 中的 `LocalArtifactRef.relativePath` 重写该字段；文件缺失时不得
伪造 completed + readable path。

失败结果只有 Text part，按[工具错误返回协议](#5-工具错误返回协议)带稳定 `reason`，必要时带不超过 128 字符的 `detail`。本地前置失败包括：
`invalid_arguments`、`image_model_unavailable`、`tool_revoked`、`image_model_changed`、
`assistant_not_found`（执行前发现会话所属 Assistant 已删除）。

工具在当前运行中未注册时，返回 `tool_not_available` 和明确 `detail`，不抛内部异常。模型收到后不应原样重试。
生图调用失败由 `classifyProviderFailure()` 分类，并带回裁剪后的 `detail`
（默认字符上限，脱敏 API key / Bearer / 内联 base64，不含堆栈）：

| reason | 模型应如何处理 |
|--------|----------------|
| `content_blocked` | 提示词或结果触发政策。改写提示词，去掉违禁内容。`detail` 是稳定政策说明，不回传检查类型（含 OpenAI `moderation_blocked` / `content_policy_violation` 与 xAI `respect_moderation=false`） |
| `rate_limited` | 稍后再试，不要改写提示词 |
| `quota_exhausted` | 额度、余额或 spend limit 用尽。请用户检查账单，重试无效 |
| `auth_failed` | API key 无效。请用户检查 Provider 设置 |
| `permission_denied` | 账号或模型无权限 |
| `invalid_request` | 服务拒绝参数（尺寸、过长提示词等）。可按 `detail` 调整后重试 |
| `provider_unavailable` | 超时、过载或 5xx。稍后重试 |
| `provider_error` | 已识别为 HTTP 失败但无法再细分。阅读 `detail` |
| `runtime_error` | 本地未分类异常。阅读 `detail` |
| `invalid_result` | HTTP 成功但没有最终图片 |
| `persistence_error` | 图片已生成但本地保存失败 |

无法解析的响应体只提取 `error.message` / `error.code` / `error.type` 等短字段；HTML 或乱码不进入 `detail`。
历史重放或 UI rematerialize 时，若 managed chat copy 已不存在，去掉 Image part，
并在 `file` 上标记 `available=false`、`reason=artifact_missing`，不保留看似可读的
`/upload/...` 路径。历史执行 `status` 保持原值，Replay 不重新判定工具是否成功。
背景失败不回滚已进入 Gallery 的图片；此时图片仍为 completed，`background.updated=false`
并带 `assistant_not_found` / `background_copy_failed` / `settings_write_failed`。

### `inspect_attachments`

启用条件：`attachmentInspectionModelId` 能解析到 Provider 可用且 `inputModalities` 含 IMAGE 的识图模型。
不要求当前模型缺少视觉能力，也不要求当前会话已携带图片或有可用工作区。原生视觉能力与按需读取文件是不同能力；
模型能力由 `Model.inputModalities` 声明，协议容器由 `RequestMediaCapabilities` 映射，不按 host 再次否决。

> Inspect attachment content on demand when the task depends on it — for example,
> text or other visual details in an image. Returns the findings for the request.

参数：

- `attachments`（array，1–4 项，required）：`Image file paths from the user's request, [Attachment path=...] markers,
  tool result file.path, or artifacts[].path. Files need not have appeared as images in this chat.
  Up to 4; order is preserved. Does not require a workspace.` items：`Exact image file path: /upload/<file>.`
- `request`（string，required）：`The specific information needed and its expected form: exact text to
  transcribe, details to compare across images, or facts to verify. Prefer precise requests over vague
  descriptions. Keep it focused on the current task.`

路径经 `ArtifactStore` 校验为 ACTIVE、已发布、位于受管 upload 且可读的真实图片；不要求当前分支引用。
不接受 UUID、HTTP(S)、file URI、裸文件名或 `/workspace`。识图读取形成内存快照，通过共用 FileEncoder 规范化为
data URI，保留压缩、方向和格式转换规则，不落盘或复制文件。
识图模型接收固定独立 System instruction、按序 `[Image N path=...]` 与 Image、最后的 request；不携带主会话历史。

识别调用内部以 `reasoningLevel = AUTO`（Provider 使用模型默认推理档）发起，不表达「关闭
推理」——`OFF` 在 `GEMINI_3_NO_MINIMAL_THINKING`（3.1 Pro 全形态与 3.7 Flash）上映射为
`thinkingLevel = "low"`，其余 Gemini 3 系列映射为 `"minimal"`。Provider 异常经统一分类器 `classifyProviderFailure`
映射为细分 `reason` 并附 sanitized `detail` 诊断文本（与 `generate_image` / `assistant_call`
的失败契约一致）；原始异常写入 logcat。

识别调用由工具自身持有 Provider 请求边界，不经过 `TurnRunner`。工具构造时
（`createAttachmentInspectionTool`）一次性解析并捕获 inspection model、provider setting
与派生的 `RequestMediaCapabilities`，写入 `TextGenerationParams.mediaCapabilities`。
执行时不再通过 Settings 重找模型，也不再按 endpoint host 二次裁决图片能力。构造时仅断言 Provider 遵守
`IMAGE` 模型必须能结构化编码 USER 图片的静态契约；若远端实际不兼容，Provider 请求返回的真实分类错误表达。识别模型配置无效时工具不会注入；若历史或恢复中的
ToolCall 已失去该工具，则走统一 `tool_not_available`，不产生专用能力 fallback。

成功结果为普通 Text part（识别模型输出），不携带附件数据。失败结果为带稳定 `reason` 的
JSON：

| reason | 含义 |
|--------|------|
| `invalid_attachments` | paths 为空 / 超过 4 个 / 不是安全 upload 路径 / request 非字符串或为空 / 解析图片数与输入数不一致 |
| `attachment_not_found` | 无已发布 ACTIVE 登记、文件不存在或不在受管目录 |
| `unsupported_attachment_type` | 文件内容不是支持的图片 |
| `attachment_too_large` | 图片超过大小上限 |
| `attachment_read_failed` | 本地读取 IO 失败 |
| `attachment_resolution_unavailable` | 执行环境未提供附件解析能力 |
| `rate_limited` / `quota_exhausted` / `auth_failed` / `permission_denied` / `invalid_request` / `provider_unavailable` / `provider_error` / `content_blocked` / `runtime_error` | Provider 调用失败，`classifyProviderFailure` 细分（与 `ProviderFailureKind` 字面一致）；失败信封可携带 `detail`（sanitized 诊断文本） |
| `inspection_failed` | 识别输出为空（附明确 `detail`） |

媒体资源引用（全工具链统一语义）：

```text
attachment:<uuid>   仅内部持久化逻辑身份，不作为模型披露或工具输入
/upload/<file>      托管附件的模型文件路径；识别与委托不依赖 workspace
/workspace/...      显式共享的工作产物区；Shell 的 /upload 只含 uploads 列出的本域授权副本
```

工具产出新媒体（如 `generate_image`）时，Tool Result `file.path` 与附件事实行的 path 指向同一聊天副本。
内部 UUID 不改写成文件名，模型也不需要在图库名称、副本路径与 UUID 之间换算。
新文件使用无前缀的短名，按四档随机候选查重，全冲突才加数字后缀；旧文件原样保留，规则见[多模态参考](multimodal-context-and-turn-durability.md)。

### `get_time_info`

启用：`LocalToolOption.TimeInfo`。无参数。

> Get the current local date and time from the device.

结果为固定字段 JSON：year/month/day、weekday、weekday_en、weekday_index、date、time、
datetime、timezone、utc_offset、timestamp_ms。

### `text_to_speech`

启用：`LocalToolOption.Tts`。

> Speak text aloud when the user asks you to read something, or when audio is appropriate.
> Returns immediately; playback continues in the background.
> Provide natural speech text without markdown in the required `text` argument.

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

启用：`LocalToolOption.AskUser`。`interactionRequirement=UserInput`；合法调用经统一 ToolCallRuntime 暂停并由 typed `Answer` 决定回填结果，`execute` 不直接跑。
问题字段不合法时在审批门口直接失败：写入
`{"status":"failed","reason":"invalid_arguments","detail":"..."}`，不 Pending、不自动执行。
`detail` 指出错误字段与期望；`options` 格式错误时也包含修正提示。Target 上由 Coordinator 桥到主聊天子助手卡片。

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
> Requires Calendar permission; if missing, an error asks the user to enable it in local tools settings.

另有 `query`（标题关键字）与 `limit`（默认 20）。

### `calendar_create`

启用：同上。`interactionRequirement=Approval`。

> Create a calendar event (title and start required). End defaults to 1 hour after start, or the next day if all-day.
> Device timezone: '\<zone\>' (UTC \<offset\>).
> Requires Calendar permission; if missing, an error asks the user to enable it in local tools settings.

成功结果：`{"success":true,"event_id":N,"start":"...","end":"..."}`。`start`/`end` 是解析后的规范时间。

创建工具在 START 装配时捕获设备时区，同一 Turn 的参数校验与执行共用该快照。必填项、字段类型和时间范围在审批前校验；
系统权限检查、日历选择和实际插入仍属于执行阶段，参数错误不会触发授权或系统权限访问。

### `eval_javascript`

启用：`LocalToolOption.JavascriptEngine`。

> Execute JavaScript (QuickJS, ES2020). Result is the last expression.
> Use toFixed() for decimal precision. No DOM or Node.js APIs. Console output precedes the result.

参数：`code`。成功结果使用真实换行的行式文本：有控制台输出时先给出 `[console]` 段，每次 console 调用保持独立物理行，
最后给出 `[result]` 段。它不把多行日志再次塞入 JSON 字符串，因此归档后的 `read_tool_output` / `grep_tool_output`
行号直接对应实际日志行。

### `memory_tool`

启用：`assistant.enableMemory`。由 `TurnRunner` 按 owner namespace 构建。

描述是常量文本：工具名称、描述、Schema 与列表排序共同构成 Provider 请求的可缓存前缀，
描述里没有当前日期（原先的 `Today is ...` 会让整条前缀每天被击穿一次）。
消息时间由 `TimeReminderTransformer` 表达，捕获日期由 `{{cur_date}}` 表达。
Memory 行的顺序由 DAO 的 `ORDER BY id ASC` 固定。

> Store long-term notes across conversations (create/edit/delete).
> Merge similar records; prefer edit over create.
> Do not store sensitive personal attributes.
> Do not show memory content unless the user asks.

| 参数 | description |
|------|-------------|
| `action` | create, edit, or delete |
| `id` | Record id (required for edit/delete) |
| `content` | Note text (required for create/edit) |

create：`{"id":N}`。edit / delete：`{"success":true,"id":N}`。
成功写入结果通过 `successfulOutputPolicy` 保持 `PRESERVE`，原 input 已包含正文，不再次回显。
这只改变成功结果的压缩策略，不改权限校验、失败终态或其他工具的归档策略。
edit / delete 在当前 owner namespace 中影响 0 行时返回 `memory_not_found_in_namespace`，`detail` 给出 ID 与“不原样重试”的提示；读取过旧快照不构成当前可写证明。
未进入执行的拒绝不使已知分区失效；已执行但结果无法确认时，不能从失败文本推断未发生修改，
下一安全请求边界通过完整分区恢复。成功短结果和原 input 已表达的变更不另行通知。
聊天卡片摘要读的是 tool **入参**的 `content`，不是结果。

### `recent_chats`

启用：`enableRecentChatsReference`。

> List recent conversations with this assistant (titles and last-activity dates, pinned first).
> Use `conversation_search` for message content.

`limit` 默认 10、最大 30。结果为 id / title / last_chat 数组。
该工具经 `ConversationQueryService` 读取轻量列表记录，不为标题与时间摘要加载消息树。

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
审批按规范化路径判定，缺字段或错误类型先返回参数错误；文件工具不跟随符号链接，实际 IO 由 Workspace owner 的安全文件操作完成。

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

启用：`LocalToolOption.AssistantManagement`。合法 `CREATE` 不审批；合法 `UPDATE` / `DELETE` 必须审批。
缺失或非法 action、错误字段类型、非法 ID 和不完整操作参数在审批前直接返回 `invalid_arguments`。

> Create, update, or delete a user sub-assistant (sub-agent). New ones join your allowed list in this realm.
> User definitions are shared across spaces; updates and deletion affect that shared definition. Enterprise definitions are read-only.

| 参数 | description |
|------|-------------|
| `action` | CREATE, UPDATE, or DELETE. |
| `assistant_id` | Required for UPDATE and DELETE. |
| `name` | Display name. Required and non-empty for CREATE; optional replacement for UPDATE. |
| `description` | Specialty and when to call it. Required and non-empty for CREATE; optional replacement for UPDATE. Not a system prompt. |
| `instructions` | System prompt for the sub-assistant: role, method, output style. Required and non-empty for CREATE; optional replacement for UPDATE. Do not invent tools or skills. |

成功返回 `action` 与 `id`；仅当本次 input 修改过的字段与 owner 提交返回的实际值不同时，增加
`applied` 对象，字段限 `name` / `description` / `instructions`。比较原始 input，而不是已经 trim/
规范化的参数；未修改字段及没有差异的正文不回显。结果来自本次提交，不在提交后重读 Settings。
DELETE 可带 `cleanup_pending`，不带 applied。成功写入结果使用 `PRESERVE`。

```json
{"action":"update","id":"<target-reference>","applied":{"description":"实际保存的规范化描述"}}
```

### `assistant_inspect`

启用：同上。只读。

> Inspect a sub-assistant's configuration before updating or deleting it.
> Returns profile by default; request additional sections if needed.

| 参数 | description |
|------|-------------|
| `assistant_id` | Catalog id. |
| `sections` | Optional: profile, tools, skills, memory. |

结果始终带顶层 `id`。点名的段才出现：

- `profile`：当前 `name` / `description` / `instructions`
- `tools`：Target Run 此刻可注册的工具名数组，无 description；`enableMemory` 时含 `memory_tool`
- `skills`：已挂载技能名
- `memory`：`active`（`local` / `global` / `disabled`）+ `header+rows`；仅 `local` 时 rows 有内容

caller 自身返回 `target_is_caller`。

### `assistant_call`

启用：`LocalToolOption.AssistantDelegation`。同步：返回前父工具批次等待该 Child。

> Delegate a self-contained request to a catalog sub-assistant (sub-agent). Do not prescribe how it must work.

| 参数 | description |
|------|-------------|
| `assistant_id` | Catalog id. |
| `request` | It cannot see this chat. Give a clear goal, the facts it needs, and any constraints. Say what you need back; a concise, high-value reply is usually enough. |
| `attachments` | Up to 4 task-related image file paths: `/upload/<file>`. Copy paths from the user's request, `[Attachment path=...]` markers, tool result `file.path`, or `artifacts[].path`. The target cannot see this chat—do not assume it can see a just-uploaded image. |
| `extras` | Extra result content for the caller model. Default none. Values: artifacts, tts, tool_calls. Request artifacts when you need to inspect, reason about, or reuse the file contents produced by the sub-assistant. The user can already see those files in the call card. A short artifact list is always included when files were produced. |

完成：`{"status":"completed","assistant_name":"...","content":"..."}`，必要时
`has_non_text_output`。本次 run 有可持久化交付物时，无论是否点名 extras 都带轻量
`artifacts[]`（仅 `path` / `type` / `mime`，无可用 path 的项不披露）；超出上限时另带
`artifacts_omitted`。其他终态按[工具错误返回协议](#5-工具错误返回协议)带稳定 `reason`，必要时带有界 `detail`。`provider_error` / `runtime_error` 的 `detail` 保留可定位的异常类型、消息和有意义的 cause；`content_blocked` 使用稳定政策说明，不回传检查类型（含 OpenAI `content_filter`）。

默认清单是 Text JSON，没有 Image 或 `[Attachment ...]` 事实行。需要后续检查时，可以直接使用 `artifacts[].path`
调用识图；无需重跑子助手。`extras` 只在原委托调用时选择内容，不是事后补取接口。

已跑过 Child 且调用过 `text_to_speech` 时，默认另带精简 `tts_stats`（`calls` 次数、`chars` 朗读字符合计）。体积较大或需 Caller 主动取回的段只在 `extras` 点名后返回：

- `artifacts`：把本次可持久化 Image 交付物追加为带 stable `attachment_ref` 的 Image parts。它不在这里做能力判断或视觉识别；Caller 下一次请求统一由 `AttachmentProjectionTransformer` 按当次 resolved model 投影为原图 + `input=native` 事实，或仅 `input=reference_only` 事实。需要视觉细节时由模型显式调用 `inspect_attachments`。
- `tool_calls`：本次 run 范围内每个工具的发出次数（`header+rows`，首次出现序）
- `tts`：按调用顺序的朗读文本表

`unavailable` 不加这些段。通过共用 JSON object 校验后的委托领域参数错误也使用同一信封（`status=unavailable` + `reason`）。附件相关 reason：`invalid_attachments`、`attachment_not_found`、`unsupported_attachment_type`、`attachment_too_large`、`attachment_read_failed`。聊天模型 / Target 不接收 IMAGE 本身不是 attachment failure。

`content` 只取本次 run 范围内最后一条 Assistant 的最后一个 `StepOutcome.Final` Step 之后的顶层 Text，
按换行拼接并 trim。没有 Final Step 或正文时为空，不回退工具前文字、更早 Step 或 text island。

### `mcp__<server>__<tool>`

启用：Assistant 已选择该 server，definition 当前 enabled，并存在 definition digest 匹配的完整非空 LKG Catalog；
当前连接健康不参与 schema 注入。名称、description、参数来自 run 开始时冻结的 `TurnMcpCapabilitySnapshot`；Settings
只保存 enable/approval policy，不保存远端 schema。输入参数以完整 JSON Schema 文档保存，`$schema`、`$defs`、`$ref`
和未知扩展不会丢失。

用户手工刷新与 `notifications/tools/list_changed` 成功提交后只更新后续 turn；同一 run 继续使用启动 revision。

失败的阶段分类、公共字段与远端正文边界见[工具错误返回协议](#5-工具错误返回协议)。无 live session 时内部触发恢复；
调用承诺后不能自动重放未知结果。

成功 `TextContent` 进文本，`ImageContent` 先取得 Artifact lease 再转 Image part，成功 `structuredContent` 也进入工具结果。
调用总时限 120 秒，取消向上传播。

---

## 7. 工具输出归档与回查

工具结果的压缩资格与预算由 [请求上下文](request-context.md) 定义；此处只说明模型能看到的策略与回查接口。
`ARCHIVABLE_TEXT` 在成功请求消费后可变为 `[Archived tool result: ref=...]`；`REGENERABLE_TEXT` 只变为
固定 `[Derived tool result folded]`，原参数仍在调用中；`PRESERVE` 完整保留。返回时不立即裁剪。

登记过 unpublished Artifact 的工具结果在成功与失败时都强制 PRESERVE。`assistant_call` 静态默认
PRESERVE；只有单 Text、status=completed、assistant_name / content 为字符串且没有 artifacts manifest
的结果才可改为 ARCHIVABLE_TEXT。带交付物、混合媒体、非完成态或损坏结果继续保留。

`read_tool_output` 与 `grep_tool_output` 始终随 Master 和 Target 注册，均为无交互、`REGENERABLE_TEXT`、32 KiB 有界结果。
它们接受 marker 中的正整数 `ref`，但每次读取仍必须验证当前 conversation 的 `TOOL_OUTPUT` reference；
`read_tool_output` 使用 `start` / `limit`，每次最多 500 个稳定虚拟行；超长物理行按最多 4096 个 Unicode code point
切分，不会拆开 emoji 等辅助平面字符。`grep_tool_output` 使用 RE2/J 逐行匹配，
支持常用分组、交替、字符类、锚点和量词，不支持 lookaround 与 backreference；`context` 最多 5，`limit` 最多 100。
成功结果为带编号的短小纯文本，不回显 ref、pattern 或可选入参，最终 UTF-8 输出不超过 32 KiB。其结果仍参与滚动压缩，
但折叠不创建新 Artifact 或 ref，避免形成读取切片的复制链。
ref 不授予权限，也不会暴露 relative path、`file://` 或 App 私有路径。原 ref 对应的归档正文保持不可变；回查结果满足
统一阈值后只折叠 marker，不再归档，不建立复制链或递归读取协议。

工具结果的错误信封与 typed 终态见[工具错误返回协议](#5-工具错误返回协议)；本节的归档规则对内建及 MCP 结果共用。

---

## 8. 结果里不回写入参原文

入参已留在同一条消息的 tool call 中。结果只保留 input 尚未表达的执行结果或实际差异，结合两者理解操作：

| 工具 | 结果保留 |
|------|----------|
| `memory_tool` create | `id` |
| `memory_tool` edit / delete | `success` + `id` |
| `clipboard_tool` write | `success` |
| `calendar_create` | `event_id` + 规范化 `start` / `end` |
| `text_to_speech` | `success` |
| `workspace_write_file` / `workspace_edit_file` | 文件元数据，不含正文 |
| `assistant_manage` | `action` + `id`；有实际规范化差异时仅加 touched 字段的 `applied`，DELETE 可加 `cleanup_pending` |
| `generate_image` | bounded JSON + Image part；成功不回显 prompt |

`clipboard_tool` read 的 `text`、`assistant_call` 的 `content` 是新数据，不是回显。
