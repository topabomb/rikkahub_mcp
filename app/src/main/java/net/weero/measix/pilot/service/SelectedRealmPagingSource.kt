package net.weero.measix.pilot.service

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.RealmSelection

/** A replaced directory invalidates both active sources and pages collected later. */
internal fun <Row : Any, Ui : Any> selectedRealmPaging(
    sessions: EnterpriseSessionController,
    selection: RealmSelection,
    config: PagingConfig,
    source: () -> PagingSource<Int, Row>,
    project: (Row) -> Ui,
): Flow<PagingData<Ui>> = flow {
    val owner = Any()
    val active = mutableSetOf<PagingSource<Int, Row>>()
    var closed = false
    try {
        emit(PagingData.empty())
        val pager = Pager(config) {
            synchronized(owner) {
                SelectedRealmPagingSource(source(), sessions, selection).also { page ->
                    if (closed) page.invalidate() else {
                        active.add(page)
                        page.registerInvalidatedCallback { synchronized(owner) { active.remove(page) } }
                    }
                }
            }
        }
        emitAll(pager.flow.map { data ->
            data.filter {
                try {
                    sessions.withSelectedRealmSelection(selection) { true }
                } catch (_: EnterpriseConfigurationException) {
                    false
                }
            }.map(project)
        })
    } finally {
        synchronized(owner) {
            closed = true
            active.toList().forEach { it.invalidate() }
            active.clear()
        }
    }
}

/** A lazy page load retains the originating selection and session, including after its flow is replaced. */
internal class SelectedRealmPagingSource<Key : Any, Value : Any>(
    private val delegate: PagingSource<Key, Value>,
    private val sessions: EnterpriseSessionController,
    private val access: RealmSelection,
) : PagingSource<Key, Value>() {
    init {
        delegate.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(delegate::invalidate)
    }

    override val jumpingSupported: Boolean get() = delegate.jumpingSupported
    override val keyReuseSupported: Boolean get() = delegate.keyReuseSupported
    override fun getRefreshKey(state: PagingState<Key, Value>): Key? = delegate.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> = try {
        if (invalid) LoadResult.Invalid() else sessions.withSelectedRealmSelection(access) {
            if (invalid) LoadResult.Invalid() else delegate.load(params)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LoadResult.Error(error)
    }
}
