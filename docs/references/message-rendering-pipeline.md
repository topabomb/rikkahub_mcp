# 消息渲染管线

本文描述已授权的消息显示投影如何成为原生 Compose 内容或 WebView 文档，以及交互、导出和销毁的边界。
页面导航与窗口承载见 [界面架构](ui-architecture.md)；请求转换见 [请求上下文](request-context.md)，
文件登记与生命周期见 [多模态与资源持久化](multimodal-context-and-turn-durability.md)。

## 1. 数据流与消息分组

```text
ConversationPresentation 的有序消息、阶段和预览能力
  → ChatMessage / groupMessageParts：按消息部分分组
  → 原生正文、工具/子助手卡片、Markdown 或媒体
  → 需要脚本渲染时，由原来源创建独立 WebView 文档
```

渲染只消费查询投影，不修改持久消息、选择分支或工具状态。流式投影保留其他 variants 与 selectIndex；
`visualTransform()` 只改变显示，不能代替输入转换或终态提交。UI 工具身份使用 `localCallId`，
运行与交互由 `ToolLivePhase` 决定，不能用 Provider call ID、列表位置或 output 是否为空推断。

`groupMessageParts` 将连续推理和普通工具合成 `ThinkingBlock`；`assistant_call` 单独形成
`SubAssistantCallBlock`，正文和媒体使用 `ContentBlock`。`UIMessagePart.Step` 通常不可见，
仅当其对应已接纳的上下文变化时结束前组并插入 `ContextUpdateBlock`。
普通 Step 不切断折叠时间线，展示分组不改变原消息顺序。

折叠推理块时，待审批/待回答工具以及 `generate_image` 保持可见，保证用户操作和图片结果仍在原时间线位置。
工具调用本身属于可见内容，即使尚未执行或没有结果；空白文本仍为空。
子助手卡片只显示目标、任务、阶段、有界回答预览及待回答问题，不以过程文本或百分比冒充完成结果。
卡片导航借用父页面授权，具体详情与执行状态由 [子助手架构](sub-assistant-architecture.md) 维护。

工作区产出提示只从 `workspace_write_file` / `workspace_edit_file` 的成功且结构有效结果提取，
不从请求参数或“存在结果”推断写入成功；统一 diff 来自显示 metadata，不混入 Provider 工具文本。
工具的本地化名称只用于显示，协议名仍是执行和持久化身份。

## 2. 应用上下文变化展示

应用上下文条目不作为额外消息节点插入列表。`ConversationPresentationSnapshot.context` 提供轻量摘要和来源标记，
`MessageContextSummary.updates` 只包含实际新增 EXTERNAL 变化的 Step、requestId 和类别，正文按授权查询读取。
纯 streaming 不重新扫描全部历史或读取正文。

主聊天与子助手时间线在实际接纳通知的 Step 边界显示标签：首请求位于输出开头，中途位于上一批工具结果之后、
受影响输出之前。同一请求合并变化类别，不同请求分别定位，沿用历史不生成新标签。
有通知但无正文的失败/取消消息仍显示该边界和原终态；没有变化的空消息不增加入口。

标签使用稳定类别次序与完整无障碍名称，不列资源 ID 或数量。消息“更多”菜单和子助手请求区不提供聚合上下文入口。
预置/摘要沿原正文来源显示，Starter 沿开场详情入口查看；它们不转成“上下文变化”。

`ConversationContextDetails` 固定用户打开时的 requestId，后续更新不切换阅读对象。
`forUpdate` 只展示该请求新增的 EXTERNAL 条目和分区；不混入初始披露、恢复、System、时间或规则。
混合状态包的完整原文和技术来源仍可按需展开；旧记录没有逐项差异时明确是当时完整状态，不推测修改前内容。
长正文渐进展开，结构化内容只由保存的正文和类型化来源生成，不读取当前 Settings 重新解释历史。

`ConversationQueryService.contextDetails` / `contextContent` 提供目录与按需正文。
读取前后复验页面 lease、域、选中 Assistant variant 和请求关联；相同 entry 在弹层内复用，
关闭、切域或切换所选回复后取消读取并拒绝迟到结果，失败保留原诊断。
编辑因果 USER 不隐藏仍保存的所选回复通知，兄弟回复之间不混合。
没有 admission 的历史原文可被授权读取，但不伪造通知、请求记录或完整 HTTP 快照。

请求接纳早于 Provider IO，详情展示实际状态，不把“已接纳”说成模型已收到。
历史可读与新请求可回放是不同判断，规则见 [请求上下文](request-context.md)。

## 3. Markdown、代码与原生媒体

Text 先经过助手的视觉正则，再进入 `MarkdownBlock`；视觉规则与生成管道的持久化转换是不同机制。
流式生成期间禁用文本选择，避免不断重建的 selectable 与选择工具栏产生竞争；生成结束后恢复。

`MarkdownBlock` 预处理 LaTeX 分隔符时避开代码块。首次同步解析，后续通过
`snapshotFlow`、`mapLatest` 与后台 dispatcher 更新 AST，旧解析不能覆盖新内容。

| 内容 | 渲染路径 | 主要边界 |
| --- | --- | --- |
| 普通 Markdown | IntelliJ Markdown AST → 原生 Compose | 文本、列表、引用、表格、代码和公式按节点分发 |
| 含 HTML 的 Markdown | `MarkdownNew` → HtmlGenerator → Jsoup DOM → Compose | 独立预处理；支持选定标签和内联文本样式，不是浏览器完整 CSS 布局 |
| 普通代码 | `HighlightCodeBlock` → `highlight` 模块 | 行号不进入复制文本；显示换行、折叠与字体连字不修改原文 |
| LaTeX | JLatexMath 原生 Canvas | 行内拆分支持换行，失败保留单体公式；块公式可横向滚动 |
| Image part | `ZoomableAsyncImage` / Coil | 使用已授权 ImageSource，加载占位不可点击 |
| Document / Audio / Video | 原生附件入口 | 经文件应用服务导出独立副本后交给外部应用 |

企业动态摘要的 `MarkdownSummary` 只投影文字和样式，不交付链接或媒体动作，展开后才使用完整 Markdown 渲染。
摘要不是读取企业文件或聊天历史的权限。

### 代码预览与完整性

`HighlightCodeBlock` 只为已闭合代码围栏提供 Mermaid 或 HTML/SVG 预览；流式未闭合内容统一显示源码高亮。
HTML/SVG 默认源码，用户显式切换预览；Mermaid 完整后渲染图形。
HTML 代码直接成为文档内容，SVG 包装为展示文档；两者均使用下述文档隔离与授权规则。

内联 Mermaid 的 `buildMermaidHtml` 转义代码并加载本地 `mermaid.min.js`，不依赖 CDN；
当前主题映射到 `themeVariables`。代码、主题或来源变化重建整个文档及原生桥，避免旧实例复用新输入。

Markdown 全文预览另经 `buildMarkdownPreviewHtml` 和 `assets/html/mark.html` 构建，
只提取消息顶层 Text，随原宿主进入全屏预览。该模板从 CDN 加载 Markdown、公式、高亮及图表依赖，
不具有内联 Mermaid 的离线保证；其图表主题使用媒体查询而非内联 M3 配色映射。
依赖版本与模板参数以资源文件为准，不在参考文档复制版本清单。

## 4. 图片、链接与导出来源

`RichTextHost` 为渲染树提供链接、预览和唯一待处理文档选择器。
`RichTextActions.exportText` 未提供时，表格和代码块只保留复制，不显示没有处理者的保存/下载动作；
远程文件的 `RestrictedMarkdown` 使用这一边界，正文文件下载仍由文件页负责。聊天/子助手借用 `ConversationViewLease`，
共享用户配置预览使用 `UserConfiguration`，开发示例与更新说明使用 `Static`。
渲染组件不访问 Store、不根据当前空间重新推断来源；缺少交互宿主的离屏树只显示内容，不能借系统默认 URI handler 打开资源。
两个 Markdown 引擎及 HTML 链接共用原宿主的 URI handler。

`LocalConversationImages` 在点击时按当前分支顺序收集顶层和 Tool.output 中的图片，过滤空加载占位，
形成会话相册；未命中相册时单图打开。Markdown/HTML 正文图经 `LocalImageSourceResolver` 解析，仍按单图浏览。
背景和输入附件不并入消息相册。查看器窗口与动作承载见 [界面架构](ui-architecture.md)。

图片预览、信息与保存沿用同一 `ImageSource`。本地路径不能代替文件 ID 和原页面授权；
解析失败不回退直接读文件。共享配置图片必须仍被个人配置引用，网络与内联图片也保留宿主权限。
非图片附件通过 `MediaExportService.openAttachment` 交付独立临时副本，不向外部应用授予原件路径。
文件复制、最终交付复验及失败/取消补偿见 [资源持久化](multimodal-context-and-turn-durability.md)。

代码和表格下载在点击时冻结正文、文件名、MIME 与来源。选择器返回由 `MediaExportService` 接收，
包括宿主已取消或原请求缺失的情况；失败清理新文档，取消继续传播。
旧选择器未返回前不被新点击替换，晚到回调不能导出新页面内容。

聊天截图的离屏 Compose 树只携带原来源和预览投影，不安装交互选择器。
`BitmapComposer` 归导出协程直接持有，捕获完成或取消都拆除组合树，未交付 Bitmap 被回收；
不存在独立 Handler 或第二协程作用域延续导出。

单附件查询的预期缺失或 `ArtifactProjectionException` 仍关闭访问，不中止整段聊天。
非预期数据库或 payload IO 故障保留完整日志，并在 `AttachmentPreview.diagnostic` 提供脱敏诊断；
失败投影的 URI、ImageSource 和 fileTarget 均不提供能力，也不能通过原 URL 再次降级为未校验图片读取。
原附件/工具/富文本位置使用 `AttachmentPreviewDiagnostic` 显示紧凑摘要及可展开、复制的详情。
`LocalAttachmentPreviewRetry` 只由主聊天或借用详情宿主提供原查询重试；无宿主的只读导出不安装重试动作。

## 5. WebView 文档与生命周期

富文本和 Mermaid 共用 `RenderedContentWebView` / `rememberRenderedContentState`，企业 Portal 使用独立宿主。
每份渲染文档拥有随机 `.invalid` origin，关闭 DOM Storage，隔离 Cookie、IndexedDB 与浏览器缓存身份；
不修改 Portal 的浏览器状态。原来源尚未授权或已撤销时不创建 WebView，旧来源的授权结果不能给新文档使用。
`RenderedContentReadState` 区分 Loading、Unavailable、Failed 与 Ready；授权读取尚未完成时不显示不可用。
非预期初始化、刷新或文档资源读取异常保留完整诊断，显式重试复用原 RenderedContent 与来源，不能重建失效能力。
旧 WebView 回调只更新其原读取尝试，不能覆盖新文档或新尝试；预览失败或销毁不主动关闭借用父 lease，
确认撤权沿原查询门禁关闭失效 lease 并释放预览。

本地图片通过文件服务授权读取，`file:` 与 `/upload` 资源映射到当前文档的绝对虚拟 origin，
包含动态节点与 CSS；外部 `<base>` 不能把本地路径改发网络。
WebView 不得自行读取任意 file/content 路径，链接交由原宿主处理。模型 HTML 不获得 Portal Bridge；
HTML/SVG 预览不注入业务 JavaScript 接口。

`WebView.kt` 封装 AndroidView 的生命周期：

- 创建时配置客户端、设置与文档需要的接口；数据比较避免普通重组重复加载，刷新可显式重新加载。
- `onReset` 停止加载并移除接口；重新使用前不能保留旧文档的交互能力。
- `onRelease` 只清空匹配实例的状态引用，停止加载、移除接口与客户端、清理内容后销毁原生 WebView。

来源、代码或主题替换后，迟到的加载/桥接回调不能写入新文档状态。
`ContentPreviewPage` 只持有进程内 `RenderedContent` 和原来源；导航不保存 HTML 或授权对象。
重建后显示不可用并要求从原页面打开，不读磁盘缓存重建能力。刷新复验来源，撤权销毁 WebView，
关闭预览不主动关闭借来的父页面 lease。该页面提供预览与诊断，不成为通用浏览器。

### Mermaid 导出

PNG 导出由用户动作产生请求 ID，经 `exportSvgToPng(requestId)` 返回原生桥；
只接受当前待处理请求的一次结果。脚本未就绪可重试，旧回调不能完成新请求，新文档不重放旧导出计数。
结果通过原宿主 ImageSource 和 `MediaExportService.saveImage` 验证、读取及发布；
不在桥接线程直接解码或写相册。组件销毁取消未完成导出，失败保留诊断及重试能力。

## 6. 验证与实现入口

消息分组从 `ChatMessage` / `ChatMessageCot` 进入，文本解析从 `Markdown` / `MarkdownNew` 进入，
文档预览从 `HighlightCodeBlock`、`Mermaid` 与 `RenderedContentWebView` 进入；
正文查询和导出始终使用上述应用端口，不能在新 renderer 中复制权限判断。

验证应覆盖：

- 流式更新、普通 Step 和变化 Step 的分组；折叠不能隐藏待处理交互，失败空消息仍能查看已接纳变化。
- 查询使用保存的来源及正确 variant；关闭详情、撤权和迟到结果不混入新页面。
- 两种 Markdown 路径、未闭合代码、图片占位与相册顺序，复制内容保持原文。
- 文档替换、重组、全屏返回与销毁不重复加载或复用旧桥；本地资源映射、撤权和动态节点仍遵守原来源。
- 选择器迟到回交、导出取消、补偿失败和 Mermaid 旧请求回调不能错误交付。

纯分组/投影由单元测试证明；WebView、Compose 选择、图片解码和系统文件交付需要对应 Android 场景。
完整分层见 [测试策略](testing-strategy.md)，测试存在不等于设备或真实服务验收完成。
