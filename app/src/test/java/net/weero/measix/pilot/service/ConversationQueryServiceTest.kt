package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeState
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationQueryServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test
    fun `runtime failure remains a diagnostic read state`() = runTest {
        val error = IllegalStateException("corrupt message payload")
        val state = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Failed(error))
        val service = service(state)

        val observed = service.observeConversation(ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0) {}).first()

        assertTrue(observed is ConversationReadState.Failed)
        assertSame(error, (observed as ConversationReadState.Failed).error)
    }

    @Test
    fun `ready runtime projects its live snapshot without a nullable fallback`() = runTest {
        val conversation = Conversation.ofId(Uuid.random(), ConfigurationReference.random())
        val runtime = mockk<ConversationRuntime>()
        every { runtime.snapshot } returns MutableStateFlow(
            ConversationRuntimeSnapshot(durable = conversation.toSnapshot(), stream = null),
        )
        val service = service(MutableStateFlow(ConversationRuntimeState.Ready(runtime)))

        val observed = service.observeConversation(ConversationViewLease(conversation.id, RealmAccess.Personal, 0) {}).first()

        assertTrue(observed is ConversationReadState.Ready)
        assertEquals(conversation.id, (observed as ConversationReadState.Ready).snapshot.conversationId)
    }

    private suspend fun service(state: MutableStateFlow<ConversationRuntimeState>): ConversationQueryService {
        val registry = mockk<ConversationRuntimeRegistry>()
        every { registry.observeRuntimeState(any()) } returns state
        return ConversationQueryService(
            repository = mockk<ConversationRepository>(relaxed = true),
            runtimeRegistry = registry,
            folderRepository = mockk<FolderRepository>(relaxed = true),
            titleCoordinator = mockk<ConversationTitleCoordinator>(relaxed = true),
            attachmentPreviewProjector = mockk(relaxed = true),
            sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())).apply { recover() },
            recoveryGate = ApplicationRecoveryGate().also { it.ready() },
            settings = mockk(),
            coordinator = mockk(),
        )
    }
}
