# Google Gemini 官方协议审计与整改方案

> 状态：高风险 step / signature / 终态 / custom-body ownership 整改已落地。
> 线协议见 [`protocol-reference.md`](../references/protocol-reference.md)。
> 本文保留官方协议基线、已落地决策，以及仍未实现的后续范围：`GeminiThinkingProfile`、
> ordered Part envelope、原生 audio/video/document。

## 1. 背景

DeepSeek V4 Chat reasoning 回放整改暴露了一个共性问题：可见内容、Provider continuation state、工具执行状态和
终态不能互相代替。Google Gemini 线同样依赖 opaque `thoughtSignature`、有序 `Part`、function call ID 和
模型响应 step 边界；若继续只从通用 `UIMessage.parts` 的相邻类型猜测，会在并行/顺序工具、模型切换和流式中断时
重写官方历史。

本次因此独立对照 Google 官方文档和 `googleapis` protobuf，并审查 `GoogleProvider`、通用消息合并、终态投影、
custom body ownership 与附件 capability。目标不是把 Google 强塞进 OpenAI reasoning 抽象，而是让 Google
协议状态有明确 owner，并让通用层只承载真正共通的事实。

官方依据：

- [GenerateContent API](https://ai.google.dev/api/generate-content)
- [Thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures)
- [Function calling](https://ai.google.dev/gemini-api/docs/function-calling)
- [Thinking](https://ai.google.dev/gemini-api/docs/generate-content/thinking)
- [googleapis generative language protobuf](https://github.com/googleapis/googleapis/tree/master/google/ai/generativelanguage/v1beta)

## 2. 官方协议基线

后续未实现项仍按此基线设计，不得改成 OpenAI Chat 形状。

### 2.1 Content 与 Part

- `contents` 是完整历史与本次输入；wire role 是 `user` 或 `model`。
- `Content.parts` 是有序 union。text、inline/file data、function call/response、code 和 server-side tool parts
  不能互相替换，也不能按 UI 类型重新排序。
- `systemInstruction` 是顶层 text-only Content，不是普通 user message。
- 工具续轮必须回放完整 model Content，再追加 user role 的 functionResponse Content。

### 2.2 thought 与 thoughtSignature

- `thought=true` 表示可见 thought 摘要的内容分类；是否展示由 UI/请求设置决定。
- `thoughtSignature` 是 Part 级 opaque bytes，用于后续推理；它可能位于 functionCall、text、inlineData，
  也可能位于空 text Part。
- signature 必须留在原 Part，不得拼接、移动或从相邻 Part 推断。
- Gemini 3 工具调用中，每个模型响应 step 的第一个 function call 必须携带该 step 的 signature；同一步并行 calls
  只有第一个携带，后续顺序 step 则各有自己的首个 signature。

### 2.3 function call 与 response

- `FunctionCall.name` 和 object `args` 必需；Gemini 3 返回唯一 ID。
- 对应 `FunctionResponse` 必须原样携带 call ID 和 name；旧模型无 ID 时可以解析，但不能伪造 Provider ID。
- 并行 step 的 wire 形状是一个 model Content 内的全部 calls，随后一个 user Content 内的全部 responses。
- 顺序 step 必须保持 `FC1/FR1/FC2/FR2`，不能因两个 Tool 在本地相邻而改写为并行。

### 2.4 流式与终态

- stream 是 `GenerateContentResponse` 序列，按 `candidate.index` 聚合。
- 空 text + signature 是有效 Part；usage-only 事件也是有效响应片段。
- `STOP` 才是正常完成；`MAX_TOKENS` 是 incomplete，安全/协议类 finish reason 是失败。
- prompt block 可能只有 `promptFeedback` 而没有 candidate。
- 连接在未见 terminal finish reason 时关闭必须标记 incomplete。
- usage 是 generation snapshot，不按 chunk 累加。

## 3. 已落地：当前代码对照

高风险整改已经写进 `GoogleProvider`、`GoogleThoughtMetadata`、`RequestBodyOwnership` 和通用
`replaySafeProjection()`。下面是当前实现，不是待办。

### 3.1 Step 身份与 signature

`GoogleThoughtMetadata` 同时保存：

- `thoughtSignature`、`functionCallId`、`thought`、thought image 的原始 `inlineData`
- `sourceModelId`
- `sourceProfile`：transport + 实际请求 endpoint host；Developer 使用配置后的请求 host，Vertex 使用实际固定的
  `aiplatform.googleapis.com`
- `providerStepId`：一次 Gemini candidate 响应的本地稳定身份

同一 candidate 响应中的并行 calls 共享 `providerStepId`；每次后续 Provider 调用产生新 ID。回放按 ID 拆分工具
envelope，不再由 Tool output 或完成顺序推断。旧会话缺少 ID 时只保留既有相邻工具分组，兼容历史并行 calls；
该歧义不用于新消息，也不升级成第二状态源。

serializer 只有在当前 model 与 source profile 都精确匹配时回放 signature。无来源的旧 opaque state 不猜测迁移。
Tool 流式 delta 做字段级 merge，空 delta 不再删除先前 signature/call ID。terminal incomplete tail 对 Text/Image
剥离 Google 等 Provider opaque replay key，保留 attachment/display metadata。

`providerStepId` 只表达 Provider response grouping。工具实时 phase 仍归 turn projection，durable checkpoint
仍归 turn/tool fact owner。

### 3.2 终态

`geminiFinishReasonException()` 把协议终态映射到现有 `HttpException.terminalStatus`：

| Gemini 状态 | Provider/turn 结果 |
|---|---|
| `STOP` | 正常完成 |
| `MAX_TOKENS` | `INCOMPLETE` |
| safety/recitation/prohibited/PII | `FAILED` |
| malformed/unexpected/too many/missing signature | `FAILED` |
| 未知或缺失 finish reason | fail-closed；stream 提前关闭为 `INCOMPLETE` |
| `promptFeedback.blockReason` | `FAILED`，允许无 candidates |

不新增 Google UI 状态源；`TurnOutcome.fromFailure()` 仍是 Provider failure 到 durable turn terminal 的唯一映射。
非流式失败/incomplete 经 `ProviderResponseException` 交接已解码内容和 usage。usage-only SSE 仍发布 snapshot。

### 3.3 custom body ownership

`GEMINI_OWNERSHIP`（`GoogleProvider.kt`）声明：

- 顶层 `reservedKeys`：`contents`、`systemInstruction`、`tools`
- `reservedPaths`：`generationConfig.candidateCount`、`generationConfig.responseModalities`、
  `toolConfig.functionCallingConfig.streamFunctionCallArguments`

会改变 decoder 形状的 nested path 在 HTTP 前以 `CustomBodyReservedKeyException` 拒绝
（`reason=custom_body_reserved_key`）。父 object 被 scalar、array 或 null 替换同样拒绝。Assistant UI 不维护
跨协议保留键并集。

### 3.4 当前 decoder / serializer 边界

`GoogleProvider.parseMessagePart()` 只接受：

- `text`（含空 text + signature、`thought=true` 的 Reasoning）
- `functionCall`（`name` 非空字符串，`args` 必须是完整 object）
- `inlineData` 且 `mimeType` 以 `image/` 开头；`thought=true` 的草稿图以 Reasoning 占位保存原始 `inlineData`

其他 Part 字段（`fileData`、`executableCode`、`codeExecutionResult`、server-side `toolCall`/`toolResponse`、
增量 function args）`error("Unknown Gemini message part fields: ...")`，只报告排序后的字段名，不写入原始 payload。

`UIMessagePart.toGooglePart()`：

- Image 仅在 `RequestImageSupport.STRUCTURED` 时编码 `inlineData`
- `Audio` / `Video` 返回 `null`，不硬编码 `audio/mp3`、`video/mp4`
- `Document` 无分支，落入 `else -> null`
- thought image 原样回放 metadata 里的 `inlineData`

`buildContents` 在编完 `contents` 后合并相邻同 `role` 的项（`parts` 按序拼接）。这是 Gemini 要求 `user` /
`model` 交替的线协议规范化；contents 级合并不把两个 Part 合成一个，带 `thoughtSignature` 的 Part 仍保持独立。
所有 SYSTEM 文本按顺序用换行合入一个 text-only `systemInstruction`；图片输出模型也保留它。

### 3.5 当前 thinking 控制（尚未收成 profile）

没有 `GeminiThinkingProfile`。`GoogleProvider.buildCompletionRequestBody()` 在模型声明 `REASONING` 时写
`thinkingConfig.includeThoughts=true`，再按模型组分支：

| 模型判定 | AUTO | OFF | LOW / MEDIUM / HIGH / XHIGH / MAX |
|---|---|---|---|
| `ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING`（3.1 Pro 全形态 + 3.7 Flash） | 省略 level | `thinkingLevel=low` | 3 系列映射：LOW→`low`，MEDIUM→`medium`，其余→`high` |
| 其余 `GEMINI_3_SERIES` | 省略 level | `thinkingLevel=minimal` | 同上 |
| `modelId` 匹配 `2.5.*pro` | 省略 budget | 不发送 `thinkingBudget=0`（Pro 不能保证可关） | `gemini25ThinkingBudget()`，上限 32768 |
| 其他 2.5 / 非 3 系列 reasoning 模型 | 省略 budget | `thinkingBudget=0` 且 `includeThoughts=false` | `gemini25ThinkingBudget()`，Flash/Flash-Lite 上限 24576 |

2.5 Pro 判定仍是 `modelId.contains(Regex("2\\.5.*pro"))`，不是 registry group。未知 reasoning 模型走 AUTO
时省略显式 thinking 控制。§6.1 的目标就是用单一 profile 替换这些散落分支。

### 3.6 当前媒体 capability

`Modality` 只有 `TEXT` / `IMAGE`。`RequestMediaCapabilities` 只有 `userImages` / `assistantImages` /
`toolOutputImages`。`GoogleProvider.requestMediaCapabilities()` 在模型声明 IMAGE 时三个容器都是
`STRUCTURED`，否则 `NONE`。Audio/Video 只保留附件投影生成的引用文本。这是有意收口，不是私有旁路残留。

## 4. 架构决策（后续实现仍须遵守）

1. Google step identity 归 `GoogleThoughtMetadata.providerStepId`，不建第二张状态表。
2. opaque signature 绑定精确 `sourceModelId` + `sourceProfile`；不匹配不回放。
3. typed Provider failure 进入唯一 turn 终态，不新增 Google UI 状态源。
4. custom body 用 path-aware ownership；会改变 decoder 形状的 path 在 HTTP 前拒绝。
5. 媒体 capability 先统一公共 owner，再扩能力；禁止 Google 私有 MIME/base64 旁路。
6. 禁止把 Gemini Part envelope 泛化为 OpenAI `reasoning_content`。
7. 未知 union 在对应 decoder 完成前 fail-closed，不静默丢弃。

## 5. 未实现范围

### 5.1 `GeminiThinkingProfile`

建立精确模型参数表，由 registry/model definition 提供事实。字段至少包括：

- parameter kind（`thinkingBudget` / `thinkingLevel` / 省略）
- 支持的 levels
- budget min/max
- OFF 映射（含不可关闭时的降级）
- 是否可关闭内部 thinking
- 是否可请求 thought summary

未知模型省略显式 thinking 控制，不用宽字符串猜测。完成时删除 `GoogleProvider` 中的 2.5/3.x 分支、
`isGeminiPro` 正则和 `gemini25ThinkingBudget()`，由单一 profile 驱动 `thinkingConfig`。

当前必须一并迁走的事实：`GEMINI_3_SERIES`、`GEMINI_3_NO_MINIMAL_THINKING`、`GEMINI_3_PRO` 对 3.1 Pro
subsequence 的 `notTokens("1")` 排除、2.5 Flash/Flash-Lite 24576 与 Pro 32768 上限。

### 5.2 ordered provider Part envelope

当需要支持 server-side tools、code execution 或 partial function args 时，引入 Google 专属 ordered Part
envelope/decoder：

- 保留 candidate、Content、Part ordinal、union type、opaque bytes 与 response ID
- stream accumulator 按 candidate 和 Part identity 合并
- 完整 committed step 原子持久化/回放
- `UIMessage` 只是展示投影，未知 union 不静默丢弃
- 禁止把该 envelope 泛化为 OpenAI `reasoning_content`

完成前继续维持现状：未知 Part fail-closed；`functionCall.args` 只接受完整 object；`GEMINI_OWNERSHIP`
拒绝 `candidateCount`、`responseModalities`、`streamFunctionCallArguments`。

该阶段测试至少覆盖：

- partial function args 与跨 chunk Part identity
- 多 candidate 完整消费或显式只允许 candidate 0
- server-side tool / code union
- stream 末尾空 signed Part
- `fileData` 与未知 union 的 fail-closed 诊断不含原始 payload

### 5.3 原生媒体能力

按 §4 决策 5 扩展公共 capability 与真实 MIME owner 后，再恢复 Gemini 原生 audio/video/document。同一变更必须完成：

1. 扩展 `Modality` / `RequestMediaCapabilities` 到 audio/video/document
2. Artifact/attachment owner 提供真实 MIME
3. 所有 Provider 明确声明各 role/container 支持
4. 输入投影、serializer、测试和 UI 同步
5. 删除任何 Google 私有旁路；`toGooglePart()` 不得再对 Audio/Video 返回 `null` 却同时 base64 发送

此阶段属于跨 Provider、附件和持久化边界变更，需要独立设计审查。

## 6. 已覆盖的自动化回归

`GoogleProviderMessageTest`、`MessageTest`、`MessageMetadataTest`、`JsonTest` 已覆盖：

- 同 step 并行 tools 与不同 step 相邻 tools
- function call ID/signature 往返
- Tool delta metadata 保留
- model/source 精确匹配与切换拒绝 opaque signature
- terminal tail 剥离 opaque metadata 但保留附件事实
- STOP / MAX_TOKENS / 失败 / 未知 finish reason 映射
- 多 SYSTEM、多 Part、图片输出模型 `systemInstruction`
- Google nested response-shape custom body 冲突
- nested parent object 的 scalar/array/null 替换与非法 `functionCall.name/args`
- 当前 2.5 budget / 3.x `thinkingLevel` / 3.1·3.7 OFF 降级

§5.1 完成后，thinking 断言应从 `GoogleProvider` 分支改为 profile 表驱动。§5.2 / §5.3 的测试在对应阶段补齐。

## 7. 兼容性

- Room/DataStore/backup schema 不变；Google metadata 只增加可空 JSON 字段。
- 旧可见文本、图片和 Tool result 可继续读取。
- 旧的无 model/source signature 不猜测回放。
- custom body 配置不自动删除；只有实际请求触及当前协议保留 key/path 时本地 fail-fast。
- 后续阶段不得为了兼容保留 Google 私有媒体旁路或双 decoder。
