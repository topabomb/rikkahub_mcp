package net.weero.measix.pilot.data.ai.mcp

import androidx.annotation.VisibleForTesting
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.HttpClient
import me.rerere.common.android.Logging
import net.weero.measix.pilot.data.ai.RequestLoggingInterceptor
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method.Defined.NotificationsToolsListChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.files.saveUploadFromBytes
import net.weero.measix.pilot.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"

/**
 * MCP 服务器连接管理器
 *
 * 职责:
 * 1. **连接生命周期**: 管理 Client 连接池，响应 settings 变更自动 add/remove
 * 2. **重连策略**: transport 断连 → 指数退避（5次）→ Dormant 长间隔兜底（10次×60s）→ Error
 * 3. **网络感知**: NetworkCallback 网络恢复 → 主动 syncAll；离线时跳过重连节省电池
 * 4. **前台恢复**: ProcessLifecycle onStart → syncAll 健康检查
 * 5. **工具管理**: 连接成功后 syncTools 拉取 schema + 合并用户偏好；监听 list_changed 通知
 * 6. **状态追踪**: StateFlow<Map<Uuid, McpStatus>> 驱动 UI 实时显示连接状态
 *
 * 线程安全:
 * - 每个 server 有独立的 Mutex，序列化所有操作（connect/reconnect/syncTools/callTool）
 * - clients/reconnectJobs/dormantJobs 使用 ConcurrentHashMap
 *
 * 关键设计决策:
 * - reconnectClient 只调用 closeClient（不取消自身运行的 reconnectJob），否则 connect() 会抛 CancellationException
 * - 所有 runCatching 均显式 rethrow CancellationException，防止破坏结构化并发
 * - transport.onClose/onError 回调仅在 Connected 状态下触发重连，避免重复触发
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    private val networkMonitor: NetworkMonitor,
) {
    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val BASE_RECONNECT_DELAY_MS = 1000L
        const val MAX_RECONNECT_DELAY_MS = 30000L
        const val DORMANT_RETRY_INTERVAL_MS = 60_000L
        const val DORMANT_MAX_RETRIES = 30
        /** 离线时重连检查间隔：不执行实际重连，仅检查网络是否恢复 */
        const val OFFLINE_CHECK_INTERVAL_MS = 10_000L
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .addNetworkInterceptor(RequestLoggingInterceptor())
        .build()

    private val ktorClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    // === 连接池 ===
    private val clients = ConcurrentHashMap<Uuid, Client>()

    // === 状态机 ===
    private val _status = MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap())
    val syncingStatus: StateFlow<Map<Uuid, McpStatus>> = _status

    // === 重连管理 ===
    private val reconnectJobs = ConcurrentHashMap<Uuid, Job>()
    private val dormantJobs = ConcurrentHashMap<Uuid, Job>()
    private val reconnectAttempts = ConcurrentHashMap<Uuid, Int>()

    // === per-server 互斥锁 ===
    private val serverLocks = ConcurrentHashMap<Uuid, Mutex>()
    private fun getServerLock(serverId: Uuid) = serverLocks.getOrPut(serverId) { Mutex() }

    // === 日志辅助: Logcat + LogPage ===
    private fun logMcp(serverName: String, message: String) {
        Log.i(TAG, "[$serverName] $message")
        Logging.log("MCP", "[$serverName] $message")
    }

    // init: 三条恢复链
    init {
        // 链 1: settings 变更 → 自动 add/remove
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.mcpServers }
                .distinctUntilChanged()
                .collect { configs ->
                    val enabled = configs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                    val enabledIds = enabled.map { it.id }.toSet()
                    val currentIds = clients.keys
                    enabled.filter { it.id !in currentIds }.forEach { appScope.launch { addClient(it) } }
                    currentIds.filter { it !in enabledIds }.forEach { id -> appScope.launch { removeClient(id) } }
                }
        }

        // 链 2: 前台恢复 → syncAll（OS 可能在后台静默断开 SSE/HTTP）
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (clients.isNotEmpty()) {
                    appScope.launch { syncAll() }
                }
            }
        })

        // 链 3: 网络恢复 → syncAll（WiFi↔蜂窝切换、离线恢复后主动重建）
        // 这是最可靠的恢复信号，比 transport.onClose 回调快 10-30s
        networkMonitor.onNetworkAvailable = {
            appScope.launch { syncAll() }
        }
    }

    fun getClient(serverId: Uuid): Client? = clients[serverId]

    fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { it.enable }
                    .map { Triple(server.id, server.commonOptions.name, it) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        return getServerLock(serverId).withLock {
            val client = clients[serverId]
                ?: return@withLock listOf(UIMessagePart.Text("MCP server not connected"))
            val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == serverId }
                ?: return@withLock listOf(UIMessagePart.Text("MCP server config not found"))

            if (client.transport == null) {
                setStatus(serverId, McpStatus.Reconnecting(1, MAX_RECONNECT_ATTEMPTS))
                scheduleReconnect(serverId)
                return@withLock listOf(UIMessagePart.Text("MCP server not connected, reconnecting"))
            }

            val serverName = config.commonOptions.name

            runCatching {
                val result = client.callTool(
                    CallToolRequest(CallToolRequestParams(name = toolName, arguments = args)),
                    RequestOptions(timeout = 120.seconds)
                )
                result.content.map {
                    when (it) {
                        is TextContent -> UIMessagePart.Text(it.text)
                        is ImageContent -> convertImageContentToFilePart(it)
                        else -> UIMessagePart.Text(JsonInstant.encodeToString(it))
                    }
                }.also { logMcp(serverName, "Tool '$toolName' succeeded") }
            }.getOrElse { e ->
                // 1. 工具超时: TimeoutCancellationException 是 CancellationException 子类，但表示工具超时
                //    → 降级为错误文本返回给 AI，不中断对话，不重连（服务器还活着）
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    logMcp(serverName, "Tool '$toolName' timed out (120s)")
                    return@withLock listOf(UIMessagePart.Text("MCP tool '$toolName' timed out (120s)"))
                }
                // 2. 真正的协程取消: 必须向上传播，不吞不处理
                if (e is CancellationException) throw e
                // 3. 连接错误: 触发重连 + 返回错误文本
                if (isConnectionError(e)) {
                    logMcp(serverName, "Tool '$toolName' connection error: ${e.message}")
                    setStatus(serverId, McpStatus.Reconnecting(1, MAX_RECONNECT_ATTEMPTS))
                    scheduleReconnect(serverId)
                    return@withLock listOf(UIMessagePart.Text("MCP tool '$toolName' failed: connection error (${e.message})"))
                }
                // 4. 其他错误（McpException / 服务器返回错误等）: 返回错误文本，不重连
                logMcp(serverName, "Tool '$toolName' failed (${e.javaClass.simpleName}): ${e.message}")
                listOf(UIMessagePart.Text("MCP tool '$toolName' failed: ${e.message}"))
            }
        }
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$ext",
            mimeType = image.mimeType,
        )
        val uri = filesManager.getFile(entity).toUri()
        return UIMessagePart.Image(url = uri.toString())
    }

    private fun getTransport(config: McpServerConfig): AbstractTransport {
        val customHeaders = StringValues.build {
            config.commonOptions.headers.forEach { append(it.first, it.second) }
        }
        return when (config) {
            is McpServerConfig.SseTransportServer -> SseClientTransport(
                urlString = config.url,
                client = ktorClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
            is McpServerConfig.StreamableHTTPServer -> StreamableHttpClientTransport(
                url = config.url,
                client = ktorClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
        }
    }

    // ==================== 连接管理 ====================

    suspend fun addClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        getServerLock(config.id).withLock {
            cancelAllJobs(config.id)
            closeClient(config.id)
            createAndConnect(config)
        }
    }

    suspend fun removeClient(serverId: Uuid) = withContext(Dispatchers.IO) {
        getServerLock(serverId).withLock {
            val name = settingsStore.settingsFlow.value.mcpServers
                .find { it.id == serverId }?.commonOptions?.name ?: serverId.toString()
            cancelAllJobs(serverId)
            closeClient(serverId)
            reconnectAttempts.remove(serverId)
            _status.update { it - serverId }
            logMcp(name, "Disconnected (removed)")
        }
    }

    /**
     * 手动同步全部服务器（下拉刷新 / 前台恢复 / 网络恢复）
     *
     * 策略:
     * - Client 存在且 transport 存活 → syncTools 刷新（若失败且为连接错误 → 触发重连）
     * - Client 不存在或 transport 已断开 → addClient 完全重建
     */
    suspend fun syncAll() = withContext(Dispatchers.IO) {
        val configs = settingsStore.settingsFlow.value.mcpServers
            .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
        configs.forEach { config ->
            val existingClient = clients[config.id]
            if (existingClient != null && existingClient.transport != null) {
                getServerLock(config.id).withLock {
                    val client = clients[config.id]
                    if (client != null && client.transport != null) {
                        runCatching { syncTools(config.id) }
                            .onFailure {
                                if (it is CancellationException) throw it
                                if (isConnectionError(it)) {
                                    // 半开连接：syncTools 失败说明连接实际已断，触发重连
                                    logMcp(config.commonOptions.name, "syncAll detected stale connection: ${it.message}")
                                    scheduleReconnect(config.id)
                                } else {
                                    setStatus(config.id, McpStatus.Error(it.message ?: ""))
                                    logMcp(config.commonOptions.name, "syncTools failed: ${it.message}")
                                }
                            }
                    }
                }
            } else {
                runCatching { addClient(config) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    // ==================== 重连策略 ====================

    /**
     * 快速重连：指数退避（5次），失败后转入 Dormant。
     * 网络不可用时跳过实际重连，仅周期检查网络恢复。
     */
    private fun scheduleReconnect(configId: Uuid) {
        val attempt = (reconnectAttempts[configId] ?: 0) + 1

        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            enterDormant(configId)
            return
        }

        reconnectAttempts[configId] = attempt
        reconnectJobs[configId]?.cancel()
        val delayMs = calculateBackoffDelay(attempt)

        reconnectJobs[configId] = appScope.launch {
            try {
                val serverName = getServerName(configId)
                // 网络不可用时不浪费重连尝试，等待网络恢复
                if (!networkMonitor.isOnline.value) {
                    setStatus(configId, McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
                    logMcp(serverName, "Network offline, waiting for connectivity...")
                    delay(OFFLINE_CHECK_INTERVAL_MS)
                    // 网络仍未恢复 → 不消耗 attempt，递归重新调度
                    reconnectAttempts[configId] = attempt - 1
                    scheduleReconnect(configId)
                    return@launch
                }

                setStatus(configId, McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
                logMcp(serverName, "Reconnecting (attempt $attempt/$MAX_RECONNECT_ATTEMPTS, ${delayMs}ms delay)")
                delay(delayMs)

                val currentConfig = settingsStore.settingsFlow.value.mcpServers
                    .find { it.id == configId && it.commonOptions.enable }
                if (currentConfig == null) {
                    cancelAllJobs(configId)
                    closeClient(configId)
                    _status.update { it - configId }
                    return@launch
                }

                reconnectClient(currentConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scheduleReconnect(configId)
            }
        }
    }

    /**
     * Dormant 长间隔兜底重试：60s × 30 次，全部失败后标记 Error。
     * 这保证了即使快速重连全部失败，仍有长达 30 分钟的恢复窗口。
     */
    private fun enterDormant(configId: Uuid) {
        setStatus(configId, McpStatus.Dormant(DORMANT_RETRY_INTERVAL_MS))
        val serverName = getServerName(configId)
        logMcp(serverName, "Entering dormant mode (${DORMANT_RETRY_INTERVAL_MS / 1000}s interval, max $DORMANT_MAX_RETRIES retries)")

        dormantJobs[configId] = appScope.launch {
            var retries = 0
            while (retries < DORMANT_MAX_RETRIES && isActive) {
                delay(DORMANT_RETRY_INTERVAL_MS)
                retries++

                val currentConfig = settingsStore.settingsFlow.value.mcpServers
                    .find { it.id == configId && it.commonOptions.enable }
                if (currentConfig == null) {
                    cancelAllJobs(configId)
                    closeClient(configId)
                    _status.update { it - configId }
                    return@launch
                }

                try {
                    reconnectClient(currentConfig)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setStatus(configId, McpStatus.Dormant(DORMANT_RETRY_INTERVAL_MS))
                }
            }
            setStatus(configId, McpStatus.Error("MCP reconnect failed after $DORMANT_MAX_RETRIES dormant retries"))
            logMcp(serverName, "All reconnection attempts exhausted (Error)")
        }
    }

    @VisibleForTesting
    internal fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    // ==================== 内部连接逻辑 ====================

    /**
     * 创建 Client + Transport + 注册回调 + connect + syncTools。
     * 由 addClient 和 reconnectClient 共享调用（持锁上下文）。
     *
     * @return true=连接成功, false=连接失败
     */
    private suspend fun createAndConnect(config: McpServerConfig): Boolean {
        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(name = config.commonOptions.name, version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities())
        )
        setupNotificationHandlers(client, config)

        val configId = config.id
        transport.onClose {
            if (_status.value[configId] == McpStatus.Connected) scheduleReconnect(configId)
        }
        transport.onError {
            if (_status.value[configId] == McpStatus.Connected) scheduleReconnect(configId)
        }

        clients[config.id] = client
        setStatus(config.id, McpStatus.Connecting)

        return runCatching {
            client.connect(transport)
            val toolCount = syncTools(config.id)
            setStatus(config.id, McpStatus.Connected)
            reconnectAttempts[config.id] = 0
            logMcp(config.commonOptions.name, "Connected ($toolCount tools synced)")
            true
        }.onFailure {
            if (it is CancellationException) throw it
            closeClient(config.id)
            setStatus(config.id, McpStatus.Error(it.message ?: it.javaClass.name))
            logMcp(config.commonOptions.name, "Connection failed: ${it.message}")
        }.getOrElse { false }
    }

    /**
     * 重连：关闭旧 Client → createAndConnect。
     * 不调用 cancelAllJobs（reconnectClient 运行在 reconnectJob 中，取消自身会导致 connect 抛 CancellationException）。
     */
    private suspend fun reconnectClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        getServerLock(config.id).withLock {
            closeClient(config.id)
            val success = createAndConnect(config)
            if (!success) {
                // createAndConnect 已设置 Error 状态，这里抛异常让上层重试
                throw RuntimeException("Reconnect failed")
            }
        }
    }

    private suspend fun syncTools(configId: Uuid): Int {
        val client = clients[configId] ?: return 0
        val serverTools = client.listTools().tools

        val existingConfig = settingsStore.settingsFlow.value.mcpServers
            .find { it.id == configId } ?: return 0

        val merged = mergeTools(serverTools, existingConfig.commonOptions.tools)
        val newConfig = existingConfig.clone(
            commonOptions = existingConfig.commonOptions.copy(tools = merged)
        )
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map {
                    if (it.id == configId) newConfig else it
                }
            )
        }
        return merged.size
    }

    private fun setupNotificationHandlers(client: Client, config: McpServerConfig) {
        val configId = config.id
        val configName = config.commonOptions.name
        client.setNotificationHandler<ToolListChangedNotification>(
            NotificationsToolsListChanged
        ) {
            logMcp(configName, "Received tools/list_changed notification")
            appScope.launch {
                runCatching { syncTools(configId) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed to sync tools after list_changed for $configName", e)
                        logMcp(configName, "syncTools after list_changed failed: ${e.message}")
                    }
            }
            CompletableDeferred(Unit)
        }
    }

    private fun getServerName(configId: Uuid): String {
        return settingsStore.settingsFlow.value.mcpServers
            .find { it.id == configId }?.commonOptions?.name ?: configId.toString()
    }

    private suspend fun closeClient(serverId: Uuid) {
        clients[serverId]?.let {
            runCatching { it.close() }.onFailure { e ->
                if (e is CancellationException) throw e
                val name = getServerName(serverId)
                Log.w(TAG, "Failed to close MCP client for $serverId: ${e.message}")
                logMcp(name, "Failed to close client: ${e.message}")
            }
        }
        clients.remove(serverId)
    }

    private fun cancelAllJobs(serverId: Uuid) {
        reconnectJobs[serverId]?.cancel()
        reconnectJobs.remove(serverId)
        dormantJobs[serverId]?.cancel()
        dormantJobs.remove(serverId)
    }

    @VisibleForTesting
    internal fun isConnectionError(e: Throwable): Boolean {
        return e is java.io.IOException
            || e is StreamableHttpError
            || e.message?.contains("connection", ignoreCase = true) == true
            || e.message?.contains("timeout", ignoreCase = true) == true
            || e.message?.contains("closed", ignoreCase = true) == true
    }

    private fun setStatus(serverId: Uuid, status: McpStatus) {
        _status.update { it + (serverId to status) }
    }

    fun getStatus(serverId: Uuid): Flow<McpStatus> = _status.map { it[serverId] ?: McpStatus.Idle }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val McpJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}
