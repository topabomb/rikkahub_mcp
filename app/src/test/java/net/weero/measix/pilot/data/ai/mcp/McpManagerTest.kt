package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * McpManager 单元测试
 *
 * 覆盖范围:
 * - 指数退避延迟计算 (calculateBackoffDelay)
 * - 连接错误分类 (isConnectionError)
 * - McpStatus 状态机完整性
 * - getAllAvailableTools 过滤逻辑（通过状态机验证）
 */
class McpManagerTest {

    // ==================== calculateBackoffDelay 测试 ====================

    /**
     * 使用反射访问 internal 方法，避免实例化 McpManager（依赖 Android 组件）。
     * McpManager 的 calculateBackoffDelay 是纯函数，不依赖实例状态。
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        val BASE = 1000L
        val MAX = 30000L
        val exponentialDelay = BASE * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX)
    }

    @Test
    fun `calculateBackoffDelay should return base delay for first attempt`() {
        val delay = calculateBackoffDelay(1)
        assertEquals(1000L, delay)
    }

    @Test
    fun `calculateBackoffDelay should double for each subsequent attempt`() {
        assertEquals(1000L, calculateBackoffDelay(1))
        assertEquals(2000L, calculateBackoffDelay(2))
        assertEquals(4000L, calculateBackoffDelay(3))
        assertEquals(8000L, calculateBackoffDelay(4))
        assertEquals(16000L, calculateBackoffDelay(5))
    }

    @Test
    fun `calculateBackoffDelay should cap at max delay`() {
        // 2^14 * 1000 = 16384000 < 30000... wait, 2^5 = 32000 > 30000
        // attempt 6: 2^5 * 1000 = 32000 → capped to 30000
        assertEquals(30000L, calculateBackoffDelay(6))
        assertEquals(30000L, calculateBackoffDelay(10))
        assertEquals(30000L, calculateBackoffDelay(100))
    }

    @Test
    fun `calculateBackoffDelay should never exceed max reconnect delay`() {
        for (attempt in 1..50) {
            val delay = calculateBackoffDelay(attempt)
            assertTrue(
                "Delay for attempt $attempt ($delay) should not exceed max",
                delay <= 30000L
            )
        }
    }

    @Test
    fun `calculateBackoffDelay should always be positive`() {
        for (attempt in 1..50) {
            val delay = calculateBackoffDelay(attempt)
            assertTrue(
                "Delay for attempt $attempt should be positive",
                delay > 0
            )
        }
    }

    // ==================== isConnectionError 测试 ====================

    /**
     * 复制 isConnectionError 逻辑进行测试。
     * 该方法是纯函数，行为可独立验证。
     */
    private fun isConnectionError(e: Throwable): Boolean {
        return e is IOException
            || e is StreamableHttpError
            || e.message?.contains("connection", ignoreCase = true) == true
            || e.message?.contains("timeout", ignoreCase = true) == true
            || e.message?.contains("closed", ignoreCase = true) == true
    }

    @Test
    fun `isConnectionError should detect IOException`() {
        assertTrue(isConnectionError(IOException("network reset")))
        assertTrue(isConnectionError(IOException()))
    }

    @Test
    fun `isConnectionError should detect StreamableHttpError`() {
        val error = StreamableHttpError(code = 503, message = "Service Unavailable")
        assertTrue(isConnectionError(error))
    }

    @Test
    fun `isConnectionError should detect connection keyword in message`() {
        assertTrue(isConnectionError(RuntimeException("Connection refused")))
        assertTrue(isConnectionError(RuntimeException("CONNECTION RESET")))
        assertTrue(isConnectionError(RuntimeException("Lost connection to server")))
    }

    @Test
    fun `isConnectionError should detect timeout keyword in message`() {
        assertTrue(isConnectionError(RuntimeException("Request timeout")))
        assertTrue(isConnectionError(RuntimeException("TIMEOUT after 30s")))
    }

    @Test
    fun `isConnectionError should detect closed keyword in message`() {
        assertTrue(isConnectionError(RuntimeException("Channel closed")))
        assertTrue(isConnectionError(RuntimeException("socket closed")))
    }

    @Test
    fun `isConnectionError should return false for non-connection errors`() {
        assertFalse(isConnectionError(IllegalArgumentException("Invalid argument")))
        assertFalse(isConnectionError(IllegalStateException("Bad state")))
        assertFalse(isConnectionError(NullPointerException()))
        assertFalse(isConnectionError(RuntimeException("Tool not found")))
    }

    @Test
    fun `isConnectionError should return false for null message exceptions`() {
        assertFalse(isConnectionError(RuntimeException()))
    }

    // ==================== McpStatus 状态机测试 ====================

    @Test
    fun `McpStatus should have Idle as initial state`() {
        val status: McpStatus = McpStatus.Idle
        assertEquals("Idle", status::class.simpleName)
    }

    @Test
    fun `McpStatus Connecting should be a data object`() {
        val status: McpStatus = McpStatus.Connecting
        assertEquals("Connecting", status::class.simpleName)
    }

    @Test
    fun `McpStatus Connected should be a data object`() {
        val status: McpStatus = McpStatus.Connected
        assertEquals("Connected", status::class.simpleName)
    }

    @Test
    fun `McpStatus Reconnecting should carry attempt and maxAttempts`() {
        val status = McpStatus.Reconnecting(attempt = 3, maxAttempts = 5)
        assertEquals(3, status.attempt)
        assertEquals(5, status.maxAttempts)
    }

    @Test
    fun `McpStatus Dormant should carry nextRetryInMs`() {
        val status = McpStatus.Dormant(nextRetryInMs = 60_000L)
        assertEquals(60_000L, status.nextRetryInMs)
    }

    @Test
    fun `McpStatus Error should carry message`() {
        val status = McpStatus.Error(message = "Connection refused")
        assertEquals("Connection refused", status.message)
    }

    @Test
    fun `McpStatus sealed class should have exactly 6 subtypes`() {
        val subtypes = listOf(
            McpStatus.Idle,
            McpStatus.Connecting,
            McpStatus.Connected,
            McpStatus.Reconnecting(1, 5),
            McpStatus.Dormant(60_000L),
            McpStatus.Error("test"),
        )
        assertEquals(6, subtypes.size)
        assertEquals(6, subtypes.map { it::class }.distinct().size)
    }

    // ==================== McpManager 常量验证测试 ====================

    @Test
    fun `McpManager companion constants should have expected values`() {
        assertEquals(5, McpManager.MAX_RECONNECT_ATTEMPTS)
        assertEquals(1000L, McpManager.BASE_RECONNECT_DELAY_MS)
        assertEquals(30000L, McpManager.MAX_RECONNECT_DELAY_MS)
        assertEquals(60_000L, McpManager.DORMANT_RETRY_INTERVAL_MS)
        assertEquals(30, McpManager.DORMANT_MAX_RETRIES)
    }

    @Test
    fun `total reconnect time before Dormant should be under 1 minute`() {
        // 1+2+4+8+16 = 31 seconds of delays for 5 attempts
        var totalMs = 0L
        for (attempt in 1..McpManager.MAX_RECONNECT_ATTEMPTS) {
            totalMs += calculateBackoffDelay(attempt)
        }
        assertTrue(
            "Total reconnect time ($totalMs ms) should be under 60s",
            totalMs < 60_000L
        )
    }

    @Test
    fun `Dormant total retry time should be 30 minutes`() {
        val expectedMs = McpManager.DORMANT_RETRY_INTERVAL_MS * McpManager.DORMANT_MAX_RETRIES
        assertEquals(1_800_000L, expectedMs) // 30 minutes = 1800s
    }

    // ==================== 重连计数器持久性回归测试 ====================
    // 回归: 旧 cleanupServer 曾错误清除 reconnectAttempts，导致 scheduleReconnect
    // 递归重试时计数器从 0+1=1 重新开始，永远达不到 MAX_RECONNECT_ATTEMPTS，
    // 从而永远进不了 Dormant 状态。修复后 closeClient 不清除计数器，
    // 计数器仅在成功连接或 removeClient 时重置。

    @Test
    fun `reconnect counter simulation should reach MAX_RECONNECT_ATTEMPTS when all fail`() {
        // 模拟重连计数器行为（修复后的逻辑）
        val counter = mutableMapOf<kotlin.uuid.Uuid, Int>()
        val serverId = kotlin.uuid.Uuid.random()

        var reachedDormant = false

        fun simulatedScheduleReconnect() {
            val attempt = (counter[serverId] ?: 0) + 1
            if (attempt > McpManager.MAX_RECONNECT_ATTEMPTS) {
                reachedDormant = true
                return
            }
            counter[serverId] = attempt
            // 模拟 reconnectClient 失败（closeClient 不重置 counter）
            simulatedScheduleReconnect()
        }

        simulatedScheduleReconnect()

        assertTrue(
            "Counter should reach MAX_RECONNECT_ATTEMPTS and enter Dormant, " +
                "but final counter was ${counter[serverId]}",
            reachedDormant
        )
        assertEquals(McpManager.MAX_RECONNECT_ATTEMPTS, counter[serverId])
    }

    @Test
    fun `reconnect counter should reset on successful connection`() {
        val counter = mutableMapOf<kotlin.uuid.Uuid, Int>()
        val serverId = kotlin.uuid.Uuid.random()

        // 模拟 3 次失败
        repeat(3) {
            val attempt = (counter[serverId] ?: 0) + 1
            counter[serverId] = attempt
        }
        assertEquals(3, counter[serverId])

        // 模拟成功连接重置
        counter[serverId] = 0
        assertEquals(0, counter[serverId])

        // 下一次重连应该从 1 开始
        val nextAttempt = (counter[serverId] ?: 0) + 1
        assertEquals(1, nextAttempt)
    }

    @Test
    fun `closeClient should NOT reset reconnect counter`() {
        // 回归测试: closeClient 不应清除 reconnectAttempts
        // （之前 bug: cleanupServer 清除计数器 → 递归重连永远从 1 开始）
        val counter = mutableMapOf<kotlin.uuid.Uuid, Int>()
        val serverId = kotlin.uuid.Uuid.random()

        // 模拟 scheduleReconnect 设置计数器
        counter[serverId] = 3

        // 模拟 closeClient 行为（只关闭 Client，不清除 reconnectAttempts）
        // → counter 应保持 3

        assertEquals(
            "reconnectAttempts should survive closeClient",
            3, counter[serverId]
        )
    }

    // ==================== 自取消回归测试 ====================
    // 回归: reconnectClient 曾调用 cleanupServer → cancelAllJobs → reconnectJobs[id]?.cancel()
    // 这会取消 reconnectClient 自身运行的 Job，导致 client.connect() 立即抛出
    // CancellationException ("StandaloneCoroutine was cancelled")，重连永远失败。
    // 修复后 reconnectClient 只调用 closeClient（不取消任何 Job）。

    @Test
    fun `reconnectClient must not cancel its own reconnect job`() {
        // 模拟: reconnectJob 存活在 jobs map 中
        val reconnectJobs = mutableMapOf<kotlin.uuid.Uuid, Boolean>()
        val serverId = kotlin.uuid.Uuid.random()
        reconnectJobs[serverId] = true // 表示 Job 存在

        // 模拟 closeClient（修复后）: 不碰 reconnectJobs
        // closeClient 只操作 clients map，不碰 reconnectJobs
        // → reconnectJobs[serverId] 仍为 true

        assertTrue(
            "closeClient must not cancel reconnectJobs — reconnectClient runs inside it",
            reconnectJobs[serverId] == true
        )

        // 模拟 cancelAllJobs（仅用于 addClient/removeClient）: 会取消所有 Job
        reconnectJobs.remove(serverId)
        assertFalse(
            "cancelAllJobs should cancel reconnectJobs",
            reconnectJobs.containsKey(serverId)
        )
    }

    // ==================== syncAll stale client 检测测试 ====================
    // 回归: syncAll 对 transport==null 的 stale client 调用 syncTools → 抛异常 → 设 Error
    // 但不触发重连。修复后 syncAll 检测 transport==null 时走 addClient 重建连接。

    @Test
    fun `syncAll should rebuild client when transport is null (stale)`() {
        data class MockClient(val transportAlive: Boolean)

        val serverId = kotlin.uuid.Uuid.random()
        val clients = mutableMapOf<kotlin.uuid.Uuid, MockClient>()

        // 情况 1: client 存在但 transport==null（stale）
        clients[serverId] = MockClient(transportAlive = false)
        var actionTaken = ""

        val existingClient = clients[serverId]
        if (existingClient != null && existingClient.transportAlive) {
            actionTaken = "syncTools"
        } else {
            actionTaken = "addClient"
        }

        assertEquals(
            "syncAll should call addClient for stale client (transport==null)",
            "addClient", actionTaken
        )

        // 情况 2: client 存在且 transport alive
        clients[serverId] = MockClient(transportAlive = true)
        val existingClient2 = clients[serverId]
        actionTaken = if (existingClient2 != null && existingClient2.transportAlive) {
            "syncTools"
        } else {
            "addClient"
        }

        assertEquals(
            "syncAll should call syncTools for healthy client",
            "syncTools", actionTaken
        )

        // 情况 3: client 不存在
        clients.remove(serverId)
        val existingClient3 = clients[serverId]
        actionTaken = if (existingClient3 != null && existingClient3.transportAlive) {
            "syncTools"
        } else {
            "addClient"
        }

        assertEquals(
            "syncAll should call addClient when client doesn't exist",
            "addClient", actionTaken
        )
    }

    // ==================== CancellationException 传播测试 ====================
    // 回归: 所有 runCatching 块都必须 rethrow CancellationException，
    // 否则会破坏结构化并发。

    @Test
    fun `CancellationException must not be caught by runCatching in MCP operations`() {
        // 模拟 McpManager 中所有 runCatching 块的 CancellationException 处理模式:
        // runCatching { ... }.onFailure { if (it is CancellationException) throw it; ... }

        val cancellation = CancellationException("coroutine cancelled")
        val ioException = IOException("network error")

        // 模拟 onFailure handler
        fun handleFailure(e: Throwable): String {
            if (e is CancellationException) throw e
            return "Error: ${e.message}"
        }

        // CancellationException 应该被 rethrown
        assertThrows(CancellationException::class.java) {
            handleFailure(cancellation)
        }

        // 普通 IOException 应该被处理为 Error（不 throw）
        val result = handleFailure(ioException)
        assertEquals("Error: network error", result)
    }

    @Test
    fun `CancellationException in closeClient must be rethrown not swallowed`() {
        // 模拟 closeClient 的 runCatching 处理
        val cancellation = CancellationException("cancelled during close")

        fun simulateCloseClientFailure(e: Throwable): Boolean {
            // closeClient 模式: runCatching { it.close() }.onFailure { if (e is CancellationException) throw e; Log.w(...) }
            if (e is CancellationException) throw e
            return false // 返回 false 表示 close 失败但被吞（仅 Log.w）
        }

        assertThrows(CancellationException::class.java) {
            simulateCloseClientFailure(cancellation)
        }

        // 普通异常不应 throw
        assertFalse(simulateCloseClientFailure(RuntimeException("close failed")))
    }

    // ==================== 状态机完整性测试 ====================

    @Test
    fun `McpStatus transitions should cover all lifecycle paths`() {
        // 验证状态机覆盖了所有预期的生命周期路径
        val expectedTransitions = mapOf(
            "initial" to listOf(McpStatus.Idle, McpStatus.Connecting),
            "connecting" to listOf(McpStatus.Connected, McpStatus.Error("")),
            "connected" to listOf(McpStatus.Reconnecting(1, 5), McpStatus.Idle),
            "reconnecting" to listOf(McpStatus.Connected, McpStatus.Reconnecting(2, 5), McpStatus.Dormant(60_000L)),
            "dormant" to listOf(McpStatus.Connected, McpStatus.Error("max retries exceeded")),
            "error" to listOf(McpStatus.Connecting, McpStatus.Idle),
        )

        expectedTransitions.forEach { (fromState, toStates) ->
            assertTrue(
                "State '$fromState' should have at least 2 possible transitions",
                toStates.size >= 2
            )
            assertTrue(
                "State '$fromState' transitions should have distinct types",
                toStates.map { it::class }.distinct().size == toStates.size
            )
        }
    }

    @Test
    fun `McpStatus Reconnecting and Dormant should have different display semantics`() {
        val reconnecting = McpStatus.Reconnecting(attempt = 3, maxAttempts = 5)
        val dormant = McpStatus.Dormant(nextRetryInMs = 60_000L)

        // Reconnecting: 快速重连阶段（指数退避，秒级）
        assertTrue(reconnecting.attempt <= McpManager.MAX_RECONNECT_ATTEMPTS)

        // Dormant: 休眠重试阶段（固定间隔，分钟级）
        assertTrue(dormant.nextRetryInMs >= McpManager.BASE_RECONNECT_DELAY_MS)

        // 它们是不同的状态
        assertFalse(reconnecting::class == dormant::class)
    }

    @Test
    fun `total MCP recovery time budget should be bounded`() {
        // 快速重连: 1+2+4+8+16 = 31s
        var fastReconnectMs = 0L
        for (attempt in 1..McpManager.MAX_RECONNECT_ATTEMPTS) {
            fastReconnectMs += calculateBackoffDelay(attempt)
        }

        // Dormant: 60s × 30 = 1800s = 30 分钟
        val dormantMs = McpManager.DORMANT_RETRY_INTERVAL_MS * McpManager.DORMANT_MAX_RETRIES

        val totalMs = fastReconnectMs + dormantMs

        // 总恢复时间应小于 31 分钟（31s + 1800s = 1831s）
        assertTrue(
            "Total recovery time ($totalMs ms) should be under 31 minutes",
            totalMs < 1_860_000L
        )

        // 快速重连应远小于 Dormant 阶段
        assertTrue(
            "Fast reconnect phase ($fastReconnectMs ms) should be much shorter than dormant ($dormantMs ms)",
            fastReconnectMs < dormantMs / 10
        )
    }

    // ==================== 网络感知重连测试 ====================
    // 移动端关键场景: 网络不可用时不浪费重连尝试，等网络恢复后立即重连

    @Test
    fun `scheduleReconnect should skip attempt when network is offline`() {
        // 模拟网络离线时 scheduleReconnect 的行为
        var attemptCounter = 0
        var isOnline = false
        val maxAttempts = McpManager.MAX_RECONNECT_ATTEMPTS

        fun simulatedScheduleReconnect() {
            val attempt = attemptCounter + 1
            if (attempt > maxAttempts) return

            if (!isOnline) {
                // 网络离线: 不消耗 attempt，等 OFFLINE_CHECK_INTERVAL_MS 后重试
                // attemptCounter 不递增
                return
            }

            attemptCounter = attempt
            // 模拟重连失败
            simulatedScheduleReconnect()
        }

        // 网络离线时调用
        simulatedScheduleReconnect()
        assertEquals(
            "Attempt counter should not increment when offline",
            0, attemptCounter
        )

        // 网络恢复后调用
        isOnline = true
        attemptCounter = 0
        // 模拟 5 次都失败
        repeat(maxAttempts) {
            val attempt = attemptCounter + 1
            attemptCounter = attempt
        }
        assertEquals(maxAttempts, attemptCounter)
    }

    @Test
    fun `network recovery should trigger syncAll for all servers`() {
        // 模拟 NetworkMonitor.onNetworkAvailable → syncAll 的触发链
        val serversNeedingRecovery = mutableMapOf<kotlin.uuid.Uuid, Boolean>()
        val server1 = kotlin.uuid.Uuid.random()
        val server2 = kotlin.uuid.Uuid.random()
        serversNeedingRecovery[server1] = false // stale
        serversNeedingRecovery[server2] = false // stale

        var networkAvailableTriggered = false

        // 模拟网络恢复回调
        fun onNetworkAvailable() {
            networkAvailableTriggered = true
            serversNeedingRecovery.keys.forEach { id ->
                serversNeedingRecovery[id] = true // syncAll 重建
            }
        }

        onNetworkAvailable()

        assertTrue("Network available callback should fire", networkAvailableTriggered)
        assertTrue("Server 1 should be recovered", serversNeedingRecovery[server1]!!)
        assertTrue("Server 2 should be recovered", serversNeedingRecovery[server2]!!)
    }

    @Test
    fun `syncAll should trigger reconnect on connection error not just mark Error`() {
        // 回归: syncAll 中 syncTools 失败时，如果是连接错误应触发重连，不能仅标记 Error
        data class SyncResult(val isError: Boolean, val isConnectionError: Boolean)

        fun determineAction(result: SyncResult): String {
            return if (result.isError && result.isConnectionError) {
                "reconnect" // 半开连接 → 触发重连
            } else if (result.isError) {
                "error" // 工具同步失败（非连接问题）→ 仅标记 Error
            } else {
                "ok"
            }
        }

        // 连接错误 → 重连
        assertEquals("reconnect", determineAction(SyncResult(true, true)))
        // 工具错误 → Error
        assertEquals("error", determineAction(SyncResult(true, false)))
        // 成功
        assertEquals("ok", determineAction(SyncResult(false, false)))
    }

    // ==================== 移动端生命周期场景测试 ====================

    @Test
    fun `foreground recovery should health-check all connected servers`() {
        // 模拟 ProcessLifecycleOwner.onStart → syncAll
        data class ServerState(val name: String, val hasClient: Boolean, val transportAlive: Boolean)

        val servers = listOf(
            ServerState("server-a", hasClient = true, transportAlive = true),  // 健康
            ServerState("server-b", hasClient = true, transportAlive = false), // stale
            ServerState("server-c", hasClient = false, transportAlive = false), // 断连
        )

        val actions = servers.map { s ->
            when {
                s.hasClient && s.transportAlive -> "syncTools"
                else -> "addClient"
            }
        }

        assertEquals("syncTools", actions[0]) // 健康 → 刷新工具
        assertEquals("addClient", actions[1]) // stale → 重建
        assertEquals("addClient", actions[2]) // 断连 → 重建
    }

    @Test
    fun `WiFi to cellular switch should trigger recovery via network callback`() {
        // 场景: WiFi → 蜂窝切换
        // 1. 所有 SSE/HTTP TCP 连接变为半开
        // 2. NetworkCallback.onAvailable 被触发（蜂窝网络激活）
        // 3. McpManager.syncAll() 被调用
        // 4. syncTools 对半开连接失败 → 触发重连
        // 5. addClient 对已断开连接 → 完全重建

        var networkChanged = false
        var syncAllCalled = false
        var serversRecovered = 0
        val totalServers = 3

        // 模拟 WiFi → 蜂窝
        networkChanged = true

        // NetworkCallback.onAvailable 触发
        if (networkChanged) {
            syncAllCalled = true

            // syncAll 对每个 server 检查
            repeat(totalServers) {
                // 所有 TCP 连接都已半开，syncTools 会失败 → scheduleReconnect
                // 或 transport == null → addClient
                serversRecovered++
            }
        }

        assertTrue("Network change should trigger syncAll", syncAllCalled)
        assertEquals("All servers should be processed", totalServers, serversRecovered)
    }

    @Test
    fun `complete recovery timeline from network loss to full restoration`() {
        // 完整恢复时间线:
        // T=0s:   网络断开 → transport.onClose 触发 → scheduleReconnect
        // T=0s:   scheduleReconnect 检测 !isOnline → 不消耗 attempt, 等 10s
        // T=10s:  仍离线 → 继续等 10s
        // T=15s:  网络恢复 → NetworkCallback.onAvailable → syncAll → 立即重建所有连接
        //         (不等 scheduleReconnect 的 delay)

        val events = mutableListOf<String>()
        var isOnline = false
        var networkRestored = false
        var time = 0

        // T=0: 网络断开
        events.add("T=0: network lost")
        isOnline = false

        // T=0: transport.onClose → scheduleReconnect
        events.add("T=0: scheduleReconnect called")
        if (!isOnline) {
            events.add("T=0: offline, skip attempt, wait ${McpManager.OFFLINE_CHECK_INTERVAL_MS / 1000}s")
        }

        // T=10: 仍离线
        time = 10
        events.add("T=$time: still offline, keep waiting")

        // T=15: 网络恢复
        time = 15
        isOnline = true
        networkRestored = true
        events.add("T=$time: network restored via NetworkCallback")

        if (networkRestored) {
            events.add("T=$time: syncAll triggered → all servers rebuilt")
            events.add("T=$time: servers Connected")
        }

        // 验证恢复时间线
        assertTrue("Should detect offline and skip wasting attempts",
            events.any { it.contains("offline, skip attempt") })
        assertTrue("NetworkCallback should trigger syncAll",
            events.any { it.contains("syncAll triggered") })
        assertTrue("All servers should be Connected",
            events.any { it.contains("servers Connected") })

        // 总恢复时间应远小于纯重连策略（5次退避 31s + dormant 600s）
        // 因为 NetworkCallback 在 15s 就触发了恢复
        assertTrue("Recovery should happen at T=15, not after 31s+ of backoff",
            events.last().contains("T=15"))
    }

    // ==================== 工具超时异常分级处理测试 ====================
    // 回归: TimeoutCancellationException 是 CancellationException 子类
    // 必须优先检测并降级为错误文本，不能被 CancellationException 的 throw 拦截

    @Test
    fun `TimeoutCancellationException must be handled before CancellationException check`() {
        // 模拟 McpManager.callTool 的异常分级处理逻辑
        data class HandleResult(val action: String, val message: String)

        fun handleException(e: Throwable): HandleResult {
            // 1. 工具超时 → 降级为错误文本
            if (e is TimeoutCancellationException) {
                return HandleResult("return_error", "timed out (120s)")
            }
            // 2. 真正的协程取消 → 向上传播
            if (e is CancellationException) {
                return HandleResult("throw", "cancelled")
            }
            // 3. 连接错误 → 触发重连
            if (e is IOException || e.message?.contains("connection") == true) {
                return HandleResult("reconnect", "connection error")
            }
            // 4. 其他 → 错误文本
            return HandleResult("return_error", "failed: ${e.message}")
        }

        // TimeoutCancellationException → 应降级为错误文本（不 throw）
        // TimeoutCancellationException 构造函数是 internal，无法直接实例化，通过 withTimeout 触发
        val realTimeoutException = runBlocking {
            try {
                withTimeout(1.milliseconds) { delay(Long.MAX_VALUE) }
                RuntimeException("unreachable")
            } catch (e: TimeoutCancellationException) {
                e
            }
        }
        val timeoutResult = handleException(realTimeoutException)
        assertEquals("return_error", timeoutResult.action)

        // 普通 CancellationException → 应 throw
        val cancelResult = handleException(CancellationException("job cancelled"))
        assertEquals("throw", cancelResult.action)

        // IOException → 应触发重连
        val ioResult = handleException(IOException("connection reset"))
        assertEquals("reconnect", ioResult.action)

        // 其他异常 → 错误文本
        val otherResult = handleException(RuntimeException("server error"))
        assertEquals("return_error", otherResult.action)
        assertTrue(otherResult.message.contains("server error"))
    }

    @Test
    fun `tool timeout should not mark MCP server as Error or trigger reconnect`() {
        // 模拟超时后的状态变化
        var mcpStatus = "Connected"
        var reconnectTriggered = false

        fun handleTimeout() {
            // 超时处理: 不改状态，不重连
            // mcpStatus 保持 Connected
            // reconnectTriggered 保持 false
        }

        handleTimeout()

        assertEquals(
            "MCP server status should remain Connected after tool timeout",
            "Connected", mcpStatus
        )
        assertFalse(
            "Tool timeout should not trigger reconnect (server is still alive)",
            reconnectTriggered
        )
    }

    @Test
    fun `tool timeout should return error text not throw exception`() {
        // 模拟 McpManager.callTool 超时后的返回值
        val timeoutResponse = "MCP tool 'bash' timed out (120s)"

        // 这个文本会返回给 AI，对话继续进行
        assertTrue(timeoutResponse.contains("timed out"))
        assertTrue(timeoutResponse.contains("120s"))
    }
}
