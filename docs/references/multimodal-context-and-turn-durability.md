# 多模态上下文与资源持久化

本文维护附件身份、创建交接、授权读取和请求媒体投影。Artifact 与生成媒体分别拥有生命周期，
文件应用服务只负责编排。通用提交和取消见 [Turn/Step](turn-step-execution.md)，子助手入站与交付见
[子助手附件交付](sub-assistant-architecture.md#附件输入与交付)，界面和导出动作见 [消息渲染](message-rendering-pipeline.md)。

```text
导入或工具产出 → 原文件 owner 创建并保留未发布资源
  → Conversation / Settings 提交持久引用 → 原 owner 发布
  → 请求或页面借用授权读取能力 → 结束后释放
```

请求投影按本次模型能力产生副本，持久消息保留原媒体与附件引用。模型切换无需改写历史；
文件删除后历史仍保留身份，但不能再据旧路径取得文件或自动重执行工具。

## 1. 附件身份与导入

### 稳定附件引用

- 格式：`attachment:<uuid>`（`AttachmentRefs` 前缀常量）。
- 存储：多媒体 part 的 `metadata` 中的 `attachment_ref` 键，merge 语义（保留其他 metadata 键）。
- 子助手交付物：Master 的 `assistant_call` metadata 以 `SubAssistantCallArtifact(ref, artifact)` 保存同一种稳定
  handle；不复制图片 part 来伪造第二份引用事实。
- 唯一性：一个 ref 指向一个逻辑附件；不同 part 可指向同一文件（保持各自 ref）。
- 幂等：`ensureAttachmentRef` 对已带**合法可解析** ref 的 part 恒等返回；仅对非多媒体 part 恒等。导入 / 旧数据 / 异常 Provider metadata 中的非法 ref 会被重建为合法 UUID，保持内部身份可解析。
- `AttachmentReferenceLookup` 只索引多媒体 part 的合法 ref；同一 ref 同时出现在 `Tool.output` 直接媒体与
  `sub_assistant_call.artifacts[]` manifest 是正常双表示，直接媒体优先、manifest 作为回退。等价资源的重复声明复用同一逻辑附件；多个不同 direct 资源或仅 manifest 间的不一致声明 fail-closed，不按遍历顺序选取。

### 导入与元数据

媒体进入持久消息的入口都调用 `ensureAttachmentRef`：

| 入口 | 说明 |
|------|------|
| 用户上传 / 编辑消息 | `ConversationApplicationService` / `ConversationTurnService` 提交前 |
| `generate_image` 产出 | 工具成功时对 Image part 写入附件元数据 |
| MCP 图片内容 | `McpToolCallExecutor` 转本地文件时 |
| base64 图片 | `Base64ImageToLocalFileTransformer` 经 `ArtifactStore` 终态落盘并写入附件元数据 |
| `assistant_call` 注入 Child | 为新 Image part 写入附件元数据；原文件身份不变，不复制 payload |
| 历史消息补章 | 仅在 `ConversationTurnService` 的 START structural preflight 由 `planDurableAttachmentRefBackfills` / `BackfillAttachmentRefs` 执行；会话加载与恢复只做校验，不由 UI/query 旁路补章 |

所有字节型图片入口都在创建 durable artifact 前限制输入规模并校验实际内容：

- MCP `ImageContent` 先限制 base64 字符与解码字节，再按文件头与容器结构识别 MIME；声明 MIME 不能覆盖实际格式。
- `GeneratedMediaStore` 对 URL/base64 结果使用同一尺寸上限与结构检查，并以检测 MIME 决定扩展名；容器头存在不等于图片完整。Gallery 只通过 `resolveCanonicalFile` 解析根目录内路径。
- 编辑器导入返回 `ArtifactDraftItem(uri, displayName, mimeType)`；路由、分享、粘贴和裁剪调用方直接使用托管时已经确定的 metadata，不对托管 `file://` URI 再走外部 ContentResolver 分类。裁剪输出扩展名与 PNG 压缩格式一致。
- 配置编辑器的头像与背景经 `ArtifactUseCase.importSettingsImage` 执行有界复制与结构检查；查看器/生成工具的背景由 `AssistantBackgroundService` 接受原页面、任务或共享定义目标，分别写个人定义或企业主体偏好。两条入口均复用 Artifact 的 Settings root 提交及 pin 移交，失败或取消回滚未发布 artifact，不能直接挂接原始文件路径。
- 生成中预览、参考图片与助手背景只接受经结构检查的图片，文件名与扩展名由实际内容生成，不信任模型名、索引或远程声明。临时图片由页面/请求保留清理权，`FileManagementApplicationService` 统一物化、原目标复验和未交接补偿；补偿不能覆盖原取消或失败。参考输入到生成请求的借用由原 VM 跟踪，取消等待链结束前保留实际输入，不延迟回收无关副本。

### 文件路径与命名

逻辑附件与物理文件不是同一身份，不相互截断或推导 UUID：

| 身份 | 标识 | 用途 | 交付方式 |
|------|------|------|----------|
| 逻辑身份 | `attachment:<uuid>` | durable metadata、克隆与 UI 稳定 handle 索引 | 仅内部使用，不作为工具入参或模型披露 |
| 托管文件路径 | `/upload/<file>` | 识别、委托与 workspace 读取使用同一路径；前两者不依赖工作区 | `[Attachment path=...]`、Tool Result `file.path`、子助手 `artifacts[].path` |
| 工作产物路径 | `/workspace/...` | workspace 工具族读写 | workspace 工具结果；不属于附件识别输入 |

`/upload` 是受管文件的工具访问路径，读取由 ArtifactStore 授权。Shell 只获得本次 `uploads` 显式列出的本域授权副本，PTY 不挂载 upload；具体见 [Workspace](workspace-architecture.md)。内部数据库 ID 不进入模型可见 JSON。

`AssetFileNames` 只生成随机短名候选，扩展名经 `FileUtils.safeExtension` 校验，生图按实际格式确定。
`ArtifactStore` 在 lifecycle lock 内查重并占有 staging；`GeneratedMediaStore` 在 persist lock 内查重并占有 pending。
候选碰撞由各 owner 重试和追加尾缀处理，发布不覆盖既有文件。命名不承担资源身份或授权，也不建立第三个文件 owner。
来源保存在 `ArtifactOrigin`，原始上传名称保存在 displayName；不承诺已删除资源的名称永久不重用。

图库原件与聊天副本各自归原 owner，允许有不同短名；模型披露聊天副本的真实 `/upload` 路径，不混入图库原名。旧长路径继续使用，不建别名或按相似名称纠错。

路径查重沿用 Artifact 的 `relative_path` 唯一索引；图库使用 `GenMediaEntity.path` 普通索引。schema、索引与备份升级边界见 [数据持久化](data-persistence.md)。

## 2. 创建、提交与配置引用

`ArtifactStore` 的创建入口显式接收 `ConfigurationScope`，在 CREATING 行写入后一直保留该归属；`copyFilePreservingOrigin` 保留源文件的域与 origin。聊天输入的 `ArtifactDraftScope` 绑定原 `ConversationCommandTarget`，提交和消息编辑不能借用另一个页面的 draft，即使它们属于同一用户或会话。关闭后的补偿仍归原 lease。

模型输出转换、MCP/Workspace 图片、rolling compaction 归档沿原 Turn 传递 scope；`ImageGenerationRequest.source` 固定原页面选择或原工具任务的域与模型请求视图，`GeneratedMediaStore` 将同一归属写入图库原件及聊天副本。共享助手定义的头像/背景导入仍创建个人配置资产。目录、统计、删除、预览、导出和归档工具读取均复验原页面或执行主体；Workspace 挂载遵守其独立的显式共享边界。

### 聊天草稿与预设附件

助手预设消息进入主聊天或子助手时，`ArtifactStore.materializeConfigurationMessages` 先按已提交的共享/本主体配置根校验并保留源，再去重复制为目标 scope 的独立附件。现有 `AttachmentCloner` 使用这份复制映射重写媒体、工具交付 metadata、嵌套输出与归档 ID/marker；复制后的每个文件必须仍有消息引用。源 scope、原消息和逻辑附件 handle 不变，不新增表或目录别名。

主聊天在 Settings → Conversation → Artifact 的锁序内惰性创建预设副本。`ConversationRuntime` 持有 `ConversationDraft` 的创建令牌，多页面共用一个 Draft；首 USER 仍通过原单事务建立会话、消息和引用。事务失败保留 Draft，提交后晋升并发布令牌，发布回执失败可由后续打开重试。无人使用的 Draft 清理同步将未提交副本交还 GC。子助手沿既有 `createdArtifacts`、Child 创建、父调用链接提交与补偿链交接。

Draft 预览、读图和附件导出通过原 `ConversationViewLease` 查询其 Runtime 持有的令牌。Artifact owner 在同一 lifecycle 临界区裁决：未发布必须匹配该文件的创建令牌，发布后按普通 ACTIVE/scope 规则读取；发布与读取交错不会误判失效。关闭页面或切换原选择仍撤销访问，其他页面不能凭同域身份读取未发布副本。

输入框的 `ArtifactDraftScope.describeInputs` 按原 scope 和规范化路径投影名称与图片读取能力，
不订阅全局上传目录或按文件名反查。新图片要求创建 token，编辑已有图片要求同域已发布 ID；
读取按 Session → Draft → Artifact 锁序，返回前复验原页面、选择和草稿状态。

`ConversationTurnService.sendMessage` 在安装被拒绝或首条 Append 未提交时，经 `returnToDraft` 归还创建权，
首发失败回执等待归还完成；已关闭编辑器释放创建 pin，仍打开的草稿保留附件供重试。
Append 期间取消以 Runtime 已发布的 USER 身份判定是否提交：已提交附件不退回草稿，后续发布失败由持久引用保护。
`inputRevision` 只通知同一草稿所有权变化，不保存第二份附件事实；失败保留原异常，取消继续传播。

### Settings 引用与删除

配置文件引用直接来自已提交的 `UserSettingsDocument`：共享用户头像、所有助手定义的头像/背景/预设消息，以及全部主体保留的使用覆盖。预设消息复用消息附件引用规则，包含嵌套工具输出与结构化交付物；不会只检查当前选中企业或当前显示的助手。

`SettingsStore` 持有唯一配置写锁，`ArtifactSettingsCoordinator` 仅适配该协议，不另持锁或缓存。新增引用按 Settings writer → Artifact lifecycle → DataStore 提交回执 → creation pin 交接执行；普通 Settings 写入不能绕过新增引用校验。共享定义资产归个人域，使用覆盖可引用个人配置资产或本主体资产，不能反向把企业资产挂到个人定义。启动备份的配置恢复同样按 Settings → Artifact 执行，在锁内将旧备份中失去 metadata 或 payload 的可变配置引用回退默认后提交；不会因失效头像/背景阻断整份个人备份。仍可用的资产必须属于个人域。恢复入口不等待尚未开放的 recovery gate，普通新增引用保持严格校验。

用户删除与启动恢复同样先取得 Settings 写权限，再进入 Artifact；删除先保存 DELETING，按规范化路径找齐所有配置原始引用并持久化移除，复验后才删除文件。移除预设附件时保留其他消息内容，包含该文件引用的工具 part 整体移除，清空的预设消息一并移除，避免留下空模型消息、失效的归档句柄或交付 metadata。显式覆盖清空后仍保留覆盖，避免重新继承同一附件。配置提交失败保留 DELETING 与 payload 供重试。文件列表和图片预览的删除确认按同一规范化路径统计头像、背景及预设消息影响。GC、发布和补偿在 Artifact 锁内只读已提交配置，不等待 Settings 写锁；迟到的新引用必须重新校验 ACTIVE 与实际文件。

本地 Settings background/头像是可变显示偏好，不是 artifact owner。冷启动发现其 ACTIVE metadata 缺失时，经 `ArtifactReferencePolicy.detach` 持久化回退默认值，绝不扫描或认领遗留文件；metadata 存在而 payload 缺失时，也先持久化回退默认值，再删除该失效 metadata。两种情形均不阻断 Settings 读取；消息附件仍按其独立 durable root 规则 fail-closed。

### 工具结果与恢复

`ToolResultCheckpoint` 提交 output transformers 完成后的消息，消息、执行事实与 typed Artifact 引用进入同一 Room 事务。资源发布必须等待该 durable root 已包含本地引用；不能发布资源却持久化转换前消息。

同一次 base64 output transform 产生的多个 Artifact 只注册一个 `unpublishedBatchLease`。`ArtifactStore.publishAllUnpublished` 在交接前验证整批 durable roots 与 ownership token；失败或取消精确清理该 owner 取得的未发布资源，不留下半批发布状态。

Artifact metadata、引用和生命周期归 `ArtifactStore`；`ArtifactPayloadStore` 只处理磁盘 IO。启动时按 CREATING / ACTIVE / DELETING 状态与持久化根引用完成恢复，不能仅凭 payload 存在认领资源。图库生成媒体由 `GeneratedMediaStore` 独立恢复；全局恢复门禁在两者完成前阻止文件查询和写入。

会话写入在原 Artifact lifecycle lock 内，用 durable header 的 scope 准备引用 delta；跨域文件使提交失败，不静默删除引用。启动时 `ensureReferenceProjection` 同样核验归属，并在全量准备成功后才事务替换投影与完成标记。`Migration_11_12` 将既有行归为个人，保留文件、ID 和路径。

`GeneratedMediaStore.commit` 以同步 `receiveChatArtifact` 回调明确聊天副本的接收者，图库 row 提交后、可取消返回边界前交给原 `ToolExecutionContext.registerUnpublishedResource`。接收后由工具 checkpoint 协议发布或丢弃；接收失败只清理未交付副本，保留已提交图库。未提供接收者的页面任务只生成图库原件，不先创建无人管理的聊天副本。

消息引用提取统一使用 `collectArtifactReferences`，递归覆盖媒体、工具直接 artifact 与子助手交付 manifest。
资源提交复用原 Turn 检查点；未知副作用、终态比较并交换和主/子恢复顺序见 [Turn/Step](turn-step-execution.md)，
全局恢复顺序见 [应用架构](application-architecture.md#启动恢复)。

## 3. 授权读取、导出与清理

### 预览与文件身份

会话预览投影沿 durable header 的 scope 校验文件；子助手终态交付沿原执行 scope 校验图片和文档等媒体。Artifact 的预览入口在 lifecycle lock 内统一验证 ACTIVE、完整主体、已解除创建 pin、匹配的 MIME 和 upload/images canonical 根；图片另做有界内容校验。返回的 URL 只是投影结果，不能替代后续解码或导出的授权。创建 pin 解除后，原 owner 通过 `lifecycleChanges` 合并内存发布通知和两个目录的数据库变化，使先于交接完成的空预览重新计算；该通知不保存文件事实、不推进配置 generation。

文件目录与图库缩略图将 `ManagedFileKey.Artifact` / `Generated` 交给 `FileManagementApplicationService.imageSource`，取得携带原 RealmSelection 和稳定文件身份的 `ImageSource`。该对象只借用原 owner 的校验和有界读取能力，不管理文件生命周期或 Job；UI 不直接构造它。文件服务在原 Session 内调用文件 owner 验证归属、状态和文件边界，并在 owner 操作结束后复验原选择。Artifact lifecycle lock 与 GeneratedMedia persist lock 各自保护有界读取，解码使用返回的字节，不持有 owner 锁；共用 `FileUtils.readBoundedBytes` 按实际读入字节限制大小并传播取消。`ImageSourceInterceptor` 在 Coil 内存缓存命中前及解码结果回交前复验权限，缓存键包含原选择身份；未发布、已删除或跨域资源不能靠旧缓存恢复显示。该入口不创建新的 durable 状态或文件 owner。

会话预览由 `ConversationAttachmentPreviewProjector` 携带原 `ConversationViewLease` 解析。`ArtifactMediaPreview` 在 Store 锁内同时取得稳定 ID 与 URI；`AttachmentPreview` 中的图片读取对象由文件 application port 绑定该 ID 和原页面，后续读取不重新按同路径认领文件。同一页面重复投影使用同一缓存身份；原页面关闭后不可读，新页面即使打开同一会话也取得独立身份。会话大图、富文本图片及导出沿用该对象，聊天整体导出另复验原页面。共享配置的本地图片每次读取要求仍有已提交的配置根；此准入不会开放普通个人文件。网络或内联图片复验原来源，不按本地 Artifact 配置根查找。

`ConversationAttachmentPreviewProjector` 从持久节点和当前活动 Assistant 重建预览 map，
并为 `inspect_attachments` / `assistant_call` 顶层 `attachments` 中的合法路径补充预览。
UI 只查询该 map，不扫描 metadata；map 不是持久引用或读取授权，`file:` 资源没有有效投影时不能回退原路径。
Fork/Child clone 仅按已取得的文件复制映射重写这两个工具的入参路径，不改其他字段或正文，不建立旧路径别名。

### 导出与生成预览

非图片附件导出由 `ArtifactStore.copyMediaTo` 在同一生命周期锁内验证原 scope、ACTIVE、已发布与 canonical upload/images 根，并通过 `ArtifactPayloadStore.copyTo` 流式写入导出者的文件；不套用图片大小上限，也不返回原件读取权限。`FileManagementApplicationService` 保护原 ConversationViewLease/RealmSelection，文件读取后、最终系统交付前重新取得授权；最后接受边界不等待 Artifact IO。`MediaExportService` 只持有独立临时副本，失败/取消精确清理，已发出的 Intent 保留副本。启动在 application owner 创建新文件前分离旧 temp 目录，异步清理只处理已分离目录，不能递归删除当前会话的新导出文件。

图片生成页的参考图由 `ImgGenVM` 一次性消费打开时的 `ImageReferenceImport`；`FileManagementApplicationService.importImageReference` 在复制前后验证原选择并沿用 `TemporaryImage` 的图片校验与失败清理。缩略图借用带原选择和任务身份的 `ImageSource`，不直接读取文件路径。只有已交给生成请求的副本按实际任务借用延迟删除，其余副本立即清理。

输入参考图、生成 partial 和预览分别由原页面或请求持有。partial 的文件与读取对象从同一次创建返回，
预览借用原请求 Job，短期 collector 结束不撤销仍活跃的请求；创建校验原 RealmAccess，显示校验原 RealmSelection。
图像页切域取消并清空本页任务，取消返回前等待实际执行与清理结束，下个请求等待旧协程退出。
临时副本按实际借用关系清理，清理异常不能覆盖原失败或取消。

### 删除与目录查询

上传 Artifact 与图库生成媒体保持独立 owner，不存在共享目录扫描删除器：

- 上传文件由 `ArtifactStore` 从 durable metadata 选取候选，在同一 lifecycle lock 内重验 retention pin、消息引用和 Settings roots，并复用单项 CREATING / ACTIVE / DELETING 状态机；
- 图库媒体由 `GeneratedMediaStore` 从 canonical row 选取候选，在 persist lock 内复用单项删除协议。row 删除成功而 payload 暂未清除时返回 `cleanupPending`，保留删除 tombstone，由启动恢复继续清理；row 删除失败则恢复原 payload 身份；
- 文件目录和候选 SQL 都限定明确 scope；单项删除在 owner 锁内复验 metadata 归属，不能用本域令牌操作其他域的 ID。恢复和 GC 仍由全局 owner 遍历全部主体。
- FileManagementQueryService 将上传和生成媒体合成携带原 RealmSelection 的目录；统计以该目录的登记条目为准，生成媒体大小读取对应原件，不扫描未登记文件。查询失败有明确失败状态和同域重试入口，统计不可用显示 `—`。
- 设置文件页按选择重建确认框和预览；图库分页与会话目录复用 selectedRealmPaging，切换时撤销旧数据源，迟到消费不发布已撤权行；删除始终携带原选择，即使离开后回到同域也不能复活旧确认。
- 候选计数只用于确认提示，不锁定待删集合；真正执行时由 owner 在锁内重新取候选。取消在单项之间传播，已经取得删除所有权的单项按既有终态或补偿协议完成；
- 两个领域分别返回结构化结果。`FileManagementApplicationService` 只映射为 UI 所需的 `deleted`、`cleanupPending`、`skippedInProgress` 与 `failed`，不把部分成功压成 Boolean，也不为没有该状态的领域伪造结果；
- `FileManagementApplicationService` 的 owner 命令与 `FileManagementQueryService` 中会读取 row/payload 状态的列表、分页、统计和检查均等待全局 `ApplicationRecoveryGate`。纯 canonical-root 路径分类不读取 row 或 payload 状态，只用于本地图片来源标签。`ApplicationRecoveryCoordinator` 在发布文件读写能力前依次完成 Artifact 与 GeneratedMedia reconcile，页面不会观察或操作尚未完成恢复处理的 tombstone、staging 或孤儿 payload。

## 4. 请求级媒体投影

### 能力与文件可用性

`StepRunner` 在每次请求的上下文裁剪完成后，通过 `ArtifactStore.retainForRequest` 取得原 scope 的 `ArtifactReadLease`，覆盖输入转换、请求装配与完整 Provider 流收集。该临时读视图复用既有 retention pin，不新增持久化记录。主、子 Turn 共用此边界；成功、失败或取消后释放，既不保留窗口外历史，也不跨 Step 长期占用文件。

`DocumentAsPromptTransformer`、`ToolArtifactReplayTransformer` 和 `AttachmentProjectionTransformer` 只解析这份读视图。外域 ACTIVE 引用直接拒绝；缺失或不受管路径固定为不可用，同路径迟到出现的新文件不能被本次请求认领。缺失图片保留 unavailable 投影，工具结果保留 artifact_missing 降级；最终装配还会拒绝任何未被本次保留的本地媒体 URL。文件 URL、Provider 图片编码和 durable 消息保持不变；此边界不代表当前 Provider 已实现音视频传输。

判定输入：本次请求的 `RequestMediaCapabilities`（显式模型 IMAGE 声明 + 用户选择的 Provider 协议 + 已知容器 profile）。未知 OpenAI-compatible host 按所选通用协议处理，不否决 USER 图片能力。`OPAQUE_REPLAY_ONLY` 只允许 Responses raw `response.output` 回放历史媒体，不能让普通 `UIMessagePart.Image` 借消息级 metadata 变成 native。

### 输入形态

图片事实格式（与内部 UUID 无关）：

```text
[Attachment path=/upload/7ka2b9.png type=image input=native]
[Attachment path=/upload/7ka2b9.png type=image input=reference_only]
```

| 对象 | STRUCTURED | NONE / OPAQUE_REPLAY_ONLY 的直接媒体 |
|------|-----------|-------------|
| Image（有可用 path） | 路径事实 + 原图 | 替换为路径事实 |
| 非本地 Image（无工具 path） | `input=native` + 原图 | `input=unavailable`，不保留 Image |
| 本地 Image（本次读租约中不可解析） | `input=unavailable`，不保留 Image | 同左 |
| Document / Audio / Video | 有可用 path 时前插路径事实；仅非本地或本次读租约中可解析的 part 保留 | 同左 |
| `Tool.output` 内媒体 | 递归同上（否则模型看不到生图结果上的 ref） | 递归同上 |

- 图片事实只描述可选 `path`、`type` 与本次输入形态 `input`，不含重复文件名、MIME、内部 UUID、host path 或行为指令。
  `native` 表示 Image 同时进入请求，`reference_only` 表示只携带可读路径，`unavailable` 表示既无结构化图片输入也无可用路径。
- 本地路径由本次 `ArtifactReadLease.resolveUri` 解析，再经 `LocalArtifactRef.toolPath()` 派生，不要求该 part 带内部 ref。
  远程、非 upload 或失效资源省略 `path`；原生图片能力仍按来源容器判定，不伪造 UUID 工具入口。
- 投影文本带 request-only `AttachmentProjectionTextMetadata`，仅供协议适配器识别；不进入持久化，也不在 UI 显示。

投影只生成请求副本，不写回持久消息、不缓存跨请求结果、不创建别名；事实文本保持原消息与工具结果的来源位置。

### 来源与回放

| 图片来源 | 通用消息归属 | Provider 线协议归属 |
|----------|--------------|---------------------|
| 用户上传 | 原 USER message 的 parts | Chat/Responses 的 user content、Claude user block、Gemini user part |
| 工具产出 | 原 `UIMessagePart.Tool.output` | Chat `role=tool`、Responses `function_call_output`、Claude `tool_result`、Gemini `functionResponse` |
| 助手原生产出 | 原 ASSISTANT message 的 parts | assistant/model content；Responses 有原始 `response.output` 时先无损回放，再追加 request-only assistant 事实 |

`role` 只存在于消息层；`parts` 内没有第二层 role。投影器因此不创建伪造的 USER 消息，而是在已有来源容器内替换或前置对应 Image 的事实文本。工具结果在 Claude/Gemini 外层虽然使用 user role，但由 typed `tool_result` / `functionResponse` 在线协议中明确标为工具结果；客户端不据此保证模型的理解或遵循行为。

- `ToolArtifactReplayTransformer` 按 artifact metadata 重新物化历史工具产物路径与 Image URL。
- 历史 Assistant 的 replay-safe 投影移除未持久化图片与媒体保存失败占位；Tool.output 中以媒体失败文本表达，不伪造可读取路径。识图工具的内存 data URI 输入属于独立请求，不受此历史回放规则限制。
- `resultStatus` 表示 Tool Result 存在，合法空 output 原样保留。媒体缺失不改变执行事实，也不能触发自动重执行。
- 未成功 Assistant 的完整 Step 前缀、尾部处理及 Provider 严格回放策略见 [turn-step-execution.md](turn-step-execution.md) 和 [protocol-reference.md](protocol-reference.md)。

主、子助手共用文档、模板和附件投影；主助手额外执行 `ToolArtifactReplayTransformer`。
`generate_image` 的 Image 保存在 Tool.output，后续 Step 按本次能力回放。完整输入和输出顺序由
[模型输入格式](prompts-and-tools.md)维护，本投影不创建状态披露或会话摘要。

## 5. 按需附件识别

### 装配与执行

`ModelExecutionService` 从原域 `ResolvedConfiguration.modelSelection(ATTACHMENT_INSPECTION)` 解析可执行的 Chat 视觉模型，与主模型在同次用户配置和企业 binding 捕获中冻结。未配置、引用失效或策略不允许时不装配识图工具；不回退到其他模型。`TurnToolSetFactory` 只接收已捕获的模型请求视图，不再次读取 Settings。

不依赖当前会话是否含图、当前模型的图片容器覆盖或工作区状态。模型具备原生视觉能力，不代表上下文已经携带文件清单中的图片。
子助手默认清单只有 `artifacts[].path/type/mime`，没有 Image 或附件事实行；后续仍可直接按路径调用识图。

模型图片能力唯一来源于 `Model.inputModalities`；`RequestMediaCapabilities` 负责协议容器映射，不负责禁止按需读取。
不按 endpoint host 再次否决 IMAGE 能力；真实远端不兼容由 Provider 分类失败表达。

工具在新 `START` 冻结；后续 step、用户交互继续与重试复用同一工具定义和执行索引。执行期凭据、权限和资源重验见 [turn-step-execution.md](turn-step-execution.md)。

### 文件解析与识别请求

`inspect_attachments` 使用 `ToolExecutionContext.resolveAttachments` 提供的只读文件能力：

```text
/upload/<file> 或 HTTP(S) 附件 URL（1..4 个）
→ ToolExecutionContext.resolveAttachments(paths)
→ AttachmentResolver.readImages(scope, paths)
→ 本地：ArtifactStore.withUploadImages（授权与保留文件，有界读取和图片校验）
→ 远程：SafeRemoteMediaFetcher（Basic、逐跳地址检查、有界内存读取）
→ 共用 FileEncoder 规范化内存快照 → data URI Image parts，无临时文件
→ 识图模型（[Image N path=...] + 图片 + request；独立固定 system instruction）
→ Text Tool Result
```

- `AttachmentInspectionTool` 通过捕获的 `ModelRequests` 发起独立识图请求；借用视图只提供执行能力，原 Runtime 的 `ModelExecutionLease` 唯一持有并释放共享企业 binding，关闭后全部角色立即不可再准入。工具不持有另一份凭据 owner。
  各请求复验原助手、原模型及 Child caller/target 授权，保持原 endpoint/protocol/model shape。CHAT 使用原助手的有效模型选择，识图使用本域识图选择；两者分别冻结，不受后续用户选择变动影响。用户凭据从原 owner 刷新，企业私有 header/凭据走相同受管请求边界。
  `RequestMediaCapabilities` 在捕获时冻结，IMAGE 模型必须提供结构化 USER 图片编码；远端不兼容由真实 Provider 分类错误表达。企业执行沿原平台 binding 和准入协议，测试中的模拟响应不代表产品中的独立执行来源。
- paths 与产出 1:1、顺序稳定，重复路径保留对应图片位置；本地标签使用原请求路径，远程只传附件序号，不向识图 Provider 转交资源 URL 或凭据。委托仍只接受 /upload 路径；识图额外接受 HTTP(S)。两者均不接受 UUID、file URI、workspace 或越界路径。
- `ArtifactStore` 在同一 lifecycle lock 内校验原操作 scope、ACTIVE/已发布并取得既有 retention pin，锁外读取；成功、失败和取消都在 finally 释放。
  识图内存快照复用 FileEncoder 的压缩、EXIF 方向和格式转换，不以 raw data URI 绕过现有图片编码；网络调用不持有磁盘文件，也不创建副本。
- 未注入 resolver 的执行环境统一返回 `attachment_resolution_unavailable`，不静默成功。
- 远程图片每个最多 20 MiB，声明长度和实际字节数都受限，每次 HTTP 请求与响应体读取共享超时；最多 4 个顺序处理。允许用户 LAN 发布资源，仍拒绝 loopback/link-local/metadata 等地址。跨源跳转移除 Basic，拒绝 HTTPS 降级。
  `Call.readResponse` 持有请求直到响应体消费结束，取消调用真实 Call.cancel 并关闭响应；没有识别临时文件。图像解码前限制尺寸和像素，取消检查位于编码各阶段，单次原生解码/压缩需结束后才能观察取消。
- 识别无缓存；结果作为显式 Tool Result 已是正确的历史记录。
- 失败 reason 原样透传，代码含义见 [提示词与工具](prompts-and-tools.md)。编码拒绝无效图片时返回 `unsupported_attachment_type`，其他编码异常返回 `attachment_read_failed`；保留简短类型与原因及完整异常日志，取消继续传播。

### 配置兼容

- `ResourceSelections.attachmentInspectionModelId` 保存本域的类型化配置引用；设置页只允许选择当前域可用且声明 IMAGE 输入的 Chat 模型。用户定义保持一份，企业策略只限制本域使用。
- 旧 `ocr_model` / `ocr_prompt` 只存在于一次性迁移边界（`SettingsOcrMigration`）：新 key 优先；有效旧视觉模型（Provider 存在且声明 IMAGE 输入）映射到新字段；旧 Prompt 丢弃；旧 key 清除；旧 observation cache best-effort 清理。备份恢复（S3 / WebDav）在导入 settings.json 前同样应用 `migrateLegacySettingsJson()` 旧键映射，见 [Android 配置架构](android-configuration-architecture.md)。

## 6. 上下文正文与工具输出持久化

### 上下文正文

`ConversationContextBody.Artifact` 以 Artifact ID 与相对路径共同定位已渲染的不可变正文。`ArtifactStore.readContextText` 在生命周期锁内校验原 scope、ACTIVE、发布状态、ID/path、文本类型及真实文件；旧路径复用不能替换历史正文。读取返回原文本，不再次执行模板。接纳前创建仍返回 `OwnedArtifact`，Conversation 提交 durable root 后才由原资源 lease 发布，失败或取消精确回收尚未交接的正文。

上下文正文使用原 `artifact_reference` 的 `CONTEXT` 类型，引用归 entry 的 owner node；无需另一张引用表。`prepareReferenceDelta` 分开替换消息附件引用和上下文引用：普通消息更新保留上下文，context-only 提交也取得同一个生命周期锁并在 Conversation 事务更新引用。Fork 各自建立节点引用，删除一个 owner 不释放其他消费者使用的正文；最后引用消失后由原 GC 回收。启动投影版本 `artifact_reference_projection_context_v4` 从消息树与上下文条目共同重建。备份先在完整来源库验证上下文 Artifact scope，再按会话域过滤、重建引用；个人恢复保留本机企业正文及引用。显式删除的文件保持历史不可用，不因路径相同重新绑定另一个 Artifact。

### 工具输出归档与折叠

归档与折叠的触发阈值、保护窗口、净回收要求和成功消费 receipt 统一见 [request-context.md](request-context.md)。本领域只执行资源 staging 与提交交接：

- `ARCHIVABLE_TEXT` 由 `ToolOutputStore` 创建未发布的 `tool_outputs` Artifact，并生成 marker 与 `runtimeState.archive`；`REGENERABLE_TEXT` 只写固定 folded marker，`PRESERVE` 保留原文。
- `ModelResponseCheckpoint` 将历史 `toolOutputCompactionPatches`、当前 Assistant 和 typed `artifact_reference` 同事务提交；无工具 Final Step 的同类 patch 随 `FinalizeTurn` 提交。Reducer 使用已提交 durable 原文校验 patch，不从 streaming projection 获取历史事实。
- 提交成功后 publish lease；失败保留原 inline output 并精确 discard 暂存 Artifact。归档不建立新表或第二 checkpoint。

`ArtifactStore.withToolOutputText` 在 lifecycle lock 内同时验证 ACTIVE、`tool_outputs` folder、`text/plain`、当前 conversation
的 `TOOL_OUTPUT` reference 并取得 retention pin，锁外流式读取，finally 释放。ref 猜测、其他会话、payload 缺失都统一
fail-closed。fork 的节点引用同一 Artifact；删除任一会话只移除自己的 reference，最后引用消失后才允许 GC。backup/restore
包含 `tool_outputs` durable directory，启动恢复按 Artifact 既有 CREATING/DELETING 协议提交终态。

## 7. 验证边界

附件投影以 `AttachmentProjectionTransformerTest` 覆盖本地可用性、来源容器、递归工具结果和非破坏转换；
`AttachmentResolverTest` 覆盖精确路径、授权、内容校验和取消。Store、草稿、Settings 引用与文件服务的测试
还需观察提交前后所有权、发布失败、删除重试和原能力撤销，不能仅以文件存在证明事务完成。
Android 解码、相册发布、系统分享及 Compose 预览需要对应设备场景，分层见 [测试策略](testing-strategy.md)。
