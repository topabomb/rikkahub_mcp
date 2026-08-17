# 多图浏览与图片查看器升级方案

> 目标：把「多张图片同屏」的界面统一到一套可复用的浏览体验上——点击任意图片进入全屏查看器，
> 从被点击的那张开始左右滑动浏览同组图片，并补齐优秀相册应用的常用手势（点按关闭、下滑拖拽关闭）。
> 本文档是该次迭代的实现依据与验收基准。

---

## 1. 背景与问题

### 1.1 现状盘点

全局唯一的大图查看器是 `ImagePreviewDialog`（`app/.../ui/components/ui/ImagePreviewDialog.kt`），
基于 `com.jvziyaoyao.scale` 的 `ImagePager` + `rememberZoomablePagerState`，本身已支持
双指缩放、双击缩放、放大后平移、多页 Pager 翻页，底部有保存按钮（`FilesManager.saveMessageImage`，
认 `data:` / `file:` / 绝对路径 / `http` 等来源）。

各图片界面的接入情况：

| 界面 | 入口组件 | 布局 | 点击行为 |
|------|----------|------|----------|
| 聊天消息顶层 Image part（用户多附件） | `ZoomableAsyncImage`（72dp 竖排） | 消息流内联 | 弹单张查看器 |
| 聊天 Markdown / HTML `<img>` | `ZoomableAsyncImage` | 正文流内联 | 弹单张查看器 |
| 工具步骤摘要的图片缩略行 | `ZoomableAsyncImage`（64dp 横向 `LazyRow`） | 工具卡内 | 每张弹单张查看器 |
| `generate_image` 工具卡 Preview | `ZoomableAsyncImage` 纵向列表 | 工具卡内 | 每张弹单张查看器 |
| 无专属渲染器的工具输出图片 | `DefaultToolPreview` 内 `ZoomableAsyncImage` | 工具详情内 | 每张弹单张查看器 |
| 文生图页生成结果 | 裸 `AsyncImage`（写死最多 2 张，VM 实际允许 1–4） | Row 网格 | 弹单张查看器 |
| 文生图页 Gallery | 裸 `AsyncImage` + Paging3 | 双列网格 | 弹单张查看器 |
| 文件管理 Upload / 文生图 Tab | `MediaThumb`（裸 `AsyncImage`） | 双列瀑布流 | 无任何响应 |
| Workspace 详情页 IMAGE | 直接调用 `ImagePreviewDialog` | 单张 | 单张查看器 |
| 助手背景预览 / 聊天背景 / 附件 chips | 裸 `AsyncImage` | 装饰 / 输入态 | 不可点开（保持） |

### 1.2 问题清单

1. **Pager 能力闲置**：查看器支持多图翻页，但所有调用点都传 `listOf(单张)`，且没有初始页参数。
   一轮多图（用户多附件、文生图多张结果、Gallery 网格）只能逐张点开逐张关闭。
2. **点按无响应**：查看器内 `dismissOnClickOutside = false` 且未接 `onTap`，只能系统返回关闭。
3. **无下滑拖拽关闭**：相册类应用的标准手势缺失。
4. **文件管理两个 Tab 的图片点不开**。
5. **文生图结果区 UI 写死只显示前 2 张**，第 3、4 张生成后不可见。
6. **i18n 违规**：查看器保存 Toast 硬编码中文。
7. **无页码指示**：多图浏览时不知道当前位置和总数。
8. **聊天内分组过碎**：初版按「同条消息顶层 Image / 同一工具 output」分组，而典型使用是
   一条消息一张附件、一次生成一张图——组内永远只有 1 张，翻页无从发生。用户在聊天里浏览的
   实际是整个会话的图片时间流，应作为同一个时序序列导航。

### 1.3 已确认的边界

- 聊天内按**会话级时序相册**聚合：当前选中分支下，各消息按列表顺序、消息内按 part 顺序，
  收集顶层 Image part 与 Tool.output 中的 Image（即用户附件与文生图等工具产物）。
- Markdown `<img>` 与顶层 Image part 不在同一层（AST/DOM vs parts），**不**解析 Markdown
  把正文插图并入会话相册；正文图仍单张打开。
- 助手背景、聊天背景、附件 chips 是装饰 / 输入态，不接入查看器。
- 查看器是全屏 Dialog，**不**套用 `AdaptiveModal`（半屏 Sheet / 有界卡片与看图冲突）。
- 删除、复制 prompt 等**管理**动作仍留在网格卡片；查看器只做浏览、保存，以及由调用方注入的轻量动作（如设为背景）。查看器本身不理解助手 / Gallery / 文件管理差异。

---

## 2. 目标与非目标

### 目标

1. `ImagePreviewDialog` 支持 `initialIndex`，从被点击的图片进入。
2. 所有「同组多图」入口把整组列表传入查看器：**聊天为会话级时序相册**（当前分支全部
   明确图片：用户附件 + 工具产物，经 `LocalConversationImages` 点击期求值提供；相册
   为空时回退单图模式），其余入口——文生图当次结果（全部 1–4 张）、Gallery 当前已加载快照、
   文件管理两个 Tab 的当前图片集合；Workspace 维持单张。
3. 查看器补齐手势：点按关闭、下滑（含上滑可选）拖拽关闭（跟手位移 + 缩放 + 背景渐隐，
   超阈值释放关闭、未超阈值回弹）。
4. 多图时显示页码指示（`n / m`）。
5. 保存 Toast 本地化（5 locale），保存按钮补充无障碍描述。
6. 文件管理 Upload Tab 仅 `image/*` 可点开，文生图 Tab 全部可点开；非图片维持原状。

### 非目标

- 共享元素转场（缩略图 → 全屏平滑放大）。
- 长按菜单、分享按钮（后续迭代）。
- 宽屏键鼠交互（方向键翻页、Ctrl+滚轮缩放、Esc 关闭，后续迭代）。
- 统一各处缩略图的 placeholder / 错误图。
- Viewer 内删除 / 复制 prompt / caption 叠加层（复制 prompt 留在文生图详情与 Gallery 卡片）。
- 共享元素转场、键鼠交互（仍属后续）。

### 已知限制（记录在案，后续迭代处理）

- 库的单击回调带延迟调度（约 270ms，用于区分双击）：单击后立刻按下并竖直拖拽时，
  延迟关闭可能在拖拽进行中触发，跳过跟手动画直接关闭。窗口极窄且结果与意图一致。
- 查看器宿主是 LazyColumn item：流式生成 + 自动滚动开启时，宿主 item 被滚出视口会使
  查看器随组合销毁而关闭。罕见场景，如需彻底规避要把 Dialog 状态提升到消息级之上。

---

## 3. 交互规范（查看器）

### 3.1 手势

| 手势 | 行为 | 说明 |
|------|------|------|
| 单指左右滑 | 翻页 | 库已有（Pager） |
| 双指捏合 | 缩放 | 库已有 |
| 双击 | 放大 / 还原 | 库已有（默认开启） |
| 放大后单指拖动 | 平移 | 库已有 |
| 单击 | 关闭 | 新增：`PagerGestureScope.onTap` |
| 系统返回 | 关闭 | 已有 |
| 未放大时竖直拖拽 | 拖拽关闭 | 新增，见 3.2 |

### 3.2 下滑拖拽关闭的详细规范

参照 Google Photos / Telegram 的行为：

- **触发条件**：当前页缩放率为 1（未放大）、单指、竖直分量占主导
  （`|dy| > slop` 且 `|dy| > |dx| * 1.5`）。竖直向上或向下拖都允许关闭。
  当前页尚未组合完成（如图片未加载）时手势不触发，保守放行给原有手势。
- **跟手反馈**：
  - 图片容器（整个 Pager 区域）跟随手指位移：`translationY = dy`，`translationX = dx / 2`
    （水平方向只跟随一半，暗示「竖直才是关闭」）。
  - 容器缩放：`scale = 1 - progress * 0.35`，`progress = |dy| / 容器高`；progress 不设上限，
    退出动画期间位移超过容器高，缩放与背景按同进度继续变化。
  - 黑色背景透明度：`1 - progress * 0.75`（收敛到 `0..1`）。
  - 底部保存栏与页码指示按 `1 - 2 * progress` 淡出（进度过半即隐藏，避免视觉干扰）。
- **释放判定**：`|dy| > 容器高 * 0.2`，或竖直方向速度与拖动方向一致且 `|velocityY| > 2000 px/s`。
  - 满足 → 沿拖动方向动画滑出（位移至约 1.2 倍容器高、背景随进度归零）后调用
    `onDismissRequest()`。
  - 不满足 → spring 回弹到原位，背景与叠加层恢复。
- **手势仲裁**（关键实现约束）：拖拽检测在父层以 `PointerEventPass.Initial` 运行，
  判定为竖直拖拽后消费指针变化。`ZoomableView` 的变换手势检测遇到已消费事件会自行取消，
  因此捏合 / 双击 / 平移 / 翻页均不受影响；水平滑动不消费，正常翻页。
- **图层顺序约束**：`pointerInput` 必须位于 `graphicsLayer`（位移/缩放）之外。若在图层
  内侧，指针坐标会被图层逆变换，拖拽反馈反向且逐帧抖动。
- **中断恢复**：多指按下（第二根手指）或手势被系统取消时，立即回弹并恢复输入权；
  回弹动画在新一次拖拽开始时取消。

### 3.3 视觉

- 页码指示：`TopCenter`，白色 70% 透明度 `labelMedium` 小字 `n / m`，仅 `images.size > 1` 时显示；
  拖拽关闭过程中随进度淡出。
- 底部按钮组：信息 → 保存 → 调用方注入的额外按钮，整组 `BottomCenter` + 16dp 间距；
  拖拽时随进度淡出，正常态白色。
- 反馈：保存 / 设背景等 Toast 必须画在 **Dialog 窗口内**。应用根上的 `Toaster` 在全屏
  Dialog 下层，会被查看器挡住；查看器自建一层 `Toaster` 并临时覆盖 `LocalToaster`。
  进行中与结果共用同一个 toast `id`，结果到达时替换进行中文案，避免两条叠在一起。
  保存成功写明相册文件名；设背景成功写明助手名。
- 背景：纯黑，透明度由拖拽进度驱动；关闭动画结束时 Dialog 出栈。

---

## 4. 技术方案

### 4.1 `ImagePreviewDialog` 升级

签名：

```kotlin
data class ImagePreviewAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: (url: String, toaster: ToasterState) -> Unit,
)

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
    initialIndex: Int = 0,
    extraActions: List<ImagePreviewAction> = emptyList(),
    overlay: (@Composable () -> Unit)? = null,
)
```

- `initialIndex` 越界时收敛到 `[0, images.size - 1]`；传给
  `rememberZoomablePagerState(initialPage = ...) { images.size }`。
- `images` 为空列表时直接不组合（防止 0 页查看器与保存按钮越界）。
- `detectGesture = PagerGestureScope(onTap = onDismissRequest)` 实现点按关闭。
- 竖直拖拽关闭为该文件内的私有实现：`Modifier.pointerInput`（位于 `graphicsLayer` 之外）
  + `awaitEachGesture` + `VelocityTracker` + `MutableFloatState` 直写位移 + 收尾用
  `animate()` 在组合作用域驱动，不引入新依赖。实现模式借鉴库内
  `DraggablePreviewerState.verticalDrag`（同款 `scale == 1` 门控与阈值判定），但不采用
  `DraggablePreviewer` / `TransformPreviewer` 容器——那套面向共享元素转场，需要
  itemStateMap / open-close 生命周期，替换现有 Dialog 结构改动大、收益不在本次目标内。
- 页码指示读取 `state.currentPage`（组合内快照读取，翻页自动重组）。
- 保存逻辑沿用 `FilesManager.saveMessageImage`，Toast 与按钮描述改用 `stringResource`。
- `extraActions` 是按钮扩展点：画在保存右侧，点击时把**当前页 url** 与 Dialog 内
  Toaster 交给调用方。`overlay` 是确认框 / 选择器扩展点。查看器不解析助手、不写 Settings。
- Dialog 内自建 `rememberToasterState()` + `Toaster(alignment = TopCenter)`，并用
  `CompositionLocalProvider(LocalToaster provides dialogToaster)` 覆盖保存按钮与
  extra action 的反馈通道。进行中与结果共用同一 toast id。

### 4.2 `ZoomableAsyncImage` 多图参数

```kotlin
@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    albumProvider: (() -> List<String>)? = null,
    extraActions: List<ImagePreviewAction>? = null,
    overlay: (@Composable () -> Unit)? = null,
)
```

`albumProvider == null` 时行为与现状完全一致（单张）；Dialog 的弹出仍由本组件持有，
打开时求值相册，命中则从该张浏览整本相册，未命中或相册为空则单图打开。
未显式传入 `extraActions` / `overlay` 时分别读取 `LocalImagePreviewActions` 与
`LocalImagePreviewOverlay`。Markdown / HTML 路径零改动。

聊天 / 子助手详情 / 助手 Prompt 预览通过这两个 Local 下发会话级动作与确认层
（当前会话助手的「设为背景」+ 确认框）。`ZoomableAsyncImage` 透传给查看器，
因此消息图、工具缩略行、工具详情 Preview 无需逐点改签名。

### 4.3 聊天会话级时序相册

在 `ChatMessageCot.kt` 增加纯函数（可单测）：

```kotlin
private val LOADING_IMAGE_URL_REGEX = Regex("^data:image/[^;]*;base64,\\s*$")

internal fun isImagePartLoading(url: String): Boolean =
    url.isBlank() || url.matches(LOADING_IMAGE_URL_REGEX)

// 消息内明确图片: 顶层 Image part 与 Tool.output 的 Image, 按 part 位置顺序
internal fun collectMessageImageUrls(parts: List<UIMessagePart>): List<String>

// 会话级时序相册(点击期求值)
val LocalConversationImages = compositionLocalOf<() -> List<String>> { { emptyList() } }
```

实现机制（**点击期求值**，组合期零成本）：

- `LocalConversationImages` 的类型是 `() -> List<String>`（`compositionLocalOf`，
  默认返回空列表的稳定函数）。会话宿主（`ChatList`、`SubAssistantDetailPage`、
  `AssistantPromptPage`）用 `rememberUpdatedState(conversation/timeline/messages)` +
  `remember { { 最新状态.flatMap { collectMessageImageUrls(...) } } }` 构造**稳定
  lambda 实例**经 Provider 下发：会话数据是不可变 data class（无快照状态），相册
  依赖靠 lambda 捕获的 updated state 在**点击时**读取最新值展平，组合期不做任何
  扫描（流式期间不随每帧重算，读者零失效）。
- `ZoomableAsyncImage` 用 `albumProvider: (() -> List<String>)?` 参数替换逐 site 的
  `images/initialIndex`：打开查看器时求值相册，命中则从该张浏览整本相册，未命中
  或相册为空则单图打开。查看器打开期间相册扩展（流式新图落地）
  经 dialog 分支的状态读自动生效。
- 消费点：`MessagePartsBlock` 的 Image 分支（用户附件）、`ChatMessageTools` 的缩略
  `LazyRow`、`ImageGenerationToolUI.Preview`、`DefaultToolPreview`，统一传
  `albumProvider = LocalConversationImages.current`。工具卡 Preview 弹层
  （AdaptiveModal）在同一组合树内，CompositionLocal 可达。`ChatMessage.kt` 原有的
  内联 loading 正则改用 `isImagePartLoading` 消除重复。Markdown/HTML 正文图不读取
  该 Local，仍单张打开。
- 同一批宿主再提供 `LocalImagePreviewActions` 与 `LocalImagePreviewOverlay`：
  当前会话助手（`conversation.assistantId` / 子助手 Target / Prompt 页正在编辑的助手）
  设为背景，不弹选择器，但助手确定后仍要确认一次。

时序语义：相册顺序 = 消息列表顺序 × 消息内 part 顺序，即用户在时间线上看到图片的
先后顺序；重复 url 时 `indexOf` 定位首个（可接受的边界）。

### 4.4 文生图页（`ImgGenPage.kt`）

- **生成结果区**：按 `currentGeneratedImages` 全量展示（1–4 张），两两一行
  （`chunked(2)`），单张时占满整行；预览状态提升为页面级 `previewIndex`，
  传入当次结果全量列表（`file://` 前缀，与网格的 File model 共享 Coil 缓存键）与点击位。
- **Gallery**：预览状态同样提升；列表取 `itemSnapshotList` 当前快照的
  `filePath`，点击位为网格位置。卡片上的复制 prompt / 保存 / 删除按钮维持不变。
- 当次结果与 Gallery 查看器注入「设为背景」：点按钮后弹出 `AssistantPickerSheet`，
  选中哪个助手就把当前图设为那个助手的背景（与聊天「当前助手一键设置」不同）。

### 4.5 文件管理（`SettingFilesPage.kt`）

- 页面级提升预览状态（`previewImages` + `previewIndex`）。
- Upload Tab：`image/*` 的文件组成预览列表（`file://` 前缀），`MediaThumb` 的图片区域
  变为可点击（`onImageClick` 参数），非图片仍无点击。
- 文生图 Tab：全部产物组成预览列表（`file://` 前缀），同样可点击打开。
- 删除确认等管理流程不变。文生图 Tab 单删确认改为与 Upload 同结构的短文案
  （截断 prompt + 后果），不再把完整 prompt 或 UUID 文件名塞进对话框。
- 两个 Tab 的查看器均注入「设为背景」+ 助手选择器 + 确认框（与文生图橱窗一致）。

### 4.6 工具输出的图片分组

三处工具相关入口统一传 `albumProvider = LocalConversationImages.current`（会话级
时序相册，点击期求值；相册为空时由 `ZoomableAsyncImage` 回退单图模式）：

- `ImageGenerationToolUI.Preview`：output 的 Image part 纵向列表。
- `ChatMessageTools` 工具步骤摘要的 `LazyRow` 缩略行（64dp）。
- `DefaultToolPreview`（无专属渲染器的工具详情）。

### 4.7 删除确认与图片信息

**删除确认**：所有删除单张/清空入口统一带上下文确认（短标识 + 后果）：

- `SettingFilesPage` Upload 单删：文件名 + 「聊天附件或助手背景引用将无法显示」。
- `SettingFilesPage` 文生图单删：与 Upload 同结构——用卡片上能认出来的短标识
  （截断 prompt，兜底「无提示词」）+ 「聊天与背景副本不受影响」。**不**把完整 prompt
  或 UUID 文件名塞进对话框；prompt 仍在卡片上两行截断。
- `SettingFilesPage` 清空（两 Tab）：分类项数 + 各自后果。
- `ImgGenPage` Gallery 删除：短标识（截断 prompt 或「无提示词」）+ 后果；确认后 `vm.deleteImage`。
- error Toast 收集器在 `ImageGenPage` 顶层，Gallery 页删除失败也能弹出。

**查看器图片信息**：

- 底部按钮组：信息 → 保存 → extraActions，整组 `BottomCenter` 水平居中。
- 信息获取（`resolveImageInfo`，IO 线程，只读头不解码像素）：来源由 `classifyImageSource`
  按 url 前缀与应用目录推断；本地文件经 `BitmapFactory` bounds 取分辨率与 MIME；
  内联图解码 base64 后同样只读头；网络图不发请求。翻页时按当前页 url 自动重查。
- 面板：Dialog 内自绘底部面板；点面板外 scrim / 关闭按钮 / 返回键关闭。
- 手势门控：面板打开时拖拽关闭早退；翻页/缩放不受影响。

### 4.8 设为背景（调用方注入）

查看器只渲染按钮。业务在调用方（`rememberImageBackgroundHost`）：

1. 把当前页 url 物化为本地文件（`file:` / 绝对路径直接用；`data:` / `http` 先流式落到
   cache 临时文件）。`AssistantBackgroundService.replaceBackground` 会再拷一份独立背景副本。
2. **聊天相关入口**（`ChatList` / `SubAssistantDetailPage` / `AssistantPromptPage`）：
   助手已知，不弹选择器。
3. **文生图橱窗与文件管理**：先弹 `AssistantPickerSheet`（`forceDialog`、隐藏编辑入口），
   作为查看器 `overlay`，保证盖在全屏查看器之上。
4. 助手一旦确定（已知或刚选完），一律再弹一次确认，写明助手名与「将替换当前背景 / 渐变」。
   点确认才写入；点取消不改设置。
5. 成功 / 失败 Toast 走查看器 Dialog 内的 `LocalToaster`，进行中与结果共用同一 toast id。
   成功文案带助手名。
6. Workspace 图片查看器不注入该动作（工作区文件不是助手素材入口）。

实现落在共享 helper（`rememberImageBackgroundHost`），避免各页面复制 IO / Toast / 失败码映射。
选择器只回答「哪个助手」，确认框才回答「是否覆盖该助手背景」。

### 4.9 文生图工具详情统一

`ImageGenerationToolUI.Preview` 原先自绘「技术详情」折叠段，与 `DefaultToolPreview`
的「工具调用」详情不一致。改为：

- 上半段保留用户可读信息：图、供应商、模型、**完整提示词 + 复制按钮**、背景结果、文件路径。
- 「技术详情」文案改为「调用详情」，展开后复用与 `DefaultToolPreview` 相同的
  入参 JSON + 输出 JSON 高亮块（同一套 `HighlightCodeBlock` / 字号 / FormItem 标签）。
- 不再单独发明一套折叠样式。

### 4.10 i18n / 版本

- 既有查看器 / Gallery 本地化键保留。
- 本轮新增 / 调整：`image_viewer_set_as_background`、带助手名的
  `image_viewer_background_set`、`image_viewer_background_failed`、
  `image_viewer_select_assistant_title`、确认框三键、带文件名的
  `image_viewer_saved`、`chat_message_tool_generate_image_copy_prompt`、
  `chat_message_tool_generate_image_call_details`（替换 technical_details 的用户可见文案）、
  文生图删除确认短文案。
- 本迭代不递增版本号：`versionCode = 16`、`versionName = "0.0.16"` 保持不变；
  变更记录并入 `changelog.md` 的 `0.0.16` 条目。

---

## 5. 文件变更清单

| 文件 | 变更 |
|------|------|
| `app/.../ui/components/ui/ImagePreviewDialog.kt` | extraActions 槽、Dialog 内 Toaster、同一 id 替换进度 Toast、安全区 |
| `app/.../ui/components/ui/ImagePreviewActions.kt` | `ImagePreviewAction`、Local、统一 `rememberImageBackgroundHost` |
| `app/.../ui/components/richtext/ZoomableAsyncImage.kt` | 透传 extraActions / overlay / Local |
| `app/.../ui/pages/chat/ChatList.kt` 等会话宿主 | 下发当前助手「设为背景」 |
| `app/.../ui/pages/imggen/ImgGenPage.kt` | 查看器设背景 + 助手选择器 |
| `app/.../ui/pages/setting/SettingFilesPage.kt` | 同上；文生图删除确认改为短文案 |
| `app/.../ui/components/message/tools/ImageGenerationToolUI.kt` | 复制提示词；调用详情与 DefaultToolPreview 对齐 |
| `app/src/main/res/values*/strings.xml` | 设背景 / 复制提示词 / 调用详情 / 短删除确认 |
| `app/src/test/...` | 物化路径、失败码映射、删除文案参数 |
| `docs/dev/changelog.md`、`docs/references/*` | 同步本轮行为 |

---

## 6. 测试与验收

### 单元测试

- `collectMessageImageUrls`：顶层与 Tool.output 图片的 part 顺序、loading 过滤、重复 url、空输入。
- `isImagePartLoading`：空白、base64 空壳、正常 data URI、file://、http。

### 编译与回归

- `gradlew test` 全绿；`gradlew assembleDebug` 通过。
- 既有图片路径（Markdown 图、Workspace、背景）行为不变。

### 手动验收清单

1. 聊天内任一明确图片（用户附件、工具缩略行、工具卡 Preview 图）：点开 → 从该张开始，
   左右滑可浏览整个会话的图片时间流（跨消息、跨轮次），页码为会话相册内的位置。
2. 聊天单图会话 / Markdown 正文图：点开仍为单张，无页码指示；Markdown 图不并入会话相册。
3. 文生图生成 4 张：结果区两行展示 4 张；点第 3 张从第 3 张开始浏览。
4. Gallery：点任意网格项，从该项开始左右滑；返回、删除、复制、保存不受影响。
5. 文件管理 Upload：图片可点开浏览，文档点击无反应；文生图 Tab 全部可点开。
6. 查看器：单击关闭；未放大时下滑/上滑拖拽有跟手位移缩放 + 背景渐隐，超阈值关闭、
   未超回弹；放大后竖直拖动只平移不关闭；双指捏合、双击、翻页不受拖拽层影响；
   拖拽跟手（不出现隔帧半速移动）。
7. 保存 / 设背景 Toast 出现在查看器内顶部，不被全屏 Dialog 挡住；成功后进行中文案消失，
   成功提示分别带相册文件名 / 助手名。
8. `generate_image` 流式生成中（loading 占位）：点击占位图不进入 0 页查看器、不崩溃。
9. 文件管理文生图删除确认短，不含完整 prompt；Upload 与清空确认保持短标识 + 后果。
10. 查看器按钮顺序：信息、保存、设为背景（有注入时）；面板/手势行为不变。
11. 聊天点「设为背景」先确认当前会话助手再写入；文生图橱窗 / 文件管理先弹助手选择器，
    选完后再确认一次，确认后才写入。
12. 文生图工具详情可复制完整提示词；「调用详情」展开后与其他工具卡的入参/结果 JSON 一致。

---

## 7. 实施顺序

1. `ImagePreviewDialog` 升级（手势 + initialIndex + i18n）。
2. `ZoomableAsyncImage` 参数 + 聊天聚合 + 工具卡适配。
3. `ImgGenPage` 结果区与 Gallery。
4. `SettingFilesPage`。
5. 字符串 / changelog / 版本号。
6. 单元测试与编译回归。

风险点：竖直拖拽层与 Pager 的手势仲裁。缓解：仅竖直主导时消费、Initial pass 抢占后
库内检测自动取消、放大态直接不触发；验收清单第 6 条逐项覆盖。
