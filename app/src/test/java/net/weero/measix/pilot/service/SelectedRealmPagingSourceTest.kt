package net.weero.measix.pilot.service

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectedRealmPagingSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val params = PagingSource.LoadParams.Refresh<Int>(null, 20, false)

    @Test fun `lazy loads reject another selection and cannot revive after exit and reentry`() = runTest {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        sessions.enrollFixture(packet)
        val access = sessions.captureSelectedRealmAccess()
        val delegate = Source()
        val page = SelectedRealmPagingSource(delegate, sessions, access)
        assertTrue(page.load(params) is PagingSource.LoadResult.Page)
        sessions.switchToPersonal()
        assertTrue(page.load(params) is PagingSource.LoadResult.Error)
        assertEquals(1, delegate.loads)
        sessions.switchToEnterprise()
        sessions.finishExit(requireNotNull(sessions.beginExit()))
        sessions.enrollFixture(packet)
        assertNotEquals(access, sessions.captureSelectedRealmAccess())
        assertTrue(page.load(params) is PagingSource.LoadResult.Error)
        assertEquals(1, delegate.loads)
    }

    @Test fun `expiry blocks queued load without a manifest change`() = runTest {
        var now = 1_800_000_000_000L
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val state = sessions.enrollFixture(exampleEnterprisePackage())
        val delegate = Source()
        val page = SelectedRealmPagingSource(delegate, sessions, sessions.captureSelectedRealmAccess())
        now = requireNotNull(state.manifest.session).expiresAtMillis
        assertTrue(page.load(params) is PagingSource.LoadResult.Error)
        assertEquals(0, delegate.loads)
    }

    @Test fun `invalidation is mutual and cancellation is not converted to load error`() = runTest {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        sessions.recover()
        val access = sessions.captureSelectedRealmAccess()
        val delegate = Source()
        val page = SelectedRealmPagingSource(delegate, sessions, access)
        page.invalidate()
        assertTrue(delegate.invalid)
        assertTrue(page.load(params) is PagingSource.LoadResult.Invalid)
        assertEquals(0, delegate.loads)
        val other = Source()
        val wrapper = SelectedRealmPagingSource(other, sessions, access)
        other.invalidate()
        assertTrue(wrapper.invalid)
        val cancelled = SelectedRealmPagingSource(Source(cancel = true), sessions, access)
        try {
            cancelled.load(params)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    private class Source(private val cancel: Boolean = false) : PagingSource<Int, String>() {
        var loads = 0
        override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
            loads++
            if (cancel) throw CancellationException("cancelled")
            return LoadResult.Page(listOf("row"), null, null)
        }
    }
}
