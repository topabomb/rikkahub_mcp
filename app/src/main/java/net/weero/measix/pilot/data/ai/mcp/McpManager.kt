package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import androidx.annotation.VisibleForTesting
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.HttpClient
import me.rerere.common.android.Logging
import net.weero.measix.pilot.data.ai.RequestLoggingInterceptor
import net.weero.measix.pilot.data.ai.attachments.ImageMime
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"
private const val MAX_MCP_IMAGE_BASE64_CHARS = (GeneratedMediaStore.MAX_IMAGE_BYTES * 4 / 3) + 4

/** Android 前台信号 adapter，使 [McpManager] 的生命周期订阅可在 JVM 测试中替换。 */
fun interface ForegroundObserver {
    fun onForegroundStarted(action: () -> Unit)
}

private val ProcessLifecycleForegroundObserver = ForegroundObserver { action ->
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            action()
        }
    })
}

/**
 * MCP 服务器连接管理器。
 *
 * 每个 server 只有一个 [ConnectionSlot]。有效配置、前台恢复、网络恢复和手动刷新
 * 都只触发同一个 revision-aware [reconcile]；slot 内所有状态迁移都持 [ConnectionSlot.mutex]
 * 并按 generation 拒绝过期 job 与旧 transport 回调。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val artifactStore: ArtifactStore,
    private val networkMonitor: NetworkMonitor,
    private val appEventBus: AppEventBus,
    private val foregroundObserver: ForegroundObserver = ProcessLifecycleForegroundObserver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transportOverride: ((McpServerConfig) -> AbstractTransport)? = null,
    private val clientOverride: ((McpServerConfig) -> Client)? = null,
    oauthClientOverride: McpOAuthClient? = null,
) {
    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val BASE_RECONNECT_DELAY_MS = 1000L
        const val MAX_RECONNECT_DELAY_MS = 30000L
        const val DORMANT_RETRY_INTERVAL_MS = 60_000L
        const val DORMANT_MAX_RETRIES = 30
        const val OFFLINE_CHECK_INTERVAL_MS = 10_000L
    }

    private val TOKEN_REFRESH_LEEWAY_MS = 60_000L
    private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .addNetworkInterceptor(RequestLoggingInterceptor())
        .build()

    private val oauthClient = oauthClientOverride ?: McpOAuthClient(okHttpClient)

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

    private val slots = ConcurrentHashMap<Uuid, ConnectionSlot>()
    private val _status = MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap())
    val syncingStatus: StateFlow<Map<Uuid, McpStatus>> = _status

    private fun logMcp(serverName: String, message: String) {
        Log.i(TAG, "[$serverName] $message")
        Logging.log("MCP", "[$serverName] $message")
    }

    init {
        // 链 1: 有效配置变化 → reconcile（create/update/remove 全部由 slot 重新校验当前配置）
        appScope.launch {
            settingsStore.effectiveSettings
                .map { snapshot -> snapshot.revision to snapshot.settings.mcpServers }
                .distinctUntilChanged()
                .collect { (revision, configs) ->
                    appScope.launch { reconcile(revision, configs, refreshTools = false) }
                }
        }

        // 链 2: 前台恢复 → refreshConnections（OS 可能在后台静默断开 SSE/HTTP）
        foregroundObserver.onForegroundStarted {
            if (slots.isNotEmpty()) {
                appScope.launch { refreshConnections() }
            }
        }

        // 链 3: 网络恢复 → refreshConnections（比 transport.onClose 快 10-30s）
        networkMonitor.onNetworkAvailable = {
            appScope.launch { refreshConnections() }
        }
    }

    fun getClient(serverId: Uuid): Client? = slots[serverId]?.client

    fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.effectiveSettings.value.settings
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { it.enable }
                    .map { Triple(server.id, server.commonOptions.name, it) }
            }
    }

    suspend fun callTool(
        serverId: Uuid,
        toolName: String,
        args: JsonObject,
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): List<UIMessagePart> {
        val slot = slots[serverId] ?: return listOf(UIMessagePart.Text("MCP server not connected"))
        return slot.mutex.withLock {
            var client = slot.client
                ?: return@withLock listOf(UIMessagePart.Text("MCP server not connected"))
            var config = settingsStore.effectiveSettings.value.settings.mcpServers.find { it.id == serverId }
                ?: return@withLock listOf(UIMessagePart.Text("MCP server config not found"))

            val freshConfig = ensureFreshToken(config)
            if (slot.fingerprint != freshConfig.connectionFingerprint()) {
                logMcp(config.commonOptions.name, "Connection parameters changed during callTool, reconnecting")
                slot.replaceClientLocked(freshConfig)
                client = slot.client
                    ?: return@withLock listOf(UIMessagePart.Text("MCP server not connected after reconnection"))
                config = freshConfig
            }

            if (client.transport == null) {
                slot.setStatusLocked(McpStatus.Reconnecting(1, MAX_RECONNECT_ATTEMPTS))
                slot.scheduleReconnectLocked(slot.currentGeneration())
                return@withLock listOf(UIMessagePart.Text("MCP server not connected, reconnecting"))
            }

            val serverName = config.commonOptions.name
            val createdArtifacts = mutableListOf<OwnedArtifact>()
            runCatching {
                val result = client.callTool(
                    CallToolRequest(CallToolRequestParams(name = toolName, arguments = args)),
                    RequestOptions(timeout = 120.seconds)
                )
                result.content.map {
                    when (it) {
                        is TextContent -> UIMessagePart.Text(it.text)
                        is ImageContent -> convertImageContentToFilePart(it, createdArtifacts::add)
                        else -> UIMessagePart.Text(JsonInstant.encodeToString(it))
                    }
                }.also { logMcp(serverName, "Tool '$toolName' succeeded") }
            }.onSuccess {
                createdArtifacts.forEach(onArtifactCreated)
            }.getOrElse { e ->
                discardCreatedArtifacts(createdArtifacts, "MCP tool result rollback", e)
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
                    slot.setStatusLocked(McpStatus.NeedsAuthorization)
                    return@withLock listOf(UIMessagePart.Text("MCP server requires authorization. Please re-authorize in MCP settings."))
                }
                // 4. 连接错误: 触发重连 + 返回错误文本
                if (isConnectionError(e)) {
                    val code = extractHttpCode(e)
                    logMcp(serverName, "Tool '$toolName' connection error: ${e.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                    slot.setStatusLocked(McpStatus.Reconnecting(1, MAX_RECONNECT_ATTEMPTS))
                    slot.scheduleReconnectLocked(slot.currentGeneration())
                    return@withLock listOf(UIMessagePart.Text("MCP tool '$toolName' failed: connection error (${e.message})"))
                }
                // 5. 其他错误（McpException / 服务器返回错误等）: 返回错误文本，不重连
                val code = extractHttpCode(e)
                logMcp(serverName, "Tool '$toolName' failed: ${e.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                listOf(UIMessagePart.Text("MCP tool '$toolName' failed: ${e.message}"))
            }
        }
    }

    private suspend fun convertImageContentToFilePart(
        image: ImageContent,
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): UIMessagePart.Image {
        require(image.data.isNotEmpty() && image.data.length <= MAX_MCP_IMAGE_BASE64_CHARS) {
            "MCP image payload exceeds the size limit"
        }
        val bytes = Base64.decode(image.data)
        require(bytes.size <= GeneratedMediaStore.MAX_IMAGE_BYTES) { "MCP image payload exceeds the size limit" }
        require(ImageMime.isAcceptedImage(bytes)) { "MCP image payload is invalid" }
        val detectedMime = requireNotNull(ImageMime.sniff(bytes)) { "MCP image MIME cannot be detected" }
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(detectedMime) ?: "bin"
        val owned = artifactStore.createFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$ext",
            mimeType = detectedMime,
            origin = ArtifactOrigin.SYSTEM,
        )
        return try {
            net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.ensureAttachmentRef(
                UIMessagePart.Image(url = owned.uri.toString()),
            ).also { onArtifactCreated(owned) } as UIMessagePart.Image
        } catch (error: Throwable) {
            discardCreatedArtifacts(listOf(owned), "MCP image projection rollback", error)
            throw error
        }
    }

    private suspend fun discardCreatedArtifacts(
        artifacts: List<OwnedArtifact>,
        operation: String,
        primary: Throwable,
    ) = withContext(NonCancellable) {
        artifacts.asReversed().forEach { owned ->
            try {
                artifactStore.discardUnpublished(owned).requireDiscarded(operation)
            } catch (cleanupFailure: Throwable) {
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun transportFor(config: McpServerConfig): AbstractTransport =
        transportOverride?.invoke(config) ?: defaultTransport(config)

    private fun clientFor(config: McpServerConfig): Client =
        clientOverride?.invoke(config) ?: Client(
            clientInfo = Implementation(name = config.commonOptions.name, version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )

    private fun defaultTransport(config: McpServerConfig): AbstractTransport {
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

    /** 合并用户自定义请求头与 OAuth Bearer 令牌。 */
    private fun McpServerConfig.resolveHeaders(): List<Pair<String, String>> =
        connectionFingerprint().headers

    /** 用户/生命周期触发的唯一同步入口：只调用同一个 reconcile，不建立第二条路径。 */
    suspend fun refreshConnections() = withContext(ioDispatcher) {
        val snapshot = settingsStore.effectiveSettings.value
        reconcile(snapshot.revision, snapshot.settings.mcpServers, refreshTools = true)
    }

    private suspend fun reconcile(
        revision: Long,
        desiredConfigs: List<McpServerConfig>,
        refreshTools: Boolean,
    ) = coroutineScope {
        val desired = desiredConfigs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
        val desiredIds = desired.map { it.id }.toSet()
        // remove 分支不信任快照顺序：slot 在锁内重读当前配置后才拆除，旧的 reconcile
        // 无法拆掉新 revision 刚建立的连接。
        slots.keys.filter { it !in desiredIds }.forEach { id ->
            launch { slot(id).removeIfStillAbsent() }
        }
        desired.forEach { config ->
            launch { slot(config.id).reconcile(refreshTools) }
        }
    }

    private fun slot(serverId: Uuid): ConnectionSlot =
        slots.getOrPut(serverId) { ConnectionSlot(serverId) }

    suspend fun retryConnect(config: McpServerConfig) {
        slot(config.id).reconcile(refreshTools = true, forceReconnect = true)
    }

    @VisibleForTesting
    internal fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
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

    fun getStatus(serverId: Uuid): Flow<McpStatus> =
        _status.map { it[serverId] ?: McpStatus.Idle }.distinctUntilChanged()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        slot(config.id).startAuthorization(config, context.applicationContext)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        slot(config.id).cancelAuthorization()
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
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
        slot(config.id).revokeAuthorization()
    }

    suspend fun setOAuthClientCredentials(config: McpServerConfig, clientId: String, clientSecret: String?): McpServerConfig {
        val existing = config.commonOptions.oauth ?: McpOAuthState()
        val updated = existing.copy(clientId = clientId, clientSecret = clientSecret)
        persistOAuthState(config.id, updated)
        return config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
    }

    private suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        // Re-read from settingsStore to avoid overwriting concurrent user config changes.
        val config = settingsStore.effectiveSettings.value.settings.mcpServers.find { it.id == configInput.id }
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
                ?: return@runCatching config
            val updated = oauth.copy(
                accessToken = newAccessToken,
                refreshToken = token.refreshToken ?: oauth.refreshToken,
                expiresAt = computeExpiry(token.expiresIn),
                scope = token.scope ?: oauth.scope,
            )
            if (!persistOAuthStateFor(resource, config.id, updated)) {
                logMcp(config.commonOptions.name, "Token refresh discarded: server URL changed")
                return@runCatching settingsStore.effectiveSettings.value.settings.mcpServers
                    .find { it.id == config.id } ?: config
            }
            settingsStore.effectiveSettings.value.settings.mcpServers
                .find { it.id == config.id } ?: config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            logMcp(config.commonOptions.name, "Token refresh failed: ${error.message}")
            config
        }
    }

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.updateLocal { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    /**
     * 授权流程期间唯一允许的 OAuth 状态写入：目标 server 的 canonical resource 必须仍等于
     * 发起授权时的值。浏览器往返期间用户修改 URL 时令牌不会写入新地址，防止把旧 resource
     * 的 bearer token 发给不同 endpoint。
     */
    @VisibleForTesting
    internal suspend fun persistOAuthStateFor(
        expectedResource: String,
        configId: Uuid,
        oauth: McpOAuthState?,
    ): Boolean {
        var applied = false
        settingsStore.updateLocal { old ->
            val server = old.mcpServers.find { it.id == configId } ?: return@updateLocal old
            if (McpOAuthClient.canonicalResource(server.serverUrl) != expectedResource) {
                return@updateLocal old
            }
            applied = true
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
        return applied
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    private suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!looksUnauthorized(error)) return false
        // 已开启 OAuth：令牌失效，直接引导重新授权
        if (config.commonOptions.oauth?.enabled == true) return true
        // 用户手动配置了 Authorization header：属于手动登录模式，header 无效是用户配置问题
        val hasManualAuth = config.commonOptions.headers.any {
            it.first.equals("Authorization", ignoreCase = true)
        }
        if (hasManualAuth) return false
        // 主动探测：仅当 server 发布了受保护资源元数据时才支持 OAuth
        return runCatching { oauthClient.discoverProtectedResource(config.serverUrl) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                logMcp(config.commonOptions.name, "OAuth probe failed: ${error.message}")
            }
            .isSuccess
    }

    private fun extractHttpCode(error: Throwable): String {
        val httpError = generateSequence(error) { it.cause }
            .filterIsInstance<StreamableHttpError>()
            .firstOrNull()
        return httpError?.code?.let { "HTTP $it" } ?: ""
    }

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
     * 重建整个客户端会形成无限重连循环 (#1294)。
     */
    private fun isSseStreamGiveUpError(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return message.contains("Maximum reconnection attempts exceeded", ignoreCase = true)
    }

    /**
     * 一个 server 的唯一生命周期容器。所有状态迁移都在 [mutex] 内执行；client 所有权每次
     * 交接都推进 generation，使旧 client 的 transport 回调与过期 job 的结果被拒绝。
     */
    private inner class ConnectionSlot(val serverId: Uuid) {
        val mutex = Mutex()
        var client: Client? = null
            private set
        var fingerprint: McpConnectionFingerprint? = null
            private set
        @Volatile
        var status: McpStatus = McpStatus.Idle
            private set
        private var reconnectJob: Job? = null
        private var dormantJob: Job? = null
        private var authorizationJob: Job? = null
        private var reconnectAttempt = 0
        private val generation = AtomicLong(0)

        fun currentGeneration(): Long = generation.get()

        private fun desiredEnabledConfig(): McpServerConfig? =
            settingsStore.effectiveSettings.value.settings.mcpServers
                .find { it.id == serverId && it.commonOptions.enable && it.commonOptions.name.isNotBlank() }

        suspend fun reconcile(
            refreshTools: Boolean,
            forceReconnect: Boolean = false,
        ) = withContext(ioDispatcher) {
            mutex.withLock {
                if (slots[serverId] !== this@ConnectionSlot) return@withLock
                val config = desiredEnabledConfig()
                if (config == null) {
                    teardownLocked()
                    return@withLock
                }
                // 授权流程进行中不被配置同步打断；需要授权的 server 只有连接参数变化时才重连
                val freshConfig = ensureFreshToken(config)
                val desiredFingerprint = freshConfig.connectionFingerprint()
                if (!forceReconnect && status == McpStatus.Authorizing) return@withLock
                if (!forceReconnect && status == McpStatus.NeedsAuthorization && fingerprint == desiredFingerprint) {
                    return@withLock
                }
                val live = client?.takeIf { it.transport != null }
                if (!forceReconnect && live != null && fingerprint == desiredFingerprint) {
                    if (refreshTools) refreshToolsLocked(freshConfig)
                    return@withLock
                }
                replaceClientLocked(freshConfig)
            }
        }

        suspend fun removeIfStillAbsent() = withContext(ioDispatcher) {
            mutex.withLock {
                if (slots[serverId] !== this@ConnectionSlot) return@withLock
                if (desiredEnabledConfig() == null) teardownLocked()
            }
        }

        suspend fun revokeAuthorization() = withContext(ioDispatcher) {
            mutex.withLock {
                generation.incrementAndGet()
                cancelAllJobsLocked()
                closeClientLocked()
                setStatusLocked(McpStatus.NeedsAuthorization)
            }
        }

        private suspend fun teardownLocked() {
            val name = settingsStore.effectiveSettings.value.settings.mcpServers
                .find { it.id == serverId }?.commonOptions?.name ?: serverId.toString()
            generation.incrementAndGet()
            cancelAllJobsLocked()
            closeClientLocked()
            reconnectAttempt = 0
            // close 挂起期间配置可能被重新加入。slot 在 map 里仍是唯一 owner，
            // 必须在这里原位接回，不能先摘掉再让排队的 reconcile 操作孤儿实例。
            val readded = desiredEnabledConfig()
            if (readded != null) {
                generation.incrementAndGet()
                connectLocked(readded)
                return
            }
            slots.remove(serverId, this@ConnectionSlot)
            _status.update { it - serverId }
            logMcp(name, "Disconnected (removed)")
        }

        /** client 所有权交接的唯一入口：先推进 generation 再关闭旧 client，旧回调立即失效。 */
        suspend fun replaceClientLocked(config: McpServerConfig) {
            generation.incrementAndGet()
            cancelRecoveryJobsLocked()
            closeClientLocked()
            connectLocked(config)
        }

        private suspend fun connectLocked(config: McpServerConfig): Boolean {
            val transport = transportFor(config)
            val newClient = clientFor(config)
            val assignedGeneration = generation.get()
            setupNotificationHandlers(newClient, config, assignedGeneration)
            transport.onClose {
                appScope.launch { onTransportClosed(assignedGeneration) }
            }
            transport.onError { error ->
                appScope.launch { onTransportError(assignedGeneration, error) }
            }

            client = newClient
            fingerprint = config.connectionFingerprint()
            setStatusLocked(McpStatus.Connecting)
            return try {
                newClient.connect(transport)
                val toolCount = syncTools(config.id)
                setStatusLocked(McpStatus.Connected)
                reconnectAttempt = 0
                logMcp(config.commonOptions.name, "Connected ($toolCount tools synced)")
                true
            } catch (cancelled: CancellationException) {
                if (client === newClient) closeClientLocked()
                throw cancelled
            } catch (it: Exception) {
                if (client === newClient) closeClientLocked()
                if (needsAuthorization(config, it)) {
                    setStatusLocked(McpStatus.NeedsAuthorization)
                    logMcp(config.commonOptions.name, "Needs OAuth authorization${extractHttpCode(it).let { c -> if (c.isNotEmpty()) " ($c)" else "" }}")
                } else {
                    setStatusLocked(McpStatus.Error.from(it))
                    val code = extractHttpCode(it)
                    logMcp(config.commonOptions.name, "Connection failed: ${it.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                }
                false
            }
        }

        private suspend fun onTransportClosed(capturedGeneration: Long) = withContext(ioDispatcher) {
            mutex.withLock {
                if (generation.get() != capturedGeneration) return@withLock
                if (status != McpStatus.Connected) return@withLock
                scheduleReconnectLocked(capturedGeneration)
            }
        }

        private suspend fun onTransportError(capturedGeneration: Long, error: Throwable) {
            if (isSseStreamGiveUpError(error)) return
            onTransportClosed(capturedGeneration)
        }

        /** 外部（重试 job、callTool 之外的路径）触发的重连入口。 */
        private suspend fun requestReconnect(capturedGeneration: Long) = withContext(ioDispatcher) {
            mutex.withLock {
                if (generation.get() != capturedGeneration) return@withLock
                scheduleReconnectLocked(capturedGeneration)
            }
        }

        private suspend fun refreshToolsLocked(config: McpServerConfig) {
            val live = client
            if (live == null || live.transport == null) {
                scheduleReconnectLocked(currentGeneration())
                return
            }
            runCatching { syncTools(config.id) }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (looksUnauthorized(it)) {
                        val code = extractHttpCode(it)
                        setStatusLocked(McpStatus.NeedsAuthorization)
                        logMcp(config.commonOptions.name, "refreshConnections detected auth error${if (code.isNotEmpty()) " ($code)" else ""}: ${it.message}")
                    } else if (isConnectionError(it)) {
                        val code = extractHttpCode(it)
                        logMcp(config.commonOptions.name, "refreshConnections detected stale connection: ${it.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                        scheduleReconnectLocked(currentGeneration())
                    } else {
                        setStatusLocked(McpStatus.Error.from(it, "syncTools failed"))
                        val code = extractHttpCode(it)
                        logMcp(config.commonOptions.name, "syncTools failed: ${it.message}${if (code.isNotEmpty()) " [$code]" else ""}")
                    }
                }
        }

        /**
         * 快速重连：指数退避（5 次），失败后转入 Dormant。网络不可用时等待恢复且不消耗次数。
         * caller 已持有 [mutex]。
         */
        fun scheduleReconnectLocked(capturedGeneration: Long) {
            val attempt = reconnectAttempt + 1
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                enterDormantLocked(capturedGeneration)
                return
            }
            reconnectAttempt = attempt
            reconnectJob?.cancel()
            val delayMs = calculateBackoffDelay(attempt)
            reconnectJob = appScope.launch {
                try {
                    if (!awaitBackoff(capturedGeneration, attempt, delayMs)) return@launch
                    val config = mutex.withLock {
                        if (generation.get() != capturedGeneration) return@withLock null
                        desiredEnabledConfig() ?: run { teardownLocked(); null }
                    } ?: return@launch
                    val retryGeneration = reconnectTransition(config, capturedGeneration)
                    if (retryGeneration != null) requestReconnect(retryGeneration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logMcp(getServerName(), "Reconnect failed: ${e.message}")
                }
            }
        }

        /** 等待退避延迟；网络离线时循环等待且不消耗 attempt。generation 失效时返回 false。 */
        private suspend fun awaitBackoff(capturedGeneration: Long, attempt: Int, delayMs: Long): Boolean {
            val serverName = getServerName()
            while (true) {
                val stillCurrent = mutex.withLock {
                    if (generation.get() != capturedGeneration) {
                        false
                    } else {
                        setStatusLocked(McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
                        true
                    }
                }
                if (!stillCurrent) return false
                if (!networkMonitor.isOnline.value) {
                    logMcp(serverName, "Network offline, waiting for connectivity...")
                    delay(OFFLINE_CHECK_INTERVAL_MS)
                    continue
                }
                logMcp(serverName, "Reconnecting (attempt $attempt/$MAX_RECONNECT_ATTEMPTS, ${delayMs}ms delay)")
                delay(delayMs)
                return true
            }
        }

        /** Dormant 长间隔兜底重试：60s × 30 次，全部失败后标记 Error。 */
        private fun enterDormantLocked(capturedGeneration: Long) {
            setStatusLocked(McpStatus.Dormant(DORMANT_RETRY_INTERVAL_MS))
            val serverName = getServerName()
            logMcp(serverName, "Entering dormant mode (${DORMANT_RETRY_INTERVAL_MS / 1000}s interval, max $DORMANT_MAX_RETRIES retries)")
            dormantJob?.cancel()
            dormantJob = appScope.launch {
                var ownedGeneration = capturedGeneration
                repeat(DORMANT_MAX_RETRIES) {
                    delay(DORMANT_RETRY_INTERVAL_MS)
                    val config = mutex.withLock {
                        if (generation.get() != ownedGeneration) return@withLock null
                        desiredEnabledConfig() ?: run { teardownLocked(); null }
                    } ?: return@launch
                    val retryGeneration = try {
                        reconnectTransition(config, ownedGeneration)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mutex.withLock {
                            if (generation.get() == ownedGeneration) {
                                setStatusLocked(McpStatus.Dormant(DORMANT_RETRY_INTERVAL_MS))
                            }
                        }
                        return@repeat
                    }
                    if (retryGeneration == null) return@launch
                    ownedGeneration = retryGeneration
                    mutex.withLock {
                        if (generation.get() == ownedGeneration) {
                            setStatusLocked(McpStatus.Dormant(DORMANT_RETRY_INTERVAL_MS))
                        }
                    }
                }
                mutex.withLock {
                    if (generation.get() == ownedGeneration) {
                        setStatusLocked(McpStatus.Error("MCP reconnect failed after $DORMANT_MAX_RETRIES dormant retries"))
                        logMcp(serverName, "All reconnection attempts exhausted (Error)")
                    }
                }
            }
        }

        /**
         * 重连也必须先推进 generation 再关闭旧 client。返回值非空表示本次仍由同一 slot
         * 拥有、需要按新 generation 继续退避；成功、需要授权或 generation 已失效时返回 null。
         */
        private suspend fun reconnectTransition(
            configInput: McpServerConfig,
            capturedGeneration: Long,
        ): Long? = withContext(ioDispatcher) {
            mutex.withLock {
                if (generation.get() != capturedGeneration) return@withLock null
                val config = ensureFreshToken(configInput)
                generation.incrementAndGet()
                closeClientLocked()
                val success = connectLocked(config)
                when {
                    success -> null
                    status is McpStatus.NeedsAuthorization -> {
                        cancelRecoveryJobsLocked()
                        null
                    }
                    else -> generation.get()
                }
            }
        }

        fun setStatusLocked(newStatus: McpStatus) {
            if (slots[serverId] !== this@ConnectionSlot) return
            status = newStatus
            // 只发布本 slot 的键，避免全表重建把其他 server 的更新回退。
            _status.update { it + (serverId to newStatus) }
        }

        fun cancelRecoveryJobsLocked() {
            reconnectJob?.cancel()
            reconnectJob = null
            dormantJob?.cancel()
            dormantJob = null
        }

        private fun cancelAllJobsLocked() {
            cancelRecoveryJobsLocked()
            authorizationJob?.cancel()
            authorizationJob = null
        }

        suspend fun closeClientLocked() {
            client?.let {
                runCatching { it.close() }.onFailure { e ->
                    if (e is CancellationException) throw e
                    val name = getServerName()
                    Log.w(TAG, "[$name] Failed to close MCP client: ${e.message}")
                    logMcp(name, "Failed to close client: ${e.message}")
                }
            }
            client = null
            fingerprint = null
        }

        private fun setupNotificationHandlers(client: Client, config: McpServerConfig, assignedGeneration: Long) {
            val configName = config.commonOptions.name
            client.setNotificationHandler<ToolListChangedNotification>(
                NotificationsToolsListChanged
            ) {
                logMcp(configName, "Received tools/list_changed notification")
                appScope.launch {
                    mutex.withLock {
                        if (generation.get() != assignedGeneration) return@withLock
                        runCatching { syncTools(config.id) }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                Log.e(TAG, "Failed to sync tools after list_changed for $configName", e)
                                logMcp(configName, "syncTools after list_changed failed: ${e.message}")
                            }
                    }
                }
                CompletableDeferred(Unit)
            }
        }

        fun startAuthorization(config: McpServerConfig, context: Context) {
            appScope.launch {
                mutex.withLock {
                    // 已有进行中的授权先取消，避免并发的挂起协程互相覆盖状态
                    authorizationJob?.cancel()
                    setStatusLocked(McpStatus.Authorizing)
                    authorizationJob = appScope.launch {
                        runCatching { authorizeInternal(config, context) }
                            .onFailure {
                                // 用户主动取消：状态由 cancelAuthorization 负责回退，同时保留 Job 取消语义。
                                if (it is CancellationException) throw it
                                logMcp(config.commonOptions.name, "OAuth authorization failed: ${it.message}")
                                mutex.withLock { setStatusLocked(McpStatus.NeedsAuthorization) }
                                // 授权期间被跳过的配置变化（如 URL 修改）在此收敛
                                appScope.launch { reconcile(refreshTools = true) }
                            }
                    }
                }
            }
        }

        fun cancelAuthorization() {
            appScope.launch {
                mutex.withLock {
                    authorizationJob?.cancel()
                    authorizationJob = null
                    setStatusLocked(McpStatus.NeedsAuthorization)
                }
            }
        }

        private suspend fun authorizeInternal(config: McpServerConfig, context: Context) =
            withContext(ioDispatcher) {
                val serverUrl = config.serverUrl
                require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }
                // 授权令牌只对发起时的 canonical resource 有效；期间 URL 被修改则拒绝写入
                val expectedResource = McpOAuthClient.canonicalResource(serverUrl)

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

                // 4. PKCE + state；持久化中间状态（端点/clientId）以便后续刷新
                val pkce = oauthClient.generatePkce()
                val state = oauthClient.generateState()

                if (!persistOAuthStateFor(
                        expectedResource,
                        config.id,
                        (existing ?: McpOAuthState()).copy(
                            enabled = true,
                            clientId = clientId,
                            clientSecret = clientSecret,
                            authorizationEndpoint = authEndpoint,
                            tokenEndpoint = tokenEndpoint,
                            registrationEndpoint = asMeta.registrationEndpoint,
                            scope = scope,
                        ),
                    )
                ) {
                    error("Server URL changed during authorization")
                }

                // 5. 打开浏览器授权
                val authUrl = oauthClient.buildAuthorizationUrl(
                    authorizationEndpoint = authEndpoint,
                    clientId = clientId,
                    redirectUri = MCP_OAUTH_REDIRECT_URI,
                    pkce = pkce,
                    state = state,
                    scope = scope,
                    resource = expectedResource,
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
                    subscribed.await()
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
                    resource = expectedResource,
                )
                val accessToken = token.accessToken
                    ?: error("Token exchange failed: response missing access_token")

                // 8. 持久化令牌（仍受 canonical resource 守卫保护）
                if (!persistOAuthStateFor(
                        expectedResource,
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
                        ),
                    )
                ) {
                    error("Server URL changed during authorization")
                }

                // 9. 使用最新配置重新连接
                reconcile(refreshTools = true, forceReconnect = true)
            }

        private fun getServerName(): String {
            return settingsStore.effectiveSettings.value.settings.mcpServers
                .find { it.id == serverId }?.commonOptions?.name ?: serverId.toString()
        }
    }

    private suspend fun syncTools(configId: Uuid): Int {
        val client = slots[configId]?.client ?: return 0
        val serverTools = client.listTools().tools

        var mergedSize = 0
        settingsStore.updateLocal { old ->
            old.copy(
                mcpServers = old.mcpServers.map { currentConfig ->
                    if (currentConfig.id != configId) {
                        currentConfig
                    } else {
                        val merged = mergeTools(serverTools, currentConfig.commonOptions.tools)
                        mergedSize = merged.size
                        currentConfig.clone(
                            commonOptions = currentConfig.commonOptions.copy(tools = merged)
                        )
                    }
                }
            )
        }
        return mergedSize
    }
}

/** 只包含会影响实际连接的字段；工具开关和 Schema 变化不会触发重连。 */
private data class McpConnectionFingerprint(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
)

private fun McpServerConfig.connectionFingerprint(): McpConnectionFingerprint = McpConnectionFingerprint(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedConnectionHeaders(),
)

private fun McpServerConfig.resolvedConnectionHeaders(): List<Pair<String, String>> {
    val base = commonOptions.headers
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}
