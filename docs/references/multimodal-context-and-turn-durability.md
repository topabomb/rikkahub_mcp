# 多模态上下文与资源持久化

本文描述附件身份、请求级媒体投影、Artifact 与工具输出资源的持久化交接。Turn/Step、审批、取消与恢复的通用协议见 [turn-step-execution.md](turn-step-execution.md)；请求预算与披露见 [request-context.md](request-context.md)；子助手入站和交付物见 [sub-assistant-multimodal.md](sub-assistant-multimodal.md)；模型可见参数与失败 reason 见 [prompts-and-tools.md](prompts-and-tools.md)。

## 1. 行为总览

```text
用户上传 / 工具产出媒体
    │  AttachmentRefs.ensureAttachmentRef() 盖章（持久事实，一次性）
    ▼
durable Conversation
    Image part（url + metadata.attachment_ref）+ Artifact
    │  每次生成请求
    ▼
AttachmentProjectionTransformer（按本次 RequestMediaCapabilities）
    ├── STRUCTURED → input=native 引用事实 + 原图
    └── NONE / OPAQUE_REPLAY_ONLY 的普通 Image part → input=reference_only 引用事实
           │  模型需要细节时显式调用
           ▼
    inspect_attachments(attachments, request)
        → ToolExecutionContext.resolveAttachments → 识别模型（单次多图调用）→ Text
    ▼
Tool Result checkpoint（消息与 Artifact 引用同事务）
    ▼
提交成功后发布资源；失败或取消精确回滚未发布资源
```

关键性质：投影是**请求级、无状态、非破坏**——durable Conversation 永远保存原图与 ref，Model View 每次按当次模型重算；模型切换（视觉 ↔ 文本）不需要迁移消息，下一次请求自然回放对应形态。

## 2. 附件事实

### 2.1 stable attachment ref

- 格式：`attachment:<uuid>`（`AttachmentRefs` 前缀常量）。
- 存储：多媒体 part 的 `metadata` 中的 `attachment_ref` 键，merge 语义（保留其他 metadata 键）。
- 子助手交付物：Master 的 `assistant_call` metadata 以 `SubAssistantCallArtifact(ref, artifact)` 保存同一种稳定
  handle；不复制图片 part 来伪造第二份引用事实。
- 唯一性：一个 ref 指向一个逻辑附件；不同 part 可指向同一文件（保持各自 ref）。
- 幂等：`ensureAttachmentRef` 对已带**合法可解析** ref 的 part 恒等返回；仅对非多媒体 part 恒等。导入 / 旧数据 / 异常 Provider metadata 中的非法 ref 会被重建为合法 UUID，保持内部身份可解析。
- `AttachmentReferenceLookup` 只索引多媒体 part 的合法 ref；同一 ref 同时出现在 `Tool.output` 直接媒体与
  `sub_assistant_call.artifacts[]` manifest 是正常双表示，直接媒体优先、manifest 作为回退。等价资源的重复声明复用同一逻辑附件；多个不同 direct 资源或仅 manifest 间的不一致声明 fail-closed，不按遍历顺序选取。

### 2.2 盖章位置

媒体进入持久消息的入口都调用 `ensureAttachmentRef`：

| 入口 | 说明 |
|------|------|
| 用户上传 / 编辑消息 | `ConversationApplicationService` / `ConversationTurnService` 提交前 |
| `generate_image` 产出 | 工具成功时对 Image part 盖章 |
| MCP 图片内容 | `McpToolCallExecutor` 转本地文件时 |
| base64 图片 | `Base64ImageToLocalFileTransformer` 经 `ArtifactStore` 终态落盘并盖章 |
| `assistant_call` 注入 Child | 为新 Image part 盖章；原文件身份不变，不复制 payload |
| 历史消息补章 | 仅在 `ConversationTurnService` 的 START structural preflight 由 `planDurableAttachmentRefBackfills` / `BackfillAttachmentRefs` 执行；会话加载与恢复只做校验，不由 UI/query 旁路补章 |

所有字节型图片入口都在创建 durable artifact 前限制输入规模并校验实际内容：

- MCP `ImageContent` 先限制 base64 字符与解码字节，再按文件头与容器结构识别 MIME；声明 MIME 不能覆盖实际格式。
- `GeneratedMediaStore` 对 URL/base64 结果使用同一尺寸上限与结构检查，并以检测 MIME 决定扩展名；WebP 校验遍历 RIFF chunk、padding 与 VP8X 后续图像/动画 payload，不把扩展头误当完整图像。Gallery 只通过 `resolveCanonicalFile` 解析根目录内路径。
- 编辑器导入返回 `ArtifactDraftItem(uri, displayName, mimeType)`；路由、分享、粘贴和裁剪调用方直接使用托管时已经确定的 metadata，不对托管 `file://` URI 再走外部 ContentResolver 分类。裁剪输出扩展名与 PNG 压缩格式一致。
- 配置编辑器的头像与背景经 `ArtifactUseCase.importSettingsImage` 执行有界复制与结构检查；查看器/生成工具的背景由 `AssistantBackgroundService` 接受原页面、任务或共享定义目标，分别写个人定义或企业主体偏好。两条入口均复用 Artifact 的 Settings root 提交及 pin 移交，失败或取消回滚未发布 artifact，不能直接挂接原始文件路径。
- 本地 Settings background/头像是可变显示偏好，不是 artifact owner。冷启动发现其 ACTIVE metadata 缺失时，经 `ArtifactReferencePolicy.detach` 持久化回退默认值，绝不扫描或认领遗留文件；metadata 存在而 payload 缺失时，也先持久化回退默认值，再删除该失效 metadata。两种情形均不阻断 Settings 读取；消息附件仍按其独立 durable root 规则 fail-closed。
- 生成中预览、参考图片与助手背景只接受经结构检查的图片，文件名与扩展名由实际内容生成，不信任模型名、索引或远程声明。临时图片由页面/请求保留清理权，`FileManagementApplicationService` 统一物化、原目标复验和未交接补偿；补偿不能覆盖原取消或失败。参考输入到生成请求的借用由原 VM 跟踪，取消等待链结束前保留实际输入，不延迟回收无关副本。

### 2.3 内部身份与模型引用

逻辑附件与物理文件不是同一身份，不相互截断或推导 UUID：

| 身份 | 标识 | 用途 | 交付方式 |
|------|------|------|----------|
| 逻辑身份 | `attachment:<uuid>` | durable metadata、克隆与 UI 稳定 handle 索引 | 仅内部使用，不作为工具入参或模型披露 |
| 托管文件路径 | `/upload/<file>` | 识别、委托与 workspace 读取使用同一路径；前两者不依赖工作区 | `[Attachment path=...]`、Tool Result `file.path`、子助手 `artifacts[].path` |
| 工作产物路径 | `/workspace/...` | workspace 工具族读写 | workspace 工具结果；不属于附件识别输入 |

`/upload` 挂载会话共享的只读文件（用户上传与生成媒体）。内部数据库 id 不进入模型可见 JSON。

新托管文件由 `AssetFileNames.candidates()` 生成四个随机候选主体，顺序固定为 base36 的 6、7、8 位及 base62 的 8 位。字符集分别为 `0-9a-z` 与 `0-9a-zA-Z`；每档使用 `ThreadLocalRandom.current().nextLong(bound)` 整体取样后定宽编码，不查重、不做 IO。候选、最终落盘文件名和模型引用均无来源前缀，来源继续由既有 `ArtifactOrigin` metadata 表达。扩展名经 `FileUtils.safeExtension` 校验，生图按实际图片格式确定。

候选是否可用由既有 owner 裁决：按优先级逐一查重并跳过重复文本，首个可用项立即占位；四个候选均被占用时，为第一个主体追加 `-2`、`-3` 等尾缀并继续查重。`ArtifactStore` 在 lifecycle lock 内检查任意状态 metadata、最终文件与 staging，并由 `ArtifactPayloadStore` 原子创建 staging 占位；`GeneratedMediaStore` 在 persist lock 内检查 canonical row、final、pending、deleting 并占有 pending。仅命名规则共享，不引入第三个文件 owner。发布仍走同文件系统 rename，不覆盖现有文件，失败只清理本次取得所有权的资源。

命名不持久化随机种子或序号，不新增表、字段、设置键或名称登记表，不依赖系统时间；字节写入在 Artifact 生命周期锁外进行。保证当前受管路径不被覆盖，不承诺已删除资源的历史名称永久不重用。

图库原件与聊天副本各自归原 owner，允许有不同短名；模型只披露聊天副本的真实 `/upload` 路径，不再重复披露 `name` 或混入图库原名。用户可见的原始上传名称仍保存在现有 displayName。已有文件、UUID、metadata 字段与内容均不改；旧长路径按同一规则使用，不建别名、不按相似名称或忽略大小写纠错。

路径查重沿用 Artifact 的 `relative_path` 唯一索引；图库使用 `GenMediaEntity.path` 普通索引。schema、索引与备份升级边界见 [database-indexing.md](database-indexing.md)。

### 2.4 文件可用性

`ArtifactStore` 的创建入口显式接收 `ConfigurationScope`，在 CREATING 行写入后一直保留该归属；`copyFilePreservingOrigin` 保留源文件的域与 origin。聊天输入的 `ArtifactDraftScope` 绑定原 `ConversationCommandTarget`，提交和消息编辑不能借用另一个页面的 draft，即使它们属于同一用户或会话。关闭后的补偿仍归原 lease。

模型输出转换、MCP/Workspace 图片、rolling compaction 归档沿原 Turn 传递 scope；`ImageGenerationRequest.source` 固定原页面选择或原工具任务的域与模型请求视图，`GeneratedMediaStore` 将同一归属写入图库原件及聊天副本。共享助手定义的头像/背景导入仍创建个人配置资产。目录、统计和删除已按原 RealmSelection 授权；预览、导出、归档工具读取与 Workspace 挂载的完整域授权仍在企业集成计划中。

`StepRunner` 在每次请求的上下文裁剪完成后，通过 `ArtifactStore.retainForRequest` 取得原 scope 的 `ArtifactReadLease`，覆盖输入转换、请求装配与完整 Provider 流收集。该临时读视图复用既有 retention pin，不新增持久化记录。主、子 Turn 共用此边界；成功、失败或取消后释放，既不保留窗口外历史，也不跨 Step 长期占用文件。

`DocumentAsPromptTransformer`、`ToolArtifactReplayTransformer` 和 `AttachmentProjectionTransformer` 只解析这份读视图。外域 ACTIVE 引用直接拒绝；缺失或不受管路径固定为不可用，同路径迟到出现的新文件不能被本次请求认领。缺失图片保留 unavailable 投影，工具结果保留 artifact_missing 降级；最终装配还会拒绝任何未被本次保留的本地媒体 URL。文件 URL、Provider 图片编码和 durable 消息保持不变；此边界不代表当前 Provider 已实现音视频传输。

消息引用提取统一使用 `collectArtifactReferences`，递归覆盖媒体 part、工具直接 artifact，以及 `sub_assistant_call.artifacts[].artifact`；模型请求不读取 archive marker 的原文，其归档 payload 仍由独立工具读取边界保护。


会话预览投影沿 durable header 的 scope 校验文件；子助手终态交付沿原执行 scope 校验图片和文档等媒体。Artifact 的预览入口在 lifecycle lock 内统一验证 ACTIVE、完整主体、已解除创建 pin、匹配的 MIME 和 upload/images canonical 根；图片另做有界内容校验。返回的 URL 只是投影结果，不能替代后续解码或导出的授权。创建 pin 解除后，原 owner 通过 `lifecycleChanges` 合并内存发布通知和两个目录的数据库变化，使先于交接完成的空预览重新计算；该通知不保存文件事实、不推进配置 generation。

会话写入在原 Artifact lifecycle lock 内，用 durable header 的 scope 准备引用 delta；跨域文件使提交失败，不静默删除引用。启动时 `ensureReferenceProjection` 同样核验归属，并在全量准备成功后才事务替换投影与完成标记。v19 迁移将既有行归为个人，保留文件、ID 和路径。

- 文件被清理后，历史消息仍保留 Image part 与 ref；请求投影不为失效本地资源披露可用路径。历史文本中的旧引用若被再次调用，Resolver 按真实可用性失败，不伪造内容。
- 模型读到 `[Attachment path=...]` 表示投影时存在可用受管路径，不保证后续调用时文件仍然存在；工具执行必须重新校验。
- `AttachmentReferenceLookup` 只负责 message part 与 `assistant_call` 交付物 metadata 的内部 handle 索引。
  `ConversationAttachmentPreviewProjector` 从 durable nodes 与 owning active assistant message 重建 UI map，
  并为已知工具 `inspect_attachments` / `assistant_call` 顶层 `attachments` 中的合法路径补充预览。
  所有本地 URL 均经 `ArtifactStore` 生命周期与内容校验，文件生命周期变化使投影失效。
  UI 只做 O(1) map lookup，不扫描 metadata；该 map 既不是持久化别名，也不是文件读取授权或 durable root。
- `ChatMessage` 与会话相册消费查询投影的读取对象；非图片附件同时保存原页面与 Artifact ID。
  `file:` 图片/媒体没有有效投影时不回退原路径。聊天 Markdown 导出只使用投影中的 URL 表示；助手配置页的合成消息预览同样不读取本地附件。
- Resolver 只接受安全 `/upload` 路径，由 `ArtifactStore.withUploadImages` 校验 ACTIVE、已发布、文件存在、受管根目录、大小与实际图片内容；仅 payload 存在不能认领孤儿文件。路径读取不要求当前会话引用，不依赖 Workspace。
- Fork/Child clone 只按本次已经取得的文件复制映射，重绑定 `inspect_attachments` 与 `assistant_call` 的
  `attachments` 输入数组中的 `/upload` 路径，使工具卡查询和后续引用指向新会话副本。UUID、其他字段与正文不改，
  未复制的路径不触发额外文件复制，查询层不增加源路径别名；详见子助手多模态参考的文件寿命与 fork 协议。

配置文件引用直接来自已提交的 `UserSettingsDocument`：共享用户头像、所有助手定义的头像/背景/预设消息，以及全部主体保留的使用覆盖。预设消息复用消息附件引用规则，包含嵌套工具输出与结构化交付物；不会只检查当前选中企业或当前显示的助手。

`SettingsStore` 持有唯一配置写锁，`ArtifactSettingsCoordinator` 仅适配该协议，不另持锁或缓存。新增引用按 Settings writer → Artifact lifecycle → DataStore 提交回执 → creation pin 交接执行；普通 Settings 写入不能绕过新增引用校验。共享定义资产归个人域，使用覆盖可引用个人配置资产或本主体资产，不能反向把企业资产挂到个人定义。启动备份的配置恢复同样按 Settings → Artifact 执行，在锁内将旧备份中失去 metadata 或 payload 的可变配置引用回退默认后提交；不会因失效头像/背景阻断整份个人备份。仍可用的资产必须属于个人域。恢复入口不等待尚未开放的 recovery gate，普通新增引用保持严格校验。

用户删除与启动恢复同样先取得 Settings 写权限，再进入 Artifact；删除先保存 DELETING，按规范化路径找齐所有配置原始引用并持久化移除，复验后才删除文件。移除预设附件时保留其他消息内容，包含该文件引用的工具 part 整体移除，清空的预设消息一并移除，避免留下空模型消息、失效的归档句柄或交付 metadata。显式覆盖清空后仍保留覆盖，避免重新继承同一附件。配置提交失败保留 DELETING 与 payload 供重试。文件列表和图片预览的删除确认按同一规范化路径统计头像、背景及预设消息影响。GC、发布和补偿在 Artifact 锁内只读已提交配置，不等待 Settings 写锁；迟到的新引用必须重新校验 ACTIVE 与实际文件。

### 2.5 范围清理与恢复

文件目录与图库缩略图将 `ManagedFileKey.Artifact` / `Generated` 交给 `FileManagementApplicationService.imageSource`，取得携带原 RealmSelection 和稳定文件身份的 `ImageSource`。该对象只借用原 owner 的校验和有界读取能力，不管理文件生命周期或 Job；UI 不直接构造它。文件服务在原 Session 内调用文件 owner 验证归属、状态和文件边界，并在 owner 操作结束后复验原选择。Artifact lifecycle lock 与 GeneratedMedia persist lock 各自保护有界读取，解码使用返回的字节，不持有 owner 锁；共用 `FileUtils.readBoundedBytes` 按实际读入字节限制大小并传播取消。`ImageSourceInterceptor` 在 Coil 内存缓存命中前及解码结果回交前复验权限，缓存键包含原选择身份；未发布、已删除或跨域资源不能靠旧缓存恢复显示。该入口不创建新的 durable 状态或文件 owner。

会话预览由 `ConversationAttachmentPreviewProjector` 携带原 `ConversationViewLease` 解析。`ArtifactMediaPreview` 在 Store 锁内同时取得稳定 ID 与 URI；`AttachmentPreview` 中的图片读取对象由文件 application port 绑定该 ID 和原页面，后续读取不重新按同路径认领文件。同一页面重复投影使用同一缓存身份；原页面关闭后不可读，新页面即使打开同一会话也取得独立身份。会话大图、富文本图片及导出沿用该对象，聊天整体导出另复验原页面。共享配置图片每次读取要求仍有已提交的配置根；此准入不会开放普通个人文件。背景写入目标及其他文件出口的剩余工作见实施方案。

非图片附件导出由 `ArtifactStore.copyMediaTo` 在同一生命周期锁内验证原 scope、ACTIVE、已发布与 canonical upload/images 根，并通过 `ArtifactPayloadStore.copyTo` 流式写入导出者的文件；不套用图片大小上限，也不返回原件读取权限。`FileManagementApplicationService` 保护原 ConversationViewLease/RealmSelection，文件读取后、最终系统交付前重新取得授权；最后接受边界不等待 Artifact IO。`MediaExportService` 只持有独立临时副本，失败/取消精确清理，已发出的 Intent 保留副本。启动在 application owner 创建新文件前分离旧 temp 目录，异步清理只处理已分离目录，不能递归删除当前会话的新导出文件。

上传 Artifact 与图库生成媒体保持独立 owner，不存在共享目录扫描删除器：

- 上传文件由 `ArtifactStore` 从 durable metadata 选取候选，在同一 lifecycle lock 内重验 retention pin、消息引用和 Settings roots，并复用单项 CREATING / ACTIVE / DELETING 状态机；
- 图库媒体由 `GeneratedMediaStore` 从 canonical row 选取候选，在 persist lock 内复用单项删除协议。row 删除成功而 payload 暂未清除时返回 `cleanupPending`，保留删除 tombstone，由启动 reconcile 继续收口；row 删除失败则恢复原 payload 身份；
- 文件目录和候选 SQL 都限定明确 scope；单项删除在 owner 锁内复验 metadata 归属，不能用本域令牌操作其他域的 ID。恢复和 GC 仍由全局 owner 遍历全部主体。
- FileManagementQueryService 将上传和生成媒体合成携带原 RealmSelection 的目录；统计以该目录的登记条目为准，生成媒体大小读取对应原件，不扫描未登记文件。查询失败有明确失败状态和同域重试入口，统计不可用显示 `—`。
- 设置文件页按选择重建确认框和预览；图库分页与会话目录复用 selectedRealmPaging，切换时撤销旧数据源，迟到消费不发布已撤权行；删除始终携带原选择，即使离开后回到同域也不能复活旧确认。
- 候选计数只用于确认提示，不锁定待删集合；真正执行时由 owner 在锁内重新取候选。取消在单项之间传播，已经取得删除所有权的单项按既有终态或补偿协议完成；
- 两个领域分别返回结构化结果。`FileManagementApplicationService` 只映射为 UI 所需的 `deleted`、`cleanupPending`、`skippedInProgress` 与 `failed`，不把部分成功压成 Boolean，也不为没有该状态的领域伪造结果；
- `FileManagementApplicationService` 的 owner 命令与 `FileManagementQueryService` 中会读取 row/payload 状态的列表、分页、统计和检查均等待全局 `ApplicationRecoveryGate`。纯 canonical-root 路径分类不读取 row 或 payload 状态，只用于本地图片来源标签。`ApplicationRecoveryCoordinator` 在发布文件读写能力前依次完成 Artifact 与 GeneratedMedia reconcile，页面不会观察或操作尚未收口的 tombstone、staging 或孤儿 payload。

输入框由原 `ArtifactDraftScope.describeInputs` 同时投影附件名称和图片 `ImageSource`；按规范化路径和 scope 匹配，不订阅全局上传目录，也不按 basename 反查其他文件。草稿新导入的图片必须同时通过原 draft 所有权和 Artifact 创建 token 校验；编辑已有图片则验证同域已发布 ID，不能读取其他创建者尚未发布的文件。读取遵守 Session → Draft → Artifact 锁序，返回前复验原页面、选择和草稿状态。提交认领后原草稿不能读取已交出的图片；拒绝退回由同一 owner 恢复原创建权。`inputRevision` 仅通知同一草稿的所有权变更，驱动查询与输入缩略图重试，不保存第二份附件事实，也不推进企业配置 generation。名称读取失败可回退显示，取消继续传播。生成 partial 的文件与图片对象由同一次创建返回；预览借用 enqueue 外层请求 Job，在短期 collector 结束后仍可读取，创建检查原 RealmAccess，显示检查原 RealmSelection。图像页切域仍显式取消并清空本页请求。图像页取消沿 enqueue 的原请求收口，Coordinator 的取消返回前等待真实执行结束；下个页面请求等待旧协程完成，旧图片投影不能跨选择继续展示。

## 3. 请求级投影（`AttachmentProjectionTransformer`）

### 3.1 两态

判定输入：本次请求的 `RequestMediaCapabilities`（显式模型 IMAGE 声明 + 用户选择的 Provider 协议 + 已知容器 profile）。未知 OpenAI-compatible host 按所选通用协议处理，不否决 USER 图片能力。`OPAQUE_REPLAY_ONLY` 只允许 Responses raw `response.output` 回放历史媒体，不能让普通 `UIMessagePart.Image` 借消息级 metadata 变成 native。

| 模式 | 行为 |
|------|------|
| STRUCTURED | Image part 保留，前方插入 `input=native` 引用事实 |
| NONE 或 OPAQUE_REPLAY_ONLY 的直接 Image | Image 替换为 `input=reference_only`（无可用文件路径时 `unavailable`） |

### 3.2 投影规则

图片事实格式（与内部 UUID 无关）：

```text
[Attachment path=/upload/7ka2b9.png type=image input=native]
[Attachment path=/upload/7ka2b9.png type=image input=reference_only]
```

| 对象 | 可读图模式 | 不可读图模式 |
|------|-----------|-------------|
| Image（有可用 path） | 路径事实 + 原图 | 替换为路径事实 |
| Image（无可用 path） | `[Attachment type=image input=native]` + 原图 | `[Attachment type=image input=unavailable]` |
| Document / Audio / Video | 有可用 path 时前插路径事实；原 part 始终保留 | 同左 |
| `Tool.output` 内媒体 | 递归同上（否则模型看不到生图结果上的 ref） | 递归同上 |

- 图片事实只描述可选 `path`、`type` 与本次输入形态 `input`，不含重复文件名、MIME、内部 UUID、host path 或行为指令。
  `native` 表示 Image 同时进入请求，`reference_only` 表示只携带可读路径，`unavailable` 表示既无结构化图片输入也无可用路径。
- 本地路径由 `ArtifactStore.resolveManagedReference` 校验后经 `LocalArtifactRef.toolPath()` 派生，不要求该 part 带内部 ref。
  远程、非 upload 或失效资源省略 `path`；原生图片能力仍按来源容器判定，不伪造 UUID 工具入口。
- 投影文本带 request-only `AttachmentProjectionTextMetadata`，仅供协议适配器识别；不进入持久化，也不在 UI 显示。

### 3.3 不变量

| 不变量 | 含义 |
|--------|------|
| 非破坏 | 投影只产生请求副本，durable Conversation 原对象不变（含 `Tool.output`） |
| 无状态 | 无跨请求缓存；同一消息按不同模型投影出不同请求，互不影响 |
| 来源稳定 | 事实文本只留在原 `UIMessage` 或原 `Tool.output`，不追加到别的消息，不改变 role |
| 事实先行 | UUID 与文件路径在既有持久化协议中锚定；引用行只投影已有事实，不创建短别名 |

### 3.4 三种来源与协议归属

| 图片来源 | 通用消息归属 | Provider 线协议归属 |
|----------|--------------|---------------------|
| 用户上传 | 原 USER message 的 parts | Chat/Responses 的 user content、Claude user block、Gemini user part |
| 工具产出 | 原 `UIMessagePart.Tool.output` | Chat `role=tool`、Responses `function_call_output`、Claude `tool_result`、Gemini `functionResponse` |
| 助手原生产出 | 原 ASSISTANT message 的 parts | assistant/model content；Responses 有原始 `response.output` 时先无损回放，再追加 request-only assistant 事实 |

`role` 只存在于消息层；`parts` 内没有第二层 role。投影器因此不创建伪造的 USER 消息，而是在已有来源容器内替换或前置对应 Image 的事实文本。工具结果在 Claude/Gemini 外层虽然使用 user role，但由 typed `tool_result` / `functionResponse` 明确标识，模型不会把它当普通用户输入。

## 4. `inspect_attachments`（按需识别）

### 4.1 注入条件

`ModelExecutionService` 从原域 `ResolvedConfiguration.modelSelection(ATTACHMENT_INSPECTION)` 解析可执行的 Chat 视觉模型，与主模型在同次用户配置和企业 binding 捕获中冻结。未配置、引用失效或策略不允许时不装配识图工具；不回退到其他模型。`TurnToolSetFactory` 只接收已捕获的模型请求视图，不再次读取 Settings。

不依赖当前会话是否含图、当前模型的图片容器覆盖或工作区状态。模型具备原生视觉能力，不代表上下文已经携带文件清单中的图片。
子助手默认清单只有 `artifacts[].path/type/mime`，没有 Image 或附件事实行；后续仍可直接按路径调用识图。

模型图片能力唯一来源于 `Model.inputModalities`；`RequestMediaCapabilities` 负责协议容器映射，不负责禁止按需读取。
不按 endpoint host 再次否决 IMAGE 能力；真实远端不兼容由 Provider 分类失败表达。

工具在新 `START` 冻结；后续 step、用户交互继续与重试复用同一工具定义和执行索引。执行期凭据、权限和资源重验见 [turn-step-execution.md](turn-step-execution.md)。

### 4.2 附件解析接口（`ToolExecutionContext.resolveAttachments`）

工具获得的不是会话状态，而是最小只读资源访问能力：

```text
/upload/<file>（1..4 个，精确文件路径）
→ ToolExecutionContext.resolveAttachments(paths)
→ AttachmentResolver.readImages(scope, paths)
→ ArtifactStore.withUploadImages（授权与保留文件，有界读取和图片校验）
→ 共用 FileEncoder 规范化内存快照 → data URI Image parts，无临时文件
→ 识图模型（[Image N path=...] + 图片 + request；独立固定 system instruction）
→ Text Tool Result
```

- `AttachmentInspectionTool` 通过捕获的 `ModelRequests` 发起独立识图请求；借用视图只提供执行能力，原 Runtime 的 `ModelExecutionLease` 唯一持有并释放共享企业 binding，关闭后全部角色立即不可再准入。工具不持有另一份凭据 owner。
  各请求复验原助手、原模型及 Child caller/target 授权，保持原 endpoint/protocol/model shape。企业助手固定聊天绑定只约束 CHAT；识图使用本域识图选择。用户凭据从原 owner 刷新，企业私有 header/凭据走相同受管请求边界。
  `RequestMediaCapabilities` 在捕获时冻结，IMAGE 模型必须提供结构化 USER 图片编码；远端不兼容由真实 Provider 分类错误表达。企业本地示例接收同样的图片请求并明确返回模拟结果，不调用网络或声称真实识图。
- paths 与产出 1:1、顺序稳定，重复路径保留对应图片位置；内部标签使用原请求路径。识图与委托入口均不接受 UUID、HTTP(S)、file URI、workspace 或越界路径，不提供旧参数兼容入口。
- `ArtifactStore` 在同一 lifecycle lock 内校验原操作 scope、ACTIVE/已发布并取得既有 retention pin，锁外读取；成功、失败和取消都在 finally 释放。
  识图内存快照复用 FileEncoder 的压缩、EXIF 方向和格式转换，不以 raw data URI 绕过现有图片编码；网络调用不持有磁盘文件，也不创建副本。
- 未注入 resolver 的执行环境统一返回 `attachment_resolution_unavailable`，不静默成功。
- 识别无缓存；结果作为显式 Tool Result 已是正确的历史记录。
- 失败 reason 原样透传（表见 [prompts-and-tools.md](prompts-and-tools.md)）。

### 4.3 设置与迁移

- `ResourceSelections.attachmentInspectionModelId` 保存本域的类型化配置引用；设置页只允许选择当前域可用且声明 IMAGE 输入的 Chat 模型。用户定义保持一份，企业策略只限制本域使用。
- 旧 `ocr_model` / `ocr_prompt` 只存在于一次性迁移边界（`SettingsOcrMigration`）：新 key 优先；有效旧视觉模型（Provider 存在且声明 IMAGE 输入）映射到新字段；旧 Prompt 丢弃；旧 key 清除；旧 observation cache best-effort 清理。备份恢复（S3 / WebDav）在导入 settings.json 前同样应用 `migrateLegacySettingsJson()` 旧键映射，见 [Android 配置架构](android-configuration-architecture.md)。

## 5. 投影时序（三条链路）

| 链路 | Transformer 顺序要点 |
|------|---------------------|
| Master 聊天 | `DocumentAsPromptTransformer` → Template → Workspace → 可选 `ToolArtifactReplayTransformer` → `AttachmentProjectionTransformer` |
| `generate_image` 产出 | 成功时 Image part 落入本次 Tool.output 并盖章；下一个 step 的请求由投影管线回放（原图或引用行）。识别这张图 = 把它的 `file.path` 传给 `inspect_attachments` |
| Target（`assistant_call`） | Child 拥有完整 Assistant 级 transformer 链 + 自己的 resolved model；入站只校验 path / 资产，视觉能力由 Target run 自己的投影与工具集表达；`AttachmentProjectionTransformer` 同样位于动态模板之后、Provider 序列化之前 |

披露快照、条数窗口和请求规划由 [request-context.md](request-context.md) 定义；附件投影只处理已有媒体事实，不生成披露 entry 或会话摘要。

## 6. 资源提交与恢复

`ToolResultCheckpoint` 提交 output transformers 完成后的消息，消息、执行事实与 typed Artifact 引用进入同一 Room 事务。资源发布必须等待该 durable root 已包含本地引用；不能发布资源却持久化转换前消息。

同一次 base64 output transform 产生的多个 Artifact 只注册一个 `unpublishedBatchLease`。`ArtifactStore.publishAllUnpublished` 在交接前验证整批 durable roots 与 ownership token；失败或取消精确清理该 owner 取得的未发布资源，不留下半批发布状态。

Artifact metadata、引用和生命周期归 `ArtifactStore`；`ArtifactPayloadStore` 只处理磁盘 IO。启动时按 CREATING / ACTIVE / DELETING 状态与 durable roots 收口，不能仅凭 payload 存在认领资源。图库生成媒体由 `GeneratedMediaStore` 独立恢复；全局恢复门禁在两者完成前阻止文件查询和写入。

工具副作用之前的 STARTED、checkpoint、终态 CAS、UNKNOWN 与父子恢复顺序统一见 [turn-step-execution.md](turn-step-execution.md)。资源 durability 复用这条提交链，不建立第二张执行表或旁路写协议。

`GeneratedMediaStore.commit` 以同步 `receiveChatArtifact` 回调明确聊天副本的接收者，图库 row 提交后、可取消返回边界前交给原 `ToolExecutionContext.registerUnpublishedResource`。接收后由工具 checkpoint 协议发布或丢弃；接收失败只清理未交付副本，保留已提交图库。未提供接收者的页面任务只生成图库原件，不先创建无人管理的聊天副本。

## 7. 媒体回放边界

- 请求级附件投影保留来源容器与 durable 原文，模型切换无需迁移消息。
- `ToolArtifactReplayTransformer` 按 artifact metadata 重新物化历史工具产物路径与 Image URL。
- 历史 Assistant 的 replay-safe 投影移除未持久化图片与媒体保存失败占位；Tool.output 中以媒体失败文本表达，不伪造可读取路径。识图工具的内存 data URI 输入属于独立请求，不受此历史回放规则限制。
- `resultStatus` 表示 Tool Result 存在，合法空 output 原样保留。媒体缺失不改变执行事实，也不能触发自动重执行。
- 未成功 Assistant 的完整 Step 前缀、尾部处理及 Provider 严格回放策略见 [turn-step-execution.md](turn-step-execution.md) 和 [protocol-reference.md](protocol-reference.md)。

## 8. 组件与职责

| 符号（全局搜索定位） | 职责 |
|----------------------|------|
| `AttachmentRefs` | ref 前缀、metadata 键、merge / ensure / backfill、file URL |
| `AttachmentResolver` | path → 识图内存快照 / Child 本地 Image；只通过 ArtifactStore 读取 |
| `AttachmentProjectionTransformer` | 请求级投影（本文件 §3） |
| `AttachmentInspectionTool` / `ModelExecutionService` | `inspect_attachments` 工具、原域捕获与逐请求准入 |
| `ToolExecutionContext` / `ToolAttachmentResolution` | ai 模块最小只读附件能力接口 |
| ToolOutputStore | Artifact-backed Tool Output staging、marker、conversation-scoped bounded read/grep |
| `ArtifactStore` / `ArtifactPayloadStore` | 附件 metadata、引用与生命周期 / 受管磁盘 IO |
| `GeneratedMediaStore` | 图库生成媒体的 canonical row、payload 与删除恢复 |
| `ConversationApplicationService` / `ConversationTurnService` / `SubAssistantRunCoordinator` | 各消息入口的附件盖章与资源交接 |
| `SettingsOcrMigration` / `migrateLegacySettingsJson` | 旧 OCR 设置迁移边界 |

## 9. Tool Output 压缩 durability

归档与折叠的触发阈值、保护窗口、净回收要求和成功消费 receipt 统一见 [request-context.md](request-context.md)。本领域只执行资源 staging 与提交交接：

- `ARCHIVABLE_TEXT` 由 `ToolOutputStore` 创建未发布的 `tool_outputs` Artifact，并生成 marker 与 `runtimeState.archive`；`REGENERABLE_TEXT` 只写固定 folded marker，`PRESERVE` 保留原文。
- `ModelResponseCheckpoint` 将历史 `toolOutputCompactionPatches`、当前 Assistant 和 typed `artifact_reference` 同事务提交；无工具 Final Step 的同类 patch 随 `FinalizeTurn` 提交。Reducer 使用已提交 durable 原文校验 patch，不从 streaming projection 获取历史事实。
- 提交成功后 publish lease；失败保留原 inline output 并精确 discard 暂存 Artifact。归档不建立新表或第二 checkpoint。

`ArtifactStore.withToolOutputText` 在 lifecycle lock 内同时验证 ACTIVE、`tool_outputs` folder、`text/plain`、当前 conversation
的 `TOOL_OUTPUT` reference 并取得 retention pin，锁外流式读取，finally 释放。ref 猜测、其他会话、payload 缺失都统一
fail-closed。fork 的节点引用同一 Artifact；删除任一会话只移除自己的 reference，最后引用消失后才允许 GC。backup/restore
包含 `tool_outputs` durable directory，启动恢复按 Artifact 既有 CREATING/DELETING 协议收口。
