# 子助手多模态：附件入站与交付物出站

子助手整体语义见 [sub-assistant-architecture.md](sub-assistant-architecture.md)，模型可见参数与结果见
[prompts-and-tools.md](prompts-and-tools.md)，通用投影与文件命名见
[multimodal-context-and-turn-durability.md](multimodal-context-and-turn-durability.md)。

当前附件输入与识图完整支持 Image；Document / Audio / Video 可以作为交付物清单展示，不因此获得识图能力。

## 1. 职责与边界

`assistant_call` 是同步委托。Target 在独立 Child Conversation 中复用正常的 `GenerationLoop`、
Transformer、Provider 和 checkpoint。Target 不读取 Master 历史，只接收 `request` 与显式指定的附件；
Child 可以继续使用自己的历史，但不继承 Master 的 `workspaceCwd`。

必须区分：

- Caller 决定任务相关性：给 Target 哪些图片，是否需要交付物内容。
- ArtifactStore 决定文件可用性：受管登记、生命周期、目录、大小和实际图片内容。
- Provider 能力决定请求表示：图片所在 USER / ASSISTANT / Tool.output 容器能否结构化编码。
- 用户可见性由 UI 查询投影负责：看得到缩略图不等于模型已收到像素。

不在 Resolver、投影器或 Coordinator 中自动做 OCR、图像描述或视觉判断。识别只由模型显式调用
`inspect_attachments` 发起；该工具不依赖工作区，也不因当前模型具有原生视觉能力而隐藏。

## 2. 对外路径与内部身份

| 边界 | 表达 | 职责 |
| --- | --- | --- |
| 模型可见图片位置 | `/upload/<file>`，字段名 `path` | 工具入参、附件事实行、生图结果、子助手清单 |
| 持久化逻辑身份 | `metadata.attachment_ref = "attachment:<uuid>"` | 内部索引、UI、clone/fork；不作为模型工具参数 |
| 文件事实 | `LocalArtifactRef` | 既有相对路径与 MIME，归 ArtifactStore |
| Workspace | `/workspace/...` | 工作区工具负责，不属于识图或委托附件参数 |

`/upload` 是应用受管文件的工具路径，不以 Workspace 的存在作为前提。准确路径可跨会话读取，
不要求当前分支曾引用该文件。仅磁盘存在、但没有 ACTIVE 登记或仍未发布的文件不可读。

文件名区分大小写，采用 `LocalToolPath.parseUploadToolPath` 的安全单层文件名语法。新文件使用无前缀短名；
旧 GUID 文件名继续按其原始完整路径使用。内部 UUID 与历史持久化不改写，不增加别名、参数兼容或名称推导。

## 3. 入站协议：Caller → Target

`assistant_call` 参数：

| 参数 | 含义 |
| --- | --- |
| `assistant_id` | Catalog id |
| `request` | 任务简报，必须提供所需事实和约束 |
| `attachments` | 可选图片文件路径数组；仅安全 `/upload/<file>`；最多 4 张 |
| `extras` | 可选 `artifacts` / `tts` / `tool_calls`，控制 Caller 额外接收的结果内容 |

`parseAssistantCallAttachments` 对缺省、JSON null、空数组返回无附件；其余要求全部为非空字符串和安全
upload 路径。trim 后按首次出现顺序去重，再检查最多 4 张。不接受 UUID、裸文件名、HTTP(S)、Android
file URI、base64 或 Workspace 路径。参数无效在创建 Child 前返回 `status=unavailable`。

文件路径可以来自用户直接提供、`[Attachment path=...]`、生图结果 `file.path` 或
子助手 `artifacts[].path`。Target 不会因为图片刚出现在 Master 界面就自动获得它。

### 3.1 文件读取与 Child 提交

`AttachmentResolver.withImages(paths, consume)` 通过既有 `ArtifactStore.withUploadImages` 读取：

1. ArtifactStore 在 lifecycle lock 内校验安全 upload 路径、ACTIVE 登记、已发布状态、实际文件存在和受管目录。
2. 同一锁内取得已有 retention pin；不新建持久化 lease、登记表或第二套生命周期。
3. 锁外有界读取，使用 `GeneratedMediaStore.MAX_IMAGE_BYTES` 限制大小，通过 `ImageMime` 校验实际图片。
4. Resolver 构造原文件 URI 的 Image parts 并为新逻辑附件盖章，不复制文件。
5. Consumer 在 retention 作用域内通过既有会话命令提交 Child USER。
6. 成功、失败或取消后 finally 释放 pin。提交成功的 Child 持久化 root 接管文件引用。

删除和 GC 必须尊重 retention pin；它不是长持有的 lifecycle lock。取消继续传播，不用临时文件或补写 Master
消息取得读取资格。读图不创建 `OwnedArtifact`；只有正常 Child clone 复制历史资源时才产生原有创建所有权。

新建、复用和克隆 Child 都写入：

```text
USER
├── Text(preprocessSubAssistantTask(request))
├── Image(原受管 file URI + metadata.attachment_ref)
└── Image(...)
```

文本预处理只作用于 request，不把路径、OCR 或图片内容拼入 System Prompt。Child 原样保存图片；
Target 当次模型不支持图片不是入站失败，后续请求由统一投影与按需识图能力处理。

### 3.2 失败语义

| reason | 含义 |
| --- | --- |
| `invalid_attachments` | 参数类型、数量或安全路径语法不合法 |
| `attachment_not_found` | 无 ACTIVE 已发布登记、文件缺失、越界或不可用 |
| `unsupported_attachment_type` | 实际内容不属于支持的图片，或明确为 PDF/音频/视频等类型 |
| `attachment_too_large` | 文件超过图片读取大小上限 |
| `attachment_read_failed` | 本地文件 IO 读取失败 |

任何一项失败都不注入部分图片、不开始 Target run。Resolver 不下载 URL；
`SafeRemoteMediaFetcher` 仍由其他需要下载的功能使用，不是附件工具的备用入口。

## 4. 三种来源的统一请求投影

`AttachmentProjectionTransformer` 是请求级、无状态、非破坏投影。它不创建文件、不改 durable metadata、
不替换 Provider 不透明状态，也不为子助手建立专用视觉流程。

| 图片来源 | 通用消息归属 | 能力字段 |
| --- | --- | --- |
| 用户上传或委托输入 | 原 USER parts | `userImages` |
| 工具产图或显式取回的 Child 图片 | 原 Tool.output | `toolOutputImages` |
| 模型原生产图 | 原 ASSISTANT parts | `assistantImages` |

每个来源独立判定：

- `STRUCTURED`：保留 Image，并前插 `[Attachment path=/upload/abc123.png type=image input=native]`。
- 其他能力、有可用路径：只保留 `[Attachment path=/upload/abc123.png type=image input=reference_only]`。
- 没有可用路径：省略 path，原生时为 `[Attachment type=image input=native]` 加图片，否则为
  `[Attachment type=image input=unavailable]`。

路径由 ArtifactStore 校验后从 `LocalArtifactRef.toolPath()` 派生，不以 `attachment_ref` 存在为前提。
远程或非 upload 资源不暴露内部 UUID，也不伪造本地路径。事实行不重复 name，不带行为指令；
Document / Audio / Video 只在有路径时添加事实行，并保留原 part。

四种协议各自编码原来源容器：Chat Completions 的 tool 文本、Responses 的 function_call_output、
Claude 的 tool_result、Gemini 的 functionResponse 不会被改造成用户上传消息。
Responses 的 `OPAQUE_REPLAY_ONLY` 不表示普通 Image 可结构化编码；匹配的原始 output 仍无损回放，
request-only 附件事实在其后追加，不重复普通回答。具体协议映射见 [protocol-reference.md](protocol-reference.md)。

Master 与 Target 使用同一投影器，位置在动态模板、Workspace 等输入处理之后、Provider 序列化之前。
`generate_image` 的 JSON `file.path` 与 Image 事实行的 path 指向同一个聊天副本。
Target 的 `generate_image` 仍要求启用 `TextToImage` 且文生图模型可解析，并遵守启动快照与当前工具权限交集。
`set_as_background=true` 继续走审批；非交互 Target 不绕过权限。模型原生产图不依赖该工具。

## 5. 按需识图

`shouldInjectAttachmentInspection(settings)` 只检查已配置识图模型存在、Provider 可用且声明 IMAGE 输入。
当前模型是否能看图、当前请求是否带图、有没有 READY Workspace 都不构成额外限制。

Master/Target 在各自新 `START` 时冻结同一套 `FrozenToolDefinition` 与执行索引，供该 Turn 全部 step 的
schema、审批与执行。配置撤销后不复活旧工具，待执行调用按 `tool_not_available` 失败。

`inspect_attachments` 接收 1–4 张图片路径和非空 request。与委托的去重语义不同，识图保持每个输入位置，
包括重复路径，以确保多图比较的顺序确定。读取调用 `AttachmentResolver.readImages(paths)`：

- 同样通过 ArtifactStore 的受保护有界读取。
- 内存快照复用 FileEncoder 的压缩、EXIF 方向和格式转换后构造 data URI；Provider 不依赖可能已被清理的 file URI。
- 不复制磁盘文件、不创建 Artifact，不写入会话或设置。
- 发给识图模型的内容是独立固定 System instruction、按序 `[Image N path=...]`、图片和 request；
  不携带主会话历史或主 Assistant system prompt。
- 一次调用处理全部图片；成功返回 Text，失败返回稳定 reason，取消不转换为普通错误。

有效识图配置只提供能力，不触发自动识别。工具参数的完整文案与 Provider 错误分类见
[prompts-and-tools.md](prompts-and-tools.md)。

## 6. 出站协议：Target → Caller

### 6.1 提取与默认清单

`extractDeliverableArtifacts` 只提取本次 Child USER 到下一条 USER 之前的交付物，不做任务理解或网络 IO：

- 已完成且成功的 `generate_image` 工具输出图片。
- 最后一条 ASSISTANT 的顶层媒体。
- 不包括入站 USER 附件、Web Search/MCP 中间图片、失败或未执行工具、历史 run、未落地 HTTP/data 图片。

按消息/part 顺序提取，通过内部逻辑身份或规范化文件去重。最多保存 `MAX_ASSISTANT_CALL_ATTACHMENTS`
个可持久化项，超出部分计入省略数。内部 metadata 可以保留 upload/images 范围的文件；
无法形成合法 `LocalArtifactRef` 的项只影响 `has_non_text_output`，不披露假路径。

completed 的模型结果通过 `buildSubAssistantArtifactManifest` 从内部 metadata 派生：

```json
{
  "status": "completed",
  "assistant_name": "...",
  "content": "...",
  "has_non_text_output": true,
  "artifacts": [
    { "path": "/upload/abc123.png", "type": "image", "mime": "image/png" }
  ]
}
```

清单只有 path/type/mime，不带重复 ref、host path 或 base64。没有合法 toolPath 的条目不进入模型清单，
内部 metadata 不因此删除或改写；旧 GUID 文件路径照常保留。超出持久化交付上限时另有 `artifacts_omitted`。
failed/stopped/unavailable 不返回交付物清单或 `has_non_text_output`。

### 6.2 内容与用户可见性

| `extras` | 用户 | Caller 模型 |
| --- | --- | --- |
| 省略 artifacts | 卡片显示交付物缩略图 | Text JSON 清单；没有 Image，也没有附件事实行 |
| 包含 artifacts，原生容器支持图片 | 同上 | JSON + Image + input=native 事实 |
| 包含 artifacts，容器不支持图片 | 同上 | JSON + input=reference_only 事实；无路径时 unavailable |

`extras=["artifacts"]` 必须在该次委托调用时指定，它不是事后补取接口。
默认 manifest-only 后，Caller 可随时把 `artifacts[].path` 交给识图工具或下一次委托，不必重跑产图子助手。

`projectArtifactsForCaller` 只追加 Image，不判断 Caller 能力、不调用识图模型。
追加内容进入 Caller Tool.output，随后每次请求由统一投影器重新适配；这会保留在 Caller 历史中，
不是只供当前轮使用的临时通道。Document / Audio / Video 即使点名 extras 也只通过清单披露。

`has_non_text_output` 表示用户可见交付物存在，不承诺模型已看到内容。
`tts_stats` 仍默认精简，`tts` / `tool_calls` 仍由各自 extras 控制，不与图片像素绑在一起。

## 7. UI、生命周期与复制

内部 `SubAssistantCallMetadata` 保留现有 schema：

```text
artifacts: [{ ref: "attachment:<uuid>", type: "image", mime: "image/png", artifact: LocalArtifactRef? }]
artifact_omitted: Int = 0
```

`mergeSubAssistantCallMetadata` 只 merge 对应键，保留 Provider metadata。
`SubAssistantRunStateReducer` 保证终态不可回到 running；最终 metadata 与结果走同一提交边界。

卡片通过查询端口获得缩略图，不加载整个 Child，也不把默认清单转成 Tool.output 图片。
主卡片设背景归 Master Assistant，Child 详情设背景归 Target。文件缺失时不提供预览路径，不伪造内容。

`ConversationAttachmentPreviewProjector` 使用内部 `AttachmentReferenceLookup` 加上已知附件工具
顶层 attachments 路径，经 ArtifactStore 校验后生成 UI map。输入路径不要求会话已有 Image；
它是预览请求，不构成 durable 文件 root。UI 不扫描 metadata，不直连文件 owner，也不授权执行读取。

文件生命周期：

- Child 中原文件 URI 与 Master metadata 的 `LocalArtifactRef` 由现有引用计算器纳入 durable roots。
- 删除 Master 会级联 Child；只删除/收缩 Child 时，仍由 Master 引用的交付物受到保护。
- Fork/Child clone 本身是复制协议，`AttachmentCloner` 按 source-canonical 映射复制历史资源并更新
  Image URL、artifact metadata 和模型清单中的 path；与按需读取无关。
- 已知附件工具顶层 attachments 中已复制资源的路径同步重绑定；不为输入数组额外复制文件，
  不猜文件名、不加源路径别名。未知工具/字段、正文和内部 UUID 保持原规则。
- clone 创建的资源由 DelegationCoordinator 持有到 Child link 提交后发布；关联前失败补偿，
  关联后失败保留已关联 Child 与引用并进入失败终态。普通路径读取不加入这批创建资源。

## 8. 关键实现与验证边界

| 组件 | 唯一职责 |
| --- | --- |
| `AttachmentRefs` / `AttachmentReferenceLookup` | 内部 metadata 身份、盖章与 UI 索引 |
| `LocalToolPath` | upload 文件路径语法 |
| `ArtifactStore.withUploadImages` / `ArtifactPayloadStore.readBytes` | 生命周期读取保护 / 有界磁盘 IO |
| `AttachmentResolver` | path → 识图内存输入或受保护 Child 文件输入 |
| `AttachmentInspectionTool` / `GenerationToolSetFactory` | 显式识图与工具可用性 |
| `AttachmentProjectionTransformer` | 原来源容器内的请求级图片投影 |
| `AssistantToolFactory` / `DelegationCoordinator` | 委托参数、run/Child 编排与交接 |
| `SubAssistantResultProjection` / `buildSubAssistantArtifactManifest` | 交付物提取、内容选择与模型清单 |
| `AttachmentCloner` / `ConversationAttachmentPreviewProjector` | 正常复制重绑定 / 只读 UI 预览 |

路径契约、三来源四协议投影、默认清单后识图、无工作区、旧文件名及失败/取消应有定向测试；
读取与删除/GC、Child 提交的竞态使用实际 ArtifactStore/Room 设备测试覆盖。
JVM 与构建成功不等于真实 Provider 在线验收。
