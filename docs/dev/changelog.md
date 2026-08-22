# 功能迭代清单

> 本文档记录 Fork 精简落地（0.0.2）后的功能迭代历史。
> 精简过程见 `fork-simplification-plan.md`，精简前架构见 `original-architecture.md`。
> 每次功能迭代提交时必须更新本文档，该文档保持精简描述的风格。

---

## 0.0.17（versionCode 17）— 2026-08-17 ~ 2026-08-22

### 新增

- 附件投影架构：图片按当前模型能力投影，能看图的模型保留原图，不能的得到引用行与提示，替代逐轮 OCR 改写
- `inspect_attachments` 工具：模型不能看图且配置了附件识别模型时，按需读取附件内容返回文本
- 附件识别模型设置项：未配置即关闭；旧 OCR 模型自动迁移，OCR 提示词设置移除
- `assistant_call` 可附带最多 4 个任务相关附件；第一阶段支持图片。主助手只决定传哪些，Target 用自己的 resolved model 与统一投影链处理
- 会话图片会盖上稳定 `attachment:<uuid>` 句柄，模型可在工具参数里引用；外部 HTTPS 图先落地再注入 Child
- 子助手可按配置调用 `generate_image`；主卡片用轻量引用显示交付物，Caller 默认只拿文本和 `artifacts[]`，点名 `extras=["artifacts"]` 后才按 Caller 能力拿到原图或 visual observation
- `TurnExecution` / `ToolExecution` 双表（DB v5，`Migration_4_5`）：每轮对话与每次工具执行的检查点与终态（COMPLETED / CANCELLED / FAILED / INCOMPLETE / INTERRUPTED / AWAITING_APPROVAL）持久化，FK 级联删除
- 启动时在接受新聊天操作前恢复遗留进行中的轮次：标为「已中断」，结果未知的工具写不可自动重试的协议结果
- 消息终态（取消/失败/不完整/中断）在 UI 显示明确的本地化标签；媒体保存失败显示可见占位

### 变更

- `assistant_call` 进入 Coordinator 前的参数错误改为与终态一致的 `status` + `reason` 信封
- 附件能力判定收敛到 `AttachmentProjectionTransformer`：移除 per-turn OCR observation、`artifact_delivery` 三态与 Caller 快照链；`generate_image` 结果移除模型可见的 `media_id`
- `/upload` 注入语义修正为「会话共享文件（上传与生成的媒体）」
- Target 不再永久过滤 `generate_image`；未开启 `TextToImage` 或没有有效默认图片模型时仍不注册
- Target 非交互下需审批工具自动拒绝的返回统一为 `tool_not_permitted` + `approval_unavailable` + `message`：语义为「需要审批但当前运行环境无法提供审批，不要原样重试」；只拒绝当前 ToolCall，不终止整个 Run。`ask_user` 仍走交互桥接，与审批错误协议无关
- 文件管理删除收口两套语义：新增 `ManagedFileDeletionService`，显式删除前先解除助手背景/头像等可变引用；`FilesManager` 物理删除 API 更名为 destructive 语义；「清理」改为「清空」，确认框显示实际影响
- `generate_image` 消息卡把执行结果与文件可用性分开：生成成功但文件缺失显示「图片已生成 · 文件不可用」，`artifact_missing` 移出失败文案
- 工具先落盘「开始执行」再真正执行，逐个提交结果；任一崩溃点后协议历史都完整合法
- 下一次请求对非成功 assistant 回复只用安全投影：剔除未完成思考、开放工具调用和残缺 Base64，屏幕上仍完整保留原文
- Base64 图片不再持久化进数据库：收尾时尽量转存本地文件，失败替换为 typed 占位；工具结果内嵌图片同样处理
- 附件处理顺序调整：历史 artifact 先物化，再做附件提示注入与图片能力适配

### 修复

- 模型设置页：快速/图片生成/压缩模型支持清除回未配置；快速模型未设置时标题与建议回退聊天模型，不再静默失效
- 模型设置页：标题提示词的变量说明错用建议的键，已拆分；移除死参数
- 助手配置页：聊天模型支持清除回「跟随默认模型」；`ModelSelector` 移除不可用的死参数 `allowClear`，改为 `onClear` 回调
- 等待工具审批不再当成一轮生成完成：不启动标题/建议等完成副作用
- 标题、建议、置顶改为列级更新并 merge 当前 Session，避免旧 Conversation 整对象覆盖正在生成的消息树
- Conversation 普通状态更新不再按瞬时文件差做物理 GC；压缩等生命周期操作走统一的引用检查
- 历史 Tool artifact 缺失时保持执行 `status=completed`，只把文件标为不可用
- 文件管理删除正被助手背景/头像引用的文件后，不再留下悬空引用（背景透明却无背景图）
- 已完成的一轮生成不会再被误标为「已取消」（停止请求与收尾竞态）；停止已结束的生成不再残留取消原因
- 等待审批或已有终态的 assistant 消息不再被当作无效消息移除
- OpenAI Responses 断流/incomplete 不再统一按失败收口（其余 Provider 逐协议接入为后续工作）

---

## 0.0.16（versionCode 16）— 2026-08-13 ~ 2026-08-17

### 新增

- 聊天可按需开启 `generate_image`，用全局默认图片模型出图；用户明确要求并审批后，可把独立副本设为当前会话助手背景
- 设置页补齐默认文生图模型；页面与工具共用队列和 Gallery。Gallery、聊天图、背景各存一份
- 文件管理增加「文生图」Tab
- **图片查看器**：聊天可按会话时间线左右翻页看图；文生图结果、图库和文件管理同样支持组内翻页。支持点按关闭、上下拖关闭和页码
- 查看器可查看图片信息，并把图片设为助手背景。聊天用于当前助手；其他入口先选助手，确认后再写入
- 删除文件或生成图时会先确认，并说明会影响哪些位置

### 变更

- 开启背景图或渐变后，聊天页 chrome 卡片（思考过程、气泡、子助手卡等）按 `ChatSurfacePolicy` 略微透明，让背景透出；代码、表格、公式和媒体产物保持不透明
- Target 看不到该工具；没有有效默认图片模型时也不注册
- 模型只看到 `/upload/<file>`；chat copy 缺失时标为 `artifact_missing`
- 子助手失败分为政策拒绝、Provider 错误和运行时错误，不回显政策检查类型
- `ask_user` 与普通工具审批共用同一套音效
- 文生图一次结果最多展示 4 张
- 文件管理中的图片可点开浏览，非图片仍不可点
- 文生图工具详情可复制提示词，调用详情与其他工具一致

### 修复

- 文生图编辑走共享队列；取消背景更新不再被改写成普通失败
- 清空 Upload 会提示同时删掉聊天副本和助手背景
- 查看器保存提示改为各语言本地化
- 图库删除失败也会弹出错误
- 文生图调用失败不再一律 `provider_error`：按政策拒绝、限流、额度、鉴权、权限、非法请求、服务不可用等分类，并带回脱敏裁剪后的 `detail`

---

## 0.0.15（versionCode 15）— 2026-08-11 ~ 2026-08-13

### 新增

- **子助手同步委托**：`assistant_manage`、`assistant_inspect`、`assistant_call`。主助手按 Catalog 与授权把任务交给 Target，Target 在独立 Child 会话中工作。`assistant_inspect` 按需查看人设、工具名、技能和局部记忆；`assistant_call` 默认回传 `tts_stats`，完整 `tts` / `tool_calls` 由 `extras` 按需返回，`runtime_error` 另带回裁剪后的 `detail`。`assistant_manage` 的 CREATE 不审批，UPDATE/DELETE 必须审批；管理结果只回 `action` 与 `id`
- **可恢复执行与交互**：Room v4 持久化 Child / run / 交互；支持启动恢复、Fork、孤儿清理和 durable 删除；主聊天有调用卡片、`ask_user` 与只读详情
- **通用生成原语**：`messageId + toolOrdinal` locator、phase/checkpoint/finished、动态工具集、非交互审批
- **模型与搜索**：注册 qwen-3.8-max、mimo v3、Claude 5、muse；搜索为关 / 本地 / 模型内置

### 修复

- **ask_user 与卡片**：非法参数在审批门口拒绝；回答只在服务端收下后锁定；交互与 step 上限分开；Child 未写入时详情不再误判不可用
- **启动恢复**：恢复完成前禁止会话写入；中断收口时 Child 超时不丢掉 Master 终态；扫描只加载顶层会话和被引用的 Child
- **协议与工具 Schema**：Claude / DeepSeek reasoning 按端点映射；MCP 保留 `$defs/$ref/$schema`；结构化工具结果可免截断
- **隔离与一致性**：`conversation_search` 不含 Child；lineage 按当前选中分支；Settings 写入成功后才发布；Memory 执行前重验 owner
- **TTS 与定位**：Master/Target 共用 turn 播放队列；工具审批不再依赖可能为空的 Provider `toolCallId`
- **更新与发行**：下载错误完成本地化；正式 Release 强制签名校验

### 变更

- Target 显式模型优先，未绑定则调用期继承 Caller；禁止再委托；撤权按快照 ∩ 当前配置
- 工具参数为完整 JSON Schema；ChatService 经 `GenerationToolSetFactory` 装配

### 文档与测试

- 新增 [子助手架构与执行流程参考](../references/sub-assistant-architecture.md) 和 [提示词、上下文注入与工具描述](../references/prompts-and-tools.md)
- 补充子助手、恢复、Memory/TTS、Room 与 Provider reasoning 回归测试
- 第 11 批上游同步记录见 [upstream-sync.md](upstream-sync.md)

---

## 0.0.14（versionCode 14）— 2026-08-10 ~ 2026-08-11

### 新增

- **聊天主界面自适应**：窗口宽度 ≥600dp 且高度 ≥480dp 时显示“会话列表 + 聊天详情”双栏；普通窄屏和矮横屏保持单栏抽屉。竖向折叠屏按 WindowManager 提供的真实铰链坐标分区，Tabletop 且有有效横向铰链时将聊天限制在上半屏
- **自适应临时弹层**：普通窄屏使用 `ModalBottomSheet`，宽屏使用限宽居中 `Dialog`；竖向铰链下限制在聊天详情侧，Tabletop 且有有效横向铰链时限制在上半屏
- **矮屏紧凑输入**：聊天可用高度低于 480dp 时改用单行紧凑输入，功能项保持完整

### 修复

- **会话助手一致性**：聊天标题、背景、模型能力、搜索、推理、快捷消息和文件能力统一以 `conversation.assistantId` 为准；仅在原助手已删除时回退当前全局助手
- **更新检查生命周期**：`UpdateChecker` 在 AppScope 内共享一个首次订阅才启动、启动后不重启的 `StateFlow`，同一进程最多请求一次；忽略的成功版本持久化到 DataStore，失败提示的关闭状态在当前进程内共享
- **宽屏弹层交互**：改用独立 scrim 与内容层，避免底部按钮或输入框被误判为外部点击；补充内容区、底部操作区和外部点击回归测试
- **非聊天页面回归**：设置文件页和图片生成页恢复固定双列，其他设置与管理页面继续使用原有全屏导航；同时恢复关于页 emoji 并修正受影响源码的 UTF-8 文本
- **聊天界面可读性**：收紧会话栏间距，调整标题层级、头像尺寸和消息区留白，修复 AMOLED 模式下选中会话对比度，并增强工具授权操作的视觉区分
- **质量门禁**：移除模块级 Lint 全局忽略配置；补充自适应策略、会话助手切换和更新检查测试

### 变更

- **适配职责收敛**：自适应双栏只作用于聊天主界面；设置、历史、统计等页面维持全屏逐页导航。根节点仅负责提供统一的 `LocalAdaptiveLayoutInfo`
- **弹层状态约定**：Dialog 路径不组合 BottomSheet；调用方以自身可见状态为准，不读取 `sheetState` 判断 Dialog 是否显示

---

## 0.0.13（versionCode 13）— 2026-08-05 ~ 2026-08-09

### 修复

- **Gemini 工具与思考状态回放**：保留任意 Part 的 `thoughtSignature`、服务端 `functionCall.id` 和思考草稿图原始数据，工具结果按同一 id 回传；带签名的空文本 Part 不再被流式合并吞掉，流式 JSON 解析失败会显式终止生成
- **Gemini 工具定义完整性**：自定义 Function 与内置工具合并到同一个 `tools` 数组，避免后写覆盖；保留官方支持的 JSON Schema `enum`
- **Claude 不透明思考状态**：持久化并原样回传 `redacted_thinking.data`；切换到不同模型配置时剥离旧模型 thinking/redacted blocks，避免签名跨模型复用
- **OpenAI/方舟 Responses 状态隔离**：除 `wireFormat` 外新增 endpoint `sourceProfile`，消息级 output items 与 Part 级 reasoning id/encrypted content 均按来源隔离，同时兼容没有新字段的旧会话
- **模型代际与 reasoning effort**：GPT-5 系列共享 developer role/temperature 行为，但基础版、点版本、Pro、Codex 分别按官方支持范围映射 effort；DeepSeek 按 Chat/Responses 端点接受的 `none`、`low`、`high` 转换项目级推理档位，不再透传无效枚举值
- **Responses 无状态历史回放**：持久化每次响应完整且有批次边界的 `response.output`，按官方顺序先回放整批 output、再追加函数执行结果，避免内置工具、`phase/status`、未来 output item 和并行工具关系丢失；流式响应在终态写入同一协议状态，并将未见终态的连接关闭显式报错
- **DeepSeek Responses 协议适配**：仅直连 `api.deepseek.com` 时使用 `content[].reasoning_text`；兼容 `reasoning_text.delta`、`reasoning_text.done` 与完整 output item 且避免重复追加，保留 reasoning 信封和明文思考；图片型工具结果按官方约束降级为字符串输出
- **火山方舟加密思考回传**：保持方舟默认生成 thinking summary 的行为，同时按官方文档请求 `reasoning.encrypted_content`，确保 `store=false` 工具续轮可手动回传完整思考状态

### 变更

- **LLM endpoint 职责收敛**：新增内部 `OpenAIEndpointProfile`，集中维护 host → vendor、Responses profile 和有限参数映射；不增加用户配置，不按 modelId 猜测代理线协议
- **协议架构文档重整**：`protocol-reference.md` 集中说明 Provider 选择、线格式、模型特例、不透明状态回放、持久化兼容与验证边界

---

## 0.0.12（versionCode 12）— 2026-07-31 ~ 2026-08-04

### 新增

- **Kotlin 原生语法高亮引擎**：将 highlight 模块从 QuickJS + prism.js 完全重写为纯 Kotlin 实现，移植 highlight.js 11.11.1 的 Mode 栈解析架构，支持 30+ 种语言（bash, c, cmake, cpp, csharp, css, dart, diff, dockerfile, glsl, go, ini/toml, java, javascript, json, kotlin, latex, lua, markdown, php, powershell, properties, python, ruby, rust, sql, swift, typescript, xml, yaml），配套 60+ golden fixture 单元测试
- **TTS 默认播放速度**：新增 `defaultTTSPlaybackSpeed` 设置项（DataStore 持久化，范围 0.5x～2.0x，默认 1.0x），在设置 > 语音 > TTS tab 顶部提供 Slider 调节，自动应用到所有 TTS provider
- **TTS 工具顺序播放**：同一轮生成中 AI 多次调用 `text_to_speech` 工具时，首次打断之前播放、后续追加到队列末尾顺序播放；新增 `ttsToolSequentialPlayback` 设置项（默认开启，可关闭恢复每次打断行为）。`AppEvent.Speak` 新增 `flush` 参数区分打断与追加；`autoPlayTTSAfterGeneration` 文案同步更新为“生成后自动朗读”
- **MCP 请求头隐私保护**：请求头值默认按密码遮罩，允许逐项临时显示；显示/隐藏按钮补充 5 语言无障碍文案

### 修复

- **Release 界面运行时类名被混淆**：提供商切换、模型覆盖和 TTS 类型标签改用显式本地化映射；模板预览与 MCP 错误不再把异常类名作为界面兜底，避免 R8 将 OpenAI/Google/Claude 或异常类型显示为 `y48`/`v48`/`r48` 等短名
- **DeepSeek V4 工具思考回传**：即使关闭“回传历史思考过程”，带工具调用的 DeepSeek V4 assistant 消息仍按协议完整回传 `reasoning_content`；普通历史回答继续遵守原设置，并避免多段思考内容只保留首段
- **DeepSeek 工具步骤原子关联**：Chat 非流式响应按 `reasoning_content`、`content`、`tool_calls` 的原始 assistant 关系保存；流式响应将迟到的思考和正文归入当前未完成工具步骤，并按 `tool_calls[].index` 关联并行工具参数分片，避免 delta 顺序导致下一轮工具历史缺少思考、正文错位或并行工具串参
- **DeepSeek Responses 思考格式**：直连 `api.deepseek.com` 时使用 `content[].reasoning_text` 回传思考内容，不再请求 OpenAI encrypted reasoning；非流式响应同时兼容 DeepSeek `reasoning_text` 与 OpenAI `summary_text`
- **OpenAI Chat/Responses 协议适配**：官方 Chat 改用 `max_completion_tokens`，o-series/GPT-5 使用 `developer` 指令角色，并过滤非官方 `reasoning_content` 与工具图片；两套流协议正确传播 JSON 内错误且不再记录完整 payload；Responses 另修正 `item_id`/`call_id` 混用、函数参数 delta 重复风险、失败/不完整终态静默、空摘要加密推理状态丢失、拒答输出及非严格函数工具语义
- **Moonshot K2.5/K3 temperature 参数报错**：K2.5/K3/K3_ALIAS 模型不支持 `temperature` 参数，发送时导致 API 400 报错；`isModelAllowTemperature` 新增 KIMI 系列检查
- **workspace 不支持 HEIC/AVIF/ICO 图片**：`IMAGE_EXTENSIONS` 扩展 `heic`, `heif`, `avif`, `ico`，工具描述同步更新；AI 读取这些格式图片不再返回乱码
- **Skills 列表同名冲突**：LazyColumn `key` 从 `it.name` 改为 `it.skillDir.absolutePath`，避免同名 skill 在不同目录时列表渲染异常
- **API 26-28 文件写入失败**：声明 `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion="28"`），修复 Android 8.0-9.0 设备上备份导出等文件写入操作失败
- **顺播状态跨对话干扰**：每轮生成创建独立的 TTS 工具播放状态，并在更新顺播状态前拒绝空白朗读文本，避免并发对话互相重置或无效调用占用首次打断机会
- **MCP 请求头显示状态错位**：删除请求头时同步重映射临时显示状态，避免上游按列表位置保存状态导致另一条密钥被意外显示

### 变更

- **上游消息类型结构同步**：同步上游 `b106e8bb` 的文件拆分，将 `UIMessagePart` 与 `UIMessageAnnotation` 移至独立源码文件；保留本 fork 已完成的类型精简，不恢复废弃的 `Search`、`ToolCall`、`ToolResult`
- **依赖升级**：material3 `1.5.0-alpha24` → `1.5.0-alpha25`，Navigation 3 `1.1.4` → `1.1.5`，baselineprofile `1.5.0-alpha07` → `1.5.0-beta01`
- **highlight 模块依赖清理**：移除 `quickjs`、`kotlinx-serialization-json`、`kotlinx-coroutines-core` 依赖和 `kotlin.serialization` 插件；`common` 模块的 QuickJS 依赖保留（`eval_javascript` 工具 + `QuickJSFetch` 仍需使用）
- **代码清理**：移除 `HighlightCodeVisualTransformation` 中无调用方的 `regex()` 死代码；`CodeBlockPreview` 硬编码 URL 改用 `WEB_VIEW_BASE_URL` 常量统一
- **构建配置收敛**：新增 Android library / Compose convention plugin，统一 compileSdk、minSdk、Java/Kotlin 17 和测试 runner；模块脚本仅保留自身差异
- **Release 优化规则精简**：改用 AGP 9 `optimization` 与 source-set keep rules，删除 library 空规则和模板文件；保留有真机扫码崩溃依据的 strict-full-mode 兼容开关

---

## 0.0.11（versionCode 11）— 2026-07-18 ~ 2026-07-29

### 新增

- **极简白和 Claude 主题**：新增 Minimal（纯白底 + 中性灰层级 + 低饱和蓝强调色）和 Claude（象牙白暖底 + 赤陶橙强调色）两个预设主题，主题选择器由横向滚动改为 FlowRow 四列网格布局
- **SAF 支持复制/移动文件到 workspace**：Root 加上 `FLAG_SUPPORTS_CREATE`，实现 `copyDocument`/`moveDocument`，文件管理器可将 workspace 作为粘贴/保存目标
- **Mermaid 内置离线加载**：将 mermaid 11.16.0 的 JS（3.5MB）打包进 assets，通过 `WebViewLocalAssets` 拦截虚拟域名 `measix.local/assets/**` 映射到 assets 目录，内嵌 WebView 与全屏预览页共用同一 baseUrl，首次渲染不再依赖 CDN
- **新会话就绪引导**：未配置可用对话模型时，在“配置本次会话”前展示与设置页复用的紧凑阻断卡片；配置卡使用主题常规表面色，仅对话模型行使用警告图标和状态标签。MCP、记忆、本地能力和本地工作区保持可选，状态文案压缩为短标签；模型项复用输入框底部的选择面板，其余项进入对应配置入口。标题行支持点击头像+助手名切换当前会话助手，右侧按钮跳转助手设置页。已有消息的会话仅在模型不可用时显示对应提示
- **会话就绪回归测试**：覆盖未配置、未选择、Provider 全禁用、仅有非对话模型、MCP 未配置/全禁用/已选择和工作区绑定状态
- **上下文管理双层策略**：Assistant 可显式开启请求级阶梯裁剪，默认关闭，开启后默认 80 条、范围 40～512；裁剪锚点按完整 USER 轮次分段移动，减少相邻请求的提示缓存前缀漂移且不改写会话。用户触发的语义压缩继续负责用摘要替换较早历史
- **本地生成链路参考文档**：基于上游有价值的生成管线文档，按 Measix Pilot 的通知解耦、Workspace、MCP、工具集合及上下文策略重写，并新增上下文管理决策文档
- **版本检查与发行参考文档**：新增当前客户端检查生命周期、版本优先级、下载委托、Play Store 过滤和 Release 构建契约说明
### 修复

- **消息模板时间破坏 prompt 缓存**：`TemplateTransformer` 改为取 `UIMessage.createdAt` 而非 `Instant.now()`，历史消息的 `time`/`date` 变量在每轮请求中保持稳定
- **Response API 内置工具覆盖自定义函数工具**：`buildRequestBody` 中两次 `putJsonArray("tools")` 互相覆盖，改为合并到同一个 key 下
- **图片裁剪失败静默忽略**：UCrop 返回 `RESULT_ERROR` 时弹出错误 toast 并记录日志
- **月之暗面 K2.6 保留式思考**：思考开启时发送 `thinking.keep=all`，K2.5 不支持 keep 参数不发送
- **cached tokens 按 provider 方言兜底解析**：OpenAI 嵌套 → Moonshot 顶层 `cached_tokens` → DeepSeek `prompt_cache_hit_tokens`
- **Kimi K3 裸 id 适配**：补充 `KIMI_K3_ALIAS` 匹配不带 kimi 前缀的裸模型 id "k3"
- **图标匹配 k3 模型 id**：`PATTERN_KIMI` 正则增加 `k3` 匹配
- **本地备份继承 WebDAV items 过滤**：`exportToFile`/`restoreFromLocalFile` 强制使用 `BackupItem.entries` 包含所有项
- **Skills 从 metadata 目录解析**：`createSkillTools` 移除 `SkillManager` 依赖，改用 `SkillFrontmatterParser` 和 `SkillPaths` 直接操作 `SkillMetadata`
- **工作区 bind mount 文件读取**：`workspace_read_file` 统一通过 `WorkspaceManager` 解析
  `/workspace`、`/skills`、`/upload` 与 rootfs 内部路径；PRoot 和文件工具共享同一挂载表，
  并为文本/图片读取统一保留 8 MiB 限制
- **TTS 下拉菜单小屏崩溃**：Provider、OpenAI voice、MiMo model/voice 改用有高度上限的
  `SelectTextField`，避免输入法弹出时 `ExposedDropdownMenuPositionProvider` 范围非法
- **MCP 导入冲突提示格式**：五语言字符串改用位置参数 `%1$d`/`%2$s`，消除 Android
  资源编译的多参数格式警告并确保参数顺序可本地化
- **无签名环境无法构建 Release**：仅在 `local.properties` 的四项签名信息完整时创建并
  绑定 release signingConfig；普通开发环境可生成 unsigned release，正式签名配置行为不变
- **Fork 后 Compose 稳定性配置仍引用旧包名**：`Conversation`、`MessageNode` 改为当前
  `net.weero.measix.pilot` 包名，并清除 release baseline profile 中已删除的 `limitContext` 规则
- **长按发送绕过模型校验**：普通发送与长按发送共用同一模型前置条件和本地化错误提示；生成服务也统一拒绝禁用 Provider 中的模型
- **Bing 忽略结果数量设置**：抓取结果统一遵守 `SearchCommonOptions.resultSize`
- **Room v1→v2 迁移使用错误表名**：`Migration_1_2` 改为迁移 v1 schema 中真实存在的
  `ConversationEntity` 表；迁移测试同步适配当前 SQLite API，并在模拟器上验证旧会话数据保留及
  `tags` 默认值
- **仪器测试配置漂移**：为 `highlight`、`material3`、`search` 补齐 AndroidX Test 依赖；
  `speech` 样板测试的包路径与期望 packageName 对齐当前 `me.rerere.speech` namespace
- **上下文压缩拆散对话轮次**：保留最近消息和 256 条分块的切点统一回退到 USER 边界，避免摘要输入或保留历史从孤立的助手/工具回复开始；压缩提示明确摘要会替换旧历史且不可撤销
- **本地能力说明误含语音识别**：会话配置卡改为列举语音播报、剪贴板和屏幕使用时间，和实际 Local Tools 注册表一致
- **工作区 proot Android 14+ 兼容性**：移除误加的 `PROOT_NO_SECCOMP=1`（恢复 seccomp 过滤器，修复 `mkdir`/`cd` 返回 ENOSYS）；附加 `-k 4.14.0` 内核伪装、`PWD` 回退、移除冗余 `cd` 和 `bash -l`。详见 `docs/references/workspace-architecture.md`
- **空会话切换助手无效**：`moveConversationToAssistant` 对未持久化的空会话 DB 查询返回 null 后直接退出；改为使用内存状态 fallback。`saveConversation` 对空会话跳过内存状态更新，改为始终更新内存状态、仅跳过 DB 持久化
- **助手头像点击无响应**：`UIAvatar` 在没有自身操作时仍拦截父级点击，导致配置会话标题和助手列表中的头像成为无响应区域；改为仅在编辑或显式点击时消费事件，头像与助手名称现在触发同一切换操作
- **切换助手覆盖会话状态**：活动会话改以内存状态为准，避免数据库旧快照覆盖尚未落库的更新；重复选择当前助手按无操作处理，不再意外清空会话文件夹归属
- **Grok 生图 size 参数 400**：`generateImage` 检测 Grok（x.ai baseUrl 或 grok modelId）时跳过 `size` 参数
- **删除会话未等待即跳转**：`deleteConversation` 返回 `Job`，抽屉 `onDelete` 等待删除完成后再刷新列表和导航
- **切换助手后新对话归属旧助手**：`updateSettings` 返回 `Job`，`AssistantPicker` 等待持久化完成后再创建新会话
- **cur_time/cur_datetime 占位符破坏提示词缓存**：移除这两个每次请求都变化的占位符，默认提示词改用 `{{cur_date}}`；已配置旧占位符的用户自动降级为日期值
- **battery_level 占位符破坏提示词缓存**：移除该每请求必变占位符，无需兼容（默认提示词未使用）
- **Workspace cwd 注入破坏 System 消息缓存前缀**：移除 workspace 引导中的 cwd 行，模型执行 cd 的结果已记录在对话历史中，System 消息不再随目录切换而变化


### 变更

- **许可证变更为纯 AGPL-3.0**：上游 RikkaHub 将许可证从"分段双重许可（Segmented Dual Licensing）"改为纯 AGPL-3.0，移除了商业用途限制和用户数量门槛。本地 LICENSE 文件已同步更新为 GNU 官方 AGPL-3.0 全文
- **上下文限制改为阶梯式裁剪**：先移除会逐轮移动并破坏提示缓存的旧 `contextMessageSize`，再以新字段 `contextMessageLimit` 适配引入上游 `2d61ba9`。本地没有照搬上游边界和参数：完整轮次对齐、显式开关、异常值归一化，并明确消息条数不是 token/window 上限；旧字段仍由 `JsonInstant.ignoreUnknownKeys` 安全忽略
- **SkillFrontmatterParser 抽离为独立文件**：从 `SkillManager.kt` 抽离到独立的 `SkillFrontmatterParser.kt`
- **移除 Custom JS 搜索**：删除任意脚本搜索运行时、配置 UI、资源和 search 模块 QuickJS 依赖；旧 `custom_js` 落盘项通过宽容解码安全跳过，若无其他 provider 则回退到默认 Bing
- **搜索模块职责收敛**：`search` 只负责 provider 配置、schema 和网络执行；说明文案迁移至 app UI；新增显式 `SearchProviderType` 工厂，移除 Compose 依赖和反射创建
- **抽屉助手入口重构**：当前助手区域改为独立选择器，头像、“当前助手”标签、名称和下拉箭头共同构成切换入口；右侧独立编辑按钮管理当前助手，下方助手设置继续负责全局管理，避免切换与管理语义混杂
- **阶段复核制度化**：首份九批复核改名为 [phase-1-review-2026-07-28.md](upstream-sync/phase-1-review-2026-07-28.md)，并在 [upstream-sync.md](upstream-sync.md) 中明确阶段复核的目标、价值、命名和冻结规则
- 依赖升级：material3 1.5.0-alpha23 → 1.5.0-alpha24；kotlin 2.4.0 → 2.4.10；ksp 2.3.4 → 2.3.10；sonner 0.3.9 → 0.4.0
- **配置卡片标题行与文案优化**：标题从纯文本"配置此会话"改为"为 [头像][高亮助手名] 配置此会话"左右布局，右侧设置按钮与抽屉风格一致；MCP/本地能力状态文案从"已启用n项"简化为"启用n项"；五语言适配前后缀拆分
- **默认系统提示词抽离常量**：将默认助手系统提示词从 `PreferencesStore` 硬编码抽离为 `Assistant.kt` 中的 `DEFAULT_SYSTEM_PROMPT` 常量，新建助手时自动填入
- **CI 自动化发行**：`release.yml` 从仅手动构建 Artifact 改为 tag 驱动发布 GitHub Releases，收敛写权限、自动提取 changelog、递归 checkout submodule 并清理临时签名文件；更新机制参考文档同步记录客户端与发行契约

### 上游同步

- 同步 rikkahub 上游 `8eebe950..2d61ba95`（2026-07-18 ~ 2026-07-28）的 22 个提交（19 个同步 + 3 个按 Fork 边界跳过），详见 `docs/dev/upstream-sync.md` 第九批检查记录
- 完整审查：补齐 bind mount 与 TTS 两个 P0，恢复上游五组回归测试并增加 K3 边界测试；修复 Crop 文案本地化、K3 图标误匹配、BOM、WebView 虚拟域名/遗漏文件及同步台账范围连续性
- 阶段复核：九批范围 `5b9be301..2d61ba95` 共 131 个上游提交连续闭合，无间隙、无重复；修正首批失效哈希、第六/七批检查点重叠和各批数量口径，详见 [阶段 1 复核](upstream-sync/phase-1-review-2026-07-28.md)

---

## 0.0.10（versionCode 10）— 2026-07-10 ~ 2026-07-17

### 新增

- **工作区文件编辑与预览**：点击工作区文件按类型分派——文本文件打开应用内编辑页（FILES 区可编辑保存，LINUX 区只读预览），图片走可缩放预览弹窗，其他文件交给系统应用打开。文件过大预览错误改用 `FileTooLargeException` 异常 + UI 层本地化格式化，避免硬编码中文异常消息在非中文 locale 下直接显示
- **一键清理聊天文件**：文件管理页新增清理按钮，一键删除分类下全部文件
- **Kimi K3 模型定义**：新增 Kimi K3 模型检测（vision + tool reasoning）

### 修复

- **WebView 预览崩溃**：HTML 内容从导航参数移出，改为写入 cacheDir 并传递 SHA-256 内容 ID，避免大段 HTML 撑爆 SaveableStateRegistry 触发 TransactionTooLargeException
- **CustomJs 搜索 API 400**：scrape 参数的 urls 数组补全 items 类型声明，修复 Gemini/Claude API 校验失败
- **工作区恢复备份后误删**：目录缺失时标记 BROKEN 而非删除记录，避免误删用户工作区与助手绑定
- **folder sync 孤儿记录**：syncFolder 改为双向同步，补录未登记文件 + 清理磁盘已删除的孤儿记录
- **proot 终端 SIGILL 崩溃**：部分 Android 设备上 proot 的 seccomp 加速与系统 seccomp 策略冲突，导致被追踪进程收到 SIGILL（signal 4）立即退出；为 ProotShellRunner 和 WorkspaceTerminalSession 添加 `PROOT_NO_SECCOMP=1` 环境变量，回退到纯 ptrace 模式以保证全设备兼容
- **AndroidManifest XML 解析失败**：移除 `AndroidManifest.xml` 开头多余的双 BOM 字符，修复"前言中不允许有内容"解析错误

### 变更

- **网络搜索开关迁移到助手级**：`enableWebSearch` 从全局 Settings 迁移到 Assistant，每个助手独立记忆是否启用网络搜索
- **TTS 工具描述更新**：从"设备 TTS 引擎"改为"所选 TTS 服务"，反映多 TTS provider 支持
- **建议回复提示词优化**：新增"help the assistant improve its answers"引导
- **TTS 引号描述补全**：补全中英文直引号显示，引号间加空格
- **MCP Error 详情展示**：McpStatus.Error 新增 detail 字段（含堆栈），SettingMcpPage 支持点击展开错误详情并复制；新增 McpConnectionKey 连接键机制——通过 connectedConfigs 追踪 Map 记录每个连接的配置，init 链 1 和 syncAll() 使用 hasSameConnectionParameters() 检测连接参数变化（URL/transport/headers/oauth token），变化时重连、仅工具开关变化时跳过（由下次 syncTools 自然刷新）+ 3 个单元测试
- 依赖升级：okhttp 5.4.0 / coil 3.5.0 / koin 4.2.2 / ktor 3.5.1 / slf4j 2.0.18 / uiautomator 2.4.0 / baselineprofile alpha07

### 上游同步

- 同步 rikkahub 上游 `449ce1e6..upstream/master`（2026-07-10 ~ 2026-07-16）的 24 个提交（11 个同步 + 2 个选择性引入 + 11 个跳过），详见 `docs/dev/upstream-sync.md` 第八批检查记录

---

## 0.0.9（versionCode 9）— 2026-07-04 ~ 2026-07-08

### 新增

- **MCP OAuth 2.1 授权**：支持 MCP 服务器 OAuth 授权流程（元数据发现、DCR/PKCE、浏览器授权、令牌自动刷新），不支持 DCR 的服务器可手动填写 Client ID
- **会话文件夹分组**：助手内文件夹分组，会话可移入/移出文件夹，支持新建/重命名/删除（DB Migration 2→3）
- **workspace `/tmp` 免审批**：`/tmp` 纳入可写安全区白名单
- **化学公式渲染**：KaTeX 新增 mhchem 扩展（mark.html），jlatexmath 1.4→1.5，支持 `\ce{}` 等化学公式语法
- **HEIF/HEIC 图片上传与裁剪**：扩展 HEIF/HEIC/AVIF 品牌码识别（heix/hevc/mif1 等 12 种），裁剪前自动将 HEIF 转为 JPEG 规避 UCrop 解码失败
- **imggen 自定义尺寸输入**：`ImageAspectRatio` 枚举替换为 `ImageGenSize`（携带实际 size 值），设置面板新增自定义尺寸输入框
- **TTS 直角引号朗读**：`extractQuotedContent()` 新增直角引号 `「」` 和白直角引号 `『』` 匹配
- **模型注册扩展**：新增 GLM-5.2、HY3、LongCat-2.0、Qwen 3.5/3.6/3.7 MAX、Doubao 2.0/2.1 模型检测
- **TTS 语气标记引导**：TTSProvider 新增可选 `promptGuidance`，text_to_speech 工具启用时将当前选中 provider 的引导注入 system prompt；MiMo 硬编码风格/音频标签引导
- **生成通知解耦**：新增 ChatNotificationManager 通过 AppEventBus 消费生成事件，承接 Live Update/完成通知、前后台判断与 1s 节流，ChatService 只发事件

### 修复

- **附件菜单按钮居中**：FlowRow 折行后未与首行左对齐
- **粗体字重**：SemiBold → Bold，修复 OEM 字体下粗体不生效
- **OpenAI 工具调用参数残缺 JSON**：流式中断导致 arguments 不完整，改用 `inputAsJson()` 归一化
- **Google/OpenAI 多模态工具调用**：工具返回的图片按模型输入模态回传，不支持图片的模型以文本占位替代；简化为仅处理文字和图片（移除 Video/Audio）
- **预览搜索跳转被打断**：`animateScrollToItem` → `requestScrollToItem`，避免键盘滚动打断跳转
- **搜索选择面板空白**：不支持内置搜索的模型残留 `BuiltInTools.Search` 状态时面板空白且无入口关闭，改为已开启时也显示开关
- **化学公式 `\ce{}` 渲染**：mhchem 改为 side-effect import + 传入已加载 mhchem 的 katex 实例给 markdown-it-katex 插件，修复插件使用内部自带 katex 实例导致 `\ce` 无法识别
- **中文弯引号匹配**：raw string 中的 Unicode 弯引号改为 `\u201C`/`\u201D`/`\u2018`/`\u2019` 转义，修复正则无法匹配
- **状态栏沉浸**：助手详情页（Basic/LocalTool/Memory/Prompt/Request/Mcp）和设置页（Files/Mcp）的 innerPadding 移入滚动容器内部，内容可滚动到顶栏下方
- **扩展管理按钮关闭面板**：导航回调改为直接关闭内外两层 sheet 并立即跳转，消除面板残留视觉割裂
- **工作区导入按钮遮挡**：FAB 移至顶栏 IconButton，避免遮挡文件菜单上下文菜单
- **流式输出丢字**：所有 Provider 的 callbackFlow 改为无界缓冲（`Channel.UNLIMITED`），trySend 失败记录日志；ClaudeProvider 将 trySend 移到 close() 前修复最后一个 chunk 丢失
- **MCP SSE 无限重连**：StreamableHttpClientTransport 的 SSE 通知流重试耗尽不再触发整体重连，新增 `isSseStreamGiveUpError` 过滤
- **聊天头部模型名截断**：`provider/model` 格式的长模型名 `maxLines` 改为 2 + `TextOverflow.Ellipsis`
- **输入框底部间距**：键盘收起时增加 8dp 底部呼吸间距

### 变更

- MCP SDK 0.13.0 → 0.14.0
- MCP UI 文本统一为"MCP"，Loading 指示器修复为按配置精确匹配
- MCP OAuth 健壮性增强（Job 自杀修复、连接泄露修复、清除授权立即断开），详见 `docs/dev/mcp-lifecycle-analysis.md` 第十四章
- 压缩上下文对话框保留消息数改为手动输入（OutlinedNumberInput 替换分段按钮）
- Compose BOM 2026.06.00→2026.06.01、Material3 1.5.0-alpha22→1.5.0-alpha23、Navigation3 1.1.2→1.1.4
- 预存偏离对齐：material3AdaptiveNav3 1.3.0-beta02→1.3.0-rc01、lifecycleViewmodelNav3 2.10.0→2.11.0、lifecycleRuntimeKtx 2.10.0→2.11.0
- **fileSizeToString 统一重构**：合并 4 处分散的 `formatBytes` 实现，统一为三位有效数字 + 支持到 TB
- **移除 Google Imagen 图像生成**：`Provider.generateImage` 改为默认 `error()` 实现，删除 GoogleProvider 的 Imagen predict 接口
- **imggen 移除重复模型选择器**：设置面板中的 ModelSelector 与页面顶部重复，已移除
- **replaceRegexes 正则缓存**：流式输出时正则编译结果用 `SimpleCache` 缓存（10 分钟过期），避免长回复期间重复编译
- Gradle 9.5.0 → 9.6.1
- **清理历史兼容代码**：移除 `UIMessagePart.Search/ToolCall/ToolResult` 废弃类型及全部关联迁移函数（`migrateToolMessages`/`migrateToolNodes`/`migrateToolParts`/`toSortedMessageParts`），fork 无历史数据负担
- **默认助手提示词增强**：system prompt 新增 SVG/Mermaid 图表建议，引导 LLM 主动使用可视化图表辅助说明

### 上游同步

- 同步 rikkahub 上游 `4b2fd4b9..upstream/master`（2026-07-01 ~ 2026-07-03）的 9 个提交，详见 `docs/dev/upstream-sync.md` 第四批检查记录
- 同步 rikkahub 上游 `5b39e05d..upstream/master`（2026-07-05）的 6 个提交，详见 `docs/dev/upstream-sync.md` 第五批检查记录
- 同步 rikkahub 上游 `ef564dca..upstream/master`（2026-07-06 ~ 2026-07-08）的 17 个提交（16 个同步 + 1 个补充同步），详见 `docs/dev/upstream-sync.md` 第六批检查记录
- 同步 rikkahub 上游 `624ab635..upstream/master`（2026-07-08）的 5 个提交，详见 `docs/dev/upstream-sync.md` 第七批检查记录

---

## 0.0.8（versionCode 8）— 2026-06-30

### 新增

- **日历查询与创建工具**（`calendar_query` / `calendar_create`）：AI 可查询设备日历事件（支持 `today/week/month` 预设、自定义时间区间、关键词筛选，返回事件标题/描述/地点/时间/日历名）和创建新事件（需用户审批确认）。开关开启时通过 PermissionManager 框架申请 `READ_CALENDAR` + `WRITE_CALENDAR` 权限。聊天中工具调用步骤显示日历图标 + 事件摘要

### 修复

- **屏幕使用时间统计偏差**：改用 `UsageEvents` 事件配对计算真实前台时长（`MOVE_TO_FOREGROUND`/`MOVE_TO_BACKGROUND`/`SCREEN_NON_INTERACTIVE` 逐事件结算），排除桌面 launcher，新增 12h 向前回看 + 区间裁剪，结果更贴近系统"屏幕使用时间"
- **S3/COS 备份恢复下载丢数据**：`downloadObjectToFile` 改用 `toInputStream().copyTo()` 替代手动 `readAvailable` 循环，修复竞态导致文件末尾数据丢失；`AwsSignatureV4` 修复腾讯云 COS endpoint（`bucket.cos.region.myqcloud.com`）已含 bucket 名时重复拼接的问题
- **Skills 扩展面板角标计数偏大**：外部删除 `/skills/` 目录后 `enabledSkills` 残留"幽灵"技能名，新增 `pruneOrphanedEnabledSkills()` 在打开扩展面板时自动清理
- **后台文本生成默认推理级别**：标题/摘要/建议的后台生成从 `ReasoningLevel.OFF` 改为 `AUTO`，让支持推理的模型自动利用推理能力
- **聊天页动态渐变背景动画循环跳变**：`phase` 函数增加 `loops` 参数，通过最小公倍数圈数（20/1/10/10）消除 `2π→0` 回归跳变
- **IME 展开时输入栏底部圆角不贴合**：键盘弹出时输入栏底部两角变直角，贴合 IME
- **助手头像选图无裁剪**：选图后进入 1:1 裁剪流程（UCrop），锁定正方形比例，裁剪后保存

### 上游同步

- 同步 rikkahub 上游 `a6e7a305..upstream/master`（2026-06-27 ~ 2026-06-30）的 9 个提交，详见 `docs/dev/upstream-sync.md` 第三批检查记录

---

## 0.0.7（versionCode 7）— 2026-06-27/28

### MCP 生命周期架构级重构

> 详细技术分析见 `docs/dev/mcp-lifecycle-analysis.md`，本节为功能摘要。

**三条恢复链**覆盖移动端全部场景：① settings 变更 → add/remove Client；② `ProcessLifecycleOwner.onStart` → syncAll 健康检查；③ `ConnectivityManager.NetworkCallback.onAvailable` → syncAll（WiFi↔蜂窝切换，比 transport.onClose 快 10-30s）。

**重连分层**：5 次指数退避（31s 总计）→ Dormant 休眠（60s × 30 次 = 30 分钟兜底）→ Error。网络离线时不消耗重连尝试（省电），每 10s 检查恢复。

**工具执行**：四级异常分级（超时→降级文本 / 取消→传播 / 连接错误→重连 / 其他→错误文本）；`finishPendingTools`（仅 Pending）+ `finishInterruptedTools`（非 Pending 中断）互补处理，根治超时工具误报"已拒绝"。

**架构**：SettingsStore（配置唯一数据源）、McpManager（连接+状态+策略，Uuid key + per-server Mutex + cleanupServer）、ChatService（按助手过滤消费）。状态机 6 态含 Dormant，UI 全部状态有图标+文案。

**质量**：32+8 回归测试 / 15 处 runCatching CancellationException 审计 / 通知 `tools/list_changed` / 5 locale 翻译。

**日志质量**：消息上限 500 字符防 UI 撑爆；TextLog 配额 400（翻倍）彻底消除跨 tag 挤占；工具调用日志改为结果导向（成功/失败/超时），去除 "Calling tool" 噪声；失败日志追加异常类名（如 `McpError`）；ChatService/FilesManager 不再写完整 stack trace 到 LogPage（仅保留 message + 前 5 帧）；通知处理器和 closeClient 失败路径补日志。

#### Bug 修复摘要

| 问题 | 严重 | 修复方式 |
|------|------|----------|
| stale state：停启 MCP 服务器后会话故障 | **致命** | cleanupServer() + syncAll() 重建 |
| 自取消：reconnectClient 取消自身 Job | **致命** | closeClient（不取消 Job）/ cancelAllJobs 拆分 |
| 计数器回溯：cleanupServer 清除计数 → 永远不进 Dormant | **致命** | 仅在 removeClient 时重置 |
| syncAll 死锁：持 Mutex 调 addClient | **致命** | 外部持锁后调用 |
| CancellationException 被吞：runCatching 未 rethrow | 高 | 15 处全部 audit |
| TimeoutCancellationException 误中断对话 | 高 | 优先于 CancellationException 检测 |
| "已拒绝"误报：非 Pending 工具被标记 Denied | 高 | finishPendingTools→Pending only + finishInterruptedTools 新增 |
| callTool 绕生命周期：transport==null 时直连 | 高 | 改为触发正常重连 |
| syncAll 假 Error：stale client 不重连 | 中 | transport==null → addClient |
| Logging 不分类型：请求日志挤掉生命周期日志 | 中 | 独立配额 200/100 |
| 硬编码英文状态字符串 | 低 | stringResource + 5 locale |

---

## 0.0.6（versionCode 6）— 2026-06-27

### 新增

- **屏幕使用时间工具**（`get_screen_time`）：查询设备应用前台使用时长，支持 `today/week` 预设和自定义时间区间，含权限引导。需授予「使用情况访问」权限
- **对话工具**（`recent_chats` / `conversation_search`）：将最近聊天引用从静态注入 system prompt 改为按需工具，避免动态内容破坏 prompt cache，提升缓存命中率
- **搜索结果图片展示**：Tavily 搜索结果新增图片字段，展开 Sheet 中显示横向滚动缩略图行
- **Workspace 上传目录挂载**：`/upload` 目录挂载到 workspace，AI 可直接读取原始上传文件

### 变更

- **LocalTools 拆分**：将单体 `LocalTools.kt` 拆分为 `tools/local/` 目录下 8 个独立文件，提升可维护性
- **LaTeX 字体跟随聊天设置**：公式字号从硬编码改为跟随用户设置的聊天字体大小
- **上下文截断警告**：限制上下文消息数时显示警告，提示可能影响 prompt cache
- **快捷消息菜单宽度约束**：DropdownMenu 最大宽度限制为 360dp，避免过宽

### 修复

- `DocumentAsPromptTransformer` 改用 `<UploadFile>` 标签，附带 workspace 内路径

---

## 0.0.5（versionCode 5）— 2026-06-27

### 变更

- **品牌名称更正**：`Mersix Pilot` → `Measix Pilot`
  - 包名变更：`net.weero.mersix.pilot` → `net.weero.measix.pilot`（需卸载重装）
  - 数据库名：`mersix_pilot` → `measix_pilot`
  - 域名：`mersix.weero.net` → `measix.weero.net`
  - DeepLink scheme：`mersix://` → `measix://`
  - S3/WebDAV 备份路径：`mersix_pilot_backups/` → `measix_pilot_backups/`
  - 全项目 358 个文件中的 "mersix" 拼写错误已更正为 "measix"

### 数据迁移说明

由于包名变更，用户需要手动迁移数据：

1. 在旧 app（`net.weero.mersix.pilot`）中导出本地备份（设置 → 备份 → 本地导入导出 → 导出备份）
2. 卸载旧 app
3. 安装新 app（`net.weero.measix.pilot`）
4. 在新 app 中导入备份（设置 → 备份 → 本地导入导出 → 导入备份）

> 注意：`settings.json` 中的助手、MCP、提供商配置可完整恢复。旧备份中的数据库文件（`mersix_pilot.db`）会被跳过。

---

## 0.0.4（versionCode 4）— 2026-06-21

### 新增

- LLM 交互 loop 前台声音反馈：loop 成功/失败（排除用户取消）/单步完成/工具待审批四个状态点播放提示音，设置页新增「声音反馈」开关（默认开启）。声音文件源自 freedesktop sound-theme（GPL-2.0+）

### 修复

- `eval_javascript` 工具的 QuickJS Context 内存泄漏：`QuickJSContext.create()` 后未调用 `destroy()`，每次执行都泄漏原生 JS runtime。改为 `try/finally` 保证释放（对齐 `CustomJsSearchService` 既有写法）
- `McpManager` 连接状态更新非原子：`syncingStatus` 的 read-modify-write 在多服务器并发时会互相覆盖。改为 `MutableStateFlow.update {}`（CAS 原子更新），消除 UI 状态错乱

---

## 0.0.3（versionCode 3）— 2026-06-20

### 新增

- MiMo TTS provider（小米 MiMo 语音合成），从上游 `5b9be301` 移植，按官方 v2.5 文档实现协议
  - 支持 `mimo-v2.5-tts`（标准）和 `mimo-v2.5-tts-voicedesign`（音色设计）两个模型，UI 按模型动态切换字段
  - 标准模型：Voice 下拉选预置音色（mimo_default/冰糖/茉莉等），风格指令可选；voicedesign：隐藏 Voice，音色描述必填
  - 请求体协议 curl 实测验证通过；单测 9 case 覆盖 SSE 解码/协议边界/请求体条件构造
  - 默认不预置，用户在「添加 TTS Provider」下拉主动选 MiMo；图标复用既有 `xiaomimimo.svg`
- MCP 服务器分享功能（二维码 + 文本分享）
- Provider 粘贴导入功能

### 变更

- 优化默认配置：启用振动反馈、朗读/询问工具、记忆功能、通知、代码块自动换行与折叠

### 清理

- 移除遗留兼容死代码约 700 行
- 新增 `decodeListLenient<T>()` 逐元素反序列化
- 清理未使用字符串资源（5 语言文件共 55 条）
- 替换应用图标，移除 RikkaHub 品牌资源

### 上游同步

- 同步 RikkaHub 2.3.2 更新（OCR 修复、平板 UI 修复、依赖更新）
- 修复废弃 API（`currentWindowDpSize` → `LocalWindowInfo`）
- 新增 `upstream-sync.md` 检查点记录文档

### 已知技术债

- TTS 流式聚合：`TtsSynthesizer.collectToResponse()` 将 Flow 全量缓冲再播放，MiMo 等流式 provider 首音延迟优势被抹平。流式播放重构列为独立后续 issue。
