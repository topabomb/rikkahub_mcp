package net.weero.measix.pilot.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import net.weero.measix.pilot.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.utils.cancelNotification
import net.weero.measix.pilot.utils.sendNotification
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

private data class LiveUpdateNotificationState(
    val sentAt: Long,
    val executingToolOrdinal: Int?,
)

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val notificationStateLock = Any()
    private var isForeground = false
    private val liveUpdateStates = mutableMapOf<Uuid, LiveUpdateNotificationState>()
    private val activeLiveNotifications = mutableSetOf<Uuid>()

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> synchronized(notificationStateLock) {
                            isForeground = true
                            activeLiveNotifications.toList().forEach(::cancelLiveUpdateNotification)
                        }
                        Lifecycle.Event.ON_STOP -> synchronized(notificationStateLock) {
                            isForeground = false
                        }
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                    is AppEvent.ChatGenerationAwaitingApproval -> handleAwaitingApproval(event)
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        synchronized(notificationStateLock) {
            if (isForeground) return
            val displaySetting = settingsStore.effectiveSettings.value.settings.displaySetting
            if (!displaySetting.enableNotificationOnMessageGeneration) return
            if (!displaySetting.enableLiveUpdateNotification) return

            val now = SystemClock.elapsedRealtime()
            val previous = liveUpdateStates[event.conversationId]
            val committedToolPhaseChanged = previous?.executingToolOrdinal != event.executingToolOrdinal
            if (!committedToolPhaseChanged &&
                previous != null &&
                now - previous.sentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS
            ) {
                return
            }
            liveUpdateStates[event.conversationId] = LiveUpdateNotificationState(
                sentAt = now,
                executingToolOrdinal = event.executingToolOrdinal,
            )

            sendLiveUpdateNotification(
                event.conversationId,
                event.lastMessage,
                event.senderName,
                event.executingToolOrdinal,
            )
        }
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        synchronized(notificationStateLock) {
            cancelLiveUpdateNotification(event.conversationId)

            if (!event.notifyCompletion) return
            val contentPreview = event.contentPreview ?: return
            if (isForeground) return
            if (!settingsStore.effectiveSettings.value.settings.displaySetting.enableNotificationOnMessageGeneration) return
            sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
        }
    }

    private fun handleAwaitingApproval(event: AppEvent.ChatGenerationAwaitingApproval) {
        synchronized(notificationStateLock) {
            liveUpdateStates.remove(event.conversationId)
            val displaySetting = settingsStore.effectiveSettings.value.settings.displaySetting
            if (isForeground ||
                !displaySetting.enableNotificationOnMessageGeneration ||
                !displaySetting.enableLiveUpdateNotification
            ) {
                cancelLiveUpdateNotification(event.conversationId)
                return
            }
            val pendingTool = event.lastMessage.parts.filterIsInstance<UIMessagePart.Tool>()
                .getOrNull(event.pendingToolOrdinal)
            val toolName = pendingTool?.toolName?.substringAfterLast("__").orEmpty()
            activeLiveNotifications += event.conversationId
            context.sendNotification(
                channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                notificationId = getLiveUpdateNotificationId(event.conversationId),
            ) {
                title = event.senderName
                content = pendingTool?.input?.take(100).orEmpty()
                subText = if (toolName.isBlank()) {
                    context.getString(R.string.conversation_turn_awaiting_approval)
                } else {
                    context.getString(R.string.notification_live_update_tool, toolName)
                }
                ongoing = false
                autoCancel = true
                onlyAlertOnce = true
                category = NotificationCompat.CATEGORY_MESSAGE
                useBigTextStyle = true
                contentIntent = getPendingIntent(context, event.conversationId)
                shortCriticalText = context.getString(R.string.conversation_turn_awaiting_approval)
            }
        }
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String,
        executingToolOrdinal: Int?,
    ) {
        val (chipText, statusText, contentText) = determineNotificationContent(
            parts = lastMessage.parts,
            executingToolOrdinal = executingToolOrdinal,
        )

        activeLiveNotifications += conversationId
        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(
        parts: List<UIMessagePart>,
        executingToolOrdinal: Int?,
    ): Triple<String, String, String> {
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val executingTool = executingToolOrdinal?.let(parts.filterIsInstance<UIMessagePart.Tool>()::getOrNull)
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            executingTool != null -> {
                val toolName = executingTool.toolName.substringAfterLast("__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    executingTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdateStates.remove(conversationId)
        activeLiveNotifications.remove(conversationId)
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
