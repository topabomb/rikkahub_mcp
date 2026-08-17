# Repository Guidelines

本文档面向 AI agent 和贡献者，提供仓库结构、开发流程和编码规范的快速索引。
深度技术细节请查阅 `docs/references/` 和 `docs/dev/` 中的专题文档。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle（Windows 用 `gradlew.bat`，macOS/Linux 用 `./gradlew`）：

```bash
gradlew assembleDebug              # 构建 Debug APK
gradlew test                       # 运行所有模块的 JVM 单元测试
gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
gradlew lint                       # 运行 Android Lint
```

> 发行版默认用 gradlew assembleRelease。

## Build Configuration Notes

- `gradle.properties` 中 `android.r8.strictFullModeForKeepRules=false`：AGP 9 默认启用 R8 strict full mode，部分依赖库（如 ML Kit barcode-scanning 17.3.0）的 consumer ProGuard rules 不兼容，导致 release 构建运行时崩溃。此项回退到 AGP 8 的 keep rules 处理行为。当所有依赖库更新兼容后可移除。
- Release 使用 AGP 9 `optimization { enable = true }`，必要运行时规则集中在 `app/src/main/keepRules/rikkahub.keep`；构建生成的 mapping 用于还原混淆堆栈。
- ABI Splits：`arm64-v8a` + `x86_64`，构建 AppBundle 时自动禁用 splits。
- Debug 包名后缀 `.debug`，与 Release 共存安装。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。
- 文本文件统一使用 UTF-8 without BOM 和 CRLF 换行符。禁止新增 UTF-8 BOM；修改已有带 BOM 的源码、配置或文档文件时，应移除 BOM。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

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

核心数据模型和链路的深度文档见 `docs/references/`，以下为快速索引：

- **Assistant**：助手配置（系统提示词、模型参数、工具、记忆、正则、模式注入等），定义于 `Assistant.kt`。详见 [`assistant-configuration.md`](docs/references/assistant-configuration.md)。
- **Conversation**：持久化对话线程，维护 `MessageNode` 树结构以支持消息分支。定义于 `Conversation.kt`。
- **UIMessage**：平台无关的消息抽象，封装 text / image / document / reasoning / tool call 等内容部件，支持流式 chunk 合并。定义于 `ai/.../ui/Message.kt`。
- **MessageNode**：消息分支容器，持有多条可选 `UIMessage`，通过 `selectIndex` 跟踪当前选中分支。定义于 `Conversation.kt`。
- **Message Transformer**：消息变换管道，分输入变换（`InputMessageTransformer`）和输出变换（`OutputMessageTransformer`）。
- **Generation Pipeline**：从用户发送到 AI 回复的完整生成链路，含工具装配、上下文裁剪、流式收集、HITL 审批、Transformer 管道。详见 [`chat-generation-pipeline.md`](docs/references/chat-generation-pipeline.md)。
- **Protocol Layer**：4 种线协议（Chat Completions / Responses / Anthropic Messages / Gemini）的 Provider 实现。详见 [`protocol-reference.md`](docs/references/protocol-reference.md)。
- **Workspace**：PRoot 沙箱架构与工具调用生命周期。详见 [`workspace-architecture.md`](docs/references/workspace-architecture.md)。
- **UI Architecture**：自适应布局策略、导航、主题体系。详见 [`ui-architecture.md`](docs/references/ui-architecture.md)。
- **Message Rendering**：消息渲染管线（UIMessage.parts → 像素），含 Markdown 双路径、代码块三态、Mermaid/HTML WebView、LaTeX 原生渲染。详见 [`message-rendering-pipeline.md`](docs/references/message-rendering-pipeline.md)。
- **Update Mechanism**：版本检查、宽松版本优先级比较、下载委托与 CI 发布流程。详见 [`update-mechanism.md`](docs/references/update-mechanism.md)。
- **Sub-Assistant**：子助手（Target）的访问、同步调用、Child lineage、持久化、撤权与恢复体系。详见 [`sub-assistant-architecture.md`](docs/references/sub-assistant-architecture.md)。附件入站与交付物出站（含背景、设计与扩展方向）见 [`sub-assistant-multimodal.md`](docs/references/sub-assistant-multimodal.md)。
- **Prompts and Tools**：模型可见的系统注入、工具 description、参数说明与 Tool Result 形状。详见 [`prompts-and-tools.md`](docs/references/prompts-and-tools.md)。

Fork 前的完整架构说明见 [`docs/dev/original-architecture.md`](docs/dev/original-architecture.md)（冻结归档）。

## Internationalization

- String resources 位于 `app/src/main/res/values*/strings.xml`；`search` 等功能模块可能有自己的 `values*/strings.xml`。
- 使用 `stringResource(R.string.key_name)` 在 Compose 中引用。
- 页面级字符串使用页面前缀（如 `setting_page_`）。
- **所有用户可见字符串必须本地化**，禁止在 Kotlin 代码中硬编码用户可见文本。先在 `values/strings.xml`（英文，源语言）中定义，再同步到其他语言文件。
- 支持的 locale：English(`values`)、Chinese(`values-zh`)、Japanese(`values-ja`)、Korean(`values-ko-rKR`)、Russian(`values-ru`)。
- 新增功能时：在所有 5 个 `strings.xml` 中定义字符串。翻译暂不可用时，复制英文文本作为占位符。
- 非 Composable 代码中使用 `context.getString(R.string.key, args...)`。
- 使用 `locale-tui-localization` skill 管理 string resources。

## Development Documentation

`docs/dev/` 维护开发过程文档。`original-architecture.md` 为 Fork 前冻结归档；其余历史调研和设计文档（`fork-simplification-plan.md`、`mcp-lifecycle-analysis.md` 等）按需查阅，不再逐项列举。

**需主动维护的文档**：

- `changelog.md`：功能迭代清单。只有在新增重要功能或重大需求时，在顶部新增版本条目并递增 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
- `upstream-sync.md`：按批执行的上游同步的检查总账，避免漏检或重复劳动。每次同步 fetch、按上批冻结点续查，并在该文档追加摘要；方法与判定原则以文档正文为准。

## Reference Documentation

`docs/references/` 维护深度技术参考文档，供迭代决策和新人理解架构使用。文档变更时应同步更新对应内容。

### 文档维护规范

核心目标：文档高价值、低维护成本。以下为硬性要求：

- **禁止引用行号和计数**：不写行号（`L43`）、行数（`X 行代码`）、计数（`N 项配置`）等易变内容。用类名、函数名或常量名定位，读者可全局搜索。
- **SDK 版本可保留**：作为核验来源的 SDK 版本（如 `openai-python v2.7.1`）保留。
- **代码片段忠实反映实际结构**：展示密封类、嵌套类、`override` 等结构，不简化为独立定义。
- **工具名用实际注册名**：使用 `Tool(name = "xxx")` 中的注册名，而非 `@SerialName` 或简称。
