package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.files.saveUploadFromBytes
import net.weero.measix.pilot.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"

/**
 * MCP 服务器连接管理器
 *
 * 职责:
 * 1. **连接生命周期**: 管理 Client 连接池，响应 settings 变更自动 add/remove
 * 2. **重连策略**: transport 断连 → 指数退避（5次）→ Dormant 长间隔兜底（30次×60s）→ Error
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
    private val appEventBus: AppEventBus,
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

    // OAuth 相关常量
    private val TOKEN_REFRESH_LEEWAY_MS = 60_000L // 令牌到期前 60s 视为需要刷新
    private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .addNetworkInterceptor(RequestLoggingInterceptor())
        .build()

    private val oauthClient = McpOAuthClient(okHttpClient)

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
    // 记录每个连接建立时使用的配置，用于 hasSameConnectionParameters() 判断
    // 配置变更时仅需重连（URL/headers/token 变化）还是仅刷新工具（工具开关/Schema 变化）
    private val connectedConfigs = ConcurrentHashMap<Uuid, McpServerConfig>()

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

    // === OAuth 授权任务 ===
    private val authorizationJobs = ConcurrentHashMap<Uuid, Job>()

    // === 日志辅助: Logcat + LogPage ===
    private fun logMcp(serverName: String, message: String) {
        Log.i(TAG, "[$serverName] $message")
        Logging.log("MCP", "[$serverName] $message")
    }

    // init: 三条恢复链
    init {
        // 链 1: settings 变更 → 自动 add/remove
        // 注意: 授权流程中 persistOAuthState 会触发此 collect，需排除
        //       NeedsAuthorization/Authorizing 状态的 server，避免与授权流程竞争
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.mcpServers }
                .distinctUntilChanged()
                .collect { configs ->
                    val enabled = configs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                    val enabledIds = enabled.map { it.id }.toSet()
                    val currentIds = clients.keys
                    val currentStatus = _status.value
                    // 新增 server：不在 clients 中 且 不在授权流程中 → 发起连接
                    enabled
                        .filter { it.id !in currentIds }
                        .filter { id ->
                            val st = currentStatus[id.id]
                            st != McpStatus.NeedsAuthorization && st != McpStatus.Authorizing
                        }
                        .forEach { appScope.launch { addClient(it) } }
                    // 已存在 server：检查连接参数是否变化（URL/transport/headers/oauth token）
                    // 仅工具开关/Schema 变化时不重连，下次 syncAll 的 syncTools 会自然刷新
                    enabled
                        .filter { it.id in currentIds }
                        .filter { id ->
                            val st = currentStatus[id.id]
                            st != McpStatus.NeedsAuthorization && st != McpStatus.Authorizing
                        }
                        .forEach { config ->
                            val connected = connectedConfigs[config.id]
                            if (connected != null && !hasSameConnectionParameters(connected, config)) {
                                logMcp(config.commonOptions.name, "Connection parameters changed, reconnecting")
                                appScope.launch { addClient(config) }
                            }
                        }
                    // 删除 server：在 clients 中但已不在 enabled 列表 → 断开
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
            var client = clients[serverId]
                ?: return@withLock listOf(UIMessagePart.Text("MCP server not connected"))
            var config = settingsStore.settingsFlow.value.mcpServers.find { it.id == serverId }
                ?: return@withLock listOf(UIMessagePart.Text("MCP server config not found"))

            // 调用前确保 OAuth 令牌新鲜。若连接参数变化（token 刷新 / URL / headers 变更），需重建连接
            val freshConfig = ensureFreshToken(config)
            if (!hasSameConnectionParameters(connectedConfigs[serverId], freshConfig)) {
                logMcp(config.commonOptions.name, "Connection parameters changed during callTool, reconnecting")
                cancelAllJobs(serverId)
                closeClient(serverId)
                createAndConnect(freshConfig)
                client = clients[serverId]
                    ?: return@withLock listOf(UIMessagePart.Text("MCP server not connected after reconnection"))
                config = freshConfig
            }

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
                // 3. 授权错误: 令牌过期/无效，引导用户重新授权，不重连
                if (looksUnauthorized(e)) {
                    val code = extractHttpCode(e)
                    logMcp(serverName, "Tool '$toolName' auth error${if (code.isNotEmpty()) " ($code)" else ""}: ${e.message}")
                    setStatus(serverId, McpStatus.NeedsAuthorization)
                    return@withLock listOf(UIMessagePart.Text("MCP server requires authorization. Please re-authorize in MCP settings."))
                }
                // 4. 连接错误: 触发重连 + 返回错误文本
                if (isConnectionError(e)) {
                    val code = extractHttpCode(e)
                    logMcp(serverName, "Tool '$toolName' connection error: ${e.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                    setStatus(serverId, McpStatus.Reconnecting(1, MAX_RECONNECT_ATTEMPTS))
                    scheduleReconnect(serverId)
                    return@withLock listOf(UIMessagePart.Text("MCP tool '$toolName' failed: connection error (${e.message})"))
                }
                // 5. 其他错误（McpException / 服务器返回错误等）: 返回错误文本，不重连
                val code = extractHttpCode(e)
                // Do not expose runtime class names here: R8 obfuscates them in Release and the
                // resulting short names look like corrupted UI. The detailed exception is logged elsewhere.
                logMcp(serverName, "Tool '$toolName' failed: ${e.message}${if (code.isNotEmpty()) " [$code]" else ""}")
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
            config.resolveHeaders().forEach { append(it.first, it.second) }
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

    /** 合并用户自定义请求头与 OAuth Bearer 令牌。 */
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

    suspend fun addClient(configInput: McpServerConfig) = withContext(Dispatchers.IO) {
        getServerLock(configInput.id).withLock {
            // Re-read from settingsStore to avoid stale config.
            // configInput may come from a delayed coroutine that was queued before a config change;
            // using it directly could connect with an outdated URL/headers.
            val desiredConfig = settingsStore.settingsFlow.value.mcpServers
                .find { it.id == configInput.id }
            if (desiredConfig == null ||
                !desiredConfig.commonOptions.enable ||
                desiredConfig.commonOptions.name.isBlank()
            ) {
                // Config was removed or disabled between call and execution
                cancelAllJobs(configInput.id)
                closeClient(configInput.id)
                reconnectAttempts.remove(configInput.id)
                _status.update { it - configInput.id }
                return@withLock
            }
            val config = ensureFreshToken(desiredConfig)
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
     * - 连接参数变化（URL/headers/token）→ addClient 完全重建
     * - Client 存在且 transport 存活 → syncTools 刷新（若失败且为连接错误 → 触发重连）
     * - Client 不存在或 transport 已断开 → addClient 完全重建
     */
    suspend fun syncAll() = withContext(Dispatchers.IO) {
        val configs = settingsStore.settingsFlow.value.mcpServers
            .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
        configs.forEach { config ->
            // 跳过授权流程中的 server，避免与授权竞争
            val st = _status.value[config.id]
            if (st == McpStatus.NeedsAuthorization || st == McpStatus.Authorizing) return@forEach
            // 连接参数变化（如后台修改了 URL/headers/token）→ 重连而非仅刷新工具
            val connected = connectedConfigs[config.id]
            if (connected != null && !hasSameConnectionParameters(connected, config)) {
                logMcp(config.commonOptions.name, "syncAll: connection parameters changed, reconnecting")
                runCatching { addClient(config) }
                    .onFailure { if (it is CancellationException) throw it }
                return@forEach
            }
            val existingClient = clients[config.id]
            if (existingClient != null && existingClient.transport != null) {
                getServerLock(config.id).withLock {
                    val client = clients[config.id]
                    if (client != null && client.transport != null) {
                        runCatching { syncTools(config.id) }
                            .onFailure {
                                if (it is CancellationException) throw it
                                if (looksUnauthorized(it)) {
                                    // 授权错误：引导用户重新授权，不重连
                                    val code = extractHttpCode(it)
                                    setStatus(config.id, McpStatus.NeedsAuthorization)
                                    logMcp(config.commonOptions.name, "syncAll detected auth error${if (code.isNotEmpty()) " ($code)" else ""}: ${it.message}")
                                } else if (isConnectionError(it)) {
                                    // 半开连接：syncTools 失败说明连接实际已断，触发重连
                                    val code = extractHttpCode(it)
                                    logMcp(config.commonOptions.name, "syncAll detected stale connection: ${it.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                                    scheduleReconnect(config.id)
                                } else {
                                    setStatus(config.id, McpStatus.Error.from(it, "syncTools failed"))
                                    val code = extractHttpCode(it)
                                    logMcp(config.commonOptions.name, "syncTools failed: ${it.message}${if (code.isNotEmpty()) " [$code]" else ""}")
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
            if (isSseStreamGiveUpError(it)) return@onError
            if (_status.value[configId] == McpStatus.Connected) scheduleReconnect(configId)
        }

        clients[config.id] = client
        connectedConfigs[config.id] = config
        setStatus(config.id, McpStatus.Connecting)

        return try {
            client.connect(transport)
            val toolCount = syncTools(config.id)
            setStatus(config.id, McpStatus.Connected)
            reconnectAttempts[config.id] = 0
            logMcp(config.commonOptions.name, "Connected ($toolCount tools synced)")
            true
        } catch (it: Throwable) {
            // 无论什么异常（包括 CancellationException）都先清理 client，防止坏连接残留
            closeClient(config.id)
            if (it is CancellationException) throw it
            if (needsAuthorization(config, it)) {
                setStatus(config.id, McpStatus.NeedsAuthorization)
                logMcp(config.commonOptions.name, "Needs OAuth authorization${extractHttpCode(it).let { c -> if (c.isNotEmpty()) " ($c)" else "" }}")
            } else {
                setStatus(config.id, McpStatus.Error.from(it))
                val code = extractHttpCode(it)
                logMcp(config.commonOptions.name, "Connection failed: ${it.message}${if (code.isNotEmpty()) " [$code]" else ""}")
            }
            false
        }
    }

    /**
     * 重连：关闭旧 Client → createAndConnect。
     * 不调用 cancelAllJobs（reconnectClient 运行在 reconnectJob 中，取消自身会导致 connect 抛 CancellationException）。
     */
    private suspend fun reconnectClient(configInput: McpServerConfig) = withContext(Dispatchers.IO) {
        getServerLock(configInput.id).withLock {
            val config = ensureFreshToken(configInput)
            closeClient(config.id)
            val success = createAndConnect(config)
            if (!success) {
                val currentStatus = _status.value[config.id]
                if (currentStatus is McpStatus.NeedsAuthorization) {
                    // 需要授权，停止重连，等待用户操作
                    cancelAllJobs(config.id)
                    return@withLock
                }
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
                Log.w(TAG, "[$name] Failed to close MCP client: ${e.message}")
                logMcp(name, "Failed to close client: ${e.message}")
            }
        }
        clients.remove(serverId)
        connectedConfigs.remove(serverId)
    }

    private fun cancelAllJobs(serverId: Uuid) {
        reconnectJobs[serverId]?.cancel()
        reconnectJobs.remove(serverId)
        dormantJobs[serverId]?.cancel()
        dormantJobs.remove(serverId)
        authorizationJobs[serverId]?.cancel()
        authorizationJobs.remove(serverId)
    }

    @VisibleForTesting
    internal fun isConnectionError(e: Throwable): Boolean {
        // 401 是授权错误，不是连接错误
        if (looksUnauthorized(e)) return false
        return e is java.io.IOException
            || e is StreamableHttpError
            || e.message?.contains("connection", ignoreCase = true) == true
            || e.message?.contains("timeout", ignoreCase = true) == true
            || e.message?.contains("closed", ignoreCase = true) == true
    }

    private fun setStatus(serverId: Uuid, status: McpStatus) {
        _status.update { it + (serverId to status) }
    }

    fun getStatus(serverId: Uuid): Flow<McpStatus> =
        _status.map { it[serverId] ?: McpStatus.Idle }.distinctUntilChanged()

    // =====================================================================
    // OAuth 2.1 授权 (MCP 规范 2025-11-25)
    // =====================================================================

    /**
     * 发起 OAuth 授权流程：发现元数据 -> 动态注册 -> 浏览器授权 -> 交换令牌 -> 重新连接。
     * 通过 [Context] 打开 Custom Tab，用户完成后经 deep link 回调继续。
     */
    fun startAuthorization(config: McpServerConfig, context: Context) {
        // 若已有进行中的授权，先取消，避免并发的挂起协程互相覆盖状态
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            setStatus(config.id, McpStatus.Authorizing)
            runCatching { authorizeInternal(config, context.applicationContext) }
                .onFailure {
                    // 用户主动取消：状态由 cancelAuthorization 负责回退，这里不覆盖
                    if (it is CancellationException) return@onFailure
                    logMcp(config.commonOptions.name, "OAuth authorization failed: ${it.message}")
                    // 授权失败回到 NeedsAuthorization，让用户可以重试或调整配置
                    setStatus(config.id, McpStatus.NeedsAuthorization)
                }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    /** 取消进行中的 OAuth 授权（用户中止），并回退到需要授权状态。 */
    fun cancelAuthorization(config: McpServerConfig) {
        authorizationJobs.remove(config.id)?.cancel()
        appScope.launch { setStatus(config.id, McpStatus.NeedsAuthorization) }
    }

    private suspend fun authorizeInternal(config: McpServerConfig, context: Context) =
        withContext(Dispatchers.IO) {
            val serverUrl = config.serverUrl
            require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }

            // 1. 发现受保护资源 & 授权服务器元数据
            val prm = oauthClient.discoverProtectedResource(serverUrl)
            val issuer = prm.authorizationServers.firstOrNull()
                ?: error("受保护资源未声明授权服务器")
            val asMeta = oauthClient.discoverAuthorizationServer(issuer)
            val authEndpoint = asMeta.authorizationEndpoint
                ?: error("授权服务器缺少 authorization_endpoint")
            val tokenEndpoint = asMeta.tokenEndpoint
                ?: error("授权服务器缺少 token_endpoint")

            // 2. 计算 scope
            val scope = config.commonOptions.oauth?.scope
                ?: prm.scopesSupported?.joinToString(" ")
                ?: asMeta.scopesSupported?.joinToString(" ")

            // 3. 客户端注册 (复用已注册的 client_id)
            val existing = config.commonOptions.oauth
            var clientId = existing?.clientId
            var clientSecret = existing?.clientSecret
            if (clientId.isNullOrBlank()) {
                val regEndpoint = asMeta.registrationEndpoint
                    ?: error("此 MCP 服务器需要 OAuth 授权，但不支持动态客户端注册 (DCR)。请在服务器设置的 OAuth 配置中手动填入 Client ID（从授权服务器注册应用获取）。")
                val reg = oauthClient.registerClient(
                    registrationEndpoint = regEndpoint,
                    clientName = config.commonOptions.name,
                    redirectUri = MCP_OAUTH_REDIRECT_URI,
                    scope = scope,
                )
                clientId = reg.clientId
                clientSecret = reg.clientSecret
            }

            // 4. PKCE + state；持久化中间状态(端点/clientId)以便后续刷新
            val pkce = oauthClient.generatePkce()
            val state = oauthClient.generateState()
            val resource = McpOAuthClient.canonicalResource(serverUrl)

            persistOAuthState(
                config.id,
                (existing ?: McpOAuthState()).copy(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = asMeta.registrationEndpoint,
                    scope = scope,
                )
            )

            // 5. 打开浏览器授权
            val authUrl = oauthClient.buildAuthorizationUrl(
                authorizationEndpoint = authEndpoint,
                clientId = clientId,
                redirectUri = MCP_OAUTH_REDIRECT_URI,
                pkce = pkce,
                state = state,
                scope = scope,
                resource = resource,
            )
            // 6. 先建立回调订阅，再打开浏览器，避免快速回调在订阅生效前 emit 而丢失
            //    (AppEventBus 的 SharedFlow replay=0，无订阅者时的事件不会补发)
            val callback = coroutineScope {
                val subscribed = CompletableDeferred<Unit>()
                val awaitCallback = async {
                    withTimeoutOrNull(OAUTH_CALLBACK_TIMEOUT) {
                        appEventBus.events
                            .onSubscription { subscribed.complete(Unit) }
                            .filterIsInstance<AppEvent.McpOAuthCallback>()
                            .first { it.state == state }
                    }
                }
                subscribed.await() // 确保订阅已注册
                withContext(Dispatchers.Main) { launchOAuthAuthorization(context, authUrl) }
                awaitCallback.await()
            } ?: error("OAuth 授权超时")
            if (callback.error != null) error("授权失败: ${callback.error}")
            val code = callback.code ?: error("授权失败: 未返回授权码")

            // 7. 用授权码换取令牌
            // RFC 8707: 授权码在首次交换时即被消费，无论成功失败，因此不能重试
            val token = oauthClient.exchangeCode(
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                clientSecret = clientSecret,
                code = code,
                codeVerifier = pkce.verifier,
                redirectUri = MCP_OAUTH_REDIRECT_URI,
                resource = resource,
            )
            val accessToken = token.accessToken
                ?: error("Token exchange failed: response missing access_token")

            // 8. 持久化令牌
            persistOAuthState(
                config.id,
                McpOAuthState(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = asMeta.registrationEndpoint,
                    scope = token.scope ?: scope,
                    accessToken = accessToken,
                    refreshToken = token.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                )
            )

            // 9. 使用最新配置重新连接
            // 先从 authorizationJobs 移除自己，防止 addClient 内部的 cancelAllJobs 取消当前 job
            authorizationJobs.remove(config.id)
            val freshConfig = settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
                ?: config
            addClient(freshConfig)
        }

    /** 重试连接某个服务器（从 Error 状态恢复）。 */
    suspend fun retryConnect(config: McpServerConfig) {
        addClient(config)
    }

    /** 清除某个 Server 的 OAuth 授权令牌（登出），并断开当前连接使其失效。
     *  保留 clientId/clientSecret/端点等注册信息，便于用户重新授权时无需重新输入。 */
    suspend fun clearAuthorization(config: McpServerConfig) {
        // 仅清除令牌，保留注册信息
        val existing = config.commonOptions.oauth
        if (existing != null) {
            persistOAuthState(
                config.id,
                existing.copy(
                    accessToken = null,
                    refreshToken = null,
                    expiresAt = 0L,
                )
            )
        }
        // 断开当前连接（仍持有旧 token），直接设为 NeedsAuthorization
        // 不走 addClient 重连流程，避免 Connecting → Error → NeedsAuthorization 的中间状态
        cancelAllJobs(config.id)
        closeClient(config.id)
        setStatus(config.id, McpStatus.NeedsAuthorization)
    }

    /** 预配置 OAuth Client ID/Secret（用于不支持 DCR 的服务器，如 GitHub）。返回更新后的 config。 */
    suspend fun setOAuthClientCredentials(config: McpServerConfig, clientId: String, clientSecret: String?): McpServerConfig {
        val existing = config.commonOptions.oauth ?: McpOAuthState()
        val updated = existing.copy(clientId = clientId, clientSecret = clientSecret)
        persistOAuthState(config.id, updated)
        return config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
    }

    /** 若令牌即将过期且存在 refresh_token，则提前刷新并持久化，返回更新后的配置。 */
    private suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        // Re-read from settingsStore to avoid overwriting concurrent user config changes.
        // If the user modified URL/headers while a coroutine was queued, we must use the latest config.
        val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
            ?: configInput
        val oauth = config.commonOptions.oauth ?: return config
        if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return config
        val expired = oauth.expiresAt > 0 &&
            System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
        val needsRefresh = oauth.accessToken.isNullOrBlank() || expired
        if (!needsRefresh) return config

        val tokenEndpoint = oauth.tokenEndpoint ?: return config
        val clientId = oauth.clientId ?: return config
        return runCatching {
            val resource = McpOAuthClient.canonicalResource(config.serverUrl)
            val token = oauthClient.refreshToken(
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                clientSecret = oauth.clientSecret,
                refreshToken = oauth.refreshToken,
                resource = resource,
                scope = oauth.scope,
            )
            val newAccessToken = token.accessToken
                ?: return@runCatching config // postToken 保证非空，防御性兜底
            val updated = oauth.copy(
                accessToken = newAccessToken,
                refreshToken = token.refreshToken ?: oauth.refreshToken,
                expiresAt = computeExpiry(token.expiresIn),
                scope = token.scope ?: oauth.scope,
            )
            persistOAuthState(config.id, updated)
            config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
        }.getOrElse {
            logMcp(config.commonOptions.name, "Token refresh failed: ${it.message}")
            config // 刷新失败仍用旧令牌尝试，失败会转为 NeedsAuthorization
        }
    }

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    /**
     * 判断某次连接/同步失败是否应引导用户进行 OAuth 授权。
     *
     * 仅靠错误文本匹配 401/invalid_token 并不可靠：很多 MCP server 依赖用户手动填写
     * Authorization header，缺失时同样返回 401。因此在文本预筛之上进一步区分：
     * - 已开启 OAuth（此前授权过、令牌失效）→ 直接引导重新授权
     * - 用户手动配置了 Authorization header → 视为普通错误，尊重手动登录模式
     * - 其余情况 → 主动探测该 server 是否发布受保护资源元数据 (RFC 9728)，
     *   能发现才认为其支持 OAuth、需要授权
     */
    private suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!looksUnauthorized(error)) return false
        // 已开启 OAuth：令牌失效，直接引导重新授权
        if (config.commonOptions.oauth?.enabled == true) return true
        // 用户手动配置了 Authorization header：属于手动登录模式，header 无效是用户配置问题
        val hasManualAuth = config.commonOptions.headers.any {
            it.first.equals("Authorization", ignoreCase = true)
        }
        if (hasManualAuth) return false
        // 主动探测：仅当 server 发布了受保护资源元数据 (protected resource metadata) 时才支持 OAuth
        return runCatching { oauthClient.discoverProtectedResource(config.serverUrl) }
            .onFailure { logMcp(config.commonOptions.name, "OAuth probe failed: ${it.message}") }
            .isSuccess
    }

    /** 从异常链中提取 HTTP 返回码（如果存在 StreamableHttpError）。 */
    private fun extractHttpCode(error: Throwable): String {
        val httpError = generateSequence(error) { it.cause }
            .filterIsInstance<StreamableHttpError>()
            .firstOrNull()
        return httpError?.code?.let { "HTTP $it" } ?: ""
    }

    /** 错误是否疑似未授权（HTTP 401 或 RFC 6750 定义的 OAuth token 错误）。 */
    private fun looksUnauthorized(error: Throwable): Boolean {
        // 1. 最可靠：检查 StreamableHttpError.code 是否为 401
        val httpError = generateSequence(error) { it.cause }
            .filterIsInstance<StreamableHttpError>()
            .firstOrNull()
        if (httpError?.code == 401) return true

        // 2. 文本模式匹配（覆盖各种服务器返回的错误描述）
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("invalid_token") ||
            message.contains("invalid access token") ||
            message.contains("missing or invalid") ||
            message.contains("missing required authorization")
    }

    /**
     * StreamableHttpClientTransport 会额外开一条 GET SSE 长连接，部分 server 不支持或主动关闭。
     * SDK 内部重试耗尽后 emit "Maximum reconnection attempts exceeded"，此时 POST 通道仍健康，
     * 旧逻辑据此重建整个客户端，形成无限重连循环 (#1294)。
     */
    private fun isSseStreamGiveUpError(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return message.contains("Maximum reconnection attempts exceeded", ignoreCase = true)
    }
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
