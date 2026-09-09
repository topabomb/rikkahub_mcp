package net.weero.measix.pilot.service.workspace

import android.content.Context
import android.os.Looper
import android.util.Log
import com.termux.view.TerminalView
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection

/** Application owner of interactive PTYs. Entries and viewports are confined to Main. */
class WorkspaceTerminalRuntime internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val sessions: EnterpriseSessionController,
    private val terminalHost: WorkspaceTerminalHost = AndroidWorkspaceTerminalHost,
) {
    private val context = context.applicationContext
    private val entries = LinkedHashMap<String, TerminalEntry>()
    private val mutableWorkspaces = MutableStateFlow<Map<WorkspaceTerminalOwner, WorkspaceTerminalWorkspaceState>>(emptyMap())
    internal val workspaces = mutableWorkspaces.asStateFlow()

    suspend fun create(
        root: String,
        selection: RealmSelection,
        prepareUnderCommandGate: suspend (suspend () -> Boolean) -> Boolean,
    ): WorkspaceTerminalCreateResult = selected(selection) {
        val owner = WorkspaceTerminalOwner(root, selection.access)
        if (entries.values.count { it.owner.root == root } >= MAX_TABS_PER_WORKSPACE) {
            return@selected WorkspaceTerminalCreateResult.LimitReached(MAX_TABS_PER_WORKSPACE)
        }
        appScope.coroutineContext.ensureActive()
        val entry = TerminalEntry(UUID.randomUUID().toString(), owner,
            (entries.values.filter { it.owner == owner }.maxOfOrNull { it.number } ?: 0) + 1)
        entries[entry.id] = entry
        publish(owner, entry.id)
        entry.creation = appScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.ATOMIC) { createSession(entry, prepareUnderCommandGate) }
        WorkspaceTerminalCreateResult.Created(entry.id)
    }

    suspend fun select(root: String, selection: RealmSelection, tabId: String) = selected(selection) {
        owned(root, selection, tabId)?.let { publish(it.owner, tabId) }
    }

    suspend fun rename(root: String, selection: RealmSelection, tabId: String, title: String) = selected(selection) {
        owned(root, selection, tabId)?.let {
            it.customTitle = title.trim().take(MAX_TITLE_LENGTH).ifBlank { null }
            publish(it.owner)
        }
    }

    suspend fun reorder(root: String, selection: RealmSelection, orderedIds: List<String>) = selected(selection) {
        val owner = WorkspaceTerminalOwner(root, selection.access)
        val current = entries.values.filter { it.owner == owner }.associateBy { it.id }
        if (orderedIds.size != current.size || orderedIds.toSet() != current.keys) return@selected
        current.keys.forEach(entries::remove)
        orderedIds.forEach { entries[it] = current.getValue(it) }
        publish(owner)
    }

    suspend fun close(root: String, selection: RealmSelection, tabId: String) {
        var admitted: TerminalEntry? = null
        var failure: Throwable? = null
        try {
            selected(selection) { owned(root, selection, tabId)?.let { admitted = it; beginClose(it) } }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { withContext(NonCancellable) { admitted?.let { closeEntry(it) } } }
            catch (cleanup: Throwable) { failure?.let { if (cleanup !== it) it.addSuppressed(cleanup) } ?: throw cleanup }
        }
    }

    suspend fun closeWorkspace(root: String) = closeMatching { it.owner.root == root }
    internal suspend fun closeRealm(access: RealmAccess) = closeMatching { it.owner.access == access }

    /** Called before publishing the replacement selection. No Session or Workspace lock is acquired. */
    internal suspend fun revokeViewports(access: RealmAccess) = withContext(Dispatchers.Main.immediate) {
        entries.values.filter { it.owner.access == access }.forEach(::retireViewport)
    }

    suspend fun bind(selection: RealmSelection, tabId: String, view: TerminalView): Boolean = selected(selection) {
        val entry = ownedForView(selection, tabId) ?: return@selected false
        if (entry.readiness != WorkspaceTerminalReadiness.READY || view.isRetired) return@selected false
        val live = entry.live ?: return@selected false
        if (entry.viewport?.view !== view) {
            retireViewport(entry)
            entry.viewport = BoundViewport(view, selection, CoroutineScope(appScope.coroutineContext +
                SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Main.immediate))
        }
        val bound = requireNotNull(entry.viewport)
        live.client.terminalView = view
        view.setActionDispatcher(object : TerminalView.ActionDispatcher {
            override fun dispatch(action: Runnable) = dispatchAction(entry, bound) { action.run() }
        })
        view.attachSession(live.session)
        view.onScreenUpdated()
        true
    }

    fun unbind(tabId: String, view: TerminalView) {
        requireMain()
        val entry = entries[tabId]
        if (entry?.viewport?.view === view) retireViewport(entry) else view.retire()
    }

    fun write(selection: RealmSelection, tabId: String, text: String) {
        requireMain()
        val entry = ownedForView(selection, tabId) ?: return
        val bound = entry.viewport?.takeIf { it.selection == selection } ?: return
        val session = entry.live?.session ?: return
        dispatchAction(entry, bound) { session.write(text) }
    }

    private fun ownedForView(selection: RealmSelection, tabId: String) = entries[tabId]?.takeIf {
        it.owner.access == selection.access && it.readiness != WorkspaceTerminalReadiness.CLOSING
    }

    /** Short UI actions wait for the original selection without treating contention as revocation. */
    private fun dispatchAction(entry: TerminalEntry, bound: BoundViewport, action: () -> Unit): Boolean {
        requireMain()
        if (entries[entry.id] !== entry || entry.viewport !== bound || bound.view.isRetired || !bound.scope.isActive) return false
        bound.scope.launch {
            try {
                sessions.withSelectedRealmSelection(bound.selection) {
                    if (entries[entry.id] === entry && entry.viewport === bound && !bound.view.isRetired &&
                        entry.readiness == WorkspaceTerminalReadiness.READY) action()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error !is EnterpriseConfigurationException) {
                    Log.w(TAG, "Terminal input failed", error)
                    try {
                        beginClose(entry)
                        publishFailure(entry.owner, WorkspaceTerminalFailureReason.Unexpected)
                    } finally { entry.writing?.cancel() }
                }
            }
        }
        return true
    }

    /** Includes bytes being drained, so a blocked child cannot grow the queue without bound. */
    private fun enqueue(entry: TerminalEntry, session: WorkspacePtySession, data: ByteArray, offset: Int, count: Int) {
        requireMain()
        if (entries[entry.id] !== entry || entry.live?.session !== session ||
            entry.readiness != WorkspaceTerminalReadiness.READY || count == 0) return
        if (count <= MAX_PENDING_INPUT_BYTES - entry.pendingBytes) {
            entry.pendingInput.write(data, offset, count)
            entry.pendingBytes += count
            if (entry.inputAvailable.trySend(Unit).isSuccess) return
        }
        try {
            beginClose(entry)
            publishFailure(entry.owner, WorkspaceTerminalFailureReason.Unexpected)
        } finally {
            entry.writing?.cancel(CancellationException("workspace_terminal_input_limit"))
        }
    }

    private fun startWriter(entry: TerminalEntry, session: WorkspacePtySession): Job =
        appScope.launch(Dispatchers.Main, start = CoroutineStart.ATOMIC) {
            try {
                // Cleanup is inside coroutineScope: kill the PTY before waiting for a blocked IO child.
                coroutineScope {
                    try {
                        for (signal in entry.inputAvailable) {
                            if (entry.pendingInput.size() == 0) continue
                            val bytes = entry.pendingInput.toByteArray()
                            entry.pendingInput.reset()
                            async(Dispatchers.IO) { session.drain(bytes) }.await()
                            entry.pendingBytes -= bytes.size
                        }
                    } finally {
                        withContext(NonCancellable) {
                            var failure: Throwable? = null
                            try { beginClose(entry) }
                            catch (error: Throwable) { failure = error; throw error }
                            finally {
                                try { stopSession(entry) }
                                catch (cleanup: Throwable) {
                                    failure?.let { if (cleanup !== it) it.addSuppressed(cleanup) } ?: throw cleanup
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w(TAG, "Terminal writer failed", error)
                publishFailure(entry.owner, WorkspaceTerminalFailureReason.Unexpected)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    entry.inputAvailable.cancel()
                    entry.pendingInput.reset()
                    entry.pendingBytes = 0
                    entry.writing = null
                    removeIfStopped(entry)
                }
            }
        }

    private suspend fun <T> selected(selection: RealmSelection, action: () -> T): T =
        sessions.withSelectedRealmSelection(selection) { withContext(Dispatchers.Main.immediate) { action() } }

    private fun owned(root: String, selection: RealmSelection, id: String) = entries[id]?.takeIf {
        it.owner == WorkspaceTerminalOwner(root, selection.access)
    }

    private suspend fun createSession(entry: TerminalEntry, prepare: suspend (suspend () -> Boolean) -> Boolean) {
        try {
            val ready = prepare { runInterruptible(Dispatchers.IO) { terminalHost.prepare(context, entry.owner.root) } }
            if (!ready) {
                withContext(Dispatchers.Main.immediate) { remove(entry); publishFailure(entry.owner, WorkspaceTerminalFailureReason.NotReady) }
                return
            }
            sessions.withRealmAccess(entry.owner.access) {
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    if (entries[entry.id] !== entry || entry.readiness == WorkspaceTerminalReadiness.CLOSING) return@withContext
                    val ended = CompletableDeferred<Unit>()
                    val client = WorkspaceTerminalSessionClient(context,
                        onAction = { action -> entry.viewport?.let { dispatchAction(entry, it, action) } ?: false },
                        onWrite = { session, data, offset, count -> enqueue(entry, session, data, offset, count) },
                        onFinished = {
                            ended.complete(Unit)
                            entry.inputAvailable.cancel()
                            entry.readiness = WorkspaceTerminalReadiness.CLOSING
                            try {
                                retireViewport(entry)
                                if (entry.writing == null) remove(entry) else publish(entry.owner)
                            }
                            catch (error: Exception) {
                                entry.readiness = WorkspaceTerminalReadiness.CLOSING
                                publishFailure(entry.owner, WorkspaceTerminalFailureReason.Unexpected)
                                Log.w(TAG, "Terminal viewport cleanup failed", error)
                            }
                        })
                    val session = terminalHost.create(context, entry.owner.root, client)
                    entry.live = LiveTerminal(session, client, ended)
                    entry.readiness = WorkspaceTerminalReadiness.READY
                    entry.writing = startWriter(entry, session)
                    publish(entry.owner)
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                val needsCleanup = withContext(Dispatchers.Main.immediate) {
                    if (entries[entry.id] !== entry || entry.readiness == WorkspaceTerminalReadiness.CLOSING) false
                    else { beginClose(entry); true }
                }
                if (needsCleanup) {
                    try { stopSession(entry) }
                    catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    withContext(Dispatchers.Main.immediate) {
                        if (entry.live == null) remove(entry) else removeIfStopped(entry)
                        publishFailure(entry.owner, WorkspaceTerminalFailureReason.Unexpected)
                    }
                }
            }
            if (error is CancellationException) throw error
            Log.w(TAG, "Terminal creation failed", error)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { entry.creation = null }
        }
    }

    private suspend fun closeMatching(predicate: (TerminalEntry) -> Boolean) {
        var admitted = emptyList<TerminalEntry>()
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                admitted = entries.values.filter(predicate)
                admitted.forEach(::beginClose)
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            var cleanupFailure: Throwable? = null
            withContext(NonCancellable) {
                admitted.forEach { entry ->
                    try { closeEntry(entry) }
                    catch (error: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = error
                        else if (cleanupFailure !== error) cleanupFailure!!.addSuppressed(error)
                    }
                }
            }
            cleanupFailure?.let { cleanup -> failure?.let { if (cleanup !== it) it.addSuppressed(cleanup) } ?: throw cleanup }
        }
    }

    private fun beginClose(entry: TerminalEntry) {
        entry.readiness = WorkspaceTerminalReadiness.CLOSING
        retireViewport(entry)
        publish(entry.owner)
    }

    private suspend fun closeEntry(entry: TerminalEntry) = entry.closeGate.withLock {
        withContext(Dispatchers.Main.immediate) { entry.creation }?.cancelAndJoin()
        stopSession(entry)
        withContext(Dispatchers.Main.immediate) { entry.inputAvailable.cancel(); entry.writing }?.join()
        withContext(Dispatchers.Main.immediate) { retireViewport(entry); remove(entry) }
    }

    private suspend fun stopSession(entry: TerminalEntry) {
        val live = withContext(Dispatchers.Main.immediate) {
            entry.live?.also {
                // Termux starts lazily on the first viewport size; killing PID 0 would target the process group.
                if (it.session.pid == 0) it.ended.complete(Unit)
                else if (!it.ended.isCompleted) it.session.finishIfRunning()
            }
        } ?: return
        check(withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { live.ended.await(); true } == true) { "workspace_terminal_close_timeout" }
    }

    private fun retireViewport(entry: TerminalEntry) {
        entry.live?.client?.terminalView = null
        entry.viewport?.scope?.cancel()
        entry.viewport?.view?.retire()
        entry.viewport = null
    }

    private fun removeIfStopped(entry: TerminalEntry) {
        if (entry.live?.ended?.isCompleted == true && entry.writing == null) {
            retireViewport(entry)
            remove(entry)
        }
    }

    private fun remove(entry: TerminalEntry) {
        if (entries[entry.id] !== entry) return
        entries.remove(entry.id)
        publish(entry.owner)
    }

    private fun publish(owner: WorkspaceTerminalOwner, selected: String? = mutableWorkspaces.value[owner]?.selectedTabId) {
        val tabs = entries.values.filter { it.owner == owner }.map {
            WorkspaceTerminalTabUiModel(it.id, it.number, it.customTitle, it.readiness)
        }
        val failure = mutableWorkspaces.value[owner]?.lastFailure
        mutableWorkspaces.value = if (tabs.isEmpty() && failure == null) mutableWorkspaces.value - owner
        else mutableWorkspaces.value + (owner to WorkspaceTerminalWorkspaceState(tabs,
            selected?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.lastOrNull()?.id, failure))
    }

    private fun publishFailure(owner: WorkspaceTerminalOwner, reason: WorkspaceTerminalFailureReason) {
        val current = mutableWorkspaces.value[owner] ?: WorkspaceTerminalWorkspaceState()
        mutableWorkspaces.value += owner to current.copy(lastFailure = WorkspaceTerminalFailure(UUID.randomUUID().toString(), reason))
    }

    private class TerminalEntry(val id: String, val owner: WorkspaceTerminalOwner, val number: Int) {
        var customTitle: String? = null
        var readiness = WorkspaceTerminalReadiness.PREPARING
        var creation: Job? = null
        var live: LiveTerminal? = null
        var viewport: BoundViewport? = null
        val closeGate = Mutex()
        val pendingInput = ByteArrayOutputStream()
        val inputAvailable = Channel<Unit>(Channel.CONFLATED)
        var pendingBytes = 0
        var writing: Job? = null
    }
    private data class BoundViewport(val view: TerminalView, val selection: RealmSelection, val scope: CoroutineScope)
    private data class LiveTerminal(val session: WorkspacePtySession, val client: WorkspaceTerminalSessionClient, val ended: CompletableDeferred<Unit>)

    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    companion object {
        const val MAX_TABS_PER_WORKSPACE = 6
        private const val MAX_PENDING_INPUT_BYTES = 4 * 1024 * 1024
        private const val MAX_TITLE_LENGTH = 48
        private const val CLOSE_TIMEOUT_MILLIS = 10_000L
        private const val TAG = "WorkspaceTerminalRuntime"
    }
}

internal data class WorkspaceTerminalOwner(val root: String, val access: RealmAccess)

internal interface WorkspaceTerminalHost {
    fun prepare(context: Context, root: String): Boolean
    fun create(context: Context, root: String, client: WorkspaceTerminalSessionClient): WorkspacePtySession
}

private object AndroidWorkspaceTerminalHost : WorkspaceTerminalHost {
    override fun prepare(context: Context, root: String): Boolean {
        if (!workspaceRootfsReady(context, root)) return false
        prepareWorkspaceTerminalSession(context, root)
        return true
    }

    override fun create(
        context: Context,
        root: String,
        client: WorkspaceTerminalSessionClient,
    ): WorkspacePtySession = createWorkspaceTerminalSession(context, root, client)
}

data class WorkspaceTerminalWorkspaceState(
    val tabs: List<WorkspaceTerminalTabUiModel> = emptyList(),
    val selectedTabId: String? = null,
    val lastFailure: WorkspaceTerminalFailure? = null,
)

data class WorkspaceTerminalFailure(
    val id: String,
    val reason: WorkspaceTerminalFailureReason,
)

sealed interface WorkspaceTerminalFailureReason {
    data object NotReady : WorkspaceTerminalFailureReason
    data object Unexpected : WorkspaceTerminalFailureReason
}

data class WorkspaceTerminalTabUiModel(
    val id: String,
    val number: Int,
    val customTitle: String?,
    val readiness: WorkspaceTerminalReadiness,
)

enum class WorkspaceTerminalReadiness { PREPARING, READY, CLOSING }

sealed interface WorkspaceTerminalCreateResult {
    data class Created(val tabId: String) : WorkspaceTerminalCreateResult
    data class LimitReached(val maximum: Int) : WorkspaceTerminalCreateResult
    data object NotReady : WorkspaceTerminalCreateResult
}
