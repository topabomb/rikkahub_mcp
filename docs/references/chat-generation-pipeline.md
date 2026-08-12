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

同一 Assistant message 含多个 ToolCall 时，审批是整批屏障：只要任一调用仍为 Pending，本批自动工具不
执行；全部 Approved/Denied/Answered 后，按 Tool part ordinal 串行处理。审批更新、执行结果合并和
截断文件定位均使用 `messageId + toolOrdinal`，Provider `toolCallId` 只用于线协议。

文本工具输出超过 32 KiB 且当前具备 Workspace Shell 时，只在消息中保留 4 KiB 预览，
完整内容保存到 `/tool_outputs/{executionId}.txt`，其中 `executionId` 由 `messageId + toolOrdinal`
构成，不使用 Provider `toolCallId`。没有 Shell 访问能力时不生成模型无法读取的
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

## 子助手生成管线扩展

子助手（Sub-Assistant）V1 在通用生成管线上增加了以下原语，普通聊天行为保持不变。

### GenerationChunk 事件

`GenerationHandler.generateText()` 使用 `channelFlow` 返回四种 `GenerationChunk` 事件。`assistant_call` 在可取消
子 Job 中执行并通过 `reportMetadata` 回写，因此普通 `flow { emit(...) }` 会违反跨协程发射 invariant；
`channelFlow.send` 保证事件安全，ToolCall 本身仍由 ordinal 循环串行执行：

| 事件 | 用途 |
|------|------|
| `Messages` | 更新后的消息列表（原有行为） |
| `Phase` | 生成阶段变化，phase 使用稳定英文枚举：`preparing`/`model_waiting`/`tool_executing`/`between_steps`；`tool_executing` 携带 registered tool name |
| `Checkpoint` | 持久化检查点，`CheckpointKind` 区分 `STEP_COMPLETED`/`TOOL_STATE_CHANGED`/`TOOL_RESULT_COMPLETED` 等 |
| `Finished` | 生成结束，`FinishedReason` 区分 `completed`/`awaiting_approval`/`step_limit_reached` |

Master collector 消费 Checkpoint 保存会话状态；Target collector 额外用 Phase 更新运行卡片。

### ToolExecutionContext

`Tool.execute` 保留原有签名，新增可选的 `contextualExecute` 和 `executeWithContext()`：

- `ToolExecutionContext` 含 `messageId` + `toolOrdinal`（精确 locator，不依赖 `toolCallId`）和 `reportMetadata` 回写回调
- `GenerationHandler` 只通过 `executeWithContext()` 执行工具；普通 Tool 自动回退 `execute`
- `assistant_call` 的普通 `execute` fallback 返回 `context_required` 错误，不在缺少 locator 时启动 Child

### toolProvider 与 Target 交互边界

- `generateText()` 接收 `toolProvider: (suspend () -> List<Tool>)`，每个 LLM step 重新调用以获取最新工具列表
- `nonInteractive: Boolean` 参数默认拒绝需审批工具；`interactiveToolNames` 可显式放行宿主能够承接的工具
- Target Run 只放行 `ask_user`：Coordinator 持久化 Child locator，把问题桥接到主聊天子助手卡片并等待回答；其余需审批工具返回 `tool_not_permitted`
- 截断文件名使用 `messageId + toolOrdinal` 构成的 execution ID，不使用 Provider `toolCallId`

### ConversationSessionRegistry

从 `ChatService` 抽取的 Session/Job/StateFlow 生命周期管理器：

- 同一 Conversation ID 只有一个 Session
- Master 和 Child 共用同一 Registry
- `getOrCreateSession` 创建型打开 Session；`getExisting` 只查询不创建
- 加载持久化会话使用显式 `open(conversation)`，不创建空 Session 遮蔽 Room 快照

### Target 执行流程

`assistant_call` 通过 `SubAssistantCoordinator` 实现：

1. Target preflight（caller 存在且 AssistantDelegation 启用、Target 存在、Target != caller、`allowAsSubAssistant == true`、访问公式、模型来源可解析）
2. 构造内存 `SubAssistantRunSpec`：Target 显式模型优先；未绑定时继承 caller 的有效模型和模型执行参数；显式 Target 模型失效与 caller 无模型分别返回 `target_model_unavailable`、`caller_model_unavailable`
3. 按当前 Master 分支只读解析 lineage，再获取 `Master Conversation ID + Target ID` lease；同一 Master/Target 串行，不同 Master 可并行
4. 写入 request 前从最新 Settings 重验权限与 RunSpec 模型；通过后创建/克隆 Child Conversation 并追加 Target USER request（Child 创建失败时释放 lease）
5. 回写 Master tool metadata 的 run/child link 并 checkpoint
6. Target GenerationHandler 执行（Child Messages/Phase 实时更新，step/tool 边界 checkpoint）
7. 提取 final answer，写 terminal metadata，返回 Tool Result
8. 释放 lineage lease，Master 继续当前 Tool Loop

Target Run policy 永久过滤 `AssistantManagement`/`AssistantDelegation`（LocalToolOption 级别），同时按工具名过滤 `assistant_manage`、`assistant_call`、`assistant_memory_list`。`ask_user` 保留并作为唯一可桥接的交互工具；等待回答时停止、撤权、删除或重启都会解除等待并按对应 stopped reason 收口，不自动重放。运行中撤权在安全边界 stopped。

### TTS 来源切换

Target Generation 中 `TtsToolPlaybackContext` 在每轮 Generation 创建一次，不在每个 LLM step 重建；不同 step 重建 Tool 时复用同一 context，不放入单例 LocalTools。`computeEffectiveFlush` 决定同一 Generation 内首次 TTS 调用 flush、后续 append（顺序开关开时）；playback session 变化时无论开关都 flush。队列自然播放完毕（`PlaybackStatus.Ended`）、Provider 切换、播放错误、dispose 时清空 `activeSource`，避免控制条在无音频时继续显示旧 Target。`assistant_call` 完成时不清除已提交给 TTS Controller 的音频（保持后台播放语义）；来源不持久化，不从历史消息恢复。

## 关键文件

```text
ai/src/main/java/me/rerere/ai/
├─ core/Tool.kt                          # ToolExecutionContext、contextualExecute
├─ provider/
└─ ui/Message.kt                         # UIMessage、工具状态、上下文边界与阶梯裁剪

app/src/main/java/net/weero/measix/pilot/
├─ service/
│  ├─ ChatService.kt                     # 会话编排、工具装配、显式压缩
│  ├─ ConversationSession.kt             # 单会话状态
│  ├─ ConversationSessionRegistry.kt     # Master/Child 共用 Session 生命周期
│  ├─ AssistantManagementService.kt      # Assistant CRUD、原子授权、删除编排
│  ├─ SubAssistantCoordinator.kt         # Target 执行协调器
│  └─ ChatNotificationManager.kt         # 通知事件消费者
└─ data/ai/
   ├─ GenerationHandler.kt               # Provider 请求与工具循环（含 Phase/Checkpoint/Finished）
   ├─ transformers/
   ├─ subassistant/                      # 访问策略、lineage、reducer、metadata、preview、catalog
   └─ tools/
      ├─ AssistantToolFactory.kt         # assistant_manage/memory_list/call 工具构建
      ├─ GenerationToolSetFactory.kt     # 按 Assistant/资源/RunMode 装配工具集
      └─ local/
```
