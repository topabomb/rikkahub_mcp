package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "NetworkMonitor"

/**
 * 网络状态监控器：基于 ConnectivityManager.NetworkCallback
 *
 * 职责:
 * - 实时感知网络变化（WiFi ↔ 蜂窝切换、网络断开/恢复）
 * - 暴露 [isOnline] StateFlow 供重连策略查询
 * - 网络恢复时触发回调，让 McpManager 主动 syncAll
 *
 * 移动端 MCP 连接的关键问题:
 * - WiFi→蜂窝切换时 TCP 连接半开（OS 认为活着，实际已废），transport 回调 30s+ 才触发
 * - 网络完全断开时，重连尝试纯粹浪费电池
 * - NetworkCallback.onAvailable 是最可靠的网络恢复信号，比 transport 回调快 10-30s
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkCurrentNetwork())
    val isOnline: StateFlow<Boolean> = _isOnline

    var onNetworkAvailable: (() -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            _isOnline.value = true
            onNetworkAvailable?.invoke()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost")
            // 检查是否还有其他可用网络（WiFi 断开但蜂窝可用的情况）
            _isOnline.value = checkCurrentNetwork()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _isOnline.value = hasInternet && isValidated
        }
    }

    init {
        connectivityManager?.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback
        )
    }

    /**
     * 检查当前是否有可用的网络连接
     */
    private fun checkCurrentNetwork(): Boolean {
        val cm = connectivityManager ?: return true // 无 ConnectivityManager 时乐观假设可用
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 释放资源（通常在 App onTerminate 或测试中调用）
     */
    fun unregister() {
        connectivityManager?.unregisterNetworkCallback(callback)
    }
}
