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
| `app/.../data/ai/mcp/McpConfig.kt` | 配置数据结构（McpServerConfig、McpCommonOptions、McpTool、ConnectionKey） |
| `app/.../data/ai/mcp/McpStatus.kt` | 状态枚举定义 |
| `app/.../service/ChatService.kt` | MCP 工具的消费方（构建工具列表、调用工具） |
| `app/.../utils/CollectionUtils.kt` | `checkDifferent` 工具函数 |
| `app/.../data/datastore/PreferencesStore.kt` | SettingsStore，配置持久化 |

---

## 二、数据结构

### 2.1 McpManager 成员变量（McpManager.kt:53-82）

| 变量 | 类型 | 用途 |
|------|------|------|
| `okHttpClient` | `OkHttpClient` | 底层 HTTP 客户端。20s 连接超时，10min 读超时，120s 写超时 |
| `client` | `Ktor HttpClient` | 基于 OkHttp，安装 ContentNegotiation + SSE 插件。所有 transport 共享此客户端 |
| `clients` | `MutableMap<McpServerConfig, Client>` | 活跃连接池。key 是配置对象，value 是 MCP SDK Client |
| `reconnectJobs` | `MutableMap<Uuid, Job>` | 重连协程。key 是 config.id |
| `reconnectAttempts` | `MutableMap<Uuid, Int>` | 重连计数。key 是 config.id |
| `syncingStatus` | `MutableStateFlow<Map<Uuid, McpStatus>>` | 状态跟踪。key 是 config.id，UI 通过此变量展示状态 |

### 2.2 McpStatus 状态定义（McpStatus.kt:3-9）

```kotlin
sealed class McpStatus {
    data object Idle : McpStatus()                                          // 默认值，未连接
    data object Connecting : McpStatus()                                    // 正在连接或同步工具
    data object Connected : McpStatus()                                     // 已连接，可调用工具
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()  // 正在重连
    data class Error(val message: String) : McpStatus()                     // 错误状态
}
```

**注意**：`getStatus(config)` 在 map 中找不到时返回 `Idle`（McpManager.kt:438）。

### 2.3 ConnectionKey（McpConfig.kt:51-56）

```kotlin
data class ConnectionKey(
    val id: Uuid,
    val transportType: String,  // "sse" 或 "streamable_http"
    val url: String,
    val headers: List<Pair<String, String>>,
)
```

**确定**：`tools` 和 `name` **不参与** ConnectionKey 的构成。ConnectionKey 用于判断两个配置是否需要重建连接。

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

- `SseTransportServer`：SSE 传输，`connectionKey.transportType = "sse"`
- `StreamableHTTPServer`：Streamable HTTP 传输，`connectionKey.transportType = "streamable_http"`

两者结构相同：`id: Uuid`、`commonOptions: McpCommonOptions`、`url: String`。

---

## 三、常量定义（McpManager.kt:48-51）

| 常量 | 值 | 用途 |
|------|-----|------|
| `MAX_RECONNECT_ATTEMPTS` | 5 | 最大重连次数 |
| `BASE_RECONNECT_DELAY_MS` | 1000L | 基础重连延迟（1 秒） |
| `MAX_RECONNECT_DELAY_MS` | 30000L | 最大重连延迟（30 秒） |

---

## 四、McpManager 初始化（McpManager.kt:84-113）

```
McpManager 被 Koin 创建
    │
    ▼
init 块启动
    │
    ▼
appScope.launch {
    settingsStore.settingsFlow
        .map { settings -> settings.mcpServers }  // 只提取 mcpServers 字段
        .collect { mcpServerConfigs -> 处理配置变更 }
}
```

### 4.1 配置变更处理逻辑

```
收到新的 mcpServerConfigs
    │
    ▼
第一步：过滤
    newConfigs = mcpServerConfigs.filter {
        it.commonOptions.enable && it.commonOptions.name.isNotBlank()
    }
    │
    ▼
第二步：比较（使用 checkDifferent）
    currentConfigs = clients.keys.toList()
    (toAdd, toRemove) = currentConfigs.checkDifferent(
        other = newConfigs,
        eq = { a, b -> a.connectionKey == b.connectionKey }
    )
    │
    // toAdd = newConfigs 中存在，但 currentConfigs 中不存在的
    // toRemove = currentConfigs 中存在，但 newConfigs 中不存在的
    │
    ▼
第三步：并行执行
    toAdd.forEach { cfg -> appScope.launch { addClient(cfg) } }
    toRemove.forEach { cfg -> appScope.launch { removeClient(cfg) } }
```

**确定**：

- `checkDifferent` 的比较依据是 `connectionKey`（包含 id、transportType、url、headers）
- `addClient` 和 `removeClient` 是**并行**执行的（各自启动独立协程）
- 只有 `enable=true && name.isNotBlank()` 的配置才会被添加

### 4.2 checkDifferent 函数（CollectionUtils.kt:3-14）

```kotlin
fun <E> Collection<E>.checkDifferent(
    other: Collection<E>,
    eq: (E, E) -> Boolean,
): Pair<List<E>, List<E>> {
    val added = other.filter { e -> this.none { eq(it, e) } }    // other 中有，this 中没有
    val removed = this.filter { e -> other.none { eq(it, e) } }  // this 中有，other 中没有
    return added to removed
}
```

---

## 五、单个 MCP 服务器的完整生命周期

### 5.1 添加阶段：addClient(config)（McpManager.kt:209-254）

```
addClient(config)
    │
    ▼
withContext(Dispatchers.IO) {
    │
    ├── 第一步：清理旧状态
    │   removeClient(config)              // 先移除同 id 的旧连接
    │   cancelReconnect(config.id)        // 取消重连任务
    │   reconnectAttempts[config.id] = 0  // 重置重连计数
    │
    ▼
    ├── 第二步：创建 transport 和 client
    │   transport = getTransport(config)
    │   // SseTransportServer → SseClientTransport(urlString, client, requestBuilder)
    │   // StreamableHTTPServer → StreamableHttpClientTransport(url, client, requestBuilder)
    │
    │   client = Client(clientInfo = Implementation(
    │       name = config.commonOptions.name,
    │       version = "1.0"
    │   ))
    │
    ▼
    ├── 第三步：注册 transport 回调
    │   transport.onClose {
    │       val currentStatus = syncingStatus.value[config.id]
    │       if (currentStatus == McpStatus.Connected) {  // 只有 Connected 才触发重连
    │           scheduleReconnect(config)
    │       }
    │   }
    │
    │   transport.onError { error ->
    │       val currentStatus = syncingStatus.value[config.id]
    │       if (currentStatus == McpStatus.Connected) {  // 只有 Connected 才触发重连
    │           scheduleReconnect(config)
    │       }
    │   }
    │
    ▼
    ├── 第四步：放入连接池
    │   clients[config] = client
    │
    ▼
    ├── 第五步：连接流程
    │   runCatching {
    │       setStatus(config, Connecting)      // 状态 → Connecting
    │       client.connect(transport)           // MCP 协议握手
    │       sync(config)                        // 同步工具列表（见 5.2）
    │       setStatus(config, Connected)        // 状态 → Connected
    │       reconnectAttempts[config.id] = 0    // 重置重连计数
    │   }
    │
    ▼
    └── 第六步：失败处理
        .onFailure {
            clients.remove(config)                      // 从连接池移除
            setStatus(config, Error(it.message ?: ...)) // 状态 → Error
        }
}
```

**确定**：

- `addClient` 会先调用 `removeClient` 清理同 id 的旧连接
- transport 回调只在 `Connected` 状态下触发重连，避免正常关闭时重连
- `sync` 在 `connect` 之后、`Connected` 之前调用
- 失败时从 `clients` 移除，状态设为 `Error`

### 5.2 同步阶段：sync(config)（McpManager.kt:256-290）

```
sync(config)
    │
    ▼
第一步：查找 client
    entry = clients.entries.find { it.key.id == config.id }
    client = entry?.value
    if (client == null) return  // 找不到则直接返回
    │
    ▼
第二步：设置状态
    setStatus(config, Connecting)
    │
    ▼
第三步：确保 transport 存在
    if (client.transport == null) {
        client.connect(getTransport(config))
    }
    │
    ▼
第四步：获取服务器工具列表
    serverTools = client.listTools().tools  // MCP 协议调用
    │
    ▼
第五步：更新 settingsStore（持久化）
    settingsStore.update { old ->
        old.copy(mcpServers = old.mcpServers.map { serverConfig ->
            if (serverConfig.id != config.id) return@map serverConfig
            │
            ├── mergedTools = mergeTools(serverTools, common.tools)
            │   // mergeTools 逻辑（McpConfig.kt:95-124）：
            │   // - 服务器有，本地无 → 新增（enable=true）
            │   // - 服务器有，本地有 → 更新 description/inputSchema，保留 enable/needsApproval
            │   // - 服务器无，本地有 → 移除
            │
            ├── newConfig = serverConfig.clone(commonOptions = common.copy(tools = mergedTools))
            │
            ├── 更新 clients Map 的 key（因为 config 对象变了）
            │   clients.remove(entry.key)
            │   clients[newConfig] = client
            │
            └── return newConfig
        })
    }
    │
    ▼
第六步：恢复状态
    setStatus(config, Connected)
```

**确定**：

- `sync` 会**修改 settingsStore**，这会触发 `settingsFlow` 的监听器
- `sync` 会**更新 clients Map 的 key**（因为 tools 变了，config 对象变了）
- `sync` 前后状态变化：`Connecting → Connected`
- `mergeTools` 是单向同步：以服务器为准，本地只保留 enable/needsApproval 的用户偏好

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

#### 5.4.2 scheduleReconnect(config)（McpManager.kt:325-371）

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

#### 5.4.3 calculateBackoffDelay(attempt)（McpManager.kt:378-382）

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

#### 5.4.4 reconnectClient(config)（McpManager.kt:384-430）

```
reconnectClient(config)
    │
    ▼
withContext(Dispatchers.IO) {
    │
    ├── 第一步：关闭旧客户端
    │   oldEntry = clients.entries.find { it.key.id == config.id }
    │   if (oldEntry != null) {
    │       oldEntry.value.close()
    │       clients.remove(oldEntry.key)
    │   }
    │
    ▼
    ├── 第二步：创建新的 transport 和 client
    │   transport = getTransport(config)
    │   client = Client(clientInfo = Implementation(...))
    │
    ▼
    ├── 第三步：注册新的回调（同 addClient）
    │   transport.onClose { if (status == Connected) scheduleReconnect(config) }
    │   transport.onError { if (status == Connected) scheduleReconnect(config) }
    │
    ▼
    ├── 第四步：放入连接池
    │   clients[config] = client
    │
    ▼
    ├── 第五步：设置状态
    │   setStatus(config, Connecting)
    │
    ▼
    ├── 第六步：连接流程
    │   runCatching {
    │       client.connect(transport)
    │       sync(config)
    │       setStatus(config, Connected)
    │       reconnectAttempts[config.id] = 0  // 重置重连计数
    │   }
    │
    ▼
    └── 第七步：失败处理
        .onFailure {
            clients.remove(config)
            setStatus(config, Error(it.message ?: ...))
        }
}
```

**确定**：

- `reconnectClient` 与 `addClient` 的流程几乎相同
- 区别：`reconnectClient` 不调用 `removeClient`（因为已经在第一步手动清理了）
- 重连成功后 `reconnectAttempts` 重置为 0

### 5.5 移除阶段：removeClient(config)（McpManager.kt:309-323）

```
removeClient(config)
    │
    ▼
withContext(Dispatchers.IO) {
    │
    ├── 第一步：取消重连任务
    │   cancelReconnect(config.id)
    │   // cancelReconnect: reconnectJobs[id]?.cancel(), reconnectJobs.remove(id)
    │
    ▼
    ├── 第二步：找到所有匹配的 entries
    │   toRemove = clients.entries.filter { it.key.id == config.id }
    │
    ▼
    ├── 第三步：逐个清理
    │   toRemove.forEach { entry ->
    │       entry.value.close()                       // 关闭 MCP 客户端
    │       clients.remove(entry.key)                 // 从连接池移除
    │       syncingStatus.update { it - entry.key.id }  // 从状态表移除
    │   }
    │
    ▼
    └── 第四步：清理重连计数
        reconnectAttempts.remove(config.id)
}
```

**确定**：

- `removeClient` 会取消重连任务
- `removeClient` 会从 `syncingStatus` 中移除状态（状态变为不存在，`getStatus` 返回 `Idle`）
- `removeClient` 会从 `reconnectAttempts` 中移除计数

---

## 六、工具调用链路

### 6.1 getAllAvailableTools()（McpManager.kt:119-131）

```kotlin
fun getAllAvailableTools(): List<Triple<Uuid, String, McpTool>> {
    val settings = settingsStore.settingsFlow.value
    val assistant = settings.getCurrentAssistant()
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

### 6.2 callTool(serverId, toolName, args)（McpManager.kt:133-163）

```
callTool(serverId, toolName, args)
    │
    ▼
第一步：查找 client
    entry = clients.entries.find { it.key.id == serverId }
    client = entry?.value
    if (client == null) {
        return "Failed to execute tool, because no such mcp client for the tool"
    }
    config = entry.key
    │
    ▼
第二步：确保 transport 存在
    if (client.transport == null) {
        client.connect(getTransport(config))
    }
    │
    ▼
第三步：调用工具
    runCatching {
        result = client.callTool(
            request = CallToolRequest(params = CallToolRequestParams(name=toolName, arguments=args)),
            options = RequestOptions(timeout = 120.seconds)
        )
        │
        ▼
        第四步：转换结果
        result.content.map {
            when (it) {
                is TextContent → UIMessagePart.Text(it.text)
                is ImageContent → convertImageContentToFilePart(it)  // 保存图片到本地
                else → UIMessagePart.Text(JsonInstant.encodeToString(it))
            }
        }
    }
    │
    ▼
    第五步：失败处理
    .onFailure { e ->
        setStatus(config, McpStatus.Error(e.message ?: e.javaClass.name))
    }
    .getOrElse { e ->
        listOf(UIMessagePart.Text("Failed to execute tool: ${e.message}"))
    }
```

**确定**：

- `callTool` 会先检查 `client.transport`，如果为 null 会重新连接
- `callTool` 失败会将状态设为 `Error`，但**不会触发重连**
- `callTool` 的超时是 120 秒

### 6.3 ChatService 中的使用（ChatService.kt:565-609）

```
ChatService.handleMessageComplete()
    │
    ▼
构建 tools 列表：
    tools = buildList {
        // ... 其他工具（搜索、本地、工作空间、Skill）...
        │
        ▼
        mcpManager.getAllAvailableTools()  // 获取可用工具
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
  → connect 成功
  → sync(config)
    → client.listTools() 获取工具
    → settingsStore.update(...)  // 更新 tools
    → settingsFlow 发出新值
    → init 块的 collect 被触发
    → checkDifferent 比较 connectionKey
    → connectionKey 相同（只是 tools 变了）
    → toAdd = [], toRemove = []
    → 不触发任何操作
  → setStatus(config, Connected)
```

**确定**：`sync` 更新 `settingsStore` 会触发 `settingsFlow`，但由于 `connectionKey` 没变，不会产生额外的 `addClient`/`removeClient`。

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

```
用户修改 URL
  → settingsStore 更新
  → settingsFlow 发出新配置
  → checkDifferent 比较 connectionKey
  → 旧 connectionKey.url != 新 connectionKey.url
  → toRemove = [旧配置], toAdd = [新配置]
  → 并行执行：
    → removeClient(旧配置)：关闭连接，清理状态
    → addClient(新配置)：创建新连接
```

### 场景 E：用户禁用 MCP 服务器（enable = false）

```
用户禁用
  → settingsStore 更新
  → settingsFlow 发出新配置
  → 过滤：enable=false 的被排除
  → toRemove = [被禁用的配置]
  → removeClient(config)：关闭连接，清理状态，从 syncingStatus 移除
```

### 场景 F：用户修改工具开关（tool.enable = false）

```
用户禁用某个工具
  → settingsStore 更新
  → settingsFlow 发出新配置
  → checkDifferent 比较 connectionKey
  → connectionKey 相同（tools 不参与比较）
  → toAdd = [], toRemove = []
  → 不触发任何连接操作
  → 下次 getAllAvailableTools() 时该工具不会出现
```

### 场景 G：重连期间用户修改配置

```
状态：Reconnecting(2, 5)
  → 用户修改 URL
  → settingsStore 更新
  → settingsFlow 发出新配置
  → checkDifferent 比较 connectionKey
  → 旧 connectionKey 存在于 clients 中
  → 新 connectionKey 不同于旧 connectionKey
  → toRemove = [旧配置], toAdd = [新配置]
  → 并行执行：
    → removeClient(旧配置)：cancelReconnect → 关闭连接 → 清理状态
    → addClient(新配置)：创建新连接
```

**确定**：`removeClient` 会调用 `cancelReconnect`，取消正在进行的重连协程。

### 场景 H：重连期间配置被禁用

```
状态：Reconnecting(2, 5)
  → 用户禁用配置
  → settingsStore 更新
  → settingsFlow 发出新配置
  → 过滤：enable=false 的被排除
  → toRemove = [被禁用的配置]
  → removeClient(config)：cancelReconnect → 关闭连接 → 清理状态
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

| 当前状态 | 触发条件 | 目标状态 | 代码位置 |
|----------|----------|----------|----------|
| （不存在） | addClient 成功 | Connected | McpManager.kt:246 |
| （不存在） | addClient 失败 | Error | McpManager.kt:252 |
| Connected | transport.onClose/onError | Reconnecting | McpManager.kt:348 |
| Reconnecting | reconnectClient 成功 | Connected | McpManager.kt:422 |
| Reconnecting | reconnectClient 失败 | Error | McpManager.kt:428 |
| Reconnecting | 超过最大重连次数 | Error | McpManager.kt:332 |
| Reconnecting | 配置被禁用/移除 | （退出，不改状态） | McpManager.kt:357 |
| 任意 | removeClient | （从 map 移除） | McpManager.kt:319 |
| 任意 | callTool 失败 | Error | McpManager.kt:159 |

---

## 十、关键设计特征总结

1. **全局单例**：McpManager 是应用级单例，所有 MCP 连接全局管理
2. **配置驱动**：连接的创建和销毁由 `settingsStore.settingsFlow` 驱动
3. **ConnectionKey 判断**：只有 id/transportType/url/headers 变化才触发重建连接
4. **自动重连**：Connected 状态下断联会自动重连，最多 5 次，指数退避
5. **重连不恢复**：超过 5 次后停止，需要用户手动操作（如同步配置）
6. **sync 触连锁**：sync 更新 settingsStore 会触发 settingsFlow，但因 ConnectionKey 不变不会产生额外操作
7. **并行操作**：addClient 和 removeClient 通过独立协程并行执行

---

## 十一、存在的问题

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
