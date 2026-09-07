# 应用架构

本文定义 Pilot 当前的产品语义、模块边界、事实所有权和跨领域协议，是专题参考的总入口。具体字段、状态矩阵、线协议与界面规则由相应专题维护，不在总览重复。代码与静态契约是实现事实；发现不一致时核对代码并同步修正文档。

## 产品语义

Pilot 是 Android 本地 Agent 工作台。一个 Conversation 保存用户可见历史与分支；一个 Assistant message variant 对应一次完整 Turn；Step 表示一次逻辑模型采样及其完整工具批次。

```text
Conversation
  └─ Turn
      └─ Step
          └─ Tool Call
              ├─ Tool Interaction：执行前的审批或提问
              ├─ Tool Execution：副作用开始后的执行事实
              └─ Tool Result：供后续采样回放的结果
```

Step 以 `UIMessagePart.Step` 保存在 owning Assistant transcript 内，不创建独立 Step 表或顶层消息。用户交互继续原 Turn/Step，Provider 透明重试也不构成新 Step。子助手是同一执行链上的 Child Conversation/Turn，不是第二套生成引擎。

## 模块与依赖

| 模块 | 职责 |
| --- | --- |
| `app` | Compose、application services、会话/配置/文件领域、Room/DataStore 与 Android 集成 |
| `ai` | Provider 抽象与线协议、消息和工具基础类型、usage 归一化；不拥有会话执行生命周期 |
| `common` | 跨模块 Kotlin/Android 工具 |
| `document` / `highlight` | 文档解析、原生代码语法高亮 |
| `material3` | Material You 动态配色扩展 |
| `search` / `speech` | 搜索 SDK、TTS 与 ASR adapter |
| `workspace` | PRoot、Rootfs、文件与进程执行基础能力 |

```text
UI / ViewModel
  → application command / query port / typed use case
      → domain owner / runtime state machine
          → persistence or external-system adapter
```

UI 不持有 DAO、ConversationRepository、Runtime Registry、Artifact/GeneratedMedia Store、payload 层或 Provider 容器。Query 组合只读事实，不反向发起 mutation；application service 负责编排，不创建第二套持久化协议。

聊天页面通过携带原域授权的 `ConversationOpenRequest` 显式区分新建与打开已有会话，Application 校验成功后交付
`ConversationViewLease`。Query 以该 lease 约束页面投影，ViewModel 负责关闭页面导入资源；域或 Session 变化使旧页面失效。
此页面边界与会话命令、Turn 和文件 owner 各自的授权职责分开，不能用页面检查替代执行时的授权。

边界按事实所有权和操作语义选择：单 owner 的操作直接扩展既有 typed contract；跨 owner、补偿或外部 SDK 流程才增加 application 编排。SettingsStore 和 SkillManager 可通过各自 typed contract 服务配置编辑；不能为了层数增加无语义的透传 facade。页面草稿、弹窗和选择态归 UI，跨页面存活的 Job、session 与资源归 application owner。

## 事实与唯一 owner

| 事实或流程 | 唯一 owner 与入口 |
| --- | --- |
| Conversation 命令串行化与 commit-then-publish | `ConversationCommandCoordinator`；application services 与领域 coordinator 提交命令 |
| Conversation header/tree/variant | `ConversationTransition`；只产生结构 mutation |
| Turn/Step/Tool transcript | `TurnTransition`；通过同一 command gate 与 Room 事务提交 |
| Resident snapshot、streaming、active session | `ConversationRuntime`；`ConversationRuntimeRegistry` 管加载与生命周期，UI 只消费 presentation |
| Conversation 事务持久化 | `ConversationRepository`；执行 command 产生的精确 delta 与 execution facts |
| Turn 输入与上下文冻结 | `ConversationTurnService` / `TurnContextFactory`；工具集合由 `TurnToolSetFactory` 一次装配 |
| 多 Step / 单 Step / 工具批次 | `TurnRunner` / `StepRunner` / `ToolBatchRunner`；单 Call 解析与执行包装归 `ToolCallRuntime` |
| start、checkpoint、stream 与结果提交适配 | `TurnCommitter`；用户与 Child 共用 |
| stop、failure、cancel 终态准备 | `TurnFinalizer`；恢复使用 `TurnRecovery` |
| 子助手 run / lineage 与 retention | `SubAssistantRunCoordinator` / `SubAssistantLifecycle`；run 并发归 `SubAssistantRunGate` |
| 标题 | `ConversationTitleCoordinator`；模型结果与手动标题串行，token + expected-title CAS |
| 会话读模型 | `ConversationQueryService` 与专用 reader/query port；目录与 Pager 归 Query，Repository/DAO 提供带 scope 的查询及 PagingSource，原 Session 校验归 EnterpriseSessionController |
| 当前域统计 | `StatsQueryService` 在原选中域/Session 内聚合；StatsVM 负责取消旧查询及清空旧显示 |
| 运行记忆 | `MemoryRepository` 唯一写入；`MemoryService` 编排原域 Session、配置授权和 UI 投影，见 [运行记忆](memory-architecture.md) |
| Artifact metadata、reference、生命周期 | `ArtifactStore`；`ArtifactPayloadStore` 只做磁盘 IO，不持有 DAO |
| Settings 图片 roots | `ArtifactSettingsCoordinator`；与 Settings、Artifact owner 交接 |
| 图库生成媒体 row、payload 与删除恢复 | `GeneratedMediaStore` |
| 跨文件 owner 命令与列表 | `FileManagementApplicationService` / `FileManagementQueryService`；不成为第三个文件 owner |
| 内部 attachment handle 索引 | `AttachmentReferenceLookup`；查询投影，不是文件读取授权 |
| Local Settings 与唯一有效读模型 | `SettingsStore`；内部 normalization、managed storage/resolver 与 write rules 不另发状态流 |
| Provider 配置与连接探测 | `ProviderSettingsApplicationService`；协调 SDK 与 SettingsStore |
| Skill 身份、文件树与发布 | `SkillManager`；typed parse、导入、读取和可恢复目录事务 |
| MCP definition / catalog / runtime / OAuth | 分别归 `SettingsStore` / `McpCatalogStore` / `McpServerRuntime` / `McpOAuthCoordinator`；`McpRuntimeCoordinator` 跨 server 编排 |
| Workspace 命令、只读投影、PTY | `WorkspaceApplicationService` / `WorkspaceQueryService` / `WorkspaceTerminalRuntime`；模型与 UI mutation 共用 Workspace command gate |
| 备份恢复请求与 archive staging | `BackupRestoreApplicationService` / `BackupArchiveService`；`PendingBackupRestore` 执行可恢复发布 |
| 应用启动恢复与全局写门禁 | `ApplicationRecoveryCoordinator` / `ApplicationRecoveryGate` |
| 生成期后台保活 | `ChatGenerationForegroundService` / `GenerationForegroundLifetime`；只消费活动投影，不拥有运行事实 |

同一 durable 事实只有一个 owner 和一个写协议。禁止旁路 DAO/Repository 写入、整聚合回写、服务定位器、兼容转发和第二状态源。

## 会话写入与执行协议

```text
application command
  → 校验身份、epoch 与当前 owner
  → ConversationTransition / TurnTransition 产生 mutation
  → ConversationRepository 的单一 Room transaction
  → publish committed snapshot
```

- Runtime 明确区分 Loading、Draft、Ready、Missing、Failed。空 Draft 不落库、不进入会话列表；首条 AppendUserMessage 同事务建库并原位晋升 Ready。
- 持久化失败不发布 next snapshot。Streaming 是唯一允许先发布且不落库的会话状态，只携带 owning Assistant，旧 Turn/epoch 的迟到输出被拒绝。
- Durable 流程只读 ConversationAggregateSnapshot；UI 只读 ConversationPresentationSnapshot。显示列表不能反向作为写入事实。Non-resident command 使用同一 command gate，不能退回 Repository 旁路。
- START 同事务建立 Assistant variant、首个 Step 与 turn fact；用户交互继续原 owner。工具参数纯校验先于审批，副作用前才创建 STARTED execution，最后结果与下一 Step 的创建原子提交。
- Tool Call、Interaction、Execution 与 Result 各有 typed 事实。resultStatus 决定可回放结果是否存在；output 空与否不决定执行状态，live phase 也不能反向充当 durable truth。
- 取消向上传播；NonCancellable 只用于已取得所有权的终态提交或补偿交接。终态不可回退，未知副作用不自动重试。

具体命令、checkpoint、暂停、取消与标题协议见 [Turn/Step 执行](turn-step-execution.md)。

## 请求上下文与模型边界

每个 Turn 在 START 冻结 Assistant、Model、Provider wire、prompt 与工具 definitions/bindings。Step 从冻结上下文和已提交 transcript 派生请求；工具执行仍由原 owner 实时复核权限、资源与撤销状态。Provider 凭据通过精确 owner lease 刷新，不重新选择模型或 endpoint。

`RequestAssembler` 是 UIMessage 到 ModelRequestMessage 的唯一转换边界；Provider 不直接消费 durable 会话聚合。请求预算优先于 prompt-cache 前缀稳定：只有成功请求实际消费的历史 inline Tool Result 才可滚动压缩，规划与落盘职责分开。会话级自动摘要尚未实现。

Turn 累计 usage 与逐 Step 计量由同一请求事实派生、同事务保存，不互相反推。请求窗口与披露见 [请求上下文](request-context.md)，wire 见 [Provider 协议](protocol-reference.md)，计量见 [Token usage](token-usage-accounting.md)。

## 资源所有权与补偿

未发布 Artifact/生成媒体必须通过 typed lease 显式交接，checkpoint 成功后发布，失败或取消精确回滚。元数据、引用和 payload 生命周期分别由其领域 owner 管理；文件管理服务只协调，不统一成第三套状态机。

Settings 与文件删除跨 owner 时，使用可恢复暂存和同一 Settings 写协议；提交拒绝必须恢复原树，不能隐藏引用或另写 Settings。内部 `attachment:<uuid>` 只用于索引；对模型披露真实 `/upload` 路径，读取授权由 ArtifactStore 校验，不依赖 UI 扫描或当前分支引用。

资源创建、删除、GC、读取与发布契约见 [多模态与持久化](multimodal-context-and-turn-durability.md)。Skill 与 Workspace 分别在 [配置与资源](android-configuration-architecture.md)、[Workspace](workspace-architecture.md) 定义文件事务。

## 启动恢复

`ApplicationRecoveryCoordinator.recoverNow()` 按固定顺序执行：

```text
pending backup restore
  → Settings/effectiveSettings（BLOCKED 时停止）
  → 企业配置恢复（企业错误保留为企业不可用状态）
  → Artifact reconcile → GeneratedMedia reconcile
  → reference projection → FTS projection
  → Child run recovery → Master turn recovery
  → pending assistant deletion
  → post-recovery maintenance → pending backup complete
  → Ready
```

未被领域 owner 收口的恢复异常进入 Failed，全局 durable write 门禁保持关闭；retry 重跑同一幂等顺序。企业配置校验失败由 EnterpriseSessionController 发布，个人数据恢复继续；取消仍向上传播。文件 command/query 同样等待门禁，不能在删除状态和孤儿 payload 尚未收口时访问托管文件。TurnRecovery 只查询非终态执行事实；缺 owning message 或损坏 payload 是完整性错误，不以空树、默认对象或 best-effort 写入伪装 Ready。

恢复顺序归应用 coordinator，各领域恢复算法仍归原 owner。恢复链在可注入的 IO dispatcher 执行；助手清理服务使用同一 DI singleton 的 Lazy 引用，在原清理步骤首次解析，避免进程主线程为启动门禁提前构造完整生成依赖链。失败与重试仍经过同一 gate。TurnFinalizer 不接管启动恢复，SubAssistantLifecycle 不另建生成或 Turn 终态写链。

## 持久化与演进

Room/DataStore/文件协议按长期数据保全演进。结构变化必须提供显式 migration、fresh schema 同构与历史数据验证；索引随实体和 migration 维护，不由业务请求临时创建。备份先在 staging 升级和验证，成功后才发布。

兼容只存在于明确的持久化迁移和外部协议解析边界。架构迁移同次删除旧 facade、fallback、deprecated 转发、过渡命名与无调用协议，不能以双路径掩盖不一致。未来配置或工具来源应从既有 TurnContextFactory/TurnToolSetFactory 接入，有真实消费者后再扩展合同；不预埋无消费者的 schema。下一步企业阶段目标见 [Android 企业集成计划](../dev/android-enterprise-integration-plan.md)。

验证分层、失败路径、设备要求及门禁命令统一见 [测试策略](testing-strategy.md)。版本号与 changelog 仅随明确的发布需求更新。

## 专题参考

| 领域 | 文档 |
| --- | --- |
| 会话、Runtime、生成、审批、工具与标题 | [`turn-step-execution.md`](turn-step-execution.md) |
| 请求上下文：条数窗口、滚动压缩、披露与摘要 | [`request-context.md`](request-context.md) |
| 多模态上下文与资源持久化 | [`multimodal-context-and-turn-durability.md`](multimodal-context-and-turn-durability.md) |
| 子助手 owner、lineage、retention 与恢复 | [`sub-assistant-architecture.md`](sub-assistant-architecture.md) |
| 子助手多模态输入输出 | [`sub-assistant-multimodal.md`](sub-assistant-multimodal.md) |
| Assistant 配置 | [`assistant-configuration.md`](assistant-configuration.md) |
| Android 配置目录与企业下发边界 | [`android-configuration-architecture.md`](android-configuration-architecture.md) |
| MCP 生命周期、目录与 UI 投影 | [`mcp-architecture.md`](mcp-architecture.md) |
| Provider 线协议 | [`protocol-reference.md`](protocol-reference.md) |
| Token usage、缓存命中与累计口径 | [`token-usage-accounting.md`](token-usage-accounting.md) |
| 模型可见 prompts 与工具结果 | [`prompts-and-tools.md`](prompts-and-tools.md) |
| Compose 导航、布局、主题与图片查看器 | [`ui-architecture.md`](ui-architecture.md) |
| 消息渲染 | [`message-rendering-pipeline.md`](message-rendering-pipeline.md) |
| 数据库查询索引与迁移边界 | [`database-indexing.md`](database-indexing.md) |
| Workspace/PRoot | [`workspace-architecture.md`](workspace-architecture.md) |
| 更新与发布 | [`update-mechanism.md`](update-mechanism.md) |
| 测试分层、契约 owner、CI 与性能测量 | [`testing-strategy.md`](testing-strategy.md) |
