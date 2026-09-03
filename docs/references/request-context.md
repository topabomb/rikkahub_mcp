# 请求上下文

本文档是请求级上下文策略的当前总览：条数窗口、Tool Result 滚动压缩、Disclosure Snapshot 与
用户触发的语义摘要如何叠在同一次 Provider 请求上。Turn / checkpoint 协议见
[`chat-generation-pipeline.md`](chat-generation-pipeline.md)，模型看见的文案与工具形状见
[`prompts-and-tools.md`](prompts-and-tools.md)，披露表与 Artifact 见
[`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)，
用量与估算口径见 [`token-usage-accounting.md`](token-usage-accounting.md)，条数旋钮见
[`assistant-configuration.md`](assistant-configuration.md)。

Durable Conversation、Conversation Presentation 和 Model Request Plan 是三个概念。请求投影不能
成为第二持久化事实源。`conversation_model_context` 属于 Conversation aggregate，不进入
Presentation / FTS / UI。

## 1. 四层

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

### 1.1 条数窗口

`Assistant.contextMessageLimit` 默认 `0`（关闭）。启用后由 `effectiveContextMessageLimit()` 归一化到
`40..512`，UI 重新打开开关写入默认 `80`。持久化仍保存原始字段；导入或异常备份不会绕过该归一化。
旧 `contextMessageSize` 已删除，由 `ignoreUnknownKeys` 忽略，不会静默打开新策略。

超限时 `ConversationContextPlanner` 内部 `limitContext` 按 50% 保留比例一次前移较大步幅，再
`findUserTurnStart()` 回退到完整 USER 轮次。工具调用与结果在同一个 `UIMessagePart.Tool` 里，因此
可靠边界是最近的 USER，而不是按 `toolCallId` 向前配对未执行 Tool。旧逐条滑动窗口每增加一条消息就
挪起点，会持续打断从请求开头建立的提示缓存。

`80` 而不是 `40` 作为启用默认值，是因为一轮通常新增 USER + ASSISTANT 两条；40 条约每 10 次请求
移动一次锚点，80 条才接近「约 95% 请求保持同一前缀起点」的量级。这只解释失效频率，不承诺命中率。

### 1.2 滚动压缩

生产阈值只来自 `ContextTrimmingPolicy`。完整规则、marker、Artifact 与 receipt 见
[`chat-generation-pipeline.md`](chat-generation-pipeline.md)
与 [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md)。

### 1.3 Disclosure Snapshot

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

### 1.4 手动摘要

`ConversationApplicationService.compress()` 是用户触发、持久化且不可撤销的摘要，切点对齐完整
USER 轮次，与滚动压缩、条数窗口独立。分块与 `targetTokens` 语义见
[`chat-generation-pipeline.md`](chat-generation-pipeline.md)。

## 2. 组装顺序

`ConversationContextPlanner` 是唯一纯规划边界。每次真实 Provider 调用：

```text
durable selected branch
  → replay-safe projection
  → limitContext（条数窗口，对齐完整 USER 轮次）
  → Input Transformers（不重读 Settings / 时钟 / Locale / Workspace）
  → applyContextProjections：把选中 Snapshot 作为 durable USER 的第一个 Text part
  → 发送
  → 成功后再 planPostStepCompaction（只认本次 receipt 里仍 inline 的 Tool Result）
```

`limitContext` 是 planner 内部函数，不是 `List<UIMessage>` 公共 API。System / 冻结 Tool prompt 与
Provider 请求共用同一份 `RequestContextPlan`。Snapshot 在 transformers 之后注入，因此不经过
messageTemplate、Placeholder、Time / Workspace Reminder、DocumentAsPrompt 或附件投影。

`GenerationLoop.generateInternal` 在发请求前对最终投影做 `estimateRequestContextTokens`；压缩水位
用同一条 `estimateStableTextTokens`，但只加本次可见的 inline tool 正文，不看整包请求估算或
Provider `input_tokens`。`ChatSizeChecker` 的预警读取最近一次发送前估算，也不参与压缩决策。

## 3. 叠加

- **窗口开**：压缩器只看见后缀里发出去的 tool；窗口外的全文仍在库里，本轮不归档。
- **窗口内压缩**：改请求中间的历史 tool 正文，从被改处打断缓存；更早的 System / USER 仍可能命中。
- **跨台阶**：请求第一条 USER 换人，baseline Snapshot 跟到新的第一条，整段前缀失效一次。
- **同 Turn Memory / 子助手工具**：Snapshot 不变，只追加 Tool Result；新 START 才换 baseline。
- **手动摘要**：改的是消息树，与滚动压缩、条数窗口独立；切点同样对齐 USER。
- **窗口外旧 Snapshot**：不发送。retained 第一条真实 USER 之前（含同位置）最近一条适用 Snapshot
  作为 window baseline，投影到该 USER；窗口内更晚的 Snapshot 保持在各自因果 USER 前。

旧逐条滑动窗口已删除：每增加一条消息就挪起点会持续打前缀。现行台阶在越过阈值时一次前移较大步幅。

### 3.1 提示缓存：什么保持稳定，什么会变

有利于前缀稳定的条件：

- `TemplateTransformer` 使用每条消息的 `createdAt`，不用当前时间重写历史。
- `TimeReminderTransformer` / Placeholder / Workspace reminder 消费 START 时冻结的
  `FrozenTurnPromptInputs`，审批跨日或改 Locale 不改本 Turn。
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

## 4. 为什么 Snapshot 是 append-only entry，不是 Conversation 头部快照

把「当前 Memory / Catalog」反复覆盖进 Conversation 头部，再用 generation 解释后面的旧 Tool Result，
会把后发生的状态搬到历史开头，并在 Fork 与窗口裁剪时失去该点之前的真实 baseline。append-only
entry 由本次 `START` 的 Assistant request variant 拥有、锚定其因果 USER：

- 时间顺序保持 `Snapshot A → 工具 A→B → Snapshot B`，模型不需要学代际覆盖算法。
- Fork 按 owner / anchor node 映射复制；切回旧 Assistant variant 恢复它所拥有的历史 baseline。
- 不需要 `MutationOrigin`、Settings revision 或 Memory revision 表。无论变化来自 Agent、用户还是
  有效配置切换，只在下一自然 `START` 比较完整 candidate。
- 即使旧 Tool mutation 已离开条数窗口，下一 START 的完整 Snapshot 已经作为尾部 context 保存，
  模型不会退回过期的 A。

适用谓词只有一个 `ConversationModelContextApplicability`：owner Assistant 在目标 selected branch
（含 active owner）、anchor USER 也在同一 branch、anchor 是 owner 之前最后一条真实 USER。START
判等、请求组装、Fork 和裁剪必须共用它，不得按 role 或列表位置猜测。

canonical envelope 形状见 [`prompts-and-tools.md`](prompts-and-tools.md)。完整 candidate 超过
256KiB UTF-8 时 `StartTurn` fail-closed，不得写入或发送截断信封。已提交 entry 永不后台改写；
未知 format 装载 fail-closed。Snapshot 是模型认知，不是授权。

## 5. 为什么是一个 USER turn，而不是相邻两条 USER

Provider-neutral 语义是：

```text
USER TURN
  part 0: synthetic disclosure context text
  part 1..n: original transformed user parts
```

Anthropic Messages 会把连续同 role 输入合并成一个 turn；Gemini 官方多轮结构要求 `user` / `model`
交替。跨协议合同因此主动构造一个有序 USER part 列表，不依赖「两条相邻 USER 永远保持两个独立语义
turn」。TimeReminder 与 USER 角色注入仍可以是独立 synthetic USER 消息（以便跳过 `messageTemplate`）；
Gemini `contents` 在编码后合并相邻同 role，见 [`protocol-reference.md`](protocol-reference.md)。

## 6. 明确不做

- `Model` 没有可信 `contextWindowTokens`。不得用 `Assistant.maxTokens`（输出上限）或消息条数冒充
  输入窗口，也不得按估算值 fail-closed 挡 START。自动按模型窗口裁剪或自动摘要，仍要求注册表默认值
  + 用户覆盖的窗口元数据、序列化后的 token 估算，以及可审计的 opt-in 摘要；当前未实现。
- 估算是稳定启发式，不是计费 token：拉丁字母与空白约 4 字 / token，连续 ASCII 数字段约 3 位 /
  token，连续 ASCII 符号段约 2 字 / token，其他 Unicode code point 各 1。
- Provider 报窗口错误时保留原始错误，引导用户开条数窗口或手动摘要；不得为了重发静默覆盖历史。
- 不为缓存失效判断增加 Settings revision、Memory revision 或 Conversation 头部 disclosure 字段。

## 7. 实现入口

| 边界 | 符号 |
| --- | --- |
| 规划 | `ConversationContextPlanner.planRequest` / `planPostStepCompaction` / `applyContextProjections` |
| 冻结投影 | `TurnModelContextProjection`、`DurableMessageLocator` |
| 预算 | `ContextTrimmingPolicy`、`estimateStableTextTokens` |
| 归档 | `ToolOutputStore.stageCompaction`，回查 `read_tool_output` / `grep_tool_output` |
| 披露 | `ConversationDisclosureSnapshotService`，表 `conversation_model_context` |
| 适用谓词 | `ConversationModelContextApplicability` |
| 条数旋钮 | `Assistant.contextMessageLimit`、`effectiveContextMessageLimit()` |
| 手动摘要 | `ConversationApplicationService.compress()` → `GenerationSideEffects.compressConversation()` |
