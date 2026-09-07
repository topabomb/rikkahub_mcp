# Token 用量统计架构

本文档是 Token 用量语义、所有权、累计、持久化和展示口径的当前权威参考。Provider 的其他请求与回放规则见
[`protocol-reference.md`](protocol-reference.md)，会话提交协议见
[`turn-step-execution.md`](turn-step-execution.md)，请求上下文策略见
[`request-context.md`](request-context.md)，UI 通用边界见
[`ui-architecture.md`](ui-architecture.md)。

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

禁止 Provider Adapter 跨请求求和、UI 按 chunk 或消息重算、旁路 DAO 写入、第二 usage 表、fallback 累计器和旧字段转发属性。

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

`TokenUsage` 保存 turn 累计 input/output/cache/细分/total，以及：

- `peakRequestContextTokens`：本 turn 所有已关闭请求中 `inputTokens + outputTokens` 的最大值，只增不减。
- `latestRequestContextTokens`：只覆盖为最新一次请求的 canonical input；最新请求未报告时写 `null`，不能继承旧值。
- `latestRequestOutputTokens`：只覆盖为最新一次请求的 output；缺失写 `null`，不能拿 turn 累计 output 代替。
- `latestRequestCacheReadInputTokens`：只覆盖为最新一次请求的 cache read；缺失写 `null`，显式零保留为零。
- `latestRequestOutputDurationMillis`：只覆盖为最新一次已关闭请求从首个模型输出到响应流关闭的输出阶段时间；用于
  canonical 请求审计，不包含 TTFT。
- `latestRequestEstimatedContextTokens`：每次 Provider 请求发出前，对经过输入 transformer 的最终消息投影和工具 schema
  做出的稳定 token 粗估；请求一旦发出就刷新，不等待 Provider usage。
- `latestRequestTimeToFirstOutputMillis`：本 turn 最近一次实际产生首个有效模型输出的请求 TTFT；无输出请求不覆盖旧值。
- `latestRequestCacheHitPercent`：只覆盖为最新一次已关闭请求的命中率，由该请求的 cache read 与 canonical input 得出；
  缺字段写 `null`，不能继承上一请求。它与 `latestRequestContextTokens` 严格同属一次请求，因此命中率的分母始终是摘要
  中显示的那个上下文值。
- `latestRequestTokensPerSecond`：只覆盖为最新一次已关闭请求的吞吐率，由该请求的 output 与输出阶段时长得出；缺字段写
  `null`，不能继承上一请求。
- `observedProviderRequestCount`：当前 turn 中已经关闭并加入累计的 Provider 请求数。
- `observedUsageReportedRequestCount`：上述请求中至少报告一个 usage 字段的请求数。
- `providerRequestDurationMillis`：各 Provider 请求从发起到响应流关闭的墙钟时间之和，不包含工具执行和审批等待。
- `initialRequestTimeToFirstOutputMillis`：本 turn 第一次 Provider 请求发起到首个有效模型输出 chunk 的时间；只记录 ordinal 1，后续请求不累计、不覆盖。
- `successfulToolOutputCompactionBatchCount`：当前 turn 成功随 checkpoint 提交的 Tool Output 滚动裁剪批次数；一批替换多个结果仍只加一。
- `inputCompleteness`、`coreCompleteness` 与 `cacheReadCompleteness`：输入、核心量与 cache-read 各自独立的完整性。
- `semanticsVersion`：当前新记录为版本 6；缺少该字段的历史记录解释为版本 1。

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
- `peakRequestContextTokens` 对每个完整请求计算 `input + output` 后取最大值；后续请求只能提高或保持峰值。历史 turn
  已经有请求但未保存峰值时，审批续跑仍保持峰值未知，不能拿续跑后的局部请求冒充整轮峰值。
- `totalTokens` 按各请求的权威 total 求和，不在 turn 末尾用累计 input + output 重写。
- 已收口最新请求的 canonical input、output、cache read 和输出阶段 duration 一次性覆盖四个 `latestRequest*`
  审计字段；usage 没有报告的字段覆盖为 `null`，不能继承上一请求。
- 审计字段分别刷新：canonical input 在请求关闭后覆盖，估算在发送前覆盖，TTFT 在首个有效输出到达时刷新。
  latest cache rate 与 tok/s 在请求关闭且所需字段明确时刷新，缺字段写
  `null`，不继承上一请求；TTFT 表示最近一次实际产生首个有效模型输出的请求，无输出请求不覆盖旧值。任何摘要都不把缺失
  解释为零。
- `initialRequestTimeToFirstOutputMillis` 仍只记录本 turn 第一次请求，供历史/聚合审计；footer 使用每请求刷新的
  `latestRequestTimeToFirstOutputMillis`。
- 没有 usage 的失败请求仍计入 `observedProviderRequestCount`，但不增加 `observedUsageReportedRequestCount`，并使相关 turn 完整性降级。
- Provider 内容已经返回时，即使随后失败、取消或响应 incomplete，已收到的 usage 仍随原 turn 收口；取消异常继续传播。
- Google / Responses 的非流式 HTTP 成功但协议失败响应先解码可用内容和 usage，再抛出携带该快照的
  `ProviderResponseException`。`StepRunner` 先接收快照，再沿原失败链关闭请求；不执行失败响应中的工具。
  其他 `generateText` 调用者仍收到异常，不会把 partial 响应当成成功。
- `CONTINUE_USER_INTERACTION` 从原 Assistant 消息的已提交 usage 恢复，不创建第二 turn，也不重复加入 checkpoint 前的请求。
- 非空 Tool Output 滚动裁剪批次把 marker、可选 archive metadata 与 trim count 的 `+1` 放入同一个 checkpoint；计划为空、提交失败或
  提交前取消不计数，已提交消息的幂等重放也不会再次计算。
- 历史版本 1 baseline 的请求边界不可恢复；若继续运行，完整性按当前请求合并为 `PARTIAL`，不保留 legacy 特殊累计或显示路径。

## 4. 四种线协议映射

字段语义以供应商当前官方协议为准：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat)、
[OpenAI Responses](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)、
[Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)、
[Gemini GenerateContent](https://ai.google.dev/api/generate-content) 与
[Vertex GenerateContentResponse](https://cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/GenerateContentResponse)。
兼容 endpoint 只有经过 `OpenAIEndpointProfile` 识别的 vendor 才能启用其专有 cache 方言。

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

### 聊天消息底部

`ChatMessageNerdLine` 只在 owning Assistant 消息持有 usage 且摘要至少有一项时显示。
布局与活动状态见 [UI 架构](ui-architecture.md)。

第一行按关注度排列四项，分两种粒度：第一项是状态量（只能取单请求），后三项是 turn 级总结。

- `[Layers] x`：上下文。取最近一次已关闭请求的 canonical input；尚无实测值时退回该请求发送前的稳定估算，并加 `~`
  前缀。下一请求进行中，已有最近已关闭请求的实测 input 仍优先；请求关闭但未报告 input 时清空实测，
  改用最近发送前估算，不继承前一请求的实测 input。
- `[Database] y%`：缓存命中率，等于本 turn 累计 cache read ÷ 累计 input，也就是第二行 `Cached` 与 `Input` 之比。
  只有 input 和 cache-read 完整性均为 COMPLETE、input > 0 且 cache read 不超过 input 时显示。
  `latestRequestCacheHitPercent` 只作审计字段，footer 不读取。
- `[Scissors] n`：本 turn 成功随 checkpoint 提交的滚动裁剪批次数，大于零才出现，出现即以主题主色高亮，一批裁掉多个
  结果仍只计 1。
- `[Clock] t`：本 turn 端到端耗时。`turnFinished` 为假时用 `now - createdAt` 每秒刷新；`FinalizeTurn` /
  `RecoverInterruptedTurn` 冻结 `finishedAt` 后改用 `finishedAt - createdAt` 并停止刷新。它包含工具、审批和用户
  输入等待，与下面第二行的 Provider 墙钟是两个不同的口径。

第二行默认隐藏，点击第一行后展开，宽度不足时换行；各项目有值才渲染，缺失项不占位。项目语义为
`[Upload] Input · [Download] Output · [Database] Cached · [Cloud] Provider · Req · [Zap] tok/s · TTFT`：

- Input / Output / Cached 是本 turn 累计值，分别受 `inputCompleteness` / `coreCompleteness` /
  `cacheReadCompleteness` 门控，非 `COMPLETE` 不显示数值；Cached 同时是第一行命中率的分子。
- Provider 只累计 Provider 请求墙钟，不含工具执行与审批等待。
- Req 包括成功、失败和取消的已关闭请求。
- `tok/s` 与 `TTFT` 是单请求指标（速度无法累计）：`tok/s = 该请求 output / 输出阶段时间`，取最近一次已关闭请求；
  TTFT 取最近一次**实际产生首个有效模型输出**的请求，空输出请求不覆盖旧值。

共性规则：

- 全部项目都不把缺失当零；显式 cache read 零仍显示 `0.0%`。
- 新 `START` 建立 `usage=null` 的 Assistant 槽，因此全部摘要与 Tool trims 都不继承上一 turn；审批继续复用原槽。
- turn 终态后数值冻结；中间 Provider step 不具有 turn 终止权，只有 `FinalizeTurn` / `RecoverInterruptedTurn` 的
  终态提交会覆盖并冻结 `finishedAt`。
- Turn 活动状态不进入 usage footer；关闭 token 显示不隐藏活动状态。

### 上下文预警

`ChatSizeChecker` 只读取 `latestRequestEstimatedContextTokens`，表达最近一次最终 Provider 请求投影的发送前估算规模。
它不读取 turn 累计 input，也不从估值中减去 cache read。

### Stats

Stats 表示“当前数据库仍保留的 Provider usage”，不是账户终身账单：

- token 汇总包含主会话、Child conversation 和仍保留的 regenerated variants，因为它们都真实发起过请求。
- 删除会话或节点后，对应 usage 从 retained-history 汇总中消失。
- cache 只累计 cache read，不把 cache write 称为命中或节省。
- core/cache-read 非精确记录分别计数；存在 legacy、partial、none 或缺失 usage 时，以一行短说明标明统计包含旧版或不完整记录。
- cache 已知累计为零时不显示缓存卡，避免把未知误呈现为精确零。

## 9. 关键架构文件

| 边界 | 文件 |
| --- | --- |
| Canonical usage model / request snapshot | `ai/src/main/java/me/rerere/ai/core/Usage.kt` |
| 四线协议 adapter | `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt`、`ResponseAPI.kt`、`provider/providers/ClaudeProvider.kt`、`GoogleProvider.kt` |
| Request reducer / turn accumulator | `app/src/main/java/net/weero/measix/pilot/data/ai/TokenUsageAccounting.kt` |
| Provider 请求循环 | `app/src/main/java/net/weero/measix/pilot/service/turn/TurnRunner.kt`（多 Step 循环）、`StepRunner.kt`（单 Step 采样与 Provider 请求）、`TurnRunState.kt`（草稿与 checkpoint 交接） |
| Checkpoint / continue owner | `app/src/main/java/net/weero/measix/pilot/service/turn/TurnCommitter.kt` |
| 紧凑底栏与上下文预警 | `app/src/main/java/net/weero/measix/pilot/ui/components/message/ChatMessageNerdLine.kt`、`ui/pages/chat/ChatSizeChecker.kt` |
| Stats durable SQL 投影 | `app/src/main/java/net/weero/measix/pilot/data/db/dao/MessageNodeDAO.kt` |
| Stats query / UI 投影 | `app/src/main/java/net/weero/measix/pilot/service/StatsQueryService.kt`、`ui/pages/stats/StatsVM.kt`、`StatsPage.kt` |

协议映射由 Adapter 负责，累计和完整性只由 request / Turn owner 负责。修改字段时需同步
Stats SQL、序列化与消费者；测试分层和门禁见 [测试策略](testing-strategy.md)。
