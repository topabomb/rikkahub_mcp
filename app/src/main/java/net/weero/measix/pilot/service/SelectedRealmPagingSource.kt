package net.weero.measix.pilot.service

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection

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
