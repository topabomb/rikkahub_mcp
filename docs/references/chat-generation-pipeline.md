# 消息生成链路

> 本文档以 Measix Pilot 当前代码为准，说明从用户发送消息到模型回复落盘的完整数据流。
> 文档结构参考上游 RikkaHub 的
> [`chat-generation-pipeline.md`](https://github.com/rikkahub/rikkahub/blob/master/docs/references/chat-generation-pipeline.md)，
> 但包名、工具集合、通知解耦、Workspace、MCP 生命周期和上下文策略均按本 Fork 的最终实现重写。
> 上下文取舍与后续演进见 [`docs/dev/context-management.md`](../dev/context-management.md)。

## 核心职责

| 类型 | 职责 |
|------|------|
| `ChatService` | 会话入口与编排；选择助手/模型，装配 Transformer、Tools、Memory 和 Workspace 上下文 |
| `ConversationSession` | 单会话内存状态、引用计数、生成 Job 和处理状态 |
| `GenerationHandler` | 构建请求、驱动最多 256 步的模型/工具循环、合并流式输出 |
| `InputMessageTransformer` | 请求发送前，以固定顺序变换消息 |
| `OutputMessageTransformer` | 流式显示、持久化变换和生成结束后的收口处理 |
| Provider 实现 | 把统一的 `UIMessage` / `TextGenerationParams` 转为 OpenAI、Claude、Google 等协议 |

## 主链路

```text
用户发送
  │
  ▼
ChatService.sendMessage()
  ├─ 取消并等待上一生成 Job
  ├─ finishInterruptedPendingTools()
  ├─ preprocessUserInputParts()：执行 USER 范围正则
  ├─ 追加 UIMessage(USER) 到 Conversation.messageNodes
  └─ handleMessageComplete()
       │
       ├─ 解析当前 Assistant 与可用 CHAT Model
       ├─ 校验消息与工具能力
       ├─ 装配 Input/Output Transformers
       ├─ 装配 Search / Local / Conversation / Workspace / Skill / MCP Tools
       └─ GenerationHandler.generateText()
            │
            ├─ 每个 step 构建 Memory Tools
            ├─ 若没有待恢复的工具，进入 generateInternal()
            │    ├─ limitContext()：可选的请求级阶梯裁剪
            │    ├─ 构建 System：助手提示 + Memory + Tool systemPrompt
            │    ├─ 顺序执行 Input Transformers
            │    ├─ 构建 TextGenerationParams
            │    └─ Provider streamText() / generateText()
            │
            ├─ 合并 chunk，执行 Output Transformers 并持续 emit
            ├─ 结束当前 step，写入 finishedAt
            ├─ 无工具调用：结束
            ├─ 工具待审批：写入 Pending 后暂停
            └─ 工具可执行：执行并把结果写回同一 ASSISTANT UIMessage，进入下一 step
                 │
                 ▼
ChatService.collect()
  ├─ 更新 ConversationSession
  ├─ 通过 AppEventBus 发布生成进度、声音和通知事件
  └─ Flow 完成后保存会话，异步生成标题和建议回复
```

工具结果在统一模型中内联于 `UIMessagePart.Tool.output`，不会作为持久化的
`MessageRole.TOOL` 消息单独插入；各 Provider 在序列化请求时再展开为自己的 tool call/result
协议。

## 请求构建顺序

`GenerationHandler.generateInternal()` 的顺序是有意固定的：

1. 从原始会话历史计算 `contextMessages`。未启用自动裁剪时保持原列表；启用后只改变本次请求，
   不改写 `Conversation`。
2. 使用同一份 `contextMessages` 构建 Tool system prompt，避免工具提示和实际发送历史看到不同上下文。
3. 组装 System message：

   - 对话允许自定义 System 时使用会话级提示，否则使用 Assistant 提示；
   - 按 Assistant 配置追加隔离记忆或全局记忆；
   - 追加启用工具的 system prompt。

4. 把 System 与 `contextMessages` 合并后执行 Input Transformer 管道。
5. 构建模型、采样、输出上限、自定义 Header/Body、工具等请求参数。
6. 调用 Provider；返回的 usage 合并到最后一条助手消息。

`Assistant.maxTokens` 是模型的输出上限，不是输入上下文窗口，也不参与历史裁剪阈值计算。

## Input Transformer 管道

Transformer 以 `fold` 顺序执行，后一个接收前一个的输出：

| 顺序 | Transformer | 作用 |
|------|-------------|------|
| 1 | `TimeReminderTransformer` | 启用时按消息自身时间戳注入跨时段提醒 |
| 2 | `PromptInjectionTransformer` | 注入 Assistant 或会话绑定的 Mode Injection |
| 3 | `PlaceholderTransformer` | 替换内置占位符 |
| 4 | `DocumentAsPromptTransformer` | 将文档附件内容注入提示 |
| 5 | `OcrTransformer` | 对图片执行 OCR 并附加识别文本 |
| 6 | `TemplateTransformer` | 按消息自己的 `createdAt` 渲染模板，避免历史文本每轮变化 |
| 7 | `WorkspaceReminderTransformer` | Workspace Shell 就绪时向 System 追加环境和路径约束 |

`PromptInjectionTransformer` 支持 `BEFORE_SYSTEM_PROMPT`、`AFTER_SYSTEM_PROMPT`、
`TOP_OF_CHAT`、`BOTTOM_OF_CHAT` 和 `AT_DEPTH`。插入点会避开用户消息与其工具回复之间的边界。

## Output Transformer 管道

| 时机 | 方法 | 当前职责 |
|------|------|----------|
| 流式 chunk 到达 | `transforms()` | `RegexOutputTransformer` 等真实变换，结果进入会话状态 |
| 流式 UI 展示 | `visualTransforms()` | `ThinkTagTransformer` 等仅展示变换 |
| 单步生成完成 | `onGenerationFinish()` | 提取最终 reasoning、将 base64 图片落盘 |

`visualTransforms()` 不应修改持久化事实；最终需要保存的变化必须进入 `transforms()` 或
`onGenerationFinish()`。

## 工具装配与执行

`ChatService.handleMessageComplete()` 按以下顺序装配工具：

1. Search Tools：`assistant.enableWebSearch` 开启时；
2. Local Tools：JavaScript、时间、剪贴板、语音播报、向用户提问、屏幕使用时间、日历等；
3. Conversation Tools：允许引用近期对话时；
4. Workspace Tools：绑定 Workspace 且 Shell 状态为 `READY` 时；
5. Skill Tools：Assistant 启用了 Skill 时；
6. MCP Tools：仅取 Assistant 选中且当前已连接、名称合法的服务；
7. Memory Tools：在 `GenerationHandler` 内按 Assistant 的记忆模式加入。

工具审批状态：

```text
Auto
  ├─ 无需审批 ───────────────► 执行
  └─ 需要审批 ─► Pending
                    ├─ Approved ─► 执行
                    ├─ Denied ───► 写入拒绝结果
                    └─ Answered ─► 写入用户答案
```

文本工具输出超过 32 KiB 且当前具备 Workspace Shell 时，只在消息中保留 4 KiB 预览，
完整内容保存到 `/tool_outputs/{toolCallId}.txt`。没有 Shell 访问能力时不生成模型无法读取的
文件引用。

MCP 连接、休眠与恢复不由生成链路持有；`McpManager` 负责连接状态和工具快照，
`ChatService` 只在每次请求装配当前可用工具。生命周期细节见
[`docs/dev/mcp-lifecycle-analysis.md`](../dev/mcp-lifecycle-analysis.md)。

## 上下文、缓存与压缩

### 请求级阶梯裁剪

`Assistant.contextMessageLimit` 默认是 `0`，表示不自动裁剪。用户显式开启后：

- UI 默认阈值为 80，允许范围为 40～512；
- 消息数首次越过阈值时，保留量回落到约一半；
- 后续在同一台阶内只追加尾部消息，不移动历史起点；
- 起点回退到最近的 USER 消息，确保保留完整对话轮次和其中的工具调用；
- 只影响发给 Provider 的 `internalMessages`，完整历史仍保存在会话中。

普通一轮通常增加 USER、ASSISTANT 两条 `UIMessage`。在 50% 保留比例下，阈值 40
大约每 10 次普通请求移动一次起点，理想缓存命中率约 90%；阈值 80 大约每 20 次移动一次，
约 95%。这只是“裁剪起点不变”带来的理论上限，System、Memory、Tools、模型或注入配置变化
仍可能使 Provider 的提示缓存失效。

消息条数不等于 token 数。单条长文档、图片、工具 schema、OCR 结果或 System prompt 都可能
占用大量上下文，因此该阈值是可选的成本/历史长度控制，不是模型窗口溢出的可靠防线。

### 显式语义压缩

用户可通过“压缩上下文”调用 `ChatService.compressConversation()`：

- 较早历史交给压缩模型生成摘要；
- 至少保留用户指定数量的最近消息，并把切点回退到完整 USER 轮次；
- 大批历史分块时同样优先在 USER 轮次边界拆分；
- 摘要和最近历史会替换当前会话的旧消息，这是持久化且不可撤销的操作。

摘要的“目标 token”是给模型的输出指令，不是本地 tokenizer 的硬校验。当前没有把语义压缩
自动挂到发送链路，以免在缺少准确模型窗口与 token 计量时静默丢失制造分析中的约束、结论或证据。

完整决策、已知边界和后续 token 预算方案见
[`docs/dev/context-management.md`](../dev/context-management.md)。

## 会话与通知生命周期

`ConversationSession` 负责单会话状态：

- 页面通过 `acquire()` / `release()` 维护引用；
- 生成 Job 活跃时即使页面离开也保留 Session；
- 无引用且未生成时，空闲超时后由 `ChatService` 清理；
- 生成结束、失败或取消都通过 `AppEventBus` 发布事件；
- `ChatNotificationManager` 独立消费事件，生成主链路不直接持有通知状态。

## 关键文件

```text
ai/src/main/java/me/rerere/ai/
├─ core/Tool.kt
├─ provider/
└─ ui/Message.kt                         # UIMessage、工具状态、上下文边界与阶梯裁剪

app/src/main/java/net/weero/measix/pilot/
├─ service/
│  ├─ ChatService.kt                     # 会话编排、工具装配、显式压缩
│  ├─ ConversationSession.kt             # 单会话状态
│  └─ ChatNotificationManager.kt         # 通知事件消费者
└─ data/ai/
   ├─ GenerationHandler.kt               # Provider 请求与工具循环
   ├─ transformers/
   └─ tools/
      └─ local/
```
