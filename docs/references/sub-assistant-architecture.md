# 子助手架构与执行流程参考

本文描述同步委托的访问控制、Child lineage、父子关联、交互桥接与生命周期。通用执行协议见 [turn-step-execution.md](turn-step-execution.md)，模型可见参数与 Tool Result 形状见 [prompts-and-tools.md](prompts-and-tools.md)；附件输入、结果交付及其文件引用也在本文维护。

## 语义与边界

子助手采用单层、同步委托模型：

- **Caller**：当前主会话所属的 Assistant。
- **Master Conversation**：用户可见的顶层会话，`parentConversationId == null`。
- **Target**：被 `assistant_call` 调用的 Assistant。
- **Child Conversation**：Target 的持久化工作会话，`parentConversationId` 指向 Master。
- **Run**：一次 `assistant_call` 执行，由全局唯一 `run_id` 标识。

Caller 调用 Target 后，当前 Tool Loop 会等待 Target 返回终态。Target 不读取 Master 历史，只收到 `request` 以及可选的
`attachments` 文件路径；Caller 必须提供任务所需事实、约束和交付要求。Target 可以继续自身 Child 历史，但不能再次委托。
附件通过受保护读取进入 Child USER，默认只向 Caller 返回交付清单；模型是否接收图片内容由显式 `extras` 和请求容器能力决定，详见本文“附件输入与交付”。

当前实现不包含异步 mailbox、后台结果回投、多层递归委托或并行 fan-out。这些能力不能通过提示词假装存在。

## 配置、发现与访问控制

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

关闭 `allowAsSubAssistant` 时，`AssistantDetailVM` 将页面字段差异合并到最新用户定义，同时关闭全局可见并从其他用户助手的允许列表移除该 ID。更新沿 `ArtifactUseCase.updateSettingsReferences` 交给 Artifact/Settings 既有提交链，落盘成功后才发布；不从页面旧快照覆盖整份配置。

### 披露

可见目录由 `ConversationDisclosureSnapshotService` 在每个新请求边界披露，详细配置由 `assistant_inspect` 按需读取。
目录与已确认工具效果的对账见[请求上下文](request-context.md)。执行仍按原 Session 的有效配置与
`SubAssistantAccessPolicy` 重验权限，目录快照不能作为授权。

`AssistantManagement` 与 `AssistantDelegation` 是独立 Local Tool 权限。前者注册 `assistant_manage`、`assistant_inspect`，后者注册 `assistant_call`。工具创建的新 Target 只保存一份用户定义：个人域原子加入 Caller 定义的 allowedSubAssistantIds，企业域原子加入原主体的 additionalSubAssistantIds；两者均与新定义共用同一次 Settings 文档提交。管理服务持原 Session 授权，叠加 allowLocalAssistants、调用者管理权限和目标主从授权；Factory 不再用个人 Settings 重复准入。企业定义禁止 UPDATE/DELETE，用户定义修改有共享影响。删除清理各域 usage/附加引用，既有个人数据 tombstone 清理在授权锁外进行，不删除企业历史。

### 模型解析

`resolveSubAssistantRunSpec` 在调用开始时生成只存在于内存的稳定 RunSpec：

- Target 显式绑定模型时，严格使用 Target 模型及其执行参数；模型无效返回 `target_model_unavailable`。
- Target 未绑定模型时，继承 Caller 当前有效模型及模型执行参数，但不回写 Target；Caller 无有效模型返回 `caller_model_unavailable`。
- 借用模型后重新按原始模型和 Target 的 `builtInSearch` 解析 Search，不继承 Caller 已覆盖的工具开关；未设置时继承模型定义。
- Target 的身份、System Prompt、工具、记忆、正则和权限始终保持独立。
- 活跃调用的 RunSpec 基于原 RealmAccess 的 ResolvedConfiguration 解析，个人资源也受本企业五项策略控制。模型捕获前再次核验同一 RunSpec，随后上下文只使用 captured 配置；每次模型请求在同一授权临界区复验 Caller、Target、引用关系和原模型，撤权沿现有取消/STOPPED 协议提交终态，不依赖异步配置 watcher 抢先运行。

## 编排与持久化职责

| 组件 | 子助手专属职责 |
| --- | --- |
| `AssistantManagementService` / `AssistantToolFactory` | 定义管理、授权更新与工具入口 |
| `SubAssistantRunCoordinator` / `SubAssistantRunGate` | preflight、Child 创建、运行和终态编排；每个 Master/Target 的运行租约与用户交互 |
| `SubAssistantAccessPolicy` / `SubAssistantRunPolicy` | 访问范围、模型来源、执行工具边界与撤权条件 |
| `resolveLineage` / `SubAssistantLifecycle` | 分支关联、Child 保留、裁剪与删除 |
| `SubAssistantResultProjection` / `SubAssistantRunStateReducer` | 结果投影与单向 metadata 状态更新 |
| `SubAssistantDetailReader` | 原父页面授权内的只读详情与附件预览 |

Master 与 Child 共用 `TurnRunner`、`TurnCommitter`、`TurnPipelineFactory`、Runtime 及终态/恢复协议，
其职责和事务边界见[Turn/Step 执行](turn-step-execution.md)。Coordinator 不成为第二个提交或恢复 owner。

## 持久化模型

### Child Conversation

Child 通过 `parent_conversation_id` 自引用外键关联 Master，删除 Master 使用 `ON DELETE CASCADE`。普通会话列表、搜索、最近会话和选择器只暴露顶层会话；Child 使用专用查询和只读详情入口。历史 migration 与索引见 [数据持久化](data-persistence.md)。

Child 的 `assistantId` 固定为 Target，`parentConversationId` 固定为 Master。Child 使用正常的 `MessageNode`/`UIMessage` 结构持久化，因此 Provider 不透明 metadata、工具结果和文件引用都沿用现有消息协议。

### Master Tool metadata

每次调用的状态嵌入对应 `UIMessagePart.Tool.metadata["sub_assistant_call"]`。更新采用 merge，不替换整个 metadata，以保留 Provider 的 `functionCallId`、`thoughtSignature` 等不透明字段。

关键字段包括：

| 字段 | 语义 |
|------|------|
| `schema_version` | 当前 metadata 版本为 2；等待交互使用本地调用身份 |
| `run_id` | 当前调用 ID |
| `previous_run_id` | 当前 Master 分支上同一 Target 的前序调用 |
| `target_assistant_id` / `target_name_snapshot` | Target 身份与显示快照 |
| `child_conversation_id` | 持久化 Child ID |
| `child_task_node_id` | 本次 Child USER `UIMessage.id`；序列化字段名固定；值不是 `MessageNode.id` |
| `state` / `phase` / `active_tool_name` | 状态机与当前阶段 |
| `preview` | 主卡片的有界文本投影 |
| `reason` | 失败、停止或不可用的稳定原因码 |
| `has_non_text_output` | 本次 run 有用户可见非文本交付物（`generate_image` 成功图或最终 ASSISTANT 顶层媒体） |
| `artifacts` / `artifact_omitted` | 轻量交付物引用（最多 4 条）与超出上限的省略数；只存引用，不存像素 |
| `user_interaction` | 等待宿主回答的 `interaction_id`、Child `message_id`、`local_call_id`、工具名与入参；实际执行仍使用完整 `ToolCallLocator` |

`SubAssistantRunStateReducer` 保证终态不可回到运行态，迟到的 phase/preview 不覆盖终态，所有 patch 都从完整快照派生。
metadata 的交付使用共用 `ToolMetadataDelivery`：phase/preview 为流式投影，等待回答先提交 checkpoint，
初始关联和最终结果只合并进当前生成消息，分别随 Child link 与 Tool Result 提交，不提前发布未提交的引用。

## `assistant_call` 执行流程

```text
Master ToolCall
  -> 精确定位 ToolCallLocator(assistantMessageId, stepId, localCallId)
  -> preflight 与 RunSpec
  -> 解析当前分支 lineage
  -> 获取 Master + Target lease
  -> 按原 RealmAccess 读取最新有效配置并做写入前重验
  -> 解析 attachments 为本地资产；能力判定留给 Target 请求级投影
  -> 新建 / 复用 / 克隆 Child，并追加 USER（Text(request) + 原始 Image parts）
  -> 预分配 childTurnId，以 reportChildRun 一次提交 Child link metadata 与执行事实的 childConversationId/childTurnId/subAssistantRunId
  -> 捕获 disclosure/MCP/tools/TurnContext，并以精确 childTurnId 提交 Child StartTurn
  -> Target TurnRunner 循环
  -> 持久化 Child、更新 phase/preview、桥接 ask_user
  -> 提取 final result，写入终态 metadata 与 Tool Result
  -> 释放 lease，Master 继续 Tool Loop
```

### 执行前校验

调用开始依次验证 Caller 的委托权限、Target 存在且不是 Caller、Target 可作为子助手、访问公式成立、模型来源可解析、同一 lineage 没有活跃 Run。失败在创建 Child 前返回稳定 reason。

Lineage 决策完成后先获取 `(masterConversationId, targetAssistantId)` lease，再由 `ConfigurationQueryService.read(realmAccess)` 重验身份、访问与模型可用性。
`AttachmentResolver.withImages` 校验安全 `/upload` 图片路径，并在 ArtifactStore retention 作用域内提交 Child USER；
不要求 Master 当前分支引用，不复制输入文件。路径或文件不可用才阻断创建，Target 是否 native 由请求投影决定。
同一 Master/Target 串行，不同 Master 独立运行；图片读取完成、失败或取消均释放保留，不新增持久化结构。

新 Child 的 Target 预设消息先经 `ArtifactStore.materializeConfigurationMessages` 复制为 Master 所在域的独立附件，不能直接挂接个人配置原路径。副本加入原 `createdArtifacts`，随 Child 创建、父调用链接提交和既有失败补偿交接；重用 Child 不重新导入预设。其引用重写与 Draft 共享现有附件机制，不增加 Child 文件 owner。

Child clone 创建的历史附件由 `SubAssistantRunCoordinator` 持有到关联提交：先原子提交 Master metadata 与执行事实的 Child link，
再发布该批 `OwnedArtifact`。关联失败才补偿未关联 Child；关联成功后的资源发布失败保留 Child 和已有引用。
如果 disclosure/MCP/tool/context 准备在 Child `StartTurn` 前失败，真实 USER 与 link 保留，只提交 Caller 失败 metadata，绝不伪造 Child START；
已 START 的取消/失败则必须携带原 `childTurnId`，`TurnFinalizer` 只终止身份匹配的活动执行，迟到清理不能终结更新的 Child Turn。
finally 同样只以原 `childTurnId + runJob` 请求释放 active request 与 context；同 turn 的 stream 尚未关闭时保留 owner，不以 Job 已取消或显示 phase 代替终态提交。终态失败继续保留事实与原 owner，成功清空 stream 后才可释放。该批资源不交给通用 Tool Result lease 作用域。

### 会话分支关联（Lineage）

`findPreviousCallMetadata` 只查看 Master 当前选中分支，并从当前 `ToolCallLocator(assistantMessageId, stepId, localCallId)` 向前寻找同一 Target 最近的终态调用：

- 没有有效前序调用时新建 Child。
- 前序 Run 仍位于 Child 尾部时复用 Child，并追加新的 USER（Text + 本次 Image parts）。
- Child 在前序 Run 后已有其他选中 USER task 时，只克隆截至前序 Run 的选中历史前缀，再追加同一形状的 USER；分支判断始终以 `MessageNode.currentMessage` 为准。
- metadata、父子关系或 task locator 损坏时创建新 Child，不猜测错误 lineage。

### 被委托助手生成

Target 复用通用 `TurnRunner`，不是独立的简化模型循环。它应用 Target 的 System Prompt、记忆、输入/输出 Transformer、模式注入、上下文裁剪、Provider 协议和 checkpoint 机制。Child 不继承 Master 的会话级 System Prompt、模式选择、聊天历史或 Workspace 工作目录（`workspaceCwd`）；运行时使用 Child 自己的会话目录。

Coordinator 将 Child 的 `TurnRunInputs.onCheckpoint` 与 `onResult` 连接到同一个 `TurnCommitter`，流式与 phase 回调更新主卡片预览。完整 Tool locator、Step 边界和提交协议由 [turn-step-execution.md](turn-step-execution.md) 定义。

### 结果提取

完成态只检查 owning Assistant 的尾部 Step，仅当 outcome 为 `Final` 时提取顶层 Text；没有最终文本时返回空文本，不把过程文本当作最终答案。交付物提取和 `extras` 规则见“附件输入与交付”；Recovery 与 Master 停止只重建文本结果，不增加媒体。

只有 `completed` 返回 `assistant_name` 和 `content`；其他终态返回状态与稳定 reason，不将过程文本作为成功答案。Provider 失败经 `classifyProviderFailure` 统一分类并提供脱敏 detail；模型结果形状、错误码和 `tts_stats` / `tts` / `tool_calls` 的 extras 规则见 [prompts-and-tools.md](prompts-and-tools.md)。

## 附件输入与交付

### 输入校验与文件交接

`assistant_call.attachments` 只接受安全的 `/upload/<file>` 图片路径数组，最多 4 张。缺省、JSON null 和空数组表示无附件；其他值必须全部为非空字符串，trim 后按首次出现顺序去重，再校验数量。不接受 UUID、裸文件名、URL、Android URI、base64 或 Workspace 路径。无效参数在创建 Child 前返回 `status=unavailable`。

`AttachmentResolver.withImages` 通过 `ArtifactStore.withUploadImages` 校验原 Master scope、安全路径、ACTIVE 已发布登记与文件存在，取得 retention pin 后在锁外有界读取，以 `GeneratedMediaStore.MAX_IMAGE_BYTES` 和 `ImageMime` 校验大小及实际图片类型。在 pin 作用域内提交 Child USER，随后由 Child 的持久引用接管；成功、失败或取消均释放 pin，删除与 GC 必须尊重该保护。普通读取不创建 `OwnedArtifact`、临时副本或持久化租约。

新建、复用和克隆 Child 均保存 `Text(preprocessSubAssistantTask(request))` 加原文件 URI 的 Image parts，写入内部 `attachment_ref`；文本预处理只作用于 request。任一图片失败均不注入部分内容、不启动 Target run。

文件校验沿[提示词与工具](prompts-and-tools.md)的附件拒绝协议返回稳定 reason，不以模型缺少视觉能力冒充读取失败。

Target 当次模型不支持图片不构成入站失败。Child USER 与 Caller Tool.output 分别使用 `userImages` 和 `toolOutputImages` 能力投影，不能从 USER 图片能力推断工具结果图片能力。投影不自动 OCR、识图或改写持久 metadata；通用路径、来源容器和回放规则由 [多模态资源](multimodal-context-and-turn-durability.md)维护。

### 交付物提取与内容选择

`extractDeliverableArtifacts` 只检查本次 Child USER 到下一条 USER 之前的输出：成功完成的 `generate_image` 图片，以及最终 ASSISTANT 的顶层媒体。入站图片、Web Search/MCP 中间图片、失败工具、历史 run 和未落地远端图片均不作为交付物。

按消息/part 顺序提取，通过逻辑身份或规范化文件去重，最多持久化 `MAX_ASSISTANT_CALL_ATTACHMENTS` 项，其余计入省略数。`validateDeliverableArtifacts` 在写入 metadata 前复验登记、文件、Image 预览及 canonical URL，一致性失败的项不发布。无法形成 `LocalArtifactRef` 的非文本输出只影响 `has_non_text_output`，不伪造路径。

完成态的清单字段与 `extras` 选择由[提示词与工具](prompts-and-tools.md)统一维护。没有合法工具路径的项不进入模型清单，但不因此改写历史 metadata；Document、Audio、Video 可以列为交付物，不因此获得原生输入或图片识别能力。

`projectArtifactsForCaller` 只追加 Image，能力判断留给统一请求投影。追加内容属于 Caller 的持久工具结果，并非只用于本轮。`extras` 必须在调用时指定；未取回像素的 Caller 可直接用 `artifacts[].path` 调用 `inspect_attachments`，或传给下一次委托，不必重跑产图。识图保留重复路径和输入顺序，委托输入则去重。

`has_non_text_output` 表示有用户可见交付物，不保证模型收到内容；TTS 与工具调用明细分别由各自 extras 控制。Target 使用 `generate_image` 仍需当前授权，设背景的审批不能绕过非交互模式限制。

### 预览、分支复制与引用保全

`ConversationAttachmentPreviewProjector` 通过 `AttachmentReferenceLookup` 和已知附件工具顶层参数生成受授权的预览；UI 不扫描 metadata 或直连文件 Store。预览不是持久文件引用，也不授权后续工具读取。主卡片设背景属于 Master，Child 详情设背景属于 Target；文件失效时不伪造预览路径。

Child 原文件与 Master 交付 metadata 的 `LocalArtifactRef` 均由现有引用计算器保护。Child 删除或裁剪不能删除仍被 Master 引用的交付物。Fork/clone 使用 `AttachmentCloner` 按 source-canonical 映射复制历史资源，重绑定 Image URL、artifact metadata 和模型清单；已知附件工具输入只重绑定本次已复制的路径，不额外复制，不改未知字段、正文或内部 UUID。clone 资源在 Child link 提交前由 Coordinator 持有，关联失败补偿，关联后失败保留已提交的 Child 与引用。

读取与删除/GC、Child 提交和分支复制的竞态需以实际 ArtifactStore/Room 验证；JVM 投影测试不等于真实 Provider 图片输入验收。

## Target 工具与运行中撤权

Target 在 Child START 前从同一份原域有效配置、Target、resolved model 与 MCP capability snapshot 装配工具；同一 Run 复用冻结 `TurnContext`。通用冻结、审批屏障和执行复验见[Turn/Step 执行](turn-step-execution.md)，这里仅列委托带来的限制与桥接。

以下边界始终成立：

- `AssistantManagement`、`AssistantDelegation` 以及注册名 `assistant_manage`、`assistant_inspect`、`assistant_call` 永久从 Target Run 过滤；历史名 `assistant_memory_list` 一并过滤。
- 除 `ask_user` 外，所有需审批工具在非交互 Target 模式自动拒绝，返回
  `tool_not_permitted` + `approval_unavailable`。`approval_unavailable` 表示“需要审批但当前
  运行环境无法提供审批，不要原样重试”，是 ToolCall 级可恢复错误，不会终止整个 Run。
- `ask_user` 由 Coordinator 按 Child `ToolCallLocator(assistantMessageId, stepId, localCallId)` 持久化到 Master 卡片。`SubAssistantRunGate` 登记原 Master 与执行时的 `RealmAccess`，pending deferred 依附原等待 Job；取消立即使其不可回答，finally 按具体登记清理，迟到清理不能移除后续交互。原生回答通过 `ConversationApplicationService.answerSubAssistant`，在原页面 `ConversationCommandTarget` 的选择、Session、根会话与生命周期校验后，原子匹配 Master、原 Session、`run_id` 和 `interaction_id`，只接受一次。退出重登不能用新 Session 回答旧 pending；单纯切域往返后，新页面可以回答仍属于原 Session 的后台运行。
- Target run 暂停时，`TurnPause` 携带 `pendingInteractions: List<PendingToolInteraction>`（每项为
  `ToolCallLocator` + `ToolInteractionState`，按 transcript 调用顺序、非空）。Coordinator 直接消费这份列表定位
  待应答工具，**不扫描消息、不依赖任何已落盘的运行时元数据**。一批存在多个挂起交互时逐个处理，
  决定提交后使用原 `TurnHandle` 和当前 `Step` 继续 Target Runner，不创建第二 Turn，也不重置 Step 上限。
- 暂停时的 owning Assistant 通过 `onAssistantObserved` 交给 committer；durable 状态由 checkpoint 提交。Coordinator 使用 `TurnPause.pendingInteractions` 定位交互，`lastMessages` 只承载继续输入。Child 等待期间，Master 仍为 RUNNING，`assistant_call` execution 为 STARTED；主卡片的等待子阶段由 metadata 表达。
- `ask_user` 只接受满足 Schema 数量和大小上限的完整 JSON 入参；无效或过大的入参会在进入等待态前失败，不会截断后持久化。交互轮次上限与模型 step 上限使用不同终态 reason。
- `recent_chats` 与 `conversation_search` 都限定为 Target 自己的顶层会话，不允许借 Target Run 搜索其他 Assistant 或内部 Child。
- Memory Tool 在每次执行前重验 Target 仍启用记忆且 local/global namespace 没有改变；撤销后返回 `tool_not_permitted`。
- `ConfigurationQueryService.observe(realmAccess.scope)` 观察原域有效配置，重验原访问身份、Target、Caller 关系与 RunSpec 模型；失效即取消 Run。它不能替代每次模型请求的同步准入校验。

## 状态、预览与只读详情

调用状态为 `starting`、`running`、`completed`、`failed`、`stopped` 或 `unavailable`。运行阶段用稳定枚举表示准备、等待模型、推理流、回答流、工具执行、step 间隙和等待用户，不持久化百分比、ETA、推理文本或工具 JSON 作为“进度”。

实时预览只投影本次 Child task 范围内 ASSISTANT 的顶层 Text，排除 Reasoning、Tool input/output、preset 和下一次 USER task。Reducer 保持消息与 part 的显示顺序，只保留有界尾部，并在 Unicode grapheme 边界裁剪。完成态改用 final answer 的有界开头摘要；纯非文本完成态在没有缩略图时显示本地化提示。

`SubAssistantCallCard` 单独显示 Target、request、状态、预览、交付物和 `ask_user`。缩略图来自受授权的 metadata 引用，不为显示卡片加载 Child；背景设置的归属见上文。失败使用本地化 reason 与结果 detail 摘要，政策拒绝使用固定文案，不回显内部检查类型。

有效 Child link 可打开 `SubAssistantDetail`。详情借用父页面 `ConversationViewLease`，切域、关闭父页面或删除关联会话后失效。`ConversationCommandCoordinator.openChildForView` 在会话锁内检查 Child header、加载并取得 Runtime lease；关闭详情只释放 Child，不关闭借来的父 lease。导航不持久化 lease，恢复页面必须从父聊天重新打开，不能沿用 ViewModel 中旧 Ready 状态。

`SubAssistantDetailReader` 同时验证 Master、run 唯一性、Target、父子关系与 task `UIMessage.id`。只有非终态且尚无 Child link 的 run 可以继续 Loading；不存在、歧义、撤权或终态缺少 link 时显示不可用。非预期读取异常保留为 Failed，并提供展开、复制诊断及重试；重试只订阅原父 lease，不取得新授权，也不因读取失败撤销仍有效的父页面。

Child snapshot 与 Artifact 发布/生命周期变化合并为同一 `collectLatest` 投影，取消过时计算，避免 Loading 期间遗漏发布或两路预览互相覆盖。父 metadata 在后台每次更新解析一次，子流复用解析结果；UI 不扫描 payload。

详情使用 `ChatMessage(readOnly = true)`，不提供输入、编辑、删除、重生成、分支、收藏、分享或审批；终态条保留 reason 和摘要。卡片和详情以 typed metadata 与 lineage 为事实源，不以归档正文或 Tool output 推导活动状态。工具归档资格与模型回查统一见[提示词与工具](prompts-and-tools.md)，卡片仍通过 Child 详情供人查看。

## 恢复、分支与删除

### 启动恢复

启动恢复只由 `ApplicationRecoveryCoordinator` 按[应用架构](application-architecture.md)的完整顺序调用；
全局写门禁和通用 Turn 恢复见[Turn/Step 执行](turn-step-execution.md)。子助手的额外约束是：

- `TurnRecovery.recoverInterruptedRuns()` 先取消运行租约和待答交互，再从非终态 execution 定点加载 Master、Child 与已保存关联，不扫描所有会话。
- 先关闭 Child，再在父会话原事务中提交工具 UNKNOWN Result、Step/Turn 终态及 `stopped` metadata。Child 的 execution 独立恢复，不能因父 metadata 损坏而漏掉。
- 有效关联使用 `app_restarted`，缺失或无效关联使用 `child_missing`；已提交终态不重写，也不按当前配置重新裁定历史授权。
- 父子关系由自引用外键保护；缺失执行 owner、非法 transcript 或事务失败保持恢复门禁关闭，不能只修显示状态。

### Master 分支变化与复制

普通树变更前先停止并 join 活跃 turn；`requireClosedRunsBeforeTreeMutation` 只验证 Child turns 已关闭，不成为第二个终态写 owner。

手动摘要由 `commitSummary` 在完整父子锁内执行相同 retention planner，并通过 `ConversationWrite.MutateTree` 将 Master 树、建议和 Child 截断/删除一次提交。Child 删除失败会回滚 Master；不依赖启动扫描或下一次树操作补做摘要清理。其他树操作仍使用其既有 retention 调用。

Master 分支切换或历史裁剪后，`SubAssistantLifecycle` 只保留仍被有效 metadata 引用的 Child，并把共享 Child 收缩到最长仍被引用的 lineage 前缀。未变化 Child 不重写，写入量只与裁剪 delta 相关。

Fork 顶层会话时，`forkSubAssistantTree` 同时复制有效 Child，重建 `MessageNode.id`、`UIMessage.id`、`run_id`、`previous_run_id` 和 Child link。新 Child 改绑新 Master；随后 `AttachmentCloner.cloneParts()` 对本地附件做内容级复制，并同步改写 `assistant_call` 的 artifact manifest、递归 `Tool.output` Image URL，按复制后的 typed metadata 重建本工具结果 JSON 的 `artifacts[].path`。托管 upload 使用新副本的实际路径；附件逻辑 ref 保留，消息、run 与 Child 身份按复制映射重建。除这些文件归属字段外，Provider metadata 与选中消息内容保持不变；失效 artifact 的输出图片与旧路径会被移除或降级为无路径的不可用描述。

### 删除

删除 Target 先通过原子 Settings 更新移除 Assistant、清理反向授权并写入 `pendingAssistantDeletions`。随后停止活跃 Run，删除该 Assistant 自己的顶层会话和本地 Memory；被其他 Master 的历史 run 引用的 Child 保留，以维持已持久化卡片和只读详情的可追溯性。成功后消费 tombstone；应用重启会继续未完成清理，同 ID Assistant 已恢复时丢弃旧 tombstone。

删除 Master 会级联处理 Child 与只被该会话树引用的本地文件。普通用户入口始终过滤 Child，避免内部工作会话泄漏到历史、搜索或最近会话工具。

## Runtime、取消与 TTS

Child 不使用普通新聊天的 Draft，只能经 `loadRuntime()` 安装已读取的 Ready Snapshot，不以默认助手或空树替代丢失的持久会话。活动 Job、未关闭 stream 和尚未释放的执行资源仍由通用 Runtime 生命周期保护。

停止 Master、删除 Target、撤销访问、模型失效、回答等待中断或应用恢复都会取消 Target Job。正常运行中的中断由
`TurnFinalizer` 在 `NonCancellable` 收尾区先提交匹配 `childTurnId` 的 Child 终态，成功后才准备 Master terminal metadata；失败或超时保留尚未关闭的事实，不能先将父调用标为终态。`finalizeSubAssistantRun` 将这类异常作为 `ToolRuntimeInfrastructureException` 传播，`ToolCallRuntime` 不把它降为业务失败 Tool Result。Master 自身失败处理同样验证被引用的 Child exact turn 已终态；不满足则保留事实供启动恢复。Master metadata 随工具结果提交；Master 自身已经取消时，由 owning turn 的 `FinalizeTurn` 提交终态，不向已取消的显示通道发送终态。lease 与交互等待器由 `SubAssistantRunGate.withLease` 释放。

Master 与该 Turn 内的 Target 共享 `TtsToolPlaybackContext.sessionId`；Target 只替换 Assistant 身份和 `SUB_ASSISTANT` 来源。审批继续不更换 session，`assistant_call` 结束不停止已提交音频。队列仲裁、播放回调与释放规则由 [语音架构](speech-architecture.md)维护。
