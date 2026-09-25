# Google Gemini 后续协议能力设计

> 状态：以下三项尚未实现。当前线协议和已支持能力只以 `docs/references/protocol-reference.md` 与代码为准。
> 原始整改审计保留在 Git 历史；本文不再记录已落地实现。

当前工具批次由 app Turn/Step 协议分配 durable `Tool.stepId`。Google adapter 按该身份回放，
不读取旧 `providerStepId` metadata，也不从相邻 Tool 或 output 推测 Step。后续设计必须沿用这个 owner，
不能根据旧审计记录重新引入 Provider 私有 Step 身份。

以下能力各自需要真实消费者和独立的实现验证。未实现的 Part union 继续明确失败；
opaque signature 必须绑定原 Part、模型和来源，不能以 UI 展示内容代替 Provider 回放状态。

## 1. `GeminiThinkingProfile`

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

## 2. ordered provider Part envelope

当需要支持 server-side tools、code execution 或 partial function args 时，引入 Google 专属 ordered Part
envelope/decoder：

- 保留 candidate、Content、Part ordinal、union type、opaque bytes 与 response ID
- stream accumulator 按 candidate 和 Part identity 合并
- 完整 committed Step 经现有 Turn/Step owner 原子持久化/回放
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

## 3. 原生媒体能力

扩展公共 capability 与真实 MIME owner 后，再支持 Gemini 原生 audio/video/document。同一变更必须完成：

1. 扩展 `Modality` / `RequestMediaCapabilities` 到 audio/video/document
2. Artifact/attachment owner 提供真实 MIME
3. 所有 Provider 明确声明各 role/container 支持
4. 输入投影、serializer、测试和 UI 同步
5. 删除任何 Google 私有旁路；`toGooglePart()` 不得再对 Audio/Video 返回 `null` 却同时 base64 发送

此阶段属于跨 Provider、附件和持久化边界变更，需要独立设计审查。

## 实施与验收边界

- Thinking profile 以当前 `GoogleProvider`、`ModelRegistry` 和对应 wire 测试重新核对型号与参数，不以本文列出的型号表代替实施时的代码及官方协议检查。
- Ordered Part 改变解析、流式合并、持久化和回放；先确定现有 Turn/Step 写入协议、失败语义及数据保全，再实现新 union。不能增加第二套会话状态源。
- 原生媒体能力必须连同公共 capability、真实 MIME、附件 owner、所有 Provider 的声明和设备场景一起验收；仅有 serializer 测试不表示端到端支持。
- 每项完成后，从本文移除已实现方案，将当前支持范围及限制写入 `protocol-reference.md`，行为变化同步相应静态契约和测试。
