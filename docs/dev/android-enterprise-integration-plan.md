# Android 企业配置与运行集成计划

本文从 Android 配置与助手参考中迁出，保留下一步企业集成的阶段、字段映射和架构约束。它描述待实施目标，不代表当前 Android 已具备这些能力；当前实现见 [配置架构](../references/android-configuration-architecture.md) 与 [助手配置](../references/assistant-configuration.md)。

平台合同来源为 `topabomb/measix-architecture` 的 Enterprise Realm & Experience Contract、Android Managed Runtime Integration Contract、S0 Control Protocol，以及 `topabomb/measix-platform-core/api/client/client-control.openapi.yaml`。实施前应核对冻结合同与 generated DTO；本计划不反向定义新的平台 wire 字段。

## 当前起点

Android 已有 `schemaVersion=1` 的内部签名 `ManagedConfigurationEnvelope`：generation 单调、asset staging、Local shadow、有效投影和提交写门禁。该原型将 Local DTO 放入 `records[]`，使用 Android UUID，经全局 `EffectiveSettingsResolver` 合并 Built-in/Local/Managed。

当前没有 ClientRealm、Enrollment/Session、平台 Snapshot v2/v3 generated DTO、Managed Memory Seed store、Starter projection、Enterprise Update/Portal 或生产同步入口。不能把内部 overlay 当作已完成的企业 Snapshot 集成，也不能在其外包一层 adapter 保留双 owner/双协议。

## 阶段与交付范围

| 阶段 | 平台目标 |
| --- | --- |
| S0.1 / Snapshot v1 | Managed Provider、Model、TTS、HTTP ASR、Direct MCP 与 Policy |
| S0.2 / Snapshot v2 | 保留 v1，增加 Managed Assistant、Managed Memory Seed、Assistant Starter |
| S0.3 / Snapshot v3 | 增加 Enterprise Tool Gateway |
| S0.4 / Android integration | 消费冻结后的完整 profile，完成 Android 运行集成 |

Enterprise Update 使用独立 Feed，不进入 Snapshot；Enterprise Local Assistant Memory 是 User Data，也不进入 Snapshot。S0.2 不包含 Agent Space、服务端 Conversation 或 User Sync。

配置与状态分离：

```text
Built-in defaults + Local configuration + Applied Managed Snapshot
  → Realm 对应的 Effective Configuration
      → Assistant / Model / TTS / ASR / MCP consumers

Enterprise Binding + Credential + Applied Managed State
  → 身份、同步、generation 与交互正确性
```

Enterprise Binding、Refresh Credential、Applied Snapshot payload/元数据必须独立保存，且彼此分离；Access Token 只在内存。它们不属于普通 Local Settings 或手工备份，不能借用 Provider/MCP credential 字段。

Personal Assistant/Prompt/Memory、Search、Skill、Workspace、主题/显示、备份、快捷消息、图片生成和实时 ASR 保持本地或属于后续范围。禁止把整个服务端 payload 直接反序列化为 `Settings`。

不得下发到 Android 的服务端内部事实：`UpstreamDefinition`、`upstreamId`、base/internal URL、企业 API Key/Secret、RuntimeBinding、`runtimeRouteId`、Pricing。客户端确需接收的 `upstreamModelKey` 不在此禁止范围内。

## Managed Assistant 映射

Local `Assistant` 是 UUID 持久化聚合，包含个人运行与 UI 配置；`ManagedAssistantDefinition` 是平台 `asd_*` 身份的只读窄范围 definition。可复用展示与生成消费边界，不能直接互相序列化、复制 ID 或共用持久记录。

| 平台字段/对象 | 可复用的 Android 消费边界 | 必须隔离的现有结构 |
| --- | --- | --- |
| `displayName` / `description` / `systemPrompt` | Assistant 展示与 prompt | 不把完整 Assistant 当作 wire DTO |
| `modelId: mdl_*` | 模型 readiness / 生成选择 | Local `Assistant.chatModelId: Uuid?` |
| `mcpServerIds: mcp_*[]` | MCP discovery / 工具装配 | Local `Assistant.mcpServers: Set<Uuid>` |
| `memorySeed[]` | 请求记忆投影 | 可变 Local `MemoryEntity`，不作为 seed store |
| `AssistantStarterDefinition: str_*` | 快捷消息列表、向输入草稿追加文本 | Local `QuickMessage.id: Uuid` |
| `enabled` | 受管目录可用性 / readiness | Local Assistant 没有同义字段 |

S0.2 不下发 `localTools`、Skill、Workspace、custom headers/body、Regex、Mode Injection、子助手关系、完整 `presetMessages`、头像/背景等 Local schema。新增 definition 字段必须先修改平台合同，不能因 Android 已有字段而自动暴露。

### Realm、身份与提交

- Personal Realm 不出现企业助手；Enterprise Realm 使用 Managed Assistant 与 policy-allowed Enterprise Local，Personal Local 不自动进入。
- Managed 与 Personal/Enterprise Local 保留 origin/provenance；平台 ID 原样保存，不 hash 或转写成 Local UUID。
- Snapshot v2 使用 whole-state commit；generation/hash/schema/release 属于 Applied Managed State。
- Local shadow 不因 Managed 覆盖被删除，移除/断开受管配置后按所属 Realm 恢复。
- UI disabled 只是来源与锁定投影；实际 mutation 必须在 command/commit 边界拒绝。
- 实施时替换原型协议和全局 merge，物理删除无消费者的旧路径，不保留兼容旁路。

### Memory 与 Starter

当前 `MemoryEntity` 只有自增 `id`、String `assistantId` 和 `content`，没有 realm、deployment、managedGeneration 或 provenance。Managed Seed 需要独立 generation-bound 只读投影；有效记忆为 `Managed Seed + Enterprise Local Assistant Memory`，工具只能修改后者。补齐数据模型和 migration 前，不得声称现有 Room 记录支持企业记忆回流。

Quick Message 当前只调用 `ChatInputState.appendText`，不自动发送或执行工具。Managed Starter 可复用该交互语义，但需要独立 `str_*` projection、assistant reference、排序和 enabled 状态，不写入 `Settings.quickMessages` 或生成随机 UUID。

## 运行能力与资源边界

- Managed ASR 是 HTTP multipart transcription；现有 Local ASR 是 WebSocket/realtime controller，不能把 `runtimePath/model/language` 强塞进 realtime 类型，也不能伪造 VAD/sample-rate 字段。
- Search 的本地 API key、URL、账号不复用为 Managed Model/MCP 路由。
- Skill、Workspace、Assistant assets 若扩展为受管资源，先定义签名内容、稳定身份、引用完整性、版本、删除/回滚和同名冲突协议；不继续扩展自由形态的 `JsonObject records`。
- WorkspaceConfig 当前是代码内运行限制；若变成可配置策略，先明确由 Local、Managed Policy 或 Runtime owner 持有。
- 当前 key 轮换缓存含原始 API key，企业 credential 不能复用；手工备份排除不等于系统备份排除，应分别审计 Android backup/data-extraction 配置。

### Gateway 与 Turn 接入

TurnContextFactory 与 TurnToolSetFactory 是新配置、Realm/Gateway 来源进入执行链的接缝。能力与来源在 START 冻结，Step 不刷新模型可见 definitions。只有同时存在读取方时才增加 revision/source stamp。

禁止 Gateway 伪装 McpServerConfig、写入 Assistant.mcpServers、本地下载完整 Gateway Catalog、本地签发 toolRef，或在 Gateway 失败后 fallback Direct MCP。不要为接入来源复制 Conversation、Step、Tool execution 或 checkpoint 协议。

## 实施验证

接入时必须同时交付真实 owner、typed DTO/消费边界、持久化迁移与验证：Realm 隔离、整包原子应用、generation/revoke、凭据隔离、离线与损坏行为、资源引用和 rollback、交互与 Turn 冻结一致性。

JVM/构建不能替代 Enrollment、Keystore、后台同步、网络失败、428/revoke、真实 emulator/device 和服务端互操作验收。未接入的阶段不计为完成。
