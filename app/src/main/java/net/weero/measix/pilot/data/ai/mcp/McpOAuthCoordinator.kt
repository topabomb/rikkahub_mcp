package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * 浏览器授权期间对 loopback 回调 socket 的保活平台端口。
 *
 * 与 [McpRuntimeCoordinator.ForegroundObserver] 同模式：data 层只依赖本抽象，Android 前台服务
 * 实现在 application 层（service 包）注入，避免基础设施反向定位 application service。
 */
fun interface OAuthCallbackKeepAliveLease : AutoCloseable {
    override fun close()
}

interface OAuthCallbackKeepAlive {
    /** 每次授权取得独立 lease；只有最后一个 lease 释放时平台 service 才能停止。 */
    fun acquire(context: Context): OAuthCallbackKeepAliveLease
}

/**
 * JVM 测试与未注入环境的空实现：保活是 best-effort 平台增强，缺失只影响后台进程存活窗口，
 * 不改变授权语义；生产由 DI 显式注入 service 包实现。
 */
object NoOpOAuthCallbackKeepAlive : OAuthCallbackKeepAlive {
    override fun acquire(context: Context) = OAuthCallbackKeepAliveLease {}
}

/**
 * Owns MCP OAuth protocol orchestration and OAuth Settings commits.
 *
 * Connection state, authorization jobs and reconnect decisions remain owned by the per-server
 * runtime. Token writes are guarded by the complete remote trust boundary and OAuth revision
 * captured before remote I/O, so a stale response cannot attach credentials to a changed server.
 */
internal class McpOAuthCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val oauthClient: McpOAuthClient,
    private val oauthCallbackKeepAlive: OAuthCallbackKeepAlive,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (serverName: String, message: String) -> Unit,
) {
    companion object {
        const val IO_TIMEOUT_MS = 15_000L
        private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
        private val CALLBACK_TIMEOUT = 5.minutes
    }

    private val refreshFlightsMutex = Mutex()
    private val refreshFlights = mutableMapOf<McpOAuthRefreshLeaseKey, Deferred<McpServerConfig>>()

    /**
     * Returns the latest effective definition, refreshing an expired token when necessary.
     * Concurrent callers sharing the same resource and OAuth revision join one AppScope-owned
     * refresh; cancellation of a waiter never cancels that shared refresh.
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        val config = currentConfig(configInput.id) ?: configInput
        val oauth = config.commonOptions.oauth ?: return config
        if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return config
        val expired = oauth.expiresAt > 0 &&
            System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
        if (!oauth.accessToken.isNullOrBlank() && !expired) return config

        val trustBoundary = config.oauthTrustBoundary()
        val leaseKey = McpOAuthRefreshLeaseKey(config.id, trustBoundary, oauth.revision)
        val flight = refreshFlightsMutex.withLock {
            refreshFlights[leaseKey] ?: appScope.async(ioDispatcher) {
                withTimeout(IO_TIMEOUT_MS) { refreshToken(config, oauth, trustBoundary) }
            }.also { created ->
                refreshFlights[leaseKey] = created
                created.invokeOnCompletion {
                    appScope.launch {
                        refreshFlightsMutex.withLock {
                            refreshFlights.remove(leaseKey, created)
                        }
                    }
                }
            }
        }
        return try {
            flight.await()
        } finally {
            if (flight.isCompleted) {
                refreshFlightsMutex.withLock {
                    refreshFlights.remove(leaseKey, flight)
                }
            }
        }
    }

    suspend fun setClientCredentials(
        serverId: Uuid,
        clientId: String,
        clientSecret: String?,
    ) {
        mutateState(serverId) { existing ->
            (existing ?: McpOAuthState()).copy(clientId = clientId, clientSecret = clientSecret)
        }
    }

    suspend fun clearAuthorization(serverId: Uuid) {
        mutateState(serverId) { existing ->
            existing?.copy(
                accessToken = null,
                refreshToken = null,
                expiresAt = 0L,
            )
        }
    }

    /** Advances the OAuth revision without changing values, sealing a cancelled browser operation. */
    suspend fun touchState(serverId: Uuid) {
        mutateState(serverId) { it }
    }

    /**
     * Completes the interactive OAuth protocol and persists its result. The caller owns the
     * authorization Job/status and decides whether a successful authorization reconnects.
     */
    suspend fun authorize(config: McpServerConfig, context: Context) = withContext(ioDispatcher) {
        val serverUrl = config.serverUrl
        require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }
        val expectedTrustBoundary = config.oauthTrustBoundary()
        val expectedResource = expectedTrustBoundary.canonicalResource

        val protectedResource = withTimeout(IO_TIMEOUT_MS) {
            oauthClient.discoverProtectedResource(serverUrl)
        }
        val issuer = protectedResource.authorizationServers.firstOrNull()
            ?: error("受保护资源未声明授权服务器")
        val authorizationServer = withTimeout(IO_TIMEOUT_MS) {
            oauthClient.discoverAuthorizationServer(issuer)
        }
        val authorizationEndpoint = authorizationServer.authorizationEndpoint
            ?: error("授权服务器缺少 authorization_endpoint")
        val tokenEndpoint = authorizationServer.tokenEndpoint
            ?: error("授权服务器缺少 token_endpoint")
        val scope = config.commonOptions.oauth?.scope
            ?: protectedResource.scopesSupported?.joinToString(" ")
            ?: authorizationServer.scopesSupported?.joinToString(" ")

        // loopback 回调必须先于 DCR/授权 URL 绑定：redirect_uri 需要真实端口。
        // 端口可变、完整 path 固定，符合 RFC 8252 的 loopback 端口例外；服务端若错误要求固定端口，
        // DCR 会直接失败并返回明确互操作错误，不回退自定义 scheme。
        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val callbackServer = McpOAuthCallbackServer()
        callbackServer.start(expectedState = state)
        val redirectUri = callbackServer.redirectUri
        var keepAliveLease: OAuthCallbackKeepAliveLease? = null
        try {
            val existing = config.commonOptions.oauth
            var clientId = existing?.clientId
            var clientSecret = existing?.clientSecret
            if (clientId.isNullOrBlank()) {
                val registrationEndpoint = authorizationServer.registrationEndpoint
                    ?: error("此 MCP 服务器需要 OAuth 授权，但不支持动态客户端注册 (DCR)。请在服务器设置的 OAuth 配置中手动填入 Client ID（从授权服务器注册应用获取）。")
                val registration = withTimeout(IO_TIMEOUT_MS) {
                    oauthClient.registerClient(
                        registrationEndpoint = registrationEndpoint,
                        clientName = config.commonOptions.name,
                        redirectUri = redirectUri,
                        scope = scope,
                    )
                }
                clientId = registration.clientId
                clientSecret = registration.clientSecret
            }

            if (!persistStateFor(
                    expectedTrustBoundary = expectedTrustBoundary,
                    serverId = config.id,
                    expectedRevision = existing?.revision ?: 0L,
                    oauth = (existing ?: McpOAuthState()).copy(
                        enabled = true,
                        clientId = clientId,
                        clientSecret = clientSecret,
                        authorizationEndpoint = authorizationEndpoint,
                        tokenEndpoint = tokenEndpoint,
                        registrationEndpoint = authorizationServer.registrationEndpoint,
                        scope = scope,
                    ),
                )
            ) {
                error("Server trust boundary changed during authorization")
            }

            val authorizationUrl = oauthClient.buildAuthorizationUrl(
                authorizationEndpoint = authorizationEndpoint,
                clientId = clientId,
                redirectUri = redirectUri,
                pkce = pkce,
                state = state,
                scope = scope,
                resource = expectedResource,
            )
            // 浏览器授权期间保活 loopback socket：避免进程被回收导致回调丢失。
            // 服务不保存 token/配置/阶段，授权结果仍只经 callbackServer 到达。
            keepAliveLease = oauthCallbackKeepAlive.acquire(context.applicationContext)
            withContext(Dispatchers.Main) {
                launchOAuthAuthorization(context.applicationContext, authorizationUrl)
            }
            val callback = callbackServer.awaitCallback(CALLBACK_TIMEOUT)
                ?: error("OAuth 授权超时")
            if (callback.state != state) error("OAuth 授权失败: state 不匹配")
            if (callback.error != null) error("授权失败: ${callback.error}")
            val code = callback.code ?: error("授权失败: 未返回授权码")

            // RFC 8707 authorization codes are consumed by the first exchange and are not retried.
            val token = withTimeout(IO_TIMEOUT_MS) {
                oauthClient.exchangeCode(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    code = code,
                    codeVerifier = pkce.verifier,
                    redirectUri = redirectUri,
                    resource = expectedResource,
                )
            }
            val accessToken = token.accessToken
                ?: error("Token exchange failed: response missing access_token")
            if (!persistStateFor(
                    expectedTrustBoundary = expectedTrustBoundary,
                    serverId = config.id,
                    expectedRevision = (existing?.revision ?: 0L) + 1L,
                    oauth = McpOAuthState(
                        enabled = true,
                        clientId = clientId,
                        clientSecret = clientSecret,
                        authorizationEndpoint = authorizationEndpoint,
                        tokenEndpoint = tokenEndpoint,
                        registrationEndpoint = authorizationServer.registrationEndpoint,
                        scope = token.scope ?: scope,
                        accessToken = accessToken,
                        refreshToken = token.refreshToken,
                        expiresAt = computeExpiry(token.expiresIn),
                    ),
                )
            ) {
                error("Server trust boundary changed during authorization")
            }
        } finally {
            callbackServer.close()
            keepAliveLease?.close()
        }
    }

    private suspend fun refreshToken(
        config: McpServerConfig,
        oauth: McpOAuthState,
        trustBoundary: McpOAuthTrustBoundary,
    ): McpServerConfig {
        val tokenEndpoint = oauth.tokenEndpoint
            ?: error("OAuth refresh metadata is missing token_endpoint")
        val clientId = oauth.clientId ?: error("OAuth refresh metadata is missing client_id")
        return try {
            val token = oauthClient.refreshToken(
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                clientSecret = oauth.clientSecret,
                refreshToken = requireNotNull(oauth.refreshToken),
                resource = trustBoundary.canonicalResource,
                scope = oauth.scope,
            )
            val accessToken = token.accessToken
                ?: error("OAuth refresh response is missing access_token")
            val updated = oauth.copy(
                accessToken = accessToken,
                refreshToken = token.refreshToken ?: oauth.refreshToken,
                expiresAt = computeExpiry(token.expiresIn),
                scope = token.scope ?: oauth.scope,
            )
            if (!persistStateFor(trustBoundary, config.id, oauth.revision, updated)) {
                logger(config.commonOptions.name, "Token refresh discarded: OAuth lease changed")
                return currentConfig(config.id) ?: config
            }
            currentConfig(config.id)
                ?: config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger(config.commonOptions.name, "Token refresh failed: ${error.message}")
            throw error
        }
    }

    private suspend fun mutateState(
        serverId: Uuid,
        transform: (McpOAuthState?) -> McpOAuthState?,
    ) {
        settingsStore.updateLocal { old ->
            val current = old.mcpServers.firstOrNull { it.id == serverId }?.commonOptions?.oauth
            val next = transform(current)?.copy(revision = (current?.revision ?: 0L) + 1L)
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != serverId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = next))
                }
            )
        }
    }

    @VisibleForTesting
    internal suspend fun persistStateFor(
        expectedTrustBoundary: McpOAuthTrustBoundary,
        serverId: Uuid,
        expectedRevision: Long,
        oauth: McpOAuthState?,
    ): Boolean {
        var applied = false
        settingsStore.updateLocal { old ->
            val server = old.mcpServers.find { it.id == serverId } ?: return@updateLocal old
            if (server.oauthTrustBoundary() != expectedTrustBoundary) {
                return@updateLocal old
            }
            val currentRevision = server.commonOptions.oauth?.revision ?: 0L
            if (currentRevision != expectedRevision) return@updateLocal old
            applied = true
            val next = oauth?.copy(revision = expectedRevision + 1L)
            old.copy(
                mcpServers = old.mcpServers.map { current ->
                    if (current.id != serverId) current
                    else current.clone(commonOptions = current.commonOptions.copy(oauth = next))
                }
            )
        }
        return applied
    }

    private fun currentConfig(serverId: Uuid): McpServerConfig? =
        settingsStore.effectiveSettings.value.settings.mcpServers.find { it.id == serverId }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }
}

private data class McpOAuthRefreshLeaseKey(
    val serverId: Uuid,
    val trustBoundary: McpOAuthTrustBoundary,
    val revision: Long,
)
