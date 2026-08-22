# 多模态附件上下文与 Turn 持久化

> 本文是当前实现参考，说明附件事实、请求级投影、按需附件识别，以及 Master Turn / Tool 执行的持久化不变量。
>
> 当前完整识别范围是 **Image**。Document / Audio / Video 保留稳定附件身份；Document 的文本消费仍由现有文档转换链负责。

## 1. 文档职责

这份文档只记录跨模块必须共同遵守的契约，不重复其他参考文档已经负责的细节：

| 主题 | 参考文档 |
|---|---|
| 完整生成流程、Transformer 与工具循环顺序 | [chat-generation-pipeline.md](chat-generation-pipeline.md) |
| 模型实际看到的提示词、工具 description / schema / Tool Result | [prompts-and-tools.md](prompts-and-tools.md) |
| 子助手附件入站、交付物出站、metadata 与卡片 | [sub-assistant-multimodal.md](sub-assistant-multimodal.md) |
| 子助手生命周期、lineage、lease、撤权与恢复 | [sub-assistant-architecture.md](sub-assistant-architecture.md) |
| Provider 协议编码与兼容边界 | [protocol-reference.md](protocol-reference.md) |
| 消息与工具卡片渲染 | [message-rendering-pipeline.md](message-rendering-pipeline.md) |
| 持久化介质、文件、备份与同步 | [../dev/persistent-records-and-sync.md](../dev/persistent-records-and-sync.md) |
| 上下文裁剪与显式压缩 | [../dev/context-management.md](../dev/context-management.md) |

本文关注的是以下四层不要互相污染：

```text
Durable Conversation / Artifact Fact
                │
                ▼
       Request-time Model View
                │
                ▼
          Provider Request

Turn / Tool Execution Facts  ── 与上述附件事实一起在明确 checkpoint 持久化
```

核心原则：**Conversation 保存事实；请求投影表达当前能力；Tool Result 保存真实执行结果；Provider 适配只负责线协议。**

## 2. 附件事实与稳定身份

### 2.1 `attachment:<uuid>` 是引用身份

用户上传、工具产物和模型落盘媒体都通过 `UIMessagePart` 保存原始附件，并在 metadata 中使用稳定引用：

```text
attachment_ref = attachment:<uuid>
```

`AttachmentRefs` 负责解析、规范化、metadata merge、补齐以及 file URL 辅助。已有合法 ref 必须保留；写 metadata 时必须 merge，不能覆盖 Provider metadata 或 Tool metadata。

`attachment:<uuid>` 表示“这一个会话附件事实”。它不是 Android 文件路径，也不是 Provider file id。

### 2.2 `/upload/...` 是文件访问身份

模型可使用的文件路径与附件引用是两个不同平面：

```text
attachment:<uuid>   用于引用附件、传给 inspect_attachments / assistant_call.attachments
/upload/<file>      用于 Workspace / Tool 读取文件字节
```

内部 `file://`、`filesDir` relative path、Provider 临时资源 id 不应成为模型层的稳定身份。

### 2.3 ref 在持久化边界生成，不由投影器制造

当前主要盖章位置：

- 用户发送 / 编辑后的附件进入 Conversation 前；
- `generate_image` 成功构造 `Tool.output` Image 时；
- MCP 图片内容落地时；
- Base64 模型图片落成本地文件时；
- Master 附件注入 Child 时复制原 ref；
- 旧历史缺 ref 时由 Master 写路径做一次 backfill。

因此 `AttachmentProjectionTransformer` 只读取附件事实，不负责把临时 ref 写回 Conversation。

### 2.4 文件可用性不改写历史执行事实

Tool artifact 的 `LocalArtifactRef` / managed file 用于重新物化历史 Image 和 `/upload/...` 路径。文件后来被显式删除或丢失时，当前请求与 UI 应体现“资源不可用”，但不能把历史 `completed` Tool 执行改写成 `failed`。

当前实现没有为了附件识别新增 `AttachmentAsset`、`AttachmentDerivative`、Delivery Receipt 或识别结果数据库。识别结果若真实发生，就是普通 Tool Result。

## 3. 请求级附件投影

附件投影是无状态的 Model View 变换，不修改 durable Conversation。

### 3.1 Image 只有两种当前投影

当前实现以本次 resolved `Model.inputModalities` 是否包含 `IMAGE` 为判断：

| 当前模型 | Model View |
|---|---|
| 接收 Image | `[Attachment ref=... type=image name="..."]` + 原始 Image part |
| 不接收 Image | 只保留 `[Attachment ref=... type=image name="..."]`，并在本次请求末尾追加一次不可见提示 |

没有 `DERIVED` 第三态。投影阶段不会调用其他模型，也不会因为聊天模型不能看图而让整个 Turn fail-fast。

`AttachmentProjectionTransformer` 递归处理顶层媒体和 `Tool.output`，因此用户图片、`generate_image`、MCP 图片和 Child 回传 Image 使用同一语义。

Document / Audio / Video 当前保留原 part，并在有 stable ref 时增加引用行；它们没有接入本轮 `inspect_attachments` 的内容识别。Document 文本读取继续由 `DocumentAsPromptTransformer` 负责，不在附件投影中再实现一套解析器。

### 3.2 UI 可见不等于模型收到像素

UI 可以从 durable attachment / artifact 显示缩略图，即使当前聊天模型只收到引用行。因此必须始终区分：

```text
附件存在 != 当前模型能读取附件
UI 可显示附件 != 当前模型收到附件像素
attachment_ref != path
模型能力 != Conversation 历史
```

模型切换只重新计算下一次请求的 Model View，不迁移、不重写历史附件。

### 3.3 当前能力边界

当前 native Image 判定仍以 `Model.inputModalities` 为主，没有独立的“Model × Provider × endpoint profile”能力矩阵。若用户把实际不支持视觉输入的兼容端点配置成 IMAGE 模型，请求仍可能在 Provider / 网关层失败。

这是当前协议能力边界，不应通过恢复自动 OCR、后台识别或持久化 A/B/C 状态来掩盖。Provider placement 与具体 wire 行为由协议层负责。

## 4. 按需附件识别

当聊天模型不接收 Image，而设置中存在有效的附件识别模型时，Runtime 在该 run 的工具集中提供 `inspect_attachments`。精确 description、参数文案和失败 JSON 以 [prompts-and-tools.md](prompts-and-tools.md) 为准。

当前契约只有几个关键点：

- 识别是**显式 ToolCall**，附件存在本身不会触发第二次模型调用；
- 当前只接受 1–4 个稳定 `attachment:<uuid>` Image refs；
- `AttachmentResolver.resolveImages()` all-or-nothing，并保持输入和解析结果 **1:1、有序**；重复 ref 或多个 ref 指向同一文件也不改变顺序；
- 同一远程 URL 在一次批量解析中只 fetch / 落盘一次；
- 多图在一次 inspection model 调用中按序提供，便于比较；
- 成功只返回普通 Text Tool Result；不建立识别 cache / derivative store；
- inspection model 只接收固定安全指令、点名的图片和当前 `request`，不继承主会话完整历史或主工具集。

### 4.1 Tool 只获得最小资源能力

`ToolExecutionContext` 不把完整 Conversation / `List<UIMessage>` 暴露给工具，而提供窄的：

```text
stable refs -> resolveAttachments(...) -> resolved parts / failure reason
```

Runtime 内部可以用执行时刻的 durable 消息状态定位本 run 已完成的 Tool 结果，但工具本身不扫描 Conversation，也不复制 AttachmentResolver 规则。

这保证了：**Tool 需要的是资源访问能力，不是读取 Agent 全部会话状态的权限。**

### 4.2 设置与旧 OCR cutover

当前设置只保留：

```text
attachmentInspectionModelId: Uuid? = null
DataStore key: attachment_inspection_model
```

`null` 即未配置，不再用随机 UUID 表示空状态，也没有单独 Enable 开关或用户可配置识别 Prompt。

旧 `ocr_model` / `ocr_prompt` 只存在于一次性迁移边界：新 key 优先；有效旧视觉模型可映射到新字段；旧 Prompt 丢弃；旧 key 清除；旧 observation cache best-effort 清理。运行时不再保留 OCR、自动 visual observation、`DERIVED` 或 observation cache 语义。

## 5. Master、Target 与媒体 Tool 共用同一事实模型

### 5.1 Master

Master Conversation 保存原始附件事实；每次 Provider 请求重新投影。`ToolArtifactReplayTransformer` 先恢复历史 Tool artifact 的当前路径 / Image URL，再由附件投影决定当前模型拿到原图还是引用。

### 5.2 `generate_image`

`generate_image` 成功的判定与 Caller 是否能读取图片无关。成功路径先形成可持久化 artifact，再返回：

```text
Tool metadata artifact
+ bounded Text Tool Result
+ UIMessagePart.Image
+ stable attachment_ref
```

下一模型 step 或未来 Turn 只是重新投影这份事实，不自动执行识别。

### 5.3 Target / Child

Target 是独立 run，使用自己的 resolved model；入站附件只验证 ref、资产和安全性，不做“Target 是否能看图”的 preflight。Child Conversation 保存原始 Image，Target 请求再使用统一附件投影。

Target run 内附件识别模型选择按 run 开始时的设置冻结，避免 `inspect_attachments` 在一个工具循环中因用户修改设置而无意义地出现 / 消失；Target 删除、工具撤权等运行安全条件仍按现有子助手策略在 step 边界重验。

Child 交付物的 stable ref 表达“产物存在”。Caller 是否点名产物内容、当前是否原生接收 Image、是否需要显式 inspection 是三个独立问题。Caller 看不懂图不会推翻已经完成的 Child run，也不存在内部 `artifact_delivery` 三态。

具体 `assistant_call` 入站 / 出站协议、extras、artifact metadata 与 UI 见 [sub-assistant-multimodal.md](sub-assistant-multimodal.md)。

## 6. Turn 与 Tool 执行持久化

附件事实必须建立在可靠的 Turn 执行事实之上；否则中止、崩溃或工具副作用发生后，会话历史会出现“UI 看见但数据库没有”或“工具是否执行过无法判断”的状态。

### 6.1 两类执行记录

`TurnExecutionEntity` 当前状态：

```text
CREATED
RUNNING
AWAITING_APPROVAL
COMPLETED
CANCELLED
FAILED
INCOMPLETE
INTERRUPTED
```

`ToolExecutionEntity` 当前状态：

```text
STARTED
COMPLETED
FAILED
CANCELLED
UNKNOWN
```

它们是运行事实，不替代 `UIMessage` / `UIMessagePart.Tool` 的用户可见历史。

### 6.2 checkpoint 是 awaited durability boundary

`GenerationHandler` 发出带当前消息快照的 checkpoint；生产调用方必须等持久化成功后再越过关键边界。

`ConversationRepository.checkpointTurn()` / `finalizeTurn()` 在同一个 Room transaction 中提交：

```text
当前 MessageNode 快照
+ TurnExecution
+ 对应 ToolExecution（如有）
```

生成 checkpoint 对 Conversation header 使用窄更新，避免生成过程中持有的旧快照覆盖 title、folder、pin、suggestions 等其他职责拥有的字段。

### 6.3 Tool STARTED 必须先于实际副作用落盘

工具真正执行前先持久化 `STARTED`。同一 Assistant message 的多个 ToolCall 在审批屏障结束后按 Tool ordinal 串行处理；前一个 Tool Result 完成并 checkpoint 后才执行下一个。

内部精确定位使用：

```text
messageId + toolOrdinal
```

Provider `toolCallId` 只服务线协议，不能作为本地更新的唯一主键。

这也保证 `inspect_attachments` 等后续工具通过 Runtime resolver 能看到本 run 已经完成并持久化到当前消息状态中的 Tool artifact，而不需要获得完整会话 API。

### 6.4 所有退出路径都要有明确终态

Master Turn 的正常完成、待审批、用户停止、被新 Turn 替换、Provider failure / incomplete、运行时异常和 step / interaction limit 都进入统一终态收口。失败或取消时已经生成的可见文本可以保留，但不能把未完成协议片段伪装成成功历史。

`AWAITING_APPROVAL` 是明确可恢复状态；不是普通成功，也不能启动正常 completed 副作用。

## 7. 重启恢复与 replay-safe 历史

### 7.1 进程重启不自动重放未知副作用

应用启动恢复遗留执行记录时：

```text
Turn CREATED / RUNNING -> INTERRUPTED
其下仍为 STARTED 的 Tool -> UNKNOWN
```

原因记录为恢复原因（当前默认 `process_restarted`）。`UNKNOWN` 的含义是副作用是否已经发生无法可靠证明，因此禁止把它当成“肯定没执行”后自动重试。

### 7.2 下一次 Provider 请求只使用协议安全投影

非成功 Assistant 历史在再次发给 Provider 前经过 `replaySafeProjection()`：移除或修复未完成 reasoning、开放 / 不配对 Tool 状态、Provider opaque metadata、残缺媒体等不能安全回放的部分，同时 UI 仍保留原来的部分输出和终态信息。

附件投影发生在新的请求副本上，因此同一 durable Image 可以在模型切换后自然得到：

```text
视觉模型     -> ref + Image
文本模型     -> ref only + capability hint
未来再切回视觉 -> ref + 原始 Image
```

历史事实没有因为模型能力变化而被重写。

## 8. 维护不变量

修改附件、Tool loop 或 Turn 持久化时，至少保持以下不变量：

1. **图片存在不会自动触发第二模型调用。** 只有显式 `inspect_attachments` ToolCall 才识别。
2. **请求投影不写回 Conversation。** capability hint、引用行和 Provider placement 都是 request-time view。
3. **stable ref 不随模型切换变化。** `attachment:<uuid>` 与 `/upload/...` 职责分离。
4. **模型能力不足不是附件损坏。** 真正的附件失败只来自 ref / 文件 / 安全 / MIME / IO 等事实问题。
5. **生成工具成功与 Caller 可读性分离。** `generate_image` / Child 已成功不能因 Caller 不看图而改成失败。
6. **Tool 不扫描整个会话。** 附件访问经 `ToolExecutionContext.resolveAttachments` 与统一 Resolver。
7. **Tool 外部副作用前先持久化 STARTED。** 不确定副作用在恢复后标为 UNKNOWN，不自动重放。
8. **一个 Tool 结果持久化后才允许同批下一个 Tool 执行。** 执行时序与 resolver 可见事实一致。
9. **文件缺失不改写历史 Tool 成败。** 当前可用性与历史执行事实分开表达。
10. **旧 OCR 只允许存在于迁移、迁移测试或历史记录中。** 运行时不得重新引入自动 observation、DERIVED、识别 cache 或 `artifact_delivery` 变体。

当前关键实现集中在：

```text
ai/core/Tool.kt
app/data/ai/GenerationHandler.kt
app/data/ai/attachments/AttachmentRefs.kt
app/data/ai/attachments/AttachmentResolver.kt
app/data/ai/transformers/AttachmentProjectionTransformer.kt
app/data/ai/tools/AttachmentInspectionTool.kt
app/data/ai/tools/GenerationToolSetFactory.kt
app/service/ChatService.kt
app/service/SubAssistantCoordinator.kt
app/data/repository/ConversationRepository.kt
app/data/db/entity/TurnExecutionEntity.kt
app/data/db/entity/ToolExecutionEntity.kt
```

实现细节发生变化时，应更新对应职责文档，而不是重新建立平行的附件或 Turn 设计文档。
