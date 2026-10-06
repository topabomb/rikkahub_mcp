# MCP 架构与生命周期

本文描述当前 MCP 实现的 owner、状态边界和确定性协议。

## 1. 不变量

MCP 的“工具能力”和“当前能否连通”是两类正交事实：

```text
已确认目录（durable LKG） ──决定──> Agent 可见 schema
连接会话（ephemeral）      ──决定──> 本次 tools/call 能否送达
```

因此：

- timeout、断网、5xx、后台、Doze、连接重建和重试耗尽只改变连接健康，不删除同 definition 的已确认目录；
- 用户手工刷新和服务端 `notifications/tools/list_changed` 是明确的目录重验请求；只有完整、合法的
  `tools/list` 结果成功落盘后，才原子替换后续 turn 的 schema；
- 当前运行中的 turn 只使用 run-start 快照。目录刷新、网络变化或 Settings revision 不会改写已经发给 Provider 的工具前缀；
- 用户禁用/删除 server、修改连接 definition、禁用工具或收紧审批，是本地明确撤销。旧快照在远端副作用前 fail-closed；
- 工具已经通过不可撤销调用承诺后，不因随后发生的配置或会话变化丢弃成功结果。承诺后 transport failure/timeout 返回
  `status=unknown`，不得自动重放可能有副作用的调用。

## 2. 事实与唯一 owner

| 事实 | 唯一 owner | 存储 | 读取边界 |
| --- | --- | --- | --- |
| 用户 MCP 定义、启用状态与工具 enable/approval policy | `SettingsStore` | Settings DataStore | Coordinator、Query、Turn 捕获 |
| 企业 MCP 定义与执行描述 | `EnterpriseAppliedStore` / `EnterpriseSessionController` | Applied manifest 与不可变配置 | `ConfigurationResolver` 解析；平台 binding 不转换为用户配置 |
| 完整远端工具目录 | `McpCatalogStore` | `mcp_catalog` DataStore | Runtime 启动恢复和提交 |
| 跨 server 注册表、触发汇流和并发预算 | `McpRuntimeCoordinator` | AppScope 内存 | application / turn |
| client、generation、授权 Job、刷新与恢复调度、连接健康 | 每 `McpRuntimeKey` 一个 `McpServerRuntime` | AppScope 内存 | `McpRuntimeStateStore` |
| OAuth 网络流程、refresh single-flight 与 Settings CAS | `McpOAuthCoordinator` | AppScope + Settings | `McpServerRuntime` / invocation admission |
| transport/client 创建与完整分页发现 | `McpProtocolClientFactory` / `McpCatalogDiscovery` | 工厂持有进程内共享 HTTP client，发现无独立状态 | `McpServerRuntime` |
| 已承诺工具调用和结果/Artifact 补偿 | `McpToolCallExecutor` | invocation 内存 | Coordinator |
| 单个 run 的 Provider 工具集合 | `TurnMcpCapabilitySnapshot` | run 内存 | `TurnToolSetFactory` |
| UI command / joined read model | `McpApplicationService` / `McpQueryService` | 无独立状态 | Compose / ViewModel |

`McpCommonOptions.toolPolicies` 只保存工具名、enable 和 needsApproval。远端 description/input schema 不写回
Settings；导入、编辑、OAuth 更新也无权覆盖 Catalog。

### 配置读取与执行租约

个人连接维护和 OAuth 只读取 `SettingsStore.userMcpDefinitions` / `withUserMcpDefinitions`，不读取全局
个人 `userSettings` 投影。两入口复用既有定义规范化规则；观察流只触发收敛，不能代替调用准入。
`McpRuntimeDefinition.withCurrent` 将原配置 owner 的授权边界保持到 Runtime 接受或拒绝定义，
锁顺序固定为配置 owner → Runtime；Runtime 不保存第二份配置快照。连接、发现、恢复、目录激活和
调用准入均复验定义，网络和 OAuth I/O 位于这些锁外。取消等待释放配置 gate，清理仍归原连接 owner。
初次读取只恢复已有目录，选中的 turn、显式刷新或后续配置变化才触发连接；初始化不排队连接全部个人服务器。
企业执行以 `(server reference, 原 RealmAccess, interactionId)` 为连接键；借用用户 MCP 也使用独立的企业执行连接，
但其定义、凭据与目录继续归用户 owner。个人维护连接仍以用户 reference 为键，不复制用户定义。
`McpConnectionDefinition` 区分用户定义与临时受管 binding；企业凭据不转换成可编辑 `McpServerConfig`。
原 Session → Settings → Runtime 是企业连接和调用准入的锁顺序，网络等待在锁外。
Settings 和 Runtime 锁等待后通过原 definition 的 `requireAuthority` 复验时间授权；
Session 锁可阻止退出状态并发写入，但不能阻止墙上时钟越过期限。
目录提交回执返回后、Runtime 激活前也复验原授权；期间到期按原提交回执回滚，不发布 Ready。
`McpExecutionLease` 在捕获 binding 前交给原 Turn owner，等待用户期间保留，CONTINUE 转交同一租约。
终态与退出在原 owner 上关闭并等待全部 transport，之后释放 binding；失败保留清理所有权。

### 企业平台连接

平台连接使用 `McpConnectionDefinition.ManagedPlatform`。平台连接消费同一顶层模型捕获的 AppliedVersion 与 interactionId，以原 Session 签发执行 lease；完整 URL 来自 Platform execution，不构造 Local binding。`McpProtocolClientFactory` 仍唯一创建 SDK transport/client，平台 Bearer 由原 lease 的请求回调在每个 POST/GET 之前取得，刷新 I/O 不进入配置/Runtime 锁。平台 catalog digest 只含身份、公开 route、release/hash、generation 和 authOwnership，token 轮换不改变目录或连接身份。只读目录检查使用 `readExecution`，不要求网络或额外执行 lease。

Direct MCP 只装配已解析助手选中的服务。企业助手仅使用发布的 `mcpBindings`；固定引用不可删，
使用偏好不能增加未绑定的受管服务或恢复已移除的绑定。准入允许时仍可添加用户 MCP；用户助手可选择本域受管服务。

服务 ALL 使用动态发现目录，不增加逐次确认，也不锁定企业发布哈希。服务 ALLOWLIST 使用准确工具名和完整 Tool 的
JCS UTF-8 SHA-256 `contractHash`，包括 description、outputSchema、annotations、`_meta` 和扩展字段。
`McpCatalogTool.contractHash` 与共享 `mcp-tool-contract-vectors.json` 验证 Unicode、属性顺序和 ECMAScript 数字语义。
无法按 JCS 编码的定义在 ALLOWLIST 中仅使该工具按契约不匹配不可用，不中断其他工具的投影；ALL 不要求此摘要。
助手 ALL 不增加过滤；助手 ALLOWLIST 取名称子集，不能越过服务授权上限。`EnterpriseMcpResource.toolAccess`
是 Turn 捕获、只读检查、UI 与调用门共同使用的纯投影，不保存第二份授权状态。
ALLOWLIST 仅按发布的 AUTO/REQUIRE_CONFIRMATION 决定审批，不从远端 annotations 推断。
审批复用原 `ToolCallRuntime`、`ToolBatchRunner` 和同一 Turn 的继续协议；Target 仍是 USER_INPUT_ONLY，
需要明确确认的工具返回 `approval_unavailable`，ALL 不增加人工交互。
企业策略禁用用户 MCP 时，准备结果保留 `POLICY_BLOCKED` 结果供界面提示，但不建立连接或阻断对话；设置页仍显示原选择并允许移除。
其他显式引用不可执行时准备失败。Gateway 独立装配完整工具对，REQUIRED 目录未就绪时拒绝准备。
Gateway 使用开关只影响新 interaction；在途执行仍复验原 Session、助手和资源是否存在。
当前 Core Snapshot 映射只提供普通受管 MCP，`PlatformSnapshotMapper` 的 Gateway 目录为空；下述 Gateway 校验描述已有类型与执行约束，不代表当前平台接入已提供独立 Gateway 资源。
受管 namespace 由稳定资源引用摘要派生，避免本地化名称或长资源 ID 破坏 Provider 工具名。

受管 Streamable HTTP 使用禁止重定向、关闭透明请求重试并带 `PrivateRequest` 的专用共享 client。
原 generation 与 interaction headers 来自捕获的 binding owner，私有包不能覆盖这些协议头。
受管连接失败保留异常类型、原始 message/detail 和 cause；`McpStatus.Error` 使用 `userVisibleDiagnostic` 与脱敏堆栈供诊断。仅删除凭据、令牌和认证字段，不能用通用连接错误替代实际 HTTP 状态或底层原因。`McpToolCallExecutor` 继续携带原异常，取消保持传播。
POST 与通知/恢复 GET 都解析有效 `428 managed_snapshot_required`，先封闭该 Runtime 的连接/调用准入，随后由原 `TurnFinalizer.stopInteraction`
按 Runtime/turnId 捕获当前 worker、提交终态并等待租约清理；随后由用户在空间页手动同步配置，barrier 不自动下载 Snapshot。
暂停已完成的 worker 和 CONTINUE 后的新 worker 都执行上述终止与清理；不等待过时的 START Job，也不停止后续新 turn。

### 传输与完整目录解码

`McpProtocolClientFactory` 在首次真实 transport 创建时同步且唯一地初始化共享 Ktor client，调用位于现有 server 的 IO 连接任务；
不在 Application/DI 构造阶段初始化 Ktor，也不为测试 override 初始化真实 transport。初始化返回后复查取消，超时/取消的连接
不取得新 transport；已创建的共享 HTTP client 继续由工厂持有，单个 server 关闭或重连不能关闭它。

`McpCatalogTool.definition` 保存完整远端 Tool JSON；名称、说明与输入 Schema 都从它投影，
`outputSchema`、annotations、`_meta` 和扩展字段不会在 SDK 类型转换时丢弃。已有个人目录的三字段 JSON
仍是合法的已保存目录表示，序列化字节与摘要不变；不得把历史个人缓存当作已经验证的企业 Gateway surface。

实际 HTTP JSON、POST SSE、GET SSE 与个人 SSE 收包都经过 `McpCatalogWire`：同一次 SDK `tools/list`
调用携带短期 capture，发送时关联 SDK 生成的 request ID，响应在交给 SDK handler 前保留完整结果。
SDK 继续拥有请求、超时和取消；capture 在调用结束的 `finally` 中释放，不能缺失时回退 typed Tool、
另发请求或维护第二份目录。重复响应以首个匹配结果为准，原始 ID 必须匹配，不能借 SSE 的 replay ID
重写认领其他响应。JSON 解码拒绝重复键并限制帧字节数与嵌套深度。
POST 使用 Ktor streaming `execute` 作用域，在预读整个 body 之前执行边界检查；收到原请求的终态响应
后即可结束 POST SSE，不等待远端关闭流。GET SSE 保留持续接收和断线重连，只有指定 resumption
请求的匹配响应才结束该恢复。多行 `data` 按 SSE 换行规则还原，不拼接为不同 JSON。
初始化响应确认后，后续 HTTP 请求带协商后的 `mcp-protocol-version`。

`McpStreamableHttpTransport` 与 `McpSseTransport` 从 [kotlin-sdk 0.15.0](https://github.com/modelcontextprotocol/kotlin-sdk/tree/0.15.0)
的对应 transport 源码适配，复用 SDK `AbstractClientTransport`、RPC Client、重连参数和错误类型；
本地修改集中在上述完整 JSON 解码、capture 释放、传输 I/O 所有权和不记录请求正文，移除未使用的上游构造/发送重载。
`McpClientTransport` 持有自己的 I/O Job；初始化、POST 和 GET 都在该 Job 下运行，关闭必须取消并等待实际 I/O。
GET 使用 streaming `prepareGet().execute`，不使用会在共享 HttpClient scope 留下任务的 SSE session builder。
SDK 提前进入终态或断开 Client 引用不能代替实际清理；原 transport 的重复关闭仍等待同一 I/O owner，
单个 transport 不关闭共享 HTTP client。个人 SSE 在 endpoint 前结束属于明确连接失败；
传输内部取消不能伪装为 Runtime 调用者取消而留下 Connecting，仍须清理原连接并发布恢复状态。
已消费事件立即更新恢复游标和 retry，成功建立流即重置重连计数；
只有网络 I/O 与明确可重试 HTTP 错误重连，坏 JSON、超限帧和其他协议错误报告并停止接收。
上游许可证全文随应用放在 `assets/licenses/mcp-kotlin-sdk-LICENSE.txt`。

协调器可以从恢复 IO 线程构造；ProcessLifecycleOwner 观察者通过 AppScope 的主线程任务注册，不能在构造调用线程直接注册。

### OAuth 信任边界

编辑或同名导入只在 transport、canonical resource 和静态 headers 都未变化时保留原 OAuth 状态。任一信任边界变化都会
清除旧 access/refresh token 与 client secret，避免旧资源凭据发送到新 endpoint。`definitionDigest` 包含全部静态 headers
（包括手工 `Authorization`）；自动 OAuth token 独立存放在 OAuth 状态中，其正常轮换不会使已确认目录失效。
授权和 token refresh 的持久化 lease 同时校验 transport、canonical resource、规范化静态 headers 与 OAuth revision。
OAuth 回调使用 loopback：
`McpOAuthCallbackServer` 每次授权绑定 OS 分配的 ephemeral loopback 端口，redirect path 固定为 `/callback`，
高熵 `state` 绑定本次 coordinator lease。未知 path/state、畸形或超限请求不会消费有效回调；只接受一次合法
code/error，响应 `Cache-Control: no-store`，日志不输出 code/token/verifier。授权服务器若
错误要求固定端口，返回明确互操作错误，不回退自定义 scheme。`McpOAuthCallbackService` 只在浏览器授权期间保活 loopback
socket，并以引用计数 lease 支持并发授权，不保存 token、MCP config 或授权阶段。发现元数据、issuer、authorization、
token 与 registration endpoint 必须保持 HTTPS，不接受 fragment 或 userinfo，且资源/issuer 精确匹配。PKCE、resource、canonical server URI 与 trust-boundary/revision CAS
全部保留：callback 到达后、token 持久化前再次校验信任边界与 revision，旧回调不能写入新 definition。
回调或 refresh 响应跨越任一信任边界变化时只能丢弃。重复启动授权必须先取消并等待旧 Job 完成，再推进 revision 后启动新流程。

### 目录持久化与版本

从曾将完整 schema 写在 Settings 的版本升级时，DataStore migration 在重写 policy-only Settings 的同一事务中生成一次性
catalog staging；`McpCatalogStore` 从个人迁移 staging 只接收完整、非空候选，提交后删除 staging，不保留旧 schema 读取旁路。手工备份 v4/v5 将
`mcp_catalogs.json` 作为 manifest 必需根；恢复 v3 时执行同样的一次性提取。备份恢复先让已经取得租约的旧迁移完成，再用
备份目录替换个人 Catalog，保留企业主体的目录，避免旧 staging 在恢复后写回孤儿目录。

Catalog 初始化只执行一次迁移、读取和发布；全部命令等待这次初始化完成。后续目录仅由 `McpCatalogStore` 的
`commitMutex` 内提交协议发布，不再由常驻 DataStore collector 回写内存。初始化取消或迁移失败明确拒绝命令；
目录读取失败不伪装为空备份。个人恢复不能绕过尚未成功完成的旧迁移。

`McpCatalogKey` 由 `ConfigurationScope` 与 server reference 组成：用户资源的目录归个人，企业资源的目录归
Deployment、User 完整主体；Session 不进入持久化目录身份。`catalog_document` 保存版本化目录，
已发布的个人 `catalogs` 数组只在一次性迁移或旧备份导入时读取，成功后原键删除。版本化文档存在时不回退旧键。
个人备份仅导出与用户 definition 匹配的个人目录；恢复入口统一拒绝企业记录，在同一提交锁内重新读取并保留
当前所有企业目录。仅旧个人键损坏可由明确的个人恢复替换；新文档损坏或读取失败必须拒绝写入，不能把企业事实
视为空。非法摘要、重复主体/资源键和资源归属不一致均为失败。

企业目录的 `McpManagedCatalog` 记录发布 generation，与目录 revision、连接 epoch 分开。
同一资源低于已确认目录 generation 的候选被拒绝且不夺取提交补偿 token；同 generation 的
Gateway surface 改变被拒绝。私有 binding 可在同 generation 轮换而改变 definition digest，
目录发布另在原 Session 的 Applied revision gate 内验证，旧 binding 的发现结果不能覆盖新配置目录。
旧 interaction 可保留原 binding 与已确认目录；发布不再获准时只能沿用匹配的已确认目录，无目录则明确失败。
同一 definition 的目录被其他 interaction 刷新后，初次和最终调用准入均读取 `McpCatalogStore` 最新已发布目录，
工具删除或契约变化不能由原 Runtime 缓存绕过。`McpRuntimeStateStore.publish` 同步更新同一 key、definitionDigest 和
managed 元数据的已有 Runtime 目录，保留各自连接状态、诊断和 sessionCallable；旧刷新恢复也不能倒退已知 revision。
因此 durable head 被新发布替换后，旧 interaction 仍保留原发布最后确认的删除结果；不同发布不改写其捕获的 Provider schema。
相同工具随新 generation 发布时仍持久化新 generation。这不能代替原 Session 的执行准入或服务端 428 barrier。

### Gateway 目录约束

Gateway 的 `McpGatewaySurface` 校验固定顺序 `discover_tools` / `invoke_tool` 两个完整 Tool 对象，
包含 outputSchema、annotations、`_meta` 和扩展字段；先验证再做目录排序。
采用 [java-json-canonicalization](https://github.com/erdtman/java-json-canonicalization) 的 RFC 8785 JCS
字符串经严格 UTF-8 编码，拒绝孤立代理项而不替换字符，再计算 SHA-256，与企业定义要求的 `sha256:<lowercase hex>` 比对；不使用普通 Kotlin JSON 序列化摘要替代。
目录构造与重开统一要求 `twg_*` 携带 surface、`mcp_*` 不携带 surface；其他企业资源类型拒绝进入目录。
目录重开再次验证 surface，缺失元数据不能降级成普通目录，也不能把个人 LKG 作为已验证的 Gateway 工具对。
Gateway 的目录 digest 使用已验证的 canonical surface digest；仅 JSON 对象键顺序变化不推进目录 revision，
保留已确认 Tool 对象和请求前缀。个人与 Direct MCP 的既有目录摘要算法保持。

### 运行状态发布

`McpRuntimeCoordinator.runtimeCapabilities` 是 runtime 的唯一公开状态源；底层由 `McpRuntimeStateStore` 对每个键以一个 immutable
`McpRuntimeCapability(status, catalog, sessionCallable)` 原子发布。Settings、Catalog DataStore flow 和 UI 不再形成第二条 runtime
读写路径。status 可变化而 catalog 保持不变，这正是离线仍披露 LKG 工具的协议。

## 3. 进程启动与按需激活

`validateMcpHeaders` 是配置与连接共用的纯校验规则：name 必须是非空 HTTP token，value 只允许可打印 ASCII 与水平制表符，不 trim 或改写合法值。`McpHeaderValidationException` 保留稳定 reason、从 1 开始的行号与可选导入服务器序号，诊断不含 header value。`McpApplicationService.upsert/importServers/overwriteByName` 在 Settings 写入前校验；批量导入先验证全部候选，再执行原子提交。

历史非法配置不会在 `connectionFingerprint`、`mcpDefinitionDigest` 或 `resolvedConnectionHeaders` 身份投影中抛出新校验异常。`McpServerRuntime.runConnectionOperation` 在原异常处理边界内、OAuth refresh 前校验静态头；`McpProtocolClientFactory.createTransport` 在 transport override 和 HTTP client 创建前再次校验最终 resolved headers。错误只影响原 server 的状态，不打断其他 server 的协调。`ManagedPlatform.requestHeaders` 每次取得认证凭据后复验，避免初次通过后轮换出非法请求头；受管错误不借用户 Settings 修补。

MCP 编辑器仅忽略本次新增且完全空的草稿行，已存空行仍需明确修正或删除。保存等待 application 成功才关闭；行校验和写入失败保留草稿、错误行与可复制的完整诊断。JSON 导入分别显示解析错误与配置业务错误，失败保留输入，不把 header 校验包装成 JSON 解析失败。取消沿原协程传播。

启动时先从 `McpCatalogStore` 恢复与当前 `definitionDigest` 匹配的 durable LKG。恢复目录不需要网络，也不会把全部
已登记 server 排进连接队列。新对话开始时只激活该 Assistant 选择的 server；新建、重新启用或修改 definition 的
server 会主动建立其自身连接。用户全局刷新显式激活全部 enabled server。

每个 `McpRuntimeKey` 只有一个 `McpServerRuntime`。它持有 mutex、generation、client、已接受连接请求的 fingerprint 和各 operation Job；
其子 scope 保留所有已接受的生命周期任务，取消或替换 Job 引用不会丢失尚未完成的清理。
连接替换与原始 transport 集合只在同一个 connection-operation mutex 下访问；新连接先完成旧连接清理。
移除先封闭准入并取消 runtime scope，由 AppScope 接受关闭任务，在状态锁外等待原任务完成并关闭原始 transport，
然后才从注册表移除。超时/失败保留 Runtime 与资源，公开错误并由后续命令重试；重新启用也须先完成旧资源清理。
SDK Client 即使已经丢失 transport 引用，清理仍使用 Runtime 持有的原 transport。
所有远程或持久化 I/O 在锁外执行，完成时以 generation/client/definition lease 重新验收。同 fingerprint 的重复触发合并到
已有 operation，definition 变化则取消旧 operation、推进 generation 并替换。跨 server 的 connect、首次 discovery 和健康
session 的 catalog refresh 共用一个全局 semaphore，最多 4 路并行；工具调用永不经过该门。连接/目录 attempt 的 timeout 从
取得 permit 后开始，排队时间不消耗 server 的远端操作预算。排队中的 server 不发布 `Connecting`，所以 20 个配置不会因为
前四个慢连接而全部显示 loading；已选 server 也不会等待无关 server。

## 4. 发现与目录提交

`McpCatalogDiscovery.fetchCandidate()` 从空 cursor 开始执行 `tools/list` 并遍历全部 `nextCursor`：

1. server 必须声明 tools capability；
2. 最多 64 页、4096 个工具；
3. 工具名必须非空且 server 内唯一，cursor 不得重复；
4. 保留原始完整 Tool `JsonObject`，从中投影 Schema，保留 `$schema`、`$defs`、`$ref` 与扩展字段；
5. 全部页面成功后才形成 candidate；
6. `McpCatalogStore.commitCandidate()` 在单一 commit mutex 下计算 digest、revision 并原子落盘；
7. 完整成功的受管 Direct MCP 空目录正常提交并替换旧工具；个人空目录仍拒绝并保留 LKG，Gateway 仍须完整工具对；相同 digest 是 no-op；
8. definition 已变化时旧目录不再匹配，不能借 LKG 伪装新 server 已发现。

Catalog Store 对成功提交、相同目录的 no-op 和空目录拒绝推进进程内 head token；低 generation 拒绝不推进 token，不能夺取较新提交的补偿权。若 Server Runtime 在持久化后发现 connection lease 已过期，只允许在 snapshot identity 与 head token 仍匹配时精确回滚；旧 operation 不能覆盖更新的目录事实。

候选校验或写盘失败不推进 head token。提交取得所有权后，Store 等待 DataStore ack、目录投影和 token 更新全部结束，
再传播调用者取消；Runtime 对提交凭据的接收、原连接 lease 复验和必要补偿也在同一提交与补偿边界内完成。已接受的新目录
不会因紧随其后的取消或超时被 Runtime 恢复成旧目录；尚未接受的提交仍按原 snapshot/token 精确补偿。

## 5. 明确刷新与意外失败

| 触发 | 行为 | 是否改变 Agent schema |
| --- | --- | --- |
| 首次无 LKG 的新对话 | 连接并完整发现，最多等待 20 秒 | 成功非空提交后加入当前 run；失败则本 run unavailable |
| 用户下拉刷新 | 全部 enabled slot 执行真实 operation receipt | 成功提交后影响后续 run |
| 用户点击单 server 重试 | 强制重建该 slot 并等待真实 operation receipt | 成功提交后影响后续 run |
| `notifications/tools/list_changed` | 350ms 去抖，同 slot single-flight，运行中通知合并为一次 follow-up | 成功提交后影响后续 run |
| timeout、断网、5xx、transport close | 保留 LKG，进入同 slot 恢复调度 | 否 |
| App 进入后台 / Doze | 暂停普通恢复，等待前台；不轮询 | 否 |
| validated default network 恢复 / 回前台 | 仅恢复已激活且已断连的 runtime；健康 session 不执行 `tools/list` | 否 |
| 用户禁用/删除/修改 definition | teardown、撤销旧 binding；删除时清理 durable catalog | 是 |
| 用户禁用工具/收紧审批 | 调用前重验本地 policy | 后续 run 更新；旧 run 调用 fail-closed |

手工刷新与 `list_changed` 的共同点是“重新发现并提交”，不是直接清空 cache。刷新期间继续发布旧目录；分页中断、个人空目录、
超时、取消、校验或 DataStore 提交失败均保留 LKG。用户 command 等待其接受的 connection/catalog Job 及合并的 follow-up，
不能通过观察一个共享 status 猜测完成。前台 receipt 最多等待 20 秒；超时只结束 spinner 并提示剩余 server 在后台继续，
不会取消 AppScope owner 的连接或发现操作。

## 6. 连接恢复与移动端策略

恢复是每 server 单一调度器，不是页面 timer：

- 1–3 次为快速 equal-jitter，ceiling 为 2/6/15 秒；
- 4–8 次为 maintenance retry，ceiling 为 30/60/120/240/300 秒；每次实际等待在 ceiling 的 1/2 到 ceiling 之间；
- offline 时挂起等待 `NetworkMonitor.isOnline`，background 时挂起等待前台 StateFlow；等待不消耗 attempt，不进行固定轮询；
- 8 次耗尽后进入明确 Error，但 LKG 仍可见。下一次工具调用、validated network 变化、回前台、单 server 重试或手工刷新
  会重置恢复预算并立即尝试；
- 401 进入授权状态；404/408/425/429/5xx 与 I/O 类错误可恢复；其他 4xx/协议/配置错误不做盲目重试；
- 首次目录发现期间的 transport close 也进入同一恢复调度；握手后和目录刷新后的提交均复验
  当前 client、transport 与状态，关闭或授权期间的迟到结果不能发布 `Ready`；
- 失败分类检查 cause 链中的明确 HTTP 状态、OAuth 响应错误、SDK 连接/超时错误及 I/O 异常；本地超时按可恢复处理。
  SSE 的 GET/POST HTTP 错误保留状态码，OAuth 响应/协议错误不伪装成网络 I/O；OAuth endpoint 的 404
  属于配置/协议失败，不按 MCP transport 的 404 恢复；通知流耗尽使用明确异常。
  已排队的恢复操作独占 `RetryScheduled`/`WaitingNetwork` 状态，后续调用失败不能覆盖等待状态；
  已承诺调用返回授权错误时取消该代的恢复与目录刷新；迟到的 close、通知流错误和刷新完成回调不得覆盖
  `NeedsAuthorization`，由用户授权或显式重试重新建立连接；
- 当前 SDK 的 `StreamableHttpError` 不暴露响应头，恢复调度尚不消费 `Retry-After`。

Android default network 必须同时具备 `INTERNET + VALIDATED`；前后台和网络事件只唤醒同一有界恢复调度器。

## 7. Run 快照与调用结果

Master 和每个 Target 在 run 开始时调用 `prepareTurnCapabilities()`：已有匹配 LKG 时立即捕获；仅缺目录的已选 server 才等待
其 AppScope operation，全部缺失项共享 20 秒上限。随后生成 immutable `TurnMcpCapabilitySnapshot`：

```text
Assistant 选择
∩ 当前 enabled definition
∩ definitionDigest 匹配的确认目录
∩ 当前服务工具授权与助手工具选择
∩ 用户 enable/approval policy 或企业发布的明确审批规则
```

`list_changed` 或手工刷新成功后，下一 run 捕获新 revision；当前 run 保留原 Provider schema。受管 Direct MCP 调用前还须在最新确认目录中存在；
ALLOWLIST 的完整契约须匹配原捕获与当前发布的 hash，ALL 不做 hash 锁定。个人工具仍由 server 对旧 schema 返回协议错误。用户本地明确禁用/删除/definition 修改及 approval tightening 会在调用承诺前拒绝旧 binding。

本地审批前通过共用 `Tool.parseArguments` 检查参数为合法 JSON object；非空损坏 JSON、数组或标量直接失败，
不会先询问用户或发送 RPC。远端 `inputSchema` 仍完整保留，其完整 schema 与业务参数语义由 Server 校验；
客户端不通过局部扫描 `required` 或忽略 `$ref` 的自制校验器建立第二份参数契约。

本地 definition/policy typed command 与 `tools/call` 的不可撤销调用承诺共享
原 `SettingsStore` 配置 writer gate，经 `McpRuntimeDefinition.withCurrent` 保持到 Runtime 接受或拒绝，形成唯一线性化顺序：配置提交先取得门时，最终 admission 读取新配置并拒绝；
调用先取得门时，该 invocation 已进入 in-flight，随后配置变化只影响后续调用和披露。SDK 不暴露“首个 HTTP 字节已写出”的
精确边界，因此上层不把本地承诺伪称为“请求已经发送”；承诺后未取得完整结果的 transport/timeout 失败都保守报告
`status=unknown`，客户端不得自动重放。
该门只覆盖 Settings 提交或 invocation commitment 这一极短临界区，不覆盖 OAuth、connect、discovery 或远端调用等待，也不
持有 slot mutex 执行 I/O，因此不会把不同 server 的网络操作重新串行化。

`McpRuntimeCoordinator.callTool()` 将捕获的契约 hash 与 `ToolExecutionContext.approvedByUser` 传到初次和凭据刷新后的
最终 admission。两次均在原 Session → Settings → Runtime gate 内读取当前企业服务授权及助手绑定；
移除、授权收紧、契约漂移或缺少实际确认均在不可撤销承诺前拒绝。捕获的连接、release/generation 与 schema 不因此升级。

`McpRuntimeCoordinator.callTool()` 先从对应 `McpServerRuntime` 取得冻结 invocation lease，再由 `McpToolCallExecutor` 执行。
调用结束的企业额度刷新按原调用的 `McpConnectionDefinition.ManagedPlatform` 判断；企业空间中的 `User` 连接不发送该信号。
失败使用 `ToolExecutionFailure` 向 TurnRunner 返回稳定的 Agent 可见结果，并把 durable tool terminal 记录为 FAILED。
Agent 看到[工具错误返回协议](prompts-and-tools.md#5-工具错误返回协议)的 `status + reason`，必要时有 `detail`：

- `unavailable/tool_unavailable`：本地 definition、policy 或工具已经明确撤销；
- `unavailable/server_unavailable`：当前没有可调用 session、正处于连接恢复，或当前 session 尚未完成 capability 握手；内部恢复已经触发；
- `unavailable/authorization_required`：调用前需要用户授权；
- `failed/protocol_incompatible`：已完成握手的 server 明确未声明 tools capability，或完整结果无法按 MCP 内容契约投影；
- `failed/remote_error`：保留 `CallToolResult.isError` 的文本 content 与 `structured_content`，非文本内容用省略标记表示；明确 MCP error message 经裁剪后保留；
- `failed/result_processing_failed`：完整结果已收到，但本地投影或保存失败；远端副作用可能已经发生；
- `unknown/outcome_unknown`：承诺后未取得可确认结果；提示先核实远端状态。

server/tool 身份、generation、transport 阶段、`retryable`、`request_sent` 和恢复动作只属于内部诊断，
不进入模型上下文；本地异常的脱敏类型、消息和 cause 可进入有界 `detail`。

成功结果保留 text/image content 和 `structuredContent`。Image 先取得 Artifact lease，checkpoint 成功后发布；本地失败或未知
结果会精确回滚未发布 Artifact。取消始终向上传播，客户端不自动重放工具调用。

## 8. UI 投影

`McpQueryService` 是唯一 UI read port。UI 的工具计数来自有效 Catalog 与本地 policy，不从连接 status 猜测：

`McpRuntimeCapability` 同时原子发布 `catalog` 与由当前 client、transport、握手和调用准入阶段计算的
`sessionCallable`。前者只表示已确认的工具目录，后者表示当前会话能否进入工具调用；聊天页“就绪”还要求
至少一个本地启用的目录工具。断连后的 LKG 继续显示，但不计入就绪数量；设置页和选择器仍用目录存在性
决定是否显示工具与 loading。`CatalogStale` 的不同原因由状态详情表达，调用能力以 `sessionCallable` 为准。

- 有 LKG 时，无论 Ready、Reconnecting、WaitingNetwork、RetryScheduled、NeedsAuthorization 或 Error，都显示真实工具数；
- 只有首次无目录的 Connecting/Discovering/Authorizing 使用 spinner；maintenance recovery 使用静态状态和下次重试信息；
- 个人空目录显示 rejected；受管 Direct MCP 成功空目录显示服务器当前未提供工具，不计入可用工具数；
- 服务范围、助手范围、确认规则和有效计数只读显示；批准但未发现或契约已变化的工具保留可行动原因，详情渐进展开；
- MCP 选择卡以名称、连接或准入状态和原开关为主，范围与固定绑定合并为次要说明；Ready 仅在 `sessionCallable` 为真时称工具可用，部分工具缺失或变更按数量汇总，不取目录的首项错误代表整个服务器。首次尚未发现目录使用中性提示，成功空目录与后台重连仍保持可区分；
- 工具卡的“调用前确认”仅说明可执行工具的显式确认规则；契约变化提示管理员重新发布，两者不混用。只有实际存在描述或参数时提供展开动作；共享用户定义继续使用原启用与确认开关；
- 企业服务器的目录展开按钮显示紧凑数字，首次未发现目录显示发布项数量，已确认目录显示启用/总量；读屏保留完整的本地化数量说明；
- 共享用户定义的列表下拉刷新沿用个人连接维护；spinner 只绑定本次用户 command 的 20 秒 receipt，不绑定全局后台恢复。企业目录在首次执行时发现，页面只读展示已确认目录，不为浏览建立 interaction；
- notification stream 单独退化时保留 command transport 与目录，并显示 stale/degraded 原因；前台或手工刷新补齐遗漏；
- 来源、当前域准入、强制启用、Gateway policy 与工具目录由同一 presentation 提供，Compose 不直连 Manager/Store。

`userServers` 明确用于共享用户定义管理，保留原 OAuth、工具策略和编辑入口，并说明跨空间影响。
按域目录通过 `ConfigurationQueryService` 读取原配置，复用 Coordinator 的 `readCatalogCapabilities` 验证完整主体、generation、surface 和当前 binding 摘要。
目录行携带原 `RealmAccess`，聊天按原 Session 匹配；企业定义没有可编辑的 `McpServerConfig`，也不暴露 endpoint/header/credential。
读取失败发布 `McpCatalogReadState.Unavailable`，之后的 owner 变化可以恢复观察；私有 Applied revision 单独唤醒读取。
选中目录在选择变化时先清空，停止订阅后不保留旧企业 replay，重开先重新授权。多个在途连接不能被任一单独状态冒充为整个资源的连接状态；已确认工具目录独立显示。

企业 MCP 卡片没有编辑、删除、关闭、OAuth 或单工具策略入口；用户定义始终可管理，企业准入被禁止时显示原因。
Gateway 按发布 policy 对完整工具对启停，REQUIRED 只读；写入携带渲染时的 `RealmSelection` 并复验原选择版本，等待提交时禁用重复操作。
`com.measix/resolvedTool` 的安全元数据随原工具 checkpoint 持久化；默认工具卡用其业务 name，详情仅展示 gatewayToolId/name/status/requestId。
显示不解析下游输出猜测身份、不再次请求 Gateway，输出归档不删除这份业务身份。

## 9. 企业调用与只读检查

`assistant_inspect` 与管理页面共用 `readCatalogCapabilities` 的目录匹配规则：用户目录要求 definition digest 匹配；企业目录还要求原主体、generation、surface 与当前 execution 描述匹配。检查只读原域配置和已确认目录，不连接、不创建 execution lease，也不构成调用授权。

企业调用沿既有 `TurnToolSetFactory`、`TurnRunner` 和 MCP owner 执行。安全业务元数据经 `ToolExecutionContext` 的 deferred metadata 随 checkpoint 提交，不另写执行记录。受管 POST 与 SSE GET 在转换 SDK 错误前解析 Core Problem：额度、计量与核对阻断进入统一企业运行时错误；身份删除进入正式 Session 退出。个人 MCP 不使用该解析器，普通远端 429 不能变成企业额度错误。业务调用和 428 不自动重放，也不回退同名用户工具；可恢复的连接与发现失败仍按本篇恢复策略处理。

## 10. 验证边界

修改 MCP 时至少验证：旧 Settings/备份迁移与恢复竞态不会破坏 durable LKG；启动激活有界；分页、空目录拒绝、
手工刷新和 `list_changed` single-flight 保持目录提交规则；断连、maintenance recovery 与远端错误不改写已承诺事实；
当前 run 快照稳定；配置或 policy 撤销与调用承诺保持线性化；OAuth 使用 CAS 且授权替换不越过信任边界；Provider
命名碰撞和 UI 投影保持确定。

构建/JVM 通过不等于真实 Android 验收。前后台、Wi-Fi/蜂窝、Doze、OAuth 浏览器回调、真实通知通道和 MCP UI/Agent
仍需连接设备后执行对应 instrumentation 与现场场景。
