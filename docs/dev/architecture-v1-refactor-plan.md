# Architecture V1 重构方案：Runtime Core / Persistence / Projection

> 状态：pre 阶段已落地（versionCode 18 / `0.0.18`，git 里程碑 **pre1**）。§0–§8 与附录为实施前方案原文；**§9 为 pre 落地后相对草图的约定变更（以代码为准）**；**§10–§12 为 V1 正式阶段（架构收敛）方案**；**§13 为 V1 实施落点记录**；**§14 为落地后 UI 缺陷修复记录**；**§15 为 2026-08-24 面向当前实现的全量收敛审计**；**§16 为 V1 Completion（V1C，架构封板）阶段计划**。§15–§16 推翻与其冲突的“固定文件数、负代码、兼容白名单”约束。
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
12. **（落地后）草图与不变式的局部修正**：实施中若干草图字段/对象无法原样落地（`node_index`、TurnSession 外形、附录 A 个别符号、`new===old` 短路等）。**不回写 §4 草图**——原文保留为设计意图；**以 §9 为当前约定**。核心不变式未改：单写 `submit`、流式永不落库、Master/Target 同一 chunk→checkpoint→终态协议、投影可重建、Artifact CAS。

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

---

## 9. 实施落点：相对方案草图的约定变更

> 本节记录落地代码相对 §4 / 附录草图的**有意偏离**。理由在先，结果在后。未列出的条目按方案原文执行。
> 工作流 A–J 已在同一 versionCode 18 交付；git 里程碑 `架构重构 v1（pre）` 为地基，`架构重构 v1（pre1）` 收口提交协议与 delta `node_index`。

### 9.1 持久化 delta 必须携带新树下标

**草图**：`ConversationMutation.upsertedNodes` 是变更子集，无下标字段；`applyMutation` 对子集 `upsertAll`。
**问题**：`MessageNodeEntity.node_index` 是全树位置。对子集 `mapIndexed` 会把非空树上的 append/checkpoint 写成 `0..k`，破坏排序与 I2。
**结果**：`ConversationMutation` 增加必填 `upsertedNodeIndices`（与 `upsertedNodes` 1:1，值为**新树**下标）。`ConversationRuntime.buildMutation` 在 structural diff 时记录下标 `i`；`applyMutation` 写入 `nodeIndex = upsertedNodeIndices[i]`。C-2 / I2 锁定非空树 append 与 checkpoint 下标。

### 9.2 生成期命令即使内存无变化也要落库

**草图**：`submit` 在 `new === old` 时直接返回，不持久化。
**问题**：空消息 `CommitCheckpoint(RUNNING)`、纯 turn 事实推进等命令可能不改 `messageNodes`，但必须写入 `turn_execution`。
**结果**：生成期命令（`CommitCheckpoint` / `FinalizeTurn`）在 reducer 无节点变化时仍走 `applyMutation(..., executionFacts)`，不因引用相等而短路。

### 9.3 真实节点与流式投影必须分槽

**草图**：`state` 由 `_snapshot.map { it.conversation }.stateIn(...)` 派生；`applyStreamingDelta(messages)` 只改 `activeTurn`。
**问题**：若 reducer 输入与兼容投影共用同一份「已 overlay 的树」，后续 `CommitCheckpoint` 的 structural diff 会把流式投影误判为已落盘而无 delta。
**结果**：`_state` 为 reducer 真实节点（不含流式 overlay）；`_compatibleState` / `ConversationSnapshot.conversation` 为兼容投影（末节点由 `activeTurn` 覆盖）。`applyStreamingDelta(turnId, assistantMessageId, messages)` 只更新 snapshot + 兼容投影，永不 `submit`、永不落库。`ChatList` 订 `snapshot.renderNodes`（即该 overlay 列表），不是裸 `snapshot.nodes`。

### 9.4 BeginTurn / 审批定位字段对齐产品，而不是草图最小集

**草图**：`BeginTurn(turnId, assistantMessageId)`；`UpdateToolApproval(..., toolCallId: String)`。
**问题**：发送/重生成/审批恢复需要区分「新开槽 / 复用槽 / 是否立刻露出空 assistant」；产品工具定位键是 `toolOrdinal`，不是 `toolCallId`。
**结果**：`BeginTurn` 增加 `fromNodeId` / `resume` / `onStart`。`UpdateToolApproval` 使用 `toolOrdinal: Int`。不另建平行 id 体系。

### 9.5 TurnEngine 按 turn 构造，bind 自己提交终态

**草图**：Koin 单例 `TurnEngine(appEventBus)` + `beginTurn(...): TurnSession` + `bind(session, flow)`；`TurnEvent.Finished` 带 `MasterTurnOutcome`；bind 内 `appEventBus.tryEmit`；E6 注册 `TurnEngine`。
**问题**：提交协议状态（`lastFinalizedStatus`、取消/失败时的 Child 消息并入）是**单次 turn** 的，不是进程单例。`Flow.first()` / `take()` 会抛 `AbortFlowException`（也是 `CancellationException`），若一律 `FinalizeTurn(CANCELLED)` 会把收集器中止当成用户停止。
**结果**：

- `TurnEngine(runtime, turnId, assistantMessageId, prepareFinalize?)` 每次 launch 新建；`onCheckpoint` 与 `bind` 在同一实例上。无 `TurnSession` 类型；不注册 Koin。
- `TurnPipelineFactory` 为带依赖的 **class**（注入 `TemplateTransformer` 等），不是草图里的 `object` + 方法参数。
- `bind`：`Messages` → `applyStreamingDelta`；`onCheckpoint` → `CommitCheckpoint`；`Finished` / 非 Abort 的取消 / 异常 → `FinalizeTurn`。`AbortFlowException` 跳过终态提交并原样重抛。
- 取消路径重抛、不 `emit(Finished)`，避免调用方把收集器中止当成生成失败。
- `prepareFinalize` 仅用于命令构造前 IO（Master 取消/失败时并入 Child 消息）；reducer 仍零 IO。
- 通知 `AppEventBus` 留在 `ChatService.launchRun` 的 `TurnEvent.Streaming` 消费处，不进 TurnEngine。
- `TurnEvent.Finished(reason: FinishedReason?, error: Throwable?)`，outcome 由调用方映射。

Master `launchRun` 与 Target `runTargetGeneration` 都是：`BeginTurn` → 空 `CommitCheckpoint(RUNNING)` → `generateText(onCheckpoint = turnEngine::onCheckpoint)` → `bind.collect` 副作用。`ChatService` 不再存在 `handleMessageComplete` / `finalizeMasterTurn`。

### 9.6 Target 在整段 run 开始时 BeginTurn 一次

**草图**：ask_user 多步循环「每步一次 beginTurn + bind」。
**问题**：循环每步再 `BeginTurn` 会重复开槽；若不先 `BeginTurn`，snapshot overlay 会画到末条 USER 任务节点上。
**结果**：`runTargetGeneration` 在进入 `while` 之前提交一次 `BeginTurn`（assistant 槽与后续 `TurnEngine` / `generateText(assistantMessageId=…)` 同一 id），再 `CommitCheckpoint(RUNNING)`，循环内每步只 `bind`。循环末尾仅当 `!hasSubmittedTerminal()` 时补 `submitFinalize`（ask_user 次数上限把 `AWAITING_APPROVAL` 升为 `INCOMPLETE`）。

### 9.7 FinalizeTurn 只收口未结束的 reasoning

**草图**：终态 `finishReasoning` 扫全部消息。
**问题**：历史节点里已有 `finishedAt` 的 Reasoning 也会被 copy，整树变脏，checkpoint 写放大，违背 I2 / R-4。
**结果**：`applyFinishReasoning` 只处理 `finishedAt == null` 的段；已结束的历史节点保持同一实例。`ConversationRuntimePersistenceTest` 锁定三节点树上 Finalize 只 upsert 活跃节点且 `upsertedNodeIndices = [2]`。

### 9.8 附录 A 个别符号保留为门面或白名单，而不是物理删除

| 草图 | 结果 | 理由 |
| --- | --- | --- |
| 删除 `saveMessageNodes` / `persistMessageNodes` | 私有 `saveMessageNodes` 仅 insert/fork 全量下标；`persistImportedMessageNodes` 仅 `updateConversation`（导入/迁移/启动恢复） | 新建会话与 fork 是整树首次写入，不是 runtime checkpoint；C2 禁止的是 **checkpoint 全量重写** |
| `updateConversation`「导入/迁移专用」 | `@Deprecated`；I1 白名单再含 `DelegationCoordinator.submitRecoveredTree`：无内存 Runtime 时的 Child 恢复整写 | 启动早期可能还没有 session；有 session 则走 `ReplaceMessageTree` |
| 删除 `ChatService.updateConversationState` | 保留，仅 `@Transient` 投影（如 `isFavorite`）；不落库 | 收藏事实在 `FavoriteRepository`；整对象回写仍禁止 |
| `finishInterruptedPendingTools` 并入 FinalizeTurn 后删除包装 | 保留为门面：仅当 `previousTurnId` 存在、该 turn 尚未 `isTurnFinalized`、末条 assistant 仍有未执行工具时，提交 `FinalizeTurn(INTERRUPTED, closeInterruptedTools=true)` | 无条件调用会用随机 id 写 INTERRUPTED，或覆盖 bind 已提交的 CANCELLED/COMPLETED |
| `closeOpenTools` 迁入 reducer 后 ChatService 删除 | reducer 有纯变换 `closePendingTools`；ChatService 仍保留 IO 版 `closeOpenTools`（加载 Child 消息）经 `prepareFinalize` 喂给 `FinalizeTurn.messages` | 子助手中断结果不能进 reducer |
| E6 注册 `TurnEngine` | 不注册；每次 turn `TurnEngine(...)` | 见 9.5 |
| J1 删除 `getAllTopLevelConversationsSync` / `getAllChildConversationIds` | **保留**：`DelegationCoordinator` 恢复扫描与 `AssistantBackgroundService` 仍消费 | 附录 A 第 4 组本就是「待核查」；核查后仍有读路径，不删 |

### 9.9 Runtime 目录与「5 文件」配额

**草图**：`service/runtime/` 固定 5 文件（Commands / Reducer / Runtime / Registry / TurnEngine）。
**结果**：上述 5 个仍是 Runtime 层。`DelegationCoordinator` 按 D3 放在同一包，但是 **Application 编排**（谱系/租约/ask_user/恢复），不计入 Runtime 5 文件配额，也不承担第二套提交协议。

### 9.10 工程备注（非架构不变式）

- androidTest 打包排除 JUnit 5 重复的 `META-INF/LICENSE.md` / `LICENSE-notice.md` / `NOTICE.md`，否则 `Migration_5_6Test` 无法安装到设备。
- `ConversationDAO.searchConversations` 非分页版、`resetConversationNodes` 等 J1 项未在本里程碑继续清扫。

---

## 10. Pre 阶段达成分析

> 基线：versionCode 18（git 里程碑 pre / pre1），对照 §2.2 不变式与 §6 验收标准逐条裁决。证据以类名/函数名定位（可全局检索）。本章为 §11–§12 的输入：所有缺口必须归因到「计划内未完成 / 计划缺口 / 设计缺口」之一，V1 阶段的范围 = 缺口全集。

### 10.1 已达成项

§6.2 缺陷消除验收六项全部达成（长会话卡顿、删除重启复活、删除重入、标题/folder 覆盖、删除确认迟缓、Master/Target 漂移）。§2.2 六大不变式逐条裁决：

| 不变式 | 裁决 | 证据 |
| --- | --- | --- |
| 1 每类 durable state 单一 owner | 达成 | Runtime 命令通道 / TurnEngine 执行事实 / ArtifactStore |
| 2 全库无整对象回写 | 达成 | I1 契约测试；§9.8 白名单外无路径 |
| 3 提交协议唯一实现 | 达成 | TurnEngine Master/Target 共用（§9.5/§9.6）；T-1 等价测试 |
| 4 投影可重建、永不当事实源 | 达成 | message_fts 增量 / artifact_reference / Snapshot；I2 / I6 |
| 5 破坏性操作 durable / 幂等 / 可恢复 | 达成 | ArtifactStore CAS + reconcileStartup；I4 |
| 6 文件配额 | 达成 | Runtime 5 文件；DelegationCoordinator 按 §9.9 归 Application 编排 |

delta 持久化含新树下标（§9.1）、生成期命令不短路（§9.2）、流式单消息变换（I5）、Target 整段 run 一次 BeginTurn（§9.6）、Finalize 只收口活跃 reasoning（§9.7）均达成。

### 10.2 未达成项

| # | 缺口 | 证据 | 归因 |
| --- | --- | --- | --- |
| G-1 | §4.1.4「过渡策略」未执行终态：兼容投影仍在每 chunk 双写 | `ConversationRuntime` 维护三份状态（`_state` / `_compatibleState` / `_snapshot`）；`applyStreamingDelta` 每 chunk 派生一次 O(N) 兼容投影；`ConversationSnapshot.renderNodes` 实现为 `conversation.messageNodes`（绕回投影）；`ChatVM` 同时订阅 conversation 流与 snapshot 流 | 计划内未完成——工作流 G 仅迁移 ChatList 一处 |
| G-2 | §2.1「ChatService 收缩为装配 + 副作用薄壳」未达成 | ChatService 约 1656 行、约 20 个构造依赖；仍持有崩溃恢复（`recoverInterruptedTurns`）、中断收口原语（`closeOpenTools` 族）、标题/建议/压缩三胞胎、child retention 编排、fork 文件复制 | 计划缺口——§2.1 有愿景，工作流 D2 只删装配段，无对应条目 |
| G-3 | §4.7 性能承诺部分兑现：会话恢复路径 O(全库) | `DelegationCoordinator.performRecovery` 经 `getAllTopLevelConversationsSync` 全库反序列化；`ChatService.recoverInterruptedTurns` 在 execution 循环内逐条全树加载 | 设计缺口——§6.3 基线未覆盖会话恢复；恢复输入未接入修订记录 6 已铺好的 `turn_execution` 事实链路 |
| G-4 | 数据层未贯彻「主/子同构」哲学 | 运行时协议已统一（T-1），但 `conversation.parentConversationId` 无 FK 无索引、级联删除靠应用层（孤儿 child 因此存在，恢复需全库扫描清理）；调用 ↔ child 对应关系埋在消息 JSON metadata（无关系查询路径） | 设计缺口——pre 聚焦运行时协议统一，数据层关系表达未跟进 |

### 10.3 制度化过渡层的检讨

附录 B 将 `getConversationFlow` 列入「保持不变的公开 API」——等于把 §4.1.4 自己声明为过渡的投影层合法化，G-1 因此获得了存续依据。V1 阶段显式推翻该条目（§11.3 块 1 / §12 工作流 N）。

### 10.4 结论

pre 达成 Runtime 内核全部不变式与 §6.2 全部缺陷消除；未完成的是三类收敛——**状态形状（G-1）、职责（G-2）、恢复与关系（G-3/G-4）**。三者共同特征：目标语义在 §0–§9 中均有正确描述，缺的是「旧路径退役」的交付边界。V1 阶段以此三类收敛为全部范围，验收判据是**切换完成**而非兼容并存。

---

## 11. V1 正式阶段方案（架构收敛）

> 阶段命名：pre = 预备/地基（已交付，versionCode 18）；**V1 正式阶段 = 完成态**。设计哲学：**负代码收敛**——pre 建新架构，V1 删旧架构。每项变更必须是净删除、等量搬家或等量替换；纯新增代码仅限两类：通过数据结构三判据裁决的 v7 修正项、turn 骨架唯一化的吸收段。切换纪律：**符号删除不留 deprecated 转发，靠编译失败保证迁移彻底**。

### 11.1 数据结构裁决纪律（三判据）

后续任何数据结构提案必须依次通过（本轮评审中事实表方案的否决过程固化为纪律）：

1. **需要关系查询的信息 → 范式化为列**。禁止埋进文档 JSON 后再用全量反序列化扫描取回。
2. **关系的完整性 → 用外键表达**。禁止用应用层清理约定替代数据库约束——应用层约定意味着每个写入点都要记得，遗漏即悬挂。
3. **只需整体读写的聚合 → 文档化 JSON**；当其内部出现真实的关系查询需求时**用投影表**（`artifact_reference` 模式），不回头改事实表。

曾评估并否决的方案记录：`SubAssistantRun` 执行事实表——其全部字段可由 `turn_execution` / `tool_execution` + 定点消息加载推导，属可推导冗余状态（可推导即禁止存储）；且会成为第三张执行事实表，而 assistant_call 的执行事实本就属于 `tool_execution` 的语言。教训：把「缺查询路径」误判为「缺事实表」。

### 11.2 同构性与 3NF 审计 → v7 修正性 Migration

**同构前提**：子代理调用与主会话是同构体。一次 assistant_call 在数据层只有三个侧面——执行侧 = master turn 的一次工具执行（`tool_execution` 已承载）；会话侧 = 派生的普通 Conversation（`conversation` / `message_node` / `turn_execution` 已承载）；**唯一差别是关系**，而这恰是当前唯一未用关系机制表达的部分。

**现有 schema 的 3NF 审计**：

| # | 偏离 | 性质 | 裁决 |
| --- | --- | --- | --- |
| 1 | `message_node.messages` 整树消息变体存单 JSON 列 | 违反 1NF（文档模型） | **保留（正确偏离）**。part 为多态密封类型，拆表即 EAV 反模式；读写恒为节点粒度（delta 即节点级），无按 part 查询需求；唯一真实的按元素查询（文件引用）已由 `artifact_reference` 投影满足。范式化边界 = 「有无关系查询需求」 |
| 2 | `conversation.chatSuggestions` / `modeInjectionIds` JSON 列 | 违反 1NF | **保留**。无按元素查询需求，拆表零收益 |
| 3 | `conversation.nodes` 空置死列 | 死数据 | **删除**（随表重建） |
| 4 | `parentConversationId` 无 FK / 无索引 / 级联删除靠应用层 | **关系完整性缺失（结构性缺口）** | **修复**。主/子唯一差别是关系，该关系应交还数据库：自引用 FK + ON DELETE CASCADE + 索引。孤儿 child 从「启动清扫」变为「结构上不可能」 |
| 5 | 调用 ↔ child 对应关系埋在消息 metadata JSON | 关系可查询性缺失 | **补一列**：`tool_execution.child_conversation_id`。非新表、非投影、非冗余——一次执行派生哪个 child 本就是该行执行事实的自然属性（如 `toolOrdinal`），函数依赖 executionId，3NF 合法；写入点即已有 STARTED upsert |
| 6 | `assistantId` 引用 DataStore 实体（跨存储） | 跨域债务 | 本轮不动（SettingsStore 迁移为既定非目标），记录为已知债务 |

审计结论：**没有一张表多余，也没有一张新表必要；缺一个外键和一列。**

**Migration_6_7**（重建 conversation 表一次完成；列清单以 `ConversationEntity` 为准）：

```kotlin
val Migration_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1) 清理存量孤儿 child（加 FK 前必须收敛；此后孤儿结构性不可能）
        db.execSQL("""
            DELETE FROM conversationentity WHERE parent_conversation_id IS NOT NULL
            AND parent_conversation_id NOT IN (SELECT id FROM conversationentity)
        """.trimIndent())
        // 2) 重建 conversationentity（SQLite 无法 ALTER 添加 FK），标准流程：
        //    foreign_keys=OFF → CREATE 新表（+ FK(parent_conversation_id) REFERENCES conversationentity(id)
        //    ON DELETE CASCADE、+ Index(parent_conversation_id)、− nodes 死列）
        //    → INSERT SELECT 复制全列（除 nodes）→ DROP 旧表 → RENAME → 建索引
        //    → foreign_keys=ON → foreign_key_check 校验
        // 3) tool_execution 增列 + 索引
        //    ALTER TABLE tool_execution ADD COLUMN child_conversation_id TEXT
        //    CREATE INDEX index_tool_execution_child ON tool_execution(child_conversation_id)
    }
}
```

配套：`ConversationEntity` 声明自引用 FK 与索引、删除 `nodes` 属性；`ToolExecutionEntity` 增列；`message_node` 既有 FK 不受影响（表名不变、行数据不动）。表重建行数 = 会话数（毫秒级）。

v7 换来的代码删除：`performRecovery` 孤儿扫描与删除（约 40 行）、`deleteConversation` 显式 child 级联（约 10 行）、恢复路径全库扫描（块 2）。

### 11.3 四个工作块

**块 1 状态形状统一（G-1 终态）**：`ConversationSnapshot` 成为唯一事实流；`Conversation` 退回持久化边界（DB 映射与命令构造处的形状，不再作为运行时状态）。

- reducer 状态换快照：`reduce(snapshot, command): Snapshot`；`UpdateHeader` 仅 copy header（不再触碰 nodes）；`toSnapshot()` 转换删除。
- 删除：`_state` / `_compatibleState` / `state` / `toSnapshot()` / `ConversationSnapshot.conversation` getter / `submitGeneration` 别名；`replaceState` 改名 `loadSnapshot`（整对象装载语义显式化）。
- `renderNodes` 真快路径：activeTurn 为空时即 `nodes`；非空时仅末 assistant 节点替换（每帧一次 O(N) 浅拷贝、元素引用共享，conflation 后为不可变列表模型下的下限）；新增 `snapshot.currentMessages()` 命令语义读取入口（调用时一次 O(N)，仅 turn 边界低频点使用）。
- 消费方迁移：ChatService 内部读点、DelegationCoordinator 内部读点、ChatVM / ChatPage / ChatList / TTSAutoPlay / SubAssistantDetailVM；删除 `Registry.getConversationFlow` 与 `ChatService.getConversationFlow`（附录 B 修正）。

**块 2 恢复与关系归位（G-3 / G-4 终态）**：恢复输入从「全库扫描推导」切换到「执行事实 + 关系查询」；恢复语义合并为单文件。

- v7（§11.2）落地。
- assistant_call STARTED 时 `tool_execution` upsert 携带 `childConversationId`（写入点已存在，加字段赋值，与 metadata patch 同点）。
- 恢复链路重写（`TurnRecovery.recoverInterruptedRuns`）：`turn_execution` 状态查询 JOIN conversation 过滤 child → master 行定点加载收口 stale 调用 + 经 `child_conversation_id` 定点收口 child；child 行（parent NOT NULL）定点加载收口。孤儿扫描整体删除（FK 接管）。
- `recoverInterruptedTurns` 修复：会话级分组加载（外层每会话一次，替代 execution 循环内逐条全树加载）；Child 过滤下沉 DAO JOIN。
- 恢复域合并：`SubAssistantRecovery.kt` 改名 `TurnRecovery.kt`，吸收 `ChatService.recoverInterruptedTurns` / `closeOpenTools` 族 / `finishInterruptedToolAfterGenerationStop` / `finalizeDanglingToolExecutions` / `finishInterruptedPendingTools` 与 `DelegationCoordinator.performRecovery` / `recoverMasterForMutation` / `recoverInterruptedChild` / `finalizeInterruptedRun` / `submitRecoveredTree`；四处重复的树替换收口原语（`markAssistantTerminal` 等）合一。
- 写路径读消除：`ConversationMutation` 增 `titleForIndex`（Runtime 内存 header 为权威，随 delta 携带）；`applyMutation` 删除事务后 `getConversationById` 回查。

**块 3 turn 骨架与公用逻辑唯一化（G-2 部分）**：

- `TurnEngine` 吸收 `startTurn()`：resumable 槽探测 + `BeginTurn` + 空 `CommitCheckpoint(RUNNING)`（`launchRun` 与 `runTargetGeneration` 各写一遍的骨架段归一）。
- 文件克隆合一：`copyForkedPart`（ChatService，fork 场景）与 `copyPartForChildClone`（Coordinator，clone 场景）合并为 `AttachmentCloner`（参数化 `toolArtifactRewriter`）。
- 背景生成三胞胎合并：`generateTitle` / `generateSuggestion` / `compressConversation` 的公共样板（settings → 专属模型(fallback fastModel) → provider → generateText → 命令提交）提取为私有 `runBackgroundGeneration`。
- `executeCall` 分四阶段：`preflightRun`（readiness + runSpec + lineage → sealed 结果）/ `materializeChild`（lease use 结构 + 附件 + create/reuse/clone）/ `runChildTurn` / `toToolResult`。五处手动 `runLeases.release` 归 use 结构。
- child retention 编排（`applyChildRetentionAfterTreeMutation`）移交 Delegation 域。
- 子助手输出纯函数（`extractFinalAnswerInternal` / `collectSubAssistantCallOutputs` 族）合并进 `data/ai/subassistant/SubAssistantResultProjection.kt`。

**块 4 命名与死代码清理**：

- 改名：`recoverMasterForMutation` → `finalizeStaleRunsBeforeMutation`（它是变更前收口不是崩溃恢复）；`finishInterruptedPendingTools` → `finalizeSupersededTurn`（处理被新 turn 取代者）；`performRecovery` → `recoverInterruptedRuns`；`getAllTopLevelConversationsSync` → `loadAllTopLevelConversations`（Sync 后缀歧义；`AssistantBackgroundService` 保留消费）。
- 删除：J1 遗留（`searchConversations` 非分页版 / `resetConversationNodes`）；ChatService 尾部私有 `sanitizeForPersistence` 与 `markAssistantTerminal`（reducer 已有等价实现）；`updateConversationState` 经消费方核查后处置（若收藏回填仍有消费方则保留并改名 `patchTransientProjection`）。
- 附录 B 修正：移除 `getConversationFlow` 条目。

### 11.4 文件目标值

| 文件 | pre 基线（行） | V1 目标（行） | Δ | 职责终态 |
| --- | --- | --- | --- | --- |
| `service/ChatService.kt` | 1656 | ~1200 | -456 | Master turn 编排 + 会话/消息 CRUD 门面 + 错误总线 |
| `service/runtime/DelegationCoordinator.kt` | 1454 | ~1070 | -384 | executeCall 四阶段 + child 管理 + target 循环 + ask_user 桥 |
| `service/TurnRecovery.kt`（原 SubAssistantRecovery） | 195 | ~610 | +415 | 中断/崩溃恢复语义唯一所有者（master turn + sub-assistant run + 取消收口原语） |
| `data/ai/subassistant/SubAssistantResultProjection.kt` | 269 | ~400 | +131 | 子助手输出侧纯函数（final answer / outputs / artifacts 投影） |
| `service/runtime/ConversationRuntime.kt` | 387 | ~300 | -87 | 单写通道；快照唯一状态 |
| `service/runtime/TurnEngine.kt` | 278 | ~360 | +82 | turn 骨架唯一实现（startTurn 吸收） |
| `service/runtime/ConversationReducer.kt` | 376 | ~400 | +24 | 快照态纯函数 |
| `service/runtime/ConversationCommands.kt` | 199 | ~190 | -9 | 命令 + 快照（兼容投影删除） |
| `data/repository/ConversationRepository.kt` | 746 | ~700 | -46 | mutation 事务 + 定向查询（title 回查删除 / 级联简化 / 死代码清扫） |
| `data/db`（Migration_6_7 + entity + DAO 增量） | — | ~120 | +120 | 自引用 FK + child 列 + 孤儿清理 |
| UI 消费方（ChatVM / ChatPage / ChatList / TTSAutoPlay / SubAssistantDetailVM） | — | — | ~-40 | snapshot 单订阅 |

主引擎文件（ChatService + DelegationCoordinator + Runtime + Commands + Repository）合计降幅约 982 行。

### 11.5 行数账本

- **纯删除 ≈ -500 行**：兼容层三状态与投影（130）、三胞胎样板（58）、文件克隆重复（25）、turn 骨架内联（90）、死代码与别名（60）、收口原语合一（80）、孤儿扫描与显式级联（50）、title 回查（8）。
- **纯新增 ≈ +226 行**：v7（120）、`TurnEngine.startTurn`（82）、reducer 快照化（24）。**纯新增 < 纯删除**。
- **搬家 ≈ 550 行**（不计增减）：恢复域归位 TurnRecovery（约 415）、输出纯函数并入 ResultProjection（约 131）。
- **代码库净行数 ≈ -250（±10% 容差）**。验收判据：净行数 ≤ 0，且主引擎文件合计降幅 ≥ 900 行。

---

## 12. V1 正式阶段执行计划

> 工作流 K–P 续接 §5 的 A–J 字母序。依赖关系：K 与 L 可并行；M 依赖 K 完成（`launchRun` 收缩在投影清理后做，避免双改）；N 任意时点（机械）；O 依赖 K / L / M；P 收口。V1 落地后的实施落点相对本章的偏离记录为 §13（落地后补写），§0–§9 维持冻结。

### 工作流 K：状态形状统一

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| K1 | 测试先行 | `ConversationReducerTest` 扩展 | 快照态等价用例：同命令序列下，旧 Conversation reducer 与新 Snapshot reducer 产出 currentMessages 逐项等价（旧实现保留至本组用例通过后删除） |
| K2 | 改造 | `ConversationReducer.kt` | `reduce(current: ConversationSnapshot, command): ConversationSnapshot`；UpdateHeader 仅 copy header；structural sharing 断言不变（R-4 延续） |
| K3 | 改造 | `ConversationRuntime.kt` | 删 `_state` / `_compatibleState` / `state` / `toSnapshot()`；submit 单状态发布；`persistedState` 基线换快照；`buildMutation` 输入 old/new 快照（含 `titleForIndex` 填充）；`replaceState` → `loadSnapshot` |
| K4 | 改造 | `ConversationCommands.kt` | 删 `conversation` getter；`renderNodes` 真快路径；增 `currentMessages()` |
| K5 | 迁移 | ChatService / DelegationCoordinator 内部读点 | `session.state.value.currentMessages` → `session.snapshot.value.currentMessages()`（turn 边界低频点） |
| K6 | 迁移 | ChatVM / ChatPage / ChatList / TTSAutoPlay / SubAssistantDetailVM | 单订阅 snapshot；删 conversation 流与 `getConversationFlow` 调用；**与 K7 同 PR 交付，杜绝半迁移状态入库** |
| K7 | 删除 | Registry / ChatService / Runtime | `getConversationFlow` / `submitGeneration` 别名；符号物理删除不留 deprecated 转发，编译失败兜底迁移彻底性 |
| K8 | 测试 | `ConversationRuntimeTest` | `applyStreamingDelta` 快照路径零 DB 调用；`renderNodes` 未变节点引用相同 |

### 工作流 L：恢复与关系归位

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| L1 | 新增 | `Migration_6_7` + `ConversationEntity` / `ToolExecutionEntity` | §11.2：孤儿清理 → 表重建（FK + CASCADE + 索引 + 删 nodes 死列）→ `tool_execution` 补列 |
| L2 | 测试 | `Migration_6_7Test` | 孤儿清理三态（有 parent / 无 parent / parent 悬挂）；FK 级联（删 master → child / message_node / artifact_reference 级联消失）；schema 校验；v6→v7 与直建 v7 同构 |
| L3 | 修改 | TurnEngine / GenerationHandler 事实写入 | assistant_call STARTED 的 `tool_execution` upsert 携带 `childConversationId` |
| L4 | 合并 | `SubAssistantRecovery.kt` → `TurnRecovery.kt` | §11.3 块 2 合并清单；逐函数平移不重写；树替换收口原语合一 |
| L5 | 重写 | `TurnRecovery.recoverInterruptedRuns` | 定点链路（§11.3 块 2）；孤儿扫描删除；`recoverInterruptedTurns` 会话级分组 + DAO JOIN 过滤 child |
| L6 | 修改 | `ConversationRepository.kt` | `getRecoverableTurnExecutionsByConversation` 加 JOIN；`deleteConversation` 级联简化；title 回查删除；`getAllTopLevelConversationsSync` 改名 |
| L7 | 测试 | `TurnRecoveryTest` | 中断恢复矩阵：master 生成中 kill → 收口；assistant_call 运行中 kill → master metadata + child 双侧收口；child 生成中 kill → 收口；恢复涉及会话与库规模无关（与 I8 呼应的 JVM 级断言） |

### 工作流 M：骨架与公用逻辑

| # | 动作 | 文件 | 内容 |
| --- | --- | --- | --- |
| M1 | 扩展 | `TurnEngine.kt` | `startTurn()` 吸收（resumable 探测 + BeginTurn + RUNNING checkpoint）；`launchRun` / `runTargetGeneration` 骨架段删除 |
| M2 | 合并 | `AttachmentCloner`（data/files/） | `copyForkedPart` + `copyPartForChildClone` 归一，参数化 `toolArtifactRewriter` |
| M3 | 合并 | `ChatService.kt` | `runBackgroundGeneration` 提取；三胞胎调用点收缩 |
| M4 | 重构 | `DelegationCoordinator.executeCall` | 四阶段拆分；lease use 结构 |
| M5 | 平移 | `SubAssistantResultProjection.kt` | 输出侧纯函数并入（`extractFinalAnswerInternal` / `collectSubAssistantCallOutputs` 族） |
| M6 | 移交 | Delegation 域 | `applyChildRetentionAfterTreeMutation` 归 Coordinator |
| M7 | 测试 | `TurnEngineTest` 扩展 | startTurn 等价：Master / Target 经新骨架的完整命令序列一致（T-1 扩展） |

### 工作流 N：命名与死代码

| # | 动作 | 内容 |
| --- | --- | --- |
| N1 | 改名 | §11.3 块 4 清单（随 L4 / L5 / M 落点执行） |
| N2 | 删除 | `searchConversations` 非分页版 / `resetConversationNodes` / ChatService 私有 `sanitizeForPersistence` / `markAssistantTerminal` |
| N3 | 核查 | `updateConversationState` 消费方清点 → 删除或改名 `patchTransientProjection` |
| N4 | 修正 | 附录 B：移除 `getConversationFlow` 条目；标注 `getConversationSnapshot` / `submitConversationCommand` 为 VM 唯一入口 |

### 工作流 O：契约测试（常驻 gate）

| # | 测试 | 断言 |
| --- | --- | --- |
| I7 | `SnapshotOnlyContractTest` | CI grep：全库无 `getConversationFlow` / 兼容投影 `state` 引用；`ConversationRuntime` 无第二状态流 |
| I8 | `RecoveryCostDecouplingTest` | 500 会话库 vs 50 会话库（各含同量中断执行）：恢复耗时同数量级（恢复成本与库大小解耦） |
| I9 | `TurnSkeletonEquivalenceTest` | T-1 扩展：`startTurn` + `bind` 全序列 Master / Target 等价 |

### 工作流 P：文档与验收

| # | 动作 | 内容 |
| --- | --- | --- |
| P1 | 版本 | `changelog.md` 新条目；`versionCode` 19 |
| P2 | 文档 | `AGENTS.md` Runtime Core 段（TurnRecovery / 结果投影归位）；`docs/references/` 相应文档同步（chat-generation-pipeline / sub-assistant-architecture） |
| P3 | 交付 | 行数账本核对（§11.5，±10%）；文件目标值核对（§11.4，±10%）；净行数 ≤ 0 |

### 依赖图

```text
K ───────► M ──► O ──► P
L ───────► ▲
N（任意时点，机械）
```

### 验收标准

1. **切换完成**：I7 通过（无兼容符号、Runtime 无第二状态流）；全部 UI 单订阅 snapshot；`Conversation` 不再作为运行时状态形状出现。
2. **恢复成本**：I8 通过；恢复耗时与库大小解耦（G-3 关闭）。
3. **负代码**：代码库净行数 ≤ 0；主引擎文件合计降幅 ≥ 900 行；纯新增（≈226）< 纯删除（≈500）。
4. **行为等价**：既有测试全绿（`ChatServiceTest` / `ChatServiceConversationWriteTest` / `SubAssistant*` / `ConversationReducerTest` / `ConversationRuntimeTest` 等）；K1 / L2 / L7 / M7 新增用例全绿；手测：生成中 kill -9 → 重启 master metadata + child 双侧收口无悬挂。
5. **schema**：v7 迁移测试（L2）通过；`fallbackToDestructiveMigration` 保持关闭。

### 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 消费方迁移遗漏（K5 / K6 遗漏读点） | K7 符号物理删除不留转发，编译失败兜底；I7 grep 契约常驻 |
| reducer 快照化语义回归 | K1 等价测试先行，旧实现保留至通过后删除；R-1–R-5 全量延续 |
| v7 表重建失败 | 标准重建流程（foreign_keys=OFF → 复制 → RENAME → foreign_key_check）；行数 = 会话数（毫秒级）；L2 覆盖孤儿三态与双级联；schema 校验对齐 M7 范式 |
| `childConversationId` 写入遗漏 | L3 与既有 STARTED upsert 同点；L7 恢复矩阵覆盖「运行中 kill」用例 |
| TurnRecovery 合并行为漂移 | 逐函数平移不重写（对齐 B2 纪律）；崩溃恢复专项回归 |
| 迁移期 UI 半态 | K6 与 K7 同 PR 交付；主干禁止出现「双订阅并存」的中间提交 |
| 回滚 | v7 对旧代码可运行（新列 nullable；FK CASCADE 与旧显式删除幂等共存）；代码级 revert 后 migration 幂等重跑 |

---

## 13. V1 实施落点记录（落地后补写，以代码为准）

> versionCode 18 保持不变（本轮不发布）。§0–§12 维持冻结；本节记录实际交付与 §11/§12 的偏离。

### 13.1 已落地（全部结构目标达成）

- **块 1 状态形状统一**：`ConversationSnapshot` 唯一事实流；`_state`/`_compatibleState`/`state`/`toSnapshot()`/`conversation` getter/`submitGeneration` 别名全部物理删除；`renderNodes` 真快路径（末节点 activeTurn 覆盖）；`currentMessages()` 命令语义读取；`toConversation()` 顶层纯函数（持久化边界转换）；消费方全量迁移（ChatService/Coordinator 内部读点、ChatVM/ChatPage/ChatList/TTSAutoPlay/SubAssistantDetailVM）；`getConversationFlow` API 删除（附录 B 修正）。**修复实录**：原 `submit` 经 `toSnapshot()` 重建隐式清空 activeTurn——快照化后 activeTurn 残留会遮蔽终态投影，已改为结构性命令提交后显式收口流式态（`ChatServiceTurnPersistenceTest` 停止/失败两用例验证）。
- **块 2 恢复与关系归位**：v7 修正性 Migration（自引用 FK CASCADE + 索引 + 删 nodes 死列 + `tool_execution.child_conversation_id` + 孤儿收敛）；`recoverInterruptedRuns` 定点链路（`getNonTerminalTurnExecutionsWithScope` 状态索引为唯一输入，全库扫描与孤儿清理删除）；`recoverInterruptedTurns` 会话级分组 + DAO JOIN 过滤 Child；恢复语义唯一所有者 `TurnRecovery.kt`（吸收 ChatService 收口族/Coordinator 恢复段/原 SubAssistantRecovery）；title 随 mutation 携带（applyMutation 零回查）。
- **块 3 骨架与公用逻辑**：`TurnEngine.start` 唯一骨架（Master/Target 共用，I9 锁定）；`AttachmentCloner` 文件克隆唯一实现；背景生成三胞胎整体迁出 ChatService → `GenerationSideEffects`（音效反馈 + 标题/建议/压缩共用后台生成骨架）；子助手输出/结果形状/入站投影纯函数归位 `SubAssistantResultProjection.kt`。
- **第二轮收敛（职责彻底归位）**：`SubAssistantRunGate` 新设 run 门禁域（lease + pending ask_user 组合并发原语，Coordinator 与 TurnRecovery 共同消费）；恢复入口 `recoverInterruptedRuns` / 变更前收口 `finalizeStaleRunsBeforeMutation` / retention `applyChildRetentionAfterTreeMutation` / `finalizeInterruptedRun` 全部归位 TurnRecovery（ChatService 直连，Coordinator 不再持有恢复语义）；executeCall 四阶段化（preflight → materialize → run → terminal，终态失败三分支收敛为 `failedTerminal`，lease 由唯一 `finally` 释放替代五处手动 release）；`answerToolAtLocator` 死代码删除；AssistantDataRecovery 直连 TurnRecovery。
- **块 4 清理**：`finalizeStaleRunsBeforeMutation`/`loadAllTopLevelConversations` 改名落地；`searchConversationsOfAssistant`（非分页版）/`sanitizeForPersistence`/私有 `markAssistantTerminal` 删除。
- **契约测试**：I7（快照唯一，grep 契约）/I8（恢复成本与库大小解耦，结构性断言：恢复输入仅状态索引、健康库零加载）/I9（骨架命令形状唯一）；既有 1030 用例全量迁移并全绿（合计 1037）。
- **迁移测试**：`Migration_6_7Test` 八用例（孤儿三态/FK 双级联/列删除数据保全/悬挂插入拒绝/schema 同构/**v5→v7 全链路**——managed_files→artifact 改名数据、会话树、消息节点无损到达 v7）。androidTest 编译与 Room schema 校验通过（7.json 生成）；仪器执行待设备。

### 13.2 行数账本（实测，两轮收敛后）

| 文件 | pre | 实测 | Δ | §11.4 目标 |
| --- | --- | --- | --- | --- |
| ChatService.kt | 1656 | 1083 | **-573** | ~1200 ✓ 超额 |
| DelegationCoordinator.kt | 1454 | 960 | **-494** | ~1070 ✓ 超额 |
| TurnRecovery.kt（原 SubAssistantRecovery 195） | 195 | 731 | +536 | 恢复/中断/retention/lease 收口语义唯一所有者 |
| GenerationSideEffects.kt（新） | 0 | 375 | +375 | 音效反馈 + 标题/建议/压缩衍生生成唯一所有者 |
| SubAssistantRunGate.kt（新） | 0 | 67 | +67 | run 门禁（lease + pending ask_user）并发原语 |
| ConversationRuntime.kt | 387 | 363 | -24 | ~300 |
| ConversationReducer.kt | 376 | 371 | -5 | ~400 ✓ |
| ConversationCommands.kt | 199 | 215 | +16 | 快照 + 持久化边界转换 |
| TurnEngine.kt | 278 | 335 | +57 | ~360 ✓ 骨架唯一实现 |
| ConversationRepository.kt | 746 | 750 | +4 | ~700 ✓ |
| SubAssistantResultProjection.kt | 269 | 557 | +288 | 输出/结果形状/入站投影纯函数唯一所有者 |
| AttachmentCloner.kt（新）/ Migration_6_7.kt（新） | 0/0 | 45/79 | +124 | — |

- **主引擎文件（ChatService/Coordinator/Runtime/Commands/Repository）合计 -1071 行**——超过 §11.5 判据（≥900）。ChatService -35%、DelegationCoordinator -34%（相对 pre 基线），两文件合计 3110 → 2043。
- 代码库全局净 +371：引擎移出的约 1300 行归位到五个职责域文件（TurnRecovery/SideEffects/Gate/ResultProjection），归位侧含必要的新增文档注释与结构骨架；纯新增逻辑（v7 + TurnEngine.start + reducer 快照化 + Gate 原语）约 330 行。
- 结构判据全部达成：每个文件单一职责域、无兼容层、恢复/副作用/投影/门禁各有唯一所有者。

### 13.3 未执行项与后续

- **Migration_6_7Test 仪器执行**：需要设备/emulator（`gradlew connectedDebugAndroidTest`），编译与 schema 校验已过。
- 版本号 18 与 changelog 维持现状（本轮为未发布重构）。

### 13.4 修订记录

1. 快照化的 activeTurn 收口语义修复（见 13.1 块 1 修复实录）。
2. `ConversationMutation.titleForIndex` 移至参数表末位并设默认值（兼容既有位置参数构造与测试）。
3. I1 白名单条目迁移：`updateConversation` 白名单文件由 DelegationCoordinator.kt 改为 TurnRecovery.kt（恢复域归位的契约跟随）。
4. `ConversationDAO.searchConversationsOfAssistant` 非分页版删除（J1 收尾），分页版为唯一搜索入口。
5. 第二轮收敛中 lease 释放重构为 executeCall 唯一 `finally`（materialize 失败路径的泄漏由测试暴露后修复）；`reportSubAssistantMetadataPatch` 因底层 `ToolExecutionContext.reportMetadata` 为 suspend 而标记 suspend（全部调用方均在挂起上下文）。
6. `SubAssistantRunGate` 对外的 lease API 标记 internal（`SubAssistantRunKey`/`SubAssistantRunLease` 为 internal 类型），DI 暴露 public 门面。
7. 见 13.5——FK 约束启用与 REPLACE 级联消除（落地后全量审查发现的两处数据完整性缺陷）。

### 13.5 落地后审查：数据完整性缺陷与修复

> 对照 §11.2/§11.3 全量审查 v7 与重构实现后发现：**v7 的 FK 承诺在运行时从未生效**——Room 默认不启用外键约束（SQLite 默认 `foreign_keys=OFF`），且未配置任何开启路径。该缺陷同时波及 pre 阶段的既有设计（`artifact_reference` 的"node FK 级联自动清"注释是错误假设，实际依赖显式删除兜底）。

| # | 发现 | 后果（若不修复） | 修复 |
| --- | --- | --- | --- |
| R1 | FK 约束从未启用：`setForeignKeyConstraintsEnabled` 类配置缺失，SQLite 默认 OFF | v7 自引用 CASCADE 不生效——"孤儿 child 结构性不可能"落空；`message_node`/`artifact_reference` 的级联清理全部依赖应用层显式删除，遗漏即悬挂 | `DataSourceModule` 的 `RoomDatabase.Callback.onOpen` 首行执行 `PRAGMA foreign_keys = ON`（onOpen 晚于 onUpgrade → 迁移期间 FK 保持 OFF，`Migration_6_7` 的表重建天然安全；migration 内保留显式 OFF/ON 包裹作防御） |
| R2 | `MessageNodeDAO` 全部写入用 `OnConflictStrategy.REPLACE`——SQLite 的 REPLACE 对已存在主键执行 DELETE+INSERT，FK ON 后级联删除该节点的全部 `artifact_reference` 行 | delta upsert 与事务后的 `syncReferences` 重建之间存在引用真空——GC 恰在此窗口跑 `collectUnreferencedArtifacts` 会误删仍被引用的文件（保护窗口仅覆盖新 artifact） | `upsertAll` 改为 `INSERT OR IGNORE + UPDATE` 组合（@Transaction 默认方法）：无 DELETE 语义、无级联，引用行保留 |
| R3 | `deleteConversation` / `updateChildRetention(deleted)` 只删 conversation 行，依赖（未生效的）FK 级联清理 `message_node` / `artifact_reference` | 删除会话/child 留悬挂行（FK ON 后已自动级联，但该路径历史上一直在留残渣） | 两处事务内补显式清理（`messageNodeDAO.deleteByConversation` + `artifactStore.deleteReferencesOfConversation`）——与 FK 级联双路径幂等 |
| R4 | 历史残留：FK OFF 期间删除路径遗留的悬挂 `message_node` / `artifact_reference` 行 | FK ON 后 `PRAGMA foreign_key_check` 恒有违规行；悬挂行干扰引用计数与 GC 判定 | v7 migration 增加存量悬挂清理（conversation 不存在的 node、node 不存在的 reference），新增迁移用例 m9 |

新增行为锁定用例：m9（悬挂清理）、m10（IGNORE+UPDATE upsert 在 FK ON 下不级联删除引用行）。既有 m3/m4/m8 的 FK 级联/拒绝用例继续通过（它们显式 `PRAGMA foreign_keys = ON`，与生产 onOpen 开关语义一致）。

### 13.6 审查确认（无缺陷项）

- 写入顺序 FK 安全：`insertConversation` / `insertConversationTree`（fork）均为父 conversation 行先于 nodes；`applyMutation` 的 node upsert 时会话已在库。
- 引用行 FK 安全：`resolveNodeReferenceEntities` 仅在 artifact 行存在时（`getByPath` 命中）插入引用行。
- `ConversationDAO.insert` 无 REPLACE 策略（默认 ABORT），不存在 conversation 行替换级联。
- executeCall 重写行为等价：preflight 顺序/lease 时机/附件回滚/撤权监听/终态分类与 HEAD 逐段对照一致；差异仅为结构化（四阶段 + 唯一 finally）。

## 14. 已知问题：落地后 UI 链路复查与修复记录（2026-08-24）

> 本节为 v1 落地后的缺陷记录与修复实录。三项缺陷均属"行为恢复"级修复，不改变 §10–§13 的核心方向；但根因揭示了三类系统性适配风险，记录于此供后续重构对照。其后续全库审计见 §15。

### 14.1 缺陷 A：头像剪裁后静默不生效（文件生命周期竞态）

**现象**：助手头像选择图片、剪裁并确定后头像未更新（保持纯色默认头像），无任何错误提示；偶发另见"裁剪失败"toast（该 toast 为 uCrop 自身加载/解码失败的既有独立路径，与本缺陷无关）。

**根因**：修订记录 10 将 `FilesManager` 写入路径 suspend 化（`createChatFilesByContents` 等改 `withContext(Dispatchers.IO)`）后，UI 调用点被机械包装为 `scope.launch { ... }` 协程化适配。而 `useCropLauncher`（`CropLauncher.kt`）的既有契约是**在 activity result 回调返回前同步删除剪裁输出临时文件**。旧同步实现中消费（文件复制）在回调栈内完成、删除发生在消费之后，时序安全；协程化后消费被调度到 IO 线程排队，回调栈内的同步删除**必然先于**消费执行——`openInputStream` 读到已删除文件，异常被 `runCatching` 吞掉，返回空 URI 列表，`onUpdate` 不被调用，静默失败。

**波及面**（`useCropLauncher` 全部消费方）：

| 调用点 | 暴露条件 |
| --- | --- |
| `UIAvatar.saveAvatarImage`（助手头像 + 用户头像 `ChatDrawer`） | 强制剪裁，无跳过开关——必现 |
| `ChatPage` 拍照剪裁 / 选图剪裁两处 | 仅当 `DisplaySetting.skipCropImage` 为 false（默认 true 绕过剪裁路径） |

**不受影响**（复查确认）：`BackgroundPicker`（源为 content URI，无剪裁无临时文件）；`AssistantBackgroundService.replaceBackground` 两条链路（手工设背景经图片查看器、文生图工具设背景——均为 await 完成后 `finally` 删除源文件，时序正确）；`ChatInput` 粘贴（content URI / 内存字符串）；相机跳过剪裁路径（协程内先消费后删）。

**修复**：输出文件所有权移交消费方——`useCropLauncher` 在 RESULT_OK 后不再代删输出文件（失败/取消分支仍清理，幂等）；`UIAvatar` 与 `ChatPage` 两处在消费协程完成复制后删除源文件。契约变更已在 `CropLauncher.kt` 注释中写明。

**教训**：suspend 化适配时，"临时文件所有权/清理责任"是随调用链传递的隐式契约，机械包一层 `scope.launch` 不转移该契约。凡"回调内同步清理 + 异步消费"的组合都是该模式的高危点，后续重构必须显式标注文件所有权的移交点。

### 14.2 缺陷 B：GC 误删 Settings 域引用的文件（引用面缺口）

**现象**（潜伏，非立即显现）：设置超过 24 小时的助手头像/背景文件，在任意触发 GC 的操作（删除会话、会话压缩）后被回收——Settings 残留指向已删除文件的引用，头像回退纯色、背景消失。手工设背景/文生图设背景实测正常是因为新文件处于 24 小时保护窗口内。

**根因**：旧 GC（`ManagedFileDeletionService` 时代的探测式删除）候选集仅限"本次树变更中不再被引用的文件"，Settings 域文件（头像/背景）从不在消息树中、天然不进候选集。E1 合并为 `ArtifactStore.collectUnreferencedArtifacts` 后 GC 语义变为**全库扫荡式**（artifact 表中全部超保护窗口文件），而豁免条件只实现了 §4.3 KDoc 所写的消息历史引用（`artifact_reference` 投影）——§3.3 明知 Settings 域引用"不进本表"，两条款组合即必然误删。`inspect`/`deletePermanently` 均正确识别 Settings 引用面（`detachMutableReferences`），唯独 GC 遗漏：**引用面清单在三个消费点各写一份，且测试矩阵 C-8 与 GC KDoc 一样未包含 Settings 豁免，GC 此前零测试**。

**修复**（引用面收敛为单一定义）：

- `ArtifactStore.collectMutableReferenceUris(settings)`：Settings 域可变引用全集的唯一所有者——助手背景、助手头像、用户头像（`DisplaySetting.userAvatar`，复查中发现的原有双重遗漏：GC 豁免与 detach 均未覆盖，用户头像经 `UIAvatar` 同样写入受管 upload 文件）。
- `ArtifactStore.detachSettingsReferences(settings, uris)`：删除时解除全部可变引用的唯一实现，GC 豁免、显式删除解除、删除影响检查（`inspect` 头像计数含用户头像）三处共用上述定义。
- `AssistantBackgroundService.isReferenced` 同步对齐（换背景清理旧背景的引用检查补入用户头像）。
- 复查确认无其他 Settings 字段引用受管文件：自定义字体（`chatCustomFontPath`）存于 `fonts/` 自管目录，不进 artifact 表且形式为相对路径，GC 结构性触达不到。

**测试锁定**：`ArtifactStoreTest` 补 GC 引用面矩阵——助手头像豁免、助手背景豁免、用户头像豁免、消息引用豁免与孤儿回收、显式删除重置用户头像、未回填跳过。

**后续复查补充（当时已知模式的第 5 处）**：`AssistantManagementService.cleanupAssistantFilesIfNotReferenced`（删除助手的 tombstone 文件清理）同样只查其他 Assistant 引用、漏用户头像，已对齐修复。当时已知的五处消费点（GC 豁免、删除解除、影响检查、换背景清理、删助手清理）已对齐；§15 的扩大范围审计进一步发现 UI 直删与 unchecked 删除入口仍未收口，不能据此宣称全域完成。

**教训**：同一引用面（"哪些 Settings 字段持有受管文件 URI"）必须收敛为单一定义并被全部消费方（GC 豁免、删除解除、影响检查、独立清理服务）引用；语义从探测式放宽为扫荡式时，豁免面必须同步扩到与新候选集匹配。

### 14.3 缺陷 C：切换当前助手后界面停留旧助手（derivedStateOf 冻结）

**现象**：抽屉助手选择器切换当前助手后，聊天页顶栏/输入区仍显示旧助手（初始为助手列表第一项），但消息列表正常、实际生成管道使用新助手——展示与交互目的不符、展示内容与实际执行不一致。

**根因**：v1 收敛将 UI 主订阅源改为 Runtime 单流（`ConversationSnapshot`）时，`ChatPage` 中需要 `Conversation` 形状的派生值写成了：

`remember { derivedStateOf { vm.snapshot.value.toConversation() } }`

`derivedStateOf` 的依赖追踪基于 Compose 快照系统，只对 Compose 状态（`mutableStateOf` 等）读取生效；块内读取的是 `StateFlow.value`（普通 Kotlin 属性），**不构成依赖**——派生值永不失效重算，冻结在首次求值（Runtime 初始快照，其 `assistantId` 为 `assistants.firstOrNull()`，即列表第一个助手）。而同页消息列表直接订阅 `snapshot` 热流（`collectAsStateWithLifecycle`）不受影响，生成管道经 `liveConversation` 直读 Runtime 快照——三条链路数据源分裂，造成"消息正常、顶栏冻结"的割裂表现。旧实现（`ConversationSession.state` 经 `collectAsState` 订阅）不存在此问题，属 v1 迁移时引入。

**波及面**（全库同模式复查）：`derivedStateOf` 块内读取 `.value` 的用法全库共两处——本处（`vm.snapshot` 是 `StateFlow`，冻结成立）与 `UpdateCard` 的 `errorDismissed`（其类型是 `mutableStateOf` 产出的 Compose State，依赖追踪正常，**非缺陷**；同名 `.value` 不同类型，勿混淆）。除此之外无其他波及。

**修复**：`ChatPage` 改为两段式——`vm.snapshot.collectAsStateWithLifecycle()` 先将流订阅入组合树，`derivedStateOf { snapshotState.value.toConversation() }` 再从 Compose State 派生（保持"每 snapshot 变化至多一次转换、共享同一派生实例"的注释意图）；消息列表同步复用同一订阅实例。

**教训**：`derivedStateOf` 的块内只能依赖 Compose 快照状态；Flow/StateFlow 必须经 `collectAsState`/`collectAsStateWithLifecycle` 入组合树。跨数据源（快照流 vs 派生值 vs 服务直读）的迁移中，任何一条未走订阅协议的链路都会造成静默的数据面分裂。

### 14.4 遗留风险与手测建议

- UI 时序类缺陷（缺陷 A/C）依赖 activity result / uCrop / 真机导航链路，JVM 单测无法锁定，建议手测清单：助手头像设置、用户头像设置（抽屉侧栏）、关闭"跳过剪裁"后的聊天选图/拍照附加、抽屉切换当前助手后顶栏/输入区即时跟随。
- 缺陷 B 的历史损伤不可逆：若用户此前已有头像/背景被误删，文件无法恢复，重新设置即可；Settings 中残留的死引用会在下次 detach/GC 时被自然清理。
- 理论性风险记录（未修）：`ChatInput` 粘贴图片的 clipboard content URI 在协程化后为异步消费，若用户在消费前更换剪贴板内容，URI 权限可能已失效——与重构前风险等价（剪贴板权限时效极短），实际未见报告，暂不处理。
- 本轮修复未变更 `versionCode`/`versionName`。

### 14.5 共性架构缺陷归纳

缺陷 A/B/C 并非孤立失误，反映本轮重构的三类系统性风险。逐类排查结论与防范守则如下，供后续重构对照：

**模式一：两套状态体系的依赖追踪语义混用（缺陷 C 根因）**

重构引入 Runtime 单流（`StateFlow<ConversationSnapshot>`）后，UI 侧存在两套状态体系：Compose 快照状态（`mutableStateOf`/`collectAsState` 产物，参与依赖追踪）与 Kotlin Flow（不参与）。凡在 `derivedStateOf`/`remember` 计算块内直读 `Flow.value` 的代码，派生值都会静默冻结——编译期无警告、运行期无异常，仅表现为"部分 UI 停留在旧值"，且因同屏其他区域（正确订阅的）仍在流动而极具迷惑性。

排查结论：全库 `derivedStateOf` 共四处，仅 `ChatPage` 一处为 Flow 冻结（已修）；其余三处读取 Compose 状态（安全）。守则：**Flow 必须经 `collectAsState`/`collectAsStateWithLifecycle` 入组合树后方可参与派生；`derivedStateOf` 块内禁止出现 `Flow.value`。** 同名 `.value` 在 `StateFlow` 与 Compose `State` 上语义完全不同（`UpdateCard` 的 `errorDismissed` 即后者，勿误判）。

**模式二：suspend 化适配撕裂隐式的资源生命周期契约（缺陷 A 根因）**

"调用方协程化"（机械包一层 `scope.launch`）保持签名兼容，但**不转移隐式契约**：回调栈内"先同步清理临时资源、消费方假定资源仍存活"的时序约定在消费被调度到其他线程后必然破裂，且失败被 `runCatching` 静默吞没。凡"activity result / 生命周期回调内同步清理 + 消费方协程化"的组合都是高危点。

排查结论：全库 `useCropLauncher` 消费方（`UIAvatar`、`ChatPage` 两处）已全部修复（输出文件所有权移交消费方）；其余 launch 适配点（`ChatInput` 粘贴、`ChatVM`、`SettingFilesPage`、`BackgroundPicker`）源资源由系统或 service 全程持有，无此模式。守则：**suspend 化改造时必须显式标注文件/资源所有权的移交点；"回调内同步清理"与"异步消费"不得共存。**

**模式三：同一引用面在多个消费点各写一份清单（缺陷 B 根因）**

"哪些 Settings 字段持有受管文件 URI"这一引用面事实，分散在 GC 豁免、删除解除（detach）、删除影响检查（inspect）、换背景清理（`AssistantBackgroundService.isReferenced`）、删助手清理（`AssistantManagementService.cleanupAssistantFilesIfNotReferenced`）五处手写。任何一处漏写/漏更新（如用户头像 `displaySetting.userAvatar`）即造成该消费点的数据丢失；且 GC 语义从探测式放宽为全库扫荡式时，候选集扩张但豁免面未同步——语义迁移放大了清单不一致的后果。

排查结论：当时已知的五处已对齐（含本轮补齐的两处），核心定义初步收敛于 `ArtifactStore.collectMutableReferenceUris`/`detachSettingsReferences`。守则：**引用面必须单一定义、多处引用；清理/回收语义放宽时，豁免面必须与候选集同步扩张。** §15 确认该收敛仍不完整：定义是 private，`AssistantBackgroundService`、`AssistantManagementService` 与 UI 清理路径仍有手写或绕过入口，必须在 V1C 物理删除。

**共性教训**：三类模式的共同点是**重构保持编译兼容的适配方式（包协程、改派生写法、复制清单）让契约破损静默化**——编译通过、测试通过（测试矩阵本身按新契约写成）、仅真机特定交互序列暴露。后续同类规模重构应显式列出"隐式契约清单"（资源所有权、状态依赖体系、引用面事实）并逐项标注迁移方式，而非依赖调用点机械适配。

---

## 15. 当前实现全量收敛审计（2026-08-24）

> 本章不是对 §14 三个现象的局部复盘，而是以 §2.1 分层、§2.2 不变式、§10 pre 缺口和 §11 V1 完成态为基准，对当前生产代码、调用链、失败路径与契约测试重新审计。结论以当前实现为准；本章列出的缺口全部进入 §16，禁止再标记为“可选优化”或“后续清理”。

### 15.1 总体裁决

V1 的核心方向成立，且不应回退：`ConversationSnapshot` 单事实流、纯 reducer、delta 持久化、Master/Target 共用 `TurnEngine`、执行事实驱动恢复、自引用 FK 与 Child 定点关系都为后续演进提供了正确地基。

但“V1 已完成全部结构目标”的结论不再成立。当前实现仍混有三类未完成工作：

1. **与目标直接冲突的实现偏离**：第二写入口、active/inactive 双写策略、unchecked 文件删除、恢复整对象白名单、UI 双状态形状。
2. **原计划本身遗漏的正确性契约**：提交失败语义、流式并发与 stale turn 隔离、投影崩溃窗口、跨文件系统/数据库创建协议、GC 可达性原子判定。
3. **为“固定文件数 / 负代码 / API 兼容”保留的结构债**：大类继续承担多个业务域、兼容转换扩散、旧命名与无效字段残留、契约测试只锁定符号而没有锁定语义。

对 §14 三项问题的性质应明确区分：

| 问题 | 直接性质 | 架构含义 |
| --- | --- | --- |
| A 剪裁后文件先删 | 实现/迁移 bug | suspend 化时没有把临时文件所有权写进接口和类型，原验收遗漏生命周期契约；不需要推翻 Runtime 方向，但必须以 OwnedArtifact 式所有权消除同类复发面 |
| B GC 误删 Settings 引用 | 原方案缺口落成的实现缺陷 | GC 候选集扩大，却没有先建立全域可达性定义、唯一引用 owner 与原子 recheck；这是局部 Artifact 架构本身不完整，不是简单补一条 if 即可关闭 |
| C 助手切换 UI 冻结 | 实现/迁移 bug | 错用 Compose/Flow 订阅语义是直接根因；同时 `Conversation` 兼容形状与 Snapshot 双消费让错误更容易发生，说明状态形状迁移仍未物理完成 |

因此当前状态可作为继续收敛的基线，但不能作为架构封板状态。V1C 必须完成 §15.3 的全部关闭项后，才能重新声明“V1 完成”。

### 15.2 已确认成立的地基

| 能力 | 当前事实 | 裁决 |
| --- | --- | --- |
| Snapshot 唯一事实流 | `ConversationRuntime` 只暴露 `StateFlow<ConversationSnapshot>`，旧 `state/getConversationFlow` 已删除 | 保留 |
| reducer 与 delta | `ConversationReducer` 为纯函数，未变节点引用稳定；`ConversationRepository.applyMutation` 按 dirty nodes 写入 | 保留 |
| 生成提交协议 | Master/Target 都使用 `TurnEngine.start/onCheckpoint/bind` | 保留并加强事务与状态机 |
| 恢复索引 | 非终态执行由 `turn_execution` 状态索引与关系列定点查询 | 保留 |
| Child 关系 | v7 自引用 FK、`tool_execution.child_conversation_id` 已落地 | 保留并补强写入失败语义 |
| 职责域拆出 | `GenerationSideEffects`、`SubAssistantRunGate`、`SubAssistantResultProjection` 已形成明确领域 | 保留，但移除门面穿透和重复基础设施 |

### 15.3 未收敛项清单

#### A. Runtime、状态与写入协议

| ID | 当前实现 | 问题性质与后果 | V1C 关闭标准 |
| --- | --- | --- | --- |
| F-01 | `ConversationRuntimeRegistry.getOrCreateSession` 同步构造带默认 Assistant 的空 Conversation，随后 `initializeConversation` 再异步覆盖 | Snapshot 在初始化完成前不是事实，而是伪造占位；缺陷 C 的“首个 Assistant”现象即由此放大。不存在显式 Loading/Ready/Missing 状态 | Registry 只能发布已从 DB 装载或经显式 Create 创建的 Ready Runtime；加载期使用独立 readiness 状态，禁止伪造 Conversation |
| F-02 | `ConversationRuntime.loadSnapshot(Conversation)` 是公开、无锁的整对象替换入口，并被初始化、附件 backfill、收藏投影、恢复和助手迁移共同调用 | 它是 `submit` 之外的第二写入口，可与 stream/command 竞态；还会无条件重置 `persistedState`。附件 backfill 调用后把未落库树误认作已持久化基线，违背“随 checkpoint 落库”的注释 | hydration 入口改为一次性、受 Registry 状态机约束的 `installLoadedSnapshot`；运行期结构变更一律走命令；附件 backfill 形成显式 durable command；收藏由独立 UI projection 合并 |
| F-03 | `applyStreamingDelta` 使用 `_snapshot.value = _snapshot.value.copy(...)`，虽接收 turnId/messageId 却不校验当前 owner | read-modify-write 非原子，可与 `submit/loadSnapshot` 相互覆盖；旧 turn 的迟到 chunk 可重新占据 activeTurn | 由 `TurnHandle(epoch, turnId, assistantMessageId)` 独占更新；使用原子 `update` 并校验 handle；stale delta 必须是可观测的拒绝而非静默覆盖 |
| F-04 | `submit` 对所有非流式命令统一清空 `activeTurn` | title/pin/folder/收藏等与生成正交的命令也会清空流式投影，下一 chunk 再重建，造成短暂数据面撕裂 | 只有匹配 TurnHandle 的 checkpoint/finalize 或明确冲突的树命令可以改变 activeTurn；Header 命令保持 activeTurn |
| F-05 | `submit` 先发布 Snapshot，再 `runCatching` 持久化；失败仅置 `pendingPersist`，对调用方仍返回成功，重试依赖下一条命令 | “onCheckpoint 是 awaited durability boundary”实际不成立；无下一命令或进程退出时丢 durable state，UI 与上层副作用会基于未提交状态继续运行 | durable command 固定为 reduce → 同事务持久化 → publish；失败向调用方传播且不发布。允许先发布的仅有明确非 durable 的 streaming projection |
| F-06 | ChatService 与 GenerationSideEffects 各有一份 `submitHeaderUpdate + fallback`；Runtime 不活跃时直接调用 Repository 窄列写 | 同一命令存在 active/inactive 两套实现，Registry 创建与 fallback 之间有竞态；updateAt、FTS、错误传播语义不一致 | 建立全局唯一 `ConversationCommandCoordinator`，按 conversationId 串行化 resident/non-resident 命令；Application/UI 不再判断 Runtime 是否存在，也不持有 fallback |
| F-07 | 生产代码广泛调用 `ConversationSnapshot.toConversation()`；ChatList 同时接收 Conversation 与 Snapshot，ChatPage 对同一 flow 建立两次 collector | 兼容形状重新进入 Runtime、恢复、子助手和 UI；同屏可读到两个不同拍次，且流式期间重复 O(N) 投影 | 生产路径物理删除 `toConversation`；组件、selector、恢复纯函数直接使用 Snapshot/header/nodes；一个页面对同一 Snapshot 只订阅一次 |
| F-08 | `ApplyStreamingDelta` 命令无调用方；`GenerationCommand/DomainCommand` 只作标签；`BeginTurn.fromNodeId/onStart` 未被 reducer 使用；Runtime Registry API 与大量局部变量仍使用 Session 命名 | 形成虚假语义和无意义兼容，违反产品词汇映射 | 无行为的命令/字段/标签删除或实现其不变式；`getSession/getOrCreateSession/...` 全部改为 Runtime 语义，契约测试禁止 Runtime 域出现旧 Session 命名 |

#### B. Turn、执行事实与恢复

| ID | 当前实现 | 问题性质与后果 | V1C 关闭标准 |
| --- | --- | --- | --- |
| F-09 | `TurnEngine.start` 先提交 `BeginTurn`，再提交空 `CommitCheckpoint(RUNNING)` | assistant 槽和 RUNNING 执行事实跨两个事务；进程死在中间会留下无执行事实的空槽，与原 `BeginTurn` 原子可见承诺冲突 | 合并为一个 `StartTurn` durable command，在同一事务写消息槽与 RUNNING turn fact，并一次发布 Snapshot |
| F-10 | 每个 checkpoint 先查询旧 turn 以保留 createdAt，再以 `@Upsert` 覆盖整行；终态保护依赖 Runtime 内存集合 | 每 checkpoint 多一次读；迟到 RUNNING/STARTED 可覆盖 durable 终态；重启后内存保护消失 | DAO 使用 INSERT-ONCE + 合法状态 CAS；终态不可逆、重复终态幂等、非法回退返回明确冲突；删除 `finalizedTurns` 与 query-before-upsert |
| F-11 | `DelegationCoordinator` 对 `reportChildConversation` 使用 `runCatching` 仅日志后继续运行 | 调用↔Child 的关键 durable 关系可能未写入，而 Target 已开始；崩溃恢复将失去定点链路 | Child 创建、tool execution STARTED 和 childConversationId 关系必须在 Target 启动前完成强制提交；失败则补偿 Child/附件并终止调用 |
| F-12 | `FinishedReason?`、`TurnEvent.Finished(reason?, error?)`、`MasterTurnOutcome` 与 Target 自身映射并存 | null、error 与 reason 组合需要调用方二次解释，终态语义重复且可形成非法组合 | TurnEngine 输出非空 sealed `TurnOutcome`；Master/Target 只消费同一 outcome，状态/原因/异常映射只有一份 |
| F-13 | `AssistantDataRecovery` 与 ChatService 分别触发 `recoverInterruptedRuns`/`recoverInterruptedTurns`，并维护 recoveryGate + turnRecoveryReady 两道门；异常多为记录日志后继续 Ready | 启动恢复不是单一入口且是 fail-open；恢复失败后仍允许写入，可把未收口事实继续复杂化 | 单一 `ApplicationRecoveryCoordinator` 按固定次序执行并暴露 Loading/Ready/Failed；写操作仅在 Ready 开放，Failed 可重试或进入安全模式 |
| F-14 | `TurnRecovery` 同时承担启动恢复、被新 turn 取代时收口、普通 mutation 前清理、Child retention、工具行收口和大量纯投影 | “Recovery 唯一所有者”被扩大为杂项生命周期所有者；与 §11 原定 Child retention 属 Delegation 域的裁决偏离 | TurnRecovery 仅保留进程恢复；正常 supersede/finalize 归 TurnFinalization；Child retention/删除归 SubAssistantLifecycle；纯函数归对应 projection |
| F-15 | `TurnRecovery.submitRecoveredTree` 在无 Runtime 时白名单调用 deprecated `updateConversation(Conversation)` | 整对象写白名单仍是第二协议；契约测试通过白名单固化了债务 | 恢复也走 `ConversationCommandCoordinator`/专用 recovery mutation；删除 deprecated API 与全部白名单 |
| F-16 | `SubAssistantRunGate` 暴露 tryAcquire/release，正确性仍依赖 Coordinator 的手写 finally | 当前路径虽已集中，但所有权没有编码进 API，新调用方可再次漏 release | 提供 `withLease`/AutoCloseable 风格作用域 API，原始 release 私有化；取消、异常、materialize 失败全部由结构化作用域测试锁定 |

#### C. Projection 与持久化一致性

| ID | 当前实现 | 问题性质与后果 | V1C 关闭标准 |
| --- | --- | --- | --- |
| F-17 | `artifact_reference` 在 message/conversation 事务提交后执行 delete+insert | 进程可死在事实提交后、投影更新前，或死在投影 delete 后、insert 前；GC 会把 false-negative 当成无引用并删除事实文件 | 引用 token 在事务前计算，`message_node + artifact_reference + execution facts` 在同一 Room 事务提交；GC 只读取强一致引用 |
| F-18 | `backfillReferences` 遇到 JSON 解析失败时跳过节点，最终仍写全局 backfill=true | 不完整投影被永久标记完成，随后 GC 可误删解析失败节点引用的文件 | 任一节点失败则整个 backfill 不置位并报告定位；采用事务或分片进度，完成后做全量一致性校验再开放 GC |
| F-19 | FTS 在事实事务后 best-effort 更新；Runtime 的纯 title UpdateHeader 不调用 `updateConversationTitle`；FTS 更新自身吞异常 | 崩溃/异常产生永久 stale index；active 与 inactive title 路径结果不同 | node delta、delete、title/updateAt 投影与事实放进同一 DB 事务；若底层限制无法同事务，则使用 durable dirty journal 且恢复前完成，禁止裸 best-effort |
| F-20 | Repository 仍公开整对象更新、header 窄列更新、执行事实直接更新等多种写原语；SingleWriterContractTest 只限制 DAO 所在文件 | “DAO 只有一个持有者”不等于“业务只有一个写协议”，Application 仍能选择任意语义 | Repository 写原语降为 internal 且只被 CommandCoordinator/Recovery/创建事务调用；契约测试检查调用图而非文件位置 |
| F-21 | `updateChildRetention` 对 retained Child 删除整树再全量插入 | 长 Child 的 retention 成本随完整历史增长，重新引入整聚合写放大 | retention 产出 per-child delta，经同一 command/mutation 通道写入；复杂度与被裁剪节点数相关 |

#### D. Artifact 所有权与生命周期

| ID | 当前实现 | 问题性质与后果 | V1C 关闭标准 |
| --- | --- | --- | --- |
| F-22 | FilesManager 与 ArtifactStore 同时持有 ArtifactDAO；ManagedLocalArtifactStore 再包一层 FilesManager | “Artifact metadata 归 ArtifactStore”没有落实；所谓构造依赖成环被当成长期架构，而不是拆分低层 payload 能力 | 新设无 DAO 的 ArtifactPayloadStore；ArtifactStore 为 ArtifactDAO、创建、查询、引用、GC、删除的唯一所有者；FilesManager 只保留非托管 IO/导出 |
| F-23 | `deleteChatFiles`、`deleteManagedFilePermanently`、`deleteManagedFolderPermanently` 为 unchecked 入口，UI、Coordinator、AttachmentResolver、ManagedLocalArtifactStore 可直接调用 | 绕过 CAS、Settings detach、引用检查与恢复协议；`deleteChatFiles` 还先删文件、异步删 DB | unchecked API 私有化到 ArtifactStore；调用方只能使用显式 `discardUnpublished`、`deleteUserRequested`、`collectGarbage` 三类有所有权语义的入口 |
| F-24 | GC 直接筛选后调用 unchecked delete，不走 ACTIVE→DELETING；候选判断与新引用建立之间没有原子屏障 | 旧 artifact 可在检查后被新消息/Settings 引用，随后仍被删除；违反“所有破坏性操作 durable/幂等/可恢复” | GC 在统一生命周期锁/事务下重新验证全部 roots 并 CAS 到 DELETING；引用建立拒绝 DELETING artifact；删除恢复走同一状态机 |
| F-25 | 文件先写最终路径、后插 artifact 行；进程死在中间会留下应用自己制造的 untracked 文件，启动只日志不处理 | “文件+记录原子、untracked 只来自外部”表述不成立 | 使用专用 staging 目录与 `CREATING→ACTIVE→DELETING` 状态；启动可判定地完成或回滚 CREATING，绝不产生无法归因的最终路径文件 |
| F-26 | Settings 文件引用定义仍是 ArtifactStore private 函数；Background/Assistant 服务和多个 VM 继续手写引用判断或直接删除 | 第 14 节只对齐了当时已知清单，并未形成唯一 owner；新增字段仍会再次漏写 | 建立 ArtifactReferencePolicy/ArtifactSettingsCoordinator；所有头像/背景写入、替换、detach、inspect、GC、删助手共享同一实现与同一互斥边界；UI 不再清理文件 |
| F-27 | 文件写入函数对单项失败多以 runCatching 返回空/部分列表；临时资源所有权靠注释和 finally 约定 | 缺陷 A 的静默失败模式仍可在新调用链复现；调用者无法区分空输入、部分成功和持久化失败 | 返回 typed per-item result/OwnedArtifact；所有权从 staging→created→published/rolledBack 由类型表达，禁止以空列表代表失败 |

#### E. Application/UI 职责与效率

| ID | 当前实现 | 问题性质与后果 | V1C 关闭标准 |
| --- | --- | --- | --- |
| F-28 | ChatService 仍持有生成、CRUD/fork、Runtime facade、错误总线、进程生命周期和二十项左右依赖，并在内部手工构造 GenerationSideEffects | 达到行数目标但没有达到“装配 + 副作用薄壳”；依赖和生命周期仍集中 | 拆为 MasterTurnCoordinator、ConversationApplicationService、GenerationSideEffects、ChatErrorStore；全部由 DI 注入；ChatService 删除或只保留无状态兼容期为零的 facade 后立即删除 |
| F-29 | ChatVM/ChatDrawer/SubAssistantDetailVM 等直接依赖 ConversationRepository、FilesManager 或 ConversationRuntimeRegistry；ChatService 暴露 sideEffects/mcpManager 内部对象 | UI/Application 边界穿透，调用方可以绕开命令、生命周期和恢复门禁 | ViewModel 只依赖 Query/Command/Artifact use-case；Runtime Registry 与 Repository 不出 service/data 边界；禁止对象穿透式属性 |
| F-30 | ChatPage 的 Conversation 派生、ChatList 的 Snapshot 渲染、SubAssistantDetail 的逐 chunk `toConversation` 同时存在 | 每个 chunk 可能发生多次 O(N) 浅投影/扫描，且不同 consumer 不保证同一拍次 | UI 以 Snapshot 或稳定 UiModel 为唯一输入；selector 对相关字段 `distinctUntilChanged`；每帧最多一次 renderNodes，无历史无关扫描 |

#### F. 制度与验收

| ID | 当前实现 | 问题性质与后果 | V1C 关闭标准 |
| --- | --- | --- | --- |
| F-31 | I1/I7 等 grep 契约只禁止少量旧符号，注释仍引用过期白名单；没有禁止 fallback、loadSnapshot、toConversation、unchecked delete 和 UI 直连 | 测试把“部分收敛”误认证为“完成”，甚至固化例外 | 重写架构契约测试，所有 §16.2 禁止项零命中；调用边界用编译可见性与依赖测试双重锁定 |
| F-32 | 缺少 stream×command 竞态、stale turn、持久化失败、投影 crash point、GC×新引用、staging 恢复、recovery fail-closed 等测试 | 正常路径全绿无法证明生命周期与故障语义 | 增加确定性并发测试、每个事务边界 fault injection、process-death instrumentation 与恢复幂等测试 |
| F-33 | Migration_6_7 仪器执行、§14 真机链路和 §6.3 性能基线仍没有完成证据 | 只能证明编译/JVM 结构，不能证明设备行为、迁移与量化效率 | V1C 交付前必须完成全量 JVM、Debug/Release、Lint、v5→最新 DB 仪器迁移、真机 UI 清单和固定数据集性能报告 |
| F-34 | 固定“Runtime 5 文件”、主文件行数、净负代码被当作架构不变式 | 数量指标促使职责被塞进 TurnRecovery/ChatService，并为避免新增 owner 保留 DI 环和门面 | 废止数量型硬约束；允许为唯一职责新增对象。完成标准改为依赖方向、唯一 owner、无旁路、失败语义和验收证据 |

### 15.4 根因归纳

本轮未完成的根因不是“代码还不够少”，而是完成定义偏向静态形状：删除了旧类、减少了行数、grep 不到旧 API，就被视为切换完成；但资源所有权、事务原子性、并发 epoch、错误传播和跨存储恢复没有成为同等级验收项。

V1C 必须采用相反的裁决顺序：

1. 先确定事实 owner、事务边界和失败语义。
2. 再确定调用方只能获得哪些能力，借助可见性和类型消灭旁路。
3. 然后迁移所有消费方并物理删除旧符号。
4. 最后以故障注入、进程死亡、真机和性能证据验收；行数只作观察值，不参与通过/失败裁决。

---

## 16. V1 Completion 阶段计划（V1C：架构封板）

> 阶段定义：V1C 不是 V2 功能开发，而是把 pre/V1 已声明的架构目标真正完成。范围等于 §15.3 全部缺口；任何条目不得降级为 TODO、deprecated 转发、白名单、兼容 facade 或“后续优化”。如实施中发现同根因新路径，必须并入本阶段，不以原清单外为由排除。

### 16.1 最终架构

```text
UI / ViewModel
  ├─ ConversationQueryService / SubAssistantDetailReader
  ├─ ConversationApplicationService（用户命令、创建、删除、fork）
  └─ ArtifactUseCase（选择、发布、替换、显式删除）
                    │
Application Coordinators
  ├─ ConversationCommandCoordinator（所有 resident/non-resident durable command 的唯一入口）
  ├─ MasterTurnCoordinator
  ├─ DelegationCoordinator（仅 active sub-assistant run）
  ├─ SubAssistantLifecycle（lineage / retention / child delete）
  ├─ TurnFinalization（正常 supersede/cancel）
  ├─ TurnRecovery（仅进程恢复）
  └─ ApplicationRecoveryCoordinator（唯一启动门禁）
                    │
Runtime / Domain
  ├─ ConversationRuntime（Ready Snapshot + active TurnHandle + job/TTS runtime state）
  ├─ ConversationReducer（Snapshot 纯函数）
  ├─ TurnEngine（唯一生成提交与非空 TurnOutcome）
  └─ ArtifactStore（artifact 与全部引用/生命周期的唯一 owner）
                    │
Data / Payload
  ├─ ConversationRepository（internal persistence primitives）
  ├─ Room transaction（message + execution facts + artifact refs + FTS）
  ├─ ArtifactPayloadStore（纯磁盘，无 DAO）
  └─ SettingsStore（artifact 字段经 ArtifactSettingsCoordinator 更新）
```

最终不变式：

1. Ready Snapshot 才是事实；不存在默认 Assistant/空树伪造的可用 Conversation。
2. 所有 durable Conversation 命令只经 `ConversationCommandCoordinator`；resident 与 non-resident 共享同一 reducer、事务和错误语义。
3. durable command 必须持久化成功后发布；streaming 是唯一允许先发布且不落库的投影。
4. 每个 turn 只有一个 TurnHandle；start/checkpoint/finalize 由 durable CAS 状态机约束，stale worker 无写权限。
5. 会影响删除正确性的 projection 必须与事实同事务；不得让 eventually-consistent false-negative 驱动 GC。
6. ArtifactStore 是 ArtifactDAO 与托管文件生命周期的唯一 owner；磁盘层不知道 metadata，UI 不知道删除协议。
7. 启动恢复只有一个入口且 fail-closed；恢复失败时禁止继续 durable 写入。
8. UI 只消费 Snapshot/UiModel 和 Application ports；不直连 Repository、Runtime Registry、FilesManager。
9. 无 deprecated 转发、兼容白名单、双形状、unchecked destructive API、无意义旧命名或未使用协议字段。
10. 完成由自动化故障测试、设备验收和性能证据共同证明，不由行数证明。

### 16.2 必须物理消失的残留

- 生产代码中的 `ConversationSnapshot.toConversation()`。
- 运行期 `loadSnapshot(Conversation)`、`updateConversationState`、`updatePersistedConversation`。
- `ConversationRepository.updateConversation` 及其恢复白名单。
- ChatService/GenerationSideEffects 的 `submitHeaderUpdate(...fallback...)` 双实现。
- Runtime Registry 的 `getSession/getOrCreateSession/getSessionsSnapshot` 等 Session 命名。
- 无调用的 `ApplyStreamingDelta` command、无约束价值的 marker interface、未使用的 BeginTurn 字段。
- `FilesManager` 对 ArtifactDAO 的依赖及 `deleteChatFiles/deleteManagedFilePermanently/deleteManagedFolderPermanently` 公共入口。
- Assistant/Background/UI 中手写的 Settings artifact 引用清单和文件清理。
- `TurnEvent.Finished(reason?, error?)`、独立 `MasterTurnOutcome` 与 null=某终态的约定。
- UI/ViewModel 对 ConversationRepository、ConversationRuntimeRegistry、FilesManager 的架构域直连。
- ChatList 的 Conversation + Snapshot 双参数及同页重复 Snapshot collector。
- `finishInterruptedPendingTools` 等与实际语义不符的旧命名。
- 架构测试中的任何白名单；导入/恢复改用显式专用 API，不借 deprecated 逃逸。

### 16.3 工作流 Q：Runtime 权威与统一命令入口

| # | 动作 | 交付 |
| --- | --- | --- |
| Q1 | 新建 | `ConversationCommandCoordinator`：conversationId keyed mutex；resident/non-resident 命令统一调度；HeaderCommand 可 O(1) 读取 header，TreeCommand 才加载 nodes |
| Q2 | 重构 | Registry 引入 Loading/Ready/Missing/Failed 生命周期；只允许显式 create 或持久化 load 进入 Ready；全系 Session 命名改 Runtime |
| Q3 | 改造 | durable 命令执行顺序固定为 reduce → 单事务 persist → publish；返回 typed success/conflict/failure，禁止吞异常 |
| Q4 | 改造 | Runtime 只保留 committed Snapshot 和非 durable activeTurn；移除 repository nullable、pendingPersist 与“下一命令重试”协议 |
| Q5 | 改造 | streaming 通过 TurnHandle + epoch 原子 update；Header 命令不清 activeTurn；树冲突命令显式取消/等待 active turn |
| Q6 | 迁移 | attachment backfill 变为 durable `BackfillAttachmentRefs`；Favorite 通过 Favorite flow/UiProjection 合并，不改 durable Snapshot |
| Q7 | 删除 | §16.2 中 Conversation 兼容转换、load/fallback/whole-write 残留 |

### 16.4 工作流 R：Turn 原子协议与状态机

| # | 动作 | 交付 |
| --- | --- | --- |
| R1 | 合并 | `StartTurn` 单命令、单事务写 assistant slot + RUNNING turn fact，返回唯一 TurnHandle |
| R2 | 改造 | turn/tool DAO 使用 INSERT-ONCE + 合法状态 CAS；终态不可逆，重复请求幂等，非法回退可诊断 |
| R3 | 删除 | 每 checkpoint 的 `getTurnExecution` 读与 Runtime `finalizedTurns` 集合 |
| R4 | 强化 | Child 创建、tool STARTED、childConversationId 关系在 Target 启动前完成；失败执行附件/Child 补偿并返回 failed tool result |
| R5 | 统一 | 非空 sealed `TurnOutcome` 作为 Master/Target/SideEffects 唯一终态；状态、reason、error 映射集中于 TurnEngine |
| R6 | 结构化 | SubAssistantRunGate 提供 scoped lease；原始 release 私有化 |

### 16.5 工作流 S：Projection 强一致

| # | 动作 | 交付 |
| --- | --- | --- |
| S1 | 事务化 | 在进入 Room 事务前解析 changed node 的 artifact tokens；事务内同步 message_node 与 artifact_reference |
| S2 | 事务化 | FTS node delta/delete/title/updateAt 与 Conversation mutation 同事务提交；删除吞异常的 best-effort 更新 |
| S3 | 修复 | backfill 使用事务/分片进度；任一反序列化失败不置完成标记，并输出 conversationId/nodeId |
| S4 | 校验 | 启动恢复包含 projection consistency check；支持全量重建后再开放 GC/search Ready |
| S5 | 删除 | Repository 所有事务后 projection 手工调用和因此产生的 GC 空窗 |

### 16.6 工作流 T：Artifact 唯一所有权与可恢复协议

| # | 动作 | 交付 |
| --- | --- | --- |
| T1 | 拆底层 | `ArtifactPayloadStore` 只做 staging/write/rename/stat/delete，不持有 DAO；ArtifactStore 独占 ArtifactDAO |
| T2 | Migration | 最新 schema 增加 CREATING 状态及必要索引；创建走 staging → CREATING row → atomic rename → ACTIVE，冷启动可完成或回滚 |
| T3 | 统一删除 | 用户删除、GC、folder delete、创建补偿全部进入同一 CAS 状态机；仅 OwnedArtifact 可 discardUnpublished |
| T4 | 收敛引用 | `ArtifactReferencePolicy` 定义 Settings roots；`ArtifactSettingsCoordinator` 串行化引用字段更新与 GC recheck |
| T5 | 防竞态 | GC 在提交 DELETING 前重验消息/Settings roots；DELETING artifact 不允许建立新引用 |
| T6 | 迁移调用方 | Avatar、Background、Attachment draft、Generated media、子助手附件与工具补偿全部改用 typed Artifact API |
| T7 | 删除 | FilesManager metadata/unchecked delete、VM 文件清理、各服务手写引用清单、全库 Conversation 引用扫描 |

### 16.7 工作流 U：Application 与 UI 边界

| # | 动作 | 交付 |
| --- | --- | --- |
| U1 | 拆分 | ChatService 拆为 MasterTurnCoordinator、ConversationApplicationService、ChatErrorStore；GenerationSideEffects 由 DI 注入，不再作为属性穿透 |
| U2 | 归位 | Child retention/lineage/delete 进入 SubAssistantLifecycle；正常 supersede/cancel 进入 TurnFinalization；TurnRecovery 只保留进程恢复 |
| U3 | 查询门面 | 建立 ConversationQueryService/SubAssistantDetailReader，封装 persisted + resident 状态选择；ViewModel 不见 Registry/Repository |
| U4 | UI 迁移 | ChatPage/ChatList/TTS/SubAssistantDetail 仅消费 Snapshot 或专用 UiModel；同一 flow 单订阅；相关字段 selector 使用 distinctUntilChanged |
| U5 | 端口收口 | UI 文件选择、发布、替换、删除只调用 ArtifactUseCase；MCP 等依赖直接按领域接口注入，不经 ChatService 暴露内部对象 |
| U6 | 命名清理 | 按产品语义统一 Runtime/Turn/Artifact/SubAssistantLifecycle 名称，删除旧注释和无行为兼容字段 |

### 16.8 工作流 V：唯一启动恢复与失败可见

| # | 动作 | 交付 |
| --- | --- | --- |
| V1 | 新建 | ApplicationRecoveryCoordinator：Settings ready → artifact staging/reconcile → projection backfill/check → turn/run recovery → pending assistant deletion |
| V2 | 状态 | 暴露 Loading/Ready/Failed(error, retry)；所有 durable command 共享同一门禁，删除 recoveryGate + turnRecoveryReady 双门 |
| V3 | 幂等 | 每个恢复步骤可重复；进程在任一步被杀后下次继续收敛，不覆盖更具体终态 |
| V4 | 失败策略 | 数据完整性/投影/turn 恢复失败禁止进入 Ready；UI 显示可诊断状态，不以日志代替恢复 |

### 16.9 工作流 W：效率封板

| # | 目标 | 可执行判据 |
| --- | --- | --- |
| W1 | checkpoint 与历史解耦 | 每 checkpoint 零 turn 预读；DB 写入仅 changed nodes + 固定数量执行事实 |
| W2 | 流式渲染 | Runtime delta O(1)；每个 UI frame 最多一次 renderNodes；无 `toConversation` 或历史全树 selector |
| W3 | Header 命令 | non-resident title/pin/folder/assistant 更新 O(1)，不得为 Header 加载 message nodes |
| W4 | 恢复 | 健康库零 Conversation 加载；恢复成本只与非终态 execution 数量相关 |
| W5 | Artifact | GC 只扫描索引候选与明确 roots；无 Conversation JSON 全库扫描；创建/删除无 fire-and-forget DB 操作 |
| W6 | Child retention | 写入量只与 retained/deleted delta 相关，不全量重写未变 Child 历史 |
| W7 | 报告 | 固定 50/500/5000 会话与长会话数据集，提交前后 DB 操作数、字节数、p50/p95 和 Compose recomposition 结果；不得只给理论复杂度 |

### 16.10 工作流 X：契约、故障与设备测试

| # | 测试层 | 必须覆盖 |
| --- | --- | --- |
| X1 | 静态契约 | §16.2 禁止项零命中；UI→Repository/Registry/FilesManager、ArtifactDAO 多 owner、unchecked delete、fallback、deprecated 白名单均编译或测试失败 |
| X2 | Runtime 并发 | stream×Header、stream×tree mutation、旧 turn×新 turn、load×submit、100 并发 command；无丢失、无 activeTurn 误清、stale 全拒绝 |
| X3 | Durability fault injection | StartTurn、checkpoint、finalize、Child relation、message/ref/FTS 事务每个边界注入失败；失败不发布，重试幂等，终态不回退 |
| X4 | Artifact fault injection | staging 各死亡点、Settings 引用并发、GC×新消息引用、GC×头像替换、folder delete 中断、backfill 单节点损坏 |
| X5 | Recovery | 每个步骤 kill/restart；Recovery Failed 阻断写入；retry 后唯一收敛；Master/Child/tool 双侧无悬挂 |
| X6 | Migration | v5→最新、v6→最新、v7→最新与 fresh schema 同构；FK/索引/state 数据保全；`foreign_key_check` 为空 |
| X7 | UI 自动化 | Flow→Compose 订阅、助手切换、生成中 title/folder 更新、SubAssistantDetail 流式更新、裁剪成功/失败与错误提示 |
| X8 | 真机 | 助手/用户头像、关闭跳过剪裁后的相册与拍照、后台/杀进程恢复、删除/GC、主/子生成与 ask_user、数据库迁移 |

### 16.11 工作流 Y：删除、文档与最终验收

执行顺序：禁止项清单先加入测试并临时记录命中基线；各工作流迁移后逐项降为零，最后物理删除测试白名单本身。不得以 deprecated、typealias、转发 facade 或“仅内部使用”保留旧协议。

缺口到交付项的闭环映射如下；任何工作流调整都必须同步更新此表，禁止出现只有问题描述、没有 owner 和验收入口的条目：

| 缺口 | 负责交付项 |
| --- | --- |
| F-01–F-08 Runtime/状态 | F-01→Q2；F-02→Q2/Q6/Q7；F-03/F-04→Q5；F-05→Q3/Q4；F-06→Q1；F-07→Q7/U4；F-08→Q2/U6/Y |
| F-09–F-16 Turn/恢复 | F-09→R1；F-10→R2/R3；F-11→R4；F-12→R5；F-13→V1–V4；F-14→U2/V1；F-15→Q1/Q7/U2；F-16→R6 |
| F-17–F-21 Projection | F-17→S1；F-18→S3/S4；F-19→S2；F-20→Q1/S5/X1；F-21→U2/W6 |
| F-22–F-27 Artifact | F-22→T1；F-23→T3/T7；F-24→T4/T5；F-25→T2；F-26→T4/T6/T7；F-27→T2/T3/T6 |
| F-28–F-30 Application/UI | F-28→U1；F-29→U3/U5；F-30→U4/W2 |
| F-31–F-34 制度/验收 | F-31→X1/Y；F-32→X2–X5；F-33→W7/X6–X8/Y；F-34→§16 总体约束/Y |

最终验收必须同时满足：

1. §15.3 F-01–F-34 全部有对应实现提交、测试和证据，状态全部关闭。
2. §16.1 十条不变式全部由测试或类型/可见性结构保证。
3. §16.2 所有残留在生产代码零命中；无新增 TODO/FIXME/兼容白名单。
4. 全量 JVM 测试通过；Debug/Release 构建、Lint、全部 migration instrumentation 与 connected device 测试通过。
5. §16.9 性能报告齐全且不存在随历史规模增长的已禁止路径。
6. §16.10 真机清单逐项记录设备、系统版本、步骤和结果；构建成功不得替代 UI/设备验收。
7. `docs/references/`、AGENTS.md 与实际类名/调用链一致；本计划最终补写实施落点，不保留“待后续”。
8. `git diff --check` 通过，工作区变更全集已审查；版本号与 changelog 仅在用户明确授权时变更。

### 16.12 依赖与实施顺序

```text
Q Runtime/Command ─────► R Turn state machine ──┐
        │                                        │
        ├──────────────► S Projection ───────────┼──► V Recovery ──► X Tests ──► Y Final
        │                                        │
        └──────────────► U App/UI ───────────────┤
T Artifact ──────────────────────────────────────┘

W Performance：从 Q/T 开始记录基线，U/S 完成后封板
```

阶段内允许 Q/S/T 为正确 owner 新增必要组件和最新 schema migration；禁止再用固定文件数或净行数否决结构性必需对象。任何临时桥接必须与其删除在同一工作流、同一合并批次完成，主分支不得出现“新旧协议长期并存”的中间完成态。
