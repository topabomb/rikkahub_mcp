# Token 用量统计架构

本文档是 Token 用量语义、所有权、累计、持久化和展示口径的当前权威参考。Provider 的其他请求与回放规则见
[`protocol-reference.md`](protocol-reference.md)，会话提交协议见
[`chat-generation-pipeline.md`](chat-generation-pipeline.md)，UI 通用边界见
[`ui-architecture.md`](ui-architecture.md)。

## 1. 事实层级与唯一所有者

```text
Provider wire usage event
  → ProviderUsageSnapshot          单请求、协议无关快照
  → RequestUsageReducer            单请求 presence overlay 与 close-once
  → CompletedRequestUsage          已关闭请求事实
  → TurnUsageAccumulator           owning Assistant turn 内按 ordinal 累计
  → UIMessage.usage: TokenUsage    唯一 durable 结果
  → Nerd line / ChatSizeChecker / Stats query
```

所有权固定如下：

- 各线协议 Adapter 只解释一次 Provider 请求的 wire usage，不读取历史消息，也不跨请求累计。
- `RequestUsageReducer` 是单请求快照合并和完整性判定的唯一 owner。
- `TurnUsageAccumulator` 是一个 Assistant turn 内多次 Provider 请求累计的唯一 owner。
- `UIMessage.usage` 是 durable usage 的唯一事实；它随 owning Assistant 消息通过既有 checkpoint 或终态事务提交。
- UI 只显示投影，`MessageNodeDAO` / `StatsQueryService` 只查询 durable JSON；二者都不重新计算 usage，也不建立账本。

禁止 Provider Adapter 跨请求求和、UI 按 chunk 或消息重算、旁路 DAO 写入、第二 usage 表、fallback 累计器和旧字段转发属性。

## 2. 规范数据模型

`ProviderUsageSnapshot` 的所有数值字段都是 nullable `Long`：

| 字段 | 含义 |
| --- | --- |
| `inputTokens` | 单请求完整输入，也是最新请求 Context 的唯一来源；cache read/write 与 tool-use 是其子集，不得再次相加 |
| `outputTokens` | 单请求完整输出；reasoning 是其子集 |
| `cacheReadInputTokens` | Provider 明确报告的缓存读取输入；缺失不等于零 |
| `cacheWriteInputTokens` | Provider 明确报告的缓存写入输入；缺失不等于零 |
| `reasoningOutputTokens` | 输出中的 reasoning 子集 |
| `toolUseInputTokens` | 输入中的 tool-use 子集 |
| `totalTokens` | Provider 权威总量，或由 Adapter 明确授权后在单请求边界安全推导 |
| `canDeriveTotalFromInputAndOutput` | 瞬态 Adapter 策略，不进入 durable `TokenUsage` |

`TokenUsage` 保存 turn 累计 input/output/cache/细分/total，以及：

- `latestRequestContextTokens`：只覆盖为最新一次请求的 canonical input；最新请求未报告时写 `null`，不能继承旧值。
- `latestRequestCacheReadInputTokens`：只覆盖为最新一次请求的 cache read；缺失写 `null`，显式零保留为零。
- `observedProviderRequestCount`：版本 2 新 turn 中已经关闭并加入该 turn 的 Provider 请求数。
- `observedUsageReportedRequestCount`：上述请求中至少报告一个 usage 字段的请求数。
- `providerRequestDurationMillis`：版本 2 新 turn 中各 Provider 请求从发起到响应流关闭的墙钟时间之和，不包含工具执行和审批等待。
- `initialRequestTimeToFirstOutputMillis`：本 turn 第一次 Provider 请求发起到首个有效模型输出 chunk 的时间；只记录 ordinal 1，后续请求不累计、不覆盖。
- `coreCompleteness` 与 `cacheReadCompleteness`：核心量与 cache-read 各自独立的完整性。
- `semanticsVersion`：当前新记录为版本 2；缺少该字段的历史记录解释为版本 1。

所有加法使用 checked `Long`。负值、子集大于父项、可验证的 total 不一致或溢出不能被修成看似精确的数字；保留仍可证明的字段，记录 typed diagnostic，并只降低受影响的完整性。Provider 已报告的 total 不被公共层覆盖。

## 3. 单请求与 Turn 累计算法

每次真实 Provider 调用创建一个新 `RequestUsageReducer` 和连续 request ordinal：

1. 流式事件按字段 presence 覆盖当前请求快照；字段缺失表示本事件不更新该字段，显式 `0` 必须覆盖旧值。
   携带 usage 的事件即使没有 choices/candidates 也必须进入 reducer，包括 Chat 最终 usage-only chunk、Responses
   completed/incomplete/failed terminal event 和 Gemini usage-only event。
2. 流式 UI 可以读取“已累计 turn + 当前请求预览”，但预览不是 durable fact。紧凑栏的 Context/Cache 成对保留上一条
   已收口请求，当前请求关闭后再原子切换；没有上一条已收口请求时保持 unknown，不能用流式中间态的临时零覆盖。
3. 正常、失败和取消都在 Provider 调用的 `finally` 路径关闭请求；一个 reducer 只能关闭一次。
   `GenerationLoop.run()` 的 `channelFlow` 使用 rendezvous 容量：允许 Transformer 子协程安全发布；当 Provider 抛出失败或
   取消且下游仍 active 时，带最终 usage 的消息投影被下游接收后才传播原异常，不能让缓冲在异常收口时丢弃已观测 usage。
   若 collector/turn 已从外部取消，则不承诺最终 UI 投影交付，取消仍立即传播。
4. `TurnUsageAccumulator.apply()` 只接受下一个连续 ordinal，因此一次请求最多累计一次，重复或跳号立即失败。
5. checkpoint 成功后，该 turn 累计值才成为后续 step 或审批继续的 durable baseline。

turn 聚合规则：

- input、output、cache read/write、reasoning、tool-use 和 Provider request duration 分别按请求求和。
- `totalTokens` 按各请求的权威 total 求和，不在 turn 末尾用累计 input + output 重写。
- 已收口最新请求的 canonical input/cache read 分别覆盖 `latestRequestContextTokens` 与
  `latestRequestCacheReadInputTokens`；没有报告时覆盖为 `null`。进行中的 request preview 保留上一条已收口请求的这对字段。
- TTFT 从第一次请求发起计时；空协议事件和 usage-only 事件不算，Text、Reasoning、Tool 或媒体 payload 首次出现时冻结。
- 没有 usage 的失败请求仍计入 `observedProviderRequestCount`，但不增加 `observedUsageReportedRequestCount`，并使相关 turn 完整性降级。
- Provider 内容已经返回时，即使随后失败、取消或响应 incomplete，已收到的 usage 仍随原 turn 收口；取消异常继续传播。
- `CONTINUE_APPROVAL` 从原 Assistant 消息的已提交 usage 恢复，不创建第二 turn，也不重复加入 checkpoint 前的请求。
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
| Gemini generateContent | `promptTokenCount + toolUsePromptTokenCount` | `candidatesTokenCount + thoughtsTokenCount` | `cachedContentTokenCount` / 不提供 | Provider `totalTokenCount` |

`response.completed`、`response.incomplete` 和 `response.failed` 使用同一个 Responses usage decoder。Anthropic 的
`message_start` 与 `message_delta` 是同一请求的互补快照，不是两个请求。Gemini 的 tool-use 和 thoughts 已分别包含在
canonical input/output 中，不能再次加入 total；其 Provider total 保持权威。

## 5. 完整性语义

`UsageCompleteness` 的含义：

| 值 | 含义 |
| --- | --- |
| `COMPLETE` | 版本 2 中该维度的每个已观察请求均有完整、有效数据 |
| `PARTIAL` | 至少保留一个已知值，但存在缺失、无效或溢出的请求 |
| `NONE` | 当前已观察请求均没有该维度的可用值 |
| `LEGACY` | 历史记录没有足够证据恢复请求边界或完整性 |

单请求 core 需要有效的 input、output 和 total 才是 `COMPLETE`；cache-read 是否完整独立判断。turn 只有在 baseline 与新请求均为 `COMPLETE` 时才能保持 `COMPLETE`；其他组合按已知值归为 `PARTIAL` 或 `NONE`。响应成功与 usage 完整是两件事，不能用 HTTP/流终态推断缺失字段为零。

## 6. Master 与 Child 隔离

usage owner 是实际发起请求的 Assistant 消息。Master 与每个 Child 使用独立的 `ConversationRuntime`、`TurnEngine` 和
`GenerationLoop.run()` 局部 accumulator：

- Target usage 只写 Child Assistant 消息，并在子助手详情中显示。
- 子助手工具结果只向 Master 返回文本和附件投影，不复制 Child 的 message usage。
- Child Provider 失败且未返回 usage 时，只影响 Child 自己的完整性，不改变 Master usage。
- Master 在工具调用前后通常各发起一次自己的 Provider 请求；后一次用于读取工具成功或失败结果，因此两次 Master input 都应计入同一个 Master turn。

例如 Master 第一次请求约 16.7K input，工具返回后第二次请求约 16.5K，则默认 Context 显示最新请求约 16.5K，展开后的
turn input 显示约 33.2K。即使 Child 因余额不足未产生 usage，33.2K 仍是两次 Master 请求之和，不是 Child 串账。

## 7. 持久化与兼容

usage 仍位于 `UIMessage` JSON 中，不新增 Room 表或 schema migration。`TokenUsage` 的 Kotlin 属性只使用
`inputTokens`、`outputTokens`、`cacheReadInputTokens` 等规范名称；以下 `@SerialName` 仅固定既有存储键：

| Kotlin 属性 | 既有 JSON key |
| --- | --- |
| `inputTokens` | `promptTokens` |
| `outputTokens` | `completionTokens` |
| `cacheReadInputTokens` | `cachedTokens` |

新增 usage 字段均为 nullable 且默认 `null`。旧 JSON、备份与历史消息不迁移、不重写、不猜测回填；缺失字段直接保持
unknown。旧记录可以继续解码，但 UI 不为 `LEGACY` 建立特殊显示或计算旁路。Stats 仍按数据库实际保存的历史值查询。

## 8. 消费者口径

### 聊天消息底部

`ChatMessageNerdLine` 是低对比度、无容器背景的紧凑 footer：

- 默认单行固定显示 `Context x · Cache y% · Req n`。Context 与 Cache 同属最新请求，Req 是 owning Assistant turn 的请求数。
- Cache 百分比为 `latestRequestCacheReadInputTokens / latestRequestContextTokens`；分母必须大于零且 cache 不得超过 Context。
  缺失显示 unknown，显式零显示 `0%`；cache write 不进入分子。显示精度按原始比例确定：低于 90% 为整数，
  90% 至低于 99% 保留一位小数，99% 及以上保留两位小数；仅格式化显示，不改变原始计数和比值。
- 点击后最多补充两个自适应信息组：turn 累计 input/output/cache read，以及 tok/s、初始 TTFT、整条消息总耗时。
- turn input/output 只有 core `COMPLETE` 时显示；turn cache 只有 cache-read `COMPLETE` 时显示。`LEGACY` 没有显示特例。
- TPS 按累计 output 除以累计 Provider request duration；Clock 使用消息 `createdAt` 到 `finishedAt`，包含工具和审批等待。
- 活动动效与等待审批继续由 `ChatList` 原有独立状态行显示；归属、28dp loading、主题主色、容器、审批标签和判断条件
  均不改变，只收紧该行的上下留白。它不进入 usage footer，关闭 token 显示也不会隐藏 turn 活动状态。

### 上下文预警

`ChatSizeChecker` 只读取 `latestRequestContextTokens`，表达最近一次 Provider 请求的上下文规模。它不读取 turn 累计 input，
不使用本地 tokenizer 猜测，也不从 input 中减去 cache read。

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
| Provider 请求循环 | `app/src/main/java/net/weero/measix/pilot/data/ai/GenerationLoop.kt` |
| Checkpoint / continue owner | `app/src/main/java/net/weero/measix/pilot/service/runtime/TurnEngine.kt` |
| 紧凑底栏与上下文预警 | `app/src/main/java/net/weero/measix/pilot/ui/components/message/ChatMessageNerdLine.kt`、`ui/pages/chat/ChatSizeChecker.kt` |
| Stats durable SQL 投影 | `app/src/main/java/net/weero/measix/pilot/data/db/dao/MessageNodeDAO.kt` |
| Stats query / UI 投影 | `app/src/main/java/net/weero/measix/pilot/service/StatsQueryService.kt`、`ui/pages/stats/StatsVM.kt`、`StatsPage.kt` |

## 10. 变更与验证规则

新增或修改 usage 字段时必须：

1. 在对应线协议 Adapter 中完成 wire 映射，不向公共层加入 host/model 猜测。
2. 更新 request reducer、turn accumulator 或消费者前，确认没有形成第二 owner。
3. 覆盖字段缺失与显式零、流式互补快照、Long 边界、失败/取消、工具多 step、审批继续和 Master/Child 隔离。
4. 保持新旧 JSON round-trip、Stats SQL 与 Room fixture 一致。
5. 同步本文档以及真正受影响的协议、生成或 UI 参考。
