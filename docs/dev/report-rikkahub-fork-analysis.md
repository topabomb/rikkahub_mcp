# RikkaHub Fork 改造调研报告（早期调研，已过时）

> **⚠️ 文档状态说明**
> 
> **本文档为早期调研报告（2026-06-18），仅记录当时的调研过程和初步分析。**
> **文档中的信息已过时，不作为当前设计和开发的依据。**
> 
> 如需了解项目最新架构和设计，请参考：
> - `architecture.md` — 当前项目架构文档
> - `fork-simplification-plan.md` — 当前精简版规划
> 
> 调研日期：2026-06-18
> 调研目标：寻找一个结构清晰、架构优秀的开源 Android LLM 聊天客户端，用于 fork 改造
> 核心需求：MCP 支持、自动上下文压缩、以 MCP 为中心的对话入口

---

## 一、项目选型调研

### 1.1 候选项目横向对比

| 项目 | 语言/框架 | Stars | 特点 | 架构清晰度 | Fork 适合度 |
|------|-----------|-------|------|-----------|------------|
| **RikkaHub** | Kotlin + Jetpack Compose | 4.3k | 功能最全，MCP 支持，多 Provider | ⭐⭐⭐ 模块化但膨胀 | ⭐⭐⭐ 可改造 |
| PocketPal AI | React Native | - | 本地推理（llama.cpp），离线优先 | ⭐⭐⭐ 聚焦本地 | ⭐⭐ 方向不同 |
| Maid | React Native + Flutter | - | 本地+远程，llama.cpp 集成 | ⭐⭐ 跨平台但偏重本地 | ⭐⭐ 方向不同 |
| chatgpt-android (skydoves) | Kotlin + Compose | - | 示范项目，Stream Chat SDK | ⭐⭐⭐⭐ 极简清晰 | ⭐⭐⭐⭐ 理想骨架但缺 MCP |
| OpenCode | TypeScript (非 Android) | 100k+ | CLI 编程 Agent，上下文压缩优秀 | ⭐⭐⭐⭐⭐ 分层清晰 | N/A（非 Android，参考用） |

### 1.2 结论：选择 RikkaHub

**选择理由**：
- 是唯一同时满足"原生 Android + 多 Provider + MCP 支持"的开源项目
- `ai` 模块（10K 行）的 Provider 抽象层设计优秀，可直接复用
- MCP 基础设施（McpManager、transport 层）已经完备
- 项目活跃度高，版本迭代快（v2.3.1，23 次 DB 迁移）

**核心问题**：功能过多，需要做大量减法。

---

## 二、RikkaHub 代码架构深度分析

### 2.1 项目规模

| 指标 | 数值 |
|------|------|
| Kotlin 文件数 | 510 个 |
| 总代码行 | ~100,000 行 |
| Gradle 模块数 | 10 个 |
| Room DB 版本 | 23 |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 37 |

### 2.2 模块结构

```
rikkahub/
├── app/          (376 files, 79K lines) ← 主应用，占 79%
│   ├── data/     (99 files, 12.7K lines) — 数据层
│   ├── ui/       (220 files, 56.6K lines) — UI 层
│   ├── service/  (3 files, 1.6K lines)   — 核心服务
│   └── di/       (4 files, 510 lines)    — 依赖注入
├── ai/           (42 files, 10K lines)   ← AI SDK 抽象层（精华）
├── search/       (20 files, 3K lines)    — 搜索引擎集成（10+ 引擎）
├── speech/       (29 files, 3.1K lines)  — TTS 语音合成（8 个 Provider）
├── workspace/    (10 files, 1.7K lines)  — 沙盒工作空间(proot)
├── document/     (6 files, 1.1K lines)   — 文档处理
├── highlight/    (4 files, 400 lines)    — 代码高亮
├── material3/    (3 files, 155 lines)    — Material3 扩展
├── common/       (17 files, 1.3K lines)  — 通用工具
└── web/          (3 files, 84 lines)     — Web 服务器入口
```

**核心问题**：`app` 模块占了整个项目 79% 的代码，是典型的"胖模块"。

### 2.3 技术栈

| 类别 | 库 |
|------|-----|
| UI | Jetpack Compose + Material3 + Navigation3 |
| DI | Koin |
| 网络 | OkHttp + Ktor + Retrofit（三套并存） |
| 序列化 | kotlinx.serialization |
| 数据库 | Room（v23，17 次迁移） |
| 异步 | Coroutines + Flow |
| 图片 | Coil |
| MCP | modelcontextprotocol-kotlin-sdk |
| Markdown | JetBrains Markdown |
| JS 引擎 | QuickJS |
| TTS | 8 个 Provider |
| 搜索 | 10+ 个搜索引擎 |
| 分析 | Firebase (Analytics/Crashlytics/RemoteConfig) |

### 2.4 架构优点

**1. `ai` 模块设计优秀（10K 行，可独立复用）**

```kotlin
// Provider 接口 — 无状态设计，泛型约束
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>
    suspend fun generateText(...): MessageChunk
    suspend fun streamText(...): Flow<MessageChunk>
    suspend fun generateImage(...): Flow<ImageGenerationItem>
}
```

- Provider 通过 `ProviderManager` 注册，支持运行时扩展
- `ProviderSetting` 用 sealed class + kotlinx.serialization，序列化干净
- OpenAI 实现分了 `ChatCompletionsAPI` 和 `ResponseAPI` 两条路径
- 有 `KeyRoulette` 做 API key 轮换

**2. Transformer 管道设计（消息处理链）**

```kotlin
interface InputMessageTransformer : MessageTransformer
interface OutputMessageTransformer : MessageTransformer {
    suspend fun visualTransform(...)   // 流式时的视觉转换
    suspend fun onGenerationFinish(...) // 生成完成后的后处理
}
```

责任链模式，输入/输出分离，支持 10+ 个 transformer。

**3. 多模块边界清晰**

`search`、`speech`、`workspace`、`document`、`highlight` 都是独立模块，依赖方向单一。

**4. Koin DI 分层合理**

```
AppModule → DataSourceModule → RepositoryModule → ViewModelModule
```

### 2.5 架构问题

**1. app 模块是 God Object（376 文件，79K 行）**

`ChatService.kt` 是 1353 行的"上帝类"，负责生成、工具调用、MCP、通知、内存管理……全部塞在一起。

**2. 代码重复严重**

`ProviderSetting` 的三个子类（OpenAI/Google/Claude）中，`addModel`、`editModel`、`delModel`、`moveMove`、`copyProvider` 方法是完全相同的拷贝粘贴。

**3. UI 层过于庞大**

`ui/` 占 56.6K 行（总代码的 57%），最大的文件：
- `SettingProviderDetailPage.kt` — 1572 行
- `MarkdownNew.kt` — 1489 行
- `ChatList.kt` — 849 行

**4. ChatService 依赖膨胀**

```kotlin
single {
    ChatService(
        context = get(), appScope = get(), settingsStore = get(),
        conversationRepo = get(), memoryRepository = get(),
        generationHandler = get(), templateTransformer = get(),
        providerManager = get(), localTools = get(),
        mcpManager = get(), filesManager = get(),
        skillManager = get(), workspaceRepository = get()
    )
}
// 13 个依赖
```

**5. ProviderSetting 混入了 Compose UI**

```kotlin
sealed class ProviderSetting {
    abstract val description: @Composable() () -> Unit  // ← UI 代码在数据模型里
}
```

**6. 测试覆盖不足**

46 个测试文件，大部分集中在 `ai` 模块。`app` 模块（79K 行）只有约 6 个测试文件。

**7. 三套 HTTP 客户端并存**

OkHttp、Ktor、Retrofit 共存，是渐进式开发没有统一。

### 2.6 评分总结

| 维度 | 评分 | 说明 |
|------|------|------|
| 模块化 | ⭐⭐⭐ | 外部模块分离好，但 app 是胖模块 |
| 代码质量 | ⭐⭐⭐ | Kotlin 惯用写法不错，但有大量复制粘贴 |
| 架构设计 | ⭐⭐⭐ | Provider/Transformer 管道优秀，整体 MVVM 中规中矩 |
| 可测试性 | ⭐⭐ | ai 模块有测试，app 核心逻辑几乎无测试 |
| 可维护性 | ⭐⭐ | 功能太多，ChatService 是维护噩梦 |
| Fork 友好度 | ⭐⭐⭐ | ai 模块可直接拿走用，但需要砍大量代码 |

---

## 三、上下文压缩机制调研

### 3.1 RikkaHub 的上下文管理（现状）

RikkaHub 有两层机制，但都比较粗糙：

#### 层一：`limitContext` — 硬截断（自动）

**位置**：`ai/src/main/java/me/rerere/ai/ui/Message.kt:272`

- `contextMessageSize = 0` → 不截断
- `contextMessageSize > 0` → 只保留最近 N 条消息
- 智能边界处理：如果截断点落在 tool call/tool result 中间，自动向前扩展

**本质**：这是**丢弃**，不是压缩。被截掉的消息永久丢失。

#### 层二：`compressConversation` — LLM 摘要压缩（手动）

**位置**：`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:848`

**触发方式**：纯手动，用户点击"压缩上下文"按钮。

**流程**：
1. 分割：前 N 条压缩 + 后 N 条保留
2. 分块：超过 256 条的消息用二分递归分块
3. 并行压缩：每个 chunk 异步调用 LLM 生成摘要
4. 替换：摘要作为 user 消息 + 保留的最近消息 = 新对话

**用户可配置参数**：
- `targetTokens`：500 / 1000 / 2000 / 4000
- `keepRecentMessages`：0 / 16 / 32 / 64
- `additionalPrompt`：额外的压缩指令
- `compressModelId`：用哪个模型来压缩

#### 自动压缩：**完全没有**

搜索整个代码库，没有任何自动压缩逻辑：
- 没有 token 计数达到阈值时自动触发
- 没有消息数量超过阈值时自动触发
- 没有在生成流程中嵌入自动压缩检查

### 3.2 OpenCode 的上下文管理（参考标杆）

OpenCode 有三层机制，远比 RikkaHub 精细：

#### 层一：Prune（自动裁剪工具输出）

**配置**：`"compaction": { "prune": true }`

从消息历史末尾向前追溯，累加工具结果的 token 数。保护最近 **40,000 tokens** 的工具输出，超出部分被替换为 `"[Old tool result content cleared]"`。

```
PRUNE_PROTECT = 40,000 tokens
PRUNE_MINIMUM = 20,000 tokens（裁剪节省量 > 20K 才执行）
```

#### 层二：Compaction（LLM 摘要压缩，自动触发）

**核心文件**：
```
packages/opencode/src/session/
├── compaction.ts    ← 压缩核心逻辑
├── overflow.ts      ← 溢出检测
└── prompt.ts        ← ReAct 循环（触发压缩的入口）
```

**自动触发机制**：在 ReAct 循环中，当 LLM API 返回 context window 溢出信号时，自动触发压缩。

```typescript
// prompt.ts 的 ReAct 循环中
const result = await processor.process({ system, messages, tools, model })
if (result === "compact") {
    yield* compaction.create({
        sessionID, agent, model,
        auto: true,      // 自动触发
        overflow: true   // 溢出触发
    })
}
```

**overflow 模式的特殊处理**：
1. 从触发点往前找上一条真实 user 消息作为 replay 点
2. 媒体附件被替换为文本占位符：`[Attached image/png: filename]`
3. 工具输出被截断到 `toolOutputMaxChars: 2000`

**压缩执行流程**：
1. 找到历史上的压缩记录，取上次摘要作为 `previousSummary`
2. 将消息分为 head（待压缩）和 tail（保留原文）
3. 触发插件钩子 `experimental.session.compacting`
4. 将 head 转为 modelMessages（stripMedia: true，toolOutputMaxChars: 2000）
5. 调用 **small_model**（如 claude-haiku）生成摘要
6. 传入 `previousSummary` 实现增量压缩

#### 层三：手动 `/compact`

用户可以通过 `/compact` 或 `Ctrl+x c` 手动触发。

#### 对比总结

| 维度 | OpenCode | RikkaHub |
|------|----------|----------|
| **自动压缩** | ✅ 上下文溢出时自动触发 | ❌ 纯手动 |
| **Prune（工具输出裁剪）** | ✅ 保护最近 40K token | ❌ 无 |
| **Token 精确计数** | ✅ 基于 API usage + 字符估算 | ❌ 只有消息条数 |
| **增量压缩** | ✅ previousSummary 传给 LLM | ❌ 每次全量 |
| **媒体处理** | ✅ 压缩时替换为文本占位符 | ❌ 无 |
| **工具输出截断** | ✅ 2000 char limit | ❌ 无 |
| **small_model 策略** | ✅ 用便宜模型压缩 | ⚠️ 可配置但无 small_model 概念 |
| **消息边界保护** | ✅ 不切断 tool call/result 对 | ✅ limitContext 有类似逻辑 |

### 3.3 OpenCode 的 Token 计算机制

两种方式并存：

**1. API 返回的真实 token 数（主数据源）**

每次 LLM API 调用返回的 `usage` 字段，直接采信：

```typescript
tokens: {
    input: number,      // 输入 token
    output: number,     // 输出 token
    cache: { read: number, write: number }  // 缓存 token
}
```

**2. 字符数估算（用于压缩前的预判）**

```typescript
function estimate(text: string): number {
    return Math.ceil(text.length / 4)  // 平均每个 token 约 4 个字符
}
```

**溢出判断**：

```typescript
function isOverflow(input) {
    const context = input.model.limit.context  // 模型上下文窗口
    const count = input.tokens.input + input.tokens.cache.read + input.tokens.output
    const output = Math.min(input.model.limit.output, OUTPUT_TOKEN_MAX)
    return (count + output) > context  // 已消耗 + 预留输出 > 窗口
}
```

### 3.4 LLM Cache 机制说明

#### 什么是 Prefix Cache

LLM 推理是自回归的（从左到右逐 token 生成）。Prefix Cache 是服务端缓存已处理过的 token 的 KV 矩阵。

```
请求1: [A B C D E F G] → 计算全部 KV，缓存 [A B C D E F G]
请求2: [A B C D E F G H I] → 前7个匹配，只计算 [H I]
请求3: [A B C D X Y Z] → 第5个变了，从 E 开始重新计算
```

**关键约束：必须从头匹配，中间断了后面全废。**

#### Agent 端需要做什么

核心事情：**让请求的前缀尽可能稳定、可复用**。

```
正确顺序:
1. 固定的系统指令（不变，可缓存）
2. 项目上下文 / AGENTS.md（很少变，可缓存）
3. 工具定义（很少变，可缓存）
4. 对话历史（每次增长，但旧部分不变）
5. 当前用户消息（每次都变）
```

各家 Provider 对缓存命中区的处理：
- Anthropic：需要显式标记 `cache_control: { type: "ephemeral" }`
- OpenAI：自动检测相同前缀（至少 1024 tokens）
- Google：显式创建 cached content 资源

#### Prune 与 Cache 的矛盾

Prune 会改变旧消息内容，破坏 prefix cache。但这是刻意的取舍：
- 被 prune 的是旧消息，不在 prefix 高命中区（高命中区是 System + Tools）
- prune 的目的是避免更昂贵的 compaction
- "小亏换大赚"的工程决策

---

## 四、Fork 改造方案

### 4.1 三项核心改造

#### 改造一：自动上下文压缩（中等，3-5天）

**需要新增/修改**：

| 改动 | 涉及文件 | 工作量 |
|------|---------|--------|
| Token 估算器 | 新增 `TokenEstimator.kt` | ~50行 |
| 溢出检测 | 新增 `OverflowDetector.kt` | ~80行 |
| Prune 逻辑 | 新增 `ToolOutputPruner.kt` | ~120行 |
| 自动触发点 | 修改 `GenerationHandler.generateText()` | ~30行改动 |
| 增量压缩 | 修改 `ChatService.compressConversation()` | ~50行改动 |
| 配置项 | 修改 `PreferencesStore` + `Settings` | ~30行 |

**代码量预估**：新增 ~400行，修改 ~100行

**难点**：
- `GenerationHandler` 的 Flow 结构需要在每次生成前检查溢出
- 需要处理"正在生成时触发压缩"的边界情况
- 要确保 prune 和 compaction 不破坏正在进行的 tool call 对

#### 改造二：移除无用功能（简单，1-2天）

**可删除功能**：

| 功能 | 相关文件 | 行数 |
|------|---------|------|
| Lorebook/世界书 | Assistant.kt 中的定义 + PromptPage.kt + AssistantExtensionsPage.kt | ~1700行 |
| PromptInjectionTransformer | 整个文件（ModeInjection 需分离保留） | 269行 |
| 酒馆角色卡导入 | AssistantImporter.kt | ~200行 |
| Lorebook 相关测试 | PromptInjectionTransformerTest.kt | 1300行 |
| DB migrations | 整个 migrations 目录 | ~475行 |
| DataStore migrations | 整个 migration 目录 | ~353行 |
| Migration 测试 | androidTest/migrations | ~50行 |
| **总计** | | **~4350行** |

**注意**：`PromptInjectionTransformer` 中的 ModeInjection（学习模式等开关注入）是有用的，需要和 Lorebook 部分分离后保留。

#### 改造三：MCP 为中心的入口界面（较大，5-8天）

**现状**：MCP 绑定在 Assistant（助手）级别。
```
全局 Settings.mcpServers: List<McpServerConfig>  ← 全局 MCP 服务器列表
Assistant.mcpServers: Set<Uuid>                  ← 助手关联的 MCP ID
McpManager                                       ← 全局单例，管理所有连接
```

**目标**：每次新建对话可重配 MCP，支持扫码登记。

**需要改的地方**：

| 改动 | 说明 | 工作量 |
|------|------|--------|
| 数据模型 | `Conversation` 新增 `mcpServerIds: Set<Uuid>` | ~20行 |
| DB schema | `ConversationEntity` 新字段，version=1 直接包含 | ~50行 |
| 新建对话 UI | 重做"新建对话"界面，加入 MCP 选择器 | ~300行新 UI |
| ChatService | 对话用自己的 MCP 配置 | ~100行改动 |
| McpManager | 支持按对话 ID 管理连接生命周期 | ~200行改动 |
| 扫码功能 | QR 扫码 → 解析 MCP URL → 自动添加 | ~300行新代码 |
| MCP 配置编辑 | 对话内快捷编辑 MCP 服务器 | ~200行 |
| 路由 | 新增路由、修改导航流程 | ~50行 |

**难点**：
- **McpManager 当前是全局单例**，需要改成按对话隔离
- 需要定义 MCP 的 QR 码格式（JSON？URL？）
- MCP 服务器的 CRUD 需要同时支持全局预设 + 对话级临时添加

### 4.2 DB Migration 清理方案

新 app 从零开始，所有 migration 纯粹是历史包袱，**全部砍掉**。

**AppDatabase 改为**：

```kotlin
@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        // 按需保留
    ],
    version = 1,  // ← 从 1 开始
    // autoMigrations 全部删掉
)
```

**删除清单**：

| 删除项 | 行数 |
|--------|------|
| `data/db/migrations/` 整个目录 | ~475行 |
| `data/datastore/migration/` 整个目录 | ~353行 |
| `androidTest/migrations/` 目录 | ~50行 |
| AppDatabase 中的 autoMigrations 列表 | ~20行 |
| DataStore 中的 migration 注册代码 | ~30行 |
| **总计** | **~928行** |

新 app 的 Entity 就是 schema，以后自己加字段自己写 migration。

### 4.3 总体评估

| 任务 | 难度 | 工作量 | 风险 |
|------|------|--------|------|
| 移除无用功能 + 清理 migration | ⭐ | 1-2天 | 低 |
| 自动上下文压缩 | ⭐⭐⭐ | 3-5天 | 中 |
| MCP 为中心入口 | ⭐⭐⭐⭐ | 5-8天 | 高 |
| **总计** | | **9-15天** | |

### 4.4 建议实施顺序

```
第一阶段: 移除无用功能 + 清理 migration（1-2天）
  → 瘦身代码库，降低后续改动的复杂度
  → 预计删除 ~5000 行代码

第二阶段: 自动上下文压缩（3-5天）
  → 核心功能，不依赖 MCP 改动
  → 参考 OpenCode 的三层机制设计

第三阶段: MCP 为中心入口（5-8天）
  → 最大改动，需要在瘦身后的基础上做
  → McpManager 从全局单例改为按对话隔离
```

### 4.5 值不值得 Fork？

**值得**，原因：
1. `ai` 模块的 Provider 抽象层直接可用（10K 行精华）
2. MCP 基础设施（McpManager、McpConfig、transport 层）已有
3. 扫码依赖（zxing/quickie）已在项目里
4. Kotlin + Compose 的现代 Android 技术栈

**代价**：
- 改造期间要跟着上游更新节奏
- `app` 模块的 79K 行代码需要持续维护
- McpManager 的架构改动牵一发动全身
- 预计需要砍掉 60%+ 的代码才能得到干净的基底

---

## 五、值得借鉴的设计

### 5.1 从 RikkaHub 拿走的

| 组件 | 行数 | 价值 |
|------|------|------|
| `ai` 模块整体 | 10K | Provider 抽象 + 消息模型 + 工具定义 |
| Transformer 管道模式 | ~500 | 输入/输出消息处理的责任链 |
| Koin DI 分层结构 | ~500 | AppModule → DataSource → Repository → ViewModel |
| MCP 基础设施 | ~1500 | McpManager + McpConfig + transport |

### 5.2 从 OpenCode 学到的

| 设计 | 说明 |
|------|------|
| 三层压缩 | Prune（裁剪工具输出）→ Compaction（LLM 摘要）→ 手动 |
| 自动触发 | 溢出检测嵌入 ReAct 循环主路径 |
| small_model | 用便宜模型做压缩，主模型做对话 |
| 增量压缩 | previousSummary 传给 LLM，避免重复压缩 |
| 字符÷4 估算 | 粗估够用，不需要 tiktoken |

### 5.3 MCP 中心化设计的参考

当前 RikkaHub 的 MCP 绑定在 Assistant 级别，需要改为对话级别。参考设计：

```
全局 MCP 预设（Settings.mcpServers）
    ↓ 选择
对话级 MCP 配置（Conversation.mcpServerIds）
    ↓ 运行时
McpManager 按对话 ID 隔离连接
    ↓ 工具注册
ChatService 动态加载对话的 MCP 工具
```

扫码功能利用已有的 zxing/quickie 依赖，解析 MCP 服务器的 URL 配置。

---

## 附录：文件删除清单（Fork 后立即执行）

### 删除目录
```
app/src/main/java/me/rerere/rikkahub/data/db/migrations/     ← 整个目录
app/src/main/java/me/rerere/rikkahub/data/datastore/migration/ ← 整个目录
app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/ ← 整个目录
```

### 删除文件
```
app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt
app/src/test/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformerTest.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptPage.kt  (Lorebook 部分)
app/src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt    (酒馆导入部分)
app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt (酒馆导入)
```

### 修改文件
```
app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt
  → version = 1, 删除 autoMigrations 列表

app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
  → 删除 migration 注册代码

app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt
  → 删除 lorebookIds、modeInjectionIds 等 Lorebook 相关字段
  → 保留 localTools、mcpServers 等有用字段
```

---

*报告完成。以上分析基于对 rikkahub 仓库的直接代码审查和 OpenCode 的公开资料分析。*

---

> **⚠️ 重要提醒**
> 
> **本文档为早期调研报告（2026-06-18），仅记录当时的调研过程和初步分析。**
> **文档中的信息已过时，不作为当前设计和开发的依据。**
> 
> 如需了解项目最新架构和设计，请参考：
> - `architecture.md` — 当前项目架构文档
> - `fork-simplification-plan.md` — 当前精简版规划
> 
> 本文档仅供历史参考，不应用于指导当前的开发工作。
