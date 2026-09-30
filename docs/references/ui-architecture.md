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

`ConversationOpenRequest.NewDraft` 固定会话 ID、原域与助手；`OpenExisting` 只打开已有根会话。
历史、搜索、收藏和通知使用已有会话请求，分享和新建显式创建 Draft。失效的历史 ID 不转成新聊天。
冷启动等待应用恢复后，由 `ConversationApplicationService.initialRequest` 选择最近会话或合法的新聊天入口；
缺少有效企业默认助手时进入空间页，不自动选目录首项或改写失效引用。

`ChatVM.initialize` 取得页面 lease 后才订阅聊天、收藏、错误和附件预览，并创建附件导入作用域。
Missing/Failed 状态提供重试、显式新建和空间入口；重试撤销旧作用域，释放其未提交资源，但仍复验原请求身份。
授权变化说明只描述原页面无法继续访问，不据此认定当前企业空间失效。加载失败及启动请求失败保留完整日志，
`ConversationUnavailable` 提供可展开、选择及复制的原异常诊断，短屏可滚动。
分享输入由 ViewModel 串行消费一次，只预填、不自动发送；旋转不重复导入，恢复到已持久聊天时不重放输入。
未发送 Draft 不另行持久化。

子助手详情借用父聊天的 lease；内容预览借用原渲染来源。导航仅保存稳定条目身份，借用能力为 transient。
保存恢复后必须从原页面重新打开，不能凭会话 ID、runId 或缓存 ID 重建授权。

### 加载、失败与重试

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

普通发送和仅发送不生成通过 `ChatVM.handleMessageSend` 与 Starter 共用的操作锁捕获输入。
`SendMessageReceipt` 表示 USER 已提交；随后 `ChatInputState.completeSubmission` 只清理匹配的文字和原附件实例，
不等待模型响应。等待期间编辑的文字、新增或重新添加的附件保留；清空、替换或进入历史编辑使旧提交不能清理新草稿。
提交前失败不清理输入，重复点击不排队发送同一份内容。附件创建权归还与提交事务见
[多模态与资源持久化](multimodal-context-and-turn-durability.md)。

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

## 6. 文件编辑正文的生命周期

Workspace 和 Skill 的文件编辑入口共用 `FileEditorState` / `FileTextEditor`，正文只在当前 composition 内存中保存。
路径、名称等小状态可以保存，大段正文不放入 rememberSaveable、SavedStateHandle、导航参数或原生 View state。
Activity 重建或进程恢复后重新读取已发布文件，不承诺恢复未保存正文；普通后台停留未重建时保留内存内容。

`FileEditorState` 与原生 `FileEditText` 共用一个 Editable，派生 revision 和提交 snapshot 不构成第二份可写正文。
只读切换保留原 buffer。`FileEditorInputConnection` 只限制 IME 查询窗口，不截断文件；
过大选区/快照不返回伪造截断结果，全文 extracted text/monitor 和可产生大回包的几何查询不注册。
输入、组合、删除、选择使用原生协议，复制与保存仍读取完整正文。

`FileTextEditor` 的 `fillViewport` 模式使用无边框正文及 8dp 内边距，占满受限视口；
Skill 等表单继续使用有边框的输入框。工作区编辑、保存及批量下载使用具有原操作名称的图标，
触摸区域至少 48dp；另存、重新读取和删除保留文字菜单及必要确认。
本地文件编辑退出时比较已加载或成功提交的正文，未保存时确认，保存中不退出；不改变重建后重新读取的契约。

编辑任务绑定原文件身份和 composition 生命周期。读写异常保留原 cause，保存失败保留正文，
取消和失败均恢复提交状态；命令成功后才关闭编辑或删除窗口。文件访问和 Workspace 导出契约见
[Workspace](workspace-architecture.md)。

## 7. 验证边界

企业远程文件入口由 `RemoteWorkspaceService.summary` 统一投影：企业页在连接之后显示紧凑摘要，聊天附件面板
在本地 Workspace 之后显示独立远程行。只有原企业聊天目标仍有效且文件可用时显示快捷行；导航保留原聊天草稿。
两处使用 `Screen.RemoteWorkspace`，导航不序列化管理 handle。页面及 `RemoteWorkspaceVM` 提交应用命令，
不访问 DAO、DAV 或凭据。文件行复用 `ui/components/files/FileRow`，正文复用 FileTextEditor，Markdown 使用受限策略，
PDF 使用有界逐页渲染。身份、条件写入及系统交付约束见 [远程文件](workspace-architecture.md#11-企业远程文件)。

远程文件页使用紧凑列表、按需筛选、目录数量与真实容量摘要；批量下载位于标题栏，其余批量动作在菜单，进度和结果不长期占用正文高度。
文本编辑动作位于标题栏，另存与重新读取逐步披露。目录选择与结果核验保持原管理会话身份，布局收拢不改变写协议。
图片信息面板区分加载、读取失败与真实字段缺失，失败保留可复制诊断和重试。
宽屏文件列表采用对齐的名称、时间和大小列，窄屏与大字体采用上下排列；选择态保持列位置。
名称弹窗适配键盘和矮窗口，输入与确认动作保持可达，切换布局不重建输入焦点。
图片控制保持亮图上的对比度，信息面板限制宽度；PDF 支持逐页缩放，失败退出加载并提供原授权下的显式刷新。

页面变更需验证实际路径，不能只确认节点存在：

- 授权撤销、快速切域往返、Activity 重建与迟到回调不能复活旧投影、导航能力或待提交操作。
- 首发失败、提交期间继续编辑、附件重新添加、旋转及取消不得误清理新草稿。
- 窗口边界、矮横屏、普通宽屏、有效/无效铰链、Tabletop 与 IME 切换保持动作可达；Dialog 内容点击不误关闭。
- 历史阅读不被流式增长或 IME 拉回末端，提交滚动只针对对应 USER。
- 图片浏览、放大/翻页/关闭、Dialog 内反馈、导出失败及文件编辑的大正文/IME 场景需要实际 Compose 与设备证据。

纯布局与投影策略先做 JVM 验证，系统窗口、键盘、触觉和交互绑定由设备测试证明，分层要求见
[测试策略](testing-strategy.md)。
