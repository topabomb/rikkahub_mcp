# Android 远程工作区界面执行计划

本文记录 Android 远程工作区的实施范围、顺序和验收要求。制定日期：2026-09-29。
2026-09-30 开始功能实施；功能和验收在完成前仍是目标，不代表当前已经具备。
修改仅限 Android 仓库，Core 与 Agent Space 只作为合同来源和本地联调依赖。

## 1. 已确认范围与实施基线

用户已确认：企业空间“企业连接”后增加紧凑摘要卡片；聊天输入框“＋”快捷面板增加远程工作区入口，
仅可用时显示；两处进入同一个独立管理界面；复用已有 UI，确有必要时调整通用组件归属。
管理界面以远程文件的浏览、整理、上传下载、预览、编辑、分享为核心。

核对的 Core 基线为 `35e3ac9cda9d255a404979004400f759285d7939`：
`feat: complete workspace file contract and admin text editing`。复核结束时 Core 工作树干净。
它包含此前 `d7f310a` 的 Hub/Relay/Admin 集成及后续文件客户端修订。
Android 调研基线为 `0e1b10e9b`，开始调研时工作树干净；尚无远程工作区客户端和界面。
上述工作树描述属于调研时点，实施与联调使用的实际身份记录在第 9 节。
跨组件合同核对基线为 architecture `e6ea18a26c59dfc28316042fea66e386848956ac`，工作树干净。
开始实施时重新核对各基线、工作树和导出合同，不覆盖他人新改动。

权威和源码依据：

- [Core Android 对接说明](../../../measix/measix-platform-core/docs/android-platform-integration.md)。
- [Core 实现参考](../../../measix/measix-platform-core/docs/remote-workspace-implementation.md)。
- [Core 验收记录](../../../measix/measix-platform-core/docs/remote-workspace-verification.md)。
- [跨组件合同](../../../measix/measix-architecture/docs/10-runtime-foundation/s1/measix-s1-remote-workspace-contract-spec.md)。
- Core `api/client/client-control.openapi.yaml`、`backend/internal/hub/workspace/service.go`、
  `files.go`、`backend/internal/hub/httpapi/workspace.go`、`backend/internal/hub/agentspace/dav.go`。
- Core `console/src/components/WorkspaceTextEditor.vue`、`WorkspaceFiles.vue` 和
  `console/src/composables/workspacePreview.ts` 及其行为测试。
- Android [UI 架构](../references/ui-architecture.md)、[配置架构](../references/android-configuration-architecture.md)、
  [Workspace 架构](../references/workspace-architecture.md)、[测试策略](../references/testing-strategy.md)。

### 1.1 目标与 Android 实施边界

目标是在企业身份下完成远程文件管理闭环，同时保持当前聊天草稿、本地 Workspace 和既有企业登录流程。
下述范围是 Android 仓库的实施任务；服务端能力作为已提供的协议前提核对，不转成服务端改造任务。

| 范围 | 本计划的责任 | 完成依据 |
| --- | --- | --- |
| Android | 原生两处入口、独立管理页、文件客户端、身份/传输/临时资源生命周期、必要共享组件、五语言和测试 | 本文功能与验收矩阵 |
| Android 消费合同 | 同步 Core 已导出到本仓库的 schema、manifest、fixtures，扩展现有生成链 | 来源摘要、生成检查及消费行为测试 |
| Core / Relay / Admin | 只读核对既有身份、状态和文件协议；作为联调依赖 | 固定构建和真实请求证据；发现阻碍单独报告 |
| Agent Space / Portal / 部署 | 不改源码、协议、数据库、发布或运维流程；Portal 仅同步 Android 内消费副本 | 外部仓库和生产验收分别由其负责人完成 |

首版预览类型、客户端资源上限、传输驻留和分享副本清理期限是本计划的 Android 产品选择，
并非 Core 对所有客户端的硬性配额；实现时通过设备场景验证。
不建立第二份远程目录数据库，不修改共享 Snapshot v4/v5。
空间开通、断开、恢复、整空间删除、DAV 凭据签发与交付继续属于 Admin。
文件可用性独立于 MCP 发布、助手绑定、模型类型和工具预算。

## 2. 两处入口及统一状态规则

### 2.1 唯一状态来源

使用当前企业 Session 的 Bearer 调用 `GET /api/client/v1/workspace`，严格消费 schemaVersion 1。
文中写标准部署路径；实际 URL 由原 `PlatformConnection.control("/workspace")` 和
Discovery 的 `clientApiBase` 构造，走 Hub 控制地址，不使用 Runtime Relay、Portal Cookie 或直接 DAV。
以 `serviceState` 表达企业配置意图、`state` 表达用户空间生命周期、`filesAvailable/filesReason`
表达文件准入；`mcpAvailable/mcpReason` 只用于详情说明。不能从 MCP 名称或本地 Workspace 推断远程能力。

本地查询状态另行区分 Initial/Loading/Ready/Failed，以及当前结果是否已经失效。
这些是请求与展示状态，不替代服务端空间生命周期。未知枚举、缺失必填字段、矛盾的关键身份信息
进入可诊断的合同失败，不按未开通或未启用降级，不给 `serviceState` 补默认值。
例如 filesAvailable=true 必须同时具备 ENABLED、CONNECTED 和合法 agentSpaceId；
不要求 mcpServerId 或 mcpAvailable=true。可选字段缺省与显式 null 按现有严格 wire 规则区分。

企业页和聊天页订阅同一 service 的只读投影，UI 不各自发 HTTP 或维护独立可用布尔值。
同一身份的并发刷新合并；较早查询不得覆盖较新查询或文件失败后的失效状态。
登录、切入企业、前台恢复、打开快捷面板、进入管理页、手动刷新和文件访问失效触发必要查询。
重新验证期间快捷入口隐藏；纯 Compose 重组不触发请求。不增加常驻轮询或无依据的 TTL 系统。
“同一身份”包含选择版本、Session 和连接上下文；刷新合并只在这一范围内成立。
`bindingRevision` 是服务端绑定修订，不是文件 ETag 或本地请求序号；修订/空间变更使旧操作上下文失效，
但它不作为客户端自造的文件条件参数，修订不变也不能证明 DAV 凭据仍有效。
迟到结果在发布前再次核对原上下文。

`filesAvailable=true` 是准入信息，不是实时探活结果。摘要只查询状态，不扫描目录、不预取预览。
实际文件服务或授权不可用时撤销本地“可打开”状态，重新查询并显示读取错误，不能用一次仍为 true 的
服务端投影把已经观察到的连接问题伪装成恢复；显式重试读取成功后再恢复正常文件展示。
恢复入口始终保留在企业卡片/管理页：用户点击重试先复验身份与状态，若服务端仍允许文件访问，
即使本地保留上一轮连接错误，也允许一次新的只读目录请求；成功后清除该错误并恢复快捷入口。
不可用投影禁止文件请求，本地读取错误不能形成“入口隐藏后永远无法复验”的循环。
文件不存在、单文件冲突、目录超限、并发已满和容量不足不导致整个工作区或企业 Session 失效。

### 2.2 企业摘要卡片

放在 `EnterprisePage` 的企业连接之后，沿用页面间距、圆角、主题与 720dp 宽度约束。
仅当前选择企业域时展示；个人域的企业连接摘要不附带可执行的远程入口。

正常形态为两行、一个入口，不放常驻按钮排、容量条、文件数或技术 ID：

```text
远程工作区                         文件可用  ›
浏览和管理远程文件
```

异常时第二行改为简短、可行动的原因；点击打开同一路由的状态说明，详情可展开、复制并重新检查。
状态文案必须由同一 mapper 产生，不能由两处 UI 分别组合。建议映射：

| 条件 | 企业卡片 | 聊天快捷入口 |
| --- | --- | --- |
| 个人域、无有效企业身份、正在切域/退出 | 不展示远程业务卡片 | 隐藏 |
| 初次查询或必须重新验证 | 紧凑“正在检查” | 隐藏 |
| 状态查询失败 | “暂时无法获取状态”，保留重试 | 隐藏 |
| NOT_CONFIGURED，且没有已有空间 | 隐藏 | 隐藏 |
| DISABLED，且没有已有空间 | 隐藏 | 隐藏 |
| ENABLED + UNPROVISIONED | “尚未开通 · 请联系管理员” | 隐藏 |
| CONNECTING / RESTORING | “正在开通” / “正在恢复” | 隐藏 |
| DISCONNECTING / DISCONNECTED | “正在断开” / “已断开” | 隐藏 |
| 已有空间且服务 DISABLED | “企业已关闭远程工作区” | 隐藏 |
| DELETING / NEEDS_ATTENTION | “正在删除” / “需要管理员处理” | 隐藏 |
| CONNECTED，但 filesAvailable=false | 按 filesReason 显示文件服务或凭据不可用 | 隐藏 |
| 文件访问失败，尚未完成显式读取复验 | “暂时无法读取文件”，保留重试 | 隐藏 |
| 当前有效身份、合法 agentSpaceId、filesAvailable=true，且无未解除的本地访问错误 | “文件可用” | 显示 |

组合状态优先说明用户当前实际阻碍：身份失效先撤销入口；删除/待处理不被普通连接状态覆盖；
服务关闭不写成凭据丢失。DELETED 按返回事实显示；Core 删除记录后返回 UNPROVISIONED 时，
不要自行永久保存一个“已删除”状态。未知 reason 显示通用可行动说明，并保留原 reason 字符串；
投影没有 detail 字段，HTTP Problem 的 code/detail 单独保留，不虚构服务端诊断。

“文件可用”不使用“在线”“运行中”“已同步”等词。当前投影没有 VM 运行状态、容量和文件总数；
observedAt 也不是本次 HTTP 查询时间。容量仅在目录请求取得真实数据后于管理页详情展示；
`usedBytes` / `availableBytes` 为可选卷信息，不推导用户 quota、计费余额或本目录大小。
条目的 size/modifiedAt/etag 也可缺省，未知值不补 0/当前时间，排序时未知值统一置后。

### 2.3 聊天“＋”快捷面板

实际接入链为 `ChatPage` → `ChatFilesPickerSheet` → `FilesPicker`。
在本地工作区项之后、MCP 项之前增加独立“远程工作区”行；即使本地工作区列表为空，远程行也能显示。
沿用现有列表项的尺寸、图标与文字排版，尾部箭头；不附加工具开关、终端按钮或 workspaceCwd 控件。

显示条件必须同时满足：

1. 原聊天页面/配置目标仍有效，原会话的企业 RealmSelection 与当前选择相同。
2. 原 Session 有效，摘要属于相同企业主体、Session、选择版本和平台连接上下文。
3. 当前查询成功且未失效，filesAvailable=true，并有合法 agentSpaceId，且无未解除的本地访问错误。

加载、未知、未开通、关闭、断开、访问失败均隐藏整行。没有 MCP、助手未绑定本地工作区、
模型不支持附件，都不影响这个文件管理入口。
点击时先复验原目标、关闭快捷面板，再进入同一远程工作区页面；状态在点击期间失效则拒绝旧导航。
不能复用 `onManageSharedConfiguration`，它在企业聊天中会打开“修改共享用户配置”的确认流程，
不适用于远程企业文件。增加明确的远程导航回调，保留聊天草稿、附件和当前助手，不创建或发送消息。
返回管理页之前的聊天时仍保留原导航栈；切域或退出导致原聊天无效时走现有导航恢复规则。

## 3. 独立管理页面与功能闭环

默认直接展示文件；服务端状态不可用时显示原因、重试和返回，不发目录请求。
本地读取错误按 §2.1 的显式只读重试恢复；身份有效、CONFIGURATION_PENDING 且尚无 Applied
配置时，也应能从企业页查询和进入文件页，不套用模型执行或助手配置的准入。聊天入口仍要求原页面有效。
顶部为返回、标题、刷新、更多；路径栏为“全部文件”及可点击面包屑；主体为目录列表；
“添加”菜单包含上传文件、新建目录、新建文本。详情、排序和隐藏文件开关放更多菜单。
宽屏沿用独立全屏导航，扩展内容宽度/信息列；首版不引入目录树与新的双栏页面体系。

| 功能 | 首版行为与验收点 |
| --- | --- |
| 浏览 | 目录优先，支持返回上级、面包屑、刷新、隐藏文件显示；空、加载、错误分开 |
| 排序/筛选 | 名称、修改时间、大小；仅筛选当前已完整读取的目录，不宣称全盘搜索 |
| 新建 | 当前目录下建文件夹或 UTF-8 文本；检查名称、重复项及根目录保护 |
| 上传 | 系统文件选择器多选；固定选择前的远程目录和身份，逐项流式传输 |
| 下载 | 单文件 CreateDocument；批量选目标目录后逐项导出，完整 close 后才记成功 |
| 重命名/移动/复制 | 单项菜单、多选操作；图形化目标目录选择；普通文件覆盖显式确认当前目标版本 |
| 删除 | 明确文件名称/数量；目录递归确认；说明不可撤销；展示部分完成和失败明细 |
| 预览 | 文本、Markdown、常见栅格图片、PDF；默认只读；不支持或超限时下载/外部打开 |
| 编辑 | 显式进入；保存并关闭、另存新文件、未保存离开确认；失败保留当前正文 |
| 分享/外部打开 | 下载完整私有副本后，以只读 content URI 交付系统选择的应用 |
| 详情 | 文件元信息、工作区归属、文件/MCP 各自状态、真实容量；技术诊断折叠 |
| 批量结果 | 区分成功、失败、部分完成、结果未知和未开始；已完成项保留，不承诺整批事务或重放整批 |

长按进入选择模式，顶部显示数量并提供批量动作。目录下载/分享不伪装成压缩包能力，首版不提供。
多项传输默认顺序执行，优先简单可取消的队列，不用完服务端全部并发名额。
批量目录选择仅针对同一个远程空间；祖先目录与其子项不重复入队，禁止目标等于源或落入源目录内部。
取消停止未开始项，已经发出的写入须区分已确认结果与待核实；客户端取消不承诺远端回滚。
当前 Core 无目录分页协议且限制 DAV 响应规模；目录超限显示明确错误，不截断成成功列表。

工作区状态读取不启动 VM；首次目录读取可能较慢。显示“正在读取远程文件，首次访问可能需要较长时间”
并允许取消，不制造 VM 启动百分比。容量字段缺失时省略，不显示 0 或据此推断企业存储配额。

## 4. 组件复用及代码职责

### 4.1 UI 提取范围

现有 `WorkspaceDetailPage` 的私有文件行依赖 `WorkspaceFileEntry`、本地文件区和固定动作菜单，
不能直接让远程页面依赖整个本地工作区页面。提取真正被两侧使用的展示部分：

| 现有内容 | 处理方式 |
| --- | --- |
| 文件行、目录图标、选择框、更多按钮布局 | 提取到 `ui/components/files/`，只接受展示值、可用动作和回调；本地页同步改用 |
| 路径栏、空目录与加载/失败展示 | 按真实共用形态提取；路径跳转和刷新仍由各页面提交命令 |
| `FileTextEditor`、`FileEditorState`、`FileEditorInputConnection` | 已位于通用 UI 目录，直接复用；不为目录整齐做无收益搬迁 |
| `ImagePreviewDialog`、`ImageSource` | 复用查看器和授权读取能力；远程 service 提供受限读取，不传 Bearer URL |
| Markdown 渲染 | 复用解析/排版与链接/图片能力入口，增加受限来源与禁用原始 HTML 策略；不得直接继承默认富文本的 HTML 分支 |
| 文件导出/分享 | 复用系统 SAF、FileProvider 交互与已有提交后交付模式，流式接口按真实使用提取 |
| PDF | 当前未发现现成 Android 查看器；在文件预览组件下新增受限原生分页渲染 |

通用 UI 不依赖 `WorkspaceFileEntry`、Platform wire、DAO、Repository 或远程 service，
不持有文件身份的授权事实。动作列表是展示输入，不发展为文件管理插件框架。
提取必须同时迁移原调用者并删除失去调用的私有实现，保持本地行为和已有测试。
不一次性搬迁全部 Skill/聊天/图库页面，也不把应用文件业务下沉到 `common` 或 `workspace` 模块。

本地 `WorkspaceFileType` 包含 HTML/SVG 等分类，远程预览规则不能直接继承它。
文件名分类只是展示线索，实际内容需检查格式、编码及大小；二进制响应当前固定为 octet-stream，
不能仅依赖 Content-Type 判定可预览。HTML/SVG 首版下载或交给外部应用，不在应用内执行。

### 4.2 应用和传输职责

建议在 `app/service/remoteworkspace/` 增加一个内聚的 `RemoteWorkspaceService`，提供 typed application
命令和只读状态/页面投影，拥有客户端活动请求、传输与未交付临时文件的生命周期。
它不拥有远端文件持久事实；不再为命名拆一组只做转发的 Query/Repository/Manager。
数据传输层在 `data/enterprise/` 复用 PlatformConnection、Problem 和 OkHttp 配置；
文件流按职责由 `PlatformWorkspaceClient` 承接，不把大文件读入控制 API 的 JSON 缓冲。
沿用 `PlatformControlClient` 的禁止普通/TLS 重定向、`PrivateRequest` 日志保护及单次写入策略；
不能直接继承公共 DI 客户端默认开启的重定向和连接重试。文件流采用有限连接/响应头等待及无进展超时，
不照搬短控制请求的总时限；JSON/Problem 有界解析，下载上传使用背压，不整文件装入内存。

身份、Token 刷新、退出仍归 `EnterpriseSessionController` / `PlatformEnterpriseService`。
新增远程 service 只借用原身份，不能维护第二 Session。所有网络 IO 在 owner 锁外执行。
`PlatformEnterpriseService.accessToken()` 的平台 operation lease 在返回 Token 时已经释放，
不能代表后续长传输。远程 service 必须通过原 Session owner 登记覆盖完整 IO、结果确认、资源退出
的 operation lease，并在请求发出和结果发布/系统交付前复验原选择、Session、连接与目标。
Token 刷新不产生新操作身份；连接或空间变更不能将原队列迁移到新目标。
企业页及 ChatVM 只消费其 UiModel；导航注册位于 `RouteActivity` 中的 `Screen`/entry，DI 沿现有模块装配。

每次请求固定原 RealmSelection、完整企业主体、Session、平台连接、agentSpaceId，以及相对路径和
适用的文件 ETag。全体 files/content 请求携带 agentSpaceId，客户端不传 userId 来选择用户。
query 路径以 URL builder 编码一次，POST JSON 的路径保持逻辑原文；只有目录列表可用空串读根目录，
content 的 GET/HEAD 也不能读根。按 Core `RelativePath` 检查 UTF-8 路径不超过 4096 字节，
不接受绝对路径、空段、`.`/`..`、反斜线、NUL/CR/LF、以 `.agent-space` 开头的路径段及
`%2e/%2f/%5c/%25/%00` 编码别名（大小写不敏感），保留路径也不能读取。
不把非法操作路径自动规范化为另一个目标；Markdown 相对引用先在当前文件父目录下解析并检查仍在根内，
再生成合法 API 路径。名称检查提供客户端反馈，服务端仍负责最终校验；列表无同名项不能代替创建条件。

## 5. 请求、取消、草稿与临时文件

### 5.1 条件写入与未知结果

所有 files/content 请求带原 `agentSpaceId` query；POST 的路径与条件位于 JSON，
Android 不直接发送 WebDAV 方法、Destination 或 tagged If。具体映射如下：

| Android 操作 | Core 请求与必要条件 |
| --- | --- |
| 列目录 | `GET files?path=…` → `WorkspaceFileList`，无分页游标 |
| 读取元信息/正文 | `HEAD/GET content?path=…`；HEAD 不能代替编辑正文同一次 GET 的 ETag |
| 新建/另存/上传新文件 | `PUT content?path=…`，原始正文，`If-None-Match: *` |
| 覆盖普通文件 | 同一 PUT，单个强 `If-Match`，与 If-None-Match 互斥 |
| 新建目录 | `POST files`，`{action: MKCOL, path}`；不套用 PUT 的条件头，存在冲突交由服务端拒绝 |
| 重命名/移动/复制 | `POST files`，action 为 MOVE/COPY，path、destination；普通文件必须 sourceEtag；默认 overwrite=false |
| 覆盖目标普通文件 | MOVE/COPY 另外要求 overwrite=true、确认时的 targetEtag；源与目标必须均为普通文件 |
| 删除普通文件 | `POST files`，action=DELETE、path、sourceEtag |
| 删除目录 | 同一 POST，recursiveConfirmed=true；不要求不存在的文件 ETag，不承诺子树快照或事务 |

目录 MOVE/COPY 仅允许目标不存在；源 ETag 有合法值时可携带，不伪造缺失值。
强 ETag 原样保留引号，不剥离、不接受弱值、`*` 或列表作为 If-Match。缺少强 ETag 的普通文件
可下载/预览/另存，但禁止覆盖、MOVE/COPY/DELETE 等要求源版本的操作，并说明原因。

- 上传换文件、换目录或目标版本变化后，旧覆盖确认失效。
- PUT/POST 以单次尝试发送，关闭隐式连接重试和可重放 request body；不能套用
  `PlatformEnterpriseService.read()` 的认证重试，也不能在 503/取消后自动再发一次。
  复用已有 `withSingleAttemptBody()`；30x 不跟随，不通过重定向重发正文。
- 写入前取得当前 Token；写入被拒后可走原身份刷新流程，但新写请求由用户重新决定。
  只读状态请求可复用已验证的刷新重试；流式下载的重试必须先重建输出，不能在旧文件尾部盲目追加。
- 首版下载使用完整 GET，不实现断点续传或持久 HTTP 内容缓存。未请求 Range/条件缓存却收到
  206/304 时拒绝当作完整文件发布；未来引入续传再按合同实现 Range、If-Match、Content-Range 和 416 处理。
- POST/PUT 只有符合合同的 `WorkspaceFileResult` 才能确认结果，不能将空 204 或任意 2xx 当作成功。
  检查 outcome、failures、truncated；上传字节进度达到 100% 时显示“等待确认”，SUCCEEDED 才完成。
  PARTIAL 展示已返回的 path/status/code；truncated=true 明确“仅返回部分失败明细”，不能由该列表
  推算所有未列项成功。UNKNOWN 或发送后无法解码/结果矛盾均保留待核实，不自动重放。
- 网络中断、取消及 UNKNOWN 不证明远端没写入；禁止无提示重放原目标，提供重新读取核实/更名另存。
- 409 保留草稿和原目标；空间不匹配时回到状态核查，绝不改用新空间 ID 提交旧内容。
- 文件服务/DAV 503 不触发 Core 登出；Core 身份失效按稳定 code 进入原认证处理。
  保留 HTTP 状态、Problem code/detail、原异常与可复制诊断；403 不一律视为退出。

| 失败类别 | Android 行为 |
| --- | --- |
| 400 路径/条件无效 | 保留输入并修正请求，不重复原写入 |
| 404 file_not_found | 刷新所在目录，保留编辑；不清空企业身份 |
| 409 workspace_space_mismatch | 撤销旧目标，重新查状态；旧正文不得转投新空间 |
| 409 file_version_conflict / file_conflict / file_locked | 保留草稿/操作信息，重新读取核实或更名另存 |
| 422 file_listing_limit | 明确目录超限；无完整列表时禁用依赖完整列表的筛选/全选，不伪造分页 |
| 429 file_transfer_limit | 提示等待已有传输结束；不当作 DAV 凭据失效或自动重试写入 |
| 503 工作区/凭据/连接不可用或 workspace_result_unknown | 区分访问失败与结果未知；状态重查和只读核实，不据投影为 true 清除原错误 |
| 507 file_storage_full | 保留本地内容，由用户释放空间后决定后续动作 |

局部失败不关闭工作区；未知 code 保留原诊断且不猜测认证撤销。
PUT/POST 的重定向、408/503、认证刷新与取消均须测试没有第二次发送。

Core 近期修复包含：BOM 后正文 U+FEFF 被二次剥离、不可用空间的旧目标请求错误分类、上传字节计数竞争。
Android 需要对应的字节往返、先判断目标身份、进度线程安全测试，不复制 Vue 实现细节。

### 5.2 关闭与切域顺序

首版传输属于打开的管理会话，允许其目录、预览子页和配置旋转继续观察；显式离开管理会话有在途
传输时选择继续停留或停止后离开。按 Home 普通后台停留不声称任务已完成；不承诺系统杀进程后的续传，
恢复只重新查状态/目录，不重放写入。不新增 WorkManager、前台下载服务或持久任务表。

取消请求必须关闭 Call/Response/输入输出、等待本次资源实际退出，再释放租约。
已有 `EnterpriseExitService.closeEnterpriseDomain` 先等待 platform operations；新增长文件请求后，
要在该等待前关闭远程请求准入并 cancelAndAwait 其请求，避免退出等待一个仍在下载的 lease。
错误处理触发退出时，不能同步等待包含本请求的退出屏障；沿原异步失效协议并先结束/释放本请求。

`sessions.switchRealm` 的旧宿主回调运行在 Session mutex 内。因此切域在该回调中只能撤销访问、
捕获原任务并发出取消；实际等待 IO/lease release 放到锁外的现有收尾阶段。
切换失败也不复活已撤销的原页面能力。所有 picker、对话框和迟到结果须重新校验原选择版本。
这一顺序以取消/退出/切回竞态测试证明，不依赖 UI 恰好销毁。

### 5.3 编辑与预览资源

正文与 ETag 从同一次 GET 取得；文本上限采用 Core Admin 的 2 MiB，严格 UTF-8，保留单个 BOM 标记、
BOM 后真实 U+FEFF 及统一 LF/CRLF/CR。非法编码、NUL、混合换行不自动转码后覆盖。
编辑缓冲与原编码/换行信息一起保存于内存；发送前按原格式编码，含 BOM 的最终字节仍须不超过 2 MiB。
没有强 ETag 时允许只读及另存新文件。另存目标曾出现未知结果时先核实或换新目标。
成功保存并关闭；下一次编辑重新 GET，不用 HEAD 得到的新版本去配旧编辑缓冲。
保存时冻结本次正文、目标和条件，阻止重复保存及期间继续改写待关闭的编辑缓冲；失败恢复编辑，
访问失效后的恢复也须重新 GET 并明确处理旧正文，不能给旧缓冲静默换上新 ETag。

复用现有 FileEditorState，正文由原 ViewModel 的单个内存编辑会话持有；未保存返回、关闭、重读确认，以及另存、保存并关闭，
是远程页面需要新增的交互，不是 `FileTextEditor` 已有能力。普通后台保留内存正文，
Activity/界面重建保留原会话草稿与读取版本，进程死亡后重新读取，不恢复旧授权。正文不放 Bundle、SavedStateHandle、
导航参数或新增 durable draft。正在写入的提交副本与传输 owner 绑定，重建不能重放保存。
同身份临时失败保留可复制正文；切域/退出后撤销旧内容展示和写能力，绝不转投新身份。

文本/Markdown 最多 2 MiB；图片/PDF 初始采用 24 MiB 文件上限，图片解码前限制 1600 万像素，
PDF 逐页渲染并限制画布像素。按实际读取字节强制上限，不能只相信 Content-Length。
Markdown 不执行原始 HTML、不自动加载外部资源；相对图片通过同一授权文件读取，
按 Core 首期边界每张 4 MiB、最多 20 张。外链只接受明确安全 scheme，并由用户点击打开。
关闭/切域取消预览读取与 PDF 渲染、释放画布和句柄；列表首版用文件图标，不自动下载全部图片作缩略图。

下载必须确认完整 EOF、可用 Content-Length 与实际字节一致、输出 close 成功，才算完成或交付分享 URI；
异常/不完整响应只清理本次输出，不把半文件当预览。SAF 目标从创建起可能已对用户可见，不承诺原子隐藏；
清理失败记录残留 URI 并给出定位/删除说明，不能报成功。
SAF 创建返回的 document 是本次所有权，失败只清理本项未完成输出，
不删除以前成功文件、不截断用户已有同名文档；输出 close 失败不是下载成功。
分享文件不可在 chooser 打开后立即删除；使用专用私有临时目录，失败立即清理、预览关闭清理自有副本，
已交付分享副本按明确期限（首版 24 小时）在后续访问/应用启动清理，无常驻定时器。
清理只作用于这一目录及本 owner 生成的文件；退出清理未交付资源，不能承诺收回其他应用已收到的副本。
缓存按主体、Session、空间、路径、版本隔离；无强版本不长期复用。缓存命中仍须验证当前授权。
初版不开放远程 DocumentsProvider，不把企业 Bearer 或 DAV 凭据交给系统下载器或外部应用。

## 6. 实施顺序与逐步完成条件

| 步骤 | 修改范围 | 本步完成条件 |
| --- | --- | --- |
| 1. 固定合同和样例 | 读取 Core 已有导出 → Android contract resources、manifest；生成器 ROOTS 加 WorkspaceProjection、WorkspaceFileList、WorkspaceFileMutation、WorkspaceFileResult 及引用类型；更新 PlatformWire 和 Android 内 Portal 副本 | hash/生成校验通过；未开通、files-only 样例严格解析；保留 v4/v5 回归 |
| 2. 身份与传输核心 | 远程 service、平台文件 transport、typed outcome/error、Session/退出/切域接入 | 固定空间、无写入重放、取消和退出无悬挂等定向行为测试通过 |
| 3. 共享 UI 与只读纵向链路 | 提取文件展示组件并迁移本地调用；统一摘要 mapper；两个入口与同一路由；目录浏览 | 可用时快捷行出现；无 MCP 仍可读；不可用快捷行消失；卡片正确解释；本地无回归 |
| 4. 文件管理与传输 | 上传下载、新建、重命名、移动复制、删除、目录选择、多选、结果明细 | 成功/冲突/部分/未知/取消完整闭环；已完成外部文件保留 |
| 5. 预览、编辑、分享 | 复用 editor/image/Markdown；补 PDF；临时导出；修改确认 | BOM 字节保全、竞争保存、另存、访问撤销、系统分享和清理通过 |
| 6. 整体验收和交付 | 五语言、参考文档、全量门禁、设备与真实 Core 联调、最终 diff | 下节验收完成或逐项标明阻碍；只提交本次范围，不更新版本号/changelog |

这些是实现依赖顺序，不是以只读半成品代替最终交付。状态/身份协议先于快捷入口，
条件写入先于编辑，资源生命周期先于分享。测试针对失败和竞态，不用静态源码扫描证明 UI 行为。

合同同步沿 `tools/generate-enterprise-wire.py` 现有生成链，不能手写第二套 wire DTO。
普通构建仍使用检入产物，不新增对同级 Core 仓库或 Python 的构建时依赖。
具体输入为 Core `api/generated/android/{client-control.openapi.yaml,manifest.json}`、
`api/fixtures/workspace/` 的两份投影样例及 `api/generated/android/portal/`；消费位置为
`app/src/test/resources/contracts/`。校验源内容与 manifest，不能单改 hash；Portal 副本仅在实际
导出有差异时整套按 manifest 同步，不要求修改 Portal 仓库。
实施前已确认 Android Client sourceHash 与 Core 导出不同；现沿生成链同步消费副本，不手工改 hash。
Core 全局 Preview 检查若仍因 Portal 仓库漂移失败，
分清责任并报告，不改他人仓库或用修改 hash 绕过检查。

实施期间按实际变更更新 `docs/references/workspace-architecture.md` 的远程文件边界，
以及 UI/配置/测试专题中的对应摘要和链接；不把未实现计划写成当前参考事实。
新增可见字符串同步 values、values-zh、values-ja、values-ko-rKR、values-ru。

## 7. 验收矩阵与执行命令

| 层级 | 必须覆盖 |
| --- | --- |
| Wire/HTTP | Core 样例、必填/未知/null 字段、路径编码与保留区、中文/空格文件名、强 ETag、各动作 JSON 条件、完整 GET 与异常不完整响应、outcome/truncated、30x 与无写入重试、私密日志保护 |
| 状态/入口 | 未配置、关闭、未开通、连接中、CONFIGURATION_PENDING、仅文件可用、MCP 可用但文件不可用、未知/失败、失败后显式读取恢复、旧查询迟到、点击时撤权 |
| 身份/并发 | 两用户隔离、个人聊天隐藏、快速切域返回、重新登录、重开新空间、同名路径版本变化、地址上下文变更 |
| 生命周期 | 慢流取消、退出前取消长请求、切域锁内撤销锁外等待、自身错误触发失效不死锁、重复关闭、picker 迟到 |
| 写入 | 上传同名冲突、双编辑者、BOM/U+FEFF/换行字节保全、未知结果保留正文/阻止重放、部分删除、507 空间不足 |
| Android 平台 | SAF 成功/取消/关闭失败与清理、FileProvider 只读授权、无可打开应用、Activity 重建、IME 大文本、PDF/图片资源释放 |
| UI 实看 | 企业卡片紧凑、＋面板入口位置、打开后仍为同一聊天草稿；手机/宽屏/矮横屏、大字体、暗色、键盘、长文件名 |
| 真实集成 | 固定 Core/Agent Space 构建；只文件无 MCP；64 MiB 往返摘要；慢流取消、撤销/恢复、双端编辑、旧空间拒绝 |

以下为本次功能实施的门禁，分别记录 JVM、Android 设备和真实服务结果。
实施时先执行受影响的定向 JVM/Compose 测试，再执行完整架构门禁。所有 Gradle 串行：

```powershell
python tools/generate-enterprise-wire.py --check
.\gradlew.bat :app:testDebugUnitTest --tests "<本次相关测试类完整名称>" --no-parallel --max-workers=1
.\gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
.\gradlew.bat connectedDebugAndroidTest --no-parallel --max-workers=1
git diff --check
git diff --cached --check
```

设备门禁前用 adb 核对专用测试设备，显式固定目标；不安装/卸载用户或演示设备数据。
设备测试的 Mock 合同、真 Core 网络、真 Agent Space 文件链路分别记录，不相互替代。
共享 UI 提取同时回归本地 Workspace、Skill 文本编辑和受影响的图片/Markdown 消费者。
跨身份、取消、提交及资源交付是交付前重点审查对象；按仓库约定必要时使用最多两个独立上下文审查代理，
各自范围明确，主代理核实并修复发现后再复验。

## 8. 已知服务端经验与交付边界

Core 记录最新 13 组真实服务联调、240 项前端测试和 Go/tooling 检查通过，但本轮只是阅读记录，未重跑。
其新增回归明确覆盖服务启用但未开通、无 MCP 的文件能力、固定空间请求、BOM 和版本冲突。
Admin 实际浏览器验证了编辑冲突后保留草稿、另存副本及窄屏布局，这些交互可以作为 Android 验收场景。

仍须保留两个不同的失败事实：

- 早前大文件 PUT 中途 `503 workspace_result_unknown` 未确认根因，重跑通过不表示已修复。
- 最新旧测试容器首次文件访问失败，日志定位到 Agent Space VM 启动 `insert run` 的 SQLite 外键错误。
  同镜像独立新卷通过联调，不证明旧环境修复，也不能证明前一问题同源。

因此 Android 在真服务不可达时展示真实错误、取消与核实入口；不靠循环重试写入掩盖远端问题。
集成开始前固定运行容器/镜像/服务端构建，并先做最小只读/文件往返预检。
Core 验收记录中的 `verify:preview-contract` 曾报告消费者漂移，本次未重跑整个跨仓库检查。
Android Client 导出摘要差异已独立核实，合同同步作为 Android 实施第一步执行；
整套 Preview/生产部署是独立交付边界，不由本次 UI 实现自动获得通过结论。

不纳入本轮：远程终端/VM 管理、用户自助 DAV key、回收站/历史版本/公开链接、全盘索引、
自动同步/离线盘、后台持久传输、目录压缩下载、远程文件直接变成聊天附件。
最终报告列明代码和文档变化、各层验证、未验证风险及实际 commit/push 状态。

## 9. 本轮执行记录

开始实施时 Android 为 `6bd1d450e`，Core 为 `35e3ac9cda9d255a404979004400f759285d7939`，两者工作树干净。
按第 6 节六步顺序执行；先证明合同、身份与取消，再接 UI 和系统交付。
保留一个远程应用服务、一个文件 HTTP transport；仅抽取双方确实使用的 UI，不新增远程数据库、后台调度或转发层。
两项独立审查分别核对文件合同和 Session 生命周期；UI 收尾与测试按文件边界委派，主代理核实并统一验证。

实施中保留计划的责任边界，补充了三个必要修正：普通状态刷新只限制新请求，不能把在途请求当成撤权；
已创建的 SAF 文档从回调接收起交给 service，批量文档创建也纳入原请求租约；本地文件/预览错误不应
冒充远程连接失败。受限 Markdown 的子节点必须继承限制，不能在表格中重新开启 HTML。

复核早期 Core 后新增明确兼容边界：缺 workspace 路由或已知旧投影时隐藏可选入口，不作为连接异常，
也不影响既有企业功能。旧投影对应的文件接口尚未验证固定空间，不能仅补 `serviceState` 默认值后开放。
真实业务/认证/代理错误仍展示诊断，后续刷新能够发现升级。当前 Core 新增管理端资源观察接口，
未改变 Android Client 导出和文件协议；Android 不消费这些管理端接口。
独立审查同时修复入口刷新异常未处理、长文件名分享副本超限，并复核未提交编辑草稿的界面重建行为。

联调运行 `npm run device:real` 时，既有开发数据库因 migration version 3 checksum 冲突启动失败。
未重置原数据库；使用 Android `build/remote-core-validation/` 下独立数据库和同一 Core 编译产物继续验收。
Agent Space 容器为 `measix-workspace-integration-20260929`，镜像 ID
`sha256:150b71a5a19d5db42fcee85fa4945670af7cbd65ce69c7869f14fc20131f4216`，
管理/MCP 与 DAV 分别使用本机 18932/18933 端口。初次测试把 DAV 端口设错，出现
`dav_confirmation_mismatch`；保留失败实例，另建正确配置的测试数据库，文件预检返回 200 且 MCP 未发布。
Core 二进制 SHA-256 为 `E6CF6AF1DC80443CCB9B7DB44E6F08B0FE34D737B8B93872D06E7C62F87C99C1`。
联调期间同级 Core 工作树出现了其他任务的资源管理改动；本次未修改它们，验收归属上述固定二进制。
随后复核 Core 提交 `9c985d2`：新增 Admin 资源摘要，Android Client 导出 SHA-256 仍为
`55930f4ddc6aa790537f37797f3f4f8f180d431569c8daf9022295d4b9bf90d7`，与本仓库消费副本一致。

真实恢复测试确认：本次 Core 的 RESTORE 恢复连接后保留 `dav_credential_unavailable`，
管理员执行 SET_DAV 后文件才恢复；不把连接恢复等同于文件可用。模拟器实测还定位到 SAF 返回与
ON_RESUME 状态刷新的竞态，已将新操作改为登记后在锁外等待刷新并复验，保留失败文档清理。

- [x] 合同同步及 v4/v5、工作区样例回归
- [x] 身份、传输、取消及退出/切域行为
- [x] 双入口、共享组件及目录浏览
- [x] 文件管理、批量传输及条件操作
- [x] 预览、编辑、分享及资源清理
- [x] 五语言、参考文档、全量门禁、模拟器实看及真实服务联调

真实服务显式设备测试完整通过：CONFIGURATION_PENDING 且无 Applied/MCP、64 MiB 往返摘要一致、
双编辑者冲突及另存、慢下载取消和关闭、固定空间拒绝、PDF/图片/受限 Markdown 预览，
以及 DISCONNECT、RESTORE、SET_DAV、删除后重新 CREATE 的旧句柄失效。
64 MiB 文件 SHA-256 为 `601fc533f64b11042a9ae821c272064871306a99496652afb5758c8979d8834d`。
另用系统文件选择器实际完成单文件保存、多选上传、同名版本确认、批量目录导出并核对摘要；
实际系统分享面板显示原文件名。聊天快捷入口位于本地文件动作后、MCP 前，往返后未发送草稿保留。
测试只操作隔离数据库中的专属用户；未请求外部模型 Provider，也不声称生产环境已验收。

模拟器为专用 `Codex_Context_V5_API36`（API 36，x86_64）。实际查看手机、宽屏、大字体/暗色、
矮横屏、长文件名、原生编辑器输入法、冲突正文和 UNKNOWN 核实界面。横屏发现筛选改变后列表
沿用旧项目位置，已在筛选/排序/目录变化时回到列表起点；短屏测试通过列表滚动定位尚未组合的项目。
系统保存返回时的状态刷新竞态由服务行为测试和真实 SAF 成功操作共同复验。
最终暂存区复核补充迟到错误隔离：旧请求的 SAF 清理可能等待到空间已替换，HTTP 失败发布前必须
再次核对句柄、原身份、空间与绑定版本；新增行为测试分别覆盖迟到 503 和空间不匹配错误。

首次完整设备门禁中，既有 `RichTextHostAndroidTest` 的链接测试在 ActivityScenario 收尾等待销毁时
超时。日志显示 AndroidX EmptyActivity 启动耗时 68.967 秒，同期 system_server 收集线程栈超时；
未发现链接断言或本次 Markdown 限制导致的销毁阻塞。保留原始失败报告；后续复验不能证明该系统延迟
根因已修复，也不通过放宽断言或跳过测试获得通过。
第二轮完整运行中该富文本用例通过，但既有图片预览用例等待系统 PixelCopy 超时；随后主动停止
已失败的运行，因此后续 instrumentation 的进程结束记录不代表产品崩溃。记录当时模拟器约 2.5 GiB
内存及换页活动；后续改以临时 `-memory 4096` 冷启动同一专用 AVD，释放构建守护进程后复验。
这些环境调整不修改测试断言，不证明系统延迟或 PixelCopy 超时的根因已经解决。

最终 JVM 汇总为 2,992 项、0 失败、0 错误、12 项既有环境条件跳过；应用模块 2,423 项全通过。
远程服务定向测试 25 项通过，旧 Core/文件 HTTP 合同 14 项通过。手机远程文件与受限 Markdown
9 项通过，宽屏 8 项、大字体/暗色 8 项、矮横屏 8 项通过；重启专用模拟器后，富文本宿主 4 项
独立复验通过。参考文档检查覆盖 198 个链接（其中 170 个本地链接），无错误；合同生成检查通过。
4 GiB 冷启动后图片预览与方向图标 9 项独立复验通过；完整 `connectedDebugAndroidTest` 成功，
报告合计 365 项（应用 335、speech 18、workspace 12），346 通过、19 条件跳过、0 失败、0 错误。
跳过项包含未提供显式输入的外部服务/设备场景；本次远程工作区 live 场景已单独完整执行通过。
全量 `test assembleDebug lintDebug assembleRelease` 通过，lint 为 0 错误；门禁使用缓存依赖的
`--offline` 模式，并始终附带 `--no-parallel --max-workers=1`。未修改版本号或 changelog。

## 10. 真实企业空间的文件界面复审

本轮从 Android `6d9fcaf1c` 开始，连接用户已启动的 `http://192.168.1.5:9100/`。
使用独立验证用户开通远程工作区，经模拟器界面粘贴接入材料、确认企业地址并加入，未发送模型请求。
未修改 Core、Agent Space 或已有用户的数据。Core 工作树为 `c63e6af`，这只是源码核对基线，
不替代运行包身份；Client 合同摘要仍与 Android 消费副本一致。

界面审查按常用网盘的浏览、选择、操作、传输和预览路径展开，保持原 application owner 和短生命周期：

- 文件列表使用共享行的紧凑样式，按类型显示图标，名称最多两行，日期按设备语言与时区压缩显示。
  路径、筛选入口和新增动作共用一行；筛选按需进入标题栏输入，收起键盘保留结果，进入目录后清除。返回先退出选择或筛选，
  然后返回父目录。选择数量与批量动作进入标题栏，排序菜单标记当前项。
- 列表摘要只显示可见项目数和目录 API 实际提供的可用/已用容量。CPU、内存、VM 采样及配置容量
  仅在 Core Admin 接口提供，本轮不把管理端权限或凭据放入客户端，也不从文件大小推算配额。
- 名称输入自动聚焦并选中默认值；目录选择保留原名称，不把源目录展示为可进入的目标。
  超长组合路径在确认时报告诊断，保留对话框，不在组合或命令登记前崩溃。
  确认清单可滚动、可复制，空正文不占空间；覆盖目标时间也按设备语言和时区显示。
- 进度显示正在执行的文件和当前批次位置，已知总字节才展示比例；传输完成后等待服务端结果。
  完成汇总占一行，逐项状态和可复制诊断进入详情。新批次保留旧 UNKNOWN 核验入口，
  清除完成结果也不能删除未核实记录。取消保留尚未执行项目，不自动重放修改。
- 文本编辑与保存进入标题栏，另存、重新读取、分享及下载进入菜单；读取失败不冒充格式不支持。
  显式重试先刷新原空间状态再复验原句柄；失败/不支持预览详情可滚动，使横屏恢复动作可达。
  图片信息区显示加载、原诊断及重试，并按短屏高度滚动。修改时间来自原文件元信息。

真实系统选择器返回两次触发 `SocketException`/EOF，状态准入正确拦截下载并清理本次创建文档，
但错误详情只显示准入拒绝。已补充原状态查询诊断，并把 GET 的连接恢复与写请求分开：
仅 GET 在响应头之前恢复可恢复连接失败，正文中断不重放；PUT/POST 继续关闭重试、跳转和认证器重发。
真实重试后单文件保存成功，22 字节与远端一致。行为测试分别验证旧连接恢复、正文中断单次读取、
未确认写入不重放，以及失败状态下的 SAF 清理。

系统分享面板保留原文件名。外部打开复审确认 FileProvider MIME 按实际私有文件名判断，
只有 displayName 不能改变 MIME；分享副本改为短随机名加安全短扩展名，Intent 使用 URI 实际 MIME。
仍保留长原始展示名、原租约、只读授权、交付失败清理和已交付副本的过期策略。
修改后系统 HTML Viewer 实际显示正文。矮横屏、大字体和中文输入法实看又发现第二行筛选框被键盘遮挡，
已将输入放入标题栏，内容区域避让键盘；不增加独立页面、查询 owner 或索引。

实际通过界面检查企业摘要、聊天快捷入口、空目录、根目录、多级路径、筛选/排序/隐藏、选择与批量菜单、
名称输入、目标目录、文件与工作区详情、删除确认、覆盖确认及逐项结果。
创建目录和文本、编辑保存、复制/重命名/移动/删除均在专属工作区完成；同名上传取消后显示未开始且远端内容保持。
文本/JSON、Markdown 表格与相对图片、图片信息、PDF、不支持格式页面、冲突草稿与另存均已实际查看。
系统单文件保存、多选上传、目录批量导出、分享和外部打开已完成；两份上传/导出分别为 25、26 字节，内容与摘要一致。
另存的冲突草稿为 30 字节，与提交正文一致。实看覆盖英文/中文、普通手机、360dp 窄屏、1.5 倍字体、暗色及矮横屏。

本轮 JVM 报告为 2,995 项、0 失败、0 错误、12 项既有环境条件跳过；应用模块 2,426 项全通过。
远程文件 HTTP/旧 Core 合同 17 项、服务行为 25 项通过；远程页面与图片预览定向设备测试 20 项通过，
360dp、1.5 倍字体、暗色横屏下的远程页面 12 项也全通过。
完整 `connectedDebugAndroidTest` 成功，报告合计 370 项（应用 340、speech 18、workspace 12），
351 通过、19 条件跳过、0 失败、0 错误。数量以 XML 报告为准，跳过不作为成功的外部服务验证。
本轮未改变条件跳过或测试超时；设置页测试期间观察到系统 Activity 启动/销毁延迟，最终用例通过，
不据此声称环境延迟的根因已解决。首次完整 lint 曾出现 `Unexpected owner function: null` 内部异常，
同期源码有编辑；冻结源码后完整门禁通过，未禁用 lint 或压制诊断，异常根因未确认。
最后两处确认弹窗调整后，再次完整执行 `test assembleDebug lintDebug assembleRelease` 成功，
lint 为 0 错误；所有 Gradle 门禁使用 `--offline --no-parallel --max-workers=1`。
合同生成检查通过，参考文档 198 个链接（170 个本地链接）无错误，五语言键与格式参数一致，最终 diff 和编码检查通过。
两名独立子代理完成合同及生命周期复核，发现的问题均已修正并复验。上一节数量属于前一轮交付。

收尾冷启动专用模拟器、重新接入同一验证用户后，Android 与 Admin 文件 GET 均返回
`503 file_service_unavailable`。既有 Agent Space 集成容器为 unhealthy，`/healthz` 返回 503；
日志持续报告 SQLite code 11：`database disk image is malformed`，资源检查返回 inspection_failed。
Android 实际显示可展开的原始 503 诊断，返回企业摘要后显示 READ_FAILED 的重试核实提示，未伪装成空目录。
此前真实 UI、SAF、CRUD、预览和冲突另存的成功证据仍成立，但当前环境恢复后的文件成功路径无法再次复验。
本轮未修复或重建外部数据库，未修改或停止 Core；该外部故障与通过的 Android 自动门禁分别记录。

## 11. 宽窄窗口与紧凑呈现复审

本轮从 `b5289290e` 再次沿网盘文件管理路径审视实际 Compose 界面，修改仍仅限 Android。
可用宽度至少 600dp、普通字体时，列表采用名称、修改时间、大小列，常规行高缩为 48dp，
保留 48dp 动作触控区域；选择态预留动作位置，列与标题一致。窄屏或大字体采用上下排列，
长名称仍可两行显示，未知元信息不显示为零。新增使用标准图标，短屏筛选键盘占满正文时不压缩新增按钮。

矮横屏实看发现长确认标题挤压正文、名称确认被键盘遮住。删除、停止及重新读取改用短标题，
原说明保留在可滚动正文。名称弹窗按实际可用高度排列输入和按钮，布局切换保留同一输入框、
焦点与选择，Done 只关闭输入法，确认才提交。校验失败保留名称，在紧凑与常规布局都提供错误原因。
独立子代理发现并复核了弹窗焦点 owner 和持续矮窗口错误提示问题，均沿同一弹窗修正。

亮色图片上原白色工具难以辨认，控制与页数增加深色背景，信息面板限制为 560dp、保留短屏滚动，
展开信息时隐藏底部控制。PDF 支持双击、捏合缩放和平移；翻页重新建立缩放状态，取消原页面 scope，
不改变有界渲染上限。损坏 PDF 不再停留在加载或出现 1/0；释放本次副本后显示原异常和刷新，
刷新先查询原状态、核对原句柄再读取。新增设备测试覆盖损坏 PDF、恢复读取和两次副本释放。

本轮成功界面实看使用专用 API 36 模拟器、真实页面/VM/Android 渲染及隔离 service fixture，
不把 fixture 当作真实 Core 成功联调。查看覆盖普通手机、686dp 平板、914dp 宽屏、800×360dp 横屏、
1.5 倍字体和暗色，包含列表/选择/菜单、名称与目标目录、确认、逐项结果、Markdown 相对图片、
图片信息、PDF 缩放翻页及损坏恢复。当前 Agent Space 的既有 SQLite 故障仍使健康检查及 Core 文件 GET 返回 503，
因此本轮真实服务的文件成功路径不能重新验证；没有修改或重建外部数据库。

最终窄屏原生操作确认：非法名称保留输入并显示原因，修正后显式确认才创建；实际点击输入法 Done 仅关闭键盘。
重命名自动聚焦并选中原名，取消不写入。宽屏选择后时间与大小列保持原位置，隐藏和排序后选择集合仍对应原文件。
临时交互 fixture 入口已移除；远程页面和图片预览定向设备测试 21 项通过，360dp 高度、1.5 倍字体、暗色横屏的
远程页面 13 项通过，包括新增损坏 PDF 恢复及名称校验零请求。两名独立子代理完成最终只读复核。

首次全量设备运行在既有 `ConversationContextAndroidTest` 的 Activity 收尾阶段等待 DESTROYED 超时，
随后整个 instrumentation 被 `System has crashed` 中止。日志记录 `HardwareRenderer$DestroyContextRunnable`
清理超时及 System UI 的 `DeadSystemException`，原始报告已保存。改用临时 SwiftShader 软件渲染冷启动同一专用 AVD，
不修改测试断言、超时或条件跳过；后续复验通过也不证明此次 Android 系统故障的根因已修复。

受影响的上下文和工具卡测试类独立复验 9 项通过。第二次全量设备运行完成应用 341 项，
既有 `EditorDraftAndroidTest.newSkillFileBodyStaysOutOfBundle` 在关闭输入法、写入大正文之前等待
Compose 空闲超时，同期记录输入法动画超时；报告保留。保持同一源码和设备配置独立运行该类，
10 项全部通过，未修改该类或技能编辑器，也不据此声称输入法等待超时的根因已解决。

同一源码及设备配置下的最终全量 `connectedDebugAndroidTest` 成功，XML 合计 371 项：
应用 341、speech 18、workspace 12；352 通过、19 条件跳过、0 失败、0 错误。
`test assembleDebug lintDebug assembleRelease` 也完整成功，JVM 为 2,995 项、0 失败、0 错误、
12 条件跳过，应用 2,426 项全通过；lint 无错误，既有警告保留。所有 Gradle 验证均串行执行，
使用 `--offline --no-daemon --no-parallel --max-workers=1`，未调整测试断言、超时或跳过条件。

最终 APK 再次通过界面接入同一专属验证用户与 9100 企业空间，实际查看普通手机、360dp 窄屏及
914dp 宽屏下的卡片、文件页、展开诊断和显式刷新；真实文件 GET 持续返回 `503 file_service_unavailable`，
错误未显示为空目录。读取状态失败时卡片保留重试核实提示；窗口重建使原管理句柄失效时，页面要求返回重开，
没有把旧授权移交给新会话。成功文件路径的本轮证据仍仅来自隔离 fixture，外部故障恢复后的成功联调未复验。
接入材料与剪贴板已清理；合同生成、参考文档链接、编码及最终 diff 检查通过，Core 工作树保持未修改。

## 12. 全部任务提交复审

从 `b49942d45` 到 `cd2ade68a` 共五个提交、78 个文件，按代码、契约、测试、语言资源及文档整理一起审查。
两名独立子代理分别复核 HTTP/路径边界，以及 Session、取消、资源交付与退出链；主代理复核界面及共享渲染组件。
不修改 Core，不扩大到版本或发布流程。

原源码新增测试复现两类问题：Markdown 的 `%20` 被当作文件名字面量，非法百分编码未拒绝；
批次结束先开放动作再挂起校验，使旧目录可选，刷新后失效的选择路径继续计入数量。
修复仅在 Markdown 引用处严格解码一次，HTTP 逻辑路径仍由 URL builder 单次编码；安全别名、非法 UTF-8 和越根引用继续拒绝。
批次结束在同一次状态发布中清空旧目录，再由原刷新入口复验权限；取消不自动刷新，不清理 UNKNOWN 或编辑草稿。
新目录对账选择路径，空选择关闭批量动作。

另存名称重组曾有疑点，但原源码的设备测试证实非法名称和同源名称均保留输入与原因，拒绝时不发写请求，
修正后单次提交并保留失败草稿，因此未修改该实现，只补充回归覆盖。复现失败报告保留，与修复后的验证分开记录。
只读 GET 的文档补充 OkHttp 对 408 和 `Retry-After: 0` 的 503 的恢复行为，不改变写入重放策略。

验证结果：

- 原源码基线：HTTP 类 19 项中新增两项失败；新增设备场景三项中两项失败、另存名称保护通过。
  基线报告单独保留，修复后不修改断言或弱化验证。
- 定向 JVM 60 项、设备界面及预览 25 项全部通过；核对本轮模拟器产生的文件列表、详情、编辑冲突和撤权截图。
- `test assembleDebug lintDebug assembleRelease` 通过；JVM XML 共 2997 项，2985 通过、12 跳过，无失败或错误。
- `connectedDebugAndroidTest` 通过；设备 XML 共 374 项，355 通过、19 按环境条件跳过，无失败或错误。
  Provider、PRoot 镜像及显式 live 接入场景仍按各自参数门禁，不以 fixture 代替真实依赖验证。
- 上述 Gradle 均使用 `--offline --no-daemon --no-parallel --max-workers=1` 串行执行；编译与 lint 期间保持源码不变。
- 合同生成检查、五语言的 77 个远程工作区键和格式参数、参考文档链接及锚点检查通过。
  本轮真实 Core 的文件读取仍为 `503 file_service_unavailable`；成功文件操作的复验来自隔离设备 fixture，
  外部服务恢复后的成功联调仍未复验。Core 工作树未修改。

完整门禁后重新安装 Debug，在专用模拟器通过界面连接 `http://192.168.1.5:9100` 的企业空间，
打开管理页、展开 503 原因、执行刷新并返回企业页。管理页保留诊断和刷新，企业卡片显示读取失败及重试核实，
没有假装为空目录或恢复可用；实际像素截图已核对。临时接入材料、剪贴板及设备上的 UI dump 已清理。

## 13. 服务恢复后的联调与聊天返回恢复

本轮从 `17d363e21` 继续，修改仅限 Android。9100 Core 运行包的 Go 构建信息确认
`vcs.revision=c63e6af89e5873db5cd091b4fdf8919ed2e792e0`、`vcs.modified=false`，
Agent Space 集成容器的新镜像 `sha256:d0d26b41dcc4a55c5cedcbd527f3bc40a23b3379baa1ca4f14bb1fcc3761fa6f`
健康，真实管理端文件 GET 已恢复。使用独立验证用户，不修改全局发布、Core 源码或外部数据库。
当前 Core 已发布 MCP，验证输入明确断言 `expectedMcpAvailable=true`，不沿用历史 files-only 环境假设。

模拟器原错误页显示 `enterprise_data_access_unavailable`，重试仍失败，显式新建聊天成功。
同期 Session manifest 是 READY，Applied 存在，授权期限尚余七天；因此该 reason 不能解释为当前企业空间过期。
旧错误页面的请求身份未在切换页面前捕获，不能事后声称已读到其具体 Session。
沿实际调用链确认：聊天请求保存原访问身份；首次接入企业或更换 Session 后，原个人/旧 Session 请求仍应拒绝。
空间页的进入基线原用普通 `remember`，重建后错误捕获新选择，返回栈却保留旧请求，因而回到不可访问的聊天。

原生产源码的三个设备基线场景中，更换 Session 和切域往返后重建返回失败，未切换选择时返回通过。
修复保存原选择身份、revision 及捕获标记；工具栏与系统返回发现变化时清理旧栈，进入合法的 Startup 入口。
不改写旧请求，不放宽 Session 准入，不把草稿或附件自动移交新身份。另补首次个人聊天接入企业后重建返回场景。
加载失败及启动失败共用空间出口、短屏滚动和可展开复制的诊断，完整异常进入日志；文案区分原页面访问变化与当前空间状态。
独立子代理分别复核返回恢复和真实文件验证边界，发现的问题均由主代理核实修正。

定向 JVM 94 项通过。首次设备定向 45 项中授权、恢复与导航全部通过，一项旧截图保存因同名自动编号耗尽失败；
保留日志并将验证截图改为唯一文件名，该项再次通过，未删除行为断言或放宽超时。

服务恢复后真实链路通过：64 MiB 摘要往返、版本条件写入、双编辑者冲突与另存、覆盖竞态、
取消及切域关闭、原生图片/PDF/Markdown 相对图片、实际目录对账，以及断开、恢复、重新开启文件资格、
替换空间后旧句柄拒绝读写。当前发布环境使用 `configurationPublished=true` 显式同步并断言 READY/Applied；
不能把启动后台同步后 READY 的环境记作 Pending 或无 Applied。未发布环境的独立门禁保留。
实际 UI 接入同一专属用户，完成文件复制、移动、删除、筛选、冲突另存、原生 SAF 多选上传、
重复名称取消与确认覆盖、文件及目录下载；Core 内容和设备输出摘要一致。PDF 故障保留完整原因，
修复同一专属文件后显式刷新恢复原生预览；外部打开实际读取 FileProvider 副本。

继续实际窄屏检查时发现第二个恢复缺陷：只改变窗口密度、进程和 Session 均未更换，文件页却报告访问变化。
`Screen.RemoteWorkspace` 的临时 selection 不参加导航序列化；Nav3 默认 contentKey 含 key 的字符串，
重建后 selection 为 null 使 contentKey 改变，原 VM/句柄/草稿丢失。五个新增原生 Activity 恢复基线中四项失败，
冷恢复要求重开的场景通过。修复用已有页面 UUID 固定 `clazzContentKey`，页面刷新和状态匹配读取 VM 原 selection。
不从当前 Session 补旧授权；新进程仍要求显式重开，撤权和 UNKNOWN 原协议保持。

真实 Markdown 表格还发现中文硬编码标题及无处理者的下载动作。复现测试确认按钮存在；
将导出回调表达为可缺省能力，远程阅读器不给导出回调，表格与代码块保留复制，聊天原文档选择器继续负责导出。
表格标题和动作通过 locale-tui 同步五语言资源，没有引入第二套导出流程。

修复后导航、限制 Markdown、聊天富文本导出和文件页面定向设备 27 项通过。
实际 360dp 窄屏 → 914dp 宽屏切换时，原文件句柄和未提交文本继续有效；选择前后宽屏列对齐保持。
800×360dp 矮横屏检查创建、非法名称纠正、预览与图片信息面板；两页真实 PDF 翻页与缩放重置、
编辑器重新读取及丢弃确认、SVG 不支持预览时的下载/外部打开出口均实际核对。
1.5 倍字体和暗色界面使用元信息堆叠，未挤压成不可读的宽屏列。
名称拒绝原只呈现内部异常，新增两条五语言可行动提示，原 reason 和完整诊断仍可展开；
预期校验保留输入且不写远端，其他异常继续完整报告，取消继续向上传播。
独立审查发现聊天表格导出测试仍按英文 Download 查找，已同步为资源值，保留实际导出行为断言。

最终源码的 `test assembleDebug lintDebug assembleRelease` 全部成功，JVM XML 共 3,000 项：
2,988 通过、12 条件跳过、0 失败/错误；lint 0 错误。最终全量 `connectedDebugAndroidTest` 成功，
应用 357、speech 18、workspace 12，共 387 项：368 通过、19 条件跳过、0 失败/错误。
所有 Gradle 均串行使用 `--offline --no-daemon --no-parallel --max-workers=1`，构建期间冻结源码，
设备门禁使用专用 API 36 模拟器，真实服务门禁随后单独运行。

早先的全量设备检查曾在既有 `ChatContextFlowAndroidTest` 等待第二请求 ordinal 时超时，
故障截图停在 ContextDetailsSheet Loading，独立查询当时返回两个请求和首个已观测 ordinal。
保留首次 XML、截图和查询证据；没有确认 lease 退出或淘汰，也没有修改该领域实现、断言、超时或跳过条件。
同源码同设备独立该项、随后 381 项全量及本次最终 387 项全量均通过，不能据此声称首次等待超时的根因已修复。
合同生成、七条变更资源的五语言键和格式参数、参考文档链接/锚点、UTF-8 无 BOM 与 CRLF 检查通过。
Core 工作树保持干净，Agent Space 固定镜像再次确认运行且健康。

最终 APK 的真实 `RemoteWorkspaceLiveAndroidTest` 单独运行成功（76.817 秒），四步主机生命周期均完成；
67,108,864 字节摘要为 `601fc533f64b11042a9ae821c272064871306a99496652afb5758c8979d8834d`，
其余条件写入、回读、覆盖竞态、取消/切域关闭、原生预览与旧空间读写拒绝均保留结构化证据。
当前环境已发布配置、MCP 可用，断言 READY/Applied；不宣称实际运行了未发布配置的 live 分支。

门禁后通过普通界面接入同一独立用户，真实确认 9100 企业来源并同步配置。首次个人→企业接入后，
后台回收确认 PID 4779 退出，恢复为 PID 5157，系统返回进入有效企业聊天，未回到旧个人请求。
最终窄屏/宽屏重建的远程列表正常，返回聊天仍保留未发送的 `Final draft preserved`。
再实际回收远程页进程（PID 5157 退出、5700 恢复），冷恢复按契约要求显式重开，未恢复原句柄或草稿；
返回后原企业仍可用，重开读到真实文件。专用模拟器最终恢复普通手机、字体 1.0 与亮色，停在可用文件列表。
临时接入材料、剪贴板与设备 UI dump 已清理。两名独立子代理复核最终导航授权、UNKNOWN/SAF、
取消和导出职责；发现的资源测试定位问题已修正，最终未发现未修复的可证实变更问题。
