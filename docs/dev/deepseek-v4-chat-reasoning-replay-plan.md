# DeepSeek V4 Chat reasoning 回放与请求体所有权整改方案

> 状态：已实施，本地全量门禁已通过；真实 Provider 验收尚未执行。
>
> 本文是 DeepSeek V4 Flash/Pro 在 OpenAI Chat Completions 工具模式下的
> `reasoning_content` 回放修复、终态历史回放收口和自定义请求体所有权整改的实施依据。
> 当前实现事实以代码与 `docs/references/` 为准；本文同时记录已落地的设计决策、测试边界和
> 尚未执行的真实 Provider 验收，不再作为与代码并行的第二套协议事实。

## 1. 背景

### 1.1 故障现象

用户通过 B.AI 的 OpenAI-compatible Chat Completions 端点调用 `deepseek-v4-flash`，
在 thinking mode 与工具同时启用的会话中，后续请求可能返回：

```text
The `reasoning_content` in the thinking mode must be passed back to the API.
```

Provider 的 HTTP 400 最终被归类为无效请求，并使当前 turn 进入失败终态。Turn 的终态提交、
错误展示和持久化链路只是正确记录了 Provider 拒绝；它们不是错误根因。

### 1.2 外部协议要求

DeepSeek Thinking Mode 的 Chat Completions 契约区分两种请求：

- 请求不携带顶层 `tools`：历史 `reasoning_content` 不要求回传，即使回传也不进入上下文；
- 请求携带顶层 `tools`：所有先前 assistant turn 的 `reasoning_content` 都必须完整回传，
  包括该 assistant turn 本身没有 `tool_calls` 的情况；缺失时服务端返回 400。

参考：

- [DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)
- [B.AI DeepSeek Harness integration guide](https://docs.b.ai/llmservice/deepseek-harness/integration-guide/)

B.AI 使用 `https://api.b.ai/v1/chat/completions` 和 `openai-completions`，并提供精确模型 ID
`deepseek-v4-flash`。因此 B.AI 是兼容网关，不是第五条 Provider 线，也没有证据表明它拥有独立
reasoning 方言。该模型应通过 Chat Completions 线和 `ModelRegistry.DEEPSEEK_V4` 的模型契约
进入 DeepSeek V4 replay policy，禁止添加 `BAI` endpoint vendor 或 host 特判。

### 1.3 当前实现为什么失败

当前 `ChatCompletionsAPI.buildChatCompletionRequest()` 分别计算消息回放和顶层工具字段：

1. `buildMessages()` 接收 `includeHistoryReasoning` 与
   `requiresToolReasoningReplay` 两个布尔量；
2. `addAssistantMessages()` 只在即将绑定 `tool_calls` 的 assistant envelope 上，用
   `includeHistoryReasoning || requiresToolReasoningReplay` 强制发送 reasoning；
3. 工具后的最终 assistant reasoning，以及更早没有 tool call 的 assistant reasoning，仍然只受
   `includeHistoryReasoning` 控制；
4. 顶层 `tools` 在请求体的另一处分支中生成；
5. `mergeCustomBody()` 最后执行，允许再次替换 `messages`、`tools` 或其他核心字段。

因此，当用户关闭“包含历史思考”但当前请求仍携带工具时，序列化结果可能是：

```text
assistant(reasoning + tool_calls) -> 保留 reasoning
tool(result)                     -> 保留
assistant(final reasoning+text) -> 删除 reasoning，只保留 text
next request(tools present)      -> DeepSeek V4 拒绝并返回 400
```

现有 `ChatCompletionsAPIMessageTest` 还明确断言上述 final reasoning 应被删除，
`docs/references/protocol-reference.md` 也把规则描述成“仅工具步骤强制回放”。二者共同固化了旧假设，
必须随代码修复一起改正。

### 1.4 暴露出的架构问题

直接缺陷位于 OpenAI Chat Completions 线，不代表四条标准协议线都偏离规范。但它暴露了三个共性问题：

1. 用户偏好 `includeHistoryReasoning` 与 Provider 强制要求的 continuation state 没有类型化分离；
2. `messages`、`tools` 的线协议 owner 可以被通用 `customBody` 覆盖，最终请求不存在唯一构造者；
3. 非成功 Assistant 的回放按整条消息删除 reasoning，却可能保留已经完成的 tool call/result，
   破坏了同一 Provider step 的原子性。

## 2. 结论与范围

### 2.1 总体结论

本次采用“局部修复、统一原则、各线独立 owner”的方案：

- 直接修改 OpenAI Chat Completions 的 reasoning replay policy；
- 四条协议线遵守同一条原则：Provider 要求的不透明或 reasoning continuation state 是 mandatory
  protocol state，不能被普通用户偏好关闭；
- 不为 Chat、Responses、Claude、Gemini 强行创建一个共享 wire-state 接口；
- `customBody` 收敛成高级扩展参数入口，不再拥有结构性协议字段；
- 非成功历史以“完整 Provider step”为最小回放单位；
- 保持 Room、DataStore、消息 JSON 和备份结构兼容，不做数据库 migration。

### 2.2 目标

1. B.AI `deepseek-v4-flash` 在请求携带工具时完整回传所有可回放的历史 assistant reasoning。
2. DeepSeek V4 的 mandatory replay 不受 `includeHistoryReasoning` 开关影响。
3. 不把 DeepSeek V4 规则扩散到 B.AI 上的其他模型、通用兼容模型、官方 OpenAI、MiMo 或 OpenRouter。
4. 请求体的结构性字段只有 Provider builder 一个 owner。
5. 已完成的 reasoning/content/tool call/tool result 作为完整 step 原子回放；未完成尾部不伪装成正常 assistant turn。
6. 正常旧会话、旧 Settings 和备份无需迁移即可继续读取。
7. 同步修正静态契约测试、协议参考和配置参考。

### 2.3 非目标

- 不新增 B.AI Provider 类型、endpoint vendor 或设置项。
- 不修改 Responses、Claude、Gemini 的既有 wire format。
- 不统一不同协议的不透明状态数据结构。
- 不启用服务端会话、`previous_response_id` 或新的持久化状态源。
- 不改变工具注册、审批、执行顺序或 Tool execution durable 状态机。
- 不修改 `versionCode`、`versionName` 或发布 changelog。
- 不用空字符串或推测内容补造缺失的 reasoning。

## 3. 不可破坏的不变量

1. Provider adapter 是最终线协议唯一 owner；Transformer、Settings UI 和 custom body 不得重建第二份
   `messages`、`tools` 或 continuation state。
2. `includeHistoryReasoning` 只控制可选的可见历史思考，不控制 Provider mandatory state。
3. mandatory replay 的触发条件来自本次最终由 builder 拥有的请求结构，而不是历史中是否出现工具。
4. B.AI host 不提供模型协议证据；兼容网关上的 DeepSeek V4 只由明确的模型 family 契约识别。
5. `UIMessagePart.Reasoning` 继续作为可见 reasoning 文本的持久化事实；不创建第二份 DeepSeek 专用历史表。
6. OpenRouter `reasoning_details`、Claude signature/redacted thinking、Gemini `thoughtSignature`、
   Responses output items 继续遵守各自的 source/profile 隔离规则。
7. terminal diagnostic 只用于 UI 和持久化诊断，不直接发给 Provider。
8. 取消继续传播；本方案不得新增 `runCatching` 吞掉 `CancellationException` 或扩大 `NonCancellable`。

## 4. Chat reasoning replay policy

### 4.1 用类型替换旧布尔量

删除 `requiresToolReasoningReplay: Boolean` 及同语义函数，改为 Chat 线私有的有效策略：

```kotlin
internal enum class VisibleReasoningReplay {
    NONE,
    TOOL_ASSISTANT_ENVELOPES,
    ALL_ASSISTANT_ENVELOPES,
}

internal enum class OpaqueReasoningReplay {
    NONE,
    OPENROUTER_SOURCE_MATCHED,
}

internal enum class TerminalAssistantReplay {
    COMPATIBLE_PARTIAL,
    COMPLETE_STEP_PREFIX,
}

internal data class ChatReasoningReplayPolicy(
    val visible: VisibleReasoningReplay,
    val opaque: OpaqueReasoningReplay,
    val terminalAssistant: TerminalAssistantReplay,
)
```

三个维度彼此正交：

- visible reasoning text 的范围；
- source-isolated opaque details 的范围；
- terminal Assistant 是兼容保留 partial text，还是只回放完整 step 前缀。

禁止把三者重新压回一个 Boolean，也禁止用 `isDeepSeek`、`isOpenRouter` 等散落分支在 serializer 内重复判断。

### 4.2 唯一策略解析器

在 OpenAI endpoint/profile 领域提供一个 Chat 私有解析器，输入仅包括：

```text
endpointVendor
modelId
requestHasTools
includeHistoryReasoning
```

输出唯一的 `ChatReasoningReplayPolicy`。建议将类型与解析器放在
`OpenAIEndpointProfile.kt`，因为该文件已经拥有 endpoint vendor 与已知 profile 的内部判定；
`ChatCompletionsAPI` 只消费结果，不再自行组合条件。

有效策略冻结如下：

| Endpoint/model | requestHasTools | includeHistoryReasoning | visible reasoning | opaque state | terminal Assistant |
|---|---:|---:|---|---|---|
| 官方 OpenAI Chat | 任意 | 任意 | `NONE` | `NONE` | `COMPATIBLE_PARTIAL` |
| DeepSeek 官方端点 | 是 | 任意 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPLETE_STEP_PREFIX` |
| DeepSeek V4 on compatible gateway | 是 | 任意 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPLETE_STEP_PREFIX` |
| DeepSeek/DeepSeek V4 | 否 | 开 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| DeepSeek/DeepSeek V4 | 否 | 关 | `NONE` | `NONE` | `COMPATIBLE_PARTIAL` |
| MiMo 官方端点 | 是 | 任意 | `TOOL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| MiMo 官方端点 | 否 | 开 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| OpenRouter | 任意 | 开 | `ALL_ASSISTANT_ENVELOPES` | `OPENROUTER_SOURCE_MATCHED` | `COMPATIBLE_PARTIAL` |
| OpenRouter | 任意 | 关 | `NONE`；工具 envelope 不降级发送 visible text | `OPENROUTER_SOURCE_MATCHED` | `COMPATIBLE_PARTIAL` |
| 其他 compatible Chat | 任意 | 开 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| 其他 compatible Chat | 任意 | 关 | `NONE` | `NONE` | `COMPATIBLE_PARTIAL` |

说明：

- DeepSeek V4 compatible gateway 使用 `ModelRegistry.DEEPSEEK_V4`，包括明确登记并通过测试的规范 ID/别名；
  不在 serializer 中新增 `contains("deepseek-v4")`。
- B.AI `deepseek-v4-flash` 走上表的 compatible gateway + DeepSeek V4 行。
- MiMo 维持现有“工具 envelope reasoning 必须回放”的范围，不借本次修改扩大成全部历史。
- OpenRouter 优先回放同来源的 `reasoning_details`；存在 details 时不能同时降级发送
  `reasoning_content`，其他 host 不能消费该 metadata。
- 官方 OpenAI Chat 继续过滤第三方 `reasoning_content` 扩展。

### 4.3 `requestHasTools` 的唯一来源

每个 Provider step 在工具动态解析完成后得到 `toolsInternal`。Chat request builder 使用同一个局部事实：

```kotlin
val requestHasTools =
    params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
```

这个值必须同时控制：

1. 是否写入顶层 `tools`；
2. DeepSeek/MiMo mandatory replay policy；
3. 对应请求体测试的预期。

禁止在 `buildMessages()` 和请求顶层分别重算。`tool_choice = none` 仍不改变 `requestHasTools`，
因为请求在线协议上仍携带 `tools` 参数。

### 4.4 “所有历史 assistant”的精确定义

`ALL_ASSISTANT_ENVELOPES` 的范围是：

> 经过上下文裁剪、输入 Transformer 和最后的 `replaySafeProjection()` 后，最终进入本次 Chat
> `messages` 数组的每个完整 assistant envelope，只要保存了非空 Reasoning，就写出
> `reasoning_content`，不论该 envelope 是否包含 `tool_calls`。

因此：

- 包含更早用户轮次中的普通 assistant reasoning；
- 包含每个工具步骤的 reasoning；
- 包含工具执行后的成功最终回答 reasoning；
- 不包含被上下文裁剪掉的消息；
- 不包含 terminal message 的未完成尾部；
- reasoning 原本不存在时不制造空字段。

## 5. `customBody` 所有权整改

### 5.1 最终裁决

`customBody` 保留“高级请求参数覆盖”能力，但只允许扩展或覆盖非结构字段。
由 Provider builder 拥有的身份、消息、工具、续轮和传输生命周期字段是保留字段；
发生冲突时必须在发起 HTTP 前返回明确的本地配置错误。

不能采用以下行为：

- custom body 最后覆盖结构字段；
- builder 静默覆盖 custom body 而不提示；
- 仅对新配置限制、旧配置继续覆盖；
- 只在 DeepSeek/B.AI 上限制，其他协议保持双 owner。

### 5.2 字段分类

文本协议的最小保留集合如下；实现时由各 builder 声明自身集合，不建立包含所有 Provider 字段的全局巨型表。

| 请求线 | builder-owned 保留字段 |
|---|---|
| OpenAI Chat Completions | `model`, `messages`, `tools`, `stream`, `stream_options`, `session_id` |
| OpenAI Responses | `model`, `input`, `tools`, `stream`, `store`, `include`, `previous_response_id`, `session_id` |
| Anthropic Messages | `model`, `messages`, `system`, `tools`, `stream` |
| Gemini Generate Content | `contents`, `systemInstruction`, `tools`；URL/path 中的 model 仍由 adapter 拥有 |

允许 custom body 继续配置的典型字段：

- `temperature`、`top_p`、token 上限；
- `reasoning_effort`、`thinking` 等模型控制参数；
- `response_format`、`tool_choice`、`parallel_tool_calls`；
- 路由、缓存、实验开关等 Provider 扩展字段。

若后续证明某个字段会改变 parser 模式、不透明状态形状或 durable continuation 语义，
必须把它加入对应 builder 的保留集合，并同步测试和参考文档，不能在调用点临时特判。

### 5.3 合并 API

将通用合并函数改成必须显式传入所有权契约的形式，例如：

```kotlin
data class RequestBodyOwnership(
    val protocol: String,
    val reservedKeys: Set<String>,
    val reservedPaths: Set<List<String>> = emptySet(),
)

fun JsonObject.mergeCustomBody(
    bodies: List<CustomBody>,
    ownership: RequestBodyOwnership,
): JsonObject
```

要求：

- 删除无 ownership 参数的旧 overload；
- 所有现有调用点一次性迁移，不保留默认空集合逃生口；
- 合并前一次性收集全部冲突 key，保证诊断稳定且顺序确定；
- 继续保留现有非保留字段的递归 object merge 和简单值覆盖语义；
- builder 已建立的 object shape 只能递归扩展/覆盖 leaf，不能被 scalar/array 整体替换；
- 冲突抛出 typed 本地异常，稳定 reason 为 `custom_body_reserved_key`，detail 列出协议与排序后的 key；
- 异常在 Provider 请求发出前产生，不能记录成远端 400。

### 5.4 既有配置兼容

- `CustomBody`、Assistant、Model、Settings、备份 JSON 结构不变；不做 DataStore/Room migration。
- 既有配置无保留字段冲突时行为完全不变。
- 既有配置存在冲突时仍可加载和编辑，但请求会 fail-fast，并明确指出需要删除的字段。
- Assistant 级 custom body 可随默认模型或运行时覆盖切换协议，因此配置 UI 不使用跨协议保留键并集制造假错误；
  当前协议 request builder 是唯一权威校验点，并返回 typed、稳定的本地诊断。
- 不自动删除、改名或迁移用户的 custom body；静默修复会使保存内容与实际请求不一致。

## 6. 非成功历史的原子回放

### 6.1 当前问题

`UIMessage` 可在同一用户 turn 中承载多个：

```text
assistant reasoning/content -> tool call -> tool result
assistant reasoning/content -> tool call -> tool result
assistant reasoning/content
```

当前 `replaySafeProjection()` 只看整条 Assistant 是否有 `terminalStatus`：一旦非成功，就删除全部
Reasoning，但仍可能保留已执行且有安全 envelope 的 Tool。对 DeepSeek V4 来说，这会把原本完整的
provider step 改造成缺少 mandatory reasoning 的不合法历史。

### 6.2 完整 step 的回放证据

本投影只判断 Provider wire 是否具备可回放的 call/result 配对，不判断工具的实时执行状态：

1. `Tool.output` 非空只表示已保存 Provider 可回放结果；
2. Tool 还必须具有合法 `toolCallId`、`toolName` 和可解析参数 envelope；
3. 连续 content/reasoning 加上述 call/result 配对可作为 replay-safe 完整前缀；
4. 最后一个配对之后的尾部，以及 pending、无结果或 envelope 损坏的 Tool，都按未完成尾部处理；
5. `STEP_COMPLETED`、`TOOL_EXECUTION_STARTED`、`TOOL_RESULT_COMPLETED` 仍由 turn durable 状态机拥有，
   `replaySafeProjection()` 不读取它们，也不把 `Tool.output` 冒充 execution phase。

### 6.3 request-only replay metadata

为 `UIMessage` 增加不进入 Kotlin serialization 的 request-only 投影字段，例如：

```kotlin
data class ProviderReplayProjection(
    val completePartCount: Int,
    val hasIncompleteTail: Boolean,
)

@Transient
val providerReplayProjection: ProviderReplayProjection? = null
```

名称可以按最终代码风格调整，但语义必须保持：

- `null`：普通成功消息，全部 parts 由正常协议策略处理；
- 非空：这是非成功 Assistant 的请求投影；`parts.take(completePartCount)` 是具有完整
  Provider call/result 回放配对的前缀，其余只是可见的部分输出/提示；
- 字段只存在于 `replaySafeProjection()` 的返回对象，不写回 Conversation、Room、备份或 UI durable 状态；
- 禁止用匹配 `[Previous assistant response did not complete.]` 字符串推断边界。

### 6.4 `replaySafeProjection()` 新规则

对成功消息保持现状。对非成功 Assistant，按 tool boundary 处理：

1. 连续的 `Content -> replay-safe Tools` 组成完整 step：
   - 保留 Text、持久化成功的 Image、Reasoning 和 source metadata；
   - 保留所有安全 Tool envelope 与其 replay-safe output；
2. pending、未执行、参数损坏或无安全 envelope 的 Tool 仍删除；
3. 最后一个完整 Tool step 之后的尾部：
   - Text/Image 按现有规则保留为辅助上下文；
   - Reasoning 与不透明 provider metadata 删除；
   - 追加现有 incomplete marker；
   - 从该尾部开始不计入 `completePartCount`；
4. message-level `providerMetadata` 在 terminal message 上继续清除，因为它可能描述整个未完成 output batch，
   不能仅凭 part 边界证明完整；
5. `terminalStatus`、reason、detail 继续不进入 Provider wire。

如果 parts 出现“unsafe/pending tool 之后又有 executed tool”的非连续结构，投影必须 fail-closed：
只保留第一个不安全边界之前的完整前缀，不能跨越不完整 step 拼接后续内容。

### 6.5 DeepSeek V4 严格历史

当 Chat policy 的 `terminalAssistant` 为 `COMPLETE_STEP_PREFIX`：

- 成功消息序列化全部完整 assistant envelopes；
- terminal 请求投影只使用 `parts.take(completePartCount)`；
- terminal 的 partial text 和 incomplete marker 不伪装成正常 DeepSeek assistant turn；
- `completePartCount == 0` 时，该 terminal Assistant 不进入 DeepSeek Chat 历史；
- 不修改 UI 中持久化的部分文本和终态诊断。

其他协议保持既有 partial-text replay 行为，除非它们自己的协议测试证明也需要严格丢弃尾部。
这避免用 DeepSeek 的验证规则无差别改变所有 Provider 的上下文兼容性。

### 6.6 缺失 reasoning 的处理

禁止仅凭“某 assistant 没有 Reasoning part”判定数据损坏，因为当时可能关闭了 thinking，
也可能模型没有返回 reasoning。处理规则是：

- 本地有非空 Reasoning：按有效 replay policy 完整发送；
- 本地没有 Reasoning：不制造空字符串或占位 CoT；
- 只有 metadata 明确表明某个必要 opaque/reasoning payload 存在但损坏时，才以协议状态不完整 fail-closed；
- 本次不新增此类 durable metadata，也不对旧会话猜测性迁移。

## 7. 逐文件变更

### 7.1 `ai` 模块

#### `provider/providers/openai/OpenAIEndpointProfile.kt`

- 增加 `VisibleReasoningReplay`、`OpaqueReasoningReplay`、`TerminalAssistantReplay` 和
  `ChatReasoningReplayPolicy`；
- 增加唯一 `resolveChatReasoningReplayPolicy()`；
- 复用 `resolveOpenAIEndpointVendor()` 和 `ModelRegistry.DEEPSEEK_V4`；
- 不新增 B.AI vendor；
- 保持 Responses profile 不变。

#### `provider/providers/openai/ChatCompletionsAPI.kt`

- 在 request builder 顶部只计算一次 `requestHasTools`；
- 使用该值同时写顶层 `tools` 和解析 replay policy；
- `buildMessages()`/`addAssistantMessages()` 改接收 typed policy；
- `TOOL_ASSISTANT_ENVELOPES` 只强制 tool-bound assistant；
- `ALL_ASSISTANT_ENVELOPES` 同时覆盖 tool-bound 和 trailing/final assistant；
- DeepSeek 严格工具请求识别 terminal request projection，只序列化完整 part 前缀；
- OpenRouter details 继续 source-isolated，存在 details 时不重复发 visible reasoning；
- 删除 `requiresToolReasoningReplay()`、旧 Boolean 参数和旧注释。

#### `provider/Provider.kt`、`util/Request.kt`

- 增加 typed request-body ownership/typed reserved-key error；
- `mergeCustomBody()` 必须接收 ownership；
- 保留非保留字段原有递归合并语义；
- 删除旧无 ownership overload。

#### 其他 Provider request builder

- `ResponseAPI`、`ClaudeProvider`、`GoogleProvider` 以及使用 `mergeCustomBody()` 的文本请求构造器
  一次性声明本协议保留字段；
- 图像、编辑、Embedding 请求构造器按自己的身份/输入/传输字段声明最小保留集合；
- 不改变各线已有请求格式，只增加冲突验证。

#### `ui/Message.kt`

- 增加 request-only、非持久化的 replay projection metadata；
- 把 terminal parts 处理从逐 part 一刀切改为 tool-boundary group 处理；
- 保留已完成 step 的 Reasoning，剔除未完成尾部 Reasoning；
- 保持 media failure、data URL、unsafe Tool 和 terminal marker 的现有安全规则；
- 明确非连续 unsafe step 的 fail-closed 行为。

#### `ui/MessageMetadata.kt`

- 如果 replay projection metadata 定义在独立文件中，仅放请求级类型；
- 不改变现有 OpenRouter、Responses、Claude、Gemini durable metadata JSON；
- 不给普通 visible reasoning 增加伪造的 endpoint source。

### 7.2 `app` 模块

#### Assistant/Model custom body 配置 UI

- 保持通用 JSON 编辑能力，不复制 Provider ownership，也不使用跨协议保留键并集；
- 已有冲突配置仍可打开、编辑和删除，不在读取时修改用户数据；
- 实际协议在请求构建时由对应 builder 精确校验，避免默认模型或 Provider override 切换造成 UI 假错误。

#### `GenerationLoop`

- 保持每 step 动态解析工具和 checkpoint 顺序不变；
- 确认传给 `TextGenerationParams.tools` 的集合就是 request builder 的唯一工具事实；
- 不新增另一份 `requestHasTools` durable 状态；
- 先完成上下文裁剪和输入 Transformer，再应用 `replaySafeProjection()`，保证
  `completePartCount` 对应最终 Provider parts；不写回其 request-only metadata。
- 工具返回空 part 列表时规范化为非空结构化 replay envelope，保证“结果已产生”和“结果内容为空”不再混为一谈，
  避免重复执行有副作用工具；
- active Tool phase 只由已提交的 `ToolExecutionEntity` / typed `ToolResultEvent` 推进；checkpoint 后补发 presentation tick，通知栏不再以 output 缺失推断执行中。等待审批使用独立通知事件，不冒充“生成完成”终态。

### 7.3 参考文档

同一变更中更新：

- `docs/references/protocol-reference.md`
  - 把 DeepSeek 规则改成“请求带 tools 时回放所有完整历史 assistant reasoning”；
  - 记录 Chat typed policy、B.AI/compatible gateway 的模型识别边界；
  - 保留 MiMo、OpenRouter 的独立范围。
- `docs/references/multimodal-context-and-turn-durability.md`
  - 把 terminal replay 改成完整 Provider step 原子回放；
  - 记录 request-only complete prefix 与严格协议尾部丢弃规则。
- `docs/references/assistant-configuration.md`
  - 将 custom body 说明为非结构高级参数覆盖入口；
  - 列明冲突 fail-fast、持久化不迁移和 request-builder 权威校验语义。

除非用户明确要求发布版本，否则不修改 `docs/dev/changelog.md`、`versionCode` 或 `versionName`。

## 8. 测试方案

### 8.1 Chat policy 单元测试

至少覆盖：

| 场景 | tools | includeHistoryReasoning | 期望 |
|---|---:|---:|---|
| B.AI/compatible + `deepseek-v4-flash` | 是 | 关 | 所有完整 assistant reasoning 回放 |
| compatible + `deepseek-v4-pro` | 是 | 关 | 所有完整 assistant reasoning 回放 |
| compatible + DeepSeek V4 已登记别名 | 是 | 关 | 与 registry 契约一致 |
| compatible + 非 DeepSeek 模型 | 是 | 关 | 不回放 visible reasoning |
| B.AI + 非 DeepSeek 模型 | 是 | 关 | 不因 host 被误判 |
| DeepSeek V4 | 否 | 关 | 不回放历史 reasoning |
| DeepSeek V4 | 否 | 开 | 回放历史 reasoning |
| DeepSeek V4 模型有 TOOL 能力但 `params.tools` 为空 | 否 | 任意 | 不写顶层 tools，不启用 mandatory replay |
| DeepSeek V4 `params.tools` 非空但模型无 TOOL 能力 | 否 | 任意 | 不写顶层 tools，不启用 mandatory replay |
| 官方 OpenAI + DeepSeek 风格 modelId | 是 | 开 | 不发送第三方 reasoning 扩展 |
| MiMo 官方端点 | 是 | 关 | 只保留 tool-bound reasoning |
| OpenRouter same source | 是 | 关 | 回放 details，不降级 visible reasoning |
| OpenRouter/其他 host source mismatch | 是 | 任意 | 不泄漏 details |

现有测试
`deepseek v4 tool reasoning should be replayed when history reasoning disabled`
必须改成断言两个 assistant envelope 都包含各自 reasoning，不能只改测试名称。

### 8.2 消息结构测试

覆盖：

- 多个 reasoning fragment 在同一 envelope 内按顺序拼接；
- content、reasoning、并行 tool calls 保持原 assistant envelope；
- 多个工具 step 后的最终 reasoning 回放；
- 流式 reasoning/content/tool delta 顺序变化后仍得到相同历史；
- reasoning 为空或原本不存在时不制造字段；
- 上下文裁剪后的消息集合才是“所有历史”的范围。
- 输入 Transformer 若重新物化 terminal/pending Tool 或 opaque reasoning，最终 Provider 请求仍由 Transformer 之后的
  `replaySafeProjection()` 清理并正确计算完整前缀。

### 8.3 terminal replay 测试

在 `MessageTest` 与 Chat message/request 测试中覆盖：

1. terminal message 只有 partial reasoning/text：完整前缀为 0；
2. 一个完整工具 step + partial final reasoning/text：保留首个 step reasoning/tool/result，尾部不进入严格 DeepSeek 历史；
3. 多个完整工具 step + partial tail：全部完整 step 原序保留；
4. pending/invalid tool：从该边界 fail-closed，不跨越拼接后续 step；
5. executed tool 的 output 含 media failure：维持现有降级 JSON；
6. terminal UI/persistence 内容不因请求投影发生变化；
7. 非 DeepSeek Provider 维持现有 partial text + marker 兼容行为；
8. message-level opaque metadata 不因保留 visible reasoning 被误回放。

### 8.4 custom body 所有权测试

- 每条文本协议的每个保留字段发生冲突时都在 HTTP 前抛 typed error；
- 多个冲突 key 的诊断排序稳定；
- 非保留简单字段仍覆盖；
- 非保留嵌套 object 仍递归合并；
- Assistant 与 Model custom bodies 合并后统一校验；
- B.AI DeepSeek 请求不能通过 custom body 添加/删除 `tools` 或替换 `messages`；
- request builder 按当前协议 ownership 精确校验，不存在跨协议 UI 并集校验；
- 已有冲突配置可反序列化、可编辑，不发生持久化迁移。

### 8.5 四线回归

- Responses：`outputItemGroups`、wire/source profile、encrypted content、DeepSeek reasoning text 不变；
- Claude：signed thinking/redacted thinking、tool use/result 顺序不变；
- Gemini：`thoughtSignature`、Part boundary、function call id 不变；
- OpenAI Chat：官方端点不发送第三方 reasoning 字段，OpenRouter/MiMo 维持既有协议范围。

### 8.6 Turn 与恢复回归

- Provider 400 仍归类为 `INVALID_REQUEST` 并正确 Finalize，不把错误吞掉；
- `STEP_COMPLETED`、`TOOL_EXECUTION_STARTED`、`TOOL_RESULT_COMPLETED` 顺序不变；
- 审批暂停/继续复用原 TurnHandle；
- 进程重启时 STARTED tool 仍恢复为 UNKNOWN；
- request-only replay metadata 不进入 Room、备份或 Conversation snapshot；
- Master 与 Target 使用相同请求投影和 Chat policy。
- 空成功结果与空 `ToolExecutionFailure.output` 都形成非空 replay envelope，不会触发第二次执行；
- 无 execution fact 的 contract rejection 通过 typed result checkpoint 推进 FAILED phase，流式 output 本身不能提前推进。

## 9. 实施顺序

以下步骤可以分提交准备，但最终交付不能保留过渡接口或双路径：

1. 先新增 policy/ownership/replay projection 的失败测试，复现 B.AI DeepSeek V4 400 所对应的缺失请求体形状；
2. 建立 request-body ownership，并迁移所有 `mergeCustomBody()` 调用点；
3. 建立 Chat typed replay policy，删除旧 Boolean 和 `requiresToolReasoningReplay()`；
4. 改造 terminal replay 为完整 step 前缀 + 不完整尾部；
5. 更新 Chat serializer，使 DeepSeek 严格工具请求只消费完整前缀并回放全部 reasoning；
6. 补齐 Provider、terminal、custom body、Master/Target 回归测试；
7. 更新三份 `docs/references/`；
8. 删除所有旧注释、旧测试断言、旧 helper 和无调用类型；
9. 执行定向测试、全量门禁、diff 和工作树检查。

不得以 deprecated overload、默认空 ownership、B.AI 临时特判或新旧 replay Boolean 并存完成迁移。

## 10. 兼容性矩阵

| 维度 | 结果 |
|---|---|
| B.AI DeepSeek V4 Flash | 带 tools 时修复为完整 reasoning 回放 |
| B.AI 其他模型 | 不受 DeepSeek model policy 污染 |
| DeepSeek 官方 Chat | 按官方 tools 契约完整回放 |
| 普通 OpenAI-compatible Chat | 继续遵守 `includeHistoryReasoning` |
| 官方 OpenAI Chat | 继续不发送第三方 reasoning 扩展 |
| MiMo/OpenRouter | 保持各自既有 mandatory/source-isolated 规则 |
| Responses/Claude | wire 行为不变，只接受 custom body 保留字段校验 |
| Gemini | DeepSeek 改动不复用其 opaque 状态；Google 专属整改见独立方案与协议参考 |
| 旧成功会话 | 无迁移，直接使用已有 Reasoning parts |
| 旧非成功会话 | 请求期生成完整 step 前缀，不改持久化消息 |
| 旧 Settings/备份 | 数据结构不变；保留字段冲突明确报错并可编辑 |
| Room/DataStore | schema 与 migration 不变 |
| 用户 UI | 正常聊天体验不变；非法 custom body 获得明确诊断 |

## 11. 风险与控制

### 11.1 模型误识别

风险：在兼容网关上仅靠字符串包含把其他模型误判成 DeepSeek V4。

控制：只使用 `ModelRegistry.DEEPSEEK_V4` 已登记契约；新增正反例测试；B.AI host 不参与模型识别。

### 11.2 custom body 兼容破坏

风险：少数用户依赖覆盖 `messages`、`tools` 等核心字段。

控制：不删持久化数据，不静默忽略；当前协议的 request builder 在请求前给出明确冲突字段。结构字段覆盖
本身无法与唯一协议 owner 共存，因此不保留兼容旁路。

### 11.3 terminal reasoning 误当成完整状态

风险：保留未完成 CoT 或不透明签名会制造新的无效 continuation。

控制：只把具有 replay-safe call/result envelope 的 Tool 之前的连续前缀视为完整 step；尾部
reasoning/opaque state 继续剔除；
严格 DeepSeek 请求不发送 partial assistant tail。

### 11.4 跨 Provider 状态泄漏

风险：普通 reasoning 修复意外带出 OpenRouter details、Responses encrypted content、Claude/Gemini signature。

控制：visible 与 opaque replay 分维度；opaque state 始终检查既有 source/profile；四线增加负向测试。

### 11.5 请求与解析模式不一致

风险：custom body 修改 `stream`、model 或 input container 后，builder 与 parser 使用不同契约。

控制：这些字段进入各 builder 的保留集合，HTTP 前 fail-fast。

## 12. 验证与完成定义

### 12.1 定向验证

Windows 上串行执行，按测试类分开使用 `--tests`：

```powershell
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.ChatCompletionsAPIMessageTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.ui.MessageTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.MiMoEndpointContractTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.ResponseAPIMessageTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.ClaudeProviderMessageTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.GoogleProviderMessageTest" --no-parallel --max-workers=1
```

custom body ownership 由 `JsonTest` 和各 Provider request-builder 测试覆盖。

### 12.2 全量门禁

```powershell
gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
git diff --check
git status --short
```

检查最终 diff，确认没有纳入或回退用户已有改动；以最终退出码和测试报告为准。

2026-08-29 本地执行结果：定向 Provider/turn/UI 回归通过；`test assembleDebug lintDebug assembleRelease`
串行全量门禁 `BUILD SUCCESSFUL`（831 actionable tasks）。独立架构、协议、测试与 UI 审查未发现剩余 P0/P1。
本结果不包含真机 Compose 交互、系统通知或真实 B.AI/DeepSeek 服务验收。

### 12.3 真实 Provider 验收

自动化门禁证明序列化与本地状态机正确，但不能替代真实 B.AI/DeepSeek 服务验收。具备有效凭据时，至少验证：

1. B.AI `deepseek-v4-flash`，thinking 开启，工具开启，`includeHistoryReasoning=false`；
2. 第一用户轮产生多次工具调用和最终回答；
3. 第二用户轮继续携带 tools，服务端不再返回 reasoning missing 400；
4. 请求 trace 中每个历史完整 assistant envelope 都带回原 reasoning；
5. B.AI 上选择一个非 DeepSeek 模型，确认不发送 DeepSeek 专用强制历史；
6. 取消或故障后继续对话，完整工具 step 可回放，partial tail 不进入严格历史。

不得把构建/JVM 测试通过表述为真实 Provider 验收通过；未配置凭据时必须明确记录该项未运行。

### 12.4 完成定义

同时满足以下条件才能宣布开发完成：

- B.AI DeepSeek V4 请求体测试证明 tools 存在时所有完整历史 reasoning 均回放；
- `includeHistoryReasoning` 与 mandatory state 的语义在类型和测试中完全分离；
- `requestHasTools` 只有一个计算点；
- `messages`、`tools` 等结构字段只有 Provider builder owner；
- 所有 `mergeCustomBody()` 调用点使用显式 ownership，无旧 overload；
- terminal replay 按完整 step 原子处理，不再出现 tool/result 保留而绑定 reasoning 被删除；
- B.AI、MiMo、OpenRouter、官方 OpenAI 和普通 compatible 的正负测试全部通过；
- Responses、Claude、Gemini 状态回放回归通过；
- 三份参考文档与实际代码一致；
- 无 Room/DataStore/schema/version/changelog 非授权变化；
- 定向测试、全量门禁、`git diff --check` 和最终工作树检查通过；
- 真实 Provider 验收状态单独、准确记录。
