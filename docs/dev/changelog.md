# 功能迭代清单

> 本文档记录 Fork 精简落地（0.0.2）后的功能迭代历史。
> 精简过程见 `fork-simplification-plan.md`，精简前架构见 `original-architecture.md`。
> 每次功能迭代提交时必须更新本文档，该文档保持精简描述的风格。

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
