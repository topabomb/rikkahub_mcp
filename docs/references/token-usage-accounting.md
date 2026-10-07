# Token 用量与缓存统计

本文定义 Token 用量的单请求归一化、Turn 累计、持久化和展示口径。四种协议的 usage 映射由本文统一维护；请求与回放协议见
[AI 协议](protocol-reference.md)；Turn 的提交时机由
[`turn-step-execution.md`](turn-step-execution.md) 定义。

## 1. 事实层级与唯一所有者

```text
Provider wire usage event
  → ProviderUsageSnapshot          单请求、协议无关快照
  → RequestUsageReducer            单请求 presence overlay 与 close-once
  → CompletedRequestUsage          已关闭请求事实
  → TurnUsageAccumulator           owning Assistant Turn 内按 request ordinal 累计
  → UIMessage.usage: TokenUsage    durable Turn 累计
  → Nerd line / ChatSizeChecker / Stats query
```

所有权固定如下：

- 各线协议 Adapter 只解释一次 Provider 请求的 wire usage，不读取历史消息，也不跨请求累计。
- `RequestUsageReducer` 是单请求快照合并和完整性判定的唯一 owner。
- `TurnUsageAccumulator` 是一个 Assistant turn 内多次 Provider 请求累计的唯一 owner。
- `UIMessage.usage` 是 durable Turn 累计的唯一事实；`UIMessagePart.Step.modelResult` 保存对应采样的 `StepUsage`、请求数与耗时。两者由同一已关闭请求事实派生，随 owning Assistant 消息通过同一 checkpoint 或终态事务提交，不互相反推、不建立第二账本。
- UI 只显示投影，`MessageNodeDAO` / `StatsQueryService` 只查询 durable JSON；二者都不重新计算 usage，也不建立账本。

各层只消费上述事实，不跨越归一化、累计与查询职责或建立第二账本。

历史迁移无法恢复逐 Step 的真实请求数与耗时：`StepModelResult.usageCompleteness=LEGACY`、`providerRequestCount=0`、时间字段为 null，原消息累计 usage 原样保留。这里的 0 表示未观测历史请求数，不能当作新请求的实测记录，也不能据此覆盖保留的 Turn 累计。

## 2. 规范数据模型

`ProviderUsageSnapshot` 的 token 数值字段均为 nullable `Long`；是否允许推导 total 由独立策略字段声明：

| 字段 | 含义 |
| --- | --- |
| `inputTokens` | 单请求 canonical 输入；cache read/write 与 tool-use 是其子集，不得再次相加 |
| `outputTokens` | 单请求完整输出；reasoning 是其子集 |
| `cacheReadInputTokens` | Provider 明确报告的缓存读取输入；缺失不等于零 |
| `cacheWriteInputTokens` | Provider 明确报告的缓存写入输入；缺失不等于零 |
| `reasoningOutputTokens` | 输出中的 reasoning 子集 |
| `toolUseInputTokens` | 输入中的 tool-use 子集 |
| `totalTokens` | Provider 权威总量，或由 Adapter 明确授权后在单请求边界安全推导 |
| `canDeriveTotalFromInputAndOutput` | 瞬态 Adapter 策略，不进入 durable `TokenUsage` |

`TokenUsage` 保存 Turn 累计 input/output/cache/细分/total。请求审计与累计字段按以下边界更新：

| 字段 | 更新边界与缺失语义 |
| --- | --- |
| `latestRequestContextTokens`、`latestRequestOutputTokens`、`latestRequestCacheReadInputTokens` | 最新请求关闭时同时覆盖；分别为该请求 input、output、cache read，缺失写 null |
| `latestRequestOutputDurationMillis` | 最新请求首个有效输出至关闭的时间，不含 TTFT；无输出则 null |
| `latestRequestCacheHitPercent`、`latestRequestTokensPerSecond` | 最新已关闭请求的 cache read/input、output/输出阶段时长；所需字段不全则 null，不沿用旧值。前者仅供请求审计，底栏另用 Turn 累计命中率 |
| `latestRequestEstimatedContextTokens` | 请求发送前按最终消息和工具 Schema 粗估，不等待 Provider usage；不是计费 token |
| `latestRequestTimeToFirstOutputMillis` | 最近实际产生首个有效输出的请求 TTFT；无输出请求不覆盖旧值 |
| `initialRequestTimeToFirstOutputMillis` | 仅记录本 Turn 第一个请求的 TTFT；后续请求不能补填 |
| `peakRequestContextTokens` | 已知请求 input + output 的最大值；已有请求却无历史峰值时保持未知 |
| `observedProviderRequestCount` / `observedUsageReportedRequestCount` | 已关闭请求数 / 其中报告 usage 的请求数 |
| `providerRequestDurationMillis` | 所有请求从发起到关闭的墙钟时间之和，不包含工具执行或审批等待 |
| `successfulToolOutputCompactionBatchCount` | 成功随 checkpoint 提交的非空滚动压缩批次数，一批多个结果仍只计一次 |
| `inputCompleteness`、`coreCompleteness`、`cacheReadCompleteness` | 输入、核心量与 cache read 分别判定完整性 |
| `semanticsVersion` | 当前写入版本 6；缺失时解释为历史版本 1 |

所有加法使用 checked `Long`。负值、子集大于父项、可验证的 total 不一致或溢出不能被修成看似精确的数字；保留仍可证明的字段，记录 typed diagnostic，并只降低受影响的完整性。Provider 已报告的 total 不被公共层覆盖。

## 3. 单请求与 Turn 累计算法

每次真实 Provider 调用创建一个新 `RequestUsageReducer` 和连续 request ordinal：

1. 流式事件按字段 presence 覆盖当前请求快照；字段缺失表示本事件不更新该字段，显式 `0` 必须覆盖旧值。
   携带 usage 的事件即使没有 choices/candidates 也必须进入 reducer，包括 Chat 最终 usage-only chunk、Responses
   completed/incomplete/failed terminal event 和 Gemini usage-only event。
2. `StepRunner.generateInternal` 在调用 Provider 前估算最终消息和冻结工具 schema，写入 owning Assistant
   草稿。估算使用 `RequestContextPlanner.estimateRequestContextTokens` 与 `estimateStableTextTokens`，
   是稳定启发式，不是计费 token；规则见 [请求上下文](request-context.md)。
3. 首个 Text、Reasoning、Tool 或媒体 payload 到达时刷新该请求 TTFT；空协议事件和 usage-only 事件不触发。流式 usage
   仍只进入当前 `RequestUsageReducer`，不提前改写累计账本。
4. 正常、失败和取消都在 Provider 调用的 `finally` 路径关闭请求；一个 reducer 只能关闭一次。
   `onAssistantObserved` 在可取消 Transformer 和显示交付前同步交接已关闭 usage，
   `TurnCommitter.observeAssistant` 更新唯一 owning Assistant 槽。取消终态使用该槽，不要求取消后的
   UI 投影交付成功。checkpoint / 终态提交与取消传播规则见 [执行链路](turn-step-execution.md)。
5. `TurnUsageAccumulator.apply()` 只接受下一个连续 ordinal，因此一次请求最多累计一次，重复或跳号立即失败。
6. checkpoint 成功后，该 turn 累计值才成为后续 step 或审批继续的 durable baseline。

turn 聚合规则：

- input、output、cache read/write、reasoning、tool-use 和 Provider request duration 分别按请求求和。
- `totalTokens` 按各请求的权威 total 求和，不在 Turn 末尾用累计 input + output 重写。
- 最近请求字段、TTFT 与峰值按上表各自的边界更新，不能混用请求状态与累计值，也不能将缺失解释为零。
- 没有 usage 的失败请求仍计入 `observedProviderRequestCount`，但不增加 `observedUsageReportedRequestCount`，并使相关 turn 完整性降级。
- Provider 内容已经返回时，即使随后失败、取消或响应 incomplete，已收到的 usage 仍随原 turn 提交；取消异常继续传播。
- Google / Responses 的非流式 HTTP 成功但协议失败响应先解码可用内容和 usage，再抛出携带该快照的
  `ProviderResponseException`。`StepRunner` 先接收快照，再沿原失败链关闭请求；不执行失败响应中的工具。
  其他 `generateText` 调用者仍收到异常，不会把 partial 响应当成成功。
- `CONTINUE_USER_INTERACTION` 从原 Assistant 消息的已提交 usage 恢复，不创建第二 turn，也不重复加入 checkpoint 前的请求。
- 非空 Tool Output 滚动裁剪批次把 marker、可选 archive metadata 与 trim count 的 `+1` 放入同一个 checkpoint；计划为空、提交失败或
  提交前取消不计数，已提交消息的幂等重放也不会再次计算。
- 历史版本 1 baseline 的请求边界不可恢复；若继续运行，完整性按当前请求合并为 `PARTIAL`，不保留 legacy 特殊累计或显示路径。

## 4. 四种线协议映射

下表描述当前 Adapter 的映射；供应商协议入口：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat)、
[OpenAI Responses](https://platform.openai.com/docs/api-reference/responses)、
[Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)、
[Gemini GenerateContent](https://ai.google.dev/api/generate-content) 与
[Vertex GenerateContentResponse](https://cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/GenerateContentResponse)。
兼容 endpoint 只有经过 `resolveOpenAIEndpointVendor` 识别的 vendor 才能启用其专有 cache 方言。

| 线协议 | canonical input / context | canonical output | cache read / write | total |
| --- | --- | --- | --- | --- |
| OpenAI Chat Completions | `prompt_tokens` | `completion_tokens` | `prompt_tokens_details.cached_tokens` / `cache_write_tokens`；Moonshot 顶层 `cached_tokens` 与 DeepSeek `prompt_cache_hit_tokens` 只按已识别 endpoint vendor 使用 | `total_tokens` 优先；缺失且 input/output 完整时安全推导 |
| OpenAI Responses | `input_tokens` | `output_tokens` | `input_tokens_details.cached_tokens` / `cache_write_tokens` | `total_tokens` 优先；缺失且 input/output 完整时安全推导 |
| Anthropic Messages | `input_tokens + cache_read_input_tokens + cache_creation_input_tokens` | `output_tokens` | `cache_read_input_tokens` / `cache_creation_input_tokens` | 合并 `message_start` 与 `message_delta` 的互补快照后安全推导 canonical input + output |
| Gemini generateContent | `promptTokenCount + toolUsePromptTokenCount` | `candidatesTokenCount + thoughtsTokenCount`（缺失时回退 `responseTokenCount`） | `cachedContentTokenCount` / 不提供 | Provider `totalTokenCount` |

`response.completed`、`response.incomplete` 和 `response.failed` 使用同一个 Responses usage decoder。Anthropic 的
`message_start` 与 `message_delta` 是同一请求的互补快照，不是两个请求。Gemini 的 tool-use 和 thoughts 已分别包含在
canonical input/output 中，不能再次加入 total；其 Provider total 保持权威。Gemini 的 `cachedContentTokenCount`
同时覆盖显式与隐式缓存命中，且是 `promptTokenCount` 的子集，因此命中率分母仍为 canonical input。

## 5. 完整性语义

`UsageCompleteness` 的含义：

| 值 | 含义 |
| --- | --- |
| `COMPLETE` | 当前语义版本中该维度的每个已观察请求均有完整、有效数据 |
| `PARTIAL` | 至少保留一个已知值，但存在缺失、无效或溢出的请求 |
| `NONE` | 当前已观察请求均没有该维度的可用值 |
| `LEGACY` | 历史记录没有足够证据恢复请求边界或完整性 |

单请求 core 需要有效的 input、output 和 total 才是 `COMPLETE`；cache-read 是否完整独立判断。turn 只有在 baseline 与新请求均为 `COMPLETE` 时才能保持 `COMPLETE`；其他组合按已知值归为 `PARTIAL` 或 `NONE`。响应成功与 usage 完整是两件事，不能用 HTTP/流终态推断缺失字段为零。

## 6. Master 与 Child 隔离

usage owner 是实际发起请求的 Assistant 消息。Master 与每个 Child 使用独立的 `ConversationRuntime`、`TurnCommitter` 和
各自的 `TurnUsageAccumulator`；`TurnRunState.accumulator` 则只负责模型输出片段合并：

- Target usage 只写 Child Assistant 消息，并在子助手详情中显示。
- 子助手工具结果只向 Master 返回文本和附件投影，不复制 Child 的 message usage。
- Child Provider 失败且未返回 usage 时，只影响 Child 自己的完整性，不改变 Master usage。
- Master 在工具调用前后通常各发起一次自己的 Provider 请求；后一次用于读取工具成功或失败结果，因此两次 Master input 都应计入同一个 Master turn。

例如 Master 两次请求分别报告 16.7K input + 0.3K output、16.5K input + 0.2K output，则摘要 Context 显示最新请求
input 16.5K，`peakRequestContextTokens` 保留峰值 17.0K，展开后的 turn input 显示约 33.2K。即使 Child 因余额不足未产生
usage，33.2K 仍是两次 Master 请求之和，不是 Child 串账。

## 7. 持久化与兼容

Turn 累计 usage 位于 `UIMessage` JSON 中，不设独立 usage 表。`TokenUsage` 的 Kotlin 属性只使用
`inputTokens`、`outputTokens`、`cacheReadInputTokens` 等规范名称；以下 `@SerialName` 仅固定既有存储键：

| Kotlin 属性 | 既有 JSON key |
| --- | --- |
| `inputTokens` | `promptTokens` |
| `outputTokens` | `completionTokens` |
| `cacheReadInputTokens` | `cachedTokens` |

nullable usage 字段缺失时默认 `null`。Turn 累计字段不猜测回填；transcript 升级中无法恢复的逐 Step
计量使用 LEGACY，已有 Turn 累计仍保持原值。旧记录可以继续解码，但 UI 不为 `LEGACY` 建立特殊显示或计算旁路。Stats 仍按数据库实际保存的历史值查询。

## 8. 消费者口径

### 聊天消息统计

`ChatMessageNerdLine` 只消费 owning Assistant 的 usage。当前请求状态量与 Turn 累计量不可混算：

| 指标 | 口径与缺失处理 |
| --- | --- |
| 上下文 | 最近已关闭请求的 canonical input；无实测 input 时用最近发送前估算并标 `~`。下一请求进行中保留最近已关闭实测；请求关闭却未报告 input 时清空该实测，不沿用更早值 |
| 缓存命中率 | Turn 累计 cache read / input；两者完整性均为 COMPLETE、input > 0 且 cache read ≤ input 时才显示。`latestRequestCacheHitPercent` 仅供审计 |
| 工具输出压缩次数 | 本 Turn 随 checkpoint 成功提交的压缩批次数，一批多个结果仍计 1 |
| Turn 耗时 | 从 createdAt 到当前时间；`FinalizeTurn` / `RecoverInterruptedTurn` 提交终态后使用 finishedAt 冻结，包含工具、审批与输入等待 |
| Input / Output / Cached | Turn 累计值，分别由 input/core/cache-read 完整性控制，非 COMPLETE 不显示数值 |
| Provider 耗时 / 请求数 | 前者累计 Provider 请求墙钟，不含工具和审批；后者包含成功、失败和取消的已关闭请求 |
| tok/s / TTFT | tok/s 取最近已关闭请求的 output / 输出阶段时间；TTFT 取最近实际产生首个有效输出的请求，空输出不覆盖旧值；均不可累计 |

缺失不等于零，明确的 cache read 零仍可显示 `0.0%`。新 START 创建 `usage=null` 的 Assistant 槽，不继承上一轮；交互继续复用原槽。中间 Step 不能冻结 Turn 耗时，活动状态也不从统计字段推导。布局和展开交互由 [UI 架构](ui-architecture.md)维护。

### 上下文预警

`ChatSizeChecker` 只读取 `latestRequestEstimatedContextTokens`，表达最近一次最终 Provider 请求投影的发送前估算规模。
它不读取 turn 累计 input，也不从估值中减去 cache read。

### Stats

Stats 表示“当前域在数据库仍保留的 Provider usage”，不是账户终身账单：

`MessageNodeDAO` 在 `json_each` 参数内验证文档为有效数组，在 `json_extract` 参数内保护非对象成员；损坏文档、非数组顶层与非对象成员不参与统计。此防护不改写原行，也不把损坏会话恢复为 Ready；统计仍仅表示可解析记录，不代表数据修复。

- token 汇总包含主会话、Child conversation 和仍保留的 regenerated variants，因为它们都真实发起过请求。
- 会话数、可见消息数和每日消息数只计本域主会话；Token 汇总按所属会话 scope 包含本域 Child，不因复用同一个个人助手而合并企业与个人用量。应用启动次数仍为全局值。
- StatsQueryService 在原选中域/Session 授权内读取；StatsVM 切域时清空旧统计并取消旧查询，查询失败显示失败状态，不继续展示旧值或把零值作为成功结果。
- 删除会话或节点后，对应 usage 从 retained-history 汇总中消失。
- cache 只累计 cache read，不把 cache write 称为命中或节省。
- core/cache-read 非精确记录分别计数；存在 legacy、partial、none 或缺失 usage 时，以一行短说明标明统计包含旧版或不完整记录。
- cache 已知累计为零时不显示缓存卡，避免把未知误呈现为精确零。

## 9. 修改边界

核心模型由 `Usage.kt` 的 `TokenUsage` / `ProviderUsageSnapshot` 定义，合并和累计在 `TokenUsageAccounting.kt`；Provider Adapter 产生单请求快照，
`StepRunner` 交给原 Turn 提交链。变更计量语义须核对单请求关闭、Turn 累计、协议映射与聊天/统计消费者的同一口径。

协议映射由 Adapter 负责，累计和完整性只由 request / Turn owner 负责。修改字段时需同步
Stats SQL、序列化与消费者；测试分层和门禁见 [测试策略](testing-strategy.md)。
