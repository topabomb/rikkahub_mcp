# 数据库索引

`AppDatabase` 是业务 Room 数据库；`APP_DATABASE_VERSION`、实体注解、导出 schema 与显式 migration 必须一致。索引只服务既有 DAO 查询，不改变 durable owner 或写协议。下文括号内字段按索引顺序排列。

## 查询覆盖

| 表 | 索引与用途 |
| --- | --- |
| `ConversationEntity` | `(assistant_id, parent_conversation_id, is_pinned, update_at)` 支持助手列表与最近会话；`(assistant_id, parent_conversation_id, folder_id, is_pinned, update_at)` 支持未归档分页；`(folder_id, parent_conversation_id, is_pinned, update_at)` 支持文件夹分页；`(parent_conversation_id, is_pinned, update_at)` 支持置顶列表、子会话查找与外键级联 |
| `message_node` | `(conversation_id, node_index)` 按会话读取有序消息节点，同时覆盖会话外键 |
| `conversation_model_context` | 主键 `(owner_node_id, owner_message_id)` 覆盖按 owner 点查与 insert-once；`(anchor_node_id)` 覆盖按因果 USER node 收口。按会话装载走 `owner_node_id JOIN message_node` 并按 `message_node.conversation_id` 过滤，由 `message_node(conversation_id, node_index)` 覆盖，不在 context 行重复保存 `conversation_id` |
| `MemoryEntity` | `(assistant_id)` 支持按助手读取记忆 |
| `GenMediaEntity` | `(path)` 支持文件名查重；`(create_at)` 支持图库时间排序与清理候选 |
| `artifact` | 保留 `relative_path` 唯一索引；`(folder, created_at)` 支持分目录列表与清理；`(state, created_at)` 支持生命周期候选。目录查询的状态条件可作为剩余过滤，不破坏全状态清理的时间顺序 |
| `artifact_reference` | 保留 `(artifact_id, node_id, reference_type)` 唯一索引与 `(node_id)`；唯一索引的左前缀同时覆盖附件引用检查与外键，不另存同列普通索引 |
| `conversation_folder` | `(assistant_id, sort_index, create_at)` 支持助手文件夹排序 |
| `favorites` | 保留 `ref_key` 唯一索引与 `(created_at)`；`(type, created_at)` 支持分类后的时间排序 |
| `turn_execution` | 保留 `(conversation_id)` 与 `(status)`，分别支持归属查询、非终态恢复 |
| `tool_execution` | 保留 `(turn_id)`、`(status)` 与 `(child_conversation_id)`，支持 turn 归属、非终态恢复和 Child 关系 |
| `workspaces` | 保留 `root` 唯一索引与 `(updated_at)`，支持路径唯一性和列表排序；主键用于 Workspace 点查 |
| `system_meta` | 现有主键满足 key 点查，无额外业务筛选索引 |

复合索引优先让等值筛选字段位于排序字段前。助手全列表与未归档列表分别建索引：后者需要 `folder_id` 参与范围定位，前者不能被中间的 `folder_id` 打断排序。全部排序方向一致时 SQLite 可反向扫描，无需另外建立 DESC 镜像索引。

索引不是按字段数量补齐：全量导出、低频恢复的小结果集排序不额外增加写入负担；包含前置通配符的文本搜索、JSON 展开聚合不能靠普通 B-tree 索引消除扫描。会话全文搜索继续由既有 FTS 投影负责，不新增第二搜索表或维护协议。

## 迁移边界

`Migration_8_9` 仅创建普通索引并移除被复合索引或既有唯一索引覆盖的冗余普通索引。表、列、默认值、主键、外键、唯一约束和行值均不变；文件、GUID、附件 metadata、Settings 和备份 manifest 不改写。图库路径索引不是唯一索引，历史重复路径不会阻止升级。

`Migration_9_10` 只新增 `conversation_model_context` 及其 `anchor_node_id` 索引，不扫描或回填历史会话。

迁移由 Room 在事务内执行，新安装直接使用同构 schema。备份校验接受受支持的历史数据库，恢复后由同一 Room migration 链升级，不为旧文件名引入额外读取路径。

架构相关入口：`AppDatabase`、`DataSourceModule`、各 `*Entity` / `*DAO`、`Migration_8_9`、`Migration_9_10`、`BackupArchiveService`。迁移验证覆盖历史链、新旧 schema、数据与约束保全，并用 Android SQLite 的 `EXPLAIN QUERY PLAN` 检查主要查询的索引和排序行为；查询计划验证不等于设备耗时基准。
