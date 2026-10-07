# Measix Pilot

> 小睿助手（Measix Pilot）— 基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（原作者 [re-ovo](https://github.com/re-ovo)）fork 的原生 Android LLM 聊天客户端。
>
> Fork 源头：RikkaHub v2.3.1（versionCode 164），提交 `5b9be301`。

## 功能特性

- **多 Provider 对话**：OpenAI / Gemini / Claude / DeepSeek 兼容 API，支持 Chat Completions 与 Responses 两套协议
- **个人与企业空间**：接入 Core，按主体隔离历史、记忆和文件，使用企业发布的助手、模型与工具
- **任务入口与上下文**：Starter 开场、请求来源保全与可查看的上下文变化
- **MCP 协议**：外部服务器、OAuth 授权、可恢复目录与企业工具治理
- **工具调用 + HITL 审批**：安全的工具执行机制，支持人工审批
- **工作空间**：本地 PRoot 执行与终端；企业远程文件管理、上传下载、源码编辑和媒体预览
- **消息分支**：重新生成、切换对话分支
- **Markdown 渲染**：Kotlin 原生语法高亮（30+ 语言）、LaTeX、Mermaid 图表
- **多模态输入**：图片、PDF、DOCX 文档
- **全文搜索**：FTS5 + jieba 中文分词
- **备份同步**：WebDAV / S3
- **AI 生图**：用户与企业配置的图片生成及附件识别
- **Skills 系统**：可扩展的技能框架
- **语音合成**：System TTS / OpenAI / Gemini / MiMo
- **自适应 UI**：折叠屏双栏、矮横屏紧凑输入、宽屏弹层居中面板

## 架构概览

```
app/          主应用（UI、应用编排、领域 owner、Room/DataStore 与 Android 集成）
ai/           AI SDK 抽象层（Provider 适配 + 消息模型 + 工具定义）
search/       搜索引擎 SDK（Bing / Tavily / SearXNG）
speech/       语音 SDK（TTS + ASR）
workspace/    工作空间（PRoot Linux 沙箱）
document/     文档解析（PDF / DOCX / PPTX / EPUB）
highlight/    代码语法高亮（纯 Kotlin 实现）
material3/    Material3 颜色工具扩展
common/       通用工具
```

**核心概念**：Assistant（助手配置）、Conversation（对话线程）、UIMessage（消息抽象）、Provider（服务商适配）、MCP（工具协议）、Transformer（消息变换管道）。详见 [应用总体架构](docs/references/application-architecture.md)。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material Expressive (M3) + Navigation 3 |
| DI | Koin |
| 网络 | OkHttp + Ktor Client |
| 序列化 | kotlinx.serialization |
| 数据库 | Room |
| 异步 | Coroutines + Flow |
| 图片 | Coil |

## 构建与开发

### 环境要求

- 支持仓库锁定 AGP 的 Android Studio，或命令行构建环境
- JDK 17+
- Android SDK 37

### 常用命令

```bash
./gradlew assembleDebug --no-parallel --max-workers=1
./gradlew assembleRelease --no-parallel --max-workers=1
./gradlew test --no-parallel --max-workers=1
./gradlew connectedDebugAndroidTest --no-parallel --max-workers=1
./gradlew lintDebug --no-parallel --max-workers=1
```

Windows PowerShell 使用 `.\gradlew.bat`；设备门禁需专用测试设备，准备与证据边界见 [测试策略](docs/references/testing-strategy.md)。

### 配置

| 项目 | 值 |
|------|-----|
| 包名 | `net.weero.measix.pilot` |
| 最低 SDK | 26（Android 8.0） |
| 目标 SDK | 37 |

首次启动时预设 Provider 均为禁用状态，需手动启用并配置 API Key。

## 文档

### 参考文档（`docs/references/`）

从 [应用架构与分组导航](docs/references/application-architecture.md) 开始，了解模块职责、持久化事实、
执行协议和术语，再进入配置、会话请求、数据资源、工具运行时、UI、测试与发行专题。
参考文档只维护当前契约；具体字段和默认值以代码为准。`docs/dev/` 保留版本摘要、可追溯的上游同步记录与尚未实施的设计；
已完成的一次性计划不再与当前契约并行维护。

### 开发文档（`docs/dev/`）

| 文档 | 说明 |
|------|------|
| [版本变更记录](docs/dev/changelog.md) | 各发布版本的功能与修复摘要 |
| [上游同步总账](docs/dev/upstream-sync.md) | 冻结范围、判定与各批详细审查记录 |
| [自主通知设计](docs/dev/android-autonomous-notifications-v1.md) | 未实施；保留目标、可靠性契约和需要重新验证的运行前提 |
| [Gemini 后续设计](docs/dev/google-gemini-protocol-correction-plan.md) | 尚未实现的协议扩展范围与验收要求 |

## Fork 说明

相比原项目 RikkaHub，本 fork 移除了 Firebase、Retrofit、Web 服务器模块、酒馆角色卡导入、Lorebook、翻译功能，精简了预设 Provider（18→4）和搜索引擎（17→4）。许可证同步上游变更为纯 AGPL-3.0。已完成的精简、旧架构与执行计划保留在 Git 历史；当前能力以参考文档和代码为准。

## 许可

[AGPL-3.0](LICENSE)
