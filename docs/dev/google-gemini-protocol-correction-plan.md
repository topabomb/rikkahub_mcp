# Google Gemini 官方协议审计与整改方案

> 状态：官方协议审计和当前高风险 step/signature/终态安全整改已完成，本地全量门禁已通过；
> 真实 Gemini 验收尚未执行。模型 thinking profile、ordered raw Part decoder 与更广泛原生媒体能力属于明确记录的后续范围。
>
> 本文记录 Google Gemini Developer API / Vertex `generateContent` 与 `streamGenerateContent` 的协议基线、
> 本地差异、已实施整改和后续架构边界。当前实现事实以代码和 `docs/references/` 为准。

## 1. 背景

DeepSeek V4 Chat reasoning 回放整改暴露了一个共性问题：可见内容、Provider continuation state、工具执行状态和
终态不能互相代替。Google Gemini 线虽然不是本次 DeepSeek 400 的根因，但同样依赖 opaque
`thoughtSignature`、有序 `Part`、function call ID 和模型响应 step 边界；若继续只从通用 `UIMessage.parts`
的相邻类型猜测，会在并行/顺序工具、模型切换和流式中断时重写官方历史。

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

## 3. 审计发现

### 3.1 响应 step 身份缺失

旧实现用 `groupPartsByToolBoundary()` 把所有相邻、有 output 的 Tool 当成一个并行组。这无法区分：

```text
同一响应：FC1 + FC2 -> FR1 + FR2
两个响应：FC1 -> FR1 -> FC2 -> FR2
```

当两个顺序模型响应之间没有可见 Text/Reasoning 时，durable parts 形状完全相同，邻接推断会改变 wire 语义并把
第二个 step 的 signature 放错位置。

### 3.2 signature 合并与来源不安全

- Tool 流式 delta 的空 metadata 可以整体覆盖先前 signature/call ID。
- terminal tail 的 Text/Image 曾保留 Google opaque metadata，与“未完成尾部不可回放”冲突。
- signature 没有绑定产生它的模型和 Developer/Vertex endpoint source，切换模型或 host 后仍可能盲目回放。

### 3.3 finishReason 没有成为 turn 终态

旧非流式路径忽略 finish reason，流式路径只把字符串塞进 `UIMessageChoice`，而 `GenerationLoop` 不消费它；
SSE 正常关闭会被当作成功。`MAX_TOKENS`、SAFETY、MALFORMED_FUNCTION_CALL 等因此可能错误提交为 completed。

### 3.4 request shape 可被 custom body 改变

仅保护顶层 `contents/systemInstruction/tools` 不足。`candidateCount`、`responseModalities` 和
`streamFunctionCallArguments` 会改变 decoder 需要处理的候选数、输出类型或 function args 增量形状。

### 3.5 system 与媒体 owner 偏差

- 旧实现只取第一条 SYSTEM、用逗号拼多 Part，并在图片输出模型上整段删除。
- 公共 `RequestMediaCapabilities` 只声明 TEXT/IMAGE，但 Google serializer 私自发送 Audio/Video，并硬编码
  `audio/mp3`、`video/mp4`，绕过模型 capability 与真实 MIME owner。

### 3.6 仍需更大工程处理的差异

- `Part` 已包含 server-side `toolCall/toolResponse` 等新 union；当前实现选择显式 fail-closed，尚未保存通用 raw envelope。
- Gemini thinking 参数已出现 2.5 budget、3/3.1/3.7 level 和图像变体差异，继续靠宽模型组会逐渐失稳。
- 原生 audio/video/document 若要恢复，必须先扩展公共 capability 和 MIME 数据源。

## 4. 架构决策

### 4.1 Google step identity 归 Provider metadata

`GoogleThoughtMetadata.providerStepId` 是一次 Gemini candidate 响应的本地稳定身份：

- 同一 candidate 响应中的并行 calls 共享 ID；
- 每次后续 Provider 调用产生新 ID；
- 新响应回放按 ID 拆分工具 envelope，不再由 Tool output 或完成顺序推断；旧会话缺少 ID 时只保留既有相邻工具分组，
  以兼容历史并行 calls。旧数据无法无损恢复顺序/并行身份，这一歧义不会用于新消息，也不会升级成 durable 状态事实；
- ID 随消息 metadata 持久化，不建立第二张状态表。

它只表达 Provider response grouping，不表达工具执行状态。工具实时 phase 仍归 turn projection，durable checkpoint
仍归 turn/tool fact owner。

### 4.2 opaque signature 绑定精确来源

Google Part metadata 同时保存：

- `sourceModelId`；
- `sourceProfile`：transport + 实际请求 endpoint host；Developer 使用配置后的请求 host，Vertex 使用实际固定的
  `aiplatform.googleapis.com`，不把未参与请求的自定义 base URL 写入来源身份；
- `providerStepId`；
- function call ID 与 signature。

serializer 只有在当前 model 与 source profile 都精确匹配时回放 signature。旧会话中无来源的 opaque state 不猜测迁移；
可见文本和普通工具结果仍可按各自安全投影保留。

### 4.3 typed Provider failure 进入唯一 turn 终态

Google adapter 将协议终态转换成现有 `HttpException.terminalStatus`：

| Gemini 状态 | Provider/turn 结果 |
|---|---|
| `STOP` | 正常完成 |
| `MAX_TOKENS` | `INCOMPLETE` |
| safety/recitation/prohibited/PII | `FAILED` |
| malformed/unexpected/too many/missing signature | `FAILED` |
| 未知或缺失 finish reason | fail-closed；stream 提前关闭为 `INCOMPLETE` |
| `promptFeedback.blockReason` | `FAILED`，允许无 candidates |

不新增 Google UI 状态源；`TurnOutcome.fromFailure()` 继续是 Provider failure 到 durable turn terminal 的唯一映射。

### 4.4 custom body 使用 path-aware ownership

`RequestBodyOwnership` 保留顶层 `reservedKeys`，并增加 `reservedPaths`。这样普通 sampling/路由扩展仍可递归 merge，
但会改变 decoder schema 的嵌套 path 在 HTTP 前以 `CustomBodyReservedKeyException` 拒绝。Assistant UI 不维护所有协议
reserved key 并集，避免在 Chat 上误报 Responses/Gemini 字段。

### 4.5 媒体 capability 先统一 owner，再扩能力

当前公共 capability 只声明原生图片。因此 Google 对 Audio/Video 只消费附件投影生成的引用文本，不再暗中 base64
发送或猜 MIME。后续若要支持原生音视频，必须同一变更完成：

1. 扩展 `Modality` / `RequestMediaCapabilities` 到 audio/video/document；
2. Artifact/attachment owner 提供真实 MIME；
3. 所有 Provider 明确声明各 role/container 支持；
4. 输入投影、serializer、测试和 UI 同步；
5. 不保留 Google 私有旁路。

## 5. 已实施变动

### 5.1 `GoogleProvider`

- 非流式和流式统一检查 prompt block、candidate index、usage 与 finish reason。
- stream 未见 terminal reason 就关闭为 incomplete；usage-only 事件不再丢失。
- 所有 SYSTEM 文本按顺序用换行合并，图片输出模型也保留 systemInstruction。
- 每次 candidate 响应生成 step ID；并行/顺序 function envelopes 按 step 回放。
- function call ID、signature、模型和 endpoint source 精确回放。
- 未知 Part 只报告字段名并 fail-closed，不把原始 payload 写入异常。
- `functionCall.name` 必须是非空字符串、`args` 必须是 object；shape 不合法时以不含原始 payload 的本地协议错误 fail-closed。
- 删除原始请求/SSE/错误体日志。
- Audio/Video 降为引用事实，不再硬编码 MIME 原生发送。

### 5.2 通用消息与终态投影

- Tool delta metadata 改为字段级 merge，空 delta 不再删除先前 opaque 状态。
- terminal incomplete tail 对 Text/Image 剥离 Google、Claude、Responses/OpenRouter 等 Provider opaque replay key，
  同时保留 attachment/display metadata。
- DeepSeek terminal strictness 独立为 `TerminalAssistantReplay`，不会因 Google/OpenRouter 的可见 reasoning 设置而误触发。

### 5.3 request ownership

- 所有 Provider builder 显式声明 ownership，无默认空集合 overload。
- Google 增加 response-shape nested paths；父 object 被 scalar、array 或 null 替换也拒绝；typed exception 暴露稳定
  `reason=custom_body_reserved_key`。
- 配置 UI 删除跨协议全局并集 validator；实际 builder 按当前协议精确校验。

## 6. 后续阶段

### 6.1 `GeminiThinkingProfile`

建立精确模型参数表，字段至少包括 parameter kind、支持 levels、budget min/max、OFF 映射、是否可关闭内部 thinking、
是否可请求 thought summary。未知模型省略显式 thinking 控制，不用宽字符串猜测。

完成时删除 `GoogleProvider` 中散落的 2.5/3.x 分支，由 registry/model definition 的单一 profile 提供事实。

### 6.2 ordered provider Part envelope

当需要支持 server-side tools、code execution 或 partial function args 时，引入 Google 专属 ordered Part envelope/decoder：

- 保留 candidate、Content、Part ordinal、union type、opaque bytes 与 response ID；
- stream accumulator 按 candidate 和 Part identity 合并；
- 完整 committed step 原子持久化/回放；
- UIMessage 只是展示投影，未知 union 不静默丢弃；
- 禁止把该 envelope 泛化为 OpenAI `reasoning_content`。

在该 decoder 完成前，相关未知 Part 和 shape-changing custom body 明确 fail-closed。

### 6.3 原生媒体能力

按 §4.5 扩展公共 capability 与真实 MIME owner后，再恢复 Gemini 原生 audio/video/document。此阶段属于跨 Provider、
附件和持久化边界变更，需要独立设计审查和设备/真实 API 验收。

## 7. 测试矩阵

已纳入自动化的核心场景：

- 同 step 并行 tools 与不同 step 相邻 tools；
- function call ID/signature 往返；
- Tool delta metadata 保留；
- model/source 精确匹配与切换拒绝 opaque signature；
- terminal tail 剥离 opaque metadata但保留附件事实；
- STOP/MAX_TOKENS/失败/未知 finish reason 映射；
- 多 SYSTEM、多 Part、图片输出模型 systemInstruction；
- Google nested response-shape custom body 冲突；
- nested parent object 的 scalar/array/null 替换与非法 `functionCall.name/args`；
- 四条文本协议所有顶层 reserved keys；
- B.AI DeepSeek V4 request-level 正反例。

后续 ordered decoder 阶段必须补：

- partial function args 与跨 chunk Part identity；
- 多 candidate 完整消费或显式只允许 candidate 0；
- server-side tool/code union；
- stream 末尾空 signed Part；
- promptFeedback、usage-only 和无 terminal SSE 的 MockWebServer 测试；
- Gemini 2.5/3/3.1/3.7 的 thinking profile 表测试；
- 原生媒体恢复后的真实 MIME 与 container capability 测试。

## 8. 兼容性与迁移

- Room/DataStore/backup schema 不变；Google metadata 只增加可空 JSON 字段。
- 旧可见文本、图片和 Tool result 可继续读取。
- 旧的无 model/source signature 不猜测回放；这是 fail-closed 的协议安全边界，不引入临时 fallback。
- custom body 配置不自动删除；只有实际请求触及当前协议保留 key/path 时本地 fail-fast。
- versionCode/versionName/changelog 保持不变。

## 9. 验证与完成边界

定向验证：

```powershell
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.GoogleProviderMessageTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.ui.MessageTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.ui.MessageMetadataTest" --no-parallel --max-workers=1
gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.util.JsonTest" --no-parallel --max-workers=1
```

仓库完整门禁：

```powershell
gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
git diff --check
git status --short
```

2026-08-29 本地执行结果：Google 协议定向回归与仓库串行全量门禁通过，
`BUILD SUCCESSFUL`（831 actionable tasks）。独立协议与架构审查未发现剩余 P0/P1。
真实 Developer API / Vertex 请求、SSE 网络生命周期与真机 UI 未执行，不在自动化通过结论内。

自动化只能证明本地 decoder/serializer 与状态机。真实 Gemini 3 验收至少覆盖单次、并行、顺序 function calling，
模型/Developer-Vertex 切换、MAX_TOKENS、安全阻断和流中断；没有凭据时必须明确记录未执行，不能称为线上验收通过。
