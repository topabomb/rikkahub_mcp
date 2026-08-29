package net.weero.measix.pilot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import net.weero.measix.pilot.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.mcp.OAuthCallbackKeepAlive
import net.weero.measix.pilot.data.ai.mcp.OAuthCallbackKeepAliveLease
import net.weero.measix.pilot.utils.NotificationConfig
import net.weero.measix.pilot.utils.NotificationUtil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 浏览器授权期间对 loopback 回调 socket 的保活宿主。
 *
 * 它不保存 token、MCP config 或授权阶段——`McpOAuthCoordinator` 仍是授权流程的唯一 owner，
 * 回调完成后由 coordinator 显式停止本服务。保活只覆盖浏览器在前台期间的进程存活窗口。
 */
class McpOAuthCallbackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = NotificationConfig().apply {
            title = getString(R.string.oauth_callback_foreground_notification_title)
            content = getString(R.string.oauth_callback_foreground_notification_content)
            ongoing = true
            onlyAlertOnce = true
            visibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        val notification = NotificationUtil.buildNotification(
            context = this,
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            config = config,
        ).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    OAUTH_CALLBACK_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(OAUTH_CALLBACK_NOTIFICATION_ID, notification)
            }
        } catch (security: SecurityException) {
            // 保活被拒只影响后台进程存活；授权本身由 loopback 回调完成，前台场景仍可用
            Log.w(TAG, "OAuth callback foreground service rejected", security)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val OAUTH_CALLBACK_NOTIFICATION_ID = 9002
        private const val TAG = "McpOAuthCallbackSvc"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, McpOAuthCallbackService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "OAuth callback service start failed", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpOAuthCallbackService::class.java))
        }
    }
}

/**
 * DI 注入的保活端口实现。引用计数只拥有平台 service lease，不保存授权阶段或 server 身份；
 * 不同 MCP server 并发授权时，一个流程结束不能提前停止另一个仍在使用的保活。
 */
class McpOAuthCallbackKeepAlive(
    private val startService: (Context) -> Unit = McpOAuthCallbackService::start,
    private val stopService: (Context) -> Unit = McpOAuthCallbackService::stop,
) : OAuthCallbackKeepAlive {
    private val lock = Any()
    private var leaseCount = 0

    override fun acquire(context: Context): OAuthCallbackKeepAliveLease {
        val applicationContext = context.applicationContext
        synchronized(lock) {
            leaseCount++
            if (leaseCount == 1) startService(applicationContext)
        }
        return Lease { release(applicationContext) }
    }

    private fun release(context: Context) {
        synchronized(lock) {
            check(leaseCount > 0) { "OAuth callback keep-alive lease underflow" }
            leaseCount--
            if (leaseCount == 0) stopService(context)
        }
    }

    private class Lease(
        private val release: () -> Unit,
    ) : OAuthCallbackKeepAliveLease {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
