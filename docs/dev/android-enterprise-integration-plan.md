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

Gateway 是独立受管资源，不出现普通 MCP URL/Header/OAuth 编辑器。`REQUIRED` 强制整对工具开启；`USER_CONTROLLABLE_DEFAULT_ON` 默认开启且可成对关闭。偏好按 `(deploymentId, toolGatewayId)` 保存；REQUIRED 保留旧 false 但不生效，恢复可控时恢复。变更只影响新执行。

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
- Workspace 为用户显式选择的共享空间，目录不随切域复制或清空，UI 标明共享。共享目录不等于内容隔离；`/upload` 及会话资源挂载必须按调用主体限制，不能暴露整个个人上传目录。Skill/字体属于选择使用的共享配置资产。
- 用户配置与偏好一次 DataStore edit 迁移；SharedPreferences 读取在提交前，完成标记同事务，旧 key 清理幂等。跨 Room/文件升级由启动恢复协调器排序；未完成不开放新写入，重启可恢复。成功后删除旧读写路径。
- 提供显式 Room migration、fresh schema 同构和真实历史升级测试，覆盖复杂引用、tombstone、凭据和用户值。Room 版本、App 20、本地资料 schema 不混淆。
- 普通备份含用户配置、公用/个人偏好和个人数据，不含企业凭据/企业配置/企业数据。恢复在 staging 只替换个人闭合子图并保全企业图，校验主键/路径冲突后走既有发布，不全库覆盖。
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
| 工作台 | 本地 HTML 信息/动态、Starter、相机/录音；关闭仅关闭网页 |
| 示例企业配置场景 | 本地企业服务管理：五项策略、资源变化、故障/过期/撤销/恢复，不是普通用户绕过真实 policy 的入口 |

初始/升级进入 PERSONAL，不自动接入。体验示例企业走同一接入校验和提交，不能直接赋值 READY。扫码/粘贴共享 bounded typed 解析器，资料含独立 format/source/deployment/测试接入材料。未知、不完整、过期或错误资料不留下半绑定。

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

交付完整无秘密模板 `docs/examples/enterprise.local.example.json`，根目录 `/enterprise.local.json` 加入 Git ignore。用户可修改私有文件提供真实端点/凭据；不打印秘密，不进源码/测试/提交。模板和示例不得只包含空占位资源。

本地文件使用独立 `formatVersion`，包含 source/deployment/user、完整五项 policy、资源/助手/seed/starter/gateway/动态，以及独立 `runtimeBindings`。endpoint/credential 是本地 source 私有实现，不属于平台 client-safe Managed Snapshot，不混进用户资源配置。

通过“导入本地企业配置”系统文件选择器读取，限制输入大小并验证格式、ID/引用、URL 和绑定完整性，全部成功后原子应用。按明确稳定 ID 更新，不按名称。换 source/deployment/user 要先退出再接入；同主体更新保留历史。错误包无半提交。

凭据导入后由独立受保护 binding store 持有，公开配置只含引用，不进普通备份、日志、Portal 或 crash detail。默认不把私有文件编译进 APK；用户重新导入即可更新，无须改代码/重新构建。最终交付提供不进 Git 的私有文件位置与操作说明。

整包发布由企业应用状态提交协调者负责：先暂存不可变的定义、binding revision 和必要资源，校验闭合关系；最后一次原子持久提交 manifest 指针，包含完整 source/principal、配置 generation 与 binding revision。此提交是可见状态的唯一发布点；Session owner 只有在该主体的 manifest 可恢复后才发布企业可用状态。崩溃恢复只接受完整已提交组合，未提交资料回收；旧 binding 由在途 lease 持有，最后引用释放后清理。退出先撤销 session 执行资格，再按同一恢复协议移除活动 manifest/凭据，不允许配置已换而仍调用旧凭据。

## 8. Portal 与手机能力

随包本地企业 HTML 使用专属受信 origin，只给专用企业宿主 bridge；普通外链/模型生成 HTML 不能取得企业权限。正式宿主管 document/session，后续换远端来源不换业务 owner。

本期 typed bridge 使用 requestId/method/params 与成功/typed error，支持状态、关闭、刷新、退出请求、批准外链、拍照、录音开始/停止/取消。退出调用原生命令和确认。没有直接写 Settings/Room、读取 Refresh Credential、任意文件路径的能力。

每次请求绑定当前顶层 origin、document identity、企业主体和 session。导航/关闭/切域/失效/退出立即取消采集、丢弃迟到结果；并发采集明确 busy/cancel，不后台静默录音。相机真实拍摄、录音真实采集，拒绝权限可解释可恢复。

媒体由原生 owner 管理，bridge 返回受限句柄/元数据，通过受限资源访问预览，不暴露宿主路径/无限 base64，不自动上传。未交接资源取消后回收，保留到会话经 Artifact 原协议。原生扫码在登录前可用，一键/扫码/粘贴共用接入验证。生产 grant/Cookie/CSRF 只在后续真实接入规定，本地会话不冒充生产授权。

## 9. 执行、更新、恢复不变量

- 扩展 TurnContextFactory、TurnToolSetFactory、transport lease 和既有 Conversation/Turn/Step owner，不建企业运行栈。
- 新企业动作检查当前 source/session/五项准入；本地 source 在本机提供状态，无互联网也 READY。用户资源同样受企业调用前准入。
- 捕获域/主体、用户 revision、企业 generation、资源身份、endpoint/binding、prompt/工具；后续 Step 不重读全局当前选择。
- 收紧/退出与外部副作用承诺有统一线性化门禁；旧捕获在下一请求/调用前复验，不合规则终止，不换模型、不重放。已经承诺的调用保存已知结果或 unknown，不谎报未执行。
- MCP Catalog/OAuth、异步标题、自动 TTS、附件识别、子助手均带原 scope。共享用户凭据刷新只写用户 owner。
- 企业候选失败只影响企业 readiness；个人不能因企业网络/配置错被全局锁死。共享 DB 真实损坏仍按恢复协议拒绝 Ready。
- 移除旧签名 envelope/global merge/path lock 和无消费者 facade；原型文件不迁成正式身份；仅保留真实历史迁移需要的解码边界。

## 10. 完整变更清单与批次

D0 已由 `c2745a1d` 独立提交完成。C1/C2 正在实施：用户配置/偏好文档及旧键迁移、跨模块配置引用已进入代码；正式企业 source、域数据隔离、UI 与完整模拟能力尚未完成。下表保留全部交付范围，单项基础测试通过不代表 C1/C2 或整期完成。

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
| U3 | Portal 宿主/HTML/动态、相机/录音/扫码/受限媒体句柄 | 真机权限、取消、结果与来源隔离 |
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
