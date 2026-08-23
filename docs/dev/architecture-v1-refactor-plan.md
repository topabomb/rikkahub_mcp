# Architecture V1 重构方案：Runtime Core / Persistence / Projection

> 状态：本文档是本轮重构的唯一实施依据。含语义化命名体系、数据库 v6 一次性变更（每项变更附必要性裁决）、核心组件设计、逐文件执行计划与代码删除清单。
> 范围：`app` 模块（`service` / `data` / `ui`）。`ai` / `workspace` / `speech` / `search` 模块对外 API 不变更。
> 配置持久化（SettingsStore / Settings）本轮零改动；企业下发走既定 `EnterprisePolicyStore` 路线。
> 本轮为一次完整重构，不分发布阶段；工作流 A–J 为工程依赖序，同一版本交付。

## 0. 调研修订记录（2026-08 实施前核查）

对 master 代码逐文件核对后，以下偏差已修订进正文（其余假设全部核实成立）：

1. **既有索引必须随 RENAME 迁移**：`ManagedFileEntity` 现有 `relative_path`（UNIQUE）与 `folder` 两个索引。SQLite `ALTER TABLE RENAME` 保留原索引名，而 Room schema 校验要求索引名与 `@Entity` 声明一致——若不处理，M7 `runMigrationsAndValidate` 必然失败。修订：`ArtifactEntity` 声明全部三个索引（§3.3）；`Migration_5_6` 增加 DROP 旧名索引 + CREATE 新名索引 DDL（§3.4）。
2. **`ManagedFileRepository` 不存在**：现状 DB CRUD 由 `data/repository/FilesRepository.kt`（纯 DAO 包装）承担，文件系统编排在 `data/files/FilesManager.kt`。修订：工作流 E 的合并对象为 `ManagedFileDeletionService` + `FilesRepository`（§4.3、§5 工作流 E、附录 A 第 3 组）。
3. **checkpoint 持久化走 `onCheckpoint` 回调而非 Flow**：`GenerationHandler.generateText` 的持久化边界是 `onCheckpoint: suspend (GenerationCheckpoint) -> Unit` 参数（awaited durability boundary），Flow 中的 `GenerationChunk.Checkpoint` 只是事件通知。修订：`TurnEngine` 暴露 `TurnSession`（内含 `onCheckpoint` 供装配传参 + `bind(flow)`），提交协议仍是唯一实现（§4.2）。
4. **`GenerationChunk.Phase` 存在**：Target collect 体消费 Phase 更新 card 状态。修订：`TurnEvent` 增加 `Phase` 事件透传（§4.2）。
5. **`addMigrations` 注册位置**：在 `di/DataSourceModule.kt`（非 `AppDatabase.kt`）；DAO getter 命名风格为小写驼峰（`managedFileDao()`）。修订：A5 按实际位置与命名风格执行（`artifactDao()` / `artifactReferenceDao()` / `systemMetaDao()`）。
6. **Target 执行事实**：Target 走 TurnEngine 后将开始写 `turn_execution`/`tool_execution`（现状 Target checkpoint 只窄列写）。Master 全库恢复扫描（`recoverInterruptedTurns`）需过滤 Child 会话的 turn（由 `SubAssistantRecovery` 全权收口 Child），避免双路径收口（§5 工作流 D3、H2）。
7. **`ConversationHeader` 补 `newConversation`**：`Conversation.newConversation` 是 `@Transient` 运行态标记（新建未落库标记），snapshot 派生 `conversation` 投影时必须保留，否则标题生成等行为回归（§4.1.2）。
8. **测试基建**：Room 迁移测试对齐**上游范式**（`app/src/androidTest/.../Migration_11_12_Test.kt`）——用 **androidTest 插桩测试**（非 Robolectric JVM）：`build.gradle.kts` 配 `sourceSets { getByName("androidTest").assets.srcDirs("$projectDir/schemas") }`，`MigrationTestHelper` 用 4 参 `(instrumentation, AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())`。级联（FK）测试须显式 `PRAGMA foreign_keys = ON`（框架连接默认关闭）。`connectedDebugAndroidTest` 运行。FTS 虚表与 libsimple 不在 Room schema 内，Migration 测试不受影响。
9. **`reconcileStartup` 需保留启动补录开关语义**：现 `MeasixPilotApp.syncManagedFiles` 调 `filesManager.syncFolder()`；修订后启动路径只做 reconcile（无 INSERT），原补录能力移至文件页显式 `rescanUntracked()`（正文 §4.3 已含，此处强调 E5 落点）。
10. **（实施中裁决）`rescanUntracked` 与 `MISSING` 状态整体移除，写入路径原子化**：修订记录 9 保留的"文件页重新扫描补录入口"与 §3.3 的 `MISSING` 状态，经实施复核判定为对根因的事后补救而非修复——根因是 `FilesManager.trackManagedFile` 为 fire-and-forget 异步登记（失败仅日志），写文件成功但登记失败/未完成时遗留"磁盘有文件、DB 无记录"的不一致数据，`rescanUntracked`（补录）与 `MISSING`（缺失诊断）两个补丁功能均由此而来。裁决：① `trackManagedFile` 改造为同步 `registerTrackedFile`（登记失败 → 回滚删除磁盘文件、不返回失效 URI，"文件 + 记录"要么都在要么都不在，`createChatFilesByContents` / `createChatFilesByByteArrays` / `createChatTextFile` 相应 suspend 化、调用方协程化）；② 删除 `rescanUntracked()` / `FilesManager.syncFolder()` / 文件页"重新扫描"入口及相关本地化键；③ `ArtifactState` 只保留 `ACTIVE` / `DELETING`，`reconcileStartup` 对磁盘缺失的 ACTIVE 行直接删除（死数据清理，引用行经 FK 级联消失），删除 `MISSING` 徽标与 `files_page_state_missing` 键。数据完整性契约收敛为：DB 是唯一事实源，应用侧写入路径原子保证一致性，磁盘侧外部不一致由冷启动 reconcile 单向收敛（缺失→清行；外部放入→仅日志、绝不补录）。
11. **（实施中裁决）`ArtifactOrigin` 诞生方式列并入 v6，语义账本与注释纪律同步收口**：文件管理页暴露出"上传"语义名不副实（upload 目录实际混居五类居民：用户附件、生成图 chat copy、助手背景/头像副本、工具产物、MCP 资源），根因是 artifact 领域模型缺失"诞生面"。裁决：① v6 `artifact` 表新增 `origin TEXT NOT NULL DEFAULT 'USER'`（`ArtifactOrigin { USER, GENERATED, SYSTEM }`，String 列存 `.name`，不索引）——USER=用户引入（聊天上传/背景选图/头像选图/查看器设背景）、GENERATED=生成媒体派生副本（文生图 chat copy、工具设背景）、SYSTEM=其余系统创建（模型输出落盘、Workspace/MCP 工具产物、子助手远程附件拉取）；② 两条写入规则：结构性复制继承源 origin（`createChatFilesByContents` 对 `file://` upload 源反查继承，覆盖 fork/克隆/子助手入站；`ManagedLocalArtifactStore.copyFilePreservingOrigin` 覆盖 tool output 重写），系统链路显式传参（`GeneratedMediaStore`/`AssistantBackgroundService.replaceBackground`/`convertBase64Image`/`WorkspaceTools`/`McpManager`/`AttachmentResolver`）；③ 文件页条目显示来源徽标（`setting_files_page_origin_*` 键 ×5 locale），tab 结构与删除流程不变；④ 语义账本定稿：`artifact` 表=聊天域+设置域的受引用文件实体注册表，`gen_media`=相册域化身编目，`tool_outputs`=非受管落盘层，`ArtifactStore`=生命周期协调器、`ManagedLocalArtifactStore`=写入门面（类名不改、KDoc 互声明边界）；⑤ 注释纪律：全部代码注释清除对本计划文档的术语引用（工作流编号/章节号/修订记录号/用例编号），改为语义自足描述——本计划归档后代码不依赖其编号体系。

---

## 1. 背景

### 1.1 产品定位与语义体系

Pilot 是 **Android 本地 Agent 工作台**（对标 ChatGPT Work / WorkBuddy 的移动端形态）。本轮重构以产品语义为命名的唯一权威，全部核心对象、表、命令、事件映射到下表词汇：

```text
Work（一次持续任务 = 一个 Conversation）
 ├─ Turn（一轮交互）
 │   ├─ Step（模型步进，运行态）
 │   ├─ Tool Execution（工具执行，含 MCP）
 │   ├─ Approval（审批 / ask_user）
 │   └─ Artifact（附件 / 生成媒体 / 工具产物）
 ├─ Sub-Agent Run（子助手委派，Child Conversation）
 └─ Workspace（PRoot 沙箱）
```

**产品语义 → 代码对象权威映射表**（命名最终裁决，全 PR 遵守）：

| 产品语义 | 运行态对象 | 持久化 | 说明 |
| --- | --- | --- | --- |
| Conversation | `ConversationRuntime` / `ConversationCommand` / `ConversationSnapshot` / `ConversationMutation` | `conversationentity` + `message_node` | 会话状态唯一所有者 |
| Turn | `TurnEngine` / `TurnEvent` / `ConversationRuntime.beginTurn` | `turn_execution`（现有） | 一轮生成执行 |
| Step | `GenerationChunk.Phase` / `CheckpointKind`（现有顶层枚举） | 不落库 | 步进是 Turn 内运行态，随 checkpoint 固化为执行事实 |
| Tool | `Tool` / `ToolExecutionContext`（现有） | `tool_execution`（现有） | 含 MCP 工具 |
| Approval | `UpdateToolApproval` 命令 / `ToolApprovalState`（现有） | 消息 Tool part + `tool_execution.status` | 不新增表：现有机制已完备 |
| Artifact | `ArtifactStore` / `ArtifactEntity` / `ArtifactReferenceEntity` | `artifact`（原 managed_files 改名）/ `artifact_reference` | 文件资产事实与引用投影 |
| Interaction | `ask_user` 桥接（`user_interaction` metadata，现有） | 不新增表 | 机制保留 |
| Sub-Agent Run | `DelegationCoordinator`（原 SubAssistantCoordinator） | Child conversation + `sub_assistant_call`（现有） | lineage / lease / 权限 / 恢复 |

命名迁移：`ManagedFile` 全系 → `Artifact`；`Session` → `Runtime`；执行引擎按 Turn 语义命名为 `TurnEngine`。`Session`、`ManagedFile` 不再出现于新代码。

### 1.2 重构动机：三个结构性缺陷（均已在代码定位验证）

**缺陷 A：状态所有权没有落点。** `Conversation` 一个可变聚合被三方写入（UI 层 folder/pin/title/收藏/分支、生成链路 messageNodes/suggestions、维护链路压缩/恢复/标题生成），一致性靠"先 patch 内存 Session 再落库"的注释级约定。`ChatService.moveConversationToFolder` 与 `deleteFolder` 的实现注释直接承认：任何一次整对象 `updateConversation(state.value)` 都会把旧 `folderId` 写回数据库。

**缺陷 B：编排双轨。** Master 生成走 `ChatService.handleMessageComplete` 的 collect 体，Target 生成走 `SubAssistantCoordinator` 的另一套 collect 体，两者都调用 `GenerationHandler`（共享核心已存在，分裂的是 chunk→持久化提交协议）。新能力要双路径验证，是"普通聊天正常、子助手行为不一样"的结构性来源。

**缺陷 C：持久化按"整聚合快照"工作。** 已验证的六个机制：

1. `MessageNodeDAO.insertAll(REPLACE)` 全量重写 message_node，checkpoint 成本 O(history × checkpoints)。
2. `MessageFtsManager.indexConversation` 每轮 finalize 全量 DELETE+INSERT，累计写入平方级增长。
3. 文件引用埋在消息 JSON：`hasConversationReference` 判断"文件是否被引用"需加载全部 Conversation（含 Child）并反序列化全部消息。
4. 磁盘与 DB 双事实源：`FilesManager.syncFolder()` 启动时"磁盘有文件、DB 无记录则自动 INSERT"，掩盖任何 metadata 一致性错误（删除竞态导致的记录消失会在重启后"复活"）。
5. 破坏性操作无幂等：`deletePermanently` 依赖按钮 disable + Mutex，重复点击是多个独立 destructive request——即用户观察到的"删除重复点击时 DB 奇怪状态"。
6. 流式变换全量执行：`visualTransforms` 对完整消息列表 map，而流式期间只有最后一条 assistant 消息在变。

### 1.3 用户可感知现象与根因对照

| 现象 | 根因 |
| --- | --- |
| 长会话越聊越卡、生成期间掉帧 | C1 + C6 + C2 |
| 文件删除后重启"复活" | C4 |
| 删除重复点击后 DB 状态异常 | C5 |
| 标题/文件夹偶发丢失、旧关系复活 | A |
| 子助手与主会话行为不一致 | B |
| 打开删除确认框迟缓（历史越多越明显） | C3 |

### 1.4 非目标

- 不重写 Compose UI；不更换 Room / DataStore / FTS 技术栈。
- 不引入 Event Sourcing / CQRS / 新 Gradle 模块 / 插件框架。
- 不动 SettingsStore、Provider / MCP / TTS 配置结构。
- 不拆 conversation 表；不做消息内容拆列范式化。
- 不做消息节点懒加载 / 分窗口加载（本轮解决"渲染与写入成本"，不解决"内存占用"；懒加载列为未来独立议题）。
- 不合并 GenMedia（生成媒体）存储进 Artifact 体系：GenMedia 有独立目录 / 缩略图 / 导出链路，本轮仅统一命名语义。
- 不修改 `ai` 模块 `Conversation` / `MessageNode` / `UIMessage` 模型结构。

---

## 2. 目标架构

### 2.1 分层与职责契约

```text
┌────────────────────────────────────────────────────────────┐
│ UI 层（不重写，仅换数据订阅与命令提交）                          │
│ Compose Screen + ViewModel                                  │
│ 契约：只发 Command / 订阅 Snapshot；不碰 Repository/文件/Runtime│
├────────────────────────────────────────────────────────────┤
│ Application 层（ChatService 收缩为装配 + 副作用薄壳）           │
│ ChatService：装配 Turn 输入（transformers/tools/context）、     │
│   collect TurnEvent 做业务副作用（标题/建议/TTS/通知）           │
│ DelegationCoordinator：子助手专有语义（lineage/lease/恢复/审批） │
│ 契约：不写 Conversation、不落库、不含生成循环                    │
├────────────────────────────────────────────────────────────┤
│ Runtime 层（本轮核心）                                        │
│ ConversationRuntime：单写通道 Command→Reducer→Snapshot+Mutation│
│ TurnEngine：GenerationChunk→Runtime 提交协议（唯一实现）        │
│ TurnPipelineFactory：Master/Target 共用管道清单（唯一实现）     │
├────────────────────────────────────────────────────────────┤
│ Data 层（职责边界修正）                                       │
│ ConversationRepository：mutation 单事务应用 + 执行事实 + FTS 编排│
│ ArtifactStore：Artifact 事实 + 引用投影 + 生命周期协议           │
│ 契约：Data 层不依赖调用方内存态；投影表可重建                     │
├────────────────────────────────────────────────────────────┤
│ Room(v6) / Files / DataStore / message_fts                   │
└────────────────────────────────────────────────────────────┘
```

依赖方向只允许向下。

### 2.2 架构不变式（写入后续所有相关 PR 描述）

1. 每类 durable state 只有一个 owner：conversation header 归 Runtime 命令、message tree 与流式状态归 `ConversationRuntime`、执行事实归 `TurnEngine` 提交、artifact metadata 归 `ArtifactStore`。
2. 所有 Conversation 修改必须经 `ConversationRuntime.submit(command)` 或 `applyStreamingDelta`；全库不存在任何整对象回写路径（契约测试 I1 强制）。
3. Master 与 Target 的 chunk→持久化提交协议只有一个实现（`TurnEngine`）；业务副作用差异留在各自 Application 组件。
4. `message_fts`、`artifact_reference`、`ConversationSnapshot` 都是 projection，可随时重建，永不当事实源。
5. 破坏性副作用 durable（状态机）、幂等（CAS）、可恢复（重启续删）。UI disable 只是 UX。
6. Runtime 层固定 5 个文件（Commands / Reducer / Runtime / Registry / TurnEngine）；Artifact 域固定 1 个服务类 + 2 个新 DAO + 2 个新实体。新增组件须先证明无法并入现有对象。

---

## 3. 数据库 v6 变更（一次性完整变更）

> 只产生 `Migration_5_6`，不预留 v7。每项变更先给必要性裁决，未通过裁决的不进 schema。

### 3.1 变更清单与必要性裁决

| 变更 | 目的 / 实际操作频次 | 必要性裁决 |
| --- | --- | --- |
| `managed_files` RENAME → `artifact` | 语义统一；零运行时成本（一次性 DDL） | 采纳：语义化是本轮既定要求 |
| `artifact.state` 列（ACTIVE / DELETING）+ 索引 | 删除幂等屏障（CAS）+ 崩溃续删。写频次 = 删除操作（低频）；读频次 = 文件页列表 + 每次冷启动 reconcile（按 state 索引扫）。磁盘缺失的 ACTIVE 行由 reconcile 直接清行（修订记录 10：无 MISSING 态） | 采纳：一个列同时解决 C4/C5，且是幂等屏障的唯一承载（见 3.2） |
| 新表 `artifact_reference` | inspect（删除影响检查）从全库反序列化降为索引查询；GC 判定从内存对象匹配降为 SQL。写频次 = 每 checkpoint 增量小批量；读频次 = 删除确认（低频但关键路径） | 采纳：这是 C3 的唯一结构性解法 |
| 新表 `system_meta(key, value)` | 软件级一次性标记（backfill flag 等）。写一次、读冷启动一次 | 采纳：必须随 DB 备份/恢复走（SharedPreferences 会在恢复后与库失配，见 3.5）；定义宽度足够承载未来任何一次性迁移标记 |
| ~~`message_node.revision` 列~~ | delta 持久化 | **否决**：单写通道下 delta 由内存 structural diff（引用不等）计算，DB 层无任何读路径；列只为"未来可能的并发"预设，违背最小 schema 原则。未来真需要时加列是 additive migration |
| ~~`artifact_operation` 表~~ | 删除幂等 | **否决**：其全部信息可由 `state` + 行存在性推导（进行中=DELETING、已完成=行不存在、未开始=ACTIVE），CAS UPDATE 即原子幂等屏障；通用幂等不应按操作类型建表（见 3.2） |
| ~~`artifact_reference.conversation_id` 冗余列~~ | 按 artifact 反查引用会话 | **否决**：唯一消费场景（inspect 的诊断信息）低频，走 `artifact_id` 索引 + `message_node` 主键 join 足够；会话级清理已由 FK 级联覆盖，冗余列无净收益 |
| `conversationentity.nodes` 空置列 | — | 保留（删列需重建表，收益不成比例）；配套死代码 `resetConversationNodes` 删除 |

裁决后 schema 增量：**1 个 RENAME、1 个加列、2 张新表、4 个索引**。

### 3.2 幂等屏障模式（通用原则，不建专用表）

破坏性操作的幂等统一由**实体状态机列 + CAS UPDATE**承载：

```sql
UPDATE artifact SET state = 'DELETING', updated_at = :now
WHERE id = :id AND state = 'ACTIVE'
```

返回受影响行数 == 1 → 获得执行权；== 0 → 已在进行中或已完成（读 state / 行存在性区分回复）。SQLite 单写事务保证该语义原子，**不依赖调用方生成 operationId 的纪律**，比 operation 表更强且零 schema 成本。

未来出现其他破坏性操作（批量移动、workspace 重建等）时，演进路径是**同一模式推广**（相关实体加 state 列 + CAS），而不是每类操作建一张 operation 表。真正需要跨实体、多步、带审计的操作队列时再评估通用 job 表——当前唯一用例撑不起该抽象，提前建表即定义过窄的镜像错误。

### 3.3 实体定义（最终形态）

`data/db/entity/ArtifactEntity.kt`（原 `ManagedFileEntity.kt` 改名，既有列名不变；既有两个索引必须保留声明，见修订记录 1）：

```kotlin
enum class ArtifactState { ACTIVE, DELETING }

@Entity(
    tableName = "artifact",
    indices = [
        Index(value = ["relative_path"], unique = true),   // 既有索引，声明名随表名迁移
        Index(value = ["folder"]),                          // 既有索引，声明名随表名迁移
        Index(value = ["state"]),                           // 新增
    ],
)
data class ArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val folder: String,           // e.g. "upload" / "image"
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "state") val state: String = ArtifactState.ACTIVE.name,
    @ColumnInfo(name = "origin") val origin: String = ArtifactOrigin.USER.name,  // 修订记录 11：诞生方式（USER / GENERATED / SYSTEM）
)
```

`data/db/entity/ArtifactReferenceEntity.kt`：

```kotlin
/** 消息历史对 artifact 的引用投影。可从消息 JSON 全量重建，永不当事实源。 */
enum class ArtifactReferenceType { ATTACHMENT, TOOL_OUTPUT }

@Entity(
    tableName = "artifact_reference",
    indices = [
        Index("artifact_id"),
        Index("node_id"),
        Index(value = ["artifact_id", "node_id", "reference_type"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ArtifactEntity::class,
            parentColumns = ["id"], childColumns = ["artifact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"], childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArtifactReferenceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "artifact_id") val artifactId: Long,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "reference_type") val referenceType: String,
)
```

归属说明：
- **node FK（CASCADE）**：`message_node` 行删除（会话删除级联 / 节点删除）时引用行自动消失——清理路径由 DB 保证，无需显式 DELETE。
- **conversation 关联**：经 `node_id` join `message_node.conversation_id` 获得，不冗余存储。
- **Assistant 背景图引用（Settings 内 URL）是可变当前引用而非历史事实**，不进本表——继续由 `detachMutableReferences`（Settings 解除）处理，归属：Settings 域。
- **GenMedia 独立体系不进本表**（非目标）。

`data/db/entity/SystemMetaEntity.kt`：

```kotlin
@Entity(tableName = "system_meta")
data class SystemMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
```

`MessageNodeEntity` **不加列**（revision 已否决）。

### 3.4 Migration_5_6

`data/db/migrations/Migration_5_6.kt`：

```kotlin
val Migration_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1) Artifact 语义统一（列与数据原样保留）
        db.execSQL("ALTER TABLE managed_files RENAME TO artifact")

        // 1b) 既有索引随表迁移（SQLite RENAME 不改索引名，Room 校验要求名随 @Entity 声明走）
        db.execSQL("DROP INDEX IF EXISTS index_managed_files_relative_path")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artifact_relative_path ON artifact(relative_path)")
        db.execSQL("DROP INDEX IF EXISTS index_managed_files_folder")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_folder ON artifact(folder)")

        // 2) 状态机（幂等屏障 + 续删 + 缺失诊断）
        db.execSQL("ALTER TABLE artifact ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_state ON artifact(state)")

        // 3) 引用投影（双 FK 级联：artifact 行删除 / node 行删除均自动清理）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artifact_reference (
                rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                artifact_id INTEGER NOT NULL,
                node_id TEXT NOT NULL,
                reference_type TEXT NOT NULL,
                CONSTRAINT fk_artifact_reference_artifact
                    FOREIGN KEY(artifact_id) REFERENCES artifact(id) ON DELETE CASCADE,
                CONSTRAINT fk_artifact_reference_node
                    FOREIGN KEY(node_id) REFERENCES message_node(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artifact_reference_artifact_id_node_id_reference_type ON artifact_reference(artifact_id, node_id, reference_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_reference_artifact_id ON artifact_reference(artifact_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_reference_node_id ON artifact_reference(node_id)")

        // 4) 软件级一次性标记
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS system_meta (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}
```

`AppDatabase.kt` 同步：`version = 6`；entities 以 `ArtifactEntity` 替换 `ManagedFileEntity`，新增 `ArtifactReferenceEntity` / `SystemMetaEntity`；DAO getter 更名与新增（沿用现有小写驼峰命名：`artifactDao()` / `artifactReferenceDao()` / `systemMetaDao()`）。`addMigrations(..., Migration_5_6)` 在 `di/DataSourceModule.kt` 的 databaseBuilder 处追加（迁移注册实际位置，非 `AppDatabase.kt`）。

### 3.5 引用回填与备份恢复一致性

backfill 完成标记必须随库走：备份恢复（ZIP 整库替换）会把 DB 带回旧状态，若标记在 SharedPreferences（不随备份走）则恢复后出现"标记已完成但引用为空"的失配，引用检查与 GC 全部失效。`system_meta` 随库备份/恢复，天然一致；v5 备份恢复到 v6 App 时 migration 幂等重跑、backfill 重跑，收敛正确。

回填协议（`ArtifactStore.backfillReferences()`，`MeasixPilotApp` 在 `recoveryGate` 就绪后启动，Application scope 不阻塞 UI）：

1. 读 `system_meta['artifact_reference_backfilled']`；已置位返回。
2. `conversationDAO.getAllConversations()`（现有方法，含 Child）逐会话加载 message_node，提取消息内 file URI token（提取算法合并自现 `ConversationRepository.fileReferenceTokensFor(uri)` 与 `ManagedFileDeletionService.collectFileUrlStrings()`，迁入 `ArtifactStore` 私有函数）。
3. token → 相对路径 → `artifact.relative_path` 匹配 → 批量 `INSERT IGNORE`（唯一索引幂等）。
4. 写 `system_meta['artifact_reference_backfilled'] = "true"`。

降级行为（回填完成前）：`inspect` 回退旧全量扫描（删除功能可用）；`collectUnreferencedArtifacts`（GC）保守跳过本轮（宁可保留文件）。

### 3.6 DAO 变更明细

`data/db/dao/ArtifactDAO.kt`（原 `ManagedFileDAO.kt` 改名，表名 `artifact`，现有方法全部保留）新增：

```kotlin
@Query("UPDATE artifact SET state = :state, updated_at = :now WHERE id = :artifactId AND state = :expectedState")
suspend fun compareAndSetState(artifactId: Long, expectedState: String, state: String, now: Long): Int
// 返回受影响行数：1 = 获得执行权（幂等屏障），0 = 状态已变迁

@Query("SELECT * FROM artifact WHERE state = :state")
suspend fun listByState(state: String): List<ArtifactEntity>   // reconcileStartup 专用（冷启动一次）

@Query("SELECT * FROM artifact WHERE id = :artifactId")
suspend fun getById(artifactId: Long): ArtifactEntity?
```

`data/db/dao/ArtifactReferenceDAO.kt`（新增）：

```kotlin
@Dao
interface ArtifactReferenceDAO {
    @Query("SELECT EXISTS(SELECT 1 FROM artifact_reference WHERE artifact_id = :artifactId)")
    suspend fun existsByArtifactId(artifactId: Long): Boolean

    /** 引用会话（诊断信息）：走 artifact_id 索引 + message_node 主键 join */
    @Query(
        """
        SELECT DISTINCT mn.conversation_id FROM artifact_reference ar
        JOIN message_node mn ON mn.id = ar.node_id
        WHERE ar.artifact_id = :artifactId
        """
    )
    suspend fun referencingConversationIds(artifactId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)  // 依赖唯一索引幂等
    suspend fun insertAll(references: List<ArtifactReferenceEntity>)

    /** upserted 节点的引用替换语义第一步（第二步 insertAll） */
    @Query("DELETE FROM artifact_reference WHERE node_id IN (:nodeIds)")
    suspend fun deleteByNodeIds(nodeIds: List<String>)

    /** 会话级兜底清理（node FK 级联已覆盖主路径，此为显式备份入口） */
    @Query("DELETE FROM artifact_reference WHERE node_id IN (SELECT id FROM message_node WHERE conversation_id = :conversationId)")
    suspend fun deleteByConversationId(conversationId: String)
}
```

`data/db/dao/SystemMetaDAO.kt`（新增）：

```kotlin
@Dao
interface SystemMetaDAO {
    @Query("SELECT value FROM system_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: SystemMetaEntity)
}
```

`MessageNodeDAO` 新增（现有保留）：

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertAll(nodes: List<MessageNodeEntity>)   // delta upsert 原语

@Query("DELETE FROM message_node WHERE id IN (:nodeIds)")
suspend fun deleteByIds(nodeIds: List<String>)
```

`ConversationDAO`：现有窄列方法（`updateConversationTitle` / `updateConversationFolderId` / `updateChatSuggestions` / `updatePinStatus` / `updateAssistantId` / `updateCustomSystemPrompt` / `updateModeInjectionIds` / `updateWorkspaceCwd` / `updateAll`）全部保留，是 `applyMutation` 的 header 落库原语。

---

## 4. 核心设计

### 4.1 ConversationRuntime（单写通道）

#### 4.1.1 文件组织（5 文件）

```text
service/runtime/
├── ConversationCommands.kt   // 命令 + Mutation + HeaderPatch + Snapshot + 三态包装
├── ConversationReducer.kt    // 纯函数 reducer
├── ConversationRuntime.kt    // 单写通道主体（由 ConversationSession 改造）
├── ConversationRuntimeRegistry.kt
└── TurnEngine.kt             // chunk → Runtime 提交协议
```

#### 4.1.2 命令集（11 条）

`ConversationCommands.kt`：

```kotlin
sealed interface ConversationCommand

// ---- 生成期（由 TurnEngine 提交） ----
/**
 * Reducer 语义：若消息树中不存在 assistantMessageId 对应的 assistant 消息（新 turn），
 * 追加空 assistant 响应槽（对齐现 handleMessageComplete 的 updateCurrentMessages(generationMessages)
 * 预写语义，保证执行事实与消息槽原子可见）；若已存在（审批后 resume 场景）则不追加，仅登记。
 * ExecutionFacts：turn status = RUNNING（对齐现 persistTurnCheckpoint 首次提交语义）。
 */
data class BeginTurn(val turnId: Uuid, val assistantMessageId: Uuid) : ConversationCommand

data class CommitCheckpoint(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>,        // checkpoint 时点完整 currentMessages
    val turnStatus: TurnExecutionStatus,
    val turnReason: String?,
    val toolExecution: ToolExecutionEntity?,
) : ConversationCommand

data class FinalizeTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>?,       // null = 仅终态收口，不替换消息
    val terminalStatus: TurnExecutionStatus,   // COMPLETED / CANCELLED / FAILED / INCOMPLETE / INTERRUPTED（现有枚举 8 值全集见 TurnExecutionEntity.kt）
    val terminalReason: String?,
    val closeInterruptedTools: Boolean,   // 崩溃恢复场景：关闭未完工具
) : ConversationCommand

// ---- 结构与域命令（立即持久化；由 Application 层/UI 提交） ----
data class AppendUserMessage(val message: UIMessage) : ConversationCommand
data class EditMessageVariant(val nodeId: Uuid, val variant: UIMessage) : ConversationCommand
data class DeleteMessage(val messageId: Uuid) : ConversationCommand
data class SelectNodeVariant(val nodeId: Uuid, val selectIndex: Int) : ConversationCommand
data class TruncateToNodeIndex(val nodeIndexInclusive: Int) : ConversationCommand          // regenerate 截断
data class ReplaceMessageTree(val nodes: List<MessageNode>) : ConversationCommand          // 压缩/恢复/fork 载入/新会话初始化
data class UpdateHeader(
    val title: String? = null,
    val suggestions: List<String>? = null,
    val isPinned: Boolean? = null,
    val folderId: OptionalFolderId = OptionalFolderId.Keep,     // Keep / Clear / SetTo(uuid)
    val assistantId: Uuid? = null,
    val customSystemPrompt: OptionalString = OptionalString.Keep,
    val modeInjectionIds: OptionalUuidSet = OptionalUuidSet.Keep,
    val workspaceCwd: OptionalString = OptionalString.Keep,
    val sanitizeForPersistence: Boolean = false,                // 恢复路径落库前 sanitize
) : ConversationCommand
data class UpdateToolApproval(
    val messageId: Uuid,
    val toolCallId: String,
    val approvalState: ToolApprovalState,
) : ConversationCommand

// 三态包装（同文件）
sealed interface OptionalFolderId { data object Keep; data object Clear; data class SetTo(val id: Uuid) }
sealed interface OptionalString { data object Keep; data class Set(val value: String?) }
sealed interface OptionalUuidSet { data object Keep; data class Set(val value: Set<Uuid>) }
```

收藏语义说明：`MessageNode.isFavorite` 为 `@Transient`（加载时由 `FavoriteRepository` 回填，不持久化到 node），故不设收藏命令——收藏记录写 `FavoriteRepository`（现状保留），Runtime 在下次 load 时自然回填。

同文件持久化 delta 与 UI 投影：

```kotlin
data class ConversationMutation(
    val conversationId: Uuid,
    val headerPatch: ConversationHeaderPatch?,
    val upsertedNodes: List<MessageNode>,
    val deletedNodeIds: List<Uuid>,
    val updateAt: Long,
)

data class ConversationHeaderPatch(
    val title: String? = null,
    val chatSuggestions: List<String>? = null,
    val isPinned: Boolean? = null,
    val folderId: OptionalFolderId = OptionalFolderId.Keep,
    val assistantId: Uuid? = null,
    val customSystemPrompt: OptionalString = OptionalString.Keep,
    val modeInjectionIds: OptionalUuidSet = OptionalUuidSet.Keep,
    val workspaceCwd: OptionalString = OptionalString.Keep,
)

data class ExecutionFacts(
    val turn: TurnExecutionEntity?,
    val toolExecution: ToolExecutionEntity?,
)

class ConversationSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,     // title/isPinned/folderId/suggestions/assistantId 等
    val nodes: List<MessageNode>,       // 渲染顺序；未变节点引用稳定（===）
    val activeTurn: ActiveTurnState?,   // 流式期间仅此处高频变化
) {
    val conversation: Conversation      // 兼容投影：header + nodes（含 activeTurn 覆盖）派生
}

data class ConversationHeader(
    val id: Uuid, val title: String, val assistantId: Uuid, val folderId: Uuid?,
    val isPinned: Boolean, val chatSuggestions: List<String>, val customSystemPrompt: String?,
    val modeInjectionIds: Set<Uuid>, val workspaceCwd: String?, val parentConversationId: Uuid?,
    val newConversation: Boolean,   // @Transient 运行态标记；snapshot 派生 conversation 投影时必须保留（修订记录 7）
    val createAt: Long, val updateAt: Long,
)

data class ActiveTurnState(
    val turnId: Uuid, val assistantMessageId: Uuid, val messages: List<UIMessage>,
)
```

#### 4.1.3 Reducer（纯函数，独立可测）

`ConversationReducer.kt`：

```kotlin
internal object ConversationReducer {
    /**
     * 将 command 应用到 current，返回新 Conversation。
     * 未被命令触及的 MessageNode 必须保持同一实例引用（structural sharing，
     * 是 delta 持久化与 Compose skip 的共同前提）。
     * 实现迁移来源（从 ChatService / Conversation 私有函数平移为纯函数，逐函数对照）：
     *  - Conversation.updateCurrentMessages 等价逻辑（CommitCheckpoint / FinalizeTurn 消息替换）
     *  - closeOpenTools / finishReasoning / markAssistantTerminal（FinalizeTurn 终态收口）
     *  - cancelToolByUser / interruptPendingTool（UpdateToolApproval 拒绝路径）
     *  - editMessage / deleteMessage / selectMessageNode / regenerateAtMessage 的树操作
     *  - sanitizeForPersistence（UpdateHeader(sanitize=true) 路径）
     */
    fun reduce(current: Conversation, command: ConversationCommand): Conversation
}
```

约束：reducer 零 IO；迁移函数平移不改写逻辑，diff 可逐行核对。

#### 4.1.4 Runtime 主体（ConversationSession 原地改造）

`ConversationRuntime.kt`（保留 refCount / idle 逐出 / generationJob / turn 取消管理 / TTS 会话标记等既有职责与签名）：

```kotlin
class ConversationRuntime(
    initial: Conversation,
    private val repository: ConversationRepository,   // 直接依赖，不设中间接口
    private val coroutineScope: CoroutineScope,
) {
    /** 唯一内部事实流 */
    private val _snapshot = MutableStateFlow(initial.toSnapshot())

    /** UI 主订阅源 */
    val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    /** 兼容投影（ChatService.getConversationFlow 的返回物）：由 snapshot 派生，单事实源 */
    val state: StateFlow<Conversation> =
        _snapshot.map { it.conversation }.stateIn(coroutineScope, SharingStarted.Eagerly, initial)

    /** 流式高频更新：非挂起、conflated、永不落库、不加锁 */
    fun applyStreamingDelta(messages: List<UIMessage>)

    /** 所有结构性修改的唯一入口 */
    suspend fun submit(command: ConversationCommand): Conversation
    suspend fun submitGeneration(command: ConversationCommand): Conversation = submit(command)

    // ---- 保留的既有职责（签名不变） ----
    fun acquire(): Int
    fun release(): Int
    val generationJob: StateFlow<Job?>
    fun beginTurn(turnId: Uuid)
    fun requestCancel(turnId: Uuid, reason: String)
    fun markTurnFinalized(turnId: Uuid)
    /** 写通道占用中（submit 进行时），Registry 据此阻止 idle 回收 */
    fun isWriteInFlight(): Boolean

    // ---- 内部 ----
    private val commandMutex = Mutex()   // 单写通道互斥（吸收原 persistMutex/stateRevision/persistedRevision/withPersistLock）
}
```

`submit` 行为契约（顺序固定）：

1. `commandMutex.withLock`。
2. `val old = state.value`。
3. `val new = ConversationReducer.reduce(old, command)`。
4. `new === old`（幂等）→ 直接返回。
5. structural sharing diff：`changedNodes = new.messageNodes.filterIndexed { i, n -> n !== old.messageNodes.getOrNull(i) }`、`deletedNodeIds`、header 差异。
6. 发布 `_snapshot`（未变节点引用不变）。
7. 持久化：结构命令 → `repository.applyMutation(mutation)`；生成期命令 → `applyMutation(mutation, executionFacts)`（Turn/ToolExecution upsert 时机对齐现 `persistTurnCheckpoint`）。
8. 持久化失败：日志记录、内存保持新状态、下次命令重试整包差异——不回滚内存，避免 UI 与 DB 双向不一致放大。

`applyStreamingDelta` 行为契约：

- 无锁（`MutableStateFlow` 原子性 + conflation），只更新 `_snapshot.activeTurn` 并同步派生 `conversation` 投影（最后一个 assistant node 的当前消息替换为 activeTurn 内容）——**旧消费方（读 `conversation.currentMessages`）在流式期间继续看到更新**。
- 派生成本：O(N) 引用级浅 copy（列表 + 单 node 替换），无变换、无序列化；对比旧路径（全列表 visualTransforms map + 整树 copy + 发布）低一个量级，且 `StateFlow` conflation 按 Compose frame 自然合并多次 chunk。
- 不触发任何持久化。
- 过渡策略：消费方全部迁移到 `snapshot` 后（工作流 G），`state` 投影停止随 streaming 更新、仅在 submit 时派生，最终可移除。

**folder 覆盖缺陷消除证明**：`UpdateHeader(folderId)` 基于 `state.value` 最新值应用，持久化只写 `folder_id` 窄列；全库不存在整对象回写路径（不变式 2 + 契约测试 I1）。

#### 4.1.5 Repository 扩展（delta 单事务应用）

`ConversationRepository` 新增：

```kotlin
/**
 * 单事务应用一次会话变更：
 * 1. headerPatch → ConversationDAO 既有窄列方法（update_at 恒写）
 * 2. deletedNodeIds → MessageNodeDAO.deleteByIds + FavoriteDAO 清理（引用行由 node FK 级联自动清）
 * 3. upsertedNodes → MessageNodeDAO.upsertAll
 * 4. executionFacts → TurnExecutionDAO / ToolExecutionDAO upsert（事务内）
 * 5. 事务提交后（投影，允许最终一致）：
 *    - MessageFtsManager.reindexNodes / deleteNodesIndex（变更节点）
 *    - ArtifactStore.syncReferences(conversationId, upsertedNodes, deletedNodeIds)
 * 返回是否实际写入。
 */
suspend fun applyMutation(mutation: ConversationMutation, executionFacts: ExecutionFacts? = null): Boolean
```

其他持久化入口的引用维护职责（避免投影悬挂）：

| 入口 | 引用动作 |
| --- | --- |
| `insertConversation` / `insertConversationTree`（fork 直建） | 新会话创建 → `syncReferences(id, 全部节点, empty)` 登记 |
| `updateConversationTree`（fork 收缩） | 树更新事务内不处理；事务后 `syncReferences(id, retained 节点, deleted 节点)` + 触发 GC |
| `deleteConversation` / `deleteChildConversations` | 行删除 → node FK 级联自动清引用；事务后触发 GC |
| `applyMutation` | 见上 |

替代 GC 签名：

```kotlin
/**
 * GC：回收 state=ACTIVE 且 artifact_reference 无引用、created_at 超过保护窗口
 * （默认 24h，防误删刚上传未发送文件）的 artifact。回填未完成时保守跳过。
 */
suspend fun collectUnreferencedArtifacts(protectionWindowMillis: Long = 24 * 3600 * 1000L): List<ArtifactEntity>
```

`updateConversation(conversation)` 标注 `@Deprecated("导入/迁移专用")`，运行时零调用由契约测试 I1 锁定。

#### 4.1.6 迁移矩阵：现有写路径 → 命令

| 现有路径 | 目标 |
| --- | --- |
| `ChatService.updateConversation / updateConversationState / mergeSessionConversation`（私有） | 删除；调用方改提交命令 |
| `ChatService.persistLoadedConversation / persistTurnCheckpoint`（私有） | 迁入 Runtime.persist（私有化） |
| `ChatService.moveConversationToFolder / deleteFolder / updateConversationTitle / togglePinStatus` | `UpdateHeader(folderId=...)` 等（公开签名不变） |
| `ChatService.sendMessage` 内 append 用户消息 | `AppendUserMessage` |
| collect 体内 chunk 收集 | `applyStreamingDelta` |
| collect 体内 onCheckpoint | `CommitCheckpoint` |
| `finalizeMasterTurn` | `FinalizeTurn` |
| `ChatService.editMessage / selectMessageNode / deleteMessage / regenerateAtMessage` | `EditMessageVariant / SelectNodeVariant / DeleteMessage / TruncateToNodeIndex` |
| `compressConversation` | `ReplaceMessageTree` + 触发 GC |
| `recoverInterruptedTurns / finishInterruptedPendingTools` | `FinalizeTurn(closeInterruptedTools=true)` |
| `generateTitle / generateSuggestion` | `UpdateHeader(title=...) / UpdateHeader(suggestions=...)` |
| `handleToolApproval` | `UpdateToolApproval` + resume 走 TurnEngine |
| `ChatVM.updateConversationState` 调用（customSystemPrompt / modeInjectionIds / workspaceCwd / assistantId 迁移） | `UpdateHeader` 对应三态字段 |
| 子助手 checkpoint / 终态提交（Coordinator 内） | 同一命令集，经 TurnEngine |
| ChatService 顶层 `inputTransformers` / `outputTransformers` val + `handleMessageComplete` 装配 buildList | `TurnPipelineFactory.masterInput` + `BASE_OUTPUT`（§4.2） |
| Coordinator `runTargetGeneration` 内硬编码 `targetInputTransformers` / `targetOutputTransformers` 列表 | `TurnPipelineFactory.targetInput` + `BASE_OUTPUT`（§4.2） |
| `materializeMediaForPersistence`（终态 IO） | TurnEngine 构造 `FinalizeTurn` 前的命令构造前 IO 段（§4.2；reducer 保持零 IO） |

### 4.2 TurnEngine（chunk → Runtime 提交协议，唯一实现）

主/子对话的重用边界（用户裁决：共同逻辑必须只有一份实现，不得分流）：

| 环节 | 重用方式 |
| --- | --- |
| chunk → Runtime 提交协议 | `TurnEngine.bind` 唯一实现（Master 与 Target 共用） |
| transformer 管道清单 | `TurnPipelineFactory` 唯一实现（见下；现状两侧各写一份清单：Master 8 输入（含可选 replay）/ Target 7 输入，输出 3 项完全相同，基础 4 输入完全重复） |
| 工具集装配 | `GenerationToolSetFactory` 唯一实现（现有，`ToolSetRunMode` 参数化） |
| 终态收口 | `FinalizeTurn` reducer 语义唯一实现 |
| 崩溃恢复收口 | Master `recoverInterruptedTurns` 与 Target `SubAssistantRecovery` 的收口动作统一为 `runtime.submitGeneration(FinalizeTurn(closeInterruptedTools=true))`——同一命令路径，恢复扫描逻辑各自保留（扫描输入不同：全库 vs Child） |
| 上下文解析（assistant/model/memories/attachment） | 留在各自 Application 组件（Master 读当前会话上下文；Target 经 `resolveSubAssistantRunSpec` 解析委派上下文——这是真实业务差异，合并反而制造参数膨胀） |
| 多步循环 | Target 的 `while(true)` 多步循环（ask_user 交互后再驱动）保留在 `DelegationCoordinator`——循环每步调用一次 `bind`，循环本身是委派语义不是提交协议 |

**TurnPipelineFactory**（与 TurnEngine 同文件，不新增文件）：

```kotlin
/**
 * Master / Target 共用的 Turn 管道装配。
 * 消除现状两处重复清单：ChatService 顶层 inputTransformers/outputTransformers
 * 与 SubAssistantCoordinator 内 Target 生成执行段的硬编码列表。
 * 顺序差异是既有行为，平移保持（不合并顺序——两轨顺序不同是有意设计，
 * 见 sub-assistant-architecture.md）。
 */
object TurnPipelineFactory {

    /** 基础输入变换（两轨现状完全一致的前缀，原 ChatService 顶层 inputTransformers） */
    val BASE_INPUT: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
    )

    /** 基础输出变换（两轨现状完全一致，原 ChatService 顶层 outputTransformers） */
    val BASE_OUTPUT: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** Master 管道（顺序对齐现 ChatService.handleMessageComplete 装配段） */
    fun masterInput(
        templateTransformer: TemplateTransformer,
        workspaceReminderTransformer: WorkspaceReminderTransformer,
        toolArtifactReplayTransformer: ToolArtifactReplayTransformer?,
    ): List<InputMessageTransformer> = buildList {
        addAll(BASE_INPUT)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        toolArtifactReplayTransformer?.let(::add)
        add(AttachmentProjectionTransformer)
    }

    /** Target 管道（顺序对齐现 SubAssistantCoordinator 装配段；无 toolArtifactReplay） */
    fun targetInput(
        templateTransformer: TemplateTransformer,
        workspaceReminderTransformer: WorkspaceReminderTransformer,
    ): List<InputMessageTransformer> = buildList {
        addAll(BASE_INPUT)
        add(AttachmentProjectionTransformer)
        add(templateTransformer)
        add(workspaceReminderTransformer)
    }
}
```

`TurnEngine.kt`：

```kotlin
class TurnEngine(private val appEventBus: AppEventBus) {

    /**
     * 创建一次 turn 的提交会话。
     * Master 与 Target 共用同一实现（提交协议唯一化）。
     * [session.onCheckpoint] 作为 `generateText(onCheckpoint=…)` 参数传入——
     * 现状持久化边界是 GenerationHandler 的 onCheckpoint 回调（awaited durability
     * boundary，见修订记录 3），Flow 中的 GenerationChunk.Checkpoint 仅是事件通知。
     */
    fun beginTurn(runtime: ConversationRuntime, turnId: Uuid, assistantMessageId: Uuid): TurnSession

    /** 把 GenerationChunk 流绑定到已建立的提交会话（冷流，collect 触发执行）。 */
    fun bind(session: TurnSession, source: Flow<GenerationChunk>): Flow<TurnEvent>
}

/**
 * 单次 turn 的提交会话。装配方（ChatService / DelegationCoordinator）在调用
 * `generateText` 前创建并持有，把 [onCheckpoint] 透传给 GenerationHandler。
 */
class TurnSession(
    val runtime: ConversationRuntime,
    val turnId: Uuid,
    val assistantMessageId: Uuid,
) {
    /** 交给 generateText(onCheckpoint=…) 的回调：将 GenerationCheckpoint 落为 CommitCheckpoint 命令。 */
    suspend fun onCheckpoint(checkpoint: GenerationCheckpoint)
    // TurnExecutionStatus / ToolExecutionEntity 映射 = 现 persistTurnCheckpoint 语义
    // toolExecution = checkpoint.toolExecution；turnStatus 由 kind 推导（STEP_COMPLETED→RUNNING、TERMINAL_STATE→终态）
}

sealed interface TurnEvent {
    /** 流式快照（已投递 Runtime；事件仅供调用方做 UI 副作用） */
    data class Streaming(val lastMessage: UIMessage) : TurnEvent
    /** 阶段变化（Phase 透传现有顶层 GenerationChunk.Phase；Target 用其更新 card 状态） */
    data class Phase(val phase: String, val toolName: String?) : TurnEvent
    /** checkpoint 已提交；kind 透传现有顶层 CheckpointKind 枚举 */
    data class Checkpoint(val kind: CheckpointKind) : TurnEvent
    /** 终态；outcome 沿用现 ChatService 内 internal enum MasterTurnOutcome（SUCCESS/AWAITING_APPROVAL/…） */
    data class Finished(
        val outcome: MasterTurnOutcome,
        val finalizedReason: FinishedReason?,
        val error: Throwable?,
    ) : TurnEvent
}
```

`bind` 内部职责（对齐现 ChatService.collect 与 Coordinator collect 的共同段）：
- `GenerationChunk.Messages` → `runtime.applyStreamingDelta` + `appEventBus.tryEmit`（通知事件，保持现状）+ `emit(Streaming(last))`。
- `GenerationChunk.Phase` → `emit(Phase(phase, toolName))`（Target 消费更新 card 状态）。
- `GenerationChunk.Checkpoint` → `emit(Checkpoint(kind))`（真正的落库已由 `session.onCheckpoint` 在 generateText 内执行）。
- `Finished` / 异常 → 先做命令构造前 IO（媒体 materialize：base64 图片落盘，对齐现 `materializeMediaForPersistence`；reducer 保持零 IO），再 `runtime.submitGeneration(FinalizeTurn)`——终态纯变换（finishReasoning / closeOpenTools / markAssistantTerminal）在 reducer 内完成。
- 取消（`runtime.requestCancel` 触发）→ `FinalizeTurn(CANCELLED, reason)` 后重抛。

调用方形态：

- `ChatService.launchRun(conversationId, turnId, assistantMessageId, senderName)`（私有）：装配（`TurnPipelineFactory.masterInput` + `GenerationToolSetFactory`，替换原顶层 transformer val 与 buildList 装配段）→ `val session = turnEngine.beginTurn(runtime, turnId, assistantMessageId)` → `generationHandler.generateText(..., onCheckpoint = session::onCheckpoint)`（签名不变）→ `turnEngine.bind(session, flow).collect { event -> handleTurnEvent(...) }`。`handleTurnEvent` 承接现 collect 体的业务副作用：标题生成决策、建议生成、TTS 队列、通知音效、autoTurnCompression 决策。
- `DelegationCoordinator`：preflight/lineage/lease 不动 → `resolveSubAssistantRunSpec` 解析上下文 → `TurnPipelineFactory.targetInput` → 同一 `generateText`（同一 `onCheckpoint` 传参）→ 同一 `bind` → collect 做子助手专有副作用（ask_user 桥接转发、审批转发、交付物登记、card Phase 更新）；多步循环（ask_user 应答后再驱动）保留在本组件，每步一次 beginTurn + bind。

双轨消除的本质：两份 collect 体中"提交 Runtime"的每行代码只剩 TurnEngine 一份（Master 经 onCheckpoint 回调、Target 经同一回调与同一 bind 内部提交段）；两份装配清单只剩 TurnPipelineFactory 一份；业务副作用与上下文解析保留在各自 Application 组件（它们本来就该不同）。

**Target 执行事实注意事项（修订记录 6）**：Target 走 TurnEngine 后开始写 `turn_execution`/`tool_execution`（现状 Target checkpoint 只窄列写）。Master 的全库恢复扫描 `recoverInterruptedTurns` 必须过滤 Child 会话（`parent_conversation_id IS NOT NULL`）的 turn——Child 的恢复由 `SubAssistantRecovery` 全权收口，避免双路径收口同一 Child turn。

### 4.3 ArtifactStore（合并 ManagedFileDeletionService + FilesRepository）

一个类承载 Artifact 域全部事实与投影职责（引用投影维护与生命周期协议共享同一批私有函数——token 提取、路径映射）。修订记录 2：合并对象为 `ManagedFileDeletionService` + `data/repository/FilesRepository.kt`（现状 DB CRUD 包装，非 `ManagedFileRepository`；磁盘编排仍在 `FilesManager`）。

```kotlin
sealed interface ArtifactDeleteResult {
    data class Completed(val artifactId: Long) : ArtifactDeleteResult
    /** 已在进行中（DELETING）或已完成（行不存在）；payload 告知当前态 */
    data class Rejected(val artifactId: Long, val reason: RejectionReason) : ArtifactDeleteResult
    data class Failed(val artifactId: Long, val reason: String) : ArtifactDeleteResult

    enum class RejectionReason { IN_PROGRESS, ALREADY_DELETED }
}

class ArtifactStore(
    private val filesManager: FilesManager,        // 纯磁盘 payload 操作
    private val artifactDAO: ArtifactDAO,
    private val artifactReferenceDAO: ArtifactReferenceDAO,
    private val systemMetaDAO: SystemMetaDAO,
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,    // backfill 逐会话加载 message_node
    private val settingsStore: SettingsStore,      // Assistant 背景图解除（detachMutableReferences 保留）
) {
    // ---- 元数据（吸收 FilesRepository 与 FilesManager.trackManagedFile 私有逻辑） ----
    fun observe(folder: String): Flow<List<ArtifactEntity>>
    suspend fun list(folder: String): List<ArtifactEntity>
    suspend fun getById(id: Long): ArtifactEntity?
    suspend fun getByRelativePath(relativePath: String): ArtifactEntity?
    /** 上传产物登记（原 FilesManager.trackManagedFile + FilesRepository.insert 语义合并） */
    suspend fun registerCreation(
        folder: String, relativePath: String, displayName: String,
        mimeType: String, sizeBytes: Long,
    ): ArtifactEntity

    // ---- 引用投影 ----
    /**
     * delta 同步，替换语义：
     *  - upsertedNodes：先 deleteByNodeIds(该批 node) 再 insertAll(重算引用)
     *    （node 变更后可能不再引用某文件，仅 INSERT 无法移除旧行）
     *  - deletedNodeIds：无需显式删除（node FK 级联），仅在 node 尚未物理删除的调用序中兜底
     * 由 ConversationRepository 各持久化入口在事务外调用（投影，允许最终一致）。
     */
    suspend fun syncReferences(conversationId: Uuid, upsertedNodes: List<MessageNode>, deletedNodeIds: List<Uuid>)
    suspend fun backfillReferences()          // §3.5 协议；幂等
    fun isBackfilled(): Boolean

    // ---- 影响检查（替换全量扫描） ----
    /** 引用的会话列表（含 child 标记）。回填未完成回退旧全量扫描。返回类型沿用现 ManagedFileDeleteImpact（嵌套类迁至本类）。 */
    suspend fun inspect(artifact: ArtifactEntity): ManagedFileDeleteImpact

    // ---- 生命周期协议（CAS 幂等，无 operationId） ----
    /**
     * 协议（依赖 §3.2 幂等屏障）：
     * 1. compareAndSetState(ACTIVE → DELETING)；返回 0 行 → Rejected(IN_PROGRESS / ALREADY_DELETED)
     * 2. 解除 Assistant 背景图等 Settings 可变引用（失败 → state 回滚 ACTIVE，返回 Failed）
     * 3. 删磁盘文件（filesManager）
     * 4. 删 artifact 行（artifact_reference 经 FK 级联消失）
     * 目录删除：对目录内每个 ACTIVE artifact 逐一走同一协议（单文件原子性，目录级尽力而为）。
     */
    suspend fun deletePermanently(artifact: ArtifactEntity): ArtifactDeleteResult
    suspend fun deleteFolderPermanently(folder: String): ArtifactDeleteResult

    // ---- 启动恢复（替代 FilesManager.syncFolder 的启动调用） ----
    /**
     * 每次冷启动执行一次（成本：artifact 全表读 + 每文件一次 stat，文件数为百级时毫秒级）：
     *  - state=DELETING → 续删（步骤 3-4）
     *  - state=ACTIVE 且磁盘缺失 → 删除行（文件已不存在，记录是死数据；引用行经 FK 级联消失）
     *  - 磁盘存在但 DB 无记录 → 仅日志，绝不自动补录
     * （修订记录 10：无 MISSING 中间态、无 rescanUntracked 补录入口——
     *   应用侧写入路径已原子化，untracked 只可能来自外部，DB 是唯一事实源）
     */
    suspend fun reconcileStartup()
}
```

删除时序不变量（任意时刻进程死亡，重启后 `reconcileStartup` 收敛）：

| 死亡时刻 | 磁盘 | DB | 重启动作 |
| --- | --- | --- | --- |
| CAS 置 DELETING 前 | 在 | ACTIVE | 无变化，安全 |
| DELETING、文件未删 | 在 | DELETING | 续删 |
| 文件已删、行未删 | 缺 | DELETING | 删行（引用级联），完成 |
| 行已删 | 缺 | 无行 | 完成（无残留状态） |

`FilesManager` 收缩为纯 payload IO + 登记门面：文件创建/写入/base64 落盘/删除/导出；metadata 登记经私有 `registerTrackedFile`（同步失败回滚删文件，修订记录 10）直连 `ArtifactDAO`（E4 备选分支裁决：`ArtifactStore` 构造依赖 `FilesManager`，反向注入成环，故登记门面留在 `FilesManager`、`ArtifactStore` 聚焦引用投影与生命周期）。上传/保存的公开方法签名除 suspend 化外不变，调用方以协程包裹适配。

### 4.4 Transformer 双通道

`Transformer.kt` 新增：

```kotlin
/** 流式变换器：只处理 active assistant 消息。历史消息在流式期间 immutable。 */
interface StreamingMessageTransformer {
    suspend fun transformStreaming(ctx: TransformerContext, message: UIMessage): UIMessage
    suspend fun onStreamingFinish(ctx: TransformerContext, message: UIMessage): UIMessage
}
```

- `ThinkTagTransformer` / `RegexOutputTransformer` 实现该接口（其现有 transform 本就逐条独立处理，单消息化等价）；保留请求级实现。
- `GenerationHandler` 流式收集路径：`messages.dropLast(1) + streamingTransformers.fold(messages.last()) { acc, t -> t.transformStreaming(ctx, acc) }`；终态路径同只处理最后一条。
- 其余 13 个 transformer 保持请求级（每 model step 一次 O(N) 合理）。
- `GenerationChunk.Messages` 对外形状不变。

### 4.5 FTS 增量索引

`MessageFtsManager` 新增：

```kotlin
/** 节点级增量：删旧 FTS 行 + 插入当前内容行（message_id 前缀 "<convId>:nodeId:" 对齐现有 schema）。 */
suspend fun reindexNodes(conversationId: String, title: String, updateAt: Long, nodes: List<MessageNode>)

/** 节点删除的索引清理。 */
suspend fun deleteNodesIndex(conversationId: String, nodeIds: List<Uuid>)
```

调用收敛：`applyMutation` → `reindexNodes(upsertedNodes)` + `deleteNodesIndex(deletedNodeIds)`；全量 `indexConversation` 仅剩两个入口（`insertConversation` 首建、设置页手动重建 `rebuildAllIndexes(onProgress)`，后者在 `ConversationRepository` 保持现有签名）。

### 4.6 UI 消费侧

**ChatVM**：

```kotlin
val snapshot: StateFlow<ConversationSnapshot> = chatService.getConversationSnapshot(conversationId)

fun submit(command: ConversationCommand) = viewModelScope.launch {
    chatService.submitConversationCommand(conversationId, command)
}
```

**ChatService 新增桥接**（公开，供 VM）：

```kotlin
fun getConversationSnapshot(conversationId: Uuid): StateFlow<ConversationSnapshot>
suspend fun submitConversationCommand(conversationId: Uuid, command: ConversationCommand): Conversation
```

**ChatList**：items 数据源 `conversation.messageNodes` → `snapshot.nodes`（key = node.id 不变）；未变节点引用相同 → Compose skip 真正生效。

**SettingFilesPage**：`DeleteState { Idle; Confirming(target); Executing(target); Failed(reason) }`；进入 Executing 即禁用确认按钮（UX 层防重入）；底层幂等由 CAS 保证（4.3），重复请求返回 `Rejected` 时按 IN_PROGRESS（"删除进行中"）/ ALREADY_DELETED（"已删除，视为成功"）分别提示，不报错。（修订记录 10：无 state 徽标——磁盘缺失行由 reconcile 清行，文件页不存在 MISSING 展示态。）

### 4.7 性能设计（四大机制）

| # | 机制 | 之前 | 之后 | 架构保证 |
| --- | --- | --- | --- | --- |
| 1 | **写放大** | 每 checkpoint 全量 REPLACE N 节点（O(N×K)） | `applyMutation` 只 upsert changed 节点（O(changed×K)） | reducer structural sharing 契约 + 契约测试 I2 |
| 2 | **读放大** | 删除影响检查 / GC 全库反序列化 JSON | `artifact_reference` 索引查询（EXISTS / join） | 投影由 mutation 管道自动维护，无人工同步点 |
| 3 | **渲染** | 每 chunk 全列表 map + 整树 copy 发布 | 单消息流式变换 + snapshot 引用稳定 + StateFlow conflation 按 frame 合并 | `StreamingMessageTransformer` 接口隔离 + 契约测试 I5 |
| 4 | **FTS** | 每轮 finalize 全量重建 | node 级增量（同一 dirty set） | 投影与 mutation 同管道 + 契约测试 I6 |

频次账：checkpoint 写从"每轮 O(N) 行"降为"每轮 O(changed) 行"（changed 通常 = 1 个 active node）；inspect 从"全库消息反序列化"降为"一次索引 EXISTS"；FTS 从"每轮 O(N) 行重建"降为"每轮 O(changed) 行"；流式变换从"每 chunk O(M)"降为"每 chunk O(1) + 每 frame 一次 O(N) 浅 copy"。

明确不做（防修补蔓延）：SQLite PRAGMA 调参、缓存层、消息懒加载、Compose 层重写。

---

## 5. 执行计划

> 工作流 A–J 按依赖序执行，同一版本交付。每项精确到文件与函数签名；"新增/修改/删除"指对当前 master 的动作。

### 工作流 A：数据库 v6（地基）

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| A1 | 改名 | `ManagedFileEntity.kt` → `ArtifactEntity.kt` | + `ArtifactState` 枚举 + `state` 列 + `Index("state")`（§3.3） |
| A2 | 新增 | `data/db/entity/ArtifactReferenceEntity.kt` | §3.3（双 FK 级联；reference_type 仅 ATTACHMENT / TOOL_OUTPUT） |
| A3 | 新增 | `data/db/entity/SystemMetaEntity.kt` | §3.3 |
| A4 | 新增 | `data/db/migrations/Migration_5_6.kt` | §3.4 逐条 DDL |
| A5 | 修改 | `AppDatabase.kt` + `di/DataSourceModule.kt` | `AppDatabase`：version=6；entities 两增一换；DAO getter 更名新增（沿用小写驼峰：`artifactDao()` / `artifactReferenceDao()` / `systemMetaDao()`）。`DataSourceModule`：`managedFileDao()` 改 `artifactDao()`；`.addMigrations(..., Migration_5_6)` 追加（迁移注册实际位置） |
| A6 | 改名 | `ManagedFileDAO.kt` → `ArtifactDAO.kt` | 表名 `artifact`（@Query SQL 全量 `managed_files`→`artifact`）；现有方法保留；+ `compareAndSetState` / `listByState` / `getById` |
| A7 | 新增 | `ArtifactReferenceDAO.kt` / `SystemMetaDAO.kt` | §3.6 |
| A8 | 修改 | `MessageNodeDAO.kt` | + `upsertAll` / `deleteByIds` |
| A9 | 测试 | `app/src/androidTest/.../migrations/Migration_5_6Test.kt` | 用例集 M1–M8（见测试覆盖矩阵一：RENAME 数据完整、默认值、索引、FK 双级联、schema 校验、迁移/新建同构）。**基建**：`build.gradle.kts` `androidTest.assets.srcDirs("$projectDir/schemas")`（见修订记录 8）；级联用例 `PRAGMA foreign_keys = ON`；`connectedDebugAndroidTest` 运行 |

### 工作流 B：ConversationRuntime

依赖 A。

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| B1 | 新增 | `service/runtime/ConversationCommands.kt` | §4.1.2 全部 |
| B2 | 新增 | `service/runtime/ConversationReducer.kt` | §4.1.3；迁移来源函数逐个对照（updateCurrentMessages / closeOpenTools / finishReasoning / markAssistantTerminal / cancelToolByUser / interruptPendingTool / editMessage / deleteMessage / selectMessageNode / regenerate 树操作 / sanitizeForPersistence） |
| B3 | 改造+改名 | `ConversationSession.kt` → `ConversationRuntime.kt` | §4.1.4；删除 `persistMutex` / `stateRevision` / `persistedRevision` / `withPersistLock`（被 `commandMutex` 吸收；idle 守卫改用 `isWriteInFlight()`）；保留 acquire/release/generationJob/beginTurn/requestCancel/markTurnFinalized 签名 |
| B4 | 改名 | `ConversationSessionRegistry.kt` → `ConversationRuntimeRegistry.kt` | 类型重命名；缓存/逐出策略不变（idle 判定并入 isWriteInFlight） |
| B5 | 测试 | `ConversationReducerTest.kt` | 用例集 R-1–R-4（含 BeginTurn 新槽追加 / resume 幂等、structural sharing） |
| B6 | 测试 | `ConversationRuntimeTest.kt` | 用例 R-5（100 协程并发交错 submit 无丢失更新）；`applyStreamingDelta` 零 DB 调用且兼容投影含最新流式内容 |

### 工作流 C：持久化收敛

依赖 A、B。

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| C1 | 修改 | `ConversationRepository.kt` | + `applyMutation`（§4.1.5 单事务 + 事务后投影）；+ `collectUnreferencedArtifacts`；`insertConversation` / `insertConversationTree` / `updateConversationTree` / `deleteConversation` / `deleteChildConversations` 按 §4.1.5 引用维护表补登/触发 GC |
| C2 | 删除 | `ConversationRepository.kt` | `persistMessageNodes` / `saveMessageNodes` / `checkpointTurn` / `checkpointConversation` / `finalizeTurn` / `deleteUnreferencedChatFiles(retained)` / `fileReferenceTokensFor` / `findUnsharedFileUris` / `findUnsharedFilesForDeletion`（逻辑分别并入 `applyMutation` / `ArtifactStore`） |
| C3 | 修改 | 同上 | `updateConversation` 标 `@Deprecated("导入/迁移专用")` |
| C4 | 修改 | `MessageFtsManager.kt` | + `reindexNodes` / `deleteNodesIndex`（§4.5）；现有方法保留 |
| C5 | 测试 | `ConversationRepositoryMutationTest.kt` | 用例集 C-1–C-8（见测试覆盖矩阵四：零写入断言、delta 命中、级联清理、执行事实同事务、失败重试、替换语义、fork 登记、GC 窗口） |

### 工作流 D：TurnEngine 与 ChatService 收缩

依赖 B、C。

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| D1 | 新增 | `service/runtime/TurnEngine.kt` | §4.2：`beginTurn(runtime, turnId, assistantMessageId): TurnSession`（含 `session.onCheckpoint`）+ `bind(session, source): Flow<TurnEvent>` + `TurnEvent`（含 `Phase`）+ `TurnPipelineFactory`（`BASE_INPUT` / `BASE_OUTPUT` / `masterInput` / `targetInput`，同文件） |
| D2 | 修改 | `ChatService.kt` | ① + `launchRun(...)`（私有：装配 `TurnPipelineFactory.masterInput` + `beginTurn` → `generateText(onCheckpoint = session::onCheckpoint)` → `bind(...).collect { handleTurnEvent(it) }`；删除顶层 `inputTransformers` / `outputTransformers` val 与装配 buildList）；② + `getConversationSnapshot` / `submitConversationCommand`（公开桥接）；③ 删除清单见附录 A 第 1 组；④ `sendMessage` 等公开方法改为提交命令（§4.1.6 矩阵），**公开签名不变** |
| D3 | 改名+收缩 | `SubAssistantCoordinator.kt` → `service/runtime/DelegationCoordinator.kt` | 执行段改走 TurnEngine（装配改用 `TurnPipelineFactory.targetInput`，同一 `beginTurn` + `generateText(onCheckpoint=…)` + `bind`，删除 `runTargetGeneration` 内硬编码 transformer 列表）；preflight / lineage / lease / `SubAssistantRecovery` / ask_user 桥接 / 审批转发 / 多步循环原样保留；崩溃恢复收口改提交 `FinalizeTurn(closeInterruptedTools=true)`（与 Master 同一命令路径）。**Target 开始写执行事实**：Master 全库恢复扫描 `recoverInterruptedTurns` 须过滤 Child 会话的 turn（`parent_conversation_id IS NOT NULL`），Child 由 `SubAssistantRecovery` 收口（修订记录 6） |
| D4 | 修改 | `GenerationHandler.kt` | 流式/终态变换切单消息通道（§4.4）；`generateText` 签名不变 |
| D5 | 修改 | `Transformer.kt` + `ThinkTagTransformer.kt` + `RegexOutputTransformer.kt` | + `StreamingMessageTransformer`；两个实现 |
| D6 | 测试 | `TurnEngineTest.kt` | fake Flow 驱动：chunk→Streaming 顺序、checkpoint→CommitCheckpoint 次数、Finished→FinalizeTurn、异常→FinalizeTurn(FAILED) 不上抛、取消→FinalizeTurn(CANCELLED) 后重抛；含用例 T-1（Master/Target 提交协议等价） |
| D7 | 适配 | `ChatServiceTest` / `ChatServiceConversationWriteTest` / `SubAssistant*Test` | 写路径断言改为命令语义；**全部既有用例必须通过（行为等价回归）** |
| D8 | 测试 | `TurnPipelineFactoryTest.kt` | 装配等价性锁定：`masterInput` 输出与现 ChatService 装配段逐项一致（含顺序、toolArtifactReplay 为 null 与非 null 两分支）；`targetInput` 与现 Coordinator 硬编码列表逐项一致（含顺序）；`BASE_OUTPUT` 与现顶层 `outputTransformers` 一致 |

### 工作流 E：ArtifactStore

依赖 A（可与 B/C/D 并行）。

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| E1 | 新增 | `data/files/ArtifactStore.kt` | §4.3 全部职责（合并吸收 `ManagedFileDeletionService` + `data/repository/FilesRepository.kt`，修订记录 2） |
| E2 | 删除 | `ManagedFileDeletionService.kt` | 逻辑并入 E1：`inspect`（投影查询 + 回退）、`collectFileUrlStrings`、`detachMutableReferences`、删除协议（CAS 版） |
| E3 | 删除 | `data/repository/FilesRepository.kt` | DB CRUD 职责并入 E1（非 `ManagedFileRepository`，该文件不存在；修订记录 2）。`ArtifactStore` 构造注入 `ArtifactDAO` 替代 `FilesRepository` |
| E4 | 收缩 | `FilesManager.kt` | 纯磁盘 IO；metadata 登记保留 `ArtifactDAO` 直连（E4 备选分支裁决：`FilesManager` 无法注入 `ArtifactStore`——后者依赖前者，构造注入成环；登记走私有 `registerTrackedFile` 同步失败回滚，修订记录 10）；`syncFolder` 删除（补录能力整体移除，修订记录 10） |
| E5 | 修改 | `MeasixPilotApp.kt` | `syncManagedFiles()` 内 `filesManager.syncFolder()` → `artifactStore.reconcileStartup()`（无 INSERT 补录路径）；其后启动 `backfillReferences()`（Application scope，不阻塞 UI；修订记录 10：无 `rescanUntracked` 后续入口——补录能力整体移除） |
| E6 | 修改 | `di/AppModule.kt` / `di/RepositoryModule.kt`（Koin） | 新增注册：`ArtifactStore`（依赖 ArtifactDAO / ArtifactReferenceDAO / SystemMetaDAO / ConversationDAO / MessageNodeDAO / FilesManager / SettingsStore）、`TurnEngine`、`ConversationRuntimeRegistry`；移除旧绑定：`ManagedFileDeletionService`、`FilesRepository`（`FilesManager` 构造注入同步调整） |
| E7 | 修改 | `SettingFilesPage.kt` | `DeleteState` 防重入 + `Rejected` 结果处理（修订记录 10：无 state 徽标、无"重新扫描"入口） |
| E8 | 修改 | `values/strings.xml` + zh/ja/ko/ru | 删除流程新增键全量同步（修订记录 10：`files_page_state_missing` 与 `files_page_rescan_*` 键随功能移除，不入库） |
| E9 | 测试 | `ArtifactStoreTest.kt` | 用例集 BF1–BF5（回填）+ RS1–RS5（启动恢复，即 §4.3 时序表四行的机器可执行版）+ 并发 20 次 `deletePermanently` → 单次副作用 + CAS 二次调用返回 Rejected(IN_PROGRESS) + 行删除后再调返回 Rejected(ALREADY_DELETED)；存量 `ManagedFileDeletionServiceTest` 用例迁移并入 |

### 工作流 F/G：FTS 与 UI 消费切换

依赖 C4 / D2。

| # | 动作 | 内容 |
| --- | --- | --- |
| F1 | 核查 | 全库仅 `insertConversation` 与 `rebuildAllIndexes` 调用 `indexConversation`；finalize 全量索引路径随 D2 消失 |
| F2 | 测试 | `MessageFtsManagerIncrementalTest.kt`：用例集 F-1–F-4（单 node 变更范围、删除清空、增量==全量 rebuild、会话删除回归） |
| G1 | 修改 | `ChatVM.kt`：+ snapshot 订阅 + `submit(command)`；`updateConversationState` 调用改 `UpdateHeader`；`moveConversationToAssistant` 改 `UpdateHeader(assistantId)` |
| G2 | 修改 | `ChatList.kt`：数据源 → `snapshot.nodes` |
| G3 | 验证 | 长会话（数百节点）流式生成：Layout Inspector 确认历史列表项零重组 |

### 工作流 H：SubAssistant 全链路回归

依赖 D3。

| # | 内容 |
| --- | --- |
| H1 | 创建/审批/撤权/恢复/附件入站/交付出站全链路手测（对照 `sub-assistant-architecture.md` / `sub-assistant-multimodal.md` 行为清单） |
| H2 | 崩溃恢复：生成中 kill → 重启 `recoverInterruptedExecutions` + `FinalizeTurn(closeInterruptedTools=true)` 收口（Master 与 Child 两条恢复路径同一命令路径），工具状态无悬挂 |
| H3 | `SubAssistantCoordinatorTest` 等存量测试全通过 |
| H4 | 主/子重用等价性回归：用例 T-1（相同 chunk 序列 → Master/Target 提交命令序列一致）+ P-1–P-3（管道等价）全绿 |

### 工作流 I：契约测试（常驻架构 gate）

依赖 C/D/E。目录 `app/src/test/.../architecture/`。

| # | 测试 | 断言 |
| --- | --- | --- |
| I1 | `SingleWriterContractTest` | CI grep：`conversationDAO` 写方法、`messageNodeDAO.insertAll/upsertAll` 仅被 `ConversationRepository` 引用；`updateConversation(Conversation)` 仅导入/迁移入口引用 |
| I2 | `CheckpointWriteAmplificationTest` | 500 历史节点 + 50 次 `CommitCheckpoint`（每次只动 active node）：累计 upsert 行数不随历史增长 |
| I3 | `FolderOwnershipTest` | streaming delta 与 `UpdateHeader(folderId)` 交错 → DB `folder_id` 终值为命令值 |
| I4 | `DestructiveIdempotencyTest` | 20 并发 `deletePermanently` → 磁盘删除恰好一次；状态机收敛无中间态残留（CAS 保证，与调用顺序无关） |
| I5 | `StreamingTransformScopeTest` | 5000 chunk 流式期间历史消息进入 `transformStreaming` 次数为 0 |
| I6 | `FtsDeltaScopeTest` | 单节点修改 → FTS 变更行仅属该节点 |

### 工作流 J：清理与文档

| # | 动作 | 内容 |
| --- | --- | --- |
| J1 | 删除 | 附录 A 全部条目；核查并删除无引用项：`ConversationDAO.searchConversations`（非分页版）、`resetConversationNodes`、`getAllTopLevelConversationsSync`、`getAllChildConversationIds`（确认除旧 inspect/Recovery 外无消费后删；`SubAssistantRecovery` 若依赖则保留并注明）、`LightConversationEntity` 未用字段 |
| J2 | 更新 | `docs/references/chat-generation-pipeline.md`：链路改为 ChatService→TurnEngine→Runtime；checkpoint 段改 delta 语义 |
| J3 | 更新 | `sub-assistant-architecture.md` / `workspace-architecture.md`：Coordinator 命名与执行入口同步（仅命名与调用关系） |
| J4 | 更新 | `AGENTS.md`：Module Structure 表 + 架构不变式 + 语义映射表引用 |
| J5 | 更新 | `changelog.md` ，版本号递增为18，在全过程中稳定为18版本

### 依赖图

```text
A ──► B ──► C ──► D ──► F/G ──► H ──► I ──► J
│            │      │
└──► E ──────┴──────┘   (E 依赖 A，与 B/C/D 并行)
```

### 测试覆盖矩阵（数据库与持久化重点）

> 工作流条目（A9/B5/B6/C5/D6/D8/E9/F2/I1–I6）是测试文件实现入口；本矩阵是其用例全集，ID 在测试类内作为方法名前缀（如 `M1_dataPreservedAcrossRename`），保证用例可逐条追溯。

**一、Room 迁移（`Migration_5_6Test`，工作流 A9）**

| ID | 前置 | 操作 | 断言 |
| --- | --- | --- | --- |
| M1 | v5 库写入 managed_files 多行（覆盖多 folder / mime / 中文路径） | 执行 5→6 迁移 | `artifact` 表行数与每行列值逐字段相等（RENAME 零数据损伤） |
| M2 | 同上 | 迁移后查询 | 所有行 `state = 'ACTIVE'`（新列默认值生效） |
| M3 | 已迁移库 | `sqlite_master` 查询 | `index_artifact_state`、`index_artifact_relative_path`（重命名后的既有 UNIQUE）、`index_artifact_folder`（重命名后的既有索引）、`index_artifact_reference_artifact_id`、`index_artifact_reference_node_id`、唯一组合索引均存在；旧名 `index_managed_files_*` 不存在 |
| M4 | 已迁移库插入 `artifact_reference` 行 | 删除父 `artifact` 行 | 引用行级联消失（FK 路径 1） |
| M5 | 同上 | 删除父 `message_node` 行 | 引用行级联消失（FK 路径 2） |
| M6 | 已迁移库 | `system_meta` put→get 往返 | 值一致 |
| M7 | v5 库 | `runMigrationsAndValidate` | 通过（DDL 与 `@Entity` 注解一致——Room 迁移最常见翻车点，必须显式校验） |
| M8 | 分别构造"v5 迁移至 v6"与"直接 v6 新建"两个库 | schema dump 对比 | 两者 schema 完全一致（新装与升级路径同构） |

**二、引用回填（`ArtifactStoreTest` backfill 组，工作流 E9）**

| ID | 前置 | 操作 | 断言 |
| --- | --- | --- | --- |
| BF1 | flag 未置位；3 会话（含 1 Child）消息引用 2 个 artifact | `backfillReferences()` | 引用行按 node→artifact 正确登记（含 Child 会话） |
| BF2 | BF1 完成后 | 再次 `backfillReferences()` | 零新增行（唯一索引幂等） |
| BF3 | 第 2 个会话处理时注入 IO 异常 | 失败后重跑 | flag 未置位；重跑结果与一次成功完全一致（部分登记后崩溃可收敛） |
| BF4 | 消息含外链 URL / 无匹配 artifact 的 file URI | `backfillReferences()` | 忽略该 token 不报错、不产生引用行 |
| BF5 | flag 已置位 | `backfillReferences()` | 立即返回，零扫描零写入 |

**三、启动恢复（`ArtifactStoreTest` reconcile 组，工作流 E9；同时是 §4.3 时序不变量的机器可执行版）**

| ID | 前置 | 操作 | 断言 |
| --- | --- | --- | --- |
| RS1 | artifact state=DELETING、磁盘文件在 | `reconcileStartup()` | 文件删除、行删除（续删完成） |
| RS2 | state=DELETING、磁盘文件缺 | `reconcileStartup()` | 行删除（完成悬挂删除） |
| RS3 | state=ACTIVE、磁盘文件缺 | `reconcileStartup()` | 行删除（死数据清理，引用级联消失；修订记录 10：无 MISSING 态） |
| RS4 | 磁盘存在 untracked 文件、DB 无记录 | `reconcileStartup()` | **不 INSERT**，artifact 行数不变（"重启复活"缺陷的回归锁定） |
| RS5 | artifact 表为空 | `reconcileStartup()` | 正常返回无异常 |

**四、delta 持久化（`ConversationRepositoryMutationTest`，工作流 C5；③④⑤为原有条目细化）**

| ID | 前置 | 操作 | 断言 |
| --- | --- | --- | --- |
| C-1 | 会话含 N 节点 | `applyMutation`（仅 headerPatch） | `message_node` 零写入（DAO 调用计数断言） |
| C-2 | 会话含 N 节点 | `applyMutation`（append 1 节点） | 仅 1 行 upsert；其余 N 行零命中 |
| C-3 | 节点 A 有 favorite 与引用 | `applyMutation`（删除 A） | node 行删 + favorite 行删 + 引用行经 FK 级联消失 |
| C-4 | 注入 DAO 写异常 | `applyMutation`（含 executionFacts） | 事务回滚：nodes 与 turn/tool_execution 均无残留行（执行事实与消息树同事务） |
| C-5 | 持久化失败一次 | 再次 submit 任意命令 | 重试差异包含上次未落盘变更（失败不丢 delta） |
| C-6 | node A 原引用 artifact X，checkpoint 后消息不再含 X | `applyMutation` + `syncReferences` | X 的引用行消失（替换语义，非纯 INSERT） |
| C-7 | fork 源会话含引用 | `insertConversationTree` | 新会话全部节点引用登记（fork 入口无悬挂） |
| C-8 | 会话含 ACTIVE 无引用文件，created_at 超 24h | `collectUnreferencedArtifacts()` | 回收；同会话内有引用文件保留；窗口内文件保留；flag 未置位时跳过 |

**五、Runtime / Reducer（`ConversationReducerTest` / `ConversationRuntimeTest`，工作流 B5/B6）**

| ID | 断言 |
| --- | --- |
| R-1 | `BeginTurn`（新 turn）：追加空 assistant 响应槽 + RUNNING 执行事实一次落库 |
| R-2 | `BeginTurn`（resume，槽已存在）：节点数不变、不重复追加 |
| R-3 | `FinalizeTurn(closeInterruptedTools=true)`：全部开放工具转终态、reasoning 收口、assistant 消息标记终态 |
| R-4 | 每条命令：未触及节点 `===` 原实例（structural sharing，B5 核心断言） |
| R-5 | 100 协程交错 submit：header 与节点无丢失更新（B6 核心断言） |

**六、主/子对话重用等价性（`TurnPipelineFactoryTest` + `TurnEngineTest`，工作流 D6/D8；H4）**

| ID | 断言 |
| --- | --- |
| P-1 | `masterInput` 与现 ChatService 装配段逐项一致（含顺序；toolArtifactReplay null/非 null 两分支） |
| P-2 | `targetInput` 与现 Coordinator 硬编码列表逐项一致（含顺序） |
| P-3 | `BASE_OUTPUT` 与现顶层 `outputTransformers` 一致 |
| T-1 | Master 与 Target 以相同 fake `GenerationChunk` 序列驱动 `TurnEngine.bind` → 两者对 Runtime 提交的命令序列完全一致（提交协议单一实现的直接锁定） |

**七、FTS 增量（`MessageFtsManagerIncrementalTest`，工作流 F2）**

| ID | 断言 |
| --- | --- |
| F-1 | 单 node 修改 → FTS 变更行仅属该 node（前后行快照 diff） |
| F-2 | node 删除 → 该 node 索引行清空 |
| F-3 | 增量索引最终态 == 全量 rebuild 结果（投影一致性） |
| F-4 | 会话删除 → FTS 行全清（现有行为回归） |

---

## 6. 验收标准

### 6.1 功能验收

1. `gradlew test` 全部通过；存量套件（`ChatServiceTest` / `ChatServiceConversationWriteTest` / `SubAssistant*` / `ManagedFileDeletionServiceTest` 迁移版）行为等价。
2. 工作流 I 全部契约测试常驻；测试覆盖矩阵（M/BF/RS/C/R/P/T/F 全部用例）方法级可追溯。
3. H1/H2/H4 手测与等价性回归通过。

### 6.2 缺陷消除验收

| 原现象 | 验收 |
| --- | --- |
| 长会话生成卡顿 | I2 + I5 + G3：checkpoint 写入与历史规模解耦；流式期间历史节点零变换零重组 |
| 删除文件重启复活 | E5 + E9 + 修订记录 10：启动无 INSERT 路径、写入路径原子化（登记失败回滚）；reconcile 仅续删/清行/日志 |
| 删除重复点击 DB 异常 | I4：并发全收敛为单次副作用，无 operationId 依赖 |
| 标题/folder 覆盖 | I3 + I1：无丢失更新、全库无整对象回写 |
| 删除确认迟缓 | E1：inspect 为 EXISTS 索引查询；千会话级库 P95 < 50ms（本地基准） |
| Master/Target 行为漂移 | D6/D7/D8/H4：同一 TurnEngine 提交协议（T-1 命令序列等价）+ 同一管道清单（P-1–P-3） |

### 6.3 性能基线（随 PR 输出对比表）

checkpoint 持久化行数/字节每轮、FTS 变更行数每轮、流式 chunk 处理 p95、`applyMutation` 耗时 p95、启动 reconcile 耗时。

---

## 7. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| reducer 迁移语义回归（树操作路径多） | B2 逐函数平移不重写；D7 存量测试强制等价；fork 路径（`forkConversationAtMessage` 走 Repository 直建新会话）明确不经 Runtime |
| DelegationCoordinator 收缩触碰恢复/审批 | D3 仅替换执行段；lineage/lease/recovery 原样保留；H1/H2 专项回归 |
| backfill 失败导致删除/GC 降级 | 降级路径显式（inspect 回退旧扫描 / GC 跳过）；backfill 幂等可重试；flag 在 `system_meta`，备份恢复天然一致（§3.5） |
| Room v6 升级失败 | 全部为 RENAME/加列/建表/索引（无数据搬移）；A9 迁移测试含 schema 校验；`fallbackToDestructiveMigration` 保持关闭 |
| 表 RENAME 影响面 | `managed_files` 仅被 `ManagedFileDAO`（原）以 SQL 引用，A6 一次性改完；无其他表 FK 指向它 |
| 双 FK 级联行为 | A9 显式测试两条级联路径；`artifact_reference` 为纯投影，级联误删可由 backfill 重建 |
| 兼容投影遗漏 | `state` 由 snapshot 派生（含 activeTurn 覆盖，§4.1.4），`getConversationFlow` 公开契约不变；G3 逐一检查只读消费 |
| 回滚 | 代码级 revert 不需 schema 降级：v6 对旧代码可运行（新列有默认值、新表无旧代码读路径）；v5 备份恢复会幂等重跑 migration |

---

## 8. 术语表

| 术语 | 定义 |
| --- | --- |
| ConversationRuntime | 单会话内存态唯一所有者与写通道（原 ConversationSession） |
| Snapshot | UI 只读投影 `ConversationSnapshot`，节点引用稳定 |
| Mutation | 一次持久化 delta：`ConversationMutation` |
| Execution Facts | `TurnExecutionEntity` / `ToolExecutionEntity` |
| TurnEngine | GenerationChunk → Runtime 提交协议的唯一实现 |
| Projection | 可重建派生数据：`message_fts` / `artifact_reference` / Snapshot |
| Artifact | 有生命周期的文件资产：上传附件 / 工具产物（原 ManagedFile） |
| 幂等屏障 | 状态机列 + CAS UPDATE 的原子执行权获取（§3.2） |

---

## 附录 A：代码删除与收敛清单

### 第 1 组：ChatService 删除或迁移（公开签名不变，私有实现收敛）

| 符号 | 处置 |
| --- | --- |
| `updateConversation` / `updateConversationState` / `mergeSessionConversation`（私有） | 删除 → Runtime 命令 |
| `persistLoadedConversation` / `persistTurnCheckpoint` | 迁入 Runtime（私有化） |
| `handleMessageComplete` | 拆解：装配段 → `launchRun`；collect 段 → TurnEngine；终态段 → `FinalizeTurn` reducer 语义 |
| `finalizeMasterTurn` | 消失：终态收口 = `FinalizeTurn` 命令 + reducer |
| `finishInterruptedPendingTools` | 并入 `FinalizeTurn(closeInterruptedTools=true)` |
| `closeOpenTools` / `finishReasoning` / `markAssistantTerminal` / `cancelToolByUser` / `interruptPendingTool` / `checkInvalidMessages` | 迁入 `ConversationReducer`（纯函数）；`materializeMediaForPersistence`（IO）迁入 TurnEngine 命令构造前段 |
| `conversation.updateCurrentMessages(...)` 直接调用点（collect 体内、发送路径） | 全部替换为命令提交（BeginTurn / CommitCheckpoint / FinalizeTurn） |

### 第 2 组：ConversationRepository 删除

`persistMessageNodes` / `saveMessageNodes` / `checkpointTurn` / `checkpointConversation` / `finalizeTurn` / `deleteUnreferencedChatFiles(retained)` / `fileReferenceTokensFor` / `findUnsharedFileUris` / `findUnsharedFilesForDeletion`

### 第 3 组：整体消失/改名的类

| 文件 | 归宿 |
| --- | --- |
| `data/files/ManagedFileDeletionService.kt` | `ArtifactStore`（协议 + inspect + detach + token 提取） |
| `data/repository/FilesRepository.kt` | `ArtifactStore`（元数据 CRUD；注：现状 DB CRUD 由 `FilesRepository` 承担，不存在 `ManagedFileRepository` 文件——修订记录 2） |
| `service/ConversationSession.kt` | 改名 `ConversationRuntime.kt`（吸收三字段一锁；idle 守卫改 `isWriteInFlight()`） |
| `service/SubAssistantCoordinator.kt` | 改名 `DelegationCoordinator.kt`（执行段移交 TurnEngine） |

### 第 4 组：J1 待核查删除项

`ConversationDAO.searchConversations`（非分页版）；`resetConversationNodes` + `conversationentity.nodes` 死代码路径；`getAllTopLevelConversationsSync` / `getAllChildConversationIds`（确认 `SubAssistantRecovery` 等无消费后删）；`LightConversationEntity` 未引用字段。

## 附录 B：保持不变的公开 API（调用方零改动清单）

- `ChatService.sendMessage / editMessage / selectMessageNode / deleteMessage / regenerateAtMessage / forkConversationAtMessage / moveConversationToFolder / deleteFolder / updateConversationTitle / togglePinStatus / compressConversation / handleToolApproval / generateTitle / generateSuggestion / getConversationFlow / deleteConversation`
- `GenerationHandler.generateText` 全签名
- `MessageFtsManager.search / updateConversationTitle`；`ConversationRepository.rebuildAllIndexes(onProgress)`
- `SettingsStore` / `Settings` 全部；`ai` 模块全部模型
- `FilesManager` 公开方法（上传/保存/observe/list/get，签名不变，metadata 委托 ArtifactStore）
