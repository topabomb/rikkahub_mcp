# 子助手架构与执行流程参考

本文档描述当前已经落地的子助手实现。它是维护架构、排查调用链和评估后续变更的事实基线；模型可见的具体提示词、参数与 Tool Result 形状另见 [prompts-and-tools.md](prompts-and-tools.md)。

---

## 1. 语义与边界

子助手采用单层、同步委托模型：

- **Caller**：当前主会话所属的 Assistant。
- **Master Conversation**：用户可见的顶层会话，`parentConversationId == null`。
- **Target**：被 `assistant_call` 调用的 Assistant。
- **Child Conversation**：Target 的持久化工作会话，`parentConversationId` 指向 Master。
- **Run**：一次 `assistant_call` 执行，由全局唯一 `run_id` 标识。

Caller 调用 Target 后，当前 Tool Loop 会等待 Target 返回终态。Target 不读取 Master 历史，只收到 `request` 以及可选的 `attachments`；因此 Caller 必须在请求中提供完成任务所需的事实、约束、相关附件和交付要求。Target 可以连续使用自身 Child 历史，但不能再次调用或管理其他 Assistant。附件由 Runtime 解析成本地 Image part 写入 Child USER；是否以原图或 visual observation 发给 Target，由本次 RunSpec 模型决定。Target 产出的 Image 交付物由卡片用引用展示，Caller 默认只拿轻量 `artifacts[]`，点名 `extras=["artifacts"]` 后才按 Caller 能力投影内容。多模态协议、设计判定与扩展方向见 [sub-assistant-multimodal.md](sub-assistant-multimodal.md)。

当前实现不包含异步 mailbox、后台结果回投、多层递归委托或并行 fan-out。这些能力不能通过提示词假装存在。

## 2. 配置、发现与访问控制

### Assistant 配置

与子助手有关的字段定义在 `Assistant`：

| 字段 | 语义 |
|------|------|
| `description` | 用于路由和 Catalog 的能力描述，不是 System Prompt |
| `allowAsSubAssistant` | 是否属于可调用的 Target 类别 |
| `isSubAssistantGloballyVisible` | 是否对所有启用 Assistant 工具的 Caller 可见 |
| `allowedSubAssistantIds` | Caller 显式允许访问的 Target ID 集合 |

有效访问公式为：

```text
Target.allowAsSubAssistant
&& Target.id != Caller.id
&& (Target.id in Caller.allowedSubAssistantIds
    || Target.isSubAssistantGloballyVisible)
```

关闭 `allowAsSubAssistant` 时，`AssistantDetailVM` 通过 `SettingsStore.updateLocal` 同时关闭全局可见，并从所有 Assistant 的允许列表移除该 ID。Local shadow 落盘成功后才由 `SettingsStore` 发布新的有效配置快照，避免内存状态领先于持久化状态。

### Catalog

`AssistantCatalogBuilder` 从当前 Settings 和 `SubAssistantAccessPolicy` 动态构建 Catalog。Catalog 使用带 `header` 与 `rows` 的紧凑 JSON，并置于 `<sub_assistant_catalog>` 边界中；执行期仍重新读取 Settings，不把提示词中的 Catalog 当作授权凭据。

`AssistantManagement` 与 `AssistantDelegation` 是独立 Local Tool 权限。前者注册 `assistant_manage`、`assistant_inspect`，后者注册 `assistant_call`。工具创建的新 Target 会原子加入 Caller 的 `allowedSubAssistantIds`。

### 模型解析

`resolveSubAssistantRunSpec` 在调用开始时生成只存在于内存的稳定 RunSpec：

- Target 显式绑定模型时，严格使用 Target 模型及其执行参数；模型无效返回 `target_model_unavailable`。
- Target 未绑定模型时，继承 Caller 当前有效模型及模型执行参数，但不回写 Target；Caller 无有效模型返回 `caller_model_unavailable`。
- Target 的身份、System Prompt、工具、记忆、正则和权限始终保持独立。

## 3. 核心组件与职责

| 组件 | 职责 |
|------|------|
| `AssistantToolFactory` | 注册 Assistant 工具、构建动态 Catalog、把 `assistant_call` 交给 Coordinator |
| `AssistantManagementService` | Assistant CRUD、授权更新、删除 tombstone 与恢复清理 |
| `DelegationCoordinator` | 四阶段编排（preflight → materialize Child → run → terminal）、ask_user 桥接和卡片 Phase；不实现第二套提交或恢复协议 |
| `SubAssistantRunGate` | scoped run lease 与 pending ask_user 并发所有者；原始 release 不向调用方暴露 |
| `SubAssistantLifecycle` | lineage、retention、Child 删除和普通树变更前的 run 收口 |
| `TurnFinalization` | 正常 stop/supersede 与中断结果的强制终态写入 |
| `TurnRecovery` | 只处理进程恢复：按非终态 execution 定点收口 Master、Child 与工具事实 |
| `ApplicationRecoveryCoordinator` | 唯一启动顺序与 fail-closed 写门禁 |
| `SubAssistantResultProjection` | 子助手输出/结果形状/入站投影纯函数（final answer 提取、Tool Result 构建、metadata patch、任务预处理） |
| `TurnEngine` / `TurnPipelineFactory` | Master 与 Target 共用的 turn 骨架（`TurnEngine.start`）与 chunk→CommitCheckpoint→FinalizeTurn 提交协议、输入/输出管道 |
| `SubAssistantAccessPolicy` | 统一计算发现、管理和调用的有效访问范围 |
| `SubAssistantRunPolicy` | 模型解析、运行中停止条件和 Target 工具边界 |
| `SubAssistantLineageResolver` | 在 Master 当前分支上决定新建、复用或克隆 Child |
| `SubAssistantRunStateReducer` | 串行维护单次 Run 的完整 metadata 快照和单向状态转换 |
| `ConversationRuntimeRegistry` | 为 Master 与 Child 提供同一套 Runtime、Job 和状态流生命周期 |
| `GenerationLoop` | 通用模型循环、Tool locator、审批策略、phase/checkpoint/finished 事件 |
| `GenerationToolSetFactory` | 按 Assistant、资源和 Run Mode 统一装配工具 |

## 4. 持久化模型

### Child Conversation

Room v4 为 Conversation 增加 `parent_conversation_id` 及关联索引；v7（`Migration_6_7`）将其升级为自引用外键 `ON DELETE CASCADE` 并启用运行时 FK 约束（`PRAGMA foreign_keys = ON`）——孤儿 Child 自此结构性不可能产生（存量孤儿在迁移中收敛）。`Migration_3_4` 是 additive migration，旧会话迁移后保持顶层会话语义。普通会话列表、搜索、最近会话和选择器只暴露顶层会话；Child 通过专用查询和只读详情入口访问。

Child 的 `assistantId` 固定为 Target，`parentConversationId` 固定为 Master。Child 使用正常的 `MessageNode`/`UIMessage` 结构持久化，因此 Provider 不透明 metadata、工具结果和文件引用都沿用现有消息协议。

### Master Tool metadata

每次调用的状态嵌入对应 `UIMessagePart.Tool.metadata["sub_assistant_call"]`。更新采用 merge，不替换整个 metadata，以保留 Provider 的 `functionCallId`、`thoughtSignature` 等不透明字段。

关键字段包括：

| 字段 | 语义 |
|------|------|
| `run_id` | 当前调用 ID |
| `previous_run_id` | 当前 Master 分支上同一 Target 的前序调用 |
| `target_assistant_id` / `target_name_snapshot` | Target 身份与显示快照 |
| `child_conversation_id` | 持久化 Child ID |
| `child_task_node_id` | 本次 Child USER `UIMessage.id`；序列化名称为兼容既有 schema 保留，并非 `MessageNode.id` |
| `state` / `phase` / `active_tool_name` | 状态机与当前阶段 |
| `preview` | 主卡片的有界文本投影 |
| `reason` | 失败、停止或不可用的稳定原因码 |
| `has_non_text_output` | 本次 run 有用户可见非文本交付物（`generate_image` 成功图或最终 ASSISTANT 顶层媒体） |
| `artifacts` / `artifact_omitted` | 轻量交付物引用（最多 4 条）与超出上限的省略数；只存引用，不存像素 |
| `user_interaction` | 正在等待宿主回答的 `ask_user` locator 与入参 |

`SubAssistantRunStateReducer` 保证终态不可回到运行态，迟到的 phase/preview 不覆盖终态，所有 patch 都从完整快照派生。

## 5. `assistant_call` 执行流程

```text
Master ToolCall
  -> 精确定位 messageId + toolOrdinal
  -> preflight 与 RunSpec
  -> 解析当前分支 lineage
  -> 获取 Master + Target lease
  -> 用最新 Settings 做写入前重验
  -> 解析 attachments 为本地资产；能力判定留给 Target 请求级投影
  -> 新建 / 复用 / 克隆 Child，并追加 USER（Text(request) + 原始 Image parts）
  -> 强制提交 Child、tool STARTED 与 childConversationId 关系
  -> Target GenerationLoop 循环
  -> 持久化 Child、更新 phase/preview、桥接 ask_user
  -> 提取 final result，写入终态 metadata 与 Tool Result
  -> 释放 lease，Master 继续 Tool Loop
```

### Preflight

调用开始依次验证 Caller 的委托权限、Target 存在且不是 Caller、Target 可作为子助手、访问公式成立、模型来源可解析、同一 lineage 没有活跃 Run。失败在创建 Child 前返回稳定 reason。

Lineage 决策完成后先获取 `(masterConversationId, targetAssistantId)` lease，再从最新 Settings 重验身份、访问与模型可用性。写入 Child 之前解析 `attachments` 为本地资产，但不做 Image 能力 preflight：无法解析的 ref 才阻断创建；模型能否 native 由 Target `RequestMediaCapabilities` 在请求投影中决定。这样同一 Master/Target 串行执行，而不同 Master 可以独立运行；并发竞态不会创建重复 Child，也不会留下只有 USER、没有 Run 的脏 Child。

### Lineage

`findPreviousCallMetadata` 只查看 Master 当前选中分支，并从当前 `messageId + toolOrdinal` 向前寻找同一 Target 最近的终态调用：

- 没有有效前序调用时新建 Child。
- 前序 Run 仍位于 Child 尾部时复用 Child，并追加新的 USER（Text + 本次 Image parts）。
- Child 在前序 Run 后已有其他选中 USER task 时，只克隆截至前序 Run 的选中历史前缀，再追加同一形状的 USER；分支判断始终以 `MessageNode.currentMessage` 为准。
- metadata、父子关系或 task locator 损坏时创建新 Child，不猜测错误 lineage。

### Target 生成

Target 复用通用 `GenerationLoop`，不是独立的简化模型循环。它应用 Target 的 System Prompt、记忆、输入/输出 Transformer、模式注入、上下文裁剪、Provider 协议和 checkpoint 机制。Child 不继承 Master 的会话级 System Prompt、模式选择或聊天历史。

`GenerationLoop` 通过 `GenerationChunk.Messages`、`Phase` 与 `Finished` 向 Coordinator 报告流式状态；durability 走 awaited `onCheckpoint`。工具执行使用 `ToolExecutionContext(messageId, toolOrdinal)`；Provider 的 `toolCallId` 只作为协议数据保留，不能作为本地唯一键。

### 结果提取

完成态优先取最终 ASSISTANT step 中最后一个工作工具之后的顶层 Text。`text_to_speech` 等副作用工具不切断答案；最终 step 没有可见文本时向更早 step 回退。`extractDeliverableArtifacts()` 收集本次 run 的明确交付物：成功的 `generate_image` Tool.output Image，以及最终 ASSISTANT 顶层媒体。`has_non_text_output` 由该清单派生。completed 且存在可持久化交付物时，JSON 始终带轻量 `artifacts[]`；`extras=["artifacts"]` 才按 Caller 的 `ImageInputAdapter` 能力把原图或 observation 追加进 Tool.output。

只有 `completed` 返回 `assistant_name` 和 `content`。其他终态只返回状态与稳定 reason，避免让 Caller 把半成品当作成功结果。Provider 与本地异常统一由 `classifyProviderFailure` 分类，reason 与 `ProviderFailureKind.reason` 完全一致：`rate_limited`、`quota_exhausted`、`auth_failed`、`permission_denied`、`invalid_request`、`provider_unavailable`、`provider_error`、`content_blocked` 或 `runtime_error`，并带回同一分类器生成的脱敏 `detail`。`content_blocked` 使用稳定英文说明，不回传检查类型或原始政策字符串；其他详情为有界单行诊断，不含因果链和堆栈。用户卡片和详情使用细分本地化原因加同一条消息摘要，不再把 429、额度、鉴权和 5xx 压成一档 `provider_error`。调用过 `text_to_speech` 时默认带 `tts_stats`（次数与朗读字符合计）；完整 `tool_calls` 计数表、朗读文本表 `tts` 以及交付物内容只在 Caller 通过 `extras` 点名后返回。Recovery 与 Master 停止只重建文本 extras，不加媒体。

## 6. Target 工具与运行中撤权

Target 每个模型 step 都重新构建工具集。有效工具能力是“调用开始时的 Target 快照”与“当前持久化 Target 配置”的交集：Web Search、Recent Chats、Local Tools、MCP、Workspace 和 Skills 可以在下一 step 被撤销，但运行中新增配置不会给当前 Run 增权。

以下边界始终成立：

- `AssistantManagement`、`AssistantDelegation` 以及注册名 `assistant_manage`、`assistant_inspect`、`assistant_call` 永久从 Target Run 过滤；历史名 `assistant_memory_list` 一并过滤。
- 除 `ask_user` 外，所有需审批工具在非交互 Target 模式自动拒绝，返回
  `tool_not_permitted` + `approval_unavailable`。`approval_unavailable` 表示“需要审批但当前
  运行环境无法提供审批，不要原样重试”，是 ToolCall 级可恢复错误，不会终止整个 Run。
- `ask_user` 由 Coordinator 按 Child `messageId + toolOrdinal` 持久化到 Master 卡片；回答也用 `run_id + interaction_id` 精确匹配，防止重复或过期提交。
- `ask_user` 只接受满足 Schema 数量和大小上限的完整 JSON 入参；无效或过大的入参会在进入等待态前失败，不会截断后持久化。交互轮次上限与模型 step 上限使用不同终态 reason。
- `recent_chats` 与 `conversation_search` 都限定为 Target 自己的顶层会话，不允许借 Target Run 搜索其他 Assistant 或内部 Child。
- Memory Tool 在每次执行前重验 Target 仍启用记忆且 local/global namespace 没有改变；撤销后返回 `tool_not_permitted`。
- Settings watcher 持续检查 Target 删除/停用、Caller 访问撤销和 RunSpec 模型失效，并取消当前 Run。

工具列表按 step 形成快照。同一批 ToolCall 先完成 Pending 判定，再按原 `toolOrdinal` 串行执行；不会在同批尚有待确认调用时抢先执行自动工具。

## 7. 状态、预览与只读详情

调用状态为 `starting`、`running`、`completed`、`failed`、`stopped` 或 `unavailable`。运行阶段用稳定枚举表示准备、等待模型、推理流、回答流、工具执行、step 间隙和等待用户，不持久化百分比、ETA、推理文本或工具 JSON 作为“进度”。

实时预览只投影本次 Child task 范围内 ASSISTANT 的顶层 Text，排除 Reasoning、Tool input/output、preset 和下一次 USER task。Reducer 保持消息与 part 的显示顺序，只保留有界尾部，并在 Unicode grapheme 边界裁剪。完成态改用 final answer 的有界开头摘要；纯非文本完成态在没有缩略图时显示本地化提示。

`SubAssistantCallCard` 从通用 COT 分组中独立渲染 Target、request、状态、preview、交付物缩略图和 `ask_user`。缩略图只读 metadata 引用，不加载 Child；点击分区与 `ask_user` 一样不抢走整卡进详情。主卡片把图设为背景改当前 Master Assistant；详情里设为背景仍改 Target。失败或不可用时额外显示本地化 reason 和用户可见摘要：政策拒绝使用固定文案，不回显检查类型；其他失败从 Tool Result `detail` 取第一行并去掉异常类型前缀。整卡在 Child link 有效时进入 `SubAssistantDetail`。详情解析会同时校验 Master、run 唯一性、Target、父子关系和 task `UIMessage.id`；仅非终态且尚未写入 Child link 的 run 可以保持 Loading，不存在、歧义或已经终止但缺少 link 的 run 立即显示不可用。详情页只渲染 `ChatMessage(readOnly = true)`，不提供输入、编辑、删除、重生成、分支、收藏、分享或审批入口；终态条同样展示 reason 与用户摘要。子助手失败不会抬成 Master 整轮 `ErrorCard`。

## 8. 恢复、分支与删除

### 启动恢复

应用启动后，`ApplicationRecoveryCoordinator` 在 artifact/reference/FTS 投影完成后调用
`TurnRecovery.recoverInterruptedRuns()`，以**执行事实为唯一输入**做定点收口：

- 输入 = `turn_execution` 非终态行（`getNonTerminalTurnExecutionsWithScope`，JOIN 会话表区分 Master/Child）；健康会话零加载，不再全库扫描。
- Master 行 → 定点加载该会话，树中 `starting`/`running` 调用不会自动重放，而是按当前配置收口为 `stopped`；有效 Child 的可见预览会重建并保留。
- 被非终态调用引用的 Child → 定点加载收口（finishReasoning + 工具中断 + turn 事实 `INTERRUPTED`）；即使 Master metadata 缺失或损坏，Child execution 仍独立收口。
- STARTED tool fact 先变为 `UNKNOWN`/`CANCELLED`，再提交 owning turn 终态；终态事务失败不会留下“turn 已终态、tool 仍 STARTED”的窗口。
- 恢复先经 `SubAssistantRunGate` 取消全部运行 lease 与 pending ask_user，再读取 Room。
- 孤儿 Child 由 v7 自引用 FK CASCADE 结构性杜绝（存量在 `Migration_6_7` 收敛），启动恢复不再扫描孤儿。
- `target_removed`、`target_disabled`、`target_access_revoked`、模型不可用、`child_missing` 和 `app_restarted` 按确定优先级选择。

`ApplicationRecoveryGate` 在完整恢复和 tombstone 清理结束前阻止所有 durable Conversation/Assistant 写入。任一步失败进入 `Failed(error)`；用户可显式 retry，但不能绕过门禁继续写。

### Master 分支变化与复制

Master 分支切换或历史裁剪后，`SubAssistantLifecycle` 只保留仍被有效 metadata 引用的 Child，并把共享 Child 收缩到最长仍被引用的 lineage 前缀。未变化 Child 不重写，写入量只与裁剪 delta 相关。

Fork 顶层会话时，`forkSubAssistantTree` 同时复制有效 Child，重建 `MessageNode.id`、`UIMessage.id`、`run_id`、`previous_run_id` 和 Child link。新 Child 改绑新 Master；随后 `AttachmentCloner.cloneParts()` 对本地附件做内容级复制，并同步改写 `assistant_call` 的 artifact manifest、递归 `Tool.output` Image URL 及结果 JSON `artifacts[].path`。除这些文件归属字段外，Provider metadata 与选中消息内容保持不变；失效 artifact 的输出图片与旧路径会被移除或降级为无路径的不可用描述。

### 删除

删除 Target 先通过原子 Settings 更新移除 Assistant、清理反向授权并写入 `pendingAssistantDeletions`。随后停止活跃 Run，删除该 Assistant 自己的顶层会话和本地 Memory；被其他 Master 的历史 run 引用的 Child 保留，以维持已持久化卡片和只读详情的可追溯性。成功后消费 tombstone；应用重启会继续未完成清理，同 ID Assistant 已恢复时丢弃旧 tombstone。

删除 Master 会级联处理 Child 与只被该会话树引用的本地文件。普通用户入口始终过滤 Child，避免内部工作会话泄漏到历史、搜索或最近会话工具。

## 9. Runtime、取消与 TTS

`ConversationRuntimeRegistry` 保证一个 Conversation ID 对应一个 Runtime，并显式暴露 `Loading/Draft/Ready/Missing/Failed`；Draft 仅用于尚未发送首条消息的普通新聊天，Child 不使用 Draft。持久化 Child 只能经 `loadRuntime()` 安装已读取的 Ready Snapshot；不存在默认 Assistant 或空树占位。页面引用归零但 Job 活跃时 Runtime 继续保留；生成结束且空闲后再清理。

停止 Master、删除 Target、撤销访问、模型失效、回答等待中断或应用恢复都会取消 Target Job。正常运行中的中断由 `TurnFinalization` 在 `NonCancellable` 收尾区分别尝试 Child 与 Master metadata 终态写入；两侧都执行，任一失败最终仍向调用方传播，并以 suppressed exception 保留双侧诊断。lease 与交互等待器由 `SubAssistantRunGate.withLease` 的结构化作用域释放。

每个 Master turn 创建共享的 `TtsToolPlaybackContext`，其中稳定 `sessionId` 是播放队列的唯一边界。Master 和该 turn 内的 Target 复用此 ID，Target 只替换 Assistant 身份和 `SUB_ASSISTANT` 来源类型；工具审批暂停与恢复继续使用原 ID，新消息或重新生成才轮换。`TtsController` 同时只接受一个 session 独占队列：新 session 替换旧队列；同 session 在顺序开关开启时追加、关闭时替换。Tool 不维护“是否首调”状态，UI `activeSource` 也不参与队列仲裁；每个 chunk 在入队时直接绑定来源，避免跳过或追加导致来源索引错位。控制条仅在当前来源是 Target 且该 Assistant 开启 `useAssistantAvatar` 时显示 Target 头像；播放结束会清空显示来源，但保留该 session 的队列所有权，以便同 turn 的迟到调用继续追加；Provider 切换、stop 或 dispose 才释放所有权。旧 worker 与旧播放器回调不能覆盖或停止新队列。`assistant_call` 结束不会中断已提交的音频。

## 10. 维护约束

- 修改访问规则时，必须同步检查 UI 候选、Catalog、三个 Assistant 工具、preflight、运行中 watcher、恢复和测试。
- 修改 metadata 时，必须保持 merge 语义、向后兼容默认值，以及 fork/recovery/detail resolver 的一致性。
- 修改工具执行定位时，只能使用 `messageId + toolOrdinal`；不能退回 Provider `toolCallId`。
- 修改 Child 持久化时，必须覆盖 Room migration、顶层查询过滤、事务删除、文件保留和分支复制。
- 修改 Target 生成时，应优先复用通用 Generation Pipeline；任何差异都要作为明确的 Run Mode policy 表达。
- 修改用户可见文案时，必须同步所有支持的 locale。
