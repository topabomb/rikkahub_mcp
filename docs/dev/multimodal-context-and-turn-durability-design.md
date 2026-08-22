# 多模态上下文与 Turn 持久化设计

> 状态：核心 P0 已实施，资产持久化增强待后续迭代
>
> 日期：2026-08-21
>
> 适用范围：Master 普通聊天、用户上传附件、本地工具附件产物、未来创建或下载文件的工具
>
> 明确排除：本阶段不新增独立 OCR、图像识别或 `inspect_image` 工具

本文以当前代码和已支持协议为事实基线，解决两个相互关联但职责不同的问题：

- 用户上传的图片、`generate_image` 产出的图片，以及未来工具创建或下载的图片和文件，怎样根据
  当前聊天模型的实际输入能力稳定进入上下文；
- Master turn 在用户停止、Provider 失败、协议异常、工具异常或进程退出时，怎样保存可见历史、
  工具事实和明确终态，不再只在成功路径落盘。

## 0. 2026-08-21 实施同步

本轮已经完成以下架构闭环：

- Room v5 新增 `TurnExecutionEntity`、`ToolExecutionEntity` 及对应 DAO；`Migration_4_5` 保留旧数据，
  execution 随 Conversation 级联删除。
- `ConversationRepository.checkpointTurn()` 和 `finalizeTurn()` 在一个 Room transaction 内提交消息树、
  turn 状态和工具执行事实；生成 checkpoint 只更新 `update_at`，不再用旧快照覆盖 title、folder、pin、
  suggestions 等窄列。
- `GenerationHandler` 的 checkpoint 改为 awaited callback。工具执行前必须先提交稳定 execution id 和
  `STARTED`；每个工具的结果单独合并并提交，前一个工具结果落盘后才允许执行下一个工具。
- Master 普通发送、重新生成和审批恢复都绑定稳定 `turnId` 与 `assistantMessageId`。重新生成首包前失败
  会形成新失败分支，不会回退修改任意旧 assistant。
- 启动时在接受新聊天操作前恢复遗留 `CREATED/RUNNING` turn：可见消息标为 `INTERRUPTED`，遗留
  `STARTED` 工具标为 `UNKNOWN` 并写入不可自动重试的协议结果。
- 取消原因按 turnId 隔离；Session dirty revision 落盘后会重新安排空闲回收。
- 非成功 assistant 通过 `replaySafeProjection()` 生成请求专用历史：清理未完成 reasoning、Provider
  opaque metadata、开放或非法工具调用，UI 中的部分文本和终态仍完整保留。
- 空终态消息、媒体落盘失败均有可见且本地化的渲染；媒体失败使用 typed Text metadata，不再保存
  永久 loading 的空 URL Image。
- OpenAI Responses 的 `incomplete` 和“流在终态事件前关闭”进入 typed incomplete 路径，不再统一
  降级为 provider failed。
- 附件投影顺序已修正为 artifact replay 在附件提示和图片适配之前；图片适配递归处理
  `Tool.output`。当前用户图片既无原生能力又无兼容 OCR 时，在调用聊天 Provider 前明确失败。

仍未完成、不得在文档或产品说明中宣称已经具备的部分：

- `AttachmentAsset`、`AttachmentDerivative`、`AttachmentDeliveryReceipt` 仍是目标领域模型；当前 OCR
  观察继续使用版本化 cache key 的缓存，而不是可同步的持久派生记录。
- Provider 文件句柄、请求 rendition、媒体预算和 token 成本治理尚未实现。
- 文档、音频、视频与任意未来下载文件尚未统一到完整资产图；本轮先完成图片与工具嵌套图片的正确性。
- `MasterTurnCoordinator` / `ConversationCommitCoordinator` 尚未拆成独立类；当前权威提交协议已经集中在
  `ChatService`、`GenerationHandler` 和 `ConversationRepository`，后续可在不改变持久契约的前提下抽取。
- `ProviderTerminalStatus.INCOMPLETE` 目前只在 OpenAI Responses Provider 的两处路径
  （`incomplete` 事件、terminal 前断流）标记；Chat Completions / Anthropic / Gemini Provider 的
  流式提前关闭尚未逐协议接入 typed incomplete 检测，仍按 FAILED/RUNTIME_ERROR 收口（部分内容照常
  保留，`replaySafeProjection` 对 FAILED 与 INCOMPLETE 同样生效）。各协议的 terminal 信号语义
  （`[DONE]`/`finish_reason`、`message_stop`、`finishReason`）需要逐个验证后再接入，属协议层独立工作。

相关现状文档：

- [`chat-generation-pipeline.md`](../references/chat-generation-pipeline.md)
- [`protocol-reference.md`](../references/protocol-reference.md)
- [`sub-assistant-multimodal.md`](../references/sub-assistant-multimodal.md)
- [`context-management.md`](context-management.md)
- [`persistent-records-and-sync.md`](persistent-records-and-sync.md)

---

## 1. 决策摘要

### 1.1 多模态附件

采用一条统一的附件链路，不再按“用户上传”“文生图结果”“某个具体工具”分别打补丁：

```text
任意附件来源
  -> 受管资产与稳定 attachment_ref
  -> 本次上下文选取
  -> 资产物化和完整性预检
  -> 能力与放置位置解析
  -> NATIVE / DERIVED / UNAVAILABLE 投影
  -> Provider 无损且失败可见的协议编码
```

核心决策：

1. `Conversation` 保存原始附件引用，是用户历史和渲染的事实源；OCR 文本、协议占位和临时提示
   不改写原始消息。
2. `NATIVE / DERIVED / UNAVAILABLE` 是“某个附件在某次请求中的投影结果”，不是只看
   `Model.inputModalities` 得到的全局模型标签。
3. 能力解析必须同时考虑模型、Provider、端点 profile、MIME、消息位置和当前文件状态。
4. 用户消息顶层、`Tool.output`、工具 metadata 中的 artifact 必须递归进入同一个资产图。
5. 工具结果位置不能承载原生媒体时，由上下文投影层把媒体重定位为带来源标记的临时应用上下文，
   Provider 编码器不得静默删除，也不得自行决定改成 OCR 或占位。
6. 无状态或手工回放协议中，只要带图历史仍在本次上下文内，后续请求重新发送图片是正确行为；
   优化方向是请求 rendition、Provider 文件句柄和媒体预算，不是首轮发图、后续偷偷换成文字。
7. DERIVED 结果必须成为版本化的持久派生记录。三天缓存可以保留为加速层，但不能再充当历史语义
   的唯一来源。
8. 当前用户明确提交且当前请求依赖的附件无法投影时，在调用聊天 Provider 前失败并给出可恢复提示；
   不允许让模型在只收到 `[Image]` 的情况下继续假装看过图片。

### 1.2 Master turn

把失败或中止 turn 丢失定为 P0 持久化缺陷。目标不是在更多 `catch` 中补一次
`saveConversation()`，而是建立一个 Master turn 的权威所有者和统一提交协议：

```text
ChatService
  -> MasterTurnCoordinator
       -> GenerationHandler 产生 Snapshot / Checkpoint / Terminal
       -> ConversationCommitCoordinator 持久化
       -> ConversationSession 只承载实时视图和 dirty revision
```

核心决策：

1. 用户任务、模型 step、工具执行前、工具结果、审批和所有终态都有明确持久化边界。
2. 成功、主动停止、被新 turn 替换、Provider failed、Provider incomplete、传输断开、运行时异常
   最终都进入同一个 terminal finalizer。
3. UI 保留已生成的部分文本；下一次模型上下文只回放协议合法检查点，未完成 reasoning、工具参数、
   未配对工具结果和残缺图片不能伪装成已完成响应。
4. 工具开始执行前先保存稳定 execution id 和 `STARTED`；崩溃后状态不确定的外部副作用禁止自动重放。
5. Session 有未持久化 revision 时不得空闲回收。

---

## 2. 背景与问题边界

### 2.1 “界面可见”与“模型可见”是两件事

当前 `UIMessage` 保存 `Image`、`Document`、`Audio`、`Video` 和嵌套在 `Tool.output` 中的 part。
`ChatMessage` 与工具卡片直接读取这些原始 part，因此图片可以一直显示。

模型请求则先经过 Input Transformer，再由 OpenAI Chat、OpenAI Responses、Claude 或 Gemini
分别编码。当前请求投影不会回写 `Conversation`。因此以下现象可以同时发生：

- 用户和 UI 能看到图片；
- 数据库也保存了图片引用；
- 实际聊天模型只收到 `[Image]`、文件路径字符串、空文本，或者根本没有对应媒体块。

设计和测试必须分别验证持久历史、渲染结果、上下文投影和最终 wire payload，不能用其中一个证明
另外三个正确。

### 2.2 附件不是单一数据类型

不同附件需要不同消费方式：

| 类型 | 原生输入 | 可派生输入 | 当前主要缺口 |
|------|----------|------------|--------------|
| 图片 | 图片内容块或 Provider 文件引用 | 视觉观察文本 | 工具嵌套图片绕过 Adapter；能力和位置判定不完整 |
| PDF | Provider 原生文件或页面图像 | 本地抽取文本、页面 OCR | 当前只做本地文本抽取，扫描页、图表和版式可能丢失 |
| DOCX/PPTX/EPUB | 取决于 Provider | 本地抽取文本 | 每次请求重复解析，派生结果无持久版本 |
| 纯文本/代码 | 原生文件或文本块 | 本地读取文本 | 缺少统一大小、编码和截断回执 |
| 音频/视频 | 取决于模型和协议 | ASR、抽帧等未来派生器 | 当前没有跨协议统一能力与失败策略 |
| 其他二进制文件 | 取决于协议 | 对应类型的解析器 | 无解析器时只能保留引用并明确不可读 |

因此，聊天模型是否支持 `IMAGE` 只能决定图片路径的一部分。最终判定必须落到每个资产、每次请求。

### 2.3 当前图片三态

`ImageInputAdapter.resolveCapability()` 当前提供：

| 状态 | 当前条件 |
|------|----------|
| `NATIVE` | 聊天模型的 `inputModalities` 含 `IMAGE` |
| `DERIVED` | 聊天模型不含 `IMAGE`，但 OCR 模型存在、Provider 可用且 OCR 模型含 `IMAGE` |
| `UNAVAILABLE` | 以上条件不满足 |

本轮已把 `AttachmentInputTransformer` 改为递归扫描 `Tool.output`，并把
`ToolArtifactReplayTransformer` 调整到引用提示和图片 Adapter 之前，因此用户上传图片与
`generate_image` 的本地 artifact 进入同一图片三态判断。Provider 最终放置规则仍由各协议编码器决定；
本轮测试覆盖了请求投影和嵌套发现，完整 wire matrix 仍属于验收项。

设置入口本身也没有形成可靠状态：`Settings.ocrModelId` 用无法解析的随机 UUID 兼容“未配置”，
选择器没有按 IMAGE 输入能力过滤，已设置后也没有显式清除入口；提示词页面还展示 `{images}` 变量，
实际 `ImageInputAdapter.observe()` 并不替换该变量，而是把 Image part 直接交给视觉模型。目标设计需要
同时修正领域能力和设置语义，不能只调整 UI 文案。

### 2.4 当前文档转换

`DocumentAsPromptTransformer` 把 PDF、DOCX、PPTX、EPUB 或普通文本读取为 `<UploadFile>` 文本，
原始 `Document` part 仍留在消息里。多数 Provider 不编码这个原始 part，因此模型主要依赖本地抽取文本。

该策略对文字型文档是可接受降级，但不能宣称为完整多模态文件输入：扫描 PDF、图表、版式和嵌入图片
可能没有进入模型；历史文档每次请求还会重新解析。

### 2.5 当前 `generate_image` 结果

`ImageGenerationTool` 成功后写入：

```text
Tool.metadata.artifact = LocalArtifactRef
Tool.output = [有界 JSON 文本, Image(file://... + attachment_ref)]
```

`LocalArtifactRef` 是内部权威文件引用，模型只看到 `/upload/<safe-file-name>`。这个边界应继续保留。
问题不在文件所有权，而在上下文投影：

- UI 和文件清理会递归看到 `Tool.output.Image`；
- 顶层图片 Adapter 看不到它；
- OpenAI Chat 的官方工具结果位置只能按文本处理；
- Responses 是否允许多媒体 `function_call_output` 还取决于端点 profile；
- Claude 和 Gemini 有各自的工具媒体结构。

本轮先通过正确的 Transformer 顺序和递归图片三态消除了“artifact 未物化或绕过 OCR Adapter”的
确定性缺陷。各协议已有不同的工具媒体编码能力，仍需按第 17 节的 wire matrix 持续验证，不能仅凭
UI 可见性推断模型实际收到图片。

---

## 3. 外部协议和成熟实现带来的约束

### 3.1 协议事实

- OpenAI 图片输入允许 URL、Base64 和文件 ID，多张图片会共同占用输入 token：
  <https://developers.openai.com/api/docs/guides/images-vision>
- OpenAI 文件输入对 PDF 和非 PDF 的处理不同；视觉模型读取 PDF 时会同时使用文本和页面图像，
  其他文档通常以抽取文本为主：
  <https://developers.openai.com/api/docs/guides/file-inputs>
- OpenAI Responses 把输入、输出、工具结果和终态建模为显式 item；`completed`、`failed`、
  `cancelled`、`incomplete` 不是同一种结束：
  <https://developers.openai.com/api/reference/cli/resources/responses/methods/create>
- Claude 图片内容和 Gemini 多媒体 function response 有自己的内容块与位置规则，不能由一个
  OpenAI-shaped DTO 推断：
  <https://platform.claude.com/docs/en/build-with-claude/vision>
  <https://ai.google.dev/gemini-api/docs/generate-content/function-calling>

这些差异说明：能力解析必须包含“媒体能否出现在当前位置”。“模型能看图”不等于“协议允许把图
放进工具结果”。

### 3.2 持久化和失败恢复

- AI SDK 的流结束结果显式区分 abort、disconnect 和 error，而不是只有成功回调：
  <https://ai-sdk.dev/docs/reference/ai-sdk-ui/use-chat>
- LangGraph 在步骤边界创建 checkpoint，并保留故障前已完成节点的 pending writes，用于恢复和
  故障隔离：
  <https://docs.langchain.com/oss/python/langgraph/persistence>

本项目不需要复制这些框架，但应采用相同的底层原则：终态类型化，持久化围绕安全步骤边界，
失败不是“什么都不写”。

---

## 4. 目标领域模型

### 4.1 `AttachmentAsset`

所有来源统一成逻辑资产：

```text
AttachmentAsset
  attachmentRef          // attachment:<uuid>，会话内稳定引用
  localArtifactRef       // 受管文件的内部权威引用
  displayName
  verifiedMimeType
  sizeBytes
  contentHash
  source                 // USER_UPLOAD / TOOL_OUTPUT / REMOTE_DOWNLOAD / PROVIDER_OUTPUT
  sourceLocator          // messageId + part path，或 messageId + toolOrdinal + output path
  availability           // AVAILABLE / MISSING / CORRUPT
```

`AttachmentAsset` 是逻辑视图，可以由现有 `ManagedFileEntity`、`LocalArtifactRef`、part metadata 和
消息坐标组装；不要新增第二套文件真源。后续若增加独立表，只保存稳定索引和派生关系，不复制宿主路径。

工具创建或下载附件时必须遵守同一输出契约：

1. 先通过受管文件 Store 落地并验证真实 MIME、大小和内容；
2. metadata 保存 `LocalArtifactRef`；
3. multimedia part 保存合法 `attachment_ref`；
4. 模型可见 JSON 只暴露 `/upload/<safe-file-name>` 和有界 manifest；
5. 文件、Room、消息提交失败时沿现有所有权协议补偿；
6. 远程下载必须经过重定向、私网、体积、真实 MIME 和内容签名检查，不能把任意 URL 直接登记为
   持久资产；
7. 不允许工具直接返回任意 Android `file:` 路径作为长期协议。

### 4.2 `AttachmentDerivative`

派生内容不是聊天消息文本，而是资产的版本化副产品：

```text
AttachmentDerivative
  attachmentRef
  sourceContentHash
  kind                   // VISUAL_OBSERVATION / DOCUMENT_TEXT / PAGE_TEXT / TRANSCRIPT
  producer               // LOCAL_PARSER / modelId + providerId
  policyVersion
  promptHash
  status                 // READY / FAILED / STALE
  content
  safeError
  createdAt
```

关键规则：

- 同一 `sourceContentHash + kind + producer + policyVersion` 的 READY 结果可复用；
- 修改视觉模型或提示词产生新版本，不覆盖旧版本；
- 某个 turn 实际使用哪个 derivative 记录在投影回执中；
- cacheDir 中的 LRU 只加速读取，不决定历史语义；
- 原始文件删除时，由文件引用与派生记录的共同所有权规则清理。

### 4.3 `AttachmentDeliveryReceipt`

每次 turn 保存附件投影事实，便于审计、UI 解释和故障定位：

```text
AttachmentDeliveryReceipt
  attachmentRef
  sourceLocator
  capability             // NATIVE / DERIVED / UNAVAILABLE
  placement              // ORIGINAL / TOOL_RESULT / APP_CONTEXT
  derivativeIdentity
  requestRendition
  outcome                // DELIVERED / REFERENCE_ONLY / OMITTED / FAILED
  reason
```

回执不包含图片 Base64、宿主路径或完整 Provider 错误体。

---

## 5. 能力与放置位置解析

### 5.1 解析键

新增 `AttachmentCapabilityResolver`，输入至少包括：

```text
ChatModelSnapshot
ProviderType
EndpointProfile
ProtocolFamily
VerifiedMimeType
MessageLocation          // USER_CONTENT / ASSISTANT_CONTENT / TOOL_RESULT
ArtifactAvailability
ConfiguredDerivers
```

输出不是一个 Boolean，而是：

```text
AttachmentCapability
  mode                   // NATIVE / DERIVED / UNAVAILABLE
  nativePlacement        // SAME_LOCATION / RELOCATE_TO_APP_CONTEXT / NONE
  derivativeKind
  limits                 // bytes / dimensions / pages / media count
  failureReason
```

### 5.2 `NATIVE`

同时满足以下条件才是 `NATIVE`：

- 当前模型明确支持对应输入类型；
- 当前 Provider 和 endpoint profile 支持该类型；
- 协议允许媒体出现在原消息位置，或者允许安全重定位到应用上下文；
- 文件存在，真实 MIME、大小、尺寸或页数满足要求；
- 能生成该 Provider 接受的请求 rendition。

只满足 `model.inputModalities` 不够。用户手工把兼容端点标成 IMAGE 时，仍要经过 endpoint 和请求
预检；未知端点采用保守能力，不以 modelId 猜测。

### 5.3 `DERIVED`

原生输入不可用，但存在适配该 MIME 的可靠派生器时使用 `DERIVED`：

- 图片：配置的视觉观察模型；
- 文本型文档：本地解析器；
- 扫描 PDF：未来可使用页面渲染加视觉观察；
- 音频或视频：只有未来配置了明确转录或抽帧派生器后才可用。

派生器必须先产生 READY 记录，再把有来源标签的文本放入模型上下文。失败不能退成无说明的空文本。

### 5.4 `UNAVAILABLE`

原生能力和派生能力都不可用，或文件缺失、损坏、超限且无法生成安全 rendition 时使用
`UNAVAILABLE`。

`UNAVAILABLE` 仍保留 asset 和 UI 渲染事实，但不能伪装成内容已交付。

### 5.5 工具结果媒体的重定位

当模型能看图，但协议不允许在 `TOOL_RESULT` 位置携带图片时：

1. 原工具结果保留协议合法的 JSON 文本和 `attachment_ref`；
2. 所有并行工具结果结束后，投影层追加一个临时、应用拥有的上下文消息；
3. 该消息使用 Provider 允许的 USER 媒体位置，并带稳定来源标签；
4. 临时消息不回写 `Conversation`，但写入 delivery receipt；
5. Provider 编码器只编码已经合法的投影计划，不再自行改角色或丢媒体。

示意：

```text
ASSISTANT: tool_calls(generate_image)
TOOL: {status: completed, attachment_ref: attachment:...}
APP_CONTEXT_AS_USER:
  <app_tool_artifact ref="attachment:..." tool="generate_image">
  Image(...)
ASSISTANT: 下一步模型输出
```

这保持 tool call/result 配对完整，也使 OpenAI Chat 这类工具结果只能是文本的协议仍能把原图交给
视觉模型。Claude、Gemini 或支持多媒体 function output 的 Responses profile 可以留在原位置，
无需重定位。

---

## 6. 三种状态下的明确产品规则

### 6.1 当前附件为 `NATIVE`

#### 用户当前上传

- 发送前先复制或解析为受管文件，验证签名和 MIME，并盖章 `attachment_ref`；
- 原始文件进入持久历史，Provider 请求使用受控 rendition，不修改原件；
- 当前用户 turn 的附件是 REQUIRED。预检或编码失败时不调用聊天模型，保存用户消息和 FAILED turn，
  UI 提供重试、切换模型或移除附件；
- Provider 成功接收的方式写入 delivery receipt。

#### 历史用户附件

- 只要源消息仍在本次上下文内，无状态/手工回放协议就继续发送原生内容；
- 如果较新的消息显式引用一个已被裁剪源 turn 的 `attachment_ref`，上下文依赖解析器可把该资产作为
  应用上下文重新带入；没有显式引用时不复活所有旧附件；
- 历史文件缺失时保留带 ref 的不可用说明和回执。当前用户明确引用该缺失资产时，本 turn 预检失败。

#### `generate_image` 与未来工具产物

- 新产物在当前工具 step 完成后立即进入统一资产图；
- 协议支持工具媒体则原位发送；只支持用户媒体则按 §5.5 重定位；
- 后续请求只要对应工具 turn 仍在上下文内，继续按相同规则投影；
- artifact 缺失时工具历史不改写为“生成失败”，而是保留原成功事实并标记当前内容不可用。

#### 文件

- Provider 和模型真正支持原生文件输入时发送文件块或可复用文件句柄；
- 不支持原生文件时按该 MIME 的派生能力转入 `DERIVED`，不能因为模型支持 IMAGE 就把任意文件
  当图片发送。

### 6.2 当前附件为 `DERIVED`

#### 用户当前上传图片

- 在调用聊天模型之前取得或生成版本化视觉观察；
- 上下文中使用带 `attachment_ref`、文件名、MIME 和派生来源的观察块；
- 原图继续用于 UI，不把观察文字写回用户原消息；
- 当前附件观察失败时将 turn 标记为 FAILED，不让聊天模型只收到错误占位后继续回答；
- 用户切换为原生视觉模型重试时，直接使用原图，不受旧观察限制。

观察块示意：

```text
<attachment_observation
  ref="attachment:..."
  name="screenshot.png"
  mime="image/png"
  producer="visual-model-id"
  rendition="policy-version">
...
</attachment_observation>
```

#### 历史图片

- 按当前 turn 的聊天模型快照选择匹配的 READY derivative；过去 turn 使用过的版本由其 delivery receipt
  保持可审计，但不强制当前 turn 继续使用已经失配的 producer；
- 设置变化只为新投影生成新版本，不覆盖旧记录；
- 若旧版本不存在但原件可用，可以生成新版本，并在本次回执记录实际版本；
- 原件和 derivative 都不可用时进入 `UNAVAILABLE`，禁止使用过期缓存冒充成功。

#### `generate_image` 与未来图片工具

- 工具文件成功和视觉观察成功是两个独立事实；
- 图片生成成功后，自动为新产物生成一次可复用的基础视觉观察，并把观察文本与 artifact manifest
  一起交给文本聊天模型；
- 观察失败不能回滚已经成功、可展示的图片，也不能把工具改记为生成失败；工具结果写
  `artifact_created + content_delivery_failed`，模型得到明确不可见说明，UI 保留原图和警告；
- 后续历史复用同一 derivative，不在每次请求重新收费调用视觉模型。

当前阶段不增加任务特定的二次看图工具，因此基础观察提示必须继续覆盖可见文字、对象、图标、布局和
空间关系，并明确不替用户完成任务推理。

#### 文档

- 本地文本抽取也属于 `DERIVED`，结果按内容哈希和解析器版本持久化；
- 扫描 PDF 没有可用文字时，只有配置了页面视觉派生链才算可读；否则明确 `UNAVAILABLE`；
- 文档正文过大时记录截断范围和原因，禁止无提示截断。

### 6.3 当前附件为 `UNAVAILABLE`

#### 用户当前上传或明确引用

- 保存用户消息、附件和 turn 记录；
- Provider 请求前结束为 `FAILED(ATTACHMENT_INPUT_UNAVAILABLE)`；
- UI 明确说明当前模型不能读取该附件，并提供切换支持模型、配置视觉观察模型、移除附件后重试；
- 不再把 `[Image]` 当作已经处理的正常输入。

#### 历史但当前未明确引用

- 在原位置保留简短、结构化的不可用 manifest，使模型知道历史中存在附件但不知道内容；
- 允许当前请求继续，delivery receipt 记录 `REFERENCE_ONLY`；
- 不发送宿主路径、过期 URL 或伪造描述。

示意：

```text
<attachment_unavailable
  ref="attachment:..."
  name="screenshot.png"
  mime="image/png"
  reason="current_model_cannot_consume_content" />
```

#### `generate_image` 与未来工具产物

- 工具仍可成功创建文件，UI 仍展示或提供下载；
- 文本聊天模型只收到成功 manifest、稳定 ref 和“内容未交付给当前模型”的明确字段；
- 不因聊天模型不能看图而删除产物或把生成结果改成失败；
- 后续用户要求分析该产物时，在请求前阻断并引导切换能力，不让模型猜测。

### 6.4 汇总矩阵

| 来源 | `NATIVE` | `DERIVED` | `UNAVAILABLE` |
|------|----------|-----------|---------------|
| 当前用户图片 | 原生媒体；失败则请求前终止 | 持久观察文本；观察失败则请求前终止 | 保存历史并明确终止，不调用聊天模型 |
| 历史用户图片 | 在上下文内继续原生回放 | 复用版本化观察 | 未被当前引用时仅 manifest；被引用时终止 |
| 当前工具图片 | 原位媒体或应用上下文重定位 | 生成一次观察；图片成功与观察失败分别记录 | 工具成功保留，模型只收 manifest 和不可见说明 |
| 历史工具图片 | 随完整工具 turn 投影 | 复用观察 | 仅 manifest；显式依赖时终止 |
| 文字型文档 | 原生文件或按协议降级 | 持久化本地抽取文本 | 没有派生器时仅 manifest |
| 不支持的二进制文件 | 协议明确支持才原生发送 | 有匹配派生器才转换 | 仅引用和明确不可读状态 |

---

## 7. 为什么原生图片需要在后续请求重复发送

当前 OpenAI Responses 使用 `store=false` 手工回放，Chat Completions、Claude、Gemini 也由客户端
重新构建历史。在这种模式下，模型没有本地 `Conversation`。如果首轮发送图片、下一轮只保留
`[Image]`，下一轮就不再具备观察原图的能力。

因此正确规则是：

> 只要带图消息仍属于本次选定上下文，且没有可验证的 Provider 服务器状态引用，客户端就必须重新
> 发送图片或等价的原生文件引用。

“基本正确”中的不足不在重复发送本身，而在当前缺少以下保障：

1. 只判断模型 IMAGE 标记，没有完整 Provider、端点、MIME 和消息位置矩阵；
2. 只处理顶层本地图片，工具结果和 replay 后图片没有统一投影；
3. 文档、音频、视频没有走同一能力解析；
4. 部分编码失败会静默变成空文本或被忽略；
5. 消息条数裁剪不知道图片、PDF 和 Tool schema 的成本；
6. 没有稳定的请求 rendition 和 Provider 文件句柄缓存；
7. 缺失历史文件与当前必需文件没有区分处理；
8. UI 没有告诉用户某个附件本轮究竟以原图、派生文本还是不可用引用交付。

### 7.1 合理优化

- 保留原始高质量文件，按 Provider 限制生成请求专用缩放或压缩 rendition；缓存键包含内容哈希和
  rendition policy，不重复处理文件；
- Provider 支持上传文件 ID 或等价句柄时，把它当可失效缓存使用，过期后由本地原件重建；
  Provider 句柄不是资产真源；
- 上下文规划加入媒体成本预估，超预算时按完整 USER turn 裁剪，不拆散 tool call/result；
- 当前用户附件永不因预算静默删除；需要裁剪时先裁更早完整 turn，仍无法满足则在请求前给出明确错误；
- 相同 `attachment_ref` 在一次请求中重复出现时可在经过协议回归验证后复用 Provider 文件句柄，
  但第一阶段不通过文本引用替代原图，以免改变时序语义；
- 模型切换时从原始资产重新计算投影：视觉模型用 NATIVE，文本加观察模型用 DERIVED，纯文本用
  UNAVAILABLE；不改写过去 turn 的 delivery receipt。

---

## 8. 目标上下文管道

当前独立的 `DocumentAsPromptTransformer`、`AttachmentRefHintTransformer`、
`AttachmentInputTransformer` 和末尾 `ToolArtifactReplayTransformer` 应收口为有序、可验证的阶段：

```text
Conversation.currentMessages
  -> ContextSelector
       选择完整 USER turn；保留 Provider 回放边界
  -> ArtifactMaterializer
       递归物化 Tool.metadata 和 Tool.output；验证受管文件
  -> AttachmentGraphResolver
       统一发现顶层与嵌套媒体；解析显式 attachment_ref 依赖
  -> AttachmentCapabilityResolver
       按模型、Provider、端点、MIME、位置输出三态和 placement
  -> AttachmentDerivativeService
       读取或生成版本化观察、文档文本等派生内容
  -> ContextProjectionBuilder
       生成原位 part、应用上下文重定位、引用提示和 delivery receipt
  -> 其余 Prompt Transformer
       Time / PromptInjection / Placeholder / Template / Workspace
  -> ProviderRequestValidator
       校验角色、tool 配对、媒体限制、文件存在和请求预算
  -> Provider Encoder
       只做协议无损编码；遇到不支持的投影立即抛类型化错误
```

约束：

- `ContextSelector` 仍先于昂贵的 OCR 和文档解析；
- `ToolArtifactReplayTransformer` 的物化职责必须先于附件能力判定；
- 递归遍历使用一个共用 walker，不能让 Hint、文件清理和 Adapter 各自理解不同的树；
- Provider Encoder 不再包含“如果不支持图片就放一个占位”这类产品降级；
- 投影是请求视图，不回写原始 `Conversation`；派生记录和 delivery receipt 例外，它们是可审计事实；
- Tool system prompt 和 Provider 请求继续使用同一个 `contextMessages` 选择结果。

---

## 9. 渲染与用户反馈

渲染继续以原始消息和受管 asset 为真源，不渲染请求投影产生的临时文本。需要新增投影状态反馈，但不把
它混入消息正文：

- 原生交付：可显示“已作为图片发送”；
- 派生交付：显示“已通过视觉观察模型读取”，可查看使用的模型；
- 未交付：显示“当前模型无法读取”，并提供可执行修复入口；
- 文件缺失：区分“工具当时成功、当前本地副本缺失”和“工具执行失败”；
- turn 失败：错误卡持久化，重新打开会话后仍存在；
- 导出对话时默认导出原始历史和终态，不把请求专用 APP_CONTEXT 伪装成用户消息。

delivery receipt 属于 turn 调试和解释信息。普通 UI 可只显示简短状态，高级详情再展示具体 projection、
placement 和安全错误原因。

---

## 10. Master turn 丢失的审查根因与本轮修复

以下是本轮审查时确认的原实现根因。它解释了为什么失败 turn 看起来会消失，也是本轮回归测试的
来源：

1. `GenerationChunk.Messages` 只更新 `ConversationSession.state`；
2. Master collector 明确忽略 `GenerationChunk.Checkpoint`；
3. `onCompletion` 只在内存中结束 reasoning 和发送通知；
4. 非取消异常只写瞬时 `ChatError`；
5. 取消直接退出 failure 分支；
6. 只有整个 Flow `onSuccess` 才调用 `saveConversation()`；
7. `stopGeneration()` 只有在 `finishInterruptedPendingTools()` 确实修改了工具时才会间接保存；
8. 没有未完成工具时，部分文本、reasoning 和 Provider metadata 不落盘；
9. 页面无引用且 Job 结束后，`ConversationSession` 可以被空闲回收；
10. 应用进程退出时所有仅在 Session 中的内容直接消失。

媒体还有额外的不一致：正常 step 完成才调用 Output Transformer 的 `onGenerationFinish()` 完成
Base64 图片落地；中断时可能留下 `data:` part。`ConversationRepository` 又拒绝持久化顶层 Base64，
而 `UIMessage.hasBase64Part()` 没有递归 `Tool.output`。因此相同问题既可能让整个保存失败，也可能让
嵌套 Base64 绕过保护进入消息 JSON。

于是会出现：

| 中断点 | 数据库中的典型结果 |
|--------|--------------------|
| Provider 首个输出前失败 | USER 已保存，没有持久错误终态 |
| 流式文本中停止或失败 | 当前页面暂时可见，重新打开后只剩 USER |
| 工具调用参数流中失败 | 未闭合工具可能只存在内存，下一轮无法安全回放 |
| 工具执行成功、下一模型 step 失败 | 外部副作用存在，但工具调用和结果可能没有历史记录 |
| 流式图片中取消 | 可能留下不可持久化的残缺 `data:` part |
| 很快发送下一条消息 | 新 USER 的保存可能偶然把旧内存快照一并落盘，结果取决于时序 |

最后一种“偶然保存”不是正确性保证，反而会让问题难以稳定复现。

本轮修复后，`GenerationHandler` 不再依赖 collector 异步消费一个无确认事件来表达持久化边界；
`onCheckpoint(GenerationCheckpoint)` 返回即表示 Room transaction 已提交，否则异常会阻止后续工具
副作用或 Provider step。所有 Master 终态进入同一有界重试 finalizer，并由 TurnExecution 保留进程恢复
所需事实。上述表格现在是历史缺陷与回归场景，不再是目标运行行为。

---

## 11. Turn 领域模型与状态机

### 11.1 `TurnExecution`

新增持久化的 Master turn 记录，以 `turnId` 为权威身份：

```text
TurnExecution
  turnId
  conversationId
  userMessageId
  responseMessageId
  status
  terminalReason
  safeError
  stateRevision
  lastPersistedRevision
  lastSafeCheckpoint
  startedAt
  finishedAt
  attachmentDeliveryReceipts
```

当前 Room v5 已持久化保证恢复与审计所需的最小字段：`turnId`、`conversationId`、
`assistantMessageId`、`status`、`reason`、`createdAt`、`updatedAt`。上面其余字段仍是后续扩展目标；
消息安全 checkpoint 当前与 MessageNode 在同一 transaction 提交，附件 delivery receipt 尚未建表。

`responseMessageId` 在发起 Provider 请求前预分配。即使首个 token 前失败，也能给该 USER turn 保存一个
明确失败记录和响应槽位。重新生成同一 USER 消息创建新 `turnId` 和新响应分支，不重复插入 USER。

### 11.2 状态

```text
CREATED -> RUNNING <-> AWAITING_APPROVAL
                   -> COMPLETED
                   -> CANCELLED
                   -> INCOMPLETE
                   -> FAILED
                   -> INTERRUPTED
```

含义：

- `COMPLETED`：Provider 和本地工具循环正常完成；
- `CANCELLED`：用户停止、被新 turn 替换、删除会话等有意取消；
- `INCOMPLETE`：Provider 明确 incomplete、step limit 等有可见输出但任务未完整完成的终态；
- `FAILED`：Provider failed、请求编码、附件投影、持久化前置条件或本地运行时错误；
- `INTERRUPTED`：进程重启时发现仍为 RUNNING，无法证明正常结束；
- `AWAITING_APPROVAL`：已持久化的可恢复状态，不是失败也不是普通完成。

`terminalReason` 使用稳定英文枚举，例如：

```text
user_stop
superseded_by_new_turn
attachment_input_unavailable
provider_failed
provider_incomplete
transport_closed_before_terminal
protocol_error
tool_loop_limit
runtime_error
process_restarted
```

原始响应体和堆栈只进受控日志；数据库只保存有界、安全、可本地化映射的分类和摘要。

### 11.3 工具执行状态

每个工具执行以 `turnId + messageId + toolOrdinal` 形成稳定 execution id：

```text
PLANNED -> STARTED -> COMPLETED
                   -> FAILED_AS_RESULT
                   -> UNKNOWN_OUTCOME
```

- `STARTED` 必须在调用可能有外部副作用的工具前持久化；
- 正常异常和 timeout 可以写成结构化工具结果，进入 `FAILED_AS_RESULT`，对话继续；
- 进程在外部调用后、结果提交前退出时，启动恢复标记 `UNKNOWN_OUTCOME`；
- `UNKNOWN_OUTCOME` 禁止自动重试，除非该工具提供经过验证的幂等键或查询接口；
- `generate_image` 的文件所有权、产物提交和 tool result checkpoint 必须保持现有补偿协议。

---

## 12. Turn 事件与持久化协议

### 12.1 事件

`GenerationHandler` 保持负责 Provider 请求和工具循环，但输出类型化生命周期：

```text
TurnEvent
  Snapshot(revision, messages)
  Phase(...)
  Checkpoint(revision, kind)
  Terminal(revision, outcome)
```

Flow 抛异常仍允许作为编程和传输兜底，但 `MasterTurnCoordinator` 必须映射为 Terminal，不能让异常绕过
终态提交。

建议检查点：

```text
USER_TASK_PERSISTED
MODEL_STEP_COMPLETED
TOOL_EXECUTION_STARTED
TOOL_STATE_CHANGED
TOOL_RESULT_PERSISTED
AWAITING_APPROVAL
TERMINAL_STATE
```

### 12.2 提交顺序

#### Turn 开始

```text
同一 Room transaction：
  保存 USER 消息/分支选择
  创建 TurnExecution(CREATED)
  预分配 responseMessageId
提交成功
  -> Session 发布 RUNNING
  -> 执行附件投影预检
  -> 调用 Provider
```

#### 流式 Snapshot

- 每个 token 只更新 Session 和递增 `stateRevision`；
- 以受控节奏保存可见文本草稿，限制进程崩溃的丢失窗口；
- 不对每个 token 删除重插完整消息树；可对当前 response node 增加定向 upsert，或使用独立 turn draft；
- reasoning、图片和工具 delta 必须保持同一 revision，不允许分别写出相互不一致的快照。

#### 安全检查点

- 先执行 Output Transformer 的持久化收口，再提交 Conversation 当前节点和 TurnExecution；
- 提交成功后更新 `lastPersistedRevision` 和 `lastSafeCheckpoint`；
- 工具执行前检查点必须先落盘，再调用外部工具；
- 工具结果和 artifact owner 提交成功后，才能进入下一模型 step。

#### 终态

所有结果进入一个有界的 `NonCancellable` terminal finalizer：

1. 读取最新 revision，而不是开始时捕获的旧 `Conversation`；
2. 结束可保留的 reasoning，清理未完成协议 part；
3. 完成有效媒体落地；残缺 Base64 图片替换为明确的本地失败标记，不能阻断其他文本保存；
4. 把 pending 或执行中断工具转换成匹配实际原因的终态；
5. 写 Turn status、reason、delivery receipt 和最后可见快照；
6. 原子提交后才发完成/失败通知和标题、建议等边缘任务；
7. 提交失败时 Session 保持 dirty、禁止回收，显示存储错误并在后台重试，不能假装已完成。

`onSuccess` 不再是唯一保存入口，`onCompletion` 也不承担隐藏的消息修复。

### 12.3 单一提交所有者

新增 `ConversationCommitCoordinator`，按 conversationId 串行化消息树提交并拒绝旧 revision 覆盖新状态：

```text
commit(turnId, revision, conversation, turnState)
  if revision < lastPersistedRevision: ignore stale write
  else Room transaction:
       patch conversation header
       upsert affected message node
       upsert TurnExecution
```

标题、建议和文件夹继续使用窄列 patch；禁止用早先捕获的整份 `Conversation` 覆盖后来 checkpoint。

---

## 13. 失败、中止后的历史与下一次上下文

需要同时保存两个视图：

- 用户可见历史：保留已经显示的部分文本和持久错误终态；
- Provider 可重放历史：只包含协议合法检查点。

规则：

| 内容 | UI 历史 | 下一次普通请求 |
|------|---------|----------------|
| 已完成模型 step | 保留 | 原样回放，含必要 Provider metadata |
| 已持久化工具调用和结果 | 保留 | 按协议成对回放 |
| 部分可见 assistant 文本 | 保留并标注中断 | 作为应用注入的 interrupted transcript，不冒充完成 assistant output |
| 未完成 reasoning | 可折叠显示已中断 | 不回放 |
| 半截工具参数 | 仅显示失败状态 | 不回放 |
| 工具 `STARTED` 无结果 | 显示结果未知 | 不自动执行；等待用户处理 |
| 残缺图片数据 | 显示媒体失败占位 | 不回放 Base64 |
| transient ChatError | 转成持久 turn error | 错误卡本身不作为模型内容 |

应用注入的部分文本示意：

```text
<interrupted_assistant_output
  turn="..."
  status="cancelled">
  仅包含经过清理的可见文本，不含 reasoning 和工具协议碎片
</interrupted_assistant_output>
```

该块让模型理解用户界面已经显示过什么，但不会伪造 Provider 已正常完成。重新生成原 USER 消息时，
从最后安全检查点创建新 response 分支，不注入旧部分草稿。

### 13.1 用户主动停止

```text
用户点击停止
  -> MasterTurnCoordinator.cancel(USER_STOP)
  -> 取消 Provider/工具协程并等待传播
  -> terminal finalizer 保存最新可见 snapshot
  -> 未完成工具按真实状态收口
  -> TurnExecution = CANCELLED
```

无论当前是否存在 Tool，都必须保存；不能再以 `finishInterruptedPendingTools()` 是否发生变化作为保存条件。

### 13.2 新消息替换旧 turn

新消息必须先 `cancelAndFinalize(SUPERSEDED_BY_NEW_TURN)`，确认旧 turn 已提交后，再原子追加新 USER。
这样新消息不会偶然承担旧 turn 的落盘职责。

### 13.3 Provider 或协议错误

- 保存 Provider 已经产生的可见 snapshot；
- 只保存已经闭合、可无损回放的 opaque metadata；
- `response.failed`、`response.incomplete` 和“未收到 terminal 就断流”分别记录；
- 不把 closed-before-terminal 当作 completed；
- 失败前已 checkpoint 的工具结果保留。

### 13.4 进程重启

启动恢复扫描非终态 `TurnExecution`：

- `RUNNING` 没有活跃进程所有者：标为 `INTERRUPTED(PROCESS_RESTARTED)`；
- 恢复最后持久 snapshot；
- 工具 `STARTED` 无结果：标 `UNKNOWN_OUTCOME`，禁止自动重试；
- `AWAITING_APPROVAL` 保持可恢复，不误标失败；
- 完成附件和文件引用对账，不删除仍被中断 turn 引用的产物。

---

## 14. 组件职责

| 组件 | 唯一职责 | 明确不负责 |
|------|----------|------------|
| `ChatService` | UI/业务入口，启动、停止、重试和分支命令 | 不直接收集 token 并决定持久化 |
| `MasterTurnCoordinator` | 一个 Master turn 的权威 owner、取消、事件收集、终态收口 | 不编码 Provider JSON |
| `GenerationHandler` | 模型 step、工具循环、类型化事件 | 不直接写 Room，不决定 Session 回收 |
| `ConversationCommitCoordinator` | 按会话 revision 串行提交 Conversation 和 TurnExecution | 不执行工具或派生附件 |
| `ConversationSession` | 实时 UI state、active job、state/persisted revision | 不作为持久真源 |
| `TurnRecoveryService` | 启动时收口遗留 RUNNING turn 和未知工具状态 | 不自动重放外部副作用 |
| `ArtifactMaterializer` | 把 artifact metadata 解析为安全本地资产 | 不决定模型能力 |
| `AttachmentGraphResolver` | 递归发现 part、Tool.output、显式 ref 依赖 | 不调用 OCR 或 Provider |
| `AttachmentCapabilityResolver` | 按协议、位置、MIME 选择三态和 placement | 不读取或改写聊天 UI |
| `AttachmentDerivativeService` | 版本化视觉观察、文档抽取等派生内容 | 不拼 Provider 消息角色 |
| `ContextProjectionBuilder` | 生成请求视图、重定位媒体、回执 | 不持久化原始消息改写 |
| `ProviderRequestValidator` | 在发网前验证协议结构、文件与限制 | 不静默降级 |
| Provider Encoder | 协议字段和不透明状态的无损编码 | 不决定 OCR、占位、裁剪或产品提示 |
| Renderer | 显示原始历史、artifact 和持久终态 | 不以请求投影为内容真源 |

这是本设计的核心边界：原始事实、派生事实、请求视图、协议编码和 UI 显示各有唯一 owner。

---

## 15. 兼容与迁移

### 15.1 历史附件

- 继续使用现有 `attachment_ref` backfill；
- `LocalArtifactRef` 和 `/upload/<safe-file-name>` 契约不变；
- 首次需要投影时按现有文件生成 content hash 和 derivative；
- 旧工具结果只有路径、没有 artifact metadata 时，仅在受管 upload 目录和 `managed_files` 可验证时升级；
- 无法验证的宿主路径不自动信任。

### 15.2 历史 turn

- 没有 `TurnExecution` 的旧消息由兼容读取层按 completed 历史处理，不新增持久化
  `LEGACY_COMPLETED` 枚举，也不批量猜测失败状态；
- 只有迁移时能明确发现未执行 Tool，才标记 legacy interrupted，不自动执行；
- 新表或新 JSON 字段使用默认值和显式 Room migration；
- 备份、恢复、fork、clone、删除、FTS 和文件引用扫描必须同步识别 TurnExecution 与 derivative 的所有权。

### 15.3 设置

- 当前“OCR 模型”产品名称改为“视觉观察模型”；
- 选择器只显示经过能力解析可接收 IMAGE 的模型；
- 支持显式清除，不再用无法解析的随机 UUID 表达“未配置”；
- 移除没有实际替换的 `{images}` 变量说明；
- 设置变化影响新 derivative 版本，不破坏旧 delivery receipt。

---

## 16. 实施计划

### 阶段 A：先锁定缺陷和契约

状态：核心 turn、上下文安全投影和图片失败契约已完成；完整协议 golden 继续补充。

- 为 Master turn 增加失败/取消持久化的回归测试，先证明当前会丢失；
- 为用户顶层图片、`generate_image` 嵌套图片和文档建立当前 wire payload golden；
- 锁定 `attachment_ref`、`LocalArtifactRef`、`messageId + toolOrdinal` 和 Responses
  `outputItemGroups` 等现有稳定契约；
- 文档和测试明确区分原始历史、投影视图、Provider payload 和渲染。

### 阶段 B：修复 Master turn P0

状态：已完成。协调职责目前集中实现，独立 `MasterTurnCoordinator` 类的抽取不是正确性前置条件。

- 引入 `TurnExecution`、typed terminal 和 `MasterTurnCoordinator`；
- Master collector 开始消费 checkpoint；
- 加入统一 `NonCancellable` terminal finalizer；
- 手动停止、被新 turn 替换和 Provider 错误都保存最新 snapshot；
- Session 增加 dirty revision gate；
- 工具执行前增加 universal `TOOL_EXECUTION_STARTED` checkpoint；
- 启动恢复标记 interrupted 和 unknown outcome。

阶段 B 不等待多模态重构完成；它先保证任何后续附件失败也能留下可靠历史。

### 阶段 C：统一资产发现与上下文投影

状态：部分完成。artifact 顺序、递归 `Tool.output` 图片、三态和 terminal replay-safe 投影已落地；
完整 `AttachmentGraphResolver`、placement matrix 与 delivery receipt 未完成。

- 抽取共用递归 part walker 和 `AttachmentGraphResolver`；
- 把 Tool artifact 物化移到附件判定之前；
- 建立 `AttachmentCapabilityResolver` 和 Provider/endpoint/location 矩阵；
- 建立 `ContextProjectionBuilder` 和 delivery receipt；
- Provider 编码器移除静默占位和静默 omission，改成类型化校验错误；
- 为不支持工具媒体的位置实现 APP_CONTEXT 重定位。

### 阶段 D：派生内容持久化

状态：未开始，保留为下一阶段。

- 新增 `AttachmentDerivative` Store；
- 迁移视觉观察缓存为“持久记录 + LRU 加速”；
- 文档本地抽取按内容哈希和解析器版本持久化；
- 区分当前必需附件失败与历史非必需附件不可用；
- 修正视觉观察设置和五语言资源。

### 阶段 E：媒体预算和文件能力

状态：未开始，保留为下一阶段。

- 增加图片 request rendition、文件句柄缓存和限制预检；
- 上下文规划纳入媒体成本并保持完整 USER/tool 边界；
- 按实际支持补充 PDF 原生输入、扫描页派生、音频/视频派生器；
- 未实现的类型继续明确 UNAVAILABLE，不做推测性降级。

### 阶段 F：收口文档与兼容链路

状态：本设计文档和 Generation Pipeline 参考文档在本轮同步；设备级与全部附件类型验收仍待执行。

- 更新 `chat-generation-pipeline.md`、`protocol-reference.md`、
  `sub-assistant-multimodal.md` 和 `prompts-and-tools.md`；
- 验证 fork、clone、导出、备份恢复、删除和文件清理；
- 完成设备级停止、后台、进程重启和低存储回归。

---

## 17. 验收矩阵

### 17.1 附件来源与状态

- 当前用户上传、历史用户附件、`generate_image`、Provider 原生图片、MCP 图片、未来下载文件工具；
- 顶层 part、嵌套 `Tool.output`、只有 artifact metadata、显式 `attachment_ref` 依赖；
- NATIVE、DERIVED、UNAVAILABLE；
- 文件可用、缺失、损坏、真实 MIME 不符、超大小或页数；
- 当前必需附件与历史非必需附件；
- 模型和 Provider 在相邻 turn 间切换。

### 17.2 协议

- OpenAI Chat：用户图片、文本 tool result 加 APP_CONTEXT 媒体；
- OpenAI Responses：原生 `input_image`、支持和不支持多媒体 function output 的 profile、
  `store=false` 完整回放；
- Claude：用户 content block、tool_result 媒体、redacted thinking 保持；
- Gemini：inlineData、function response 媒体、thought signature 保持；
- 未知兼容端点：保守能力与请求前失败，不通过 modelId 猜测。

最终断言必须检查实际请求 JSON/Part，不只检查 UIMessage。

### 17.3 Turn 终态

- 首 token 前停止、文本流中停止、reasoning 流中停止；
- 工具参数流中断、工具执行前取消、工具副作用后崩溃、工具结果后 Provider 失败；
- 图片 Base64 流中停止、媒体落地失败；
- Provider HTTP 错误、SSE 未见 terminal 断开、failed、incomplete、内容拦截；
- 等待审批后退出、恢复、拒绝和回答；
- 新消息替换旧 turn、重新生成分支；
- 页面释放超过 Session idle timeout、进程重启；
- terminal commit 失败时 Session 不回收、旧 revision 不覆盖新 revision。

每项都要验证：

```text
Room 重载后的历史
TurnExecution 终态
工具副作用和 execution status
下一次 Provider payload
附件 delivery receipt
UI 错误与重试动作
最终文件引用和所有权
```

### 17.4 构建和人工验证

- 相关 JVM 单元测试与协议 golden；
- Room migration 和旧备份恢复；
- Lint、Debug、Release；
- 固定设备上的前后台、停止、进程杀死、附件上传和文生图回归；
- 构建成功不能替代设备/UI 验收，未运行的矩阵必须明确标注。

---

## 18. 完成标准

只有同时满足以下条件，才算解决本设计中的问题：

- 任意附件来源都先成为稳定 asset，再按同一三态投影；
- NATIVE 的实际可用性由完整能力和位置矩阵证明，不再只依赖模型 IMAGE 标记；
- `generate_image` 和未来工具附件不会因嵌套位置绕过投影；
- 文本模型的视觉观察和文档抽取是可复用、可追溯的持久派生事实；
- UNAVAILABLE 对当前必需附件请求前失败，对历史非必需附件明确降级；
- Provider 编码不再静默删除附件；
- 成功、停止、失败、incomplete 和进程中断都留下持久 turn 终态；
- 部分可见文本保留，下一次请求只使用协议安全历史；
- 外部工具副作用不会因为本地 turn 丢失而失去审计记录，也不会在未知结果时被自动重放；
- Session 只有在状态已持久化后才能回收；
- 用户能判断每个附件本轮是以原生内容、派生文本还是不可用引用交给了模型。
