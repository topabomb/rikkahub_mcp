# 多模态上下文与 Turn 持久化

> 定位：会话中的多媒体附件如何成为持久事实（stable attachment ref + Artifact）、如何在每次生成请求中按模型能力投影（`AttachmentProjectionTransformer` / `inspect_attachments`）、以及一轮生成（Turn）如何以执行事实落库并在崩溃后恢复。
>
> 分工：总体 owner 与分层边界见 [application-architecture.md](application-architecture.md)；`inspect_attachments` 的模型可见描述与失败 reason 表见 [prompts-and-tools.md](prompts-and-tools.md)；Resolver 的 SSRF / 魔数 / 去重细节与子助手附件链路见 [sub-assistant-multimodal.md](sub-assistant-multimodal.md)；生成主链路与 Transformer 顺序见 [chat-generation-pipeline.md](chat-generation-pipeline.md)；数据层结构见 [../dev/persistent-records-and-sync.md](../dev/persistent-records-and-sync.md)。

## 1. 行为总览

```text
用户上传 / 工具产出媒体
    │  AttachmentRefs.ensureAttachmentRef() 盖章（持久事实，一次性）
    ▼
durable Conversation
    Image part（url + metadata.attachment_ref）+ Artifact
    │  每次生成请求
    ▼
AttachmentProjectionTransformer（按本次 RequestMediaCapabilities）
    ├── STRUCTURED → input=native 引用事实 + 原图
    └── NONE / OPAQUE_REPLAY_ONLY 的普通 Image part → input=reference_only 引用事实
           │  模型需要细节时显式调用
           ▼
    inspect_attachments(refs, request)
        → ToolExecutionContext.resolveAttachments → 识别模型（单次多图调用）→ Text
    ▼
Turn / Tool 执行事实（CommitCheckpoint / FinalizeTurn 命令 → ConversationRepository.commit(ConversationWrite)，Room 事务）
    ▼
崩溃恢复（INTERRUPTED / UNKNOWN）与 replay-safe 回放
```

关键性质：投影是**请求级、无状态、非破坏**——durable Conversation 永远保存原图与 ref，Model View 每次按当次模型重算；模型切换（视觉 ↔ 文本）不需要迁移消息，下一次请求自然回放对应形态。

## 2. 附件事实

### 2.1 stable attachment ref

- 格式：`attachment:<uuid>`（`AttachmentRefs` 前缀常量）。
- 存储：多媒体 part 的 `metadata` 中的 `attachment_ref` 键，merge 语义（保留其他 metadata 键）。
- 子助手交付物：Master 的 `assistant_call` metadata 以 `SubAssistantCallArtifact(ref, artifact)` 保存同一种稳定
  handle；不复制图片 part 来伪造第二份引用事实。
- 唯一性：一个 ref 指向一个逻辑附件；不同 part 可指向同一文件（保持各自 ref）。
- 幂等：`ensureAttachmentRef` 对已带**合法可解析** ref 的 part 恒等返回；仅对非多媒体 part 恒等。导入 / 旧数据 / 异常 Provider metadata 中的非法 ref 会被重建为合法 UUID，避免模型拿到永远无法解析的 handle。
- `AttachmentReferenceLookup` 只索引多媒体 part 的合法 ref；同一 ref 同时出现在 `Tool.output` 直接媒体与
  `sub_assistant_call.artifacts[]` manifest 是正常双表示，直接媒体优先、manifest 作为回退。等价资源的重复声明复用同一逻辑附件；多个不同 direct 资源或仅 manifest 间的不一致声明 fail-closed，不按遍历顺序选取。

### 2.2 盖章位置

媒体进入持久消息的入口都调用 `ensureAttachmentRef`：

| 入口 | 说明 |
|------|------|
| 用户上传 / 编辑消息 | `ConversationApplicationService` / `MasterTurnCoordinator` 提交前 |
| `generate_image` 产出 | 工具成功时对 Image part 盖章 |
| MCP 图片内容 | `McpToolCallExecutor` 转本地文件时 |
| 外部 HTTPS 图 | 入站时落地（`wrapLocalImage`），Child 只存 `file://` |
| base64 图片 | `Base64ImageToLocalFileTransformer` 经 `ArtifactStore` 终态落盘并盖章 |
| `assistant_call` 注入 Child | 复制源 ref（跨会话引用同一 Artifact） |
| 历史消息补章 | 仅在 `MasterTurnCoordinator` 的 START structural preflight 由 `planDurableAttachmentRefBackfills` / `BackfillAttachmentRefs` 执行；会话加载与恢复只做校验，不由 UI/query 旁路补章 |

所有字节型图片入口都在创建 durable artifact 前限制输入规模并校验实际内容：

- MCP `ImageContent` 先限制 base64 字符与解码字节，再按文件头与容器结构识别 MIME；声明 MIME 不能覆盖实际格式。
- `GeneratedMediaStore` 对 URL/base64 结果使用同一尺寸上限与结构检查，并以检测 MIME 决定扩展名；WebP 校验遍历 RIFF chunk、padding 与 VP8X 后续图像/动画 payload，不把扩展头误当完整图像。Gallery 只通过 `resolveCanonicalFile` 解析根目录内路径。
- 编辑器导入返回 `ArtifactDraftItem(uri, displayName, mimeType)`；路由、分享、粘贴和裁剪调用方直接使用托管时已经确定的 metadata，不对托管 `file://` URI 再走外部 ContentResolver 分类。裁剪输出扩展名与 PNG 压缩格式一致。
- 头像与助手背景经 `ArtifactUseCase.importSettingsImage` 执行有界复制与结构检查，Settings root 提交成功后才发布，失败或取消回滚未发布 artifact。
- 本地 Settings background/头像是可变显示偏好，不是 artifact owner。冷启动发现其 ACTIVE metadata 缺失时，经 `ArtifactReferencePolicy.detach` 持久化回退默认值，绝不扫描或认领遗留文件；metadata 存在而 payload 缺失时，也先持久化回退默认值，再删除该失效 metadata。两种情形均不阻断 Settings 读取；消息附件仍按其独立 durable root 规则 fail-closed。
- 生成中预览与助手背景只接受经结构检查的图片，文件名与扩展名由实际内容生成，不信任模型名、索引或远程声明。

### 2.3 资源的两种身份

每个进入会话的媒体同时拥有两个标识，模型可见标识只此两种：

| 身份 | 标识 | 用途 | 交付方式 |
|------|------|------|----------|
| 引用身份 | `attachment:<uuid>` | 把附件作为输入传给工具（`inspect_attachments`、`assistant_call.attachments`） | 投影出的 `[Attachment ref=...]` 引用行 |
| 文件身份 | `/upload/<file>`、`/workspace/...` | 读写字节（workspace 工具族） | `<UploadFile path=...>`（文档展开）、Tool Result `file.path` |

`/upload` 挂载会话共享的只读文件（用户上传与生成媒体）。内部数据库 id（如已移除的 `media_id`）不进入模型可见 JSON。

### 2.4 文件可用性

- 文件被清理后，历史消息仍保留 Image part 与 ref；投影引用行照常回放，`inspect_attachments` 解析该 ref 时按 `attachment_not_found` 失败，不伪造内容。
- 模型读到 `[Attachment ref=...]` 只代表引用存在，不代表文件可用——需要内容时显式调用工具验证。
- `AttachmentReferenceLookup` 是 message part 与 `assistant_call` 交付物 metadata 的唯一 handle 索引规则；
  `AttachmentResolver` 每批建立一次索引，`ConversationAttachmentPreviewProjector` 每次查询都从 durable nodes
  与 active assistant message 重建索引，并经 `ArtifactStore` 生命周期校验，不缓存 payload/索引。缩略图 Compose
  只做 O(1) map lookup，不扫描消息 metadata；查询投影器通过 `ArtifactStore` 只读文件端口解析 managed fallback，
  因此执行可解析的 ref 与卡片缩略图不会形成两套索引语义。
- `ChatMessage`、会话相册与聊天导出只消费查询投影返回的本地媒体 URL；没有投影 map 时，
  `file:` 图片/媒体保持不可见，不回退为原始路径。助手配置页的合成消息预览同样不读取本地附件。
- Resolver 对 managed manifest 与本地 file URL 都必须先通过 `ArtifactStore` 校验 ACTIVE、version、mime 与受管根目录；仅 payload 文件存在不能使已失效的附件重新可用。

### 2.5 范围清理与恢复

上传 Artifact 与图库生成媒体保持独立 owner，不存在共享目录扫描删除器：

- 上传文件由 `ArtifactStore` 从 durable metadata 选取候选，在同一 lifecycle lock 内重验 retention pin、消息引用和 Settings roots，并复用单项 CREATING / ACTIVE / DELETING 状态机；
- 图库媒体由 `GeneratedMediaStore` 从 canonical row 选取候选，在 persist lock 内复用单项删除协议。row 删除成功而 payload 暂未清除时返回 `cleanupPending`，保留删除 tombstone，由启动 reconcile 继续收口；row 删除失败则恢复原 payload 身份；
- 候选计数只用于确认提示，不锁定待删集合；真正执行时由 owner 在锁内重新取候选。取消在单项之间传播，已经取得删除所有权的单项按既有终态或补偿协议完成；
- 两个领域分别返回结构化结果。`FileManagementApplicationService` 只映射为 UI 所需的 `deleted`、`cleanupPending`、`skippedInProgress` 与 `failed`，不把部分成功压成 Boolean，也不为没有该状态的领域伪造结果；
- `FileManagementApplicationService` 的 owner 命令与 `FileManagementQueryService` 中会读取 row/payload 状态的列表、分页、统计和检查均等待全局 `ApplicationRecoveryGate`。纯 canonical-root 路径分类不读取 row 或 payload 状态，只用于本地图片来源标签。`ApplicationRecoveryCoordinator` 在发布文件读写能力前依次完成 Artifact 与 GeneratedMedia reconcile，页面不会观察或操作尚未收口的 tombstone、staging 或孤儿 payload。

## 3. 请求级投影（`AttachmentProjectionTransformer`）

### 3.1 两态

判定输入：本次请求的 `RequestMediaCapabilities`（显式模型 IMAGE 声明 + 用户选择的 Provider 协议 + 已知容器 profile）。未知 OpenAI-compatible host 按所选通用协议处理，不否决 USER 图片能力。`OPAQUE_REPLAY_ONLY` 只允许 Responses raw `response.output` 回放历史媒体，不能让普通 `UIMessagePart.Image` 借消息级 metadata 变成 native。

| 模式 | 行为 |
|------|------|
| STRUCTURED | Image part 保留，前方插入 `input=native` 引用事实 |
| NONE 或 OPAQUE_REPLAY_ONLY 的直接 Image | Image 替换为 `input=reference_only`（无稳定 ref 时 `unavailable`） |

### 3.2 投影规则

带 stable ref 的图片事实格式：

```text
[Attachment ref=attachment:<uuid> type=image name="screenshot.png" input=native]
[Attachment ref=attachment:<uuid> type=image name="screenshot.png" input=reference_only]
```

| 对象 | 可读图模式 | 不可读图模式 |
|------|-----------|-------------|
| Image（带 ref） | 引用行 + 原图 | 替换为引用行 |
| Image（无 ref，legacy） | 原图，不发明引用 | 替换为 `[Attachment ref=unavailable type=image input=unavailable]` |
| Document / Audio / Video | 引用行 + 原 part（始终保留，不分叉） | 同左 |
| `Tool.output` 内媒体 | 递归同上（否则模型看不到生图结果上的 ref） | 递归同上 |

- 图片事实只描述 `ref`、`type`、`name` 与本次输入形态 `input`，不包含能力判断、行为指令或工具调用建议。`native` 表示原 Image 同时进入该请求，`reference_only` 表示只有引用文本进入，`unavailable` 表示没有可用 stable ref。
- 引用事实不含 mime、host path。显示名优先 `ArtifactEntity.displayName`，否则磁盘文件名。
- 投影文本带 request-only `AttachmentProjectionTextMetadata`，仅供协议适配器识别；不进入持久化，也不在 UI 显示。

### 3.3 不变量

| 不变量 | 含义 |
|--------|------|
| 非破坏 | 投影只产生请求副本，durable Conversation 原对象不变（含 `Tool.output`） |
| 无状态 | 无跨请求缓存；同一消息按不同模型投影出不同请求，互不影响 |
| 来源稳定 | 事实文本只留在原 `UIMessage` 或原 `Tool.output`，不追加到别的消息，不改变 role |
| 事实先行 | ref 在持久化时锚定；引用行只是 ref 的模型可见呈现 |

### 3.4 三种来源与协议归属

| 图片来源 | 通用消息归属 | Provider 线协议归属 |
|----------|--------------|---------------------|
| 用户上传 | 原 USER message 的 parts | Chat/Responses 的 user content、Claude user block、Gemini user part |
| 工具产出 | 原 `UIMessagePart.Tool.output` | Chat `role=tool`、Responses `function_call_output`、Claude `tool_result`、Gemini `functionResponse` |
| 助手原生产出 | 原 ASSISTANT message 的 parts | assistant/model content；Responses 有原始 `response.output` 时先无损回放，再追加 request-only assistant 事实 |

`role` 只存在于消息层；`parts` 内没有第二层 role。投影器因此不创建伪造的 USER 消息，而是在已有来源容器内替换或前置对应 Image 的事实文本。工具结果在 Claude/Gemini 外层虽然使用 user role，但由 typed `tool_result` / `functionResponse` 明确标识，模型不会把它当普通用户输入。

## 4. `inspect_attachments`（按需识别）

### 4.1 注入条件

工具注入判定 `shouldInjectAttachmentInspection()`（`GenerationToolSetFactory`），全部满足才注入：

1. 本次 resolved model 的派生 `RequestMediaCapabilities` 未能覆盖全部三个附件来源容器
   （USER / ASSISTANT / Tool.output）的 `STRUCTURED` IMAGE 能力。
   `OPAQUE_REPLAY_ONLY` 不算完整覆盖——并非所有普通 Assistant 图片都具有可回放的 Provider
   opaque metadata，因此仍需识别工具。
2. `Settings.attachmentInspectionModelId` 能解析到模型，且该模型 Provider 可用；
3. 该模型 `inputModalities` 含 IMAGE。

模型图片能力唯一来源于 `Model.inputModalities`；`RequestMediaCapabilities` 只是协议适配器
对三个来源容器的静态映射，不是第二套能力配置。注入判断在此派生结果上进行，
**不再根据 endpoint host 做第二次否决**——自定义 OpenAI-compatible 端点与官方端点遵循
同一套判断规则。若远端实际不兼容图片，Provider 请求返回的真实分类错误表达，
而非预先伪装为 `inspection_model_unavailable`。

每个 Provider step 开始时只构建一次确定性工具集合；同一集合同时用于该 step 的 schema、审批解析和紧随其后的
ToolCall 执行。Master 与 Target 都从当前有效 Settings 构建，配置变更从下一次 step 构建起生效；Target 额外应用
启动时捕获的实际工具名集合与 Assistant 字段交集，因此只能维持、重建或撤销启动时已有的名字，不能在 run 内新增。审批继续、恢复或历史 ToolCall
若已不在当前集合，统一提交 `FAILED` 执行事实并返回 `tool_not_available`，不恢复已撤销工具。

### 4.2 附件解析接口（`ToolExecutionContext.resolveAttachments`）

工具获得的不是会话状态，而是最小只读资源访问能力：

```text
attachment:<uuid>（1..4 个）
→ ToolExecutionContext.resolveAttachments(refs)
→ AttachmentReferenceLookup（直接 part 或子助手交付物 metadata）
→ 统一 AttachmentResolver（Runtime 内部使用执行时刻的 durable 消息快照，含本 run 内已完成的 Tool Result）
→ Image parts
→ 识别模型（单次多图调用，[Image N ref=...] 内部标签 + request + 固定 system instruction）
→ Text Tool Result
```

- 识别调用由 `AttachmentInspectionTool` 直接持有 Provider 请求边界，不经过 `GenerationLoop`；
  工具构造时（`createAttachmentInspectionTool`）一次性解析并捕获 inspection model、
  provider setting 与派生的 `RequestMediaCapabilities`，写入
  `TextGenerationParams.mediaCapabilities`。执行时不再通过 Settings 重找模型，
  也不再按 endpoint host 二次裁决图片能力；构造时仅断言 IMAGE 模型具有结构化 USER 图片编码映射。若远端实际不兼容，Provider 请求返回的
  真实分类错误（如 `provider_error`）表达，而不是预先伪装为 `inspection_model_unavailable`。
  不得依赖参数默认值或把引用行当作识别输入。
- refs 与产出 1:1、顺序稳定（`resolveImages()` 禁用去重）；同一远程 url 在单次批量解析内只 fetch / 落盘一次。
- 未注入 resolver 的执行环境统一返回 `attachment_resolution_unavailable`，不静默成功。
- 识别无缓存；结果作为显式 Tool Result 已是正确的历史记录。
- 失败 reason 原样透传（表见 [prompts-and-tools.md](prompts-and-tools.md)）。

### 4.3 设置与迁移

- `Settings.attachmentInspectionModelId: Uuid? = null`；DataStore key `attachment_inspection_model`；未配置即关闭工具。设置页选择器只列出声明 IMAGE 输入的 Chat 模型。
- 旧 `ocr_model` / `ocr_prompt` 只存在于一次性迁移边界（`SettingsOcrMigration`）：新 key 优先；有效旧视觉模型（Provider 存在且声明 IMAGE 输入）映射到新字段；旧 Prompt 丢弃；旧 key 清除；旧 observation cache best-effort 清理。备份恢复（S3 / WebDav）在导入 settings.json 前同样应用 `migrateLegacySettingsJson()` 旧键映射，见 [../dev/persistent-records-and-sync.md](../dev/persistent-records-and-sync.md)。

## 5. 投影时序（三条链路）

| 链路 | Transformer 顺序要点 |
|------|---------------------|
| Master 聊天 | `DocumentAsPromptTransformer` → Template → Workspace → 可选 `ToolArtifactReplayTransformer` → `AttachmentProjectionTransformer` |
| `generate_image` 产出 | 成功时 Image part 落入本次 Tool.output 并盖章；下一个 step 的请求由投影管线回放（原图或引用行）。识别这张图 = 把它的 ref 传给 `inspect_attachments` |
| Target（`assistant_call`） | Child 拥有完整 Assistant 级 transformer 链 + 自己的 resolved model；入站只校验 ref / 资产，视觉能力由 Target run 自己的投影与工具集表达；`AttachmentProjectionTransformer` 同样位于动态模板之后、Provider 序列化之前 |

## 6. Turn / Tool 执行事实

### 6.1 实体与状态

| 实体 | 状态枚举 | 说明 |
|------|----------|------|
| `TurnExecutionEntity` | `CREATED` / `RUNNING` / `AWAITING_APPROVAL` / `COMPLETED` / `CANCELLED` / `FAILED` / `INCOMPLETE` / `INTERRUPTED` | 一轮用户输入到最终 Assistant 消息 |
| `ToolExecutionEntity` | `STARTED` / `COMPLETED` / `FAILED` / `CANCELLED` / `UNKNOWN` | Turn 内单次工具调用 |

Schema 见 [../dev/persistent-records-and-sync.md](../dev/persistent-records-and-sync.md)（DB v5 起）。

### 6.2 checkpoint 与 finalize

- `StartTurn` 单事务写 assistant 槽与 RUNNING turn fact，返回唯一 `TurnHandle`。
- `CommitCheckpoint` 命令（`TurnEngine.onCheckpoint` 提交）：工具循环内以 Room 事务提交 changed-node delta、执行事实、artifact reference 与 FTS delta。
- `FinalizeTurn` 命令（`TurnEngine.bind` 在终态提交）：同一事务先收口 STARTED tool fact，再 CAS turn 终态；失败整体回滚。
- 非成功 Master/Child 消息在同一 `FinalizeTurn` 中写入 `terminalStatus`、细分稳定 `terminalReason` 与可空的脱敏
  `terminalDetail`。详情属于消息 JSON，不改变 Room 表结构；它用于进程重启后重新打开诊断，不参与状态机或 Provider 回放。
- 工具执行期间崩溃：从最近 checkpoint 恢复，丢失窗口 = 当前工具 step。
- `ActiveTurnState.toolCallPhases` 只投影当前 turn 的调用装配、审批和执行阶段；`TOOL_EXECUTION_STARTED` 与结果终态必须在对应事实提交成功后推进，结束 turn 时随 active projection 一同释放，不形成第二张 durable 执行表。

### 6.3 定位与副作用顺序

- `ToolExecutionContext` 以 `messageId + toolOrdinal` 作为工具执行在 Assistant 消息内的唯一 locator（`toolCallId` 只供 Provider 协议使用，重试后会变）。
- 工具产生副作用（文件、数据库、外部调用）前必须先落 `STARTED`——副作用可观测时 DB 中必有记录。
- 同一 Assistant 消息的多个 ToolCall 在审批屏障结束后按 Tool ordinal 串行处理。
- Master 的新 turn 启动与审批继续使用不同 typed entry：只有 START 可在 active turn 建立前执行消息树清理和附件引用回填；批准、拒绝与回答由 `applyToolApprovalDecision` 提交决定后只继续原 owner，不执行结构维护。回填计划只从 durable nodes 生成精确 part-path assignment，显示用 `renderNodes` 不参与持久化判断。

### 6.4 终态收口

- 正常终态只由 `FinalizeTurn` command 写入；工具循环内只写非终态（`RUNNING` / `AWAITING_APPROVAL`）。
- stop/supersede 归 `TurnFinalization`，进程恢复归 `TurnRecovery`；两者都经 CommandCoordinator 与同一 CAS 状态机。等待工具审批的 turn 不触发标题 / 建议等完成副作用。

## 7. 崩溃恢复与回放

### 7.1 重启恢复（`TurnRecovery.recoverInterruptedTurns`）

| 重启时状态 | 恢复动作 | 默认失败原因 |
|-----------|----------|--------------|
| Turn 为 `CREATED` / `RUNNING` | 置 `INTERRUPTED`，工具占位按中断渲染 | `process_restarted` |
| 非终态 Turn 的 owning Assistant 消息已不存在 | 恢复进入 `Failed`，不发布不完整会话 | 完整性错误 |
| Tool 为 `STARTED` | 置 `UNKNOWN`（副作用可能已发生，结果不可判定，禁止标记为成功或失败） | — |

non-terminal execution 的 owning message 缺失是持久化完整性错误，恢复进入 `Failed`，不伪造 owner 或改写终态。消息分片缺失、长度不符或 JSON 损坏同样使恢复进入 `Failed`，不得发布不完整会话。

### 7.2 回放安全

非成功 Assistant 历史在再次发给 Provider 前经过 `replaySafeProjection()`（`me.rerere.ai.ui` 扩展）。
terminal messages 按完整 Provider step 原子回放：

1. 连续的 Content（Reasoning/Text/Image）+ 具有合法 call envelope 和可回放 result output 的 Tool 组成完整
   replay 前缀：保留 Text、持久化成功的 Image、Reasoning 和 source metadata，并保留 call/result 配对；
2. pending、未执行、参数损坏或无安全 envelope 的 Tool 仍删除；
3. 最后一个完整 Tool step 之后的尾部：Text/Image 按现有规则保留为辅助上下文，Reasoning 与不透明 provider metadata 删除，
   追加 `[Previous assistant response did not complete.]` 标记，从该尾部开始不计入 `completePartCount`；
4. message-level `providerMetadata` 在 terminal message 上清除；
5. `terminalStatus`、`terminalReason`、`terminalDetail` 继续不进入 Provider wire。

这里的 `UIMessagePart.Tool.hasReplayResult` / `Tool.output` 只表示 Provider 可回放结果，不表示实时执行状态，也不代替 turn checkpoint/phase owner。工具合法返回空 part 列表时，结果 owner 将其规范化为非空结构化 replay envelope，避免把已经产生副作用的工具重新识别为待执行。
如果 parts 出现"unsafe/pending tool 之后又有带结果 Tool"的非连续结构，投影 fail-closed：只保留第一个不安全边界
之前的完整前缀，不能跨越不完整 step 拼接后续内容。

投影返回的 `UIMessage` 携带 request-only `providerReplayProjection: ProviderReplayProjection`（`@Transient`，不持久化）：
- `completePartCount`：具有完整 Provider call/result 回放配对的前缀 part 数量；
- `hasIncompleteTail`：是否存在未完成尾部。

严格协议由 `TerminalAssistantReplay.COMPLETE_STEP_PREFIX` 显式选择，只序列化
`parts.take(completePartCount)`，
不发送 partial assistant tail。`completePartCount == 0` 时该 terminal Assistant 不进入严格历史。
其他协议维持现有 partial text + marker 兼容行为。不修改持久化会话中的部分文本和终态诊断。

- 模型切换后的历史回放由统一投影负责（§3），无迁移逻辑。
- `ToolArtifactReplayTransformer` 按 metadata 恢复历史 Tool Result 的路径与 Image URL（会话 fork / 恢复 / 文件迁移后仍指向有效文件）。

## 8. 组件与职责

| 符号（全局搜索定位） | 职责 |
|----------------------|------|
| `AttachmentRefs` | ref 前缀、metadata 键、merge / ensure / backfill、file URL |
| `AttachmentResolver` | 引用 → 本地 Image 统一解析（安全细节见 sub-assistant-multimodal.md） |
| `AttachmentProjectionTransformer` | 请求级投影（本文件 §3） |
| `AttachmentInspectionTool` / `shouldInjectAttachmentInspection` | `inspect_attachments` 工具与注入判定 |
| `ToolExecutionContext` / `ToolAttachmentResolution` | ai 模块最小只读附件能力接口 |
| `GenerationLoop` | 工具循环、checkpoint、`resolveAttachments` 注入 |
| `ConversationApplicationService` / `MasterTurnCoordinator` / `DelegationCoordinator` | 盖章时机、Master/Target 工具集 |
| `TurnFinalization` | 正常 stop/supersede 与中断结果终态 |
| `TurnRecovery` | 仅重启恢复（Master/Child/tool 定点链路） |
| `ConversationCommandCoordinator` / `ConversationRepository.commit(ConversationWrite)` | durable command 唯一入口与 Room 事务 |
| `TurnExecutionStatus` / `ToolExecutionStatus` | 执行事实状态枚举 |
| `SettingsOcrMigration` / `migrateLegacySettingsJson` | 旧 OCR 设置迁移边界 |

## 9. 易错点

| 易错 | 正确做法 |
|------|----------|
| 在投影或工具里改写 / 删除 durable 消息 | 投影只产生请求副本；ref 在持久化时锚定，工具结果显式写入 |
| 期望 `inspect_attachments` 有缓存或自动触发 | 识别只有模型显式调用一条路径；无缓存 |
| 能力不足当成附件失败 | 入站只校验 ref / 资产；`attachment_not_found` 是解析失败，能力不足由投影与工具集表达 |
| 向 Tool 暴露会话消息或内部 id | 工具只拿 `resolveAttachments` 最小能力；模型可见标识只有 ref 与 `/upload`、`/workspace` 路径 |
| 工具副作用前未写 `STARTED` | 副作用可观测时 DB 必须已有记录，否则恢复后无法判定 |
| 把 `toolCallId` 当持久 locator | 重试后 id 会变；用 `messageId + toolOrdinal` |
| Target run 内期望设置变更立即生效 | 配置变更在下一次构建生效；run 内 schema 冻结，安全信号除外 |
