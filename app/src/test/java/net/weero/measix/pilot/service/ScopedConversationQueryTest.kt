package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.selectPersonalFixture
import net.weero.measix.pilot.data.enterprise.selectEnterpriseFixture

import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.tools.createConversationTools
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.dao.LightConversationEntity
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.repository.ConversationListRecord
import net.weero.measix.pilot.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScopedConversationQueryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = ConfigurationReference.random()

    @Test fun `conversation tools retain their original scope and session across selection and reentry`() = runTest {
        val sessions = sessions()
        val packet = exampleEnterprisePackage()
        sessions.enrollFixture(packet)
        val access = sessions.captureSelectedRealmAccess()
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getRecentConversationRecords(access.scope, assistant, 10) } returns emptyList()
        coEvery { repository.searchMessagesOfAssistant(access.scope, assistant, "needle", MessageSearchSort.RELEVANCE) } returns emptyList()
        val tools = createConversationTools(service(repository, sessions), assistant, access)
        sessions.selectPersonalFixture()
        val arguments = buildJsonObject { put("query", "needle") }
        tools.forEach { it.execute(arguments) }
        coVerify(exactly = 1) { repository.getRecentConversationRecords(access.scope, assistant, 10) }
        coVerify(exactly = 1) { repository.searchMessagesOfAssistant(access.scope, assistant, "needle", MessageSearchSort.RELEVANCE) }
        sessions.finishExit(sessions.beginExit(requireNotNull(sessions.captureExitRequest())))
        sessions.enrollFixture(packet)
        tools.forEach { tool ->
            try {
                tool.execute(arguments)
                fail("Old tool session must not be recaptured")
            } catch (_: EnterpriseConfigurationException) { }
        }
        coVerify(exactly = 1) { repository.getRecentConversationRecords(any(), any(), any()) }
        coVerify(exactly = 1) { repository.searchMessagesOfAssistant(any(), any(), any(), any()) }
    }

    @Test fun `directory clears failed old rows and continues observing realm switches`() = runTest {
        val sessions = sessions()
        val packet = exampleEnterprisePackage()
        sessions.enrollFixture(packet)
        val repository = mockk<ConversationRepository>()
        val enterpriseRow = row(packet.identity.scope, "enterprise")
        val personalRow = row(ConfigurationScope.Personal, "personal")
        var failEnterprise = false
        every { repository.getConversationsOfAssistant(packet.identity.scope, assistant) } answers {
            kotlinx.coroutines.flow.flow {
                if (failEnterprise) throw IllegalStateException("read failure")
                emit(listOf(enterpriseRow))
            }
        }
        every { repository.getConversationsOfAssistant(ConfigurationScope.Personal, assistant) } returns flowOf(listOf(personalRow))
        var latest: List<ConversationSummary> = emptyList()
        val job = backgroundScope.launch { service(repository, sessions).conversationsOfAssistant(assistant).collect { latest = it } }
        runCurrent()
        assertEquals(listOf("enterprise"), latest.map { it.title })
        sessions.selectPersonalFixture()
        runCurrent()
        assertEquals(listOf("personal"), latest.map { it.title })
        failEnterprise = true
        sessions.selectEnterpriseFixture()
        runCurrent()
        assertTrue(latest.isEmpty())
        sessions.selectPersonalFixture()
        runCurrent()
        assertEquals(listOf("personal"), latest.map { it.title })
        job.cancel()
    }

    @Test fun `actual Pager invalidates sources on switch and replaces cached data before reentry`() = runTest {
        val sessions = sessions()
        sessions.enrollFixture(exampleEnterprisePackage())
        val repository = mockk<ConversationRepository>()
        val sources = mutableListOf<Rows>()
        every { repository.unfiledPagingSource(any(), assistant) } answers {
            Rows(firstArg()).also(sources::add)
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<ConversationSummary>() {
                override fun areItemsTheSame(oldItem: ConversationSummary, newItem: ConversationSummary) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: ConversationSummary, newItem: ConversationSummary) = oldItem == newItem
            },
            updateCallback = object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            },
            mainDispatcher = dispatcher,
            workerDispatcher = dispatcher,
        )
        val job = backgroundScope.launch {
            service(repository, sessions).unfiledPaging(assistant).collectLatest(differ::submitData)
        }
        runCurrent()
        assertEquals(1, sources.size)
        val original = sources.single()
        assertEquals(listOf("enterprise"), differ.snapshot().items.map { it.title })
        sessions.selectPersonalFixture()
        runCurrent()
        assertTrue(original.invalid)
        assertEquals(listOf("personal"), differ.snapshot().items.map { it.title })
        sessions.selectEnterpriseFixture()
        runCurrent()
        assertTrue(original.invalid)
        assertEquals(3, sources.size)
        assertEquals(listOf("enterprise"), differ.snapshot().items.map { it.title })
        val oldRow = differ.snapshot().items.single()
        val oldSource = sources.last()
        sessions.selectPersonalFixture()
        sessions.selectEnterpriseFixture()
        runCurrent()
        assertTrue(oldSource.invalid)
        assertTrue(sources.dropLast(1).all { it.invalid })
        assertNotEquals(oldRow.selection, differ.snapshot().items.single().selection)
        job.cancel()
        runCurrent()
        assertTrue(sources.all { it.invalid })
    }

    private fun sessions() = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
    private fun service(repository: ConversationRepository, sessions: EnterpriseSessionController) = ConversationQueryService(
        repository, mockk(), mockk(), mockk(), mockk(), sessions, ApplicationRecoveryGate().apply { ready() },
    )
    private fun row(scope: ConfigurationScope, title: String) = ConversationListRecord(
        Uuid.random(), assistant, title, null, false, Instant.EPOCH, Instant.EPOCH, scope,
    )
    private inner class Rows(scope: ConfigurationScope) : PagingSource<Int, LightConversationEntity>() {
        private val row = LightConversationEntity(Uuid.random().toString(), assistant.toString(),
            if (scope == ConfigurationScope.Personal) "personal" else "enterprise", false, 0, 0, "", scope)
        override fun getRefreshKey(state: PagingState<Int, LightConversationEntity>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LightConversationEntity> =
            LoadResult.Page(listOf(row), null, null)
    }
}
