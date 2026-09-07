# 请求上下文

本文档是请求级上下文策略的当前总览：条数窗口、Tool Result 滚动压缩、Disclosure Snapshot 与
用户触发的语义摘要如何叠在同一次 Provider 请求上。Turn / checkpoint 协议见
[`turn-step-execution.md`](turn-step-execution.md)，模型看见的文案与工具形状见
[`prompts-and-tools.md`](prompts-and-tools.md)，披露表与 Artifact 见
[`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)，
用量与估算口径见 [`token-usage-accounting.md`](token-usage-accounting.md)，条数旋钮见
[`assistant-configuration.md`](assistant-configuration.md)。

Durable Conversation、Conversation Presentation 和 Model Request Plan 是三个概念。请求投影不能
成为第二持久化事实源。`conversation_model_context` 属于 Conversation aggregate，不进入
Presentation / FTS / UI。

## 上下文策略

| 层 | 目的 | 改 durable 会话 | 默认 |
| --- | --- | --- | --- |
| 条数阶梯窗口 `contextMessageLimit` | 控制发送历史长度，减少相邻请求的前缀漂移 | 否 | 关（`0`） |
| Tool Result 滚动压缩 | 缩短已消费的 inline tool 正文 | 只替换该 Tool 的 output 为 marker / archive | 开（预算触发） |
| Disclosure Snapshot | 把 Memory / 子助手 Catalog 从 System 挪到因果 USER 的第一 part | 内容相对 baseline 变化才随 `StartTurn` 追加 `conversation_model_context` | 每个新 START 都捕获；相同则不写 |
| 用户触发语义摘要 | 长期缩小可见历史 | 是，不可撤销 | 仅用户确认 |

会话级自动摘要（conversation-level compaction）未实现，不得与滚动压缩混为一谈。

优先级：先控制每请求发送长度，再稳 prompt cache 前缀。滚动压缩改写已消费的 tool 正文时，由此产生的
前缀失效是预期代价。Agent 自动链路不得为了「影响面小」改写更早前缀。UI 只描述降低失效频率，
实际命中以响应里的 cache read 为准。

### 条数窗口

`Assistant.contextMessageLimit` 默认 `0`（关闭）。启用后由 `effectiveContextMessageLimit()` 归一化到
`40..512`，UI 重新打开开关写入默认 `80`。持久化仍保存原始字段；导入或异常备份不会绕过该归一化。

超限时 `RequestContextPlanner` 内部 `limitContext` 按 50% 保留比例一次前移较大步幅，再
`findUserTurnStart()` 回退到完整 USER 轮次。工具调用与结果在同一个 `UIMessagePart.Tool` 里，因此
窗口边界回退到最近的 USER，不按 `providerCallId` 配对工具。

### 滚动压缩

`ToolOutputCompactionPlanner.planAfterSuccessfulRequest` 只规划本次成功请求确实可见且模型已消费的
历史 inline Tool Result。输入资格来自 `ModelRequestReceipt`；规划阶段没有消息或文件写入。

全部阈值只来自 `ContextBudget`：inline Tool 正文达到 48 × 1024 estimated tokens 才触发，目标低水位
16 × 1024，整批至少净回收 24 × 1024。最近两个 Step 工具批次与最近 8 × 1024 estimated tokens
受保护；单个结果净回收至少 128。不额外保护整个已完成 USER 轮次，也不使用 Provider input/cache 指标决策。

候选须有 COMPLETED / FAILED Result、纯文本 output 与可压缩策略。已归档结果、PRESERVE、混合媒体、
Provider opaque replay、Denied / Answered 和无终态调用不参与。整批回收不足时不改历史。

| `ToolOutputPolicy` | 处理 |
| --- | --- |
| `ARCHIVABLE_TEXT` | 原文通过 Artifact owner 归档，inline 替换为 `[Archived tool result: ref=...]` marker |
| `REGENERABLE_TEXT` | 只替换固定 `[Derived tool result folded]`，不复制 payload；原工具输入保留 |
| `PRESERVE` | 完整保留 |

`ToolOutputStore.stageCompaction` 暂存归档并返回窄替换与 lease。active 与历史 Tool patch 随
`ModelResponseCheckpoint` 提交；无工具 Final Step 则随 `FinalizeTurn` 提交。提交后发布 lease，
失败或提交前取消保留原 inline output 并回滚未发布 Artifact。每个成功非空批次只累计一次裁剪计数。
资源交接见 [Turn / Step 执行](turn-step-execution.md)，Artifact 归属见
[多模态与持久化](multimodal-context-and-turn-durability.md)。

模型通过 `read_tool_output` / `grep_tool_output` 回查；注册名、参数、输出上限及工具策略见
[提示词与工具](prompts-and-tools.md)。回查仍校验当前 conversation 的 TOOL_OUTPUT reference，ref 不是授权。

### Disclosure Snapshot

每个新 `START` 从同一份 Effective Settings 与一次 `ORDER BY id ASC` 的 Memory 查询捕获完整
candidate；与结构变换后目标 selected branch 上最近适用 entry 做逐字比较，不同才随新 Assistant
owner 追加。同一 Turn 的 step、审批、`ask_user`、重试不刷新。后出现的完整 Snapshot 按时间顺序
成为新 baseline，不需要 generation / effect 协议，也不把当前 live 状态写回 Conversation 头部。

regenerate 同一 USER 创建新 Assistant owner，不复制 USER。即将被替换的旧 owner 先退出目标分支
再判等，因此相同 live content 也可能相对更早 baseline 被判定为变化，并由新 owner 重新落一条
entry，不会丢基线。

`planRequest` 只消费 START 时已经适用谓词过滤并冻结的 `TurnModelContextProjection`，窗口内不再
重跑适用性。有 Snapshot 时，窗口内每条消息必须已有 `DurableMessageLocator`；
`applyContextProjections` 只附着 Durable USER，anchor 缺失、重复或变成 synthetic 时请求失败。

### 手动摘要

`ConversationApplicationService.compress()` 经 `GenerationSideEffects.compressConversation()` 生成摘要，
再以 durable tree command 替换历史。它由用户显式触发、持久化且不可撤销，不挂到自动发送链路。
保留最近消息的切点回退到完整 USER 轮次，因此配置数量是最低保留量；可压缩前缀为空则报错。

待摘要消息超过 256 条时递归分块，中点优先回退到 USER；没有可用的前置 USER 切点时按原中点分开。
每条消息通过 `summaryAsText(maxLength = 2000)` 提供正文摘要，Step 标记不参与；这不是附件全文或
工具 output 的无损备份。`targetTokens` 只进入摘要提示，不是本地硬校验。

## 组装顺序

请求前由 `RequestContextPlanner` 纯规划，请求成功后由 `ToolOutputCompactionPlanner` 纯规划压缩。每次真实 Provider 调用：

```text
durable selected branch
  → replay-safe projection
  → limitContext（条数窗口，对齐完整 USER 轮次）
  → Input Transformers（不重读 Settings / 时钟 / Locale / Workspace）
  → applyContextProjections：把选中 Snapshot 作为 durable USER 的第一个 Text part
  → RequestAssembler.assemble：丢弃 Step，产出 ModelRequestMessage 与对应 providerVisibleMessages
  → RequestContextPlanner.receiptOf：从 providerVisibleMessages 生成保守 receipt
  → 估算请求 token 并发送 ModelRequestMessage
  → 成功后再由 ToolOutputCompactionPlanner.planAfterSuccessfulRequest（只认本次 receipt 里仍 inline 的 Tool Result）
```

`limitContext` 是 planner 内部函数，不是 `List<UIMessage>` 公共 API。System / 冻结 Tool prompt 与
Provider 请求共用同一份 `RequestContextPlan`。Snapshot 在 transformers 之后注入，因此不经过
messageTemplate、Placeholder、Time / Workspace Reminder、DocumentAsPrompt 或附件投影。

`StepRunner.generateInternal` 在发请求前对最终投影做 `estimateRequestContextTokens`；压缩水位
用同一条 `estimateStableTextTokens`，但只加本次可见的 inline tool 正文，不看整包请求估算或
Provider `input_tokens`。`ChatSizeChecker` 的预警读取最近一次发送前估算，也不参与压缩决策。

## 策略叠加

- **窗口开**：压缩器只看见后缀里发出去的 tool；窗口外的全文仍在库里，本轮不归档。
- **窗口内压缩**：改请求中间的历史 tool 正文，从被改处打断缓存；更早的 System / USER 仍可能命中。
- **跨台阶**：请求第一条 USER 换人，baseline Snapshot 跟到新的第一条，整段前缀失效一次。
- **同 Turn Memory / 子助手工具**：Snapshot 不变，只追加 Tool Result；新 START 才换 baseline。
- **手动摘要**：改的是消息树，与滚动压缩、条数窗口独立；切点同样对齐 USER。
- **窗口外旧 Snapshot**：不发送。retained 第一条真实 USER 之前（含同位置）最近一条适用 Snapshot
  作为 window baseline，投影到该 USER；窗口内更晚的 Snapshot 保持在各自因果 USER 前。

### 前缀稳定条件

有利于前缀稳定的条件：

- `TemplateTransformer` 使用每条消息的 `createdAt`，不用当前时间重写历史。
- `TimeReminderTransformer` / Placeholder / Workspace reminder 消费 START 时冻结的
  `TurnPromptSnapshot`，审批跨日或改 Locale 不改本 Turn。
- 阶梯内的历史起点稳定；工具结果只改变当前尾部。
- Tool 的 name / description / Schema / 顺序在 START 时冻结为 `FrozenToolDefinition`，不得含日期、
  Memory 或 Catalog。
- System 只保留稳定规则；动态 Memory / Catalog 只出现在 USER 第一 part 的 Snapshot 里。

会合理导致前缀变化的条件：

- Assistant System、会话 System、Mode Injection 或 Workspace 环境变化（下一次新 START）。
- Memory 或子助手 Catalog 的 live 内容变化，且下一 START 追加了新 Snapshot。
- 启用的 Tools、MCP schema、模型或 Provider 变化。
- 到达下一裁剪台阶，或滚动压缩改写了窗口内历史 tool 正文。
- Provider 自身的缓存最小 token 数、TTL 或路由变化。

## Disclosure 所有权与窗口基线

Snapshot entry 由 START 的 Assistant variant 拥有，并锚定它之前最后一条真实 USER。
已提交内容 append-only；新 START 只比较完整 candidate，不因工具修改 Memory / Catalog 而回写旧 entry。
Fork 按 owner / anchor node 映射复制；variant 选择决定适用 baseline。窗口外旧 entry 不直接发送，
但 retained 第一条 USER 前最近适用 entry 会作为窗口基线投影到该 USER。

适用谓词只有一个 `ConversationModelContextApplicability`：owner Assistant 在目标 selected branch
（含 active owner）、anchor USER 也在同一 branch、anchor 是 owner 之前最后一条真实 USER。START
判等、请求组装、Fork 和裁剪必须共用它，不得按 role 或列表位置猜测。

canonical envelope 形状见 [`prompts-and-tools.md`](prompts-and-tools.md)。完整 candidate 超过
256KiB UTF-8 时 `StartTurn` fail-closed，不得写入或发送截断信封。已提交 entry 永不后台改写；
未知 format 装载 fail-closed。Snapshot 是模型认知，不是授权。

## Disclosure 的请求形状

Provider-neutral 语义是：

```text
USER TURN
  part 0: synthetic disclosure context text
  part 1..n: original transformed user parts
```

跨协议统一构造一个有序 USER part 列表，不依赖相邻同角色消息保留独立边界。TimeReminder 与 USER 角色注入仍可以是独立 synthetic USER 消息（以便跳过 `messageTemplate`）；
Gemini `contents` 在编码后合并相邻同 role，见 [`protocol-reference.md`](protocol-reference.md)。

## 估算与未实现边界

- `Model` 没有可信 `contextWindowTokens`。不得用 `Assistant.maxTokens`（输出上限）或消息条数冒充
  输入窗口，也不得按估算值 fail-closed 挡 START。自动按模型窗口裁剪或自动语义摘要当前未实现。
- 估算是稳定启发式，不是计费 token：拉丁字母与空白约 4 字 / token，连续 ASCII 数字段约 3 位 /
  token，连续 ASCII 符号段约 2 字 / token，其他 Unicode code point 各 1。
- Provider 报窗口错误时保留原始错误，引导用户开条数窗口或手动摘要；不得为了重发静默覆盖历史。
- 不为缓存失效判断增加 Settings revision、Memory revision 或 Conversation 头部 disclosure 字段。

## 实现入口

| 边界 | 符号 |
| --- | --- |
| 请求前规划 | `RequestContextPlanner.planRequest` / `applyContextProjections` |
| 压缩规划 | `ToolOutputCompactionPlanner.planAfterSuccessfulRequest` |
| 冻结投影 | `TurnModelContextProjection`、`DurableMessageLocator` |
| 预算 | `ContextBudget`、`estimateStableTextTokens` |
| 归档 | `ToolOutputStore.stageCompaction`，回查 `read_tool_output` / `grep_tool_output` |
| 披露 | `ConversationDisclosureSnapshotService`，表 `conversation_model_context` |
| 适用谓词 | `ConversationModelContextApplicability` |
| 条数旋钮 | `Assistant.contextMessageLimit`、`effectiveContextMessageLimit()` |
| 手动摘要 | `ConversationApplicationService.compress()` → `GenerationSideEffects.compressConversation()` |
