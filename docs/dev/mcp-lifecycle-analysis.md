# MCP 生命周期调研报告

> **文档定位**：McpManager 及被管理的 MCP 服务器的完整生命周期分析。
> **状态**：调研完成，基于代码实证。
> **创建日期**：2026-06-27
> **代码版本**：包名 `net.weero.measix.pilot`

---

## 一、相关文件清单

| 文件 | 职责 |
|------|------|
| `app/.../data/ai/mcp/McpManager.kt` | MCP 连接管理器，生命周期核心 |
| `app/.../data/ai/mcp/McpConfig.kt` | 配置数据结构（McpServerConfig、McpCommonOptions、McpTool） |
| `app/.../data/ai/mcp/McpConnectionKey.kt` | 连接参数键（McpConnectionKey、connectionKey()、hasSameConnectionParameters） |
| `app/.../data/ai/mcp/McpStatus.kt` | 状态枚举定义 |
| `app/.../service/ChatService.kt` | MCP 工具的消费方（构建工具列表、调用工具） |
| `app/.../data/datastore/PreferencesStore.kt` | SettingsStore，配置持久化 |

---

## 二、数据结构

### 2.1 McpManager 成员变量

> 以下为 0.0.10 当前状态。0.0.7~0.0.9 的历史变量见各版本章节。

| 变量 | 类型 | 用途 |
|------|------|------|
| `okHttpClient` | `OkHttpClient` | 底层 HTTP 客户端。20s 连接超时，10min 读超时，120s 写超时 |
| `client` | `Ktor HttpClient` | 基于 OkHttp，安装 ContentNegotiation + SSE 插件。所有 transport 共享此客户端 |
| `clients` | `ConcurrentHashMap<Uuid, Client>` | 活跃连接池。key 是 config.id，value 是 MCP SDK Client |
| `connectedConfigs` | `ConcurrentHashMap<Uuid, McpServerConfig>` | 记录每个连接建立时使用的配置，用于 `hasSameConnectionParameters()` 判断参数变化 |
| `reconnectJobs` | `ConcurrentHashMap<Uuid, Job>` | 重连协程。key 是 config.id |
| `reconnectAttempts` | `ConcurrentHashMap<Uuid, Int>` | 重连计数。key 是 config.id |
| `_status` | `MutableStateFlow<Map<Uuid, McpStatus>>` | 状态跟踪。key 是 config.id，UI 通过 `getStatus()` 展示状态 |

### 2.2 McpStatus 状态定义

> 0.0.10 当前状态（含 0.0.8 OAuth 状态和 0.0.10 Error.detail）。

```kotlin
sealed class McpStatus {
    data object Idle : McpStatus()                                          // 默认值，未连接
    data object Connecting : McpStatus()                                    // 正在连接或同步工具
    data object Connected : McpStatus()                                     // 已连接，可调用工具
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()  // 正在重连
    data class Dormant(val nextRetryInMs: Long) : McpStatus()               // 休眠等待重试（0.0.7 新增）
    data class Error(val message: String, val detail: String? = null) : McpStatus()  // 错误状态（0.0.10 新增 detail）
    data object NeedsAuthorization : McpStatus()                            // 需 OAuth 授权（0.0.8 新增）
    data object Authorizing : McpStatus()                                   // 正在授权（0.0.8 新增）
}
```

`Error.from(throwable, fallbackMessage?)` 工厂方法自动提取 message + stackTraceToString。 `getStatus()` 返回的 Flow 添加了 `distinctUntilChanged()`（0.0.10 修复）。

### 2.3 McpConnectionKey（McpConnectionKey.kt）

> 0.0.10 新增，替代旧版 `McpConfig.ConnectionKey`（已删除）。

```kotlin
internal data class McpConnectionKey(
    val transportType: String,   // "sse" 或 "streamable_http"
    val serverUrl: String,
    val clientName: String,      // commonOptions.name，用作 MCP initialize 的 clientInfo.name
    val headers: List<Pair<String, String>>,  // 含 OAuth Bearer token
)
```

`connectionKey()` 扩展函数从 `McpServerConfig` 提取连接键；`hasSameConnectionParameters(left, right)` 比较两个配置的连接键是否相同。

**设计**：`tools` 和 `enable` **不参与**连接键。修改 URL/transport/headers/OAuth token/clientName 会触发重连；仅工具开关或 Schema 变化时不重连（由 `syncTools` 自然刷新）。

> `clientName` 参与连接键是因为 `createAndConnect` 将 `commonOptions.name` 作为 MCP `initialize` 请求的 `clientInfo.name` 发送给服务端，改名后需重建连接。

### 2.4 McpCommonOptions（McpConfig.kt:17-22）

```kotlin
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList()
)
```

### 2.5 McpTool（McpConfig.kt:25-31）

```kotlin
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
    val needsApproval: Boolean = false
)
```

### 2.6 McpServerConfig（McpConfig.kt:34-87）

密封类，两个子类：

- `SseTransportServer`：SSE 传输
- `StreamableHTTPServer`：Streamable HTTP 传输

两者结构相同：`id: Uuid`、`commonOptions: McpCommonOptions`、`url: String`。`serverUrl` 扩展属性统一获取 URL。

---

## 三、常量定义（McpManager.kt:104-111）

> 0.0.10 当前状态。0.0.7 仅含前 3 个常量；Dormant/离线检查常量为 0.0.7 改进新增。

| 常量 | 值 | 用途 |
|------|-----|------|
| `MAX_RECONNECT_ATTEMPTS` | 5 | 指数退避最大重连次数 |
| `BASE_RECONNECT_DELAY_MS` | 1000L | 基础重连延迟（1 秒） |
| `MAX_RECONNECT_DELAY_MS` | 30000L | 最大重连延迟（30 秒） |
| `DORMANT_RETRY_INTERVAL_MS` | 60_000L | Dormant 模式重试间隔（60 秒） |
| `DORMANT_MAX_RETRIES` | 30 | Dormant 模式最大重试次数（30 分钟兜底） |
| `OFFLINE_CHECK_INTERVAL_MS` | 10_000L | 离线时网络恢复检查间隔（10 秒） |

---

## 四、McpManager 初始化（McpManager.kt:171-219）

> 0.0.10 当前状态。0.0.7 使用 `checkDifferent` 三步走（过滤→比较→并行 add/remove）；0.0.10 改为三路分类 + `hasSameConnectionParameters` 连接参数检测，详见第十六章。

```
McpManager 被 Koin 创建
    │
    ▼
init 块启动三条恢复链
    │
    ├── 链 1: settings 变更 → 自动 add/remove/重连
    │     appScope.launch {
    │         settingsStore.settingsFlow
    │             .map { it.mcpServers }
    │             .distinctUntilChanged()
    │             .collect { configs -> 三路分类处理 }
    │     }
    │
    ├── 链 2: 前台恢复 → syncAll
    │     ProcessLifecycleOwner.onStart → syncAll()
    │
    └── 链 3: 网络恢复 → syncAll
          ConnectivityManager.NetworkCallback.onAvailable → syncAll()
```

### 4.1 配置变更处理逻辑（链 1）

```
收到新的 mcpServerConfigs
    │
    ▼
第一步：过滤
    enabled = configs.filter {
        it.commonOptions.enable && it.commonOptions.name.isNotBlank()
    }
    enabledIds = enabled.map { it.id }.toSet()
    │
    ▼
第二步：三路分类（跳过授权流程中的 server）
    │
    ├── 新增 server：id 不在 clients 中 且 不在授权流程中
    │     → appScope.launch { addClient(it) }
    │
    ├── 已存在 server：id 在 clients 中 且 不在授权流程中
    │     → hasSameConnectionParameters(connectedConfigs[id], config)
    │       false（URL/transport/headers/token 变化）→ addClient 重连
    │       true（仅工具开关/Schema 变化）→ 跳过，由 syncAll 的 syncTools 刷新
    │
    └── 删除 server：id 在 clients 中但已不在 enabled 列表
          → appScope.launch { removeClient(id) }
```

**确定**：

- `hasSameConnectionParameters` 比较连接键（transportType/serverUrl/clientName/headers），不包含 tools/enable
- 仅工具开关变化时**不触发重连**，由下次 `syncAll` 的 `syncTools` 自然刷新
- `addClient` 和 `removeClient` 是**并行**执行的（各自启动独立协程）
- 授权流程中的 server（NeedsAuthorization/Authorizing）被跳过，避免与授权竞争

---

## 五、单个 MCP 服务器的完整生命周期

### 5.1 添加阶段：addClient + createAndConnect（McpManager.kt:357-379 / 556-598）

> 0.0.10 当前状态。0.0.7 `addClient` 内联全部逻辑；0.0.10 拆分为 `addClient`（配置重读 + 清理）+ `createAndConnect`（transport + client + 连接），并新增 `connectedConfigs` 追踪。

```
addClient(configInput)
    │
    ▼
withContext(Dispatchers.IO) + getServerLock(configInput.id).withLock {
    │
    ├── 第一步：重读最新配置（0.0.10 新增）
    │   desiredConfig = settingsStore.settingsFlow.value.mcpServers
    │       .find { it.id == configInput.id }
    │   if (desiredConfig == null || !enable || name.isBlank()) {
    │       cancelAllJobs / closeClient / cleanup → return  // 配置已删除/禁用
    │   }
    │
    ▼
    ├── 第二步：刷新 OAuth token + 清理旧状态
    │   config = ensureFreshToken(desiredConfig)
    │   cancelAllJobs(config.id)
    │   closeClient(config.id)
    │
    ▼
    └── 第三步：委托 createAndConnect(config)
}

createAndConnect(config)
    │
    ▼
    ├── 创建 transport 和 client
    │   transport = getTransport(config)
    │   client = Client(clientInfo = Implementation(name, version = "1.0"))
    │   setupNotificationHandlers(client, config)  // tools/list_changed 监听
    │
    ├── 注册 transport 回调
    │   transport.onClose { if (Connected) scheduleReconnect(id) }
    │   transport.onError { if (isSseStreamGiveUpError) return; if (Connected) scheduleReconnect(id) }
    │
    ├── 放入连接池 + 记录配置快照
    │   clients[config.id] = client
    │   connectedConfigs[config.id] = config   // 0.0.10 新增
    │
    ├── 连接流程
    │   setStatus(Connecting)
    │   client.connect(transport)              // MCP 协议握手
    │   syncTools(config.id)                   // 同步工具列表（见 5.2）
    │   setStatus(Connected)
    │   reconnectAttempts[config.id] = 0
    │
    └── 失败处理
        closeClient(config.id)                 // 清理坏连接
        if (CancellationException) throw it
        if (needsAuthorization) → NeedsAuthorization
        else → Error.from(it)                  // 0.0.10 新增 detail
```

**确定**：

- `addClient` 从 `settingsStore` 重读配置，避免协程排队期间 config 过期（0.0.10）
- `addClient` 和 `createAndConnect` 在同一个 `getServerLock` 保护下，序列化所有操作
- `connectedConfigs` 在 `createAndConnect` 中写入，在 `closeClient` 中清除
- transport 回调只在 `Connected` 状态下触发重连，避免正常关闭时重连
- `syncTools` 在 `connect` 之后、`Connected` 之前调用
- 401 错误设置 `NeedsAuthorization` 状态而非 `Error`

### 5.2 同步阶段：syncTools(configId)（McpManager.kt:622-641）

> 0.0.10 当前状态。0.0.7 `sync(config)` 按对象查找 client 并更新 clients Map key；0.0.10 简化为 `syncTools(configId)` 按 Uuid 查找，不再更新 Map key（clients 以 id 为 key）。

```
syncTools(configId)
    │
    ▼
第一步：查找 client
    client = clients[configId]
    if (client == null) return 0  // 找不到则直接返回
    │
    ▼
第二步：获取服务器工具列表
    serverTools = client.listTools().tools  // MCP 协议调用
    │
    ▼
第三步：读取当前配置 + 合并工具
    existingConfig = settingsStore.settingsFlow.value.mcpServers
        .find { it.id == configId } ?: return 0
    │
    ├── mergedTools = mergeTools(serverTools, existingConfig.commonOptions.tools)
    │   // mergeTools 逻辑（McpConfig.kt:mergeTools 函数）：
    │   // - 服务器有，本地无 → 新增（enable=true）
    │   // - 服务器有，本地有 → 更新 description/inputSchema，保留 enable/needsApproval
    │   // - 服务器无，本地有 → 移除
    │
    ▼
第四步：更新 settingsStore（持久化）
    settingsStore.update { old ->
        old.copy(mcpServers = old.mcpServers.map {
            if (it.id == configId) existingConfig.clone(
                commonOptions = existingConfig.commonOptions.copy(tools = merged)
            ) else it
        })
    }
    │
    ▼
返回 merged.size
```

**确定**：

- `syncTools` 会**修改 settingsStore**，这会触发 `settingsFlow` 的监听器（链 1）
- clients Map 以 `id: Uuid` 为 key，工具合并不改变 key（0.0.10 简化）
- `mergeTools` 是单向同步：以服务器为准，本地只保留 enable/needsApproval 的用户偏好
- `tools/list_changed` 通知也会触发 `syncTools`（见 `setupNotificationHandlers`）

### 5.3 运行阶段

状态为 `Connected` 时：

- 可以通过 `callTool()` 调用工具
- 可以通过 `getAllAvailableTools()` 获取工具列表
- 监听 `transport.onClose` 回调
- 监听 `transport.onError` 回调

### 5.4 断联重连阶段

#### 5.4.1 触发条件

```
transport.onClose 或 transport.onError 触发
    │
    ▼
val currentStatus = syncingStatus.value[config.id]
    │
    ├── currentStatus != Connected → 不处理（忽略）
    └── currentStatus == Connected → scheduleReconnect(config)
```

**确定**：只有在 `Connected` 状态下才会触发重连。

#### 5.4.2 scheduleReconnect(configId)（McpManager.kt:456-530）

> 0.0.10 当前状态。0.0.7 仅 5 次指数退避后 Error；改进后 5 次退避 → Dormant（60s×30 次）→ Error，并新增离线检查。Dormant/离线检查详见第十三章。

```
scheduleReconnect(config)
    │
    ▼
第一步：递增重连计数
    currentAttempt = (reconnectAttempts[configId] ?: 0) + 1
    │
    ▼
第二步：检查是否超过最大次数
    if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {  // MAX_RECONNECT_ATTEMPTS = 5
        setStatus(config, Error("连接断开，已达最大重连次数"))
        return
    }
    │
    ▼
第三步：更新计数
    reconnectAttempts[configId] = currentAttempt
    │
    ▼
第四步：取消之前的重连任务
    reconnectJobs[configId]?.cancel()
    │
    ▼
第五步：计算延迟（指数退避）
    delayMs = calculateBackoffDelay(currentAttempt)
    // attempt=1 → 1s, attempt=2 → 2s, attempt=3 → 4s, attempt=4 → 8s, attempt=5 → 16s
    │
    ▼
第六步：启动重连协程
    reconnectJobs[configId] = appScope.launch {
        │
        ├── setStatus(config, Reconnecting(currentAttempt, MAX_RECONNECT_ATTEMPTS))
        │
        ├── delay(delayMs)
        │
        ├── 检查配置是否仍然启用
        │   currentConfig = settingsStore.settingsFlow.value.mcpServers
        │       .find { it.id == configId && it.commonOptions.enable }
        │
        │   if (currentConfig == null) {
        │       return@launch  // 配置已禁用或移除，静默退出
        │   }
        │
        ├── 尝试重连
        │   try {
        │       reconnectClient(currentConfig)
        │   } catch (e: CancellationException) {
        │       throw e  // 协程取消，向上传播
        │   } catch (e: Exception) {
        │       scheduleReconnect(config)  // 重连失败，继续调度下一次
        │   }
        │
        └── 注意：reconnectClient 成功时不调用 scheduleReconnect
    }
```

**确定**：

- 重连使用**指数退避**：1s → 2s → 4s → 8s → 16s
- 重连前会检查配置是否仍然启用
- 重连失败会递归调用 `scheduleReconnect` 继续尝试
- 重连成功后 `reconnectAttempts` 在 `reconnectClient` 中重置为 0
- 超过 5 次后状态变为 `Error`，**不再自动重连**

#### 5.4.3 calculateBackoffDelay(attempt)（McpManager.kt:538-542）

```kotlin
private fun calculateBackoffDelay(attempt: Int): Long {
    val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
    return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
}
```

计算结果：

| attempt | 计算过程 | 结果 |
|---------|----------|------|
| 1 | 1000 * 2^0 | 1s |
| 2 | 1000 * 2^1 | 2s |
| 3 | 1000 * 2^2 | 4s |
| 4 | 1000 * 2^3 | 8s |
| 5 | 1000 * 2^4 | 16s |

#### 5.4.4 reconnectClient(configInput)（McpManager.kt:604-620）

> 0.0.10 当前状态。0.0.7 内联全部逻辑；0.0.10 委托 `createAndConnect`，复用连接逻辑。

```
reconnectClient(configInput)
    │
    ▼
withContext(Dispatchers.IO) + getServerLock(configInput.id).withLock {
    │
    ├── 重读最新配置 + ensureFreshToken
    │   config = settingsStore.settingsFlow.value.mcpServers
    │       .find { it.id == configInput.id } ?: return
    │
    ├── closeClient(config.id)    // 关闭旧客户端（不取消自身 reconnectJob）
    │
    └── createAndConnect(config)  // 委托创建+连接（同 addClient 路径）
}
```

**确定**：

- `reconnectClient` 运行在 `reconnectJob` 中，**不调用 `cancelAllJobs`**（取消自身会导致 `connect` 抛 `CancellationException`）
- 仅调用 `closeClient`（关闭 client + 清理 `clients`/`connectedConfigs`），不取消 Job
- 委托 `createAndConnect` 复用与 `addClient` 相同的连接逻辑
- 重连成功后 `reconnectAttempts` 在 `createAndConnect` 中重置为 0

### 5.5 移除阶段：removeClient(serverId)（McpManager.kt:382-392）

> 0.0.10 当前状态。0.0.7 按 config 对象查找；0.0.10 简化为按 Uuid 查找 + `getServerLock` 保护 + `cancelAllJobs`。

```
removeClient(serverId)
    │
    ▼
withContext(Dispatchers.IO) + getServerLock(serverId).withLock {
    │
    ├── cancelAllJobs(serverId)     // 取消重连/Dormant/授权任务
    ├── closeClient(serverId)       // 关闭 client + 清理 clients/connectedConfigs
    ├── reconnectAttempts.remove(serverId)
    ├── _status.update { it - serverId }  // 从状态表移除
    └── logMcp(name, "Disconnected (removed)")
}
```

**确定**：

- `removeClient` 在 `getServerLock` 保护下序列化执行
- `cancelAllJobs` 取消重连/Dormant/授权三类 Job
- `closeClient` 清理 `clients` 和 `connectedConfigs`（0.0.10）
- 移除后状态从 `_status` 中消失，`getStatus` 返回 `Idle`

---

## 六、工具调用链路

### 6.1 getAllAvailableTools(assistant)（McpManager.kt:230-239）

> 0.0.10 当前状态。签名 `getAllAvailableTools(assistant: Assistant)` — 外部传入 assistant，比上游 `getAllAvailableTools()` 内部 getCurrentAssistant 更显式。

```kotlin
fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
    val settings = settingsStore.settingsFlow.value
    return settings.mcpServers
        .filter {
            it.commonOptions.enable && it.id in assistant.mcpServers
        }
        .flatMap { server ->
            server.commonOptions.tools
                .filter { tool -> tool.enable }
                .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
        }
}
```

**确定**：

- 这是一个**纯读取**操作，不修改任何状态
- 过滤条件：配置启用 + 在当前助手中 + 工具启用
- 返回的是 `settingsStore` 中的工具列表，**不是** `clients` 中的
- 使用 `getCurrentAssistant()` 获取当前助手（PreferencesStore.kt:587-589）

### 6.2 callTool(serverId, toolName, args)（McpManager.kt:241-310）

> 0.0.10 当前状态。0.0.7 简单 try-catch + Error；改进后四级异常分级 + `ensureFreshToken` + `hasSameConnectionParameters` 重连检测。

```
callTool(serverId, toolName, args)
    │
    ▼
getServerLock(serverId).withLock {
    │
    ├── 查找 client + config
    │   client = clients[serverId] ?: return "not connected"
    │   config = settingsStore.settingsFlow.value.mcpServers.find{ it.id == serverId }
    │
    ├── ensureFreshToken + 连接参数变化检测（0.0.10）
    │   freshConfig = ensureFreshToken(config)
    │   if (!hasSameConnectionParameters(connectedConfigs[serverId], freshConfig)) {
    │       cancelAllJobs / closeClient / createAndConnect  // 重建连接
    │   }
    │
    ├── transport 为 null → scheduleReconnect + return
    │
    ├── 调用工具（120s 超时）
    │   runCatching { client.callTool(...) }
    │
    └── 四级异常分级
        1. TimeoutCancellationException → 降级文本（不中断、不重连）
        2. CancellationException → 向上传播（不吞）
        3. looksUnauthorized → NeedsAuthorization 状态 + 文本
        4. isConnectionError → scheduleReconnect + 错误文本
        5. 其他 → 错误文本（不重连）
}
```

**确定**：

- `callTool` 在 `getServerLock` 保护下执行，与其他操作序列化
- 调用前检查 OAuth token 新鲜度 + 连接参数变化（0.0.10）
- 超时降级为文本返回给 AI，不中断对话
- 连接错误触发重连，其他错误仅返回错误文本
- `transport == null` 时触发 `scheduleReconnect` 而非直接连接

### 6.3 ChatService 中的使用（ChatService.kt:519+）

```
ChatService.handleMessageComplete()
    │
    ▼
构建 tools 列表：
    tools = buildList {
        // ... 其他工具（搜索、本地、工作空间、Skill）...
        │
        ▼
        mcpManager.getAllAvailableTools(assistant)  // 获取可用工具
            │
            ▼
            验证服务器名称
            invalidNames = allTools.map { it.second }.distinct()
                .filter { name ->
                    name.isEmpty() || !name.all {
                        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                    }
                }
            if (invalidNames.isNotEmpty()) {
                addError(...)   // 报错
                return          // 中断整个生成流程
            }
            │
            ▼
            注册工具
            .forEach { (serverId, serverName, tool) ->
                add(Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) }
                ))
            }
    }
```

**确定**：

- 工具名格式：`mcp__${serverName}__${tool.name}`
- 服务器名验证：只允许字母、数字、`-`、`_`
- 如果服务器名无效，整个生成流程中断
- `execute` 回调捕获的是 `serverId` 和 `tool.name`，不是服务器名

---

## 七、sync 触发 settingsFlow 的连锁反应

`sync(config)` 会更新 `settingsStore`，这会触发 `settingsFlow` 的监听器。以下是完整的连锁反应：

```
addClient(config)
  → createAndConnect 成功
  → syncTools(configId)
    → client.listTools() 获取工具
    → settingsStore.update(...)  // 更新 tools
    → settingsFlow 发出新值
    → init 块的 collect 被触发
    → hasSameConnectionParameters(connectedConfigs[id], config) → true（只是 tools 变了）
    → 不触发 addClient/removeClient
  → setStatus(config.id, Connected)
```

**确定**：`syncTools` 更新 `settingsStore` 会触发 `settingsFlow`，但由于连接键没变（tools 不参与比较），不会产生额外的 `addClient`/`removeClient`。

---

## 八、典型场景的状态流转

### 场景 A：正常启动

```
应用启动
  → McpManager 创建
  → init 块启动，监听 settingsFlow
  → settingsFlow 发出当前配置
  → 过滤出启用的配置
  → toAdd = 所有启用配置（clients 为空）
  → 并行调用 addClient(config)
  → 每个 addClient：[不存在] → Connecting → sync → Connected
```

### 场景 B：服务端短暂断联后恢复（重连成功）

```
状态：Connected
  → 服务端断开连接
  → transport.onClose 触发
  → currentStatus == Connected → scheduleReconnect(config)
  → currentAttempt = 1, delay = 1s
  → status = Reconnecting(1, 5)
  → 等待 1s
  → 检查配置仍启用 → true
  → reconnectClient(config)
    → 关闭旧 client
    → 创建新 transport + client
    → connect → sync
    → status = Connected
    → reconnectAttempts = 0
```

### 场景 C：服务端长时间不可用（达到最大重连次数）

```
状态：Connected
  → 服务端断开连接
  → transport.onClose 触发
  → scheduleReconnect(config)
  → 第 1 次：Reconnecting(1, 5), delay=1s → 失败 → scheduleReconnect
  → 第 2 次：Reconnecting(2, 5), delay=2s → 失败 → scheduleReconnect
  → 第 3 次：Reconnecting(3, 5), delay=4s → 失败 → scheduleReconnect
  → 第 4 次：Reconnecting(4, 5), delay=8s → 失败 → scheduleReconnect
  → 第 5 次：Reconnecting(5, 5), delay=16s → 失败 → scheduleReconnect
  → 第 6 次：currentAttempt(6) > MAX_RECONNECT_ATTEMPTS(5)
  → status = Error("连接断开，已达最大重连次数")
  → 停止重连
```

**确定**：达到最大重连次数后，**不会自动恢复**。此时 `clients` 中已无该配置（`reconnectClient` 失败时移除）。

### 场景 D：用户修改 MCP 服务器 URL

> 0.0.10 后使用 `hasSameConnectionParameters` 检测。

```
用户修改 URL
  → settingsStore 更新
  → settingsFlow 发出新配置
  → init block: 已存在 server 的 connectedConfigs[id] 与新配置比较
  → hasSameConnectionParameters → false（URL 变了）
  → appScope.launch { addClient(config) }  // 完全重建
  → addClient 内部: closeClient → createAndConnect
```

### 场景 E：用户禁用 MCP 服务器（enable = false）

```
用户禁用
  → settingsStore 更新
  → settingsFlow 发出新配置
  → 过滤：enable=false 的被排除
  → 不在 enabled 列表 → removeClient
  → 关闭连接，清理状态，从 _status 移除
```

### 场景 F：用户修改工具开关（tool.enable = false）

> 0.0.10 后使用 `hasSameConnectionParameters` 检测。

```
用户禁用某个工具
  → settingsStore 更新
  → settingsFlow 发出新配置
  → init block: hasSameConnectionParameters → true（tools 不参与比较）
  → 不触发重连
  → 下次 syncAll 的 syncTools 会自然刷新工具列表
```

### 场景 G：重连期间用户修改配置

```
状态：Reconnecting(2, 5)
  → 用户修改 URL
  → settingsStore 更新
  → settingsFlow 发出新配置
  → init block: 已存在 server，hasSameConnectionParameters → false（URL 变化）
  → addClient(新配置)：cancelAllJobs → closeClient → createAndConnect
  → cancelAllJobs 取消正在进行的重连协程
  → createAndConnect 使用新 URL 建立连接
```

**确定**：`addClient` 内部的 `cancelAllJobs` 会取消正在进行的重连协程。

### 场景 H：重连期间配置被禁用

```
状态：Reconnecting(2, 5)
  → 用户禁用配置
  → settingsStore 更新
  → settingsFlow 发出新配置
  → 过滤：enable=false 的被排除
  → id 在 clients 中但不在 enabled 列表
  → removeClient(id)：cancelAllJobs → closeClient → 清理状态
```

同时，如果重连协程在 `delay` 之后才检查配置：

```
重连协程 delay 结束
  → 检查配置：settingsStore.settingsFlow.value.mcpServers.find { it.id == configId && it.commonOptions.enable }
  → 找不到（已被禁用）
  → return@launch（静默退出）
```

---

## 九、状态转换汇总

> 0.0.10 当前状态。0.0.7 简单状态机；改进后新增 Dormant/NeedsAuthorization/Authorizing 状态 + 四级异常分级。

| 当前状态 | 触发条件 | 目标状态 | 代码位置 |
|----------|----------|----------|----------|
| （不存在） | createAndConnect 成功 | Connected | McpManager.kt:580 |
| （不存在） | createAndConnect 失败 | Error / NeedsAuthorization | McpManager.kt:584-596 |
| Connected | transport.onClose/onError | Reconnecting | McpManager.kt:566-570 |
| Reconnecting | reconnectClient 成功 | Connected | McpManager.kt:580 |
| Reconnecting | 超过 MAX_RECONNECT_ATTEMPTS | Dormant | McpManager.kt:465+ |
| Dormant | Dormant 重试成功 | Connected | McpManager.kt:580 |
| Dormant | Dormant 重试耗尽 | Error | McpManager.kt:465+ |
| Reconnecting/Dormant | 配置被禁用/移除 | （退出，不改状态） | McpManager.kt:383+ |
| 任意 | removeClient | （从 _status 移除） | McpManager.kt:389 |
| 任意 | callTool 超时 | （不改状态） | McpManager.kt:283 |
| 任意 | callTool 连接错误 | Reconnecting | McpManager.kt:300 |
| 任意 | callTool 授权错误 | NeedsAuthorization | McpManager.kt:293 |
| NeedsAuthorization | 用户完成 OAuth | Connected | McpOAuthCoordinator |

---

## 十、关键设计特征总结

1. **全局单例**：McpManager 是应用级单例，所有 MCP 连接全局管理
2. **配置驱动**：连接的创建和销毁由 `settingsStore.settingsFlow` 驱动
3. **连接键检测**：`hasSameConnectionParameters` 精确区分连接参数变化（URL/transport/headers/token）与工具开关变化，后者不触发重连
4. **分层重连**：5 次指数退避 → Dormant（60s×30 次）→ Error；网络离线时跳过重连，每 10s 检查恢复
5. **三条恢复链**：settings 变更 + 前台恢复 + 网络恢复，覆盖移动端全部场景
6. **per-server Mutex**：每个 server 有独立锁，序列化所有操作
7. **四级异常分级**：超时降级 / 协程取消传播 / 授权错误引导 / 连接错误重连
8. **并行操作**：addClient 和 removeClient 通过独立协程并行执行

---

## 十一、存在的问题（0.0.7 基线）

> 以下为 0.0.7 基线分析中发现的问题。全部已在 0.0.7~0.0.10 中修复，详见第十三章改进对照表。

### 问题 1：达到最大重连次数后不会自动恢复

**场景**：服务端长时间不可用（如重启），5 次重连全部失败。

**现状**（McpManager.kt:329-334）：
```kotlin
if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {
    appScope.launch {
        setStatus(config, McpStatus.Error("连接断开，已达最大重连次数"))
    }
    return
}
```

**问题**：
- 状态变为 `Error` 后停止重连
- 此时 `clients` 中已无该配置（`reconnectClient` 失败时移除）
- 用户看到的错误状态不会自动恢复
- 用户必须手动操作（如进入设置页重新同步）才能恢复

**影响**：服务端重启后，如果重启时间超过约 31 秒（1+2+4+8+16），MCP 连接将永久断开，直到用户手动干预。

### 问题 2：Reconnecting 状态下配置被禁用时状态不清理

**场景**：重连协程正在 `delay` 等待期间，用户禁用了该配置。

**现状**（McpManager.kt:351-358）：
```kotlin
val currentConfig = settingsStore.settingsFlow.value.mcpServers
    .find { it.id == configId && it.commonOptions.enable }

if (currentConfig == null) {
    Log.i(TAG, "Config disabled or removed, cancelling reconnect for ${config.commonOptions.name}")
    return@launch  // 静默退出
}
```

**问题**：
- 重连协程静默退出，但**不清理 `syncingStatus`**
- 同时 `removeClient` 也会被触发（因为 settingsFlow 检测到配置被移除）
- 两者存在竞态：
  - 如果 `removeClient` 先执行：状态被清理，重连协程找到 `currentConfig == null` 后退出 → 正常
  - 如果重连协程先执行到 `return@launch`：状态仍为 `Reconnecting`，之后 `removeClient` 再清理 → 正常
  - 如果重连协程在 `delay` 期间被 `cancelReconnect` 取消：抛出 `CancellationException`，被 catch 后 rethrow → 正常

**影响**：实际竞态窗口很小，因为 `removeClient` 会调用 `cancelReconnect`。但如果时序特殊，UI 可能短暂显示 `Reconnecting` 状态。

### 问题 3：sync 触发 settingsFlow 的连锁反应

**场景**：`addClient` 或 `reconnectClient` 成功后调用 `sync(config)`。

**现状**（McpManager.kt:269-287）：
```kotlin
settingsStore.update { old ->
    old.copy(mcpServers = old.mcpServers.map { serverConfig ->
        // ... 更新 tools ...
    })
}
```

**问题**：
- `sync` 更新 `settingsStore` 会触发 `settingsFlow` 的 `collect`
- 虽然 `ConnectionKey` 不变不会产生额外的 `addClient`/`removeClient`
- 但每次 `sync` 都会触发一次完整的 `checkDifferent` 计算
- 如果有 N 个服务器同时连接成功，会触发 N 次 `settingsFlow` collect

**影响**：性能开销较小，但逻辑上存在不必要的重复计算。

### 问题 4：callTool 失败不触发重连

**场景**：`Connected` 状态下调用工具失败（如服务端突然断开）。

**现状**（McpManager.kt:158-159）：
```kotlin
.onFailure { e ->
    setStatus(config, McpStatus.Error(e.message ?: e.javaClass.name))
}
```

**问题**：
- `callTool` 失败只设置 `Error` 状态，**不触发重连**
- 与 `transport.onClose`/`onError` 的行为不一致
- 如果 transport 的 `onClose`/`onError` 回调没有及时触发，状态会停留在 `Error`

**影响**：工具调用失败后，如果 transport 回调延迟触发，用户可能看到 `Error` 状态但不会自动恢复。不过 transport 回调通常会很快触发，所以实际影响取决于 MCP SDK 的实现。

### 问题 5：addClient 和 removeClient 并行执行的竞态

**场景**：`settingsFlow` 同时产生 `toAdd` 和 `toRemove`。

**现状**（McpManager.kt:99-107）：
```kotlin
toAdd.forEach { cfg ->
    appScope.launch { addClient(cfg) }
}
toRemove.forEach { cfg ->
    appScope.launch { removeClient(cfg) }
}
```

**问题**：
- `addClient` 和 `removeClient` 通过独立协程并行执行
- `addClient` 内部会先调用 `removeClient`（McpManager.kt:210）
- 如果 `toRemove` 中的配置与 `toAdd` 中的配置有相同的 `id`，可能出现：
  - 协程 A：`addClient(config)` → 调用 `removeClient(config)`
  - 协程 B：`removeClient(config)`
  - 两者同时操作同一个 `id` 的 `clients` Map

**影响**：由于 `removeClient` 是幂等的（找不到就跳过），实际不会出错。但可能导致 `addClient` 的 `removeClient` 调用与外层的 `removeClient` 并发执行，产生短暂的状态不一致。

### 问题 6：clients Map 的 key 是 config 对象

**场景**：`sync` 更新 tools 后，config 对象变化。

**现状**（McpManager.kt:280-282）：
```kotlin
entry.key.let { clients.remove(it) }
clients[newConfig] = client
```

**问题**：
- `clients` 的 key 是 `McpServerConfig` 对象
- `sync` 后会移除旧 key，添加新 key（因为 tools 变了）
- 如果在 `sync` 执行期间有其他代码通过旧 config 对象查找 client，会找不到

**影响**：`callTool` 通过 `serverId`（Uuid）查找，不依赖 config 对象引用，所以不受影响。但 `getClient(config)` 方法通过 config 对象查找（McpManager.kt:116），可能在 sync 期间返回 null。

### 问题 7：重连期间工具调用失败

**场景**：服务端断联，正在重连期间，用户尝试调用 MCP 工具。

**现状**：
- 重连期间，`reconnectClient` 会先关闭旧客户端（McpManager.kt:387-389）
- 然后创建新客户端并放入 `clients`（McpManager.kt:417）
- 在旧客户端被移除、新客户端还未放入的窗口期，`callTool` 会找不到 client

**影响**：工具调用会返回错误消息 "Failed to execute tool, because no such mcp client for the tool"。这是瞬态错误，下次调用时如果重连已完成则正常。

### 问题 8：无状态恢复机制

**场景**：应用重启后，之前处于 `Error` 或 `Reconnecting` 状态的配置。

**现状**：
- `syncingStatus` 是内存中的 `MutableStateFlow`，应用重启后丢失
- `reconnectAttempts` 和 `reconnectJobs` 也是内存中的
- 应用重启后，`settingsFlow` 会重新触发配置变更处理
- 所有启用的配置都会重新走 `addClient` 流程

**影响**：应用重启后状态自动重置，这不是问题。但如果服务端仍然不可用，会重新开始 5 次重连循环。

---

## 十二、问题汇总表

| 问题 | 严重程度 | 影响 | 触发条件 |
|------|----------|------|----------|
| 达到最大重连次数后不会自动恢复 | **高** | MCP 永久断开，需手动干预 | 服务端不可用超过 31 秒 |
| Reconnecting 状态下配置被禁用时状态不清理 | 低 | UI 可能短暂显示错误状态 | 重连期间禁用配置 |
| sync 触发 settingsFlow 连锁反应 | 低 | 不必要的重复计算 | 每次 sync |
| callTool 失败不触发重连 | 中 | 可能延迟恢复 | 工具调用时服务端断开 |
| addClient/removeClient 并行竞态 | 低 | 短暂状态不一致 | 配置同时增减 |
| clients Map key 是 config 对象 | 低 | sync 期间 getClient 可能返回 null | sync 执行期间 |
| 重连期间工具调用失败 | 中 | 瞬态错误 | 重连期间调用工具 |
| 无状态恢复机制 | 低 | 应用重启后状态重置 | 应用重启 |

---

## 十三、改进落地（0.0.7）

> 以下问题已在 0.0.7 版本中修复，详见 `changelog.md`。

### 改进对照表

| 原问题 | 严重程度 | 改进措施 | 状态 |
|--------|----------|----------|------|
| 达到最大重连次数后不会自动恢复 | **高** | 新增 `Dormant` 状态：快速 5 次重连失败后进入休眠，60s 周期重试最多 30 次（共 30 分钟兜底窗口） | ✅ 已修复 |
| callTool 失败不触发重连 | 中 | callTool 区分连接错误（触发 `scheduleReconnect`）与工具错误（仅标记 Error） | ✅ 已修复 |
| 重连期间工具调用失败 | 中 | per-server `Mutex` 序列化 + `cleanupServer()` 统一清理后重建，消除窗口期 | ✅ 已修复 |
| Reconnecting 状态下配置被禁用时状态不清理 | 低 | `scheduleReconnect`/`enterDormant` 每次重试前检查 `enable`，禁用时 `cleanupServer` + 移除 status | ✅ 已修复 |
| addClient/removeClient 并行竞态 | 低 | per-server `Mutex` 序列化所有操作（addClient/removeClient/callTool/syncTools/reconnectClient） | ✅ 已修复 |
| clients Map key 是 config 对象 | 低 | key 改为 `Uuid`，sync 更新 tools 不再改变 key | ✅ 已修复 |
| sync 触发 settingsFlow 连锁反应 | 低 | 保留（可接受开销）：connectionKey 不含 tools，settingsFlow 触发后 init block 无实际操作 | ✅ 已评估可接受 |
| 无状态恢复机制 | 低 | 保留（设计决策）：内存状态重启后重置是正确行为 | ✅ 设计合理 |
| **[新] syncAll 死锁** | **致命** | `syncAll` 不在持有 Mutex 时调用 `addClient`（Mutex 非重入） | ✅ 已修复 |
| **[新] 无工具变更通知** | 中 | 接入 `notifications/tools/list_changed`，服务器工具变更自动 sync | ✅ 已新增 |
| **[新] stale state（停止→开启服务器）** | **高** | `cleanupServer()` 彻底清理 + `syncAll()` 重建断连连接，模拟"退出重进"效果 | ✅ 已修复 |
| **[新] 硬编码字符串** | 中 | MCP 状态字符串改用 stringResource，补充 5 locale 翻译 | ✅ 已修复 |

### 架构变更

#### 状态机（0.0.7）

```kotlin
sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()
    data class Dormant(val nextRetryInMs: Long) : McpStatus()  // 新增
    data class Error(val message: String) : McpStatus()
}
```

#### McpManager 成员变量（0.0.7）

| 变量 | 类型 | 变更说明 |
|------|------|----------|
| `clients` | `ConcurrentHashMap<Uuid, Client>` | key 从 `McpServerConfig` 改为 `Uuid` |
| `_status` | `MutableStateFlow<Map<Uuid, McpStatus>>` | 新增 `Dormant` 子状态 |
| `serverLocks` | `ConcurrentHashMap<Uuid, Mutex>` | **新增**：per-server 互斥锁 |
| `dormantJobs` | `ConcurrentHashMap<Uuid, Job>` | **新增**：Dormant 周期重试协程 |
| `reconnectJobs` | `ConcurrentHashMap<Uuid, Job>` | 类型从 MutableMap 改为 ConcurrentHashMap |
| `reconnectAttempts` | `ConcurrentHashMap<Uuid, Int>` | 类型从 MutableMap 改为 ConcurrentHashMap |

#### 重连分层（0.0.7）

```
transport 断连 (onClose/onError)
  ↓ 仅在 Connected 状态时触发
scheduleReconnect(configId)
  ↓ 快速重连: 5次指数退避 (1s→2s→4s→8s→16s)
  ↓ 失败超过上限
enterDormant(configId)
  ↓ 休眠重试: 60s × 30次 (共 30 分钟兜底窗口)
  ↓ 全部失败
Error("MCP reconnect failed after 30 dormant retries")
```

#### 数据同步链路（0.0.7）

```
server schema 变更
  → tools/list_changed 通知 (新增)
  → McpManager.syncTools()
  → mergeTools(serverSchema, 现有偏好)
  → 写回 settingsStore (保留 enable/needsApproval)

用户改偏好 (enable/needsApproval)
  → 直接写 settingsStore
  → 下次 getAllAvailableTools 读到新值

getAllAvailableTools(assistant)
  → 从 settingsStore 读 (唯一数据源)
  → 按 assistant.mcpServers 过滤
```

#### 职责分层（0.0.7）

```
SettingsStore（配置 + 工具缓存层，唯一持久化数据源）
  └─ Settings.mcpServers: List<McpServerConfig>
     — McpCommonOptions 保留 tools 字段（混合 schema + 用户偏好）
     — sync 写回此处，getAllAvailableTools 直接读取

McpManager（连接 + 状态机层，纯内存态）
  ├─ 连接池: ConcurrentHashMap<Uuid, Client>
  ├─ 状态机: StateFlow<Map<Uuid, McpStatus>> (6 态)
  ├─ 重连: 快速 5 次 → Dormant 60s×30 周期（30 分钟兜底窗口）+ 离线感知跳过
  ├─ 恢复链: settings Flow / ProcessLifecycle onStart / NetworkCallback onAvailable
  ├─ 通知: setNotificationHandler<ToolListChangedNotification>
  ├─ per-server Mutex: 序列化所有操作
  └─ cleanupServer: 统一彻底清理

ChatService（消费层）
  └─ getAllAvailableTools(assistant) — 按 assistant 候选集过滤
```

---

## 十四、OAuth 2.1 授权扩展（0.0.8）

> 以下变更在 0.0.8 版本中引入，实现 MCP 授权规范 (2025-11-25) 的完整 OAuth 流程。

### 14.1 新增文件

| 文件 | 职责 |
|------|------|
| `McpOAuthClient.kt` | OAuth 2.1 HTTP 客户端：PRM/AS 元数据发现、DCR、PKCE、令牌交换/刷新 |
| `McpOAuthCallback.kt` | redirect URI 常量 + Chrome Custom Tabs 启动函数 |
| `McpOAuthCallbackActivity.kt` | 透明 Activity 接收 deep link 回调，经 AppEventBus 转发 |
| `AppEvent.kt` | 新增 `McpOAuthCallback(state, code, error)` 事件 |

### 14.2 数据结构变更

#### McpStatus 新增 2 个状态

```kotlin
sealed class McpStatus {
    // ... 0.0.7 的 6 个状态 ...
    data object NeedsAuthorization : McpStatus()  // 服务器返回 401，需用户授权
    data object Authorizing : McpStatus()          // 正在进行 OAuth 授权流程
}
```

#### McpCommonOptions 新增 oauth 字段

```kotlin
data class McpCommonOptions(
    // ... 原有字段 ...
    val oauth: McpOAuthState? = null,  // 新增：OAuth 授权状态
)
```

#### McpOAuthState（新增）

```kotlin
@Serializable
data class McpOAuthState(
    val enabled: Boolean = false,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val registrationEndpoint: String? = null,
    val scope: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
)
```

#### McpServerConfig 新增 serverUrl 扩展属性

```kotlin
val McpServerConfig.serverUrl: String
    get() = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }
```

### 14.3 McpManager 成员变量变更

| 变量 | 类型 | 变更说明 |
|------|------|----------|
| `appEventBus` | `AppEventBus` | **新增**：OAuth 回调事件传递 |
| `oauthClient` | `McpOAuthClient` | **新增**：OAuth HTTP 客户端 |
| `authorizationJobs` | `ConcurrentHashMap<Uuid, Job>` | **新增**：OAuth 授权协程管理 |
| `okHttpClient` | `OkHttpClient` | 新增 `RequestLoggingInterceptor` |

### 14.4 新增常量

| 常量 | 值 | 用途 |
|------|-----|------|
| `TOKEN_REFRESH_LEEWAY_MS` | 60_000L | 令牌到期前 60s 视为需要刷新 |
| `OAUTH_CALLBACK_TIMEOUT` | 5.minutes | OAuth 回调等待超时 |

### 14.5 新增方法

| 方法 | 职责 |
|------|------|
| `resolveHeaders()` | 合并用户 headers 与 OAuth Bearer token |
| `startAuthorization(config, context)` | 发起 OAuth 授权流程（浏览器） |
| `cancelAuthorization(config)` | 取消进行中的授权 |
| `authorizeInternal(config, context)` | 完整 9 步 OAuth 流程 |
| `clearAuthorization(config)` | 清除 OAuth 状态（登出） |
| `ensureFreshToken(config)` | 令牌即将过期时提前刷新 |
| `persistOAuthState(configId, oauth)` | 持久化 OAuth 状态到 settingsStore |
| `computeExpiry(expiresIn)` | 计算令牌过期时间戳 |
| `needsAuthorization(config, error)` | 判断失败是否应引导 OAuth 授权 |
| `looksUnauthorized(error)` | 错误文本是否疑似 401/invalid_token |

### 14.6 callTool 增强

`callTool` 在获取 config 后、transport 检查前新增令牌刷新逻辑：

```
callTool(serverId, toolName, args)
  ↓ getServerLock(serverId).withLock
  ↓ 获取 client + config
  ↓ ensureFreshToken(config)
  ↓ 若令牌已刷新:
    → cancelAllJobs(serverId)     // 清理所有待处理任务
    → closeClient(serverId)       // 关闭旧连接（携带过期令牌）
    → createAndConnect(freshConfig) // 用新令牌重建连接
    → 重新获取 client + config
  ↓ transport 检查 → callTool → 结果转换
```

### 14.7 createAndConnect 增强

连接失败时新增 OAuth 授权探测：

```
createAndConnect(config)
  ↓ connect + syncTools
  ↓ onFailure:
    → needsAuthorization(config, error)?
      → true:  setStatus(NeedsAuthorization)  // 引导用户授权
      → false: setStatus(Error(...))           // 普通错误
```

`needsAuthorization` 三层检测：
1. `looksUnauthorized(error)` — 错误文本匹配 401/unauthorized/invalid_token
2. `oauth.enabled == true` — 已开启 OAuth，令牌失效 → 引导重新授权
3. `hasManualAuth` — 用户手动配置 Authorization header → 尊重手动模式，不触发 OAuth
4. `discoverProtectedResource(serverUrl)` — 主动探测 PRM 元数据，确认服务器支持 OAuth

### 14.8 reconnectClient 增强

重连失败时同样检测 `NeedsAuthorization`，若需要授权则停止重连：

```
reconnectClient(config)
  ↓ ensureFreshToken → closeClient → createAndConnect
  ↓ 若 createAndConnect 返回 false:
    → status == NeedsAuthorization?
      → cancelAllJobs(config.id)  // 停止重连，等待用户操作
      → return
    → 否则抛异常让上层重试
```

### 14.9 cancelAllJobs 增强

新增 `authorizationJobs` 清理，确保禁用/移除服务器时 OAuth 授权协程也被取消：

```kotlin
private fun cancelAllJobs(serverId: Uuid) {
    reconnectJobs[serverId]?.cancel()
    dormantJobs[serverId]?.cancel()
    authorizationJobs[serverId]?.cancel()  // 新增
    // ... remove ...
}
```

### 14.10 transport headers 变更

transport 构建从 `config.commonOptions.headers` 改为 `config.resolveHeaders()`：

```kotlin
private fun McpServerConfig.resolveHeaders(): List<Pair<String, String>> {
    val base = commonOptions.headers
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthHeader = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthHeader) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}
```

### 14.11 状态转换新增

| 当前状态 | 触发条件 | 目标状态 |
|----------|----------|----------|
| 任意 | connect 失败 + needsAuthorization | NeedsAuthorization |
| NeedsAuthorization | 用户点击"授权" | Authorizing |
| Authorizing | 授权流程完成 + connect 成功 | Connected |
| Authorizing | 授权流程失败 | Error |
| Authorizing | 用户点击"取消授权" | NeedsAuthorization |
| Reconnecting | reconnect 失败 + needsAuthorization | NeedsAuthorization |
| Connected | callTool 前令牌刷新 | Connected（经 createAndConnect 重建） |

### 14.12 日志体系

0.0.7 引入 `logMcp(serverName, message)` 双日志机制（Logcat + LogPage），0.0.8 确保所有 OAuth 相关日志均使用此机制：

```
logMcp(name, "Connected (N tools synced)")       // 连接成功
logMcp(name, "Connection failed: ...")             // 连接失败
logMcp(name, "Needs OAuth authorization")          // 需要授权
logMcp(name, "OAuth authorization failed: ...")    // 授权失败
logMcp(name, "Token refreshed during callTool...") // 令牌刷新
logMcp(name, "Token refresh failed: ...")          // 刷新失败
logMcp(name, "OAuth probe failed: ...")            // PRM 探测失败
```

`McpOAuthClient` 内部的发现日志仍使用 `Log.i`（仅 Logcat），因为它是底层 HTTP 工具类，关键错误已由 `McpManager` 的 `logMcp` 捕获并写入 LogPage。

### 14.13 持久化影响

| 改动 | 持久化影响 | 风险 |
|------|-----------|------|
| `McpCommonOptions.oauth` 新增字段 | `@Serializable` + 默认值 `null`，旧 `settings.json` 无此字段时默认不启用 | **无迁移风险** |
| `McpOAuthState` 持久化在 DataStore | OAuth 令牌/刷新令牌存储在 `settings.json` | 令牌为明文存储（与上游一致） |
| deep link `measix://mcp-oauth-callback` | 仅 Manifest 声明 | **无运行时行为变化** |
| `McpStatus` 新增 `NeedsAuthorization`/`Authorizing` | 内存状态，不持久化 | **无风险** |

---

## 十五、上游对比与修正（0.0.9）

> 基于 upstream `McpSessionRegistry` (460 行) 与本地 `McpManager` (~1000 行) 的逐项对比。

### 15.1 架构对比

| 维度 | 上游 (McpSessionRegistry) | 本地 (McpManager) | 评价 |
|------|--------------------------|-------------------|------|
| 类拆分 | McpSessionRegistry + McpStatusStore + McpOAuthCoordinator | 单体 McpManager | 本地单体可工作，但维护成本更高 |
| 状态载体 | McpSession (config + client + connectedConfig + Mutex) | 独立 ConcurrentHashMap (clients + connectedConfigs + serverLocks) | 功能等价 |
| ConnectResult | sealed interface (Success/Stale/NeedsAuthorization/Failed) | 无，直接返回 Boolean | 上游更健壮（见 15.3） |
| requestReconnect | 集中去重，检查 `reconnectJob?.isActive` | cancel-and-restart | 上游更高效，但本地保证 delay 从最后断连开始 |
| OAuth 令牌刷新锁 | 独立 `refreshLocks` (per-server Mutex) | 复用 `serverLocks` | 本地可行：ensureFreshToken 始终在 serverLock 内调用 |

### 15.2 本地合理增强（保留）

| 增强 | 上游无 | 理由 |
|------|--------|------|
| **Dormant 模式** (60s × 30 = 30 分钟兜底) | ✅ | 移动端网络不稳定，5 次 31 秒不够；30 分钟窗口覆盖服务端重启 |
| **NetworkMonitor** (离线跳过 + 恢复主动 syncAll) | ✅ | 省电 + 加速恢复；比 transport.onClose 回调快 10-30s |
| **ProcessLifecycle** (前台恢复 syncAll) | ✅ | Android 后台静默断连 SSE/HTTP 的标准应对 |
| **isSseStreamGiveUpError** | ✅ | 上游也有，一致 |
| **tools/list_changed 通知** | ✅ | 上游也有，一致 |
| **OAuth 2.1 完整流程** | ✅ | 上游也有，功能等价 |
| **logMcp 双日志** (Logcat + LogPage) | ✅ | 上游仅 Logcat，本地增强用户可查 |

### 15.3 已修复问题

| 问题 | 上游做法 | 本地缺陷 | 修复 |
|------|----------|----------|------|
| `getStatus` 缺少 `distinctUntilChanged()` | `.distinctUntilChanged()` | 无 — 任意 server 状态变更都触发所有 server 的 Flow emit | ✅ 添加 `distinctUntilChanged()` |
| `addClient` 未从 settingsStore 重读配置 | 内部 `settingsStore.settingsFlow.value.find{...}` 重读 | 直接用 `configInput` 参数 — 协程排队期间 config 可能已变更 | ✅ 添加重读逻辑，config 不存在/已禁用时直接清理 |
| `ensureFreshToken` 未从 settingsStore 重读 | 内部重读最新 config | 直接用参数 — token 刷新可能基于过期 URL，且 `persistOAuthState` 可能覆盖用户并发修改 | ✅ 添加重读逻辑 |
| `callTool` 仅比较 `accessToken` | `hasSameConnectionParameters(connectedConfig, freshConfig)` | `accessToken !=` — 遗漏 URL/headers 变更，不触发重连 | ✅ 改用 `hasSameConnectionParameters(connectedConfigs[serverId], freshConfig)` |

### 15.4 上游模式未引入（设计差异，非缺陷）

| 上游模式 | 不引入理由 |
|----------|-----------|
| McpSession 抽象类 | 本地用独立 Map 已实现等价功能，引入 Session 类增加间接层无明确收益 |
| ConnectResult sealed interface | 本地 per-server Mutex 保证操作串行，config 在操作期间不会被修改（settingsStore 变更触发 init block 排队等锁），Stale 场景不成立 |
| McpOAuthCoordinator 独立类 | 本地 ensureFreshToken 始终在 serverLock 内调用，无需额外 refreshLocks；拆分增加类间耦合 |
| `reconnectAfterDelay` 用 `NonCancellable` 清理 job 引用 | 本地 `cancelAllJobs` 从外部统一清理，覆盖面更广（包括 dormantJobs/authorizationJobs） |

---

## 十六、连接参数检测重构（0.0.10）

> 上游 `0f5b3f3e` 拆分 McpManager 并引入 `McpConnectionKey`，本地选择性引入连接键 + Error.detail，保留单体架构。

### 16.1 McpConnectionKey

**文件**：`McpConnectionKey.kt`（新建）

替代旧版 `McpConfig.ConnectionKey`（已删除）。旧版按 `id` 比较（`checkDifferent`），无法区分「连接参数变化」与「工具开关变化」——用户改 URL 时需要 remove+add 两条操作；用户改工具开关时不触发任何操作但也不会刷新。

新版 `connectionKey()` 扩展函数提取连接键（transportType/serverUrl/clientName/headers），`hasSameConnectionParameters()` 比较两个配置的连接键。

**`clientName` 参与连接键的理由**：`createAndConnect` 将 `commonOptions.name` 作为 MCP `initialize` 请求的 `clientInfo.name` 发送给服务端。改名后需重建连接以更新服务端感知的客户端身份。

### 16.2 connectedConfigs 追踪 Map

**新增成员**：`connectedConfigs: ConcurrentHashMap<Uuid, McpServerConfig>`

记录每个连接**建立时**使用的配置快照。在 `createAndConnect` 中写入，在 `closeClient` 中清除。

**三处使用点**：

| 位置 | 用途 |
|------|------|
| init block | 已存在 server：比较 `connectedConfigs[id]` 与新配置，参数变化时 `addClient` 重连 |
| `callTool` | `ensureFreshToken` 后比较，token/URL/headers 变化时重建连接 |
| `syncAll` | 同上，参数变化时 `addClient` 而非仅 `syncTools` |

### 16.3 init block 重构

旧版使用 `checkDifferent` 三步走（过滤 → 比较 → 并行 add/remove）。新版改为三路分类：

```
收到新配置
  │
  ├── 新增 server：id 不在 clients 中 且 不在授权流程中 → addClient
  │
  ├── 已存在 server：hasSameConnectionParameters(connectedConfigs[id], config)
  │     → false（参数变化）→ addClient 重连
  │     → true（仅工具变化）→ 跳过，由 syncAll 的 syncTools 刷新
  │
  └── 删除 server：id 在 clients 中但已不在 enabled 列表 → removeClient
```

**改进**：用户仅切换工具开关时不再触发 remove+add 假重连（旧版 `checkDifferent` 因 `connectionKey` 不含 tools 返回空集，但不刷新已有连接的工具列表；新版跳过后由 `syncAll` 的 `syncTools` 自然刷新）。

### 16.4 McpStatus.Error 增强

```kotlin
data class Error(val message: String, val detail: String? = null) : McpStatus() {
    companion object {
        fun from(throwable: Throwable, fallbackMessage: String? = null): Error
    }
}
```

- `message`：简短摘要，用于列表内联展示
- `detail`：完整堆栈（`throwable.stackTraceToString()`），用于展开查看与复制
- `Error.from()` 自动提取 message（优先 throwable.message，其次 fallbackMessage，最后类名）

`SettingMcpPage` 中 Error 文本可点击展开 `AlertDialog`，含 `SelectionContainer` + 复制按钮。

### 16.5 addClient 重读配置

`addClient(configInput)` 从 `settingsStore.settingsFlow.value` 重读最新配置，而非直接使用 `configInput`。协程排队期间 config 可能已变更（用户修改 URL 后又禁用），重读确保使用最新配置，config 不存在/已禁用时直接清理并返回。

### 16.6 ensureFreshToken 重读配置

同上，`ensureFreshToken(configInput)` 从 `settingsStore` 重读最新配置后再检查 token 过期。避免基于过期 URL 刷新 token，且 `persistOAuthState` 不会覆盖用户并发修改。

### 16.7 死代码清理

| 删除项 | 原因 |
|--------|------|
| `McpConfig.ConnectionKey` 数据类 | 被 `McpConnectionKey.connectionKey()` 替代 |
| `McpServerConfig.connectionKey` 抽象属性 | 同上 |
| `McpConfigTest` 中 9 个 `connectionKey`/`checkDifferent` 测试 | 被替换为 `McpConnectionKeyTest` 3 个测试 |
| `CollectionUtils.checkDifferent` 的 MCP 用例 | init block 不再使用（`checkDifferent` 函数本身保留，其他模块可能使用） |

### 16.8 职责划分分析

| 组件 | 职责 | 评价 |
|------|------|------|
| `McpConnectionKey.kt` | 连接键定义 + 比较函数 | 独立文件，职责单一，可独立测试 |
| `McpManager` | 连接生命周期管理 + 连接参数变化检测 | `connectedConfigs` 作为追踪 Map 内聚于 McpManager，与 `clients` Map 同步维护 |
| `McpStatus.Error` | 错误状态 + 详情 | `Error.from()` 工厂方法封装异常提取逻辑，调用方简洁 |
| `SettingMcpPage` | 错误详情 UI 展示 | 纯 UI 层，通过 `McpStatus.Error` 的 `detail` 字段获取数据 |

**结论**：各组件职责清晰，连接键逻辑独立于 McpManager 便于测试，`connectedConfigs` 追踪 Map 与 `clients` Map 同步维护无一致性风险（均在 `createAndConnect`/`closeClient` 中配对操作，且在 `getServerLock` 保护下）。
