# 界面架构与自适应布局参考

> 本文档以 Measix Pilot 当前代码为准，完整描述项目的 UI 架构层次、导航体系、主题系统、自适应布局策略以及核心组件流程。
> 文档同时记录了窄屏 / 宽屏 / 折叠屏三种形态下的实际适配方案，供后续界面迭代参考。

---

## 1. 架构总览

```
RouteActivity (ComponentActivity)
  └─ MeasixTheme (主题入口)
       ├─ LocalDarkMode / LocalExtendColors (主题状态)
       └─ AppRoutes() (导航根)
            ├─ CompositionLocalProvider (应用状态注入)
            │    ├─ LocalAdaptiveLayoutInfo  — 窗口尺寸/折叠姿态/布局策略
            │    ├─ LocalNavController       — 导航控制器
            │    ├─ LocalSettings            — 全局设置
            │    ├─ LocalSharedTransitionScope — 共享元素动画
            │    └─ LocalToaster / LocalTTSState / LocalASRState
            └─ NavDisplay (Navigation 3)
                 └─ entry<Screen.*> (全屏逐页导航)
```

### 技术栈

| 层面 | 技术 |
|------|------|
| UI 框架 | Jetpack Compose + Material Expressive (M3) |
| 导航 | Navigation 3 (`NavDisplay` / `NavKey` / `entryProvider`) |
| 依赖注入 | Koin (`koinInject` / `koinViewModel`) |
| 持久化 | Room (数据库) + DataStore (偏好) + SharedPreferences (快捷 KV) |
| 网络 | OkHttp (SSE 流式) |
| 图片 | Coil3 (SVG / GIF / 动画解码) |
| 动画 | SharedTransitionLayout + animateDpAsState |
| 模糊效果 | Haze (对话背景毛玻璃) |

---

## 2. 导航体系

### 2.1 RouteActivity

`RouteActivity` 是唯一的 Activity 入口，职责：

- `enableEdgeToEdge()` + `disableNavigationBarContrast()` 实现 edge-to-edge 全屏
- `CrashHandler` 崩溃检测 → 崩溃后跳转 `SafeModeActivity`
- `setContent { MeasixTheme { AppRoutes() } }` 启动 Compose 树
- `ShareHandler` 监听 `ACTION_SEND` / `ACTION_PROCESS_TEXT` Intent
- `onNewIntent` 处理外部 `conversationId` 跳转
- `volumeKeyListeners` 注册音量键监听（最后注册者优先）

### 2.2 导航模型

```kotlin
class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {})
    fun clearAndNavigate(screen: Screen)
    fun popBackStack()
}
```

- `Screen` 是 `sealed interface : NavKey`，路由均为可序列化的 data class/object
- 路由以**全屏页面切换**为主，`fadeIn/fadeOut` 用于根级切换，`slideInHorizontally + scaleOut` 用于层级切换
- `entryDecorators` 包含 `rememberSaveableStateHolderNavEntryDecorator()` 和 `rememberViewModelStoreNavEntryDecorator()`，保证页面状态保持与 ViewModel 生命周期正确

### 2.3 路由边界

`Screen` 的密封层次与 `RouteActivity` 的 `entry<Screen.*>` 注册是路由清单的唯一权威来源。
路由按职责分为聊天/分享、历史与收藏、助手配置、设置、扩展与 Workspace，以及 WebView、备份、
图片生成和调试页面。带业务身份的页面把 ID 放入可序列化路由参数；例如聊天使用会话 ID，
子助手详情使用 `masterConversationId + runId`，工作区文件编辑使用 Workspace ID、区域和路径。

新增路由必须同时补齐 `Screen` 定义、entry 注册、参数恢复和返回行为；不要在参考文档维护一份易漂移的逐项副本。

---

## 3. 主题系统

### 3.1 MeasixTheme

外观判定集中在 `AppearancePolicy`，`MeasixTheme` 只负责取色并提供 CompositionLocal：

```
MeasixTheme(colorMode)
  ├─ ColorMode（SharedPreferences `colorMode`）→ AppearancePolicy.resolveDarkTheme
  ├─ 读取 Settings (dynamicColor, themeId, customThemes)
  ├─ 读取 AMOLED（SharedPreferences `amoledDark`）
  ├─ 颜色方案选择：
  │    ├─ 动态色仅在 Android 12+ 且开关开启时生效
  │    └─ 否则 findThemeById → getColorScheme(dark)；未知 id 回退 Sakura
  ├─ AMOLED 暗色: AppearancePolicy.applyAmoledDark（仅 dark + amoled）
  ├─ 系统栏图标: isAppearanceLightStatusBars / isAppearanceLightNavigationBars = !darkTheme
  └─ MaterialExpressiveTheme(colorScheme, typography, motionScheme.expressive())
```

### 3.2 颜色模式

```kotlin
enum class ColorMode { SYSTEM, LIGHT, DARK }
```

- `SYSTEM` 跟随系统暗色模式；`LIGHT` / `DARK` 强制浅色或深色
- 动态色、预设/自定义主题、AMOLED 与颜色模式在「偏好 → 主题」页集中展示；设置首页保留颜色模式快捷入口
- Android 12 以下动态色开关不可用，主题选择器始终可选
- AMOLED 暗色模式在普通暗色基础上将 `background` 和 `surface` 设为纯黑，其他 tonal 色（`surfaceContainerHighest` 等）不受影响。强制浅色时开关禁用；跟随系统时偏好会保留，只在实际暗色时生效
- **注意**：AMOLED 下 `surfaceColorAtElevation` 会贴近纯黑导致选中项不可辨；对比表面使用 `surfaceContainer` / `High` / `Highest`

### 3.3 助手背景上的卡片透明度

助手开启背景图或渐变后，聊天页通过 `ProvideChatSurfacePolicy` 写入 `LocalChatChromeAlpha`：

- **消息层 Chrome**（思考过程 `ChainOfThought`、用户/助手气泡、子助手卡、空态 readiness、建议胶囊）：使用 `ChatSurfacePolicy.chromeAlpha`。有背景时封顶为 `BACKGROUND_CHROME_MAX_ALPHA`，用户仍可通过气泡不透明度滑条再调低
- **输入条实心底**：只用 `pageChromeAlpha`（有背景 `0.82`，无背景 `1.0`），不跟随气泡滑条，避免输入区随消息透明度一起变淡
- **产物与正文**（代码块、Mermaid/HTML 预览、表格、块级公式、图片/音视频/文档芯片、工具输出正文）：保持完全不透明，不套 chrome alpha
- 没有助手背景时，消息层 chrome 继续只跟随 `DisplaySetting.bubbleOpacity`（默认 1.0）

### 3.4 预设主题

| 主题 | 文件 |
|------|------|
| Spring | `presets/SpringTheme.kt` |
| Sakura | `presets/SakuraTheme.kt` |
| Ocean | `presets/OceanTheme.kt` |
| Minimal | `presets/MinimalTheme.kt` |
| Claude | `presets/ClaudeTheme.kt` |
| Black | `presets/BlackTheme.kt` |
| Autumn | `presets/AutumnTheme.kt` |

自定义主题通过 `CustomTheme.kt` 支持，存储在 Settings 的 `customThemes` 列表中。

### 3.5 扩展颜色

`LocalExtendColors` 提供随明暗切换的语义状态色（成功/警告/信息等），定义在 `Color.kt`。代码高亮使用 `AtomOneDarkPalette` / `AtomOneLightPalette`，不走扩展色板。

---

## 4. 自适应布局系统

### 4.1 设计原则

- 以窗口的**实际可用宽高**和**折叠姿态**为输入，不依赖"手机/平板"名称或横竖屏判断
- 自适应只改变**承载方式**，不迁移或删减功能
- 旋转、分屏、折叠和展开只重排界面，不丢失当前会话、输入草稿、滚动位置或导航状态
- 物理铰链、系统栏和显示切口始终作为不可用区域处理
- **聊天主界面**最多两栏，不设计永久三栏模式
- **次要页面**（设置、历史、统计等）保持全屏逐页导航，不纳入自适应改造

### 4.2 AdaptiveLayoutInfo

根级统一计算一次，通过 `LocalAdaptiveLayoutInfo` CompositionLocal 注入所有页面：

```kotlin
@Immutable
data class AdaptiveLayoutInfo(
    val windowSize: DpSize,
    val windowAdaptiveInfo: WindowAdaptiveInfo,
    val separatingVerticalHingeBounds: List<AdaptiveHingeBounds>,
    val separatingHorizontalHingeBounds: List<AdaptiveHingeBounds>,
) {
    val widthClass: AdaptiveWidthClass          // Compact/Medium/Expanded/Large/ExtraLarge
    val primaryVerticalHingeBounds: AdaptiveHingeBounds?   // 真实竖向铰链窗口坐标（dp）
    val primaryHorizontalHingeBounds: AdaptiveHingeBounds? // 真实横向铰链窗口坐标（dp）
    val hasSeparatingVerticalHinge: Boolean
    val isTabletop: Boolean                      // 是否为桌面半折姿态
    val chatLayoutMode: ChatLayoutMode           // SinglePane / ListDetail
    val useExpandedModal: Boolean                // 居中 Dialog vs 底部 Sheet
    val canCollapseChatSidebar: Boolean          // 侧栏是否可折叠
    val verticalPaneSplit: VerticalPaneSplit
    val useCompactChatInput: Boolean             // 紧凑输入模式
    val listPaneWidth: Dp                        // 平面窗口 300/360dp；铰链窗口为 hinge.left
    val verticalHingeSpacerWidth: Dp             // 物理铰链宽度
    val tabletopContentHeight: Dp?               // Tabletop 上半屏可用高度
}
```

### 4.3 策略阈值（AdaptiveLayoutPolicy）

所有阈值集中在 `AdaptiveLayoutPolicy` 纯函数对象中，可独立单元测试：

```
MediumWidthBreakpoint = 600f    // Compact ↔ Medium
ExpandedWidthBreakpoint = 840f  // Medium ↔ Expanded
LargeWidthBreakpoint = 1200f    // Expanded ↔ Large
ExtraLargeWidthBreakpoint = 1600f
MinimumDualPaneHeight = 480f    // 双栏/弹层/紧凑输入共用的高度阈值
```

### 4.4 项目宽度分档与聊天策略

| Width Class | dp 范围 | 高度 ≥480dp 且非 Tabletop 时的聊天模式 |
|-------------|---------|------------------------------------------|
| Compact | 0–599 | SinglePane |
| Medium | 600–839 | ListDetail |
| Expanded | 840–1199 | ListDetail |
| Large | 1200–1599 | ListDetail |
| ExtraLarge | 1600+ | ListDetail |

宽度分档只负责描述窗口；最终布局还必须同时满足高度和姿态条件。项目从 600dp 起允许聊天双栏，使中等宽度窗口也能利用横向空间，而设置等次要页面不随该分档改成双栏。

### 4.5 核心策略函数

```kotlin
// 聊天布局模式
chatLayoutMode(widthDp, heightDp, isTabletop):
  if (isTabletop || heightDp < 480) → SinglePane
  if (widthDp >= 600) → ListDetail
  else → SinglePane

// 弹层模式；Tabletop 强制使用受限 Dialog，避免 BottomSheet 穿过横向铰链
useExpandedModal(widthDp, heightDp, isTabletop):
  isTabletop || (widthDp >= 600 && heightDp >= 480)

// 紧凑输入（矮横屏）
useCompactChatInput(heightDp): heightDp < 480

// 侧栏可折叠（铰链场景不可折叠）
canCollapseChatSidebar(widthDp, heightDp, hasHinge, isTabletop):
  chatLayoutMode == ListDetail && !hasSeparatingVerticalHinge

// 竖向铰链分区
verticalPaneSplit(windowWidthDp, fallbackListWidthDp, hingeBounds):
  flat → fallbackListWidth + 0 + remainingWidth
  hinge → hinge.left + hinge.width + (windowWidth - hinge.right)
```

### 4.6 布局常量（AdaptiveLayoutDefaults）

| 常量 | 值 | 用途 |
|------|-----|------|
| `ReadableContentMaxWidth` | 840.dp | 消息/输入框最大可读宽度 |
| `SheetMaxWidth` | 640.dp | 居中 Dialog 默认最大宽度 |
| `SheetMaxHeight` | 760.dp | 居中 Dialog 默认最大高度 |
| `ListPaneWidth` | 300.dp | Medium/Expanded 侧栏宽度（与 ModalDrawerSheet 一致） |
| `WideListPaneWidth` | 360.dp | Large/ExtraLarge 侧栏宽度 |
| `DialogPadding` | 24.dp | Dialog 外边距 |

### 4.7 AdaptiveModal

短生命周期选择器使用 `AdaptiveModal` 包裹，页面本身仍保持全屏逐页导航：

- **宽屏**（`useExpandedModal = true`）：居中 `Dialog`，限制最大宽高
- **竖向铰链**：根据真实 `hinge.right` 将 Dialog 限制在右侧 detail pane
- **Tabletop**：根据真实 `hinge.top` 将 Dialog 限制在上半屏
- **窄屏**：`ModalBottomSheet`，贴底弹层

聊天链路中的助手、模型、文件、MCP、搜索、推理、Workspace、扩展与导出等临时内容均复用该容器；设置等页面也可以复用 `AdaptiveModal`，但这不会改变其页面导航结构。

空会话引导卡片的 MCP 行与输入框“＋”菜单中的 MCP 项必须打开同一个 `McpPickerSheet`，直接修改当前助手的 MCP 选择，
不能把引导卡片旁路到 Assistant 或全局设置页。该 Sheet 的标题左对齐，右侧“管理 MCP 服务器”按钮关闭 Sheet 后导航到
`Screen.SettingMcp`；即使尚未登记 Server 或所有 Server 都已禁用，Sheet 仍显示空状态和这个管理入口。

> **例外**：全屏图片查看器（`ImagePreviewDialog`）是刻意不经过 `AdaptiveModal` 的全屏 `Dialog`
> （`usePlatformDefaultWidth = false`、纯黑背景、自有点按/竖直拖拽关闭手势与多图翻页）。
> 相册式浏览需要完整的屏幕空间与手势域，不适配半屏 Sheet / 有界卡片的弹层约定。
> 场景差异通过 `extraActions` / `overlay` 与 `LocalImagePreviewActions` /
> `LocalImagePreviewOverlay` 注入（如设为背景、确认框、助手选择器），查看器不理解助手或页面。
> 仅当宿主场景本身具有独立删除语义时才传入 `ImagePreviewDeleteAction`。查看器统一承载
> 确认、执行中、失败提示和相册页序列更新，typed suspend action 仍由宿主调用既有领域删除
> API；成功删除中间项后显示原下一项，删除末项后显示新末项，清空后关闭。聊天消息图片等
> 只能随上层实体删除的内容不传该 action，避免查看器建立旁路文件删除协议。
> Toast 画在 Dialog 窗口内，进行中与结果共用同一 toast id，避免被全屏层挡住应用根 `Toaster`。
> 决策依据见 [`docs/dev/image-viewer-upgrade-plan.md`](../dev/image-viewer-upgrade-plan.md)。

### 4.8 AdaptiveDialogContainer

全屏 Dialog 容器，解决 Compose 平台 outside-click 无法检测自定义全屏 Dialog 外部点击的问题。采用**显式 scrim 双层方案**：

- 底层 scrim：铺满整个窗口的 `clickable` 层，任何落到其上的点击触发 `onDismissRequest`
- 上层内容层：`clipToBounds()` 防止浮动工具栏等子内容溢出 Dialog 边界；内部通过 `detectTapGestures` 吸收落在表面空白区的点击，使它们不会穿透到 scrim；子控件（按钮/输入框/分段按钮/滚动区）优先消费各自手势，不受影响

该分层避免依赖内容测量边界；`AdaptiveDialogContainerTest` 锁定“点击 scrim 关闭、点击内容不关闭、底部操作可执行”三项交互契约。

---

## 5. 聊天主界面架构

聊天主界面是自适应改造的核心，也是唯一实现双栏的页面。

### 5.1 ChatPage 布局分支

```kotlin
when (adaptiveLayoutInfo.chatLayoutMode) {
    ListDetail -> Row {
        // 左侧：会话列表侧栏（宽度动画 + clipToBounds）
        Surface(width = sidebarWidth, color = surfaceContainerLow) {
            ChatDrawerContent(permanent = true, onCollapse = ...)
        }
        Spacer(width = verticalHingeSpacerWidth) // 平面窗口为 0dp
        // 右侧：聊天详情区（weight(1f)）
        Box {
            ChatPageContent(navigationAction = ExpandSidebar / None)
        }
    }

    SinglePane -> ModalNavigationDrawer(drawerContent = {
        ChatDrawerContent(navigateFromDrawer = { close drawer then navigate })
    }) {
        ChatPageContent(navigationAction = OpenDrawer)
    }
}
```

Tabletop 且存在有效横向铰链坐标时，以上单栏内容再由外层容器限制到 `hinge.top`，不会跨入下半屏。

### 5.2 侧栏折叠动画

采用**宽度动画**（`animateDpAsState`）而非 `ListDetailPaneScaffold` 或 `AnimatedVisibility`：

- `ListDetailPaneScaffold` 的 `adaptStrategies` 默认"有空间就展示所有 pane"，用户手动折叠后 pane 仍被强制展开
- `AnimatedVisibility` 在 Row 中 exit 动画期间内容仍占满宽度，表现为"先留白占位再消失"
- **当前方案**：侧栏 `Surface` 宽度在 `0.dp` 和 `listPaneWidth` 之间使用 `animateDpAsState` + `clipToBounds()`；动画期间内容随容器裁剪，宽度到 0 后停止组合侧栏内容。会话列表数据与滚动状态由 Activity 级 `ChatDrawerVM` 管理

侧栏展开状态持久化到 SharedPreferences（key `chat_sidebar_expanded`，默认展开）。折叠后 TopBar 显示 `PanelLeftOpen` 按钮恢复侧栏。

### 5.3 展开与折叠图标

| 按钮 | 图标 | 场景 |
|------|------|------|
| 窄屏打开抽屉 | `Menu03`（汉堡菜单） | SinglePane 模式 |
| 宽屏展开侧栏 | `PanelLeftOpen` | ListDetail + 侧栏已折叠 |
| 宽屏折叠侧栏 | `PanelLeftClose` | ListDetail + 侧栏已展开 |

### 5.4 ChatDrawerContent 结构

```
ChatDrawerContent
  ├─ permanent = true  → Surface + statusBarsPadding
  ├─ permanent = false → ModalDrawerSheet（外层 ModalNavigationDrawer 由 ChatPage 持有）
  └─ Column (drawerBody)
       ├─ 用户头像行 (UIAvatar 50dp + 昵称 + 编辑入口)
       ├─ DrawerActions (搜索入口 + 历史入口，两个独立 Surface)
       ├─ FolderBar (文件夹选择栏)
       ├─ ConversationList (LazyColumn, weight(1f))
       │    ├─ PinnedHeader (置顶会话分组)
       │    ├─ DateHeaderItem (日期分组)
       │    └─ ConversationItem (单个会话条目)
       └─ AssistantPicker (底部助手选择器)
```

### 5.5 ChatPageContent 结构

```
ChatPageContent
  ├─ Surface(background)
  │    ├─ AssistantBackground (hazeSource 毛玻璃背景)
  │    └─ Scaffold(contentWindowInsets = 0)
  │         ├─ topBar: TopBar
  │         │    ├─ 导航按钮 (Menu03 / PanelLeftOpen / PanelLeftClose)
  │         │    ├─ 会话标题 (titleMedium) + 副标题 (bodySmall)
  │         │    ├─ 头像 (UIAvatar 40dp)
  │         │    └─ 操作按钮 (新建聊天 / 预览模式 / 菜单)
  │         ├─ content: ChatList
  │         │    ├─ LazyColumn (消息列表, widthIn(max = ReadableContentMaxWidth))
  │         │    ├─ 滚动控制 (跳转底部 / 搜索消息)
  │         │    └─ 空会话引导卡片 (ConversationReadiness)
  │         └─ bottomBar: ChatInput
  │              ├─ 紧凑模式 (useCompactChatInput): 收紧输入框与 action row 间距
  │              ├─ 正常模式: 附件预览 + 输入框 + action row
  │              ├─ IME 目标显示时隐藏 action row；正常态发送/取消、录音态 ASR 停止进入 TextField trailing
  │              └─ AdaptiveModal pickers (助手/模型/文件/MCP/搜索/推理/Workspace)
```

MCP 设置页只保留列表下拉刷新，避免顶部栏重复入口。下拉只调用 `McpApplicationService.refreshAll()`，指示器偏移到可折叠
TopAppBar 下方；它最多绑定 20 秒用户 receipt，不绑定 AppScope 中可能持续数分钟的后台恢复。receipt 结束时若仍有 server
继续执行，页面停止 spinner、给出后台继续提示，并由各 server 卡片持续显示真实状态。单 server 失败卡片保留独立重试入口。

### 5.6 顶部/底部留白

| 位置 | 值 | 说明 |
|------|-----|------|
| Scaffold `contentWindowInsets` | `WindowInsets(0)` | 让 TopAppBar 自己处理状态栏避让，避免双重留白 |
| ChatList contentPadding top | 0dp；配置提示存在时 8dp | TopAppBar 已提供主要间距 |
| ChatList contentPadding bottom | 24dp | 输入框上方滚动余量 |
| ChatDrawer body padding | horizontal 8dp | 侧栏左右边距 |
| ChatInput bottom padding | 8dp | 另由 `navigationBarsPadding()` 与 `imePadding()` 处理系统区域；IME 动画不切换这一本地间距 |

发送后的到底部请求持有 command 返回的 user message id，并以会话分支和该 durable 节点追加为准，不把
loading、配置提示等临时 LazyColumn item 数量变化当作消息提交。请求在目标分支出现目标消息、LazyColumn item
结构与当前 snapshot 对齐且 IME 实际到达隐藏终态后滚动到底部 sentinel；新发送、会话切换或节点分支变化会
取消旧请求，不使用固定时间延迟猜测布局完成。

会话底部 `ErrorCardsDisplay` 是需要展示明确诊断原文的主通道。普通命令和标题/建议/压缩等边缘失败维持 5 秒自动关闭；
本轮 Master 回复 `FAILED` / `INCOMPLETE` 使用手动关闭卡片，并按当前 `conversationId` 过滤。消息终态条只显示 durable
reason 对应的短状态，取消使用中性色而不冒充错误；失败或未完成条可再次打开消息 `terminalDetail`。工具与子助手卡片
自行显示其领域失败，不向主会话重复投递卡片。

---

## 6. 组件层次

### 6.1 UI 组件目录

`ui/components/` 按职责分层：`ai/` 负责聊天输入和能力选择器，`message/` 负责消息、分支、
推理与工具卡片，`richtext/` 负责 Markdown、代码、LaTeX、Mermaid、HTML 和图片，`webview/`
封装 WebView 生命周期，`ui/`、`table/`、`nav/` 提供通用组件。具体文件以目录和调用点为准，
不在本文复制文件清单。

### 6.2 页面目录

`ui/pages/` 按路由域组织。聊天自适应只在 `chat/` 内实现；`assistant/` 同时包含助手配置和只读
子助手详情；`extensions/` 承载 Skill 与 Workspace；设置、历史、收藏、搜索、备份、分享等页面
保持独立的全屏导航职责。页面目录不应反向依赖具体组件文件名。

### 6.3 全局上下文 (CompositionLocal)

| Local | 类型 | 提供位置 | 用途 |
|-------|------|---------|------|
| `LocalAdaptiveLayoutInfo` | `AdaptiveLayoutInfo` | RouteActivity | 窗口尺寸/布局策略 |
| `LocalNavController` | `Navigator` | RouteActivity | 页面导航 |
| `LocalSettings` | `Settings` | RouteActivity | 全局设置 |
| `LocalSharedTransitionScope` | `SharedTransitionScope` | RouteActivity | 共享元素动画 |
| `LocalToaster` | `ToasterState` | RouteActivity | Toast 消息 |
| `LocalTTSState` / `LocalASRState` | TTS/ASR 状态 | RouteActivity | 语音 |
| `LocalDarkMode` | `Boolean` | MeasixTheme | 暗色模式 |
| `LocalExtendColors` | `ExtendColors` | MeasixTheme | 扩展色板 |

---

## 7. 数据流与状态管理

### 7.1 ViewModel 层

每个页面使用 Koin 注入对应的 ViewModel：

| ViewModel | 职责 |
|-----------|------|
| `ChatVM` | 单会话状态：消息列表、生成 Job、输入状态、错误处理 |
| `ChatDrawerVM` | 侧栏状态：会话分页列表、文件夹、滚动位置 |
| `SettingVM` | 设置读写 |
| `AssistantDetailVM` | 助手配置（含子助手原子清理、`hasValidChatModel` 警告） |
| `SubAssistantDetailVM` | 子助手只读详情（从 Master 消息解析 Tool metadata） |
| `HistoryVM` / `FavoriteVM` / `StatsVM` | 对应页面数据 |
| `SearchVM` | 消息搜索 |
| `WorkspaceVM` / `WorkspaceDetailVM` | 工作区管理 |
| `BackupVM` | 备份/恢复 |
| `DebugVM` / `PromptVM` / `QuickMessagesVM` / `SkillsVM` / `SkillDetailVM` | 对应页面 |
| `ImgGenVM` | 图片生成 |
| `ShareHandlerVM` | 分享处理 |

### 7.2 依赖注入

Koin 模块在 `di/AppModule.kt` 和 `di/DatabaseModule.kt` 等文件中定义。关键单例：

- `UpdateChecker` — 应用更新检查（`by lazy` 缓存 `StateFlow`，AppScope 级共享，切换会话不重复请求）
- `MasterTurnCoordinator` — 主回合生成编排
- `ConversationApplicationService` / `ConversationQueryService` — UI 写/读端口
- `McpApplicationService` / `McpQueryService` — MCP 命令与 definition/catalog/runtime 只读投影；Compose 不持有 client
- `ApplicationRecoveryCoordinator` — 启动恢复与 fail-closed 门禁
- `ChatNotificationManager` — 通知管理（`createdAtStart = true` 保证进程启动即订阅事件）
- `AppEventBus` — 全局事件总线
- `LocalTools` — 本地工具集
- `TTSManager` / `SoundEffectPlayer` / `EmojiData`

### 7.3 会话助手归属

`Conversation.assistantId` 是已创建会话的助手权威来源。聊天页通过
`Settings.getConversationAssistant(conversation.assistantId)` 解析助手，并将同一对象传给标题、背景、模型、搜索、推理、快捷消息、文件能力和生成前检查；只有会话引用的助手已被删除时，才回退到当前全局助手。

切换会话助手时，`ChatVM.switchConversationAssistant` 负责更新会话的 `assistantId` 和目标模型。全局 `Settings.assistantId` 只表示新建会话等全局入口的当前选择，不应直接驱动已有会话的聊天界面。

### 7.4 更新检查状态流

```kotlin
class UpdateChecker(...) {
    val updateState: StateFlow<UiState<UpdateInfo>> by lazy {
        checkUpdate().stateInOnce(appScope, UiState.Loading)
    }
}

stateInOnce(scope, initialValue) =
    stateIn(scope, SharingStarted.Lazily, initialValue)
```

所有 `ChatVM` 共享 Koin 单例 `UpdateChecker` 的同一 `StateFlow`。`ChatDrawer` 仅在 `DisplaySetting.areUpdateChecksEnabled()` 为真且非 Play Store 安装时组合 `UpdateCard`。首次订阅时才启动检查；启动后即使订阅暂时消失也不会重建冷 Flow，因此同一 App 进程内最多请求一次。成功版本的关闭状态写入 `Settings.ignoredUpdateVersion`，只有版本变化后才再次提示；失败卡片的关闭状态保存在 `UpdateChecker.errorDismissed`，下次进程启动可重试。

---

## 8. 屏幕适配方案

### 8.1 普通窄屏（widthDp < 600dp）

| 方面 | 方案 |
|------|------|
| 聊天布局 | `ModalNavigationDrawer` + 单栏聊天 |
| 抽屉触发 | TopBar 汉堡按钮 `Menu03` |
| 弹层 | `ModalBottomSheet`（贴底） |
| 输入区 | 高度 ≥480dp 时为正常布局；低于 480dp 时为紧凑布局 |
| 次要页面 | 全屏逐页导航 |
| 安全区 | `safeDrawingPadding` / 系统栏避让 |

### 8.2 普通宽屏（widthDp ≥ 600dp、heightDp ≥ 480dp、非 Tabletop）

| 方面 | 方案 |
|------|------|
| 聊天布局 | `Row` 双栏：永久侧栏 + 聊天详情 |
| 侧栏宽度 | Medium/Expanded: 300dp；Large/ExtraLarge: 360dp |
| 侧栏折叠 | `animateDpAsState` 宽度动画 + `clipToBounds` + `PanelLeftClose/Open` 按钮 |
| 弹层 | `AdaptiveModal` 居中 `Dialog`（最大 640×760dp） |
| 输入区 | 正常两行布局，`widthIn(max = 840dp)` 居中限宽 |
| 消息列表 | `widthIn(max = 840dp)` 居中限宽 |
| 次要页面 | 全屏逐页导航（不做双栏） |
| 安全区 | `Scaffold(contentWindowInsets = 0)` + TopAppBar 自避让 |

### 8.3 折叠屏（Foldable）

#### 有竖向分隔铰链

| 方面 | 方案 |
|------|------|
| 聊天布局 | 使用真实铰链坐标分为 `hinge.left + hinge.width + detail`，会话列表和聊天严格位于铰链两侧 |
| 侧栏折叠 | **不可折叠**（`canCollapseChatSidebar = false`），避免聊天面板跨越铰链 |
| 弹层 | `AdaptiveModal` 使用 `Alignment.CenterEnd`，限制到铰链真实右边界之后 |
| 弹层宽度 | `min(SheetMaxWidth, windowWidth - hinge.right - safeInsets - 2×DialogPadding)` |

#### 无竖向分隔铰链

回到纯尺寸策略：满足普通宽屏条件时使用可折叠双栏，否则使用单栏；不会根据设备型号或“展开/闭合”名称猜测布局。

#### 桌面半折（Tabletop）

`isTabletop = true` 时强制 `SinglePane`。存在有效横向铰链坐标时，聊天页限制在 `hinge.top` 以上，输入区按上半屏实际高度决定是否紧凑，临时弹层使用上半屏受限 Dialog。若平台只报告 Tabletop 姿态而未提供有效坐标，仍保持单栏和 Dialog，但无法进一步按铰链位置裁切。

### 8.4 矮横屏（heightDp < 480dp）

- `useCompactChatInput = true`：收紧附件、输入框和 action row 的垂直间距；IME 隐藏时能力操作仍在 action row
- IME 动画目标为显示时隐藏 action row；正常态把发送/取消放入 TextField trailing，ASR 录音态则放置 ASR 停止动作，保证任一组合态都有唯一可达的终止操作；目标隐藏时恢复 action row，避免依据当前帧 inset 来回抖动
- 功能不删减，恢复正常高度后回到普通间距
- 聊天保持单栏（`heightDp < 480` → `SinglePane`）

### 8.5 各形态适配矩阵

| 场景 | widthDp | heightDp | chatLayoutMode | 弹层 | 输入 | 侧栏折叠 |
|------|---------|----------|---------------|------|------|---------|
| 普通窄屏 | 390 | 844 | SinglePane | BottomSheet | 正常 | N/A |
| 普通矮横屏 | 844 | 390 | SinglePane | BottomSheet | 紧凑 | N/A |
| 竖向铰链窗口 | 900 | 800 | ListDetail | 右侧 Dialog | 正常 | 不可折叠 |
| 中等宽屏边界 | 600 | 480 | ListDetail | Dialog | 正常 | 可折叠 |
| 大宽屏 | 1280 | 800 | ListDetail | Dialog | 正常 | 可折叠 |
| Tabletop（有横向铰链） | 1000 | 800 | SinglePane（上半屏） | 上半屏 Dialog | 按上半屏高度 | N/A |

---

## 9. 导航图标方向

| 按钮 | 图标 | 箭头方向 | 说明 |
|------|------|---------|------|
| 窄屏打开抽屉 | `Menu03` | 无 | 移动端惯例 |
| 宽屏展开侧栏 | `PanelLeftOpen` | 朝左 | 指向面板方向，与 VS Code/Android Studio 一致 |
| 宽屏折叠侧栏 | `PanelLeftClose` | 朝右 | 收起方向，与 VS Code/Android Studio 一致 |

---

## 10. 安全区与窗口插入

- 聊天页 `Scaffold` 设置 `contentWindowInsets = WindowInsets(0)`，让 TopAppBar 自己处理状态栏避让，避免 Scaffold 与 TopAppBar 重复计算状态栏高度导致顶部留白过多
- 永久会话栏使用 `statusBarsPadding()` 处理顶部安全区
- `ChatInput` 使用 `navigationBarsPadding()` 与 `imePadding()` 处理底部系统栏和软键盘
- `AdaptiveModal` 居中 Dialog 使用 `safeDrawingPadding()` 确保不与系统栏重叠
- edge-to-edge 模式下 `disableNavigationBarContrast()` 关闭系统导航栏对比度强制

---

## 11. 消息渲染管线

消息渲染的稳定层次是：

```
ChatList (LazyColumn)
  └─ items: messageNodes
       └─ ChatMessage（单条消息与分支容器）
            ├─ avatar / branch / actions / nerd line
            └─ groupMessageParts（按原始 Part 顺序分组）
                 ├─ ContentBlock → 文本、图片、音频、视频、文档等
                 ├─ ThinkingBlock → reasoning 与普通工具 step（折叠时钉住 Pending 与 generate_image）
                 └─ SubAssistantCallBlock → SubAssistantCallCard
```

文本从 `MarkdownBlock` 进入；无 HTML 时走 Markdown AST，含 HTML 时转入 `MarkdownNew` 的 DOM
路径。工具审批和 Target 卡片必须保留在 Part 的语义位置。节点级细节统一见
[消息渲染管线](message-rendering-pipeline.md)，生成侧见 [消息生成链路](chat-generation-pipeline.md)。

---

## 12. Message Transformer 管道

消息在发送前和接收后经过 Transformer 管道处理：

### 输入管道（InputMessageTransformer）

| Transformer | 作用 |
|-------------|------|
| TimeReminderTransformer | 按助手设置注入时间提醒 |
| PromptInjectionTransformer | 合并助手与会话启用的提示注入 |
| PlaceholderTransformer | 处理消息中的占位符 |
| DocumentAsPromptTransformer | 文档附件转文本提示 |
| OcrTransformer | 图片 OCR 文字提取 |
| TemplateTransformer | 应用 Pebble 模板 |
| WorkspaceReminderTransformer | 注入 Workspace 上下文提醒 |
| ToolArtifactReplayTransformer | 按 artifact metadata 重写历史 Tool Result 路径与 Image URL |

### 输出管道（OutputMessageTransformer）

| Transformer | 作用 |
|-------------|------|
| ThinkTagTransformer | 提取 `<think>` 标签转为推理部分 |
| Base64ImageToLocalFileTransformer | 生成完成后将 Base64 图片转为本地文件引用 |
| RegexOutputTransformer | 正则替换助手响应 |

输出 Transformer 的 `transforms()` 处理进入生成循环的消息，`visualTransform()` 只形成流式 UI 投影，
`onGenerationFinish()` 在单步完成时收口推理和文件引用。只有持久化路径的结果可以改变会话事实。

---

## 13. 测试

### JVM 单元测试

`AdaptiveLayoutPolicyTest` 覆盖：

- Width Class 边界值（599/600/840/1200/1600）
- 手机竖屏 SinglePane
- 矮横屏 SinglePane + 紧凑输入
- 代表性的竖向折叠宽屏 ListDetail
- 平板 ListDetail
- 铰链不影响 chatLayoutMode 但阻止侧栏折叠
- Tabletop 强制 SinglePane
- 普通窗口 useExpandedModal 与 chatLayoutMode 边界一致，Tabletop 使用铰链安全 Dialog
- 600dp/480dp 精确边界值
- 真实竖向铰链坐标的 list / gap / detail 分配
- 无效铰链过滤与多铰链中心选择

`ConversationAssistantSwitchTest` 覆盖会话助手切换、重复切换幂等，以及已删除助手的回退规则。`UpdateCheckerTest` 覆盖首次订阅启动、订阅离开后不重启，以及同一共享流只执行一次上游请求。`AppearancePolicyTest` 覆盖颜色模式、动态色 API 门槛、AMOLED 仅改写 `background`/`surface`，以及未知主题回退 Sakura。`ChatSurfacePolicyTest` 覆盖助手背景下 chrome 封顶、无背景时跟随气泡不透明度，以及产物保持不透明。

### 设备仪器测试

`AdaptiveDialogContainerTest` 覆盖 Dialog 的 scrim、内容区和底部操作区点击契约。设备手工验证还应至少覆盖：

- 普通窄屏、普通宽屏、竖向铰链展开、折叠外屏和 Tabletop 姿态切换
- 会话栏折叠/展开、单栏抽屉、宽屏 Dialog 与窄屏 BottomSheet
- 切换会话助手后标题、模型、搜索、推理、快捷消息及实际请求模型保持一致
- 设置等非聊天页面在宽屏下仍沿用原有全屏布局

---

## 14. 设计边界与平台回退

- 双栏只属于聊天主界面；设置、历史、统计等页面保持原有全屏逐页导航，这是当前职责边界，不是待补齐的自适应场景
- 折叠策略只使用 WindowManager 实际报告且通过窗口边界校验的 separating hinge；未报告铰链时按普通窗口处理，不根据机型、分辨率或屏幕比例猜测
- `chat_sidebar_expanded` 是持久化的用户偏好；普通宽屏恢复时沿用上次状态，竖向分隔铰链场景则无条件显示两侧面板
- Tabletop 需要有效横向铰链坐标才能裁切到上半屏；只有姿态而没有坐标时采用安全回退：单栏 + Dialog，但不执行位置猜测
- Dialog 分支不组合 BottomSheet，调用方必须维护自己的可见状态；传入的 `sheetState` 只对 BottomSheet 分支有实际 UI 含义

---

## 15. 子助手 UI

Assistant 配置页提供 Target 类别、全局可见与 Caller 访问范围设置；关闭 Target 类别时会原子清理全局可见和反向授权。普通选择器默认隐藏 Target，可通过筛选显式显示；搜索同时匹配名称与路由描述。

主聊天把 `assistant_call` 渲染为独立 `SubAssistantCallCard`。卡片显示 Target、request、运行状态、有界文本预览和桥接的 `ask_user`，整卡进入 `SubAssistantDetail(masterConversationId, runId)`。详情页校验 run 与 Child 关系，并通过 `ChatMessage(readOnly = true)` 与不提供输入区来禁止修改型交互。

TTS 控制条由当前 worker 的 `isSpeaking` 决定可见性，暂停不隐藏。暂停优先于底层播放器状态。同 turn 新内容继续入队，新 turn 替换整条队列；`stop` 释放所有权。控制条只在当前播放来源为 Target 且该 Assistant 开启 `useAssistantAvatar` 时显示 Target 头像。

完整配置、执行状态、详情解析和生命周期见 [sub-assistant-architecture.md](sub-assistant-architecture.md)。

---

## 附录：相关设计资料

- [Material 3 Canonical layouts](https://m3.material.io/foundations/layout/canonical-layouts/list-detail)
- [Android Developers: Support different screen sizes](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-screen-sizes)
- [Android Developers: Navigation 3](https://developer.android.com/guide/navigation/navigation-3)
- [Material 3 Adaptive](https://m3.material.io/develop/android/jetpack-compose/adaptive-layouts)
