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

Caller 调用 Target 后，当前 Tool Loop 会等待 Target 返回终态。Target 不读取 Master 历史，只收到 `request`；因此 Caller 必须在请求中提供完成任务所需的事实、约束和交付要求。Target 可以连续使用自身 Child 历史，但不能再次调用或管理其他 Assistant。

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

关闭 `allowAsSubAssistant` 时，`AssistantDetailVM` 通过 `SettingsStore.updateAtomic` 同时关闭全局可见，并从所有 Assistant 的允许列表移除该 ID。配置写入成功后才发布新的 `settingsFlow`，避免内存状态领先于持久化状态。

### Catalog

`AssistantCatalogBuilder` 从当前 Settings 和 `SubAssistantAccessPolicy` 动态构建 Catalog。Catalog 使用带 `header` 与 `rows` 的紧凑 JSON，并置于 `<sub_assistant_catalog>` 边界中；执行期仍重新读取 Settings，不把提示词中的 Catalog 当作授权凭据。

`AssistantManagement` 与 `AssistantDelegation` 是独立 Local Tool 权限。前者注册 `assistant_manage`、`assistant_memory_list`，后者注册 `assistant_call`。工具创建的新 Target 会原子加入 Caller 的 `allowedSubAssistantIds`。

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
| `SubAssistantCoordinator` | preflight、lineage、lease、Child 生命周期、Target 生成、交互桥接、终态收口 |
| `SubAssistantAccessPolicy` | 统一计算发现、管理和调用的有效访问范围 |
| `SubAssistantRunPolicy` | 模型解析、运行中停止条件和 Target 工具边界 |
| `SubAssistantLineageResolver` | 在 Master 当前分支上决定新建、复用或克隆 Child |
| `SubAssistantRunStateReducer` | 串行维护单次 Run 的完整 metadata 快照和单向状态转换 |
| `ConversationSessionRegistry` | 为 Master 与 Child 提供同一套 Session、Job 和状态流生命周期 |
| `GenerationHandler` | 通用模型循环、Tool locator、审批策略、phase/checkpoint/finished 事件 |
| `GenerationToolSetFactory` | 按 Assistant、资源和 Run Mode 统一装配工具 |
| `SubAssistantRecovery` | 启动恢复、链接校验、未完成 Run 收口和孤儿清理 |

## 4. 持久化模型

### Child Conversation

Room v4 为 Conversation 增加 `parent_conversation_id` 及关联索引。`Migration_3_4` 是 additive migration，旧会话迁移后保持顶层会话语义。普通会话列表、搜索、最近会话和选择器只暴露顶层会话；Child 通过专用查询和只读详情入口访问。

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
| `has_non_text_output` | 最终结果含可见非文本 part |
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
  -> 新建 / 复用 / 克隆 Child，并追加 USER request
  -> 回写 Child link 与 checkpoint
  -> Target GenerationHandler 循环
  -> 持久化 Child、更新 phase/preview、桥接 ask_user
  -> 提取 final result，写入终态 metadata 与 Tool Result
  -> 释放 lease，Master 继续 Tool Loop
```

### Preflight

调用开始依次验证 Caller 的委托权限、Target 存在且不是 Caller、Target 可作为子助手、访问公式成立、模型来源可解析、同一 lineage 没有活跃 Run。失败在创建 Child 前返回稳定 reason。

Lineage 决策完成后先获取 `(masterConversationId, targetAssistantId)` lease，再从最新 Settings 重验身份、访问与模型可用性。这样同一 Master/Target 串行执行，而不同 Master 可以独立运行；并发竞态不会创建重复 Child。

### Lineage

`findPreviousCallMetadata` 只查看 Master 当前选中分支，并从当前 `messageId + toolOrdinal` 向前寻找同一 Target 最近的终态调用：

- 没有有效前序调用时新建 Child。
- 前序 Run 仍位于 Child 尾部时复用 Child，并追加新的 USER request。
- Child 在前序 Run 后已有其他 USER task 时，只克隆截至前序 Run 的选中历史前缀，再追加 request。
- metadata、父子关系或 task locator 损坏时创建新 Child，不猜测错误 lineage。

### Target 生成

Target 复用通用 `GenerationHandler`，不是独立的简化模型循环。它应用 Target 的 System Prompt、记忆、输入/输出 Transformer、模式注入、上下文裁剪、Provider 协议和 checkpoint 机制。Child 不继承 Master 的会话级 System Prompt、模式选择或聊天历史。

`GenerationHandler` 通过 `GenerationChunk.Messages`、`Phase`、`Checkpoint` 与 `Finished` 向 Coordinator 报告可持久化状态。工具执行使用 `ToolExecutionContext(messageId, toolOrdinal)`；Provider 的 `toolCallId` 只作为协议数据保留，不能作为本地唯一键。

### 结果提取

完成态优先取最终 ASSISTANT step 中最后一个工作工具之后的顶层 Text。`text_to_speech` 等副作用工具不切断答案；最终 step 没有可见文本时向更早 step 回退。最终 step 若含 Image、Document、Audio 或 Video，则设置 `has_non_text_output`。

只有 `completed` 返回 `assistant_name` 和 `content`。其他终态只返回状态与稳定 reason，避免让 Caller 把半成品当作成功结果。

## 6. Target 工具与运行中撤权

Target 每个模型 step 都重新构建工具集。有效工具能力是“调用开始时的 Target 快照”与“当前持久化 Target 配置”的交集：Web Search、Recent Chats、Local Tools、MCP、Workspace 和 Skills 可以在下一 step 被撤销，但运行中新增配置不会给当前 Run 增权。

以下边界始终成立：

- `AssistantManagement`、`AssistantDelegation` 以及注册名 `assistant_manage`、`assistant_memory_list`、`assistant_call` 永久从 Target Run 过滤。
- 除 `ask_user` 外，所有需审批工具在非交互 Target 模式返回 `tool_not_permitted`。
- `ask_user` 由 Coordinator 按 Child `messageId + toolOrdinal` 持久化到 Master 卡片；回答也用 `run_id + interaction_id` 精确匹配，防止重复或过期提交。
- Memory Tool 在每次执行前重验 Target 仍启用记忆且 local/global namespace 没有改变；撤销后返回 `tool_not_permitted`。
- Settings watcher 持续检查 Target 删除/停用、Caller 访问撤销和 RunSpec 模型失效，并取消当前 Run。

工具列表按 step 形成快照。同一批 ToolCall 先完成 Pending 判定，再按原 `toolOrdinal` 串行执行；不会在同批尚有待确认调用时抢先执行自动工具。

## 7. 状态、预览与只读详情

调用状态为 `starting`、`running`、`completed`、`failed`、`stopped` 或 `unavailable`。运行阶段用稳定枚举表示准备、等待模型、推理流、回答流、工具执行、step 间隙和等待用户，不持久化百分比、ETA、推理文本或工具 JSON 作为“进度”。

实时预览只投影本次 Child task 范围内 ASSISTANT 的顶层 Text，排除 Reasoning、Tool input/output、preset 和下一次 USER task。Reducer 保持消息与 part 的显示顺序，只保留有界尾部，并在 Unicode grapheme 边界裁剪。完成态改用 final answer 的有界开头摘要；纯非文本完成态显示本地化提示。

`SubAssistantCallCard` 从通用 COT 分组中独立渲染 Target、request、状态、preview 和 `ask_user`。整卡在 Child link 有效时进入 `SubAssistantDetail`。详情解析会同时校验 Master、run 唯一性、Target、父子关系和 task `UIMessage.id`，并通过 `ChatInteractionPolicy.ReadOnlyChild` 禁止输入、编辑、删除、重生成、分支、收藏、分享与审批。

## 8. 恢复、分支与删除

### 启动恢复

应用启动后扫描顶层会话的所有消息 variants：

- `starting`/`running` 调用不会自动重放，而是按当前配置收口为 `stopped`。
- 有效 Child 的可见预览会重建并保留。
- 缺失或歧义的 run/link 不被信任，也不能保留孤儿 Child。
- `target_removed`、`target_disabled`、`target_access_revoked`、模型不可用、`child_missing` 和 `app_restarted` 按确定优先级选择。

### Master 分支变化与复制

Master 分支切换或历史裁剪后，`planSubAssistantRetention` 只保留仍被任一有效 metadata 引用的 Child，并把共享 Child 收缩到最长仍被引用的 lineage 前缀。

Fork 顶层会话时，`forkSubAssistantTree` 同时复制有效 Child，重建 `MessageNode.id`、`UIMessage.id`、`run_id`、`previous_run_id` 和 Child link。新 Child 改绑新 Master，Provider metadata 与选中消息内容保持不变。

### 删除

删除 Target 先通过原子 Settings 更新移除 Assistant、清理反向授权并写入 `pendingAssistantDeletions`。随后停止活跃 Run，并在 Room 事务中删除相关 Master/Child/MessageNode/收藏数据；成功后消费 tombstone。应用重启会继续未完成清理，同 ID Assistant 已恢复时丢弃旧 tombstone。

删除 Master 会级联处理 Child 与只被该会话树引用的本地文件。普通用户入口始终过滤 Child，避免内部工作会话泄漏到历史、搜索或最近会话工具。

## 9. Session、取消与 TTS

`ConversationSessionRegistry` 保证一个 Conversation ID 对应一个 Session。加载持久化 Child 使用 `open(conversation)`，不会先创建空 Session 遮蔽 Room 快照。页面引用归零但 Job 活跃时 Session 继续保留；生成结束且空闲后再清理。

停止 Master、删除 Target、撤销访问、模型失效、回答等待中断或应用恢复都会取消 Target Job，并由 Coordinator 在 `NonCancellable` 收尾区写入 Child checkpoint、Master 终态和 Tool Result。lease 与交互等待器在 `finally` 中释放。

每个 Master turn 创建共享的 `TtsToolPlaybackContext`。Master 和该 turn 内的 Target 派生来源共享 session 与顺序播放状态，Target 只替换 Assistant 身份和 `SUB_ASSISTANT` 来源类型。控制条仅在当前来源是 Target 且该 Assistant 开启 `useAssistantAvatar` 时显示 Target 头像；播放结束、Provider 切换、错误或 dispose 会清空来源。`assistant_call` 结束不会中断已提交的音频。

## 10. 维护约束

- 修改访问规则时，必须同步检查 UI 候选、Catalog、三个 Assistant 工具、preflight、运行中 watcher、恢复和测试。
- 修改 metadata 时，必须保持 merge 语义、向后兼容默认值，以及 fork/recovery/detail resolver 的一致性。
- 修改工具执行定位时，只能使用 `messageId + toolOrdinal`；不能退回 Provider `toolCallId`。
- 修改 Child 持久化时，必须覆盖 Room migration、顶层查询过滤、事务删除、文件保留和分支复制。
- 修改 Target 生成时，应优先复用通用 Generation Pipeline；任何差异都要作为明确的 Run Mode policy 表达。
- 修改用户可见文案时，必须同步所有支持的 locale。

主要回归测试集中在 `data/ai/subassistant`、`service/SubAssistant*Test`、`ui/pages/subassistant`，以及 Room migration、DAO 与 ConversationRepository 的 instrumentation tests。
