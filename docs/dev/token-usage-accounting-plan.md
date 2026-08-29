# Token 用量统计架构整改方案

> 状态：代码实施与自动门禁已完成；真实 Provider 和 Android 设备验收未执行。
>
> 本文记录本次开发方案与交付边界；当前架构事实仍以代码和 `docs/references/` 为准。
> 本方案保持聊天底部与统计页现有视觉结构，不增加详情面板、命中率或第二套统计页面；Stats 仅在确有旧版/不完整
> 记录时显示一行必要的口径说明。

2026-08-29 实施结果：四线 Adapter、请求 reducer、turn accumulator、durable JSON、聊天底栏、上下文告警、Stats、
五语言和四份当前架构参考均已同步；独立终审未发现剩余实质问题。完整
`test assembleDebug lintDebug assembleRelease` 门禁通过，XML 报告汇总 1914 tests、0 failures、0 errors、11 skipped；
Android instrumentation fixture 已编译但因无连接设备未执行，在线 Provider 真实账单对照也不在本次本地门禁内。

## 1. 结论

当前 `TokenUsage` 不能同时正确表达“单次 Provider 请求的累计 usage 快照”和“一个 turn 内多次 Provider 请求的总量”。
`TokenUsage.merge()` 对正数覆盖、对零保留旧值，并重新计算 `totalTokens`，会造成多工具 step 少计、cache 旧值残留以及
Gemini tool-use 总量被覆盖。

整改采用一条唯一链路：

```text
四线 Provider adapter
    │ 解析本次请求的协议字段
    ▼
ProviderUsageSnapshot
    │ RequestUsageReducer：同一次请求内按字段覆盖
    ▼
CompletedRequestUsage
    │ TurnUsageAccumulator：不同请求只累计一次
    ▼
UIMessage.usage
    │ 现有 Turn checkpoint / terminal 唯一写协议
    ▼
ChatMessageNerdLine / ChatSizeChecker / StatsQueryService
```

这条链符合现有架构要求：

- 每个事实只有一个 owner；
- 请求内快照与跨请求累计是两个不同事实，不共用含糊的 `merge`；
- durable usage 仍只随 owning Assistant 消息由 Turn 协议提交；
- Stats 只做只读投影，不增加 DAO 或事件账本旁路；
- UI 不解释线协议，也不建立第二状态源；
- 旧运行 API、旧字段访问和旧 `merge` 在同一交付中物理删除，不保留 facade、fallback 或双读分支。

## 2. 目标与边界

### 2.1 必须实现

1. Chat Completions、OpenAI Responses、Anthropic Messages、Gemini Generate Content 各自按官方 usage 语义归一化。
2. 同一请求内重复、分段或 usage-only 流事件不重复相加。
3. 同一 turn 内因工具调用产生的多次独立 Provider 请求完整累计。
4. 明确区分字段未报告与明确报告零；不得用零充当 unknown。
5. Provider 报告的 `totalTokens` 保持权威；只有协议字段完整且 Provider 未报告 total 时才推导。
6. cache read、cache write、reasoning output、tool-use input 都是 input/output 的 breakdown，不得二次加入总量。
7. approval continuation、失败终态、取消和恢复不得重复累计已提交的请求 usage。
8. 聊天底部、上下文预警和 Stats 各自读取明确语义。
9. 保持 Room 表结构、DataStore、备份清单、版本号和现有 UI 布局不变。

### 2.2 明确不做

- 不使用本地 tokenizer 猜测 Provider 账单 token。
- 不根据消息数、字符数或模型名称补造 usage。
- 不新增 `ModelUsageEvent`、统计账本或第二张 token 表。
- 不重做聊天底部或 Stats 页面。
- 不为旧错误值进行不可验证的数值回填。
- 不把 retained-history 统计描述成 Provider 账单或账户终身消耗。

应用只对 Provider 实际返回并成功归一化的 usage 负责。Provider 未返回 usage、连接在 usage 终态前中断或兼容网关返回
未知方言时，结果必须保持 unknown/partial，不能伪装成精确零值。

## 3. 唯一 owner 与依赖方向

| 事实 | 唯一 owner | 生命周期 | 消费者 |
| --- | --- | --- | --- |
| 原始 usage JSON 的字段含义 | 对应 Provider adapter | 单个 wire 事件 | `RequestUsageReducer` |
| 当前 Provider 请求的累计快照 | `RequestUsageReducer` | 单次 Provider 调用 | `GenerationLoop` |
| 当前 turn 的累计 usage 与 Provider 请求时长 | `TurnUsageAccumulator` | 原 `TurnHandle` 内 | Turn checkpoint/terminal |
| durable turn usage | owning Assistant 的 `UIMessage.usage` | Conversation 历史 | Chat UI、query projection |
| retained-history token 汇总 | `StatsQueryService` / `MessageNodeDAO` 只读投影 | 查询期 | Stats UI |
| 最新一次请求的上下文规模 | `UIMessage.usage.latestRequestContextTokens` | durable turn summary | `ChatSizeChecker` |

`ProviderUsageSnapshot` 是 `ai` core 中公开、协议无关的只读 DTO，由 `UIMessageChunk`（或等价 Provider 输出）携带，
因为 Provider adapter 位于 `ai`、`GenerationLoop` 位于 `app`。`RequestUsageReducer`、`CompletedRequestUsage` 和
`TurnUsageAccumulator` 都位于 `app` Generation 包并保持 internal，不增加 interface/implementation 二件套、Manager、
UseCase 或应用服务。request reducer 不能写消息，turn accumulator 不能解析 Provider JSON。

`TurnEngine` 的 checkpoint/terminal 仍是唯一 durable 提交通道。streaming usage 可以随 streaming message projection 临时发布，
但只有 checkpoint 或终态事务成功后才能成为 durable 事实。

## 4. 规范数据模型

### 4.1 请求级快照

Provider adapter 输出协议无关、请求级且字段可空的值：

```kotlin
data class ProviderUsageSnapshot(
    val inputTokens: Long?,
    val contextInputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadInputTokens: Long?,
    val cacheWriteInputTokens: Long?,
    val reasoningOutputTokens: Long?,
    val toolUseInputTokens: Long?,
    val totalTokens: Long?,
    val canDeriveTotalFromInputAndOutput: Boolean,
)
```

约束：

- `null` 表示该事件或该协议没有报告该字段；`0` 表示明确报告为零。
- adapter 按本协议官方定义判断“usage 对象内省略的可选 breakdown”是否等价于零；只有协议明确保证时才能归一为零。
- 所有计数使用 `Long`。
- `cacheReadInputTokens`、`cacheWriteInputTokens` 是 input 子集。
- `reasoningOutputTokens` 是 output 子集。
- `toolUseInputTokens` 是 input 子集。
- `contextInputTokens` 表示适合对话上下文预警的请求输入；它不必等于包含 Provider 内部 tool-use prompt 的计费 input。
- adapter 只能解释自己的 wire 字段，不读取旧 `UIMessage.usage`。
- Anthropic 流的 input/output 位于互补事件中；Adapter 以 typed derivation capability 声明 canonical input + output
  可形成 total，由 request reducer 在 presence overlay 后计算，不能在 `message_start` 固化临时 output 的 total。

### 4.2 durable turn summary

现有 `TokenUsage` 改为单一、明确的 turn summary：

```kotlin
@Serializable
enum class UsageCompleteness {
    LEGACY,
    NONE,
    PARTIAL,
    COMPLETE,
}

@Serializable
data class TokenUsage(
    @SerialName("promptTokens") val inputTokens: Long? = null,
    @SerialName("completionTokens") val outputTokens: Long? = null,
    @SerialName("cachedTokens") val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val toolUseInputTokens: Long? = null,
    val totalTokens: Long? = null,
    val latestRequestContextTokens: Long? = null,
    val observedProviderRequestCount: Int? = null,
    val observedUsageReportedRequestCount: Int? = null,
    val providerRequestDurationMillis: Long? = null,
    val coreCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val cacheReadCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val semanticsVersion: Int = 1,
)
```

运行时代码只使用 `inputTokens`、`outputTokens`、`cacheReadInputTokens` 等唯一名称；不得保留 `promptTokens`、
`completionTokens`、`cachedTokens` 的 Kotlin 转发属性。`@SerialName` 只固定既有 JSON 存储键，属于持久化格式兼容，
不是第二套运行协议。

旧 JSON 缺少字段时固定解释为 `semanticsVersion=1`、两项 completeness 均为 `LEGACY`，观察请求数保持 unknown；
所有新建 usage 必须由 turn accumulator 显式写入版本 2 和真实 completeness，不能依赖默认值。

数值字段保存 Provider 已报告的已知累计；`coreCompleteness` 只评价 input/output/total 三项核心 usage：

- `COMPLETE`：该 turn 的全部实际 Provider 请求都有完整 input/output/total，核心数值是精确 turn 总量；
- `PARTIAL`：至少收到一部分核心 usage，但存在请求或核心字段未报告，核心数值只是已知下界；
- `NONE`：实际发出请求但没有收到任何可归一化核心 usage；
- `LEGACY`：版本 1 历史值，可能是单请求 usage，也可能是多 step 的最后非零混合快照。

`cacheReadCompleteness` 用同一枚举独立评价 cache-read：只有每个 Provider 请求都报告 cache read，或该精确 endpoint
profile 明确允许把省略解释为零时，cache 总量才是 `COMPLETE`。cache write、reasoning、tool-use 是可选审计 breakdown，
保持 nullable，不反向降低核心 usage 的 completeness。

这样既不清空已经收到的 usage，也不会因兼容 endpoint 缺少 cache breakdown 而隐藏精确 input/output。
`observedProviderRequestCount` 和 `observedUsageReportedRequestCount` 只统计本语义版本实际观察到的 Provider 调用与 usage；
不得用工具数或内部 HTTP retry 次数代替。新建 v2 turn 中它们覆盖整个 turn；legacy continuation 中只覆盖升级后观察到的请求，
UI/Stats 不把它们解释为旧 turn 的完整请求数。

## 5. 两级归并规则

### 5.1 同一次请求：presence-aware overlay

同一请求中的 usage 通常是累计快照，不按 chunk 求和：

- 新事件出现字段时覆盖请求快照，包括明确的 `0`；
- 新事件未出现字段时保留该请求此前值；
- 不以 `> 0` 判断字段是否存在；
- 不重新计算 Provider 已报告的 total；
- usage-only 事件必须进入 reducer，不能因无 choices/candidate 被丢弃。

这同时覆盖：

- Chat Completions 的最终 usage-only chunk；
- Responses 的 completed/incomplete/failed terminal usage；
- Claude `message_start` input 与 `message_delta` output；
- Gemini 多个累计 `usageMetadata` 流事件。

### 5.2 不同请求：finalize once then sum

每次实际 Provider 调用开始时，`GenerationLoop` 创建一个 request reducer，并分配在当前 accumulator 内单调递增的
`requestOrdinal`。所有正常、incomplete、failed、transport error、decoder error 和 cancellation 退出路径都通过结构化
`try/finally` 调用一次 `closeRequest(outcome)`。close 不吞异常或 `CancellationException`，只返回：

```kotlin
internal data class CompletedRequestUsage(
    val requestOrdinal: Int,
    val snapshot: ProviderUsageSnapshot?,
    val coreCompleteness: UsageCompleteness,
    val cacheReadCompleteness: UsageCompleteness,
    val outcome: ProviderRequestOutcome,
    val providerRequestDurationMillis: Long,
)
```

request reducer 内部只有 `OPEN/CLOSED` 一次性状态；重复 close 必须 fail-fast。`TurnUsageAccumulator.apply()` 只接受下一个
ordinal，重复或跳号同样 fail-fast，因此一次请求最多加入一次；request close 的两项 completeness 只能产生
`NONE/PARTIAL/COMPLETE`，不能产生 `LEGACY`。accumulator 随后执行：

- input、output、cache read/write、reasoning、tool-use 分别按请求求和；
- `totalTokens` 按“请求权威 total”求和，而不是在 turn 末尾重算 `input + output`；
- `latestRequestContextTokens` 覆盖为本次请求的 context input；
- 最新请求未报告 context input 时，必须覆盖为 `null`，不能继承上一请求值；
- `observedProviderRequestCount` 增加一次；有任何 usage 字段时才增加 `observedUsageReportedRequestCount`；
- `providerRequestDurationMillis` 累加每次 Provider 请求墙钟时间，包含连接、TTFT 和流传输，不包含工具执行、审批等待和 turn 间暂停；
- 分别生成 core 与 cache-read 的 `COMPLETE/PARTIAL/NONE`，同时保留已经报告的数值下界。

累计使用 checked addition，绝不回绕或截断。负数、溢出或不可能的 usage breakdown 只让受影响 usage 降为
`PARTIAL/NONE` 并记录不含原始内容的 typed diagnostic；不得因附属统计异常丢弃已经合法解析的模型内容。只有响应主体、
工具状态或协议终态损坏才按既有 typed Provider failure 结束 turn。

版本 2 continuation 以已提交的 `observedProviderRequestCount + 1` 作为下一 ordinal；版本 1 baseline 的历史请求数未知，
升级后的新增请求从 post-upgrade ordinal 1 开始，并将该观察数持久化到 `observedProviderRequestCount`。summary 的 core/cache
completeness 始终保持 `LEGACY`，不能伪造旧请求数或升级为精确值。

请求输出、usage、工具 phase 和 durable checkpoint 使用同一个 step 边界：

- checkpoint 成功后，累计值才成为下一 step 的基线；
- approval continuation 从原 `TurnHandle` 的已提交 Assistant usage 恢复 accumulator；
- 已提交 step 不会重新加入；
- 更新后继续旧 Pending Approval 时，v1 usage 作为 `LEGACY` baseline 保留，新请求可以继续累计，但整个 summary 不得静默升级为 `COMPLETE`；
- checkpoint 前进程中断时，本地没有可提交的 usage 证据；恢复只保留最后一次已提交累计，不补记未知外部计费，也不把 retained-history 统计冒充成账单；
- close 后先更新 turn-owned 最新消息投影，再让异常继续传播；失败或 incomplete 响应已经收到的 usage 随同一 terminal transaction 保存；
- 取消必须传播，不能用 `runCatching` 或 `NonCancellable` 吞掉后再补加 usage。

## 6. 四线协议映射

官方依据：

- [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat)
- [OpenAI Responses](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
- [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- [Gemini GenerateContent](https://ai.google.dev/api/generate-content)
- [Vertex GenerateContentResponse](https://cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/GenerateContentResponse)

### 6.1 Chat Completions

| 规范字段 | wire 字段 |
| --- | --- |
| input | `prompt_tokens` |
| output | `completion_tokens` |
| cache read | `prompt_tokens_details.cached_tokens`；兼容 profile 明确允许时依次读取顶层 `cached_tokens`、`prompt_cache_hit_tokens` |
| cache write | `prompt_tokens_details.cache_write_tokens`，存在时保留 |
| reasoning output | `completion_tokens_details.reasoning_tokens`，存在时保留；已包含在 output |
| total | `total_tokens` 优先；缺失且 input/output 完整时推导 |

cache 方言优先级只属于 `ChatCompletionsAPI` 已知 endpoint profile，不新增用户可配置“token 方言”，也不从 modelId 猜测。
cache read/write 已包含在 input 中，不能再次相加。

### 6.2 OpenAI Responses

| 规范字段 | wire 字段 |
| --- | --- |
| input | `input_tokens` |
| output | `output_tokens` |
| cache read | `input_tokens_details.cached_tokens` |
| cache write | `input_tokens_details.cache_write_tokens`，存在时保留 |
| reasoning output | `output_tokens_details.reasoning_tokens`，存在时保留；已包含在 output |
| total | `total_tokens` 优先；缺失且 input/output 完整时推导 |

`response.completed`、`response.incomplete` 和 `response.failed` 都必须经过同一个 usage decoder；没有 terminal usage 时保持 unknown。

### 6.3 Anthropic Messages

| 规范字段 | 公式 |
| --- | --- |
| input | `input_tokens + cache_read_input_tokens + cache_creation_input_tokens` |
| output | `output_tokens` |
| cache read | `cache_read_input_tokens` |
| cache write | `cache_creation_input_tokens` |
| total | input + output |

Anthropic 的 `input_tokens` 不等同于已包含所有缓存分类的 canonical input，因此只在该 adapter 内执行上述相加。
`message_start` 和 `message_delta` 是同一请求的互补快照，不是两个请求。

### 6.4 Gemini Generate Content

| 规范字段 | 公式 |
| --- | --- |
| input | `promptTokenCount + toolUsePromptTokenCount` |
| output | `candidatesTokenCount + thoughtsTokenCount` |
| cache read | `cachedContentTokenCount` |
| tool-use input | `toolUsePromptTokenCount`，已包含在 input |
| reasoning output | `thoughtsTokenCount`，已包含在 output |
| total | `totalTokenCount` 优先 |

`totalTokenCount` 不得再被公共代码改写。Developer API 与 Vertex 对 total/breakdown 的返回组合按实际 transport/source
profile 建 fixture；只有对应官方 profile 明确保证等式时才在测试中验证一致性。运行时遇到不一致始终保留 Provider total，
记录不含原始内容的诊断，不能 assert、fail 或静默覆盖。

四线的 context input 映射固定为：Chat `prompt_tokens`、Responses `input_tokens`、Claude canonical input、Gemini
`promptTokenCount`。Gemini 的 `toolUsePromptTokenCount` 计入本次请求 input/total，但不用于提示用户压缩持久化对话上下文。

## 7. 三个消费者的确定语义

### 7.1 聊天底部

保持 `ChatMessageNerdLine` 当前图标、顺序、间距和短文本：

- Upload：`TokenUsage.inputTokens`，表示本 turn 所有 Provider 请求的完整 input 总量；
- `(x cached)`：`cacheReadCompleteness=COMPLETE` 且 `cacheReadInputTokens > 0` 时显示；legacy 记录保持升级前行为；
- Download：`outputTokens`，表示本 turn 所有 Provider 请求的完整 output 总量；
- tok/s：`outputTokens / providerRequestDurationMillis`，表示本 turn 各 Provider 请求的平均输出吞吐；
- Clock：继续表示用户看到的整个消息墙钟时间。

Master 与 Child 各自拥有 Assistant message、turn accumulator 和 durable usage。子助手调用的 usage 只进入 Child 详情消息，
不会汇入 Master 底部；全局 Stats 才按下述 retained-history 口径同时读取两者。工具调用前后属于 Master 的两次 Provider 请求
仍应在 Master turn 内相加，因此 Child 即使因余额错误失败，Master 为读取该工具错误而发出的后续请求也会让底部 input 增长。
例如调用前累计 16.7K、失败返回后变为 33.2K，表示两次 Master input 约为 16.7K 与 16.5K，不表示把 Child usage 加入 Master。

TPS 的分母是 Provider 请求墙钟时间，包含连接、TTFT 和流传输，不称为纯模型生成速度；duration 为零、output unknown 或
新记录的 `coreCompleteness` 为 `PARTIAL/NONE` 时隐藏 TPS。

不增加 cache miss、unknown、cache-write 或详情展开 UI。版本 2 只有 `coreCompleteness=COMPLETE` 才显示精确 input/output；
core `PARTIAL/NONE` 不显示伪造总量。cache 按独立 completeness 决定是否显示，不能反向隐藏精确 input/output。
`LEGACY` 继续按升级前方式显示已有记录值，避免历史消息突然丢失原信息，但不得在文档或统计中称为精确 turn 总量。
仅将 Stats 页中文“缓存节省 Token”修正为“缓存命中输入 Token”；布局不变。

### 7.2 上下文预警

`ChatSizeChecker` 只读取 `latestRequestContextTokens`，不读取 turn 累计 input。它表达最近一次实际 Provider 请求中可归因于
对话上下文的规模，
不进行本地 tokenizer 估算，也不把 cache read 从 input 中减去。

### 7.3 Stats

Stats 定义为“当前数据库保留历史中记录到的 Provider usage”：

- 包含主会话和子助手，因为它们都真实发起 Provider 请求；
- 包含仍保留的 regenerated variants，因为每个 variant 都发生过请求；
- 删除会话或节点后相应值消失，因此不是账户终身账单；
- cache 统计只累计 cache read，不把 cache write 称为命中或节省；
- `MessageNodeDAO` 只读取 `UIMessage.usage` 的既有序列化键，不创建第二 owner。

查询同时返回 `coreNonExactRecordCount` 与 `cacheReadNonExactRecordCount`。core `COMPLETE` 使用精确 turn 总量，core
`PARTIAL` 使用已知下界；cache read 按自己的 completeness 判断精确性；旧 `semanticsVersion=1/LEGACY` 按原记录值参与
retained-history 汇总。只要核心汇总或当前显示的 cache 汇总含 `PARTIAL/NONE/LEGACY`，Stats 在现有卡片下显示一行静态说明：
“统计包含旧版或不完整记录，数值为当前已记录用量。”不拆卡片、不增加筛选和详情页。

历史多 step 请求的每次 usage 没有单独保存，无法可靠重建，禁止猜测回填。该说明是历史真实性约束，不是第二统计口径。

## 8. 持久化与兼容

- Room 表和 schema version 不变；usage 仍在消息 JSON 中。
- `@SerialName` 保留 `promptTokens`、`completionTokens`、`cachedTokens` 既有 JSON 键，备份与旧消息可继续读取。
- 新字段均提供合法默认值；旧记录缺少 `semanticsVersion` 时解释为版本 1。
- `TokenUsageConverter`、`UIMessage` JSON、Conversation 导入导出和备份恢复必须增加新旧 round-trip 测试。
- 运行时代码一次性迁移到新属性名并删除旧属性；不得保留 deprecated getter、typealias 或双字段写入。
- 不修改 `versionCode`、`versionName` 或 `docs/dev/changelog.md`。

## 9. 实施切片

### 9.1 AI 协议层

1. 新增 `ProviderUsageSnapshot` 和请求内 presence-aware reducer。
2. 重塑 `TokenUsage` 为 turn summary，删除全局 `TokenUsage?.merge()`。
3. 四个 Provider adapter 改为输出规范快照。
4. 每条线同时增加真实响应 fixture 与字段级断言。

### 9.2 Generation/Turn

1. `GenerationLoop.generateInternal()` 为每次 Provider 调用创建独立 request reducer。
2. 请求终态只 finalize 一次并加入 turn accumulator。
3. streaming projection 使用“已提交 turn aggregate + 当前请求快照”的临时视图，不能把预览提前落库。
4. tool step checkpoint、approval continuation、失败/取消/恢复共用同一累计协议。
5. 使用单调时钟记录 Provider 请求墙钟时间；工具执行和审批不进入 tok/s 分母。

### 9.3 消费者与查询

1. `ChatMessageNerdLine` 只替换字段来源和 TPS 分母，不重做布局。
2. `ChatSizeChecker` 改读 latest-request context input。
3. `MessageNodeDAO` / `StatsQueryService` 继续只读消息 usage，分别返回 core/cache-read non-exact 记录数并增加新语义与 legacy fixture。
4. 同步五语言中不准确的 cache 名称；只新增 Stats 必要的单行非精确口径说明。

### 9.4 文档

实施时必须同步：

- `docs/references/protocol-reference.md`：四线字段表、unknown/zero、cache read/write、total 权威规则；
- `docs/references/chat-generation-pipeline.md`：request reducer、turn accumulator、checkpoint/恢复语义；
- `docs/references/application-architecture.md`：usage owner 表与 durable 写边界；
- `docs/references/ui-architecture.md`：现有底部字段和 Stats retained-history 范围。

参考文档只记录最终实现，不保留迁移阶段名称、旧 `merge` 或双路径描述。

## 10. 测试矩阵

### 10.1 Provider adapter

- Chat non-stream、最终 usage-only stream、三种已知 cache-read 方言优先级、cache write、reasoning breakdown；
- Responses completed/incomplete/failed、stream/non-stream、cache read/write；
- Claude `message_start + message_delta`、cache read/create、明确零；
- Gemini usage-only SSE、`toolUsePromptTokenCount`、thoughts、Developer/Vertex profile 的官方 total 保留；
- 四线均覆盖字段 absent 与 explicit zero；
- 超过 `Int` 范围的 `Long` round-trip。

### 10.2 reducer 与 turn

- 同请求重复累计快照不重复相加；
- 后续快照明确 cache=0 能覆盖当前请求正值；
- 两个独立请求的 input/output/cache 分别求和；
- Master 与 Child 使用独立 accumulator；Child 无 usage 的 Provider 失败不改变 Master，而 Master 读取失败工具结果的下一请求继续计入自己的 turn；
- 任一请求缺核心字段时保留已知下界并标记 core `PARTIAL`；只缺 cache breakdown 时 core 仍可 `COMPLETE`，cache-read 单独降级；
- 多工具 step、并行工具、无工具单请求；
- approval continuation 不重复加入 checkpoint 前 usage；旧 v1 Pending Approval 续跑保持 `LEGACY` baseline；
- incomplete/failed 携带 usage 时保存；无 usage 时不造零；
- normal/error/cancellation 的 close-once、取消传播、checkpoint 失败、终态重试和进程恢复幂等；
- provider request duration 包含连接/TTFT/流传输，不包含工具和审批等待。

### 10.3 持久化、Stats 与 UI

- `semanticsVersion=1/2` JSON 与 `TokenUsageConverter` round-trip；
- 旧备份恢复和新备份导入；
- Stats 包含 retained variants 与 child conversation，删除后按定义变化，并按 core/cache-read 两种 completeness 显示单行说明；
- Chat bottom 仍保持现有项目顺序，cache 仅正命中显示；
- ChatSizeChecker 使用 latest-request context input；
- 五语言 key 完整性和架构静态契约。

## 11. 验证门禁

定向验证完成后运行：

```powershell
gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
git diff --check
```

真实验收至少覆盖：

1. OpenAI Chat Completions 单请求与工具续轮；
2. OpenAI Responses 单请求与工具续轮；
3. Anthropic cache read 与 cache creation；
4. Gemini function calling，确认 `toolUsePromptTokenCount` 和 provider total；
5. Android 聊天底部、Stats、重生成、审批继续和进程恢复。

JVM/build 通过不能表述为真实 Provider 或设备验收通过。某条线无法取得真实凭据时，该线只能标记自动契约通过，
不能标记在线验收完成。

## 12. 完成判据

只有同时满足以下条件，整改才可声明开发完成：

- 全仓库不存在旧 `TokenUsage.merge`、旧 Kotlin 属性名或第二 usage 累计路径；
- 四线 adapter 的官方字段映射均有 fixture 和明确零/缺失测试；
- 单请求快照与 turn 多请求累计分别只有一个 reducer；
- 所有 Provider 调用退出路径 close-once，旧 Pending Approval 不会把 legacy baseline 升级为 complete；
- Provider total 不被公共层覆盖，Gemini tool-use 不再少计；
- cache read 不残留上一请求值，cache write 不丢失且不重复加入 input；
- Chat bottom、ChatSizeChecker、Stats 使用各自冻结语义；
- Room/DataStore/备份兼容测试通过，旧数据没有被猜测改写；
- 相关 `docs/references/` 与代码一致；
- 风险匹配的完整 Gradle 门禁、最终 diff 和工作树检查通过；
- 真实 Provider/设备验收状态单独、准确记录。
