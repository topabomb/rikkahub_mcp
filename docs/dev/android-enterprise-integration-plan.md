# Android 企业域本期实施方案（0.0.20）

> 状态：实施中，尚未完成整期开发与验收。本文是 `versionName=0.0.20` / `versionCode=20` 的需求、架构、UI、变更与验收权威。
> 本期交付正式企业域功能与本地模拟企业服务，Debug 和 Release 均有完整入口，不依赖真实企业后台或 Debug 开关。
> 后续真实接入见 [真实企业服务接入规划](android-enterprise-production-integration-roadmap.md)。`docs/references/` 只描述已实现事实。

## 1. 目标与范围

升级保留原个人配置、凭据、历史、引用和文件。用户从正式空间入口接入示例企业，使用企业或获准的用户助手、模型、工具、语音、工作台；可以切回个人、退出、重启恢复、修改模拟企业策略和资源，实际观察所有页面及执行行为。

| 本期必须完成 | 明确边界 |
| --- | --- |
| 三类配置迁移 | 用户配置、用户偏好、企业配置；没有 Enterprise Local 资源副本 |
| 唯一生效配置 | 按域解析的只读快照，UI、命令和执行共享规则，不另落盘镜像 |
| 五项用户资源准入 | Provider/Model、TTS、ASR、MCP、Assistant；清单外允许用户配置 |
| 正式空间入口 | 接入、切换、状态、更新、退出、重新接入；Debug/Release 一致 |
| 本地企业完整能力 | 身份、配置、Model/TTS/ASR/MCP/Gateway、助手/Seed/Starter、动态与 Portal |
| 既有功能接入 | Search、Skill、Prompt、本地工具、Workspace、子助手、辅助生成不笼统关闭 |
| 数据与恢复 | 会话/记忆/文件等按域主体，显式共享工作空间单独处理；个人备份不覆盖企业数据 |
| 手机能力 | 原生扫码，Portal 相机拍摄/麦克风录制、权限与生命周期真实执行 |
| 私有配置 | 无秘密完整示例随包提供；不进 Git 的配置文件可导入真实地址/凭据 |
| 发行 | 0.0.20 两种构建、设备验收、文档与实现提交、APK 和配置使用说明 |

不包含真实 Control Hub/Relay 认证和下发、后台建设、生产 Portal 部署、User Sync、远程 Agent Space/Runtime/Fleet、受管 Skill 发布协议。用户已有 Skill/本地 Workspace 在本期内。缺少真实凭据不阻碍默认示例运行。

平台架构仓库由其他任务维护。本期按 architecture、core、Portal 当前交付契约执行；企业 Snapshot、桥接协议和接入资料各有独立版本。
上游企业服务与 Android 企业入口均未进入生产，不保留旧企业契约、分批原型或其兼容路径；已发布个人配置与数据仍按明确迁移规则保全。
不得让真实服务端 Gate 阻塞本期本地实现，不宣称本地实现已获得生产互操作认证。

## 2. 分类与五项策略

### 2.1 内容归属

| 类别 | 内容 |
| --- | --- |
| A 运行资源 | Provider/Model、TTS、ASR |
| B 工具能力 | MCP、带地址/API Key 的 Search、本地工具、Gateway |
| C 经验配置 | Assistant、Skill、提示词/注入、QuickMessage、Seed、Starter；子助手定义及主从引用 |
| 用户偏好 | 显示/操作、资源选择、允许调整的开关 |
| 用户数据 | Conversation、运行 Memory、附件、生成媒体、执行记录；不属于企业配置 |

Policy 横切 A/B/C，不是第四种业务内容。是否含 API Key 不决定 A/B 分类。Memory Seed 随企业配置只读，运行记忆按域/主体可变。子助手仍由同一 Child Conversation/Turn 执行链执行。

### 2.2 策略控制清单

| 字段 | 允许的用户配置 |
| --- | --- |
| `allowLocalProviders` | 已有 Provider 及其 Model |
| `allowLocalTts` | 已有 TTS，包括 System TTS |
| `allowLocalAsr` | 已有 ASR |
| `allowLocalMcp` | 已有 MCP |
| `allowLocalAssistants` | 已有主/子助手定义 |

- true 直接引用用户原配置和凭据，不复制“企业版”。false 只使企业域该类用户内容不可选/不可执行，个人域原配置不变。
- 五项 Boolean 在本地企业候选中必须显式存在；缺失/null 拒绝整包。默认示例显式全部 true，另提供逐项 false 场景。关闭五项不等于封禁未列入的能力，不命名为“所有内容 Managed Only”。
- Search、Skill、Prompt Injection、QuickMessage、本地工具、Workspace 等清单外配置本期允许用户选择，保留原安全校验、审批和 OS 权限。后续增加控制项需明确默认与兼容，不默认封禁未知类别。
- 未受控入口调用 Model/TTS/ASR/MCP/子助手时仍复验对应规则，不得用搜索工具、图片工具或委派绕过资源准入。
- 用户资源使用用户凭据；不注入企业 Session、不上传用户凭据。企业定义不能覆盖同名用户定义。
- Built-in 资源保留原稳定身份，按对应类型参与用户准入；不能以 System TTS/默认助手名义绕过开关。安全、导航及不可移除执行基础协议不属于可选配置。

### 2.3 助手、选择与引用

用户 Assistant 定义只保存一份。企业域为用户助手保存本域使用选择（模型、MCP、允许的扩展）；这是偏好，不是 Assistant 副本。个人域原编辑行为保持；企业页区分“本域使用设置”与“编辑我的助手定义”，后者明确提示会影响其他空间。

用户助手的本域显式模型选择优先于定义引用；定义模型为空才使用本域角色默认。显式引用失效或被策略排除时显示未就绪，禁止按首项/名称静默替换。用户可在本域重选有效资源，不必修改其个人助手定义。

企业 Assistant 固定的 model/systemPrompt/managed MCP refs 只读，引用缺失由企业修复。未固定的用户扩展可以通过本域偏好选择，不复制完整个人 Assistant 默认值，也不覆盖受管字段。用户助手可选择本域可用的企业/用户模型和 MCP。

企业助手不持久化为一份填满个人默认值的 Assistant。配置与使用字段按下表落地：

| 字段 | 企业助手的规则 |
| --- | --- |
| id、名称、描述、enabled、modelId、systemPrompt、mcpServerIds、memorySeed | 企业定义只读；固定模型/提示词/MCP 不被本域偏好覆盖 |
| 会话 systemPrompt 覆盖 | 企业助手始终禁止；历史遗留覆盖不参与新执行，页面解释原因 |
| temperature/topP/reasoning/maxTokens、stream/context limit、message template、headers/bodies | 平台未提供这些字段，本域使用偏好可调整；初值使用当前普通助手对应默认值，不继承当前个人助手；不能改写已解析模型/认证路由或绕过资源门禁 |
| Prompt Injection、QuickMessage、Skill、Search、Local Tools、Workspace、Memory/最近聊天、时间提醒 | 本域偏好选择；初值使用普通助手对应默认值（引用集合为空），会话注入允许用户启用；Workspace 必须显式选择 |
| 标签、头像显示、背景/透明度、regex、preset messages | 本域可选使用设置，默认无标签/自定义资产/regex/preset；不改企业名称与描述，也不把个人历史变为 preset |
| 子助手授权 | 企业定义的固定引用（如来源支持）不可删；本域额外引用保存在偏好，与固定引用合成后叠加原主从授权及 allowLocalAssistants；不回写企业定义 |

在企业域通过 assistant_manage 创建用户子助手时，仍保存一份用户定义，并在同一用户配置/偏好事务把新引用加入调用者的本域授权。工具只允许更新/删除已获授权的用户定义，明确其共享影响，不能编辑企业定义；关闭 allowLocalAssistants 时创建/管理/委派用户助手均不可执行。个人域沿原主从定义引用协议。企业默认本地工具与用户默认相同，其他扩展入口可以由用户启用，不新建一套企业默认个人配置。

子助手沿原主从授权关系解析并叠加助手开关；Child 继承父域/主体，不因入域扩大原授权。Seed 与本域运行记忆分开；共享定义不得读取个人 GLOBAL_MEMORY_ID 或个人最近聊天。Preset graph/头像/背景/Skill 是用户显式配置资产，不借此导入个人真实历史。

### 2.4 企业 MCP 与 Gateway

企业 Direct MCP `enabled=true` 自动加入企业目录并启用，用户无关闭/编辑/删除入口，命令也拒绝。`allowLocalMcp` 只控制额外的用户 MCP。服务启用不等于所有助手自动取得全部工具：企业助手按固定绑定，用户助手在本域选择可用服务。

Gateway 是独立受管资源，不出现普通 MCP URL/Header/OAuth 编辑器。`REQUIRED` 强制整对工具开启；`USER_CONTROLLABLE_DEFAULT_ON` 默认开启且可成对关闭。使用偏好按 `(sourceNamespace, deploymentId, userId, toolGatewayId)` 保存，不继承同企业其他用户的选择；REQUIRED 保留旧 false 但不生效，恢复可控时恢复。变更只影响新执行。

本地 Gateway 经标准发现、固定 discover/invoke pair、surface 校验及调用流程；工具卡显示真实业务动作。业务 Catalog/发布后台不在本期，不通过客户端拼接业务工具或 Direct MCP fallback 冒充 Gateway。

## 3. 三类配置的持久化架构

### 3.1 唯一 owner

| 事实 | 本期载体与 owner |
| --- | --- |
| 用户配置 | `settings` DataStore 中版本化 `UserConfiguration`，由用户配置事务 owner 管理；Skill/资产仍归原文件 owner |
| 用户偏好 | 同 DataStore 中 `UserPreferences`，与用户配置同一次 edit 迁移/提交；含公用和按域/主体偏好 |
| 企业配置 | 独立 Applied Enterprise State；定义、policy、generation、完整性与恢复元数据整体原子提交 |
| 身份/Session/当前空间 | 企业会话 owner；source 明确为本地，不能冒充真实授权 |
| 用户/企业 MCP Catalog | 既有 McpCatalogStore；来源与稳定引用明确，不写回 Settings |
| 导航/草稿、恢复与运行数据 | 原导航、Room、文件及恢复 owner；不塞入企业配置 |

三类不是必须三个文件；用户配置与偏好保持单一事务，不制造多个 Store 长期双写。旧 Settings 只在真实迁移解码边界保留必要兼容，不保留平行可写旧 aggregate。

### 3.2 完整字段归属

| 目标 | 当前字段/内容 |
| --- | --- |
| 用户 A | `providers` 全部 Model/override/header/body/凭据；`ttsProviders/asrProviders` |
| 用户 B | `mcpServers` definition/OAuth/tool policies；`searchServices/searchCommonOptions`；本地工具配置随助手 |
| 用户 C | `assistants` 全字段、`assistantTags/modeInjections/quickMessages`、Skill；`titlePrompt/suggestionPrompt/compressPrompt` |
| 用户资料/备份 | `displaySetting.userAvatar/userNickname`、`webDavConfig/s3Config` |
| 公用外观 | `dynamicColor/themeId/customThemes/colorMode/amoledDark/appLanguage`；字号、字体/路径/名称、模糊效果 |
| 公用显示 | 加载样式、头像显示、气泡/透明度、模型图标/名称、日期、usage、思考显示/折叠、跳转器/位置、代码换行/折叠/行号、LaTeX |
| 公用交互 | 自动滚动、音量键滚动/倍率、裁剪、长文本文件/阈值、Enter、震动/音效、侧栏、搜索排序、启动新建 |
| 公用播放/通知 | `defaultTTSPlaybackSpeed`；引用/括号过滤、自动朗读、顺序播放、完成/实时通知 |
| 公用更新/提醒 | `showUpdates/updateCheckDisabledUntilEpochMillis`、备份提醒 enabled/intervalDays；不控制企业更新 |
| 按域选择 | `favoriteModels/assistantId`；chat/fast/title/imageGeneration/suggestion/attachmentInspection/compress ModelId；`enableSuggestion`；Search/TTS/ASR 选择；用户助手本域使用偏好、Gateway preference |
| 内部状态 | init、launchCount、ignoredUpdateVersion、lastBackupTime、crash 状态；pendingAssistantDeletions 保留可恢复协议并增加精确清理 scope |
| 导航/草稿 | lastConversationId、未发送输入按域/主体，由导航/草稿 owner 管理 |
| 删除无效项 | developerMode 字段/key；入口与之无关，旧备份迁移明确丢弃 |

DisplaySetting 全字段逐项迁移，不因命名而整体当纯显示。Assistant 的 regex、preset graph、request overrides、子助手 refs 等全部保全；本期不借结构整理重设用户默认值。

### 3.3 生效快照

```text
UserConfiguration + UserPreferences + Applied Enterprise State
  + requested realm / authorized principal
  → EffectiveSettingsResolver（纯解析与规则）
  → EffectiveSettingsSnapshot（只读，不额外落盘）
       → 页面 UiModel / command admission / immutable execution capture
```

快照含域/主体、来源/稳定引用、用户 revision/企业 generation、候选及原因、实际选择、可选/可编辑/强制启用、显示偏好。UI 不自行合并来源；执行不依赖 UI 当前域。

目录使用当前空间；已存在会话使用持久归属；在途操作使用捕获上下文。写命令明确指向用户定义或 scoped preference，提交时校验最新规则，落盘成功后发布。企业不允许使用某资源不等于全局禁止用户管理原配置。

## 4. 身份、数据、迁移与备份

- Realm 为 PERSONAL 或 ENTERPRISE(sourceNamespace, deploymentId)，企业用户数据另携带稳定 userId。sourceNamespace 为稳定来源身份，不是展示名；本地与生产命名空间强制不相交。同源资料更新不能自行重命名来源。数据、偏好、Catalog、缓存、资源引用和恢复均使用完整身份，不能仅凭相同 deployment/user 混用模拟与真实数据。用户定义只保存一次，来源与使用域分别表达。
- typed 配置引用保留 Local UUID 原值和 Managed `prv_/mdl_/mcp_/asd_/str_` 原字符串，不 hash/strip 为 UUID。路由、会话、Folder/Memory、缓存与历史显示一起迁移，不只改数据库列。
- 一个 AppDatabase；Conversation、Memory、Artifact、GenMedia、Folder/Favorite 等独立根保存必要 scope。MessageNode/Turn/Tool/Disclosure 从可靠外键派生，不新增平行可变真源。
- 旧数据全归 PERSONAL，原 ID/内容/排序/引用/文件不变；旧选择只进入个人偏好。Draft 首消息事务和 Child lineage 不变。新企业记录创建时确定域/主体。
- 会话与本域文件的查询/命令/FTS/统计/收藏/最近聊天/deep link/通知/子助手都校验 scope，不先全量读取再 UI 过滤。SAF 只暴露下述显式共享 Workspace，校验已注册 root 与文件访问边界，不额外建立当前域目录。
- C4 的会话入口明确区分创建 Draft 与打开既有记录；不存在的既有 ID 返回 Missing，外域 ID 返回拒绝，不能借 loadOrRegisterDraft 变成新聊天。ConversationViewLease 携带从持久 header 或已注册 Draft 捕获的原 RealmAccess；ChatVM 先取得页面授权，再订阅会话、turn、附件、收藏和活动，关闭或授权撤销时清空投影。lastConversationId 移入按域偏好，旧 SharedPreferences 值只迁入个人域，构造 ViewModel 时不再直接写全局值。
- C4 的命令授权覆盖 resident、仅 header 和完整加载三个分支，并与提交/发布保持同一顺序；新增 START 与继续用户交互沿用原授权。停止后删除分成校验并请求取消、释放锁等待任务结束、用原授权重新提交三个步骤，不能持有 Session 锁等待仍需记忆/配置授权的任务。已取得 TurnHandle 的终态收口与恢复继续使用现有 typed owner，不能新增通用跳过授权开关。Memory 等已持 Session 锁的调用使用明确的内部已授权读取边界，避免重复获取不可重入锁。
- Child 从父会话复制 scope；创建、导入和 fork 都验证父子同域。移动到 Folder 同时验证 scope 与 assistant reference；收藏必须由原会话 owner 核实节点归属，不能信任 UI 传来的快照。撤销 token、缓存和已加载 Runtime 保留原 scope 并复验授权，不能因避开 DAO 而跳过隔离。
- Workspace 为用户显式选择的共享空间，目录不随切域复制或清空，UI 标明共享。共享目录不等于内容隔离；`/upload` 及会话资源挂载必须按调用主体限制，不能暴露整个个人上传目录。Skill/字体属于选择使用的共享配置资产。
- Artifact 的数据归属与共享配置资产的读取用途分开。旧行仍归个人；头像/背景等共享配置预览由原 Artifact owner 验证持久配置 root，不能因此向其他域的工具开放原路径。配置资产进入另一域聊天时按既有 lease/创建协议生成目标域附件，不修改源行归属。GC/删除检查所有主体的持久配置 roots；不能仅看当前生效配置。聊天引用投影发现跨域 Artifact 时拒绝，不静默丢失引用。
- 用户配置与偏好一次 DataStore edit 迁移；SharedPreferences 读取在提交前，完成标记同事务，旧 key 清理幂等。跨 Room/文件升级由启动恢复协调器排序；未完成不开放新写入，重启可恢复。成功后删除旧读写路径。
- 提供显式 Room migration、fresh schema 同构和真实历史升级测试，覆盖复杂引用、tombstone、凭据和用户值。Room 版本、App 20、本地资料 schema 不混淆。
- 普通备份含用户配置、公用/个人偏好和个人数据，不含企业凭据/企业配置/企业数据。归档 payload 清单从个人闭合图和共享配置资产根产生，不扫描整份 upload/images。恢复继续使用既有 staging/swap/rollback；最终待发布图在冷启动、live Room 尚未开放时合并“最新企业图 + 备份个人图”，不能在用户点击恢复时提前冻结企业图而丢失重启前的新写入。主键或路径冲突整体验证后拒绝，不以 REPLACE 覆盖企业记录；重建验证 FTS/Artifact 引用投影并保留企业生命周期待办。
- 旧备份进入同一迁移协议，更新 manifest 明确新范围；Auto Backup/device transfer 不复制未经 scope 过滤的企业存储/共享 DB。
- 已授权企业数据保留原手工编辑、复制/分享和单文件导出能力；本期不发明“禁止复制全文”或远端 DLP。显式导出不等于跨域自动合并或整库备份。退出封存企业数据，同主体重新授权后恢复；清除走原 owner 可恢复命令。
- 删除用户助手定义对所有域后续使用生效，确认界面说明共享影响；原个人数据清理由 PERSONAL tombstone 精确执行，不按 assistantId 无域删除 Memory/Conversation。企业记录（含封存记录）保留，引用失效显示“助手已删除”，允许查看历史；继续执行必须由用户显式重选本域助手，经原会话命令修改引用，不回退当前助手。旧 tombstone 迁移为 PERSONAL，旧清理函数与 getConversationAssistant 静默回退同时退出。

## 5. 正式入口、接入与退出

### 5.1 页面与路径

| 页面 | 功能 |
| --- | --- |
| Chat 顶部/抽屉空间入口 | 当前个人空间/企业名；企业持续“本地模拟”标记，点击选择空间 |
| 空间选择 | 个人、已接入企业、接入企业；切换不注销 |
| Settings 企业接入 | 始终可见，包括离线/网页失败；不依赖 Debug/About 长按 |
| 接入页 | 体验示例企业、粘贴、扫码；提供测试资料/二维码，错误明确 |
| 企业状态 | 企业/用户、授权/配置状态、版本/最近更新、同步、工作台、切回个人、退出 |
| 工作台 | 固定 Portal 包信息/动态、Starter、相机/录音；关闭仅关闭网页 |
| 示例企业配置场景 | 本地企业服务管理：五项策略、资源变化、故障/过期/撤销/恢复，不是普通用户绕过真实 policy 的入口 |

初始/升级进入 PERSONAL，不自动接入。体验示例企业走同一接入校验和提交，不能直接赋值 READY。一键生成的资料、扫码解码结果和粘贴文本均进入 EnrollmentMaterialParser 与同一来源分流。未知、不完整、过期或错误凭据不留下半绑定。

接入资料对齐平台 Control Protocol §8 当前工作树修订；尚未 Freeze，不代表真实平台互操作通过。formatVersion=1 与 Snapshot v4/v5、完整企业配置文件版本分别独立。所有字段必填且禁止额外字段：

| kind | 字段（含共同字段） |
| --- | --- |
| PLATFORM_ENROLLMENT | formatVersion、kind、platformUrl、code、expiresAt |
| LOCAL_EXAMPLE_ENROLLMENT | formatVersion、kind、sourceNamespace、deploymentId、code、expiresAt |

- 原文最多 2048 UTF-8 字节，先计入首尾空白再去除空白；拒绝重复键（包括转义后同名）、未知字段/版本/kind、缺失/null、错误类型。旧 userId/enrollmentCode/expiresAtMillis 资料没有 fallback。
- expiresAt 是 RFC3339 UTC 字符串，接受小写 t/z 与 +00:00，小数秒仅 1–9 位；拒绝超精度、闰秒、-00:00、非 UTC 和错误日期，输出统一大写 T/Z。只用于客户端到期预检查；来源保存的到期和消费状态仍是兑换权威。code 与本地 sourceNamespace/deploymentId 各为 1–128 个 Unicode 字符，不修改其内容。
- platformUrl 最多 1024 字符，只接受 HTTPS origin，无 userinfo/query/fragment/非根 path；根 `/` 和默认端口可规范化。HTTP 只在明确启用的 loopback 开发/测试策略下接受，正式来源不启用该例外。本期解析后明确返回 platform_enrollment_not_supported，不加载本地示例、不联网、不建立身份；后续真实接入必须展示目标 origin，Discovery/API base/Enrollment 固定同源且禁止跨 origin 重定向，code 不进入 Discovery URL。
- 本地资料不携带 userId/platformUrl。身份只由已安装本地来源与票据提供，资料不能注册来源、加载脚本或指定网络地址。安装目录来自随包初值或显式原生完整文件导入；后者继续由原生文件选择器走独立 4 MiB 导入校验，不修改其身份与私有 binding 格式。
- 本地示例资料在运行时由 LocalEnrollmentAuthority 领取随机短期一次性 code，再序列化为上述格式；不打包固定可重用 code。模拟服务的摘要/到期/消费账本位于 noBackupFilesDir/local_enterprise_service，独立于客户端 Session，不进入普通备份。
- 接入锁序固定为 Session owner → LocalEnrollmentAuthority。主体冲突/正在退出在消费前拒绝；成功消费后客户端落盘失败、取消或死亡不能回滚 code，需要重新领取资料。客户端一次发布 READY 或 CONFIGURATION_PENDING；后者仅用于可信身份已验证而配置缺失/错误，不把凭据或持久化失败当作待配置成功。
- PrepareEnterpriseExampleAssets 从唯一公开完整模板派生首次安装身份。运行时先查安装目录，再只读验证票据身份，在 Session 冲突检查后重新校验并消费；之后读取来源当前配置，缺失/截断可进入待配置，无法验证安装身份则拒绝。重新接入不重读旧安装包配置，也不设置 Applied 回退分支。

平台现已提供 platform-v1.json、local-v1.json 和原始 cases.json。Android 从 api/generated/android/portal 验证摘要后固定消费副本，以固定 now/installedSources 执行真实解析、到期与来源预检查；共享 code 不作为安装来源的真实登录凭据。来源清单、摘要和验证边界见 [接入资料契约](../references/enrollment-material-contract.md)。Native/Feed 案例须在对应实际消费者接通后分别验收。

```text
个人空间 → 接入企业 → 体验示例企业（或扫码/粘贴）
→ 本地验证 → 应用完整企业配置 → 示例企业 → 选择助手并执行
```

### 5.2 状态矩阵

| 事件 | 结果 |
| --- | --- |
| 身份/配置成功 | 发布可用状态并进入企业，用户可打开工作台 |
| 身份成功但无配置/配置失败 | 已接入、配置待就绪，提供更新/退出，不假装可执行 |
| 切回个人 | 保留登录和两域选择/草稿；关闭 Portal/采集，在途生成仍归原域 |
| 重启 | 先恢复 source 再恢复企业状态；有效恢复原空间，失效回个人并保留恢复入口 |
| 模拟网络失败 | 保留绑定；企业新外部执行暂停，合法历史/本地操作可用，个人正常 |
| 访问令牌过期 | 同一 session owner single-flight 模拟刷新，成功继续 |
| 授权失效/撤销 | 撤销资格，收口企业任务/Portal，回个人，提示重新接入 |
| Portal 过期 | 只重建网页会话，不退出企业 |
| 退出接入 | 原生确认，阻断新企业动作，收口该域运行/采集，清活动绑定/凭据/企业配置/网页会话，回个人；保留封存数据 |
| 清除示例企业数据 | 独立确认，仅清该示例主体数据/导航，不删个人配置/历史/共享 Workspace |

正式退出由应用层统一编排：原生确认同时冻结企业 RealmAccess 与当前 RealmSelection，并在同一 Session 锁内复验。停留个人空间也能退出企业，不能仅凭个人 selection 推断目标 Session；旧确认不能退出新接入。授权到期/撤销按原企业 Session 收口，不要求用户仍选中企业或该授权仍有效。

先持久发布 CLOSING 撤销准入，再释放 Session 锁，调用既有主/子运行、Runtime 内标题/建议任务与媒体 owner 按原企业域取消并等待终态，最后凭原退出 token 完成退出；不得以 binding lease 为空或 Job.join 返回代替运行终态提交。流程由应用作用域持有，页面销毁或重复点击不产生第二退出操作；失败保留 CLOSING 和可重试状态，重试不复验已经被退出动作改变的页面选择。重启恢复先保留 CLOSING，完成现有 Child/主 TurnRecovery 后、恢复 gate ready 前完成原退出 token；不能在恢复任务成功前假报退出完成，也不能让恢复等待自己的 ready gate。企业状态页直接投影 Session；待配置或停留个人空间时，同步目标仍是已接入的企业 Session。当前 EnterpriseExitService 已实现 Portal 文档、同步与会话取消、终态核验、到期观察和启动恢复；正式原生入口与 Portal logout 已接通；媒体 owner 接线继续实施，各批次验收分别记录。

退出原因随 CLOSING 写入同一个 manifest，原退出 token 保留该原因；主动退出完成为 SIGNED_OUT，到期/撤销完成为 REAUTH_REQUIRED。自动触发由应用生命周期观察已接入 Session，不能依赖企业页面是否打开。Session 锁内只撤销准入，不等待运行任务或配置同步；同一原 Session 的同步由既有同步 owner 取消并等待。终态提交后的文件清理失败由存储维护收口，不把已退出状态报告成仍在 CLOSING，也不让重试误作用于新 Session。

当前 manifest 磁盘格式为版本 3；未交付原型的旧版本明确拒绝，不提供专用迁移或自动重写。当前格式正常重开保全原身份、Applied、Feed 与退出原因。到期写入 CLOSING 之前的失败单独投影为原 Session 的准入失败，可重试但不假报已接受；已接受退出的清理失败保留原 token。自动观察器和显式重试共用任务准入锁，失败后不因迟到状态通知擅自重新清理。

正常本地 source 不依赖设备互联网，“网络失败”是主动场景。source/deployment/user 是恢复身份边界；本期没有 live fallback，模拟凭据永远不能交给真实服务。网页退出调用同一个原生退出命令，网页打不开也能退出。

## 6. 全部配置界面的调整矩阵

统一标记应用偏好、我的配置、企业提供、本域使用设置。五项禁止的用户候选显示禁用及原因；原个人管理不被全局锁死。企业只读与用户偏好分开，新增/导入用户配置保存到唯一用户目录。

| 页面/字段组 | 企业域变化与必需消费者 |
| --- | --- |
| Settings 首页 | 当前空间/企业状态；配置不足依据当前域，不能要求填个人 key 代替企业资源 |
| 所有主题/偏好子页 | 共用显示/输入/播放/通知值并标作用范围；企业身份不反写个人昵称头像 |
| Provider/Model 列表详情/探测 | 来源、只读/可编辑明确；准入控制候选；探测按实际 binding；共享编辑提示跨空间影响 |
| 默认模型/收藏 | 所有模型角色限本域候选，收藏/选择独立；默认不是强制；失效明确要求重选 |
| 标题/建议/摘要/附件识别/图片生成 | 保留功能，模型受 Provider 开关和能力限制；缺配置显示原因，不能偷读个人默认；带原会话域 |
| Search | 已有服务/API Key/参数可选可编辑，不受 MCP/Provider 开关误管 |
| MCP/OAuth/工具策略 | 企业自动开启只读；用户按开关，保留原 OAuth/审批；不回写企业 token |
| TTS/ASR/聊天语音 | 各自来源/准入，System TTS 受控；HTTP-ASR 与 realtime 分开；不可用明确原因 |
| Assistant 列表详情 | 企业只读，用户按开关；企业页区分本域选择与共享定义编辑 |
| Assistant Prompt/Request/Extensions | 固定企业字段锁定，用户定义全保留；未受控扩展可选，不以“未定义”禁掉整个页面 |
| Assistant MCP/Local Tools | 企业固定 MCP 不可移除；用户选择有效服务；本地工具调用受控资源复验规则 |
| Memory/最近聊天 | Seed 只读、本域记忆可编辑；global/recent 范围限定主体 |
| 子助手引用/发现/详情 | 原主从 refs 与授权保留，叠加助手开关；Child 同域，结果用原持久协议 |
| Skill/Injection/QuickMessage/Tag | 用户配置可用；Starter 独立只读、预填等待发送，不覆盖原草稿 |
| Workspace/终端/补全 | 显式共享空间与受限会话挂载；切域不改目录或抢走 PTY owner |
| Chat 顶部/输入/选择器 | 持续空间标识；失效资源禁发送且可修复；按钮/快捷键同一命令 |
| 历史/搜索/收藏/统计/图库/文件 | 同组件查询当前域主体；通知/deep link 复验，不能泄漏另一域结果/计数 |
| Backup/WebDAV/S3/SafeMode | 范围清楚、恢复保全企业、无全库覆盖旁路 |
| About/帮助/升级 | 应用功能保留；企业入口不依赖 BuildConfig.DEBUG/developerMode |

显示偏好不改变准入。通知取原会话的 scope 和原显示规则，失效跳转不能打开封存数据，不新增远端通知内容策略。

企业模型同 ID 改名/参数更新影响下一次执行；删除/停用使选择不可用，不按名称替换。企业助手引用不闭合拒绝候选整包，保留最后完整配置并提示失败，但不绕过已知撤销/版本屏障。个人助手本域重选不改其原定义。

## 7. 本地企业服务、执行与私有资料

### 7.1 外部 I/O 可替换

```text
LocalEnterpriseSource（身份/配置/动态/场景）
→ Session / Applied State owners → realm-aware resolver
→ 正式 UI / Conversation / Turn / MCP / Speech / Portal
→ captured binding
   ├─ 企业示例：确定性服务 adapter
   ├─ 私有本地企业配置：显式真实 endpoint/credential
   └─ 用户资源：原用户 adapter/credential
```

只模拟外部 I/O，不替换 Room、配置验证、写门禁、ViewModel 或 Turn 生命周期。必须覆盖 Chat 流、图片输入示例回复、工具批次及后续回复、有效 TTS 音频、ASR 转写、Direct MCP 初始化/发现/调用、Gateway discover/invoke 和真实动作显示；不往 UI 直接插假消息。语音播放与相机/录音是真实设备行为。

用户资源仍真实执行，不伪装成 Mock 结果。私有真实 binding 显示连接来源，失败不退回假回复。零凭据安装可以完整跑内置示例，不因占位 API Key 阻塞。子助手、Search、Skill、Workspace 和辅助生成必须接同一执行链。

场景管理覆盖五项逐一收紧/恢复、Gateway policy、模型新增/改名/删除、坏 schema/引用、版本变化、缺配置、网络/身份故障、Portal 过期和重启。调整通过新 generation 和正常应用协议发布，不能直接改 UI Boolean。

### 7.2 私有配置文件

来源候选与客户端 Applied 是两个事实：LocalEnterpriseSource 在 local_enterprise_service 持久保存本地来源当前发布的配置，安装包仅负责初始值；场景编辑按来源 revision 做 CAS，不能直接改客户端 Applied。EnterpriseSessionController 继续唯一管理母 Session、Applied 和执行 binding lease。旧 updateLocalPackage、无认证的 applyPackage/registerIdentity 与“安装包旧版本时保留 Applied”的特殊接入分支均已删除；接入、原生导入和原 Session 同步使用明确入口，重启/重入均读来源当前版本。

原生同步与 Portal refresh 共用命令：捕获原主体/Session，读取来源候选，完整校验，在原 Session 内原子应用后返回。同步不得创建新 Session、续期或切域；同版本成功检查也可更新成功时间，失败保留已应用配置和上次成功时间。来源发布与客户端应用分开可观察；私有导入先完整验证再发布候选，应用失败应显示待同步。并发请求合并同步工作，取消单个等待者不回滚已提交结果。此流程仍为本地模拟，不要求真实平台网络接入。

交付完整无秘密模板 `docs/examples/enterprise.local.example.json`，根目录 `/enterprise.local.json` 加入 Git ignore。用户可修改私有文件提供真实端点/凭据；不打印秘密，不进源码/测试/提交。模板和示例不得只包含空占位资源。

本地文件使用独立 `formatVersion`，包含 source/deployment/user、完整五项 policy、资源/助手/seed/starter/gateway/动态，以及独立 `runtimeBindings`。endpoint/credential 是本地 source 私有实现，不属于平台 client-safe Managed Snapshot，不混进用户资源配置。

通过“导入本地企业配置”系统文件选择器读取，限制输入大小并验证格式、ID/引用、URL 和绑定完整性。无效文件不改变来源目录、候选或 Applied；有效文件先原子发布来源，再应用客户端，客户端失败明确显示待接入/待同步，不能回滚来源或假报已应用。按明确稳定 ID 更新，不按名称。换 source/deployment/user 要先退出再接入；同主体更新保留历史。原生完整文件可安装本地来源，短接入资料无安装权限；同 source/deployment 下由票据确定登录用户。来源目录保留 generation 与 revision；旧配置文件损坏时仅显式导入更高 generation 可修复，仍检查 revision CAS。

凭据导入后由独立受保护 binding store 持有，公开配置只含引用，不进普通备份、日志、Portal 或 crash detail。默认不把私有文件编译进 APK；用户重新导入即可更新，无须改代码/重新构建。最终交付提供不进 Git 的私有文件位置与操作说明。

整包发布由企业应用状态提交协调者负责：先暂存不可变的定义、binding revision 和必要资源，校验闭合关系；最后一次原子持久提交 manifest 指针，包含完整 source/principal、配置 generation 与 binding revision。此提交是可见状态的唯一发布点；Session owner 只有在该主体的 manifest 可恢复后才发布企业可用状态。崩溃恢复只接受完整已提交组合，未提交资料回收；旧 binding 由在途 lease 持有，最后引用释放后清理。退出先撤销 session 执行资格，再按同一恢复协议移除活动 manifest/凭据，不允许配置已换而仍调用旧凭据。

### 7.3 独立企业动态

按 Control Protocol §8/§10.16 将 Feed 从 EnterpriseConfiguration 移出：完整文件升为 v2，顶层 feedSeed 携带企业时区与动态初值；应用 manifest 使用独立 Feed 存储引用、摘要和公开 revision。配置/绑定与 Feed 复用同一原子 manifest 发布协议，Feed 的领域命令负责草稿、发布、撤回和查询，不增加第二写锁或状态流。仅编辑不可见草稿不推进公开 revision；发布/撤回改变可见表示时推进 Feed revision，配置 applied/generation 和配置同步时间不变。

Seed 只初始化尚未建立的主体 Feed。同步、重入与重复导入不能覆盖已发布/撤回状态；导入结果明确提示已有 Feed 不被 seed 替换。动态保留来源/Deployment/User 归属，退出后仍保存但不可读取，重登不重新播种。

公开 DTO、eup_UUIDv4 标识和枚举直接遵守 Client OpenAPI。查询统一处理日期、默认/范围 limit、truncated 和 publishedAt 降序，时间相同以稳定 ID 排序。日期使用企业时区日历的首日零点至末日下一日零点，覆盖 DST；ETag 包含主体、公开 revision、时区和规范化查询，start-only 包含本次解析的企业当前日期。主体/Session/document 授权先于内容及 notModified 返回；withRealmAccess 的历史数据授权不能代替 Portal 当前 selectedScope 校验。

0.0.20 是个人域/企业域的第一个入口版本。企业接入、Portal、完整配置及本地企业存储只执行本期最新契约；外部旧完整文件明确拒绝并提示使用新模板，不为分阶段开发的企业原型保留双格式解析、旧 HTML、旧桥接或专用历史迁移/归档。清理未交付的旧企业实现，以及与其绑定的临时/过时文件、旧模板、无效测试和文档；保留当前有效的共享消费契约与必要交付材料。已发布个人配置、Room 数据和个人备份的迁移与保全要求仍有效。

共享 feed-vectors.json 必须执行实际查询组件，另验证动态/配置版本独立、撤回后重入、会话撤销后的 ETag 请求及持久提交失败/重开恢复。Portal 的正式页面测试在宿主接通后执行，不以这批领域测试替代。

## 8. Portal 与手机能力

2026-09-08 上游已裁决采用 Control Protocol §8 的 **Native Bridge v3 / 本地读取 v2**。固定 https://local.measix.invalid/portal/ 只承载经过摘要校验的随包静态资源；context/Feed 通过同一个 MeasixHost 类型化消息通道读取。废止本地 GET/document header/304 方案，不保留旧 Bridge v2、CustomEvent 或另一套工作台页面。远端 Hub HTTP、Cookie、CSRF、ETag/304 协议保持独立，本期不实现真实后台接入。

core 与 Portal 的新版可执行契约和资源包均已交付，Android 已核验并固定 Bridge v3/localReadVersion=2 的原始包、manifest 和全部八份共享输入，删除旧 v2 JavaScript 与重复的消费清单。PortalProtocol、PortalDocument、PortalWebView、正式入口与原生退出已实现并有分层验证；媒体请求、原生 UI 与硬件已接线，Debug 设备验证已通过；Release 设备及整期验收仍按下文完成。不得改写固定网页脚本或把资源入包当作完整设备验收。enrollment 与 context 的 formatVersion=1 不变；已完成的接入与 Feed 领域规则继续复用。

原生在批准的顶层文档运行脚本前提供 window.MeasixPortalDocument={bridgeVersion:3,documentId}。documentId 至少具有 128 bit 随机性、非空且最多 128 字符，绑定本次文档、来源/Deployment/User、原母 Session 和期限；网页授权最多十分钟且不超过母 Session，读取不续期。不能可靠提供启动绑定或真实消息 origin/frame 能力时，明确显示宿主不可用，不降级。Android 每个批准文档独占新 WebView：先注册监听器和 document-start bootstrap，再首次加载；重开/重载先撤销旧 owner 和媒体，再创建新实例。旧实例不得加载第二份 Portal HTML，导航回调与静态主文档拦截共同拒绝；导航回调不能被当作替换当前文档启动脚本的时序保证。

请求为 {bridgeVersion:3,documentId,requestId,method,params}，params 必须是对象，信封拒绝未知字段；requestId 非空、最多 128 字符且在当前文档内唯一。使用 WebMessageListener 的 sourceOrigin/isMainFrame 逐请求验证精确可信 origin 和顶层发起 frame，并复验原文档、主体、Session、方法及参数。静态资源拦截和消息授权各负其责；iframe、外链、模型 HTML、旧文档消息不能取得权限。

每个请求保存原 JavaScriptReplyProxy，完成时再次复核原文档，只向原消息对象返回 JSON；不向当前 WebView evaluateJavascript 派发异步结果。Portal 单一 Bridge owner 在首次请求前设置 MeasixHost.onmessage。响应携带同一 bridgeVersion/documentId/requestId，result/error 严格互斥，错误保留 code 并使用不含凭据/路径/内部诊断的 message。同源导航、刷新、关闭、切域、退出、重登和期限失效均撤销旧文档、取消等待并清理迟到媒体，旧响应不能交给新页面。

本地必须支持 getLocalContext({})、listLocalUpdates({startDate?,endDate?,limit?,ifNoneMatch?})、getLocalUpdate({enterpriseUpdateId})。context 由原生真实身份生成，不使用 fixture 身份或时钟；list/detail 复用 EnterpriseFeed 的 Client DTO、日期、排序、默认 10/上限 20、truncated 和独立 revision/ETag。列表返回 {kind:"modified",etag,feed} 或 {kind:"notModified",etag}；未变化分支禁止正文，只有当前文档/主体/规范化查询的完整匹配缓存才能接受。授权先于 ETag 判断；context/detail 不缓存，列表只保留页面内存缓存。远端不声明且拒绝三个本地方法，不提供通用 HTTP 代理。缺失动态返回 enterprise_update_not_found；过期/来源拒绝进入统一网页失效清理，不冒充断网，也不自行断言母 Session 必须退出。

其他方法为 getStatus、refresh、close、logout、openExternal、capturePhoto、recordAudio、readMedia、releaseMedia、cancel，按 Control Protocol 的严格 params/result 执行，不保留旧别名。getStatus/refresh 返回真实已应用状态与实际 capabilities，未知值为 null。refresh 共用原生 EnterpriseSynchronizationService，提交完成才成功；Feed 刷新独立，不改变配置 generation 或续期。close 仅关闭工作台；logout 通过原生确认和同一退出命令，完成后销毁文档，不依赖 JS 回调来撤销授权。正式原生状态与退出入口在 Portal 不可用时仍可使用。一键、扫码、粘贴共用原生接入验证，私有完整文件仍由原生文件选择器导入。

媒体由统一原生 owner 管理，仅用于本页预览，不自动上传或保存到聊天/Artifact。照片为 JPEG、录音为 audio/mp4，recordAudio 包含原生开始/停止 UI，时长参数 1–60 秒。单项最多 10 MiB、每文档两项/合计 20 MiB、最多保留五分钟；readMedia 按不透明句柄返回最多 65536 字节的 base64 分块，不暴露 URI 或路径。普通请求十秒、采集请求 120 秒的期限由原生独立保证，页面不能靠崩溃或超时留下后台录音。释放、取消、期限、导航、切域、退出、会话失效及进程恢复都清理临时文件与迟到结果；取消未知/已结束请求无副作用，不能取消其他文档请求。

Android 媒体接线按下列所有权完成：`PortalMediaStore` 管理独占临时目录，每个文档取得独立 Session，预留时占额度，写入者停止后才发布为不可变句柄；进程恢复通过现有恢复编排清理遗留目录。`PortalNativeActions` 负责原文档的采集请求与原生 UI 交互，硬件适配器控制 Camera2/TextureView 预览和 MediaRecorder 开始/停止，UI 不接触路径或 Store。相机在应用内展示可取消预览，避免外部相机 Activity 使 Portal 进入后台后仍继续写入。媒体请求取消或文档关闭时先停止硬件并等待原保存回调，再丢弃预留文件；已发布结果的过期扫描不得删除仍在采集的文件。原生媒体清理回执需纳入现有宿主关闭屏障，完成前不能发布新选中空间；请求收尾仍在 Session 锁外等待。正式宿主按已接通的实现声明媒体 capabilities；设备验收记录需分别列出实际成功和未验证的场景。

验收分开记录：共享 v3 案例由真实 Android 消费者执行；文档启动、来源/frame、原回复代理、同源导航/旧文档拒绝以及权限/扫码/拍照/录音/句柄释放执行设备验证；生产 Portal 使用新包联调。浏览器替身、纯解析测试、资源打包和本地示例均不代表真实平台互操作或 S0.2 Freeze。原 GET/304 两项限制已由上游消息方案解决，不再列为待裁决事项。

首轮设备发现 Portal 包的 dispose 删除 WebView 原生 onmessage accessor，导致首次 connect 重建 Bridge 后无法接收原生响应。Portal 已修复为赋值清空回调并重新交付，Android 已核验并原样替换 FXzsIi 包：sourceHash 为 056e78b269453a5ec19eb5c29f4a07d02385afb05533a155010103aad3a19c02，build-identity.json 摘要为 3a45b0f297b39e07912f099bd3e469c6e99bfedc721bf3424b5660d9c0e558ec；core 契约不变。Pixel_10_Pro_Fold / Android 17 / WebView 151.0.7922.199 已通过首次 context/动态读取、页内导航、重载撤销并打开新页面、快速切域和旧同步错误不进入新文档的设备验证。生产响应始终使用原 JavaScriptReplyProxy，没有脚本补丁或替代回复通道。正式原生入口、退出和媒体接线继续，不将此问题重新列为协议裁决。

## 9. 执行、更新、恢复不变量

- 扩展 TurnContextFactory、TurnToolSetFactory、transport lease 和既有 Conversation/Turn/Step owner，不建企业运行栈。
- 新企业动作检查当前 source/session/五项准入；本地 source 在本机提供状态，无互联网也 READY。用户资源同样受企业调用前准入。
- 捕获域/主体、用户 revision、企业 generation、资源身份、endpoint/binding、prompt/工具；后续 Step 不重读全局当前选择。
- 收紧/退出与外部副作用承诺有统一线性化门禁；旧捕获在下一请求/调用前复验，不合规则终止，不换模型、不重放。已经承诺的调用保存已知结果或 unknown，不谎报未执行。
- MCP Catalog/OAuth、异步标题、自动 TTS、附件识别、子助手均带原 scope。共享用户凭据刷新只写用户 owner。
- 企业候选失败只影响企业 readiness；个人不能因企业网络/配置错被全局锁死。共享 DB 真实损坏仍按恢复协议拒绝 Ready。
- 移除旧签名 envelope/global merge/path lock 和无消费者 facade；原型文件不迁成正式身份；仅保留真实历史迁移需要的解码边界。

## 10. 完整变更清单与批次

当前进度以此表和最终验收结果为准。下方早期批次记录是当时的实现与验证快照，其中“继续实施”的入口、聊天配置、主/子模型 lease、辅助任务生命周期、Portal 退出和媒体 owner 已在后续批次接通，不重复建设；`developerMode` 已退休。

| 收口工作包 | 当前事实与剩余工作 |
| --- | --- |
| 模型消费者（C5） | 主/子、标题/建议/摘要、附件识别和图片生成已接入原域模型捕获、逐请求准入与 binding；完整工具调用示例继续实施 |
| MCP / Gateway（C5、U2） | 企业固定选择已有 UI；仍需接通原 MCP runtime/catalog/OAuth owner、企业 binding 与示例实际工具执行 |
| 语音（C5、U2） | 仍需 TTS/ASR 的企业/用户目录、私有 binding、原请求准入及本地音频/转写 adapter |
| 文件与 Workspace（C6） | 会话/记忆、目录、图片、背景、参考输入及附件/富文本出口已分域验证；Workspace 上传/终端/SAF 已完成本批实现与完整门禁；共享配置预设附件的目标域复制及 Draft/Child 交接已完成，完整门禁通过；整体 UI/版本验收仍待最终收口 |
| 个人备份（C7） | 个人 Settings 保全已实现；备份仍需个人闭合图导出、恢复合并保全最新企业图和系统备份边界，不能用 Settings 测试代表数据保全 |
| 完整示例与 UI（M1、U2） | 正式企业入口、Portal、公开模板和私有文件 ignore 已有；仍需原生整包导入/场景管理、工具批次 mock、Starter 预填及剩余资源/助手页面 |
| 退休与发行（R1、V1） | 消费者完成后删除旧 managed overlay 链，再做 E01–E12、Release/硬件验收和版本 20 交付；真实后台属于下一阶段 |


聊天配置与抽屉接线已实现：原页面 query 同时提供助手、模型目录、搜索与传输能力；字段命令复用 Session/Settings/会话 owner，删除无消费者的通用 usage 写入口。企业固定模型/MCP 不可修改，用户助手可本域重选、跟随域默认或恢复定义；失效定义不再静默回退。Workspace 目录、系统提示和附件迟到结果绑定原目标，写盘取消等待实际 ack。抽屉助手、目录、分页和筛选共用原 ConversationFolderAccess，旧目标不自动转入新空间；会话移动等待实际提交，换助手同时清 folder/cwd。此次没有增加配置存储区或持久化镜像。

两位独立复审提出的原目标、取消与最新文档写入问题已收口。Pixel_10_Pro_Fold / Android 17 的 ScopedConfigurationAndroidTest、ModelCatalogAndroidTest、EnterprisePageAndroidTest、EnterpriseAppliedStateAndroidTest 共 11 项通过，耗时 2 分 25 秒。另在实际 Debug/Koin 页面走通示例企业聊天、固定 MCP 勾选且不可关闭、重启后从抽屉重开企业历史、企业域内移动到用户助手、模型继承选项，以及切回个人后历史/模型不混用。固定 MCP 尚无已验证工具，此处不算工具执行验收。设备旧消息中的 mock 原始回显保留为历史，新的 mock 回复已去除原始请求回显。

本批最终串行 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 13 分 35 秒内通过：App 2,049 项、AI 366 项 JVM 测试无失败/跳过，lint 0 errors、284 warnings；Workspace 保留 11 项 Windows 宿主条件跳过。前置全量分别发现旧 mock 回显断言及抽屉两处 Compose 文案读取，均已修正，不通过忽略检查放行。设备业务回归发生在最后文案读取调整前，该调整由最终构建/lint 验证；另重新安装 Debug 后实际发送数字消息，确认新 mock 不回显内部请求且会话进入企业抽屉。分类证据、日志和截图见 `build/reports/enterprise/chat-configuration-verification.json`。

完整助手使用参数、额外子助手引用编辑、企业配置资产、其余 Speech/MCP/辅助生成执行、文件/备份与完整示例继续实施；本批不代表 U2、Release 设备、真实平台互操作或 0.0.20 整期完成，版本保持 0.0.19 开发基线。

聊天搜索配置的前置整理已进入代码：`Assistant.builtInSearch` 与企业同名 usage 保存选择，唯一模型派生保留原模型的其他工具；
关闭/外挂/内建搜索同时修改助手的两个开关，不再改共享 `Model.tools`。Child 借用 Caller 模型后重新按 Target 偏好解析。
旧无字段个人 JSON 继承原模型，经过生产 key 迁移与备份恢复验证；企业偏好不改变个人定义。
实际传输不支持内建搜索时，选择器显示不可用，执行捕获在 HTTP 前拒绝，保留选择。
此批不代替聊天页原域助手/模型投影、原页面 typed 配置提交及其余 U2/C5 接线；这些继续实施，版本仍为 0.0.19 开发基线。

该批 105 项 App 定向测试及 AI 模块测试通过；完整 `test assembleDebug lintDebug assembleRelease` 与定向
`ScopedConfigurationAndroidTest` 串行门禁在 13 分 38 秒内通过。App 2,049 项、AI 366 项无失败/跳过，lint 为
0 errors、280 warnings；Workspace 保留 11 项 Windows 宿主跳过。Pixel_10_Pro_Fold / Android 17 的实际 DataStore
重开验证企业搜索偏好、个人定义保全和不同用户隔离。新增测试的泛型推断及内置 Provider 断言错误修正后通过，
独立审查无剩余本批实质问题。证据见 `build/reports/enterprise/assistant-search-verification.json`；请求 JSON 测试和该设备
持久化验证不代表聊天企业 UI、Release 设备或真实平台互操作验收。

U2 的共享模型目录和默认模型设置页已接通：目录分开提供用户覆盖、有效选择和不可用原因；企业候选按来源分组，不构造个人 Provider。选择、收藏与建议开关通过原 RealmSelection 提交，清除企业覆盖只继承企业默认；旧选择命令重载和 FavoriteModelService 已删除。个人默认页保留跟随聊天/快速模型与未启用识别的区别；失效收藏可移除，弹窗等待写入成功关闭且禁止重复提交，旧选择的迟到错误不进入新域。用户图片能力按实际覆盖连接统一校验，附件识别用途规则来自同一 Resolver。企业禁止使用个人资源不会关闭个人 Provider 编辑入口。两位独立审查的问题均已收口。

本批完整 test/assembleDebug/lintDebug/assembleRelease 串行门禁在 10 分 19 秒内通过：App 2,043 项、AI 364 项无失败或跳过，lint 0 errors、280 warnings；Workspace 保留 11 项 Windows 宿主跳过。最终定向复验的 16 项 JVM 和 Pixel_10_Pro_Fold / Android 17 的 3 项设备测试在 53 秒内通过，覆盖禁选个人模型仍可导航其原 Provider、实际企业选择与持久化重开、挂起提交的重复点击拒绝和失败重试。新增 ViewModel 测试首次因测试清理先撤 Main dispatcher 而失败，修正为等待 collector 取消完成后通过。正式 Debug/Koin 手动验证示例 READY、企业默认模型页、选择及清除覆盖、切回个人后的默认模式。手动检查后的调整仅隐藏企业页不适用的个人回退说明和个人默认态的冗余重置入口，重新构建及 lint 的结果随本批报告记录。汇总见 build/reports/enterprise/model-catalog-verification.json。

本批仅接通默认模型页，Chat 的助手和使用参数、独立图片生成执行、其余 C5/C6/C7、U2/U3/M1 及版本 20 最终验收仍待完成。按域模型目录的 Provider 余额还需用真实用户 ID 接通现有余额 owner；共享用户定义编辑目录保留原余额显示。企业图片定义可选择不表示执行适配已完成；辅助模型消费者对失效引用的旧回退仍需迁入原域准入链。当前不变更版本，不以此批代替完整目标。

C5 的主聊天/子助手模型入口已接入统一配置捕获与逐请求准入：原 Runtime 持有 ModelExecutionLease，等待用户继续沿用原绑定，清理在 Session/会话锁外等待，失败保留 owner 重试。个人模型复验原 credential owner；企业模型保留原 Applied binding。子助手使用同一捕获的配置构建上下文，并在每次请求准入同步复验 Caller → Target 权限。当前示例模型可通过现有流式和非流式请求链返回模拟文本；完整工具调用示例、MCP/Speech/辅助模型/附件识别及相关 UI 尚未完成，不能视为 C5 整体验收。

私有模型连接复用四线 Provider builder，但凭据改为请求级 Fixed，不进入个人轮换缓存。补齐认证 header 唯一来源、模型回退/路由参数拒绝、Responses 系统提示保护、HTTP 日志隔离及跨 origin 重定向拒绝。子助手在准备中及请求进行中撤权，父工具与子执行保存同一取消原因；执行资源清理失败保留原 owner，成功回收后可正常闲置移除。两项独立复审无剩余实质问题。

此模型批次最终完整 test/assembleDebug/lintDebug/assembleRelease 串行门禁在 8 分 59 秒内通过：App 2,037 项、AI 364 项 JVM 测试无失败或跳过，lint 无错误；Workspace 保留 11 项 Windows 宿主跳过。Pixel_10_Pro_Fold / Android 17 上实际 Room 的取消、审批继续及企业 Applied 存储共 7 项设备回归在 38 秒内通过。Debug 正式入口可接入示例并显示 READY，但聊天页仍用个人配置查询助手，显示回退为默认助手，助手/模型选择器尚待 U2 接通；不计作企业聊天 UI 端到端通过。证据见 build/reports/enterprise/model-execution-verification.json。当前本地模型是确定性文本/图片收取模拟响应，完整工具批次、其余资源消费者、Release 设备/扫码及 0.0.20 整期验收继续实施，不宣称真实平台互操作完成。

此绑定批次 EnterpriseSessionControllerTest 的 18 项 JVM 测试通过，独立审查无阻塞项；完整 test/assembleDebug/lintDebug/assembleRelease 串行门禁在 10 分 26 秒内通过，App 2,024 项无失败或跳过，lint 0 errors、280 warnings，Workspace 仍有 11 项 Windows 宿主跳过。Pixel_10_Pro_Fold / Android 17 上既有 EnterpriseAppliedStateAndroidTest 的 4 项设备回归在 39 秒内通过。清理失败测试在删除开始前注入，不代表文件部分删除恢复已经验收；此次没有新增设备失败场景。证据见 build/reports/enterprise/binding-session-verification.json；本批不代表 C5 或 0.0.20 整体完成。

Portal 原生媒体批次已完成：正式宿主接通相机、录音、分块读取和释放；文件独占 noBackupFilesDir/portal_media，启动恢复清除遗留。原生取消覆盖硬件结束后仍未交付的结果，回复失败补偿未交付句柄，取消读取不删除已交付结果。网页、硬件和文件清理共同控制原宿主关闭屏障，失败保留原 owner 供重试。设备发现 CameraDevice 关闭会截断采集序列回调，现以原设备 onClosed 确认其 Session 失效，不再永久等待被截断的 Session 回调；迟到回调不能恢复所有权。两位独立审查已复核。

本批 37 项定向 JVM 测试通过；完整 test/assembleDebug/lintDebug/assembleRelease 串行门禁在 9 分 6 秒内通过：App 2,020 项无失败或跳过，lint 0 errors、280 warnings；Workspace 仍有 11 项 Windows 宿主跳过。Pixel_10_Pro_Fold / Android 17 的 Portal、采集硬件、企业页面及 Applied 存储 17 项设备回归在 2 分 26 秒内全部通过，包含实际网页照片/音频预览和释放、取消、录音中切域、录音限时自动停止及再次录音、相机打开期间关闭。正式 Debug/Koin 流程手动验证系统相机权限拒绝与重新授权、照片方向及页面预览、麦克风授权，以及录音中退后台后硬件停止、文件清空、返回原生 READY 状态且保留登录。证据汇总为 build/reports/enterprise/portal-capture-verification.json。前置失败记录包含 Compose 等待方式修正与上述真实设备关闭缺陷，不计作成功验收。Release 硬件、实际相机扫码、剩余资源消费者/配置 UI/文件与备份、私有导入及 0.0.20 整期验收仍未完成；本批不代表真实平台互操作，版本保持 0.0.19 开发基线。

Portal v3 读取与文档宿主已实现：固定 origin 只加载已校验静态资源，context/Feed 使用原生消息；原 RealmSelection、母 Session、文档期限及原回复代理贯穿执行和回复。七项已实现能力按真实状态声明，配置同步复用既有应用服务，Feed 缓存命中仍先验证授权。完整八份共享输入与原始 manifest 统一固定，删除旧资源和重复消费清单。独立审查无剩余本批阻塞项。

该批 80 项定向 JVM 测试通过，覆盖接入/Feed 回归、共享请求解析、原始重复键、授权撤销、取消与超时。Pixel_10_Pro_Fold / Android 17 的 PortalWebViewAndroidTest 三项及 EnterpriseFeedAndroidTest 一项在 47 秒内全部通过；先前一次运行因测试返回类型不符合 JUnit 要求而在场景开始前失败，修正测试声明后通过。设备日志为 build/reports/enterprise/portal-v3-device-final.log。

最终串行 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 7 分 18 秒内通过：App 1,964 项 JVM 无失败或跳过，lint 为 0 errors、279 warnings、5 hints；Workspace 45 项中 11 项 Windows 宿主测试仍跳过。Debug/Release 六份 APK 的 Portal identity 和全部网页资源均与固定输入逐项摘要一致。汇总为 build/reports/enterprise/portal-v3-verification.json。首次全量运行因 C 盘空间不足未完成 Release，本次改用 D 盘任务缓存与临时目录后通过，未修改项目构建约定。正式页面、logout、相机/录音和真实平台互操作尚未验收，版本仍为 0.0.19 开发基线。

下列记录保留各已提交批次的验证范围；其中当时尚未实现的事项，以本文最新条目和实际代码为准。

C4 的主聊天 START/继续交互已接入原页面与 Session：请求接受时交接 AppScope worker 和输入附件保护，等待前任收口期间不持有 Session 锁，退出重登拒绝旧请求。START/CONTINUE 提交与 committer 接收在同一取消安全边界；继续沿用原 TurnContext/TurnHandle。停止与替换冻结原 worker，前任终态写入失败保留其 owner，可显式重试。附件提交使用原 Artifact owner 的独立保留引用，快速重复发送不因首个请求取消丢失输入。UI 仅在实际接受工具回答后推进交互。

本批独立审查通过。最终串行 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 10 分 26 秒内通过，App 1,953 项 JVM 测试无失败或跳过，Debug/Release 构建成功；Workspace 保留 11 项 Windows 宿主跳过。Pixel_10_Pro_Fold / Android 17 的 ArtifactUploadImageReadIntegrationTest、AskUserSubmissionTest、ConversationRepositoryTreeIntegrationTest 共 19 项设备测试在 48 秒内通过。一次前置设备运行被外部中止，未执行业务测试，最终重跑通过。该证据不代表 Portal、正式企业入口或完整域执行验收，版本仍为 0.0.19 开发基线。

C4 的子助手回答已接通原页面、Master 与执行 Session：UI 回调保存原页面目标，命令复用既有选择和根会话锁；pending 仍归 SubAssistantRunGate，等待 Job 取消即拒绝新回答，finally 精确清理该登记并覆盖 metadata 发布失败。退出重登不复活旧 pending；同一 Session 切域往返后，新页面可继续回答原后台运行。已删除 TurnService 和 SubAssistantRunCoordinator 的裸 ID 回答转发。UI 等待接收结果，失败保留重试，页面移除取消未接收的提交；接收不冒充后续持久化成功。本批无持久化结构变化。最终定向回归 70 项通过；完整 test/assembleDebug/lintDebug/assembleRelease 串行门禁在 6 分 2 秒内通过：App 1,941 项无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Workspace 保留 11 项 Windows 环境跳过。汇总见 app/build/reports/enterprise/child-answer-full-gate.json。Pixel_10_Pro_Fold / Android 17 的 AskUserSubmissionTest 三项 Compose 设备测试在 25 秒内通过，验证接收前等待、拒绝重试、页面移除取消及相同问题的新交互清空答案。首次设备运行因 APK 安装服务错误未执行任何测试，重启模拟器和 ADB 后重跑通过。独立审查发现的等待方取消窗口已修复并复核；恢复清理也覆盖同步 finally 重入。该证据不代替 Portal、扫码、媒体、真实平台互操作或 0.0.20 整期验收，版本保持 0.0.19。主聊天 START/继续仍需统一处理请求接受、原 Session 执行、附件保护交接、完整树锁及审批提交后的 continuation 安装，不以本次子助手回答授权代替。

C4 的普通会话命令已接入原页面/目录授权：手工标题与提示词、注入、Workspace 路径、助手移动、置顶、消息编辑/选择、停止、删除/撤销及分支克隆不再接受 UI 裸 ID。等待 Settings/标题/会话锁后复查原页面，快速切域往返不能恢复旧操作。停止捕获具体 worker 后在锁外等待，取消仍完成已取得的清理，失败保留终态 owner；树操作在等待后重新授权并锁定完整 lineage。撤销保留原选择与完整父子树，只领取一次，恢复中的 discard 不提前释放附件保留权，ID 冲突不覆盖。历史批量删除固定用户确认的集合。没有 Room/DataStore schema 变化。START/继续、模型生成标题/压缩、文件 scope、正式入口和 Portal 继续按整期范围推进，版本保持 0.0.19。最终定向回归 133 项通过，包含真实父子执行链中 Child 终态提交失败后保留原 worker。独立审查发现的页面排队关闭与 producer finally 提前释放问题均已修复并复核；默认 cleanup 以同 turn stream 是否关闭判断，不依赖显示 phase。Pixel_10_Pro_Fold / Android 17 的 ConversationRepositoryTreeIntegrationTest 九项设备测试在 7 分 51 秒内通过，包括真实企业父子树删除/Runtime 驱逐、恢复与 Artifact 引用保全。最终完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 9 分 53 秒内通过：App 1,932 项无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 构建成功；Workspace 仍有 11 项 Windows 环境跳过。前置全量发现静态扫描把应用层 ConversationCommandTarget 误判为内部命令，现按准确类型名保持依赖禁令，最终全量通过。JVM 汇总保存在 `app/build/reports/enterprise/conversation-command-full-gate.json`。本批不代表 START/继续、完整域执行、Compose 交互、Portal/扫码/媒体或 0.0.20 整期验收。

C4 的文件夹命令已接入原选择授权：目录和分页使用 RealmSelection，保存原 Session 与选择版本；快速切域往返后旧行、旧弹窗均不可恢复授权，新目录不能给旧行补发权限。创建显式落在原域并复核助手准入；移动验证根会话、主体、助手和两端选择，删除在完整成员锁内先检查在途 turn，失败保留文件夹供重试。UI 不再在提交时重读当前助手，也不提前假报删除成功。未改变 Room/DataStore schema；普通会话命令、START/继续及退出收口仍待完成。本批 22 项定向 JVM 测试通过；完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 10 分 39 秒内通过，App 1,917 项无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Workspace 的 11 项 Windows 跳过项仍保留。Pixel_10_Pro_Fold / Android 17 上的 ConfigurationScopePersistenceTest 两项 instrumentation 在 4 分 59 秒内通过，包含真实 FolderRepository 创建、跨主体目录过滤、数据库重开及重命名/删除保全。独立审查已关闭新目录给旧分页行重新授权的缺口；真实 Pager 测试验证旧源全部失效及行的选择版本变化，不依赖内部重建次数。完整 JVM 汇总保存在 `app/build/reports/enterprise/folder-access-full-gate.json`。这些结果不代替 Compose 文件夹交互、Portal/扫码/媒体或完整企业域验收，版本保持 0.0.19。

C4 的聊天页面打开已接入原域授权：可序列化请求明确区分 NewDraft/OpenExisting，先验证选中主体与 Session，再在会话锁内检查 header，已有聊天缺失不回退新建。页面在 lease 成功后才订阅投影、收藏、错误和创建导入作用域；退出重登或切域使旧 lease 失效。Session owner 的进程内 selectionRevision 防止快速切出再切回被 StateFlow 合并而复活页面，不推进配置或 Feed 版本。Draft 首消息提交后保持原 Runtime/导航项，恢复不重放 preset 或分享输入。页面回收同时清理未提交输入，重试不能复用旧导入 owner 的附件。

启动、通知、历史、搜索、收藏、分享与助手切换已使用明确请求；不可用页提供重试和新建按钮。最近聊天归 ScopedUserPreferences，旧 SharedPreferences 键在 DataStore 成功提交后一次性清理并归个人域；空 Draft 不保存最近 ID，个人设置更新保全企业偏好。普通按 ID 命令、START/继续、文件授权及正式企业入口仍按 C4/C6/U1 继续，不以页面检查代替执行授权。

该页面批次 74 项定向 JVM 回归通过；Pixel_10_Pro_Fold / Android 17 的 5 项 DataStore/SharedPreferences 迁移与域偏好测试在 6 分 11 秒内通过，验证失败重试、重开、个人历史归属与企业值保全。审查发现的重复导入、重试残留附件、查询撤销异常及快速切域通知合并问题均已修正并补充测试。页面批次首次完整门禁在 9 分 52 秒内通过；随后启动检查发现的问题及最终变更验证见下段，尚未计作完整 C4、Portal 或 0.0.20 验收。

设备启动检查发现 Debug 在 Application 的 eager 依赖构造阶段发生 ANR。恢复链现由 IO dispatcher 执行，助手清理依赖在原恢复步骤首次解析同一 singleton；MCP 的共享 Ktor client 在首次真实连接任务中初始化，前台观察者仍在 Main 注册。独立审查及恢复/MCP 定向测试通过。该模拟器上未预编译的 Debug 冷启动仍未通过，最新堆栈停在 Settings 构造；执行设备 `cmd package compile -m speed -f` 后，Debug 在约 21 秒内打开缺失会话页，并实际通过“新聊天”进入空 Draft。先前 Release 构建的对应页面流程通过；安装最终 Release 后首次启动超时，退出 Activity 后 warm 重开约 10 秒进入缺失会话页，并实际通过“新聊天”进入空 Draft。最终产物的冷启动稳定性仍需继续排查，不能把预编译或 warm 页面验证表述为原始冷启动验收通过。

本批最终完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 9 分 52 秒内通过：App 1,908 项 JVM 测试无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 构建成功。两次前置全量运行暴露子助手测试对 Function1 使用 relaxed proxy 的失败，改为显式 processingReporter lambda 后通过，未修改子助手生产行为；清理无调用测试 stub 后，21 项子助手/恢复/MCP 工厂定向测试再次通过。Workspace 45 项中 11 项 Windows 宿主测试仍跳过。本批不构成正式企业入口、Portal、完整域执行或 0.0.20 验收，版本维持 0.0.19。

C4 的目录查询隔离已进入代码：列表/最近聊天/置顶/文件夹/分页/FTS/统计在 SQL 内过滤完整主体，FTS 在排序与限额前排除外域及 Child；消息/Token 沿用既有主子统计口径。ConversationQueryService 从 Session owner 派生选中域订阅，实际 Pager 的每次惰性加载验证原选择/Session，切域和停止订阅失效旧 source。Search/Stats 清除旧结果；抽屉持续观察文件夹，域变化清除筛选和滚动位置。助手查询工具沿用原 RealmAccess，不随全局选中域或重新接入更换身份。没有 schema 变化或第二搜索投影。按 ID 的页面 lease、Draft/Open、命令及文件授权继续作为 C4/C6 后续工作，本批不构成完整域访问验收。

该查询批次 33 项定向 JVM 测试通过，覆盖实际 Pager、旧工具 Session、到期及取消。Pixel_10_Pro_Fold / Android 17 的 7 项定向 instrumentation 在 3 分 18 秒内通过，包含生产数据库工厂的 Requery/simple/Jieba 中文搜索、完整主体过滤、限额前过滤和实际 Room 统计。两轮独立审查无剩余本批阻塞项。完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 9 分 2 秒内通过：App 1,892 项 JVM 测试无失败或跳过，其中新增统计页用例验证切域取消及失败清空；lint 为 0 errors、272 warnings、5 hints，Debug/Release 均构建成功。Workspace 45 项中 11 项 Windows 宿主测试仍跳过。版本仍为 0.0.19 开发基线，正式入口、Portal 与完整 0.0.20 交付继续实施。

Gateway 使用偏好已移入既有 ScopedUserPreferences，按完整主体保存，删除无 userId 的顶层原型字段；保留已发布个人配置迁移协议。EnterpriseGateway 删除第二个 enabled 位，对齐定义存在即发布、撤销通过候选移除的语义。Resolver 统一派生完整工具对的生效开关与可切换性，REQUIRED 保留但不使用原 false，恢复可控后恢复原偏好。配置应用命令只接受捕获的 RealmAccess.Enterprise，同主体重登后旧页面不能继续修改使用偏好。该批 Gateway 执行消费者与 UI 尚未接通，不代表 Gateway 运行功能已验收。

该偏好批次 39 项定向 JVM 测试通过；新增断言首次误用包含引用相等对象的整文档 equals，改为对比完整序列化内容后通过，未修改产品逻辑规避。Pixel_10_Pro_Fold / Android 17 的 ScopedConfigurationAndroidTest 在 2 分 27 秒内通过，使用实际 DataStore/企业存储重开验证偏好保留、用户隔离与个人配置保全。完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 8 分 13 秒内通过：App 1,885 项 JVM 测试无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 均构建成功。Workspace 45 项测试中 11 项 Windows 宿主跳过，不算相应能力验收。独立审查无剩余本批阻塞项；版本仍为 0.0.19 开发基线，当时 Portal 读取协议尚待确认（现已按 §8 完成裁决）；会话隔离、执行消费者、正式页面和完整 0.0.20 交付继续实施。

本地来源候选与统一同步已实现：安装目录独立保存来源/Deployment/User 身份、generation 和内容摘要引用，随包文件仅初始化；场景发布使用来源 revision CAS，不直接改客户端 Applied。原生完整文件可显式安装其他本地主体，短资料只查询已安装来源，由票据确定用户，消费前检查 Session 冲突。EnterpriseSynchronizationService 合并同原 Session 的同步，失败保留 Applied 和成功时间，同版本成功检查复用 Applied revision，提交期间到期不创建新 Session。有效来源发布后客户端失败返回待接入/待同步；来源文件损坏可显式导入更高 generation 修复。旧 updateLocalPackage 与安装包/Applied 回退分支已删除，未新增客户端 enterprise_local 配置区。

此来源批次最终 67 项定向 JVM 测试通过，覆盖来源与 Applied 分开观察、重开、多主体票据、损坏修复、CAS、提交失败/取消、并发等待、退出后迟到结果和提交期间到期。首次二维码库测试在生成图识别阶段出现 NotFoundException，改为对无畸变生成图使用 PURE_BARCODE 后通过；仍执行真实二维码解码及接入，不算相机扫码设备验收。退休旧入口后的首次定向运行暴露记忆测试混用虚拟调度与真实时钟，修正测试收集器执行上下文后通过；未延长超时或修改产品到期逻辑。Pixel_10_Pro_Fold / Android 17 的 11 项定向 instrumentation 在 2 分 14 秒内通过，验证实际 AtomicFile 来源发布、重开、同步、退出重入、多用户安装，以及 Applied/Feed、域偏好和 Room 记忆回归。独立审查无剩余来源/同步阻塞项；正式 UI、Portal 文档授权与媒体流程尚未接通。

该来源批次最终完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 7 分 57 秒内通过：App 1,881 项 JVM 测试无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 均构建成功。Workspace 的 Windows 宿主跳过项仍不算验收。保留既有记忆/数据隔离改造，未修改其他仓库；版本仍为 0.0.19 开发基线，不代表 0.0.20、正式扫码、Portal/媒体设备验收或真实平台互操作完成。

C6 的运行记忆已按域接通：MemoryAddress 固定 scope 与共享/助手 owner；MemoryService 统一编排原 Session、当前配置权限和 UI 记录上下文，Repository 持有真实 Room 事务直到提交或回滚结束。主助手、子助手、assistant_inspect、记忆编辑页和工具结果删除均使用原域；退出重登不能复活旧操作，共享模式切换不会把旧编辑写到新 namespace。企业 Seed 配置与运行记忆仍分开，没有新增配置副本或修改 schema。独立审查发现的旧查询终止订阅、子助手重取 Session、工具卡旧来源及授权拒绝后 lease 未释放均已修复并补充验证。

该记忆批次 87 项定向 JVM 测试全部通过；Pixel_10_Pro_Fold / Android 17 的 9 项定向 instrumentation 全部通过，涵盖实际 Room 的多主体/owner 隔离、错误地址拒绝、提交前取消回滚、提交决定后授权锁保持，以及域偏好和主子会话交互回归。删除了以假 DAO 重复 SQL 行为的旧 MemoryRepositoryOwnershipTest，替换为真实 Room 验证。会话列表/命令、文件、备份、正式企业入口和全部执行 adapters 仍未完成，本项不代表 C4、C5、C6 或 E08 整体验收通过。

该记忆批次完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 11 分 38 秒内通过：App 1,820 项 JVM 测试无失败或跳过，lint 无错误，Debug/Release 均构建成功；Workspace 的 11 项 Windows 宿主测试仍跳过。独立审查无剩余阻塞项。此批不修改版本号，不作为 Release 企业页面或真实平台互操作验收。

Feed 领域与持久发布已接通：完整文件和企业 manifest 使用 v2，配置内 Feed/HTML 与旧桥接示例删除；Feed 以独立主体引用、公开 revision 和内容摘要发布，草稿/发布/撤回及日期查询共用 EnterpriseFeed 规则。共享 feed-vectors.json 已执行实际查询，覆盖双边/单边日期、限额、空结果、错误范围和 DST 23 小时日；来源摘要现统一固定在 contracts/portal/manifest.json。独立审查发现的新 Feed 指针提交前缺少内容校验已修复，并覆盖文件损坏、取消前后发布和重开。Pixel_10_Pro_Fold / Android 17 的 6 项定向设备测试通过，包含相同动态 ID 跨来源/用户隔离、退出重入保留撤回状态和旧 Session 拒绝。该批不代表 Portal 文档与页面验收；本地 304 方案现已由 §8 的消息读取替代。

Portal 首次资源打包批次将 dist-local 原样固定到 app/src/main/enterprisePortal；该历史包已被 §8 的 v3 修复包替换，以当前 build-identity.json 为准。构建任务为全部 variant 校验并生成 assets，不依赖 sibling checkout。实际 Gradle 正例通过；篡改 index.html 后被摘要校验拒绝，随后精确恢复原始字节。独立审查无资源打包阻塞项；WebView、Bridge 和手机能力尚未实现。

Feed 与 Portal 资源批次完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 10 分 44 秒内通过：App 1,873 项 JVM 测试无失败或跳过，lint 无错误。逐个检查三种 ABI 组合的 Debug/Release 共六份 APK，Portal identity、全部资源及新版公开示例均与固定输入摘要一致。Workspace 的 Windows 宿主跳过项不算已验收；APK 内容校验不代表 WebView、Bridge 或媒体生命周期设备验收。版本仍为 0.0.19 开发基线。

接入时间精度与共享案例已完成第二次对齐：接受 +00:00 与小写 t/z，拒绝超过 9 位小数及闰秒，不截断输入；三份 core 导出输入原样固定并校验摘要。EnrollmentMaterialParserTest、EnrollmentSharedCasesTest、LocalEnterpriseSourceTest 共 58 项定向测试通过，其中 34 项直接消费原始共享案例。没有独立的 LocalEnrollmentAuthorityTest；票据真实消费、并发、取消、重开与失败覆盖归 LocalEnterpriseSourceTest。独立审查无阻塞项。Native/Feed 消费、正式页面和真实平台互操作仍待完成。

MCP Catalog 修复已纳入本期：初始化、目录提交/恢复及投影发布由同一 owner 排序，坏目录不冒充空备份；有效整包恢复可修复目录读取失败。持久提交取得所有权后等待写盘确认，Runtime 在取消前完成接收或精确补偿，取消/超时不能把已接收的新目录回退为旧状态。五组 MCP 定向测试共 62 项通过。Pixel_10_Pro_Fold / Android 17 上，McpCatalogPersistenceTest 和 LocalEnrollmentAndroidTest 的 3 项设备测试通过，分别验证实际 DataStore 重开/恢复/删除，以及接入消费重开和配置缺失待就绪。设备命令限定 :app:connectedDebugAndroidTest；首次根任务错误地向其他模块传递 App 类过滤条件而失败，不属于业务场景失败。

上述 MCP 与接入时间/共享案例批次的完整 test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache 在 10 分 52 秒内通过：App 1,865 项 JVM 测试无失败或跳过，lint 无错误，Debug/Release 均构建成功。Workspace 的 Windows 宿主跳过项不算已验收。当前 APK 仍是 0.0.19 开发基线；本批不代表 0.0.20 正式企业入口、Portal/媒体或真实平台互操作完成。

接入资料协议修正已进入代码：两种 kind 的严格解析、平台明确分流、独立安装身份目录、一次性 code 账本和单次客户端状态发布。35 项定向 JVM 测试通过，覆盖平台共享正例、一键/粘贴/二维码库解码的统一接入、UTF-8 边界、原始重复键、旧格式拒绝、固定时钟到期、来源/凭据/消费、并发与取消、提交失败和缺配置待就绪。Pixel_10_Pro_Fold / Android 17 的 6 项定向 instrumentation 通过，其中 2 项新增测试验证随包身份与完整模板一致、真实 AtomicFile 消费重开以及配置缺失后的持久待配置状态；其余 4 项验证已有企业状态和域偏好回归。独立审查无剩余阻塞项。该证据不包含正式扫码/粘贴页面、相机扫码或真实平台互操作。

该接入批次完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 13 分 20 秒内通过：App 1,822 项 JVM 测试无失败或跳过，lint 无错误，Debug/Release 均构建成功；Workspace 的 11 项 Windows 宿主测试仍跳过。协议修正没有改变完整企业配置文件格式、版本号或本期其余功能的完成状态。

C2 的域身份持久化已进入代码：Room schema 12 给会话、记忆、Artifact、生成媒体、文件夹、收藏追加 ConfigurationScope，旧记录按个人域保留；所有已修改的会话/文件夹映射保留主体，主子会话创建与树导入拒绝跨域关系。独立审查发现的 Draft 首消息落盘丢失 scope 已修复并增加回归测试。Pixel_10_Pro_Fold / Android 17 的 12 项定向 instrumentation 通过，覆盖旧六表内容/引用保全、失败迁移回滚与重试、数据库重开后的来源/部署/用户保全、不同主体主子关系拒绝及原事务回归。查询授权、记忆工具、文件访问、备份和正式入口尚未全面按域接通，此项不代表 C2 或 C4 完成。

此批完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 通过；App 1,801 项 JVM 测试无失败，Workspace 仍有 11 项 Windows 宿主环境跳过。首次使用配置缓存的门禁在 AGP 分 ABI Debug 打包属性读取处失败，关闭配置缓存重跑后完整通过；未通过修改产品逻辑规避。上述设备证据仅覆盖该持久化批次，不代表正式企业页面、扫码或真实平台互操作验收。

D0 已由 `c2745a1d` 独立提交完成。C1/C2 正在实施：用户配置/偏好文档及旧键迁移、跨模块配置引用已进入代码；正式企业 source、域数据隔离、UI 与完整模拟能力尚未完成。下表保留全部交付范围，单项基础测试通过不代表 C1/C2 或整期完成。

C3/M1 的本地资料与持久状态基础已进入代码：完整公开示例、短接入资料校验、整包/绑定原子提交、会话切换/退出/损坏恢复、按 revision 更新及在途绑定 lease。19 项定向 JVM 测试和 3 项模拟器持久化测试通过，独立审查所发现的提交失败误发布、损坏配置无法退出、离线编辑误恢复在线已修复并复核。该基础批次完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 通过，lint 无错误；两种 APK 的示例内容与公开模板一致且未打包私有文件。

当前配置批次已接入 DI/启动恢复，增加按主体的 ConfigurationResolver/application/query ports、资源选择与助手使用偏好写入。用户定义保留单份；失效引用不静默清理，新增选择校验五项准入及模型类型，重复模型 ID 明确不可用。独立审查发现的 DataStore actor 写盘取消竞态已修复：取得提交所有权后等待最终 ack，策略更新/退出不能抢在未完成的偏好提交之前。真实 Serializer 写盘暂停/失败测试及提交前取消测试通过，最终审查无剩余阻塞项。

该批次完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 通过，App 1,798 项 JVM 测试无失败、lint 无错误；Windows 环境下 Workspace 的 11 项 HostShellRunner JVM 测试跳过，不作宿主 shell 行为验收。Pixel_10_Pro_Fold / Android 17 模拟器的 6 项定向 instrumentation 全部通过，覆盖两个存储重开后的企业选择、个人定义/凭据保全、不同用户隔离、企业原子提交/退出及旧配置迁移。正式页面、域数据隔离及执行 adapters 尚未接通，旧 Managed 原型尚未退休，不计作 C1、C3、U1 或 M1 完成，版本仍为开发基线 0.0.19。

配置基础变更的已执行验证（不替代 E01–E12 整期验收）：

- `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 通过；lint 无错误。
- `connectedDebugAndroidTest --no-parallel --max-workers=1` 通过；App 112 项全部通过，包含旧 DataStore 迁移提交/拒绝后的实际文件重开、企业配置引用的 Room 往返、真实 Compose 列表选择与状态恢复。
- Workspace `hardLinkedWriteTargetCannotModifyAnotherPath` 因设备策略不允许建立硬链接 fixture 而跳过，不能视为该场景已验证。
- 模拟器实际打开 Debug 的助手选择器、提供商/搜索列表及 Bing 详情；R8 Release 冷启动与助手列表正常。当前构建仍为开发过程中的 0.0.19 基线，尚非 0.0.20 企业域交付。


辅助生成收口已通过阶段验证：标题、建议和手动摘要由原 Runtime 登记并持有原 Session；停止与助手删除纳入其取消/等待。手动摘要取消等待真实 worker 完成，包含登记成功但准入尚未返回时的取消；提交复验原树，并将 Master 摘要、建议清空及 Child retention 合为一次事务。独立审查无剩余实质问题。完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 9 分 18 秒内通过，App 1,973 项 JVM 测试无失败/跳过，lint 0 错误；Workspace 仍有 11 项 Windows 宿主环境跳过。Pixel_10_Pro_Fold / Android 17 的整组 10 项真实 Room 测试通过，新增用例验证 Child 删除失败时 Master、收藏、模型上下文、Artifact 引用和 FTS 全部回滚，以及成功重试后的清理。报告位于 `build/reports/enterprise/auxiliary-ownership-verification.json`。正式企业退出编排、后台资源按原域解析、入口与媒体接线仍待完成；本段不代表 C3/C4/C5 或 0.0.20 整期已验收。

企业退出编排已通过阶段验证：EnterpriseExitService 持有原 Session 的退出任务，统一取消同步、主/子 Runtime 与辅助生成，核验运行终态后完成退出；退出原因和 CLOSING 在 manifest 中持久保存，当前仅接受 manifest 版本 3，未交付原型旧格式明确拒绝，不自动重写。清理失败保留原 token，自动到期准入写盘失败单独可见；迟到状态通知不能擅自重试失败任务。真实主/子执行测试覆盖 Child 创建、父 link 提交、Child START 提交及子终态失败重试，验证退出后无残留运行 lease、未完成事实或无关联的 Child。独立复审无剩余实质问题。最终完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 6 分 8 秒内通过，App 1,989 项 JVM 测试无失败/跳过，lint 0 错误；Workspace 的 11 项 Windows 宿主环境跳过保持不变。Pixel_10_Pro_Fold / Android 17 的 18 项配置持久化、真实 Room 与 Portal WebView 测试全部通过；Portal 用例改为等待同步按钮实际可用后点击，已重新验证原页面迟到回复不会进入替换页面。报告位于 `build/reports/enterprise/exit-verification.json`。正式入口、Portal logout、媒体 owner 与运行资源 adapters 尚未全部接通，本段不代表 C3/C5/U1 或 0.0.20 整期验收完成。

Portal 关闭屏障已接入企业退出：文档在原 Session 授权锁内登记，CLOSING 后不再接收新文档；退出在锁外同时收口网页、同步和会话，全部完成后才清活动绑定。关闭先撤权，外部 owner 等待原请求和宿主清理；WebView 清理失败可重试，销毁后的实例不再调用旧方法，界面通知异常不阻断退出。测试覆盖多文档失败等待、旧 Session 不关闭新登录文档、创建交接取消与补偿失败、同步清理失败仍等待会话。独立复审无剩余实质问题。本批同时删除未交付企业 manifest 原型迁移，仅接受当前版本，个人已发布数据迁移保持原协议。

本批最终完整门禁通过，App 1,993 项测试无失败/跳过，lint 0 错误；Workspace 保留 11 项 Windows 环境跳过。Pixel_10_Pro_Fold / Android 17 上 3 项实际 Portal WebView 与 4 项企业持久化设备测试全部通过，包含关闭通知在旧 view 脱离后发出、迟到回复隔离和当前格式重开。一次 Debug 增量打包属性缺失在重试后恢复，随后完整门禁重新通过。汇总与原始日志见 `build/reports/enterprise/portal-lifecycle-verification.json`。正式空间 UI、Portal logout、媒体与运行资源 adapters 继续实施，不以本批结果代替 E02/E06/E10/E12 或 0.0.20 整期验收。

正式切域已使用发布前关闭屏障：冻结原 RealmSelection 与目标 Session，Session 锁内阻止旧文档新准入，确认宿主及浏览状态清理后才提交新空间；原请求在锁外等待。WebStorageCompat.deleteBrowsingDataForSite 的系统回调确认 Cookie、缓存及站点存储清理，超时只结束等待，重试复用同一不可取消的系统操作。Registry 阻止旧清理完成前打开新宿主。关闭或写盘失败保持原空间，已撤销文档不复活；切域不退出登录、不停止原域生成。此前 7 项设备证据仍只属于原关闭阶段，新增清理确认与入口证据另行记录。

正式原生入口已接入聊天顶部、抽屉和设置页。EnterprisePage 经 EnterpriseApplicationService 提供示例体验、扫码、粘贴、示例码、状态、同步、Portal、空间切换和原 Session 退出确认；返回聊天重新取得本域访问请求。页面退到后台会取消尚未交接的 Portal 打开，旧回调不改新页面。配置损坏时仍可通过原生入口验证 manifest 并发起退出。本阶段 App 1,998 项 JVM 测试通过，Debug/Release 构建及 lint 无错误。Pixel_10_Pro_Fold / Android 17 的 13 项设备测试通过：4 项真实 Portal WebView、5 项原生页面与生命周期、4 项企业持久化。新增设备覆盖实际 Cookie（含私有路径 HttpOnly）与 localStorage 清理、保留无关站点、停止页面后迟到宿主清理、原退出请求和显示名称冻结。另在实际 Debug 安装包手动走通聊天入口 → 一键示例 → Portal 状态/动态 → 关闭工作台 → 切个人 → 原生确认退出 → 未登录；这条路径使用实际 Koin 和本地来源。扫码摄像头、Release 设备路径、Portal logout、媒体、配置规则 UI、完整资源适配和 0.0.20 整体验收继续实施。版本仍为 0.0.19 开发基线；日志、设备证据与完整门禁记录见 build/reports/enterprise/realm-entry-verification.json。

Portal 原生操作批次已接通 logout 与 openExternal：每次文档创建绑定同一 PortalDocumentContext，网页退出通过原生确认复用 EnterpriseExitService；确认前过期不发起退出，已接受退出继续由应用作用域收口。外链在确认后的 Session 锁内复验原文档期限，旧提示、取消和十秒超时不能打开地址。新增 PortalMediaStore 提供按文档隔离的预留、冻结发布、分块读取、过期与失败清理组件，13 项真实临时文件测试通过；尚未接入硬件和宿主，不声明媒体 capabilities。独立审查提出的确认期限与媒体扫描内存问题均已修正，未保留旧回调路径。

本批完整门禁 test、assembleDebug、lintDebug、assembleRelease 与相关 connectedDebugAndroidTest 串行通过。App 2,014 项 JVM 测试无失败，其他模块无失败，Workspace 保留 11 项 Windows 条件跳过；lint 无错误。Android 17 的 14 项设备回归通过，新增随包 Portal → Bridge → Compose 原生确认 → 实际 Session/store/Exit 的取消与确认退出验证。另在实际 Debug 包使用完整 Koin 服务走通网页原生退出、未登录状态和个人新聊天，并观察到过时确认超时且不退出。日志与分类证据见 build/reports/enterprise/portal-native-verification.json。硬件权限/采集/迟到写入、Release 设备体验、剩余资源与配置规则 UI、私有整包导入界面和 0.0.20 最终验收继续实施；当前版本号不变，未宣称真实平台互操作完成。

| 编号 | 变更 owner / 文件范围 | 完成要求 |
| --- | --- | --- |
| D0 | 本文、后续 roadmap、README/引用、退休 persistent-records 旧计划 | 评审后先独立提交文档 |
| C1 | SettingsStore/Commit/Normalization/EffectiveSettings/WriteRules、三类模型/scope/typed refs | 五项规则、全部字段迁移、唯一新读写链 |
| C2 | DataStore/SharedPreferences/旧备份迁移、AppDatabase/Entity/DAO/Repository/schema/索引 | 原个人 ID/秘密/值/图保全，fresh/upgrade 同构、中断恢复 |
| C3 | 企业 source/session/applied/private binding/application/query、DI | 无后台接入/切换/退出/更新/重启可用 |
| C4 | Conversation 命令/查询/runtime、Draft/标题/搜索/统计 | 所有数据入口 scoped、在途与草稿隔离 |
| C5 | TurnContextFactory/TurnToolSetFactory、Provider/Speech、MCP、SubAssistant | 企业/用户资源消费者完整，模拟及真实 binding 正确 |
| C6 | Memory、Artifact/GenMedia、文件服务、Workspace/Rootfs、通知/SAF | 本域数据授权，共享配置资产/工作空间明确，上传无泄漏 |
| C7 | BackupArchiveService/PendingBackupRestore/Recovery、系统 backup rules | 个人恢复保全企业图，失败可恢复，无整库覆盖 |
| U1 | RouteActivity/Screen、Chat 空间入口、Settings 企业页面/VM | 两种构建正式入口、一键/扫码/粘贴/切域/退出 |
| U2 | 全部 Settings/Assistant/Extensions/Speech/MCP/Model/Prompt/Backup 页面和 pickers | §6 每行有实际行为，UI 与命令/执行一致 |
| U3 | Portal 宿主/固定资源包/动态、相机/录音/扫码/受限媒体句柄 | 真机权限、取消、结果与来源隔离 |
| M1 | 完整示例、确定性 adapters、场景管理、私有模板/导入/.gitignore | 零凭据完整体验，私有文件可替换真实服务，无秘密进 Git |
| R1 | 删除旧原型/无效 developerMode/全局消费者，更新 references/静态约束 | 无双路径/假 UUID/无消费者接口，文档忠实代码 |
| V1 | build.gradle.kts、changelog、报告、APK/说明 | 20/0.0.20，完整门禁与设备体验，最终提交 |

顺序：D0 → C1/C2 → C3/U1/M1 最小完整接入 → C4/C5/C6/C7 与 U2 → U3/完整 M1 → R1/V1。中间开发态允许暂时不完整，最终不留无功能按钮/未迁移消费者或本期“后续再补”。

C6 的文件创建与引用边界已接通：上传、粘贴文本、输出图片、MCP/Workspace 图片、归档和生成媒体显式保存原操作 scope；聊天 Draft 绑定原页面命令目标，会话提交和启动引用重建拒绝跨域文件。独立审查未发现该创建链的剩余实质问题。已发布 v19 按既有 Migration_11_12 保全个人数据，不新增未交付企业原型兼容路径。配置引用已扩展到共享用户定义、用户头像及全部主体保留的使用覆盖，包含预设消息、嵌套工具输出和结构化交付物。Settings writer → Artifact lifecycle → DataStore 回执 → pin 交接构成统一写顺序，协调器不另持锁或状态；GC/发布/补偿只读已提交完整文档。删除按规范化路径收齐原始引用，先持久移除并复验，再清理文件；失败保留 DELETING 与 payload。共享个人定义不能挂接企业资产。旧备份的悬挂配置引用仅在明确恢复入口回退默认，普通新增引用仍严格验证。文件列表与预览删除确认同时提示头像、背景和预设内容影响。文件目录、统计、候选与删除已按原 RealmSelection 接通；单项 owner 复验归属，切域清理页面确认和预览，分页复用既有会话目录生命周期。输入框不再订阅全局文件目录。查询失败明确呈现并可重试。图像页取消沿原请求等待执行收口，禁止迟到取消新域请求。预览/导出的完整读取授权、共享配置资产复制、Workspace 上传挂载、个人备份保全企业图，以及 ImgGen/MCP 等完整资源准入继续实施；C5/C6 尚未整体验收。

文件目录与原请求取消批次的最终 `test assembleDebug lintDebug assembleRelease :app:connectedDebugAndroidTest` 串行门禁在 9 分 11 秒内通过：App 2,060 项 JVM 测试无失败/跳过，lint 0 错误、285 警告，Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器 16 项定向设备用例全部通过，保留此前真实 v19 升级、配置引用与删除恢复覆盖，并新增同库个人/当前企业用户/其他企业用户的列表、图库分页、统计、伪造跨域 ID 拒绝、范围清理及旧选择拒绝验证。实际 Pager 测试覆盖迟到消费与切域；图像页确定性测试覆盖连续取消不能绕过原请求收口。独立审查的生命周期问题已修复并复核。新 Debug 包冷启动、个人文件页、正式入口示例接入 READY 和企业文件页均已手工走通。证据见 `build/reports/enterprise/file-directory-verification.json`；本批不代替完整文件读取/导出授权、真实平台互操作、硬件扫码和 Release 实际运行验收。企业设置首页仍沿用个人配置就绪提示，须与剩余配置 UI 差异一并修正。

模型请求读取已接到原 Turn 域：`StepRunner` 通过 Artifact owner 取得单请求读视图，文档解析、工具结果回放和原生图片编码共享同一保留期；成功、失败和取消统一释放。外域引用拒绝，缺失文件仍降级为不可用，同路径新文件不会被旧请求重新认领。识图与子助手委托按原域验证上传，结构化子助手交付物进入同一引用提取规则。此批不代替预览/导出、共享配置资产复制、Workspace 挂载和备份边界，C5/C6/C7/U2/M1/R1/V1 继续实施。

本批完整串行门禁 `test assembleDebug lintDebug assembleRelease :app:connectedDebugAndroidTest` 在 8 分 29 秒内通过：App 2,063 项 JVM 测试无失败/跳过，lint 0 错误、287 警告；Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器 17 项定向用例通过，新增企业原件在保留期间不能删除、Android Provider 图片编码实际读取及跨域拒绝验证。JVM 验证同时覆盖结构化交付物保留、缺失路径迟到重用拒绝，以及流式成功/失败/取消后释放。独立审查已完成，证据见 `build/reports/enterprise/request-read-verification.json`。未将此结果计为 Release 设备、物理扫码或真实平台互操作验收。

子助手详情已绑定原父聊天页面授权：导航仅运行时借用 lease，保存恢复不重建授权；切域、关闭父页面和关联会话删除使旧详情不可用。Child 检查、加载和观察引用由既有 ConversationCommandCoordinator 在同一会话锁内取得，Reader 组合父 metadata、Child 时间线与附件预览，ViewModel 不再自行解析或订阅 Runtime。父 link 每次更新只在后台解析一次；关闭或失败只释放详情的 Child 引用，不误关父页面。未新增持久化结构或第二 Runtime owner。预览 URL 之后的实际解码、导出与共享配置资产读取仍属未完成的 C6。

该批完整串行 `test assembleDebug lintDebug assembleRelease :app:connectedDebugAndroidTest` 通过，最后一次增量门禁耗时 55 秒。App 2,071 项 JVM 测试无失败/跳过，lint 0 错误、287 警告，Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器 12 项测试全部通过：新增真实 Navigation 保存恢复后屏蔽旧 ViewModel 内容，另回归实际 Room 会话树与引用事务。JVM 使用真实 Registry/Coordinator 与确定性调度验证超过闲置时限后的删除通知和独立释放，并覆盖跨域拒绝、迟到预览和父页面关闭。完整门禁曾发现 Query 直接加载 Runtime，现收回原 Coordinator，未放宽静态约束；旧树测试创建/保留个人附件的错误夹具已改为企业范围，设备复验通过。独立复审无剩余本批实质问题，证据见 `build/reports/enterprise/detail-read-verification.json`。该结果不代表 Release 设备、真实平台或 0.0.20 整期验收。

受管图片读取已复用既有 Artifact/GeneratedMedia owner：文件目录和图库缩略图保存原 RealmSelection，Coil 在缓存查询前和解码返回后验证原授权；owner 在原生命周期锁内校验归属、发布状态、真实路径及图片类型并限量读取。文件锁等待期间到期也不能返回内容。没有新增持久化结构、文件缓存或保留权 owner。完整查看器、保存、设为背景、Markdown/HTML、配置资产及临时图片仍需统一传递类型化来源，不能把本批缩略图接线当作 C6 完成。

该批完整串行 `test assembleDebug lintDebug assembleRelease :app:connectedDebugAndroidTest` 在 8 分 27 秒内通过：App 2,073 项 JVM 测试无失败/跳过，lint 0 错误、287 警告，Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器 14 项测试全部通过，其中生产 Coil 组件配合真实 Room/Android 解码验证两类图片解码与内存缓存命中、跨域 ID、未发布附件、删除后缓存、切域往返旧请求及解码后的迟到结果拒绝；同时回归请求文件保留与子助手导航恢复。独立审查无剩余本批实质问题，证据见 `build/reports/enterprise/managed-image-verification.json`。版本保持 0.0.19，Release 设备、物理扫码和真实平台互操作不在本批证据内。

会话附件预览与子助手终态交付已补齐原 scope 检查：图片、文档等媒体共用 Artifact lifecycle lock 下的主体、发布状态、MIME 和 upload/images canonical 根校验，删除未调用的 URI 入口。创建 pin 解除通过原 owner 的失效通知刷新预览，不需要额外数据库写入。子助手详情将 Child snapshot 与附件变化合为一条可取消投影，删除重复监听及旧 CAS helper，回交前检查取消，避免首次 Loading 时丢失发布通知或短暂显示旧结果。返回 URL 仍不构成后续解码/导出权限，完整类型化图片来源链继续实施。

该批完整串行 `test assembleDebug lintDebug assembleRelease :app:connectedDebugAndroidTest` 在 8 分 23 秒内通过：App 2,073 项 JVM 测试无失败/跳过，lint 0 错误、287 警告，Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器 16 项测试全部通过，新增真实 Room/文件的跨域与非附件目录拒绝、未发布媒体拒绝、子助手文档归属检查及无额外数据库写入的发布刷新。确定性 Reader 测试逐个检查已交付状态，覆盖发布/新 Child 期间的迟到预览，替换旧 helper 的低价值镜像测试。独立审查发现的目录边界、通知与取消窗口已修复并复核。首次设备 PNG 静态夹具未被实际 Android 校验接受，改用 Bitmap 编码夹具后复验通过，未放宽生产校验。证据见 `build/reports/enterprise/scoped-preview-verification.json`。版本仍为 0.0.19，本批不代表完整 C6、Release 设备或 0.0.20 整期验收。

图片读取对象已统一为进程内 `ImageSource`，旧 `ManagedImageLoader` 机制删除。文件目录/图库缩略图及输入框图片使用相同 Coil 校验边界；会话预览投影保留稳定文件 ID 和原页面授权。草稿输入沿既有创建 pin 与提交/退回协议读取，所有权变更通知使发送失败后的缩略图能够自动恢复，不新增附件 owner 或持久化结构。真实 Room/Coil 验证创建权认领、退回、丢弃、已发布输入及外域拒绝；Compose 设备用例验证首次图片读取中提交后拒绝退回，原输入不变且挂载组件恢复像素。完整查看器、保存、背景及其他来源消费者尚未完成，本批不代表 C6 或版本 20 验收。

本批完整串行 `test assembleDebug lintDebug assembleRelease :app:connectedDebugAndroidTest` 在 8 分 39 秒内通过；App 2,075 项 JVM 测试无失败/跳过，lint 0 错误、287 警告，Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器 18 项全部通过，包括实际挂载输入组件的发送失败恢复。独立复审无剩余本批实质问题，证据见 `build/reports/enterprise/image-source-verification.json`；Release 构建通过不等于 Release 设备验收。

查看器与导出接线批次：会话/子助手相册、Markdown/HTML、文件目录、生成结果和 Workspace 图片已改为 ImageSource；共享配置图片按 durable root 验证，临时生成预览借用完整请求 Job。删除旧 String 查看器入口及服务内重复的网络/base64/本地路径解析。图片保存保留编码并通过 MediaStore pending 发布；聊天截图与 Markdown 的最终发布、分享复验原页面，文件收口统一放在既有 MediaExportService。设为背景的目标域、ImgGen 参考输入、其他文件/媒体出口及 Workspace 上传挂载仍未完成，不计作完整 C6。

本批定向设备复验 39 项通过，包括实际查看器手势、GIF 编码保全、pending 清理和共享配置根撤销。完整设备扫描发现旧迁移测试夹具没有注册既有 Migration_11_12，已修正为当前生产迁移链；查看器测试安装生产图片组件，相册测试显式查询 pending，复验通过。完整门禁中的 JVM、Debug/Release 构建和 lint 已通过：App 2,065 项 JVM 测试无失败/跳过，lint 0 错误、286 警告，Workspace 保留 11 项 Windows 条件跳过。完整 Android 17 扫描 169 项中 168 项通过，真实键盘用例在 Gboard 展开接近十秒时超时；改用真实触摸并延长系统 IME 等待后，该组 8 项设备复验全部通过，未再重跑整套 169 项。取消补偿的 2 项 JVM 复验通过，保留原取消异常及清理失败诊断。独立审查无剩余本批实质问题；分层证据见 `build/reports/enterprise/image-viewer-verification.json`。真实平台互操作、Release 设备与版本 20 整体验收仍未完成。

辅助模型接线批次：标题、建议和手动摘要已复用 `ModelExecutionService` 的原域捕获、冻结 binding 和逐请求准入，删除直接查全局个人 Provider 的旧路径。原 Runtime 保存任务登记时的助手身份和模型 lease，会话移交不重定向原任务；删除助手按原 owner 停止并保留有界等待，清理失败可重试。摘要先释放模型资源，再原子提交历史，建议的清空和最终提交均复验原助手/节点。独立复审发现的身份窗口与提交后失败问题已修正。完整门禁的 JVM、Debug/Release 构建及 lint 通过：App 2,073 项无失败/跳过，lint 0 错误、286 警告，Workspace 保留 11 项 Windows 条件跳过。设备源集三处旧接口已同步，随后 Android 17 模拟器完整 169 项测试全部通过；包含原 Turn 取消/暂停继续、真实 Room、WebView、文件与键盘布局验证。分层结果见 `build/reports/enterprise/auxiliary-model-verification.json`。图片生成、附件识别、MCP/Gateway、Speech、其余文件出口和备份仍继续实施，不据此将 C5/C6 或版本 20 标为完成。

附件识别已接入原域模型捕获：与 CHAT 共享同次用户配置和企业 binding，`ModelRequests` 只提供执行，Runtime 仍持有唯一释放 owner。主/子装配消费捕获结果，`assistant_inspect` 从本域目录描述可用性，旧 Settings/ProviderTransportLease 路径删除。本地示例走相同结构化图片请求，返回明确模拟结果。复审未发现实质缺陷，补充 Child 撤权与暂停继续的借用请求断言。完整串行 `test assembleDebug lintDebug assembleRelease :app:compileDebugAndroidTestKotlin` 在 10 分 4 秒内通过：App 2,072 项 JVM 无失败/跳过，lint 0 错误、286 警告；Workspace 保留 11 项 Windows 条件跳过。Android 17 模拟器三个实际 Room/Turn 取消与审批继续用例全部通过；此前辅助模型基线的整套 169 项设备通过单独保留，本批不冒称重新运行全部设备用例。证据见 `build/reports/enterprise/inspection-model-verification.json`。图片生成、MCP/Gateway、Speech、其余文件出口和备份继续实施。

图片生成已接入原域模型捕获与请求链：页面从本域目录选择模型，队列节点持有页面资源；工具借用原 Turn，删除旧 ImageGenerationSelectionResolver。生成/编辑使用私有凭据或明确模拟 PNG；私有下载只继承日志标记，不转发认证。原页面切域往返仍失效，企业退出等待实际停止与失败释放重试。图库提交后同步把聊天副本交给原工具资源协议，取消回交不丢失 owner。

图片批次独立复审无剩余实质问题。完整串行 `test assembleDebug lintDebug assembleRelease :app:compileDebugAndroidTestKotlin` 通过：App 2,072 项、AI 369 项 JVM 无失败，lint 0 错误、287 警告；Workspace JVM 保留 11 项 Windows 条件跳过。完整 `connectedDebugAndroidTest` 通过：Android 17 模拟器 App 170 项、Speech 6 项通过，Workspace 报告 10 项、其中 1 项硬链接环境条件跳过。新增图片设备用例使用真实 DataStore、Room、PNG 和生产本地队列验证生成/编辑、域隔离、旧页面拒绝与退出，无真实 Provider 网络。证据见 `build/reports/enterprise/image-owner-verification.json`。参考图片导入授权、背景目标、MCP/Gateway、Speech 企业适配及备份继续实施；版本仍为 0.0.19，不据此宣称 Release 设备或真实平台互操作验收。

背景设置已按原域接通：个人写共享助手定义，企业写完整主体的助手使用偏好；生成工具按原任务与图库 ID 读取，查看器确认绑定原选择。Settings typed mutation 与 Artifact 引用提交共用既有写 owner；失败精确回收副本，GC 保留其他域仍引用的图片。助手编辑页面传入同源的渲染基线与编辑结果，避免长存提示词回调撤销后来设置的背景；目标未就绪时不挂载编辑器。

背景批次双人复审已关闭发现。最终串行 `test assembleDebug lintDebug assembleRelease` 与两项定向 Android 用例通过：App 2,074 项、AI 369 项 JVM 无失败；lint 0 错误、287 警告；Workspace JVM 保留 11 项 Windows 条件跳过。Android 17 实际 DataStore/Room 用例验证企业偏好、个人定义不混写、读取中切域、旧页面与退出拒绝；真实 Prompt 页面验证更新背景后的输入回调，并由 VM 测试验证合并保全。此次仅运行两项设备场景，不把此前整套设备基线当作本批重跑。证据见 `build/reports/enterprise/background-verification.json`。参考图片导入、其余 C6、MCP/Gateway、Speech、备份及最终版本 20 验收继续实施。

## 11. 验收证据

| 编号 | 必须证明 |
| --- | --- |
| E01 升级 | 真实旧 Settings/SharedPreferences/Room/文件保全 ID/值/秘密/引用，失败重启可恢复 |
| E02 正式入口 | Debug/Release 可见入口，一键/扫码/粘贴成功与失败，无 Debug 依赖 |
| E03 五项策略 | 每项 true/false/恢复、已选失效、个人不变、清单外可用、工具不能旁路 |
| E04 MCP/助手 | 企业 MCP 强制启用、用户主子助手、固定绑定、工具结果与持久化 |
| E05 配置变化 | 改名/参数/删除/新 ID/坏引用/缺字段/错 source，whole-state 原子，不静默换模型 |
| E06 执行 | Chat 流/工具/语音/Search/Skill/Workspace/子助手/辅助生成，用户真实资源及企业示例路径 |
| E07 状态 | 切域/退出/过期/断网/恢复/重启、在途取消与迟到响应、无自动重放 |
| E08 数据 | 会话/记忆/文件/统计/搜索/deep link 隔离，Workspace 显式共享与上传挂载授权 |
| E09 备份 | 旧包迁移、个人包排除企业、恢复保全企业、失败/主键/路径冲突处理 |
| E10 Portal | 真机权限允许/拒绝/取消/导航/退出、原文档结果、句柄回收、无凭据泄漏 |
| E11 私有资料 | 完整模板、坏包无半提交、更新真实 binding，缺真实秘密仍可运行示例 |
| E12 发行 | Release R8 后入口/HTML/序列化/执行可用，版本/产物与 Git 范围正确 |

纯规则测试锁语义；组件测试覆盖失败/竞态/所有权；Room/文件/迁移、Compose、权限、恢复需真实设备。静态测试只保护依赖与退休面，不用字符串冒充 UI/授权。Mock 证据不证明真实企业系统。

定向验证后串行执行：

```text
gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
gradlew.bat connectedDebugAndroidTest --no-parallel --max-workers=1
```

另需安装 Release 实际验证，Debug instrumentation 不代替发行版行为。新增常见文案同步 values、values-zh、values-ja、values-ko-rKR、values-ru。记录设备/报告/场景与外部凭据验证缺口，只按真实证据更新完成状态。

最终交付两份文档独立提交、完整实现提交、0.0.20 APK/校验信息、私有文件模板和使用说明、E01–E12 证据。提交前检查 git diff --check、最终 diff/工作树、schema/序列化/备份兼容，不含秘密或无关变更。

参考图片导入已归入现有文件应用服务与 ImgGenVM：原生选择前保存原域和页面任务，返回时复验；重建页面保留待返回目标，重置任务则拒绝旧结果。临时副本只由 VM 持有，实际请求借用的文件等待请求收口，其余未接收或移除的文件立即回收。没有新增持久化结构或资源 owner。独立复审发现的取消补偿、满额临时文件与选择器目标丢失均已修复。

本批定向 JVM 与 Android 17 的实际图片解码/存储/域授权用例通过；完整串行 `test assembleDebug lintDebug assembleRelease :app:compileDebugAndroidTestKotlin` 通过（11 分 5 秒）：App 2,077 项 JVM 无失败/跳过，lint 0 错误、287 警告，Workspace 保留 11 项 Windows 条件跳过。原生选择器 Activity 重建未作设备验收，目标保留由 VM 测试验证；本批设备只运行一项图片集成场景，不冒充整套设备回归。证据见 `build/reports/enterprise/reference-image-verification.json`。其余媒体出口、Workspace、MCP/Gateway、Speech、备份及最终版本 20 验收继续实施。

附件与渲染导出已收回既有文件服务：文档/音频/视频从原页面授权和稳定 Artifact ID 出发，在生命周期锁内复制后交给外部查看器；最终交付复验原页面与 Session。失败或取消只清理未交付副本，启动清理不会删除本次进程刚导出的文件。Mermaid 的实际 WebView、导出请求和回调属于原文档，换来源/主题不重放旧请求，迟到回调不能完成新请求；没有新增持久化结构或文件 owner。

该批独立复审已关闭发现，最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 全部通过（5 分 26 秒）：App 2,080 项 JVM 无失败/跳过，lint 0 错误、287 警告；Android 17 模拟器 App 173 项、Speech 6 项通过，Workspace 的硬链接能力用例按设备条件跳过。首次整套设备运行因模拟器系统看门狗终止系统进程而中断，冷启动并切换软件图形后端后完整重跑通过，未放宽业务断言。分层结果见 `build/reports/enterprise/attachment-export-verification.json`。富文本链接/代码与表格导出/HTML 预览、Workspace、MCP/Gateway、Speech 企业接线、备份和版本 20 最终验收继续实施。


富文本批次已收回原页面来源：聊天/子助手、用户配置预览与静态说明明确区分；链接、代码和表格下载共用宿主动作与既有文件服务。选择器返回只处理点击时冻结的正文和来源，失效或恢复后缺失请求时清理新建文档。HTML 预览不再落盘缓存，每份文档使用独立 origin；原生 file/content 访问关闭，本地资源由原 Artifact owner 校验。保存导航只保留条目 ID，不能重建旧 HTML 或授权。聊天截图由原导出协程拥有临时 Compose 树，取消/清理失败保留原错误并释放资源。

该批独立复审的来源、选择器、Bitmap 清理、外部 base 与观察器循环问题已关闭。最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 通过（13 分 37 秒）：App 2,079 项 JVM、Android 17 模拟器 App 178 项和 Speech 6 项无失败，lint 0 错误、290 警告；Workspace 保留 Windows JVM 与设备硬链接条件跳过。设备消费者覆盖代码/表格点击、两个 Markdown 引擎与 HTML 链接、真实 Room/Artifact 图片、预览导航恢复与截图取消；选择器结果和截图最终出口使用测试适配，不能表述为人工系统文件选择/图库验收。分层证据见 `build/reports/enterprise/richtext-verification.json`。版本仍为 0.0.19；共享配置复制、Workspace、MCP/Gateway、Speech 企业接线、备份与版本 20 整体验收继续实施。

Workspace 输入交付已接通原 RealmAccess：原生 `/upload` 读取由 ArtifactStore 校验主体、ACTIVE 与发布状态，写入/编辑始终拒绝，不回落到共享 Linux 同名目录。Shell 只接收显式 `uploads` 的授权副本，数量与总量有界，空列表也绑定空目录；原文件不受副本修改影响。单次调用持有临时目录，实际进程停止后才清理；重复中断、销毁失败、复制中取消及符号链接清理均有失败路径验证。Session 锁不包住 Shell 等待，未新增持久化结构或文件 owner。全局挂载表与 PTY 不再自动暴露上传目录。

该批独立审查无剩余实质问题。最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 通过（13 分 59 秒）：App 2,081 项 JVM、Android 17 模拟器 App 179 项与 Speech 6 项无失败；lint 0 错误、290 警告。Workspace JVM 48 项中保留 11 项 Windows 条件跳过，设备 11 项中保留 1 项硬链接条件跳过。首次全量运行遇到 JBR 编译器自身崩溃，保留诊断后原代码完整重跑通过，未放宽断言。应用设备用例使用真实 Room/Artifact/文件与受控 Shell adapter，进程终止另由确定性 Process 用例验证，不冒充真实 PRoot 命令验收。分层证据见 `build/reports/enterprise/workspace-input-verification.json`。

Workspace PTY 与 SAF 已完成实现、独立复审与完整门禁：原 Session 保留终端，原选择版本约束输入与页面操作；切域在发布新选择前永久退休旧 viewport，返回同一有效 Session 可用新视图接回原 PTY。绑定和输入等待原 Session 准入，锁忙不能静默丢弃按键；已接受关闭即使页面取消也继续收口，失败保留 owner 供重试。SAF 暴露共享 Workspace，经既有应用/查询入口与描述符操作完成，不另建当前域状态。沿用 PRoot 的既有非内核沙箱边界：限制应用交付的文件并保护原 Artifact，不宣称能够阻断恶意 Shell 的所有宿主访问。共享配置资产复制、MCP/Gateway、Speech、备份及版本 20 整体验收继续实施；当前版本仍为 0.0.19。

PTY 最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 通过（9 分 7 秒）：App 2,083 项 JVM、Android 17 模拟器 App 186 项与 Speech 6 项无失败；App lint 0 错误、287 警告，Workspace lint 0 错误、11 警告。Workspace JVM 保留 11 项 Windows 条件跳过，设备 11 项中保留 1 项硬链接条件跳过。分层结果见 `build/reports/enterprise/workspace-terminal-verification.json`。

新增设备消费者验证包含 6 项原生 PTY 场景和 1 项实际 Compose 页面场景：真实 JNI 与系统 shell 覆盖阻塞输入、自动回复、中文长文本、输入上限、取消、旧 IME/autofill 拒绝及切域失败；Compose 验证标签切换、物理视图退休/重建和扩展键路由。完整回归暴露的首次绑定轮询超时已改用 Compose 空闲同步，断言不放宽。原生 open/fork 失败抛错、waitpid 重试 EINTR、信号退出按 JNI 约定返回负值。本批未新增数据库或配置结构，不代表 PRoot 全场景、Release 设备或真实平台验收。

SAF 批次已删除 Provider 直连 DAO/Manager、查询时建目录以及移动失败后的复制删除回退。URI 保持 `ws/{root}/{path}`，注册查找复用 `root` 唯一索引；命令复用既有 workspaceId stripe，双操作去重排序并在锁内复验。底层与 Rootfs 复用同一 JNI 文件设施，目录句柄处理 NOFOLLOW、验证后截断和原子重命名；复制 staging 使用既有 `tmp/`，失败按原所有权清理，普通用户文件名不被隐藏。描述符移交后归外部客户端，不承诺随切域撤销。

该批最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 通过（13 分 59 秒）：App 2,085 项 JVM、Android 17 模拟器 App 189 项与 Speech 6 项无失败；App/Workspace lint 均为 0 错误，分别有 287/11 项警告。Workspace JVM 保留 11 项 Windows 条件跳过，设备 12 项中保留 1 项硬链接条件跳过。真实 Provider/Room/JNI 消费者覆盖注册操作、锁冲突、缺失目录、非法 URI、符号链接、复制失败清理和 PFD 取消；目录句柄另验证路径替换后仍作用原目录。未新增表、索引或配置结构。证据见 `build/reports/enterprise/workspace-documents-verification.json`；此批不代替系统文件选择器人工验收、Release 设备、完整 PRoot 或真实平台验收。其余 C6、MCP/Gateway、Speech、备份、UI/示例与版本 20 整体验收继续实施。


共享配置预设附件已通过原 Artifact 创建协议物化到目标域：媒体、工具交付 metadata、嵌套输出及归档引用使用同一复制映射，原配置文件和归属保持不变。主 Draft 由 Runtime 保留创建令牌，首 USER 单事务提交；失败可重试，闲置丢弃交还 GC；Child 复用既有创建/链接/补偿。未新增 schema、配置区或持久化 owner。独立复审发现的发布/读取竞态和工具多图片重写问题均已关闭。

本批最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 通过（16 分 36 秒）：App 2,088 项 JVM、Android 17 模拟器 App 190 项与 Speech 6 项无失败；App/Workspace lint 均为 0 错误，分别有 287/11 项警告。Workspace JVM 保留 11 项 Windows 条件跳过，设备 12 项中保留 1 项硬链接条件跳过。真实设备消费者使用正式 Room/FTS 建库入口，验证企业 Draft 预览、源删除、首消息事务失败与重试、提交后读取及关页撤权。证据见 `build/reports/enterprise/configuration-assets-verification.json`；不替代 Release 设备、实体机、系统选择器人工验收或真实平台互操作。MCP/Gateway、Speech、备份、UI/示例和版本 20 整体验收继续实施，最后仍须对整份需求做独立审查。


C5 的 MCP 协议接线正在实施。完整远端 Tool 对象已进入唯一 Catalog：SDK 仍拥有 RPC/超时/取消，
传输保留同次响应，不丢失 outputSchema、annotations、`_meta` 或 Schema 扩展；原个人目录的 Tool 字节和摘要保持。
HTTP JSON、POST SSE、GET SSE 和个人 SSE 经实际 SDK、生产 OkHttp 与本机 HTTP 服务验证，覆盖分页、
完整字段往返、错 ID、持续 POST 流、GET 断线恢复与多行内容拒绝错误拼接。

Catalog 已按来源、Deployment、User 和资源保存企业目录，Session 属于运行态；用户目录仍归个人。
已发布个人数组一次性迁移到版本化文档，成功后删除旧键。个人备份只替换个人目录，保留企业内容及提交回执；
新文档损坏时拒绝覆盖，不回退旧键。独立审查发现的无关恢复导致企业补偿失效问题已修正。
MCP 与备份定向 JVM 回归 146 项通过，Android 17 模拟器目录关闭重开用例通过；覆盖不同用户和本地/平台来源。
证据位于 `build/reports/enterprise/mcp-catalog-scope-focused.log` 与 `mcp-catalog-scope-jvm-results.json`。
企业原 Session 连接与准入、Gateway hash/成对策略/执行、本地企业工具完整场景仍待本批收口；
不将上述目录与协议验证视为企业互操作完成。

本目录与协议接收批次的完整串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest` 通过（19 分 43 秒）：App 2,101 项 JVM、Android 17 模拟器 App 190 项与 Speech 6 项无失败；App/Workspace lint 均为 0 错误，分别有 287/11 项警告。Workspace JVM 保留 11 项 Windows 条件跳过，设备 12 项中保留 1 项硬链接条件跳过。证据见 `build/reports/enterprise/mcp-catalog-verification.json`。未完成原 Session MCP 连接、Gateway 完整场景、Speech、个人备份图保全与其余整期验收；版本仍保持 0.0.19 开发基线。
