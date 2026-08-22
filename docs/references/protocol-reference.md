# AI 协议层参考

本文档描述当前代码支持的线协议、统一消息模型、不透明状态回放和 endpoint/model 适配边界。它以实现契约为中心；在线模型能力会变化，新增或修改适配时仍须以对应供应商的官方文档和回归测试为依据。

## 1. 协议拓扑

项目有三类 Provider 配置和四种线协议：

```text
ProviderSetting
├─ OpenAI -> OpenAIProvider
│  ├─ ChatCompletionsAPI  (useResponseApi = false)
│  └─ ResponseAPI          (useResponseApi = true)
├─ Claude -> ClaudeProvider
└─ Google -> GoogleProvider
```

| 线协议 | 请求入口 | 对话结构 | 工具调用/结果 | 流式终态 |
|--------|----------|----------|---------------|----------|
| OpenAI Chat Completions | `/chat/completions` | `messages[]` | assistant `tool_calls` / `role=tool` | `[DONE]` |
| OpenAI Responses | `/responses` | 扁平 `input[]` items | `function_call` / `function_call_output` | `response.completed`、`incomplete`、`failed` |
| Anthropic Messages | `/messages` | `messages[]` + content blocks | `tool_use` / user `tool_result` | `message_stop` |
| Google Gemini generateContent | `:streamGenerateContent` | `contents[]` + parts | `functionCall` / `functionResponse` | SSE 关闭或错误 |

Provider 类型决定原生协议族，`useResponseApi` 只选择 OpenAI 子协议，host 只选择已证实的 endpoint 差异，modelId 只决定模型能力和参数约束。未知 OpenAI-compatible host 不会因为模型名称被重新解释为某个供应商。

### 工具 JSON Schema 边界

`Tool.parameters` 与缓存的 `McpTool.inputSchema` 都使用完整 `JsonObject` 作为规范表示。MCP SDK 的 `ToolSchema` 会整体序列化，因而保留 JSON Schema 2020-12 的 `$schema`、`$defs`、`$ref`、`properties` 和 `required`；通用层不使用封闭数据类枚举关键字，也不按 host 删除定义。

OpenAI Chat Completions、Responses 和 Anthropic Messages 直接发送该文档。Gemini 使用官方的 `parametersJsonSchema` 字段，并移除该字段不接受的 `$schema` 方言声明；其余定义和引用保持原样。Provider 差异只能在对应协议 Adapter 处理，不能回写或降级通用缓存。

## 2. 统一消息模型

`UIMessage` 是协议无关的持久化中间表示：

```text
UIMessage
├─ role
├─ parts: Text / Reasoning / Image / Document / Audio / Video / Tool
├─ providerMetadata: MessageMetadata
└─ createdAt / finishedAt / usage
```

`UIMessagePart.Tool` 同时保存调用参数、审批状态和执行结果。Provider 序列化时再展开为各自的 tool call/result 结构，数据库不会插入独立的持久化 `MessageRole.TOOL`。

生成图片在解析源头就必须是可渲染 URL。Chat Completions 保留完整 `data:` URI，Gemini 使用真实 mime 组装 `data:<mime>;base64,<payload>`。合并层只把无前缀的 base64 碎片追加到当前图片，不会给已经完整的 URL 再补 `image/png` 前缀，也不会把两张完整图片拼成一条。

所有 Provider 使用 `groupPartsByToolBoundary()` 重建 assistant/tool 步骤。已执行 Tool 是边界；同一模型 step 的 Reasoning、Text 和并行 Tool 保持顺序，工具结果紧跟产生它们的 assistant step。

### Metadata 分层

| 类型 | 层级 | 保留内容 |
|------|------|----------|
| `ClaudeReasoningMetadata` | Part | thinking `signature`、`redacted_thinking.data` |
| `OpenAIReasoningMetadata` | Part | reasoning item ID、`encrypted_content`、来源 profile |
| `GoogleThoughtMetadata` | Part | `thoughtSignature`、function call ID、thought 标记、草稿图原始数据 |
| `OpenRouterReasoningMetadata` | Part | 仅 `openrouter.ai` 回传的有序 `reasoning_details`；旧会话无此字段时走可见 `reasoning_content` |
| `OpenAIResponseMetadata` | Message | `store=false` 所需的完整有序 `response.output` 批次、wire format、来源 profile |
| `DiffMetadata` | Part | 仅供 UI 展示的 unified diff，不发送给 Provider |

不透明状态不能从可见 Text/Reasoning 无损重建，也不能跨来源随意复用。metadata 字段保持可空并向后兼容旧会话；更新工具 metadata 时必须 merge，不能覆盖 Provider 状态。

## 3. OpenAI endpoint 身份

`OpenAIEndpointProfile.kt` 是 host 到内部 endpoint 身份的唯一来源。已识别 host 用于选择经验证的参数或 wire 差异；其他 host 为 `COMPATIBLE`。

```text
host
  -> resolveOpenAIEndpointVendor()
  -> Chat Completions 参数与兼容分支
  -> resolveResponseEndpointProfile()
  -> Responses wire/source/能力组合
```

这些枚举是内部协议状态，不是用户设置。不得新增持久化的“方言”开关来修复单个 host，也不得用 modelId 猜测未知网关背后的 endpoint。

独立文生图走同一 OpenAI-compatible host 的 `/images/generations` 与 `/images/edits`。HTTP 失败通过 `formatProviderHttpError()` 变成带 `statusCode` / `errorCode` / `errorType` 的 `HttpException`。
OpenAI Images 使用 `error.code`（如 `moderation_blocked`、`content_policy_violation`、`rate_limit_exceeded`、`credit_balance_exhausted`、`invalid_api_key`）；
xAI Imagine 使用相同信封或顶层 `code`/`message`，并可能在 200 响应里用 `respect_moderation=false` 标记审核未通过。
分类与工具回传见 [`chat-generation-pipeline.md`](chat-generation-pipeline.md) 与 [`prompts-and-tools.md`](prompts-and-tools.md)。

## 4. Chat Completions

`ChatCompletionsAPI` 负责：

- system/developer/user/assistant/tool 角色映射；
- 文本、图片、推理、拒答、生成图片与 Tool parts 的序列化和解析；
- `tool_calls[].index` 对并行工具参数 delta 的关联；
- usage 与各兼容 endpoint 缓存字段的归一化；
- endpoint/model 特定的 reasoning、temperature、token limit 和 stream 参数。

### System role 与 token limit

官方 OpenAI reasoning 模型需要 developer role 时由 `useDeveloperRoleForSystemMessages` 决定；兼容服务默认保持 system role。官方 endpoint 使用 `max_completion_tokens`，兼容服务仍使用 `max_tokens`。

### Reasoning effort

项目级 `ReasoningLevel` 是统一 UI 枚举，不代表所有模型接受相同字符串。官方 OpenAI 变体由 `mapOfficialOpenAIReasoningEffort()` 映射，并以单元测试锁定各已支持系列；AUTO 省略参数，让 endpoint 使用默认值。

DeepSeek 直连的 Chat Completions 还发送 `thinking.type`：OFF 为 `disabled`，其他级别为 `enabled`。effort 以官方 Thinking Mode 文档为准：请求字段接受 `low` / `high` / `max`，兼容别名 `medium → high`、`xhigh → high`。

| 项目级别 | Chat `reasoning_effort` |
|----------|-------------------------|
| OFF / AUTO | 省略 |
| LOW | `low` |
| MEDIUM / HIGH / XHIGH | `high` |
| MAX | `max` |

XHIGH 保持文档别名 `high`，只有独立的 MAX 级别才发送 `max`。官方 OpenAI 不接受 `max`，因此 `mapOfficialOpenAIReasoningEffort()` 把 MAX 落到该模型支持的最高档（`high` 或 `xhigh`）。

兼容网关可能把 `choices`、`delta`、`message` 或 `tool_calls` 显式写成 JSON `null`。Chat Completions 流式解析必须用 `jsonArrayOrNull` / `jsonObjectOrNull`，不能对 `JsonNull` 强制取数组或对象，否则会中断整轮生成。`tool_calls` 数组里的空槽、`function: null`，以及 Mistral thinking 的非对象首元素，同样按空值跳过，不能强制 `jsonObject`。

OpenRouter 直连 host（`openrouter.ai`）若返回结构化 `reasoning_details`，会保存在 Reasoning part 的 source-isolated metadata 中，并只在同一 host 的后续请求回传该数组。流式分片按到达顺序累积：相同 `id` 或相同 `index` 的条目合并 `text`/`summary`，新条目追加。其他 Chat Completions host 只回放可见 `reasoning_content`。

### DeepSeek reasoning 回放

DeepSeek thinking + tools 要求后续请求保留工具步骤的 `reasoning_content`。`requiresDeepSeekToolReasoningReplay()` 在两类场景启用：

- host 是 `api.deepseek.com`；
- 兼容代理上的 modelId 可由 `ModelRegistry.DEEPSEEK_V4` 明确识别。

工具步骤强制回放 reasoning；普通无工具回答仍遵循 `includeHistoryReasoning`。Provider `toolCallId` 只用于线协议，不作为本地工具执行定位键。

## 5. Responses API

`ResponseAPI` 始终使用本地无状态回放。即使 endpoint 支持 `previous_response_id`，当前实现也通过 `store=false` 和完整历史保证 Provider 切换、持久化与离线恢复的一致性。

### Endpoint profile

| Profile | Wire format | Reasoning 表示 | 加密状态 | Function output |
|---------|-------------|----------------|----------|-----------------|
| `OPENAI` | OpenAI | summary items | `encrypted_content` | 可多模态 |
| `OPENAI_COMPATIBLE` | OpenAI | 按 OpenAI 形状 | 按 OpenAI 形状 | 可多模态 |
| `VOLC_ARK` | OpenAI | endpoint 默认 summary | 请求 `encrypted_content` | 字符串 |
| `DEEPSEEK` | DeepSeek | `content[].reasoning_text` | 不使用 | 字符串 |

相同 wire format 不表示不透明状态可跨来源复用。`sourceProfile` 进一步区分 OpenAI、通用兼容网关和方舟。

### 历史回放

```text
有 OpenAIResponseMetadata
  && wireFormat 匹配
  && sourceProfile 兼容
    -> 按 outputItemGroups 原样回放完整 output 批次
否则
    -> 从 UIMessage.parts 重建 input items
       -> Part 级 reasoning ID/encrypted content 仍单独检查来源
```

`outputItemGroups` 保留每次 response 的批次边界。回放时先追加某一 response 的完整 output，再追加该批函数调用的本地 `function_call_output`；不能把多次工具续轮压平成一个无边界列表。

流式状态用 item ID 关联 reasoning、text 和 function arguments，并在终态把完整 output items 写回 metadata。连接在未收到协议终态时关闭视为异常，不能把半个 response 当作成功。

### DeepSeek Responses effort

| 项目级别 | `reasoning.effort` |
|----------|--------------------|
| OFF | `none` |
| AUTO | 省略 |
| LOW | `low` |
| MEDIUM / HIGH / XHIGH | `high` |
| MAX | `max` |

DeepSeek profile 不请求 OpenAI summary 或 encrypted content，并使用 `reasoning_text` 流事件与 item 内容。

OpenAI / 兼容 / Ark 路径重建 reasoning item 时：若 part 带有同源 `encrypted_content`，只回传 `id` 和加密状态，不附带可见 `summary` 明文。DeepSeek 的 `reasoning_text` 路径不受影响。

## 6. Anthropic Messages

`ClaudeProvider` 把 System 放在顶层 `system`，把 assistant 内容表示为 `text`、`thinking`、`redacted_thinking` 和 `tool_use` blocks；工具结果放入 user `tool_result` blocks。

### 模型兼容的 thinking 配置

Claude thinking 不是一个可对所有历史模型统一发送的结构：

| 模型组 | AUTO | LOW～XHIGH | OFF |
|--------|------|------------|-----|
| `ModelRegistry.CLAUDE_ADAPTIVE_THINKING` | `thinking.type=adaptive` | adaptive + `output_config.effort` | `disabled` |
| 较早的 reasoning 模型 | 省略 thinking | `enabled + budget_tokens` | `disabled` |

当前 adaptive 组覆盖已注册的 Claude 4.6 及更新型号。项目级 XHIGH 在支持该值的 Opus 4.7/4.8
发送 `xhigh`，在 4.6 映射为该型号支持的 `max`。手动 thinking 的 budget 至少为协议下限且必须
小于 `max_tokens`；过小的显式 `maxTokens` 会在请求构建时失败，而不是发送无效参数。
Opus 4.5 同时发送受支持的 effort 与 manual budget，其他旧型号只使用 budget。Claude 3.5 只注册
TOOL 能力，不发送 thinking。

当 thinking 实际启用时不发送 temperature；legacy AUTO 因为省略 thinking，可以正常发送 temperature。

### 不透明 thinking 状态

- `thinking.signature` 保存到 `ClaudeReasoningMetadata.signature`；
- `redacted_thinking.data` 保存到 `redactedData`；
- 工具续轮按原顺序、原内容回放；
- 切换到不同模型配置时剥离旧模型的 thinking/redacted blocks，保留可见答案和工具历史。

### Prompt caching

开启 `promptCaching` 时，Provider 同时支持顶层 automatic `cache_control` 和显式 block 断点：最后一个 System block、最后一个 Tool，以及倒数第二条可缓存 user message。TTL 由 `ClaudePromptCacheTtl` 选择。

## 7. Google Gemini

`GoogleProvider` 使用 `systemInstruction`、`contents[].parts`、`functionCall` 和 `functionResponse`。认证支持 AI Studio API key、Vertex API key 与 Service Account OAuth。

### Thinking 与签名

- Gemini 2.5 使用 `thinkingBudget`；Pro 型号不能保证完全关闭时不会强行发送 `0`。MAX 的 32000 预算在 Flash/Flash-Lite 上钳到 24576，Pro 钳到 32768。
- `ModelRegistry.GEMINI_3_SERIES` 使用 `thinkingLevel`，项目 XHIGH / MAX 收敛为 HIGH。
- `GEMINI_3_NO_MINIMAL_THINKING`（3.1 Pro 全形态 + 3.7 Flash）不支持 `minimal`（官方 API 校验错误，
  无法停用思考），`ReasoningLevel.OFF` 降级为 `low`。`GEMINI_3_PRO` 通过 `notTokens("1")` 排除
  3.1 Pro 的 subsequence 宽匹配，版本号优先由 `GEMINI_3_1_PRO` 独立接管。
- `GoogleThoughtMetadata` 在 Text、Reasoning、Image 和 FunctionCall 等 Part 上保留 `thoughtSignature`。
- 带签名 Part 不能与相邻 Part 合并；空文本 Part 上的签名也必须保留。
- function call 的 API ID 保存并回填到对应 `functionResponse.id`。
- thought image 的原始 `inlineData` 保存在 metadata 中，UI 可以显示占位而续轮仍能原样回放。

函数声明与模型内置工具合并到同一个 `tools` 数组。Schema 清理只移除当前适配明确不使用的兼容字段，保留 Gemini 支持的 `enum`。grounding metadata 转为 `UIMessageAnnotation.UrlCitation`。

## 8. ModelRegistry 的职责

`ModelRegistry` 根据 modelId 推断：

- 输入/输出模态；
- TOOL / REASONING 能力；
- 已知模型组的请求参数差异。

它不决定 Provider 类型或 wire format。模型组应服务于稳定、可测试的行为差异；易变在线清单不能成为 endpoint 选择或请求硬阻断条件。匹配规则必须测试相邻版本和别名，避免 `gpt-5.10` 被误判为 `gpt-5.1` 一类的前缀错误。

## 9. 协议保真原则

1. **保留完整顺序**：Reasoning、Text、并行 Tool、Tool Result 的相对顺序是协议数据。
2. **不透明状态原样回放**：signature、encrypted content、raw output items 不做解释或拼接。
3. **不跨来源复用**：wire format 和 source profile 都兼容时才回放原始 Responses items。
4. **UI 投影不是协议真相**：可见 Text/Reasoning 可用于回退重建，但不能代替服务端原始状态。
5. **host、model、provider 正交**：只在各自职责层做判断。
6. **未知 endpoint 保守处理**：保持 compatible 默认，不猜供应商。
7. **失败必须可见**：协议终态缺失、签名错误或来源不兼容不得静默伪装成功。

## 10. 回归验证

协议修改至少覆盖：

| 范围 | 必须验证 |
|------|----------|
| 通用消息 | 多工具、并行工具、工具边界、delta 合并、usage、错误终态 |
| Chat Completions | host registry、system/developer、token limit、reasoning 映射、DeepSeek 回放 |
| Responses | profile、完整 output 批次、旧 metadata、跨来源隔离、字符串/多模态工具结果 |
| Claude | legacy/adaptive thinking、budget 边界、signature、redacted data、跨模型剥离、cache control |
| Gemini | function ID、任意 Part 签名、草稿图、function 与内置工具共存、schema enum |
| ModelRegistry | 能力、版本边界、别名和错误前缀 |

离线单元测试、Lint 和编译只能证明客户端序列化与状态机。真实供应商验证还受账号权限、地区、灰度、模型可用性、计费和限流影响；没有在线验证时不得把“请求已构建”写成“服务端已验证”。

## 11. 关键文件

```text
ai/src/main/java/me/rerere/ai/
├─ provider/providers/
│  ├─ openai/OpenAIEndpointProfile.kt
│  ├─ openai/ChatCompletionsAPI.kt
│  ├─ openai/ResponseAPI.kt
│  ├─ OpenAIProvider.kt
│  ├─ ClaudeProvider.kt
│  ├─ GoogleProvider.kt
│  └─ ProviderMessageUtils.kt
├─ provider/images/ImageGenerationResponseParser.kt
├─ util/ErrorParser.kt
├─ util/ProviderFailure.kt
├─ registry/ModelRegistry.kt
└─ ui/
   ├─ Message.kt
   └─ MessageMetadata.kt
```

## 12. 官方协议入口

- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)
- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses)
- [OpenAI Error codes](https://developers.openai.com/api/docs/guides/error-codes)
- [xAI Imagine Image Generation](https://docs.x.ai/developers/model-capabilities/images/generation)
- [xAI Debugging Errors](https://docs.x.ai/developers/debugging)
- [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages)
- [Anthropic thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [Gemini thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures)
- [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/)
- [Volcengine Ark Responses](https://www.volcengine.com/docs/82379/1956279)
