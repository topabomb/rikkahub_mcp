package net.weero.measix.pilot.ui.pages.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.runtime.ConversationNotFoundException
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.UiState
import net.weero.measix.pilot.utils.UpdateChecker
import net.weero.measix.pilot.utils.base64Encode
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPageLifecycleTest {
    @Test fun `ready snapshot waits for its joined configuration instead of reporting a missing resource`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        fixture.models.value = null
        try {
            val vm = fixture.create()
            runCurrent()
            assertSame(ConversationReadState.Loading, vm.conversationState.value)
            assertNull(vm.snapshot.value)
            assertNull(vm.conversationUiModel.value)
            assertFalse(fixture.lease.closed.value)
            vm.inputState.setMessageText("Draft while the projection loads")
            fixture.models.value = fixture.model
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            assertSame(fixture.model, vm.conversationUiModel.value)
            assertEquals("Draft while the projection loads", vm.inputState.textContent.text.toString())
            assertSame(fixture.imports, vm.artifactDraftScope)
            coVerify(exactly = 1) { fixture.application.initialize(fixture.request) }
            verify(exactly = 0) { fixture.imports.close() }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `joined projection failure keeps its diagnostic and retry preserves the input editor`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val failure = java.io.IOException("configuration projection unreadable", IllegalStateException("original detail"))
        try {
            val vm = fixture.create()
            runCurrent()
            vm.inputState.setMessageText("unsent edits")
            vm.inputState.messageContent = listOf(UIMessagePart.Image("file:///owned/input"))
            every { fixture.query.conversationUiModel(fixture.lease) } returns kotlinx.coroutines.flow.flow { throw failure }
            vm.retryConversationLoad()
            runCurrent()
            val observedError = (vm.conversationState.value as ConversationReadState.Failed).error
            assertEquals(failure.javaClass, observedError.javaClass)
            assertEquals(failure.message, observedError.message)
            assertTrue(generateSequence(observedError.cause) { it.cause }.any {
                it is IllegalStateException && it.message == "original detail"
            })
            assertTrue(observedError.userVisibleDiagnostic().contains(failure.userVisibleDiagnostic()))
            assertNull(vm.snapshot.value)
            assertNull(vm.conversationUiModel.value)
            assertFalse(fixture.lease.closed.value)
            assertSame(fixture.imports, vm.artifactDraftScope)
            assertEquals("unsent edits", vm.inputState.textContent.text.toString())
            assertEquals(listOf(UIMessagePart.Image("file:///owned/input")), vm.inputState.messageContent)
            verify(exactly = 0) { fixture.imports.close() }
            every { fixture.query.conversationUiModel(fixture.lease) } returns fixture.models
            vm.retryConversationLoad()
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            assertSame(fixture.model, vm.conversationUiModel.value)
            assertEquals("unsent edits", vm.inputState.textContent.text.toString())
            assertEquals(listOf(UIMessagePart.Image("file:///owned/input")), vm.inputState.messageContent)
            coVerify(exactly = 1) { fixture.application.initialize(fixture.request) }
            verify(exactly = 1) { fixture.artifacts.openDraftScope(fixture.lease) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `primary read failure cannot be covered by an older joined ready model`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val failure = java.io.IOException("conversation snapshot unreadable")
        try {
            val vm = fixture.create()
            runCurrent()
            vm.inputState.setMessageText("keep draft")
            fixture.reads.value = ConversationReadState.Failed(failure)
            runCurrent()
            assertSame(failure, (vm.conversationState.value as ConversationReadState.Failed).error)
            assertNull(vm.snapshot.value)
            assertNull(vm.conversationUiModel.value)
            assertFalse(fixture.lease.closed.value)
            fixture.reads.value = ConversationReadState.Ready(fixture.snapshot)
            vm.retryConversationLoad()
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            assertEquals("keep draft", vm.inputState.textContent.text.toString())
            verify(exactly = 0) { fixture.imports.close() }
            coVerify(exactly = 1) { fixture.application.initialize(fixture.request) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `recent chat write failure stays actionable without revoking the ready chat or clearing input`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(draft = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = java.io.IOException("recent preference write denied", IllegalStateException("disk detail"))
        coEvery { fixture.application.rememberConversation(fixture.lease) } coAnswers {
            entered.complete(Unit)
            release.await()
            throw failure
        }
        try {
            val vm = fixture.create()
            runCurrent()
            assertTrue(entered.isCompleted)
            vm.inputState.setMessageText("unsent message")
            vm.inputState.messageContent = listOf(UIMessagePart.Image("file:///owned/input"))
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            assertEquals("unsent message", vm.inputState.textContent.text.toString())
            assertEquals(listOf(UIMessagePart.Image("file:///owned/input")), vm.inputState.messageContent)
            assertSame(fixture.imports, vm.artifactDraftScope)
            assertFalse(fixture.lease.closed.value)
            val reported = fixture.errors.errors.value.single()
            assertEquals(fixture.request.id, reported.conversationId)
            assertTrue(reported.detail.contains("reopen"))
            assertTrue(reported.detail.contains(failure.userVisibleDiagnostic()))
            verify(exactly = 0) { fixture.imports.close() }
            vm.retryConversationLoad()
            runCurrent()
            coVerify(exactly = 1) { fixture.application.rememberConversation(fixture.lease) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `recent chat cancellation does not become a chat error or close the page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(draft = false)
        coEvery { fixture.application.rememberConversation(fixture.lease) } throws kotlinx.coroutines.CancellationException("write cancelled")
        try {
            val vm = fixture.create()
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            assertFalse(fixture.lease.closed.value)
            assertTrue(fixture.errors.errors.value.isEmpty())
            assertSame(fixture.imports, vm.artifactDraftScope)
            verify(exactly = 0) { fixture.imports.close() }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `favorite read failure is visible on its authorized chat page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val failure = java.io.IOException("favorite index unreadable")
        every { fixture.favorites.observeNodeIds(any()) } returns kotlinx.coroutines.flow.flow { throw failure }
        coEvery { fixture.query.requireViewAccess(fixture.lease) } returns Unit
        try {
            val vm = fixture.create()
            runCurrent()
            assertTrue(vm.favoriteNodeIds.value.isEmpty())
            assertEquals("IOException: favorite index unreadable", fixture.errors.errors.value.single().detail)
            assertEquals(fixture.lease.conversationId, fixture.errors.errors.value.single().conversationId)
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `favorite read boundary failure stays local to favorites`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val failure = java.io.IOException("favorite authorization read unavailable")
        every { fixture.query.observeForView(fixture.lease, emptySet<Uuid>(), any()) } returns
            kotlinx.coroutines.flow.flow { throw failure }
        try {
            val vm = fixture.create()
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            assertTrue(vm.favoriteNodeIds.value.isEmpty())
            assertTrue(fixture.errors.errors.value.single().detail.contains(failure.userVisibleDiagnostic()))
            assertFalse(fixture.lease.closed.value)
            verify(exactly = 0) { fixture.imports.close() }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `configuration failure returns its cause to the active control without a hidden chat error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val failure = IllegalStateException("enterprise_resource_changed")
        coEvery { fixture.configuration.changeAssistantPreference(any(), any()) } throws failure
        try {
            val vm = fixture.create()
            runCurrent()
            val target = ConversationAssistantTarget(fixture.lease.commandTarget, fixture.request.assistantId)
            val result = vm.changeAssistantPreference(target,
                net.weero.measix.pilot.data.configuration.AssistantPreferenceChange.InheritModel)
            assertSame(failure, result.exceptionOrNull())
            assertTrue(fixture.errors.errors.value.isEmpty())
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `configuration cancellation propagates instead of becoming an operation failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val cancellation = kotlinx.coroutines.CancellationException("configuration cancelled")
        coEvery { fixture.configuration.changeAssistantPreference(any(), any()) } throws cancellation
        try {
            val vm = fixture.create()
            runCurrent()
            val target = ConversationAssistantTarget(fixture.lease.commandTarget, fixture.request.assistantId)
            try {
                vm.changeAssistantPreference(target,
                    net.weero.measix.pilot.data.configuration.AssistantPreferenceChange.InheritModel)
                fail("Cancellation must propagate")
            } catch (caught: kotlinx.coroutines.CancellationException) {
                assertSame(cancellation, caught)
            }
            assertTrue(fixture.errors.errors.value.isEmpty())
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `page acquires authorization before any attachment or private subscription and closes on revocation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val pending = CompletableDeferred<ConversationViewLease>()
        coEvery { fixture.application.initialize(fixture.request) } coAnswers { pending.await() }
        try {
            val vm = fixture.create()
            runCurrent()
            assertSame(ConversationReadState.Loading, vm.conversationState.value)
            verify(exactly = 0) { fixture.artifacts.openDraftScope(any()) }
            verify(exactly = 0) { fixture.query.observeConversation(any()) }
            verify(exactly = 0) { fixture.favorites.observeNodeIds(any()) }
            pending.complete(fixture.lease)
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
            fixture.access.value = false
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Failed)
            assertNull(vm.snapshot.value)
            assertNull(vm.conversationUiModel.value)
            assertTrue(vm.favoriteNodeIds.value.isEmpty())
            assertTrue(fixture.lease.closed.value)
            verify(exactly = 1) { fixture.imports.close() }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `missing page is explicit and retry can acquire a valid page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        coEvery { fixture.application.initialize(fixture.request) } throws ConversationNotFoundException(fixture.request.id)
        try {
            val vm = fixture.create()
            runCurrent()
            assertSame(ConversationReadState.Missing, vm.conversationState.value)
            coEvery { fixture.application.initialize(fixture.request) } returns fixture.lease
            vm.retryConversationLoad()
            runCurrent()
            assertTrue(vm.conversationState.value is ConversationReadState.Ready)
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `retry of a rejected original request never captures current access or opens attachments`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val failure = EnterpriseConfigurationException("enterprise_data_access_unavailable")
        coEvery { fixture.application.initialize(fixture.request) } throws failure
        try {
            val vm = fixture.create()
            runCurrent()
            assertSame(failure, (vm.conversationState.value as ConversationReadState.Failed).error)
            vm.retryConversationLoad()
            runCurrent()
            assertSame(failure, (vm.conversationState.value as ConversationReadState.Failed).error)
            assertNull(vm.snapshot.value)
            assertTrue(vm.inputState.textContent.text.isEmpty())
            assertTrue(vm.inputState.messageContent.isEmpty())
            coVerify(exactly = 2) { fixture.application.initialize(fixture.request) }
            coVerify(exactly = 0) { fixture.query.captureCurrentAccess() }
            coVerify(exactly = 0) { fixture.application.newDraftRequest(any<RealmAccess>()) }
            coVerify(exactly = 0) { fixture.application.newDraftRequest(any<net.weero.measix.pilot.data.enterprise.RealmSelection>()) }
            verify(exactly = 0) { fixture.artifacts.openDraftScope(any()) }
            verify(exactly = 0) { fixture.query.observeConversation(any()) }
            verify(exactly = 0) { fixture.favorites.observeNodeIds(any()) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cleared page releases a late authorization result without publishing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val pending = CompletableDeferred<Unit>()
        coEvery { fixture.application.initialize(fixture.request) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await(); fixture.lease }
        }
        try {
            fixture.create()
            runCurrent()
            fixture.store.clear()
            pending.complete(Unit)
            runCurrent()
            assertTrue(fixture.lease.closed.value)
            verify(exactly = 0) { fixture.query.observeConversation(any()) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `retained unsent draft consumes shared input once and preserves subsequent edits`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val uri = Uri.parse("content://test/image")
        coEvery { fixture.imports.importUrisOrThrow(listOf(uri)) } returns listOf(
            ArtifactDraftItem(Uri.parse("file:///owned/image"), "image", "image/png"),
        )
        try {
            val vm = fixture.create()
            runCurrent()
            vm.initializeInput("shared text".base64Encode(), listOf(uri))
            assertEquals("shared text", vm.inputState.textContent.text.toString())
            assertEquals(listOf(UIMessagePart.Image("file:///owned/image")), vm.inputState.messageContent)
            vm.inputState.setMessageText("edited")
            vm.inputState.messageContent = emptyList()
            val retained = fixture.create()
            assertSame(vm, retained)
            retained.initializeInput("shared text".base64Encode(), listOf(uri))
            assertEquals("edited", retained.inputState.textContent.text.toString())
            assertTrue(retained.inputState.messageContent.isEmpty())
            coVerify(exactly = 1) { fixture.imports.importUrisOrThrow(any()) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `restored committed page never replays shared input`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(draft = false)
        try {
            val vm = fixture.create()
            runCurrent()
            vm.initializeInput("shared text".base64Encode(), listOf(Uri.parse("content://test/image")))
            assertTrue(vm.inputState.textContent.text.isEmpty())
            coVerify(exactly = 0) { fixture.imports.importUrisOrThrow(any()) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `retry after revocation does not retain attachments owned by the closed editor`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val uri = Uri.parse("content://test/image")
        coEvery { fixture.imports.importUrisOrThrow(listOf(uri)) } returns listOf(
            ArtifactDraftItem(Uri.parse("file:///owned/old"), "image", "image/png"),
        )
        try {
            val vm = fixture.create()
            runCurrent()
            vm.initializeInput("share".base64Encode(), listOf(uri))
            fixture.access.value = false
            runCurrent()
            assertTrue(vm.inputState.textContent.text.isEmpty())
            assertTrue(vm.inputState.messageContent.isEmpty())
            val nextLease = ConversationViewLease(fixture.request.id, fixture.request.access, 0) {}
            val nextImports = mockk<ArtifactDraftScope>()
            every { nextImports.close() } returns Unit
            coEvery { nextImports.importUrisOrThrow(listOf(uri)) } returns listOf(
                ArtifactDraftItem(Uri.parse("file:///owned/new"), "image", "image/png"),
            )
            coEvery { fixture.application.initialize(fixture.request) } returns nextLease
            every { fixture.artifacts.openDraftScope(any()) } returns nextImports
            fixture.access.value = true
            vm.retryConversationLoad()
            runCurrent()
            vm.initializeInput("share".base64Encode(), listOf(uri))
            assertEquals(listOf(UIMessagePart.Image("file:///owned/new")), vm.inputState.messageContent)
            coVerify(exactly = 1) { fixture.imports.importUrisOrThrow(any()) }
            coVerify(exactly = 1) { nextImports.importUrisOrThrow(any()) }
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `answer handler retains its original page across retry and reports a rejected answer`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            val vm = fixture.create()
            runCurrent()
            val oldHandler = requireNotNull(vm.subAssistantAnswerHandler())
            fixture.access.value = false
            runCurrent()
            val nextLease = ConversationViewLease(fixture.request.id, fixture.request.access, 1) {}
            coEvery { fixture.application.initialize(fixture.request) } returns nextLease
            fixture.access.value = true
            vm.retryConversationLoad()
            runCurrent()
            coEvery { fixture.application.answerSubAssistant(any(), "run", "ask", "answer") } coAnswers {
                firstArg<ConversationCommandTarget>() === nextLease.commandTarget
            }
            assertFalse(oldHandler("run", "ask", "answer"))
            coVerify { fixture.application.answerSubAssistant(fixture.lease.commandTarget, "run", "ask", "answer") }
            assertEquals(1, fixture.errors.errors.value.size)
            assertTrue(requireNotNull(vm.subAssistantAnswerHandler())("run", "ask", "answer"))
            coEvery { fixture.application.answerSubAssistant(any(), "run", "ask", "answer") } throws kotlinx.coroutines.CancellationException("cancelled")
            try { requireNotNull(vm.subAssistantAnswerHandler())("run", "ask", "answer"); fail("Cancellation must propagate") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, fixture.errors.errors.value.size)
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `tool decision handler awaits acceptance and keeps the original page target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            val vm = fixture.create()
            runCurrent()
            val handler = requireNotNull(vm.toolDecisionHandler())
            val locator = me.rerere.ai.core.ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random())
            val decision = net.weero.measix.pilot.service.runtime.ToolInteractionDecision.Approve
            fixture.access.value = false
            runCurrent()
            val nextLease = ConversationViewLease(fixture.request.id, fixture.request.access, 1) {}
            coEvery { fixture.application.initialize(fixture.request) } returns nextLease
            fixture.access.value = true
            vm.retryConversationLoad(); runCurrent()
            coEvery { fixture.turns.submitToolDecision(fixture.lease.commandTarget, locator, decision) } throws IllegalStateException("closed")
            assertFalse(handler(locator, decision))
            assertEquals(1, fixture.errors.errors.value.size)
            coEvery { fixture.turns.submitToolDecision(nextLease.commandTarget, locator, decision) } throws kotlinx.coroutines.CancellationException("cancelled")
            try { requireNotNull(vm.toolDecisionHandler())(locator, decision); fail("Cancellation must propagate") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, fixture.errors.errors.value.size)
            coEvery { fixture.turns.submitToolDecision(nextLease.commandTarget, locator, decision) } returns Unit
            assertTrue(requireNotNull(vm.toolDecisionHandler())(locator, decision))
        } finally { fixture.store.clear(); Dispatchers.resetMain() }
    }

    private class Fixture(draft: Boolean = true) {
        val store = ViewModelStore()
        val request = ConversationOpenRequest.NewDraft(Uuid.random(), RealmAccess.Personal, ConfigurationReference.random())
        val lease = ConversationViewLease(request.id, request.access, 0) {}
        val application = mockk<ConversationApplicationService>()
        val query = mockk<ConversationQueryService>()
        val artifacts = mockk<ArtifactUseCase>()
        val imports = mockk<ArtifactDraftScope>()
        val favorites = mockk<FavoriteService>()
        val access = MutableStateFlow(true)
        private val settings = mockk<SettingsStore>()
        val errors = ChatErrorStore()
        val configuration = mockk<ConfigurationApplicationService>()
        val turns = mockk<net.weero.measix.pilot.service.ConversationTurnService>()
        private val updater = mockk<UpdateChecker>()
        val snapshot = ConversationRuntimeSnapshot(
            Conversation.ofId(request.id, request.assistantId, newConversation = draft).toSnapshot(), null,
        ).toPresentationSnapshot()
        val model = ConversationUiModel(snapshot, ConversationPresentation.IDLE)
        val reads = MutableStateFlow<ConversationReadState>(ConversationReadState.Ready(snapshot))
        val models = MutableStateFlow<ConversationUiModel?>(model)
        init {
            coEvery { application.initialize(request) } returns lease
            coEvery { application.rememberConversation(lease) } returns Unit
            every { artifacts.openDraftScope(any()) } returns imports
            every { imports.close() } returns Unit
            every { settings.userSettings } returns MutableStateFlow(net.weero.measix.pilot.data.datastore.Settings.dummy())
            every { updater.updateState } returns MutableStateFlow(UiState.Idle)
            every { favorites.observeNodeIds(any()) } returns flowOf(setOf(Uuid.random()))
            every { query.observeViewAccess(any()) } returns access
            coEvery { query.requireViewAccess(any()) } returns Unit
            every { query.observeForView<Any?>(any(), any(), any()) } answers { thirdArg<() -> Flow<Any?>>()() }
            every { query.observeConversation(any()) } returns reads
            every { query.conversationUiModel(any()) } returns models
        }

        fun create(): ChatVM = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatVM(
                request, mockk<Application> {
                    every { getString(net.weero.measix.pilot.R.string.error_title_operation) } returns "Operation failed"
                    every { getString(net.weero.measix.pilot.R.string.chat_recent_conversation_save_failed, *anyVararg()) } answers {
                        "Recent chat preference could not be saved; reopen to retry.\n\n" + secondArg<Array<Any>>().single()
                    }
                }, settings, turns, application,
                query, updater, artifacts, favorites, errors, configuration, mockk { every { summary } returns kotlinx.coroutines.flow.MutableStateFlow(null) },
            ) as T
        })[ChatVM::class.java]
    }
}
