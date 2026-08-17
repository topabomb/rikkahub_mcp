# 消息渲染管线参考

> 本文档以 Measix Pilot 当前代码为准，描述 LLM 回复内容从 `UIMessage.parts` 到最终像素的完整渲染管线，
> 涵盖各内容类型的渲染方式、WebView 的生命周期/交互/布局机制。
>
> **相关文档**：[界面架构参考](ui-architecture.md) | [消息生成链路](chat-generation-pipeline.md)

---

## 目录

1. [架构总览](#1-架构总览)
2. [第一层：Part 分组与分发](#2-第一层part-分组与分发)
3. [第二层：Markdown 解析与节点分发](#3-第二层markdown-解析与节点分发)
4. [代码块渲染](#4-代码块渲染highlightcodeblock)
5. [Mermaid WebView 渲染](#5-mermaid-webview-渲染)
6. [HTML/SVG 代码预览](#6-htmlsvg-代码预览)
7. [WebView 核心封装层](#7-webview-核心封装层)
8. [全屏 WebView 页面](#8-全屏-webview-页面)
9. [Markdown 全文预览](#9-markdown-全文预览)
10. [渲染方式汇总](#10-渲染方式汇总)
11. [关键设计决策](#11-关键设计决策)

---

## 1. 架构总览

LLM 回复渲染经过 **两层分发**：

```
UIMessage.parts[]
  │
  ├─ 第一层: ChatMessage.kt → groupMessageParts() 分组
  │     将 parts 分为 ThinkingBlock（推理+工具）和 ContentBlock（文本/图片等）
  │
  └─ 第二层: MarkdownBlock / MarkdownNew → AST 或 HTML DOM 逐节点分发
        Text part 的文本被解析为 Markdown，再按节点类型分发到各渲染组件
```

### 流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        UIMessage.parts                              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                  groupMessageParts()
                               │
         ┌─────────────────────┼─────────────────────┐
         ▼                     ▼                     ▼
  ThinkingBlock          ContentBlock           ContentBlock
  (Reasoning+Tool)       (Text)                 (Image/Audio/...)
         │                     │
         ▼                     ▼
  ChainOfThought        MarkdownBlock
  (折叠卡片)                  │
                    ┌────────┴────────┐
                    │ containsHtml?   │
                    ▼                 ▼
               路径 A: AST        路径 B: HTML DOM
              (Markdown.kt)      (MarkdownNew.kt)
               逐节点分发          逐节点分发
```

**关键文件**：

| 文件 | 职责 |
|------|------|
| `app/.../ui/components/message/ChatMessage.kt` | 第一层分发入口，`MessagePartsBlock` 渲染 |
| `app/.../ui/components/message/ChatMessageCot.kt` | `groupMessageParts()` 分组逻辑、`ThinkingStep` / `MessagePartBlock` 定义 |
| `app/.../ui/components/richtext/Markdown.kt` | 路径 A：纯 Markdown AST 原生渲染 |
| `app/.../ui/components/richtext/MarkdownNew.kt` | 路径 B：含 HTML 的 Markdown 渲染（Jsoup DOM） |
| `app/.../ui/components/richtext/HighlightCodeBlock.kt` | 代码块渲染（三态分发） |
| `app/.../ui/components/richtext/Mermaid.kt` | Mermaid 图表 WebView 渲染 |
| `app/.../ui/components/richtext/LatexText.kt` | LaTeX 公式原生 Canvas 渲染 |
| `app/.../ui/components/richtext/MathBlock.kt` | LaTeX 行内/块级公式包装 |
| `app/.../ui/components/richtext/SimpleHtmlBlock.kt` | HTML 块原生渲染（Jsoup → Compose） |
| `app/.../ui/components/richtext/DiffView.kt` | 文件编辑 diff 渲染（工具步骤中使用） |
| `app/.../ui/components/richtext/ZoomableAsyncImage.kt` | 图片渲染（Coil3） |
| `app/.../ui/components/richtext/MarkdownWeb.kt` | Markdown 全文预览 HTML 模板构建 |
| `app/.../ui/components/webview/WebView.kt` | WebView 核心封装层 |
| `app/.../ui/pages/webview/WebViewPage.kt` | 全屏 WebView 页面 |
| `app/src/main/assets/html/mark.html` | Markdown 全文预览 HTML 模板 |

---

## 2. 第一层：Part 分组与分发

### 2.1 分组逻辑

`groupMessageParts()`（`ChatMessageCot.kt`）将 `parts` 列表按顺序遍历，连续的 `Reasoning` 和 `Tool` 合并为一个 `ThinkingBlock`，其他类型各自成为独立的 `ContentBlock`。

```
输入: [Reasoning, Tool, Tool, Text, Image, Reasoning, Text]
输出: [ThinkingBlock(R, T, T), ContentBlock(Text), ContentBlock(Image),
       ThinkingBlock(R), ContentBlock(Text)]
```

### 2.2 ContentBlock 分发

`MessagePartsBlock()`（`ChatMessage.kt`）对 `ContentBlock` 内的 part 按类型分发：

| Part 类型 | 渲染组件 | 方式 |
|---|---|---|
| `Text` | `MarkdownBlock` | 进入第二层 Markdown 解析 |
| `Image` | `ZoomableAsyncImage` | 原生 Coil3 |
| `Video` | Surface + Icon，点击 Intent 打开 | 原生 |
| `Audio` | Surface + Icon，点击 Intent 打开 | 原生 |
| `Document` | Surface + Icon + 文件名，点击 Intent 打开 | 原生 |

> **会话级时序相册**：聊天内的明确图片（用户附件、工具产物）由会话宿主
> （`ChatList` / `SubAssistantDetailPage` / `AssistantPromptPage`）按消息顺序经
> `collectMessageImageUrls`（`ChatMessageCot.kt`，含顶层 Image part 与 Tool.output
> 的 Image）展平成 `LocalConversationImages` 下发；消息区、工具缩略行与工具详情
> Preview 点击任意图进入全屏 `ImagePreviewDialog`，从该张开始左右翻页浏览整个
> 会话的图片时间流；未提供相册或相册为空时回退单图模式。
> 聊天宿主同时下发 `LocalImagePreviewActions` 与 `LocalImagePreviewOverlay`
> （当前会话助手「设为背景」+ 确认框），`ZoomableAsyncImage` 透传给查看器；
> 文生图工具详情可复制完整提示词，「调用详情」展开后与默认工具卡共用 `ToolCallJsonDetails`。
> 流式 loading 占位（空白 url 或 base64 空壳，由 `isImagePartLoading` 判定）被过滤并
> 渲染为 shimmer 方块、不参与点击。Markdown/HTML 正文图不在 part 层，仍单张打开。
> 查看器交互规范见 [`docs/dev/image-viewer-upgrade-plan.md`](../dev/image-viewer-upgrade-plan.md)。

> 用户消息（`MessageRole.USER`）的 Text 额外包一层 `Surface`（primaryContainer 气泡）；
> 助手消息可选气泡（`showAssistantBubble` 设置项）。

#### 文本预处理（replaceRegexes）

Text part 在传入 `MarkdownBlock` 之前，先经过 `replaceRegexes()` 处理，应用助手配置的正则替换规则：

- `scope = AssistantAffectScope.USER`：用户消息的替换规则
- `scope = AssistantAffectScope.ASSISTANT`：助手消息的替换规则
- `visual = true`：只应用 `visualOnly` 规则，供 UI 显示使用；发送给模型和持久化路径使用
  `visual = false`。这与 Output Transformer 的 `visualTransform()` / `transforms()` /
  `onGenerationFinish()` 生命周期是两套不同机制。

#### 流式期间禁用文本选择

流式生成期间（`loading == true`），`SelectionContainer` 被禁用。原因：Markdown 在不断重渲染时，
内部可选择的 `Text` 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段对 selectable 列表的排序
产生并发修改，导致 `ConcurrentModificationException`。生成结束后内容稳定，再启用文本选择。

### 2.3 ThinkingBlock 渲染

`ThinkingBlock` 通过 `ChainOfThought` 组件渲染为可折叠的推理卡片，内部步骤交替显示：

- `ReasoningStep` → `ChatMessageReasoningStep`（推理文本）
- `ToolStep` → `ChatMessageToolStep`（工具调用卡片，含输入/输出/审批）

折叠只隐藏普通的早期步骤。`ToolApprovalState.Pending` 的 Tool step 与 `generate_image` 都被固定
展示，且不计入隐藏数量。前者保证 HITL 审批不会被「再显示 N 步」收走；后者保证穿插在搜索/读写/
shell 中间的文生图结果仍留在时间线原位。`generate_image` 不拆成 `SubAssistantCallBlock`：它没有
Child 会话身份和导航，只是普通 Tool step。

> 工具步骤中，工作空间文件编辑工具（`workspace_edit_file`）的输出通过 `DiffView`
> （`DiffView.kt`）渲染统一 diff，支持折叠摘要与展开全量视图。

### 2.4 SubAssistantCallCard 渲染

`assistant_call` 在 `groupMessageParts` 中从普通 COT block 拆出，由 `SubAssistantCallCard` 独立渲染 Target 身份、request、状态、当前文本预览与等待中的 `ask_user`。

卡片不显示百分比、ETA、推理文本或工具 JSON。预览来自本次 Child task 的顶层 Text 投影，按可用高度限制显示且不内嵌滚动；纯非文本完成态显示本地化提示。整卡仅在 Child link 有效时导航到只读详情页，交互问题区会消费点击，避免误触详情导航。

状态模型、preview reducer、Child link 校验和只读策略见 [sub-assistant-architecture.md](sub-assistant-architecture.md)。

---

## 3. 第二层：Markdown 解析与节点分发

### 3.1 预处理

`MarkdownBlock`（`Markdown.kt`）在解析前执行 `preProcess()`：

1. 找出所有代码块范围（避免代码块内的内容被替换）
2. 将 `\(...\)` 替换为 `$...$`（行内 LaTeX）
3. 将 `\[...\]` 替换为 `$$...$$`（块级 LaTeX）

### 3.2 异步 AST 解析

`MarkdownBlock` 使用 `snapshotFlow` + `mapLatest` + `flowOn(Dispatchers.Default)` 在后台线程解析 AST 树，
防止流式更新频繁重组时掉帧。初次渲染使用同步解析结果（`parseMarkdown(content)`），后续更新通过
Flow 异步收集。

### 3.3 双路径分发

解析 AST 后，检查是否包含 HTML 节点（`HTML_BLOCK` 或 `HTML_TAG`）：

- **路径 A（无 HTML）**：直接遍历 AST 子节点，每个节点对应一个 Composable
- **路径 B（含 HTML）**：调用 `MarkdownNew`，传入 **原始 `content`**（非预处理后的数据），
  `MarkdownNew` 内部独立执行 `preProcess()` 后，用 `HtmlGenerator` 生成 HTML 字符串 → Jsoup 解析为 DOM → 遍历 DOM 节点

两条路径的节点分发对照：

| 内容类型 | 路径 A（AST 节点） | 路径 B（HTML 标签） | 渲染方式 |
|---|---|---|---|
| 段落 | `PARAGRAPH` | `<p>` | 原生 `Text` + `AnnotatedString` |
| 标题 | `ATX_1~6` | `<h1>~<h6>` | 原生 `Text` + `HeaderStyle` |
| 围栏代码块 | `CODE_FENCE` | `<pre>` | → `HighlightCodeBlock`（见第 4 节） |
| 缩进代码块 | `CODE_BLOCK` | — | 原生 `Text`（无语法高亮） |
| 行内代码 | `CODE_SPAN` | `<code>` | 原生 `Text`（JetbrainsMono） |
| 行内公式 | `INLINE_MATH` | `<span class="math" inline="true">` | → `MathInline`（原生 Canvas） |
| 块级公式 | `BLOCK_MATH` | `<span class="math" inline!="true">` | → `MathBlock`（原生 Canvas） |
| 图片 | `IMAGE` | `<img>` | → `ZoomableAsyncImage`（Coil3） |
| 表格 | `TABLE` | `<table>` | → `DataTable`（原生 Compose） |
| 引用块 | `BLOCK_QUOTE` | `<blockquote>` | 原生 `Column` + `drawWithContent` |
| 列表 | `UNORDERED_LIST` / `ORDERED_LIST` | `<ul>` / `<ol>` | 原生 `Column`（递归） |
| HTML 块 | `HTML_BLOCK` | — | → `SimpleHtmlBlock`（Jsoup → Compose） |
| 分割线 | `HORIZONTAL_RULE` | `<hr>` | 原生 `HorizontalDivider` |
| 折叠块 | — | `<details>` | 原生 `Column` + `AnimatedVisibility` |
| 进度条 | — | `<progress>` | 原生 `LinearProgressIndicator` |

> 路径 B 支持更丰富的 HTML 标签（`<details>`、`<progress>`、内联 `style` 属性解析等），
> 因为 Jsoup DOM 比 IntelliJ AST 能更精确地表达 HTML 语义。
> 路径 B 中还支持 `<font>` 标签的 color/size 属性、CSS style 属性的完整解析
>（font-size、font-weight、text-align、line-height 等）。
>
> **流式安全性**：`CODE_FENCE` 节点通过检查 `CODE_FENCE_END` token 判断代码块是否完整（`completeCodeBlock`），
> 传入 `HighlightCodeBlock`。流式生成中未闭合的代码块不会触发 Mermaid/HTML 预览，避免渲染半成品。

---

## 4. 代码块渲染（HighlightCodeBlock）

`HighlightCodeBlock`（`HighlightCodeBlock.kt`）根据代码语言进入三条路径：

```
HighlightCodeBlock(code, language, completeCodeBlock)
  │
  ├─ canInlinePreview = completeCodeBlock && language ∈ {html, svg}
  │    └─ canInlinePreview && previewMode → CodeBlockPreview (WebView 内联预览)
  │         默认预览模式，可切换"代码/预览"
  │         └─ 全屏: Screen.WebView(base64(content))
  │
  ├─ completeCodeBlock && language == "mermaid" → Mermaid (WebView 渲染)
  │    └─ 全屏: Screen.WebView(base64(html))
  │
  └─ 其他（或代码块未闭合）→ 原生 HighlightText (语法高亮)
        ├─ autoWrap + showLineNumbers → 逐行渲染 (CodeBlockWithLineNumbersWrapped)
        └─ 其他组合 → 整体渲染 + horizontalScroll (CodeBlockDefault)
```

> **流式降级**：`completeCodeBlock` 在流式生成中为 `false`（代码围栏未闭合），此时 Mermaid/HTML 预览
> 均不可用，统一走原生语法高亮路径。生成完成后围栏闭合，重新触发渲染切换到 WebView 预览。

### 4.1 普通代码块 — 原生渲染

- **语法高亮**：`highlight` 模块（`HighlightText` / `Highlighter`）
- **配色**：`AtomOneDarkPalette` / `AtomOneLightPalette`（跟随 `LocalDarkMode`）
- **字体**：`JetbrainsMono`
- **功能**：复制、下载（`CreateDocument`）、折叠/展开（`codeBlockAutoCollapse` 开启时超过阈值自动折叠）
- **行号**：`showLineNumbers` 开关控制
- **换行**：`codeBlockAutoWrap` 开关控制

### 4.2 Mermaid — WebView 渲染（详见第 5 节）

### 4.3 HTML/SVG — WebView 渲染（详见第 6 节）

---

## 5. Mermaid WebView 渲染

### 5.1 数据注入

`Mermaid.kt` 中 `buildMermaidHtml()` 动态构建完整 HTML 文档：

- Mermaid 代码经 `escapeHtml()` 转义后嵌入 `<pre class="mermaid">` 标签
- 加载 Mermaid 脚本：通过 `WEB_VIEW_ASSET_URL` 从本地 assets 加载 `mermaid.min.js`（离线可用，不依赖 CDN）
- `mermaid.initialize()` 配置 `theme: 'base'`，通过 `themeVariables` 注入 M3 配色

### 5.2 配色同步

从 `MaterialTheme.colorScheme` 提取颜色，通过 `toCssHex()`（`ComposeExt.kt`）转为 CSS Hex 字符串：

| M3 颜色 | Mermaid themeVariable |
|---|---|
| `primaryContainer` | `primaryColor` / `primaryBorderColor` / `mainBkg` / `nodeBorder` / `actorBorder` / `actorLineColor` / `clusterBorder` / `taskBorderColor` / `taskBkgColor` |
| `onPrimaryContainer` | `primaryTextColor` / `taskTextLightColor` |
| `secondaryContainer` | `secondaryColor` / `secondBkg` / `secondaryBorderColor` |
| `onSecondaryContainer` | `secondaryTextColor` |
| `tertiaryContainer` | `tertiaryColor` / `tertiaryBorderColor` |
| `onTertiaryContainer` | `tertiaryTextColor` |
| `background` | `background`（body + canvas 填充） |
| `surface` | `nodeBkg` / `clusterBkg` / `actorBkg` |
| `onBackground` | `lineColor` / `textColor` / `labelColor` / `taskTextDarkColor` / `actorTextColor` |
| `error` | `errorBkgColor` |
| `onError` | `errorTextColor` |

`remember(code, colorScheme, darkMode)` 确保主题或代码变化时 HTML 重新构建。

### 5.3 JS ↔ Kotlin 交互

- **接口注入**：`MermaidInterface` 类通过 `@JavascriptInterface` 注入，名为 `AndroidInterface`
- **导出 PNG**：Kotlin 侧通过 `webViewState.webView?.evaluateJavascript("exportSvgToPng();", null)` 触发 JS 函数
  - JS 侧：SVG 序列化 → Base64 → Canvas 绘制（含水印）→ `canvas.toDataURL('image/png')` → 调用 `AndroidInterface.exportImage(base64)`
  - Kotlin 侧：解码 Bitmap → `exportImage()` 保存到相册
- **全屏预览**：点击 View 图标 → `navController.navigate(Screen.WebView(content = html.base64Encode()))`

### 5.4 布局

- 内联预览：`Modifier.height(200.dp)` 固定高度
- `useWideViewPort = true` + `loadWithOverviewMode = true` 自适应内容宽度
- 圆角裁剪：`Modifier.clip(RoundedCornerShape(4.dp))`

---

## 6. HTML/SVG 代码预览

### 6.1 数据注入

`CodeBlockPreview()`（`HighlightCodeBlock.kt`）调用 `buildCodePreviewHtml()` 构建最小 HTML：

- **SVG**：包裹在 `<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$svgCode</body></html>` 中，居中显示
- **HTML**：直接使用原始代码作为 HTML 内容

WebView 通过 `rememberWebViewState(data = html, baseUrl = "https://measix.local", mimeType = "text/html")` 加载，
`baseUrl` 使 HTML 中的相对路径资源能正确解析。

### 6.2 布局与交互

- 内联预览：`Modifier.height(200.dp)` 固定高度（与 Mermaid 一致）
- `useWideViewPort` + `loadWithOverviewMode` + `builtInZoomControls`（隐藏缩放按钮）
- 可在"代码/预览"模式间切换（`previewMode` 状态）
- 全屏预览：通过 `Screen.WebView` 导航到 `WebViewPage`
- **无 JS 接口**：纯展示，不需要 `@JavascriptInterface`

---

## 7. WebView 核心封装层

`WebView.kt` 是所有 WebView 场景的统一封装，通过 `AndroidView` 包装原生 `WebView`。

### 7.1 状态管理（WebViewState）

```
WebViewState
  ├─ content: WebContent        ─ 密封类: Url / Data / NavigatorOnly
  ├─ isLoading / loadingProgress ─ 加载状态（WebChromeClient 驱动）
  ├─ webView: WebView?           ─ 持有原生实例引用（用于 JS 交互）
  ├─ interfaces: Map<String, Any> ─ JS 接口映射
  ├─ consoleMessages             ─ 控制台日志（有上限）
  └─ settings: WebSettings.() -> Unit ─ WebSettings 配置块
```

### 7.2 生命周期

| 阶段 | 回调 | 行为 |
|------|------|------|
| 创建 | `factory` | 创建 WebView，配置 WebSettings（JS、DOM Storage、缩放等），注入 JS 接口，设置 Client |
| 更新 | `update` | 内容变化时通过 `loadDataWithBaseURL` 或 `loadUrl` 加载；重新注入 JS 接口 |
| 重置 | `onReset` | 停止加载、移除 JS 接口（AndroidView 从组合中移除但未释放时） |
| 释放 | `onRelease` | 同 onReset + 清除 Client + 置空 `state.webView` 引用 |

### 7.3 防重复加载机制

- `WebContent.Data` 通过 `lastLoadedData` 记录上次加载的数据，避免 Compose 重组时重复触发 `loadDataWithBaseURL`
- `forceReload` 标志可强制重新加载

### 7.4 加载进度

`MyWebChromeClient.onProgressChanged` 驱动 `loadingProgress`，在 `isLoading` 为 true 时顶部显示 `LinearProgressIndicator`。

---

## 8. 全屏 WebView 页面

`WebViewPage.kt` 接收 `url` 或 base64 编码的 `content`，提供完整的 WebView 浏览体验：

- `Scaffold` + `TopAppBar`（标题 / 刷新 / 前进 / 更多操作）
- `BackHandler` 处理 WebView 内部后退导航
- "Open in Browser"：通过 `LocalUriHandler` 打开系统浏览器
- Console Logs BottomSheet：开发调试用，按级别（ERROR/WARNING）着色

---

## 9. Markdown 全文预览

### 9.1 触发方式

在聊天消息的长按操作菜单中，`onWebViewPreview` 提取所有 `UIMessagePart.Text` 的文本，调用 `buildMarkdownPreviewHtml()` 生成 HTML，导航到 `WebViewPage` 全屏渲染。

### 9.2 HTML 模板（mark.html）

`MarkdownWeb.kt` 读取 `assets/html/mark.html` 模板，替换占位符：

- `{{MARKDOWN_BASE64}}` — Markdown 内容（Base64 编码）
- M3 配色变量：`BACKGROUND_COLOR`、`ON_BACKGROUND_COLOR`、`SURFACE_COLOR`、`ON_SURFACE_COLOR`、
  `SURFACE_VARIANT_COLOR`、`ON_SURFACE_VARIANT_COLOR`、`PRIMARY_COLOR`、`OUTLINE_COLOR`、`OUTLINE_VARIANT_COLOR`

模板内部集成：

| 库 | CDN | 用途 |
|---|---|---|
| markdown-it@14.0.0 | esm.sh | Markdown 解析 |
| @vscode/markdown-it-katex | esm.sh | LaTeX 公式（KaTeX 渲染） |
| katex@0.16.8 + mhchem | esm.sh + jsdelivr (CSS) | 化学公式 `\ce{}` |
| highlight.js@11.9.0 | esm.sh + jsdelivr (CSS) | 语法高亮 |
| mermaid@10.6.1 | esm.sh | Mermaid 图表 |
| markdown-it-task-lists@2.1.1 | esm.sh | 任务列表 |
| js-base64@3.7.5 | esm.sh | Base64 解码 |

> 全文预览的 Mermaid 主题通过 `prefers-color-scheme` 媒体查询自动适配深色/浅色，
> 而非像内联 Mermaid 那样注入 M3 变量。

---

## 10. 渲染方式汇总

| 内容类型 | 渲染方式 | 关键组件 | 技术 |
|---|---|---|---|
| 普通文本 / Markdown | 原生 | `MarkdownBlock` / `MarkdownNew` | IntelliJ Markdown AST / Jsoup DOM |
| 代码块（普通语言） | 原生 | `HighlightCodeBlock` → `HighlightText` | highlight 模块 |
| LaTeX 行内公式 | 原生 Canvas | `MathInline` → `LatexText` | JLatexMathDrawable |
| LaTeX 块级公式 | 原生 Canvas | `MathBlock` → `LatexText` | JLatexMathDrawable |
| Mermaid 图表 | WebView | `Mermaid` | 本地 mermaid.min.js + JS Bridge |
| HTML/SVG 代码 | WebView | `CodeBlockPreview` | loadDataWithBaseURL |
| 图片 | 原生 | `ZoomableAsyncImage`（点击进入全屏多图查看器） | Coil3 |
| 表格 | 原生 | `DataTable` | Compose 自定义布局 |
| HTML 块 | 原生 | `SimpleHtmlBlock` / `MarkdownNew` | Jsoup → Compose |
| 全屏预览 | WebView | `WebViewPage` | 独立页面 |
| Markdown 全文预览 | WebView | `mark.html` 模板 | markdown-it + KaTeX + Mermaid + highlight.js |

---

## 11. 关键设计决策

1. **双路径 Markdown 渲染**：纯 Markdown 走 AST 路径（性能更好），含 HTML 走 DOM 路径（表达力更强）。通过 `containsHtml()` 检查自动切换。路径 B 传入原始内容由 `MarkdownNew` 独立预处理。

2. **异步 AST 解析**：`MarkdownBlock` 在后台线程（`Dispatchers.Default`）解析 AST，通过 `snapshotFlow` + `mapLatest` 响应内容变化，避免流式更新时主线程阻塞。

3. **流式安全降级**：`SelectionContainer` 在流式期间禁用（防 `ConcurrentModificationException`）；`completeCodeBlock` 在代码围栏未闭合时禁用 WebView 预览，统一走原生渲染。

4. **LaTeX 原生渲染而非 WebView**：使用 JLatexMath 在 Compose Canvas 上绘制，避免 WebView 的性能开销。行内公式通过 `splitLatex()` 按顶层运算符拆分，实现文本流内换行。

5. **WebView 固定高度 + 全屏切换**：内联场景统一 200dp 高度，通过全屏页面获得完整交互。避免 WebView 在 LazyColumn 中动态测量高度的性能问题。

6. **配色 CSS 变量注入**：Compose `ColorScheme` → `toCssHex()` → CSS 变量 / Mermaid themeVariables，实现主题实时同步。

7. **防重复加载**：`WebViewState` 通过 `lastLoadedData` 和 `forceReload` 机制，避免 Compose 重组时重复触发 `loadDataWithBaseURL`。

8. **JS 接口安全**：`@JavascriptInterface` 仅暴露必要方法，在 `onReset`/`onRelease` 时主动 `removeJavascriptInterface` 清理。

9. **内联 Mermaid 离线可用**：Mermaid 脚本从本地 assets 加载（`WEB_VIEW_ASSET_URL`），不依赖 CDN，确保离线环境下图表正常渲染。全文预览的 Mermaid 仍使用 CDN（esm.sh）。
