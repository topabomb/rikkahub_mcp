# 界面架构与自适应布局

本文维护页面状态、导航授权、用户交互与窗口布局的职责。消息内容如何分组和渲染见
[消息渲染管线](message-rendering-pipeline.md)；配置解析、执行与持久化分别见
[配置架构](android-configuration-architecture.md)、[Turn/Step 执行链路](turn-step-execution.md)。

## 1. 页面状态与授权

`RouteActivity` 在 `MeasixTheme` 下承载 Navigation 3 的 `NavDisplay`；`Navigator` 管理返回栈，
`Screen` 定义路由参数，entry decorators 管理页面状态和 ViewModel 生命周期。具体路由以
`Screen` 与 `RouteActivity` 的注册为准，不在文档复制清单。

页面消费 application/query UiModel 并提交类型化命令，不持有 Runtime Job，不从渲染列表推断持久化状态。
`ConversationPresentation` 提供消息、执行阶段、工具定位和附件预览；工具结果是否存在不能代替执行阶段或访问权限。
Koin 只负责装配依赖，不作为 UI 的服务定位器。`LocalSettings` 来自 `SettingsStore.userSettings`，
提供个人配置与通用显示偏好；企业资源和会话使用配置来自按域查询投影。

页面打开时捕获的 `RealmSelection`、`ConversationViewLease` 或 Portal 文档身份贯穿异步操作。
切域、退出、授权到期或关闭页面会撤销旧投影和操作；返回同一空间不恢复旧权限。
`selectionRevision` 区分实际发生过的域切换，即使 StateFlow 合并中间状态也不能恢复旧页面。
凭据、WebView、lease 和大段正文不进入导航或 Activity saved state。Lazy 列表使用稳定业务 ID；
类型化引用仅在 Saveable key 边界转成 Bundle 支持的标量，不改变业务命令类型。

### 聊天导航与页面恢复

`ConversationOpenRequest.NewDraft` 固定会话 ID、原域与助手；显式 `OpenExisting` 只打开已有根会话。
历史、搜索、收藏和通知使用已有会话请求，分享和新建显式创建 Draft。失效的历史 ID 不转成新聊天。
冷启动等待应用恢复后，由 `ConversationApplicationService.initialRequest` 选择最近会话或合法的新聊天入口；
缺少有效企业默认助手时进入空间页，不自动选目录首项或改写失效引用。
最近 ID 在打开前核对原 scope 的根 header；缺失回到合法启动入口，读取异常、外域和 Child 不解释为不存在。
只有这一隐式启动入口携带 `StartupConversationResume`，用于核对后、真正打开前发生删除时接续原助手；
接续仍复验原选择 revision 与 Session，显式历史、搜索和通知的缺失 ID 不自动新建。

抽屉删除由 `ChatVM` 执行，删除当前聊天前记录交接状态，`ChatPage` 在 Missing 等提前返回之前消费单次结果。
成功进入同域同助手 Draft，不打开 Starter 选择、不自动发送；原助手不可用时说明原因并进入空间选择。
删除其他会话不清输入。`HistoryVM` 持有删除结果与 Undo retention，历史页保持当前位置；
`Navigator.replaceDeletedConversation` 只替换原 ID、scope 和 Session 匹配的聊天返回入口。
应用删除回执区分已提交的删除与最近引用维护失败；撤销恢复原树，不覆盖或自动打开新草稿。
最近引用维护失败单独说明删除已完成，保留可复制诊断及继续新建入口；正常删除直接接续。
历史页切域时释放原撤销 retention，不将旧空间的撤销或命令失败展示在新空间。
撤销点击立即交接原 token，忙碌时等待当前命令完成后串行恢复；新的删除不会关闭已接纳的旧 token。
等待结束仍核对原选择，切域、页面关闭或取消会释放未恢复的保留资源。

`ChatVM` 取得页面 lease 后才订阅聊天、收藏、错误和附件预览，并创建附件导入作用域。
页面访问观察消费 Session owner 原子读取的 `RealmSelection`；独立的状态和切换序号只作为唤醒信号，
不能以迟到的到期值决定关闭仍有效的页面。真正的切域、退出或撤权仍立即撤销旧 lease。
Missing 说明会话已删除或不存在，只提供显式新建和空间入口；Failed 另外保留重试。
原页面仍有有效授权时，读取失败保留原异常、输入和附件作用域，
显式重试只重新订阅读取；初始化或授权失败才释放旧作用域并复验原请求身份，不能自动改绑新登录。
页面等待包含配置的完整联合投影后才显示 Ready，正文快照先到时保持 Loading，不能误报助手或模型资源已不存在。
最近聊天偏好写入失败单独显示原诊断，不关闭聊天、不清输入，也不自动重试；重新打开聊天可再次记录。
授权变化说明只描述原页面无法继续访问，不据此认定当前企业空间失效。加载失败及启动请求失败保留完整日志，
`ConversationUnavailable` 提供可展开、选择及复制的原异常诊断，短屏可滚动。
未发送 Draft 不另行持久化。

分享输入由 ViewModel 串行消费一次：文本先填入空输入框，再异步追加附件，保留等待期间的新输入。
旋转、普通读取重试和恢复到已持久聊天不重复导入，也不自动发送；失败保留原诊断，用户可重新选择文件。
提交时如何清理输入见“输入、滚动与运行反馈”。

聊天的记忆计数借用原 lease，Workspace 目录由单一局部查询结果供 readiness 和加号面板共用。
加载、读取失败、明确不可用与成功空数据保持可区分；成功目录缺少已有 Workspace 绑定时明确显示绑定不可用。
收藏、MCP、记忆和 Workspace 的显式读取重试使用同一只读 revision，重新建立原能力订阅，不依赖瞬时 Loading 被界面观察到，
不重新授权或自动重放写入。辅助故障不关闭正常正文、输入和附件作用域。
MCP 设置页沿 `McpQueryService.catalog` 呈现读取失败，`retryCatalog` 复验原空间选择后只重订阅查询，
不调用连接刷新。共享定义仍可编辑，查询失败时不从个人运行状态推断当前企业准入。

### 诊断与读取重试

命令反馈绑定发起页面与原 lease，抽屉行命令另保留原行目标；离页、切域或撤权后的迟到失败只留日志。
同一诊断去重，显式关闭后新失败仍可显示。`ChatVM.errors` 在 Ready 时按当前选中消息筛选终态错误，
切分支或截断不残留旧卡；Loading/Failed 不用空树清理诊断，无消息来源的命令和读取错误不参与分支筛选。

`ChatError.summary` 提供用户说明，`detail` 保留异常类型、原 message、cause 与 suppressed；
`logDiagnosticFailure` 对完整诊断脱敏后按安全 Unicode 边界分块记录，避免 Android 日志截断。
Core Problem 保留 HTTP status、code、requestId、forwarded 与预算等实际字段；先脱敏解析后的字符串值，
再序列化为有效 JSON，不以翻译摘要替代技术详情。取消不展示为失败。

Provider 故障的设置入口依据失败消息原 `modelId`：用户模型指向个人 Provider，企业模型提示检查企业服务，
未知历史来源不猜测。只有 `EnterpriseSynchronizationService.prepareExecution` 抛出的
`EnterprisePreparationException` 明确表示企业操作尚未开始；其他网络错误不套用该提示。

`ErrorDetails` 是统一的可选择、复制、滚动诊断弹层，页面通过 `DiagnosticDisclosure` 打开。
错误卡保留短摘要，完整诊断不挤占输入区或另建页面内滚动区；弹层中的标题、关闭和复制动作始终可达。
阅读详情暂停短期卡的自动关闭，复制不关闭原操作或清掉草稿。语音诊断由 `SpeechApplicationService`
发布，`RouteActivity` 统一呈现；各录音页面不重复弹出。

读取失败与授权失效分开处理：原能力有效时仅重订阅读取，保留输入和原异常；失效时关闭访问，不能凭 ID 重建授权。
上下文和 Opening 的初次查询及分项正文都可显式重试，重试不触发更新或移除；Opening 读写共用忙碌状态。
详情组件在所属 composition 读取当前值，按需加载当前分区，不因父级重组重新订阅，不让局部目录故障阻断无关内容。
文件保存、附件导入及使用 `ErrorDetails` 的导出路径在失败时保留原选择或正文，查看诊断不自动重放操作。
不同渲染器的导出反馈能力见 [消息渲染管线](message-rendering-pipeline.md)，不能据统一弹层推断所有导出都有完整诊断。

子助手详情借用父聊天的 lease；内容预览借用原渲染来源。导航仅保存稳定条目身份，借用能力为 transient。
详情的读取失败保留原异常和可复制诊断，只重订阅原借用能力；缺链、删除和确认撤权仍显示不可用。
保存恢复后必须从原页面重新打开，不能凭会话 ID、runId 或缓存 ID 重建授权。

目录必须区分加载、可用、预期不可用和非预期失败。历史、文件夹、收藏的读取失败清除旧列表并保留原异常，
不能伪装成空目录；页面提供可复制诊断和重试。单次订阅失败不终止外层空间观察，切域后重新读取并清除旧诊断。
取消继续传播，不展示为业务失败。资源来源与不可用原因使用文字说明，可点击图标提供无障碍名称。

收藏滑动删除由页面协程执行；撤销失败保留原域 `RestoreToken` 供重试，离页后失效。
手势位移只在卡片 composition 内保存，撤销恢复同一 ID 时不能恢复“已删除”的位移。
列表次动作不拥有业务事实；排序、删除和确认仍通过原命令，过滤期间禁用排序。

## 2. 配置与企业入口

已有会话的助手来自 `ConversationUiModel.snapshot.header.assistantId`。
`ConversationConfigurationUiModel` 将该助手的标题、模型、背景及能力选择一并投影，
定义删除或撤权时保留历史与不可用原因，不回退全局助手。抽屉目录、文件夹筛选和移动助手操作携带原域目标；
切域或换助手先丢弃旧筛选，异步完成不能改变新页面。

聊天配置通过 `ConversationAssistantTarget` 编辑当前域的使用偏好，字段命令只修改最新值中的目标字段。
企业固定定义只读；共享用户定义从企业上下文编辑时明确提示影响，并复用原编辑器。
未选候选只提供只读详情，不构造虚假会话目标。模型的助手默认、空间默认、指定模型三态直接消费类型化投影，
失效显式引用保留诊断，不能从最终显示值反推或静默替换。具体解析规则见
[助手配置](assistant-configuration.md)。

`ModelListSheet` 在聊天页稳定根部只组合一次，与助手详情和引导入口共用 `ModelListState`，
不随 IME 显隐销毁。选择提交成功才关闭，提交中拒绝重复选择，失败保留原选择；目录选择身份变化关闭旧弹层。
配置反馈在发起操作的弹层显示，嵌套弹层隐藏父反馈而不销毁父内容。
运行中修改影响请求的配置后显示“下次发送生效”；头像、背景等即时显示修改不显示该提示。

### Starter 与空态

个人空态提供现有能力配置入口；企业空态突出助手、模型、适用 Starter 和已绑定 Workspace，其他能力从助手详情查看。
Starter 在 Draft 中先绑定开场再追加提示词，保留原文字和附件，不自动发送；Ready 中只追加提示词。
再次点击已选项打开详情，刷新开场不重复填充提示词。目录只提供轻量摘要，正文默认折叠并按需查询；
已保存开场的查看入口不因当前目录删除该项而消失。开场持久化及首发校验见
[配置架构](android-configuration-architecture.md)。

### 企业空间与工作台

`EnterprisePage` 经 `EnterpriseApplicationService` 提供接入、同步、切换、退出与本机重置。
聊天的空间标记不兼任切换动作；连接异常入口消费 Session 归属的同步投影并导航到空间页。
空间页进入时保存原 `RealmSelection` 的身份与 revision 作为返回基线，界面重建不重新捕获。
工具栏或系统返回发现选择发生变化时清理旧返回栈，进入 `Screen.Startup` 创建当前合法入口；
未改变选择时正常返回。保存的基线只决定导航，不提供授权，也不把旧聊天请求改绑新 Session。
预算只显示真实能力限制，不相加不同计量维度或周期；配置详情渐进展示，不暴露凭据和运行路由。
历史入口位于页面更多菜单，企业标为全部聊天历史；聊天抽屉仍按当前助手和文件夹筛选。
更改企业地址与退出企业统一位于页面更多菜单，在个人空间查看已连接企业时也可管理；
退出位于菜单末尾并保留原企业的确认。连接卡不设第二个更多菜单，同步配置使用强调按钮，配置详情为次要动作。
连接卡按成员与地址、配置状态与最近成功同步、操作、失败说明排列；提示及诊断入口保持左对齐。
本机当前生效版本放在配置详情首屏，发布 ID、完整摘要和实际记录的格式版本按需展开、复制。
助手企业定义复用只读 `AssistantCatalogDetails`，Starter 复用原定义内容与 `StarterOpeningContext`；
正文在原授权查询中按需读取，显示 key 不充当资源身份或授权，Memory Seed 保持数量摘要。
`EnterpriseVM.refreshStatus` 查询工作区、动态和预算，不应用配置；前台和目标变化合并同目标在途请求。
动态在刷新和失败时保留同目标旧内容，成功空结果才隐藏。工作区与动态错误留在对应区域。
预算用量通知和原 30 秒在途轮询仅在页面活跃时刷新，后台或内嵌工作台、配置详情不继续轮询。
已连接企业在个人空间可只读查看，不能借个人选择执行企业业务。个人备份与企业数据隔离见
[个人备份与恢复](data-persistence.md#个人备份与恢复)。

Android 工作台只承载 Core `/portal/`，不维护另一套网页。每个 `PortalDocument` 绑定原 Session、选择与独立文档身份；
关闭先撤销授权，再销毁 WebView 并等待站点数据清理。网页外链与退出走原生确认，媒体采集归当前文档的硬件和文件所有者。
关闭工作台保留登录，退出另走 Session 命令。详细准入见 [配置架构](android-configuration-architecture.md)。

## 3. 窗口、主题与自适应布局

`AdaptiveLayoutPolicy` 以实际可用宽高、姿态和有效 separating hinge 作决策；
`AdaptiveLayoutInfo` 在根级计算并传递，尺寸由 `AdaptiveLayoutDefaults` 维护。
自适应改变承载方式，不改变业务身份或权限；双栏仅属于聊天，其他页面保持全屏逐页导航。

| 条件 | 聊天与弹层 |
| --- | --- |
| 宽度不足 600dp，或高度不足 480dp | 单栏；普通姿态使用底部 Sheet，矮窗口使用紧凑输入 |
| 宽度至少 600dp、高度至少 480dp，且非 Tabletop | 会话列表与详情双栏，居中 Dialog |
| 双栏且有有效竖向分隔铰链 | 按真实铰链分区，侧栏不可折叠，弹层限于详情区 |
| Tabletop（桌面半折） | 单栏与受限 Dialog；有有效横向铰链时限于上半屏，否则不猜测裁切位置 |

普通双栏侧栏使用保存的 `chat_sidebar_expanded` 偏好；铰链分区优先于折叠偏好。
不能依据机型、横竖屏名称或宽度分档跳过高度和姿态约束。

`AdaptiveModal` 统一承载短期选择器，调用方持有可见状态；Dialog 分支不组合 BottomSheet。
`AdaptiveDialogContainer` 以独立 scrim 接收关闭动作，内容表面阻止穿透，同时保留子控件的点击和滚动。
窗口、内容与底部操作区的点击边界必须实际验证。

`AppearancePolicy` 解析明暗、动态配色和 AMOLED；`MeasixTheme` 只提供颜色及 CompositionLocal。
`WindowSystemBars` 由 `RouteActivity` 与 `SafeModeActivity` 根宿主管理，终端可使用独立深色系统栏，
嵌套主题与离屏导出不写 Window、不维护恢复颜色栈。
聊天背景透明度由 `ChatSurfacePolicy` 与 `ChatOverlaySurface` 管理：消息容器、输入/播放覆盖层、正文产物分别处理，
不能把文字、图片、代码和工具输出一起降低透明度。

聊天 Scaffold 与 TopAppBar 分工处理系统栏，输入区负责导航栏和 IME 避让，不重复累计 inset。
IME 目标显示时将发送/取消或录音停止移入输入框，隐藏能力动作行；目标隐藏后恢复，
不根据动画中间帧反复重组，任何状态都保留唯一可达的终止动作。

## 4. 输入、滚动与运行反馈

### 提交与草稿

普通发送、仅发送不生成、历史保存和 USER 编辑重发共用 `ChatVM.submitInput`，
与 Starter 操作共用锁捕获输入并拒绝重复提交。`SendMessageReceipt` 表示 USER 已提交；
`ChatInputState.completeSubmission` 只清理匹配的文字、附件实例和编辑目标，不等待模型响应。
提交前失败或取消保留原稿；等待期间的新文字、重新添加的附件、新编辑目标不被旧结果清除。
附件归还与事务提交见 [多模态与资源持久化](multimodal-context-and-turn-durability.md)。

输入内容以最终 parts 判断是否为空，空白 Text 不形成输入；编辑历史保留非文本附件及顺序。
导入结果交给输入框前复验原目标，失败或取消只释放本批新文件。

### 滚动意图

`ChatScrollIntent` 是页面内唯一滚动意图，普通列表与预览共用。默认打开、明确回到末端以及新消息追加完成可进入
`FOLLOW_TAIL`；历史定位、无障碍/滚轮阅读和用户拖动撤销自动跟随。拖动停止后只有列表实际空闲且到底才恢复跟随，
新历史操作会撤销这项资格。内容增长、惯性滚动和 IME 动画不能反推用户意图。

发送后的滚动绑定 command 返回的 USER ID 与原分支，等待该节点出现在匹配 snapshot、布局就绪及 IME 隐藏后才执行。
新发送、会话切换或分支变化取消旧请求；不以临时列表项数量或固定延迟猜测提交完成。
自动跟随不要求 Turn 活跃，但空 Draft 的引导卡不触发；IME 补偿仍由 `ImeLazyListAutoScroller` 负责。

### 活动状态、诊断与提示

`ChatPageContent` 根据 `ConversationPresentation.isActive` 统一管理屏幕唤醒，包含准备、生成、工具、待交互和停止清理。
`IDLE` 或离页释放；输入按钮不管理 Window flag。`STOPPING` 时停止提供审批和回答 callback。

`ErrorCardsDisplay` 展示主聊天诊断。回复失败或未完成保留可手动关闭卡片，消息终态条可重新打开保存的 `terminalDetail`；
普通命令失败使用短期反馈。错误绑定原 conversation/selection；工具和子助手在自己的卡片显示领域失败，不重复投递主卡。
取消使用中性状态，不以通用“重试”文案替代真实异常。上下文变化的展示与按需查询统一见
[消息渲染管线](message-rendering-pipeline.md)。

前台触觉由聊天页稳定组合位置的 `TurnHapticFeedback` 管理，直接消费同版本 `turnFeedback` 查询投影，
不依赖消息项是否在视口或自行解析 metadata。仅 RESUMED 且设置允许时工作；新输出触发限频反馈，
无输出时的慢心跳只表示本地请求仍在处理或等待，不证明远端进度。新待审批/回答交互提醒一次后暂停工作心跳。
首次观察、恢复前台、重开设置和切换 Turn 只建立基线；停止、离页或失去前台立即取消后续反馈。
平台适配器尊重系统触觉设置，无振动器时静默。

声音由 `GenerationSideEffects` 管理；完成 Step 音与交互提示按身份去重，待交互提示必须在 checkpoint 成功后播放。
继续执行不重播旧提示，播放失败不回滚已提交事实。更新提醒和 MCP 刷新分别消费既有应用服务状态，
页面不为浏览或重复组合创建后台工作；具体生命周期见 [更新机制](update-mechanism.md) 与 [MCP 架构](mcp-architecture.md)。

### 播放覆盖层

TTS 控制条消费 `SpeechApplicationService` 的播放投影，暂停仍可见，承载页面变化不改变播放所有权。
聊天中以输入面板的实测上沿定位，并遵守详情栏、Tabletop 和窗口安全区，不猜测输入高度或重复叠加 IME inset。
只有当前活动 NavKey 的聊天组合控制条，退出动画中的旧页不能留下第二条；无输入面板的页面由根宿主承载。
覆盖层之外不拦截触摸，大字体下操作仍可达；独立 Activity/Dialog 不承载该覆盖层。
队列、主/子助手共享会话和取消见 [语音架构](speech-architecture.md) 与 [子助手架构](sub-assistant-architecture.md)。

## 5. 图片查看与操作

进程图片加载器由 `MeasixPilotApp` 的 `SingletonImageLoader.Factory` 提供，不依赖 Activity 首次组合。
`ImageSourceInterceptor` 在缓存命中前与结果交付后复验原读取能力，cache key 不充当授权。

`ImagePreviewDialog` 是统一的全屏查看器，不套用 `AdaptiveModal`。它只负责相册浏览、缩放、信息和动作呈现，
业务命令由宿主注入。空集合关闭，初始页校正到合法范围；放大后拖动为平移，未放大时竖向拖动可关闭，
水平翻页和多指缩放不能被关闭手势抢占。信息面板打开时不触发拖拽关闭。

查看器内的进度和结果通过 Dialog 自己的 Toaster 呈现，避免被窗口遮挡。
信息面板打开后才从当前 `ImageSource` 读取有界内容；来源名称由文件服务提供，不扫描路径推断归属。
缩略图、相册、信息、保存共享同一读取能力，URL 不是授权。
聊天相册如何收集、Markdown 图片和导出来源见 [消息渲染管线](message-rendering-pipeline.md)；
Gallery、文件管理与生成页面传入各自可见集合。

设背景通过 `AssistantBackgroundService` 创建独立副本，原宿主决定编辑共享定义还是当前域偏好。
需要选择助手时使用本域目录；确认绑定原目标，切域撤销选择与确认。查看器不自己解析助手或写配置。
仅有独立删除语义的宿主注入删除动作，聊天消息图片不提供该动作；删除成功后调整页序，集合清空则关闭，
失败保留相册与可见诊断。资源读取、相册发布及临时导出补偿归
[资源持久化](multimodal-context-and-turn-durability.md) 中的文件服务。

查看器仍依附宿主 composition：Lazy 列表项离开视口可能使其关闭；单击关闭与双击识别也受图片库手势时序影响。
不能把查看器当成独立的页面或资源生命周期所有者。

## 6. 文件页面与编辑

### 正文与提交

本地 Workspace、远程文件和 Skill 复用 `ui/components/files` 的 `FileEditorState` / `FileTextEditor`。
本地与 Skill 正文只在当前 composition 内存中保存；远程编辑会话的生命周期见下文。
路径、名称等小状态可以保存，大段正文不放入 rememberSaveable、SavedStateHandle、导航参数或原生 View state。
本地与 Skill 在 Activity 重建或进程恢复后重新读取已发布文件，不承诺恢复未保存正文；普通后台停留未重建时保留内存内容。

`FileEditorState` 与原生 `FileEditText` 共用一个 Editable，派生 revision 和提交 snapshot 不构成第二份可写正文。
只读切换保留原 buffer。`FileEditorInputConnection` 只限制 IME 查询窗口，不截断文件；
过大选区/快照不返回伪造截断结果，全文 extracted text/monitor 和可产生大回包的几何查询不注册。
输入、组合、删除、选择使用原生协议，复制与保存仍读取完整正文。

源码预览与编辑共用原生多行 `FileEditText`；只读切换保持同一正文、选择和滚动能力。
`FileTextEditor` 的 `fillViewport` 模式占满受限视口，不施加表单行数上限；Skill 等表单使用有边框及行数约束的输入框。
`fileTextFormat` 统一识别常见文本、代码、配置及无扩展名文件，语言只选择高亮，不要求 JSON/XML 在编辑中合法。
HTML/XML/SVG 按源码显示，不执行 HTML；未知语法的文本保持普通文本显示。
高亮复用 `highlight` 的 `CodeHighlighter`、`highlightTokenStyle` 和原有深浅色配色。
文件与语言各自持有解析实例，避免可变 matcher 与消息渲染或已取消的旧语言计算并发使用。
解析在后台串行合并快速输入，仅对当前正文 revision 应用结果；最多处理 128 Ki 个 UTF-16 字符和 10,000 个颜色 span，
超出预算仍可阅读、编辑和保存完整正文。只管理专有 `FileSyntaxColorSpan`，不修改正文或清理 IME/选择 span，样式变化不产生 dirty。
工作区编辑、保存及批量下载使用具有原操作名称的图标，
触摸区域至少 48dp；另存、重新读取和删除保留文字菜单及必要确认。
本地文件编辑退出时比较已加载或成功提交的正文，未保存时确认，保存中不退出；不改变重建后重新读取的契约。

编辑任务绑定原文件身份和 composition 生命周期。读写异常保留原 cause，保存失败保留正文，
取消和失败均恢复提交状态；命令成功后才关闭编辑或删除窗口。文件访问和 Workspace 导出契约见
[Workspace](workspace-architecture.md)。

### 企业远程文件

企业远程文件入口由 `RemoteWorkspaceService.summary` 统一投影：企业页在连接之后显示紧凑摘要，聊天附件面板
在本地 Workspace 之后显示独立远程行。只有原企业聊天目标仍有效且文件可用时显示快捷行；导航保留原聊天草稿。
两处使用 `Screen.RemoteWorkspace`，导航不序列化管理 handle。页面及 `RemoteWorkspaceVM` 提交应用命令，
不访问 DAO、DAV 或凭据。文件行复用 `ui/components/files/FileRow`，正文复用 FileTextEditor，Markdown 使用受限策略，
PDF 使用有界逐页渲染。身份、条件写入及系统交付约束见 [远程文件](workspace-architecture.md#10-企业远程文件)。

远程编辑正文由 `RemoteWorkspaceVM` 的 `RemoteEditorSession` 在内存中持有，复用同一 `FileEditorState`；
原 VM 保留时可跨视图重建，关闭预览、离页或撤权后清理。正文和管理 handle 不序列化，进程恢复不能重建原编辑授权。

远程文件页使用紧凑列表、按需筛选和真实容量摘要，批量动作及编辑操作按需展开。
宽屏文件行对齐名称、时间和大小，窄屏与大字体上下排列；选择态及布局切换保留输入焦点。
名称弹窗在键盘与矮窗口下保持输入、确认可达。PDF 支持逐页缩放，读取失败提供原授权下的刷新。
条件写入、未知结果及恢复方式由远程文件服务定义，布局变化不改变目标身份或写协议。

## 7. 验证边界

页面变更需验证实际路径，不能只确认节点存在：

- 授权撤销、快速切域往返、Activity 重建与迟到回调不能复活旧投影、导航能力或待提交操作。
- 首发失败、提交期间继续编辑、附件重新添加、旋转及取消不得误清理新草稿。
- 窗口边界、矮横屏、普通宽屏、有效/无效铰链、Tabletop 与 IME 切换保持动作可达；Dialog 内容点击不误关闭。
- 历史阅读不被流式增长或 IME 拉回末端，提交滚动只针对对应 USER。
- 图片浏览、放大/翻页/关闭、Dialog 内反馈、导出失败及文件编辑的大正文/IME 场景需要实际 Compose 与设备证据。

纯布局与投影策略先做 JVM 验证，系统窗口、键盘、触觉和交互绑定由设备测试证明，分层要求见
[测试策略](testing-strategy.md)。
