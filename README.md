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

**核心概念**：Assistant（助手配置）、Conversation（对话线程）、UIMessage（消息抽象）、Provider（服务商适配）、MCP（工具协议）、Transformer（消息变换管道）。详见 [界面架构参考](docs/references/ui-architecture.md)。

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

| 文档 | 说明 |
|------|------|
| [界面架构参考](docs/references/ui-architecture.md) | UI 架构层次、导航体系、自适应布局策略、折叠屏适配方案 |
| [消息渲染管线](docs/references/message-rendering-pipeline.md) | UIMessage.parts 到像素的完整渲染管线、Markdown 双路径、WebView 封装 |
| [助手配置参考](docs/references/assistant-configuration.md) | Assistant 字段、默认模板、解析/持久化规则与配置消费边界 |
| [Android 配置架构与企业下发清单](docs/references/android-configuration-architecture.md) | Android 完整配置目录、持久化/引用架构、企业下发边界与 S0.2 Snapshot 映射 |
| [消息生成链路](docs/references/chat-generation-pipeline.md) | 从用户发送到模型回复落盘的完整数据流 |
| [AI 协议参考](docs/references/protocol-reference.md) | 四类基础协议规范、Provider 差异映射、模型级适配 |
| [工作区架构](docs/references/workspace-architecture.md) | Rootfs/挂载边界、PRoot 执行、文件工具、终端与安装生命周期 |
| [更新与发行](docs/references/update-mechanism.md) | 更新检查、宽松版本排序、下载委托、签名与 CI 发行契约 |
| [子助手架构](docs/references/sub-assistant-architecture.md) | Target 访问、同步调用、Child lineage、撤权、恢复与只读详情 |
| [提示词与工具](docs/references/prompts-and-tools.md) | 模型可见的系统注入、实际工具名、参数和 Tool Result 形状 |

### 开发文档（`docs/dev/`）

| 文档 | 说明 |
|------|------|
| [功能迭代清单](docs/dev/changelog.md) | 0.0.3 起的功能迭代历史（每次迭代更新） |
| [Fork 精简计划](docs/dev/fork-simplification-plan.md) | Fork 精简规划与落地记录（已归档） |
| [原始架构文档](docs/dev/original-architecture.md) | Fork 前 RikkaHub 架构详解（已归档） |
| [上游同步记录](docs/dev/upstream-sync.md) | RikkaHub 上游提交检查与同步历史 |

## Fork 说明

相比原项目 RikkaHub，本 fork 移除了 Firebase、Retrofit、Web 服务器模块、酒馆角色卡导入、Lorebook、翻译功能，精简了预设 Provider（18→4）和搜索引擎（17→4）。许可证同步上游变更为纯 AGPL-3.0。详见 [Fork 精简计划](docs/dev/fork-simplification-plan.md)。

## 许可

[AGPL-3.0](LICENSE)
