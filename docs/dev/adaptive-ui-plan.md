# 自适应界面架构与交互约束

## 文档定位

本文档是自适应界面改造的**需求、实现记录与反思**。第一版（0.0.14 自适应改造）已于 `c7adc3ed` / `a3b24793` 落地；经历模拟器实测与代码审查后，2026-08-10 补充了 **"过度设计调研与回滚分析"** 章节（见文末），修正对宽屏适配范围的认识。文中"当前实现"指 HEAD 状态，"回滚基线"指 `ae895469`（自适应改造前）。本文档经过代码与官方资料逐项核对，所有阈值、文件数、组件名与实测数据均可在"代码与资料核对记录"章节追溯。

## 目标与非目标

Measix Pilot 的主流程是"选择会话—持续对话—按需进入助手与工具配置"。自适应改造以窗口的实际可用宽高和折叠姿态为输入，而不是依赖"手机/平板"名称或横竖屏判断。

改造必须同时满足以下约束：

- 原有功能入口、数据语义和配置能力保持不变；自适应只改变承载方式，不迁移或删减功能。
- 聊天主界面最多两栏，不设计永久三栏模式。
- 旋转、分屏、折叠和展开只重排界面，不丢失当前会话、输入草稿、滚动位置或导航状态。
- 物理铰链、系统栏和显示切口始终作为不可用区域处理。

**修订认知（2026-08-10）**：以上约束对**聊天主界面**是刚性的；但对历史、收藏、统计、设置、备份、扩展等**次要页面**，"不因宽度变化而改变承载方式"才是更合理的默认，它们的自适应收益低、回归风险高（详见文末调研章节）。

---

## v3 重新实现记录（2026-08-10，从 `ae895469` 基线）

### 起点与决策

v1 的两轮提交（`c7adc3ed` 70 files / `a3b24793` 40 files）引入了设置场景化 Dialog、全局 ListDetailSceneStrategy、AdaptiveContentContainer、34 个文件 AdaptiveModal 扩散等过度设计。v2 调研确认这些问题后，v3 从 `ae895469` 干净基线出发，仅保留经验证有价值的部分。

### 保留的组件（经 v1/v2 验证正确）

| 组件 | 文件 | 说明 |
|---|---|---|
| `AdaptiveLayout.kt` | 新建 | 纯函数策略 `AdaptiveLayoutPolicy`：`widthClass`、`chatLayoutMode`、`useExpandedModal`、`useCompactChatInput`、`canCollapseChatSidebar`；`AdaptiveLayoutInfo` 数据类；`LocalAdaptiveLayoutInfo` CompositionLocal；`AdaptiveLayoutDefaults` 常量 |
| `AdaptiveModal.kt` | 新建 | ≥600dp 宽且 ≥480dp 高时用居中 Dialog，否则 `ModalBottomSheet`；铰链场景限制到右半屏 |
| `AdaptiveDialogContainer.kt` | 新建 | 全屏 Dialog 容器，内层 `clipToBounds()` 防溢出，`PointerEventPass.Initial` 实现外部点击关闭 |
| `ChatPage.kt` | 从 v1 迁移 | `Row + animateDpAsState + clipToBounds` 双栏；`sidebarExpanded` 持久化；`PanelLeftOpen`/`PanelLeftClose` 展开/折叠按钮 |
| `ChatDrawer.kt` | 从 v1 迁移 | `permanent` 模式 + `onCollapse` 回调；`statusBarsPadding` + `navigateFromDrawer` |
| `ChatInput.kt` | 从 v1 迁移 | `useCompactChatInput` 矮屏单行紧凑布局 |
| `ChatList.kt` | 从 v1 迁移 | `widthIn(max = ReadableContentMaxWidth)` 可读宽度限制 |
| `RouteActivity.kt` | 基线 +4 行 | 仅添加 `rememberAdaptiveLayoutInfo()` 和 `LocalAdaptiveLayoutInfo provides` |

### 移除的组件（v1 过度设计）

| 组件 | 原因 |
|---|---|
| `AdaptiveDialogScene.kt` | 设置场景化 Dialog 导致闪烁、多余返回按钮、留白过大、工具栏泄漏 |
| 全局 `ListDetailSceneStrategy` + 38 处 metadata | RouteActivity 从 599→768 行，只为支撑设置 dialog |
| `AdaptiveContentContainer` | 折叠屏（<1120dp）上 `fillMaxWidth` 占满，实际无限宽效果 |
| `AdaptiveNavigationPolicy` | 仅为设置 dialog 返回逻辑而存在 |
| `NavContext.navigateReplacingAfter` / `openSettings(includeDefaultDetail)` | 仅为设置 dialog 场景导航而存在 |
| `isConfigurationScreen` 40+ Screen `when` 分支 | 配置场景判定逻辑 |
| `listDetailDirective` / `maximumHorizontalPartitions` | 为 ListDetailSceneStrategy 提供 directive |
| `useConfigurationDialog` | 控制设置 dialog 的策略函数 |
| `WideContentMaxWidth` / `MinimumDialogHeight` | 随 AdaptiveContentContainer / 旧 useExpandedModal 移除 |

### 高频 picker AdaptiveModal 迁移（10 文件，保留）

聊天上下文中直接使用的选择器迁移到 `AdaptiveModal`，宽屏居中 Dialog，窄屏维持 `ModalBottomSheet`：

- `AssistantPicker.kt`、`ModelList.kt`、`FilesPicker.kt`、`McpPicker.kt`、`SearchPicker.kt`、`ReasoningPicker.kt`、`WorkspaceSelectSheet.kt`、`WorkspaceCwdPicker.kt`、`ExtensionContent.kt`、`Export.kt`

### 回退到基线的文件（18 文件）

设置页面（SettingMcpPage、SettingSpeechPage、SettingProviderDetailPage、SettingThemePage、SettingFilesPage、SettingModelPromptPage）、非核心页面（ImgGenPage、WebViewPage、ProviderConnectionTester、AssistantPage、ShareHandlerPage）、无关修复（UpdateCard、EmojiBurst、DataTable、CardGroup、Switch、ChatVM）全部回退到 `ae895469` 基线。

### 策略阈值统一

v3 将 `useExpandedModal` 阈值从 v1 的 840dp/600dp 改为 600dp/480dp，与 `chatLayoutMode` 完全一致：

```
chatLayoutMode:   widthDp >= 600 && heightDp >= 480 && !isTabletop → ListDetail
useExpandedModal: widthDp >= 600 && heightDp >= 480 && !isTabletop → 居中 Dialog
useCompactChatInput: heightDp < 480 → 紧凑输入
canCollapseChatSidebar: chatLayoutMode == ListDetail && !hasSeparatingVerticalHinge
```

这确保了"双栏聊天 = 居中弹层"的一致体验。v1 的 840dp 阈值导致 600–840dp 窗口（如折叠屏展开 ~700–800dp）聊天已双栏但弹层仍贴底。

### `chatLayoutMode` 简化

v1 的 `hasUsefulHingeSplit = hasSeparatingVerticalHinge && widthDp >= MediumWidthBreakpoint` 是 `hasUsefulWidth = widthDp >= MediumWidthBreakpoint` 的子集，`||` 运算使其成为死代码。v3 移除了 `hasSeparatingVerticalHinge` 参数——铰链仅影响 `canCollapseChatSidebar`（防止折叠后聊天跨越铰链），不影响是否进入双栏模式。

### AdaptiveLayoutDefaults 常量统一

v3 将所有布局常量集中到 `AdaptiveLayoutDefaults`：

```
ReadableContentMaxWidth = 840.dp    // 消息/输入框最大可读宽度
SheetMaxWidth = 640.dp              // 居中 Dialog 默认最大宽度
SheetMaxHeight = 760.dp             // 居中 Dialog 默认最大高度
ListPaneWidth = 300.dp              // Medium/Expanded 侧栏宽度（与 ModalDrawerSheet 一致）
WideListPaneWidth = 360.dp          // Large/ExtraLarge 侧栏宽度
HingePaneMinWidth = 320.dp          // 铰链场景最小面板宽度
DialogPadding = 24.dp               // Dialog 外边距
```

### 工具授权按钮改进

v1 使用 `FilledTonalIconButton`（28dp / 14dp 图标），两个按钮样式相同不醒目。v3 改为：
- 批准：`FilledTonalIconButton` + `primaryContainer`/`onPrimaryContainer` 色，36dp / 18dp 图标
- 拒绝：`FilledTonalIconButton` + `errorContainer`/`onErrorContainer` 色，36dp / 18dp 图标

保持圆形按钮形状（非椭圆），通过颜色区分语义，兼容主题配色。

### 顶部/底部留白调整

| 位置 | v1 | v3 | 说明 |
|---|---|---|---|
| ChatList contentPadding top | 16dp | 0dp | TopAppBar 已提供间距，消除双重留白 |
| ChatList contentPadding bottom | 48dp | 24dp | 减少输入框上方滚动余量 |
| ChatDrawer body padding | 8dp 全边 | horizontal 8dp + vertical 4dp | 减少侧栏上下留白 |
| ChatInput bottom padding | 8dp | 4dp | 减少输入区底部留白 |

### 最终变更规模

40 files changed, +1689 / -353（v1 为 110 files / +2556 / -807）。

### 验证结果

- ✅ `./gradlew assembleDebug` — BUILD SUCCESSFUL
- ✅ `./gradlew assembleRelease` — BUILD SUCCESSFUL
- ✅ `./gradlew :app:testDebugUnitTest` — AdaptiveLayoutPolicyTest 全通过（253 行测试）
- ✅ `./gradlew :app:lintDebug` — BUILD SUCCESSFUL
- ✅ 无 BOM 字符

### v3 已知限制

| # | 问题 | 严重度 | 说明 |
|---|---|---|---|
| 1 | 顶部留白仍以 TopAppBar 高度（~64dp + 状态栏 ~24dp = ~88dp）为主导 | P3 | TopAppBar 标准高度，无法在不影响状态栏避让的前提下进一步缩减 |
| 2 | 底部留白以 `navigationBarsPadding()`（~48dp 手势导航）为主导 | P3 | 系统导航栏避让，无法移除 |
| 3 | 设置页面保持全屏逐页导航，宽屏无限宽/居中 | P3 | 按设计决策，设置页不纳入自适应改造 |

---

## 布局决策

### 聊天主界面（核心，保留）

| 场景 | 聊天结构 |
|---|---|
| 紧凑窗口（< 600dp 宽） | 单栏聊天；会话历史使用模态抽屉 |
| 中等及以上窗口（≥ 600dp 宽且 ≥ 480dp 高） | 可折叠的会话列表 + 聊天双栏 |
| 有竖向分隔铰链 | 会话列表和聊天分别位于铰链两侧 |
| tabletop 横向半折 | 单栏聊天 |
| 手机短边横屏或矮窗口（高度 < 480dp） | 保持单栏 |

### 双栏阈值

双栏起点为 **600dp（Material Medium 档）**，而非 Material 默认的 840dp（Expanded 档）。原因：

- 国内主流折叠屏展开内屏宽度集中在 916–962dp（详见附录 A），**全部位于 Material Expanded 档（840–1199dp）**。回滚前 `isBigScreen` 用 `windowWidthDp ≥ 1100.dp`（仅大尺寸平板/桌面级窗口才触发），把折叠屏排除在外。这是本轮改造最直接的动机。
- 采用 Material 默认 Expanded 档（840dp）即可让所有折叠屏进入双栏；项目额外将 `chatLayoutMode` 的双栏门槛降到 Medium 档（600dp），目的是让 600–840dp 的窗口（如横屏平板的 7–8 英寸 8.0–8.4 英寸）也能在特定场景下启用双栏。

代码事实：`AdaptiveLayout.kt:88` `const val MediumWidthBreakpoint = 600f`、`:92` `const val MinimumDualPaneHeight = 480f`。

同时 `maximumHorizontalPartitions` 必须与 `chatLayoutMode` 对齐：当 `widthDp >= 600dp && !tabletop` 时强制允许 2 栏（`AdaptiveLayout.kt:188-191`）。否则 Material 的 `calculatePaneScaffoldDirective` 对 Medium 宽度默认返回 `maxHorizontalPartitions=1`，会导致 `ListDetailPaneScaffold` 强制单栏，与 `chatLayoutMode=ListDetail` 矛盾，出现"既非窄屏也非两栏、按钮消失"的异常状态。

### 会话侧栏折叠与展开动画（保留，经验正确）

会话侧栏的展开/收起使用 **宽度动画**（`animateDpAsState`），而非 `ListDetailPaneScaffold` 的 pane 状态机或 `AnimatedVisibility`（`ChatPage.kt:225-229`）：

- `ListDetailPaneScaffold` 的 `adaptStrategies` 默认策略是"有空间就展示所有 pane"，即使用户手动折叠，pane 仍会被强制 Expanded，导致折叠按钮无效。
- `AnimatedVisibility` 在 Row 中 exit 动画期间内容仍占满宽度，动画结束后才突然消失，表现为"先留白占位再消失"。
- 正确方案：侧栏 `Surface` 宽度由 `animateDpAsState` 在 `0.dp` 和 `listPaneWidth` 之间平滑动画（`ChatPage.kt:225-229`），配合 `Modifier.clipToBounds()` 裁剪溢出内容（`ChatPage.kt:235`）。折叠时宽度连续收窄到 0，展开时连续展开到目标宽度，detail 区域自动跟随扩展。

会话列表的展开状态由用户控制并持久化（SharedPreferences key `chat_sidebar_expanded`，默认展开；`ChatPage.kt:159`）；折叠后聊天占满可用宽度，顶部提供恢复列表的按钮（`navigationAction = ExpandSidebar`，`ChatPage.kt:267-272`）。分隔铰链场景不允许折叠列表（`canCollapseChatSidebar = false`，`AdaptiveLayout.kt:150`），避免单个聊天面板跨越铰链。

### 侧栏宽度（listPaneWidth）

代码 `AdaptiveLayout.kt:81`：

```kotlin
val listPaneWidth: Dp = when (widthClass) {
    AdaptiveWidthClass.Large, AdaptiveWidthClass.ExtraLarge -> 360.dp
    else -> 300.dp
}
```

- Compact/Medium/Expanded（< 1200dp）：320dp
- Large/ExtraLarge（≥ 1200dp）：360dp

### 展开与折叠图标（保留）

| 按钮 | 图标 | 箭头方向 | 说明 |
|---|---|---|---|
| 窄屏打开抽屉 | `Menu03`（汉堡菜单） | 无箭头 | 移动端惯例 |
| 宽屏展开侧栏 | `PanelLeftOpen` | 朝左 | 指向面板方向，与 VS Code/Android Studio 一致 |
| 宽屏折叠侧栏 | `PanelLeftClose` | 朝右 | 收起方向，与 VS Code/Android Studio 一致 |

图标方向遵循 Lucide/Material 主流惯例（收起=右箭头、展开=左箭头），未做反方向处理。

### 两栏间距（保留）

聊天双栏使用 `Row` 手动布局（非 `ListDetailPaneScaffold`），两栏之间不设额外 spacer——左侧会话栏的 `Surface(color = surfaceContainerLow)` 已用背景色区分左右栏。各 pane 内部 padding（会话栏 `padding(8.dp)`、详情区 content padding）提供内容边距。这比 `ListDetailPaneScaffold` 的 `horizontalPartitionSpacerSize` + pane 内 padding 叠加更紧凑。

---

## 次要页面自适应：过度设计调研与回滚分析（2026-08-10）

### 背景：两轮提交的实际波及范围

| 提交 | 内容 | 规模（`git show --stat` 实测） |
|---|---|---|
| `c7adc3ed` | 0.0.14 自适应改造（审查基线） | 70 files changed, 2155 insertions(+), 545 deletions(-) |
| `a3b24793` | 折叠屏修复、侧栏动画、顶部留白、编译警告与文档同步 | 40 files changed, 401 insertions(+), 262 deletions(-) |
| `ae895469` | 自适应改造前的代码基线（`fix(ai): 收敛多协议状态与模型适配`） | —— |

### 回滚基线（ae895469）的实际状态

代码事实（`git show ae895469:...` 实测）：

- **导航架构**：`RouteActivity`（**599** 行）单一 `NavDisplay`，**45** 个 `entry<Screen.*>` 全部为**全屏 Screen 切换**，`fadeIn/fadeOut` 过渡。
- **聊天页**：`ChatPage` 用 `isBigScreen = windowWidthDp > windowHeightDp && windowWidthDp >= 1100.dp` 判断（横向且 ≥1100dp），用 `PermanentNavigationDrawer`；否则 `ModalNavigationDrawer`。**折叠屏展开（~766–950dp）不会触发大屏模式**——这是本轮改造最核心的动机。
- **设置页**：全屏逐页导航（`SettingPage.kt` 的 `LazyColumn` 12+ 个 `navController.navigate(Screen.Setting*)`、`Screen.Assistant`、`Screen.Extensions`、`Screen.Backup`、`Screen.Mcp` 等），逐级返回。
- **临时操作**：所有 picker/弹层用 `ModalBottomSheet`（手机底部弹层）。
- **次要页面**（历史/收藏/统计等）：全屏 `Scaffold + LazyColumn`。

### 当前 HEAD 的过度改造清单

审查 + 模拟器实测后确认，以下改动属于**收益低或反效果**的过度设计：

#### 1. 设置界面场景化 dialog（反效果，P0）

- 实现：RouteActivity 用 `rememberListDetailSceneStrategy` + `rememberAdaptiveDialogSceneDecoratorStrategy`（`RouteActivity.kt:343-347`）双组合，由 `useConfigurationDialog`（`AdaptiveLayout.kt:135-141`）控制是否把配置场景包成 Dialog。
- `AdaptiveDialogScene.kt:70-114` 用 `OverlayScene` + `Dialog` 把整个配置场景包成居中 dialog，配合 `AdaptiveDialogContainer`（`AdaptiveDialogContainer.kt:37-64`）自实现 outside-click 命中测试（`PointerEventPass.Initial` + `boundsInParent`，**内层 Box 无 `clipToBounds()`**）。
- 标记 38 处 `adaptiveConfigurationListPaneMetadata` / `adaptiveConfigurationDetailPaneMetadata`（`git grep | wc -l` 实测）。
- 模拟器实测问题（Pixel 10 Pro Fold，Android 17，density 390，展开 2076×2152px = 851.7×882.9dp）：
  - **左栏切换闪烁**：点击"偏好设置"瞬间 dialog 整体半透明淡出、底层聊天界面透出，再淡入重建（实测 v_flash_b1.png 动画中间帧）。
  - **多余返回按钮**：进入"偏好设置 → 主题"后右栏左上角出现 `<` 返回按钮（`ListDetailSceneStrategy` 的 detail TopAppBar 自动插入），宽屏下无必要。
  - **留白过大**：实测 dialog 占据屏幕中间 [x≈40, y≈120] 到 [x≈1290, y≈2145]，宽 1250px ≈ 513dp（不是文档先前估算的 ~1100px ≈ 451dp）。左栏"设置"宽 ≈412px (169dp)，右栏"主题"宽 ≈838px (344dp)，左栏只占 dialog 总宽的 33%。
  - **工具栏泄漏**：`SettingProviderDetailPage` 的 `HorizontalFloatingToolbar`（align BottomCenter + offset）渲染到 dialog 之外。
- 结论：**设置页从全屏逐页导航改为 dialog 场景 + 双栏，没有带来可用性收益，反而引入了闪烁、多余导航、留白与泄漏**。

#### 2. 所有 picker/sheet 迁移到 AdaptiveModal（收益存疑，P2）

- 实现：**34** 个文件（`git grep AdaptiveModal\( -l | wc -l` 实测）把 `ModalBottomSheet` 换成 `AdaptiveModal`（`AdaptiveModal.kt:31-103`）。
- `AdaptiveModal` 的判定阈值由 `AdaptiveLayoutPolicy.useExpandedModal` 提供（`AdaptiveLayout.kt:147-154`）：
  ```
  widthDp >= ExpandedWidthBreakpoint(840dp) && heightDp >= MinimumDialogHeight(600dp) && !isTabletop
  ```
  即**只有 Expanded（840dp+）以上宽度才用居中 Dialog**，600–839dp 范围仍用 `ModalBottomSheet`。
- 分析：手机底部弹层在 600–839dp 窗口宽度下仍贴底；居中面板仅在 840dp+ 才有意义。**收益集中在 840dp+ 窗口的高频 picker**，对 JsonTree、ShareSheet、UIAvatar、S3Tab、WebDavTab 等低频弹层纯属无谓改动面。且 `AdaptiveModal` 内部用 `AdaptiveDialogContainer`（存在 P0 相同的 outside-click 判定风险）。

#### 3. 次要页面 metadata 标记（过度，P2）

- 实现：`History/Favorite/Stats/Search/Assistant*/Backup/Extensions/Skills/Workspaces/Log/Setting*` 等 20+ entry 全部加上 `adaptiveConfigurationDetailPaneMetadata` 或 listPane 标记（实测 38 处）。
- 分析：这些页面在回滚前是全屏单栏，改后被并入"配置场景"（dialog）或 ListDetail 场景。但它们的交互是"列表 → 点进聊天/详情 → 返回"，**不需要也不应该双栏**。

#### 4. 全局 ListDetailSceneStrategy + 大量页面属于配置场景（过度，P1）

- `RouteActivity` 从 599 行膨胀到 768 行（`wc -l` 实测），新增 `isConfigurationScreen`（`RouteActivity.kt:172`）、`AdaptiveNavigationPolicy.shouldDismissConfigurationOnBack`（`AdaptiveDialogScene.kt:47-53`）、`NavContext.Navigator.navigateReplacingAfter`（`NavContext.kt`）等一整套"配置场景返回层级"逻辑，只为支撑"设置 dialog 里三级导航"这一个价值存疑的交互。

### 各页面价值评估矩阵

| 页面 | 回滚前 | 当前 | 宽屏价值 | 结论 |
|---|---|---|---|---|
| 聊天主界面（会话列表+对话） | 折叠屏展开是单栏 | 双栏+可折叠侧栏+动画 | **高**（利用展开屏空间，主流程高频） | **保留** |
| 对话详情（消息列表+输入） | 单栏 | 双栏右侧 | **高**（伴随聊天双栏自然获得） | **保留** |
| 历史 / 收藏 | 全屏列表 | 全屏列表 | 低（列表本就适合全宽或限宽） | **回退** |
| 统计 | 全屏列表 | 全屏列表 | 低 | **回退** |
| 设置 | 全屏逐页 | dialog 场景+双栏 | **负**（闪烁/多余返回/留白） | **回退为逐页或简单居中限宽** |
| 助手配置（Assistant*） | 全屏逐页 | 配置场景 detail | 低（偶发低频） | **回退** |
| 备份 / 扩展 / Skills / Workspaces / Log | 全屏 | 配置场景 detail | 低 | **回退** |
| 所有 picker/sheet | 底部弹层 | 宽屏居中面板 | 中（仅 840dp+ 高频 picker） | **收敛范围** |
| 输入区（矮横屏单行） | 双行 | 单行紧凑 | 中 | **保留**（已实测有效） |

#### 关于"次要页面限宽"的实测复核

`AdaptiveContentContainer`（`AdaptiveLayout.kt:234-252`）实现：

```kotlin
Box(modifier.fillMaxSize()) {                       // 外层填满
    Box(modifier.widthIn(max = maxWidth).fillMaxWidth()) {  // 内层:最大 maxWidth，否则填满
        content()
    }
}
```

- 在 851.7dp 折叠屏展开态，`StatsPage` 用 `AdaptiveContentContainer(maxWidth = WideContentMaxWidth=1120dp)`，实际 `fillMaxWidth` 占满 851.7dp——**没有居中限宽效果**。
- 仅当窗口宽 ≥ 1120dp（Large/ExtraLarge）时，`widthIn(max=1120dp)` 才真正居中限宽到 1120dp。
- 故"次要页面限宽"在当前主流折叠屏（< 1120dp）上**实际无效**，回滚时此条不必保留。

---

## 从回滚视角：哪些事情真正值得做、怎么做

若以 `ae895469` 为起点重新做，**值得做的清单（按优先级）**：

### 必做（高收益、低风险）

1. **折叠屏展开态聊天双栏**（核心）
   - 方案：保留 `chatLayoutMode`（600dp 阈值 + 480dp 高度保护）与 `canCollapseChatSidebar` 纯函数策略；`ChatPage` 内用 `Row + animateDpAsState` 实现可折叠侧栏（当前实现正确，保留）。
   - 验证：折叠屏展开（~916–962dp）、手机竖屏、手机横屏、tabletop 四象限，用 JVM 测试 + 模拟器。
   - **唯一必须重构的部分是保留现有 ChatPage/ChatDrawer 实现，其余页面不纳入。**

2. **顶部留白修复**
   - 方案：`Scaffold(contentWindowInsets = WindowInsets(0))` + TopAppBar 自己避让状态栏（当前实现正确，保留）。
   - 注意：侧栏永久模式仍用 `statusBarsPadding()`，但**底部应补 `navigationBarsPadding()`** 或改回 `safeDrawingPadding()`，否则底部操作按钮与手势条重叠（模拟器实测按钮距屏底约 19px / 7.8dp < 手势区 ~58px / 23.8dp）。

3. **矮横屏输入区单行压缩**
   - 方案：`useCompactChatInput(heightDp < 480)` 时输入框各项排一行（当前实现正确，保留）。

4. **仅 840dp+ 窗口启用 AdaptiveModal 居中面板**
   - 方案：保留 `useExpandedModal` 阈值（widthDp ≥ 840 && heightDp ≥ 600 && !tabletop），只在 ≥840dp 的窗口把高频 picker（助手选择、模型选择等）改为居中面板；其他弹层保持 `ModalBottomSheet`。
   - 注意：`AdaptiveModal` 仍依赖 `AdaptiveDialogContainer`，需补 `clipToBounds()` 修复溢出。

### 不做（或大幅收敛）

5. **设置界面场景化 dialog** —— 回退为回滚前的全屏逐页导航；或仅做"宽屏下设置页居中限宽"（`AdaptiveContentContainer` 包 `SettingPage` 自身，不引入 `OverlayScene`/`Dialog`/ListDetail）。
6. **全局 ListDetailSceneStrategy + adaptiveConfiguration*PaneMetadata** —— 移除；聊天页用内部 Row 双栏，其他页面保持全屏。
7. **AdaptiveDialogScene / AdaptiveDialogContainer / AdaptiveNavigationPolicy / NavContext.navigateReplacingAfter** —— 全部回退，除非重新评估设置页双栏确有不可替代的价值。
8. **34 个文件全部迁移 AdaptiveModal** —— 只保留高频 picker（助手、模型、文件、MCP、搜索、推理、Workspace 选择），其余回退为 `ModalBottomSheet`。

### 当前 HEAD 中值得迁移复用的合理实现（记录）

以下组件/经验在"只保留聊天双栏"的新方向下依然成立，可抽取为公共库：

- `AdaptiveLayout.kt` 的 `AdaptiveWidthClass` / `ChatLayoutMode` / `AdaptiveLayoutPolicy` 纯函数策略 + `LocalAdaptiveLayoutInfo` CompositionLocal（根级统一提供窗口信息，页面不自行猜测）。
- `chatLayoutMode` 与 `maximumHorizontalPartitions` 对齐逻辑（600dp 阈值论证）。
- 侧栏 `animateDpAsState` 宽度动画 + `clipToBounds` + SharedPreferences 持久化（`chat_sidebar_expanded`）。
- `contentWindowInsets = WindowInsets(0)` + TopAppBar 状态栏自避让。
- `useCompactChatInput` 矮屏单行逻辑。

---

## 新会话引导卡片

新/空会话的引导卡片保持原有入口和判断逻辑：

| 入口 | 行为 | 宽屏返回/取消 |
|---|---|---|
| 当前助手 | 打开助手选择；编辑进入当前助手配置 | 选择后关闭面板；编辑页返回引导卡片 |
| 模型 | 打开当前助手可用模型选择 | 选择后关闭；返回或点外部取消 |
| MCP | 没有已配置且启用的 MCP 时进入全局 MCP 设置；已有可用 MCP 时进入当前助手 MCP 开关 | 直接配置页有返回；创建/编辑有取消和保存 |
| 记忆 | 进入当前助手的记忆配置 | 返回引导卡片 |
| 本地工具 | 进入当前助手本地工具配置 | 返回引导卡片，不依赖对话框 Context 查找 Activity |
| Workspace | 打开 Workspace 选择或进入配置 | 选择后关闭；配置页可返回 |

**修订**：宽屏下这些页面建议保持与窄屏相同的全屏导航（"设置对话框"方案回退后，此表不再依赖 `AdaptiveDialogScene`）。

## 输入框 `+` 面板

输入框 `+` 是临时任务面板，不是设置入口的替代品：

- 手机使用底部弹层（`AdaptiveModal` 在 600–839dp 仍走 `ModalBottomSheet`），宽屏（≥ 840dp）使用居中面板，功能列表一致。
- 只要全局存在已添加的 MCP，面板就显示 MCP 项；进入后控制的是当前助手启用的 MCP 和工具，不能跳转成无关的全局管理页。
- 文件、MCP、搜索、模型、推理、Workspace 等二级选择器覆盖在父面板之上；返回先关闭二级选择器并恢复父面板，再关闭 `+` 面板。
- 选择立即生效的项目在选择后关闭；需要编辑的项目提供保存/取消。点面板外部和系统返回均可取消临时操作。
- 面板内容必须可滚动；低高度横屏即使同时存在 Workspace、MCP、扩展和历史压缩，也不能裁掉后半部分入口。
- **修订**：宽屏居中面板的实现建议直接收敛为 `AdaptiveModal` 的简单版本（Dialog 居中），并**补 `clipToBounds()` 修复溢出**；不再与设置场景耦合。

## 内容、安全区与顶部节奏

- 聊天页 `Scaffold` 设置 `contentWindowInsets = WindowInsets(0)`，让 TopAppBar 自己处理状态栏避让，避免 Scaffold 与 TopAppBar 重复计算状态栏高度导致顶部留白过多。
- **修订**：永久会话栏使用 `statusBarsPadding()` + **`navigationBarsPadding()`**（或 `safeDrawingPadding()`），否则宽屏底部操作按钮与系统手势条重叠（实测按钮距屏底仅 19px / 7.8dp < 手势区 ~58px / 23.8dp）。
- 聊天消息、输入框及普通内容页限制最大可读宽度；图片和文件卡片按可用宽度计算网格列数。
- 可用高度低于 480dp 时，输入文本和原有模型、搜索、推理、`+`、语音、发送操作改排为同一行；功能不删减，恢复正常高度后回到原版上下两行结构。
- 临时面板在宽屏限制最大宽高并可滚动，在手机维持贴底体验。

## 状态与代码边界

- 根级 `AdaptiveLayoutInfo` 统一提供窗口尺寸（`LocalWindowInfo.current.containerDpSize`）、折叠姿态、聊天布局和弹层策略；业务页面不自行猜测设备类型。
- Navigation 3 只管理页面状态；自适应 scene 决定单页、列表—详情或配置对话框的呈现。
- 会话列表、聊天正文和配置内容各自保持单一实现，单栏/双栏不复制业务逻辑。
- 自适应策略是纯函数并配套单元测试（`AdaptiveLayoutPolicyTest`、`AdaptiveNavigationPolicyTest`、`NavigatorTest`），保证任何窗口最多两个 pane，且 `chatLayoutMode` 与 `maximumHorizontalPartitions` 对齐。
- 对话框场景显式传递宿主生命周期；需要 Activity 的权限、ViewModel 和窗口操作统一读取 `LocalActivity`，弹出菜单等窗口组件继续使用 Dialog Context，避免错误的窗口归属。
- 紧凑窗口从会话抽屉选择设置、历史、助手等目的地时，先关闭抽屉再导航，返回聊天后不会恢复成仍展开的抽屉。

## 已知问题与待修复清单（2026-08-10 实测）

| # | 问题 | 严重度 | 建议 |
|---|---|---|---|
| 1 | 设置 dialog 切换内容时整体半透明淡出重建（闪烁） | P0 | 回退设置场景化，或 `AdaptiveDialogContainer` 内层加 `clipToBounds()` + 内容 Crossfade |
| 2 | 设置 dialog 内 `HorizontalFloatingToolbar` 溢出 dialog 之外 | P0 | 同上 `clipToBounds()`；或工具栏改 Scaffold bottomBar |
| 3 | 宽屏侧栏底部操作按钮与系统手势条重叠（距屏底 19px ≈ 7.8dp） | P1 | permanent 模式追加 `navigationBarsPadding()` |
| 4 | 设置右栏左上多余返回按钮 | P2 | 宽屏双栏时隐藏 detail TopAppBar Back |
| 5 | 设置 dialog 左右留白过大（左栏仅占 33%，dialog 总宽 1250px ≈ 513dp） | P2 | 调整 dialog maxWidth 或减小 padding |
| 6 | 旋转后 `chat_sidebar_expanded` 残留折叠态 | P2 | LayoutMode 切换时评估重置 |
| 7 | `if (sidebarWidth > 0.dp)` 与"保留组合维持状态"注释矛盾 | P2 | 修正注释或改 `>= 0.dp` |
| 8 | 重复 import `spring`、unused import `listDetailDirective` | P3 | 清理 |
| 9 | `listDetailDirective` 完全无测试；480/600 边界值未精确测试 | P3 | 补测试 |
| 10 | 字符串 zh 弯引号被改直引号、ja/ko/ru `\' \'` 多空格 | P3 | 恢复原文 |

## 验证结果与回归矩阵

### 已实测验证（模拟器 Pixel 10 Pro Fold, Android 17, density 390）

| 场景 | 结果 |
|---|---|
| 折叠屏展开（2076×2152px = 851.7×882.9dp）双栏 | ✅ 会话栏+聊天双栏；侧栏 surfaceContainerLow 区分；emoji 正确 |
| 折叠屏 CLOSED（443×970dp） | ✅ 单栏 + Menu03 汉堡菜单；TopBar 无重复留白 |
| 侧栏折叠动画 | ✅ spring 平滑收窄到 0；展开按钮 PanelLeftOpen 出现；无负宽度崩溃 |
| 设置页（展开态） | ❌ 切换闪烁、右栏多余返回按钮、留白过大（dialog 1250px ≈ 513dp，左栏仅 33%）、工具栏泄漏（见已知问题清单） |
| 矮横屏（1600×600px）输入区 | ✅ 单行紧凑布局生效（wm size 修改有 artifact，仅作参考） |

### 仍需验证（回退方案落地后）

- 回退设置 dialog 后：窄屏设置逐页导航、折叠屏设置居中限宽、返回链路。
- 收敛 AdaptiveModal 后：高频 picker 宽屏居中面板 + 窄屏底部弹层双路径。
- 侧栏底部 padding 修复后：手势条不遮挡按钮、左右栏底部对齐。

### JVM 测试

- `AdaptiveLayoutPolicyTest`、`AdaptiveNavigationPolicyTest`、`NavigatorTest` 覆盖策略纯函数；**`listDetailDirective` 与 480/600 精确边界值待补**。
- 每次布局变更执行策略与导航 JVM 测试、Android Lint、Debug 构建，并在紧凑窗口和展开折叠屏各走一遍新会话引导、`+` 面板和设置三级导航。

---

## 附录 A：折叠屏展开宽度计算（实测计算式）

| 机型 | 内屏分辨率 | ppi | 宽度计算 | 展开宽 (dp) | 档位 |
|---|---|---|---|---|---|
| vivo X Fold3 Pro（2024.3 上市） | 2480×2200 | 约 412 | 2480 × 160 / 412 | ≈ 962 | Expanded |
| Samsung Galaxy Z Fold6（2024.7 上市） | 2160×1856 | 374 | 2160 × 160 / 374 | ≈ 924 | Expanded |
| Huawei Mate X5（2023.9 上市） | 2496×2224 | 约 426 | 2496 × 160 / 426 | ≈ 937 | Expanded |
| OPPO Find N3（2023.10 上市） | 2268×2440 | 426 | 2440 × 160 / 426 | ≈ 916 | Expanded |
| Xiaomi MIX Fold 4（2024.7 上市） | 2488×2248 | 约 418 | 2488 × 160 / 418 | ≈ 952 | Expanded |

**结论**：所有 2024 年前后上市的主流折叠屏展开内屏宽度均在 **Expanded 档（840–1199dp，集中在 916–962dp）**。Material 默认 840dp 阈值足以覆盖所有折叠屏，让它们进入双栏。**附录 A 表中数字基于厂商公布分辨率 + ppi 计算**。

> 注：早期文档（v1）中"~766dp"等数字来源不明，与厂商官方分辨率+ppi 计算的 916–962dp 不一致；本文档采用厂商官方分辨率+ppi 重新计算。

---

## 附录 B：Material 3 Window Size Class 与代码阈值对照（官方核对）

来源：[Canonical layouts – Material Design 3](https://m3.material.io/foundations/layout/canonical-layouts/list-detail)

| Material 3 Width Class | dp 范围 | 默认 pane 数 | 代码 `AdaptiveLayout.kt` 常量 |
|---|---|---|---|
| Compact | 0–599 | 1 pane | `widthDp < MediumWidthBreakpoint(600f)` → Compact |
| Medium | 600–839 | 1（推荐）或 2 | `600 ≤ widthDp < 840` → Medium |
| Expanded | 840–1199 | 2 panes | `840 ≤ widthDp < 1200` → Expanded |
| Large | 1200–1599 | 2 panes | `1200 ≤ widthDp < 1600` → Large |
| Extra-large | 1600+ | 2 panes | `widthDp ≥ 1600` → ExtraLarge |

代码常量（`AdaptiveLayout.kt:87-92`）：
```
MediumWidthBreakpoint = 600f
ExpandedWidthBreakpoint = 840f
LargeWidthBreakpoint = 1200f
ExtraLargeWidthBreakpoint = 1600f
MinimumDualPaneHeight = 480f
MinimumDialogHeight = 600f
```

**结论**：代码阈值与 Material 3 官方定义完全一致；项目自定义 `chatLayoutMode` 在 Medium 档就允许双栏（Material 默认 Medium 推荐 1），这是为折叠屏展开态做合理放宽。

---

## 附录 C：代码与资料核对记录（v2 修订追溯）

本版本相对 v1 修正的事实：

| 修正项 | v1 描述 | 实际事实（实测/官方资料） | 修订 |
|---|---|---|---|
| 回滚前 entry 数量 | 44 个 | 45 个（`git show ae895469:RouteActivity.kt \| grep entry` 实测） | 45 |
| AdaptiveModal 使用文件数 | 35 个 | 34 个（`git grep AdaptiveModal\( -l \| wc -l`） | 34 |
| AdaptiveModal 阈值 | "600dp 宽窗上确实显得过宽，居中面板有合理性" | `useExpandedModal` 阈值是 `widthDp ≥ 840dp` 且 `heightDp ≥ 600dp` | 840dp 才居中；600–839dp 仍用 ModalBottomSheet |
| 设置 dialog 总宽 | ~1100px | 实测 1250px ≈ 513dp（截图 v_theme_final.png 实测） | 513dp |
| adaptiveConfiguration 标记数 | "20+" | 38 处 | 38 |
| AdaptiveNavigationPolicy 位置 | 隐含为单独文件 | 实际在 `AdaptiveDialogScene.kt:42-54` | 修正路径 |
| isConfigurationScreen 位置 | 未提及 | `RouteActivity.kt:172` | 补充 |
| navigateReplacingAfter 位置 | 隐含为 RouteActivity | 实际在 `NavContext.kt` | 修正路径 |
| 设置 dialog 实现 | "用 OverlayScene + Dialog 把整个设置导航场景包成居中 dialog" | 由 `RouteActivity` 的 `rememberListDetailSceneStrategy` + `rememberAdaptiveDialogSceneDecoratorStrategy` 双组合实现（`RouteActivity.kt:343-347`），受 `useConfigurationDialog` 控制 | 修正描述 |
| "次要页面限宽"的实际效果 | "值得保留" | `AdaptiveContentContainer` 在 < 1120dp 屏上 `fillMaxWidth` 占满，无居中限宽 | 改为"仅 ≥1120dp 才有效" |
| 折叠屏尺寸"~766dp"等 | 给出具体数字 | 各机型官方分辨率 + ppi 计算（附录 A 表） | 重写附录 |

---

## 附录 D：模拟器交互验证经验（2026-08-10 实测沉淀）

以下为在 Android Studio 模拟器（Pixel 10 Pro Fold / Android 17 / density 390）上验证自适应布局时沉淀的实操经验，供后续回归与验收参考。

### D.1 模拟器环境准备

- **SDK 位置**：本机为 `D:\Android\`，adb 与 emulator 命令加入 PATH：`$env:Path += ";D:\Android\platform-tools;D:\Android\emulator"`。
- **折叠屏 AVD**：`Pixel_10_Pro_Fold`（2024/2025 款）。展开内屏分辨率为 2076×2152px，density 390（dpi），换算约 **851.7×882.9dp**。
- **多 display 警告**：`adb shell screencap`/`uiautomator` 可能报 `"Multiple displays were found"`，折叠屏有主屏+副屏两个 display。需显式指定 display（见 D.2），否则截图可能取到错误的屏。

### D.2 可靠截图与 UI 结构提取

- **截图**：优先用 `adb shell screencap -p /sdcard/x.png` + `adb pull`，**避免 `exec-out >` 重定向**（PowerShell 会把二进制当文本处理，损坏 PNG 头）。截图默认存 `C:\Users\<user>\AppData\Local\Temp\`。
- **多 display**：若提示多个 display，用 `adb -s emulator-5554 shell screencap -p -d 0 /sdcard/x.png` 指定 display 0（主屏）。
- **UI 结构**：`adb shell uiautomator dump /sdcard/ui.xml` + `pull`，用 Python 正则逐 node 解析，提取 `bounds`/`text`/`content-desc`/`clickable`。
  - 经验：**逐 node 匹配比单条跨属性正则可靠**——先 `re.finditer(r'<node\b[^>]*?/>')` 分割节点，再分别匹配各属性，避免 `bounds` 与 `text` 属性顺序变化导致的组错位。
  - `content-desc` 为 null 的节点（如某些 IconButton）无文本可读，需靠坐标推算。

### D.3 触发折叠/展开与布局切换

- **折叠态（CLOSED）**：`adb shell cmd device_state state 0`。若模拟器未配 device_state，用 `wm size` 强制改分辨率（见 E.5 的坑）。
- **展开态（OPENED）**：`adb shell cmd device_state state 2`（需确认该 AVD 的状态号）。
- **矮横屏**：`adb shell wm size 1600x600` + `adb shell wm density 390`，实测输入区单行紧凑布局。
  - ⚠️ **wm size 有 artifact**：Android 17 上强制改 wm size 会导致部分 Compose 布局量测错位（如 ChatList 内容延伸到 bottomBar 下方），**不能作为真实 bug 的证据**，仅作趋势参考。真实横竖屏验证应旋转屏幕（`wm set-user-rotation`）或换 AVD。

### D.4 动画中间帧捕获

- **捕捉过渡帧**：连续 `screencap`（间隔 50–150ms）可捕捉动画中间态。例如侧栏折叠动画、设置 dialog 半透明淡出/淡入、页面切换。
- **时间点**：点击后立即截（`Start-Sleep -Milliseconds 50~120`）抓"刚进入"帧，再隔 1–3s 抓"稳定"帧。对比两者差异即可定位"闪烁/叠加/透出"类问题。
- **验证经验**：设置 dialog 切换闪烁，正是靠"点击瞬间帧（半透明）与稳定帧（不透明）"对比确认；工具栏泄漏则靠"稳定帧中 dialog 外仍有浮动按钮"确认。

### D.5 交互模拟

- **点击**：`adb shell input tap x y`（坐标为中心点）。
- **长按**：`adb shell input swipe x y x y 1500`（duration 1500ms）。
- **返回**：`adb shell input keyevent KEYCODE_BACK`；**Home**：`KEYCODE_HOME`。
- **坐标来源**：uiautomator dump 提取的 `bounds` 中心点；若无文本节点，用截图目测推算。
- **坑**：不同 density 下坐标是**像素**而非 dp；若改过 `wm density`，坐标随之变化，需重新 dump。

### D.6 应用启动/重置

- **冷启动**：`adb shell am force-stop <pkg>` + `adb shell am start -n <pkg>/.MainActivity`（或 `RouteActivity`）。
- **清数据重置**：`adb shell pm clear <pkg>`（重置 SharedPreferences，如 `chat_sidebar_expanded` 回到默认展开；同时验证初始双栏布局）。
- **包名**：Debug 变体带 `.debug` 后缀，如 `net.weero.measix.pilot.debug`（`applicationIdSuffix = ".debug"`）。
- **每次 layout 变更后**：`./gradlew assembleDebug`（约 18s）+ `adb install -r`。

### D.7 会话状态与滚动位置验证

- **滚动位置保持**：靠 `rememberLazyListState(initialFirstVisibleItemIndex = drawerVm.scrollIndex)` + `DisposableEffect`/`snapshotFlow` 写回 VM。实测折叠后展开滚动位置正确恢复。
- **验证方法**：折叠侧栏 → 展开 → 检查会话列表滚动位置未重置；旋转设备 → 回宽屏 → 检查 `chat_sidebar_expanded` 是否残留（已知问题 #6）。

---

## 附录 E：布局尺寸换算与量测经验（2026-08-10 实测沉淀）

### E.1 px 与 dp 换算

- 公式：**dp = px × 160 / density(dpi)**。
- 实例（density 390）：2076px → 851.7dp；1250px → 513dp；19px → 7.8dp；58px → 23.8dp。
- 常见误区：`wm density 390` 给出的 390 是 **dpi**，1dp = 390/160 px；**1px = 160/390 ≈ 0.41dp**。反过来 1dp ≈ 2.44px（density 390）。
- 屏幕实际可用 dp 需扣安全区（状态栏/导航栏/手势条），`wm size` 给出的是物理分辨率。

### E.2 density 与屏幕 dp 换算对照

| 设备 | 分辨率(px) | density(dpi) | 宽(dp) | 高(dp) |
|---|---|---|---|---|
| Pixel 10 Pro Fold 展开 | 2076×2152 | 390 | 851.7 | 882.9 |
| Pixel 10 Pro Fold CLOSED | 1080×2364 | 390 | 443.1 | 969.8 |
| 模拟器矮横屏 | 1600×600 | 390 | 656.4 | 246.2 |

（CLOSED 高需扣手势区后约为 443×970dp 文档值，实测 `wm size` 返回 1080×2364。）

### E.3 dialog/弹层实际尺寸量测

- **实测 dialog 边界**：从 UI dump 找 dialog 内最左/最右 `bounds`，或从截图目测。本设置 dialog：x∈[40, 1290]，宽 1250px ≈ **513dp**；左栏"设置"宽 412px ≈ **169dp（占 33%）**，右栏"主题"宽 838px ≈ **344dp（占 67%）**。
- **`AdaptiveContentContainer` 限宽行为**：`widthIn(max = maxWidth).fillMaxWidth()` 组合下，**窗口宽 < maxWidth 时 fillMaxWidth 占满，不居中限宽**；仅窗口宽 ≥ maxWidth（1120dp）才真正限宽。故折叠屏（<1120dp）上历史/统计页实际占满全屏，验证"限宽"需在 ≥1120dp 窗口做。
- **`ReadableContentMaxWidth = 840.dp`**：设置 dialog 与常规内容页最大宽 840dp；`WideContentMaxWidth = 1120.dp`（内容/AdaptiveContentContainer）；`SheetMaxWidth = 640.dp`（sheet）。

### E.4 屏幕尺寸测量技巧

- **像素级测量**：用 PIL 打开截图，`Image.crop` 放大目标区域（×4）再读图，可看清小图标/坐标。
- **比例法**：已知屏幕宽 2076px，量出目标元素的左右像素边界，即可算 dp 和百分比。
- **多帧对比**：同一交互截多帧，量变化量（如 dialog 宽度在动画中的变化）。

### E.5 布局调试的坑（务必避免）

- ⚠️ **`wm size` 会破坏真实布局**：强制改分辨率会触发 Compose 量测异常，产生假 artifact（内容溢出、控件错位）。**结论必须用真实旋转/换 AVD 复核，不能以 wm size 结果为准**。
- ⚠️ **PowerShell 二进制重定向**：`exec-out screencap -p > file.png` 会损坏图片，必须 `shell screencap` + `pull`。
- ⚠️ **uiautomator 多 display**：折叠屏要指定 `-d 0`，否则可能 dump 到错误的屏。
- ⚠️ **包名带 .debug 后缀**：`force-stop`/`start` 需用完整 `net.weero.measix.pilot.debug`。
- ⚠️ **改过 wm density 后坐标失效**：所有 input tap 坐标是像素，density 改变后必须重新 dump。

### E.6 折叠屏布局回归清单（速查）

1. 展开态（~852×883dp）：验证聊天双栏、侧栏折叠/展开、设置 dialog 交互。
2. CLOSED（~443×970dp）：验证单栏 + 汉堡菜单、无重复顶部留白。
3. 矮横屏（656×246dp，仅 wm size 参考）：验证输入区单行压缩。
4. 旋转/分屏：验证 `chat_sidebar_expanded` 残留、滚动位置。
5. ≥1120dp 大窗：验证 `AdaptiveContentContainer` 真正限宽、AdaptiveModal 居中面板。

---

## 设计依据

- Android Developers：Canonical layouts — <https://developer.android.com/develop/ui/views/layout/canonical-layouts>
- Android Developers：List-detail layout — <https://developer.android.com/develop/ui/views/layout/canonical-layouts#list-detail>
- Android Developers：Navigation 3 — <https://developer.android.com/guide/navigation/navigation-3>
- Android Developers：Navigation 3 dialog recipe —
  <https://developer.android.com/guide/navigation/navigation-3/recipes/dialog>
- Android Developers：Support different screen sizes —
  <https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-screen-sizes>
- Android Developers：Animate layout changes —
  <https://developer.android.com/develop/ui/compose/animation-quick-guide/animate-layout>
- Material 3 Adaptive — <https://m3.material.io/develop/android/jetpack-compose/adaptive-layouts>
- Material 3 Canonical layouts（List-detail） — <https://m3.material.io/foundations/layout/canonical-layouts/list-detail>

## 附录 F：本轮改造提交信息对照

| 提交 | 说明（`git log` 实测） | 处置建议 |
|---|---|---|
| `ae895469` | 自适应改造前基线（`fix(ai): 收敛多协议状态与模型适配`） | 回滚目标 |
| `c7adc3ed` | 0.0.14 自适应改造（70 files / +2155 / -545） | 保留聊天双栏部分，回退设置场景化/全局 ListDetail/AdaptiveModal 扩散 |
| `a3b24793` | 修复提交（40 files / +401 / -262） | 保留顶部留白、侧栏动画、矮屏单行输入、emoji 修复；回退随附的字符串与 lint 改动需复核 |