package net.weero.measix.pilot.service.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.AppScope

/** Application-scoped owner of every interactive Workspace PTY and its tab lifecycle. */
class WorkspaceTerminalRuntime internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val terminalHost: WorkspaceTerminalHost,
) {
    constructor(context: Context, appScope: AppScope) : this(context, appScope, AndroidWorkspaceTerminalHost)

    private val context = context.applicationContext
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, TerminalEntry>()
    private val liveSessions = ConcurrentHashMap<String, LiveTerminal>()
    private val creationJobs = mutableMapOf<String, Job>()
    private val mutableWorkspaces = MutableStateFlow<Map<String, WorkspaceTerminalWorkspaceState>>(emptyMap())
    val workspaces: StateFlow<Map<String, WorkspaceTerminalWorkspaceState>> = mutableWorkspaces.asStateFlow()

    suspend fun create(
        root: String,
        prepareUnderCommandGate: suspend (suspend () -> Boolean) -> Boolean,
    ): WorkspaceTerminalCreateResult {
        val tabId = UUID.randomUUID().toString()
        mutex.withLock {
            val count = entries.values.count { it.root == root }
            if (count >= MAX_TABS_PER_WORKSPACE) {
                return WorkspaceTerminalCreateResult.LimitReached(MAX_TABS_PER_WORKSPACE)
            }
            val nextNumber = (entries.values.filter { it.root == root }.maxOfOrNull { it.number } ?: 0) + 1
            entries[tabId] = TerminalEntry(
                id = tabId,
                root = root,
                number = nextNumber,
                customTitle = null,
                readiness = WorkspaceTerminalReadiness.PREPARING,
            )
            selectLocked(root, tabId)
            publishLocked(root)
            val creationJob = appScope.launch(start = CoroutineStart.LAZY) {
                createSession(tabId, prepareUnderCommandGate)
            }
            creationJobs[tabId] = creationJob
            creationJob.start()
        }
        return WorkspaceTerminalCreateResult.Created(tabId)
    }

    suspend fun select(root: String, tabId: String) = mutex.withLock {
        if (entries[tabId]?.root != root) return@withLock
        selectLocked(root, tabId)
        publishLocked(root)
    }

    suspend fun rename(root: String, tabId: String, title: String) = mutex.withLock {
        val entry = entries[tabId]?.takeIf { it.root == root } ?: return@withLock
        entry.customTitle = title.trim().take(MAX_TITLE_LENGTH).ifBlank { null }
        publishLocked(root)
    }

    suspend fun reorder(root: String, orderedIds: List<String>) = mutex.withLock {
        val current = entries.values.filter { it.root == root }
        if (orderedIds.toSet() != current.mapTo(linkedSetOf()) { it.id }) return@withLock
        val byId = current.associateBy { it.id }
        val other = entries.values.filterNot { it.root == root }
        entries.clear()
        other.forEach { entries[it.id] = it }
        orderedIds.forEach { id -> entries[id] = requireNotNull(byId[id]) }
        publishLocked(root)
    }

    suspend fun close(root: String, tabId: String) {
        val resources = mutex.withLock { removeLocked(root, tabId) } ?: return
        withContext(NonCancellable) {
            resources.creationJob?.cancelAndJoin()
            withContext(Dispatchers.Main.immediate) { resources.session?.finishIfRunning() }
        }
    }

    suspend fun closeWorkspace(root: String) {
        val resources = mutex.withLock {
            entries.values.filter { it.root == root }.mapNotNull { removeLocked(root, it.id) }.also {
                mutableWorkspaces.value = mutableWorkspaces.value - root
            }
        }
        withContext(NonCancellable) {
            resources.forEach { it.creationJob?.cancelAndJoin() }
            withContext(Dispatchers.Main.immediate) {
                resources.forEach { it.session?.finishIfRunning() }
            }
        }
    }

    fun bind(tabId: String, view: TerminalView): Boolean {
        val live = liveSessions[tabId] ?: return false
        live.client.terminalView = view
        view.attachSession(live.session)
        view.onScreenUpdated()
        return true
    }

    fun unbind(tabId: String, view: TerminalView) {
        liveSessions[tabId]?.client?.let { client ->
            if (client.terminalView === view) client.terminalView = null
        }
    }

    fun write(tabId: String, text: String) {
        val session = liveSessions[tabId]?.session ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    private suspend fun createSession(
        tabId: String,
        prepareUnderCommandGate: suspend (suspend () -> Boolean) -> Boolean,
    ) {
        var created: TerminalSession? = null
        try {
            val root = mutex.withLock { entries[tabId]?.root } ?: return
            val ready = prepareUnderCommandGate {
                runInterruptible(Dispatchers.IO) {
                    terminalHost.prepare(context, root)
                }
            }
            if (!ready) {
                mutex.withLock {
                    if (entries[tabId]?.root == root) {
                        removeLocked(root, tabId)
                        publishFailureLocked(root, WorkspaceTerminalFailureReason.NotReady)
                    }
                }
                return
            }
            val client = WorkspaceTerminalSessionClient(context) {
                appScope.launch { removeAfterShellExit(root, tabId) }
            }
            created = withContext(Dispatchers.Main.immediate) {
                terminalHost.create(context, root, client)
            }
            val retained = mutex.withLock {
                val entry = entries[tabId]
                if (entry == null) {
                    false
                } else {
                    entry.session = created
                    entry.readiness = WorkspaceTerminalReadiness.READY
                    liveSessions[tabId] = LiveTerminal(created, client)
                    creationJobs.remove(tabId)
                    publishLocked(root)
                    true
                }
            }
            if (!retained) withContext(NonCancellable + Dispatchers.Main.immediate) { created.finishIfRunning() }
        } catch (cancelled: CancellationException) {
            created?.let {
                withContext(NonCancellable + Dispatchers.Main.immediate) { it.finishIfRunning() }
            }
            throw cancelled
        } catch (error: Exception) {
            created?.let {
                withContext(NonCancellable + Dispatchers.Main.immediate) { it.finishIfRunning() }
            }
            mutex.withLock {
                entries[tabId]?.root?.let { root ->
                    removeLocked(root, tabId)
                    Log.w(TAG, "Failed to create terminal for workspace root=$root", error)
                    publishFailureLocked(root, WorkspaceTerminalFailureReason.Unexpected)
                }
            }
        }
    }

    private suspend fun removeAfterShellExit(root: String, tabId: String) {
        mutex.withLock { removeLocked(root, tabId) }
    }

    private fun removeLocked(root: String, tabId: String): TerminalResources? {
        val entry = entries[tabId]?.takeIf { it.root == root } ?: return null
        entries.remove(tabId)
        liveSessions.remove(tabId)
        val job = creationJobs.remove(tabId)
        val remaining = entries.values.filter { it.root == root }
        val selected = mutableWorkspaces.value[root]?.selectedTabId
        val replacement = if (selected == tabId) remaining.lastOrNull()?.id else selected
        publishLocked(root, replacement)
        return TerminalResources(job, entry.session)
    }

    private fun selectLocked(root: String, tabId: String) {
        val previous = mutableWorkspaces.value[root]
        mutableWorkspaces.value = mutableWorkspaces.value + (
            root to (previous ?: WorkspaceTerminalWorkspaceState()).copy(selectedTabId = tabId)
        )
    }

    private fun publishLocked(root: String, selectedOverride: String? = mutableWorkspaces.value[root]?.selectedTabId) {
        val tabs = entries.values.filter { it.root == root }.map { entry ->
            WorkspaceTerminalTabUiModel(
                id = entry.id,
                number = entry.number,
                customTitle = entry.customTitle,
                readiness = entry.readiness,
            )
        }
        mutableWorkspaces.value = if (tabs.isEmpty()) {
            mutableWorkspaces.value - root
        } else {
            mutableWorkspaces.value + (
                root to WorkspaceTerminalWorkspaceState(
                    tabs = tabs,
                    selectedTabId = selectedOverride?.takeIf { selected -> tabs.any { it.id == selected } }
                        ?: tabs.last().id,
                )
            )
        }
    }

    private fun publishFailureLocked(root: String, reason: WorkspaceTerminalFailureReason) {
        val current = mutableWorkspaces.value[root] ?: WorkspaceTerminalWorkspaceState()
        mutableWorkspaces.value = mutableWorkspaces.value + (
            root to current.copy(
                lastFailure = WorkspaceTerminalFailure(
                    id = UUID.randomUUID().toString(),
                    reason = reason,
                ),
            )
        )
    }

    private data class TerminalEntry(
        val id: String,
        val root: String,
        val number: Int,
        var customTitle: String?,
        var readiness: WorkspaceTerminalReadiness,
        var session: TerminalSession? = null,
    )

    private data class TerminalResources(val creationJob: Job?, val session: TerminalSession?)

    private data class LiveTerminal(
        val session: TerminalSession,
        val client: WorkspaceTerminalSessionClient,
    )

    companion object {
        const val MAX_TABS_PER_WORKSPACE = 6
        private const val MAX_TITLE_LENGTH = 48
        private const val TAG = "WorkspaceTerminalRuntime"
    }
}

internal interface WorkspaceTerminalHost {
    fun prepare(context: Context, root: String): Boolean
    fun create(context: Context, root: String, client: WorkspaceTerminalSessionClient): TerminalSession
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
    ): TerminalSession = createWorkspaceTerminalSession(context, root, client)
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

enum class WorkspaceTerminalReadiness { PREPARING, READY }

sealed interface WorkspaceTerminalCreateResult {
    data class Created(val tabId: String) : WorkspaceTerminalCreateResult
    data class LimitReached(val maximum: Int) : WorkspaceTerminalCreateResult
    data object NotReady : WorkspaceTerminalCreateResult
}
