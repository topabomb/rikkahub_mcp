# Android 远程工作区界面执行计划

本文是待实施计划，不代表 Android 已具备下述能力。制定日期：2026-09-29。
本次交付仅审查、修订并提交这份计划，不修改功能代码、平台合同副本或版本号。
下文“首版”“实施”“验收”均指后续 Android 功能实施，不是本次文档审查已完成的能力。

## 1. 已确认范围与实施基线

用户已确认：企业空间“企业连接”后增加紧凑摘要卡片；聊天输入框“＋”快捷面板增加远程工作区入口，
仅可用时显示；两处进入同一个独立管理界面；复用已有 UI，确有必要时调整通用组件归属。
管理界面以远程文件的浏览、整理、上传下载、预览、编辑、分享为核心。

核对的 Core 基线为 `35e3ac9cda9d255a404979004400f759285d7939`：
`feat: complete workspace file contract and admin text editing`。复核结束时 Core 工作树干净。
它包含此前 `d7f310a` 的 Hub/Relay/Admin 集成及后续文件客户端修订。
Android 调研基线为 `0e1b10e9b`，开始调研时工作树干净；尚无远程工作区客户端和界面。
本次复审核对两仓库 HEAD 未变；Android 仅本文未跟踪，Core 工作树干净。
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

复用现有 composition 内存正文生命周期；未保存返回、关闭、重读确认，以及另存、保存并关闭，
是远程页面需要新增的交互，不是 `FileTextEditor` 已有能力。普通后台保留内存正文，
Activity 重建/进程死亡后重新读取，不承诺未保存草稿恢复。正文不放 Bundle、SavedStateHandle、
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
本次已确认 Android Client sourceHash 与 Core 当前导出不同；本轮不提前修改该副本。
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

以下为后续功能实施的门禁，本次纯文档审查不运行 Android 构建或设备测试。
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
当前 Android Client 导出摘要差异已独立核实，合同同步是后续 Android 实施第一步；
整套 Preview/生产部署是独立交付边界，不由本次 UI 实现自动获得通过结论。

不纳入本轮：远程终端/VM 管理、用户自助 DAV key、回收站/历史版本/公开链接、全盘索引、
自动同步/离线盘、后台持久传输、目录压缩下载、远程文件直接变成聊天附件。
最终报告列明代码和文档变化、各层验证、未验证风险及实际 commit/push 状态。
