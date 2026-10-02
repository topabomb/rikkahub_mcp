package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.ProviderFailureKind
import net.weero.measix.pilot.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatErrorStoreTest {
    @Test fun `read failures deduplicate only within their conversation and can return after dismissal`() {
        val store = ChatErrorStore()
        val error = ChatError(detail = "IOException: favorites unavailable", conversationId = Uuid.random(),
            solution = ChatErrorSolution.RetryConversationReads, retention = ChatErrorRetention.UNTIL_DISMISSED)
        store.add(error)
        store.add(error.copy(id = Uuid.random()))
        assertEquals(1, store.errors.value.size)
        store.add(error.copy(id = Uuid.random(), conversationId = Uuid.random()))
        assertEquals(2, store.errors.value.size)
        store.dismiss(error.id)
        store.add(error.copy(id = Uuid.random()))
        assertEquals(2, store.errors.value.size)
    }

    @Test
    fun `command diagnostics preserve full cause and cleanup failures while redacting credentials`() {
        val cause = java.io.IOException("payload read failed " + "x".repeat(900))
        val error = IllegalStateException("Cannot import Authorization: Bearer command-secret", cause)
        error.addSuppressed(java.io.IOException("temporary-file cleanup failed"))
        val store = ChatErrorStore()
        store.add(error)
        val diagnostic = store.errors.value.single().detail
        assertTrue(diagnostic.contains("IllegalStateException"))
        assertTrue(diagnostic.contains("Caused by: IOException: payload read failed"))
        assertTrue(diagnostic.contains("x".repeat(900)))
        assertTrue(diagnostic.contains("Suppressed: IOException: temporary-file cleanup failed"))
        org.junit.Assert.assertFalse(diagnostic.contains("command-secret"))
    }

    @Test
    fun `chat errors preserve diagnostics and receive unique identities`() {
        val conversationId = Uuid.random()
        val first = ChatError(
            title = "Generation Failed",
            detail = "API rate limit exceeded",
            conversationId = conversationId,
            retention = ChatErrorRetention.UNTIL_DISMISSED,
        )
        val second = ChatError(detail = "second")

        assertNotEquals(first.id, second.id)
        assertEquals("Generation Failed", first.title)
        assertEquals("API rate limit exceeded", first.detail)
        assertEquals(conversationId, first.conversationId)
        assertEquals(ChatErrorRetention.UNTIL_DISMISSED, first.retention)
        assertTrue(first.timestamp <= System.currentTimeMillis())
    }

    @Test
    fun `store ignores cancellation and supports deterministic dismiss and clear`() {
        val store = ChatErrorStore()
        store.add(CancellationException("cancelled"))
        store.add(IllegalStateException("first"))
        store.add(IllegalStateException("second"))

        assertEquals(2, store.errors.value.size)
        store.dismiss(store.errors.value.first().id)
        assertEquals(listOf("IllegalStateException: second"), store.errors.value.map { it.detail })
        store.clear(Uuid.random())
        assertTrue(store.errors.value.isEmpty())
    }

    @Test
    fun `conversation projection isolates errors and terminal reopening replaces the same source`() = runTest {
        val firstConversation = Uuid.random()
        val secondConversation = Uuid.random()
        val sourceMessageId = Uuid.random()
        val store = ChatErrorStore()

        store.add(ChatError(detail = "global"))
        store.add(
            ChatError(
                detail = "first diagnostic",
                conversationId = firstConversation,
                sourceMessageId = sourceMessageId,
            )
        )
        store.add(ChatError(detail = "other", conversationId = secondConversation))
        store.add(
            ChatError(
                detail = "reopened diagnostic",
                conversationId = firstConversation,
                sourceMessageId = sourceMessageId,
            )
        )

        assertEquals(
            listOf("global", "reopened diagnostic"),
            store.errorsFor(firstConversation).first().map { it.detail },
        )
        assertEquals(3, store.errors.value.size)

        store.clear(firstConversation)
        assertEquals(listOf("other"), store.errors.value.map { it.detail })
    }

    @Test
    fun `provider action follows original model ownership and preserves managed service diagnostics`() {
        val context = mockk<android.content.Context> {
            every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
        }
        val managed = me.rerere.common.configuration.ConfigurationReference.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("original-enterprise"), "mdl_original",
        )
        val personal = me.rerere.common.configuration.ConfigurationReference.random()
        for (reason in listOf(ProviderFailureKind.AUTH_FAILED.reason, ProviderFailureKind.PERMISSION_DENIED.reason,
            ProviderFailureKind.QUOTA_EXHAUSTED.reason)) {
            fun failure(model: me.rerere.common.configuration.ConfigurationReference?) = requireNotNull(terminalChatError(
                context, Uuid.random(), Uuid.random(), MessageTerminalStatus.FAILED, reason,
                "HTTP 401: original upstream diagnostic", model,
            ))
            val enterpriseError = failure(managed)
            assertEquals(null, enterpriseError.solution)
            assertEquals("resource-${R.string.chat_enterprise_model_service_configuration}", enterpriseError.summary)
            assertEquals("HTTP 401: original upstream diagnostic", enterpriseError.detail)
            val userError = failure(personal)
            assertEquals(ChatErrorSolution.CheckProviderSettings, userError.solution)
            assertEquals("HTTP 401: original upstream diagnostic", userError.detail)
            assertEquals(null, failure(null).solution)
        }
        val budget = requireNotNull(terminalChatError(context, Uuid.random(), Uuid.random(), MessageTerminalStatus.FAILED,
            net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED, "budget rejected", managed))
        assertEquals(ChatErrorSolution.ViewEnterpriseUsage, budget.solution)
    }

    @Test fun `enterprise preparation presentation follows its boundary and preserves original causes`() {
        val context = mockk<android.content.Context> {
            every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
        }
        val store = ChatErrorStore()
        val original = java.net.ConnectException("192.168.1.8:9100 EHOSTUNREACH").apply {
            initCause(java.io.IOException("No route to host"))
            addSuppressed(IllegalStateException("cleanup detail"))
        }
        store.add(EnterprisePreparationException(original), context = context, solution = ChatErrorSolution.CheckTitleModelSettings)
        val error = store.errors.value.single()
        assertEquals("resource-${R.string.enterprise_prepare_connection_title}", error.title)
        assertEquals("resource-${R.string.enterprise_prepare_connection}", error.summary)
        assertEquals(ChatErrorSolution.ViewEnterpriseSpace, error.solution)
        assertEquals(ChatErrorRetention.UNTIL_DISMISSED, error.retention)
        assertTrue(error.detail.contains("ConnectException: 192.168.1.8:9100 EHOSTUNREACH"))
        assertTrue(error.detail.contains("No route to host"))
        assertTrue(error.detail.contains("cleanup detail"))
        store.add(original, context = context)
        assertNull(store.errors.value.last().summary)
        assertNull(store.errors.value.last().solution)
        val sync = enterprisePreparationError(context, EnterprisePreparationException(
            net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException("platform_runtime_synchronization_required")))
        assertEquals("resource-${R.string.enterprise_prepare_sync}", sync.summary)
        val http = enterprisePreparationError(context, EnterprisePreparationException(
            net.weero.measix.pilot.data.enterprise.PlatformHttpException(403, null, "device_revoked")))
        assertEquals("resource-${R.string.enterprise_prepare_session}", http.summary)
        assertTrue(http.detail.contains("HTTP 403: platform_http_error: device_revoked"))
    }

    @Test fun `runtime problem retains complete structured diagnostics separately from localized explanation`() {
        val context = mockk<android.content.Context> {
            every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
        }
        val problem = net.weero.measix.pilot.data.enterprise.PlatformProblem(
            type = "https://example.test/problems/usage_meter_unavailable", title = "Usage meter unavailable", status = 422,
            code = "usage_meter_unavailable", detail = "Original relay diagnostic", forwarded = false,
            requestId = "req_00000000-0000-4000-8000-000000000001",
        )
        val encoded = net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemException(422, problem).terminalDetail()
        val error = requireNotNull(terminalChatError(context, Uuid.random(), Uuid.random(), MessageTerminalStatus.FAILED,
            problem.code, encoded))
        assertEquals("resource-${R.string.enterprise_usage_meter_unavailable_detail}", error.summary)
        val decoded = net.weero.measix.pilot.data.enterprise.PlatformWireCodec.decode<net.weero.measix.pilot.data.enterprise.PlatformProblem>(error.detail)
        assertEquals(problem, decoded)
    }

    @Test fun `structured secret redaction preserves valid JSON and diagnostics after the header`() {
        val context = mockk<android.content.Context> {
            every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
        }
        val problem = net.weero.measix.pilot.data.enterprise.PlatformProblem(
            type = "https://example.test/problems/usage_meter_unavailable", title = "Usage meter unavailable", status = 422,
            code = "usage_meter_unavailable", detail = "Original detail\nAuthorization: Bearer fixture-secret\nOriginal lower cause /192.168.1.8:9100",
            forwarded = false, requestId = "req_00000000-0000-4000-8000-000000000001",
        )
        val error = requireNotNull(terminalChatError(context, Uuid.random(), Uuid.random(), MessageTerminalStatus.FAILED,
            problem.code, net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemException(422, problem).terminalDetail()))
        assertFalse(error.detail.contains("fixture-secret"))
        val decoded = net.weero.measix.pilot.data.enterprise.PlatformWireCodec.decode<net.weero.measix.pilot.data.enterprise.PlatformProblem>(error.detail)
        assertEquals(problem.copy(detail = "Original detail\nAuthorization: <redacted>\nOriginal lower cause /192.168.1.8:9100"), decoded)
    }

    @Test
    fun `terminal presentation distinguishes provider kinds incomplete and cancellation`() {
        assertEquals(
            R.string.error_title_quota_exhausted,
            terminalMessagePresentation(
                MessageTerminalStatus.FAILED,
                ProviderFailureKind.QUOTA_EXHAUSTED.reason,
            ).titleResource,
        )
        assertEquals(
            R.string.error_title_response_incomplete,
            terminalMessagePresentation(
                MessageTerminalStatus.INCOMPLETE,
                TurnTerminalReasons.PROVIDER_INCOMPLETE,
            ).statusResource,
        )
        assertEquals(
            R.string.chat_message_terminal_user_stopped,
            terminalMessagePresentation(
                MessageTerminalStatus.CANCELLED,
                TurnTerminalReasons.USER_STOP,
            ).statusResource,
        )
        assertEquals(
            ChatErrorSolution.ViewEnterpriseUsage,
            terminalMessagePresentation(
                MessageTerminalStatus.FAILED,
                net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED,
            ).solution,
        )
    }

}
