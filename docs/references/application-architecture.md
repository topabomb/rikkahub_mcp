# 应用架构

本文定义 Pilot 当前的产品语义、模块边界、事实所有权和跨领域协议，是专题参考的总入口。具体字段、状态矩阵、线协议与界面规则由相应专题维护，不在总览重复。实现以当前代码和行为测试核实，静态契约只约束依赖与所有权；发现不一致时先查明原因，再同步修正文档。

## 产品语义

Pilot 是 Android 本地 Agent 工作台。一个 Conversation 保存用户可见历史与分支；一个 Assistant message variant 对应一次完整 Turn；Step 表示一次逻辑模型采样及其完整工具批次。

执行层次为 Conversation → Turn → Step → Tool Call；持久化消息则是带分支和 variant 的树，
不能把两者当成同一层级模型。Step 以 `UIMessagePart.Step` 保存在所属 Assistant transcript 内，
不创建独立 Step 表或顶层消息。交互继续原 Turn/Step，Provider 透明重试也不构成新 Step。

## 术语与阅读约定

| 术语 | 本仓库含义 |
| --- | --- |
| Owner（责任组件） | 某类事实或资源的唯一写入/生命周期管理者；协作组件通过其协议提交动作 |
| Durable state（持久状态） | 事务成功后可恢复的事实；与流式内存投影、页面状态分开 |
| Command / Query | 命令改变事实；查询在授权边界内生成只读投影，UI 不从投影反推写入语义 |
| Snapshot（快照） | 某一明确边界捕获的值；必须说明捕获时机、范围和适用版本，不暗示跨 owner 原子性 |
| Projection（投影） | 从事实派生的请求或显示表示；不形成第二个可编辑事实源 |
| Turn / Step | 一轮生成执行 / 一次逻辑模型请求及其工具批次；START 创建新轮，CONTINUE 继续原轮 |
| Checkpoint（检查点） | 执行中经事务提交的进度与结果；提交成功后才推进 durable phase 或发布资源 |
| Admission（准入/接纳） | 执行准入检查当前权限；请求上下文接纳保存定稿输入及来源，二者不是同一操作 |
| Lease（资源租约） | 明确持有者及释放责任的使用权；不是永久权限，也不能只凭 ID 重建 |
| Realm / Scope（空间/数据范围） | Realm 表达个人或企业使用上下文；Scope 标识持久数据归属，Session 另表达当前授权 |
| LKG（Last Known Good） | 最近一次完整校验并持久化的目录；不是当前连接可调用性的证明 |
| Disclosure（状态披露） | 应用向模型提供的结构化状态；区分初始、外部变化与窗口恢复，不等同用户新指令 |
| Memory Seed / Starter / Opening | 企业只读背景 / 任务入口定义 / 首发时持久化的会话开场副本 |
| Master / Caller、Target / Child | 发起委托的主助手、被委托助手及其独立子会话；共用生成引擎，各自持有执行与数据归属 |

文中的英文类名、状态和字段用于定位代码；解释使用上述领域含义。协议字段的拼写不能用近义词改写。
详细实现和测试入口在对应领域维护，历史验收记录不作为当前实现或当前环境可用性的证明。

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

聊天通过 `ConversationOpenRequest` 区分新建与打开已有会话，Application 校验后交付
`ConversationViewLease`。页面、命令、Turn 与文件读取分别复验各自持有的原域身份，不能互相替代授权。
导航、页面恢复和进程级 UI 宿主见 [UI 架构](ui-architecture.md)。

边界按事实所有权和操作语义选择：单 owner 的操作直接扩展既有 typed contract；跨 owner、补偿或外部 SDK 流程才增加 application 编排。SettingsStore 和 SkillManager 可通过各自 typed contract 服务配置编辑；不能为了层数增加无语义的透传 facade。页面草稿、弹窗和选择态归 UI，跨页面存活的 Job、session 与资源归 application owner。

## 事实与唯一 owner

| 事实或流程 | 责任组件与协作边界 |
| --- | --- |
| 会话持久状态 | `ConversationCommandCoordinator` 串行命令；`ConversationTransition` / `TurnTransition` 产生 mutation，`ConversationRepository` 同事务提交 |
| 会话内存状态与生命周期 | `ConversationRuntime` 管 resident snapshot、streaming 与 active session；`ConversationRuntimeRegistry` 管加载和释放 |
| Turn 输入与执行 | `ConversationTurnService` / `TurnContextFactory` 捕获输入，`TurnToolSetFactory` 装配工具；`TurnRunner` / `StepRunner` / `ToolBatchRunner` 执行，`TurnCommitter` 提交，`TurnFinalizer` / `TurnRecovery` 分别处理运行终态与恢复 |
| 子助手 | `SubAssistantRunCoordinator` 编排 run；`SubAssistantLifecycle` 管分支关联与 retention；复用会话和 Turn 写协议 |
| 标题与辅助生成 | `ConversationTitleCoordinator` 管标题 CAS；`GenerationSideEffects` 绑定原 Runtime，`ModelExecutionService` 管原域模型准入 |
| 会话与统计查询 | `ConversationQueryService` / `StatsQueryService` 组合带 scope 的只读结果；Session owner 复验访问，UI 不反向写回聚合 |
| 运行记忆 | `MemoryRepository` 唯一写入；`MemoryService` 编排 Session、配置授权与投影 |
| Artifact | `ArtifactStore` 管 metadata、引用与生命周期；`ArtifactPayloadStore` 只做磁盘 IO；`AttachmentReferenceLookup` 只提供内部附件索引 |
| 生成媒体与文件应用服务 | `GeneratedMediaStore` 管图库 row、payload 与删除恢复；`FileManagementApplicationService` / `FileManagementQueryService` 跨文件 owner 编排与查询 |
| 用户配置与配置文件引用 | `SettingsStore` 提交 `UserSettingsDocument`；`ArtifactSettingsCoordinator` 适配同一 Settings 写协议和 Artifact 交接 |
| 企业身份、Session 与 Applied | `EnterpriseSessionController` 串行写入 `EnterpriseAppliedStore`；`PlatformEnterpriseService` 执行 Discovery、Enrollment 与 Snapshot IO |
| 企业退出与本机清理 | `EnterpriseExitService` 关闭原 Session；`EnterpriseIdentityDataDisposer` / `EnterpriseDataResetService` 调用原数据 owner，保留可恢复的退出或 reset intent |
| 有效配置与模型执行 | `ConfigurationResolver` 纯派生；`ModelExecutionService` 捕获请求路由与准入，图片执行由 `ImageGenerationCoordinator` 编排 |
| Skill | `SkillManager` 管身份、文件树与可恢复发布 |
| MCP | `SettingsStore` / `McpCatalogStore` / `McpServerRuntime` / `McpOAuthCoordinator` 分别管 definition、catalog、runtime 与 OAuth；`McpRuntimeCoordinator` 跨 server 编排 |
| 本地 Workspace | `WorkspaceApplicationService` / `WorkspaceQueryService` / `WorkspaceTerminalRuntime` 分别管命令、投影与 PTY |
| 企业远程文件 | `RemoteWorkspaceService` 管原 Session 下的文件会话与操作；Core/Agent Space 保持服务端工作区与文件所有权 |
| 备份与启动恢复 | `BackupRestoreApplicationService` / `BackupArchiveService` 编排副本与请求；`PendingBackupRestore` 发布；`ApplicationRecoveryCoordinator` / `ApplicationRecoveryGate` 控制启动顺序与全局门禁 |

企业生产用量与预算由 Core 准入、结算和持久化，Android 仅提供原 Session 的只读投影。
`ChatGenerationForegroundService` 只消费活动状态维持后台运行，不拥有 Turn 事实。

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
  → Settings/userSettings（用户文档初始化成功）
  → Artifact reconcile → GeneratedMedia reconcile
  → pending enterprise data reset → 企业配置恢复
  → reference projection → FTS projection
  → Child run recovery → Master turn recovery
  → pending assistant deletion
  → pending enterprise exit（复验原域运行已终止并清理）
  → pending enterprise data reset 再次完成待处理清理
  → post-recovery maintenance → pending backup complete
  → Ready
```

Settings 步骤直接等待 `SettingsStore.initializeForRecovery()`，包括首次读取、migration、解码与投影发布；不等待无法携带原读取异常的 UI StateFlow。pending backup restore 仍在此前执行，`restoreLocal/updateLocal` 不反向等待启动门禁。

领域 owner 无法处理的恢复异常进入 Failed，记录原堆栈，失败页提供可选择复制的类型、message 与 cause；全局 durable write 门禁保持关闭，retry 重跑同一幂等顺序。企业配置校验失败由 EnterpriseSessionController 发布，个人数据恢复继续；取消向上传播并保持门禁关闭，不发布为用户故障，原 recovery job 释放后可重试。文件 command/query 同样等待门禁，不能在删除状态和孤儿 payload 尚未完成恢复处理时访问托管文件。TurnRecovery 只查询非终态执行事实；缺 owning message 或损坏 payload 是完整性错误，不以空树、默认对象或 best-effort 写入伪装 Ready。

恢复顺序归应用 coordinator，各领域恢复算法仍归原 owner。EnterpriseExitService 只完成已验证的 CLOSING token，核验 Runtime 与数据库没有原域未完成运行，且不等待尚由恢复任务持有的 ready gate；企业 manifest 本身不可验证时保持企业 Failed，不重复读取它阻断个人启动。恢复链在可注入的 IO dispatcher 执行；助手清理服务使用同一 DI singleton 的 Lazy 引用，在原清理步骤首次解析，避免进程主线程为启动门禁提前构造完整生成依赖链。失败与重试仍经过同一 gate。TurnFinalizer 不接管启动恢复，SubAssistantLifecycle 不另建生成或 Turn 终态写链。

## 持久化与演进

Room、DataStore 与文件格式按长期数据保全演进。结构变化须提供显式迁移、与新安装同构的 schema 和历史数据验证；索引由实体与迁移维护，不由业务请求临时创建。个人备份在独立数据图中升级、校验，恢复时保留最新企业数据，不能整体覆盖混合域存储。数据结构、记忆隔离、迁移及备份发布统一见 [数据持久化](data-persistence.md)。

兼容处理限于明确的持久化迁移和外部协议解析边界；内部重构应同次移除无调用旧路径，不保留双写、fallback 或转发层掩盖不一致。

企业接入、配置兼容性、空间导航和执行准入由 [配置架构](android-configuration-architecture.md)分别定义。测试分层与运行入口见 [测试策略](testing-strategy.md)，构建与发布契约见 [更新发行](update-mechanism.md)。

## 专题参考

从本页了解职责和跨领域顺序，再按问题进入下列文档。专题按稳定职责组织，同一问题的输入、执行、提交与失败边界尽量在一处读完；其他领域只保留必要摘要和引用。

| 领域 | 参考文档及维护范围 |
| --- | --- |
| 配置与企业接入 | [Android 配置](android-configuration-architecture.md)：配置 owner、企业接入、Session、平台发布与 Starter；[助手配置](assistant-configuration.md)：字段与使用偏好 |
| 会话与模型请求 | [Turn/Step 执行](turn-step-execution.md)：命令、审批、提交、取消与恢复；[请求上下文](request-context.md)：窗口、冻结、对账、接纳与回放；[提示词与工具](prompts-and-tools.md)：模型输入格式和工具契约 |
| Provider 与计量 | [线协议](protocol-reference.md)：请求编码、流式终态和历史回放；[Token 统计](token-usage-accounting.md)：规范 usage、累计及显示口径 |
| 数据与资源 | [数据持久化](data-persistence.md)：结构、域隔离、运行记忆、迁移与备份恢复；[多模态资源](multimodal-context-and-turn-durability.md)：附件投影与文件生命周期 |
| 工具运行时 | [MCP](mcp-architecture.md)：目录、连接、OAuth、调用准入；[Workspace](workspace-architecture.md)：文件、PRoot、终端；[语音](speech-architecture.md)：合成、录音、播放与清理 |
| 子助手 | [子助手](sub-assistant-architecture.md)：访问、委托执行、附件交付、分支关联、撤权与恢复 |
| 界面 | [UI 架构](ui-architecture.md)：导航、状态、布局与交互；[消息渲染](message-rendering-pipeline.md)：parts、Markdown、代码、WebView 与预览 |
| 质量与发行 | [测试策略](testing-strategy.md)：覆盖选择、证据边界与运行入口；[更新发行](update-mechanism.md)：检查、下载、构建与签名 |
