# Measix Pilot

> 小麦助手 — 基于 [RikkaHub](https://github.com/re-ovo/rikkahub)（原作者 [re-ovo](https://github.com/re-ovo)）精简 fork 的原生 Android LLM 聊天客户端。

Fork 源头：RikkaHub v2.3.1（versionCode 164），提交 `5b9be301`。感谢原项目提供的优秀架构和功能基础。

**版本起点**：本 fork 从 `0.0.1`（versionCode 1）开始，采用 SemVer 语义化版本管理。详见 [版本规约](docs/dev/fork-simplification-plan.md#八版本规约)。

## 架构概览

```
app/          主应用（UI + ViewModel + 数据层）
ai/           AI SDK 抽象层（Provider 适配 + 消息模型 + 工具定义）
search/       搜索引擎 SDK（Bing / Tavily / SearXNG / Custom JS）
speech/       语音 SDK（TTS + ASR）
workspace/    工作空间（proot Linux 沙箱）
document/     文档解析（PDF / DOCX / PPTX / EPUB）
highlight/    代码语法高亮
material3/    Material3 颜色工具扩展
common/       通用工具
```

**核心概念**：

| 概念 | 说明 |
|------|------|
| Assistant | 助手配置：系统提示词、模型参数、对话隔离 |
| Conversation | 对话线程，MessageNode 树形结构支持消息分支 |
| UIMessage | 平台无关的消息抽象，支持流式更新和多种内容类型 |
| Provider | AI 服务商适配层（OpenAI / Google / Claude / DeepSeek 等兼容 API） |
| MCP | Model Context Protocol，工具调用与外部服务集成 |
| Transformer | 消息变换管道（模板、正则、OCR、Think 标签提取等） |

## 功能特性

- **多 Provider 对话**：OpenAI / Gemini / Claude / DeepSeek（4 个预设，全部默认禁用）
- **MCP 协议**：连接外部工具服务器
- **工具调用 + HITL 审批**：安全的工具执行机制
- **工作空间沙箱**：proot Linux 环境执行命令
- **消息分支**：重新生成、切换对话分支
- **Markdown 渲染**：代码高亮、LaTeX、Mermaid 图表
- **多模态输入**：图片、PDF、DOCX 文档
- **全文搜索**：FTS5 + jieba 中文分词
- **备份同步**：WebDAV / S3
- **AI 生图**：文生图演示功能
- **Skills 系统**：可扩展的技能框架
- **语音合成**：System TTS / OpenAI / Gemini

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 + Navigation3 |
| DI | Koin |
| 网络 | OkHttp + Ktor Client |
| 序列化 | kotlinx.serialization |
| 数据库 | Room（版本 2，含 Migration 样本） |
| 异步 | Coroutines + Flow |
| 图片 | Coil |

## 构建与开发

### 环境要求

- Android Studio（最新稳定版）
- JDK 17+
- Android SDK 37

### 常用命令

```bash
./gradlew assembleDebug              # 构建 Debug APK
./gradlew test                       # 运行所有 JVM 单元测试
./gradlew :app:testDebugUnitTest     # 运行 app 模块单元测试
./gradlew :ai:testDebugUnitTest      # 运行 AI 模块单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器测试
./gradlew lint                       # 运行 Android Lint
```

### 配置

| 项目 | 值 |
|------|-----|
| 版本 | `0.0.1`（versionCode 1） |
| 包名 | `net.weero.measix.pilot` |
| 最低 SDK | 26（Android 8.0） |
| 目标 SDK | 37 |
| 数据库 | Room `measix_pilot`（version 2） |
| SharedPreferences | `MeasixPilot.preferences` |

首次启动时 4 个预设 Provider 均为禁用状态，需手动启用并配置 API Key。

## 精简说明

本 fork 相比原项目移除了以下功能：

- Firebase（RemoteConfig / Crashlytics / Analytics）
- Retrofit（改用 OkHttp + Ktor Client）
- Web 服务器模块（Ktor Server + React 前端 + mDNS）
- 酒馆角色卡 / Chatbox / CherryStudio 导入
- Lorebook / 世界书（保留 ModeInjection）
- 翻译功能
- 大量预设 Provider（18→4）和搜索引擎（17→4）

详细变更记录见 [Fork 精简计划](docs/dev/fork-simplification-plan.md)。

## 文档

- [架构文档](docs/dev/architecture.md) — 原项目 RikkaHub 架构详解
- [Fork 精简计划](docs/dev/fork-simplification-plan.md) — 变更记录与执行状态

## 许可

[LICENSE](LICENSE)
