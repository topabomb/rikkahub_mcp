# Android 企业域本期实施方案（0.0.20）

> 状态：实施基线，待开发与验收。本文是 `versionName=0.0.20` / `versionCode=20` 的需求、架构、UI、变更与验收权威。
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

平台架构仓库由其他任务维护。本期以已明确的用户配置复用语义为准；其修订中的 Snapshot v4/v5 和桥接协议不是本地资料版本，也不代表服务端已 Freeze。不得让真实服务端 Gate 阻塞本期本地实现，不宣称本地实现已获得生产互操作认证。

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
- 查询/命令/FTS/统计/收藏/最近聊天/文件/deep link/通知/SAF/子助手都校验 scope，不先全量读取再 UI 过滤。
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

正式退出由应用层统一编排：确认时捕获原主体和 Session，提交时复验，旧确认不能退出新接入。先撤销准入，再释放 Session 锁，调用既有主/子运行与媒体 owner 按原企业域取消并等待终态，最后完成退出；不得以 binding lease 为空代替运行清理。流程由应用作用域持有，页面销毁或重复点击不产生第二退出操作，也不能遗留无人收口的 CLOSING。企业状态页直接投影 Session；待配置或停留个人空间时，同步目标仍是已接入的企业 Session。
| 清除示例企业数据 | 独立确认，仅清该示例主体数据/导航，不删个人配置/历史/共享 Workspace |

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

公开 DTO、eup_UUIDv4 标识和枚举直接遵守 Client OpenAPI。查询统一处理日期、默认/范围 limit、truncated 和 publishedAt 降序，时间相同以稳定 ID 排序。日期使用企业时区日历的首日零点至末日下一日零点，覆盖 DST；ETag 包含主体、公开 revision、时区和规范化查询，start-only 包含本次解析的企业当前日期。主体/Session/document 授权先于内容及 304 返回；withRealmAccess 的历史数据授权不能代替 Portal 当前 selectedScope 校验。

0.0.20 是个人域/企业域的第一个入口版本。企业接入、Portal、完整配置及本地企业存储只执行本期最新契约；外部旧完整文件明确拒绝并提示使用新模板，不为分阶段开发的企业原型保留双格式解析、旧 HTML、旧桥接或专用历史迁移/归档。清理未交付的旧企业实现，以及与其绑定的临时/过时文件、旧模板、无效测试和文档；保留当前有效的共享消费契约与必要交付材料。已发布个人配置、Room 数据和个人备份的迁移与保全要求仍有效。

共享 feed-vectors.json 必须执行实际查询组件，另验证动态/配置版本独立、撤回后重入、会话撤销后的 ETag 请求及持久提交失败/重开恢复。Portal 的正式页面测试在宿主接通后执行，不以这批领域测试替代。

## 8. Portal 与手机能力

本期接入已交付 Portal dist-local 包，核验并固定资源摘要，使用 https://local.measix.invalid/portal/。清理配置内 HTML 和旧桥接路径；旧企业原型文件明确拒绝，不静默丢弃旧字段或兼容执行。宿主管 document/session，普通外链和模型生成 HTML 不取得企业权限。此段为待实现要求。

采用 Control Protocol Bridge v2：window.MeasixHost.postMessage 请求含 bridgeVersion=2/requestId/method/params，通过 measix:host-response 返回关联结果。支持 getStatus、refresh、close、logout、openExternal、capturePhoto、recordAudio、readMedia、releaseMedia、cancel；不保留旧方法别名。退出由原生确认，关闭仅关闭页面。固定路径拦截 context/Feed，验证主体、Session 与 document 后才返回数据或 304，不访问 DNS 或启动本地监听服务。

每次请求绑定当前顶层 origin、document identity、企业主体和 session。导航/关闭/切域/失效/退出立即取消采集、丢弃迟到结果；并发采集明确 busy/cancel，不后台静默录音。相机真实拍摄、录音真实采集，拒绝权限可解释可恢复。

媒体由统一原生 owner 管理，仅用于本页预览，不自动上传或保存到聊天/Artifact。照片为 JPEG、录音为 audio/mp4，recordAudio 包含原生开始/停止 UI，时长参数 1–60 秒。单项最多 10 MiB、每文档两项/合计 20 MiB、最多保留五分钟；readMedia 只按句柄返回最多 65536 字节的 base64 分块，不暴露 URI 或路径。普通请求十秒、采集请求 120 秒的期限由原生独立保证。释放、超时、导航、切域、退出、会话失效及进程恢复均清理临时文件与迟到结果，旧文档句柄不能复活。

context 从当前原生身份产生，documentId 不可预测，文档期限最多十分钟且不超过母 Session；不使用 fixture 身份或时钟。getStatus/refresh 返回真实已应用状态与实际能力，未知值为 null。context/detail 使用 no-store，动态列表 private/no-cache，所有授权先于缓存判断。原生扫码在登录前可用，一键/扫码/粘贴共用接入验证。生产 grant/Cookie/CSRF 只在后续真实接入规定，本地会话不冒充生产授权。

Portal 本地读取存在两项需要架构侧确认的 Android 平台限制：

- [WebResourceRequest](https://developer.android.com/reference/android/webkit/WebResourceRequest) 不提供 fetch 发起 frame，主页面和 iframe 的 fetch 均为 isForMainFrame=false；WebMessageListener 提供消息的 sourceOrigin/isMainFrame。CSP、Referer 或 document header 不能作为逐请求原生 frame 证明。
- [WebResourceResponse](https://developer.android.com/reference/android/webkit/WebResourceResponse) 的公开构造器及状态码 setter 仅接受 100–299、400–599，拒绝 304。该结论已核对官方 API 与本地 SDK 源码，尚未作为设备场景执行；不能用反射或覆盖 getter 绕过公开 API 限制。

建议架构侧为本地 context/Feed 读取明确带原生来源/frame 信息的消息传输，由 Portal 消费端配套调整，保留现有 DTO、查询、revision/ETag 和先授权再判断未变化的语义，真实平台 HTTP 协议保持独立。若保留本地 GET，需明确同意两项差异：可信文档实例及内容约束授权（不宣称逐请求 frame 证明）、成功读取返回完整 200（不宣称原生拦截实现 304）。目前两种方案均未获确认，Android 不自行修改 Portal 包、增加隐藏桥接或降低验收要求。来源配置同步和 Feed 领域实现不依赖该选择；资源打包和纯查询测试不代表 Portal 接入验收。

## 9. 执行、更新、恢复不变量

- 扩展 TurnContextFactory、TurnToolSetFactory、transport lease 和既有 Conversation/Turn/Step owner，不建企业运行栈。
- 新企业动作检查当前 source/session/五项准入；本地 source 在本机提供状态，无互联网也 READY。用户资源同样受企业调用前准入。
- 捕获域/主体、用户 revision、企业 generation、资源身份、endpoint/binding、prompt/工具；后续 Step 不重读全局当前选择。
- 收紧/退出与外部副作用承诺有统一线性化门禁；旧捕获在下一请求/调用前复验，不合规则终止，不换模型、不重放。已经承诺的调用保存已知结果或 unknown，不谎报未执行。
- MCP Catalog/OAuth、异步标题、自动 TTS、附件识别、子助手均带原 scope。共享用户凭据刷新只写用户 owner。
- 企业候选失败只影响企业 readiness；个人不能因企业网络/配置错被全局锁死。共享 DB 真实损坏仍按恢复协议拒绝 Ready。
- 移除旧签名 envelope/global merge/path lock 和无消费者 facade；原型文件不迁成正式身份；仅保留真实历史迁移需要的解码边界。

## 10. 完整变更清单与批次

C4 的文件夹命令已接入原选择授权：目录和分页使用 RealmSelection，保存原 Session 与选择版本；快速切域往返后旧行、旧弹窗均不可恢复授权，新目录不能给旧行补发权限。创建显式落在原域并复核助手准入；移动验证根会话、主体、助手和两端选择，删除在完整成员锁内先检查在途 turn，失败保留文件夹供重试。UI 不再在提交时重读当前助手，也不提前假报删除成功。未改变 Room/DataStore schema；普通会话命令、START/继续及退出收口仍待完成。本批 22 项定向 JVM 测试通过；完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 10 分 39 秒内通过，App 1,917 项无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Workspace 的 11 项 Windows 跳过项仍保留。Pixel_10_Pro_Fold / Android 17 上的 ConfigurationScopePersistenceTest 两项 instrumentation 在 4 分 59 秒内通过，包含真实 FolderRepository 创建、跨主体目录过滤、数据库重开及重命名/删除保全。独立审查已关闭新目录给旧分页行重新授权的缺口；真实 Pager 测试验证旧源全部失效及行的选择版本变化，不依赖内部重建次数。完整 JVM 汇总保存在 `app/build/reports/enterprise/folder-access-full-gate.json`。这些结果不代替 Compose 文件夹交互、Portal/扫码/媒体或完整企业域验收，版本保持 0.0.19。

C4 的聊天页面打开已接入原域授权：可序列化请求明确区分 NewDraft/OpenExisting，先验证选中主体与 Session，再在会话锁内检查 header，已有聊天缺失不回退新建。页面在 lease 成功后才订阅投影、收藏、错误和创建导入作用域；退出重登或切域使旧 lease 失效。Session owner 的进程内 selectionRevision 防止快速切出再切回被 StateFlow 合并而复活页面，不推进配置或 Feed 版本。Draft 首消息提交后保持原 Runtime/导航项，恢复不重放 preset 或分享输入。页面回收同时清理未提交输入，重试不能复用旧导入 owner 的附件。

启动、通知、历史、搜索、收藏、分享与助手切换已使用明确请求；不可用页提供重试和新建按钮。最近聊天归 ScopedUserPreferences，旧 SharedPreferences 键在 DataStore 成功提交后一次性清理并归个人域；空 Draft 不保存最近 ID，个人设置更新保全企业偏好。普通按 ID 命令、START/继续、文件授权及正式企业入口仍按 C4/C6/U1 继续，不以页面检查代替执行授权。

该页面批次 74 项定向 JVM 回归通过；Pixel_10_Pro_Fold / Android 17 的 5 项 DataStore/SharedPreferences 迁移与域偏好测试在 6 分 11 秒内通过，验证失败重试、重开、个人历史归属与企业值保全。审查发现的重复导入、重试残留附件、查询撤销异常及快速切域通知合并问题均已修正并补充测试。页面批次首次完整门禁在 9 分 52 秒内通过；随后启动检查发现的问题及最终变更验证见下段，尚未计作完整 C4、Portal 或 0.0.20 验收。

设备启动检查发现 Debug 在 Application 的 eager 依赖构造阶段发生 ANR。恢复链现由 IO dispatcher 执行，助手清理依赖在原恢复步骤首次解析同一 singleton；MCP 的共享 Ktor client 在首次真实连接任务中初始化，前台观察者仍在 Main 注册。独立审查及恢复/MCP 定向测试通过。该模拟器上未预编译的 Debug 冷启动仍未通过，最新堆栈停在 Settings 构造；执行设备 `cmd package compile -m speed -f` 后，Debug 在约 21 秒内打开缺失会话页，并实际通过“新聊天”进入空 Draft。先前 Release 构建的对应页面流程通过；安装最终 Release 后首次启动超时，退出 Activity 后 warm 重开约 10 秒进入缺失会话页，并实际通过“新聊天”进入空 Draft。最终产物的冷启动稳定性仍需继续排查，不能把预编译或 warm 页面验证表述为原始冷启动验收通过。

本批最终完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 9 分 52 秒内通过：App 1,908 项 JVM 测试无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 构建成功。两次前置全量运行暴露子助手测试对 Function1 使用 relaxed proxy 的失败，改为显式 processingReporter lambda 后通过，未修改子助手生产行为；清理无调用测试 stub 后，21 项子助手/恢复/MCP 工厂定向测试再次通过。Workspace 45 项中 11 项 Windows 宿主测试仍跳过。本批不构成正式企业入口、Portal、完整域执行或 0.0.20 验收，版本维持 0.0.19。

C4 的目录查询隔离已进入代码：列表/最近聊天/置顶/文件夹/分页/FTS/统计在 SQL 内过滤完整主体，FTS 在排序与限额前排除外域及 Child；消息/Token 沿用既有主子统计口径。ConversationQueryService 从 Session owner 派生选中域订阅，实际 Pager 的每次惰性加载验证原选择/Session，切域和停止订阅失效旧 source。Search/Stats 清除旧结果；抽屉持续观察文件夹，域变化清除筛选和滚动位置。助手查询工具沿用原 RealmAccess，不随全局选中域或重新接入更换身份。没有 schema 变化或第二搜索投影。按 ID 的页面 lease、Draft/Open、命令及文件授权继续作为 C4/C6 后续工作，本批不构成完整域访问验收。

该查询批次 33 项定向 JVM 测试通过，覆盖实际 Pager、旧工具 Session、到期及取消。Pixel_10_Pro_Fold / Android 17 的 7 项定向 instrumentation 在 3 分 18 秒内通过，包含生产数据库工厂的 Requery/simple/Jieba 中文搜索、完整主体过滤、限额前过滤和实际 Room 统计。两轮独立审查无剩余本批阻塞项。完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 9 分 2 秒内通过：App 1,892 项 JVM 测试无失败或跳过，其中新增统计页用例验证切域取消及失败清空；lint 为 0 errors、272 warnings、5 hints，Debug/Release 均构建成功。Workspace 45 项中 11 项 Windows 宿主测试仍跳过。版本仍为 0.0.19 开发基线，正式入口、Portal 与完整 0.0.20 交付继续实施。

Gateway 使用偏好已移入既有 ScopedUserPreferences，按完整主体保存，删除无 userId 的顶层原型字段；保留已发布个人配置迁移协议。EnterpriseGateway 删除第二个 enabled 位，对齐定义存在即发布、撤销通过候选移除的语义。Resolver 统一派生完整工具对的生效开关与可切换性，REQUIRED 保留但不使用原 false，恢复可控后恢复原偏好。配置应用命令只接受捕获的 RealmAccess.Enterprise，同主体重登后旧页面不能继续修改使用偏好。该批 Gateway 执行消费者与 UI 尚未接通，不代表 Gateway 运行功能已验收。

该偏好批次 39 项定向 JVM 测试通过；新增断言首次误用包含引用相等对象的整文档 equals，改为对比完整序列化内容后通过，未修改产品逻辑规避。Pixel_10_Pro_Fold / Android 17 的 ScopedConfigurationAndroidTest 在 2 分 27 秒内通过，使用实际 DataStore/企业存储重开验证偏好保留、用户隔离与个人配置保全。完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 8 分 13 秒内通过：App 1,885 项 JVM 测试无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 均构建成功。Workspace 45 项测试中 11 项 Windows 宿主跳过，不算相应能力验收。独立审查无剩余本批阻塞项；版本仍为 0.0.19 开发基线，Portal 读取协议待确认，会话隔离、执行消费者、正式页面和完整 0.0.20 交付继续实施。

本地来源候选与统一同步已实现：安装目录独立保存来源/Deployment/User 身份、generation 和内容摘要引用，随包文件仅初始化；场景发布使用来源 revision CAS，不直接改客户端 Applied。原生完整文件可显式安装其他本地主体，短资料只查询已安装来源，由票据确定用户，消费前检查 Session 冲突。EnterpriseSynchronizationService 合并同原 Session 的同步，失败保留 Applied 和成功时间，同版本成功检查复用 Applied revision，提交期间到期不创建新 Session。有效来源发布后客户端失败返回待接入/待同步；来源文件损坏可显式导入更高 generation 修复。旧 updateLocalPackage 与安装包/Applied 回退分支已删除，未新增客户端 enterprise_local 配置区。

此来源批次最终 67 项定向 JVM 测试通过，覆盖来源与 Applied 分开观察、重开、多主体票据、损坏修复、CAS、提交失败/取消、并发等待、退出后迟到结果和提交期间到期。首次二维码库测试在生成图识别阶段出现 NotFoundException，改为对无畸变生成图使用 PURE_BARCODE 后通过；仍执行真实二维码解码及接入，不算相机扫码设备验收。退休旧入口后的首次定向运行暴露记忆测试混用虚拟调度与真实时钟，修正测试收集器执行上下文后通过；未延长超时或修改产品到期逻辑。Pixel_10_Pro_Fold / Android 17 的 11 项定向 instrumentation 在 2 分 14 秒内通过，验证实际 AtomicFile 来源发布、重开、同步、退出重入、多用户安装，以及 Applied/Feed、域偏好和 Room 记忆回归。独立审查无剩余来源/同步阻塞项；正式 UI、Portal 文档授权与媒体流程尚未接通。

该来源批次最终完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 7 分 57 秒内通过：App 1,881 项 JVM 测试无失败或跳过，lint 为 0 errors、272 warnings、5 hints，Debug/Release 均构建成功。Workspace 的 Windows 宿主跳过项仍不算验收。保留既有记忆/数据隔离改造，未修改其他仓库；版本仍为 0.0.19 开发基线，不代表 0.0.20、正式扫码、Portal/媒体设备验收或真实平台互操作完成。

C6 的运行记忆已按域接通：MemoryAddress 固定 scope 与共享/助手 owner；MemoryService 统一编排原 Session、当前配置权限和 UI 记录上下文，Repository 持有真实 Room 事务直到提交或回滚结束。主助手、子助手、assistant_inspect、记忆编辑页和工具结果删除均使用原域；退出重登不能复活旧操作，共享模式切换不会把旧编辑写到新 namespace。企业 Seed 配置与运行记忆仍分开，没有新增配置副本或修改 schema。独立审查发现的旧查询终止订阅、子助手重取 Session、工具卡旧来源及授权拒绝后 lease 未释放均已修复并补充验证。

该记忆批次 87 项定向 JVM 测试全部通过；Pixel_10_Pro_Fold / Android 17 的 9 项定向 instrumentation 全部通过，涵盖实际 Room 的多主体/owner 隔离、错误地址拒绝、提交前取消回滚、提交决定后授权锁保持，以及域偏好和主子会话交互回归。删除了以假 DAO 重复 SQL 行为的旧 MemoryRepositoryOwnershipTest，替换为真实 Room 验证。会话列表/命令、文件、备份、正式企业入口和全部执行 adapters 仍未完成，本项不代表 C4、C5、C6 或 E08 整体验收通过。

该记忆批次完整 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1 --no-configuration-cache` 在 11 分 38 秒内通过：App 1,820 项 JVM 测试无失败或跳过，lint 无错误，Debug/Release 均构建成功；Workspace 的 11 项 Windows 宿主测试仍跳过。独立审查无剩余阻塞项。此批不修改版本号，不作为 Release 企业页面或真实平台互操作验收。

Feed 领域与持久发布已接通：完整文件和企业 manifest 使用 v2，配置内 Feed/HTML 与旧桥接示例删除；Feed 以独立主体引用、公开 revision 和内容摘要发布，草稿/发布/撤回及日期查询共用 EnterpriseFeed 规则。共享 feed-vectors.json 已执行实际查询，覆盖双边/单边日期、限额、空结果、错误范围和 DST 23 小时日；来源摘要固定在 contracts/portal/consumer-manifest.json。独立审查发现的新 Feed 指针提交前缺少内容校验已修复，并覆盖文件损坏、取消前后发布和重开。Pixel_10_Pro_Fold / Android 17 的 6 项定向设备测试通过，包含相同动态 ID 跨来源/用户隔离、退出重入保留撤回状态和旧 Session 拒绝。该批不代表 Portal 的 document/304/UI 验收。

Portal dist-local 已原样固定到 app/src/main/enterprisePortal，来源 sourceHash 为 7281c1f019b61eb3bc0bbe8dd439911bf466917191fe881d3586aa4601fc28c6，build-identity.json 摘要为 5e041b9fd791db5545c2d09cd4f9ce3e6aa4ff10e1fb2991a7fb3e79db2374ba。构建任务为全部 variant 校验并生成 assets，不依赖 sibling checkout。实际 Gradle 正例通过；篡改 index.html 后被摘要校验拒绝，随后精确恢复原始字节。独立审查无资源打包阻塞项；WebView、Bridge 和手机能力尚未实现。

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
