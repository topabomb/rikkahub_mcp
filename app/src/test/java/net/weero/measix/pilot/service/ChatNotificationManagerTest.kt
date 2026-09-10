package net.weero.measix.pilot.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import net.weero.measix.pilot.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatNotificationManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `late buffered enterprise events cannot publish after expiry exit or a new session`() = runTest {
        val base = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(base).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val app = spyk(base)
        every { app.getString(any()) } returns "notification"
        val notifications = app.getSystemService(NotificationManager::class.java)
        listOf(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID).forEach {
            notifications.createNotificationChannel(NotificationChannel(it, it, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val settings = mockk<SettingsStore> { every { userSettings } returns MutableStateFlow(Settings()) }
        var now = 1_000L
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val owner = ChatNotificationManager(app, scope, AppEventBus(), settings, sessions)
        try {
            val packet = exampleEnterprisePackage()
            val state = sessions.enrollFixture(packet)
            val access = sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val id = Uuid.random()
            val update = AppEvent.ChatGenerationUpdate(access, id, UIMessage.assistant("private output"), "assistant", null)
            val awaiting = AppEvent.ChatGenerationAwaitingUser(access, id, UIMessage.assistant("private tool"), "assistant", Uuid.random())
            val ended = AppEvent.ChatGenerationEnded(access, id, "assistant", "private completion", true)
            owner.handleEvent(update)
            assertEquals(1, notifications.activeNotifications.size)
            now = state.manifest.session!!.expiresAtMillis
            for (event in listOf(awaiting, update, ended)) owner.handleEvent(event)
            assertTrue(notifications.activeNotifications.isEmpty())
            val exit = sessions.beginExit(requireNotNull(sessions.captureExitRequest()))
            sessions.finishExit(exit)
            for (event in listOf(update, awaiting, ended)) owner.handleEvent(event)
            assertTrue(notifications.activeNotifications.isEmpty())
            sessions.enrollFixture(packet)
            for (event in listOf(update, awaiting, ended)) owner.handleEvent(event)
            assertTrue(notifications.activeNotifications.isEmpty())
            val fresh = sessions.captureSelectedRealmAccess()
            owner.handleEvent(ended.copy(access = fresh))
            assertEquals("private completion", notifications.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
            // Cleanup remains allowed without a valid Session; previously valid completion notifications stay.
            owner.handleEvent(ended.copy(notifyCompletion = false))
            assertEquals(1, notifications.activeNotifications.size)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            notifications.cancelAll()
        }
    }
}
