# Repository Guidelines

本文档面向 AI agent 与贡献者，提供仓库结构、开发流程和编码规范的快速索引。深度技术细节见 `docs/references/` 与 `docs/dev/` 专题文档。

## Build & Test

- 命令行 Gradle：Windows 用 `gradlew.bat`，macOS/Linux 用 `./gradlew`。
- `gradlew assembleDebug` 构建 Debug APK；`gradlew test` 跑全部模块 JVM 单测；`gradlew connectedDebugAndroidTest` 跑仪器测试。
- 发行版用 `gradlew assembleRelease`。
- 新逻辑应配套新增/更新测试（JUnit / AndroidX Test）。

## Build 配置要点

- `gradle.properties` 中 `android.r8.strictFullModeForKeepRules=false`：AGP 9 默认 strict full mode 与部分依赖库（如 ML Kit barcode-scanning 17.3.0）的 consumer rules 不兼容，会导致 release 崩溃。依赖库兼容后可移除。
- Release 用 AGP 9 `optimization { enable = true }`，运行时规则集中在 `app/src/main/keepRules/rikkahub.keep`，mapping 用于还原混淆堆栈。
- ABI Splits：`arm64-v8a` + `x86_64`，构建 AppBundle 时自动禁用。Debug 包名后缀 `.debug`，与 Release 共存。

## Coding Style

格式由 `.editorconfig` 统一。关键点：

- Kotlin/Gradle 4 空格缩进，行长 120；XML/JSON/Markdown/YAML 2 空格。
- 文本文件统一 UTF-8 without BOM + CRLF；禁止新增 BOM，改已有 BOM 文件时移除。
- 命名：模块目录小写（`ai/`、`speech/`），Kotlin 类 PascalCase，测试类以 `*Test` 结尾。

## Module Structure

| 模块 | 职责 |
|------|------|
| **app** | UI（Jetpack Compose）、ViewModel、Service、数据层 |
| **ai** | AI SDK 抽象层：Provider 管理、协议实现、UIMessage 模型、流式合并 |
| **common** | 通用工具和 Kotlin 扩展 |
| **document** | 文档解析：PDF / DOCX / PPTX / EPUB |
| **highlight** | Kotlin 原生代码语法高亮引擎（30+ 语言） |
| **material3** | Material You 动态取色扩展（`DynamicSchemeExt`） |
| **search** | 搜索 SDK：Bing / Tavily / SearXNG |
| **speech** | TTS（System / OpenAI / Gemini / MiMo）与 ASR（DashScope / OpenAI Realtime） |
| **workspace** | PRoot 沙箱：shell 执行、文件读写、代码编辑工具 |

## Core Concepts

核心模型与链路的深度文档见 `docs/references/`，以下为索引（定义文件见各文档）：

- **Assistant**：助手配置（系统提示词、模型参数、工具、记忆、正则、模式注入）。[assistant-configuration.md](docs/references/assistant-configuration.md)
- **Conversation / MessageNode**：持久化对话线程，`MessageNode` 树支持消息分支，`selectIndex` 跟踪选中分支。`Conversation.kt`
- **UIMessage**：平台无关消息抽象（text / image / document / reasoning / tool call），支持流式 chunk 合并。`ai/.../ui/Message.kt`
- **Message Transformer**：输入变换（`InputMessageTransformer`）与输出变换（`OutputMessageTransformer`）管道。
- **Generation Pipeline**：用户发送 → AI 回复的完整链路（工具装配、上下文裁剪、流式、HITL 审批、Transformer）。[chat-generation-pipeline.md](docs/references/chat-generation-pipeline.md)
- **Protocol Layer**：Chat Completions / Responses / Anthropic Messages / Gemini 四种线协议。[protocol-reference.md](docs/references/protocol-reference.md)
- **Workspace**：PRoot 沙箱架构与工具调用生命周期。[workspace-architecture.md](docs/references/workspace-architecture.md)
- **UI Architecture**：自适应布局、导航、主题体系。[ui-architecture.md](docs/references/ui-architecture.md)
- **Message Rendering**：消息渲染管线（Markdown 双路径、代码块三态、Mermaid/HTML WebView、LaTeX 原生）。[message-rendering-pipeline.md](docs/references/message-rendering-pipeline.md)
- **Update Mechanism**：版本检查、优先级比较、下载与 CI 发布。[update-mechanism.md](docs/references/update-mechanism.md)
- **Runtime Core（v1 重构）**：会话状态机与提交协议——`ConversationRuntime`（单写 `submit` 命令通道 + 流式 `applyStreamingDelta` 投影）、`ConversationReducer` 纯函数（structural sharing）、`TurnEngine`（chunk→投影 / checkpoint→CommitCheckpoint / 终态→FinalizeTurn 的唯一提交协议实现）、`TurnPipelineFactory`（Master/Target 共用管道装配）、`DelegationCoordinator`（子助手域编排）。`service/runtime/`。深度设计见 `docs/dev/architecture-v1-refactor-plan.md`（§4）。
- **Sub-Assistant**：子助手访问、Child lineage、持久化、撤权与恢复；执行协调由 `DelegationCoordinator` 负责。[sub-assistant-architecture.md](docs/references/sub-assistant-architecture.md)；附件入站/交付出站见 [sub-assistant-multimodal.md](docs/references/sub-assistant-multimodal.md)
- **Multimodal Context & Turn Durability**：附件事实、请求级投影、`inspect_attachments`、Turn/Tool 执行事实与崩溃恢复。[multimodal-context-and-turn-durability.md](docs/references/multimodal-context-and-turn-durability.md)
- **Prompts and Tools**：模型可见的系统注入、工具 description、Tool Result 形状。[prompts-and-tools.md](docs/references/prompts-and-tools.md)

Fork 前架构见 [`docs/dev/original-architecture.md`](docs/dev/original-architecture.md)（冻结归档）。

## Internationalization

- 字符串资源在 `app/src/main/res/values*/strings.xml`（`search` 等模块可能另有自己的）。
- **所有用户可见字符串必须本地化**：禁止在 Kotlin 硬编码。先在 `values/strings.xml`（英文源语言）定义，再同步其他语言；翻译暂缺时复制英文占位。
- **例外——底层错误诊断文案只需英文**：工具失败 reason、provider 错误 detail 等技术诊断类字符串只在 `values/strings.xml` 定义即可，不必同步其他 locale；面向正常交互的 UI 文案（标题、按钮、设置项等）仍需全量本地化。
- 支持 locale：en(`values`)、zh(`values-zh`)、ja(`values-ja`)、ko(`values-ko-rKR`)、ru(`values-ru`)。
- Compose 用 `stringResource(R.string.key)`；非 Composable 用 `context.getString(R.string.key, args...)`。页面级字符串用页面前缀（如 `setting_page_`）。

## 文档维护

**需主动维护**：

- `changelog.md`：功能迭代清单。仅当用户明确要求时才在顶部新增版本条目并递增 `app/build.gradle.kts` 的 `versionCode`/`versionName`；默认维持当前版本。
- `upstream-sync.md`：上游同步检查总账。每次同步 fetch、按上批冻结点续查并追加摘要。

**`docs/references/` 参考文档规范**（核心：高价值、低维护成本，硬性要求）：

- **禁止引用行号 / 行数 / 计数**（如 `L43`、`N 项`）；用类名、函数名或常量名定位，读者可全局搜索。
- SDK 版本（如 `openai-python v2.7.1`）作为核验来源可保留。
- 代码片段忠实反映实际结构（密封类、嵌套类、`override`），不简化。
- 工具名用实际注册名 `Tool(name = "xxx")`，而非 `@SerialName` 或简称。

`docs/dev/` 为开发过程文档：`original-architecture.md` 是冻结归档；其余历史调研/设计文档按需查阅，不再逐项列举。
