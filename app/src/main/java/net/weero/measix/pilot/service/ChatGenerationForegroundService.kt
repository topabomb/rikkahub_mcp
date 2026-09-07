package net.weero.measix.pilot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import net.weero.measix.pilot.R
import net.weero.measix.pilot.utils.NotificationConfig
import net.weero.measix.pilot.utils.NotificationUtil
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val FOREGROUND_LIFETIME_TAG = "GenerationForeground"

/**
 * 纯决策：只要还有任一 Conversation 处于 RESPONSE_GENERATION 就保持保活。
 *
 * APPROVAL_REQUIRED 与 TITLE_GENERATION 都不是持续生成：等待审批长时间不落定
 * 不应占用 dataSync 配额；Target 运行由其 Master 的 RESPONSE_GENERATION 自然覆盖。
 */
internal fun shouldKeepGenerationForeground(
    activities: Map<Uuid, Set<ConversationActivity>>,
): Boolean = activities.any { (_, set) -> ConversationActivity.RESPONSE_GENERATION in set }

/**
 * 生成期保活的 Android 生命周期消费者。
 *
 * 它**不是**生成事实的 owner：`ConversationRuntime` / `TurnCommitter` 仍是生成、取消、终态与恢复的
 * 唯一权威。本 service 只把"当前是否存在正在生成的 Master turn"这一只读投影转换成 Android 前台
 * 服务语义，让系统在网络流期间把应用视为用户可见的持续工作。
 *
 * 因此这里没有 generation id、turn map、Job 或消息：唯一输入是
 * [ConversationQueryService.conversationActivities]，停止条件也只来自该投影。
 * 它同样不调用 `KeepScreenOn`、不操作 Window flag、不读取 UI 状态——亮屏由 `ChatPageContent`
 * 独占，两者只是同一 Runtime 事实的两种平台消费。
 */
class ChatGenerationForegroundService : Service() {

    private val queryService: ConversationQueryService by inject()
    private val applicationService: ConversationApplicationService by inject()
    private val appScope: AppScope by inject()

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        publishForeground()
        startMonitoring()
        // 进程被杀后不自动重启：重新拉起会把"没有活跃生成"伪装成需要保活
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync 共享配额：系统只给数秒 stopSelf，不能先等待投影或网络取消。
        // 停止 turn 的 command 交给 AppScope 继续收口；service 本身立即释放平台配额。
        stopSelf(startId)
        stopProjectedGenerations()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            queryService.conversationActivities()
                .map { activities -> shouldKeepGenerationForeground(activities) }
                .distinctUntilChanged()
                .collect { keepAlive ->
                    if (!keepAlive) {
                        stopSelf()
                    } else {
                        publishForeground()
                    }
                }
        }
    }

    private fun publishForeground() {
        // 保活通知只表达"正在后台继续生成"，不承载内容与进度；
        // 流式/完成通知仍由 ChatNotificationManager 拥有，事件不得反向推断生成是否活跃。
        val config = NotificationConfig().apply {
            title = getString(R.string.generation_foreground_notification_title)
            content = getString(R.string.generation_foreground_notification_content)
            ongoing = true
            onlyAlertOnce = true
            visibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        val notification = NotificationUtil.buildNotification(
            context = this,
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            config = config,
        ).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    GENERATION_FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(GENERATION_FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (security: SecurityException) {
            // 部分 OEM ROM 会在系统侧拒绝 FGS 类型权限。保活失败只影响后台存活，
            // 不能取消或伪装 turn 失败；生成仍由 Runtime 按现有终态收口。
            Log.w(TAG, "foreground service launch rejected by the platform", security)
            GenerationForegroundDiagnostics.reportRejected(security)
            stopSelf()
        }
    }

    private fun stopProjectedGenerations() {
        // 平台配额已到：从只读投影找出 active turn，再经 application command 请求停止。
        appScope.launch {
            val generatingIds: Set<Uuid> = try {
                queryService.conversationActivities().first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "failed to read active generations after foreground timeout", error)
                emptyMap()
            }
                .filterValues { ConversationActivity.RESPONSE_GENERATION in it }
                .keys
            generatingIds.forEach { conversationId ->
                runCatching { applicationService.stopGeneration(conversationId) }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.w(TAG, "failed to stop generation for $conversationId", error)
                    }
            }
        }
    }

    internal companion object {
        /** 与 ChatNotificationManager 的会话通知 ID 不重叠的稳定 ID。 */
        const val GENERATION_FOREGROUND_NOTIFICATION_ID = 9001
        private const val TAG = "ChatGenerationForegroundSvc"

        fun newIntent(context: Context): Intent =
            Intent(context, ChatGenerationForegroundService::class.java)
    }
}

/**
 * 应用侧对 FGS 的唯一入口：单向平台请求，无状态、无返回值语义。
 *
 * 调用方在用户可见地发起或继续 Master turn 之后调用 [ensureStarted]；何时停止由 service 自己
 * 依据投影决定，因此不存在 acquire/release 计数，也不存在第二个运行事实源。
 */
object GenerationForegroundLifetime {
    fun ensureStarted(context: Context) {
        runCatching {
            val intent = ChatGenerationForegroundService.newIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { error ->
            Log.w(
                FOREGROUND_LIFETIME_TAG,
                "foreground service start failed; generation continues without background keep-alive",
                error,
            )
            GenerationForegroundDiagnostics.reportRejected(error)
        }
    }
}

/** FGS 启动被平台拒绝时的 typed 诊断出口：只用于日志与诊断，不影响 turn 终态。 */
internal object GenerationForegroundDiagnostics {
    @Volatile
    var lastRejection: Throwable? = null
        private set

    fun reportRejected(cause: Throwable) {
        lastRejection = cause
    }
}
