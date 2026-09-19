# Android 与 Core 真实企业接入执行方案

> 状态：实施中。2026-09-18 根据当前 architecture、Core 与 Android 源码核对形成；本文不是已完成或验收声明。
> 目标：用户扫描 Core 管理台二维码，或粘贴其生成的完整接入 JSON，确认平台地址后，正式接入企业；已发布快照必须驱动实际聊天、工具、语音与工作台功能。
> UI 原则：尽量与个人空间的呈现风格和逻辑一致。允许新增一处接入来源确认；企业语音列表统一复用个人类型呈现，不设计企业专用摘要、编辑器或页面。发现其他必要交互变化时，先与用户讨论。
> 2026-09-19 范围调整：远端 Portal 尚未交付，用户要求先不要适配。本文原有远端 Portal 步骤保留为待后续启动的清单，本轮完成标准不包含远端 Portal 接线；已有本地 Portal 与退出清理继续保持可用。

## 1. 范围、基线与交付定义

### 1.1 本轮要交付什么

扫码/粘贴 → 本地解析 → 确认平台来源（HTTP 或 HTTPS）→ Discovery → Enrollment → 安全保存会话 → Bootstrap → Managed State → Snapshot 校验与原子应用 → 现有企业空间实际执行。

HTTP 与 HTTPS 均为正式支持方式，包括局域网域名、IP 地址及显式端口；Debug/Release 行为一致。不要求 HTTPS、域名、证书或 loopback 开发开关，不设 IP/主机白名单，不增加 HTTP 专用确认或设置。继续使用已同意的一处平台地址确认，展示实际 scheme、host、port。

本轮包括四种模型协议、四种 TTS、四种 ASR（含 Core 新增 DashScope HTTP）、Direct MCP、企业助手/Seed/Starter、五项用户资源准入、配置同步、会话刷新、撤销/退出/进程恢复。远端 Portal 与 Feed 暂缓。不能以“JSON 能解析”“资源能显示”“模拟服务成功”作为完成标准。

已有企业域实现保留：ConfigurationResolver、Settings 用户偏好、Conversation/Turn、Artifact、GeneratedMedia、运行 Memory、MCP Catalog/runtime、Speech 播放录音 owner、Portal 原生媒体与退出清理。用户自有资源仍使用自己的配置与凭据；受管配置不复制进个人 Settings。

不纳入：Gateway/Snapshot v5、企业动态的模型工具投影、企业下发子助手关系、企业独立图片生成/Embedding 协议、User Sync、受管 Skill 发布、远端 Agent Runtime、服务器保存 Android 会话。现有个人能力不因此删除；企业空间只按现有政策和实际资源能力使用它们。Direct MCP 的模型工具调用属于本轮，不能与未来通过 Gateway 查询企业动态混为一谈。

最新 Foundation Contract §3 已明确：当前 S0.1/S0.2 v4 可以在 Gateway 实现前正式适配与端到端联调。Hub/Relay 不存在“未 Freeze 禁止运行”的开关，客户端不能制造 Gateway 占位资源或额外 readiness 门禁。源版本与证据仍须固定，但等待未来阶段 Freeze 不是开始本轮工作的条件。

### 1.2 本次核对的来源

| 仓库 | 检查基线 | 状态与含义 |
| --- | --- | --- |
| Android | `5de1b084516798ee8eda971ff3340c5608dc95cc` | 文档编写前工作树干净；已有企业域、本地来源及私有配置导入 |
| architecture | `de2f60fda521a82c45eff9f8bd6e5e47fc30a6c2` 加工作区修改 | 已提交基线之外扩展模型/语音协议；不是已发布版本 |
| Core | `35010631f1cec0a0e9afe1e181c2e1f13c3d2817` 加工作区修改 | 当前分支 `agent/s0-platform-core`；新增协议、编译器、Relay 与测试在工作区 |
| Core Android 集成包 | `sourceHash=950a41fa33b94148095894a1f1962faeb21e5e2035c31aa37baa5e1c87d90371` | 最新完整交付包内含的 72 个内容文件；已运行 verify.mjs 校验通过 |
| Portal 完整交付包 | `.artifacts/android-integration-RxyrgW`；`sourceHash=9c1c6e12daf2a287f2924403c6bfa94068e42de871a1b86aa6128c32eb0847be` | 150 个文件逐项 SHA-256/集合校验通过；内含 Core 资料、架构文档、Portal local 资源与说明 |
| Portal 构建来源 | `ffe296fc325914f7954c121d5ff72538aed914d7`，`dirty=true` | Bridge 3/local-read 2；此候选包含工作区变化，不能标为 clean-source release |

当前组合：Enrollment `formatVersion=1`、Discovery `protocolVersion="1"`、Snapshot `schemaVersion=4`、Portal Bridge `3`、local-read `2`。Android 本地完整导入包 `formatVersion=2` 与这些版本无对应关系。

实现启动时重新固定源提交、工作区差异、OpenAPI/fixtures/导出摘要；允许基于明确候选开发，不允许把候选冒充冻结版本。最终联调证据必须对应实际构建源。上游工作区若继续变化，先复核差异再更新本表。

本次新资料复核结果：完整包内 Core 接入说明与展开后的 Client OpenAPI 与当前 Core 原文件字节一致；Portal local assets 和 8 份 native 导出合同校验通过。外层 sourceHash 沿用 Portal 构建源摘要，文件完整性由 artifacts 表逐项验证，不能把它与内层 Core sourceHash 当成同一个摘要。

用户随后明确要求正式支持局域网/IP HTTP，此要求优先于当前交付包的 HTTPS/loopback 限制。上述摘要只证明本次核对的文件版本；当前包尚不满足这一地址要求。实施时需同步修正 architecture/Core 的约定、校验和共享用例，再重新生成 Core/Portal 交付包并记录新摘要，不能只放开 Android 后宣称端到端 HTTP 已可用。

Android 现有内嵌 Portal 的 sourceHash 为 `b29533489820a58eb489d3df3c9f2e09245e413988a40fff6ccae0ea0936049c`，不是这份新包。实施时通过交付链更新资源/构建身份与合同摘要；即使 JS/CSS 文件名未变，也不能以名字相同跳过验证。本轮只编写方案，尚未替换内嵌资源。

### 1.3 权威与参考入口

- 本仓库：[总架构](../references/application-architecture.md)、[配置架构](../references/android-configuration-architecture.md)、[助手配置](../references/assistant-configuration.md)、[协议](../references/protocol-reference.md)、[MCP](../references/mcp-architecture.md)、[UI](../references/ui-architecture.md)、[测试](../references/testing-strategy.md)。
- 历史交付边界：[0.0.20 企业域实施方案](android-enterprise-integration-plan.md)；原先明确排除真实 Hub/Relay 认证与下发，不能把此缺口归因于本次语音扩展。
- 上游：[architecture README](../../../measix/measix-architecture/README.md)、[Control Protocol](../../../measix/measix-architecture/docs/10-runtime-foundation/s0/measix-s0-control-protocol.md)、[Android 合同](../../../measix/measix-architecture/docs/10-runtime-foundation/s0/measix-s0-android-integration-contract-spec.md)、[Core 接入说明](../../../measix/measix-platform-core/docs/android-platform-integration.md)。
- 可执行结构：[Client OpenAPI](../../../measix/measix-platform-core/api/generated/android/client-control.openapi.yaml)、[接入样例](../../../measix/measix-platform-core/api/fixtures/enrollment/cases.json)、[集成样例](../../../measix/measix-platform-core/api/fixtures/client-integration/cases.json)。
- 用户指定的最新完整包：[manifest](../../../measix/measix-enterprise-portal/.artifacts/android-integration-RxyrgW/manifest.json)、[Portal 交接](../../../measix/measix-enterprise-portal/.artifacts/android-integration-RxyrgW/measix-enterprise-portal/docs/android-alignment-handoff.md)、[Portal 接入](../../../measix/measix-enterprise-portal/.artifacts/android-integration-RxyrgW/measix-enterprise-portal/docs/android-integration.md)。临时目录用于此次证据定位，正式测试输入应随固定摘要保存，不依赖该目录长期存在。

已发现上游文档冲突：Control Protocol §10.2 同时将 `ANTHROPIC_MESSAGES` 列入已支持与尚未纳入。当前 OpenAPI、compiler 测试和 Claude fixture 一致支持它。本轮按明确的四模型范围制定计划；正式固定合同前需要上游修正文档，不在客户端增加兼容分支掩盖冲突。

原 [真实企业服务接入规划](android-enterprise-production-integration-roadmap.md) 中包含未来 Gateway 的总体路线。本轮具体范围、执行步骤和验收以本文为准，不把 S0.3/S0.4 整阶段完成作为本轮交付声明。

## 2. 实现差异与改动定位

以下 Android 文件以 `app/src/main/java/net/weero/measix/pilot/` 为根；Core 文件以其仓库根为准。符号是核对入口，不表示已经实现计划中的新增行为。

| 领域 | Core 已有实现 | Android 现状 | 本轮改动 |
| --- | --- | --- | --- |
| 接入资料 | `console/src/api/enrollment.ts::encodeEnrollmentMaterial`；UsersPage 同一字符串复制/生成 QR | `EnrollmentMaterialParser` 已解析平台格式；`LocalEnterpriseSource.enroll` 拒绝平台来源 | 应用入口按来源分派；平台来源确认后执行独立平台 I/O |
| 身份/认证 | `identity/service.go::ExchangeEnrollment`、`refresh.go::Refresh`、`httpapi/handler.go` | `EnterpriseSessionController.enrollLocal` 生成本地 Session/期限 | 保留服务端 ID/期限，增加安全认证记录与单次刷新协议 |
| 配置 | `httpapi/client_snapshot.go`、`capability/snapshot.go`、`managedStateWire` | `EnterpriseSynchronizationService` 固定读取 LocalEnterpriseSource；AppliedStore prepare 接受本地包 | 平台 DTO/校验/映射进入同一 Applied 发布协议，来源 I/O 与内部候选分开 |
| 模型 | Provider 四协议、Model 完整路径，Relay 透明转发 | `ModelExecutionService.enterpriseTarget` 把 binding endpoint 作为 Provider baseUrl，凭据按供应商头发送 | 增加明确的请求级完整 endpoint/平台认证输入，复用现有协议编码器 |
| TTS | `capability/service.go` 校验四协议；Snapshot 保留条件字段 | 企业定义强制 modelId/voice；`EnterpriseSpeechTransport` 仅 OpenAI MP3 | typed 语音定义与执行分派，复用个人语音编解码和播放 |
| ASR | `capability/asr.go`；Relay 显式 WEBSOCKET 升级 | 企业一律 `HttpAsrController`；个人 `RealtimeAsrController` 使用用户端点/凭据 | 按协议选择控制器，录音参数、平台握手与父执行上下文显式传入 |
| MCP | `authOwnership=ENTERPRISE_MANAGED/NONE`、资源路由、会话透传 | `McpConnectionDefinition.Managed` 从私有 binding 取地址/凭据，digest 包含 binding 内容 | 稳定公开定义与短期凭据分离，现有连接/Catalog/执行 owner 不变 |
| Portal | `identity/portal.go` grant；`httpapi/portal.go` form POST、Cookie、303 | `PortalWebView` 禁网络并固定 local origin；`PortalDocument` 固定本地来源与 local-read | typed 本地/远端宿主输入；远端 Cookie 会话、受信 origin、既有 Bridge 与清理 |
| UI | Core 提供完整 JSON 和二维码 | 扫码直接 join；企业语音摘要固定 modelId/voice | 仅新增来源确认；语音列表沿个人类型标签与现有卡片统一呈现 |

## 3. Owner 与内部设计约束

### 3.1 唯一发布链

```text
EnterpriseApplicationService：接入/同步/打开工作台等应用命令
  → 平台 I/O（Discovery、认证、Managed State、Snapshot）
  → 纯 DTO 校验与 typed 映射
  → EnterpriseSessionController：原 Session/操作令牌复验、串行提交
  → EnterpriseAppliedStore：不可变文件暂存 + 原子 manifest
  → ConfigurationResolver / query UiModel / 原有执行入口
```

- 新增平台 client 与纯 Snapshot mapper；它们不拥有第二份生效配置，不直接写 Settings/Room。
- `EnterpriseSessionController` 继续拥有身份、会话、认证轮换的持久提交与 Applied 状态；`EnterpriseAppliedStore` 承担磁盘/加密载体。平台 client 仅做传输，不缓存另一份 durable Session。
- 将 AppliedStore/SessionController 的输入从“本地包”抽离为内部已验证候选。LocalEnterpriseSource 与平台来源分别产出该候选；本地完整包仍只属于本地导入协议。删除平台不能接入的旧分支与无调用的过渡 API，保留仍在用的本地示例来源。
- Session 写锁不包住 HTTP、长录音或流式运行：锁内捕获 Session/操作令牌，锁外 I/O，锁内 CAS 验证与提交；退出/重登后迟到响应不得重新建立原 Session。
- 同步、刷新、接入使用各自必要的在途操作合并；不增加定时轮询、通用任务框架或第二全局状态机。网络等待取消向上传播；只有已取得提交所有权的短提交/补偿允许 NonCancellable。

### 3.2 生效事实与运行输入

- Provider 关联、启用状态、协议、Model runtimePath、语音条件字段、MCP authOwnership 都属于企业已验证配置，必须保留，不能显示后丢弃。
- 平台资源目标只包含可信平台定位、稳定资源 ID、完整路径、协议与冻结 generation。服务端 RuntimeBinding、Upstream、Secret 不下发，也不在 Android 构造假的上游 binding。
- 系统 TTS 是设备执行的企业资源，不属于远端资源目标。资源目录可以包含它，但“必须存在远端 binding”的集合不能再覆盖全部 TTS。
- 每个新顶层执行捕获 immutable context；主 Turn 的后续 Step、工具和明确的子操作复用父上下文。独立朗读、ASR、MCP、辅助生成各有自己的执行身份，不用全局 currentInteraction。
- `ai`/`speech` 不依赖 app 企业类；通用请求参数允许携带明确的完整地址、认证/请求头与错误处理边界，企业决策仍在 app。禁止复制四套企业 Provider 或企业 ASR/TTS 引擎。

### 3.3 持久化与恢复设计

实施前 manifest schema 为 3，只记录本地 Session 和配置/binding 摘要；没有真实 refresh 凭据或 release/snapshotHash。不能仅补内存字段后宣称重启可用。

计划扩展同一 manifest 的来源类型、平台身份/发现结果、认证版本引用、待完成轮换引用和平台 Applied 元信息。设计采用不可变认证记录/配置文件先写并同步，再由单一 manifest 指针发布；凭据文件以 Android Keystore 支持的加密封装保存在 noBackupFilesDir 下。加密工具不成为 Session owner。

- Access token 只保留内存；refresh token、其期限、sessionIdleExpiresAt、pending refresh key 及恢复所需旧 token 安全持久化。重启通过原认证记录刷新，不复用过期 access token，也不要求重新扫描仍有效的 Session。
- 身份/认证写入是一个提交；刷新成功时新 refresh 与服务端期限一起提交，之后才能开始下一次轮换。磁盘失败保留原恢复事务，不能发布内存假成功。
- Enrollment 成功但 Bootstrap/Snapshot 尚未完成时，先持久保存服务端认证身份与待初始化状态；不得因后续下载失败丢掉已兑换凭据。显示名只有 Discovery/Bootstrap 提供，不能伪造用户身份；未完成 Bootstrap 不允许执行。恢复继续 Bootstrap/同步，复用现有待配置/错误反馈入口。
- 已应用 generation/releaseId/snapshotHash 与本地文件完整性摘要分开保存；不能拿本地序列化 hash 冒充服务器 snapshotHash。
- Room 和 UserSettingsDocument 不因平台接入而新增平行资源副本。企业下发从未上线，只保留当前企业协议和文件格式，不新增旧企业格式迁移、兼容读取或回退。删除无意义的原型/过渡代码；当前格式的初始化、完整性、原子提交及故障恢复必须保留。个人已发布数据与其必要迁移不受影响；不自动删除用户目录。
- Keystore 不可用或认证文件损坏时企业执行 fail-closed，保留可诊断原因；不能重置为示例。企业恢复仍位于 Settings 之后，退出恢复仍在原 Turn/Child 收口之后。

## 4. 接入、认证与同步的详细时序

### 4.1 扫码/粘贴与来源确认

Core 管理台输出的是 JSON；二维码编码同一份字符串，例如下面的无效占位资料：

```json
{
  "formatVersion": 1,
  "kind": "PLATFORM_ENROLLMENT",
  "platformUrl": "http://192.168.1.20:8080",
  "code": "ONE_TIME_CODE_PLACEHOLDER",
  "expiresAt": "2030-01-01T00:10:00Z"
}
```

1. 扫码和粘贴共用 EnrollmentMaterialParser；保留严格 UTF-8/体积、重复键、类型、版本、时间和 origin 校验。不能只接受裸 code。
2. 本地资料继续原流程；平台资料产生短期 typed 确认请求，UI 只展示规范化后的完整 origin。确认请求不得保存到导航、SavedState 或日志。
3. 确认前不执行 Discovery、不兑换 code；取消/离页丢弃资料。确认后复验原确认请求、有效期和当前无其他企业会话，防止输入替换/重复点击/旧回调。
4. GET `/.well-known/measix`，校验 product、protocolVersion、deploymentId、v4 支持和同源 API base path；拒绝 userinfo、跨域重定向、路径逃逸以及 scheme-relative URL。
5. POST `/api/client/v1/enrollments/exchange`：code、installationId、deviceName、appVersion、platform=ANDROID。installationId 由安装生成并稳定保留；用户/设备/Session ID 只能取服务端结果。
6. 校验响应身份与发现的 deployment 一致，原子保存认证状态，再执行 Bootstrap。不能让扫码输入提供 userId 或覆盖已有用户身份。

真实设备使用可达的 HTTP 或 HTTPS 平台 origin，例如 `http://192.168.1.20:8080`、`http://core.lan:8080` 或 `https://enterprise.example.invalid`。HTTP 是正常部署方式，不能仅允许 localhost；手机的 localhost 指手机自身。HTTPS 继续使用正常证书校验，HTTP 不涉及证书配置。

Discovery、Client、Runtime、Portal 采用同一已确认入口，Hub 的 Portal origin 与其一致；统一入口既可 HTTP，也可 HTTPS。部署只需提供设备可达地址与正确路由，不将 TLS 代理或域名作为接入前提。管理员配置真实供应商/CLIProxyAPI、模型、凭据和绑定后执行 Apply/Publish，并确认 Relay 已应用；仅发布合成测试上游不能交付真实生成。

地址支持的具体适配如下，沿用现有 URL 校验和网络组件，不建立新的网络策略层：

| 位置 | 当前事实与实施内容 |
| --- | --- |
| Android `EnrollmentMaterialParser` | 当前只允许 HTTPS 或显式开启的 loopback HTTP；改为正常接受 HTTP/HTTPS 的域名与 IP origin，移除 `allowLoopbackHttp` 特例。保留合法 origin 结构、禁止 userinfo/query/fragment/额外路径及默认端口规范化 |
| Android 网络与远端 Portal | Manifest 已有 `usesCleartextTraffic=true`，无需新增开关；远端宿主按已确认的 scheme/host/port 放行 HTTP 或 HTTPS。原本地虚拟 HTTPS origin 保持原语义 |
| Core `console/src/api/enrollment.ts::encodeEnrollmentMaterial` | 移除 HTTPS/loopback 限制，让 HTTP 域名/IP 入口直接生成可复制、可扫码的完整资料；用户无需手工改 JSON |
| Core `backend/internal/hub/identity/portal.go::ValidatePortalOrigin` | 从 HTTPS/HTTP loopback 限制改为合法 HTTP/HTTPS origin；否则 HTTP 已接入后仍无法取得 Portal grant |
| Core `httpapi/portal.go::setPortalCookie` | 已按 Portal origin 是否 HTTPS 设置 Secure；复用当前行为，HTTP 下 Secure=false、HTTPS 下 true，保留 HttpOnly/SameSite 与同源会话逻辑 |
| architecture/Core 文档、共享 fixtures、交付导出 | 修正强制 HTTPS 与仅 loopback HTTP 的约定/反例，加入局域网域名、IPv4/IPv6、显式端口正例，保留非法地址反例；更新 operations/接入说明并重新生成、验证交付包 |

HTTP Runtime 的实时通道使用 `ws`，HTTPS 使用 `wss`；不得在 ASR 中无条件升级为 `wss`。平台地址校验与 Portal Bridge 文档授权均沿用实际 origin，不能按主机相同忽略 scheme/port。Portal 相机/录音复用现有原生 Bridge，无需为 HTTP 新建网页采集方案。

Core 的 ExchangeEnrollment 消费一次性 code，当前没有刷新协议那样的幂等响应恢复。兑换结果未知时不自动重放并声称成功；若服务端已消费而客户端未保存，明确提示重新取得资料。Bootstrap/下载失败则恢复已经保存的 Session，不让用户重复消费 code。同一 installation 已属于另一个用户时，Core 返回冲突；客户端不能自动改 installationId 绕过限制。

### 4.2 Token 刷新与错误分类

Core 当前 refresh 恢复窗为两分钟，七天闲置期只由成功的 authenticated refresh 续期；普通请求、同步、Portal load 不续期。两个期限必须采用服务端值，不能用手机 now+7 天代替。

1. 为同一 Session 合并并发 refresh；先持久化 `Idempotency-Key`，再发送 POST `/api/client/v1/sessions/refresh`，body 为 refreshToken。
2. 未确定结果重试只使用同一个旧 token 和同一个 key；禁止为不确定结果生成新 key。新 token+旧 key、旧 token+新 key 的 409 `refresh_conflict` 不循环重试。
3. 成功后原子提交新 refresh/期限并清理 pending，再发布新的内存 access token。恢复窗已过且旧凭据不可恢复时进入明确重接入状态，不自行延长授权。
4. Control GET 等可重试读遇到确定的 access 失效，可安全刷新一次并重试；Runtime 请求、工具调用与音频提交不得套通用 HTTP 自动重试。优先在 I/O 前取得有效 token，已经发出的未知副作用不重放。
5. Hub 的 403 `session_revoked` 与 Relay 的 401 `invalid_session`/403 device_revoked、user_disabled 等分别分类；不能将所有 401 都当成单纯 token 过期。保留 status/code/detail/requestId/forwarded 与有用 cause，凭据脱敏。

### 4.3 Managed State、Snapshot 与 Guard

1. Bootstrap 获取展示身份、设备状态、会话期限和 Managed State；activeManagedGeneration=0 表示待配置，不请求 Snapshot 0。
2. GET `/api/client/v1/managed/state`，只携带实际已提交的 `X-Measix-Applied-Managed-Generation`；首次没有 Applied 时不伪报。
3. `READY + 同 generation` 才允许新受管执行；ACTIVATING、DEGRADED、runtimeBlocked 或同步缺失阻止新受管执行并沿现有错误/状态投影反馈。
4. 获取目标 `/managed/snapshots/{generation}`，校验来源/部署、v4、generation、资源 ID/枚举/引用、五项策略、语音条件字段、路径、ETag 与 body.snapshotHash。按共享合同验证，不另造客户端 JSON canonicalization。
5. 200 校验完整后提交；304 只能复用同一来源/主体/generation 下已验证的本地缓存。没有对应缓存时不能用空配置完成同步。同 generation 内容/身份冲突拒绝，候选损坏保留最后完整配置。
6. 下载期间目标可再次变化；提交后按权威 state 重新准入，不以“下载完成”代表已可执行。不无限追逐新版本，不延用已知落后的快照执行。
7. 每个新的顶层受管 interaction 做权威 preflight；并发可共享同一在途检查/同步，但执行上下文各自冻结，不增加 TTL 跳过检查。控制面故障阻止新受管执行，不锁死个人空间、历史浏览或不需远端授权的本地动作。
8. 持有旧 generation 的已开始 Turn 不切换配置；后续请求遇到 verified 428 `managed_snapshot_required` 且 forwarded=false 后停止原交互、同步、等待下一次用户动作。不得新建第二 Turn 重放已执行工具。

同步复用现有 EnterpriseSynchronizationService 和取消协议；UI、Portal、执行前检查走同一同步入口。网络工作不持 Session 写锁，候选提交复验原 Session。成功时间只在检查/提交确实成功后更新。

## 5. Snapshot 映射与实际执行

### 5.1 配置字段的去向

| 平台事实 | Android 映射与校验 |
| --- | --- |
| Provider.providerId/clientProtocol/enabled | 保留公开 Provider 表；Model 引用必须存在，禁用 Provider 下的资源不可执行 |
| Model.modelId/upstreamModelKey | 前者是 ConfigurationReference 的稳定企业 ID；后者才是请求模型名，不能互换 |
| Model modalities/capabilities | 显式映射可支持枚举；未知值拒绝候选，不默认为文本或自行增添图片生成能力 |
| TTS/ASR/MCP 的协议和路径 | 保存 typed 执行描述；系统 TTS 不生成远端描述，其他资源仅使用平台路径 |
| policy 五项 allowLocal* | 必填 Boolean，缺失/null 拒绝；控制当前企业域中的用户原资源，不改个人域 |
| defaultModelId/defaultTtsId/defaultAsrId/defaultAssistantId | 映射现有默认选择槽位；用户显式选择优先，失效显式引用不回退首项或企业默认 |
| Assistant | 映射 ID、名称、说明、默认模型、systemPrompt、固定 MCP 和 enabled；模型是默认而非锁定 |
| memorySeed[] | 保留顺序、非空文本；内部稳定键由 authority/assistant/generation/index 派生，不按内容去重，不写运行记忆 |
| Starter | 保留绑定助手、标题/prompt/说明/排序/enabled；只预填，用户发送才创建 durable Turn |
| v4 未提供字段 | 不补本地示例值；企业 gateways/子助手关系为空，其他功能按当前用户偏好与解析规则工作 |

标题、快速模型、建议、附件检查等辅助模型槽位必须单独检查现有选择规则。不得把 defaultModelId 批量写入全部槽位；配置不足时使用已有明确不可用行为，不把主聊天失败或个人 fallback 当作解决办法。验收需实际检查这些入口。

### 5.2 模型请求

改动定位：ModelExecutionService、service/runtime/ModelRequestTransport、ModelExecutionLease、ai/provider/RequestCredentials 与四协议 builder。所有普通/流式/辅助调用入口均覆盖，不用 OkHttp 全局拦截器事后猜 URL 或替换供应商认证。

- 统一目标为可信 origin + runtimeApiBase + `/resources/{resourceId}` + runtimePath；完整接口路径不追加第二次协议后缀。
- 平台凭据不进入 ProviderSetting.apiKey、用户 KeyRoulette、MCP OAuth、日志或快照。请求前从同一 Session 凭据 owner 取得有效 token；刷新只替换凭据，不重新选择模型/路径/generation。
- 平台 transport 拥有 Authorization、generation、interaction 和最终地址。助手 customHeaders/customBodies 不得覆盖这些身份/路由字段；复用现有保留字段校验，失败保留具体原因。
- 平台流量不能依据 Relay 主机名推断供应商的特殊请求 profile。只执行当前下发协议明确表达的能力；需要额外供应商语义时先补合同，不硬编码 model/host 白名单。

| 协议 | 实施要求 | 最低功能证据 |
| --- | --- | --- |
| OPENAI_CHAT_COMPLETIONS | 复用 ChatCompletionsAPI；messages、tool_calls、usage 和 declared modalities | 文本 SSE、多轮、工具结果回传、取消/断流 |
| OPENAI_RESPONSES | 复用 ResponseAPI；完整 input、store=false、不用 previous_response_id；当前平台流式约定明确传入 | function_call_output.call_id、完整 output/reasoning 批次回放、response.completed |
| GOOGLE_GENERATE_CONTENT | 完整路径加 alt=sse；复用 contents/parts，平台 Bearer，不带用户 x-goog-api-key | functionCall/functionResponse、调用 ID、thoughtSignature 原样保留 |
| ANTHROPIC_MESSAGES | 完整路径、平台 Bearer，保留 anthropic-version、max_tokens 和独立 system | tool_use/tool_result 配对、thinking 回放约束、message_stop |

不改变 RequestAssembler、checkpoint、rolling compaction、工具审批/执行和消息持久协议。非流式的应用入口若遇到仅流式平台约定，应复用同一流式 decoder 聚合最终结果，不自行调用未下发的另一接口，也不为此增加 UI 开关。

### 5.3 TTS：配置、执行与个人风格一致

改动定位：EnterpriseConfiguration、EnterpriseSpeechTransport、SpeechApplicationService、speech/provider 下 OpenAI/Gemini/MiMo/System 实现。提取或扩展现有纯请求编码与音频解码接口；保留同一播放队列、音频焦点、停止与清理 owner。

| 协议 | 条件字段 | 编码/输出 |
| --- | --- | --- |
| OPENAI_AUDIO_SPEECH | model、voice、runtimePath 必填；禁止 system 参数和 voiceDesignPrompt | 既有 Speech 请求，MP3 二进制 |
| GEMINI_GENERATE_CONTENT_TTS | model、voice、runtimePath 必填；禁止 system 参数和 voiceDesignPrompt | responseModalities=AUDIO、prebuiltVoiceConfig.voiceName；JSON inlineData PCM，按合同 24kHz/16bit/mono |
| MIMO_CHAT_COMPLETIONS_TTS | model/runtimePath 必填；标准模型必填 voice、可选风格指令；voicedesign 模型必填非空 voiceDesignPrompt 且禁止 voice | 复用 buildMiMoRequestBody：描述为前置 user、目标文字为 assistant；stream=true/audio.format=pcm16；SSE 音频增量 |
| SYSTEM_TTS | 仅 speechRate/pitch，均大于零；禁止云端模型/voice/path/binding | 复用设备 SystemTTSProvider；不调用 Relay、不伪造上游计量 |

个人 SystemTTS 原本就没有模型名和音色；个人 MiMo 已有标准/voicedesign 模式以及 voiceDesignPrompt。本轮复用这些语义，企业配置只改变定义来源、只读权限与云端传输目标。

当前企业列表硬编码“modelId · voice”，个人列表显示 OpenAI/Gemini/MiMo/系统TTS 类型。移除企业专用摘要，查询投影提供与个人一致的类型，继续使用同一 SpeechProviderItem、名称、来源标记、选择和试听动作。系统企业资源显示“系统TTS”，MiMo 音色设计仍显示“MiMo”；不另列语速/音调、模型或 prompt 长摘要，不新增企业编辑器。若未来确需只读详情，再另行讨论。

企业 System TTS 仍是 Enterprise 引用，allowLocalTts=false 不阻止它；该策略仍禁止用户自己的 System TTS。设备引擎不可用明确报错，不切换云端。MiMo voicedesign 不把 null voice 填成 mimo_default，也不将企业参数持久化进个人配置。

### 5.4 ASR：HTTP 与实时录音

改动定位：EnterpriseAsrResource、EnterpriseSpeechTransport、SpeechApplicationService、speech/RealtimeAsrController、HttpAsrController。将实时参数与握手请求的来源分开，避免把平台 token 装成个人 apiKey；录音与转写展示沿原 ASRController/ASRState。

| 协议 | 必填与禁止字段 | 传输 |
| --- | --- | --- |
| OPENAI_AUDIO_TRANSCRIPTIONS | model/runtimePath，可选 language；禁止所有实时参数 | multipart file+model+可选 language，boundary 由库生成 |
| DASHSCOPE_HTTP_ASR | model/runtimePath，可选 language；禁止所有实时参数 | WAV/MP3 Data URI 放在 input.messages 用户消息的 input_audio.data；parameters.format 与实际音频一致，可选 language_hints；读取 output.text，不发送 multipart |
| OPENAI_REALTIME_TRANSCRIPTION | sampleRate=24000、vadThreshold∈[0,1]、silenceDurationMs>0、prefixPaddingMs≥0，可选非空 prompt | 按平台 HTTP/HTTPS 使用 ws/wss 资源完整路径，追加 intent=transcription |
| DASHSCOPE_REALTIME_ASR | sampleRate 为 8000/16000、vadThreshold∈[0,1]、silenceDurationMs>0；禁止 prefixPaddingMs/prompt | 按平台 HTTP/HTTPS 使用 ws/wss 资源完整路径，追加编码后的 model=upstreamModelKey |

实时通道 GET Upgrade 携带三项平台头；runtimePath 本身没有 query。复用 session.update、PCM append、增量/完成合并和各自停止事件。握手失败解析普通 HTTP Problem，包括 428；不要在 WebSocket onFailure 中把原错误全部折叠成连接失败。

按权限/准入后启动录音，切域、退出、取消、前后台与超时沿现有 owner 关闭 recorder/socket，禁止旧连接回调回填新页面。企业 ASR 类型标签沿用个人已有 OpenAI Realtime/DashScope 呈现；HTTP ASR 使用明确类型名称，布局和录音入口不改。

### 5.5 Direct MCP

- 复用 McpConnectionDefinition、McpServerRuntime、McpRuntimeCoordinator、McpCatalogStore 与执行租约；不新建企业 MCP 客户端栈。
- Managed 连接目标通过同一平台 Runtime 输入构造；authOwnership=NONE 仅表示上游不需凭据，客户端仍发送平台 Bearer。
- init/initialized/tools/list/tools/call、GET/DELETE、Mcp-Session-Id/MCP-Protocol-Version 按既有 Streamable HTTP 生命周期执行，不能人为固定 Session。
- 配置 digest 由稳定公开执行定义和 generation 构造，不能把短期 access token 混入 durable Catalog 身份。连接凭据轮换与 Catalog 版本是不同事实；失效连接由原 runtime owner 安全重建，不能自动重放结果未知的工具。
- 助手固定 mcpServerIds、完整 Catalog 校验、分页、工具命名和 Turn 冻结目录保留。维护发现与实际调用都要有原主体/interaction/generation，禁止发现旁路跳过准入。
- 当前 v4 平台没有 Gateway，不自动创建 discover_tools/invoke_tool 对；本地示例 Gateway 保持其原来源行为。

### 5.6 编码体积、录音上限与实际可用性

Core 当前 `runtimecontrol/service.go::defaultMaxRequestBytes` 为 `10 << 20`，即 10 MiB。Relay HTTP 限制请求体，WebSocket 限制每条连接累计客户端发送的线字节；base64、JSON、会话设置/停止事件和帧开销都需要考虑，不能只按原始图片或 PCM 文件大小判断。

- 图片沿现有编码/压缩链处理，并在完整请求编码后检查体积；历史文本与工具结果也计入请求。超限走原诊断入口，让用户减小附件/内容，不能静默删除历史或截断附件。
- HTTP ASR 在上传前核对包含 multipart 开销的体积；实时 ASR 按实际发送累计字节控制有界录音，并预留协议结束消息空间。达到上限结束并明确反馈，不无限录音、静默拆连接或自动重复发送音频。
- 不从 Snapshot 猜测不存在的 limits 字段，不把此实现上限伪装成服务端下发配置。将当前基线约束集中在平台 transport 中并用边界测试固定；后端若变更上限，更新固定合同与测试，不增加用户调参入口。
- 供应商 429、余额/权限、队列容量及路由超时保留真实诊断；它们需要后台配置或供应商条件解决，协议适配不承诺消除外部限制。

验收必须包含五项 allowLocal* 全关时，企业模型/语音/MCP/助手仍可使用；再单独验证策略允许时混用原用户资源。不能靠打开所有个人准入掩盖企业资源接线失败。

## 6. 远端 Portal、Feed 与退出

### 6.1 远端工作台接线

1. 原生在当前 Session 下 POST `/api/client/v1/portal/grants`，取得 exchangeUrl、ticket、expiresAt。服务器当前 ticket 最长一分钟且一次消费，不持久化、不进 URL/log/JS。
2. 校验返回的 HTTP/HTTPS exchange origin 与固定 `/portal/session/exchange` 路径；本轮统一入口要求它与已确认平台 origin 相同，且来源必须是已认证平台响应。不能从网页导航、接入资料附加字段或任意 URL 获得 Bridge 授权，不提前扩展跨站部署模式。
3. WebView 通过原生 `postUrl` 发送 application/x-www-form-urlencoded 的 ticket，让 WebView 接收 HttpOnly Cookie 和受控 303 `/portal/`；不能用 OkHttp 建完 Cookie 后假设 WebView 自动共享，也不把平台 Bearer 注入网页请求。
4. 将 PortalWebView 固定 local 的来源/资源策略扩展为 typed Local/Remote 两种输入。Local 仍禁止网络并校验随包资源；Remote 只加载批准站点、禁止混合内容和任意导航，不因失败 fallback 本地页面。
5. 交换页到首次 Portal 文档是受控初始化导航；只有批准的最终顶层文档激活 Bridge。后续导航/重载按现有文档授权生命周期失效，不能把允许初始 303 变成开放重定向。
6. PortalDocument/MediaStore/PageBinding 不再硬编码 LOCAL_ORIGIN，而是捕获本次已批准文档 origin；保留 main-frame/documentId/RealmSelection/Session 校验和原回复接收器。
7. Remote Portal 使用 Core Cookie API 获取身份/Feed；本地 getLocalContext/listLocalUpdates/getLocalUpdate 仅对 Local 文档开放。Bridge v3 原生能力保留，不扩展网页聊天写入权限。
8. Cookie、站点存储、媒体与旧接收器清理仍由 PortalDocumentRegistry 和宿主关闭屏障收口；删除目标是原批准站点，不全局清除无关站点。WebView 平台能力按现有 feature 检查验证。

这会修改 PortalWebView 的网络/会话实现，但不新建页面、不改变工作台操作流程。远端页面由 Portal 仓库正式交付，Android 不复制前端 UI。验收固定远端 build identity；更新本地包时走已有交付构建，禁止手改压缩 JS。

### 6.2 Feed 与 Session 边界

Feed revision 独立于配置 generation。远端使用现有 Client/Portal Feed HTTP 与 ETag；本地原生编辑器只用于本地来源，不提供真实企业后台编辑能力。默认/日期/时区/分页/分类/级别/正文格式按共享 query vectors 验证；内容发布、撤回不能靠重新同步 Snapshot 才生效。

网页 Session 过期可经现有重新打开流程申请新 grant，不等于母 Session 失效。关闭工作台保留企业登录；远端网页 Session 的关闭使用已有 Portal close API/Cookie/CSRF 语义，不调用母 Session logout。本机宿主关闭与站点清理不能被远端关闭结果未知永久阻塞，也不能假报远端关闭成功。网页 logout 继续原生退出确认并调用同一退出 owner。相机/麦克风继续原生权限与 MediaHandle，文件不自动上传。

最新 Portal 交接明确保留现有 accessor、原 JavaScriptReplyProxy 与页面功能，不要求重建消息通道。将新包补充的媒体多块读取、读取中途取消/文档失效、退出超时后恢复纳入设备回归；本地已实现的相机/麦克风与 accessor 不是待重新开发事项。

### 6.3 退出、撤销与故障恢复

- EnterpriseExitService 先原子 CLOSING，撤销新准入，再并发取消/等待 Portal、同步、模型主子运行、辅助生成、Speech、MCP 与 Workspace 原 owner。
- 平台 logout 为 POST `/api/client/v1/sessions/logout`，body 是当前 refreshToken；不是 access-token-only。与 pending refresh 串行协调，不能拿不确定轮换后的旧 token 假报撤销成功。
- 本机收口完成后可以完成本机退出，不被断网永久卡住；远端撤销未确认作为明确诊断沿现有反馈显示，不谎报服务器已注销。不得为了远端重试保留仍可用于本机执行的权限或启动后台无限重试。
- 本机清理失败保留原 CLOSING 与可重试事实；进程重启继续既有退出恢复。正常退出保留该域历史、运行记忆及文件，不能执行清除示例数据的删除流程。
- 重新接入相同主体使用服务端新 Session，不复活旧文档、连接、租约和异步回调；切回个人仅切域，不注销。

## 7. 分批执行清单

每批都更新受影响 references 与 architecture 静态契约，先定向验证；跨 owner 的最终阶段运行完整门禁。以下是逐项验收清单，已开展的实现及证据见第 10 节；未勾选项不能据代码存在推定为已验收。

### P0：固定资料与测试入口

- [ ] 重新核对三仓库状态；固定上游源/工作区差异与集成包摘要，解决 Anthropic 文档矛盾。
- [ ] 以 RxyrgW 完整包及内层 Core 包的不同摘要作为核对基线；完成 §4.1 的 HTTP 约定、Core 生成器与 Portal 校验修正后，重新生成并固定交付包/8 份 Portal 导出，复核 local build identity 与远端实际部署身份。
- [ ] 在 Android 测试资源中引入当前共享正反例与五个新增快照：responses、gemini、claude、speech、asr；保留来源摘要，不维护另一份手写协议真源。
- [ ] 准备设备可达的局域网/IP HTTP 与 HTTPS 入口、测试用户、发布资源和可用供应商；HTTP 验收不依赖域名或证书。真实凭据只在私有配置，不进入文档/报告。
- [ ] 检查现有 ui/query/error 投影可覆盖流程；仅来源确认和语音类型呈现一致化进入 UI 范围，其余交互发现后先讨论。

完成条件：当前 v4 范围、源身份、fixture 校验与联调入口明确；未获得真实供应商条件时记录待验收项，不以替身通过销项。

### P1：内部候选、平台数据与持久发布

- [ ] 拆开 Local package wire、Platform Snapshot wire 与共同已验证候选；扩展企业 Provider/模型/语音/MCP 执行描述。
- [ ] 编写纯 Snapshot 校验和映射，覆盖全部条件字段、引用闭包、禁止字段与未知枚举；保持用户偏好和 Seed/Starter 规则。
- [ ] 扩展 AppliedStore 单 manifest 发布、平台源与认证引用；删除旧企业格式兼容与迁移，验证当前格式失败恢复，不触碰无关 Room/Settings schema。
- [ ] 更新 SessionController 的平台提交/CAS 入口；清除 local-only 校验被通用入口误用的问题，保留 local 原来源限定。

验证入口：EnterprisePackageTest、EnterpriseSessionControllerTest、ConfigurationResolverTest、EnterpriseAppliedStateAndroidTest；新增平台候选、当前格式初始化/写失败测试。

完成条件：所有共享快照正确映射；畸形候选不破坏原状态；磁盘故障和重启不能产生半个 READY；个人数据保全，当前格式本地示例功能有效。

### P2：扫码接入、认证与同步

- [ ] 移除 EnrollmentMaterialParser 的 HTTPS/loopback 限制，接入更新后的 HTTP/HTTPS 共享正反例；以 Core 实际生成资料验证域名/IP/端口，不增加开关、白名单或额外确认。
- [ ] EnterpriseApplicationService/EnterpriseVM 增加 typed 来源确认请求，EnterprisePage 复用现有对话框；取消无网络请求，确认仅兑换一次。
- [ ] 实现 Discovery/Enrollment/Bootstrap 平台 client、installation identity、安全认证持久化及 refresh 单飞/恢复。
- [ ] 同步服务按来源读取，增加 State/Snapshot/304/原子应用；Control 错误保留原 Problem，网络失败不覆盖上次成功时间。
- [ ] 接入启动恢复和平台退出；不在 DI 注册第二个企业配置 owner，不持锁联网。

验证入口：EnterpriseApplicationServiceTest、EnterpriseVMTest、EnterpriseSynchronizationServiceTest、EnterpriseExitServiceTest、EnterprisePageAndroidTest；Core client_integration_test 与 refresh_test 作为互操作依据。

完成条件：Core 生成的 QR/JSON 都能接入、展示真实身份、下载配置；token 轮换并发/丢响应/进程死亡可恢复；待配置、撤销和重新接入可操作。

### P3：统一 Runtime Guard 与四模型

- [ ] 在模型、辅助生成、独立语音/MCP 捕获之前接入权威 Guard，父子操作显式复用上下文。
- [ ] 实现请求级完整 endpoint/平台 Bearer/header ownership，覆盖流式及非流式入口；个人 Provider 路径与凭据行为不变。
- [ ] 四模型跑完整文本/工具往返，保留 opaque reasoning/signature 和协议终态；处理 428、401/403、429、断流、取消。
- [ ] 加入平台 10 MiB 编码请求预算，验证含图片/历史/工具结果的实际体积和超限诊断。
- [ ] 验证标题/建议/附件检查/搜索/子助手等现有调用入口：有配置按域执行，无配置明确不可用，不能越权 fallback。

完成条件：通过真实 Core/Relay 的 Android 对话能执行工具并继续回答；配置变化不污染进行中的上下文，未知副作用不重放。

### P4：四 TTS、四 ASR 与 Direct MCP

- [ ] 复用 speech 编解码与控制器，接入 typed 企业参数、平台请求目标和取消；System TTS 不捕获远端 binding。
- [ ] SpeechCatalog 查询投影/现有卡片统一个人类型文案，移除企业固定 modelId/voice 摘要；保持来源/只读与个人编辑差异。
- [ ] 完成 HTTP→ws、HTTPS→wss 的实时 WebSocket 握手/错误/PCM/停止流程，覆盖前后台、退出、generation 屏障。
- [ ] 验证 HTTP multipart/Data URI JSON 编码后的体积与 WebSocket 累计编码字节上限，录音有界结束、取消清理、保留已完成转写且无自动重发。
- [ ] 完成 MCP 平台目标/动态认证、稳定 Catalog digest、完整会话和工具调用；无 OAuth 或 Direct/Gateway fallback。

完成条件：设备可听到真实语音、实际麦克风转写可回填、MCP 被模型实际调用；SYSTEM_TTS 在 allowLocalTts=false 下仍作为企业资源正常运行。

### P5：退出收口

- [ ] 验证服务端 revoke、远端 logout 结果未知、本机清理失败/重启和新 Session 之间的隔离。
- [ ] 保持现有本地 Portal 文档、媒体句柄和退出清理可用，切域或退出后原文档不能继续访问。

完成条件：本机准入与原域工作确实收口，Core 注销结果准确反馈；个人空间与已有本地 Portal 不受影响。远端 Portal/Feed 待其交付后另行启动。

### 暂缓：远端 Portal/Feed 交付后再启动

- 复用现有宿主增加 HTTP/HTTPS remote grant/exchange/Cookie/受信文档输入，验证实际 origin 与 Cookie 行为，保留 local source 严格隔离。
- remote Feed/Session 通过 Core HTTP，Bridge local-read 只限 Local；核对 Portal 构建身份与共同 vectors。
- 通过交付流程更新当前内嵌 Portal 身份/资源与导出合同；保留既有 accessor/reply proxy，验证媒体多块读取与退出超时恢复。
- 实测相机/录音/媒体读取、外链、关闭、退出、文档替换、过期和站点清理。

### P6：端到端验收与交付

- [ ] 执行第 8 节全部适用场景，保存精确源/构建/设备/发布 generation/资源协议/测试结果关联。
- [ ] 完整串行构建、JVM、lint、instrumentation 与 Debug/Release 真实路径验证。
- [ ] 更新 references 为实际实现事实；清除无调用协议、过渡分支和失效测试；核查编码/换行、最终 diff、无凭据/产物误入版本控制。
- [ ] 将未通过/未执行项明确列为未交付，不以“配置完成”替代用户功能验收；无明确发版请求不改 versionCode/versionName/changelog。

## 8. 验收矩阵与故障用例

| 编号 | 场景 | 必须观察到的结果 |
| --- | --- | --- |
| E01 | Core UsersPage QR 与复制 JSON；取消/错误格式/过期/重复点击/切页 | 同一正式解析链；确认前不联网；原请求失效后不可兑换；无 code 泄漏 |
| E02 | HTTP/HTTPS Discovery、非法 product/version/API base、跨域重定向 | 两种 scheme 正常可用；只接入确认来源；未知/非法配置明确失败，个人空间仍可用 |
| E03 | 首次接入无 Release；后台首次发布后用户同步 | 已登录待配置；不请求 generation 0；同步后现有目录与执行生效 |
| E04 | Token 过期、多调用并发、刷新丢响应、响应前/后杀进程 | 单一轮换；旧 token+同 key 恢复；写入失败不发布；期限来自服务端 |
| E05 | enrollment 结果未知、已消费 code、installation 用户冲突 | 不伪造身份/重复兑换成功；使用现有错误反馈要求新资料或管理员处理 |
| E06 | 200/304、坏 hash/缺字段/非法 enum/失效引用、下载时新版本发布 | 完整候选才应用；304 必须有匹配缓存；LKG 不越过执行屏障 |
| E07 | 四种模型真实对话与两轮工具调用 | 正确完整 URL/平台头，结果 ID/reasoning/signature 保留，回答真实可见 |
| E08 | 声明支持的图片附件、多轮历史、重启重开、标题/建议等辅助入口 | 原消息与文件 owner 不变；按模型能力与域配置工作，不向错误主体泄漏 |
| E09 | 四 TTS；MiMo 普通/设计；System TTS 禁本地策略 | 设备真实播放/停止；音频正确解码；企业系统资源可用，用户系统资源仍被禁止 |
| E10 | 四 ASR，录音权限拒绝、真实麦克风、停止/取消、断网 | 文本正确回填；录音/socket 清理；两种 HTTP 请求和实时参数不混用 |
| E11 | Direct MCP init/list/call、分页/Session 失效/GET/DELETE | 沿原 Catalog/runtime；工具调用真实生效，NONE 仍带平台认证 |
| E12 | 五项 allowLocal* 各自允许/禁止，个人↔企业切换 | 原用户定义未复制/修改；企业约束实际阻止执行；个人空间不受企业策略污染 |
| E13 | 新 generation、428、ACTIVATING/DEGRADED、401/403/429/5xx、断流 | 准入与现有诊断可见；停止/同步，不盲重放；本地浏览不全局锁死 |
| E14 | 助手/Seed/Starter 更新、助手禁用、手动模型偏好 | 只读定义更新；运行记忆保全；默认/空间默认/显式模型仍区分；Starter 不自动发送 |
| E15（暂缓） | 远端 Portal grant/Cookie/过期、Feed 发布/撤回/筛选 | 远端 Portal 交付后验收；本轮不计完成条件 |
| E16（仅本地） | 现有本地 Portal 相机/录音/外链、刷新/重载/导航、关闭/切域 | 原生权限与同文档能力有效；旧接收器/媒体句柄失效；无任意站点 Bridge |
| E17 | 服务端撤权、本机退出/断网退出、清理失败、重启与重新接入 | 本机授权确实收口，远端结果准确反馈；新 Session 不复活旧工作 |
| E18 | 个人数据/备份恢复、当前企业格式初始化、配置与文件失败注入 | 数据保全、scope 不变、无企业凭据入备份；个人与本地示例功能回归通过 |
| E19 | 编码后接近/超过 10 MiB 的图片请求、multipart 与 WebSocket 累计帧 | 明确限额与边界行为；录音有界结束，无静默截断、拆连接或重试；正确保留可诊断错误 |
| E20 | 五项 allowLocal* 全关，随后逐项开放 | 企业资源独立驱动真实功能；用户资源按策略恢复可用，不靠个人资源完成企业验收 |
| E21（暂缓） | 新 Portal 包媒体分块读取、途中取消/失效、退出超时后重新打开 | 远端 Portal 交付后验收；本轮不计完成条件 |
| E22 | Debug/Release 经局域网域名或 IP HTTP 扫码/粘贴，以及 HTTPS 对照 | Core 原样生成资料可接入；聊天/MCP/TTS/HTTP ASR、ws/wss 实时 ASR 与退出链可用；HTTP 无额外开关/确认，IP HTTP 无域名/证书前提 |

证据分三层：共享 fixture/纯逻辑测试证明映射；真实 Hub/Relay/SQLite 加严格合成上游证明协议链；设备加真实供应商/MCP 证明用户可用性。合成音频/固定转写/测试工具不能替代可听播放、麦克风识别和真实业务执行。缺少某供应商条件时保留该行“未执行”，不宣称所有协议验收。

真实联调要从 Core 管理台创建用户、接入资料、资源和发布开始，不直接写数据库或把 fixture 手动导入 Android 代替。报告记录每项 PASS/FAIL/NOT_EXECUTED、设备/API/WebView、Android 包类型/版本/构建摘要、Core/Portal 源与构建、generation/协议及非敏感 requestId；不保存 token、code、Cookie 或私人对话载荷。

## 9. 验证命令、文档与交付检查

先运行修改领域的定向 JVM/设备测试，再运行：

```powershell
.\gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1
.\gradlew.bat connectedDebugAndroidTest --no-parallel --max-workers=1
git diff --check
```

instrumentation 使用隔离设备/测试数据，预先确认会否清除应用数据。Debug 通过不能代替 Release 安装后实际扫码/粘贴、聊天工具、语音、WebView、进程恢复与退出。Release 保持既有 AGP/R8 keep 约定。

Core 独立资料包校验入口：在 Core 根运行 `node api/generated/android/integration/verify.mjs --verify api/generated/android/integration`。它仅校验资料完整性。需要运行 Core 测试或修复 Core 时遵守其 AGENTS，不在 Android 任务中擅自发布/改动后台服务。

初版方案编写时完成的只读检查：RxyrgW 外层 150 文件逐项摘要/完整集合/路径检查；Portal local assets、Bridge/local-read profile 与 8 份导出摘要检查；内层 verify.mjs 校验 72 文件；包内 Core 说明/OpenAPI 与当时文件字节比对。后续 Android 构建、设备及真实供应商验收进展见第 10 节。

实现完成时同步：application-architecture（owner/恢复）、android-configuration-architecture（平台与持久化）、assistant-configuration（实际映射）、protocol-reference（完整 endpoint/平台认证）、mcp-architecture（连接/Catalog 身份）、ui-architecture（接入确认与统一类型呈现）、testing-strategy（平台与设备场景）。references 只写已实现事实，未实施事项留在本文。

最终交付检查：

- [ ] Android 未新增第二企业配置真源、企业 Provider 栈或企业语音 UI。
- [ ] 个人资源/本地示例/平台资源三条来源边界明确，共用业务 owner，无 fallback。
- [ ] 所有网络响应、刷新与同步提交复验原 Session，所有取消与未知副作用符合原 Turn/资源协议。
- [ ] 接入和语音呈现遵循已确认 UI 范围，未增加其他交互。
- [ ] 个人数据保全；当前企业格式初始化/密钥/恢复失败有证据，无旧企业协议或文件格式兼容层。
- [ ] 所有完成声明有当前构建、真实 Core 与相应设备证据；未验收资源逐项列出。
- [ ] 最终 diff、编码/换行与工作树检查通过；不提交无关修改、凭据、数据库或构建产物。

## 10. 实施记录

### 默认助手补充约定

用户已确认同时适配 Core 下发与未下发默认助手，不等待配置齐全才允许打开空间。当前 OpenAPI 与 Control Protocol 新增可选 `policy.defaultAssistantId`，指向同一快照中启用的助手；映射到现有 `EnterpriseDefaults.assistantId`，不另建偏好或默认值真源。用户已有的有效选择优先；已保存但失效的引用不能静默改用企业默认或首项。缺少可用助手时复用已有空间页与选择入口，保留原引用。用户另已确认：“开始对话”复用个人空间 `AssistantPicker`，推荐开场继续保留；即使默认助手与推荐开场都为空，也能选择已有可用助手。没有新增页面、选择器组件或第二套企业编辑器。

验证包括：有默认、无默认、默认引用不存在或停用、用户选择优先、失效用户选择、最近会话继续打开；设备检查冷启动后空间可达以及实际开始对话。Core 正在配置真实供应商，当前合成上游的成功只能证明协议与转发，不代表真实模型、语音质量和完整功能交付。

- 2026-09-19：按用户约定取消未上线企业协议/文件的历史迁移要求；只保留当前格式与必要的故障恢复。
- 阶段复核：Core 工作区已接受 HTTP/HTTPS，Portal 已改为 PublicOrigin/ValidatePublicOrigin；发现接入说明与共享 enrollment fixtures 尚有 HTTPS-only 旧内容；本阶段已修正这两处并重导出 Core 集成资料。以上旧基线表中的符号与摘要不能作为后续完成依据。
- Android 已删除 EnrollmentMaterialParser 的 allowLoopbackHttp 特例；域名/IP HTTP 与 HTTPS 共用正常解析。已原样同步 Core Portal 导出；Android 定向 47 个测试通过（解析器 9、共享接入 34、Portal 合同 4）。Core 共享 HTTP 用例先复现失败、修正参考校验器后通过；导出一致性测试通过。
- 新 Core 集成包：72 文件，sourceHash=d67275fc175c3c6090218e351461300c6305e3863295148dceccad9ec86db299，导出及 verify.mjs 均通过；Portal 外层完整包尚待后续重新生成。
- 本阶段 Core 自有改动：api/fixtures/enrollment/cases.json、backend/internal/contract/enrollment_vectors_test.go、docs/android-platform-integration.md 及对应生成资料；其余既有工作区变化不归本任务。
- 当前未连接设备；可用 AVD 为 Codex_Portal_API36、Pixel_10_Pro_Fold，UI 实施后启动隔离模拟器验收。尚未进行 UI/设备验收，后续 P1–P6 与最终子代理审查仍未完成。

### 当前协议类型与 Control 传输

- 已从当前 Core OpenAPI 生成 PlatformWire，固定 OpenAPI/cases 消费副本；生成脚本支持 --check，构建不依赖相邻仓库。共享 cases 覆盖四模型、四 TTS/三 ASR 的结构以及五项策略缺失/null/错误类型。语义映射和跨资源引用校验仍待接入。
- 新增 PlatformControlClient/PlatformConnection：Discovery、Enrollment、Refresh、Bootstrap、Managed State、Snapshot、Applied 回报和 Logout；请求不自动重放、不跟随重定向，沿既有 readResponse 取消链。远端 Portal 暂缓后已移除无消费者的 grant 方法；生成的完整 OpenAPI 类型仍固定原合同。
- 本阶段 Core 又新增 PUT /api/client/v1/managed/applied（ManagedAppliedReport）；已更新 pinned OpenAPI、生成类型和传输方法。同步流程必须在校验并原子应用成功后，以当前 Session 回报 managedGeneration/snapshotHash；相同报告可重试，409 回报倒退与 422 发布/hash 不匹配保留诊断。不回退已持久状态、不据回报授权运行或续期，失败在后续同步/恢复时重报已提交版本。P2/E06/E17 要覆盖提交前不回报、重启重报与旧 Session 隔离。
- 本阶段最终定向验证：55 个测试通过（含 37 条 Core cases 在类型测试中逐项消费）、生成 --check 与 git diff --check 通过。尚未执行完整构建门禁/设备验收；主链整合继续进行。

### 平台候选与单一发布存储

- 新增 PlatformSnapshotMapper/EnterpriseCandidate/EnterpriseExecution，保留完整 Provider、模型、语音条件字段、MCP authOwnership、助手/Seed/Starter；Local 包和平台候选进入同一个 Session synchronize/applyValidated。
- AppliedStore 当前 schema=4，只存当前格式 configuration.json/execution.json，删去 bindings.json 与仅本地包重建读取；平台执行不冒充本地 binding。平台身份可恢复，凭据持久化和平台登录仍待连接。
- 语音列表改为复用个人的 OpenAI/Gemini/MiMo/System 及实时 ASR 类型标签，不新增页面；实际模拟器检查待 UI/接入闭环后执行。
- 本阶段 141 项定向回归通过；随后加入 Core 8 个引用闭包样例及同 generation/hash 冲突测试，最终 mapper/session 定向 30 项通过。OpenAPI 当前源与消费副本摘要仍一致。平台凭据、登录 UI 与实际设备验收尚未完成，不以存储/映射测试代替真实用户功能。

### 平台凭据与登录网络编排

- Session owner 已实现 pendingEnrollment、Bootstrap 身份核验和刷新 CAS；凭据以 AndroidKeyStore AES-GCM 加密的不可变版本保存，manifest 统一发布引用，access token 不落盘。安装标识独立保存，企业未上线格式不做历史迁移或明文降级。
- PlatformEnterpriseSessionService 已注册 DI，编排 Discovery/兑换/Bootstrap 与串行刷新；网络在 Session 写锁外，兑换成功先保存恢复凭据，Bootstrap 失败不重复消费 code。刷新未知结果在进程重建后继续使用原 token/key；只对明确的 Client 401 invalid_credential 读取重试一次。
- 本阶段认证、网络恢复和 Session 定向 29 项测试通过。网络测试为真实本机 HTTP 加共享响应 fixtures，不代表真实 Core 部署或 AndroidKeyStore 设备验收。现有 UI 仍未分派平台接入，后续继续完成确认交互、同步/运行准入、远端资源执行及设备验收。

### 平台同步与来源确认接线

- 平台网络编排统一为 PlatformEnterpriseService；EnterpriseSynchronizationService 按来源调用它，同步仍共用原并发合并/取消和 Session 发布链。generation=0 不请求快照；200 完整校验后提交；304 只复用原主体的已验证同 generation 缓存；应用成功后回报 Core，回报失败保留配置并可在重启后重报。
- EnterpriseApplicationService.join 将平台扫码/粘贴转换为短期确认命令，UI 仅显示来源，确认前无 Discovery/兑换。只新增已授权的一处原生对话框；五语言使用同一 HTTP/HTTPS 文案，删除“真实企业暂不支持”的旧 UI 分支和文本。
- 完整运行准入、四模型/语音/MCP/远端 Portal 接线，以及未完成接入的自动恢复/终态退出仍待后续收口。不得把配置已应用或 JVM HTTP 测试当成真实用户功能/设备验收通过。
- 本阶段最终 43 项 JVM 定向测试通过（平台网络 4、共享同步 5、企业 application 11、本地来源 23），Debug 与 AndroidTest APK 构建通过。API 36 模拟器的来源确认测试和 AndroidKeyStore 测试共 2 项通过；已检查确认截图，平台 HTTP 地址完整可见，取消/确认按钮无截断。截图位于本任务可视化目录 enterprise-platform-confirm.png；确认测试替换 application service，不代表真实 Core 接入或其余 UI 已验收。

### Core 本轮交付复核、真实接入与执行前检查

- 最新交付基线改为 android-integration-TzTeNh：150 文件逐项摘要及文件集合校验通过，外层 sourceHash=577379e9d5e78f05eda1916fc074418cdc1119d1cb407bc03544b3630d1d513a；Core 资料 sourceHash=1ec5190a4ab8df11375af82832a9e0700a0dcacd18584a4aa0654988cb1a2590。当前 OpenAPI 与 Android 固定副本仍为 7c43d6eb11952fe9f23fe340235018289f931a35d14228ac8c5e9ccacb35cf4e。已同步最新 Portal 八份输入与 manifest，包括新增局域网/公网 IP/域名/IPv6 HTTP 正例及非法 origin 反例。RxyrgW 等摘要只保留为历史核对记录。
- 2026-09-18 14:49 UTC：使用用户提供的一次性资料，在 API 36 模拟器真实 Debug App 中从粘贴入口接入 http://192.168.31.235:9100，确认来源后完成 Discovery/Enrollment/Bootstrap/Snapshot/应用报告并切入企业空间。设备 manifest 为 READY、Applied generation=2；只读核对实际 Hub sessions 行为 ACTIVE、applied_managed_generation=2、reportedAt=2026-09-18 14:49:19.5572093 UTC，快照 hash 与设备 execution.json 完全一致。截图 enterprise-real-core-connected.png 已检查。未保存接入码或令牌到仓库、文档、测试或证据文件。
- 这是真实 Core + 真实 Android 接入/应用回报证据，不是模型、TTS、ASR、MCP 或远端 Portal 完整执行验收；上游当前仍使用合成协议服务。保留模拟器应用和会话供后续联调，更新使用覆盖安装，不通过卸载/清数据消耗已经兑换的资料。
- 平台模型捕获已接入权威 Managed State preflight，缺配置时最多同步一次；READY/版本一致/未 blocked 才产生 AppliedVersion，Session owner 以版本 CAS 签发类型化 EnterpriseExecutionLease。原 EnterpriseBindingLease/captureBindings 命名已移除，Local/Platform 执行来源均冻结而不伪造私有绑定；退出、取消、到期和旧 lease 清理保留原 owner。平台资源的请求装配继续实施。
- 执行前检查、模型捕获、Session、语音传输和本地 MCP 本轮 57 项定向回归通过；覆盖每次新交互查询权威、DEGRADED 拒绝、平台 lease 必须持检查版本、取消/到期不泄漏 lease。完整门禁与最终子代理审查仍未完成。

### 模型、MCP 与默认助手的实际消费

- 四种模型协议共用原 Provider 编解码器，新增 Routed 凭据仅携带完整平台地址和平台令牌；请求附带冻结 generation/interaction。辅助生成复用原流式累积器，Core 并未规定所有模型请求必须流式。平台请求禁止重定向、认证重试以及 408/503 隐式重发；有效 428 终止原交互并同步，不重放原请求。
- MCP 按 ManagedLocal/ManagedPlatform 明确来源，共用原协议客户端、运行时和 Catalog owner。平台请求逐次获取当前令牌，目录摘要不含令牌，模型与其工具共用 interaction。失败保留真实异常类型、message/detail 和 cause，只脱敏认证信息。
- 早期合成 Adapter 的 initialized 通知曾返回非法 JSON；补充 Core 定向回归并修正为 202 空响应，非 POST 返回 405。另一个任务同期修正了 Chat 流的终态，本任务验证其回归。只更新本地辅助 Adapter，未修改 Hub/Relay 数据或替换平台凭据。
- Core 已发布 `policy.defaultAssistantId` 并更新共享 cases/reference-cases；Android 已重新固定 OpenAPI、生成类型并映射默认助手，拒绝不存在/停用的默认引用。当前 57 项定向测试通过，涵盖共享 schema、引用闭包、网络、已有选择优先、无默认及失效选择的启动路由；Debug 与测试 APK 构建通过。
- 2026-09-18 15:42 UTC 后，实际设备已应用 generation=3：默认企业工作助手、deepseek-flash、Firecrawl MCP、MiMo 朗读和系统朗读；ASR 列表为空。冷启动正确进入企业助手的新聊天页。实际页面发送“37 × 29”后收到 1073，并完成标题和建议回复；已检查 `enterprise-default-assistant-cold-start.png` 与 `enterprise-real-deepseek-chat.png`。新配置的原生设备测试 3 项通过：启动选择、MCP 工具发现与 Catalog、模型流式和辅助生成。该证据不包括语音播放、识别、远端 Portal 或完整工具往返验收。
- 切换 generation 时一次测试捕获旧助手，执行前同步后因引用失效拒绝运行；未增加自动改选或重放。随后按新快照重新开启独立验证通过。后续必须继续验证工具往返、语音、Portal、恢复/退出及完整构建门禁；全部完成后再进行用户要求的独立子代理协议审查。
- 随后实际聊天完成 DeepSeek → Firecrawl `firecrawl_scrape` → 模型续答，目标为公开 `https://example.com`，已展开检查真实工具参数与返回的 markdown/metadata，回答标题为 Example Domain；截图 `enterprise-real-firecrawl-roundtrip.png` 已检查。空间“开始对话”中复用的助手卡、个人/企业助手选择列表及选择后进入新聊天均已在同一模拟器操作验证，截图 `enterprise-start-conversation-picker.png`、`enterprise-reused-assistant-selector.png`。未下发默认/失效引用的路由目前由定向测试覆盖，不将它们混称为本次真实服务器下发场景。
- `EnterpriseAssistantEntryAndroidTest` 的两个实际 Compose 设备用例通过：无默认且无推荐开场仍可明确选择助手，失效旧选择先显示原诊断再由用户重选。测试使用隔离的 query/application 替身，不改真实设备 Session 或服务端配置；不声称 Core 实际发布过这两种配置。
- Core 随后新增第四种识别协议 `DASHSCOPE_HTTP_ASR`。Android 已重新生成当前类型、增加明确协议映射与非实时字段校验；mapper 9 项定向测试通过，完整上传/转写执行仍在 P4 中。当前固定 OpenAPI SHA-256=f154f15fb429bdbdc90139e77454df2374b5c3177787bd0b0818dba5ef139df3，与 Core 源文件一致；旧包 TzTeNh 的摘要不是此新合同的交付证明。
- 最新一次设备重新同步并重跑平台 3 项测试通过，但实际服务仍返回 generation=3、asr=[]、defaultAsrId=null。文档和 schema 新增识别协议不等于当前登录主体已拿到对应资源；继续等待实际发布并复核，不替 Core 伪造识别资源。

### 平台朗读与文件识别执行

- 实时识别已接入内存 RealtimeAsrTransport：OpenAI 与 DashScope 明确映射原 realtime setting，平台 token 在 Session 准入锁外获取；握手使用原资源完整路径、编码后的 query、generation/interaction 头。原录音控制器保留异常和上游事件诊断，发送队列拥堵时明确失败，不再静默丢弃 PCM。
- 回归曾复现 OkHttp 5.4.0 在 `503 + Retry-After: 0` 下重复 WebSocket 握手的问题。已由 SingleAttemptWebSocketFactory 使用正式 HTTP upgrade socket API 解决：普通 HTTP 网络拦截器在 follow-up 前保留失败正文并终止，成功连接交回原 WebSocket 编解码器；取消同时终止原握手 Call 与 WebSocket。直接在升级 socket 的 sink 统计实际写入，覆盖文本、压缩、自动 pong 和关闭帧，超限停止连接；已移除近似字节预算与预留方案。真实 TCP 升级、文本往返、关闭、握手中取消、文本与 pong 超限测试以及 428/503 不重发回归通过。设备及真实 Core realtime 资源验收仍需补齐。
- 随后 14 项 JVM 定向测试通过（升级连接 3、平台语音协议 3、语音应用 8）。API 36 模拟器上 speech 模块的 4 项 realtime 生命周期测试通过：平台显式地址与两个协议参数消费、上游诊断、取消等待、旧连接隔离、服务端/用户停止及录音释放；这些使用真实 Android 录音器和可控 WebSocket 替身。JVM 另用真实 TCP 测了升级传输，两类证据不可合称真实 Core realtime 供应商验收。本次 speech 测试包独立安装，没有卸载已接入的主 App。

- Core 后续实际发布至 generation=5，默认 ASR 为 DASHSCOPE_HTTP_ASR / qwen-audio-3.0-asr-flash。已接入模拟器成功同步，使用原平台凭据、版本和资源路由完成真实转写：先经平台 MiMo 合成已知英文句子，将返回 PCM 包装为对应采样率的 WAV，再经平台百炼 ASR 得到包含 “quick brown fox” 与 “lazy dog” 的识别文本。独立设备测试通过（8.036 秒），临时录音与执行 lease 在 finally 清理。此证据覆盖真实合成、文件编码、平台转发和供应商转写，不等同于麦克风声学采集或实时 WebSocket 验收。
- 本地语音错误响应统一保留 RoutedHttpException 的 status/detail；非法或重复字段的 428 不进入版本同步分支，302 不跟随。修正原先只断言通用占位错误的测试后，本地语音与平台语音协议 5 项回归通过。
- generation=5 上完整重跑平台设备用例 6 项通过（21.067 秒）：启动选择、MCP 目录、模型流式与辅助生成、MiMo 播放、系统播放、百炼文件识别。手动操作现有聊天麦克风入口，完成系统权限授予、开始录音、停止及回到可录音状态；该次上传实际返回 HTTP 400，原响应 detail 为 `{}`，HttpAsrController 保留了 RoutedHttpException 与堆栈。没有向模拟器麦克风输入已知语音，失败原因尚待核对录音内容及上游记录，不据此推断是静音，也不把该界面检查记为麦克风识别验收通过。

- 已接通平台 OpenAI/Gemini/MiMo TTS，通过内存 `SpeechHttpTransport` 传递完整地址和平台头，继续使用原 Provider、TtsSynthesizer 与 TtsController；系统 TTS 使用原设备引擎，无模型/音色字段或远端 binding。MiMo 的音色设计仍使用原编码器，不另建企业 UI 或编码器。
- 模型和语音的严格 HTTP 行为集中到 `common.http.withExplicitRoute`，共用 `RoutedHttpException` 与 `MAX_ROUTED_REQUEST_BYTES`；禁止重定向、自动认证及 HTTP 408/503 透明重发，保留真实失败诊断。独立语音先 preflight，原 turn 中的朗读继续原 interaction；刷新不替换已冻结版本或资源。
- OpenAI 与 DashScope 文件 ASR 共用 HttpAsrController 的录音和文件生命周期。新增两种文件协议编解码，按 multipart 或 UTF-8/base64 JSON 的实际开销计算允许的录音文件上限，上传前再次核对正文大小。到达上限明确失败并清理，不静默丢弃音频或重发。实时 WebSocket ASR 的平台执行仍未完成，不能将文件上传路径用于替代。
- 平台语音协议、语音 application、本地语音与模型请求回归先通过 25 项；补充禁止个人 TTS 下的企业系统引擎测试后，语音定向 13 项以及 AI 的请求凭据/重放回归通过。speech 模块测试包含文件编码上限边界与协议专属响应，全部通过。Debug 与 AndroidTest APK 构建通过；完整跨模块门禁仍待最终实施完成后运行。
- API 36 的真实平台 MiMo 用例已观察到播放器 Playing 并正常结束；企业系统朗读也完成相同播放验证，测试使用原应用 owner，并在结束后恢复原 TTS 选择。系统资源不取网络 binding，禁止个人 TTS 的行为另由真实配置解析加播放器替身测试验证，不把该政策场景冒称为当前 Core 发布配置。录音真实转写、平台实时协议、远端 Portal、重启恢复/退出以及最终独立审查仍未验收。

### 2026-09-19 范围与回归收口

- 用户要求远端 Portal 暂缓；P5/E15/E21/E22 已按当前交付范围修正。现有本地 Portal 与退出清理仍在回归范围，远端接线留待 Portal 正式交付。
- Core 当前 OpenAPI SHA-256 仍为 `f154f15fb429bdbdc90139e77454df2374b5c3177787bd0b0818dba5ef139df3`；已发布 generation=5 同时包含 DeepSeek Flash、Qwen 3.8 Flash、MiMo、System TTS、百炼 HTTP ASR 和 Firecrawl。模拟器切换至企业 Qwen 真实发出“Reply with two words: green moon”，收到“green moon”；界面截图 `enterprise-real-qwen-chat.png`。随后将测试选择恢复至“助手默认”/DeepSeek Flash。
- 模拟器个人空间可进入，默认助手和复用的助手选择器可打开；切回企业后原企业会话与发布配置仍在。此 AVD 的个人空间没有可用 API/会话模型，页面明确提示配置；因此只计入口、选择和域隔离回归，不计个人真实对话验收。
- `EnterpriseApplicationService` 在恢复门禁打开后自动继续持久化的 pending enrollment Bootstrap/同步；失败保留可见诊断且不阻断个人恢复。平台退出在 CLOSING 与原域工作停止后，按刷新互斥锁读取当前凭据；pending 轮换用原幂等键先恢复，再调用有界的 Core logout。本机退出不因远端失败停留，手动退出和启动恢复都可沿现有反馈显示“远端注销未确认”及诊断。定向网络测试验证 pending refresh→logout 顺序，退出测试验证断网仍完成本机清理；这不是对已接入真实 Session 的注销测试，真实会话需保留给后续联调。
- 独立协议审查发现四处收口：终态 pending 会挡住新 code、跨 origin 的 pending 缺切换路径、ClaudeProvider 自身记录企业请求内容、Android 对重复 MCP 引用比 Core 合同更严格；另发现已接入 Session 收到 Core `403 session_revoked` 后没有进入 REAUTH_REQUIRED。Android 已按既有 owner 修复：同 origin 只在确认的终态错误后替换 pending，临时失败继续原兑换；明确确认不同 origin 时有界注销旧 pending，即使旧 Core 离线也本地换源，并显示远端注销未确认；Claude 不写 body/messages/SSE/error body 日志，OpenAI/Gemini 流式回调也只记录不含内容的错误类型，Gemini 不记录引用元数据；合法重复 MCP 引用映射时去重；Core 撤销通知转交既有退出 owner。新增本机 HTTP 用例覆盖终态、临时、跨 origin 离线与撤销信号，mapper 用例覆盖重复引用。真实 Core 仍未执行撤销或退出，因为需保留已接入凭据；这些场景目前是协议网络测试与原退出状态机测试，不是生产端到端验收。
- 最终完整串行门禁 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 通过（11 分 31 秒）；AndroidTest Kotlin 编译通过，`git diff --check` 与生成代码 `--check` 通过。app/ai/speech 的 JVM 报告分别为 2285/381/22 项，均无失败或跳过。保留原数据覆盖安装 x86_64 Debug APK 后，模拟器冷启动仍进入已接入的企业工作助手和 DeepSeek Flash；在最新构建上经真实 Core 发送 “Reply with exactly: live ready”，收到 “live ready”，标题与建议回复正常生成。截图见 `enterprise-final-cold-start.png` 与 `enterprise-final-live-chat.png`（本任务本地可视化目录）。本轮未卸载原应用，也未调用真实 Core revoke/logout；远端 Portal/Feed 按用户要求暂缓，模拟器麦克风实际语音的供应商转写和实时 ASR Core 链路仍不计通过。
- 2026-09-19 独立全量 diff 审查另发现三个发布边界：已提交快照在回执失败后重启不会自动重报；OkHttp 可对 Control 的 408/503 重发带正文请求；Local 包可接受无法执行的平台语音协议。现已让 Session owner 投影可恢复的平台访问，应用恢复门禁后同步已发布 Session 并幂等重报；兑换、refresh、回执和注销统一标记单次发送；Local 包发布前拒绝 Gemini/MiMo TTS 及 DashScope/实时 ASR。相应 JVM 失败路径和本机 HTTP 用例通过，不把它们当作真实 Core 故障注入验收。
- 最新 Core `v4-asr` 合同样例加入 DASHSCOPE_HTTP_ASR 且改为默认识别资源；Android 固定样例已按 Core 字节更新。当前 Client OpenAPI 与 Android 副本 SHA-256 均为 `f154f15fb429bdbdc90139e77454df2374b5c3177787bd0b0818dba5ef139df3`；最新 `cases.json` 双方均为 `1a40f29be101faa14abb00af482d91d1723fe9fa0822c4f08471339a54b09c71`，相关合同、映射和语音定向测试通过。
- 同一已接入 API 36 模拟器实际检查了“开始对话”推荐开场及复用助手选择器；已下发默认助手自动高亮，个人与企业助手同表面可选。语音设置页沿用个人的类型卡片；企业 MiMo 与 System TTS、百炼 HTTP ASR 按平台默认选择显示，只增加“由企业提供”的来源信息。截图保存在任务可视化目录 `enterprise-start-final.png`、`enterprise-assistants-final.png`、`enterprise-speech-final.png`、`enterprise-asr-final.png`。用户最终确认平台空间继续保留现有“企业工作台”按钮，并打开既有的本地 Portal 演示页；因此移除 `PortalDocument` 对 Platform 来源的拒绝，页面仍绑定当前企业 Session，不增加远端 Portal 协议、页面或入口。

- 同日重新核对当前最新 Portal 完整交付包 `android-integration-QzcnBe`：外层 `sourceHash=f85c87a300701b5777da58718f7a241ba6545c0de0767672a83c5d4cbdc10eea`，150 个 manifest 条目与实际文件集合完全一致，逐项 SHA-256 均通过；内层 Core 集成资料 `sourceHash=8394a313df1ca22eb9c8ce29f6680e791bc709e6b3d65e106eb73988d9a8f741`，与 Core 当前生成资料一致。九份 Android 消费的 Portal 合同现与该包字节一致；本次变化仅是 client-feed.schemas.json 中 OpenAPI 源摘要及其 manifest 摘要，未接线远端 Portal/Feed。
- 完整门禁在上述生产代码改动后以 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 再次通过（9 分 17 秒）；此后仅补充测试断言及同步 Portal 合同摘要，相关 `EnterprisePackageTest`、`PlatformControlClientTest`、`PortalProtocolTest` 定向复跑通过。最新 x86_64 Debug APK 以覆盖安装保留已兑换会话，冷启动仍进入企业工作助手；在此构建上通过真实 Core 发送 “Reply with exactly: ready six”，得到 “ready six” 和标题/建议回复。设备 `PlatformModelLiveAndroidTest` 带 `platformLive=true` 实际执行 6 项通过（22.589 秒），覆盖模型流式与辅助生成、MCP 目录、MiMo/System 播放、百炼已知语音转写及启动助手；助手无默认/失效选择 2 项、真实 AndroidKeyStore 1 项、来源确认 1 项设备用例也通过。截图 `enterprise-recovery-final.png`、`enterprise-recovery-chat-final.png`。全部使用保留数据的覆盖安装或直接 instrument，没有清除已接入 Session。
- 2026-09-19 后续验收：同一真实 Core 的只读 `request_usages` 显示模拟器麦克风两次上传均已到达百炼上游并由上游返回 400；请求体分别约 512 KB 与 647 KB，非本地准入或转发失败。同一 ASR 资源对已知语音 WAV 多次返回 200。当前既不能把 400 判定为编码问题，也不能把已知 WAV 成功替代真实麦克风识别。需在有可控声学输入的设备上完成 E10。真实冷启动前后只读比较 Core Session 的 `applied_reported_at`，从 2026-09-18 18:19:43 UTC 更新到 18:21:00 UTC，证明已发布 Session 自动同步并幂等重报 applied 回执。
- 本地企业回归进一步在已接入模拟器的隔离测试目录执行：`PortalWebViewAndroidTest` 8 项通过，`LocalEnrollmentAndroidTest` 2 项通过，不修改平台 Session。签名 Release x86_64 APK 已与 Debug 并存安装并实际启动至个人空间；尚无新的有效一次性 Core 接入资料，因此 Release 的 HTTP 扫码/粘贴、真实企业资源执行与退出，以及真实相机扫码仍标为 E01/E22 未验收。旧资料已过期且已兑换，不能重用或清除现有 Debug 会话冒充首次接入。
- 同一已接入模拟器上的隔离数据测试进一步通过 7 项：`EnterpriseDataRemovalAndroidTest` 2 项验证企业退出后跨 scope 保留与中断删除恢复，`EnterpriseImageGenerationAndroidTest` 1 项验证本地图像生成、背景引用及切域失效，`EnterpriseAppliedStateAndroidTest` 4 项验证已应用配置的磁盘重开、原子发布失败、撤销与损坏恢复。测试均使用独立目录/内存数据库，没有清除当前平台 Session；它们补足 E18 的 Android owner 回归，但不代表真实 Core 注销或个人空间真实供应商对话已验收。
- 真实 E11 工具调用已补齐：保留原平台 Session 的 x86_64 Debug/API 36 模拟器，用已发布的企业工作助手与 DeepSeek Flash 请求调用 Firecrawl `firecrawl_scrape` 抓取公开 `https://example.com`。会话显示工具调用与返回；工具详情的实际 JSON 含 `metadata.title=Example Domain`，助手随后报告页面标题。Core 只读 `request_usages` 同时记录 generation=5 的 MCP 请求已转发、HTTP/上游均 200（并有初始化 202）；截图 `enterprise-mcp-live.png`。Firecrawl 响应为缓存命中，故此证明 Android 到 Core/Relay/MCP 的真实工具往返和可见结果，不证明强制实时抓取或全部 MCP 生命周期故障场景。
- E08 的真实企业看图路径也已验证：从 Android 系统图片选择器选择仓库自带图标，经现有附件导入与会话发送到 generation=5 的企业 DeepSeek Flash。用户消息保留图片缩略图，Core 同时记录模型请求已转发且上下游均 200；模型准确描述了浅米白背景、橙色四瓣外框、黄色/深棕同心圆和白色高光，与原图相符。截图 `enterprise-vision-live-input.png`、`enterprise-vision-live.png`。此证据覆盖一次真实图片输入、持久会话显示与平台多模态转发；其他模型、超大附件及更多轮图像历史仍按 E08/E19 边界分别验收。
- 随后强制停止并冷启动同一 Debug 包，从企业会话历史重新打开该对话，图片缩略图、原提问与模型回答均仍在；截图 `enterprise-vision-reopened.png`。这进一步覆盖 E08 的一次真实附件持久化/重开路径，不替代数据库迁移与全部多轮附件边界测试。
- 同一持久对话内手动切到企业 Qwen 3.8 Flash 后，追问上一轮图片的五词英文概述，真实模型返回 `Orange eye shape on cream`；会话标题/历史与 DeepSeek 的图像回答仍可见，截图 `enterprise-cross-model-history.png`。这验证企业助手模型可切换、跨模型多轮历史与新模型请求；该回答也可从前一轮文字推断，所以不把它单独计作 Qwen 原图视觉能力验收。测试后已恢复“助手默认”模型选择。
- 进一步删除模拟器 `/sdcard/Pictures/` 下本次专用原始测试图，再强制停止/冷启动应用并从会话历史重开；图片缩略图及原对话仍可读取，截图 `enterprise-vision-import-survives.png`。证明已导入附件由应用持久 owner 持有，不依赖系统选择器的原文件继续存在。
- E08 补验企业 Qwen 3.8 Flash 原图视觉：在全新企业会话从系统图片选择器上传同一公开测试图，普通发送后 Qwen 首轮回答“一只眼睛（eye icon）”；Core generation=5 的 Qwen 模型请求已转发且上下游均 200，截图 `qwen-vision-first-send.png`。另一轮更详细的看图请求首次只提交用户消息、未调用 Core；点“重新生成”后 Qwen 正确说明橙色外框、黄色圆盘、深色瞳孔与白色高光，截图 `qwen-vision-regenerated.png`。代码的发送键长按本来就会仅添加消息、不请求模型；目前无法证明该次 ADB 点击是否触发长按，随后新会话普通发送一次成功，故不把单次现象定性为模型/附件实现缺陷。测试后恢复“助手默认”并清除模拟器本次专用原图。
- E07/E11 再补企业 Qwen 工具往返：全新会话选择 Qwen 3.8 Flash，请求通过 Firecrawl `firecrawl_scrape` 抓取 `https://example.com`；会话显示真实工具步骤并回答 `Example Domain`，Core generation=5 同时记录 Qwen 模型与 MCP 上下游 200，截图 `qwen-mcp-live.png`。返回仍为 Firecrawl 缓存命中；测试后恢复“助手默认”。至此当前发布的 DeepSeek、Qwen 两个模型均有 Android 真实文本、图片与 MCP 工具调用证据，未发布到当前快照的其他模型协议仍不能据此计为设备通过。
- E19 边界复核：Core 当前 `defaultMaxRequestBytes=10<<20`，Android 的 HTTP 模型路由按完整编码请求体 `contentLength()` 在网络前拒绝超额，MCP 按 UTF-8 JSON 字节、文件 ASR 按 multipart/Data URI 实际开销、WebSocket 按实际写出的客户端帧累计计数。新增本机 HTTP 测试以含图片 Data URI 与历史工具结果的 JSON 请求验证恰好 10 MiB 可发送，增加 1 字节即在外部 I/O 前拒绝；定向 `RoutedRequestTest` 通过。已有文件边界及 WebSocket 文本/自动 pong 超限测试覆盖相邻入口。此处仍是编码边界与本机传输证据，尚未执行 Android 上接近上限的大图、长录音及实际 Core 超限场景，因此 E19 不标完整通过。
- E13 的真实 428 屏障补验：在保留已接入 Session 的 API 36 模拟器上，经当前平台模型资源提交上一代 generation 的单次请求。`PlatformModelLiveAndroidTest.enrolledPlatformRejectsStaleGenerationBeforeForwarding` 设备测试 1 项通过、无跳过，Android 解析到 `managed_snapshot_required` 和目标 generation=5；Core 只读 usage 同时记录 `http_status=428`、`forwarded=0`、无上游状态、`error_class=MANAGED_SNAPSHOT_REQUIRED`。这证明真实 Core/Relay 未转发的版本屏障，不代表 401/403/429 或断流场景均已通过。
- 2026-09-19 按最终 Debug 验收口径重启本地 Core/Relay/Caddy，并以当前局域网 HTTP 地址 `http://192.168.100.138:9100` 完成一份新接入资料的粘贴接入；Debug 显示企业“MEASIX 企业接入验证”、用户“企业体验验证”、状态“已就绪”。覆盖安装最新 Debug 包保留该 Session 后，平台空间的既有“企业工作台”已实际打开 APK 内置本地示例页，显示企业会话有效及已应用配置版本 5；截图 `enterprise-platform-local-portal-final.png`。同一构建通过当前 Core 的 DeepSeek Flash 发送 “Reply with exactly: core ready” 并收到 “core ready”；截图 `enterprise-current-core-live-final.png`。`PortalDocumentTest` 新增 Platform Session 回归并通过，`assembleDebug` 通过；本轮按用户要求不重复 Release 验证。该局域网地址是本次运行地址，不作为固定部署合同。
- 2026-09-19：按 Core 的录音验收要求，`HttpAsrController` 在停止录音、写 WAV 头及调用平台 transport 之前累计检查 PCM 的 RMS、峰值和非零样本数。空 PCM、仅一个非零样本和低于最小持续信号的录音抛出 `NoSpeechDetectedException`；应用映射为“未检测到语音，请重试。”，因检查位于 `transcribe` 调用之前，不会上传。`PcmSignalStatisticsTest` 的三项定向 JVM 用例通过。模拟器已重新授权麦克风，并启用主机麦克风映射；本次供应商验收采用更可重复的已知有声 WAV。
- 同一保留企业 Session 的 API 36 Debug 设备运行 `PlatformModelLiveAndroidTest.enrolledPlatformTranscribesKnownSpeechThroughPublishedAsr` 通过（11.638 秒、零失败）：企业 MiMo 先生成 “The quick brown fox jumps over the lazy dog.” 的 PCM，将其封装为 WAV 后经已发布 DashScope ASR 资源转写，测试断言 `output.text` 含 “quick brown fox” 与 “lazy dog”。Core 只读 `request_usages` 随后记录该 ASR 资源请求体 215,288 字节、Relay 与上游 HTTP 均为 200、响应 374 字节；这是文件识别经真实 Core/Relay 的完成证据。该项不等同于主机麦克风实际说话的声学质量验收。
