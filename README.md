# Measix Pilot

> 小睿助手（Measix Pilot）— 基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（原作者 [re-ovo](https://github.com/re-ovo)）fork 的原生 Android LLM 聊天客户端。
>
> Fork 源头：RikkaHub v2.3.1（versionCode 164），提交 `5b9be301`。

## 功能特性

- **多 Provider 对话**：OpenAI / Gemini / Claude / DeepSeek 兼容 API，支持 Chat Completions 与 Responses 两套协议
- **MCP 协议**：连接外部工具服务器，支持 OAuth 2.1 授权与 DCR 动态注册
- **工具调用 + HITL 审批**：安全的工具执行机制，支持人工审批
- **工作空间沙箱**：基于 PRoot 的 Linux 环境，AI 可执行命令、读写文件
- **消息分支**：重新生成、切换对话分支
- **Markdown 渲染**：Kotlin 原生语法高亮（30+ 语言）、LaTeX、Mermaid 图表
- **多模态输入**：图片、PDF、DOCX 文档
- **全文搜索**：FTS5 + jieba 中文分词
- **备份同步**：WebDAV / S3
- **AI 生图**：文生图演示功能
- **Skills 系统**：可扩展的技能框架
- **语音合成**：System TTS / OpenAI / Gemini / MiMo
- **自适应 UI**：折叠屏双栏、矮横屏紧凑输入、宽屏弹层居中面板

## 架构概览

```
app/          主应用（UI + ViewModel + 数据层）
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

- Android Studio（最新稳定版）
- JDK 17+
- Android SDK 37

### 常用命令

```bash
./gradlew assembleDebug              # 构建 Debug APK
./gradlew assembleRelease            # 构建 Release APK
./gradlew test                       # 运行所有 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器测试
./gradlew lint                       # 运行 Android Lint
```

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
参考文档只维护当前契约；具体字段和默认值以代码为准，历史设计与交付记录位于 `docs/dev/`。


### 开发文档（`docs/dev/`）

| 文档 | 说明 |
|------|------|
| [版本变更记录](docs/dev/changelog.md) | 各发布版本的功能与修复摘要 |
| [Fork 精简计划](docs/dev/fork-simplification-plan.md) | 已完成的 Fork 改造历史，不作为当前实现依据 |
| [原始架构文档](docs/dev/original-architecture.md) | Fork 前的架构历史，不作为当前实现依据 |
| [上游同步总账](docs/dev/upstream-sync.md) | 冻结范围、判定与各批详细审查记录 |
| [自主通知设计](docs/dev/android-autonomous-notifications-v1.md) | 尚未实施；按文中研究基线阅读，实施前重新核对代码 |
| [Gemini 后续设计](docs/dev/google-gemini-protocol-correction-plan.md) | 尚未实现的协议扩展范围与验收要求 |

## Fork 说明

相比原项目 RikkaHub，本 fork 移除了 Firebase、Retrofit、Web 服务器模块、酒馆角色卡导入、Lorebook、翻译功能，精简了预设 Provider（18→4）和搜索引擎（17→4）。许可证同步上游变更为纯 AGPL-3.0。详见 [Fork 精简计划](docs/dev/fork-simplification-plan.md)。

## 许可

[AGPL-3.0](LICENSE)
