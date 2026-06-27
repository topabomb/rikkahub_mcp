# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

## Build Configuration Notes

- `gradle.properties` 中 `android.r8.strictFullModeForKeepRules=false`：AGP 9 默认启用 R8 strict full mode，部分依赖库（如 ML Kit barcode-scanning 17.3.0）的 consumer ProGuard rules 不兼容，导致 release 构建运行时崩溃。此项回退到 AGP 8 的 keep rules 处理行为。当所有依赖库更新兼容后可移除。
- `app/proguard-rules.pro` 中 `-dontobfuscate`：启用 R8 裁剪但不混淆类名，便于 crash 堆栈定位。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Bing, Tavily, SearXNG, Custom JS)
- **speech**: Speech module for TTS and ASR implementations
- **workspace**: Local workspace module providing file tools and sandbox environments

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/net/weero/measix/pilot/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/net/weero/measix/pilot/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/net/weero/measix/pilot/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/net/weero/measix/pilot/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- **Localization is mandatory for all user-facing strings.** Never hardcode user-visible text in Kotlin code;
  always define a string resource in `values/strings.xml` (English, the source language) first.
- Supported locales: English(`values`), Chinese(`values-zh`), Japanese(`values-ja`),
  Korean(`values-ko-rKR`), Russian(`values-ru`). 5 files total.
- When adding a new feature: define strings in all 5 `strings.xml` files. If a translation is not immediately
  available, duplicate the English text as a placeholder and translate later.
- For non-Composable code (e.g. ViewModel, utility functions), use `context.getString(R.string.key, args...)`.
- For `locale-tui` operations, use the `locale-tui-localization` skill.

## Development Documentation

`docs/dev/` 维护以下文档，贡献者应了解其定位和维护规则：

| 文档 | 定位 | 维护规则 |
|------|------|----------|
| `original-architecture.md` | Fork 前（RikkaHub）的完整架构说明，供追溯源头 | 冻结归档，不再更新（反映精简前状态） |
| `fork-simplification-plan.md` | Fork 精简规划与落地记录（0.0.1 → 0.0.2） | 冻结归档，不再更新（精简已完成） |
| `changelog.md` | 精简落地后的功能迭代清单（0.0.3 起） | **每次功能迭代提交时更新**，新增版本条目记录变更 |

**功能迭代时**：在 `changelog.md` 顶部新增版本条目（版本号、日期、新增/修复/变更摘要），并同步递增 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`。

