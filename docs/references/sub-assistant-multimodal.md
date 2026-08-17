# 子助手多模态：附件入站与交付物出站

本文是当前已经落地的实现参考。子助手整体语义以 [sub-assistant-architecture.md](sub-assistant-architecture.md) 为准；模型可见的参数文案与 Tool Result 形状以 [prompts-and-tools.md](prompts-and-tools.md) 为准；Transformer 顺序以 [chat-generation-pipeline.md](chat-generation-pipeline.md) 为准。

当前范围：**Image 双向完整**。协议与内部命名从第一天使用通用 Attachment / Artifact，便于以后接 Document / Audio / Video；那些类型的内容适配见文末扩展方向，不要当成已实现。

---

## 1. 背景与要解决的问题

`assistant_call` 是同步委托：Target 在独立 Child Conversation 中跑完，Master 等终态后再继续当前 Tool Loop。Child 用正常 `UIMessage` / `UIMessagePart` 持久化；Target 复用 `GenerationHandler`、Transformer 与 Provider。Target **不读取** Master 历史。

因此会出现两条必须分开、又共用同一套文件与适配层的链路：

```text
入站  Main → Target     Caller 点名任务相关附件，写进 Child USER
出站  Target → Main     Target 产出文件后，用户要看见，Caller 不一定要看见内容
```

入站不做时，Caller 无法把刚生成或用户刚上传的图交给绘图/视觉子助手。出站不做时，Target 即便生了图，主卡片也几乎只有文本；Caller 要么完全看不见交付物，要么一看见就被迫吃视觉 token。

必须拆开三件事，不能绑在一起：

```text
Target 产生了交付物
用户需要在主卡片看到交付物
Caller 模型需要读取交付物内容
```

绑在一起的后果：只要 Target 生图，就会把图再喂给 Main，产生视觉 token；Main 是文本模型时还可能偷偷跑 OCR。行业里 A2A 把 Task Artifact 与普通 Message 分开，查询时 `includeArtifacts` 默认 `false`：交付物存在，不代表每次都要取回内容。

两条链路共用失败策略会错：入站失败应在写 Child 之前挡住；出站时 Target 已经成功，Caller 看不懂图不能推翻整次 `completed`。

---

## 2. 设计判定

### 2.1 仍然成立的原则

入站三条：

> **Main Agent 决定传什么，Runtime 决定怎么传。**

> **Child 永久保存原始附件，模型适配只产生本次 Run 的 Model View。**

> **现在只完整支持 Image，但协议与内部命名从第一天使用通用 Attachment 语义。**

因此：工具参数是通用 `attachments?: string[]`，没有 `images` / `documents`，也没有 `ocr` / `vision` / `mode`。不把附件写进 System Prompt。`SubAssistantCoordinator` 只解析引用并注入 Child，不做任务向视觉理解。本次 `attachments[]` 是强契约；Child **历史**附件可降级，避免换模型后永久卡死。

出站与共用：

1. **User visibility ≠ Model visibility。** 用户看见缩略图，不等于 Caller 上下文里有像素。
2. **Agent owns relevance，Runtime owns representation。** 不增加并列开关。Caller 只点名「传哪些附件」「要不要交付物内容」；怎么传、怎么投影由 Runtime 按**当次模型**决定。
3. 用现有 `extras` 增加 `artifacts`，与 `tts` / `tool_calls` 同一语义：**内容**默认不给。轻量引用不是像素，completed 有交付物时 JSON **始终**带 `artifacts[]`。
4. Child 是完整事实源；不新增 Artifact 消息协议，不新建 Store。复用 `LocalArtifactRef`、`managed_files`、`attachment_ref`、`ImageInputAdapter`。
5. 不把 Web Search / MCP 边角 / 中间文件 / 入站附件当成出站交付物。
6. `ask_user` 不是 Artifact；`set_as_background=true` 仍是审批副作用。
7. `SubAssistantCallState.UNAVAILABLE` 是运行态（撤权、模型不可用）。出站看不懂图仍是 `completed` + `artifact_delivery`。

四条平面不要混：

```text
State Plane          phase / preview
Interaction Plane    ask_user
Result Plane         final text / artifact refs / optional content
Permission Plane     approval / deny
```

### 2.2 入站强契约，出站弱契约

```text
Main → Target     attachments[]     强契约；写 Child 前 preflight
Target → Main     extras=artifacts  弱契约；看不懂也不推翻 completed
```

| | 入站 | 出站 |
|--|------|------|
| 谁决定相关性 | Caller 点名 `attachments[]` | Target 是否产出由任务和 Target 工具配置决定 |
| 用户是否看见 | Child 详情里有原图 | Master 卡片读 metadata 引用；不靠未点名的 Tool.output 媒体 |
| 模型是否看见内容 | 写 Child 前必须 NATIVE 或 DERIVED | 默认只要文本 + 轻量引用；`extras=["artifacts"]` 才投影内容 |
| 能力看谁的模型 | Target 本次 `RunSpec.model` | `settings.getChatModel(caller)`，不是 Target 的 `TransformerContext.model` |
| 失败 | 不写 Child，`reason=attachment_input_unavailable` 等 | 仍 `completed`，只降级投影 |

典型组合正是：Target 绑定视觉/绘图模型，Caller 是文本模型。用错模型会把本该 Derived / Unavailable 的图当成 Native 塞进 Main。

### 2.3 实现时纠正过的易错点

| 易错假设 | 实际判定 |
|----------|----------|
| Target 一直能生图 | 曾经永久过滤 `TextToImage` / `generate_image`。当前已从禁令拿掉，再走「快照 ∩ 当前配置」。不是所有 Target 都能生图。 |
| 卡片「有 Child 就能画缩略图」 | 卡片不加载 Child。必须把轻量引用写进 `sub_assistant_call` metadata。 |
| 默认 JSON 禁止出现 `artifacts[]` | 轻量 manifest 不是像素。completed 且有可持久化交付物时始终带引用，便于再委托。`extras=["artifacts"]` 只控制**内容**。 |
| 默认给引用但 Resolver 以后再改 | 同一套实现必须能解析。否则 Caller 抄 `attachment:` 会得到 `attachment_not_found`。 |
| 永远不要把 Image 放进 Master `Tool.output` | 当前 Tool Loop 只看见已经写入的 tool result。点名且 NATIVE 时必须把 Image 写进本次 `Tool.output`。点名后的投影会进入 Master 历史并在后续 turn 回放——这是 opt-in 的代价。 |
| 把图塞进 output 等 Transformer 转译 | `AttachmentInputTransformer` 不递归 Tool.output。Chat Completions / Claude / Gemini 会单独编码 `Tool.output` 里的图，绕过顶层 Adapter。模型未声明 IMAGE 时，`toToolResultContent()` 改成文本占位 `[Image output omitted: ...]`，**不是**视觉转译；Gemini 上还可能静默丢块。DERIVED 必须在写 output **之前** `observe()`，output 里只放 observation 文本。 |
| `decodeArtifactRef()` 以后能读到 `sub_assistant_call` | 它只读 Tool.metadata 顶层 `"artifact"`。只把引用写在 `artifacts[]`、又不把 Image 写入 `assistant_call` output 时，必须同期扩展 Resolver，否则 Caller 抄 `attachment:` 会 `attachment_not_found`。 |
| UNAVAILABLE 当成 `SubAssistantCallState.UNAVAILABLE` | 那是运行态。出站看不懂图仍是 `completed` + `artifact_delivery`。 |
| 为卡片描述未点名也跑 OCR | 禁止。 |

当前能力三态只看 Caller/Target 当次模型的 `inputModalities` 与 `Settings.ocrModelId`。完整「模型 ∩ Provider ∩ 协议」矩阵不存在；用户把不支持视觉的兼容端点标成 IMAGE 时，NATIVE 仍会把图发出去，由 Provider/网关报错，现有 `provider_error` / `content_blocked` 收口。

---

## 3. 当前行为总览

```text
Main Agent
    │  request + attachments? + extras?
    ▼
AssistantToolFactory                 形状校验；统一 unavailable 信封
    ▼
SubAssistantCoordinator
    │  有 attachments：Target RunSpec 能力 preflight
    │  AttachmentResolver → Child USER = Text + 原始 Image
    │  Target GenerationHandler
    │      AttachmentRefHintTransformer
    │      AttachmentInputTransformer / ImageInputAdapter
    ▼
extractDeliverableArtifacts          只提取，不做能力判断
    ├─ metadata + SubAssistantCallCard     用户可见引用
    └─ completed JSON                      轻量 artifacts[]
           extras 含 artifacts?
                │           │
               no          yes → getChatModel(caller) + ImageInputAdapter
                                 Native Image / Derived 文本 / Unavailable
```

| Target 产生图片 | extras=artifacts | Caller 模型 | Master 卡片 | Caller 模型上下文 |
|-----------------|------------------|-------------|-------------|-------------------|
| 否 | 任意 | 任意 | 无缩略图 | 仅文本 |
| 是 | 否 | 任意 | 显示图 | 文本 + 轻量 artifacts[]；无 OCR |
| 是 | 是 | NATIVE | 显示图 | JSON + Image parts |
| 是 | 是 | DERIVED | 显示原图 | JSON + observation 文本 |
| 是 | 是 | UNAVAILABLE | 显示原图 | JSON + `artifact_delivery=unavailable` |

```text
用户：让绘图助手画一张图给我。
Main：assistant_call(...)                         extras 省略
→ 卡片显示图片；Caller 拿到文本 + 轻量 artifacts[]

用户：让绘图助手生成一张图，然后你评价哪里还要改。
Main：assistant_call(..., extras=["artifacts"])
→ 卡片显示原图；Caller 按能力拿到原图或 observation
```

---

## 4. `assistant_call` 协议

`AssistantToolFactory.buildAssistantCallTool()`：

| 参数 | 语义 |
|------|------|
| `assistant_id` | Catalog id |
| `request` | 任务简报；兼容旧键 `task` |
| `attachments` | 可选 string 数组；`maxItems` 与运行时上限均为 `MAX_ASSISTANT_CALL_ATTACHMENTS` |
| `extras` | 可选；白名单 `artifacts` / `tts` / `tool_calls`（`ASSISTANT_CALL_EXTRA_*`）。未知值丢弃 |

`SubAssistantCoordinator.executeCall()` 接收 `attachments` 与 `extras`。缺省或空数组视为未传附件。

`extras` 的工具 description 写明 **for the caller model**：用户能不能看见，不由这个参数控制。点名 `artifacts` 表示 Caller 需要检查、推理或继续使用 Target 产出的文件内容。`tts_stats` 仍默认精简投影，不要把 `artifacts` 学成「默认也给像素」。

当前接受的入站引用：

```text
attachment:<uuid>     // 本会话已盖章的稳定引用
/upload/<file>        // LocalToolPath.parseUploadToolPath() 接受的 managed 路径
https://...           // 仅作为输入来源，必须先落地
http://...            // 同样先落地；SSRF 规则与 https 相同
```

模型不应传递 Android `file:`。Resolver 若收到 `file:`，只接受已经落在 `filesDir/upload` 或 `filesDir/images`、文件存在、且被 **Master 当前选中分支**引用的路径；否则 `attachment_not_found`。

### 4.1 `attachments` 校验

`parseAssistantCallAttachments()`：

- `null` / `JsonNull` / 空数组 → 未传附件。
- 不是 string 数组、含空白、去重后超过上限 → `AttachmentParseResult.Invalid`。
- 同一规范化字符串（trim）重复：去重后计入上限。

`executeAssistantCall()` 在进入 Coordinator 前的失败一律走 `buildSubAssistantCallResult(status = "unavailable", reason = ...)`。`request_required`、`assistant_id_required`、`invalid_assistant_id`、`invalid_arguments` 等旧 reason 保持不变。`assistant_manage` / `assistant_inspect` 仍用 `{"error":...}`。

`attachments` description 只说明：最多若干个、只传与本次任务相关的附件、优先 `attachment:<uuid>`、生图可用 `/upload/<file>`、对方看不见本对话。不描述 Target 是否支持视觉，也不展开 MIME 适配。

### 4.2 入站稳定 reason

定义在 `AttachmentFailureReasons`。这些失败都发生在写 Child 之前。下载是解析的一部分，失败则不写 Child。

| reason | 何时 |
|--------|------|
| `invalid_attachments` | 形状、数量、空白项不合法 |
| `attachment_not_found` | 引用在 Master 当前分支和 `/upload` 登记表中都不存在，或 `file:` 不在允许目录 / 未被当前分支引用 |
| `unsupported_attachment_type` | 解析后不是当前接受的图片（含 PDF / 音频 / 视频，以及下载成功但无法当作位图消费） |
| `unsafe_attachment_url` | scheme / 重定向 / 私网 / 体积等安全检查失败 |
| `attachment_fetch_failed` | 远程下载或 IO 失败 |
| `attachment_input_unavailable` | 本次附件无法 NATIVE 且无法 DERIVED |

`SubAssistantCallState.UNAVAILABLE` 与这些 reason 不是同一套枚举。附件三态的对外出口是 Tool Result 的 `reason`。

---

## 5. 共用基础设施

### 5.1 `attachment_ref`

权威键与前缀在 `AttachmentRefs`：

```text
metadata.attachment_ref = "attachment:<uuid>"
```

不修改 `Image` / `Document` / `Audio` / `Video` 的主体字段。写入必须 **merge**（`AttachmentRefs.mergeMetadata()` / `ensureAttachmentRef()`），禁止整表替换，以免冲掉 Gemini `thoughtSignature`、`artifact`、`sub_assistant_call`。`ensureAttachmentRef()` 只保留能被 `parse()` 解析的既有 ref；导入 / 旧数据 / 异常 Provider metadata 里的非法值会被重建为合法 `attachment:<uuid>`。

盖章在持久化写路径，不是 Transformer：

- 用户发送与编辑重发：`ChatService.preprocessUserInputParts()`。
- `generate_image` 产出的 Tool.output Image：构造时盖章；权威文件仍是 Tool.metadata 的 `LocalArtifactRef`。
- MCP `ImageContent`：`McpManager.convertImageContentToFilePart()` 落盘后盖章。
- `assistant_call` 为外部 URL 新落地的 Image：新 ref。
- Target 模型原生出图（`data:image`）：`FilesManager.convertBase64ImagePartToLocalFile()` 落盘时即 `ensureAttachmentRef()`。Child 没有每轮 backfill，不在这里盖章的话出站提取每次都会生成新的随机 ref。
- 从 Master 注入 Child 的 part：**复制源 `attachment_ref`**。

`AttachmentInputTransformer` 只扫描消息顶层 `parts`，不进入 `Tool.output`。因此 Master 上的 `generate_image` 结果**不会**被改成 observation；Caller 要把生图交给 Target，应引用该 Image 的 `attachment_ref` 或 JSON 里的 `/upload/<file>`。注入 Child 后这些图变成 USER 顶层 Image，Adapter 才能处理。

历史消息没有 ref：`ChatService.handleMessageComplete()` 在 Master 生成前调用 `AttachmentRefs.backfillConversation()`，并随现有 `updateConversation()` 写回。不要在 Input Transformer 里只做一次性临时 id。

Child clone / Master fork 已保留 metadata（`copyPartForChildClone()` / `copyForkedPart()`）。Resolver 以「当前消息树里带该 ref 的 part.url」为准，不把 ref 理解成全局文件主键。

### 5.2 `ImageInputAdapter`

`OcrTransformer` 是薄委托。真实逻辑：

```text
AttachmentInputTransformer
    └── ImageInputAdapter
```

`ImageInputAdapter.resolveCapability()`：

| 状态 | 条件 |
|------|------|
| `NATIVE` | 该模型 `inputModalities` 含 `Modality.IMAGE` |
| `DERIVED` | 不含 IMAGE，但 `Settings.ocrModelId` 能解析到模型、其 Provider 可用，**且 OCR 模型自身 `inputModalities` 含 IMAGE** |
| `UNAVAILABLE` | 三者任一不满足 |

OCR fallback 模型自身必须是视觉模型：text-only 模型配置成 OCR 时在 preflight 阶段即判 UNAVAILABLE，不会等到 `observe()` 才必然失败。

`preflight()` 与 `resolveCapability()` 当前等价（`parts` 未使用）。

普通聊天与子助手共用 Adapter，策略由 `TransformerContext` 区分：

| 字段 | 含义 |
|------|------|
| `imageAdaptMode` | `CHAT_COMPAT`（默认）或 `SUB_ASSISTANT` |
| `currentTaskMessageId` | 仅 Target；等于本次 Child USER 的 `UIMessage.id` |

`GenerationHandler.generateText()` 把这两项传进 Input Transformer。Target 的 `runTargetGeneration()` 传入 `SUB_ASSISTANT` 与 `childTaskNodeId`。

Adapter 只处理 `url` 以 `file:` 开头的**顶层** Image，不递归 `Tool.output`。HTTP / data: 图不会被转译，所以入站 Resolver 必须先落地。

DERIVED 不把 `assistant_call.request` 或用户任务送进视觉模型。调用是 `provider.generateText()`：system 为 `settings.ocrPrompt`（用户自定义有效；默认文案在 `DEFAULT_OCR_PROMPT`），user 只有 Image part。不经过 `GenerationHandler`。

包装：

```text
<attachment_observation ref="attachment:<uuid>">
...
</attachment_observation>
```

缓存只存视觉模型原文，标签由 `ImageInputAdapter.wrapObservation()` 在读出后加上。缓存文件是 `image_observation_cache.json`（不再读旧的 `ocr_cache.json`）。键是 `sha256(file bytes) + RENDITION_VERSION + ocrModelId + sha256(ocrPrompt)`。`RENDITION_VERSION` 绑定输出模板。容量与 3 天过期沿用原 LruCache。clone/fork 换路径后按内容哈希命中。处理中状态走 `R.string.image_observation_processing`。

| 对象 | 无法 NATIVE / DERIVED 时 |
|------|--------------------------|
| 本次 `attachments[]` | 写 Child 前 preflight 失败，`attachment_input_unavailable` |
| 普通聊天里的用户图 | `[Image]`；DERIVED 调用失败则 `[ERROR, OCR failed: ...]`，生成继续 |
| Child 历史图 | `[Historical attachment unavailable to the current model]`，有 ref 时追加 `: attachment:<uuid>`。Child 可继续跑 |
| 出站点名且 DERIVED | 写 output 前 `observe()`；单张失败写 `OBSERVATION_FAILED`，整次 call 仍 completed |

挂载顺序：

| 管线 | 相对顺序 |
|------|----------|
| Master `ChatService` | `DocumentAsPromptTransformer` 之后：`AttachmentRefHintTransformer`，再 `AttachmentInputTransformer`，再 Template / Workspace / `ToolArtifactReplayTransformer` |
| Target `runTargetGeneration()` | 同样替换原 `OcrTransformer`，并加上 Hint；不强制补 `ToolArtifactReplayTransformer` |

`AttachmentRefHintTransformer`（`insertAttachmentRefHintsInMessages()` / `insertAttachmentRefHintsInParts()`）只在本次请求中、每个带 `attachment_ref` 的多媒体 part **前方**插入很短的 Text，例如 `[Attachment attachment:<uuid>, screenshot.png]`。必须递归 `Tool.output`，否则主模型看不到生图结果上的 ref。不改用户原文本，也不回写 Conversation。显示名优先 `ManagedFileEntity.displayName`，否则磁盘文件名；`Document` 优先 `fileName`。DERIVED 替换 Image 时，observation 文本再次带上同一个 ref。用户气泡、导出、复制原文都不使用这些提示句。

不要写 `SubAssistantOcrTransformer`，也不要给 `AttachmentInputTransformer` 加 `assistant_call` 特例。

### 5.3 Target `generate_image`

`SubAssistantRunPolicy.FORBIDDEN_LOCAL_TOOLS` 与 `filterTargetTools()` **不再**永久过滤 `TextToImage` / `generate_image`。有效工具仍是：

```text
有效工具 = 调用开始时的 Target 快照 ∩ 当前持久化配置
```

- Target **未开启** `TextToImage`：没有该工具。
- 已开启且默认文生图模型可解析：Target 可调用 `generate_image`。
- `set_as_background=true` 仍须审批；Target 非交互下继续 `tool_not_permitted`。`generate_image(false)` 正常执行。
- `assistant_inspect` 使用 `ToolSetRunMode.TARGET`，开启后应能列出该名。
- 运行中撤权：下一 step 重建工具集时去掉。

模型原生出图（落成顶层 Image）不依赖这个工具。`generate_image` 成功时 `Tool.output = [Text(bounded JSON), Image(file://)]`；Image 已 `ensureAttachmentRef()`；权威是 Tool.metadata 的 `LocalArtifactRef`，`ToolArtifactRewriter` 另写 `"artifact"` 键。JSON 的 `file.path` 是 `/upload/<file>`。

Child 不需要 `ToolArtifactReplayTransformer` 才能在本 run 里看见刚生成的图。跨 step 的历史 `/upload` 字符串：Target 仍无 replay。

---

## 6. 入站实现（Main → Target）

### 6.1 AttachmentResolver

`AttachmentResolver.resolve(masterMessages, refs)`：

```text
模型提供的 attachment reference
        ↓
可持久使用的 UIMessagePart.Image（file:// + metadata.attachment_ref）
```

解析范围：**Master 当前选中分支**（含 Tool.output 递归）+ `ManagedLocalArtifactStore` 已登记且被该分支引用的 `/upload`。禁止跨会话、禁止任意 `file:`、禁止 Workspace `/workspace` 路径。Child **不继承** Master 的 `workspaceCwd`。

出站落地后，当前分支 `assistant_call` 的 `sub_assistant_call.artifacts[]` 也算 Master 引用。metadata 回退解析仍要求文件落在 `filesDir/upload` 或 `filesDir/images`。

| 来源 | 处理 |
|------|------|
| `attachment:<uuid>` | `AttachmentRefs.walkMessageParts()` 找 `metadata.attachment_ref`。未命中再读当前分支 `sub_assistant_call.artifacts[]`。命中后使用该 part 的当前 `url` 或 metadata 中的 `LocalArtifactRef` |
| `/upload/<file>` | `LocalToolPath.parseUploadToolPath()` + `ManagedLocalArtifactStore.resolveToolPath()`。再确认该文件被当前 Master 选中分支引用（顶层 / Tool.output 的 `file://`、同消息 `LocalArtifactRef`，或 `sub_assistant_call.artifacts[]`） |
| `file:` | 规范化路径落在 `filesDir/upload` 或 `filesDir/images`，文件存在，且被当前分支引用 |
| HTTP(S) | `SafeRemoteMediaFetcher.fetch()` → `FilesManager.saveManagedFromBytes()` 落地 → 新 Image + 新 ref |

同一规范化文件只注入一次（按 canonical path 去重）。同一 Master 文件注入 Child 时 **共享** `file://`，不先复制。命中 Master 已有 Image 时复制源 `attachment_ref`。若源 url 仍是 HTTP(S)，会先落地再注入，Child 只存 `file://`。Master 删除会级联 Child。批次中途失败或抛错时，`deleteCreated()` 删除本批新登记的远程落地文件；resolve 成功返回后由 `AttachmentResolveResult.Success.createdManagedFileIds` 交给 Coordinator，Child 写入 / 持久化失败时同样回滚，不留下孤儿文件。删除仍按 URL 引用计数，不要在注入时 `delete` 源文件。

本地附件（`/upload/<file>` 与 `file:`）在读取前同样受 `GeneratedMediaStore.MAX_IMAGE_BYTES` 体积上限，超限映射 `unsafe_attachment_url`，与远程路径一致，避免大文件整读进内存。

`attachments` 留在工具入参里（与 `extras` 一样，恢复/审计从 tool input 读）。不另写一份到 `sub_assistant_call` metadata。

`FilesManager.saveMessageImage()` 没有 SSRF 防护，不能复用为模型可控下载。统一走 `SafeRemoteMediaFetcher`：只允许 `http` / `https`；有限次重定向且每次重解析 host；禁止 `file:` / `content:` / `javascript:` 跳转；拒绝回环、链路本地、私网、metadata、CGNAT、IPv6 unique local，以及 IPv4-mapped / NAT64 / 6to4 里嵌套的私网地址；查 DNS 后把连接钉到已校验地址（HTTPS 用 `PinningSslSocketFactory`——包括 `createSocket(Socket, host, port, autoClose)` 这个 Android 平台实际使用的 layered overload，已连接的远端地址不等于 pin 即拒绝；明文 HTTP 改连 IP 并带原 `Host`）；超时与最大字节数与 `GeneratedMediaStore.MAX_IMAGE_BYTES` 对齐。先看 Content-Type，再以魔数解码。失败映射到 `unsafe_attachment_url` / `attachment_fetch_failed` / `unsupported_attachment_type`，不把内部 IP 或异常栈回给模型。测试可注入 `dnsLookup` 与 `RemoteHttpTransport`。

`ImageMime` 当前通过才注入：魔数 JPEG / PNG / GIF / WEBP（`GeneratedMediaStore.detectImageMimeBySignature()`）；HEIC/HEIF 按 ISO-BMFF `ftyp` 品牌识别，若 `encodeBase64()` 能转 JPEG 则接受；声明为 `image/*` 但魔数不明时再尝试 `GeneratedMediaStore.detectImageMime()`。PDF、音频、视频、未知二进制 → `unsupported_attachment_type`。

### 6.2 Child 注入与 preflight

`createNewChild()` / `reuseChild()` / `cloneChild()` 的 USER 由 `buildChildUserParts()` 构造：

```text
USER
├── Text(preprocessSubAssistantTask(request))
├── Image(file://..., metadata.attachment_ref=...)
└── Image(...)
```

正则预处理只作用于 Text。不把 URL / OCR 拼进 `request`，不写 System Prompt。`child_task_node_id` 仍是该 USER 的 `UIMessage.id`。

lease 已持有之后的顺序：

1. `resolvePreWriteBlockReason()` 用最新 Settings 重验身份、访问与模型。
2. 若 `attachments` 非空，先 `ImageInputAdapter.preflight(runSpec.model, settings)`。`UNAVAILABLE` 则释放 lease，返回 `attachment_input_unavailable`，**不下载、不写 Child**。
3. 再 `AttachmentResolver.resolve()`。
4. 解析失败释放 lease，不写 Child。
5. 写入 `Text + Images`。
6. 写入或 resolve 抛错走现有 `runtime_error` 分类。lease 在该 `try` 的 `catch` 中释放。

`InputMessageTransformer` 不能让整次 `assistant_call` 失败。强契约只在写 Child 前用 RunSpec 做能力预检。预检不跑视觉模型；DERIVED 的真实转换仍在 Transformer 里，命中缓存即可。运行中 OCR 失败写成明确的 `<attachment_observation>` 错误句，不中途杀掉已经开始的 Target 循环。只要 `assistant_call` 返回 `completed` 且本次传了附件，Child 里一定有原图，且本次请求要么以原图、要么以 observation（或明确错误 observation）进入 Target。

http / data: 图不会被 Adapter 转译。Child 只存 `file://`，否则文本 Target 会把裸 URL 送给 Provider，或在 Gemini 上被静默丢掉。

---

## 7. 出站实现（Target → Main）

### 7.1 提取

`extractDeliverableArtifacts()` 是纯提取，不做能力判断，不要在里面碰 IO。范围与 `messagesInRunRange()` 相同：从本次 Child USER 到下一条 USER 之前。

当前明确交付物：

| 来源 | 条件 |
|------|------|
| `generate_image` 的 `Tool.output` | 工具已执行、结果为成功完成态、含 `UIMessagePart.Image`，且能解析到有效 `LocalArtifactRef` 或本地 `file://` |
| 最终一条 ASSISTANT 的顶层媒体 | Image 做内容投影；Document / Audio / Video 只进引用清单，点名 extras 时走 `artifact_delivery=unavailable` |

**不是交付物**：本次 Child USER 上的入站附件、Web Search / MCP 中间图、失败 / 未执行的 Tool、历史 run、未落地的 http / data: 图。

顺序：按消息与 part 出现序。去重键：同一个 `attachment_ref`，或规范化后的本地文件。

上限与入站共用 `MAX_ASSISTANT_CALL_ATTACHMENTS`：

- 写入 metadata / 卡片 / Caller JSON 的是前若干条**可持久化**项（带 `LocalArtifactRef`，相对路径落在 `upload/` 或 `images/`）。
- 超出的仍留在 Child；卡片 `+N`；JSON 写 `artifacts_omitted`。
- 只有本地 `file://`、无法落到可校验相对路径的项：计入 `has_non_text_output`，但不写入 metadata / JSON，避免广告无法解析的句柄。

`has_non_text_output` 表示本次有用户可见非文本交付物。failed / stopped / unavailable **不写**该标志，也不写 artifacts 清单。

### 7.2 metadata 与卡片

`SubAssistantCallMetadata`（`schema_version` 保持 1，新字段默认空，旧消息可解码）：

```text
artifacts: [
  {
    ref: "attachment:<uuid>",
    type: "image",              // image / document / audio / video
    mime: "image/png",
    artifact: LocalArtifactRef?
  }
]
artifact_omitted: Int = 0
```

只存引用，不存像素，不存 host `file://`，不存 observation。`mergeSubAssistantCallMetadata` 仍只替换 `sub_assistant_call` 键。`updateTerminalState()` 的 `copy(...)` 必须带上新字段。

`SubAssistantCallCard`：

- 有 image 类 artifact 时用 `ZoomableAsyncImage` 显示缩略图，尺寸对齐 `ChatMessageTools` 工具卡预览。
- 多于上限显示 `sub_assistant_card_more_artifacts`。
- 文件缺失显示 `sub_assistant_card_artifact_missing`，不崩溃、不留假路径。
- 点击缩略图进 `ImagePreviewDialog`；相册用这几张预览 URL。完整时间线仍在详情。
- 主聊天 `LocalImagePreviewActions`：从 **Master 卡片** 设为背景 = 改当前 Master Assistant。详情里设为背景仍改 **Target**。
- 缩略图条与 `ask_user` 区一样消费点击。
- `COMPLETED && preview 空 && has_non_text_output` 的纯文本占位是**无缩略图**时的退路。

卡片**不加载 Child Conversation**。不要为了给卡片供图，在未点名 `extras` 时把 Image 写入 `assistant_call` 的 `Tool.output`。

### 7.3 文件寿命与 fork

当前与 Child **共享**已落地文件，写入 metadata 时不再复制。

- 删除 Master 会级联 Child；Child 消息里的 `file://` 继续喂 `ConversationFileReferences`。
- 仅收缩/删除 Child、Master 仍在：`ConversationFileReferences` 把 `sub_assistant_call.artifacts[].artifact`（含 `generate_image` 的顶层 `"artifact"` 键）的 `relativePath` 也视为引用；`findUnsharedFileUris` 同时按完整 URL 与 filesDir 相对路径探测，收缩时保留树的 metadata 引用阻止删除。卡片不再因 Child 单独收缩而缺图。
- `forkSubAssistantTree()` 共享 Child 文件 URL：`LocalArtifactRef.relativePath` 仍然有效。
- 恢复：不重放 running；completed 的引用保留。文件没了就显示缺失，不伪造路径。

### 7.4 Caller 投影

闸门在 `projectCompletedArtifacts()` / `projectArtifactsForCaller()`：

```text
未点名 extras=artifacts
    → Tool.output 只有 Text JSON
    → 可含 has_non_text_output 与轻量 artifacts[]
    → 禁止调用 observe()

点名 extras=artifacts
    → capability = ImageInputAdapter.resolveCapability(getChatModel(caller), settings)
    → capability 判定用本轮 Master Tool Loop 的 Caller Settings 快照
      （ChatService → buildTools(callerSettings=...) → executeCall(callerSettingsSnapshot)）；
      Target Run 期间用户切换 Main 模型不影响投影方式，权限类检查仍读 latest
    → 对可持久化 Image 交付物做 Adaptation
    → 投影 part 追加在 Text JSON 之后
```

**NATIVE**：`Tool.output = [ Text(JSON), Image, ... ]`。共享 Child 的 `file://`，复制 `attachment_ref`。全部内容已按原图交付时省略 `artifact_delivery`；存在当前不支持类型（Document / Audio / Video）时写 `"artifact_delivery": "partial"`。

**DERIVED**：写 output 前 `observe()`。output 里只放 observation 文本。单张失败写 `OBSERVATION_FAILED`，其它项继续，整次 call 仍 `completed`。全部成功且无不支持类型写 `"artifact_delivery": "derived"`；部分失败或含不支持类型写 `"artifact_delivery": "partial"`；全部失败写 `"artifact_delivery": "unavailable"`。

**UNAVAILABLE**：output 仍只有 Text JSON。`status` 保持 `completed`。写 `"artifact_delivery": "unavailable"`。点名后只有 Document / Audio / Video 时同样走这个字段。

`unavailable` / failed / stopped 不加 artifacts 段，也不追加媒体 part。`SubAssistantRecovery` 与 `finishInterruptedToolAfterGenerationStop()` 只重建文本 extras（tts / tool_calls）。

completed 且存在可持久化交付物时，无论是否 extras 都带：

```json
{
  "status": "completed",
  "assistant_name": "...",
  "content": "...",
  "has_non_text_output": true,
  "artifacts": [
    { "ref": "attachment:<uuid>", "type": "image", "mime": "image/png", "path": "/upload/<file>" }
  ]
}
```

manifest 不含 host path、不含 base64。`path` 来自 `LocalArtifactRef.toolPath()`。`extras=["artifacts"]` 只改变 parts。

Resolver 顺序：

1. 带该 `attachment_ref` 的多媒体 part（含 Tool.output）
2. 当前分支 `sub_assistant_call.artifacts[]`
3. `/upload` + 「必须被当前 Master 引用」——metadata 里的 `artifacts[]` 算作引用

点名后的 Image / observation 会留在 Master 历史中，后续 turn 会再次发给 Caller。当前接受这一点。

未点名时只有 manifest。Caller 若还要把图交给另一个 Target，应把 `artifacts[].ref`（或 `path`）填进另一次 `assistant_call.attachments`。

---

## 8. 组件、测试与维护约束

| 组件 | 职责 |
|------|------|
| `AttachmentRefs` | 前缀、metadata 键、merge、ensure、backfill、file URL |
| `AttachmentFailureReasons` / `MAX_ASSISTANT_CALL_ATTACHMENTS` | 稳定 reason 与上限 |
| `parseAssistantCallAttachments()` / `parseAssistantCallExtras()` | 工具层形状校验；extras 白名单 |
| `AttachmentResolver` / `SafeRemoteMediaFetcher` / `ImageMime` | 引用 → 本地 Image；SSRF；魔数门禁 |
| `AttachmentRefHintTransformer` | Model View 句柄提示 |
| `AttachmentInputTransformer` / `ImageInputAdapter` | NATIVE / DERIVED / 占位 |
| `ImageAdaptMode` / `ImageAdaptCapability` | 策略与三态 |
| `OcrTransformer` | 兼容委托 |
| `AssistantToolFactory` | schema、校验、统一 unavailable 信封 |
| `SubAssistantCoordinator` | 入站 preflight / resolve / 注入；出站提取 / 投影 |
| `extractDeliverableArtifacts` / `projectArtifactsForCaller` | 纯提取与 Caller 投影 |
| `SubAssistantCallMetadata` / `SubAssistantRunStateReducer` | `artifacts[]` / `artifact_omitted`；终态快照 |
| `buildSubAssistantCallResult` | 轻量 `artifacts[]`、可选 `artifacts_omitted`、可选 `artifact_delivery` |
| `SubAssistantCallCard` | 缩略图 / `+N` / 缺失占位 / 点击分区 |
| `SubAssistantRecovery` / `finishInterruptedToolAfterGenerationStop` | 只重建文本 extras |
| `ChatService` | 发送/编辑盖章，生成前 backfill |
| `SubAssistantRunPolicy` / `filterTargetTools` | 不再永久过滤 `TextToImage` / `generate_image` |

不改：`GenerationHandler` 主循环语义、Provider 编码、lineage / lease。

主要测试：`AssistantCallToolTest`、`AttachmentRefsTest`、`AttachmentResolverTest`、`SafeRemoteMediaFetcherTest`、`ImageInputAdapterTest`、`AttachmentRefHintTransformerTest`、`SubAssistantAttachmentCoordinatorTest`、`SubAssistantChildPartsTest`、`SubAssistantResultProjectionTest`、`SubAssistantArtifactProjectionTest`、`SubAssistantRunPolicyTest`、`SubAssistantCallMetadataTest`、`SubAssistantRecoveryTest`、`ChatServiceToolApprovalTest` 停止路径。

维护约束：

- Transformer 默认不回写 Conversation。ref 必须在 send / tool output / backfill 时写入。Hint 文本绝不能 save 进消息。
- 任何 `copy(metadata = ...)` 都要从原 `JsonObject` 出发 merge。
- 不要在 Coordinator 里做任务向 caption。入站 preflight 只问「能不能传」。
- 注入与出站 Native 都共享 `file://`；不要在注入或展示时 `delete` 源文件。
- 出站用 `settings.getChatModel(caller)`，且必须是本轮 Master Tool Loop 的 Caller 快照（`buildTools(callerSettings=...)` 一路传入），不是完成时的 latest。`observe()` 的 Context 由 Coordinator 注入，不要在纯提取函数里碰 IO。
- 未点名 extras 时不要把 Image 写入 Master `Tool.output`，也不要调用视觉模型。DERIVED 不得把原图再塞进 output。
- `errorResult` 迁移只限 `assistant_call`。
- 用户可见字符串走 5 份 `strings.xml`。
- 文档不要写行号或易变计数；用类名 / 函数名 / 常量名定位。
- http / data: 图不会被 Adapter 转译。Child 只存 `file://`。

---

## 9. 将来扩展与补充

这些**不是**当前实现的缺口，而是同一条多模态需求里尚未立项的方向。协议（`attachments[]`、`extras=["artifacts"]`、`attachment:`）预期保持不变。

### 9.1 类型扩展（产品上最常见的下一步）

内部命名保持 Attachment。到时只加 Adapter 与 Resolver 的 MIME 分支，不再改 `attachments[]`、Child USER、lineage、RunSpec：

```text
AttachmentInputTransformer
    ├── ImageInputAdapter        ← 已落地
    ├── DocumentInputAdapter     ← Native PDF / 抽文本 / 渲染+视觉 / unavailable
    ├── AudioInputAdapter        ← Native / transcript / unavailable
    └── VideoInputAdapter        ← Native / transcript+关键帧 / unavailable
```

出站对 Document / Audio / Video 做与 Image 对等的内容投影，而不只是引用清单 + `artifact_delivery=unavailable`。

文档类应复用 `DocumentAsPromptTransformer`，不要再写一套 PDF 抽文本。

### 9.2 能力判定矩阵

补齐「模型 ∩ Provider ∩ 协议 Adapter」。避免把不支持视觉的兼容端点标成 IMAGE 后，NATIVE 仍把图发出去、只靠网关报错。

### 9.3 Provider File Cache

OpenAI / Claude / Gemini 远端文件缓存。同一张图多轮反复发给同一家视觉模型时，可省重复编码与上传。不改变「传什么 / 是否点名内容」的契约。

### 9.4 历史视觉 token

点名 `extras=["artifacts"]` 后，原图或 observation 会留在 Master 历史并在后续 turn 回放。若要「只让本轮看见」，需另做历史 `assistant_call` 媒体折叠，并可能改 `GenerationHandler`。不要在未点名时发明「只发给本轮、不落盘」的通道。

### 9.5 Target 跨 step 回放 `/upload`

Child 仍无 `ToolArtifactReplayTransformer`。本 run 刚生成的图在当前 step 的 Tool.output 里看得到；跨 step 只剩路径字符串时，Target 不一定能再当原图用。入站靠 Resolver 预落地规避。若子助手经常「先画再改同一张图」，再补 replay。

### 9.6 文件寿命

当前与 Child 共享文件，不另做第二套引用计数。`ConversationFileReferences` 已把 `sub_assistant_call.artifacts[].artifact` 与 `generate_image` 顶层 `"artifact"` 键视为引用（按完整 URL 与 filesDir 相对路径双探测），仅收缩/删除 Child、Master 仍在时卡片不会再缺图。

若以后 `forkSubAssistantTree()` 改为 `copyPartForChildClone` 式复制 Child 文件，**必须同时改写** `sub_assistant_call.artifacts[].artifact`。`copyForkedPart()` 不会自动做这件事。

### 9.7 明确不做（除非需求改口）

- 不把 Child 里所有非文本 Tool output 打包回传。
- 不在未点名 `artifacts` 时触发视觉转译（包括「为了卡片描述」）。
- 不把 Android host path / 任意 `file:` 写进模型 JSON。
- 不为 Target 再写一套 `set_as_background` 专用拒绝文案（现有 HITL + `nonInteractive` 已够）。
- 不改 lineage / lease / 撤权 / RunSpec 语义来迁就多模态。
