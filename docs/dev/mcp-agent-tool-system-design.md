# MCP Agent 工具系统设计

> **文档定位**：Fork 后的核心功能设计文档，记录"以 MCP 为中心的对话入口"这一原始诉求的重新审视、需求演进、最终方案与待定问题。
> **状态**：设计已对齐，待实现。实现启动前需复核"待定问题"章节。
> **创建日期**：2026-06-21
> **关联文档**：
> - `report-rikkahub-fork-analysis.md` — 原始调研报告（含本诉求的最初表述，部分判断已被本文档修正）
> - `fork-simplification-plan.md` — 精简落地记录（精简阶段未涉及 MCP 架构改造）

---

## 一、背景

### 1.1 原始诉求的表述

Fork 调研报告（`report-rikkahub-fork-analysis.md`）开篇定义了三项核心需求：

> 核心需求：MCP 支持、自动上下文压缩、**以 MCP 为中心的对话入口**

其中第三项（§4.3 改造三、§5.3）的具体描述是：

- **目标**：每次新建对话可重配 MCP，支持扫码登记
- **架构设想**：全局 MCP 预设 → 对话级 MCP 配置（`Conversation.mcpServerIds`）→ McpManager 按对话 ID 隔离连接 → ChatService 动态加载对话的 MCP 工具
- **预估工作量**：5-8 天，风险最高

精简阶段（0.0.2，见 `fork-simplification-plan.md`）只完成了"移除无用功能"，**未触碰 MCP 架构**。0.0.3 补齐了 MCP 服务器分享（二维码扫码登记/导入/导出）。截至本文档创建，原始诉求的"对话级 MCP"部分尚未实装。

### 1.2 当前实现现状（代码实证）

| 维度 | 现状 | 代码位置 |
|---|---|---|
| MCP 配置存储 | **全局** | `Settings.mcpServers: List<McpServerConfig>` |
| MCP 绑定层级 | **Assistant 级** | `Assistant.mcpServers: Set<Uuid>`；`Conversation` 无任何 MCP 字段 |
| 连接生命周期 | **全局单例**，与对话/助手无关 | `McpManager` 单例；init collector 只按 `connectionKey` diff 全局列表，不看消费者 |
| 工具暴露过滤 | 按 `getCurrentAssistant().mcpServers` | `McpManager.getAllAvailableTools()`（McpManager.kt:119-131） |
| 配置 UI 入口 | 设置页（全局 CRUD）+ 输入栏"+"（写 Assistant） | `SettingMcpPage.kt`、`FilesPicker.kt:166` → `McpPickerListItem` |
| 扫码登记 | ✅ 完整（扫码/选图/粘贴/分享二维码） | `SettingMcpPage.kt`、`McpConfig.kt` 的 `parseMcpServersFromJson`/`encodeForShare` |
| Transport 支持 | 仅 SSE + StreamableHTTP（无 stdio） | `McpConfig.kt`：`SseTransportServer` / `StreamableHTTPServer` |

**一句话总结**：扫码登记已 100% 完成；MCP 绑在 Assistant 上——同一助手的两个对话无法用不同的 MCP 组合，也无法在对话级对工具做过滤。

### 1.3 移动端的特殊性

- **无 stdio transport**：Android 上无法像桌面端 spawn `npx`/`node` 子进程。当前实现只支持远程 HTTP/SSE server，这与 MCP 官方对"移动端/浏览器场景"的推荐（Streamable HTTP）一致。
- **server 必然是远程的、无状态的、按需连接的**——这一点在后续决策中起到关键作用（见 §3.2）。

---

## 二、业界调研结论

### 2.1 主流客户端的 MCP 绑定层级

调研了 6 个主流 MCP 客户端，**无一例外**地把 MCP 绑在"比会话更高的层级"：

| 客户端 | 配置层级 | 会话级绑定？ | 团队共享 |
|---|---|---|---|
| Claude Desktop | 全局单层（`claude_desktop_config.json`） | 否 | 无（纯个人） |
| Cursor | 全局 + 项目（`.cursor/mcp.json`） | 否 | commit 配置文件 |
| Claude Code | user / project / local 三层 | 否 | commit `.mcp.json` |
| Cline | 全局 + 工作区 | 否 | commit 工作区配置 |
| Roo Code | 全局 + 项目（`.roo/mcp.json`） | 否 | commit 配置文件 |
| 5ire | App 级（文档明确否定会话级） | 否 | 无 |

### 2.2 业界为何不做"会话级绑定"

**产品取舍为主，技术限制为辅**：

1. **"能力池"心智模型**：MCP 是用户拥有的能力集合，对话只是消费这些能力的场合。配置和消费分属两个层级。
2. **模型自决调用**：现代做法是把所有可用工具 schema 注入上下文，让模型根据 prompt 自行决定调哪个。
3. **团队共享优先**：Cursor/Claude Code/Roo Code 重点投入"项目级配置 + git 共享"，会话级绑定对协作价值低。
4. **server 生命周期重于对话**：stdio server 要 spawn 进程、长连接、握手加载 tools；绑到"可能只活几十秒的对话"上不划算。

### 2.3 对本项目的关键启示

业界调研带来两个看似矛盾的结论，需要辩证对待：

- **结论一（顺应）**：MCP **生命周期应全局管理**，不应按对话隔离连接池。原始报告里"McpManager 按对话 ID 隔离连接"的设计**被否决**——连接复用是工程正确性，不因产品取舍而改变。
- **结论二（差异化）**：移动端反而比桌面**更适合**做会话级绑定。因为移动端只能连远程无状态 server，启动成本低，"每个对话挂一组 server"的技术阻力比桌面小。这是**未被业界开发的差异化机会点**。

> ⚠️ **重要修正**：原始报告的"McpManager 按对话 ID 隔离连接"是一个判断偏差。它混淆了两个层面——连接管理（必须全局）与工具暴露（可对话级过滤）。正确的做法是：**全局连接池 + 对话级工具过滤**，二者解耦。

---

## 三、需求的重新发现

### 3.1 真实场景：多设备同协议 MCP

经过多轮探讨，确认了原始诉求背后的真实场景（这是业界调研无法覆盖的，因为业界是桌面/IDE 场景，没有"多物理设备"维度）：

> **场景**：存在多台目标设备（例如测量机器的上位机），每台设备对外提供一套 MCP server，**都遵循相同的协议（相同工具集）**。作为 Agent 交互终端，App 需要在不同对话里**选择连接不同设备**（即不同 MCP server）。

这个场景的本质特征：
- 多个 MCP server 的**工具集相同/同构**（都叫 `measure`/`read_sensor`）
- 但指向**不同的物理设备**（设备 A vs 设备 B）
- 在一个对话里**同时连多台没有意义**，甚至会造成工具名冲突
- → 这是一种**互斥**关系，而非叠加关系

### 3.2 三层模型的提出

基于上述场景，将 MCP/工具体系重新建模为三层（**所有结论的基石**）：

```
全局层  Settings.mcpServers
        ├─ 全局连接池（生命周期管理，长连接复用）
        └─ 每个 MCP server = 一组语义相关的工具（"工具分组"）
              │
              ▼
助手层  Assistant.mcpServers
        └─ 定义"该助手角色可能用到的工具【候选集】"
           （对多设备这类互斥分组，助手会把多个都列为候选）
              │
              ▼
对话层  Conversation.{mcpSelection, mcpToolOverrides, localToolOverrides}
        └─ 定义"这次对话实际【暴露给模型】的工具"
           本质是对助手候选集的一次"过滤/选择决策"，而非配置本身
              │
              ▼
运行时  getAllAvailableTools() = 助手工具候选集 ∩ 对话过滤决策
```

**核心语义**：
- **助手管"可能性"**（这个角色能用哪些工具）
- **对话管"这次用哪个"**（本次激活哪些，尤其从互斥组里选其一）
- **连接池全局共享**（不因对话切换而重建连接）

### 3.3 工具的两种交互范式

基于互斥/叠加属性，工具选择在对话层有两种范式：

| 范式 | 适用对象 | 选择粒度 | UI 形态 |
|---|---|---|---|
| **排它选择** | 同 `groupId` 的 MCP server | 整个 server（整组激活） | RadioGroup（单选） |
| **叠加选择** | 独立 MCP server（无 groupId）+ 内置工具 | 单个工具 | Checkbox（多选） |

---

## 四、关键决策记录

以下是经过多轮探讨后锁定的设计决策。每条都记录了**选择**与**被否决的备选**，便于日后追溯。

### 4.1 MCP 生命周期管理

| 决策 | 全局单例连接池，不按对话隔离 |
|---|---|
| **否决** | 原始报告的"McpManager 按对话 ID 隔离连接" |
| **理由** | 业界共识 + 工程正确性。连接复用避免频繁握手/鉴权；移动端虽可承受重建，但无收益。连接管理与工具暴露是两个正交问题，必须解耦 |

### 4.2 排它分组的协议表达

| 决策 | 在 `McpCommonOptions` 新增 `groupId: String? = null` 字段 |
|---|---|
| **否决** | ① 类型化分类枚举（`McpCategory`，维护成本高、扩展性差）；② 不加协议靠对话侧单选行为（App 无法自动识别互斥组，每次靠用户手动判断，摩擦大且不可靠） |
| **理由** | 最小侵入。语义清晰：`null` = 独立可叠加；非空 = 同组互斥。纯增量字段，旧数据无 `groupId` → 全部视为独立 → 完全向后兼容 |
| **约束** | 同 `groupId` 的 server 在任意时刻只能激活一个。空/不设 = 任意叠加 |

### 4.3 选择粒度

| 决策 | 排它类 → server 级（选其一，整组激活）；非排它类 → 工具级（精细到单工具） |
|---|---|
| **否决** | ① 全部 server 级（非排它场景丢失工具级精细控制）；② 全部工具级（排它场景下用户要手动排除同组其它 server，反直觉且易错） |
| **理由** | 精确对应真实场景。多设备排它 = 选一台设备 = 选一个 server（细到工具无意义）；搜索/内置工具叠加 = 按需开关单个工具 |

### 4.4 内置工具是否纳入模型

| 决策 | 纳入。内置工具（`localTools`）按"非排它类"对待，对话级可做工具级过滤 |
|---|---|
| **理由** | 与非排它 MCP 统一交互范式。对话可临时关掉助手默认开的某个内置工具（如 JS 引擎），无需回助手设置 |

### 4.5 对话级数据存储模型

| 决策 | **Delta 偏移**。`Conversation` 只存相对助手的增删/选择 |
|---|---|
| **否决** | ① 存最终选择结果（助手配置变更后旧对话失配）；② 存完整快照（完全脱节助手优化，历史对话无法继承改进） |
| **理由** | 最契合"对话是对助手工具列表的过滤处理"语义。助手改 MCP 配置后，对话自动继承新配置，仅保留"本次决策"的 delta |
| **向后兼容** | 所有 delta 字段为 nullable，null = 完全继承助手。现有对话零迁移 |

---

## 五、技术方案

> 本章节是设计层面的方案描述，**非最终实现**。实现时需先复核 §七 的待定问题。

### 5.1 数据模型变更

#### 5.1.1 MCP 配置层（协议更新）

```kotlin
// McpCommonOptions 新增字段
data class McpCommonOptions(
    val name: String,
    val enable: Boolean = true,
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val groupId: String? = null,   // 新增。null=独立可叠加；非空=同组排它
)
```

**向后兼容**：
- 旧序列化数据（备份/分享 JSON）反序列化不崩：`defaultValue = null` + 项目已有 `ignoreUnknownKeys = true`
- MCP 分享格式（`encodeForShare`）**不带 groupId**（见 §7.2 待定）

#### 5.1.2 对话层（Delta 偏移）

```kotlin
// Conversation 新增三个 nullable 字段
data class Conversation(
    // ... 现有字段不变 ...

    // 对助手工具候选集的过滤 delta（全部 nullable，null = 完全继承助手）
    val mcpSelection: Map<String, Uuid>? = null,          // groupId -> 选中的 serverId
    val mcpToolOverrides: Map<Uuid, Set<String>>? = null, // serverId -> 启用的 tool 名集合（仅非排它类，null=该 server 全启用）
    val localToolOverrides: Map<String, Boolean>? = null, // 内置工具名 -> 启用（null=继承助手）
)
```

**字段语义**：
- `mcpSelection`：对每个排它组，记录"对话选了哪个 server"。键是 groupId，值是选中的 serverId。只存组的选择，不存独立 server（独立的默认全候选）
- `mcpToolOverrides`：非排它 server 的工具级过滤。某 serverId 缺失 = 该 server 全部工具启用；存在 = 只启用列出的工具名
- `localToolOverrides`：内置工具过滤。某工具名缺失或整个 map 为 null = 继承助手；存在 = 按值覆盖

**DB schema**：`ConversationEntity` 需新增三列（JSON 字符串存储），对应一次 Room migration（v2 → v3）。

### 5.2 运行时工具解析（getAllAvailableTools 改造）

核心改造点：`McpManager.getAllAvailableTools()` 从"按 assistant.mcpServers 过滤"改为"按 assistant 候选集 + conversation delta 联合解析"。

```
实际暴露给模型的工具集合 = 

  [内置工具]
    助手 localTools
    × conversation.localToolOverrides（按工具名覆盖启用状态）
    
  + [排它类 MCP：每个 groupId 选 1 个 server]
    对 assistant.mcpServers 中所有 server，按当前 settings.mcpServers 的 groupId 分组
    对每个非空 groupId：
      - 若 conversation.mcpSelection[groupId] 存在且该 serverId 仍有效 → 选中该 server
      - 否则 → 该组无激活（不回退默认，见 §6.1 隐患1）
    激活 server 的全部工具

  + [非排它类 MCP：工具级过滤]
    assistant.mcpServers 中 groupId 为 null 的 server
    每个 server 的工具 ∩ conversation.mcpToolOverrides[serverId]（缺失=全启用）
```

**关键：幽灵 server 处理**（见 §6.1）
解析 `mcpSelection[groupId] → serverId` 时，若 serverId 不在 `settings.mcpServers` 或 `assistant.mcpServers` 中，视为该组**无激活**，跳过该组（不抛异常，不回退默认）。

### 5.3 调用链影响

- `GenerationHandler.generateText()`：新增 `conversationMcpSelection` / `conversationMcpToolOverrides` / `conversationLocalToolOverrides` 参数，透传到工具构建
- `ChatService.handleMessageComplete()`：从 `conversation` 读取三个 delta 字段，传入 generationHandler
- `McpManager.getAllAvailableTools()`：签名扩展，接收 conversation delta；连接管理逻辑不变（仍全局）

### 5.4 UI 信息架构（初步）

对话内的工具选择面板（输入栏"+"入口），分三个区域：

1. **排它设备组**（若有）：每个 groupId 一个 RadioGroup，候选 = 助手候选集内同组 server
2. **MCP 工具**（非排它）：助手候选集内独立 server 的工具，按 server 折叠，Checkbox 控制
3. **内置工具**：助手 localTools，Checkbox 控制

默认收起，仅展开有内容的区域。移动端 UX 挑战见 §6.3。

---

## 六、风险与隐患（诚实记录）

这些是在方案推演中识别出的真实问题，实现前必须有明确应对。

### 6.1 Delta 存储的"幽灵 server"问题

**场景**：
1. 助手候选集 = {设备A_MCP, 设备B_MCP}（同组 "measure"）
2. 对话 1 的 `mcpSelection = {"measure" → 设备A_MCP}`
3. 用户删除了 设备A_MCP（全局 MCP 列表移除，或从助手候选集移除）
4. 对话 1 的选择指向不存在的 server

**应对**：运行时解析时做存在性校验。选中的 serverId 无效 → 该组**回退到"无激活"**（不崩溃、不自动选其它、不回退到默认）。UI 层应在配置变更后提示用户"该对话的设备选择已失效，请重新选择"。

### 6.2 groupId 变更的语义

**场景**：用户给 设备A_MCP 设 `groupId="measure"` 建对话后，把 设备B_MCP 的 groupId 改成 `"sensor"`。

**判定**：**这不是 bug，是合理行为**。`mcpSelection` 按 serverId 锚定，groupId 是 server 的当前属性。`getAllAvailableTools` 动态按当前 groupId 重新计算分组视图。只要选中的 serverId 仍存在且仍属于该组，选择有效。

**实现要求**：语义必须是"serverId 锚定 + groupId 当前视图"，避免任何"按 groupId 名称硬绑定"的歧义。

### 6.3 移动端 UI 复杂度

对话工具选择面板需同时表达三种交互范式（排它 RadioGroup / MCP 工具 Checkbox / 内置工具 Checkbox），且有前置依赖（非排它 server 需先在候选集才显示其工具）。

**风险**：手机小屏上易用性是真实挑战，可能成为整个改造的体验瓶颈。

**应对方向**（待 UI 设计时细化）：
- 分级折叠，默认收起
- 仅展开当前有 delta 的区域
- 排它组优先置顶（最高频操作）
- 考虑"预设"机制：常用工具组合存为助手模板，减少对话内操作

### 6.4 协议字段向后兼容的边界

`groupId` 字段向后兼容有保障（null 默认值 + ignoreUnknownKeys）。但需注意：
- **备份恢复**：旧备份的 `McpServerConfig` 无 groupId，恢复后全部视为独立 MCP——符合预期
- **分享格式**：`encodeForShare` 是否带 groupId 见 §7.2 待定
- **Migration**：`ConversationEntity` 新增三列需要 Room migration（v2 → v3），是项目第一次"正式"schema 演进，应作为 Migration 范式参考

### 6.5 getAllAvailableTools 的签名破坏性

`getAllAvailableTools()` 当前无参（内部读 `getCurrentAssistant()`）。改造后需接收 conversation delta，是**签名破坏性变更**。所有调用方（`ChatService`）需同步更新。需排查是否有其他调用点（搜索全仓确认）。

---

## 七、待定问题

以下问题尚未拍板，实现前需逐个确认。

### 7.1 排它组"无激活"时的模型行为

对话的 `mcpSelection["measure"]` 指向的 server 已删除（幽灵 server），该组回退到"无激活"。但另一种边界：**用户从未在该对话做过选择**（`mcpSelection` 为 null 或不含该组）时，该组默认行为是什么？

- 选项 A：默认无激活（用户必须主动选）——安全，但每次新对话都要操作
- 选项 B：默认激活组内第一个 server——省事，但"第一个"的定义不稳定（依赖排序）
- 选项 C：默认激活助手候选集里该组"最近用过"的 server——智能但状态复杂

**待定**。倾向 A（显式优于隐式），但需结合 UX 评估摩擦。

### 7.2 MCP 分享格式是否携带 groupId

`encodeForShare` 生成的二维码/分享文本，是否包含 `groupId`？

- **不带（当前倾向）**：分享给别人就是"一个独立 MCP"，接收方自己归类。理由：groupId 是本机组织逻辑，跨设备/跨用户时 groupId 名称可能撞名或无意义。
- **带**：保留分组信息，便于批量导入同组设备。但跨设备 groupId 语义不可靠。

**待定**。倾向不带，但若用户的实际使用是"批量导入一批同协议设备"，则带更方便。

### 7.3 助手候选集与 groupId 的关系

助手配置 `mcpServers`（候选集）时，是否应该**约束**：同 groupId 的 server 要么都进候选集、要么都不进？还是允许"助手候选集里只有同组的部分 server"？

- 若允许部分：用户可能在助手层只选了 设备A（不选 设备B），那对话层的"排它选择"就没意义了（只有一个候选）
- 若强制全进全出：助手配置变重，且与"助手管可能性"的语义有张力

**待定**。这是助手层与对话层职责边界的细化问题。

### 7.4 工具名冲突的处理

非排它 MCP 的工具级过滤中，若两个独立 server 有同名工具（如都有 `search`），当前 `getAllAvailableTools` 用 `mcp__${serverName}__${tool.name}` 命名规避了冲突。但排它组切换时，工具名前缀的 serverName 部分会变化，模型上下文里的工具名会跳变。

**待定**。是否需要"排它组用统一虚拟名"（如 `mcp__measure__read` 而非 `mcp__deviceA__read`），让切换设备时工具名稳定？这涉及模型上下文连续性。

### 7.5 自动上下文压缩（原始诉求之二）与本改造的关系

原始三项诉求里，"自动上下文压缩"与本 MCP 改造是独立的。MCP 改造会增加对话内的工具数量波动（排它切换），间接影响上下文长度。两个改造的优先级与顺序需整体规划，不在本文档范围。

---

## 八、实施前置条件

在动工前需确认/完成：

1. **复核 §七 全部待定问题**，尤其 7.1（无激活默认行为）和 7.4（工具名冲突），这两个会直接影响数据模型最终形态
2. **UI 信息架构设计**：§5.4 是初步构想，移动端面板的具体交互需先出设计稿/原型，避免实现后返工
3. **调用点排查**：`getAllAvailableTools` 全仓调用点梳理，确认签名变更的影响范围
4. **Migration 范式**：v2 → v3 是项目第一次正式 schema 演进，应作为后续 Migration 的参考范式编写

---

## 九、决策溯源（探讨过程要点）

记录关键探讨节点，便于日后理解"为什么是这个方案"。

| 节点 | 讨论内容 | 结论 |
|---|---|---|
| 业界调研 | 6 个主流客户端是否做会话级 MCP 绑定？ | 无一做。否决"按对话隔离连接"，确认"全局连接池" |
| 真实场景挖掘 | "以 MCP 为中心"到底指什么？ | 多设备同协议 MCP 的排它选择。非"扫码即用"或"对话级配置" |
| 互斥约束 | 排它关系怎么表达？ | 协议级 `groupId` 字段（方案 A），非靠 UI 行为 |
| 选择粒度 | server 级还是 tool 级？ | 排它→server 级，非排它→tool 级 |
| 内置工具 | 是否纳入模型？ | 纳入，按非排它类对待 |
| 存储模型 | delta / 结果 / 快照？ | Delta 偏移，null 继承助手 |

### 被否决的原始设计（重要修正）

原始报告（`report-rikkahub-fork-analysis.md` §4.3 改造三）的两个核心设计**已被本文档否决**，未来实现时**不要参考原始报告的这一部分**：

1. ❌ `Conversation.mcpServerIds: Set<Uuid>`（简单存 server id 集合）→ 改为 Delta 偏移模型（`mcpSelection` + `mcpToolOverrides` + `localToolOverrides`）
2. ❌ "McpManager 支持按对话 ID 管理连接生命周期"→ 改为全局连接池 + 对话级工具过滤（解耦）

**否决理由**：原始设计混淆了"连接管理"与"工具暴露"两个正交层面，且未识别"多设备排它"这个真实场景。本文档的三层模型 + groupId + Delta 是对原始设计的根本性修正。

---

*文档结束。本文档为活文档，待 §七 待定问题解决后更新；实现启动后转为实现记录。*
