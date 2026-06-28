# 功能迭代清单

> 本文档记录 Fork 精简落地（0.0.2）后的功能迭代历史。
> 精简过程见 `fork-simplification-plan.md`，精简前架构见 `original-architecture.md`。
> 每次功能迭代提交时必须更新本文档，该文档保持精简描述的风格。

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
