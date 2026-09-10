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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPageLifecycleTest {
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
            val nextLease = ConversationViewLease(fixture.request.id, fixture.request.access, 1) {}
            coEvery { fixture.application.initialize(fixture.request) } returns nextLease
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
            val nextLease = ConversationViewLease(fixture.request.id, fixture.request.access, 1) {}
            coEvery { fixture.application.initialize(fixture.request) } returns nextLease
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
        init {
            coEvery { application.initialize(request) } returns lease
            coEvery { application.rememberConversation(lease) } returns Unit
            every { artifacts.openDraftScope(any()) } returns imports
            every { imports.close() } returns Unit
            every { settings.userSettings } returns MutableStateFlow(net.weero.measix.pilot.data.datastore.Settings.dummy())
            every { updater.updateState } returns MutableStateFlow(UiState.Idle)
            every { favorites.observeNodeIds(any()) } returns flowOf(setOf(Uuid.random()))
            every { query.observeViewAccess(any()) } returns access
            every { query.observeForView<Any?>(any(), any(), any()) } answers { thirdArg<() -> Flow<Any?>>()() }
            val snapshot = ConversationRuntimeSnapshot(
                Conversation.ofId(request.id, request.assistantId, newConversation = draft).toSnapshot(), null,
            ).toPresentationSnapshot()
            every { query.observeConversation(any()) } returns flowOf(ConversationReadState.Ready(snapshot))
            every { query.turnPresentation(any()) } returns flowOf(ConversationPresentation.IDLE)
            every { query.conversationUiModel(any()) } returns flowOf(ConversationUiModel(snapshot, ConversationPresentation.IDLE))
        }

        fun create(): ChatVM = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatVM(
                request, mockk<Application> { every { getString(net.weero.measix.pilot.R.string.error_title_operation) } returns "Operation failed" }, settings, turns, application,
                query, updater, artifacts, favorites, errors, configuration,
            ) as T
        })[ChatVM::class.java]
    }
}
