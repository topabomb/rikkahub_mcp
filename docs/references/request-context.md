# 请求上下文

本文维护应用上下文从捕获、请求接纳到历史回放的生命周期，以及条数窗口、Tool Result 滚动压缩和手动摘要的边界。
Turn/checkpoint 归 [`turn-step-execution.md`](turn-step-execution.md)，模型可见文案与工具形状归
[`prompts-and-tools.md`](prompts-and-tools.md)；其余依赖在对应规则处引用。

Durable Conversation、Conversation Presentation 和 Model Request Plan 是三个概念。请求投影不能
成为第二持久化事实源。应用输入的正文、来源、请求接纳与位置属于 Conversation aggregate。Presentation 只提供轻量摘要，
详情通过授权 query 按需读取；正文不进入会话列表、FTS 或普通消息分享。变化短标签只定位首次接纳该通知的请求，
详情仅展示该请求新增的 EXTERNAL 分区；沿用历史不新增标签或请求条目。该条通知的完整原文和来源在对应变化详情中折叠查看，
不设消息“更多 → 上下文”入口。此展示筛选不改变接纳记录、请求投影或历史回放。

## 上下文策略

| 层 | 目的 | 改 durable 会话 | 默认 |
| --- | --- | --- | --- |
| 条数阶梯窗口 `contextMessageLimit` | 控制发送历史长度，减少相邻请求的前缀漂移 | 否 | 关（`0`） |
| Tool Result 滚动压缩 | 缩短已消费的 inline tool 正文 | 只替换该 Tool 的 output 为 marker / archive | 开（预算触发） |
| 状态同步 `conversation_disclosure_snapshot` | 对比本请求可知事实与合法当前事实，只补充尚未知的分区 | 请求前通过 `AdmitRequestContext` 提交内容及接纳记录 | 每个新请求边界对账；重试复用 |
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

全部阈值只来自 `ContextBudget`：inline Tool 正文达到 64 × 1024 estimated tokens 才触发，目标低水位
24 × 1024，整批至少净回收 32 × 1024。最近两个 Step 工具批次与最近 12 × 1024 estimated tokens
受保护；单个结果净回收至少 256。不额外保护整个已完成 USER 轮次，也不使用 Provider input/cache 指标决策。

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

## 输入生命周期与冻结

| 输入 | 采样或持久化边界 | 后续使用 |
| --- | --- | --- |
| System、模型参数、工具定义、提示规则、变量、Workspace 说明 | 每次 START 捕获 | 同一 Turn 的 Step、重试与交互继续复用；下一 START 可更新 |
| 运行记忆、可见子助手目录 | 每个新请求在当前权限内读取 | 与请求可见历史对账，仅补充未表达或已丢失的状态 |
| 企业只读 Memory Seed | START 捕获 | 本 Turn 固定；不是可写记忆记录 |
| Starter opening | 首次用户提交保存到会话根 | System 按助手适用性选择，背景按窗口投影；不重新读取发布定义替换历史 |
| 预置消息、手动摘要 | 原会话命令持久化 | 按自身消息来源和分支回放，不在每次请求重新实例化 |
| 时间、文档与附件 | 请求变换后接纳来源和实际文本 | 已保存原文用于历史查看；新请求仍按窗口、当前媒体能力和授权决定适用性 |

冻结模型输入不冻结执行权限：实际模型、工具、文件和企业请求仍由原 owner 复验准入。

### Turn 冻结与状态同步

`TurnContextFactory.prepareLaunch` 捕获配置、变量、Workspace、工具定义和地址；`materialize` 以
`freezeTurnSystem` 组装本 Turn 的最终 System。领域指令优先级为会话覆盖、适用企业 opening、助手定义。
固定应用解释规则、工具贡献、Workspace 提醒、System 位置注入均进入同一冻结文本；普通配置变化
在下一次 START 捕获。`CONTINUE_USER_INTERACTION` 保留原 TurnHandle 和快照。

`TurnDisclosureSource` 保存原调用者、Memory 地址和只读 Seed。每个新请求前，从当前合法配置读取
可见助手目录、从原 Memory 地址读取状态；Seed 使用 Turn 捕获值。当前准入与实际安装能力可以收紧，
不能凭历史描述扩权。发送重试前仍复验调用者、Memory 和 Artifact 权限，不重新采样输入。

`ConversationDisclosureReconciliation` 将最终请求中实际可见的历史状态包及已确认的内置工具
input/output 按因果顺序归并为“可见历史已表达状态”（K），并与“当前合法状态”（C）对账。
这里的 K 是应用可以从输入证明的事实，不是对模型内部记忆的推测；C 也不是跨所有 owner 的全局事务快照。工具身份由本地 producer 的
`executionIdentity` 固定到 `TurnContextSelection`；同名 MCP 不获得内置写效果语义。只有已提交成功
且结果足够的操作可以推导效果，未执行不产生效果，不确定或被压缩/移出窗口的效果标为缺失。
自身已表达效果无需再注入；跨会话或其他未表达差异在下一请求边界补充。没有新请求则不主动生成消息。

例如，历史已表达 A，本会话工具成功把它改成 B，随后外部又改回 A：基准已经是 B，下一请求应披露 A。
若外部在两次采样间 A→B→A 且 B 从未进入请求，则无需通知。通过设置页进行的修改没有当前请求工具证据，
仍属于外部变化；子助手的自然语言总结也不能证明共享记忆的精确写入。

`DisclosureSectionChange` 在同一次对账中保存 EXTERNAL_STATE 的逐项差异：新增 ID、修改/移除前的行、
变化前的属性，以及只读背景的相对顺序变化。比较基准是已归并自身成功工具效果的实际可见 K，
不是上一条状态包，更不是查看详情时的 Settings。更新后内容由同条 entry 的完整分区正文提供。
差异仅随 `ConversationContextSource.Disclosure.changes` 与正文一同接纳，服务历史展示；不进入模型输入、
不改变 format 3、不增加全局事件表或另一份当前配置。这里的移除指退出模型可见集合，不证明资源被物理删除。
INITIAL/BASELINE_RESTORE 不保存外部差异。历史 source 缺少 changes 时保持 null，详情明确展示当时完整状态，
不得推断逐项修改。该默认可空字段沿现有 payload version 1 保存，不改 Room schema 或旧正文。

Fork 保留原 `TurnContextSelection` 与 Tool typed result，但不制造新的历史 execution 行。
`TurnRequestAdmission` 在原 builtin 身份及 namespace 适用时，接受已提交的 `COMPLETED` 结果作为成功证据；
若本地 execution 存在，仍要求执行与结果均成功。明确 `DENIED` 不产生效果；原 Turn 仍被记录而没有
Tool execution 的校验拒绝也是未执行。没有原 Turn 记录的失败或未确认结果无法证明未执行，按 RESTORE
补齐认知，不伪装成外部变化。结果正文不足、压缩或移出窗口仍沿原缺失事实规则恢复。

新增包使用 format 3：出现的分区是该分区完整状态，缺省表示未提供，空 rows 表示清空；未变化分区省略。
完整 C 的 256 KiB UTF-8 校验先于省略，不能以增量小为由绕过。支持的 format 1/2 历史保持原 bytes；
历史读取按这两个格式的身份语法验证旧地址来源引用，仅用于语法校验，不转换正文，也不作为当前资源选择。
新写入的 canonical envelope 和 format 3 仍只接受当前规范引用，非法旧身份也明确拒绝。
未知 namespace 只能证明曾披露、不能证明当前域认知；已知不兼容域不投影。未知格式明确拒绝。

`RequestContextPlanner` 只回放 selected branch、当前请求边界之前、原因果位置仍可见的历史包。
窗口移走或压缩丢失事实后，在当前因果尾部恢复合法 C，原因记为 RESTORE；不把旧包搬到窗口首部。
初始信息、外部变化和窗口恢复分别记录 INITIAL、EXTERNAL、RESTORE，正文保持同一种状态格式。
每个完整工具批次的结果之后，才能在下一 Step 前插入 USER 内容；不能插进 call/result 中间。

## 请求接纳与来源

`TurnRequestAdmission` 在最终输入变换、位置计算和 `RequestAssembler.assemble` 校验后，调用
`AdmitRequestContext`，经原 Conversation command/transition/committer 事务提交。内容由消息 variant
拥有，创建 Step、因果 anchor、实际使用位置分开保存；复用同一正文无需复制正文。无新增内容也保存
该 Step 接纳，后续 Step 只记录关联差异；`Omitted` 明确关闭继承，空差异不表示关闭。

接纳内容覆盖实际 System、提示规则、状态同步、时间提示、文档/附件输入、Starter 背景以及
预置/摘要来源。`ContextPlacement` 区分 System、BeforeMessage、MessagePart、BeforeStep、
MessageOrigin 和 Omitted。MessagePart 保存最终请求的 part 偏移，附件 source 另存原始 part 偏移。
新预置与摘要以 `MessageReference` 指向自身 immutable variant，不复制原文、不猜测历史来源。

规则与时间/文档文本保存原输入和实际文本；已接纳文档复用原文，时间沿原消息时间、真实 USER 前驱与
首次渲染时区复用。只读取本次窗口需要的 Artifact。非 disclosure 正文超过 64 KiB 时通过
`ArtifactStore` 暂存为 immutable text，再随同一事务建立 CONTEXT 引用并发布；失败精确释放未发布资源。
Artifact identity 与路径必须同时匹配，读取仍需原域授权，路径复用不重绑定旧来源。

时间提醒将消息的首次时间解释与前驱间隔分开：同一真实 USER 身份及 createdAt 使用已保存
`MessageTime.zoneId`，前驱改变只重算 gap；前驱有首次时区时按它自己的时区转换为 Instant，
不因窗口裁剪或设备切换时区改变已知时间。无已接纳事实时才使用当前 Turn 捕获的时区，
复用 metadata 不读取窗口外正文，也不建立单独的消息时区存储。

接纳先于 Provider IO。网络重试沿已组装输入，重新进入已接纳 Step 也必须匹配其 selection、窗口和位置，
不重新采样 C 或默许消失的贡献。接纳表示应用输入已定稿，不代表请求成功或模型已经消费；UI 沿原
Step/请求状态说明发送结果。进程恢复使用原中断 Turn 终态协议，不承诺精确续跑远端未知结果。

编辑或切换历史 USER 后，`RequestContextPlanner` 先在完整 entry 索引验证引用，再用
`ConversationModelContextApplicability` 判断是否仍适用。失效披露不回放，其兼容分区在引用它的
历史 Step 记为 Missing，新 START 必要时于尾部恢复当前 C；真正缺失的引用仍报错。历史原文不改写。
`resolveRequestContextUses` 只读取所查看 Turn 明确接纳及同 Turn 继承的贡献，旧 windowStart/placement
允许定位原 node 内仍保存的 USER variant，owner 仍须当前选中。前序 Turn 的变化从其原通知查看；其他保存内容仍由 query 按各自用途读取，
不根据今日的历史 Assistant 选择推定旧请求输入，不提供完整历史应用上下文或 HTTP 快照。
没有 admission 的历史原文仍可由授权 query 读取，但不补造更新标签或请求边界；保存的 anchor 必须仍存在且因果合法，
但不要求旧 USER variant 当前选中。它只提供历史查看能力，不放宽 planner 的回放适用性。

## 手动摘要

`ConversationApplicationService.compress()` 经 `GenerationSideEffects.compressConversation()` 生成摘要，
再以 durable tree command 替换历史。它由用户显式触发、持久化且不可撤销，不挂到自动发送链路。
保留最近消息的切点回退到完整 USER 轮次，因此配置数量是最低保留量；可压缩前缀为空则报错。
保留节点沿用原 node/variant 身份和分支；新摘要与 HistorySummary 来源同事务提交，普通 UI 显示摘要原文，
模型输入使用 `conversation_history_summary` format 1 数据封装并跳过用户模板。

待摘要消息超过 256 条时递归分块，中点优先回退到 USER；没有可用的前置 USER 切点时按原中点分开。
每条消息通过 `summaryAsText(maxLength = 2000)` 提供正文摘要，Step 标记不参与；这不是附件全文或
工具 output 的无损备份。`targetTokens` 只进入摘要提示，不是本地硬校验。

## 组装顺序

请求前由 `RequestContextPlanner` 纯规划，请求成功后由 `ToolOutputCompactionPlanner` 纯规划压缩。每次真实 Provider 调用：

```text
durable selected branch + 已提交请求来源/接纳
  → replay-safe projection → limitContext（完整 USER 轮次）
  → 本 Turn 冻结 System + Input Transformers（普通持久消息应用模板，应用来源内容按字面投影）
  → 合法 C / 实际可见 K 对账 → 原位置历史 + 本次因果尾部应用输入
  → RequestAssembler 校验、授权文件校验、稳定 token 粗估
  → AdmitRequestContext 事务（内容 + selection/位置差异 + 零变化接纳）
  → 当前权限复验 → Provider IO（重试复用输入）
  → 成功请求 receipt → ToolOutputCompactionPlanner 纯规划 → 原 checkpoint 提交
```

`limitContext` 是 planner 内部函数。应用状态、文件正文、摘要和 Starter 背景不会再经过
messageTemplate 或 Placeholder 二次解释。完整工具批次可以将历史 ASSISTANT 按 Step 分段，
每段保留自己的协议 metadata；Provider encoder 继续使用各协议原有 role/工具映射。

`StepRunner.generateInternal` 在发请求前对最终投影做 `estimateRequestContextTokens`；压缩水位
用同一条 `estimateStableTextTokens`，但只加本次可见的 inline tool 正文，不看整包请求估算或
Provider `input_tokens`。`ChatSizeChecker` 的预警读取最近一次发送前估算，也不参与压缩决策。

## 分支变更与事实保全

`ConversationModelContextApplicability` 统一 selected variant 与因果 anchor 适用性；预置/摘要自身拥有并
锚定其消息。Fork 映射 node/message/entry/接纳引用，保留未选 variant 的事实；删除或裁剪通过
`ConversationContextTransition.prune` 清理无效引用并保全存活来源。被删位置明确关闭，仍被保留请求使用的不可变正文交接给
合法存活 owner，BeforeStep 只可在同一因果 owner 内重新定位，不搬入任意 USER。

只有删除消息、截断或替换消息树执行该重整。START、请求接纳、checkpoint 及普通配置命令保留既有
entries/admissions 的结构共享，不反复重放全部历史来裁剪；Turn checkpoint 只能扩展已提交 Step，不能移除历史身份。

`ConversationContextIntegrity` 在接纳、装载与恢复时检查身份/引用/域边界；Room 对应表保存
opening、context entry、request admission 和关联；大正文使用分段读取。格式版本和迁移由
[数据持久化](data-persistence.md)与[配置架构](android-configuration-architecture.md)维护。Snapshot 是事实描述，不是授权。

## 跨协议请求形状

初始状态可以位于因果真实 USER 的前置 Text part，原 USER parts 的相对顺序不变。Step 中外部变化：

```text
ASSISTANT: tool calls
TOOL: 全部对应 results
USER: conversation_disclosure_snapshot format 3（仅有必要分区时）
ASSISTANT: 下一次模型响应
```

这是一种解释用的角色序列。内部使用 provider-neutral 消息，OpenAI Chat/Responses、Claude 和 Gemini
由原 encoder 映射；后两者可能将工具结果与后置上下文放在同一 USER 容器，完整结果仍必须在前。
不为状态同步修改用户输入，也不依赖相邻同 role 保留独立消息边界。详见
[`protocol-reference.md`](protocol-reference.md)。

## 估算与未实现边界

- `Model` 没有可信 `contextWindowTokens`。不得用 `Assistant.maxTokens`（输出上限）或消息条数冒充
  输入窗口，也不得按估算值 fail-closed 挡 START。自动按模型窗口裁剪或自动语义摘要当前未实现。
- 估算是稳定启发式，不是计费 token：拉丁字母与空白约 4 字 / token，连续 ASCII 数字段约 3 位 /
  token，连续 ASCII 符号段约 2 字 / token，其他 Unicode code point 各 1。
- Provider 报窗口错误时保留原始错误，引导用户开条数窗口或手动摘要；不得为了重发静默覆盖历史。
- 不为缓存失效判断增加 Settings revision、Memory revision 或 Conversation 头部 disclosure 字段。
