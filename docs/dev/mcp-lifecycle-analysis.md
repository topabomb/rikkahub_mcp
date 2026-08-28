# MCP 生命周期问题、结论与执行计划

本文记录 2026-08-28 对移动端 MCP 管理问题的根因、架构结论和修复计划。当前实现契约以
`docs/references/mcp-architecture.md` 为准。

## 1. 旧实现为什么会出现 0/0 和长时间 loading

旧链路存在结构性问题：

1. 远端 schema 被写回 Settings；编辑或同名导入可用空 `tools` 覆盖真实目录。
2. `Connected` 只证明 transport 建立，却被 UI 和工具装配解释为“目录已经可用”。
3. discovery 通过全局 Settings writer 落盘并触发全配置 fan-out，多个 server 的独立生命周期被间接耦合。
4. 启动时所有 enabled server 都立刻连接；全局四个 permit 覆盖 connect + discovery，而排队前就显示 Connecting。
   20 个 server 中前四个慢连接时，其余看起来持续 loading，所需 server 也可能排在无关 server 后面。
5. 工具装配读取可变 Settings；刷新、重连或导入可在同一 turn 中改变 Provider schema，破坏前缀缓存与调用确定性。
6. 首次 discovery 没有完整 pagination；空目录、partial result 和提交失败缺少不可激活边界。
7. `list_changed`、手工刷新、网络/前台恢复和 transport close 没有统一的 per-server owner 与 operation receipt。
8. 断连后只做约 31 秒的五次重试，并清空 active catalog；服务端短暂离线数分钟便同时造成连接停摆和工具消失。
9. MCP `CallToolResult.isError` 与调用承诺后不确定结果被当作普通成功文本，durable tool terminal 也可能错误记录为 COMPLETED。

这些问题不能靠“空数组再重试一次”或 UI 特判修复；必须拆分 owner，并物理删除 Settings schema cache、全量启动连接、
状态猜测刷新完成和 UI raw runtime 写旁路。

## 2. 最终模型

```text
SettingsStore
  └─ definition + enabled + tool enable/approval policy

McpCatalogStore
  └─ complete non-empty durable last-known-good catalog

McpRuntimeCoordinator
  └─ registry + lifecycle trigger fan-in + shared operation budget + turn capture

McpServerRuntime (one per server)
  └─ client + generation + accepted fingerprint + jobs + runtime health

McpOAuthCoordinator / McpProtocol / McpToolCallExecutor
  └─ OAuth CAS / stateless SDK protocol / admitted invocation execution

TurnMcpCapabilitySnapshot
  └─ immutable run-start Provider schema

McpApplicationService / McpQueryService
  └─ typed UI commands / joined presentation
```

核心判定只有两条：

- 没有明确目录变更证据时保持 LKG。断网、超时、5xx、后台、Doze、重试和通知通道退化不删除 Agent 工具；调用时返回
  可识别的 transport/auth/unknown 错误。
- 有明确变更意图时重新发现。用户手工刷新和 server `notifications/tools/list_changed` 都从第一页开始完整 `tools/list`，
  校验后原子提交；成功目录进入后续 run，当前 run 保持启动快照。

用户禁用/删除、修改 definition、禁用工具或收紧审批属于本地明确撤销，不等待远端；旧快照在调用承诺前拒绝。已经承诺的
调用不因随后变化被改写，成功结果照常交付，timeout/transport failure 标记 outcome unknown 且禁止自动 replay。

“发送前”在上层被定义为不可撤销调用承诺之前，而不是不可观察的首个 HTTP 字节。所有本地 definition/policy typed command
与 invocation commitment 共享一个短门：配置提交先发生则最终 admission 拒绝，调用承诺先发生则归类为 in-flight；其后即使
SDK 尚在发送前挂起，配置变化也只影响后续调用。由于 SDK 不暴露精确网络发送边界，承诺后的 transport/timeout 失败一律保守
返回 unknown 且禁止 replay。远端 I/O 在门外等待，slot mutex 也不跨网络调用持有，所以确定性不会退化为多 server 串行。

## 3. 移动端生命周期与重试决策

### 3.1 激活

- App 启动只恢复 durable LKG，不连接全部已登记 server。
- 新对话只激活 Assistant 选择的 server；已有 LKG 时不等待网络即可稳定生成工具前缀。
- 新增、重新启用、definition 修改、用户刷新/重试是显式激活。
- 前台和 validated default network 恢复只收敛已激活且断连的 runtime，不唤醒全部登记项，也不刷新健康 session 的目录。

### 3.2 恢复

- 每 server 唯一恢复 Job；connect/首次 discovery/健康 session 目录刷新共用四路并发门，工具调用不经过该门；排队前不显示 Connecting。
- operation timeout 从取得 permit 后开始，不把全局排队误算成单 server 超时。
- 前三次 equal-jitter ceiling 为 2/6/15 秒；之后五次 maintenance ceiling 为 30/60/120/240/300 秒。
- offline/background 使用 StateFlow 事件等待，不做固定轮询且不消耗 attempt。
- 恢复预算耗尽只暂停自动尝试，不撤下 LKG。下次调用、网络变化、回前台、手工刷新/重试会重新触发。
- 401 转授权；404/408/425/429/5xx 和 I/O 类错误可恢复；其他 4xx/配置/协议错误不盲目循环。
- 当前 MCP Kotlin SDK 的 `StreamableHttpError` 不提供响应头，尚不能消费 `Retry-After`。未来 SDK 暴露后应进入同一个
  scheduler，不能在 transport 或 UI 另起 timer。

### 3.3 通知

- `list_changed` 在 slot 内 350ms debounce + single-flight；运行期间的多次通知合并为一个 follow-up。
- 首次 discovery 与通知 refresh 不并发，旧 generation/client 的通知不能提交。
- SSE notification lane 退化时明确显示 degraded/stale，但 command transport 和 LKG 不受影响；前台或用户刷新补齐遗漏。
- 移动端不承诺后台永久持有通知流，前台重连和用户刷新是必要的 catch-up 机制。

## 4. UI 结论

- 工具计数来自有效 Catalog 与 policy，不来自连接 status；有 LKG 即显示真实 `enabled/total`。
- 无 LKG 的 Connecting/Discovering 才是 loading；RetryScheduled、WaitingNetwork、maintenance retry 和 Error 使用静态状态。
- 空目录是 `CatalogRejectedEmpty`，不能显示 Ready 或“已连接 0/0”。
- 用户下拉刷新和单 server 重试绑定真实 operation receipt；前台最多等待 20 秒，随后停止 spinner 并提示后台继续，不能取消
  AppScope operation，也不通过共享 status 猜测命令完成。
- Agent 工具披露只在 run-start capture 发生。手工/服务端目录变更成功后更新下一 run；意外连接状态不改写披露。
- 设置页只保留下拉刷新，不保留重复的顶部刷新按钮；Picker 顶部提供设置跳转。设置页、Picker、FilesPicker 和聊天 readiness
  都消费 `McpServerPresentation`；Compose 不直连 Coordinator、Settings 或 Catalog。

## 5. 工具调用错误协议

MCP 调用失败通过 `ToolExecutionFailure` 返回最小稳定 JSON，同时把 durable terminal 标为 FAILED：

| status/reason | 确定语义 | 可选 message |
| --- | --- | --- |
| `unavailable/tool_unavailable` | 本地已明确撤销；远端调用未开始 | 无 |
| `unavailable/server_unavailable` | 当前 session 不可用；内部恢复已触发 | `Try again later.` |
| `unavailable/authorization_required` | 调用前需要用户授权 | `User authorization is required.` |
| `failed/protocol_incompatible` | tools capability 缺失或完整结果无法投影 | 无 |
| `failed/remote_error` | 取得明确远端失败 | 服务端 content/structured content，或裁剪后的 MCP message |
| `unknown/outcome_unknown` | 越过本地承诺但没有取得可确认结果 | `The request may have completed.` |

Agent 输出不包含 server/tool、generation、transport 阶段、HTTP/SDK detail、`retryable`、`request_sent` 或内部恢复动作。
本地调用承诺不等于网络请求已发送；客户端不作该断言。

客户端不自动 replay tool call。成功结果一旦收到便是本次调用的权威结果，不因随后关闭、配置或目录变化而丢弃。

## 6. 执行计划与完成标准

### A. Owner 与持久化

- [x] Settings 改为 policy-only，删除远端 schema 回写与导入覆盖路径。
- [x] 增加 `McpCatalogStore`，完整非空目录才可提交，损坏/空/partial 结果 fail-closed。
- [x] durable LKG 在进程启动时恢复；删除 definition 时清理，disable 时保留供 re-enable 使用。
- [x] 旧 Settings/v3 备份中的完整非空 schema 一次性迁移到 Catalog；v4 备份显式保存目录，恢复与旧 staging 串行且整体替换。
- [x] 删除 UI/VM 对 Manager 和通用 Settings mutation 的旁路。

### B. Slot 生命周期与并发

- [x] 每 server 唯一 `McpServerRuntime`、generation/client/definition lease。
- [x] connect、discovery、refresh、OAuth 和 close 的长 I/O 移出 slot mutex，并设置 operation deadline。
- [x] 启动按需激活；20 个登记项不再全部排队，跨 server I/O 四路有界并行。
- [x] 前台/网络/手工/调用恢复进入同一 slot；offline/background 事件等待，无轮询。
- [x] 快速 + maintenance equal-jitter 重试，耗尽后保留 LKG并等待明确触发。

### C. 目录变化与上下文

- [x] 完整 pagination、名称/cursor/数量校验、digest/revision/head-token rollback。
- [x] 用户手工刷新等待真实 receipt；`list_changed` debounce、single-flight、follow-up 合并。
- [x] 刷新失败/空目录保留 LKG；成功原子更新后续 run。
- [x] Master/Target 固定 run-start snapshot；无 latest-snapshot fallback。
- [x] disable/remove/definition/policy 撤销在调用承诺前重验；已承诺结果不做事后撤销。

### D. 调用与 UI

- [x] `ToolExecutionFailure`、remote `isError`、structured content、承诺后 unknown 与无自动 replay。
- [x] Query/Application ports、统一 presentation、短时 spinner、真实工具计数和 command receipt。
- [x] lifecycle、MCP 当前参考、chat pipeline 和 V2 架构/ledger 同步。

### E. 自动验证

- [x] 20 工具目录、完整分页、首次空目录、digest/revision/rollback。
- [x] durable LKG 启动恢复与 20 个登记 server 无 eager connect。
- [x] 手工刷新等待、`list_changed` 串行/合并、刷新期间 LKG 稳定、当前 run 使用旧快照。
- [x] 数分钟 maintenance 重试语义、失败后 LKG 注入、离线调用 typed error。
- [x] remote `isError`、承诺后 unknown、用户撤销、审批收紧、OAuth 并发/CAS、替换授权取消顺序与完整信任边界。
- [x] 本地配置撤销与 invocation commitment 线性化；配置先行拒绝、调用先行保留 in-flight/unknown 结果。
- [x] 最终完整 Gradle gate 与 `git diff --check`。
- [x] 独立子代理针对最终 diff 审查 owner、旁路、死代码、竞态和需求覆盖；P1 调用边界与 P2 术语均已整改并复审。

### F. 设备验收

- [ ] Android 前后台、Wi-Fi/蜂窝、Doze、OAuth 浏览器回调。
- [ ] 设置页显示完整工具目录，新对话 Agent 可发现并调用；断网后工具仍披露且调用返回可识别错误；恢复后无需重建目录。
- [ ] 手工刷新和真实 `list_changed` 更新后续 turn，当前运行 turn 的 Provider schema 不漂移。

设备当前未连接，因此 F 不能由 JVM/build 结果推断为完成。
