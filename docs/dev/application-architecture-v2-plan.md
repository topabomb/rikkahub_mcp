# Application Architecture V2：减法重构方案

> 状态：设计稿，尚未实施。
>
> 本文定义 V2 的目标架构、删减边界和实施顺序，不描述已经完成的事实。当前实现仍以
> [`../references/application-architecture-v1.md`](../references/application-architecture-v1.md) 及各专题参考为准；
> 只有 V2 完整落地并通过验收后，才把 `docs/references/` 更新为 V2 事实。
>
> 本方案以 0.0.18 的现行代码和数据写入协议为重构起点，但完整保留 Room schema 1→8 的历史迁移能力。
> 当前工作区已经形成的“发送后按消息身份定位”和“附件按来源做请求级投影”属于 V2 必须保留的用户行为；本文把它们
> 纳入边界和验收，但不把尚未提交的工作区实现表述为 V2 已完成。

## 1. 目的与结论

V2 不是在 V1 外再包一层 service，也不是为了追求某种架构名词而重新分包。它只做三件事：

1. 保留 V1C 已建立的正确性边界：唯一 owner、单写、提交后发布、显式状态机、取消传播、失败关闭；
2. 删除同一事实被多处解释、同一运行态被多张表表达、旧缺陷被永久修补以及仅为占位而存在的扩展层；
3. 在不建立第二套 Settings 的前提下，让客户端能够原子接收服务端下发的完整受管配置。

“纯减法”衡量的是维护者理解和修改系统所需经过的决策点，而不只是物理行数。V2 完成后必须同时出现：

- 更少的 durable 写入口、终态入口和运行态权威；
- 更短的 Conversation/Turn/Settings/MCP 主链；
- 更少的并行 map、重复 `when`、重复 staging/cleanup 和源码形状测试；
- 旧修复协议、旧命令、旧测试和旧文档描述在同一阶段物理删除；
- V2 范围内生产代码总体净减少，而且减少来自删除职责和分支，不来自压缩格式或合并成 God Object。

正确性优先于行数，但行数仍是证明“确实做了减法”的硬门禁。本文不采用“每阶段统一减少 20%”这种脱离职责的指标，
而是在 §11 冻结现有范围、逐域预算、允许新增文件和最终仓库上限：纯重构阶段必须净减少，受管配置只能使用预留预算，
最终核心范围至少减少 10%。任何行数达标都不能抵消 UI 行为、事务、迁移或恢复门禁失败。

## 2. V1 提供的教训，而不是 V2 的模板

已删除的 `architecture-v1-refactor-plan.md` 记录了从早期 V1 到 V1C 的多轮调整。V2 不复制它按工作流、函数和
测试逐项展开的粒度，只提取那些会影响本次设计的因果关系。

| V1 阶段/现象 | 暴露的问题 | V2 采用的结论 |
| --- | --- | --- |
| 初期以固定文件数、兼容 facade 和渐进迁移落地 Runtime | 新旧协议并存，编译兼容掩盖双状态源和旁路 | 一个重构切片必须同时迁移消费者并删除旧入口，不保留 V1/V2 双 API |
| 初期把“内存先变、随后持久化”视为可接受实现 | 失败时 UI 发布了数据库中不存在的 durable 事实 | durable commit 成功后才发布；只有 streaming projection 可先显示且不落库 |
| 以净行数和源码零命中作为主要完成指标 | 产生大协调器、职责搬家和验证符号而不验证语义的测试 | 先定 owner、事务和失败语义，再收窄能力，最后观察规模 |
| 把同步资源消费机械改成协程调用 | 临时文件仍由旧回调清理，异步消费者失去所有权 | 所有异步资源都要有显式 lease/owner 交接，不能靠作用域和时序猜测 |
| 多处分别枚举 Settings 持有的 Artifact 引用 | GC、删除检查、detach 与业务清理的引用面不一致 | 引用事实只定义一次，所有生命周期操作复用同一策略 |
| Compose 派生状态直接读取 `StateFlow.value` | 运行时数据变化但 UI 依赖没有被跟踪 | Flow 必须在 UI 边界转成 Compose State；静态形状正确不能代替交互测试 |
| V1C 为历史错误执行记录增加孤儿修复命令 | 修复了旧版本产生的错误数据，但也成为永久命令分支 | 所有客户端已运行 0.0.18 后，删除这种“错误状态修复”；保留合法历史数据迁移 |
| V1C 为 Settings 图片增加 Artifact adoption | 修复旧写路径制造的“有引用、无 metadata”状态 | 当前写协议不能再制造该状态后，删除 adoption；不保留常态自愈旁路 |

V1C 最终确认的裁决顺序继续有效：

1. 先确定每项 durable 事实的唯一 owner、事务边界和失败语义；
2. 再决定调用方能获得哪些能力，并用类型和可见性禁止旁路；
3. 迁移全部消费者，在同一交付中物理删除旧符号和兼容测试；
4. 用故障注入、进程恢复、迁移、真机和性能证据验收；行数只用于确认减法结果。

## 3. 范围与不可破坏的不变量

### 3.1 本次重构范围

V2 聚焦下列高成本主链：

- Conversation command、resident Runtime、Turn、Generation、审批、恢复和子助手运行；
- Settings 本地持久化、有效配置、服务端完整受管快照和依赖它的 MCP 运行态；
- Artifact、Skill、Backup 中重复的资源提交与历史错误修复；
- Workspace application/query/terminal/PRoot 中重复装配与过细读端；
- UI/Application 边界以及只验证内部类名和源码文本的脆弱架构测试。

不重做 Provider 的模型调用、流式解析、reasoning replay 等既有产品能力，不改消息渲染、产品 UI、备份产品语义或
Workspace 功能。附件是否能进入某个 Provider 线协议容器属于本次边界正确性：只收敛能力判定、投影顺序以及各
Provider serializer 的媒体分支；serializer 的非媒体结构、流式解析和既有 replay 语义不重写。

### 3.2 V2 必须保留的事实

以下约束不能为了删代码而放宽：

- 每类 durable 事实只有一个写 owner；UI/ViewModel 不直连 DAO、Repository、Runtime Registry 或 payload 层；
- 新聊天仍是非持久化 Draft；首条用户消息与会话创建同事务提交后原位晋升 Ready；
- resident 与 non-resident Conversation command 使用同一 transition、事务和错误语义；
- durable 状态只在事务成功后发布；streaming projection 是唯一可提前发布的会话态；
- 每个 turn 只有一个不可变 `TurnHandle` 作为 durable CAS 授权令牌；Runtime 同时最多一个私有 `ActiveTurnRuntime`；
  `START` 与 `CONTINUE_APPROVAL` 是不同协议，stale worker 只能用原 handle 尝试幂等 terminal CAS，无 START、checkpoint 或
  approval 提交权限；
- Turn/Tool/Artifact 的终态不可逆，取消必须传播；`NonCancellable` 只用于已取得所有权的终态提交或补偿；
- Artifact metadata 与引用由 `ArtifactStore` 唯一拥有，payload 层只做磁盘 IO；
- 启动恢复 fail-closed；损坏数据不能伪装 Ready，best-effort 日志不能代表恢复成功；
- Provider、Android API、合法旧消息/备份格式的兼容不属于“错误旧数据修复”，不得借 V2 删除；
- Room schema 1→8 的 migration、schema JSON 和数据保全测试完整保留。
- `SendMessageReceipt` 只表示请求已被 Runtime 接受，不冒充 durable success；同一 receipt 必须能由只读 presentation
  判断“目标消息已提交”或“本次请求已失败/取消/被替代”，UI 不得无限等待，也不得持有 Runtime Job；
- 附件投影只存在于单次 Provider 请求，不写回 Conversation；USER、ASSISTANT、`Tool.output` 的领域来源不可改变；
- `input=native` 必须同时满足模型能力、具体 Provider endpoint 和实际 wire container 能力，且最终请求确实携带媒体；
  不支持的来源只能在原来源位置降级为 `reference_only`/`unavailable`，不得伪装成 USER，也不得静默丢图后仍标记 native；
- `CAPABILITY_HINT` 及任何脱离附件来源、统一追加到末条消息的全局提示不得恢复。

### 3.3 明确不引入的结构

- 不引入 Event Sourcing、Command Bus、Event Bus、通用 Saga、全局 `Result` 或服务定位器；
- 不为了“分层”给每个操作创建 `UseCase`、`Coordinator`、`Manager` 或接口/实现二件套；
- 不把整份 Settings 迁入 Room，也不创建通用 `type/id/json` 配置表；
- 不把受管配置、市场包、灾备 ZIP 和运行数据同步合成一个协议；
- 不将几个不相关的大类机械合并成新的 God Object；
- 不保留 deprecated 转发、typealias、fallback、白名单或“稍后删除”的过渡路径。

## 4. V2 总体架构

```text
Compose / ViewModel
    │ command ports + immutable UI projections
    ▼
Application boundary
    ├─ ConversationApplicationService / ConversationQueryService
    ├─ ProviderSettingsApplicationService
    ├─ WorkspaceApplicationService / WorkspaceQueryService
    └─ Backup entry
    │ cross-owner orchestration only
    ▼
Domain owners
    ├─ ConversationCommandCoordinator ─ ConversationRuntimeRegistry
    │                                  └─ ConversationRuntime
    ├─ TurnEngine ─ GenerationLoop
    ├─ SettingsStore ─ effective configuration
    ├─ ArtifactStore / SkillManager / BackupArchiveService
    ├─ McpManager
    └─ WorkspaceTerminalRuntime
    │ narrow repository, payload and platform ports
    ▼
Room / DataStore / managed snapshot / filesystem / Provider / PRoot / Android
```

`GenerationLoop` 是当前 `GenerationHandler` 收敛后的职责名称：只表示 Provider 流式响应与工具 step 循环，
不表示新增一层。实施时应直接重命名并迁移，不能同时保留 Handler 与 Loop。

### 4.1 依赖方向

1. UI 只依赖 application command 和 query projection；
2. application 只做跨 owner、外部 SDK、用户确认或生命周期编排；单 owner 转发直接删除；
3. domain owner 可以依赖窄 repository/payload port，但基础设施不能反向定位 application service；
4. query 可以组合 durable 事实与进程内投影，但不能获得写能力；
5. 同一模块内不为了形式增加接口；只有跨模块、平台实现或可替换外部传输才使用 port/adapter；
6. 进程内状态不得通过多个 map、Flow 和 nullable Job 共同表达同一个生命周期。
7. 请求级投影只能消费 durable snapshot 与不可变 endpoint capability；Provider serializer 只能编码投影结果，不能再次
   改写附件语义或另算一份能力结论。

### 4.2 唯一 owner 与保留理由

| 事实/能力 | V2 owner | 保留理由 |
| --- | --- | --- |
| Conversation durable command | `ConversationCommandCoordinator` | 统一 resident/non-resident、锁、事务和发布顺序 |
| resident snapshot 与 active turn projection | `ConversationRuntime` | 进程内聚合投影需要明确生命周期，但不再解释持久化命令 |
| Runtime 装载、Draft 与逐出 | `ConversationRuntimeRegistry` | conversationId 级 resident 生命周期与 command 语义不同 |
| Turn checkpoint/terminal CAS | `TurnEngine` | Master 与 Child generation 必须共用一套 durability 骨架 |
| 用户级发送、重生成与 supersede | `MasterTurnCoordinator` | 涉及 UI 意图、旧 turn 屏障、上下文准备和完成后副作用 |
| Conversation 标题生成与手动标题 CAS | `ConversationTitleCoordinator` | token + expected-title 与手动写串行，不能混入 Conversation Runtime |
| Master 请求身份分配与 send receipt | `MasterTurnCoordinator` | send 一次分配 `turnId/userMessageId`；regenerate 一次分配 `turnId`，均不保存第二份存活状态 |
| Child 请求身份分配 | `DelegationCoordinator` | 在 LAZY worker 启动前分配 child turnId，不持有 Conversation 状态 |
| 子助手 run lease | `SubAssistantRunGate` | 只拥有跨 Master/Child 的 run 互斥与释放，不拥有 active request 或 durable turn |
| Master/Child active request 生命周期与 projection | 各自的 `ConversationRuntime` | 唯一私有 `ActiveTurnRuntime` 表达该 Conversation 的进程内存活性，不冒充 durable message |
| 请求消息投影顺序 | `TurnPipelineFactory` | Master/Target 各只有一份有序 transformer 清单，附件投影固定在链末 |
| Provider 媒体容器能力/线编码 | `Provider` adapter | adapter 知道 endpoint/profile 和 wire schema；Transformer 只消费其不可变能力，不猜 host 方言 |
| 子助手编排、lineage 与 preview/interaction 回写 | `DelegationCoordinator` | 跨 Master/Child 与工具交互，但 run 互斥委托 Gate、Conversation 状态委托 Child Runtime |
| 启动恢复 | `ApplicationRecoveryCoordinator` + `TurnRecovery` | 前者是全应用门禁，后者只解释当前 schema 的中断状态 |
| 本地与受管配置、有效读模型 | `SettingsStore` | 防止出现 LocalStore、PolicyStore、EffectiveStore 三个公开 owner |
| Artifact metadata/引用/生命周期 | `ArtifactStore` | 删除和 GC 正确性依赖唯一引用面与状态机 |
| MCP connection lifecycle | `McpManager` | 每个 server 是进程资源，不是 Settings 的一部分 |
| Workspace durable command / terminal process | `WorkspaceApplicationService` / `WorkspaceTerminalRuntime` | 持久化 Workspace 与 PTY 生命周期是不同事实 |

Application service 的存在必须能回答“它跨越了哪两个 owner 或哪项平台边界”。无法回答的只转发 API 在 V2 删除。

### 4.3 采用的模式

V2 只使用项目已经需要的常用模式，不建立通用框架：

| 模式 | 落点 | 限制 |
| --- | --- | --- |
| Aggregate + Unit of Work | Conversation、Settings、Artifact 的唯一 owner 与一次提交 | 不跨聚合制造“万能事务” |
| State | Turn、Tool、Artifact、MCP connection 的显式状态与合法迁移 | 不用散落布尔值、nullable Job 或 metadata 猜状态 |
| Policy | Settings 有效合并、写锁、Artifact 引用面 | 只有一份纯规则；没有单实现占位接口 |
| Repository | Room/DataStore/文件的窄持久化边界 | Repository 不发布 UI 状态，也不包含业务回退 |
| Adapter | Provider、服务端 transport、PRoot、Android 平台 | 外部差异停在边界，不渗入领域状态机 |
| Projection | Conversation/Workspace/managed lock 的只读 UI 模型 | 只读 join 不获得写能力，不形成第二事实源 |

## 5. Conversation：一个 transition、一个提交点

### 5.1 当前重复

同一 `ConversationCommand` 目前在下列位置重复判断：

- `ConversationReducer` 计算新 snapshot；
- `ConversationMutationBuilder` 再比较 old/new 生成持久化 mutation；
- `ConversationCommandCoordinator` 再判断 execution fact、activity 和 resident/non-resident 行为；
- `ConversationRuntime` 再判断 owner、命令类别和串行写入。

这种结构不是四层各司其职，而是四处共同解释一条命令。新增命令需要同步维护多份 `when`，也正是历史兼容
分支容易长期残留的原因。

### 5.2 V2 提交模型

用一个内部纯 transition owner 取代 `ConversationReducer + ConversationMutationBuilder` 的双解释，同时保留 non-resident
header command 不加载整棵消息树的性能边界：

```kotlin
internal sealed interface ConversationChange {
    val snapshot: ConversationSnapshot

    data class DraftOnly(
        override val snapshot: ConversationSnapshot,
    ) : ConversationChange

    data class Durable(
        override val snapshot: ConversationSnapshot,
        val write: ConversationWrite,
    ) : ConversationChange
}

internal sealed interface ConversationWrite {
    data class MaterializeDraft(val conversation: Conversation) : ConversationWrite
    data class Mutate(
        val mutation: ConversationMutation,
        val executionFacts: ExecutionFacts? = null,
    ) : ConversationWrite
}

internal data class ConversationHeaderChange(
    val committedHeader: ConversationHeader,
    val write: ConversationWrite.Mutate,
)

internal object ConversationTransition {
    fun plan(
        current: ConversationSnapshot,
        command: ConversationCommand,
        nowMillis: Long,
    ): ConversationChange

    fun planHeader(
        current: ConversationHeader,
        command: HeaderConversationCommand,
        nowMillis: Long,
    ): ConversationHeaderChange

    private fun applyHeader(
        current: ConversationHeader,
        command: HeaderConversationCommand,
        nowMillis: Long,
    ): ConversationHeader
}
```

`DraftOnly` 不是第三套持久化模型，而是把“尚无 durable aggregate”编码进 transition 结果。实施时：

- 将 `ConversationReducer.kt` 改为唯一 transition 实现；
- 删除 `ConversationMutationBuilder` 及 old/new 全树推导；
- mutation 必须由命令语义直接产生，只写 command 实际改变的 header/node/fact；
- Draft 只接受 `UpdateHeader`、`MoveToAssistant` 和首条 `AppendUserMessage`：前两者返回 `DraftOnly` 并只发布 resident
  snapshot，首条消息返回 `Durable(MaterializeDraft)`，把最终 Draft header、首消息和 Conversation 一次插入；
- Ready full command 与 non-resident header command 返回 `Durable(Mutate)`；只有 `Durable.write` 能进入
  `ConversationRepository.commit(write)`，Coordinator 不再传 `persistCommand/materializeDraft` callback；
- `UpdateHeader`、`UpdateTitleIfCurrent`、`MoveToAssistant`、`TogglePinned` 实现同文件的窄
  `HeaderConversationCommand`；`plan` 与 `planHeader` 都复用 private `applyHeader`，因此 Draft/Ready、resident/non-resident
  不复制 header 规则；
- non-resident header command 只读 `ConversationHeader` 并调用 `planHeader`；其他 non-resident command 才装载完整 Runtime，
  不以“统一”为由退化 Drawer pin/title/移动助手的长会话性能；
- command-specific 私有函数可保留在同一领域文件，不能为每个 command 建 handler 类；
- transition 不访问 Room、Runtime、时钟、Koin 或文件系统；随机 id 由 command 携带，`nowMillis` 由 Coordinator 一次取值后
  显式传入，测试不依赖真实时钟；
- illegal/stale command 在 transition 前或 transition 内显式失败，不回退到整聚合覆盖。

用户发送在 durable command 之前还有一段请求接收链。V2 固定为：

```text
ChatPage
  → UI scope 调用 suspend ChatVM.handleMessageSend
  → suspend MasterTurnCoordinator.sendMessage
  → 分配 turnId + userMessageId，创建 CoroutineStart.LAZY worker
  → ConversationRuntimeRegistry.installAndStartActiveRequest(...) 在 operation lock 内取消/隔离旧 owner、安装 PREPARING_START 并 start worker
  → 返回 SendMessageReceipt(conversationId, turnId, userMessageId)
  → active worker 等待旧 turn durable 收口、预处理输入
  → 执行 AppendUserMessage durable command
```

`SendMessageReceipt` 保留在 `MasterTurnCoordinator.kt`，只增加 `turnId`，不再建立 `SendRequest` 文件、callback 或事件总线。
receipt 返回时目标消息可以尚未提交，因此不能清空错误、宣布发送成功或把预分配 id 当成数据库事实。`ConversationRuntime`
在安装 active runtime 后才能返回 receipt。每个 worker 的 `finally` 必须按 identity 完成且只完成一种转换：
`TurnOutcome.AwaitingApproval` 保留同一 active runtime 并进入 `AWAITING_APPROVAL(handle)`；START 前失败、`answer=false` 完成或 durable
terminal 完成才释放整个 active runtime。释放必须让 `ConversationPresentation.activeRequestTurnId` 从该 receipt 的 `turnId`
变为其他值或 `null`，UI 不依赖可能被合并的瞬时 terminal event。失败详情继续由 `ChatErrorStore`/durable turn 终态提供。
`answer=false` 也走同一协议：目标消息 commit 并 publish 后才释放 active runtime。

LAZY worker 用于消除“协程已经执行/失败，但 Runtime 尚未取得所有权”的窗口，不是第二条调度路径。`install` 在 Runtime
内部原子替换 active runtime：旧普通 worker 先收到 supersede reason，停止 streaming/checkpoint；其 immutable handle 只交给
`TurnFinalization` 做一次 terminal CAS。新 active runtime 持有旧 worker/turn 的 durable finalization barrier；只有它能把
PREPARING 推进到 RUNNING。Registry 在 operation lock 内执行 `ensureActive → install → start`，锁获取前取消则什么都不安装，
安装后的取消由新 active runtime 收口；安装失败取消尚未 start 的 worker 并返回明确冲突。

`installAndStartActiveRequest` 是 Registry 中的 internal 生命周期操作，不是新的 application service：Master send/regenerate 和
Delegation 都调用它。新 worker 在锁外等待前任 worker 退出并经 `TurnFinalization` 完成 durable terminal，绝不能持有 operation
lock 等待，否则前任无法取得同一锁收口。`StartTurn`、`CommitCheckpoint` 与 `UpdateToolApproval` 在 Coordinator 已持有
operation lock 时，除 durable handle 校验外还必须确认 Runtime 当前 active identity 等于 command.turnId；
`UpdateToolApproval` 因而增加原 `TurnHandle`，不再仅凭 message/ordinal 修改 active turn。`FinalizeTurn` 是唯一允许旧
identity 以原 handle 提交的命令。这样新请求安装与旧 worker 非终态写之间没有 check-then-act 窗口。

唯一 Conversation command 调用链固定为；其中只有 Durable 分支落库：

```text
Application command
  → ConversationCommandCoordinator.execute(conversationId, command)
  → ConversationOperationLocks.withLock(conversationId)
  → resident/Draft 取得 command-scope Runtime lease 后读取；non-resident header command 只读 header，其余装载完整 aggregate
  → ConversationTransition.plan(...) / planHeader(...)
  → DraftOnly：ConversationRuntime.publishDraft(change.snapshot)，不访问 Repository
  → Durable：ensureActive 后进入 NonCancellable 收口
      → ConversationRepository.commit(change.write)
      → ConversationRuntime.publishCommitted(change.snapshot)（若 resident）
  → 返回 command result（只有 Durable 分支表示数据库 commit）
```

`ConversationOperationLocks` 是 durable command 和 active request owner 交接的唯一串行门；前者只由 Coordinator 执行，
后者只由 Registry 的 `installAndStartActiveRequest` 执行。`ConversationRuntime` 删除自己的 command mutex、
命令分类和持久化 owner 校验，只保留：

- Draft/Ready resident projection；
- streaming delta；
- 一个 private active `ActiveTurnRuntime`；
- resident lease 和 idle eviction 所需状态。

`Runtime` 的发布方法保持 `internal`，只有 Coordinator 可调用。`publishDraft` 只接受仍为同一 lease/identity 的 Draft；
`publishCommitted` 只在 repository commit 成功后执行。Repository 不发布 Flow，
Runtime 不持有 Repository。`ConversationWrite` 与 `ConversationChange` 都定义在 `ConversationTransition.kt`，不是新抽象层；
Repository 的 `commit` 在一个 Room transaction 内区分 materialize/mutate。Draft 首条消息 commit 后才原位晋升 Ready，
失败则保留原 Draft，不能先 promote 再补偿。

command-scope lease 从读取 current state 持有到 commit 后 publish/promote 完成，防止 resident 在事务中途被 idle eviction；lease
不是第二把写锁，不参与命令排序。锁顺序固定为 operation lock → Runtime lease → Room transaction，任何 Runtime/Repository
回调都不得反向获取 operation lock。operation lock 获取、状态读取和纯 plan 保持可取消；进入 durable commit 前显式
`ensureActive()`，随后 `commit → publish/promote` 在同一个 `NonCancellable` 收口中完成。`publishCommitted` 是不挂起、无 IO 的
内存替换，不另取锁；因此取消不能制造“数据库已提交、resident 仍旧”的窗口，也不能用补偿事务回滚已提交命令。

### 5.3 运行态收敛

当前 `generationJob`、`activeTurnId`、`cancelReasons` 和 approval mutex 分散表达一个活跃请求。V2 将其归入
`ConversationRuntime` 内一个 private `ActiveTurnRuntime` 状态机：稳定 `turnId`、可选 `appendTargetMessageId`、取消原因、审批
gate、request phase 和 processing status 随 active runtime 一起创建和释放；worker 只存在于需要执行的具体 state 中。
状态集合固定为 `PREPARING_START(waitingForPrevious, worker)`、`RUNNING(handle, worker)`、
`AWAITING_APPROVAL(handle)`、`PREPARING_CONTINUATION(handle, worker)`、`STOPPING_BEFORE_START(worker)`、
`STOPPING(handle, worker)`。`appendTargetMessageId` 只在 send/append 请求中存在，regenerate 为 `null`，不能据此猜 durable
message。

合法迁移固定为：START 请求从 `PREPARING_START` 在 `StartTurn` commit 后进入 `RUNNING`；Provider 请求审批后，worker
`finally` 清除自身但保留 owner 并进入 `AWAITING_APPROVAL`；最后一个 pending decision commit 后，Runtime 先以 LAZY worker
进入 `PREPARING_CONTINUATION`，再启动并复用同一 handle 进入 `RUNNING`，绝不再次 START。取消分别进入两个 stopping
状态，完成 terminal CAS 后释放；`answer=false` 从 `PREPARING_START` 在 append publish 后直接释放。终态不作为可滞留的第七
状态，所有替换、worker 完成和释放都按 `turnId + worker identity` CAS；这些事实不再各建 Flow 或 map。实现留在
`ConversationRuntime.kt`，不新增 runtime 文件或公开 DI owner。

现有 `TurnHandle(conversationId, epoch, turnId, assistantMessageId)` 原样保留为不可变 durable 写授权 token：只有 START 成功后
active runtime 才获得 handle，`TurnEngine`、`CommitCheckpoint`、`UpdateToolApproval`、`FinalizeTurn` 只接收这个小值对象，
绝不接触 Job、gate 或 mutable Flow。这样 `answer=false` 和 append 前失败无需伪造 durable handle；stale checkpoint/approval
由 current active identity 拒绝，
stale terminal 只可能以原 epoch/turnId 做幂等 CAS。
Runtime 不再维护 turnId→reason map，也不向 Query/UI 暴露 Job。`ttsQueueSessionId` 明确保留在 Runtime：顺序播放队列可能
在生成终态后继续播放，并可被审批继续复用，它不是 active-turn 状态，强行并入 `ActiveTurnRuntime` 会破坏自动播放。

processing text 的写能力也必须收窄：`ActiveTurnRuntime` 为当前 worker 生成捕获 `turnId + worker identity` 的
`reportProcessingText(String?)` 函数，放入 `GenerationRequest` 并传给 `TransformerContext`；Generation/Transformer 不再接收
`MutableStateFlow`，Runtime 仅在 identity 仍匹配时更新私有 active state。旧 worker 的 late report 被忽略，释放 active runtime
自动清空文字；Query/UI 只能从 `ConversationPresentation.processingText` 读取。

合并后的 `ConversationPresentation` 是 UI 唯一 turn 运行态读模型。V2 对外字段固定为 `activeRequestTurnId: Uuid?`、
`phase: ConversationTurnPhase`、`processingText: String?` 和
`toolCallPhases: Map<ToolCallLocator, ToolCallPhase>`；`ConversationTurnPhase` 固定为 `IDLE/PREPARING/GENERATING/`
`AWAITING_APPROVAL/STOPPING`。它由 `ActiveTurnRuntime`、durable `activeTurn` 和 streaming/tool projection 一次组合；不暴露
Job，不保存另一份可写状态。“Job 是否为空”不再成为第二份生成事实。工具阶段的映射只保留一个纯函数，Runtime 与 UI
共用；删除 UI 中独立的 `resolveToolCallPhase` 及重复的 approval/output 推断。

映射没有自由度：无 active runtime 且无合法 durable active turn 为 `IDLE`；两个 preparing state 都为 `PREPARING`；
`RUNNING` 为 `GENERATING`；`AWAITING_APPROVAL` 同名映射；两个 stopping state 都为 `STOPPING`。仅在进程恢复后尚未建立
active runtime 时，合法 durable RUNNING/AWAITING 状态可由恢复 projection 暂时显示 GENERATING/AWAITING；恢复门禁完成后必须
重建 owner 或提交 interrupted terminal，不能长期留下“只有数据库 activeTurn”的第二运行路径。

标题不并入该类型。`ConversationTitleCoordinator` 继续唯一拥有 title generation token/CAS；
`ConversationQueryService.conversationActivities()` 只在 query 边界把只读 title phase 与 turn presentation 合并成 Drawer 活动集合，
不把标题状态写回 Runtime 或 active runtime。

组合必须按 turn identity join，不能把“当前新 active runtime”与“尚在 terminal finalization 的旧 durable activeTurn”拼成
一个状态。supersede barrier 期间 presentation 显示新 active runtime 的 PREPARING/可停止状态；旧 turn 只允许完成 durable terminal，不再提供
按钮或 processing text。新 START 提交后，active runtime 中的 handle 与 durable `activeTurn.turnId/epoch` 相等，才合入 streaming、
approval 和 tool projection。

发送后滚动只消费 receipt、durable snapshot、`ConversationPresentation` 和 Compose layout/IME 状态：目标
`userMessageId` 已出现在同一分支、实际 item count 等于期望值且 IME 已关闭时才滚到底部；若分支/会话改变、新 receipt
替代旧 receipt，或 `activeRequestTurnId` 已不再等于 receipt.turnId 且目标消息仍未出现，则立即终止等待。字段名特意带
`Request`：PREPARING 阶段还没有 durable active turn，不能把预分配 id 误称为已提交事实。不得用固定 delay、列表
长度猜测 append 完成，也不得因发送失败留下存活到下次发送或页面销毁的等待协程。

必须覆盖三个窗口：START 已提交但 Provider 尚未发 chunk、终态已提交但协程尚未返回、cancel 已请求但终态尚未提交。
这些窗口分别由 durable active turn、终态和 active-runtime cancellation 表示，不用 nullable Job 猜测；TTS session 在这些状态
变化前后保持现有复用与清理语义。

## 6. Turn、Generation 与子助手：共享执行骨架，不混合领域策略

### 6.1 职责划分

V2 不把 Master、Child generation、Provider 和终态全部塞进一个类，而是固定四个不同职责：

| 组件 | 只负责 | 不再负责 |
| --- | --- | --- |
| `MasterTurnCoordinator` | 用户发送/重生成/审批意图、supersede 屏障、上下文与成功后副作用 | Provider step 循环、checkpoint 细节、重复终态映射 |
| `DelegationCoordinator` | Master/Child 关联、ask_user、通过 Gate 使用 run lease，以及 Child preview/phase 回写 | Conversation active state、Gate 内部 lease、Master 通知/声音/完成副作用 |
| `TurnEngine` | START/CONTINUE、stream projection、checkpoint、terminal CAS 和唯一 `TurnOutcome` | 业务 UI、副作用、Provider SDK 兼容 |
| `GenerationLoop` | Provider 调用、消息 transformer、工具执行、资源 lease、step limit | Conversation Repository、Runtime Job、Turn terminal commit |

Master 与 Child generation 的调用链固定为：

```text
TurnEngine.start(...) / continueActive(...) → StartedTurn（engine 内持 immutable TurnHandle）
  → 构造 GenerationRequest(onCheckpoint = engine::onCheckpoint)
  → source = GenerationLoop.run(request)             // cold Flow<GenerationChunk>
  → engine.bind(source)                               // streaming/checkpoint/terminal durability
  → MasterTurnCoordinator 或 DelegationCoordinator collect TurnEvent
```

`GenerationRequest` 只把当前 `generateText` 的请求级参数分组，定义在 `GenerationLoop.kt` 内，不新增 handler/observer 接口。
`TurnEngine.start/continueActive` 是唯一能调用 Runtime `markRunning(handle)` 的位置：START commit 后把同 identity 的 state 从
`PREPARING_START` 更新为 `RUNNING(handle)`；审批继续先校验 durable owner，再把 `PREPARING_CONTINUATION` 更新为同 handle 的
`RUNNING`。`StartedTurn` 不向 application 暴露 Job/gate，只返回 engine、assistant slot 与 resumable message。Transformer 顺序
继续由唯一 `TurnPipelineFactory` 定义。

Master send/regenerate 与 Child run 都必须先创建 LAZY worker、再在对应 Conversation Runtime 安装 active runtime、最后启动；
Child 的 `turnId` 因而从运行中 worker 内联调用 `TurnEngine.start` 时生成，前移到 `DelegationCoordinator` 在安装前分配并传入，删除
`commandCoordinator.load(childId).setJob(runJob)` 旁路。`SubAssistantRunGate` 仍只控制 target run lease；它不能代替 Child
Runtime 的 request/turn projection，也不能持有 `TurnHandle`。

Master 与 Delegation 的 `TurnEvent` 消费明确保留为两个 application consumer：Master 处理通知、声音和完成副作用；
Delegation 处理 Child preview 与 phase metadata。二者不是重复 owner，强行合并只能变成 callback soup，并可能丢失 UI
反馈。真正删除的是两侧都不消费的 `TurnEvent.Checkpoint`、仅为它存在的 `GenerationChunk.Checkpoint`，以及两侧重复的
异常→`TurnOutcome` 和工具终态纯变换；保留 `Streaming`、`Phase`、`Finished` 三种事件。

### 6.2 终态与恢复边界

`TurnFinalization` 明确保留：它处理用户 stop、supersede、Child 中断和未完成工具收口，跨越活跃 engine 与多个
Conversation，不等价于 `TurnEngine` 的单 turn 提交。它的 durable 写入继续只经 Coordinator/Engine；其中与
`ConversationTransition` 重复的 cancel/interrupted tool 纯变换合并为 `ConversationTransition.kt` 内的一份领域函数。
不把 `TurnFinalization` 并入 Engine，避免制造新的 God Object。

`TurnRecovery` 只恢复当前 schema 可以合法留下的状态：进程死亡时的 RUNNING turn、STARTED tool、未完成 Child。
它不再修复当前写协议不可能产生的孤儿 execution，也不把无法解释的损坏数据改写为“已恢复”。

`SubAssistantRunLeaseRegistry` 的唯一消费者是 `SubAssistantRunGate`，因此 lease map 和取消逻辑直接成为 Gate 的
private 实现，删除 Registry 文件及只验证该内部类的测试。Gate 的行为测试继续覆盖同 target 互斥、取消、释放和
stale handle。

### 6.3 附件请求投影与 Provider 线协议

附件投影不是 durable command，也不是 Provider serializer 的任意 fallback。固定调用链为：

```text
Conversation durable snapshot
  → replaySafeProjection()
  → limitContext(...)
  → TurnPipelineFactory 的有序 input transformers
  → AttachmentProjectionTransformer（Master/Target 均为最后一个 input transformer）
  → Provider adapter 按同一 RequestMediaCapabilities 编码 wire request
```

Master 在附件投影前保留 `ToolArtifactReplayTransformer`，Target 不装配它；两者都不得在附件投影之后再运行会新增、移动或
删除 media part 的 transformer。`AttachmentProjectionTransformer` 继续留在现有文件，不并入 `GenerationLoop`、Coordinator
或各 Provider，也不写 Conversation、Artifact metadata 或识别缓存。

能力结论不再由 `ctx.model.inputModalities.contains(IMAGE)` 单独决定。V2 在现有 `ai/.../Provider.kt` 增加小型值对象
`RequestMediaCapabilities(userImages, assistantImages, toolOutputImages)` 和 `RequestImageSupport` 三态
`NONE/STRUCTURED/OPAQUE_REPLAY_ONLY`，以及 Provider 方法 `requestMediaCapabilities(providerSetting, model)`。
`OPAQUE_REPLAY_ONLY` 只允许 source profile 匹配且带完整原始协议 metadata 的历史输出；普通 `UIMessagePart.Image` 不能借此
重建成 native。
`GenerationLoop` 对每次请求只解析一次，把同一个不可变值同时放入 `TransformerContext` 和 `TextGenerationParams`；
Transformer 决定 `native/reference_only/unavailable`，serializer 只能按该结论编码并 fail-fast 检查，不能重算或静默降级。
这些类型写入现有 `Provider.kt`、`Transformer.kt`、`AttachmentProjectionTransformer.kt`，不新增 capability/registry 文件。
能力表是各 adapter 随代码发布的协议事实，不是 Settings、服务端受管配置或用户可切换选项；接口没有 permissive default，
未登记的 endpoint/profile/source container 一律为 `NONE`。

能力矩阵固定为“默认拒绝、profile 明确开放”：

| Provider wire container | native 条件 | 不满足时 |
| --- | --- | --- |
| 普通 USER 图片 | resolved model 声明 IMAGE，且当前 endpoint 的用户输入 schema 支持图片 | 原 USER 内 `reference_only`；无稳定 ref 时 `unavailable` |
| OpenAI 官方 Chat Completions ASSISTANT / TOOL_OUTPUT | 永不 native；官方 assistant/tool 输入容器不承载本项目图片形状 | 分别留在 ASSISTANT / `Tool.output`，只发引用事实 |
| OpenAI compatible Chat ASSISTANT / TOOL_OUTPUT | 只有已命名 endpoint profile 明确支持对应容器；未知 compatible profile 不猜扩展 | 原来源内引用事实 |
| OpenAI Responses ASSISTANT / TOOL_OUTPUT | ASSISTANT 为匹配 source profile 的 raw `response.output`，或 endpoint profile 明确支持 structured assistant image；TOOL_OUTPUT 仅在 profile 明确支持 multimodal function output 时 native；未知 host 映射到 `OPENAI_COMPATIBLE` 只证明基本 wire shape，不自动证明这两项媒体扩展 | 原来源内引用事实；不得把 raw output 改造成 Chat message |
| Claude ASSISTANT / TOOL_OUTPUT | 只有 adapter 当前使用的正式 content/tool-result schema 明确支持对应图片块 | 原来源内引用事实；不得搬到下一条 USER |
| Gemini ASSISTANT / TOOL_OUTPUT | 只有 model/profile 与 adapter 都支持所需 model-content 或 multimodal function-response 形状及协议 metadata | 原来源内引用事实；不得伪造 model output/signature |

Phase A 的初始证据以 2026-08-26 的正式请求 schema 为锚点：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、
[OpenAI Responses](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)、
[Claude Messages/tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls) 与
[Gemini generateContent](https://ai.google.dev/api/generate-content)。后续 schema 变化只能通过具名 profile + serializer fixture 的
独立协议变更开放，不能让远端模型名、host 或成功过一次的请求动态扩大能力。

每个图片在其来源位置生成且只生成一个 `AttachmentProjectionTextMetadata` 标记：能实际编码媒体为 `input=native`；不能编码
但有稳定 `attachment:<uuid>` 为 `input=reference_only`；两者都没有为 `ref=unavailable input=unavailable`。native 图片无稳定
ref 时保留图片并标记 `ref=unavailable input=native`，不能因为没有 ref 隐藏实际输入能力。USER 图片仍在 USER，助手图片仍在
ASSISTANT，工具图片仍在原 `Tool.output`；Provider 为满足 wire schema 把 tool result 包在 user/function-response 外层，不改变
其领域来源。

删除全局 `CAPABILITY_HINT`、末条消息统一追加和 serializer 中“丢图后补空文本/omitted 文案”的语义 fallback。编码失败是
明确的 request failure；能力不支持则必须早在 Transformer 中生成引用事实。Responses raw replay 保留原 item group/call 顺序，
请求级附件事实只在该 assistant replay 之后追加一次，不改写 raw output，也不复制原可见文本。

## 7. 服务端完整配置：一个配置 Aggregate

本节承接 [`persistent-records-and-sync.md`](persistent-records-and-sync.md) 已完成的准备：本地 Settings 与受管覆盖分离、
稳定 id、写入来源和提交后发布。V2 保留这些契约，但不照搬其中“再公开一个 PolicyStore”的阶段性形态；受管来源作为
`SettingsStore` 的内部持久化组成，外部始终只有一个配置 owner 和一个有效读模型。

### 7.1 “全部配置”的边界

服务端完整快照可以管理所有影响应用 AI 行为的配置图：

- Provider、Model、默认模型与模型级参数；
- Assistant、Tag、Mode Injection、Quick Message、受管 Prompt 与默认选择；
- MCP 的非机密连接定义、工具开关和策略；
- TTS、ASR、Search 的公开定义与默认选择；
- 受管 Skill 的签名引用、版本、公开资源和允许关系；
- 字段/记录锁、租户策略、能力开关和配置 generation。

“全部”不等于把所有应用数据变成服务端配置。下列内容仍是本地运行或用户数据：

- Conversation、Message、Turn、Tool execution、Memory；
- Artifact metadata/payload、附件、生成图片；
- Workspace 内容、Rootfs、terminal/tab/PTY；
- OAuth token、用户 Provider key、备份凭据和设备 Keystore material；
- 设备 UI 偏好、窗口状态、缓存、诊断与统计。

### 7.2 存储与有效读模型

V2 只公开一个 `SettingsStore`，内部组合三种来源：

```text
Built-in defaults ─┐
Local Settings ────┼─ EffectiveSettingsResolver ─ EffectiveSettingsSnapshot
Verified managed ──┘                              ├─ settings
                                                   ├─ lock index / source info
                                                   ├─ effective revision
                                                   └─ managed state
```

- 现有 DataStore 继续只保存本地 `Settings`；
- 已验证受管快照作为不可变、签名的文档 aggregate 保存到 app-private 原子文件；
- 企业分发的密钥材料与设备定向协议尚未定稿；本轮受管记录沿用既有 `Settings` 的明文配置字段，
  不引入 `secretRef`、generation 专属密钥文件、Android Keystore owner 或未经定义的 `deviceId` target；
- Local shadow 的原始 DataStore 流变为 Store 私有实现，业务消费者统一订阅唯一有效快照；
- 同 id 的受管记录覆盖运行时有效值，本地记录作为 shadow 保留，不被受管载荷反写或删除；
- UI 从有效快照读取来源和 lock reason，但授权仍在 Store 写入边界重新验证。

为避免把 Store 扩成 God Object，envelope、验签和原子文件 adapter 固定放在白名单文件
`ManagedConfiguration.kt`，effective snapshot 与纯 resolver 固定放在 `EffectiveSettings.kt`。这些类型均为同包 `internal`
协作者，不进入 UI/Application DI 图、不各自发布 Flow，也不成为可绕过 `SettingsStore` 的第二入口。

签名 envelope 是一个整包验证、整包替换、无需局部关系查询的文档 aggregate，使用原子文件比拆入 Room 更简单，
也不违反第三范式。V2 不为受管配置新增 Room 表。

#### 7.2.1 受管 envelope 不是远端 `Settings` JSON

服务端完整下发使用独立、版本化的 `ManagedConfigurationEnvelope`，不能直接序列化当前 `Settings` 数据类。envelope 的
逻辑结构固定为：签名元数据（schema、tenant、generation、有效期）+ 按稳定 id 归一化的记录 + 引用边 +
lock index + asset 引用。即使物理上是一个文档，记录也不能内嵌另一份可独立寻址记录或用显示名/下标建立关系。
验签 trust anchor 来自应用内置 keyset 或已由旧可信 key 签名的轮换包，envelope 不能自带一个未经信任的公钥证明自己；
已接受的 tenant/device/generation 与 active envelope 同一原子提交，防止重放旧包。

字段所有权固定如下：

| 字段类别 | 受管 envelope | 有效读模型规则 |
| --- | --- | --- |
| Provider/Model/Assistant/MCP/TTS/ASR/Search 的公开配置 | 按稳定 id 与引用边下发 | 同 id 受管记录覆盖 local shadow；引用图必须在同 generation、Built-in 或协议明确允许的本地能力内闭合 |
| API key、密码和 MCP headers | 当前与本地 `Settings` 一样随所属记录明文保存 | 企业密钥分发协议定稿前不增加第二个密钥 owner；OAuth token 始终是设备本地状态 |
| 头像、背景、Skill 等受管资源 | 只允许同 generation 的签名 asset/ref | 发布前全部落入 generation staging；禁止服务端传主机绝对路径或任意 `file://` URI |
| `Assistant.workspaceId`、Workspace root/content、terminal 状态 | 不允许下发，managed Assistant 中该字段必须为空 | Workspace 是设备/用户数据；V2 不增加象征性 workspace binding 或把本地 id 猜成服务端引用 |
| 设备 UI 偏好、缓存、诊断、备份端点 | 不允许下发 | 始终取本地值，不进入 lock index |
| 默认选择、策略和能力开关 | 使用稳定 id/具名字段下发，可单独锁定 | 不用列表位置，不复制为 UI 本地状态 |

启动顺序固定为 local DataStore materialize → 读取并重新验签当前受管 envelope → 校验 assets/引用图 → 生成一个
`EffectiveSettingsSnapshot` → 发布后开放配置依赖的恢复门禁。`managedState` 只取 `ABSENT/ACTIVE/DEGRADED/BLOCKED`：无
受管包为 `ABSENT`；已验证且未过期为 `ACTIVE`；过期或传输中断但仍有 LKG 为 `DEGRADED`；启动时存在受管包却无法验签、
解密或闭合引用且没有可用 LKG 为 `BLOCKED`。`BLOCKED` 不得回退 local shadow 继续执行 AI/MCP，只允许 UI 展示诊断和接收
新受管包。

### 7.3 唯一 API 与提交顺序

`SettingsStore` 最终只暴露四类能力：

```kotlin
val effectiveSettings: StateFlow<EffectiveSettingsSnapshot>
suspend fun updateLocal(transform: (Settings) -> Settings): Settings
suspend fun restoreLocal(snapshot: Settings): Settings
internal suspend fun applyManagedSnapshot(envelope: ByteArray): ManagedApplyResult
```

普通 UI、市场导入和备份恢复不能获得 `applyManagedSnapshot`。来源是有效读模型的事实，不是调用方可伪造的写入参数；
V2 将真实 lock/generation 校验作为 Store 内的纯函数，在同一个 mutex 内针对最新 local 与 managed generation 执行。
备份使用专用 `restoreLocal`，服务端来源只能进入 internal managed apply；普通更新入口没有 `BACKUP_RESTORE`、
`ENTERPRISE_DELIVERY` 或其他可伪造的来源枚举。

锁定记录的写语义也不留给 UI 猜测：普通本地更新命中受管锁时整次更新失败且不改 local shadow；
`restoreLocal` 可以原子替换完整本地快照，但当前 effective 值仍由 managed 覆盖，只有签名撤回后新 shadow 才可见。未命中
lock 的本地记录继续可编辑，禁止为受管状态建立另一套 update API。

当前 `commitSettings` 的“规范化→持久化→发布”顺序必须保留为唯一内部提交模板。它是可故障测试的纯顺序约束，
不是第二 owner，不为减少文件数而强行内联；文件必须由 `SettingsCommitCoordinator.kt` 重命名为 `SettingsCommit.kt`。
真正删除的是 AllowAll 占位扩展点和可绕过 managed lock 的写入口。读取和写入仍共用
`normalizeForPersistence/materializeForRead`，不得恢复成两套默认补齐。

受管快照应用固定为：

1. 有界读取和解析 staging envelope；
2. 验证签名、tenant 约束、schema 和单调 generation；
3. 验证稳定 id 唯一、引用图闭合、默认选择存在、策略合法；
4. 写入 generation 专属 assets，任何失败都不改变有效 generation；
5. 原子替换受管 envelope；
6. 在持久化成功后一次发布新的 `EffectiveSettingsSnapshot`；
7. 异步清理不再被现 generation 引用的旧 assets。

验签失败、版本倒退、依赖缺失或资源不完整时继续使用上一份 last-known-good，不能发布半包。快照过期只把
`managedState` 标记为 expired/degraded 并提示用户，不能自动暴露本地 shadow 绕过策略；只有更高 generation 的已签名
撤回快照才能解除覆盖和 lock。服务端传输 adapter 只负责获取 envelope；在 HTTP API 未冻结前不在核心层预设端点、
轮询协议或 DTO 分叉。

### 7.4 合并与身份规则

- 合并以稳定 id 为准，不以显示名、列表位置或类型整表覆盖；
- 优先级固定为 Built-in < Local < Managed；覆盖的是有效读模型，不改写低优先级来源；
- 受管图中的引用只解析到同 generation 的受管记录或协议明确允许的本地能力，不能碰巧绑定同 id 市场项；
- 服务端删除受管记录必须通过新 generation 表达；网络失败、过期或空响应都不是删除；
- `searchServiceSelected: Int` 必须迁移为稳定 service id，避免排序、删除和受管合并后指错记录；
- 该变更使用 DataStore 的一次性显式迁移：根据旧列表和 index 计算 id、写新 key、删除旧 key，不保留双读；
- 旧 key 的迁移代码作为合法持久化迁移保留，不能在客户端升级完成后当成错误修复删除。

## 8. MCP：一个 server 一个 ConnectionSlot

当前 `McpManager` 用 `clients`、`connectedConfigs`、`status`、`reconnectJobs`、`dormantJobs`、
`reconnectAttempts`、`serverLocks`、`authorizationJobs` 等并行 map 表达同一 server 生命周期。任何增删、重连或取消都要
跨 map 保持一致。

V2 只保留一个 keyed `ConnectionSlot`：

```text
ConnectionSlot(serverId)
├─ desired config fingerprint + effective revision
├─ client
├─ lifecycle state/status
├─ reconnect attempt/job
├─ dormant/auth job
└─ operation mutex
```

所有入口最终走一个 `reconcile(effectiveRevision, desiredConfigs)`：

- 有效配置变化、应用回前台、网络恢复和用户手工同步都只是触发 reconcile；
- reconcile 以 id + connection fingerprint 做 create/update/remove，不为每种触发原因复制重连主链；
- 仅工具 schema/开关变化时刷新工具，不重建连接；连接参数变化时关闭旧 client 后创建新 client；
- job 只能修改拥有它的 slot，revision/fingerprint 变化后旧 job 的结果被拒绝；
- `callTool` 在 slot mutex 内确认 client 与最新 fingerprint 匹配，取消继续传播；
- 对外状态是 slots 的只读投影，不再另有可独立修改的 `_status` 权威；
- managed MCP headers 仍作为既有配置记录字段解析；OAuth token 始终为设备本地状态，不进入服务端快照。

实施后删除上述并行 map、`getServerLock`、重复 `createAndConnect/reconnect/syncAll` 决策分支；保留一个 Manager 是因为
它确实拥有多连接进程资源，不能为了删类把 client 生命周期塞入 SettingsStore。`McpConnectionKey` 作为 slot 的 private
fingerprint 合并进 `McpManager.kt`，删除独立文件；用户手工刷新入口由含义模糊的 `syncAll()` 重命名为
`refreshConnections()`，它也只触发同一个 reconcile，不建立第二条同步路径。`McpStatus`、`McpOAuthClient` 和
`NetworkMonitor` 分别是 UI contract、外部授权 adapter 和系统网络 adapter，明确保留。

## 9. Artifact、Skill、Backup 与 Workspace

### 9.1 Artifact

`ArtifactStore` 的唯一 owner 地位不变。V2 删除的是旁支和重复引用解释，而不是状态机：

- 删除 `adoptSettingsOwnedImages` 及其启动期调用；它只修复旧写路径制造的无 metadata 引用；
- Settings 引用集合、detach、impact inspection 和 GC root 继续复用一个 `ArtifactReferencePolicy`；
- `ArtifactSettingsCoordinator` 继续承担跨 DataStore 与 Artifact 生命周期的明确交接，不允许 UI 自行删旧文件；
- CREATING/ACTIVE/DELETING、lease、checkpoint 后 publish、失败补偿和 recovery 均保留；
- `ArtifactStore`、`ArtifactPayloadStore`、`ArtifactReferencePolicy`、`ArtifactSettingsCoordinator` 四个边界全部保留；
  本轮除删除 adoption 外不重命名、不跨类搬运状态机。

### 9.2 Skill 与 Backup

`SkillManager` 仍拥有 Skill 安装目录和包语义，`BackupArchiveService` 仍拥有灾备 manifest 与 restore 编排。两者不能直接
操作 ArtifactDAO，也不能因为都使用 ZIP 就共享业务状态机。

`SkillManager` 内的 `saveSkillFileUnlocked`、`saveSkillFileBytesAtomicallyUnlocked` 和 `deleteSkillFile` 共享“复制当前树→
修改 staging→验证→原子发布→finally 清理”流程，合并为一个 private `mutateSkillTree`；三个公开 API 名称和同步/挂起
调用方式保持不变。整包 `importSkillBundleAtomically` 使用 skills-root 级事务，不能合并进单 Skill 流程。

`BackupArchiveService`、`PendingBackupRestore` 和 `BackupSettingsPolicy` 明确保留，不与 Skill/Artifact 抽取跨领域
`FileTransaction`。它们的 manifest、跨数据库/目录 staging 和重启恢复语义不同，机械相似不足以支持合并。

旧备份 manifest 和 `migrateLegacySettingsJson` 是用户持有的合法外部格式，不因“所有已安装客户端是 0.0.18”而删除。
受管配置也不得进入普通 Settings 备份；恢复本地备份后重新与当前受管 generation 计算有效配置。

### 9.3 Workspace/PRoot

调用关系固定为：

```text
Workspace UI/VM command → WorkspaceApplicationService → Workspace repository/manager
Workspace UI/VM read    → WorkspaceQueryService → persisted projection + WorkspaceTerminalRuntime read projection
shell tool / terminal   → ProotLaunchSpec → 各自 process adapter
```

Query 不反向调用 ApplicationService，`ProotLaunchSpec` 不启动进程、不持有 PTY，也不读取 Settings/DI；它只是从已经解析好的
Workspace 与命令输入生成相同 argv/env/bind/cwd 的纯值。

- `WorkspaceApplicationService` 继续作为 durable Workspace command 和 terminal 用户命令入口；
- `WorkspaceTerminalRuntime` 继续唯一拥有 PTY、tab、creation job 与 viewport 绑定；
- `WorkspaceTerminalQueryService` 合并到 `WorkspaceQueryService`，因为二者都是同一 bounded context 的只读投影；
- `WorkspaceTerminalScreenUiModel` 移入 `WorkspaceQueryService.kt`，`observe()` 重命名为含义明确的
  `observeTerminal(workspaceId)`，删除旧 Query 的 DI 注册和 UI import；
- `WorkspaceTerminalViewport` 是 UI host capability，不是第二生命周期 owner，继续保留；
- 新增且只新增 `workspace/.../ProotLaunchSpec.kt`，由 shell tool 与交互 terminal 共用 PRoot executable、bind、cwd、env
  和 argv 装配；`ProotShellRunner` 与 `WorkspaceTerminalSession` 只把 spec 交给各自进程 adapter，删除两处手写参数；
- rootfs install/update、文件操作和 terminal 生命周期保持不同失败边界，不建立 Workspace 总管类。

## 10. 历史数据策略：迁移与错误修复严格分开

“客户端均已升级到 0.0.18”只说明可以退休已由 0.0.18 完成的一次性错误状态修复，不代表可以删除用户数据的历史
解释能力。备份文件、停用设备、数据库副本和合法历史消息不会因为当前安装版本而自动重写。

### 10.1 必须保留

| 内容 | 决策 | 原因 |
| --- | --- | --- |
| `Migration_1_2` 至 `Migration_7_8` | 永久保留 | Room 的历史升级契约；V2 仍必须从任意已支持 schema 迁到 8/后续版本 |
| schema JSON 1～8、migration 注册和链式测试 | 保留 | 证明 migration 与 fresh schema 同构并保全数据 |
| `SettingsOcrMigration` | 保留 | 合法 DataStore key 迁移，不是错误数据自愈 |
| `migrateLegacySettingsJson` | 保留 | 用户可长期保存并导入旧备份；外部文件未必已在 0.0.18 中打开 |
| 旧备份 manifest 的受支持读取 | 保留 | 是公开导入契约；除非另行宣布格式停止支持并提供转换工具 |
| 历史消息的 nullable/default 解码 | 保留 | schema 当时允许的合法事实；不能以当前 writer 不再为空为由拒绝历史会话 |
| nullable `sourceProfile`、历史 message metadata/SubAssistant payload 解码 | 保留 | 已持久化历史与 Provider replay 的合法输入；只有显式数据迁移后才可收紧 |
| Provider/Android/文件格式兼容 | 保留 | 外部系统与平台兼容，不属于客户端错误数据修复 |

若 V2 需要 Room schema 9，必须在 8→9 新增显式 migration，并继续保留 1→8 全链。当前方案没有需要 Room 9 的设计。

### 10.2 确定删除的错误状态修复

| 当前实现 | 产生原因 | V2 处理 |
| --- | --- | --- |
| `ReconcileOrphanedTurnExecution` 及 `TurnRecovery.reconcileOrphanedTurnExecution` | 旧版本可能留下 owning message 缺失的 execution fact；V1C 用专用 command 原子终结 | 0.0.18 已完成恢复且当前 writer/事务协议不能再生成后，删除 command、Reducer/Runtime/Coordinator 分支和 legacy test |
| `ArtifactStore.adoptSettingsOwnedImages` | 旧 Settings 图片写入曾绕过 Artifact metadata owner | 0.0.18 已完成 adoption 且所有当前入口经 typed ownership 后，删除启动扫描、adoption 分支和对应旧数据测试 |

删除后如果再次出现这两种状态，应作为当前写协议破坏或数据损坏 fail-closed，而不是重新加 silent repair。

### 10.3 其他分支的判定规则

实施前对 `legacy/compat/fallback/orphan/migrate/backfill/nullable` 命中逐项登记，但不能按关键词批量删除。只有同时满足
以下条件才归入错误修复删除：

1. 该状态从未是任何已发布 schema/格式允许的合法值；
2. 当前 writer、migration、restore/import 和进程中断都不能产生它；
3. 0.0.18 的一次性修复已经覆盖全部现存客户端数据；
4. 删除后异常会明确失败，不会把数据静默丢弃或误解释；
5. command、repository API、测试、文档和指标必须在同一阶段一并删除。

不满足任一条就保留，或先设计显式持久化迁移；禁止把运行时双读当成迁移。

### 10.4 数据结构与第三范式

V2 默认不改变 Room 结构。若实施中发现确需关系化新事实，必须遵循：

- 独立实体一表一事实，列保持原子值；
- 多对多使用 join table，引用使用稳定 id 和外键；
- 不存储可从其他列推导的重复状态，不以列表下标或显示名作为关系；
- 需要过滤、连接、完整性约束的关系不能藏在 JSON；
- 只作为整体加载/保存、内部字段不参与关系查询的值对象可以保留 JSON；
- migration、schema 同构、数据保全和 `foreign_key_check` 与结构变更同一交付。

## 11. 冻结变更账本、减法门禁与 UI 保真

### 11.1 必须删除

| 删除对象 | 同步删除/迁移范围 | 删除后的唯一替代 |
| --- | --- | --- |
| `ReconcileOrphanedTurnExecution` | command、Transition/Reducer、Runtime、Coordinator、Recovery 分支及 `TurnRecoveryLegacyExecutionTest` 等专用用例 | 当前 writer 不允许产生；再次出现按损坏数据 fail-closed |
| `ConversationMutationBuilder` | `build/buildHeader/buildMutation` 和所有 old/new 全树推导测试 | `ConversationTransition.plan` 直接返回 `DraftOnly` 或 snapshot/header + delta mutation + facts |
| Runtime durable command 实现 | `startTurn`、`submit`、`withCommandWrite`、`validateCommandOwner`、`commandMutex`、`commandWrites`、`isCommandWriteInFlight` | `ConversationCommandCoordinator` + `ConversationOperationLocks`；命令期间持 Runtime lease 防逐出 |
| Runtime 多份 turn 状态 | `_generationJob/generationJob`、`activeTurnId`、`cancelReasons` 及 track/get/set/request/peek/consume API | Runtime 内唯一 private `ActiveTurnRuntime`；Query 只读 presentation，不读 Job |
| Master 私有 supersede helper | `beginSupersedingTurn`、`SupersededTurnBarrier` 及只适用于 Master 的 previous Job/turn 拼装 | `ActiveTurnRuntime` 在安装时接管前任 worker/turn barrier；Master/Child 共用 Registry 安装协议 |
| 独立 processing 状态读链 | Runtime 公开 `processingStatus`、Registry/Query 转发和 `ChatVM.processingStatus` | `ConversationPresentation.processingText`；与 active runtime 同生命周期发布 |
| 无请求终止条件的 append-scroll 等待 | 只等待目标 message/layout/IME、只能等到下次发送或页面销毁才取消的路径 | receipt.turnId + `ConversationPresentation.activeRequestTurnId`；目标未出现且该 active runtime 已终止时立即取消 |
| 无消费者的 checkpoint 事件 | `GenerationChunk.Checkpoint`、`TurnEvent.Checkpoint` 及只验证这些事件的断言 | `GenerationCheckpoint` awaited callback 仍是唯一 durable checkpoint 边界 |
| `SubAssistantRunLeaseRegistry.kt` | `SubAssistantRunLeaseRegistryTest` 和对该内部类型的直接测试 | lease map/handle 变为 `SubAssistantRunGate` private 实现，测试 Gate 行为 |
| 三个 presentation 原文件 | `ConversationTurnPresentation.kt`、`ToolCallPhase.kt`、`ToolCallProjection.kt` | 合并后的 `ConversationPresentation.kt` |
| UI 私有工具阶段推断 | `ChatMessageTools.resolveToolCallPhase/resultTerminalPhase` 的独立实现 | `ConversationPresentation.kt` 的唯一纯 resolver；active durable phase 优先，历史 output 仅作已结束展示 |
| `McpConnectionKey.kt` | `McpConnectionKeyTest` 的内部形状断言 | `McpManager.ConnectionSlot` private fingerprint；行为测试迁入 Manager reconcile 测试 |
| MCP 并行状态容器 | `clients`、`connectedConfigs`、`reconnectJobs`、`dormantJobs`、`reconnectAttempts`、`serverLocks`、`authorizationJobs` 和可独立写 `_status` | 一个 `slots: Map<Uuid, ConnectionSlot>`，状态 Flow 只是 slots 的投影 |
| MCP 重复重连决策 | `getServerLock` 及 `addClient/createAndConnect/reconnectClient/syncAll` 中重复的 create/update/remove 分支 | 一个 `reconcile(revision, desiredConfigs)`；连接、工具刷新、OAuth 均作为 slot transition |
| `ArtifactStore.adoptSettingsOwnedImages` | 启动调用和 `ArtifactStoreLifecycleTest` 中 adoption 专用场景 | 当前 typed ownership；缺 metadata 视为损坏而非静默补写 |
| 可替换的 `SettingsWritePolicy` 接口与 `AllowAll` 实现 | Store 构造参数、测试 fake policy、`ENTERPRISE_DELIVERY` 可伪造来源 | `SettingsWriteRules` 内唯一纯 lock/generation 校验；managed apply 是 internal 专用入口 |
| 未读取的 `data_version` key 声明 | 不进入 `Settings`、无 migration 或 writer 的死声明；既有 DataStore 标量不删除 | `Preferences` 的未知 key 透传保留，不创建新的 version owner |
| 运行时 Search 下标身份 | `Settings.searchServiceSelected: Int` 及 index fallback | `selectedSearchServiceId: Uuid?`；旧 key 只在一次性 DataStore migration 中读取 |
| `WorkspaceTerminalQueryService.kt` | DI 注册、VM import 和该 service mock | `WorkspaceQueryService.observeTerminal` |
| 重复 Skill 单目录事务骨架 | 三个 API 内各自的 copy/stage/validate/publish/finally 流程 | `SkillManager.mutateSkillTree` private 模板；公开 API 不变 |
| 全局 `CAPABILITY_HINT` 与末条消息追加 | 常量、注入分支、提示词断言和文档 | 每个附件旁的 `AttachmentProjectionTextMetadata` 事实；不生成跨来源提示 |
| model-only 图片能力判断与 serializer 语义 fallback | `nativeImageSupported` 单布尔结论、`Image output omitted`、编码失败补空文本及相应测试 | 同一 `RequestMediaCapabilities`；不支持在 Transformer 降级，宣称 native 后编码失败则 request 明确失败 |
| 绑定内部形状的测试断言 | 类名、文件名、函数源码片段和允许旧 facade 的白名单 | 状态机、事务失败、依赖方向和用户行为测试 |

### 11.2 必须重命名

| 当前名称 | V2 名称 | 理由 |
| --- | --- | --- |
| `ConversationReducer.kt` / `ConversationReducer` | `ConversationTransition.kt` / `ConversationTransition` | 它同时产生新状态、delta mutation 与 execution facts，不再只是 reducer |
| `ConversationReducerTest` | `ConversationTransitionTest` | 测试目标随唯一 command planner 对齐 |
| `GenerationHandler.kt` / `GenerationHandler` | `GenerationLoop.kt` / `GenerationLoop` | 只负责 Provider + tool step loop，不是应用层 handler |
| `generateText(...)` | `GenerationLoop.run(GenerationRequest)` | 删除超长参数表；请求模型定义在同文件，不新增 `TurnSpec`/handler 层 |
| `PreferencesStore.kt` | `SettingsStore.kt` | 文件中的真实 owner 已是 `SettingsStore`，旧文件名失真 |
| `SettingsWritePolicy.kt` | `SettingsWriteRules.kt` | 不再存在可替换 policy 接口，只保存来源和值规则 |
| `SettingsCommitCoordinator.kt` | `SettingsCommit.kt` | 保留纯 `commitSettings` 顺序，但不把单函数称为 Coordinator |
| `settingsFlow` | `effectiveSettings` | 所有业务消费者明确读取 Local + Managed + Built-in 的有效快照 |
| `searchServiceSelected` | `selectedSearchServiceId` | 选择身份从列表位置改为稳定 id |
| `McpManager.syncAll()` | `refreshConnections()` | 这是用户/生命周期触发 reconcile，不是另一种配置同步协议 |
| `WorkspaceTerminalQueryService.observe()` | `WorkspaceQueryService.observeTerminal()` | 合并后仍明确表达 terminal read projection |
| `ImageInputProjection` | `AttachmentInputMode` | 投影对象不是图片本身，且必须完整表达 `NATIVE/REFERENCE_ONLY/UNAVAILABLE` 三态 |

### 11.3 必须合并

| 当前重复 | 合并位置 | 合并后仍分开的边界 |
| --- | --- | --- |
| `ConversationReducer` + `ConversationMutationBuilder` | `ConversationTransition.kt` | Coordinator 负责 IO/锁，Repository 负责事务，Runtime 负责发布 |
| Runtime Job/turnId/cancel reason/approval/processing | 一个 private active `ActiveTurnRuntime` | immutable `TurnHandle` 继续是 durable CAS token；`ttsQueueSessionId` 继续属于可跨终态的 TTS Runtime 资源 |
| `ToolCallPhase`、active checkpoint projection、turn presentation、UI fallback resolver | `ConversationPresentation.kt` | presentation 不写 durable 事实；工具终态变换仍在 Transition |
| Reducer 与 `TurnFinalization` 的 cancel/interrupted tool copy | `ConversationTransition.kt` 一份 internal 领域函数 | `TurnFinalization` 跨 Conversation 编排职责保留 |
| SubAssistant run lease registry + Gate | `SubAssistantRunGate.kt` private 状态 | `DelegationCoordinator` 与 `TurnRecovery` 仍只调用 Gate 公共能力 |
| MCP client/config/status/jobs/lock | `McpManager.ConnectionSlot` | `McpStatus`、OAuth adapter、NetworkMonitor 保留 |
| Workspace persisted + terminal read projection | `WorkspaceQueryService.kt` | `WorkspaceTerminalRuntime` 继续独占 PTY 生命周期 |
| shell 与 terminal 的 PRoot 参数装配 | `workspace/.../ProotLaunchSpec.kt` | `ProotShellRunner` 与 Termux session adapter 继续分开 |
| Skill 单目录 save/import/delete staging | `SkillManager.mutateSkillTree` | bundle root transaction 与 Backup restore 不合并 |
| Local/Managed/Built-in 配置读模型 | `SettingsStore.effectiveSettings` | Local DataStore 与 managed 原子文件仍是不同基础设施 |
| model modality、endpoint/profile 与各 serializer 的图片判断 | `Provider.requestMediaCapabilities(...)` 返回一个请求级值对象 | Provider serializer 保留各自 wire 结构；Transformer 仍独占来源内投影 |

### 11.4 必须修改

| 修改对象 | V2 明确修改 | 禁止结果 |
| --- | --- | --- |
| 四个 header commands | 实现同文件 `HeaderConversationCommand`；Draft/full/header-only plan 共用 private `applyHeader`；Draft 只允许 UpdateHeader/MoveToAssistant | non-resident 复制 `when`，为统一链加载整棵长会话，或把空 Draft 写入 Room |
| `ConversationRepository` command commit | 一个 `commit(ConversationWrite)` 在 Room transaction 内处理 Draft materialize 或 delta+facts；其他 create-tree/delete API 保持各自显式语义 | Coordinator 继续传 persistence callback，或 Repository 发布 Runtime/UI Flow |
| `MasterTurnCoordinator.sendMessage` / `ChatVM.handleMessageSend` | 改为 suspend 接受链；LAZY worker 经 Registry 在 operation lock 内安装并启动后才返回 receipt | UI 直接持有 Job，先返回 receipt 后安装，或持锁等待旧 turn finalization |
| `SendMessageReceipt` | 增加 `turnId`；仍只包含三个稳定 id，不含 Job、Deferred、callback 或 mutable state | 把 receipt 当 durable success，或再建 send-result EventBus |
| `ConversationRuntimeRegistry` | 新增唯一 internal `installAndStartActiveRequest`，与 durable command 共用 operation lock；供 Master/Child 使用 | Application 各自操作 Runtime CAS，或 Registry 执行业务 terminal finalization |
| `ActiveTurnRuntime` | 作为 `ConversationRuntime.kt` 内 private 状态机，拥有 request identity、当前 worker、前任 barrier、取消原因、审批 gate、phase 与 processing status | 新建公开 runtime owner，或 Runtime 继续保留并行 `generationJob/activeTurnId/cancelReasons` |
| `ConversationCommandCoordinator` 非终态授权 | START/checkpoint/approval 在 operation lock 内同时校验 current active identity；Finalize 只校验 durable handle 并允许 superseded owner 收口 | check-then-act、stale checkpoint，或拒绝旧 handle 做幂等 terminal CAS |
| `UpdateToolApproval` | 增加 owning `TurnHandle`，并校验 locator message 与 handle 的 assistant slot；审批继续沿原 handle | 仅凭 message/ordinal 修改，或审批期间创建第二 handle/turn |
| `TurnHandle` | 保持四个稳定字段的不可变 CAS token，只在 START 成功后进入 running/approval 状态并传给 Engine/command | 把 Job、Flow、gate 或 UI state 塞进 durable command token |
| `ConversationPresentation` | 合并后公开 `activeRequestTurnId`、turn phase、processing text 和 tool projections；只读、不可提交 | UI 读取 Job、把 preparing id 当 durable turn，或 presentation 成为第二 writer |
| `GenerationRequest` / `TransformerContext` processing 上报 | 改用 ActiveTurnRuntime 生成的 request-scoped `reportProcessingText`；每次上报校验 turn+worker identity | 向 Generation 暴露可写 Flow、旧 worker 覆盖新文字、保留 Registry→Query→VM 转发链 |
| `ChatPage` 发送与 append scroll | 在 UI scope 等待 suspend receipt；非空请求被接受后沿现有时机清空输入；以 message/turn identity、分支、layout count、IME 和 presentation 决定 READY/INVALIDATED | UI 持有 Runtime Job、固定延时、只按列表长度滚动、失败后永久等待 |
| `TurnPipelineFactory` | Master/Target 的 `AttachmentProjectionTransformer` 都固定为最后一个 input transformer；Master 仅在它之前保留 tool artifact replay | 调用方自行拼 transformer，或投影后再改 media |
| `Provider.kt` / `TransformerContext` / `TextGenerationParams` | 定义并传递同一个 `RequestMediaCapabilities`；每次请求解析一次 | model-only 布尔值、host 字符串猜测或 adapter/Transformer 双算 |
| `AttachmentProjectionTransformer` | 按 USER/ASSISTANT/TOOL_OUTPUT 与三态逐附件原位投影；每图恰好一个事实，不落库 | 改 role、搬来源、调用 OCR/识别、重引入全局 hint |
| Chat/Responses/Claude/Gemini serializer | 只编码 capability 允许的 media container；native 编码失败 fail-fast；保留协议要求的外层 role 和 tool call 顺序 | 静默丢图片、补空文本或把 tool/assistant 图片伪装成用户图片 |
| `ResponseAPI` raw replay | 原 `response.output` group/call 顺序不变；只把请求投影 marker 在对应 assistant replay 后追加一次 | 重建 opaque output、重复可见文本或 marker 进入 durable metadata |
| `SettingsStore` 与全部 `settingsFlow` 生产消费者 | Store 发布 `effectiveSettings`；消费者改读同一 `EffectiveSettingsSnapshot.settings/source/lock/revision`，raw Flow 降为 Store 私有 | Local/Managed 双读、consumer 自行补默认或按来源合并 |
| `ManagedConfiguration.kt` / `EffectiveSettings.kt` | 按 §7.2.1 实现 envelope、验签、LKG、四态 managed state、原子 asset 与唯一纯 resolver | 直接反序列化远端 `Settings`、第二个公开 Store/Flow、无 LKG 时静默回退 local |
| Settings 写入入口与设置 UiModel | LOCAL/market 命中 lock 时原子拒绝；restore 只替换 shadow；原页面显示 source/lock/blocked diagnostic | 只禁用 Compose 控件、为 managed 建第二套页面/ViewModel、写失败后发布假 effective 值 |
| Search 设置与全部搜索消费者 | `selectedSearchServiceId` 一次迁移后只按稳定 id 读写；删除旧 key 与运行时 index fallback | 同时维护 id/index，或排序后靠位置猜选中项 |
| `McpManager` | 每个 server 只有一个 `ConnectionSlot`；配置、网络、前台、手动刷新统一调用 revision-aware `reconcile` | 平行 map、每种触发源复制连接主链、stale job 覆盖新配置 |
| `ArtifactStore` 启动恢复 | 删除 settings image adoption 后只恢复合法 Artifact 状态；缺 metadata 明确损坏 | 重新扫描 Settings 猜 owner 或补 DAO 行 |
| `SkillManager` | 三个单目录公开 API 复用 private `mutateSkillTree`，整包 import 仍走 root transaction | 抽取跨 Skill/Backup/Artifact 的通用文件事务 |
| `WorkspaceQueryService` / shell 与 terminal adapter | 合并 terminal 只读 projection；两种启动都消费同一 `ProotLaunchSpec` | Query 获得写能力、合并 PTY owner、继续手写两套 argv/env/bind |
| 架构/行为测试与 `docs/references/` | 删除内部形状断言，改测 owner、事务、状态迁移、用户行为；每阶段同步当前事实 | 允许旧 facade 的白名单、计划术语进入当前参考、只凭源码字符串验收 |

### 11.5 明确保留

下列对象不是“暂时保留”，V2 不再对其去留留到实施期判断：

- `ConversationCommandCoordinator`、`ConversationRuntimeRegistry`、`ConversationOperationLocks`；
- `TurnEngine.bind`、`TurnOutcome`、`TurnEvent.Streaming/Phase/Finished`、`TurnPipelineFactory`；
- immutable `TurnHandle` 的 CAS 授权语义；
- `SendMessageReceipt`、`AttachmentProjectionTransformer`、`AttachmentProjectionTextMetadata` 及按消息身份滚动的交互；
- `generationDoneFlow` 作为 `TTSAutoPlay` 的单消费者、非 replay 边沿通知；它不表示 durable 状态，不并入 presentation，
  本轮不增加第二个同义 completion flow；
- Master 与 Delegation 两个不同的 `TurnEvent` consumer；
- `TurnFinalization`、`GenerationSideEffects`、当前合法状态的 `TurnRecovery`；
- `ConversationTitleCoordinator` 的 token + expected-title CAS 与 query-only 活动合并；
- Runtime 的 `ttsQueueSessionId` 与现有连续播放/审批继续语义；
- `commitSettings` 的 normalize→persist→materialize→publish 顺序；
- `ArtifactStore`、`ArtifactPayloadStore`、`ArtifactReferencePolicy`、`ArtifactSettingsCoordinator`；
- `BackupArchiveService`、`PendingBackupRestore`、`BackupSettingsPolicy`、旧备份合法读取；
- `McpStatus`、`McpOAuthClient`、`NetworkMonitor`；
- `WorkspaceApplicationService`、`WorkspaceTerminalRuntime`、`WorkspaceTerminalViewport`；
- `Migration_1_2`～`Migration_7_8`、schema 1～8、`SettingsOcrMigration`、`migrateLegacySettingsJson`；
- Provider/Android/历史消息和文件格式的合法兼容。

### 11.6 新增生产文件白名单

V2 只允许新增四个生产文件，其他新文件必须先修改本方案并同时指出可删除的旧文件：

| 新文件 | 唯一职责 | 替代/抵消 |
| --- | --- | --- |
| `service/runtime/ConversationPresentation.kt` | Tool/Turn 的唯一纯展示投影 | 删除三个旧 presentation 文件和 UI resolver |
| `data/datastore/EffectiveSettings.kt` | effective snapshot、lock/source index 与纯 resolver | 删除 raw/effective 双读和 AllowAll policy |
| `data/datastore/ManagedConfiguration.kt` | envelope、验签/图校验、原子文件与 generation 资源协议 | 落地此前准备能力，不再新增 `EnterprisePolicyStore` 等公开 owner |
| `workspace/.../ProotLaunchSpec.kt` | 两类 PRoot 启动的公共 argv/env/bind/cwd 事实 | 删除 shell/terminal 两套手写装配 |

`ConversationTransition.kt`、`GenerationLoop.kt`、`SettingsStore.kt`、`SettingsWriteRules.kt`、`SettingsCommit.kt` 均为原文件
直接重命名，不计作新增文件。`GenerationRequest` 留在重命名后的 `GenerationLoop.kt`，`ActiveTurnRuntime` 留在
`ConversationRuntime.kt`，`ConnectionSlot` 留在 `McpManager.kt`，managed storage helper 留在上述两个 Settings 白名单文件；
不得再拆成 DTO/Factory/Manager 文件。`RequestMediaCapabilities`、`RequestImageSupport` 和 `AttachmentInputMode` 全部进入 §6.3
指定的现有文件，不增加白名单项。

### 11.7 行数与结构硬门禁

基线固定为提交 `e19ae595bdcd5b4159dea2375852ab00efc730dc`。以下是 2026-08-26 按生产 Kotlin 物理行统计的核心范围；
空行和注释计入，防止通过改统计工具改变结果，最终审查同时禁止靠删注释、压行或搬出范围达标。

该基线之后当前工作区的发送定位与附件投影生产代码合计净增加 115 行（发送定位 +83，附件投影 +32）。它们是已确认要
保留并在 V2 中收正边界的行为，不是无关噪声，也不提高下表上限：Phase A 必须把这 115 行计入实际起点，V2 的删除量要
同时吸收它们。若工作区在实施前变化，按同一路径重算并说明差值，不能改基线提交或把已接受行为排除在统计外。

| 范围 | 基线文件/行数 | V2 上限 | 约束来源 |
| --- | ---: | ---: | --- |
| Conversation/Turn：`service/runtime/*.kt` + Master/Finalization/Recovery/SideEffects/SubAssistant Gate/Lease + `GenerationHandler.kt` | 18 / 6,867 | 5,800 | 删除双 planner、Runtime 写协议、多 turn 状态、无用事件和重复终态逻辑 |
| Settings 本地/有效读模型：`data/datastore/*.kt`，不含 `ManagedConfiguration.kt` | 6 / 1,017 | 1,250 | 本地持久化、默认物化和有效读模型只能净增加 233 行；超过即说明旧链路删除或职责收敛不足 |
| MCP：`data/ai/mcp/*.kt` | 7 / 1,890 | 1,450 | 八组 parallel map 和多条 reconnect 决策收成 slot/reconcile |
| Artifact/Skill：`data/files/*.kt` | 14 / 2,681 | 2,450 | 删除 adoption 与重复单目录事务，不删状态机 |
| Workspace：app workspace service + `workspace/src/main` | 13 / 2,402 | 2,170 | 合并 query 和 PRoot 装配 |
| **核心合计** | **58 / 14,857** | **最多 56 / 13,370** | **文件净减至少 2，生产代码净减至少 1,487 行（10%）** |

另设一个不计入上表合计、与全仓统计重叠的“附件请求/wire 切片”：`Provider.kt`、`MessageMetadata.kt`、
`OpenAIProvider.kt`、Chat/Responses/Claude/Google serializer、`Transformer.kt`、`AttachmentProjectionTransformer.kt`，基线
9 文件/4,362 行，V2 最终不得超过 9 文件/4,362 行。当前工作区该切片净增 32 行，因此新增 capability 类型必须由删除
model-only 判断、全局 hint/fallback 和重复 serializer 分支抵消；不能把正确性修复变成永久净增层。

仓库全部 `src/main/**/*.kt` 同一基线为 610 个文件、125,713 行；V2 最终不得超过 124,713 行。UI/Application 消费者的
必要适配也包含在这个仓库总上限内，不能把核心代码搬到其他包规避核心预算。

`ManagedConfiguration.kt` 是 §11.6 白名单中的未来受管配置 document aggregate，不设单独的行数上限；企业分发协议尚未冻结时，
不得为了凑 Settings 子预算删除验签、图校验、原子 generation 资源发布或失败关闭。它必须维持为 `SettingsStore` 的同包 internal
协作者，不能借此拆出公开 Store、Flow 或额外生产文件。该文件仍计入核心和全仓总量，并在每阶段账本中单独报告物理行数；全局
13,370 行核心上限不因这个例外提高，其他域须共同吸收其预留成本。

执行规则：

1. Phase B、D、E 每个纯重构阶段的生产代码必须独立净减少；
2. Phase C 可以净增加，但 Settings 本地/有效读模型不得超过 1,250 行；`ManagedConfiguration.kt` 单独报告且新增文件不得超出 §11.6；
3. 测试与文档单独统计，行为测试允许增加；删除源码形状测试不能抵扣生产代码预算；
4. 每阶段记录 `git diff --numstat`、核心范围总量、全仓生产总量和删除账本完成项；
5. 删除空行、长期有效注释、错误处理、日志诊断或把多语句压成一行不计入架构减法；
6. 若正确实现无法满足预算，停止实施并先修订方案，不能删安全边界，也不能静默放宽指标。

### 11.8 UI/交互功能保真矩阵

新增 UI 体验只允许“受管配置来源与锁定展示”。此外，§6.3 明确授权一项线协议正确性修正：当前模型虽声明 IMAGE、但实际
endpoint/container 未证明支持时，从“尝试发送 native”改为原来源 `reference_only`；预览、附件持久化和
`inspect_attachments` 仍可用。依赖未声明 compatible 扩展的网关可能不再收到 ASSISTANT/TOOL_OUTPUT 原图，只有把该 host
纳入有协议证据的命名 profile 后才能恢复 native。除此之外 V2 不授权产品行为变化。下表每一行都是硬性兼容契约，不是
验收方式：

| 界面/用户路径 | 必须保持的现有行为 | 高风险架构动作 | 阶段退出证据 |
| --- | --- | --- | --- |
| New Chat、Drawer、会话列表 | 空 Draft 不入库/列表；首发原位进入同一会话；pin、标题、文件夹、助手和活动指示及时更新 | Transition、Runtime publish、Job presentation 删除 | JVM 并发 + Compose instrumentation + 真机新建/切换/后台恢复 |
| `ChatPage/ChatVM` 发送与编辑 | UI scope 等待接受凭证且不持有 Runtime Job；非空发送被接受后沿现有时机清空输入并以 receipt 目标定位；仅在目标已提交、分支未变、layout 就绪且 IME 关闭后滚到底部；新发送替代旧等待，失败/取消/supersede 必须结束等待；编辑、删除、选分支、截断、重新生成保持树与滚动位置；失败无幽灵消息 | 双 planner、TurnHandle/presentation、挂起接受、append-scroll 终止条件 | receipt/分支/layout/IME/失败表驱动测试 + 长会话和键盘真机回归 |
| 流式生成与停止 | START 后立即显示生成态；首 chunk 前可停止；chunk 连续；终态后按钮/动画及时消失；late cancel 不覆盖 Completed | active runtime、presentation 合并 | 三个时间窗口的 coroutine test + UI 自动化 + 真机 stop/regenerate |
| 工具卡片与审批 | call streaming、ready、pending、executing、completed、failed、cancelled、interrupted、denied、answered 状态与按钮不变；审批继续不创建第二 turn | Tool phase resolver 合并、checkpoint event 删除 | 全 phase 表驱动测试 + ask/approve/deny instrumentation |
| 图片生成等专用 Tool UI | 进度、失败原因、取消、结果预览和再次打开历史消息一致 | UI output fallback 收敛 | `ImageGenerationToolUI` 全状态测试 + 真机生成/取消 |
| 子助手 | Child preview 持续更新，phase/tool 名正确，ask_user 可回答，详情页流式，取消/删除无残留 run | Delegation event consumer、Gate/lease 合并 | Master/Child 集成测试 + `SubAssistantDetailPage` 真机路径 |
| 标题、建议、通知和声音 | 只有 Completed 触发标题/建议；手动标题不被覆盖；通知/成功失败声音时机不变 | Turn outcome/side-effect 去重 | side-effect 行为测试 + 前后台真机验证 |
| TTS 顺序播放 | 同 turn 审批继续复用队列；新 turn 按现有规则替换；生成终态后仍可继续播放 | active runtime 收敛 | TTS session 单测 + 自动播放/审批继续真机验证 |
| 头像、背景、附件与图片预览 | 裁剪后立即生效；临时文件不早删；头像/背景不被 GC；附件、删除和预览索引行为不变 | adoption 删除、Artifact/Settings effective 接入 | ownership/fault tests + 相册/拍照/删除/GC 真机回归 |
| 对话附件的模型可见性 | 用户上传、工具产出、助手原生图片保持原来源；视觉模型仅在实际 wire 容器支持时收到图片；文本/不支持容器收到紧邻来源的引用事实；无全局 hint、无错误归因；Responses raw replay 不重复文本/调用 | capability 收敛、附件投影链末化、serializer fallback 删除 | 3 来源 × 3 mode × Provider/profile serializer fixture + Master/Target 集成测试 + 视觉/文本模型真机回归 |
| Provider/Assistant/MCP/TTS/ASR 设置 | 所有本地新增、编辑、删除、排序、默认选择和即时生效保持不变；写失败不发布假配置 | `effectiveSettings`、write rules | 设置页 delta/并发测试 + 各类设置真机编辑 |
| 受管配置（唯一允许新增的体验） | 受管记录显示来源/锁定理由且不可改；本地 shadow 不丢；坏包保持 LKG；签名撤回后才恢复本地项；启动无可用 LKG 时显示阻断诊断且 AI/MCP 不静默使用 local | Managed snapshot | resolver/验签/原子失败测试 + managed UI instrumentation |
| Search 设置与搜索页 | index→id 后仍选择同一服务；排序、删除、受管覆盖不串项 | DataStore identity migration | 旧 key fixture migration + 设置/实际搜索一致性测试 |
| MCP 设置页与 Picker | 状态文字、手动刷新、工具列表、OAuth、断网重连、前后台恢复不退化或闪错 server | ConnectionSlot/reconcile | fake server 并发测试 + 网络切换/OAuth 真机回归 |
| Workspace Terminal | ready、创建/选择/重命名/排序/关闭 Tab、输入、resize、cwd、Skills bind、shell tool 结果保持一致 | Query 合并、`ProotLaunchSpec` | query/PTY 竞态测试 + arm64/x86_64 terminal 与 tool 对照 |
| 启动、迁移与备份恢复 | 合法历史库可启动；恢复失败明确阻断；旧备份仍可导入；已清理错误状态不再扫描 | legacy repair 删除、effective settings | Room 1→8 instrumentation + 0.0.18 实库/备份样本 + kill/restart |

每个 Phase 开始前先冻结其影响行的当前自动化结果和真机操作记录，结束时立即重跑；不能把所有 UI 验收推迟到 Phase F。
只要任一未被本节明确授权的行为发生变化，该 Phase 就失败并在当前新架构内修复。禁止用 feature flag、旧 facade 或双路径保功能；
阶段回退依靠可独立 revert 的 Git 提交，而不是把兼容旁路留在生产代码。

UI 结构同样执行减法：不删除或重命名现有页面、路由和用户 action；只删除 `ChatMessageTools` 的私有工具阶段 resolver，
改为消费 `ConversationPresentation`。Provider、Assistant、MCP、TTS、ASR、Search 等现有设置页继续使用原页面和编辑流程，
仅把输入替换为 `EffectiveSettingsSnapshot` 并在原 UiModel 增加 source/lock 展示；不新建第二套“受管设置页”、受管专用
ViewModel 或 UI 侧配置合并器。因架构迁移需要调整 UI 时，允许的改动只有 projection 类型适配、锁定态显示和删除重复推断，
不得借机改变导航、控件含义、默认值、排序、错误反馈、动画或滚动行为。

## 12. 实施计划

每个阶段是一个可独立审查的架构切片。分支内可以短暂出现编译中间态，但完成提交不得包含双路径、deprecated 转发或
临时白名单。测试与文档在同一阶段随代码更新。

### Phase A：冻结契约与删除账本

目标：先证明要删的是什么，避免再次用代码形状替代语义。

- 记录 Conversation/Turn/Settings/MCP/Artifact/Workspace 的入口、owner、状态源和失败点；
- 搜索并分类全部 legacy/compat/fallback/migrate/backfill 分支，按 §10 判为“合法迁移”或“错误修复”；
- 为两项确定删除的错误状态记录 0.0.18 修复覆盖证据和当前 writer 不可再生成的证据；
- 复算并冻结 §11.7 的文件/行数，明确核对已记录的 +115 行行为增量，不得把它称为无关变更或上调比例门槛；
- 按 §11.8 逐行冻结自动化结果、真机步骤和现有错误反馈；
- 按 §6.3 将每个当前 Provider endpoint/profile 的 USER/ASSISTANT/TOOL_OUTPUT 图片能力登记为显式表；未由正式 schema
  和现有 adapter 共同证明的格子固定为非 native，不允许实施时再猜；
- 先补齐关键行为测试，不新增锁定旧类名的测试。

退出条件：§11.1～§11.5 的每个删除、重命名、合并、修改和保留项都有当前符号清单、目标 owner、失败语义和验收测试；
Room 1→8 保留项、新文件白名单、行数预算、Provider capability 表和 UI 保真矩阵全部冻结。缺少任一 UI 基线的后续
Phase 不得开始。

### Phase B：退休错误旧数据修复

目标：先移除已失效的历史旁支，缩小后续状态空间。

- 删除 `ReconcileOrphanedTurnExecution` command、transition/reducer、Runtime、Coordinator、Repository 和 Recovery 路径；
- 删除 `TurnRecoveryLegacyExecutionTest`，改以当前写事务不可产生孤儿 execution 的约束和故障测试覆盖；
- 删除 `ArtifactStore.adoptSettingsOwnedImages` 及其启动调用和旧状态 adoption 测试；
- 对再次出现的缺失 owner/missing metadata 走明确损坏诊断或恢复失败，不新增另一种补偿；
- 使用 0.0.18 真实数据库与 Settings/Artifact 样本证明两类错误状态已归零，不只使用新建 fixture；
- 保持 `Migration_1_2`～`Migration_7_8`、OCR/DataStore migration、旧备份读取完全不变。

退出条件：旧错误状态不再是生产 command；正常启动不扫描它们；所有历史 migration 测试通过；§11.8 的启动、
头像、背景、附件和恢复路径在本阶段完成设备复验；本阶段生产代码净减少。

### Phase C：配置 Aggregate 与受管完整快照

目标：把此前的“企业配置准备插槽”变成唯一、可用的配置协议，同时删掉占位层。

- 在 `SettingsStore` 内建立 Local/Managed/Built-in 的有效读模型和唯一 revision；
- 实现受管 envelope 的验签、图校验、generation 原子发布、LKG 与 generation 资源清理；
- 把所有业务消费者从 raw/local Flow 迁到 effective snapshot；写入口仍只改变 local shadow；
- 把 source/lock/generation 校验固化为 Store 内纯策略，删除 `AllowAll` 占位扩展点；保留唯一纯 `commitSettings` 顺序模板；
- 按 §11.2 完成 Settings 文件/类型重命名，不保留旧 typealias 或转发属性；
- 将 Search 当前选择从 index 一次性迁移为稳定 id，删除运行时双读和 index fallback；
- 普通备份只导入/导出 local Settings；恢复后重新应用当前 managed overlay；
- 不实现未定义的服务器 endpoint，只完成 transport adapter 能调用的核心原子 apply contract。

退出条件：本地提交失败不发布；受管坏包/旧 generation/缺依赖不发布；撤回必须由新签名 generation 驱动；所有消费者
只看到同一 effective revision；Settings 本地/有效读模型不超过 1,250 行，`ManagedConfiguration.kt` 单独报告；§11.8 的全部设置、Search、MCP 配置输入和 managed UI
路径完成自动化与设备复验。

### Phase D：Conversation 与 Turn 主链收敛

目标：删除重复命令解释和重复运行态，同时保留 Master 与 Delegation 各自必要的事件副作用 consumer。

- 以单一 transition 替代 Reducer + MutationBuilder，并逐个迁移所有 command；
- 将 durable command 串行权收进 Coordinator + `ConversationOperationLocks`，删除 Runtime command mutex 和分类；
- 将 active request/turn 临时状态收进 Runtime private `ActiveTurnRuntime`，移除 Job/turnId/cancel reason 多源表达；保留
  immutable `TurnHandle` 的 durable CAS 语义和独立 TTS session；
- 让 Registry 用 operation lock 安装并启动 Master/Child LAZY worker；send/ChatVM 改为 suspend receipt，Child turnId 在安装前
  分配；START/checkpoint/approval 增加 current active identity 校验，`UpdateToolApproval` 携带原 handle；
- 给 `SendMessageReceipt` 增加 `turnId`，让合并后的 presentation 暴露同一 active identity；删除发送失败后无终止条件的
  append-scroll 等待，不新增 send-result Flow/EventBus；
- 将 `GenerationHandler.generateText` 重命名为 `GenerationLoop.run(GenerationRequest)`，不新增 `TurnSpec`；
- 保留 `TurnEngine.bind` 以及 Master/Delegation 两个事件消费者，删除无用 checkpoint event 和重复异常/终态纯变换；
- 按 §6.3 以 Provider 请求级 capability 替换 model-only 图片判断；保持附件投影为 Master/Target 输入链末项，删除
  `CAPABILITY_HINT` 和 serializer 丢图 fallback，并完成 Responses raw replay 约束；
- 将三个 presentation 文件与 UI resolver 合入 `ConversationPresentation.kt`；
- 删除 Runtime→Registry→Query→ChatVM 的独立 `processingStatus` 读链，由 `ConversationPresentation.processingText` 取代；
- 把 run lease 私有化到 `SubAssistantRunGate`；明确保留 `TurnFinalization` 和 `TurnPipelineFactory`。

退出条件：每个 Conversation command 只有一个 planner；每个 active request 只有一个 `ActiveTurnRuntime`，每个已 START turn
只有一个 immutable handle 和 terminal commit；Master/Delegation 的
不同事件副作用仍完整；receipt 的每种结果都能结束 UI 等待，native marker 与实际 wire payload 一致；Conversation/Turn
核心不超过 5,800 行，附件请求/wire 切片不超过 9 文件/4,362 行；§11.8 从 New Chat、附件到 TTS 的全部聊天路径在本阶段完成
自动化与设备复验；本阶段生产代码净减少。

### Phase E：MCP、文件域与 Workspace 收敛

目标：删除平行运行态和重复机械协议，不跨领域合并 owner。

- 将 MCP 状态合并为 per-server `ConnectionSlot`，所有触发源统一 reconcile；
- 验证配置 revision 变化、重连、OAuth、网络切换和 stale job，再删除并行 map 与旧重连分支；
- 合并 `McpConnectionKey`，把 `syncAll` 重命名为 `refreshConnections`；
- 删除 Artifact adoption，保持唯一引用策略；Skill 单目录操作合入 `mutateSkillTree`，不建立跨领域文件事务；
- 删除 terminal query 文件并移入 `WorkspaceQueryService`；新增唯一 `ProotLaunchSpec` 装配；
- 保持 Artifact、Skill、Backup、Workspace、Terminal 各自状态机和补偿边界。

退出条件：MCP 每个 server 只有一个状态容器；文件 owner 无旁路；Workspace 读端减少且 PTY 所有权不变；MCP、
Artifact/Skill、Workspace 分别不超过 §11.7 上限；对应设置页、Picker、图片生命周期和 Terminal 路径均完成设备复验；
本阶段生产代码净减少。

### Phase F：边界、测试与文档封板

目标：移除为了旧结构存在的测试和命名，并用运行行为证明完成。

- 删除 `UpstreamBatch13BoundaryTest`、`SingleWriterContractTest` 等测试中对内部类名、函数文本或文件布局的具体断言；
- 保留或重写这些测试中真正约束单写、禁止越层和取消传播的部分；只有文件不再承载有效契约时才删除整个测试文件；
- 保留无法由 Kotlin 可见性/模块依赖表达的通用禁止导入检查；其余改为状态机、事务和故障测试；
- 清理无调用协议、旧计划术语、compat/fallback 白名单和失真命名；
- 更新所有受影响的 `docs/references/`、上游同步总账和开发记录；
- 汇总 before/after 删除账本、核心/全仓行数、新增文件白名单、UI 矩阵、性能、migration、设备和构建证据。

退出条件：代码、测试和参考文档只描述 V2 单一路径；本阶段生产 Kotlin 相对 Phase E 不增加；核心最多 56 文件/13,370 行，
附件请求/wire 切片最多 9 文件/4,362 行，全仓生产 Kotlin 最多 124,713 行；§11.1～§11.8 无未决项，UI 矩阵全部通过，
不存在以“之后再删”为条件的残留。

## 13. 验证方案

### 13.1 Conversation、Turn 与并发

- Draft 首消息事务失败不入库、不发布、不丢 Draft；重试只创建一次；
- resident/non-resident 同命令得到相同 change 与错误；100 并发 header/tree command 无丢更新；
- non-resident pin/title/移动助手只查询 header，不读取 message nodes；其结果与 resident 路径逐字段一致，并保留查询次数与
  长会话延迟基线；
- checkpoint 失败不 publish Artifact，成功后取消不回滚已提交事实；
- supersede 后旧 Generation/Transformer 的 processing late report 不改变新 request presentation；active release 后文字为 null；
- START 与 CONTINUE_APPROVAL 不混用，继续审批复用原 handle，不创建第二 turn；
- cancel×chunk、supersede×旧 worker、install×START/checkpoint/approval、terminal commit×late cancel、load×submit 全部拒绝
  stale 非终态写，同时允许 superseded handle 仅做一次幂等 terminal CAS；
- `AWAITING_APPROVAL` 时旧 worker 已结束但 active owner/handle 保留；并发 approve/deny 只有一次 decision commit，继续 worker
  复用同 handle，supersede/stop 与 decision 的 operation-lock 顺序决定唯一结果；
- receipt 必须在 active runtime 安装且 worker start 后返回；append 成功、预处理失败、append 失败、用户取消、supersede、
  `answer=false` 都能由
  snapshot + presentation 得到唯一终止结论，失败路径不产生 durable message 或悬挂滚动任务；
- Master 与 Child generation 使用相同 Engine 骨架，只有 transformer/tool/interactivity spec 不同；
- 进程死亡恢复当前合法 RUNNING/STARTED 状态，损坏 owner 明确失败而不是自动补写。

### 13.2 请求投影与 Provider wire

- 表驱动覆盖来源 `USER/ASSISTANT/TOOL_OUTPUT` × mode `NATIVE/REFERENCE_ONLY/UNAVAILABLE`；断言 marker 紧邻原附件、
  role/Tool.output 归属和相对顺序不变、每图只有一个 marker、durable 输入对象不变；
- 分别覆盖模型无 IMAGE、endpoint 不支持容器、存在/缺失稳定 ref、native 编码成功/失败；marker 为 native 时反序列化最终
  request 必须找到对应图片，reference/unavailable 时最终 request 不得含该图片；
- 覆盖 OpenAI 官方 Chat、每个已命名 compatible profile、Responses 各 `ResponseEndpointProfile`、Claude、Gemini 2.x/3.x
  的实际 serializer fixture；未知 compatible endpoint 必须走保守能力，不靠模型名猜方言；
- Master 与 Target 都验证 `limitContext` 在前、附件投影最后；Master 单独验证 tool artifact replay 在附件投影之前，Target
  验证不装配 replay；
- Responses raw/rebuilt 两条路径分别验证 source profile 匹配、opaque item group/call 顺序、marker 单次追加以及无可见文本重复；
- 断言源码和最终请求均不存在 `CAPABILITY_HINT`、末条消息全局 hint、`Image output omitted` 和编码失败空文本 fallback；
- 视觉模型与文本模型各做 USER 上传、工具产图、助手原生产图的真机/受控端点回归；未进行真实端点调用不能只凭 JSON
  fixture 声称 Provider 兼容已验收。

### 13.3 Settings 与受管配置

- local、backup、market 写入在最新 managed lock 下重验；UI disabled 不是授权依据；
- LOCAL/market 命中 lock 时整次失败且 shadow 不变；restore 可以替换 shadow，但 effective revision 仍保持 managed 值；
- local shadow 被 managed 同 id 覆盖但不丢失，签名撤回后按协议恢复；
- 签名失败、未授权自带公钥、tenant 不符、generation 重放/倒退、重复 id、悬空引用、缺 asset 均保持 LKG；
- 启动时 active envelope 损坏且无可用 LKG 时进入 `BLOCKED`，AI/MCP 不启动；不存在受管包时为 `ABSENT` 且本地行为不变；
- 写 assets 后、envelope rename 前杀进程不会发布半包；重启能清理未引用 generation；
- expired 快照不自动解除 lock；明确撤回 generation 才改变有效图；
- Search index→id 一次迁移后旧 key 删除，排序/删除/managed overlay 不会改变选择身份；
- MCP 只消费完整 effective revision，不观察半新半旧配置。

### 13.4 数据与文件

- Room 1→2→…→8 全链、各关键旧版本→8 和 fresh 8 schema 同构；历史数据保全、外键检查为空；
- 若新增 schema 9，再增加 8→9 并验证 1→9，不得删旧 migration；
- OCR/DataStore 和旧备份 JSON 的合法迁移保持通过；
- Artifact create/publish/delete/GC、Settings replace、进程死亡和并发删除仍覆盖失败路径；
- 受管配置不进入普通备份，local restore 后有效 overlay 重新正确计算；
- Skill/Backup/Workspace 的 zip/path/staging 测试覆盖 traversal、超限、取消和中断清理。

### 13.5 MCP、Workspace 与 UI

- 同 server 并发 sync/call/remove/auth 只作用于一个 slot；stale job 不能覆盖新 fingerprint；
- 网络离线/恢复、前后台切换和 retry exhausted 都经同一 reconcile 收敛；
- shell tool 与 terminal 对相同 Workspace 生成相同 PRoot binary/binds/cwd/env；
- terminal tab create/close/reorder 与 Workspace delete 竞态不泄漏 PTY；
- Flow 经 lifecycle-aware collect 进入 Compose；助手切换、生成、标题、工具审批和 terminal 状态不会冻结；
- Compose/instrumentation 覆盖发送后键盘收起、目标 item 延迟进入 layout、分支切换、新发送、失败、取消和 supersede；每个
  场景都断言滚动结果与 append-scroll job 已终止；
- 真机验证后台/杀进程恢复、裁剪/Artifact、MCP 网络切换、Workspace terminal 和数据库 migration。

### 13.6 工程门禁

按仓库规则串行执行与风险匹配的定向测试，并在最终阶段执行：

```text
gradlew test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
gradlew connectedDebugAndroidTest --no-parallel --max-workers=1
git diff --check
```

connected device、真实 migration、Compose instrumentation 和系统集成结果必须单独记录；JVM/构建成功不能表述为设备验收。

## 14. 完成定义

V2 只有同时满足以下条件才算完成：

1. §3.2 的不变量由类型、可见性、事务或行为测试保证；
2. §11.1 的删除、§11.2 的重命名、§11.3 的合并和 §11.4 的修改全部完成，无“评估后再决定”的项目；
3. Conversation command 只在一个 Transition 中解释，durable commit 和 resident publish 只有一条链；
4. Master/Child generation 共用 TurnEngine durability 骨架，Master/Delegation 保留各自事件副作用，每个 turn 只有一个
   handle 和终态协议；
5. receipt 的成功/失败/取消/supersede 都有终止投影，发送定位无无限等待；附件来源不变，native marker 与实际 wire
   media 一致，所有不支持容器在原来源明确降级；
6. §11.8 中除 managed UI 和已明确列出的附件 wire 降级修正外，界面、反馈、时序和错误行为零变化，每个 Phase 都有
   当期设备证据；
7. 本地、内置、受管配置只生成一个有效读模型，服务端整包原子发布且失败保留 LKG；
8. MCP 每个 server 只有一个 ConnectionSlot，网络/前台/配置变化统一 reconcile；
9. 两项已确认的错误旧数据修复及其测试、文档和 command 全链物理删除；
10. Room 1→8 全部 migration、合法 Settings/备份迁移和外部协议兼容保持有效；
11. 无 deprecated facade、双读、双写、fallback 白名单、无调用协议或只转发的新增 owner；
12. 新增生产文件仅限 §11.6 四个；核心最多 56 文件/13,370 行，附件请求/wire 切片最多 9 文件/4,362 行，全仓生产
    Kotlin 最多 124,713 行；
13. 全量门禁、migration、故障、性能和真机证据齐全，参考文档与代码一致。

V2 的最终形态不是“更多层但更规范”，而是每项事实只在一个位置被决定、每项失败只沿一条路径收口、合法历史数据
通过明确迁移进入当前结构，已经完成使命的错误修复彻底退出生产代码。
