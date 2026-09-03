# 提示词、上下文注入与工具描述

> 本文档以当前代码为准，记录模型在一次生成请求里实际看到的系统提示、动态注入、工具
> `description`、参数说明和 Tool Result 形状。文案以英文源串为准。
> 工具名使用 `Tool(name = "...")` 的注册名。
> 附件身份、请求投影与 Turn/Tool 持久化不变量见
> [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)。
> 条数窗口、滚动压缩与 Snapshot 如何叠加见 [`request-context.md`](request-context.md)。

相关实现：`GenerationLoop.generateInternal()`、`PlaceholderTransformer`、
`TemplateTransformer`、`AssistantToolFactory`、
`WorkspaceReminderTransformer`、`ToolArtifactReplayTransformer`、
`TimeReminderTransformer`、`AttachmentProjectionTransformer`、
`AttachmentInspectionTool`、`ConversationDisclosureSnapshotService`、
`TurnRequestContextFactory`。

---

## 1. 一次请求里的上下文顺序

`generateInternal()` 使用 START 时冻结的 `TurnRequestContext`（`FrozenTurnPromptInputs` +
`FrozenToolDefinition`）装配请求；同一 Turn 的所有 step、审批与重试逐字复用。Master 当前装配顺序为：

```text
System（合成消息，标记 SyntheticMessageKind.SYSTEM_PROMPT）
  1. Assistant.systemPrompt
     （allowConversationSystemPrompt 且会话 customSystemPrompt 非空时，用会话提示覆盖）
  2. 各 FrozenToolDefinition.systemPromptContribution ← START 装配时求值一次并冻结
  3. 请求携带 Disclosure Snapshot 时追加固定 MODEL_RULES（ConversationDisclosureSnapshotService）

Durable 消息（selected branch → replay-safe projection → messageLimit 窗口）
随后 Input Transformer（不在 transform 时重读 Settings / 时钟 / Locale / Workspace）：
  TimeReminderTransformer
  PromptInjectionTransformer
  PlaceholderTransformer      ← 替换 {{char}} / {{description}} 等
  DocumentAsPromptTransformer
  TemplateTransformer         ← 渲染 messageTemplate
  WorkspaceReminderTransformer
  ToolArtifactReplayTransformer ← 先按 artifact metadata 重写历史 Tool Result 路径与 Image URL
  AttachmentProjectionTransformer ← 最后按本次模型能力投影附件：可读 IMAGE 时保留图片并前插
                                  input=native 事实；不可读时只保留 input=reference_only 事实
Transformer 全部完成后：
  ConversationContextPlanner.applyContextProjections 把 Disclosure Snapshot 作为 anchor USER
  turn 的第一个 Text part 注入；不经过任何模板、占位符、提醒或附件投影改写。
```

Target 复用相同模型可见语义，但 Transformer 装配由 `DelegationCoordinator` 独立负责；完整顺序见
[`chat-generation-pipeline.md`](chat-generation-pipeline.md) 与
[`sub-assistant-multimodal.md`](sub-assistant-multimodal.md)。

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
请求管线的占位符值由 `TurnRequestContextFactory` 在 START 时求值并冻结（`placeholderValues`），
跨日 / 切换 Locale / 时区不改变本 Turn 的替换结果；`DefaultPlaceholderProvider` 只服务
提示词页的变量芯片展示。

### 2.2 `TemplateTransformer`（Pebble `messageTemplate`）

| 变量 | 含义 |
|--------|-----|
| `message` | 当前文本 part |
| `role` | `user` / `assistant` / `system` |
| `time` / `date` | 该条消息的创建时间/日期，不是“现在” |
| `description` | `assistant.description` |

默认模板 `"{{ message }}"` 原样输出文本。
管线为本次请求合成的内容（System、时间提醒、模式注入、Workspace 提醒）由 request-scoped
`RequestMessageOriginTracker` 标记，不应用该模板；普通 durable 消息仍按用户配置渲染。

---

## 3. 静态注入与 Disclosure Snapshot

### 3.1 记忆：Disclosure Snapshot 的 memory section

动态 Memory System 注入已删除。Memory 内容的唯一披露路径是每次新 `START` 前由
`ConversationDisclosureSnapshotService.captureCandidate()` 从固定 effective-settings 快照与一次
`ORDER BY id ASC` 的有序 Memory 查询渲染的 canonical Snapshot；内容变化才随新 Assistant owner
追加 entry（见 [`chat-generation-pipeline.md`](chat-generation-pipeline.md)）。memory section
形状（`enabled` / `scope` / `header` / `rows`）由该 service 的 canonical renderer 唯一定义；关闭时仍输出
固定形状，不写日期、Locale 或 revision，相同业务数据必须逐字相同。

`memory_tool` 的执行仍是 live owner 语义：写入前按最新有效配置重验 owner 与写权限；
Snapshot 中是否存在某条 Memory 不代表它仍可写，也不妨碍按真实 ID 操作新 Memory。

### 3.2 子助手：Disclosure Snapshot 的 sub_assistants section

`assistant_manage` / `assistant_call` 的动态 Catalog systemPrompt 已删除。可见子助手集合
（`id` / `name` / `description`）作为 canonical Snapshot 的 `sub_assistants` section 披露；
`mode` 由 caller 的 `AssistantManagement` / `AssistantDelegation` 开关决定，关闭时是固定
`disabled` 形状。列表来自 `SubAssistantAccessPolicy.accessibleSubAssistants()`，排除 caller，
保持 `Settings.assistants` 顺序。详细配置继续由 `assistant_inspect` 按需读取。

执行期不信任 Snapshot：三个 Assistant 工具都会从最新 Settings 与 `SubAssistantAccessPolicy`
重算访问范围；本 Turn 内经 `assistant_manage` 成功创建的 Target 按 live policy 即可调用。

### 3.3 技能 `use_skill` contribution

`SkillFrontmatterParser` 使用 SnakeYAML `SafeConstructor` 和 loader limits（禁止重复键、限制 alias/nesting depth/collection size/code points）解析 `SKILL.md` frontmatter，返回 typed `SkillDocument(frontmatter: SkillFrontmatter, body: String)` 或 typed parse error。`SkillFrontmatter` 只含 `name`、`description`、`compatibility`；`allowedTools` 已删除（无执行消费者，保留会形成假权限协议）。

`SkillManager` 是 Skill 目录身份、文件树、读取、frontmatter 校验和发布的唯一 owner。UI 与 `use_skill` 只取得 `SkillMetadata`、`SkillFile`、`SkillFileNode` 和 typed result，不取得宿主 `File` 或目录路径。任何模型/UI 可读文本都在 owner 边界先做 4 MiB bounded byte read，再用 strict UTF-8 解码；超限、非法编码与 IO 分别返回 typed failure，不能先 `readText()` 整文件后才检查。任何主文档/支持文件写入和支持文件删除都先复制完整已发布目录到 staging，拒绝 symlink/path escape，重新校验 typed frontmatter 与预期 name，再通过 rename 发布；更新中断留下的 hidden backup 会在下一次 owner 访问时恢复旧目录或清理已过期 backup，歧义时 fail-closed。更新 `SKILL.md` 保留支持文件；ZIP bundle 先完整解析、拒绝重复 Skill name，再复制整个 Skill root 并通过一次 root swap 提交，第二项失败或取消不会留下部分更新，root backup 同样有重启恢复协议。文件导入固定限制为 16 MiB 输入、512 entries、单文件 4 MiB、累计解压 32 MiB；GitHub 下载以 bounded `ByteArray` 保存所有支持文件，只对 `SKILL.md` strict UTF-8 解码/typed parse，因此 PNG、PDF、字体和其他二进制资产按原字节发布。`SKILL.md` 不允许作为普通支持文件删除，整项删除只能走 `deleteSkill` 及其 enabled-skills 清理协议。

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
| 对方看不见本对话，简报与交付偏好 | `assistant_call.request` |
| 本次任务相关附件 | `assistant_call.attachments` |
| 额外结果段 | `assistant_call.extras` |
| 路由短句不是系统提示 | `assistant_manage.description` 字段 |
| 创建时不要编造工具 | `assistant_manage.instructions` 字段 |
| 子助手人设、工具名、技能、局部记忆 | `assistant_inspect`；Catalog 只保留路由三列 |
| 工作区路径与挂载 | `<workspace>`；工具侧只在 `path` 参数重复绝对路径规则 |
| 技能清单 | `<available_skills>` |

---

## 5. 工具描述与参数

主会话的基础工具装配顺序见 `MasterTurnCoordinator` / `GenerationToolSetFactory`：搜索、Local Tools、最近会话、Workspace、技能、
Assistant Tools、MCP；运行时 `inspect_attachments` 由 `GenerationToolSetFactory` 按 START 解析的 model 与设置条件加入；Memory Tools
同样由 Master/Target owner 在 START 前按该 Assistant 的固定 namespace 装配，不进入 System。
主/子 run 必须显式传入实际 resolved model；`assistant_inspect` 显式解析目标助手的配置模型。
不允许用缺省或可空模型开启更宽的工具集。`AssistantToolFactory` 的委派与工具集依赖均为必填构造参数。
Target Run 在 START 前只装配一次工具集合，并永久过滤 `assistant_manage`、`assistant_inspect`、`assistant_call` 以及历史名
`assistant_memory_list`，保留并桥接 `ask_user`。`generate_image` 不再永久过滤：Target 已开启 `TextToImage` 且默认文生图模型可解析时才会注册。

`freezeToolSet` 在 START 前拒绝空名和重名，并从同一有序列表物化 Provider 唯一可见的 `FrozenToolDefinition` 与执行专用
`ToolExecutionBinding` 索引。`Tool.freeze()` 在唯一求值点立即将 parameters JSON serialize→parse，脱离工具可能持有的 mutable backing map；`systemPromptContribution` 同时按值捕获。Master/Target 的后续 Provider step、审批 continuation 与 `ask_user` continuation 都复用同一对象；
配置变化只影响下一次新 START。工具执行前仍由 owner 重验权限、文件/Workspace/MCP 资源、Memory namespace 与远端状态，撤销时
live fail-closed，但不得借此改写当前 Turn 的工具名称、描述、Schema 或顺序。
所有工具先校验合法 JSON object，再调用自身纯参数校验；空参数缓冲按无参 `{}` 解释，非空损坏 JSON 不当成空 object，也不先询问用户。
纯参数校验只返回领域错误，`ToolArgumentsException` 保留其字段并补齐 `error` 与 `type:"error"`。工具撤销或审批不可用
同样带标准错误标记，使历史重读仍显示 FAILED；未执行的拒绝不创建 `tool_execution` 记录或伪造 STARTED。
正常执行返回的领域失败保持原有信封，不因此变成执行失败。MCP 本地只检查 JSON object，远端 schema 与业务校验仍归 Server。

下列 description 为源码中的英文原文（动态日期/时区用占位标明）。

### `search_web`

启用：`shouldUseExternalWebSearch(assistant, model)`。助手打开外挂搜索，且当前模型未带 `BuiltInTools.Search`。

描述同样是常量文本，不再内嵌当前日期（与 `memory_tool` 同一条缓存约束）。

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
（含 `assistant_inspect` 的 `ToolSetRunMode.TARGET`）在配置满足时都会注册。
`set_as_background=true` 仍须审批；Target 非交互下自动拒绝，返回
`tool_not_permitted` + `approval_unavailable`，语义为“需要审批但当前运行环境无法
提供审批，不要原样重试”。该错误只拒绝当前 ToolCall，Target 可调整参数后继续运行。

> Generate one image from a text prompt, show it to the user, and return a local path that follow-up tools can use.
> Failures return a stable reason and a short detail when available.

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
`{"status":"failed","reason":"invalid_arguments","error":"invalid_arguments","type":"error"}`。

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

失败结果只有 Text part，带稳定 `reason`。本地前置失败没有 `detail`：
`invalid_arguments`、`image_model_unavailable`、`tool_revoked`、`image_model_changed`、
`assistant_not_found`（执行前发现会话所属 Assistant 已删除）。

工具在当前运行中未注册时，返回 `tool_not_available`（结构化 JSON，含 `tool` 名称与
`message`），不抛内部异常。模型收到后不应原样重试。
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
推理」——`OFF` 在 Gemini 3 系列上会映射为 `thinkingLevel = "minimal"`，Gemini 3.7 Flash
不支持该档位（显式设置返回 API 校验错误）。Provider 异常经统一分类器 `classifyProviderFailure`
映射为细分 `reason` 并附 sanitized `detail` 诊断文本（与 `generate_image` / `assistant_call`
的失败契约一致）；原始异常写入 logcat。

识别调用由工具自身持有 Provider 请求边界，不经过 `GenerationLoop`。工具构造时
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
| `inspection_failed` | 识别输出为空（兜底，无 `detail`） |

媒体资源引用（全工具链统一语义）：

```text
attachment:<uuid>   仅内部持久化逻辑身份，不作为模型披露或工具输入
/upload/<file>      托管附件的模型文件路径；识别与委托不依赖 workspace
/workspace/...      工作产物区。会话共享的只读文件（上传与生成的媒体）挂载在 /upload
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

启用：`LocalToolOption.AskUser`。`interactionRequirement=UserInput`；合法调用经统一 ToolCallRuntime 暂停并由 typed `Answer` 决定回填结果，`execute` 不直接跑。
问题字段不合法时在审批门口直接失败：写入
`{"error":"invalid_arguments","field":"...","expected":"...","type":"error"}`，不 Pending、不自动执行。
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

启用：`assistant.enableMemory`。由 `GenerationLoop` 按 owner namespace 构建。

描述是常量文本：工具名称、描述、Schema 与列表排序共同构成 Provider 请求的可缓存前缀，
描述里没有当前日期（原先的 `Today is ...` 会让整条前缀每天被击穿一次）。
当前时间只由 `TimeReminderTransformer` 与 `{{cur_date}}` 负责。
Memory 行的顺序由 DAO 的 `ORDER BY id ASC` 固定，读取路径不再依赖 SQLite 的默认返回次序。

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

> Create, update, or delete a sub-assistant (sub-agent). New ones join your allowed list.

| 参数 | description |
|------|-------------|
| `action` | CREATE, UPDATE, or DELETE. |
| `assistant_id` | Required for UPDATE and DELETE. |
| `name` | Display name. Required and non-empty for CREATE; optional replacement for UPDATE. |
| `description` | Specialty and when to call it. Required and non-empty for CREATE; optional replacement for UPDATE. Not a system prompt. |
| `instructions` | System prompt for the sub-assistant: role, method, output style. Required and non-empty for CREATE; optional replacement for UPDATE. Do not invent tools or skills. |

成功只回 `action` 与 `id`，不回显 `name` / `description` / `instructions`。DELETE 可带 `cleanup_pending`。

### `assistant_inspect`

启用：同上。只读。历史工具名 `assistant_memory_list` 不再注册。

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

启用：`LocalToolOption.AssistantDelegation`。同步：返回前主助手当前 Tool Loop 不继续。

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
`artifacts_omitted`。其他终态带稳定 `reason`。`content_blocked`、`provider_error` 与 `runtime_error` 另带提炼后的 `detail`。`provider_error` / `runtime_error` 含单行异常类型与消息（按字符上限裁剪，不含因果链和堆栈）；`content_blocked` 使用稳定政策说明，不回传检查类型（含 OpenAI `content_filter`）。

默认清单是 Text JSON，没有 Image 或 `[Attachment ...]` 事实行。需要后续检查时，可以直接使用 `artifacts[].path`
调用识图；无需重跑子助手。`extras` 只在原委托调用时选择内容，不是事后补取接口。

已跑过 Child 且调用过 `text_to_speech` 时，默认另带精简 `tts_stats`（`calls` 次数、`chars` 朗读字符合计）。体积较大或需 Caller 主动取回的段只在 `extras` 点名后返回：

- `artifacts`：把本次可持久化 Image 交付物追加为带 stable `attachment_ref` 的 Image parts。它不在这里做能力判断或视觉识别；Caller 下一次请求统一由 `AttachmentProjectionTransformer` 按当次 resolved model 投影为原图 + `input=native` 事实，或仅 `input=reference_only` 事实。需要视觉细节时由模型显式调用 `inspect_attachments`。没有 `DERIVED`、自动 observation 或 `artifact_delivery` 字段。
- `tool_calls`：本次 run 范围内每个工具的发出次数（`header+rows`，首次出现序）
- `tts`：按调用顺序的朗读文本表

`unavailable` 不加这些段。通过共用 JSON object 校验后的委托领域参数错误也使用同一信封（`status=unavailable` + `reason`）。附件相关 reason：`invalid_attachments`、`attachment_not_found`、`unsupported_attachment_type`、`attachment_too_large`、`attachment_read_failed`。聊天模型 / Target 不接收 IMAGE 本身不是 attachment failure。

`content` 取本次 run 范围内：优先最后一条 ASSISTANT 在最后一个工作工具之后的顶层 Text；
`text_to_speech` 不算工作工具；最后一步为空则回退更早 step，再回退最后一段 Text island。

### `mcp__<server>__<tool>`

启用：Assistant 已选择该 server，definition 当前 enabled，并存在 definition digest 匹配的完整非空 LKG Catalog；
当前连接健康不参与 schema 注入。名称、description、参数来自 run 开始时冻结的 `TurnMcpCapabilitySnapshot`；Settings
只保存 enable/approval policy，不保存远端 schema。输入参数以完整 JSON Schema 文档保存，`$schema`、`$defs`、`$ref`
和未知扩展不会丢失。

用户手工刷新与 `notifications/tools/list_changed` 成功提交后只更新后续 turn；同一 run 继续使用启动 revision。

失败结果只包含调用决策所需的 `status`、稳定 `reason` 和真正增加信息时的短 `message`：撤销返回
`unavailable/tool_unavailable`；无 live session 返回 `unavailable/server_unavailable` 并触发内部恢复；需要用户授权返回
`unavailable/authorization_required`；server 未声明 tools capability 或完整响应无法投影返回
`failed/protocol_incompatible`；远端 `CallToolResult.isError` 或明确 MCP error 返回 `failed/remote_error` 并保留服务端
content、`structured_content` 或经裁剪的 message；调用承诺后未取得可确认结果返回 `unknown/outcome_unknown`。
客户端不声称本地 commitment 等于网络请求已发送，也不自动重放 unknown 调用。server/tool、transport、generation、SDK/HTTP detail、
`retryable` 和 `request_sent` 不进入 Agent 输出。

成功 `TextContent` 进文本，`ImageContent` 先取得 Artifact lease 再转 Image part，成功 `structuredContent` 也进入工具结果。
调用总时限 120 秒，取消向上传播。

---

## 6. 工具输出归档与回查

所有本地、Workspace、Memory、Assistant 与 MCP 工具先由 `GenerationToolSetFactory` 装配，再统一经过
`ToolCallRuntime.prepareBatch` / `execute`。Runtime 对同一连续调用只解析一次 JSON object，并集中处理 definition lookup、
纯参数校验、typed interaction gate、timeout/异常/空结果规范化；`GenerationLoop` 只编排 Provider step、批次屏障、顺序和 checkpoint。

`ToolOutputPolicy.ARCHIVABLE_TEXT` 表示纯文本结果在一次成功 Provider 请求实际读取后可由
`ConversationContextPlanner` 滚动归档；`REGENERABLE_TEXT` 表示派生文本可滚动折叠，但不得复制为新 Artifact。
两者都不表示工具返回时立即裁剪。归档内容由 `ToolOutputStore` 通过 `ArtifactStore` 保存为 `TOOL_OUTPUT` reference；
可再生结果只留下 `[Derived tool result folded]`，原 Tool 输入仍保留。`PRESERVE`、混合媒体和 Provider opaque replay 始终完整保留。
所有工具结果都经过同一 Planner，但只有 runtime metadata 为可压缩纯文本、显式 `completed` / `failed`，且
`originalEstimatedTokens - markerEstimatedTokens >= 128` 时才成为候选；`denied`、`answered` 和缺失终态不压缩。
只要调用登记过 unpublished Artifact，Runtime 会在成功和失败收口时统一强制 `PRESERVE`；文生图、MCP/Workspace 图片以及
带 `artifacts` manifest 的 `assistant_call` 不依赖普通文本候选规则保存交付引用。
`assistant_call` 静态默认仍为 `PRESERVE`；只有单 Text、`status=completed`、`assistant_name` / `content` 为字符串且没有
`artifacts` manifest 的结果，才会在 Runtime 收口时解析为 `ARCHIVABLE_TEXT`。带交付 manifest、混合媒体、非完成态或
损坏结果继续保留。
压缩只由 inline Tool 文本达到 48K estimated tokens 触发，尽量降到 16K，整批至少净回收 24K estimated tokens；
最近两个 typed 批次和最近 8K estimated tokens 受保护，不额外冻结整个已完成 USER turn。估算规则按每个 Tool Result
独立计算：ASCII 字母与空白约每 4 个 code point 1 token，连续 ASCII 数字段约每 3 位 1 token，连续 ASCII 符号段约每 2 个 1 token，其他 Unicode code point 各计 1。阈值只来自 `ContextTrimmingPolicy.kt`，
Provider input token 与 cache 百分比都不参与决策。

`read_tool_output` 与 `grep_tool_output` 始终随 Master 和 Target 注册，均为无交互、`REGENERABLE_TEXT`、32 KiB 有界结果。
它们接受 marker 中的正整数 `ref`，但每次读取仍必须验证当前 conversation 的 `TOOL_OUTPUT` reference；
`read_tool_output` 使用 `start` / `limit`，每次最多 500 个稳定虚拟行；超长物理行按最多 4096 个 Unicode code point
切分，不会拆开 emoji 等辅助平面字符。`grep_tool_output` 使用 RE2/J 逐行匹配，
支持常用分组、交替、字符类、锚点和量词，不支持 lookaround 与 backreference；`context` 最多 5，`limit` 最多 100。
成功结果为带编号的短小纯文本，不回显 ref、pattern 或可选入参，最终 UTF-8 输出不超过 32 KiB。其结果仍参与滚动压缩，
但折叠不创建新 Artifact 或 ref，避免形成读取切片的复制链。
ref 不授予权限，也不会暴露 relative path、`file://` 或 App 私有路径。原 ref 对应的归档正文保持不可变；回查结果满足
统一阈值后只折叠 marker，不再归档，不建立复制链或递归读取协议。

内建工具成功时直接返回领域数据，由 Runtime metadata 提交 `completed`；通用领域失败通过 `ToolExecutionFailure` 返回短小
`status=failed` / `reason` / 可选 `detail`，并提交 `failed`。参数校验拒绝、`denied`、`answered` 不伪装成执行失败。
MCP 远端正文保持原协议，不强制套用内建信封，但其 typed 终态、输出策略与裁剪资格仍由同一 Runtime/Planner 决定。

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
| `assistant_manage` | `action` + `id`（DELETE 可加 `cleanup_pending`） |
| `generate_image` | bounded JSON + Image part；成功不回显 prompt |

`clipboard_tool` read 的 `text`、`assistant_call` 的 `content` 是新数据，不是回显。
