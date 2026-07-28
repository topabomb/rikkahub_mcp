# 上游同步阶段 1 复核（第 1～9 批）

> 复核日期：2026-07-28  
> 冻结上游检查点：`2d61ba95`（九批范围连续闭合；`669cc460` 为独立的 AGP + Kotlin 依赖升级，在冻结后应用，不影响阶段闭合计数）  
> 本地目标版本：0.0.11（versionCode 11）

## 文档定位、目标与价值

本文档是第一个**阶段性冻结检查点**，不是第九批逐提交记录的重复摘要。它把第 1～9 批在
`2d61ba95` 处的累计结果固化为一个可审计基线，回答四个问题：

1. 九批检查范围是否连续、互斥且数量闭合；
2. 每项引入或跳过是否符合 Measix Pilot 的产品边界；
3. 多批改动叠加后的代码最终态是否职责清晰、兼容旧数据；
4. 测试、Lint、Debug/Release 构建是否足以支撑阶段收口。

它的价值是让下一阶段只需从冻结检查点继续，不必重新推导九批历史，也能防止“批次记录均显示完成，
但最终代码或台账已经漂移”。本文件冻结后只修正文档事实错误；后续累计复核创建新的
`phase-N-review-YYYY-MM-DD.md`，不覆盖本阶段结论。

相关文档及职责：

- [original-architecture.md](../original-architecture.md)：Fork 前架构历史基线，冻结不改。
- [fork-simplification-plan.md](../fork-simplification-plan.md)：0.0.1 → 0.0.2 精简目标和落地记录，冻结不改。
- [mcp-lifecycle-analysis.md](../mcp-lifecycle-analysis.md)：当前 MCP 生命周期实现与后续优化依据。
- [upstream-sync.md](../upstream-sync.md)：同步方法、原则、检查点和各批摘要的唯一总账。
- 下文“九批范围闭合”表逐批链接到第 1～9 批记录；每份记录保存该批的逐提交分析、适配与验证证据。

## 一、结论

当前阶段可以收口：九批上游范围从 `5b9be301` 到 `2d61ba95` 连续覆盖 131 个提交，
没有范围间隙或重复；其中 90 个提交已按本地架构同步或选择性引入，41 个按 Fork
边界明确跳过。第九批最初遗漏的两个 P0 已补齐，当前没有挂在“已完成”名下的待办项。

代码最终态符合 Measix Pilot 的目标：保留 AI 对话、MCP、本地工具、Workspace、
Skills、记忆与备份等核心能力；继续拒绝赞助商、无关 Provider/Search/TTS、Web 端和
上游版本线扩张。同步不是直接合并上游，而是以本地包名、Provider 集、MCP 恢复策略和
模块职责为约束做语义适配。

本轮实际修复了十二类问题：

1. 第九批两个 P0 只记录未实现：bind mount 文件路径读取、TTS 下拉菜单崩溃。
2. 同步了生产代码但遗漏上游回归测试。
3. 图片裁剪错误提示硬编码英文，违反五语言本地化约束。
4. K3 图标正则会误匹配 `sdk3`、`k30` 等无关名称。
5. MCP 生命周期文档仍描述“快速重连耗尽即 Error”，与 Dormant 实现冲突。
6. 同步台账存在首批失效哈希、批次范围重叠、动态终点和数量口径不一致。
7. MCP 导入冲突提示使用两个非位置格式参数，资源编译持续告警。
8. 缺少本地签名信息时仍强制绑定空 release signingConfig，导致 R8 后打包失败。
9. 搜索模块同时承担 UI 文案、反射创建和 Custom JS 运行时，且 Bing 忽略统一结果数上限。
10. 会话入口会把禁用 Provider 的模型视为可用，长按发送可绕过校验，模型入口还复制了选择逻辑。
11. v1→v2 Room 迁移把真实表名 `ConversationEntity` 错写为 `conversations`，旧数据升级必然失败。
12. 三个库模块缺少仪器测试依赖，`speech` 测试仍断言重命名前的 namespace，导致全模块设备门槛失效。

以上问题均已修复。

## 二、项目目标与同步边界复核

| 维度 | 当前结论 |
|------|----------|
| 产品方向 | 仍是面向移动端 AI Pilot 的精简 Fork，不回退为上游全功能聚合客户端 |
| 核心能力 | AI Provider 抽象、消息管线、MCP、Workspace/PRoot、Skills、Memory、本地工具、备份均保留 |
| 精简边界 | 赞助商、推荐 Provider、额外 Search/TTS/ASR、Web UI、Firebase 及上游版本号继续跳过 |
| 品牌边界 | 生产包名为 `net.weero.measix.pilot`，DeepLink 为 `measix://`，WebView 虚拟域名为 `measix.local` |
| 数据边界 | 本批无 Room schema 变更；旧 `contextMessageSize` 被安全忽略，新 `contextMessageLimit` 有默认值且异常值在使用时归一化 |
| 许可证 | `LICENSE` 与检查点 `2d61ba95` 的纯 AGPL-3.0 blob 完全一致 |

`original-architecture.md` 和 `fork-simplification-plan.md` 继续作为冻结历史基线，不用当前
实现反向改写。当前实现和偏离记录由 changelog、MCP 生命周期文档及逐批同步文档承接。

## 三、九批范围闭合

| 批次 | 冻结范围 | 提交数 | 同步/选择性引入 | 跳过 | 复核结论 |
|------|----------|--------|-----------------|------|----------|
| [1](batch-1-2026-06-20.md) | `5b9be301..7f709b23` | 8 | 3 | 5 | 旧短哈希已映射到当前 upstream 历史，补齐文档/发布提交 |
| [2](batch-2-2026-06-27.md) | `7f709b23..a6e7a305` | 13 | 7 | 6 | 移除与第 1 批重复的范围口径 |
| [3](batch-3-2026-06-30.md) | `a6e7a305..4b2fd4b9` | 12 | 9 | 3 | 连续、判定闭合 |
| [4](batch-4-2026-07-04.md) | `4b2fd4b9..5b39e05d` | 9 | 9 | 0 | OAuth/文件夹等本地适配有持久化说明 |
| [5](batch-5-2026-07-06.md) | `5b39e05d..ef564dca` | 8 | 6 | 2 | 修正文首“5 个提交”笔误 |
| [6](batch-6-2026-07-08.md) | `ef564dca..d7f0ef26` | 23 | 17 | 6 | 将两次补充检查纳入最终冻结范围 |
| [7](batch-7-2026-07-08.md) | `d7f0ef26..449ce1e6` | 12 | 7 | 5 | 起点改为第 6 批最终检查点，消除 HEIF 提交重叠 |
| [8](batch-8-2026-07-17.md) | `449ce1e6..8eebe950` | 24 | 13 | 11 | 11 个完整同步 + 2 个选择性引入 |
| [9](batch-9-2026-07-28.md) | `8eebe950..2d61ba95` | 22 | 19 | 3 | 两个 P0 与阶梯上下文策略均已补齐，无待办 |
| **合计** | `5b9be301..2d61ba95` | **131** | **90** | **41** | 分段合计与 `git rev-list` 直接计数一致 |

跳过项均能归入稳定边界：Fork 已移除的功能、新 Provider/Search/TTS/ASR、赞助商/CI/Web
文档或独立版本发布。没有发现以“精简”为名误跳过的安全修复、MCP 修复、本地工具修复或
模型兼容修复。

## 四、当前变更逐文件最终态审查

### 4.1 AI 与消息管线

| 文件 | 审查结论 |
|------|----------|
| `ai/.../ChatCompletionsAPI.kt` | K2.6 仅在思考启用时发送 `keep=all`；cached token 兜底优先级正确 |
| `ai/.../ResponseAPI.kt` | 函数工具和内置工具只生成一个 `tools` 数组，无覆盖 |
| `ai/.../ModelRegistry.kt` | 裸 `k3` 为 exact alias，vision/tool/reasoning 能力完整 |
| `ai/.../ui/Message.kt` | 阶梯裁剪只在越过阈值时移动锚点，并统一回退到完整 USER 轮次；同一边界函数供语义压缩复用 |
| `GenerationHandler.kt`、`ChatService.kt` | 请求级裁剪不改写会话；Tool prompt 与请求共享裁剪结果；显式压缩的保留/分块不再拆散轮次 |
| `Assistant.kt`、`AssistantBasicPage.kt` | 新字段默认关闭；显式开关开启后默认 80、范围 40～512，旧字段不会恢复新行为 |
| `TemplateTransformer.kt` | 模板 time/date 使用每条消息的 `createdAt`，历史请求结果稳定 |

结构判断：本地适配的消息阈值只是默认关闭的请求级启发式，不冒充 token/window 管理；手动摘要
继续显式、可预期地改写历史。当前职责边界清楚，统一 token 预算仍是下一阶段中风险架构任务，
方案见 [`context-management.md`](../context-management.md)。

### 4.2 Skills、Workspace 与文件系统

| 文件 | 审查结论 |
|------|----------|
| `SkillsTools.kt`、`SkillManager.kt`、`SkillFrontmatterParser.kt` | metadata 负责定位真实目录，parser 独立，工具不再反向依赖 manager |
| `WorkspaceDocumentsProvider.kt` | SAF create/copy/move capability 与实现一致，复制/移动失败路径有明确返回 |
| `WorkspaceManager.kt` | 唯一拥有 rootfs→宿主路径解析与挂载表；最长 target 优先，路径仍经 canonical containment 校验 |
| `WorkspaceShellRunner.kt`、`ProotShellRunner.kt` | Shell context 接收同一挂载表；保留 `PROOT_NO_SECCOMP=1` |
| `WorkspaceRepository.kt` | 只负责 workspace 记录解析、IO dispatcher 与 manager 委托，不复制路径规则 |
| `WorkspaceTools.kt` | 文本和图片统一走 rootfs buffer，并统一执行 8 MiB 上限 |
| `RepositoryModule.kt` | `/skills`、`/tool_outputs`、`/upload` 在一个位置声明，同时供 shell 与文件工具使用 |

结构判断：路径规则从 app 工具层下沉至 workspace 模块，职责比初始同步清晰；bind mount
不再有“shell 能看到、文件工具看不到”的双重事实源。

### 4.3 UI、主题、WebView 与备份

| 文件 | 审查结论 |
|------|----------|
| `Select.kt`、`TTSProviderConfigure.kt` | 四处本地下拉全部迁移；普通 DropdownMenu 有 240dp 高度上限，现有四类 Provider 保持不变 |
| `CropLauncher.kt`、5 个 `strings.xml` | 用户只看到本地化错误，异常详情保留在日志；五语言键完全一致；MCP 冲突提示改为位置参数 |
| `MinimalTheme.kt`、`ClaudeTheme.kt`、`PresetTheme.kt`、`PresetThemeButton.kt` | 两主题完整注册，网格布局保持空位对齐 |
| `Mermaid.kt`、`WebView.kt`、`WebViewLocalAssets.kt`、`WebViewPage.kt` | `measix.local` 常量为唯一 origin，asset 拦截和两个入口一致，不再引用 Mermaid CDN |
| `mermaid.min.js` | 本地 blob 与冻结上游检查点完全一致 |
| `BackupVM.kt` | 本地导出/恢复强制使用全部 BackupItem，不继承 WebDAV 过滤 |
| `AIIconMatcher.kt` | K3 使用 token 边界，保留 `kimi-*` 匹配且不误命中普通子串 |

### 4.4 配置、许可证、MCP 与测试

| 文件 | 审查结论 |
|------|----------|
| `app/build.gradle.kts` | 版本递增到 11/0.0.11；签名完整时构建正式包，否则允许 unsigned release 验证 |
| `compose_compiler_config.conf` | Conversation/MessageNode 稳定性声明使用当前 Fork 包名，不再引用 RikkaHub 旧包 |
| release baseline profiles | 清除旧签名遗留规则；新 `limitContext` 不手工伪造 profile，等待下次设备采样 |
| `gradle/libs.versions.toml` | 仅 material3 alpha23→alpha24，编译链验证覆盖 |
| `LICENSE` | 纯 AGPL-3.0，与上游 blob 一致 |
| `McpManager.kt`、`mcp-lifecycle-analysis.md` | 代码注释和当前 5 次快速重连→Dormant 30 次→Error 一致 |
| 第九批 9 个新增/扩展测试文件 | 覆盖 rootfs 路径、Response tools、Moonshot thinking、usage 方言、Skills 目录、K3 能力/图标边界、阶梯裁剪与旧字段兼容 |
| `Migration_1_2.kt`、`Migration_1_2_Test.kt` | 迁移目标对齐 v1/v2 导出 schema 的 `ConversationEntity`；测试使用当前 SQLite 冲突参数，并在模拟器上验证 schema 与旧数据 |
| `highlight`/`material3`/`search` 构建脚本、`speech` 仪器测试 | 补齐 AndroidX Test 依赖，测试包路径、namespace 和 packageName 断言一致 |
| `chat-generation-pipeline.md`、`context-management.md` | 生成链路按本地工具/MCP/通知结构同步；缓存、压缩和 token/window 的当前边界有明确决策 |
| `changelog.md`、`upstream-sync.md`、batch 1～9 文档 | 版本、范围、计数、状态与当前 Git 证据统一 |

### 4.5 0.0.11 本地收口：搜索与会话入口

| 文件 | 审查结论 |
|------|----------|
| `search/.../SearchService.kt` 与 3 个 provider 实现 | `SearchProviderType` 显式创建 Bing/Tavily/SearXNG；网络模块不依赖 Compose，也不再承担 UI 说明文案 |
| `CustomJsSearchService.kt`、search 资源、依赖与兼容测试 | Custom JS 搜索执行面完整移除；旧 `custom_js` 配置由宽容列表解码跳过，并在无有效项时回退默认 Bing |
| `SettingSearchPage.kt`、`SettingSearchDetailPage.kt`、5 个 app `strings.xml` | 设置页只展示仍受支持的 provider；说明文案位于 app 资源层，五语言键一致 |
| `SearchTools.kt`、`BingSearchService.kt` | 单次工具调用只解析一次服务实例；Bing 结果遵守统一 `resultSize` 上限 |
| `ConversationReadiness.kt` 与 3 组本地收口测试 | 新增会话就绪、旧搜索配置兼容、搜索 provider 注册表测试；纯状态构建器区分“缺少可用模型 Provider”和“已有模型但未选择”，并区分 MCP、本地能力和 workspace；只有模型阻断发送，禁用 Provider 与非对话模型不会误报可用 |
| `ChatPage.kt`、`ChatInput.kt`、`ModelList.kt` | 会话卡片与输入框共享同一模型面板状态；模型选择写回当前会话的助手；收藏、当前选择和生成服务统一排除禁用 Provider |
| `ProviderConfigWarningCard.kt`、`SettingPage.kt`、`ChatList.kt` | 设置页与会话页复用同一紧凑 Provider 阻断卡；配置卡使用正常主题表面色，只有模型行表达阻断。顶部安全区与列表内容间距不再重复，配置提示距顶端收紧为 8dp |
| `ConversationReadiness.kt` 与 5 个 app `strings.xml` | 四项状态改为独立短标签，MCP/本地能力/工作区保持可选；中文显示“小睿远控”，其他 locale 使用对应的 Measix Device Remote Control 文案，用户可见文字均来自资源层 |
| `AssistantPicker.kt`、`ChatDrawer.kt` | 当前助手显示为有边界的选择器，整块主区域打开切换面板；独立编辑按钮进入当前助手配置，下方助手设置继续承担全局管理 |

结构判断：搜索 provider 的协议/执行留在 `search`，产品说明与配置交互留在 `app`。这是对
`fork-simplification-plan.md` 历史基线的后续收敛，只移除 Custom JS **搜索 provider**；代码高亮和
本地 JavaScript 工具仍需 QuickJS，因此 `common`/`highlight` 的运行时与现有 baseline profile 规则继续保留。会话就绪状态集中为
可单测的派生模型，UI 只负责展示和路由。就绪逻辑没有复制模型选择面板，也没有把 MCP、工作区等可选能力
误变成发送前置条件。独立需求文档已删除，稳定事实由 changelog、代码测试和本阶段复核共同承接。

## 五、架构与职责评价

当前模块边界总体清晰：

- `ai` 负责 Provider 协议、模型能力、消息与 token usage，不依赖 app UI。
- `workspace` 负责文件系统安全边界、rootfs 映射和 shell 执行。
- `app/data/repository` 负责持久化实体到模块能力的协调。
- `app/data/ai/tools` 负责把领域能力转换为模型 Tool schema，不再拥有底层路径规则。
- `search` 负责 provider 配置结构与网络执行，app 负责产品文案和设置交互。
- Compose 组件负责状态与展示，用户可见文案全部通过资源层。
- MCP 仍由 app 级单例统一管理连接、OAuth、网络/前台恢复和工具同步，符合配置驱动模型。

仍需关注三项非阻断技术债：

1. `McpManager` 职责较多。下一次结构迭代可先抽离纯函数和 session/job 容器，但必须保留
   per-server Mutex、Dormant、网络恢复和 OAuth 竞争控制，不应为了贴近上游而直接替换架构。
2. 当前阶梯消息阈值不能保证模型窗口安全。下一阶段需一起引入模型窗口元数据、Provider 请求前
   token 估算、输出预留和可审计摘要；在此之前保持默认关闭，不能把固定消息数当作 token 管理。
3. Baseline Profile 仍来自既有设备采样。本轮只清除了确定失效的方法规则；下次正式性能发布前应在
   目标设备/模拟器上重新生成，覆盖本阶段新增的 Workspace、WebView 和主题交互路径。

## 六、验证与完成门槛

- `git diff --check`：通过。
- 最终工作树审查覆盖 108 个路径（87 个已跟踪变更、21 个新增文件），按 root 文档、`ai`、`app`、
  `search`、`workspace`、Gradle 与同步文档逐组核对；未发现遗留 `.orig`/`.rej`/临时文件。
- 五语言 XML：均可解析，且每个文件 1211 个字符串键，missing/extra 均为 0。
- LICENSE、Mermaid blob：与 `2d61ba95` 完全一致。
- 范围连续性：九段合计 131，与 `git rev-list --count 5b9be301..2d61ba95` 一致。
- `:workspace:test :ai:test :app:testDebugUnitTest`：通过。
- 全量 `test`、`lint`、`assembleDebug`、`assembleRelease`：✅ 通过（831 tasks；114 executed、717 up-to-date）。
- JVM 单元测试汇总：377 tests，0 failures，0 errors，0 skipped；9 份 Lint 报告合计 0 errors。
- 全模块 `connectedDebugAndroidTest`：✅ 在 `Small_Phone(AVD) - 17` 上通过（9 个模块、10 tests，
  0 failures、0 errors、0 skipped）；其中 app 的 2 项测试真实执行 v1→v2 schema/数据迁移。
- x86_64 Debug 包模拟器视觉核对：✅ 空会话阻断/配置层级、模型单项警告、顶部间距和抽屉助手
  切换/管理入口均按预期显示。
- 设备回归首次复跑在 `highlight` 测试 APK 安装阶段遇到一次 ADB 连接超时；恢复设备连接后定向复跑
  通过，随后完整 9 模块回归重新从头通过，判定为模拟器通信瞬时故障。
- Debug 与 unsigned Release 的 universal/x86_64/arm64-v8a APK 均已生成；产物元数据均为
  0.0.11（versionCode 11），R8、资源压缩与 APK 打包链路验证通过。
- 定向复跑曾遇到 Gradle daemon 占用 `processDebugResources/R.jar`；停止残留 daemon 后同命令与
  随后的全量串行门槛均通过，判定为构建缓存文件锁而非代码或资源问题。
- 首次并发全量构建曾因 4 GiB Gradle heap 在 Debug 打包与 Release R8 并发时耗尽；停止旧 daemon 后以
  `--no-parallel --max-workers=1` 串行复跑通过，判定为构建机资源竞争而非代码或打包配置缺陷。

自动化仪器测试已经闭合；TTS 菜单、SAF 和 WebView 的手势、系统选择器及 OEM WebView 行为仍建议
进入发布前真机冒烟清单。
