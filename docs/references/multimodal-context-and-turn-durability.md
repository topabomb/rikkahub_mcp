# 多模态上下文与 Turn 持久化

> 定位：会话中的多媒体附件如何成为持久事实（stable attachment ref + managed file）、如何在每次生成请求中按模型能力投影（`AttachmentProjectionTransformer` / `inspect_attachments`）、以及一轮生成（Turn）如何以执行事实落库并在崩溃后恢复。
>
> 分工：`inspect_attachments` 的模型可见描述与失败 reason 表见 [prompts-and-tools.md](prompts-and-tools.md)；Resolver 的 SSRF / 魔数 / 去重细节与子助手附件链路见 [sub-assistant-multimodal.md](sub-assistant-multimodal.md)；生成主链路与 Transformer 顺序见 [chat-generation-pipeline.md](chat-generation-pipeline.md)；数据层结构见 [../dev/persistent-records-and-sync.md](../dev/persistent-records-and-sync.md)。

## 1. 行为总览

```text
用户上传 / 工具产出媒体
    │  AttachmentRefs.ensureAttachmentRef() 盖章（持久事实，一次性）
    ▼
durable Conversation
    Image part（url + metadata.attachment_ref）+ managed file
    │  每次生成请求
    ▼
AttachmentProjectionTransformer（按本次 resolved model 的 inputModalities）
    ├── 可读图（IMAGE in inputModalities）→ 引用行 + 原图
    └── 不可读图 → 引用行 + capability hint（最后一条消息尾部，一次）
           │  模型需要细节时显式调用
           ▼
    inspect_attachments(refs, request)
        → ToolExecutionContext.resolveAttachments → 识别模型（单次多图调用）→ Text
    ▼
Turn / Tool 执行事实（ConversationRepository.checkpointTurn / finalizeTurn，Room 事务）
    ▼
崩溃恢复（INTERRUPTED / UNKNOWN）与 replay-safe 回放
```

关键性质：投影是**请求级、无状态、非破坏**——durable Conversation 永远保存原图与 ref，Model View 每次按当次模型重算；模型切换（视觉 ↔ 文本）不需要迁移消息，下一次请求自然回放对应形态。

## 2. 附件事实

### 2.1 stable attachment ref

- 格式：`attachment:<uuid>`（`AttachmentRefs` 前缀常量）。
- 存储：多媒体 part 的 `metadata` 中的 `attachment_ref` 键，merge 语义（保留其他 metadata 键）。
- 唯一性：一个 ref 指向一个逻辑附件；不同 part 可指向同一文件（保持各自 ref）。
- 幂等：`ensureAttachmentRef` 对已带**合法可解析** ref 的 part 恒等返回；仅对非多媒体 part 恒等。导入 / 旧数据 / 异常 Provider metadata 中的非法 ref 会被重建为合法 UUID，避免模型拿到永远无法解析的 handle。

### 2.2 盖章位置

媒体进入持久消息的入口都调用 `ensureAttachmentRef`：

| 入口 | 说明 |
|------|------|
| 用户上传 / 编辑消息 | `ChatService` 发送前 |
| `generate_image` 产出 | 工具成功时对 Image part 盖章 |
| MCP 图片内容 | `McpManager` 转本地文件时 |
| 外部 HTTPS 图 | 入站时落地（`wrapLocalImage`），Child 只存 `file://` |
| base64 图片 | `FilesManager.convertBase64ImagePartToLocalFile` 落盘即盖章 |
| `assistant_call` 注入 Child | 复制源 ref（跨会话引用同一 managed file） |
| 历史消息补章 | 会话加载 / 生成前的 backfill |

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

## 3. 请求级投影（`AttachmentProjectionTransformer`）

### 3.1 两态

判定输入：本次请求 resolved model 的 `inputModalities` 是否包含 `Modality.IMAGE`。无 Provider 维度矩阵——声明错误的兼容端点由 Provider / 网关报错收口（`provider_error` / `content_blocked`）。

| 模式 | 行为 |
|------|------|
| 可读图 | Image part 保留，前方插入引用行 |
| 不可读图 | Image 替换为引用行；最后一条消息尾部追加一次 capability hint |

### 3.2 投影规则

引用行格式（A/B/C 三态均保留）：

```text
[Attachment ref=attachment:<uuid> type=image name="screenshot.png"]
```

| 对象 | 可读图模式 | 不可读图模式 |
|------|-----------|-------------|
| Image（带 ref） | 引用行 + 原图 | 替换为引用行 |
| Image（无 ref，legacy） | 原图，不加引用行 | 替换为 `[Image]` 占位 |
| Document / Audio / Video | 引用行 + 原 part（始终保留，不分叉） | 同左 |
| `Tool.output` 内媒体 | 递归同上（否则模型看不到生图结果上的 ref） | 递归同上 |

- capability hint 固定文本：「附件图片本次运行不可直接可见，不要仅凭引用推断视觉细节」。模型可见，不进入持久化，也不在 UI 显示；不写 `use inspect_attachments`（何时调用由工具 description 表达）。
- 引用行含 ref / type / name；不含 mime、host path。显示名优先 `ArtifactEntity.displayName`，否则磁盘文件名。

### 3.3 不变量

| 不变量 | 含义 |
|--------|------|
| 非破坏 | 投影只产生请求副本，durable Conversation 原对象不变（含 `Tool.output`） |
| 无状态 | 无跨请求缓存；同一消息按不同模型投影出不同请求，互不影响 |
| 单次 hint | capability hint 只在最后一条消息出现一次 |
| 事实先行 | ref 在持久化时锚定；引用行只是 ref 的模型可见呈现 |

## 4. `inspect_attachments`（按需识别）

### 4.1 注入条件

工具注入判定 `shouldInjectAttachmentInspection()`（`GenerationToolSetFactory`），全部满足才注入：

1. 本次 resolved model 不接收 IMAGE；
2. `Settings.attachmentInspectionModelId` 能解析到模型；
3. 该模型 Provider 可用；
4. 该模型自身 `inputModalities` 含 IMAGE。

不根据当前消息是否包含图片决定 schema。注入时机遵循「配置变更在下一次构建时生效」：主链路 = 下一轮 turn；Target = 下一次 `assistant_call`；run 内 tool schema 稳定（Target 删除 / 撤权等安全信号除外，仍每 step 走 latest）。

### 4.2 附件解析接口（`ToolExecutionContext.resolveAttachments`）

工具获得的不是会话状态，而是最小只读资源访问能力：

```text
attachment:<uuid>（1..4 个）
→ ToolExecutionContext.resolveAttachments(refs)
→ 统一 AttachmentResolver（Runtime 内部使用执行时刻的 durable 消息快照，含本 run 内已完成的 Tool Result）
→ Image parts
→ 识别模型（单次多图调用，[Image N ref=...] 内部标签 + request + 固定 system instruction）
→ Text Tool Result
```

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
| Master 聊天 | `DocumentAsPromptTransformer` → `AttachmentProjectionTransformer` → Template / Workspace / `ToolArtifactReplayTransformer`（先按 artifact metadata 恢复历史 Tool Result 路径，再投影） |
| `generate_image` 产出 | 成功时 Image part 落入本次 Tool.output 并盖章；下一个 step 的请求由投影管线回放（原图或引用行）。识别这张图 = 把它的 ref 传给 `inspect_attachments` |
| Target（`assistant_call`） | Child 拥有完整 Assistant 级 transformer 链 + 自己的 resolved model；入站只校验 ref / 资产，视觉能力由 Target run 自己的投影与工具集表达 |

## 6. Turn / Tool 执行事实

### 6.1 实体与状态

| 实体 | 状态枚举 | 说明 |
|------|----------|------|
| `TurnExecutionEntity` | `CREATED` / `RUNNING` / `AWAITING_APPROVAL` / `COMPLETED` / `CANCELLED` / `FAILED` / `INCOMPLETE` / `INTERRUPTED` | 一轮用户输入到最终 Assistant 消息 |
| `ToolExecutionEntity` | `STARTED` / `COMPLETED` / `FAILED` / `CANCELLED` / `UNKNOWN` | Turn 内单次工具调用 |

Schema 见 [../dev/persistent-records-and-sync.md](../dev/persistent-records-and-sync.md)（DB v5 起）。

### 6.2 checkpoint 与 finalize

- `ConversationRepository.checkpointTurn()`：工具循环内以 Room 事务提交当前会话消息与执行状态（消息快照 + 状态 upsert，FTS 可选重索引）。
- `finalizeTurn()`：run 终止后一次性提交终态（reindexFts = true）。
- 工具执行期间崩溃：从最近 checkpoint 恢复，丢失窗口 = 当前工具 step。

### 6.3 定位与副作用顺序

- `ToolExecutionContext` 以 `messageId + toolOrdinal` 作为工具执行在 Assistant 消息内的唯一 locator（`toolCallId` 只供 Provider 协议使用，重试后会变）。
- 工具产生副作用（文件、数据库、外部调用）前必须先落 `STARTED`——副作用可观测时 DB 中必有记录。
- 同一 Assistant 消息的多个 ToolCall 在审批屏障结束后按 Tool ordinal 串行处理。

### 6.4 终态收口

- 终态只由 `finalizeTurn()` 一次写入；工具循环内只写非终态（`RUNNING` / `AWAITING_APPROVAL`）。
- 恢复、停止、取消走统一终态路径，最终状态由 `TurnExecutionStatus` 决定；等待工具审批的 turn 不触发标题 / 建议等完成副作用。

## 7. 崩溃恢复与回放

### 7.1 重启恢复（`recoverInterruptedExecutions`）

| 重启时状态 | 恢复动作 | 默认失败原因 |
|-----------|----------|--------------|
| Turn 为 `CREATED` / `RUNNING` / `AWAITING_APPROVAL` | 置 `INTERRUPTED`，工具占位按中断渲染 | `process_restarted` |
| Tool 为 `STARTED` | 置 `UNKNOWN`（副作用可能已发生，结果不可判定，禁止标记为成功或失败） | — |

### 7.2 回放安全

- 非成功 Assistant 历史在再次发给 Provider 前经过 `replaySafeProjection()`（`me.rerere.ai.ui` 扩展）：保留有效 Text / Image / 已执行且安全封套的 Tool；剔除 media-failure 文本、`data:` 图（Tool.output 内降级为失败 JSON 文本）、终态下未完成的 Reasoning 与未执行 / 无安全封套的 Tool；对非成功终态追加 `[Previous assistant response did not complete.]` 标记并清空 `terminalStatus`。不修改持久化会话。
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
| `GenerationHandler` | 工具循环、checkpoint、`resolveAttachments` 注入 |
| `ChatService` / `DelegationCoordinator` | 盖章时机、Target run 工具集 |
| `ConversationRepository.checkpointTurn` / `finalizeTurn` | Turn 持久化事务 |
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
