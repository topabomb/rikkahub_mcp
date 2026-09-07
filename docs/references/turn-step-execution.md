# Turn / Step 执行链路

本文说明会话命令、Turn / Step 状态、checkpoint 与中断收口。总体依赖边界见
[应用架构](application-architecture.md)，请求窗口与压缩见 [请求上下文](request-context.md)，
模型文案见 [提示词与工具](prompts-and-tools.md)，文件交接见
[多模态与持久化](multimodal-context-and-turn-durability.md)。

## 职责与主链

| Owner | 职责 |
| --- | --- |
| `ConversationApplicationService` / `ConversationQueryService` | 会话结构命令与 UI 只读投影 |
| `ConversationTurnService` | 用户发送、重生成、编辑后发送与交互继续 |
| `ConversationCommandCoordinator` | 按 conversationId 串行化、校验命令、Room transaction、commit-then-publish |
| `ConversationTransition` | header、tree、variant 结构变换 |
| `TurnTransition` | Turn / Step / Tool transcript 变换与 execution facts |
| `ConversationRuntime` | durable snapshot、纯内存 streaming projection 与私有 `ActiveTurnSession` |
| `ConversationRuntimeRegistry` | Loading / Draft / Ready / Missing / Failed 生命周期、引用与 worker |
| `TurnContextFactory` | START 前捕获 `TurnLaunchPlan`，START 后绑定冻结 `TurnContext` |
| `TurnRunner` / `StepRunner` / `ToolBatchRunner` | 多 Step 循环、单次采样、工具批次门禁与串行执行 |
| `TurnCommitter` | START、continue、checkpoint、stream 与终态提交适配 |
| `TurnFinalizer` / `TurnRecovery` | 正常停止、失败和 supersede 收口 / 进程重启恢复 |

```text
ConversationTurnService / SubAssistantRunCoordinator
  → TurnContextFactory
  → TurnCommitter.start → StartTurn
  → TurnRunner
      → StepRunner → RequestAssembler → Provider
      → ToolBatchRunner → ToolCallRuntime
      → TurnCommitter → ConversationCommandCoordinator
                         → ConversationTransition / TurnTransition
                         → ConversationRepository Room transaction
                         → publish committed snapshot
```

结构与执行 reducer 共用一个命令入口和事务。未变化节点保持引用；UI/ViewModel 只消费
`ConversationPresentationSnapshot`，不持有 Repository、Runtime Registry 或 worker Job。
`ConversationAggregateSnapshot` 保存 header、nodes 与 model-context entries；streaming 不进入它。

## 会话文件夹命令

`ConversationQueryService.foldersOfAssistant` 发布 `ConversationFolderDirectory`，即使目录为空也保留原助手及
`RealmSelection`。抽屉在打开创建、重命名、删除或移动界面时固定此授权；确认不重新读取全局选中域。
分页行携带自身原选择，移动时必须与目标目录一致。工具使用的只读会话摘要不带当前 UI 选择，不能据此执行文件夹移动。

文件夹写入沿 `ConversationApplicationService` → 原 Session/选择锁 → `ConversationCommandCoordinator.withRootHeaders`
的既有会话锁执行。先检查完整 scope、根会话和助手，之后才提交原 `UpdateHeader`，不加载非驻留消息树。
创建通过配置 resolver 验证助手当前可用，并显式把域传给 `FolderRepository`；整理历史文件夹不要求助手仍可执行。

删除取得完整成员锁集合，在任何 detach 前复查成员与活动 turn；任一成员仍在运行则拒绝整个操作。
每次 detach 仍是原会话命令事务。某次提交失败时保留文件夹，重试只处理剩余成员；全部清空后才删除 metadata。
UI 等待命令结果后关闭对话框，错误可见，取消继续传播。

## 普通会话操作授权

页面通过 `ConversationViewLease.commandTarget` 取得原会话、RealmSelection 与页面生命周期检查；目录行通过
自身 `ConversationSummary.commandTarget` 保留原选择。工具的只读摘要不能签发 UI 命令。手工标题、系统提示词、
注入、Workspace 路径、移动助手、置顶、消息编辑/变体选择、停止、删除与克隆都接受该目标，不在执行时重取当前域。
原 Session/选择在最外层验证，最终会话锁内再次检查页面仍打开和 header 的完整主体/根会话身份，之后才读消息树或写入。
企业助手的固定系统提示词不能由会话覆盖；移动目标由当前主体的配置 resolver 验证，失效助手不回退为全局当前助手。

`TurnFinalizer.captureStop` 捕获具体 Runtime、turnId 与 worker，立即请求停止；等待 worker 和终态提交由
`finishStop` 在 Session/会话锁外接手，即使调用者取消也完成已经取得的清理责任。终态提交前在会话锁内验证原 worker；
同一 turnId 的替代 worker 不能被迟到停止影响。producer finally 与 Job completion 共用 releaseTurnWorker：
同 turn stream 未关闭就保留原 owner，不以显示 phase 或 Job 已结束判断提交成功，Child 也不能强制提前释放。OS 前台超时由专用 application
命令捕获当时的工作任务，UI 不使用该全局停止入口。

删除、撤销删除、消息删除和克隆在停止结束后重新验证原选择，再取得完整父子锁集合，检查 lineage 未变、全部主体一致且无活动 turn。
删除的数据库提交与 Runtime 驱逐在同一个已取得所有权的不可取消段完成。克隆只读取授权时捕获的父子快照，创建新树前再次验证原页面。
`RestoreToken` 保留原选择和完整父子树；只可领取一次，页面关闭不影响尚有效选择内的撤销，切域/重新接入后则拒绝。
恢复期间 discard 不能提前释放其 Artifact retention，成功或失败均由领取者释放；已有同 ID 会话导致冲突，不覆盖新对象。
历史批量删除只处理用户打开确认框时看到的那组会话，晚到的新会话不被纳入。

START/交互继续、模型生成标题/压缩和文件授权仍有独立执行链，不能以本节的普通操作检查代替其域准入。

## Turn、Step 与工具事实

一个 Assistant message variant 对应一个 Turn。发送、regenerate、编辑 USER 后重发与 Child task
创建新 Turn；批准、拒绝和回答继续原 `TurnHandle` 与 Step。一个 Step 包含一次逻辑模型采样及其完整
Tool batch，Provider 透明重试不创建新 Step。Step 保存在消息 parts，不设独立 Step 表。

`StartTurn` 在构造命令时固定首个 Step 身份和时间，单事务建立 Assistant 槽、`Step(ordinal=0)`、
RUNNING turn fact 与可选 model-context entry。`StepOutputAccumulator` 只更新已预开的未采样 Step。
`StepModelResult` 保存采样的 finish reason、usage、请求数、时间与 Provider metadata；计量算法见
[Token 用量](token-usage-accounting.md)。

Step ordinal 从 0 连续递增；最多一个 open Step，且只能在尾部。全部工具有 Result 后，最后一个结果
checkpoint 同时关闭当前 Step 为 `Continue` 并预开下一 Step。无工具最终回答由 `FinalizeTurn` 一次关闭
Step 为 `Final` 和 Turn 为 COMPLETED。失败、取消、incomplete 或恢复关闭尾部 Step；`AWAITING_USER`
是暂停。Step 上限按持久化 ordinal 判断，交互继续不会重置上限。

| 事实 | 身份与状态 |
| --- | --- |
| Tool Call | `ToolCallLocator(assistantMessageId, stepId, localCallId)`；Provider wire ID 独立保存 |
| Tool Interaction | `NotRequired`、`AwaitingApproval`、`AwaitingInput`、`Approved`、`Denied`、`Answered` |
| Tool Execution | 副作用前才提交 STARTED 行；终态为 COMPLETED / FAILED / CANCELLED / UNKNOWN |
| Tool Result | `resultStatus` 决定结果存在，空 output 也可以是合法结果 |

Tool 的 `stepId` 指向前方最近的 Step，`localCallId` 在 owning Assistant 内唯一；ordinal 仅作顺序。
流式增量通过 `UIMessageChoice.toolCallSlots` 的响应内身份拼接，本地调用首次出现时分配随机 localCallId。
槽不持久化，重复或空 wire ID 不会合并不同调用。协议编码见 [AI 协议](protocol-reference.md)。

提交拒绝改写既有 Step 身份、开始时间、已提交 modelResult 与闭合历史；闭合 Tool output 只能经 typed
压缩 patch 修改。Step 不进入 Provider、FTS、摘要或 UI 内容分组。`RequestAssembler` 移除 Step 时同步
调整 replay-safe prefix 计数；不同 Step 的连续工具仍按 `Tool.stepId` 分批回放。

## START 与交互继续

新聊天先是非持久化 Draft，首条 `AppendUserMessage` 单事务创建会话并原位晋升 Ready。
页面打开通过 `ConversationApplicationService.initialize` 校验原 selected RealmAccess，再由 `openForView` 在会话锁内先检查
resident/Room header 的完整 scope 与根会话身份，之后才加载消息树或安装显式 Draft。已有会话不存在时返回 Missing，禁止回退新建。
已晋升聊天保留原 Runtime 与导航项；恢复的 `NewDraft` 请求若已有持久 header，就读取已提交内容，不重放助手 preset。
页面 Query 只接受成功打开的 `ConversationViewLease`，每次发布复验原 Session；关闭的 lease 永不重新激活。
最近聊天偏好只在观察到持久 Ready 并复查 Room header 后保存，空 Draft 不写最近聊天 ID。
空 Draft 不进入数据库、列表或 Turn。发送先结束旧 owner、预处理输入和稳定附件，再提交 USER。
START 前准备失败可以留下已提交 USER，但不能伪造已经开始的 Assistant Turn。

同一 START 的用户预处理与模型解析使用同一份 `EffectiveSettingsSnapshot`。START 冻结
Assistant / Model / Provider wire shape、媒体能力、prompt inputs、有序 `FrozenToolDefinition` 与 execution bindings。
同一 Turn 的 Step 和审批继续不重读模型可见配置。凭据由 `ProviderTransportLease` 每请求从
`ProviderCredentialOwnerLocator` 指定的原 owner 获取；owner/type 漂移失败关闭，不重新选择 live provider。

`sendMessage` 返回的 `SendMessageReceipt.userMessageId` 是本次 USER 的稳定身份，返回只证明 worker
已安装，不证明 USER 已提交。UI 通过正式消息投影观察该 ID。`editAndResend` 截断到目标 USER node、
提交新 variant 后 START；纯 `editMessage` 不启动 Turn，也不创建 model-context entry。

`TurnEntry.START` 可执行建议清理、无效消息清理和附件引用回填；`CONTINUE_USER_INTERACTION`
保留原 owner，不执行这些结构预检。`ResolveToolInteraction` 校验完整 locator 和等待类型，事务提交后才
发布决定。owning Assistant 还有 Pending 就继续等待；全部解决后 `TurnCommitter.continueActive()`
复用原 TurnContext、TurnHandle、Step 和累计 usage。

## 工具批次与 checkpoint

`TurnToolSetFactory` 在 START 物化工具；可用性、审批与执行使用同一冻结索引。
`Tool.parseArguments` 严格解析 JSON object，并在审批前调用工具自身的纯 `validateArguments`；空参数缓冲
按 `{}` 解释。坏 JSON、错误类型、不可用工具和参数失败不执行，也不创建 execution 行。执行 owner 仍负责
文件、权限、配置与远端状态复核。工具描述与领域信封见 [提示词与工具](prompts-and-tools.md)。

Pending 是整批屏障，任何自动工具都不抢先执行。合法 Denied / Answered 直接成为 Result，Approved 不重复
审批。无效 Pending 清除等待态并产生 FAILED；同批仍有合法 Pending 时，两者合入一次
`ModelResponseCheckpoint(AWAITING_USER)`。首次采样的即时失败同样随模型 checkpoint 提交；续跑且无 Pending
时，即时失败通过 `ToolResultCheckpoint` 提交。新 Pending 不进入 RUNNING snapshot 或流式投影。

| 命令 | durable 边界 |
| --- | --- |
| `ModelResponseCheckpoint` | 采样结果、Tool Calls、即时失败、Pending、压缩 patch 与 RUNNING / AWAITING_USER |
| `ToolExecutionStartedCheckpoint` | 副作用前 STARTED 与 Turn / Step / Call 身份；成功返回后才执行 |
| `ToolExecutionUpdatedCheckpoint` | Child link 或必须持久化的中间事实 |
| `ToolResultCheckpoint` | Result、execution 终态、metadata、Artifact roots；最后结果可关闭/预开 Step |
| `FinalizeTurn` | 最新 owning Assistant、Step / Turn 终态及执行收口；无工具最终采样也在此提交 |
| `RecoverInterruptedTurn` | 进程恢复的 owning Assistant 与终态事实 |

Room transaction 同时覆盖消息 delta、turn/tool execution、Artifact 引用与 FTS。提交失败向上传播，
Runtime 不发布。旧 epoch 或不匹配 Turn / Assistant 的 streaming delta 返回 `STALE_TURN`。
Turn 状态使用 insert-once 与合法 CAS；终态不可回退，重复同终态幂等。

`ToolMetadataDelivery.STREAMING` 只更新进度；`CHECKPOINT` 持久化后展示；`DEFERRED` 合入草稿并随接下来
的 checkpoint 提交。生图和子助手最终 metadata 与结果同时落定，初始 Child metadata 与完整 child link
同时落定，不让未提交文件引用进入取消终态。

## 草稿、输出与资源交接

`TurnRunState` 持有当前 Assistant 草稿、累加器与未发布资源作用域。每个 chunk 只合并 owning Assistant，
历史结构共享，不遍历历史 nodes 或重跑历史 Input Transformer。`TurnRunInputs.onAssistantObserved` 在
可取消转换/投影交付前同步更新 `TurnCommitter` 的唯一 Assistant 槽，checkpoint 成功后以已提交消息覆盖。
失败或取消使用该槽，保留最后一个 checkpoint 后已经观察到的内容和 usage，不依赖 UI 交付成功。

`TurnPipelineFactory` 是主/子输入输出管线唯一装配 owner；具体顺序见
[提示词与工具](prompts-and-tools.md)。输出正则、think 标签与媒体落盘只处理当前 open Step。
模型采样提交后，工具阶段发布已转换正文与工具 metadata，不再次套用流式正文变换。

`ThinkTagTransformer` 只解释当前 Step 首个非空 Text 开头的 `<think>`；当前 Step 有 Provider 原生 Reasoning
时不启用 fallback。派生 Reasoning 从 `Step.startedAt` 计时，闭合标签首次到达时固定 finishedAt，后续投影
复用它；流结束只补未闭合标签的时间。阶段判定使用相同 raw message 语义。

拥有新资源的 checkpoint 先检查取消，再在不可取消交接段内提交 durable root、更新 Assistant 槽、发布 lease。
无工具 Final Step 的资源随 `FinalizeTurn` 落根。提交前取消或失败回滚未发布资源；提交已成功而 lease 发布
失败时保留 durable 消息，不退回旧草稿。失败准备把未落盘 base64 转为 typed 失败占位。文件 owner、引用与
删除协议见 [多模态与持久化](multimodal-context-and-turn-durability.md)。

## 终态与恢复

`TurnOutcome` 仅含 Completed / Failed / Cancelled / Incomplete；`TurnPause` 携带非空 pending locator 列表，
其 AWAITING_USER checkpoint 已由 Runner 提交。取消传播到调用者，`NonCancellable` 只用于已有所有权的终态
提交或补偿。`TurnOutcome.fromFailure` 共用 Provider 失败分类，并将稳定 reason 与脱敏 detail 随 owning
Assistant 落盘；达到 Step 上限为 Incomplete。

终态事务把残留 STARTED execution 收为 UNKNOWN，再 CAS Turn 终态。未知副作用不自动重试；未执行调用
使用 INTERRUPTED Result，保留真实 interaction，不伪造用户拒绝。子助手停止必须先完成 Child 终态，才可提交
父工具恢复结果。正常停止/supersede 归 `TurnFinalizer`，进程恢复归 `TurnRecovery`。

`ApplicationRecoveryCoordinator` 是启动恢复唯一入口，完整顺序和全局写门禁见
[应用架构](application-architecture.md)。TurnRecovery 从非终态 execution 定点加载 validated aggregate，
先 Child 后 Master；无待恢复 Turn 时不加载会话树。缺失 owning Assistant、损坏 transcript 或非法
model-context owner/anchor 使恢复失败关闭，不能以空会话或只改 execution 状态掩盖。

Header command 不清除 active owner；冲突树命令必须结束或拒绝当前 owner。Registry 在无页面引用且无活跃
Job 后可清理 Runtime。前台服务只通过 query port 观察活动，保活、通知与 UI 阶段不成为第二执行 owner，
具体投影见 [UI 架构](ui-architecture.md)。

## 标题与子助手

`ConversationTitleCoordinator` 拥有标题阶段、去重和有限重试。首条 USER 的确定性本地标题随
`AppendUserMessage` 提交。模型标题使用 generation token + expected-title CAS，手动标题与模型提交共用
Coordinator mutex；手动提交失效旧 token，因此 force 或迟到模型结果也不能覆盖它。重启后非空标题按
RESOLVED 保护，不猜历史 provenance。

Child 与用户会话共用 TurnRunner / StepRunner / TurnCommitter。父 `assistant_call` 等待 Child 终态；
Child ask_user 暂停原 Child Turn / Step，父 Turn 保持 RUNNING、execution 保持 STARTED。决定写入 Child，
不会写成父 Tool Interaction。完整 lineage、retention 与结果协议见
[子助手架构](sub-assistant-architecture.md)，父子用量隔离见 [Token 用量](token-usage-accounting.md)。
