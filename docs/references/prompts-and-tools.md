# 模型输入格式与工具契约

本文维护模型实际接收的提示结构、工具参数的非显然语义和结果协议。完整英文 description、JSON Schema、
字段默认值以工具工厂源码为准；配置开关见 [助手配置](assistant-configuration.md)。
[请求上下文](request-context.md)负责窗口、冻结、状态对账、请求接纳与历史回放；
[Turn/Step 执行](turn-step-execution.md)负责审批、执行、检查点和取消。本文不另建生命周期规则。

## 1. 请求输入的组装

`TurnContextFactory.materialize` 通过 `freezeTurnSystem` 生成完整 System，`StepRunner.generateInternal`
使用其文本。工具 name、description、parameters 与 System contribution 由 `Tool.freeze()` 固定；
Schema 随 `TextGenerationParams.tools` 发送，不拼进 System 正文。

```text
System
  1. BEFORE_SYSTEM_PROMPT 规则
  2. 合法非空会话覆盖 → 同一助手适用的 opening → Assistant.systemPrompt
  3. APPLICATION_CONTEXT_RULES 与 MODEL_RULES
  4. FrozenToolDefinition.systemPromptContribution
  5. 已捕获的 Workspace 说明
  6. AFTER_SYSTEM_PROMPT 规则

选中分支经请求窗口处理后
  → TimeReminderTransformer
  → PromptInjectionTransformer
  → PlaceholderTransformer
  → DocumentAsPromptTransformer
  → TemplateTransformer
  → ToolArtifactReplayTransformer（主助手）
  → AttachmentProjectionTransformer
  → TurnRequestAdmission / applyContextProjections
```

`APPLICATION_CONTEXT_RULES` 与 `MODEL_RULES` 不随本 Step 是否新增状态披露而变化。
主助手与子助手共用 `TurnPipelineFactory`，子助手管线不包含 `ToolArtifactReplayTransformer`。
输出管线依次为 `ThinkTagTransformer`、`Base64ImageToLocalFileTransformer`、`RegexOutputTransformer`，只处理当前 open Step；
采样提交后的工具阶段不再次转换正文。`ThinkTagTransformer` 仅在本 Step 没有 Provider 原生 Reasoning 时，
解释首个非空 Text 开头的 `<think>`：从 Step 开始时间计时，首次闭合时固定结束时间，流结束只补未闭合时间。
生成文件的提交与回滚仍沿[Turn/Step 执行](turn-step-execution.md)的资源交接。
工具操作规则由 description/参数说明提供，状态由结构化上下文提供，不把相同规则重复加入两处。

## 2. 模板、位置与字面内容

### 占位符与消息模板

`renderPromptPlaceholders` 不区分占位符大小写，接受 `{key}` / `{{key}}`，单次匹配替换，
不递归解释替换值。`TurnContextFactory` 提供的值如下：

| 变量 | 来源 |
| --- | --- |
| `char`、`description` | 助手名称与描述；空名称使用 `assistant` |
| `user` / `nickname` | 用户昵称，空值使用 `user` |
| `model_name`、`model_id` | 模型显示名与模型 ID |
| `cur_date` | 捕获时的本地日期；旧 `cur_time` / `cur_datetime` 同样使用日期值 |
| `locale`、`timezone` | 捕获的语言与时区显示名 |
| `system_version`、`device_info` | Android 版本、设备品牌与型号 |

新建助手默认模板由 `Assistant.kt` 的 `DEFAULT_SYSTEM_PROMPT` 维护。选中的领域 System 模板只渲染一次；
System 前后规则使用已渲染内容，工具贡献和 Workspace 说明按字面拼接。`DefaultPlaceholderProvider`
只服务编辑器变量展示，不参与实际请求取值。

Pebble `messageTemplate` 提供 `message`、`role`、`time`、`date`、`description`；其中时间来自该消息，
不是模型回复时刻。默认 `{{ message }}` 原样输出。普通持久消息按配置变换，Tool input/output 不递归渲染。
System、规则、状态包、时间提醒、文档正文、Starter 背景及摘要使用登记的来源跳过二次解释；
同一 USER 内的用户文本仍可应用模板。`RequestMessageOriginTracker` 的标记只属于本次投影，
复制 part 时通过 `transferPartSource` 交接，不把对象身份当作持久标识。

### 提示规则的位置

规则选择集与开关语义见 [助手配置](assistant-configuration.md)。`PromptInjectionTransformer` 在同一份
保留历史上计算位置，不让先插入的合成消息影响后续 depth；System、请求合成消息及仅含 Step 的占位消息不计数，
预置消息和摘要仍计入。`TOP_OF_CHAT` 在首条历史 USER 前，无 USER 时在历史末尾；
`BOTTOM_OF_CHAT` 在末条历史消息前；`AT_DEPTH` 从历史末尾计数，至少为 1，超范围取历史起点。
工具批次的安全边界由 `findSafeInsertIndex` 调整，规则也不能拆开时间提醒与其 USER。

同一位置按 priority 降序，同优先级保持目录顺序；仅相邻同 role 规则合并为消息，规则仍各占一个 Text part。
System 前后规则由 `freezeTurnSystem` 处理，不使用规则配置的 role，但在来源中保留该值。

## 3. 结构化上下文格式

### 状态披露

`ConversationDisclosureSnapshotService` 生成固定键序的紧凑 JSON，以下仅为便于阅读的排版：

```json
{
  "type": "conversation_disclosure_snapshot",
  "format": 3,
  "memory": { "enabled": false, "scope": "disabled", "header": ["id", "content"], "rows": [] },
  "sub_assistants": { "mode": "disabled", "header": ["id", "name", "description"], "rows": [] },
  "enterprise_memory_seeds": { "header": ["id", "content"], "rows": [] }
}
```

format 3 仅携带本次需要披露的完整分区：缺省表示本包未涉及，空 rows 表示该分区为空；不能混淆。
不加入捕获时间、Locale 或 revision。Memory scope 为 `local/global/disabled`；子助手 mode 为
`management_only/delegation_only/both/disabled`。Memory 按行 ID 排序，可见子助手排除调用者并按完整配置引用排序，
Seed 保留企业绑定顺序。Seed 的 ID 是企业资源引用，不是 `memory_tool` 可写的整数 ID，关闭运行记忆不会移除 Seed。
子助手目录只提供路由所需三列，详细配置通过 `assistant_inspect` 读取。

首个 Step 的新披露放在因果 USER 的前置 Text part；后续 Step 的变化放在完整工具结果批次之后。
披露内容不授予执行权限。变化判定、历史格式兼容和大小限制统一见 [请求上下文](request-context.md)。

### Starter 背景与摘要

`TurnRequestAdmission` 将 Starter 的有序背景编码为：

```json
{"type":"starter_context","format":1,"blocks":[{"id":"…","title":"…","content":"…"}]}
```

空背景不构造包。背景置于适用窗口首个真实 USER 的原输入 parts 前；与新状态披露定位到同一 USER 时，
披露先于背景，时间提醒位于该 USER 前。背景为字面数据，不执行占位符或消息模板。

手动摘要使用 `{"type":"conversation_history_summary","format":1,"content":"…"}`，
来源关联摘要自身持久消息；普通 UI 仍显示摘要原文。Starter 选择和首发见 [配置架构](android-configuration-architecture.md)，
摘要的持久化与窗口规则见 [请求上下文](request-context.md)。

### 时间与文档

开启时间提醒时，`TimeReminderTransformer` 使用真实 USER 的 createdAt、前驱和捕获/已接纳时区，
不使用模型响应时钟。没有已知真实 USER 前驱，或与前驱间隔超过一小时的 USER 前插入提醒：

```text
<time_reminder>Message time: 2026-09-26T10:00:00+08:00</time_reminder>
<time_reminder>Message time: 2026-09-26T12:00:00+08:00; gap: 2 h</time_reminder>
```

gap 小于一天取整数 h，达到一天取整数 d；助手、工具和应用合成 USER 不重置间隔。
原时间解释与窗口变化的关系见请求上下文中的时间来源规则。

`DocumentAsPromptTransformer` 在每个原 Document 前放入 `<UploadFile name="..." path="...">` 正文，
保持附件顺序，仅在存在实际工具路径时提供 path。属性转义引号、尖括号、& 和控制空白；正文围栏不少于
三个反引号，且长于正文最长连续反引号。正文不执行模板；读取经 `ArtifactReadLease`，解析失败明确报错，
不伪装成成功文件背景。图片的 `[Attachment ...]` 格式、媒体能力与路径规则由
[多模态资源](multimodal-context-and-turn-durability.md)统一维护。

### 工具对 System 的固定贡献

- `use_skill` 提供 `**Skills**` 与 `<available_skills><skill><name>…</name><description>…</description></skill></available_skills>`。
  仅模型可见 name/description 转义 `&<>`，description 先按 1024 个 Unicode code point 截取，不拆 surrogate pair 或 XML entity。
  磁盘正文和 canonical name 不变，读取仍精确匹配原名称；转义不代表消除了所有指令注入。
- `text_to_speech` 使用 `TTSManager.getPromptGuidance()`，无指导时为空。
- `generate_image` 由 `ImageGenerationModelDescriptor` 提供 `provider_type/provider_name/model_id/model_name`，
  不包含凭据、endpoint 或 custom headers/body，也不据此声明聊天模型的视觉能力。
- 绑定且 READY 的 Workspace 使用 `buildWorkspacePrompt` 生成 `<workspace>`，说明工具分工和路径规则，不包含 cwd。
  指引要求按需读取 `/root/.agents/AGENTS.md`、`/workspace/AGENTS.md` 及项目指引，不自动把文件加入 System；
  缺失文件为可选，文件指引不能覆盖用户意图或工具权限。

## 4. 工具接口中的关键语义

`TurnToolSetFactory` 负责工具集合，`freezeToolSet` 拒绝空名/重名并固定 definitions/bindings。
下文按工具族保留不宜只凭 Schema 推断的行为；完整参数类型、默认值及数量上限由相应工厂维护。

### 搜索与历史查询

| 注册名 | 必须保留的语义 |
| --- | --- |
| `search_web` | 仅外挂搜索模式装配，模型自带 `BuiltInTools.Search` 时不重复装配；结果包含 `items` 与可选 `answer/images`，引用使用真实结果 ID |
| `scrape_web` | 只有当前搜索服务提供抓取能力时装配，读取指定 URL |
| `recent_chats` | 读取当前助手的顶层会话标题与时间摘要，不加载消息树 |
| `conversation_search` | 与 recent_chats 共用范围，不检索其他助手或 Child 会话 |

`search_web` 的固定指引要求时效问题核验发布日期和事件发生日期；检索顺序与抓取时间不证明新鲜度，
缺日期或一手证据时继续检索/读取来源，不伪造 SDK 日期字段。服务拒绝与意外异常按公共错误协议区分。

### 图片生成与按需识别

`generate_image` 在开启 TextToImage 且本域生图配置可用时装配；主助手与子助手共用规则。
纯生成不审批，`set_as_background=true` 必须审批。子助手不能提供该审批时返回
`tool_not_permitted` / `approval_unavailable`，只拒绝当前调用；空 prompt 或非法 boolean 在审批前拒绝。
成功返回不回显 prompt 的 JSON 加 Image part，策略为 `PRESERVE`：

```json
{"status":"completed","file":{"path":"/upload/7ka2b9.png","mime_type":"image/png"},"background":{"requested":false,"updated":false}}
```

`file.path` 是模型的文件访问标识；内部 attachment 身份留在 metadata，不泄露 host path、file URI 或内部相对路径。
历史复制/恢复通过 Artifact metadata 重写路径。聊天副本缺失时移除 Image，file 标记
`available=false, reason=artifact_missing` 并移除不可读路径，保留原执行 status。
背景设置失败不回滚已进入图库的图片：生成仍完成，`background.updated=false`，并记录
`assistant_not_found/background_copy_failed/settings_write_failed`。

生图前置失败包括 `image_model_unavailable/tool_revoked/image_model_changed/assistant_not_found`；
HTTP 成功但无图片为 `invalid_result`，本地保存失败为 `persistence_error`。Provider 失败分类见下一节。

`inspect_attachments` 使用独立捕获的识图模型，不要求主模型缺少视觉能力或会话已经包含图片。
`attachments` 为 1–4 个 `/upload/<file>`，顺序和重复项对应保留；`request` 说明所需事实和输出形式。
不接受 UUID、HTTP(S)、file URI、裸文件名或 Workspace 路径，不要求当前分支引用。
识图模型只接收固定 System、按序 `[Image N path=...]` 与图片、最后的 request，不携带主会话历史。
成功返回普通 Text，不回传图片字节；空输出为 `inspection_failed`。

识图与委托共用的文件拒绝码为 `invalid_attachments`、`attachment_not_found`、
`unsupported_attachment_type`、`attachment_too_large`、`attachment_read_failed`；未提供解析能力为
`attachment_resolution_unavailable`。模型不接收 IMAGE 本身不是附件读取失败。
捕获模型、规范化内存图片与授权读取的实现统一见 [多模态资源](multimodal-context-and-turn-durability.md)。

### 记忆与子助手

`memory_tool` 在 START 按捕获的 MemoryAddress 构建，执行仍重验当前地址及权限。
create 只返回 `id`，edit/delete 返回 `success+id`；正文已在 input，不重复回显。
成功写结果通过 `successfulOutputPolicy` 保持 `PRESERVE`。更新/删除影响零行时返回
`memory_not_found_in_namespace`，不能凭历史披露扩大可写范围。工具效果如何参与状态对账由请求上下文维护。

`AssistantToolFactory` 提供以下工具；访问与单层委托、Child 生命周期统一见
[子助手架构](sub-assistant-architecture.md)。子助手不能再次装配管理/委托工具。

| 注册名 | 参数及结果的非显然语义 |
| --- | --- |
| `assistant_manage` | CREATE 无需审批，UPDATE/DELETE 必须审批；description 是路由描述，instructions 是 System。成功返回 action+id；仅 touched 字段的实际提交值不同于原始 input 时返回 applied，不在提交后重读配置；DELETE 可带 cleanup_pending。成功写结果 PRESERVE |
| `assistant_inspect` | 只读，不能检查调用者自己；默认 profile，按 sections 读取 tools/skills/memory。tools 是 Target 此刻可注册的工具名；memory 仅 local 返回 rows，不泄露共享记忆正文 |
| `assistant_call` | request 必须自包含：Target 看不到主聊天。attachments 显式传本任务需要的图片路径；extras 在本次调用选择，不能事后补取；返回前父工具批次等待 Child |

`assistant_call` 的 content 只取本次 run 最后一条 Assistant 的最终 Step 之后的顶层 Text，
没有 Final Step 或正文时为空，不回退工具前文字或更早 Step。completed 结果包含
`status/assistant_name/content`，必要时有 `has_non_text_output`；有持久交付物时始终返回轻量
`artifacts[].path/type/mime`，超出交付上限时记录 `artifacts_omitted`。

默认清单只有 Text JSON，没有 Image 或附件事实行；Caller 可把合法路径交给 `inspect_attachments`，无需重跑子助手。
`extras=artifacts` 追加可持久化 Image，下一请求按 Caller 的 Tool.output 能力与文件当前可读性投影；缺失文件不因模型支持图片而恢复可读，它也不触发自动识图。
有朗读调用时默认返回 `tts_stats`，`extras=tts` 返回朗读文本，`extras=tool_calls` 返回本次 run 工具计数。
非 completed 终态不返回交付物清单；失败保持公共 status/reason，不能把 Child 失败伪装为成功。

### 本地交互、执行与文件

| 注册名 | 非显然行为或结果 |
| --- | --- |
| `ask_user` | typed UserInput 暂停，结果由 Answer 提交，execute 不直接执行；options 是字符串数组。text/single/multi 都允许自由文本，空白不是答案；Target 经父调用卡片桥接 |
| `get_time_info` | 读取调用时设备时间；与消息 createdAt 派生的时间提醒不同 |
| `get_screen_time`、`calendar_query` | begin/end 接受日期、本地/偏移时间或 epoch 毫秒；提供 begin 时优先于 range，时间参数先于权限动作校验 |
| `calendar_create` | 需要审批；START 捕获时区，校验与执行共用。返回 event_id 和规范化 start/end，系统权限与实际插入归执行阶段 |
| `text_to_speech` | 返回 success 表示已提交朗读，播放可继续；语音停止及清理见 [语音架构](speech-architecture.md) |
| `clipboard_tool` | read 返回新 text，write 只返回 success，不回显写入正文 |
| `workspace_read_file`、`workspace_write_file`、`workspace_edit_file`、`workspace_shell` | 文件工具使用 Rootfs 绝对路径，shell cwd 相对 /workspace；写/编辑只返回结果及元数据，diff 仅进入 UI metadata。审批、安全路径与 uploads 副本见 [Workspace](workspace-architecture.md) |
| `use_skill` | name 按 canonical name 精确匹配；省略 path 读 SKILL.md，工具说明要求仅使用正文 Markdown 链接给出的相对路径。返回文件原文，严格 UTF-8 和大小限制由 SkillManager 校验 |

`eval_javascript` 在每次调用独立的 QuickJS runtime 执行，不提供 DOM、Node.js 或网络 fetch。
成功输出为真实换行的 `[console]` 段及最后的 `[result]` 段，便于归档后按行回查，不再套成 JSON 字符串。
`evaluateJavascript` 的调用期限、原生 deadline、内存/栈与输出限制覆盖执行、getter/toJSON、stringify 和 console 格式化；
取消在实际原生执行停止后关闭 runtime。超时/结果超限提交 `javascript_timeout/javascript_output_limit`，
不是成功文本里的错误；其他异常沿 `runtime_error` 保留诊断。具体限额以源码常量为准。

### MCP

`mcp__<server>__<tool>` 的名称、description 和完整 JSON Schema 来自冻结的 `TurnMcpCapabilitySnapshot`；
Settings 只保存选择及工具策略，不保存远端 Schema。完整 Schema 保留 `$schema/$defs/$ref` 和未知扩展。
完整且匹配 definition 的 LKG Catalog 决定能否装配，当前连接健康不参与 Schema 注入。
成功 TextContent、ImageContent、structuredContent 进入工具结果；图片先通过 Artifact 资源交接。
连接、刷新和调用承诺边界由 [MCP 架构](mcp-architecture.md)维护，模型可见失败见下一节。

## 5. 工具错误返回协议

`ToolErrorProtocol` 定义工具失败的公共 JSON 信封，放在 Text part 中；成功结果保留领域形状。durable 终态由 `ToolResultStatus`
和执行记录决定，不能只从结果正文推断执行是否发生。

```json
{"status":"failed","reason":"memory_not_found_in_namespace","detail":"Memory 111 does not exist in the current namespace. Do not retry this ID unchanged."}
```

| 字段 | 合同 |
| --- | --- |
| status | failed：执行/业务失败；unavailable：已确认当前不能调用；unknown：调用已承诺但结果无法确认，不能盲重试 |
| reason | 必有且稳定的小写下划线代码，只表达已确认类别，不使用异常 message 或动态资源名充当代码 |
| detail | 仅在修正动作或异常诊断需要时提供；App 生成值须非空、单行，先脱敏和压平空白，再限制为 128 个 Unicode code point（含省略号） |

领域字段可与公共字段并存，如 Shell stderr、子助手摘要，但不能覆盖公共语义。App 不另建
error/type/message 平行字段；远端 MCP 正文不受本地 detail 长度限制。
参数纯校验在审批前返回同一信封，不创建 execution；用户拒绝和 ask_user 回答使用 typed interaction 终态。
取消向上传播，不生成失败结果。

### 分类与诊断

已知参数错误使用 `invalid_arguments`，不存在的资源使用对应领域 not found 代码；未知读取异常不能冒充权限拒绝。
Workspace 不存在、未 ready、企业访问撤销分别为 `workspace_unavailable/workspace_not_ready/tool_not_permitted`；
Shell 非零退出和超时分别为 `shell_exit_nonzero/shell_timeout`，仍保留命令输出。
归档 ref 格式错误为 `invalid_arguments`，合法但不可用为 `archive_unavailable`。

`classifyProviderFailure` 统一生图、识图和委托的模型调用错误：

| reason | 含义 |
| --- | --- |
| content_blocked | 政策拒绝；detail 使用稳定说明，不回传内部检查类型 |
| rate_limited / quota_exhausted | 速率限制 / 额度或余额不足，不能混淆 |
| auth_failed / permission_denied | 认证失败 / 账号或资源权限不足 |
| invalid_request | 服务明确拒绝请求参数 |
| provider_unavailable | 超时、过载或服务端暂不可用 |
| provider_error | 已识别 HTTP 失败但无法进一步分类 |
| runtime_error | 未分类的本地实现异常 |

搜索 HTTP 拒绝使用 `invalid_request/auth_failed/rate_limited/search_provider_error`，detail 保留服务名与 HTTP 状态。
未预期实现异常的 runtime_error detail 保留异常类型及 cause 链最深非空 message；无 message 至少保留类型。
完整 cause/堆栈记录到诊断日志。checkpoint、资源登记等 Runtime 基础设施异常继续向 Turn owner 传播。
只脱敏凭据和明确隐私内容，不删除定位所需的非敏感细节；不可解析响应只取有意义的短错误字段，不回传 HTML、乱码或内联 base64。

历史媒体未能持久化时，回放使用 `unavailable/media_persistence_failed` 表达不可读，不倒改原执行终态。
具体错误必须保留，不用 tool_failed、operation_failed 或固定“稍后重试”替代。

### MCP 阶段分类

| 已确认事实 | status / reason |
| --- | --- |
| 本地定义或工具撤销 | unavailable/tool_unavailable |
| 调用前无可用 session | unavailable/server_unavailable |
| 调用前需要用户授权 | unavailable/authorization_required |
| 明确 MCP error 或 isError | failed/remote_error |
| 结果违反 MCP 内容协议 | failed/protocol_incompatible |
| 已收到结果但本地投影/保存失败 | failed/result_processing_failed |
| 调用承诺后无可确认结果 | unknown/outcome_unknown |

remote_error 保留原文本及 structuredContent，只有没有正文时才补有界远端 message；错误图片等非文本内容仅标记省略，
不写入 Artifact。result_processing_failed 说明本地异常并提示核实远端副作用，outcome_unknown 提醒先核实、勿盲重试。
SDK `McpException` 也可表示本地关闭或超时，不能只凭类型声称服务端错误。客户端 commitment 不等于字节已发送。
server/tool、transport、generation、retryable、request_sent 不进入模型错误结果。

## 6. 结果保留、归档与回查

结果仅返回 input 尚未表达的执行结果或实际差异。写记忆、剪贴板、文件和助手配置不重复回显原正文；
assistant_manage 的 applied 只返回实际规范化差异，clipboard read 的 text 和 assistant_call 的 content 属于新数据。

压缩策略、marker 格式、预算与事务交接统一见[请求上下文](request-context.md)；本文维护具体工具的结果保留资格与回查协议。

登记过未发布 Artifact 的结果成功或失败均强制 PRESERVE。assistant_call 默认 PRESERVE，只有单 Text、
completed、assistant_name/content 为字符串且没有 artifacts manifest 的结果才可归档；带交付物、混合媒体、
非完成态或损坏结果继续保留。

`read_tool_output` 与 `grep_tool_output` 始终随主/子助手装配，无交互且结果为 REGENERABLE_TEXT。
ref 是 marker 内的正整数，每次读取仍验证当前会话 TOOL_OUTPUT 引用；不是授权，也不泄露磁盘路径。
read 用从 1 开始的 start/limit 读取稳定虚拟行，超长物理行按最多 4096 个 Unicode code point 分段，不拆单个 Unicode 码点。
grep 用 RE2/J 逐行匹配，不支持 lookaround/backreference；可返回邻近上下文。
两者返回带行号的纯文本，最终 UTF-8 不超过 32 KiB；分页/匹配上限由 `ToolOutputProtocol` 维护。
原归档不可变，回查结果再次压缩时只折叠，不建立切片副本、第二 ref 或递归归档链。
