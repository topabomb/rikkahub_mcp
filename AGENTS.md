# Measix Pilot 仓库工作约定

本项目是 Kotlin/Compose Android Agent 应用。本文件规定如何开展修改、必须保持的架构边界和交付要求；
具体字段、状态机及协议细节由 [应用架构与专题导航](docs/references/application-architecture.md)及各领域参考文档维护。
代码和契约测试用于核实当前实现。文档与实现不一致时，先查明原因，再在相关变更中同步修正，不能通过增加旁路掩盖问题。

## 开展任务

1. 先确认任务范围和工作树状态，区分本次工作与已有改动。用户要求只分析时保持只读；实施时保留无关改动，
   不回退、覆盖或顺带提交。只有需求存在关键歧义，或冲突无法安全绕开时，才请求用户决定。
2. 按任务阅读对应参考文档，并沿实际调用链核对写入入口、消费者及测试。涉及行为、数据或职责变化时，先明确
   哪个组件负责写入、何时提交、失败如何恢复。文字修正和局部显示调整不需要通读所有文档或整个仓库。
3. 优先扩展已有命令、应用服务、查询投影和状态机，让逻辑留在原负责组件内。完成本次替换时移除失去调用者的
   旧路径、转发层和过渡命名，但不要借机重构无关代码。没有真实消费者的抽象、配置项和扩展机制不要预先加入。
4. 将实现、必要测试和对应文档作为同一变更完成。行为或职责变化需要更新相应契约；静态测试只约束依赖方向和
   架构边界，运行行为由行为测试证明，不为文案调整增加源码扫描断言。
5. 在已明确的范围内持续完成修改、验证和失败修复，不停在第一版实现。验证受环境限制时，说明具体阻碍、
   已验证范围与剩余风险，不能将未执行的检查写成通过。

模块职责保持清楚：`app` 负责 UI、应用编排、Room/DataStore 和 Android 集成；`ai` 负责 Provider、线协议、
`UIMessage`、流式合并及工具基础模型；`speech`、`workspace` 分别负责语音和 PRoot/文件/执行能力。
`common` 只放跨模块通用工具，`document`、`highlight`、`material3`、`search` 各自负责文档解析、代码高亮、
动态配色和搜索 SDK。不要为了方便调用，把应用业务状态下沉到通用模块。

## 必须保持的架构约定

### 状态归属与事务发布

每类持久化事实只有一个负责写入及生命周期管理的组件（owner）和一套写协议。跨领域操作通过已有类型化接口编排，
不得旁路访问 DAO/Repository、使用服务定位器、从旧投影回写整个聚合，或建立第二份可编辑状态。

UI/ViewModel 只提交 application 命令、消费 query/UiModel，不直连 DAO、Repository、Runtime Registry、
Artifact/GeneratedMedia Store 或 payload 层。聊天使用 `ConversationPresentation`，不持有 Runtime Job，
也不能从显示列表推导持久化写入。持久化流程使用 `ConversationAggregateSnapshot`，不消费显示快照。

会话修改沿 `ConversationCommandCoordinator`、Runtime、Transition 与 `TurnCommitter` 的既有提交链执行。
事务成功后才发布持久状态；流式投影可以提前显示，但不作为已提交事实。标题由 `ConversationTitleCoordinator`
串行管理，模型结果必须通过请求 token 与 expected-title 比较并交换，不能覆盖请求发出后的手动标题。

### 会话、工具与取消

- 新聊天先是内存 Draft。首条 `AppendUserMessage` 在同一事务中建库并原位晋升 Ready，空 Draft 不进入持久列表或 Turn。
  `START` 创建新一轮，`CONTINUE_USER_INTERACTION` 保留原 `TurnHandle`；继续审批或回答不能清树、补填附件或新建第二轮。
- 工具装配、参数校验、交互、执行和终态使用明确的类型与阶段，并共享同一 Step 工具索引。
  `Tool.parseArguments` 和工具纯校验先于审批；资源、权限和远端业务校验由执行组件负责。
  未执行的拒绝只提交消息结果，不创建执行记录；执行阶段只随已提交检查点推进，metadata 不另建平行状态机。
- 工具的 output 或可回放结果只证明结果存在，不能代替活动阶段、当前权限或详情访问资格。
  子助手复用同一生成与终态协议，不另建简化执行链。
- 取消必须向上传播，不能被 `runCatching` 或宽泛 catch 吞掉。`NonCancellable` 仅用于已经取得明确资源或事务
  所有权后的提交、回滚及必要清理；它不是让整个操作忽略取消的手段。停止完成应包含原任务和资源实际退出，
  不能只更新 UI 状态后遗留后台执行。

### 配置、企业空间与界面

用户配置由 Settings 负责，企业发布配置由 Enterprise 负责，`ConfigurationResolver` 按原空间解析。
UI 只提交类型化字段命令，不从显示值反推持久化语义，也不把用户 Settings 当成企业空间的有效配置。
异步查询、执行和写入必须保留原空间、Session 与资源身份；切域或重新登录不能让旧操作自动获得新授权。

企业助手定义的 `modelId` 是默认模型。准入允许时，可选择本域企业模型或用户自有模型，受管定义、System Prompt
和固定 MCP 仍只读。“助手默认 / 空间默认 / 指定模型”必须在存储及 UI 中保持可区分，显式失效引用应报告原因，
不能静默改用其他模型。构造默认、缺失字段迁移默认、读取物化结果与企业字段未提供的语义也不能混用。

移动端默认界面保持低密度、清晰动作归属与渐进披露。内部 owner、generation、准入等概念只在用户需要作决定或
诊断失败时出现。编辑跨空间共享的用户定义时明确说明影响，并复用原编辑器；不要复制第二套企业编辑界面。

### 模型上下文与历史

普通配置、System、工具定义和模板变量在 START 捕获，同一 Turn 的 Step、重试和交互继续复用。
运行记忆与可见助手目录在每个新请求边界按当前权限读取并对账，企业 Memory Seed 使用 Turn 捕获值；
输入冻结不冻结执行权限。请求上下文经原会话事务接纳后才开始 Provider IO，接纳不等于模型已收到。
历史正文、来源和因果位置必须保全，不能根据当前 Settings 重建过去输入，也不能把应用上下文伪装成用户指令。

Starter 是任务入口，Opening 是首次用户提交时与会话一起保存的开场副本。选择入口不自动发送；
首发需复验完整定义与准入，已有会话不能因重新选择入口而改写开场。详细版本与分支规则见配置和请求上下文参考。

自动历史工具输出处理只使用 rolling compaction：成功请求后、检查点前，由
`ToolOutputCompactionPlanner.planAfterSuccessfulRequest` 计划，阈值统一来自 `ContextBudget`，
由 `ToolOutputStore.stageCompaction` 写入。只处理本次成功请求确实可见且已消费的历史 inline result：
`ARCHIVABLE_TEXT` 归档，`REGENERABLE_TEXT` 折叠，`PRESERVE` 保留；保护窗口内或净回收不足的批次不改写。
上下文长度控制优先，其次保持 prompt cache 前缀稳定。手动会话摘要是独立显式操作，自动会话摘要尚未实现。

### 文件、数据与恢复

- Artifact 元数据、引用和生命周期只归 `ArtifactStore`，`ArtifactPayloadStore` 只做磁盘 IO；生成媒体记录、
  payload 与删除恢复只归 `GeneratedMediaStore`。文件 application/query service 负责跨组件编排和只读投影。
  未发布资源通过类型化所有权或租约交接，成功提交后发布，失败或取消只回滚本操作拥有的资源。
- `attachment:<uuid>` 索引由 `AttachmentReferenceLookup` 管理，对模型披露真实 `/upload` 路径。
  UI 不扫描消息 metadata 或解析子助手 payload；文件读取由 `ArtifactStore` 校验，不依赖当前分支引用或 Workspace，
  路径和 ID 本身不是授权证明。
- 启动恢复只由 `ApplicationRecoveryCoordinator` 按固定顺序编排，完整顺序见应用架构；不要另写一份简化顺序。
  待恢复备份先于运行存储开放，文件恢复先于引用投影；恢复链成功后才开放全局持久化写入及受管文件访问。
  未处理异常或取消必须保持门禁关闭；企业配置校验失败由企业领域处理，不阻断个人数据恢复。
  恢复错误保留诊断和重试入口，不能通过空列表、日志或尽力而为的操作假装恢复成功。
- Room、DataStore 和文件格式变化必须提供显式迁移与数据保全验证，升级后的 schema 与新安装保持同构。
  内部重构应移除旧执行路径；已发布数据和外部协议的兼容则在明确的迁移/解析边界维护，不能随意删除。
  个人备份恢复不得整体覆盖企业数据；删除和恢复使用可重试状态机、幂等操作及必要的比较并交换。

### 错误与诊断

预期的校验、权限和业务拒绝使用稳定 reason/code，并提供可行动说明。非预期异常必须保留异常类型、原始
message/detail、有意义的 cause 与完整日志堆栈；用户可见长诊断应能展开或复制，不能只记录日志后返回空值、
假成功或“操作失败，请重试”。只脱敏凭据、token、Cookie、Authorization、密钥和明确的隐私 payload，
不删除定位需要的非敏感细节。取消不显示为失败；新增错误路径应验证诊断、重试或恢复行为。

## 验证与交付

先运行最接近改动的验证，再根据风险扩大范围。只为关键业务、持久化、数据结构、并发、取消、事务、恢复、
授权或资源所有权增加高价值测试，重点覆盖失败和竞态；不要复制实现细节或用静态扫描代替真实行为。
已有测试不能仅因旧、慢或版本较早就删除，先确认其保护的契约是否仍存在。

Windows PowerShell 使用 `.\gradlew.bat`，macOS/Linux 使用 `./gradlew`。
所有 Gradle 验证固定附带 `--no-parallel --max-workers=1`，不要同时启动多份 Gradle 门禁。

| 变更范围 | 验证要求 |
| --- | --- |
| 纯文档、说明或格式 | 核对事实、链接、编码及最终 diff；没有运行行为变化时无需执行 Android 全量构建 |
| 单一领域行为 | 运行对应模块的定向测试，并补充受影响的编译、lint 或集成检查 |
| 架构、跨模块或数据契约 | 在定向验证后执行 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` |
| 数据库迁移、Compose instrumentation、依赖 Android 平台行为或真实系统集成 | 还必须运行 `connectedDebugAndroidTest --no-parallel --max-workers=1`，并验证受影响的设备场景 |

例如，Windows 定向测试可执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "<完整测试类名>" --no-parallel --max-workers=1
```

设备门禁使用专用测试设备并明确目标，安装/卸载可能清除 Debug 数据，不能直接用于保留用户或日常演示数据的设备。
真实 Core/Provider、有效麦克风输入、PRoot 镜像等显式场景，按 [测试策略](docs/references/testing-strategy.md)
准备环境并分别记录；JVM、构建、模拟器和真实服务的证据不能相互替代。失败后的重跑通过不等于根因已修复。

交付前检查 `git diff --check`、最终 diff、报告、编码/换行和工作树。说明具体改变、验证结果、未验证边界，
以及实际 commit/push 状态；不要顺带更新 `versionCode`、`versionName` 或 changelog，除非用户明确要求发布版本。

## 审查重点

重大重构、跨模块/数据契约变更及交付前关键审查，可以使用最多 2 个独立上下文子代理；简单检查直接完成。
委派时明确范围，避免多个代理编辑同一文件。审查以可证实的问题为准，给出触发条件、影响和代码依据，
优先检查唯一写入职责、事务发布、授权时效、取消清理、兼容性和失败诊断。不为凑数量报告猜测或格式偏好，
主代理负责核实发现、修正并完成必要复验。

## 领域文档与维护

按下表选择与任务相关的入口；其他专题从应用架构导航进入，不要求每次任务全部阅读。

| 任务涉及 | 优先阅读 |
| --- | --- |
| 职责划分、跨模块调用、启动恢复 | [应用架构](docs/references/application-architecture.md) |
| 会话、生成、工具交互、标题 | [Turn/Step 执行](docs/references/turn-step-execution.md) |
| 模型输入、工具输出压缩、上下文来源 | [请求上下文](docs/references/request-context.md)、[提示词与工具](docs/references/prompts-and-tools.md) |
| 助手、企业空间、Starter、配置迁移 | [配置架构](docs/references/android-configuration-architecture.md)、[助手配置](docs/references/assistant-configuration.md) |
| 文件、数据库、备份 | [多模态资源](docs/references/multimodal-context-and-turn-durability.md)、[数据持久化与恢复](docs/references/data-persistence.md) |
| 页面、显示投影、布局 | [UI 架构](docs/references/ui-architecture.md)、[消息渲染](docs/references/message-rendering-pipeline.md) |
| Provider、MCP、子助手、Workspace、语音 | 从应用架构进入相应专题；不要仅凭通用 SDK 经验推断项目协议 |

- `docs/references/` 只维护当前事实，用类、函数、常量和真实工具名定位，不用易失行号、阶段编号或内部黑话。
  按稳定领域组织参考文档；相关子主题优先并入现有文档，只有具备独立职责和阅读场景时才新增专题。
  同一规则只在所属专题完整维护，其他文档保留理解边界所需的摘要；删除重复字段、过程记录和可直接从代码获取的低价值清单。
  本文件只保留跨任务约定和高风险边界，不累积事故记录或重复专题说明。
- `docs/dev/original-architecture.md`、`docs/dev/fork-simplification-plan.md` 是历史归档；上游同步每批先 fetch，
  再续写 `docs/dev/upstream-sync.md` 及对应记录。历史与未实施方案不能充当当前实现依据。
- 遵循 `.editorconfig`：Kotlin/Gradle 4 空格，XML/JSON/Markdown/YAML 2 空格，UTF-8 无 BOM。
  常规源码和文档沿用 CRLF；明确要求 LF 的脚本、补丁及生成文件按各自格式保留，不做无关换行转换。
  注释解释长期语义、资源责任和非显然原因，不保留临时实施计划。
- 常见用户可见字符串同步 `values`、`values-zh`、`values-ja`、`values-ko-rKR`、`values-ru`；
  Compose 使用 `stringResource`，非 Composable 使用 `Context.getString`。底层英文 reason/detail 可只保留源语言。
- Release 保持 AGP 9 optimization，运行时 keep rules 位于 `app/src/main/keepRules/rikkahub.keep`。
  `android.r8.strictFullModeForKeepRules=false` 是现有依赖 consumer rules 的兼容要求，未核验依赖前不得移除。
  APK 保持 `arm64-v8a` / `x86_64`，App Bundle 构建禁用 APK splits，Debug 包名带 `.debug`。
