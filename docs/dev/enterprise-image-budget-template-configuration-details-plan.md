# 企业文生图、限额模板与配置详情执行方案

> 状态：已执行
> 日期：2026-09-20（2026-09-21 完成验证）
> 覆盖仓库：`measix-architecture`、`measix-platform-core`（含 Admin Console）、`measix-enterprise-portal`、`rikkahub_mcp`
> 目标：在保持单一当前协议和清晰 owner 的前提下，交付企业文生图可选下发、五类能力限额模板、Android 企业配置详情，并完成真实 UI 与跨端验证。

## 1. 基线与工作树

执行开始前核对结果：

| 仓库 | 分支 | 基线 | 初始工作树 |
| --- | --- | --- | --- |
| `measix-architecture` | `main` | `b94bdae` | 仅有用户已有未跟踪 `备忘.md`；不纳入本任务 |
| `measix-platform-core` | `agent/s0-platform-core` | `9dcc6e2` | clean |
| `measix-enterprise-portal` | `main` | `6355743` | clean |
| `rikkahub_mcp` | `master` | `58bea4fb2` | clean |

“各端先提交”按真实改动处理：三个 clean 仓库不制造空提交；架构仓库的 `备忘.md` 不是正式架构文档且归属不明，保持未跟踪。后续每个仓库在自身范围完成实现、验证和 diff 审核后独立提交。

## 2. 方案前真实 UI 核查

方案不是根据源码臆测。2026-09-20 已完成以下真实操作：

### 2.1 Admin Console

实际环境：`http://192.168.31.235:9100/admin`，当前已有真实 Provider、Model、TTS、ASR、MCP、企业助手和默认值。

已操作路径：

```text
企业配置 → 策略
用户 → 1111 → 用量额度 → 模型 → 编辑额度 → 有限额
```

观察：

- 企业配置使用左侧分区、右侧详情，图片生成适合放在“模型”之后成为独立资源分区。
- 用户额度当前是 MODEL/TTS/ASR/MCP 四个能力卡片，每卡只编辑周期、计量项、上限和审计原因。
- 当前不存在逐 `resourceId` 的编辑入口；用户确认这是期望的产品边界。保留 MODEL/TTS/ASR/MCP 四个大类，只随文生图增加 IMAGE_GENERATION 第五类，不增加逐资源操作。
- 限额模板适合复用现有 `DetailWorkspace`；用户页只负责模板指派和用户覆盖，不再复制一套规则编辑器。

### 2.2 Android

实际环境：API 37.1、x86_64、16 KB page-size `Pixel_10_Pro_Fold` AVD，安装包 `net.weero.measix.pilot`。

已操作路径：

```text
个人空间 → 空间 → 接入本机已有企业 → 示例企业 → 显示连接详情
```

观察：

- 当前“连接详情”只显示本机配置说明、当前生效版本和最近同步时间。
- 企业卡之后还包含导入企业配置、本地企业管理等长内容；把所有策略、默认值和资源继续内联会使页面过长。
- 最终采用独立“配置详情”页面；原企业卡保留同步、退出、地址编辑等连接动作，并提供一行配置详情入口。
- 配置详情使用总览 → 分类 → 单项的渐进披露，不在首屏展示稳定 ID、协议、prompt 或 route。

方案前截图与 UI hierarchy 位于本地验证目录 `build/reports/ui-inspection/before-plan/`，不作为产品源码提交。

## 3. 固定设计决策

### 3.1 当前协议与数据库

- Snapshot 保持 `schemaVersion=4`，Bridge 保持 v3，现有 API 保持 `/v1`；不通过升版规避同步修改。
- Core 继续只维护当前初始化 schema，不新增历史数据库 migration、双 schema reader 或兼容服务。
- Core schema 变更直接修改 Ent schema、当前初始化 SQL 和生成物。
- 本机真实 Core 数据在数据库结构操作前先备份；验证副本可执行审阅过的开发环境一次性 DDL，但该操作不进入产品运行时、仓库 migration 或长期兼容合同。
- Android Room/DataStore 不因本任务增加 migration；图片资源使用现有图片模型选择引用。

### 3.2 网络协议严格，持久化加法兼容

严格边界和持久化边界必须分开：

- `PlatformWireCodec` 继续拒绝未知字段、未知枚举、错误类型、非法引用、null 冒充缺失和未知 schema version。
- 当前版本中新增的集合或默认引用允许旧持久化载荷缺失：缺失 `imageGenerators` 唯一归一为 `[]`，缺失 `defaultImageGenerationId` 唯一归一为未设置。
- 新 Core writer 始终输出 canonical `imageGenerators` 字段；旧不可变 Release/Snapshot 不重写、不换 hash。
- Android 自有 `EnterpriseConfiguration` 持久化允许未知字段，并为新增字段提供默认值；解码后仍执行完整领域校验。
- `ENTERPRISE_MANIFEST_SCHEMA_VERSION` 保持 6。只有 envelope、身份、归属或安全不变量发生破坏性变化时才增加显式 storage migration。
- 用户 Settings 已使用 `ignoreUnknownKeys=true + encodeDefaults=true`；本任务保持现有 `imageGenerationModelId`，不新增第二选择字段。

这属于同一当前语义的加法兼容，不是两套 wire 协议、fallback 执行路径或无条件吞错。

### 3.3 文生图资源

文生图是独立 Managed Runtime Resource，不进入 Chat `ModelDefinition`：

```text
ImageGenerationDefinition
  imageId                 img_*
  displayName             non-empty
  clientProtocol          OPENAI_IMAGES_GENERATIONS
  upstreamModelKey        non-empty
  runtimePath             path-only
  maxImagesPerRequest     1..6
  allowedSizes[]          unique, non-empty
  enabled                 boolean
```

当前交付范围：

- 同步、非流式 Text-to-Image。
- `POST /images/generations`。
- 请求只允许 `model + prompt + n + size`。
- 响应接受 `data[].b64_json` 或 `data[].url`。
- 新增 `ResourceKind/BudgetCapability = IMAGE_GENERATION`、`ClientProtocol = OPENAI_IMAGES_GENERATIONS` 和 meter `REQUESTED_IMAGES`；图片配额可选按 `REQUESTS` 或 `REQUESTED_IMAGES` 计量。
- 企业默认值使用 `defaultImageGenerationId`；本地图片模型准入继续复用 `allowLocalProviders`。
- Android 查询投影可把 `img_*` 表示成 `Model(type=IMAGE)`，但 durable 企业定义保持独立资源类型。

当前明确不做：图片编辑、参考图、mask、multipart、partial image、stream、异步任务、Gemini interleaved content、Relay 供应商 body 转换。

### 3.4 五类能力额度与限额模板

额度目标保持“用户 + capability”模型，编辑 `MODEL / TTS / ASR / MCP / IMAGE_GENERATION` 五个大类。本任务不增加 `resourceId`、逐资源覆盖、资源选择器或多层优先级。

每个能力的唯一规则来源是：

```text
用户显式能力覆盖
→ 已指派模板的同能力规则
→ DEFAULT UNLIMITED
```

模板采用持续关联继承，而不是一次性复制：

- 每个用户最多关联一个模板。
- 模板修改后，所有未被用户显式覆盖的能力立即生效。
- 模板保存必须显示影响用户数、要求非空原因、revision CAS 和二次确认。
- 用户能力覆盖优先；清除覆盖后恢复模板，解除模板后显式覆盖保留，其余恢复默认无限。
- 删除仍被指派的模板返回稳定冲突，不级联解除。
- 相同 target + period 更新保持 scope key 和历史用量；改变 period 才建立新 scope。
- 模板、指派是配置真源；`UserBudget/BudgetLimit` 是 Budget owner 维护的执行投影，Relay 不解析模板。

Android Managed Snapshot 不包含模板。Client/Portal/Android 只读取五类同形的有效额度；Admin API 才展示 `TEMPLATE`、templateId/name/revision。Client 继续把非默认来源投影为现有“管理员配置”，不泄漏模板管理元数据；Client Budget 只加入 IMAGE_GENERATION 及其计量项，不加入模板字段。

### 3.5 Android 配置详情

原“显示连接详情”改为独立“配置详情”入口和页面：

1. 企业默认值：助手、聊天、快速、标题、文生图、附件检查、建议、压缩、TTS、ASR。
2. 使用策略：是否允许个人 Provider/TTS/ASR/MCP/Assistant。
3. 下发资源：Provider、Chat Model、Image Generator、TTS、ASR、MCP、Assistant、Starter、Memory Seed、Gateway。
4. 同步与诊断：状态、generation、最近成功同步、企业地址；离线时明确显示最近成功配置。

缺失显示“未设置”，失效引用保留 ID 并显示“引用不可用”，禁用资源显示禁用；不得静默替换。不得展示 credential、header、上游 URL、私密 route/binding、memory seed 正文、完整 system prompt 或原始 Snapshot JSON。

## 4. Owner 与依赖方向

| 事实 | 唯一 owner | 消费边界 |
| --- | --- | --- |
| Image 术语、ID、协议、安全语义 | Architecture | Core/Android 实现当前合同 |
| Image resource、release、binding、route | Core Capability/Runtime Control | Admin 只调 Admin API；Android 只接 Snapshot |
| 图片生成队列和媒体生命周期 | Android `ImageGenerationCoordinator` / `GeneratedMediaStore` | Relay 透明转发，不存媒体 |
| 模板、指派、五类有效规则、计数、审计 | Core Budget Service | Admin 写；Portal/Android 只读有效额度 |
| 配置详情投影 | Android application/query service | Compose 不直读 Store、Session、wire 或 execution |
| 企业 durable state | `EnterpriseAppliedStore` | UI 不持久化第二份摘要 |

禁止：

- 把 Image resource 直接并入 Chat Model durable owner。
- 在 Admin 前端计算最终预算或建立第二套预算执行器。
- 为额度引入逐资源编辑、资源级优先级或任何隐性双重扣量。
- 把模板字段塞入 Managed Snapshot、Portal 或 Android。
- 为配置详情另存 UI summary。
- 以模型名、host 或供应商品牌猜协议。
- 通过删除/重建 limit、解绑/重绑模板隐式清零。

## 5. 架构仓库修改

按 authority 顺序同步现有文档，不新增平行架构文档：

- `measix-platform-terminology-and-identifier-contract.md`
  - 激活 `img_*`；新增 `bgt_*` Budget Template stable ID。
- `measix-s0-capability-delivery-contract-spec.md`
  - 删除 Image Generation 非目标；纳入资源、发布、退出与 Admin 要求。
- `measix-s0-control-protocol.md`
  - 当前 Snapshot v4 的 image 集合、默认引用、runtime/budget 枚举和加法持久化规则。
- `measix-s0-usage-budget-closure-contract-spec.md`
  - 增加 IMAGE_GENERATION 类别与图片计量项；固定模板继承、能力覆盖、scope 和删除语义。
- `measix-s0-admin-console-product-requirements.md`
  - 图片资源编辑器、模板一级菜单、用户模板指派与五类能力覆盖交互。
- `measix-s0-android-integration-contract-spec.md`
  - 图片资源接收执行、配置详情和 storage/wire 严格性分层。
- Enterprise Realm/Experience、Control Hub、Runtime Relay、Upstream Adapter 相关合同。
- Admin、Hub、Relay、Android、Capability Delivery、Enterprise Realm、System testing specs。

验收：全库搜索不能再把 Image Generation 列为当前非目标；不能引入逐资源额度或模板字段下发；模板事实只能由一个 authority 完整定义。

## 6. Core 与 Admin 实现

### 6.1 OpenAPI 和生成物

按 `OpenAPI → fixtures → generated artifacts → tests → implementation`：

- Admin/Client/Relay/Usage OpenAPI 增加 Image resource、协议、额度能力和 `REQUESTED_IMAGES`。
- Admin OpenAPI 增加 Budget Template CRUD、assignment 和 capability override/clear API。
- Client Budget item 从固定四类扩展为五类；不新增 resource target 或模板管理字段。
- 重新生成 Go wire、Admin TypeScript、Android export、Ent、canonical fixtures。

### 6.2 Capability / Runtime / Relay

- `platformid` 增加 `img` 和 `bgt`。
- Capability owner 完成 Image ID、重复、default、enabled、size、count、binding、diff、preview、hash 校验。
- Runtime Control 编译 Image route：POST、`HTTP_REQUEST_RESPONSE`、精确 `runtimePath`。
- Relay 校验 `img_* + IMAGE_GENERATION + OPENAI_IMAGES_GENERATIONS`，透明转发。
- Image request observer 只解析有界 JSON 的 `n`；不运行 LLM observer、不缓冲/解析图片响应体。
- `REQUESTS + REQUESTED_IMAGES` 用于准入；无法可靠解析且存在图片张数限额时 fail-closed。

### 6.3 Budget 数据模型

当前 schema 直接调整，不新增历史 migration。建议结构：

- `BudgetTemplate`：`bgt_*`、name、normalized name、description、canonical definition、revision、审计元数据。
- `BudgetTemplateAssignment`：user unique、template、revision、actor/time。
- Template/Assignment audit 独立于 reconciliation audit。
- `UserBudget` 保持 `(user_id, capability)` 唯一语义，增加 `source/source_template_id/source_template_revision`。
- Limit 和 bucket 保留历史与在途引用；模板执行投影由同一个 Budget transaction 维护。

模板 definition 可使用严格 canonical JSON，只有 Budget Service 编解码和验证；runtime admission 不直接解释 JSON。模板/assignment 更新事务内重算关联用户的 `source=TEMPLATE` 执行投影，显式覆盖不被覆盖。

### 6.4 Admin UI

导航顺序：

```text
用户
限额模板
企业配置
```

模板页复用 `DetailWorkspace`：

- 左侧搜索、模板名、指派人数、revision、更新时间。
- 右侧名称/说明、MODEL/TTS/ASR/MCP/IMAGE_GENERATION 五张能力规则卡和审计。
- 共享 `BudgetRuleEditor`，用户页与模板页不能复制 period/meter/validation 逻辑。
- 不出现资源选择器、资源搜索或资源级覆盖。
- 保存有关联用户的模板前展示影响人数和“不清零历史用量”；reason 必填。

用户详情额度页：

- 顶部“限额策略”卡展示默认/模板/用户覆盖。
- 模板选择使用分页实体选择器；应用、更换、解除均有差异预览和审计原因。
- 生效额度与个性化覆盖分区；继承项操作名为“创建用户覆盖”，显式项可“恢复模板”。
- 继续以五张能力卡显示，一次只打开一个规则编辑器。

图片资源页放在 Model 后：名称、上游模型 key、固定协议、runtime path、最大张数、允许尺寸、enabled、upstream binding。Policy 增加默认文生图资源。

## 7. Portal 实现

- 重新生成 Client API 类型。
- Usage/Budget 支持 `IMAGE_GENERATION`、`OPENAI_IMAGES_GENERATIONS` 和 `REQUESTED_IMAGES`。
- 页面按五个 capability 显示有效额度，不显示 templateId/name/revision，不提供管理动作。
- 保持 Portal 仅使用本人受限 API，不访问 Admin API。

## 8. Android 实现

### 8.1 Wire / enterprise owner / resolver

- 当前 Snapshot v4 增加 optional `imageGenerators` 和 optional default；mapper `.orEmpty()`。
- `PlatformWireCodec` 保持严格。
- 新增 `EnterpriseImageGenerationResource`、`EnterpriseResourceKind.IMAGE_GENERATION`。
- `EnterpriseConfiguration.imageGenerators=emptyList()`；本地 codec `ignoreUnknownKeys=true`，随后完整校验。
- resolver 把企业 image resource 投影进现有统一图片模型目录；stable ID 仍为 `img_*`。
- `allowLocalProviders=false` 只列企业图片资源；true 时同时列个人图片模型。
- 显式失效选择保持不可用，不静默替换。

### 8.2 图片能力、执行和安全

将单一布尔值扩成 typed capability：

```text
canGenerate
canEdit
maxImagesPerRequest
allowedSizes
```

- managed Image：generate=true、edit=false；限制来自企业定义。
- 企业域中的个人图片模型仍保留其个人 edit 能力。
- `ModelExecutionService` 按 role 分别解析 Chat Model 和 Image Resource，冻结 realm/generation/binding；队列等待后重新验证。
- managed 请求不能被个人 Provider、custom header/body 覆盖；reserved keys 包含 `model/prompt/n/size`。
- URL 结果仅 HTTPS、无 userinfo、重定向重验、不转发 Authorization/Cookie、bounded stream、MIME+magic 校验，成功后立即交给 `GeneratedMediaStore`。

### 8.3 配置详情

- `EnterpriseApplicationService` 输出 immutable `EnterpriseConfigurationDetailsUiModel`。
- `EnterpriseVM` 只消费 application/query port；Compose 不直接依赖 raw `EnterpriseConfiguration`、Store、Session 或 Execution。
- 新建全屏详情页面：总览卡为默认值、策略、资源、同步诊断；资源类别进入二级列表，单项技术信息再次渐进披露。
- 默认值逐项区分“已设置且可用 / 未设置 / 已设置但引用不可用”；未设置不能以目录首项或名称匹配补齐。
- 地址编辑仍留在企业连接卡；详情页地址只读可复制。
- READY/OFFLINE+LKG 可展示，PENDING/退出不可展示；Session/generation 更新立即替换投影，迟到旧结果不污染。
- 五套常用语言资源同步。

### 8.4 动态预算查询

- Client Budget 不接收模板管理字段。
- 支持第五种 `IMAGE_GENERATION` capability 及 `REQUESTS/REQUESTED_IMAGES` 计量。
- 个人 Provider 的同名 429 不进入企业预算状态机。

## 9. 测试与验收矩阵

| ID | 场景 | 必需证据 |
| --- | --- | --- |
| BASE-001 | 基线和工作树范围 | SHA、status、`备忘.md` 未提交 |
| ARC-001 | Image 不再是非目标 | authority diff + 冲突搜索 |
| ARC-002 | 五类能力与模板继承唯一，无逐资源层 | Usage contract + Admin PRD |
| IMG-001 | Admin 创建、绑定、验证并发布真实 `img_*` | production Admin 实际操作 + Hub 状态 |
| IMG-002 | Snapshot v4 有/无 image 字段均按固定语义读取 | fixtures + Core/Android tests |
| IMG-003 | Android 企业默认、手选和 mixed catalog 正确 | resolver/execution tests |
| IMG-004 | 真实 Core/Relay/供应商生成并落入媒体库 | 实际请求、媒体记录、UI |
| IMG-005 | managed 禁用编辑，个人 edit 不回归 | Compose test + 实际操作 |
| IMG-006 | Relay 不翻译、不存图片、不泄漏 credential | Relay/security tests |
| BGT-001 | 用户能力覆盖 > 模板能力 > 默认，不双扣 | Budget SQLite tests |
| BGT-002 | 模板 CRUD、CAS、审计、关联冲突 | API/DB tests |
| BGT-003 | 模板实时影响继承用户，显式覆盖不变 | transaction/concurrency tests |
| BGT-004 | 同周期不清零、改周期新 scope、解绑重绑不清零 | clock/bucket tests |
| BGT-005 | 清除覆盖恢复模板，解除模板保留显式覆盖 | service/API tests |
| BGT-006 | 删除被引用模板拒绝；资源失效可诊断 | API + Admin UI |
| BGT-007 | template 元数据未进入 Snapshot/Client/Portal/Android | contract/static tests |
| BGT-008 | Image 请求的 REQUESTED_IMAGES 准入、耗尽、恢复 | Hub/Relay/Portal/Android chain |
| PST-001 | 上一版真实企业缓存可读取 | frozen old fixture |
| PST-002 | additive field 不升 manifest、不清 LKG | store recovery tests |
| PST-003 | wire 未知字段仍 fail-closed | PlatformWireCodec tests |
| CFG-001 | 详情展示全部默认值、策略和资源类别 | projector + Compose tests |
| CFG-002 | OFFLINE/LKG、同步失败、失效引用准确 | state tests + UI |
| CFG-003 | 不显示 secret/route/prompt 原文 | projection/static/security tests |
| CFG-004 | 切域、退出、同步迟到不污染 | coroutine/realm tests |
| CFG-005 | Admin 设置/清空每个 Policy 与默认值后，Preview、Snapshot、Android 详情及 resolver 一致 | contract tests + production browser + emulator |
| ADM-UI-001 | 模板创建编辑、用户指派、覆盖恢复、冲突 | production browser 操作 |
| ADM-UI-002 | 桌面/窄屏、loading/empty/error/assigned | Playwright + 实际截图 |
| AND-UI-001 | 配置详情在手机/折叠屏可读 | emulator/真机操作 |
| AND-UI-002 | managed 图片模型、数量尺寸和错误 | connected test + 实际操作 |
| POR-001 | Portal 显示 Image capability 额度 | unit + production E2E |
| OWN-001 | 无跨 owner 直连或第二事实源 | 静态契约审查 |
| REV-001 | 四端独立子代理审查无未解决高优先级问题 | 审查报告 + 修复提交 |
| FINAL-001 | 固定 Core/Portal/APK 完整真实链 | 一次端到端证据 |

## 10. 执行顺序与提交

1. 本文落地并提交 Android 计划基线。
2. Architecture：修改全部 authority/testing 文档，冲突搜索和 diff 审核后提交。
3. Core/Admin：OpenAPI、fixtures、generated、Red tests、实现、Admin UI、真实浏览器验证后提交。
4. Portal：生成类型、功能、测试和 production UI 验证后提交。
5. Android：wire、storage、resolver/execution、配置详情、预算查询、测试和设备操作后提交。
6. 固定同一组 Core/Portal/APK，使用真实已配置资源完成图片发布、同步、生成、预算和详情联调。
7. Architecture、Core/Admin、Portal、Android 分别使用独立子代理只读审查；修复后重跑受影响门禁并分别提交。
8. 最终跨端 owner/协议/冗余审查和工作树审计。

每个实现仓库执行定向 Red/Green 后再跑风险匹配的集中门禁：

```text
Core/Admin:
  node scripts/checks.mjs generate
  node scripts/checks.mjs drift
  go test ./... -count=1
  go test -tags=smoke ./test/system/scenarios/ -count=1 -timeout 5m
  pnpm -C console typecheck
  pnpm -C console test
  pnpm -C console build
  production Playwright/browser scenarios

Portal:
  pnpm generate:api
  pnpm typecheck
  pnpm test
  pnpm build
  production E2E

Android:
  gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
  gradlew.bat connectedDebugAndroidTest --no-parallel --max-workers=1
```

构建/JVM 通过不代表设备、真实 Core、真实 Provider 或 production UI 验收通过。所有仓库交付前必须执行 `git diff --check`、最终 diff、生成物漂移、测试报告和工作树检查。

## 11. 执行结果

本次交付保持单一当前合同：Snapshot 仍为 v4，Bridge 仍为 v3，API 仍为 `/v1`；Core 直接更新当前 Ent schema、初始化 SQL、OpenAPI 和生成物，没有新增数据库 migration、协议升版、兼容 reader、运行时 fallback 或双写。Android 也没有增加 Room、DataStore 或 manifest migration。

已完成：

- Architecture 已移除 Image Generation 非目标，并固定独立图片资源、五类 capability budget、模板实时继承与无逐资源预算层。
- Core/Admin 已实现图片资源发布与 Relay 路由、`REQUESTED_IMAGES` 计量、Budget Template CRUD、指派与覆盖，以及当前 schema 和生成合同；真实生产构建的 Admin 浏览器流程已验证。
- Portal 已按第五类 `IMAGE_GENERATION` 展示有效额度，且没有接收模板管理元数据；真实生产构建浏览器流程已验证。
- Android 已接收可选 `imageGenerators`，按 realm 解析个人与企业图片模型，冻结执行能力并在 Provider I/O 前校验数量、尺寸、编辑和 partial image；企业 URL 图片下载采用隔离 transport 和 HTTPS、DNS、重定向、大小、MIME、签名校验。
- Android 配置详情已实现默认值、策略、资源分类、单项详情和同步诊断的渐进披露，不展示 secret、route、prompt、内部 ID 或原始协议；只展示当前活动企业域的 READY/OFFLINE 最近成功配置。
- 五套 Android 常用语言资源、静态契约、架构参考和测试同步完成。

最终验证证据：

| 端 | 结果 |
| --- | --- |
| Core/Admin | 生成物、漂移、Go test/vet、smoke、Console test/typecheck/build、公开来源检查、真实生产构建浏览器 E2E 全部通过 |
| Portal | API 生成、typecheck、11 个测试文件共 87 个用例、build、format、真实 Core + production Portal + Chromium E2E 全部通过 |
| Android JVM/构建 | `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 通过，827 tasks，10m37s |
| Android 设备 | Pixel 10 Pro Fold API 37：Enterprise 页面 14/14；完整 `connectedDebugAndroidTest` 215 个测试、9 个既有 live/PRoot 前置条件跳过、0 失败；折叠屏半开态配置详情地址与复制场景单独通过 |
| Android 合同 | `python tools/generate-enterprise-wire.py --check` 通过；Snapshot 有或无图片字段、严格未知字段、resolver、execution、coordinator、UI projection 和安全下载测试通过 |

边界：本轮没有在 Android 上使用真实外部图片供应商 URL 完成一次生成并落入媒体库，因此 `IMG-004` 和 `FINAL-001` 中“真实供应商生成”这一段不作为已验收项；其余 Core/Portal 真实浏览器链、Android 设备链和传输安全、媒体 owner 自动化验证均已完成。真实供应商联调需要可用的企业图片 binding、凭据和上游服务环境，不通过 mock 结果冒充。
