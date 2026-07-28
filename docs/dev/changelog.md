# 功能迭代清单

> 本文档记录 Fork 精简落地（0.0.2）后的功能迭代历史。
> 精简过程见 `fork-simplification-plan.md`，精简前架构见 `original-architecture.md`。
> 每次功能迭代提交时必须更新本文档，该文档保持精简描述的风格。

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
