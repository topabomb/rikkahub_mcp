# AI 协议体系化参考文档

> **定位**：协议层全景参考，涵盖四类基础协议规范、提供商差异映射、模型级适配，
> 以及项目代码架构与职责分析。供后续迭代决策和新人理解协议层使用。
>
> **日期**：2026-08-09
>
> **核对说明**：本文档于 2026-08-07 完成 SDK 源码核对，并于 2026-08-09 再次以当前官方在线文档和项目代码交叉核对。
> - **OpenAI**：已通过 `openai-python` v2.7.1 SDK 源码（PyPI）核对 Chat Completions + Responses API 类型定义。
> - **Anthropic**：已通过 `anthropic` Python v0.72.0 + `@anthropic-ai/sdk` TypeScript v0.115.0 SDK 源码核对 Messages API。
>   Python SDK 尚未包含 `adaptive` thinking 类型，TypeScript SDK 已确认。
> - **Google Gemini**：已通过 `@google/genai` TypeScript v2.16.0 SDK 源码核对 Gemini API 类型定义。
> - **DeepSeek**：已与 `api-docs.deepseek.com` 官方文档完整核对。
> - **火山方舟**：已核对 Responses 工具调用与深度思考官方文档（最近更新时间分别为 2026-08-04、2026-08-06）。
> - 项目代码描述已与当前 0.0.13 实现逐行比对；SDK 快照与当前在线文档冲突时，以当前官方文档为准。

---

## 一、协议分类：三类还是四类？

### 1.1 结论：4 种线协议，3 个 Provider 实现

从 **wire format（线协议）** 角度，项目中存在 **4 种独立的基础协议**：

| # | 协议名称 | Endpoint 路径 | 消息结构 | 流式终止 | 项目实现类 |
|---|----------|--------------|----------|----------|------------|
| 1 | **OpenAI Chat Completions** | `/chat/completions` | `messages[]` 嵌套数组 | `data: [DONE]` | `ChatCompletionsAPI` |
| 2 | **OpenAI Responses API** | `/responses` | `input[]` 扁平 item 数组 | `response.completed` / `response.incomplete` / `response.failed` | `ResponseAPI` |
| 3 | **Anthropic Messages API** | `/messages` | `messages[]` + content blocks | `message_stop` 事件 | `ClaudeProvider` |
| 4 | **Google Gemini API** | `:streamGenerateContent` | `contents[]` + parts | SSE 自然关闭 | `GoogleProvider` |

从 **Provider 实现** 角度，项目中只有 **3 个 Provider 类**：

```
ProviderSetting (sealed class)
  ├── OpenAI  →  OpenAIProvider
  │               ├── ChatCompletionsAPI   (useResponseApi = false)
  │               └── ResponseAPI           (useResponseApi = true)
  ├── Google  →  GoogleProvider
  └── Claude  →  ClaudeProvider
```

**为什么 OpenAI 下有两个子协议而非独立 Provider？**
- Chat Completions 和 Responses 共享同一套认证（`Bearer token`）、同一 baseUrl 格式、同一 modelId 体系
- 用户在同一个 OpenAI Provider 配置中通过 `useResponseApi` 开关切换，无需创建两个 Provider
- 两者都实现 `OpenAIImpl` 接口，`OpenAIProvider` 根据开关委托给具体实现

### 1.2 四种协议的本质差异

| 维度 | Chat Completions | Responses API | Anthropic Messages | Gemini API |
|------|-----------------|---------------|---------------------|------------|
| **消息模型** | role + content 的消息数组 | 扁平 input/output items | role + content blocks 的消息数组 | role + parts 的 contents 数组 |
| **System 消息** | `system` / `developer` role 消息 | `instructions` 顶层字段 | `system` 顶层字段 | `systemInstruction` 顶层字段 |
| **工具调用** | `tool_calls` 在 assistant 消息内 | 独立 `function_call` item | `tool_use` content block | `functionCall` part |
| **工具结果** | `role: "tool"` 独立消息 | 独立 `function_call_output` item | `tool_result` content block（在 user 消息内） | `functionResponse` part（在 user 消息内） |
| **推理状态** | 无原生支持（扩展 `reasoning_content`） | `reasoning` item（`summary`/`encrypted_content`） | `thinking` content block（含 `signature`） | `thought` part（含 `thoughtSignature`） |
| **流式格式** | SSE `data: {json}` | SSE `event: type\ndata: {json}` | SSE `event: type\ndata: {json}` | SSE `data: {json}` |
| **状态管理** | 无状态 | 支持 `store=true` 服务端状态 | 无状态 | 无状态 |
| **内置工具** | `web_search_options`（SDK 已定义） | `web_search`、`image_generation` 等 | `web_search`（server tool，SDK 已定义） | `googleSearch`、`urlContext` 等 |

---

## 二、OpenAI Chat Completions 协议

### 2.1 官方协议规范

**Endpoint**: `POST /v1/chat/completions`

**核心请求结构**：
```json
{
  "model": "gpt-5",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "...", "tool_calls": [...]},
    {"role": "tool", "tool_call_id": "...", "content": "..."}
  ],
  "temperature": 0.7,
  "max_tokens": 4096,        // 已弃用，官方改用 max_completion_tokens
  "reasoning_effort": "high", // o-series / GPT-5
  "tools": [...],
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

**关键协议要求**：
1. **角色体系**：`system` / `developer` / `user` / `assistant` / `tool`
   - 官方要求 o-series 及 GPT-5 使用 `developer` 替代 `system`
   - 兼容服务仍使用 `system`
2. **工具消息**：`tool` 角色消息只接受文本 content，不支持多模态
3. **流式终止**：以 `data: [DONE]` 终止
4. **reasoning_effort**：具体可用值随模型代际变化，不能只依赖旧 SDK 的联合类型
   - 2026-08-07 的 `openai-python` v2.7.1 快照只定义 `minimal` / `low` / `medium` / `high`
   - 基础 GPT-5 支持 `minimal` / `low` / `medium` / `high`，不支持 `none` 或 `xhigh`
   - GPT-5.1 支持 `none` / `low` / `medium` / `high`；GPT-5.2、5.4、5.5 标准版支持到 `xhigh`，GPT-5.6 还新增 `max`
   - Pro、Codex 与 Chat 不是标准点版本的同义别名：例如 GPT-5 Pro 只支持 `high`，GPT-5.2/5.4/5.5 Pro 支持 `medium/high/xhigh`，GPT-5.2/5.3 Codex 支持 `low/medium/high/xhigh`，Chat 型号不发送 reasoning effort
   - 旧 o-series 不支持 `none` 需回退 `low`
   - > **项目枚举范围**：`ReasoningLevel` 只定义了 `OFF`(`none`)/`AUTO`/`LOW`/`MEDIUM`/`HIGH`/`XHIGH` 六个级别，
     > 不直接包含 `minimal`/`max`。`XHIGH` 在支持它的 OpenAI 型号上发送官方值 `xhigh`；在基础 GPT-5、GPT-5.1 等不支持型号上收敛到 `high`。
     > `max` 级别不直接暴露为项目枚举；DeepSeek/NVIDIA 的特定分支仍可把 `XHIGH` 映射为 `max`。
5. **max_completion_tokens**：替代已弃用的 `max_tokens`
6. **web_search_options**：SDK 已定义此参数（`WebSearchOptions`），Chat Completions 支持内置网页搜索
   - > **SDK 依据**：`openai-python` v2.7.1 `CompletionCreateParamsBase.web_search_options`

### 2.2 项目代码实现

**实现文件**：`ChatCompletionsAPI.kt`

**请求构建**：`buildChatCompletionRequest()` 方法，核心逻辑：

```
buildChatCompletionRequest()
  ├── resolveOpenAIEndpointVendor(host) → endpoint 身份的单一来源
  │     ├── includeHistoryReasoning: 官方不回传 reasoning_content
  │     ├── supportToolResultModalities: 官方只发 TEXT
  │     └── useDeveloperRoleForSystemMessages: o-series/GPT-5 用 developer
  ├── requiresDeepSeekToolReasoningReplay(host, modelId)
  │     ├── host == "api.deepseek.com" → true
  │     └── ModelRegistry.DEEPSEEK_V4.match(modelId) → true（代理场景）
  ├── reasoning 控制：按 OpenAIEndpointVendor 枚举分支
  ├── max_tokens / max_completion_tokens：按 isOfficialOpenAI 选择
  └── stream_options：Mistral 不支持，其余添加 include_usage
```

**消息构建**：`buildMessages()` → `addAssistantMessages()` 核心逻辑：
1. `groupPartsByToolBoundary(parts)` 按已执行 Tool 边界分组
2. 每个工具步骤组：
   - 先输出累积的 reasoning + content → assistant 消息
   - reasoning_content 是否回传：`includeHistoryReasoning || requiresToolReasoningReplay`
   - **关键规则**：工具步骤强制回传 reasoning_content，末尾普通回答遵守 `includeHistoryReasoning`
3. 紧跟 tool 结果消息

**响应解析**：`parseMessage()` 中 part 顺序：Reasoning → Content → Refusal → Images → Tool Calls

**并行工具**：`ChatCompletionsStreamState` 按 `tool_calls[].index` 关联并行工具参数分片

### 2.3 兼容服务差异全景

| Provider | Host | reasoning 控制方式 | effort 值映射 | 特殊约束 |
|----------|------|-------------------|--------------|----------|
| **OpenAI 官方** | `api.openai.com` | `reasoning_effort` | 按模型变体 | 基础 GPT-5 OFF→minimal；GPT-5.1、5.2、5.4～5.6 标准版 OFF→none；Pro/Codex 按各自下界收敛；Chat 省略；旧 o-series OFF→low |
| **DeepSeek** | `api.deepseek.com` | `thinking.type` + `reasoning_effort` | LOW/MEDIUM/HIGH→high，XHIGH→max；OFF/AUTO 不发送 effort | 工具续轮**必须**回传 `reasoning_content`；官方有效强度为 high/max |
| **火山方舟** | `ark.cn-beijing.volces.com` | `thinking.type` | — | — |
| **OpenRouter** | `openrouter.ai` | `reasoning.effort` / `reasoning.enabled` | — | reasoning tokens 文档化 |
| **阿里云百炼** | `dashscope.aliyuncs.com` | `enable_thinking` + `thinking_budget` | — | — |
| **Mistral** | `api.mistral.ai` | 不支持 reasoning | — | 不支持 `stream_options`；thinking 在 content 数组内 |
| **书生** | `chat.intern-ai.org.cn` | `thinking_mode` (boolean) | — | — |
| **SiliconFlow** | `api.siliconflow.cn` | `enable_thinking` (boolean) | — | 需维护模型白名单（约 25 个模型 ID） |
| **智谱** | `open.bigmodel.cn` | `thinking.type` | — | — |
| **Moonshot** | `api.moonshot.cn` | `thinking.type` + `thinking.keep` | — | K2.5/K3 不支持 temperature；K2.6 需 `keep: "all"` |
| **NVIDIA** | `integrate.api.nvidia.com` | `reasoning_effort` | DeepSeek V4 特殊映射 | — |
| **OpenCode** | `opencode.ai` | `reasoning_effort` | — | — |

**各兼容服务的其他差异**：

| 差异维度 | OpenAI 官方 | 兼容服务（通用） | 特例 |
|----------|-------------|-----------------|------|
| `reasoning_content` 字段 | 不定义 | DeepSeek 扩展，与 `content` 同级 | — |
| `max_tokens` 字段 | 已弃用 | 仍使用 | — |
| Tool 消息多模态 | 不支持 | 支持（如 SiliconFlow、OpenRouter） | — |
| 流式错误 | — | 200 状态码内嵌 `error` 字段 | — |
| 缓存命中字段 | `prompt_tokens_details.cached_tokens` | 各家不同 | Moonshot: `cached_tokens`; DeepSeek: `prompt_cache_hit_tokens` |
| temperature | 思考模式不生效 | 思考模式不生效 | Moonshot K2.5/K3 完全不支持 |

---

## 三、OpenAI Responses API 协议

### 3.1 官方协议规范

**Endpoint**: `POST /v1/responses`

**核心请求结构**：
```json
{
  "model": "gpt-5",
  "instructions": "system prompt text",
  "input": [
    {"role": "user", "content": [{"type": "input_text", "text": "..."}]},
    {"type": "reasoning", "id": "rs_xxx", "summary": [...], "encrypted_content": "..."},
    {"type": "function_call", "call_id": "call_xxx", "name": "...", "arguments": "..."},
    {"type": "function_call_output", "call_id": "call_xxx", "output": "..."}
  ],
  "store": false,
  "reasoning": {
    "effort": "high",
    "summary": "auto"
  },
  "include": ["reasoning.encrypted_content"],
  "tools": [{"type": "function", "name": "...", "strict": false, "parameters": {...}}],
  "max_output_tokens": 4096,
  "stream": true
}
```

**关键协议要求**：
1. **无状态协议**：`store=false` 时，客户端必须完整回放上一轮 `response.output`
2. **扁平 input 数组**：不是嵌套 messages，而是有序的 items
3. **function_call 顺序**：同一 response 的所有 `function_call` 必须先于其 `function_call_output`
4. **reasoning item**（SDK 依据：`ResponseReasoningItem`）：
   - `id`：唯一标识，无状态续轮必须回传
   - `summary[]`：OpenAI 默认生成的推理摘要（`summary_text` 类型）
   - `content[]`：DeepSeek 扩展使用（`reasoning_text` 类型）；SDK 中 `Content` 类型定义为 `Optional[List[Content]]`
   - `encrypted_content`：加密推理状态（`Optional[str]`），无状态续轮必须回传
   - > **`reasoning.summary` 值**：SDK 定义为 `Optional[Literal["auto", "concise", "detailed"]]`，不只支持 `auto`
   - > **`reasoning.context` 的版本边界**：2026-08-07 核对的 `openai-python` v2.7.1 `Reasoning` 类型尚无该字段；当前 GPT-5.6 官方指南已经提供 `auto` / `all_turns` / `current_turn`，并要求与 `previous_response_id` 或手动完整历史回放配合。它是新模型能力，不能按旧 SDK 快照断言为“不存在”，项目当前也尚未主动发送该字段
   - > **`reasoning.mode` 的版本边界**：当前 GPT-5.6 官方指南支持在同一 GPT-5.6 模型上设置 `reasoning.mode: "pro"`；这不是独立 `gpt-5.6-pro` 模型 slug，项目当前尚未暴露该参数
   - > **`reasoning.generate_summary`** 已弃用，由 `summary` 替代
5. **流式终止**：不发送 `data: [DONE]`，通过 `response.completed` / `response.incomplete` / `response.failed` 事件终止
6. **内置工具**：`web_search`、`image_generation` 等可作为 output item 出现

**流式事件体系**：

| 事件 | 说明 |
|------|------|
| `response.created` | 响应已创建 |
| `response.output_item.added` | 输出项开始 |
| `response.output_item.done` | 输出项完成 |
| `response.reasoning_summary_text.delta` | OpenAI 摘要文本增量 |
| `response.reasoning_text.delta` / `done` | DeepSeek 推理文本增量/完整 |
| `response.output_text.delta` / `done` | 输出文本增量/完整 |
| `response.refusal.delta` | 拒答文本增量（代码已处理） |
| `response.function_call_arguments.delta` / `done` | 函数参数增量/完整 |
| `response.content_part.added` / `done` | 内容块开始/完成（DeepSeek 列出，代码不单独处理） |
| `response.completed` | 响应正常完成 |
| `response.incomplete` | 响应被截断 |
| `response.failed` | 响应失败 |

### 3.2 项目代码实现

**实现文件**：`ResponseAPI.kt`；endpoint 身份与 profile 定义集中在 `OpenAIEndpointProfile.kt`

**Endpoint Profile 架构**：

```kotlin
internal enum class ResponseEndpointProfile(
    val wireFormat: OpenAIResponseWireFormat,
    val sourceProfile: OpenAIResponseSourceProfile,
    val supportsReasoningSummary: Boolean,
    val supportsEncryptedContent: Boolean,
    val usesReasoningTextContent: Boolean,
    val supportsMultimodalFunctionOutput: Boolean,
) {
    OPENAI(...),
    OPENAI_COMPATIBLE(...),
    VOLC_ARK(...),
    DEEPSEEK(...),
}
```

**Profile 解析**：`resolveResponseEndpointProfile(host)` 只用 host，不用 modelId
- `ark.cn-beijing.volces.com` → `VOLC_ARK`
- `api.deepseek.com` → `DEEPSEEK`
- `api.openai.com` → `OPENAI`
- 其余 → `OPENAI_COMPATIBLE`

**无状态历史回放**：
1. **优先路径**：消息有 `OpenAIResponseMetadata`，且 `wireFormat` 与 `sourceProfile` 均兼容 → `addPreservedResponseItems()` 原样回放
2. **旧数据兼容**：历史 metadata 没有 `sourceProfile` 时暂按 `wireFormat` 回放，下一次响应写入当前 source
3. **回退路径**：无 metadata 或消息级来源不匹配 → 从 UIMessage parts 重建；Part 级 `reasoningId` / `encryptedContent` 仍需独立校验 `sourceProfile`，不因进入回退路径而跨来源复用

**流式状态管理**（`ResponseStreamState`）：
- `toolCallIdsByItemId`：item_id → call_id 映射
- `reasoningTextEmittedByItemId`：避免 `done` 重复追加
- `outputItemsById`（LinkedHashMap）：收集完整 item，终态时交付
- `terminalSeen`（AtomicBoolean）：检测流异常关闭

### 3.3 兼容服务差异

| 维度 | OpenAI 官方 | DeepSeek | 火山方舟 |
|------|-------------|----------|----------|
| Host | `api.openai.com` | `api.deepseek.com` | `ark.cn-beijing.volces.com` |
| `store` | 支持 `true`（服务端状态） | 恒为 `false`（无状态） | 支持；项目统一使用 `false` |
| `previous_response_id` | 支持 | 不支持 | 支持；项目使用本地无状态回放 |
| `include` | 支持 `reasoning.encrypted_content` | **不支持**，静默忽略 | 支持 |
| 推理文本格式 | `summary[].summary_text` | `content[].reasoning_text` | `summary[].summary_text` |
| `encrypted_content` | 默认生成 | **不支持** | 默认不返回，需 `include` 请求 |
| `reasoning.summary` | 生成摘要 | 可传入但不生成 | 默认生成 thinking summary |
| 流式推理事件 | `reasoning_summary_text.delta` | `reasoning_text.delta/done` | `reasoning_summary_text.delta` |
| `function_call_output.output` | 支持多模态数组 | **只接受字符串** | **只接受字符串** |
| 支持模型 | GPT-5/o-series | 仅 `deepseek-v4-flash` | 豆包系列 |
| `truncation` | 支持 `auto` | 不支持，超长直接 400 | — |
| `stream_options` | 支持 | 不支持 | — |

> **方舟 `supportsReasoningSummary = false` 设计说明**：该值为 `false` 的含义是
> 不在请求中显式发送 `reasoning.summary = "auto"`，但方舟 **默认生成** thinking summary。
> 这与代码注释一致："方舟默认生成 thinking summary，但手动无状态续轮仍需通过 `include`
> 请求 `encrypted_content`"。此设计合理——不在请求中显式设置 summary 让方舟使用默认行为，
> 同时通过 `include` 获取 `encrypted_content` 用于无状态续轮。

---

## 四、Anthropic Messages API 协议

### 4.1 官方协议规范

**Endpoint**: `POST /v1/messages`

**认证**：`x-api-key` header + `anthropic-version: 2023-06-01`

**核心请求结构**：
```json
{
  "model": "claude-opus-4-7",
  "messages": [
    {"role": "user", "content": [{"type": "text", "text": "..."}]},
    {"role": "assistant", "content": [
      {"type": "thinking", "thinking": "...", "signature": "..."},
      {"type": "tool_use", "id": "toolu_xxx", "name": "...", "input": {...}}
    ]},
    {"role": "user", "content": [
      {"type": "tool_result", "tool_use_id": "toolu_xxx", "content": [...]}
    ]}
  ],
  "system": [{"type": "text", "text": "system prompt"}],
  "max_tokens": 64000,
  "thinking": {"type": "adaptive", "display": "summarized"},
  "output_config": {"effort": "high"},
  "tools": [{"name": "...", "description": "...", "input_schema": {...}}],
  "stream": true,
  "cache_control": {"type": "ephemeral", "ttl": "1h"}
}
```

**关键协议要求**：
1. **System 消息**：不在 `messages` 数组内，而是顶层 `system` 字段（支持数组格式 + `cache_control`）
2. **Content blocks**：消息 content 是类型化数组，包括 `text`、`thinking`、`tool_use`、`tool_result`、`image`
3. **Thinking block**：
   - `thinking`：推理文本
   - `signature`：签名，回传时必须携带（用于验证 thinking 的完整性）
   - `redacted_thinking`：加密的推理内容，不可读但需回传
4. **Thinking 控制**（SDK 依据：`@anthropic-ai/sdk` TS v0.115.0）：
   - `ThinkingConfigParam = ThinkingConfigEnabled | ThinkingConfigDisabled | ThinkingConfigAdaptive`
   - `type=enabled`：`budget_tokens`（必填，≥1024）+ `display?`（`summarized`/`omitted`）
   - `type=adaptive`：`display?`（`summarized`/`omitted`，默认 `summarized`）+ 配合 `output_config.effort`
   - `type=disabled`：关闭思考
   - > **Python SDK v0.72.0 尚未包含 `adaptive` 类型**，只有 `enabled`/`disabled`；TypeScript SDK v0.115.0 已包含
   - > **`output_config.effort` 值**：TS SDK 定义为 `'low' | 'medium' | 'high' | 'xhigh' | 'max'`（5 值，无 `none`）
   - > **`display` 字段**：`enabled` 和 `adaptive` 都支持 `display`，值为 `summarized`（正常返回）或 `omitted`（隐藏但返回签名）
5. **Tool 体系**：
   - `tool_use` block 在 assistant 消息内
   - `tool_result` block 在 user 消息内（与 tool_use_id 关联）
   - `input_schema` 而非 `parameters`
6. **流式事件**：
   - `message_start` → `content_block_start` → `content_block_delta` → `content_block_stop` → `message_delta` → `message_stop`
   - `thinking_delta` / `signature_delta` 用于流式 thinking
   - `input_json_delta` 用于流式工具参数
7. **Prompt Caching**：
   - `cache_control: {"type": "ephemeral"}` 标记缓存断点
   - 支持 5 分钟（默认）和 1 小时 TTL
   - 可在 system、messages、tools 上设置

### 4.2 项目代码实现

**实现文件**：`ClaudeProvider.kt`

**请求构建**：`buildMessageRequest()` 方法

**Thinking 控制**（新 API）：
```kotlin
when (params.reasoningLevel) {
    ReasoningLevel.OFF → thinking.type = "disabled"
    ReasoningLevel.AUTO → thinking.type = "adaptive", display = "summarized"
    else → thinking.type = "adaptive", display = "summarized"
           + output_config.effort = level.effort
}
```

**Temperature**：思考模式不生效，仅在 `!reasoningLevel.isEnabled` 时发送

**消息构建**：
- `addAssistantMessage()`：使用 `groupPartsByToolBoundary` 分组
  - Content 组 → `toContentBlock()` 转换（text/image/thinking）
  - Tools 组 → `tool_use` blocks + 紧跟 `tool_result` user 消息
- Reasoning 回传：`thinking` block 携带 `signature`（来自 `ClaudeReasoningMetadata`）
- `redacted_thinking.data` 作为不透明协议状态保存在 `ClaudeReasoningMetadata.redactedData` 并原样回传
- 已知由不同模型配置生成的 assistant 消息会剥离 thinking/redacted blocks，避免跨模型回放签名

**Prompt Caching**：
- 当前官方 Prompt Caching 文档同时支持顶层自动缓存 `cache_control` 和 block 级显式断点；旧 SDK 快照未暴露顶层字段不代表 API 不支持
- System 消息最后一个 block 标记 `cache_control`（`TextBlockParam.cache_control`）
- Tools 最后一个标记 `cache_control`
- `insertMessagesCacheControl()`：在倒数第二条非 tool_result 的 user message 上插入缓存断点
- `CacheControlEphemeralParam`：`type: "ephemeral"` + `ttl: "5m" | "1h"`（默认 `5m`）

**流式解析**：
- `content_block` + `delta` 合并为 delta message
- `message_stop` 事件终止流
- `[DONE]` 被忽略（兼容处理）

### 4.3 Anthropic 特有的协议特性

| 特性 | 说明 | 项目实现 |
|------|------|----------|
| `signature` 回传 | thinking block 必须携带 signature 回传 | `ClaudeReasoningMetadata.signature` |
| `redacted_thinking` | 加密推理内容（`data` 字段，非 `thinking`） | metadata 持久化并原样回传 |
| Prompt Caching | 顶层自动缓存 + content block 断点 | 两种方式均支持 |
| `anthropic-version` header | 必须携带 | 固定 `2023-06-01` |
| `max_tokens` | 必填参数（`Required[int]`） | 默认 64000 |
| Content block 顺序 | thinking 必须在 text 之前 | `toContentBlock()` 中 Reasoning → Text |
| 内置工具（web_search） | SDK 定义 `web_search_20250305` server tool | 项目未实现 |
| `temperature` 范围 | 0.0~1.0（非 2.0） | 仅在非思考模式发送 |

---

## 五、Google Gemini API 协议

### 5.1 官方协议规范

**Endpoint**: `POST /v1beta/models/{model}:streamGenerateContent?alt=sse`

**认证**：
- AI Studio：`x-goog-api-key` header 或 `?key=` query param
- Vertex AI：`Authorization: Bearer` (API key 或 Service Account OAuth)

**核心请求结构**：
```json
{
  "systemInstruction": {
    "parts": [{"text": "system prompt"}]
  },
  "contents": [
    {"role": "user", "parts": [{"text": "..."}, {"inlineData": {"mimeType": "image/png", "data": "..."}}]},
    {"role": "model", "parts": [
      {"text": "...", "thought": false},
      {"text": "thinking...", "thought": true},
      {"functionCall": {"name": "...", "args": {...}}, "thoughtSignature": "..."}
    ]},
    {"role": "user", "parts": [
      {"functionResponse": {"name": "...", "response": {"result": "..."}}}
    ]}
  ],
  "generationConfig": {
    "temperature": 0.7,
    "maxOutputTokens": 4096,
    "thinkingConfig": {
      "includeThoughts": true,
      "thinkingBudget": 8000,
      "thinkingLevel": "high"
    },
    "responseModalities": ["TEXT", "IMAGE"]
  },
  "tools": [
    {"functionDeclarations": [{"name": "...", "description": "...", "parameters": {...}}]},
    {"googleSearch": {}}
  ],
  "safetySettings": [
    {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "OFF"}
  ]
}
```

**关键协议要求**：
1. **Role 映射**：`user` → user, `model` → assistant, `system` → systemInstruction, `tool` → user（tool 结果在 user 角色内）
2. **Parts 结构**：每条 content 包含 `parts[]`，每个 part 可以是 `text`、`inlineData`、`functionCall`、`functionResponse`
3. **Thought 机制**：
   - `thought: true` 标记推理文本
   - `thoughtSignature` 用于关联推理状态，回传时需携带
   - 草稿图（thought image）也会标记 `thought: true`
4. **Thinking 控制**（SDK 依据：`@google/genai` TS v2.16.0）：
   - `ThinkingConfig` 接口：`includeThoughts?` / `thinkingBudget?` / `thinkingLevel?`
   - Gemini 2.5：`thinkingBudget`（`0` = DISABLED，`-1` = AUTOMATIC，正数控制 token 预算）
   - Gemini 3：`thinkingLevel`（SDK 枚举 `ThinkingLevel`：`MINIMAL`/`LOW`/`MEDIUM`/`HIGH`）
   - `includeThoughts`：是否返回推理内容
   - Gemini 2.5 Pro 不支持 `thinkingBudget: 0`（无法完全关闭）
5. **内置工具**：
   - `googleSearch`：服务端联网搜索
   - `urlContext`：URL 上下文获取
   - 当前官方文档支持内置工具与自定义 function declarations 在同一请求中组合
6. **Safety Settings**：项目默认全部设为 `OFF`
7. **Schema 清理**：代码仅移除当前适配中不使用的 `const`、`exclusiveMaximum`、`exclusiveMinimum`、`format`、`additionalProperties`；官方支持的 `enum` 原样保留
8. **工具关联与签名边界**：
   - API 返回的 `functionCall.id` 必须在 `functionResponse.id` 中原样回传
   - 任意 Part 都可能携带 `thoughtSignature`；Gemini 3 工具步骤缺失签名会返回 400
   - 带签名 Part 不能与相邻 Part 合并；流式响应可能把签名放在空文本 Part 上

### 5.2 项目代码实现

**实现文件**：`GoogleProvider.kt`

**认证**：
- 非 Vertex AI：`x-goog-api-key` header
- Vertex AI + API Key：`?key=` query param
- Vertex AI + Service Account：OAuth Bearer token（通过 `ServiceAccountTokenProvider`）

**Thinking 控制**（按模型系列区分）：
```kotlin
when {
    GEMINI_3_SERIES.match(modelId) → thinkingLevel (minimal/low/medium/high)
    isGeminiPro → AUTO 时不设置，OFF 时不设置（Pro 无法完全关闭）
    else → thinkingBudget (0 关闭, 正数控制)
}
```

**消息构建**：
- `addModelMessage()`：使用 `groupPartsByToolBoundary` 分组
  - Content 组 → `toGooglePart()` 转换
  - Tools 组 → `functionCall` parts + 紧跟 `functionResponse` user 消息
- `GoogleThoughtMetadata` 保存 Part 签名、API function call id、thought 标记和草稿图原始 inlineData
- Text、Reasoning、Image、FunctionCall 均按原 Part 回传 `thoughtSignature`

**Tool 结果**：`functionResponse` 支持 `$ref` 指针绑定多模态数据

**Grounding**：解析 `groundingMetadata` → `UIMessageAnnotation.UrlCitation`

### 5.3 Gemini 特有的协议特性

| 特性 | 说明 | 项目实现 |
|------|------|----------|
| `thoughtSignature` | 任意 Part 的推理状态签名，回传时需携带 | `GoogleThoughtMetadata` + signed Part 合并边界 |
| 草稿图 | `thought: true` 的 `inlineData` | UI 显示占位，metadata 保留原始 inlineData 供回放 |
| `thinkingBudget` vs `thinkingLevel` | 按模型系列区分 | `ModelRegistry.GEMINI_3_SERIES` 判断 |
| Safety Settings | 默认全部 OFF | 硬编码 5 个 category |
| Schema 清理 | 保留官方支持的 `enum` | 仅移除 5 个兼容字段 |
| 工具组合 | 内置工具可与 function calling 共存 | 一次构造同一 `tools` 数组 |
| Function ID | call/response 使用同一 API id | `GoogleThoughtMetadata.functionCallId` |
| `responseModalities` | 支持 IMAGE 输出 | Gemini 2.5 Flash Image 等模型 |
| Vertex AI | 支持 Service Account 认证 | `ServiceAccountTokenProvider` |

---

## 六、模型级适配

### 6.1 ModelRegistry 体系

**实现文件**：`ModelRegistry.kt`

项目通过 `ModelRegistry` 维护了一个模型定义数据库，用于自动推断模型的输入/输出模态和能力。

**模型定义方式**：基于 token 匹配（非精确字符串匹配），支持正则和精确匹配。

**模型能力推断**：
- `inputModalities`：TEXT / IMAGE
- `outputModalities`：TEXT / IMAGE
- `abilities`：TOOL / REASONING

### 6.2 模型级特殊适配

| 模型/系列 | 适配内容 | 代码位置 | 原因 |
|-----------|----------|----------|------|
| **OpenAI o-series** | OFF → `low` 回退（不支持 none） | `ChatCompletionsAPI` / `ResponseAPI` | 旧 o-series 不支持 `reasoning_effort: none` |
| **基础 GPT-5（含 mini/nano）** | OFF→`minimal`，XHIGH→`high` | `mapOfficialOpenAIReasoningEffort()` | 官方范围为 minimal/low/medium/high |
| **GPT-5.1 标准版** | OFF→`none`，XHIGH→`high` | 同上 | 官方范围为 none/low/medium/high |
| **GPT-5.2/5.4/5.5/5.6 标准版** | OFF→`none`，XHIGH→`xhigh` | 同上 | 当前标准点版本支持 xhigh；项目没有 MAX，5.6 的 XHIGH 仍保持 xhigh |
| **GPT-5 Pro / 点版本 Pro** | 基础 Pro 固定 `high`；5.2/5.4/5.5 Pro 下界为 `medium` | 同上 | Pro 的 effort 范围与标准版不同 |
| **GPT-5 Codex / Chat** | 5.2/5.3 Codex OFF→`low` 且可用 `xhigh`；旧 Codex 保守封顶 `high`；Chat 省略 effort | 同上 | Codex 不支持 none；Chat 型号未声明 reasoning effort |
| **GPT-5 / o-series** | 不发送 temperature | `isModelAllowTemperature()` | 思考模式 temperature 不生效 |
| **GPT-5 / o-series** | system → `developer` role | `useDeveloperRoleForSystemMessages` | OpenAI 官方要求 |
| **DeepSeek V4** | 工具续轮强制回传 reasoning_content | `requiresDeepSeekToolReasoningReplay()` | DeepSeek 协议硬性要求 |
| **DeepSeek V4 (NVIDIA)** | effort 特殊映射（LOW/MEDIUM/HIGH→`high`，XHIGH→`max`，OFF→`none`） | `ChatCompletionsAPI` NVIDIA 分支 | NVIDIA 上的 DeepSeek V4 只接受 `high`/`max`/`none` |
| **Moonshot K2.5/K3** | 不发送 temperature | `isModelAllowTemperature()` | K2.5/K3 不支持 temperature |
| **Moonshot K2.6** | `thinking.keep = "all"` | `ChatCompletionsAPI` Moonshot 分支 | K2.6 需显式传 keep 才是保留式思考 |
| **Gemini 2.5 Pro** | OFF 时不设置 thinkingBudget | `GoogleProvider` | Pro 无法完全关闭思考 |
| **Gemini 3 系列** | 使用 `thinkingLevel` 而非 `thinkingBudget` | `GoogleProvider` | Gemini 3 API 变更 |
| **SiliconFlow 模型** | 模型白名单控制 `enable_thinking`（25 个模型 ID） | `ChatCompletionsAPI` SiliconFlow 分支 | 只有特定模型支持该参数 |

### 6.3 ModelRegistry 中的模型分组

项目注册的主要模型组：

| 分组 | 包含模型 | 用途 |
|------|----------|------|
| `OPENAI_O_MODELS` | o1, o3, o4 等 | reasoning_effort 控制 + developer role |
| `GPT_5` | 基础 gpt-5（排除带点号/chat 变体） | 基础模型能力推断；其 effort 下界为 `minimal` |
| `OPENAI_GPT_5_SERIES` | `GPT_5` 与 GPT-5.1～5.6 的独立定义 | 只复用 developer role 与 temperature 行为；不代表各成员的 effort 范围相同 |
| `DEEPSEEK_V4` | deepseek-v4-flash, deepseek-v4-pro | reasoning_content 回传 + NVIDIA 特殊映射 |
| `GEMINI_3_SERIES` | gemini-3-pro, gemini-3-flash, gemini-3.5 等 | thinkingLevel 控制 |
| `KIMI_K2_5` / `KIMI_K3` / `KIMI_K3_ALIAS` | kimi-k2.5, kimi-k3, 裸 ID `k3` | temperature 不支持（`KIMI_K3_ALIAS` 兼容不带 kimi 前缀的裸 ID） |
| `KIMI_K2_6` | kimi-k2.6 | thinking.keep = "all" |

---

## 七、代码架构与职责分析

### 7.1 整体架构

```
用户配置层
  ProviderSetting (sealed class)
    ├── OpenAI  (baseUrl, apiKey, chatCompletionsPath, useResponseApi, includeHistoryReasoning)
    ├── Google  (baseUrl, apiKey, vertexAI, useServiceAccount, ...)
    └── Claude  (baseUrl, apiKey, promptCaching, promptCacheTtl)

Provider 管理层
  ProviderManager
    ├── registerProvider("openai", OpenAIProvider)
    ├── registerProvider("google", GoogleProvider)
    └── registerProvider("claude", ClaudeProvider)

Provider 实现层
  OpenAIProvider : Provider<OpenAI>
    ├── ChatCompletionsAPI : OpenAIImpl  ← useResponseApi = false
    └── ResponseAPI         : OpenAIImpl  ← useResponseApi = true
  GoogleProvider : Provider<Google>
  ClaudeProvider : Provider<Claude>

中间表示层
  UIMessage (provider-agnostic)
    ├── parts: List<UIMessagePart>  (Text, Image, Reasoning, Tool, ...)
    ├── metadata: PartMetadata      (ClaudeReasoningMetadata, OpenAIReasoningMetadata, GoogleThoughtMetadata)
    └── providerMetadata: MessageMetadata (OpenAIResponseMetadata)

模型注册表
  ModelRegistry
    └── ALL_MODELS: List<ModelDefinition>  (~80 个模型定义)
```

### 7.2 职责分离

| 组件 | 职责 | 不做什么 |
|------|------|----------|
| `Provider` 接口 | 定义文本生成/流式/嵌入/图像生成的统一接口 | 不处理协议细节 |
| `ProviderManager` | 根据 ProviderSetting 类型路由到对应 Provider | 不处理协议细节 |
| `OpenAIProvider` | 委托给 ChatCompletionsAPI 或 ResponseAPI；处理模型列表、余额、嵌入、图像生成 | 不处理 Chat Completions / Responses 的线协议 |
| `ChatCompletionsAPI` | Chat Completions 请求构建、消息序列化、响应解析、流式处理 | 不处理 Responses API |
| `ResponseAPI` | Responses 请求构建、无状态回放、按已解析 profile 序列化、流式处理 | 不处理 Chat Completions；不自行解析供应商身份 |
| `OpenAIEndpointProfile` | host 到稳定 endpoint vendor、Responses profile 与有限参数映射的唯一来源 | 不猜测未知代理后端；不维护在线模型白名单 |
| `ClaudeProvider` | Anthropic Messages 的完整请求/响应/流式处理 | 不处理 OpenAI 协议 |
| `GoogleProvider` | Gemini 的完整请求/响应/流式处理 | 不处理 OpenAI 协议 |
| `ProviderMessageUtils` | `groupPartsByToolBoundary` 共享工具 | 不包含协议特定逻辑 |
| `UIMessage` | provider-agnostic 消息抽象 + 流式合并 | 不包含协议特定序列化 |
| `MessageMetadata` / `PartMetadata` | 类型化保存 UI 无法无损表达、但续轮必须保留的协议状态 | 不承载用户配置或 UI 展示内容 |
| `ModelRegistry` | modelId 到模型能力与共享行为组的匹配 | 不决定 endpoint wire format |

### 7.3 共享中间表示：UIMessage

`UIMessage` 是所有协议的统一中间表示，核心设计：

**Parts 列表**：按顺序保存 Text、Reasoning、Tool、Image 等 part
- **Tool 作为步骤边界**：已执行的 Tool（`isExecuted = true`）分隔不同的 assistant 步骤
- 所有 Provider 都使用 `groupPartsByToolBoundary()` 进行消息重建

**Metadata 分层**：
```
Metadata (顶层接口)
  ├── PartMetadata (part 级别)
  │     ├── ClaudeReasoningMetadata (signature, redactedData)
  │     ├── OpenAIReasoningMetadata (reasoningId, encryptedContent, sourceProfile)
  │     ├── GoogleThoughtMetadata (thoughtSignature, functionCallId, thought, inlineData)
  │     └── DiffMetadata (diff)
  └── MessageMetadata (消息级别)
        └── OpenAIResponseMetadata (wireFormat, sourceProfile, outputItemGroups)
```

**流式合并**：`appendChunk()` 引入 currentStep 概念
- 确保同一 assistant 步骤内 `Reasoning → Content → pending Tool(s)` 顺序
- 不修改已执行 Tool 之前的历史步骤

### 7.4 协议适配原则

项目坚持的 7 条协议适配原则：

1. **host 驱动**：协议形状由 endpoint host 决定，不由 modelId 猜测
   - modelId 决定模型能力，但不能猜测代理背后的供应商
   - 例外：`requiresDeepSeekToolReasoningReplay` 通过 modelId 判断代理场景
2. **最小配置**：不暴露 ProviderDialect 或网关类型给用户
3. **枚举约束**：`ResponseEndpointProfile` 使用枚举，避免无效状态组合
4. **原始保真**：保存服务端原始 JSON（`OpenAIResponseMetadata`），不因 UI 投影丢失字段
5. **官方依据**：新增适配必须有官方文档依据和回归测试
6. **不跨协议/来源**：`wireFormat` 与 `sourceProfile` 双重检查，避免在 OpenAI、兼容网关、方舟、DeepSeek 之间原样回放不透明 JSON
7. **正交判断**：Provider 类型决定原生协议族，`useResponseApi` 决定 OpenAI 子协议，host 决定已证实的 endpoint 差异，modelId 只决定模型能力和参数约束

### 7.5 结构收敛与拆分边界

当前实现有意保持四个线协议实现单元，只增加一个 OpenAI endpoint 身份文件：

```text
ai/.../provider/providers/
├── openai/
│   ├── OpenAIEndpointProfile.kt   # host 身份、Responses profile、有限参数映射
│   ├── ChatCompletionsAPI.kt      # Chat Completions 协议主体
│   └── ResponseAPI.kt             # Responses 协议主体
├── GoogleProvider.kt              # Gemini 协议主体
├── ClaudeProvider.kt              # Anthropic Messages 协议主体
└── ProviderMessageUtils.kt        # 仅共享 provider-neutral 工具边界分组
```

该边界同时约束以下非目标：

- 不新增 ProviderDialect、供应商类型等用户配置或持久化字段。
- 不根据 modelId 猜测未知代理的 wire format；未知 host 保持 `OPENAI_COMPATIBLE` 默认。
- 不为每个 host 创建策略类，也不合并四协议的请求/响应 DTO。
- 不把易变在线模型列表变成客户端硬阻断白名单。
- 暂不继续拆分 request builder、stream parser、tool adapter。只有组件可独立测试、至少被两处复用且边界稳定时才继续拆分。

因此，endpoint 身份、模型行为、wire 序列化和不可见续轮状态各有唯一归属，同时避免把协议主体切成大量只承载单个分支的小文件。

---

## 八、四协议对比矩阵

### 8.1 推理（Thinking/Reasoning）对比

| 维度 | Chat Completions | Responses API | Anthropic Messages | Gemini API |
|------|-----------------|---------------|---------------------|------------|
| **控制参数** | `reasoning_effort` / `thinking.type`（扩展） | `reasoning.effort` / `reasoning.summary` | `thinking.type` + `output_config.effort` | `thinkingConfig.thinkingBudget` / `thinkingLevel` |
| **OFF 语义** | 按模型映射：基础 GPT-5→`minimal`、已核实支持的标准点版本→`none`、Pro/Codex/o-series→各自下界 | 同左；DeepSeek Responses OFF 省略 effort，但不能保证关闭 thinking | `thinking.type: disabled` | `thinkingBudget: 0` / `thinkingLevel: minimal` |
| **推理输出字段** | `reasoning_content`（扩展） | `reasoning` item 的 `summary` / `content` | `thinking` content block | `thought: true` 的 text part |
| **状态回传** | `reasoning_content`（DeepSeek 强制） | `encrypted_content` / `reasoning_text` | `signature` | `thoughtSignature` |
| **effort 级别**（协议） | 按 OpenAI 模型代际；DeepSeek high/max | 按 endpoint；DeepSeek high/max | low/medium/high/xhigh/max | 0-N（budget）/ minimal/low/medium/high（level） |
| **effort 级别**（项目枚举） | OFF(none)/AUTO/LOW/MEDIUM/HIGH/XHIGH — 无 minimal/max；XHIGH 在支持型号上发送 `xhigh` | 同左 | 同左；effort 直传枚举值 | 同左；XHIGH 映射为 high |

### 8.2 工具调用对比

| 维度 | Chat Completions | Responses API | Anthropic Messages | Gemini API |
|------|-----------------|---------------|---------------------|------------|
| **工具定义** | `tools[].function.parameters` | `tools[].parameters`（扁平） | `tools[].input_schema` | `tools[].functionDeclarations[].parameters` |
| **工具调用** | `tool_calls[]` 在 assistant 消息内 | 独立 `function_call` item | `tool_use` content block | `functionCall` part |
| **工具结果** | `role: "tool"` 独立消息 | 独立 `function_call_output` item | `tool_result` block（user 消息内） | `functionResponse` part（user 消息内） |
| **结果格式** | 文本 / 多模态数组 | 字符串 / 多模态数组 | content blocks 数组 | `response` 对象（含 `result`） |
| **并行工具** | `tool_calls[].index` 关联 | 多个 `function_call` items | 多个 `tool_use` blocks | 多个 `functionCall` parts |
| **strict 模式** | 默认非严格 | 默认严格（项目显式 `false`） | 不适用 | 不适用 |
| **内置工具** | `web_search_options`（SDK 已定义） | `web_search` / `image_generation` | `web_search`（server tool） | `googleSearch` / `urlContext` |

### 8.3 流式协议对比

| 维度 | Chat Completions | Responses API | Anthropic Messages | Gemini API |
|------|-----------------|---------------|---------------------|------------|
| **SSE 格式** | `data: {json}` | `event: type\ndata: {json}` | `event: type\ndata: {json}` | `data: {json}` |
| **终止信号** | `data: [DONE]` | `response.completed` 事件 | `message_stop` 事件 | 连接自然关闭 |
| **delta 结构** | `choices[0].delta` | 按 event type 不同 | `content_block_delta` / `delta` | `candidates[0].content` |
| **usage 时机** | 最后一个 chunk | `response.completed` 事件 | `message_delta` 事件 | 每个 chunk 的 `usageMetadata` |
| **错误处理** | 200 内嵌 `error` 字段 | `response.failed` 事件 | `error` 事件 | `promptFeedback.blockReason` |
| **工具参数流式** | `tool_calls[].function.arguments` delta | `function_call_arguments.delta` | `input_json_delta` | 无（非流式参数） |

### 8.4 认证对比

| 协议 | 认证方式 | 额外 header |
|------|----------|-------------|
| Chat Completions | `Authorization: Bearer {key}` | — |
| Responses API | `Authorization: Bearer {key}` | — |
| Anthropic Messages | `x-api-key: {key}` | `anthropic-version: 2023-06-01` |
| Gemini API (AI Studio) | `x-goog-api-key: {key}` | — |
| Gemini API (Vertex AI) | `Authorization: Bearer {oauth}` 或 `?key={key}` | — |

---

## 九、0.0.13 核心目标重审

### 9.1 从架构角度审视

0.0.13 的核心目标可以归纳为：**在四协议体系下，确保推理状态在工具调用续轮中正确回传**。

具体涉及三个层面：

1. **UIMessage 层**：流式合并的步骤边界规范化
   - 引入 `currentStep` 概念，确保 `Reasoning → Content → pending Tool(s)` 顺序
   - 修改 `appendChunk()` 的插入策略，不篡改已执行 Tool 之前的历史

2. **Chat Completions 层**：DeepSeek reasoning_content 的正确回传
   - `requiresDeepSeekToolReasoningReplay()` 精准识别需要回传的场景
   - 工具步骤强制回传，末尾普通回答遵守 `includeHistoryReasoning`
   - 并行工具 `tool_calls[].index` 关联

3. **Responses API 层**：无状态协议的完整状态回放
   - `ResponseEndpointProfile` 枚举替代布尔组合
   - `OpenAIResponseMetadata` 持久化原始 output items
   - `wireFormat` 匹配检查避免跨协议发送
   - DeepSeek `reasoning_text` 格式兼容

### 9.2 从职责角度审视

0.0.13 修复中各组件的职责变化：

| 组件 | 修复前职责 | 修复后新增/变更 |
|------|-----------|----------------|
| `UIMessage.appendChunk` | 简单按 delta 到达顺序追加 | 引入 currentStep 步骤规范化 |
| `ChatCompletionsAPI` | 基础请求/响应处理 | 并行工具状态、part 顺序、DeepSeek 回传 |
| `ResponseAPI` | 基础 Responses 处理 | endpoint profile、无状态回放、reasoning_text 事件 |
| `MessageMetadata` | 无 | 新增 `OpenAIResponseMetadata` 消息级元数据 |
| `PartMetadata` | 基础 metadata | 新增 `OpenAIReasoningMetadata` |
| `ModelRegistry` | 模型能力推断 | 新增 `DEEPSEEK_V4` 等模型组用于代理场景判断 |

### 9.3 协议适配的"正确性"标准

0.0.13 确立了一个重要原则：**协议适配的正确性不等于功能对等，而是线协议保真**。

- Chat Completions 的 `reasoning_content` 是 DeepSeek 扩展，不是 OpenAI 官方字段
- Responses API 的 `reasoning_text` 是 DeepSeek 扩展，不是 OpenAI 官方的 `summary_text`
- Anthropic 的 `signature` 是协议状态，不是可选的元数据
- Gemini 的 `thoughtSignature` 是协议状态，不是可选的元数据

每个协议的推理状态回传机制都是**不同的**，不能用统一的"includeHistoryReasoning"策略覆盖所有场景。

### 9.4 0.0.13 架构续补

本轮 0.0.13 内部整理在既有协议保真基础上补齐“身份、模型行为、不可见状态”三条边界：

- `OpenAIEndpointProfile.kt` 成为 OpenAI-compatible host 身份和 Responses profile 的单一来源。
- `OPENAI_GPT_5_SERIES` 负责点版本的共享协议行为，不改变各模型自身能力定义。
- Responses 消息级 output 状态与 Part 级 reasoning 状态都增加 `sourceProfile`，相同 wire format 不再等同于可跨供应商回放。
- Gemini 保存任意 Part 的签名、function call id 与草稿图原始状态，并允许 function 与内置工具组合。
- Claude 保存 `redacted_thinking.data`，且不会把已知属于另一模型配置的 thinking 签名发送给当前模型。

### 9.5 实施自审、回归矩阵与验证基线

实施阶段没有引入按 host 拆类或统一四协议 DTO，并在自审后完成五项收敛：

1. 未知网关使用 `OPENAI_COMPATIBLE`，不把“形状兼容”写成“OpenAI 官方”。
2. metadata 合并硬边界只保留 Gemini 签名/原始图像、Claude redacted data 等不透明状态，普通 OpenAI/DeepSeek reasoning metadata 仍可合并，避免消息无谓碎片化。
3. DeepSeek V4 effort 按官方 high/max 约束映射；Responses OFF 只省略 effort，不声称已关闭 thinking。
4. Responses 来源边界同时落在消息级 output metadata 和 Part 级 reasoning metadata，避免回退重建时跨来源复用 id/encrypted content。
5. GPT-5 共享 developer role/temperature 行为与 effort 能力分离，基础版、点版本、Pro、Codex、Chat 分别映射。

长期回归矩阵如下；协议行为发生变化时应同步更新这些测试，而不是只验证请求可以编译：

| 范围 | 必须覆盖的行为 |
|---|---|
| endpoint profile | 已知 host、未知 host 默认值、Chat/Responses 共用身份 |
| OpenAI 模型 | GPT-5 各代 developer role、temperature 与 effort 特例，避免 `gpt-5.10` 误匹配 `gpt-5.1` |
| DeepSeek | OFF/AUTO/LOW/MEDIUM/HIGH/XHIGH 映射与工具 reasoning 回放 |
| Responses metadata | 同来源累积、跨来源隔离、旧 metadata 兼容、完整 output 批次和工具边界 |
| Gemini | function 与内置工具共存、enum、function id、各类 Part signature、草稿图与 signed Part 合并边界 |
| Claude | thinking signature、redacted data 原样回放、跨模型 stripping |
| 通用回归 | 多工具、并行工具、流式终态、错误传播、token usage |

2026-08-09 本地串行验证基线：

- `:ai:testDebugUnitTest`：195 项测试，0 失败、0 错误、0 跳过。
- 全仓 `test`：522 项测试，0 失败、0 错误、9 跳过。
- `lint`：成功；345 个任务中 37 个执行、308 个 up-to-date。
- `assembleDebug`：成功；生成 arm64-v8a、x86_64、universal 三个 Debug APK，包版本保持 `versionCode=13`、`versionName=0.0.13`。
- `assembleRelease`：成功；391 个任务中 38 个执行、353 个 up-to-date。三个 Release APK 均通过 `apksigner`（v2、单一签名者）和 `aapt` 复核，包版本均为 `versionCode=13`、`versionName=0.0.13`。
- `git diff --check`：无空白错误，仅有工作区 LF/CRLF 转换提示。

上述结果只证明本地编译、静态检查和离线回归；本次没有使用真实供应商账号或设备仪器测试，不能据此证明账号权限、地区开放、服务端灰度、计费/限流或未知兼容网关的真实行为。

---

## 十、后续建议与风险

### 10.1 已知风险

| 风险 | 严重度 | 说明 |
|------|--------|------|
| SiliconFlow 模型白名单过期 | 低 | 25 个硬编码模型 ID，新模型上线需手动更新 |
| `providerMetadata` 持久化增长 | 低 | `OpenAIResponseMetadata.outputItemGroups` 在长对话中累积 |
| DeepSeek V4 Pro Responses | 观察 | 2026-08-09 官方页仍写“暂不支持”，同时保留“8 月初增加支持”的过期预告；客户端不硬编码阻断白名单 |
| DeepSeek Responses OFF | 中 | 官方兼容表只说明 `reasoning.effort`，未记录关闭 thinking 的字段；项目对 OFF 省略 effort，不能保证真正关闭思考 |
| 未知兼容网关状态来源 | 低 | `OPENAI_COMPATIBLE` sourceProfile 把未知网关视为同一兼容族；切换未知网关时仍需依赖服务端校验 |
| GPT-5.6 新 reasoning 控制 | 观察 | 当前官方指南已有 `reasoning.context` 与 `reasoning.mode: "pro"`，但锁定的 SDK 快照及项目请求模型尚未覆盖；在实现前必须按具体模型和 SDK 版本处理，不能生成 `gpt-5.6-pro` slug |
| Anthropic `web_search` server tool 未实现 | 低 | SDK 定义了 `web_search_20250305` server tool，项目未实现该内置工具 |
| Chat Completions `web_search_options` 未实现 | 低 | SDK 定义了 `web_search_options` 参数，项目未实现该内置工具 |

### 10.2 架构改进方向

| 方向 | 时机 | 说明 |
|------|------|------|
| endpoint vendor 继续扩展 | 出现新的已证实供应商差异时 | 统一加入 `OpenAIEndpointProfile.kt`，不在协议主体散落 host 字符串 |
| `providerMetadata` 保留窗口 | 出现性能问题时 | 只保留最近 N 轮原始 output |
| GPT-5.6 persisted reasoning / pro mode | SDK 与产品需求稳定后 | 分别评估 `reasoning.context` 和 `reasoning.mode`，并保持与完整 output 回放、项目 ReasoningLevel 的边界 |
| 流式合并端到端示例注释 | 低优先级 | 在 `appendChunk` 中补充典型 delta 序列 |

### 10.3 协议演进观察

| 协议 | 演进趋势 | 项目关注点 |
|------|----------|------------|
| Chat Completions | 逐步被 Responses API 替代 | 维持兼容服务支持 |
| Responses API | OpenAI 主推方向 | 关注 `reasoning.summary`、`conversation`，以及 GPT-5.6 的 `reasoning.context` / `reasoning.mode` 等模型限定能力 |
| Anthropic Messages | adaptive 模式 + output_config | 关注旧 `type=enabled` 的弃用时间线 |
| Gemini API | thinkingLevel 替代 thinkingBudget | 关注 Gemini 3+ 的统一控制方式 |

---

## 附录 A：协议参考来源

| 来源 | URL | 用途 |
|------|-----|------|
| DeepSeek Thinking Mode | `https://api-docs.deepseek.com/guides/thinking_mode` | reasoning_content 回传规则、effort 映射 |
| DeepSeek Responses API | `https://api-docs.deepseek.com/zh-cn/guides/responses_api` | Responses 兼容性明细 |
| OpenAI Chat Completions API | `https://platform.openai.com/docs/api-reference/chat` | 官方协议规范 |
| OpenAI Responses API | `https://platform.openai.com/docs/api-reference/responses` | 官方协议规范 |
| OpenAI GPT-5 / GPT-5.1 / GPT-5.4 / GPT-5.6 | `https://developers.openai.com/api/docs/models/gpt-5`<br>`https://developers.openai.com/api/docs/models/gpt-5.1`<br>`https://developers.openai.com/api/docs/models/gpt-5.4`<br>`https://developers.openai.com/api/docs/guides/latest-model` | 标准模型 reasoning effort 范围 |
| OpenAI GPT-5 Pro / GPT-5.4 Pro / GPT-5.3 Codex | `https://developers.openai.com/api/docs/models/gpt-5-pro`<br>`https://developers.openai.com/api/docs/models/gpt-5.4-pro`<br>`https://developers.openai.com/api/docs/models/gpt-5.3-codex` | 特例模型 reasoning effort 范围 |
| OpenAI Python SDK | `openai-python` GitHub | ResponseReasoningItem / Reasoning 结构 |
| Anthropic Messages API | `https://docs.anthropic.com/en/api/messages` | 官方协议规范 |
| Anthropic Thinking | `https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking` | thinking 控制方式 |
| Gemini API | `https://ai.google.dev/api/generate-content` | 官方协议规范 |
| Gemini Thinking | `https://ai.google.dev/gemini-api/docs/thinking` | thinkingConfig 控制 |
| Gemini Thought Signatures | `https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures` | Part 签名与回传约束 |
| Gemini Function Calling | `https://ai.google.dev/gemini-api/docs/generate-content/function-calling` | function id 与 schema |
| Anthropic Prompt Caching | `https://platform.claude.com/docs/en/build-with-claude/prompt-caching` | 顶层自动缓存与 block 断点 |
| 火山方舟 Responses API | `https://www.volcengine.com/docs/82379/1956279` | 方舟 Responses 深度思考 |
| 火山方舟 Responses 工具调用 | `https://www.volcengine.com/docs/82379/1958524` | call_id 与工具结果 |

## 附录 B：代码文件索引

| 文件 | 职责 |
|------|------|
| `Provider.kt` | Provider 接口 + 参数数据类 |
| `ProviderSetting.kt` | 三种 Provider 配置（OpenAI/Google/Claude） |
| `ProviderManager.kt` | Provider 注册与路由 |
| `OpenAIProvider.kt` | OpenAI Provider 委托层 |
| `openai/OpenAIEndpointProfile.kt` | endpoint vendor、Responses profile、有限参数映射 |
| `openai/ChatCompletionsAPI.kt` | Chat Completions 完整实现 |
| `openai/ResponseAPI.kt` | Responses API 完整实现 |
| `ClaudeProvider.kt` | Anthropic Messages 完整实现 |
| `GoogleProvider.kt` | Gemini API 完整实现 |
| `ProviderMessageUtils.kt` | groupPartsByToolBoundary 共享工具 |
| `ModelRegistry.kt` | 模型定义、能力和行为组 |
| `Message.kt` | UIMessage 中间表示 + 流式合并 |
| `MessageMetadata.kt` | Metadata 分层体系 |
| `UIMessagePart.kt` | Part 类型定义 |
| `Reasoning.kt` | ReasoningLevel 枚举 |
| `Model.kt` | Model 数据类 + ModelAbility/Modality |

---

## 附录 C：2026-08-07 SDK 核对与 2026-08-09 在线文档复核

### C.1 已修正的文档错误

| # | 位置 | 修正前 | 修正后 | 依据 |
|---|------|--------|--------|------|
| 1 | §2.1 第 4 点 | `reasoning_effort: none/minimal/low/medium/high/xhigh/max`（7 值混合） | SDK 快照只定义 4 值；当前在线文档按具体模型给出不同范围，项目不再把 GPT-5 全系列视为同一能力集 | `openai-python` v2.7.1 + OpenAI 当前模型页 |
| 2 | §2.1 内置工具 | Chat Completions 内置工具“无” | SDK 定义 `web_search_options` 参数 | `openai-python` v2.7.1 `CompletionCreateParamsBase.web_search_options` |
| 3 | §3.1 请求示例 | 把 `reasoning.context` 当作通用 Responses 字段 | 2026-08-07 的 SDK 快照尚无该字段，因此不放入通用示例；当前 GPT-5.6 官方指南已支持，文档改为明确 SDK/模型版本边界 | `openai-python` v2.7.1 + OpenAI GPT-5.6 当前指南 |
| 4 | §3.1 reasoning item | `summary` 只支持 `auto` | SDK 定义为 `auto`/`concise`/`detailed` | `openai-python` v2.7.1 `Reasoning.summary` |
| 5 | §4.1 Thinking 控制 | 旧/新 API 二分法 | 三类型：`enabled`+`budget_tokens`/`adaptive`+`display`/`disabled`；`display` 支持 `summarized`/`omitted` | `@anthropic-ai/sdk` TS v0.115.0 `ThinkingConfigParam` |
| 6 | §4.1 effort 值 | `low/medium/high/max` | TS SDK 定义 5 值 `low/medium/high/xhigh/max`（无 `none`） | `@anthropic-ai/sdk` TS v0.115.0 `OutputConfig.effort` |
| 7 | §4.1 内置工具 | Anthropic 内置工具“无” | SDK 定义 `web_search_20250305` server tool | `anthropic-python` v0.72.0 `WebSearchTool20250305Param` |
| 8 | §4.2 Prompt Caching | SDK 快照未定义顶层 `cache_control` | 2026-08-09 官方文档确认顶层自动缓存与 block 断点均受支持 | Anthropic Prompt Caching 官方文档 |
| 9 | §5.1 Thinking 控制 | `thinkingBudget`（0 关闭） | SDK：`0` = DISABLED，`-1` = AUTOMATIC | `@google/genai` TS v2.16.0 `ThinkingConfig` |
| 10 | §8.1 effort 行 | 混合协议值与项目值 | 拆分“协议”和“项目枚举”两行，SDK 值精确化 | 多个 SDK 源码 |
| 11 | §8.2 内置工具行 | Chat Completions 和 Anthropic 均为“无” | 分别标注 `web_search_options` 和 `web_search` | OpenAI + Anthropic SDK |
| 12 | §10.3 演进观察 | 仅依据旧 SDK 删除 `reasoning.context`/`reasoning.mode` | 当前 GPT-5.6 官方指南已同时记录 persisted reasoning 与 pro mode；恢复为模型限定的演进项，不宣称项目已实现 | OpenAI GPT-5.6 当前指南 |

### C.2 SDK 核对确认正确的描述

| 描述 | 验证来源 |
|------|----------|
| OpenAI Chat Completions 角色体系 `developer/system/user/assistant/tool/function` | `openai-python` v2.7.1 `ChatCompletionRole` |
| OpenAI `max_completion_tokens` 替代 `max_tokens`（deprecated） | `openai-python` v2.7.1 `CompletionCreateParamsBase` |
| OpenAI Responses `ResponseIncludable` 支持 8 种值 | `openai-python` v2.7.1 `ResponseIncludable` 类型 |
| OpenAI Responses `ResponseStatus` 支持 6 种状态 | `openai-python` v2.7.1 `ResponseStatus` 类型 |
| Anthropic `thinking` block 结构（`signature`+`thinking`+`type`） | `anthropic-python` v0.72.0 `ThinkingBlock` |
| Anthropic `redacted_thinking` block 结构（`data`+`type`） | `anthropic-python` v0.72.0 `RedactedThinkingBlock` |
| Anthropic `max_tokens` 为必填 (`Required[int]`) | `anthropic-python` v0.72.0 `MessageCreateParamsBase` |
| Anthropic `temperature` 范围 0.0~1.0 | `anthropic-python` v0.72.0 `MessageCreateParamsBase.temperature` |
| Anthropic `adaptive` thinking 类型 + `display` 字段 | `@anthropic-ai/sdk` TS v0.115.0 `ThinkingConfigAdaptive` |
| Anthropic `output_config.effort` 5 值 `low/medium/high/xhigh/max` | `@anthropic-ai/sdk` TS v0.115.0 `OutputConfig.effort` |
| Anthropic `CacheControlEphemeralParam` 支持 `ttl: "5m"/"1h"` | `anthropic-python` v0.72.0 `CacheControlEphemeralParam` |
| Gemini `ThinkingConfig` 三字段 `includeThoughts`/`thinkingBudget`/`thinkingLevel` | `@google/genai` TS v2.16.0 `ThinkingConfig` |
| Gemini `ThinkingLevel` 枚举 4 值 `MINIMAL/LOW/MEDIUM/HIGH` | `@google/genai` TS v2.16.0 `ThinkingLevel` |
| Gemini `thoughtSignature` 在 `Part` 接口上 | `@google/genai` TS v2.16.0 `Part.thoughtSignature` |
| Gemini `HarmCategory` 包含 5+ 个分类 | `@google/genai` TS v2.16.0 `HarmCategory` 枚举 |
| Gemini 内置工具 `google_search`/`url_context` | `@google/genai` TS v2.16.0 Blob/BlobData 注释 |
| DeepSeek Responses `store` 恒为 `false` | DeepSeek 官方文档 + `ResponseAPI.kt:269` |
| DeepSeek Responses 不支持 `include`/`truncation`/`stream_options` | DeepSeek 官方文档兼容性明细表 |
| DeepSeek Responses 仅支持 `deepseek-v4-flash` | DeepSeek 官方文档 |
| DeepSeek 工具续轮必须回传 `reasoning_content` | DeepSeek 官方文档 Tool Calls 节 |
| `ResponseEndpointProfile` 的 4 个来源 profile | `OpenAIEndpointProfile.kt` |
| `resolveResponseEndpointProfile` 只用 host | `OpenAIEndpointProfile.kt` |
| Claude `redacted_thinking` 持久化与回传 | `ClaudeProvider.kt` + 回归测试 |
| Gemini 认证三种方式 | `GoogleProvider.kt:96-115` |
| Gemini Safety Settings 5 个 category 全 OFF | `GoogleProvider.kt:476-497` |
| Gemini 2.5 Pro OFF 时不设置 thinkingBudget | `GoogleProvider.kt:391-398` |
| ALL_MODELS 79 个模型定义 | `ModelRegistry.kt:499-579` 逐行计数 |

### C.3 2026-08-09 已解决的存疑项

| 描述 | 复核结论 |
|------|----------|
| OpenAI GPT-5 `reasoning_effort: "none"` | 重新核实后确认基础 GPT-5 不支持；项目已改为 OFF→`minimal`。`none` 只用于 GPT-5.1 及明确支持它的后续标准点版本 |
| Anthropic 顶层 `cache_control` | 当前官方 Prompt Caching 文档明确支持自动缓存 |
| 火山方舟 Responses | 已核实语义事件、call_id、字符串工具结果、summary/encrypted_content 行为 |
| Gemini `enum` 与工具组合 | 当前官方文档确认支持；代码已保留 enum 并合并 tools 数组 |
| Gemini function id / thought signature | 当前官方文档确认必须精确回传；代码和测试已覆盖 |
| OpenAI `reasoning.context` / `reasoning.mode` | 当前 GPT-5.6 指南确认两者存在；前者控制 persisted reasoning 范围，后者用 `pro` 模式增强同一模型，不对应独立 Pro slug。项目当前未发送，列为演进项 |
