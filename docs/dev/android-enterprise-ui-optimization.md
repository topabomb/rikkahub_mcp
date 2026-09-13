# Android 企业域界面审查与优化方案

## 1. 审查范围与结论

本次审查以 `docs/dev/android-enterprise-integration-plan.md` 首次进入仓库前的提交为交互基线，检查从
`f49f86f9` 到当前实现的可见界面变化。期间共有 131 个 UI 与资源文件发生变化；其中大部分变更是
Realm、Session、资源引用和文件所有权的正确传播，真正增加用户理解成本的变化集中在空间入口、企业主页、
助手、模型、语音、MCP 及共享配置提示。

企业/个人隔离继续由配置解析、application/query port 和原始 RealmSelection 保证。界面不再重复解释
owner、definition、generation、准入等内部概念，而只在用户需要作决定时呈现来源、当前效果和可执行动作。

本次产品裁决同时替换实施方案中的旧约束：企业定义助手的 `modelId` 是助手默认绑定，不是不可覆盖的强制
绑定。企业配置和准入允许时，用户可以为该助手选择当前空间可用的企业模型或个人模型。企业定义本身、
受管 system prompt 和固定 MCP 引用仍不可由用户改写。

## 2. 设计原则

1. 默认路径回归企业域引入前的密度与结构；域逻辑不应让个人空间页面永久变复杂。
2. 页面按当前状态渐进披露。未接入、已接入但位于个人空间、企业空间 Ready、Pending/Offline/失效分别只
   显示当前可执行的入口，不用大量 disabled 控件解释互斥状态。
3. 空间归属在顶层入口和混合资源分组处表达一次。应用级设置不显示空间，用户定义页不重复显示“跨空间
   共享”长提示，真正从企业上下文跳转编辑用户定义时再确认影响。
4. 图标用于返回、关闭、刷新、编辑、展开、更多、收藏和删除等熟悉动作；接入、切换空间、退出、导入、
   恢复默认和模拟场景保留文字。企业/个人身份不能只依赖颜色或图标，无障碍描述必须完整。
5. 同一事实只保留一个用户界面。Starter、助手目录、助手定义和当前空间使用偏好不得各自复制详情投影、
   记忆编辑器和导航状态。
6. 正常状态不暴露内部机制；操作失败时必须直接呈现原始错误 message/detail，缺少 message 时至少显示异常
   类型。不得用通用失败文案覆盖内部 reason/code；完整堆栈仍进入日志，避免把不可读的长堆栈挤入移动端主界面。

## 3. 变更矩阵

| 范围 | 当前问题 | 调整方案 | 主要落点 |
| --- | --- | --- | --- |
| 聊天与设置的空间入口 | 聊天顶栏同时容纳双行标题、最长 120dp 的空间文字、选项和新聊天；设置首页裸放 TextButton | 聊天顶栏使用紧凑空间图标；抽屉保留图标与空间名；设置使用标准 CardGroup 行并显示当前值 | `EnterpriseSpaceButton`、`ChatPage`、`ChatDrawer`、`SettingPage` |
| 企业空间页 | 成员入口、接入、导入、本地模拟管理和连接诊断同级堆叠；已接入仍显示禁用接入区；返回强制跳 Startup | 按登录/所在空间/Session phase 裁剪内容；常用动作前置；连接详情和本地工具折叠；普通返回遵循 back stack，只有切域/退出成功重建聊天 | `EnterprisePage`、`EnterprisePageAndroidTest` |
| 企业 Starter 与助手查看 | `EnterpriseExperienceDialog` 同时实现 Starter 和第二套企业助手列表/详情/记忆，且从企业页和用户助手页旁路进入 | 保留独立 Starter picker；删除 ASSISTANTS mode、企业助手详情复制投影及用户助手页旁路。企业助手通过聊天统一选择和当前空间设置管理 | `EnterpriseStarterPicker`、`EnterpriseStarterUiModel`、`AssistantPage` |
| 新聊天配置概览 | 五个配置项始终展开解释文字，窄屏与大字体下占据大半首屏 | 默认只显示图标、名称和当前状态；只有模型阻塞、MCP 异常或已选本地能力失效时展开可行动说明 | `ConversationReadiness` |
| 助手当前空间设置 | 9 个横向页签在手机上不可扫读，并与普通助手详情结构不一致 | 改为与普通助手详情一致的分组入口；进入子页后显示返回与关闭。扩展合并为一组，仅在组内保留三个子类 | `AssistantUsageEditor` |
| 助手模型选择 | 界面只显示最终模型，无法区分继承助手默认、跟随空间默认和指定模型；清除图标语义含糊 | 投影 typed 三态；模型 Sheet 以可勾选行明确“助手默认”“空间默认”，目录选择表示显式模型。企业助手和企业域用户助手共用规则 | `ResolvedConfiguration`、`ConversationConfigurationUiModel`、`ModelList`、`AssistantUsageEditor` |
| 默认模型设置 | 7 个模型槽位各占一个同名 CardGroup 和说明；首屏只能看到少量选项；关闭建议仍展示模型行 | 分为“对话”“后台任务”“媒体与文件”三个紧凑 CardGroup；每行只保留标题与当前模型，角色含义由清晰名称和分组表达；建议关闭时隐藏模型行；恢复默认放到行内图标 | `SettingModelPage` |
| 模型选择器 | 已按类型过滤仍重复显示类型标签；每个模型都显示 Provider 编辑箭头；个人目录也重复显示来源 | 删除冗余类型标签；Provider 编辑入口提升到组标题；仅混合企业目录显示短来源；增加 Sheet 标题、关闭动作和 typed 选择动作 | `ModelList` |
| Provider/Search/Prompt/Quick Message | 每个列表和详情页首屏重复“共享用户配置”说明 | 恢复原页面结构；从企业空间跳入共享定义编辑时使用一次确认，其余页面不常驻提示 | 对应设置与扩展页 |
| Speech | 当前空间选择、共享服务管理、来源、测试、编辑、删除和排序塞在同一卡片；个人空间也重复 scope/source | 个人空间恢复原密度；企业空间只显示一次上下文，资源卡仅在混合目录显示来源；受管资源不提供编辑/删除/排序 | `SettingSpeechPage` |
| MCP | 个人空间无条件显示“我的 MCP”说明；受管卡片重复只读提示并展开全部工具 | 只有混合目录显示“企业工具 / 我的 MCP”分组；只读和 required 在组/短标签表达；工具详情按需展开 | `SettingMcpPage` |
| 备份与危险操作 | 个人备份不含企业数据是行为差异，若隐藏会产生数据预期错误 | 保留一次范围说明；退出、清除示例和导入继续使用文字与确认 | `BackupPage`、企业确认框 |

## 4. 文案规则

- 面向用户统一使用“当前空间”“助手默认”“空间默认”“我的选择”“由企业提供”；避免“本域”“定义字段”
  “generation”“准入”等实现词。
- 模型三态的持久化语义必须保持可区分：
  - 助手默认：没有 `AssistantUsagePreferences.chatModelId` 字段；以后助手默认变化时跟随变化。
  - 空间默认：保存 `UsageValue(null)`；以后空间默认变化时跟随变化。
  - 指定模型：保存 `UsageValue(reference)`；模型撤权或缺失时保留引用并显示不可用，不静默替换。
- 企业定义详情若出现模型字段，必须称“助手默认模型”，不能把它冒充当前最终模型。
- 五套字符串资源保持同步：`values`、`values-zh`、`values-ja`、`values-ko-rKR`、`values-ru`。

## 5. 架构与删除边界

- 删除 `EnterpriseExperienceMode.ASSISTANTS`、企业助手详情分支及只为该分支存在的
  `EnterpriseAssistantUiModel`。Starter query 只投影 Starter 所需字段，不再携带助手详情和记忆。
- `AssistantUsageEditor` 继续借用原 ConversationAssistantTarget 与 ConversationViewLease，不新增可持久化
  页面身份、第二配置快照或 DAO 访问。
- 模型偏好模式由配置解析层产生 typed 投影，UI 不读取 DataStore，也不根据最终 modelId 反推模式。
- 不改变 Room、DataStore JSON、企业包或 Portal 协议结构；现有 `Model(null)` 与 `InheritModel` 命令保留各自
  语义，不增加兼容别名。

## 6. 验证计划

定向测试覆盖：

- 企业助手与企业域用户助手的三种模型模式、相同引用下模式仍可区分、默认变化后正确跟随、显式模型撤权
  后仍显示不可用引用；
- 企业页未接入、企业 Ready、个人空间但保持企业登录、Pending/Offline/Reauth/失败状态的互斥入口；
- Starter 选择复验原 selection，旧企业助手查看分支无法回归；
- 设置 → 空间页普通返回保持来源，切域/退出成功才回到新聊天；
- 模型建议关闭时不显示模型选择行，企业覆盖可恢复默认；
- 360dp/390dp、fontScale 1.0/1.3 的企业页、模型页、模型选择器和助手空间设置无水平裁切，图标具备
  content description。
- 配置目录读取和命令失败保留异常类型、message 与 cause，同时脱敏 Authorization、Cookie、token、API key
  等凭据；取消继续传播，旧 selection 的迟到失败不能污染新页面。

完成定向验证后执行：

`gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`

模拟器安装新 Debug APK，实际走查个人 → 示例企业 → 个人、模型三态、Starter、设置返回栈和窄屏滚动。
设备测试只在实际运行 `connectedDebugAndroidTest` 后表述为通过；真实企业平台和实体设备不由模拟器结果替代。

## 7. 实施结果

本方案中的界面与投影调整已经完成：空间入口恢复紧凑层级；企业页按会话状态渐进披露；旧的企业助手
旁路查看界面及其专用投影已物理删除；Starter 只保留自己的选择器；助手当前空间设置改为分组概览；
助手模型偏好使用“助手默认 / 空间默认 / 指定模型”三态；默认模型页改为对话、后台任务、媒体与文件三组；
模型、Provider、Speech 与 MCP 页面移除了常驻的重复域说明，并在从企业上下文编辑共享个人定义时增加一次
确认。企业定义助手可在准入允许时选择当前企业空间中的企业模型或用户自己配置的模型，其受管定义、
system prompt 与固定 MCP 仍保持只读。

错误呈现遵循“常态简洁、失败可诊断”：配置目录读取失败、模型选择/收藏/排序失败和助手空间偏好保存失败
均通过 typed read state 显示异常类型、底层 detail/message 与有意义的 cause；无 message 时仍显示异常类型，
不再用笼统的“请重试”替换真实错误。`UserVisibleDiagnostic` 只脱敏凭据，完整堆栈仍写日志；History、Share、
Drawer、Speech、Starter 与模型目录不再用空列表或旧值掩盖读取失败。

两次独立审查分别从 UI/交互合理性以及架构/代码清晰度检查了最终实现。审查提出的三态歧义、重复页面
状态源、使用设置中的定义字段、共享设置误编辑风险、撤权引用可见性、测试缺口与临时仓库配置等问题均已
在本轮修正，没有保留兼容旁路或第二事实源。

模拟器配置页走查还带来以下收口调整：聊天就绪区去掉重复的“当前助手”标题并统一 MCP 短标签；企业助手
基础页明确模型是可覆盖的“助手默认”，提示词页明确只读，固定 MCP 明确不可移除；空语音目录提供空状态；
失效模型保留原引用并可重新选择；模型能力图标和抽屉设置图标补齐无障碍名称；“Mode Injection / Prompts”
统一为“提示词注入 / Prompt Injections”。

## 8. 验证结果

- 定向 JVM 验证通过：`AssistantPreferenceMutationTest`、`ConfigurationApplicationServiceTest`、
  `ConversationPageAccessTest`、`MemoryServiceTest`、`RetiredSurfaceContractTest`。
- 完整门禁通过：`gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`。
- 模拟器设备测试通过：`EnterprisePageAndroidTest` 8/8，`ModelCatalogAndroidTest` 1/1。覆盖普通返回栈、
  企业状态入口、共享个人 Provider 编辑确认、不可用模型、写入失败详情与重试。
- 第一轮模拟器走查覆盖个人/企业空间、工作台与 Starter、聊天摘要与模型选择、助手列表/详情/当前空间设置、
  设置首页与偏好、默认模型/Prompt、Provider、Search、TTS/ASR、MCP、扩展、Workspace 及窄屏聊天。
  修正发现后又安装最终 Debug APK，在 1080 × 2400、density 420、fontScale 1.3 下回归企业接入/Ready、
  Starter、企业聊天摘要、企业助手模型选择、基础设置、只读提示词、固定 MCP、共享设置确认和 Speech；
  上述回归页面均可滚动且未见水平裁切。
- 示例企业包是持久化快照；已接入的旧快照不会被应用升级静默改写。构建产物中的最新示例资源已核对为
  新文案和新行为。真实企业后端、Portal 联调及实体设备不在本轮本地模拟器验收范围内。
