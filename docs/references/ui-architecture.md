# 界面架构与自适应布局参考

> 本文档以 Measix Pilot 当前代码为准，完整描述项目的 UI 架构层次、导航体系、主题系统、自适应布局策略以及核心组件流程。
> 文档同时记录了窄屏 / 宽屏 / 折叠屏三种形态下的实际适配方案，供后续界面迭代参考。

---

## 1. 架构总览

```
RouteActivity (ComponentActivity)
  └─ MeasixTheme (主题入口)
       └─ AppRoutes() (导航根)
            ├─ CompositionLocalProvider (全局状态注入)
            │    ├─ LocalAdaptiveLayoutInfo  — 窗口尺寸/折叠姿态/布局策略
            │    ├─ LocalNavController       — 导航控制器
            │    ├─ LocalSettings            — 全局设置
            │    ├─ LocalSharedTransitionScope — 共享元素动画
            │    ├─ LocalToaster / LocalTTSState / LocalASRState
            │    └─ LocalDarkMode / LocalExtendColors
            └─ NavDisplay (Navigation 3)
                 └─ entry<Screen.*> (全屏逐页导航, 45 个路由)
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

- `Screen` 是 `sealed interface : NavKey`，共 45 个路由（均为 `@Serializable` data class/object）
- 路由以**全屏页面切换**为主，`fadeIn/fadeOut` 用于根级切换，`slideInHorizontally + scaleOut` 用于层级切换
- `entryDecorators` 包含 `rememberSaveableStateHolderNavEntryDecorator()` 和 `rememberViewModelStoreNavEntryDecorator()`，保证页面状态保持与 ViewModel 生命周期正确

### 2.3 路由清单

| 分类 | Screen 路由 |
|------|-----------|
| 聊天 | `Chat(id, text, files, nodeId)` |
| 分享 | `ShareHandler(text, streamUri)` |
| 历史/收藏 | `History`, `Favorite`, `Stats`, `MessageSearch` |
| 助手 | `Assistant`, `AssistantDetail(id)`, `AssistantBasic`, `AssistantPrompt`, `AssistantMemory`, `AssistantRequest`, `AssistantMcp`, `AssistantLocalTool`, `AssistantInjections` |
| 设置 | `Setting`, `SettingPreferences`, `SettingPreferencesTheme`, `SettingPreferencesNotification`, `SettingPreferencesGeneral`, `SettingPreferencesUI`, `SettingTheme`, `SettingProvider`, `SettingProviderDetail(providerId)`, `SettingModels`, `SettingSearch`, `SettingSearchDetail(serviceId)`, `SettingSpeech`, `SettingMcp`, `SettingFiles`, `SettingAbout` |
| 扩展 | `Extensions`, `QuickMessages`, `Prompts`, `Skills`, `SkillDetail(skillName)`, `Workspaces`, `WorkspaceDetail(id)`, `WorkspaceTerminal(id)`, `WorkspaceFileEditor(id, area, path)` |
| 其他 | `Backup`, `ImageGen`, `WebView(url, contentId)`, `Debug`, `Log` |

---

## 3. 主题系统

### 3.1 MeasixTheme

```
MeasixTheme(colorMode)
  ├─ 读取 Settings (dynamicColor, themeId, customThemes, amoledDarkMode)
  ├─ 颜色方案选择：
  │    ├─ 动态色 (S+): dynamicLight/DarkColorScheme(context)
  │    └─ 预设/自定义: findThemeById → getColorScheme(dark)
  ├─ AMOLED 暗色: background + surface → #000000 (仅 dark + amoled 时)
  ├─ 状态栏图标: isAppearanceLightStatusBars/Bars = !darkTheme
  └─ MaterialExpressiveTheme(colorScheme, typography, motionScheme.expressive())
```

### 3.2 颜色模式

```kotlin
enum class ColorMode { SYSTEM, LIGHT, DARK }
```

- `SYSTEM` 跟随系统暗色模式
- AMOLED 暗色模式在普通暗色基础上将 `background` 和 `surface` 设为纯黑，其他 tonal 色（`surfaceContainerHighest` 等）不受影响
- **注意**：AMOLED 下 `surfaceColorAtElevation(8.dp)` 会变纯黑导致选中项不可辨；应使用 `surfaceContainerHighest` 等 tonal 色替代

### 3.3 预设主题

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

### 3.4 扩展颜色

`LocalExtendColors` 提供主题无关的扩展色板（如代码高亮色、Markdown 元素色），定义在 `Color.kt`。

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
) {
    val widthClass: AdaptiveWidthClass          // Compact/Medium/Expanded/Large/ExtraLarge
    val hasSeparatingVerticalHinge: Boolean     // 是否有竖向分隔铰链
    val isTabletop: Boolean                      // 是否为桌面半折姿态
    val chatLayoutMode: ChatLayoutMode           // SinglePane / ListDetail
    val useExpandedModal: Boolean                // 居中 Dialog vs 底部 Sheet
    val canCollapseChatSidebar: Boolean          // 侧栏是否可折叠
    val useCompactChatInput: Boolean             // 紧凑输入模式
    val listPaneWidth: Dp                        // 侧栏宽度 (300dp / 360dp)
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

### 4.4 Material 3 Window Size Class 对照

| Width Class | dp 范围 | Material 默认 pane 数 | 项目 chatLayoutMode |
|-------------|---------|----------------------|-------------------|
| Compact | 0–599 | 1 | SinglePane |
| Medium | 600–839 | 1（推荐）或 2 | **ListDetail**（项目放宽） |
| Expanded | 840–1199 | 2 | ListDetail |
| Large | 1200–1599 | 2 | ListDetail |
| ExtraLarge | 1600+ | 2 | ListDetail |

项目将 Medium 档（600dp）就允许双栏，低于 Material 默认的 840dp。原因：国内主流折叠屏展开内屏宽度集中在 916–962dp（详见附录 A），全部位于 Expanded 档。采用 600dp 阈值让 600–840dp 的窗口（如 7–8 英寸横屏平板）也能启用双栏。

### 4.5 核心策略函数

```kotlin
// 聊天布局模式
chatLayoutMode(widthDp, heightDp, isTabletop):
  if (isTabletop || heightDp < 480) → SinglePane
  if (widthDp >= 600) → ListDetail
  else → SinglePane

// 弹层模式（与 chatLayoutMode 完全一致）
useExpandedModal(widthDp, heightDp, isTabletop):
  widthDp >= 600 && heightDp >= 480 && !isTabletop

// 紧凑输入（矮横屏）
useCompactChatInput(heightDp): heightDp < 480

// 侧栏可折叠（铰链场景不可折叠）
canCollapseChatSidebar(widthDp, heightDp, hasHinge, isTabletop):
  chatLayoutMode == ListDetail && !hasSeparatingVerticalHinge
```

### 4.6 布局常量（AdaptiveLayoutDefaults）

| 常量 | 值 | 用途 |
|------|-----|------|
| `ReadableContentMaxWidth` | 840.dp | 消息/输入框最大可读宽度 |
| `SheetMaxWidth` | 640.dp | 居中 Dialog 默认最大宽度 |
| `SheetMaxHeight` | 760.dp | 居中 Dialog 默认最大高度 |
| `ListPaneWidth` | 300.dp | Medium/Expanded 侧栏宽度（与 ModalDrawerSheet 一致） |
| `WideListPaneWidth` | 360.dp | Large/ExtraLarge 侧栏宽度 |
| `HingePaneMinWidth` | 320.dp | 铰链场景最小面板宽度 |
| `DialogPadding` | 24.dp | Dialog 外边距 |

### 4.7 AdaptiveModal

聊天上下文中的临时选择器使用 `AdaptiveModal` 包裹：

- **宽屏**（`useExpandedModal = true`）：居中 `Dialog`，限制最大宽高，铰链场景靠右半屏
- **窄屏**：`ModalBottomSheet`，贴底弹层

已迁移到 AdaptiveModal 的 10 个高频 picker：
`AssistantPicker`、`ModelList`、`FilesPicker`、`McpPicker`、`SearchPicker`、`ReasoningPicker`、`WorkspaceSelectSheet`、`WorkspaceCwdPicker`、`ExtensionContent`、`Export`

### 4.8 AdaptiveDialogContainer

全屏 Dialog 容器，解决 Compose 平台 outside-click 无法检测自定义全屏 Dialog 外部点击的问题。采用**显式 scrim 双层方案**：

- 底层 scrim：铺满整个窗口的 `clickable` 层，任何落到其上的点击触发 `onDismissRequest`
- 上层内容层：`clipToBounds()` 防止浮动工具栏等子内容溢出 Dialog 边界；内部通过 `detectTapGestures` 吸收落在表面空白区的点击，使它们不会穿透到 scrim；子控件（按钮/输入框/分段按钮/滚动区）优先消费各自手势，不受影响

> 曾用手写坐标 hit-test（`PointerEventPass.Initial` + `onGloballyPositioned` 记录内容边界）判断点击是否在内容区外，但 `fillMaxHeight`/`weight` 布局的实际渲染会溢出内容测量边界，导致底部操作行（保存/确认/筛选输入框）在部分窗口尺寸下被误判为外部点击——保存失效或弹窗误关闭。回归测试见 `AdaptiveDialogContainerTest`。

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

### 5.2 侧栏折叠动画

采用**宽度动画**（`animateDpAsState`）而非 `ListDetailPaneScaffold` 或 `AnimatedVisibility`：

- `ListDetailPaneScaffold` 的 `adaptStrategies` 默认"有空间就展示所有 pane"，用户手动折叠后 pane 仍被强制展开
- `AnimatedVisibility` 在 Row 中 exit 动画期间内容仍占满宽度，表现为"先留白占位再消失"
- **正确方案**：侧栏 `Surface` 宽度在 `0.dp` 和 `listPaneWidth` 之间 `animateDpAsState` + `clipToBounds()`，宽度为 0 时仍保留组合维持状态（滚动位置等），内容被裁剪不可见

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
  ├─ DismissibleNavigationDrawer / ModalNavigationDrawer (根据 permanent 参数)
  └─ Column (body)
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
  │              ├─ 紧凑模式 (useCompactChatInput): 单行排列所有操作
  │              ├─ 正常模式: 附件预览 + 输入框 + 操作行
  │              └─ AdaptiveModal pickers (助手/模型/文件/MCP/搜索/推理/Workspace)
```

### 5.6 顶部/底部留白

| 位置 | 值 | 说明 |
|------|-----|------|
| Scaffold `contentWindowInsets` | `WindowInsets(0)` | 让 TopAppBar 自己处理状态栏避让，避免双重留白 |
| ChatList contentPadding top | 0dp | TopAppBar 已提供间距 |
| ChatList contentPadding bottom | 24dp | 输入框上方滚动余量 |
| ChatDrawer body padding | horizontal 8dp | 侧栏左右边距 |
| ChatInput bottom padding | 4dp | 输入区底部边距 |

---

## 6. 组件层次

### 6.1 UI 组件目录

```
ui/components/
  ├─ ai/          — 聊天输入与选择器
  │    ├─ ChatInput.kt          (输入框, 紧凑/正常两种布局)
  │    ├─ AssistantPicker.kt    (助手选择 AdaptiveModal)
  │    ├─ ModelList.kt          (模型选择 AdaptiveModal)
  │    ├─ FilesPicker.kt        (文件选择 AdaptiveModal)
  │    ├─ McpPicker.kt          (MCP 选择 AdaptiveModal)
  │    ├─ SearchPicker.kt       (搜索引擎选择 AdaptiveModal)
  │    ├─ ReasoningPicker.kt    (推理等级选择 AdaptiveModal)
  │    ├─ WorkspaceSelectSheet.kt / WorkspaceCwdPicker.kt
  │    ├─ ExtensionContent.kt   (扩展面板 AdaptiveModal)
  │    ├─ Export.kt             (导出 AdaptiveModal)
  │    └─ completion/           (输入补全提供者)
  ├─ message/     — 消息渲染
  │    ├─ ChatMessage.kt        (单条消息容器)
  │    ├─ ChatMessageTools.kt   (工具调用/结果渲染, 含授权按钮)
  │    ├─ ChatMessageReasoning.kt (思考链折叠)
  │    ├─ ChatMessageBranch.kt  (消息分支切换)
  │    ├─ ChatMessageCopySheet.kt (复制面板)
  │    └─ tools/                (工具 UI 注册)
  ├─ richtext/    — 富文本渲染
  │    ├─ MarkdownNew.kt        (Markdown 渲染入口)
  │    ├─ HighlightCodeBlock.kt (代码高亮)
  │    ├─ LatexText.kt / MathBlock.kt (LaTeX)
  │    ├─ Mermaid.kt            (Mermaid 图表)
  │    ├─ DiffView.kt           (Diff 对比)
  │    └─ ZoomableAsyncImage.kt (可缩放图片)
  ├─ ui/          — 通用 UI 组件
  │    ├─ UIAvatar.kt           (头像, 支持文字/图片/渐变/emoji)
  │    ├─ Form.kt / Input.kt / TextArea.kt / Select.kt / Switch.kt
  │    ├─ CardGroup.kt / Tag.kt / TagList.kt
  │    ├─ UpdateCard.kt         (应用更新检查卡片)
  │    ├─ Greeting.kt           (问候语)
  │    ├─ JsonTree.kt           (JSON 树形查看器)
  │    ├─ DataTable.kt          (数据表格)
  │    └─ permission/           (权限管理)
  ├─ nav/         — 导航组件
  │    └─ BackButton.kt
  ├─ easteregg/   — 彩蛋
  │    └─ EmojiBurst.kt
  └─ webview/     — WebView 封装
```

### 6.2 页面目录

```
ui/pages/
  ├─ chat/        — 聊天主界面 (ChatPage, ChatDrawer, ChatList, ConversationList, ChatVM, ChatDrawerVM)
  ├─ setting/     — 设置页面群 (SettingPage 入口 + 15 个子页面)
  ├─ assistant/   — 助手配置 (AssistantPage 列表 + detail/ 8 个详情页)
  ├─ extensions/  — 扩展管理 (ExtensionsPage + skills/ + workspace/)
  ├─ history/     — 历史记录
  ├─ favorite/    — 收藏
  ├─ stats/       — 统计
  ├─ search/      — 消息搜索
  ├─ backup/      — 备份 (ImportExport / S3 / WebDav / Reminder)
  ├─ imggen/      — 图片生成
  ├─ log/         — 日志
  ├─ debug/       — 调试
  ├─ share/       — 分享处理
  └─ webview/     — WebView 页面
```

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
| `AssistantDetailVM` | 助手配置 |
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
- `ChatService` — 后台生成服务
- `ChatNotificationManager` — 通知管理（`createdAtStart = true` 保证进程启动即订阅事件）
- `AppEventBus` — 全局事件总线
- `LocalTools` — 本地工具集
- `TTSManager` / `SoundEffectPlayer` / `EmojiData`

### 7.3 更新检查状态流

```kotlin
class UpdateChecker(...) {
    val updateState: StateFlow<UpdateState> by lazy {
        checkForUpdates()
            .stateIn(AppScope, SharingStarted.WhileSubscribed, UpdateState.Loading)
    }
}
```

所有 `ChatVM` 共享同一 `UpdateChecker` 单例的 `updateState`，切换会话不会重复发起网络请求。

---

## 8. 屏幕适配方案

### 8.1 窄屏（手机竖屏 / Compact < 600dp）

| 方面 | 方案 |
|------|------|
| 聊天布局 | `ModalNavigationDrawer` + 单栏聊天 |
| 抽屉触发 | TopBar 汉堡按钮 `Menu03` |
| 弹层 | `ModalBottomSheet`（贴底） |
| 输入区 | 正常两行布局（附件预览 + 输入框 + 操作行） |
| 次要页面 | 全屏逐页导航 |
| 安全区 | `safeDrawingPadding` / 系统栏避让 |

### 8.2 宽屏（平板 / 桌面 / Expanded ≥ 840dp）

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

#### 展开态（~916–962dp，属 Expanded 档）

| 方面 | 方案 |
|------|------|
| 聊天布局 | 双栏，会话列表和聊天分别位于铰链两侧 |
| 侧栏折叠 | **不可折叠**（`canCollapseChatSidebar = false`），避免聊天面板跨越铰链 |
| 弹层 | `AdaptiveModal` 居中 Dialog，铰链场景限制到右半屏（`Alignment.CenterEnd`） |
| 弹层宽度 | `min(SheetMaxWidth, windowWidth/2 - DialogPadding)`，最小 `HingePaneMinWidth(320dp)` |

#### 折叠态（外屏 ~443dp，属 Compact 档）

与窄屏手机完全一致：单栏 + ModalNavigationDrawer + ModalBottomSheet。

#### 桌面半折（Tabletop）

`isTabletop = true` 时强制 `SinglePane`，不进入双栏模式，避免聊天内容跨越铰链。

### 8.4 矮横屏（heightDp < 480dp）

- `useCompactChatInput = true`：输入框各项（文本 + 模型 + 搜索 + 推理 + `+` + 语音 + 发送）排为同一行
- 功能不删减，恢复正常高度后回到原版上下两行结构
- 聊天保持单栏（`heightDp < 480` → `SinglePane`）

### 8.5 各形态适配矩阵

| 场景 | widthDp | heightDp | chatLayoutMode | 弹层 | 输入 | 侧栏折叠 |
|------|---------|----------|---------------|------|------|---------|
| 手机竖屏 | 390 | 844 | SinglePane | BottomSheet | 正常 | N/A |
| 手机横屏 | 844 | 390 | SinglePane | BottomSheet | 紧凑 | N/A |
| 折叠屏闭合 | 443 | 970 | SinglePane | BottomSheet | 正常 | N/A |
| 折叠屏展开 | 962 | 854 | ListDetail | Dialog | 正常 | 不可折叠 |
| 7寸平板横屏 | 600 | 480 | ListDetail | Dialog | 正常 | 可折叠 |
| 10寸平板 | 1280 | 800 | ListDetail | Dialog | 正常 | 可折叠 |
| 桌面半折 | 1000 | 800 | SinglePane | BottomSheet | 正常 | N/A |

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
- `AdaptiveModal` 居中 Dialog 使用 `safeDrawingPadding()` 确保不与系统栏重叠
- edge-to-edge 模式下 `disableNavigationBarContrast()` 关闭系统导航栏对比度强制

---

## 11. 消息渲染管线

消息渲染涉及以下组件层次：

```
ChatList (LazyColumn)
  └─ items: messageNodes
       └─ ChatMessage (单条消息容器, AnimatedVisibilityScope)
            ├─ ChatMessageAvatar (头像)
            ├─ ChatMessageBranch (分支切换, AnimatedContent)
            ├─ ChatMessageReasoning (思考链, 折叠/展开)
            ├─ ChatMessageCot (思维链文本)
            ├─ MarkdownNew (Markdown 渲染)
            │    ├─ HighlightCodeBlock (代码高亮, highlight 模块)
            │    ├─ MathBlock / LatexText (LaTeX)
            │    ├─ Mermaid (图表)
            │    ├─ DiffView (Diff 对比)
            │    ├─ ZoomableAsyncImage (图片)
            │    └─ SimpleHtmlBlock (HTML)
            ├─ ChatMessageTools (工具调用/结果)
            │    ├─ ToolUI 注册表
            │    └─ 授权按钮 (primaryContainer/errorContainer)
            ├─ ChatMessageActions (复制/重生成/编辑)
            └─ ChatMessageNerdLine (技术信息行)
```

消息生成链路的完整描述见 [消息生成链路](chat-generation-pipeline.md)。

---

## 12. Message Transformer 管道

消息在发送前和接收后经过 Transformer 管道处理：

### 输入管道（InputMessageTransformer）

| Transformer | 作用 |
|-------------|------|
| TemplateTransformer | 应用 Pebble 模板（时间/日期变量） |
| DocumentAsPromptTransformer | 文档附件转文本提示 |
| OcrTransformer | 图片 OCR 文字提取 |
| Base64ImageToLocalFileTransformer | Base64 图片转本地文件引用 |

### 输出管道（OutputMessageTransformer）

| Transformer | 作用 |
|-------------|------|
| ThinkTagTransformer | 提取 `<think>` 标签转为推理部分 |
| RegexOutputTransformer | 正则替换助手响应 |
| OcrTransformer | 生成结束后 OCR 处理 |

输出 Transformer 支持 `visualTransform()`（流式显示期间）和 `onGenerationFinish()`（生成结束后最终处理）。

---

## 13. 测试

### 自适应策略测试

`AdaptiveLayoutPolicyTest`（253 行）覆盖：

- Width Class 边界值（599/600/840/1200/1600）
- 手机竖屏 SinglePane
- 矮横屏 SinglePane + 紧凑输入
- 折叠屏展开 ListDetail（vivo X Fold3 Pro ~962dp / Samsung Z Fold6 ~924dp）
- 平板 ListDetail
- 铰链不影响 chatLayoutMode 但阻止侧栏折叠
- Tabletop 强制 SinglePane
- useExpandedModal 与 chatLayoutMode 边界一致
- 600dp/480dp 精确边界值

---

## 14. 已知限制

| # | 问题 | 严重度 | 说明 |
|---|------|--------|------|
| 1 | 顶部留白以 TopAppBar 高度（~64dp + 状态栏 ~24dp = ~88dp）为主导 | P3 | TopAppBar 标准高度，无法在不影响状态栏避让的前提下进一步缩减 |
| 2 | 底部留白以 `navigationBarsPadding()`（~48dp 手势导航）为主导 | P3 | 系统导航栏避让，无法移除 |
| 3 | 设置页面保持全屏逐页导航，宽屏不限宽 | P3 | 按设计决策，设置页不纳入自适应改造 |
| 4 | 旋转后 `chat_sidebar_expanded` 可能残留折叠态 | P3 | LayoutMode 切换时未重置 |

---

## 附录 A：折叠屏展开宽度计算

| 机型 | 内屏分辨率 | ppi | 展开宽 (dp) | 档位 |
|------|-----------|-----|-----------|------|
| vivo X Fold3 Pro | 2480×2200 | ~412 | ~962 | Expanded |
| Samsung Galaxy Z Fold6 | 2160×1856 | 374 | ~924 | Expanded |
| Huawei Mate X5 | 2496×2224 | ~426 | ~937 | Expanded |
| OPPO Find N3 | 2268×2440 | 426 | ~916 | Expanded |
| Xiaomi MIX Fold 4 | 2488×2248 | ~418 | ~952 | Expanded |

所有 2024 年前后上市的主流折叠屏展开内屏宽度均在 Expanded 档（840–1199dp，集中在 916–962dp）。

## 附录 B：设计依据

- [Material 3 Canonical layouts](https://m3.material.io/foundations/layout/canonical-layouts/list-detail)
- [Android Developers: Support different screen sizes](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-screen-sizes)
- [Android Developers: Navigation 3](https://developer.android.com/guide/navigation/navigation-3)
- [Material 3 Adaptive](https://m3.material.io/develop/android/jetpack-compose/adaptive-layouts)
