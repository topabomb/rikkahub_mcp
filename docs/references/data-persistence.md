# 数据持久化、隔离与恢复

本文集中说明 Room 数据结构、按域存储与运行记忆、历史迁移和个人备份恢复。Settings 文档及企业 Session 的持久化由 [配置架构](android-configuration-architecture.md)维护；文件生命周期由 [多模态资源](multimodal-context-and-turn-durability.md)维护。

`AppDatabase` 是业务 Room 数据库；`APP_DATABASE_VERSION`、实体注解、导出 schema 与显式 migration 必须一致。索引只服务既有 DAO 查询，不改变 durable owner 或写协议。下文括号内字段按索引顺序排列。

## 查询覆盖

| 表 | 索引与用途 |
| --- | --- |
| `ConversationEntity` | `(scope, assistant_id, parent_conversation_id, is_pinned, update_at)` 支持域内助手列表与最近会话；`(scope, assistant_id, parent_conversation_id, folder_id, is_pinned, update_at)` 支持域内未归档分页；`(scope, folder_id, parent_conversation_id, is_pinned, update_at)` 支持域内文件夹分页；`(scope, parent_conversation_id, is_pinned, update_at)` 支持域内置顶、根会话与统计入口；`(parent_conversation_id)` 单独覆盖 Child 查询和自引用外键级联 |
| `message_node` | `(conversation_id, node_index)` 按会话读取有序消息节点，同时覆盖会话外键 |
| `conversation_model_context` | stable `id` 主键用于正文点查；`(owner_node_id, owner_message_id, occurrence)` 唯一索引支持同 variant 多条，`(anchor_node_id)` 支持按因果锚点查询和清理。按会话经 owner node JOIN，不重复保存 conversation/realm；正文由轻量 header 与字符分片查询组装 |
| `conversation_context_admission` | stable `id` 主键；`(owner_node_id, owner_message_id, step_id)` 唯一，请求零新增也保存边界。来源选择是轻量版本化 payload，窗口起点保留真实 node/message locator |
| `conversation_context_use` | `(admission_id, ordinal)` 主键保存本边界贡献顺序，`(entry_id)` 索引支持正文引用。entry 外键为 NO ACTION，必须先调整关联再删除正文；`Omitted` 明确关闭继承贡献，不产生模型正文 |
| `conversation_opening` | `conversation_id` 主键/外键，同一会话最多一份；删除消息节点不删 opening，整会话删除级联。完整发布定义为冷 payload，通过 header 与字符分片按需读取 |
| `MemoryEntity` | `(scope, assistant_id)` 直接定位企业用户范围与 owner，列表按主键 `id ASC` |
| `GenMediaEntity` | `(path)` 支持文件名查重；`(create_at)` 支持全库恢复读取；`(scope, create_at)` 支持域内图库分页、观察与清理候选 |
| `artifact` | 保留 `relative_path` 唯一索引；`(folder, created_at)` 支持跨域生命周期恢复；`(state, created_at)` 支持状态候选；`(scope, folder, created_at)` 支持域内目录列表与清理；`(scope, created_at)` 支持整域读取与清理。目录查询的状态条件可作为剩余过滤，不破坏时间顺序 |
| `artifact_reference` | 保留 `(artifact_id, node_id, reference_type)` 唯一索引与 `(node_id)`；`CONTEXT` 与附件/工具归档共用此表，按来源类别精确替换，node 删除沿原外键级联清理。唯一索引的左前缀同时覆盖引用检查与外键，不另存同列普通索引 |
| `conversation_folder` | `(scope, assistant_id, sort_index, create_at)` 支持域内助手文件夹排序 |
| `favorites` | 保留 `ref_key` 唯一索引与 `(created_at)`；`(scope, type, created_at)` 支持域内分类后的时间排序 |
| `turn_execution` | 保留 `(conversation_id)` 与 `(status)`，分别支持归属查询、非终态恢复 |
| `tool_execution` | `(turn_id)` 支持归属查询；唯一 `(turn_id, local_call_id)` 约束调用身份；`(child_conversation_id)` 保留 Child 关系查询。恢复按 `turn_id` 读取执行事实并验证 Child Turn/run，不为无独立查询的 `child_turn_id`、`sub_assistant_run_id` 建索引；无全局 status 查询，不建 status 索引 |
| `workspaces` | 保留 `root` 唯一索引与 `(updated_at)`，支持路径唯一性、SAF 的 `getByRoot` 注册查找及列表排序；主键用于 Workspace 点查，SAF 不新增表或索引 |
| `system_meta` | 现有主键满足 key 点查，无额外业务筛选索引 |

复合索引优先让等值筛选字段位于排序字段前。助手全列表与未归档列表分别建索引：后者需要 `folder_id` 参与范围定位，前者不能被中间的 `folder_id` 打断排序。全部排序方向一致时 SQLite 可反向扫描，无需另外建立 DESC 镜像索引。

`ConversationRepository.getContextToolHistory` 经 `ToolExecutionDAO.getConversationOutcomes` 一次
`turn_execution LEFT JOIN tool_execution` 同时读取已记录的 Assistant owner 与工具执行终态。
没有 Tool 行的已记录 Turn 仍出现在结果中，以区分审批前拒绝与 Fork 仅复制 transcript、没有本地执行记录的历史。
该查询沿现有 conversation/turn 索引，不逐 entry 查询、不复制 execution 行，也不新增持久状态。

索引不是按字段数量补齐：全量导出、低频恢复的小结果集排序不额外增加写入负担；包含前置通配符的文本搜索、JSON 展开聚合不能靠普通 B-tree 索引消除扫描。会话全文搜索继续由既有 FTS 投影负责，不新增第二搜索表或维护协议。

## 运行记忆的存储与授权

运行记忆是用户数据，与企业 Memory Seed 配置分开。MemoryRepository 是唯一写 owner；MemoryService 编排恢复、Session、配置授权及只读 UI 投影，UI/ViewModel 不直接调用 Repository 或 DAO。

### 持久归属

`MemoryAddress` 包含不可变 `ConfigurationScope` 和 `MemoryOwner`。`RealmShared` 使用既有 `__global__` 存储值，含义是该域主体内共享；Assistant 使用稳定 `ConfigurationReference`。个人与企业、不同部署以及同部署的不同用户不能共享运行记忆行。

MemoryDAO 的列表、读取、更新、删除均含 scope 和 owner，单行修改还要求 id；列表按 id 升序。工具结果定位仅允许在明确 scope 内按 id 查到 canonical owner，再以完整地址写入，没有跨域裸 ID 删除。旧个人行保留原归属；`MemoryEntity` 的 `(scope, assistant_id)` 联合索引按主体和 namespace 缩小查询范围。

删除用户助手的记忆清理明确限定 Personal。企业记录保留，不因删除共享助手定义而按 assistant ID 跨域清空。

### 授权与提交

RealmAccess 捕获完整 scope 与企业 Session ID，不持久化、不自行决定权限。ConfigurationQueryService 在恢复门禁之后提供通用主体捕获与复验；不由 MemoryService 代理整个 Turn 的入域授权。EnterpriseSessionController 每次使用核对主体、Session、阶段和期限；退出后同主体重新登录不恢复旧 access。切换个人空间不重定向已捕获企业操作，OFFLINE 不阻止本地记忆访问。身份已验证但配置待就绪时 Session 的数据访问资格仍存在；新助手记忆操作另需当前配置可解析且准入通过。

MemoryAccess 再捕获助手、共享/局部模式和工具是否要求 enableMemory。MemoryService 写入前持有 Session → Settings → Room 的固定锁序，重新解析该域最新配置并验证原地址。打开编辑框后切换共享模式不会把保存转向新的 namespace；旧操作被拒绝。

MemoryRepository 在原 caller 仍有效时进入事务，并在提交决定前再次检查取消。取得事务所有权后以 NonCancellable 等待 Room 完整提交或回滚，不能提前释放上层授权锁；结束后继续传播取消。不是把一次 DAO 返回当作事务已经提交。

### 查询与调用

MemoryService 的原页面订阅接收 RealmAccess，延迟订阅也不按 scope 重新捕获 Session；共享定义入口仍在订阅时捕获其明确范围。MemoryView 保存授权上下文和记录；编辑/删除使用记录的原上下文。订阅在 Session 退出、主体变化或期限届满时取消数据观察并清空 rows，明确显示不可用。同一旧订阅不因重新登录恢复。配置模式/策略变化只替换当前数据观察，不因一次旧地址的查询失败永久结束外层观察。

目录投影区分访问失效与读取异常：前者关闭访问并提示重新打开，后者在 `MemoryView.diagnostic` 保留异常类型、原始 detail 与 cause，记录原异常栈，并由记忆页面提供可复制文本。两者都清空旧行且禁止编辑；重新打开重新订阅原 owner，取消继续传播，不显示为读取失败。

Master 的 START 使用会话持久 scope 捕获 RealmAccess；Child 继承父调用的 access 并核对父子 scope，不重新取得新 Session。Disclosure 读取与 memory_tool 使用同一捕获的 MemoryAccess；工具每次写入复验。assistant_inspect 使用捕获域的配置核实 caller/target/主从授权，只披露目标的局部记忆；共享或关闭模式返回空 rows。

工具卡只提交原会话 ID 与 ToolCallLocator。MemoryService 核对持久 Assistant message 中的成功 memory_tool create/edit 结果，再定位对应域记录；点击删除时再次核实原结果未变。独立消息预览没有会话身份时不提供删除入口；工具卡来源改变时旧删除对象不再显示。

聊天中的当前空间助手设置使用 `MemoryService.observe(ConversationViewLease, assistantId)` 绑定打开时的会话目标，切域再返回也不能恢复旧编辑授权；不另设企业助手目录或第二套记忆页面。
`ConversationConfigurationUiModel.memorySeeds` 单独投影当前会话助手的公开 Seed，更新 Seed 不回写运行记忆；`AssistantUsageEditor` 在记忆子页分开显示只读 Seed 与实际助手专用/空间共享地址中的运行记忆。
共享助手定义页面的默认设置与当前空间的运行记忆分别标明范围。

## 版本迁移

当前 schema 版本由 `AppDatabase` 声明；迁移是显式、可验证的数据转换，不是运行时兼容分支。

### 索引与执行记录

`Migration_8_9` 仅创建普通索引并移除被复合索引或既有唯一索引覆盖的冗余普通索引。表、列、默认值、主键、外键、唯一约束和行值均不变；文件、GUID、附件 metadata、Settings 和备份 manifest 不改写。图库路径索引不是唯一索引，历史重复路径不会阻止升级。

`Migration_9_10` 只新增 `conversation_model_context` 及其 `anchor_node_id` 索引，不扫描或回填历史会话。

`Migration_10_11` 将旧 Assistant transcript 转成显式 Step 与稳定 Tool locator，新增 `transcript_schema = 3`，重建 `tool_execution`，并转换等待用户与未开始的 turn 状态。消息按 SQLite 字符切片读取，避免大 tool output 超过 CursorWindow；转换后验证 Step/Tool 身份、顺序和终态，仅 `Continue` 可接后续 Step，Tool output 不得嵌套 Step。已转换内容只验证、不重复改写。

### 数据范围与企业身份

`Migration_11_12` 给 Conversation、Memory、Artifact、生成媒体、会话文件夹和收藏六类根记录追加 `scope TEXT NOT NULL DEFAULT 'personal'`。旧行的 ID、payload、引用、索引和外键不变；消息、turn、tool 和 context 通过所属会话确定域，不重复保存。ConfigurationScopeConverter 使用当时的来源、部署与用户编码，非法编码不能回退个人域。

`Migration_12_13` 先验证上述六类根记录中的全部非 Personal scope，以及 Conversation、Memory 与文件夹中的全部 managed ConfigurationReference；任一旧值不规范即回滚。验证通过后，把企业 scope 从 `enterprise~sourceNamespace~deploymentId~userId` 一次性改写为 `enterprise~deploymentId~userId`，并把企业引用改写为 `managed~deploymentId~resourceId`。同一次迁移把高频域内读取索引改为以 `scope` 开头，避免地址变化后保留下来的多企业数据在列表、分页、记忆、文件、媒体、文件夹和收藏查询中互相扩大扫描；Child 外键、全库恢复、生命周期状态与唯一性查询继续保留各自不带 scope 的必要索引。迁移不改变 deploymentId、userId、资源 ID、主键、关系或内容；运行时转换器只接受新格式。

会话助手列表、最近聊天、置顶、未归类/文件夹分页、文件夹列表和统计均在 SQL 内过滤完整 scope。FTS 在排序与限额之前经所属会话过滤 scope 和主会话，包含全局搜索与助手内搜索；索引仍是既有 message_fts 投影，不复制域数据。ConversationQueryService 负责选中域订阅及原 Session 授权，SelectedRealmPagingSource 对每次惰性加载重新校验，切域或结束订阅使旧源失效。此查询调整不改变 schema 或索引；按 ID 的页面与命令授权另由对应 application owner 校验，不能以索引代替访问授权。

### 上下文与开场持久化

`Migration_13_14` 校验旧 Disclosure envelope、owner/anchor 的 conversation 与 variant membership、角色及先后关系后，保留原 format 1/2 正文逐字包装为 typed payload；创建 Step、namespace 和通知原因保持未知，不伪造接纳记录。新建 admission/use 与 opening 表；新安装使用相同导出 schema。历史 transcript 与新正文读取均按 SQLite 字符偏移分片，包含补充平面字符时按 code point 推进，避免 Android CursorWindow 单行上限。

### 旧企业消息身份修复

`Migration_14_15` 是数据迁移，表、列、索引与 v14 同构。`LegacyEnterpriseTranscriptMigration` 定点转换历史消息各 variant 的 `modelId`、Tool metadata 中 `sub_assistant_call.target_assistant_id`（包含嵌套 Tool output），以及 Conversation 的 `mode_injection_ids`。只移除旧引用中的地址来源，保留 deployment/resource 身份；消息正文、工具 input/output 文本、Provider metadata、历史 Disclosure 正文及未知字段不做字符串替换。当前格式的记录保留原序列化文本，非法企业引用中止整笔事务，原库可重试。

同一转换也由 `Migration_12_13` 与 `Migration_13_14` 在当前类型解码之前执行，覆盖尚未升级及已遗留在 v13 的旧企业消息；`LegacyTurnTranscriptMigrator` 在旧 Step 转换与 typed validator 前转换消息身份，保证更早版本不会先被严格解析器阻断。已经落到 v14 的历史记录由 `Migration_14_15` 完成转换；运行时解析器不接收旧格式。消息逐节点分片读取，不把整库或超大单行直接装入 CursorWindow。

历史 Disclosure format 1/2 中的子助手和企业 Seed 引用属于原正文，按对应历史格式语法验证并逐字保留，不复用当前配置解码器直接拒绝旧身份。当前 format 3 与 canonical 写入仍严格使用新引用；历史包也保持原 256 KiB UTF-8 能力上限，不接受超限数据。

## 运行时上下文完整性

`ConversationContextIntegrity` 在命令与加载/备份边界验证 entry/request 身份、来源选择、有效定位和因果关系：禁止引用未来节点/Step、同 node 的兄弟 variant，首次接纳必须有来源选择，同 Turn 后续选择保持一致。类型中的原始来源可保留已删除身份作为历史说明，但有效 placement 必须可定位。

`ConversationContextTransition.prune` 仅随删除消息、截断或替换消息树在同一事务中清理并保全引用；普通 START、接纳及 checkpoint 不触发全历史重整。较早接纳或创建 Step 删除前，把后续请求仍依赖的来源选择与位置增量物化到首个必要保留边界，无新增正文的接纳记录仍保留。失效 BeforeStep 向首个保留消费者迁移；失效消息位置用 Omitted 关闭对应贡献，不猜另一个消息。仍被引用的正文按原文转交并重映射，兄弟 variant 不共用对方创建的替代记录。Repository 先删除待替换的 admission/use，随后改节点/条目，再 insert-once 新关联；任何失败回滚整笔事务。

## 升级与备份验证

迁移由 Room 在事务内执行，新安装直接使用同构 schema。所有角色的消息均须可解码；旧字段的显式 null 按既有缺省语义处理，错误类型、未知 turn 状态或未知消息 part 必须中止迁移，不得置空后继续。备份校验接受受支持的历史数据库，在 staging 内由同一 Room migration 链升级到当前版本并验证后才发布 pending；当前版本在 staging 移除派生的 `room_master_table`，使 Room 打开时执行生成的 schema 校验并重建标记，不能仅凭既有 identity hash 信任表、列和索引。所有版本另行校验外键和 transcript；当前版本不转换 transcript，不为旧文件名引入额外读取路径。

实现入口为 `AppDatabase`、`AppDatabaseFactory`、各实体/DAO 与显式 `Migration_*`。迁移验证覆盖历史链、新旧 schema、数据与约束保全，并用 Android SQLite 的 `EXPLAIN QUERY PLAN` 检查主要查询的索引和排序行为；查询计划验证不等于设备耗时基准。


## 个人备份与恢复

运行时数据仍由各领域 owner 管理，备份层只构建和发布副本。`BackupRestoreApplicationService` 编排请求，`BackupArchiveService` 生成归档，`BackupDataGraph` 筛选和合并数据图，`PendingBackupRestore` 负责可恢复发布。WebDAV、S3 与提醒设置归用户 Settings；完整启动顺序见 [应用架构](application-architecture.md#启动恢复)。

### 个人数据范围

当前手工完整备份格式为 `rikkahub-personal-v1`，包含个人 `settings.json`、个人 `mcp_catalogs.json`、个人数据图的 `measix_pilot.db`、按图收集的 payload 和完整性 manifest。设置投影仍含用户自己的 Provider/Search/TTS/ASR/MCP/WebDAV/S3 凭据；企业配置、binding、Session、Feed、企业偏好和企业数据均不进入个人包。ZIP 未加密、未签名，SHA-256 仅校验完整性。

`BackupArchiveService` 在既有文件锁与 snapshot barrier 内取得 SQLite 一致快照，`BackupDataGraph` 将个人根及其 Message/Turn/Tool/Context 复制到新建数据库，按显式列名复制并保留自增 ID 高水位；不携带源库空闲页、未知表或旧全文索引。Artifact 引用和 FTS 从保留的消息重建。个人 Artifact 包含未挂接聊天的用户文件；payload 清单只来自保留的 metadata、生命周期 receipt，以及共享 Skill/字体配置，不扫描整份 upload/images。Workspace 注册信息属于共享配置，Workspace 目录内容不进入该包。

个人格式最低要求带 durable scope 的 Room schema 12，不绑定后续 App 或当前 Room 版本；升级继续使用同一迁移链。恢复仍接受已发布的 durable-v3/v4/v5 和原有无 manifest 个人备份，使用同一 Room migration/物理 schema 校验；旧格式缺失的头像、背景和预设资产仅在显式恢复入口按 `ArtifactReferencePolicy.detach` 回退默认。新格式要求配置根完整，已持久化的 DELETING 根保留给 Artifact 恢复 owner。正常用户删除附件留下的历史消息仍有效，不重建已经失效的活引用。

### 冷启动恢复与失败重试

`PendingBackupRestore` 保留原始个人输入。冷启动在应用 Room 和运行写入开放前读取最新数据库与同一 Settings DataStore，将“备份个人图 + 最新企业图 + 企业偏好仍引用的共享个人资产”构建为独立 publication。共享 Workspace 注册使用最新本地值。

相同主键但不同内容、不同文件 owner 的路径冲突、非规范路径或跨域引用均拒绝，不覆盖企业行；同一共享 Artifact 仅在 metadata 与 payload 均相同时合并。物理发布仍使用既有 swap/rollback；重试先恢复原图，再重新读取最新企业状态。

升级时遇到已发布个人版本留下的 prepared 输入，在 publication 副本上走生产 Room migration，保留原输入字节；若旧个人恢复已开始直接交换 pending 文件，先按原交换来源回滚，再进入合并。

CREATING/DELETING（包含 payload 已清除但 metadata 补偿未完成的状态）和图库 `.pending`/`.deleting` receipt 交回原 owner 恢复，不在备份层执行生命周期动作。

Settings 恢复只替换个人投影，保留企业使用偏好与内部清理状态。旧个人 prepared 输入的失效配置资产在合并企业图前归一化；派生结果保存在 pending 的独立文件，实际 Settings owner 消费同一结果，原 settings.json 与数据库字节不变。

恢复 owner 完成后先原子移出待恢复的 pending，再删除 rollback、publication 与已移出的目录；清理中断不重放恢复。

Settings-only 包只有个人 Settings 与 MCP Catalog，不携带会话数据或本地 payload 引用。

### 系统备份边界

`AndroidManifest.xml` 设置 `android:allowBackup="false"`。`backup_rules.xml` 与
`data_extraction_rules.xml` 同时排除普通和 device-protected 的文件、数据库、偏好及外部目录；
后者分别覆盖云备份和设备迁移，避免依赖部分厂商可能忽略的单一 manifest 开关。
混合域存储只能通过应用备份 owner 提取个人图，不能交给系统整体搬运。

备份验证需覆盖真实 SQLite 一致快照、个人/企业隔离、共享资产冲突、文件交换失败及重启重试；运行入口见 [测试策略](testing-strategy.md)。
