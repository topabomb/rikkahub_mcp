# Measix Pilot 子助手工具集 V1 方案

## 1. 设计目标

在现有 `Assistant → Conversation → GenerationHandler → Tool Loop` 架构上增加子助手能力，重点解决：

* Assistant 的创建、维护和局部记忆审查；
* Master 将独立任务委托给 Target Assistant；
* Target 独立上下文和长期局部记忆隔离；
* Target 执行过程对用户可观察，但不污染 Master Context。

V1 保持**单层、同步 delegation**：

```text
Master Conversation
        │
        │ assistant_call
        ▼
Target Assistant
        │
        ▼
Persistent Child Conversation
        │
        ├── Target LLM / Tool Loop
        └── Final
        │
        ▼
Master 继续当前 Turn
```

不实现后台异步 Agent、semantic progress/mailbox、Target→Master 主动中断、递归 delegation 和 A2A。

---

# 2. Assistant 模型与默认配置

新增：

```kotlin
val description: String = ""
val allowAsTarget: Boolean = false
```

### `description`

用于告诉其他 Assistant：

> 该助手擅长什么，以及何时适合调用。

约定：

* 默认 `""`，兼容历史配置；
* 可通过 Assistant 管理工具创建和修改；
* 应保持简短，不承担 `systemPrompt` 的职责。

### `allowAsTarget`

控制 Assistant 是否允许被其他 Assistant 调用。

约定：

* 数据模型默认 `false`；
* 历史 Assistant 自动兼容为 `false`；
* UI 创建的新 Assistant 默认 `false`；
* Agent 通过 `assistant_manage(CREATE)` 创建的新 Assistant 显式设置为 `true`；
* 之后只能由用户在 Assistant 设置页修改。

这样不会因为版本升级自动把用户现有 Assistant 暴露为 Target。

---

# 3. Assistant 工具权限分组

不再使用当前草案中的单一 `Assistants` Tool Group，也移除独立的 `assistant_list`。当前草案原本把 list/manage/call/memory-list 放在同一组。

新增两个独立的 Local Tool 配置项。

## 3.1 维护其他助手

```kotlin
LocalToolOption.AssistantManagement
```

UI：

```text
维护其他助手
```

启用：

```text
assistant_manage
assistant_memory_list
```

用于：

* 创建 Assistant；
* 修改 Assistant 的语义定义；
* 删除 Assistant；
* 检查其他 Assistant 的局部记忆。

### 默认值

**默认禁用。**

历史 Assistant、新建普通 Assistant、`assistant_manage` 创建的 Assistant 均不自动开启。

---

## 3.2 调用其他助手

```kotlin
LocalToolOption.AssistantDelegation
```

UI：

```text
调用其他助手
```

启用：

```text
assistant_call
```

### 默认值

**默认禁用。**

因此 Assistant 是否具有：

```text
管理其他 Assistant
```

和：

```text
调用其他 Assistant
```

是两个完全独立的权限。

---

## 3.3 Target 模式强制限制

无论 Target 本身配置如何，通过 `assistant_call` 执行时 Runtime 永久过滤：

```text
AssistantManagement
AssistantDelegation
```

所以 V1 始终只有：

```text
Master → Target
```

不存在：

```text
Master → Target → Target
```

---

# 4. Assistant Catalog：移除 `assistant_list`

独立的 `assistant_list` Tool 没有必要。

LLM 如果具有维护或调用 Assistant 的能力，本身就需要在决策前知道有哪些 Assistant。相比：

```text
LLM
→ assistant_list
→ ToolResult
→ LLM
→ assistant_call
```

更合理的是在相应能力启用时直接提供紧凑的 Assistant Catalog。

## 4.1 注入位置

当前生成 System Prompt 的顺序本身已经是：

```text
Assistant System Prompt
→ Memory Prompt
→ Tool.systemPrompt(...)
```

即每个 Tool 都可以在生成请求时动态贡献 System Context。

因此 Assistant Catalog 使用现有 `Tool.systemPrompt()` 扩展点，不增加新的 Transformer 或独立 Tool。

---

## 4.2 注入规则

### 仅开启「调用其他助手」

注入：

```text
<available_assistants>
- id: ...
  name: Android分析
  description: 分析 Android/Kotlin、Gradle 和应用架构问题
- ...
</available_assistants>
```

只包含：

```text
allowAsTarget == true
且不是当前 Assistant
```

---

### 开启「维护其他助手」

注入全部其他 Assistant：

```text
<assistants>
- id: ...
  name: Android分析
  description: ...
  callable: true

- id: ...
  name: 写作助手
  description: ...
  callable: false
</assistants>
```

因为维护行为需要知道所有 Assistant，而不只是可调用 Target。

---

### 两项同时开启

只注入一次完整 Catalog：

```text
id
name
description
callable
```

不重复注入两套列表。

实现上：

* `assistant_manage` 存在时，由其 `systemPrompt()` 提供完整 Catalog；
* 只有 `assistant_call` 时，由 `assistant_call.systemPrompt()` 提供可调用 Catalog。

Catalog 应在每次 Master LLM 请求构建时读取**当前最新 Settings**，而不是在 Conversation 创建时缓存。

因此用户运行期间：

```text
新增 Assistant
修改 description
关闭 allowAsTarget
删除 Assistant
```

都会在 Master 下一次 LLM Loop 自动反映到 Catalog。

---

# 5. Assistant 管理工具

## 5.1 `assistant_manage`

### Description

> Create, update, or delete another assistant. This tool manages only the assistant's identity, capability description, and instructions. Model, tools, permissions, memory settings, and UI settings remain user-managed.

### 参数

```text
action: CREATE | UPDATE | DELETE
assistant_id?: String
name?: String
description?: String
instructions?: String
```

### CREATE

要求：

```text
name          required
instructions  required
description   optional, default ""
```

Runtime 创建：

```text
allowAsTarget = true
chatModelId = null

其他字段使用当前 Assistant 默认值
```

因此 Agent 创建的新 Assistant 默认：

```text
Memory            enabled
Memory Scope      local
调用其他助手       disabled
维护其他助手       disabled
Web Search        disabled
MCP               none
Workspace         none
```

现有 Assistant 默认即 `enableMemory=true`、`useGlobalMemory=false`，因此默认 Memory namespace 按 Assistant ID 隔离。

`chatModelId=null` 继续使用现有全局模型回退逻辑。

### UPDATE

仅允许修改：

```text
name
description
instructions
```

### DELETE

只接受：

```text
assistant_id
```

不能修改或删除当前 Master Assistant。

### 审批

`assistant_manage` 永远需要现有 HITL 审批。

---

# 6. Target 局部记忆审查

## `assistant_memory_list`

归属：

```text
维护其他助手
```

### Description

> Read the isolated local memories stored for another assistant. This tool always reads that assistant's local memory namespace and never exposes global memory.

### 参数

```text
assistant_id: String
```

### 返回

```json
{
  "assistant_id": "...",
  "assistant_name": "Android 分析助手",
  "memory_enabled": true,
  "using_local_memory": true,
  "memories": [
    {
      "id": 12,
      "content": "项目默认使用 JDK 17"
    }
  ]
}
```

其中：

```text
memory_enabled
```

表示 Target 当前是否开启 Memory；

```text
using_local_memory
```

表示 Target 当前是否使用自身 Local Memory。

无论 Target 当前 Memory 是否启用、或者正在使用 Global Memory，该 Tool 始终读取：

```text
assistant.id.toString()
```

对应的 Local Memory namespace。

现有 Memory 本身已经以 Assistant ID 作为局部隔离键，并通过 `memory_tool` 提供 `create/edit/delete`。

### 不增加 `assistant_memory_get`

当前 Memory item 本身就是完整的：

```text
id + content
```

LIST 已经提供完整内容，因此 GET 没有额外价值。

### Memory 修正方式

Master 只负责**审查**其他 Assistant 的 Local Memory，不直接修改。

正常流程：

```text
assistant_memory_list(B)
        ↓
发现 memory #12 已过期
        ↓
assistant_call(
    B,
    "你的局部记忆 #12 已经过期。
     请根据以下新信息检查并修正自己的记忆……"
)
        ↓
B 使用自己的 memory_tool
```

这样 Memory 的最终写入仍由拥有该 Memory 的 Assistant 完成。

如果：

```text
using_local_memory = false
```

则 Master 应知道当前调用 B 时，其 `memory_tool` 不会修改 Local Memory；这种情况下需要用户先调整 B 的 Memory 配置。

---

# 7. `assistant_call`

## Description

> Delegate a self-contained task to another assistant. The target assistant runs in its own persistent conversation with its own context and currently permitted tools. The caller receives only the target's final response.

## 参数

```text
assistant_id: String
task: String
```

`assistant_id` 直接来自当前 System Context 中的 Assistant Catalog，因此不再需要 `assistant_list`。

`task` 应包含 Target 独立完成任务所需要的信息。

Target 不自动读取：

```text
Master Conversation history
Master System Prompt
Master Tool History
```

---

# 8. Master 与 Target 的上下文模型

V1 固定采用：

```text
Master Conversation × Target Assistant
                  =
一个 Child Conversation
```

例如：

```text
Master A100
 ├── Target B → Child B200
 └── Target C → Child C300

Master A101
 └── Target B → Child B201
```

因此：

```text
A100 第一次调用 B → 创建 B200
A100 再次调用 B   → 继续 B200

A101 调用 B       → 创建 B201
```

这是严格的 Master Conversation → Target 上下文隔离。

### 两类持久上下文

必须明确区分：

```text
Child Conversation
→ 属于 Master Conversation
→ 保存本次 Master 会话范围内 B 的工作上下文
```

和：

```text
Local Memory
→ 属于 Assistant
→ 跨不同 Master Conversation 长期存在
```

即：

> **Conversation Context 跟随 Master Conversation；Memory 跟随 Target Assistant。**

---

# 9. Child Conversation 持久化

Conversation 增加：

```kotlin
val parentConversationId: Uuid? = null
```

语义：

```text
null     → 普通 Conversation
非 null  → Target Child Conversation
```

Child：

```text
assistantId = Target Assistant ID
parentConversationId = Master Conversation ID
```

数据库建立唯一约束：

```text
(parentConversationId, assistantId)
```

保证一个 Master Conversation 对同一个 Target 只有一个 Child Context。

---

## 9.1 创建

采用懒创建：

```text
assistant_call
→ Target readiness 校验
→ 校验成功
→ 查找 Child
→ 不存在才创建
```

模型不可用等启动失败不创建空 Child。

---

## 9.2 持久化时机

普通流式 token 不需要持续写 DB。

Child 在几个明确边界 checkpoint：

```text
Target task 写入后
每个完整 LLM step 完成后
每个 ToolResult 完成后
Target terminal state
```

保存：

```text
USER task
Target messages
Reasoning
ToolCall / ToolResult
Final
```

这样避免每个 streaming chunk 写数据库，同时保证长时间 Target 工作在异常退出时不会全部丢失。

---

## 9.3 生命周期

### Master Conversation 删除

级联删除：

```text
所有 parentConversationId == Master ID 的 Child Conversation
```

### Target Assistant 删除

历史 Child 保留。

新的 `assistant_call` 禁止。

### 新的 Master Conversation

即使调用同一个 Target，也创建新的 Child。

---

# 10. `assistant_call` 启动与执行

## 10.1 Readiness

调用前依次检查：

```text
Target exists
allowAsTarget == true
Target != Master
Chat Model available
Child 当前没有其他 active run
```

阻断原因保持少量且可操作：

```text
assistant_not_found
target_not_allowed
target_model_unavailable
target_busy
```

例如模型不可用：

```json
{
  "status": "unavailable",
  "reason": "target_model_unavailable",
  "message": "The target assistant has no available chat model. Configure a valid assistant model or global default model."
}
```

Target 不允许使用普通 Conversation 当前的 Assistant fallback 行为。

指定 Target 被删除就必须失败，不能切换成其他 Assistant。

---

## 10.2 调用级配置

本次 `assistant_call` 开始时固定：

```text
Target identity
System Prompt
Model
temperature / topP / maxTokens
reasoningLevel
Template / Prompt Injection
其他生成语义配置
```

用户中途修改这些配置，从**下一次 assistant_call**生效。

---

## 10.3 Tool 权限

Target ToolSet 每个 Loop 重新构建。

当前项目本身已经根据 Assistant 配置动态装配 Search、Local、Conversation、Workspace、Skills、MCP 和 Memory Tool。

Target 能看到某个 Tool 必须满足：

```text
当前明确启用
AND
资源当前可用
AND
当前权限允许直接执行
AND
不需要用户审批
AND
不是 AssistantManagement / AssistantDelegation
```

ToolCall 真正执行前再读取一次最新配置和权限。

如果已被用户关闭或当前参数需要审批：

```json
{
  "error": "tool_not_permitted"
}
```

作为普通 ToolResult 返回 Target。

Target 不进入隐藏 Pending HITL。

---

# 11. `assistant_call` 结束状态

Tool Result 只需要：

```text
completed
unavailable
failed
stopped
```

### completed

```json
{
  "status": "completed",
  "content": "Target final response"
}
```

### unavailable

Target 尚未真正开始：

```text
assistant_not_found
target_not_allowed
target_model_unavailable
target_busy
```

### failed

Target 已启动但 Generation 无法完成：

```text
provider_error
runtime_error
step_limit_reached
```

单个 Tool 错误仍由 Target 自己处理，不升级成 `assistant_call` failure。

### stopped

运行期间：

```text
Target 被删除
allowAsTarget 被关闭
```

在下一 Loop 边界停止。

---

# 12. 用户停止当前 Master Turn

Target 必须运行在 Master Generation Job 的结构化协程链中：

```text
Master Job
   ↓
assistant_call
   ↓
Target Generation
```

用户点击当前聊天已有的「停止」：

```text
Cancel Master Job
        ↓
assistant_call Cancel
        ↓
Target LLM / 当前 Tool Cancel
```

沿用现有 `CancellationException` 传播规则，不转换成普通 Tool Error。

Target 已完成的 Child History 保留，未完成 Tool 使用现有 interrupted 清理语义。

Master 的 `assistant_call` 在 UI 中显示：

```text
已中断
```

但不生成一个新的 ToolResult 再唤醒 Master LLM。

下一次调用同一 Target 时继续使用原 Child Conversation。

---

# 13. App 异常退出

V1 不自动重放正在执行的 `assistant_call`。

App 恢复后发现：

```text
Master 中存在未完成 assistant_call
且不存在对应 active Job
```

则将调用恢复为：

```text
interrupted / app_restarted
```

Child 中已经 checkpoint 的历史继续保留，未完成 Tool 做 interrupted 清理。

后续新的 `assistant_call` 继续原 Child Context，而不是自动重新执行旧任务。

---

# 14. Master 聊天 UI

子助手不应该在聊天列表里退化成普通的：

```text
Tool: assistant_call
Arguments: {...}
```

需要专门的 Mobile Sub-Assistant Card。

业界常见做法也是明确展示正在运行的 Agent 身份和状态，并允许用户进入查看运行详情；例如 Claude Code 的 Agent UI 有 Running 状态、可进入正在运行的 subagent，并以独立标识区分不同 agent。([Claude][1])

Measix Pilot 不需要复制桌面端完整 Agent 面板，而应压缩成聊天消息里的轻量卡片。

---

## 14.1 Running Card

默认折叠：

```text
┌────────────────────────────────┐
│ [头像] Android 分析助手    执行中 │
│ 分析 DeepSeek API 调用失败原因…   │
│ 正在使用：代码搜索                 │
│                         查看详情 › │
└────────────────────────────────┘
```

只保留三个信息层：

### 谁在工作

```text
Target Avatar
Target Name
```

### 在做什么

显示 `task` 的一行摘要。

不额外调用模型生成摘要，直接对 task 文本做 UI 截断。

### 当前正在做什么

来自 Runtime 的**确定性执行状态**，不是让 Target LLM 主动汇报 progress。

状态映射：

```text
Target LLM 正在运行
→ 正在分析

Reasoning/Text 正在生成
→ 正在生成

Tool 正在执行
→ 正在使用：{Tool Display Name}

等待下一 Loop
→ 正在继续处理
```

不显示虚假的：

```text
进度 45%
即将完成
预计剩余时间
```

---

## 14.2 Completed

```text
┌────────────────────────────────┐
│ [头像] Android 分析助手    已完成 │
│ 分析 DeepSeek API 调用失败原因…   │
│ 已定位问题主要来自请求状态处理…    │
│                         查看详情 › │
└────────────────────────────────┘
```

显示 Target Final 的 1–2 行预览。

完整 Final 仍进入 Master ToolResult，由 Master 负责整合成用户最终回答。

---

## 14.3 异常状态

统一保持简短：

```text
Android 分析助手 · 无法启动
未配置可用聊天模型
```

```text
Android 分析助手 · 执行失败
模型请求失败
```

```text
Android 分析助手 · 已中断
```

用户不需要看到内部 Exception、UUID 或原始 JSON。

---

# 15. Target 执行详情

点击卡片进入只读 Child Conversation 页面。

展示：

```text
委托任务
Target reasoning
Target ToolCall / ToolResult
Target Final
```

运行中直接观察 Child Conversation Flow，因此能够实时看到执行过程。

允许从详情页进入 Target Assistant 设置页面。

不允许：

```text
直接给 Child 输入消息
单独批准 Child Tool
从 Child 启动下一 Turn
```

V1 的操作入口仍然只有 Master Conversation。

---

# 16. Master Tool Metadata

`assistant_call` 的 `UIMessagePart.Tool.metadata` 保存：

```text
targetAssistantId
targetAssistantNameSnapshot
childConversationId
childTaskNodeId
```

用途：

* Target 改名/删除后历史卡片仍有名称；
* 定位 Child Conversation；
* 同一个 Child 被多次调用时，可以定位本次任务在 Child 中的起点。

这些 metadata 只服务 Runtime/UI，不发送给 Master LLM。

---

# 17. Child Conversation 的 UI 与后处理

Child Conversation 是持久上下文，但不是普通用户 Conversation。

必须从以下入口过滤：

```text
普通聊天列表
Recent Chats
Conversation Search
普通会话搜索结果
```

Child 正常：

```text
持久化
更新 Flow
供详情页实时观察
```

但不执行：

```text
generateTitle()
generateSuggestion()
普通聊天完成通知
普通聊天声音
```

当前普通 Conversation 的成功路径会保存 Conversation，并进一步触发标题和 Suggested Reply 后处理；Target Child 必须显式绕过这些用户会话副作用。

Child 页面标题直接使用 Target Assistant 名称。

只有 Master Conversation 最终完成用户 Turn 后，才继续现有：

```text
保存
标题生成
Suggested Reply
通知 / 声音
```

---

# 18. V1 最终约定

| 维度                     | V1                                       |
| ---------------------- | ---------------------------------------- |
| Assistant 工具权限         | `维护其他助手` / `调用其他助手` 两组                   |
| 两组默认值                  | 均禁用                                      |
| Assistant discovery    | System Context 动态 Catalog                |
| `assistant_list`       | 删除                                       |
| Assistant 管理           | `assistant_manage`                       |
| Target Local Memory 审查 | `assistant_memory_list`                  |
| Memory 写入              | Target 自己的 `memory_tool`                 |
| Delegation             | `assistant_call`                         |
| 调用模式                   | 同步                                       |
| Target Context         | 独立、持久化 Child Conversation                |
| Master↔Target Context  | 每个 Master Conversation 一对一               |
| Local Memory           | Target Assistant 级，跨 Master Conversation |
| Target ToolSet         | 每 Loop 动态解析                              |
| Tool 权限                | 执行前再次实时校验                                |
| 需要审批的 Tool             | Target 禁止                                |
| Assistant Tool         | Target 永久禁止                              |
| 用户停止 Master            | 级联取消 Target                              |
| App 异常退出               | 不自动重放                                    |
| 主聊天呈现                  | 专用 Sub-Assistant Card                    |
| 执行状态                   | Runtime 确定性状态摘要                          |
| Target Detail          | 只读 Child Conversation                    |
| Child 标题 / Suggestion  | 不生成                                      |
| Child 普通聊天列表           | 不展示                                      |

核心模型最终保持为：

```text
Assistant
├── Instructions
├── User-configured capabilities
└── Local Memory                 ← Assistant 生命周期

Master Conversation
└── Target Assistant
      └── Child Conversation     ← Master Conversation 生命周期

Master
└── assistant_call
      └── Target Final           ← 唯一进入 Master Context 的 Target 输出
```

这样既保留真正有价值的子助手上下文隔离和专业化能力，又避免为了 Agent 编排引入额外 list round-trip、semantic progress、mailbox 或后台任务状态机。

