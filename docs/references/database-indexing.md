# 数据库索引

`AppDatabase` 是业务 Room 数据库；`APP_DATABASE_VERSION`、实体注解、导出 schema 与显式 migration 必须一致。索引只服务既有 DAO 查询，不改变 durable owner 或写协议。下文括号内字段按索引顺序排列。

## 查询覆盖

| 表 | 索引与用途 |
| --- | --- |
| `ConversationEntity` | `(scope, assistant_id, parent_conversation_id, is_pinned, update_at)` 支持域内助手列表与最近会话；`(scope, assistant_id, parent_conversation_id, folder_id, is_pinned, update_at)` 支持域内未归档分页；`(scope, folder_id, parent_conversation_id, is_pinned, update_at)` 支持域内文件夹分页；`(scope, parent_conversation_id, is_pinned, update_at)` 支持域内置顶、根会话与统计入口；`(parent_conversation_id)` 单独覆盖 Child 查询和自引用外键级联 |
| `message_node` | `(conversation_id, node_index)` 按会话读取有序消息节点，同时覆盖会话外键 |
| `conversation_model_context` | stable `id` 主键用于正文点查；`(owner_node_id, owner_message_id, occurrence)` 唯一索引支持同 variant 多条，`(anchor_node_id)` 支持因果收口。按会话经 owner node JOIN，不重复保存 conversation/realm；正文由轻量 header 与字符分片查询组装 |
| `conversation_context_admission` | stable `id` 主键；`(owner_node_id, owner_message_id, step_id)` 唯一，请求零新增也保存边界。来源选择是轻量版本化 payload，窗口起点保留真实 node/message locator |
| `conversation_context_use` | `(admission_id, ordinal)` 主键保存本边界贡献顺序，`(entry_id)` 索引支持正文引用。entry 外键为 NO ACTION，必须先调整关联再删除正文；`Omitted` 明确关闭继承贡献，不产生模型正文 |
| `conversation_opening` | `conversation_id` 主键/外键，同一会话最多一份；删除消息节点不删 opening，整会话删除级联。完整发布定义为冷 payload，通过 header 与字符分片按需读取 |
| `MemoryEntity` | `(scope, assistant_id)` 直接定位企业用户范围与 owner，列表按主键 `id ASC` |
| `GenMediaEntity` | `(path)` 支持文件名查重；`(create_at)` 支持全库恢复读取；`(scope, create_at)` 支持域内图库分页、观察与清理候选 |
| `artifact` | 保留 `relative_path` 唯一索引；`(folder, created_at)` 支持跨域生命周期恢复；`(state, created_at)` 支持状态候选；`(scope, folder, created_at)` 支持域内目录列表与清理；`(scope, created_at)` 支持整域读取与清理。目录查询的状态条件可作为剩余过滤，不破坏时间顺序 |
| `artifact_reference` | 保留 `(artifact_id, node_id, reference_type)` 唯一索引与 `(node_id)`；`CONTEXT` 与附件/工具归档共用此表，按来源类别精确替换，node 删除沿原 FK 收口。唯一索引的左前缀同时覆盖引用检查与外键，不另存同列普通索引 |
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

## 迁移边界

`Migration_8_9` 仅创建普通索引并移除被复合索引或既有唯一索引覆盖的冗余普通索引。表、列、默认值、主键、外键、唯一约束和行值均不变；文件、GUID、附件 metadata、Settings 和备份 manifest 不改写。图库路径索引不是唯一索引，历史重复路径不会阻止升级。

`Migration_9_10` 只新增 `conversation_model_context` 及其 `anchor_node_id` 索引，不扫描或回填历史会话。

`Migration_10_11` 将旧 Assistant transcript 转成显式 Step 与稳定 Tool locator，新增 `transcript_schema = 3`，重建 `tool_execution`，并转换等待用户与未开始的 turn 状态。消息按 SQLite 字符切片读取，避免大 tool output 超过 CursorWindow；转换后验证 Step/Tool 身份、顺序和终态，仅 `Continue` 可接后续 Step，Tool output 不得嵌套 Step。已转换内容只验证、不重复改写。

`Migration_11_12` 给 Conversation、Memory、Artifact、生成媒体、会话文件夹和收藏六类根记录追加 `scope TEXT NOT NULL DEFAULT 'personal'`。旧行的 ID、payload、引用、索引和外键不变；消息、turn、tool 和 context 通过所属会话确定域，不重复保存。ConfigurationScopeConverter 使用当时的来源、部署与用户编码，非法编码不能回退个人域。

`Migration_12_13` 先验证上述六类根记录中的全部非 Personal scope，以及 Conversation、Memory 与文件夹中的全部 managed ConfigurationReference；任一旧值不规范即回滚。验证通过后，把企业 scope 从 `enterprise~sourceNamespace~deploymentId~userId` 一次性改写为 `enterprise~deploymentId~userId`，并把企业引用改写为 `managed~deploymentId~resourceId`。同一次迁移把高频域内读取索引改为以 `scope` 开头，避免地址变化后保留下来的多企业数据在列表、分页、记忆、文件、媒体、文件夹和收藏查询中互相扩大扫描；Child 外键、全库恢复、生命周期状态与唯一性查询继续保留各自不带 scope 的必要索引。迁移不改变 deploymentId、userId、资源 ID、主键、关系或内容；运行时转换器只接受新格式。

会话助手列表、最近聊天、置顶、未归类/文件夹分页、文件夹列表和统计均在 SQL 内过滤完整 scope。FTS 在排序与限额之前经所属会话过滤 scope 和主会话，包含全局搜索与助手内搜索；索引仍是既有 message_fts 投影，不复制域数据。ConversationQueryService 负责选中域订阅及原 Session 授权，SelectedRealmPagingSource 对每次惰性加载重新校验，切域或结束订阅使旧源失效。此查询调整不改变 schema 或索引；按 ID 的页面与命令授权另由对应 application owner 校验，不能以索引代替访问授权。

`Migration_13_14` 校验旧 Disclosure envelope、owner/anchor 的 conversation 与 variant membership、角色及先后关系后，保留原 format 1/2 正文逐字包装为 typed payload；创建 Step、namespace 和通知原因保持未知，不伪造接纳记录。新建 admission/use 与 opening 表；新安装使用相同导出 schema。历史 transcript 与新正文读取均按 SQLite 字符偏移分片，包含补充平面字符时按 code point 推进，避免 Android CursorWindow 单行上限。

`Migration_14_15` 是数据迁移，表、列、索引与 v14 同构。`LegacyEnterpriseTranscriptMigration` 定点转换历史消息各 variant 的 `modelId`、Tool metadata 中 `sub_assistant_call.target_assistant_id`（包含嵌套 Tool output），以及 Conversation 的 `mode_injection_ids`。只移除旧引用中的地址来源，保留 deployment/resource 身份；消息正文、工具 input/output 文本、Provider metadata、历史 Disclosure 正文及未知字段不做字符串替换。当前格式的记录保留原序列化文本，非法企业引用中止整笔事务，原库可重试。

同一转换也由 `Migration_12_13` 与 `Migration_13_14` 在当前类型解码之前执行，覆盖尚未升级及已遗留在 v13 的旧企业消息；`LegacyTurnTranscriptMigrator` 在旧 Step 转换与 typed validator 前转换消息身份，保证更早版本不会先被严格解析器阻断。已经落到 v14 的历史记录由 `Migration_14_15` 收口；运行时解析器不接收旧格式。消息逐节点分片读取，不把整库或超大单行直接装入 CursorWindow。

历史 Disclosure format 1/2 中的子助手和企业 Seed 引用属于原正文，按对应历史格式语法验证并逐字保留，不复用当前配置解码器直接拒绝旧身份。当前 format 3 与 canonical 写入仍严格使用新引用；历史包也保持原 256 KiB UTF-8 能力上限，不接受超限数据。

`ConversationContextIntegrity` 在命令与加载/备份边界验证 entry/request 身份、来源选择、有效定位和因果关系：禁止引用未来节点/Step、同 node 的兄弟 variant，首次接纳必须有来源选择，同 Turn 后续选择保持一致。类型中的原始来源可保留已删除身份作为历史说明，但有效 placement 必须可定位。

`ConversationContextTransition.prune` 仅随删除消息、截断或替换消息树同事务收口；普通 START、接纳及 checkpoint 不触发全历史重整。较早接纳或创建 Step 删除前，把后续请求仍依赖的来源选择与位置增量物化到首个必要保留边界，零新增 seal 保留。失效 BeforeStep 向首个保留消费者迁移；失效消息位置用 Omitted 关闭对应贡献，不猜另一个消息。仍被引用的正文按原文转交并重映射，兄弟 variant 不共用对方创建的替代记录。Repository 先删除待替换的 admission/use，随后改节点/条目，再 insert-once 新关联；任何失败回滚整笔事务。

迁移由 Room 在事务内执行，新安装直接使用同构 schema。所有角色的消息均须可解码；旧字段的显式 null 按既有缺省语义处理，错误类型、未知 turn 状态或未知消息 part 必须中止迁移，不得置空后继续。备份校验接受受支持的历史数据库，在 staging 内由同一 Room migration 链升级到当前版本并验证后才发布 pending；当前版本在 staging 移除派生的 `room_master_table`，使 Room 打开时执行生成的 schema 校验并重建标记，不能仅凭既有 identity hash 信任表、列和索引。所有版本另行校验外键和 transcript；当前版本不转换 transcript，不为旧文件名引入额外读取路径。

架构相关入口：`AppDatabase`、`AppDatabaseFactory`、各 `*Entity` / `*DAO`、`Migration_8_9`、`Migration_9_10`、`Migration_10_11`、`Migration_11_12`、`Migration_12_13`、`Migration_13_14`、`Migration_14_15`、`LegacyEnterpriseTranscriptMigration`、`BackupArchiveService`。迁移验证覆盖历史链、新旧 schema、数据与约束保全，并用 Android SQLite 的 `EXPLAIN QUERY PLAN` 检查主要查询的索引和排序行为；查询计划验证不等于设备耗时基准。
