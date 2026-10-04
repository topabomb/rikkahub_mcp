package net.weero.measix.pilot.service.remoteworkspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.PlatformEnterpriseService
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import kotlin.uuid.Uuid

internal enum class RemoteWorkspaceStatus {
    CHECKING, FAILED, AVAILABLE, UNPROVISIONED, CONNECTING, RESTORING, DISCONNECTING,
    DISCONNECTED, DISABLED, DELETING, DELETED, NEEDS_ATTENTION, FILES_UNAVAILABLE, READ_FAILED,
}
internal data class RemoteWorkspaceSummary(
    val selection: RealmSelection,
    val status: RemoteWorkspaceStatus,
    val reason: String? = null,
    val diagnostic: String? = null,
    val mcpAvailable: Boolean = false,
    val mcpReason: String? = null,
) {
    val canOpenFiles: Boolean get() = status == RemoteWorkspaceStatus.AVAILABLE
}
internal data class RemoteWorkspaceQueryState(
    val selection: RealmSelection,
    val refreshing: Boolean,
    val diagnostic: String? = null,
)
internal data class RemoteFile(
    val path: String, val directory: Boolean, val size: Long?, val modifiedAt: String?, val etag: String?,
) {
    val name: String get() = path.substringAfterLast('/')
    val versioned: Boolean get() = WorkspaceFileRules.strongEtag(etag)
}
internal data class RemoteDirectory(val path: String, val files: List<RemoteFile>, val usedBytes: Long?, val availableBytes: Long?)
internal enum class RemoteOutcome { SUCCEEDED, FAILED, PARTIAL, UNKNOWN, NOT_STARTED, CANCELLED }
internal data class RemoteOperationResult(val path: String, val outcome: RemoteOutcome, val diagnostic: String? = null)
internal enum class RemoteFileAction { DELETE, MOVE, COPY }

/** A transient capability, never serialized into navigation, saved state, or a durable draft. */
internal class RemoteWorkspaceHandle internal constructor(
    internal val id: Uuid,
    internal val selection: RealmSelection,
    internal val connection: PlatformConnection,
    internal val space: String,
    internal val binding: Long,
)
internal data class RemoteTextDocument(val file: RemoteFile, val content: WorkspaceText)
internal class RemoteWorkspaceRevocation internal constructor(private val jobs: List<Job>, private val cleanup: suspend () -> Unit = {}) {
    suspend fun awaitClosed() { jobs.joinAll(); cleanup() }
}
internal interface RemoteExportDestination {
    val description: String
    fun open(): OutputStream
    fun delete(): Boolean
}

/** Owns only client requests and unpublished local copies, never the remote persistent file tree. */
internal class RemoteWorkspaceService(
    private val sessions: EnterpriseSessionController,
    private val platform: PlatformEnterpriseService,
    private val client: PlatformWorkspaceClient,
    private val recovery: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
    private val temporaryRoot: File,
) {
    private data class Identity(val selection: RealmSelection, val connection: PlatformConnection)
    private data class State(val identity: Identity, val projection: PlatformWorkspaceProjection?, val readError: String? = null)
    private data class Pending(val selection: RealmSelection, val handle: RemoteWorkspaceHandle?, val job: Job)
    private val monitor = Any()
    private val jobs = mutableSetOf<Pending>()
    private val handles = mutableSetOf<RemoteWorkspaceHandle>()
    private data class WriteTarget(val access: RealmAccess, val connection: PlatformConnection, val space: String)
    private fun RemoteWorkspaceHandle.writeTarget() = WriteTarget(selection.access, connection, space)
    private val uncertain = mutableMapOf<WriteTarget, MutableSet<String>>()
    private val verified = mutableMapOf<RemoteWorkspaceHandle, MutableSet<String>>()
    private val copies = mutableMapOf<File, RemoteWorkspaceHandle>()
    private val changingConnections = mutableSetOf<RealmAccess>()
    private var current: State? = null
    private var generation = 0L
    private var refreshing: Pair<Identity, Deferred<Unit>>? = null
    private val _summary = MutableStateFlow<RemoteWorkspaceSummary?>(null)
    val summary: StateFlow<RemoteWorkspaceSummary?> = _summary.asStateFlow()
    private val _queryState = MutableStateFlow<RemoteWorkspaceQueryState?>(null)
    val queryState = _queryState.asStateFlow()

    init {
        scope.launch {
            recovery.awaitReady()
            cleanupExpiredShares()
            combine(sessions.observeSelectedRealmSelection(), sessions.state) { selection, state ->
                val connection = (state as? EnterpriseState.Available)?.manifest?.session?.platform?.connection
                if (selection?.access is RealmAccess.Enterprise && connection != null) Identity(selection, connection) else null
            }.distinctUntilChanged().collectLatest { identity ->
                val previous = synchronized(monitor) { current?.identity }
                if (previous != identity) {
                    previous?.let { revoke(it.selection.access).awaitClosed() }
                    synchronized(monitor) { current = identity?.let { State(it, null) }; _summary.value = null; _queryState.value = null }
                }
                if (identity != null && synchronized(monitor) { identity.selection.access !in changingConnections }) {
                    try { refresh(identity.selection) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.e("RemoteWorkspace", "Selection refresh failed", error) }
                }
            }
        }
    }

    /** Called under the Session lock during switching: no suspend, IO, or re-entry to Session. */
    fun revoke(access: RealmAccess): RemoteWorkspaceRevocation = synchronized(monitor) {
        generation++
        handles.removeAll { it.selection.access == access }
        verified.keys.removeAll { it.selection.access == access }
        if (current?.identity?.selection?.access == access) { current = null; _summary.value = null; _queryState.value = null; refreshing = null }
        val captured = jobs.filter { it.selection.access == access }.map { it.job }
        captured.forEach { it.cancel() }
        RemoteWorkspaceRevocation(captured) { cleanupCopies { it.selection.access == access } }
    }

    suspend fun cancelAndAwait(access: RealmAccess) = revoke(access).awaitClosed()

    suspend fun <T> changeConnection(access: RealmAccess, change: suspend () -> T): T {
        synchronized(monitor) { check(changingConnections.add(access)) { "workspace_connection_change_in_progress" } }
        return try { cancelAndAwait(access); change() }
        finally {
            synchronized(monitor) { changingConnections -= access }
            scope.launch {
                try {
                    sessions.readPresentation().selection?.takeIf { it.access == access }?.let { refresh(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { android.util.Log.e("RemoteWorkspace", "Connection refresh failed", error) }
            }
        }
    }

    suspend fun refresh(selection: RealmSelection) {
        recovery.awaitReady()
        val identity = identity(selection)
        val task = sessions.withSelectedRealmSelection(selection) {
            synchronized(monitor) {
                check(selection.access !in changingConnections) { "workspace_connection_change_in_progress" }
                refreshing?.takeIf { it.first == identity && it.second.isActive }?.second ?: run {
                    val stamp = ++generation
                    val old = current?.takeIf { it.identity == identity }
                    current = old ?: State(identity, null)
                    _summary.value = old?.let(::project)?.copy(status = RemoteWorkspaceStatus.CHECKING)
                    _queryState.value = RemoteWorkspaceQueryState(selection, refreshing = true)
                    tracked<Unit>(selection, null) {
                        try {
                            val projection = platform.read((selection.access as RealmAccess.Enterprise).sessionId) { connection, token ->
                                check(connection == identity.connection) { "workspace_connection_changed" }
                                client.state(connection, token)
                            }
                            validate(identity)
                            synchronized(monitor) {
                                if (generation == stamp) {
                                    if (old?.projection?.let { it.agentSpaceId != projection.agentSpaceId ||
                                            it.bindingRevision != projection.bindingRevision } == true || !projection.filesAvailable) {
                                        handles.filter { it.selection == selection }.toList().forEach(::revokeHandleLocked)
                                    }
                                    current = State(identity, projection, current?.takeIf { it.identity == identity }?.readError)
                                    _summary.value = project(requireNotNull(current))
                                    _queryState.value = RemoteWorkspaceQueryState(selection, refreshing = false)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (unsupported: WorkspaceProtocolUnavailableException) {
                            // An optional feature absent from a known older Core is not an enterprise
                            // failure. Do not retain a capability across a server downgrade, or cache
                            // absence: each later refresh can discover an upgraded server.
                            validate(identity)
                            synchronized(monitor) {
                                if (generation == stamp) {
                                    handles.filter { it.selection == selection }.toList().forEach(::revokeHandleLocked)
                                    current = null
                                    _summary.value = null
                                    _queryState.value = RemoteWorkspaceQueryState(selection, refreshing = false)
                                }
                            }
                            android.util.Log.i("RemoteWorkspace", "Optional workspace protocol unavailable: ${unsupported.message}")
                        }
                        catch (error: Exception) {
                            android.util.Log.e("RemoteWorkspace", "Status query failed", error)
                            synchronized(monitor) {
                                if (generation == stamp) {
                                    val diagnostic = error.userVisibleDiagnostic()
                                    _summary.value = old?.let(::project)?.copy(status = RemoteWorkspaceStatus.FAILED, diagnostic = diagnostic)
                                    _queryState.value = RemoteWorkspaceQueryState(selection, refreshing = false, diagnostic = diagnostic)
                                }
                            }
                        }
                    }.also { task ->
                        refreshing = identity to task
                        task.invokeOnCompletion {
                            synchronized(monitor) {
                                // File failures can supersede the projection without ending this query.
                                if (refreshing?.second === task) {
                                    refreshing = null
                                    _queryState.value = _queryState.value?.takeIf { it.selection == selection }
                                        ?.copy(refreshing = false)
                                }
                            }
                        }
                        task.start()
                    }
                }
            }
        }
        task.await()
    }

    suspend fun open(selection: RealmSelection): RemoteWorkspaceHandle {
        refresh(selection)
        val identity = identity(selection)
        return sessions.withSelectedRealmSelection(selection) {
            synchronized(monitor) {
                val state = current?.takeIf { it.identity == identity } ?: error("workspace_status_unavailable")
                val projection = state.projection ?: error("workspace_status_unavailable")
                check(projection.filesAvailable) { "workspace_files_unavailable: ${projection.filesReason}" }
                check(_summary.value?.status !in setOf(RemoteWorkspaceStatus.CHECKING, RemoteWorkspaceStatus.FAILED)) {
                    "workspace_status_unavailable"
                }
                RemoteWorkspaceHandle(Uuid.random(), selection, identity.connection, requireNotNull(projection.agentSpaceId),
                    projection.bindingRevision).also { handles += it }
            }
        }
    }

    suspend fun close(handle: RemoteWorkspaceHandle) {
        val pending = synchronized(monitor) {
            revokeHandleLocked(handle)
            jobs.filter { it.handle === handle }.map { it.job }
        }
        pending.joinAll()
        cleanupCopies { it === handle }
    }

    fun closeLater(handle: RemoteWorkspaceHandle) {
        synchronized(monitor) { revokeHandleLocked(handle) }
        scope.launch {
            try { close(handle) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.e("RemoteWorkspace", "Management session cleanup failed", error) }
        }
    }

    suspend fun isValid(handle: RemoteWorkspaceHandle): Boolean = try { validate(handle); true }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: EnterpriseConfigurationException) { false }
    catch (_: IllegalStateException) { false }

    suspend fun requireNavigation(selection: RealmSelection, filesRequired: Boolean = false) {
        val identity = identity(selection)
        synchronized(monitor) {
            check(current?.identity == identity) { "workspace_identity_changed" }
            if (filesRequired) check(_summary.value?.canOpenFiles == true) { "workspace_files_unavailable" }
        }
    }

    suspend fun list(handle: RemoteWorkspaceHandle, path: String): RemoteDirectory = operation(handle, retryRejectedToken = true) { token ->
        val result = client.list(handle.connection, token, handle.space, path)
        validate(handle)
        synchronized(monitor) {
            current?.takeIf { it.identity == Identity(handle.selection, handle.connection) }?.let {
                current = it.copy(readError = null)
                if (_summary.value?.status !in setOf(RemoteWorkspaceStatus.CHECKING, RemoteWorkspaceStatus.FAILED)) {
                    _summary.value = project(requireNotNull(current))
                }
            }
        }
        RemoteDirectory(path, result.entries.map { RemoteFile(it.path, it.kind == PlatformWorkspaceFileEntryKind.DIRECTORY,
            it.size, it.modifiedAt, it.etag) }, result.usedBytes, result.availableBytes)
    }

    suspend fun mediaSource(handle: RemoteWorkspaceHandle, file: RemoteFile): net.weero.measix.pilot.service.files.MediaPreviewSource {
        val metadata = operation(handle, retryRejectedToken = true) { token ->
            client.mediaMetadata(handle.connection, token, handle.space, file.path)
        }
        return net.weero.measix.pilot.service.files.MediaPreviewSource(file.name, metadata.length, summary,
            verifyAccess = { validate(handle) },
            readRange = { position, length -> operation(handle, retryRejectedToken = true) { token ->
                client.mediaRange(handle.connection, token, handle.space, file.path, metadata, position, length)
            } },
        )
    }

    suspend fun readText(handle: RemoteWorkspaceHandle, file: RemoteFile): RemoteTextDocument = operation(handle, retryRejectedToken = true) { token ->
        val buffer = ByteArrayOutputStream()
        val metadata = client.download(handle.connection, token, handle.space, file.path, buffer, WorkspaceFileRules.TEXT_LIMIT.toLong())
        RemoteTextDocument(file.copy(etag = metadata.etag, size = metadata.length), WorkspaceText.decode(buffer.toByteArray()))
    }

    suspend fun upload(handle: RemoteWorkspaceHandle, path: String, etag: String?, length: Long?,
        input: () -> InputStream, progress: (Long, Long?) -> Unit = { _, _ -> },
    ): RemoteOperationResult = write(handle, path) { token ->
        client.upload(handle.connection, token, handle.space, path, etag, length, input, progress)
    }

    suspend fun save(handle: RemoteWorkspaceHandle, document: RemoteTextDocument, text: String,
        destination: String = document.file.path,
    ): RemoteOperationResult {
        val bytes = document.content.encode(text)
        val etag = if (destination == document.file.path) WorkspaceFileRules.requireEtag(requireNotNull(document.file.etag)) else null
        return upload(handle, destination, etag, bytes.size.toLong(), { bytes.inputStream() })
    }

    suspend fun createDirectory(handle: RemoteWorkspaceHandle, path: String): RemoteOperationResult = write(handle, path) {
        client.mutate(handle.connection, it, handle.space, PlatformWorkspaceFileMutation(PlatformWorkspaceFileMutationAction.MKCOL, path))
    }

    suspend fun mutate(handle: RemoteWorkspaceHandle, action: RemoteFileAction, file: RemoteFile,
        destination: String? = null, overwrite: RemoteFile? = null, recursiveConfirmed: Boolean = false,
    ): RemoteOperationResult {
        if (!file.directory) WorkspaceFileRules.requireEtag(requireNotNull(file.etag))
        if (action != RemoteFileAction.DELETE) {
            WorkspaceFileRules.path(requireNotNull(destination))
            require(destination != file.path && (!file.directory || !destination.startsWith(file.path + "/"))) { "workspace_invalid_destination" }
        }
        if (file.directory && action == RemoteFileAction.DELETE) require(recursiveConfirmed) { "workspace_recursive_confirmation_required" }
        if (overwrite != null) {
            require(!file.directory && !overwrite.directory && overwrite.path == destination)
            WorkspaceFileRules.requireEtag(requireNotNull(overwrite.etag))
        }
        return write(handle, destination ?: file.path) { token ->
            client.mutate(handle.connection, token, handle.space, PlatformWorkspaceFileMutation(
                action = PlatformWorkspaceFileMutationAction.valueOf(action.name), path = file.path, destination = destination,
                sourceEtag = file.etag?.takeIf(WorkspaceFileRules::strongEtag), targetEtag = overwrite?.etag,
                overwrite = overwrite != null, recursiveConfirmed = recursiveConfirmed.takeIf { file.directory },
            ))
        }
    }

    /** A read acknowledges an uncertain target only after explicit user verification, never an automatic refresh. */
    suspend fun acknowledgeVerified(handle: RemoteWorkspaceHandle, path: String) {
        validate(handle)
        synchronized(monitor) {
            check(verified[handle]?.remove(path) == true) { "workspace_read_target_before_confirmation" }
            uncertain[handle.writeTarget()]?.remove(path)
        }
    }

    fun outcomeAfterCancellation(handle: RemoteWorkspaceHandle, path: String): RemoteOutcome = synchronized(monitor) {
        if (path in uncertain[handle.writeTarget()].orEmpty()) RemoteOutcome.UNKNOWN else RemoteOutcome.CANCELLED
    }

    suspend fun verifyUnknown(handle: RemoteWorkspaceHandle, path: String): RemoteFile? {
        val parent = path.substringBeforeLast('/', "")
        refresh(handle.selection)
        val directory = list(handle, parent)
        validate(handle)
        synchronized(monitor) { verified.getOrPut(handle) { mutableSetOf() } += path }
        return directory.files.find { it.path == path }
    }

    suspend fun download(handle: RemoteWorkspaceHandle, file: RemoteFile, output: () -> OutputStream,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ) = operation(handle) { token ->
        withContext(Dispatchers.IO) {
            withOutput(output) { sink -> client.download(handle.connection, token, handle.space, file.path, sink, progress = progress) }
        }
    }

    suspend fun export(handle: RemoteWorkspaceHandle, file: RemoteFile, destination: RemoteExportDestination,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ) = exportDestination(handle, file, destination, { destination }, progress)

    /** Accept an already-created SAF document synchronously, before its UI owner can disappear. */
    fun acceptExport(handle: RemoteWorkspaceHandle, file: RemoteFile, destination: RemoteExportDestination,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ): Deferred<Unit> = synchronized(monitor) {
        tracked(handle.selection, handle) { export(handle, file, destination, progress) }
    }

    suspend fun exportCreated(handle: RemoteWorkspaceHandle, file: RemoteFile,
        create: () -> RemoteExportDestination, progress: (Long, Long?) -> Unit = { _, _ -> },
    ) = exportDestination(handle, file, null, create, progress)

    fun discardExport(destination: RemoteExportDestination, onFailure: (Throwable) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { check(destination.delete()) { "workspace_incomplete_export_remains: ${destination.description}" } }
                catch (error: Exception) { onFailure(error) }
            }
        }
    }

    private suspend fun exportDestination(handle: RemoteWorkspaceHandle, file: RemoteFile,
        initial: RemoteExportDestination?, create: () -> RemoteExportDestination, progress: (Long, Long?) -> Unit,
    ) {
        var owned = initial
        operation(handle, onFailure = { error ->
            withContext(NonCancellable + Dispatchers.IO) {
                try { owned?.let { if (!it.delete()) throw IOException("workspace_incomplete_export_remains: ${it.description}") } }
                catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
        }) { token ->
            withContext(Dispatchers.IO) {
                if (owned == null) owned = workspaceLocalIO(create)
                currentCoroutineContext().ensureActive()
                validate(handle)
                withOutput(requireNotNull(owned)::open) { sink ->
                    client.download(handle.connection, token, handle.space, file.path, sink, progress = progress)
                }
            }
        }
    }

    suspend fun previewCopy(handle: RemoteWorkspaceHandle, file: RemoteFile, maxBytes: Long = WorkspaceFileRules.PREVIEW_LIMIT): File {
        var owned: File? = null
        return operation(handle, onFailure = { error ->
            withContext(NonCancellable + Dispatchers.IO) {
                owned?.let { copy ->
                    if (copy.exists() && !copy.delete()) error.addSuppressed(IOException("workspace_temporary_cleanup_failed: $copy"))
                    synchronized(monitor) { if (!copy.exists()) copies -= copy }
                }
            }
        }) { token ->
            withContext(Dispatchers.IO) {
                temporaryRoot.mkdirs()
                val copy = File(temporaryRoot, "preview-${handle.id}-${Uuid.random()}")
                owned = copy
                synchronized(monitor) { copies[copy] = handle }
                withOutput(copy::outputStream) { client.download(handle.connection, token, handle.space, file.path, it, maxBytes) }
                validate(handle)
                copy
            }
        }
    }

    suspend fun releaseCopy(copy: File) = withContext(Dispatchers.IO) {
        require(copy.parentFile?.canonicalFile == temporaryRoot.canonicalFile && copy.name.startsWith("preview-"))
        if (copy.exists() && !copy.delete()) throw IOException("workspace_temporary_cleanup_failed: $copy")
        synchronized(monitor) { copies -= copy }
    }

    /** The callback is the system handoff boundary; returned shares remain readable for 24 hours. */
    suspend fun share(handle: RemoteWorkspaceHandle, file: RemoteFile, deliver: (File) -> Unit) {
        operation(handle) { token ->
            var owned: File? = null
            try {
                withContext(Dispatchers.IO) {
                    temporaryRoot.mkdirs()
                    // Keep a short private name but preserve a safe extension: FileProvider MIME
                    // detection uses the actual filename, independently of its displayName.
                    val extension = file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                        .takeIf { it.matches(Regex("[a-z0-9]{1,16}")) }
                    owned = File(temporaryRoot, "share-${Uuid.random()}${extension?.let { ".$it" }.orEmpty()}")
                    val copy = requireNotNull(owned)
                    synchronized(monitor) { copies[copy] = handle }
                    withOutput(copy::outputStream) { client.download(handle.connection, token, handle.space, file.path, it) }
                }
                validate(handle)
                sessions.withSelectedRealmSelection(handle.selection) {
                    synchronized(monitor) { requireHandleLocked(handle) }
                    deliver(requireNotNull(owned))
                    synchronized(monitor) { copies -= owned }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    owned?.let { copy ->
                        if (copy.exists() && !copy.delete()) error.addSuppressed(IOException("workspace_share_cleanup_failed: $copy"))
                        synchronized(monitor) { if (!copy.exists()) copies -= copy }
                    }
                }
                throw error
            }
        }
    }

    fun imageSource(handle: RemoteWorkspaceHandle, file: RemoteFile, maxBytes: Long = net.weero.measix.pilot.service.workspace.MAX_WORKSPACE_IMAGE_BYTES.toLong()) =
        net.weero.measix.pilot.service.ImageSource(
            cacheIdentity = "remote:${handle.id}:${file.path}:${file.etag ?: Uuid.random()}",
            origin = net.weero.measix.pilot.service.ImageOrigin.NETWORK, displayName = file.name,
            gallerySaveSupported = net.weero.measix.pilot.service.workspace.workspaceImageGallerySaveSupported(file.name),
            mimeHint = if (file.name.endsWith(".svg", ignoreCase = true)) "image/svg+xml" else null,
            modifiedAtMillis = file.modifiedAt?.let { value ->
                try { java.time.Instant.parse(value).toEpochMilli() } catch (_: java.time.DateTimeException) { null }
            },
            verifyAccess = { validate(handle) },
            readPayload = {
                operation(handle, retryRejectedToken = true) { token ->
                    val buffer = ByteArrayOutputStream()
                    client.download(handle.connection, token, handle.space, file.path, buffer, maxBytes)
                    buffer.toByteArray().also { bytes ->
                        net.weero.measix.pilot.service.workspace.validateWorkspaceImage(bytes, file.name)
                    }
                }
            },
        )

    private suspend fun <T> withOutput(open: () -> OutputStream, use: suspend (OutputStream) -> T): T {
        val output = workspaceLocalIO(open)
        var failure: Throwable? = null
        try { return use(output) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { workspaceLocalIO { output.close() } }
            catch (error: Throwable) { if (failure != null) failure.addSuppressed(error) else throw error }
        }
    }

    private suspend fun cleanupCopies(matches: (RemoteWorkspaceHandle) -> Boolean) = withContext(Dispatchers.IO) {
        val owned = synchronized(monitor) { copies.filterValues(matches).keys.toList() }
        var failure: IOException? = null
        owned.forEach { file ->
            if (file.exists() && !file.delete()) {
                val error = IOException("workspace_temporary_cleanup_failed: $file")
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } else synchronized(monitor) { copies -= file }
        }
        failure?.let { throw it }
    }

    suspend fun cleanupExpiredShares() = withContext(Dispatchers.IO) {
        temporaryRoot.listFiles()?.filter {
            synchronized(monitor) { it !in copies } && (it.name.startsWith("preview-") ||
                (it.name.startsWith("share-") && System.currentTimeMillis() - it.lastModified() >= 86_400_000))
        }?.forEach {
            if (!it.delete()) android.util.Log.w("RemoteWorkspace", "Expired share cleanup failed: $it")
        }
    }

    private suspend fun write(handle: RemoteWorkspaceHandle, path: String,
        send: suspend (String) -> PlatformWorkspaceFileResult,
    ): RemoteOperationResult {
        var sent = false
        var confirmedRejection = false
        return try {
            operation(handle, writing = true) { token ->
                if (synchronized(monitor) { path in uncertain[handle.writeTarget()].orEmpty() }) {
                    return@operation RemoteOperationResult(path, RemoteOutcome.UNKNOWN, "workspace_verify_unknown_result_first")
                }
                sent = true
                val result = try {
                    send(token)
                } catch (error: PlatformHttpException) {
                    // Credential recovery may be cancelled after Core has already confirmed this rejection.
                    confirmedRejection = !unknownWriteFailure(error)
                    throw error
                }
                val outcome = RemoteOutcome.valueOf(result.outcome.name)
                if (outcome == RemoteOutcome.UNKNOWN) synchronized(monitor) { uncertain.getOrPut(handle.writeTarget()) { mutableSetOf() } += path }
                RemoteOperationResult(path, outcome, result.failures.joinToString("\n") { "${it.path}: HTTP ${it.status} ${it.code}" }
                    .let { if (result.truncated) "$it\nworkspace_failure_details_truncated" else it }.ifBlank { null })
            }
        } catch (error: Exception) {
            val unknown = sent && !confirmedRejection && unknownWriteFailure(error)
            if (unknown) synchronized(monitor) { uncertain.getOrPut(handle.writeTarget()) { mutableSetOf() } += path }
            if (error is CancellationException) throw error
            android.util.Log.e("RemoteWorkspace", "File write failed", error)
            RemoteOperationResult(path, if (unknown) RemoteOutcome.UNKNOWN else RemoteOutcome.FAILED, error.userVisibleDiagnostic())
        }
    }

    private fun unknownWriteFailure(error: Throwable): Boolean = error !is PlatformHttpException ||
        error.problem?.code == "workspace_result_unknown" ||
        error.status !in setOf(400, 401, 403, 404, 409, 412, 413, 422, 423, 429, 507)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> operation(handle: RemoteWorkspaceHandle, writing: Boolean = false,
        retryRejectedToken: Boolean = false, onFailure: suspend (Throwable) -> Unit = {}, action: suspend (String) -> T,
    ): T {
        val cleaned = AtomicBoolean(false)
        suspend fun cleanup(error: Throwable) {
            if (cleaned.compareAndSet(false, true)) {
                try { onFailure(error) }
                catch (failure: Throwable) { error.addSuppressed(failure) }
            }
        }
        try {
            val pending = synchronized(monitor) {
                // Registration precedes admission, so waiting for a foreground refresh still belongs
                // to the original handle and its owned SAF resources participate in revocation.
                tracked(handle.selection, handle) {
                    val access = handle.selection.access as RealmAccess.Enterprise
                    var lease: EnterprisePlatformOperationLease? = null
                    try {
                        currentCoroutineContext().ensureActive()
                        awaitAdmission(handle, writing)
                        lease = sessions.capturePlatformOperation(access.sessionId)
                        check(lease.access == access && lease.context.platform.connection == handle.connection) { "workspace_identity_changed" }
                        validate(handle)
                        val token = platform.accessToken(access.sessionId, handle.connection)
                        validate(handle)
                        val result = try {
                            action(token.value)
                        } catch (error: PlatformHttpException) {
                            if (error.status != 401 || error.problem?.code != "invalid_credential") throw error
                            val replacement = try {
                                platform.recoverWorkspaceAccessToken(access, handle.connection, token)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (recoveryFailure: Exception) {
                                error.addSuppressed(recoveryFailure)
                                throw error
                            }
                            validate(handle)
                            // Streaming reads own SAF/output resources, so only calls with fresh resources may retry.
                            if (writing || !retryRejectedToken) throw error
                            action(replacement.value)
                        }
                        validate(handle)
                        result
                    } catch (error: Throwable) {
                        cleanup(error)
                        if (error is PlatformHttpException) {
                            platform.workspaceFailure(access, error)
                            markReadFailure(handle, error)
                        } else if (!writing && error is IOException && error !is WorkspaceLocalIOException) markConnectionFailure(handle, error)
                        throw error
                    } finally { lease?.release() }
                }.also { it.start() }
            }
            return try { pending.await() }
            catch (error: Throwable) {
                if (!pending.isCompleted) withContext(NonCancellable) { pending.cancelAndJoin() }
                generateSequence(pending.getCompletionExceptionOrNull()) { it.cause }.flatMap { it.suppressed.asSequence() }
                    .filter { it !== error && it !in error.suppressed }.forEach(error::addSuppressed)
                throw error
            }
        } catch (error: Throwable) {
            cleanup(error)
            throw error
        }
    }

    private suspend fun awaitAdmission(handle: RemoteWorkspaceHandle, writing: Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val barrier = sessions.withSelectedRealmSelection(handle.selection) {
                synchronized(monitor) {
                    requireHandleLocked(handle)
                    if (_summary.value?.status == RemoteWorkspaceStatus.CHECKING) {
                        val refresh = refreshing?.takeIf {
                            it.first == Identity(handle.selection, handle.connection)
                        }?.second
                        check(refresh != null && !refresh.isCompleted) { "workspace_status_unavailable" }
                        refresh
                    } else {
                        check(_summary.value?.status != RemoteWorkspaceStatus.FAILED) {
                            listOfNotNull("workspace_status_unavailable", _summary.value?.diagnostic).joinToString("\n")
                        }
                        check(!writing || current?.readError == null) { "workspace_verify_file_access_first" }
                        null
                    }
                }
            } ?: return
            // Never hold either owner lock while waiting. Recheck after completion because a newer
            // refresh, a failed query, or a space/Session change can supersede this barrier.
            barrier.await()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> tracked(selection: RealmSelection, handle: RemoteWorkspaceHandle?, action: suspend () -> T): Deferred<T> {
        lateinit var record: Pending
        // Once registered, cleanup belongs to this Job even if cancellation precedes its first dispatch.
        val task = scope.async(start = CoroutineStart.ATOMIC) { action() }
        record = Pending(selection, handle, task)
        jobs += record
        task.invokeOnCompletion { synchronized(monitor) { jobs -= record } }
        return task
    }

    private suspend fun identity(selection: RealmSelection): Identity {
        sessions.withSelectedRealmSelection(selection) {}
        val access = selection.access as? RealmAccess.Enterprise ?: error("workspace_enterprise_required")
        return Identity(selection, sessions.platformContext(access.sessionId).platform.connection)
    }

    private suspend fun validate(identity: Identity) {
        check(this.identity(identity.selection) == identity) { "workspace_connection_changed" }
    }

    private suspend fun validate(handle: RemoteWorkspaceHandle) {
        validate(Identity(handle.selection, handle.connection))
        synchronized(monitor) { requireHandleLocked(handle) }
    }

    private fun requireHandleLocked(handle: RemoteWorkspaceHandle) {
        check(handle in handles) { "workspace_access_revoked" }
        check(handle.selection.access !in changingConnections) { "workspace_connection_change_in_progress" }
        val published = (sessions.state.value as? EnterpriseState.Available)?.manifest?.session?.platform?.connection
        check(published == handle.connection) { "workspace_connection_changed" }
        val state = current ?: error("workspace_access_revoked")
        check(state.identity == Identity(handle.selection, handle.connection) && state.projection?.filesAvailable == true &&
            state.projection.agentSpaceId == handle.space && state.projection.bindingRevision == handle.binding) { "workspace_target_changed" }
    }

    private fun revokeHandleLocked(handle: RemoteWorkspaceHandle) {
        handles -= handle; verified -= handle
        jobs.filter { it.handle === handle }.forEach { it.job.cancel() }
    }

    private fun markReadFailure(handle: RemoteWorkspaceHandle, error: PlatformHttpException) = synchronized(monitor) {
        val state = current ?: return@synchronized
        val projection = state.projection ?: return@synchronized
        if (handle !in handles || state.identity != Identity(handle.selection, handle.connection) ||
            projection.agentSpaceId != handle.space || projection.bindingRevision != handle.binding) return@synchronized
        if (error.problem?.code == "workspace_space_mismatch") {
            generation++; revokeHandleLocked(handle)
            current = current?.copy(projection = null)
            _summary.value = RemoteWorkspaceSummary(handle.selection, RemoteWorkspaceStatus.FAILED, diagnostic = error.userVisibleDiagnostic())
        } else if (error.status == 503 && error.problem?.code != "workspace_result_unknown") {
            generation++
            current = current?.copy(readError = error.userVisibleDiagnostic())
            current?.let { _summary.value = project(it) }
        }
    }

    private fun markConnectionFailure(handle: RemoteWorkspaceHandle, error: IOException) = synchronized(monitor) {
        if (handle !in handles) return@synchronized
        generation++
        current = current?.copy(readError = error.userVisibleDiagnostic())
        current?.let { _summary.value = project(it) }
    }

    private fun project(state: State): RemoteWorkspaceSummary? {
        val p = state.projection ?: return null
        if (p.agentSpaceId == null || p.state == PlatformWorkspaceProjectionState.DELETED ||
            p.state == PlatformWorkspaceProjectionState.UNPROVISIONED) return null
        val status = when {
            p.state == PlatformWorkspaceProjectionState.DELETING -> RemoteWorkspaceStatus.DELETING
            p.state == PlatformWorkspaceProjectionState.NEEDS_ATTENTION -> RemoteWorkspaceStatus.NEEDS_ATTENTION
            p.serviceState != PlatformWorkspaceProjectionServiceState.ENABLED -> RemoteWorkspaceStatus.DISABLED
            p.state != PlatformWorkspaceProjectionState.CONNECTED -> RemoteWorkspaceStatus.valueOf(p.state.name)
            !p.filesAvailable -> RemoteWorkspaceStatus.FILES_UNAVAILABLE
            state.readError != null -> RemoteWorkspaceStatus.READ_FAILED
            else -> RemoteWorkspaceStatus.AVAILABLE
        }
        return RemoteWorkspaceSummary(state.identity.selection, status, p.filesReason, state.readError, p.mcpAvailable, p.mcpReason)
    }
}
