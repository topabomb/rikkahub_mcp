# MCP 架构与生命周期

本文描述当前 MCP 实现的 owner、状态边界和确定性协议。问题复盘与验证记录见
`docs/dev/mcp-lifecycle-analysis.md`。

## 1. 不变量

MCP 的“工具能力”和“当前能否连通”是两类正交事实：

```text
已确认目录（durable LKG） ──决定──> Agent 可见 schema
连接会话（ephemeral）      ──决定──> 本次 tools/call 能否送达
```

因此：

- timeout、断网、5xx、后台、Doze、连接重建和重试耗尽只改变连接健康，不删除同 definition 的已确认目录；
- 用户手工刷新和服务端 `notifications/tools/list_changed` 是明确的目录重验请求；只有完整、合法、非空的
  `tools/list` 结果成功落盘后，才原子替换后续 turn 的 schema；
- 当前运行中的 turn 只使用 run-start 快照。目录刷新、网络变化或 Settings revision 不会改写已经发给 Provider 的工具前缀；
- 用户禁用/删除 server、修改连接 definition、禁用工具或收紧审批，是本地明确撤销。旧快照在远端副作用前 fail-closed；
- 工具已经通过不可撤销调用承诺后，不因随后发生的配置或会话变化丢弃成功结果。承诺后 transport failure/timeout 返回
  `outcome=unknown`，不得自动重放可能有副作用的调用。

## 2. 事实与唯一 owner

| 事实 | 唯一 owner | 存储 | 读取边界 |
| --- | --- | --- | --- |
| Server definition、启用状态、工具 enable/approval policy | `SettingsStore` | Settings DataStore | Coordinator、Query、run capture |
| 完整非空远端工具目录 | `McpCatalogStore` | `mcp_catalog` DataStore | Runtime 启动恢复和提交 |
| 跨 server 注册表、触发汇流和并发预算 | `McpRuntimeCoordinator` | AppScope 内存 | application / turn |
| client、generation、授权 Job、刷新与恢复调度、连接健康 | 每 server 一个 `McpServerRuntime` | AppScope 内存 | `McpRuntimeStateStore` |
| OAuth 网络流程、refresh single-flight 与 Settings CAS | `McpOAuthCoordinator` | AppScope + Settings | `McpServerRuntime` / invocation admission |
| transport/client 创建与完整分页发现 | `McpProtocolClientFactory` / `McpCatalogDiscovery` | 无状态 | `McpServerRuntime` |
| 已承诺工具调用和结果/Artifact 补偿 | `McpToolCallExecutor` | invocation 内存 | Coordinator |
| 单个 run 的 Provider 工具集合 | `TurnMcpCapabilitySnapshot` | run 内存 | `GenerationToolSetFactory` |
| UI command / joined read model | `McpApplicationService` / `McpQueryService` | 无独立状态 | Compose / ViewModel |

`McpCommonOptions.toolPolicies` 只保存工具名、enable 和 needsApproval。远端 description/input schema 不写回
Settings；导入、编辑、OAuth 更新也无权覆盖 Catalog。

编辑或同名导入只在 transport、canonical resource 和静态 headers 都未变化时保留原 OAuth 状态。任一信任边界变化都会
清除旧 access/refresh token 与 client secret，避免旧资源凭据发送到新 endpoint。`definitionDigest` 包含全部静态 headers
（包括手工 `Authorization`）；自动 OAuth token 独立存放在 OAuth 状态中，其正常轮换不会使已确认目录失效。
授权和 token refresh 的持久化 lease 同时校验 transport、canonical resource、规范化静态 headers 与 OAuth revision。
MCP 2025-03 授权规范要求 redirect 为 localhost 或 HTTPS。本实现的 OAuth 回调使用 loopback（RFC 8252）：
`McpOAuthCallbackServer` 每次授权绑定 OS 分配的 ephemeral loopback 端口，redirect path 固定为 `/callback`，
高熵 `state` 绑定本次 coordinator lease。未知 path/state、畸形或超限请求不会消费有效回调；只接受一次合法
code/error，响应 `Cache-Control: no-store`，日志不输出 code/token/verifier。授权服务器若
错误要求固定端口，返回明确互操作错误，不回退自定义 scheme。`McpOAuthCallbackActivity` 与
`measix://mcp-oauth-callback` deep link 已删除；`McpOAuthCallbackService` 只在浏览器授权期间保活 loopback
socket，并以引用计数 lease 支持并发授权，不保存 token、MCP config 或授权阶段。发现元数据、issuer、authorization、
token 与 registration endpoint 必须保持 HTTPS，不接受 fragment 或 userinfo，且资源/issuer 精确匹配。PKCE、resource、canonical server URI 与 trust-boundary/revision CAS
全部保留：callback 到达后、token 持久化前再次校验信任边界与 revision，旧回调不能写入新 definition。
回调或 refresh 响应跨越任一信任边界变化时只能丢弃。重复启动授权必须先取消并等待旧 Job 完成，再推进 revision 后启动新流程。

从曾将完整 schema 写在 Settings 的版本升级时，DataStore migration 在重写 policy-only Settings 的同一事务中生成一次性
catalog staging；`McpCatalogStore` 只接收完整、非空候选，提交后删除 staging，不保留旧 schema 读取旁路。手工备份 v4 将
`mcp_catalogs.json` 作为 manifest 必需根；恢复 v3 时执行同样的一次性提取。备份恢复先让已经取得租约的旧迁移收口，再用
备份目录整体替换 Catalog，避免旧 staging 在恢复后写回孤儿目录。

`McpRuntimeCoordinator.runtimeCapabilities` 是 runtime 的唯一公开状态源；底层由 `McpRuntimeStateStore` 对每个键以一个 immutable
`McpRuntimeCapability(status, catalog)` 原子发布。Settings、Catalog DataStore flow 和 UI 不再形成第二条 runtime
读写路径。status 可变化而 catalog 保持不变，这正是离线仍披露 LKG 工具的协议。

## 3. 进程启动与按需激活

启动时先从 `McpCatalogStore` 恢复与当前 `definitionDigest` 匹配的 durable LKG。恢复目录不需要网络，也不会把全部
已登记 server 排进连接队列。新对话开始时只激活该 Assistant 选择的 server；新建、重新启用或修改 definition 的
server 会主动建立其自身连接。用户全局刷新显式激活全部 enabled server。

每个 server 只有一个 `McpServerRuntime`。它持有 mutex、generation、client、已接受连接请求的 fingerprint 和各 operation Job；
所有远程或持久化 I/O 在锁外执行，完成时以 generation/client/definition lease 重新验收。同 fingerprint 的重复触发合并到
已有 operation，definition 变化则取消旧 operation、推进 generation 并替换。跨 server 的 connect、首次 discovery 和健康
session 的 catalog refresh 共用一个全局 semaphore，最多 4 路并行；工具调用永不经过该门。连接/目录 attempt 的 timeout 从
取得 permit 后开始，排队时间不消耗 server 的远端操作预算。排队中的 server 不发布 `Connecting`，所以 20 个配置不会因为
前四个慢连接而全部显示 loading；已选 server 也不会等待无关 server。

## 4. 发现与目录提交

`fetchCatalogCandidate()` 从空 cursor 开始执行 `tools/list` 并遍历全部 `nextCursor`：

1. server 必须声明 tools capability；
2. 最多 64 页、4096 个工具；
3. 工具名必须非空且 server 内唯一，cursor 不得重复；
4. `ToolSchema` 整体序列化为 `JsonObject`，保留 `$schema`、`$defs`、`$ref` 与扩展字段；
5. 全部页面成功后才形成 candidate；
6. `McpCatalogStore.commitCandidate()` 在单一 commit mutex 下计算 digest、revision 并原子落盘；
7. 空目录不会成为稳定目录，也不会覆盖 LKG；相同 digest 是 no-op；
8. definition 已变化时旧目录不再匹配，不能借 LKG 伪装新 server 已发现。

Catalog Store 对 commit/no-op/rejection 都推进进程内 head token。若 Server Runtime 在持久化后发现 connection lease 已过期，
只允许在 snapshot identity 与 head token 仍匹配时精确回滚；旧 operation 不能覆盖更新的目录事实。

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

手工刷新与 `list_changed` 的共同点是“重新发现并提交”，不是直接清空 cache。刷新期间继续发布旧目录；分页中断、空目录、
超时、取消、校验或 DataStore 提交失败均保留 LKG。用户 command 等待其接受的 connection/catalog Job 及合并的 follow-up，
不能通过观察一个共享 status 猜测完成。前台 receipt 最多等待 20 秒；超时只结束 spinner 并提示剩余 server 在后台继续，
不会取消 AppScope owner 的连接或发现操作。

MCP 2025-06-18 规范将 `notifications/tools/list_changed` 定义为 server 对工具列表变化的显式通知，客户端收到后重新
`tools/list`。官方 SDK 也把 change notification 表述为 cached list 已过期，而不是 session failure：

- [MCP 2025-06-18 schema](https://modelcontextprotocol.io/specification/2025-06-18/schema)
- [MCP tools discovery and list-changed flow](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [MCP TypeScript SDK notifications](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/servers/notifications.md)

## 6. 连接恢复与移动端策略

恢复是每 server 单一调度器，不是页面 timer：

- 1–3 次为快速 equal-jitter，ceiling 为 2/6/15 秒；
- 4–8 次为 maintenance retry，ceiling 为 30/60/120/240/300 秒；每次实际等待在 ceiling 的 1/2 到 ceiling 之间；
- offline 时挂起等待 `NetworkMonitor.isOnline`，background 时挂起等待前台 StateFlow；等待不消耗 attempt，不进行固定轮询；
- 8 次耗尽后进入明确 Error，但 LKG 仍可见。下一次工具调用、validated network 变化、回前台、单 server 重试或手工刷新
  会重置恢复预算并立即尝试；
- 401 进入授权状态；404/408/425/429/5xx 与 I/O 类错误可恢复；其他 4xx/协议/配置错误不做盲目重试；
- 当前 SDK 的 `StreamableHttpError` 不暴露响应头，故暂时无法读取 `Retry-After`；若 SDK 暴露该字段，应由同一调度器
  将其作为服务端最小等待时间，而不是新增第二个 timer。

该策略遵循移动端的事件驱动原则：Android default network 必须同时具备 `INTERNET + VALIDATED`；后台不维持无意义的
常连接风暴；恢复采用有界并发和 jitter，避免多个 server 同时惊群。参考：

- [Android NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [AWS Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
- [RFC 9110 Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after)

## 7. Run 快照与调用结果

Master 和每个 Target 在 run 开始时调用 `prepareTurnCapabilities()`：已有匹配 LKG 时立即捕获；仅缺目录的已选 server 才等待
其 AppScope operation，全部缺失项共享 20 秒上限。随后生成 immutable `TurnMcpCapabilitySnapshot`：

```text
Assistant 选择
∩ 当前 enabled definition
∩ definitionDigest 匹配的非空 LKG
∩ 当前 enable/approval policy
```

`list_changed` 或手工刷新成功后，下一 run 捕获新 revision；当前 run 仍可调用旧 schema，让 server 自己对已删除或修改的
工具返回协议错误。用户本地明确禁用/删除/definition 修改及 approval tightening 会在调用承诺前拒绝旧 binding。

本地 definition/policy typed command 与 `tools/call` 的不可撤销调用承诺共享
`McpRuntimeCoordinator.configurationInvocationCommitMutex`，形成唯一线性化顺序：配置提交先取得门时，最终 admission 读取新配置并拒绝；
调用先取得门时，该 invocation 已进入 in-flight，随后配置变化只影响后续调用和披露。SDK 不暴露“首个 HTTP 字节已写出”的
精确边界，因此上层不把本地承诺伪称为“请求已经发送”；承诺后未取得完整结果的 transport/timeout 失败都保守报告
`status=unknown`，客户端不得自动重放。
该门只覆盖 Settings 提交或 invocation commitment 这一极短临界区，不覆盖 OAuth、connect、discovery 或远端调用等待，也不
持有 slot mutex 执行 I/O，因此不会把不同 server 的网络操作重新串行化。

`McpRuntimeCoordinator.callTool()` 先从对应 `McpServerRuntime` 取得冻结 invocation lease，再由 `McpToolCallExecutor` 执行。
失败使用 `ToolExecutionFailure` 向 GenerationLoop 返回稳定的 Agent 可见结果，并把 durable tool terminal 记录为 FAILED。
Agent 只看见 `status + reason + 必要 message`：

- `unavailable/tool_unavailable`：本地 definition、policy 或工具已经明确撤销；
- `unavailable/server_unavailable`：当前没有可调用 session，内部恢复已经触发；仅补充 `Try again later.`；
- `unavailable/authorization_required`：调用前需要用户授权；
- `failed/protocol_incompatible`：server 未声明 tools capability，或完整结果无法按 MCP 内容契约投影；
- `failed/remote_error`：保留 `CallToolResult.isError` 的 content 与 `structured_content`，或保留经裁剪的明确 MCP error message；
- `unknown/outcome_unknown`：承诺后未取得可确认结果；仅说明请求可能已经完成。

server/tool 身份、generation、transport 阶段、HTTP/SDK 异常、`retryable`、`request_sent` 和恢复动作只属于内部诊断，
不进入模型上下文。工具撤销和协议不兼容的 reason 已足够明确，因此不重复附加 message。

成功结果保留 text/image content 和 `structuredContent`。Image 先取得 Artifact lease，checkpoint 成功后发布；本地失败或未知
结果会精确回滚未发布 Artifact。取消始终向上传播，客户端不自动重放工具调用。

## 8. UI 投影

`McpQueryService` 是唯一 UI read port。UI 的工具计数来自有效 Catalog 与本地 policy，不从连接 status 猜测：

- 有 LKG 时，无论 Ready、Reconnecting、WaitingNetwork、RetryScheduled、NeedsAuthorization 或 Error，都显示真实工具数；
- 只有首次无目录的 Connecting/Discovering/Authorizing 使用 spinner；maintenance recovery 使用静态状态和下次重试信息；
- 空目录显示 rejected，绝不显示“已连接 0/0 tools”；
- 设置页只有列表下拉刷新入口，不保留重复的顶部刷新按钮；spinner 只绑定本次用户 command 的 20 秒 receipt，不绑定全局后台恢复；
- notification stream 单独退化时保留 command transport 与目录，并显示 stale/degraded 原因；前台或手工刷新补齐遗漏；
- managed source/lock、授权、错误详情和工具策略都由同一 presentation 提供，Compose 不直连 Manager/Store。

## 9. 验证边界

修改 MCP 时至少验证：旧 Settings/备份迁移与恢复竞态不会破坏 durable LKG；启动激活有界；分页、空目录拒绝、
手工刷新和 `list_changed` single-flight 保持目录提交规则；断连、maintenance recovery 与远端错误不改写已承诺事实；
当前 run 快照稳定；配置或 policy 撤销与调用承诺保持线性化；OAuth 使用 CAS 且授权替换不越过信任边界；Provider
命名碰撞和 UI 投影保持确定。

构建/JVM 通过不等于真实 Android 验收。前后台、Wi-Fi/蜂窝、Doze、OAuth 浏览器回调、真实通知通道和 MCP UI/Agent
仍需连接设备后执行对应 instrumentation 与现场场景。
